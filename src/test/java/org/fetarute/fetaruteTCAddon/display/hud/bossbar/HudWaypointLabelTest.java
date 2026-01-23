package org.fetarute.fetaruteTCAddon.display.hud.bossbar;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.Test;

class HudWaypointLabelTest {

  @Test
  void stationLabelParsesStationAndInterval() {
    assertEquals("Central", HudWaypointLabel.stationLabel(NodeId.of("OP:S:Central:1")));
    assertEquals("Central", HudWaypointLabel.stationLabel(NodeId.of("OP:S:Central:1:01")));
    assertEquals("To", HudWaypointLabel.stationLabel(NodeId.of("OP:From:To:1:01")));
  }
}
