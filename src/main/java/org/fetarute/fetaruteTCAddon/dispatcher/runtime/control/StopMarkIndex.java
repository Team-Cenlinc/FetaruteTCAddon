package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import com.bergerkiller.bukkit.tc.SignActionHeader;
import com.bergerkiller.bukkit.tc.controller.components.RailPiece;
import com.bergerkiller.bukkit.tc.controller.components.RailState;
import com.bergerkiller.bukkit.tc.rails.RailLookup;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.TrainCartsRailBlockAccess;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.GraphSignParsers;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.StopMarkSign;

/**
 * 各车站股道上的停车位置标：按车站牌子所在的轨道沿股道找出（见 {@link StopMarks#scan}），结果缓存；建、拆停车位置标或节点牌子时清空缓存。
 *
 * <p>只在服务器主线程使用。
 */
public final class StopMarkIndex {

  /**
   * 缓存多久（毫秒）：建、拆停车位置标与节点牌子时立即清空；改轨道（加减道岔）不会通知这里，过一会儿重新找。
   *
   * <p>每个车站只在缓存过期后第一趟车进站时找一次；繁忙车站也不会每趟车都沿站台走一遍。
   */
  static final long CACHE_MILLIS = 300_000L;

  /** 沿途还有区块没加载时，结果只存这么久（毫秒）：既不每拍重扫，区块加载后也很快能找全。 */
  static final long INCOMPLETE_CACHE_MILLIS = 5_000L;

  private record Key(UUID world, RailBlockPos rail) {}

  private record Entry(StopMarks.Scan scan, long atMillis) {}

  private final Function<Block, StopMarks.Scan> scanner;
  private final LongSupplier clock;

  /** 节点注册表可能在异步线程里通知作废，缓存用并发 map。 */
  private final Map<Key, Entry> cache = new ConcurrentHashMap<>();

  public StopMarkIndex() {
    this(StopMarkIndex::scanTrack, System::currentTimeMillis);
  }

  StopMarkIndex(Function<Block, StopMarks.Scan> scanner, LongSupplier clock) {
    this.scanner = Objects.requireNonNull(scanner, "scanner");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** 车站牌子所在轨道那条股道上的停车位置标；区块未加载或找不到轨道时为空。 */
  public List<StopMarks.Mark> around(Block stationRail) {
    if (stationRail == null) {
      return List.of();
    }
    World world = stationRail.getWorld();
    RailBlockPos start =
        new RailBlockPos(stationRail.getX(), stationRail.getY(), stationRail.getZ());
    Key key = new Key(world.getUID(), start);
    long now = clock.getAsLong();
    Entry entry = cache.get(key);
    if (entry != null
        && now - entry.atMillis()
            < (entry.scan().complete() ? CACHE_MILLIS : INCOMPLETE_CACHE_MILLIS)) {
      return entry.scan().marks();
    }
    StopMarks.Scan scan;
    try {
      scan = scanner.apply(stationRail);
    } catch (RuntimeException | LinkageError ex) {
      // TrainCarts 版本不同或轨道正在变化：当作没有标志，过一会儿再找。
      scan = new StopMarks.Scan(List.of(), false);
    }
    cache.put(key, new Entry(scan, now));
    return scan.marks();
  }

  /** 沿车站股道收集标志。轨道与牌子都经 TrainCarts 读取，TCCoasters 的轨道与虚拟牌子同样适用；距离按轨道实际长度量。 */
  private static StopMarks.Scan scanTrack(Block stationRail) {
    World world = stationRail.getWorld();
    return StopMarks.scan(
        new TrainCartsRailBlockAccess(world),
        new RailBlockPos(stationRail.getX(), stationRail.getY(), stationRail.getZ()),
        StopMarks.SEARCH_BLOCKS,
        pos -> inspect(world, pos),
        pos -> world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4));
  }

  /**
   * 按节数与行进方向选出车头该停的标志（见 {@link StopMarks#select}）。
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

  /** 清空缓存：停车位置标或节点牌子建好、拆掉时调用。可在任意线程调用。 */
  public void invalidate() {
    cache.clear();
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
