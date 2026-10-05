package org.fetarute.fetaruteTCAddon.dispatcher.sign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import net.kyori.adventure.text.Component;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.junit.jupiter.api.Test;

final class SignNodeTypeNamesTest {

  @Test
  void waypointKindTakesPrecedenceOverNodeType() {
    assertEquals(
        "sign.type.station_throat",
        SignNodeTypeNames.key(definition(NodeType.WAYPOINT, WaypointKind.STATION_THROAT, "1")));
    assertEquals(
        "sign.type.depot_throat",
        SignNodeTypeNames.key(definition(NodeType.WAYPOINT, WaypointKind.DEPOT_THROAT, "1")));
    assertEquals(
        "sign.type.station",
        SignNodeTypeNames.key(definition(NodeType.STATION, WaypointKind.STATION, null)));
    assertEquals(
        "sign.type.depot",
        SignNodeTypeNames.key(definition(NodeType.DEPOT, WaypointKind.DEPOT, null)));
  }

  @Test
  void otherNodesFallBackToNodeType() {
    assertEquals(
        "sign.type.waypoint",
        SignNodeTypeNames.key(definition(NodeType.WAYPOINT, WaypointKind.INTERVAL, "00")));
    assertEquals(
        "sign.type.switcher",
        SignNodeTypeNames.key(
            new SignNodeDefinition(
                NodeId.of("SWITCHER:w:1:2:3"),
                NodeType.SWITCHER,
                Optional.empty(),
                Optional.empty())));
  }

  @Test
  void localizedRendersPlainTextAndToleratesMissingInputs() {
    LocaleManager locale = mock(LocaleManager.class);
    when(locale.component("sign.type.station")).thenReturn(Component.text("站点"));
    SignNodeDefinition station = definition(NodeType.STATION, WaypointKind.STATION, null);

    assertEquals("站点", SignNodeTypeNames.localized(locale, station));
    assertEquals("STATION", SignNodeTypeNames.localized(null, station));
    assertEquals("", SignNodeTypeNames.localized(locale, null));
  }

  private static SignNodeDefinition definition(NodeType type, WaypointKind kind, String sequence) {
    return new SignNodeDefinition(
        NodeId.of("SURC:X:" + kind.name()),
        type,
        Optional.empty(),
        Optional.of(
            new WaypointMetadata(
                "SURC",
                "PTK",
                kind == WaypointKind.INTERVAL ? Optional.of("GPT") : Optional.empty(),
                1,
                Optional.ofNullable(sequence),
                kind)));
  }
}
