package org.fetarute.fetaruteTCAddon.drive.driver;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopAlignment;
import org.fetarute.fetaruteTCAddon.drive.driver.score.ScoreRules;
import org.fetarute.fetaruteTCAddon.drive.driver.score.StopScore;
import org.fetarute.fetaruteTCAddon.drive.driver.score.TaskScore;

/**
 * 一趟驾驶任务的奖励：人工驾驶与 ATO 各自的里程、停站数分别计，ATO 那段打折，再乘评级系数。本类不依赖服务器对象。
 *
 * <p>同车加成：驾驶员人工驾驶、车上同时有车掌在岗的那段（停站与里程），驾驶员与车掌都按各自的单价多发 {@link DriveRewardConfig#crewBonus()} 这个比例。
 */
public final class DriveRewards {

  private static final double BLOCKS_PER_KM = 1000.0;

  /**
   * 奖励。
   *
   * @param experience 经验（取整，不足 1 不发）
   * @param money 钱币（保留两位小数）
   * @param crewBonus 其中含同车加成
   */
  public record Reward(int experience, double money, boolean crewBonus) {
    public static final Reward NONE = new Reward(0, 0.0);

    /** 不含同车加成的奖励。 */
    public Reward(int experience, double money) {
      this(experience, money, false);
    }

    /** 什么也不发。 */
    public boolean empty() {
      return experience <= 0 && !(money > 0.0);
    }
  }

  private DriveRewards() {}

  /** 按这趟的里程、停站与评级算奖励；奖励关闭时为 {@link Reward#NONE}。 */
  public static Reward of(DriveRewardConfig config, TaskScore score, ScoreRules.Grade grade) {
    if (config == null || !config.enabled() || score == null) {
      return Reward.NONE;
    }
    int manualStops = 0;
    for (StopScore stop : score.stops()) {
      if (stop.outcome() != StopAlignment.Outcome.SKIPPED) {
        manualStops++;
      }
    }
    Basis basis =
        new Basis(
            score.manualBlocks() / BLOCKS_PER_KM,
            manualStops,
            score.atoBlocks() / BLOCKS_PER_KM,
            score.atoStops(),
            config.atoMultiplier(),
            config.gradeMultiplier(grade));
    // 同车的那段已算在人工驾驶里：这里只另加加成的部分
    Basis crew =
        new Basis(
            score.crewBlocks() / BLOCKS_PER_KM,
            score.crewStops(),
            0.0,
            0,
            0.0,
            config.gradeMultiplier(grade) * config.crewBonus());
    return rounded(
        basis.amount(config.experiencePerKm(), config.experiencePerStop()),
        basis.amount(config.moneyPerKm(), config.moneyPerStop()),
        crew.amount(config.experiencePerKm(), config.experiencePerStop()),
        crew.amount(config.moneyPerKm(), config.moneyPerStop()));
  }

  /**
   * 车掌一次值乘的奖励：每站按驾驶员人工停站的单价乘 {@code stopRatio}，里程按驾驶员每公里的单价乘 {@code kmRatio}，再乘评级系数。 不分人工驾驶与
   * ATO：车掌的活都是人工做的。
   *
   * @param stops 完成作业的停站数
   * @param blocks 值乘期间列车走过的距离（格）
   * @param crewStops 其中驾驶员人工驾驶时做的站数（同车加成）
   * @param crewBlocks 其中驾驶员人工驾驶时走过的距离（格，同车加成）
   */
  public static Reward guard(
      DriveRewardConfig config,
      double stopRatio,
      double kmRatio,
      int stops,
      double blocks,
      int crewStops,
      double crewBlocks,
      ScoreRules.Grade grade) {
    if (config == null || !config.enabled()) {
      return Reward.NONE;
    }
    double multiplier = config.gradeMultiplier(grade);
    double crewMultiplier = multiplier * config.crewBonus();
    return rounded(
        multiplier
            * guardAmount(
                config.experiencePerStop(),
                config.experiencePerKm(),
                stopRatio,
                kmRatio,
                stops,
                blocks),
        multiplier
            * guardAmount(
                config.moneyPerStop(), config.moneyPerKm(), stopRatio, kmRatio, stops, blocks),
        crewMultiplier
            * guardAmount(
                config.experiencePerStop(),
                config.experiencePerKm(),
                stopRatio,
                kmRatio,
                crewStops,
                crewBlocks),
        crewMultiplier
            * guardAmount(
                config.moneyPerStop(),
                config.moneyPerKm(),
                stopRatio,
                kmRatio,
                crewStops,
                crewBlocks));
  }

  /**
   * 信号员一个班次的奖励：每列车按驾驶员人工停站的单价乘 {@code trainRatio}（经验与钱币各一份），再乘评级系数。按班次结算，不按值守时长计；ATO 折扣与同车加成都不适用。
   *
   * @param trainRatio 每列车的单价是驾驶员人工停站单价的几倍
   * @param trains 计奖励的车数：经手的车里去掉超时代排的（调用方算好）
   */
  public static Reward signaller(
      DriveRewardConfig config, double trainRatio, double trains, ScoreRules.Grade grade) {
    if (config == null || !config.enabled()) {
      return Reward.NONE;
    }
    double ratio = Double.isFinite(trainRatio) ? Math.max(0.0, trainRatio) : 0.0;
    double count = Double.isFinite(trains) ? Math.max(0.0, trains) : 0.0;
    double units = count * ratio * config.gradeMultiplier(grade);
    return rounded(units * config.experiencePerStop(), units * config.moneyPerStop(), 0.0, 0.0);
  }

  /** 车掌按站数与里程的数额（未乘评级系数）。 */
  private static double guardAmount(
      double perStop, double perKm, double stopRatio, double kmRatio, int stops, double blocks) {
    double km = Double.isFinite(blocks) ? Math.max(0.0, blocks) / BLOCKS_PER_KM : 0.0;
    return Math.max(0, stops) * perStop * stopRatio + km * perKm * kmRatio;
  }

  /**
   * 浮点乘出来的整数常差一点点（35.99999999999999）：先补一个极小量再取整，钱币四舍五入到分。
   *
   * @param crewExperience 同车加成的经验，另加在 {@code experience} 上
   * @param crewMoney 同车加成的钱币，另加在 {@code money} 上
   */
  private static Reward rounded(
      double experience, double money, double crewExperience, double crewMoney) {
    double bonusExperience = Math.max(0.0, crewExperience);
    double bonusMoney = Math.max(0.0, crewMoney);
    return new Reward(
        (int) Math.floor(Math.max(0.0, experience) + bonusExperience + ROUNDING_EPSILON),
        BigDecimal.valueOf(Math.max(0.0, money) + bonusMoney)
            .setScale(2, RoundingMode.HALF_UP)
            .doubleValue(),
        bonusExperience > 0.0 || bonusMoney > 0.0);
  }

  private static final double ROUNDING_EPSILON = 1.0e-9;

  /** 一趟的里程、停站与系数：经验与钱币用同一个式子，只是每公里、每站的数不同。 */
  private record Basis(
      double manualKm,
      int manualStops,
      double atoKm,
      int atoStops,
      double atoMultiplier,
      double gradeMultiplier) {

    double amount(double perKm, double perStop) {
      return gradeMultiplier
          * (manualKm * perKm
              + manualStops * perStop
              + atoMultiplier * (atoKm * perKm + atoStops * perStop));
    }
  }

  /** 钱币数额写进命令与提示：两位小数，去掉末尾的 0。 */
  public static String formatAmount(double amount) {
    return BigDecimal.valueOf(amount)
        .setScale(2, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString();
  }
}
