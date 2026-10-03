package org.fetarute.fetaruteTCAddon.drive.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.inventory.ItemStack;
import org.fetarute.fetaruteTCAddon.drive.DriveConfig;
import org.fetarute.fetaruteTCAddon.drive.cab.CabConfig;
import org.fetarute.fetaruteTCAddon.drive.cab.CabSystems;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveMode;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveParams;
import org.fetarute.fetaruteTCAddon.drive.dynamics.Notch;
import org.fetarute.fetaruteTCAddon.drive.seat.SeatBinding;
import org.fetarute.fetaruteTCAddon.drive.session.DriveSession;
import org.fetarute.fetaruteTCAddon.drive.setup.PowerSupply;
import org.fetarute.fetaruteTCAddon.drive.setup.SetupTimings;
import org.fetarute.fetaruteTCAddon.drive.setup.TrainSetup;
import org.junit.jupiter.api.Test;

class DriveSidebarRowsTest {

  private static DriveSession session(CabSystems cab) {
    MemoryConfiguration section = new MemoryConfiguration();
    section.set("level", cab.enabled() ? "simulation" : "standard");
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
  void standardShowsOnlyTheDoorsBecauseOneOffStepsLiveInTheActionBar() {
    List<DriveSidebarRows.Row> rows = DriveSidebarRows.build(session(CabSystems.disabled()), 0);

    assertEquals(1, rows.size());
    assertEquals("drive.sidebar.value.doors.closed", row(rows, "doors").valueKey());
  }

  @Test
  void simulationAddsAirAndVigilance() {
    CabSystems cab = CabSystems.simulation(CabConfig.defaults(), true, 600, false, 0);
    List<DriveSidebarRows.Row> rows = DriveSidebarRows.build(session(cab), 0);

    assertEquals(3, rows.size());
    DriveSidebarRows.Row air = row(rows, "air");
    assertEquals("drive.sidebar.value.air.warn", air.valueKey());
    assertEquals(Map.of("mr", "600", "bc", "0", "pump", ""), air.values());
    assertEquals("drive.sidebar.value.vigilance.stopped", row(rows, "vigilance").valueKey());
  }

  @Test
  void aRunningCompressorIsMarkedOnTheAirRow() {
    CabSystems cab = CabSystems.simulation(CabConfig.defaults(), false, 300, false, 0);
    cab.tick(1, 0.05, true, 0, true, true);

    List<DriveSidebarRows.Row> rows = DriveSidebarRows.build(session(cab), 1);

    assertEquals("↑", row(rows, "air").values().get("pump"));
    assertTrue(row(rows, "air").valueKey().endsWith(".low"));
  }
}
