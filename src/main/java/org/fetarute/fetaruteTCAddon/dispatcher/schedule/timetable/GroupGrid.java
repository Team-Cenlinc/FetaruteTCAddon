package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 一个方向的规整子网格：发车 = {@code phase + k × interval}，k 从 0 起到计划窗口末；同方向多条 route 用 SWRR 决定第 k 格归谁。
 *
 * <p>取代"全线一张网格、SWRR 把格子分给各 route"的做法：那样每条 route 的间隔是 360/240/240 这种没规律的数，端点上的等待就是相位余数。 乘客要的是"这个方向每
 * N 秒一班"，所以间隔是方向的属性，weight 只在同方向多 route 之间切份额。
 */
public final class GroupGrid {

  private GroupGrid() {}

  /**
   * 一个格子。
   *
   * @param departureSeconds 相对计划窗口起点的发车时刻
   * @param routeId 这一格归哪条 route
   * @param candidateIndex 在方向候选里的下标
   * @param slot 格子序号
   */
  public record Slot(int departureSeconds, UUID routeId, int candidateIndex, int slot) {}

  /**
   * 一个方向的子网格。
   *
   * @param direction 方向
   * @param intervalSeconds 间隔
   * @param phaseSeconds 相位（{@code [0, interval)}）
   * @param slots 格子，按时刻升序
   */
  public record DirectionGrid(
      ServiceGroupClassifier.Direction direction,
      int intervalSeconds,
      int phaseSeconds,
      List<Slot> slots) {
    public DirectionGrid {
      Objects.requireNonNull(direction, "direction");
      slots = slots == null ? List.of() : List.copyOf(slots);
    }
  }

  /**
   * 生成子网格。
   *
   * @param direction 方向
   * @param intervalSeconds 间隔（秒，≥ 1）
   * @param phaseSeconds 相位
   * @param horizonSeconds 计划窗口长度
   * @param feasible 第 k 格上、方向内第 i 个候选能否再接一班（通常是"这一班要在窗口内跑完"）
   */
  public static DirectionGrid of(
      ServiceGroupClassifier.Direction direction,
      int intervalSeconds,
      int phaseSeconds,
      int horizonSeconds,
      WeightedTripAllocator.FeasibilityCheck feasible) {
    // 单一制式：窗口末尾那一秒也能发车（与按格数算的旧口径一致）。
    return ofSegments(
        direction,
        List.of(
            new Segment(
                0, Math.max(0, horizonSeconds) + 1, Math.max(1, intervalSeconds), phaseSeconds)),
        feasible);
  }

  /**
   * 分时段的一段：{@code [from, to)} 内按 {@code phase + k × interval} 发车。
   *
   * @param from 起点（相对计划窗口起点，含）
   * @param to 终点（不含）
   * @param interval 间隔
   * @param phase 相位（对间隔取模）
   */
  public record Segment(int from, int to, int interval, int phase) {
    public Segment {
      interval = Math.max(1, interval);
      phase = Math.floorMod(phase, interval);
    }
  }

  /**
   * 各段的发车时刻，按时间排好：交界处新段的第一班离上一段末班至少隔两段间隔里较小的那个，再对齐到新段的相位。
   *
   * @param segments 按时间排好、互不重叠的段
   */
  public static List<Integer> times(List<Segment> segments) {
    List<Integer> times = new ArrayList<>();
    Integer last = null;
    int lastInterval = 0;
    for (Segment segment : segments) {
      int earliest =
          last == null
              ? segment.from()
              : Math.max(segment.from(), last + Math.min(lastInterval, segment.interval()));
      int first = earliest + Math.floorMod(segment.phase() - earliest, segment.interval());
      for (int t = first;
          t < segment.to() && times.size() < TimetableBuildOptions.MAX_TRIPS;
          t += segment.interval()) {
        times.add(t);
        last = t;
      }
      lastInterval = segment.interval();
    }
    return List.copyOf(times);
  }

  /**
   * 分时段生成子网格：各段按自己的间隔与相位铺格，SWRR 在每段里按环上的格号对齐。
   *
   * <p>周期层的环上，相位 {@code p}、间隔 {@code I} 的第 {@code m} 格（发车 {@code p + m·I}，从窗口起点数）归 SWRR 一轮的第 {@code
   * m mod s} 格； 多停按 route 定，格归错了 route，多停就加到了别的班次上。所以每段的 SWRR 从头数起，先空转到这一段第一格在环上的格号，
   * 而不是跨段连续计数。只有一段、或方向里只有一条 route 时与连续计数相同。
   *
   * <p>交界处新段的第一班离上一段末班至少隔两段间隔里较小的那个，再对齐到新段的相位：否则两段相位不同时，交界处会挤出两班几乎同时发的车。
   *
   * @param segments 按时间排好、互不重叠的段
   * @param feasible 第 k 格上、方向内第 i 个候选能否再接一班（{@code k} 是全部段合起来的格号）
   */
  public static DirectionGrid ofSegments(
      ServiceGroupClassifier.Direction direction,
      List<Segment> segments,
      WeightedTripAllocator.FeasibilityCheck feasible) {
    Objects.requireNonNull(direction, "direction");
    WeightedTripAllocator.FeasibilityCheck check =
        feasible == null ? WeightedTripAllocator.unconstrained() : feasible;
    List<Integer> times = times(segments);
    List<Slot> slots = new ArrayList<>(times.size());
    int index = 0;
    for (Segment segment : segments) {
      int first = index;
      while (index < times.size() && times.get(index) < segment.to()) {
        index++;
      }
      int count = index - first;
      if (count == 0) {
        continue;
      }
      int warmup = (times.get(first) - segment.phase()) / segment.interval();
      // 空转的格只为把 SWRR 转到位：按这一段第一格的可行性记，不出现在结果里。
      for (WeightedTripAllocator.Allocation allocation :
          WeightedTripAllocator.allocate(
              direction.candidates(),
              warmup + count,
              (slot, candidate, assigned) ->
                  check.canAssign(first + Math.max(0, slot - warmup), candidate, assigned))) {
        if (allocation.slot() < warmup) {
          continue;
        }
        int global = first + allocation.slot() - warmup;
        slots.add(
            new Slot(
                times.get(global),
                direction.routeIds().get(allocation.candidateIndex()),
                allocation.candidateIndex(),
                global));
      }
    }
    Segment head = segments.isEmpty() ? new Segment(0, 1, 1, 0) : segments.get(0);
    return new DirectionGrid(direction, head.interval(), head.phase(), slots);
  }
}
