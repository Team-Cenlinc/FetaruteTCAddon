package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 把已排好的班次打包成有限的车辆交路（duty）。
 *
 * <p>核心不变量：<b>不存在任何一条正常运行路径能让一辆车无限接班。</b>
 *
 * <p>实现方式是把边界放进计划本身，而不是指望运行期"恰好没有下一班"：每开一个 duty 就记下它的 出库时刻与已接班次数，一旦再接一班会越过 {@code maxTripsPerDuty}
 * 或 {@code maxDutyDuration} 之一，这个 duty 立刻封口并写上回库端点。两个上限都是必填的（调用方从配置取默认值，不存在"未配置=无限"）， 因此每个 duty
 * 都满足：
 *
 * <pre>
 *   tripCount ≤ maxTripsPerDuty ∧ duration ≤ maxDutyDuration ∧ 有回库端点
 * </pre>
 *
 * <h2>出库与回库是具体的线路，不是估计值</h2>
 *
 * <p>一辆车不能凭空出现在首站，也不能在末站凭空消失。duty 的两端各对应一条真实 route：
 *
 * <ul>
 *   <li>只有在<b>起点有 CREATE 线路</b>（车库 → 该站）的班次上才能开新 duty；首站本身从车库始发（CRET）的 route 例外，它自己就是出库票。
 *   <li>只有在<b>终点有 RETURN 线路</b>（该站 → 车库）的班次后才能封口；route 本身以销毁收尾（DSTY）的例外，它跑完即回库。
 * </ul>
 *
 * 两端的走行时分取自 {@link Legs}，由 {@link TimetableTimingCalculator} 从路网算出，因此 duty 的在线时长是真实的。
 * 起点开不了、终点收不了的班次被列入 {@link Result#unassigned()}，由调用方从时刻表里剔除并如实报告——宁可少排一班，也不排一班发不出去或回不了库的车。
 *
 * <p>这也顺带把两个问题彻底分开：
 *
 * <ul>
 *   <li>"下一班该跑哪条线" —— 由 weight 决定，已经在 {@link WeightedTripAllocator} 里定好了。
 *   <li>"这一班由哪辆车跑" —— 由本类决定。
 * </ul>
 *
 * 本类只在<b>已经排定</b>的班次序列上做车辆指派，绝不为了方便复用去改班次的线路或时刻—— 否则车辆周转会反过来扭曲服务比例，而那正是"某终点恰好停着一辆车，于是这条线一直发车"的来源。
 */
public final class VehicleDutyPlanner {

  private VehicleDutyPlanner() {}

  /**
   * 指派车辆并封装 duty。
   *
   * @param timetableId 所属时刻表
   * @param trips 已按发车时刻升序排定的班次
   * @param legs 各站的出库/回库走行段
   * @param limits 硬上限
   * @return 打包结果
   */
  public static Result plan(UUID timetableId, List<PlannedTrip> trips, Legs legs, Limits limits) {
    return plan(timetableId, trips, legs, limits, Map.of());
  }

  /**
   * 指派车辆并封装 duty。
   *
   * @param timetableId 所属时刻表
   * @param trips 已按发车时刻升序排定的班次
   * @param legs 各站的出库/回库走行段
   * @param limits 硬上限
   * @param nextSlotByOrigin 各起点上后续班次的名义发车时刻（升序）；封口判据用它回答"下一班最早什么时候"。
   *     为空时退化为不按闲置上限收口的旧行为——只关心班次链的用例可以不传。
   * @return 打包结果
   */
  public static Result plan(
      UUID timetableId,
      List<PlannedTrip> trips,
      Legs legs,
      Limits limits,
      Map<String, NavigableSet<Integer>> nextSlotByOrigin) {
    Objects.requireNonNull(timetableId, "timetableId");
    Objects.requireNonNull(limits, "limits");
    Legs access = legs == null ? Legs.none() : legs;
    List<PlannedTrip> ordered =
        trips == null
            ? List.of()
            : trips.stream()
                .filter(Objects::nonNull)
                .sorted(
                    Comparator.comparingInt(PlannedTrip::departureSeconds)
                        .thenComparing(PlannedTrip::tripCode))
                .toList();
    if (ordered.isEmpty()) {
      return new Result(List.of(), List.of(), 0, List.of());
    }

    // 封口判据要回答"还装不装得下任何一班"，因此需要知道最短的一趟车与最短的回库段有多长。
    int minTripDuration = ordered.stream().mapToInt(PlannedTrip::durationSeconds).min().orElse(0);
    int minClosingTail = access.minClosingTail(ordered, limits);

    List<OpenDuty> open = new ArrayList<>();
    List<OpenDuty> closed = new ArrayList<>();
    List<UnassignedTrip> unassigned = new ArrayList<>();
    int dutySequence = 0;

    for (PlannedTrip trip : ordered) {
      OpenDuty host = selectHost(open, trip, access, limits, minTripDuration, minClosingTail);
      if (host == null) {
        UnassignedReason blocker =
            openBlocker(trip, access, limits, minTripDuration, minClosingTail);
        if (blocker != null) {
          unassigned.add(new UnassignedTrip(trip.tripId(), trip.tripCode(), blocker));
          continue;
        }
        host = open(dutySequence, trip, access, limits);
        open.add(host);
        dutySequence++;
      }
      host.accept(trip, limits);
      // 接完这一班立刻判断还能不能再接：能不能"再接一班"是 duty 的封口条件，
      // 放到下一班到来时再判会让边界依赖于"恰好还有没有下一班"，那就不是硬上限了。
      VehicleDuty.CloseReason reason =
          closeReasonAfter(host, access, limits, minTripDuration, minClosingTail, nextSlotByOrigin);
      if (reason != null) {
        host.seal(reason);
        closed.add(host);
        open.remove(host);
      }
    }

    // 计划窗口结束：所有还开着的 duty 一律封口回库，没有例外。停在没有回库线路的终点上的，
    // 把尾段班次退掉直到能回库为止——退掉的班次如实上报，而不是留一辆回不了库的车。
    for (OpenDuty duty : List.copyOf(open)) {
      boolean trimmed = false;
      while (!duty.trips.isEmpty() && !access.closable(duty.lastTrip())) {
        PlannedTrip dropped = duty.popLast();
        unassigned.add(
            new UnassignedTrip(
                dropped.tripId(), dropped.tripCode(), UnassignedReason.NO_RETURN_ACCESS));
        trimmed = true;
      }
      if (duty.trips.isEmpty()) {
        continue;
      }
      duty.seal(
          trimmed
              ? VehicleDuty.CloseReason.NO_COMPATIBLE_NEXT
              : VehicleDuty.CloseReason.HORIZON_END);
      closed.add(duty);
    }
    open.clear();

    List<VehicleDuty> built = new ArrayList<>(closed.size());
    for (OpenDuty duty : closed) {
      built.add(duty.toDuty(timetableId, access, limits));
    }
    built.sort(
        Comparator.comparingInt(VehicleDuty::plannedStartSecondOfDay)
            .thenComparing(VehicleDuty::dutyCode));
    List<VehicleDuty> renumbered = new ArrayList<>(built.size());
    for (int i = 0; i < built.size(); i++) {
      VehicleDuty duty = built.get(i);
      String dutyCode = String.format(Locale.ROOT, "D%03d", i + 1);
      renumbered.add(
          new VehicleDuty(
              deterministicDutyId(timetableId, dutyCode),
              duty.timetableId(),
              i,
              dutyCode,
              duty.startDepotNodeId(),
              duty.endDepotNodeId(),
              duty.createRouteId(),
              duty.returnRouteId(),
              duty.tripIds(),
              duty.plannedStartSecondOfDay(),
              duty.returnSecondOfDay(),
              duty.plannedEndSecondOfDay(),
              duty.closeReason()));
    }
    unassigned.sort(Comparator.comparing(UnassignedTrip::tripCode));
    return new Result(
        List.copyOf(renumbered), List.of(), renumbered.size(), List.copyOf(unassigned));
  }

  /**
   * 选择能接下这一班的已开 duty。
   *
   * <p>条件缺一不可：位置对得上（上一班的终点就是这一班的起点）、时间来得及（含折返时间）、
   * 接下后仍不越过硬上限、并且接下之后这辆车仍然回得了库（终点有回库线路，或还有余量再跑一班到有回库线路的终点）。 都满足时取"最早就绪"的那一个，并列时按 duty 序号——完全确定。
   *
   * <p>从车库始发的班次（CRET）永远不接在别的 duty 后面：它的出库票会实体化一辆新车，接不了待命列车。 不同车池（多线联编时的不同线路）之间也永远不接。
   */
  private static OpenDuty selectHost(
      List<OpenDuty> open,
      PlannedTrip trip,
      Legs access,
      Limits limits,
      int minTripDuration,
      int minClosingTail) {
    if (trip.startsAtDepot()) {
      return null;
    }
    OpenDuty best = null;
    for (OpenDuty duty : open) {
      if (!duty.lastTerminal.equals(trip.originNodeId()) || !duty.pool.equals(trip.pool())) {
        continue;
      }
      if (duty.readyAtSeconds > trip.departureSeconds()) {
        // readyAt 已经含折返时间，因此这一条同时覆盖了"来不及掉头"。
        continue;
      }
      if (trip.departureSeconds() - duty.readyAtSeconds > limits.maxIdleSeconds()) {
        // 这辆车在端点已经等过头了：运行时的闲置回收早把它送回库，编表不能让它凭空等到下一个时隙。
        continue;
      }
      if (duty.tripCount() + 1 > limits.maxTripsPerDuty()) {
        continue;
      }
      boolean closable = access.closable(trip);
      if (!closable && duty.tripCount() + 1 >= limits.maxTripsPerDuty()) {
        // 接完这一班就到班次上限，却停在一个回不了库的终点。
        continue;
      }
      // 上限覆盖整个 duty，包含最后回库那一段。终点回不了库时，至少要留出"再跑最短的一班到能回库的终点"的余量，
      // 否则这辆车会被困在那里。
      int tail =
          closable
              ? access.closingTail(trip, limits)
              : limits.turnaround().minimumSeconds() + minTripDuration + minClosingTail;
      int endIfAccepted = trip.departureSeconds() + trip.durationSeconds() + tail;
      if (endIfAccepted - duty.startSeconds > limits.maxDutyDurationSeconds()) {
        continue;
      }
      if (best == null
          || duty.readyAtSeconds < best.readyAtSeconds
          || (duty.readyAtSeconds == best.readyAtSeconds && duty.sequence < best.sequence)) {
        best = duty;
      }
    }
    return best;
  }

  /**
   * 这一班能不能作为一个新 duty 的首班；不能时给出原因。
   *
   * <p>三种阻塞：起点没有出库途径；单独这一班连同出库、回库走行就已经超过 duty 时长上限； 终点回不了库、而且余量也不够再跑一班到能回库的终点。
   */
  private static UnassignedReason openBlocker(
      PlannedTrip trip, Legs access, Limits limits, int minTripDuration, int minClosingTail) {
    int start;
    if (trip.startsAtDepot()) {
      start = trip.departureSeconds();
    } else {
      Optional<Leg> create = access.createLegAt(trip.originNodeId());
      if (create.isEmpty()) {
        return UnassignedReason.NO_CREATE_ACCESS;
      }
      start = openingStart(trip, create.get(), limits);
    }
    boolean closable = access.closable(trip);
    int tail =
        closable
            ? access.closingTail(trip, limits)
            : limits.turnaround().minimumSeconds() + minTripDuration + minClosingTail;
    int end = trip.departureSeconds() + trip.durationSeconds() + tail;
    if (end - start > limits.maxDutyDurationSeconds()) {
      return closable ? UnassignedReason.EXCEEDS_DUTY_LIMITS : UnassignedReason.NO_RETURN_ACCESS;
    }
    if (!closable && limits.maxTripsPerDuty() < 2) {
      return UnassignedReason.NO_RETURN_ACCESS;
    }
    return null;
  }

  /** 在这一班上开一个新 duty。前提是 {@link #openBlocker} 返回 null。 */
  private static OpenDuty open(int sequence, PlannedTrip trip, Legs access, Limits limits) {
    if (trip.startsAtDepot()) {
      return new OpenDuty(
          sequence, trip.originNodeId(), Optional.empty(), trip.departureSeconds(), trip.pool());
    }
    Leg leg = access.createLegAt(trip.originNodeId()).orElseThrow();
    return new OpenDuty(
        sequence,
        leg.depotNodeId(),
        Optional.of(leg.routeId()),
        openingStart(trip, leg, limits),
        trip.pool());
  }

  /**
   * 出库要提前：车库到首站的走行 + 到站后的就绪时间，否则首班必然晚点。
   *
   * <p>就绪时间取 <b>CREATE 线路自己</b>终到站的 dwell——车是按那条线路到的首站，能不能走由那条线路的停靠配置决定。
   */
  private static int openingStart(PlannedTrip trip, Leg create, Limits limits) {
    return trip.departureSeconds()
        - create.runSeconds()
        - limits.turnaround().secondsFor(create.routeId());
  }

  /**
   * 接完这一班之后，duty 是否必须封口。
   *
   * <p>route 自己以销毁收尾的，列车跑完就没了，duty 必然在此结束。 班次上限到了就封口（{@link #selectHost}
   * 已保证此时停在能回库的终点）。时长上限用<b>最短的那一班</b>来判：折返 + 最短全程 + 最短回库段。若连这个下界都装不下，
   * 就没有任何后续班次接得上了，此时封口的原因确实是时长上限，而不是"恰好没有下一班"。 用一个更松的下界（比如只算折返）会让 duty 一直挂着开放状态到窗口末尾，收尾原因也就变得没有信息量。
   */
  private static VehicleDuty.CloseReason closeReasonAfter(
      OpenDuty duty,
      Legs access,
      Limits limits,
      int minTripDuration,
      int minClosingTail,
      Map<String, NavigableSet<Integer>> nextSlotByOrigin) {
    PlannedTrip last = duty.lastTrip();
    if (last.endsAtDepot()) {
      return VehicleDuty.CloseReason.ROUTE_ENDS_AT_DEPOT;
    }
    if (duty.tripCount() >= limits.maxTripsPerDuty()) {
      return VehicleDuty.CloseReason.MAX_TRIPS;
    }
    if (!access.closable(last)) {
      // 回不了库的终点上不能封口；selectHost 已保证这里仍有余量再跑一班。
      return null;
    }
    int minimumTail =
        duty.endSeconds + limits.turnaround().minimumSeconds() + minTripDuration + minClosingTail;
    if (minimumTail - duty.startSeconds > limits.maxDutyDurationSeconds()) {
      return VehicleDuty.CloseReason.MAX_DURATION;
    }
    if (idleBeyondLimit(duty, limits, nextSlotByOrigin)) {
      return VehicleDuty.CloseReason.IDLE_LIMIT;
    }
    return null;
  }

  /**
   * 这辆车在当前终点要等多久才有下一班：超过闲置上限就该回库。
   *
   * <p>没有传时隙表时一律返回 false——退化成旧行为，而不是把所有 duty 都按闲置收口。
   */
  private static boolean idleBeyondLimit(
      OpenDuty duty, Limits limits, Map<String, NavigableSet<Integer>> nextSlotByOrigin) {
    if (nextSlotByOrigin == null || nextSlotByOrigin.isEmpty()) {
      return false;
    }
    NavigableSet<Integer> slots = nextSlotByOrigin.get(duty.lastTerminal);
    if (slots == null || slots.isEmpty()) {
      // 这个终点上没有任何后续发车：等下去也等不到，交给"接不上"的常规收口。
      return false;
    }
    Integer next = slots.ceiling(duty.readyAtSeconds);
    return next == null || next - duty.readyAtSeconds > limits.maxIdleSeconds();
  }

  /**
   * 由时刻表 ID 与 duty 编号派生稳定的 duty UUID。
   *
   * <p>理由同 trip：主键会进数据库、会被 trip 引用、会出现在导出里，随机化会让"同样输入构建两次结果一致" 这条性质在主键层面失效。
   */
  static UUID deterministicDutyId(UUID timetableId, String dutyCode) {
    return UUID.nameUUIDFromBytes(
        ("duty:" + timetableId + ":" + dutyCode).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  /** 规划中的一个 duty。 */
  private static final class OpenDuty {

    private final int sequence;
    private final String startDepot;
    private final Optional<UUID> createRouteId;
    private final int startSeconds;
    private final String pool;
    private final List<PlannedTrip> trips = new ArrayList<>();
    private String lastTerminal;
    private int endSeconds;
    private int readyAtSeconds;
    private VehicleDuty.CloseReason closeReason;

    private OpenDuty(
        int sequence,
        String startDepot,
        Optional<UUID> createRouteId,
        int startSeconds,
        String pool) {
      this.sequence = sequence;
      this.startDepot = startDepot;
      this.createRouteId = createRouteId;
      this.startSeconds = startSeconds;
      this.pool = pool;
      this.lastTerminal = "";
      this.endSeconds = startSeconds;
      this.readyAtSeconds = startSeconds;
    }

    private void accept(PlannedTrip trip, Limits limits) {
      trips.add(trip);
      refresh(limits);
    }

    private PlannedTrip popLast() {
      PlannedTrip removed = trips.remove(trips.size() - 1);
      // 退掉尾段后 lastTerminal/endSeconds 只在 toDuty 里再用，那里会按剩余班次重算。
      if (!trips.isEmpty()) {
        PlannedTrip last = lastTrip();
        lastTerminal = last.terminalNodeId();
        endSeconds = last.departureSeconds() + last.durationSeconds();
      }
      return removed;
    }

    private void refresh(Limits limits) {
      PlannedTrip last = lastTrip();
      lastTerminal = last.terminalNodeId();
      endSeconds = last.departureSeconds() + last.durationSeconds();
      // 到达之后要等本班终到站的停站结束才能再发车，与运行时 readyAt = 到达 + 终到站 dwell 同一口径。
      readyAtSeconds = endSeconds + limits.turnaround().secondsFor(last.routeId());
    }

    private PlannedTrip lastTrip() {
      return trips.get(trips.size() - 1);
    }

    private int tripCount() {
      return trips.size();
    }

    private void seal(VehicleDuty.CloseReason reason) {
      this.closeReason = reason;
    }

    private VehicleDuty toDuty(UUID timetableId, Legs access, Limits limits) {
      PlannedTrip last = lastTrip();
      int arrival = last.departureSeconds() + last.durationSeconds();
      String endDepot;
      Optional<UUID> returnRouteId;
      int returnAt;
      int end;
      if (last.endsAtDepot()) {
        endDepot = last.terminalNodeId();
        returnRouteId = Optional.empty();
        returnAt = arrival;
        end = arrival;
      } else {
        // 回自己出库的那个库：startDepot 是这条交路的出库点。
        Leg leg =
            access
                .returnLegAt(last.terminalNodeId(), startDepot)
                .orElseThrow(
                    () -> new IllegalStateException("duty 停在没有回库线路的终点: " + last.terminalNodeId()));
        endDepot = leg.depotNodeId();
        returnRouteId = Optional.of(leg.routeId());
        returnAt = arrival + limits.turnaround().secondsFor(last.routeId());
        end = returnAt + leg.runSeconds();
      }
      String dutyCode = String.format(Locale.ROOT, "D%03d", sequence + 1);
      return new VehicleDuty(
          deterministicDutyId(timetableId, dutyCode),
          timetableId,
          sequence,
          dutyCode,
          startDepot,
          endDepot,
          createRouteId,
          returnRouteId,
          trips.stream().map(PlannedTrip::tripId).toList(),
          startSeconds,
          returnAt,
          end,
          closeReason);
    }
  }

  /**
   * 待指派的一趟车。
   *
   * @param tripId 班次 UUID
   * @param routeId 这一班跑的 route；折返时间按它查 {@link TurnaroundTable}。为 {@code null} 时走表的兜底值
   * @param tripCode 班次号，用于确定性排序
   * @param originNodeId 起点节点
   * @param terminalNodeId 终点节点
   * @param departureSeconds 相对计划窗口起点的发车秒数
   * @param durationSeconds 全程时分（秒）
   * @param startsAtDepot route 首站就是车库（CRET）：出库票即运营票，不能接在别的 duty 后面
   * @param endsAtDepot route 以销毁收尾（DSTY）：跑完即回库，后面不能再接班
   * @param pool 车池：只有同一车池的班次才能接在同一条交路上。多线联编时每条线一个车池——一辆车不跨线接班；单线为空串
   */
  public record PlannedTrip(
      UUID tripId,
      UUID routeId,
      String tripCode,
      String originNodeId,
      String terminalNodeId,
      int departureSeconds,
      int durationSeconds,
      boolean startsAtDepot,
      boolean endsAtDepot,
      String pool) {

    public PlannedTrip {
      pool = pool == null ? "" : pool;
      Objects.requireNonNull(tripId, "tripId");
      tripCode = tripCode == null ? "" : tripCode;
      originNodeId = originNodeId == null ? "" : originNodeId;
      terminalNodeId = terminalNodeId == null ? "" : terminalNodeId;
      if (durationSeconds < 0) {
        throw new IllegalArgumentException("durationSeconds 不能为负");
      }
    }

    /** 单线（不分车池）的班次。 */
    public PlannedTrip(
        UUID tripId,
        UUID routeId,
        String tripCode,
        String originNodeId,
        String terminalNodeId,
        int departureSeconds,
        int durationSeconds,
        boolean startsAtDepot,
        boolean endsAtDepot) {
      this(
          tripId,
          routeId,
          tripCode,
          originNodeId,
          terminalNodeId,
          departureSeconds,
          durationSeconds,
          startsAtDepot,
          endsAtDepot,
          "");
    }

    /** 普通站间班次：起点终点都是车站。折返走 {@link TurnaroundTable} 的兜底值。 */
    public PlannedTrip(
        UUID tripId,
        String tripCode,
        String originNodeId,
        String terminalNodeId,
        int departureSeconds,
        int durationSeconds) {
      this(
          tripId,
          null,
          tripCode,
          originNodeId,
          terminalNodeId,
          departureSeconds,
          durationSeconds,
          false,
          false);
    }
  }

  /**
   * 车库与车站之间的一段走行：一条 CREATE 或 RETURN route 及其从路网算出的时分。
   *
   * @param routeId 走行线路
   * @param routeCode 线路 code
   * @param depotNodeId 车库端节点
   * @param runSeconds 走行时分（秒）
   * @param declared 运营 route 在 metadata 里显式指定了这条走行线路（直通运转）；同一站有多条时它优先
   */
  public record Leg(
      UUID routeId, String routeCode, String depotNodeId, int runSeconds, boolean declared) {
    public Leg(UUID routeId, String routeCode, String depotNodeId, int runSeconds) {
      this(routeId, routeCode, depotNodeId, runSeconds, false);
    }

    public Leg {
      Objects.requireNonNull(routeId, "routeId");
      routeCode = routeCode == null ? "" : routeCode;
      depotNodeId = depotNodeId == null ? "" : depotNodeId.trim();
      if (depotNodeId.isBlank()) {
        throw new IllegalArgumentException("走行段必须有车库端节点");
      }
      runSeconds = Math.max(0, runSeconds);
    }
  }

  /**
   * 全线的出库/回库走行段索引。
   *
   * @param createByStation 按首站节点索引的 CREATE 段
   * @param returnCandidates 按末站节点索引的<b>全部</b> RETURN 段，组内已按"显式指定 &gt; 走行最短"排序
   */
  public record Legs(Map<String, Leg> createByStation, Map<String, List<Leg>> returnCandidates) {

    public Legs {
      createByStation = createByStation == null ? Map.of() : Map.copyOf(createByStation);
      Map<String, List<Leg>> frozen = new LinkedHashMap<>();
      if (returnCandidates != null) {
        returnCandidates.forEach(
            (station, legs) -> {
              if (station != null && legs != null && !legs.isEmpty()) {
                frozen.put(station, List.copyOf(legs));
              }
            });
      }
      returnCandidates = Map.copyOf(frozen);
    }

    /** 同一站只有一条回库线路的简单形态：用例与只关心存在性的调用方用它。 */
    public static Legs simple(Map<String, Leg> createByStation, Map<String, Leg> returnByStation) {
      Map<String, List<Leg>> candidates = new LinkedHashMap<>();
      if (returnByStation != null) {
        returnByStation.forEach(
            (station, leg) -> {
              if (station != null && leg != null) {
                candidates.put(station, List.of(leg));
              }
            });
      }
      return new Legs(createByStation, candidates);
    }

    /** 没有任何走行段：只有自带 CRET/DSTY 的 route 能成 duty。 */
    public static Legs none() {
      return new Legs(Map.of(), Map.of());
    }

    /** 从走行段列表建索引；同一站有多条时先取显式指定的，再取走行最短的，并列按 routeCode 再按 routeId——确定性。 */
    public static Legs of(List<Leg> creates, List<Leg> returns, Map<UUID, String> stationByLeg) {
      Map<String, Leg> create = new LinkedHashMap<>();
      Map<String, List<Leg>> ret = new LinkedHashMap<>();
      index(create, creates, stationByLeg);
      indexAll(ret, returns, stationByLeg);
      return new Legs(create, ret);
    }

    /** 回库段按站收全部候选，组内保持 {@link #index} 的确定性序；选哪一条留到 {@link #returnLegAt} 按车库偏好决定。 */
    private static void indexAll(
        Map<String, List<Leg>> out, List<Leg> legs, Map<UUID, String> station) {
      for (Leg leg : sortDeterministically(legs)) {
        String node = station == null ? null : station.get(leg.routeId());
        if (node == null || node.isBlank()) {
          continue;
        }
        out.computeIfAbsent(node, key -> new ArrayList<>()).add(leg);
      }
    }

    private static List<Leg> sortDeterministically(List<Leg> legs) {
      return legs == null
          ? List.of()
          : legs.stream()
              .filter(Objects::nonNull)
              .sorted(
                  Comparator.comparing((Leg leg) -> !leg.declared())
                      .thenComparingInt(Leg::runSeconds)
                      .thenComparing(Leg::routeCode)
                      .thenComparing(leg -> leg.routeId().toString()))
              .toList();
    }

    private static void index(Map<String, Leg> out, List<Leg> legs, Map<UUID, String> station) {
      List<Leg> sorted = sortDeterministically(legs);
      for (Leg leg : sorted) {
        String node = station == null ? null : station.get(leg.routeId());
        if (node == null || node.isBlank()) {
          continue;
        }
        out.putIfAbsent(node, leg);
      }
    }

    public Optional<Leg> createLegAt(String stationNodeId) {
      return Optional.ofNullable(createByStation.get(stationNodeId));
    }

    /** 不带偏好的回库段：只回答"这个终点能不能回库"，与 {@link #closable} 同一口径。 */
    public Optional<Leg> returnLegAt(String stationNodeId) {
      return returnLegAt(stationNodeId, null);
    }

    /**
     * 带车库偏好的回库段：<b>回自己出库的那个车库</b>的优先，其次显式指定的，再次走行最短。
     *
     * <p>按最短选会让一条线的车全部涌进离终点最近的那个库——实测里 MT 从 OFL 终到的车全回了 HHU，
     * 于是别的线的回库走行在同一段咽喉上和它们撞。回原库是运营常识，也让每条线的回库流各走各的。
     *
     * @param stationNodeId 终到节点
     * @param preferredDepotNodeId 本交路的出库车库节点；为空时退化为无偏好
     */
    public Optional<Leg> returnLegAt(String stationNodeId, String preferredDepotNodeId) {
      List<Leg> candidates = returnCandidates.get(stationNodeId);
      if (candidates == null || candidates.isEmpty()) {
        return Optional.empty();
      }
      String preferredGroup = depotGroupOf(preferredDepotNodeId);
      if (!preferredGroup.isEmpty()) {
        for (Leg leg : candidates) {
          if (depotGroupOf(leg.depotNodeId()).equals(preferredGroup)) {
            return Optional.of(leg);
          }
        }
      }
      // 候选已按"显式指定 > 走行最短"排好序，第一条就是无偏好时的答案。
      return Optional.of(candidates.get(0));
    }

    /** 车库组：{@code 运营商:D:库名}，解析不出时用节点 id 本身，保证同一个库永远同一个键。 */
    private static String depotGroupOf(String nodeId) {
      if (nodeId == null || nodeId.isBlank()) {
        return "";
      }
      String group = TimetableConflictChecker.groupOf(nodeId);
      return group.isBlank() ? nodeId.trim() : group;
    }

    /** 这一班跑完之后，这辆车能不能回库。 */
    boolean closable(PlannedTrip trip) {
      return trip.endsAtDepot() || returnCandidates.containsKey(trip.terminalNodeId());
    }

    /** 这一班跑完到回到车库还要多久（含折返）。前提是 {@link #closable}。 */
    int closingTail(PlannedTrip trip, Limits limits) {
      if (trip.endsAtDepot()) {
        return 0;
      }
      Leg leg = returnLegAt(trip.terminalNodeId()).orElse(null);
      return leg == null ? 0 : limits.turnaround().secondsFor(trip.routeId()) + leg.runSeconds();
    }

    /** 所有可能的收尾里最短的一种，用作"还装不装得下"的下界。 */
    int minClosingTail(List<PlannedTrip> trips, Limits limits) {
      int min = Integer.MAX_VALUE;
      for (PlannedTrip trip : trips) {
        if (closable(trip)) {
          min = Math.min(min, closingTail(trip, limits));
        }
      }
      return min == Integer.MAX_VALUE ? 0 : min;
    }
  }

  /**
   * duty 的硬上限。
   *
   * <p>两个上限都<b>没有</b>"不限制"这个取值：构造时会把非正值夹回默认值。 允许无限就等于允许一辆车永远不回库，而那正是本类要排除的情况。
   *
   * <p>没有"回库走行时间"这一项：回库段的时分来自具体的 RETURN route（{@link Legs}），不是估计值。
   *
   * @param maxTripsPerDuty 单个 duty 最多承担多少班次
   * @param maxDutyDurationSeconds 单个 duty 最长在线时间（秒），含出库与回库走行
   * @param turnaround 折返时间表，见 {@link TurnaroundTable}——它不是上限，是从 route 定义算出来的物理量
   * @param maxIdleSeconds 在端点等下一班的上限，超过就回库；与运行时 {@code reclaim.max-idle-seconds} 同一条规则
   */
  public record Limits(
      int maxTripsPerDuty,
      int maxDutyDurationSeconds,
      TurnaroundTable turnaround,
      int maxIdleSeconds) {

    /** 默认上限：4 趟 / 2 小时在线 / 端点闲置 5 分钟。折返<b>没有</b>默认值——它来自各 route 终到站的 dwell。 */
    public static final int DEFAULT_MAX_TRIPS = 4;

    public static final int DEFAULT_MAX_DURATION_SECONDS = 7200;

    /** 与 {@code reclaim.max-idle-seconds} 的默认值一致；命令层会用实服配置覆盖它。 */
    public static final int DEFAULT_MAX_IDLE_SECONDS = 300;

    public Limits {
      maxTripsPerDuty = maxTripsPerDuty > 0 ? maxTripsPerDuty : DEFAULT_MAX_TRIPS;
      maxDutyDurationSeconds =
          maxDutyDurationSeconds > 0 ? maxDutyDurationSeconds : DEFAULT_MAX_DURATION_SECONDS;
      turnaround = turnaround == null ? TurnaroundTable.none() : turnaround;
      maxIdleSeconds = maxIdleSeconds > 0 ? maxIdleSeconds : DEFAULT_MAX_IDLE_SECONDS;
    }

    /** 折返表 + 默认闲置上限。 */
    public Limits(int maxTripsPerDuty, int maxDutyDurationSeconds, TurnaroundTable turnaround) {
      this(maxTripsPerDuty, maxDutyDurationSeconds, turnaround, DEFAULT_MAX_IDLE_SECONDS);
    }

    /** 折返用显式全线值：等价于 {@link TurnaroundTable#fixed}，供 {@code --turnaround} 与用例使用。 */
    public Limits(int maxTripsPerDuty, int maxDutyDurationSeconds, int fixedTurnaroundSeconds) {
      this(maxTripsPerDuty, maxDutyDurationSeconds, TurnaroundTable.fixed(fixedTurnaroundSeconds));
    }

    /** 带闲置上限的显式折返值，供用例把既有期望钉在"不回收"的旧行为上（{@code maxIdle} 给很大）。 */
    public Limits(
        int maxTripsPerDuty,
        int maxDutyDurationSeconds,
        int fixedTurnaroundSeconds,
        int maxIdleSeconds) {
      this(
          maxTripsPerDuty,
          maxDutyDurationSeconds,
          TurnaroundTable.fixed(fixedTurnaroundSeconds),
          maxIdleSeconds);
    }

    public static Limits defaults() {
      return new Limits(DEFAULT_MAX_TRIPS, DEFAULT_MAX_DURATION_SECONDS, TurnaroundTable.none());
    }
  }

  /** 班次排不进任何 duty 的原因。 */
  public enum UnassignedReason {
    /** 起点没有 CREATE 线路，也没有接得上的待命车。 */
    NO_CREATE_ACCESS,
    /** 终点没有 RETURN 线路，且后面接不上能回库的班次。 */
    NO_RETURN_ACCESS,
    /** 单独这一班连同出库、回库走行就已经超过 duty 的时长上限。 */
    EXCEEDS_DUTY_LIMITS,
    /** 单股道端点排队：等端点空出来会让交路超过时长上限或越过计划窗口，从这一班起截断交路。 */
    STUB_SATURATED
  }

  /**
   * 排不进任何 duty 的班次。
   *
   * @param tripId 班次 UUID
   * @param tripCode 班次号
   * @param reason 原因
   */
  public record UnassignedTrip(UUID tripId, String tripCode, UnassignedReason reason) {
    public UnassignedTrip {
      Objects.requireNonNull(tripId, "tripId");
      Objects.requireNonNull(reason, "reason");
      tripCode = tripCode == null ? "" : tripCode;
    }
  }

  /**
   * 打包结果。
   *
   * @param duties 全部 duty，按出库时刻升序
   * @param notes 规划说明
   * @param spawnedVehicles 需要从库里取出的实体车次数（= duty 数）
   * @param unassigned 因出库/回库途径缺失而排不进 duty 的班次
   */
  public record Result(
      List<VehicleDuty> duties,
      List<String> notes,
      int spawnedVehicles,
      List<UnassignedTrip> unassigned) {

    public Result {
      duties = duties == null ? List.of() : List.copyOf(duties);
      notes = notes == null ? List.of() : List.copyOf(notes);
      unassigned = unassigned == null ? List.of() : List.copyOf(unassigned);
    }

    /** 最长 duty 的班次数。 */
    public int maxTripsInAnyDuty() {
      return duties.stream().mapToInt(VehicleDuty::tripCount).max().orElse(0);
    }

    /** 最长 duty 的在线时间（秒）。 */
    public int maxDutyDurationSeconds() {
      return duties.stream().mapToInt(VehicleDuty::plannedDurationSeconds).max().orElse(0);
    }

    /** 是否每个 duty 都有回库端点。构建产物上可直接断言的终止性。 */
    public boolean allDutiesReturnToStorage() {
      return duties.stream().allMatch(duty -> !duty.endDepotNodeId().isBlank());
    }

    /**
     * 同一时刻最多有多少辆车在线。
     *
     * <p>跟 operator 车数上限比的是这个数，不是 duty 总数：一天跑 40 个 duty 可能只需要 6 辆车。 「一个量只能和同一个量比」。
     */
    public int peakConcurrentVehicles() {
      List<int[]> events = new ArrayList<>(duties.size() * 2);
      for (VehicleDuty duty : duties) {
        events.add(new int[] {duty.plannedStartSecondOfDay(), 1});
        events.add(new int[] {duty.plannedEndSecondOfDay(), -1});
      }
      // 同一秒先减后加：一辆车回库的那一秒另一辆出库，不算两辆同时在线。
      events.sort(Comparator.<int[]>comparingInt(e -> e[0]).thenComparingInt(e -> e[1]));
      int current = 0;
      int peak = 0;
      for (int[] event : events) {
        current += event[1];
        peak = Math.max(peak, current);
      }
      return peak;
    }
  }
}
