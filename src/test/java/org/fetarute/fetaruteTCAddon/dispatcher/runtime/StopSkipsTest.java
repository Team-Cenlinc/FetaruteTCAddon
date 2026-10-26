package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.junit.jupiter.api.Test;

/** 信号员越站：认下一个停车站，按禁越清单与刹车锁定判能不能越，下令、撤销与过站后的清理。 */
class StopSkipsTest {

  private static final String TRAIN = "SURC-WS-A-0001";
  private static final UUID ROUTE_UUID = UUID.randomUUID();
  private static final NodeId AAA = NodeId.of("SURC:S:AAA:1");
  private static final NodeId BBB = NodeId.of("SURC:S:BBB:1");
  private static final NodeId CCC = NodeId.of("SURC:S:CCC:1");
  private static final NodeId DDD = NodeId.of("SURC:S:DDD:1");

  /** 交路：AAA 停 → BBB 通过 → CCC 停 → DDD 停（终点）。 */
  private static final RouteDefinition ROUTE =
      new RouteDefinition(
          RouteId.of("SURC:WS:WS-N"), List.of(AAA, BBB, CCC, DDD), Optional.empty());

  private static final class Fixture {
    final RouteDefinitionCache routes = mock(RouteDefinitionCache.class);
    final RouteProgressRegistry progress = new RouteProgressRegistry();

    /** 已开始为这一站刹车的车。 */
    final java.util.Set<String> braking = new java.util.HashSet<>();

    /** 别的模块不许越的站：车 → 原因。 */
    final java.util.Map<String, StopSkips.Refusal> guarded = new java.util.HashMap<>();

    final StopSkips skips =
        new StopSkips(
            progress,
            routes,
            (train, routeUuid, stopIndex) -> braking.contains(train),
            (train, node) -> Optional.ofNullable(guarded.get(train)));

    Fixture() {
      when(routes.findById(ROUTE_UUID)).thenReturn(Optional.of(ROUTE));
      stop(0, AAA, RouteStopPassType.STOP);
      stop(1, BBB, RouteStopPassType.PASS);
      stop(2, CCC, RouteStopPassType.STOP);
      stop(3, DDD, RouteStopPassType.STOP);
    }

    void stop(int index, NodeId node, RouteStopPassType passType) {
      when(routes.findStop(ROUTE.id(), index))
          .thenReturn(Optional.of(RuntimeDispatchTestFixtures.routeStop(index, node, passType)));
    }

    void at(int index) {
      progress.advance(
          TRAIN,
          ROUTE_UUID,
          ROUTE,
          index,
          new RuntimeDispatchTestFixtures.TagStore(TRAIN).properties(),
          Instant.now());
    }
  }

  @Test
  void theCandidateIsTheNextStoppingStationSkippingPassStops() {
    Fixture f = new Fixture();
    f.at(0);

    StopSkips.Candidate candidate = f.skips.candidate(TRAIN).orElseThrow();

    assertEquals(2, candidate.stopIndex());
    assertEquals(CCC, candidate.node());
    assertTrue(candidate.refusal().isEmpty());
  }

  @Test
  void theTerminusCannotBeSkipped() {
    Fixture f = new Fixture();
    f.at(2);

    StopSkips.Candidate candidate = f.skips.candidate(TRAIN).orElseThrow();

    assertEquals(3, candidate.stopIndex());
    assertEquals(Optional.of(StopSkips.Refusal.TERMINAL), candidate.refusal());
  }

  @Test
  void aStopWithAnActionDirectiveCannotBeSkipped() {
    Fixture f = new Fixture();
    when(f.routes.findStop(ROUTE.id(), 2))
        .thenReturn(Optional.of(RuntimeDispatchTestFixtures.dynamicStop(2, CCC, "CHANGE:SURC:L2")));
    f.at(0);

    assertEquals(
        Optional.of(StopSkips.Refusal.DIRECTIVE), f.skips.candidate(TRAIN).orElseThrow().refusal());
  }

  @Test
  void aDynamicPlatformAloneDoesNotStopASkip() {
    Fixture f = new Fixture();
    when(f.routes.findStop(ROUTE.id(), 2))
        .thenReturn(
            Optional.of(
                RuntimeDispatchTestFixtures.dynamicStop(2, CCC, "DYNAMIC:SURC:S:CCC:[1:2]")));
    f.at(0);

    assertTrue(f.skips.candidate(TRAIN).orElseThrow().refusal().isEmpty());
  }

  @Test
  void oncePastTheBrakingPointTheStopIsLocked() {
    Fixture f = new Fixture();
    f.at(0);
    f.braking.add(TRAIN);

    assertEquals(
        Optional.of(StopSkips.Refusal.BRAKING), f.skips.candidate(TRAIN).orElseThrow().refusal());
  }

  @Test
  void anOrderedSkipIsSeenByTheReadPointsAndShownAsOrdered() {
    Fixture f = new Fixture();
    f.at(0);

    assertEquals(StopSkips.Outcome.ORDERED, f.skips.order(TRAIN).outcome());

    assertTrue(f.skips.skipped(TRAIN, ROUTE_UUID, 2));
    assertFalse(f.skips.skipped(TRAIN, ROUTE_UUID, 3));
    StopSkips.Candidate candidate = f.skips.candidate(TRAIN).orElseThrow();
    assertEquals(2, candidate.stopIndex());
    assertTrue(candidate.ordered());
  }

  @Test
  void aSkipCanBeRevokedOnlyBeforeBrakingStarts() {
    Fixture f = new Fixture();
    f.at(0);
    f.skips.order(TRAIN);

    assertEquals(StopSkips.Outcome.REVOKED, f.skips.revoke(TRAIN).outcome());
    assertFalse(f.skips.skipped(TRAIN, ROUTE_UUID, 2));

    f.skips.order(TRAIN);
    f.braking.add(TRAIN);
    StopSkips.Result late = f.skips.revoke(TRAIN);
    assertEquals(StopSkips.Outcome.REFUSED, late.outcome());
    assertEquals(Optional.of(StopSkips.Refusal.BRAKING), late.refusal());
    assertTrue(f.skips.skipped(TRAIN, ROUTE_UUID, 2));
  }

  @Test
  void theOrderLastsThroughTheSkippedStationAndClearsOncePast() {
    Fixture f = new Fixture();
    f.at(0);
    f.skips.order(TRAIN);

    f.at(2);
    assertTrue(f.skips.skipped(TRAIN, ROUTE_UUID, 2), "压上被越的那一站时仍要认得");

    f.at(3);
    assertFalse(f.skips.skipped(TRAIN, ROUTE_UUID, 2));
    f.at(0);
    assertFalse(f.skips.skipped(TRAIN, ROUTE_UUID, 2), "过站后清掉，下一趟同一站不受影响");
  }

  /** 别的交路在这一站终到（短交路、折返站）：哪趟车都不能越它。 */
  @Test
  void aStationWhereAnyRouteTerminatesCannotBeSkipped() {
    Fixture f = new Fixture();
    RouteDefinition shortTurn =
        new RouteDefinition(RouteId.of("SURC:WS:WS-S"), List.of(AAA, BBB, CCC), Optional.empty());
    when(f.routes.entries())
        .thenReturn(
            List.of(
                new RouteDefinitionCache.RouteEntry(
                    UUID.randomUUID(),
                    shortTurn,
                    new RouteDefinitionCache.RouteRecord(
                        mock(org.fetarute.fetaruteTCAddon.company.model.Operator.class),
                        mock(org.fetarute.fetaruteTCAddon.company.model.Line.class),
                        mock(org.fetarute.fetaruteTCAddon.company.model.Route.class)),
                    List.of(
                        RuntimeDispatchTestFixtures.routeStop(0, AAA, RouteStopPassType.STOP),
                        RuntimeDispatchTestFixtures.routeStop(1, BBB, RouteStopPassType.PASS),
                        RuntimeDispatchTestFixtures.routeStop(
                            2, NodeId.of("SURC:S:CCC:2"), RouteStopPassType.TERMINATE)))));
    f.at(0);

    StopSkips.Candidate candidate = f.skips.candidate(TRAIN).orElseThrow();

    assertEquals(2, candidate.stopIndex());
    assertEquals(Optional.of(StopSkips.Refusal.TERMINAL), candidate.refusal(), "同站别的股道终到也算");
  }

  /** 叫车与乘务员任务按 TrainCarts 车名认，越站按调度逻辑名问：同一列车的几个名字逐个问，任一个被拦就拦。 */
  @Test
  void theGuardIsAskedUnderEveryNameOfTheTrain() {
    StopSkips.Guard byTcName =
        (train, node) ->
            "tc-0001".equals(train)
                ? Optional.of(StopSkips.Refusal.CREW_BOARDING)
                : Optional.empty();
    StopSkips.Guard guard =
        StopSkips.Guard.acrossNames(
            train -> TRAIN.equals(train) ? List.of(TRAIN, "tc-0001") : List.of(train), byTcName);

    assertEquals(Optional.of(StopSkips.Refusal.CREW_BOARDING), guard.refuse(TRAIN, CCC));
    assertEquals(Optional.empty(), guard.refuse("SURC-WS-A-0002", CCC));
  }

  /** 交路在这一站原路折返（前后是同一个节点）：越过去车就冲出折返点，不能越。 */
  @Test
  void aStationWhereTheRouteReversesCannotBeSkipped() {
    Fixture f = new Fixture();
    RouteDefinition reversing =
        new RouteDefinition(
            RouteId.of("SURC:WS:WS-R"), List.of(AAA, BBB, CCC, BBB, DDD), Optional.empty());
    when(f.routes.findById(ROUTE_UUID)).thenReturn(Optional.of(reversing));
    when(f.routes.findStop(reversing.id(), 0))
        .thenReturn(
            Optional.of(RuntimeDispatchTestFixtures.routeStop(0, AAA, RouteStopPassType.STOP)));
    when(f.routes.findStop(reversing.id(), 1))
        .thenReturn(
            Optional.of(RuntimeDispatchTestFixtures.routeStop(1, BBB, RouteStopPassType.PASS)));
    when(f.routes.findStop(reversing.id(), 2))
        .thenReturn(
            Optional.of(RuntimeDispatchTestFixtures.routeStop(2, CCC, RouteStopPassType.STOP)));
    when(f.routes.findStop(reversing.id(), 3))
        .thenReturn(
            Optional.of(RuntimeDispatchTestFixtures.routeStop(3, BBB, RouteStopPassType.PASS)));
    when(f.routes.findStop(reversing.id(), 4))
        .thenReturn(
            Optional.of(RuntimeDispatchTestFixtures.routeStop(4, DDD, RouteStopPassType.STOP)));
    f.progress.advance(
        TRAIN,
        ROUTE_UUID,
        reversing,
        0,
        new RuntimeDispatchTestFixtures.TagStore(TRAIN).properties(),
        Instant.now());

    StopSkips.Candidate candidate = f.skips.candidate(TRAIN).orElseThrow();

    assertEquals(2, candidate.stopIndex());
    assertEquals(Optional.of(StopSkips.Refusal.REVERSAL), candidate.refusal());
  }

  /** 压上被越那一站的站牌时，进度标签多半还停在上一个节点（停车站要到站后才推进）：按节点认出本站。 */
  @Test
  void theSkippedStationIsRecognisedAtItsSignBeforeProgressReachesIt() {
    Fixture f = new Fixture();
    f.at(0);
    f.skips.order(TRAIN);
    f.at(1);

    assertTrue(f.skips.skippedAt(TRAIN, ROUTE_UUID, OptionalInt.of(1), CCC));
    assertTrue(f.skips.skippedAt(TRAIN, ROUTE_UUID, OptionalInt.of(2), CCC), "标签已推进到本站也认");
    assertFalse(f.skips.skippedAt(TRAIN, ROUTE_UUID, OptionalInt.of(1), DDD));
  }

  /** 读不改写：改名时进度先迁到新名，这个间隙里按旧名查不到进度，别的线程来问也不能把令删掉。 */
  @Test
  void readingWithoutProgressDoesNotDropTheOrder() {
    Fixture f = new Fixture();
    f.at(0);
    f.skips.order(TRAIN);
    f.progress.remove(TRAIN);

    assertFalse(f.skips.skipped(TRAIN, ROUTE_UUID, 2));

    f.at(0);
    assertTrue(f.skips.skipped(TRAIN, ROUTE_UUID, 2));
    assertEquals(Optional.of(CCC), f.skips.orderedStation(TRAIN));
  }

  @Test
  void anOrderFollowsARenameAndIsForgottenWithTheTrain() {
    Fixture f = new Fixture();
    f.at(0);
    f.skips.order(TRAIN);
    String renamed = "SURC-WS-A-0002";
    f.progress.advance(
        renamed,
        ROUTE_UUID,
        ROUTE,
        0,
        new RuntimeDispatchTestFixtures.TagStore(renamed).properties(),
        Instant.now());

    f.skips.rename(TRAIN, renamed);
    assertTrue(f.skips.skipped(renamed, ROUTE_UUID, 2));
    assertFalse(f.skips.skipped(TRAIN, ROUTE_UUID, 2));

    f.skips.forget(renamed);
    assertFalse(f.skips.skipped(renamed, ROUTE_UUID, 2));
  }

  @Test
  void aCalledTrainsDestinationOrACrewChangeStationCannotBeSkipped() {
    Fixture f = new Fixture();
    f.at(0);
    f.guarded.put(TRAIN, StopSkips.Refusal.CALL_DESTINATION);

    assertEquals(
        Optional.of(StopSkips.Refusal.CALL_DESTINATION),
        f.skips.candidate(TRAIN).orElseThrow().refusal());
    assertEquals(StopSkips.Outcome.REFUSED, f.skips.order(TRAIN).outcome());
  }

  @Test
  void theBrakingPointIsTheComfortableBrakingDistanceWithHalfAgainAsMargin() {
    // 20 格/秒按 1 格/秒² 刹到停：200 格，加一半余量为 300 格
    assertFalse(StopSkips.pastBrakingPoint(20.0, 310.0));
    assertTrue(StopSkips.pastBrakingPoint(20.0, 290.0));
    // 慢车与停着的车至少留 16 格
    assertTrue(StopSkips.pastBrakingPoint(0.0, 15.0));
    assertFalse(StopSkips.pastBrakingPoint(0.0, 17.0));
  }

  @Test
  void aGuardAttachedLaterIsHonoured() {
    Fixture f = new Fixture();
    f.at(0);
    StopSkips plain = new StopSkips(f.progress, f.routes, null, null);
    assertTrue(plain.candidate(TRAIN).orElseThrow().refusal().isEmpty());

    plain.setGuard((train, station) -> Optional.of(StopSkips.Refusal.CALL_DESTINATION));

    assertEquals(
        Optional.of(StopSkips.Refusal.CALL_DESTINATION),
        plain.candidate(TRAIN).orElseThrow().refusal());
  }
}
