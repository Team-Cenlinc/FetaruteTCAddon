package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.Test;

/** 位置保持对当前边联锁区的取舍：只有“停稳且足迹完整”才跟随现场，其余资源恒保持。 */
class PositionZoneEvidenceTest {

  private static final OccupancyResource CROSSING =
      OccupancyResource.forConflict("interlocking:test:crossing");
  private static final OccupancyResource OTHER_CROSSING =
      OccupancyResource.forConflict("interlocking:test:other");
  private static final List<OccupancyResource> NON_ZONE_RESOURCES =
      List.of(
          OccupancyResource.forNode(NodeId.of("B")),
          OccupancyResource.forEdge(EdgeId.undirected(NodeId.of("B"), NodeId.of("J"))),
          OccupancyResource.forConflict("switcher:SWITCHER:Towny:-516:77:2268"),
          OccupancyResource.forConflict("single:test:B~J"));

  @Test
  void edgeWideEvidenceRetainsEverything() {
    assertTrue(PositionZoneEvidence.EDGE_WIDE.retains(CROSSING));
    NON_ZONE_RESOURCES.forEach(
        resource ->
            assertTrue(PositionZoneEvidence.EDGE_WIDE.retains(resource), resource::toString));
  }

  @Test
  void stationaryFootprintRetainsOnlyTheZonesTheBodyCovers() {
    PositionZoneEvidence covering = PositionZoneEvidence.stationaryFootprint(Set.of(CROSSING));
    PositionZoneEvidence clear = PositionZoneEvidence.stationaryFootprint(Set.of());

    assertTrue(covering.retains(CROSSING));
    assertFalse(covering.retains(OTHER_CROSSING));
    assertFalse(clear.retains(CROSSING));
    NON_ZONE_RESOURCES.forEach(
        resource -> assertTrue(clear.retains(resource), "非联锁区资源恒保持: " + resource));
  }
}
