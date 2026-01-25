package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;

/**
 * RailGraph 合并工具：用于把一次 build 的结果合并进已有快照。
 *
 * <p>策略：默认“追加 + 覆盖重扫分量”：
 *
 * <ul>
 *   <li>若 update 与 base 没有节点 ID 交集：直接追加（支持多个 Connected Components）
 *   <li>若 update 与 base 存在交集：认为重扫到了已有分量，先删除 base 中对应连通分量，再写入 update
 * </ul>
 *
 * <p>注意：该合并仅基于节点 ID 与 base 图的连通性判断，不会主动访问世界轨道或加载区块。
 */
public final class RailGraphMerger {

  private RailGraphMerger() {}

  /**
   * 安全增量合并：把 update 的节点/边 upsert 到 base 中，不会删除 base 中任何节点/边。
   *
   * <p>用途：在“未加载区块视为不可达”或达到 maxChunks 限制时，本次 build 的节点集合可能只是子集；此时不应替换旧分量， 否则会误删旧图中尚未扫描到的节点/边。
   */
  public static MergeResult upsert(RailGraph base, RailGraph update) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(update, "update");

    Map<NodeId, RailNode> nodesById = new HashMap<>();
    for (RailNode node : base.nodes()) {
      nodesById.put(node.id(), node);
    }
    for (RailNode updateNode : update.nodes()) {
      RailNode existing = nodesById.get(updateNode.id());
      if (existing != null) {
        // 合并节点属性：保留旧节点的元数据（如果新节点没有）
        nodesById.put(updateNode.id(), mergeNodeAttributes(existing, updateNode));
      } else {
        nodesById.put(updateNode.id(), updateNode);
      }
    }

    Map<EdgeId, RailEdge> edgesById = new HashMap<>();
    for (RailEdge edge : base.edges()) {
      edgesById.put(edge.id(), edge);
    }
    for (RailEdge edge : update.edges()) {
      if (nodesById.containsKey(edge.from()) && nodesById.containsKey(edge.to())) {
        // 取最短边长，避免"绕道边"覆盖已有最短路径
        RailEdge existing = edgesById.get(edge.id());
        if (existing == null || edge.lengthBlocks() < existing.lengthBlocks()) {
          edgesById.put(edge.id(), edge);
        }
      }
    }

    Set<EdgeId> blockedEdges = new HashSet<>();
    for (RailEdge edge : edgesById.values()) {
      EdgeId edgeId = edge.id();
      if (base.isBlocked(edgeId) || update.isBlocked(edgeId)) {
        blockedEdges.add(edgeId);
      }
    }

    RailGraph merged = new SimpleRailGraph(nodesById, edgesById, blockedEdges);
    return new MergeResult(
        merged, MergeAction.UPSERT, 0, 0, 0, merged.nodes().size(), merged.edges().size());
  }

  /**
   * 合并一次 build 的结果：将 update 的节点/边合并到 base 中。
   *
   * <p>合并策略（v2 - 保守合并）：
   *
   * <ul>
   *   <li>节点：update 中的节点会覆盖 base 中同 ID 的节点，但保留 base 中"update 未覆盖"的节点
   *   <li>边：取最短长度；只删除"两端节点都在 update 中"的旧边，保留"跨越 update 边界"的边
   *   <li>元数据：合并节点时保留旧节点的运维元数据（如果新节点没有）
   * </ul>
   *
   * <p>该策略确保多次局部 build 不会意外删除其他区域的数据。
   *
   * <p>若需要完全替换某个区域，请先用 {@code /fta graph delete here} 清理后再 build。
   */
  public static MergeResult appendOrReplaceComponents(RailGraph base, RailGraph update) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(update, "update");

    // 收集 update 中的节点 ID
    Set<NodeId> updateNodeIds = new HashSet<>();
    for (RailNode node : update.nodes()) {
      updateNodeIds.add(node.id());
    }

    // 构建合并后的节点映射
    Map<NodeId, RailNode> nodesById = new HashMap<>();
    for (RailNode node : base.nodes()) {
      nodesById.put(node.id(), node);
    }

    // 统计：被覆盖的节点数
    int overlappedNodes = 0;
    for (RailNode updateNode : update.nodes()) {
      RailNode existing = nodesById.get(updateNode.id());
      if (existing != null) {
        overlappedNodes++;
        // 合并节点属性：保留旧节点的元数据（如果新节点没有）
        RailNode merged = mergeNodeAttributes(existing, updateNode);
        nodesById.put(updateNode.id(), merged);
      } else {
        nodesById.put(updateNode.id(), updateNode);
      }
    }

    // 构建合并后的边映射
    Map<EdgeId, RailEdge> edgesById = new HashMap<>();

    // 先加入 base 的边（排除"两端都在 update 中"的边，这些边会被 update 的边替换）
    int removedEdges = 0;
    for (RailEdge edge : base.edges()) {
      boolean fromInUpdate = updateNodeIds.contains(edge.from());
      boolean toInUpdate = updateNodeIds.contains(edge.to());
      if (fromInUpdate && toInUpdate) {
        // 两端都在 update 中，这条边会被 update 的新边替换
        removedEdges++;
        continue;
      }
      edgesById.put(edge.id(), edge);
    }

    // 加入 update 的边（取最短）
    for (RailEdge edge : update.edges()) {
      if (nodesById.containsKey(edge.from()) && nodesById.containsKey(edge.to())) {
        RailEdge existing = edgesById.get(edge.id());
        if (existing == null || edge.lengthBlocks() < existing.lengthBlocks()) {
          edgesById.put(edge.id(), edge);
        }
      }
    }

    // 保留 blocked 状态
    Set<EdgeId> blockedEdges = new HashSet<>();
    for (RailEdge edge : edgesById.values()) {
      EdgeId edgeId = edge.id();
      if (base.isBlocked(edgeId) || update.isBlocked(edgeId)) {
        blockedEdges.add(edgeId);
      }
    }

    MergeAction action = overlappedNodes > 0 ? MergeAction.REPLACE_COMPONENTS : MergeAction.APPEND;

    RailGraph merged = new SimpleRailGraph(nodesById, edgesById, blockedEdges);
    return new MergeResult(
        merged,
        action,
        0, // 不再删除整个连通分量
        0, // 不删除节点，只覆盖
        removedEdges,
        merged.nodes().size(),
        merged.edges().size());
  }

  /**
   * 合并节点属性：新节点覆盖旧节点，但保留旧节点的运维元数据。
   *
   * <p>规则：
   *
   * <ul>
   *   <li>位置、类型：使用新节点的值（反映当前世界状态）
   *   <li>waypointMetadata：如果新节点有则用新的，否则保留旧的
   *   <li>trainCartsDestination：如果新节点有则用新的，否则保留旧的
   * </ul>
   */
  private static RailNode mergeNodeAttributes(RailNode existing, RailNode update) {
    if (!(existing instanceof SignRailNode) || !(update instanceof SignRailNode)) {
      return update; // 非 SignRailNode 直接用新节点
    }
    SignRailNode existingSign = (SignRailNode) existing;
    SignRailNode updateSign = (SignRailNode) update;

    // 保留旧节点的元数据（如果新节点没有）
    var waypointMeta =
        updateSign.waypointMetadata().isPresent()
            ? updateSign.waypointMetadata()
            : existingSign.waypointMetadata();
    var tcDest =
        updateSign.trainCartsDestination().isPresent()
            ? updateSign.trainCartsDestination()
            : existingSign.trainCartsDestination();

    return new SignRailNode(
        updateSign.id(), updateSign.type(), updateSign.worldPosition(), tcDest, waypointMeta);
  }

  /**
   * 从 base 中删除包含 seeds 的连通分量。
   *
   * <p>用途：运维清理/局部重建。删除后会一并移除分量内的所有节点与相关边。
   *
   * @param seeds 用于定位待删除分量的种子节点 ID 集合；不存在的 seed 会被忽略
   */
  public static RemoveResult removeComponents(RailGraph base, Set<NodeId> seeds) {
    Objects.requireNonNull(base, "base");
    Set<NodeId> seedSnapshot = seeds != null ? new HashSet<>(seeds) : Set.of();
    if (seedSnapshot.isEmpty()) {
      return new RemoveResult(base, 0, 0, 0, base.nodes().size(), base.edges().size());
    }

    Set<NodeId> effectiveSeeds = new HashSet<>();
    for (NodeId seed : seedSnapshot) {
      if (seed == null) {
        continue;
      }
      if (base.findNode(seed).isPresent()) {
        effectiveSeeds.add(seed);
      }
    }
    if (effectiveSeeds.isEmpty()) {
      return new RemoveResult(base, 0, 0, 0, base.nodes().size(), base.edges().size());
    }

    ComponentSet components = collectComponents(base, effectiveSeeds);
    Set<NodeId> removed = components.nodes();
    if (removed.isEmpty()) {
      return new RemoveResult(base, 0, 0, 0, base.nodes().size(), base.edges().size());
    }

    Map<NodeId, RailNode> nodesById = new HashMap<>();
    for (RailNode node : base.nodes()) {
      if (node == null || removed.contains(node.id())) {
        continue;
      }
      nodesById.put(node.id(), node);
    }
    int removedNodes = base.nodes().size() - nodesById.size();

    Map<EdgeId, RailEdge> edgesById = new HashMap<>();
    for (RailEdge edge : base.edges()) {
      if (edge == null) {
        continue;
      }
      if (removed.contains(edge.from()) || removed.contains(edge.to())) {
        continue;
      }
      edgesById.put(edge.id(), edge);
    }
    int removedEdges = base.edges().size() - edgesById.size();

    Set<EdgeId> blockedEdges = new HashSet<>();
    for (RailEdge edge : edgesById.values()) {
      if (base.isBlocked(edge.id())) {
        blockedEdges.add(edge.id());
      }
    }

    RailGraph next = new SimpleRailGraph(nodesById, edgesById, blockedEdges);
    return new RemoveResult(
        next,
        components.componentCount(),
        removedNodes,
        removedEdges,
        next.nodes().size(),
        next.edges().size());
  }

  private static ComponentSet collectComponents(RailGraph graph, Set<NodeId> seeds) {
    Set<NodeId> visited = new HashSet<>();
    ArrayDeque<NodeId> queue = new ArrayDeque<>();
    int components = 0;

    for (NodeId seed : seeds) {
      if (seed == null) {
        continue;
      }
      if (!visited.add(seed)) {
        continue;
      }
      components++;
      queue.add(seed);
      while (!queue.isEmpty()) {
        NodeId current = queue.poll();
        for (RailEdge edge : graph.edgesFrom(current)) {
          NodeId neighbor = otherEndpoint(edge, current);
          if (neighbor == null) {
            continue;
          }
          if (visited.add(neighbor)) {
            queue.add(neighbor);
          }
        }
      }
    }

    return new ComponentSet(Set.copyOf(visited), components);
  }

  private static NodeId otherEndpoint(RailEdge edge, NodeId current) {
    if (edge == null || current == null) {
      return null;
    }
    if (current.equals(edge.from())) {
      return edge.to();
    }
    if (current.equals(edge.to())) {
      return edge.from();
    }
    return null;
  }

  private record ComponentSet(Set<NodeId> nodes, int componentCount) {
    private ComponentSet {
      Objects.requireNonNull(nodes, "nodes");
      if (componentCount < 0) {
        throw new IllegalArgumentException("componentCount 不能为负数");
      }
    }
  }

  public enum MergeAction {
    /** update 与 base 不重叠，直接追加。 */
    APPEND,
    /** update 覆盖 base 中同 ID 的节点/边，不做删除。 */
    UPSERT,
    /** update 与 base 有重叠，替换对应连通分量后再追加。 */
    REPLACE_COMPONENTS
  }

  /** 合并结果：包含最终图与统计信息（用于命令回显与诊断）。 */
  public record MergeResult(
      RailGraph graph,
      MergeAction action,
      int replacedComponentCount,
      int removedNodes,
      int removedEdges,
      int totalNodes,
      int totalEdges) {
    public MergeResult {
      Objects.requireNonNull(graph, "graph");
      Objects.requireNonNull(action, "action");
      if (replacedComponentCount < 0
          || removedNodes < 0
          || removedEdges < 0
          || totalNodes < 0
          || totalEdges < 0) {
        throw new IllegalArgumentException("merge 计数不能为负数");
      }
    }
  }

  /** 删除连通分量的结果：包含最终图与统计信息（用于命令回显与诊断）。 */
  public record RemoveResult(
      RailGraph graph,
      int removedComponentCount,
      int removedNodes,
      int removedEdges,
      int totalNodes,
      int totalEdges) {
    public RemoveResult {
      Objects.requireNonNull(graph, "graph");
      if (removedComponentCount < 0
          || removedNodes < 0
          || removedEdges < 0
          || totalNodes < 0
          || totalEdges < 0) {
        throw new IllegalArgumentException("remove 计数不能为负数");
      }
    }
  }
}
