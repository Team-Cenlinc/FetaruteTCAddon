package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.Test;

class LayoverRegistryTest {

  @Test
  void renamePreservesCandidateAndMovesLookupKey() {
    LayoverRegistry registry = new LayoverRegistry();
    Instant readyAt = Instant.parse("2026-01-01T00:00:00Z");
    NodeId terminal = NodeId.of("SURC:S:PPK:1");
    registry.register("inbound", "SURC:S:PPK", terminal, readyAt, Map.of("route", "old"));

    assertTrue(registry.rename("inbound", "outbound"));

    assertTrue(registry.get("inbound").isEmpty());
    LayoverRegistry.LayoverCandidate migrated = registry.get("outbound").orElseThrow();
    assertEquals("outbound", migrated.trainName());
    assertEquals(terminal, migrated.locationNodeId());
    assertEquals(readyAt, migrated.readyAt());
    assertEquals(Map.of("route", "old"), migrated.tags());
  }

  @Test
  void renameCollisionRestoresOriginalCandidate() {
    LayoverRegistry registry = new LayoverRegistry();
    Instant readyAt = Instant.parse("2026-01-01T00:00:00Z");
    registry.register("inbound", "terminal", NodeId.of("A"), readyAt, Map.of());
    registry.register("outbound", "terminal", NodeId.of("B"), readyAt, Map.of());

    assertFalse(registry.rename("inbound", "outbound"));

    assertEquals("A", registry.get("inbound").orElseThrow().locationNodeId().value());
    assertEquals("B", registry.get("outbound").orElseThrow().locationNodeId().value());
  }

  @Test
  void dispatchAttemptIsStableForSameTicketAndRejectsOtherTicket() {
    LayoverRegistry registry = registeredCandidate("inbound");

    LayoverRegistry.DispatchAttempt first =
        registry.claimDispatch("inbound", "ticket-1", "outbound-1", Instant.now()).orElseThrow();
    LayoverRegistry.DispatchAttempt retry =
        registry.claimDispatch("inbound", "ticket-1", "outbound-2", Instant.now()).orElseThrow();

    assertEquals(first, retry);
    assertEquals("outbound-1", retry.targetTrainName());
    assertTrue(
        registry.claimDispatch("inbound", "ticket-2", "outbound-2", Instant.now()).isEmpty());
  }

  @Test
  void ticketAttemptHasOnlyOneOwnerAcrossCandidates() {
    LayoverRegistry registry = registeredCandidate("train-1");
    registry.register(
        "train-2",
        "terminal",
        NodeId.of("SURC:S:TERM:2"),
        Instant.parse("2026-01-01T00:00:01Z"),
        Map.of());

    LayoverRegistry.DispatchAttempt attempt =
        registry.claimDispatch("train-1", "ticket-1", "outbound-1", Instant.now()).orElseThrow();

    assertTrue(
        registry.claimDispatch("train-2", "ticket-1", "outbound-2", Instant.now()).isEmpty());
    LayoverRegistry.LayoverCandidate owner =
        registry.findDispatchAttemptOwner("ticket-1").orElseThrow();
    assertEquals("train-1", owner.trainName());
    assertEquals(Optional.of(attempt), owner.dispatchAttempt());
    assertTrue(registry.findDispatchAttemptOwner("ticket-2").isEmpty());
  }

  @Test
  void renamePreservesAttemptAndOnlyMatchingTicketCanReleaseIt() {
    LayoverRegistry registry = registeredCandidate("inbound");
    registry.claimDispatch("inbound", "ticket-1", "outbound", Instant.now()).orElseThrow();

    assertTrue(registry.rename("inbound", "outbound"));
    assertEquals(
        "ticket-1",
        registry.get("outbound").orElseThrow().dispatchAttempt().orElseThrow().ticketId());
    assertFalse(registry.releaseDispatchAttempt("outbound", "ticket-2"));
    assertTrue(registry.releaseDispatchAttempt("outbound", "ticket-1"));
    assertTrue(registry.get("outbound").orElseThrow().dispatchAttempt().isEmpty());
  }

  @Test
  void dispatchAttemptLookupFollowsCandidateRename() {
    LayoverRegistry registry = registeredCandidate("inbound");
    registry.claimDispatch("inbound", "ticket-1", "outbound", Instant.now()).orElseThrow();

    assertTrue(registry.hasDispatchAttemptForTicket("ticket-1"));
    assertTrue(registry.rename("inbound", "outbound"));

    assertTrue(registry.hasDispatchAttemptForTicket("ticket-1"));
    assertFalse(registry.hasDispatchAttemptForTicket("ticket-2"));
  }

  @Test
  void repeatedRegistrationCannotEraseActiveDispatchAttempt() {
    LayoverRegistry registry = registeredCandidate("inbound");
    LayoverRegistry.DispatchAttempt attempt =
        registry.claimDispatch("inbound", "ticket-1", "outbound", Instant.now()).orElseThrow();

    registry.register(
        "inbound",
        "terminal-refreshed",
        NodeId.of("SURC:S:TERM:2"),
        Instant.parse("2026-01-01T00:00:05Z"),
        Map.of("state", "refreshed"));

    LayoverRegistry.LayoverCandidate refreshed = registry.get("inbound").orElseThrow();
    assertEquals(Optional.of(attempt), refreshed.dispatchAttempt());
    assertEquals("terminal-refreshed", refreshed.terminalKey());
    assertEquals("SURC:S:TERM:2", refreshed.locationNodeId().value());
  }

  private static LayoverRegistry registeredCandidate(String trainName) {
    LayoverRegistry registry = new LayoverRegistry();
    registry.register(
        trainName,
        "terminal",
        NodeId.of("SURC:S:TERM:1"),
        Instant.parse("2026-01-01T00:00:00Z"),
        Map.of());
    return registry;
  }
}
