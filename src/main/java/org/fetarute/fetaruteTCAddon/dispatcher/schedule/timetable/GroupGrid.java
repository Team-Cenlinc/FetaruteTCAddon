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
    Objects.requireNonNull(direction, "direction");
    int interval = Math.max(1, intervalSeconds);
    int phase = Math.floorMod(phaseSeconds, interval);
    int count =
        horizonSeconds < phase
            ? 0
            : (int)
                Math.min(
                    TimetableBuildOptions.MAX_TRIPS,
                    (long) (horizonSeconds - phase) / interval + 1L);
    List<WeightedTripAllocator.Allocation> allocations =
        WeightedTripAllocator.allocate(direction.candidates(), count, feasible);
    List<Slot> slots = new ArrayList<>(allocations.size());
    for (WeightedTripAllocator.Allocation allocation : allocations) {
      slots.add(
          new Slot(
              phase + allocation.slot() * interval,
              direction.routeIds().get(allocation.candidateIndex()),
              allocation.candidateIndex(),
              allocation.slot()));
    }
    return new DirectionGrid(direction, interval, phase, slots);
  }
}
