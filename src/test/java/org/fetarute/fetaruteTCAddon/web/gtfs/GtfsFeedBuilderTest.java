package org.fetarute.fetaruteTCAddon.web.gtfs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.api.StationDirectory;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.LineServiceType;
import org.fetarute.fetaruteTCAddon.company.model.LineStatus;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLineChanges;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.Timetable;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableRoutePlan;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableStatus;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableStop;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableTrip;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.VehicleDuty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("静态 GTFS 生成")
class GtfsFeedBuilderTest {

  private final GtfsFeed feed = GtfsTestFixtures.feed();

  private static List<String> column(GtfsTable table, String name) {
    int index = table.columns().indexOf(name);
    assertTrue(index >= 0, "没有列 " + name);
    return table.rows().stream().map(row -> row.get(index)).toList();
  }

  private List<String> values(String tableName, String column) {
    GtfsTable table =
        GtfsFeedTables.tables(feed).stream()
            .filter(candidate -> candidate.name().equals(tableName))
            .findFirst()
            .orElseThrow();
    return column(table, column);
  }

  @Test
  @DisplayName("直通运转在换线站拆成两段：前段 WS、后段 MT，第二段 ID 加 ~2")
  void splitsThroughRunningTrips() {
    List<GtfsFeed.Trip> segments =
        feed.trips(GtfsTestFixtures.TIMETABLE_ID, GtfsTestFixtures.TRIP_CODE);

    assertEquals(2, segments.size());
    assertEquals("sv:SURC:WS:WS_TEMP:WS-001", segments.get(0).id());
    assertEquals("sv:SURC:WS:WS_TEMP:WS-001~2", segments.get(1).id());
    assertEquals("sv:SURC:WS", segments.get(0).routeId());
    assertEquals("sv:SURC:MT", segments.get(1).routeId());
    assertEquals(
        List.of(0, 2),
        segments.get(0).stopTimes().stream().map(GtfsFeed.StopTime::stopSequence).toList());
    assertEquals(
        List.of(2, 3),
        segments.get(1).stopTimes().stream().map(GtfsFeed.StopTime::stopSequence).toList());
    assertEquals("林湾", segments.get(0).headsign(), "两段的终点都写全程终点");
    assertEquals(Optional.of("Lam Wan"), segments.get(0).headsignSecondary());
  }

  @Test
  @DisplayName("跨零点写 24:xx:xx；计划股道与动态站台兜底各就各位")
  void writesCrossMidnightTimesAndDynamicPlatforms() {
    GtfsTable stopTimes =
        GtfsFeedTables.tables(feed).stream()
            .filter(table -> table.name().equals("stop_times"))
            .findFirst()
            .orElseThrow();

    List<String> trips = column(stopTimes, "trip_id");
    List<String> arrivals = column(stopTimes, "arrival_time");
    List<String> departures = column(stopTimes, "departure_time");
    List<String> stopIds = column(stopTimes, "stop_id");
    int first = trips.indexOf("sv:SURC:WS:WS_TEMP:WS-001");
    int second = trips.indexOf("sv:SURC:WS:WS_TEMP:WS-001~2");

    assertEquals("23:50:00", arrivals.get(first));
    assertEquals("23:50:20", departures.get(first));
    assertEquals("24:00:00", arrivals.get(first + 1), "换线站 HHU 在零点后到站");
    assertEquals("sv:SURC:S:HHU:1", stopIds.get(first + 1));
    assertEquals("24:10:00", arrivals.get(second + 1), "动态站台林湾");
    assertEquals("sv:SURC:S:LDF:2", stopIds.get(second + 1), "编表时排了计划股道就用它，不用同名的占位股道");
    assertEquals("sv:SURC:S:HHU:1", stopIds.get(second), "换线站同时属于两段");
  }

  @Test
  @DisplayName("整趟的首站不下客、末站不上客；拆段边界两侧都正常上下客")
  void marksPickupAndDropOffOnlyAtTheRunEnds() {
    GtfsFeed.Trip first =
        feed.trips(GtfsTestFixtures.TIMETABLE_ID, GtfsTestFixtures.TRIP_CODE).get(0);
    GtfsFeed.Trip second =
        feed.trips(GtfsTestFixtures.TIMETABLE_ID, GtfsTestFixtures.TRIP_CODE).get(1);

    assertEquals(1, first.stopTimes().get(0).dropOffType());
    assertEquals(0, first.stopTimes().get(0).pickupType());
    assertEquals(0, first.stopTimes().get(1).dropOffType(), "换线站可以下车");
    assertEquals(0, first.stopTimes().get(1).pickupType());
    assertEquals(0, second.stopTimes().get(0).dropOffType());
    assertEquals(1, second.stopTimes().get(1).pickupType(), "全程终点不上客");
  }

  @Test
  @DisplayName("换乘：直通运转 transfer_type=4；车站组成员两两互转，步行秒数写在目标成员上")
  void writesTransfers() {
    List<GtfsFeed.Transfer> transfers = feed.transfers();

    GtfsFeed.Transfer inSeat =
        transfers.stream()
            .filter(transfer -> transfer.type() == GtfsFeed.TRANSFER_IN_SEAT)
            .findFirst()
            .orElseThrow();
    assertEquals("sv:SURC:S:HHU:1", inSeat.fromStopId());
    assertEquals("sv:SURC:S:HHU:1", inSeat.toStopId());
    assertEquals(Optional.of("sv:SURC:WS:WS_TEMP:WS-001"), inSeat.fromTripId());
    assertEquals(Optional.of("sv:SURC:WS:WS_TEMP:WS-001~2"), inSeat.toTripId());

    GtfsFeed.Transfer toHhu =
        transfers.stream()
            .filter(
                transfer ->
                    transfer.fromStopId().equals("sv:SURC:S:NTA")
                        && transfer.toStopId().equals("sv:SURC:S:HHU"))
            .findFirst()
            .orElseThrow();
    assertEquals(GtfsFeed.TRANSFER_MIN_TIME, toHhu.type());
    assertEquals(180, toHhu.minTransferSeconds().getAsInt());
    GtfsFeed.Transfer toNta =
        transfers.stream()
            .filter(
                transfer ->
                    transfer.fromStopId().equals("sv:SURC:S:HHU")
                        && transfer.toStopId().equals("sv:SURC:S:NTA"))
            .findFirst()
            .orElseThrow();
    assertEquals(0, toNta.type(), "目标站没写步行秒数：只标推荐换乘");
    assertTrue(toNta.minTransferSeconds().isEmpty());
  }

  @Test
  @DisplayName("检修线路进 routes.txt 但不出车次；规划线路既不进线路也不出车次")
  void publishesMaintenanceRoutesWithoutTrips() {
    assertTrue(feed.route("SURC", "DS").isPresent(), "检修线路照常发布");
    assertTrue(feed.route("SURC", "PL").isEmpty(), "规划线路不发布");
    assertTrue(
        feed.trips().stream()
            .noneMatch(trip -> trip.shortName().equals(GtfsTestFixtures.MAINTENANCE_TRIP_CODE)));
  }

  @Test
  @DisplayName("交路计划里只有一个停靠站的车次不进包（纯走行与出库段）")
  void skipsTripsWithADepotOnlyStop() {
    assertTrue(feed.trips(GtfsTestFixtures.TIMETABLE_ID, "CR-001").isEmpty());
  }

  @Test
  @DisplayName("stops.txt 发布车站的全部分股道与车站级兜底；股道挂在车站下")
  void publishesStationsPlatformsAndDynamicStops() {
    assertTrue(feed.station("SURC", "HHU").isPresent());
    assertEquals(
        2,
        feed.stops().stream()
            .filter(stop -> stop.parentStation().equals(Optional.of("sv:SURC:S:HHU")))
            .filter(stop -> stop.platformCode().isPresent())
            .count(),
        "HHU 的两条股道都发布，即使只有一条有车停");
    assertTrue(feed.dynamicStopId("sv:SURC:S:LDF").isPresent());
    assertTrue(feed.stopIdOfNode(GtfsTestFixtures.LDF_PLANNED).isPresent(), "计划股道补了停靠点");
    assertTrue(feed.stop("sv:SURC:S:NTA").orElseThrow().station());
    assertEquals(0, feed.stop("sv:SURC:S:NTA:1").orElseThrow().locationType());
    assertEquals(Optional.of("1"), feed.stop("sv:SURC:S:NTA:1").orElseThrow().platformCode());
  }

  @Test
  @DisplayName("有线路经过、暂时没有车次停靠的车站也发布车站与股道（Q4）")
  void publishesStationsOfLinesWithoutTimetables() {
    assertTrue(feed.station("SURC", "RVS").isPresent());
    assertTrue(feed.stop("sv:SURC:S:RVS:1").isPresent());
    assertTrue(
        feed.stops().stream().noneMatch(stop -> stop.id().equals("sv:SURC:S:RVS:DYN")),
        "没有车次的车站不需要 DYN 兜底");
  }

  @Test
  @DisplayName("route_type、线路色与文字色按标准取值")
  void mapsRouteTypeAndColours() {
    GtfsFeed.Route route = feed.route("SURC", "MT").orElseThrow();

    assertEquals(1, route.routeType(), "METRO → 地铁");
    assertEquals(Optional.of("FF0000"), route.color());
    assertEquals(Optional.of("FFFFFF"), route.textColor(), "红底用白字");
  }

  @Test
  @DisplayName("英文名写进 translations.txt；中文与英文相同时不写")
  void writesTranslations() {
    List<String> recordIds = values("translations", "record_id");
    List<String> translations = values("translations", "translation");

    assertTrue(recordIds.contains("sv:SURC:S:HHU"));
    assertTrue(translations.contains("Ho Wan"));
    assertTrue(recordIds.contains("sv:SURC:WS:WS_TEMP:WS-001"));
    assertTrue(translations.contains("Lam Wan"));
  }

  @Test
  @DisplayName("feed_version 是 服务器-内容摘要，并且写进 feed_info")
  void writesFeedVersion() {
    assertTrue(feed.version().matches("sv-[0-9a-f]{12}"), feed.version());
    assertEquals(feed.version(), values("feed_info", "feed_version").get(0));
    assertEquals("sv:DAILY", feed.serviceId());
    assertEquals("20261024", values("calendar", "start_date").get(0));
    assertEquals("20261124", values("calendar", "end_date").get(0));
  }

  @Test
  @DisplayName("车型变体：交路带车型时按该车型的站间时分写 stop_times")
  void usesTheConsistVariantTimetable() {
    UUID lineId = UUID.randomUUID();
    UUID routeId = UUID.randomUUID();
    UUID timetableId = UUID.randomUUID();
    UUID tripId = UUID.randomUUID();
    UUID dutyId = UUID.randomUUID();
    Line line =
        new Line(
            lineId,
            "MT",
            GtfsTestFixtures.OPERATOR_ID,
            "地铁线",
            Optional.of("Metro Line"),
            LineServiceType.METRO,
            Optional.empty(),
            LineStatus.ACTIVE,
            Optional.empty(),
            Map.of(),
            GtfsTestFixtures.NOW,
            GtfsTestFixtures.NOW);
    StationDirectory.StationEntry nta =
        GtfsTestFixtures.station("NTA", "南头", "Nam Toa", 100.0, 0.0);
    StationDirectory.StationEntry hhu =
        GtfsTestFixtures.station("HHU", "河湾", "Ho Wan", 500.0, 200.0);
    List<RouteStop> routeStops =
        List.of(
            new RouteStop(
                routeId,
                0,
                Optional.empty(),
                Optional.of("SURC:S:NTA:1"),
                Optional.of(20),
                RouteStopPassType.STOP,
                Optional.empty()),
            new RouteStop(
                routeId,
                1,
                Optional.empty(),
                Optional.of("SURC:S:HHU:1"),
                Optional.of(20),
                RouteStopPassType.TERMINATE,
                Optional.empty()));
    TimetableRoutePlan base =
        new TimetableRoutePlan(
            routeId,
            "MT-1",
            RouteOperationType.OPERATION,
            100,
            List.of(
                new TimetableStop(
                    0,
                    Optional.of("NTA"),
                    Optional.of("SURC:S:NTA:1"),
                    0,
                    20,
                    RouteStopPassType.STOP),
                new TimetableStop(
                    1,
                    Optional.of("HHU"),
                    Optional.of("SURC:S:HHU:1"),
                    600,
                    620,
                    RouteStopPassType.TERMINATE)),
            "SURC:S:NTA:1",
            "SURC:S:HHU:1",
            Optional.empty(),
            Optional.empty());
    TimetableRoutePlan variant =
        base.asVariant(
                UUID.randomUUID(),
                new TimetableRoutePlan.ConsistVariant("4car", routeId, 10.0, 3.0),
                List.of(
                    new TimetableStop(
                        0,
                        Optional.of("NTA"),
                        Optional.of("SURC:S:NTA:1"),
                        0,
                        20,
                        RouteStopPassType.STOP),
                    new TimetableStop(
                        1,
                        Optional.of("HHU"),
                        Optional.of("SURC:S:HHU:1"),
                        760,
                        780,
                        RouteStopPassType.TERMINATE)))
            .withRouteId(routeId);
    TimetableTrip trip =
        new TimetableTrip(
            tripId, timetableId, routeId, 0, "MT-1-001", 10 * 3600, Optional.of(dutyId));
    VehicleDuty duty =
        new VehicleDuty(
            dutyId,
            timetableId,
            0,
            "D1",
            "SURC:D:LWN:1",
            "SURC:D:LWN:1",
            Optional.empty(),
            Optional.empty(),
            List.of(tripId),
            9 * 3600,
            11 * 3600,
            12 * 3600,
            VehicleDuty.CloseReason.HORIZON_END,
            Optional.of("4car"));
    Timetable timetable =
        new Timetable(
            timetableId,
            GtfsTestFixtures.COMPANY,
            GtfsTestFixtures.OPERATOR_ID,
            lineId,
            "MT_Temp",
            "地铁临时表",
            TimetableStatus.PUBLISHED,
            GtfsTestFixtures.ZONE,
            0,
            86_400,
            List.of(base, variant),
            List.of(trip),
            List.of(duty),
            Optional.empty(),
            GtfsTestFixtures.NOW,
            GtfsTestFixtures.NOW);
    GtfsSnapshot snapshot =
        new GtfsSnapshot(
            Optional.of("sv"),
            GtfsTestFixtures.ZONE,
            "",
            "",
            GtfsTestFixtures.NOW,
            true,
            List.of(GtfsTestFixtures.operator()),
            List.of(line),
            List.of(nta, hhu),
            List.of(),
            List.of(
                new GtfsPlatformNode("SURC:S:NTA:1", 100.0, 0.0),
                new GtfsPlatformNode("SURC:S:HHU:1", 500.0, 200.0)),
            List.of(timetable),
            Map.of(routeId, new GtfsRouteRef("SURC", "MT", "MT-1", routeStops)),
            GtfsPlatformPlans.none());

    GtfsFeed.Trip built = GtfsFeedBuilder.build(snapshot).trips().get(0);

    assertEquals(10 * 3600 + 760, built.stopTimes().get(1).arrivalSecondOfDay());
    assertEquals(10 * 3600 + 780, built.stopTimes().get(1).departureSecondOfDay());
  }

  @Test
  @DisplayName("stop_times 的时刻必须沿停靠序号不倒退")
  void detectsNonMonotonicStopTimes() {
    GtfsFeed.StopTime first = new GtfsFeed.StopTime(0, "a", 0, 20, 0, 1);
    GtfsFeed.StopTime second = new GtfsFeed.StopTime(1, "b", 100, 120, 0, 0);
    GtfsFeed.StopTime third = new GtfsFeed.StopTime(2, "c", 140, 160, 0, 0);

    assertTrue(GtfsFeedBuilder.timeOrdered(List.of(first, second, third)));
    assertFalse(GtfsFeedBuilder.timeOrdered(List.of(first, third, second)));
    assertTrue(GtfsFeedBuilder.timeOrdered(List.of(first)));
  }

  @Test
  @DisplayName("每张表的每一行都与表头等长")
  void tableRowsMatchTheirHeaders() {
    for (GtfsTable table : GtfsFeedTables.tables(feed)) {
      for (List<String> row : table.rows()) {
        assertEquals(table.columns().size(), row.size(), table.name());
      }
    }
  }

  @Test
  @DisplayName("zip 由同一份 feed 生成两次逐字节一致")
  void zipIsDeterministic() {
    byte[] first = GtfsFeedTables.zip(feed);
    byte[] second = GtfsFeedTables.zip(feed);

    assertArrayEquals(first, second);
    assertTrue(first.length > 0);
  }

  @Test
  @DisplayName("换线站紧挨着换两次时不拆出只有一站的段；终点换线不拆")
  void dropsSegmentsThatWouldOnlyHoldOneStop() {
    RouteLineChanges.LineRef ws = new RouteLineChanges.LineRef("SURC", "WS");
    RouteLineChanges.LineRef mt = new RouteLineChanges.LineRef("SURC", "MT");
    RouteLineChanges.LineRef is = new RouteLineChanges.LineRef("SURC", "IS");

    assertEquals(List.of(1), GtfsFeedBuilder.boundaries(List.of(ws, mt, mt, mt)));
    assertEquals(List.of(1), GtfsFeedBuilder.boundaries(List.of(ws, mt, is, is)), "相邻换线只留一处");
    assertTrue(GtfsFeedBuilder.boundaries(List.of(ws, mt)).isEmpty(), "末段只有一站：并回前一段");
    assertFalse(GtfsFeedBuilder.boundaries(List.of(ws, ws, ws)).contains(0));
  }

  @Test
  @DisplayName("动态站台没有计划股道时用车站级 DYN：占位节点与起始股道同名，不能当成固定站台")
  void dynamicStopWithoutPlanUsesStationLevelStop() {
    GtfsFeed noPlan = GtfsFeedBuilder.build(withPlans(GtfsPlatformPlans.none(), Duration.ZERO));
    GtfsFeed.Trip second =
        noPlan.trips(GtfsTestFixtures.TIMETABLE_ID, GtfsTestFixtures.TRIP_CODE).get(1);

    assertEquals("sv:SURC:S:LDF:DYN", second.stopTimes().get(1).stopId());
    assertEquals("sv:SURC:S:HHU:1", second.stopTimes().get(0).stopId(), "固定站台照旧用图里的股道");
  }

  @Test
  @DisplayName("交路停靠表与时刻表不对齐时，按交路里指向同站的 DYNAMIC 认出动态站台，不把占位节点当固定站台")
  void dynamicStopIsRecognisedWhenTheRouteNoLongerAligns() {
    GtfsSnapshot base = withPlans(GtfsPlatformPlans.none(), Duration.ZERO);
    GtfsRouteRef route = base.routes().get(GtfsTestFixtures.ROUTE_WS);
    List<RouteStop> edited = new java.util.ArrayList<>(route.stops());
    edited.add(1, edited.get(1));
    Map<UUID, GtfsRouteRef> routes = new java.util.HashMap<>(base.routes());
    routes.put(
        GtfsTestFixtures.ROUTE_WS,
        new GtfsRouteRef(route.operatorCode(), route.lineCode(), route.routeCode(), edited));
    GtfsSnapshot misaligned =
        new GtfsSnapshot(
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
            routes,
            base.plannedPlatforms(),
            base.stationsOfPublishedLines(),
            base.platformPlansVersion());

    List<GtfsFeed.StopTime> stopTimes =
        GtfsFeedBuilder.build(misaligned)
            .trips(GtfsTestFixtures.TIMETABLE_ID, GtfsTestFixtures.TRIP_CODE)
            .get(0)
            .stopTimes();
    GtfsFeed.StopTime ldf = stopTimes.get(stopTimes.size() - 1);

    assertEquals(3, ldf.stopSequence());
    assertEquals("sv:SURC:S:LDF:DYN", ldf.stopId());
    assertEquals("sv:SURC:S:NTA:1", stopTimes.get(0).stopId(), "固定站台不受影响");
  }

  @Test
  @DisplayName("内容没变时隔天版本号不变，日历窗口照常往后滚")
  void feedVersionIgnoresTheDate() {
    GtfsFeed nextDay =
        GtfsFeedBuilder.build(
            withPlans(GtfsTestFixtures.snapshot().plannedPlatforms(), Duration.ofDays(1)));

    assertEquals(feed.version(), nextDay.version());
    assertEquals(feed.calendarStart().plusDays(1), nextDay.calendarStart());
  }

  private static GtfsSnapshot withPlans(GtfsPlatformPlans plans, Duration shift) {
    GtfsSnapshot base = GtfsTestFixtures.snapshot();
    return new GtfsSnapshot(
        base.serverId(),
        base.zone(),
        base.publisherName(),
        base.publisherUrl(),
        base.generatedAt().plus(shift),
        base.timetableEnabled(),
        base.operators(),
        base.lines(),
        base.stations(),
        base.groups(),
        base.platforms(),
        base.timetables(),
        base.routes(),
        plans,
        base.stationsOfPublishedLines(),
        base.platformPlansVersion());
  }
}
