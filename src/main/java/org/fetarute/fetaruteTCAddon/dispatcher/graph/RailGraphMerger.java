package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailEdgeFootprint;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingState;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;

/**
 * RailGraph 合并工具：用于把一次 build 的结果合并进已有快照。
 *
 * <p>两种合并都不删除节点：
 *
 * <ul>
 *   <li>{@link #appendOrReplaceComponents}（完整 build）：两端都在本次结果里的旧边被本次的边替换，其余旧边保留
 *   <li>{@link #upsert}（局部 build）：只增改，不删边
 * </ul>
 *
 * <p>同一区间两边都有时：完整 build 以本次为准（重新走过的轨道才是现状），局部 build 取较短的。联锁足迹逐边取：重扫到的区间只认本次的证据，没重扫的沿用旧图； 缺证据的区间让整体
 * coverage 不完整。局部合并无法证明完整 Edge universe，即使足迹齐全也按不完整发布，但已测到的足迹保留下来。
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
        edgesById.put(edge.id(), preferShorter(edgesById.get(edge.id()), edge));
      }
    }

    Set<EdgeId> blockedEdges = new HashSet<>();
    for (RailEdge edge : edgesById.values()) {
      EdgeId edgeId = edge.id();
      if (base.isBlocked(edgeId) || update.isBlocked(edgeId)) {
        blockedEdges.add(edgeId);
      }
    }

    RailGraph merged =
        new SimpleRailGraph(
            nodesById,
            edgesById,
            blockedEdges,
            mergeInterlockingState(base, update, edgesById, false));
    return new MergeResult(
        merged, MergeAction.UPSERT, 0, merged.nodes().size(), merged.edges().size());
  }

  /**
   * 只增不改地把新节点与新区间加进旧图：旧节点、旧区间原样保留。
   *
   * <p>联锁按旧图足迹加新区间足迹整体重算；旧图 coverage 完整且新区间都带完整足迹时结果仍完整。Zone 键按区间确定性生成，
   * 只有新区间与旧区间真的重叠时旧区间才会多出键——调用方据此逐键核验能否切换。
   *
   * @throws IllegalArgumentException 新节点已在旧图里、新区间已存在或端点不在合并后的图里
   */
  public static RailGraph append(
      RailGraph base,
      java.util.Collection<RailNode> newNodes,
      java.util.Collection<RailEdge> newEdges,
      Map<EdgeId, RailEdgeFootprint> newFootprints) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(newNodes, "newNodes");
    Objects.requireNonNull(newEdges, "newEdges");
    Objects.requireNonNull(newFootprints, "newFootprints");
    Map<NodeId, RailNode> nodesById = nodeMap(base.nodes());
    for (RailNode node : newNodes) {
      if (nodesById.putIfAbsent(node.id(), node) != null) {
        throw new IllegalArgumentException("增补节点已在旧图里: " + node.id().value());
      }
    }
    Map<EdgeId, RailEdge> edgesById = edgeMap(base.edges());
    for (RailEdge edge : newEdges) {
      if (!nodesById.containsKey(edge.from()) || !nodesById.containsKey(edge.to())) {
        throw new IllegalArgumentException("增补区间的端点不在图里: " + edge.id());
      }
      if (edgesById.putIfAbsent(edge.id(), edge) != null) {
        throw new IllegalArgumentException("增补区间已在旧图里: " + edge.id());
      }
    }
    RailInterlockingState baseState = interlockingState(base);
    RailInterlockingState state = RailInterlockingState.unavailable();
    if (baseState.available()) {
      Map<EdgeId, RailEdgeFootprint> footprints =
          new HashMap<>(baseState.participatingFootprints());
      footprints.putAll(newFootprints);
      state =
          RailInterlockingState.from(
              baseState.worldId().orElseThrow(), edgesById.keySet(), footprints);
      if (!baseState.coverage().complete()) {
        state = state.withCoverageMarkedIncomplete();
      }
    }
    return copyWith(base, nodesById, edgesById, state);
  }

  /**
   * 保留拓扑与足迹，但联锁整体按不完整发布。
   *
   * <p>用于无法证明完整 Edge universe 的刷新：世界 sentinel 照旧禁止旧的精确 Zone 授权放行，已测到的足迹留在索引里，写库时不会被抹掉。
   */
  public static RailGraph markInterlockingCatalogIncomplete(RailGraph graph) {
    Objects.requireNonNull(graph, "graph");
    RailInterlockingState state = interlockingState(graph);
    if (!state.available() || !state.coverage().complete()) {
      return graph;
    }
    return copyWith(
        graph,
        nodeMap(graph.nodes()),
        edgeMap(graph.edges()),
        state.withCoverageMarkedIncomplete());
  }

  /**
   * 只保留指定节点，以及两端都在其中的边。
   *
   * <p>用于刷新：库里已经删掉的节点（牌子拆了）不应被旧图带回来。剩下区间的足迹保留，旧状态不完整时继续不完整。
   */
  public static RailGraph retainNodes(RailGraph base, Set<NodeId> keep) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(keep, "keep");
    Map<NodeId, RailNode> nodesById = new HashMap<>();
    for (RailNode node : base.nodes()) {
      if (keep.contains(node.id())) {
        nodesById.put(node.id(), node);
      }
    }
    if (nodesById.size() == base.nodes().size()) {
      return base;
    }
    Map<EdgeId, RailEdge> edgesById = new HashMap<>();
    for (RailEdge edge : base.edges()) {
      if (nodesById.containsKey(edge.from()) && nodesById.containsKey(edge.to())) {
        edgesById.put(edge.id(), edge);
      }
    }
    return copyWith(base, nodesById, edgesById, retainInterlockingState(base, edgesById));
  }

  private static RailGraph copyWith(
      RailGraph source,
      Map<NodeId, RailNode> nodesById,
      Map<EdgeId, RailEdge> edgesById,
      RailInterlockingState state) {
    Set<EdgeId> blockedEdges = new HashSet<>();
    for (EdgeId edgeId : edgesById.keySet()) {
      if (source.isBlocked(edgeId)) {
        blockedEdges.add(edgeId);
      }
    }
    return new SimpleRailGraph(nodesById, edgesById, blockedEdges, state);
  }

  private static Map<NodeId, RailNode> nodeMap(java.util.Collection<RailNode> nodes) {
    Map<NodeId, RailNode> byId = new HashMap<>();
    nodes.forEach(node -> byId.put(node.id(), node));
    return byId;
  }

  private static Map<EdgeId, RailEdge> edgeMap(java.util.Collection<RailEdge> edges) {
    Map<EdgeId, RailEdge> byId = new HashMap<>();
    edges.forEach(edge -> byId.put(edge.id(), edge));
    return byId;
  }

  /** 完整 build：同一区间取本次的——整个连通分量都重新走过，改道后边长变长也要跟上。本次没有元数据时沿用旧的。 */
  private static RailEdge preferUpdate(RailEdge existing, RailEdge update) {
    return withMetadataFrom(update, existing);
  }

  /** 局部 build 与刷新：同一区间取较短的。walker 遇到没挂牌子的道岔时跟着当时的扳向走，一次局部探索可能绕远；局部证据不足以推翻较短的旧长度。 */
  private static RailEdge preferShorter(RailEdge existing, RailEdge update) {
    if (existing == null) {
      return update;
    }
    return update.lengthBlocks() < existing.lengthBlocks()
        ? withMetadataFrom(update, existing)
        : withMetadataFrom(existing, update);
  }

  /** 选中的区间没有元数据时沿用另一边的。 */
  private static RailEdge withMetadataFrom(RailEdge chosen, RailEdge other) {
    if (other == null || chosen.metadata().isPresent() || other.metadata().isEmpty()) {
      return chosen;
    }
    return new RailEdge(
        chosen.id(),
        chosen.from(),
        chosen.to(),
        chosen.lengthBlocks(),
        chosen.baseSpeedLimit(),
        chosen.bidirectional(),
        other.metadata());
  }

  /**
   * 合并一次 build 的结果：将 update 的节点/边合并到 base 中。
   *
   * <p>合并策略（v2 - 保守合并）：
   *
   * <ul>
   *   <li>节点：update 中的节点会覆盖 base 中同 ID 的节点，但保留 base 中"update 未覆盖"的节点
   *   <li>边：只删除"两端节点都在 update 中"的旧边（由本次重扫的边取代），保留"跨越 update 边界"的边
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

    for (RailEdge edge : update.edges()) {
      if (nodesById.containsKey(edge.from()) && nodesById.containsKey(edge.to())) {
        edgesById.put(edge.id(), preferUpdate(edgesById.get(edge.id()), edge));
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

    MergeAction action = overlappedNodes > 0 ? MergeAction.REPLACE_EDGES : MergeAction.APPEND;

    RailGraph merged =
        new SimpleRailGraph(
            nodesById,
            edgesById,
            blockedEdges,
            mergeInterlockingState(base, update, edgesById, true));
    return new MergeResult(
        merged, action, removedEdges, merged.nodes().size(), merged.edges().size());
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

    RailGraph next =
        new SimpleRailGraph(
            nodesById, edgesById, blockedEdges, retainInterlockingState(base, edgesById));
    return new RemoveResult(
        next,
        components.componentCount(),
        removedNodes,
        removedEdges,
        next.nodes().size(),
        next.edges().size());
  }

  /**
   * 合并后的联锁状态：逐边取足迹——重扫到的区间只认本次的证据，没重扫的沿用旧图；缺证据的区间让整体 coverage 不完整。
   *
   * <p>由 {@link RailInterlockingState#from} 按全部足迹重算，重扫区间与旧区间之间的重叠也会生成 Zone；Zone 键按区间确定性生成，
   * 没变的区间键不变。
   *
   * @param universeProven 本次 build 是否覆盖了所涉连通分量；否则即使足迹齐全也按不完整发布
   */
  private static RailInterlockingState mergeInterlockingState(
      RailGraph base, RailGraph update, Map<EdgeId, RailEdge> edgesById, boolean universeProven) {
    RailInterlockingState updateState = interlockingState(update);
    if (updateState.available() && updateState.expectedEdges().equals(edgesById.keySet())) {
      return universeProven ? updateState : updateState.withCoverageMarkedIncomplete();
    }
    RailInterlockingState baseState = interlockingState(base);
    Optional<UUID> worldId = updateState.worldId().or(baseState::worldId);
    if (worldId.isEmpty()) {
      return RailInterlockingState.unavailable();
    }
    Set<EdgeId> rediscovered = new HashSet<>();
    for (RailEdge edge : update.edges()) {
      rediscovered.add(edge.id());
    }
    Map<EdgeId, RailEdgeFootprint> baseFootprints = baseState.participatingFootprints();
    Map<EdgeId, RailEdgeFootprint> updateFootprints = updateState.participatingFootprints();
    Map<EdgeId, RailEdgeFootprint> footprints = new HashMap<>();
    for (EdgeId edge : edgesById.keySet()) {
      RailEdgeFootprint footprint =
          (rediscovered.contains(edge) ? updateFootprints : baseFootprints).get(edge);
      if (footprint != null) {
        footprints.put(edge, footprint);
      }
    }
    RailInterlockingState merged =
        RailInterlockingState.from(worldId.get(), edgesById.keySet(), footprints);
    return universeProven ? merged : merged.withCoverageMarkedIncomplete();
  }

  /** 删掉部分区间后的联锁状态：保留剩下区间的足迹；旧状态本就不完整时继续不完整。 */
  private static RailInterlockingState retainInterlockingState(
      RailGraph base, Map<EdgeId, RailEdge> edgesById) {
    RailInterlockingState state = interlockingState(base);
    if (!state.available()) {
      return RailInterlockingState.unavailable();
    }
    if (!state.cellCoverageAvailable()) {
      return state.retainEdges(edgesById.keySet());
    }
    Map<EdgeId, RailEdgeFootprint> footprints = new HashMap<>(state.participatingFootprints());
    footprints.keySet().retainAll(edgesById.keySet());
    RailInterlockingState retained =
        RailInterlockingState.from(state.worldId().orElseThrow(), edgesById.keySet(), footprints);
    return state.coverage().complete() ? retained : retained.withCoverageMarkedIncomplete();
  }

  private static RailInterlockingState interlockingState(RailGraph graph) {
    if (graph instanceof RailGraphInterlockingSupport support) {
      return support.interlockingState();
    }
    return RailInterlockingState.unavailable();
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
    /** 局部 build：update 覆盖 base 中同 ID 的节点/边，不做删除。 */
    UPSERT,
    /** 完整 build 与 base 有重叠：两端都在 update 里的旧边由本次的边取代，节点不删。 */
    REPLACE_EDGES
  }

  /** 合并结果：包含最终图与统计信息（用于命令回显与诊断）。 */
  public record MergeResult(
      RailGraph graph, MergeAction action, int removedEdges, int totalNodes, int totalEdges) {
    public MergeResult {
      Objects.requireNonNull(graph, "graph");
      Objects.requireNonNull(action, "action");
      if (removedEdges < 0 || totalNodes < 0 || totalEdges < 0) {
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
