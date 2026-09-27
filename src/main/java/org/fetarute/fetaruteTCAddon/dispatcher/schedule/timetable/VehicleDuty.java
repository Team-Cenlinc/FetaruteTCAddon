package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 车辆交路（duty / circulation block）：一辆实体车从出库到回库之间承担的一串班次。
 *
 * <pre>
 *   Storage ─CREATE→ [ Trip 1 → Trip 2 → … → Trip N ] ─RETURN→ Storage
 *                    └────────── 有限 duty ──────────┘
 * </pre>
 *
 * <p>这个抽象存在的唯一理由，是让"每辆车最终都会回库"成为<b>可证明</b>的性质而不是概率陈述。
 * 「跑完一趟有多大概率回库」那类机制永远证明不了终止性：只要下一班恰好接得上，它就可以无限续下去。 而 duty 把边界放在计划里——{@link #tripIds()} 的长度和 {@link
 * #plannedDurationSeconds()} 在<b>构建时</b>就已经被硬上限夹住，并且每个 duty 都带一个显式的回库端点。
 *
 * <p>两端各有一条具体的走行线路：
 *
 * <ul>
 *   <li>{@link #createRouteId()}：车库 → 首班起点的 CREATE route。为空表示首班 route 本身从车库始发（首站带 CRET 指令），
 *       出库票就是首班的运营票。
 *   <li>{@link #returnRouteId()}：末班终点 → 车库的 RETURN route。为空表示末班 route 本身以销毁收尾（DSTY），不需要另发回库票。
 * </ul>
 *
 * 两端的走行时分都从路网算出来并计入 {@link #plannedStartSecondOfDay()} 与 {@link #plannedEndSecondOfDay()}，不用常数估计。
 *
 * <p>因此这条不变量可以直接在构建产物上断言，不需要跑 runtime：
 *
 * <pre>
 *   ∀ duty: tripIds.size() ≤ maxTripsPerDuty
 *        ∧ plannedDurationSeconds ≤ maxDutyDurationSeconds
 *        ∧ endDepotNodeId 存在
 * </pre>
 *
 * @param id duty UUID
 * @param timetableId 所属时刻表
 * @param sequence 表内顺序
 * @param dutyCode 人类可读编号，在同一份时刻表内唯一
 * @param startDepotNodeId 出库点
 * @param endDepotNodeId 回库点；<b>必须存在</b>，这是终止性的物理落点
 * @param createRouteId 出库走行线路；首班本身从车库始发时为空
 * @param returnRouteId 回库走行线路；末班本身以销毁收尾时为空
 * @param tripIds 按执行顺序排列的班次
 * @param plannedStartSecondOfDay 出库时刻（相对服务日的秒数；首班很早时可为负，表示前一日）
 * @param returnSecondOfDay 回库票的发出时刻（末班到达 + 折返）；无回库票时等于末班到达
 * @param plannedEndSecondOfDay 到达车库的时刻（相对同一服务日的秒数，可超过一天表示跨零点）
 * @param closeReason duty 为什么在这里结束
 */
public record VehicleDuty(
    UUID id,
    UUID timetableId,
    int sequence,
    String dutyCode,
    String startDepotNodeId,
    String endDepotNodeId,
    Optional<UUID> createRouteId,
    Optional<UUID> returnRouteId,
    List<UUID> tripIds,
    int plannedStartSecondOfDay,
    int returnSecondOfDay,
    int plannedEndSecondOfDay,
    CloseReason closeReason) {

  public VehicleDuty {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(timetableId, "timetableId");
    Objects.requireNonNull(closeReason, "closeReason");
    if (sequence < 0) {
      throw new IllegalArgumentException("sequence 不能为负");
    }
    dutyCode = dutyCode == null ? "" : dutyCode.trim();
    if (dutyCode.isBlank()) {
      throw new IllegalArgumentException("dutyCode 不能为空");
    }
    startDepotNodeId = startDepotNodeId == null ? "" : startDepotNodeId.trim();
    endDepotNodeId = endDepotNodeId == null ? "" : endDepotNodeId.trim();
    if (endDepotNodeId.isBlank()) {
      // 没有回库端点的 duty 不是 duty，是一条没有出口的链。宁可构建失败，也不落这种记录。
      throw new IllegalArgumentException("duty 必须有回库端点");
    }
    createRouteId = createRouteId == null ? Optional.empty() : createRouteId;
    returnRouteId = returnRouteId == null ? Optional.empty() : returnRouteId;
    tripIds = tripIds == null ? List.of() : List.copyOf(tripIds);
    if (tripIds.isEmpty()) {
      throw new IllegalArgumentException("duty 至少要包含一趟车");
    }
    if (returnSecondOfDay < plannedStartSecondOfDay) {
      throw new IllegalArgumentException("duty 回库出发时刻不能早于出库时刻");
    }
    if (plannedEndSecondOfDay < returnSecondOfDay) {
      throw new IllegalArgumentException("duty 结束时刻不能早于回库出发时刻");
    }
  }

  /** duty 的计划时长（秒），含出库走行、班次之间的折返与停留，以及最后回库那一段。 */
  public int plannedDurationSeconds() {
    return plannedEndSecondOfDay - plannedStartSecondOfDay;
  }

  /** duty 承担的班次数。 */
  public int tripCount() {
    return tripIds.size();
  }

  /** duty 的结束原因。 */
  public enum CloseReason {
    /** 达到单个 duty 的最大班次数。 */
    MAX_TRIPS,

    /** 达到单个 duty 的最长在线时间。 */
    MAX_DURATION,

    /** 计划窗口结束：这辆车就绪之后，窗口里再没有任何班次发车。 */
    HORIZON_END,

    /** 窗口里之后还有班次，但没有一班落到这辆车上：终点对不上、时间接不上、回不了库，或被别的车接走。 */
    NO_COMPATIBLE_NEXT,

    /** 末班 route 本身以销毁收尾（DSTY），列车不进入复用池。 */
    ROUTE_ENDS_AT_DEPOT,

    /**
     * 在端点等不到下一班：距离下一个能接的班次超过闲置上限，按计划回库。
     *
     * <p>与运行时 {@code ReclaimManager} 的"待命超过 {@code reclaim.max-idle-seconds} 就派回库票"是同一条规则。
     * 没有这条时编表会让车在终点干等到下一个时隙——实测 MT 有车在 PPK 等了 19 分钟，而运行时 5 分钟就把它收走了， 于是表上那段待命占用是假的，还把站台判成了冲突。
     */
    IDLE_LIMIT;

    /** 该原因是否来自硬上限（而非"恰好没有下一班"）。 */
    public boolean bounded() {
      return this == MAX_TRIPS || this == MAX_DURATION || this == IDLE_LIMIT;
    }

    /** 宽松解析，供存储读取使用。 */
    public static Optional<CloseReason> parse(String raw) {
      if (raw == null || raw.isBlank()) {
        return Optional.empty();
      }
      try {
        return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
      } catch (IllegalArgumentException ignored) {
        return Optional.empty();
      }
    }
  }
}
