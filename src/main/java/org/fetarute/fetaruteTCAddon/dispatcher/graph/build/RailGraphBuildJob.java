package org.fetarute.fetaruteTCAddon.dispatcher.graph.build;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.ExploredRailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdgeMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdgeValidator;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.TrainCartsRailBlockAccess;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailEdgeFootprint;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingState;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;

/**
 * 分段构建调度图的任务：在主线程按 tick 的“时间预算”增量推进，避免一次性卡服。
 *
 * <p>默认不会主动加载区块：轨道访问器会把“未加载区块”视为不可达。
 *
 * <p>当启用 {@link ChunkLoadOptions} 时，会在 HERE 模式沿轨道按需异步加载相邻区块（不会随便扩张）。
 *
 * <p>节点来源：扫描到的节点牌子（waypoint/autostation/depot + TC 的 switcher）。边一律按节点到节点探索，以取得真实 RailPath 足迹。
 */
public final class RailGraphBuildJob implements Runnable, RailGraphBuildTask {

  public enum BuildMode {
    HERE,
    ALL
  }

  private enum Phase {
    DISCOVER_NODES,
    EXPLORE_EDGES
  }

  private final JavaPlugin plugin;
  private final World world;
  private final BuildMode mode;
  private final int signAnchorSearchRadius;
  private final int switcherAnchorSearchRadius;
  private final Set<RailBlockPos> seedRails;
  private final Optional<RailGraphBuildContinuation> continuation;
  private final ChunkLoadOptions chunkLoadOptions;
  private final long tickBudgetNanos;
  private final Consumer<RailGraphBuildOutcome> onFinish;
  private final Consumer<Throwable> onFailure;
  private final Consumer<String> debugLogger;

  private final Map<String, RailNodeRecord> nodesById = new HashMap<>();

  private BukkitTask task;
  private Phase phase;
  private TrainCartsRailBlockAccess access;
  private ConnectedRailNodeDiscoverySession connectedDiscovery;
  private LoadedChunkNodeScanSession loadedChunkDiscovery;
  private NodeToNodeEdgeExplorer nodeToNodeExplorer;
  private List<RailNodeRecord> finalNodes = List.of();
  private List<DuplicateNodeId> duplicateNodeIds = List.of();
  private RailGraphBuildStatus status;

  /**
   * @param seedRails HERE 模式的起始轨道锚点集合（优先来自 TCC 编辑器选中位置，其次来自牌子/脚下轨道）
   * @param tickBudgetMs 每 tick 可消耗的时间预算（毫秒）；越小越不易卡服但构建更慢
   * @param chunkLoadOptions 是否启用沿轨道异步加载区块（HERE 模式默认开启）
   */
  public RailGraphBuildJob(
      JavaPlugin plugin,
      World world,
      BuildMode mode,
      Set<RailBlockPos> seedRails,
      int tickBudgetMs,
      ChunkLoadOptions chunkLoadOptions,
      int signAnchorSearchRadius,
      int switcherAnchorSearchRadius,
      Consumer<RailGraphBuildOutcome> onFinish,
      Consumer<Throwable> onFailure,
      Consumer<String> debugLogger) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.world = Objects.requireNonNull(world, "world");
    this.mode = Objects.requireNonNull(mode, "mode");
    if (signAnchorSearchRadius < 0 || switcherAnchorSearchRadius < 0) {
      throw new IllegalArgumentException("anchorSearchRadius 不能为负");
    }
    this.signAnchorSearchRadius = signAnchorSearchRadius;
    this.switcherAnchorSearchRadius = switcherAnchorSearchRadius;
    this.seedRails = seedRails != null ? Set.copyOf(seedRails) : Set.of();
    this.continuation = Optional.empty();
    this.chunkLoadOptions =
        chunkLoadOptions != null ? chunkLoadOptions : ChunkLoadOptions.disabled();
    if (tickBudgetMs <= 0) {
      throw new IllegalArgumentException("tickBudgetMs 必须为正数");
    }
    this.tickBudgetNanos = tickBudgetMs * 1_000_000L;
    this.onFinish = Objects.requireNonNull(onFinish, "onFinish");
    this.onFailure = Objects.requireNonNull(onFailure, "onFailure");
    this.debugLogger = debugLogger != null ? debugLogger : message -> {};
  }

  /**
   * 从续跑状态创建构建任务。
   *
   * <p>注意：续跑仅支持 HERE 模式。
   *
   * @param continuation 续跑状态快照
   * @param tickBudgetMs 每 tick 可消耗的时间预算（毫秒）
   * @param chunkLoadOptions 本次续跑允许加载的 chunk 配额
   */
  public RailGraphBuildJob(
      JavaPlugin plugin,
      World world,
      RailGraphBuildContinuation continuation,
      int tickBudgetMs,
      ChunkLoadOptions chunkLoadOptions,
      int signAnchorSearchRadius,
      int switcherAnchorSearchRadius,
      Consumer<RailGraphBuildOutcome> onFinish,
      Consumer<Throwable> onFailure,
      Consumer<String> debugLogger) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.world = Objects.requireNonNull(world, "world");
    this.mode = BuildMode.HERE;
    if (signAnchorSearchRadius < 0 || switcherAnchorSearchRadius < 0) {
      throw new IllegalArgumentException("anchorSearchRadius 不能为负");
    }
    this.signAnchorSearchRadius = signAnchorSearchRadius;
    this.switcherAnchorSearchRadius = switcherAnchorSearchRadius;
    this.seedRails = Set.of();
    this.continuation = Optional.ofNullable(continuation);
    this.chunkLoadOptions =
        chunkLoadOptions != null ? chunkLoadOptions : ChunkLoadOptions.disabled();
    if (tickBudgetMs <= 0) {
      throw new IllegalArgumentException("tickBudgetMs 必须为正数");
    }
    this.tickBudgetNanos = tickBudgetMs * 1_000_000L;
    this.onFinish = Objects.requireNonNull(onFinish, "onFinish");
    this.onFailure = Objects.requireNonNull(onFailure, "onFailure");
    this.debugLogger = debugLogger != null ? debugLogger : message -> {};
  }

  /**
   * 启动分段任务。
   *
   * <p>注意：所有 Bukkit API 访问发生在主线程 tick 回调中；若启用 {@link ChunkLoadOptions}，会通过 Paper 的异步接口按需加载区块。
   */
  public synchronized boolean start() {
    if (task != null) {
      return false;
    }

    this.access = new TrainCartsRailBlockAccess(world);
    this.phase = Phase.DISCOVER_NODES;
    this.nodesById.clear();

    if (continuation.isPresent()) {
      RailGraphBuildContinuation cached = continuation.get();
      for (RailNodeRecord node : cached.nodes()) {
        if (node == null) {
          continue;
        }
        nodesById.putIfAbsent(node.nodeId().value(), node);
      }
      this.connectedDiscovery = cached.discoverySession();
      this.connectedDiscovery.beginChunkLoading(chunkLoadOptions);
    } else {
      if (mode == BuildMode.HERE) {
        if (seedRails.isEmpty()) {
          throw new IllegalStateException("HERE 模式缺少起始轨道锚点");
        }
        this.connectedDiscovery =
            new ConnectedRailNodeDiscoverySession(
                world, seedRails, access, debugLogger, chunkLoadOptions, plugin);
      } else {
        this.loadedChunkDiscovery = new LoadedChunkNodeScanSession(world, debugLogger);
      }
    }

    this.status =
        new RailGraphBuildStatus(
            Instant.now(),
            phase.name().toLowerCase(java.util.Locale.ROOT),
            nodesById.size(),
            0,
            0,
            0,
            0,
            0,
            0,
            0);
    this.task = plugin.getServer().getScheduler().runTaskTimer(plugin, this, 1L, 1L);
    debugLogger.accept(
        "开始分段构建调度图: world="
            + world.getName()
            + " mode="
            + mode
            + " tickBudgetMs="
            + (tickBudgetNanos / 1_000_000L));
    return true;
  }

  @Override
  public synchronized Optional<RailGraphBuildStatus> getStatus() {
    return Optional.ofNullable(status);
  }

  @Override
  public synchronized boolean cancel() {
    if (task == null) {
      return false;
    }
    task.cancel();
    task = null;
    // 取消时也释放 chunk tickets
    if (connectedDiscovery != null) {
      connectedDiscovery.releaseChunkTickets();
    }
    return true;
  }

  @Override
  public void run() {
    try {
      Phase currentPhase;
      TrainCartsRailBlockAccess currentAccess;
      ConnectedRailNodeDiscoverySession currentConnectedDiscovery;
      LoadedChunkNodeScanSession currentLoadedDiscovery;
      NodeToNodeEdgeExplorer currentNodeExplorer;
      List<RailNodeRecord> currentFinalNodes;
      List<DuplicateNodeId> currentDuplicateNodeIds;
      synchronized (this) {
        currentPhase = this.phase;
        currentAccess = this.access;
        currentConnectedDiscovery = this.connectedDiscovery;
        currentLoadedDiscovery = this.loadedChunkDiscovery;
        currentNodeExplorer = this.nodeToNodeExplorer;
        currentFinalNodes = this.finalNodes;
        currentDuplicateNodeIds = this.duplicateNodeIds;
      }
      if (currentPhase == null || currentAccess == null) {
        cancel();
        return;
      }

      long deadline = System.nanoTime() + tickBudgetNanos;
      if (currentPhase == Phase.DISCOVER_NODES) {
        runDiscovery(deadline, currentAccess, currentConnectedDiscovery, currentLoadedDiscovery);
        return;
      }

      if (currentPhase != Phase.EXPLORE_EDGES) {
        return;
      }

      if (currentNodeExplorer == null) {
        return;
      }
      Map<EdgeId, ExploredRailEdge> exploredEdges =
          runNodeToNodeEdgeExplore(deadline, currentNodeExplorer, currentFinalNodes);

      if (exploredEdges == null) {
        // 还没完成
        return;
      }

      // 完成构建
      RailGraphBuildCompletion completion = computeCompletion();
      boolean allNodeAnchorsResolved;
      synchronized (this) {
        allNodeAnchorsResolved = status != null && status.nodesMissingAnchors() == 0;
      }
      Map<EdgeId, ExploredRailEdge> publishedEdges =
          applyBuildCompletion(exploredEdges, completion, allNodeAnchorsResolved);
      RailGraph graph = buildGraph(world.getUID(), currentFinalNodes, publishedEdges);
      Instant builtAt = Instant.now();
      String signature = RailGraphSignature.signatureForNodes(currentFinalNodes);
      cancel();
      RailGraphBuildResult result =
          new RailGraphBuildResult(
              graph, builtAt, signature, currentFinalNodes, List.of(), currentDuplicateNodeIds);
      Optional<RailGraphBuildContinuation> nextContinuation = Optional.empty();
      if (mode == BuildMode.HERE && connectedDiscovery != null && connectedDiscovery.isPaused()) {
        nextContinuation =
            Optional.of(
                new RailGraphBuildContinuation(
                    Instant.now(), connectedDiscovery, currentFinalNodes));
      } else if (connectedDiscovery != null) {
        // 非续跑状态，释放 chunk tickets
        connectedDiscovery.releaseChunkTickets();
      }
      List<UnterminatedDirection> unterminatedDirections =
          currentNodeExplorer.unterminatedDirections();
      onFinish.accept(
          new RailGraphBuildOutcome(result, completion, nextContinuation, unterminatedDirections));
    } catch (Throwable ex) {
      cancel();
      onFailure.accept(ex);
    }
  }

  /**
   * 使用节点到节点探索执行边探索。
   *
   * @return 边长映射（如果完成），或 null（如果还在进行中）
   */
  private Map<EdgeId, ExploredRailEdge> runNodeToNodeEdgeExplore(
      long deadline,
      NodeToNodeEdgeExplorer currentNodeExplorer,
      List<RailNodeRecord> currentFinalNodes) {

    int stepsThisTick = currentNodeExplorer.step(deadline);
    synchronized (this) {
      if (status != null) {
        status =
            new RailGraphBuildStatus(
                status.startedAt(),
                Phase.EXPLORE_EDGES.name().toLowerCase(java.util.Locale.ROOT),
                currentFinalNodes.size(),
                status.nodesWithAnchors(),
                status.nodesMissingAnchors(),
                status.scannedChunks(),
                status.scannedSigns(),
                0, // node-to-node 模式没有 visited rail blocks 统计
                currentNodeExplorer.pendingTaskCount(),
                stepsThisTick);
      }
    }
    if (!currentNodeExplorer.isDone()) {
      return null;
    }

    debugLogger.accept("节点到节点探索完成: edges=" + currentNodeExplorer.discoveredEdgeCount());
    return currentNodeExplorer.getExploredEdges();
  }

  private RailGraphBuildCompletion computeCompletion() {
    if (mode != BuildMode.HERE) {
      return RailGraphBuildCompletion.PARTIAL_UNLOADED_CHUNKS;
    }
    if (!chunkLoadOptions.enabled()) {
      return RailGraphBuildCompletion.PARTIAL_UNLOADED_CHUNKS;
    }
    if (connectedDiscovery != null && connectedDiscovery.isPaused()) {
      return RailGraphBuildCompletion.PARTIAL_MAX_CHUNKS;
    }
    if (connectedDiscovery != null && connectedDiscovery.failedChunks() > 0) {
      return RailGraphBuildCompletion.PARTIAL_FAILED_CHUNK_LOADS;
    }
    return RailGraphBuildCompletion.COMPLETE;
  }

  private void runDiscovery(
      long deadline,
      TrainCartsRailBlockAccess currentAccess,
      ConnectedRailNodeDiscoverySession currentConnectedDiscovery,
      LoadedChunkNodeScanSession currentLoadedDiscovery) {
    if (mode == BuildMode.HERE) {
      if (currentConnectedDiscovery == null) {
        throw new IllegalStateException("HERE 模式 discovery 未初始化");
      }
      currentConnectedDiscovery.step(deadline, nodesById);
      synchronized (this) {
        if (status != null) {
          status =
              new RailGraphBuildStatus(
                  status.startedAt(),
                  Phase.DISCOVER_NODES.name().toLowerCase(java.util.Locale.ROOT),
                  nodesById.size(),
                  0,
                  0,
                  currentConnectedDiscovery.scannedChunks(),
                  currentConnectedDiscovery.scannedSigns(),
                  currentConnectedDiscovery.visitedRailBlocks(),
                  currentConnectedDiscovery.queueSize(),
                  currentConnectedDiscovery.processedRailSteps());
        }
      }
      if (!currentConnectedDiscovery.isDone() && !currentConnectedDiscovery.isPaused()) {
        return;
      }

      Set<RailBlockPos> visitedRails = currentConnectedDiscovery.visitedRails();
      List<RailNodeRecord> discovered = new ArrayList<>(nodesById.values());
      ComponentNodeAnchors filtered =
          filterNodesInComponent(discovered, visitedRails, currentAccess);
      debugLogger.accept(
          "HERE 节点过滤: discovered="
              + discovered.size()
              + " filtered="
              + filtered.nodes().size()
              + " visitedRails="
              + visitedRails.size());
      finishDiscoveryAndStartEdgePhase(filtered.nodes(), currentAccess, filtered.anchorsByNode());
      return;
    }

    if (currentLoadedDiscovery == null) {
      throw new IllegalStateException("ALL 模式 discovery 未初始化");
    }
    currentLoadedDiscovery.step(deadline, nodesById);
    synchronized (this) {
      if (status != null) {
        status =
            new RailGraphBuildStatus(
                status.startedAt(),
                Phase.DISCOVER_NODES.name().toLowerCase(java.util.Locale.ROOT),
                nodesById.size(),
                0,
                0,
                currentLoadedDiscovery.chunksScanned(),
                currentLoadedDiscovery.scannedSigns(),
                0,
                0,
                currentLoadedDiscovery.scannedTileEntities());
      }
    }
    if (!currentLoadedDiscovery.isDone()) {
      return;
    }

    finishDiscoveryAndStartEdgePhase(List.copyOf(nodesById.values()), currentAccess, Map.of());
  }

  private void finishDiscoveryAndStartEdgePhase(
      List<RailNodeRecord> discoveredNodes,
      TrainCartsRailBlockAccess currentAccess,
      Map<NodeId, Set<RailBlockPos>> discoveredAnchorsByNode) {
    if (discoveredNodes.isEmpty()) {
      throw new IllegalStateException("未扫描到任何节点");
    }
    if (!containsSignalNodes(discoveredNodes)) {
      throw new IllegalStateException("未扫描到任何本插件节点牌子");
    }
    initEdgeSession(discoveredNodes, currentAccess, discoveredAnchorsByNode);
    synchronized (this) {
      this.finalNodes = discoveredNodes;
      this.duplicateNodeIds =
          mode == BuildMode.HERE && connectedDiscovery != null
              ? connectedDiscovery.duplicateNodeIds()
              : (loadedChunkDiscovery != null
                  ? loadedChunkDiscovery.duplicateNodeIds()
                  : List.of());
      this.phase = Phase.EXPLORE_EDGES;
    }
  }

  private static boolean containsSignalNodes(List<RailNodeRecord> nodes) {
    for (RailNodeRecord node : nodes) {
      if (node == null) {
        continue;
      }
      NodeType type = node.nodeType();
      if (type == NodeType.WAYPOINT || type == NodeType.STATION || type == NodeType.DEPOT) {
        return true;
      }
    }
    return false;
  }

  private int resolveAnchorRadius(NodeType nodeType) {
    return anchorRadius(nodeType, signAnchorSearchRadius, switcherAnchorSearchRadius);
  }

  /** 节点找锚点轨道的搜索半径；build、refresh、extend 共用。 */
  public static int anchorRadius(NodeType nodeType, int signRadius, int switcherRadius) {
    if (nodeType == NodeType.SWITCHER || nodeType == NodeType.PORTAL) {
      // 道岔与传送门节点的坐标就是轨道方块本身，锚点只在附近找。
      return switcherRadius;
    }
    return signRadius;
  }

  private void initEdgeSession(
      List<RailNodeRecord> nodes,
      TrainCartsRailBlockAccess currentAccess,
      Map<NodeId, Set<RailBlockPos>> discoveredAnchorsByNode) {
    Map<NodeId, Set<RailBlockPos>> anchorsByNode = new HashMap<>(discoveredAnchorsByNode);
    int missingAnchors = 0;
    for (RailNodeRecord node : nodes) {
      if (anchorsByNode.containsKey(node.nodeId())) {
        continue;
      }
      RailBlockPos center = new RailBlockPos(node.x(), node.y(), node.z());
      int anchorRadius = resolveAnchorRadius(node.nodeType());
      Set<RailBlockPos> anchors = currentAccess.findNearestRailBlocks(center, anchorRadius);
      if (anchors.isEmpty()) {
        missingAnchors++;
        continue;
      }
      anchorsByNode.put(node.nodeId(), anchors);
    }

    initNodeToNodeExplorer(nodes, anchorsByNode, missingAnchors);
  }

  private void initNodeToNodeExplorer(
      List<RailNodeRecord> nodes,
      Map<NodeId, Set<RailBlockPos>> anchorsByNode,
      int missingAnchors) {
    Set<NodeId> switcherNodes = new HashSet<>();
    for (RailNodeRecord node : nodes) {
      if (node == null) {
        continue;
      }
      if (node.nodeType() == NodeType.SWITCHER) {
        switcherNodes.add(node.nodeId());
      }
    }
    // 构建锚点 → 节点ID 的索引
    Map<RailBlockPos, NodeId> anchorIndex = new HashMap<>();
    for (Map.Entry<NodeId, Set<RailBlockPos>> entry : anchorsByNode.entrySet()) {
      NodeId nodeId = entry.getKey();
      for (RailBlockPos anchor : entry.getValue()) {
        anchorIndex.put(anchor, nodeId);
      }
    }

    NodeToNodeEdgeExplorer explorer =
        new NodeToNodeEdgeExplorer(
            world,
            anchorIndex,
            switcherNodes,
            EdgeExploreMode.NODE_TO_NODE_MAX_DISTANCE,
            debugLogger);

    // 添加所有节点作为探索起点
    for (Map.Entry<NodeId, Set<RailBlockPos>> entry : anchorsByNode.entrySet()) {
      explorer.addNode(entry.getKey(), entry.getValue());
    }

    debugLogger.accept(
        "初始化节点到节点边探索: nodes="
            + nodes.size()
            + " anchors="
            + anchorIndex.size()
            + " missingAnchors="
            + missingAnchors);

    synchronized (this) {
      this.nodeToNodeExplorer = explorer;
      if (status != null) {
        status =
            new RailGraphBuildStatus(
                status.startedAt(),
                Phase.EXPLORE_EDGES.name().toLowerCase(java.util.Locale.ROOT),
                nodes.size(),
                anchorsByNode.size(),
                missingAnchors,
                status.scannedChunks(),
                status.scannedSigns(),
                0,
                explorer.pendingTaskCount(),
                0);
      }
    }
  }

  /**
   * 筛选 HERE 连通分量内的节点，并保留本轮已经解析出的轨道锚点。
   *
   * <p>锚点搜索会检查节点周围一整个方块立方体；将结果交给 edge phase 复用，避免 discovery 完成的同一 tick 对每个保留节点再执行一次完全相同的世界查询。
   */
  private ComponentNodeAnchors filterNodesInComponent(
      List<RailNodeRecord> discovered,
      Set<RailBlockPos> visitedRails,
      TrainCartsRailBlockAccess access) {
    List<RailNodeRecord> filtered = new ArrayList<>();
    Map<NodeId, Set<RailBlockPos>> anchorsByNode = new HashMap<>();
    for (RailNodeRecord node : discovered) {
      RailBlockPos center = new RailBlockPos(node.x(), node.y(), node.z());
      Set<RailBlockPos> anchors =
          access.findNearestRailBlocks(center, resolveAnchorRadius(node.nodeType()));
      if (anchors.isEmpty()) {
        continue;
      }
      if (anchors.stream().anyMatch(visitedRails::contains)) {
        filtered.add(node);
        anchorsByNode.put(node.nodeId(), Set.copyOf(anchors));
      }
    }
    return new ComponentNodeAnchors(filtered, anchorsByNode);
  }

  /** HERE 节点过滤与已验证 anchor 的不可变交接结果。 */
  private record ComponentNodeAnchors(
      List<RailNodeRecord> nodes, Map<NodeId, Set<RailBlockPos>> anchorsByNode) {

    private ComponentNodeAnchors {
      nodes = List.copyOf(nodes);
      Map<NodeId, Set<RailBlockPos>> immutableAnchors = new HashMap<>();
      anchorsByNode.forEach((nodeId, anchors) -> immutableAnchors.put(nodeId, Set.copyOf(anchors)));
      anchorsByNode = Map.copyOf(immutableAnchors);
    }
  }

  /** 由节点记录与探索到的区间组装图：过滤跨股道直连边，按足迹建联锁状态。 */
  public static RailGraph buildGraph(
      java.util.UUID worldId,
      List<RailNodeRecord> nodeRecords,
      Map<EdgeId, ExploredRailEdge> exploredEdges) {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(nodeRecords, "nodeRecords");
    Objects.requireNonNull(exploredEdges, "exploredEdges");
    Map<NodeId, RailNode> nodesById = new HashMap<>();
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

    // 过滤跨轨道直连边（同一区间的不同轨道应通过 switcher 连接）
    Map<EdgeId, ExploredRailEdge> filteredEdges =
        RailEdgeValidator.filterCrossTrackExploredEdges(exploredEdges, nodesById);

    Map<EdgeId, RailEdge> edgesById = new HashMap<>();
    for (Map.Entry<EdgeId, ExploredRailEdge> entry : filteredEdges.entrySet()) {
      EdgeId edgeId = entry.getKey();
      ExploredRailEdge exploredEdge = entry.getValue();
      RailNode a = nodesById.get(edgeId.a());
      RailNode b = nodesById.get(edgeId.b());
      if (a == null || b == null) {
        continue;
      }
      edgesById.put(
          edgeId,
          new RailEdge(
              edgeId,
              edgeId.a(),
              edgeId.b(),
              exploredEdge.lengthBlocks(),
              0.0,
              true,
              Optional.of(new RailEdgeMetadata(a.waypointMetadata(), b.waypointMetadata()))));
    }

    Map<EdgeId, RailEdgeFootprint> footprintsByEdge = new HashMap<>();
    edgesById
        .keySet()
        .forEach(edgeId -> footprintsByEdge.put(edgeId, filteredEdges.get(edgeId).footprint()));
    RailInterlockingState interlockingState =
        RailInterlockingState.from(worldId, edgesById.keySet(), footprintsByEdge);
    return new SimpleRailGraph(nodesById, edgesById, Set.of(), interlockingState);
  }

  /**
   * 把世界发现阶段的完整性约束应用到每条已捕获足迹。
   *
   * <p>即使局部 edge walker 已经自然耗尽，未加载区块、加载失败或达到续跑配额都表示世界级探索尚不完整；此时保留已捕获坐标用于诊断，但不得发布 {@code
   * complete=true}。
   */
  static Map<EdgeId, ExploredRailEdge> applyBuildCompletion(
      Map<EdgeId, ExploredRailEdge> exploredEdges, RailGraphBuildCompletion completion) {
    return applyBuildCompletion(exploredEdges, completion, true);
  }

  static Map<EdgeId, ExploredRailEdge> applyBuildCompletion(
      Map<EdgeId, ExploredRailEdge> exploredEdges,
      RailGraphBuildCompletion completion,
      boolean allNodeAnchorsResolved) {
    Objects.requireNonNull(exploredEdges, "exploredEdges");
    Objects.requireNonNull(completion, "completion");
    if (completion == RailGraphBuildCompletion.COMPLETE && allNodeAnchorsResolved) {
      return Map.copyOf(exploredEdges);
    }

    Map<EdgeId, ExploredRailEdge> incomplete = new HashMap<>();
    exploredEdges.forEach(
        (edgeId, edge) -> {
          RailEdgeFootprint footprint = edge.footprint();
          incomplete.put(
              edgeId,
              new ExploredRailEdge(
                  edge.lengthBlocks(),
                  new RailEdgeFootprint(footprint.formatVersion(), false, footprint.cells())));
        });
    return Map.copyOf(incomplete);
  }

  public record RailGraphBuildStatus(
      Instant startedAt,
      String phase,
      int nodesFound,
      int nodesWithAnchors,
      int nodesMissingAnchors,
      int scannedChunks,
      int scannedSigns,
      int visitedRailBlocks,
      int queueSize,
      long processedSteps) {

    public RailGraphBuildStatus {
      Objects.requireNonNull(startedAt, "startedAt");
      phase = phase == null ? "" : phase;
      if (nodesFound < 0
          || nodesWithAnchors < 0
          || nodesMissingAnchors < 0
          || scannedChunks < 0
          || scannedSigns < 0
          || visitedRailBlocks < 0
          || queueSize < 0
          || processedSteps < 0) {
        throw new IllegalArgumentException("build status 计数不能为负");
      }
    }
  }
}
