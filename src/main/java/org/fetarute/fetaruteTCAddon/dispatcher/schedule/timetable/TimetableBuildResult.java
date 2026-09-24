package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.scope.NeighborTimetable;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.scope.TimetableBaseline;

/**
 * 构建结果：时刻表本体 + 这份计划到底做到了什么。
 *
 * <p>诊断部分不是附赠品。{@code build} 有三件事必须说清楚，否则运营方看不见自己下的决定：
 *
 * <ul>
 *   <li><b>目标比例 vs 实际比例</b>。weight 是 objective 而不是硬约束：线路能力、全程时分、
 *       折返时间都可能让某条线排不满它应得的份额。差异必须摆出来，而不是偷偷生成不合理的班次。
 *   <li><b>车辆交路的边界</b>。每个 duty 的班次数、在线时长、是否都以回库收尾——这三项是 "每辆车最终都会回库"这条不变量在构建产物上的直接证据。
 *   <li><b>被取消的班次</b>。起点没有出库线路、终点没有回库线路的班次不会被硬排进去，但也不会静默消失：逐条列出，附原因。
 * </ul>
 *
 * @param timetable 构建出的时刻表；失败时为空
 * @param shares 各 route 的目标份额与实际份额（按最终保留的班次计）
 * @param infeasibleRoutes 因路网原因排不了的 route 及原因（含出库/回库线路）
 * @param droppedTrips 排定后又因出库/回库途径缺失而取消的班次
 * @param dutyCount 车辆交路数量
 * @param plannedVehicles 计划需要从库里取出的实体车次数（= duty 数）
 * @param peakConcurrentVehicles 同一时刻最多同时在线的车数；与 operator 车数上限比的是它
 * @param maxTripsInAnyDuty 最长 duty 的班次数
 * @param maxDutyDurationSeconds 最长 duty 的在线时间（秒）
 * @param allDutiesReturnToStorage 是否每个 duty 都以回库收尾
 * @param longestTripSeconds 最长一趟车的全程时分（秒）
 * @param targetHeadwaySeconds 运营方要求的 headway
 * @param effectiveHeadwaySeconds 最终成表用的 headway；有冲突并回退时大于目标
 * @param conflictsAtTarget 目标 headway 下查出的冲突（含内部与外部；回退后的表没有冲突）
 * @param neighbors 参与检查的邻表摘要
 * @param baselines 邻表基线（成功时非空；无邻表为空）
 * @param shifts 因端点串行偏离名义时隙的班次
 * @param yields 让车清单：已写进表的延后（对邻表让的带 owner）
 * @param terminals 容量 1 端点的报告（经过次数、占用、结构下界）
 * @param throats 车库咽喉的报告（出入库经过次数、占用、出库在库内等了几班）
 * @param groupIntervals 各交路组的目标间隔与实际间隔
 * @param interleaves 共用起点站台组上的合成间隔
 * @param dutyShapes 交路形状：跑几班的交路各有多少条
 * @param phaseNotes 相位选择的说明
 * @param warnings 构建过程中的提示
 */
public record TimetableBuildResult(
    Optional<Timetable> timetable,
    List<WeightedTripAllocator.ShareReport> shares,
    List<InfeasibleRoute> infeasibleRoutes,
    List<DroppedTrip> droppedTrips,
    int dutyCount,
    int plannedVehicles,
    int peakConcurrentVehicles,
    int maxTripsInAnyDuty,
    int maxDutyDurationSeconds,
    boolean allDutiesReturnToStorage,
    int longestTripSeconds,
    int targetHeadwaySeconds,
    int effectiveHeadwaySeconds,
    List<TimetableConflictChecker.Conflict> conflictsAtTarget,
    List<NeighborSummary> neighbors,
    List<TimetableBaseline> baselines,
    List<TripShift> shifts,
    List<ResourceRepair.Yield> yields,
    List<TerminalSerializer.TerminalReport> terminals,
    List<TerminalSerializer.ThroatReport> throats,
    List<GroupInterval> groupIntervals,
    List<PhasePlanner.Interleave> interleaves,
    List<DutyShape> dutyShapes,
    List<String> phaseNotes,
    List<ConflictAbsorption.Residual> absorbable,
    List<ConflictAbsorption.Residual> unabsorbable,
    List<String> resourcePhaseNotes,
    List<PhasePlanner.Residue> residues,
    List<String> warnings) {

  /** 报告里最多展开多少条冲突明细。 */
  public static final int CONFLICT_DETAIL_LIMIT = 8;

  public TimetableBuildResult {
    absorbable = absorbable == null ? List.of() : List.copyOf(absorbable);
    resourcePhaseNotes = resourcePhaseNotes == null ? List.of() : List.copyOf(resourcePhaseNotes);
    residues = residues == null ? List.of() : List.copyOf(residues);
    unabsorbable = unabsorbable == null ? List.of() : List.copyOf(unabsorbable);
    timetable = timetable == null ? Optional.empty() : timetable;
    shares = shares == null ? List.of() : List.copyOf(shares);
    infeasibleRoutes = infeasibleRoutes == null ? List.of() : List.copyOf(infeasibleRoutes);
    droppedTrips = droppedTrips == null ? List.of() : List.copyOf(droppedTrips);
    conflictsAtTarget = conflictsAtTarget == null ? List.of() : List.copyOf(conflictsAtTarget);
    neighbors = neighbors == null ? List.of() : List.copyOf(neighbors);
    baselines = baselines == null ? List.of() : List.copyOf(baselines);
    shifts = shifts == null ? List.of() : List.copyOf(shifts);
    terminals = terminals == null ? List.of() : List.copyOf(terminals);
    throats = throats == null ? List.of() : List.copyOf(throats);
    groupIntervals = groupIntervals == null ? List.of() : List.copyOf(groupIntervals);
    interleaves = interleaves == null ? List.of() : List.copyOf(interleaves);
    dutyShapes = dutyShapes == null ? List.of() : List.copyOf(dutyShapes);
    phaseNotes = phaseNotes == null ? List.of() : List.copyOf(phaseNotes);
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
  }

  /** 目标 headway 下与邻表撞上的冲突。 */
  public List<TimetableConflictChecker.Conflict> externalConflictsAtTarget() {
    return conflictsAtTarget.stream().filter(TimetableConflictChecker.Conflict::external).toList();
  }

  /** 目标 headway 下自己内部的冲突。 */
  public List<TimetableConflictChecker.Conflict> internalConflictsAtTarget() {
    return conflictsAtTarget.stream().filter(c -> !c.external()).toList();
  }

  /** 目标 headway 是否被放宽了。 */
  public boolean headwayRelaxed() {
    return success() && effectiveHeadwaySeconds > targetHeadwaySeconds;
  }

  /** 构建是否产出了时刻表。 */
  public boolean success() {
    return timetable.isPresent();
  }

  /** 班次总数。 */
  public int tripCount() {
    return timetable.map(table -> table.trips().size()).orElse(0);
  }

  /** 目标比例与实际比例的最大偏差（百分点）。 */
  public double maxShareDeviationPercentPoints() {
    return shares.stream()
        .mapToDouble(WeightedTripAllocator.ShareReport::deviationPercentPoints)
        .max()
        .orElse(0.0D);
  }

  /** 失败结果，只带原因。 */
  public static TimetableBuildResult failure(String reason, List<InfeasibleRoute> infeasible) {
    return new TimetableBuildResult(
        Optional.empty(),
        List.of(),
        infeasible,
        List.of(),
        0,
        0,
        0,
        0,
        0,
        false,
        0,
        0,
        0,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(Objects.requireNonNullElse(reason, "构建失败")));
  }

  /**
   * 一个交路组的间隔：目标是配置/命令给的，实际是搜索放宽后的（没放宽时相等）。
   *
   * @param group 组名
   * @param targetSeconds 目标间隔
   * @param effectiveSeconds 实际间隔
   */
  public record GroupInterval(String group, int targetSeconds, int effectiveSeconds) {}

  /**
   * 交路形状：跑 {@code trips} 班的交路有 {@code duties} 条。
   *
   * @param trips 一条交路里的班次数（不含回库班）
   * @param duties 这种形状的交路条数
   */
  public record DutyShape(int trips, int duties) {}

  /**
   * 一班偏离名义时隙：端点串行把它延后（等端点空出来、或本车就绪晚了）或提前（续班锚在车上）。
   *
   * @param tripCode 最终车次号
   * @param nominalSecondOfDay 名义时隙（网格）
   * @param actualSecondOfDay 实际发车
   * @param reason 原因
   */
  public record TripShift(
      String tripCode,
      int nominalSecondOfDay,
      int actualSecondOfDay,
      TerminalSerializer.Shift.Reason reason) {}

  /**
   * 一份邻表在报告里的摘要。
   *
   * @param displayCode 显示码
   * @param sharedResources 共用资源数
   * @param conflictsAtTarget 目标间隔下与它的外部冲突数
   * @param stale 邻表基于旧图
   * @param zoneApproximated 时区不同，按参考日偏移换算
   */
  public record NeighborSummary(
      String displayCode,
      int sharedResources,
      int conflictsAtTarget,
      boolean stale,
      boolean zoneApproximated) {

    /** 由一份邻表与"目标间隔下与它的外部冲突数"组成；build 与 neighbors 命令共用。 */
    public static NeighborSummary of(NeighborTimetable neighbor, int conflictsAtTarget) {
      Objects.requireNonNull(neighbor, "neighbor");
      return new NeighborSummary(
          neighbor.displayCode(),
          neighbor.sharedResources(),
          conflictsAtTarget,
          neighbor.staleAgainstGraph(),
          neighbor.zoneApproximated());
    }
  }

  /**
   * 排不进计划的 route。
   *
   * @param routeCode Route code
   * @param reason 原因（区段不可达、缺限速等）
   */
  public record InfeasibleRoute(String routeCode, String reason) {
    public InfeasibleRoute {
      routeCode = routeCode == null ? "" : routeCode;
      reason = reason == null ? "" : reason;
    }
  }

  /**
   * 排定后又被取消的班次。
   *
   * @param routeCode Route code
   * @param departureText 起点发车时刻 {@code HH:mm:ss}
   * @param reason 取消原因
   */
  public record DroppedTrip(
      String routeCode, String departureText, VehicleDutyPlanner.UnassignedReason reason) {
    public DroppedTrip {
      routeCode = routeCode == null ? "" : routeCode;
      departureText = departureText == null ? "" : departureText;
      Objects.requireNonNull(reason, "reason");
    }
  }
}
