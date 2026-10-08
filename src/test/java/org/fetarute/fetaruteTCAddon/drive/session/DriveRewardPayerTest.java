package org.fetarute.fetaruteTCAddon.drive.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fetarute.fetaruteTCAddon.drive.driver.task.DriverTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("驾驶奖励的发放")
class DriveRewardPayerTest {

  @Test
  @DisplayName("没有 Vault 时的发钱命令：填好玩家名与数额，去掉开头的 /；命令或玩家名为空时不发")
  void fillsTheMoneyCommand() {
    assertEquals(
        "eco give Steve 12.5",
        DriveRewardPayer.moneyCommandLine("/eco give {player} {amount}", "Steve", 12.5));
    assertEquals("", DriveRewardPayer.moneyCommandLine("", "Steve", 12.5));
    assertEquals("", DriveRewardPayer.moneyCommandLine("eco give {player} {amount}", null, 12.5));
  }

  @Test
  @DisplayName("开到终点、中途结束、被收回的给；卡住被收回、超时、越站交还的不给；路考与练习不给")
  void whichTasksEarnRewards() {
    assertTrue(
        DriveSessionManager.earnsReward(DriverTask.State.COMPLETED, DriverTask.SOURCE_BOARD));
    assertTrue(
        DriveSessionManager.earnsReward(DriverTask.State.ABANDONED, DriverTask.SOURCE_TAKEOVER));
    assertTrue(
        DriveSessionManager.earnsReward(
            DriverTask.State.INTERRUPTED, DriverTask.SOURCE_CONTINUATION));
    assertFalse(DriveSessionManager.earnsReward(DriverTask.State.FAILED, DriverTask.SOURCE_BOARD));
    assertFalse(
        DriveSessionManager.earnsReward(DriverTask.State.COMPLETED, DriverTask.SOURCE_EXAM));
    assertFalse(
        DriveSessionManager.earnsReward(DriverTask.State.COMPLETED, DriverTask.SOURCE_TRAINING));
  }
}
