package org.fetarute.fetaruteTCAddon.api.timetable;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi;

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
 * <h2>停靠序号</h2>
 *
 * <p>本 API 所有 {@code stopSequence} 都是交路节点序列的 <b>0 起下标</b>，与 {@code
 * RouteApi.RouteDetail#waypoints()} 及 {@code stops()} 的下标、{@code
 * RouteApi.StopInfo#sequence()}、车站到发事件的 {@code getStopIndex()} 同一口径（1.5.0 起统一）：拿到序号 {@code n} 直接
 * {@code route.stops().get(n)} 即可。另附节点 ID 与站码，调用方也可以不依赖序号确认是哪一站。
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
 *   a.projectedDelaySeconds().ifPresent(d -> System.out.println(
 *       "预计到 " + a.nextStationCode().orElse("?") + " 偏差 " + d + " 秒"));
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
   * <p>只列在该站停车（{@link StopTime#stops()}）的车次；终到车次也会列出（其到达即终到，{@link Departure#terminating()} 为
   * true）。
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
   * <p>结果按 tick 缓存：同一 tick 内对同一列车重复查询只算一次（预计偏差要走一遍 ETA 路径计算）， 所以按帧轮询也不会放大开销；下一 tick 起重新计算。
   *
   * @param trainName 列车名（大小写不敏感）
   * @return 未启用按表运行或列车未绑定车次时为空
   */
  Optional<TrainAssignment> getAssignment(String trainName);

  /** 当前全部车次绑定（与 {@link #getAssignment} 共用同一份 tick 缓存）。 */
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
   * <p>是否停车看 {@code passType}（{@link #stops()}），不要拿到发时刻是否相同去猜：停站 0 秒的 STOP 站同样停车。
   *
   * @param stopSequence 停靠序号（交路节点的 0 起下标，见类注释“停靠序号”）
   * @param stationCode 站点代码（只有车站本体节点才有；区间点、咽喉、车库为空）
   * @param nodeId 调度图节点（DYNAMIC 停靠为占位股道 {@code OP:S:CODE:fromTrack}，与 RouteApi 停靠表一致）
   * @param arrivalOffsetSeconds 相对起点发车的到达偏移（秒）
   * @param departureOffsetSeconds 相对起点发车的发车偏移（秒）
   * @param passType 停车方式（1.5.0 新增；1.5.0 之前发布的时刻表按“首末站或停站大于 0 秒”回推，重新发布后按交路定义）
   */
  record StopTime(
      int stopSequence,
      Optional<String> stationCode,
      Optional<String> nodeId,
      int arrivalOffsetSeconds,
      int departureOffsetSeconds,
      RouteApi.PassType passType) {

    /** 列车是否在此停车（STOP 与 TERMINATE）。 */
    public boolean stops() {
      return passType != RouteApi.PassType.PASS;
    }
  }

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
   * @param lineId 时刻表所属线路 ID（交路组的管理归属）。直通运转（CHANGE）换线只是通知列车改按另一条线运营， 不改变归属；乘客在本站看到的线路见 {@code
   *     RouteApi.StopInfo#lineChange}
   * @param routeId 交路 ID
   * @param routeCode 交路代码
   * @param tripCode 车次号
   * @param stopSequence 本站停靠序号（交路节点的 0 起下标）
   * @param nodeId 本站调度图节点（可用于显示站台；DYNAMIC 停靠为占位股道）
   * @param plannedArrival 计划到达
   * @param plannedDeparture 计划发车
   * @param terminating 本站是否为该车次终点
   * @param serviceDate 服务日（起点发车所在日期）
   * @param cancelled 这趟车在本站不再停（1.8.0）：整趟没开出，或开出后车离开运行时、本站在剩下的站里。 详情见 {@code
   *     TimetableTripCancelledEvent}
   * @param plannedNodeId 本站是动态站台（DYNAMIC）停靠时，编表排定的计划股道（1.9.0）。列车进站前选台，计划股道被占时会改停别的股道， 届时发 {@code
   *     TrainPlatformAssignedEvent}；固定站台、没有排上或在 1.9.0 之前编的表为空
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
      LocalDate serviceDate,
      boolean cancelled,
      Optional<String> plannedNodeId) {

    public Departure {
      plannedNodeId = plannedNodeId == null ? Optional.empty() : plannedNodeId;
    }

    /** 1.8.0 的构造器（源码与二进制兼容）：没有计划股道。 */
    public Departure(
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
        LocalDate serviceDate,
        boolean cancelled) {
      this(
          timetableId,
          lineId,
          routeId,
          routeCode,
          tripCode,
          stopSequence,
          nodeId,
          plannedArrival,
          plannedDeparture,
          terminating,
          serviceDate,
          cancelled,
          Optional.empty());
    }

    /** 1.7.0 及以前的构造器（源码与二进制兼容）：未取消。 */
    public Departure(
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
        LocalDate serviceDate) {
      this(
          timetableId,
          lineId,
          routeId,
          routeCode,
          tripCode,
          stopSequence,
          nodeId,
          plannedArrival,
          plannedDeparture,
          terminating,
          serviceDate,
          false,
          Optional.empty());
    }
  }

  /**
   * 列车的车次绑定。
   *
   * <p>上一站、下一站各给三样：序号、节点、站码。序号与 RouteApi 停靠表同一口径；只想确认“晚点说的是哪一站”时， 比站码最稳（DYNAMIC 停靠选台前后节点会变，站码不变）。
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
   * @param lastStopNodeId 该次到发实际停靠的节点（DYNAMIC 为实际股道）
   * @param lastStationCode 该次到发所在车站的站码（非车站节点为空）
   * @param currentDelaySeconds 本车次最近一次实际到达或发车相对计划的偏差（正数为晚点；尚无记录时为空）
   * @param nextStopSequence 下一个计划停车点的序号（按交路停车方式，停站 0 秒的 STOP 也算；无法定位时为空）
   * @param nextStopNodeId 下一个计划停车点的节点：DYNAMIC 已选台为实际股道，未选台为占位股道（与 RouteApi 停靠表一致）
   * @param nextStationCode 下一个计划停车点的站码（非车站节点为空）
   * @param projectedDelaySeconds 按 ETA 预计到达 {@code nextStop*} 那一站相对计划的偏差（正数为晚点）。
   *     列车在区间被扣停时它会随扣停时长增长，而 {@code currentDelaySeconds} 要到下一次到发才更新；DYNAMIC 未选台时按到该站任一候选股道估算； ETA
   *     不可用时为空。同一 tick 内重复查询返回同一份结果
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
      Optional<String> lastStopNodeId,
      Optional<String> lastStationCode,
      OptionalLong currentDelaySeconds,
      Optional<Integer> nextStopSequence,
      Optional<String> nextStopNodeId,
      Optional<String> nextStationCode,
      OptionalLong projectedDelaySeconds) {}
}
