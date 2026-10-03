package org.fetarute.fetaruteTCAddon.drive.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.OptionalDouble;
import java.util.UUID;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverDoorSide;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverLink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("驾驶调度列车的车站提示")
class DriverStationHintTest {

  private final double[] odometer = {0.0};
  private final long[] clock = {0L};
  private final DriverLink link =
      new DriverLink(UUID.randomUUID(), "T", null, () -> odometer[0], () -> clock[0]);

  private final DriverStationStop stop =
      new DriverStationStop(
          NodeId.of("OP:S:STA:1"), "测试站", UUID.randomUUID(), new Vector(), null, false, true);

  @Test
  @DisplayName("进站前：按调度采样给距离，远了不提示")
  void approachDistance() {
    assertTrue(DriverStationHint.of(link, false).isEmpty());
    link.updateApproach(
        NodeId.of("OP:S:STA:1"), "station", OptionalDouble.of(80.0), Instant.EPOCH, 10.0);
    link.setTargetLabel("测试站");
    odometer[0] = 30.0;
    DriverStationHint.Hint hint = DriverStationHint.of(link, false).orElseThrow();
    assertEquals("drive.hud.station.approach", hint.key());
    assertEquals("60", hint.values().get("distance"));

    link.updateApproach(
        NodeId.of("OP:S:STA:1"),
        "station",
        OptionalDouble.of(900.0),
        Instant.ofEpochSecond(1),
        10.0);
    assertTrue(DriverStationHint.of(link, false).isEmpty());
  }

  @Test
  @DisplayName("进站后停短：提示前移")
  void moveUpWhenShort() {
    link.beginStationStop(stop);
    stop.updateOffset(-6.25);
    link.setTargetLabel("测试站");
    DriverStationHint.Hint hint = DriverStationHint.of(link, true).orElseThrow();
    assertEquals("drive.hud.station.move-up", hint.key());
    assertEquals("6.3", hint.values().get("distance"));
    stop.updateOffset(0.4);
    assertEquals("drive.hud.station.on-mark", DriverStationHint.of(link, true).orElseThrow().key());
  }

  @Test
  @DisplayName("停妥后依次提示开门、停站、关门、等待、发车")
  void stopPhases() {
    link.beginStationStop(stop);
    link.setRequiredDoorSide(DriverDoorSide.LEFT);
    stop.markStopped();
    assertEquals(
        "drive.hud.station.open-doors.left", DriverStationHint.of(link, true).orElseThrow().key());
    stop.setPhase(DriverStationStop.Phase.DWELL);
    stop.setDwellRemainingTicks(41);
    DriverStationHint.Hint dwell = DriverStationHint.of(link, true).orElseThrow();
    assertEquals("drive.hud.station.dwell", dwell.key());
    assertEquals("3", dwell.values().get("seconds"));
    stop.setPhase(DriverStationStop.Phase.CLOSE_DOORS);
    assertEquals(
        "drive.hud.station.close-doors", DriverStationHint.of(link, true).orElseThrow().key());
    stop.setPhase(DriverStationStop.Phase.DEPART);
    assertEquals("drive.hud.station.depart", DriverStationHint.of(link, true).orElseThrow().key());
    stop.end();
    assertTrue(DriverStationHint.of(link, true).isEmpty());
  }

  @Test
  @DisplayName("动作栏只放要动手的提示；侧边栏进站时是停车点、停妥后是停站")
  void actionableAndSidebarKeys() {
    link.updateApproach(
        NodeId.of("OP:S:STA:1"), "station", OptionalDouble.of(40.0), Instant.EPOCH, 10.0);
    DriverStationHint.Hint approach = DriverStationHint.of(link, false).orElseThrow();
    assertFalse(approach.actionable());
    assertFalse(approach.atStation());
    assertEquals("drive.sidebar.label.stop-mark", approach.sidebarLabelKey());
    assertEquals("drive.sidebar.value.stop.approach", approach.sidebarKey());

    link.beginStationStop(stop);
    stop.updateOffset(-8.0);
    assertTrue(DriverStationHint.of(link, true).orElseThrow().actionable(), "停短须前移");

    link.setRequiredDoorSide(DriverDoorSide.RIGHT);
    stop.markStopped();
    DriverStationHint.Hint open = DriverStationHint.of(link, true).orElseThrow();
    assertTrue(open.actionable());
    assertTrue(open.atStation());
    assertEquals("drive.sidebar.label.stop", open.sidebarLabelKey());
    assertEquals("drive.sidebar.value.stop.open-doors.right", open.sidebarKey());

    stop.setPhase(DriverStationStop.Phase.DWELL);
    DriverStationHint.Hint dwell = DriverStationHint.of(link, true).orElseThrow();
    assertFalse(dwell.actionable(), "倒计时只在侧边栏");
    assertTrue(dwell.atStation());
    stop.setPhase(DriverStationStop.Phase.WAIT_DEPARTURE);
    assertFalse(DriverStationHint.of(link, true).orElseThrow().actionable());
    stop.setPhase(DriverStationStop.Phase.DEPART);
    assertTrue(DriverStationHint.of(link, true).orElseThrow().actionable());
  }

  @Test
  @DisplayName("进站前停车（等信号）不提示前移")
  void noMoveUpBeforeEnteringTheStation() {
    link.updateApproach(
        NodeId.of("OP:S:STA:1"), "station", OptionalDouble.of(50.0), Instant.EPOCH, 10.0);
    assertEquals(
        "drive.hud.station.approach", DriverStationHint.of(link, true).orElseThrow().key());
  }
}
