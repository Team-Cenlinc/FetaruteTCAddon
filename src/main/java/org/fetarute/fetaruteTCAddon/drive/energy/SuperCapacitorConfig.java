package org.fetarute.fetaruteTCAddon.drive.energy;

import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;

/**
 * 超级电容（受电方式 {@code supercap}）的参数（{@code drive.yml} 的 {@code supercap} 段）。
 *
 * <p>能量按每单位车重计（格²/秒²，1 格 = 1 米时即 J/kg），与车重无关：加速到 v 需要 v²/2，所以电量可以直接和“起步几次”比较。
 *
 * @param capacityKwhPerTon 每吨车重的可用电量（kWh/t）
 * @param fullChargeSeconds 从空到满的充电时间（秒）
 * @param tractionEfficiency 牵引效率：电容放出的电能有多少变成动能（0–1）
 * @param regenEfficiency 再生制动：常用制动在退出速度以上回收制动能量的比例（0–1）
 * @param regenCutoffBps 电制动退出速度（格/秒），低于它没有再生
 * @param auxDrainPerSecond 辅助负载（空调、照明）每秒消耗满电的比例
 * @param lowFraction 电量低于这个比例时预警
 */
public record SuperCapacitorConfig(
    double capacityKwhPerTon,
    double fullChargeSeconds,
    double tractionEfficiency,
    double regenEfficiency,
    double regenCutoffBps,
    double auxDrainPerSecond,
    double lowFraction) {

  /** 1 kWh/t 换算成每单位车重的能量（格²/秒²）。 */
  static final double ENERGY_PER_KWH_PER_TON = 3600.0;

  private static final double KMH_PER_BPS = 3.6;

  /** 内置默认值：0.2 kWh/t 约等于满电从静止加速到 80 km/h 两次半（不计再生），实际约够三个站间。 */
  public static SuperCapacitorConfig defaults() {
    return new SuperCapacitorConfig(0.2, 30.0, 0.85, 0.5, 15.0 / KMH_PER_BPS, 0.01 / 60.0, 0.2);
  }

  /** 满电时的能量（格²/秒²）。 */
  public double capacityEnergy() {
    return capacityKwhPerTon * ENERGY_PER_KWH_PER_TON;
  }

  /**
   * 从 {@code supercap} 段解析；段缺失时返回默认值，非法项回退默认值并提示。
   *
   * @param section {@code drive.yml} 的 {@code supercap} 段，可为空
   */
  public static SuperCapacitorConfig from(ConfigurationSection section, Consumer<String> warn) {
    SuperCapacitorConfig d = defaults();
    if (section == null) {
      return d;
    }
    Consumer<String> sink = warn != null ? warn : message -> {};
    return new SuperCapacitorConfig(
        positive(section, "capacity-kwh-per-ton", d.capacityKwhPerTon, sink),
        positive(section, "full-charge-seconds", d.fullChargeSeconds, sink),
        ratio(section, "traction-efficiency", d.tractionEfficiency, false, sink),
        ratio(section, "regen-efficiency", d.regenEfficiency, true, sink),
        nonNegative(section, "regen-cutoff-kmh", d.regenCutoffBps * KMH_PER_BPS, sink)
            / KMH_PER_BPS,
        nonNegative(section, "aux-drain-percent-per-minute", d.auxDrainPerSecond * 6000.0, sink)
            / 6000.0,
        ratio(section, "low-percent", d.lowFraction * 100.0, true, 100.0, sink) / 100.0);
  }

  private static double positive(
      ConfigurationSection section, String key, double fallback, Consumer<String> warn) {
    double value = section.getDouble(key, fallback);
    if (!Double.isFinite(value) || value <= 0.0) {
      warn.accept("drive.yml 的 supercap." + key + " 必须为正数，使用默认值 " + fallback);
      return fallback;
    }
    return value;
  }

  private static double nonNegative(
      ConfigurationSection section, String key, double fallback, Consumer<String> warn) {
    double value = section.getDouble(key, fallback);
    if (!Double.isFinite(value) || value < 0.0) {
      warn.accept("drive.yml 的 supercap." + key + " 不能为负数，使用默认值 " + fallback);
      return fallback;
    }
    return value;
  }

  private static double ratio(
      ConfigurationSection section,
      String key,
      double fallback,
      boolean zeroAllowed,
      Consumer<String> warn) {
    return ratio(section, key, fallback, zeroAllowed, 1.0, warn);
  }

  private static double ratio(
      ConfigurationSection section,
      String key,
      double fallback,
      boolean zeroAllowed,
      double max,
      Consumer<String> warn) {
    double value = section.getDouble(key, fallback);
    boolean low = zeroAllowed ? value < 0.0 : value <= 0.0;
    if (!Double.isFinite(value) || low || value > max) {
      warn.accept("drive.yml 的 supercap." + key + " 须在 0–" + (int) max + " 之间，使用默认值 " + fallback);
      return fallback;
    }
    return value;
  }
}
