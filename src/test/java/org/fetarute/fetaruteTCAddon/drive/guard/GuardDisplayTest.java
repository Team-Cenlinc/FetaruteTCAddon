package org.fetarute.fetaruteTCAddon.drive.guard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop.Phase;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverDoorSide;
import org.fetarute.fetaruteTCAddon.drive.hud.DriveSidebarRows;
import org.junit.jupiter.api.Test;

/** 车掌的动作栏提示与侧边栏：按停站阶段与发车前的几道条件给出要做的事。 */
class GuardDisplayTest {

  private static GuardDisplay.StopState stop(
      Phase phase, boolean exitOpen, boolean confirmed, boolean released) {
    return new GuardDisplay.StopState(
        "PPK",
        phase,
        DriverDoorSide.LEFT,
        false,
        false,
        false,
        200L,
        exitOpen,
        confirmed,
        released);
  }

  private static GuardDisplay.Snapshot snapshot(GuardDisplay.StopState stop, boolean seated) {
    return new GuardDisplay.Snapshot("T-1", Optional.empty(), true, Optional.of(stop), seated, 0);
  }

  private static String prompt(GuardDisplay.StopState stop, boolean seated) {
    return GuardDisplay.prompt(snapshot(stop, seated)).key();
  }

  @Test
  void promptsFollowThePhase() {
    assertEquals(
        "drive.guard.prompt.running",
        GuardDisplay.prompt(
                new GuardDisplay.Snapshot(
                    "T-1", Optional.empty(), false, Optional.empty(), true, 0))
            .key());
    GuardDisplay.Line open =
        GuardDisplay.prompt(snapshot(stop(Phase.OPEN_DOORS, false, false, false), true));
    assertEquals("drive.guard.prompt.open-doors-left", open.key());
    assertEquals(Map.of("seconds", "10"), open.values());
    assertEquals("drive.guard.prompt.dwell", prompt(stop(Phase.DWELL, false, false, false), true));
    assertEquals(
        "drive.guard.prompt.close-doors",
        prompt(stop(Phase.CLOSE_DOORS, false, false, false), true));
  }

  /** 等发车那一步：先回座，再等出站开放、确认，最后按铃。 */
  @Test
  void departureStepsComeInOrder() {
    assertEquals(
        "drive.guard.prompt.return-seat",
        prompt(stop(Phase.WAIT_DEPARTURE, true, false, false), false));
    assertEquals(
        "drive.guard.prompt.exit-closed",
        prompt(stop(Phase.WAIT_DEPARTURE, false, false, false), true));
    assertEquals(
        "drive.guard.prompt.confirm", prompt(stop(Phase.WAIT_DEPARTURE, true, false, false), true));
    assertEquals(
        "drive.guard.prompt.buzzer", prompt(stop(Phase.WAIT_DEPARTURE, false, true, false), true));
    assertEquals(
        "drive.guard.prompt.released", prompt(stop(Phase.WAIT_DEPARTURE, true, true, true), true));
  }

  @Test
  void sidebarShowsTheExitOnlyWhileWaitingToDepart() {
    List<DriveSidebarRows.Row> waiting =
        GuardDisplay.rows(snapshot(stop(Phase.WAIT_DEPARTURE, true, false, false), true));
    assertTrue(
        waiting.stream()
            .anyMatch(row -> row.valueKey().equals("drive.guard.sidebar.value.exit-open")));
    List<DriveSidebarRows.Row> dwell =
        GuardDisplay.rows(snapshot(stop(Phase.DWELL, true, false, false), true));
    assertTrue(dwell.stream().noneMatch(row -> row.labelKey().endsWith(".exit")));
    assertEquals("drive.guard.sidebar.value.crew-ato", dwell.get(1).valueKey());
  }

  @Test
  void secondsRoundUp() {
    assertEquals("1", GuardDisplay.seconds(1L));
    assertEquals("—", GuardDisplay.seconds(-1L));
  }
}
