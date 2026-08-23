package org.fetarute.fetaruteTCAddon.dispatcher.graph.persist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.InterlockingZoneInfo;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingCoverage;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.Test;

final class RailInterlockingSnapshotCodecTest {

  @Test
  void roundTripPersistsOnlySparseZoneGeometryAndCoverage() {
    UUID worldId = UUID.fromString("00000000-0000-0000-0000-000000000041");
    EdgeId first = edge("MT-W", "MT-E");
    EdgeId second = edge("DS-N", "DS-S");
    RailFootprintCell crossing = new RailFootprintCell(10, 64, 10);
    RailFootprintCell ordinaryTrack = new RailFootprintCell(4000, 64, 4000);
    InterlockingZoneInfo zone =
        new InterlockingZoneInfo("interlocking:stable-zone", first, second, Set.of(crossing));
    RailInterlockingSnapshotRecord snapshot =
        new RailInterlockingSnapshotRecord(
            worldId,
            RailInterlockingSnapshotRecord.CURRENT_FORMAT_VERSION,
            "edge-signature",
            new RailInterlockingCoverage(2, 2, true),
            Map.of(zone.zoneKey(), zone));

    String json = RailInterlockingSnapshotCodec.encode(snapshot);

    assertFalse(json.contains(String.valueOf(ordinaryTrack.x())));
    assertTrue(json.contains("\"zones\""));
    assertEquals(snapshot, RailInterlockingSnapshotCodec.decode(worldId, json).orElseThrow());
  }

  @Test
  void unsupportedOrCorruptSnapshotIsRejectedInsteadOfBecomingEmptyCatalog() {
    UUID worldId = UUID.fromString("00000000-0000-0000-0000-000000000042");

    assertTrue(
        RailInterlockingSnapshotCodec.decode(
                worldId,
                "{\"formatVersion\":99,\"edgeSignature\":\"x\","
                    + "\"coverage\":{\"expectedEdges\":0,\"participatingEdges\":0,"
                    + "\"complete\":true},\"zones\":[]}")
            .isEmpty());
    assertTrue(RailInterlockingSnapshotCodec.decode(worldId, "not-json").isEmpty());
  }

  private static EdgeId edge(String first, String second) {
    return EdgeId.undirected(NodeId.of(first), NodeId.of(second));
  }
}
