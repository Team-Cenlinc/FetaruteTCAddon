package org.fetarute.fetaruteTCAddon.dispatcher.sign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.junit.jupiter.api.Test;

final class SignTextParserTest {

  @Test
  void depotFourSegmentsIsDepotNotThroat() {
    Optional<SignNodeDefinition> defOpt =
        SignTextParser.parseWaypointLike("SURN:D:AAA:1", NodeType.DEPOT);
    assertTrue(defOpt.isPresent());
    assertEquals(WaypointKind.DEPOT, defOpt.get().waypointMetadata().orElseThrow().kind());
    assertTrue(defOpt.get().waypointMetadata().orElseThrow().sequence().isEmpty());
  }

  @Test
  void depotFiveSegmentsIsDepotThroat() {
    Optional<SignNodeDefinition> defOpt =
        SignTextParser.parseWaypointLike("SURN:D:AAA:1:01", NodeType.DEPOT);
    assertTrue(defOpt.isPresent());
    assertEquals(WaypointKind.DEPOT_THROAT, defOpt.get().waypointMetadata().orElseThrow().kind());
    assertEquals("01", defOpt.get().waypointMetadata().orElseThrow().sequence().orElseThrow());
  }

  @Test
  void stationFourSegmentsIsStationNotThroat() {
    Optional<SignNodeDefinition> defOpt =
        SignTextParser.parseWaypointLike("SURN:S:PTK:2", NodeType.STATION);
    assertTrue(defOpt.isPresent());
    assertEquals(WaypointKind.STATION, defOpt.get().waypointMetadata().orElseThrow().kind());
    assertTrue(defOpt.get().waypointMetadata().orElseThrow().sequence().isEmpty());
  }

  @Test
  void stationFiveSegmentsIsStationThroat() {
    Optional<SignNodeDefinition> defOpt =
        SignTextParser.parseWaypointLike("SURN:S:PTK:2:00", NodeType.STATION);
    assertTrue(defOpt.isPresent());
    assertEquals(WaypointKind.STATION_THROAT, defOpt.get().waypointMetadata().orElseThrow().kind());
    assertEquals("00", defOpt.get().waypointMetadata().orElseThrow().sequence().orElseThrow());
  }

  @Test
  void intervalWaypointKeepsWorking() {
    Optional<SignNodeDefinition> defOpt =
        SignTextParser.parseWaypointLike("SURN:PTK:GPT:1:00", NodeType.WAYPOINT);
    assertTrue(defOpt.isPresent());
    assertEquals(WaypointKind.INTERVAL, defOpt.get().waypointMetadata().orElseThrow().kind());
  }
}
