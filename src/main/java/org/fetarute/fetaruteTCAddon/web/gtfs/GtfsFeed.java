package org.fetarute.fetaruteTCAddon.web.gtfs;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * 一份算好的静态 GTFS：各表的领域模型、对外 ID 索引与版本号。
 *
 * <p>它同时是三种消费者的共同事实来源：
 *
 * <ul>
 *   <li>zip 写出器把它排成 CSV 表（见 {@link GtfsFeedTables}）；
 *   <li>GTFS-Realtime 用它的索引把运行时车次对到 {@code trip_id}、把节点对到 {@code stop_id}；
 *   <li>{@code /v1/meta} 读它的版本号。
 * </ul>
 *
 * <p>只发「一列车次的一段」：直通运转在换线站拆成两个 GTFS 车次，第二条起 ID 加 {@code ~2}。段与段之间用 {@code transfers.txt} 的 {@code
 * transfer_type=4} 连起来。停靠时间只列 STOP 与 TERMINATE 的车站，通过站不列。
 *
 * <p>本类不可变；索引在构造时一次建好，任意线程可读。
 */
public final class GtfsFeed {

  /** 地点类型：车站。 */
  public static final int LOCATION_STATION = 1;

  /** 地点类型：站台/股道。 */
  public static final int LOCATION_PLATFORM = 0;

  /** 换乘类型：建议换乘，带最短换乘时间。 */
  public static final int TRANSFER_MIN_TIME = 2;

  /** 换乘类型：坐在车上直通运转。 */
  public static final int TRANSFER_IN_SEAT = 4;

  /**
   * 运营商（agency.txt）。
   *
   * @param id {@code {S}:{OP}}
   * @param operatorCode 运营商代码
   * @param name 中文名
   * @param secondaryName 英文名；没有时为空
   * @param url 运营商地址（统一取发布方地址）
   * @param timezone 服务器时区
   * @param lang 运营商语言（{@code zh}）
   */
  public record Agency(
      String id,
      String operatorCode,
      String name,
      Optional<String> secondaryName,
      String url,
      String timezone,
      String lang) {

    public Agency {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(operatorCode, "operatorCode");
      name = name == null || name.isBlank() ? operatorCode : name.trim();
      secondaryName = Optional.ofNullable(secondaryName).orElse(Optional.empty());
      Objects.requireNonNull(url, "url");
      Objects.requireNonNull(timezone, "timezone");
      lang = lang == null || lang.isBlank() ? "zh" : lang.trim();
    }
  }

  /**
   * 线路（routes.txt）：GTFS 的 route 对应插件的「线路」，不是「交路」。
   *
   * @param id {@code {S}:{OP}:{LINE}}
   * @param agencyId 运营商
   * @param operatorCode 运营商代码
   * @param lineCode 线路代码
   * @param shortName 线路代码
   * @param longName 中文线路名
   * @param secondaryName 英文线路名；没有时为空
   * @param routeType GTFS 标准 {@code route_type}
   * @param color 线路色（六位十六进制，无 {@code #}）
   * @param textColor 线路色上的文字色（黑或白）
   * @param sortOrder 排序
   */
  public record Route(
      String id,
      String agencyId,
      String operatorCode,
      String lineCode,
      String shortName,
      String longName,
      Optional<String> secondaryName,
      int routeType,
      Optional<String> color,
      Optional<String> textColor,
      int sortOrder) {

    public Route {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(agencyId, "agencyId");
      Objects.requireNonNull(operatorCode, "operatorCode");
      Objects.requireNonNull(lineCode, "lineCode");
      shortName = shortName == null ? lineCode : shortName;
      longName = longName == null || longName.isBlank() ? lineCode : longName.trim();
      secondaryName = Optional.ofNullable(secondaryName).orElse(Optional.empty());
      color = Optional.ofNullable(color).orElse(Optional.empty());
      textColor = Optional.ofNullable(textColor).orElse(Optional.empty());
    }
  }

  /**
   * 停靠点（stops.txt）：车站是 {@code location_type=1}，股道与「站台未定」是 {@code location_type=0}。
   *
   * @param id GTFS {@code stop_id}
   * @param name 中文名；股道与车站同名
   * @param secondaryName 英文名；没有时为空
   * @param latitude 纬度
   * @param longitude 经度
   * @param locationType 0 或 1
   * @param parentStation 所属车站；车站本身为空
   * @param platformCode 股道号；车站与「站台未定」为空
   */
  public record Stop(
      String id,
      String name,
      Optional<String> secondaryName,
      double latitude,
      double longitude,
      int locationType,
      Optional<String> parentStation,
      Optional<String> platformCode) {

    public Stop {
      Objects.requireNonNull(id, "id");
      name = name == null || name.isBlank() ? id : name.trim();
      secondaryName = Optional.ofNullable(secondaryName).orElse(Optional.empty());
      parentStation = Optional.ofNullable(parentStation).orElse(Optional.empty());
      platformCode = Optional.ofNullable(platformCode).orElse(Optional.empty());
      if (locationType != LOCATION_STATION && locationType != LOCATION_PLATFORM) {
        throw new IllegalArgumentException("locationType 只能是 0 或 1: " + locationType);
      }
    }

    /** 是否车站行。 */
    public boolean station() {
      return locationType == LOCATION_STATION;
    }
  }

  /**
   * 一段车次的一次停站（stop_times.txt 的一行）。
   *
   * @param stopSequence 交路节点的 0 起下标（GTFS 只要求递增，不要求连续；这样 RT 的 {@code stop_sequence} 与插件同一口径）
   * @param stopId GTFS {@code stop_id}（股道或「站台未定」）
   * @param arrivalSecondOfDay 到站时刻（相对服务日零点，可超过 86400）
   * @param departureSecondOfDay 发车时刻
   * @param pickupType 上车类型：0 正常，1 不上客
   * @param dropOffType 下车类型：0 正常，1 不下客
   */
  public record StopTime(
      int stopSequence,
      String stopId,
      int arrivalSecondOfDay,
      int departureSecondOfDay,
      int pickupType,
      int dropOffType) {

    public StopTime {
      Objects.requireNonNull(stopId, "stopId");
      if (stopSequence < 0) {
        throw new IllegalArgumentException("stopSequence 不能为负");
      }
      if (departureSecondOfDay < arrivalSecondOfDay) {
        throw new IllegalArgumentException("发车不能早于到达");
      }
    }
  }

  /**
   * 一段车次（trips.txt 的一行）。
   *
   * @param id GTFS {@code trip_id}
   * @param routeId 乘客看到的线路
   * @param serviceId 日历服务
   * @param shortName 车次号（同插件的车次号）
   * @param headsign 全程运营终点站名
   * @param headsignSecondary 终点的英文名；没有时为空
   * @param startSecondOfDay 本段首站计划发车（相对服务日零点，可超过 86400）
   * @param timetableId 时刻表 UUID
   * @param timetableCode 时刻表代码
   * @param tripCode 插件里的车次号
   * @param tripUuid 插件里的车次 UUID
   * @param segment 拆段序号（0 起）
   * @param firstStopSequence 本段第一个停靠序号
   * @param lastStopSequence 本段最后一个停靠序号
   * @param stopTimes 本段停站，按 {@code stopSequence} 升序
   * @param headwaySeconds 按间隔发车的模板车次的发车间隔（{@code frequencies.txt}，{@code exact_times=0}）；
   *     时刻表车次为空。模板车次的 {@code timetableId} 与 {@code tripUuid} 都是交路 UUID，{@code tripCode}
   *     是交路代码，各段停站时刻从 0 起
   */
  public record Trip(
      String id,
      String routeId,
      String serviceId,
      String shortName,
      String headsign,
      Optional<String> headsignSecondary,
      int startSecondOfDay,
      UUID timetableId,
      String timetableCode,
      String tripCode,
      UUID tripUuid,
      int segment,
      int firstStopSequence,
      int lastStopSequence,
      List<StopTime> stopTimes,
      OptionalInt headwaySeconds) {

    public Trip {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(routeId, "routeId");
      Objects.requireNonNull(serviceId, "serviceId");
      shortName = shortName == null ? "" : shortName;
      headsign = headsign == null || headsign.isBlank() ? shortName : headsign;
      headsignSecondary = Optional.ofNullable(headsignSecondary).orElse(Optional.empty());
      Objects.requireNonNull(timetableId, "timetableId");
      timetableCode = timetableCode == null ? "" : timetableCode;
      tripCode = tripCode == null ? "" : tripCode;
      Objects.requireNonNull(tripUuid, "tripUuid");
      stopTimes = stopTimes == null ? List.of() : List.copyOf(stopTimes);
      headwaySeconds = headwaySeconds == null ? OptionalInt.empty() : headwaySeconds;
    }

    /** 是否按间隔发车的模板车次。 */
    public boolean frequencyBased() {
      return headwaySeconds.isPresent();
    }

    /** 本段是否包含这个停靠序号。 */
    public boolean covers(int stopSequence) {
      return stopSequence >= firstStopSequence && stopSequence <= lastStopSequence;
    }

    /** 按停靠序号查停站。 */
    public Optional<StopTime> stopTime(int stopSequence) {
      for (StopTime stopTime : stopTimes) {
        if (stopTime.stopSequence() == stopSequence) {
          return Optional.of(stopTime);
        }
      }
      return Optional.empty();
    }
  }

  /**
   * 换乘（transfers.txt 的一行）。
   *
   * @param fromStopId 起点
   * @param toStopId 终点
   * @param type 换乘类型
   * @param minTransferSeconds 最短换乘时间；没有时为空
   * @param fromTripId 直通运转的前一段车次；普通换乘为空
   * @param toTripId 直通运转的后一段车次；普通换乘为空
   */
  public record Transfer(
      String fromStopId,
      String toStopId,
      int type,
      OptionalInt minTransferSeconds,
      Optional<String> fromTripId,
      Optional<String> toTripId) {

    public Transfer {
      Objects.requireNonNull(fromStopId, "fromStopId");
      Objects.requireNonNull(toStopId, "toStopId");
      minTransferSeconds = minTransferSeconds == null ? OptionalInt.empty() : minTransferSeconds;
      fromTripId = Optional.ofNullable(fromTripId).orElse(Optional.empty());
      toTripId = Optional.ofNullable(toTripId).orElse(Optional.empty());
    }
  }

  /** 车次查找键：时刻表 + 车次号（车次号不分大小写）。 */
  private record TripKey(UUID timetableId, String tripCode) {}

  private final String version;
  private final Instant generatedAt;
  private final ZoneId zone;
  private final String serviceId;
  private final LocalDate calendarStart;
  private final LocalDate calendarEnd;
  private final String publisherName;
  private final String publisherUrl;
  private final List<Agency> agencies;
  private final List<Route> routes;
  private final List<Stop> stops;
  private final List<Trip> trips;
  private final List<Transfer> transfers;
  private final List<String> warnings;
  private final Map<String, Agency> agenciesByOperator;
  private final Map<String, Route> routesByLine;
  private final Map<String, Stop> stopsById;
  private final Map<String, String> stopIdsByNode;
  private final Map<String, Stop> stationsByKey;
  private final Map<String, String> dynamicStopIdsByStation;
  private final Map<TripKey, List<Trip>> tripsByTimetableCode;
  private final Map<String, Trip> tripsById;
  private final Map<UUID, List<Trip>> headwayTripsByRoute;

  GtfsFeed(
      String version,
      Instant generatedAt,
      ZoneId zone,
      String serviceId,
      LocalDate calendarStart,
      LocalDate calendarEnd,
      String publisherName,
      String publisherUrl,
      List<Agency> agencies,
      List<Route> routes,
      List<Stop> stops,
      List<Trip> trips,
      List<Transfer> transfers,
      List<String> warnings,
      Map<String, String> stopIdsByNode,
      Map<String, Stop> stationsByKey) {
    this.version = Objects.requireNonNull(version, "version");
    this.generatedAt = Objects.requireNonNull(generatedAt, "generatedAt");
    this.zone = Objects.requireNonNull(zone, "zone");
    this.serviceId = Objects.requireNonNull(serviceId, "serviceId");
    this.calendarStart = Objects.requireNonNull(calendarStart, "calendarStart");
    this.calendarEnd = Objects.requireNonNull(calendarEnd, "calendarEnd");
    this.publisherName = Objects.requireNonNull(publisherName, "publisherName");
    this.publisherUrl = Objects.requireNonNull(publisherUrl, "publisherUrl");
    this.agencies = List.copyOf(agencies);
    this.routes = List.copyOf(routes);
    this.stops = List.copyOf(stops);
    this.trips = List.copyOf(trips);
    this.transfers = List.copyOf(transfers);
    this.warnings = List.copyOf(warnings);
    this.stopIdsByNode = Map.copyOf(stopIdsByNode);
    this.stationsByKey = Map.copyOf(stationsByKey);

    Map<String, Agency> agencyIndex = new HashMap<>();
    for (Agency agency : this.agencies) {
      agencyIndex.put(key(agency.operatorCode()), agency);
    }
    this.agenciesByOperator = Map.copyOf(agencyIndex);

    Map<String, Route> routeIndex = new HashMap<>();
    for (Route route : this.routes) {
      routeIndex.put(lineKey(route.operatorCode(), route.lineCode()), route);
    }
    this.routesByLine = Map.copyOf(routeIndex);

    Map<String, Stop> stopIndex = new HashMap<>();
    for (Stop stop : this.stops) {
      stopIndex.put(stop.id(), stop);
    }
    this.stopsById = Map.copyOf(stopIndex);

    Map<String, String> dynamicStops = new HashMap<>();
    for (Stop stop : this.stops) {
      if (!stop.station() && stop.id().endsWith(":DYN") && stop.parentStation().isPresent()) {
        dynamicStops.putIfAbsent(stop.parentStation().get(), stop.id());
      }
    }
    this.dynamicStopIdsByStation = Map.copyOf(dynamicStops);

    Map<TripKey, List<Trip>> tripIndex = new LinkedHashMap<>();
    Map<String, Trip> byId = new HashMap<>();
    for (Trip trip : this.trips) {
      tripIndex
          .computeIfAbsent(
              new TripKey(trip.timetableId(), key(trip.tripCode())), k -> new ArrayList<>())
          .add(trip);
      byId.put(trip.id(), trip);
    }
    Map<TripKey, List<Trip>> frozenTrips = new LinkedHashMap<>();
    tripIndex.forEach((key, list) -> frozenTrips.put(key, List.copyOf(list)));
    this.tripsByTimetableCode = Map.copyOf(frozenTrips);
    this.tripsById = Map.copyOf(byId);

    Map<UUID, List<Trip>> headwayIndex = new LinkedHashMap<>();
    for (Trip trip : this.trips) {
      if (trip.frequencyBased()) {
        headwayIndex.computeIfAbsent(trip.timetableId(), k -> new ArrayList<>()).add(trip);
      }
    }
    Map<UUID, List<Trip>> frozenHeadway = new LinkedHashMap<>();
    headwayIndex.forEach((route, list) -> frozenHeadway.put(route, List.copyOf(list)));
    this.headwayTripsByRoute = Map.copyOf(frozenHeadway);
  }

  /**
   * 喂给 {@link GtfsFeedBuilder} 的构造入口。
   *
   * @param stopIdsByNode 股道节点 ID 到 {@code stop_id} 的对照（计划股道与实时选台都按它换算）
   * @param stationsByKey 车站键到车站行的对照，键见 {@link #stationKey(String, String)}
   */
  static GtfsFeed of(
      String version,
      Instant generatedAt,
      ZoneId zone,
      String serviceId,
      LocalDate calendarStart,
      LocalDate calendarEnd,
      String publisherName,
      String publisherUrl,
      List<Agency> agencies,
      List<Route> routes,
      List<Stop> stops,
      List<Trip> trips,
      List<Transfer> transfers,
      List<String> warnings,
      Map<String, String> stopIdsByNode,
      Map<String, Stop> stationsByKey) {
    return new GtfsFeed(
        version,
        generatedAt,
        zone,
        serviceId,
        calendarStart,
        calendarEnd,
        publisherName,
        publisherUrl,
        agencies,
        routes,
        stops,
        trips,
        transfers,
        warnings,
        stopIdsByNode,
        stationsByKey);
  }

  /** 内容摘要式版本号：{@code {S}-{yyyyMMdd}-{摘要前 8 位}}。 */
  public String version() {
    return version;
  }

  /** 生成时刻。 */
  public Instant generatedAt() {
    return generatedAt;
  }

  /** 服务器时区（{@code agency_timezone}）。 */
  public ZoneId zone() {
    return zone;
  }

  /** 日历服务 ID。 */
  public String serviceId() {
    return serviceId;
  }

  /** 日历起点。 */
  public LocalDate calendarStart() {
    return calendarStart;
  }

  /** 日历终点。 */
  public LocalDate calendarEnd() {
    return calendarEnd;
  }

  /** 发布方名称。 */
  public String publisherName() {
    return publisherName;
  }

  /** 发布方地址。 */
  public String publisherUrl() {
    return publisherUrl;
  }

  /** 运营商，按代码排序。 */
  public List<Agency> agencies() {
    return agencies;
  }

  /** 线路，按运营商与线路代码排序。 */
  public List<Route> routes() {
    return routes;
  }

  /** 停靠点（车站、股道、站台未定），按 {@code stop_id} 排序。 */
  public List<Stop> stops() {
    return stops;
  }

  /** 车次（拆段后的一段一行），按 {@code trip_id} 排序。 */
  public List<Trip> trips() {
    return trips;
  }

  /** 换乘，按起点、终点排序。 */
  public List<Transfer> transfers() {
    return transfers;
  }

  /** 导出时跳过的数据与原因（每类一条），供调用方记一次日志，不影响整包。 */
  public List<String> warnings() {
    return warnings;
  }

  /** 按运营商代码查运营商（不分大小写）。 */
  public Optional<Agency> agency(String operatorCode) {
    return Optional.ofNullable(agenciesByOperator.get(key(operatorCode)));
  }

  /** 按运营商代码 + 线路代码查线路（不分大小写）。 */
  public Optional<Route> route(String operatorCode, String lineCode) {
    return Optional.ofNullable(routesByLine.get(lineKey(operatorCode, lineCode)));
  }

  /** 按 {@code stop_id} 查停靠点。 */
  public Optional<Stop> stop(String stopId) {
    return stopId == null ? Optional.empty() : Optional.ofNullable(stopsById.get(stopId));
  }

  /** 按运营商代码 + 站码查车站行（不分大小写）。 */
  public Optional<Stop> station(String operatorCode, String stationCode) {
    return Optional.ofNullable(stationsByKey.get(stationKey(operatorCode, stationCode)));
  }

  /** 按股道节点 ID 查 {@code stop_id}。 */
  public Optional<String> stopIdOfNode(String nodeId) {
    return nodeId == null
        ? Optional.empty()
        : Optional.ofNullable(stopIdsByNode.get(nodeId.trim()));
  }

  /**
   * 某座车站的「站台未定」停靠点。
   *
   * <p>实时源只知道股道时的兜底：动态站台的占位节点解析出车站后，若那条股道没有单独发布，就用车站级的 {@code …:DYN}。
   *
   * @param stationStopId 车站行的 {@code stop_id}
   * @return 车站级停靠点；车站不存在时为空
   */
  public Optional<String> dynamicStopId(String stationStopId) {
    return stationStopId == null
        ? Optional.empty()
        : Optional.ofNullable(dynamicStopIdsByStation.get(stationStopId));
  }

  /** 某趟车次拆段后的各段，按分段序号排序；没有时为空。 */
  public List<Trip> trips(UUID timetableId, String tripCode) {
    if (timetableId == null || tripCode == null || tripCode.isBlank()) {
      return List.of();
    }
    return tripsByTimetableCode.getOrDefault(new TripKey(timetableId, key(tripCode)), List.of());
  }

  /** 按 {@code trip_id} 查车次。 */
  public Optional<Trip> trip(String tripId) {
    return tripId == null ? Optional.empty() : Optional.ofNullable(tripsById.get(tripId));
  }

  /**
   * 某一时刻运行到交路下标 {@code routeIndex} 的车次属于哪一段。
   *
   * <p>换线站属于两段：前一段以它为终点，后一段以它为起点。列车到达（{@code routeIndex >= 换线站下标}）之后按后一段对乘客运营，这里也取后一段。
   *
   * @param routeIndex 运行时进度下标
   * @return 对应的段；没有这趟车时为空
   */
  public Optional<Trip> tripForRouteIndex(UUID timetableId, String tripCode, int routeIndex) {
    return segmentAt(trips(timetableId, tripCode), routeIndex);
  }

  /** 按间隔发车的交路的模板车次（拆段后的各段，按分段序号排序）；不是这种交路时为空。 */
  public List<Trip> headwayTrips(UUID routeId) {
    return routeId == null ? List.of() : headwayTripsByRoute.getOrDefault(routeId, List.of());
  }

  /** 同 {@link #tripForRouteIndex}，查按间隔发车的交路的模板车次。 */
  public Optional<Trip> headwayTripForRouteIndex(UUID routeId, int routeIndex) {
    return segmentAt(headwayTrips(routeId), routeIndex);
  }

  private static Optional<Trip> segmentAt(List<Trip> segments, int routeIndex) {
    if (segments.isEmpty()) {
      return Optional.empty();
    }
    Trip selected = segments.get(0);
    for (Trip trip : segments) {
      if (routeIndex >= trip.firstStopSequence()) {
        selected = trip;
      }
    }
    return Optional.of(selected);
  }

  /** 车站键：运营商代码与站码都归一成大写，构建与查询共用。 */
  static String stationKey(String operatorCode, String stationCode) {
    return key(operatorCode) + "|" + key(stationCode);
  }

  /** 线路键：运营商代码与线路代码都归一成大写。 */
  static String lineKey(String operatorCode, String lineCode) {
    return key(operatorCode) + "|" + key(lineCode);
  }

  private static String key(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }
}
