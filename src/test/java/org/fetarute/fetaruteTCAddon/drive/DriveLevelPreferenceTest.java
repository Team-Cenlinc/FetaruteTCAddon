package org.fetarute.fetaruteTCAddon.drive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("玩家自选仿真等级")
class DriveLevelPreferenceTest {

  @Test
  @DisplayName("有节点时用玩家选过的等级，没有选过时用 drive.yml 的")
  void chosenLevelWinsWhenAllowed() {
    assertEquals(
        SimulationLevel.SIMULATION,
        DriveLevelPreference.resolve(
            true, Optional.of(SimulationLevel.SIMULATION), SimulationLevel.STANDARD));
    assertEquals(
        SimulationLevel.STANDARD,
        DriveLevelPreference.resolve(
            true, Optional.of(SimulationLevel.STANDARD), SimulationLevel.SIMULATION));
    assertEquals(
        SimulationLevel.SIMULATION,
        DriveLevelPreference.resolve(true, Optional.empty(), SimulationLevel.SIMULATION));
  }

  @Test
  @DisplayName("没有节点时一律用 drive.yml 的，选过也不算")
  void serverDefaultWithoutPermission() {
    assertEquals(
        SimulationLevel.STANDARD,
        DriveLevelPreference.resolve(
            false, Optional.of(SimulationLevel.SIMULATION), SimulationLevel.STANDARD));
  }

  @Test
  @DisplayName("会话配置只换等级，其余不变")
  void withLevelOnlyChangesTheLevel() {
    DriveConfig defaults = DriveConfig.defaults();
    assertSame(defaults, defaults.withLevel(defaults.level()));
    DriveConfig simulation = defaults.withLevel(SimulationLevel.SIMULATION);
    assertEquals(SimulationLevel.SIMULATION, simulation.level());
    assertEquals(defaults, simulation.withLevel(SimulationLevel.STANDARD));
  }
}
