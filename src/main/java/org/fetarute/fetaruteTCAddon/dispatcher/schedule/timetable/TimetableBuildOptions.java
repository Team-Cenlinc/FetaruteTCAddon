package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 构建时刻表的参数。
 *
 * <p>全部是运营口径的输入，没有一个来自历史跑车记录：时刻表是按这些参数 + 当前路网算出来的。
 *
 * @param serviceStartSecondOfDay 首班发车时刻（当日秒数）
 * @param serviceEndSecondOfDay 末班发车时刻（相对同一服务日的秒数，可超过一天表示跨零点）
 * @param headway 全线基准发车间隔；它决定总班次数，各 route 的份额再由 weight 切分
 * @param defaultDwell RouteStop 未配置停站时长时的缺省值
 * @param dutyLimits 车辆交路硬上限
 * @param tripCodePrefix 车次号前缀
 * @param zoneId 发车时刻所用时区
 * @param separation 冲突检查里相邻占用之间的最小间隔（边、道岔、站台）
 * @param strictConflicts 目标 headway 排出来有冲突时：{@code true} 构建失败；{@code false} 回退到最小可行 headway 并警告
 */
public record TimetableBuildOptions(
    int serviceStartSecondOfDay,
    int serviceEndSecondOfDay,
    Duration headway,
    Duration defaultDwell,
    VehicleDutyPlanner.Limits dutyLimits,
    String tripCodePrefix,
    ZoneId zoneId,
    Duration separation,
    boolean strictConflicts) {

  /** 默认首班 05:00。 */
  public static final int DEFAULT_SERVICE_START = 5 * 3600;

  /** 默认末班 23:00。 */
  public static final int DEFAULT_SERVICE_END = 23 * 3600;

  /** 默认基准间隔 5 分钟。 */
  public static final int DEFAULT_HEADWAY_SECONDS = 300;

  /** 默认停站 20 秒，与 AutoStation 的缺省一致。 */
  public static final int DEFAULT_DWELL_SECONDS = 20;

  /** 单次构建允许的最大班次数，防止把 headway 写成 1 秒时生成一张无法使用的表。 */
  public static final int MAX_TRIPS = 5000;

  /** 默认相邻占用间隔 30 秒：运行时的 HeadwayRule 是零，这里留一点裕量让表定时分不至于把两趟车贴在一起。 */
  public static final int DEFAULT_SEPARATION_SECONDS = 30;

  public TimetableBuildOptions {
    if (serviceStartSecondOfDay < 0 || serviceStartSecondOfDay >= TimetableTrip.SECONDS_PER_DAY) {
      throw new IllegalArgumentException("serviceStartSecondOfDay 超出一天范围");
    }
    if (serviceEndSecondOfDay < serviceStartSecondOfDay) {
      throw new IllegalArgumentException("末班时刻不能早于首班时刻");
    }
    headway =
        headway == null || headway.isZero() || headway.isNegative()
            ? Duration.ofSeconds(DEFAULT_HEADWAY_SECONDS)
            : headway;
    defaultDwell =
        defaultDwell == null || defaultDwell.isNegative()
            ? Duration.ofSeconds(DEFAULT_DWELL_SECONDS)
            : defaultDwell;
    dutyLimits = dutyLimits == null ? VehicleDutyPlanner.Limits.defaults() : dutyLimits;
    tripCodePrefix =
        tripCodePrefix == null || tripCodePrefix.isBlank() ? "" : tripCodePrefix.trim();
    zoneId = Objects.requireNonNullElse(zoneId, ZoneId.systemDefault());
    separation =
        separation == null || separation.isNegative()
            ? Duration.ofSeconds(DEFAULT_SEPARATION_SECONDS)
            : separation;
  }

  /** 不带冲突检查参数的构造：默认间隔、有冲突时回退而不是失败。 */
  public TimetableBuildOptions(
      int serviceStartSecondOfDay,
      int serviceEndSecondOfDay,
      Duration headway,
      Duration defaultDwell,
      VehicleDutyPlanner.Limits dutyLimits,
      String tripCodePrefix,
      ZoneId zoneId) {
    this(
        serviceStartSecondOfDay,
        serviceEndSecondOfDay,
        headway,
        defaultDwell,
        dutyLimits,
        tripCodePrefix,
        zoneId,
        Duration.ofSeconds(DEFAULT_SEPARATION_SECONDS),
        false);
  }

  /** 默认参数。 */
  public static TimetableBuildOptions defaults(ZoneId zoneId) {
    return new TimetableBuildOptions(
        DEFAULT_SERVICE_START,
        DEFAULT_SERVICE_END,
        Duration.ofSeconds(DEFAULT_HEADWAY_SECONDS),
        Duration.ofSeconds(DEFAULT_DWELL_SECONDS),
        VehicleDutyPlanner.Limits.defaults(),
        "",
        zoneId);
  }

  /** 换一个 headway，其余不变。 */
  public TimetableBuildOptions withHeadway(Duration nextHeadway) {
    return new TimetableBuildOptions(
        serviceStartSecondOfDay,
        serviceEndSecondOfDay,
        nextHeadway,
        defaultDwell,
        dutyLimits,
        tripCodePrefix,
        zoneId,
        separation,
        strictConflicts);
  }

  /** 计划窗口长度（秒）。 */
  public int horizonSeconds() {
    return serviceEndSecondOfDay - serviceStartSecondOfDay;
  }

  /**
   * 计划窗口内的发车时隙数量。
   *
   * <p>含首班与末班两端，因此是 {@code horizon / headway + 1}；上限由 {@link #MAX_TRIPS} 夹住。
   */
  public int slotCount() {
    long headwaySeconds = Math.max(1L, headway.toSeconds());
    long slots = horizonSeconds() / headwaySeconds + 1L;
    return (int) Math.max(0L, Math.min(MAX_TRIPS, slots));
  }
}
