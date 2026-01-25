package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailGraphExplorer;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.TrainCartsRailBlockAccess;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;

/**
 * 从 {@link SignNodeRegistry} 构建 RailGraph 的实现。
 *
 * <p>探索阶段会尝试在节点牌子附近寻找轨道锚点，然后沿轨道网络计算相邻节点之间的区间长度（方块数）。
 */
public final class SignRegistryRailGraphBuilder implements RailGraphBuilder {

  private static final int DEFAULT_ANCHOR_SEARCH_RADIUS = 2;
  private static final int DEFAULT_MAX_EDGE_DISTANCE_BLOCKS = 512;

  private final SignNodeRegistry registry;
  private final Consumer<String> debugLogger;
  private final int anchorSearchRadius;
  private final int maxEdgeDistanceBlocks;

  public SignRegistryRailGraphBuilder(SignNodeRegistry registry, Consumer<String> debugLogger) {
    this(registry, debugLogger, DEFAULT_ANCHOR_SEARCH_RADIUS, DEFAULT_MAX_EDGE_DISTANCE_BLOCKS);
  }

  public SignRegistryRailGraphBuilder(
      SignNodeRegistry registry,
      Consumer<String> debugLogger,
      int anchorSearchRadius,
      int maxEdgeDistanceBlocks) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.debugLogger = debugLogger != null ? debugLogger : message -> {};
    if (anchorSearchRadius < 0) {
      throw new IllegalArgumentException("anchorSearchRadius 不能为负");
    }
    if (maxEdgeDistanceBlocks <= 0) {
      throw new IllegalArgumentException("maxEdgeDistanceBlocks 必须为正数");
    }
    this.anchorSearchRadius = anchorSearchRadius;
    this.maxEdgeDistanceBlocks = maxEdgeDistanceBlocks;
  }

  @Override
  public RailGraph build(World world) {
    Objects.requireNonNull(world, "world");
    UUID worldId = world.getUID();

    Map<NodeId, RailNode> nodesById = new HashMap<>();
    Map<NodeId, Set<RailBlockPos>> anchorsByNode = new HashMap<>();

    TrainCartsRailBlockAccess railAccess = new TrainCartsRailBlockAccess(world);
    for (SignNodeRegistry.SignNodeInfo info : registry.snapshotInfos().values()) {
      if (!worldId.equals(info.worldId())) {
        continue;
      }

      SignNodeDefinition def = info.definition();
      Vector position = new Vector(info.x(), info.y(), info.z());
      SignRailNode node =
          new SignRailNode(
              def.nodeId(),
              def.nodeType(),
              position,
              def.trainCartsDestination(),
              def.waypointMetadata());
      nodesById.put(node.id(), node);

      RailBlockPos center = new RailBlockPos(info.x(), info.y(), info.z());
      Set<RailBlockPos> anchors = railAccess.findNearestRailBlocks(center, anchorSearchRadius);
      if (anchors.isEmpty()) {
        debugLogger.accept(
            "图构建：节点附近未找到轨道锚点: node=" + def.nodeId().value() + " @ " + info.locationText());
      } else {
        anchorsByNode.put(def.nodeId(), anchors);
      }
    }

    Map<EdgeId, Integer> edgeLengths =
        RailGraphExplorer.exploreEdgeLengths(anchorsByNode, railAccess, maxEdgeDistanceBlocks);

    Map<EdgeId, RailEdge> edgesById = new HashMap<>();
    for (Map.Entry<EdgeId, Integer> entry : edgeLengths.entrySet()) {
      EdgeId edgeId = entry.getKey();
      int lengthBlocks = entry.getValue();
      RailNode a = nodesById.get(edgeId.a());
      RailNode b = nodesById.get(edgeId.b());
      if (a == null || b == null) {
        continue;
      }
      RailEdge edge =
          new RailEdge(
              edgeId,
              edgeId.a(),
              edgeId.b(),
              lengthBlocks,
              0.0,
              true,
              Optional.of(new RailEdgeMetadata(a.waypointMetadata(), b.waypointMetadata())));
      edgesById.put(edgeId, edge);
    }

    debugLogger.accept(
        "图构建完成: world="
            + world.getName()
            + " nodes="
            + nodesById.size()
            + " edges="
            + edgesById.size());
    return new SimpleRailGraph(nodesById, edgesById, Set.of());
  }
}
