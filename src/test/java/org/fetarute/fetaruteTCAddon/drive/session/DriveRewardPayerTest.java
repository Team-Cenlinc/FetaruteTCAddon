package org.fetarute.fetaruteTCAddon.drive.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.drive.driver.DriveRewardConfig;
import org.fetarute.fetaruteTCAddon.drive.driver.DriveRewards;
import org.fetarute.fetaruteTCAddon.drive.driver.DrivingMode;
import org.fetarute.fetaruteTCAddon.drive.driver.task.DriverTask;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("驾驶奖励的发放")
class DriveRewardPayerTest {

  /** 假的服务器操作：记下发了什么。 */
  private static final class FakeHooks implements DriveRewardPayer.Hooks {
    boolean online = true;
    boolean vault;
    Boolean deposit = Boolean.TRUE;
    int experience;
    double deposited;
    final List<String> commands = new ArrayList<>();

    @Override
    public boolean giveExperience(UUID playerId, int amount) {
      if (!online) {
        return false;
      }
      experience += amount;
      return true;
    }

    @Override
    public boolean vaultEnabled() {
      return vault;
    }

    @Override
    public boolean vaultDeposit(UUID playerId, double amount) {
      if (deposit == null) {
        throw new IllegalStateException("economy broken");
      }
      if (deposit) {
        deposited += amount;
      }
      return deposit;
    }

    @Override
    public boolean dispatch(String commandLine) {
      commands.add(commandLine);
      return true;
    }
  }

  private static final DriveRewards.Reward REWARD = new DriveRewards.Reward(66, 140.0);

  @Test
  @DisplayName("装了 Vault 且存入成功：只走 Vault，不再执行命令")
  void paysThroughVault() {
    FakeHooks hooks = new FakeHooks();
    hooks.vault = true;
    DriveRewardPayer.Paid paid =
        new DriveRewardPayer(m -> {}, hooks)
            .pay(UUID.randomUUID(), "Steve", REWARD, DriveRewardConfig.defaults());

    assertEquals(66, paid.experience());
    assertEquals(Optional.of("140"), paid.money());
    assertEquals(140.0, hooks.deposited, 1e-9);
    assertTrue(hooks.commands.isEmpty());
  }

  @Test
  @DisplayName("Vault 存不进或抛异常、或没装 Vault：改用配置的命令发，只发一次")
  void fallsBackToTheCommand() {
    for (Boolean deposit : new Boolean[] {Boolean.FALSE, null}) {
      FakeHooks hooks = new FakeHooks();
      hooks.vault = true;
      hooks.deposit = deposit;
      List<String> warnings = new ArrayList<>();
      DriveRewardPayer.Paid paid =
          new DriveRewardPayer(warnings::add, hooks)
              .pay(UUID.randomUUID(), "Steve", REWARD, DriveRewardConfig.defaults());
      assertEquals(Optional.of("140"), paid.money());
      assertEquals(List.of("eco give Steve 140"), hooks.commands);
      assertEquals(0.0, hooks.deposited, 1e-9);
      assertEquals(deposit == null ? 1 : 0, warnings.size(), warnings::toString);
    }

    FakeHooks noVault = new FakeHooks();
    new DriveRewardPayer(m -> {}, noVault)
        .pay(UUID.randomUUID(), "Steve", REWARD, DriveRewardConfig.defaults());
    assertEquals(List.of("eco give Steve 140"), noVault.commands);
  }

  @Test
  @DisplayName("命令留空时不发钱币；玩家不在线时不发经验")
  void skipsWhatCannotBePaid() {
    FakeHooks hooks = new FakeHooks();
    hooks.online = false;
    DriveRewardConfig noCommand =
        new DriveRewardConfig(true, 10, 2, 20, 5, 0.5, java.util.Map.of(), "", "FRD", 3);
    DriveRewardPayer.Paid paid =
        new DriveRewardPayer(m -> {}, hooks).pay(UUID.randomUUID(), "Steve", REWARD, noCommand);

    assertEquals(0, paid.experience());
    assertTrue(paid.money().isEmpty());
    assertTrue(paid.empty());
    assertTrue(hooks.commands.isEmpty());
  }

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
  @DisplayName("开到终点、中途结束、被收回的给；卡住被收回、超时、越站交还的不给；只给本插件自己的任务")
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
    assertFalse(
        DriveSessionManager.earnsReward(DriverTask.State.COMPLETED, "typewriter"),
        "其他插件经 DriveApi 派的任务由那个插件自己发奖励");
  }

  @Test
  @DisplayName("一趟只评一次、只发一次：终点站结算评过后，驾驶结束时不再评；别的车、没开始的不评")
  void recordedOncePerTrip() {
    DriverTask task =
        new DriverTask(
            UUID.randomUUID(),
            "Steve",
            new TaskKey(UUID.randomUUID(), "R1-001", LocalDate.of(2026, 10, 8)),
            "R1",
            "OP",
            "AAA",
            "A 站",
            "OP:S:AAA:1",
            0,
            Instant.EPOCH,
            DrivingMode.MANUAL,
            Instant.EPOCH);
    assertFalse(DriveSessionManager.recordable(task, "T-1"), "还没开始");
    task.start("T-1", 0L);
    assertFalse(DriveSessionManager.recordable(task, "T-1"), "还没结束");
    task.finish(DriverTask.State.COMPLETED, "terminal");
    assertTrue(DriveSessionManager.recordable(task, "t-1"));
    assertFalse(DriveSessionManager.recordable(task, "T-2"), "不是这列车");

    task.setResult(90, "A");
    assertFalse(DriveSessionManager.recordable(task, "T-1"), "终点站结算已评过：驾驶结束时不再发一次");
  }

  @Test
  @DisplayName("奖励提示：发了经验与钱币、只发其一、判为未完成；什么也没发时不说")
  void rewardMessageKeys() {
    assertEquals(
        Optional.of("drive.task.reward.both"),
        DriveSessionManager.rewardMessageKey(
            new DriveRewardPayer.Paid(5, Optional.of("1")), false));
    assertEquals(
        Optional.of("drive.task.reward.experience"),
        DriveSessionManager.rewardMessageKey(
            new DriveRewardPayer.Paid(5, Optional.empty()), false));
    assertEquals(
        Optional.of("drive.task.reward.money"),
        DriveSessionManager.rewardMessageKey(
            new DriveRewardPayer.Paid(0, Optional.of("1")), false));
    assertEquals(
        Optional.of("drive.task.reward.forfeited"),
        DriveSessionManager.rewardMessageKey(new DriveRewardPayer.Paid(0, Optional.empty()), true));
    assertTrue(
        DriveSessionManager.rewardMessageKey(new DriveRewardPayer.Paid(0, Optional.empty()), false)
            .isEmpty());
  }
}
