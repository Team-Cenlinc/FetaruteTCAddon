package org.fetarute.fetaruteTCAddon.display.pids;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.fetarute.fetaruteTCAddon.api.eta.EtaApi;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi;
import org.fetarute.fetaruteTCAddon.api.timetable.TimetableApi;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteTerminals;
import org.fetarute.fetaruteTCAddon.display.pids.announce.PidsPlatformChanges;

/**
 * 站台屏快照：按车站缓存，有效期内同一车站的所有屏幕共用一次查询。
 *
 * <h2>数据来源</h2>
 *
 * <p>只读公开 API，站台屏与外部插件看到的是同一份数据：
 *
 * <ul>
 *   <li>到发：{@link EtaApi#getBoard}，含运行中列车、已出票未发车与未出票预测
 *   <li>取消：{@link TimetableApi#departuresAt} 中带取消标记的计划到发。预测已排除取消车次，两处不会重复
 *   <li>末班车：同一份计划到发里每个服务日开往各终点的最后一班（{@link PidsLastTrains}），只在按表运行启用时标
 * </ul>
 *
 * <h2>缓存与失败</h2>
 *
 * <p>有效期取 {@code render.snapshot-ttl-seconds}，查询窗口取 {@code render.horizon-minutes}；同一车站的并发请求只算一次。
 * 查询失败时沿用上一份快照的行并留下调试日志，时间戳记为本次，等下一个有效期再重试，避免每块屏每次刷新都重试。
 *
 * <p>运行中列车的行按最近的站台变更（{@link PidsPlatformChanges}）标出变更前的站台；变更时整份缓存作废。
 *
 * <p>计划到发单独缓存 {@link #TIMETABLE_REFRESH}：要把全部已发布时刻表的车次与停靠点扫一遍，取消与末班又很少变。 取一整天（{@link
 * #TIMETABLE_WINDOW}）是为了比到当天最后一班；扫描量由车次与停靠点数决定，与窗口长短无关。车次取消或重新绑定（取消之后又有车接上）时由 {@link
 * #invalidateCancellations()} 立即作废，下一份快照就能看到。
 */
public final class PidsSnapshotProvider {

  /** 计划时刻已过多久的取消班次仍然显示，让乘客知道等的那班车不会来了。 */
  static final Duration CANCELLED_LOOKBACK = Duration.ofMinutes(5);

  /** 计划到发查多远：末班要比到当天最后一班，取时刻表接口的上限一整天。 */
  static final Duration TIMETABLE_WINDOW = Duration.ofHours(24);

  /** 一整天的计划到发条数上限，按大站留足；取满说明可能截断，末班就不标。 */
  static final int DEPARTURE_LOOKUP_LIMIT = 10_000;

  /** 计划到发缓存多久：只影响取消行进出显示窗口的时机（窗口 30 分钟级），取消本身由事件立即作废。 */
  static final Duration TIMETABLE_REFRESH = Duration.ofSeconds(60);

  private final EtaApi eta;
  private final TimetableApi timetables;
  private final RouteApi routes;
  private final Supplier<PidsSettings> settings;
  private final InstantSource clock;
  private final Consumer<String> debugLogger;
  private final PidsPlatformChanges platformChanges;
  private final ConcurrentMap<PidsStationKey, PidsSnapshot> cache = new ConcurrentHashMap<>();
  private final ConcurrentMap<PidsStationKey, TimetableRows> planned = new ConcurrentHashMap<>();

  /**
   * @param eta ETA 接口
   * @param timetables 时刻表接口
   * @param routes 交路接口（取消行的线路与终点）
   * @param settings 当前 {@code pids.yml} 配置；每次取快照现读，重载即生效
   * @param clock 时钟
   * @param debugLogger 调试日志
   */
  public PidsSnapshotProvider(
      EtaApi eta,
      TimetableApi timetables,
      RouteApi routes,
      Supplier<PidsSettings> settings,
      InstantSource clock,
      Consumer<String> debugLogger) {
    this(eta, timetables, routes, settings, clock, debugLogger, PidsPlatformChanges.none());
  }

  /**
   * @param platformChanges 最近的站台变更：运行中列车的行据此标出变更前的站台
   */
  public PidsSnapshotProvider(
      EtaApi eta,
      TimetableApi timetables,
      RouteApi routes,
      Supplier<PidsSettings> settings,
      InstantSource clock,
      Consumer<String> debugLogger,
      PidsPlatformChanges platformChanges) {
    this.platformChanges = Objects.requireNonNull(platformChanges, "platformChanges");
    this.eta = Objects.requireNonNull(eta, "eta");
    this.timetables = Objects.requireNonNull(timetables, "timetables");
    this.routes = Objects.requireNonNull(routes, "routes");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.debugLogger = debugLogger == null ? message -> {} : debugLogger;
  }

  /** 车站的当前快照；缓存未过期时直接返回。 */
  public PidsSnapshot snapshot(PidsStationKey station) {
    Objects.requireNonNull(station, "station");
    Instant now = clock.instant();
    PidsSettings.RenderSettings render = settings.get().render();
    Duration ttl = Duration.ofSeconds(render.snapshotTtlSeconds());
    Duration horizon = Duration.ofMinutes(render.horizonMinutes());
    return cache.compute(
        station,
        (key, cached) ->
            cached != null && now.isBefore(cached.takenAt().plus(ttl))
                ? cached
                : load(key, now, horizon, cached));
  }

  /** 丢弃各车站缓存的快照：站台变更时调用，屏幕与广播下一次取数就能看到。 */
  public void invalidateSnapshots() {
    cache.clear();
  }

  /** 丢弃各车站缓存的计划到发（取消行与末班）：车次取消或重新绑定时调用。 */
  public void invalidateCancellations() {
    planned.clear();
  }

  private PidsSnapshot load(
      PidsStationKey station, Instant now, Duration horizon, PidsSnapshot previous) {
    try {
      TimetableRows timetable = timetableRowsCached(station, now);
      List<PidsRow> rows = new ArrayList<>();
      for (EtaApi.BoardRow row :
          eta.getBoard(station.operatorCode(), station.stationCode(), null, horizon).rows()) {
        rows.add(fromBoard(row, station, now, timetable.lastTrains()));
      }
      Instant until = now.plus(horizon);
      timetable.cancelled().stream()
          .filter(row -> row.expectedAt().isBefore(until))
          .forEach(rows::add);
      rows.sort(Comparator.comparing(PidsRow::expectedAt));
      return new PidsSnapshot(station, now, rows);
    } catch (RuntimeException ex) {
      debugLogger.accept("PIDS_SNAPSHOT_FAILED station=" + station + " error=" + ex);
      return new PidsSnapshot(station, now, previous == null ? List.of() : previous.rows());
    }
  }

  private PidsRow fromBoard(
      EtaApi.BoardRow row, PidsStationKey station, Instant now, PidsLastTrains lastTrains) {
    Instant expectedAt = row.eta().orElse(now);
    return new PidsRow(
        status(row.phase()),
        row.lineName(),
        row.routeId(),
        row.destination(),
        row.destinationId(),
        row.platform(),
        expectedAt,
        row.delaySeconds(),
        row.stopSequence(),
        row.passing(),
        row.terminating(),
        row.outOfService(),
        row.trainName(),
        row.platformPending(),
        row.platformCandidates(),
        row.cars().stream().map(car -> new PidsRow.Car(car.seats(), car.occupied())).toList(),
        row.trainName()
            .flatMap(train -> platformChanges.previousOf(train, station, row.platform())),
        lastTrains.matches(row.routeId(), row.stopSequence(), expectedAt, row.delaySeconds()));
  }

  private static PidsRow.Status status(EtaApi.BoardPhase phase) {
    return switch (phase) {
      case FORECAST -> PidsRow.Status.PLANNED;
      case PENDING -> PidsRow.Status.PENDING;
      case EN_ROUTE -> PidsRow.Status.EN_ROUTE;
      case ARRIVING -> PidsRow.Status.ARRIVING;
      case AT_STATION -> PidsRow.Status.BOARDING;
    };
  }

  private TimetableRows timetableRowsCached(PidsStationKey station, Instant now) {
    return planned.compute(
        station,
        (key, cached) ->
            cached != null && now.isBefore(cached.takenAt().plus(TIMETABLE_REFRESH))
                ? cached
                : timetableRows(key, now));
  }

  /**
   * 一个车站的计划到发派生出的两样：取消行（不限窗口，取快照时再按显示窗口筛）与末班。
   *
   * @param takenAt 取数时刻
   * @param cancelled 取消行
   * @param lastTrains 末班；按表运行未启用、或计划到发取满可能截断时为空
   */
  private record TimetableRows(
      Instant takenAt, List<PidsRow> cancelled, PidsLastTrains lastTrains) {
    private TimetableRows {
      cancelled = List.copyOf(cancelled);
    }
  }

  private TimetableRows timetableRows(PidsStationKey station, Instant now) {
    List<TimetableApi.Departure> found =
        timetables.departuresAt(
            null,
            station.stationCode(),
            now.minus(CANCELLED_LOOKBACK),
            TIMETABLE_WINDOW,
            DEPARTURE_LOOKUP_LIMIT);
    List<TimetableApi.Departure> departures =
        found.stream().filter(departure -> atStation(departure, station)).toList();
    Map<UUID, Optional<RouteApi.RouteDetail>> details = new HashMap<>();
    Function<UUID, Optional<RouteApi.RouteDetail>> route =
        id -> details.computeIfAbsent(id, routes::getRoute);
    List<PidsRow> cancelled =
        departures.stream()
            .filter(TimetableApi.Departure::cancelled)
            .map(departure -> cancelledRow(departure, route.apply(departure.routeId())))
            .toList();
    PidsLastTrains lastTrains =
        timetables.enabled() && found.size() < DEPARTURE_LOOKUP_LIMIT
            ? PidsLastTrains.of(departures, route)
            : PidsLastTrains.NONE;
    return new TimetableRows(now, cancelled, lastTrains);
  }

  /** 站码可能跨运营商重名：按本站节点所属的运营商核对，节点未知时不计入。 */
  private static boolean atStation(TimetableApi.Departure departure, PidsStationKey station) {
    return departure
        .nodeId()
        .flatMap(RouteTerminals::stationIdentityOfNode)
        .filter(ref -> ref.operatorCode().equalsIgnoreCase(station.operatorCode()))
        .isPresent();
  }

  private static PidsRow cancelledRow(
      TimetableApi.Departure departure, Optional<RouteApi.RouteDetail> route) {
    Optional<RouteTerminals.StationRef> terminal =
        route.flatMap(
            detail ->
                RouteTerminals.stationIdentityOfNode(detail.terminal().endOfOperationNodeId()));
    return new PidsRow(
        PidsRow.Status.CANCELLED,
        route.map(detail -> lineAt(detail, departure.stopSequence())).orElse("-"),
        route.map(detail -> detail.info().code()).orElse(departure.routeCode()),
        terminal.map(RouteTerminals.StationRef::stationCode).orElse("-"),
        terminal.map(ref -> ref.operatorCode() + ":" + ref.stationCode()),
        dynamicStop(route, departure.stopSequence())
            ? departure.plannedNodeId().map(RouteTerminals::platformOf).orElse("-")
            : departure.nodeId().map(RouteTerminals::platformOf).orElse("-"),
        departure.plannedArrival(),
        OptionalLong.empty(),
        departure.stopSequence(),
        false,
        departure.terminating(),
        false,
        Optional.empty());
  }

  /** 动态站台停靠：时刻表里的节点只是占位股道，取消的班次从没选过台，不能写成站台号；编表排了计划站台时写计划站台。 */
  private static boolean dynamicStop(Optional<RouteApi.RouteDetail> route, int stopSequence) {
    return route
        .filter(detail -> stopSequence >= 0 && stopSequence < detail.stops().size())
        .map(detail -> detail.stops().get(stopSequence).dynamic())
        .orElse(false);
  }

  /** 列车到本站时所属线路：本站及之前最后一次换线的目标，没有换线时为交路自身线路（与站牌同一口径）。 */
  private static String lineAt(RouteApi.RouteDetail route, int stopSequence) {
    String line = route.info().lineCode();
    List<RouteApi.StopInfo> stops = route.stops();
    for (int i = 0; i <= stopSequence && i < stops.size(); i++) {
      Optional<RouteApi.LineRef> change = stops.get(i).lineChange();
      if (change.isPresent()) {
        line = change.get().lineCode();
      }
    }
    return line;
  }
}
