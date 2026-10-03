package org.fetarute.fetaruteTCAddon.drive.driver;

import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopAlignment;

/**
 * 驾驶调度列车（DRIVER 模式）的参数（{@code drive.yml} 的 {@code driver} 段）。速度单位为格/秒。
 *
 * @param enabled 是否允许驾驶调度列车；关闭并重载时立即把所有驾驶员控制的车交还自动运行
 * @param hotHandover 接管时按热车交接：受电、主断、辅助电源、风压就绪，制动试验视为已做
 * @param overspeedToleranceBps 超过容许速度多少开始常用制动
 * @param serviceReleaseHysteresisBps 常用制动介入后，降到容许速度以下多少才松开
 * @param emergencyOverspeedRatio 紧急制动线比常用制动线再高出容许速度的这个比例（不少于容差）
 * @param restrictedSpeedBps 指令过期或尚未收到指令时的限制速度
 * @param directiveStaleTicks 运行中多久没收到新指令算过期（tick）
 * @param staleHandbackTicks 运行中多久没收到新指令就停车交还自动运行（tick）
 * @param stopMarginBlocks 停车点、授权末端前留出的余量（格）
 * @param stopAccurateBlocks 站停时列车中心离停车点多近算停准（格）
 * @param stopAcceptBlocks 站停时列车中心离停车点多近可以开门（格）；越过更多时防护强制停车
 * @param recovery 驾驶任务与拥堵恢复的参数
 */
public record DriverConfig(
    boolean enabled,
    boolean hotHandover,
    double overspeedToleranceBps,
    double serviceReleaseHysteresisBps,
    double emergencyOverspeedRatio,
    double restrictedSpeedBps,
    int directiveStaleTicks,
    int staleHandbackTicks,
    double stopMarginBlocks,
    double stopAccurateBlocks,
    double stopAcceptBlocks,
    DriverRecovery recovery) {

  private static final int TICKS_PER_SECOND = 20;

  public DriverConfig {
    recovery = recovery == null ? DriverRecovery.defaults() : recovery;
  }

  /** 内置默认值。 */
  public static DriverConfig defaults() {
    return new DriverConfig(
        true,
        true,
        1.0,
        0.5,
        0.15,
        5.0,
        40,
        200,
        1.0,
        StopAlignment.DEFAULT_ACCURATE_BLOCKS,
        StopAlignment.DEFAULT_ACCEPT_BLOCKS,
        DriverRecovery.defaults());
  }

  /**
   * 从 {@code driver} 段解析；段缺失时返回默认值，非法项回退默认值并提示。
   *
   * @param section {@code drive.yml} 的 {@code driver} 段，可为空
   */
  public static DriverConfig from(ConfigurationSection section, Consumer<String> warn) {
    DriverConfig d = defaults();
    if (section == null) {
      return d;
    }
    Consumer<String> sink = warn != null ? warn : message -> {};
    int staleTicks =
        (int)
            Math.round(
                positive(
                        section,
                        "directive-stale-seconds",
                        d.directiveStaleTicks / (double) TICKS_PER_SECOND,
                        sink)
                    * TICKS_PER_SECOND);
    int handbackTicks =
        (int)
            Math.round(
                positive(
                        section,
                        "stale-handback-seconds",
                        d.staleHandbackTicks / (double) TICKS_PER_SECOND,
                        sink)
                    * TICKS_PER_SECOND);
    if (handbackTicks <= staleTicks) {
      sink.accept("drive.yml 的 driver.stale-handback-seconds 须大于 directive-stale-seconds，使用默认值");
      staleTicks = d.directiveStaleTicks;
      handbackTicks = d.staleHandbackTicks;
    }
    double stopAccurate = positive(section, "stop-accurate-blocks", d.stopAccurateBlocks, sink);
    double stopAccept = positive(section, "stop-accept-blocks", d.stopAcceptBlocks, sink);
    if (stopAccept <= stopAccurate) {
      sink.accept("drive.yml 的 driver.stop-accept-blocks 须大于 stop-accurate-blocks，使用默认值");
      stopAccurate = d.stopAccurateBlocks;
      stopAccept = d.stopAcceptBlocks;
    }
    return new DriverConfig(
        section.getBoolean("enabled", d.enabled),
        section.getBoolean("hot-handover", d.hotHandover),
        nonNegative(section, "overspeed-tolerance-bps", d.overspeedToleranceBps, sink),
        nonNegative(section, "service-release-hysteresis-bps", d.serviceReleaseHysteresisBps, sink),
        positive(section, "emergency-overspeed-ratio", d.emergencyOverspeedRatio, sink),
        positive(section, "restricted-speed-bps", d.restrictedSpeedBps, sink),
        staleTicks,
        handbackTicks,
        nonNegative(section, "stop-margin-blocks", d.stopMarginBlocks, sink),
        stopAccurate,
        stopAccept,
        DriverRecovery.from(section, sink));
  }

  private static double positive(
      ConfigurationSection section, String key, double fallback, Consumer<String> warn) {
    if (!section.contains(key)) {
      return fallback;
    }
    double value = section.getDouble(key, Double.NaN);
    if (!Double.isFinite(value) || value <= 0.0) {
      warn.accept("drive.yml 的 driver." + key + " 必须为正数，使用默认值 " + fallback);
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
      warn.accept("drive.yml 的 driver." + key + " 不能为负数，使用默认值 " + fallback);
      return fallback;
    }
    return value;
  }
}
