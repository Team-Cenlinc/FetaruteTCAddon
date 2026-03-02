package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;
import java.util.stream.Collectors;
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

  @Test
  void parseGroupsReadsMaxOperationTrips() {
    Map<String, Object> metadata =
        Map.of(
            LineSpawnMetadata.KEY_GROUPS,
            List.of(
                Map.of("name", "op-main", "baselineSec", 120, "maxOperationTrips", 3),
                Map.of("name", "op-spare", "max_trips", 5)));

    List<SpawnGroup> groups = LineSpawnMetadata.parseGroups(metadata);
    assertEquals(2, groups.size());
    Map<String, SpawnGroup> byName =
        groups.stream().collect(Collectors.toMap(SpawnGroup::name, Function.identity()));
    assertEquals(Optional.of(3), byName.get("op-main").maxOperationTrips());
    assertEquals(Optional.of(5), byName.get("op-spare").maxOperationTrips());
  }

  @Test
  void parseGroupMaxOperationTripsFindsConfiguredGroup() {
    Map<String, Object> metadata =
        Map.of(
            LineSpawnMetadata.KEY_GROUPS, List.of(Map.of("name", "main", "maxOperationTrips", 4)));

    Optional<Integer> maxTrips = LineSpawnMetadata.parseGroupMaxOperationTrips(metadata, "MAIN");
    assertEquals(Optional.of(4), maxTrips);
  }

  @Test
  void toGroupMetadataWritesMaxOperationTrips() {
    List<Map<String, Object>> metadata =
        LineSpawnMetadata.toGroupMetadata(
            List.of(new SpawnGroup("main", Optional.of(180), Optional.of(6))));

    assertEquals(1, metadata.size());
    Map<String, Object> entry = metadata.get(0);
    assertEquals("main", entry.get("name"));
    assertEquals(180, entry.get("baselineSec"));
    assertEquals(6, entry.get("maxOperationTrips"));
  }
}
