package org.fetarute.fetaruteTCAddon.api.graph;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 调度图 API：提供轨道网络的只读访问。
 *
 * <p>调度图是 FTA 的核心数据结构，描述轨道网络的拓扑结构：
 *
 * <ul>
 *   <li><b>节点（Node）</b>：站点、车库、道岔、路标等关键位置
 *   <li><b>边（Edge）</b>：连接节点的轨道段，包含距离和限速信息
 *   <li><b>连通分量（Component）</b>：相互可达的节点集合
 * </ul>
 *
 * <h2>使用示例</h2>
 *
 * <pre>{@code
 * GraphApi graph = api.graph();
 *
 * // 获取某世界的图快照
 * graph.getSnapshot(worldId).ifPresent(snapshot -> {
 *     // 遍历所有节点
 *     for (ApiNode node : snapshot.nodes()) {
 *         System.out.println(node.id() + " @ " + node.position());
 *     }
 *
 *     // 查询两点间最短路径
 *     graph.findShortestPath(worldId, startNode, endNode).ifPresent(path -> {
 *         System.out.println("距离: " + path.totalDistanceBlocks() + " blocks");
 *     });
 * });
 * }</pre>
 *
 * <h2>线程安全</h2>
 *
 * <p>所有返回的快照和集合均为不可变，可安全在任意线程使用。
 */
public interface GraphApi {

  /**
   * 获取指定世界的调度图快照。
   *
   * @param worldId 世界 UUID
   * @return 图快照，若该世界无图则返回 empty
   */
  Optional<GraphSnapshot> getSnapshot(UUID worldId);

  /**
   * 获取所有已加载世界的图快照。
   *
   * @return 世界 UUID 到图快照的映射（不可变）
   */
  Collection<WorldGraphEntry> listAllSnapshots();

  /**
   * 查询两点间的最短路径。
   *
   * @param worldId 世界 UUID
   * @param fromNodeId 起点节点 ID
   * @param toNodeId 终点节点 ID
   * @return 路径结果，若不可达则返回 empty
   */
  Optional<PathResult> findShortestPath(UUID worldId, String fromNodeId, String toNodeId);

  /**
   * 获取节点所属的连通分量标识。
   *
   * @param worldId 世界 UUID
   * @param nodeId 节点 ID
   * @return 分量标识字符串，若节点不存在则返回 empty
   */
  Optional<String> getComponentKey(UUID worldId, String nodeId);

  /**
   * 检查图是否需要重建（stale 状态）。
   *
   * @param worldId 世界 UUID
   * @return stale 状态信息
   */
  Optional<StaleInfo> getStaleInfo(UUID worldId);

  // ─────────────────────────────────────────────────────────────────────────────
  // 数据模型
  // ─────────────────────────────────────────────────────────────────────────────

  /** 世界与图快照的组合。 */
  record WorldGraphEntry(UUID worldId, GraphSnapshot snapshot) {}

  /**
   * 调度图快照：包含某一时刻的完整图结构。
   *
   * @param nodes 所有节点（不可变列表）
   * @param edges 所有边（不可变列表）
   * @param builtAt 图构建时间
   * @param nodeCount 节点数量
   * @param edgeCount 边数量
   * @param componentCount 连通分量数量
   */
  record GraphSnapshot(
      List<ApiNode> nodes,
      List<ApiEdge> edges,
      Instant builtAt,
      int nodeCount,
      int edgeCount,
      int componentCount) {

    public GraphSnapshot {
      nodes = nodes == null ? List.of() : List.copyOf(nodes);
      edges = edges == null ? List.of() : List.copyOf(edges);
    }
  }

  /**
   * 节点信息。
   *
   * @param id 节点 ID（如 "SURN:S:AAA:1"）
   * @param type 节点类型
   * @param position 世界坐标
   * @param displayName 显示名称（可选）
   */
  record ApiNode(String id, NodeType type, Position position, Optional<String> displayName) {}

  /** 节点类型枚举。 */
  enum NodeType {
    /** 站点/站台 */
    STATION,
    /** 车库 */
    DEPOT,
    /** 路标/咽喉 */
    WAYPOINT,
    /** 道岔 */
    SWITCHER,
    /** 未知 */
    UNKNOWN
  }

  /**
   * 三维坐标。
   *
   * @param x X 坐标
   * @param y Y 坐标
   * @param z Z 坐标
   */
  record Position(double x, double y, double z) {}

  /**
   * 边信息。
   *
   * @param id 边 ID
   * @param nodeA 端点 A 的节点 ID
   * @param nodeB 端点 B 的节点 ID
   * @param lengthBlocks 长度（blocks）
   * @param speedLimitBps 限速（blocks/s），0 表示无限速
   * @param bidirectional 是否双向
   * @param blocked 是否被封锁
   */
  record ApiEdge(
      String id,
      String nodeA,
      String nodeB,
      int lengthBlocks,
      double speedLimitBps,
      boolean bidirectional,
      boolean blocked) {}

  /**
   * 路径查询结果。
   *
   * @param nodes 路径经过的节点 ID 列表（有序）
   * @param edges 路径经过的边 ID 列表（有序）
   * @param totalDistanceBlocks 总距离（blocks）
   * @param estimatedTravelTimeSec 预估行程时间（秒）
   */
  record PathResult(
      List<String> nodes,
      List<String> edges,
      int totalDistanceBlocks,
      int estimatedTravelTimeSec) {}

  /**
   * 图 stale 状态信息。
   *
   * @param stale 是否 stale
   * @param reason 原因描述
   * @param lastBuiltAt 最后构建时间
   */
  record StaleInfo(boolean stale, String reason, Optional<Instant> lastBuiltAt) {}
}
