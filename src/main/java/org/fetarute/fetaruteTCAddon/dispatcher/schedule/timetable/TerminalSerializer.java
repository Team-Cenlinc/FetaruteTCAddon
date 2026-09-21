package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SingleLineSectionIndex;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SingleLineSectionInfo;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.scope.NeighborTimetable;

/**
 * 容量为 1 的端点按资源串行。
 *
 * <p>一根股道的尽头站（CHT 那种支线端点）进出共用同一段岔线，一次折返就是一次独占：进站走行、折返、出站走行。 全局网格把发车钉在 headway
 * 的整数倍上，车到了端点却要等下一个格子，这段等待整段算作站台占用，端点立刻就满了。 这里把这类端点当成串行资源：
 *
 * <ul>
 *   <li>进站班次：到达要等端点空出来，差值加到这一班的发车上——整趟延后，在它的起点等。<b>只延后，永不提前。</b>
 *   <li>续班（起点在端点的同交路下一班）：发车 = 到达 + 折返，不等网格；可能早于也可能晚于它的名义时隙。
 *   <li>其余班次：发车 = max(名义时隙, 本车就绪)，与今天相同。
 *   <li>邻表在端点的占用是预订：我的进站只能落在空档里，邻表的运行一动不动。
 * </ul>
 *
 * <p>确定性：事件按（最早可发时刻，车次 code）排序；端点空闲表按站台组键排序；不引入随机源，也不依赖哈希遍历序。
 * 延后让交路超过时长上限或越过计划窗口时，从那一班起截断交路并如实上报，而不是排一班到不了的车。
 *
 * <p>只处理站台组容量为 1 且是某条 OPERATION route 起点或终点的组；车库咽喉、多股道车站一律不碰。
 */
public final class TerminalSerializer {

  private TerminalSerializer() {}

  /**
   * 输入。临时表里的时刻约定：{@code departureSecondOfDay = zero + 相对秒}，不取模——串行只在相对秒上算，取模留给最终编号。
   *
   * @param provisional 派车后的临时表（trips 的 code 是临时 code，时刻是名义时隙；duties 引用临时 trip id）
   * @param profiles 各 route 投影（含 CREATE/RETURN）
   * @param index 图索引
   * @param zeroSecondOfDay 零点 = 计划窗口起点
   * @param horizonSeconds 计划窗口长度
   * @param separationSeconds 裕量
   * @param legs 出库/回库走行段
   * @param limits 交路硬上限
   * @param routesEndingAtDepot 以销毁收尾的 route（跑完即回库，不需要回库线路）
   * @param candidates SWRR 候选（算按权重的下界）
   * @param operationPlans 运营 route 计划，与 candidates 下标对齐
   * @param neighbors 已投影到我零点的邻表
   */
  public record Input(
      Timetable provisional,
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
      TimetableConflictChecker.GraphIndex index,
      int zeroSecondOfDay,
      int horizonSeconds,
      int separationSeconds,
      VehicleDutyPlanner.Legs legs,
      VehicleDutyPlanner.Limits limits,
      Set<UUID> routesEndingAtDepot,
      List<WeightedTripAllocator.Candidate> candidates,
      List<TimetableRoutePlan> operationPlans,
      List<NeighborTimetable> neighbors) {

    public Input {
      Objects.requireNonNull(provisional, "provisional");
      profiles = profiles == null ? Map.of() : Map.copyOf(profiles);
      index = index == null ? TimetableConflictChecker.GraphIndex.of(null) : index;
      legs = legs == null ? VehicleDutyPlanner.Legs.none() : legs;
      limits = limits == null ? VehicleDutyPlanner.Limits.defaults() : limits;
      routesEndingAtDepot =
          routesEndingAtDepot == null ? Set.of() : Set.copyOf(routesEndingAtDepot);
      candidates = candidates == null ? List.of() : List.copyOf(candidates);
      operationPlans = operationPlans == null ? List.of() : List.copyOf(operationPlans);
      neighbors = neighbors == null ? List.of() : List.copyOf(neighbors);
    }
  }

  /** 一班的时刻偏离：名义时隙与实际发车（相对秒），以及为什么。 */
  public record Shift(UUID tripId, int nominalSeconds, int actualSeconds, Reason reason) {
    public enum Reason {
      /** 终点是单股道端点，等它空出来。 */
      WAIT_FOR_TERMINAL,
      /** 起点是单股道端点，发车锚在车上（到达 + 折返）。 */
      ANCHORED_TO_VEHICLE,
      /** 本车上一班延后了，就绪晚于名义时隙。 */
      VEHICLE_READY,
      /** 让车：与前车在某个资源上撞了，整趟延后到前车离开之后（{@link ResourceRepair}）。 */
      YIELDED
    }
  }

  /**
   * 一个端点的统计。
   *
   * @param group 站台组
   * @param visits 经过（折返）次数
   * @param occupiedSeconds 占用秒数
   * @param utilization 占用 / 计划窗口；超过 1 说明目标间隔本身在结构上不可能
   * @param approachInSeconds 最长进站走行
   * @param approachOutSeconds 最长出站走行
   * @param separationSeconds 裕量
   * @param visitCostSeconds 每次折返占用 = 进站 + 折返 + 出站 + 裕量
   * @param cycleTrips SWRR 一个周期的班次数（Σweight）
   * @param visitsPerCycle 一个周期内按权重经过该端点的次数
   * @param headwayFloorSeconds 按权重满额时的全线间隔下界
   * @param nowhereToWait 起点也是单股道端点、无处等待因而没有延后的进站班次数
   * @param truncated 因排队超限而截断的班次数
   */
  public record TerminalReport(
      String group,
      int visits,
      int occupiedSeconds,
      double utilization,
      int approachInSeconds,
      int approachOutSeconds,
      int separationSeconds,
      int visitCostSeconds,
      int cycleTrips,
      double visitsPerCycle,
      int headwayFloorSeconds,
      int nowhereToWait,
      int truncated) {}

  /**
   * 输出。
   *
   * @param timetable 时刻已改写的表（trips、duties 的 plannedStart / returnSecond / plannedEnd 跟着变；截断的交路少了尾段）
   * @param shifts 偏离名义时隙的班次
   * @param truncatedTripIds 被截断的班次（临时 id）
   * @param terminals 各端点报告，按组键排序
   */
  public record Result(
      Timetable timetable,
      List<Shift> shifts,
      List<UUID> truncatedTripIds,
      List<TerminalReport> terminals) {
    public Result {
      Objects.requireNonNull(timetable, "timetable");
      shifts = shifts == null ? List.of() : List.copyOf(shifts);
      truncatedTripIds = truncatedTripIds == null ? List.of() : List.copyOf(truncatedTripIds);
      terminals = terminals == null ? List.of() : List.copyOf(terminals);
    }
  }

  /** 串行主入口。没有容量 1 的端点时原样返回。 */
  public static Result serialize(Input input) {
    Objects.requireNonNull(input, "input");
    Timetable table = input.provisional();
    List<TimetableConflictChecker.RouteProfile> operationProfiles = new ArrayList<>();
    for (TimetableRoutePlan plan : input.operationPlans()) {
      TimetableConflictChecker.RouteProfile profile = input.profiles().get(plan.routeId());
      if (profile != null) {
        operationProfiles.add(profile);
      }
    }
    List<String> terminals = terminalGroups(input.index(), operationProfiles);
    if (terminals.isEmpty()) {
      return new Result(table, List.of(), List.of(), List.of());
    }
    Set<String> terminalSet = new HashSet<>(terminals);
    int zero = input.zeroSecondOfDay();
    TurnaroundTable turnarounds = input.limits().turnaround();
    int separation = Math.max(0, input.separationSeconds());

    List<VehicleDuty> duties = table.duties();
    List<List<TimetableTrip>> chains = chainsOf(table);

    Map<String, List<int[]>> bookings = new TreeMap<>();
    for (String group : terminals) {
      bookings.put(group, neighborBookings(input.neighbors(), group, separation));
    }
    Map<String, Integer> freeAt = new TreeMap<>();
    Map<String, int[]> stats = new TreeMap<>(); // visits, occupied, nowhereToWait, truncated

    int[] next = new int[duties.size()];
    int[] ready = new int[duties.size()];
    int[] kept = new int[duties.size()];
    Map<UUID, Integer> actual = new HashMap<>();
    List<Shift> shifts = new ArrayList<>();
    Set<UUID> truncated = new TreeSet<>();
    PriorityQueue<Event> queue =
        new PriorityQueue<>(
            Comparator.comparingInt(Event::key)
                .thenComparing(Event::code)
                .thenComparingInt(Event::duty));
    for (int d = 0; d < duties.size(); d++) {
      List<TimetableTrip> chain = chains.get(d);
      kept[d] = chain.size();
      if (chain.isEmpty()) {
        continue;
      }
      int nominal = chain.get(0).departureSecondOfDay() - zero;
      ready[d] = nominal;
      queue.add(new Event(nominal, chain.get(0).tripCode(), d));
    }

    while (!queue.isEmpty()) {
      Event event = queue.poll();
      int d = event.duty();
      List<TimetableTrip> chain = chains.get(d);
      VehicleDuty duty = duties.get(d);
      int i = next[d];
      TimetableTrip trip = chain.get(i);
      TimetableRoutePlan plan = table.routePlan(trip.routeId()).orElse(null);
      TimetableConflictChecker.RouteProfile profile = input.profiles().get(trip.routeId());
      // 折返按<b>本班次自己的</b> route 取：同一股道上快车停 20 秒、慢车停 30 秒是两个数。
      int turnaround = turnarounds.secondsFor(trip.routeId());
      if (plan == null || profile == null) {
        // 没有投影的 route 不参与串行：照名义时隙记，链继续。
        actual.put(trip.id(), trip.departureSecondOfDay() - zero);
        advance(d, chain, next, ready, table, input, queue, zero, terminalSet, ready[d]);
        continue;
      }
      int nominal = trip.departureSecondOfDay() - zero;
      boolean originTerminal =
          terminalSet.contains(TimetableConflictChecker.groupOf(plan.originNodeId()));
      int dep = originTerminal ? ready[d] : Math.max(nominal, ready[d]);
      Shift.Reason reason =
          originTerminal ? Shift.Reason.ANCHORED_TO_VEHICLE : Shift.Reason.VEHICLE_READY;
      int arrival = dep + plan.totalRunSeconds();
      String terminalGroup = TimetableConflictChecker.groupOf(plan.terminalNodeId());
      boolean terminalIsStub = terminalSet.contains(terminalGroup);
      int[] stat = null;
      int in = 0;
      if (terminalIsStub) {
        stat = stats.computeIfAbsent(terminalGroup, key -> new int[4]);
        in = approachIn(profile, terminalGroup, input.index().sections());
        int earliest =
            Math.max(arrival, freeAt.getOrDefault(terminalGroup, Integer.MIN_VALUE / 2) + in);
        boolean hasNext = i + 1 < chain.size();
        int out = outRunOf(chain, i, hasNext, duty, input, terminalGroup);
        earliest =
            slideAfterBookings(
                bookings.get(terminalGroup), earliest, in, turnaround + out + separation);
        if (earliest > arrival) {
          if (originTerminal) {
            // 起点也是单股道端点：延后就是占着起点不走，只会把冲突搬家。不延后，照常登记，交给冲突检查报出来。
            stat[2]++;
          } else {
            dep += earliest - arrival;
            arrival = earliest;
            reason = Shift.Reason.WAIT_FOR_TERMINAL;
          }
        }
      }
      if (dep != nominal
          && exceedsLimits(
              dep,
              plan,
              duty,
              input.horizonSeconds(),
              turnaround,
              input.routesEndingAtDepot(),
              input.legs(),
              input.limits(),
              zero)) {
        truncateFrom(
            i,
            chains.get(d),
            d,
            kept,
            actual,
            truncated,
            input.routesEndingAtDepot(),
            input.legs(),
            table);
        if (stat != null) {
          stat[3] = stat[3] + (chain.size() - i);
        }
        continue;
      }
      actual.put(trip.id(), dep);
      if (dep != nominal) {
        shifts.add(new Shift(trip.id(), nominal, dep, reason));
      }
      int nextReady = arrival + turnaround;
      if (terminalIsStub) {
        boolean hasNext = i + 1 < chain.size();
        int out = outRunOf(chain, i, hasNext, duty, input, terminalGroup);
        int cost = in + turnaround + out + separation;
        stat[0]++;
        stat[1] += cost;
        freeAt.put(terminalGroup, nextReady + out + separation);
      }
      advance(d, chain, next, ready, table, input, queue, zero, terminalSet, nextReady);
    }

    Timetable out =
        rewrite(
            table,
            chains,
            kept,
            actual,
            truncated,
            null,
            null,
            zero,
            turnarounds,
            input.routesEndingAtDepot(),
            input.legs());

    List<TerminalReport> reports = new ArrayList<>(terminals.size());
    Map<String, Floor> floors = floors(input, operationProfiles, terminals);
    for (String group : terminals) {
      int[] stat = stats.getOrDefault(group, new int[4]);
      Floor floor = floors.get(group);
      reports.add(
          new TerminalReport(
              group,
              stat[0],
              stat[1],
              input.horizonSeconds() <= 0 ? 0.0D : (double) stat[1] / input.horizonSeconds(),
              floor.in(),
              floor.out(),
              separation,
              floor.visitCost(),
              floor.cycleTrips(),
              floor.visitsPerCycle(),
              floor.headwayFloor(),
              stat[2],
              stat[3]));
    }
    shifts.sort(
        Comparator.comparingInt(Shift::actualSeconds)
            .thenComparing(shift -> shift.tripId().toString()));
    return new Result(out, shifts, new ArrayList<>(truncated), reports);
  }

  /**
   * 把改过的时刻写回表：trips 按 {@code actual}，duties 的 plannedStart / returnSecond / plannedEnd 跟着首末班走；
   * 截断的交路少了尾段并重新找回库线路。 串行与让车修复共用这一段，因为"改时刻不增减班次、交路仍要收口"的规则只能有一份。
   *
   * @param chains 每条 duty 的班次链（与 duties 对齐）
   * @param kept 每条链保留的班次数
   * @param actual 临时 id → 实际发车（相对秒）；没有的照原时刻
   * @param truncated 被截掉的班次 id，不落表
   * @param startDelay 每条 duty 出库票额外延后的秒数（可为 null）
   * @param returnDelay 每条 duty 回库票额外延后的秒数（可为 null）
   */
  static Timetable rewrite(
      Timetable table,
      List<List<TimetableTrip>> chains,
      int[] kept,
      Map<UUID, Integer> actual,
      Set<UUID> truncated,
      int[] startDelay,
      int[] returnDelay,
      int zero,
      TurnaroundTable turnarounds,
      Set<UUID> routesEndingAtDepot,
      VehicleDutyPlanner.Legs legs) {
    List<VehicleDuty> duties = table.duties();
    List<TimetableTrip> trips = new ArrayList<>(table.trips().size());
    for (TimetableTrip trip : table.trips()) {
      if (truncated.contains(trip.id())) {
        continue;
      }
      trips.add(retimed(trip, actual, zero));
    }
    List<VehicleDuty> rewritten = new ArrayList<>(duties.size());
    for (int d = 0; d < duties.size(); d++) {
      RewrittenDuty one =
          rewriteDuty(
              table,
              chains.get(d),
              d,
              duties.get(d),
              kept,
              actual,
              startDelay,
              returnDelay,
              zero,
              turnarounds,
              routesEndingAtDepot,
              legs);
      if (one != null) {
        rewritten.add(one.duty());
      }
    }
    Timetable out =
        new Timetable(
            table.id(),
            table.companyId(),
            table.operatorId(),
            table.lineId(),
            table.code(),
            table.name(),
            table.status(),
            table.zoneId(),
            table.serviceStartSecondOfDay(),
            table.serviceEndSecondOfDay(),
            table.routePlans(),
            trips,
            rewritten,
            table.notes(),
            table.createdAt(),
            table.updatedAt());

    return out;
  }

  /**
   * 一条交路改写之后的样子。
   *
   * @param duty 改写后的交路
   * @param trips 它保留下来的班次，时刻已按 {@code actual} 改过
   */
  record RewrittenDuty(VehicleDuty duty, List<TimetableTrip> trips) {}

  /**
   * 改写一条交路。{@link #rewrite} 与让车修复的增量重扫共用这一段：修复每施加一处只改一条交路， 为了重投影它而把整张表（九百多个班次、要排序）重建一遍是纯浪费。
   *
   * @return {@code null} 表示这条交路整条不落表：全被截掉，或者退到头也找不到回库线路
   */
  static RewrittenDuty rewriteDuty(
      Timetable table,
      List<TimetableTrip> chain,
      int d,
      VehicleDuty duty,
      int[] kept,
      Map<UUID, Integer> actual,
      int[] startDelay,
      int[] returnDelay,
      int zero,
      TurnaroundTable turnarounds,
      Set<UUID> routesEndingAtDepot,
      VehicleDutyPlanner.Legs legs) {
    if (chain.isEmpty()) {
      return new RewrittenDuty(duty, List.of());
    }
    if (kept[d] <= 0) {
      return null; // 整条交路都被截掉：它没有一班能跑，不落表
    }
    List<TimetableTrip> keptChain = chain.subList(0, kept[d]);
    TimetableTrip first = keptChain.get(0);
    TimetableTrip last = keptChain.get(keptChain.size() - 1);
    int firstDelta =
        actual.getOrDefault(first.id(), first.departureSecondOfDay() - zero)
            - (first.departureSecondOfDay() - zero);
    List<UUID> ids = new ArrayList<>(keptChain.size());
    List<TimetableTrip> rows = new ArrayList<>(keptChain.size());
    for (TimetableTrip trip : keptChain) {
      ids.add(trip.id());
      rows.add(retimed(trip, actual, zero));
    }
    TimetableRoutePlan lastPlan = table.routePlan(last.routeId()).orElse(null);
    int lastArrival =
        actual.getOrDefault(last.id(), last.departureSecondOfDay() - zero)
            + (lastPlan == null ? 0 : lastPlan.totalRunSeconds());
    boolean truncatedDuty = kept[d] < chain.size();
    String endDepot = duty.endDepotNodeId();
    Optional<UUID> returnRouteId = duty.returnRouteId();
    int returnAt;
    int end;
    if (!truncatedDuty) {
      int oldLastArrival =
          (last.departureSecondOfDay() - zero)
              + (lastPlan == null ? 0 : lastPlan.totalRunSeconds());
      int delta = lastArrival - oldLastArrival + returnDelayOf(returnDelay, d);
      returnAt = duty.returnSecondOfDay() - zero + delta;
      end = duty.plannedEndSecondOfDay() - zero + delta;
    } else if (lastPlan != null && routesEndingAtDepot.contains(last.routeId())) {
      endDepot = lastPlan.terminalNodeId();
      returnRouteId = Optional.empty();
      returnAt = lastArrival + returnDelayOf(returnDelay, d);
      end = returnAt;
    } else {
      // 串行改了时刻不改归属：回库仍按派车器那条"回自己出库的库"的规则选，两边必须一致。
      VehicleDutyPlanner.Leg leg =
          lastPlan == null
              ? null
              : legs.returnLegAt(lastPlan.terminalNodeId(), duty.startDepotNodeId()).orElse(null);
      if (leg == null) {
        return null; // truncateFrom 已保证可回库；到这里是防御
      }
      endDepot = leg.depotNodeId();
      returnRouteId = Optional.of(leg.routeId());
      returnAt =
          lastArrival + turnarounds.secondsFor(last.routeId()) + returnDelayOf(returnDelay, d);
      end = returnAt + leg.runSeconds();
    }
    return new RewrittenDuty(
        new VehicleDuty(
            duty.id(),
            duty.timetableId(),
            duty.sequence(),
            duty.dutyCode(),
            duty.startDepotNodeId(),
            endDepot,
            duty.createRouteId(),
            returnRouteId,
            ids,
            duty.plannedStartSecondOfDay() + firstDelta + returnDelayOf(startDelay, d),
            zero + returnAt,
            zero + end,
            truncatedDuty ? VehicleDuty.CloseReason.NO_COMPATIBLE_NEXT : duty.closeReason()),
        rows);
  }

  /** 班次按 {@code actual} 改时刻；没改过的原样返回。 */
  private static TimetableTrip retimed(TimetableTrip trip, Map<UUID, Integer> actual, int zero) {
    Integer dep = actual.get(trip.id());
    return dep == null
        ? trip
        : new TimetableTrip(
            trip.id(),
            trip.timetableId(),
            trip.routeId(),
            trip.sequence(),
            trip.tripCode(),
            zero + dep,
            trip.dutyId());
  }

  private static int returnDelayOf(int[] delays, int d) {
    return delays == null || d >= delays.length ? 0 : Math.max(0, delays[d]);
  }

  /** 每条 duty 的班次链，与 {@code table.duties()} 对齐；引用不到的 trip id 跳过。 */
  static List<List<TimetableTrip>> chainsOf(Timetable table) {
    Map<UUID, TimetableTrip> tripsById = new HashMap<>();
    for (TimetableTrip trip : table.trips()) {
      tripsById.put(trip.id(), trip);
    }
    List<List<TimetableTrip>> chains = new ArrayList<>(table.duties().size());
    for (VehicleDuty duty : table.duties()) {
      List<TimetableTrip> chain = new ArrayList<>();
      for (UUID id : duty.tripIds()) {
        TimetableTrip trip = tripsById.get(id);
        if (trip != null) {
          chain.add(trip);
        }
      }
      chains.add(chain);
    }
    return chains;
  }

  /** 把下一班入队；没有下一班时什么都不做。 */
  private static void advance(
      int d,
      List<TimetableTrip> chain,
      int[] next,
      int[] ready,
      Timetable table,
      Input input,
      PriorityQueue<Event> queue,
      int zero,
      Set<String> terminalSet,
      int nextReady) {
    if (next[d] + 1 >= chain.size()) {
      return;
    }
    next[d]++;
    ready[d] = nextReady;
    TimetableTrip trip = chain.get(next[d]);
    TimetableRoutePlan plan = table.routePlan(trip.routeId()).orElse(null);
    boolean originTerminal =
        plan != null && terminalSet.contains(TimetableConflictChecker.groupOf(plan.originNodeId()));
    int nominal = trip.departureSecondOfDay() - zero;
    int key = originTerminal ? nextReady : Math.max(nominal, nextReady);
    queue.add(new Event(key, trip.tripCode(), d));
  }

  /** 这一班之后离开端点的走行：续班的出站走行，或回库线路的出站走行。 */
  private static int outRunOf(
      List<TimetableTrip> chain,
      int i,
      boolean hasNext,
      VehicleDuty duty,
      Input input,
      String group) {
    if (hasNext) {
      TimetableConflictChecker.RouteProfile nextProfile =
          input.profiles().get(chain.get(i + 1).routeId());
      return nextProfile == null ? 0 : approachOut(nextProfile, group, input.index().sections());
    }
    return duty.returnRouteId()
        .map(input.profiles()::get)
        .map(profile -> approachOut(profile, group, input.index().sections()))
        .orElse(0);
  }

  /** 延后之后这一班还装不装得下：越过计划窗口，或连同收尾超过交路时长上限。 */
  static boolean exceedsLimits(
      int dep,
      TimetableRoutePlan plan,
      VehicleDuty duty,
      int horizonSeconds,
      int turnaroundSeconds,
      Set<UUID> routesEndingAtDepot,
      VehicleDutyPlanner.Legs legs,
      VehicleDutyPlanner.Limits limits,
      int zero) {
    if (dep >= horizonSeconds) {
      return true;
    }
    int tail;
    if (routesEndingAtDepot.contains(plan.routeId())) {
      tail = 0;
    } else {
      tail =
          legs.returnLegAt(plan.terminalNodeId())
              .map(leg -> turnaroundSeconds + leg.runSeconds())
              .orElse(turnaroundSeconds);
    }
    int dutyStart = duty.plannedStartSecondOfDay() - zero;
    return dep + plan.totalRunSeconds() + tail - dutyStart > limits.maxDutyDurationSeconds();
  }

  /** 从第 i 班起截断交路，再往前退到一个能回库的终点为止（与派车器窗口末尾的处理同一条规则）。 */
  static void truncateFrom(
      int i,
      List<TimetableTrip> chain,
      int d,
      int[] kept,
      Map<UUID, Integer> actual,
      Set<UUID> truncated,
      Set<UUID> routesEndingAtDepot,
      VehicleDutyPlanner.Legs legs,
      Timetable table) {
    int keep = i;
    while (keep > 0) {
      TimetableTrip last = chain.get(keep - 1);
      TimetableRoutePlan plan = table.routePlan(last.routeId()).orElse(null);
      boolean closable =
          plan != null
              && (routesEndingAtDepot.contains(last.routeId())
                  || legs.returnLegAt(plan.terminalNodeId()).isPresent());
      if (closable) {
        break;
      }
      keep--;
    }
    for (int k = keep; k < chain.size(); k++) {
      truncated.add(chain.get(k).id());
      actual.remove(chain.get(k).id());
    }
    kept[d] = keep;
  }

  /** 邻表预订：把 {@code earliest} 起的一次占用窗口滑到不与任何预订相交的位置；只往后滑。 */
  static int slideAfterBookings(List<int[]> bookings, int earliest, int in, int afterArrival) {
    if (bookings == null || bookings.isEmpty()) {
      return earliest;
    }
    int arrival = earliest;
    boolean moved = true;
    while (moved) {
      moved = false;
      int from = arrival - in;
      int to = arrival + afterArrival;
      for (int[] booking : bookings) {
        if (booking[0] < to && from < booking[1]) {
          arrival = booking[1] + in;
          moved = true;
          break;
        }
      }
    }
    return arrival;
  }

  /** 容量 1 且被某条 OPERATION route 用作起点或终点的站台组，按组键排序。 */
  static List<String> terminalGroups(
      TimetableConflictChecker.GraphIndex index,
      Collection<TimetableConflictChecker.RouteProfile> operationProfiles) {
    Set<String> out = new TreeSet<>();
    for (TimetableConflictChecker.RouteProfile profile : operationProfiles) {
      for (Optional<TimetableConflictChecker.Platform> end :
          List.of(profile.origin(), profile.terminal())) {
        if (end.isEmpty() || end.get().group().isBlank()) {
          continue;
        }
        Integer capacity = index.platformCapacity().get(end.get().group());
        if (capacity != null && capacity == 1) {
          out.add(end.get().group());
        }
      }
    }
    return List.copyOf(out);
  }

  /**
   * 进站走行：从进入端点所在的单线区段起，到终点到达为止——与冲突模型里对向互斥的那一段同一口径。
   *
   * <p>端点的岔线在单线区段索引里是一条桥链（CHT 是 {@code S:CHT:3~SWITCHER}），一辆车一进这条桥链就把整段占死，
   * 只算最后一个停靠点到终点会漏掉桥链上更靠外的边。终点前那条边不在任何单线区段时（双线进站），退回按站台组命名算。
   */
  static int approachIn(
      TimetableConflictChecker.RouteProfile profile,
      String group,
      SingleLineSectionIndex sections) {
    List<TimetableStop> stops = profile.stops();
    if (stops.isEmpty()) {
      return 0;
    }
    int terminalArrival = stops.get(stops.size() - 1).arrivalOffsetSeconds();
    Optional<String> section = sectionOfLastEdge(profile, sections);
    if (section.isPresent()) {
      int entry = terminalArrival;
      outer:
      for (int s = profile.segments().size() - 1; s >= 0; s--) {
        TimetableTimingCalculator.SegmentTiming segment = profile.segments().get(s);
        for (int k = segment.edges().size() - 1; k >= 0; k--) {
          if (!section.equals(sectionKey(sections, segment.edges().get(k)))) {
            break outer;
          }
          entry = segment.enterOffset(k);
        }
      }
      return terminalArrival - entry;
    }
    for (int i = stops.size() - 2; i >= 0; i--) {
      if (!TimetableConflictChecker.groupOf(stops.get(i).nodeId().orElse("")).equals(group)) {
        return terminalArrival - stops.get(i).departureOffsetSeconds();
      }
    }
    return terminalArrival;
  }

  /** 出站走行：从起点发车到离开端点所在单线区段为止；起点后那条边不在单线区段时退回按站台组命名算。 */
  static int approachOut(
      TimetableConflictChecker.RouteProfile profile,
      String group,
      SingleLineSectionIndex sections) {
    List<TimetableStop> stops = profile.stops();
    if (stops.isEmpty()) {
      return 0;
    }
    int departure = stops.get(0).departureOffsetSeconds();
    Optional<String> section = sectionOfFirstEdge(profile, sections);
    if (section.isPresent()) {
      int exit = departure;
      outer:
      for (TimetableTimingCalculator.SegmentTiming segment : profile.segments()) {
        for (int k = 0; k < segment.edges().size(); k++) {
          if (!section.equals(sectionKey(sections, segment.edges().get(k)))) {
            break outer;
          }
          exit = segment.exitOffset(k);
        }
      }
      return Math.max(0, exit - departure);
    }
    for (int i = 1; i < stops.size(); i++) {
      if (!TimetableConflictChecker.groupOf(stops.get(i).nodeId().orElse("")).equals(group)) {
        return stops.get(i).arrivalOffsetSeconds();
      }
    }
    return 0;
  }

  private static Optional<String> sectionOfLastEdge(
      TimetableConflictChecker.RouteProfile profile, SingleLineSectionIndex sections) {
    for (int s = profile.segments().size() - 1; s >= 0; s--) {
      List<RailEdge> edges = profile.segments().get(s).edges();
      if (!edges.isEmpty()) {
        return sectionKey(sections, edges.get(edges.size() - 1));
      }
    }
    return Optional.empty();
  }

  private static Optional<String> sectionOfFirstEdge(
      TimetableConflictChecker.RouteProfile profile, SingleLineSectionIndex sections) {
    for (TimetableTimingCalculator.SegmentTiming segment : profile.segments()) {
      if (!segment.edges().isEmpty()) {
        return sectionKey(sections, segment.edges().get(0));
      }
    }
    return Optional.empty();
  }

  private static Optional<String> sectionKey(SingleLineSectionIndex sections, RailEdge edge) {
    if (sections == null || edge == null) {
      return Optional.empty();
    }
    return sections.sectionInfoForEdge(edge.id()).map(SingleLineSectionInfo::key);
  }

  /** 邻表在该组的预订时段：stays 里 platform.group() 相符的区间，按起点排序。 */
  static List<int[]> neighborBookings(
      List<NeighborTimetable> neighbors, String group, int separation) {
    List<int[]> out = new ArrayList<>();
    for (NeighborTimetable neighbor : neighbors) {
      for (TimetableConflictChecker.Stay stay : neighbor.stays()) {
        if (group.equals(stay.platform().group())) {
          out.add(new int[] {stay.from() - separation, stay.to() + separation});
        }
      }
    }
    out.sort(Comparator.<int[]>comparingInt(b -> b[0]).thenComparingInt(b -> b[1]));
    return out;
  }

  private record Floor(
      int in, int out, int visitCost, int cycleTrips, double visitsPerCycle, int headwayFloor) {}

  /** 按权重满额时的下界：每次占用 × 一个 SWRR 周期内的经过次数 / 周期班次数。 */
  private static Map<String, Floor> floors(
      Input input,
      List<TimetableConflictChecker.RouteProfile> operationProfiles,
      List<String> groups) {
    Map<String, Floor> out = new LinkedHashMap<>();
    long totalWeight = 0L;
    for (WeightedTripAllocator.Candidate candidate : input.candidates()) {
      totalWeight += Math.max(0, candidate.weight());
    }
    for (String group : groups) {
      int in = 0;
      int outRun = 0;
      int turnaround = 0;
      double visits = 0.0D;
      for (TimetableConflictChecker.RouteProfile profile : operationProfiles) {
        boolean origin = profile.origin().map(p -> group.equals(p.group())).orElse(false);
        boolean terminal = profile.terminal().map(p -> group.equals(p.group())).orElse(false);
        if (terminal) {
          in = Math.max(in, approachIn(profile, group, input.index().sections()));
          // 折返与进出站走行同口径取最大值：下界要对这个端点上最慢的那条 route 也成立。
          turnaround =
              Math.max(turnaround, input.limits().turnaround().secondsFor(profile.routeId()));
        }
        if (origin) {
          outRun = Math.max(outRun, approachOut(profile, group, input.index().sections()));
        }
        int weight = weightOf(input, profile.routeId());
        visits += weight * ((origin ? 1 : 0) + (terminal ? 1 : 0)) / 2.0D;
      }
      int cost = in + turnaround + outRun + Math.max(0, input.separationSeconds());
      int floor = totalWeight <= 0L ? 0 : (int) Math.ceil(visits * cost / totalWeight);
      out.put(group, new Floor(in, outRun, cost, (int) totalWeight, visits, floor));
    }
    return out;
  }

  private static int weightOf(Input input, UUID routeId) {
    for (int i = 0; i < input.operationPlans().size() && i < input.candidates().size(); i++) {
      if (input.operationPlans().get(i).routeId().equals(routeId)) {
        return Math.max(0, input.candidates().get(i).weight());
      }
    }
    return 0;
  }

  private record Event(int key, String code, int duty) {}
}
