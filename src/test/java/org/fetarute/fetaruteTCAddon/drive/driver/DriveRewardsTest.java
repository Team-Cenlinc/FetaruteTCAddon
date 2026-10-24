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
  @DisplayName("车掌：每站按人工停站单价、里程按每公里单价的一半，再乘评级系数，不分 ATO")
  void guardStopsFirstHalfDistance() {
    DriveRewards.Reward reward =
        DriveRewards.guard(
            DriveRewardConfig.defaults(), 1.0, 0.5, 4, 3000.0, 0, 0.0, ScoreRules.Grade.A);

    // 经验 1.2 × (4 站 × 2 + 3 km × 10 × 0.5) = 27.6
    assertEquals(27, reward.experience());
    // 钱币 1.2 × (4 × 5 + 3 × 20 × 0.5) = 60
    assertEquals(60.0, reward.money(), 1e-9);
    assertFalse(reward.crewBonus());
    assertEquals(
        DriveRewards.Reward.NONE,
        DriveRewards.guard(
            new DriveRewardConfig(
                false, 10, 2, 20, 5, 0.5, java.util.Map.of(), "eco give {player}", "FRD", 3, 0.5),
            1.0,
            0.5,
            4,
            3000.0,
            4,
            3000.0,
            ScoreRules.Grade.A));
  }

  @Test
  @DisplayName("同车加成：驾驶员人工驾驶、车掌在岗的那几站与那段里程，双方都多发 50%")
  void crewBonusOnTheSharedPart() {
    TaskScore score = score();
    score.addCrewDistance(1000.0);
    score.addCrewStop();

    DriveRewards.Reward driver =
        DriveRewards.of(DriveRewardConfig.defaults(), score, ScoreRules.Grade.A);

    // 经验 37.2 + 1.2 × 0.5 × (1 km × 10 + 1 站 × 2) = 44.4
    assertEquals(44, driver.experience());
    // 钱币 78 + 1.2 × 0.5 × (1 × 20 + 1 × 5) = 93
    assertEquals(93.0, driver.money(), 1e-9);
    assertTrue(driver.crewBonus());

    DriveRewards.Reward guard =
        DriveRewards.guard(
            DriveRewardConfig.defaults(), 1.0, 0.5, 4, 3000.0, 2, 1000.0, ScoreRules.Grade.A);
    // 经验 27.6 + 1.2 × 0.5 × (2 站 × 2 + 1 km × 10 × 0.5) = 33
    assertEquals(33, guard.experience());
    // 钱币 60 + 1.2 × 0.5 × (2 × 5 + 1 × 20 × 0.5) = 72
    assertEquals(72.0, guard.money(), 1e-9);
    assertTrue(guard.crewBonus());

    DriveRewardConfig off =
        new DriveRewardConfig(true, 10, 2, 20, 5, 0.5, java.util.Map.of(), "", "FRD", 3, 0.0);
    DriveRewards.Reward plain = DriveRewards.of(off, score, ScoreRules.Grade.A);
    // 没写评级系数按 1：2 × 20 + 2 × 5 + 0.5 × (1 × 20 + 2 × 5) = 65，同车那段不另加
    assertEquals(65.0, plain.money(), 1e-9, "crew-bonus 为 0 不加");
    assertFalse(plain.crewBonus());
  }

  @Test
  @DisplayName("同车里程与停站只在人工驾驶、车掌在岗时记；ATO 那段不算")
  void linkTracksCrewOnlyWhenDrivingWithAGuard() {
    double[] odometer = {0.0};
    long[] clock = {0L};
    DriverLink link =
        new DriverLink(UUID.randomUUID(), "T-1", null, () -> odometer[0], () -> clock[0]);
    link.trackDistance(false);
    odometer[0] = 100.0;
    link.trackDistance(false);
    link.setGuardAboard(true);
    odometer[0] = 250.0;
    link.trackDistance(false);
    odometer[0] = 300.0;
    link.trackDistance(true);

    assertEquals(250.0, link.score().manualBlocks(), 1e-9);
    assertEquals(150.0, link.score().crewBlocks(), 1e-9, "车掌上岗后的人工驾驶里程");
  }

  @Test
  @DisplayName("关闭时、没开过车时什么也不发")
  void nothingWhenDisabledOrIdle() {
    DriveRewardConfig off =
        new DriveRewardConfig(
            false, 10, 2, 20, 5, 0.5, java.util.Map.of(), "eco give {player}", "FRD", 3, 0.5);
    assertTrue(DriveRewards.of(off, score(), ScoreRules.Grade.S).empty());
    assertTrue(
        DriveRewards.of(DriveRewardConfig.defaults(), new TaskScore(), ScoreRules.Grade.S).empty());
  }

  @Test
  @DisplayName("信号员：每列车按人工停站单价乘比例，再乘评级系数；没有 ATO 折扣与同车加成")
  void signallerPerTrainThenGraded() {
    DriveRewards.Reward reward =
        DriveRewards.signaller(DriveRewardConfig.defaults(), 1.0, 6, ScoreRules.Grade.A);

    // 经验 1.2 × 6 列 × 2 = 14.4；钱币 1.2 × 6 × 5 = 36
    assertEquals(14, reward.experience());
    assertEquals(36.0, reward.money(), 1e-9);
    assertFalse(reward.crewBonus());

    DriveRewards.Reward half =
        DriveRewards.signaller(DriveRewardConfig.defaults(), 0.5, 6, ScoreRules.Grade.B);
    // 1.0 × 6 × 0.5 × 2 = 6；钱币 1.0 × 6 × 0.5 × 5 = 15
    assertEquals(6, half.experience());
    assertEquals(15.0, half.money(), 1e-9);
  }

  @Test
  @DisplayName("信号员：奖励关闭、没有计奖励的车、比例为 0 或非法时什么也不发")
  void signallerNothingWhenDisabledOrNoTrains() {
    DriveRewardConfig off =
        new DriveRewardConfig(
            false, 10, 2, 20, 5, 0.5, java.util.Map.of(), "eco give {player}", "FRD", 3, 0.5);
    assertTrue(DriveRewards.signaller(off, 1.0, 10, ScoreRules.Grade.S).empty());
    assertTrue(
        DriveRewards.signaller(DriveRewardConfig.defaults(), 1.0, 0, ScoreRules.Grade.S).empty());
    assertTrue(
        DriveRewards.signaller(DriveRewardConfig.defaults(), 1.0, -3, ScoreRules.Grade.S).empty());
    assertTrue(
        DriveRewards.signaller(DriveRewardConfig.defaults(), 0.0, 10, ScoreRules.Grade.S).empty());
    assertTrue(
        DriveRewards.signaller(DriveRewardConfig.defaults(), Double.NaN, 10, ScoreRules.Grade.S)
            .empty());
  }

  @Test
  @DisplayName("配置：信号员每列车比例默认 1，可改，负数回退默认并提示")
  void parsesSignallerRatio() throws Exception {
    assertEquals(1.0, DriveRewardConfig.defaults().signallerTrainRatio(), 1e-9);
    assertEquals(
        1.0,
        new DriveRewardConfig(true, 10, 2, 20, 5, 0.5, java.util.Map.of(), "", "FRD", 3, 0.5)
            .signallerTrainRatio(),
        1e-9,
        "不带比例的构造按默认值");

    YamlConfiguration yaml = new YamlConfiguration();
    yaml.loadFromString("signaller-per-train-ratio: 0.75");
    assertEquals(0.75, DriveRewardConfig.from(yaml, null).signallerTrainRatio(), 1e-9);

    YamlConfiguration negative = new YamlConfiguration();
    negative.loadFromString("signaller-per-train-ratio: -2");
    List<String> warnings = new ArrayList<>();
    assertEquals(1.0, DriveRewardConfig.from(negative, warnings::add).signallerTrainRatio(), 1e-9);
    assertEquals(1, warnings.size(), warnings::toString);
  }

  @Test
  @DisplayName("钱币数额两位小数、去掉末尾的 0")
  void amountFormatting() {
    assertEquals("78", DriveRewards.formatAmount(78.0));
    assertEquals("12.35", DriveRewards.formatAmount(12.349));
    assertEquals("0.5", DriveRewards.formatAmount(0.5));
  }

  @Test
  @DisplayName("配置：默认开启；可改各项与评级系数；负数与未知评级回退并提示")
  void parsesConfig() throws Exception {
    DriveRewardConfig defaults = DriveRewardConfig.defaults();
    assertTrue(defaults.enabled());
    assertEquals(1.5, defaults.gradeMultiplier(ScoreRules.Grade.S), 1e-9);
    assertEquals("eco give {player} {amount}", defaults.moneyCommand());
    assertEquals("FRD", defaults.currencyName());
    assertEquals(3, defaults.awayAfterTimeouts());
    assertEquals(0.5, defaults.crewBonus(), 1e-9);

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
            "money-command: ''",
            "currency-name: 金币",
            "away-after-timeouts: 0",
            "crew-bonus: 0.25"));
    List<String> warnings = new ArrayList<>();
    DriveRewardConfig config = DriveRewardConfig.from(yaml, warnings::add);

    assertFalse(config.enabled());
    assertEquals(4.0, config.experiencePerKm(), 1e-9);
    assertEquals(5.0, config.moneyPerStop(), 1e-9, "负数回退默认值");
    assertEquals(0.0, config.atoMultiplier(), 1e-9);
    assertEquals(3.0, config.gradeMultiplier(ScoreRules.Grade.S), 1e-9, "评级不区分大小写");
    assertEquals(1.2, config.gradeMultiplier(ScoreRules.Grade.A), 1e-9, "没写的评级保留默认");
    assertEquals("", config.moneyCommand());
    assertEquals("金币", config.currencyName());
    assertEquals(0, config.awayAfterTimeouts());
    assertEquals(0.25, config.crewBonus(), 1e-9);
    assertEquals(2, warnings.size(), warnings::toString);
  }

  @Test
  @DisplayName("里程按此刻是人工还是 ATO 分开记；ATO 停站在放行发车时记一站，反复询问只算一站")
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
    assertTrue(link.holdDeparture(400L), "等驾驶员确认");
    clock[0] = 20L;
    assertTrue(link.holdDeparture(400L));
    assertEquals(0, link.score().atoStops(), "还没放行");
    assertTrue(link.confirmDeparture());
    assertFalse(link.holdDeparture(400L));
    assertEquals(1, link.score().atoStops());
  }

  /** ATO 下这一站超时未确认：站台每秒问一次，等满时限后自动放行。 */
  private static void timeoutStop(DriverLink link, long[] clock, long at) {
    for (long t = at; t < at + 400L; t += 20L) {
      clock[0] = t;
      assertTrue(link.holdDeparture(400L));
    }
    clock[0] = at + 400L;
    assertFalse(link.holdDeparture(400L));
  }

  /** ATO 下这一站驾驶员确认发车后放行。 */
  private static void confirmedStop(DriverLink link, long[] clock, long at) {
    clock[0] = at;
    assertTrue(link.holdDeparture(400L));
    assertTrue(link.confirmDeparture());
    clock[0] = at + 10L;
    assertFalse(link.holdDeparture(400L));
  }

  @Test
  @DisplayName("ATO 连续 3 站超时未确认判定离开：第一次超时以来的里程与停站作废，确认一次发车恢复；不到 3 站就确认的照计")
  void awayAfterThreeTimeouts() {
    double[] odometer = {0.0};
    long[] clock = {0L};
    DriverLink link =
        new DriverLink(UUID.randomUUID(), "T-1", null, () -> odometer[0], () -> clock[0]);
    link.setAwayAfterTimeouts(3);
    link.enterAto();
    link.trackDistance(true);

    timeoutStop(link, clock, 0L);
    odometer[0] = 100.0;
    link.trackDistance(true);
    timeoutStop(link, clock, 1000L);
    odometer[0] = 200.0;
    link.trackDistance(true);
    assertEquals(0, link.score().atoStops(), "超时后先挂起");
    confirmedStop(link, clock, 2000L);
    assertEquals(3, link.score().atoStops(), "两站超时后确认：挂起的照计");
    assertEquals(200.0, link.score().atoBlocks(), 1e-9);
    assertFalse(link.away());

    for (long at : new long[] {3000L, 4000L, 5000L}) {
      odometer[0] += 100.0;
      link.trackDistance(true);
      timeoutStop(link, clock, at);
    }
    assertTrue(link.away());
    assertEquals(java.util.Optional.of(true), link.takeAwayNotice());
    odometer[0] += 500.0;
    link.trackDistance(true);
    assertEquals(3, link.score().atoStops(), "判定离开：这几站作废");
    assertEquals(300.0, link.score().atoBlocks(), 1e-9, "确认后到下一站的里程照计，第一次超时以来的作废");

    confirmedStop(link, clock, 6000L);
    assertFalse(link.away());
    assertEquals(java.util.Optional.of(false), link.takeAwayNotice());
    assertEquals(4, link.score().atoStops(), "确认后恢复计");
  }

  @Test
  @DisplayName("超时不到 3 站就结算：挂起的照计入本趟；判定离开时作废")
  void pendingIsSettledWithTheTrip() {
    double[] odometer = {0.0};
    long[] clock = {0L};
    DriverLink link =
        new DriverLink(UUID.randomUUID(), "T-1", null, () -> odometer[0], () -> clock[0]);
    link.setAwayAfterTimeouts(3);
    link.enterAto();
    link.trackDistance(true);
    timeoutStop(link, clock, 0L);
    odometer[0] = 50.0;
    link.trackDistance(true);

    TaskScore settled = link.settleTrip();
    assertEquals(2, settled.atoStops(), "超时那站挂起后照计 + 终点站");
    assertEquals(50.0, settled.atoBlocks(), 1e-9);
  }

  @Test
  @DisplayName("浮点误差不少发：4.35 公里 × 每公里 100 经验按 435 发，不是 434")
  void roundingDoesNotUnderpay() {
    TaskScore score = new TaskScore();
    score.addDistance(4350.0, false);
    DriveRewardConfig config =
        new DriveRewardConfig(true, 100, 0, 0.1, 0, 0.5, java.util.Map.of(), "", "FRD", 3, 0.5);

    DriveRewards.Reward reward = DriveRewards.of(config, score, ScoreRules.Grade.B);

    assertEquals(435, reward.experience());
    assertEquals(0.44, reward.money(), 1e-9, "0.435 四舍五入到分");
  }

  @Test
  @DisplayName("ATO 下终点站这一站记进刚结算的这一趟；下一趟从这里发车不再记，开到下一站照常记")
  void atoTerminalStopBelongsToTheSettledTrip() {
    double[] odometer = {0.0};
    long[] clock = {0L};
    DriverLink link =
        new DriverLink(UUID.randomUUID(), "T-1", null, () -> odometer[0], () -> clock[0]);
    link.enterAto();
    confirmedStop(link, clock, 0L);
    odometer[0] = 500.0;

    TaskScore settled = link.settleTrip();
    assertEquals(2, settled.atoStops(), "中途一站 + 终点站");

    confirmedStop(link, clock, 1000L);
    assertEquals(0, link.score().atoStops(), "从终点站发车：这一站已记进上一趟");
    odometer[0] = 900.0;
    confirmedStop(link, clock, 2000L);
    assertEquals(1, link.score().atoStops());
  }
}
