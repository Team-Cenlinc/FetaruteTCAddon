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

  /** 车掌拉下紧急停车（自动运行的车）：按驾驶员控制处理，调度不替它起步，解除后照常。 */
  @Test
  void anEmergencyHoldStopsAutomaticDeparture() {
    GuardLink guard = guard();
    assertFalse(registry.isDriverControlled(properties));
    guard.latchEmergency(1000L);
    assertTrue(registry.isDriverControlled(properties));
    assertTrue(registry.isDriverControlledName("T-1"), "健康层按车名也认");
    guard.releaseEmergency();
    assertFalse(registry.isDriverControlled(properties));
    assertFalse(registry.isDriverControlledName("T-1"));
  }

  /** 只有车掌的列车停在终点站待命：扣着等车掌换端，派车放行时由车掌这边取走调头标记。 */
  @Test
  void aGuardOnlyTrainHoldsForTheCabChange() {
    GuardLink guard = guard();
    guard.setCabHold(true);
    guard.setTurnbackPending(true);
    assertTrue(registry.isDriverControlled(properties));
    assertTrue(registry.isDriverControlledName("T-1"));
    assertTrue(registry.awaitingTurnback("T-1"));
    assertTrue(registry.takeTurnback(properties));
    assertFalse(registry.takeTurnback(properties), "只取一次");
    assertFalse(registry.awaitingTurnback("T-1"));
    guard.setCabHold(false);
    assertFalse(registry.isDriverControlled(properties));
  }

  /** 有驾驶员时调头标记归驾驶员：车掌这边的标记不替他取。 */
  @Test
  void theDriverOwnsTheTurnbackWhenAboard() {
    GuardLink guard = guard();
    guard.setTurnbackPending(true);
    DriverLink driver = new DriverLink(UUID.randomUUID(), "T-1", null, () -> 0.0, () -> 0L);
    registry.bind(properties, driver);
    driver.enterAto();
    assertFalse(registry.awaitingTurnback("T-1"));
    assertFalse(registry.takeTurnback(properties));
    driver.setTurnbackPending(true);
    assertTrue(registry.takeTurnback(properties));
    assertTrue(guard.turnbackPending(), "车掌的标记没被取走");
  }

  /** 列车改名（终点待命复用）：按新名字找得到车掌；别的列车后来用了旧名字，不能把车掌挂过去。 */
  @Test
  void aRenamedTrainKeepsItsGuardAndTheOldNameDoesNot() {
    GuardLink guard = guard();
    when(properties.getTrainName()).thenReturn("T-9");
    assertEquals("T-9", guard.currentTrainName());
    assertSame(guard, registry.guardOfName("T-9").orElseThrow());
    TrainProperties other = mock(TrainProperties.class);
    when(other.getTrainName()).thenReturn("T-1");
    assertTrue(registry.guardOf(other).isEmpty());
    assertTrue(registry.guardOfName("T-1").isEmpty());
    assertSame(properties, guard.properties(), "车掌仍挂在原来的列车上");
    guard.setCabHold(true);
    assertFalse(registry.isDriverControlledName("T-1"));
    assertTrue(registry.isDriverControlledName("T-9"));
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
