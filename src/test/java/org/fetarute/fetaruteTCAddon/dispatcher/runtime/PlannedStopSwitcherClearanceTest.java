package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequestContext;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.junit.jupiter.api.Test;

/** 前瞻测距只去掉“计划停车点之后足够远的道岔冲突键”，其它一律原样。 */
class PlannedStopSwitcherClearanceTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final NodeId CURRENT = NodeId.of("SURC:SPB:WSD:3:007");
  private static final NodeId STATION = NodeId.of("SURC:S:WSD:3");
  private static final NodeId SWITCH = NodeId.of("SWITCHER:Test:200:64:0");
  private static final NodeId AFTER = NodeId.of("SURC:WSD:SCC:3:001");
  private static final OccupancyResource SWITCH_CONFLICT =
      OccupancyResource.forConflict("switcher:" + SWITCH.value());
  private static final OccupancyResource SWITCH_NODE = OccupancyResource.forNode(SWITCH);
  private static final Optional<RouteStop> STOP =
      Optional.of(RuntimeDispatchTestFixtures.routeStop(1, STATION, RouteStopPassType.STOP));
  private static final Optional<RouteStop> PASS =
      Optional.of(RuntimeDispatchTestFixtures.routeStop(1, STATION, RouteStopPassType.PASS));

  /** 道岔在停车点之后 64 格、净空 42：去掉冲突键，道岔节点这一物理占用留着；其余字段不变。 */
  @Test
  void dropsOnlyTheSwitcherConflictOfADistantSwitchBeyondTheStop() {
    OccupancyDecision decision = decision(SWITCH_CONFLICT, SWITCH_NODE);

    OccupancyDecision filtered =
        PlannedStopSwitcherClearance.forLookahead(decision, context(64), STOP, STATION, 42L);

    assertEquals(List.of(SWITCH_NODE), resources(filtered));
    assertEquals(decision.allowed(), filtered.allowed());
    assertEquals(decision.signal(), filtered.signal());
    assertEquals(decision.reason(), filtered.reason());
  }

  /** 道岔离停车点不足净空：停站时车头可能伸到道岔，不去掉。 */
  @Test
  void keepsTheConflictWhenTheSwitchIsWithinTheClearance() {
    OccupancyDecision decision = decision(SWITCH_CONFLICT);

    assertSame(
        decision,
        PlannedStopSwitcherClearance.forLookahead(decision, context(41), STOP, STATION, 42L));
  }

  /** 下一路径点只是通过（列车不在那里停）、车长未知（净空为 MAX）、或停车点不在前瞻路径上：原样返回。 */
  @Test
  void keepsTheConflictWithoutAPlannedStopOrAKnownLength() {
    OccupancyDecision decision = decision(SWITCH_CONFLICT);

    assertSame(
        decision,
        PlannedStopSwitcherClearance.forLookahead(decision, context(64), PASS, STATION, 42L));
    assertSame(
        decision,
        PlannedStopSwitcherClearance.forLookahead(
            decision, context(64), STOP, STATION, Long.MAX_VALUE));
    assertSame(
        decision,
        PlannedStopSwitcherClearance.forLookahead(
            decision, context(64), STOP, NodeId.of("SURC:S:OTHER:1"), 42L));
  }

  /** 道岔在停车点之前：那是进站前真要经过的道岔，不去掉。 */
  @Test
  void keepsTheConflictOfASwitchBeforeTheStop() {
    OccupancyDecision decision = decision(SWITCH_CONFLICT);

    assertSame(
        decision,
        PlannedStopSwitcherClearance.forLookahead(decision, context(64), STOP, AFTER, 42L));
  }

  private static OccupancyDecision decision(OccupancyResource... blockers) {
    List<OccupancyClaim> claims =
        java.util.Arrays.stream(blockers)
            .map(
                resource ->
                    new OccupancyClaim(
                        resource,
                        "SURC-DS-LH-3597",
                        Optional.empty(),
                        NOW,
                        Duration.ZERO,
                        Optional.empty()))
            .toList();
    return new OccupancyDecision(
        false, NOW, SignalAspect.PROCEED_WITH_CAUTION, claims, false, "advisory");
  }

  private static List<OccupancyResource> resources(OccupancyDecision decision) {
    return decision.blockers().stream().map(OccupancyClaim::resource).toList();
  }

  /** 当前节点 —30— 车站 —{@code switchBeyondStation}— 道岔 —30— 站后节点。 */
  private static OccupancyRequestContext context(int switchBeyondStation) {
    List<NodeId> nodes = List.of(CURRENT, STATION, SWITCH, AFTER);
    List<RailEdge> edges =
        List.of(
            edge(CURRENT, STATION, 30),
            edge(STATION, SWITCH, switchBeyondStation),
            edge(SWITCH, AFTER, 30));
    OccupancyRequest request =
        new OccupancyRequest(
            "SURC-MT-LH-2689", Optional.empty(), NOW, List.of(), Map.of(), Map.of(), 0);
    return new OccupancyRequestContext(request, nodes, edges);
  }

  private static RailEdge edge(NodeId from, NodeId to, int length) {
    EdgeId id = EdgeId.undirected(from, to);
    return new RailEdge(id, from, to, length, -1.0, true, Optional.empty());
  }
}
