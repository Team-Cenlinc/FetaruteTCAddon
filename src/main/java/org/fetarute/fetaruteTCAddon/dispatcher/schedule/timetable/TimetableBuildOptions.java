package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;

/**
 * 构建时刻表的参数。
 *
 * <p>全部是运营口径的输入，没有一个来自历史跑车记录：时刻表是按这些参数 + 当前路网算出来的。
 *
 * @param serviceStartSecondOfDay 首班发车时刻（当日秒数）
 * @param serviceEndSecondOfDay 运营结束时刻（相对同一服务日的秒数，可超过一天表示跨零点）：每一班按名义发车时刻算须在此之前跑完；
 *     端点串行、让车把班次往后推时，实际到达与之后的回库可能略晚于它
 * @param headway 兜底发车间隔：交路组没有自己的间隔时用它；每个方向按各自的间隔铺规整子网格，weight 只在同方向多 route 之间切份额
 * @param defaultDwell RouteStop 未配置停站时长时的缺省值
 * @param dutyLimits 车辆交路硬上限
 * @param tripCodePrefix 车次号前缀
 * @param zoneId 发车时刻所用时区
 * @param separation 冲突检查里相邻占用之间的最小间隔（边、道岔、站台）
 * @param strictConflicts 目标 headway 排出来有冲突时：{@code true} 构建失败；{@code false} 回退到最小可行 headway 并警告
 * @param groupIntervals 交路组 → 该组每个方向的发车间隔（秒）；没列出的组用 {@code headway}
 * @param repair 让车修复参数（单处上限、累计上限）
 * @param rapidStagger 快车错峰搜索：排完之后用完整编表逐个试原地折返端的折返、快车组的平移量与停站，按成品表实测的快车被卡秒数挑位置（慢，见 {@link
 *     RapidStagger}）
 * @param following 运行时同向跟车的规则：给了就按闭塞时间量快车被卡（错峰也按它比），否则按占用区间
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
    Repair repair,
    boolean rapidStagger,
    Following following) {

  /**
   * 运行时同向跟车的规则，编表按它算闭塞时间（{@link BlockingTimes}）：后车多早要用到一段线路、前车多晚才放出来。
   *
   * <p>每一项都取自运行时同一份配置与出车编组，编表与控车用的是同一把尺子，不是编表自己留的裕量。{@link #NONE} 表示不按闭塞时间算， 快车被卡按占用区间量。
   *
   * @param decelBps2 常用制动减速度（格/秒²），按默认车种
   * @param marginBlocks 授权余量：制动距离之外还要空出的格数，即运行时的 {@code max(following-min-clear-blocks,
   *     following-stop-margin-blocks + movement-authority-caution-margin-blocks)}
   * @param rearGuardEdges 车尾之后多保留的边数（{@code rear-guard-edges}）
   * @param tickSeconds 调度 tick 的秒数（{@code dispatch-tick-interval-ticks} / 20）
   * @param trainLengthBlocks 各交路的保守车长（格），按出车编组算，与运行时尾部保护量车身的口径一致；没有的交路车长未知
   */
  public record Following(
      double decelBps2,
      double marginBlocks,
      int rearGuardEdges,
      double tickSeconds,
      Map<UUID, Long> trainLengthBlocks) {

    /** 不按闭塞时间算。 */
    public static final Following NONE = new Following(0.0D, 0.0D, 0, 0.0D, Map.of());

    public Following {
      boolean valid =
          Double.isFinite(decelBps2)
              && decelBps2 > 0.0D
              && Double.isFinite(marginBlocks)
              && marginBlocks >= 0.0D
              && rearGuardEdges >= 0
              && Double.isFinite(tickSeconds)
              && tickSeconds >= 0.0D;
      if (!valid) {
        decelBps2 = 0.0D;
        marginBlocks = 0.0D;
        rearGuardEdges = 0;
        tickSeconds = 0.0D;
      }
      Map<UUID, Long> lengths = new HashMap<>();
      if (valid && trainLengthBlocks != null) {
        trainLengthBlocks.forEach(
            (route, length) -> {
              if (route != null && length != null && length > 0L) {
                lengths.put(route, length);
              }
            });
      }
      trainLengthBlocks = Map.copyOf(lengths);
    }

    /** 是否按闭塞时间算。 */
    public boolean enabled() {
      return decelBps2 > 0.0D;
    }

    /** 这条交路的保守车长；出车编组读不到时为空，前车何时放出线路就算不出来。 */
    public OptionalLong trainLength(UUID routeId) {
      Long length = routeId == null ? null : trainLengthBlocks.get(routeId);
      return length == null ? OptionalLong.empty() : OptionalLong.of(length);
    }

    /** 换一组车长，其它规则不变。 */
    public Following withTrainLengths(Map<UUID, Long> lengths) {
      return new Following(decelBps2, marginBlocks, rearGuardEdges, tickSeconds, lengths);
    }
  }

  /**
   * 让车修复参数。
   *
   * @param maxWait 单处让车上限；零关闭修复。默认 300 s，上限 1800 s
   * @param tolerance 同一班累计让车上限，超过即截断交路；对应运行时的 assign-tolerance，且不小于 {@code maxWait}
   */
  public record Repair(Duration maxWait, Duration tolerance) {

    /**
     * 默认单处让车上限，与 {@code timetable.assign-tolerance-seconds} 的默认值一致。
     *
     * <p>不以 {@code timetable.hold-max-seconds} 封顶，否则是把两种等待混为一谈：{@code hold-max}
     * 约束的是<b>早到的车在站台被扣多久</b>（超了就 {@code HOLD_SKIPPED} 放行）， 而车在资源前排队等待由占用队列做，运行时无界。表上写 300 s
     * 的让车，运行时的行为是"站台扣一段 + 资源前等一段"， 车次绑定按表定时刻 ± tolerance 判偏差，仍然绑得上。
     */
    public static final int DEFAULT_MAX_WAIT_SECONDS = 300;

    /** 单处让车的上限：运行时唯一可能销毁一辆等待列车的阈值（{@code stuck-cleanup-passenger-threshold-seconds}）。 */
    public static final int MAX_WAIT_CEILING_SECONDS = 1800;

    /** 默认累计上限，与 {@code timetable.assign-tolerance-seconds} 的默认值一致。 */
    public static final int DEFAULT_TOLERANCE_SECONDS = 300;

    public Repair {
      maxWait =
          maxWait == null || maxWait.isNegative()
              ? Duration.ofSeconds(DEFAULT_MAX_WAIT_SECONDS)
              : maxWait;
      if (maxWait.toSeconds() > MAX_WAIT_CEILING_SECONDS) {
        maxWait = Duration.ofSeconds(MAX_WAIT_CEILING_SECONDS);
      }
      tolerance =
          tolerance == null || tolerance.isNegative()
              ? Duration.ofSeconds(DEFAULT_TOLERANCE_SECONDS)
              : tolerance;
      if (tolerance.compareTo(maxWait) < 0) {
        // 累计上限小于单步上限时，第一处让车就会把交路截断——那不是"容差"，是配置写错了。
        tolerance = maxWait;
      }
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

  /** 默认运营结束 23:00（末班须在此之前跑完）。 */
  public static final int DEFAULT_SERVICE_END = 23 * 3600;

  /** 默认基准间隔 5 分钟。 */
  public static final int DEFAULT_HEADWAY_SECONDS = 300;

  /** 默认停站，与运行时停车没配 dwell 时的缺省是同一个值（见 {@link RouteStop#DEFAULT_DWELL_SECONDS}）。 */
  public static final int DEFAULT_DWELL_SECONDS = RouteStop.DEFAULT_DWELL_SECONDS;

  /** 单次构建允许的最大班次数，防止把 headway 写成 1 秒时生成一张无法使用的表。 */
  public static final int MAX_TRIPS = 5000;

  /**
   * 默认相邻占用间隔 15 秒。
   *
   * <p>运行时的 {@code HeadwayRule} 是零，真调度器按授权窗口与制动距离放行——这是移动闭塞的形态，不是固定闭塞。
   * 所以这个数只是编表模型自己留的裕量，让表定时分不至于把两趟车贴在一起。
   *
   * <p>30 秒过于保守：繁忙的进站单线上每次通过的实际占用平均只有 5 秒左右，30 秒裕量会占到这条单线利用率的大部分； 取 15 秒时同一路网在更密的间隔下也排得出来。
   *
   * <p>再往下压反而变差（10 秒时又会冒出不可吸收冲突）：{@code separation} 同时被让车修复用来 算延后量（{@code wait = 前车离开 + separation
   * − 后车进入}），余量太小则每次挪得太少、修不彻底。 要继续压先把这两个角色拆开。
   */
  public static final int DEFAULT_SEPARATION_SECONDS = 15;

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
    following = following == null ? Following.NONE : following;
  }

  /** 不按闭塞时间算的构造：快车被卡按占用区间量。 */
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
      Map<String, Integer> groupIntervals,
      Repair repair,
      boolean rapidStagger) {
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
        repair,
        rapidStagger,
        Following.NONE);
  }

  /** 不做快车错峰搜索的构造。 */
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
      Map<String, Integer> groupIntervals,
      Repair repair) {
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
        repair,
        false);
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
        repair,
        rapidStagger,
        following);
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
        nextRepair,
        rapidStagger,
        following);
  }

  /** 开关快车错峰搜索，其余不变。 */
  public TimetableBuildOptions withRapidStagger(boolean enabled) {
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
        repair,
        enabled,
        following);
  }

  /** 计划窗口长度（秒）：首班发车到运营结束。 */
  public int horizonSeconds() {
    return serviceEndSecondOfDay - serviceStartSecondOfDay;
  }

  /**
   * 换一张折返时间表，其余不变。
   *
   * <p>命令层只知道用户有没有传 {@code --turnaround}；各 route 终到站的 dwell 要等 builder 拿到停靠配置才算得出来。 于是 builder 在
   * {@code prepare} 之后用本方法把表补上——显式覆盖（{@link TurnaroundTable#fixed}）时不动。
   *
   * @param turnarounds 折返时间表
   */
  public TimetableBuildOptions withTurnaround(TurnaroundTable turnarounds) {
    if (turnarounds == null || dutyLimits.turnaround().equals(turnarounds)) {
      return this;
    }
    return new TimetableBuildOptions(
        serviceStartSecondOfDay,
        serviceEndSecondOfDay,
        headway,
        defaultDwell,
        // 闲置上限必须原样带过去：三参构造会把它重置成默认值，--max-idle 与服务器配置就白传了。
        new VehicleDutyPlanner.Limits(
            dutyLimits.maxTripsPerDuty(),
            dutyLimits.maxDutyDurationSeconds(),
            turnarounds,
            dutyLimits.maxIdleSeconds()),
        tripCodePrefix,
        zoneId,
        separation,
        strictConflicts,
        groupIntervals,
        repair,
        rapidStagger,
        following);
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
