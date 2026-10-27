package org.fetarute.fetaruteTCAddon.web.gtfs.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.Timetable;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableRoutePlan;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableStatus;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableStop;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableTrip;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.VehicleDuty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GTFS-Realtime：交路里接下来的车次")
class GtfsFollowingTripsTest {

  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  private static final UUID TIMETABLE = new UUID(0L, 1L);
  private static final UUID ROUTE_OUT = new UUID(0L, 2L);
  private static final UUID ROUTE_BACK = new UUID(0L, 3L);
  private static final UUID DUTY = new UUID(0L, 4L);
  private static final UUID TRIP_LATE = new UUID(0L, 5L);
  private static final UUID TRIP_AFTER_MIDNIGHT = new UUID(0L, 6L);
  private static final UUID TRIP_LAST = new UUID(0L, 7L);
  private static final Instant NOW = Instant.parse("2026-10-25T04:00:00Z");

  /** 交路 23:00 出库：23:30 去程、00:10 回程（零点后）、00:50 去程。 */
  private static Timetable timetable() {
    return new Timetable(
        TIMETABLE,
        new UUID(0L, 10L),
        new UUID(0L, 11L),
        new UUID(0L, 12L),
        "TT",
        "TT",
        TimetableStatus.PUBLISHED,
        ZONE,
        0,
        86_400,
        List.of(plan(ROUTE_OUT, "OUT", false), plan(ROUTE_BACK, "BACK", true)),
        List.of(
            new TimetableTrip(TRIP_LATE, TIMETABLE, ROUTE_OUT, 0, "T-1", 23 * 3600 + 1800, duty()),
            new TimetableTrip(TRIP_AFTER_MIDNIGHT, TIMETABLE, ROUTE_BACK, 1, "T-2", 600, duty()),
            new TimetableTrip(TRIP_LAST, TIMETABLE, ROUTE_OUT, 2, "T-3", 3000, duty())),
        List.of(
            new VehicleDuty(
                DUTY,
                TIMETABLE,
                0,
                "D001",
                "SURC:D:LWN:1",
                "SURC:D:LWN:1",
                Optional.empty(),
                Optional.empty(),
                List.of(TRIP_LATE, TRIP_AFTER_MIDNIGHT, TRIP_LAST),
                23 * 3600,
                86_400 + 4000,
                86_400 + 4200,
                VehicleDuty.CloseReason.MAX_TRIPS,
                Optional.empty())),
        Optional.empty(),
        NOW,
        NOW);
  }

  private static Optional<UUID> duty() {
    return Optional.of(DUTY);
  }

  /**
   * @param siding 终点站之后还要开进正线折返点（表定 60 秒）
   */
  private static TimetableRoutePlan plan(UUID routeId, String code, boolean siding) {
    List<TimetableStop> stops =
        new java.util.ArrayList<>(
            List.of(
                new TimetableStop(
                    0,
                    Optional.of("NTA"),
                    Optional.of("SURC:S:NTA:1"),
                    0,
                    0,
                    RouteStopPassType.STOP),
                new TimetableStop(
                    1,
                    Optional.of("HHU"),
                    Optional.of("SURC:S:HHU:1"),
                    600,
                    600,
                    siding ? RouteStopPassType.STOP : RouteStopPassType.TERMINATE)));
    if (siding) {
      stops.add(
          new TimetableStop(
              2,
              Optional.empty(),
              Optional.of("SURC:HHU:ZKW:1:001"),
              660,
              660,
              RouteStopPassType.PASS));
    }
    return new TimetableRoutePlan(
        routeId,
        code,
        RouteOperationType.OPERATION,
        1,
        List.copyOf(stops),
        "SURC:S:NTA:1",
        "SURC:S:HHU:1",
        Optional.empty(),
        Optional.empty());
  }

  private static RouteStop stop(UUID routeId, int sequence, int dwell, RouteStopPassType type) {
    return new RouteStop(
        routeId,
        sequence,
        Optional.empty(),
        Optional.of(sequence == 0 ? "SURC:S:NTA:1" : "SURC:S:HHU:1"),
        Optional.of(dwell),
        type,
        Optional.empty());
  }

  private static final Map<UUID, List<RouteStop>> ROUTE_STOPS =
      Map.of(
          ROUTE_OUT,
          List.of(
              stop(ROUTE_OUT, 0, 20, RouteStopPassType.STOP),
              stop(ROUTE_OUT, 1, 45, RouteStopPassType.TERMINATE)),
          ROUTE_BACK,
          List.of(
              stop(ROUTE_BACK, 0, 20, RouteStopPassType.STOP),
              stop(ROUTE_BACK, 1, 30, RouteStopPassType.TERMINATE)));

  @Test
  @DisplayName("按交路顺序给出后面的车次；零点后的那一趟起点发车日期是下一个日历日；准备时间是折返线走行加交路最后一个节点的计划停站")
  void listsFollowingTripsAcrossMidnight() {
    List<GtfsRealtimeSnapshot.FollowingTrip> following =
        GtfsFollowingTrips.of(
            List.of(timetable()),
            routeId -> ROUTE_STOPS.getOrDefault(routeId, List.of()),
            TIMETABLE,
            "T-1",
            LocalDate.of(2026, 10, 25),
            5);

    assertEquals(
        List.of(
            new GtfsRealtimeSnapshot.FollowingTrip("T-2", LocalDate.of(2026, 10, 26), 45),
            new GtfsRealtimeSnapshot.FollowingTrip("T-3", LocalDate.of(2026, 10, 26), 60 + 30)),
        following);
  }

  @Test
  @DisplayName("只要前几趟；最后一趟、找不到的车次、找不到的表都没有后续")
  void respectsTheLimitAndMissingData() {
    assertEquals(
        1,
        GtfsFollowingTrips.of(
                List.of(timetable()),
                routeId -> ROUTE_STOPS.getOrDefault(routeId, List.of()),
                TIMETABLE,
                "T-1",
                LocalDate.of(2026, 10, 25),
                1)
            .size());
    assertTrue(
        GtfsFollowingTrips.of(
                List.of(timetable()),
                routeId -> List.of(),
                TIMETABLE,
                "T-3",
                LocalDate.of(2026, 10, 26),
                5)
            .isEmpty());
    assertTrue(
        GtfsFollowingTrips.of(
                List.of(timetable()),
                routeId -> List.of(),
                TIMETABLE,
                "NOPE",
                LocalDate.of(2026, 10, 25),
                5)
            .isEmpty());
    assertTrue(
        GtfsFollowingTrips.of(
                List.of(), routeId -> List.of(), TIMETABLE, "T-1", LocalDate.of(2026, 10, 25), 5)
            .isEmpty());
  }
}
