package org.fetarute.fetaruteTCAddon.drive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class SimulationLevelTest {

  @Test
  void parsesLevelNamesIgnoringCase() {
    assertEquals(Optional.of(SimulationLevel.STANDARD), SimulationLevel.parse(" Standard "));
    assertEquals(Optional.of(SimulationLevel.SIMULATION), SimulationLevel.parse("SIMULATION"));
  }

  @Test
  void rejectsUnknownOrBlankNamesIncludingTheRemovedArcadeLevel() {
    assertTrue(SimulationLevel.parse(null).isEmpty());
    assertTrue(SimulationLevel.parse("  ").isEmpty());
    assertTrue(SimulationLevel.parse("hard").isEmpty());
    assertTrue(SimulationLevel.parse("arcade").isEmpty());
  }

  @Test
  void standardStartsWithOneButtonAndSimulationSwitchBySwitch() {
    assertEquals(SimulationLevel.SetupMode.ONE_CLICK, SimulationLevel.STANDARD.setupMode());
    assertEquals(SimulationLevel.SetupMode.MANUAL, SimulationLevel.SIMULATION.setupMode());
  }
}
