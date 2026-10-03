package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import com.bergerkiller.bukkit.tc.SignActionHeader;
import com.bergerkiller.bukkit.tc.controller.components.RailPiece;
import com.bergerkiller.bukkit.tc.controller.components.RailState;
import com.bergerkiller.bukkit.tc.rails.RailLookup;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.TrainCartsRailBlockAccess;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.GraphSignParsers;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.StopMarkSign;

/**
 * 各车站股道上的停车位置标：按车站牌子所在的轨道沿股道找出（见 {@link StopMarks.ScanJob}），结果缓存。
 *
 * <p>长站台沿股道要走几百段轨道，一次走完会卡主线程，所以平时在后台分片走：每 tick 只用 {@link #TICK_BUDGET_NANOS}，
 * 车来之前就走完——已加载区块里的车站定期排队，缓存快过期时提前重走，建、拆停车位置标或节点牌子后已知车站全部重走，驾驶员开向下一站时也会排队。
 * 车头压到车站牌子时站台必须马上知道停在哪儿：这时还没走完的才当场走完。
 *
 * <p>除 {@link #invalidate()} 外只在服务器主线程使用。
 */
public final class StopMarkIndex {

  /** 结果最多用这么久（毫秒）：改轨道（加减道岔）不会通知这里，过期后重走。 */
  static final long CACHE_MILLIS = 300_000L;

  /** 结果用了这么久（毫秒）就在后台提前重走，正常运行时不会等到过期。 */
  static final long REFRESH_MILLIS = 240_000L;

  /** 沿途还有区块没加载时，结果只用这么久（毫秒）：区块加载后很快就能找全。 */
  static final long INCOMPLETE_CACHE_MILLIS = 5_000L;

  /** 每 tick 后台扫描最多用这么久（纳秒）。 */
  static final long TICK_BUDGET_NANOS = 1_000_000L;

  /** 后台扫描每次推进多少段轨道，推进一次后看一次时间。 */
  static final int RAILS_PER_SLICE = 8;

  /** 每隔这么多 tick 把已加载区块里的车站排队一次。 */
  static final int WARM_INTERVAL_TICKS = 100;

  private record Key(UUID world, RailBlockPos rail) {}

  private record Entry(StopMarks.Scan scan, long atMillis) {}

  /** 排着队、分片推进中的一次扫描。 */
  private static final class Pending {
    private final Key key;
    private final StopMarks.ScanJob job;
    private final long generation;
    private boolean failed;

    private Pending(Key key, StopMarks.ScanJob job, long generation) {
      this.key = key;
      this.job = job;
      this.generation = generation;
    }
  }

  private final Function<Block, StopMarks.ScanJob> jobs;
  private final LongSupplier millis;
  private final LongSupplier nanos;

  /** {@link #invalidate()} 可能在异步线程里调用，缓存用并发 map。 */
  private final Map<Key, Entry> cache = new ConcurrentHashMap<>();

  private final Map<Key, Block> known = new HashMap<>();
  private final Map<Key, Pending> pending = new LinkedHashMap<>();
  private final AtomicLong generation = new AtomicLong();
  private long seenGeneration;
  private int ticks;
  private Supplier<? extends Collection<Block>> warmSource = List::of;

  public StopMarkIndex() {
    this(StopMarkIndex::jobFor, System::currentTimeMillis, System::nanoTime);
  }

  StopMarkIndex(Function<Block, StopMarks.ScanJob> jobs, LongSupplier millis, LongSupplier nanos) {
    this.jobs = Objects.requireNonNull(jobs, "jobs");
    this.millis = Objects.requireNonNull(millis, "millis");
    this.nanos = Objects.requireNonNull(nanos, "nanos");
  }

  /** 设置定期排队的车站来源：返回已加载区块里各车站牌子所在的轨道。 */
  public void setWarmSource(Supplier<? extends Collection<Block>> source) {
    this.warmSource = source == null ? List::of : source;
  }

  /**
   * 车站牌子所在轨道那条股道上的停车位置标，马上要结果时用（站台在车头压牌时）：缓存里有就用，没有才当场走完。
   *
   * @return 区块未加载或找不到轨道时为空
   */
  public List<StopMarks.Mark> around(Block stationRail) {
    if (stationRail == null) {
      return List.of();
    }
    Key key = keyOf(stationRail);
    known.put(key, stationRail);
    long now = millis.getAsLong();
    Entry entry = cache.get(key);
    if (entry != null) {
      long age = now - entry.atMillis();
      if (entry.scan().complete()) {
        if (age >= REFRESH_MILLIS) {
          enqueue(key, stationRail);
        }
        if (age < CACHE_MILLIS) {
          return entry.scan().marks();
        }
      } else if (age < INCOMPLETE_CACHE_MILLIS) {
        return entry.scan().marks();
      }
    }
    Pending queued = pending.remove(key);
    Pending current =
        queued != null && queued.generation == generation.get()
            ? queued
            : new Pending(key, jobs.apply(stationRail), generation.get());
    stepSafely(current, Integer.MAX_VALUE);
    return finish(current, now).marks();
  }

  /**
   * 只看缓存，不阻塞（驾驶员进站前的距离估计与停车标用）：没有或该更新时排队去找，这一刻先按已有的（没有时为空）。
   *
   * @return 还没找过时为空
   */
  public Optional<List<StopMarks.Mark>> cached(Block stationRail) {
    if (stationRail == null) {
      return Optional.empty();
    }
    Key key = keyOf(stationRail);
    known.put(key, stationRail);
    Entry entry = cache.get(key);
    if (entry == null || stale(entry, millis.getAsLong())) {
      enqueue(key, stationRail);
    }
    return entry == null ? Optional.empty() : Optional.of(entry.scan().marks());
  }

  /** 排队在后台找这一站；已有新鲜结果或已在排队时什么也不做。 */
  public void prefetch(Block stationRail) {
    if (stationRail == null) {
      return;
    }
    Key key = keyOf(stationRail);
    known.put(key, stationRail);
    Entry entry = cache.get(key);
    if (entry == null || stale(entry, millis.getAsLong())) {
      enqueue(key, stationRail);
    }
  }

  /**
   * 按节数与行进方向选出车头该停的标志（见 {@link StopMarks#select}）；需要时当场走完，见 {@link #around}。
   *
   * @param direction 列车在车站股道上的行进方向（见 {@link StopMarks#orient}）
   */
  public Optional<StopMarks.Selected> select(
      Block stationRail, Vector stationPoint, Vector direction, int carriages) {
    if (stationPoint == null || direction == null) {
      return Optional.empty();
    }
    return StopMarks.select(around(stationRail), stationPoint, direction, carriages);
  }

  /** 同 {@link #select}，但只看缓存、不阻塞，见 {@link #cached}。 */
  public Optional<StopMarks.Selected> selectCached(
      Block stationRail, Vector stationPoint, Vector direction, int carriages) {
    if (stationPoint == null || direction == null) {
      return Optional.empty();
    }
    return cached(stationRail)
        .flatMap(marks -> StopMarks.select(marks, stationPoint, direction, carriages));
  }

  /** 清空缓存：停车位置标或节点牌子建好、拆掉时调用。可在任意线程调用；已知车站在下一 tick 起于后台重走。 */
  public void invalidate() {
    cache.clear();
    generation.incrementAndGet();
  }

  /** 每 tick 调用一次：在时间预算内推进排队的扫描。 */
  public void tick() {
    long current = generation.get();
    if (current != seenGeneration) {
      seenGeneration = current;
      pending.clear();
      known.forEach(this::enqueue);
    }
    if (++ticks % WARM_INTERVAL_TICKS == 0) {
      for (Block rail : warmSource.get()) {
        prefetch(rail);
      }
    }
    long started = nanos.getAsLong();
    while (!pending.isEmpty()) {
      Pending next = pending.values().iterator().next();
      if (stepSafely(next, RAILS_PER_SLICE)) {
        pending.remove(next.key);
        finish(next, millis.getAsLong());
      }
      if (nanos.getAsLong() - started >= TICK_BUDGET_NANOS) {
        return;
      }
    }
  }

  /** 排队与推进中的扫描数（诊断用）。 */
  int pendingCount() {
    return pending.size();
  }

  private void enqueue(Key key, Block stationRail) {
    if (!pending.containsKey(key)) {
      pending.put(key, new Pending(key, jobs.apply(stationRail), generation.get()));
    }
  }

  private static boolean stale(Entry entry, long now) {
    long age = now - entry.atMillis();
    return entry.scan().complete() ? age >= REFRESH_MILLIS : age >= INCOMPLETE_CACHE_MILLIS;
  }

  /** 推进一次；出错（TrainCarts 版本不同或轨道正在变化）时当作走完、没有标志。 */
  private static boolean stepSafely(Pending pending, int rails) {
    try {
      return pending.job.step(rails);
    } catch (RuntimeException | LinkageError ex) {
      pending.failed = true;
      return true;
    }
  }

  /** 走完的结果写进缓存；走的过程中缓存被作废过的不写（那时读到的牌子可能已经变了）。 */
  private StopMarks.Scan finish(Pending done, long now) {
    StopMarks.Scan scan = done.failed ? new StopMarks.Scan(List.of(), false) : done.job.result();
    if (done.generation == generation.get()) {
      cache.put(done.key, new Entry(scan, now));
    }
    return scan;
  }

  private static Key keyOf(Block rail) {
    return new Key(
        rail.getWorld().getUID(), new RailBlockPos(rail.getX(), rail.getY(), rail.getZ()));
  }

  /**
   * 车站牌子所在的轨道（TrainCarts 认的那一段）；TCCoasters 的虚拟牌子没有实体牌子方块，按注册位置上的轨道取。
   *
   * @return 区块未加载或找不到轨道时为空
   */
  public static Optional<RailPiece> stationRailOf(World world, int x, int y, int z) {
    if (world == null || !world.isChunkLoaded(x >> 4, z >> 4)) {
      return Optional.empty();
    }
    try {
      Block registered = world.getBlockAt(x, y, z);
      RailPiece piece = RailLookup.discoverRailPieceFromSign(registered);
      if (piece == null || piece.isNone()) {
        piece = RailPiece.create(registered);
      }
      return piece == null || piece.isNone() ? Optional.empty() : Optional.of(piece);
    } catch (RuntimeException | LinkageError ex) {
      return Optional.empty();
    }
  }

  /** 沿车站股道收集标志的扫描。轨道与牌子都经 TrainCarts 读取，TCCoasters 的轨道与虚拟牌子同样适用；距离按轨道实际长度量。 */
  private static StopMarks.ScanJob jobFor(Block stationRail) {
    World world = stationRail.getWorld();
    return new StopMarks.ScanJob(
        new TrainCartsRailBlockAccess(world),
        new RailBlockPos(stationRail.getX(), stationRail.getY(), stationRail.getZ()),
        StopMarks.SEARCH_BLOCKS,
        pos -> inspect(world, pos),
        pos -> world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4));
  }

  /** 一段轨道上的牌子：节点牌子按牌子文字认（TCCoasters 的虚拟牌子没有实体方块，不能靠查节点注册表），停车位置标解析出适用节数。 */
  private static StopMarks.RailSigns inspect(World world, RailBlockPos pos) {
    RailLookup.TrackedSign[] signs = signsAt(world, pos);
    if (signs.length == 0) {
      return StopMarks.RailSigns.NONE;
    }
    boolean boundary = false;
    List<StopMarks.Mark> marks = new ArrayList<>();
    for (RailLookup.TrackedSign sign : signs) {
      if (GraphSignParsers.parse(sign).isPresent()) {
        boundary = true;
        continue;
      }
      SignActionHeader header = sign.getHeader();
      if (header == null || !(header.isTrain() || header.isCart())) {
        continue;
      }
      if (!StopMarkSign.isStopMark(sign.getLine(1))) {
        continue;
      }
      StopMarkSign.parse(sign.getLine(2), sign.getLine(3))
          .ifPresent(spec -> marks.add(new StopMarks.Mark(pos, railPoint(world, pos), spec)));
    }
    return new StopMarks.RailSigns(boundary, marks);
  }

  private static RailLookup.TrackedSign[] signsAt(World world, RailBlockPos pos) {
    if (!world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) {
      return new RailLookup.TrackedSign[0];
    }
    RailPiece piece = RailPiece.create(world.getBlockAt(pos.x(), pos.y(), pos.z()));
    if (piece == null || piece.isNone()) {
      return new RailLookup.TrackedSign[0];
    }
    RailLookup.TrackedSign[] signs = RailLookup.discoverSignsAtRailPiece(piece);
    return signs == null ? new RailLookup.TrackedSign[0] : signs;
  }

  /** 轨道中心（与 TrainCarts 对位用的点一致）；取不到时用方块中心。 */
  private static Vector railPoint(World world, RailBlockPos pos) {
    RailPiece piece = RailPiece.create(world.getBlockAt(pos.x(), pos.y(), pos.z()));
    RailState state = piece == null || piece.isNone() ? null : RailState.getSpawnState(piece);
    if (state != null) {
      return state.positionLocation().toVector();
    }
    return new Vector(pos.x() + 0.5, pos.y(), pos.z() + 0.5);
  }
}
