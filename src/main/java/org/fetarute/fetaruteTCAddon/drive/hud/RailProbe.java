package org.fetarute.fetaruteTCAddon.drive.hud;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopMarks;

/**
 * 从停车点沿轨道前移用的 {@link StopMarkerGeometry.Track}：所有驾驶员共用，按停车点与朝向缓存沿轨道的采样（{@link TrackProfile}）。
 *
 * <p>主线程开销有上限：同一个停车点同一侧只采样一次，之后任意距离都在采样点间插值；走轨用各驾驶员共用的每 tick 时间预算 {@link
 * #TICK_BUDGET_NANOS}（与停车位置标的后台分片同一量级），一次最多为一侧走 {@link #SLICE_BLOCKS} 格，没走完的由 {@link #tick} 在之后的
 * tick 接着走，期间从已采样的末端外推。每隔 {@link #REFRESH_TICKS} 重新采样（道岔可能换了开向），新的追上之前一直用旧的。 读不到轨道时隔 {@link
 * #RETRY_TICKS} 再试：TrainCarts 在坏轨道上会打错误日志，不能每次刷新都去碰。只在服务器主线程使用。
 */
final class RailProbe implements StopMarker.Probe {

  /** 最多前移多远（格）：与沿车站股道找停车位置标的上限一致，长站台上的长编组也够用。 */
  static final double MAX_BLOCKS = StopMarks.SEARCH_BLOCKS;

  /** 一次最多为一侧走多远（格）：预算各侧共用，一侧不能独占。 */
  static final double SLICE_BLOCKS = 64.0;

  /** 每 tick 走轨的时间预算（纳秒），所有驾驶员共用。 */
  static final long TICK_BUDGET_NANOS = 1_000_000L;

  /** 多久重新采样一次（tick）。 */
  static final long REFRESH_TICKS = 100L;

  /** 读不到轨道时隔多久再试（tick）。 */
  static final long RETRY_TICKS = 40L;

  /** 多久没人查就丢掉这个停车点的采样（tick）。 */
  static final long EVICT_TICKS = 1200L;

  /** 在起点朝一侧打开游标。 */
  @FunctionalInterface
  interface Opener {
    /**
     * @return 起点区块没加载或没有轨道时为空
     */
    Optional<TrackProfile.Cursor> open(World world, Vector start, Vector heading);
  }

  /** 起点所在的方块：站台交来的停车点（方块中心）与自己找的（已吸附到轨道上）落在同一块轨道上，算同一个起点。 */
  private record Start(UUID worldId, int x, int y, int z) {
    static Start of(World world, Vector point) {
      return new Start(
          world.getUID(),
          (int) Math.floor(point.getX()),
          (int) Math.floor(point.getY()),
          (int) Math.floor(point.getZ()));
    }
  }

  /** 朝一侧的采样：在用的、到期后正在重新采样的，以及要采样到多远。 */
  private static final class Side {
    private TrackProfile current;
    private TrackProfile pending;
    private double target;
    private long retryAtTick = Long.MIN_VALUE;

    Side(TrackProfile current) {
      this.current = current;
    }
  }

  /** 一个停车点：两侧的采样、打开新一侧的重试时刻、最近被查的时刻。 */
  private static final class Entry {
    private final List<Side> sides = new ArrayList<>(2);
    private long retryAtTick = Long.MIN_VALUE;
    private long usedAtTick;
  }

  private final Opener opener;
  private final LongSupplier nanos;
  private final Map<Start, Entry> entries = new LinkedHashMap<>();
  private long budgetTick = Long.MIN_VALUE;
  private long spentNanos;

  RailProbe() {
    this(TrainCartsTrackCursor::open, System::nanoTime);
  }

  RailProbe(Opener opener, LongSupplier nanos) {
    this.opener = Objects.requireNonNull(opener, "opener");
    this.nanos = Objects.requireNonNull(nanos, "nanos");
  }

  @Override
  public StopMarkerGeometry.Track track(World world, long nowTick) {
    return (from, heading, distance) -> walk(world, from, heading, distance, nowTick);
  }

  /** 在本 tick 剩余的预算内接着做没做完的采样；顺带丢掉久未使用的停车点。 */
  @Override
  public void tick(long nowTick) {
    Iterator<Entry> iterator = entries.values().iterator();
    while (iterator.hasNext()) {
      Entry entry = iterator.next();
      if (nowTick - entry.usedAtTick >= EVICT_TICKS) {
        iterator.remove();
        continue;
      }
      for (Side side : entry.sides) {
        if (!budgetLeft(nowTick)) {
          return;
        }
        advance(side, nowTick);
      }
    }
  }

  @Override
  public void clear() {
    entries.clear();
  }

  /** 缓存着的停车点数（诊断用）。 */
  int cachedCount() {
    return entries.size();
  }

  private Optional<StopMarkerGeometry.Placement> walk(
      World world, Vector from, Vector heading, double distance, long nowTick) {
    double length = Math.abs(distance);
    if (world == null || from == null || heading == null || !(length <= MAX_BLOCKS)) {
      return Optional.empty();
    }
    Entry entry = entries.computeIfAbsent(Start.of(world, from), key -> new Entry());
    entry.usedAtTick = nowTick;
    boolean backward = distance < 0.0;
    Vector forward = backward ? heading.clone().multiply(-1.0) : heading.clone();
    Side side = sideToward(entry, forward);
    if (side == null) {
      if (nowTick < entry.retryAtTick) {
        return Optional.empty();
      }
      Optional<TrackProfile.Cursor> cursor = opener.open(world, from, forward);
      if (cursor.isEmpty()) {
        entry.retryAtTick = nowTick + RETRY_TICKS;
        return Optional.empty();
      }
      side = new Side(new TrackProfile(cursor.get(), nowTick));
      entry.sides.add(side);
    } else if (side.pending == null
        && nowTick >= side.retryAtTick
        && nowTick - side.current.createdAtTick() >= REFRESH_TICKS) {
      Optional<TrackProfile.Cursor> cursor =
          opener.open(world, from, side.current.initialDirection());
      if (cursor.isPresent()) {
        side.pending = new TrackProfile(cursor.get(), nowTick);
      } else {
        side.retryAtTick = nowTick + RETRY_TICKS;
      }
    }
    side.target = length;
    advance(side, nowTick);
    StopMarkerGeometry.Placement placement = side.current.at(length);
    if (!backward) {
      return Optional.of(placement);
    }
    return Optional.of(
        new StopMarkerGeometry.Placement(
            placement.position(), placement.direction().multiply(-1.0)));
  }

  /** 为这一侧接着采样，最多 {@link #SLICE_BLOCKS} 格；重新采样的追上后换下旧的。 */
  private void advance(Side side, long nowTick) {
    double slice = SLICE_BLOCKS;
    if (side.pending != null) {
      slice -= extend(side.pending, side.target, slice, nowTick);
      if (side.pending.reaches(side.target)) {
        side.current = side.pending;
        side.pending = null;
      }
    }
    extend(side.current, side.target, slice, nowTick);
  }

  /** 在本 tick 剩余的时间预算内采样，用时记进预算。 */
  private double extend(TrackProfile profile, double target, double blocks, long nowTick) {
    if (profile.reaches(target) || !budgetLeft(nowTick)) {
      return 0.0;
    }
    long started = nanos.getAsLong();
    try {
      return profile.extend(
          target,
          blocks,
          nowTick,
          () -> spentNanos + (nanos.getAsLong() - started) < TICK_BUDGET_NANOS);
    } finally {
      spentNanos += nanos.getAsLong() - started;
    }
  }

  private boolean budgetLeft(long nowTick) {
    if (nowTick != budgetTick) {
      budgetTick = nowTick;
      spentNanos = 0L;
    }
    return spentNanos < TICK_BUDGET_NANOS;
  }

  /** 朝 {@code forward} 一侧的采样；两侧都有了还对不上（起点走向量不出）时取最接近的，不再新开。 */
  private static Side sideToward(Entry entry, Vector forward) {
    Side best = null;
    double bestDot = Double.NEGATIVE_INFINITY;
    for (Side side : entry.sides) {
      Vector initial = side.current.initialDirection();
      double dot = initial.getX() * forward.getX() + initial.getZ() * forward.getZ();
      if (dot > bestDot) {
        best = side;
        bestDot = dot;
      }
    }
    return bestDot > 0.0 || entry.sides.size() >= 2 ? best : null;
  }
}
