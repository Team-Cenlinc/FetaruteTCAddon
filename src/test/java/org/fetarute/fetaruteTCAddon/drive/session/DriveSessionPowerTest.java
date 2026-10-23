package org.fetarute.fetaruteTCAddon.drive.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopAlignment;
import org.fetarute.fetaruteTCAddon.drive.DriveConfig;
import org.fetarute.fetaruteTCAddon.drive.cab.CabSystems;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverLink;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveMode;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveParams;
import org.fetarute.fetaruteTCAddon.drive.dynamics.Notch;
import org.fetarute.fetaruteTCAddon.drive.seat.SeatBinding;
import org.fetarute.fetaruteTCAddon.drive.setup.PowerSupply;
import org.fetarute.fetaruteTCAddon.drive.setup.SetupTimings;
import org.fetarute.fetaruteTCAddon.drive.setup.TrainSetup;
import org.junit.jupiter.api.Test;

/** 多模车的驾驶会话：交路要求的受电方式、没换时没电、换到超级电容时补上储能。 */
class DriveSessionPowerTest {

  private static DriveSession session(String level, PowerSupply supply) {
    MemoryConfiguration section = new MemoryConfiguration();
    section.set("level", level);
    List<ItemStack> hotbar = new ArrayList<>();
    for (int i = 0; i < Notch.SLOT_COUNT; i++) {
      hotbar.add(mock(ItemStack.class));
    }
    return new DriveSession(
        UUID.randomUUID(),
        "Steve",
        new SeatBinding("T1", 0, 0),
        new DriveParams(DriveMode.MU, 1.0, 1.0, 22.0, 0.5),
        DriveConfig.from(section, message -> {}),
        hotbar,
        TrainSetup.alwaysReady(supply, SetupTimings.defaults()),
        CabSystems.disabled());
  }

  @Test
  void simulationDriversLoseLinePowerUntilTheySwitch() {
    DriveSession session = session("simulation", PowerSupply.PTG5);

    session.setSectionPower(PowerSupply.PTG6);

    assertEquals(Optional.of(PowerSupply.PTG6), session.pendingPower());
    assertTrue(session.noLinePower());
    assertTrue(session.tractionBlocked());
    session.setup().switchSupply(PowerSupply.PTG6, false, 0);
    assertTrue(session.pendingPower().isEmpty());
    assertFalse(session.noLinePower());
  }

  @Test
  void selfPoweredSuppliesRunAnywhere() {
    DriveSession session = session("simulation", PowerSupply.DIESEL);

    session.setSectionPower(PowerSupply.PTG6);

    assertEquals(Optional.of(PowerSupply.PTG6), session.pendingPower(), "仍提示该换了");
    assertFalse(session.noLinePower(), "内燃不靠接触网");
  }

  @Test
  void standardDriversAreNeverCutOff() {
    DriveSession session = session("standard", PowerSupply.PTG5);

    session.setSectionPower(PowerSupply.PTG6);

    assertFalse(session.noLinePower(), "standard 级由调度直接换");
  }

  /** 车上有车掌时车门由车掌开：车掌开着门停站，超级电容照样充电。 */
  @Test
  void guardOpenedDoorsChargeTheSupercap() {
    DriveSession session = session("simulation", PowerSupply.SUPERCAP);
    DriverLink link = new DriverLink(UUID.randomUUID(), "T1", null, () -> 0.0, () -> 0L);
    session.attachDriverLink(link);
    DriverStationStop stop =
        new DriverStationStop(
            NodeId.of("OP:S:STA:1"),
            "测试站",
            UUID.randomUUID(),
            new Vector(),
            StopAlignment.Reference.CENTER,
            BlockFace.EAST,
            false,
            true);
    link.beginStationStop(stop);
    stop.reportDoors(true, true, false);

    assertFalse(session.chargesSupercap(), "没有车掌：看驾驶员自己的车门");
    link.setGuardAboard(true);
    assertTrue(session.chargesSupercap(), "车掌开着门");
    assertTrue(session.trainDoorsOpen(), "车门联锁、教程同样看车掌开的门");
    stop.reportDoors(false, false, false);
    assertFalse(session.chargesSupercap(), "车掌关了门");
  }

  /** 驾驶员迟迟不开门、站台接手开关门：这一站停着就充电。 */
  @Test
  void stationOperatedDoorsChargeTheSupercap() {
    DriveSession session = session("simulation", PowerSupply.SUPERCAP);
    double[] odometer = {0.0};
    DriverLink link = new DriverLink(UUID.randomUUID(), "T1", null, () -> odometer[0], () -> 0L);
    session.attachDriverLink(link);
    DriverStationStop stop =
        new DriverStationStop(
            NodeId.of("OP:S:STA:1"),
            "测试站",
            UUID.randomUUID(),
            new Vector(),
            StopAlignment.Reference.CENTER,
            BlockFace.EAST,
            false,
            true);
    link.beginStationStop(stop);
    stop.markDoorsTakenOver();
    stop.end();
    link.stationStop();

    assertTrue(session.chargesSupercap(), "站台开着门停站");
    odometer[0] = 50.0;
    assertFalse(link.stationDoorsTakenOver(), "开出这一站就不算了");
  }

  @Test
  void switchingToSupercapAddsStoredEnergy() {
    DriveSession session = session("standard", PowerSupply.PTG5);
    assertTrue(session.supercap().isEmpty());

    session.setup().switchSupply(PowerSupply.SUPERCAP, false, 0);
    session.powerSupplyChanged();

    assertEquals(1.0, session.supercap().orElseThrow().fraction(), 1e-9, "按满电算");
    session.setup().switchSupply(PowerSupply.PTG5, false, 0);
    assertTrue(session.supercap().isEmpty(), "不用超级电容时没有储能");
  }
}
