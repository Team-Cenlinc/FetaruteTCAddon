package org.fetarute.fetaruteTCAddon.web.gtfs.realtime;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.fetarute.fetaruteTCAddon.api.FetaruteApi;
import org.fetarute.fetaruteTCAddon.api.train.TrainApi;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaService;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainRuntimeSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainSnapshotStore;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteTerminals;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableService;
import org.fetarute.fetaruteTCAddon.display.pids.PidsService;
import org.fetarute.fetaruteTCAddon.display.pids.bulletin.PidsBulletin;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsLineStatus;

/**
 * 插件侧的实时数据源：把公开 API 的车次绑定、取消登记，运行时列车快照、站台屏公告与线路运行状况拼成 {@link GtfsRealtimeSnapshot}。
 *
 * <p>全部读内存快照：车次绑定在 tick 内缓存、列车快照是并发安全的拷贝、公告板是并发 Map、线路状况是站台屏的汇总。可在 web 线程调用。
 *
 * <p>取消登记取发布前后各 3 小时：更早的取消对行程规划已经没有意义，更远期的取消还没进登记。
 */
public final class GtfsRealtimeSnapshotFactory implements GtfsRealtimeSource {

  /** 取消登记窗口：现在前后各 3 小时。 */
  private static final Duration CANCELLATION_WINDOW = Duration.ofHours(3);

  private final Supplier<Optional<FetaruteApi>> api;
  private final Supplier<Optional<TrainSnapshotStore>> trains;
  private final Supplier<Optional<PidsService>> pids;
  private final Supplier<Optional<TimetableService>> timetables;
  private final Supplier<Optional<EtaService>> eta;
  private final Supplier<Optional<RouteDefinitionCache>> routes;

  /**
   * @param api 公开 API（车次绑定、取消、在线列车状态）
   * @param trains 运行时列车快照（位置、速度、载客）
   * @param pids 站台屏服务（公告与线路运行状况）
   * @param timetables 时刻表服务（越站查询）
   * @param eta 到站预测服务（已选定的实际股道；与站牌同一口径，可在任意线程调用）
   * @param routes 交路缓存（出库途中的判定读停靠表）
   */
  public GtfsRealtimeSnapshotFactory(
      Supplier<Optional<FetaruteApi>> api,
      Supplier<Optional<TrainSnapshotStore>> trains,
      Supplier<Optional<PidsService>> pids,
      Supplier<Optional<TimetableService>> timetables,
      Supplier<Optional<EtaService>> eta,
      Supplier<Optional<RouteDefinitionCache>> routes) {
    this.api = Objects.requireNonNull(api, "api");
    this.trains = Objects.requireNonNull(trains, "trains");
    this.pids = Objects.requireNonNull(pids, "pids");
    this.timetables = Objects.requireNonNull(timetables, "timetables");
    this.eta = Objects.requireNonNull(eta, "eta");
    this.routes = Objects.requireNonNull(routes, "routes");
  }

  /** 取当前车次绑定、取消登记、列车位置与站台屏公告的一份快照；公开 API 还没初始化时为空。 */
  @Override
  public Optional<GtfsRealtimeSnapshot> snapshot(Instant now) {
    Optional<FetaruteApi> current = api.get();
    if (current.isEmpty()) {
      return Optional.empty();
    }
    FetaruteApi api = current.get();
    Map<String, TrainRuntimeSnapshot> running =
        trains.get().map(TrainSnapshotStore::snapshot).orElse(Map.of());
    Set<String> outOfService =
        api.trains().listAllActiveTrains(false).stream()
            .filter(TrainApi.TrainSnapshot::outOfService)
            .map(TrainApi.TrainSnapshot::trainName)
            .collect(Collectors.toUnmodifiableSet());
    Set<String> beforeService = beforeService(running, this::routeStops);
    Optional<PidsService> pidsService = pids.get();
    List<PidsBulletin> bulletins =
        pidsService.map(service -> service.bulletins().all()).orElse(List.of());
    BiFunction<String, String, Optional<PidsLineStatus>> lineStatus =
        (operator, line) -> pidsService.flatMap(service -> service.lineStatus(operator, line));
    Optional<TimetableService> timetableService = timetables.get();
    Optional<EtaService> etaService = eta.get();
    return Optional.of(
        new GtfsRealtimeSnapshot(
            now,
            List.copyOf(api.timetables().listAssignments()),
            api.timetables()
                .cancellations(now.minus(CANCELLATION_WINDOW), now.plus(CANCELLATION_WINDOW)),
            running,
            outOfService,
            beforeService,
            bulletins,
            lineStatus,
            (timetableId, tripId, serviceDate) ->
                timetableService
                    .map(service -> service.skippedStops(timetableId, tripId, serviceDate))
                    .orElse(Set.of()),
            (trainName, stopIndex) ->
                etaService
                    .flatMap(service -> service.placedStopNode(trainName, stopIndex))
                    .map(NodeId::value)));
  }

  /** 交路的停靠表（与进度下标对齐）；交路缓存没起来或交路不在缓存里时为空。 */
  private Optional<List<RouteStop>> routeStops(UUID routeId) {
    return routes
        .get()
        .flatMap(cache -> cache.findById(routeId).map(route -> cache.listStops(route.id())));
  }

  /**
   * 还没到当前交路第一个载客站的车（出库途中），口径见 {@link RouteTerminals#beforeService}。找不到交路时不排除：宁可多报一辆车，也不凭空藏掉。
   *
   * @param trains 运行时列车快照（列车名 → 快照）
   * @param stopsOf 交路 UUID → 停靠表（与进度下标对齐）
   * @return 列车名
   */
  static Set<String> beforeService(
      Map<String, TrainRuntimeSnapshot> trains, Function<UUID, Optional<List<RouteStop>>> stopsOf) {
    Set<String> out = new HashSet<>();
    for (Map.Entry<String, TrainRuntimeSnapshot> entry : trains.entrySet()) {
      TrainRuntimeSnapshot train = entry.getValue();
      if (stopsOf
          .apply(train.routeUuid())
          .filter(stops -> RouteTerminals.beforeService(stops, train.routeIndex()))
          .isPresent()) {
        out.add(entry.getKey());
      }
    }
    return out;
  }
}
