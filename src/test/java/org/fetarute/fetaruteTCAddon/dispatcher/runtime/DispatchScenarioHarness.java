package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.routeStop;
import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.testConfigView;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.signactions.SignActionType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.TagStore;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.RuntimeDispatchRequestProvider;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.RuntimeSignalReevaluationScheduler;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.SignalEvaluator;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.SignalEventBus;

/**
 * Phase 0 多列车确定性调度场景骨架。
 *
 * <p>骨架驱动的是真实调度路径：每 tick 对每列车调用包内 {@link
 * RuntimeDispatchService#handleSignalTick(RuntimeTrainHandle, boolean)}，对走完一条边的列车调用 {@link
 * RuntimeDispatchService#handleWaypointMemberEnter} 提交到达，并接入真实的 {@link SignalEvaluator}
 * 释放唤醒链路。它<b>不</b>替代、不旁路、也不放宽任何调度判定。
 *
 * <p>列车是否前进只取决于已发布信号（{@link RouteProgressRegistry.RouteProgressEntry#lastSignal()}）：非 STOP
 * 才推进。骨架自身不判断安全性， 因此任何"列车该不该走"的结论都来自生产代码。
 *
 * <p>确定性来源：固定的列车遍历顺序、固定 tick 步长、由边长与固定速度推导的行驶时长、排序后的不变量输出。唯一不可控的是 {@link Instant#now()}——{@link
 * RuntimeDispatchService} 内部直接读取它，因此骨架不得依赖任何基于墙钟阈值的行为（TTL、老化、超时恢复）。
 */
final class DispatchScenarioHarness {

  /**
   * 默认边长。
   *
   * <p>必须明显大于线路速度对应的制动距离（{@code v²/2a}），否则授权末端永远落在制动距离之内，列车无法稳定运行。 当前取线速 10 blocks/s（制动距离约 14
   * blocks）配 30 blocks 的边，既满足该约束，又把每条边压到约 60 tick， 让多车场景能在秒级跑完。
   */
  static final int DEFAULT_EDGE_LENGTH = 30;

  /**
   * 失败现场保留的 debug 行数上限。
   *
   * <p>多车场景每 tick 产生成百上千行，整套场景无界保留会把测试 JVM 撑爆（实测 {@code OutOfMemoryError}）。 展示只需要最近若干行；不变量需要的"本
   * tick 新增"走独立缓冲，不受此上限影响。
   */
  private static final int DEBUG_LOG_RETAINED_LINES = 3000;

  /** 需要整场累计计数的诊断 token——保留窗口会丢弃旧行，但计数必须完整。 */
  private static final List<String> COUNTED_DIAGNOSTIC_TOKENS =
      List.of("SMART_LIVE_BLOCKER_SNAPSHOT_UPDATED", "SMART_LIVE_BLOCKER_SNAPSHOT_REJECTED");

  /** 只保留最近 N 行的 debug 汇聚点，同时把每一行原样转发给当前 tick 的待检缓冲。 */
  static final class BoundedLog {
    private final java.util.ArrayDeque<String> retained = new java.util.ArrayDeque<>();
    private final List<String> sink = new ArrayList<>();
    private final int limit;

    BoundedLog(int limit) {
      this.limit = Math.max(1, limit);
    }

    void add(String line) {
      if (line == null) {
        return;
      }
      sink.add(line);
      retained.addLast(line);
      while (retained.size() > limit) {
        retained.removeFirst();
      }
    }

    /** 取走自上次调用以来的全部新行。 */
    List<String> drain() {
      if (sink.isEmpty()) {
        return List.of();
      }
      List<String> fresh = List.copyOf(sink);
      sink.clear();
      return fresh;
    }

    List<String> retained() {
      return List.copyOf(retained);
    }

    int size() {
      return retained.size();
    }

    String get(int index) {
      return retained().get(index);
    }
  }

  private final SimpleOccupancyManager occupancy;
  private final RouteProgressRegistry registry;
  private final RuntimeDispatchService service;
  private final Map<String, ScenarioTrain> trains;
  private final List<Runnable> nextTickTasks;
  private final BoundedLog debugLog;
  private final SignActionEvent enterEvent;
  private final Map<NodeId, List<NodeId>> adjacency;
  private final Map<String, RouteDefinition> routeByTrain;
  private final List<String> violations = new ArrayList<>();

  /** 每列车当前 STOP 生命周期的标识与已连续出现的 tick 数，供 I5 判断"停了多久"。 */
  private final Map<String, StopStreak> stopStreaks = new LinkedHashMap<>();

  private int tick;

  private DispatchScenarioHarness(
      SimpleOccupancyManager occupancy,
      RouteProgressRegistry registry,
      RuntimeDispatchService service,
      Map<String, ScenarioTrain> trains,
      List<Runnable> nextTickTasks,
      BoundedLog debugLog,
      SignActionEvent enterEvent,
      Map<NodeId, List<NodeId>> adjacency,
      Map<String, RouteDefinition> routeByTrain) {
    this.adjacency = adjacency;
    this.routeByTrain = routeByTrain;
    this.occupancy = occupancy;
    this.registry = registry;
    this.service = service;
    this.trains = trains;
    this.nextTickTasks = nextTickTasks;
    this.debugLog = debugLog;
    this.enterEvent = enterEvent;
  }

  // ---------------------------------------------------------------- 构建

  static Builder builder() {
    return new Builder();
  }

  /**
   * 生成一条 T1 单线走廊：两端车站 + 中间区间点。
   *
   * <p>节点 id 必须用真实编码语义，否则生产判定会（正确地）拒绝放行：
   *
   * <ul>
   *   <li>两端必须是 {@code OP:S:NAME:1} 形式的 STATION。{@code
   *       EntryLookaheadEvaluator.targetIsSingleRegionBoundary} 只有在规范计划终止于 Station/Depot
   *       边界时才允许"停在单线区内"作为安全例外；终止于普通 waypoint 的走廊是一条 没有出口的死胡同，列车会被 {@code
   *       entry-lookahead-exit-not-feasible} 永久挡住——那是对的。
   *   <li>中间点用 {@code OP:FROM:TO:track:seq} 形式的 INTERVAL，让 {@code
   *       SemanticCorridorDirectionResolver} 能从站间语义轴推导方向，与生产一致。
   * </ul>
   *
   * <p>线性链上每条边都是桥，因此 {@code SingleLineSectionIndex} 会把整条链生成为一个 {@code single:section:*}
   * 冲突区——这正是"一段单线只能有一个行车方向"的语义。
   *
   * @param from 起点站名
   * @param to 终点站名
   * @param intervalCount 两站之间的区间点数量
   */
  static List<NodeId> corridor(String from, String to, int intervalCount) {
    List<NodeId> nodes = new ArrayList<>(intervalCount + 2);
    nodes.add(NodeId.of("OP:S:" + from + ":1"));
    for (int i = 0; i < intervalCount; i++) {
      nodes.add(
          NodeId.of(String.format(java.util.Locale.ROOT, "OP:%s:%s:1:%03d", from, to, i + 1)));
    }
    nodes.add(NodeId.of("OP:S:" + to + ":1"));
    return List.copyOf(nodes);
  }

  /**
   * 一个命名拓扑：节点全集、无向边集，以及可供列车使用的有序物理路径与站点序列。
   *
   * @param nodes 节点全集（含道岔与所有股道）
   * @param edges 无向边，端点对
   * @param physicalPath 主方向的有序节点串，列车逐节点推进
   * @param stations 交路 waypoint 序列（只列车站）
   */
  record Topology(
      List<NodeId> nodes,
      List<NodeId[]> edges,
      List<NodeId> physicalPath,
      List<NodeId> stations,
      Map<NodeId, String> dynamicSpecByStation) {}

  /**
   * 生成"终端站 — 会让站链 — 终端站"的 T2 拓扑。
   *
   * <p>为什么必须是会让环而不是一串单线站：{@code SingleLineSectionIndex} 只在 <b>bridgeDegree≠2 / 分支道岔 / 多股道会让点</b>
   * 处截断单线链。单股道中间站的 bridgeDegree 恰好是 2，因此一串单股道站会被合并成 <b>一个</b>跨越全线的单线区；而静止列车的硬授权窗口固定为 1 条边（{@code
   * HARD_AUTHORITY_LOOKAHEAD_EDGES=1}，距离扩展在 速度 0 时为 0），永远无法证明清出多边的区——列车会被 {@code
   * entry-lookahead-exit-not-feasible} 正确地挡死。
   *
   * <p>因此每个中间站建成双股道会让环 + 两端分支道岔：道岔是 section 边界，站间正线恰好 1 条边，构成一个可进入的单线区。 这也正是真实单线铁路的运行方式。
   *
   * @param stationNames 站名序列，首尾为终端站，中间为会让站；至少 2 个
   */
  static Topology loopCorridor(List<String> stationNames) {
    if (stationNames.size() < 2) {
      throw new IllegalArgumentException("至少需要 2 个车站");
    }
    List<NodeId> nodes = new ArrayList<>();
    List<NodeId[]> edges = new ArrayList<>();
    List<NodeId> physical = new ArrayList<>();
    List<NodeId> stations = new ArrayList<>();

    Map<NodeId, String> dynamicSpecs = new LinkedHashMap<>();
    NodeId previousExit = null;
    String previousStation = null;
    for (int i = 0; i < stationNames.size(); i++) {
      String station = stationNames.get(i);
      boolean terminal = i == 0 || i == stationNames.size() - 1;
      NodeId track1 = NodeId.of("OP:S:" + station + ":1");
      nodes.add(track1);
      stations.add(track1);

      if (terminal) {
        if (previousExit != null) {
          edges.add(new NodeId[] {previousExit, track1});
        }
        physical.add(track1);
        previousExit = track1;
        previousStation = station;
        continue;
      }

      NodeId track2 = NodeId.of("OP:S:" + station + ":2");
      NodeId west = NodeId.of("SWITCHER:" + station + ":W");
      NodeId east = NodeId.of("SWITCHER:" + station + ":E");
      nodes.add(track2);
      nodes.add(west);
      nodes.add(east);
      // 会让站声明为 DYNAMIC 双股道：调度可以把对向车分到 2 道交会。
      // 不给这条规格，两车就只能抢同一条股道，会让环等于不存在。
      dynamicSpecs.put(track1, "DYNAMIC:OP:S:" + station + ":[1:2]");
      edges.add(new NodeId[] {previousExit, west});
      edges.add(new NodeId[] {west, track1});
      edges.add(new NodeId[] {west, track2});
      edges.add(new NodeId[] {track1, east});
      edges.add(new NodeId[] {track2, east});
      physical.add(west);
      physical.add(track1);
      physical.add(east);
      previousExit = east;
      previousStation = station;
    }
    return new Topology(
        List.copyOf(nodes),
        List.copyOf(edges),
        List.copyOf(physical),
        List.copyOf(stations),
        Map.copyOf(dynamicSpecs));
  }

  private static NodeType nodeTypeFor(NodeId nodeId) {
    if (nodeId.value().startsWith("SWITCHER:")) {
      return NodeType.SWITCHER;
    }
    String[] parts = nodeId.value().split(":");
    if (parts.length == 4 && "S".equalsIgnoreCase(parts[1])) {
      return NodeType.STATION;
    }
    if (parts.length == 4 && "D".equalsIgnoreCase(parts[1])) {
      return NodeType.DEPOT;
    }
    return NodeType.WAYPOINT;
  }

  static final class Builder {
    private final List<NodeId> nodes = new ArrayList<>();
    private final List<NodeId[]> explicitEdges = new ArrayList<>();
    private final Map<NodeId, String> dynamicSpecs = new LinkedHashMap<>();
    private final List<TrainSpec> trainSpecs = new ArrayList<>();
    private int edgeLength = DEFAULT_EDGE_LENGTH;
    private int lookaheadEdges = 3;

    Builder nodes(List<NodeId> value) {
      nodes.clear();
      nodes.addAll(value);
      explicitEdges.clear();
      return this;
    }

    /** 使用命名拓扑：节点与边都由拓扑给出，不再按线性链推导。 */
    Builder topology(Topology topology) {
      nodes.clear();
      nodes.addAll(topology.nodes());
      explicitEdges.clear();
      explicitEdges.addAll(topology.edges());
      dynamicSpecs.clear();
      dynamicSpecs.putAll(topology.dynamicSpecByStation());
      return this;
    }

    Builder edgeLength(int value) {
      edgeLength = value;
      return this;
    }

    /**
     * 设置硬授权窗口边数。
     *
     * <p>必须 ≥ 场景中最长单线 section 的边数，否则列车无法证明清出该区，会被 {@code entry-lookahead-exit-not-feasible}
     * 永久挡住——那是正确的 fail-closed 行为，不是缺陷。默认 3，与生产 {@code lookahead-edges} 一致。
     */
    Builder lookaheadEdges(int value) {
      lookaheadEdges = value;
      return this;
    }

    /**
     * 登记一列车，使用该车专属交路。
     *
     * @param name 逻辑列车名
     * @param path 该车的交路节点序列；与 {@link #nodes} 的顺序相反即代表反方向运行
     * @param startIndex 起始所在的路径下标
     */
    Builder train(String name, List<NodeId> path, int startIndex) {
      return train(name, name + "-route", path, startIndex);
    }

    /**
     * 登记一列车并指定交路代码。
     *
     * <p>多列车共用同一个 {@code routeCode} 即代表同交路跟驰——这会让 {@link RouteProgressRegistry} 的索引比较生效，
     * 与跨交路场景的判定路径不同，两者必须分别覆盖。
     */
    Builder train(String name, String routeCode, List<NodeId> path, int startIndex) {
      if (startIndex < 0 || startIndex >= path.size()) {
        throw new IllegalArgumentException("startIndex 超出交路范围: " + startIndex);
      }
      List<NodeId> physical = List.copyOf(path);
      return train(
          name,
          routeCode,
          physical,
          List.of(physical.get(0), physical.get(physical.size() - 1)),
          startIndex);
    }

    /**
     * 登记一列车并显式给出交路 waypoint 序列。
     *
     * <p>{@code physicalPath} 是逐节点推进用的完整节点串（含道岔），{@code routeWaypoints} 是交路声明的站点序列。
     * 二者分离是必须的：把每个节点都列成 waypoint 会让每次 Movement Plan 只覆盖一条边。
     */
    Builder train(
        String name,
        String routeCode,
        List<NodeId> physicalPath,
        List<NodeId> routeWaypoints,
        int startIndex) {
      if (startIndex < 0 || startIndex >= physicalPath.size()) {
        throw new IllegalArgumentException("startIndex 超出路径范围: " + startIndex);
      }
      trainSpecs.add(
          new TrainSpec(
              name, routeCode, List.copyOf(physicalPath), List.copyOf(routeWaypoints), startIndex));
      return this;
    }

    DispatchScenarioHarness build() {
      if (nodes.size() < 2) {
        throw new IllegalStateException("拓扑至少需要 2 个节点");
      }
      UUID worldId =
          UUID.nameUUIDFromBytes(
              "dispatch-scenario".getBytes(java.nio.charset.StandardCharsets.UTF_8));

      Map<NodeId, RailNode> railNodes = new LinkedHashMap<>();
      for (NodeId node : nodes) {
        railNodes.put(node, new RailNodeTest(node, nodeTypeFor(node), Optional.empty()));
      }
      Map<EdgeId, RailEdge> railEdges = new LinkedHashMap<>();
      List<NodeId[]> edgePairs = new ArrayList<>(explicitEdges);
      if (edgePairs.isEmpty()) {
        for (int i = 0; i + 1 < nodes.size(); i++) {
          edgePairs.add(new NodeId[] {nodes.get(i), nodes.get(i + 1)});
        }
      }
      Map<EdgeId, org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailEdgeFootprint>
          footprints = new LinkedHashMap<>();
      int cellIndex = 0;
      for (NodeId[] pair : edgePairs) {
        EdgeId edgeId = EdgeId.undirected(pair[0], pair[1]);
        if (railEdges.containsKey(edgeId)) {
          continue;
        }
        railEdges.put(
            edgeId,
            new RailEdge(edgeId, pair[0], pair[1], edgeLength, -1.0, true, Optional.empty()));
        // 每条边一格互不相交的足迹：目录因此是 complete，且不产生任何 interlocking zone。
        // 没有完整目录，LiveRailFootprintResolver 会返回 interlocking-catalog-incomplete，
        // 于是 livePhysicalReleaseGuardsOrFailRetain 保留列车的<b>全部</b> claim——
        // 任何涉及释放的场景都会必然死锁，而那是骨架缺少现场目录，不是调度缺陷。
        footprints.put(
            edgeId,
            new org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailEdgeFootprint(
                org.fetarute
                    .fetaruteTCAddon
                    .dispatcher
                    .graph
                    .interlocking
                    .RailEdgeFootprint
                    .CURRENT_FORMAT_VERSION,
                true,
                Set.of(new RailFootprintCell(cellIndex++, 64, 0))));
      }
      SimpleRailGraph graph =
          new SimpleRailGraph(
              railNodes,
              railEdges,
              Set.of(),
              org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingState.from(
                  worldId, railEdges.keySet(), footprints));

      ConfigManager config = mock(ConfigManager.class);
      lenient().when(config.current()).thenReturn(testConfigView(20, 20.0, lookaheadEdges, 1));

      RailGraphService graphs = mock(RailGraphService.class);
      lenient()
          .when(graphs.getSnapshot(worldId))
          .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
      lenient()
          .when(graphs.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
          .thenReturn(10.0);

      // 交路只列两端车站：中间区间点由路径展开填充。若把每个节点都列为 waypoint，
      // 每次 Movement Plan 只覆盖一条边，永远无法证明清出多边的单线 section。
      Map<String, RouteDefinition> routesByCode = new LinkedHashMap<>();
      for (TrainSpec spec : trainSpecs) {
        routesByCode.computeIfAbsent(
            spec.routeCode(),
            code -> new RouteDefinition(RouteId.of(code), spec.routeWaypoints(), Optional.empty()));
      }
      RouteDefinitionCache routes = mock(RouteDefinitionCache.class);
      lenient()
          .when(routes.findByCodes(any(), any(), any()))
          .thenAnswer(
              inv -> {
                Object routeCode = inv.getArgument(2);
                return routeCode == null
                    ? Optional.empty()
                    : Optional.ofNullable(routesByCode.get(routeCode.toString()));
              });
      lenient()
          .when(routes.findStop(any(), anyInt()))
          .thenAnswer(
              inv -> {
                RouteId routeId = inv.getArgument(0);
                int index = inv.getArgument(1);
                RouteDefinition route = routeId == null ? null : routesByCode.get(routeId.value());
                if (route == null || index < 0 || index >= route.waypoints().size()) {
                  return Optional.empty();
                }
                NodeId stopNode = route.waypoints().get(index);
                String dynamicSpec = dynamicSpecs.get(stopNode);
                if (dynamicSpec == null) {
                  return Optional.of(routeStop(index, stopNode, RouteStopPassType.PASS));
                }
                return Optional.of(
                    new org.fetarute.fetaruteTCAddon.company.model.RouteStop(
                        UUID.nameUUIDFromBytes(
                            (routeId.value() + "#" + index)
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        index,
                        Optional.empty(),
                        Optional.of(stopNode.value()),
                        Optional.empty(),
                        RouteStopPassType.PASS,
                        Optional.of(dynamicSpec)));
              });

      SignNodeRegistry signs = mock(SignNodeRegistry.class);
      lenient()
          .when(signs.findByNodeId(any(), any()))
          .thenAnswer(
              inv -> {
                NodeId node = inv.getArgument(0);
                if (node == null || !railNodes.containsKey(node)) {
                  return Optional.empty();
                }
                return Optional.of(
                    new SignNodeRegistry.SignNodeInfo(
                        new SignNodeDefinition(
                            node, nodeTypeFor(node), Optional.empty(), Optional.empty()),
                        worldId,
                        "world",
                        0,
                        64,
                        0));
              });

      SignalEventBus eventBus = new SignalEventBus();
      SimpleOccupancyManager occupancy =
          new SimpleOccupancyManager(
              (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy(), eventBus);
      RouteProgressRegistry registry = new RouteProgressRegistry();
      // 有界：多车场景每 tick 会产生成百上千行，无界保留会把测试 JVM 撑爆（实测 OOM）。
      // 只留最近若干行供失败现场展示；不变量所需的"本 tick 新增"走 pendingDiagnostics，不依赖这里。
      BoundedLog debugLog = new BoundedLog(DEBUG_LOG_RETAINED_LINES);

      RuntimeDispatchService service =
          new RuntimeDispatchService(
              occupancy,
              graphs,
              routes,
              registry,
              signs,
              mock(LayoverRegistry.class),
              new DwellRegistry(),
              config,
              null,
              new TrainConfigResolver(),
              debugLog::add);

      Map<String, ScenarioTrain> trains = new LinkedHashMap<>();
      Map<String, RouteDefinition> routeByTrainName = new LinkedHashMap<>();
      for (TrainSpec spec : trainSpecs) {
        RouteDefinition route = routesByCode.get(spec.routeCode());
        TagStore tags =
            new TagStore(
                spec.name(),
                "FTA_OPERATOR_CODE=op",
                "FTA_LINE_CODE=l1",
                "FTA_ROUTE_CODE=" + spec.routeCode(),
                "FTA_ROUTE_INDEX=" + spec.startIndex());
        ScenarioTrain train =
            new ScenarioTrain(
                spec.name(), worldId, tags, spec.path(), edgeLength, spec.startIndex());
        registry.initFromTags(spec.name(), tags.properties(), route);
        // 交路索引必须与物理位置一致：取物理起点之前（含）最后一个交路 waypoint。
        // 否则列车的 nextTarget 会指向身后的站，判定链会从一个自相矛盾的状态出发。
        int routeIndex = 0;
        NodeId arrivedStation = spec.routeWaypoints().get(0);
        for (int w = 0; w < spec.routeWaypoints().size(); w++) {
          int physicalPosition = spec.path().indexOf(spec.routeWaypoints().get(w));
          if (physicalPosition >= 0 && physicalPosition <= spec.startIndex()) {
            routeIndex = w;
            arrivedStation = spec.routeWaypoints().get(w);
          }
        }
        registry.recordArrival(
            spec.name(), null, route, routeIndex, arrivedStation, tags.properties(), Instant.now());
        if (!spec.path().get(spec.startIndex()).equals(arrivedStation)) {
          registry.updateLastPassedGraphNode(
              spec.name(), spec.path().get(spec.startIndex()), Instant.now());
        }
        trains.put(spec.name(), train);
        routeByTrainName.put(spec.name(), route);
      }

      // 真实的释放唤醒链路：资源释放 / 队首变化 -> 下一 tick 完整重评估。
      List<Runnable> nextTickTasks = new ArrayList<>();
      DispatchScenarioHarness harness =
          new DispatchScenarioHarness(
              occupancy,
              registry,
              service,
              trains,
              nextTickTasks,
              debugLog,
              enterEventMock(worldId),
              buildAdjacency(edgePairs),
              routeByTrainName);
      RuntimeSignalReevaluationScheduler scheduler =
          new RuntimeSignalReevaluationScheduler(
              nextTickTasks::add,
              trainName -> {
                ScenarioTrain target = trains.get(trainName);
                if (target != null) {
                  service.handleSignalTick(target, false);
                }
              });
      RuntimeDispatchRequestProvider provider =
          new RuntimeDispatchRequestProvider(occupancy, service::trainsWaitingForDynamicCapacity);
      new SignalEvaluator(eventBus, provider, scheduler::request).start();

      // 现场列车在第一个 tick 之前必须已经持有自己所在位置的物理占用。否则别的列车会被允许把
      // "已经被车占着的节点"一并纳入授权窗口——那是骨架缺少初始现场事实，不是调度缺陷
      // （首轮实测：对向场景里 east 把 west 所在的 BRAVO 站台也一并占了）。
      //
      // 这里不走 rebuildOccupancySnapshot：生产的启动重建要求可解析的实体轨道足迹，
      // 而骨架的 liveRailFootprintCells 是占位值，重建会正确地 fail-closed 成 STOP_FIRST 冻结。
      // 因此只登记"车在哪"这一条现场事实，与既有测试夹具用一次 acquire 表示"站台上有车"一致。
      // 它不授予任何通行权，也不预置任何前方资源。
      Instant fieldTime = Instant.now();
      for (ScenarioTrain train : trains.values()) {
        occupancy.acquire(
            new org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest(
                train.name(),
                Optional.empty(),
                fieldTime,
                List.of(
                    org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource
                        .forNode(train.currentNode())),
                Map.of()));
      }
      return harness;
    }
  }

  private static Map<NodeId, List<NodeId>> buildAdjacency(List<NodeId[]> edgePairs) {
    Map<NodeId, List<NodeId>> adjacency = new LinkedHashMap<>();
    for (NodeId[] pair : edgePairs) {
      adjacency.computeIfAbsent(pair[0], unused -> new ArrayList<>()).add(pair[1]);
      adjacency.computeIfAbsent(pair[1], unused -> new ArrayList<>()).add(pair[0]);
    }
    return Map.copyOf(adjacency);
  }

  private static SignActionEvent enterEventMock(UUID worldId) {
    SignActionEvent event = mock(SignActionEvent.class);
    org.bukkit.World world = mock(org.bukkit.World.class);
    lenient().when(world.getUID()).thenReturn(worldId);
    lenient().when(event.getWorld()).thenReturn(world);
    lenient().when(event.getAction()).thenReturn(SignActionType.MEMBER_ENTER);
    return event;
  }

  private record TrainSpec(
      String name,
      String routeCode,
      List<NodeId> path,
      List<NodeId> routeWaypoints,
      int startIndex) {}

  // ---------------------------------------------------------------- 驱动

  /**
   * 推进若干 tick。
   *
   * <p>单 tick 的固定顺序：① 排空上一 tick 登记的重评估任务；② 按登记顺序对每列车执行完整信号 tick；③ 按登记顺序推进已获放行的列车，跨过节点边界时提交到达；④
   * 执行全部不变量检查。三段严格分离，避免同一 tick 内前车的到达影响后车的判定顺序。
   */
  void runTicks(int count) {
    for (int i = 0; i < count; i++) {
      tick++;
      drainNextTickTasks();
      for (ScenarioTrain train : trains.values()) {
        service.handleSignalTick(train, false);
      }
      for (ScenarioTrain train : trains.values()) {
        advance(train);
      }
      checkInvariants();
    }
  }

  private void drainNextTickTasks() {
    if (nextTickTasks.isEmpty()) {
      return;
    }
    List<Runnable> due = List.copyOf(nextTickTasks);
    nextTickTasks.clear();
    for (Runnable task : due) {
      task.run();
    }
  }

  /**
   * 按调度下发的 destination 推进列车。
   *
   * <p>列车走哪条股道由调度决定（DYNAMIC 选台），不能由骨架预先钉死：预先钉死会让会让环形同虚设，两列对向车被迫抢同一条股道， 场景变成物理上无解的对头，而那不是调度缺陷。这里只做
   * TrainCarts 做的事——朝 destination 沿最短路走一跳。
   */
  private void advance(ScenarioTrain train) {
    train.stepKinematics();
    double step = train.speed();
    if (step <= 0.0) {
      return;
    }
    NodeId destination = destinationOf(train);
    if (destination == null || destination.equals(train.currentNode())) {
      return;
    }
    NodeId nextHop = nextHopToward(train.currentNode(), destination);
    if (nextHop == null) {
      return;
    }
    if (!train.travelToward(nextHop, step)) {
      return;
    }
    NodeType nodeType = nodeTypeFor(nextHop);
    SignNodeDefinition definition =
        new SignNodeDefinition(nextHop, nodeType, Optional.empty(), Optional.empty());
    service.handleWaypointMemberEnter(train, enterEvent, definition);
    if (nodeType == NodeType.STATION) {
      // 车站到达必须走生产的到站入口。handleWaypointMemberEnter 对 NodeType.STATION 明确返回
      // shouldAdvancePassedWaypoint=false（只更新 lastPassedGraphNode，不推进 route index），
      // 推进由 AutoStation 的到站路径负责。只调前者会让 route index 永远停在起点，
      // 而 applyCurrentNodeOverride 又把窗口起点改写成列车实际所在的站台——
      // 于是 waypoint N 与 N+1 塌成同一个节点，movement plan 永久不可构建。
      service.handleStationArrival(train, definition);
    }
  }

  /**
   * 解析列车当前该驶向哪里。
   *
   * <p>优先用调度写入 TrainCarts 的 destination——它反映 DYNAMIC 选台的实际结果。token 尚未激活时该值为空， 此时退回调度自己的 {@code
   * nextTarget}，而不是让列车停住：停住会把"授权已发布但 destination 还没提交" 这个正常的中间态误报成停滞。
   */
  private NodeId destinationOf(ScenarioTrain train) {
    NodeId committed = train.destinationNode();
    if (committed != null) {
      return committed;
    }
    // 必须用 DYNAMIC 解析后的有效 waypoint，不能用交路声明的占位节点：会让站声明的是 1 道，
    // 而调度可能把本车分到 2 道。用声明节点会把列车开到调度没有授权的股道上，
    // 于是账本说车在 2 道、实体在 1 道，两车物理重叠——那是骨架制造的假故障。
    RouteDefinition route = routeByTrain.get(train.name());
    Optional<RouteProgressRegistry.RouteProgressEntry> entry = registry.get(train.name());
    if (route != null && entry.isPresent()) {
      List<NodeId> effective = service.resolveEffectiveWaypointsForEvent(train.name(), route);
      int next = entry.get().currentIndex() + 1;
      if (next >= 0 && next < effective.size()) {
        return effective.get(next);
      }
    }
    return entry.flatMap(RouteProgressRegistry.RouteProgressEntry::nextTarget).orElse(null);
  }

  /** 无权重 BFS：返回从 {@code from} 朝 {@code to} 的第一跳，不可达时返回 null。 */
  private NodeId nextHopToward(NodeId from, NodeId to) {
    if (from == null || to == null || from.equals(to)) {
      return null;
    }
    Map<NodeId, NodeId> parent = new LinkedHashMap<>();
    java.util.Deque<NodeId> queue = new java.util.ArrayDeque<>();
    queue.add(from);
    parent.put(from, null);
    while (!queue.isEmpty()) {
      NodeId current = queue.poll();
      if (current.equals(to)) {
        NodeId step = current;
        while (parent.get(step) != null && !from.equals(parent.get(step))) {
          step = parent.get(step);
        }
        return step;
      }
      for (NodeId neighbour : adjacency.getOrDefault(current, List.of())) {
        if (!parent.containsKey(neighbour)) {
          parent.put(neighbour, current);
          queue.add(neighbour);
        }
      }
    }
    return null;
  }

  // ---------------------------------------------------------------- 观测

  int tick() {
    return tick;
  }

  SignalAspect signalOf(String trainName) {
    return registry
        .get(trainName)
        .map(RouteProgressRegistry.RouteProgressEntry::lastSignal)
        .orElse(SignalAspect.STOP);
  }

  /** 返回当前生效的停因代码，无停因时返回 "-"。 */
  String stopReasonOf(String trainName) {
    return service.getActiveStopState(trainName).map(RuntimeStopState::reasonCode).orElse("-");
  }

  /** 返回列车已经通过的节点数。 */
  int progressOf(String trainName) {
    return train(trainName).nodesPassed();
  }

  NodeId positionOf(String trainName) {
    return train(trainName).currentNode();
  }

  /**
   * 列车是否到达交路终点站。
   *
   * <p>比较站点分组（前三段）而非精确节点：DYNAMIC 选台可能把列车分到 2 道，到达 {@code OP:S:ECHO:2} 同样算到站。
   */
  boolean reachedEnd(String trainName) {
    ScenarioTrain train = train(trainName);
    return stationGroup(train.currentNode())
        .equals(stationGroup(train.path().get(train.path().size() - 1)));
  }

  private static String stationGroup(NodeId nodeId) {
    String[] parts = nodeId.value().split(":");
    return parts.length >= 3 ? parts[0] + ":" + parts[1] + ":" + parts[2] : nodeId.value();
  }

  ScenarioTrain train(String trainName) {
    ScenarioTrain train = trains.get(trainName);
    if (train == null) {
      throw new IllegalArgumentException("未登记的列车: " + trainName);
    }
    return train;
  }

  List<String> trainNames() {
    return List.copyOf(trains.keySet());
  }

  SimpleOccupancyManager occupancy() {
    return occupancy;
  }

  RuntimeDispatchService service() {
    return service;
  }

  RouteProgressRegistry registry() {
    return registry;
  }

  /** 保留的最近若干行，用于失败现场展示。不是全量日志。 */
  List<String> debugLog() {
    return debugLog.retained();
  }

  /** 返回按列车名排序的 claim 快照，避免枚举顺序进入断言。 */
  List<OccupancyClaim> sortedClaims() {
    List<OccupancyClaim> claims = new ArrayList<>(occupancy.snapshotClaims());
    claims.sort(
        Comparator.comparing((OccupancyClaim c) -> c.resource().toString())
            .thenComparing(OccupancyClaim::trainName)
            .thenComparing(c -> c.role().name()));
    return claims;
  }

  // ---------------------------------------------------------------- 不变量

  /** 整场累计出现过的诊断 token 次数；保留窗口有上限，计数没有。 */
  private final Map<String, Integer> diagnosticCounts = new LinkedHashMap<>();

  /** I7 的跨 tick 记忆：每个 (冲突资源, 列车) 迄今拿到过的最好 enqueueSequence。 */
  private Map<String, Long> queueBaselines = Map.of();

  /** I7 实际做过多少次"有基线可比"的比较——用来证明它不是空绿。 */
  private int queuePositionComparisons;

  /** 整场观察到的单个冲突队列最大并发条目数——1 表示从未真正发生竞争。 */
  private int maxQueueDepth;

  /**
   * I7 实际做过多少次"有基线可比"的比较。
   *
   * <p>为 0 表示整场没有任何列车在<b>未取得 claim</b> 的情况下跨 tick 停留在同一个队列里——此时 I7 全绿不代表任何事。
   */
  int queuePositionComparisons() {
    return queuePositionComparisons;
  }

  /** 整场观察到的单个冲突队列最大并发条目数；{@code < 2} 表示从未真正发生排队竞争。 */
  int maxQueueDepth() {
    return maxQueueDepth;
  }

  private void tallyQueueContention(DispatchInvariants.Sample sample) {
    for (var queue : sample.queues()) {
      if (queue == null || queue.resource() == null) {
        continue;
      }
      maxQueueDepth = Math.max(maxQueueDepth, queue.entries().size());
      for (var entry : queue.entries()) {
        if (entry != null
            && queueBaselines.containsKey(
                queue.resource()
                    + "|"
                    + org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.TrainNameNormalizer
                        .normalizeKey(entry.trainName()))) {
          queuePositionComparisons++;
        }
      }
    }
  }

  /**
   * 整场（不受保留窗口限制）出现过多少次含该 token 的 debug 行。
   *
   * <p>用它来确认某条证据链<b>真的跑过</b>，避免"没触发也算绿"。
   */
  int diagnosticCount(String token) {
    return diagnosticCounts.getOrDefault(token, 0);
  }

  private void tallyDiagnostics(List<String> lines) {
    for (String line : lines) {
      for (String token : COUNTED_DIAGNOSTIC_TOKENS) {
        if (line.contains(token)) {
          diagnosticCounts.merge(token, 1, Integer::sum);
        }
      }
    }
  }

  private void checkInvariants() {
    List<String> fresh = debugLog.drain();
    tallyDiagnostics(fresh);
    DispatchInvariants.Sample sample =
        new DispatchInvariants.Sample(
            tick,
            sortedClaims(),
            routePathsByTrain(),
            adjacency,
            stoppedTrains(),
            occupancy.snapshotQueues(),
            queueBaselines,
            fresh);
    tallyQueueContention(sample);
    List<String> found = DispatchInvariants.check(sample);
    for (String violation : found) {
      violations.add("tick=" + tick + " " + violation);
    }
    queueBaselines = DispatchInvariants.nextQueueBaselines(sample, queueBaselines);
  }

  /**
   * 采集本 tick 各车的 STOP 状态及其已持续 tick 数。
   *
   * <p>"同一轮 STOP" 以 {@code reasonCode + enteredAt} 判定：停因变化或重新进入都会重新计数，避免把两次不同原因的停车 拼成一次长停车。
   */
  private List<DispatchInvariants.StoppedTrain> stoppedTrains() {
    List<DispatchInvariants.StoppedTrain> stopped = new ArrayList<>();
    for (ScenarioTrain train : trains.values()) {
      String name = train.name();
      if (reachedEnd(name)) {
        // 已到交路终点的列车不参与 I5。骨架不建模终到生命周期（layover 登记、目的地清除、回库），
        // 生产会把这类列车交给 REUSE_AT_TERM / TERMINAL 流程，而骨架只能让它原地被继续 tick，
        // 于是调度（正确地）报 path_unresolvable:X->X。那是骨架缺口，不是"停车说不出原因"。
        stopStreaks.remove(name);
        continue;
      }
      Optional<RuntimeStopState> stateOpt = service.getActiveStopState(name);
      if (stateOpt.isEmpty()) {
        stopStreaks.remove(name);
        continue;
      }
      RuntimeStopState state = stateOpt.get();
      String identity = state.reasonCode() + "@" + state.enteredAt();
      StopStreak previous = stopStreaks.get(name);
      int ticks =
          previous != null && previous.identity().equals(identity) ? previous.ticks() + 1 : 1;
      stopStreaks.put(name, new StopStreak(identity, ticks));
      stopped.add(new DispatchInvariants.StoppedTrain(name, state, ticks));
    }
    return stopped;
  }

  private record StopStreak(String identity, int ticks) {}

  /**
   * 为 I3 提供每列车的合法资源范围。
   *
   * <p>范围是"本车交路路径 + 其经停站的全部股道"。DYNAMIC 选台允许调度把列车分到 2 道，因此把同一站分组下的所有股道都算作 合法范围；再窄就会把正确的选台结果误报成陈旧
   * claim。
   */
  private Map<String, List<NodeId>> routePathsByTrain() {
    Map<String, List<NodeId>> paths = new LinkedHashMap<>();
    for (ScenarioTrain train : trains.values()) {
      java.util.LinkedHashSet<NodeId> allowed = new java.util.LinkedHashSet<>(train.path());
      java.util.Set<String> groups = new java.util.LinkedHashSet<>();
      for (NodeId node : train.path()) {
        groups.add(stationGroup(node));
      }
      for (NodeId node : adjacency.keySet()) {
        if (groups.contains(stationGroup(node))) {
          allowed.add(node);
        }
      }
      paths.put(train.name(), List.copyOf(allowed));
    }
    return paths;
  }

  List<String> violations() {
    return List.copyOf(violations);
  }

  /** 只返回指定不变量的违反，用于让场景显式声明它保证哪几条。 */
  List<String> violationsOf(String invariantId) {
    List<String> matched = new ArrayList<>();
    for (String violation : violations) {
      if (violation.contains(" " + invariantId + " ")) {
        matched.add(violation);
      }
    }
    return List.copyOf(matched);
  }

  /**
   * 断言指定不变量没有被违反。
   *
   * <p>场景必须显式列出它保证哪几条，而不是笼统地"全绿"：Phase 0 的作用是把现状钉住，其中包含已知为错的行为。 用它来声明保证范围，用 {@link #violationsOf}
   * 去正面钉住已知缺陷，<b>不要</b>用它来掩盖违反。
   */
  void assertNoViolationsOf(String... invariantIds) {
    List<String> found = new ArrayList<>();
    for (String id : invariantIds) {
      found.addAll(violationsOf(id));
    }
    if (found.isEmpty()) {
      return;
    }
    throw new AssertionError(
        "不变量 "
            + String.join("/", invariantIds)
            + " 被违反 ("
            + found.size()
            + " 条):\n  "
            + String.join("\n  ", found)
            + "\n"
            + describeState());
  }

  /** 违反时抛出，附带当前现场，避免需要重跑才能定位。 */
  void assertNoViolations() {
    if (violations.isEmpty()) {
      return;
    }
    throw new AssertionError(
        "不变量被违反 ("
            + violations.size()
            + " 条):\n  "
            + String.join("\n  ", violations)
            + "\n"
            + describeState());
  }

  String describeState() {
    StringBuilder sb = new StringBuilder("现场快照 tick=").append(tick).append('\n');
    for (ScenarioTrain train : trains.values()) {
      sb.append("  列车 ")
          .append(train.name())
          .append(" 位置=")
          .append(train.currentNode().value())
          .append("+")
          .append(String.format(java.util.Locale.ROOT, "%.1f", train.blocksIntoEdge()))
          .append(" 已过节点=")
          .append(train.nodesPassed())
          .append(" 目标=")
          .append(train.lastDestination == null ? "-" : train.lastDestination)
          .append(" 信号=")
          .append(signalOf(train.name()))
          .append(" 停因=")
          .append(
              service
                  .getActiveStopState(train.name())
                  .map(RuntimeStopState::reasonCode)
                  .orElse("-"))
          .append('\n');
    }
    sb.append("  账本:\n");
    for (OccupancyClaim claim : sortedClaims()) {
      sb.append("    ")
          .append(claim.resource())
          .append(" <- ")
          .append(claim.trainName())
          .append(" role=")
          .append(claim.role())
          .append(" dir=")
          .append(claim.corridorDirection().map(Enum::name).orElse("-"))
          .append('\n');
    }
    appendTrace(sb, "最近停因", 6, "SMART_STOP_LIFECYCLE");
    appendTrace(
        sb,
        "最近准入与自持判定",
        10,
        "SMART_ADMISSION_REASON",
        "保护道岔占用",
        "SELF_OWNED",
        "ADMISSION_EXIT_PROOF",
        "REJECT_",
        "方向",
        "SINGLE_CORRIDOR");
    return sb.toString();
  }

  private void appendTrace(StringBuilder sb, String title, int limit, String... keywords) {
    List<String> lines = recentDecisionTrace(limit, keywords);
    if (lines.isEmpty()) {
      return;
    }
    sb.append("  ").append(title).append(":\n");
    for (String line : lines) {
      sb.append("    ").append(line).append('\n');
    }
  }

  /**
   * 返回最近的准入/停因判定 trace。
   *
   * <p>失败时不需要重跑就能定位是哪条判定拦下了列车。只做关键字筛选，不解析结构。
   */
  List<String> recentDecisionTrace(int limit, String... keywords) {
    List<String> picked = new ArrayList<>();
    List<String> recent = debugLog.retained();
    for (int i = recent.size() - 1; i >= 0 && picked.size() < limit; i--) {
      String line = recent.get(i);
      if (line == null) {
        continue;
      }
      for (String keyword : keywords) {
        if (line.contains(keyword)) {
          picked.add(line.length() > 420 ? line.substring(0, 420) + "…" : line);
          break;
        }
      }
    }
    java.util.Collections.reverse(picked);
    return picked;
  }

  // ---------------------------------------------------------------- 列车

  /**
   * 会移动的测试列车。
   *
   * <p>它只反映被授予的东西：{@link #isMoving()} 由骨架按已发布信号设置，句柄自身不做任何安全判断。控车调用次数保留下来，供断言"信号与实际控车动作一致"。
   */
  static final class ScenarioTrain implements RuntimeTrainHandle {

    private final String name;
    private final UUID worldId;
    private final TagStore tags;
    private final List<NodeId> path;
    private final int edgeLength;

    private NodeId currentNode;
    private NodeId movingToward;
    private double blocksIntoEdge;
    private int nodesPassed;
    private double speed;
    private double targetSpeed;
    private double accel = 0.05;

    int launchCalls;
    int stopCalls;
    int hardStopCalls;
    int destroyCalls;
    int reverseCalls;
    String lastDestination;

    ScenarioTrain(
        String name,
        UUID worldId,
        TagStore tags,
        List<NodeId> path,
        int edgeLength,
        int startIndex) {
      this.name = name;
      this.worldId = worldId;
      this.tags = tags;
      this.path = List.copyOf(path);
      this.edgeLength = edgeLength;
      this.currentNode = this.path.get(startIndex);
    }

    String name() {
      return name;
    }

    List<NodeId> path() {
      return path;
    }

    NodeId currentNode() {
      return currentNode;
    }

    int nodesPassed() {
      return nodesPassed;
    }

    double blocksIntoEdge() {
      return blocksIntoEdge;
    }

    /** 当前 TrainCarts destination 对应的图节点；未下发时返回 null。 */
    NodeId destinationNode() {
      return lastDestination == null || lastDestination.isBlank()
          ? null
          : NodeId.of(lastDestination);
    }

    /**
     * 按上一次控车指令推进一步速度。
     *
     * <p>速度必须来自调度下发的目标值，不能由骨架固定：固定全速会让制动距离超过边长， 列车在"够不到授权末端"与"停下后窗口回缩"之间每 tick 翻转，制造出并不存在的调度缺陷。
     */
    void stepKinematics() {
      if (speed < targetSpeed) {
        speed = Math.min(targetSpeed, speed + accel);
      } else if (speed > targetSpeed) {
        speed = Math.max(targetSpeed, speed - accel);
      }
      if (speed <= 0.0) {
        speed = 0.0;
        movingToward = null;
      }
    }

    double speed() {
      return speed;
    }

    /**
     * 朝目标节点行驶。
     *
     * @return 是否到达该节点（到达时 {@link #currentNode} 已更新）
     */
    boolean travelToward(NodeId target, double blocks) {
      if (!target.equals(movingToward)) {
        movingToward = target;
        blocksIntoEdge = 0.0;
      }
      blocksIntoEdge += blocks;
      if (blocksIntoEdge < edgeLength) {
        return false;
      }
      blocksIntoEdge = 0.0;
      currentNode = target;
      movingToward = null;
      nodesPassed++;
      return true;
    }

    @Override
    public boolean isValid() {
      return true;
    }

    @Override
    public boolean isMoving() {
      return speed > 0.0;
    }

    @Override
    public double currentSpeedBlocksPerTick() {
      return speed;
    }

    @Override
    public UUID worldId() {
      return worldId;
    }

    @Override
    public TrainProperties properties() {
      return tags.properties();
    }

    @Override
    public Object physicalRuntimeIdentity() {
      return this;
    }

    @Override
    public OptionalDouble estimatedTrainLengthBlocks() {
      return OptionalDouble.of(1.0);
    }

    @Override
    public Optional<Set<RailFootprintCell>> liveRailFootprintCells() {
      // 目录完整时，未命中任何联锁区的方块即为"完整清空"，释放守卫因此可以正常收缩旧 claim。
      return Optional.of(Set.of(new RailFootprintCell(0, 200, 0)));
    }

    @Override
    public void stop() {
      stopCalls++;
      targetSpeed = 0.0;
      speed = 0.0;
      movingToward = null;
    }

    @Override
    public void stopHard() {
      hardStopCalls++;
      stop();
    }

    @Override
    public void launch(double targetBlocksPerTick, double accelBlocksPerTickSquared) {
      launchCalls++;
      applySpeedCommand(targetBlocksPerTick, accelBlocksPerTickSquared);
    }

    /**
     * 记录运动中的限速指令。
     *
     * <p>接口默认实现在运动中交给 TrainCarts 的 speedLimit 接管；测试句柄里等价的做法就是把目标速度记下来， 否则 approach/caution
     * 限速对骨架完全不可见，列车会一直按上一次发车速度冲过去。
     */
    @Override
    public void accelerateTo(double targetBlocksPerTick, double accelBlocksPerTickSquared) {
      applySpeedCommand(targetBlocksPerTick, accelBlocksPerTickSquared);
    }

    private void applySpeedCommand(double targetBlocksPerTick, double accelBlocksPerTickSquared) {
      if (Double.isFinite(targetBlocksPerTick) && targetBlocksPerTick >= 0.0) {
        targetSpeed = targetBlocksPerTick;
      }
      if (Double.isFinite(accelBlocksPerTickSquared) && accelBlocksPerTickSquared > 0.0) {
        accel = accelBlocksPerTickSquared;
      }
    }

    @Override
    public void destroy() {
      destroyCalls++;
    }

    @Override
    public void setRouteIndex(int index) {}

    @Override
    public void setRouteId(String routeId) {}

    @Override
    public void setDestination(String destination) {
      lastDestination = destination;
    }

    @Override
    public Optional<org.bukkit.block.BlockFace> forwardDirection() {
      return Optional.empty();
    }

    @Override
    public void reverse() {
      reverseCalls++;
    }
  }
}
