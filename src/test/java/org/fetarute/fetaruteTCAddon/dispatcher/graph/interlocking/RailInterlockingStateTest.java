package org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.Test;

class RailInterlockingStateTest {

  @Test
  void incompleteCoverageReturnsOneStableWorldSentinelForEveryExpectedEdge() {
    UUID worldId = UUID.fromString("11111111-2222-3333-4444-555555555555");
    EdgeId mapped = edge("A1", "A2");
    EdgeId missing = edge("B1", "B2");
    RailInterlockingState state =
        RailInterlockingState.from(
            worldId,
            Set.of(mapped, missing),
            Map.of(
                mapped,
                new RailEdgeFootprint(
                    RailEdgeFootprint.CURRENT_FORMAT_VERSION,
                    true,
                    Set.of(new RailFootprintCell(1, 2, 3)))));

    Set<String> sentinel = Set.of("interlocking:incomplete:" + worldId);
    assertEquals(sentinel, state.zoneKeysForEdge(mapped));
    assertEquals(sentinel, state.zoneKeysForEdge(missing));
    assertEquals(Set.of(), state.zoneKeysForEdge(edge("OUTSIDE", "UNIVERSE")));
  }

  @Test
  void rebuildPreservesWorldAndRecomputesCoverageWhileUnavailableStaysUnavailable() {
    UUID worldId = UUID.fromString("00000000-0000-0000-0000-000000000034");
    EdgeId first = edge("A", "B");
    EdgeId second = edge("C", "D");
    RailFootprintCell crossingCell = new RailFootprintCell(1, 64, 1);
    RailEdgeFootprint footprint =
        new RailEdgeFootprint(RailEdgeFootprint.CURRENT_FORMAT_VERSION, true, Set.of(crossingCell));
    RailInterlockingState initial =
        RailInterlockingState.from(worldId, Set.of(first), Map.of(first, footprint));

    RailInterlockingState rebuilt =
        initial.rebuild(Set.of(first, second), Map.of(first, footprint, second, footprint));

    assertEquals(Optional.of(worldId), rebuilt.worldId());
    assertEquals(true, rebuilt.available());
    assertEquals(true, rebuilt.coverage().complete());
    assertEquals(1, rebuilt.exactZoneCount());
    assertEquals(1, rebuilt.zoneKeysForEdge(first).size());
    assertEquals(rebuilt.zoneKeysForEdge(first), rebuilt.zoneKeysForEdge(second));

    RailInterlockingState unavailable =
        RailInterlockingState.unavailable().rebuild(Set.of(first), Map.of(first, footprint));
    assertEquals(Optional.empty(), unavailable.worldId());
    assertEquals(false, unavailable.available());
    assertEquals(0, unavailable.exactZoneCount());
    assertEquals(Set.of(), unavailable.zoneKeysForEdge(first));
  }

  @Test
  void resourceProjectionIncludesAvailabilityWorldEdgeUniverseAndPerEdgeZoneKeys() {
    UUID worldId = UUID.fromString("00000000-0000-0000-0000-000000000035");
    EdgeId first = edge("A", "B");
    EdgeId second = edge("C", "D");
    RailEdgeFootprint crossing =
        new RailEdgeFootprint(
            RailEdgeFootprint.CURRENT_FORMAT_VERSION,
            true,
            Set.of(new RailFootprintCell(1, 64, 1)));
    RailEdgeFootprint separate =
        new RailEdgeFootprint(
            RailEdgeFootprint.CURRENT_FORMAT_VERSION,
            true,
            Set.of(new RailFootprintCell(9, 64, 9)));
    RailInterlockingState original =
        RailInterlockingState.from(
            worldId, Set.of(first, second), Map.of(first, crossing, second, crossing));
    RailInterlockingState equivalent =
        RailInterlockingState.from(
            worldId, Set.of(first, second), Map.of(first, crossing, second, crossing));
    RailInterlockingState changedProjection =
        RailInterlockingState.from(
            worldId, Set.of(first, second), Map.of(first, crossing, second, separate));
    RailInterlockingState changedUniverse =
        RailInterlockingState.from(worldId, Set.of(first), Map.of(first, crossing));

    assertEquals(true, original.sameResourceProjection(equivalent));
    assertEquals(false, original.sameResourceProjection(changedProjection));
    assertEquals(false, original.sameResourceProjection(changedUniverse));
    assertEquals(false, original.sameResourceProjection(RailInterlockingState.unavailable()));
    assertEquals(
        true,
        RailInterlockingState.unavailable()
            .sameResourceProjection(RailInterlockingState.unavailable()));
  }

  @Test
  void completeSparseCatalogIndexesOnlyCrossingCellsAndTreatsOrdinaryTrackAsClear() {
    UUID worldId = UUID.fromString("00000000-0000-0000-0000-000000000038");
    EdgeId first = edge("MT-W", "MT-E");
    EdgeId second = edge("DS-N", "DS-S");
    EdgeId ordinary = edge("LOCAL-A", "LOCAL-B");
    RailFootprintCell firstOnly = new RailFootprintCell(9, 64, 10);
    RailFootprintCell crossing = new RailFootprintCell(10, 64, 10);
    RailFootprintCell secondOnly = new RailFootprintCell(10, 64, 11);
    RailFootprintCell ordinaryOnly = new RailFootprintCell(40, 64, 40);
    RailInterlockingState state =
        RailInterlockingState.from(
            worldId,
            Set.of(first, second, ordinary),
            Map.of(
                first, footprint(firstOnly, crossing),
                second, footprint(crossing, secondOnly),
                ordinary, footprint(ordinaryOnly)));

    assertEquals(1, state.indexedZoneCellCount());

    RailInterlockingState.LiveZoneObservation ordinaryObservation =
        state.observeLiveCells(Set.of(firstOnly, ordinaryOnly));
    assertTrue(ordinaryObservation.complete());
    assertEquals(Set.of(), ordinaryObservation.occupiedZoneKeys());

    RailInterlockingState.LiveZoneObservation crossingObservation =
        state.observeLiveCells(Set.of(firstOnly, crossing));
    assertTrue(crossingObservation.complete());
    assertEquals(state.zoneKeysForEdge(first), crossingObservation.occupiedZoneKeys());
    assertEquals(state.zoneKeysForEdge(first), state.zoneKeysForEdge(second));
    assertEquals(Set.of(), state.zoneKeysForEdge(ordinary));
  }

  @Test
  void liveCellsResolveThroughSparseZoneIndexAndIgnoreOrdinaryTrackCells() {
    UUID worldId = UUID.fromString("00000000-0000-0000-0000-000000000036");
    EdgeId first = edge("A", "B");
    EdgeId second = edge("C", "D");
    RailFootprintCell crossing = new RailFootprintCell(10, 64, 10);
    RailFootprintCell firstOnly = new RailFootprintCell(9, 64, 10);
    RailInterlockingState state =
        RailInterlockingState.from(
            worldId,
            Set.of(first, second),
            Map.of(
                first,
                    new RailEdgeFootprint(
                        RailEdgeFootprint.CURRENT_FORMAT_VERSION,
                        true,
                        Set.of(firstOnly, crossing)),
                second,
                    new RailEdgeFootprint(
                        RailEdgeFootprint.CURRENT_FORMAT_VERSION, true, Set.of(crossing))));

    RailInterlockingState.LiveZoneObservation resolution =
        state.observeLiveCells(Set.of(firstOnly, crossing));

    assertTrue(resolution.complete());
    assertEquals(state.zoneKeysForEdge(first), resolution.occupiedZoneKeys());
    assertEquals(1, state.indexedZoneCellCount());
    assertEquals(0, state.multiZoneCellCount());
  }

  @Test
  void liveCellObservationFailsClosedWhenCatalogCoverageIsIncomplete() {
    EdgeId edge = edge("A", "B");
    RailFootprintCell known = new RailFootprintCell(1, 64, 1);
    RailFootprintCell unknown = new RailFootprintCell(2, 64, 2);
    RailInterlockingState state =
        RailInterlockingState.from(
            UUID.fromString("00000000-0000-0000-0000-000000000037"),
            Set.of(edge),
            Map.of(
                edge,
                new RailEdgeFootprint(
                    RailEdgeFootprint.CURRENT_FORMAT_VERSION, false, Set.of(known))));

    RailInterlockingState.LiveZoneObservation resolution =
        state.observeLiveCells(Set.of(known, unknown));

    assertFalse(resolution.complete());
    assertEquals(Set.of(), resolution.occupiedZoneKeys());
  }

  private static EdgeId edge(String first, String second) {
    return EdgeId.undirected(NodeId.of(first), NodeId.of(second));
  }

  private static RailEdgeFootprint footprint(RailFootprintCell... cells) {
    return new RailEdgeFootprint(RailEdgeFootprint.CURRENT_FORMAT_VERSION, true, Set.of(cells));
  }
}
