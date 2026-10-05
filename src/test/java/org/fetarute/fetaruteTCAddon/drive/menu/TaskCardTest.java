package org.fetarute.fetaruteTCAddon.drive.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.drive.SimulationLevel;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverLink;
import org.fetarute.fetaruteTCAddon.drive.driver.score.ScoreRules;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveMode;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("驾驶台任务卡")
class TaskCardTest {

  @Test
  @DisplayName("调度列车：车次、任务区间、驾驶方式、下一站、实时评分")
  void dispatchCard() {
    DriverLink link = new DriverLink(UUID.randomUUID(), "T-1", null, () -> 0.0, () -> 0L);
    link.setNextStopLabel("人民广场");

    TaskCard.Card card =
        TaskCard.dispatch(
            "T-1",
            link,
            Optional.of(new TaskCard.TaskSummary("L1-A", "1023", "西湖")),
            Optional.of(new ScoreRules.Result(92, ScoreRules.Grade.A)));

    assertEquals("drive.menu.item.task-card-dispatch", card.titleKey());
    assertEquals(
        List.of(
            "drive.menu.card.train",
            "drive.menu.card.task-interval",
            "drive.menu.card.mode.manual",
            "drive.menu.card.next",
            "drive.menu.card.score"),
        card.lines().stream().map(TaskCard.Line::key).toList());
    assertEquals(
        Map.of("route", "L1-A", "trip", "1023", "station", "西湖"), card.lines().get(1).values());
    assertEquals(Map.of("points", "92", "grade", "A"), card.lines().get(4).values());
  }

  @Test
  @DisplayName("运营人员直接接管、没有任务时不写任务行；开到终点站写终点")
  void dispatchWithoutTaskOrToTerminal() {
    DriverLink link = new DriverLink(UUID.randomUUID(), "T-1", null, () -> 0.0, () -> 0L);
    TaskCard.Card noTask = TaskCard.dispatch("T-1", link, Optional.empty(), Optional.empty());
    assertEquals(
        List.of("drive.menu.card.train", "drive.menu.card.mode.manual"),
        noTask.lines().stream().map(TaskCard.Line::key).toList());

    TaskCard.Card terminal =
        TaskCard.dispatch(
            "T-1",
            link,
            Optional.of(new TaskCard.TaskSummary("L1-A", "1023", "")),
            Optional.empty());
    assertEquals("drive.menu.card.task-terminal", terminal.lines().get(1).key());
  }

  @Test
  @DisplayName("非调度列车：车名、车种、加减速与最高速度")
  void freeCard() {
    TaskCard.Card card =
        TaskCard.free("debug-1", new DriveParams(DriveMode.MU, 1.1, 1.2, 22.0, 1.0));
    assertEquals("drive.menu.item.task-card-free", card.titleKey());
    assertEquals("drive.menu.card.vehicle.mu", card.lines().get(1).key());
    assertEquals(Map.of("accel", "1.10", "decel", "1.20"), card.lines().get(2).values());
    assertEquals(Map.of("kmh", "79"), card.lines().get(3).values());
  }

  @Test
  @DisplayName("末尾写本次驾驶的仿真等级")
  void levelLine() {
    TaskCard.Card card =
        TaskCard.free("debug-1", new DriveParams(DriveMode.MU, 1.1, 1.2, 22.0, 1.0))
            .withLevel(SimulationLevel.SIMULATION);
    assertEquals(5, card.lines().size());
    assertEquals("drive.menu.card.level.simulation", card.lines().get(4).key());
  }
}
