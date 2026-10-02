package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationStopEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.repository.TimetableRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.junit.jupiter.api.Test;

/**
 * 车次匹配认交路：2026-09-27 实服 MT-2 连锁错班的回归。
 *
 * <p>夹具照 MT-2 的形状：每个交路只跑一班，首班 route 本身从车库始发（停靠 0 是车库、停靠 1 是首站）， 三班相隔 150
 * 秒。当时的链条是：重启后留下的车按时间绑走了当前那一班， 那一班的出库车只好去抢下一班，此后每辆车都错一班、在首站等别人的时刻；交路归属与进度对不上，到终点接不了回库票。
 */
class TimetableDutyMatchingTest {

  private static final ZoneId ZONE = ZoneId.of("UTC");
  private static final UUID TIMETABLE = UUID.randomUUID();
  private static final UUID ROUTE = UUID.randomUUID();
  private static final LocalDate DAY = LocalDate.of(2026, 3, 2);
  private static final UUID DUTY_1 = UUID.randomUUID();
  private static final UUID DUTY_2 = UUID.randomUUID();
  private static final UUID DUTY_3 = UUID.randomUUID();

  /** 08:00:00、08:02:30、08:05:00 各一班，分属三个交路；首站计划发车 = 班次发车 + 42 秒。 */
  private static Timetable timetable() {
    List<TimetableStop> stops =
        List.of(
            new TimetableStop(
                0, Optional.empty(), Optional.of("OP:D:DEP:3"), 0, 0, RouteStopPassType.PASS),
            new TimetableStop(
                1, Optional.of("AAA"), Optional.of("OP:S:AAA:1"), 18, 42, RouteStopPassType.STOP),
            new TimetableStop(
                2,
                Optional.of("PPP"),
                Optional.of("OP:S:PPP:1"),
                300,
                300,
                RouteStopPassType.TERMINATE));
    List<TimetableTrip> trips = new ArrayList<>();
    List<VehicleDuty> duties = new ArrayList<>();
    UUID[] dutyIds = {DUTY_1, DUTY_2, DUTY_3};
    for (int i = 0; i < dutyIds.length; i++) {
      UUID tripId = UUID.randomUUID();
      int departure = 8 * 3600 + i * 150;
      trips.add(
          new TimetableTrip(
              tripId, TIMETABLE, ROUTE, i, "R-" + (i + 1), departure, Optional.of(dutyIds[i])));
      duties.add(
          new VehicleDuty(
              dutyIds[i],
              TIMETABLE,
              i,
              "D" + (i + 1),
              "OP:D:DEP:3",
              "OP:S:PPP:1",
              Optional.empty(),
              Optional.empty(),
              List.of(tripId),
              departure,
              departure + 300,
              departure + 300,
              VehicleDuty.CloseReason.MAX_TRIPS));
    }
    return new Timetable(
        TIMETABLE,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "TT",
        "单班交路",
        TimetableStatus.PUBLISHED,
        ZONE,
        7 * 3600,
        9 * 3600,
        List.of(
            new TimetableRoutePlan(
                ROUTE,
                "R",
                RouteOperationType.OPERATION,
                1,
                stops,
                "OP:D:DEP:3",
                "OP:S:PPP:1",
                Optional.empty(),
                Optional.empty())),
        trips,
        duties,
        Optional.empty(),
        Instant.parse("2026-03-01T00:00:00Z"),
        Instant.parse("2026-03-01T00:00:00Z"));
  }

  private static TimetableService service(List<String> logs) {
    TimetableService service = new TimetableService(Instant::now, logs::add);
    service.applySettings(
        new TimetableService.Settings(
            true, true, Duration.ofSeconds(120), Duration.ofSeconds(300), Duration.ofSeconds(300)));
    StorageProvider provider = mock(StorageProvider.class);
    TimetableRepository repository = mock(TimetableRepository.class);
    when(provider.timetables()).thenReturn(repository);
    when(repository.listPublished()).thenReturn(List.of(timetable()));
    service.reload(provider);
    return service;
  }

  private static StationStopEvent atFirstStation(String train, Instant at) {
    return new StationStopEvent(train, Optional.of(ROUTE), "R", 1, 3, "OP:S:AAA:1", at);
  }

  private static TimetableService.DutyKey duty(UUID dutyId) {
    return new TimetableService.DutyKey(TIMETABLE, dutyId, DAY);
  }

  private static TimetableService.TicketIntent firstTripOf(UUID dutyId) {
    return new TimetableService.TicketIntent(
        TIMETABLE, dutyId, DAY, RouteOperationType.OPERATION, 0);
  }

  /**
   * 出库晚了的车跑自己那一班，晚点就晚点开，不去抢下一班。
   *
   * <p>08:02:40 在首站：自己那班 08:00:42 该开（晚 118 秒），下一班 08:03:12 该开（早 32 秒）。按时间就近会选下一班 并扣车 32
   * 秒——下一班的出库车随后只能再往后抢，连锁错班就是这么开始的。
   */
  @Test
  void lateTrainRunsItsOwnDutyTripInsteadOfTakingTheNextOne() {
    List<String> logs = new ArrayList<>();
    TimetableService service = service(logs);
    service.bindDuty("train-late", duty(DUTY_1), "ticket-operation");

    Optional<Instant> scheduled =
        service.scheduledDepartureAt(
            atFirstStation("train-late", Instant.parse("2026-03-02T08:02:40Z")));

    assertEquals(Optional.of(Instant.parse("2026-03-02T08:00:42Z")), scheduled, "按自己那一班算，已经晚点");
    assertEquals(Optional.of("R-1"), service.assignmentOf("train-late").map(a -> a.tripCode()));
    assertTrue(
        logs.stream().noneMatch(line -> line.startsWith("TIMETABLE_DUTY_BIND_CONFLICT")),
        logs::toString);
    assertTrue(
        logs.stream()
            .anyMatch(line -> line.startsWith("TIMETABLE_ASSIGN ") && line.contains("scope=duty")),
        logs::toString);
  }

  /** 交路已经说明了该跑哪一班：晚点超过容差也照样绑，不因为晚就退回自由运行、让进度停在原地。 */
  @Test
  void dutyTrainIsNotLimitedByTheAssignTolerance() {
    TimetableService service = service(new ArrayList<>());
    service.bindDuty("train-very-late", duty(DUTY_1), "ticket-operation");

    service.scheduledDepartureAt(
        atFirstStation("train-very-late", Instant.parse("2026-03-02T08:07:00Z")));

    assertEquals(
        Optional.of("R-1"),
        service.assignmentOf("train-very-late").map(a -> a.tripCode()),
        "晚 378 秒超过 300 秒容差，第三班（晚 78 秒）更近，但那是别的交路");
    assertEquals(
        1, service.dutyProgressOf("train-very-late").map(p -> p.assignedTrips()).orElse(0));
  }

  /**
   * 没绑交路的车不能绑到一个已经有车的交路上。
   *
   * <p>第一班的出库车已经出来了（绑在交路 1 上、还没到首站）。重启后留下的车此时出现在首站：按时间它离第一班最近， 但第一班有自己的车；它只能接第二班，交路 2 的出库票随之作废。
   */
  @Test
  void orphanSkipsTripsWhoseDutyAlreadyHasATrain() {
    TimetableService service = service(new ArrayList<>());
    service.bindDuty("train-spawned", duty(DUTY_1), "ticket-operation");

    service.scheduledDepartureAt(
        atFirstStation("train-orphan", Instant.parse("2026-03-02T08:00:40Z")));

    assertEquals(Optional.of("R-2"), service.assignmentOf("train-orphan").map(a -> a.tripCode()));
    assertEquals(Optional.of(duty(DUTY_2)), service.dutyBindingOf("train-orphan"));
    assertEquals(Optional.of("train-orphan"), service.runningVehicleFor(firstTripOf(DUTY_2)));
    assertEquals(Optional.of("train-spawned"), service.runningVehicleFor(firstTripOf(DUTY_1)));
    assertEquals(Optional.empty(), service.runningVehicleFor(firstTripOf(DUTY_3)));
  }

  /**
   * 事故现场的顺序：重启后留下的车先到首站，按时间接下当前那一班和它的交路。
   *
   * <p>这时那一班的出库票（首班本身从车库始发）必须认出"交路已经有车"，否则就是同一交路两辆车。
   */
  @Test
  void restoredTrainTakesTheCurrentDutyAndItsDepotTicketIsSuperseded() {
    TimetableService service = service(new ArrayList<>());

    service.scheduledDepartureAt(
        atFirstStation("train-restored", Instant.parse("2026-03-02T08:00:35Z")));

    assertEquals(Optional.of("R-1"), service.assignmentOf("train-restored").map(a -> a.tripCode()));
    assertEquals(Optional.of("train-restored"), service.runningVehicleFor(firstTripOf(DUTY_1)));
  }

  /** 下线就放手：交路的车被销毁后，出库票不再被它挡住。 */
  @Test
  void releasedTrainNoLongerSupersedesTheDepotTicket() {
    TimetableService service = service(new ArrayList<>());
    service.bindDuty("train-gone", duty(DUTY_1), "ticket-operation");

    service.release("train-gone", "destroyed");

    assertEquals(Optional.empty(), service.runningVehicleFor(firstTripOf(DUTY_1)));
  }
}
