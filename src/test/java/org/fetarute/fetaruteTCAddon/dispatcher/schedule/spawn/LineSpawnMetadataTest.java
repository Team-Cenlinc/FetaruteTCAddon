package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class LineSpawnMetadataTest {

  @Test
  void parseDepotsSupportsStringList() {
    Map<String, Object> metadata =
        Map.of(LineSpawnMetadata.KEY_DEPOTS, List.of("OP:D:DEPOT:1", "OP:D:DEPOT:2"));
    List<SpawnDepot> depots = LineSpawnMetadata.parseDepots(metadata);
    assertEquals(2, depots.size());
    assertEquals("OP:D:DEPOT:1", depots.get(0).nodeId());
    assertEquals(1, depots.get(0).weight());
    assertEquals("OP:D:DEPOT:2", depots.get(1).nodeId());
  }

  @Test
  void parseDepotsSupportsMapEntries() {
    Map<String, Object> metadata =
        Map.of(
            LineSpawnMetadata.KEY_DEPOTS,
            List.of(
                Map.of("nodeId", "OP:D:DEPOT:1", "weight", 2),
                Map.of("node", "OP:D:DEPOT:2", "enabled", false)));
    List<SpawnDepot> depots = LineSpawnMetadata.parseDepots(metadata);
    assertEquals(1, depots.size());
    assertEquals("OP:D:DEPOT:1", depots.get(0).nodeId());
    assertEquals(2, depots.get(0).weight());
  }

  @Test
  void parseMaxTrainsSupportsString() {
    Map<String, Object> metadata = Map.of(LineSpawnMetadata.KEY_MAX_TRAINS, "3");
    OptionalInt maxTrains = LineSpawnMetadata.parseMaxTrains(metadata);
    assertTrue(maxTrains.isPresent());
    assertEquals(3, maxTrains.getAsInt());
  }
}
