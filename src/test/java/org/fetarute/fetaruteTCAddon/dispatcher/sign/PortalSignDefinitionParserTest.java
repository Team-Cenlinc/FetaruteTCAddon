package org.fetarute.fetaruteTCAddon.dispatcher.sign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("传送门节点")
class PortalSignDefinitionParserTest {

  @Test
  @DisplayName("节点 ID 与牌子坐标的写入和取回")
  void idsRoundTrip() {
    NodeId id =
        PortalSignDefinitionParser.nodeIdForRail("world_nether", new RailBlockPos(10, -12, 7));
    assertEquals("PORTAL:world_nether:10:-12:7", id.value());
    assertTrue(PortalSignDefinitionParser.isPortal(id));
    assertEquals(
        Optional.of(new RailBlockPos(10, -12, 7)), PortalSignDefinitionParser.tryParseRailPos(id));
    assertEquals(
        Optional.of(new RailBlockPos(3, 60, -4)),
        PortalSignDefinitionParser.signPos(Optional.of("@portal:3,60,-4")));
    assertTrue(PortalSignDefinitionParser.signPos(Optional.of("@switcher_sign")).isEmpty());
  }

  @Test
  @DisplayName("只认 [portal] 头部（允许红石前缀），不认 [train]")
  void header() {
    assertTrue(PortalSignDefinitionParser.isPortalHeader("[portal]"));
    assertTrue(PortalSignDefinitionParser.isPortalHeader("[!portal]"));
    assertFalse(PortalSignDefinitionParser.isPortalHeader("[train]"));
    assertFalse(PortalSignDefinitionParser.isPortalHeader(""));
  }

  @Test
  @DisplayName("传送门的自动 ID 不会被当成区间点（y 为负时也不抛异常）；道岔沿用旧行为")
  void autoIdsAreNotWaypoints() {
    assertTrue(
        SignTextParser.parseWaypointLike("PORTAL:world:10:-12:7", NodeType.WAYPOINT).isEmpty());
    assertFalse(
        SignTextParser.parseWaypointLike("SWITCHER:world:10:64:7", NodeType.WAYPOINT).isEmpty(),
        "咽喉路线仍把道岔自动 ID 当区间点终止");
    assertFalse(
        SignTextParser.parseWaypointLike("OP:AAA:BBB:1:01", NodeType.WAYPOINT).isEmpty(),
        "普通区间点照常解析");
  }

  @Test
  @DisplayName("跨世界开关关闭时传送门不进图")
  void gate() {
    boolean before = GraphSignParsers.portalsEnabled();
    try {
      GraphSignParsers.setPortalsEnabled(false);
      assertFalse(GraphSignParsers.portalsEnabled());
      GraphSignParsers.setPortalsEnabled(true);
      assertTrue(GraphSignParsers.portalsEnabled());
    } finally {
      GraphSignParsers.setPortalsEnabled(before);
    }
  }
}
