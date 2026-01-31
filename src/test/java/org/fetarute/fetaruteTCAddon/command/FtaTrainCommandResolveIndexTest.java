package org.fetarute.fetaruteTCAddon.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.junit.jupiter.api.Test;

class FtaTrainCommandResolveIndexTest {

  @Test
  void resolveIndexFallsBackToZeroWhenMissing() throws Exception {
    FtaTrainCommand command = new FtaTrainCommand(null);
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"),
            List.of(NodeId.of("A"), NodeId.of("B"), NodeId.of("C")),
            Optional.empty());

    Optional<Integer> index = invokeResolveIndex(command, Optional.empty(), route);
    assertTrue(index.isPresent());
    assertEquals(0, index.get());
  }

  @Test
  void resolveIndexParsesNumericIndex() throws Exception {
    FtaTrainCommand command = new FtaTrainCommand(null);
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());

    Optional<Integer> index = invokeResolveIndex(command, Optional.of("1"), route);
    assertTrue(index.isPresent());
    assertEquals(1, index.get());
  }

  @Test
  void resolveIndexFindsNodeIdWithinRoute() throws Exception {
    FtaTrainCommand command = new FtaTrainCommand(null);
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"),
            List.of(NodeId.of("A"), NodeId.of("SURN:S:PTK:1"), NodeId.of("C")),
            Optional.empty());

    Optional<Integer> index = invokeResolveIndex(command, Optional.of("SURN:S:PTK:1"), route);
    assertTrue(index.isPresent());
    assertEquals(1, index.get());
  }

  @Test
  void resolveIndexReturnsEmptyWhenNodeIdNotInRoute() throws Exception {
    FtaTrainCommand command = new FtaTrainCommand(null);
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());

    Optional<Integer> index = invokeResolveIndex(command, Optional.of("Z"), route);
    assertTrue(index.isEmpty());
  }

  private static Optional<Integer> invokeResolveIndex(
      FtaTrainCommand command, Optional<String> input, RouteDefinition route) throws Exception {
    Method method =
        FtaTrainCommand.class.getDeclaredMethod(
            "resolveIndex", Optional.class, RouteDefinition.class);
    method.setAccessible(true);
    @SuppressWarnings("unchecked")
    Optional<Integer> result = (Optional<Integer>) method.invoke(command, input, route);
    return result;
  }
}
