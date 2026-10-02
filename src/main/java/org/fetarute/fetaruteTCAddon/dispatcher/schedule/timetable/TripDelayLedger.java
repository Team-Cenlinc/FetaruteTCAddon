package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 晚点账：一趟车沿途每次到站、发车相对计划的偏差。
 *
 * <p>一串偏差里读得出三个量：<b>带入</b>（本趟第一次记录时已经晚了多少，来自上一趟或出库）、<b>新增</b>（相邻两次记录之间晚点增加的秒数之和，
 * 本趟途中自己产生的晚点）与<b>追回</b>（晚点减少的秒数之和）。新增与追回只数<b>晚点</b>部分（负偏差按 0 计）：早到的车在站里等点， 偏差从 −20 回到 0
 * 是计划内的扣留，不是晚点。停站压缩与放宽线路限速管不管用，只看追回；晚点从哪来，看带入与新增。 此前这几个数只能拿 {@code SMART_ROUTE_ARRIVAL}
 * 对着离线探针导出的表定时分手工拼。
 *
 * <p>只存内存、按列车名键。一趟跑完或列车离开时结账成一行日志，不落库。控车每个信号 tick 读 {@link #current}， 所以每趟只存不可变快照、整体替换。
 */
final class TripDelayLedger {

  private final ConcurrentMap<String, Trip> trips = new ConcurrentHashMap<>();

  /**
   * 一次到发记录。
   *
   * @param stopIndex 停靠序号
   * @param departure true 为发车，false 为到站
   * @param delaySeconds 相对计划的偏差，正数为晚点
   */
  record Mark(int stopIndex, boolean departure, long delaySeconds) {}

  /**
   * 一趟车的账。
   *
   * @param tripId 车次
   * @param tripCode 车次号
   * @param serviceDate 服务日
   * @param marks 按发生顺序的记录
   */
  record Trip(UUID tripId, String tripCode, LocalDate serviceDate, List<Mark> marks) {
    Trip {
      Objects.requireNonNull(tripId, "tripId");
      marks = List.copyOf(marks);
    }

    private Trip plus(Mark mark) {
      List<Mark> next = new ArrayList<>(marks.size() + 1);
      next.addAll(marks);
      next.add(mark);
      return new Trip(tripId, tripCode, serviceDate, next);
    }
  }

  /**
   * 一趟车的结账。
   *
   * @param tripCode 车次号
   * @param serviceDate 服务日
   * @param marks 记录条数
   * @param carriedIn 带入：第一次记录的偏差
   * @param finalDelay 最后一次记录的偏差
   * @param maxDelay 途中最大偏差
   * @param gained 新增：相邻记录间晚点（负偏差按 0 计）增加的秒数之和
   * @param recovered 追回：相邻记录间晚点减少的秒数之和（正数）
   * @param detail 逐条记录，{@code 序号a|d±秒} 逗号分隔
   */
  record Summary(
      String tripCode,
      LocalDate serviceDate,
      int marks,
      long carriedIn,
      long finalDelay,
      long maxDelay,
      long gained,
      long recovered,
      String detail) {

    /** 结账日志行；原因说明这趟为什么在这里结账（跑完、换了车次、列车离开）。 */
    String logLine(String trainName, String reason) {
      return "TIMETABLE_TRIP_DELAY train="
          + trainName
          + " trip="
          + tripCode
          + " date="
          + serviceDate
          + " reason="
          + reason
          + " marks="
          + marks
          + " carriedIn="
          + signed(carriedIn)
          + " final="
          + signed(finalDelay)
          + " max="
          + signed(maxDelay)
          + " gained="
          + gained
          + " recovered="
          + recovered
          + " detail="
          + detail;
    }
  }

  /**
   * 记一次到发。列车换了车次时先把上一趟结账返回，本条记到新车次上。
   *
   * @param key 规范列车键
   * @param assignment 列车当前绑定的车次
   * @param mark 本次记录
   * @return 被换下的上一趟的结账；没换车次时为空
   */
  Optional<Summary> record(String key, TimetableAssignment assignment, Mark mark) {
    if (key == null || assignment == null || mark == null) {
      return Optional.empty();
    }
    Trip previous = trips.get(key);
    Optional<Summary> closed = Optional.empty();
    Trip base = previous;
    if (previous == null || !previous.tripId().equals(assignment.tripId())) {
      closed = Optional.ofNullable(previous).map(TripDelayLedger::summarize);
      base =
          new Trip(assignment.tripId(), assignment.tripCode(), assignment.serviceDate(), List.of());
    }
    trips.put(key, base.plus(mark));
    return closed;
  }

  /**
   * 这辆车在指定车次上最近一次记录的偏差。
   *
   * @param key 规范列车键
   * @param tripId 列车此刻绑定的车次；账上记的是别的车次（已改派、重新匹配）时为空
   * @return 最近一次偏差；没有记录时为空
   */
  OptionalLong current(String key, UUID tripId) {
    Trip trip = key == null ? null : trips.get(key);
    if (trip == null || trip.marks().isEmpty() || !trip.tripId().equals(tripId)) {
      return OptionalLong.empty();
    }
    return OptionalLong.of(trip.marks().get(trip.marks().size() - 1).delaySeconds());
  }

  /** 结账并移除；没有账时为空。 */
  Optional<Summary> close(String key) {
    Trip trip = key == null ? null : trips.remove(key);
    return trip == null || trip.marks().isEmpty() ? Optional.empty() : Optional.of(summarize(trip));
  }

  /** 只留仍在运行时管辖内的列车；其余不结账直接丢弃（兜底清理，正常离开走 {@link #close}）。 */
  void retain(Set<String> keep) {
    trips.keySet().retainAll(keep);
  }

  void clear() {
    trips.clear();
  }

  static Summary summarize(Trip trip) {
    List<Mark> marks = trip.marks();
    long first = marks.isEmpty() ? 0L : marks.get(0).delaySeconds();
    long last = first;
    long max = first;
    long gained = 0L;
    long recovered = 0L;
    StringBuilder detail = new StringBuilder();
    for (int i = 0; i < marks.size(); i++) {
      Mark mark = marks.get(i);
      long delay = mark.delaySeconds();
      if (i > 0) {
        long step = Math.max(0L, delay) - Math.max(0L, last);
        if (step > 0L) {
          gained += step;
        } else {
          recovered -= step;
        }
        detail.append(',');
      }
      detail.append(mark.stopIndex()).append(mark.departure() ? 'd' : 'a').append(signed(delay));
      last = delay;
      max = Math.max(max, delay);
    }
    return new Summary(
        trip.tripCode(),
        trip.serviceDate(),
        marks.size(),
        first,
        last,
        max,
        gained,
        recovered,
        detail.length() == 0 ? "-" : detail.toString());
  }

  private static String signed(long value) {
    return value >= 0L ? "+" + value : Long.toString(value);
  }
}
