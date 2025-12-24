package org.fetarute.fetaruteTCAddon.dispatcher.sign;

import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;

/**
 * 解析 Waypoint/站台类牌子的文本编码，统一处理 S/D 保留段与轨道号校验。
 *
 * <p>格式约定：
 *
 * <ul>
 *   <li>区间点（Waypoint interval）：{@code Operator:From:To:Track:Seq}（5 段）
 *   <li>站点（Station）：{@code Operator:S:Station:Track}（4 段）
 *   <li>车库（Depot）：{@code Operator:D:Depot:Track}（4 段）
 *   <li>站咽喉（Station throat）：{@code Operator:S:Station:Track:Seq}（5 段）
 *   <li>车库咽喉（Depot throat）：{@code Operator:D:Depot:Track:Seq}（5 段）
 * </ul>
 */
public final class SignTextParser {

  private static final int SEGMENTS_TYPED = 4;
  private static final int SEGMENTS_WITH_SEQUENCE = 5;

  private SignTextParser() {}

  /**
   * 解析形如 Operator:From:To:Track:Seq 的编码，兼容站咽喉与 Depot throat。
   *
   * <p>不兼容旧格式：4 段 {@code Operator:S/D:Name:Track} 现在表示站点/车库；咽喉必须使用 5 段格式并携带 Seq。
   *
   * @param rawId 牌子填写的节点 ID
   * @param nodeType 解析后写入的节点类型
   * @return 成功则返回节点定义，失败返回 Optional.empty()
   */
  public static Optional<SignNodeDefinition> parseWaypointLike(String rawId, NodeType nodeType) {
    if (rawId == null) {
      return Optional.empty();
    }
    String trimmed = rawId.trim();
    if (trimmed.isEmpty()) {
      return Optional.empty();
    }
    String[] segments = trimmed.split(":");
    if (segments.length == SEGMENTS_TYPED) {
      // 4 段格式：强制要求第二段为 S/D，用于区分 Station/Depot，避免同名冲突。
      String operator = segments[0].trim();
      String type = segments[1].trim();
      String name = segments[2].trim();
      String trackSegment = segments[3].trim();
      if (operator.isEmpty() || type.isEmpty() || name.isEmpty()) {
        return Optional.empty();
      }
      if (!("S".equalsIgnoreCase(type) || "D".equalsIgnoreCase(type))) {
        return Optional.empty();
      }
      int trackNumber;
      try {
        trackNumber = Integer.parseInt(trackSegment);
      } catch (NumberFormatException ex) {
        return Optional.empty();
      }
      WaypointMetadata waypointMetadata =
          "S".equalsIgnoreCase(type)
              ? WaypointMetadata.station(operator, name, trackNumber)
              : WaypointMetadata.depot(operator, name, trackNumber);
      return Optional.of(
          new SignNodeDefinition(
              NodeId.of(trimmed), nodeType, Optional.of(trimmed), Optional.of(waypointMetadata)));
    }

    if (segments.length == SEGMENTS_WITH_SEQUENCE) {
      String operator = segments[0].trim();
      String second = segments[1].trim();
      String third = segments[2].trim();
      String trackSegment = segments[3].trim();
      String sequence = segments[4].trim();
      if (operator.isEmpty() || second.isEmpty() || third.isEmpty() || sequence.isEmpty()) {
        return Optional.empty();
      }

      int trackNumber;
      try {
        trackNumber = Integer.parseInt(trackSegment);
      } catch (NumberFormatException ex) {
        return Optional.empty();
      }

      WaypointMetadata waypointMetadata;
      // 5 段 + S/D：站/库咽喉（不兼容旧格式的 4 段咽喉）
      if ("S".equalsIgnoreCase(second)) {
        waypointMetadata = WaypointMetadata.stationThroat(operator, third, trackNumber, sequence);
      } else if ("D".equalsIgnoreCase(second)) {
        waypointMetadata = WaypointMetadata.depotThroat(operator, third, trackNumber, sequence);
      } else {
        // 5 段 + 非 S/D：区间点
        waypointMetadata =
            WaypointMetadata.interval(operator, second, third, trackNumber, sequence);
      }

      return Optional.of(
          new SignNodeDefinition(
              NodeId.of(trimmed), nodeType, Optional.of(trimmed), Optional.of(waypointMetadata)));
    }

    return Optional.empty();
  }
}
