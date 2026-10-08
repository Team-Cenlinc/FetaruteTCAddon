package org.fetarute.fetaruteTCAddon.drive.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopAlignment;
import org.fetarute.fetaruteTCAddon.drive.driver.score.ScoreRules;
import org.fetarute.fetaruteTCAddon.drive.driver.score.StopScore;
import org.fetarute.fetaruteTCAddon.drive.driver.score.TaskScore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("驾驶奖励：按里程与停站发经验与钱币")
class DriveRewardsTest {

  private static TaskScore score() {
    TaskScore score = new TaskScore();
    score.addDistance(2000.0, false);
    score.addDistance(1000.0, true);
    score.addStop(new StopScore("A", 0.5, StopAlignment.Outcome.ACCURATE, false, false));
    score.addStop(new StopScore("B", 3.0, StopAlignment.Outcome.ACCEPTED, false, false));
    score.addStop(new StopScore("C", Double.NaN, StopAlignment.Outcome.SKIPPED, false, false));
    score.addAtoStop();
    score.addAtoStop();
    return score;
  }

  @Test
  @DisplayName("人工驾驶全额、ATO 那段打折、越站不算停站，再乘评级系数")
  void manualFullAtoDiscountedThenGraded() {
    DriveRewards.Reward reward =
        DriveRewards.of(DriveRewardConfig.defaults(), score(), ScoreRules.Grade.A);

    // 经验 1.2 × (2 km × 10 + 2 站 × 2 + 0.5 × (1 km × 10 + 2 站 × 2)) = 37.2
    assertEquals(37, reward.experience());
    // 钱币 1.2 × (2 × 20 + 2 × 5 + 0.5 × (1 × 20 + 2 × 5)) = 78
    assertEquals(78.0, reward.money(), 1e-9);
  }

  @Test
  @DisplayName("关闭时、没开过车时什么也不发")
  void nothingWhenDisabledOrIdle() {
    DriveRewardConfig off =
        new DriveRewardConfig(false, 10, 2, 20, 5, 0.5, java.util.Map.of(), "eco give {player}");
    assertTrue(DriveRewards.of(off, score(), ScoreRules.Grade.S).empty());
    assertTrue(
        DriveRewards.of(DriveRewardConfig.defaults(), new TaskScore(), ScoreRules.Grade.S).empty());
  }

  @Test
  @DisplayName("钱币数额两位小数、去掉末尾的 0")
  void amountFormatting() {
    assertEquals("78", DriveRewards.formatAmount(78.0));
    assertEquals("12.34", DriveRewards.formatAmount(12.349));
    assertEquals("0.5", DriveRewards.formatAmount(0.5));
  }

  @Test
  @DisplayName("配置：默认开启；可改各项与评级系数；负数与未知评级回退并提示")
  void parsesConfig() throws Exception {
    DriveRewardConfig defaults = DriveRewardConfig.defaults();
    assertTrue(defaults.enabled());
    assertEquals(1.5, defaults.gradeMultiplier(ScoreRules.Grade.S), 1e-9);
    assertEquals("eco give {player} {amount}", defaults.moneyCommand());

    YamlConfiguration yaml = new YamlConfiguration();
    yaml.loadFromString(
        String.join(
            "\n",
            "enabled: false",
            "experience-per-km: 4",
            "money-per-stop: -1",
            "ato-multiplier: 0",
            "grade-multipliers:",
            "  s: 3",
            "  X: 2",
            "money-command: ''"));
    List<String> warnings = new ArrayList<>();
    DriveRewardConfig config = DriveRewardConfig.from(yaml, warnings::add);

    assertFalse(config.enabled());
    assertEquals(4.0, config.experiencePerKm(), 1e-9);
    assertEquals(5.0, config.moneyPerStop(), 1e-9, "负数回退默认值");
    assertEquals(0.0, config.atoMultiplier(), 1e-9);
    assertEquals(3.0, config.gradeMultiplier(ScoreRules.Grade.S), 1e-9, "评级不区分大小写");
    assertEquals(1.2, config.gradeMultiplier(ScoreRules.Grade.A), 1e-9, "没写的评级保留默认");
    assertEquals("", config.moneyCommand());
    assertEquals(2, warnings.size(), warnings::toString);
  }

  @Test
  @DisplayName("里程按此刻是人工还是 ATO 分开记；ATO 下每停一站记一次")
  void linkTracksDistanceAndAtoStops() {
    double[] odometer = {0.0};
    long[] clock = {0L};
    DriverLink link =
        new DriverLink(UUID.randomUUID(), "T-1", null, () -> odometer[0], () -> clock[0]);
    link.trackDistance(false);
    odometer[0] = 120.0;
    link.trackDistance(false);
    odometer[0] = 170.0;
    link.trackDistance(true);
    assertEquals(120.0, link.score().manualBlocks(), 1e-9);
    assertEquals(50.0, link.score().atoBlocks(), 1e-9);

    link.enterAto();
    link.holdDeparture(400L);
    clock[0] = 20L;
    link.holdDeparture(400L);
    assertEquals(1, link.score().atoStops(), "同一站反复询问只算一站");
    clock[0] = 2000L;
    link.holdDeparture(400L);
    assertEquals(2, link.score().atoStops());
  }
}
