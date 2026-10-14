package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
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
 *   <li><b>让车点没有容量</b>：让车点无法在等待窗口内停在合格股道上 → 不可吸收。冲突在后车某个途中停车点之后时，让车点是冲突资源之前最后一个停车的车站
 *       （运行时在那一站的发车门控前被挡住，或驶出后在区间信号前等），不再看起点——残余不写进表，车按原时刻离开起点；
 *       冲突在第一个停车点之前时让车点是起点。第二条的"后车要等的地方"也是同一个让车点。途中站只算这段等待新挡住、且会与它互等或交叉的车
 *       （对向、别的路径汇入或交叉、邻表、待命，或这一站是端点、折返站）；本车停固定股道时，只在站台组一层超员、在自己股道上没新增冲突的车
 *       不算被挡；同方向同路径跟在后面的后车只是排队，不算放不下， 它要多等的秒数照样受第三条的上限与第五条的连锁约束（{@link
 *       TimetableWaitingCapacity#room}）。
 *   <li><b>顺推超限</b>：这一等让后续班次赶不上表定时刻（本班到达 + 折返 + 等待 &gt; 下一班发车），而表上没有推后它 → 不可吸收。
 *       途中站上被这一等挡住的同方向后车同样算：它晚到多少，就按它自己的交路链判一遍，取最深的。 写得进去的顺推已由让车修复的必等顺推写进表（{@link
 *       ResourceRepair}），还剩下的就是推不动的——推迟超过 {@code --max-wait} 或会截掉班次。间隔规整优先于多排几班：这种情况判排不开，由搜索放宽间隔。
 *       <p>运行时晚点的车途中站压缩停站（{@code timetable.recovery.min-dwell-seconds}）：顶到下一班的晚点不超过那一班的可追回量 （{@link
 *       Recovery#recoverableSeconds}）时，算在那一班里追回、不顶后面。同一辆车靠追回吸收的秒数全表累计不超过 {@value
 *       #RECOVERABLE_CHAIN_BUDGET_SECONDS} 秒，超出的那几处仍判顺推超限。
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
   * 顺推超限就一次都判不出来——同一处冲突每一圈都在，晚点一圈圈累积直到被作废，表上却看不出来。 写得进去的顺推已经写进表，剩下一班都不放过； 只有在下一班里靠压缩停站追得回、且这辆车的累计没超
   * {@value #RECOVERABLE_CHAIN_BUDGET_SECONDS} 秒的，不算顶掉。
   */
  public static final int MAX_CHAIN_DEPTH = 0;

  /**
   * 同一辆车（同一交路）全表靠运行时追回吸收的顺推秒数上限。
   *
   * <p>按交路累计而不是按处放行：周期表上同一处冲突每一圈都在，每一处单看都追得回，一圈圈叠在同一辆车上就是追不完的晚点。
   * 追回也不是白来的——压缩停站压的是乘客上下车的时间，只留给偶发的小冲突。
   */
  public static final int RECOVERABLE_CHAIN_BUDGET_SECONDS = 30;

  private ConflictAbsorption() {}

  /**
   * 运行时晚点追赶里编表能算进去的那一半：途中站压缩停站。
   *
   * <p>只算停站压缩、不算放宽线路限速：后者要晚点超过 {@code recovery.engage-delay-seconds} 才启用，追回多少取决于线路限速与车型，
   * 编表算不准，保守地不算。压缩只在途中站做：起点就是发车，终到之后是折返（运行时终到停站不压缩），都不算。
   *
   * @param minDwellSeconds 晚点车途中站最少停多少秒，即 {@code timetable.recovery.min-dwell-seconds}；0
   *     表示运行时不压缩停站，可追回量恒为 0
   * @param stopOverheadSeconds 档案里车站停站多算的开销（居中刹停与开门延迟，{@code station-stop-overhead-seconds}）：
   *     压缩从开门起算，这一段压不掉；区间停车点没有这一段，照样扣，只会少算
   */
  public record Recovery(int minDwellSeconds, int stopOverheadSeconds) {

    /** 不按追回量判：顺推一班也不放过。 */
    public static final Recovery NONE = new Recovery(0, 0);

    public Recovery {
      minDwellSeconds = Math.max(0, minDwellSeconds);
      stopOverheadSeconds = Math.max(0, stopOverheadSeconds);
    }

    /** 运行时会不会压缩停站。 */
    public boolean enabled() {
      return minDwellSeconds > 0;
    }

    /**
     * 这条 route 跑一趟最多能追回多少秒：各途中停车点 {@code max(0, 表定停站 − 开销 − 最短停站)} 之和。
     *
     * <p>表定停站取档案的到发之差，与编表同一口径：喂车多停、换模加时都在里面。多停的部分运行时晚点车本来就不等，算作可追回；
     * 换模加时运行时不压，这里会多算——换模站极少，交给累计上限兜底。
     *
     * @param plan route 的时分档案
     * @return 秒数；不压缩停站时为 0
     */
    public int recoverableSeconds(TimetableRoutePlan plan) {
      if (!enabled() || plan == null || plan.stops().isEmpty()) {
        return 0;
      }
      OptionalInt terminating = plan.terminatingSequence();
      if (terminating.isEmpty()) {
        return 0;
      }
      int origin = plan.stops().get(0).stopSequence();
      int total = 0;
      for (TimetableStop stop : plan.stops()) {
        if (stop.stopSequence() <= origin || !stop.stops()) {
          continue;
        }
        if (stop.stopSequence() >= terminating.getAsInt()) {
          break;
        }
        int dwell = stop.departureOffsetSeconds() - stop.arrivalOffsetSeconds();
        total += Math.max(0, dwell - stopOverheadSeconds - minDwellSeconds);
      }
      return total;
    }
  }

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
    /** 始发等待窗口内没有合格的空位。 */
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
   * @param occupancy 最终表及邻表的完整投影：检查整个始发等待窗口，按车辆身份去重
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
    return classify(
        report,
        timetable,
        occupancy,
        index,
        separationSeconds,
        maxWaitSeconds,
        turnaround,
        Map.of());
  }

  /**
   * 带完整 route 档案的分类：编表与发布重检必须使用它。
   *
   * @param profiles 与占用投影一致的全部档案，包含车型变体与邻表；保留 DYNAMIC 股道资格和途中停站占用
   */
  public static List<Residual> classify(
      TimetableConflictChecker.Report report,
      Timetable timetable,
      TimetableOccupancyProjector.Occupancy occupancy,
      TimetableConflictChecker.GraphIndex index,
      int separationSeconds,
      int maxWaitSeconds,
      TurnaroundTable turnaround,
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles) {
    return classify(
        report,
        timetable,
        occupancy,
        index,
        separationSeconds,
        maxWaitSeconds,
        turnaround,
        profiles,
        Recovery.NONE);
  }

  /**
   * 同上，顺推按运行时可追回量判（判据第五条）：编表必须使用它，传的是运行时的晚点追赶参数。
   *
   * @param recovery 运行时的停站压缩；{@link Recovery#NONE} 时顺推一班也不放过
   */
  public static List<Residual> classify(
      TimetableConflictChecker.Report report,
      Timetable timetable,
      TimetableOccupancyProjector.Occupancy occupancy,
      TimetableConflictChecker.GraphIndex index,
      int separationSeconds,
      int maxWaitSeconds,
      TurnaroundTable turnaround,
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
      Recovery recovery) {
    if (report == null || report.conflicts().isEmpty()) {
      return List.of();
    }
    return classify(
        Context.of(timetable, occupancy, index, turnaround, profiles, recovery),
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
    return classify(
        report, waitingPointByCode, List.of(), Map.of(), index, separationSeconds, maxWaitSeconds);
  }

  /**
   * 同上，再给铺开的运行与档案：途中让车点（冲突之前最后一个停车站）因此与成品表同一口径认得出来。
   *
   * <p>相位第三层必须走这条：只给始发站台组的话，冲突在途中停车点之后的那些仍按起点判，与成功判据的结论分叉。
   *
   * @param movements 铺开的运行，与 {@code report} 出自同一次检查
   * @param profiles 这些运行的档案
   */
  public static List<Residual> classify(
      TimetableConflictChecker.Report report,
      Map<String, String> waitingPointByCode,
      List<TimetableConflictChecker.Movement> movements,
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
      TimetableConflictChecker.GraphIndex index,
      int separationSeconds,
      int maxWaitSeconds) {
    if (report == null || report.conflicts().isEmpty()) {
      return List.of();
    }
    return classify(
        Context.ofWaitingPoints(waitingPointByCode, movements, profiles, index),
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
      int maxWaitSeconds,
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles) {
    if (conflicts == null || conflicts.isEmpty()) {
      return List.of();
    }
    return classify(
        Context.of(timetable, occupancy, index, TurnaroundTable.none(), profiles, Recovery.NONE)
            .withoutChain(),
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
    // 累计上限按报告顺序（CONFLICT_ORDER，全序）逐处扣：与遍历、线程无关，build 与重检扣出同一结果。
    Map<String, Integer> recoveredByDuty = new HashMap<>();
    for (TimetableConflictChecker.Conflict conflict : report.conflicts()) {
      out.add(classifyOne(conflict, context, separation, maxWait, recoveredByDuty));
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
      TimetableConflictChecker.Conflict conflict,
      Context context,
      int separation,
      int maxWait,
      Map<String, Integer> recoveredByDuty) {
    // 1. 邻表：谁先发布谁占路权，我挪不动它。
    if (conflict.external()) {
      return new Residual(conflict, moverOf(conflict), 0, Optional.empty(), 0, Verdict.EXTERNAL);
    }
    // 后车与预计等待：与 ResourceRepair.moveFor 同一口径，内部冲突里后车恒为 second。
    String mover = moverOf(conflict);
    int wait = conflict.firstTo() + separation - conflict.secondFrom();
    wait = Math.max(0, wait);
    Optional<String> origin = context.waitingPointOf(mover);
    // 后车在哪等：冲突在它某个途中停车点之后时，运行时它在那一站的发车门控前被挡住（或驶出后在区间信号前等），
    // 不会回到起点去等，让车点就是那一站。这时不再看起点：残余不写进表（只顺推后续班次，这一班自身仍按原时刻），
    // 运行时也不读让车点——车准点离开起点、到这一站被挡，起点有空位兑现不了。中间别的车站同理不用。
    // 冲突在第一个停车点之前时让车点是起点。
    Optional<TimetableWaitingCapacity.EnRoute> enRoute =
        context.enRoute(mover, conflict.secondFrom());
    Optional<String> waitingPoint =
        enRoute.isPresent() ? Optional.of(enRoute.get().group()) : origin;

    // 2. 容量 1 的端点：后车要在只有一股道的地方等，那才是堵死岔线。
    //
    // 判据先看「后车在哪等」，而不是冲突落在哪个资源上：让车是"整趟延后"，车在自己的起点多站，
    // 并不会开到冲突点去等。冲突落在某站的进站单线上时，后车可能还停在车库里没发车，
    // 晚发一两秒就完事——按资源一刀切会把这种判成不可吸收，白白卡住更密的间隔。
    //
    // 车库（空串）算"知道在哪等"，不算"判不出"：车在库里等不堵任何人。
    // 只有连后车在哪等都判不出来时（相位第三层评估时手上没有成品表），才退回按资源保守判。
    boolean onStub =
        waitingPoint.isPresent()
            ? context.isStubGroup(waitingPoint.get())
            : context.isStubResource(conflict.resource());
    if (onStub) {
      return new Residual(conflict, mover, wait, waitingPoint, 0, Verdict.STUB_TERMINAL);
    }
    // 3. 预算：与让车修复同一个上限。
    if (wait > maxWait) {
      return new Residual(conflict, mover, wait, waitingPoint, 0, Verdict.OVER_MAX_WAIT);
    }
    // 4. 让车点有没有空位。途中站上被这一等挡住的同方向后车不算放不下（它在后面排队），它要多等的交给第 3、5 条。
    Map<String, Integer> followers = Map.of();
    if (enRoute.isPresent()) {
      TimetableWaitingCapacity.EnRouteRoom room =
          context.waitingCapacity().room(mover, enRoute.get(), wait, separation);
      if (!room.fits()) {
        return new Residual(conflict, mover, wait, waitingPoint, 0, Verdict.NO_WAITING_CAPACITY);
      }
      followers = room.followers();
    } else if (origin.isPresent()
        && !context.hasRoom(
            origin.get(),
            mover,
            context.departureByCode().getOrDefault(mover, conflict.secondFrom()),
            wait,
            separation)) {
      return new Residual(conflict, mover, wait, waitingPoint, 0, Verdict.NO_WAITING_CAPACITY);
    }
    for (int followerWait : followers.values()) {
      if (followerWait > maxWait) {
        return new Residual(conflict, mover, wait, waitingPoint, 0, Verdict.OVER_MAX_WAIT);
      }
    }
    // 5. 连锁：这一等会顶掉后面几班；被它挡住的后车各自晚到，也顶得掉它们交路里的后面几班，取最深的。
    // 顶到下一班的晚点在那一班里追得回来就不算顶掉，追回的秒数记到那辆车的交路上，全表累计有上限。
    Map<String, Integer> delays = new TreeMap<>(followers);
    delays.merge(mover, wait, Math::max);
    int depth = 0;
    Map<String, Integer> charge = new TreeMap<>();
    for (Map.Entry<String, Integer> delay : delays.entrySet()) {
      Chain chain = context.chainAfter(delay.getKey(), delay.getValue(), true);
      depth = Math.max(depth, chain.depth());
      if (chain.recovered() > 0) {
        charge.merge(context.dutyOf(delay.getKey()), chain.recovered(), Integer::sum);
      }
    }
    if (depth > MAX_CHAIN_DEPTH) {
      return new Residual(conflict, mover, wait, waitingPoint, depth, Verdict.CHAIN_TOO_DEEP);
    }
    for (Map.Entry<String, Integer> duty : charge.entrySet()) {
      if (recoveredByDuty.getOrDefault(duty.getKey(), 0) + duty.getValue()
          > RECOVERABLE_CHAIN_BUDGET_SECONDS) {
        // 累计用完：这一处不再靠追回，按不追回的链报深度（至少顶掉下一班）。
        int unrecovered = 0;
        for (Map.Entry<String, Integer> delay : delays.entrySet()) {
          unrecovered =
              Math.max(
                  unrecovered, context.chainAfter(delay.getKey(), delay.getValue(), false).depth());
        }
        return new Residual(
            conflict, mover, wait, waitingPoint, unrecovered, Verdict.CHAIN_TOO_DEEP);
      }
    }
    charge.forEach((duty, seconds) -> recoveredByDuty.merge(duty, seconds, Integer::sum));
    return new Residual(conflict, mover, wait, waitingPoint, depth, Verdict.ABSORBABLE);
  }

  /**
   * 一处等待沿交路链往后推的结果。
   *
   * @param depth 赶不上表定发车、也追不回来的后续班次数
   * @param recovered 在下一班里靠压缩停站追回的晚点秒数；没有顶到下一班、或追不回时为 0
   */
  private record Chain(int depth, int recovered) {
    static final Chain NONE = new Chain(0, 0);
  }

  /** 内部冲突的后车恒为 {@code second}：{@link ResourceRepair#moveFor} 只在对方是邻表时才改挪自己。 */
  private static String moverOf(TimetableConflictChecker.Conflict conflict) {
    return conflict.secondOwner().isEmpty() ? conflict.second() : conflict.first();
  }

  // ------------------------------------------------------------------ 上下文

  /**
   * 分类需要的只读索引：全部从成品表、投影、图索引与折返表算出来，不含构建过程的中间状态。
   *
   * @param departureByCode 班次的实际始发时刻，相对计划窗口起点；禁止用远处冲突时刻替代
   * @param waitingCapacity 整段等待的物理股道、同车与邻表占用检查
   * @param chainAfter 班次 code → 从它起到交路末班，每班的 {@code [发车（相对计划窗口起点）, 发车到可再发车的秒数, 可追回秒数]}
   * @param dutyByCode 班次 code → 交路号：追回的累计上限按交路记
   */
  private record Context(
      Map<String, String> waitingPointByCode,
      Map<String, Integer> departureByCode,
      TimetableWaitingCapacity waitingCapacity,
      Set<String> stubGroups,
      Map<String, List<int[]>> chainAfter,
      Map<String, String> dutyByCode) {

    static Context of(
        Timetable timetable,
        TimetableOccupancyProjector.Occupancy occupancy,
        TimetableConflictChecker.GraphIndex index,
        TurnaroundTable turnaround,
        Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
        Recovery recovery) {
      TurnaroundTable turnarounds = turnaround == null ? TurnaroundTable.none() : turnaround;
      Recovery recovering = recovery == null ? Recovery.NONE : recovery;
      Set<String> stubs = ConflictAbsorption.stubGroups(index);

      Map<String, String> waitingPoint = new HashMap<>();
      Map<String, Integer> departures = new HashMap<>();
      Map<String, List<int[]>> chain = new HashMap<>();
      Map<String, String> dutyByCode = new HashMap<>();
      if (timetable != null) {
        Map<UUID, TimetableTrip> tripById = new HashMap<>();
        for (TimetableTrip trip : timetable.trips()) {
          tripById.put(trip.id(), trip);
          departures.put(
              trip.tripCode(),
              TimetableOccupancyProjector.relativeDeparture(
                  timetable, trip, timetable.serviceStartSecondOfDay()));
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
            Optional<TimetableRoutePlan> plan = timetable.routePlan(trip.routeId());
            legs.add(
                new int[] {
                  Math.floorMod(
                      trip.departureSecondOfDay() - serviceStart, TimetableTrip.SECONDS_PER_DAY),
                  plan.map(TimetableRoutePlan::totalRunSeconds).orElse(0)
                      + turnarounds.secondsFor(trip.routeId()),
                  plan.map(recovering::recoverableSeconds).orElse(0)
                });
          }
          for (int i = 0; i < duty.tripIds().size(); i++) {
            TimetableTrip trip = tripById.get(duty.tripIds().get(i));
            if (trip != null) {
              dutyByCode.put(trip.tripCode(), duty.dutyCode());
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

      return new Context(
          waitingPoint,
          departures,
          TimetableWaitingCapacity.of(timetable, occupancy, index, profiles),
          stubs,
          chain,
          dutyByCode);
    }

    /**
     * 只有让车点与铺开运行的上下文：容量与岔线端点仍从图索引来，途中让车点从运行与档案来，待命与连锁没有依据，按"没有"算。
     *
     * <p>少了待命就判不出"让车点满没满"，少了交路链就判不出连锁深度——这两条都只会让判决更宽松， 而第三层本来就只用来在几个 δ 之间做相对比较，不是最终的成功判据。
     * 让车点本身（起点还是途中停车站、是不是容量 1 的端点）必须与成功判据一致，所以运行照样建。
     */
    static Context ofWaitingPoints(
        Map<String, String> waitingPointByCode,
        List<TimetableConflictChecker.Movement> movements,
        Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
        TimetableConflictChecker.GraphIndex index) {
      Set<String> stubs = ConflictAbsorption.stubGroups(index);
      return new Context(
          waitingPointByCode == null ? Map.of() : Map.copyOf(waitingPointByCode),
          Map.of(),
          TimetableWaitingCapacity.ofRuns(movements, profiles, index),
          stubs,
          Map.of(),
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
      return new Context(
          waitingPointByCode, departureByCode, waitingCapacity, stubGroups, Map.of(), Map.of());
    }

    /** 后车在冲突之前最后一个途中停车点；冲突在第一个停车点之前时为空。 */
    Optional<TimetableWaitingCapacity.EnRoute> enRoute(String code, int enterSecond) {
      return waitingCapacity.enRoute(code, enterSecond);
    }

    /** 始发等待使用完整资源模型；远处冲突时刻不用于始发站容量判定。 */
    boolean hasRoom(String group, String mover, int atSecond, int waitSeconds, int separation) {
      return waitingCapacity.hasRoom(mover, group, atSecond, waitSeconds, separation);
    }

    /** 班次所在交路的交路号；不在任何交路里时用 code 自己（各记各的）。 */
    String dutyOf(String code) {
      return dutyByCode.getOrDefault(code, code);
    }

    /**
     * 这一班途中等 {@code wait} 秒之后，同一交路里赶不上表定发车、也追不回来的后续班次数。
     *
     * <p>本班晚 {@code wait} 到终点，下一班最早在"本班发车 + 走行 + 折返 + 晚点"发车，表定发车比它早就晚发。晚发不超过那一班的可追回量时，
     * 车在那一班的途中站压缩停站追回来，链到此为止；追不回就算顶掉一班，那一班到终点仍晚"晚发 − 可追回量"，带给再下一班， 直到某一班的余量或追回把它吸收掉。
     *
     * @param recover 是否按可追回量追回；累计上限用完时不追回
     */
    Chain chainAfter(String code, int wait, boolean recover) {
      List<int[]> legs = chainAfter.get(code);
      if (legs == null || legs.size() <= 1 || wait <= 0 || legs.get(0) == null) {
        return Chain.NONE;
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
        int behind = ready - next[0];
        int recoverable = recover ? next[2] : 0;
        if (behind <= recoverable) {
          return new Chain(depth, depth == 0 ? behind : 0);
        }
        depth++;
        late = behind - recoverable;
      }
      return new Chain(depth, 0);
    }
  }
}
