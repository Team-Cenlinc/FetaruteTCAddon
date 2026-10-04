package org.fetarute.fetaruteTCAddon.drive.driver.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.drive.SimulationLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("任务板的驾驶难度按钮")
class TaskBoardLevelTest {

  @Test
  @DisplayName("两个按钮在最后一行左侧，不占车次格与说明格")
  void buttonsSitInTheBottomRow() {
    for (SimulationLevel level : SimulationLevel.values()) {
      int slot = TaskBoard.slotOf(level);
      assertTrue(
          slot >= TaskBoard.ENTRY_SLOTS && slot < TaskBoard.SIZE, level + " 在第 " + slot + " 格");
      assertNotEquals(TaskBoard.INFO_SLOT, slot);
      assertEquals(Optional.of(level), TaskBoard.levelOfSlot(slot));
    }
    assertNotEquals(
        TaskBoard.slotOf(SimulationLevel.STANDARD), TaskBoard.slotOf(SimulationLevel.SIMULATION));
    assertEquals(Optional.empty(), TaskBoard.levelOfSlot(0));
    assertEquals(Optional.empty(), TaskBoard.levelOfSlot(TaskBoard.INFO_SLOT));
    assertNotEquals(
        TaskBoard.levelMaterial(SimulationLevel.STANDARD),
        TaskBoard.levelMaterial(SimulationLevel.SIMULATION));
  }

  @Test
  @DisplayName("不能自选等级的玩家：难度格点了不算")
  void noButtonsWithoutPermission() {
    TaskBoardHolder holder = new TaskBoardHolder(UUID.randomUUID(), "OP", "ST", "站", List.of());
    assertEquals(Optional.empty(), holder.levelAt(TaskBoard.slotOf(SimulationLevel.SIMULATION)));
    holder.setLevel(SimulationLevel.STANDARD);
    assertEquals(
        Optional.of(SimulationLevel.SIMULATION),
        holder.levelAt(TaskBoard.slotOf(SimulationLevel.SIMULATION)));
    assertEquals(Optional.of(SimulationLevel.STANDARD), holder.level());
  }

  @Test
  @DisplayName("说明行：选中与否、正在驾驶时注明下次生效")
  void loreReflectsSelectionAndDriving() {
    assertEquals(
        List.of(
            "drive.task.board.level.standard-desc",
            "drive.task.board.level.selected",
            "drive.task.board.level.remember"),
        TaskBoard.levelLore(SimulationLevel.STANDARD, true, false));
    assertEquals(
        List.of(
            "drive.task.board.level.simulation-desc",
            "drive.task.board.level.simulation-desc-2",
            "drive.task.board.level.select",
            "drive.task.board.level.next-session"),
        TaskBoard.levelLore(SimulationLevel.SIMULATION, false, true));
  }
}
