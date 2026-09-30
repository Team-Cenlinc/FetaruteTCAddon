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
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphSignature;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailEdgeFootprint;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingEdgeSignature;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingState;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailComponentCautionRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeOverrideRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailGraphSnapshotRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailInterlockingSnapshotRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailComponentCautionRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailEdgeOverrideRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailEdgeRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailGraphSnapshotRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailInterlockingSnapshotRepository;
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
  private final ConcurrentMap<UUID, RailInterlockingState> lastActivatedInterlockingStates =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID, RailGraphStaleState> staleStates = new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID, RailGraphComponentIndex> componentIndexes =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID, ConcurrentMap<EdgeId, RailEdgeOverrideRecord>> edgeOverrides =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID, ConcurrentMap<String, RailComponentCautionRecord>>
      componentCautions = new ConcurrentHashMap<>();
  private volatile BooleanSupplier snapshotActivationGuard = () -> true;

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
    activateSnapshot(worldId, graph, Instant.now());
    return graph;
  }

  public void putSnapshot(World world, RailGraph graph, Instant builtAt) {
    Objects.requireNonNull(world, "world");
    Objects.requireNonNull(graph, "graph");
    Objects.requireNonNull(builtAt, "builtAt");
    activateSnapshot(world.getUID(), graph, builtAt);
  }

  /**
   * 设置图快照激活前的静默条件检查。
   *
   * <p>只有 old/new 物理联锁资源投影发生变化时才调用该 guard。运行时应注入“当前没有任何占用 claim”；默认恒为 true，以保持独立图测试与启动预热兼容。
   */
  public void setSnapshotActivationGuard(BooleanSupplier snapshotActivationGuard) {
    this.snapshotActivationGuard =
        Objects.requireNonNull(snapshotActivationGuard, "snapshotActivationGuard");
  }

  /**
   * 在持久化新快照前校验其物理联锁资源投影是否允许切换。
   *
   * <p>图构建命令在同一主线程调用链中先执行本校验、再提交 SQL 事务、最后调用 {@link #putSnapshot(World, RailGraph, Instant)}。这样既不会在
   * active claim 存在时先改磁盘，也不会在 SQL 失败时先改内存。
   *
   * @throws IllegalStateException 资源投影变化且当前仍有占用 claim
   */
  public void validateSnapshotActivation(World world, RailGraph graph) {
    Objects.requireNonNull(world, "world");
    Objects.requireNonNull(graph, "graph");
    validateSnapshotActivation(world.getUID(), graph);
  }

  private void activateSnapshot(UUID worldId, RailGraph graph, Instant builtAt) {
    validateSnapshotActivation(worldId, graph);
    RailInterlockingState nextState = interlockingState(graph);
    RailGraphComponentIndex nextComponentIndex = RailGraphComponentIndex.fromGraph(graph);
    snapshots.put(worldId, new RailGraphSnapshot(graph, builtAt));
    componentIndexes.put(worldId, nextComponentIndex);
    lastActivatedInterlockingStates.put(worldId, nextState);
    staleStates.remove(worldId);
    traceInterlockingCoverage(worldId, nextState);
  }

  /**
   * 图激活时报告物理联锁覆盖的可用性。
   *
   * <p>为什么非有不可：{@code cellCoverageAvailable()} 是「车体实际压住哪些区间」这条证据链的**总闸**—— {@code
   * RuntimeDispatchService.livePhysicalEdgeCoverage} 在它为假时一律返回 incomplete， 于是任何以实测覆盖为放行条件的机制（尾部保护释放
   * / Phase 4）都会 fail-closed 到**一个都不放**。
   *
   * <p>而它有两条构建路径，结果天差地别：{@link
   * org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingZoneIndex#from}
   * 走完整图构建， 逐边足迹齐全，索引可用；{@code fromZones} 从持久化快照重建，**按设计只有 Zone、没有逐边足迹， 索引必然为空**。正常重启的服务器走的是后者。
   *
   * <p>构建期特性标志 {@code liveFootprintReverseIndex=true} 只说明代码有这个功能，不说明索引真的建起来了。
   * 缺了这一行，运行时便无法区分这两种状态，依赖该索引的机制可能代码路径俱在、trace 照常输出，却从不触发。
   *
   * <p>每次图激活至多一行，不随 tick 放大。
   */
  private void traceInterlockingCoverage(UUID worldId, RailInterlockingState state) {
    debugLogger.accept(
        "SMART_INTERLOCKING_COVERAGE world="
            + worldId
            + " available="
            + state.available()
            + " cellCoverageAvailable="
            + state.cellCoverageAvailable()
            + " expectedEdges="
            + state.expectedEdges().size()
            + " exactZones="
            + state.exactZoneCount()
            + " indexedZoneCells="
            + state.indexedZoneCellCount()
            + " multiZoneCells="
            + state.multiZoneCellCount());
  }

  private void validateSnapshotActivation(UUID worldId, RailGraph graph) {
    RailGraphSnapshot current = snapshots.get(worldId);
    RailInterlockingState currentState =
        lastActivatedInterlockingStates.getOrDefault(
            worldId,
            current == null
                ? RailInterlockingState.unavailable()
                : interlockingState(current.graph()));
    RailInterlockingState nextState = interlockingState(graph);
    if ((current != null || lastActivatedInterlockingStates.containsKey(worldId))
        && !currentState.sameResourceProjection(nextState)
        && !snapshotActivationGuard.getAsBoolean()) {
      throw new IllegalStateException("仍有列车占用 claim，拒绝切换物理联锁资源投影");
    }
  }

  private static RailInterlockingState interlockingState(RailGraph graph) {
    if (graph instanceof RailGraphInterlockingSupport support) {
      return support.interlockingState();
    }
    return RailInterlockingState.unavailable();
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
    return effectiveSpeedLimitBlocksPerSecond(worldId, edge, now, defaultSpeedBlocksPerSecond, 1.0);
  }

  /**
   * 同 {@link #effectiveSpeedLimitBlocksPerSecond(UUID, RailEdge, Instant,
   * double)}，另按倍率放宽线路限速——晚点追赶用。
   *
   * <p>只放宽<b>写明了的线路限速</b>：边基础限速（牌子写的）或永久限速覆盖，也就是编表按它算表定时分的那个数。 不放宽的有三类：没写限速、按默认速度走的边——没有证据说它扛得住更快；
   * 临时限速——施工、限行是运维硬约束；以及不经过这里的进站限速、CAUTION 与信号给出的速度。
   *
   * @param lineSpeedFactor 线路限速倍率；不大于 1 或非有限值时按 1
   */
  public double effectiveSpeedLimitBlocksPerSecond(
      UUID worldId,
      RailEdge edge,
      Instant now,
      double defaultSpeedBlocksPerSecond,
      double lineSpeedFactor) {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(edge, "edge");
    Objects.requireNonNull(now, "now");
    if (!Double.isFinite(defaultSpeedBlocksPerSecond) || defaultSpeedBlocksPerSecond <= 0.0) {
      throw new IllegalArgumentException("defaultSpeedBlocksPerSecond 必须为正数");
    }
    double factor =
        Double.isFinite(lineSpeedFactor) && lineSpeedFactor > 1.0 ? lineSpeedFactor : 1.0;

    double baseFromEdge = edge.baseSpeedLimit();
    boolean baseWritten = Double.isFinite(baseFromEdge) && baseFromEdge > 0.0;
    double base = baseWritten ? baseFromEdge * factor : defaultSpeedBlocksPerSecond;

    EdgeId edgeId = edge.id();
    if (edgeId == null) {
      return base;
    }
    EdgeId normalized = EdgeId.undirected(edgeId.a(), edgeId.b());
    RailEdgeOverrideRecord override =
        edgeOverrides.getOrDefault(worldId, new ConcurrentHashMap<>()).get(normalized);
    double effective = base;
    if (override != null && override.speedLimitBlocksPerSecond().isPresent()) {
      effective = override.speedLimitBlocksPerSecond().getAsDouble() * factor;
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
    RailInterlockingSnapshotRepository interlockingSnapshotRepo =
        provider.railInterlockingSnapshots();

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
      Optional<RailInterlockingSnapshotRecord> interlockingSnapshot = Optional.empty();
      try {
        interlockingSnapshot = interlockingSnapshotRepo.findByWorld(worldId);
      } catch (Exception ex) {
        debugLogger.accept(
            "读取 rail_interlocking_snapshots 失败，已按 fail-closed 加载: world="
                + worldId
                + " msg="
                + ex.getMessage());
      }
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
      RailGraph graph = buildGraphFromRecords(nodeRecords, edgeRecords, interlockingSnapshot);
      try {
        activateSnapshot(worldId, graph, snapshot.builtAt());
      } catch (IllegalStateException exception) {
        debugLogger.accept(
            "持久化调度图未激活，旧联锁投影保持生效: world=" + worldId + " msg=" + exception.getMessage());
      }
    }
  }

  /**
   * 从存储记录还原一张 {@link RailGraph}。
   *
   * <p>注意：该方法不会进行“签名一致性”校验；调用方需自行决定是否信任输入数据。
   */
  public static RailGraph buildGraphFromRecords(
      java.util.List<RailNodeRecord> nodeRecords, java.util.List<RailEdgeRecord> edgeRecords) {
    return buildGraphFromRecords(nodeRecords, edgeRecords, Optional.empty());
  }

  /**
   * 从纯 Node/Edge 记录与独立的稀疏联锁快照还原图。
   *
   * <p>快照缺失、Edge 签名不一致或 Zone 引用损坏时，图仍可用于诊断，但全部 Edge 会投影同一个 incomplete sentinel，运行授权保持 fail-closed。
   */
  public static RailGraph buildGraphFromRecords(
      java.util.List<RailNodeRecord> nodeRecords,
      java.util.List<RailEdgeRecord> edgeRecords,
      Optional<RailInterlockingSnapshotRecord> interlockingSnapshot) {
    Objects.requireNonNull(nodeRecords, "nodeRecords");
    Objects.requireNonNull(edgeRecords, "edgeRecords");
    Objects.requireNonNull(interlockingSnapshot, "interlockingSnapshot");
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

    // 库里带逐边足迹时，用**完整构建**那条路径重建联锁状态——只有它会建出 cell→edge 反向索引。
    //
    // 从 Zone 快照恢复（restoreInterlockingState）按设计只有 Zone、没有逐边足迹，
    // 索引必然为空、cellCoverageAvailable() 为假，于是一切以实测覆盖为放行条件的机制
    // （尾部保护释放 / Phase 4）全部 fail-closed 到一个都不放。
    //
    // 只有**当真有足迹**时才走这条；否则保持原路径，行为一字不变。
    java.util.Map<EdgeId, RailEdgeFootprint> footprintsByEdge = new HashMap<>();
    for (RailEdgeRecord record : edgeRecords) {
      if (record == null || record.edgeId() == null || record.footprintCells().isEmpty()) {
        continue;
      }
      footprintsByEdge.put(
          record.edgeId(),
          new RailEdgeFootprint(
              RailEdgeFootprint.CURRENT_FORMAT_VERSION, true, record.footprintCells()));
    }
    RailInterlockingState interlockingState =
        resolveWorldId(nodeRecords, edgeRecords)
            .map(
                worldId ->
                    footprintsByEdge.isEmpty()
                        ? restoreInterlockingState(
                            worldId, edgesById.keySet(), interlockingSnapshot)
                        : RailInterlockingState.from(worldId, edgesById.keySet(), footprintsByEdge))
            .orElseGet(RailInterlockingState::unavailable);
    return new SimpleRailGraph(nodesById, edgesById, java.util.Set.of(), interlockingState);
  }

  private static RailInterlockingState restoreInterlockingState(
      UUID worldId,
      java.util.Set<EdgeId> expectedEdges,
      Optional<RailInterlockingSnapshotRecord> snapshotOpt) {
    if (snapshotOpt.isEmpty()) {
      return RailInterlockingState.incomplete(worldId, expectedEdges);
    }
    RailInterlockingSnapshotRecord snapshot = snapshotOpt.get();
    String currentSignature = RailInterlockingEdgeSignature.of(expectedEdges);
    if (!worldId.equals(snapshot.worldId())
        || snapshot.formatVersion() != RailInterlockingSnapshotRecord.CURRENT_FORMAT_VERSION
        || !currentSignature.equals(snapshot.edgeSignature())) {
      return RailInterlockingState.incomplete(worldId, expectedEdges);
    }
    try {
      return RailInterlockingState.fromSnapshot(
          worldId, expectedEdges, snapshot.coverage(), snapshot.zones());
    } catch (IllegalArgumentException exception) {
      return RailInterlockingState.incomplete(worldId, expectedEdges);
    }
  }

  private static Optional<UUID> resolveWorldId(
      java.util.List<RailNodeRecord> nodeRecords, java.util.List<RailEdgeRecord> edgeRecords) {
    if (!edgeRecords.isEmpty()) {
      return Optional.of(edgeRecords.get(0).worldId());
    }
    if (!nodeRecords.isEmpty()) {
      return Optional.of(nodeRecords.get(0).worldId());
    }
    return Optional.empty();
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
