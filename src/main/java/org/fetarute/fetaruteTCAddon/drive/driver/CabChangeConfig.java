package org.fetarute.fetaruteTCAddon.drive.driver;

import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;

/**
 * 折返换端的时间参数（{@code drive.yml} 的 {@code driver.cab-change} 段）。
 *
 * <p>换端时间预留 = 基础余量 + 车身长度 ÷ 步行速度：驾驶员下车、沿站台走到另一端、坐进驾驶室所需的时间，车越长留得越多。
 * 只用于驾驶员换端的时限与制动试验判定，不进编表：自动运行的列车不需要有人走过去。
 *
 * @param baseSeconds 基础余量（秒）：离座、上车、坐下与确认的时间
 * @param walkSpeedBps 步行速度（格/秒）
 * @param brakeTestSeconds simulation 级换端后重做一次制动试验大约需要的时间（秒）
 */
public record CabChangeConfig(double baseSeconds, double walkSpeedBps, double brakeTestSeconds) {

  /** 内置默认值。 */
  public static CabChangeConfig defaults() {
    return new CabChangeConfig(15.0, 4.0, 30.0);
  }

  /**
   * 换端时间预留（整秒，向上取整）。
   *
   * @param bodyLengthBlocks 车身沿轨道的长度（格）；非法值按 0 计
   */
  public long reserveSeconds(double bodyLengthBlocks) {
    double length = Double.isFinite(bodyLengthBlocks) ? Math.max(0.0, bodyLengthBlocks) : 0.0;
    return (long) Math.ceil(baseSeconds + length / walkSpeedBps);
  }

  /** 制动试验所需时间（整秒，向上取整）。 */
  public long brakeTestWholeSeconds() {
    return (long) Math.ceil(brakeTestSeconds);
  }

  /**
   * 从 {@code driver} 段的 {@code cab-change} 子段解析；缺失时用默认值，非法项回退默认值并提示。
   *
   * @param driverSection {@code drive.yml} 的 {@code driver} 段，可为空
   */
  public static CabChangeConfig from(ConfigurationSection driverSection, Consumer<String> warn) {
    CabChangeConfig d = defaults();
    ConfigurationSection section =
        driverSection == null ? null : driverSection.getConfigurationSection("cab-change");
    if (section == null) {
      return d;
    }
    Consumer<String> sink = warn != null ? warn : message -> {};
    return new CabChangeConfig(
        read(section, "base-seconds", d.baseSeconds, true, sink),
        read(section, "walk-speed-bps", d.walkSpeedBps, false, sink),
        read(section, "brake-test-seconds", d.brakeTestSeconds, true, sink));
  }

  private static double read(
      ConfigurationSection section,
      String key,
      double fallback,
      boolean zeroAllowed,
      Consumer<String> warn) {
    if (!section.contains(key)) {
      return fallback;
    }
    double value = section.getDouble(key, Double.NaN);
    boolean valid = Double.isFinite(value) && (zeroAllowed ? value >= 0.0 : value > 0.0);
    if (!valid) {
      warn.accept(
          "drive.yml 的 driver.cab-change."
              + key
              + (zeroAllowed ? " 不能为负数" : " 必须为正数")
              + "，使用默认值 "
              + fallback);
      return fallback;
    }
    return value;
  }
}
