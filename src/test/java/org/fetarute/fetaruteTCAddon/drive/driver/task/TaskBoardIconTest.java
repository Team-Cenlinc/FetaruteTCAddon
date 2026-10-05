package org.fetarute.fetaruteTCAddon.drive.driver.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("任务板条目的图标")
class TaskBoardIconTest {

  @Test
  @DisplayName("正在本站停站的用画着内容的地图，其余用空地图")
  void dwellingEntriesUseTheFilledMap() {
    assertEquals(Material.FILLED_MAP, TaskBoard.entryMaterial(true));
    assertEquals(Material.MAP, TaskBoard.entryMaterial(false));
    assertNotEquals(TaskBoard.entryMaterial(true), TaskBoard.entryMaterial(false));
  }

  @Test
  @DisplayName("别人领走的用标记染红的藏宝图")
  void claimedEntriesUseTheRedMarkedMap() {
    assertEquals(Material.FILLED_MAP, TaskBoard.claimedMaterial());
    assertEquals(0xB02E26, TaskBoard.CLAIMED_MARKINGS.asRGB());
  }
}
