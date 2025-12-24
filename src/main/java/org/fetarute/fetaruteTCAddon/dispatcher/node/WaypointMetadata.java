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
 *   <li>仅 {@link WaypointKind#INTERVAL} 允许并且必须携带序号（用于区间内排序/区分）
 *   <li>站咽喉与 Depot throat 不携带序号（避免与站台/车库类型编码混淆）
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
    if (kind == WaypointKind.INTERVAL && sequence.isEmpty()) {
      throw new IllegalArgumentException("区间节点必须包含序号");
    }
    if (kind != WaypointKind.INTERVAL && sequence.isPresent()) {
      throw new IllegalArgumentException("非区间节点不允许携带序号");
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

  public static WaypointMetadata throat(String operator, String station, int trackNumber) {
    return new WaypointMetadata(
        operator,
        station,
        Optional.empty(),
        trackNumber,
        Optional.empty(),
        WaypointKind.STATION_THROAT);
  }

  public static WaypointMetadata depot(String operator, String depotId, int trackNumber) {
    return new WaypointMetadata(
        operator, depotId, Optional.empty(), trackNumber, Optional.empty(), WaypointKind.DEPOT);
  }
}
