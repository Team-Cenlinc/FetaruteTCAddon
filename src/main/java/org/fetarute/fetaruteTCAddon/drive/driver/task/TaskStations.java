package org.fetarute.fetaruteTCAddon.drive.driver.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 插件派任务时，在一趟车次的停靠表里找出接班站与下车站。本类不依赖服务器对象，便于单测。
 *
 * <p>只认停车的车站（有站码、不是通过）。接班站不能是终点站；下车站必须在接班站之后，写的就是终点站时按开到终点站处理。
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
   * 找到的接班站与下车站。
   *
   * @param board 接班站
   * @param alight 下车站；开到终点站时为空
   */
  public record Resolved(Stop board, Optional<Stop> alight) {}

  private TaskStations() {}

  /**
   * 找接班站与下车站。
   *
   * @param boardCode 接班站站码；为空时取第一个停车的车站
   * @param alightCode 下车站站码；为空时开到终点站
   * @return 找不到、接班站是终点站、或下车站不在接班站之后时为空
   */
  public static Optional<Resolved> resolve(
      List<Stop> stops, Optional<String> boardCode, Optional<String> alightCode) {
    return resolve(stops, boardCode, -1, alightCode);
  }

  /**
   * 找接班站与下车站。
   *
   * @param boardCode 接班站站码；为空时取第一个停车的车站
   * @param boardSequence 接班站的停靠序号（同一车次两次经过同一站时用它区分）；-1 时取该站码的第一次停靠
   * @param alightCode 下车站站码；为空时开到终点站
   * @return 找不到、接班站是终点站、或下车站不在接班站之后时为空
   */
  public static Optional<Resolved> resolve(
      List<Stop> stops,
      Optional<String> boardCode,
      int boardSequence,
      Optional<String> alightCode) {
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
    Stop board = null;
    for (Stop station : stations) {
      if (station == terminus) {
        break;
      }
      boolean codeMatches = boardCode.isEmpty() || station.isStation(boardCode.get());
      if (codeMatches && (boardSequence < 0 || station.sequence() == boardSequence)) {
        board = station;
        break;
      }
    }
    if (board == null) {
      return Optional.empty();
    }
    if (alightCode.isEmpty()) {
      return Optional.of(new Resolved(board, Optional.empty()));
    }
    for (Stop station : stations) {
      if (station.sequence() <= board.sequence() || !station.isStation(alightCode.get())) {
        continue;
      }
      return Optional.of(
          new Resolved(board, station == terminus ? Optional.empty() : Optional.of(station)));
    }
    return Optional.empty();
  }
}
