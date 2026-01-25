package org.fetarute.fetaruteTCAddon.dispatcher.graph.build;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdgeMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdgeValidator;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailGraphMultiSourceExplorerSession;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.TrainCartsRailBlockAccess;
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
 * <p>节点来源（按优先级合并去重）：
 *
 * <ul>
 *   <li>{@code preseedNodes}：预置节点列表（用于 TCC 无牌子线网，把 coaster 节点注入为 Node）
 *   <li>{@code seedNode}：HERE 模式下玩家附近的节点牌子（可选）
 *   <li>扫描到的节点牌子：waypoint/autostation/depot + TC 的 switcher
 * </ul>
 */
public final class RailGraphBuildJob implements Runnable {

  public enum BuildMode {
    HERE,
    ALL
  }

  private enum Phase {
    DISCOVER_NODES,
    EXPLORE_EDGES
  }

  private static final int DEFAULT_ANCHOR_SEARCH_RADIUS = 2;
  private static final int SIGN_ANCHOR_SEARCH_RADIUS = 6;
  private static final int DEFAULT_MAX_DISTANCE_BLOCKS = 512;

  /** 边探索阶段每次调用的批量大小。减小该值可降低单帧卡顿但会延长总构建时间。 */
  private static final int DEFAULT_STEP_BATCH = 64;

  private final JavaPlugin plugin;
  private final World world;
  private final BuildMode mode;
  private final EdgeExploreMode edgeExploreMode;
  private final RailNodeRecord seedNode;
  private final Set<RailBlockPos> seedRails;
  private final List<RailNodeRecord> preseedNodes;
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
  private RailGraphMultiSourceExplorerSession edgeSession;
  private NodeToNodeEdgeExplorer nodeToNodeExplorer;
  private List<RailNodeRecord> finalNodes = List.of();
  private List<DuplicateNodeId> duplicateNodeIds = List.of();
  private RailGraphBuildStatus status;

  /**
   * @param seedRails HERE 模式的起始轨道锚点集合（优先来自 TCC 编辑器选中位置，其次来自牌子/脚下轨道）
   * @param preseedNodes 预置节点列表（用于把 TCC TrackNode 注入为 Node）
   * @param tickBudgetMs 每 tick 可消耗的时间预算（毫秒）；越小越不易卡服但构建更慢
   * @param chunkLoadOptions 是否启用沿轨道异步加载区块（用于无需手动预加载的运维模式）
   * @param edgeExploreMode 边探索模式（BFS 或节点到节点）
   */
  public RailGraphBuildJob(
      JavaPlugin plugin,
      World world,
      BuildMode mode,
      RailNodeRecord seedNode,
      Set<RailBlockPos> seedRails,
      List<RailNodeRecord> preseedNodes,
      int tickBudgetMs,
      ChunkLoadOptions chunkLoadOptions,
      EdgeExploreMode edgeExploreMode,
      Consumer<RailGraphBuildOutcome> onFinish,
      Consumer<Throwable> onFailure,
      Consumer<String> debugLogger) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.world = Objects.requireNonNull(world, "world");
    this.mode = Objects.requireNonNull(mode, "mode");
    this.edgeExploreMode =
        edgeExploreMode != null ? edgeExploreMode : EdgeExploreMode.bfsMultiSource();
    this.seedNode = seedNode;
    this.seedRails = seedRails != null ? Set.copyOf(seedRails) : Set.of();
    this.preseedNodes = preseedNodes != null ? List.copyOf(preseedNodes) : List.of();
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
   * @param edgeExploreMode 边探索模式
   */
  public RailGraphBuildJob(
      JavaPlugin plugin,
      World world,
      RailGraphBuildContinuation continuation,
      int tickBudgetMs,
      ChunkLoadOptions chunkLoadOptions,
      EdgeExploreMode edgeExploreMode,
      Consumer<RailGraphBuildOutcome> onFinish,
      Consumer<Throwable> onFailure,
      Consumer<String> debugLogger) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.world = Objects.requireNonNull(world, "world");
    this.mode = BuildMode.HERE;
    this.edgeExploreMode =
        edgeExploreMode != null ? edgeExploreMode : EdgeExploreMode.bfsMultiSource();
    this.seedNode = null;
    this.seedRails = Set.of();
    this.preseedNodes = List.of();
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
      for (RailNodeRecord preseed : preseedNodes) {
        if (preseed == null) {
          continue;
        }
        nodesById.putIfAbsent(preseed.nodeId().value(), preseed);
      }

      if (mode == BuildMode.HERE) {
        if (seedNode != null) {
          nodesById.put(seedNode.nodeId().value(), seedNode);
        }
        Set<RailBlockPos> anchors = seedRails;
        if (anchors.isEmpty() && seedNode != null) {
          anchors =
              access.findNearestRailBlocks(
                  new RailBlockPos(seedNode.x(), seedNode.y(), seedNode.z()),
                  DEFAULT_ANCHOR_SEARCH_RADIUS);
        }
        if (anchors.isEmpty()) {
          throw new IllegalStateException("HERE 模式缺少起始轨道锚点");
        }
        this.connectedDiscovery =
            new ConnectedRailNodeDiscoverySession(
                world, anchors, access, debugLogger, chunkLoadOptions, plugin);
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

  public synchronized Optional<RailGraphBuildStatus> getStatus() {
    return Optional.ofNullable(status);
  }

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
      RailGraphMultiSourceExplorerSession currentEdgeSession;
      NodeToNodeEdgeExplorer currentNodeExplorer;
      List<RailNodeRecord> currentFinalNodes;
      List<DuplicateNodeId> currentDuplicateNodeIds;
      synchronized (this) {
        currentPhase = this.phase;
        currentAccess = this.access;
        currentConnectedDiscovery = this.connectedDiscovery;
        currentLoadedDiscovery = this.loadedChunkDiscovery;
        currentEdgeSession = this.edgeSession;
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

      // 根据模式执行边探索
      Map<EdgeId, Integer> edgeLengths;
      if (edgeExploreMode.isNodeToNode()) {
        if (currentNodeExplorer == null) {
          return;
        }
        edgeLengths =
            runNodeToNodeEdgeExplore(
                deadline, currentNodeExplorer, currentFinalNodes, currentDuplicateNodeIds);
      } else {
        if (currentEdgeSession == null) {
          return;
        }
        edgeLengths =
            runBfsEdgeExplore(
                deadline, currentEdgeSession, currentFinalNodes, currentDuplicateNodeIds);
      }

      if (edgeLengths == null) {
        // 还没完成
        return;
      }

      // 完成构建
      RailGraph graph = buildGraph(currentFinalNodes, edgeLengths);
      Instant builtAt = Instant.now();
      String signature = RailGraphSignature.signatureForNodes(currentFinalNodes);
      cancel();
      RailGraphBuildResult result =
          new RailGraphBuildResult(
              graph, builtAt, signature, currentFinalNodes, List.of(), currentDuplicateNodeIds);
      RailGraphBuildCompletion completion = computeCompletion();
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
      onFinish.accept(new RailGraphBuildOutcome(result, completion, nextContinuation));
    } catch (Throwable ex) {
      cancel();
      onFailure.accept(ex);
    }
  }

  /**
   * 使用 BFS 多源探索执行边探索。
   *
   * @return 边长映射（如果完成），或 null（如果还在进行中）
   */
  private Map<EdgeId, Integer> runBfsEdgeExplore(
      long deadline,
      RailGraphMultiSourceExplorerSession currentEdgeSession,
      List<RailNodeRecord> currentFinalNodes,
      List<DuplicateNodeId> currentDuplicateNodeIds) {

    // 限制每 tick 最大步数，确保即使单步操作慢也不会卡住太久
    int maxStepsPerTick = 128;
    int stepsThisTick = 0;
    while (System.nanoTime() < deadline
        && !currentEdgeSession.isDone()
        && stepsThisTick < maxStepsPerTick) {
      // 每次只处理一小批，然后检查时间预算
      int stepped = currentEdgeSession.step(DEFAULT_STEP_BATCH);
      stepsThisTick += stepped;
      // 如果时间紧张就提前退出，避免超出预算
      if (System.nanoTime() >= deadline) {
        break;
      }
    }
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
                currentEdgeSession.visitedRailBlocks(),
                currentEdgeSession.queueSize(),
                currentEdgeSession.processedSteps());
      }
    }
    if (!currentEdgeSession.isDone()) {
      return null;
    }

    return currentEdgeSession.edgeLengths();
  }

  /**
   * 使用节点到节点探索执行边探索。
   *
   * @return 边长映射（如果完成），或 null（如果还在进行中）
   */
  private Map<EdgeId, Integer> runNodeToNodeEdgeExplore(
      long deadline,
      NodeToNodeEdgeExplorer currentNodeExplorer,
      List<RailNodeRecord> currentFinalNodes,
      List<DuplicateNodeId> currentDuplicateNodeIds) {

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
    return currentNodeExplorer.getDiscoveredEdges();
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
      List<RailNodeRecord> filtered =
          filterNodesInComponent(discovered, visitedRails, currentAccess);
      debugLogger.accept(
          "HERE 节点过滤: discovered="
              + discovered.size()
              + " filtered="
              + filtered.size()
              + " visitedRails="
              + visitedRails.size());
      finishDiscoveryAndStartEdgePhase(filtered, currentAccess);
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

    finishDiscoveryAndStartEdgePhase(List.copyOf(nodesById.values()), currentAccess);
  }

  private void finishDiscoveryAndStartEdgePhase(
      List<RailNodeRecord> discoveredNodes, TrainCartsRailBlockAccess currentAccess) {
    if (discoveredNodes.isEmpty()) {
      throw new IllegalStateException("未扫描到任何节点");
    }
    if (!containsSignalNodes(discoveredNodes)) {
      throw new IllegalStateException("未扫描到任何本插件节点牌子");
    }
    initEdgeSession(discoveredNodes, currentAccess);
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

  private void initEdgeSession(
      List<RailNodeRecord> nodes, TrainCartsRailBlockAccess currentAccess) {
    Map<NodeId, Set<RailBlockPos>> anchorsByNode = new HashMap<>();
    int missingAnchors = 0;
    for (RailNodeRecord node : nodes) {
      RailBlockPos center = new RailBlockPos(node.x(), node.y(), node.z());
      int anchorRadius =
          node.nodeType() == NodeType.SWITCHER
              ? DEFAULT_ANCHOR_SEARCH_RADIUS
              : SIGN_ANCHOR_SEARCH_RADIUS;
      Set<RailBlockPos> anchors = currentAccess.findNearestRailBlocks(center, anchorRadius);
      if (anchors.isEmpty()) {
        missingAnchors++;
        continue;
      }
      anchorsByNode.put(node.nodeId(), anchors);
    }

    // 根据模式选择边探索实现
    if (edgeExploreMode.isNodeToNode()) {
      initNodeToNodeExplorer(nodes, anchorsByNode, missingAnchors);
    } else {
      initBfsEdgeSession(nodes, anchorsByNode, missingAnchors, currentAccess);
    }
  }

  private void initBfsEdgeSession(
      List<RailNodeRecord> nodes,
      Map<NodeId, Set<RailBlockPos>> anchorsByNode,
      int missingAnchors,
      TrainCartsRailBlockAccess currentAccess) {
    RailGraphMultiSourceExplorerSession newSession =
        new RailGraphMultiSourceExplorerSession(
            anchorsByNode, currentAccess, edgeExploreMode.maxDistanceBlocks());
    synchronized (this) {
      this.edgeSession = newSession;
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
                newSession.visitedRailBlocks(),
                newSession.queueSize(),
                newSession.processedSteps());
      }
    }
  }

  private void initNodeToNodeExplorer(
      List<RailNodeRecord> nodes,
      Map<NodeId, Set<RailBlockPos>> anchorsByNode,
      int missingAnchors) {
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
            world, anchorIndex, edgeExploreMode.maxDistanceBlocks(), debugLogger);

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

  private static List<RailNodeRecord> filterNodesInComponent(
      List<RailNodeRecord> discovered,
      Set<RailBlockPos> visitedRails,
      TrainCartsRailBlockAccess access) {
    List<RailNodeRecord> filtered = new ArrayList<>();
    for (RailNodeRecord node : discovered) {
      RailBlockPos center = new RailBlockPos(node.x(), node.y(), node.z());
      Set<RailBlockPos> anchors =
          access.findNearestRailBlocks(
              center,
              node.nodeType() == NodeType.SWITCHER
                  ? DEFAULT_ANCHOR_SEARCH_RADIUS
                  : SIGN_ANCHOR_SEARCH_RADIUS);
      if (anchors.isEmpty()) {
        continue;
      }
      if (anchors.stream().anyMatch(visitedRails::contains)) {
        filtered.add(node);
      }
    }
    return List.copyOf(filtered);
  }

  private RailGraph buildGraph(List<RailNodeRecord> nodeRecords, Map<EdgeId, Integer> edgeLengths) {
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
    Map<EdgeId, Integer> filteredEdgeLengths =
        RailEdgeValidator.filterCrossTrackEdges(edgeLengths, nodesById);

    Map<EdgeId, RailEdge> edgesById = new HashMap<>();
    for (Map.Entry<EdgeId, Integer> entry : filteredEdgeLengths.entrySet()) {
      EdgeId edgeId = entry.getKey();
      int lengthBlocks = entry.getValue();
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
              lengthBlocks,
              0.0,
              true,
              Optional.of(new RailEdgeMetadata(a.waypointMetadata(), b.waypointMetadata()))));
    }

    return new SimpleRailGraph(nodesById, edgesById, Set.of());
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
