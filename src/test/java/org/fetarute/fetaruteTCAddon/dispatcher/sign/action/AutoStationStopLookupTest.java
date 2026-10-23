package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.junit.jupiter.api.Test;

/** AutoStation 停站入口的三类匹配都必须服从动态目标资格。 */
class AutoStationStopLookupTest {
  @Test
  void stationIdentityAndExactPlaceholderCannotOpenDoorsOnExcludedTrack() {
    UUID stationId = UUID.randomUUID();
    Station station = mock(Station.class);
    when(station.id()).thenReturn(stationId);
    RouteStop stop =
        new RouteStop(
            UUID.randomUUID(),
            0,
            Optional.of(stationId),
            Optional.of("OP:S:DEST:2"),
            Optional.of(20),
            RouteStopPassType.STOP,
            Optional.of("DYNAMIC:OP:S:DEST:[1,3,5]"));
    var excluded = definition("OP:S:DEST:2");
    assertTrue(
        AutoStationSignAction.findStop(
                List.of(stop), excluded, Optional.of(station), "DEST", Optional.of(0))
            .isEmpty());
    assertTrue(
        AutoStationSignAction.findStop(
                List.of(stop), excluded, Optional.empty(), "DEST", Optional.of(0))
            .isEmpty());
    assertEquals(
        Optional.of(stop),
        AutoStationSignAction.findStop(
            List.of(stop),
            definition("OP:S:DEST:3"),
            Optional.of(station),
            "DEST",
            Optional.of(0)));
  }

  @Test
  void stationCodeFallbackCannotAcceptAnExcludedOrInvalidDynamicTarget() {
    RouteStop stop =
        new RouteStop(
            UUID.randomUUID(),
            0,
            Optional.empty(),
            Optional.of("OP:S:DEST:1"),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.of("DYNAMIC:OP:S:DEST:[]"));
    assertTrue(
        AutoStationSignAction.findStop(
                List.of(stop), definition("OP:S:DEST:2"), Optional.empty(), "DEST", Optional.of(0))
            .isEmpty());
    assertTrue(
        AutoStationSignAction.findStop(
                List.of(stop), definition("OP:S:DEST:1"), Optional.empty(), "DEST", Optional.of(0))
            .isEmpty());
  }

  private static SignNodeDefinition definition(String node) {
    return new SignNodeDefinition(
        NodeId.of(node), NodeType.STATION, Optional.empty(), Optional.empty());
  }
}
