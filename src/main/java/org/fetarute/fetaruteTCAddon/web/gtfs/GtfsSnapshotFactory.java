package org.fetarute.fetaruteTCAddon.web.gtfs;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.fetarute.fetaruteTCAddon.company.api.StationDirectory;
import org.fetarute.fetaruteTCAddon.company.model.LineStatus;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.RunCurveModel;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableEdgeSpeeds;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableStop;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableTimingCalculator;

/**
 * 插件侧的数据源：把车站目录、交路缓存、时刻表服务与调度图的内存快照拼成 {@link GtfsSnapshot}。
 *
 * <p>只在 web 线程读各服务的只读快照，不读库、不调 Bukkit：
 *
 * <ul>
 *   <li>车站、线路、运营商、车站组：{@link StationDirectory#snapshot()}；
 *   <li>交路的线路归属与停靠表：{@link RouteDefinitionCache#entries()}（一次读，三者一致）；
 *   <li>时刻表与计划股道：{@link TimetableService} 的已发布表与内存计划表；
 *   <li>股道坐标：{@link RailGraphService#snapshotAll()}，只取 {@link NodeType#STATION}。
 *   <li>按间隔发车的交路：出车计划（{@link SpawnManager#snapshotPlan()}，本身线程安全）里有发车间隔、又不受时刻表管辖的交路；
 *       站间时分用编表同一个走行模型现算，按调度图版本与停靠表缓存。
 * </ul>
 *
 * <p>多个世界的方块坐标本来就重叠，股道按节点 ID 索引；同名节点以先读到的为准（节点 ID 在跨世界的路网里同样要求唯一）。
 */
public final class GtfsSnapshotFactory implements GtfsSource {

  private final Supplier<Optional<StationDirectory.Snapshot>> directory;
  private final Supplier<Optional<RouteDefinitionCache>> routes;
  private final Supplier<Optional<TimetableService>> timetables;
  private final Supplier<Optional<RailGraphService>> graphs;
  private final Supplier<Optional<String>> serverId;
  private final Supplier<ConfigManager.WebApiSettings> settings;
  private final Supplier<Optional<SpawnManager>> spawns;
  private final Supplier<Optional<ConfigManager.ConfigView>> config;
  private final Clock clock;
  private final TimetableTimingCalculator timingCalculator = new TimetableTimingCalculator();
  private final Map<UUID, CachedTiming> timings = new ConcurrentHashMap<>();

  /**
   * @param directory 车站目录快照
   * @param routes 交路缓存
   * @param timetables 时刻表服务
   * @param graphs 调度图服务（股道坐标）
   * @param serverId 本服服务器 ID（{@code server-id}）
   * @param settings 只读接口配置（发布方名称与地址）
   * @param spawns 出车管理（按间隔发车的交路与发车间隔）
   * @param config 当前配置（走行模型，与编表同一组参数）
   * @param clock 时钟（决定生成时刻与日历日期）
   */
  public GtfsSnapshotFactory(
      Supplier<Optional<StationDirectory.Snapshot>> directory,
      Supplier<Optional<RouteDefinitionCache>> routes,
      Supplier<Optional<TimetableService>> timetables,
      Supplier<Optional<RailGraphService>> graphs,
      Supplier<Optional<String>> serverId,
      Supplier<ConfigManager.WebApiSettings> settings,
      Supplier<Optional<SpawnManager>> spawns,
      Supplier<Optional<ConfigManager.ConfigView>> config,
      Clock clock) {
    this.directory = Objects.requireNonNull(directory, "directory");
    this.routes = Objects.requireNonNull(routes, "routes");
    this.timetables = Objects.requireNonNull(timetables, "timetables");
    this.graphs = Objects.requireNonNull(graphs, "graphs");
    this.serverId = Objects.requireNonNull(serverId, "serverId");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.spawns = Objects.requireNonNull(spawns, "spawns");
    this.config = Objects.requireNonNull(config, "config");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** 取当前车站目录、交路、时刻表与股道计划的一份快照；目录还没加载时为空。 */
  @Override
  public Optional<GtfsSnapshot> snapshot() {
    Optional<StationDirectory.Snapshot> catalog = directory.get();
    if (catalog.isEmpty() || catalog.get().revision() <= 0L) {
      return Optional.empty();
    }
    StationDirectory.Snapshot stationDirectory = catalog.get();
    ConfigManager.WebApiSettings current = settings.get();
    Optional<TimetableService> service = timetables.get();
    boolean timetableEnabled = service.isPresent() && service.get().settings().enabled();
    return Optional.of(
        new GtfsSnapshot(
            serverId.get(),
            clock.getZone(),
            current == null ? "" : current.publisherName(),
            current == null ? "" : current.publisherUrl(),
            clock.instant(),
            timetableEnabled,
            stationDirectory.operators(),
            stationDirectory.lines(),
            stationDirectory.stations(),
            stationDirectory.groups(),
            platforms(),
            timetableEnabled
                ? service.map(TimetableService::publishedTimetables).orElse(List.of())
                : List.of(),
            routes(),
            service
                .map(found -> (GtfsPlatformPlans) found::plannedPlatform)
                .orElseGet(GtfsPlatformPlans::none),
            stationsOfPublishedLines(stationDirectory),
            service.map(TimetableService::platformPlansVersion).orElse(0L),
            headwayRoutes(service)));
  }

  /**
   * 按间隔发车的交路：出车计划里有发车间隔、又不受时刻表管辖的交路。按表运行没启用时时刻表不约束发车，所有按间隔的交路都算。
   *
   * <p>同一交路有几个出车点（几个出车服务）时按频率合并：间隔 = 1 / Σ(1 / 各自间隔)。站间时分算不出来（交路不在缓存、调度图里走不通）的交路不发布。
   */
  private List<GtfsHeadwayRoute> headwayRoutes(Optional<TimetableService> timetables) {
    Optional<SpawnManager> spawnManager = spawns.get();
    if (spawnManager.isEmpty()) {
      return List.of();
    }
    Map<UUID, Double> frequency = new TreeMap<>();
    for (SpawnService service : spawnManager.get().snapshotPlan().services()) {
      if (service.baseHeadway().isEmpty()
          || timetables.map(found -> found.managed(service.routeId())).orElse(false)) {
        continue;
      }
      long seconds = service.baseHeadway().get().toSeconds();
      if (seconds > 0) {
        frequency.merge(service.routeId(), 1.0 / seconds, Double::sum);
      }
    }
    List<GtfsHeadwayRoute> out = new ArrayList<>();
    for (Map.Entry<UUID, Double> entry : frequency.entrySet()) {
      int headway = (int) Math.max(1L, Math.round(1.0 / entry.getValue()));
      timing(entry.getKey())
          .ifPresent(stops -> out.add(new GtfsHeadwayRoute(entry.getKey(), headway, stops)));
    }
    timings.keySet().retainAll(frequency.keySet());
    return out;
  }

  /** 交路跑一趟的站间时分；调度图版本、停靠表与配置都没变时用缓存。 */
  private Optional<List<TimetableStop>> timing(UUID routeId) {
    Optional<RouteDefinitionCache> cache = routes.get();
    Optional<RailGraphService> graphService = graphs.get();
    if (cache.isEmpty() || graphService.isEmpty()) {
      return Optional.empty();
    }
    Optional<RouteDefinition> definition = cache.get().findById(routeId);
    if (definition.isEmpty()) {
      return Optional.empty();
    }
    List<RouteStop> stops = cache.get().listStops(definition.get().id());
    RailGraphService railGraphs = graphService.get();
    long version = railGraphs.snapshotVersion();
    Optional<ConfigManager.ConfigView> view = config.get();
    CachedTiming cached = timings.get(routeId);
    if (cached != null
        && cached.graphVersion() == version
        && cached.stops() == stops
        && cached.config() == view.orElse(null)) {
      return cached.timing();
    }
    Optional<List<TimetableStop>> computed =
        railGraphs
            .findNetworkWorldForPath(definition.get().waypoints())
            .flatMap(
                worldId ->
                    railGraphs
                        .getNetworkSnapshot(worldId)
                        .map(
                            snapshot ->
                                timingCalculator.compute(
                                    snapshot.graph(),
                                    new RunCurveModel(
                                        RunCurveModel.Settings.forTimetable(view.orElse(null)),
                                        TimetableEdgeSpeeds.resolver(
                                            railGraphs.edgeOverrides(worldId))),
                                    definition.get(),
                                    stops,
                                    Duration.ofSeconds(RouteStop.DEFAULT_DWELL_SECONDS))))
            .filter(TimetableTimingCalculator.TimingResult::ok)
            .map(TimetableTimingCalculator.TimingResult::stops)
            .filter(result -> result.size() == definition.get().waypoints().size());
    timings.put(routeId, new CachedTiming(version, stops, view.orElse(null), computed));
    return computed;
  }

  /** 一条交路算好的站间时分，连同算它时的输入：调度图版本、停靠表（交路缓存每次刷新都换新列表）与配置对象（重载会换新对象）。 */
  private record CachedTiming(
      long graphVersion,
      List<RouteStop> stops,
      ConfigManager.ConfigView config,
      Optional<List<TimetableStop>> timing) {}

  /**
   * 有非规划线路经过的车站。
   *
   * <p>只按时刻表出车站会漏掉「有线路、没编表」的车站；设计稿 Q4 要求线路与车站照常出。停靠关系取自车站目录的停靠线路索引（含车站组换乘）， 只排除 PLANNING 线路：它们连
   * routes.txt 都不进。
   */
  private static Set<UUID> stationsOfPublishedLines(StationDirectory.Snapshot directory) {
    Set<UUID> stations = new LinkedHashSet<>();
    for (Map.Entry<UUID, List<StationDirectory.ServingLineEntry>> entry :
        directory.servingByStation().entrySet()) {
      for (StationDirectory.ServingLineEntry serving : entry.getValue()) {
        if (serving.line().line().status() != LineStatus.PLANNING) {
          stations.add(entry.getKey());
          break;
        }
      }
    }
    return stations;
  }

  /** 调度图里的车站股道：节点 ID → 坐标；同名节点只取一次。 */
  private List<GtfsPlatformNode> platforms() {
    Map<String, GtfsPlatformNode> byNode = new LinkedHashMap<>();
    graphs
        .get()
        .map(RailGraphService::snapshotAll)
        .orElse(Map.of())
        .values()
        .forEach(
            snapshot -> {
              for (RailNode node : snapshot.graph().nodes()) {
                if (node.type() != NodeType.STATION) {
                  continue;
                }
                byNode.putIfAbsent(
                    node.id().value(),
                    new GtfsPlatformNode(
                        node.id().value(),
                        node.worldPosition().getX(),
                        node.worldPosition().getZ()));
              }
            });
    return List.copyOf(byNode.values());
  }

  /** 交路的线路归属与停靠表。 */
  private Map<UUID, GtfsRouteRef> routes() {
    Optional<RouteDefinitionCache> cache = routes.get();
    if (cache.isEmpty()) {
      return Map.of();
    }
    Map<UUID, GtfsRouteRef> out = new LinkedHashMap<>();
    for (RouteDefinitionCache.RouteEntry entry : cache.get().entries()) {
      out.put(
          entry.routeId(),
          new GtfsRouteRef(
              entry.record().operator().code(),
              entry.record().line().code(),
              entry.record().route().code(),
              entry.stops()));
    }
    return out;
  }
}
