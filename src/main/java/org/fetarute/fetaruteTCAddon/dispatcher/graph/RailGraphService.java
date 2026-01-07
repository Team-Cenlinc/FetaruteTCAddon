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
import org.fetarute.fetaruteTCAddon.company.repository.RailEdgeOverrideRepository;
import org.fetarute.fetaruteTCAddon.company.repository.RailEdgeRepository;
import org.fetarute.fetaruteTCAddon.company.repository.RailGraphSnapshotRepository;
import org.fetarute.fetaruteTCAddon.company.repository.RailNodeRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphSignature;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeOverrideRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailGraphSnapshotRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/** 管理各世界的 RailGraph 快照，供命令与运行时调度复用。 */
public final class RailGraphService {

  private final RailGraphBuilder builder;
  private final Consumer<String> debugLogger;
  private final ConcurrentMap<UUID, RailGraphSnapshot> snapshots = new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID, RailGraphStaleState> staleStates = new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID, ConcurrentMap<EdgeId, RailEdgeOverrideRecord>> edgeOverrides =
      new ConcurrentHashMap<>();

  public RailGraphService(SignNodeRegistry registry, Consumer<String> debugLogger) {
    this(new SignRegistryRailGraphBuilder(registry, debugLogger), debugLogger);
  }

  public RailGraphService(RailGraphBuilder builder) {
    this(builder, message -> {});
  }

  public RailGraphService(RailGraphBuilder builder, Consumer<String> debugLogger) {
    this.builder = Objects.requireNonNull(builder, "builder");
    this.debugLogger = debugLogger != null ? debugLogger : message -> {};
  }

  public RailGraph rebuild(World world) {
    Objects.requireNonNull(world, "world");
    RailGraph graph = builder.build(world);
    snapshots.put(world.getUID(), new RailGraphSnapshot(graph, Instant.now()));
    staleStates.remove(world.getUID());
    return graph;
  }

  public void putSnapshot(World world, RailGraph graph, Instant builtAt) {
    Objects.requireNonNull(world, "world");
    Objects.requireNonNull(graph, "graph");
    Objects.requireNonNull(builtAt, "builtAt");
    snapshots.put(world.getUID(), new RailGraphSnapshot(graph, builtAt));
    staleStates.remove(world.getUID());
  }

  public Optional<RailGraphSnapshot> getSnapshot(World world) {
    Objects.requireNonNull(world, "world");
    return Optional.ofNullable(snapshots.get(world.getUID()));
  }

  public Optional<RailGraphStaleState> getStaleState(World world) {
    Objects.requireNonNull(world, "world");
    return Optional.ofNullable(staleStates.get(world.getUID()));
  }

  public void markStale(World world, RailGraphStaleState state) {
    Objects.requireNonNull(world, "world");
    Objects.requireNonNull(state, "state");
    UUID worldId = world.getUID();
    snapshots.remove(worldId);
    staleStates.put(worldId, state);
  }

  /**
   * 清空指定世界的内存快照。
   *
   * <p>注意：该方法只影响内存缓存，不会删除 SQL 中的快照记录；若需同时清理持久化数据，应由命令/运维逻辑另行处理。
   *
   * @return 是否存在并成功移除了快照
   */
  public boolean clearSnapshot(World world) {
    Objects.requireNonNull(world, "world");
    staleStates.remove(world.getUID());
    return snapshots.remove(world.getUID()) != null;
  }

  /** 返回指定世界的边运维覆盖快照（只读）。 */
  public Map<EdgeId, RailEdgeOverrideRecord> edgeOverrides(UUID worldId) {
    Objects.requireNonNull(worldId, "worldId");
    return Map.copyOf(edgeOverrides.getOrDefault(worldId, new ConcurrentHashMap<>()));
  }

  /** 查询某条边的运维覆盖。 */
  public Optional<RailEdgeOverrideRecord> getEdgeOverride(UUID worldId, EdgeId edgeId) {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(edgeId, "edgeId");
    EdgeId normalized = EdgeId.undirected(edgeId.a(), edgeId.b());
    return Optional.ofNullable(
        edgeOverrides.getOrDefault(worldId, new ConcurrentHashMap<>()).get(normalized));
  }

  /** 写入或更新某条边的运维覆盖（仅更新内存）。 */
  public void putEdgeOverride(RailEdgeOverrideRecord override) {
    Objects.requireNonNull(override, "override");
    EdgeId normalized = EdgeId.undirected(override.edgeId().a(), override.edgeId().b());
    edgeOverrides
        .computeIfAbsent(override.worldId(), ignored -> new ConcurrentHashMap<>())
        .put(normalized, override);
  }

  /** 删除某条边的运维覆盖（仅更新内存）。 */
  public void deleteEdgeOverride(UUID worldId, EdgeId edgeId) {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(edgeId, "edgeId");
    EdgeId normalized = EdgeId.undirected(edgeId.a(), edgeId.b());
    ConcurrentMap<EdgeId, RailEdgeOverrideRecord> byWorld = edgeOverrides.get(worldId);
    if (byWorld == null) {
      return;
    }
    byWorld.remove(normalized);
    if (byWorld.isEmpty()) {
      edgeOverrides.remove(worldId, byWorld);
    }
  }

  /**
   * 计算某条边的“当前有效限速”（blocks/s）。
   *
   * <p>规则：
   *
   * <ul>
   *   <li>base = edge.baseSpeedLimit &gt; 0 ? edge.baseSpeedLimit : default
   *   <li>normal = override.speedLimit ? override.speedLimit : base
   *   <li>effective = min(normal, override.tempSpeedLimit(if active))
   * </ul>
   */
  public double effectiveSpeedLimitBlocksPerSecond(
      UUID worldId, RailEdge edge, Instant now, double defaultSpeedBlocksPerSecond) {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(edge, "edge");
    Objects.requireNonNull(now, "now");
    if (!Double.isFinite(defaultSpeedBlocksPerSecond) || defaultSpeedBlocksPerSecond <= 0.0) {
      throw new IllegalArgumentException("defaultSpeedBlocksPerSecond 必须为正数");
    }

    double baseFromEdge = edge.baseSpeedLimit();
    double base =
        Double.isFinite(baseFromEdge) && baseFromEdge > 0.0
            ? baseFromEdge
            : defaultSpeedBlocksPerSecond;

    EdgeId edgeId = edge.id();
    if (edgeId == null) {
      return base;
    }
    EdgeId normalized = EdgeId.undirected(edgeId.a(), edgeId.b());
    RailEdgeOverrideRecord override =
        edgeOverrides.getOrDefault(worldId, new ConcurrentHashMap<>()).get(normalized);
    double effective = base;
    if (override != null && override.speedLimitBlocksPerSecond().isPresent()) {
      effective = override.speedLimitBlocksPerSecond().getAsDouble();
    }
    if (override != null && override.isTempSpeedActive(now)) {
      effective = Math.min(effective, override.tempSpeedLimitBlocksPerSecond().getAsDouble());
    }
    if (!Double.isFinite(effective) || effective <= 0.0) {
      return base;
    }
    return effective;
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
    RailEdgeOverrideRepository overrideRepo = provider.railEdgeOverrides();
    RailGraphSnapshotRepository snapshotRepo = provider.railGraphSnapshots();

    for (World world : worlds) {
      if (world == null) {
        continue;
      }
      UUID worldId = world.getUID();
      try {
        ConcurrentMap<EdgeId, RailEdgeOverrideRecord> overridesById = new ConcurrentHashMap<>();
        for (RailEdgeOverrideRecord override : overrideRepo.listByWorld(worldId)) {
          if (override == null || override.edgeId() == null) {
            continue;
          }
          EdgeId normalized = EdgeId.undirected(override.edgeId().a(), override.edgeId().b());
          overridesById.put(normalized, override);
        }
        if (!overridesById.isEmpty()) {
          edgeOverrides.put(worldId, overridesById);
        } else {
          edgeOverrides.remove(worldId);
        }
      } catch (Exception ex) {
        debugLogger.accept(
            "读取 rail_edge_overrides 失败: world=" + worldId + " msg=" + ex.getMessage());
      }

      Optional<RailGraphSnapshotRecord> snapshotOpt = snapshotRepo.findByWorld(worldId);
      if (snapshotOpt.isEmpty()) {
        continue;
      }
      RailGraphSnapshotRecord snapshot = snapshotOpt.get();
      java.util.List<RailNodeRecord> nodeRecords = nodeRepo.listByWorld(worldId);
      String currentSignature = RailGraphSignature.signatureForNodes(nodeRecords);
      if (snapshot.nodeSignature().isEmpty()
          && !currentSignature.isEmpty()
          && snapshot.nodeCount() != nodeRecords.size()) {
        staleStates.put(
            worldId,
            new RailGraphStaleState(
                snapshot.builtAt(),
                snapshot.nodeSignature(),
                currentSignature,
                snapshot.nodeCount(),
                snapshot.edgeCount(),
                nodeRecords.size()));
        snapshots.remove(worldId);
        continue;
      }

      if (!snapshot.nodeSignature().isEmpty()
          && !currentSignature.isEmpty()
          && !snapshot.nodeSignature().equals(currentSignature)) {
        staleStates.put(
            worldId,
            new RailGraphStaleState(
                snapshot.builtAt(),
                snapshot.nodeSignature(),
                currentSignature,
                snapshot.nodeCount(),
                snapshot.edgeCount(),
                nodeRecords.size()));
        snapshots.remove(worldId);
        continue;
      }

      java.util.List<RailEdgeRecord> edgeRecords = edgeRepo.listByWorld(worldId);
      if (snapshot.nodeSignature().isEmpty() && !currentSignature.isEmpty()) {
        RailGraphSnapshotRecord updated =
            new RailGraphSnapshotRecord(
                worldId,
                snapshot.builtAt(),
                snapshot.nodeCount(),
                snapshot.edgeCount(),
                currentSignature);
        try {
          snapshotRepo.save(updated);
          snapshot = updated;
        } catch (Exception ex) {
          debugLogger.accept(
              "写入 rail_graph_snapshots.node_signature 失败: world="
                  + worldId
                  + " msg="
                  + ex.getMessage());
        }
      }
      RailGraph graph = buildGraphFromRecords(nodeRecords, edgeRecords);
      snapshots.put(worldId, new RailGraphSnapshot(graph, snapshot.builtAt()));
      staleStates.remove(worldId);
    }
  }

  /**
   * 从存储记录还原一张 {@link RailGraph}。
   *
   * <p>注意：该方法不会进行“签名一致性”校验；调用方需自行决定是否信任输入数据。
   */
  public static RailGraph buildGraphFromRecords(
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

  /** 快照已失效：节点集合（签名）与当前 rail_nodes 不一致，旧图应提示重建。 */
  public record RailGraphStaleState(
      Instant builtAt,
      String snapshotSignature,
      String currentSignature,
      int snapshotNodeCount,
      int snapshotEdgeCount,
      int currentNodeCount) {
    public RailGraphStaleState {
      Objects.requireNonNull(builtAt, "builtAt");
      snapshotSignature = snapshotSignature == null ? "" : snapshotSignature;
      currentSignature = currentSignature == null ? "" : currentSignature;
    }
  }
}
