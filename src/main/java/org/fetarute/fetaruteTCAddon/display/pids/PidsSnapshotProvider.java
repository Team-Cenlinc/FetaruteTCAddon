package org.fetarute.fetaruteTCAddon.display.pids;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDate;
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
 *   <li>取消：{@link TimetableApi#departuresAt} 中带取消标记的计划到发，只列计划到达还没到的（过了就撤，不再挂在屏上）。预测已排除取消车次，两处不会重复
 *   <li>末班车：同一份计划到发里每个服务日开往各终点的最后一班（{@link PidsLastTrains}），只在按表运行启用时标
 * </ul>
 *
 * <h2>缓存与失败</h2>
 *
 * <p>有效期取 {@code render.snapshot-ttl-seconds}，查询窗口取 {@code render.horizon-minutes}；同一车站的并发请求只算一次。
 * 查询失败时沿用上一份快照的行并留下调试日志，时间戳记为本次，等下一个有效期再重试，避免每块屏每次刷新都重试。
 *
 * <p>运行中列车的行按最近的站台变更（{@link PidsPlatformChanges}）标出变更前的站台；变更时整份缓存作废。 通过行按信号员的越站令（{@link
 * SkippedRows}）标出本应停车。
 *
 * <p>信号员取消的车次（{@link #holdCancelled}）多半是晚点车，本站的计划到达常常已过：它的取消行显示到计划到达按取消时的晚点顺延、再加 {@link
 * #CANCELLED_HOLD}，计划到发的回查窗口跟着放宽。
 *
 * <p>计划到发单独缓存 {@link #TIMETABLE_REFRESH}：要把全部已发布时刻表的车次与停靠点扫一遍，取消与末班又很少变。 取一整天（{@link
 * #TIMETABLE_WINDOW}）是为了比到当天最后一班；扫描量由车次与停靠点数决定，与窗口长短无关。车次取消或重新绑定（取消之后又有车接上）时由 {@link
 * #invalidateCancellations()} 立即作废，下一份快照就能看到。
 */
public final class PidsSnapshotProvider {

  /** 计划到发从多久之前查起：晚点的末班车计划发车已过、人还没开出，末班要比得上它。取消行另按计划到达筛，过了就不列。 */
  static final Duration PLANNED_LOOKBACK = Duration.ofMinutes(5);

  /** 计划到发查多远：末班要比到当天最后一班，取时刻表接口的上限一整天。 */
  static final Duration TIMETABLE_WINDOW = Duration.ofHours(24);

  /** 一整天的计划到发条数上限，按大站留足；取满说明可能截断，末班就不标。 */
  static final int DEPARTURE_LOOKUP_LIMIT = 10_000;

  /** 计划到发缓存多久：只影响取消行进出显示窗口的时机（窗口 30 分钟级），取消本身由事件立即作废。 */
  static final Duration TIMETABLE_REFRESH = Duration.ofSeconds(60);

  /** 信号员取消的车次，取消行在顺延后的到达时刻之后再显示多久。 */
  static final Duration CANCELLED_HOLD = Duration.ofMinutes(5);

  /** 顺延按的晚点上限：再晚的车也只顺延这么多，计划到发的回查窗口有界。 */
  static final Duration CANCELLED_HOLD_MAX_DELAY = Duration.ofMinutes(30);

  /** 登记保留多久：车次剩下的站都过了显示窗口即可丢，取得宽些。 */
  static final Duration CANCELLED_HOLD_RETAIN = Duration.ofHours(3);

  private final EtaApi eta;
  private final TimetableApi timetables;
  private final RouteApi routes;
  private final Supplier<PidsSettings> settings;
  private final InstantSource clock;
  private final Consumer<String> debugLogger;
  private final PidsPlatformChanges platformChanges;
  private final ConcurrentMap<PidsStationKey, PidsSnapshot> cache = new ConcurrentHashMap<>();
  private final ConcurrentMap<PidsStationKey, TimetableRows> planned = new ConcurrentHashMap<>();
  private volatile SkippedRows skippedRows = SkippedRows.NONE;
  private final ConcurrentMap<HeldTrip, Held> held = new ConcurrentHashMap<>();

  /** 信号员取消的一班车：时刻表、车次号、服务日。 */
  private record HeldTrip(UUID timetableId, String tripCode, LocalDate serviceDate) {}

  /** 取消时的晚点（秒，已截到 0 至上限）与登记时刻。 */
  private record Held(long delaySeconds, Instant at) {}

  /** 信号员令哪趟车越过哪一站：站牌上它已是通过行，这里只区分“本应停车”。 */
  @FunctionalInterface
  public interface SkippedRows {
    SkippedRows NONE = (trainName, routeId, stopSequence) -> false;

    /**
     * @param trainName 列车名
     * @param routeId 交路 ID（{@code 运营商:线路:交路}）
     * @param stopSequence 停靠序号（交路节点的 0 起下标）
     */
    boolean skipped(String trainName, String routeId, int stopSequence);

    /**
     * 站台屏行上只有交路 ID，越站令按交路 UUID 记：先换成 UUID 再问。
     *
     * @param routeUuid 交路 ID → 交路 UUID；查不到时为空（不算越站）
     * @param skipped 按交路 UUID 问越站令
     */
    static SkippedRows byRouteUuid(
        Function<String, Optional<UUID>> routeUuid,
        org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaService.SkippedStops skipped) {
      return (trainName, routeId, stopSequence) ->
          routeUuid
              .apply(routeId)
              .filter(uuid -> skipped.skipped(trainName, uuid, stopSequence))
              .isPresent();
    }
  }

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

  /** 接上越站令的查询；已缓存的快照到期后才看得到。 */
  public void setSkippedRows(SkippedRows skippedRows) {
    this.skippedRows = skippedRows == null ? SkippedRows.NONE : skippedRows;
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

  /**
   * 信号员要取消这列车：在删车之前调用，记下它当前那一班和预计的晚点，它的取消行按晚点顺延显示。列车没有绑定车次时什么也不做。
   *
   * @param trainName 列车名
   */
  public void holdCancelled(String trainName) {
    Optional<TimetableApi.TrainAssignment> found = timetables.getAssignment(trainName);
    if (found.isEmpty()) {
      return;
    }
    TimetableApi.TrainAssignment assignment = found.get();
    // 预计晚点随扣车增长，到发晚点要到下一次到发才更新：先取预计
    OptionalLong delay =
        assignment.projectedDelaySeconds().isPresent()
            ? assignment.projectedDelaySeconds()
            : assignment.currentDelaySeconds();
    long seconds = Math.min(Math.max(0L, delay.orElse(0L)), CANCELLED_HOLD_MAX_DELAY.toSeconds());
    Instant now = clock.instant();
    held.values().removeIf(entry -> !entry.at().plus(CANCELLED_HOLD_RETAIN).isAfter(now));
    held.put(
        new HeldTrip(assignment.timetableId(), assignment.tripCode(), assignment.serviceDate()),
        new Held(seconds, now));
    planned.clear();
    cache.clear();
  }

  /** 接过重建前那一份的取消登记（{@code /fta reload} 重建站台屏服务时）。 */
  public void continueFrom(PidsSnapshotProvider previous) {
    held.putAll(previous.held);
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
          .filter(
              cancelled ->
                  cancelled.showUntil().isAfter(now)
                      && cancelled.row().expectedAt().isBefore(until))
          .map(CancelledRow::row)
          .forEach(rows::add);
      rows.sort(Comparator.comparing(PidsRow::expectedAt));
      return new PidsSnapshot(station, now, rows);
    } catch (RuntimeException ex) {
      debugLogger.accept("PIDS_SNAPSHOT_FAILED station=" + station + " error=" + ex);
      TimetableRows timetable = planned.get(station);
      List<PidsRow> kept =
          previous == null
              ? List.of()
              : previous.rows().stream()
                  .filter(
                      row ->
                          row.status() != PidsRow.Status.CANCELLED
                              || cancelledStillShown(row, timetable, now))
                  .toList();
      return new PidsSnapshot(station, now, kept);
    }
  }

  /** 取数失败时沿用的取消行还显示不显示：与正常取数同一口径（顺延的按顺延后的时刻），查不到计划到发时按计划到达。 */
  private static boolean cancelledStillShown(PidsRow row, TimetableRows timetable, Instant now) {
    if (timetable != null) {
      for (CancelledRow cancelled : timetable.cancelled()) {
        if (cancelled.row().equals(row)) {
          return cancelled.showUntil().isAfter(now);
        }
      }
    }
    return row.expectedAt().isAfter(now);
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
        lastTrains.matches(row.routeId(), row.stopSequence(), expectedAt, row.delaySeconds()),
        row.division().map(PidsSnapshotProvider::division),
        row.joining()
            .map(
                joining ->
                    new PidsRow.Joining(
                        joining.stopSequence(),
                        joining.stationId(),
                        joining.ownCars(),
                        joining.joiningCars(),
                        joining.joiningStanding(),
                        joining.continuesOnRouteId(),
                        joining.continuesFromSequence())),
        row.passing()
            && row.trainName()
                .filter(train -> skippedRows.skipped(train, row.routeId(), row.stopSequence()))
                .isPresent());
  }

  private static PidsRow.Division division(EtaApi.Division division) {
    return new PidsRow.Division(
        division.stopSequence(),
        division.stationId(),
        division.parts().stream()
            .map(
                part ->
                    new PidsRow.Division.Part(
                        part.cars(),
                        part.detached(),
                        part.destinationId(),
                        part.routeId(),
                        part.fromSequence()))
            .toList(),
        division.divided());
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
   * 一个车站的计划到发派生出的两样：取消行（不限窗口，取快照时再按显示到何时与查询窗口筛）与末班。
   *
   * @param takenAt 取数时刻
   * @param cancelled 取消行
   * @param lastTrains 末班；按表运行未启用、或计划到发取满可能截断时为空
   */
  private record TimetableRows(
      Instant takenAt, List<CancelledRow> cancelled, PidsLastTrains lastTrains) {
    private TimetableRows {
      cancelled = List.copyOf(cancelled);
    }
  }

  /**
   * 一条取消行与它显示到何时：一般是计划到达，信号员取消的车次按 {@link #holdCancelled} 顺延。
   *
   * @param row 取消行
   * @param showUntil 显示到此刻（不含）
   */
  private record CancelledRow(PidsRow row, Instant showUntil) {}

  private TimetableRows timetableRows(PidsStationKey station, Instant now) {
    held.values().removeIf(entry -> !entry.at().plus(CANCELLED_HOLD_RETAIN).isAfter(now));
    Duration lookback = PLANNED_LOOKBACK;
    for (Held entry : held.values()) {
      Duration needed = Duration.ofSeconds(entry.delaySeconds()).plus(CANCELLED_HOLD);
      if (needed.compareTo(lookback) > 0) {
        lookback = needed;
      }
    }
    List<TimetableApi.Departure> found =
        timetables.departuresAt(
            null,
            station.stationCode(),
            now.minus(lookback),
            TIMETABLE_WINDOW,
            DEPARTURE_LOOKUP_LIMIT);
    List<TimetableApi.Departure> departures =
        found.stream().filter(departure -> atStation(departure, station)).toList();
    Map<UUID, Optional<RouteApi.RouteDetail>> details = new HashMap<>();
    Function<UUID, Optional<RouteApi.RouteDetail>> route =
        id -> details.computeIfAbsent(id, routes::getRoute);
    List<CancelledRow> cancelled =
        departures.stream()
            .filter(TimetableApi.Departure::cancelled)
            .map(
                departure ->
                    new CancelledRow(
                        cancelledRow(departure, route.apply(departure.routeId())),
                        showUntil(departure)))
            .toList();
    PidsLastTrains lastTrains =
        timetables.enabled() && found.size() < DEPARTURE_LOOKUP_LIMIT
            ? PidsLastTrains.of(departures, route)
            : PidsLastTrains.NONE;
    return new TimetableRows(now, cancelled, lastTrains);
  }

  private Instant showUntil(TimetableApi.Departure departure) {
    Held entry =
        held.get(
            new HeldTrip(departure.timetableId(), departure.tripCode(), departure.serviceDate()));
    return entry == null
        ? departure.plannedArrival()
        : departure.plannedArrival().plusSeconds(entry.delaySeconds()).plus(CANCELLED_HOLD);
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
