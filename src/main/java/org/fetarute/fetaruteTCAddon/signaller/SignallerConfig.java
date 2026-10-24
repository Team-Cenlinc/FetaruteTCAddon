package org.fetarute.fetaruteTCAddon.signaller;

import java.time.Duration;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;

/**
 * 信号员配置（{@code config.yml} 的 {@code signaller} 段）。
 *
 * @param enabled 是否启用信号员：关闭时控制台只读、没有人工控制
 * @param autoRouteSeconds 车在控制区入口等许可多少秒后由系统代排；限在 {@value #MIN_AUTO_ROUTE_SECONDS}–{@value
 *     #MAX_AUTO_ROUTE_SECONDS} 秒：等待一定有上限，健康层的豁免才不会变成无限期扣车
 * @param consoleRangeBlocks 离控制台多少格以内算在岗
 * @param graceSeconds 离开控制台范围后多少秒下岗、控制区回到自动
 * @param maxHoldSeconds 扣车最长多少秒，到时自动放行；不超过 {@value #HOLD_CEILING_SECONDS} 秒。发车锁从到站起最长 180 秒，
 *     到站后过了一阵才扣的车可能被发车锁提前放走
 * @param callTimeoutSeconds 驾驶员的呼叫多少秒没回复算漏接
 * @param noticeSeconds 控制台临时提示显示多少秒
 * @param approachLockSeconds 接近锁闭：车已在接近或停在入口时取消它的进路，这个入口要过多少秒才能再排进路；0 为不锁，限 0–{@value
 *     #MAX_APPROACH_LOCK_SECONDS} 秒
 * @param skipMinDelaySeconds 晚点至少多少秒才能令越站；限 0–{@value #MAX_SKIP_MIN_DELAY_SECONDS} 秒
 */
public record SignallerConfig(
    boolean enabled,
    int autoRouteSeconds,
    int consoleRangeBlocks,
    int graceSeconds,
    int maxHoldSeconds,
    int callTimeoutSeconds,
    int noticeSeconds,
    int approachLockSeconds,
    int skipMinDelaySeconds) {

  /** 越站晚点门槛的默认值（秒）。 */
  public static final int DEFAULT_SKIP_MIN_DELAY_SECONDS = 180;

  /** 越站晚点门槛的上限（秒）：与时刻表的晚点上限相同。 */
  public static final int MAX_SKIP_MIN_DELAY_SECONDS = 900;

  /** 越站门槛取默认值。 */
  public SignallerConfig(
      boolean enabled,
      int autoRouteSeconds,
      int consoleRangeBlocks,
      int graceSeconds,
      int maxHoldSeconds,
      int callTimeoutSeconds,
      int noticeSeconds,
      int approachLockSeconds) {
    this(
        enabled,
        autoRouteSeconds,
        consoleRangeBlocks,
        graceSeconds,
        maxHoldSeconds,
        callTimeoutSeconds,
        noticeSeconds,
        approachLockSeconds,
        DEFAULT_SKIP_MIN_DELAY_SECONDS);
  }

  /** 扣车上限的上限：与按表扣车的上限相同（发车锁从到站起最长 180 秒，留出到站后的停站时间）。 */
  public static final int HOLD_CEILING_SECONDS = 150;

  /** 代排等待的下限（秒）。 */
  public static final int MIN_AUTO_ROUTE_SECONDS = 5;

  /** 代排等待的上限（秒）。 */
  public static final int MAX_AUTO_ROUTE_SECONDS = 300;

  /** 接近锁闭的上限（秒）。 */
  public static final int MAX_APPROACH_LOCK_SECONDS = 180;

  public SignallerConfig {
    autoRouteSeconds =
        Math.max(MIN_AUTO_ROUTE_SECONDS, Math.min(MAX_AUTO_ROUTE_SECONDS, autoRouteSeconds));
    consoleRangeBlocks = Math.max(1, consoleRangeBlocks);
    graceSeconds = Math.max(0, graceSeconds);
    maxHoldSeconds = Math.max(1, Math.min(HOLD_CEILING_SECONDS, maxHoldSeconds));
    callTimeoutSeconds = Math.max(1, callTimeoutSeconds);
    noticeSeconds = Math.max(1, noticeSeconds);
    approachLockSeconds = Math.max(0, Math.min(MAX_APPROACH_LOCK_SECONDS, approachLockSeconds));
    skipMinDelaySeconds = Math.max(0, Math.min(MAX_SKIP_MIN_DELAY_SECONDS, skipMinDelaySeconds));
  }

  public static SignallerConfig defaults() {
    return new SignallerConfig(true, 20, 32, 30, 150, 60, 3, 30);
  }

  /** 代排等待时长。 */
  public Duration autoRoute() {
    return Duration.ofSeconds(autoRouteSeconds);
  }

  /**
   * 读配置段；缺的键用默认值，越界的值截到范围内并告警。
   *
   * @param section {@code signaller} 段；没有时全用默认值
   * @param warn 告警
   */
  public static SignallerConfig from(ConfigurationSection section, Consumer<String> warn) {
    SignallerConfig defaults = defaults();
    if (section == null) {
      return defaults;
    }
    int maxHold = section.getInt("max-hold-seconds", defaults.maxHoldSeconds());
    if (maxHold > HOLD_CEILING_SECONDS && warn != null) {
      warn.accept(
          "signaller.max-hold-seconds 超过上限 "
              + HOLD_CEILING_SECONDS
              + " 秒，已按 "
              + HOLD_CEILING_SECONDS
              + " 秒处理");
    }
    return new SignallerConfig(
        section.getBoolean("enabled", defaults.enabled()),
        section.getInt("auto-route-seconds", defaults.autoRouteSeconds()),
        section.getInt("console-range-blocks", defaults.consoleRangeBlocks()),
        section.getInt("grace-seconds", defaults.graceSeconds()),
        maxHold,
        section.getInt("call-timeout-seconds", defaults.callTimeoutSeconds()),
        section.getInt("notice-seconds", defaults.noticeSeconds()),
        section.getInt("approach-lock-seconds", defaults.approachLockSeconds()),
        section.getInt("skip-min-delay-seconds", defaults.skipMinDelaySeconds()));
  }
}
