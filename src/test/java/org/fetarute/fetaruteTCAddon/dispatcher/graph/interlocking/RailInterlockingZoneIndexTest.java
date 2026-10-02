package org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.Test;

class RailInterlockingZoneIndexTest {

  @Test
  void crossingNonAdjacentEdgesCreateOnePairZone() {
    EdgeId first = edge("A1", "A2");
    EdgeId second = edge("B1", "B2");
    RailFootprintCell crossing = new RailFootprintCell(4, 8, 12);

    RailInterlockingZoneIndex index =
        RailInterlockingZoneIndex.from(
            UUID.fromString("11111111-2222-3333-4444-555555555555"),
            Map.of(
                first, new RailEdgeFootprint(1, true, Set.of(crossing)),
                second, new RailEdgeFootprint(1, true, Set.of(crossing))));

    String zoneKey = index.zoneKeysForEdge(first).iterator().next();
    assertEquals(Set.of(zoneKey), index.zoneKeysForEdge(second));
    assertEquals(
        new InterlockingZoneInfo(zoneKey, first, second, Set.of(crossing)),
        index.zoneInfo(zoneKey).orElseThrow());
  }

  @Test
  void disjointAndVerticallySeparatedEdgesDoNotCreateZones() {
    EdgeId disjoint = edge("A1", "A2");
    EdgeId lower = edge("B1", "B2");
    EdgeId upper = edge("C1", "C2");

    RailInterlockingZoneIndex index =
        RailInterlockingZoneIndex.from(
            UUID.fromString("11111111-2222-3333-4444-555555555555"),
            Map.of(
                disjoint, footprint(new RailFootprintCell(0, 4, 0)),
                lower, footprint(new RailFootprintCell(10, 4, 10)),
                upper, footprint(new RailFootprintCell(10, 5, 10))));

    assertTrue(index.zones().isEmpty());
    assertTrue(index.zoneKeysForEdge(disjoint).isEmpty());
  }

  @Test
  void edgesSharingEndpointAreNormalTopologyNotCrossInterlocking() {
    EdgeId incoming = edge("SHARED", "WEST");
    EdgeId outgoing = edge("EAST", "SHARED");
    RailFootprintCell commonCell = new RailFootprintCell(3, 7, 11);

    RailInterlockingZoneIndex index =
        RailInterlockingZoneIndex.from(
            UUID.fromString("11111111-2222-3333-4444-555555555555"),
            Map.of(incoming, footprint(commonCell), outgoing, footprint(commonCell)));

    assertTrue(index.zones().isEmpty());
  }

  @Test
  void multiCellEndpointJoinRemainsOneNormalTopologyCluster() {
    EdgeId incoming = edge("SHARED", "WEST");
    EdgeId outgoing = edge("EAST", "SHARED");
    RailFootprintCell first = new RailFootprintCell(3, 7, 11);
    RailFootprintCell second = new RailFootprintCell(4, 7, 11);
    RailFootprintCell slopedThird = new RailFootprintCell(4, 8, 11);

    RailInterlockingZoneIndex index =
        RailInterlockingZoneIndex.from(
            UUID.fromString("11111111-2222-3333-4444-555555555555"),
            Map.of(
                incoming, footprint(first, second, slopedThird),
                outgoing, footprint(slopedThird, second, first)));

    assertTrue(index.zones().isEmpty());
  }

  @Test
  void edgesSharingEndpointButCrossingAgainCreateInterlockingZone() {
    EdgeId first = edge("SHARED", "WEST");
    EdgeId second = edge("EAST", "SHARED");
    RailFootprintCell endpointJoin = new RailFootprintCell(3, 7, 11);
    RailFootprintCell remoteCrossing = new RailFootprintCell(30, 7, 40);

    RailInterlockingZoneIndex index =
        RailInterlockingZoneIndex.from(
            UUID.fromString("11111111-2222-3333-4444-555555555555"),
            Map.of(
                first, footprint(endpointJoin, remoteCrossing),
                second, footprint(endpointJoin, remoteCrossing)));

    assertEquals(1, index.zones().size());
    String zoneKey = index.zoneKeysForEdge(first).iterator().next();
    assertEquals(Set.of(zoneKey), index.zoneKeysForEdge(second));
    assertEquals(
        Set.of(endpointJoin, remoteCrossing), index.zoneInfo(zoneKey).orElseThrow().overlapCells());
  }

  @Test
  void multiCellEndpointAndRemoteCurveClustersStillCreateOnePairZone() {
    EdgeId first = edge("SHARED", "WEST");
    EdgeId second = edge("EAST", "SHARED");
    RailFootprintCell endpointA = new RailFootprintCell(3, 7, 11);
    RailFootprintCell endpointB = new RailFootprintCell(4, 7, 11);
    RailFootprintCell remoteA = new RailFootprintCell(30, 7, 40);
    RailFootprintCell remoteSlope = new RailFootprintCell(30, 8, 40);

    RailInterlockingZoneIndex index =
        RailInterlockingZoneIndex.from(
            UUID.fromString("11111111-2222-3333-4444-555555555555"),
            Map.of(
                first, footprint(endpointA, endpointB, remoteA, remoteSlope),
                second, footprint(remoteSlope, remoteA, endpointB, endpointA)));

    assertEquals(1, index.zones().size());
    String zoneKey = index.zoneKeysForEdge(first).iterator().next();
    assertEquals(Set.of(zoneKey), index.zoneKeysForEdge(second));
    assertEquals(
        Set.of(endpointA, endpointB, remoteA, remoteSlope),
        index.zoneInfo(zoneKey).orElseThrow().overlapCells());
  }

  @Test
  void threeEdgesCreateOnlyDirectlyOverlappingPairs() {
    EdgeId first = edge("A1", "A2");
    EdgeId bridge = edge("B1", "B2");
    EdgeId third = edge("C1", "C2");
    RailFootprintCell firstCrossing = new RailFootprintCell(1, 2, 3);
    RailFootprintCell secondCrossing = new RailFootprintCell(7, 8, 9);

    RailInterlockingZoneIndex index =
        RailInterlockingZoneIndex.from(
            UUID.fromString("11111111-2222-3333-4444-555555555555"),
            Map.of(
                first, footprint(firstCrossing),
                bridge, footprint(firstCrossing, secondCrossing),
                third, footprint(secondCrossing)));

    assertEquals(2, index.zones().size());
    assertEquals(1, index.zoneKeysForEdge(first).size());
    assertEquals(2, index.zoneKeysForEdge(bridge).size());
    assertEquals(1, index.zoneKeysForEdge(third).size());
    assertTrue(
        index.zoneKeysForEdge(first).stream().noneMatch(index.zoneKeysForEdge(third)::contains),
        "A-B 与 B-C 的重叠不得推导出 A-C 联锁区");
  }

  @Test
  void threeNonAdjacentEdgesAtOneCellCreateAllThreePairZones() {
    EdgeId first = edge("A1", "A2");
    EdgeId second = edge("B1", "B2");
    EdgeId third = edge("C1", "C2");
    RailFootprintCell crossing = new RailFootprintCell(7, 8, 9);

    RailInterlockingZoneIndex index =
        RailInterlockingZoneIndex.from(
            UUID.fromString("11111111-2222-3333-4444-555555555555"),
            Map.of(
                first, footprint(crossing),
                second, footprint(crossing),
                third, footprint(crossing)));

    assertEquals(3, index.zones().size());
    assertEquals(2, index.zoneKeysForEdge(first).size());
    assertEquals(2, index.zoneKeysForEdge(second).size());
    assertEquals(2, index.zoneKeysForEdge(third).size());
  }

  @Test
  void multipleOverlapCellsMergeIntoOnePairZone() {
    EdgeId first = edge("A1", "A2");
    EdgeId second = edge("B1", "B2");
    RailFootprintCell firstCell = new RailFootprintCell(9, 3, 4);
    RailFootprintCell secondCell = new RailFootprintCell(8, 3, 4);

    RailInterlockingZoneIndex index =
        RailInterlockingZoneIndex.from(
            UUID.fromString("11111111-2222-3333-4444-555555555555"),
            Map.of(
                first, footprint(firstCell, secondCell),
                second, footprint(secondCell, firstCell)));

    InterlockingZoneInfo zone = index.zones().values().iterator().next();
    assertEquals(1, index.zones().size());
    assertEquals(Set.of(firstCell, secondCell), zone.overlapCells());
    assertEquals(secondCell, zone.overlapCells().iterator().next(), "重叠坐标应稳定排序");
  }

  @Test
  void zoneKeyIsStableAcrossMapOrderAndEdgeOrientation() {
    UUID worldId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    EdgeId first = edge("A1", "A2");
    EdgeId second = edge("B1", "B2");
    RailFootprintCell crossing = new RailFootprintCell(4, 5, 6);
    Map<EdgeId, RailEdgeFootprint> forwardOrder = new LinkedHashMap<>();
    forwardOrder.put(first, footprint(crossing));
    forwardOrder.put(second, footprint(crossing));
    Map<EdgeId, RailEdgeFootprint> reverseOrderAndOrientation = new LinkedHashMap<>();
    reverseOrderAndOrientation.put(new EdgeId(second.b(), second.a()), footprint(crossing));
    reverseOrderAndOrientation.put(new EdgeId(first.b(), first.a()), footprint(crossing));

    RailInterlockingZoneIndex firstIndex = RailInterlockingZoneIndex.from(worldId, forwardOrder);
    RailInterlockingZoneIndex secondIndex =
        RailInterlockingZoneIndex.from(worldId, reverseOrderAndOrientation);

    String firstKey = firstIndex.zones().keySet().iterator().next();
    String secondKey = secondIndex.zones().keySet().iterator().next();
    assertEquals(firstKey, secondKey);
    assertTrue(firstKey.matches("interlocking:[0-9a-f]{64}"));
    assertEquals(
        firstIndex.zoneInfo(firstKey).orElseThrow(), secondIndex.zoneInfo(secondKey).orElseThrow());
  }

  @Test
  void incompleteOrVersionZeroFootprintsFailCoverageClosedAndDoNotParticipate() {
    RailFootprintCell crossing = new RailFootprintCell(1, 1, 1);
    EdgeId supported = edge("A1", "A2");
    EdgeId incomplete = edge("B1", "B2");
    EdgeId legacy = edge("C1", "C2");

    RailInterlockingZoneIndex index =
        RailInterlockingZoneIndex.from(
            UUID.fromString("11111111-2222-3333-4444-555555555555"),
            Map.of(
                supported, footprint(crossing),
                incomplete, new RailEdgeFootprint(1, false, Set.of(crossing)),
                legacy, new RailEdgeFootprint(0, true, Set.of(crossing))));

    assertEquals(new RailInterlockingCoverage(3, 1, false), index.coverage());
    assertFalse(index.coverage().complete());
    assertTrue(index.zones().isEmpty());
  }

  @Test
  void expectedEdgeUniverseRejectsMissingFutureAndEmptyFootprints() {
    UUID worldId = UUID.fromString("11111111-2222-3333-4444-555555555555");
    EdgeId supported = edge("A1", "A2");
    EdgeId missing = edge("B1", "B2");
    EdgeId future = edge("C1", "C2");
    EdgeId empty = edge("D1", "D2");

    RailInterlockingZoneIndex index =
        RailInterlockingZoneIndex.from(
            worldId,
            Set.of(supported, missing, future, empty),
            Map.of(
                supported, footprint(new RailFootprintCell(1, 1, 1)),
                future, new RailEdgeFootprint(2, true, Set.of(new RailFootprintCell(1, 1, 1))),
                empty, new RailEdgeFootprint(1, true, Set.of())));

    assertEquals(new RailInterlockingCoverage(4, 1, false), index.coverage());
    assertTrue(index.zones().isEmpty());
  }

  @Test
  void returnedCollectionsAreImmutableSnapshots() {
    EdgeId first = edge("A1", "A2");
    EdgeId second = edge("B1", "B2");
    RailFootprintCell crossing = new RailFootprintCell(1, 2, 3);
    RailInterlockingZoneIndex index =
        RailInterlockingZoneIndex.from(
            UUID.fromString("11111111-2222-3333-4444-555555555555"),
            Map.of(first, footprint(crossing), second, footprint(crossing)));
    String key = index.zoneKeysForEdge(first).iterator().next();

    assertThrows(UnsupportedOperationException.class, () -> index.zones().clear());
    assertThrows(UnsupportedOperationException.class, () -> index.zoneKeysForEdge(first).clear());
    assertThrows(
        UnsupportedOperationException.class, () -> index.zoneKeysForCell(crossing).clear());
    assertThrows(
        UnsupportedOperationException.class,
        () -> index.zoneInfo(key).orElseThrow().overlapCells().clear());
  }

  @Test
  void reverseIndexRetainsOnlySparseZoneCells() {
    EdgeId first = edge("A1", "A2");
    EdgeId second = edge("B1", "B2");
    RailFootprintCell firstOnly = new RailFootprintCell(1, 2, 3);
    RailFootprintCell crossing = new RailFootprintCell(4, 5, 6);
    RailFootprintCell secondOnly = new RailFootprintCell(7, 8, 9);

    RailInterlockingZoneIndex index =
        RailInterlockingZoneIndex.from(
            UUID.fromString("11111111-2222-3333-4444-555555555555"),
            Map.of(
                first, footprint(firstOnly, crossing),
                second, footprint(crossing, secondOnly)));

    String zoneKey = index.zoneKeysForEdge(first).iterator().next();
    assertEquals(1, index.indexedZoneCellCount());
    assertEquals(0, index.multiZoneCellCount());
    assertEquals(Set.of(), index.zoneKeysForCell(firstOnly));
    assertEquals(Set.of(zoneKey), index.zoneKeysForCell(crossing));
    assertEquals(Set.of(), index.zoneKeysForCell(secondOnly));
  }

  private static RailEdgeFootprint footprint(RailFootprintCell... cells) {
    return new RailEdgeFootprint(1, true, Set.of(cells));
  }

  private static EdgeId edge(String first, String second) {
    return EdgeId.undirected(NodeId.of(first), NodeId.of(second));
  }
}
