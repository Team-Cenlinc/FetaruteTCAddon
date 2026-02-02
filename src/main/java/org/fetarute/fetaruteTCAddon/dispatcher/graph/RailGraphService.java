package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphSignature;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailComponentCautionRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeOverrideRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailGraphSnapshotRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailComponentCautionRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailEdgeOverrideRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailEdgeRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailGraphSnapshotRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailNodeRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/** 管理各世界的 RailGraph 快照，供命令与运行时调度复用。 */
public final class RailGraphService {

  private final RailGraphBuilder builder;
  private final Consumer<String> debugLogger;
  private final ConcurrentMap<UUID, RailGraphSnapshot> snapshots = new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID, RailGraphStaleState> staleStates = new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID, RailGraphComponentIndex> componentIndexes =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID, ConcurrentMap<EdgeId, RailEdgeOverrideRecord>> edgeOverrides =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID, ConcurrentMap<String, RailComponentCautionRecord>>
      componentCautions = new ConcurrentHashMap<>();

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
    UUID worldId = world.getUID();
    snapshots.put(worldId, new RailGraphSnapshot(graph, Instant.now()));
    componentIndexes.put(worldId, RailGraphComponentIndex.fromGraph(graph));
    staleStates.remove(worldId);
    return graph;
  }

  public void putSnapshot(World world, RailGraph graph, Instant builtAt) {
    Objects.requireNonNull(world, "world");
    Objects.requireNonNull(graph, "graph");
    Objects.requireNonNull(builtAt, "builtAt");
    UUID worldId = world.getUID();
    snapshots.put(worldId, new RailGraphSnapshot(graph, builtAt));
    componentIndexes.put(worldId, RailGraphComponentIndex.fromGraph(graph));
    staleStates.remove(worldId);
  }

  public Optional<RailGraphSnapshot> getSnapshot(World world) {
    Objects.requireNonNull(world, "world");
    return Optional.ofNullable(snapshots.get(world.getUID()));
  }

  /** 运行时便捷入口：按 worldId 查询内存快照，避免依赖 Bukkit World 实例。 */
  public Optional<RailGraphSnapshot> getSnapshot(UUID worldId) {
    Objects.requireNonNull(worldId, "worldId");
    return Optional.ofNullable(snapshots.get(worldId));
  }

  /** 返回已加载的图快照数量，用于命令校验/诊断。 */
  public int snapshotCount() {
    return snapshots.size();
  }

  /**
   * 在已加载的快照中查找包含指定路径的世界。
   *
   * <p>路径按相邻节点“可连通”判定：只要两点处于同一连通分量即可；若任一节点缺失则返回 empty。
   */
  public Optional<UUID> findWorldIdForPath(List<NodeId> nodes) {
    if (nodes == null || nodes.size() < 2) {
      return Optional.empty();
    }
    for (Map.Entry<UUID, RailGraphSnapshot> entry : snapshots.entrySet()) {
      UUID worldId = entry.getKey();
      RailGraphSnapshot snapshot = entry.getValue();
      RailGraphComponentIndex index = componentIndexes.get(worldId);
      if (snapshot == null || snapshot.graph() == null || index == null) {
        continue;
      }
      if (pathConnected(index, nodes)) {
        return Optional.of(worldId);
      }
    }
    return Optional.empty();
  }

  /**
   * 在已加载的快照中查找包含指定区间的世界（按连通分量判定）。
   *
   * <p>用于命令侧校验路线是否可达（不触发区块加载）。
   */
  public Optional<UUID> findWorldIdForConnectedPair(NodeId from, NodeId to) {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    for (Map.Entry<UUID, RailGraphSnapshot> entry : snapshots.entrySet()) {
      UUID worldId = entry.getKey();
      RailGraphSnapshot snapshot = entry.getValue();
      RailGraphComponentIndex index = componentIndexes.get(worldId);
      if (snapshot == null || snapshot.graph() == null || index == null) {
        continue;
      }
      if (pairConnected(index, from, to)) {
        return Optional.of(worldId);
      }
    }
    return Optional.empty();
  }

  /**
   * 在已加载的快照中查找包含指定边的世界。
   *
   * <p>用于命令侧校验路线是否可达（不触发区块加载）。
   */
  public Optional<UUID> findWorldIdForEdge(NodeId from, NodeId to) {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    for (Map.Entry<UUID, RailGraphSnapshot> entry : snapshots.entrySet()) {
      RailGraphSnapshot snapshot = entry.getValue();
      if (snapshot == null || snapshot.graph() == null) {
        continue;
      }
      if (edgeExists(snapshot.graph(), from, to)) {
        return Optional.of(entry.getKey());
      }
    }
    return Optional.empty();
  }

  public Optional<RailGraphStaleState> getStaleState(World world) {
    Objects.requireNonNull(world, "world");
    return Optional.ofNullable(staleStates.get(world.getUID()));
  }

  private boolean edgeExists(RailGraph graph, NodeId from, NodeId to) {
    // 只检查内存快照，不触发区块加载。
    for (RailEdge edge : graph.edgesFrom(from)) {
      if (edgeConnects(edge, from, to)) {
        return true;
      }
    }
    return false;
  }

  private boolean pathConnected(RailGraphComponentIndex index, List<NodeId> nodes) {
    for (int i = 0; i < nodes.size() - 1; i++) {
      NodeId from = nodes.get(i);
      NodeId to = nodes.get(i + 1);
      if (from == null || to == null) {
        return false;
      }
      if (!pairConnected(index, from, to)) {
        return false;
      }
    }
    return true;
  }

  private boolean pairConnected(RailGraphComponentIndex index, NodeId from, NodeId to) {
    String fromKey = index.componentKey(from);
    String toKey = index.componentKey(to);
    return fromKey != null && fromKey.equals(toKey);
  }

  private boolean edgeConnects(RailEdge edge, NodeId a, NodeId b) {
    // 无向边匹配。
    if (edge == null) {
      return false;
    }
    return (edge.from().equals(a) && edge.to().equals(b))
        || (edge.from().equals(b) && edge.to().equals(a));
  }

  public void markStale(World world, RailGraphStaleState state) {
    Objects.requireNonNull(world, "world");
    Objects.requireNonNull(state, "state");
    UUID worldId = world.getUID();
    snapshots.remove(worldId);
    componentIndexes.remove(worldId);
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
    UUID worldId = world.getUID();
    staleStates.remove(worldId);
    componentIndexes.remove(worldId);
    return snapshots.remove(worldId) != null;
  }

  /** 返回节点所属连通分量的 key（不存在则 empty）。 */
  public Optional<String> componentKey(UUID worldId, NodeId nodeId) {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(nodeId, "nodeId");
    RailGraphComponentIndex index = componentIndexes.get(worldId);
    if (index == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(index.componentKey(nodeId));
  }

  /**
   * 返回指定图的连通分量数量。
   *
   * @param graph 调度图实例
   * @return 连通分量数量
   */
  public int componentCount(RailGraph graph) {
    if (graph == null) {
      return 0;
    }
    // 动态计算，不依赖缓存
    return RailGraphComponentIndex.fromGraph(graph).componentCount();
  }

  /** 查询某连通分量的 caution 速度覆盖（blocks/s）。 */
  public OptionalDouble componentCautionSpeedBlocksPerSecond(UUID worldId, String componentKey) {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(componentKey, "componentKey");
    RailComponentCautionRecord record =
        componentCautions.getOrDefault(worldId, new ConcurrentHashMap<>()).get(componentKey);
    if (record == null) {
      return OptionalDouble.empty();
    }
    return OptionalDouble.of(record.cautionSpeedBlocksPerSecond());
  }

  /** 写入或更新某连通分量的 caution 速度覆盖（仅更新内存）。 */
  public void putComponentCaution(RailComponentCautionRecord record) {
    Objects.requireNonNull(record, "record");
    componentCautions
        .computeIfAbsent(record.worldId(), ignored -> new ConcurrentHashMap<>())
        .put(record.componentKey(), record);
  }

  /** 删除某连通分量的 caution 速度覆盖（仅更新内存）。 */
  public void deleteComponentCaution(UUID worldId, String componentKey) {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(componentKey, "componentKey");
    ConcurrentMap<String, RailComponentCautionRecord> byWorld = componentCautions.get(worldId);
    if (byWorld == null) {
      return;
    }
    byWorld.remove(componentKey);
    if (byWorld.isEmpty()) {
      componentCautions.remove(worldId, byWorld);
    }
  }

  /** 返回指定世界的连通分量 caution 覆盖快照（只读）。 */
  public Map<String, RailComponentCautionRecord> componentCautions(UUID worldId) {
    Objects.requireNonNull(worldId, "worldId");
    return Map.copyOf(componentCautions.getOrDefault(worldId, new ConcurrentHashMap<>()));
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
    RailComponentCautionRepository cautionRepo = provider.railComponentCautions();
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

      try {
        ConcurrentMap<String, RailComponentCautionRecord> byKey = new ConcurrentHashMap<>();
        for (RailComponentCautionRecord record : cautionRepo.listByWorld(worldId)) {
          if (record == null || record.componentKey() == null || record.componentKey().isBlank()) {
            continue;
          }
          byKey.put(record.componentKey(), record);
        }
        if (!byKey.isEmpty()) {
          componentCautions.put(worldId, byKey);
        } else {
          componentCautions.remove(worldId);
        }
      } catch (Exception ex) {
        debugLogger.accept(
            "读取 rail_component_cautions 失败: world=" + worldId + " msg=" + ex.getMessage());
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
      componentIndexes.put(worldId, RailGraphComponentIndex.fromGraph(graph));
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
