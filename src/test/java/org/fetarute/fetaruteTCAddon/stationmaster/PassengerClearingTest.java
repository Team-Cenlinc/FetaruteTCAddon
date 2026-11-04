package org.fetarute.fetaruteTCAddon.stationmaster;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.Test;

/** 清客的站：最后一个载客站，且之后还要走（回库、空驶）或终点后销毁；终点折返待命的不算。 */
class PassengerClearingTest {

  private static final UUID ROUTE = UUID.randomUUID();

  private static RouteStop stop(int sequence, String node, RouteStopPassType type, String notes) {
    return new RouteStop(
        ROUTE,
        sequence,
        Optional.empty(),
        Optional.of(node),
        Optional.empty(),
        type,
        Optional.ofNullable(notes));
  }

  @Test
  void aReturnLegClearsAtItsLastPassengerStation() {
    List<RouteStop> stops =
        List.of(
            stop(0, "OP:S:AAA:1", RouteStopPassType.STOP, null),
            stop(1, "OP:S:BBB:1", RouteStopPassType.STOP, null),
            stop(2, "OP:D:DEP:1", RouteStopPassType.STOP, null));
    assertTrue(PassengerClearing.lastPassengerStop(stops, 1, NodeId.of("OP:S:BBB:1")));
    assertFalse(PassengerClearing.lastPassengerStop(stops, 0, NodeId.of("OP:S:AAA:1")), "中途站不清客");
    assertTrue(
        PassengerClearing.lastPassengerStop(stops, 1, NodeId.of("OP:S:BBB:2")), "改停到同一站的另一股道照样清客");
    assertFalse(PassengerClearing.lastPassengerStop(stops, 1, NodeId.of("OP:S:CCC:1")), "停的不是这一站");
  }

  @Test
  void aTerminalThatTurnsBackIsNotCleared() {
    List<RouteStop> stops =
        List.of(
            stop(0, "OP:S:AAA:1", RouteStopPassType.STOP, null),
            stop(1, "OP:S:BBB:1", RouteStopPassType.TERMINATE, null));
    assertFalse(
        PassengerClearing.lastPassengerStop(stops, 1, NodeId.of("OP:S:BBB:1")),
        "终点折返的车多半接着开下一趟，车上的乘客等的正是那一趟");
  }

  @Test
  void aTerminalFollowedByDestructionIsCleared() {
    List<RouteStop> stops =
        List.of(
            stop(0, "OP:S:AAA:1", RouteStopPassType.STOP, null),
            stop(1, "OP:S:BBB:1", RouteStopPassType.STOP, "DSTY"));
    assertTrue(PassengerClearing.lastPassengerStop(stops, 1, NodeId.of("OP:S:BBB:1")));
  }
}
