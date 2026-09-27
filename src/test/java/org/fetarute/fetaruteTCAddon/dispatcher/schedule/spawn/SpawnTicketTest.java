package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SpawnTicketTest {

  @Test
  void withRetryMovesDueAtToRetryWindowToAvoidQueueStarvation() {
    UUID routeId = UUID.randomUUID();
    SpawnService service =
        new SpawnService(
            new SpawnServiceKey(routeId),
            UUID.randomUUID(),
            "COMP",
            UUID.randomUUID(),
            "OP",
            UUID.randomUUID(),
            "L1",
            routeId,
            "R1",
            Duration.ofSeconds(60),
            "SURC:S:PPK:1");
    Instant dueAt = Instant.parse("2026-02-07T00:00:00Z");
    SpawnTicket ticket =
        new SpawnTicket(
            UUID.randomUUID(), service, dueAt, dueAt, 0, 1L, Optional.empty(), Optional.empty());

    Instant retryAt = dueAt.plusSeconds(15);
    SpawnTicket retry = ticket.withRetry(retryAt, "gate-blocked:STOP");

    assertEquals(retryAt, retry.notBefore());
    assertEquals(retryAt, retry.dueAt());
    assertEquals(dueAt, retry.firstDueAt());
    assertEquals(1, retry.attempts());
  }

  /** 被闭塞挡住的重试：推进时间窗、不加 attempts、丢掉选定的 depot；只是推迟的 delayedUntil 保留 depot。 */
  @Test
  void blockedRetryKeepsAttemptsButDropsTheSelectedDepot() {
    UUID routeId = UUID.randomUUID();
    SpawnService service =
        new SpawnService(
            new SpawnServiceKey(routeId),
            UUID.randomUUID(),
            "COMP",
            UUID.randomUUID(),
            "OP",
            UUID.randomUUID(),
            "L1",
            routeId,
            "R1",
            Duration.ofSeconds(60),
            "SURC:D:HHU:3");
    Instant dueAt = Instant.parse("2026-02-07T00:00:00Z");
    SpawnTicket ticket =
        new SpawnTicket(
                UUID.randomUUID(), service, dueAt, dueAt, 3, 1L, Optional.empty(), Optional.empty())
            .withSelectedDepot("SURC:D:HHU:3");
    Instant retryAt = dueAt.plusSeconds(5);

    SpawnTicket blocked = ticket.blockedUntil(retryAt, "gate-blocked:STOP");
    SpawnTicket delayed = ticket.delayedUntil(retryAt, "spawn-per-tick-limit");

    assertEquals(3, blocked.attempts());
    assertEquals(Optional.empty(), blocked.selectedDepotNodeId());
    assertEquals(retryAt, blocked.dueAt());
    assertEquals(dueAt, blocked.firstDueAt());
    assertEquals(Optional.of("gate-blocked:STOP"), blocked.lastError());
    assertEquals(Optional.of("SURC:D:HHU:3"), delayed.selectedDepotNodeId());
    assertEquals(3, delayed.attempts());
  }
}
