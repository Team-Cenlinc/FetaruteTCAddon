package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.company.model.StationSidingPool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SidingManagerTest {

  private SidingManager manager;
  private Station testStation;
  private StationSidingPool pool1;

  @BeforeEach
  void setUp() {
    manager = new SidingManager();
    pool1 =
        new StationSidingPool(
            "pool1",
            List.of("node1", "node2"),
            StationSidingPool.SelectionPolicy.FIRST_AVAILABLE,
            2,
            StationSidingPool.FallbackPolicy.STAY_AT_PLATFORM);
    // Create dummy station
    testStation =
        new Station(
            UUID.randomUUID(),
            "TEST",
            UUID.randomUUID(),
            Optional.empty(),
            "Test Station",
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            List.of(pool1),
            java.util.Map.of(),
            java.time.Instant.now(),
            java.time.Instant.now());
  }

  @Test
  void testReserveSlot() {
    Optional<String> result = manager.reserveSlot(testStation, "train1");
    assertTrue(result.isPresent());
    assertEquals("node1", result.get()); // First available

    Optional<String> result2 = manager.reserveSlot(testStation, "train2");
    assertTrue(result2.isPresent());
    assertEquals("node2", result2.get());

    Optional<String> result3 = manager.reserveSlot(testStation, "train3");
    assertFalse(result3.isPresent()); // Full
  }

  @Test
  void testReleaseSlot() {
    manager.reserveSlot(testStation, "train1");
    manager.releaseSlot("train1");

    Optional<String> result = manager.reserveSlot(testStation, "train2");
    assertTrue(result.isPresent());
    assertEquals("node1", result.get()); // Should reuse node1
  }
}
