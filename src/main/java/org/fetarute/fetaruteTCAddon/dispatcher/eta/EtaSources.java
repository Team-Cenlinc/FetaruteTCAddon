package org.fetarute.fetaruteTCAddon.dispatcher.eta;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.fetarute.fetaruteTCAddon.company.api.StationDirectory;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainRuntimeSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainSnapshotStore;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLineChanges;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeStopState;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfig;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.DutyContinuitySupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnTicket;

/**
 * {@link EtaCalculator} 读取的外部事实。
 *
 * <p>两种来源：实时来源由 {@link EtaService} 直接接到它的钩子上，钩子背后是调度、时刻表、待命等运行时注册表，由调用线程现读（站台屏在主线程，web-api 在后台线程）；
 * 冻结来源是 {@link EtaBoardInput} 在调用线程上拷好的副本，可以交给后台线程。计算只经由这里读运行时状态；调度图快照与限速覆盖由 {@link
 * org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService} 直接读（并发容器，图快照不可变）。
 *
 * @param routes 交路定义、停靠表与归属
 * @param trains 列车快照
 * @param stopStates 列车名 → 当前 STOP 生命周期；未接入时为 null
 * @param plannedDepartures (列车名, 停靠序号) → 计划发车；未接入时为 null
 * @param plannedHoldCap 计划扣留上限；不扣留时为零
 * @param plannedArrivals (列车名, 停靠序号) → 计划到达；未接入时为 null
 * @param effectiveWaypoints (列车名, 交路) → 实际节点；未接入时为 null
 * @param placedStops 已定站台的记录；未接入时为 null
 * @param plannedPlatforms 运行中列车的计划或暂定站台；未接入时为 null
 * @param ticketPlatforms 还没派出的票预先指定的站台；未接入时为 null
 * @param atStation 列车是否已停在本站；未接入时为 null
 * @param lineCanonicalizer 线路代码的规范写法
 * @param stationLookup 车站 ID → 车站与所属运营商
 * @param config 当前配置；未接入或读取失败时为 null
 * @param ticketTrainConfigs 未发车票据的车型
 * @param tickets 待发票据与预测票据
 * @param continuity 按交路运行时的前后衔接；排班器不提供时为 null
 * @param layovers 待命列车；未接入时为 null
 * @param throughRoutes 回程支线交路 → 联挂后接着跑的主干；不联挂时为空
 * @param divisionRemainders 分行后留下、不再载客的那一段停在本站
 */
record EtaSources(
    Routes routes,
    Trains trains,
    Function<String, Optional<RuntimeStopState>> stopStates,
    BiFunction<String, Integer, Optional<Instant>> plannedDepartures,
    Duration plannedHoldCap,
    BiFunction<String, Integer, Optional<Instant>> plannedArrivals,
    BiFunction<String, RouteDefinition, List<NodeId>> effectiveWaypoints,
    EtaService.PlacedStops placedStops,
    EtaService.PlannedPlatforms plannedPlatforms,
    EtaService.TicketPlatforms ticketPlatforms,
    AtStation atStation,
    UnaryOperator<RouteLineChanges.LineRef> lineCanonicalizer,
    Function<UUID, Optional<StationDirectory.StationEntry>> stationLookup,
    ConfigManager.ConfigView config,
    TicketTrainConfigs ticketTrainConfigs,
    Tickets tickets,
    DutyContinuitySupport continuity,
    Layovers layovers,
    Function<UUID, Optional<EtaService.ThroughRoute>> throughRoutes,
    EtaService.DivisionRemainders divisionRemainders,
    EtaService.SkippedStops skippedStops) {

  EtaSources {
    Objects.requireNonNull(routes, "routes");
    Objects.requireNonNull(trains, "trains");
    plannedHoldCap = plannedHoldCap == null ? Duration.ZERO : plannedHoldCap;
    Objects.requireNonNull(lineCanonicalizer, "lineCanonicalizer");
    Objects.requireNonNull(stationLookup, "stationLookup");
    Objects.requireNonNull(ticketTrainConfigs, "ticketTrainConfigs");
    Objects.requireNonNull(tickets, "tickets");
    throughRoutes = throughRoutes == null ? routeId -> Optional.empty() : throughRoutes;
    divisionRemainders =
        divisionRemainders == null ? (train, route, index) -> false : divisionRemainders;
    skippedStops = skippedStops == null ? (train, route, index) -> false : skippedStops;
  }

  /** 交路定义、停靠表与归属。 */
  interface Routes {

    /** 交路定义；{@code routeId} 不能为 null。 */
    Optional<RouteDefinition> findById(UUID routeId);

    /** 与 waypoints 对齐的停靠表；没有时为空列表。 */
    List<RouteStop> listStops(RouteId routeId);

    /** 交路所属的运营商、线路与交路实体。 */
    Optional<RouteDefinitionCache.RouteRecord> findRecord(UUID routeId);

    /** 直接读交路缓存（缓存本身是并发容器）。 */
    static Routes of(RouteDefinitionCache cache) {
      Objects.requireNonNull(cache, "cache");
      return new Routes() {
        @Override
        public Optional<RouteDefinition> findById(UUID routeId) {
          return cache.findById(routeId);
        }

        @Override
        public List<RouteStop> listStops(RouteId routeId) {
          return cache.listStops(routeId);
        }

        @Override
        public Optional<RouteDefinitionCache.RouteRecord> findRecord(UUID routeId) {
          return cache.findRecord(routeId);
        }
      };
    }
  }

  /** 列车快照。 */
  interface Trains {

    /** 列车名为空或没有快照时为空。 */
    Optional<TrainRuntimeSnapshot> get(String trainName);

    /** 全部列车快照（不可变）。 */
    Map<String, TrainRuntimeSnapshot> all();

    /** 直接读快照存储（并发容器，快照本身不可变）。 */
    static Trains of(TrainSnapshotStore store) {
      Objects.requireNonNull(store, "store");
      return new Trains() {
        @Override
        public Optional<TrainRuntimeSnapshot> get(String trainName) {
          return store.getSnapshot(trainName);
        }

        @Override
        public Map<String, TrainRuntimeSnapshot> all() {
          return store.snapshot();
        }
      };
    }
  }

  /** 列车是否已停在本站（到站后、停站计时注册前的空档按计划停站计）。 */
  @FunctionalInterface
  interface AtStation {

    /**
     * @param trainName 列车名
     * @param routeKey 交路代码
     * @param routeIndex 列车当前进度下标
     */
    boolean at(String trainName, String routeKey, int routeIndex);
  }

  /** 未发车票据的车型（加减速与最高速度）。 */
  @FunctionalInterface
  interface TicketTrainConfigs {

    /**
     * @param routeUuid 交路
     * @param consist 票上指定的车型
     * @param config 当前配置
     * @param now 当前时刻
     */
    TrainConfig resolve(
        UUID routeUuid, Optional<String> consist, ConfigManager.ConfigView config, Instant now);
  }

  /** 待发票据与预测票据。 */
  interface Tickets {

    /** 已生成、尚未派出的票据（按 id 去重）。 */
    List<SpawnTicket> pending();

    /**
     * 时间窗内的预测票据。
     *
     * @return 排班器不提供预测时为空
     */
    Optional<List<SpawnTicket>> forecast(Instant now, Duration window, int maxPerService);
  }

  /** 待命列车。 */
  interface Layovers {

    /** 全部待命候选。 */
    List<LayoverRegistry.LayoverCandidate> all();

    /** 按列车名找待命候选。 */
    Optional<LayoverRegistry.LayoverCandidate> get(String trainName);

    /** 指定终到站的待命候选，按就绪时刻升序。 */
    List<LayoverRegistry.LayoverCandidate> candidatesAt(String terminalKey);

    /** 直接读待命登记。 */
    static Layovers of(LayoverRegistry registry) {
      Objects.requireNonNull(registry, "registry");
      return new Layovers() {
        @Override
        public List<LayoverRegistry.LayoverCandidate> all() {
          return registry.snapshot();
        }

        @Override
        public Optional<LayoverRegistry.LayoverCandidate> get(String trainName) {
          return registry.get(trainName);
        }

        @Override
        public List<LayoverRegistry.LayoverCandidate> candidatesAt(String terminalKey) {
          return registry.findCandidates(terminalKey);
        }
      };
    }
  }
}
