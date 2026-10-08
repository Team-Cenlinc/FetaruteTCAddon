package org.fetarute.fetaruteTCAddon.drive.driver.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 插件派任务时，在一趟车次的停靠表里找出接班站与交班站。本类不依赖服务器对象，便于单测。
 *
 * <p>只认停车的车站（有站码、不是通过）。接班站不能是终点站；交班站必须在接班站之后，写的就是终点站时按开到终点站处理。
 */
public final class TaskStations {

  /**
   * 停靠表里的一个点。
   *
   * @param sequence 停靠序号
   * @param stationCode 站码；区间点、咽喉、车库为空
   * @param stops 是否停车
   */
  public record Stop(int sequence, Optional<String> stationCode, boolean stops) {
    public Stop {
      stationCode = stationCode == null ? Optional.empty() : stationCode;
    }

    boolean isStation() {
      return stops && stationCode.filter(code -> !code.isBlank()).isPresent();
    }

    boolean isStation(String code) {
      return isStation() && stationCode.get().equalsIgnoreCase(code.trim());
    }
  }

  /**
   * 找到的接班站与交班站。
   *
   * @param takeover 接班站
   * @param handover 交班站；开到终点站时为空
   */
  public record Resolved(Stop takeover, Optional<Stop> handover) {}

  private TaskStations() {}

  /**
   * 找接班站与交班站。
   *
   * @param takeoverCode 接班站站码；为空时取第一个停车的车站
   * @param handoverCode 交班站站码；为空时开到终点站
   * @return 找不到、接班站是终点站、或交班站不在接班站之后时为空
   */
  public static Optional<Resolved> resolve(
      List<Stop> stops, Optional<String> takeoverCode, Optional<String> handoverCode) {
    return resolve(stops, takeoverCode, -1, handoverCode);
  }

  /**
   * 找接班站与交班站。
   *
   * @param takeoverCode 接班站站码；为空时取第一个停车的车站
   * @param takeoverSequence 接班站的停靠序号（同一车次两次经过同一站时用它区分）；-1 时取该站码的第一次停靠
   * @param handoverCode 交班站站码；为空时开到终点站
   * @return 找不到、接班站是终点站、或交班站不在接班站之后时为空
   */
  public static Optional<Resolved> resolve(
      List<Stop> stops,
      Optional<String> takeoverCode,
      int takeoverSequence,
      Optional<String> handoverCode) {
    Objects.requireNonNull(stops, "stops");
    List<Stop> stations = new ArrayList<>();
    for (Stop stop : stops) {
      if (stop.isStation()) {
        stations.add(stop);
      }
    }
    if (stations.size() < 2) {
      return Optional.empty();
    }
    Stop terminus = stations.get(stations.size() - 1);
    Stop takeover = null;
    for (Stop station : stations) {
      if (station == terminus) {
        break;
      }
      boolean codeMatches = takeoverCode.isEmpty() || station.isStation(takeoverCode.get());
      if (codeMatches && (takeoverSequence < 0 || station.sequence() == takeoverSequence)) {
        takeover = station;
        break;
      }
    }
    if (takeover == null) {
      return Optional.empty();
    }
    if (handoverCode.isEmpty()) {
      return Optional.of(new Resolved(takeover, Optional.empty()));
    }
    for (Stop station : stations) {
      if (station.sequence() <= takeover.sequence() || !station.isStation(handoverCode.get())) {
        continue;
      }
      return Optional.of(
          new Resolved(takeover, station == terminus ? Optional.empty() : Optional.of(station)));
    }
    return Optional.empty();
  }
}
