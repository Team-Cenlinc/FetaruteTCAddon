package org.fetarute.fetaruteTCAddon.dispatcher.sign;

import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;

/**
 * 解析节点牌子文本中的 NodeId 编码（纯解析，不绑定“行为”语义）。
 *
 * <p>本类只负责把字符串拆解为 {@link WaypointMetadata}，并不决定“某种牌子是否允许某种编码”。允许/拒绝由各 {@code SignAction} 在 {@code
 * parseDefinition()} 的过滤规则中完成。
 *
 * <p>当前格式约定（不兼容旧格式）：
 *
 * <ul>
 *   <li>区间点（Waypoint interval）：{@code Operator:From:To:Track:Seq}（5 段，且第二段不得为 {@code S/D}）
 *   <li>站点（Station，本体）：{@code Operator:S:Station:Track}（4 段）
 *   <li>车库（Depot，本体）：{@code Operator:D:Depot:Track}（4 段）
 *   <li>站咽喉（Station throat，图节点/Waypoint）：{@code Operator:S:Station:Track:Seq}（5 段）
 *   <li>车库咽喉（Depot throat，图节点/Waypoint）：{@code Operator:D:Depot:Track:Seq}（5 段）
 * </ul>
 */
public final class SignTextParser {

  private static final int SEGMENTS_TYPED = 4;
  private static final int SEGMENTS_WITH_SEQUENCE = 5;

  private SignTextParser() {}

  /**
   * 解析节点 ID 编码并产出统一的 {@link SignNodeDefinition}。
   *
   * <p>注意：{@code nodeType} 由调用方（SignAction）决定，用于在注册表中区分牌子类型；而 {@link WaypointMetadata} 的 kind
   * 则反映“字符串编码的语义类别”（区间/站点/车库/咽喉等）。两者并不总是一一对应。
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
