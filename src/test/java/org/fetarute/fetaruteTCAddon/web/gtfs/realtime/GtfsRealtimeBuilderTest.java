package org.fetarute.fetaruteTCAddon.web.gtfs.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.api.event.TimetableTripCancelledEvent;
import org.fetarute.fetaruteTCAddon.api.timetable.TimetableApi;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainLoad;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainRuntimeSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLineChanges;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.display.pids.bulletin.PidsBulletin;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsDirectory;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsLineStatus;
import org.fetarute.fetaruteTCAddon.web.gtfs.GtfsFeed;
import org.fetarute.fetaruteTCAddon.web.gtfs.GtfsFeedBuilder;
import org.fetarute.fetaruteTCAddon.web.gtfs.GtfsGeo;
import org.fetarute.fetaruteTCAddon.web.gtfs.GtfsTestFixtures;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.Alert;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.AlertCause;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.AlertEffect;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.AlertSeverity;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.Entity;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.OccupancyStatus;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.StopScheduleRelationship;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.TripUpdate;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.VehiclePosition;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.VehicleStopStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GTFS-Realtime 生成")
class GtfsRealtimeBuilderTest {

  private static final GtfsFeed FEED = GtfsTestFixtures.feed();
  private static final Instant NOW = GtfsTestFixtures.NOW;
  private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 10, 25);
  private static final UUID WORLD = UUID.randomUUID();
  private static final String TRAIN = "TRAIN-1";
  private static final long SERVICE_DAY_BASE =
      SERVICE_DATE.atStartOfDay(GtfsTestFixtures.ZONE).toInstant().getEpochSecond();

  private static GtfsRealtimeBuilder builder(GtfsRealtimeSnapshot snapshot) {
    return new GtfsRealtimeBuilder(FEED, snapshot, NOW, key -> key);
  }

  private static GtfsRealtimeSnapshot snapshot(
      List<TimetableApi.TrainAssignment> assignments,
      List<TimetableApi.CancelledTrip> cancellations,
      Map<String, TrainRuntimeSnapshot> trains,
      Set<String> outOfService,
      List<PidsBulletin> bulletins,
      java.util.function.BiFunction<String, String, Optional<PidsLineStatus>> lineStatus,
      GtfsRealtimeSnapshot.SkippedStopsLookup skipped) {
    return snapshot(
        assignments,
        cancellations,
        trains,
        outOfService,
        bulletins,
        lineStatus,
        skipped,
        GtfsRealtimeSnapshot.PlacedStopsLookup.none());
  }

  private static GtfsRealtimeSnapshot snapshot(
      List<TimetableApi.TrainAssignment> assignments,
      List<TimetableApi.CancelledTrip> cancellations,
      Map<String, TrainRuntimeSnapshot> trains,
      Set<String> outOfService,
      List<PidsBulletin> bulletins,
      java.util.function.BiFunction<String, String, Optional<PidsLineStatus>> lineStatus,
      GtfsRealtimeSnapshot.SkippedStopsLookup skipped,
      GtfsRealtimeSnapshot.PlacedStopsLookup placed) {
    return new GtfsRealtimeSnapshot(
        NOW,
        assignments,
        cancellations,
        trains,
        outOfService,
        Set.of(),
        bulletins,
        lineStatus,
        skipped,
        placed);
  }

  /** 只跑运行中车次时的快照：没有取消、公告与越站。 */
  private static GtfsRealtimeSnapshot running(
      TimetableApi.TrainAssignment assignment,
      TrainRuntimeSnapshot train,
      List<TimetableApi.CancelledTrip> cancellations,
      GtfsRealtimeSnapshot.PlacedStopsLookup placed) {
    return snapshot(
        List.of(assignment),
        cancellations,
        Map.of(TRAIN, train),
        Set.of(),
        List.of(),
        (operator, line) -> Optional.empty(),
        GtfsRealtimeSnapshot.SkippedStopsLookup.none(),
        placed);
  }

  /** 运行中车次加上交路后续车次来源。 */
  private static GtfsRealtimeSnapshot withFollowing(
      TimetableApi.TrainAssignment assignment,
      TrainRuntimeSnapshot train,
      GtfsRealtimeSnapshot.FollowingTripsLookup following) {
    return new GtfsRealtimeSnapshot(
        NOW,
        List.of(assignment),
        List.of(),
        Map.of(TRAIN, train),
        Set.of(),
        Set.of(),
        List.of(),
        (operator, line) -> Optional.empty(),
        GtfsRealtimeSnapshot.SkippedStopsLookup.none(),
        GtfsRealtimeSnapshot.PlacedStopsLookup.none(),
        following);
  }

  private static TrainRuntimeSnapshot inTransit() {
    return runtime(
        0,
        Optional.of(NodeId.of("SURC:NTA:HHU:1:1")),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  private static long time(GtfsRealtimeFeed.StopTimeUpdate update, boolean arrival) {
    return (arrival ? update.arrival() : update.departure()).orElseThrow().time().orElseThrow();
  }

  @Test
  @DisplayName("逐站推算：下一站按预计晚点，往后按表定站间时分推，直通车的后一段接着推")
  void propagatesThroughTheRestOfTheTrip() {
    GtfsRealtimeFeed.FeedMessage message =
        builder(
                withFollowing(
                    assignment(0, 2, GtfsTestFixtures.HHU_PLATFORM, 0, 120),
                    inTransit(),
                    GtfsRealtimeSnapshot.FollowingTripsLookup.none()))
            .tripUpdates();

    long start = SERVICE_DAY_BASE + GtfsTestFixtures.DEPARTURE_SECOND;
    TripUpdate first =
        entity(message, "tu:sv:SURC:WS:WS_TEMP:WS-001:20261025").tripUpdate().orElseThrow();
    GtfsRealtimeFeed.StopTimeUpdate hhu = first.stopTimeUpdates().get(1);
    assertEquals(start + 600 + 120, time(hhu, true));
    assertEquals(start + 620 + 120, time(hhu, false));

    TripUpdate second =
        entity(message, "tu:sv:SURC:WS:WS_TEMP:WS-001~2:20261025").tripUpdate().orElseThrow();
    assertEquals(2, second.stopTimeUpdates().size(), "换线站与终点");
    assertEquals(start + 600 + 120, time(second.stopTimeUpdates().get(0), true));
    GtfsRealtimeFeed.StopTimeUpdate ldf = second.stopTimeUpdates().get(1);
    assertEquals(3, ldf.stopSequence());
    assertEquals(start + 1200 + 120, time(ldf, true), "站间时分照表，晚点原样带到终点");
    assertEquals(start + 1300 + 120, time(ldf, false));
  }

  @Test
  @DisplayName("早到的车按表等点：下一站可以早到，但不早于表定发车，往后各站都是准点")
  void earlyTrainsHoldForTheTimetable() {
    GtfsRealtimeFeed.FeedMessage message =
        builder(
                withFollowing(
                    assignment(0, 2, GtfsTestFixtures.HHU_PLATFORM, 0, -60),
                    inTransit(),
                    GtfsRealtimeSnapshot.FollowingTripsLookup.none()))
            .tripUpdates();

    long start = SERVICE_DAY_BASE + GtfsTestFixtures.DEPARTURE_SECOND;
    GtfsRealtimeFeed.StopTimeUpdate hhu =
        entity(message, "tu:sv:SURC:WS:WS_TEMP:WS-001:20261025")
            .tripUpdate()
            .orElseThrow()
            .stopTimeUpdates()
            .get(1);
    assertEquals(start + 600 - 60, time(hhu, true), "早到照报");
    assertEquals(start + 620, time(hhu, false), "不早于表定发车");
    GtfsRealtimeFeed.StopTimeUpdate ldf =
        entity(message, "tu:sv:SURC:WS:WS_TEMP:WS-001~2:20261025")
            .tripUpdate()
            .orElseThrow()
            .stopTimeUpdates()
            .get(1);
    assertEquals(start + 1200, time(ldf, true));
  }

  @Test
  @DisplayName("交路里下一趟：前一趟预计到终点加终点停站晚于表定发车时按晚开推算，始发站只写发车；准点的不发")
  void predictsLateStartsOfFollowingTrips() {
    long start = SERVICE_DAY_BASE + GtfsTestFixtures.DEPARTURE_SECOND;
    long arrivalAtLdf = start + 1200 + 120;
    // 交路里的“下一趟”借用同一个车次的前一天：表定发车早已过去，必然晚开
    LocalDate previousDay = SERVICE_DATE.minusDays(1);
    long scheduled = start - 86_400L + 20;
    Instant late = Instant.ofEpochSecond(scheduled + 60);
    GtfsRealtimeFeed.FeedMessage message =
        new GtfsRealtimeBuilder(
                FEED,
                withFollowing(
                    assignment(0, 2, GtfsTestFixtures.HHU_PLATFORM, 0, 120),
                    inTransit(),
                    (timetableId, tripCode, serviceDate) ->
                        List.of(
                            new GtfsRealtimeSnapshot.FollowingTrip(
                                GtfsTestFixtures.TRIP_CODE, previousDay, 90))),
                late,
                key -> key)
            .tripUpdates();

    TripUpdate next =
        entity(message, "tu:sv:SURC:WS:WS_TEMP:WS-001:20261024").tripUpdate().orElseThrow();
    GtfsRealtimeFeed.StopTimeUpdate origin = next.stopTimeUpdates().get(0);
    assertTrue(origin.arrival().isEmpty(), "始发站只写发车");
    assertEquals(arrivalAtLdf + 90, time(origin, false));
    assertEquals((int) (arrivalAtLdf + 90 - scheduled), next.delay().orElseThrow());
    assertTrue(
        message.entities().stream()
            .anyMatch(entity -> entity.id().equals("tu:sv:SURC:WS:WS_TEMP:WS-001~2:20261024")),
        "下一趟的后一段也接着推");

    GtfsRealtimeFeed.FeedMessage onTime =
        builder(
                withFollowing(
                    assignment(0, 2, GtfsTestFixtures.HHU_PLATFORM, 0, 120),
                    inTransit(),
                    (timetableId, tripCode, serviceDate) ->
                        List.of(
                            new GtfsRealtimeSnapshot.FollowingTrip(
                                GtfsTestFixtures.TRIP_CODE, SERVICE_DATE.plusDays(1), 90))))
            .tripUpdates();
    assertTrue(
        onTime.entities().stream().noneMatch(entity -> entity.id().endsWith(":20261026")),
        "赶得上表定发车的下一趟不发更新");
  }

  @Test
  @DisplayName("按间隔发车的车：模板车次 + UNSCHEDULED，起点发车一经报出不变；下一站按 ETA，往后按模板时分推、不等点")
  void publishesHeadwayTrains() {
    GtfsFeed headwayFeed = GtfsFeedBuilder.build(GtfsTestFixtures.headwaySnapshot());
    TrainRuntimeSnapshot train = inTransit();
    long eta = NOW.getEpochSecond() + 90;
    GtfsRealtimeSnapshot snapshot =
        new GtfsRealtimeSnapshot(
            NOW,
            List.of(),
            List.of(),
            Map.of(TRAIN, train),
            Set.of(),
            Set.of(),
            List.of(),
            (operator, line) -> Optional.empty(),
            GtfsRealtimeSnapshot.SkippedStopsLookup.none(),
            GtfsRealtimeSnapshot.PlacedStopsLookup.none(),
            GtfsRealtimeSnapshot.FollowingTripsLookup.none(),
            (trainName, stopIndex) ->
                stopIndex == 2 ? java.util.OptionalLong.of(eta) : java.util.OptionalLong.empty());
    Map<String, Instant> pins = new java.util.HashMap<>();
    GtfsRealtimeBuilder.FrequencyStarts starts =
        (key, proposed) -> pins.computeIfAbsent(key, k -> proposed);

    VehiclePosition position =
        entity(
                new GtfsRealtimeBuilder(headwayFeed, snapshot, NOW, key -> key, null, starts)
                    .vehiclePositions(),
                "vp:TRAIN-1")
            .vehiclePosition()
            .orElseThrow();
    GtfsRealtimeFeed.TripDescriptor descriptor = position.trip().orElseThrow();
    assertEquals(Optional.of("sv:SURC:WS:HEADWAY:WS-1"), descriptor.tripId());
    assertEquals(
        GtfsRealtimeFeed.TripScheduleRelationship.UNSCHEDULED, descriptor.scheduleRelationship());
    assertEquals(Optional.of("12:00:00"), descriptor.startTime(), "还没过首站以外的停靠：按现在登记");
    assertEquals(2, position.currentStopSequence().orElseThrow());

    GtfsRealtimeFeed.FeedMessage later =
        new GtfsRealtimeBuilder(
                headwayFeed, snapshot, NOW.plusSeconds(600), key -> key, null, starts)
            .tripUpdates();
    TripUpdate update =
        entity(later, "tu:sv:SURC:WS:HEADWAY:WS-1:TRAIN-1").tripUpdate().orElseThrow();
    assertEquals(Optional.of("12:00:00"), update.trip().startTime(), "起点发车一经报出不再变");
    GtfsRealtimeFeed.StopTimeUpdate hhu = update.stopTimeUpdates().get(0);
    assertEquals(2, hhu.stopSequence());
    assertEquals(eta, time(hhu, true));
    assertEquals(eta + 20, time(hhu, false), "没有表定时刻：到站加停站就开");
  }

  @Test
  @DisplayName("车停在本趟终点（没有下一站）时也推算交路下一趟：实际到达 = 表定到达 + 偏差")
  void predictsTheNextTripWhileStandingAtTheTerminal() {
    long start = SERVICE_DAY_BASE + GtfsTestFixtures.DEPARTURE_SECOND;
    long arrivedAtLdf = start + 1200 + 180;
    LocalDate previousDay = SERVICE_DATE.minusDays(1);
    long scheduled = start - 86_400L + 20;
    Instant late = Instant.ofEpochSecond(scheduled + 60);
    TimetableApi.TrainAssignment atTerminal =
        new TimetableApi.TrainAssignment(
            TRAIN,
            GtfsTestFixtures.TIMETABLE_ID,
            GtfsTestFixtures.TRIP_CODE,
            GtfsTestFixtures.ROUTE_WS,
            Optional.empty(),
            SERVICE_DATE,
            NOW,
            0,
            Optional.of(3),
            Optional.of(GtfsTestFixtures.LDF_PLANNED),
            Optional.of("LDF"),
            OptionalLong.of(180),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            OptionalLong.empty());
    TrainRuntimeSnapshot train =
        runtime(
            3,
            Optional.of(NodeId.of(GtfsTestFixtures.LDF_PLANNED)),
            Optional.of(10),
            Optional.empty(),
            Optional.empty());

    GtfsRealtimeFeed.FeedMessage message =
        new GtfsRealtimeBuilder(
                FEED,
                withFollowing(
                    atTerminal,
                    train,
                    (timetableId, tripCode, serviceDate) ->
                        List.of(
                            new GtfsRealtimeSnapshot.FollowingTrip(
                                GtfsTestFixtures.TRIP_CODE, previousDay, 90))),
                late,
                key -> key)
            .tripUpdates();

    GtfsRealtimeFeed.StopTimeUpdate origin =
        entity(message, "tu:sv:SURC:WS:WS_TEMP:WS-001:20261024")
            .tripUpdate()
            .orElseThrow()
            .stopTimeUpdates()
            .get(0);
    assertEquals(arrivedAtLdf + 90, time(origin, false));
  }

  @Test
  @DisplayName("起点发车登记的键只含此刻在按间隔发车交路上跑、没绑车次的车")
  void headwayKeysCoverOnlyRunningHeadwayTrains() {
    GtfsFeed headwayFeed = GtfsFeedBuilder.build(GtfsTestFixtures.headwaySnapshot());
    GtfsRealtimeSnapshot unbound =
        snapshot(
            List.of(),
            List.of(),
            Map.of(TRAIN, inTransit()),
            Set.of(),
            List.of(),
            (operator, line) -> Optional.empty(),
            GtfsRealtimeSnapshot.SkippedStopsLookup.none());
    assertEquals(
        Set.of("TRAIN-1|sv:SURC:WS:HEADWAY:WS-1"),
        new GtfsRealtimeBuilder(headwayFeed, unbound, NOW, key -> key).headwayKeys());

    GtfsRealtimeSnapshot bound =
        snapshot(
            List.of(assignment(0, 2, GtfsTestFixtures.HHU_PLATFORM, 0, 0)),
            List.of(),
            Map.of(TRAIN, inTransit()),
            Set.of(),
            List.of(),
            (operator, line) -> Optional.empty(),
            GtfsRealtimeSnapshot.SkippedStopsLookup.none());
    assertTrue(
        new GtfsRealtimeBuilder(headwayFeed, bound, NOW, key -> key).headwayKeys().isEmpty(),
        "绑了车次的车按时刻表车次发布");
  }

  /** 列车在 LDF（第 3 站）已经选定了 1 道：与静态包的计划股道 2 道不同。 */
  private static final GtfsRealtimeSnapshot.PlacedStopsLookup PLACED_LDF_1 =
      (train, index) -> index == 3 ? Optional.of("SURC:S:LDF:1") : Optional.empty();

  private static TimetableApi.TrainAssignment assignment(
      int lastStop, int nextStop, String nextNode, long currentDelay, long projectedDelay) {
    return new TimetableApi.TrainAssignment(
        TRAIN,
        GtfsTestFixtures.TIMETABLE_ID,
        GtfsTestFixtures.TRIP_CODE,
        GtfsTestFixtures.ROUTE_WS,
        Optional.empty(),
        SERVICE_DATE,
        NOW,
        0,
        Optional.of(lastStop),
        Optional.of(GtfsTestFixtures.NTA_PLATFORM),
        Optional.of("NTA"),
        OptionalLong.of(currentDelay),
        Optional.of(nextStop),
        Optional.of(nextNode),
        Optional.of("HHU"),
        OptionalLong.of(projectedDelay));
  }

  private static TrainRuntimeSnapshot runtime(
      int routeIndex,
      Optional<NodeId> currentNode,
      Optional<Integer> dwell,
      Optional<TrainRuntimeSnapshot.Position> position,
      Optional<TrainLoad> load) {
    return new TrainRuntimeSnapshot(
        100,
        NOW,
        WORLD,
        GtfsTestFixtures.ROUTE_WS,
        RouteId.of("SURC:WS:WS-1"),
        routeIndex,
        currentNode,
        Optional.of(NodeId.of(GtfsTestFixtures.NTA_PLATFORM)),
        dwell,
        Optional.of(SignalAspect.PROCEED),
        Optional.empty(),
        OptionalDouble.of(7.5),
        OptionalInt.empty(),
        OptionalInt.empty(),
        OptionalDouble.empty(),
        TrainRuntimeSnapshot.HoldTimeline.EMPTY,
        Optional.of(new RouteLineChanges.LineRef("SURC", "WS")),
        load,
        TrainRuntimeSnapshot.Motion.NONE,
        Optional.empty(),
        position);
  }

  private static Entity entity(GtfsRealtimeFeed.FeedMessage message, String id) {
    return message.entities().stream()
        .filter(entity -> entity.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new AssertionError("没有实体 " + id + "：" + message.entities()));
  }

  @Test
  @DisplayName("运行中车次：上一站发车偏差、下一站预计到达、换线后的段与车次身份")
  void buildsTripUpdateForRunningTrain() {
    TimetableApi.TrainAssignment assignment =
        assignment(0, 2, GtfsTestFixtures.HHU_PLATFORM, 60, 120);
    TrainRuntimeSnapshot train =
        runtime(
            0,
            Optional.of(NodeId.of("SURC:NTA:HHU:1:1")),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
    GtfsRealtimeFeed.FeedMessage message =
        builder(
                snapshot(
                    List.of(assignment),
                    List.of(),
                    Map.of(TRAIN, train),
                    Set.of(),
                    List.of(),
                    (operator, line) -> Optional.empty(),
                    GtfsRealtimeSnapshot.SkippedStopsLookup.none()))
            .tripUpdates();

    assertEquals(FEED.version(), message.header().feedVersion().orElseThrow());
    TripUpdate update =
        entity(message, "tu:sv:SURC:WS:WS_TEMP:WS-001:20261025").tripUpdate().orElseThrow();
    assertEquals("sv:SURC:WS:WS_TEMP:WS-001", update.trip().tripId().orElseThrow());
    assertEquals("20261025", update.trip().startDate().orElseThrow());
    assertEquals("23:50:20", update.trip().startTime().orElseThrow(), "与 stop_times 的首站发车一致");
    assertEquals(60, update.delay().orElseThrow());
    assertEquals(2, update.stopTimeUpdates().size());

    GtfsRealtimeFeed.StopTimeUpdate last = update.stopTimeUpdates().get(0);
    assertEquals(0, last.stopSequence());
    assertEquals(60, last.departure().orElseThrow().delay().orElseThrow());
    GtfsRealtimeFeed.StopTimeUpdate next = update.stopTimeUpdates().get(1);
    assertEquals(2, next.stopSequence());
    assertEquals(
        SERVICE_DAY_BASE + 86_400L + 120L, next.arrival().orElseThrow().time().orElseThrow());
    assertEquals(
        SERVICE_DAY_BASE + 86_420L + 120L, next.departure().orElseThrow().time().orElseThrow());
    assertEquals(Optional.of("sv:SURC:S:HHU:1"), next.stopId());
    assertTrue(next.assignedStopId().isEmpty(), "静态包已经写了这条股道，不需要再分配");
  }

  @Test
  @DisplayName("直通列车到达换线站后按 ~2 的段发布；实时选台与静态计划不同时写 assigned_stop_id")
  void switchesToTheSecondSegmentAfterTheChangeStation() {
    TimetableApi.TrainAssignment assignment = assignment(2, 3, "SURC:S:LDF:1", 0, 30);
    TrainRuntimeSnapshot train =
        runtime(
            2,
            Optional.of(NodeId.of(GtfsTestFixtures.HHU_PLATFORM)),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
    GtfsRealtimeFeed.FeedMessage message =
        builder(running(assignment, train, List.of(), PLACED_LDF_1)).tripUpdates();

    TripUpdate update =
        entity(message, "tu:sv:SURC:WS:WS_TEMP:WS-001~2:20261025").tripUpdate().orElseThrow();
    GtfsRealtimeFeed.StopTimeUpdate next = update.stopTimeUpdates().get(1);
    assertEquals(3, next.stopSequence());
    assertTrue(next.stopId().isEmpty(), "换台时只写 assigned_stop_id");
    assertEquals(Optional.of("sv:SURC:S:LDF:1"), next.assignedStopId());
  }

  @Test
  @DisplayName("动态站台还没选台：车次绑定给的占位股道与起始股道同名，不能当成实际站台写 assigned_stop_id")
  void ignoresThePlaceholderBeforePlatformSelection() {
    TimetableApi.TrainAssignment assignment =
        assignment(2, 3, GtfsTestFixtures.LDF_PLACEHOLDER, 0, 30);
    TrainRuntimeSnapshot train =
        runtime(
            2,
            Optional.of(NodeId.of(GtfsTestFixtures.HHU_PLATFORM)),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
    GtfsRealtimeFeed.FeedMessage message =
        builder(
                running(
                    assignment, train, List.of(), GtfsRealtimeSnapshot.PlacedStopsLookup.none()))
            .tripUpdates();

    GtfsRealtimeFeed.StopTimeUpdate next =
        entity(message, "tu:sv:SURC:WS:WS_TEMP:WS-001~2:20261025")
            .tripUpdate()
            .orElseThrow()
            .stopTimeUpdates()
            .get(1);
    assertEquals(Optional.of("sv:SURC:S:LDF:2"), next.stopId(), "照静态包的计划股道");
    assertTrue(next.assignedStopId().isEmpty());
  }

  @Test
  @DisplayName("越站与停站中的车：SKIPPED 覆盖预测，停站剩余时间写在发车时刻")
  void writesSkippedStopsAndDwell() {
    TimetableApi.TrainAssignment assignment = assignment(0, 2, GtfsTestFixtures.HHU_PLATFORM, 0, 0);
    TrainRuntimeSnapshot train =
        runtime(
            0,
            Optional.of(NodeId.of(GtfsTestFixtures.NTA_PLATFORM)),
            Optional.of(25),
            Optional.empty(),
            Optional.empty());
    GtfsRealtimeSnapshot.SkippedStopsLookup skipped =
        (timetableId, tripId, serviceDate) -> Set.of(2);
    // 车已经晚于表定发车：剩余停站时间决定发车
    Instant late =
        Instant.ofEpochSecond(SERVICE_DAY_BASE + GtfsTestFixtures.DEPARTURE_SECOND + 20L + 60L);
    GtfsRealtimeFeed.FeedMessage message =
        new GtfsRealtimeBuilder(
                FEED,
                snapshot(
                    List.of(assignment),
                    List.of(),
                    Map.of(TRAIN, train),
                    Set.of(),
                    List.of(),
                    (operator, line) -> Optional.empty(),
                    skipped),
                late,
                key -> key)
            .tripUpdates();

    TripUpdate update =
        entity(message, "tu:sv:SURC:WS:WS_TEMP:WS-001:20261025").tripUpdate().orElseThrow();
    GtfsRealtimeFeed.StopTimeUpdate last = update.stopTimeUpdates().get(0);
    assertEquals(late.getEpochSecond() + 25, last.departure().orElseThrow().time().orElseThrow());
    GtfsRealtimeFeed.StopTimeUpdate next = update.stopTimeUpdates().get(1);
    assertEquals(StopScheduleRelationship.SKIPPED, next.scheduleRelationship());
    assertTrue(next.arrival().isEmpty());
  }

  @Test
  @DisplayName("整趟取消：没有车在跑时按 CANCELED 发，每一段各一条")
  void writesCancelledTrips() {
    TimetableApi.CancelledTrip cancellation =
        new TimetableApi.CancelledTrip(
            GtfsTestFixtures.TIMETABLE_ID,
            GtfsTestFixtures.TRIP_ID,
            GtfsTestFixtures.TRIP_CODE,
            GtfsTestFixtures.ROUTE_WS,
            SERVICE_DATE,
            NOW,
            TimetableTripCancelledEvent.Scope.FULL,
            0,
            TimetableTripCancelledEvent.Reason.NOT_DISPATCHED,
            Optional.empty());
    GtfsRealtimeFeed.FeedMessage message =
        builder(
                snapshot(
                    List.of(),
                    List.of(cancellation),
                    Map.of(),
                    Set.of(),
                    List.of(),
                    (operator, line) -> Optional.empty(),
                    GtfsRealtimeSnapshot.SkippedStopsLookup.none()))
            .tripUpdates();

    assertEquals(2, message.entities().size());
    for (Entity entity : message.entities()) {
      assertEquals(
          GtfsRealtimeFeed.TripScheduleRelationship.CANCELED,
          entity.tripUpdate().orElseThrow().trip().scheduleRelationship());
    }
  }

  @Test
  @DisplayName("半途取消：从取消站起逐站 SKIPPED")
  void writesPartialCancellation() {
    TimetableApi.CancelledTrip cancellation =
        new TimetableApi.CancelledTrip(
            GtfsTestFixtures.TIMETABLE_ID,
            GtfsTestFixtures.TRIP_ID,
            GtfsTestFixtures.TRIP_CODE,
            GtfsTestFixtures.ROUTE_WS,
            SERVICE_DATE,
            NOW,
            TimetableTripCancelledEvent.Scope.PARTIAL,
            2,
            TimetableTripCancelledEvent.Reason.VEHICLE_REMOVED,
            Optional.of(TRAIN));
    GtfsRealtimeFeed.FeedMessage message =
        builder(
                snapshot(
                    List.of(),
                    List.of(cancellation),
                    Map.of(),
                    Set.of(),
                    List.of(),
                    (operator, line) -> Optional.empty(),
                    GtfsRealtimeSnapshot.SkippedStopsLookup.none()))
            .tripUpdates();

    TripUpdate first =
        entity(message, "tu:sv:SURC:WS:WS_TEMP:WS-001:20261025").tripUpdate().orElseThrow();
    assertEquals(1, first.stopTimeUpdates().size());
    assertEquals(
        StopScheduleRelationship.SKIPPED, first.stopTimeUpdates().get(0).scheduleRelationship());
    TripUpdate second =
        entity(message, "tu:sv:SURC:WS:WS_TEMP:WS-001~2:20261025").tripUpdate().orElseThrow();
    assertEquals(
        GtfsRealtimeFeed.TripScheduleRelationship.CANCELED,
        second.trip().scheduleRelationship(),
        "后一段一站都不再停：整段取消");
    assertTrue(second.stopTimeUpdates().isEmpty());
  }

  @Test
  @DisplayName("还没算出到站预测、但已选好站台：用 NO_DATA 只带 assigned_stop_id")
  void writesAssignedStopWithoutPrediction() {
    TimetableApi.TrainAssignment assignment =
        new TimetableApi.TrainAssignment(
            TRAIN,
            GtfsTestFixtures.TIMETABLE_ID,
            GtfsTestFixtures.TRIP_CODE,
            GtfsTestFixtures.ROUTE_WS,
            Optional.empty(),
            SERVICE_DATE,
            NOW,
            0,
            Optional.of(2),
            Optional.of(GtfsTestFixtures.HHU_PLATFORM),
            Optional.of("HHU"),
            OptionalLong.empty(),
            Optional.of(3),
            Optional.of("SURC:S:LDF:1"),
            Optional.of("LDF"),
            OptionalLong.empty());
    TrainRuntimeSnapshot train =
        runtime(
            2,
            Optional.of(NodeId.of(GtfsTestFixtures.HHU_PLATFORM)),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
    GtfsRealtimeFeed.FeedMessage message =
        builder(running(assignment, train, List.of(), PLACED_LDF_1)).tripUpdates();

    TripUpdate update =
        entity(message, "tu:sv:SURC:WS:WS_TEMP:WS-001~2:20261025").tripUpdate().orElseThrow();
    GtfsRealtimeFeed.StopTimeUpdate next =
        update.stopTimeUpdates().stream()
            .filter(stop -> stop.stopSequence() == 3)
            .findFirst()
            .orElseThrow();
    assertEquals(StopScheduleRelationship.NO_DATA, next.scheduleRelationship());
    assertTrue(next.stopId().isEmpty(), "换台时只写 assigned_stop_id");
    assertEquals(Optional.of("sv:SURC:S:LDF:1"), next.assignedStopId());
    assertTrue(next.arrival().isEmpty());
    assertTrue(next.departure().isEmpty());
  }

  @Test
  @DisplayName("车辆位置：车头坐标换经纬度、朝向与载客率；回库越 EOP 的车不发布")
  void buildsVehiclePositions() {
    TimetableApi.TrainAssignment assignment = assignment(0, 2, GtfsTestFixtures.HHU_PLATFORM, 0, 0);
    TrainRuntimeSnapshot train =
        runtime(
            0,
            Optional.of(NodeId.of(GtfsTestFixtures.NTA_PLATFORM)),
            Optional.of(25),
            Optional.of(new TrainRuntimeSnapshot.Position(500.0, 64.0, 200.0, 0.4, 0.0)),
            Optional.of(
                new TrainLoad(List.of(new TrainLoad.Car(40, 40), new TrainLoad.Car(20, 10)))));
    GtfsRealtimeFeed.FeedMessage message =
        builder(
                snapshot(
                    List.of(assignment),
                    List.of(),
                    Map.of(TRAIN, train),
                    Set.of(),
                    List.of(),
                    (operator, line) -> Optional.empty(),
                    GtfsRealtimeSnapshot.SkippedStopsLookup.none()))
            .vehiclePositions();

    VehiclePosition position = entity(message, "vp:TRAIN-1").vehiclePosition().orElseThrow();
    assertEquals(GtfsGeo.latitude(200.0), position.position().orElseThrow().latitude(), 1e-9);
    assertEquals(GtfsGeo.longitude(500.0), position.position().orElseThrow().longitude(), 1e-9);
    assertEquals(90.0, position.position().orElseThrow().bearing().orElseThrow(), 1e-9);
    assertEquals(7.5, position.position().orElseThrow().speed().orElseThrow(), 1e-9);
    assertEquals(0, position.currentStopSequence().orElseThrow());
    assertEquals(Optional.of("sv:SURC:S:NTA:1"), position.stopId());
    assertEquals(VehicleStopStatus.STOPPED_AT, position.currentStatus());
    assertEquals(OccupancyStatus.FEW_SEATS_AVAILABLE, position.occupancyStatus().orElseThrow());
    assertEquals(83, position.occupancyPercentage().orElseThrow());
    assertEquals(Optional.of("WS-001"), position.vehicle().orElseThrow().label());
    assertEquals(2, position.carriages().size(), "每节车一条，车头在前");
    assertEquals("TRAIN-1#1", position.carriages().get(0).id());
    assertEquals(OccupancyStatus.FULL, position.carriages().get(0).occupancyStatus().orElseThrow());
    assertEquals(
        OccupancyStatus.MANY_SEATS_AVAILABLE,
        position.carriages().get(1).occupancyStatus().orElseThrow());
    assertEquals(50, position.carriages().get(1).occupancyPercentage().orElseThrow());
    assertEquals(2, position.carriages().get(1).carriageSequence());

    GtfsRealtimeFeed.FeedMessage hidden =
        builder(
                snapshot(
                    List.of(assignment),
                    List.of(),
                    Map.of(TRAIN, train),
                    Set.of(TRAIN),
                    List.of(),
                    (operator, line) -> Optional.empty(),
                    GtfsRealtimeSnapshot.SkippedStopsLookup.none()))
            .vehiclePositions();
    assertTrue(hidden.entities().isEmpty(), "越过 EOP 的回库车不再发布");
  }

  @Test
  @DisplayName("bearing 按行进方向算：+x 向东为 90、−z 向北为 0，落在 [0, 360)；没动过时不写")
  void bearingFollowsTheDirectionOfTravel() {
    assertEquals(90.0, GtfsRealtimeBuilder.bearing(1.0, 0.0).orElseThrow(), 1e-9);
    assertEquals(0.0, GtfsRealtimeBuilder.bearing(0.0, -1.0).orElseThrow(), 1e-9);
    assertEquals(180.0, GtfsRealtimeBuilder.bearing(0.0, 0.3).orElseThrow(), 1e-9);
    assertEquals(270.0, GtfsRealtimeBuilder.bearing(-2.0, 0.0).orElseThrow(), 1e-9);
    assertEquals(315.0, GtfsRealtimeBuilder.bearing(-1.0, -1.0).orElseThrow(), 1e-9);
    assertTrue(GtfsRealtimeBuilder.bearing(0.0, 0.0).isEmpty());
  }

  @Test
  @DisplayName("一条站点更新都给不出时不发这趟车：上一站没有偏差、下一站还没有预测")
  void skipsTripUpdatesWithoutAnyStop() {
    TimetableApi.TrainAssignment assignment =
        new TimetableApi.TrainAssignment(
            TRAIN,
            GtfsTestFixtures.TIMETABLE_ID,
            GtfsTestFixtures.TRIP_CODE,
            GtfsTestFixtures.ROUTE_WS,
            Optional.empty(),
            SERVICE_DATE,
            NOW,
            0,
            Optional.of(0),
            Optional.of(GtfsTestFixtures.NTA_PLATFORM),
            Optional.of("NTA"),
            OptionalLong.empty(),
            Optional.of(2),
            Optional.of(GtfsTestFixtures.HHU_PLATFORM),
            Optional.of("HHU"),
            OptionalLong.empty());
    TrainRuntimeSnapshot train =
        runtime(
            0,
            Optional.of(NodeId.of("SURC:NTA:HHU:1:1")),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    GtfsRealtimeFeed.FeedMessage message =
        builder(
                running(
                    assignment, train, List.of(), GtfsRealtimeSnapshot.PlacedStopsLookup.none()))
            .tripUpdates();

    assertTrue(message.entities().isEmpty(), message.entities().toString());
  }

  @Test
  @DisplayName("停站中按表等点：预计发车不早于表定发车")
  void dwellDepartureNeverBeforeTheTimetable() {
    TimetableApi.TrainAssignment assignment = assignment(0, 2, GtfsTestFixtures.HHU_PLATFORM, 0, 0);
    TrainRuntimeSnapshot train =
        runtime(
            0,
            Optional.of(NodeId.of(GtfsTestFixtures.NTA_PLATFORM)),
            Optional.of(5),
            Optional.empty(),
            Optional.empty());
    long scheduled = SERVICE_DAY_BASE + GtfsTestFixtures.DEPARTURE_SECOND + 20L;
    Instant early = Instant.ofEpochSecond(scheduled - 100L);

    GtfsRealtimeFeed.FeedMessage message =
        new GtfsRealtimeBuilder(
                FEED,
                running(
                    assignment, train, List.of(), GtfsRealtimeSnapshot.PlacedStopsLookup.none()),
                early,
                key -> key)
            .tripUpdates();

    GtfsRealtimeFeed.StopTimeUpdate last =
        entity(message, "tu:sv:SURC:WS:WS_TEMP:WS-001:20261025")
            .tripUpdate()
            .orElseThrow()
            .stopTimeUpdates()
            .get(0);
    assertEquals(scheduled, last.departure().orElseThrow().time().orElseThrow());
  }

  @Test
  @DisplayName("直通车在前一段运行时，后一段的取消照常发布")
  void publishesCancellationsOfTheOtherSegmentWhileRunning() {
    TimetableApi.TrainAssignment assignment = assignment(0, 2, GtfsTestFixtures.HHU_PLATFORM, 0, 0);
    TrainRuntimeSnapshot train =
        runtime(
            0,
            Optional.of(NodeId.of("SURC:NTA:HHU:1:1")),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
    TimetableApi.CancelledTrip cancellation =
        new TimetableApi.CancelledTrip(
            GtfsTestFixtures.TIMETABLE_ID,
            GtfsTestFixtures.TRIP_ID,
            GtfsTestFixtures.TRIP_CODE,
            GtfsTestFixtures.ROUTE_WS,
            SERVICE_DATE,
            NOW,
            TimetableTripCancelledEvent.Scope.PARTIAL,
            3,
            TimetableTripCancelledEvent.Reason.VEHICLE_REMOVED,
            Optional.of(TRAIN));

    GtfsRealtimeFeed.FeedMessage message =
        builder(
                running(
                    assignment,
                    train,
                    List.of(cancellation),
                    GtfsRealtimeSnapshot.PlacedStopsLookup.none()))
            .tripUpdates();

    TripUpdate second =
        entity(message, "tu:sv:SURC:WS:WS_TEMP:WS-001~2:20261025").tripUpdate().orElseThrow();
    assertEquals(3, second.stopTimeUpdates().get(0).stopSequence());
    assertEquals(
        StopScheduleRelationship.SKIPPED, second.stopTimeUpdates().get(0).scheduleRelationship());
    TripUpdate first =
        entity(message, "tu:sv:SURC:WS:WS_TEMP:WS-001:20261025").tripUpdate().orElseThrow();
    assertEquals(
        GtfsRealtimeFeed.TripScheduleRelationship.SCHEDULED, first.trip().scheduleRelationship());
  }

  @Test
  @DisplayName("车辆位置的当前站只报静态包里有的停靠：下一站是区间点时退回上一站")
  void vehicleStopSequenceStaysOnPublishedStops() {
    TimetableApi.TrainAssignment assignment = assignment(0, 1, "SURC:NTA:HHU:1:1", 0, 0);
    TrainRuntimeSnapshot train =
        runtime(
            0,
            Optional.of(NodeId.of(GtfsTestFixtures.NTA_PLATFORM)),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    GtfsRealtimeFeed.FeedMessage message =
        builder(
                running(
                    assignment, train, List.of(), GtfsRealtimeSnapshot.PlacedStopsLookup.none()))
            .vehiclePositions();

    VehiclePosition position = entity(message, "vp:TRAIN-1").vehiclePosition().orElseThrow();
    assertEquals(0, position.currentStopSequence().orElseThrow());
    assertEquals(Optional.of("sv:SURC:S:NTA:1"), position.stopId());
  }

  @Test
  @DisplayName("出库途中、还没到第一个载客站的车不发布位置，绑了车次的到站预测照常")
  void hidesTrainsBeforeTheirFirstPassengerStop() {
    TimetableApi.TrainAssignment assignment =
        assignment(0, 2, GtfsTestFixtures.HHU_PLATFORM, 0, 30);
    TrainRuntimeSnapshot train =
        runtime(
            0,
            Optional.of(NodeId.of("SURC:NTA:HHU:1:1")),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
    GtfsRealtimeSnapshot snapshot =
        new GtfsRealtimeSnapshot(
            NOW,
            List.of(assignment),
            List.of(),
            Map.of(TRAIN, train),
            Set.of(),
            Set.of(TRAIN),
            List.of(),
            (operator, line) -> Optional.empty(),
            GtfsRealtimeSnapshot.SkippedStopsLookup.none(),
            GtfsRealtimeSnapshot.PlacedStopsLookup.none());

    assertTrue(builder(snapshot).vehiclePositions().entities().isEmpty());
    assertTrue(
        builder(snapshot).tripUpdates().entities().stream()
            .anyMatch(entity -> entity.id().equals("tu:sv:SURC:WS:WS_TEMP:WS-001:20261025")));
  }

  @Test
  @DisplayName("车头已在下一站节点时，车辆状态是 INCOMING_AT")
  void marksTheApproachedStopAsIncoming() {
    TimetableApi.TrainAssignment assignment = assignment(0, 2, GtfsTestFixtures.HHU_PLATFORM, 0, 0);
    TrainRuntimeSnapshot train =
        runtime(
            0,
            Optional.of(NodeId.of(GtfsTestFixtures.HHU_PLATFORM)),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
    GtfsRealtimeFeed.FeedMessage message =
        builder(
                snapshot(
                    List.of(assignment),
                    List.of(),
                    Map.of(TRAIN, train),
                    Set.of(),
                    List.of(),
                    (operator, line) -> Optional.empty(),
                    GtfsRealtimeSnapshot.SkippedStopsLookup.none()))
            .vehiclePositions();

    VehiclePosition position = entity(message, "vp:TRAIN-1").vehiclePosition().orElseThrow();
    assertEquals(VehicleStopStatus.INCOMING_AT, position.currentStatus());
    assertEquals(2, position.currentStopSequence().orElseThrow());
  }

  @Test
  @DisplayName("没绑车次的车只带乘客线路")
  void emitsRouteOnlyForUnboundTrains() {
    TrainRuntimeSnapshot train =
        runtime(
            0,
            Optional.of(NodeId.of(GtfsTestFixtures.HHU_PLATFORM)),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
    GtfsRealtimeFeed.FeedMessage message =
        builder(
                snapshot(
                    List.of(),
                    List.of(),
                    Map.of(TRAIN, train),
                    Set.of(),
                    List.of(),
                    (operator, line) -> Optional.empty(),
                    GtfsRealtimeSnapshot.SkippedStopsLookup.none()))
            .vehiclePositions();

    VehiclePosition position = entity(message, "vp:TRAIN-1").vehiclePosition().orElseThrow();
    assertTrue(position.trip().orElseThrow().tripId().isEmpty());
    assertEquals(Optional.of("sv:SURC:WS"), position.trip().orElseThrow().routeId());
    assertEquals(VehicleStopStatus.IN_TRANSIT_TO, position.currentStatus());
  }

  @Test
  @DisplayName("单条记录出错只跳过它：其它线路的告警照常发布，并记一条告警")
  void isolatesFailuresPerRecord() {
    List<String> warnings = new java.util.ArrayList<>();
    java.util.function.BiFunction<String, String, Optional<PidsLineStatus>> lineStatus =
        (operator, line) -> {
          if ("MT".equals(line)) {
            throw new IllegalStateException("boom");
          }
          if ("WS".equals(line)) {
            return Optional.of(
                new PidsLineStatus(
                    PidsLineStatus.Condition.PART_SUSPENDED,
                    new PidsLineStatus.Detail.Closed(
                        new PidsDirectory.Section("SURC:NTA", "SURC:HHU"))));
          }
          return Optional.empty();
        };
    GtfsRealtimeSnapshot snapshot =
        snapshot(
            List.of(),
            List.of(),
            Map.of(),
            Set.of(),
            List.of(),
            lineStatus,
            GtfsRealtimeSnapshot.SkippedStopsLookup.none());
    GtfsRealtimeFeed.FeedMessage message =
        new GtfsRealtimeBuilder(FEED, snapshot, NOW, key -> key, warnings::add).alerts();

    assertTrue(
        message.entities().stream()
            .anyMatch(entity -> entity.id().equals("alert:line-status:SURC:WS:PART_SUSPENDED")));
    assertTrue(message.entities().stream().noneMatch(entity -> entity.id().contains("SURC:MT")));
    assertEquals(1, warnings.size());
    assertTrue(warnings.get(0).contains("line-status:SURC:MT"));
  }

  @Test
  @DisplayName("告警：公告按运营商/线路/车站成组；线路运行状况只出停运、部分停运等影响乘车的状况")
  void buildsAlerts() {
    PidsBulletin bulletin =
        new PidsBulletin(
            UUID.randomUUID(),
            GtfsTestFixtures.COMPANY,
            "SURC",
            Set.of("HHU"),
            Set.of("WS"),
            PidsBulletin.Level.IMPORTANT,
            new PidsBulletin.Text("站台施工", "Platform works"),
            new PidsBulletin.Text("请留意站台变化", "Please mind the platform change"),
            Optional.of(NOW.minusSeconds(600)),
            Optional.of(NOW.plusSeconds(600)),
            Optional.empty(),
            NOW,
            NOW);
    java.util.function.BiFunction<String, String, Optional<PidsLineStatus>> lineStatus =
        (operator, line) -> {
          if (!"SURC".equals(operator)) {
            return Optional.empty();
          }
          if ("MT".equals(line)) {
            return Optional.of(PidsLineStatus.of(PidsLineStatus.Condition.SUSPENDED));
          }
          if ("WS".equals(line)) {
            return Optional.of(
                new PidsLineStatus(
                    PidsLineStatus.Condition.PART_SUSPENDED,
                    new PidsLineStatus.Detail.Closed(
                        new PidsDirectory.Section("SURC:NTA", "SURC:HHU"))));
          }
          return Optional.empty();
        };
    GtfsRealtimeFeed.FeedMessage message =
        builder(
                snapshot(
                    List.of(),
                    List.of(),
                    Map.of(),
                    Set.of(),
                    List.of(bulletin),
                    lineStatus,
                    GtfsRealtimeSnapshot.SkippedStopsLookup.none()))
            .alerts();

    Alert announcement = entity(message, "alert:bulletin:" + bulletin.id()).alert().orElseThrow();
    assertEquals(1, announcement.informedEntities().size(), "线路与车站同时限定时取交集");
    assertEquals(Optional.of("sv:SURC:WS"), announcement.informedEntities().get(0).routeId());
    assertEquals(Optional.of("sv:SURC:S:HHU"), announcement.informedEntities().get(0).stopId());
    assertEquals(AlertSeverity.WARNING, announcement.severity());
    assertEquals(1, announcement.activePeriods().size());
    assertEquals(
        NOW.minusSeconds(600).getEpochSecond(),
        announcement.activePeriods().get(0).start().orElseThrow());
    assertEquals(2, announcement.headerText().orElseThrow().translations().size());

    Alert suspended = entity(message, "alert:line-status:SURC:MT:SUSPENDED").alert().orElseThrow();
    assertEquals(AlertEffect.NO_SERVICE, suspended.effect());
    assertEquals(AlertCause.MAINTENANCE, suspended.cause());
    assertEquals(Optional.of("sv:SURC:MT"), suspended.informedEntities().get(0).routeId());

    Alert partial =
        entity(message, "alert:line-status:SURC:WS:PART_SUSPENDED").alert().orElseThrow();
    assertEquals(AlertEffect.NO_SERVICE, partial.effect());
    assertEquals(2, partial.informedEntities().size());
    assertEquals(Optional.of("sv:SURC:S:NTA"), partial.informedEntities().get(0).stopId());
    assertEquals(Optional.of("sv:SURC:S:HHU"), partial.informedEntities().get(1).stopId());
    assertEquals(
        Optional.of("sv:SURC:WS"), partial.informedEntities().get(0).routeId(), "只标这条线在这两站的部分停运");
  }
}
