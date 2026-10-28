package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.scope.NeighborTimetable;

/**
 * 统一预订表：把派好车的名义表按时刻落到全部资源上，只延后、不提前（出库走行除外，见下）。
 *
 * <p>取代端点串行、车库咽喉串行与让车修复三套机制：它们成本模型各不相同、先后互相覆盖，修复还会把截断交路当成冲突减少。 这里只有一张表、一种占用口径（与 {@link
 * TimetableConflictChecker} 相同的足迹与站台停留），按名义发车先后逐班落位：
 *
 * <ul>
 *   <li>起点是 {@code max(名义, 本车就绪)}；撞上已登记的占用就整趟延后到让开为止，延后不超过单处让车上限（{@code --max-wait}）——
 *       超过的照原时刻登记，留给残余判定。延后沿交路传给下一班。名义时刻来自周期层求解，运营车之间在稳态里本来就不撞。
 *   <li>出入库走行都等运营班次落位之后再插空，运营班次不为它们让路，免得走行的等待沿交路传下去：回库走行在终点多停一会儿再走，
 *       只往后找、不超过让车上限；出库走行往前提（在首站多等，不超过闲置上限）。放不下的按原时刻登记，留给残余判定。
 *   <li>端点上同一辆车的停留从到站起先占住（还不知道下一班几点走），下一班发车或回库时截到那一刻。
 * </ul>
 *
 * <p>延后让班次越过计划窗口或交路时长上限时，从那一班起截断交路并退到能回库的终点（与派车器同一规则），截掉的班次进取消清单。 截断是结果，不是手段：它不会被当成"冲突减少"去换别的东西。
 * 退回去的那几班已经登记过：它们的占用、端点停留、偏移与让车一并撤掉，后面的车不再给不跑的车让路；新末班在终点的停留重新打开，交给回库走行去截。
 *
 * <p>邻表的运行与停留是不可移动的事实（路权先到先得），先登记。
 */
final class ReservationScheduler {

  /** 找空档时最多跳几次：每跳一次至少后移一秒，到上限还没找到就按原时刻登记。 */
  private static final int MAX_JUMPS = 400;

  /** 端点停留在下一班发车之前的暂定终点：足够远，相当于"一直占着"。 */
  private static final int OPEN = Integer.MAX_VALUE / 4;

  private ReservationScheduler() {}

  /**
   * 输入。
   *
   * @param provisional 派好车的名义表（相对秒 = 当日秒 − {@code zeroSecondOfDay}）
   * @param profiles 各 route 的投影（含 CREATE/RETURN）
   * @param index 图索引
   * @param zeroSecondOfDay 零点
   * @param horizonSeconds 计划窗口长度
   * @param separationSeconds 裕量
   * @param maxWaitSeconds 单处让车上限
   * @param toleranceSeconds 同一班累计延后上限
   * @param legs 出入库走行
   * @param limits 交路上限
   * @param routesEndingAtDepot 以车库收尾的运营 route
   * @param neighbors 邻表（不可移动）
   */
  record Input(
      Timetable provisional,
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
      TimetableConflictChecker.GraphIndex index,
      int zeroSecondOfDay,
      int horizonSeconds,
      int separationSeconds,
      int maxWaitSeconds,
      int toleranceSeconds,
      VehicleDutyPlanner.Legs legs,
      VehicleDutyPlanner.Limits limits,
      Set<UUID> routesEndingAtDepot,
      List<NeighborTimetable> neighbors,
      Set<UUID> pinned) {
    Input {
      pinned = pinned == null ? Set.of() : Set.copyOf(pinned);
      Objects.requireNonNull(provisional, "provisional");
      profiles = Map.copyOf(profiles);
      Objects.requireNonNull(index, "index");
      legs = legs == null ? VehicleDutyPlanner.Legs.none() : legs;
      limits = limits == null ? VehicleDutyPlanner.Limits.defaults() : limits;
      routesEndingAtDepot =
          routesEndingAtDepot == null ? Set.of() : Set.copyOf(routesEndingAtDepot);
      neighbors = neighbors == null ? List.of() : List.copyOf(neighbors);
    }
  }

  /**
   * 输出。
   *
   * @param timetable 时刻改写后的表
   * @param shifts 偏离名义时刻的班次
   * @param yields 写进表的让车
   * @param truncatedTripIds 截掉的班次
   */
  record Result(
      Timetable timetable,
      List<DutyRetiming.Shift> shifts,
      List<TripYield> yields,
      List<UUID> truncatedTripIds) {
    Result {
      Objects.requireNonNull(timetable, "timetable");
      shifts = List.copyOf(shifts);
      yields = List.copyOf(yields);
      truncatedTripIds = List.copyOf(truncatedTripIds);
    }
  }

  /** 出入库走行在预订表里的车次后缀（{@code 交路号-CREATE}），与冲突报告里的写法一致。 */
  private static final String CREATE = "-CREATE";

  private static final String RETURN = "-RETURN";

  /**
   * 排一次；走行插空之后若有出入库走行留下运行时也让不掉的冲突（要等的超过让车上限，或撞在单股道尽头的站台上），这几条交路的走行改为
   * 随班落位（出库连同首班一起找空档，回库紧跟末班），整张表再排一次。只重排一次，结果确定。
   */
  static Result schedule(Input input) {
    Run first = new Run(input, Set.of());
    Result result = first.run();
    if (first.needsInline.isEmpty()) {
      return result;
    }
    return new Run(input, Set.copyOf(first.needsInline)).run();
  }

  // ---------------------------------------------------------------- 一次排程

  private static final class Run {
    private final Input in;
    private final Timetable table;
    private final int zero;
    private final int sep;
    private final TimetableConflictChecker.Footprints footprints;
    private final Book book = new Book();
    private final List<List<TimetableTrip>> chains;
    private final int[] kept;
    private final int[] startDelay;
    private final List<PendingReturn> pendingReturns = new ArrayList<>();
    private final int[] returnDelay;
    private final Map<UUID, Integer> actual = new HashMap<>();
    private final Set<UUID> truncated = new HashSet<>();
    private final List<DutyRetiming.Shift> shifts = new ArrayList<>();
    private final List<TripYield> yields = new ArrayList<>();

    /** 走行随班落位的交路（第二遍排程用）。 */
    private final Set<Integer> inline;

    /** 插空时放不下、留下运行时也让不掉的冲突的交路：第二遍改为随班落位。 */
    private final Set<Integer> needsInline = new TreeSet<>();

    private final Set<String> stubGroups;

    /** 每条交路末班到站后在终点的停留（暂定一直占着，回库时截到回库出发）。 */
    private final Map<Integer, List<Occ>> openStays = new HashMap<>();

    /** 每班登记过的占用（运行、随班的出库走行、到站后的终点停留）：截断退回时按班撤掉。 */
    private final Map<UUID, List<Occ>> bookedByTrip = new HashMap<>();

    /** 每班到站后在终点的停留：截断退回到这一班时重新打开。 */
    private final Map<UUID, List<Occ>> staysByTrip = new HashMap<>();

    Run(Input input, Set<Integer> inline) {
      this.in = input;
      this.inline = inline;
      this.stubGroups = ConflictAbsorption.stubGroups(input.index());
      this.table = input.provisional();
      this.zero = input.zeroSecondOfDay();
      this.sep = Math.max(0, input.separationSeconds());
      this.footprints = new TimetableConflictChecker.Footprints(input.index());
      this.chains = DutyRetiming.chainsOf(table);
      this.kept = new int[chains.size()];
      for (int d = 0; d < chains.size(); d++) {
        kept[d] = chains.get(d).size();
      }
      this.startDelay = new int[chains.size()];
      this.returnDelay = new int[chains.size()];
    }

    Result run() {
      bookNeighbors();
      placeTrips();
      placeReturns();
      placeCreates();
      Timetable out =
          DutyRetiming.rewrite(
              table,
              chains,
              kept,
              actual,
              truncated,
              startDelay,
              returnDelay,
              zero,
              in.limits().turnaround(),
              in.routesEndingAtDepot(),
              in.legs());
      shifts.sort(
          Comparator.comparingInt(DutyRetiming.Shift::actualSeconds)
              .thenComparing(shift -> shift.tripId().toString()));
      return new Result(out, shifts, yields, new ArrayList<>(truncated));
    }

    /** 这一格之后要留的余量：闭塞格不留，其余留 separation（{@link TimetableConflictChecker#marginOf}）。 */
    private int margin(TimetableConflictChecker.Slot cell) {
      return TimetableConflictChecker.marginOf(cell.key(), sep);
    }

    // ---------------------------------------------------------------- 邻表

    private void bookNeighbors() {
      int vehicle = -1;
      for (NeighborTimetable neighbor : in.neighbors()) {
        for (TimetableConflictChecker.Movement movement : neighbor.movements()) {
          TimetableConflictChecker.RouteProfile profile =
              neighbor.profiles().get(movement.routeId());
          if (profile == null) {
            continue;
          }
          for (TimetableConflictChecker.Slot cell :
              footprints.of(movement.routeId(), profile).slots()) {
            book.add(
                cell,
                new Occ(
                    movement.startSeconds() + cell.from(),
                    movement.startSeconds() + cell.to() + margin(cell),
                    cell.direction(),
                    vehicle,
                    movement.code(),
                    movement.owner()));
          }
          vehicle--;
        }
        for (TimetableConflictChecker.Stay stay : neighbor.stays()) {
          for (TimetableConflictChecker.Slot cell :
              TimetableConflictChecker.staySlots(
                  stay.platform(), stay.from(), stay.to(), footprints)) {
            book.add(
                cell,
                new Occ(
                    cell.from(),
                    cell.to() + margin(cell),
                    cell.direction(),
                    vehicle,
                    stay.code(),
                    stay.owner()));
          }
          vehicle--;
        }
      }
    }

    // ---------------------------------------------------------------- 按时刻逐班落位

    /**
     * 按名义发车先后逐班落位。出入库走行等运营班次都落位之后再插空（{@link #placeReturns}、{@link #placeCreates}）：运营班次不为它们让路，
     * 免得一处走行的等待沿交路一路传下去、把规整的班距推乱；走行放不下的留给残余判定。
     */
    private void placeTrips() {
      List<int[]> order = new ArrayList<>();
      for (int d = 0; d < chains.size(); d++) {
        for (int i = 0; i < chains.get(d).size(); i++) {
          order.add(new int[] {nominalOf(chains.get(d).get(i)), d, i});
        }
      }
      order.sort(
          Comparator.<int[]>comparingInt(entry -> entry[0])
              .thenComparing(entry -> chains.get(entry[1]).get(entry[2]).tripCode()));
      int[] ready = new int[chains.size()];
      Arrays.fill(ready, Integer.MIN_VALUE);
      // 定点班次先占位、一秒不动：别的车按时刻落位时把它当成已在场的车让开。
      Set<UUID> prebooked = new HashSet<>();
      for (int d = 0; d < chains.size(); d++) {
        for (TimetableTrip trip : chains.get(d)) {
          TimetableConflictChecker.RouteProfile profile = in.profiles().get(trip.routeId());
          if (!in.pinned().contains(trip.id()) || profile == null) {
            continue;
          }
          int departure = nominalOf(trip);
          for (TimetableConflictChecker.Slot cell :
              footprints.of(trip.routeId(), profile).slots()) {
            record(
                trip,
                cell,
                new Occ(
                    departure + cell.from(),
                    departure + cell.to() + margin(cell),
                    cell.direction(),
                    d,
                    trip.tripCode(),
                    Optional.empty()));
          }
          prebooked.add(trip.id());
        }
      }
      for (int[] entry : order) {
        int d = entry[1];
        int i = entry[2];
        if (i >= kept[d]) {
          continue;
        }
        TimetableTrip trip = chains.get(d).get(i);
        TimetableConflictChecker.RouteProfile profile = in.profiles().get(trip.routeId());
        TimetableRoutePlan plan = table.routePlan(trip.routeId()).orElse(null);
        int nominal = nominalOf(trip);
        int base = Math.max(nominal, ready[d]);
        if (profile == null || plan == null) {
          actual.put(trip.id(), base);
          continue;
        }
        List<TimetableConflictChecker.Slot> cells = footprints.of(trip.routeId(), profile).slots();
        if (prebooked.contains(trip.id())) {
          // 定点班次：车没就绪也按点发（就绪晚了是派车的残余，交给冲突判定），交路照常往下接。
          actual.put(trip.id(), nominal);
          closeStays(d, nominal);
          openTerminalStays(d, trip, nominal + lastArrivalOffset(profile), profile);
          ready[d] =
              nominal
                  + lastArrivalOffset(profile)
                  + in.limits().turnaround().secondsFor(trip.routeId());
          if (i == chains.get(d).size() - 1) {
            finishDuty(d);
          }
          continue;
        }
        Placement placement = earliest(cells, base, d, trip.tripCode(), in.maxWaitSeconds());
        List<TimetableConflictChecker.Slot> booked = cells;
        if (i == 0 && inline.contains(d)) {
          // 插空时这条交路的出库走行让不掉：先让出库走行往前提（在首站多等，不超过闲置上限），首班不动；提前也放不下，出库车在车库里
          // 等，首班连同出库一起往后找——这段等待只受同一班累计延后上限约束。
          Optional<List<TimetableConflictChecker.Slot>> create = createCells(d, trip, profile, 0);
          if (create.isPresent()) {
            int earlier = earlierCreate(d, trip, profile, placement.time);
            if (earlier >= 0) {
              booked = concat(cells, createCells(d, trip, profile, earlier).orElseThrow());
              startDelay[d] = -earlier;
            } else {
              booked = concat(cells, create.get());
              placement = earliest(booked, base, d, trip.tripCode(), in.toleranceSeconds());
            }
          }
        }
        int departure = placement.time;
        if (departure - nominal > in.toleranceSeconds()
            || DutyRetiming.exceedsLimits(
                departure,
                plan,
                table.duties().get(d),
                in.horizonSeconds(),
                in.limits().turnaround().secondsFor(trip.routeId()),
                in.routesEndingAtDepot(),
                in.legs(),
                in.limits(),
                zero)) {
          // 截断：从这一班起不排，退到能回库的终点；那一站的停留交给回库走行去截。
          DutyRetiming.truncateFrom(
              i,
              chains.get(d),
              d,
              kept,
              actual,
              truncated,
              in.routesEndingAtDepot(),
              in.legs(),
              table,
              table.duties().get(d).consist());
          retract(d);
          finishDuty(d);
          continue;
        }
        actual.put(trip.id(), departure);
        if (departure != nominal) {
          shifts.add(
              new DutyRetiming.Shift(
                  trip.id(),
                  nominal,
                  departure,
                  placement.yielded
                      ? DutyRetiming.Shift.Reason.YIELDED
                      : DutyRetiming.Shift.Reason.VEHICLE_READY));
        }
        // 这辆车在起点的停留到发车为止；上一班到站时已经先占住了。
        closeStays(d, departure);
        for (TimetableConflictChecker.Slot cell : booked) {
          record(
              trip,
              cell,
              new Occ(
                  departure + cell.from(),
                  departure + cell.to() + margin(cell),
                  cell.direction(),
                  d,
                  trip.tripCode(),
                  Optional.empty()));
        }
        int arrival = departure + lastArrivalOffset(profile);
        ready[d] = arrival + in.limits().turnaround().secondsFor(trip.routeId());
        openTerminalStays(d, trip, arrival, profile);
        if (i == chains.get(d).size() - 1) {
          finishDuty(d);
        }
      }
    }

    /** 登记一班的一格占用，并记在这一班名下（截断退回时撤掉）。 */
    private void record(TimetableTrip trip, TimetableConflictChecker.Slot cell, Occ occ) {
      book.add(cell, occ);
      bookedByTrip.computeIfAbsent(trip.id(), id -> new ArrayList<>()).add(occ);
    }

    /**
     * 截断退回：交路 {@code d} 截到第 {@code kept[d]} 班为止。退掉的班次里已登记的占用、端点停留、偏移与它们自己的让车都撤掉
     * （还没轮到的定点班次也先占过位，一并撤掉）；新末班在终点的停留重新打开（暂定一直占着），由收尾与回库走行去截。
     */
    private void retract(int d) {
      List<TimetableTrip> chain = chains.get(d);
      Set<UUID> gone = new HashSet<>();
      Set<String> goneCodes = new HashSet<>();
      for (int j = kept[d]; j < chain.size(); j++) {
        TimetableTrip trip = chain.get(j);
        gone.add(trip.id());
        goneCodes.add(trip.tripCode());
        for (Occ occ : bookedByTrip.getOrDefault(trip.id(), List.of())) {
          book.remove(occ);
        }
        bookedByTrip.remove(trip.id());
        staysByTrip.remove(trip.id());
      }
      if (gone.isEmpty()) {
        return;
      }
      shifts.removeIf(shift -> gone.contains(shift.tripId()));
      yields.removeIf(yield -> goneCodes.contains(yield.second()));
      openStays.remove(d);
      if (kept[d] == 0) {
        startDelay[d] = 0;
        return;
      }
      List<Occ> stays = staysByTrip.get(chain.get(kept[d] - 1).id());
      if (stays != null && !stays.isEmpty()) {
        for (Occ occ : stays) {
          book.resize(occ, OPEN);
        }
        openStays.put(d, stays);
      }
    }

    /** 这辆车到终点后先占住站台，下一班发车（或回库）时再截短；下一班是已占位的定点班次时当场截到它的发车。 */
    private void openTerminalStays(
        int d, TimetableTrip trip, int arrival, TimetableConflictChecker.RouteProfile profile) {
      profile
          .terminal()
          .ifPresent(
              platform -> {
                List<Occ> stays = new ArrayList<>();
                for (TimetableConflictChecker.Slot cell :
                    TimetableConflictChecker.staySlots(platform, arrival, arrival, footprints)) {
                  Occ occ =
                      new Occ(
                          arrival,
                          OPEN,
                          cell.direction(),
                          d,
                          table.duties().get(d).dutyCode(),
                          Optional.empty());
                  record(trip, cell, occ);
                  stays.add(occ);
                }
                openStays.put(d, stays);
                staysByTrip.put(trip.id(), stays);
              });
    }

    /** 交路首班的出库走行与首站等候（按派车定下的提前量再提前 {@code earlier} 秒，相对首班发车）；没有出库走行时为空。 */
    private Optional<List<TimetableConflictChecker.Slot>> createCells(
        int d,
        TimetableTrip first,
        TimetableConflictChecker.RouteProfile firstProfile,
        int earlier) {
      VehicleDuty duty = table.duties().get(d);
      if (duty.createRouteId().isEmpty()) {
        return Optional.empty();
      }
      TimetableConflictChecker.RouteProfile create = in.profiles().get(duty.createRouteId().get());
      if (create == null) {
        return Optional.empty();
      }
      int lead = nominalOf(first) - (duty.plannedStartSecondOfDay() - zero) + earlier;
      int run = lastArrivalOffset(create);
      List<TimetableConflictChecker.Slot> out = new ArrayList<>();
      for (TimetableConflictChecker.Slot cell :
          footprints.of(duty.createRouteId().get(), create).slots()) {
        out.add(shifted(cell, -lead));
      }
      int waitFrom = Math.min(0, run - lead);
      firstProfile
          .origin()
          .ifPresent(
              platform ->
                  out.addAll(
                      TimetableConflictChecker.staySlots(platform, waitFrom, 0, footprints)));
      return Optional.of(out);
    }

    /**
     * 出库走行：运营班次与回库走行都落位之后再插空。出库车在首站多等不超过闲置上限（那是车在等，不是乘客在等），所以从按时到达起 往前找最早放得下的出发；放不下就按原时刻登记，留给残余判定。
     */
    private void placeCreates() {
      int limit = Math.max(0, in.limits().maxIdleSeconds());
      for (int d = 0; d < chains.size(); d++) {
        if (kept[d] <= 0 || inline.contains(d) || !actual.containsKey(chains.get(d).get(0).id())) {
          continue;
        }
        TimetableTrip first = chains.get(d).get(0);
        TimetableConflictChecker.RouteProfile firstProfile = in.profiles().get(first.routeId());
        if (firstProfile == null) {
          continue;
        }
        int departure = actual.get(first.id());
        int chosen = -1;
        for (int earlier = 0; earlier <= limit; earlier++) {
          Optional<List<TimetableConflictChecker.Slot>> cells =
              createCells(d, first, firstProfile, earlier);
          if (cells.isEmpty() || firstBlocked(cells.get(), departure, d) == null) {
            chosen = earlier;
            break;
          }
        }
        Optional<List<TimetableConflictChecker.Slot>> cells =
            createCells(d, first, firstProfile, Math.max(0, chosen));
        if (cells.isEmpty()) {
          continue;
        }
        if (chosen < 0) {
          markUnabsorbable(cells.get(), departure, d);
        }
        chosen = Math.max(0, chosen);
        String code = table.duties().get(d).dutyCode() + CREATE;
        for (TimetableConflictChecker.Slot cell : cells.get()) {
          book.add(
              cell,
              new Occ(
                  departure + cell.from(),
                  departure + cell.to() + margin(cell),
                  cell.direction(),
                  d,
                  code,
                  Optional.empty()));
        }
        startDelay[d] = -chosen;
      }
    }

    /** 首班发在 {@code departure} 时，出库走行最少要提前几秒才放得下（走行与首站等候都不撞）；不超过闲置上限，放不下为 −1。 */
    private int earlierCreate(
        int d,
        TimetableTrip first,
        TimetableConflictChecker.RouteProfile firstProfile,
        int departure) {
      int limit = Math.max(0, in.limits().maxIdleSeconds());
      for (int earlier = 0; earlier <= limit; earlier++) {
        Optional<List<TimetableConflictChecker.Slot>> cells =
            createCells(d, first, firstProfile, earlier);
        if (cells.isEmpty() || firstBlocked(cells.get(), departure, d) == null) {
          return earlier;
        }
      }
      return -1;
    }

    private static List<TimetableConflictChecker.Slot> concat(
        List<TimetableConflictChecker.Slot> a, List<TimetableConflictChecker.Slot> b) {
      List<TimetableConflictChecker.Slot> out = new ArrayList<>(a.size() + b.size());
      out.addAll(a);
      out.addAll(b);
      return out;
    }

    private static TimetableConflictChecker.Slot shifted(
        TimetableConflictChecker.Slot cell, int by) {
      return new TimetableConflictChecker.Slot(
          cell.key(),
          cell.kind(),
          cell.capacity(),
          cell.from() + by,
          cell.to() + by,
          cell.direction(),
          cell.eligibility());
    }

    /** 交路收尾：末班（或截断后的新末班）已落位，终点停留截到到站 + 折返；有回库走行的记下来，留到运营班次都落位之后再排。 */
    private void finishDuty(int d) {
      if (kept[d] <= 0) {
        closeStays(d, Integer.MIN_VALUE / 4);
        return;
      }
      VehicleDuty duty = table.duties().get(d);
      List<TimetableTrip> chain = chains.get(d);
      TimetableTrip last = chain.get(kept[d] - 1);
      Optional<UUID> ret =
          kept[d] < chain.size()
              ? table
                  .routePlan(last.routeId())
                  .flatMap(
                      plan ->
                          in.legs()
                              .returnLegAt(
                                  plan.terminalNodeId(), duty.startDepotNodeId(), duty.consist()))
                  .map(VehicleDutyPlanner.Leg::routeId)
              : duty.returnRouteId();
      // 回库走行留到运营班次都落位之后再排（见 placeReturns）；终点停留先截到最早能回库的时刻，别挡住后面的班次。
      closeStays(d, endOfStayWithoutReturn(d, last));
      if (in.routesEndingAtDepot().contains(last.routeId())
          || ret.isEmpty()
          || !in.profiles().containsKey(ret.get())) {
        return;
      }
      if (inline.contains(d)) {
        placeReturn(d, last, ret.get());
      } else {
        pendingReturns.add(new PendingReturn(d, last, ret.get()));
      }
    }

    /** 回库走行：运营班次都落位之后，按交路收尾先后插空。运营班次不为回库让路，回库在终点多停一会儿再走，放不下按原时刻登记。 */
    private void placeReturns() {
      for (PendingReturn pending : pendingReturns) {
        placeReturn(pending.duty(), pending.last(), pending.route());
      }
    }

    /** 截短这辆车在端点的停留：到 {@code until}（加裕量）为止。 */
    private void closeStays(int d, int until) {
      List<Occ> stays = openStays.remove(d);
      if (stays == null) {
        return;
      }
      for (Occ occ : stays) {
        book.resize(occ, Math.max(occ.start, until) + sep);
      }
    }

    /** 回库走行：在终点多停一会儿再走，只往后找；找不到按原时刻登记。 */
    private void placeReturn(int d, TimetableTrip last, UUID returnRoute) {
      TimetableConflictChecker.RouteProfile lastProfile = in.profiles().get(last.routeId());
      int departure = actual.getOrDefault(last.id(), nominalOf(last));
      int arrival = departure + (lastProfile == null ? 0 : lastArrivalOffset(lastProfile));
      int nominal = arrival + in.limits().turnaround().secondsFor(last.routeId());
      TimetableConflictChecker.RouteProfile profile = in.profiles().get(returnRoute);
      List<TimetableConflictChecker.Slot> cells = footprints.of(returnRoute, profile).slots();
      // 先把暂定一直占着的停留截到最早回库，再找；找到更晚的就把停留延到那里。先找走行与多停的那一段站台都空着的时刻，
      // 免得在终点上给自己造出一处站台冲突；找不到再只看走行——站台上的车多停一会儿，后车在站外等得了，走行撞上运营车往往让不掉。
      closeStays(d, nominal);
      Optional<TimetableConflictChecker.Platform> terminal =
          lastProfile == null ? Optional.empty() : lastProfile.terminal();
      int chosen = nominal;
      boolean found = false;
      for (int later = 0; later <= in.maxWaitSeconds(); later++) {
        if (later > 0
            && terminal.isPresent()
            && stayBlocked(terminal.get(), nominal, nominal + later, d)) {
          break;
        }
        if (free(cells, nominal + later, d)) {
          chosen = nominal + later;
          found = true;
          break;
        }
      }
      for (int later = 0; !found && later <= in.maxWaitSeconds(); later++) {
        if (free(cells, nominal + later, d)) {
          chosen = nominal + later;
          found = true;
        }
      }
      if (!found && !inline.contains(d)) {
        markUnabsorbable(cells, nominal, d);
      }
      if (chosen > nominal && lastProfile != null) {
        int until = chosen;
        lastProfile
            .terminal()
            .ifPresent(
                platform ->
                    bookStay(platform, nominal, until, d, table.duties().get(d).dutyCode()));
      }
      String code = table.duties().get(d).dutyCode() + RETURN;
      for (TimetableConflictChecker.Slot cell : cells) {
        book.add(
            cell,
            new Occ(
                chosen + cell.from(),
                chosen + cell.to() + margin(cell),
                cell.direction(),
                d,
                code,
                Optional.empty()));
      }
      returnDelay[d] = chosen - nominal;
    }

    /** 交路 {@code d} 的车在 {@code platform} 上从 {@code from} 停到 {@code to} 会不会撞上别的车。 */
    private boolean stayBlocked(
        TimetableConflictChecker.Platform platform, int from, int to, int d) {
      for (TimetableConflictChecker.Slot cell :
          TimetableConflictChecker.staySlots(platform, from, to, footprints)) {
        if (book.blocking(cell, from, to + sep, cell.direction(), d) != null) {
          return true;
        }
      }
      return false;
    }

    /** 没有回库走行的收尾（以车库收尾的运营班次，或回不了库）：停留截到到站 + 折返。 */
    private int endOfStayWithoutReturn(int d, TimetableTrip last) {
      TimetableConflictChecker.RouteProfile profile = in.profiles().get(last.routeId());
      int departure = actual.getOrDefault(last.id(), nominalOf(last));
      int arrival = departure + (profile == null ? 0 : lastArrivalOffset(profile));
      return in.routesEndingAtDepot().contains(last.routeId())
          ? arrival
          : arrival + in.limits().turnaround().secondsFor(last.routeId());
    }

    private void bookStay(
        TimetableConflictChecker.Platform platform, int from, int to, int d, String code) {
      for (TimetableConflictChecker.Slot cell :
          TimetableConflictChecker.staySlots(platform, from, Math.max(from, to), footprints)) {
        book.add(
            cell,
            new Occ(from, Math.max(from, to) + sep, cell.direction(), d, code, Optional.empty()));
      }
    }

    // ---------------------------------------------------------------- 找空档

    /** 一段运行最早能从哪一秒发：从 {@code base} 起，撞上谁就跳到它让开之后；延后超过 {@code limit} 按原时刻。 */
    private Placement earliest(
        List<TimetableConflictChecker.Slot> cells, int base, int vehicle, String code, int limit) {
      int t = base;
      Occ firstBlocker = null;
      TimetableConflictChecker.Slot firstCell = null;
      for (int jump = 0; jump < MAX_JUMPS; jump++) {
        Blocked blocked = firstBlocked(cells, t, vehicle);
        if (blocked == null) {
          if (t > base && firstBlocker != null) {
            yields.add(
                new TripYield(
                    firstCell.kind(),
                    firstCell.key(),
                    firstBlocker.code,
                    firstBlocker.owner,
                    code,
                    t - base,
                    base + firstCell.from()));
          }
          return new Placement(t, t > base);
        }
        if (firstBlocker == null) {
          firstBlocker = blocked.occ;
          firstCell = blocked.cell;
        }
        int next = t + Math.max(1, blocked.release - (t + blocked.cell.from()));
        if (next - base > limit) {
          break;
        }
        t = next;
      }
      return new Placement(base, false);
    }

    /**
     * 交路 {@code vehicle} 的一段走行按 {@code start} 登记时，运行时让不让得掉：与终判（{@link
     * ConflictAbsorption}）同两条——撞上的那一格要等的
     * 不超过让车上限，且不在单股道尽头的站台上（那里没有地方等）。让不掉时这条交路连同挡住它的走行所在的交路都记下，第二遍随班落位。
     */
    private void markUnabsorbable(
        List<TimetableConflictChecker.Slot> cells, int start, int vehicle) {
      for (TimetableConflictChecker.Slot cell : cells) {
        Occ blocker =
            book.blocking(
                cell,
                start + cell.from(),
                start + cell.to() + margin(cell),
                cell.direction(),
                vehicle);
        if (blocker == null) {
          continue;
        }
        if (ConflictAbsorption.onStubTerminal(cell.key(), stubGroups)
            || blocker.end - (start + cell.from()) > in.maxWaitSeconds()) {
          needsInline.add(vehicle);
          if (blocker.vehicle >= 0
              && (blocker.code.endsWith(CREATE) || blocker.code.endsWith(RETURN))) {
            needsInline.add(blocker.vehicle);
          }
        }
      }
    }

    private boolean free(List<TimetableConflictChecker.Slot> cells, int start, int vehicle) {
      return firstBlocked(cells, start, vehicle) == null;
    }

    private Blocked firstBlocked(
        List<TimetableConflictChecker.Slot> cells, int start, int vehicle) {
      for (TimetableConflictChecker.Slot cell : cells) {
        Occ blocker =
            book.blocking(
                cell,
                start + cell.from(),
                start + cell.to() + margin(cell),
                cell.direction(),
                vehicle);
        if (blocker != null) {
          return new Blocked(cell, blocker, blocker.end);
        }
      }
      return null;
    }

    private int nominalOf(TimetableTrip trip) {
      return trip.departureSecondOfDay() - zero;
    }
  }

  private static int lastArrivalOffset(TimetableConflictChecker.RouteProfile profile) {
    List<TimetableStop> stops = profile.stops();
    return stops.isEmpty() ? 0 : stops.get(stops.size() - 1).arrivalOffsetSeconds();
  }

  private record Placement(int time, boolean yielded) {}

  private record Blocked(TimetableConflictChecker.Slot cell, Occ occ, int release) {}

  /** 一次占用；端点停留的终点会被截短，所以可变。 */
  /** 等运营班次落位之后再排的回库走行。 */
  private record PendingReturn(int duty, TimetableTrip last, UUID route) {}

  private static final class Occ {
    final int start;
    int end;
    final int dir;
    final int vehicle;
    final String code;
    final Optional<String> owner;

    Occ(int start, int end, int dir, int vehicle, String code, Optional<String> owner) {
      this.start = start;
      this.end = Math.max(start, end);
      this.dir = dir;
      this.vehicle = vehicle;
      this.code = code;
      this.owner = owner;
    }
  }

  /** 全部资源的预订：每个资源按起点排的占用，查询时只看可能相交的那一段。 */
  private static final class Book {
    private final Map<String, Res> byKey = new HashMap<>();
    private final Map<Occ, Res> resOf = new HashMap<>();

    void add(TimetableConflictChecker.Slot cell, Occ occ) {
      Res res = byKey.computeIfAbsent(cell.key(), key -> new Res(cell));
      res.add(occ);
      resOf.put(occ, res);
    }

    void remove(Occ occ) {
      Res res = resOf.remove(occ);
      if (res != null) {
        res.remove(occ);
      }
    }

    void resize(Occ occ, int end) {
      Res res = resOf.get(occ);
      res.remove(occ);
      occ.end = Math.max(occ.start, end);
      res.add(occ);
    }

    /** 在 {@code [from, to)} 内挡住这次占用的那一个（让开得最晚的）；放得下为空。同一辆车不挡自己。 */
    Occ blocking(TimetableConflictChecker.Slot cell, int from, int to, int dir, int vehicle) {
      Res res = byKey.get(cell.key());
      return res == null ? null : res.blocking(from, to, dir, vehicle);
    }
  }

  /** 一个资源上的占用。 */
  private static final class Res {
    final int capacity;
    final boolean singleLine;
    final NavigableMap<Integer, List<Occ>> byStart = new TreeMap<>();
    int longest;

    Res(TimetableConflictChecker.Slot cell) {
      this.capacity = Math.max(1, cell.capacity());
      this.singleLine = cell.kind() == TimetableConflictChecker.Kind.SINGLE_LINE;
    }

    void add(Occ occ) {
      byStart.computeIfAbsent(occ.start, key -> new ArrayList<>()).add(occ);
      longest = Math.max(longest, occ.end - occ.start);
    }

    void remove(Occ occ) {
      List<Occ> list = byStart.get(occ.start);
      if (list != null) {
        list.remove(occ);
        if (list.isEmpty()) {
          byStart.remove(occ.start);
        }
      }
    }

    Occ blocking(int from, int to, int dir, int vehicle) {
      List<Occ> overlapping = new ArrayList<>();
      for (List<Occ> list : byStart.subMap(from - longest, true, to, false).values()) {
        for (Occ occ : list) {
          if (occ.vehicle != vehicle && occ.start < to && from < occ.end) {
            overlapping.add(occ);
          }
        }
      }
      if (overlapping.isEmpty()) {
        return null;
      }
      if (singleLine) {
        Occ worst = null;
        for (Occ occ : overlapping) {
          boolean opposed = dir == 0 || occ.dir == 0 || occ.dir != dir;
          if (opposed && (worst == null || occ.end > worst.end)) {
            worst = occ;
          }
        }
        return worst;
      }
      if (overlapping.size() < capacity) {
        // 容量大于 1：同一时刻在场的不超过容量就放得下。按起止扫一遍求最大并发。
        return null;
      }
      if (capacity > 1 && maxConcurrent(overlapping, from, to) < capacity) {
        return null;
      }
      Occ worst = overlapping.get(0);
      for (Occ occ : overlapping) {
        if (capacity == 1 ? occ.end > worst.end : occ.end < worst.end) {
          worst = occ;
        }
      }
      return worst;
    }

    private static int maxConcurrent(List<Occ> occs, int from, int to) {
      List<int[]> events = new ArrayList<>();
      for (Occ occ : occs) {
        events.add(new int[] {Math.max(from, occ.start), 1});
        events.add(new int[] {Math.min(to, occ.end), -1});
      }
      events.sort(Comparator.<int[]>comparingInt(e -> e[0]).thenComparingInt(e -> e[1]));
      int now = 0;
      int best = 0;
      for (int[] event : events) {
        now += event[1];
        best = Math.max(best, now);
      }
      return best;
    }
  }
}
