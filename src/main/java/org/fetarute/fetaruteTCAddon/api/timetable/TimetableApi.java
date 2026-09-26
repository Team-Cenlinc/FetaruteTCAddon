package org.fetarute.fetaruteTCAddon.api.timetable;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * 时刻表 API：已发布时刻表、车次、站点计划到发与列车当前车次（1.4.0 新增）。
 *
 * <p>只读。数据来自内存中已发布时刻表的快照，查询不访问数据库，可在任意线程调用。草稿与归档时刻表不对外暴露。
 *
 * <h2>时间语义</h2>
 *
 * <p>时刻表是按服务日重复的模板：车次只记起点发车的“当日秒数”，各站时刻是相对起点发车的偏移。 本 API 返回的 {@link Instant}
 * 已按时刻表自身时区与服务日换算好，调用方无需自己做日期换算。
 *
 * <h2>示例</h2>
 *
 * <pre>{@code
 * TimetableApi tt = api.timetables();
 * for (TimetableApi.Departure d :
 *     tt.departuresAt(operatorId, "HHU", Instant.now(), Duration.ofMinutes(15), 8)) {
 *   System.out.println(d.tripCode() + " " + d.plannedDeparture());
 * }
 * tt.getAssignment("SURC-WS-LC-1037").ifPresent(a -> {
 *   a.currentDelaySeconds().ifPresent(d -> System.out.println(a.tripCode() + " 上一站偏差 " + d + " 秒"));
 *   a.projectedDelaySeconds().ifPresent(d -> System.out.println("预计到下一站偏差 " + d + " 秒"));
 * });
 * }</pre>
 */
public interface TimetableApi {

  /**
   * 按表运行是否启用（{@code timetable.enabled}）。
   *
   * <p>关闭时已发布时刻表仍可查询，但不会约束发车，列车也不会绑定车次。
   */
  boolean enabled();

  /** 全部已发布时刻表的概要。 */
  Collection<TimetableInfo> listPublished();

  /**
   * 某条线路的已发布时刻表概要。
   *
   * @param lineId 线路 ID
   */
  Collection<TimetableInfo> listByLine(UUID lineId);

  /**
   * 已发布时刻表的完整内容（交路时分、车次、车辆交路）。
   *
   * @param timetableId 时刻表 ID
   * @return 未发布或不存在时为空
   */
  Optional<TimetableDetail> getTimetable(UUID timetableId);

  /**
   * 某站在时间窗内的计划发车（所有已发布时刻表合并，按计划发车时刻排序）。
   *
   * <p>只列在该站停车的车次；终到车次也会列出（其到达即终到，{@link Departure#terminating()} 为 true）。
   *
   * @param operatorId 运营商 ID；为 null 时不限运营商
   * @param stationCode 站点代码（如 {@code HHU}，大小写不敏感）
   * @param from 窗口起点
   * @param window 窗口长度（上限 24 小时）
   * @param limit 最多返回条数（非正数按 50 计）
   */
  List<Departure> departuresAt(
      UUID operatorId, String stationCode, Instant from, Duration window, int limit);

  /**
   * 列车当前绑定的车次，以及相对计划的偏差。
   *
   * @param trainName 列车名（大小写不敏感）
   * @return 未启用按表运行或列车未绑定车次时为空
   */
  Optional<TrainAssignment> getAssignment(String trainName);

  /** 当前全部车次绑定。 */
  Collection<TrainAssignment> listAssignments();

  /**
   * 时刻表概要。
   *
   * @param id 时刻表 ID
   * @param operatorId 运营商 ID
   * @param lineId 线路 ID
   * @param code 时刻表代码（线路内唯一）
   * @param name 名称
   * @param zoneId 时区（IANA 名，如 {@code Asia/Shanghai}）
   * @param serviceStartSecondOfDay 运营开始（当日秒数）
   * @param serviceEndSecondOfDay 运营结束（当日秒数，可超过 86400 表示跨日）
   * @param routeIds 涉及的交路
   * @param tripCount 车次数
   * @param dutyCount 车辆交路数
   * @param updatedAt 最后修改时间
   */
  record TimetableInfo(
      UUID id,
      UUID operatorId,
      UUID lineId,
      String code,
      String name,
      String zoneId,
      int serviceStartSecondOfDay,
      int serviceEndSecondOfDay,
      List<UUID> routeIds,
      int tripCount,
      int dutyCount,
      Instant updatedAt) {
    public TimetableInfo {
      routeIds = routeIds == null ? List.of() : List.copyOf(routeIds);
    }
  }

  /**
   * 时刻表完整内容。
   *
   * @param info 概要
   * @param routePlans 各交路的站间时分
   * @param trips 车次（按起点发车时刻排序）
   * @param duties 车辆交路
   */
  record TimetableDetail(
      TimetableInfo info, List<RoutePlan> routePlans, List<Trip> trips, List<Duty> duties) {
    public TimetableDetail {
      routePlans = routePlans == null ? List.of() : List.copyOf(routePlans);
      trips = trips == null ? List.of() : List.copyOf(trips);
      duties = duties == null ? List.of() : List.copyOf(duties);
    }
  }

  /**
   * 交路时分：各站相对起点发车的到发偏移。
   *
   * @param routeId 交路 ID
   * @param routeCode 交路代码
   * @param kind 交路类型（OPERATION / CREATE / RETURN）
   * @param stops 各停靠点
   */
  record RoutePlan(UUID routeId, String routeCode, String kind, List<StopTime> stops) {
    public RoutePlan {
      stops = stops == null ? List.of() : List.copyOf(stops);
    }
  }

  /**
   * 停靠点计划时分。
   *
   * @param stopSequence 停靠序号（与运行时进度索引同义）
   * @param stationCode 站点代码（非车站节点为空）
   * @param nodeId 调度图节点
   * @param arrivalOffsetSeconds 相对起点发车的到达偏移（秒）
   * @param departureOffsetSeconds 相对起点发车的发车偏移（秒）
   */
  record StopTime(
      int stopSequence,
      Optional<String> stationCode,
      Optional<String> nodeId,
      int arrivalOffsetSeconds,
      int departureOffsetSeconds) {}

  /**
   * 车次。
   *
   * @param id 车次 ID
   * @param routeId 交路 ID
   * @param tripCode 车次号
   * @param departureSecondOfDay 起点发车（当日秒数）
   * @param dutyCode 所属车辆交路代码（未分配时为空）
   */
  record Trip(
      UUID id,
      UUID routeId,
      String tripCode,
      int departureSecondOfDay,
      Optional<String> dutyCode) {}

  /**
   * 车辆交路：一辆车从出库到回库依次跑的车次。
   *
   * @param id 交路 ID
   * @param dutyCode 交路代码
   * @param startDepotNodeId 出库车库节点
   * @param endDepotNodeId 回库车库节点
   * @param tripCodes 依次运行的车次号
   * @param plannedStartSecondOfDay 计划出库（当日秒数，可为负表示前一日）
   * @param plannedEndSecondOfDay 计划回库（当日秒数，可超过 86400）
   */
  record Duty(
      UUID id,
      String dutyCode,
      String startDepotNodeId,
      String endDepotNodeId,
      List<String> tripCodes,
      int plannedStartSecondOfDay,
      int plannedEndSecondOfDay) {
    public Duty {
      tripCodes = tripCodes == null ? List.of() : List.copyOf(tripCodes);
    }
  }

  /**
   * 站点的一条计划发车。
   *
   * @param timetableId 时刻表 ID
   * @param lineId 线路 ID
   * @param routeId 交路 ID
   * @param routeCode 交路代码
   * @param tripCode 车次号
   * @param stopSequence 本站在交路中的停靠序号
   * @param nodeId 本站调度图节点（可用于显示站台）
   * @param plannedArrival 计划到达
   * @param plannedDeparture 计划发车
   * @param terminating 本站是否为该车次终点
   * @param serviceDate 服务日（起点发车所在日期）
   */
  record Departure(
      UUID timetableId,
      UUID lineId,
      UUID routeId,
      String routeCode,
      String tripCode,
      int stopSequence,
      Optional<String> nodeId,
      Instant plannedArrival,
      Instant plannedDeparture,
      boolean terminating,
      LocalDate serviceDate) {}

  /**
   * 列车的车次绑定。
   *
   * @param trainName 列车名
   * @param timetableId 时刻表 ID
   * @param tripCode 车次号
   * @param routeId 交路 ID
   * @param dutyCode 车辆交路代码（未分配时为空）
   * @param serviceDate 服务日
   * @param assignedAt 绑定时刻
   * @param initialDeviationSeconds 绑定时相对计划的偏差（正数为晚点）
   * @param lastStopSequence 本车次最近一次实际到达或发车的停靠序号（尚无记录时为空）
   * @param currentDelaySeconds 本车次最近一次实际到达或发车相对计划的偏差（正数为晚点；尚无记录时为空）
   * @param nextStopSequence 下一个计划停靠点的序号（无法定位时为空）
   * @param projectedDelaySeconds 按 ETA 预计到达下一个计划停靠点相对计划的偏差（正数为晚点）。 列车在区间被扣停时它会随扣停时长增长， 而 {@code
   *     currentDelaySeconds} 要到下一次到发才更新；ETA 不可用时为空
   */
  record TrainAssignment(
      String trainName,
      UUID timetableId,
      String tripCode,
      UUID routeId,
      Optional<String> dutyCode,
      LocalDate serviceDate,
      Instant assignedAt,
      long initialDeviationSeconds,
      Optional<Integer> lastStopSequence,
      OptionalLong currentDelaySeconds,
      Optional<Integer> nextStopSequence,
      OptionalLong projectedDelaySeconds) {}
}
