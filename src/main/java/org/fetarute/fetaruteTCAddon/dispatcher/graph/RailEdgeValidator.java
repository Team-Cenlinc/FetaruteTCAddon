package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;

/**
 * 调度图边验证器：用于检测并过滤不合理的边（如跨轨道直连）。
 *
 * <p>规则：
 *
 * <ul>
 *   <li>Track 约束：同一区间的不同轨道（如 :1: 和 :2:）的 waypoint/station 不应直接相连，中间必须有 switcher
 *   <li>若检测到跨轨道直连，会返回警告信息供运维参考
 * </ul>
 */
public final class RailEdgeValidator {

  private RailEdgeValidator() {}

  /**
   * 从节点 ID 中提取轨道号（如果存在）。
   *
   * <p>支持格式：
   *
   * <ul>
   *   <li>Waypoint: {@code Operator:From:To:Track:Seq} -> Track
   *   <li>Station: {@code Operator:S:Station:Track} -> Track
   *   <li>Depot: {@code Operator:D:Depot:Track} -> Track
   * </ul>
   *
   * @return 轨道号字符串，若无法解析则返回 null
   */
  public static String extractTrack(String nodeId) {
    if (nodeId == null || nodeId.isEmpty()) {
      return null;
    }
    String[] parts = nodeId.split(":");
    if (parts.length == 5) {
      // Waypoint: Operator:From:To:Track:Seq
      return parts[3];
    } else if (parts.length == 4) {
      // Station/Depot: Operator:S/D:Name:Track
      return parts[3];
    }
    return null;
  }

  /**
   * 从节点 ID 中提取区间标识（用于判断是否属于同一区间）。
   *
   * <p>支持格式：
   *
   * <ul>
   *   <li>Waypoint: {@code Operator:From:To:Track:Seq} -> {@code Operator:From:To}
   *   <li>Station: {@code Operator:S:Station:Track} -> {@code Operator:S:Station}
   *   <li>Depot: {@code Operator:D:Depot:Track} -> {@code Operator:D:Depot}
   * </ul>
   *
   * @return 区间标识字符串，若无法解析则返回 null
   */
  public static String extractSection(String nodeId) {
    if (nodeId == null || nodeId.isEmpty()) {
      return null;
    }
    String[] parts = nodeId.split(":");
    if (parts.length == 5) {
      // Waypoint: Operator:From:To:Track:Seq -> Operator:From:To
      return parts[0] + ":" + parts[1] + ":" + parts[2];
    } else if (parts.length == 4) {
      // Station/Depot: Operator:S/D:Name:Track -> Operator:S/D:Name
      return parts[0] + ":" + parts[1] + ":" + parts[2];
    }
    return null;
  }

  /**
   * 检查两个节点是否违反跨轨道直连约束。
   *
   * <p>规则：同一区间的不同轨道（如 :1: 和 :2:）的 waypoint/station/depot 不应直接相连。
   *
   * @param nodeA 节点 A 的 ID
   * @param nodeB 节点 B 的 ID
   * @param nodeTypeA 节点 A 的类型（可为 null，若为 SWITCHER 则跳过检查）
   * @param nodeTypeB 节点 B 的类型（可为 null，若为 SWITCHER 则跳过检查）
   * @return 若违反约束返回 true
   */
  public static boolean violatesCrossTrackConstraint(
      String nodeA, String nodeB, NodeType nodeTypeA, NodeType nodeTypeB) {
    // Switcher 节点可以连接不同轨道
    if (nodeTypeA == NodeType.SWITCHER || nodeTypeB == NodeType.SWITCHER) {
      return false;
    }

    String sectionA = extractSection(nodeA);
    String sectionB = extractSection(nodeB);
    if (sectionA == null || sectionB == null) {
      return false; // 无法解析，不做约束
    }

    // 同一区间的不同轨道才需要检查
    if (!sectionA.equals(sectionB)) {
      return false; // 不同区间，允许直连
    }

    String trackA = extractTrack(nodeA);
    String trackB = extractTrack(nodeB);
    if (trackA == null || trackB == null) {
      return false; // 无法解析轨道号
    }

    // 同一区间、不同轨道 -> 违反约束
    return !trackA.equals(trackB);
  }

  /**
   * 验证图中所有边，返回违反跨轨道约束的边列表。
   *
   * @param graph 待验证的图
   * @return 违反约束的边列表（可能为空）
   */
  public static List<EdgeViolation> validateCrossTrackConstraints(RailGraph graph) {
    Objects.requireNonNull(graph, "graph");
    List<EdgeViolation> violations = new ArrayList<>();

    Map<NodeId, RailNode> nodeMap = new java.util.HashMap<>();
    for (RailNode node : graph.nodes()) {
      nodeMap.put(node.id(), node);
    }

    for (RailEdge edge : graph.edges()) {
      RailNode nodeA = nodeMap.get(edge.from());
      RailNode nodeB = nodeMap.get(edge.to());
      NodeType typeA = nodeA != null ? nodeA.type() : null;
      NodeType typeB = nodeB != null ? nodeB.type() : null;

      if (violatesCrossTrackConstraint(edge.from().value(), edge.to().value(), typeA, typeB)) {
        violations.add(
            new EdgeViolation(
                edge,
                ViolationType.CROSS_TRACK_DIRECT_CONNECTION,
                String.format(
                    "跨轨道直连：%s ↔ %s (len=%d)，同一区间的不同轨道应通过 switcher 连接",
                    edge.from().value(), edge.to().value(), edge.lengthBlocks())));
      }
    }

    return violations;
  }

  /**
   * 从边集合中过滤掉违反跨轨道约束的边。
   *
   * @param edges 待过滤的边 ID -> 长度映射
   * @param nodesById 节点映射（用于获取节点类型）
   * @return 过滤后的边映射
   */
  public static Map<EdgeId, Integer> filterCrossTrackEdges(
      Map<EdgeId, Integer> edges, Map<NodeId, RailNode> nodesById) {
    Objects.requireNonNull(edges, "edges");
    Map<EdgeId, Integer> filtered = new java.util.HashMap<>();

    for (Map.Entry<EdgeId, Integer> entry : edges.entrySet()) {
      EdgeId edgeId = entry.getKey();
      NodeId fromId = edgeId.a();
      NodeId toId = edgeId.b();

      RailNode nodeA = nodesById != null ? nodesById.get(fromId) : null;
      RailNode nodeB = nodesById != null ? nodesById.get(toId) : null;
      NodeType typeA = nodeA != null ? nodeA.type() : null;
      NodeType typeB = nodeB != null ? nodeB.type() : null;

      if (!violatesCrossTrackConstraint(fromId.value(), toId.value(), typeA, typeB)) {
        filtered.put(edgeId, entry.getValue());
      }
    }

    return filtered;
  }

  /** 边违规类型。 */
  public enum ViolationType {
    /** 同一区间的不同轨道直接相连（中间缺少 switcher）。 */
    CROSS_TRACK_DIRECT_CONNECTION
  }

  /** 边违规记录。 */
  public record EdgeViolation(RailEdge edge, ViolationType type, String message) {
    public EdgeViolation {
      Objects.requireNonNull(edge, "edge");
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(message, "message");
    }
  }
}
