package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 构建时刻表的参数。
 *
 * <p>全部是运营口径的输入，没有一个来自历史跑车记录：时刻表是按这些参数 + 当前路网算出来的。
 *
 * @param serviceStartSecondOfDay 首班发车时刻（当日秒数）
 * @param serviceEndSecondOfDay 末班发车时刻（相对同一服务日的秒数，可超过一天表示跨零点）
 * @param headway 兜底发车间隔：交路组没有自己的间隔时用它；每个方向按各自的间隔铺规整子网格，weight 只在同方向多 route 之间切份额
 * @param defaultDwell RouteStop 未配置停站时长时的缺省值
 * @param dutyLimits 车辆交路硬上限
 * @param tripCodePrefix 车次号前缀
 * @param zoneId 发车时刻所用时区
 * @param separation 冲突检查里相邻占用之间的最小间隔（边、道岔、站台）
 * @param strictConflicts 目标 headway 排出来有冲突时：{@code true} 构建失败；{@code false} 回退到最小可行 headway 并警告
 * @param groupIntervals 交路组 → 该组每个方向的发车间隔（秒）；没列出的组用 {@code headway}
 * @param repair 让车修复参数（单处上限、累计上限）
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
    boolean strictConflicts,
    Map<String, Integer> groupIntervals,
    Repair repair) {

  /**
   * 让车修复参数。
   *
   * @param maxWait 单处让车上限；零关闭修复。默认 60 s，不能超过运行时的 {@code timetable.hold-max-seconds}（那是车真能被扣留的上限）
   * @param tolerance 同一班累计让车上限，超过即截断交路；对应运行时的 assign-tolerance
   */
  public record Repair(Duration maxWait, Duration tolerance) {

    /** 默认单处让车上限。 */
    public static final int DEFAULT_MAX_WAIT_SECONDS = 60;

    /** 默认累计上限，与 {@code timetable.assign-tolerance-seconds} 的默认值一致。 */
    public static final int DEFAULT_TOLERANCE_SECONDS = 300;

    public Repair {
      maxWait =
          maxWait == null || maxWait.isNegative()
              ? Duration.ofSeconds(DEFAULT_MAX_WAIT_SECONDS)
              : maxWait;
      tolerance =
          tolerance == null || tolerance.isNegative()
              ? Duration.ofSeconds(DEFAULT_TOLERANCE_SECONDS)
              : tolerance;
    }

    public static Repair defaults() {
      return new Repair(
          Duration.ofSeconds(DEFAULT_MAX_WAIT_SECONDS),
          Duration.ofSeconds(DEFAULT_TOLERANCE_SECONDS));
    }

    /** 关闭修复：冲突原样上报。 */
    public static Repair none() {
      return new Repair(Duration.ZERO, Duration.ofSeconds(DEFAULT_TOLERANCE_SECONDS));
    }

    public int maxWaitSeconds() {
      return (int) Math.min(Integer.MAX_VALUE, maxWait.toSeconds());
    }

    public int toleranceSeconds() {
      return (int) Math.min(Integer.MAX_VALUE, tolerance.toSeconds());
    }
  }

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
    Map<String, Integer> intervals = new TreeMap<>();
    if (groupIntervals != null) {
      groupIntervals.forEach(
          (group, seconds) -> {
            if (group != null && !group.isBlank() && seconds != null && seconds > 0) {
              intervals.put(group, seconds);
            }
          });
    }
    groupIntervals = Collections.unmodifiableMap(intervals);
    repair = repair == null ? Repair.defaults() : repair;
  }

  /** 没有让车参数的构造：默认 60 s / 300 s。 */
  public TimetableBuildOptions(
      int serviceStartSecondOfDay,
      int serviceEndSecondOfDay,
      Duration headway,
      Duration defaultDwell,
      VehicleDutyPlanner.Limits dutyLimits,
      String tripCodePrefix,
      ZoneId zoneId,
      Duration separation,
      boolean strictConflicts,
      Map<String, Integer> groupIntervals) {
    this(
        serviceStartSecondOfDay,
        serviceEndSecondOfDay,
        headway,
        defaultDwell,
        dutyLimits,
        tripCodePrefix,
        zoneId,
        separation,
        strictConflicts,
        groupIntervals,
        Repair.defaults());
  }

  /** 没有按组间隔的构造：所有组都用兜底间隔。 */
  public TimetableBuildOptions(
      int serviceStartSecondOfDay,
      int serviceEndSecondOfDay,
      Duration headway,
      Duration defaultDwell,
      VehicleDutyPlanner.Limits dutyLimits,
      String tripCodePrefix,
      ZoneId zoneId,
      Duration separation,
      boolean strictConflicts) {
    this(
        serviceStartSecondOfDay,
        serviceEndSecondOfDay,
        headway,
        defaultDwell,
        dutyLimits,
        tripCodePrefix,
        zoneId,
        separation,
        strictConflicts,
        Map.of());
  }

  /** 某个组的间隔：配了用配的，没配用兜底。 */
  public int intervalFor(String group) {
    Integer configured = group == null ? null : groupIntervals.get(group);
    return configured != null ? configured : (int) Math.max(1L, headway.toSeconds());
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

  /** 换一个兜底 headway、清掉按组间隔，其余不变。 */
  public TimetableBuildOptions withHeadway(Duration nextHeadway) {
    return withIntervals(nextHeadway, Map.of());
  }

  /** 换兜底间隔与按组间隔，其余不变。搜索放宽时按同一比例改这两样。 */
  public TimetableBuildOptions withIntervals(Duration nextHeadway, Map<String, Integer> intervals) {
    return new TimetableBuildOptions(
        serviceStartSecondOfDay,
        serviceEndSecondOfDay,
        nextHeadway,
        defaultDwell,
        dutyLimits,
        tripCodePrefix,
        zoneId,
        separation,
        strictConflicts,
        intervals,
        repair);
  }

  /** 换让车参数，其余不变。 */
  public TimetableBuildOptions withRepair(Repair nextRepair) {
    return new TimetableBuildOptions(
        serviceStartSecondOfDay,
        serviceEndSecondOfDay,
        headway,
        defaultDwell,
        dutyLimits,
        tripCodePrefix,
        zoneId,
        separation,
        strictConflicts,
        groupIntervals,
        nextRepair);
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
