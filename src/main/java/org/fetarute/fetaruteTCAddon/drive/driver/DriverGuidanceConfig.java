package org.fetarute.fetaruteTCAddon.drive.driver;

import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;

/**
 * 驾驶调度列车时的行车引导参数（{@code drive.yml} 的 {@code driver.guidance} 段）。速度单位为格/秒。
 *
 * @param bossBar 是否用 Boss 栏显示前方目标、距离与建议速度
 * @param rangeBlocks Boss 栏满格对应的距离（格）；更远的目标不显示
 * @param adviceBrakeFraction 建议速度按常用全制动的这个比例反推（编表同一条 S 形曲线），余下的留给驾驶员修正反应迟滞；1 即与编表完全相同，人跟不住
 * @param brakeAdviceToleranceBps 车速超过建议速度多少时提示开始制动
 */
public record DriverGuidanceConfig(
    boolean bossBar,
    double rangeBlocks,
    double adviceBrakeFraction,
    double brakeAdviceToleranceBps) {

  /** 内置默认值。 */
  public static DriverGuidanceConfig defaults() {
    return new DriverGuidanceConfig(true, 400.0, 0.85, 0.3);
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
    double fraction = section.getDouble("advice-brake-fraction", d.adviceBrakeFraction);
    if (!Double.isFinite(fraction) || fraction < 0.5 || fraction > 1.0) {
      sink.accept(
          "drive.yml 的 driver.guidance.advice-brake-fraction 须在 0.5–1 之间，使用默认值 "
              + d.adviceBrakeFraction);
      fraction = d.adviceBrakeFraction;
    }
    return new DriverGuidanceConfig(
        section.getBoolean("boss-bar", d.bossBar),
        positive(section, "range-blocks", d.rangeBlocks, sink),
        fraction,
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
