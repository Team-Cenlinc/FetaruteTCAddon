package org.fetarute.fetaruteTCAddon.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.LineServiceType;
import org.fetarute.fetaruteTCAddon.company.model.LineStatus;
import org.fetarute.fetaruteTCAddon.company.repository.LineRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.model.ServiceTrip;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.model.TripSource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnDepot;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnServiceKey;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnTicket;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.junit.jupiter.api.Test;

class SpawnScheduleDiagnosticsTest {

  @Test
  void serviceMatchesDepotUsesLineDepotPoolBeforeDefaultDepot() {
    UUID lineId = UUID.randomUUID();
    StorageProvider provider =
        providerWithLine(
            lineId,
            Map.of(
                "spawn_depots",
                List.of(Map.of("nodeId", "OP:D:YARD:1", "weight", 2), "OP:D:YARD:2")));
    SpawnService service = service(lineId, "OP:D:DEFAULT:1");

    assertTrue(SpawnScheduleDiagnostics.serviceMatchesDepot(provider, service, "OP:D:YARD:2"));
    assertFalse(SpawnScheduleDiagnostics.serviceMatchesDepot(provider, service, "OP:D:DEFAULT:1"));
  }

  @Test
  void ticketMatchesDepotUsesSelectedDepotWhenPresent() {
    UUID lineId = UUID.randomUUID();
    StorageProvider provider =
        providerWithLine(lineId, Map.of("spawn_depots", List.of("OP:D:YARD:1")));
    SpawnTicket ticket =
        ticket(
            service(lineId, "OP:D:YARD:1"),
            Instant.parse("2026-01-19T00:00:00Z"),
            Optional.of("OP:D:YARD:2"),
            Optional.empty());

    assertTrue(SpawnScheduleDiagnostics.ticketMatchesDepot(provider, ticket, "OP:D:YARD:2"));
    assertFalse(SpawnScheduleDiagnostics.ticketMatchesDepot(provider, ticket, "OP:D:YARD:1"));
  }

  @Test
  void dynamicDepotSpecMatchesConcreteDepotNode() {
    ServiceTrip trip =
        new ServiceTrip(
            "trip-1",
            TripSource.SCHEDULED,
            UUID.randomUUID(),
            "C1",
            UUID.randomUUID(),
            "OP",
            UUID.randomUUID(),
            "L1",
            UUID.randomUUID(),
            "R1",
            Optional.empty(),
            Instant.parse("2026-01-19T00:00:00Z"),
            List.of(),
            0,
            List.of(new SpawnDepot("DYNAMIC:OP:D:YARD:[1:3]", 1)),
            Optional.empty(),
            Optional.empty());

    assertTrue(SpawnScheduleDiagnostics.tripMatchesDepot(trip, "OP:D:YARD:2"));
    assertFalse(SpawnScheduleDiagnostics.tripMatchesDepot(trip, "OP:D:YARD:4"));
  }

  @Test
  void ticketReasonExplainsErrorBackoffAndReadyStates() {
    Instant now = Instant.parse("2026-01-19T00:00:00Z");
    SpawnService service = service(UUID.randomUUID(), "OP:D:YARD:1");
    SpawnTicket failed = ticket(service, now, Optional.empty(), Optional.of("gate-blocked:STOP"));
    SpawnTicket delayed =
        new SpawnTicket(
            UUID.randomUUID(),
            service,
            now.minusSeconds(30),
            now.plusSeconds(45),
            0,
            2L,
            Optional.empty(),
            Optional.empty());
    SpawnTicket ready = ticket(service, now.minusSeconds(5), Optional.empty(), Optional.empty());

    assertEquals("gate-blocked:STOP", SpawnScheduleDiagnostics.ticketReason(failed, now));
    assertEquals("not-before:45s", SpawnScheduleDiagnostics.ticketReason(delayed, now));
    assertEquals("ready", SpawnScheduleDiagnostics.ticketReason(ready, now));
  }

  private static StorageProvider providerWithLine(UUID lineId, Map<String, Object> metadata) {
    StorageProvider provider = mock(StorageProvider.class);
    LineRepository lines = mock(LineRepository.class);
    when(provider.lines()).thenReturn(lines);
    when(lines.findById(lineId)).thenReturn(Optional.of(line(lineId, metadata)));
    return provider;
  }

  private static Line line(UUID lineId, Map<String, Object> metadata) {
    Instant now = Instant.parse("2026-01-19T00:00:00Z");
    return new Line(
        lineId,
        "L1",
        UUID.randomUUID(),
        "Line",
        Optional.empty(),
        LineServiceType.METRO,
        Optional.empty(),
        LineStatus.ACTIVE,
        Optional.of(120),
        metadata,
        now,
        now);
  }

  private static SpawnService service(UUID lineId, String depotNodeId) {
    UUID companyId = UUID.randomUUID();
    UUID operatorId = UUID.randomUUID();
    UUID routeId = UUID.randomUUID();
    return new SpawnService(
        new SpawnServiceKey(routeId),
        companyId,
        "C1",
        operatorId,
        "OP",
        lineId,
        "L1",
        routeId,
        "R1",
        Duration.ofSeconds(120),
        depotNodeId);
  }

  private static SpawnTicket ticket(
      SpawnService service, Instant dueAt, Optional<String> selectedDepot, Optional<String> error) {
    return new SpawnTicket(UUID.randomUUID(), service, dueAt, dueAt, 0, 1L, selectedDepot, error);
  }
}
