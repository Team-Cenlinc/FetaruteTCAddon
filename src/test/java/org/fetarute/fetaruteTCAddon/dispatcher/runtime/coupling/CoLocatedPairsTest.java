package org.fetarute.fetaruteTCAddon.dispatcher.runtime.coupling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.AuthorizationPurpose;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ClaimRole;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.DirectedTraversalContext;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.FieldOccupancySnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceIntent;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.junit.jupiter.api.Test;

/** 同股道列车对：一段东西向的站台轨道 W—E，A 在东半、B 在西半，两车共占这段轨道。A 往东走（背向 B）不被 B 挡；A 往西走（朝 B）照常被挡。 */
class CoLocatedPairsTest {

  private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");
  private static final UUID WORLD = UUID.randomUUID();
  private static final NodeId WEST = NodeId.of("OP:S:STA:1:W");
  private static final NodeId EAST = NodeId.of("OP:S:STA:1:E");
  private static final NodeId BEYOND_EAST = NodeId.of("OP:S:STA:1:X");
  private static final OccupancyResource PLATFORM =
      OccupancyResource.forEdge(new EdgeId(WEST, EAST));
  private static final OccupancyResource EAST_NODE = OccupancyResource.forNode(EAST);

  private final CoLocatedPairs pairs =
      new CoLocatedPairs(
          (world, node) ->
              Optional.ofNullable(
                  Map.of(
                          WEST, new Vector(0, 64, 0),
                          EAST, new Vector(100, 64, 0),
                          BEYOND_EAST, new Vector(200, 64, 0))
                      .get(node)));

  private void pairUp(Set<OccupancyResource> shared) {
    pairs.register(
        new CoLocatedPairs.Pair(
            "A", "B", WORLD, shared, new Vector(1, 0, 0), new Vector(-1, 0, 0)));
  }

  private static OccupancyRequest move(String train, NodeId from, NodeId to) {
    List<OccupancyResource> resources = List.of(PLATFORM, OccupancyResource.forNode(to));
    DirectedTraversalContext context =
        new DirectedTraversalContext(
            train,
            Optional.empty(),
            0,
            Optional.of(from),
            Optional.empty(),
            Optional.of(from),
            Optional.of(to),
            List.of(from, to),
            List.of(new DirectedTraversalContext.DirectedEdge(new EdgeId(from, to), from, to)),
            Map.of(),
            Map.of(),
            "TEST",
            1L,
            1L,
            "co-located-" + train,
            Optional.empty());
    return new OccupancyRequest(
        train,
        Optional.empty(),
        NOW,
        resources,
        Map.of(),
        Map.of(),
        0,
        AuthorizationPurpose.RUNTIME_MOVE,
        Map.of(),
        Map.of(
            PLATFORM,
            ResourceIntent.MOVEMENT_REQUIRED,
            OccupancyResource.forNode(to),
            ResourceIntent.MOVEMENT_REQUIRED),
        Optional.of(context));
  }

  private static OccupancyClaim footprint(String train, OccupancyResource resource) {
    return new OccupancyClaim(
        resource,
        train,
        Optional.empty(),
        NOW,
        Duration.ZERO,
        Optional.empty(),
        ClaimRole.PHYSICAL_FOOTPRINT);
  }

  @Test
  void movingAwayFromThePartnerIsExemptOnSharedResourcesOnly() {
    pairUp(Set.of(PLATFORM));

    assertTrue(pairs.exempts(move("A", WEST, EAST), PLATFORM, footprint("B", PLATFORM)));
    assertFalse(
        pairs.exempts(move("A", EAST, WEST), PLATFORM, footprint("B", PLATFORM)), "朝对方走照常挡");
    assertFalse(
        pairs.exempts(move("A", WEST, EAST), EAST_NODE, footprint("B", EAST_NODE)), "不是共用资源");
    assertFalse(
        pairs.exempts(move("A", WEST, EAST), PLATFORM, footprint("C", PLATFORM)), "不是对方的占用");
    assertTrue(
        pairs.exempts(move("B", EAST, WEST), PLATFORM, footprint("A", PLATFORM)), "B 往西也是背向对方");
  }

  @Test
  void missingDirectionOrGraphKeepsBlocking() {
    pairUp(Set.of(PLATFORM));
    OccupancyRequest noContext =
        new OccupancyRequest(
            "A",
            Optional.empty(),
            NOW,
            List.of(PLATFORM),
            Map.of(),
            Map.of(),
            0,
            AuthorizationPurpose.RUNTIME_MOVE,
            Map.of(),
            Map.of(PLATFORM, ResourceIntent.MOVEMENT_REQUIRED));

    assertFalse(pairs.exempts(noContext, PLATFORM, footprint("B", PLATFORM)));
    assertFalse(
        pairs.exempts(
            move("A", WEST, NodeId.of("OP:S:NOWHERE:1")), PLATFORM, footprint("B", PLATFORM)),
        "节点位置查不到");
  }

  @Test
  void dissolvingOneMemberReleasesBoth() {
    pairUp(Set.of(PLATFORM));
    assertEquals(1, pairs.pairs().size());

    pairs.dissolve("b");

    assertTrue(pairs.pairOf("A").isEmpty());
    assertFalse(pairs.exempts(move("A", WEST, EAST), PLATFORM, footprint("B", PLATFORM)));
    pairUp(Set.of());
    assertTrue(pairs.pairs().isEmpty(), "没有共用资源不登记");
  }

  @Test
  void theOccupancyManagerLetsTheOuterTrainLeaveButNotTheInnerOne() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    OccupancyRequest standing =
        new OccupancyRequest(
            "A",
            Optional.empty(),
            NOW,
            List.of(PLATFORM),
            Map.of(),
            Map.of(),
            0,
            AuthorizationPurpose.RUNTIME_MOVE,
            Map.of(),
            Map.of(PLATFORM, ResourceIntent.HOLD_ONLY));
    assertTrue(
        manager
            .reconstructPhysicalSnapshot(
                List.of(
                    FieldOccupancySnapshot.allPhysical(standing),
                    FieldOccupancySnapshot.allPhysical(standing.withTrainName("B"))))
            .committed());

    OccupancyDecision before = manager.canEnter(move("A", WEST, BEYOND_EAST));
    assertFalse(before.allowed(), "没有对时两车互相挡");

    pairUp(Set.of(PLATFORM));
    manager.setCoLocationPolicy(pairs);

    assertTrue(manager.canEnter(move("A", WEST, BEYOND_EAST)).allowed(), "A 背向 B 开走");
    assertFalse(manager.canEnter(move("A", EAST, WEST)).allowed(), "A 朝 B 开照常挡");
  }

  @Test
  void awayDirectionPointsFromThePartnerToSelf() {
    Vector away =
        CoLocatedPairs.awayDirection(new Vector(10, 64, 0), new Vector(0, 70, 0)).orElseThrow();
    assertEquals(1.0, away.getX(), 1e-9);
    assertEquals(0.0, away.getY(), 1e-9);
    assertTrue(CoLocatedPairs.awayDirection(new Vector(0, 64, 0), new Vector(0, 70, 0)).isEmpty());
    assertTrue(CoLocatedPairs.movingAway(new Vector(5, 0, 1), away));
    assertFalse(CoLocatedPairs.movingAway(new Vector(-5, 0, 0), away));
    assertFalse(CoLocatedPairs.movingAway(new Vector(1, 0, 10), away), "几乎横着走不算背向");
  }
}
