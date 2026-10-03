package org.fetarute.fetaruteTCAddon.dispatcher.graph.network;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphConflictSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphCorridorInfo;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphCorridorSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphInterlockingSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphSectionSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SingleLineSectionInfo;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingState;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.portal.PortalLink;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;

/**
 * 跨世界路网：各世界的调度图，加上传送门连接边。节点 ID 全服唯一，拼接不改 ID。
 *
 * <p>传送门连接边按无向边处理（与普通边一致）；占用时用独立的冲突键 {@code PORTAL:<a>~<b>}，保证门里同一时刻只有一列车。 站在某个世界看路网（{@link
 * #view}）时，节点与边是全网的，物理联锁状态是这个世界的（实时足迹只有坐标，不带世界）。 不可变，可在任意线程读。
 */
public final class RailNetwork {

  /** 传送门连接边的冲突键前缀。 */
  public static final String PORTAL_CONFLICT_PREFIX = "PORTAL:";

  private final Map<UUID, RailGraph> worlds;
  private final Map<NodeId, UUID> nodeWorld;
  private final Map<EdgeId, RailEdge> portalEdges;
  private final Map<NodeId, Set<RailEdge>> portalAdjacency;
  private final List<RailNode> allNodes;
  private final List<RailEdge> allEdges;

  private RailNetwork(
      Map<UUID, RailGraph> worlds,
      Map<NodeId, UUID> nodeWorld,
      Map<EdgeId, RailEdge> portalEdges,
      Map<NodeId, Set<RailEdge>> portalAdjacency) {
    this.worlds = worlds;
    this.nodeWorld = nodeWorld;
    this.portalEdges = portalEdges;
    this.portalAdjacency = portalAdjacency;
    List<RailNode> nodes = new ArrayList<>();
    List<RailEdge> edges = new ArrayList<>();
    for (RailGraph graph : worlds.values()) {
      nodes.addAll(graph.nodes());
      edges.addAll(graph.edges());
    }
    edges.addAll(portalEdges.values());
    this.allNodes = Collections.unmodifiableList(nodes);
    this.allEdges = Collections.unmodifiableList(edges);
  }

  /**
   * 拼接路网。两端节点都在已加载的图里、且分属不同世界的连接才成边；同一对门两个方向的连接合成一条边。
   *
   * @param worlds 各世界的调度图
   */
  public static RailNetwork build(Map<UUID, RailGraph> worlds, Collection<PortalLink> links) {
    Map<UUID, RailGraph> copy = Map.copyOf(worlds);
    Map<NodeId, UUID> nodeWorld = new HashMap<>();
    for (Map.Entry<UUID, RailGraph> entry : copy.entrySet()) {
      for (RailNode node : entry.getValue().nodes()) {
        nodeWorld.putIfAbsent(node.id(), entry.getKey());
      }
    }
    Map<EdgeId, RailEdge> portalEdges = new HashMap<>();
    for (PortalLink link : links) {
      UUID fromWorld = nodeWorld.get(link.fromNode());
      UUID toWorld = nodeWorld.get(link.toNode());
      if (fromWorld == null || toWorld == null || fromWorld.equals(toWorld)) {
        continue;
      }
      EdgeId id = EdgeId.undirected(link.fromNode(), link.toNode());
      int length = (int) Math.max(1L, Math.round(link.transitBlocks()));
      RailEdge existing = portalEdges.get(id);
      if (existing == null || existing.lengthBlocks() > length) {
        portalEdges.put(
            id,
            new RailEdge(id, link.fromNode(), link.toNode(), length, 0.0, true, Optional.empty()));
      }
    }
    Map<NodeId, Set<RailEdge>> adjacency = new HashMap<>();
    for (RailEdge edge : portalEdges.values()) {
      adjacency.computeIfAbsent(edge.from(), ignored -> new LinkedHashSet<>()).add(edge);
      adjacency.computeIfAbsent(edge.to(), ignored -> new LinkedHashSet<>()).add(edge);
    }
    return new RailNetwork(copy, Map.copyOf(nodeWorld), Map.copyOf(portalEdges), adjacency);
  }

  /** 有没有跨世界的连接边。 */
  public boolean hasPortalEdges() {
    return !portalEdges.isEmpty();
  }

  /** 节点所在的世界。 */
  public Optional<UUID> worldOf(NodeId node) {
    return Optional.ofNullable(nodeWorld.get(node));
  }

  /** 是不是传送门连接边。 */
  public boolean isPortalEdge(EdgeId edgeId) {
    return portalEdges.containsKey(edgeId);
  }

  /** 站在某个世界看路网。 */
  public RailGraph view(UUID worldId) {
    return new View(worldId);
  }

  /** 两个节点在路网里是否连通（广度优先，用于交路校验，不在每 tick 调用）。 */
  public boolean connected(NodeId from, NodeId to) {
    if (from.equals(to)) {
      return nodeWorld.containsKey(from);
    }
    if (!nodeWorld.containsKey(from) || !nodeWorld.containsKey(to)) {
      return false;
    }
    Set<NodeId> visited = new HashSet<>();
    Deque<NodeId> queue = new ArrayDeque<>();
    queue.add(from);
    visited.add(from);
    while (!queue.isEmpty()) {
      NodeId current = queue.poll();
      for (RailEdge edge : edgesFrom(current)) {
        NodeId next = edge.from().equals(current) ? edge.to() : edge.from();
        if (next.equals(to)) {
          return true;
        }
        if (visited.add(next)) {
          queue.add(next);
        }
      }
    }
    return false;
  }

  private Set<RailEdge> edgesFrom(NodeId node) {
    UUID world = nodeWorld.get(node);
    Set<RailEdge> portal = portalAdjacency.getOrDefault(node, Set.of());
    if (world == null) {
      return portal;
    }
    Set<RailEdge> own = worlds.get(world).edgesFrom(node);
    if (portal.isEmpty()) {
      return own;
    }
    Set<RailEdge> merged = new LinkedHashSet<>(own);
    merged.addAll(portal);
    return Collections.unmodifiableSet(merged);
  }

  private Optional<RailGraph> ownerOf(EdgeId edgeId) {
    UUID world = nodeWorld.get(edgeId.a());
    return Optional.ofNullable(world == null ? null : worlds.get(world));
  }

  /** 站在一个世界看路网：节点与边全网，物理联锁是这个世界的。 */
  private final class View
      implements RailGraph, RailGraphSectionSupport, RailGraphInterlockingSupport {

    private final UUID worldId;

    private View(UUID worldId) {
      this.worldId = worldId;
    }

    @Override
    public Collection<RailNode> nodes() {
      return allNodes;
    }

    @Override
    public Collection<RailEdge> edges() {
      return allEdges;
    }

    @Override
    public Optional<RailEdge> findEdge(EdgeId id) {
      if (id == null) {
        return Optional.empty();
      }
      RailEdge portal = portalEdges.get(id);
      if (portal != null) {
        return Optional.of(portal);
      }
      return ownerOf(id).flatMap(graph -> graph.findEdge(id));
    }

    @Override
    public Optional<RailNode> findNode(NodeId id) {
      UUID world = id == null ? null : nodeWorld.get(id);
      return world == null ? Optional.empty() : worlds.get(world).findNode(id);
    }

    @Override
    public Set<RailEdge> edgesFrom(NodeId id) {
      return id == null ? Set.of() : RailNetwork.this.edgesFrom(id);
    }

    @Override
    public boolean isBlocked(EdgeId id) {
      if (id == null || portalEdges.containsKey(id)) {
        return false;
      }
      return ownerOf(id).map(graph -> graph.isBlocked(id)).orElse(false);
    }

    @Override
    public Optional<String> conflictKeyForEdge(EdgeId edgeId) {
      if (edgeId == null) {
        return Optional.empty();
      }
      if (portalEdges.containsKey(edgeId)) {
        return Optional.of(PORTAL_CONFLICT_PREFIX + edgeId.a().value() + "~" + edgeId.b().value());
      }
      return ownerOf(edgeId)
          .filter(RailGraphConflictSupport.class::isInstance)
          .map(RailGraphConflictSupport.class::cast)
          .flatMap(support -> support.conflictKeyForEdge(edgeId));
    }

    @Override
    public Optional<RailGraphCorridorInfo> corridorInfoForEdge(EdgeId edgeId) {
      if (edgeId == null || portalEdges.containsKey(edgeId)) {
        return Optional.empty();
      }
      return ownerOf(edgeId)
          .filter(RailGraphCorridorSupport.class::isInstance)
          .map(RailGraphCorridorSupport.class::cast)
          .flatMap(support -> support.corridorInfoForEdge(edgeId));
    }

    @Override
    public Optional<SingleLineSectionInfo> sectionInfoForEdge(EdgeId edgeId) {
      if (edgeId == null || portalEdges.containsKey(edgeId)) {
        return Optional.empty();
      }
      return ownerOf(edgeId)
          .filter(RailGraphSectionSupport.class::isInstance)
          .map(RailGraphSectionSupport.class::cast)
          .flatMap(support -> support.sectionInfoForEdge(edgeId));
    }

    @Override
    public RailInterlockingState interlockingState() {
      RailGraph own = worlds.get(worldId);
      if (own instanceof RailGraphInterlockingSupport support) {
        return support.interlockingState();
      }
      return RailInterlockingState.unavailable();
    }
  }
}
