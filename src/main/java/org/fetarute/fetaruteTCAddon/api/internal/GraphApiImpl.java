package org.fetarute.fetaruteTCAddon.api.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.api.graph.GraphApi;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPath;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPathFinder;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;

/**
 * GraphApi 内部实现：桥接到 RailGraphService。
 *
 * <p>仅供内部使用，外部插件应通过 {@link org.fetarute.fetaruteTCAddon.api.FetaruteApi} 访问。
 */
public final class GraphApiImpl implements GraphApi {

  private final RailGraphService railGraphService;
  private final RailGraphPathFinder pathFinder = new RailGraphPathFinder();

  public GraphApiImpl(RailGraphService railGraphService) {
    this.railGraphService = Objects.requireNonNull(railGraphService, "railGraphService");
  }

  @Override
  public Optional<GraphSnapshot> getSnapshot(UUID worldId) {
    if (worldId == null) {
      return Optional.empty();
    }
    return railGraphService.getSnapshot(worldId).map(this::convertSnapshot);
  }

  @Override
  public Collection<WorldGraphEntry> listAllSnapshots() {
    List<WorldGraphEntry> result = new ArrayList<>();
    for (var entry : railGraphService.snapshotAll().entrySet()) {
      result.add(new WorldGraphEntry(entry.getKey(), convertSnapshot(entry.getValue())));
    }
    return List.copyOf(result);
  }

  @Override
  public Optional<PathResult> findShortestPath(UUID worldId, String fromNodeId, String toNodeId) {
    if (worldId == null || fromNodeId == null || toNodeId == null) {
      return Optional.empty();
    }

    Optional<RailGraphService.RailGraphSnapshot> snapOpt = railGraphService.getSnapshot(worldId);
    if (snapOpt.isEmpty()) {
      return Optional.empty();
    }

    RailGraph graph = snapOpt.get().graph();
    NodeId from = NodeId.of(fromNodeId);
    NodeId to = NodeId.of(toNodeId);

    Optional<RailGraphPath> pathOpt =
        pathFinder.shortestPath(graph, from, to, RailGraphPathFinder.Options.shortestDistance());

    return pathOpt.map(
        path -> {
          List<String> nodeIds = path.nodes().stream().map(NodeId::value).toList();
          List<String> edgeIds = path.edges().stream().map(e -> e.id().toString()).toList();
          int totalDistance = (int) path.totalLengthBlocks();
          // 粗略估算：假设平均速度 6 bps
          int estimatedSec = totalDistance > 0 ? totalDistance / 6 : 0;
          return new PathResult(nodeIds, edgeIds, totalDistance, estimatedSec);
        });
  }

  @Override
  public Optional<String> getComponentKey(UUID worldId, String nodeId) {
    if (worldId == null || nodeId == null) {
      return Optional.empty();
    }
    return railGraphService.componentKey(worldId, NodeId.of(nodeId));
  }

  @Override
  public Optional<StaleInfo> getStaleInfo(UUID worldId) {
    if (worldId == null) {
      return Optional.empty();
    }
    World world = Bukkit.getWorld(worldId);
    if (world == null) {
      return Optional.of(new StaleInfo(false, "World not loaded", Optional.empty()));
    }
    Optional<RailGraphService.RailGraphStaleState> staleOpt = railGraphService.getStaleState(world);
    if (staleOpt.isEmpty()) {
      return Optional.of(new StaleInfo(false, "No graph", Optional.empty()));
    }

    RailGraphService.RailGraphStaleState state = staleOpt.get();
    boolean isStale = !state.snapshotSignature().equals(state.currentSignature());
    return Optional.of(
        new StaleInfo(
            isStale,
            isStale ? "Node signature mismatch" : "Up to date",
            Optional.of(state.builtAt())));
  }

  private GraphSnapshot convertSnapshot(RailGraphService.RailGraphSnapshot internal) {
    RailGraph graph = internal.graph();

    List<ApiNode> nodes = new ArrayList<>();
    for (RailNode node : graph.nodes()) {
      nodes.add(convertNode(node));
    }

    List<ApiEdge> edges = new ArrayList<>();
    for (RailEdge edge : graph.edges()) {
      edges.add(convertEdge(edge, graph));
    }

    int componentCount = railGraphService.componentCount(graph);

    return new GraphSnapshot(
        List.copyOf(nodes),
        List.copyOf(edges),
        internal.builtAt(),
        nodes.size(),
        edges.size(),
        componentCount);
  }

  private ApiNode convertNode(RailNode node) {
    NodeType type =
        switch (node.type()) {
          case STATION -> NodeType.STATION;
          case DEPOT -> NodeType.DEPOT;
          case WAYPOINT -> NodeType.WAYPOINT;
          case SWITCHER -> NodeType.SWITCHER;
          default -> NodeType.UNKNOWN;
        };

    Vector pos = node.worldPosition();
    Position position = new Position(pos.getX(), pos.getY(), pos.getZ());

    // 从 WaypointMetadata 获取站点名（originStation）
    Optional<String> displayName = node.waypointMetadata().map(m -> m.originStation());

    return new ApiNode(node.id().value(), type, position, displayName);
  }

  private ApiEdge convertEdge(RailEdge edge, RailGraph graph) {
    boolean blocked = graph.isBlocked(edge.id());

    return new ApiEdge(
        edge.id().toString(),
        edge.id().a().value(),
        edge.id().b().value(),
        edge.lengthBlocks(),
        edge.baseSpeedLimit(),
        edge.bidirectional(),
        blocked);
  }
}
