package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.model.TripSource;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.junit.jupiter.api.Test;

/** 车型裁决出错时：没指定车型的票照旧规则出车，指定了车型的票不出车（改出别的车型就对不上表了）。 */
class TrainCartsDepotSpawnerConsistTest {

  private static final ConsistArbiter FAILING =
      new ConsistArbiter() {
        @Override
        public List<LayoverRegistry.LayoverCandidate> orderReuseCandidates(
            SpawnTicket ticket, List<LayoverRegistry.LayoverCandidate> candidates) {
          return candidates;
        }

        @Override
        public SpawnChoice chooseSpawn(SpawnTicket ticket) {
          throw new IllegalStateException("boom");
        }

        @Override
        public boolean acceptsForRoute(UUID routeId, LayoverRegistry.LayoverCandidate candidate) {
          return true;
        }

        @Override
        public void onDispatched(SpawnTicket ticket, String trainName) {}
      };

  @Test
  void arbiterFailureBlocksOnlyDesignatedTickets() {
    TrainCartsDepotSpawner spawner =
        new TrainCartsDepotSpawner(
            mock(FetaruteTCAddon.class), new SignNodeRegistry(), message -> {});
    spawner.setConsistArbiter(FAILING);

    assertEquals(ConsistArbiter.SpawnChoice.legacy(), spawner.chooseConsist(ticket()));
    assertEquals(
        ConsistArbiter.SpawnChoice.Kind.BLOCKED,
        spawner.chooseConsist(ticket().withConsist(Optional.of("m8"))).kind());
  }

  private static SpawnTicket ticket() {
    UUID routeId = UUID.randomUUID();
    SpawnService service =
        new SpawnService(
            new SpawnServiceKey(routeId),
            UUID.randomUUID(),
            "C",
            UUID.randomUUID(),
            "OP",
            UUID.randomUUID(),
            "L1",
            routeId,
            "CRT",
            Duration.ofSeconds(600),
            "OP:D:DEP:1");
    Instant now = Instant.parse("2026-03-02T08:00:00Z");
    return new SpawnTicket(
        UUID.randomUUID(),
        service,
        now,
        now,
        0,
        1L,
        Optional.empty(),
        Optional.empty(),
        Optional.of(SpawnTicket.TIMETABLE_TRIP_PREFIX + "TT1-D001-CREATE"),
        TripSource.SCHEDULED,
        0);
  }
}
