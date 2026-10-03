package org.fetarute.fetaruteTCAddon.dispatcher.graph.network;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 * <p>传送门连接边按无向边处理（与普通边一致），长度按 {@link #PORTAL_EDGE_BLOCKS} 计：TrainCarts 过门是瞬时的，而且门后被占时
 * 闭塞停车点（阻挡起点减停车余量）必须落在门前，否则列车会越过 {@code [portal]} 牌子被传送到对面的占用里。
 *
 * <p>站在某个世界看路网（{@link #view}）时，节点与边是全网的；同 ID 的节点优先取本世界的；物理联锁的实时观测是这个世界的
 * （实时足迹只有坐标，不带世界），各边的联锁区仍按边所在的世界取。不可变，可在任意线程读。
 */
public final class RailNetwork {

  /** 传送门连接边的冲突键前缀。 */
  public static final String PORTAL_CONFLICT_PREFIX = "PORTAL:";

  /** 传送门连接边在路网里的长度（格）。 */
  public static final int PORTAL_EDGE_BLOCKS = 1;

  private final Map<UUID, RailGraph> worlds;
  private final Map<NodeId, UUID> nodeWorld;
  private final Map<EdgeId, RailEdge> portalEdges;
  private final Map<NodeId, Set<RailEdge>> portalAdjacency;
  private final Set<UUID> portalWorlds;
  private final Map<NodeId, NodeId> components;
  private final Map<UUID, View> views = new ConcurrentHashMap<>();
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
    Set<UUID> participating = new HashSet<>();
    for (RailEdge edge : portalEdges.values()) {
      participating.add(nodeWorld.get(edge.from()));
      participating.add(nodeWorld.get(edge.to()));
    }
    this.portalWorlds = Set.copyOf(participating);
    List<RailNode> nodes = new ArrayList<>();
    List<RailEdge> edges = new ArrayList<>();
    for (RailGraph graph : worlds.values()) {
      nodes.addAll(graph.nodes());
      edges.addAll(graph.edges());
    }
    edges.addAll(portalEdges.values());
    this.allNodes = Collections.unmodifiableList(nodes);
    this.allEdges = Collections.unmodifiableList(edges);
    this.components = components(nodes, edges);
  }

  /**
   * 拼接路网。两端节点都在已加载的图里、且分属不同世界的连接才成边；同一对门两个方向的连接合成一条边。
   *
   * @param worlds 各世界的调度图（可已叠加各自的运维封锁）
   */
  public static RailNetwork build(Map<UUID, RailGraph> worlds, Collection<PortalLink> links) {
    // 按世界 UUID 排序：同 ID 节点出现在多个世界时，归属取决于固定顺序而不是哈希遍历顺序。
    Map<UUID, RailGraph> ordered = new TreeMap<>(worlds);
    Map<NodeId, UUID> nodeWorld = new HashMap<>();
    for (Map.Entry<UUID, RailGraph> entry : ordered.entrySet()) {
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
      portalEdges.putIfAbsent(
          id,
          new RailEdge(
              id, link.fromNode(), link.toNode(), PORTAL_EDGE_BLOCKS, 0.0, true, Optional.empty()));
    }
    Map<NodeId, Set<RailEdge>> adjacency = new HashMap<>();
    for (RailEdge edge : portalEdges.values()) {
      adjacency.computeIfAbsent(edge.from(), ignored -> new LinkedHashSet<>()).add(edge);
      adjacency.computeIfAbsent(edge.to(), ignored -> new LinkedHashSet<>()).add(edge);
    }
    return new RailNetwork(
        Collections.unmodifiableMap(ordered),
        Map.copyOf(nodeWorld),
        Map.copyOf(portalEdges),
        adjacency);
  }

  /** 有没有跨世界的连接边。 */
  public boolean hasPortalEdges() {
    return !portalEdges.isEmpty();
  }

  /** 这个世界有没有传送门连接边（没有时它的列车出不了本世界，运行时不必换成路网）。 */
  public boolean hasPortalIn(UUID worldId) {
    return portalWorlds.contains(worldId);
  }

  /** 节点所在的世界。 */
  public Optional<UUID> worldOf(NodeId node) {
    return Optional.ofNullable(nodeWorld.get(node));
  }

  /** 是不是传送门连接边。 */
  public boolean isPortalEdge(EdgeId edgeId) {
    return portalEdges.containsKey(edgeId);
  }

  /** 站在某个世界看路网。同一个路网对同一个世界总是同一个视图（最短路记忆按视图缓存）。 */
  public RailGraph view(UUID worldId) {
    return views.computeIfAbsent(worldId, View::new);
  }

  /** 两个节点在路网里是否连通（按预先算好的连通分量）。 */
  public boolean connected(NodeId from, NodeId to) {
    NodeId a = components.get(from);
    return a != null && a.equals(components.get(to));
  }

  /** 并查集：节点映射到所在分量的代表节点。 */
  private static Map<NodeId, NodeId> components(List<RailNode> nodes, List<RailEdge> edges) {
    Map<NodeId, NodeId> parent = new HashMap<>();
    for (RailNode node : nodes) {
      parent.put(node.id(), node.id());
    }
    for (RailEdge edge : edges) {
      if (!parent.containsKey(edge.from()) || !parent.containsKey(edge.to())) {
        continue;
      }
      NodeId a = root(parent, edge.from());
      NodeId b = root(parent, edge.to());
      if (!a.equals(b)) {
        parent.put(a, b);
      }
    }
    Map<NodeId, NodeId> result = new HashMap<>();
    for (NodeId node : new ArrayList<>(parent.keySet())) {
      result.put(node, root(parent, node));
    }
    return Map.copyOf(result);
  }

  private static NodeId root(Map<NodeId, NodeId> parent, NodeId node) {
    NodeId current = node;
    NodeId next = parent.get(current);
    while (!next.equals(current)) {
      NodeId grand = parent.get(next);
      parent.put(current, grand);
      current = next;
      next = grand;
    }
    return current;
  }

  /** 节点所在的图：视图所在世界有这个节点就用本世界的，否则按全网归属。 */
  private Optional<RailGraph> graphOf(UUID viewWorld, NodeId node) {
    RailGraph own = worlds.get(viewWorld);
    if (own != null && own.findNode(node).isPresent()) {
      return Optional.of(own);
    }
    UUID world = nodeWorld.get(node);
    return Optional.ofNullable(world == null ? null : worlds.get(world));
  }

  private Set<RailEdge> edgesFrom(UUID viewWorld, NodeId node) {
    Set<RailEdge> portal = portalAdjacency.getOrDefault(node, Set.of());
    Optional<RailGraph> graph = graphOf(viewWorld, node);
    if (graph.isEmpty()) {
      return portal;
    }
    Set<RailEdge> own = graph.get().edgesFrom(node);
    if (portal.isEmpty()) {
      return own;
    }
    Set<RailEdge> merged = new LinkedHashSet<>(own);
    merged.addAll(portal);
    return Collections.unmodifiableSet(merged);
  }

  /** 站在一个世界看路网：节点与边全网，物理联锁的实时观测是这个世界的，各边的联锁区按边所在世界。 */
  public final class View
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
      return graphOf(worldId, id.a()).flatMap(graph -> graph.findEdge(id));
    }

    @Override
    public Optional<RailNode> findNode(NodeId id) {
      return id == null ? Optional.empty() : graphOf(worldId, id).flatMap(g -> g.findNode(id));
    }

    @Override
    public Set<RailEdge> edgesFrom(NodeId id) {
      return id == null ? Set.of() : RailNetwork.this.edgesFrom(worldId, id);
    }

    @Override
    public boolean isBlocked(EdgeId id) {
      if (id == null || portalEdges.containsKey(id)) {
        return false;
      }
      return graphOf(worldId, id.a()).map(graph -> graph.isBlocked(id)).orElse(false);
    }

    @Override
    public Optional<String> conflictKeyForEdge(EdgeId edgeId) {
      if (edgeId == null) {
        return Optional.empty();
      }
      if (portalEdges.containsKey(edgeId)) {
        return Optional.of(PORTAL_CONFLICT_PREFIX + edgeId.a().value() + "~" + edgeId.b().value());
      }
      return graphOf(worldId, edgeId.a())
          .filter(RailGraphConflictSupport.class::isInstance)
          .map(RailGraphConflictSupport.class::cast)
          .flatMap(support -> support.conflictKeyForEdge(edgeId));
    }

    @Override
    public Optional<RailGraphCorridorInfo> corridorInfoForEdge(EdgeId edgeId) {
      if (edgeId == null || portalEdges.containsKey(edgeId)) {
        return Optional.empty();
      }
      return graphOf(worldId, edgeId.a())
          .filter(RailGraphCorridorSupport.class::isInstance)
          .map(RailGraphCorridorSupport.class::cast)
          .flatMap(support -> support.corridorInfoForEdge(edgeId));
    }

    @Override
    public Optional<SingleLineSectionInfo> sectionInfoForEdge(EdgeId edgeId) {
      if (edgeId == null || portalEdges.containsKey(edgeId)) {
        return Optional.empty();
      }
      return graphOf(worldId, edgeId.a())
          .filter(RailGraphSectionSupport.class::isInstance)
          .map(RailGraphSectionSupport.class::cast)
          .flatMap(support -> support.sectionInfoForEdge(edgeId));
    }

    @Override
    public Set<String> zoneKeysForEdge(EdgeId edgeId) {
      if (edgeId == null || portalEdges.containsKey(edgeId)) {
        return Set.of();
      }
      return graphOf(worldId, edgeId.a())
          .filter(RailGraphInterlockingSupport.class::isInstance)
          .map(RailGraphInterlockingSupport.class::cast)
          .map(support -> support.zoneKeysForEdge(edgeId))
          .orElse(Set.of());
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
