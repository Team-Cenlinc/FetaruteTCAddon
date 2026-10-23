package org.fetarute.fetaruteTCAddon.coupler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalDouble;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.junit.jupiter.api.Test;

/** 联挂进站每一拍的判定与速度。 */
class ApproachRulesTest {

  private static ApproachRules.Observation seen(
      OptionalDouble gap, boolean other, double travelled, int ticks, double moved) {
    return new ApproachRules.Observation(gap, other, travelled, 40.0, ticks, 1000, moved);
  }

  @Test
  void stopsToLinkWhenTheGapCloses() {
    assertEquals(
        ApproachRules.Step.ARRIVED,
        ApproachRules.step(seen(OptionalDouble.of(0.2), false, 20.0, 400, 0.0)));
    assertEquals(
        ApproachRules.Step.CONTINUE,
        ApproachRules.step(seen(OptionalDouble.of(3.0), false, 20.0, 400, 0.0)));
  }

  @Test
  void keepsGoingBlindUntilTheBudgetIsSpent() {
    assertEquals(
        ApproachRules.Step.CONTINUE,
        ApproachRules.step(seen(OptionalDouble.empty(), false, 39.0, 400, 0.0)),
        "道岔还没扳到、看不见对方：照常往前走");
    assertEquals(
        ApproachRules.Step.OVERRUN,
        ApproachRules.step(seen(OptionalDouble.empty(), false, 40.5, 400, 0.0)));
  }

  @Test
  void givesUpOnAnyoneElseOrAMovingPartnerOrAStall() {
    assertEquals(
        ApproachRules.Step.OTHER_TRAIN,
        ApproachRules.step(seen(OptionalDouble.of(0.1), true, 5.0, 100, 0.0)),
        "先碰到别的列车：哪怕空隙已经很小也不挂");
    assertEquals(
        ApproachRules.Step.STANDING_MOVED,
        ApproachRules.step(seen(OptionalDouble.of(2.0), false, 5.0, 100, 0.8)));
    assertEquals(
        ApproachRules.Step.STALLED,
        ApproachRules.step(seen(OptionalDouble.of(2.0), false, 0.01, 60, 0.0)));
    assertEquals(
        ApproachRules.Step.STALLED,
        ApproachRules.step(
            new ApproachRules.Observation(
                OptionalDouble.of(2.0), false, 10.0, 40.0, 1001, 1000, 0.0)));
  }

  @Test
  void slowsDownForTheLastFewBlocks() {
    assertEquals(0.1, ApproachRules.speed(OptionalDouble.empty(), 0.1, 0.05), 1e-9);
    assertEquals(0.1, ApproachRules.speed(OptionalDouble.of(10.0), 0.1, 0.05), 1e-9);
    assertEquals(0.05, ApproachRules.speed(OptionalDouble.of(5.0), 0.1, 0.05), 1e-9);
    assertEquals(720, ApproachRules.maxTicks(40.0, 0.1, 0.05), "400 拍走完上限 + 120 拍对位 + 200 拍余量");
  }

  /**
   * 推车找车走岔了：回库车刚把咽喉道岔扳向车库那一支，找车顺着走过去碰到它。它压着的区间都不在计划进路上、推着的车车头还在进路上（或还在车库尽头不在任何区间上）——
   * 不算挡路；它真压在进路上，或推着的车车头已经开进了那一支，照旧算挡路；算不出进路、查不到它压着哪一段也算挡路。
   */
  @Test
  void anObstacleOnlyOnTheOtherBranchDoesNotStopThePush() {
    EdgeId depotToSwitch = edge("D:HHU:3", "SW502");
    EdgeId switchToPlatform = edge("SW502", "S:HHU:1");
    EdgeId switchToOtherDepot = edge("SW502", "D:HHU:1:001");
    java.util.Set<EdgeId> route = java.util.Set.of(depotToSwitch, switchToPlatform);

    assertTrue(
        ApproachRules.obstacleOffRoute(
            route, java.util.Set.of(depotToSwitch), java.util.Set.of(switchToOtherDepot)),
        "挡路的车在另一支：不算");
    assertTrue(
        ApproachRules.obstacleOffRoute(
            route, java.util.Set.of(), java.util.Set.of(switchToOtherDepot)),
        "车头还在车库尽头、不在任何区间上：照样不算");
    assertFalse(
        ApproachRules.obstacleOffRoute(
            route, java.util.Set.of(depotToSwitch), java.util.Set.of(switchToPlatform)),
        "挡路的车就在进路上");
    assertFalse(
        ApproachRules.obstacleOffRoute(
            route,
            java.util.Set.of(depotToSwitch),
            java.util.Set.of(switchToOtherDepot, switchToPlatform)),
        "压在道岔口、两支都沾：算挡路");
    assertFalse(
        ApproachRules.obstacleOffRoute(
            route, java.util.Set.of(switchToOtherDepot), java.util.Set.of(switchToOtherDepot)),
        "推着的车已经开进了那一支：那辆车就在前面");
    assertFalse(
        ApproachRules.obstacleOffRoute(
            java.util.Set.of(),
            java.util.Set.of(depotToSwitch),
            java.util.Set.of(switchToOtherDepot)),
        "算不出计划进路");
    assertFalse(
        ApproachRules.obstacleOffRoute(route, java.util.Set.of(depotToSwitch), java.util.Set.of()),
        "查不到它压着哪一段");
  }

  private static EdgeId edge(String a, String b) {
    return EdgeId.undirected(
        org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId.of(a),
        org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId.of(b));
  }

  @Test
  void onlyAHeadOffTheTrackAMiddleCarOrAnUnseenCloseTrainStopsThePush() {
    org.junit.jupiter.api.Assertions.assertTrue(
        ApproachRules.finderBlocks(PushTargetFinder.Failure.NO_TRACK, 100.0));
    org.junit.jupiter.api.Assertions.assertTrue(
        ApproachRules.finderBlocks(PushTargetFinder.Failure.MIDDLE_CAR, 100.0));
    org.junit.jupiter.api.Assertions.assertFalse(
        ApproachRules.finderBlocks(PushTargetFinder.Failure.NOT_FOUND, 5.0), "距离内还没看见对方");
    org.junit.jupiter.api.Assertions.assertFalse(
        ApproachRules.finderBlocks(PushTargetFinder.Failure.UNLOADED_AHEAD, 80.0),
        "前方区块没加载、对方还远：没有玩家在附近时远处常常如此，照常往前推");
    org.junit.jupiter.api.Assertions.assertTrue(
        ApproachRules.finderBlocks(PushTargetFinder.Failure.UNLOADED_AHEAD, 20.0),
        "对方已经很近却看不见：不盲推");
    org.junit.jupiter.api.Assertions.assertTrue(
        ApproachRules.finderBlocks(PushTargetFinder.Failure.UNLOADED_AHEAD, Double.NaN),
        "量不出距离按很近处理");
  }
}
