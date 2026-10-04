package org.fetarute.fetaruteTCAddon.drive.driver;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopWindow;
import org.fetarute.fetaruteTCAddon.drive.seat.CabSeats;

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
 * @param stopAcceptBlocks 站停时列车离停车点多近可以开门（格）；停短更多时须前移
 * @param stopSkipBlocks 站停时越过停车点超过这么远（格）算越站：本站不停，列车继续开
 * @param stopMarker 进站时在驾驶员该停的位置显示发光停车标（只有驾驶员本人看得见）
 * @param pickupWaitSeconds 始发站待命车、车库出车等驾驶员上车接班最多等多久（秒），过时照常发车
 * @param pickupTeleport 等驾驶员接班时提供“前往列车”传送（送到车头驾驶室旁，不塞进座位）
 * @param recovery 驾驶任务与拥堵恢复的参数
 * @param guidance 行车引导（Boss 栏、建议速度、开始制动提示）的参数
 * @param cabSeatNames 驾驶座名单：座位附件的名字（TrainCarts 附件编辑器里设置）在名单里即为驾驶座，已转为小写；为空时不认标记
 * @param cabChange 折返换端的时间参数
 * @param pickupAdvanceSeconds 领了从终点站出发的车次后，提前多少秒按交路认出担当的待命车、通知驾驶员并留车（驾驶员可提前上车准备）
 * @param recordRetentionDays 驾驶记录保留多少天，超过的定时删除；0 表示一直保留
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
    double stopSkipBlocks,
    boolean stopMarker,
    int pickupWaitSeconds,
    boolean pickupTeleport,
    DriverRecovery recovery,
    DriverGuidanceConfig guidance,
    List<String> cabSeatNames,
    CabChangeConfig cabChange,
    int pickupAdvanceSeconds,
    int recordRetentionDays) {

  private static final int TICKS_PER_SECOND = 20;

  /** 默认的驾驶座名单。 */
  static final List<String> DEFAULT_CAB_SEAT_NAMES = List.of("driver", "cab", "驾驶", "驾驶座", "驾驶室");

  /** 接车最多等多久（秒）：车库扣车的发车门控 180 秒后自动失效，留出余量。 */
  static final int MAX_PICKUP_WAIT_SECONDS = 150;

  public DriverConfig {
    recovery = recovery == null ? DriverRecovery.defaults() : recovery;
    guidance = guidance == null ? DriverGuidanceConfig.defaults() : guidance;
    cabSeatNames =
        List.copyOf(cabSeatNames == null ? DEFAULT_CAB_SEAT_NAMES : normalizeNames(cabSeatNames));
    cabChange = cabChange == null ? CabChangeConfig.defaults() : cabChange;
    pickupAdvanceSeconds = Math.max(0, pickupAdvanceSeconds);
    recordRetentionDays = Math.max(0, recordRetentionDays);
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
        StopWindow.DEFAULTS.accurateBlocks(),
        StopWindow.DEFAULTS.acceptBlocks(),
        StopWindow.DEFAULTS.skipBlocks(),
        true,
        90,
        true,
        DriverRecovery.defaults(),
        DriverGuidanceConfig.defaults(),
        DEFAULT_CAB_SEAT_NAMES,
        CabChangeConfig.defaults(),
        300,
        30);
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
    double stopSkip = positive(section, "skip-station-blocks", d.stopSkipBlocks, sink);
    if (!StopWindow.valid(stopAccurate, stopAccept, stopSkip)) {
      sink.accept(
          "drive.yml 的 driver.stop-accurate-blocks < stop-accept-blocks < skip-station-blocks"
              + " 不成立，使用默认值");
      stopAccurate = d.stopAccurateBlocks;
      stopAccept = d.stopAcceptBlocks;
      stopSkip = d.stopSkipBlocks;
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
        stopSkip,
        section.getBoolean("stop-marker", d.stopMarker),
        pickupWait(section, d.pickupWaitSeconds, sink),
        section.getBoolean("pickup-teleport", d.pickupTeleport),
        DriverRecovery.from(section, sink),
        DriverGuidanceConfig.from(section, sink),
        cabSeatNames(section, d.cabSeatNames, sink),
        CabChangeConfig.from(section, sink),
        (int)
            Math.round(
                nonNegative(section, "pickup-advance-seconds", d.pickupAdvanceSeconds, sink)),
        (int)
            Math.round(nonNegative(section, "record-retention-days", d.recordRetentionDays, sink)));
  }

  /** 驾驶座名单：须是字符串列表；写成别的形式时提示并用默认名单，写成空列表表示不认标记。 */
  private static List<String> cabSeatNames(
      ConfigurationSection section, List<String> fallback, Consumer<String> warn) {
    if (!section.contains("cab-seat-names")) {
      return fallback;
    }
    if (!section.isList("cab-seat-names")) {
      warn.accept("drive.yml 的 driver.cab-seat-names 须是名字列表，使用默认值 " + fallback);
      return fallback;
    }
    return section.getStringList("cab-seat-names");
  }

  /** 名单去空白、转小写、去重，保持原顺序。 */
  private static List<String> normalizeNames(List<String> names) {
    List<String> normalized = new ArrayList<>(names.size());
    for (String name : names) {
      String key = CabSeats.normalize(name);
      if (!key.isEmpty() && !normalized.contains(key)) {
        normalized.add(key);
      }
    }
    return normalized;
  }

  /** 站停的停车窗口。 */
  public StopWindow stopWindow() {
    return StopWindow.valid(stopAccurateBlocks, stopAcceptBlocks, stopSkipBlocks)
        ? new StopWindow(stopAccurateBlocks, stopAcceptBlocks, stopSkipBlocks)
        : StopWindow.DEFAULTS;
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

  private static int pickupWait(ConfigurationSection section, int fallback, Consumer<String> warn) {
    int wait = (int) Math.round(positive(section, "pickup-wait-seconds", fallback, warn));
    if (wait > MAX_PICKUP_WAIT_SECONDS) {
      warn.accept(
          "drive.yml 的 driver.pickup-wait-seconds 不能超过 "
              + MAX_PICKUP_WAIT_SECONDS
              + "（车库扣车的发车门控会先失效），按 "
              + MAX_PICKUP_WAIT_SECONDS
              + " 处理");
      return MAX_PICKUP_WAIT_SECONDS;
    }
    return wait;
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
