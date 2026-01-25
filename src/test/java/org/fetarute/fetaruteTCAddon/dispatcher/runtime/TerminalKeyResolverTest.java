package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.Test;

/** TerminalKeyResolver 单元测试。 */
class TerminalKeyResolverTest {

  @Test
  void toTerminalKey_returnsLowercaseNodeIdValue() {
    NodeId nodeId = new NodeId("OP:S:Central:1");
    assertEquals("op:s:central:1", TerminalKeyResolver.toTerminalKey(nodeId));
  }

  @Test
  void toTerminalKey_returnsEmptyStringForNull() {
    assertEquals("", TerminalKeyResolver.toTerminalKey(null));
  }

  @Test
  void matches_exactMatch() {
    assertTrue(TerminalKeyResolver.matches("op:s:central:1", "op:s:central:1"));
    assertTrue(TerminalKeyResolver.matches("OP:S:Central:1", "op:s:central:1"));
  }

  @Test
  void matches_samePlatformDifferentTrack() {
    // 同站不同站台应该匹配
    assertTrue(TerminalKeyResolver.matches("op:s:central:1", "op:s:central:2"));
    assertTrue(TerminalKeyResolver.matches("OP:S:Central:1", "op:s:Central:2"));
  }

  @Test
  void matches_differentStation() {
    // 不同站点不应匹配
    assertFalse(TerminalKeyResolver.matches("op:s:central:1", "op:s:downtown:1"));
  }

  @Test
  void matches_depotSameName() {
    // 同车库不同股道应该匹配
    assertTrue(TerminalKeyResolver.matches("op:d:depot1:1", "op:d:depot1:2"));
  }

  @Test
  void matches_depotDifferentName() {
    // 不同车库不应匹配
    assertFalse(TerminalKeyResolver.matches("op:d:depot1:1", "op:d:depot2:1"));
  }

  @Test
  void matches_stationVsDepot() {
    // Station 和 Depot 不应匹配（即使名称相同）
    assertFalse(TerminalKeyResolver.matches("op:s:central:1", "op:d:central:1"));
  }

  @Test
  void matches_nullInputs() {
    assertFalse(TerminalKeyResolver.matches(null, "op:s:central:1"));
    assertFalse(TerminalKeyResolver.matches("op:s:central:1", null));
    assertFalse(TerminalKeyResolver.matches(null, null));
  }

  @Test
  void matches_waypointFormat() {
    // Waypoint 格式（5段）不应被解析为站点匹配
    assertFalse(TerminalKeyResolver.matches("op:from:to:track:1", "op:from:to:track:2"));
  }

  @Test
  void matches_stationThroat() {
    // Station throat（5段，第二段为 S）应该被解析为站点匹配
    assertTrue(TerminalKeyResolver.matches("op:s:central:1:entrance", "op:s:central:2:exit"));
  }

  @Test
  void extractStationKey_fourPartStation() {
    Optional<String> key = TerminalKeyResolver.extractStationKey("OP:S:Central:1");
    assertTrue(key.isPresent());
    assertEquals("op:s:central", key.get());
  }

  @Test
  void extractStationKey_fourPartDepot() {
    Optional<String> key = TerminalKeyResolver.extractStationKey("OP:D:Depot1:2");
    assertTrue(key.isPresent());
    assertEquals("op:d:depot1", key.get());
  }

  @Test
  void extractStationKey_fivePartStationThroat() {
    Optional<String> key = TerminalKeyResolver.extractStationKey("OP:S:Central:1:Entrance");
    assertTrue(key.isPresent());
    assertEquals("op:s:central", key.get());
  }

  @Test
  void extractStationKey_waypoint() {
    // Waypoint 第二段不是 S/D，不应解析
    Optional<String> key = TerminalKeyResolver.extractStationKey("OP:From:To:Track:1");
    assertFalse(key.isPresent());
  }

  @Test
  void extractStationKey_tooFewParts() {
    assertFalse(TerminalKeyResolver.extractStationKey("OP:S:Central").isPresent());
    assertFalse(TerminalKeyResolver.extractStationKey("OP:S").isPresent());
    assertFalse(TerminalKeyResolver.extractStationKey("OP").isPresent());
  }

  @Test
  void extractStationName_station() {
    NodeId nodeId = new NodeId("OP:S:Central:1");
    assertEquals("Central", TerminalKeyResolver.extractStationName(nodeId));
  }

  @Test
  void extractStationName_depot() {
    NodeId nodeId = new NodeId("OP:D:Depot1:2");
    assertEquals("Depot1", TerminalKeyResolver.extractStationName(nodeId));
  }

  @Test
  void extractStationName_fallbackToFullValue() {
    NodeId nodeId = new NodeId("SomeOtherFormat");
    assertEquals("SomeOtherFormat", TerminalKeyResolver.extractStationName(nodeId));
  }

  @Test
  void isStationNode_true() {
    assertTrue(TerminalKeyResolver.isStationNode(new NodeId("OP:S:Central:1")));
    assertTrue(TerminalKeyResolver.isStationNode(new NodeId("op:s:central:1")));
  }

  @Test
  void isStationNode_false() {
    assertFalse(TerminalKeyResolver.isStationNode(new NodeId("OP:D:Depot:1")));
    assertFalse(TerminalKeyResolver.isStationNode(new NodeId("OP:From:To:Track:1")));
    assertFalse(TerminalKeyResolver.isStationNode(null));
  }

  @Test
  void isDepotNode_true() {
    assertTrue(TerminalKeyResolver.isDepotNode(new NodeId("OP:D:Depot1:1")));
    assertTrue(TerminalKeyResolver.isDepotNode(new NodeId("op:d:depot1:1")));
  }

  @Test
  void isDepotNode_false() {
    assertFalse(TerminalKeyResolver.isDepotNode(new NodeId("OP:S:Central:1")));
    assertFalse(TerminalKeyResolver.isDepotNode(new NodeId("OP:From:To:Track:1")));
    assertFalse(TerminalKeyResolver.isDepotNode(null));
  }
}
