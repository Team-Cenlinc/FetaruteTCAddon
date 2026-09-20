package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.scope.NeighborTimetable;

/**
 * 让车写进表：按资源的"只延后"修复。
 *
 * <p>冲突模型报出来的每一处冲突都有一辆前车、一辆后车。运行时后车会在资源前等前车走完再进，这段等待今天不在表上——PIDS、车次绑定看到的是没让过的时刻，
 * 车一让完就漂。这里把它写进表：后车<b>整趟延后</b> {@code w = 前车离开 + 裕量 − 后车进入}（延后的是发车，车在起点多站），延后沿交路链传播
 * （本车下一班就绪晚了就跟着后移），再重扫，直到没有可修的冲突。单处延后超过 {@code maxWait} 的不动，留作<b>真冲突</b>交给搜索放宽； 同一班累计延后超过 {@code
 * tolerance}（运行时的 assign-tolerance）或交路超上限的，从那一班起截断交路（与端点串行同一条规则）。
 *
 * <p>邻表的占用是不可移动的前车：撞上邻表时无论谁先到，挪的都是我。{@link TerminalSerializer} 是本机制在容量 1 端点上的特例，先跑；这里接着处理它不管的
 * 区间、道岔、单线与多股道车站。
 *
 * <p>让车会在别处制造新冲突（连锁）。每施加一处就重扫一遍；冲突总数没有减少、或真冲突（超过上限、挪不动的）比施加前多了，这一处<b>回滚</b>并判为真冲突——
 * 修复只朝一个方向走：每一步都让冲突少一处，端点串行排好的东西不会被让车拆掉，循环必然终止。
 *
 * <p>确定性：冲突按检查器的稳定序取第一处可修的；延后量由时刻算出；不引入随机源、不依赖哈希遍历序。
 */
public final class ResourceRepair {

  private ResourceRepair() {}

  /**
   * 输入。时刻约定同 {@link TerminalSerializer.Input}：{@code zero + 相对秒}，不取模。
   *
   * @param provisional 端点串行之后的临时表
   * @param profiles 各 route 投影（含 CREATE/RETURN）
   * @param index 图索引
   * @param zeroSecondOfDay 零点
   * @param horizonSeconds 计划窗口长度
   * @param separationSeconds 裕量
   * @param maxWaitSeconds 单处让车上限；0 关闭修复（只扫一遍报冲突）
   * @param toleranceSeconds 同一班累计让车上限，超过即截断
   * @param legs 出库/回库走行段
   * @param limits 交路硬上限
   * @param routesEndingAtDepot 以销毁收尾的 route
   * @param neighbors 已投影到我零点的邻表
   */
  public record Input(
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
      List<NeighborTimetable> neighbors) {

    public Input {
      Objects.requireNonNull(provisional, "provisional");
      profiles = profiles == null ? Map.of() : Map.copyOf(profiles);
      index = index == null ? TimetableConflictChecker.GraphIndex.of(null) : index;
      legs = legs == null ? VehicleDutyPlanner.Legs.none() : legs;
      limits = limits == null ? VehicleDutyPlanner.Limits.defaults() : limits;
      routesEndingAtDepot =
          routesEndingAtDepot == null ? Set.of() : Set.copyOf(routesEndingAtDepot);
      neighbors = neighbors == null ? List.of() : List.copyOf(neighbors);
      maxWaitSeconds = Math.max(0, maxWaitSeconds);
      toleranceSeconds = Math.max(0, toleranceSeconds);
      separationSeconds = Math.max(0, separationSeconds);
    }
  }

  /**
   * 一处让车。
   *
   * @param kind 资源类型
   * @param resource 资源键
   * @param first 前车（不动的那个）的 code
   * @param firstOwner 前车属于哪份邻表；空 = 自己
   * @param second 后车（被延后的）的 code；班次是临时 code，走行是 {@code Dxxx-CREATE} / {@code Dxxx-RETURN}，待命是 duty
   *     号
   * @param waitSeconds 延后了多少秒
   * @param atSeconds 后车原本进入资源的时刻（相对秒）
   */
  public record Yield(
      TimetableConflictChecker.Kind kind,
      String resource,
      String first,
      Optional<String> firstOwner,
      String second,
      int waitSeconds,
      int atSeconds) {
    public Yield {
      firstOwner = firstOwner == null ? Optional.empty() : firstOwner;
    }

    /** 给邻表让的车。 */
    public boolean external() {
      return firstOwner.isPresent();
    }
  }

  /**
   * 输出。
   *
   * @param timetable 时刻已改写的表
   * @param shifts 因让车（含传播）偏离了串行后时刻的班次
   * @param yields 让车清单，按发生时刻
   * @param truncatedTripIds 累计让车超限而截断的班次
   * @param remaining 修完之后仍在的冲突——真冲突
   */
  public record Result(
      Timetable timetable,
      List<TerminalSerializer.Shift> shifts,
      List<Yield> yields,
      List<UUID> truncatedTripIds,
      TimetableConflictChecker.Report remaining) {
    public Result {
      Objects.requireNonNull(timetable, "timetable");
      shifts = shifts == null ? List.of() : List.copyOf(shifts);
      yields = yields == null ? List.of() : List.copyOf(yields);
      truncatedTripIds = truncatedTripIds == null ? List.of() : List.copyOf(truncatedTripIds);
      remaining = remaining == null ? TimetableConflictChecker.Report.none() : remaining;
    }
  }

  /** 修复主入口。{@code maxWaitSeconds == 0} 时只扫一遍冲突、原样返回表。 */
  public static Result repair(Input input) {
    Objects.requireNonNull(input, "input");
    Timetable table = input.provisional();
    int zero = input.zeroSecondOfDay();
    List<VehicleDuty> duties = table.duties();
    List<List<TimetableTrip>> chains = TerminalSerializer.chainsOf(table);
    int[] kept = new int[duties.size()];
    int[] startDelay = new int[duties.size()];
    int[] returnDelay = new int[duties.size()];
    Map<UUID, Integer> nominal = new HashMap<>();
    Map<UUID, Integer> actual = new HashMap<>();
    Map<UUID, int[]> position = new HashMap<>();
    Map<String, UUID> tripByCode = new HashMap<>();
    Map<String, Integer> dutyByCode = new HashMap<>();
    for (int d = 0; d < duties.size(); d++) {
      List<TimetableTrip> chain = chains.get(d);
      kept[d] = chain.size();
      dutyByCode.put(duties.get(d).dutyCode(), d);
      for (int i = 0; i < chain.size(); i++) {
        TimetableTrip trip = chain.get(i);
        int dep = trip.departureSecondOfDay() - zero;
        nominal.put(trip.id(), dep);
        actual.put(trip.id(), dep);
        position.put(trip.id(), new int[] {d, i});
        tripByCode.put(trip.tripCode(), trip.id());
      }
    }
    Map<UUID, TimetableConflictChecker.RouteProfile> allProfiles = new HashMap<>(input.profiles());
    for (NeighborTimetable neighbor : input.neighbors()) {
      neighbor.profiles().forEach(allProfiles::putIfAbsent);
    }
    Set<UUID> truncated = new TreeSet<>();
    Map<UUID, TerminalSerializer.Shift.Reason> reasons = new HashMap<>();
    List<Yield> yields = new ArrayList<>();
    Set<String> unrepairable = new HashSet<>();
    State state =
        new State(
            input,
            table,
            chains,
            kept,
            startDelay,
            returnDelay,
            nominal,
            actual,
            position,
            truncated,
            reasons,
            tripByCode,
            dutyByCode);

    int cap = 4 * table.trips().size() + 8;
    Timetable current = state.rewrite();
    TimetableConflictChecker.Report report = scan(input, allProfiles, current);
    for (int iteration = 0; input.maxWaitSeconds() > 0 && iteration < cap; iteration++) {
      Optional<Move> next = pickMove(input, report, unrepairable, state);
      if (next.isEmpty()) {
        break;
      }
      Move move = next.get();
      int realBefore = realCount(input, report, state);
      State.Snapshot snapshot = state.snapshot();
      if (!state.apply(move)) {
        state.restore(snapshot);
        unrepairable.add(move.key());
        continue;
      }
      Timetable candidate = state.rewrite();
      TimetableConflictChecker.Report after = scan(input, allProfiles, candidate);
      if (realCount(input, after, state) > realBefore
          || after.conflicts().size() >= report.conflicts().size()) {
        // 这一处让车没让表变好（别处多出了冲突，或把别处推成了真冲突）：回滚，它自己算真冲突。
        state.restore(snapshot);
        unrepairable.add(move.key());
        continue;
      }
      yields.add(
          new Yield(
              move.conflict().kind(),
              move.conflict().resource(),
              move.leader(),
              move.leaderOwner(),
              move.mover(),
              move.waitSeconds(),
              move.moverFrom()));
      current = candidate;
      report = after;
    }

    List<TerminalSerializer.Shift> shifts = new ArrayList<>();
    for (int d = 0; d < duties.size(); d++) {
      List<TimetableTrip> chain = chains.get(d);
      for (int i = 0; i < kept[d]; i++) {
        TimetableTrip trip = chain.get(i);
        int from = nominal.get(trip.id());
        int to = actual.getOrDefault(trip.id(), from);
        if (to != from) {
          shifts.add(
              new TerminalSerializer.Shift(
                  trip.id(),
                  from,
                  to,
                  reasons.getOrDefault(trip.id(), TerminalSerializer.Shift.Reason.VEHICLE_READY)));
        }
      }
    }
    shifts.sort(
        Comparator.comparingInt(TerminalSerializer.Shift::actualSeconds)
            .thenComparing(shift -> shift.tripId().toString()));
    yields.sort(Comparator.comparingInt(Yield::atSeconds).thenComparing(Yield::resource));
    return new Result(current, shifts, yields, new ArrayList<>(truncated), report);
  }

  /** 把当前表投影成运行 + 待命，连同邻表一起查一遍。与 builder 最后那一遍检查同一口径。 */
  private static TimetableConflictChecker.Report scan(
      Input input,
      Map<UUID, TimetableConflictChecker.RouteProfile> allProfiles,
      Timetable current) {
    TimetableOccupancyProjector.Occupancy occupancy =
        TimetableOccupancyProjector.project(current, input.profiles(), input.zeroSecondOfDay());
    List<TimetableConflictChecker.Movement> movements = new ArrayList<>(occupancy.movements());
    List<TimetableConflictChecker.Stay> stays = new ArrayList<>(occupancy.stays());
    for (NeighborTimetable neighbor : input.neighbors()) {
      movements.addAll(neighbor.movements());
      stays.addAll(neighbor.stays());
    }
    return TimetableConflictChecker.check(
        input.index(),
        allProfiles,
        movements,
        stays,
        input.separationSeconds(),
        TimetableConflictChecker.vehicleOf(current));
  }

  /**
   * 一次可修的让车：谁让、让多少。
   *
   * @param conflict 冲突
   * @param mover 后车 code（我的）
   * @param moverFrom 后车原本进入的时刻
   * @param leader 前车 code
   * @param leaderOwner 前车的邻表
   * @param waitSeconds 延后量
   */
  private record Move(
      TimetableConflictChecker.Conflict conflict,
      String mover,
      int moverFrom,
      String leader,
      Optional<String> leaderOwner,
      int waitSeconds) {

    String key() {
      return conflict.resource()
          + "|"
          + conflict.first()
          + "|"
          + conflict.second()
          + "|"
          + conflict.firstFrom()
          + "|"
          + conflict.secondFrom();
    }
  }

  /** 一处冲突对应的让车：后车是我就挪后车；后车是邻表就挪先到的我。双方都是邻表没有可挪的。 */
  private static Optional<Move> moveFor(Input input, TimetableConflictChecker.Conflict conflict) {
    int separation = input.separationSeconds();
    if (conflict.secondOwner().isEmpty()) {
      int wait = conflict.firstTo() + separation - conflict.secondFrom();
      return Optional.of(
          new Move(
              conflict,
              conflict.second(),
              conflict.secondFrom(),
              conflict.first(),
              conflict.firstOwner(),
              wait));
    }
    if (conflict.firstOwner().isEmpty()) {
      // 邻表后到也不能挪它：我这个先到的整趟延后到它离开之后。
      int wait = conflict.secondTo() + separation - conflict.firstFrom();
      return Optional.of(
          new Move(
              conflict,
              conflict.first(),
              conflict.firstFrom(),
              conflict.second(),
              conflict.secondOwner(),
              wait));
    }
    return Optional.empty();
  }

  /** 真冲突：延后量超过上限，或后车不是能挪的东西。 */
  private static boolean real(Input input, Move move, State state) {
    return move.waitSeconds() <= 0
        || move.waitSeconds() > input.maxWaitSeconds()
        || !state.canMove(move.mover());
  }

  private static int realCount(Input input, TimetableConflictChecker.Report report, State state) {
    int count = 0;
    for (TimetableConflictChecker.Conflict conflict : report.conflicts()) {
      Optional<Move> move = moveFor(input, conflict);
      if (move.isPresent() && real(input, move.get(), state)) {
        count++;
      }
    }
    return count;
  }

  /** 按检查器的稳定序取第一处可修的冲突：后车是我、延后量不超过上限、不在"已判为真冲突"的名单里。 */
  private static Optional<Move> pickMove(
      Input input, TimetableConflictChecker.Report report, Set<String> unrepairable, State state) {
    for (TimetableConflictChecker.Conflict conflict : report.conflicts()) {
      Optional<Move> candidate = moveFor(input, conflict);
      if (candidate.isEmpty()) {
        continue;
      }
      Move move = candidate.get();
      if (unrepairable.contains(move.key())) {
        continue;
      }
      if (real(input, move, state)) {
        unrepairable.add(move.key());
        continue;
      }
      return Optional.of(move);
    }
    return Optional.empty();
  }

  /** 修复过程中的可变状态：链、保留数、实际时刻、截断集合。 */
  private static final class State {
    private final Input input;
    private final Timetable table;
    private final List<List<TimetableTrip>> chains;
    private final int[] kept;
    private final int[] startDelay;
    private final int[] returnDelay;
    private final Map<UUID, Integer> nominal;
    private final Map<UUID, Integer> actual;
    private final Map<UUID, int[]> position;
    private final Set<UUID> truncated;
    private final Map<UUID, TerminalSerializer.Shift.Reason> reasons;
    private final Map<String, UUID> tripByCode;
    private final Map<String, Integer> dutyByCode;

    State(
        Input input,
        Timetable table,
        List<List<TimetableTrip>> chains,
        int[] kept,
        int[] startDelay,
        int[] returnDelay,
        Map<UUID, Integer> nominal,
        Map<UUID, Integer> actual,
        Map<UUID, int[]> position,
        Set<UUID> truncated,
        Map<UUID, TerminalSerializer.Shift.Reason> reasons,
        Map<String, UUID> tripByCode,
        Map<String, Integer> dutyByCode) {
      this.input = input;
      this.table = table;
      this.chains = chains;
      this.kept = kept;
      this.startDelay = startDelay;
      this.returnDelay = returnDelay;
      this.nominal = nominal;
      this.actual = actual;
      this.position = position;
      this.truncated = truncated;
      this.reasons = reasons;
      this.tripByCode = tripByCode;
      this.dutyByCode = dutyByCode;
    }

    /** 可回滚的状态快照：施加一处让车之前拍，连锁变糟时恢复。 */
    record Snapshot(
        int[] kept,
        int[] startDelay,
        int[] returnDelay,
        Map<UUID, Integer> actual,
        Set<UUID> truncated,
        Map<UUID, TerminalSerializer.Shift.Reason> reasons) {}

    Snapshot snapshot() {
      return new Snapshot(
          kept.clone(),
          startDelay.clone(),
          returnDelay.clone(),
          new HashMap<>(actual),
          new TreeSet<>(truncated),
          new HashMap<>(reasons));
    }

    void restore(Snapshot snapshot) {
      System.arraycopy(snapshot.kept(), 0, kept, 0, kept.length);
      System.arraycopy(snapshot.startDelay(), 0, startDelay, 0, startDelay.length);
      System.arraycopy(snapshot.returnDelay(), 0, returnDelay, 0, returnDelay.length);
      actual.clear();
      actual.putAll(snapshot.actual());
      truncated.clear();
      truncated.addAll(snapshot.truncated());
      reasons.clear();
      reasons.putAll(snapshot.reasons());
    }

    Timetable rewrite() {
      return TerminalSerializer.rewrite(
          table,
          chains,
          kept,
          actual,
          truncated,
          startDelay,
          returnDelay,
          input.zeroSecondOfDay(),
          input.limits().turnaround(),
          input.routesEndingAtDepot(),
          input.legs());
    }

    /** 这个 code 是不是我能挪的东西：班次、出库走行、回库走行、待命。 */
    boolean canMove(String code) {
      if (tripByCode.containsKey(code)) {
        return !truncated.contains(tripByCode.get(code));
      }
      return dutyIndexOf(code) >= 0;
    }

    private int dutyIndexOf(String code) {
      String dutyCode = code;
      if (code.endsWith("-CREATE") || code.endsWith("-RETURN")) {
        dutyCode = code.substring(0, code.length() - 7);
      }
      Integer d = dutyByCode.get(dutyCode);
      return d == null ? -1 : d;
    }

    /**
     * 施加一次让车；返回 false 表示这一处不能这样修（该判为真冲突）。
     *
     * <p>班次：整趟延后。出库走行：先吃掉到站等首班的空闲，吃不下的部分转成首班延后。回库走行：回库票延后（车在终点多待一会）。 待命：延后带来这段待命的那一班（到得晚一点）。
     */
    boolean apply(Move move) {
      String code = move.mover();
      int wait = move.waitSeconds();
      if (tripByCode.containsKey(code)) {
        int[] pos = position.get(tripByCode.get(code));
        return pos != null && delayTrip(pos[0], pos[1], wait, true);
      }
      int d = dutyIndexOf(code);
      if (d < 0 || kept[d] <= 0) {
        return false;
      }
      if (code.endsWith("-RETURN")) {
        return delayReturn(d, wait);
      }
      if (code.endsWith("-CREATE")) {
        return delayStart(d, wait);
      }
      // 待命：找到以 moverFrom 到达的那一班。首段待命（出库到站等首班）从出库走行算。
      List<TimetableTrip> chain = chains.get(d);
      for (int i = 0; i < kept[d]; i++) {
        TimetableTrip trip = chain.get(i);
        TimetableRoutePlan plan = table.routePlan(trip.routeId()).orElse(null);
        if (plan == null) {
          continue;
        }
        int arrival =
            actual.getOrDefault(trip.id(), nominal.get(trip.id())) + plan.totalRunSeconds();
        if (arrival == move.moverFrom()) {
          return delayTrip(d, i, wait, true);
        }
      }
      if (createArrival(d) == move.moverFrom()) {
        return delayStart(d, wait);
      }
      return false;
    }

    /** 出库走行到达首站的时刻（相对秒），没有出库走行时是首班发车。 */
    private int createArrival(int d) {
      VehicleDuty duty = table.duties().get(d);
      TimetableTrip first = chains.get(d).get(0);
      int firstDeparture = actual.getOrDefault(first.id(), nominal.get(first.id()));
      if (duty.createRouteId().isEmpty()) {
        return firstDeparture;
      }
      TimetableConflictChecker.RouteProfile create =
          input.profiles().get(duty.createRouteId().get());
      int run =
          create == null || create.stops().isEmpty()
              ? 0
              : create.stops().get(create.stops().size() - 1).arrivalOffsetSeconds();
      int start = duty.plannedStartSecondOfDay() - input.zeroSecondOfDay() + startDelay[d];
      return Math.min(start + run, firstDeparture);
    }

    private boolean delayStart(int d, int wait) {
      VehicleDuty duty = table.duties().get(d);
      if (duty.createRouteId().isEmpty()) {
        return delayTrip(d, 0, wait, true);
      }
      TimetableTrip first = chains.get(d).get(0);
      int firstDeparture = actual.getOrDefault(first.id(), nominal.get(first.id()));
      int slack = Math.max(0, firstDeparture - createArrival(d));
      int absorbed = Math.min(slack, wait);
      startDelay[d] += absorbed;
      if (wait > absorbed) {
        return delayTrip(d, 0, wait - absorbed, true);
      }
      return true;
    }

    private boolean delayReturn(int d, int wait) {
      if (returnDelay[d] + wait > input.toleranceSeconds()) {
        return false;
      }
      VehicleDuty duty = table.duties().get(d);
      int dutyStart = duty.plannedStartSecondOfDay() - input.zeroSecondOfDay() + startDelay[d];
      int end = duty.plannedEndSecondOfDay() - input.zeroSecondOfDay() + returnDelay[d] + wait;
      if (end - dutyStart > input.limits().maxDutyDurationSeconds()) {
        return false;
      }
      returnDelay[d] += wait;
      return true;
    }

    /** 第 d 条交路第 i 班延后 wait 秒，并沿链传播；累计超限或超上限时从那一班起截断。 */
    private boolean delayTrip(int d, int i, int wait, boolean yielded) {
      List<TimetableTrip> chain = chains.get(d);
      if (i >= kept[d]) {
        return false;
      }
      VehicleDuty duty = table.duties().get(d);
      int j = i;
      int delta = wait;
      while (j < kept[d] && delta > 0) {
        TimetableTrip trip = chain.get(j);
        TimetableRoutePlan plan = table.routePlan(trip.routeId()).orElse(null);
        int base = actual.getOrDefault(trip.id(), nominal.get(trip.id()));
        int dep = base + delta;
        boolean over =
            dep - nominal.get(trip.id()) > input.toleranceSeconds()
                || plan == null
                || TerminalSerializer.exceedsLimits(
                    dep,
                    plan,
                    duty,
                    input.horizonSeconds(),
                    input.limits().turnaround().secondsFor(trip.routeId()),
                    input.routesEndingAtDepot(),
                    input.legs(),
                    input.limits(),
                    input.zeroSecondOfDay());
        if (over) {
          TerminalSerializer.truncateFrom(
              j,
              chain,
              d,
              kept,
              actual,
              truncated,
              input.routesEndingAtDepot(),
              input.legs(),
              table);
          return true;
        }
        actual.put(trip.id(), dep);
        reasons.merge(
            trip.id(),
            j == i && yielded
                ? TerminalSerializer.Shift.Reason.YIELDED
                : TerminalSerializer.Shift.Reason.VEHICLE_READY,
            (old, next) -> old == TerminalSerializer.Shift.Reason.YIELDED ? old : next);
        // 传播：下一班的就绪 = 本班到达 + 折返；它原本比就绪早就跟着后移，否则链到此为止。
        if (j + 1 >= kept[d]) {
          break;
        }
        int ready =
            dep + plan.totalRunSeconds() + input.limits().turnaround().secondsFor(trip.routeId());
        TimetableTrip next = chain.get(j + 1);
        int nextDep = actual.getOrDefault(next.id(), nominal.get(next.id()));
        delta = ready - nextDep;
        j++;
      }
      return true;
    }
  }

  /** 让车清单里前车/后车的 code 替换成正式车次号（duty 号不变）。 */
  static List<Yield> renamed(List<Yield> yields, Map<String, String> finalCodeByProvisional) {
    List<Yield> out = new ArrayList<>(yields.size());
    for (Yield yield : yields) {
      out.add(
          new Yield(
              yield.kind(),
              yield.resource(),
              finalCodeByProvisional.getOrDefault(yield.first(), yield.first()),
              yield.firstOwner(),
              finalCodeByProvisional.getOrDefault(yield.second(), yield.second()),
              yield.waitSeconds(),
              yield.atSeconds()));
    }
    return List.copyOf(out);
  }

  /** 供报告：让车里最长的一处。 */
  public static int maxWait(List<Yield> yields) {
    int max = 0;
    for (Yield yield : yields) {
      max = Math.max(max, yield.waitSeconds());
    }
    return max;
  }

  /** 供报告：涉及的后车数。 */
  public static int movedCount(List<Yield> yields) {
    Set<String> out = new HashSet<>();
    for (Yield yield : yields) {
      out.add(yield.second());
    }
    return out.size();
  }
}
