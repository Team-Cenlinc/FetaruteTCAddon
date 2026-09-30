package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 残余冲突的分类：运行时能不能让、在哪让、让多久。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>运行时对这一类冲突的处理一律是"后车在资源前等"，而且<b>没有上限</b>。以"全表零冲突"作为成功判据，等于把运行时每天在做的事判成不可行——
 * 让车机制修不掉的残余里，大量是单步等待很短、纯粹由连锁引起的冲突，运行时根本不会当回事。
 *
 * <p>于是 {@code attempt} 的产物分三类：<b>让车</b>（已经写进表的延后）、<b>可吸收残余</b>（写不进表但运行时能让）、
 * <b>不可吸收残余</b>。成功判据只看第三类，搜索放宽与 {@code --strict} 也只看它。
 *
 * <h2>可吸收的五条判据</h2>
 *
 * <p>按顺序判，第一条命中即出结论，因此判决是确定的：
 *
 * <ol>
 *   <li><b>邻表</b>：一方是已发布邻表 → 不可吸收。我挪不动它，路权先到先得。
 *   <li><b>容量 1 的端点</b>：<b>后车要等的地方</b>只有一股道 → 不可吸收，在这里等就是堵死岔线。判不出后车在哪等时
 *       （手上没有成品表），退回看冲突落在不落在这类端点的站台或进站单线上。
 *   <li><b>预计等待超过 {@code --max-wait}</b> → 不可吸收。与让车修复同一个预算。
 *   <li><b>让车点没有容量</b>：后车要等的地方在那一刻已经站满 → 不可吸收。
 *   <li><b>顺推超限</b>：这一等让后续班次赶不上表定时刻（本班到达 + 折返 + 等待 &gt; 下一班发车），而表上没有推后它 → 不可吸收。
 *       写得进去的顺推已由让车修复的必等顺推写进表（{@link ResourceRepair}），还剩下的就是推不动的——推迟超过 {@code --max-wait}
 *       或会截掉班次。间隔规整优先于多排几班：这种情况判排不开，由搜索放宽间隔。
 * </ol>
 *
 * <p>单线对向本身是<b>可吸收</b>的：运行时按区段互斥，后车在区段外等。不可吸收的是"等的地方是容量 1 站台"那一种， 已被第 2 条覆盖。
 *
 * <p>本类不落库：build 与 publish 重检各算一遍，两边必须得到同一结果——所以它只依赖成品表、投影与图索引， 不依赖构建过程里的任何中间状态。
 */
public final class ConflictAbsorption {

  /**
   * 表上没写进去的顺推允许几班：0。
   *
   * <p>判据拿"下一班发车"去比"本班到达 + 折返 + 等待"，而不是"本班<b>发车</b> + 等待"：一趟车的走行远长于等待，后者永远不会成立，
   * 顺推超限就一次都判不出来——同一处冲突每一圈都在，晚点一圈圈累积直到被作废，表上却看不出来。 写得进去的顺推已经写进表，剩下一班都不放过。
   */
  public static final int MAX_CHAIN_DEPTH = 0;

  private ConflictAbsorption() {}

  /** 判决。 */
  public enum Verdict {
    /** 运行时能让：后车在让车点等一会儿即可。 */
    ABSORBABLE,
    /** 一方是已发布邻表：我挪不动它。 */
    EXTERNAL,
    /** 让车点是容量 1 的端点或它的进站单线：在这里等会堵死岔线。 */
    STUB_TERMINAL,
    /** 预计等待超过单步上限。 */
    OVER_MAX_WAIT,
    /** 让车点在那一刻没有空位。 */
    NO_WAITING_CAPACITY,
    /** 顺推超限：后续班次赶不上表定时刻、而表上没有推后它们（推迟超 {@code --max-wait} 或会截断）。 */
    CHAIN_TOO_DEEP
  }

  /**
   * 一处残余。
   *
   * @param conflict 冲突本身
   * @param mover 后车的 code（车次号或交路号）——运行时要等的是它
   * @param waitSeconds 预计等待秒数
   * @param waitingPoint 让车点（站台组、车库，或判不出来时为空）
   * @param chainDepth 因这一等而被顶后的后续班次数
   * @param verdict 判决
   */
  public record Residual(
      TimetableConflictChecker.Conflict conflict,
      String mover,
      int waitSeconds,
      Optional<String> waitingPoint,
      int chainDepth,
      Verdict verdict) {

    public Residual {
      Objects.requireNonNull(conflict, "conflict");
      Objects.requireNonNull(verdict, "verdict");
      mover = mover == null ? "" : mover;
      waitSeconds = Math.max(0, waitSeconds);
      waitingPoint = waitingPoint == null ? Optional.empty() : waitingPoint;
      chainDepth = Math.max(0, chainDepth);
    }

    public boolean absorbable() {
      return verdict == Verdict.ABSORBABLE;
    }
  }

  /**
   * 分类。
   *
   * @param report 让车修复之后剩下的冲突报告
   * @param timetable 最终表：用来找后车所在的交路与它的起点
   * @param occupancy 最终表的投影：数让车点在那一刻有几辆车在待命
   * @param index 图索引：站台组容量
   * @param separationSeconds 相邻占用裕量
   * @param maxWaitSeconds 单步让车上限
   * @param turnaround 各 route 终到之后多久能再发车：判后续班次赶不赶得上要用
   * @return 每处冲突一条，顺序与 {@code report} 一致
   */
  public static List<Residual> classify(
      TimetableConflictChecker.Report report,
      Timetable timetable,
      TimetableOccupancyProjector.Occupancy occupancy,
      TimetableConflictChecker.GraphIndex index,
      int separationSeconds,
      int maxWaitSeconds,
      TurnaroundTable turnaround) {
    if (report == null || report.conflicts().isEmpty()) {
      return List.of();
    }
    return classify(
        Context.of(timetable, occupancy, index, turnaround),
        report,
        separationSeconds,
        maxWaitSeconds);
  }

  /** 同上，折返按 0 计（到了就能走）：只给没有 route 上下文的用例用，编表必须传真实的折返表。 */
  public static List<Residual> classify(
      TimetableConflictChecker.Report report,
      Timetable timetable,
      TimetableOccupancyProjector.Occupancy occupancy,
      TimetableConflictChecker.GraphIndex index,
      int separationSeconds,
      int maxWaitSeconds) {
    return classify(
        report,
        timetable,
        occupancy,
        index,
        separationSeconds,
        maxWaitSeconds,
        TurnaroundTable.none());
  }

  /**
   * 手上没有成品表、但知道每个 code 从哪个站台组始发时的分类。
   *
   * <p>相位第三层走这条：它按周期模板铺开的是"流"，没有成品表，可它<b>知道</b>每条流的始发站台组——
   * 那正是判据第二条要的"后车在哪等"。不给的话每一处都会落到按资源保守判，于是第三层挑 δ 用的是一把
   * 比成功判据更严的尺子，而它的职责恰恰是把不可吸收的那些消掉。同一处冲突两层必须算出同一结论， 否则就是缺陷。
   *
   * @param waitingPointByCode code → 后车要等的站台组；空串表示车库（容量不限）
   */
  public static List<Residual> classify(
      TimetableConflictChecker.Report report,
      Map<String, String> waitingPointByCode,
      TimetableConflictChecker.GraphIndex index,
      int separationSeconds,
      int maxWaitSeconds) {
    if (report == null || report.conflicts().isEmpty()) {
      return List.of();
    }
    return classify(
        Context.ofWaitingPoints(waitingPointByCode, index),
        report,
        separationSeconds,
        maxWaitSeconds);
  }

  /**
   * 必等顺推用：哪些冲突是运行时"在资源前等一下"就能过去的——判据前四条（邻表、容量 1 端点、超上限、让车点无容量），不看顺推。
   *
   * <p>顺推要写进表的正是这类等待；不可吸收的那些本来就会让这次尝试失败、交给搜索放宽，推它们只会把别处搅乱。 顺推本身取决于有没有推，所以这里不能把第五条算进来。
   *
   * @param conflicts 当前的冲突
   * @param timetable 当前表
   * @param occupancy 当前表的投影
   * @param index 图索引
   * @param separationSeconds 相邻占用裕量
   * @param maxWaitSeconds 单步让车上限
   * @return 每处冲突一条，顺序与 {@code conflicts} 一致
   */
  static List<Residual> classifyWaits(
      List<TimetableConflictChecker.Conflict> conflicts,
      Timetable timetable,
      TimetableOccupancyProjector.Occupancy occupancy,
      TimetableConflictChecker.GraphIndex index,
      int separationSeconds,
      int maxWaitSeconds) {
    if (conflicts == null || conflicts.isEmpty()) {
      return List.of();
    }
    return classify(
        Context.of(timetable, occupancy, index, TurnaroundTable.none()).withoutChain(),
        new TimetableConflictChecker.Report(conflicts),
        separationSeconds,
        maxWaitSeconds);
  }

  private static List<Residual> classify(
      Context context,
      TimetableConflictChecker.Report report,
      int separationSeconds,
      int maxWaitSeconds) {
    int separation = Math.max(0, separationSeconds);
    int maxWait = Math.max(0, maxWaitSeconds);
    List<Residual> out = new ArrayList<>(report.conflicts().size());
    for (TimetableConflictChecker.Conflict conflict : report.conflicts()) {
      out.add(classifyOne(conflict, context, separation, maxWait));
    }
    return List.copyOf(out);
  }

  /** 报告用：按判决计数，缺的判决不出现。 */
  public static Map<Verdict, Integer> countByVerdict(List<Residual> residuals) {
    Map<Verdict, Integer> out = new EnumMap<>(Verdict.class);
    if (residuals != null) {
      for (Residual residual : residuals) {
        if (residual != null) {
          out.merge(residual.verdict(), 1, Integer::sum);
        }
      }
    }
    return out;
  }

  /**
   * 容量 1 的站台组（只有一股道的车站、车库）：图索引里股道数不超过 1 的组。
   *
   * @param index 图索引
   * @return 站台组名的集合
   */
  static Set<String> stubGroups(TimetableConflictChecker.GraphIndex index) {
    Set<String> stubs = new HashSet<>();
    if (index != null) {
      index
          .platformCapacity()
          .forEach(
              (group, tracks) -> {
                if (tracks != null && tracks <= 1) {
                  stubs.add(group);
                }
              });
    }
    return stubs;
  }

  /**
   * 资源在不在容量 1 的端点上：那个站台组、它的具体股道，或名字里带该端点节点的单线桥链（进站单线）。
   *
   * @param resource 冲突资源键
   * @param stubGroups {@link #stubGroups} 的结果
   * @return 在端点上返回 true
   */
  static boolean onStubTerminal(String resource, Set<String> stubGroups) {
    if (resource == null || stubGroups.isEmpty()) {
      return false;
    }
    if (resource.startsWith("platform-group:")) {
      return stubGroups.contains(resource.substring("platform-group:".length()));
    }
    if (resource.startsWith("platform:")) {
      return stubGroups.contains(
          TimetableConflictChecker.groupOf(resource.substring("platform:".length())));
    }
    if (resource.startsWith("single:")) {
      for (String stub : stubGroups) {
        if (resource.contains(stub + ":")) {
          return true;
        }
      }
    }
    return false;
  }

  /** 只要可吸收的那些。 */
  public static List<Residual> absorbable(List<Residual> residuals) {
    return filter(residuals, true);
  }

  /** 只要不可吸收的那些——成功判据、搜索放宽与 {@code --strict} 都只看它。 */
  public static List<Residual> unabsorbable(List<Residual> residuals) {
    return filter(residuals, false);
  }

  private static List<Residual> filter(List<Residual> residuals, boolean absorbable) {
    if (residuals == null || residuals.isEmpty()) {
      return List.of();
    }
    List<Residual> out = new ArrayList<>();
    for (Residual residual : residuals) {
      if (residual != null && residual.absorbable() == absorbable) {
        out.add(residual);
      }
    }
    return List.copyOf(out);
  }

  // ------------------------------------------------------------------ 判据

  private static Residual classifyOne(
      TimetableConflictChecker.Conflict conflict, Context context, int separation, int maxWait) {
    // 1. 邻表：谁先发布谁占路权，我挪不动它。
    if (conflict.external()) {
      return new Residual(conflict, moverOf(conflict), 0, Optional.empty(), 0, Verdict.EXTERNAL);
    }
    // 后车与预计等待：与 ResourceRepair.moveFor 同一口径，内部冲突里后车恒为 second。
    String mover = moverOf(conflict);
    int wait = conflict.firstTo() + separation - conflict.secondFrom();
    wait = Math.max(0, wait);
    Optional<String> waitingPoint = context.waitingPointOf(mover);

    // 2. 容量 1 的端点：后车要在只有一股道的地方等，那才是堵死岔线。
    //
    // 判据先看「后车在哪等」，而不是冲突落在哪个资源上：让车是"整趟延后"，车在自己的起点多站，
    // 并不会开到冲突点去等。冲突落在某站的进站单线上时，后车可能还停在车库里没发车，
    // 晚发一两秒就完事——按资源一刀切会把这种判成不可吸收，白白卡住更密的间隔。
    //
    // 车库（空串）算"知道在哪等"，不算"判不出"：车在库里等不堵任何人。
    // 只有连后车在哪等都判不出来时（相位第三层评估时手上没有成品表），才退回按资源保守判。
    boolean waitsOnStub =
        waitingPoint.isPresent()
            ? context.isStubGroup(waitingPoint.get())
            : context.isStubResource(conflict.resource());
    if (waitsOnStub) {
      return new Residual(conflict, mover, wait, waitingPoint, 0, Verdict.STUB_TERMINAL);
    }
    // 3. 预算：与让车修复同一个上限。
    if (wait > maxWait) {
      return new Residual(conflict, mover, wait, waitingPoint, 0, Verdict.OVER_MAX_WAIT);
    }
    // 4. 让车点有没有空位。
    if (waitingPoint.isPresent() && !context.hasRoom(waitingPoint.get(), conflict.secondFrom())) {
      return new Residual(conflict, mover, wait, waitingPoint, 0, Verdict.NO_WAITING_CAPACITY);
    }
    // 5. 连锁：这一等会顶掉后面几班。
    int depth = context.chainDepthAfter(mover, wait);
    if (depth > MAX_CHAIN_DEPTH) {
      return new Residual(conflict, mover, wait, waitingPoint, depth, Verdict.CHAIN_TOO_DEEP);
    }
    return new Residual(conflict, mover, wait, waitingPoint, depth, Verdict.ABSORBABLE);
  }

  /** 内部冲突的后车恒为 {@code second}：{@link ResourceRepair#moveFor} 只在对方是邻表时才改挪自己。 */
  private static String moverOf(TimetableConflictChecker.Conflict conflict) {
    return conflict.secondOwner().isEmpty() ? conflict.second() : conflict.first();
  }

  // ------------------------------------------------------------------ 上下文

  /**
   * 分类需要的只读索引：全部从成品表、投影、图索引与折返表算出来，不含构建过程的中间状态。
   *
   * @param chainAfter 班次 code → 从它起到交路末班，每班的 {@code [发车（相对计划窗口起点）, 发车到可再发车的秒数]}
   */
  private record Context(
      Map<String, String> waitingPointByCode,
      Map<String, List<int[]>> staysByGroup,
      Map<String, Integer> capacityByGroup,
      Set<String> stubGroups,
      Map<String, List<int[]>> chainAfter) {

    static Context of(
        Timetable timetable,
        TimetableOccupancyProjector.Occupancy occupancy,
        TimetableConflictChecker.GraphIndex index,
        TurnaroundTable turnaround) {
      TurnaroundTable turnarounds = turnaround == null ? TurnaroundTable.none() : turnaround;
      Map<String, Integer> capacity = index == null ? Map.of() : index.platformCapacity();
      Set<String> stubs = ConflictAbsorption.stubGroups(index);

      Map<String, String> waitingPoint = new HashMap<>();
      Map<String, List<int[]>> chain = new HashMap<>();
      if (timetable != null) {
        Map<UUID, TimetableTrip> tripById = new HashMap<>();
        for (TimetableTrip trip : timetable.trips()) {
          tripById.put(trip.id(), trip);
          timetable
              .routePlan(trip.routeId())
              .ifPresent(
                  plan ->
                      waitingPoint.put(
                          trip.tripCode(), TimetableConflictChecker.groupOf(plan.originNodeId())));
        }
        int serviceStart = timetable.serviceStartSecondOfDay();
        for (VehicleDuty duty : timetable.duties()) {
          // 交路里这一班之后的各班：顺推按"到达 + 折返"算，与让车修复沿链传播（delayTrip）同一口径。
          // 发车取相对计划窗口起点的秒数：取模后的当日秒数跨零点会倒回去，前后两班就比反了。
          List<int[]> legs = new ArrayList<>();
          for (UUID tripId : duty.tripIds()) {
            TimetableTrip trip = tripById.get(tripId);
            if (trip == null) {
              legs.add(null);
              continue;
            }
            int run =
                timetable
                    .routePlan(trip.routeId())
                    .map(TimetableRoutePlan::totalRunSeconds)
                    .orElse(0);
            legs.add(
                new int[] {
                  Math.floorMod(
                      trip.departureSecondOfDay() - serviceStart, TimetableTrip.SECONDS_PER_DAY),
                  run + turnarounds.secondsFor(trip.routeId())
                });
          }
          for (int i = 0; i < duty.tripIds().size(); i++) {
            TimetableTrip trip = tripById.get(duty.tripIds().get(i));
            if (trip != null) {
              chain.put(
                  trip.tripCode(),
                  java.util.Collections.unmodifiableList(
                      new ArrayList<>(legs.subList(i, legs.size()))));
            }
          }
          // 出库走行的 code 是「交路号-CREATE」：车还在库里，晚发就是在库里多停一会儿，容量不限（空串）。
          //
          // 待命（code 就是交路号）与回库走行不能这么算：那时车已经停在某个站台上，让车点是那个站台
          // 而不是车库。判不出具体是哪一处就留成"判不出"，退回按资源保守判——容量 1 的端点必须继续
          // 落在不可吸收里，否则运行时那辆车就真的堵死岔线了。
          waitingPoint.put(duty.dutyCode() + "-CREATE", "");
        }
      }

      Map<String, List<int[]>> stays = new HashMap<>();
      if (occupancy != null) {
        for (TimetableConflictChecker.Stay stay : occupancy.stays()) {
          if (stay.platform() != null && !stay.platform().group().isBlank()) {
            stays
                .computeIfAbsent(stay.platform().group(), key -> new ArrayList<>())
                .add(new int[] {stay.from(), stay.to()});
          }
        }
      }
      return new Context(waitingPoint, stays, capacity, stubs, chain);
    }

    /**
     * 只有让车点的上下文：容量与岔线端点仍从图索引来，待命与连锁没有依据，按"没有"算。
     *
     * <p>少了待命就判不出"让车点满没满"，少了交路链就判不出连锁深度——这两条都只会让判决更宽松， 而第三层本来就只用来在几个 δ 之间做相对比较，不是最终的成功判据。
     */
    static Context ofWaitingPoints(
        Map<String, String> waitingPointByCode, TimetableConflictChecker.GraphIndex index) {
      Map<String, Integer> capacity = index == null ? Map.of() : index.platformCapacity();
      Set<String> stubs = ConflictAbsorption.stubGroups(index);
      return new Context(
          waitingPointByCode == null ? Map.of() : Map.copyOf(waitingPointByCode),
          Map.of(),
          capacity,
          stubs,
          Map.of());
    }

    /**
     * 后车要在哪里等。
     *
     * <p>三种答案必须分开：具体站台组；<b>空串是车库</b>（容量不限，一定不是岔线端点）；{@code Optional.empty()}
     * 才是判不出。两者若不区分，每一处后车是待命或出入库走行的残余都会退回按资源判—— 而那正是本判据要避免的误判：车还在车库里没发车，却因为冲突落在进站单线上被判成堵死。
     */
    Optional<String> waitingPointOf(String code) {
      return Optional.ofNullable(waitingPointByCode.get(code));
    }

    /** 容量 1 的站台组。车库（空串）不是站台组，永远不算岔线端点。 */
    boolean isStubGroup(String group) {
      return group != null && !group.isBlank() && stubGroups.contains(group);
    }

    /** 资源本身就在容量 1 的端点上：站台组、具体股道，或名字里带该端点节点的单线桥链。 */
    boolean isStubResource(String resource) {
      return onStubTerminal(resource, stubGroups);
    }

    /** 同一份上下文，不带交路链：顺推判据恒不成立。 */
    Context withoutChain() {
      return new Context(waitingPointByCode, staysByGroup, capacityByGroup, stubGroups, Map.of());
    }

    /** 让车点在这一刻还有没有空位：容量减去那一刻已经在待命的车。 */
    boolean hasRoom(String group, int atSecond) {
      if (group == null || group.isBlank()) {
        // 车库：容量不限，车在库里多待一会儿不占别人的地方。
        return true;
      }
      int capacity = capacityByGroup.getOrDefault(group, 1);
      int occupied = 0;
      for (int[] window : staysByGroup.getOrDefault(group, List.of())) {
        if (window[0] <= atSecond && atSecond < window[1]) {
          occupied++;
        }
      }
      return capacity - occupied >= 1;
    }

    /**
     * 这一班途中等 {@code wait} 秒之后，同一交路里赶不上表定发车的后续班次数。
     *
     * <p>本班晚 {@code wait} 到终点，下一班最早在"本班发车 + 走行 + 折返 + 晚点"发车；表定发车比它早就赶不上， 晚点按差值带给再下一班，直到某一班的余量把它吸收掉。
     */
    int chainDepthAfter(String code, int wait) {
      List<int[]> legs = chainAfter.get(code);
      if (legs == null || legs.size() <= 1 || wait <= 0 || legs.get(0) == null) {
        return 0;
      }
      int depth = 0;
      int late = wait;
      for (int i = 1; i < legs.size(); i++) {
        int[] previous = legs.get(i - 1);
        int[] next = legs.get(i);
        if (next == null) {
          break;
        }
        int ready = previous[0] + previous[1] + late;
        if (next[0] >= ready) {
          break;
        }
        depth++;
        late = ready - next[0];
      }
      return depth;
    }
  }
}
