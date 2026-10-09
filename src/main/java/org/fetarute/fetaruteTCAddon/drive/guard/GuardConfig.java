package org.fetarute.fetaruteTCAddon.drive.guard;

import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;

/**
 * 车掌（{@code drive.yml} 的 {@code guard} 段）：车掌坐车尾驾驶室，停站时开关门、下站台监视，门全关后回座确认出站信号、按发车铃。
 *
 * <p>各步时限按秒写；发车铃那一步只累计出站放行着的时间。
 *
 * @param enabled 是否允许当车掌
 * @param openDoorsSeconds 停妥后多久不开门，由站台代开（记一次超时）
 * @param closeDoorsSeconds 停站时间到后多久不关门，由站台代关（记一次超时）
 * @param departSignalSeconds 门全关后、出站放行着累计多久还没按发车铃，传送回座、代发发车信号（记一次超时）
 * @param incidentExtensionSeconds 异常情况报告把当前这一步的时限延长多久
 * @param incidentMaxPerStop 每站最多报告几次
 * @param endAfterTimeouts 连续多少站有超时就结束车掌任务；0 表示不处置
 * @param watchRadiusBlocks 关门监视：离自己那节车门最远几格
 * @param watchAngleDegrees 关门监视：视线水平方向与车身的夹角最大几度（朝车头或车尾都行）
 * @param watchRatio 监视合格的采样至少占几成
 * @param departureWatchExtraBlocks 出站监视：列车走过“车长 + 这么多格”算车尾离开站台
 * @param departureWatchMaxSeconds 出站监视最长几秒
 * @param buzzerLongTicks 发车铃按住多久（tick）算一长
 * @param buzzerDoubleTicks 两次短按相隔多久（tick）以内算呼叫
 * @param ackSeconds simulation 级驾驶员收到发车信号后多久内要回一短
 */
public record GuardConfig(
    boolean enabled,
    int openDoorsSeconds,
    int closeDoorsSeconds,
    int departSignalSeconds,
    int incidentExtensionSeconds,
    int incidentMaxPerStop,
    int endAfterTimeouts,
    double watchRadiusBlocks,
    double watchAngleDegrees,
    double watchRatio,
    double departureWatchExtraBlocks,
    int departureWatchMaxSeconds,
    int buzzerLongTicks,
    int buzzerDoubleTicks,
    int ackSeconds) {

  public GuardConfig {
    openDoorsSeconds = Math.max(1, openDoorsSeconds);
    closeDoorsSeconds = Math.max(1, closeDoorsSeconds);
    departSignalSeconds = Math.max(1, departSignalSeconds);
    incidentExtensionSeconds = Math.max(0, incidentExtensionSeconds);
    incidentMaxPerStop = Math.max(0, incidentMaxPerStop);
    endAfterTimeouts = Math.max(0, endAfterTimeouts);
    watchRadiusBlocks = Math.max(1.0, watchRadiusBlocks);
    watchAngleDegrees = Math.min(90.0, Math.max(1.0, watchAngleDegrees));
    watchRatio = Math.min(1.0, Math.max(0.0, watchRatio));
    departureWatchExtraBlocks = Math.max(0.0, departureWatchExtraBlocks);
    departureWatchMaxSeconds = Math.max(1, departureWatchMaxSeconds);
    buzzerLongTicks = Math.max(4, buzzerLongTicks);
    buzzerDoubleTicks = Math.max(4, buzzerDoubleTicks);
    ackSeconds = Math.max(1, ackSeconds);
  }

  /** 内置默认值。 */
  public static GuardConfig defaults() {
    return new GuardConfig(true, 30, 15, 15, 30, 2, 3, 8.0, 45.0, 0.7, 24.0, 20, 16, 20, 5);
  }

  public long openDoorsTicks() {
    return openDoorsSeconds * 20L;
  }

  public long closeDoorsTicks() {
    return closeDoorsSeconds * 20L;
  }

  public long departSignalTicks() {
    return departSignalSeconds * 20L;
  }

  public long incidentExtensionTicks() {
    return incidentExtensionSeconds * 20L;
  }

  /**
   * 从 {@code guard} 段解析；段缺失时返回默认值，非法项回退默认值并提示。
   *
   * @param section {@code drive.yml} 的 {@code guard} 段，可为空
   */
  public static GuardConfig from(ConfigurationSection section, Consumer<String> warn) {
    GuardConfig d = defaults();
    if (section == null) {
      return d;
    }
    Consumer<String> sink = warn != null ? warn : message -> {};
    return new GuardConfig(
        section.getBoolean("enabled", d.enabled),
        (int) number(section, "open-doors-seconds", d.openDoorsSeconds, 1.0, sink),
        (int) number(section, "close-doors-seconds", d.closeDoorsSeconds, 1.0, sink),
        (int) number(section, "depart-signal-seconds", d.departSignalSeconds, 1.0, sink),
        (int) number(section, "incident-extension-seconds", d.incidentExtensionSeconds, 0.0, sink),
        (int) number(section, "incident-max-per-stop", d.incidentMaxPerStop, 0.0, sink),
        (int) number(section, "end-after-timeouts", d.endAfterTimeouts, 0.0, sink),
        number(section, "watch-radius-blocks", d.watchRadiusBlocks, 1.0, sink),
        number(section, "watch-angle-degrees", d.watchAngleDegrees, 1.0, sink),
        number(section, "watch-ratio", d.watchRatio, 0.0, sink),
        number(section, "departure-watch-extra-blocks", d.departureWatchExtraBlocks, 0.0, sink),
        (int) number(section, "departure-watch-max-seconds", d.departureWatchMaxSeconds, 1.0, sink),
        (int) number(section, "buzzer-long-ticks", d.buzzerLongTicks, 4.0, sink),
        (int) number(section, "buzzer-double-ticks", d.buzzerDoubleTicks, 4.0, sink),
        (int) number(section, "ack-seconds", d.ackSeconds, 1.0, sink));
  }

  private static double number(
      ConfigurationSection section,
      String key,
      double fallback,
      double min,
      Consumer<String> warn) {
    if (!section.contains(key)) {
      return fallback;
    }
    double value = section.getDouble(key, Double.NaN);
    if (!Double.isFinite(value) || value < min) {
      warn.accept("drive.yml 的 guard." + key + " 不能小于 " + min + "，使用默认值 " + fallback);
      return fallback;
    }
    return value;
  }
}
