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
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.consist.ConsistSelector;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteTerminals;

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
 *
 * <p>接续偏好相同时，正线折返点取最晚就绪的车，其他端点取最早就绪的车。正线没有站台可供待命；没有 RETURN
 * 线路时，先到车接班会在正线上多等一个组间隔、挡住同股道后车，后到车的上一班反而被收口逻辑取消，正线停留约为一个组间隔加折返时间。
 * 改取后车把选车引入的等待压回最短折返，未被接走且无法回库的前车班次仍由现有收口逻辑剔除。
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
   * @param nextSlotByOrigin 各起点上后续班次的名义发车时刻（升序）；封口判据用它回答"下一班最早什么时候"。 为空时退化为不按闲置上限收口——只关心班次链的用例可以不传。
   * @return 打包结果
   */
  public static Result plan(
      UUID timetableId,
      List<PlannedTrip> trips,
      Legs legs,
      Limits limits,
      Map<String, NavigableSet<Integer>> nextSlotByOrigin) {
    return plan(timetableId, trips, legs, limits, nextSlotByOrigin, Map.of());
  }

  /**
   * 指派车辆并封装 duty，按相位层定下的接续优先选车。
   *
   * <p>相位层按车接续时（例如小交路 1L 到端点折返后接大交路 2C），被接那一班的时刻就是为喂车方向的车排的。
   * 若仍按"最早就绪优先"选车，端点上先到、本该回库的别路车（2N）会把这一班抢走， 喂车方向的车只好等下一班——开班时抢一次，之后每一辆都晚一个周期接上，全天推后而且排不掉。
   * 所以接续的被接班次先在喂车方向的车里挑，挑不到才退回全体候选。
   *
   * @param preferredFeeders 被接 route → 喂车 route 集合；空表示不按接续偏好
   * @see #plan(UUID, List, Legs, Limits, Map)
   */
  public static Result plan(
      UUID timetableId,
      List<PlannedTrip> trips,
      Legs legs,
      Limits limits,
      Map<String, NavigableSet<Integer>> nextSlotByOrigin,
      Map<UUID, Set<UUID>> preferredFeeders) {
    Objects.requireNonNull(timetableId, "timetableId");
    Objects.requireNonNull(limits, "limits");
    Map<UUID, Set<UUID>> feedersByRoute = preferredFeeders == null ? Map.of() : preferredFeeders;
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
    int minTripDuration = ordered.stream().mapToInt(PlannedTrip::minDuration).min().orElse(0);
    int minClosingTail = access.minClosingTail(ordered, limits);
    // 车型按班次份额记账：开新交路时选欠得最多的车型，接班并列时也偏向它。
    ConsistSelector shares = new ConsistSelector();

    List<OpenDuty> open = new ArrayList<>();
    List<OpenDuty> closed = new ArrayList<>();
    List<UnassignedTrip> unassigned = new ArrayList<>();
    int dutySequence = 0;

    for (PlannedTrip trip : ordered) {
      List<Optional<String>> ranked = rankConsists(shares, trip);
      HostChoice choice =
          selectHost(
              open, trip, ranked, access, limits, minTripDuration, minClosingTail, feedersByRoute);
      OpenDuty host = choice.host();
      if (host == null) {
        Opening opening =
            opening(
                trip,
                ranked,
                choice.consistBlocked(),
                access,
                limits,
                minTripDuration,
                minClosingTail);
        if (opening.blocker() != null) {
          unassigned.add(new UnassignedTrip(trip.tripId(), trip.tripCode(), opening.blocker()));
          continue;
        }
        host = open(dutySequence, trip, opening.consist(), access, limits);
        open.add(host);
        dutySequence++;
      }
      host.accept(trip, limits);
      if (!trip.consists().isEmpty() && host.consist.isPresent()) {
        shares.record(ledgerKey(trip), "", host.consist.get());
      }
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
    // 原因如实写：车就绪之后窗口里再没有任何发车才是 HORIZON_END；之后还有班次、只是没轮到它（被别的车接走、
    // 等不起、终点对不上）的是 NO_COMPATIBLE_NEXT——否则凌晨就回库的车也会被标成"窗口结束"。
    int lastDeparture = ordered.get(ordered.size() - 1).departureSeconds();
    for (OpenDuty duty : List.copyOf(open)) {
      boolean trimmed = false;
      while (!duty.trips.isEmpty() && !access.closable(duty.lastTrip(), duty.consist)) {
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
          trimmed || duty.readyAtSeconds <= lastDeparture
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
              duty.closeReason(),
              duty.consist()));
    }
    unassigned.sort(Comparator.comparing(UnassignedTrip::tripCode));
    return new Result(
        List.copyOf(renumbered), List.of(), renumbered.size(), List.copyOf(unassigned));
  }

  /**
   * 选择能接下这一班的已开 duty。
   *
   * <p>条件缺一不可：位置对得上（上一班的终点就是这一班的起点）、时间来得及（含折返时间）、
   * 接下后仍不越过硬上限、并且接下之后这辆车仍然回得了库（终点有回库线路，或还有余量再跑一班到有回库线路的终点）。 都满足时先看接续偏好（上一班是这一班的喂车 route），
   * 再按起点类型选车：{@link RouteTerminals#isMainlineTurnback} 判定的正线折返点取"最晚就绪"者，缩短占道等待；
   * 站台、车库、咽喉等其他端点仍取"最早就绪"者，避免端点长期闲置。并列时按 duty 序号——完全确定。
   *
   * <p>正线端没有站台可停、也没有 RETURN 线路时，先到车接班会多占正线一个间隔（停留约为组间隔加折返时间），
   * 后到车的上一班反而在收口时被取消；反过来选车才让留下的班次最短折返，不改变无法回库班次的剔除规则。
   *
   * <p>从车库始发的班次（CRET）永远不接在别的 duty 后面：它的出库票会实体化一辆新车，接不了待命列车。 不同车池（多线联编时的不同线路）之间也永远不接。
   */
  private static HostChoice selectHost(
      List<OpenDuty> open,
      PlannedTrip trip,
      List<Optional<String>> ranked,
      Legs access,
      Limits limits,
      int minTripDuration,
      int minClosingTail,
      Map<UUID, Set<UUID>> preferredFeeders) {
    if (trip.startsAtDepot()) {
      return new HostChoice(null, false);
    }
    Set<UUID> feeders =
        trip.routeId() == null ? Set.of() : preferredFeeders.getOrDefault(trip.routeId(), Set.of());
    boolean latestFirst = RouteTerminals.isMainlineTurnback(trip.originNodeId());
    OpenDuty best = null;
    boolean bestPreferred = false;
    int bestRank = Integer.MAX_VALUE;
    boolean consistBlocked = false;
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
      boolean closable = access.closable(trip, duty.consist);
      if (!closable && duty.tripCount() + 1 >= limits.maxTripsPerDuty()) {
        // 接完这一班就到班次上限，却停在一个回不了库的终点。
        continue;
      }
      // 上限覆盖整个 duty，包含最后回库那一段。终点回不了库时，至少要留出"再跑最短的一班到能回库的终点"的余量，
      // 否则这辆车会被困在那里。
      int tail =
          closable
              ? access.closingTail(trip, duty.consist, limits)
              : limits.turnaround().minimumSeconds() + minTripDuration + minClosingTail;
      int endIfAccepted = trip.departureSeconds() + trip.durationFor(duty.consist) + tail;
      if (endIfAccepted - duty.startSeconds > limits.maxDutyDurationSeconds()) {
        continue;
      }
      if (!trip.allows(duty.consist)) {
        // 其余条件都满足，只是车型不许跑这一班：开不出新交路时报 CONSIST_MISMATCH 而不是缺出库线路。
        // 放在最后判，被班次或时长上限挡下的车不算"车型不对"。
        consistBlocked = true;
        continue;
      }
      UUID lastRoute = duty.lastTrip().routeId();
      boolean preferred = lastRoute != null && feeders.contains(lastRoute);
      int rank = ranked.indexOf(duty.consist);
      rank = rank < 0 ? Integer.MAX_VALUE : rank;
      if (best == null
          || (preferred && !bestPreferred)
          || (preferred == bestPreferred
              && ((latestFirst
                      ? duty.readyAtSeconds > best.readyAtSeconds
                      : duty.readyAtSeconds < best.readyAtSeconds)
                  || (duty.readyAtSeconds == best.readyAtSeconds
                      && (rank < bestRank
                          || (rank == bestRank && duty.sequence < best.sequence)))))) {
        best = duty;
        bestPreferred = preferred;
        bestRank = rank;
      }
    }
    return new HostChoice(best, consistBlocked);
  }

  /**
   * 接班结果。
   *
   * @param host 接这一班的交路；没有时为 null
   * @param consistBlocked 有车在起点、其余接班条件都满足，只是车型不许跑这一班
   */
  private record HostChoice(OpenDuty host, boolean consistBlocked) {}

  /**
   * 新开交路的结果。
   *
   * @param consist 新交路的车型；不区分车型时为空
   * @param blocker 开不出来的原因；开得出来时为 null
   */
  private record Opening(Optional<String> consist, UnassignedReason blocker) {}

  /**
   * 这一班允许的车型，按班次份额欠得多少排先后；不区分车型时只有一个"空车型"。
   *
   * <p>记账以 route 为单位（同一 route 的各班共用一本账），与运行时按 route 记账同一口径。
   */
  private static List<Optional<String>> rankConsists(ConsistSelector shares, PlannedTrip trip) {
    if (trip.consists().isEmpty()) {
      return List.of(Optional.empty());
    }
    List<ConsistSelector.Weighted> weights = new ArrayList<>(trip.consists().size());
    for (ConsistOption option : trip.consists()) {
      weights.add(new ConsistSelector.Weighted(option.key(), option.weight()));
    }
    List<Optional<String>> ranked = new ArrayList<>(weights.size());
    for (String key : shares.rank(ledgerKey(trip), "", weights)) {
      ranked.add(Optional.of(key));
    }
    return ranked;
  }

  private static UUID ledgerKey(PlannedTrip trip) {
    return trip.routeId() == null ? new UUID(0L, 0L) : trip.routeId();
  }

  /**
   * 在这一班上新开交路：按车型的先后逐个试，第一个开得出来的就用它。
   *
   * <p>都开不出来时报排第一的车型的原因；只是因为起点没有能出这些车型的出库线路、而起点其实有别的车型的车在等时，报 {@link
   * UnassignedReason#CONSIST_MISMATCH}。
   */
  private static Opening opening(
      PlannedTrip trip,
      List<Optional<String>> ranked,
      boolean consistBlocked,
      Legs access,
      Limits limits,
      int minTripDuration,
      int minClosingTail) {
    UnassignedReason first = null;
    boolean onlyMissingCreate = true;
    for (Optional<String> consist : ranked) {
      UnassignedReason blocker =
          openBlocker(trip, consist, access, limits, minTripDuration, minClosingTail);
      if (blocker == null) {
        return new Opening(consist, null);
      }
      if (first == null) {
        first = blocker;
      }
      onlyMissingCreate &= blocker == UnassignedReason.NO_CREATE_ACCESS;
    }
    boolean mismatch = consistBlocked && onlyMissingCreate && !trip.consists().isEmpty();
    return new Opening(Optional.empty(), mismatch ? UnassignedReason.CONSIST_MISMATCH : first);
  }

  /**
   * 这一班能不能作为一个新 duty 的首班；不能时给出原因。
   *
   * <p>三种阻塞：起点没有出库途径；单独这一班连同出库、回库走行就已经超过 duty 时长上限； 终点回不了库、而且余量也不够再跑一班到能回库的终点。
   */
  private static UnassignedReason openBlocker(
      PlannedTrip trip,
      Optional<String> consist,
      Legs access,
      Limits limits,
      int minTripDuration,
      int minClosingTail) {
    int start;
    if (trip.startsAtDepot()) {
      start = trip.departureSeconds();
    } else {
      Optional<Leg> create = access.createLegAt(trip.originNodeId(), consist);
      if (create.isEmpty()) {
        return UnassignedReason.NO_CREATE_ACCESS;
      }
      start = openingStart(trip, create.get(), limits);
    }
    boolean closable = access.closable(trip, consist);
    int tail =
        closable
            ? access.closingTail(trip, consist, limits)
            : limits.turnaround().minimumSeconds() + minTripDuration + minClosingTail;
    int end = trip.departureSeconds() + trip.durationFor(consist) + tail;
    if (end - start > limits.maxDutyDurationSeconds()) {
      return closable ? UnassignedReason.EXCEEDS_DUTY_LIMITS : UnassignedReason.NO_RETURN_ACCESS;
    }
    if (!closable && limits.maxTripsPerDuty() < 2) {
      return UnassignedReason.NO_RETURN_ACCESS;
    }
    return null;
  }

  /** 在这一班上开一个新 duty。前提是 {@link #openBlocker} 对这个车型返回 null。 */
  private static OpenDuty open(
      int sequence, PlannedTrip trip, Optional<String> consist, Legs access, Limits limits) {
    if (trip.startsAtDepot()) {
      return new OpenDuty(
          sequence,
          trip.originNodeId(),
          Optional.empty(),
          trip.departureSeconds(),
          trip.pool(),
          consist);
    }
    Leg leg = access.createLegAt(trip.originNodeId(), consist).orElseThrow();
    return new OpenDuty(
        sequence,
        leg.depotNodeId(),
        Optional.of(leg.routeId()),
        openingStart(trip, leg, limits),
        trip.pool(),
        consist);
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
    if (!access.closable(last, duty.consist)) {
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
   * <p>没有传时隙表时一律返回 false——退化成不按闲置收口，而不是把所有 duty 都按闲置收口。
   */
  private static boolean idleBeyondLimit(
      OpenDuty duty, Limits limits, Map<String, NavigableSet<Integer>> nextSlotByOrigin) {
    if (nextSlotByOrigin == null || nextSlotByOrigin.isEmpty()) {
      return false;
    }
    // 区分车型时只看本车型能跑的发车：别的车型的班次再近也接不了。
    NavigableSet<Integer> slots = nextSlotByOrigin.get(slotKey(duty.lastTerminal, duty.consist));
    if (slots == null || slots.isEmpty()) {
      // 这个终点上没有任何本车型能跑的后续发车：等下去也等不到，交给"接不上"的常规收口。
      return false;
    }
    Integer next = slots.ceiling(duty.readyAtSeconds);
    return next == null || next - duty.readyAtSeconds > limits.maxIdleSeconds();
  }

  /**
   * 闲置判据用的时隙表键：区分车型时，一辆车在终点等的是它的车型能跑的下一班，不是随便哪一班。
   *
   * @param origin 起点节点
   * @param consist 车型；不区分车型时为空，键就是起点节点
   * @return 时隙表键
   */
  public static String slotKey(String origin, Optional<String> consist) {
    return consist.map(key -> origin + "|" + key).orElse(origin);
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
    private final Optional<String> consist;
    private final List<PlannedTrip> trips = new ArrayList<>();
    private final List<Integer> durations = new ArrayList<>();
    private String lastTerminal;
    private int endSeconds;
    private int readyAtSeconds;
    private VehicleDuty.CloseReason closeReason;

    private OpenDuty(
        int sequence,
        String startDepot,
        Optional<UUID> createRouteId,
        int startSeconds,
        String pool,
        Optional<String> consist) {
      this.sequence = sequence;
      this.startDepot = startDepot;
      this.createRouteId = createRouteId;
      this.startSeconds = startSeconds;
      this.pool = pool;
      this.consist = consist;
      this.lastTerminal = "";
      this.endSeconds = startSeconds;
      this.readyAtSeconds = startSeconds;
    }

    private void accept(PlannedTrip trip, Limits limits) {
      trips.add(trip);
      durations.add(trip.durationFor(consist));
      refresh(limits);
    }

    private PlannedTrip popLast() {
      PlannedTrip removed = trips.remove(trips.size() - 1);
      durations.remove(durations.size() - 1);
      // 退掉尾段后 lastTerminal/endSeconds 只在 toDuty 里再用，那里会按剩余班次重算。
      if (!trips.isEmpty()) {
        PlannedTrip last = lastTrip();
        lastTerminal = last.terminalNodeId();
        endSeconds = last.departureSeconds() + lastDuration();
      }
      return removed;
    }

    /** 最后一班按本交路车型跑的全程时分。 */
    private int lastDuration() {
      return durations.get(durations.size() - 1);
    }

    private void refresh(Limits limits) {
      PlannedTrip last = lastTrip();
      lastTerminal = last.terminalNodeId();
      endSeconds = last.departureSeconds() + lastDuration();
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
      int arrival = last.departureSeconds() + lastDuration();
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
                .returnLegAt(last.terminalNodeId(), startDepot, consist)
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
          closeReason,
          consist);
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
   * @param consists 允许跑这一班的车型、各自的目标份额权重与全程时分；为空表示不区分车型（{@code durationSeconds} 即全程时分）
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
      String pool,
      List<ConsistOption> consists) {

    /** 不区分车型的班次。 */
    public PlannedTrip(
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
          pool,
          List.of());
    }

    public PlannedTrip {
      consists = consists == null ? List.of() : List.copyOf(consists);
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

    /** 这个车型许不许跑这一班。不区分车型的班次谁都能跑。 */
    public boolean allows(Optional<String> consist) {
      if (consists.isEmpty()) {
        return true;
      }
      return consist.isPresent() && option(consist.get()).isPresent();
    }

    /** 按这个车型跑的全程时分；不区分车型或车型不在其中时取 {@code durationSeconds}。 */
    public int durationFor(Optional<String> consist) {
      return consist
          .flatMap(this::option)
          .map(ConsistOption::durationSeconds)
          .orElse(durationSeconds);
    }

    /** 允许车型里最短的全程时分（封口下界用）。 */
    int minDuration() {
      return consists.stream()
          .mapToInt(ConsistOption::durationSeconds)
          .min()
          .orElse(durationSeconds);
    }

    private Optional<ConsistOption> option(String key) {
      for (ConsistOption option : consists) {
        if (option.key().equals(key)) {
          return Optional.of(option);
        }
      }
      return Optional.empty();
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
   * 允许跑某一班的一个车型。
   *
   * @param key 车型键
   * @param weight 这条 route 上该车型的目标份额权重
   * @param durationSeconds 按这个车型跑的全程时分
   */
  public record ConsistOption(String key, int weight, int durationSeconds) {
    public ConsistOption {
      Objects.requireNonNull(key, "key");
      if (weight <= 0) {
        throw new IllegalArgumentException("weight 必须为正数");
      }
      if (durationSeconds < 0) {
        throw new IllegalArgumentException("durationSeconds 不能为负");
      }
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
   * @param consist 这段走行按哪个车型算的时分（{@code routeId} 此时是该车型的变体 route）；不区分车型时为空
   */
  public record Leg(
      UUID routeId,
      String routeCode,
      String depotNodeId,
      int runSeconds,
      boolean declared,
      Optional<String> consist) {
    public Leg(UUID routeId, String routeCode, String depotNodeId, int runSeconds) {
      this(routeId, routeCode, depotNodeId, runSeconds, false);
    }

    public Leg(
        UUID routeId, String routeCode, String depotNodeId, int runSeconds, boolean declared) {
      this(routeId, routeCode, depotNodeId, runSeconds, declared, Optional.empty());
    }

    public Leg {
      consist = consist == null ? Optional.empty() : consist;
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
   * @param createByStation 按首站节点索引的不分车型的 CREATE 段（每站排第一的那一条）
   * @param returnCandidates 按末站节点索引的<b>全部</b> RETURN 段，组内已按"显式指定 &gt; 走行最短"排序
   * @param createCandidates 按首站节点索引的<b>全部</b> CREATE 段，排序同上；区分车型时每个车型各有一条变体
   */
  public record Legs(
      Map<String, Leg> createByStation,
      Map<String, List<Leg>> returnCandidates,
      Map<String, List<Leg>> createCandidates) {

    /** 每站只有一条出库段：候选就是 {@code createByStation}。 */
    public Legs(Map<String, Leg> createByStation, Map<String, List<Leg>> returnCandidates) {
      this(createByStation, returnCandidates, candidatesOf(createByStation));
    }

    public Legs {
      createByStation = createByStation == null ? Map.of() : Map.copyOf(createByStation);
      returnCandidates = freeze(returnCandidates);
      createCandidates = freeze(createCandidates);
    }

    private static Map<String, List<Leg>> candidatesOf(Map<String, Leg> byStation) {
      Map<String, List<Leg>> out = new LinkedHashMap<>();
      if (byStation != null) {
        byStation.forEach(
            (station, leg) -> {
              if (station != null && leg != null) {
                out.put(station, List.of(leg));
              }
            });
      }
      return out;
    }

    private static Map<String, List<Leg>> freeze(Map<String, List<Leg>> source) {
      Map<String, List<Leg>> frozen = new LinkedHashMap<>();
      if (source != null) {
        source.forEach(
            (station, legs) -> {
              if (station != null && legs != null && !legs.isEmpty()) {
                frozen.put(station, List.copyOf(legs));
              }
            });
      }
      return Map.copyOf(frozen);
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

    /**
     * 从走行段列表建索引；同一站有多条时先取显式指定的，再取走行最短的，并列按 routeCode 再按 routeId——确定性。
     *
     * <p>{@code createByStation} 只收不分车型的段：区分车型时那是基础 route（时分取允许车型里最慢的），不分车型的查询 不能因为快车型的变体走得短就选中它。
     */
    public static Legs of(List<Leg> creates, List<Leg> returns, Map<UUID, String> stationByLeg) {
      Map<String, Leg> create = new LinkedHashMap<>();
      Map<String, List<Leg>> ret = new LinkedHashMap<>();
      Map<String, List<Leg>> createAll = new LinkedHashMap<>();
      index(
          create,
          creates == null
              ? List.of()
              : creates.stream().filter(leg -> leg != null && leg.consist().isEmpty()).toList(),
          stationByLeg);
      indexAll(ret, returns, stationByLeg);
      indexAll(createAll, creates, stationByLeg);
      return new Legs(create, ret, createAll);
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

    /**
     * 能把这个车型送到这一站的出库段：只认按这个车型算的变体（不区分车型时只认不区分车型的段）。
     *
     * @param stationNodeId 首站节点
     * @param consist 车型；不区分车型时为空
     */
    public Optional<Leg> createLegAt(String stationNodeId, Optional<String> consist) {
      List<Leg> candidates = createCandidates.get(stationNodeId);
      if (candidates == null) {
        return Optional.empty();
      }
      for (Leg leg : candidates) {
        if (leg.consist().equals(consist)) {
          return Optional.of(leg);
        }
      }
      return Optional.empty();
    }

    /** 不带偏好、不分车型的回库段：只回答"这个终点能不能回库"。 */
    public Optional<Leg> returnLegAt(String stationNodeId) {
      return returnLegAt(stationNodeId, null);
    }

    /**
     * 带车库偏好的回库段：<b>回自己出库的那个车库</b>的优先，其次显式指定的，再次走行最短。
     *
     * <p>按最短选会让一条线的车全部涌进离终点最近的那个库， 别的线的回库走行就在同一段咽喉上和它们撞。回原库是运营常识，也让每条线的回库流各走各的。
     *
     * <p>只认不分车型的段：区分车型时那是基础 route（时分取允许车型里最慢的），与相位层按最慢车型锚定同一口径。
     *
     * @param stationNodeId 终到节点
     * @param preferredDepotNodeId 本交路的出库车库节点；为空时退化为无偏好
     */
    public Optional<Leg> returnLegAt(String stationNodeId, String preferredDepotNodeId) {
      return returnLegAt(stationNodeId, preferredDepotNodeId, Optional.empty());
    }

    /** 带车库偏好、按车型的回库段：只认按这个车型算的变体（不区分车型时只认不区分车型的段），其余同 {@link #returnLegAt(String, String)}。 */
    public Optional<Leg> returnLegAt(
        String stationNodeId, String preferredDepotNodeId, Optional<String> consist) {
      List<Leg> candidates = returnCandidates.get(stationNodeId);
      if (candidates == null) {
        return Optional.empty();
      }
      return pickReturn(
          candidates.stream().filter(leg -> leg.consist().equals(consist)).toList(),
          preferredDepotNodeId);
    }

    private static Optional<Leg> pickReturn(List<Leg> candidates, String preferredDepotNodeId) {
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

    /** 这个车型跑完这一班之后能不能回库：终点要有按这个车型算的回库段。 */
    boolean closable(PlannedTrip trip, Optional<String> consist) {
      return trip.endsAtDepot() || returnLegAt(trip.terminalNodeId(), null, consist).isPresent();
    }

    /** 这个车型跑完这一班到回到车库还要多久（含折返）。前提是 {@link #closable(PlannedTrip, Optional)}。 */
    int closingTail(PlannedTrip trip, Optional<String> consist, Limits limits) {
      if (trip.endsAtDepot()) {
        return 0;
      }
      Leg leg = returnLegAt(trip.terminalNodeId(), null, consist).orElse(null);
      return leg == null ? 0 : limits.turnaround().secondsFor(trip.routeId()) + leg.runSeconds();
    }

    /**
     * 所有可能的收尾里最短的一种，用作"还装不装得下"的下界。
     *
     * <p>每个车型取它在该终点会选的那条回库段（候选里该车型排第一的），再在车型之间取最短；不区分车型时就是该终点排第一的那条。
     */
    int minClosingTail(List<PlannedTrip> trips, Limits limits) {
      int min = Integer.MAX_VALUE;
      for (PlannedTrip trip : trips) {
        if (trip.endsAtDepot()) {
          min = 0;
          continue;
        }
        List<Leg> candidates = returnCandidates.get(trip.terminalNodeId());
        if (candidates == null) {
          continue;
        }
        java.util.Set<Optional<String>> seen = new java.util.HashSet<>();
        for (Leg leg : candidates) {
          if (seen.add(leg.consist())) {
            min = Math.min(min, limits.turnaround().secondsFor(trip.routeId()) + leg.runSeconds());
          }
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

    /** 与 {@code reclaim.max-idle-seconds} 的默认值一致；命令层会用服务器配置覆盖它。 */
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

    /** 带闲置上限的显式折返值，供用例把期望钉在"不回收"上（{@code maxIdle} 给很大）。 */
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
    STUB_SATURATED,
    /** 起点有车在等，但车型都不许跑这一班；起点也没有能出许可车型的出库线路。 */
    CONSIST_MISMATCH
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
