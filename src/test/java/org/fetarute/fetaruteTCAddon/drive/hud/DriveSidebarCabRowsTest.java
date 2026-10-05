package org.fetarute.fetaruteTCAddon.drive.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.inventory.ItemStack;
import org.fetarute.fetaruteTCAddon.drive.DriveConfig;
import org.fetarute.fetaruteTCAddon.drive.cab.CabConfig;
import org.fetarute.fetaruteTCAddon.drive.cab.CabFault;
import org.fetarute.fetaruteTCAddon.drive.cab.CabSystems;
import org.fetarute.fetaruteTCAddon.drive.cab.CabTick;
import org.fetarute.fetaruteTCAddon.drive.cab.CabVehicle;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveMode;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveParams;
import org.fetarute.fetaruteTCAddon.drive.dynamics.Notch;
import org.fetarute.fetaruteTCAddon.drive.seat.SeatBinding;
import org.fetarute.fetaruteTCAddon.drive.session.DriveSession;
import org.fetarute.fetaruteTCAddon.drive.setup.PowerSupply;
import org.fetarute.fetaruteTCAddon.drive.setup.SetupTimings;
import org.fetarute.fetaruteTCAddon.drive.setup.TrainSetup;
import org.junit.jupiter.api.Test;

/** 侧边栏里机车的制动管读数、再生标记、故障与门旁路。 */
class DriveSidebarCabRowsTest {

  private static final CabConfig CONFIG = CabConfig.defaults();

  private static DriveSession session(CabSystems cab) {
    MemoryConfiguration section = new MemoryConfiguration();
    section.set("level", "simulation");
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
        new TrainSetup(PowerSupply.PTG5, SetupTimings.defaults()),
        cab);
  }

  private static DriveSidebarRows.Row row(List<DriveSidebarRows.Row> rows, String label) {
    return rows.stream()
        .filter(row -> ("drive.sidebar.label." + label).equals(row.labelKey()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("没有 " + label));
  }

  @Test
  void locomotivesShowTheBrakePipeBetweenTheReservoirAndTheCylinder() {
    CabSystems cab =
        CabSystems.hotHandover(CONFIG, new CabVehicle(DriveMode.LOCO, false, 6, 7.0), 0);

    DriveSidebarRows.Row air = row(DriveSidebarRows.build(session(cab), 0), "air");

    assertEquals("drive.sidebar.value.air-loco.ok", air.valueKey());
    assertEquals("900", air.values().get("mr"));
    assertEquals("500", air.values().get("bp"));
    assertEquals("0", air.values().get("bc"));
  }

  @Test
  void theAirRowIsMarkedWhileTheElectricBrakeRegenerates() {
    CabSystems cab = CabSystems.hotHandover(CONFIG, new CabVehicle(DriveMode.MU, true, 6, 11.0), 0);
    cab.tick(1, 0.05, new CabTick(true, true, 1.0, false, false, 20.0, false, true, true));

    DriveSidebarRows.Row air = row(DriveSidebarRows.build(session(cab), 1), "air");

    assertEquals("drive.sidebar.value.air-regen.ok", air.valueKey());
  }

  @Test
  void eachFaultGetsARowAndTheDoorBypassAWarning() {
    CabSystems cab = CabSystems.simulation(CONFIG, false, 900, false, 0);
    cab.faults().inject(CabFault.BREAKER_TRIP, 0);
    cab.faults().inject(CabFault.BRAKE_LEAK, 0);
    cab.faults().toggleDoorBypass();

    List<DriveSidebarRows.Row> rows = DriveSidebarRows.build(session(cab), 0);

    List<String> faultValues =
        rows.stream()
            .filter(row -> row.labelKey().equals("drive.sidebar.label.fault"))
            .map(DriveSidebarRows.Row::valueKey)
            .toList();
    assertEquals(
        List.of(
            "drive.sidebar.value.fault.breaker-trip.tripped",
            "drive.sidebar.value.fault.brake-leak"),
        faultValues);
    assertEquals("drive.sidebar.value.door-bypass", row(rows, "door-bypass").valueKey());
    assertTrue(rows.size() >= 6);
  }
}
