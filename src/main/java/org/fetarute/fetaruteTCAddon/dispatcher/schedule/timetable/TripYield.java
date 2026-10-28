package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 写进表的一处让车（{@link ReservationScheduler}）。
 *
 * @param kind 资源类型
 * @param resource 资源键
 * @param first 前车（不动的那个）的 code
 * @param firstOwner 前车属于哪份邻表；空 = 自己
 * @param second 后车（被延后的）的 code；班次是临时 code
 * @param waitSeconds 延后了多少秒
 * @param atSeconds 后车原本进入资源的时刻（相对秒）
 */
public record TripYield(
    TimetableConflictChecker.Kind kind,
    String resource,
    String first,
    Optional<String> firstOwner,
    String second,
    int waitSeconds,
    int atSeconds) {
  public TripYield {
    firstOwner = firstOwner == null ? Optional.empty() : firstOwner;
  }

  /** 给邻表让的车。 */
  public boolean external() {
    return firstOwner.isPresent();
  }

  /** 前后车的临时 code 换成最终车次号；没有映射的原样保留。 */
  static List<TripYield> renamed(List<TripYield> yields, Map<String, String> finalCodes) {
    List<TripYield> out = new ArrayList<>(yields.size());
    for (TripYield yield : yields) {
      out.add(
          new TripYield(
              yield.kind(),
              yield.resource(),
              finalCodes.getOrDefault(yield.first(), yield.first()),
              yield.firstOwner(),
              finalCodes.getOrDefault(yield.second(), yield.second()),
              yield.waitSeconds(),
              yield.atSeconds()));
    }
    return List.copyOf(out);
  }

  /** 供报告：让车里最长的一处。 */
  public static int maxWait(List<TripYield> yields) {
    int max = 0;
    for (TripYield yield : yields) {
      max = Math.max(max, yield.waitSeconds());
    }
    return max;
  }

  /** 供报告：涉及的后车数。 */
  public static int movedCount(List<TripYield> yields) {
    Set<String> out = new HashSet<>();
    for (TripYield yield : yields) {
      out.add(yield.second());
    }
    return out.size();
  }
}
