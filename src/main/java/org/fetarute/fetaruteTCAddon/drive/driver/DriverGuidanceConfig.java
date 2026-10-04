package org.fetarute.fetaruteTCAddon.drive.driver;

import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;

/**
 * 驾驶调度列车时的行车引导参数（{@code drive.yml} 的 {@code driver.guidance} 段）。速度单位为格/秒。
 *
 * @param bossBar 是否用 Boss 栏显示前方目标、距离与建议速度
 * @param rangeBlocks Boss 栏满格对应的距离（格）；更远的目标不显示
 * @param adviceBrakeRatio 建议速度按常用全制动的这个比例（舒适制动）反推
 * @param adviceMarginBps 建议速度比此刻容许速度低多少，免得贴着防护线开
 * @param brakeAdviceToleranceBps 车速超过建议速度多少时提示开始制动
 */
public record DriverGuidanceConfig(
    boolean bossBar,
    double rangeBlocks,
    double adviceBrakeRatio,
    double adviceMarginBps,
    double brakeAdviceToleranceBps) {

  /** 内置默认值。 */
  public static DriverGuidanceConfig defaults() {
    return new DriverGuidanceConfig(true, 400.0, 0.8, 0.5, 0.3);
  }

  /**
   * 从 {@code driver} 段下的 {@code guidance} 子段解析；缺失时返回默认值，非法项回退默认值并提示。
   *
   * @param driverSection {@code drive.yml} 的 {@code driver} 段，可为空
   */
  public static DriverGuidanceConfig from(
      ConfigurationSection driverSection, Consumer<String> warn) {
    DriverGuidanceConfig d = defaults();
    ConfigurationSection section =
        driverSection == null ? null : driverSection.getConfigurationSection("guidance");
    if (section == null) {
      return d;
    }
    Consumer<String> sink = warn != null ? warn : message -> {};
    double ratio = section.getDouble("advice-brake-ratio", d.adviceBrakeRatio);
    if (!Double.isFinite(ratio) || ratio <= 0.0 || ratio > 1.0) {
      sink.accept(
          "drive.yml 的 driver.guidance.advice-brake-ratio 须在 (0, 1] 之内，使用默认值 "
              + d.adviceBrakeRatio);
      ratio = d.adviceBrakeRatio;
    }
    return new DriverGuidanceConfig(
        section.getBoolean("boss-bar", d.bossBar),
        positive(section, "range-blocks", d.rangeBlocks, sink),
        ratio,
        nonNegative(section, "advice-margin-bps", d.adviceMarginBps, sink),
        nonNegative(section, "brake-advice-tolerance-bps", d.brakeAdviceToleranceBps, sink));
  }

  private static double positive(
      ConfigurationSection section, String key, double fallback, Consumer<String> warn) {
    if (!section.contains(key)) {
      return fallback;
    }
    double value = section.getDouble(key, Double.NaN);
    if (!Double.isFinite(value) || value <= 0.0) {
      warn.accept("drive.yml 的 driver.guidance." + key + " 必须为正数，使用默认值 " + fallback);
      return fallback;
    }
    return value;
  }

  private static double nonNegative(
      ConfigurationSection section, String key, double fallback, Consumer<String> warn) {
    if (!section.contains(key)) {
      return fallback;
    }
    double value = section.getDouble(key, Double.NaN);
    if (!Double.isFinite(value) || value < 0.0) {
      warn.accept("drive.yml 的 driver.guidance." + key + " 不能为负数，使用默认值 " + fallback);
      return fallback;
    }
    return value;
  }
}
