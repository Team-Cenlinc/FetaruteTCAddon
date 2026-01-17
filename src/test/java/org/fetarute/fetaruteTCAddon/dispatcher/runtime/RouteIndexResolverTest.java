package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.junit.jupiter.api.Test;

class RouteIndexResolverTest {

  @Test
  void prefersTagIndexWhenMatches() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"),
            List.of(NodeId.of("A"), NodeId.of("B"), NodeId.of("A"), NodeId.of("C")),
            Optional.empty());

    assertEquals(
        2, RouteIndexResolver.resolveCurrentIndex(route, OptionalInt.of(2), NodeId.of("A")));
  }

  @Test
  void searchesForwardFromTagIndexWhenMismatched() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"),
            List.of(NodeId.of("A"), NodeId.of("B"), NodeId.of("A"), NodeId.of("C")),
            Optional.empty());

    assertEquals(
        2, RouteIndexResolver.resolveCurrentIndex(route, OptionalInt.of(1), NodeId.of("A")));
  }

  @Test
  void fallsBackToFirstOccurrenceWithoutTagIndex() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"),
            List.of(NodeId.of("A"), NodeId.of("B"), NodeId.of("A"), NodeId.of("C")),
            Optional.empty());

    assertEquals(
        0, RouteIndexResolver.resolveCurrentIndex(route, OptionalInt.empty(), NodeId.of("A")));
  }
}
