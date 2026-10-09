package org.fetarute.fetaruteTCAddon.drive.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop.Phase;
import org.fetarute.fetaruteTCAddon.drive.guard.GuardConfig;
import org.fetarute.fetaruteTCAddon.drive.guard.GuardLink;
import org.junit.jupiter.api.Test;

/** 车上有车掌：车门归车掌，站台把停站交给他、在等发车那一步问他；ATO 驾驶员不接停站。 */
class DriverControlRegistryGuardTest {

  private final DriverControlRegistry registry = new DriverControlRegistry();
  private final TrainProperties properties = mock(TrainProperties.class);

  private GuardLink guard() {
    when(properties.getTrainName()).thenReturn("T-1");
    GuardLink guard =
        new GuardLink(UUID.randomUUID(), "T-1", properties, GuardConfig.defaults(), () -> 100L);
    registry.bindGuard(properties, guard);
    return guard;
  }

  private static DriverStationStop stop() {
    return new DriverStationStop(
        NodeId.of("SURC:S:PPK:1"),
        "PPK",
        UUID.randomUUID(),
        new Vector(0, 64, 0),
        null,
        false,
        true);
  }

  @Test
  void theGuardOwnsTheDoors() {
    assertFalse(registry.guardOperatesDoors(properties));
    GuardLink guard = guard();
    assertTrue(registry.guardOperatesDoors(properties));
    registry.unbindGuard(guard);
    assertFalse(registry.guardOperatesDoors(properties));
  }

  /** 人工驾驶的驾驶员不再接管车门；ATO 驾驶员不接站台的停站，车掌接。 */
  @Test
  void stopsGoToTheGuardNotToAnAtoDriver() {
    DriverLink driver = new DriverLink(UUID.randomUUID(), "T-1", null, () -> 0.0, () -> 0L);
    registry.bind(properties, driver);
    when(properties.getTrainName()).thenReturn("T-1");
    driver.setMode(DrivingMode.MANUAL);
    assertTrue(registry.driverOperatesDoors(properties));
    GuardLink guard = guard();
    assertFalse(registry.driverOperatesDoors(properties), "有车掌时车门归车掌");

    driver.enterAto();
    DriverStationStop stop = stop();
    registry.beginStationStop(properties, stop);
    assertEquals(Optional.of(stop), guard.stationStop());
    assertTrue(driver.stationStop().isEmpty(), "ATO 驾驶员不接停站");
  }

  @Test
  void theStationAsksTheGuardBeforeDeparture() {
    GuardLink guard = guard();
    DriverStationStop stop = stop();
    registry.beginStationStop(properties, stop);
    guard.work().orElseThrow().tick(Phase.WAIT_DEPARTURE);
    assertTrue(registry.holdForGuard(properties, true));
    assertTrue(registry.holdForGuard(properties, false));
  }

  /** 列车属性对象被换掉（区块重载）：按车名找回车掌。 */
  @Test
  void theGuardIsFoundAgainByName() {
    GuardLink guard = guard();
    TrainProperties reloaded = mock(TrainProperties.class);
    when(reloaded.getTrainName()).thenReturn("T-1");
    assertSame(guard, registry.guardOf(reloaded).orElseThrow());
    assertSame(reloaded, guard.properties());
  }
}
