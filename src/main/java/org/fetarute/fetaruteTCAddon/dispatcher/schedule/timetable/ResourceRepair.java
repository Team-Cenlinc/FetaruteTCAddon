package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
 * <p>让车会在别处制造新冲突（连锁）。判据按<b>连锁段</b>走而不是按单步：施加一处之后，这一步新冒出来的冲突若全部可修就接着修， 最多 {@value #CHAIN_LIMIT}
 * 步；段末与段初比 {@code (真冲突数, 冲突总数)}，段末不合格时先退到<b>冲突最少的那个前缀</b>再判一次——
 * 一串让车常常前几步换来好处、后几步又还回去，整段一刀切会把已经到手的那部分白扔；前缀也不合格才整段回滚。
 * 单步判据会把"一串小让车各挪二十秒"的每一步都拒掉——每一步单独看都不减少冲突，合起来才减少；实测 WS@300 的一千九百处残余没有一处是预算问题，全是这个。
 * 段末仍要求冲突总数严格减少，所以修复只朝一个方向走，循环必然终止。 回滚时判死的是<b>挪不动的那一步</b>（没有就是开段的那一处），而不是无差别地怪开段。
 *
 * <p>重扫是增量的（{@link OccupationIndex}）：一处让车只改一条交路，于是只重投影那条交路、只重扫它前后碰过的资源。 返回的 {@code remaining}
 * 另外做一次全量重扫，与 builder 最后那一遍同一口径。
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

  /** 连锁段深度上限：一处让车最多带出五步跟进修复。再深的连锁不是让车能解决的，是相位问题，交给第三层。 */
  public static final int CHAIN_LIMIT = 6;

  /** 重扫方式。增量是生产路径；全量是它的参照物，只在等价性用例里走到。 */
  enum Rescan {
    /** 只重投影被改的那条交路、只重扫它碰过的资源。 */
    INCREMENTAL,
    /** 每次都把整张表重新投影再全扫。 */
    FULL
  }

  /** 修复主入口。{@code maxWaitSeconds == 0} 时只扫一遍冲突、原样返回表。 */
  public static Result repair(Input input) {
    return repair(input, Rescan.INCREMENTAL);
  }

  /** 带重扫方式的入口：两种方式必须得到逐字段相同的产物，{@code incrementalRepairEqualsFullRescan} 钉住这一条。 */
  static Result repair(Input input, Rescan mode) {
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
    Set<MoveKey> unrepairable = new HashSet<>();
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

    // maxWait == 0 是"只扫一遍冲突、原样返回表"的口径，别为它白建一份索引：Rescanner 的构造要把整张表
    // 投影一遍再全扫，而下面的循环一步都不会走，最后的 remaining 又会自己再全扫一遍。
    Rescanner rescanner =
        input.maxWaitSeconds() > 0
            ? new Rescanner(input, allProfiles, mode, state.rewrite())
            : null;
    // 上界按"起始冲突数"而不只按班次数：连锁段的每一步都计入，光有班次那一项装不下一串连锁。
    int cap = rescanner == null ? 0 : 2 * rescanner.total() + 4 * table.trips().size();
    int steps = 0;
    while (rescanner != null && steps < cap) {
      Pick pick = pickMove(input, rescanner, unrepairable, state);
      Optional<Move> opener = pick.opener();
      if (opener.isEmpty()) {
        break;
      }
      int realBefore = pick.realCount();
      int totalBefore = rescanner.total();
      State.Snapshot snapshot = state.snapshot();
      Set<Integer> movedDuties = new LinkedHashSet<>();
      List<Move> applied = new ArrayList<>();
      Move move = opener.get();
      Move failed = null;
      // 冲突总数最少的那个前缀。段末不合格时退到这里再判一次：一串让车常常是前几步换来了好处、
      // 后几步又还回去，整段一刀切会把已经到手的那部分一起扔掉。
      int bestPrefix = 0;
      int bestTotal = totalBefore;
      for (int depth = 0; depth < CHAIN_LIMIT && move != null; depth++) {
        steps++;
        int d = state.dutyOf(move.mover());
        if (d < 0 || !state.apply(move)) {
          // 这一步挪不动（累计让车超限、交路超时，或找不到对应的那一班）。施加到一半的状态没法就地
          // 评估，整段作废——但要判死的是挪不动的这一步，不是开段的那一处，见下面的 unrepairable。
          failed = move;
          break;
        }
        movedDuties.add(d);
        applied.add(move);
        List<TimetableConflictChecker.Conflict> fresh = rescanner.afterMove(state, d);
        if (rescanner.total() < bestTotal) {
          bestTotal = rescanner.total();
          bestPrefix = applied.size();
        }
        move = chainFollowUp(input, fresh, unrepairable, state);
      }
      int accepted =
          failed == null && accepts(input, rescanner, state, realBefore, totalBefore)
              ? applied.size()
              : 0;
      if (accepted == 0 && bestPrefix > 0 && bestPrefix < applied.size()) {
        // 整段不合格，前缀未必不合格：退回段初，只重放冲突最少的那个前缀再判一次。
        rollback(rescanner, state, snapshot, movedDuties);
        movedDuties.clear();
        replay(rescanner, state, applied, bestPrefix, movedDuties);
        if (accepts(input, rescanner, state, realBefore, totalBefore)) {
          accepted = bestPrefix;
        }
      }
      if (accepted == 0) {
        rollback(rescanner, state, snapshot, movedDuties);
        // 判死挪不动的那一步，而不是开段的那一处：开段的那处本来可能修得了，把它判死等于白丢一处修复；
        // 而下一轮重新开段时 chainFollowUp 会直接跳过已判死的这一步，段自然在那里收住，不会再撞一次。
        // 名单每回滚一次必定净增一条（能走到这里的那一步一定还不在名单里），所以循环照旧必然终止。
        unrepairable.add(failed != null ? failed.key() : opener.get().key());
        continue;
      }
      for (int i = 0; i < accepted; i++) {
        yields.add(yieldOf(applied.get(i)));
      }
    }
    Timetable current = state.rewrite();

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
    // 交出去的残余永远来自一次全量重扫：增量只用来在修复过程里做取舍，报出去的数不该依赖它有没有漂移。
    return new Result(
        current, shifts, yields, new ArrayList<>(truncated), scan(input, allProfiles, current));
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

    MoveKey key() {
      return new MoveKey(
          conflict.resource(),
          conflict.first(),
          conflict.second(),
          conflict.firstFrom(),
          conflict.secondFrom());
    }
  }

  /**
   * 让车的身份：同一处冲突第二次被挑中时认得出来。
   *
   * <p>原来是把这五个字段拼成一个七八十字符的串。名单每轮要查上千次，每次都现拼一个新串、再把整串哈希 一遍；而 {@code resource} 本身是共享实例，它的哈希早就算好了。换成
   * record 之后语义一字不变， 开销只剩五个字段的比较。
   */
  private record MoveKey(
      String resource, String first, String second, int firstFrom, int secondFrom) {}

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

  /** 段末判据：冲突总数严格减少（保证循环只朝一个方向走），且真冲突不增加。总数便宜，先算它。 */
  private static boolean accepts(
      Input input, Rescanner rescanner, State state, int realBefore, int totalBefore) {
    return rescanner.total() < totalBefore && realCount(input, rescanner, state) <= realBefore;
  }

  /** 退回段初：状态与索引一起退，被动过的每条交路逐条重投影。 */
  private static void rollback(
      Rescanner rescanner, State state, State.Snapshot snapshot, Set<Integer> movedDuties) {
    state.restore(snapshot);
    for (int d : movedDuties) {
      rescanner.afterMove(state, d);
    }
  }

  /** 从段初重放前 {@code count} 步。段初刚被 {@link #rollback} 还原过，重放与当初逐步施加等价。 */
  private static void replay(
      Rescanner rescanner, State state, List<Move> applied, int count, Set<Integer> movedDuties) {
    for (int i = 0; i < count; i++) {
      Move move = applied.get(i);
      int d = state.dutyOf(move.mover());
      if (d < 0 || !state.apply(move)) {
        return;
      }
      movedDuties.add(d);
      rescanner.afterMove(state, d);
    }
  }

  private static int realCount(Input input, Rescanner rescanner, State state) {
    int count = 0;
    for (List<TimetableConflictChecker.Conflict> group : rescanner.conflicts()) {
      for (TimetableConflictChecker.Conflict conflict : group) {
        Optional<Move> move = moveFor(input, conflict);
        if (move.isPresent() && real(input, move.get(), state)) {
          count++;
        }
      }
    }
    return count;
  }

  /**
   * 按检查器的稳定序取第一处可修的冲突：后车是我、延后量不超过上限、不在"已判为真冲突"的名单里。
   *
   * <p>不把全表冲突排一遍：上千条冲突每开一段排一次纯属白排。先一趟挑出序最小的那处可修的，再把序排在它之前的真冲突记进名单——
   * 与"按序逐条看过去、碰到真冲突就记下、碰到可修的就停"逐字等价，只是省掉了排序。
   */
  private static Pick pickMove(
      Input input, Rescanner rescanner, Set<MoveKey> unrepairable, State state) {
    Move best = null;
    TimetableConflictChecker.Conflict bestAt = null;
    int reals = 0;
    List<TimetableConflictChecker.Conflict> blocked = new ArrayList<>();
    List<MoveKey> blockedKeys = new ArrayList<>();
    for (List<TimetableConflictChecker.Conflict> group : rescanner.conflicts()) {
      for (TimetableConflictChecker.Conflict conflict : group) {
        Optional<Move> candidate = moveFor(input, conflict);
        if (candidate.isEmpty()) {
          continue;
        }
        Move move = candidate.get();
        // 真冲突先数、再看名单：段初的这个数要与段末的 realCount 同一口径，而 realCount 不看名单。
        boolean isReal = real(input, move, state);
        if (isReal) {
          reals++;
        }
        if (unrepairable.contains(move.key())) {
          continue;
        }
        if (isReal) {
          blocked.add(conflict);
          blockedKeys.add(move.key());
          continue;
        }
        if (bestAt == null
            || TimetableConflictChecker.CONFLICT_ORDER.compare(conflict, bestAt) < 0) {
          bestAt = conflict;
          best = move;
        }
      }
    }
    for (int i = 0; i < blocked.size(); i++) {
      if (bestAt == null
          || TimetableConflictChecker.CONFLICT_ORDER.compare(blocked.get(i), bestAt) < 0) {
        unrepairable.add(blockedKeys.get(i));
      }
    }
    return new Pick(Optional.ofNullable(best), reals);
  }

  /**
   * 挑出来的那一处，连同这一刻的真冲突数。
   *
   * <p>两样是同一趟遍历的产物：{@code pickMove} 本来就要对每一处冲突算一遍 {@code real}，把结果扔掉、 紧接着再让 {@code realCount}
   * 把同样的活重做一遍，等于每轮白走一趟全表（WS@300 是上千条冲突 × 几千轮）。
   */
  private record Pick(Optional<Move> opener, int realCount) {}

  /**
   * 连锁段的下一步。
   *
   * <p>只看这一步<b>新冒出来</b>的冲突：全部可修（后车是我、延后量在上限内、不在真冲突名单里）就接着修它们里按检查器序的第一处。
   * 有一处修不了就收段——不在这里判死，交给段末的判据决定要不要整段回滚，因为后面几步很可能把它一起消掉。 没有新增说明这一段已经收敛，同样收段。
   */
  private static Move chainFollowUp(
      Input input,
      List<TimetableConflictChecker.Conflict> fresh,
      Set<MoveKey> unrepairable,
      State state) {
    Move first = null;
    for (TimetableConflictChecker.Conflict conflict : fresh) {
      Optional<Move> candidate = moveFor(input, conflict);
      if (candidate.isEmpty()
          || unrepairable.contains(candidate.get().key())
          || real(input, candidate.get(), state)) {
        return null;
      }
      if (first == null) {
        first = candidate.get();
      }
    }
    return first;
  }

  private static Yield yieldOf(Move move) {
    return new Yield(
        move.conflict().kind(),
        move.conflict().resource(),
        move.leader(),
        move.leaderOwner(),
        move.mover(),
        move.waitSeconds(),
        move.moverFrom());
  }

  /**
   * 修复循环的重扫。
   *
   * <p>增量：一处让车只改一条交路，于是只重投影那条交路、只重扫它前后碰过的资源，别的资源沿用上一次的结果。冲突按资源存着 （{@code
   * byResource}）而不是存成一张排好序的报告：全表上千条冲突，每一步合并加排序的开销比重扫本身还大，
   * 而循环真正要的只有三样——总数、全体（数真冲突用）、<b>这一步新冒出来的那几条</b>。 只有被碰过的资源上的冲突会变，差集也就只能出在那里。
   *
   * <p>全量：每一步把整张表重新投影再全扫。它是增量的参照物，生产路径不走。
   *
   * <p>车辆身份取自修复开始时的那张表，之后不再重算：让车只改时刻不改班次归属，截断只会让 code 连同它的占用一起从表上消失， 还留在表上的 code 映射到的交路号不会变。
   */
  private static final class Rescanner {
    private final Input input;
    private final Map<UUID, TimetableConflictChecker.RouteProfile> allProfiles;
    private final Rescan mode;
    private final java.util.function.Function<String, String> vehicleOf;
    private final Set<UUID> returnRouteIds;
    private final int serviceStartSecondOfDay;
    private final Map<String, List<TimetableConflictChecker.Conflict>> byResource = new HashMap<>();
    private OccupationIndex occupations;
    private int total;

    Rescanner(
        Input input,
        Map<UUID, TimetableConflictChecker.RouteProfile> allProfiles,
        Rescan mode,
        Timetable initial) {
      this.input = input;
      this.allProfiles = allProfiles;
      this.mode = mode;
      this.vehicleOf = TimetableConflictChecker.vehicleOf(initial);
      this.returnRouteIds = TimetableOccupancyProjector.returnRouteIdsOf(initial);
      this.serviceStartSecondOfDay = initial.serviceStartSecondOfDay();
      replaceAll(
          mode == Rescan.FULL ? scan(input, allProfiles, initial).conflicts() : rebuild(initial));
    }

    /** 当前全表冲突总数。 */
    int total() {
      return total;
    }

    /** 当前全表冲突，按资源分组、组内有序；全表的序要靠 {@link TimetableConflictChecker#CONFLICT_ORDER} 自己比。 */
    Collection<List<TimetableConflictChecker.Conflict>> conflicts() {
      return byResource.values();
    }

    /**
     * 第 {@code d} 条交路改了时刻之后重扫。
     *
     * @return 这一步<b>新冒出来</b>的冲突，按检查器序
     */
    List<TimetableConflictChecker.Conflict> afterMove(State state, int d) {
      if (mode == Rescan.FULL) {
        return replaceAll(scan(input, allProfiles, state.rewrite()).conflicts());
      }
      TerminalSerializer.RewrittenDuty rewritten = state.rewriteOne(d);
      if (rewritten == null && state.alive(d)) {
        // 交路还该在表上却不见了（rewriteDuty 的防御分支）：它的班次留在表上但归属没了，增量表达不了，退回整表重建。
        return replaceAll(rebuild(state.rewrite()));
      }
      List<TimetableConflictChecker.Movement> movements = List.of();
      List<TimetableConflictChecker.Stay> stays = List.of();
      if (rewritten != null) {
        TimetableOccupancyProjector.Occupancy occupancy =
            TimetableOccupancyProjector.projectDuty(
                rewritten.duty(),
                rewritten.trips(),
                input.profiles(),
                returnRouteIds,
                serviceStartSecondOfDay,
                input.zeroSecondOfDay());
        movements = occupancy.movements();
        stays = occupancy.stays();
      }
      Set<String> keys =
          occupations.replaceVehicle(
              TimetableConflictChecker.vehicleKey(state.dutyCodeOf(d), Optional.empty(), vehicleOf),
              movements,
              stays);
      Set<TimetableConflictChecker.Conflict> old = new HashSet<>();
      int removed = 0;
      for (String key : keys) {
        List<TimetableConflictChecker.Conflict> group = byResource.remove(key);
        if (group != null) {
          old.addAll(group);
          removed += group.size();
        }
      }
      List<TimetableConflictChecker.Conflict> rescanned =
          occupations.scan(keys, input.separationSeconds()).conflicts();
      group(rescanned);
      total += rescanned.size() - removed;
      return freshOf(rescanned, old);
    }

    /** 整表换一遍冲突，并交回相对上一次新冒出来的那些。 */
    private List<TimetableConflictChecker.Conflict> replaceAll(
        List<TimetableConflictChecker.Conflict> all) {
      Set<TimetableConflictChecker.Conflict> old = new HashSet<>();
      for (List<TimetableConflictChecker.Conflict> group : byResource.values()) {
        old.addAll(group);
      }
      byResource.clear();
      group(all);
      total = all.size();
      return freshOf(all, old);
    }

    private static List<TimetableConflictChecker.Conflict> freshOf(
        List<TimetableConflictChecker.Conflict> scanned,
        Set<TimetableConflictChecker.Conflict> old) {
      List<TimetableConflictChecker.Conflict> fresh = new ArrayList<>();
      for (TimetableConflictChecker.Conflict conflict : scanned) {
        if (!old.contains(conflict)) {
          fresh.add(conflict);
        }
      }
      return fresh;
    }

    /** 整表重建索引，交回全表冲突。 */
    private List<TimetableConflictChecker.Conflict> rebuild(Timetable current) {
      TimetableOccupancyProjector.Occupancy occupancy =
          TimetableOccupancyProjector.project(current, input.profiles(), input.zeroSecondOfDay());
      List<TimetableConflictChecker.Movement> movements = new ArrayList<>(occupancy.movements());
      List<TimetableConflictChecker.Stay> stays = new ArrayList<>(occupancy.stays());
      for (NeighborTimetable neighbor : input.neighbors()) {
        movements.addAll(neighbor.movements());
        stays.addAll(neighbor.stays());
      }
      occupations = OccupationIndex.of(input.index(), allProfiles, movements, stays, vehicleOf);
      return occupations.scanAll(input.separationSeconds()).conflicts();
    }

    private void group(List<TimetableConflictChecker.Conflict> conflicts) {
      for (TimetableConflictChecker.Conflict conflict : conflicts) {
        byResource.computeIfAbsent(conflict.resource(), key -> new ArrayList<>()).add(conflict);
      }
    }
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

    /** 这个 code 归哪条交路：让车只改一条交路的时刻，增量重扫要按它换占用。挪不动的东西返回 −1。 */
    int dutyOf(String code) {
      UUID tripId = tripByCode.get(code);
      if (tripId != null) {
        int[] pos = position.get(tripId);
        return pos == null ? -1 : pos[0];
      }
      return dutyIndexOf(code);
    }

    /** 这条交路还有班次留在表上。 */
    boolean alive(int d) {
      return d >= 0 && d < kept.length && kept[d] > 0;
    }

    String dutyCodeOf(int d) {
      return table.duties().get(d).dutyCode();
    }

    /** 第 d 条交路改写之后的样子；整条不落表时为 {@code null}。增量重扫只要这一条，不必重建整张表。 */
    TerminalSerializer.RewrittenDuty rewriteOne(int d) {
      return TerminalSerializer.rewriteDuty(
          table,
          chains.get(d),
          d,
          table.duties().get(d),
          kept,
          actual,
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
