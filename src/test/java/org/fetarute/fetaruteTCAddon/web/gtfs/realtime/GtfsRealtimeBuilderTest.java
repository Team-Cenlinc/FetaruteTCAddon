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
    return new GtfsRealtimeSnapshot(
        NOW, assignments, cancellations, trains, outOfService, bulletins, lineStatus, skipped);
  }

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
    TimetableApi.TrainAssignment assignment = assignment(2, 3, "SURC:S:LDF:2", 0, 30);
    TrainRuntimeSnapshot train =
        runtime(
            2,
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
            .tripUpdates();

    TripUpdate update =
        entity(message, "tu:sv:SURC:WS:WS_TEMP:WS-001~2:20261025").tripUpdate().orElseThrow();
    GtfsRealtimeFeed.StopTimeUpdate next = update.stopTimeUpdates().get(1);
    assertEquals(3, next.stopSequence());
    assertTrue(next.stopId().isEmpty(), "换台时只写 assigned_stop_id");
    assertEquals(Optional.of("sv:SURC:S:LDF:DYN"), next.assignedStopId());
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
    GtfsRealtimeFeed.FeedMessage message =
        builder(
                snapshot(
                    List.of(assignment),
                    List.of(),
                    Map.of(TRAIN, train),
                    Set.of(),
                    List.of(),
                    (operator, line) -> Optional.empty(),
                    skipped))
            .tripUpdates();

    TripUpdate update =
        entity(message, "tu:sv:SURC:WS:WS_TEMP:WS-001:20261025").tripUpdate().orElseThrow();
    GtfsRealtimeFeed.StopTimeUpdate last = update.stopTimeUpdates().get(0);
    assertEquals(NOW.getEpochSecond() + 25, last.departure().orElseThrow().time().orElseThrow());
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
    assertEquals(2, second.stopTimeUpdates().size());
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
            Optional.of("SURC:S:LDF:2"),
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

    TripUpdate update =
        entity(message, "tu:sv:SURC:WS:WS_TEMP:WS-001~2:20261025").tripUpdate().orElseThrow();
    GtfsRealtimeFeed.StopTimeUpdate next =
        update.stopTimeUpdates().stream()
            .filter(stop -> stop.stopSequence() == 3)
            .findFirst()
            .orElseThrow();
    assertEquals(StopScheduleRelationship.NO_DATA, next.scheduleRelationship());
    assertTrue(next.stopId().isEmpty(), "换台时只写 assigned_stop_id");
    assertEquals(Optional.of("sv:SURC:S:LDF:DYN"), next.assignedStopId());
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
            Optional.of(new TrainRuntimeSnapshot.Position(500.0, 64.0, 200.0, 270.0f)),
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
  @DisplayName("朝向归一到 [0, 360)：Minecraft 的负 yaw 不会写出负 bearing")
  void normalizesBearing() {
    assertEquals(90.0, GtfsRealtimeBuilder.bearing(270.0f), 1e-9);
    assertEquals(270.0, GtfsRealtimeBuilder.bearing(-270.0f), 1e-9);
    assertEquals(0.0, GtfsRealtimeBuilder.bearing(180.0f), 1e-9);
    assertEquals(180.0, GtfsRealtimeBuilder.bearing(0.0f), 1e-9);
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
