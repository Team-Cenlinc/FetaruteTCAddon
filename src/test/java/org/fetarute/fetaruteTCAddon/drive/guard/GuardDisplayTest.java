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

/** 车掌的侧边栏：按停站阶段与发车前的几道条件给出要做的事。 */
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
    return new GuardDisplay.Snapshot(
        "T-1",
        Optional.empty(),
        true,
        Optional.of(stop),
        seated,
        0,
        Optional.empty(),
        Optional.empty());
  }

  /** 侧边栏“作业”一行。 */
  private static DriveSidebarRows.Row step(GuardDisplay.Snapshot snapshot) {
    return GuardDisplay.rows(snapshot).stream()
        .filter(row -> row.labelKey().equals("drive.guard.sidebar.label.step"))
        .findFirst()
        .orElseThrow();
  }

  private static String step(GuardDisplay.StopState stop, boolean seated) {
    return step(snapshot(stop, seated)).valueKey();
  }

  @Test
  void stepsFollowThePhase() {
    GuardDisplay.Snapshot running =
        new GuardDisplay.Snapshot(
            "T-1",
            Optional.empty(),
            false,
            Optional.empty(),
            true,
            0,
            Optional.empty(),
            Optional.empty());
    assertTrue(
        GuardDisplay.rows(running).stream().noneMatch(row -> row.labelKey().endsWith(".step")),
        "两站之间没有作业");
    DriveSidebarRows.Row open = step(snapshot(stop(Phase.OPEN_DOORS, false, false, false), true));
    assertEquals("drive.guard.sidebar.value.step-open-left", open.valueKey());
    assertEquals(Map.of("seconds", "10"), open.values());
    assertEquals(
        "drive.guard.sidebar.value.step-dwell", step(stop(Phase.DWELL, false, false, false), true));
    assertEquals(
        "drive.guard.sidebar.value.step-close",
        step(stop(Phase.CLOSE_DOORS, false, false, false), true));
    GuardDisplay.StopState closing =
        new GuardDisplay.StopState(
            "PPK",
            Phase.CLOSE_DOORS,
            DriverDoorSide.LEFT,
            false,
            false,
            true,
            200L,
            false,
            false,
            false);
    assertEquals("drive.guard.sidebar.value.step-closing", step(closing, true));
  }

  /** 等发车那一步：先回座，再等出站开放、确认，最后按铃。 */
  @Test
  void departureStepsComeInOrder() {
    assertEquals(
        "drive.guard.sidebar.value.step-return",
        step(stop(Phase.WAIT_DEPARTURE, true, false, false), false));
    assertEquals(
        "drive.guard.sidebar.value.step-wait-exit",
        step(stop(Phase.WAIT_DEPARTURE, false, false, false), true));
    assertEquals(
        "drive.guard.sidebar.value.step-confirm",
        step(stop(Phase.WAIT_DEPARTURE, true, false, false), true));
    assertEquals(
        "drive.guard.sidebar.value.step-buzzer",
        step(stop(Phase.WAIT_DEPARTURE, false, true, false), true));
    assertEquals(
        "drive.guard.sidebar.value.step-released",
        step(stop(Phase.WAIT_DEPARTURE, true, true, true), true));
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

  /** 换端：停站作业照常显示，另起一行写到第几节；放行前告知不显示秒数。 */
  @Test
  void cabChangeGetsItsOwnRow() {
    GuardDisplay.Snapshot running =
        new GuardDisplay.Snapshot(
            "T-1",
            Optional.empty(),
            false,
            Optional.empty(),
            false,
            0,
            Optional.of(new GuardDisplay.CabChangeState(4, 200L)),
            Optional.empty());
    DriveSidebarRows.Row change =
        GuardDisplay.rows(running).stream()
            .filter(row -> row.labelKey().equals("drive.guard.sidebar.label.cab-change"))
            .findFirst()
            .orElseThrow();
    assertEquals("drive.guard.sidebar.value.cab-change", change.valueKey());
    assertEquals(Map.of("car", "4", "seconds", "10"), change.values());
    GuardDisplay.Snapshot dwell =
        new GuardDisplay.Snapshot(
            "T-1",
            Optional.empty(),
            false,
            Optional.of(stop(Phase.DWELL, false, false, false)),
            true,
            0,
            Optional.of(new GuardDisplay.CabChangeState(1, -1L)),
            Optional.empty());
    assertEquals("drive.guard.sidebar.value.step-dwell", step(dwell).valueKey());
    assertTrue(
        GuardDisplay.rows(dwell).stream()
            .anyMatch(
                row ->
                    row.valueKey().equals("drive.guard.sidebar.value.cab-change-announced")
                        && row.values().equals(Map.of("car", "1"))));
  }

  /** 成绩单：每项扣分一行，全无扣分一行说明，异常报告另起一行。 */
  @Test
  void theSheetListsEachDeduction() {
    GuardScore clean = new GuardScore();
    clean.add(
        new GuardScore.Stop(
            "A", false, false, false, false, false, Optional.of(true), Optional.empty(), 0));
    assertEquals(
        List.of(new GuardDisplay.Line("drive.guard.sheet.clean", Map.of("count", "1"))),
        GuardDisplay.sheet(clean));
    GuardScore rough = new GuardScore();
    rough.add(
        new GuardScore.Stop(
            "B", true, false, true, false, true, Optional.of(false), Optional.of(true), 2));
    assertEquals(
        List.of(
            "drive.guard.sheet.forced-open",
            "drive.guard.sheet.forced-signal",
            "drive.guard.sheet.closed-early",
            "drive.guard.sheet.closing-watch",
            "drive.guard.sheet.incidents"),
        GuardDisplay.sheet(rough).stream().map(GuardDisplay.Line::key).toList());
    assertEquals(Map.of("station", "B"), GuardDisplay.sheet(rough).get(0).values());
  }

  /** 两站之间写下一站；进站起车站一行写开哪一侧，开门那一步也写哪一侧。 */
  @Test
  void theSidebarNamesTheNextStationAndTheDoorSide() {
    GuardDisplay.Snapshot running =
        new GuardDisplay.Snapshot(
            "T-1",
            Optional.empty(),
            false,
            Optional.empty(),
            true,
            0,
            Optional.empty(),
            Optional.of("西湖"));
    assertTrue(
        GuardDisplay.rows(running).stream()
            .anyMatch(
                row ->
                    row.valueKey().equals("drive.guard.sidebar.value.next-station")
                        && row.values().equals(Map.of("station", "西湖"))));
    List<DriveSidebarRows.Row> open =
        GuardDisplay.rows(snapshot(stop(Phase.OPEN_DOORS, false, false, false), true));
    assertTrue(
        open.stream()
            .anyMatch(row -> row.valueKey().equals("drive.guard.sidebar.value.station-left")));
    assertTrue(
        open.stream()
            .anyMatch(row -> row.valueKey().equals("drive.guard.sidebar.value.step-open-left")));
    assertTrue(
        open.stream().noneMatch(row -> row.labelKey().endsWith(".next-station")), "停站中不写下一站");
    assertEquals("no-doors", GuardDisplay.stationSideSuffix(DriverDoorSide.NONE));
  }

  @Test
  void secondsRoundUp() {
    assertEquals("1", GuardDisplay.seconds(1L));
    assertEquals("—", GuardDisplay.seconds(-1L));
  }
}
