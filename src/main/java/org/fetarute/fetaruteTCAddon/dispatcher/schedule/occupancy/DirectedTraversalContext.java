package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;

/**
 * 一次占用请求的有向路径上下文。
 *
 * <p>轨道图的物理资源仍使用无向 {@link EdgeId}，但运行时信号、单线方向、队列和健康监控需要稳定的 from→to
 * 视角。该上下文只描述“本次请求从哪里向哪里走”，不改变图模型，也不直接参与资源互斥。
 */
public record DirectedTraversalContext(
    String trainKey,
    Optional<RouteId> routeId,
    int currentIndex,
    Optional<NodeId> currentNode,
    Optional<NodeId> lastPassedGraphNode,
    Optional<NodeId> effectiveFromNode,
    Optional<NodeId> effectiveToNode,
    List<NodeId> expandedPathNodes,
    List<DirectedEdge> directedEdges,
    Map<String, CorridorDirection> singleConflictDirections,
    Map<String, SwitcherPathSignature> switcherPathSignatures,
    String source,
    long occupancyVersion,
    long progressVersion,
    String requestId,
    Optional<String> authorityTokenId) {

  public DirectedTraversalContext {
    trainKey = trainKey == null ? "" : TrainNameNormalizer.normalizeKey(trainKey);
    routeId = routeId == null ? Optional.empty() : routeId;
    currentNode = currentNode == null ? Optional.empty() : currentNode;
    lastPassedGraphNode = lastPassedGraphNode == null ? Optional.empty() : lastPassedGraphNode;
    effectiveFromNode = effectiveFromNode == null ? Optional.empty() : effectiveFromNode;
    effectiveToNode = effectiveToNode == null ? Optional.empty() : effectiveToNode;
    expandedPathNodes = expandedPathNodes == null ? List.of() : List.copyOf(expandedPathNodes);
    directedEdges = directedEdges == null ? List.of() : List.copyOf(directedEdges);
    singleConflictDirections =
        singleConflictDirections == null ? Map.of() : Map.copyOf(singleConflictDirections);
    switcherPathSignatures =
        switcherPathSignatures == null ? Map.of() : Map.copyOf(switcherPathSignatures);
    source = source == null || source.isBlank() ? "UNKNOWN" : source.trim();
    requestId =
        requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId.trim();
    authorityTokenId =
        authorityTokenId == null ? Optional.empty() : authorityTokenId.map(String::trim);
  }

  /** 返回同一路径但替换来源标签后的上下文。 */
  public DirectedTraversalContext withSource(String nextSource) {
    return new DirectedTraversalContext(
        trainKey,
        routeId,
        currentIndex,
        currentNode,
        lastPassedGraphNode,
        effectiveFromNode,
        effectiveToNode,
        expandedPathNodes,
        directedEdges,
        singleConflictDirections,
        switcherPathSignatures,
        nextSource,
        occupancyVersion,
        progressVersion,
        requestId,
        authorityTokenId);
  }

  /** 返回同一路径但替换占用快照版本后的上下文。 */
  public DirectedTraversalContext withOccupancyVersion(long nextOccupancyVersion) {
    return new DirectedTraversalContext(
        trainKey,
        routeId,
        currentIndex,
        currentNode,
        lastPassedGraphNode,
        effectiveFromNode,
        effectiveToNode,
        expandedPathNodes,
        directedEdges,
        singleConflictDirections,
        switcherPathSignatures,
        source,
        nextOccupancyVersion,
        progressVersion,
        requestId,
        authorityTokenId);
  }

  /** 返回同一路径但替换运行进度快照版本后的上下文。 */
  public DirectedTraversalContext withProgressVersion(long nextProgressVersion) {
    return new DirectedTraversalContext(
        trainKey,
        routeId,
        currentIndex,
        currentNode,
        lastPassedGraphNode,
        effectiveFromNode,
        effectiveToNode,
        expandedPathNodes,
        directedEdges,
        singleConflictDirections,
        switcherPathSignatures,
        source,
        occupancyVersion,
        nextProgressVersion,
        requestId,
        authorityTokenId);
  }

  /** 有向边：保留无向物理 edgeId，同时记录本次 traversal 的 from/to。 */
  public record DirectedEdge(EdgeId edgeId, NodeId fromNode, NodeId toNode) {
    public DirectedEdge {
      Objects.requireNonNull(edgeId, "edgeId");
      Objects.requireNonNull(fromNode, "fromNode");
      Objects.requireNonNull(toNode, "toNode");
    }

    @Override
    public String toString() {
      return fromNode.value() + "->" + toNode.value() + "@" + edgeId;
    }
  }

  /** 道岔区路径签名；本轮先作为诊断字段，不改变 switcher 共享语义。 */
  public record SwitcherPathSignature(String switcherKey, List<NodeId> pathNodes) {
    public SwitcherPathSignature {
      switcherKey = switcherKey == null ? "" : switcherKey.trim();
      pathNodes = pathNodes == null ? List.of() : List.copyOf(pathNodes);
    }

    @Override
    public String toString() {
      return switcherKey + ":" + pathNodes;
    }
  }
}
