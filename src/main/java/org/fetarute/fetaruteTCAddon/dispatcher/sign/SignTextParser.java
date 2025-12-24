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
 *   <li>站咽喉（Station throat）：{@code Operator:S:Station:Track}（4 段，不带 Seq）
 *   <li>车库咽喉（Depot throat）：{@code Operator:D:Depot:Track}（4 段，不带 Seq）
 * </ul>
 */
public final class SignTextParser {

  private static final int SEGMENTS_WITH_SEQUENCE = 5;
  private static final int SEGMENTS_NO_SEQUENCE = 4;

  private SignTextParser() {}

  /**
   * 解析形如 Operator:From:To:Track:Seq 的编码，兼容站咽喉与 Depot throat。
   *
   * <p>站咽喉与 Depot 不携带 Seq：Operator:S/D:Name:Track。
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
    if (segments.length == SEGMENTS_NO_SEQUENCE) {
      // 4 段格式仅保留给 S/D（站/库），避免与普通站点命名冲突。
      String operator = segments[0].trim();
      String second = segments[1].trim();
      String third = segments[2].trim();
      String trackSegment = segments[3].trim();
      if (operator.isEmpty() || second.isEmpty() || third.isEmpty()) {
        return Optional.empty();
      }
      if (!("S".equalsIgnoreCase(second) || "D".equalsIgnoreCase(second))) {
        return Optional.empty();
      }
      int trackNumber;
      try {
        trackNumber = Integer.parseInt(trackSegment);
      } catch (NumberFormatException ex) {
        return Optional.empty();
      }
      WaypointMetadata waypointMetadata =
          "S".equalsIgnoreCase(second)
              ? WaypointMetadata.throat(operator, third, trackNumber)
              : WaypointMetadata.depot(operator, third, trackNumber);
      return Optional.of(
          new SignNodeDefinition(
              NodeId.of(trimmed), nodeType, Optional.of(trimmed), Optional.of(waypointMetadata)));
    }

    if (segments.length == SEGMENTS_WITH_SEQUENCE) {
      // 5 段格式为区间点：第二段/第三段分别是 From/To，且禁止使用保留字 S/D。
      String operator = segments[0].trim();
      String second = segments[1].trim();
      String third = segments[2].trim();
      String trackSegment = segments[3].trim();
      String sequence = segments[4].trim();
      if (operator.isEmpty() || second.isEmpty() || third.isEmpty() || sequence.isEmpty()) {
        return Optional.empty();
      }
      if ("S".equalsIgnoreCase(second) || "D".equalsIgnoreCase(second)) {
        return Optional.empty();
      }
      int trackNumber;
      try {
        trackNumber = Integer.parseInt(trackSegment);
      } catch (NumberFormatException ex) {
        return Optional.empty();
      }
      WaypointMetadata waypointMetadata =
          WaypointMetadata.interval(operator, second, third, trackNumber, sequence);
      return Optional.of(
          new SignNodeDefinition(
              NodeId.of(trimmed), nodeType, Optional.of(trimmed), Optional.of(waypointMetadata)));
    }

    return Optional.empty();
  }
}
