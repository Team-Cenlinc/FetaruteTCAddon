package org.fetarute.fetaruteTCAddon.drive.driver;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.Function;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableService;

/**
 * 下一个通过站：本站到下一个停车站之间，第一个不停车通过的车站，以及它的表定通过时刻。司机运行时刻表上通过站也写时刻，驾驶员据此掌握正晚点。
 *
 * <p>本类不依赖服务器对象，便于单测。
 *
 * @param station 站名
 * @param planned 表定通过时刻
 * @param deviationSeconds 预计偏差秒数，正数为晚点；不明时为空
 */
public record DriverPass(String station, Instant planned, OptionalLong deviationSeconds) {

  public DriverPass {
    Objects.requireNonNull(station, "station");
    Objects.requireNonNull(planned, "planned");
    deviationSeconds = deviationSeconds == null ? OptionalLong.empty() : deviationSeconds;
  }

  /**
   * 找出下一个通过站。
   *
   * @param stops 这一趟车次的停靠表（含通过站）
   * @param routeIndex 交路进度下标：已到达或已驶过的最后一个停靠点
   * @param nextStopIndex 前方下一个停车站的序号；没有时为空（一直找到终点）
   * @param deviationSeconds 到下一站的预计偏差，借作通过站的偏差；不明时为空
   * @param stationName 按节点编号取站名；取不到时返回空串
   */
  public static Optional<DriverPass> next(
      List<TimetableService.PlannedStop> stops,
      int routeIndex,
      OptionalInt nextStopIndex,
      OptionalLong deviationSeconds,
      Function<String, String> stationName) {
    if (stops == null || stops.isEmpty()) {
      return Optional.empty();
    }
    int limit =
        nextStopIndex == null || nextStopIndex.isEmpty()
            ? Integer.MAX_VALUE
            : nextStopIndex.getAsInt();
    for (TimetableService.PlannedStop stop : stops) {
      int sequence = stop.stopSequence();
      if (sequence <= routeIndex || sequence >= limit) {
        continue;
      }
      if (stop.stops() || stop.stationCode().isEmpty() || stop.nodeId().isEmpty()) {
        continue;
      }
      Optional<Instant> planned = stop.arrival().or(stop::departure);
      if (planned.isEmpty()) {
        continue;
      }
      String name = stationName.apply(stop.nodeId().get());
      if (name == null || name.isBlank()) {
        name = stop.stationCode().get();
      }
      return Optional.of(new DriverPass(name, planned.get(), deviationSeconds));
    }
    return Optional.empty();
  }

  /** 与表定的关系（与表定到站同一套正点范围）。 */
  public DriverSchedule.State state() {
    if (deviationSeconds.isEmpty()) {
      return DriverSchedule.State.PLAIN;
    }
    long deviation = deviationSeconds.getAsLong();
    if (deviation > DriverSchedule.ON_TIME_SECONDS) {
      return DriverSchedule.State.LATE;
    }
    return deviation < -DriverSchedule.ON_TIME_SECONDS
        ? DriverSchedule.State.EARLY
        : DriverSchedule.State.ON_TIME;
  }

  /** 偏差的绝对值，按“分:秒”写；不明时为空串。 */
  public String deviationText() {
    return new DriverSchedule(false, planned, deviationSeconds).deviationText();
  }
}
