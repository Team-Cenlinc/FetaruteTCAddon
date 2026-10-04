package org.fetarute.fetaruteTCAddon.drive.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bukkit.Material;
import org.fetarute.fetaruteTCAddon.drive.cab.CabConfig;
import org.fetarute.fetaruteTCAddon.drive.cab.CabFault;
import org.fetarute.fetaruteTCAddon.drive.cab.CabFaults;
import org.fetarute.fetaruteTCAddon.drive.cab.CabSystems;
import org.fetarute.fetaruteTCAddon.drive.cab.CabVehicle;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveMode;
import org.fetarute.fetaruteTCAddon.drive.setup.PowerSupply;
import org.junit.jupiter.api.Test;

/** 驾驶台第三排的表计、故障指示与门旁路按钮。 */
class CabPanelTest {

  private static final CabConfig CONFIG = CabConfig.defaults();
  private static final CabVehicle EMU = new CabVehicle(DriveMode.MU, true, 6, 11.0);
  private static final CabVehicle LOCO = new CabVehicle(DriveMode.LOCO, true, 6, 11.0);

  @Test
  void theMenuHasThreeRowsWithTheGaugesInTheThirdAndTheReservedSlotsLeftEmpty() {
    assertEquals(27, MenuLayout.SIZE);
    assertEquals(Optional.of(MenuAction.DOOR_BYPASS), MenuLayout.actionAt(16));
    assertEquals(Optional.of(MenuIndicator.MAIN_RESERVOIR), MenuLayout.indicatorAt(20));
    assertEquals(Optional.of(MenuIndicator.BRAKE_CYLINDER), MenuLayout.indicatorAt(21));
    assertEquals(Optional.of(MenuIndicator.BRAKE_PIPE), MenuLayout.indicatorAt(22));
    assertEquals(Optional.of(MenuIndicator.FAULTS), MenuLayout.indicatorAt(23));
    assertEquals(Optional.of(MenuAction.DRIVING_MODE), MenuLayout.actionAt(7));
    assertEquals(Optional.of(MenuAction.END_DRIVING), MenuLayout.actionAt(8));
    assertEquals(Optional.of(MenuAction.TASK_CARD), MenuLayout.actionAt(18));
    for (int slot : List.of(7, 8, 18, 26)) {
      assertTrue(MenuLayout.indicatorAt(slot).isEmpty(), "槽位 " + slot + " 不放表计");
    }
    for (int slot : MenuLayout.dividers()) {
      assertTrue(MenuLayout.indicatorAt(slot).isEmpty(), "玻璃板盖住了表计: " + slot);
    }
    for (MenuIndicator indicator : MenuIndicator.values()) {
      int slot = MenuLayout.indicatorSlot(indicator);
      assertTrue(MenuLayout.actionAt(slot).isEmpty(), indicator + " 与按钮重叠");
    }
  }

  @Test
  void faultyEquipmentUsesTheFaultTexture() {
    assertEquals(
        "panel/breaker_fault",
        MenuLayout.modelKey(MenuAction.BREAKER, true, true, PowerSupply.PTG5));
    assertEquals(
        "panel/compressor_fault",
        MenuLayout.modelKey(MenuAction.COMPRESSOR, false, true, PowerSupply.PTG5));
    assertEquals(
        "panel/breaker_on", MenuLayout.modelKey(MenuAction.BREAKER, true, false, PowerSupply.PTG5));
    assertEquals(
        "panel/door_bypass_on",
        MenuLayout.modelKey(MenuAction.DOOR_BYPASS, true, PowerSupply.PTG5));
  }

  @Test
  void theDoorBypassButtonIsOrangeWhileBypassed() {
    ButtonView on = new ButtonView(MenuAction.DOOR_BYPASS, true, false, PowerSupply.PTG5, -1, true);
    ButtonView off =
        new ButtonView(MenuAction.DOOR_BYPASS, false, false, PowerSupply.PTG5, -1, true);
    ButtonView tripped =
        new ButtonView(
            MenuAction.BREAKER, false, false, PowerSupply.PTG5, -1, true, null, Map.of(), true);

    assertEquals(Material.ORANGE_DYE, DriveMenuItems.materialOf(on));
    assertEquals(Material.GRAY_DYE, DriveMenuItems.materialOf(off));
    assertEquals("drive.menu.item.door-bypass-on", DriveMenuItems.nameKey(on));
    assertEquals("drive.menu.hint.door-bypass", DriveMenuItems.hintKey(off));
    assertEquals(Material.RED_DYE, DriveMenuItems.materialOf(tripped));
    assertEquals("drive.menu.item.breaker-fault", DriveMenuItems.nameKey(tripped));
  }

  @Test
  void theDurabilityBarIsTheNeedleAndAlwaysDrawn() {
    GaugeView full = gauge(900, 900);
    GaugeView half = gauge(450, 900);
    GaugeView empty = gauge(0, 900);

    assertEquals(1, full.damage(), "满量程损耗 1：耐久条满格且仍然画出来");
    assertEquals(GaugeView.MAX_DAMAGE, empty.damage(), "读数 0 时耐久条空");
    assertEquals(
        GaugeView.MAX_DAMAGE - Math.round(0.5 * (GaugeView.MAX_DAMAGE - 1)), half.damage());
    assertEquals(1, gauge(2000, 900).damage(), "超量程按满量程");
    assertEquals(600.0, GaugeView.rangeFor(525));
    assertEquals(100.0, GaugeView.rangeFor(0));
  }

  private static GaugeView gauge(double value, double range) {
    return new GaugeView(
        MenuIndicator.MAIN_RESERVOIR, value, range, GaugeView.Band.NORMAL, "k", Map.of());
  }

  @Test
  void gaugeBandsFollowTheSameThresholdsAsTheSystems() {
    CabSystems low = CabSystems.simulation(CONFIG, EMU, 500, false, 0);
    CabSystems warn = CabSystems.simulation(CONFIG, EMU, 700, false, 0);
    CabSystems ok = CabSystems.simulation(CONFIG, EMU, 900, false, 0);

    assertEquals(GaugeView.Band.ALARM, CabGauges.of(MenuIndicator.MAIN_RESERVOIR, low).band());
    assertEquals(GaugeView.Band.WARNING, CabGauges.of(MenuIndicator.MAIN_RESERVOIR, warn).band());
    GaugeView mr = CabGauges.of(MenuIndicator.MAIN_RESERVOIR, ok);
    assertEquals(GaugeView.Band.NORMAL, mr.band());
    assertEquals("drive.menu.gauge.main-reservoir.normal", mr.nameKey());
    assertEquals("panel/gauge_mr_on", mr.modelKey());
    assertEquals(900.0, mr.rangeKpa());

    ok.faults().inject(CabFault.BRAKE_LEAK, 0);
    ok.tick(1, 0.05, true, 0.0, true, true);
    GaugeView bc = CabGauges.of(MenuIndicator.BRAKE_CYLINDER, ok);
    assertEquals(GaugeView.Band.ALARM, bc.band(), "漏泄时制动缸表报警");
    assertEquals("panel/gauge_bc_fault", bc.modelKey());
    assertEquals(600.0, bc.rangeKpa());
  }

  @Test
  void onlyLocomotivesHaveABrakePipeGauge() {
    assertNull(
        CabGauges.of(MenuIndicator.BRAKE_PIPE, CabSystems.simulation(CONFIG, EMU, 900, false, 0)));
    assertNull(CabGauges.of(MenuIndicator.MAIN_RESERVOIR, CabSystems.disabled()));

    GaugeView cold =
        CabGauges.of(MenuIndicator.BRAKE_PIPE, CabSystems.simulation(CONFIG, LOCO, 900, true, 0));
    assertEquals(GaugeView.Band.ALARM, cold.band(), "未充风的制动管低于自动紧急制动压力");
    GaugeView charged =
        CabGauges.of(MenuIndicator.BRAKE_PIPE, CabSystems.hotHandover(CONFIG, LOCO, 0));
    assertEquals(GaugeView.Band.NORMAL, charged.band());
    assertEquals(500, charged.roundedKpa());
    assertEquals("panel/gauge_bp_on", charged.modelKey());
  }

  @Test
  void theFaultPanelListsWhatToDoForEachFault() {
    CabFaults faults = new CabFaults(CONFIG.faults(), true);
    FaultPanelView none = FaultPanelView.of(faults);
    assertEquals("drive.menu.faults.none", none.nameKey());
    assertEquals("panel/fault_off", none.modelKey());
    assertTrue(none.lineKeys().isEmpty());

    faults.inject(CabFault.BREAKER_TRIP, 0);
    faults.inject(CabFault.DOOR, 0);
    faults.clickBreaker(0, 60);
    faults.toggleDoorBypass();
    FaultPanelView view = FaultPanelView.of(faults);

    assertEquals("drive.menu.faults.active", view.nameKey());
    assertEquals("panel/fault_on", view.modelKey());
    assertEquals(
        List.of(
            "drive.menu.faults.breaker-trip-opened",
            "drive.menu.faults.door",
            "drive.menu.faults.door-bypass"),
        view.lineKeys());

    faults.clearAll();
    assertEquals("drive.menu.faults.bypass-only", FaultPanelView.of(faults).nameKey());
  }
}
