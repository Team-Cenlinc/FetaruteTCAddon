package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import com.bergerkiller.bukkit.tc.SignActionHeader;
import com.bergerkiller.bukkit.tc.controller.components.RailPiece;
import com.bergerkiller.bukkit.tc.controller.components.RailState;
import com.bergerkiller.bukkit.tc.rails.RailLookup;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.TrainCartsRailBlockAccess;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.StopMarkSign;

/**
 * 各车站股道上的停车位置标：按车站牌子所在的轨道沿股道找出（见 {@link StopMarks#scan}），结果缓存一会儿；建、拆停车位置标牌子时清空缓存。
 *
 * <p>只在服务器主线程使用。
 */
public final class StopMarkIndex {

  /** 缓存多久（毫秒）：拆建别的牌子（道岔、节点）不会通知这里，过一会儿重新找。 */
  private static final long CACHE_MILLIS = 30_000L;

  private record Key(UUID world, RailBlockPos rail) {}

  private record Entry(List<StopMarks.Mark> marks, long atMillis) {}

  private final Predicate<Block> nodeSign;
  private final LongSupplier clock;
  private final Map<Key, Entry> cache = new HashMap<>();

  /**
   * @param nodeSign 这块牌子是调度节点牌子（车站、区间点、车库、道岔等）
   */
  public StopMarkIndex(Predicate<Block> nodeSign) {
    this(nodeSign, System::currentTimeMillis);
  }

  StopMarkIndex(Predicate<Block> nodeSign, LongSupplier clock) {
    this.nodeSign = Objects.requireNonNull(nodeSign, "nodeSign");
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
    if (entry != null && now - entry.atMillis() < CACHE_MILLIS) {
      return entry.marks();
    }
    List<StopMarks.Mark> marks;
    try {
      marks =
          List.copyOf(
              StopMarks.scan(
                  new TrainCartsRailBlockAccess(world),
                  start,
                  StopMarks.SEARCH_BLOCKS,
                  pos -> hasNodeSign(world, pos),
                  pos -> marksAt(world, pos)));
    } catch (RuntimeException | LinkageError ex) {
      // TrainCarts 版本不同或轨道正在变化：当作没有标志，过一会儿再找。
      marks = List.of();
    }
    cache.put(key, new Entry(marks, now));
    return marks;
  }

  /** 按节数与行进方向选出车头该停的标志（见 {@link StopMarks#select}）。 */
  public Optional<StopMarks.Selected> select(
      Block stationRail, Vector stationPoint, Vector travel, int carriages) {
    if (stationPoint == null || travel == null) {
      return Optional.empty();
    }
    return StopMarks.select(around(stationRail), stationPoint, travel, carriages);
  }

  /** 清空缓存：停车位置标牌子建好或拆掉时调用。 */
  public void invalidate() {
    cache.clear();
  }

  private boolean hasNodeSign(World world, RailBlockPos pos) {
    for (RailLookup.TrackedSign sign : signsAt(world, pos)) {
      if (sign.signBlock != null && nodeSign.test(sign.signBlock)) {
        return true;
      }
    }
    return false;
  }

  private List<StopMarks.Mark> marksAt(World world, RailBlockPos pos) {
    List<StopMarks.Mark> marks = new ArrayList<>();
    for (RailLookup.TrackedSign sign : signsAt(world, pos)) {
      SignActionHeader header = sign.getHeader();
      if (header == null || !(header.isTrain() || header.isCart())) {
        continue;
      }
      if (!StopMarkSign.isStopMark(sign.getLine(1))) {
        continue;
      }
      Optional<StopMarkSign> parsed = StopMarkSign.parse(sign.getLine(2), sign.getLine(3));
      parsed.ifPresent(spec -> marks.add(new StopMarks.Mark(pos, railPoint(world, pos), spec)));
    }
    return marks;
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
