package org.fetarute.fetaruteTCAddon.web.gtfs;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.api.StationDirectory;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.LineServiceType;
import org.fetarute.fetaruteTCAddon.company.model.LineStatus;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.company.model.StationGroup;
import org.fetarute.fetaruteTCAddon.company.model.StationGroupMember;
import org.fetarute.fetaruteTCAddon.company.model.StationLocation;
import org.fetarute.fetaruteTCAddon.company.model.StationTransferType;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.Timetable;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableRoutePlan;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableStatus;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableStop;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableTrip;

/**
 * GTFS 相关单测的共用现场：两条运营线路、一条检修线路、三座车站（含一条动态站台与一条计划股道）、一条直通运转交路、一张跨零点的时刻表。
 *
 * <p>刻意让主车次在换线站拆段（WS → MT）、在零点后到站（写 {@code 24:xx:xx}），并让另一条交路只有一个停靠站（不该发布）。
 */
public final class GtfsTestFixtures {

  /** 测试时区：与实服一致，没有夏令时。 */
  public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

  /** 生成时刻：2026-10-25 12:00（上海）。 */
  public static final Instant NOW = Instant.parse("2026-10-25T04:00:00Z");

  public static final UUID COMPANY = uuid(1);
  public static final UUID OPERATOR_ID = uuid(2);
  public static final UUID LINE_MT = uuid(3);
  public static final UUID LINE_WS = uuid(4);
  public static final UUID LINE_DS = uuid(5);
  public static final UUID STATION_NTA = uuid(6);
  public static final UUID STATION_HHU = uuid(7);
  public static final UUID STATION_LDF = uuid(8);
  public static final UUID ROUTE_WS = uuid(9);
  public static final UUID ROUTE_CR = uuid(10);
  public static final UUID TIMETABLE_ID = uuid(11);
  public static final UUID TRIP_ID = uuid(12);
  public static final UUID LEG_TRIP_ID = uuid(13);
  public static final UUID TIMETABLE_MAINTENANCE_ID = uuid(14);
  public static final UUID MAINTENANCE_TRIP_ID = uuid(15);
  public static final UUID GROUP_ID = uuid(16);

  /** 只挂在有线路经过、但没有车次停靠的车站：验证 Q4「线路和车站照常出」。 */
  public static final UUID STATION_RVS = uuid(19);

  /** 主车次：23:50 从南头（NTA）发车，零点后到河湾（HHU）与林湾（LDF）。 */
  public static final int DEPARTURE_SECOND = 23 * 3600 + 50 * 60;

  public static final String TRIP_CODE = "WS-001";

  public static final String TIMETABLE_CODE = "WS_TEMP";

  public static final String MAINTENANCE_TRIP_CODE = "DS-001";

  public static final String NTA_PLATFORM = "SURC:S:NTA:1";

  public static final String HHU_PLATFORM = "SURC:S:HHU:1";

  /**
   * 动态站台的占位节点 {@code OP:S:CODE:fromTrack}：与实服一样，它就是起始股道的真实节点、在图里找得到；编表时这一班排到了 {@link #LDF_PLANNED}。
   */
  public static final String LDF_PLACEHOLDER = "SURC:S:LDF:1";

  public static final String LDF_PLANNED = "SURC:S:LDF:2";

  private GtfsTestFixtures() {}

  private static UUID uuid(long value) {
    return new UUID(0L, value);
  }

  /** 示例运营商。 */
  public static Operator operator() {
    return new Operator(
        OPERATOR_ID,
        "SURC",
        COMPANY,
        "示例铁路",
        Optional.of("Sample Rail"),
        Optional.of("#336699"),
        0,
        Optional.empty(),
        Map.of(),
        NOW,
        NOW);
  }

  /** 带坐标与中英文名的车站，供单独搭现场的用例使用。 */
  public static StationDirectory.StationEntry station(
      String code, String name, String secondary, double x, double z) {
    return station(operator(), UUID.randomUUID(), code, name, secondary, x, z);
  }

  /** 现场快照。 */
  public static GtfsSnapshot snapshot() {
    Operator operator = operator();
    List<Line> lines =
        List.of(
            line(LINE_MT, "MT", "地铁线", "Metro Line", LineStatus.ACTIVE),
            line(LINE_WS, "WS", "西线", "West Line", LineStatus.ACTIVE),
            line(LINE_DS, "DS", "检修线", "Depot Line", LineStatus.MAINTENANCE),
            line(uuid(17), "PL", "规划线", "Planned Line", LineStatus.PLANNING));
    List<StationDirectory.StationEntry> stations =
        List.of(
            station(operator, STATION_NTA, "NTA", "南头", "Nam Toa", 100.0, 0.0),
            station(operator, STATION_HHU, "HHU", "河湾", "Ho Wan", 500.0, 200.0),
            station(operator, STATION_LDF, "LDF", "林湾", "Lam Wan", 900.0, -400.0),
            station(operator, STATION_RVS, "RVS", "河岸", "Riverside", 1300.0, 300.0));
    List<GtfsPlatformNode> platforms =
        List.of(
            new GtfsPlatformNode(NTA_PLATFORM, 100.0, 0.0),
            new GtfsPlatformNode(HHU_PLATFORM, 500.0, 200.0),
            new GtfsPlatformNode("SURC:S:HHU:2", 500.0, 210.0),
            new GtfsPlatformNode(LDF_PLACEHOLDER, 900.0, -400.0),
            new GtfsPlatformNode(LDF_PLANNED, 900.0, -410.0),
            new GtfsPlatformNode("SURC:S:RVS:1", 1300.0, 300.0));
    GtfsRouteRef throughRoute = new GtfsRouteRef("SURC", "WS", "WS-1", throughStops());
    GtfsRouteRef createRoute = new GtfsRouteRef("SURC", "MT", "CR-1", createStops());
    Timetable timetable =
        new Timetable(
            TIMETABLE_ID,
            COMPANY,
            OPERATOR_ID,
            LINE_WS,
            TIMETABLE_CODE,
            "西线临时表",
            TimetableStatus.PUBLISHED,
            ZONE,
            0,
            86_400,
            List.of(throughPlan(), createPlan()),
            List.of(throughTrip(), legTrip()),
            List.of(),
            Optional.empty(),
            NOW,
            NOW);
    Timetable maintenance =
        new Timetable(
            TIMETABLE_MAINTENANCE_ID,
            COMPANY,
            OPERATOR_ID,
            LINE_DS,
            "DS_Temp",
            "检修线表",
            TimetableStatus.PUBLISHED,
            ZONE,
            0,
            86_400,
            List.of(throughPlan()),
            List.of(
                new TimetableTrip(
                    MAINTENANCE_TRIP_ID,
                    TIMETABLE_MAINTENANCE_ID,
                    ROUTE_WS,
                    0,
                    MAINTENANCE_TRIP_CODE,
                    6 * 3600,
                    Optional.empty())),
            List.of(),
            Optional.empty(),
            NOW,
            NOW);
    return new GtfsSnapshot(
        Optional.of("sv"),
        ZONE,
        "Fetarute FTCA Universal",
        "https://www.fetarute.org",
        NOW,
        true,
        List.of(operator),
        lines,
        stations,
        List.of(stationGroup(stations)),
        platforms,
        List.of(timetable, maintenance),
        Map.of(ROUTE_WS, throughRoute, ROUTE_CR, createRoute),
        (tripId, stopSequence) ->
            TRIP_ID.equals(tripId) && stopSequence == 3
                ? Optional.of(LDF_PLANNED)
                : Optional.empty(),
        Set.of(STATION_RVS));
  }

  /** 交路 WS-1 按间隔发车时的发车间隔（秒）。 */
  public static final int HEADWAY_SECONDS = 300;

  /** 同一现场，另把交路 WS-1 当作按间隔发车的交路（模板车次与时刻表车次并存）。 */
  public static GtfsSnapshot headwaySnapshot() {
    GtfsSnapshot base = snapshot();
    return new GtfsSnapshot(
        base.serverId(),
        base.zone(),
        base.publisherName(),
        base.publisherUrl(),
        base.generatedAt(),
        base.timetableEnabled(),
        base.operators(),
        base.lines(),
        base.stations(),
        base.groups(),
        base.platforms(),
        base.timetables(),
        base.routes(),
        base.plannedPlatforms(),
        base.stationsOfPublishedLines(),
        base.platformPlansVersion(),
        List.of(new GtfsHeadwayRoute(ROUTE_WS, HEADWAY_SECONDS, throughPlan().stops())));
  }

  /** 现场静态包。 */
  public static GtfsFeed feed() {
    return GtfsFeedBuilder.build(snapshot());
  }

  private static Line line(UUID id, String code, String name, String secondary, LineStatus status) {
    return new Line(
        id,
        code,
        OPERATOR_ID,
        name,
        Optional.of(secondary),
        LineServiceType.METRO,
        Optional.of("#ff0000"),
        status,
        Optional.empty(),
        Map.of(),
        NOW,
        NOW);
  }

  private static StationDirectory.StationEntry station(
      Operator operator, UUID id, String code, String name, String secondary, double x, double z) {
    Station station =
        new Station(
            id,
            code,
            OPERATOR_ID,
            Optional.empty(),
            name,
            Optional.of(secondary),
            Optional.of("Towny"),
            Optional.of(new StationLocation(x, 64.0, z, 0.0f, 0.0f)),
            Optional.empty(),
            Optional.empty(),
            List.of(),
            Map.of(),
            NOW,
            NOW);
    return new StationDirectory.StationEntry(station, operator);
  }

  private static List<RouteStop> throughStops() {
    return List.of(
        stop(ROUTE_WS, 0, NTA_PLATFORM, RouteStopPassType.STOP, ""),
        stop(ROUTE_WS, 1, "SURC:NTA:HHU:1:1", RouteStopPassType.PASS, ""),
        stop(ROUTE_WS, 2, HHU_PLATFORM, RouteStopPassType.STOP, "CHANGE:SURC:MT"),
        stop(
            ROUTE_WS, 3, LDF_PLACEHOLDER, RouteStopPassType.TERMINATE, "DYNAMIC:SURC:S:LDF:[1:2]"));
  }

  private static List<RouteStop> createStops() {
    return List.of(
        stop(ROUTE_CR, 0, "SURC:D:LWN:1", RouteStopPassType.PASS, ""),
        stop(ROUTE_CR, 1, NTA_PLATFORM, RouteStopPassType.STOP, ""));
  }

  private static RouteStop stop(
      UUID routeId, int sequence, String nodeId, RouteStopPassType passType, String notes) {
    return new RouteStop(
        routeId,
        sequence,
        Optional.empty(),
        Optional.of(nodeId),
        Optional.of(20),
        passType,
        notes.isBlank() ? Optional.empty() : Optional.of(notes));
  }

  private static TimetableRoutePlan throughPlan() {
    return new TimetableRoutePlan(
        ROUTE_WS,
        "WS-1",
        RouteOperationType.OPERATION,
        100,
        List.of(
            stop(0, "NTA", NTA_PLATFORM, 0, 20, RouteStopPassType.STOP),
            stop(1, null, "SURC:NTA:HHU:1:1", 300, 300, RouteStopPassType.PASS),
            stop(2, "HHU", HHU_PLATFORM, 600, 620, RouteStopPassType.STOP),
            stop(3, "LDF", LDF_PLACEHOLDER, 1200, 1300, RouteStopPassType.TERMINATE)),
        NTA_PLATFORM,
        LDF_PLACEHOLDER,
        Optional.empty(),
        Optional.empty());
  }

  private static TimetableRoutePlan createPlan() {
    return new TimetableRoutePlan(
        ROUTE_CR,
        "CR-1",
        RouteOperationType.CREATE,
        0,
        List.of(
            stop(0, null, "SURC:D:LWN:1", 0, 0, RouteStopPassType.PASS),
            stop(1, "NTA", NTA_PLATFORM, 600, 620, RouteStopPassType.STOP)),
        "SURC:D:LWN:1",
        NTA_PLATFORM,
        Optional.empty(),
        Optional.empty());
  }

  private static TimetableTrip throughTrip() {
    return new TimetableTrip(
        TRIP_ID, TIMETABLE_ID, ROUTE_WS, 0, TRIP_CODE, DEPARTURE_SECOND, Optional.empty());
  }

  private static TimetableTrip legTrip() {
    return new TimetableTrip(
        LEG_TRIP_ID, TIMETABLE_ID, ROUTE_CR, 1, "CR-001", 5 * 3600, Optional.empty());
  }

  private static TimetableStop stop(
      int sequence,
      String stationCode,
      String nodeId,
      int arrival,
      int departure,
      RouteStopPassType passType) {
    return new TimetableStop(
        sequence,
        Optional.ofNullable(stationCode),
        Optional.of(nodeId),
        arrival,
        departure,
        passType);
  }

  private static StationDirectory.GroupEntry stationGroup(
      List<StationDirectory.StationEntry> stations) {
    StationGroup group =
        new StationGroup(
            GROUP_ID,
            COMPANY,
            "NTA-HHU",
            "南头换乘",
            Optional.of("Nam Toa Interchange"),
            Map.of(),
            NOW,
            NOW);
    StationDirectory.MemberEntry nta =
        new StationDirectory.MemberEntry(
            new StationGroupMember(
                GROUP_ID, STATION_NTA, StationTransferType.OUT_OF_STATION, Optional.empty(), 0),
            stations.get(0));
    StationDirectory.MemberEntry hhu =
        new StationDirectory.MemberEntry(
            new StationGroupMember(
                GROUP_ID, STATION_HHU, StationTransferType.OUT_OF_STATION, Optional.of(180), 1),
            stations.get(1));
    return new StationDirectory.GroupEntry(group, List.of(nta, hhu));
  }
}
