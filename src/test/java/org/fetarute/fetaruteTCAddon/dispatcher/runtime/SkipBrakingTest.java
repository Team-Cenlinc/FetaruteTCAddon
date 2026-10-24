package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.junit.jupiter.api.Test;

/** 越站的刹车锁定：车已在为那一站刹车（调度正以它为停车点、且已过刹车起点）时锁定；令越站后停车点已后移，就按车到那一站的路程算。 */
class SkipBrakingTest {

  private static final String TRAIN = "SURC-WS-A-0001";
  private static final UUID ROUTE_UUID = UUID.randomUUID();
  private static final NodeId AAA = NodeId.of("SURC:S:AAA:1");
  private static final NodeId CCC = NodeId.of("SURC:S:CCC:1");
  private static final NodeId DDD = NodeId.of("SURC:S:DDD:1");
  private static final NodeId MID = NodeId.of("SURC:AAA:CCC:1:1");
  private static final RouteDefinition ROUTE =
      new RouteDefinition(RouteId.of("SURC:WS:WS-N"), List.of(AAA, CCC, DDD), Optional.empty());

  private final Map<String, SkipBraking.Motion> motions = new HashMap<>();
  private final Map<String, Long> distances = new HashMap<>();

  /** 取不到运动时车在不在动；没写的车状态不明。 */
  private final Map<String, Boolean> moving = new HashMap<>();

  private final SkipBraking braking =
      new SkipBraking(
          train -> Optional.ofNullable(motions.get(train)),
          uuid -> uuid.equals(ROUTE_UUID) ? Optional.of(ROUTE) : Optional.empty(),
          (from, to) -> {
            Long distance = distances.get(from.value() + ">" + to.value());
            return distance == null ? OptionalLong.empty() : OptionalLong.of(distance);
          },
          train -> moving.getOrDefault(train, true));

  @Test
  void stoppingAtTheStationInsideTheBrakingPointIsLocked() {
    motions.put(TRAIN, new SkipBraking.Motion(20.0, MID, Optional.of(CCC), OptionalDouble.of(250)));
    assertTrue(braking.started(TRAIN, ROUTE_UUID, 1));

    motions.put(TRAIN, new SkipBraking.Motion(20.0, MID, Optional.of(CCC), OptionalDouble.of(400)));
    assertFalse(braking.started(TRAIN, ROUTE_UUID, 1));
  }

  @Test
  void afterTheOrderTheDistanceToTheSkippedStationComesFromTheGraph() {
    motions.put(TRAIN, new SkipBraking.Motion(20.0, MID, Optional.of(DDD), OptionalDouble.of(900)));

    distances.put(MID.value() + ">" + CCC.value(), 250L);
    assertTrue(braking.started(TRAIN, ROUTE_UUID, 1));

    distances.put(MID.value() + ">" + CCC.value(), 400L);
    assertFalse(braking.started(TRAIN, ROUTE_UUID, 1));
  }

  /** 最后经过的节点离被越的站还有一整条边：按到停车点的距离减去站到停车点的路程，不把整条边算进去。 */
  @Test
  void theDistanceAlreadyRunPastTheLastNodeIsNotCountedAgain() {
    motions.put(TRAIN, new SkipBraking.Motion(16.0, MID, Optional.of(DDD), OptionalDouble.of(600)));
    distances.put(MID.value() + ">" + CCC.value(), 350L);
    distances.put(CCC.value() + ">" + DDD.value(), 500L);
    distances.put(MID.value() + ">" + DDD.value(), 850L);

    assertTrue(braking.started(TRAIN, ROUTE_UUID, 1), "实际只剩 100 格");
  }

  /** 取不到运动：车在动或状态不明时按已锁定算（宁可不让越、不让撤），停着的车不锁。 */
  @Test
  void withoutMotionAMovingOrUnknownTrainIsLocked() {
    assertTrue(braking.started(TRAIN, ROUTE_UUID, 1));

    moving.put(TRAIN, false);
    assertFalse(braking.started(TRAIN, ROUTE_UUID, 1));
  }
}
