package org.fetarute.fetaruteTCAddon.web.gtfs;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.api.StationDirectory;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.LineStatus;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLineChanges;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteTerminals;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteTerminals.StationRef;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.Timetable;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableRoutePlan;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableStop;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableTrip;

/**
 * 把一次 {@link GtfsSnapshot} 算成一份 {@link GtfsFeed}（纯计算，不碰存储、网络与服务器对象）。
 *
 * <p>逐项口径：
 *
 * <ul>
 *   <li><b>线路</b>：ACTIVE 与 MAINTENANCE 进 {@code routes.txt}，PLANNING 不进。
 *   <li><b>车次</b>：只取生效中的已发布时刻表（{@link GtfsSnapshot#timetableEnabled()}
 *       为假时一个都不出）。交路计划里至少有两个停车的车站（STOP/TERMINATE 且有站码）的车次才进包——出库、回库交路只有在途中办客时才发布，纯走行不进。
 *   <li><b>直通运转</b>：在换线站拆成两个 GTFS 车次，第二段起 ID 加 {@code ~2}，两段用 {@code transfers.txt} 的 {@code
 *       transfer_type=4} 连起来。拆段后不足两个停靠点的段并回前一段：一站的「车次」没有行程可言，终点换线对乘客也没有意义。
 *   <li><b>停靠点</b>：车站、股道两级；站咽喉、区间点、车库、道岔都不出。被车次停靠的车站发布它的全部股道（动态站台的车进站前才选台，实时源可能报出任意一条）。
 *       编表时排了计划股道就用计划股道，没有时用车站级的 {@code …:DYN}。
 *   <li><b>上下客</b>：整趟的第一个停靠点不下客、最后一个停靠点不上客；拆段边界两侧都是正常上下客（车上的乘客在换线站照常下车）。
 * </ul>
 *
 * <p>车站记录缺坐标、站码查不到车站时，这个停靠点不出、整包继续；每类问题只在 {@link GtfsFeed#warnings()} 里记一条。
 */
public final class GtfsFeedBuilder {

  /** 每类问题最多保留这么多条告警，避免几千个坏停靠点把日志刷爆。 */
  private static final int MAX_WARNINGS = 40;

  private final GtfsSnapshot snapshot;
  private final GtfsIds ids;
  private final List<String> warnings = new ArrayList<>();
  private final Set<String> warnedKeys = new HashSet<>();

  private final Map<UUID, Operator> operatorsById = new HashMap<>();
  private final Map<UUID, Line> linesById = new HashMap<>();
  private final Map<String, StationDirectory.StationEntry> stationsByKey = new HashMap<>();
  private final Map<UUID, StationDirectory.StationEntry> stationsById = new HashMap<>();
  private final Map<String, Point> stationCoordinates = new HashMap<>();
  private final Map<String, PlatformInfo> platformsByNode = new HashMap<>();
  private final Map<String, List<PlatformInfo>> platformsByStation = new HashMap<>();

  /** 发布线路：线路键 → 线路与运营商。PLANNING 不在其中。 */
  private final Map<String, FeedLine> feedLines = new LinkedHashMap<>();

  /** 要发布的停靠车站：被车次停靠的车站，加上有非规划线路经过的车站。 */
  private final Map<String, StationDirectory.StationEntry> usedStations = new LinkedHashMap<>();

  /** 真正被车次停靠的车站：只有这些车站需要 {@code …:DYN} 兜底停靠点。 */
  private final Set<String> tripServedStations = new LinkedHashSet<>();

  /** 实际用到的股道节点：节点 ID → 所属车站键（备用股道不在图里时也能补出停靠点）。 */
  private final Map<String, String> usedPlatformNodes = new LinkedHashMap<>();

  private GtfsFeedBuilder(GtfsSnapshot snapshot) {
    this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    this.ids = new GtfsIds(snapshot.serverId());
    index(snapshot);
  }

  /**
   * 生成一份静态 GTFS。
   *
   * @param snapshot 一次读齐的输入
   */
  public static GtfsFeed build(GtfsSnapshot snapshot) {
    return new GtfsFeedBuilder(snapshot).build();
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // 索引
  // ─────────────────────────────────────────────────────────────────────────────

  private void index(GtfsSnapshot input) {
    for (Operator operator : input.operators()) {
      operatorsById.put(operator.id(), operator);
    }
    for (Line line : input.lines()) {
      linesById.put(line.id(), line);
    }
    for (StationDirectory.StationEntry entry : input.stations()) {
      stationsByKey.put(stationKey(entry.operator().code(), entry.code()), entry);
      stationsById.put(entry.id(), entry);
    }
    for (GtfsPlatformNode node : input.platforms()) {
      Optional<StationRef> ref = RouteTerminals.stationRefOfNode(node.nodeId());
      if (ref.isEmpty()) {
        warn("platform-unparsed:" + node.nodeId(), "股道节点不是车站停靠点形状，已跳过: " + node.nodeId());
        continue;
      }
      String key = stationKey(ref.get().operatorCode(), ref.get().stationCode());
      PlatformInfo info =
          new PlatformInfo(
              node.nodeId(), key, node.x(), node.z(), RouteTerminals.platformOf(node.nodeId()));
      platformsByNode.put(node.nodeId(), info);
      platformsByStation.computeIfAbsent(key, ignored -> new ArrayList<>()).add(info);
    }
    platformsByStation
        .values()
        .forEach(list -> list.sort(Comparator.comparing(PlatformInfo::nodeId)));
    computeStationCoordinates();
    indexFeedLines();
  }

  /** 车站坐标：优先取车站记录，缺坐标时取各股道质心；两者都没有的车站不发布。 */
  private void computeStationCoordinates() {
    for (Map.Entry<String, StationDirectory.StationEntry> entry : stationsByKey.entrySet()) {
      String key = entry.getKey();
      StationDirectory.StationEntry station = entry.getValue();
      Optional<Point> point =
          station
              .station()
              .location()
              .map(location -> new Point(location.x(), location.z()))
              .filter(GtfsFeedBuilder::finite)
              .or(() -> centroid(platformsByStation.getOrDefault(key, List.of())));
      point.ifPresent(found -> stationCoordinates.put(key, found));
    }
  }

  private static Optional<Point> centroid(List<PlatformInfo> platforms) {
    List<PlatformInfo> usable =
        platforms.stream()
            .filter(platform -> finite(new Point(platform.x(), platform.z())))
            .toList();
    if (usable.isEmpty()) {
      return Optional.empty();
    }
    double x = 0.0;
    double z = 0.0;
    for (PlatformInfo platform : usable) {
      x += platform.x();
      z += platform.z();
    }
    return Optional.of(new Point(x / usable.size(), z / usable.size()));
  }

  private static boolean finite(Point point) {
    return Double.isFinite(point.x()) && Double.isFinite(point.z());
  }

  /** 发布线路：PLANNING 不进；运营商或线路实体缺失的线路跳过。 */
  private void indexFeedLines() {
    List<Line> lines = new ArrayList<>(snapshot.lines());
    lines.sort(
        Comparator.comparing((Line line) -> operatorCode(line.operatorId()))
            .thenComparing(line -> line.code().toUpperCase(Locale.ROOT)));
    for (Line line : lines) {
      Operator operator = operatorsById.get(line.operatorId());
      if (operator == null) {
        warn("line-operator-missing:" + line.id(), "线路 " + line.code() + " 的运营商不存在，已跳过");
        continue;
      }
      if (line.status() == LineStatus.PLANNING) {
        continue;
      }
      feedLines.putIfAbsent(
          GtfsFeed.lineKey(operator.code(), line.code()), new FeedLine(operator, line));
    }
  }

  private String operatorCode(UUID operatorId) {
    return Optional.ofNullable(operatorsById.get(operatorId))
        .map(operator -> operator.code().toUpperCase(Locale.ROOT))
        .orElse("");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // 生成
  // ─────────────────────────────────────────────────────────────────────────────

  private GtfsFeed build() {
    List<GtfsFeed.Agency> agencies = buildAgencies();
    List<GtfsFeed.Route> routes = buildRoutes();
    List<GtfsFeed.Trip> trips = new ArrayList<>();
    List<GtfsFeed.Transfer> transfers = new ArrayList<>();
    buildTrips(trips, transfers);
    List<GtfsFeed.Stop> stops = buildStops();
    addGroupTransfers(transfers, stops);
    sortTrips(trips);
    sortTransfers(transfers);
    String serviceId = ids.service();
    LocalDate start = GtfsTime.calendarStart(snapshot.generatedAt(), snapshot.zone());
    LocalDate end = GtfsTime.calendarEnd(snapshot.generatedAt(), snapshot.zone());
    Map<String, String> stopIdsByNode = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : usedPlatformNodes.entrySet()) {
      if (usedStations.containsKey(entry.getValue())) {
        stopIdsByNode.put(entry.getKey(), ids.stop(entry.getKey()));
      }
    }
    Map<String, GtfsFeed.Stop> stationsByKeyOut = new LinkedHashMap<>();
    for (Map.Entry<String, StationDirectory.StationEntry> entry : usedStations.entrySet()) {
      String stationId = ids.station(entry.getValue().operator().code(), entry.getValue().code());
      for (GtfsFeed.Stop stop : stops) {
        if (stop.station() && stop.id().equals(stationId)) {
          stationsByKeyOut.put(entry.getKey(), stop);
          break;
        }
      }
    }
    String publisherName = snapshot.publisherName();
    String publisherUrl = snapshot.publisherUrl();
    String version =
        GtfsFeedDigest.feedVersion(
            ids,
            snapshot.generatedAt(),
            snapshot.zone(),
            serviceId,
            publisherName,
            publisherUrl,
            agencies,
            routes,
            stops,
            trips,
            transfers);
    return GtfsFeed.of(
        version,
        snapshot.generatedAt(),
        snapshot.zone(),
        serviceId,
        start,
        end,
        publisherName,
        publisherUrl,
        agencies,
        routes,
        stops,
        trips,
        transfers,
        warnings,
        stopIdsByNode,
        stationsByKeyOut);
  }

  private List<GtfsFeed.Agency> buildAgencies() {
    Map<String, Operator> operators = new LinkedHashMap<>();
    for (FeedLine feedLine : feedLines.values()) {
      operators.putIfAbsent(
          feedLine.operator().code().toUpperCase(Locale.ROOT), feedLine.operator());
    }
    List<GtfsFeed.Agency> out = new ArrayList<>();
    for (Operator operator : operators.values()) {
      out.add(
          new GtfsFeed.Agency(
              ids.agency(operator.code()),
              operator.code(),
              operator.name(),
              operator.secondaryName(),
              snapshot.publisherUrl(),
              snapshot.zone().getId(),
              "zh"));
    }
    out.sort(Comparator.comparing(GtfsFeed.Agency::operatorCode));
    return out;
  }

  private List<GtfsFeed.Route> buildRoutes() {
    List<FeedLine> lines = new ArrayList<>(feedLines.values());
    lines.sort(
        Comparator.comparing((FeedLine line) -> line.operator().code())
            .thenComparing(line -> line.line().code()));
    List<GtfsFeed.Route> out = new ArrayList<>(lines.size());
    for (int i = 0; i < lines.size(); i++) {
      FeedLine feedLine = lines.get(i);
      Line line = feedLine.line();
      Optional<String> color = normalizeColor(line.color());
      out.add(
          new GtfsFeed.Route(
              ids.route(feedLine.operator().code(), line.code()),
              ids.agency(feedLine.operator().code()),
              feedLine.operator().code(),
              line.code(),
              line.code(),
              line.name(),
              line.secondaryName(),
              routeType(line),
              color,
              color.map(GtfsFeedBuilder::textColor),
              i + 1));
    }
    return out;
  }

  private void buildTrips(List<GtfsFeed.Trip> trips, List<GtfsFeed.Transfer> transfers) {
    if (!snapshot.timetableEnabled()) {
      return;
    }
    List<Timetable> timetables = new ArrayList<>(snapshot.timetables());
    timetables.sort(Comparator.comparing(Timetable::code).thenComparing(t -> t.id().toString()));
    for (Timetable timetable : timetables) {
      addTimetableTrips(timetable, trips, transfers);
    }
  }

  private void addTimetableTrips(
      Timetable timetable, List<GtfsFeed.Trip> trips, List<GtfsFeed.Transfer> transfers) {
    Operator timetableOperator = operatorsById.get(timetable.operatorId());
    Line timetableLine = linesById.get(timetable.lineId());
    if (timetableOperator == null || timetableLine == null) {
      warn("timetable-owner:" + timetable.id(), "时刻表 " + timetable.code() + " 的运营商或线路不存在，已跳过");
      return;
    }
    if (!timetable.zoneId().equals(snapshot.zone())) {
      // GTFS 的所有时刻都按 agency_timezone 解释；时刻表用别的时区时换算要跨夏令时，宁可不发布也不出错
      warn("timetable-zone:" + timetable.id(), "时刻表 " + timetable.code() + " 的时区与服务器时区不同，已跳过这些车次");
      return;
    }
    String managementKey = GtfsFeed.lineKey(timetableOperator.code(), timetableLine.code());
    if (!feedLines.containsKey(managementKey)) {
      warn("timetable-line:" + managementKey, "时刻表 " + timetable.code() + " 所属线路不发布（规划中），已跳过这些车次");
      return;
    }
    if (timetableLine.status() != LineStatus.ACTIVE) {
      warn(
          "timetable-line-status:" + managementKey,
          "时刻表 " + timetable.code() + " 所属线路不是运营中，已跳过这些车次");
      return;
    }
    for (TimetableTrip trip : timetable.trips()) {
      buildTrip(timetable, timetableOperator, timetableLine, trip, trips, transfers);
    }
  }

  private void buildTrip(
      Timetable timetable,
      Operator managementOperator,
      Line managementLine,
      TimetableTrip trip,
      List<GtfsFeed.Trip> trips,
      List<GtfsFeed.Transfer> transfers) {
    Optional<TimetableRoutePlan> planOptional = timetable.tripPlan(trip);
    if (planOptional.isEmpty()) {
      warn(
          "trip-plan:" + trip.id(),
          "车次 " + trip.tripCode() + "（" + timetable.code() + "）没有交路时分，已跳过");
      return;
    }
    TimetableRoutePlan plan = planOptional.get();
    GtfsRouteRef route = snapshot.routes().get(trip.routeId());
    if (route == null) {
      warn("trip-route:" + trip.routeId(), "交路 " + trip.routeId() + " 不在交路缓存里，已跳过它的车次");
      return;
    }
    List<RouteStop> routeStops = route.stops();
    List<TimetableStop> planStops = plan.stops();
    boolean aligned = routeStops.size() == planStops.size();
    if (!aligned) {
      warn(
          "trip-stops-mismatch:" + trip.routeId(),
          "交路 " + route.routeCode() + " 的停靠表与时刻表不一致，换线按交路自身线路处理");
    }
    List<RouteLineChanges.LineRef> linesByIndex =
        aligned
            ? RouteLineChanges.linesByIndex(
                routeStops, new RouteLineChanges.LineRef(route.operatorCode(), route.lineCode()))
            : List.of();
    List<StationStop> stationStops = new ArrayList<>();
    for (TimetableStop stop : planStops) {
      if (!stop.stops() || stop.stationCode().isEmpty()) {
        continue;
      }
      Optional<StationStop> resolved = resolveStationStop(stop, trip);
      if (resolved.isPresent()) {
        stationStops.add(resolved.get());
      }
    }
    if (stationStops.size() < 2) {
      // 纯走行（车库 ↔ 首末站）不发布：GTFS 的车次至少要让人上得去、下得来
      return;
    }
    RouteLineChanges.LineRef routeLine =
        new RouteLineChanges.LineRef(route.operatorCode(), route.lineCode());
    List<RouteLineChanges.LineRef> linesAt =
        aligned
            ? lineAt(stationStops, linesByIndex)
            : constantLines(stationStops.size(), routeLine);
    for (RouteLineChanges.LineRef line : linesAt) {
      if (!runnableLine(line)) {
        warn(
            "trip-passenger-line:" + line.operatorCode() + ":" + line.lineCode(),
            "车次经过的线路 " + line.lineCode() + " 不运营（或不在发布范围），已跳过这些车次");
        return;
      }
    }
    Headsign headsign = resolveHeadsign(route);
    List<Integer> boundaries = boundaries(linesAt);
    Map<Integer, String> stopIds = new HashMap<>();
    for (StationStop stationStop : stationStops) {
      stopIds.put(stationStop.stop().stopSequence(), resolveStopId(trip, stationStop));
    }
    List<Segment> segments = segments(stationStops, linesAt, boundaries);
    List<GtfsFeed.Trip> built = new ArrayList<>(segments.size());
    for (int index = 0; index < segments.size(); index++) {
      Segment segment = segments.get(index);
      List<GtfsFeed.StopTime> stopTimes = new ArrayList<>(segment.stops().size());
      boolean firstSegment = index == 0;
      boolean lastSegment = index == segments.size() - 1;
      for (int k = 0; k < segment.stops().size(); k++) {
        StationStop stationStop = segment.stops().get(k);
        TimetableStop stop = stationStop.stop();
        int arrival = trip.departureSecondOfDay() + stop.arrivalOffsetSeconds();
        int departure = trip.departureSecondOfDay() + stop.departureOffsetSeconds();
        int dropOff = firstSegment && k == 0 ? 1 : 0;
        int pickup = lastSegment && k == segment.stops().size() - 1 ? 1 : 0;
        stopTimes.add(
            new GtfsFeed.StopTime(
                stop.stopSequence(),
                stopIds.get(stop.stopSequence()),
                arrival,
                departure,
                pickup,
                dropOff));
      }
      if (!timeOrdered(stopTimes)) {
        warn(
            "trip-time-order:" + trip.id(),
            "车次 " + trip.tripCode() + "（" + timetable.code() + "）的站间时分倒退，已跳过");
        return;
      }
      String segmentId =
          ids.trip(
              managementOperator.code(),
              managementLine.code(),
              timetable.code(),
              trip.tripCode(),
              index);
      built.add(
          new GtfsFeed.Trip(
              segmentId,
              ids.route(segment.line().operatorCode(), segment.line().lineCode()),
              ids.service(),
              trip.tripCode(),
              headsign.primary(),
              headsign.secondary(),
              stopTimes.get(0).departureSecondOfDay(),
              timetable.id(),
              timetable.code(),
              trip.tripCode(),
              trip.id(),
              index,
              segment.stops().get(0).stop().stopSequence(),
              segment.stops().get(segment.stops().size() - 1).stop().stopSequence(),
              stopTimes));
    }
    for (int index = 0; index + 1 < built.size(); index++) {
      StationStop boundary =
          segments.get(index).stops().get(segments.get(index).stops().size() - 1);
      String stopId = stopIds.get(boundary.stop().stopSequence());
      transfers.add(
          new GtfsFeed.Transfer(
              stopId,
              stopId,
              GtfsFeed.TRANSFER_IN_SEAT,
              OptionalInt.empty(),
              Optional.of(built.get(index).id()),
              Optional.of(built.get(index + 1).id())));
    }
    trips.addAll(built);
  }

  /** 每站所属线路（按车站停靠点逐个取，与 route 路径点下标对齐）。 */
  private static List<RouteLineChanges.LineRef> lineAt(
      List<StationStop> stationStops, List<RouteLineChanges.LineRef> linesByIndex) {
    List<RouteLineChanges.LineRef> out = new ArrayList<>(stationStops.size());
    for (StationStop stationStop : stationStops) {
      int index = stationStop.stop().stopSequence();
      out.add(
          index >= 0 && index < linesByIndex.size()
              ? linesByIndex.get(index)
              : linesByIndex.get(linesByIndex.size() - 1));
    }
    return out;
  }

  private static List<RouteLineChanges.LineRef> constantLines(
      int size, RouteLineChanges.LineRef line) {
    List<RouteLineChanges.LineRef> out = new ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      out.add(line);
    }
    return out;
  }

  /**
   * 换线边界（车站停靠点下标）：第 k 个停靠点与前一个的线路不同就拆。
   *
   * <p>相邻两个停靠点都换线时中间只剩一站，不成段，去掉靠后的边界；末段只有一站时并回前一段——终点换线对乘客没有意义，一站的「车次」也没有行程可言。
   */
  static List<Integer> boundaries(List<RouteLineChanges.LineRef> linesAt) {
    List<Integer> boundaries = new ArrayList<>();
    for (int i = 1; i < linesAt.size(); i++) {
      if (!linesAt.get(i).sameLine(linesAt.get(i - 1))) {
        boundaries.add(i);
      }
    }
    for (int i = boundaries.size() - 1; i >= 1; i--) {
      if (boundaries.get(i) - boundaries.get(i - 1) < 2) {
        boundaries.remove(i);
      }
    }
    if (!boundaries.isEmpty() && linesAt.size() - boundaries.get(boundaries.size() - 1) < 2) {
      boundaries.remove(boundaries.size() - 1);
    }
    return boundaries;
  }

  private static List<Segment> segments(
      List<StationStop> stationStops,
      List<RouteLineChanges.LineRef> linesAt,
      List<Integer> boundaries) {
    List<Segment> out = new ArrayList<>();
    int start = 0;
    RouteLineChanges.LineRef line = linesAt.get(0);
    for (int boundary : boundaries) {
      out.add(new Segment(line, List.copyOf(stationStops.subList(start, boundary + 1))));
      start = boundary;
      line = linesAt.get(boundary);
    }
    out.add(new Segment(line, List.copyOf(stationStops.subList(start, stationStops.size()))));
    return out;
  }

  private Optional<StationStop> resolveStationStop(TimetableStop stop, TimetableTrip trip) {
    Optional<StationRef> ref =
        stop.nodeId()
            .flatMap(RouteTerminals::stationRefOfNode)
            .or(() -> stop.stationCode().flatMap(code -> stationRefOfCode(code, trip)));
    if (ref.isEmpty()) {
      warn(
          "stop-ref:" + stop.stopSequence(),
          "车次 " + trip.tripCode() + " 的第 " + stop.stopSequence() + " 站解析不出车站，已跳过");
      return Optional.empty();
    }
    String key = stationKey(ref.get().operatorCode(), ref.get().stationCode());
    StationDirectory.StationEntry entry = stationsByKey.get(key);
    if (entry == null) {
      warn(
          "station-missing:" + key,
          "站码 " + ref.get().operatorCode() + ":" + ref.get().stationCode() + " 查不到车站，相关停靠点已跳过");
      return Optional.empty();
    }
    String canonical = stationKey(entry.operator().code(), entry.code());
    if (!stationCoordinates.containsKey(canonical)) {
      warn(
          "station-no-position:" + canonical,
          "车站 " + entry.operator().code() + ":" + entry.code() + " 没有坐标也没有股道，相关停靠点已跳过");
      return Optional.empty();
    }
    return Optional.of(new StationStop(stop, entry, canonical));
  }

  /** 停靠点没有节点 ID 时的兜底：按站码在本运营商的车站里找。 */
  private Optional<StationRef> stationRefOfCode(String stationCode, TimetableTrip trip) {
    GtfsRouteRef route = snapshot.routes().get(trip.routeId());
    if (route == null) {
      return Optional.empty();
    }
    String key = stationKey(route.operatorCode(), stationCode);
    StationDirectory.StationEntry entry = stationsByKey.get(key);
    return entry == null
        ? Optional.empty()
        : Optional.of(new StationRef(entry.operator().code(), entry.code(), ""));
  }

  /**
   * 停靠点的 {@code stop_id}：图里的股道直接用；动态站台用编表时排定的计划股道；都没有时用车站级的 {@code …:DYN}。
   *
   * <p>解析的同时登记「这座车站被停靠」「这条股道要发布」，车站的其余股道在生成 stops.txt 时一并补上。
   */
  private String resolveStopId(TimetableTrip trip, StationStop stationStop) {
    usedStations.putIfAbsent(stationStop.stationKey(), stationStop.entry());
    tripServedStations.add(stationStop.stationKey());
    String nodeId = stationStop.stop().nodeId().orElse("");
    PlatformInfo fixed = platformsByNode.get(nodeId);
    if (fixed != null && fixed.stationKey().equals(stationStop.stationKey())) {
      usedPlatformNodes.putIfAbsent(fixed.nodeId(), stationStop.stationKey());
      return ids.stop(fixed.nodeId());
    }
    Optional<String> planned =
        snapshot.plannedPlatforms().plannedPlatform(trip.id(), stationStop.stop().stopSequence());
    if (planned.isPresent()) {
      String plannedNode = planned.get().trim();
      Optional<StationRef> ref = RouteTerminals.stationRefOfNode(plannedNode);
      if (ref.isPresent()
          && stationKey(ref.get().operatorCode(), ref.get().stationCode())
              .equals(stationStop.stationKey())) {
        usedPlatformNodes.putIfAbsent(plannedNode, stationStop.stationKey());
        return ids.stop(plannedNode);
      }
    }
    return ids.dynamicStop(stationStop.entry().operator().code(), stationStop.entry().code());
  }

  private boolean runnableLine(RouteLineChanges.LineRef line) {
    FeedLine feedLine = feedLines.get(GtfsFeed.lineKey(line.operatorCode(), line.lineCode()));
    return feedLine != null && feedLine.line().status() == LineStatus.ACTIVE;
  }

  private Headsign resolveHeadsign(GtfsRouteRef route) {
    List<RouteStop> stops = route.stops();
    OptionalInt index = RouteTerminals.endOfOperationIndex(stops);
    if (index.isEmpty()) {
      index = RouteTerminals.namingIndex(stops);
    }
    if (index.isPresent()) {
      RouteStop stop = stops.get(index.getAsInt());
      Optional<StationRef> ref =
          RouteTerminals.stationRef(stop).or(() -> RouteTerminals.stationIdentityOf(stop));
      if (ref.isPresent()) {
        StationDirectory.StationEntry entry =
            stationsByKey.get(stationKey(ref.get().operatorCode(), ref.get().stationCode()));
        if (entry != null) {
          return new Headsign(entry.station().name(), entry.station().secondaryName());
        }
      }
    }
    return new Headsign(route.routeCode(), Optional.empty());
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // 停靠点、换乘
  // ─────────────────────────────────────────────────────────────────────────────

  private List<GtfsFeed.Stop> buildStops() {
    // 有线路经过、但暂时没有车次停靠的车站也要发布（设计稿 Q4：线路与车站照常出）
    for (UUID stationId : snapshot.stationsOfPublishedLines()) {
      StationDirectory.StationEntry entry = stationsById.get(stationId);
      if (entry == null) {
        continue;
      }
      String key = GtfsFeed.stationKey(entry.operator().code(), entry.code());
      if (stationCoordinates.containsKey(key)) {
        usedStations.putIfAbsent(key, entry);
      }
    }
    // 被停靠车站的全部股道都发布：动态站台的车可能停在任意一条，实时源报出的 assigned_stop_id 必须是静态包里有的停靠点
    for (Map.Entry<String, StationDirectory.StationEntry> station : usedStations.entrySet()) {
      for (PlatformInfo platform : platformsByStation.getOrDefault(station.getKey(), List.of())) {
        usedPlatformNodes.putIfAbsent(platform.nodeId(), station.getKey());
      }
    }
    Map<String, List<String>> extraPlatforms = new LinkedHashMap<>();
    for (Map.Entry<String, String> used : usedPlatformNodes.entrySet()) {
      if (!platformsByNode.containsKey(used.getKey())
          && usedStations.containsKey(used.getValue())) {
        extraPlatforms
            .computeIfAbsent(used.getValue(), ignored -> new ArrayList<>())
            .add(used.getKey());
      }
    }
    extraPlatforms.values().forEach(list -> list.sort(Comparator.naturalOrder()));

    List<StationDirectory.StationEntry> stations = new ArrayList<>(usedStations.values());
    stations.sort(
        Comparator.comparing((StationDirectory.StationEntry entry) -> entry.operator().code())
            .thenComparing(StationDirectory.StationEntry::code));
    List<GtfsFeed.Stop> out = new ArrayList<>();
    for (StationDirectory.StationEntry entry : stations) {
      String key = GtfsFeed.stationKey(entry.operator().code(), entry.code());
      Point point = stationCoordinates.get(key);
      if (point == null) {
        continue;
      }
      String stationStopId = ids.station(entry.operator().code(), entry.code());
      String name = entry.station().name();
      Optional<String> secondary = entry.station().secondaryName();
      out.add(
          new GtfsFeed.Stop(
              stationStopId,
              name,
              secondary,
              GtfsGeo.latitude(point.z()),
              GtfsGeo.longitude(point.x()),
              GtfsFeed.LOCATION_STATION,
              Optional.empty(),
              Optional.empty()));
      for (PlatformInfo platform : platformsByStation.getOrDefault(key, List.of())) {
        out.add(
            new GtfsFeed.Stop(
                ids.stop(platform.nodeId()),
                name,
                secondary,
                GtfsGeo.latitude(platform.z()),
                GtfsGeo.longitude(platform.x()),
                GtfsFeed.LOCATION_PLATFORM,
                Optional.of(stationStopId),
                platform.track().isBlank() ? Optional.empty() : Optional.of(platform.track())));
      }
      // 计划股道不在图里（线路未重建、股道刚改）：用车站坐标补一行，RT 里选中的站台才有可引用的 stop_id
      for (String nodeId : extraPlatforms.getOrDefault(key, List.of())) {
        String track = RouteTerminals.platformOf(nodeId);
        out.add(
            new GtfsFeed.Stop(
                ids.stop(nodeId),
                name,
                secondary,
                GtfsGeo.latitude(point.z()),
                GtfsGeo.longitude(point.x()),
                GtfsFeed.LOCATION_PLATFORM,
                Optional.of(stationStopId),
                "-".equals(track) || track.isBlank() ? Optional.empty() : Optional.of(track)));
      }
      if (tripServedStations.contains(key)) {
        // 兜底停靠点只在车次真的可能用到时发布；没有车次的线路不必为它多一行未使用的 stop
        out.add(
            new GtfsFeed.Stop(
                ids.dynamicStop(entry.operator().code(), entry.code()),
                name,
                secondary,
                GtfsGeo.latitude(point.z()),
                GtfsGeo.longitude(point.x()),
                GtfsFeed.LOCATION_PLATFORM,
                Optional.of(stationStopId),
                Optional.empty()));
      }
    }
    out.sort(Comparator.comparing(GtfsFeed.Stop::id));
    return out;
  }

  /** 车站组成员两两之间的换乘（双向）；两端车站都发布了才写。 */
  private void addGroupTransfers(List<GtfsFeed.Transfer> transfers, List<GtfsFeed.Stop> stops) {
    Set<String> published = new HashSet<>();
    for (GtfsFeed.Stop stop : stops) {
      if (stop.station()) {
        published.add(stop.id());
      }
    }
    List<GtfsFeed.Transfer> groupTransfers = new ArrayList<>();
    for (StationDirectory.GroupEntry group : snapshot.groups()) {
      List<StationDirectory.MemberEntry> members = group.members();
      for (StationDirectory.MemberEntry from : members) {
        for (StationDirectory.MemberEntry to : members) {
          if (from.station().id().equals(to.station().id())) {
            continue;
          }
          String fromKey = stationKey(from.station().operator().code(), from.station().code());
          String toKey = stationKey(to.station().operator().code(), to.station().code());
          if (!usedStations.containsKey(fromKey) || !usedStations.containsKey(toKey)) {
            continue;
          }
          String fromStopId = ids.station(from.station().operator().code(), from.station().code());
          String toStopId = ids.station(to.station().operator().code(), to.station().code());
          if (!published.contains(fromStopId) || !published.contains(toStopId)) {
            continue;
          }
          OptionalInt walk =
              to.member().walkSeconds().map(OptionalInt::of).orElse(OptionalInt.empty());
          groupTransfers.add(
              new GtfsFeed.Transfer(
                  fromStopId,
                  toStopId,
                  walk.isPresent() ? GtfsFeed.TRANSFER_MIN_TIME : 0,
                  walk,
                  Optional.empty(),
                  Optional.empty()));
        }
      }
    }
    groupTransfers.sort(
        Comparator.comparing(GtfsFeed.Transfer::fromStopId)
            .thenComparing(GtfsFeed.Transfer::toStopId));
    transfers.addAll(groupTransfers);
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // 工具
  // ─────────────────────────────────────────────────────────────────────────────

  /**
   * stop_times 的时刻必须沿停靠序号不倒退：后一站的到达不早于前一站的发车。
   *
   * <p>时刻表的站间偏移是导入/生成的数据，缺字段或手改都可能出现重叠；GTFS 消费者与校验器会把倒流的时刻当成坏包。这里在发布前拦下这一趟车， 其它车次照常出。
   *
   * @param stopTimes 一趟车（或拆段后的一段）的停站，按停靠序号升序
   */
  static boolean timeOrdered(List<GtfsFeed.StopTime> stopTimes) {
    for (int i = 1; i < stopTimes.size(); i++) {
      if (stopTimes.get(i).arrivalSecondOfDay() < stopTimes.get(i - 1).departureSecondOfDay()) {
        return false;
      }
    }
    return true;
  }

  private static void sortTrips(List<GtfsFeed.Trip> trips) {
    trips.sort(Comparator.comparing(GtfsFeed.Trip::id));
  }

  private static void sortTransfers(List<GtfsFeed.Transfer> transfers) {
    transfers.sort(
        Comparator.comparing(GtfsFeed.Transfer::fromStopId)
            .thenComparing(GtfsFeed.Transfer::toStopId)
            .thenComparing(transfer -> transfer.fromTripId().orElse("")));
  }

  private void warn(String key, String message) {
    if (warnings.size() >= MAX_WARNINGS || !warnedKeys.add(key)) {
      return;
    }
    warnings.add(message);
  }

  private static String stationKey(String operatorCode, String stationCode) {
    return GtfsFeed.stationKey(operatorCode, stationCode);
  }

  private static int routeType(Line line) {
    return switch (line.serviceType()) {
      case LRT -> 0;
      case METRO -> 1;
      case REGIONAL, COMMUTER, EXPRESS -> 2;
    };
  }

  /** 线路色：去掉 {@code #}，只认六位十六进制；认不出时不出色。 */
  static Optional<String> normalizeColor(Optional<String> raw) {
    if (raw == null || raw.isEmpty()) {
      return Optional.empty();
    }
    String hex = raw.get().trim();
    if (hex.startsWith("#")) {
      hex = hex.substring(1);
    }
    return hex.matches("[0-9A-Fa-f]{6}")
        ? Optional.of(hex.toUpperCase(Locale.ROOT))
        : Optional.empty();
  }

  /** 线路色上的文字色：按 WCAG 相对亮度取黑或白。 */
  static String textColor(String color) {
    int rgb = Integer.parseInt(color, 16);
    double luminance =
        0.2126 * linear((rgb >> 16) & 0xFF)
            + 0.7152 * linear((rgb >> 8) & 0xFF)
            + 0.0722 * linear(rgb & 0xFF);
    return luminance > 0.4 ? "000000" : "FFFFFF";
  }

  private static double linear(int channel) {
    double value = channel / 255.0;
    return value <= 0.03928 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // 内部类型
  // ─────────────────────────────────────────────────────────────────────────────

  private record Point(double x, double z) {}

  private record PlatformInfo(String nodeId, String stationKey, double x, double z, String track) {}

  private record FeedLine(Operator operator, Line line) {}

  private record StationStop(
      TimetableStop stop, StationDirectory.StationEntry entry, String stationKey) {}

  private record Headsign(String primary, Optional<String> secondary) {}

  private record Segment(RouteLineChanges.LineRef line, List<StationStop> stops) {}
}
