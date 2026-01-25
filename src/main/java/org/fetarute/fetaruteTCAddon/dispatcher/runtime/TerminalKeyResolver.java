package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.util.Locale;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * 终到站标识（terminalKey）解析与匹配工具。
 *
 * <p>核心职责：
 *
 * <ul>
 *   <li>从 NodeId 提取 terminalKey（用于 Layover 注册）
 *   <li>判断两个 terminalKey 是否"可复用匹配"
 *   <li>支持同一站点不同站台的模糊匹配
 * </ul>
 *
 * <h3>NodeId 格式约定</h3>
 *
 * <ul>
 *   <li>Station: {@code Operator:S:Station:Track}（4 段）
 *   <li>Depot: {@code Operator:D:Depot:Track}（4 段）
 *   <li>Waypoint: {@code Operator:From:To:Track:Seq}（5 段）
 *   <li>Station throat: {@code Operator:S:Station:Track:Seq}（5 段）
 *   <li>Depot throat: {@code Operator:D:Depot:Track:Seq}（5 段）
 * </ul>
 *
 * <h3>匹配规则</h3>
 *
 * <p>Layover 复用时，判定"候选列车位置"与"目标线路首站"是否匹配：
 *
 * <ul>
 *   <li><b>精确匹配</b>：完整 NodeId 相同（忽略大小写）
 *   <li><b>站点匹配</b>：提取 Station/Depot code 相同（允许不同站台复用）
 * </ul>
 *
 * <p>例如：
 *
 * <ul>
 *   <li>{@code OP:S:Central:1} 与 {@code OP:S:Central:2} → 站点匹配（同站不同站台）
 *   <li>{@code OP:S:Central:1} 与 {@code OP:S:Downtown:1} → 不匹配（不同站点）
 *   <li>{@code OP:D:Depot1:1} 与 {@code OP:D:Depot1:2} → 站点匹配（同车库不同股道）
 * </ul>
 */
public final class TerminalKeyResolver {

  private TerminalKeyResolver() {}

  /**
   * 从 NodeId 生成 terminalKey。
   *
   * <p>返回完整的 NodeId 值（小写），用于 Layover 注册。
   *
   * @param nodeId 节点 ID
   * @return terminalKey，若 nodeId 为 null 则返回空字符串
   */
  public static String toTerminalKey(NodeId nodeId) {
    if (nodeId == null || nodeId.value() == null) {
      return "";
    }
    return nodeId.value().toLowerCase(Locale.ROOT);
  }

  /**
   * 判断两个 terminalKey 是否匹配（用于 Layover 复用判定）。
   *
   * <p>匹配规则：
   *
   * <ol>
   *   <li>精确匹配：完整 terminalKey 相同（忽略大小写）
   *   <li>站点匹配：提取的 stationKey 相同（允许不同站台）
   * </ol>
   *
   * @param layoverKey Layover 注册时的 terminalKey（列车当前位置）
   * @param targetKey 目标线路首站的 terminalKey
   * @return 是否匹配
   */
  public static boolean matches(String layoverKey, String targetKey) {
    if (layoverKey == null || targetKey == null) {
      return false;
    }
    String normalizedLayover = layoverKey.toLowerCase(Locale.ROOT).trim();
    String normalizedTarget = targetKey.toLowerCase(Locale.ROOT).trim();

    // 精确匹配
    if (normalizedLayover.equals(normalizedTarget)) {
      return true;
    }

    // 站点匹配：提取 stationKey 进行比较
    Optional<String> layoverStationKey = extractStationKey(normalizedLayover);
    Optional<String> targetStationKey = extractStationKey(normalizedTarget);

    if (layoverStationKey.isPresent() && targetStationKey.isPresent()) {
      return layoverStationKey.get().equals(targetStationKey.get());
    }

    return false;
  }

  /**
   * 从 NodeId 值提取 Station/Depot 级别的 key（忽略 Track 和 Seq）。
   *
   * <p>用于同站不同站台的模糊匹配。
   *
   * <p>规则：
   *
   * <ul>
   *   <li>4 段格式 {@code Operator:S/D:Name:Track} → 返回 {@code Operator:S/D:Name}
   *   <li>5 段格式 {@code Operator:S/D:Name:Track:Seq} → 返回 {@code Operator:S/D:Name}
   *   <li>其他格式 → 返回 empty
   * </ul>
   *
   * @param nodeIdValue NodeId 的原始值
   * @return stationKey，若无法解析则返回 empty
   */
  public static Optional<String> extractStationKey(String nodeIdValue) {
    if (nodeIdValue == null || nodeIdValue.isBlank()) {
      return Optional.empty();
    }

    String[] parts = nodeIdValue.split(":");
    if (parts.length < 4) {
      return Optional.empty();
    }

    // 检查第二段是否为 S 或 D（表示 Station 或 Depot）
    String typeMarker = parts[1].toUpperCase(Locale.ROOT);
    if (!"S".equals(typeMarker) && !"D".equals(typeMarker)) {
      return Optional.empty();
    }

    // 返回 Operator:Type:Name（前三段）
    String operator = parts[0].toLowerCase(Locale.ROOT);
    String name = parts[2].toLowerCase(Locale.ROOT);
    return Optional.of(operator + ":" + typeMarker.toLowerCase(Locale.ROOT) + ":" + name);
  }

  /**
   * 从 NodeId 提取 Station/Depot 的名称（不含 Operator 和 Type）。
   *
   * <p>用于 UI 显示和日志输出。
   *
   * @param nodeId 节点 ID
   * @return Station/Depot 名称，若无法解析则返回完整 NodeId 值
   */
  public static String extractStationName(NodeId nodeId) {
    if (nodeId == null || nodeId.value() == null) {
      return "";
    }

    String[] parts = nodeId.value().split(":");
    if (parts.length >= 3) {
      String typeMarker = parts[1].toUpperCase(Locale.ROOT);
      if ("S".equals(typeMarker) || "D".equals(typeMarker)) {
        return parts[2];
      }
    }

    return nodeId.value();
  }

  /**
   * 判断 NodeId 是否为 Station 类型节点。
   *
   * @param nodeId 节点 ID
   * @return 是否为 Station 节点
   */
  public static boolean isStationNode(NodeId nodeId) {
    if (nodeId == null || nodeId.value() == null) {
      return false;
    }
    String[] parts = nodeId.value().split(":");
    return parts.length >= 3 && "S".equalsIgnoreCase(parts[1]);
  }

  /**
   * 判断 NodeId 是否为 Depot 类型节点。
   *
   * @param nodeId 节点 ID
   * @return 是否为 Depot 节点
   */
  public static boolean isDepotNode(NodeId nodeId) {
    if (nodeId == null || nodeId.value() == null) {
      return false;
    }
    String[] parts = nodeId.value().split(":");
    return parts.length >= 3 && "D".equalsIgnoreCase(parts[1]);
  }
}
