package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@link SpawnControl} 单元测试。 */
class SpawnControlTest {

  @Test
  void deniesWhenRunningAndPendingReachLineCapacity() {
    SpawnControl control = new SpawnControl(Duration.ofSeconds(30));
    UUID lineId = UUID.randomUUID();

    SpawnControl.Decision decision =
        control.tryAcquire(
            request(
                lineId,
                "ticket-1",
                SpawnControl.LeaseKind.SPAWN,
                OptionalInt.of(2),
                new SpawnControl.BaseCounters(1, 1),
                Instant.parse("2026-01-01T00:00:00Z")));

    assertFalse(decision.allowed());
    assertEquals("line-cap", decision.reason());
    assertEquals(2, decision.snapshot().total());
  }

  @Test
  void countsLayoverReservedAndReclaimReturnLeases() {
    SpawnControl control = new SpawnControl(Duration.ofSeconds(30));
    UUID lineId = UUID.randomUUID();
    Instant now = Instant.parse("2026-01-01T00:00:00Z");

    assertTrue(
        control
            .tryAcquire(
                request(
                    lineId,
                    "ticket-layover",
                    SpawnControl.LeaseKind.LAYOVER_REUSE,
                    OptionalInt.of(2),
                    SpawnControl.BaseCounters.empty(),
                    now))
            .allowed());
    assertTrue(
        control
            .tryAcquire(
                request(
                    lineId,
                    "ticket-return",
                    SpawnControl.LeaseKind.RECLAIM_RETURN,
                    OptionalInt.of(2),
                    SpawnControl.BaseCounters.empty(),
                    now))
            .allowed());

    SpawnControl.Decision blocked =
        control.tryAcquire(
            request(
                lineId,
                "ticket-spawn",
                SpawnControl.LeaseKind.SPAWN,
                OptionalInt.of(2),
                SpawnControl.BaseCounters.empty(),
                now));

    assertFalse(blocked.allowed());
    assertEquals(1, blocked.snapshot().layoverReserved());
    assertEquals(1, blocked.snapshot().reclaimReturn());
    assertEquals(2, blocked.snapshot().total());
  }

  @Test
  void releaseLeaseFreesCapacity() {
    SpawnControl control = new SpawnControl(Duration.ofSeconds(30));
    UUID lineId = UUID.randomUUID();
    Instant now = Instant.parse("2026-01-01T00:00:00Z");

    SpawnControl.Decision first =
        control.tryAcquire(
            request(
                lineId,
                "ticket-1",
                SpawnControl.LeaseKind.SPAWN,
                OptionalInt.of(1),
                SpawnControl.BaseCounters.empty(),
                now));
    assertTrue(first.allowed());
    first.lease().orElseThrow().release();

    SpawnControl.Decision second =
        control.tryAcquire(
            request(
                lineId,
                "ticket-2",
                SpawnControl.LeaseKind.SPAWN,
                OptionalInt.of(1),
                SpawnControl.BaseCounters.empty(),
                now));
    assertTrue(second.allowed());
  }

  @Test
  void sameTicketCannotOccupyNormalAndFallbackCapacityTwice() {
    SpawnControl control = new SpawnControl(Duration.ofSeconds(30));
    UUID lineId = UUID.randomUUID();
    Instant now = Instant.parse("2026-01-01T00:00:00Z");

    SpawnControl.Decision normal =
        control.tryAcquire(
            request(
                lineId,
                "ticket-1",
                SpawnControl.LeaseKind.SPAWN,
                OptionalInt.empty(),
                SpawnControl.BaseCounters.empty(),
                now));
    SpawnControl.Decision fallback =
        control.tryAcquire(
            request(
                lineId,
                "ticket-1",
                SpawnControl.LeaseKind.FALLBACK,
                OptionalInt.empty(),
                SpawnControl.BaseCounters.empty(),
                now));

    assertTrue(normal.allowed());
    assertFalse(fallback.allowed());
    assertEquals("lease-active", fallback.reason());
  }

  @Test
  void expiredLeaseNoLongerCountsAgainstCapacity() {
    SpawnControl control = new SpawnControl(Duration.ofSeconds(5));
    UUID lineId = UUID.randomUUID();
    Instant now = Instant.parse("2026-01-01T00:00:00Z");

    assertTrue(
        control
            .tryAcquire(
                request(
                    lineId,
                    "ticket-1",
                    SpawnControl.LeaseKind.SPAWN,
                    OptionalInt.of(1),
                    SpawnControl.BaseCounters.empty(),
                    now))
            .allowed());
    control.pruneExpired(now.plusSeconds(5));

    assertTrue(
        control
            .tryAcquire(
                request(
                    lineId,
                    "ticket-2",
                    SpawnControl.LeaseKind.SPAWN,
                    OptionalInt.of(1),
                    SpawnControl.BaseCounters.empty(),
                    now.plusSeconds(5)))
            .allowed());
  }

  private static SpawnControl.Request request(
      UUID lineId,
      String owner,
      SpawnControl.LeaseKind kind,
      OptionalInt maxTrains,
      SpawnControl.BaseCounters counters,
      Instant now) {
    return new SpawnControl.Request(
        owner,
        lineId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        Optional.empty(),
        kind,
        maxTrains,
        counters,
        now);
  }
}
