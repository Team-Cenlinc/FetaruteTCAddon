package org.fetarute.fetaruteTCAddon.dispatcher.signal;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.properties.TrainPropertiesStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainCartsRuntimeHandle;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainTagHelper;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.AuthorizationPurpose;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueEntry;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequestBuilder;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequestContext;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;

/**
 * 运行时调度请求提供者。
 *
 * <p>实现 {@link SignalEvaluator.TrainRequestProvider}，为信号评估器提供构建占用请求的能力。
 *
 * <p>职责：
 *
 * <ul>
 *   <li>根据列车名获取 TrainCarts 属性与线路定义
 *   <li>从 {@link RouteProgressRegistry} 读取推进点状态
 *   <li>使用 {@link OccupancyRequestBuilder} 构建占用请求
 *   <li>通过 {@link OccupancyQueueSupport} 查询等待特定资源的列车
 * </ul>
 *
 * <p>此类作为信号事件驱动系统与运行时调度的桥梁，由 {@link SignalEvaluator} 在资源释放时回调。事件链路只构建前向授权请求， 不携带 rear
 * guard；尾部保护由运行时 tick 获取，不能反向参与当前列车红灯判定。
 *
 * @see SignalEvaluator
 * @see SignalEvaluator.TrainRequestProvider
 */
public class RuntimeDispatchRequestProvider implements SignalEvaluator.TrainRequestProvider {

  private final RailGraphService railGraphService;
  private final RouteDefinitionCache routeDefinitions;
  private final RouteProgressRegistry progressRegistry;
  private final ConfigManager configManager;
  private final OccupancyManager occupancyManager;
  private final EventWaypointResolver effectiveWaypointsResolver;
  private final Consumer<String> debugLogger;

  /**
   * 事件信号请求的节点解析器。
   *
   * <p>EVENT 入口必须复用运行时已提交的 effective node 与 lastPassedGraphNode 视角，否则同一列车会出现 EVENT 从 route waypoint
   * 起算、PERIODIC 从中间图节点起算的快照分裂。
   */
  @FunctionalInterface
  public interface EventWaypointResolver {

    List<NodeId> resolve(
        String trainName, RouteDefinition route, int currentIndex, RailGraph graph);
  }

  /**
   * 构建请求提供者。
   *
   * @param railGraphService 调度图服务
   * @param routeDefinitions 线路定义缓存
   * @param progressRegistry 推进点注册表
   * @param configManager 配置管理器
   * @param occupancyManager 占用管理器（用于查询等待队列）
   * @param debugLogger 调试日志输出
   */
  public RuntimeDispatchRequestProvider(
      RailGraphService railGraphService,
      RouteDefinitionCache routeDefinitions,
      RouteProgressRegistry progressRegistry,
      ConfigManager configManager,
      OccupancyManager occupancyManager,
      Consumer<String> debugLogger) {
    this(
        railGraphService,
        routeDefinitions,
        progressRegistry,
        configManager,
        occupancyManager,
        (trainName, route) -> route == null ? List.of() : route.waypoints(),
        debugLogger);
  }

  /**
   * 构建请求提供者。
   *
   * <p>effectiveWaypointsResolver 由运行时调度服务提供，用于复用 DYNAMIC materialized node 覆盖，避免事件链路用原始 DYNAMIC
   * placeholder 做非 STOP 预判。
   */
  public RuntimeDispatchRequestProvider(
      RailGraphService railGraphService,
      RouteDefinitionCache routeDefinitions,
      RouteProgressRegistry progressRegistry,
      ConfigManager configManager,
      OccupancyManager occupancyManager,
      BiFunction<String, RouteDefinition, List<NodeId>> effectiveWaypointsResolver,
      Consumer<String> debugLogger) {
    this(
        railGraphService,
        routeDefinitions,
        progressRegistry,
        configManager,
        occupancyManager,
        effectiveWaypointsResolver == null
            ? null
            : (trainName, route, currentIndex, graph) ->
                effectiveWaypointsResolver.apply(trainName, route),
        debugLogger);
  }

  /**
   * 构建请求提供者。
   *
   * <p>该构造器允许事件链路在构建请求时拿到 currentIndex 与 RailGraph，从而复用 periodic signal tick 的
   * lastPassedGraphNode/current-node override 规则。
   */
  public RuntimeDispatchRequestProvider(
      RailGraphService railGraphService,
      RouteDefinitionCache routeDefinitions,
      RouteProgressRegistry progressRegistry,
      ConfigManager configManager,
      OccupancyManager occupancyManager,
      EventWaypointResolver effectiveWaypointsResolver,
      Consumer<String> debugLogger) {
    this.railGraphService = Objects.requireNonNull(railGraphService, "railGraphService");
    this.routeDefinitions = Objects.requireNonNull(routeDefinitions, "routeDefinitions");
    this.progressRegistry = Objects.requireNonNull(progressRegistry, "progressRegistry");
    this.configManager = Objects.requireNonNull(configManager, "configManager");
    this.occupancyManager = Objects.requireNonNull(occupancyManager, "occupancyManager");
    this.effectiveWaypointsResolver =
        effectiveWaypointsResolver != null
            ? effectiveWaypointsResolver
            : (trainName, route, currentIndex, graph) ->
                route == null ? List.of() : route.waypoints();
    this.debugLogger = debugLogger != null ? debugLogger : msg -> {};
  }

  @Override
  public Optional<OccupancyRequest> buildRequest(String trainName, Instant now) {
    if (trainName == null || trainName.isBlank()) {
      return Optional.empty();
    }
    TrainProperties properties = TrainPropertiesStore.get(trainName);
    if (properties == null) {
      return Optional.empty();
    }
    MinecartGroup group = properties.getHolder();
    if (group == null || !group.isValid()) {
      return Optional.empty();
    }
    // 非 FTA 管控列车不参与
    if (!isFtaManagedTrain(properties)) {
      return Optional.empty();
    }
    Optional<RouteDefinition> routeOpt = resolveRouteDefinition(properties);
    if (routeOpt.isEmpty()) {
      return Optional.empty();
    }
    RouteDefinition route = routeOpt.get();
    RouteProgressRegistry.RouteProgressEntry progressEntry =
        progressRegistry
            .get(trainName)
            .orElseGet(() -> progressRegistry.initFromTags(trainName, properties, route));
    int currentIndex = progressEntry.currentIndex();
    if (currentIndex < 0) {
      return Optional.empty();
    }
    TrainCartsRuntimeHandle train = new TrainCartsRuntimeHandle(group);
    UUID worldId = train.worldId();
    Optional<RailGraph> graphOpt = resolveGraph(worldId);
    if (graphOpt.isEmpty()) {
      return Optional.empty();
    }
    RailGraph graph = graphOpt.get();
    ConfigManager.RuntimeSettings runtimeSettings = configManager.current().runtimeSettings();
    int lookaheadEdges = runtimeSettings.lookaheadEdges();
    int minClearEdges = runtimeSettings.minClearEdges();
    int priority = resolvePriority(properties, route);

    OccupancyRequestBuilder builder =
        new OccupancyRequestBuilder(
            graph,
            lookaheadEdges,
            minClearEdges,
            0,
            runtimeSettings.switcherZoneEdges(),
            debugLogger);
    List<NodeId> waypoints = resolveWaypointsForRequest(trainName, route, currentIndex, graph);
    Optional<OccupancyRequestContext> contextOpt =
        builder.buildContextFromNodes(
            trainName,
            Optional.ofNullable(route.id()),
            waypoints,
            currentIndex,
            now,
            priority,
            AuthorizationPurpose.RUNTIME_MOVE);
    return contextOpt.map(context -> markEventRequest(context.request()));
  }

  private OccupancyRequest markEventRequest(OccupancyRequest request) {
    OccupancyRequest marked =
        request.withDirectedSource(SignalComputationTrace.Source.EVENT.name());
    if (occupancyManager instanceof SimpleOccupancyManager simple) {
      marked = marked.withDirectedOccupancyVersion(simple.version());
    }
    return marked.withDirectedProgressVersion(progressRegistry.version());
  }

  /**
   * 解析事件重评估使用的 waypoint 列表。
   *
   * <p>该方法单独暴露给同包测试，确保 provider 复用运行时 DYNAMIC effective node 覆盖，而不是回退到 route 原始 placeholder。
   */
  List<NodeId> resolveWaypointsForRequest(String trainName, RouteDefinition route) {
    return resolveWaypointsForRequest(trainName, route, -1, null);
  }

  /** 解析事件重评估使用的 waypoint 列表，并允许运行时按 lastPassedGraphNode 覆盖当前节点。 */
  List<NodeId> resolveWaypointsForRequest(
      String trainName, RouteDefinition route, int currentIndex, RailGraph graph) {
    if (route == null) {
      return List.of();
    }
    List<NodeId> waypoints =
        effectiveWaypointsResolver.resolve(trainName, route, currentIndex, graph);
    if (waypoints == null || waypoints.isEmpty()) {
      return route.waypoints();
    }
    return List.copyOf(waypoints);
  }

  /**
   * {@inheritDoc}
   *
   * <p>通过 {@link OccupancyQueueSupport#snapshotQueues()} 获取队列快照，遍历匹配的资源收集等待列车。
   */
  @Override
  public List<String> trainsWaitingFor(List<OccupancyResource> resources) {
    if (resources == null || resources.isEmpty()) {
      return List.of();
    }
    if (!(occupancyManager instanceof OccupancyQueueSupport queueSupport)) {
      return List.of();
    }
    Set<String> waiting = new HashSet<>();
    List<OccupancyQueueSnapshot> snapshots = queueSupport.snapshotQueues();
    Set<String> resourceKeys = new HashSet<>();
    for (OccupancyResource r : resources) {
      resourceKeys.add(r.key());
    }
    for (OccupancyQueueSnapshot snapshot : snapshots) {
      if (!resourceKeys.contains(snapshot.resource().key())) {
        continue;
      }
      for (OccupancyQueueEntry entry : snapshot.entries()) {
        waiting.add(entry.trainName());
      }
    }
    return new ArrayList<>(waiting);
  }

  private boolean isFtaManagedTrain(TrainProperties properties) {
    if (properties == null) {
      return false;
    }
    return TrainTagHelper.readTagValue(properties, "FTA_OPERATOR_CODE").isPresent()
        || TrainTagHelper.readTagValue(properties, "FTA_ROUTE_ID").isPresent();
  }

  private Optional<RouteDefinition> resolveRouteDefinition(TrainProperties properties) {
    // 优先从 FTA_ROUTE_ID 读取 UUID
    Optional<String> routeIdOpt = TrainTagHelper.readTagValue(properties, "FTA_ROUTE_ID");
    if (routeIdOpt.isPresent()) {
      try {
        UUID routeUuid = UUID.fromString(routeIdOpt.get());
        return routeDefinitions.findById(routeUuid);
      } catch (IllegalArgumentException ignored) {
        // 忽略无效 UUID
      }
    }
    // 回退到 OPERATOR/LINE/ROUTE code 组合
    Optional<String> opCode = TrainTagHelper.readTagValue(properties, "FTA_OPERATOR_CODE");
    Optional<String> lineCode = TrainTagHelper.readTagValue(properties, "FTA_LINE_CODE");
    Optional<String> routeCode = TrainTagHelper.readTagValue(properties, "FTA_ROUTE_CODE");
    if (opCode.isEmpty() || lineCode.isEmpty() || routeCode.isEmpty()) {
      return Optional.empty();
    }
    return routeDefinitions.findByCodes(opCode.get(), lineCode.get(), routeCode.get());
  }

  private int resolvePriority(TrainProperties properties, RouteDefinition route) {
    // 简单实现：从 tag 读取优先级，默认 0
    return TrainTagHelper.readIntTag(properties, "FTA_PRIORITY").orElse(0);
  }

  private Optional<RailGraph> resolveGraph(UUID worldId) {
    if (railGraphService == null || worldId == null) {
      return Optional.empty();
    }
    return railGraphService.getSnapshot(worldId).map(RailGraphService.RailGraphSnapshot::graph);
  }
}
