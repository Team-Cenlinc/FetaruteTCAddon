package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.model.TripSource;
import org.junit.jupiter.api.Test;

class DepotSpawnSchedulerTest {

  @Test
  void coordinateAllowsOnlyOneTicketPerDepotWithStableOrdering() {
    Instant now = Instant.parse("2026-02-01T00:00:00Z");
    DepotSpawnScheduler scheduler = new DepotSpawnScheduler(Duration.ofSeconds(5));
    SpawnTicket slow = ticket("R1", "OP:D:DEPOT:1", now.plusSeconds(10), 0, 1);
    SpawnTicket lowerPriority = ticket("R2", "OP:D:DEPOT:1", now.plusSeconds(5), 0, 2);
    SpawnTicket higherPriority = ticket("R3", "OP:D:DEPOT:1", now.plusSeconds(5), 8, 3);
    SpawnTicket otherDepot = ticket("R4", "OP:D:DEPOT:2", now.plusSeconds(5), 0, 4);

    DepotDispatchCoordinator.DispatchBatch batch =
        scheduler.coordinate(List.of(slow, lowerPriority, higherPriority, otherDepot), now);

    assertEquals(
        List.of("R3", "R4"), batch.ready().stream().map(t -> t.service().routeCode()).toList());
    assertEquals(2, batch.deferred().size());
    assertEquals(
        2,
        batch.ready().stream()
            .map(ticket -> ticket.service().depotNodeId())
            .collect(Collectors.toSet())
            .size());
  }

  @Test
  void occupancyFailureBacksOffSameDepotWithoutIncrementingAttempts() {
    Instant now = Instant.parse("2026-02-01T00:00:00Z");
    DepotSpawnScheduler scheduler = new DepotSpawnScheduler(Duration.ofSeconds(5));
    SpawnTicket first = ticket("R1", "OP:D:DEPOT:1", now, 0, 1);
    SpawnTicket second = ticket("R2", "OP:D:DEPOT:1", now.plusSeconds(1), 0, 2);

    scheduler.recordOccupancyFailure(first, now);
    DepotDispatchCoordinator.DispatchBatch batch =
        scheduler.coordinate(List.of(second), now.plusSeconds(1));

    assertTrue(batch.ready().isEmpty());
    assertEquals(1, batch.deferred().size());
    assertEquals(0, batch.deferred().get(0).attempts());
    assertEquals(now.plusSeconds(5), batch.deferred().get(0).notBefore());
  }

  @Test
  void coordinateUsesMaterializedSelectedDepotAsDispatchKey() {
    Instant now = Instant.parse("2026-02-01T00:00:00Z");
    DepotSpawnScheduler scheduler = new DepotSpawnScheduler(Duration.ofSeconds(5));
    SpawnTicket first =
        ticket("R1", "DYNAMIC:OP:D:POOL", now, 0, 1).withSelectedDepot("OP:D:DEPOT:1");
    SpawnTicket second =
        ticket("R2", "DYNAMIC:OP:D:POOL", now, 0, 2).withSelectedDepot("OP:D:DEPOT:1");

    DepotDispatchCoordinator.DispatchBatch batch =
        scheduler.coordinate(List.of(first, second), now);

    assertEquals(1, batch.ready().size());
    assertEquals(1, batch.deferred().size());
    assertEquals(Optional.of("OP:D:DEPOT:1"), batch.ready().get(0).selectedDepotNodeId());
    assertEquals(Optional.of("depot-backoff:op:d:depot:1"), batch.deferred().get(0).lastError());
  }

  @Test
  void sharedDepotRotatesLineAfterPreviousLineWasServed() {
    Instant now = Instant.parse("2026-02-01T00:00:00Z");
    DepotSpawnScheduler scheduler = new DepotSpawnScheduler(Duration.ofSeconds(5));
    String depot = "OP:D:DEPOT:1";
    UUID lineOne = UUID.fromString("00000000-0000-0000-0000-000000000101");
    UUID lineTwo = UUID.fromString("00000000-0000-0000-0000-000000000102");

    SpawnTicket firstLine = ticket("R1", "L1", lineOne, depot, now, 0, 1);
    SpawnTicket secondLine = ticket("R2", "L2", lineTwo, depot, now, 0, 2);
    DepotDispatchCoordinator.DispatchBatch firstBatch =
        scheduler.coordinate(List.of(firstLine, secondLine), now);

    assertEquals("L1", firstBatch.ready().get(0).service().lineCode());

    SpawnTicket firstLineNext = ticket("R1B", "L1", lineOne, depot, now.plusSeconds(10), 0, 3);
    SpawnTicket secondLineNext = ticket("R2B", "L2", lineTwo, depot, now.plusSeconds(10), 0, 4);
    DepotDispatchCoordinator.DispatchBatch secondBatch =
        scheduler.coordinate(List.of(firstLineNext, secondLineNext), now.plusSeconds(10));

    assertEquals(1, secondBatch.ready().size());
    assertEquals("L2", secondBatch.ready().get(0).service().lineCode());
  }

  @Test
  void backoffUntilExpiresAfterOccupancyFailureWindow() {
    Instant now = Instant.parse("2026-02-01T00:00:00Z");
    DepotSpawnScheduler scheduler = new DepotSpawnScheduler(Duration.ofSeconds(5));
    SpawnTicket ticket = ticket("R1", "OP:D:DEPOT:1", now, 0, 1);

    scheduler.recordOccupancyFailure(ticket, now);

    assertEquals(Optional.of(now.plusSeconds(5)), scheduler.backoffUntil("OP:D:DEPOT:1", now));
    assertTrue(scheduler.backoffUntil("OP:D:DEPOT:1", now.plusSeconds(6)).isEmpty());
  }

  private static SpawnTicket ticket(
      String routeCode, String depotNodeId, Instant dueAt, int priority, long sequenceNumber) {
    UUID lineId = UUID.fromString("00000000-0000-0000-0000-000000000003");
    return ticket(routeCode, "L1", lineId, depotNodeId, dueAt, priority, sequenceNumber);
  }

  private static SpawnTicket ticket(
      String routeCode,
      String lineCode,
      UUID lineId,
      String depotNodeId,
      Instant dueAt,
      int priority,
      long sequenceNumber) {
    UUID companyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID operatorId = UUID.fromString("00000000-0000-0000-0000-000000000002");
    UUID routeId = UUID.nameUUIDFromBytes(routeCode.getBytes(StandardCharsets.UTF_8));
    SpawnService service =
        new SpawnService(
            new SpawnServiceKey(routeId),
            companyId,
            "C1",
            operatorId,
            "OP",
            lineId,
            lineCode,
            routeId,
            routeCode,
            Duration.ofMinutes(5),
            depotNodeId);
    return new SpawnTicket(
        UUID.randomUUID(),
        service,
        dueAt,
        dueAt,
        0,
        sequenceNumber,
        Optional.empty(),
        Optional.empty(),
        Optional.of(routeCode),
        TripSource.SCHEDULED,
        priority);
  }
}
