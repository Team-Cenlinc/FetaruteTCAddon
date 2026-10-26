package org.fetarute.fetaruteTCAddon.web.gtfs;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.fetarute.fetaruteTCAddon.company.api.StationDirectory;
import org.fetarute.fetaruteTCAddon.company.model.LineStatus;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableService;

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
  private final Clock clock;

  /**
   * @param directory 车站目录快照
   * @param routes 交路缓存
   * @param timetables 时刻表服务
   * @param graphs 调度图服务（股道坐标）
   * @param serverId 本服服务器 ID（{@code server-id}）
   * @param settings 只读接口配置（发布方名称与地址）
   * @param clock 时钟（决定生成时刻与日历日期）
   */
  public GtfsSnapshotFactory(
      Supplier<Optional<StationDirectory.Snapshot>> directory,
      Supplier<Optional<RouteDefinitionCache>> routes,
      Supplier<Optional<TimetableService>> timetables,
      Supplier<Optional<RailGraphService>> graphs,
      Supplier<Optional<String>> serverId,
      Supplier<ConfigManager.WebApiSettings> settings,
      Clock clock) {
    this.directory = Objects.requireNonNull(directory, "directory");
    this.routes = Objects.requireNonNull(routes, "routes");
    this.timetables = Objects.requireNonNull(timetables, "timetables");
    this.graphs = Objects.requireNonNull(graphs, "graphs");
    this.serverId = Objects.requireNonNull(serverId, "serverId");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

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
            service.map(TimetableService::platformPlansVersion).orElse(0L)));
  }

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
