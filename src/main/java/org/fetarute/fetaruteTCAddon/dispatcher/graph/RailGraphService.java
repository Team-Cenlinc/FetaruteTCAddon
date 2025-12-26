package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.company.repository.RailEdgeRepository;
import org.fetarute.fetaruteTCAddon.company.repository.RailGraphSnapshotRepository;
import org.fetarute.fetaruteTCAddon.company.repository.RailNodeRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailGraphSnapshotRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/** 管理各世界的 RailGraph 快照，供命令与运行时调度复用。 */
public final class RailGraphService {

  private final RailGraphBuilder builder;
  private final ConcurrentMap<UUID, RailGraphSnapshot> snapshots = new ConcurrentHashMap<>();

  public RailGraphService(SignNodeRegistry registry, Consumer<String> debugLogger) {
    this(new SignRegistryRailGraphBuilder(registry, debugLogger));
  }

  public RailGraphService(RailGraphBuilder builder) {
    this.builder = Objects.requireNonNull(builder, "builder");
  }

  public RailGraph rebuild(World world) {
    Objects.requireNonNull(world, "world");
    RailGraph graph = builder.build(world);
    snapshots.put(world.getUID(), new RailGraphSnapshot(graph, Instant.now()));
    return graph;
  }

  public void putSnapshot(World world, RailGraph graph, Instant builtAt) {
    Objects.requireNonNull(world, "world");
    Objects.requireNonNull(graph, "graph");
    Objects.requireNonNull(builtAt, "builtAt");
    snapshots.put(world.getUID(), new RailGraphSnapshot(graph, builtAt));
  }

  public Optional<RailGraphSnapshot> getSnapshot(World world) {
    Objects.requireNonNull(world, "world");
    return Optional.ofNullable(snapshots.get(world.getUID()));
  }

  public Map<UUID, RailGraphSnapshot> snapshotAll() {
    return Map.copyOf(snapshots);
  }

  /**
   * 从存储后端加载每个世界的持久化调度图到内存。
   *
   * <p>若某世界没有快照记录，将跳过加载。
   */
  public void loadFromStorage(StorageProvider provider, java.util.List<World> worlds) {
    Objects.requireNonNull(provider, "provider");
    Objects.requireNonNull(worlds, "worlds");
    RailNodeRepository nodeRepo = provider.railNodes();
    RailEdgeRepository edgeRepo = provider.railEdges();
    RailGraphSnapshotRepository snapshotRepo = provider.railGraphSnapshots();

    for (World world : worlds) {
      if (world == null) {
        continue;
      }
      UUID worldId = world.getUID();
      Optional<RailGraphSnapshotRecord> snapshotOpt = snapshotRepo.findByWorld(worldId);
      if (snapshotOpt.isEmpty()) {
        continue;
      }
      RailGraphSnapshotRecord snapshot = snapshotOpt.get();
      java.util.List<RailNodeRecord> nodeRecords = nodeRepo.listByWorld(worldId);
      java.util.List<RailEdgeRecord> edgeRecords = edgeRepo.listByWorld(worldId);
      RailGraph graph = buildGraphFromRecords(nodeRecords, edgeRecords);
      snapshots.put(worldId, new RailGraphSnapshot(graph, snapshot.builtAt()));
    }
  }

  private RailGraph buildGraphFromRecords(
      java.util.List<RailNodeRecord> nodeRecords, java.util.List<RailEdgeRecord> edgeRecords) {
    Map<org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId, RailNode> nodesById = new HashMap<>();
    for (RailNodeRecord node : nodeRecords) {
      SignRailNode railNode =
          new SignRailNode(
              node.nodeId(),
              node.nodeType(),
              new Vector(node.x(), node.y(), node.z()),
              node.trainCartsDestination(),
              node.waypointMetadata());
      nodesById.put(railNode.id(), railNode);
    }

    Map<EdgeId, RailEdge> edgesById = new HashMap<>();
    for (RailEdgeRecord edge : edgeRecords) {
      EdgeId edgeId = edge.edgeId();
      RailNode a = nodesById.get(edgeId.a());
      RailNode b = nodesById.get(edgeId.b());
      if (a == null || b == null) {
        continue;
      }
      RailEdge railEdge =
          new RailEdge(
              edgeId,
              edgeId.a(),
              edgeId.b(),
              edge.lengthBlocks(),
              edge.baseSpeedLimit(),
              edge.bidirectional(),
              Optional.of(new RailEdgeMetadata(a.waypointMetadata(), b.waypointMetadata())));
      edgesById.put(edgeId, railEdge);
    }

    return new SimpleRailGraph(nodesById, edgesById, java.util.Set.of());
  }

  public record RailGraphSnapshot(RailGraph graph, Instant builtAt) {
    public RailGraphSnapshot {
      Objects.requireNonNull(graph, "graph");
      Objects.requireNonNull(builtAt, "builtAt");
    }
  }
}
