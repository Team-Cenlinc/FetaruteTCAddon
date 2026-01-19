package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;

/** 线程安全的不可变调度图快照实现。 */
public final class SimpleRailGraph implements RailGraph, RailGraphCorridorSupport {

  private final Map<NodeId, RailNode> nodesById;
  private final Map<EdgeId, RailEdge> edgesById;
  private final Map<NodeId, Set<RailEdge>> edgesFrom;
  private final Set<EdgeId> blockedEdges;
  private volatile RailGraphConflictIndex conflictIndex;

  public SimpleRailGraph(
      Map<NodeId, RailNode> nodesById, Map<EdgeId, RailEdge> edgesById, Set<EdgeId> blockedEdges) {
    Objects.requireNonNull(nodesById, "nodesById");
    Objects.requireNonNull(edgesById, "edgesById");
    Objects.requireNonNull(blockedEdges, "blockedEdges");
    this.nodesById = Map.copyOf(nodesById);
    this.edgesById = Map.copyOf(edgesById);
    this.blockedEdges = Set.copyOf(blockedEdges);
    this.edgesFrom = buildAdjacency(this.nodesById, this.edgesById);
  }

  /**
   * @return 空图快照。
   */
  public static SimpleRailGraph empty() {
    return new SimpleRailGraph(Map.of(), Map.of(), Set.of());
  }

  @Override
  public Collection<RailNode> nodes() {
    return nodesById.values();
  }

  @Override
  public Collection<RailEdge> edges() {
    return edgesById.values();
  }

  @Override
  public Optional<RailNode> findNode(NodeId id) {
    Objects.requireNonNull(id, "id");
    return Optional.ofNullable(nodesById.get(id));
  }

  @Override
  public Set<RailEdge> edgesFrom(NodeId id) {
    Objects.requireNonNull(id, "id");
    return edgesFrom.getOrDefault(id, Set.of());
  }

  @Override
  public boolean isBlocked(EdgeId id) {
    Objects.requireNonNull(id, "id");
    return blockedEdges.contains(id);
  }

  /**
   * 查询指定边的冲突组 key。
   *
   * <p>索引懒加载并缓存，避免每次查询重算。
   */
  @Override
  public Optional<String> conflictKeyForEdge(EdgeId edgeId) {
    if (edgeId == null || edgeId.a() == null || edgeId.b() == null) {
      return Optional.empty();
    }
    RailGraphConflictIndex index = conflictIndex;
    if (index == null) {
      synchronized (this) {
        index = conflictIndex;
        if (index == null) {
          index = RailGraphConflictIndex.fromGraph(this);
          conflictIndex = index;
        }
      }
    }
    return index.conflictKeyForEdge(edgeId);
  }

  /**
   * 查询指定边所属的走廊信息。
   *
   * <p>索引懒加载并缓存，避免重复构建。
   */
  @Override
  public Optional<RailGraphCorridorInfo> corridorInfoForEdge(EdgeId edgeId) {
    if (edgeId == null || edgeId.a() == null || edgeId.b() == null) {
      return Optional.empty();
    }
    RailGraphConflictIndex index = conflictIndex;
    if (index == null) {
      synchronized (this) {
        index = conflictIndex;
        if (index == null) {
          index = RailGraphConflictIndex.fromGraph(this);
          conflictIndex = index;
        }
      }
    }
    return index.corridorInfoForEdge(edgeId);
  }

  /** 构建邻接表（无向图）。 */
  private static Map<NodeId, Set<RailEdge>> buildAdjacency(
      Map<NodeId, RailNode> nodes, Map<EdgeId, RailEdge> edges) {
    Map<NodeId, Set<RailEdge>> adjacency = new HashMap<>();
    for (NodeId nodeId : nodes.keySet()) {
      adjacency.put(nodeId, new HashSet<>());
    }
    for (RailEdge edge : edges.values()) {
      adjacency.computeIfAbsent(edge.from(), ignored -> new HashSet<>()).add(edge);
      adjacency.computeIfAbsent(edge.to(), ignored -> new HashSet<>()).add(edge);
    }
    Map<NodeId, Set<RailEdge>> frozen = new HashMap<>();
    for (Map.Entry<NodeId, Set<RailEdge>> entry : adjacency.entrySet()) {
      frozen.put(entry.getKey(), Set.copyOf(entry.getValue()));
    }
    return Map.copyOf(frozen);
  }
}
