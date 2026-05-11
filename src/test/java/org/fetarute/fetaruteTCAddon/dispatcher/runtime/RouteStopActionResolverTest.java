package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.junit.jupiter.api.Test;

class RouteStopActionResolverTest {

  private final RouteStopActionResolver resolver = new RouteStopActionResolver();

  @Test
  void changeIntentReturnsParsedIntentOnly() {
    RouteStop stop = stop(0, RouteStopPassType.PASS, "CHANGE:OP2:LINE2");

    Optional<RouteStopActionResolver.ChangeIntent> intentOpt = resolver.changeIntent(stop);

    assertTrue(intentOpt.isPresent());
    RouteStopActionResolver.ChangeIntent intent = intentOpt.get();
    assertTrue(intent.valid());
    assertEquals("OP2", intent.operatorCode());
    assertEquals("LINE2", intent.lineCode());
  }

  @Test
  void invalidChangeIntentDoesNotInventTarget() {
    RouteStop stop = stop(0, RouteStopPassType.PASS, "CHANGE:OP2");

    Optional<RouteStopActionResolver.ChangeIntent> intentOpt = resolver.changeIntent(stop);

    assertTrue(intentOpt.isPresent());
    assertFalse(intentOpt.get().valid());
    assertEquals("format", intentOpt.get().reason());
  }

  @Test
  void dynamicTargetReadsDirectAndDstyInlineSpecs() {
    RouteStop direct = stop(1, RouteStopPassType.PASS, "DYNAMIC:OP:S:DEST:[1:3]");
    RouteStop inline = stop(2, RouteStopPassType.PASS, "DSTY DYNAMIC:OP:D:DEPOT:[2:4]");

    assertEquals("OP:S:DEST:[1:3]", resolver.dynamicTarget(direct).orElseThrow());
    assertEquals("OP:D:DEPOT:[2:4]", resolver.dynamicTarget(inline).orElseThrow());
  }

  @Test
  void shouldDestroyAtMatchesExactAndDynamicDstyTargets() {
    RouteStop exact = stop(1, RouteStopPassType.PASS, "DSTY OP:D:DEPOT:1");
    RouteStop dynamic = stop(2, RouteStopPassType.PASS, "DSTY DYNAMIC:OP:D:DEPOT:[1:2]");

    assertTrue(resolver.shouldDestroyAt(exact, NodeId.of("OP:D:DEPOT:1")));
    assertTrue(resolver.shouldDestroyAt(dynamic, NodeId.of("OP:D:DEPOT:2")));
    assertFalse(resolver.shouldDestroyAt(dynamic, NodeId.of("OP:S:DEPOT:2")));
  }

  @Test
  void fallbackDestroyOnlyAppliesToLastPassDepotWithoutNotes() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("ROUTE"),
            List.of(NodeId.of("OP:S:A:1"), NodeId.of("OP:D:DEPOT:1")),
            Optional.empty());
    RouteStop stop = stop(1, RouteStopPassType.PASS, null);
    SignNodeDefinition depot =
        new SignNodeDefinition(
            NodeId.of("OP:D:DEPOT:1"), NodeType.DEPOT, Optional.empty(), Optional.empty());
    SignNodeDefinition station =
        new SignNodeDefinition(
            NodeId.of("OP:S:A:1"), NodeType.STATION, Optional.empty(), Optional.empty());

    assertTrue(resolver.shouldDestroyAtFallback(stop, 1, route, depot));
    assertFalse(resolver.shouldDestroyAtFallback(stop, 0, route, depot));
    assertFalse(resolver.shouldDestroyAtFallback(stop, 1, route, station));
  }

  private static RouteStop stop(int sequence, RouteStopPassType passType, String notes) {
    return new RouteStop(
        UUID.randomUUID(),
        sequence,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        passType,
        Optional.ofNullable(notes));
  }
}
