package org.fetarute.fetaruteTCAddon.drive.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.UUID;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop;
import org.fetarute.fetaruteTCAddon.drive.DriveConfig;
import org.fetarute.fetaruteTCAddon.drive.cab.CabConfig;
import org.fetarute.fetaruteTCAddon.drive.cab.CabSystems;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverDoorSide;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverLink;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverSchedule;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveMode;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveParams;
import org.fetarute.fetaruteTCAddon.drive.dynamics.Notch;
import org.fetarute.fetaruteTCAddon.drive.energy.SuperCapacitor;
import org.fetarute.fetaruteTCAddon.drive.energy.SuperCapacitorConfig;
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

  @Test
  void theStationRowShowsTheNextStationThenTheStopMarkThenTheStopPhase() {
    DriveSession session = session(CabSystems.disabled());
    double[] odometer = {0.0};
    DriverLink link = new DriverLink(UUID.randomUUID(), "T1", null, () -> odometer[0], () -> 0L);
    session.attachDriverLink(link);
    link.setTargetLabel("测试站");

    DriveSidebarRows.Row next = row(DriveSidebarRows.build(session, 0), "next-station");
    assertEquals("drive.sidebar.value.text", next.valueKey());
    assertEquals("测试站", next.values().get("text"));

    link.updateApproach(
        NodeId.of("OP:S:STA:1"), "station", OptionalDouble.of(50.0), Instant.EPOCH, 10.0);
    DriveSidebarRows.Row mark = row(DriveSidebarRows.build(session, 0), "stop-mark");
    assertEquals("drive.sidebar.value.stop.approach", mark.valueKey());
    assertEquals("60", mark.values().get("distance"));

    DriverStationStop stop =
        new DriverStationStop(
            NodeId.of("OP:S:STA:1"), "测试站", UUID.randomUUID(), new Vector(), null, false, true);
    link.beginStationStop(stop);
    link.setRequiredDoorSide(DriverDoorSide.LEFT);
    stop.markStopped();
    assertEquals(
        "drive.sidebar.value.stop.open-doors.left",
        row(DriveSidebarRows.build(session, 0), "stop").valueKey());
  }

  @Test
  void theScheduleRowFollowsTheStationRowWhenTheTrainRunsToATimetable() {
    DriveSession session = session(CabSystems.disabled());
    DriverLink link = new DriverLink(UUID.randomUUID(), "T1", null, () -> 0.0, () -> 0L);
    session.attachDriverLink(link);
    Instant planned = Instant.parse("2026-10-03T08:00:00Z");
    link.setSchedule(new DriverSchedule(false, planned, OptionalLong.of(95L)));

    List<DriveSidebarRows.Row> rows = DriveSidebarRows.build(session, 0);
    DriveSidebarRows.Row schedule = row(rows, "scheduled-arrival");

    assertEquals("drive.sidebar.value.schedule.late", schedule.valueKey());
    assertEquals(
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(planned),
        schedule.values().get("time"));
    assertEquals("1:35", schedule.values().get("deviation"));
    assertEquals(
        "drive.sidebar.label.next-station", rows.get(rows.indexOf(schedule) - 1).labelKey());

    link.setSchedule(new DriverSchedule(true, planned, OptionalLong.of(-20L)));
    assertEquals(
        "drive.sidebar.value.schedule.plain",
        row(DriveSidebarRows.build(session, 0), "scheduled-departure").valueKey());
  }

  @Test
  void supercapRowShowsPercentAndState() {
    SuperCapacitor cap = new SuperCapacitor(SuperCapacitorConfig.defaults(), 0.62);
    DriveSidebarRows.Row normal = DriveSidebarRows.supercapRow(cap);
    assertEquals("drive.sidebar.label.supercap", normal.labelKey());
    assertEquals("drive.sidebar.value.supercap.normal", normal.valueKey());
    assertEquals("62", normal.values().get("percent"));
    cap.reset(0.1);
    assertEquals("drive.sidebar.value.supercap.low", DriveSidebarRows.supercapRow(cap).valueKey());
    cap.charge(0.05);
    assertEquals(
        "drive.sidebar.value.supercap.charging", DriveSidebarRows.supercapRow(cap).valueKey());
    cap.reset(0.0);
    assertEquals(
        "drive.sidebar.value.supercap.depleted", DriveSidebarRows.supercapRow(cap).valueKey());
  }

  @Test
  void theDoorsRowShowsClosingUntilTheAnimationHasFinished() {
    DriveSession session = session(CabSystems.disabled());
    session.markDoorsClosing(40L);

    assertEquals(
        "drive.sidebar.value.doors.closing",
        row(DriveSidebarRows.build(session, 20L), "doors").valueKey());
    assertEquals(
        "drive.sidebar.value.doors.closed",
        row(DriveSidebarRows.build(session, 40L), "doors").valueKey());
  }
}
