package org.fetarute.fetaruteTCAddon.drive.driver;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;
import org.fetarute.fetaruteTCAddon.drive.driver.score.ScoreRules;

/**
 * 驾驶奖励（{@code drive.yml} 的 {@code rewards} 段）：调度任务按驾驶距离与停站数发经验与服务器钱币。
 *
 * <p>开到终点站全额，中途结束、被收回按已开的部分；卡住被收回、超过任务时限、越站交还不发；路考与练习不发。ATO 运行的那段按 {@code atoMultiplier} 发，再按评级乘系数。
 *
 * @param enabled 是否发奖励
 * @param experiencePerKm 每公里（1000 格）给多少经验
 * @param experiencePerStop 每停一站给多少经验
 * @param moneyPerKm 每公里给多少钱币
 * @param moneyPerStop 每停一站给多少钱币
 * @param atoMultiplier ATO 运行的那段（里程与停站）按这个比例发
 * @param gradeMultipliers 按评级乘的系数；没写的评级按 1
 * @param moneyCommand 没有 Vault 时发钱币的控制台命令（{@code {player}} 玩家名、{@code {amount}} 数额）；为空时不发钱币
 */
public record DriveRewardConfig(
    boolean enabled,
    double experiencePerKm,
    double experiencePerStop,
    double moneyPerKm,
    double moneyPerStop,
    double atoMultiplier,
    Map<ScoreRules.Grade, Double> gradeMultipliers,
    String moneyCommand) {

  public DriveRewardConfig {
    experiencePerKm = Math.max(0.0, experiencePerKm);
    experiencePerStop = Math.max(0.0, experiencePerStop);
    moneyPerKm = Math.max(0.0, moneyPerKm);
    moneyPerStop = Math.max(0.0, moneyPerStop);
    atoMultiplier = Math.max(0.0, atoMultiplier);
    gradeMultipliers = gradeMultipliers == null ? Map.of() : Map.copyOf(gradeMultipliers);
    moneyCommand = moneyCommand == null ? "" : moneyCommand.trim();
  }

  /** 内置默认值。 */
  public static DriveRewardConfig defaults() {
    Map<ScoreRules.Grade, Double> grades = new EnumMap<>(ScoreRules.Grade.class);
    grades.put(ScoreRules.Grade.S, 1.5);
    grades.put(ScoreRules.Grade.A, 1.2);
    grades.put(ScoreRules.Grade.B, 1.0);
    grades.put(ScoreRules.Grade.C, 0.8);
    grades.put(ScoreRules.Grade.D, 0.5);
    return new DriveRewardConfig(
        true, 10.0, 2.0, 20.0, 5.0, 0.5, grades, "eco give {player} {amount}");
  }

  /** 这个评级乘的系数；没写的按 1。 */
  public double gradeMultiplier(ScoreRules.Grade grade) {
    return grade == null ? 1.0 : gradeMultipliers.getOrDefault(grade, 1.0);
  }

  /**
   * 从 {@code rewards} 段解析；段缺失时返回默认值，非法项回退默认值并提示。
   *
   * @param section {@code drive.yml} 的 {@code rewards} 段，可为空
   */
  public static DriveRewardConfig from(ConfigurationSection section, Consumer<String> warn) {
    DriveRewardConfig d = defaults();
    if (section == null) {
      return d;
    }
    Consumer<String> sink = warn != null ? warn : message -> {};
    Map<ScoreRules.Grade, Double> grades = new EnumMap<>(ScoreRules.Grade.class);
    grades.putAll(d.gradeMultipliers);
    ConfigurationSection gradeSection = section.getConfigurationSection("grade-multipliers");
    if (gradeSection != null) {
      for (String key : gradeSection.getKeys(false)) {
        ScoreRules.Grade grade;
        try {
          grade = ScoreRules.Grade.valueOf(key.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
          sink.accept("drive.yml 的 rewards.grade-multipliers 里没有评级 " + key + "，已忽略");
          continue;
        }
        double value = gradeSection.getDouble(key, Double.NaN);
        if (!Double.isFinite(value) || value < 0.0) {
          sink.accept("drive.yml 的 rewards.grade-multipliers." + key + " 不能为负数，使用默认值");
          continue;
        }
        grades.put(grade, value);
      }
    }
    return new DriveRewardConfig(
        section.getBoolean("enabled", d.enabled),
        nonNegative(section, "experience-per-km", d.experiencePerKm, sink),
        nonNegative(section, "experience-per-stop", d.experiencePerStop, sink),
        nonNegative(section, "money-per-km", d.moneyPerKm, sink),
        nonNegative(section, "money-per-stop", d.moneyPerStop, sink),
        nonNegative(section, "ato-multiplier", d.atoMultiplier, sink),
        grades,
        section.getString("money-command", d.moneyCommand));
  }

  private static double nonNegative(
      ConfigurationSection section, String key, double fallback, Consumer<String> warn) {
    if (!section.contains(key)) {
      return fallback;
    }
    double value = section.getDouble(key, Double.NaN);
    if (!Double.isFinite(value) || value < 0.0) {
      warn.accept("drive.yml 的 rewards." + key + " 不能为负数，使用默认值 " + fallback);
      return fallback;
    }
    return value;
  }
}
