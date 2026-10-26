package org.fetarute.fetaruteTCAddon.dispatcher.sign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.SignActionHeader;
import com.bergerkiller.bukkit.tc.rails.RailLookup.TrackedSign;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

final class NodeSignDefinitionParserTest {

  @Test
  void autostationRecordsDoorDirectionFromBukkitSign() {
    SignNodeDefinition def = parseBukkit("autostation", "SURC:S:PPK:1", "N").orElseThrow();

    assertEquals(NodeId.of("SURC:S:PPK:1"), def.nodeId());
    assertEquals(NodeType.STATION, def.nodeType());
    assertEquals(Optional.of("NORTH"), def.doorDirection());
  }

  @Test
  void autostationRecordsDoorDirectionFromTrackedSign() {
    assertEquals(
        Optional.of("BOTH"),
        parseTracked("autostation", "SURC:S:PPK:1", "BOTH").orElseThrow().doorDirection());
    assertEquals(
        Optional.of("SOUTH_WEST"),
        parseTracked("autostation", "SURC:S:PPK:1", " sw ").orElseThrow().doorDirection());
  }

  @Test
  void blankOrUnrecognizedDoorLineLeavesDoorDirectionEmpty() {
    for (String line3 : new String[] {"", "   ", "xyz", "NONE"}) {
      SignNodeDefinition bukkit = parseBukkit("autostation", "SURC:S:PPK:1", line3).orElseThrow();
      SignNodeDefinition tracked = parseTracked("autostation", "SURC:S:PPK:1", line3).orElseThrow();
      assertEquals(NodeId.of("SURC:S:PPK:1"), bukkit.nodeId(), "节点 ID 照常解析: " + line3);
      assertEquals(Optional.empty(), bukkit.doorDirection(), "Bukkit 牌子: " + line3);
      assertEquals(Optional.empty(), tracked.doorDirection(), "TCC 虚拟牌子: " + line3);
    }
  }

  @Test
  void nodeIdSplitAcrossLinesIsNotMistakenForDoorDirection() {
    String[][] splits = {{"SURC:S:PPK:", "1"}, {"SURC:S:", "PPK:1"}, {"SURC:S:PPK", ":12"}};
    for (String[] split : splits) {
      String expected = split[0] + split[1];
      SignNodeDefinition bukkit = parseBukkit("autostation", split[0], split[1]).orElseThrow();
      SignNodeDefinition tracked = parseTracked("autostation", split[0], split[1]).orElseThrow();
      assertEquals(NodeId.of(expected), bukkit.nodeId());
      assertEquals(NodeId.of(expected), tracked.nodeId());
      assertEquals(Optional.empty(), bukkit.doorDirection(), "拆行的 ID: " + expected);
      assertEquals(Optional.empty(), tracked.doorDirection(), "拆行的 ID: " + expected);
    }
  }

  @Test
  void waypointAndDepotSignsNeverRecordDoorDirection() {
    SignNodeDefinition waypoint = parseBukkit("waypoint", "SURC:PPK:GPT:1:00", "N").orElseThrow();
    SignNodeDefinition depot = parseTracked("depot", "SURC:D:DEP:1", "BOTH").orElseThrow();

    assertEquals(NodeType.WAYPOINT, waypoint.nodeType());
    assertEquals(Optional.empty(), waypoint.doorDirection());
    assertEquals(NodeType.DEPOT, depot.nodeType());
    assertEquals(Optional.empty(), depot.doorDirection());
  }

  private static Optional<SignNodeDefinition> parseBukkit(String type, String line2, String line3) {
    Sign sign = mock(Sign.class);
    SignSide view = mock(SignSide.class);
    when(sign.getSide(Side.FRONT)).thenReturn(view);
    when(sign.getSide(Side.BACK)).thenReturn(view);
    when(view.line(0)).thenReturn(Component.text("[train]"));
    when(view.line(1)).thenReturn(Component.text(type));
    when(view.line(2)).thenReturn(Component.text(line2));
    when(view.line(3)).thenReturn(Component.text(line3));

    SignActionHeader header = trainHeader();
    try (MockedStatic<SignActionHeader> mocked = mockStatic(SignActionHeader.class)) {
      mocked.when(() -> SignActionHeader.parse("[train]")).thenReturn(header);
      return NodeSignDefinitionParser.parse(sign);
    }
  }

  private static Optional<SignNodeDefinition> parseTracked(
      String type, String line2, String line3) {
    TrackedSign trackedSign = mock(TrackedSign.class);
    SignActionHeader header = trainHeader();
    when(trackedSign.getHeader()).thenReturn(header);
    when(trackedSign.getLine(1)).thenReturn(type);
    when(trackedSign.getLine(2)).thenReturn(line2);
    when(trackedSign.getLine(3)).thenReturn(line3);
    return NodeSignDefinitionParser.parse(trackedSign);
  }

  private static SignActionHeader trainHeader() {
    SignActionHeader header = mock(SignActionHeader.class);
    when(header.isTrain()).thenReturn(true);
    when(header.isCart()).thenReturn(false);
    return header;
  }
}
