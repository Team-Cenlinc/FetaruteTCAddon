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
    assertEquals(1, retry.attempts());
  }
}
