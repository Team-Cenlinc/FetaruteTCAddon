package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 驾驶员越站的停靠：车次照常跑，只是这一站没停。站台屏把这一站的这趟车显示为取消。
 *
 * <p>只记在内存里：越站发生在列车经过的那一刻，重启后不再需要。只保留最近两天的运营日。
 */
final class SkippedStops {

  private record Key(UUID timetableId, UUID tripId, LocalDate serviceDate) {}

  private final Map<Key, Set<Integer>> byTrip = new ConcurrentHashMap<>();

  /**
   * 记下一次越站。
   *
   * @return 这一站之前没记过
   */
  boolean record(UUID timetableId, UUID tripId, LocalDate serviceDate, int stopSequence) {
    LocalDate oldest = serviceDate.minusDays(2);
    byTrip.keySet().removeIf(key -> key.serviceDate().isBefore(oldest));
    return byTrip
        .computeIfAbsent(
            new Key(timetableId, tripId, serviceDate), key -> ConcurrentHashMap.newKeySet())
        .add(stopSequence);
  }

  /** 这趟车的这一站是否越站。 */
  boolean contains(UUID timetableId, UUID tripId, LocalDate serviceDate, int stopSequence) {
    Set<Integer> stops = byTrip.get(new Key(timetableId, tripId, serviceDate));
    return stops != null && stops.contains(stopSequence);
  }

  /** 只保留仍在用的时刻表。 */
  void retainTimetables(Set<UUID> timetableIds) {
    byTrip.keySet().removeIf(key -> !timetableIds.contains(key.timetableId()));
  }

  void clear() {
    byTrip.clear();
  }
}
