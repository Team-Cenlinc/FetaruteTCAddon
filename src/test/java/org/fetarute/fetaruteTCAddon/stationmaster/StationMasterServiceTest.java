package org.fetarute.fetaruteTCAddon.stationmaster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.Timetable;
import org.fetarute.fetaruteTCAddon.drive.crew.CrewDuty;
import org.fetarute.fetaruteTCAddon.drive.crew.StationMasterShift;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 站长：上岗分股道、系统排当前这一列（交替、休息）、发车信号闸（超时代发）、站台扣车与催关、离站下岗。 */
class StationMasterServiceTest {

  private static final UUID WORLD = UUID.randomUUID();
  private static final StationMasterWorld.Station STATION =
      new StationMasterWorld.Station("OP", "AAA", "甲站");
  private static final NodeId T1 = NodeId.of("OP:S:AAA:1");
  private static final NodeId T2 = NodeId.of("OP:S:AAA:2");
  private static final NodeId T3 = NodeId.of("OP:S:AAA:3");

  private final UUID alice = UUID.randomUUID();
  private final UUID bob = UUID.randomUUID();
  private final UUID carol = UUID.randomUUID();
  private final UUID dave = UUID.randomUUID();
  private final UUID guard = UUID.randomUUID();

  private FakeWorld world;
  private Messages messages;
  private Instant now;
  private StationMasterService service;

  @BeforeEach
  void setUp() {
    world = new FakeWorld();
    world.tracks =
        List.of(
            new StationMasterWorld.Track(T1, 1, WORLD, 0.0, 64.0, 0.0),
            new StationMasterWorld.Track(T2, 2, WORLD, 0.0, 64.0, 10.0),
            new StationMasterWorld.Track(T3, 3, WORLD, 0.0, 64.0, 20.0));
    messages = new Messages();
    now = Instant.parse("2026-03-02T08:00:00Z");
    service = new StationMasterService(world, StationMasterConfig::defaults, () -> now, messages);
    for (UUID player : List.of(alice, bob, carol, dave)) {
      world.at(player, 0.0, 0.0);
    }
  }

  private List<Integer> tracksOf(String name) {
    return service.status().stream()
        .filter(status -> status.player().equals(name))
        .findFirst()
        .orElseThrow()
        .tracks();
  }

  /** 列车 {@code train} 停在 (x, z)，车门已开。 */
  private void stopped(String train, NodeId node, double x, double z, boolean crewDoors) {
    world.cars.put(train, List.of(new StationMasterWorld.Spot(WORLD, x, 64.0, z)));
    assign(train, node);
    service.stopOpened(train, node, Optional.of(Duration.ofSeconds(5)), crewDoors);
  }

  /** 站牌上出现这列车、巡检一次：站长手上没车又歇够了就排给他。 */
  private void assign(String train, NodeId node) {
    world.cars.computeIfAbsent(
        train, ignored -> List.of(new StationMasterWorld.Spot(WORLD, 500.0, 64.0, 500.0)));
    int track = Integer.parseInt(node.value().substring(node.value().lastIndexOf(':') + 1));
    world.arrivals.add(new StationMasterWorld.Arrival(train, track, now.plusSeconds(60)));
    service.tick();
    world.arrivals.clear();
  }

  private Optional<StationMasterDisplay.TrainRow> current(UUID player) {
    return service.snapshot(player).orElseThrow().current();
  }

  @Test
  void theFirstMasterCoversTheWholeStationAndLaterOnesSplitIt() {
    assertEquals(StationMasterService.DutyStart.STARTED, service.startDuty(alice, "alice", true));
    assertEquals(List.of(1, 2, 3), tracksOf("alice"));

    assertEquals(StationMasterService.DutyStart.STARTED, service.startDuty(bob, "bob", true));
    assertEquals(List.of(1), tracksOf("bob"), "没有时刻表时每道按 1 算，分走一道");
    assertEquals(List.of(2, 3), tracksOf("alice"));
    assertTrue(messages.keys(alice).contains("stationmaster.duty.handed"));

    assertEquals(StationMasterService.DutyStart.STARTED, service.startDuty(carol, "carol", true));
    assertEquals(List.of(2), tracksOf("carol"), "从管得最多的人手里分");
    assertEquals(List.of(3), tracksOf("alice"));

    service.endDuty(carol, StationMasterShift.EndReason.OFF_DUTY);
    assertEquals(List.of(2, 3), tracksOf("alice"), "下岗交还给分出它的人");
    assertTrue(messages.keys(alice).contains("stationmaster.duty.returned"));
  }

  @Test
  void whenTheDonorHasLeftFreeTracksGoToTheNextMaster() {
    service.startDuty(alice, "alice", true);
    service.startDuty(bob, "bob", true);
    service.endDuty(alice, StationMasterShift.EndReason.OFF_DUTY);
    assertEquals(List.of(1), tracksOf("bob"), "分出它的人已下岗：交还不了，其余股道转为自动");

    service.startDuty(carol, "carol", true);
    assertEquals(List.of(2, 3), tracksOf("carol"), "后来的人先接走没人管的股道");
  }

  @Test
  void aSingleTrackStationCannotBeSplit() {
    world.tracks = List.of(new StationMasterWorld.Track(T1, 1, WORLD, 0.0, 64.0, 0.0));
    service.startDuty(alice, "alice", true);
    assertEquals(
        StationMasterService.DutyStart.STATION_COVERED, service.startDuty(bob, "bob", true));
  }

  @Test
  void otherRolesAndMissingPermissionAreRefused() {
    service.setCrewBusy(
        player -> player.equals(alice) ? Optional.of(CrewDuty.Role.GUARD) : Optional.empty());
    assertEquals(
        StationMasterService.DutyStart.BUSY_GUARD, service.startDuty(alice, "alice", true));
    assertEquals(
        StationMasterService.DutyStart.NO_PERMISSION, service.startDuty(bob, "bob", false));
    world.station = Optional.empty();
    assertEquals(
        StationMasterService.DutyStart.NOT_AT_STATION, service.startDuty(carol, "carol", true));
    assertTrue(messages.keys(carol).contains("stationmaster.duty.start.not-at-station"));
  }

  @Test
  void anUnattendedTrackNeverHolds() {
    stopped("T-1", T1, 0.0, 0.0, false);
    assertFalse(service.holdsDeparture("T-1", T1));
  }

  @Test
  void theTrainWaitsForTheSignal() {
    service.startDuty(alice, "alice", true);
    stopped("T-1", T1, 2.0, 0.0, false);

    assertTrue(service.holdsDeparture("T-1", T1));
    assertTrue(messages.keys(alice).contains("stationmaster.signal.waiting"));
    assertTrue(service.holdsDeparture("T-1", T1), "没发信号前一直等");

    service.signal(alice);
    assertFalse(service.holdsDeparture("T-1", T1));
    assertEquals(1, service.status().get(0).signals());
    assertEquals(0, service.status().get(0).autoSignals());
  }

  @Test
  void noSignalIsGivenToATrainThatIsNotWaitingYet() {
    service.startDuty(alice, "alice", true);
    stopped("T-1", T1, 2.0, 0.0, false);
    service.signal(alice);
    assertTrue(messages.keys(alice).contains("stationmaster.signal.not-ready"));
    assertTrue(service.holdsDeparture("T-1", T1), "车门还开着时发的信号不算");
  }

  @Test
  void aSlowMasterIsCoveredByTheTimeout() {
    service.startDuty(alice, "alice", true);
    stopped("T-1", T1, 2.0, 0.0, false);
    assertTrue(service.holdsDeparture("T-1", T1));

    now = now.plusSeconds(9);
    assertTrue(service.holdsDeparture("T-1", T1));
    now = now.plusSeconds(1);
    assertFalse(service.holdsDeparture("T-1", T1), "等满 10 秒由系统代发");
    assertEquals(1, service.status().get(0).autoSignals());
    assertTrue(messages.keys(alice).contains("stationmaster.signal.timeout"));
  }

  @Test
  void aTrainOnAnotherTrackGoesAutomaticallyWhileTheMasterIsBusy() {
    service.startDuty(alice, "alice", true);
    stopped("T-1", T1, 2.0, 0.0, false);
    stopped("T-3", T3, 0.0, 200.0, false);

    assertFalse(service.holdsDeparture("T-3", T3), "站长在 1 道办 T-1：3 道的车按自动办理");
    assertEquals(0, service.status().get(0).autoSignals(), "顾不过来不记代发");
    assertTrue(service.holdsDeparture("T-1", T1));
  }

  @Test
  void aMasterAwayFromAnyTrainStillHasToCome() {
    service.startDuty(alice, "alice", true);
    stopped("T-3", T3, 0.0, 200.0, false);
    assertTrue(service.holdsDeparture("T-3", T3), "站长没在办别的车：要过去发信号");
    assertTrue(messages.keys(alice).contains("stationmaster.signal.waiting-away"));
  }

  @Test
  void holdsLastUntilReleasedOrTheLimit() {
    service.startDuty(alice, "alice", true);
    stopped("T-1", T1, 2.0, 0.0, false);

    service.hold(alice);
    assertEquals(Optional.of(now.plusSeconds(150)), service.holdTarget("T-1"));
    service.release(alice);
    assertEquals(Optional.empty(), service.holdTarget("T-1"));
    assertEquals(List.of("T-1"), world.reevaluated, "放行后立即重评");

    service.hold(alice);
    now = now.plusSeconds(150);
    service.tick();
    assertEquals(Optional.empty(), service.holdTarget("T-1"));
    assertTrue(messages.keys(alice).contains("stationmaster.hold.expired"));
  }

  @Test
  void goingOffDutyReleasesTheHolds() {
    service.startDuty(alice, "alice", true);
    stopped("T-1", T1, 2.0, 0.0, false);
    service.hold(alice);
    service.endDuty(alice, StationMasterShift.EndReason.OFF_DUTY);
    assertEquals(Optional.empty(), service.holdTarget("T-1"));
    assertEquals(List.of("T-1"), world.reevaluated);
    assertFalse(service.holdsDeparture("T-1", T1), "股道回到自动");
  }

  @Test
  void urgingWaitsForTheWindowAndReachesTheCrew() {
    service.startDuty(alice, "alice", true);
    world.crew.put("T-1", List.of(guard));
    stopped("T-1", T1, 2.0, 0.0, true);

    service.urgeClose(alice);
    assertTrue(messages.keys(alice).contains("stationmaster.close.too-early"));
    assertFalse(messages.keys(guard).contains("stationmaster.crew.close-request"));

    now = now.plusSeconds(5);
    service.urgeClose(alice);
    assertTrue(messages.keys(guard).contains("stationmaster.crew.close-request"));
    assertTrue(messages.keys(alice).contains("stationmaster.close.urged"));
  }

  @Test
  void stationOperatedDoorsNeedNoUrging() {
    service.startDuty(alice, "alice", true);
    stopped("T-1", T1, 2.0, 0.0, false);
    service.urgeClose(alice);
    assertTrue(messages.keys(alice).contains("stationmaster.close.automatic"));
  }

  @Test
  void leavingTheStationForTooLongEndsTheDuty() {
    service.startDuty(alice, "alice", true);
    world.at(alice, 500.0, 500.0);
    service.tick();
    assertTrue(messages.keys(alice).contains("stationmaster.duty.away"));
    assertTrue(service.onDuty(alice));

    now = now.plusSeconds(30);
    service.tick();
    assertFalse(service.onDuty(alice));
    assertTrue(messages.keys(alice).contains("stationmaster.duty.ended.left-station"));
  }

  @Test
  void anEndedStopIsForgotten() {
    service.startDuty(alice, "alice", true);
    stopped("T-1", T1, 2.0, 0.0, false);
    service.hold(alice);
    service.stopEnded("T-1", T2);
    assertTrue(service.holdTarget("T-1").isPresent(), "别的股道的结束不动这一站");
    service.stopEnded("T-1", T1);
    assertEquals(Optional.empty(), service.holdTarget("T-1"));
  }

  @Test
  void anEmergencyStopBrakesAMovingTrainAndHoldsItUntilReleased() {
    service.startDuty(alice, "alice", true);
    world.cars.put("T-1", List.of(new StationMasterWorld.Spot(WORLD, 3.0, 64.0, 0.0)));
    world.moving.add("T-1");
    world.crew.put("T-1", List.of(guard));
    service.sample();

    service.emergency(alice);
    assertEquals(List.of("T-1"), world.braked);
    assertTrue(service.emergencyHeld("T-1"));
    assertTrue(messages.keys(guard).contains("stationmaster.crew.emergency"));

    world.moving.remove("T-1");
    service.emergency(alice);
    assertFalse(service.emergencyHeld("T-1"), "停稳后再按一次解除");
    assertEquals(List.of("T-1"), world.refreshed, "解除后立即重算信号");
  }

  @Test
  void anEmergencyHoldExpiresAndAManualDriverIsNotLatched() {
    service.startDuty(alice, "alice", true);
    world.cars.put("T-1", List.of(new StationMasterWorld.Spot(WORLD, 3.0, 64.0, 0.0)));
    world.moving.add("T-1");
    service.sample();
    service.emergency(alice);
    now = now.plusSeconds(120);
    service.tick();
    assertFalse(service.emergencyHeld("T-1"));
    assertTrue(messages.keys(alice).contains("stationmaster.emergency.expired"));

    world.emergency = StationMasterWorld.Emergency.DRIVER;
    service.emergency(alice);
    assertFalse(service.emergencyHeld("T-1"), "人工驾驶的车由驾驶员缓解，站长一侧不扣");
  }

  @Test
  void arrivalWatchIsJudgedWhenTheTrainStops() {
    service.startDuty(alice, "alice", true);
    world.look(alice, 0.0, 0.0, 1.0, 0.0);
    world.cars.put("T-1", List.of(new StationMasterWorld.Spot(WORLD, 20.0, 64.0, 0.0)));
    world.moving.add("T-1");
    assign("T-1", T1);

    service.sample();
    assertTrue(messages.keys(alice).contains("stationmaster.cue.watching"), "动作栏实时告诉站长算不算在看");
    service.sample();
    service.stopOpened("T-1", T1, Optional.empty(), false);
    assertTrue(messages.keys(alice).contains("stationmaster.watch.arrival-ok"));
  }

  @Test
  void lookingAwayFailsTheWatch() {
    service.startDuty(alice, "alice", true);
    world.look(alice, 0.0, 0.0, -1.0, 0.0);
    world.cars.put("T-1", List.of(new StationMasterWorld.Spot(WORLD, 20.0, 64.0, 0.0)));
    world.moving.add("T-1");
    assign("T-1", T1);
    service.sample();
    assertTrue(messages.keys(alice).contains("stationmaster.cue.look"));
    service.stopOpened("T-1", T1, Optional.empty(), false);
    assertTrue(messages.keys(alice).contains("stationmaster.watch.arrival-missed"));
  }

  @Test
  void lookingAlongTheTrainCountsEvenWhenTheNearestCarIsBeside() {
    service.startDuty(alice, "alice", true);
    world.look(alice, 0.0, 2.0, 1.0, 0.0);
    world.cars.put(
        "T-1",
        List.of(
            new StationMasterWorld.Spot(WORLD, 0.0, 64.0, 0.0),
            new StationMasterWorld.Spot(WORLD, 10.0, 64.0, 0.0),
            new StationMasterWorld.Spot(WORLD, 20.0, 64.0, 0.0)));
    world.moving.add("T-1");
    assign("T-1", T1);
    service.sample();
    assertTrue(messages.keys(alice).contains("stationmaster.cue.watching"), "最近一节在正侧面，顺着车身看也算");
  }

  @Test
  void anywhereAlongTheStoppedBodyIsInPlace() {
    StationMasterWorld.StandPoint stand =
        new StationMasterWorld.StandPoint(
            new StationMasterWorld.Spot(WORLD, 100.0, 64.0, 0.0), 1.0, 0.0, 60.0);
    assertTrue(stand.beside(new StationMasterWorld.Spot(WORLD, 100.0, 64.0, 3.0), 12.0), "车头");
    assertTrue(stand.beside(new StationMasterWorld.Spot(WORLD, 60.0, 64.0, -3.0), 12.0), "车身中部");
    assertFalse(stand.beside(new StationMasterWorld.Spot(WORLD, 20.0, 64.0, 3.0), 12.0), "车尾再往后");
    assertFalse(stand.beside(new StationMasterWorld.Spot(WORLD, 60.0, 64.0, 20.0), 12.0), "隔着几条股道");
    assertFalse(stand.beside(new StationMasterWorld.Spot(WORLD, 60.0, 80.0, 3.0), 12.0), "别的楼层");
  }

  @Test
  void theStandPointDecidesWhetherTheMasterIsInPlace() {
    service.startDuty(alice, "alice", true);
    world.at(alice, 50.0, 3.0);
    world.stands.put(
        "T-1",
        new StationMasterWorld.StandPoint(
            new StationMasterWorld.Spot(WORLD, 30.0, 64.0, 0.0), 1.0, 0.0, 60.0));
    world.cars.put("T-1", List.of(new StationMasterWorld.Spot(WORLD, -20.0, 64.0, 0.0)));
    world.moving.add("T-1");
    assign("T-1", T1);
    service.sample();
    assertTrue(messages.keys(alice).contains("stationmaster.cue.stand"), "站在车头前方太远，不在车身范围内");

    world.look(alice, 0.0, 3.0, -1.0, 0.0);
    service.sample();
    assertTrue(messages.keys(alice).contains("stationmaster.cue.watching"), "车身范围内、面向列车");
  }

  @Test
  void departureWatchEndsWhenTheTrainLeaves() {
    service.startDuty(alice, "alice", true);
    stopped("T-1", T1, 20.0, 0.0, false);
    letGo("T-1", T1);
    service.stopEnded("T-1", T1);
    world.moving.add("T-1");

    service.sample();
    world.cars.put("T-1", List.of(new StationMasterWorld.Spot(WORLD, 500.0, 64.0, 0.0)));
    service.sample();
    assertTrue(messages.keys(alice).contains("stationmaster.watch.departure-ok"));
    assertTrue(current(alice).isEmpty(), "这一列办完了");
  }

  @Test
  void onlyTheAssignedTrainNeedsTheMaster() {
    service.startDuty(alice, "alice", true);
    assign("T-1", T1);
    assertEquals("T-1", current(alice).orElseThrow().train());
    assertTrue(messages.keys(alice).contains("stationmaster.job.assigned"));

    stopped("T-2", T2, 2.0, 10.0, false);
    assertFalse(service.holdsDeparture("T-2", T2), "没排给站长的车自动办理");
    assertEquals(0, service.status().get(0).autoSignals(), "不记代发");
    assertEquals("T-1", current(alice).orElseThrow().train(), "一次只办一列");
  }

  /** 站台来问、站长在车旁发信号：放行这一站（车厢位置不变）。 */
  private void letGo(String train, NodeId node) {
    List<StationMasterWorld.Spot> cars = world.cars.get(train);
    world.cars.put(train, List.of(new StationMasterWorld.Spot(WORLD, 2.0, 64.0, 0.0)));
    if (service.holdsDeparture(train, node)) {
      service.signal(alice);
    }
    world.cars.put(train, cars);
  }

  /** 办完 {@code train}：停站、放行、开走。 */
  private void finish(String train, NodeId node) {
    if (world.cars.get(train) == null) {
      world.cars.put(train, List.of(new StationMasterWorld.Spot(WORLD, 2.0, 64.0, 0.0)));
    }
    service.stopOpened(train, node, Optional.empty(), false);
    letGo(train, node);
    service.stopEnded(train, node);
    world.moving.add(train);
    world.cars.put(train, List.of(new StationMasterWorld.Spot(WORLD, 900.0, 64.0, 900.0)));
    service.sample();
  }

  @Test
  void theMasterRestsAfterATrainBeforeTheNextOne() {
    service.startDuty(alice, "alice", true);
    assign("T-1", T1);
    finish("T-1", T1);
    assertTrue(current(alice).isEmpty());
    assertEquals(30L, service.snapshot(alice).orElseThrow().restSeconds());

    assign("T-2", T1);
    assertTrue(current(alice).isEmpty(), "还在歇，不排");
    assertTrue(stoppedAutomatically("T-2", T1), "歇着时来的车自动办理");

    now = now.plusSeconds(30);
    assign("T-3", T1);
    assertEquals("T-3", current(alice).orElseThrow().train(), "歇够了才排");
  }

  private boolean stoppedAutomatically(String train, NodeId node) {
    service.stopOpened(train, node, Optional.empty(), false);
    return !service.holdsDeparture(train, node);
  }

  @Test
  void theNextTrainAlternatesBetweenTracks() {
    service.startDuty(alice, "alice", true);
    assign("T-1", T1);
    finish("T-1", T1);
    now = now.plusSeconds(30);
    world.cars.put("T-2", List.of(new StationMasterWorld.Spot(WORLD, 500.0, 64.0, 500.0)));
    world.cars.put("T-3", List.of(new StationMasterWorld.Spot(WORLD, 500.0, 64.0, 500.0)));
    world.arrivals.add(new StationMasterWorld.Arrival("T-2", 1, now.plusSeconds(60)));
    world.arrivals.add(new StationMasterWorld.Arrival("T-3", 2, now.plusSeconds(80)));
    service.tick();
    assertEquals("T-3", current(alice).orElseThrow().train(), "晚一点点的车在另一条股道：排它");
  }

  @Test
  void aMuchLaterTrainOnTheOtherTrackIsNotWorthSkippingFor() {
    service.startDuty(alice, "alice", true);
    assign("T-1", T1);
    finish("T-1", T1);
    now = now.plusSeconds(30);
    world.cars.put("T-2", List.of(new StationMasterWorld.Spot(WORLD, 500.0, 64.0, 500.0)));
    world.cars.put("T-3", List.of(new StationMasterWorld.Spot(WORLD, 500.0, 64.0, 500.0)));
    world.arrivals.add(new StationMasterWorld.Arrival("T-2", 1, now.plusSeconds(60)));
    world.arrivals.add(new StationMasterWorld.Arrival("T-3", 2, now.plusSeconds(120)));
    service.tick();
    assertEquals("T-2", current(alice).orElseThrow().train());
  }

  @Test
  void aTrainTheMasterCannotReachInTimeIsNotAssigned() {
    service.startDuty(alice, "alice", true);
    world.at(alice, 0.0, 40.0);
    world.cars.put("T-1", List.of(new StationMasterWorld.Spot(WORLD, 500.0, 64.0, 500.0)));
    world.arrivals.add(new StationMasterWorld.Arrival("T-1", 1, now.plusSeconds(3)));
    service.tick();
    assertTrue(current(alice).isEmpty(), "40 格走 8 秒，3 秒后就到：来不及");
  }

  @Test
  void aStopAtAnotherStationIsNotThisJobsStop() {
    service.startDuty(alice, "alice", true);
    assign("T-1", T1);
    NodeId elsewhere = NodeId.of("OP:S:BBB:1");
    service.stopOpened("T-1", elsewhere, Optional.empty(), false);
    assertFalse(service.holdsDeparture("T-1", elsewhere), "在前一站停站与本站站长无关");
    assertEquals(StationMasterDisplay.State.INBOUND, current(alice).orElseThrow().state());
  }

  @Test
  void aJobFollowsAPlatformChangeAndIsDroppedWhenTheTrainStopsComing() {
    service.startDuty(alice, "alice", true);
    assign("T-1", T1);
    world.arrivals.add(new StationMasterWorld.Arrival("T-1", 2, now.plusSeconds(40)));
    service.tick();
    assertEquals("2", current(alice).orElseThrow().track(), "站牌改了股道，跟着改");

    world.arrivals.clear();
    now = now.plusSeconds(45);
    service.tick();
    assertTrue(current(alice).isEmpty(), "站牌上没了、也没开到附近：不等了");
    assertTrue(messages.keys(alice).contains("stationmaster.job.dropped"));
    assertEquals(0L, service.snapshot(alice).orElseThrow().restSeconds(), "没办不用歇");
  }

  @Test
  void aLongStopAtThePreviousStationDoesNotDropTheJob() {
    service.startDuty(alice, "alice", true);
    assign("T-1", T1);
    service.stopOpened("T-1", NodeId.of("OP:S:BBB:1"), Optional.empty(), false);
    world.arrivals.add(new StationMasterWorld.Arrival("T-1", 1, now.plusSeconds(200)));
    now = now.plusSeconds(90);
    service.tick();
    assertEquals("T-1", current(alice).orElseThrow().train(), "在前一站扣着也还是这一列");
  }

  @Test
  void aTrainStoppedAtThePreviousStationCanBeAssigned() {
    service.startDuty(alice, "alice", true);
    world.cars.put("T-1", List.of(new StationMasterWorld.Spot(WORLD, 500.0, 64.0, 500.0)));
    service.stopOpened("T-1", NodeId.of("OP:S:BBB:1"), Optional.empty(), false);
    assign("T-1", T1);
    assertEquals("T-1", current(alice).orElseThrow().train());
  }

  @Test
  void aTrainIsNeverAssignedToTwoMasters() {
    service.startDuty(alice, "alice", true);
    service.startDuty(bob, "bob", true);
    assertEquals(List.of(1), tracksOf("bob"));
    world.cars.put("T-1", List.of(new StationMasterWorld.Spot(WORLD, 500.0, 64.0, 500.0)));
    world.arrivals.add(new StationMasterWorld.Arrival("T-1", 2, now.plusSeconds(60)));
    service.tick();
    assertEquals("T-1", current(alice).orElseThrow().train());

    world.arrivals.clear();
    world.arrivals.add(new StationMasterWorld.Arrival("T-1", 1, now.plusSeconds(50)));
    service.tick();
    assertTrue(current(bob).isEmpty(), "站牌改到 bob 的股道：还是 alice 办");
  }

  @Test
  void standingBesideACarIsInPlaceOnACurvedPlatform() {
    service.startDuty(alice, "alice", true);
    world.look(alice, -20.0, 40.0, 0.0, -1.0);
    world.stands.put(
        "T-1",
        new StationMasterWorld.StandPoint(
            new StationMasterWorld.Spot(WORLD, 30.0, 64.0, 0.0), 1.0, 0.0, 60.0));
    world.cars.put("T-1", List.of(new StationMasterWorld.Spot(WORLD, -20.0, 64.0, 35.0)));
    world.moving.add("T-1");
    assign("T-1", T1);
    service.sample();
    assertTrue(messages.keys(alice).contains("stationmaster.cue.watching"), "弯道上车尾已离开车头处的切线");
  }

  @Test
  void anEmergencyOnTheApproachShowsOnTheBar() {
    service.startDuty(alice, "alice", true);
    world.cars.put("T-1", List.of(new StationMasterWorld.Spot(WORLD, 3.0, 64.0, 0.0)));
    world.moving.add("T-1");
    assign("T-1", T1);
    service.sample();
    service.emergency(alice);
    assertEquals(StationMasterDisplay.State.EMERGENCY, current(alice).orElseThrow().state());
  }

  @Test
  void aLongDwellGoesBackToAutomatic() {
    service.startDuty(alice, "alice", true);
    stopped("T-1", T1, 2.0, 0.0, false);
    now = now.plusSeconds(59);
    service.tick();
    assertEquals("T-1", current(alice).orElseThrow().train(), "普通停站不交回");

    now = now.plusSeconds(1);
    service.tick();
    assertTrue(current(alice).isEmpty(), "折返停太久：站长去接下一列");
    assertEquals(0L, service.snapshot(alice).orElseThrow().restSeconds(), "干等的时间不用再歇");
    assertFalse(service.holdsDeparture("T-1", T1), "交回后自动办理");
    assertEquals(0, service.status().get(0).autoSignals(), "不记代发");
  }

  @Test
  void aHeldOrWaitingTrainStaysWithTheMaster() {
    service.startDuty(alice, "alice", true);
    stopped("T-1", T1, 2.0, 0.0, false);
    service.hold(alice);
    now = now.plusSeconds(90);
    service.tick();
    assertEquals("T-1", current(alice).orElseThrow().train(), "站长自己扣着的不交回");
    service.release(alice);

    assertTrue(service.holdsDeparture("T-1", T1));
    service.tick();
    assertEquals("T-1", current(alice).orElseThrow().train(), "在等发车信号的不交回");
  }

  @Test
  void theSignalButtonEndsTheRestEarly() {
    service.startDuty(alice, "alice", true);
    assign("T-1", T1);
    finish("T-1", T1);
    assertTrue(service.snapshot(alice).orElseThrow().restSeconds() > 0, "办完先歇着");

    service.signal(alice);

    assertEquals(0L, service.snapshot(alice).orElseThrow().restSeconds());
    assertTrue(messages.keys(alice).contains("stationmaster.job.rest-ended"));
    assign("T-2", T2);
    assertEquals("T-2", current(alice).orElseThrow().train(), "结束休息后马上能排下一列");
  }

  @Test
  void nextEndsTheRestAndSaysSoWhenNotResting() {
    service.startDuty(alice, "alice", true);
    service.next(alice);
    assertTrue(messages.keys(alice).contains("stationmaster.job.not-resting"));

    assign("T-1", T1);
    finish("T-1", T1);
    service.next(alice);
    assertEquals(0L, service.snapshot(alice).orElseThrow().restSeconds());
  }

  @Test
  void crewDoorsCanBeSignalledBeforeTheyClose() {
    service.startDuty(alice, "alice", true);
    UUID guard = UUID.randomUUID();
    world.crew.put("T-1", List.of(guard));
    stopped("T-1", T1, 2.0, 0.0, true);

    service.signal(alice);
    assertTrue(messages.keys(alice).contains("stationmaster.signal.too-early"), "关门窗口之前还不行");
    assertEquals(StationMasterDisplay.State.DWELLING, current(alice).orElseThrow().state());

    now = now.plusSeconds(5);
    assertEquals(StationMasterDisplay.State.READY, current(alice).orElseThrow().state());
    assertTrue(service.view(alice).orElseThrow().waiting(), "到窗口按钮就亮");

    service.signal(alice);
    assertEquals(1, service.status().get(0).signals());
    assertTrue(messages.keys(guard).contains("stationmaster.crew.signal"), "车上的乘务员要知道");
    assertEquals(StationMasterDisplay.State.SIGNALLED, current(alice).orElseThrow().state());

    assertFalse(service.holdsDeparture("T-1", T1), "门关好时不用再等");
    assertEquals(0, service.status().get(0).autoSignals());
  }

  @Test
  void anEarlySignalCountsOnceAndCanStillBeHeld() {
    service.startDuty(alice, "alice", true);
    stopped("T-1", T1, 2.0, 0.0, true);
    now = now.plusSeconds(5);
    service.signal(alice);

    service.signal(alice);
    assertEquals(1, service.status().get(0).signals(), "再按不重复记");
    assertTrue(messages.keys(alice).contains("stationmaster.signal.already"));

    service.hold(alice);
    assertTrue(service.holdTarget("T-1").isPresent(), "发完信号发现夹人还能扣");
    assertEquals(StationMasterDisplay.State.HELD, current(alice).orElseThrow().state());
  }

  @Test
  void anEarlySignalKeepsTheTrainWithTheMaster() {
    service.startDuty(alice, "alice", true);
    stopped("T-1", T1, 2.0, 0.0, true);
    now = now.plusSeconds(5);
    service.signal(alice);

    now = now.plus(StationMasterService.LONG_DWELL).minusSeconds(1);
    service.tick();
    assertEquals("T-1", current(alice).orElseThrow().train(), "发过信号的车先不交回自动");

    now = now.plusSeconds(1);
    service.tick();
    assertTrue(current(alice).isEmpty(), "出站一直不开、门一直不关时不能把站长占住");
    assertFalse(service.holdsDeparture("T-1", T1), "交回后按自动办理");
  }

  @Test
  void aHoldOrAClosedExitRestartsTheSignalWait() {
    service.startDuty(alice, "alice", true);
    stopped("T-1", T1, 2.0, 0.0, false);
    assertTrue(service.holdsDeparture("T-1", T1));

    now = now.plusSeconds(3);
    service.hold(alice);
    service.departureBlocked("T-1", T1);
    now = now.plusSeconds(40);
    service.release(alice);

    assertTrue(service.holdsDeparture("T-1", T1), "放行后重新等站长的信号");
    assertEquals(0, service.status().get(0).autoSignals(), "扣着的时间不算超时");
    now = now.plusSeconds(10);
    assertFalse(service.holdsDeparture("T-1", T1));
    assertEquals(1, service.status().get(0).autoSignals());
  }

  @Test
  void aSignalAlreadyGivenSurvivesAClosedExit() {
    service.startDuty(alice, "alice", true);
    stopped("T-1", T1, 2.0, 0.0, false);
    service.holdsDeparture("T-1", T1);
    service.signal(alice);

    service.departureBlocked("T-1", T1);

    assertFalse(service.holdsDeparture("T-1", T1));
    assertEquals(1, service.status().get(0).signals());
  }

  @Test
  void aStopThatEndsWithoutReleaseIsNotADeparture() {
    service.startDuty(alice, "alice", true);
    stopped("T-1", T1, 2.0, 0.0, false);

    service.stopEnded("T-1", T1);

    assertTrue(current(alice).isEmpty(), "车没开走：这一列不用办了");
    service.sample();
    now = now.plusSeconds(30);
    service.sample();
    assertFalse(messages.keys(alice).contains("stationmaster.watch.departure-missed"));
    assertTrue(service.snapshot(alice).orElseThrow().restSeconds() == 0L, "没办完不用歇");
  }

  @Test
  void anEmergencyThatDidNotTakeDoesNotAlertTheCrew() {
    service.startDuty(alice, "alice", true);
    world.cars.put("T-1", List.of(new StationMasterWorld.Spot(WORLD, 3.0, 64.0, 0.0)));
    world.moving.add("T-1");
    world.crew.put("T-1", List.of(guard));
    world.emergency = StationMasterWorld.Emergency.UNAVAILABLE;

    service.emergency(alice);

    assertTrue(messages.keys(alice).contains("stationmaster.emergency.unavailable"));
    assertFalse(messages.keys(guard).contains("stationmaster.crew.emergency"));
  }

  @Test
  void anotherRealTrainStillGetsTheEmergencyDuringASimulation() {
    service.startDuty(alice, "alice", true);
    stopped("T-1", T1, 2.0, 0.0, false);
    assertTrue(service.drill(alice));
    world.cars.put("T-2", List.of(new StationMasterWorld.Spot(WORLD, 3.0, 64.0, 8.0)));
    world.moving.add("T-2");

    service.emergency(alice);

    assertEquals(List.of("T-2"), world.braked);
    assertFalse(messages.keys(alice).contains("stationmaster.event.wrong"), "这一按给了真车，没拿去答题");
  }

  @Test
  void aHeldTrainIsNotUsedForASimulationSoTheKeyStillReleasesIt() {
    service.startDuty(alice, "alice", true);
    stopped("T-1", T1, 2.0, 0.0, false);
    service.toggleHold(alice);

    assertFalse(service.drill(alice), "扣着的车不出题");

    service.toggleHold(alice);
    assertEquals(Optional.empty(), service.holdTarget("T-1"));
  }

  @Test
  void nothingIsTrackedWhileNobodyIsOnDuty() {
    world.cars.put("T-1", List.of(new StationMasterWorld.Spot(WORLD, 2.0, 64.0, 0.0)));

    service.stopOpened("T-1", T1, Optional.empty(), false);
    assertFalse(service.holdsDeparture("T-1", T1));

    assertEquals(0, world.lastStopQueries, "没人在岗时不查交路");
  }

  @Test
  void longRunsOfTracksAreWrittenAsARange() {
    assertEquals(
        "1-4、7、9、10", StationMasterDisplay.compactTracks(List.of(1, 2, 3, 4, 7, 9, 10), "、"));
    assertEquals("1、2", StationMasterDisplay.compactTracks(List.of(1, 2), "、"));
    assertEquals("", StationMasterDisplay.compactTracks(List.of(), "、"));
  }

  @Test
  void stationDoorsStillWaitForTheDoorsToClose() {
    service.startDuty(alice, "alice", true);
    stopped("T-1", T1, 2.0, 0.0, false);
    now = now.plusSeconds(5);

    service.signal(alice);

    assertEquals(0, service.status().get(0).signals());
    assertTrue(messages.keys(alice).contains("stationmaster.signal.not-ready"));
  }

  @Test
  void theSidebarShowsOneShortFactPerRow() {
    service.startDuty(alice, "alice", true);
    stopped("T-1", T1, 2.0, 0.0, false);
    assertEquals(
        Optional.of(false), service.snapshot(alice).orElseThrow().doorsClosed(), "登记停站时门开着");

    service.doorsClosed("T-1", T1, true);
    StationMasterDisplay.Snapshot snapshot = service.snapshot(alice).orElseThrow();
    assertEquals(Optional.of(true), snapshot.doorsClosed());
    assertEquals(100, snapshot.points());

    List<String> labels =
        StationMasterDisplay.rows(snapshot).stream()
            .map(row -> row.labelKey().substring(row.labelKey().lastIndexOf('.') + 1))
            .toList();
    assertEquals(List.of("tracks", "train", "stop", "work", "doors", "crew", "score"), labels);
  }

  private void practising() {
    service.setExaminer(
        new StationMasterExaminer() {
          @Override
          public boolean practicing(UUID player) {
            return true;
          }
        });
  }

  private List<String> coached(UUID player) {
    return messages.keys(player).stream()
        .filter(key -> key.startsWith("stationmaster.coach."))
        .map(key -> key.substring("stationmaster.coach.".length()))
        .toList();
  }

  @Test
  void practiceCoachesEachStepOnce() {
    practising();
    service.startDuty(alice, "alice", true);
    service.tick();
    service.tick();
    assertEquals(List.of("idle"), coached(alice), "同一步不重复说");

    assign("T-1", T1);
    service.tick();
    service.stopOpened("T-1", T1, Optional.of(Duration.ofSeconds(5)), true);
    service.tick();
    now = now.plusSeconds(5);
    service.tick();
    world.cars.put("T-1", List.of(new StationMasterWorld.Spot(WORLD, 2.0, 64.0, 0.0)));
    service.signal(alice);
    service.tick();
    service.holdsDeparture("T-1", T1);
    service.stopEnded("T-1", T1);
    service.tick();

    assertEquals(
        List.of("idle", "inbound", "dwelling", "ready", "signalled", "departing"), coached(alice));
  }

  @Test
  void practiceCoachesTheRestAndTheSimulation() {
    practising();
    service.startDuty(alice, "alice", true);
    assign("T-1", T1);
    finish("T-1", T1);
    service.tick();
    assertTrue(coached(alice).contains("rest"));

    now = now.plusSeconds(30);
    world.cars.put("T-2", List.of(new StationMasterWorld.Spot(WORLD, 2.0, 64.0, 0.0)));
    stopped("T-2", T1, 2.0, 0.0, false);
    assertTrue(service.drill(alice), "停站的车能出题");
    assertTrue(coached(alice).stream().anyMatch(step -> step.startsWith("sim-")), "告诉该按哪个键");
  }

  @Test
  void examsAndOrdinaryShiftsHaveNoCoach() {
    service.startDuty(alice, "alice", true);
    stopped("T-1", T1, 2.0, 0.0, false);
    service.tick();

    assertTrue(coached(alice).isEmpty());
  }

  @Test
  void aVanishedTrainIsDroppedWithoutScore() {
    service.startDuty(alice, "alice", true);
    assign("T-1", T1);
    world.cars.remove("T-1");
    service.sample();
    assertTrue(current(alice).isEmpty());
    assertEquals(0, service.status().get(0).autoSignals());
  }

  @Test
  void aLastPassengerStopIsClearedBeforeTheSignal() {
    UUID rider = UUID.randomUUID();
    service.startDuty(alice, "alice", true);
    world.lastStops.add("T-1");
    world.riders.put("T-1", List.of(rider));
    stopped("T-1", T1, 2.0, 0.0, false);

    assertTrue(service.holdsDeparture("T-1", T1));
    assertEquals(List.of("T-1"), world.announced);
    assertTrue(messages.keys(alice).contains("stationmaster.clearing.started"));
    assertFalse(messages.keys(alice).contains("stationmaster.signal.waiting"), "清完客才等发车信号");

    now = now.plusSeconds(20);
    assertTrue(service.holdsDeparture("T-1", T1), "送走乘客后开始等发车信号");
    assertEquals(List.of(rider), world.sent);
    assertTrue(messages.keys(alice).contains("stationmaster.signal.waiting"));
  }

  @Test
  void aTrainThatEmptiesOnItsOwnNeedsNoTeleport() {
    service.startDuty(alice, "alice", true);
    world.lastStops.add("T-1");
    world.riders.put("T-1", List.of(UUID.randomUUID()));
    stopped("T-1", T1, 2.0, 0.0, false);
    assertTrue(service.holdsDeparture("T-1", T1));

    world.riders.put("T-1", List.of());
    assertTrue(service.holdsDeparture("T-1", T1));
    assertTrue(messages.keys(alice).contains("stationmaster.clearing.done"));
    assertTrue(world.sent.isEmpty());
  }

  @Test
  void callsGoToTheSignallerWithACooldown() {
    List<String> called = new ArrayList<>();
    service.setSignallerLink(
        (caller, role, train, reason) -> {
          called.add(role + ":" + train + ":" + reason);
          return org.fetarute.fetaruteTCAddon.drive.crew.CallResult.of(
              org.fetarute.fetaruteTCAddon.drive.crew.CallResult.Status.NO_SIGNALLER);
        });
    service.startDuty(alice, "alice", true);
    service.call(alice, "T-1", org.fetarute.fetaruteTCAddon.drive.crew.CallReason.GENERAL);
    service.call(alice, "T-1", org.fetarute.fetaruteTCAddon.drive.crew.CallReason.GENERAL);
    assertEquals(List.of("STATION_MASTER:T-1:GENERAL"), called);
    assertTrue(messages.keys(alice).contains("stationmaster.call.none"));
    assertTrue(messages.keys(alice).contains("stationmaster.call.cooldown"));
  }

  @Test
  void theOffButtonNeedsTwoPresses() {
    service.startDuty(alice, "alice", true);
    service.pressOff(alice);
    assertTrue(service.onDuty(alice));
    assertTrue(service.view(alice).orElseThrow().offConfirm());
    service.pressOff(alice);
    assertFalse(service.onDuty(alice));
  }

  @Test
  void aDrillIsAnsweredWithoutTouchingTheTrain() {
    service.startDuty(alice, "alice", true);
    stopped("T-1", T1, 2.0, 0.0, false);
    service.setRandom(new java.util.SplittableRandom(1));
    assertTrue(service.drill(alice));

    service.hold(alice);
    assertEquals(Optional.empty(), service.holdTarget("T-1"), "答题的这一按不真的扣车");
    assertTrue(messages.keys(alice).contains("stationmaster.event.right"));
  }

  @Test
  void aWrongOrLateAnswerCounts() {
    List<StationMasterExaminer.Event> events = new ArrayList<>();
    service.setExaminer(
        new StationMasterExaminer() {
          @Override
          public void onEvent(UUID player, Event event) {
            events.add(event);
          }
        });
    service.startDuty(alice, "alice", true);
    stopped("T-1", T1, 2.0, 0.0, false);
    assertTrue(service.drill(alice));
    service.emergency(alice);
    assertEquals(List.of(StationMasterExaminer.Event.SIM_WRONG), events);
    assertTrue(world.braked.isEmpty(), "答题的这一按不真的紧急停车");

    assertTrue(service.drill(alice));
    now = now.plusSeconds(15);
    service.tick();
    assertEquals(StationMasterExaminer.Event.SIM_MISSED, events.get(events.size() - 1));
  }

  @Test
  void eventsComeAfterTheWarmupWhenATrainIsAtThePlatform() {
    service.setRandom(new java.util.SplittableRandom(7));
    service.startDuty(alice, "alice", true);
    stopped("T-1", T1, 2.0, 0.0, false);
    service.tick();
    assertTrue(
        messages.keys(alice).stream().noneMatch(key -> key.startsWith("stationmaster.event.")),
        "上岗后先缓一阵");
    now = now.plusSeconds(60 + 3600);
    stopped("T-2", T1, 2.0, 0.0, false);
    now = now.plusSeconds(5);
    service.tick();
    assertTrue(
        messages.keys(alice).stream()
            .anyMatch(
                key ->
                    key.equals("stationmaster.event.trapped")
                        || key.equals("stationmaster.event.running")));
  }

  @Test
  void anExamineeGetsOnlyTheExaminersDrillAndTheShiftIsMarked() {
    List<StationMasterShift> settled = new ArrayList<>();
    service.setSettlement(settled::add);
    service.setExaminer(
        new StationMasterExaminer() {
          @Override
          public boolean examining(UUID player) {
            return true;
          }
        });
    service.startDuty(alice, "alice", true);
    stopped("T-1", T1, 2.0, 0.0, false);
    now = now.plusSeconds(60 + 3600);
    service.tick();
    assertTrue(
        messages.keys(alice).stream().noneMatch(key -> key.startsWith("stationmaster.event.")));
    service.endDuty(alice, StationMasterShift.EndReason.OFF_DUTY);
    assertTrue(settled.get(0).examined());
  }

  @Test
  void aPieceAlreadyHandedOutIsNeverCutAgain() {
    world.tracks =
        List.of(
            new StationMasterWorld.Track(T1, 1, WORLD, 0.0, 64.0, 0.0),
            new StationMasterWorld.Track(T2, 2, WORLD, 0.0, 64.0, 10.0),
            new StationMasterWorld.Track(T3, 3, WORLD, 0.0, 64.0, 20.0),
            new StationMasterWorld.Track(NodeId.of("OP:S:AAA:4"), 4, WORLD, 0.0, 64.0, 30.0));
    service.startDuty(alice, "alice", true);
    service.startDuty(bob, "bob", true);
    service.startDuty(carol, "carol", true);
    assertEquals(List.of(1, 2), tracksOf("bob"));
    assertEquals(List.of(4), tracksOf("alice"));

    assertEquals(
        StationMasterService.DutyStart.STATION_COVERED,
        service.startDuty(dave, "dave", true),
        "第一名站长只剩一道：分出去的 1、2 道不再切");
    assertEquals(List.of(1, 2), tracksOf("bob"));
  }

  @Test
  void clearingStartsWhenTheDoorsOpen() {
    service.startDuty(alice, "alice", true);
    world.lastStops.add("T-1");
    world.riders.put("T-1", List.of(UUID.randomUUID()));
    stopped("T-1", T1, 2.0, 0.0, false);
    assertEquals(List.of("T-1"), world.announced, "开门就广播，乘客趁停站下车");

    now = now.plusSeconds(20);
    service.tick();
    assertEquals(1, world.sent.size(), "停站中到时限就送到站台，不等关门");
  }

  @Test
  void aTrainLeavingFromAnotherTrackKeepsTheMasterBusy() {
    service.startDuty(alice, "alice", true);
    stopped("T-1", T1, 2.0, 0.0, false);
    letGo("T-1", T1);
    service.stopEnded("T-1", T1);
    stopped("T-3", T3, 0.0, 200.0, false);
    assertFalse(service.holdsDeparture("T-3", T3), "站长正看着 1 道的车出站：3 道的车按自动办理");
    assertEquals(0, service.status().get(0).autoSignals());
  }

  @Test
  void anArrivalIsNotJudgedWhileTheMasterWorksAStopOnAnotherTrack() {
    service.startDuty(alice, "alice", true);
    stopped("T-1", T1, 2.0, 0.0, false);
    world.cars.put("T-3", List.of(new StationMasterWorld.Spot(WORLD, 0.0, 64.0, 25.0)));
    world.moving.add("T-3");
    service.sample();
    service.stopOpened("T-3", T3, Optional.empty(), false);
    assertFalse(messages.keys(alice).contains("stationmaster.watch.arrival-missed"));
    assertFalse(messages.keys(alice).contains("stationmaster.watch.arrival-absent"));
  }

  @Test
  void notBeingOnThePlatformIsAnUnattendedArrival() {
    service.startDuty(alice, "alice", true);
    world.at(alice, 0.0, -30.0);
    world.cars.put("T-1", List.of(new StationMasterWorld.Spot(WORLD, 5.0, 64.0, 0.0)));
    world.moving.add("T-1");
    assign("T-1", T1);
    service.sample();
    service.stopOpened("T-1", T1, Optional.empty(), false);
    assertTrue(messages.keys(alice).contains("stationmaster.watch.arrival-absent"));
    assertFalse(messages.keys(alice).contains("stationmaster.watch.arrival-missed"));
  }

  @Test
  void theEmergencyButtonOnlyStopsTrainsOnOwnTracksButNotJustTheCurrentOne() {
    service.startDuty(alice, "alice", true);
    service.startDuty(bob, "bob", true);
    world.cars.put("X-1", List.of(new StationMasterWorld.Spot(WORLD, 3.0, 64.0, 0.0)));
    world.moving.add("X-1");
    service.emergency(alice);
    assertTrue(world.braked.isEmpty(), "1 道归 bob");
    assertTrue(messages.keys(alice).contains("stationmaster.emergency.no-train"));

    world.at(alice, 0.0, 10.0);
    world.cars.put("X-2", List.of(new StationMasterWorld.Spot(WORLD, 3.0, 64.0, 10.0)));
    world.moving.add("X-2");
    world.cars.remove("X-1");
    service.emergency(alice);
    assertEquals(List.of("X-2"), world.braked, "没排给站长的车也能紧急停车");
  }

  @Test
  void holdingAndUrgingOnlyReachTheCurrentTrain() {
    service.startDuty(alice, "alice", true);
    assign("T-1", T1);
    stopped("T-2", T2, 2.0, 2.0, true);
    service.hold(alice);
    assertEquals(Optional.empty(), service.holdTarget("T-2"));
    assertTrue(messages.keys(alice).contains("stationmaster.hold.no-train"));
    service.urgeClose(alice);
    assertTrue(messages.keys(alice).contains("stationmaster.close.no-train"));
  }

  @Test
  void noDrillWhileTheTrainWaitsForTheSignal() {
    service.startDuty(alice, "alice", true);
    stopped("T-1", T1, 2.0, 0.0, false);
    assertTrue(service.holdsDeparture("T-1", T1));
    assertFalse(service.drill(alice), "有车在等发车信号时不出题");
  }

  @Test
  void aSignalPressGoesToTheWaitingTrainEvenWithADrillPending() {
    service.startDuty(alice, "alice", true);
    stopped("T-1", T1, 2.0, 0.0, false);
    assertTrue(service.drill(alice));
    assertTrue(service.holdsDeparture("T-1", T1));

    service.signal(alice);
    assertFalse(service.holdsDeparture("T-1", T1), "真车拿到信号");
    assertFalse(messages.keys(alice).contains("stationmaster.event.wrong"), "这一按不算答题");
  }

  @Test
  void theBarSaysWhatToDoNow() {
    service.startDuty(alice, "alice", true);
    StationMasterDisplay.Bar idle =
        StationMasterDisplay.bar(service.snapshot(alice).orElseThrow(), 10, 150);
    assertEquals("stationmaster.bar.idle", idle.key());

    stopped("T-1", T1, 2.0, 0.0, false);
    service.holdsDeparture("T-1", T1);
    StationMasterDisplay.Bar waiting =
        StationMasterDisplay.bar(service.snapshot(alice).orElseThrow(), 10, 150);
    assertEquals("stationmaster.bar.waiting", waiting.key());
    assertTrue(waiting.urgent());
  }

  /** 假世界：玩家位置、车站股道、列车车厢与乘务员都由测试摆放。 */
  private static final class FakeWorld implements StationMasterWorld {
    final Map<UUID, PlayerSpot> players = new HashMap<>();
    Optional<Station> station = Optional.of(STATION);
    List<Track> tracks = List.of();
    final Map<String, List<Spot>> cars = new HashMap<>();
    final Map<String, List<UUID>> crew = new HashMap<>();
    final List<String> reevaluated = new ArrayList<>();
    final java.util.Set<String> moving = new java.util.HashSet<>();
    Emergency emergency = Emergency.BRAKING;
    final List<String> braked = new ArrayList<>();
    final List<String> refreshed = new ArrayList<>();
    final java.util.Set<String> lastStops = new java.util.HashSet<>();
    int lastStopQueries;
    final Map<String, List<UUID>> riders = new HashMap<>();
    final List<String> announced = new ArrayList<>();
    final List<UUID> sent = new ArrayList<>();
    final List<Arrival> arrivals = new ArrayList<>();
    final Map<String, StandPoint> stands = new HashMap<>();

    /** 玩家站在 (x, z)，面朝 +X。 */
    void at(UUID player, double x, double z) {
      look(player, x, z, 1.0, 0.0);
    }

    void look(UUID player, double x, double z, double lookX, double lookZ) {
      players.put(player, new PlayerSpot("p", new Spot(WORLD, x, 64.0, z), true, lookX, lookZ));
    }

    @Override
    public Optional<PlayerSpot> player(UUID player) {
      return Optional.ofNullable(players.get(player));
    }

    @Override
    public List<Station> stations() {
      return List.of(STATION);
    }

    @Override
    public Optional<Station> nearestStation(UUID player) {
      return station;
    }

    @Override
    public List<Track> tracks(Station station) {
      return tracks;
    }

    @Override
    public List<Arrival> arrivals(Station station, Duration horizon) {
      return arrivals;
    }

    @Override
    public List<Timetable> timetables() {
      return List.of();
    }

    @Override
    public List<Spot> cars(String trainName) {
      return cars.getOrDefault(trainName, List.of());
    }

    @Override
    public Optional<TrainNear> train(String trainName) {
      return Optional.ofNullable(cars.get(trainName))
          .map(spots -> new TrainNear(trainName, spots, moving.contains(trainName)));
    }

    @Override
    public Optional<StandPoint> standPoint(String trainName, NodeId node) {
      return Optional.ofNullable(stands.get(trainName));
    }

    @Override
    public String trainLabel(String trainName) {
      return trainName;
    }

    @Override
    public List<UUID> crew(String trainName) {
      return crew.getOrDefault(trainName, List.of());
    }

    @Override
    public CrewOnTrain crewOn(String trainName) {
      return CrewOnTrain.AUTO;
    }

    @Override
    public void reevaluate(String trainName) {
      reevaluated.add(trainName);
    }

    @Override
    public List<TrainNear> trainsNear(Spot center, double range) {
      List<TrainNear> near = new ArrayList<>();
      cars.forEach(
          (train, spots) -> {
            if (spots.stream().anyMatch(car -> car.near(center, range))) {
              near.add(new TrainNear(train, spots, moving.contains(train)));
            }
          });
      return near;
    }

    @Override
    public Emergency emergencyStop(String trainName) {
      braked.add(trainName);
      return moving.contains(trainName) ? emergency : Emergency.NOT_MOVING;
    }

    @Override
    public void refreshSignal(String trainName) {
      refreshed.add(trainName);
    }

    @Override
    public boolean lastPassengerStop(String trainName, NodeId node) {
      lastStopQueries++;
      return lastStops.contains(trainName);
    }

    @Override
    public List<UUID> riders(String trainName) {
      return riders.getOrDefault(trainName, List.of());
    }

    @Override
    public void announceClearing(String trainName) {
      announced.add(trainName);
    }

    @Override
    public void sendToPlatform(UUID rider, NodeId node) {
      sent.add(rider);
    }
  }

  /** 记下发给每个人的语言键。 */
  private static final class Messages implements StationMasterMessenger {
    final Map<UUID, List<String>> sent = new HashMap<>();

    List<String> keys(UUID player) {
      return sent.getOrDefault(player, List.of());
    }

    private void record(UUID player, String key) {
      sent.computeIfAbsent(player, ignored -> new ArrayList<>()).add(key);
    }

    @Override
    public void send(UUID player, String key, Map<String, String> values) {
      record(player, key);
    }

    @Override
    public void alert(UUID player, String key, Map<String, String> values) {
      record(player, key);
    }

    @Override
    public void notice(UUID player, String key, Map<String, String> values) {
      record(player, key);
    }

    @Override
    public void cue(UUID player, String key, Map<String, String> values) {
      record(player, key);
    }

    @Override
    public String text(String key) {
      return "、";
    }
  }
}
