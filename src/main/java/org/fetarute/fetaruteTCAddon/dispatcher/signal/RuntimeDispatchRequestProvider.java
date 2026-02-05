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
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueEntry;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequestBuilder;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequestContext;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;

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
 * <p>此类作为信号事件驱动系统与运行时调度的桥梁，由 {@link SignalEvaluator} 在资源释放时回调。
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
  private final Consumer<String> debugLogger;

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
    this.railGraphService = Objects.requireNonNull(railGraphService, "railGraphService");
    this.routeDefinitions = Objects.requireNonNull(routeDefinitions, "routeDefinitions");
    this.progressRegistry = Objects.requireNonNull(progressRegistry, "progressRegistry");
    this.configManager = Objects.requireNonNull(configManager, "configManager");
    this.occupancyManager = Objects.requireNonNull(occupancyManager, "occupancyManager");
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
        progressRegistry.get(trainName).orElse(null);
    if (progressEntry == null) {
      return Optional.empty();
    }
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
    int rearGuardEdges = runtimeSettings.rearGuardEdges();
    int priority = resolvePriority(properties, route);

    OccupancyRequestBuilder builder =
        new OccupancyRequestBuilder(
            graph,
            lookaheadEdges,
            minClearEdges,
            rearGuardEdges,
            runtimeSettings.switcherZoneEdges(),
            debugLogger);
    List<NodeId> waypoints = route.waypoints();
    Optional<OccupancyRequestContext> contextOpt =
        builder.buildContextFromNodes(
            trainName, Optional.ofNullable(route.id()), waypoints, currentIndex, now, priority);
    return contextOpt.map(OccupancyRequestContext::request);
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
