package org.fetarute.fetaruteTCAddon.dispatcher.node;

import java.util.Objects;
import java.util.Optional;

/**
 * Waypoint（包括站咽喉、Depot throat 等）的编码信息。
 *
 * <p>解析自 SignAction 文本，便于调度层按照运营商/区间/股道规则处理。
 *
 * <p>关于 {@code sequence}：
 *
 * <ul>
 *   <li>{@link WaypointKind#INTERVAL} 必须携带序号（用于区间内排序/区分）
 *   <li>{@link WaypointKind#STATION_THROAT}/{@link WaypointKind#DEPOT_THROAT}
 *       必须携带序号（用于同站点/车库下区分多个咽喉）
 *   <li>{@link WaypointKind#STATION}/{@link WaypointKind#DEPOT} 不携带序号
 * </ul>
 */
public record WaypointMetadata(
    String operator,
    String originStation,
    Optional<String> destinationStation,
    int trackNumber,
    Optional<String> sequence,
    WaypointKind kind) {

  public WaypointMetadata {
    Objects.requireNonNull(operator, "operator");
    Objects.requireNonNull(originStation, "originStation");
    Objects.requireNonNull(destinationStation, "destinationStation");
    sequence = sequence == null ? Optional.empty() : sequence.filter(value -> !value.isBlank());
    Objects.requireNonNull(sequence, "sequence");
    Objects.requireNonNull(kind, "kind");
    if (trackNumber < 0) {
      throw new IllegalArgumentException("trackNumber 不能为负数");
    }
    if (kind == WaypointKind.INTERVAL) {
      if (sequence.isEmpty()) {
        throw new IllegalArgumentException("区间节点必须包含序号");
      }
      if (destinationStation.isEmpty()) {
        throw new IllegalArgumentException("区间节点必须包含终到站");
      }
    } else if (kind == WaypointKind.STATION_THROAT || kind == WaypointKind.DEPOT_THROAT) {
      if (sequence.isEmpty()) {
        throw new IllegalArgumentException("咽喉节点必须包含序号");
      }
      if (destinationStation.isPresent()) {
        throw new IllegalArgumentException("咽喉节点不允许包含终到站");
      }
    } else {
      if (sequence.isPresent()) {
        throw new IllegalArgumentException("非区间/咽喉节点不允许携带序号");
      }
      if (destinationStation.isPresent()) {
        throw new IllegalArgumentException("非区间节点不允许包含终到站");
      }
    }
  }

  public static WaypointMetadata interval(
      String operator, String origin, String destination, int trackNumber, String sequence) {
    return new WaypointMetadata(
        operator,
        origin,
        Optional.ofNullable(destination),
        trackNumber,
        Optional.ofNullable(sequence),
        WaypointKind.INTERVAL);
  }

  public static WaypointMetadata station(String operator, String station, int trackNumber) {
    return new WaypointMetadata(
        operator, station, Optional.empty(), trackNumber, Optional.empty(), WaypointKind.STATION);
  }

  public static WaypointMetadata stationThroat(
      String operator, String station, int trackNumber, String sequence) {
    return new WaypointMetadata(
        operator,
        station,
        Optional.empty(),
        trackNumber,
        Optional.ofNullable(sequence),
        WaypointKind.STATION_THROAT);
  }

  public static WaypointMetadata depot(String operator, String depotId, int trackNumber) {
    return new WaypointMetadata(
        operator, depotId, Optional.empty(), trackNumber, Optional.empty(), WaypointKind.DEPOT);
  }

  public static WaypointMetadata depotThroat(
      String operator, String depotId, int trackNumber, String sequence) {
    return new WaypointMetadata(
        operator,
        depotId,
        Optional.empty(),
        trackNumber,
        Optional.ofNullable(sequence),
        WaypointKind.DEPOT_THROAT);
  }
}
