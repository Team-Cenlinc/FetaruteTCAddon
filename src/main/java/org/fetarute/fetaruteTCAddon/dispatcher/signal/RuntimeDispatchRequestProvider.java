package org.fetarute.fetaruteTCAddon.dispatcher.signal;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.properties.TrainPropertiesStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.DispatchPriorityResolution;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.DispatchPriorityResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService;
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
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceIntent;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceKind;
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
 *   <li>通过 {@link OccupancyQueueSupport} 与进度快照查询等待特定资源的列车
 * </ul>
 *
 * <p>此类作为信号事件驱动系统与运行时调度的桥梁，由 {@link SignalEvaluator} 在资源释放时回调。事件链路与周期调度使用同构窗口参数构建 preview
 * 请求；是否发布更宽松信号仍由周期调度链路复核。
 *
 * @see SignalEvaluator
 * @see SignalEvaluator.TrainRequestProvider
 */
public class RuntimeDispatchRequestProvider implements SignalEvaluator.TrainRequestProvider {

  private static final int DISTANCE_LOOKAHEAD_MAX_EDGE_MULTIPLIER = 4;
  private static final int DISTANCE_LOOKAHEAD_MIN_MAX_EDGES = 12;
  private static final int DISTANCE_LOOKAHEAD_ABSOLUTE_MAX_EDGES = 24;

  private final RailGraphService railGraphService;
  private final RouteDefinitionCache routeDefinitions;
  private final RouteProgressRegistry progressRegistry;
  private final ConfigManager configManager;
  private final OccupancyManager occupancyManager;
  private final EventWaypointResolver effectiveWaypointsResolver;
  private final DispatchPriorityResolver dispatchPriorityResolver;
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
        null,
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
    this(
        railGraphService,
        routeDefinitions,
        progressRegistry,
        configManager,
        occupancyManager,
        effectiveWaypointsResolver,
        null,
        debugLogger);
  }

  /**
   * 构建请求提供者，并显式注入运行时共用的 priority resolver。
   *
   * <p>生产链路应传入 {@link RuntimeDispatchService} 持有的同一个 resolver，确保资源释放后的事件重评估与 periodic tick 使用一致的
   * route operation / priority 语义。
   */
  public RuntimeDispatchRequestProvider(
      RailGraphService railGraphService,
      RouteDefinitionCache routeDefinitions,
      RouteProgressRegistry progressRegistry,
      ConfigManager configManager,
      OccupancyManager occupancyManager,
      EventWaypointResolver effectiveWaypointsResolver,
      DispatchPriorityResolver dispatchPriorityResolver,
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
    this.dispatchPriorityResolver =
        dispatchPriorityResolver != null
            ? dispatchPriorityResolver
            : new DispatchPriorityResolver(
                null, routeDefinitions, progressRegistry, this.debugLogger);
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
    TrainCartsRuntimeHandle train = new TrainCartsRuntimeHandle(group);
    return buildRequestFromProperties(trainName, properties, train.worldId(), now);
  }

  /**
   * 基于已解析的 TrainProperties 与世界 ID 构建请求。
   *
   * <p>该方法刻意保持同包可见，供单元测试绕开 TrainCarts 实体静态初始化；生产入口仍必须先经过 {@link #buildRequest(String, Instant)}
   * 的列车存在性与有效性检查。
   */
  Optional<OccupancyRequest> buildRequestFromProperties(
      String trainName, TrainProperties properties, UUID worldId, Instant now) {
    if (trainName == null || trainName.isBlank() || properties == null || worldId == null) {
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
    Optional<RailGraph> graphOpt = resolveGraph(worldId);
    if (graphOpt.isEmpty()) {
      return Optional.empty();
    }
    RailGraph graph = graphOpt.get();
    ConfigManager.RuntimeSettings runtimeSettings = configManager.current().runtimeSettings();
    int lookaheadEdges = runtimeSettings.lookaheadEdges();
    int minClearEdges = runtimeSettings.minClearEdges();
    DispatchPriorityResolution priorityResolution =
        dispatchPriorityResolver.resolve(
            "event-request-provider", trainName, properties, route, Optional.of(progressEntry));
    int priority = priorityResolution.priority();
    traceDispatchRequestContext(
        "event-request-provider", trainName, route, currentIndex, priorityResolution);

    // 与 periodic signal tick 使用同一组窗口参数（含 rear-guard），保证资源释放后的事件重评估
    // 与周期路径基于同构请求上下文计算 preview。
    OccupancyRequestBuilder builder =
        new OccupancyRequestBuilder(
            graph,
            lookaheadEdges,
            minClearEdges,
            runtimeSettings.rearGuardEdges(),
            runtimeSettings.switcherZoneEdges(),
            runtimeLookaheadMinDistanceBlocks(runtimeSettings),
            runtimeLookaheadMaxEdges(runtimeSettings),
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
    return contextOpt.map(
        context -> {
          OccupancyRequest request = markEventRequest(context.request());
          traceSignalAuthorityLifecycle(
              "event-request-provider",
              trainName,
              route,
              currentIndex,
              request,
              priorityResolution,
              "-",
              "-",
              "-");
          return request;
        });
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
   * <p>通过 {@link OccupancyQueueSupport#snapshotQueues()} 获取队列快照，遍历匹配的资源收集等待列车；对 NODE/EDGE 释放事件，额外从
   * route progress 中找前向窗口命中的候选，避免同线跟驰车只能等下一次周期 tick。
   */
  @Override
  public List<String> trainsWaitingFor(List<OccupancyResource> resources) {
    if (resources == null || resources.isEmpty()) {
      return List.of();
    }
    Set<String> waiting = new HashSet<>();
    Set<String> resourceKeys = new HashSet<>();
    for (OccupancyResource r : resources) {
      resourceKeys.add(r.key());
    }
    if (occupancyManager instanceof OccupancyQueueSupport queueSupport) {
      List<OccupancyQueueSnapshot> snapshots = queueSupport.snapshotQueues();
      for (OccupancyQueueSnapshot snapshot : snapshots) {
        if (!resourceKeys.contains(snapshot.resource().key())) {
          continue;
        }
        for (OccupancyQueueEntry entry : snapshot.entries()) {
          waiting.add(entry.trainName());
        }
      }
    }
    collectForwardRouteWakeups(resources, waiting);
    return new ArrayList<>(waiting);
  }

  private void collectForwardRouteWakeups(List<OccupancyResource> resources, Set<String> waiting) {
    if (progressRegistry == null || routeDefinitions == null || resources == null) {
      return;
    }
    Set<OccupancyResource> resourceSet = new HashSet<>(resources);
    if (resourceSet.stream()
        .noneMatch(
            resource ->
                resource.kind() == ResourceKind.NODE || resource.kind() == ResourceKind.EDGE)) {
      return;
    }
    ConfigManager.RuntimeSettings runtimeSettings =
        configManager == null || configManager.current() == null
            ? null
            : configManager.current().runtimeSettings();
    int edgeLimit = runtimeLookaheadMaxEdges(runtimeSettings);
    Map<String, RouteProgressRegistry.RouteProgressEntry> progressSnapshot =
        progressRegistry.snapshot();
    if (progressSnapshot == null || progressSnapshot.isEmpty()) {
      return;
    }
    for (RouteProgressRegistry.RouteProgressEntry entry : progressSnapshot.values()) {
      if (entry == null) {
        continue;
      }
      Optional<RouteDefinition> routeOpt = resolveRouteDefinition(entry);
      if (routeOpt.isEmpty()
          || routeResourcesIntersect(routeOpt.get(), entry.currentIndex(), edgeLimit, resourceSet)
              .isEmpty()) {
        continue;
      }
      waiting.add(entry.trainName());
    }
  }

  private Optional<OccupancyResource> routeResourcesIntersect(
      RouteDefinition route,
      int currentIndex,
      int edgeLimit,
      Set<OccupancyResource> releasedResources) {
    if (route == null || releasedResources == null || releasedResources.isEmpty()) {
      return Optional.empty();
    }
    List<NodeId> nodes = route.waypoints();
    if (nodes == null || nodes.isEmpty() || currentIndex < 0 || currentIndex >= nodes.size()) {
      return Optional.empty();
    }
    int maxEdges = Math.max(1, edgeLimit);
    int end = Math.min(nodes.size() - 1, currentIndex + maxEdges);
    for (int i = currentIndex; i <= end; i++) {
      NodeId node = nodes.get(i);
      if (node != null) {
        OccupancyResource nodeResource = OccupancyResource.forNode(node);
        if (releasedResources.contains(nodeResource)) {
          return Optional.of(nodeResource);
        }
      }
      if (i < end) {
        NodeId next = nodes.get(i + 1);
        if (node != null && next != null) {
          OccupancyResource edgeResource = OccupancyResource.forEdge(EdgeId.undirected(node, next));
          if (releasedResources.contains(edgeResource)) {
            return Optional.of(edgeResource);
          }
        }
      }
    }
    return Optional.empty();
  }

  private Optional<RouteDefinition> resolveRouteDefinition(
      RouteProgressRegistry.RouteProgressEntry entry) {
    if (entry == null) {
      return Optional.empty();
    }
    if (entry.routeUuid() != null) {
      Optional<RouteDefinition> byUuid = routeDefinitions.findById(entry.routeUuid());
      if (byUuid.isPresent()) {
        return byUuid;
      }
    }
    Map<UUID, RouteDefinition> routeSnapshot = routeDefinitions.snapshot();
    if (routeSnapshot == null || routeSnapshot.isEmpty()) {
      return Optional.empty();
    }
    return routeSnapshot.values().stream()
        .filter(route -> route != null && route.id().equals(entry.routeId()))
        .findFirst();
  }

  private static long runtimeLookaheadMinDistanceBlocks(
      ConfigManager.RuntimeSettings runtimeSettings) {
    if (runtimeSettings == null) {
      return 0L;
    }
    double cautionMargin = Math.max(0.0, runtimeSettings.movementAuthorityCautionMarginBlocks());
    int followingMin = Math.max(0, runtimeSettings.followingMinClearBlocks());
    int followingStop = Math.max(0, runtimeSettings.followingStopMarginBlocks());
    return Math.max(followingMin, (long) Math.ceil(followingStop + cautionMargin));
  }

  private static int runtimeLookaheadMaxEdges(ConfigManager.RuntimeSettings runtimeSettings) {
    if (runtimeSettings == null) {
      return 0;
    }
    int minEdges = Math.max(runtimeSettings.lookaheadEdges(), runtimeSettings.minClearEdges());
    int preferredMax =
        Math.max(
            DISTANCE_LOOKAHEAD_MIN_MAX_EDGES, minEdges * DISTANCE_LOOKAHEAD_MAX_EDGE_MULTIPLIER);
    return Math.max(minEdges, Math.min(DISTANCE_LOOKAHEAD_ABSOLUTE_MAX_EDGES, preferredMax));
  }

  private boolean isFtaManagedTrain(TrainProperties properties) {
    if (properties == null) {
      return false;
    }
    return readFirstTag(properties, RouteProgressRegistry.TAG_OPERATOR_CODE, "FTA_OPERATOR")
            .isPresent()
        || readFirstTag(properties, RouteProgressRegistry.TAG_LINE_CODE, "FTA_LINE").isPresent()
        || readFirstTag(properties, RouteProgressRegistry.TAG_ROUTE_CODE, "FTA_ROUTE").isPresent()
        || TrainTagHelper.readTagValue(properties, RouteProgressRegistry.TAG_ROUTE_ID).isPresent();
  }

  private Optional<RouteDefinition> resolveRouteDefinition(TrainProperties properties) {
    // 优先从 FTA_ROUTE_ID 读取 UUID
    Optional<String> routeIdOpt =
        TrainTagHelper.readTagValue(properties, RouteProgressRegistry.TAG_ROUTE_ID);
    if (routeIdOpt.isPresent()) {
      try {
        UUID routeUuid = UUID.fromString(routeIdOpt.get());
        return routeDefinitions.findById(routeUuid);
      } catch (IllegalArgumentException ignored) {
        // 忽略无效 UUID
      }
    }
    // 回退到 OPERATOR/LINE/ROUTE code 组合
    Optional<String> opCode =
        readFirstTag(properties, RouteProgressRegistry.TAG_OPERATOR_CODE, "FTA_OPERATOR");
    Optional<String> lineCode =
        readFirstTag(properties, RouteProgressRegistry.TAG_LINE_CODE, "FTA_LINE");
    Optional<String> routeCode =
        readFirstTag(properties, RouteProgressRegistry.TAG_ROUTE_CODE, "FTA_ROUTE");
    if (opCode.isEmpty() || lineCode.isEmpty() || routeCode.isEmpty()) {
      return Optional.empty();
    }
    return routeDefinitions.findByCodes(opCode.get(), lineCode.get(), routeCode.get());
  }

  private void traceDispatchRequestContext(
      String context,
      String trainName,
      RouteDefinition route,
      int currentIndex,
      DispatchPriorityResolution priorityResolution) {
    if (priorityResolution == null) {
      return;
    }
    SignalComputationTrace.emitRaw(
        "SMART_DISPATCH_REQUEST_CONTEXT context="
            + safe(context)
            + " train="
            + safe(trainName)
            + " route="
            + (route == null || route.id() == null ? "-" : safe(route.id().value()))
            + " index="
            + currentIndex
            + " priority="
            + priorityResolution.priority()
            + " prioritySource="
            + priorityResolution.source()
            + " operationType="
            + priorityResolution.operationType().map(Enum::name).orElse("-")
            + " fallbackReason="
            + priorityResolution.fallbackReason(),
        debugLogger);
  }

  private void traceSignalAuthorityLifecycle(
      String source,
      String trainName,
      RouteDefinition route,
      int currentIndex,
      OccupancyRequest request,
      DispatchPriorityResolution priorityResolution,
      String computedAspect,
      String publishedAspect,
      String publishSuppressed) {
    SignalComputationTrace.emitRaw(
        "SMART_SIGNAL_AUTHORITY_LIFECYCLE source="
            + safe(source)
            + " train="
            + safe(trainName)
            + " requestId="
            + requestIdOf(request)
            + " route="
            + (route == null || route.id() == null ? "-" : safe(route.id().value()))
            + " index="
            + currentIndex
            + " currentNode="
            + directedCurrentNode(request)
            + " effectiveTo="
            + directedEffectiveTo(request)
            + " priority="
            + (request == null ? "-" : request.priority())
            + " resolvedPriority="
            + (priorityResolution == null ? "-" : priorityResolution.priority())
            + " prioritySource="
            + (priorityResolution == null ? "-" : priorityResolution.source())
            + " operationType="
            + (priorityResolution == null
                ? "-"
                : priorityResolution.operationType().map(Enum::name).orElse("-"))
            + " requestInputType="
            + (request == null ? "-" : SignalDecisionInputClassifier.classify(request))
            + " requestResourceCount="
            + (request == null ? 0 : request.resourceList().size())
            + " movementRequiredResources="
            + countResourcesByIntent(request, ResourceIntent.MOVEMENT_REQUIRED)
            + " protectiveRetainResources="
            + countResourcesByIntent(request, ResourceIntent.PROTECTIVE_RETAIN)
            + " holdOnlyResources="
            + countResourcesByIntent(request, ResourceIntent.HOLD_ONLY)
            + " queuePositionResources="
            + countResourcesByIntent(request, ResourceIntent.QUEUE_POSITION)
            + " oldSelfClaims="
            + countSelfClaims(trainName)
            + " computedAspect="
            + safe(computedAspect)
            + " publishedAspect="
            + safe(publishedAspect)
            + " publishSuppressed="
            + safe(publishSuppressed),
        debugLogger);
  }

  private static int countResourcesByIntent(OccupancyRequest request, ResourceIntent intent) {
    if (request == null || intent == null) {
      return 0;
    }
    int count = 0;
    for (OccupancyResource resource : request.resourceList()) {
      if (request.intentFor(resource) == intent) {
        count++;
      }
    }
    return count;
  }

  private int countSelfClaims(String trainName) {
    if (trainName == null || trainName.isBlank() || occupancyManager == null) {
      return 0;
    }
    int count = 0;
    for (var claim : occupancyManager.snapshotClaims()) {
      if (claim != null
          && claim.trainName() != null
          && claim.trainName().equalsIgnoreCase(trainName)) {
        count++;
      }
    }
    return count;
  }

  private static String requestIdOf(OccupancyRequest request) {
    if (request == null) {
      return "-";
    }
    return request.directedContext().map(context -> context.requestId()).orElse("-");
  }

  private static String directedCurrentNode(OccupancyRequest request) {
    if (request == null) {
      return "-";
    }
    return request
        .directedContext()
        .flatMap(context -> context.currentNode())
        .map(NodeId::value)
        .orElse("-");
  }

  private static String directedEffectiveTo(OccupancyRequest request) {
    if (request == null) {
      return "-";
    }
    return request
        .directedContext()
        .flatMap(context -> context.effectiveToNode())
        .map(NodeId::value)
        .orElse("-");
  }

  private static Optional<String> readFirstTag(
      TrainProperties properties, String primaryKey, String fallbackKey) {
    Optional<String> primary = TrainTagHelper.readTagValue(properties, primaryKey);
    return primary.isPresent() ? primary : TrainTagHelper.readTagValue(properties, fallbackKey);
  }

  private static String safe(String value) {
    return value == null || value.isBlank() ? "-" : value.trim();
  }

  private Optional<RailGraph> resolveGraph(UUID worldId) {
    if (railGraphService == null || worldId == null) {
      return Optional.empty();
    }
    return railGraphService.getSnapshot(worldId).map(RailGraphService.RailGraphSnapshot::graph);
  }
}
