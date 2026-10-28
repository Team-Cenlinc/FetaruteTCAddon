package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.RunTimeModel;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLifecycleMode;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteTerminals;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnDirectiveParser;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.scope.NeighborTimetable;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.scope.TimetableBaseline;

/**
 * 从运行网络生成时刻表。
 *
 * <p>输入是 route 定义、调度图与限速、运营参数；<b>没有任何历史跑车记录</b>。同一份网络状态 + 同一份配置，
 * 永远得到同一张表——这是可以直接写成测试的性质，也是"时刻表是造出来的"这条不变量的操作化定义。
 *
 * <p>五步，各自只做一件事：
 *
 * <ol>
 *   <li><b>算时分</b>（{@link TimetableTimingCalculator}）：每条 route 按实际穿越的区段限速积分出站间时分与逐边时分。 算不出来的 route
 *       直接标为不可行并说明原因，绝不用一个全线平均速度糊过去。CREATE/RETURN 线路同样在这里算， 它们给出车辆交路两端的出库/回库走行时分。
 *   <li><b>周期层求解</b>（{@link PeriodicSolver}）：在超周期的环上给每个方向定相位、给途中车站定多停，使运营班次在稳态里不超任何资源的容量；
 *       快车被卡是目标项，慢车可在待避站多停让快车越行。
 *   <li><b>排班次</b>（{@link WeightedTripAllocator}）：按相位与组间隔铺规整子网格，同方向多 route 按 weight 做 smooth
 *       weighted round-robin。weight 是目标比例，不是抽签。
 *   <li><b>派车</b>（{@link VehicleDutyPlanner}）：在<b>已经排定</b>的班次上指派车辆， 每个 duty 都被硬上限夹住、从有 CREATE
 *       线路的起点开、在有 RETURN 线路的终点收。
 *   <li><b>落位与查冲突</b>：预订表（{@link ReservationScheduler}）把运营班次按名义时刻落到资源上（只延后），出入库走行插进空档； 再由 {@link
 *       TimetableConflictChecker} 与 {@link ConflictAbsorption} 判剩下的冲突运行时能不能让。目标间隔排不开时，非严格模式
 *       先只问周期层找可行间隔再完整构建确认，严格模式在已选相位上取消让不掉的班次。
 * </ol>
 *
 * <p>排班次、派车的顺序不可交换。特别是派车绝不能反过来影响排班次：一旦允许"某终点恰好停着车，所以多发这条线"， 车辆周转就会扭曲服务比例，而那种扭曲在运营上是看不见的。
 * 派车唯一能对班次做的是<b>减法</b>：起点没有出库途径、终点没有回库途径的班次被取消并逐条报告——那是物理约束，不是周转偏好。
 *
 * <h2>线路级校验</h2>
 *
 * <p>一条线路必须至少有一条出库途径和一条回库途径，否则整份表发不出车或者回不了库，直接构建失败：
 *
 * <ul>
 *   <li>出库途径：一条能算出时分的 CREATE 线路，或一条首站带 CRET 指令（从车库始发）的 OPERATION 线路。
 *   <li>回库途径：一条能算出时分的 RETURN 线路，或一条以销毁收尾（DSTY）的 OPERATION 线路。
 * </ul>
 */
public final class TimetableBuilder {

  /** 目标与实际份额偏差超过这个百分点就明确警告。 */
  public static final double SHARE_WARN_PERCENT_POINTS = 5.0D;

  /**
   * 非严格模式搜索可行间隔时最多放宽到目标的多少倍。放得再宽的表间隔面目全非，没有发布的意义；超周期也随之变长，每问一次周期层都更慢。 放宽到这里还排不开就照实报失败，由调整目标间隔或
   * {@code --strict} 取消来解决。
   */
  public static final int HEADWAY_SEARCH_MAX_MULTIPLIER = 2;

  private final TimetableTimingCalculator timingCalculator;

  /** 进度：阶段与完整构建次数。 */
  private final TimetableBuildProgress progress;

  /** 编表线程数（{@code timetable.build-threads} 换算后）；1 为串行。 */
  private final int threads;

  /** 这次 build 的并行池；为空时 {@link #build} 现开一个、编完关掉。 */
  private final BuildPool pool;

  public TimetableBuilder() {
    this(TimetableBuildProgress.untracked());
  }

  /** 串行编表：测试与不关心线程数的调用方用，不起线程。 */
  public TimetableBuilder(TimetableBuildProgress progress) {
    this(progress, 1);
  }

  /**
   * @param progress 进度
   * @param threads 编表线程数；1 为串行。结果与线程数无关，线程数只影响快慢
   */
  public TimetableBuilder(TimetableBuildProgress progress, int threads) {
    this(new TimetableTimingCalculator(), progress, threads, null);
  }

  public TimetableBuilder(TimetableTimingCalculator timingCalculator) {
    this(timingCalculator, TimetableBuildProgress.untracked(), 1, null);
  }

  private TimetableBuilder(
      TimetableTimingCalculator timingCalculator,
      TimetableBuildProgress progress,
      int threads,
      BuildPool pool) {
    this.timingCalculator = Objects.requireNonNull(timingCalculator, "timingCalculator");
    this.progress = Objects.requireNonNull(progress, "progress");
    this.threads = Math.max(1, threads);
    this.pool = pool;
  }

  /**
   * 构建时刻表：补上车型车长、编表，再在成品表上量快车被卡（只报告）。
   *
   * @param input 归属信息、route 集合与路网
   * @param requested 运营参数
   * @param now 构建时间
   * @return 构建结果
   */
  public TimetableBuildResult build(
      BuildInput input, TimetableBuildOptions requested, Instant now) {
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(requested, "requested");
    if (pool == null) {
      // 一次 build 一个池：互不依赖的完整构建在里面并行编，编完关掉，不留线程也不留整表。
      try (BuildPool opened = BuildPool.open(threads)) {
        return new TimetableBuilder(timingCalculator, progress, threads, opened)
            .build(input, requested, now);
      }
    }
    TimetableBuildOptions options = withConsistLengths(input, requested);
    Built base = buildOnce(input, options, now == null ? Instant.now() : now);
    if (base.chosen().isEmpty()) {
      return collapsed(base.result());
    }
    // 快车被卡已是周期层的目标项（并允许慢车待避越行），这里只报成品表的实测。
    Set<UUID> fastRoutes = fastRoutesOf(base);
    RapidCatchUp.Measure measure =
        measureRapids(base, fastRoutes, TimetableBuildOptions.Following.NONE);
    RapidCatchUp.Measure reported = reportedRapids(base, fastRoutes, measure);
    return collapsed(
        RapidCatchUp.annotate(base.result(), reported, routeCodes(base.chosen().get())));
  }

  /** 快车交路：按基础 route 判定；区分车型时班次挂的是变体，变体随它的基础 route 算快车。 */
  private static Set<UUID> fastRoutesOf(Built base) {
    Prepared chosen = base.chosen().orElseThrow();
    return withVariants(
        RapidCatchUp.fastRoutes(
            chosen.classification().groups(),
            chosen.profiles(),
            chosen.graphIndex(),
            separationOf(base.options())),
        chosen);
  }

  /** 快车型被卡：同一段共线上，快的车型跟在慢的车型后面追上前车（与快车被卡同一套实测，{@link CorridorCatchUp}）。只报告，不改表； 不区分车型或没有快慢之分时为空。 */
  private static Optional<String> consistCatchUp(
      Prepared prepared, Timetable table, List<TripYield> yields, TimetableBuildOptions options) {
    if (prepared.variants().isEmpty()) {
      return Optional.empty();
    }
    Set<UUID> variantIds = new TreeSet<>();
    for (TimetableRoutePlan plan : prepared.operationPlans()) {
      variantIds.addAll(prepared.variants().getOrDefault(plan.routeId(), Map.of()).values());
    }
    int separation = separationOf(options);
    Set<UUID> faster =
        new CorridorCatchUp(prepared.profiles(), prepared.graphIndex(), separation)
            .fasterRoutes(variantIds);
    if (faster.isEmpty()) {
      return Optional.empty();
    }
    RapidCatchUp.Measure measure =
        RapidCatchUp.measure(
            table,
            prepared.profiles(),
            prepared.graphIndex(),
            separation,
            options.following(),
            prepared.trajectories(),
            options.serviceStartSecondOfDay(),
            faster,
            yields);
    return Optional.of(
        "快车型被卡（成品表实测）："
            + (measure.trips() == 0 ? "无" : measure.trips() + " 班、共 " + measure.seconds() + " s")
            + (measure.held() > 0 ? "；表里另有快车型让车等待共 " + measure.held() + " s" : ""));
  }

  /**
   * 跟车规则的车长按车型补上：车型变体用自己车型的车长，基础 route 取允许车型里最长的（保守）。不区分车型、或跟车规则未启用时原样返回。
   *
   * <p>命令层只知道出车编组（车库牌子、route 的编组写法）的车长；区分车型时出的是方案里的车型，车长以车型档案为准。
   */
  private static TimetableBuildOptions withConsistLengths(
      BuildInput input, TimetableBuildOptions options) {
    ConsistFleet fleet = input.fleet();
    if (!fleet.active() || !options.following().enabled()) {
      return options;
    }
    Map<UUID, Long> lengths = new HashMap<>(options.following().trainLengthBlocks());
    for (RouteInput route : input.routes()) {
      long longest = 0L;
      for (ConsistFleet.Share share : fleet.sharesFor(route.routeId())) {
        double blocks = fleet.consists().get(share.key()).lengthBlocks();
        if (blocks <= 0.0D) {
          continue;
        }
        long length = (long) Math.ceil(blocks);
        lengths.put(ConsistFleet.variantId(route.routeId(), share.key()), length);
        longest = Math.max(longest, length);
      }
      if (longest > 0L) {
        lengths.put(route.routeId(), longest);
      }
    }
    return options.withFollowing(options.following().withTrainLengths(lengths));
  }

  /** 编表内部的车型变体折回基础 route（{@link ConsistVariants#collapse}）；不区分车型时原样返回。 */
  private static TimetableBuildResult collapsed(TimetableBuildResult result) {
    return result.timetable().isEmpty()
        ? result
        : result.withTimetable(result.timetable().map(ConsistVariants::collapse));
  }

  /** 基础 route 集合加上它们的车型变体。 */
  private static Set<UUID> withVariants(Set<UUID> routes, Prepared prepared) {
    if (prepared.variants().isEmpty()) {
      return routes;
    }
    Set<UUID> out = new HashSet<>(routes);
    for (UUID routeId : routes) {
      out.addAll(prepared.variants().getOrDefault(routeId, Map.of()).values());
    }
    return Set.copyOf(out);
  }

  /**
   * 量一次构建的成品表（用选中那份表自己的准备：多停改过的停站也算在内）。
   *
   * @param following 跟车规则；未启用时按占用区间量
   */
  private static RapidCatchUp.Measure measureRapids(
      Built built, Set<UUID> fastRoutes, TimetableBuildOptions.Following following) {
    Prepared chosen = built.chosen().orElseThrow();
    return RapidCatchUp.measure(
        built.result().timetable().orElseThrow(),
        chosen.profiles(),
        chosen.graphIndex(),
        separationOf(built.options()),
        following,
        chosen.trajectories(),
        built.options().serviceStartSecondOfDay(),
        fastRoutes,
        built.result().yields());
  }

  /** 报告口径：有跟车规则时按闭塞时间再量一遍，没有时就是搜索口径那一份。 */
  private static RapidCatchUp.Measure reportedRapids(
      Built built, Set<UUID> fastRoutes, RapidCatchUp.Measure searched) {
    TimetableBuildOptions.Following following = built.options().following();
    return following.enabled() ? measureRapids(built, fastRoutes, following) : searched;
  }

  /**
   * 一次构建的产物。
   *
   * @param result 构建结果
   * @param prepared 第 1 步的准备（目标间隔、不多停）；准备阶段就失败时为空
   * @param chosen 选中那份表用的准备（周期层给了多停时是改过停站的一份）；没排出表时为空
   * @param options 补上折返表之后的参数
   */
  private record Built(
      TimetableBuildResult result,
      Optional<Prepared> prepared,
      Optional<Prepared> chosen,
      TimetableBuildOptions options) {

    static Built failed(TimetableBuildResult result, TimetableBuildOptions options) {
      return new Built(result, Optional.empty(), Optional.empty(), options);
    }
  }

  /** 构建一次：算时分、周期层定相位与多停、按目标间隔完整排一次；排不开时严格模式取消、非严格模式放宽，汇总。 */
  private Built buildOnce(BuildInput input, TimetableBuildOptions requested, Instant builtAt) {
    // 折返时间不是常数：没有 --turnaround 覆盖时按各 route 终到停靠点的 dwell 算（唯一来源是 route 定义）。
    TimetableBuildOptions options = requested.withTurnaround(resolveTurnarounds(input, requested));

    // ---- 1. 算时分（与 headway 无关，只做一次） ------------------------------
    // 不可行 route 的清单由这里持有：prepare 中途失败时它已经有内容，失败结果要把它带出去。
    List<TimetableBuildResult.InfeasibleRoute> infeasible = new ArrayList<>();
    Prepared prepared;
    stage(TimetableBuildProgress.Stage.PREPARE);
    try {
      prepared = prepare(input, options, infeasible, null);
    } catch (BuildFailure failure) {
      return Built.failed(
          TimetableBuildResult.failure(failure.getMessage(), List.copyOf(infeasible)), options);
    }

    // ---- 2–4. 周期层定相位与多停，按目标间隔完整排一次 --------------------------
    stage(TimetableBuildProgress.Stage.TARGET);
    Solved solved;
    Attempt target;
    // 分时段时有两种定多停的策略（见 solveRegimes），各完整排一次（并行编），按原顺序取完整构建结果更好的那份；一份失败不连累另一份。
    Solved best = null;
    Attempt bestAttempt = null;
    String firstFailure = null;
    for (BuildPool.Evaluated<RegimeTry> evaluated :
        pool.evaluate(
            solveRegimes(prepared, input, options),
            candidate -> tryRegime(candidate, options, builtAt))) {
      RegimeTry tried = evaluated.get();
      if (tried.attempt() == null) {
        firstFailure = firstFailure == null ? tried.failure() : firstFailure;
        continue;
      }
      if (bestAttempt == null || REGIME_ORDER.compare(tried.attempt(), bestAttempt) < 0) {
        best = tried.solved();
        bestAttempt = tried.attempt();
      }
    }
    if (bestAttempt == null) {
      return new Built(
          TimetableBuildResult.failure(
              Objects.requireNonNullElse(firstFailure, "周期层没有给出相位"), prepared.infeasible()),
          Optional.of(prepared),
          Optional.empty(),
          options);
    }
    solved = best;
    target = bestAttempt;
    // 报告以"最小的组间隔"为标量；放宽时各组等比放宽，再逐组收紧。
    int targetHeadway = target.headwaySeconds();

    Attempt chosen = target;
    // 选中的那份准备：周期层给了多停时是改过停站、重算过时分的一份。
    Prepared chosenPrepared = solved.prepared();
    List<String> warnings = new ArrayList<>();
    // 只有"运行时也让不掉"的残余才算目标间隔不可行；可吸收的那些照常发布。
    if (!target.clean()) {
      String summary =
          TimetableBuildReportText.summarizeUnabsorbable(target.unabsorbable(), targetHeadway);
      if (options.strictConflicts()) {
        // 严格模式不放宽间隔：在已选相位上取消让不掉的班次，至多 --max-cancel 班（0 不取消；没给时按运营班次的比例）。
        int cancelLimit = options.maxCancelTripsFor(target.slotByCode().size());
        String cancelLimitText = cancelLimitText(options, cancelLimit, target.slotByCode().size());
        Optional<Cancellation> cancellation =
            cancelLimit > 0
                ? Optional.of(
                    cancelUnabsorbable(
                        solved.prepared(), options, solved.input(), builtAt, target, cancelLimit))
                : Optional.empty();
        boolean cancelledClean =
            cancellation.isPresent()
                && cancellation.get().attempt().clean()
                && cancellation.get().count() <= cancelLimit;
        if (!cancelledClean) {
          List<String> reasons = new ArrayList<>();
          reasons.add(
              summary
                  + "；严格模式下不放宽间隔"
                  + cancellation
                      .map(
                          done ->
                              done.attempt().clean()
                                  ? "，取消 " + done.countText() + "才排开，超过上限（" + cancelLimitText + "）"
                                  : "，取消 "
                                      + done.countText()
                                      + "后仍有 "
                                      + done.attempt().unabsorbable().size()
                                      + " 处（"
                                      + cancelLimitText
                                      + "）")
                      .orElse(""));
          cancellation.ifPresent(
              done -> reasons.addAll(describeCancelled(done.cancelled(), prepared, options)));
          // 失败结果里的冲突与残余保持"目标间隔原表"的口径；取消之后还剩的只写进文案。
          Optional<Attempt> afterCancel =
              cancellation.map(Cancellation::attempt).filter(after -> !after.clean());
          if (afterCancel.isPresent()) {
            reasons.add("取消之后仍让不掉的：");
          }
          reasons.addAll(
              TimetableBuildReportText.describeUnabsorbable(
                  afterCancel.orElse(target).unabsorbable(), options.serviceStartSecondOfDay()));
          separationHint(prepared, input, options, builtAt).ifPresent(hint -> reasons.add(1, hint));
          return new Built(
              targetFailure(String.join("\n", reasons), prepared, target),
              Optional.of(prepared),
              Optional.empty(),
              options);
        }
        Cancellation done = cancellation.orElseThrow();
        chosen = done.attempt();
        warnings.add(
            summary
                + "，间隔不变（严格模式不放宽间隔，取消 "
                + done.countText()
                + "让不掉的班次，"
                + cancelLimitText
                + "）：");
        warnings.addAll(describeCancelled(done.cancelled(), prepared, options));
      } else {
        // 运行计划书写了时段时间隔是计划书定的，不放宽：排不开的照实报出来，由调整计划书或 --strict 取消来解决。
        // 只写了定点的计划书不定间隔，照常放宽。
        boolean bandsFixIntervals = !options.schedule().bandsByGroup().isEmpty();
        Optional<Relaxed> relaxed =
            bandsFixIntervals ? Optional.empty() : relax(prepared, input, options, builtAt, target);
        if (relaxed.isEmpty()) {
          TimetableConflictChecker.Report atTarget =
              new TimetableConflictChecker.Report(
                  target.unabsorbable().stream()
                      .map(ConflictAbsorption.Residual::conflict)
                      .toList());
          List<String> reasons = new ArrayList<>();
          reasons.add(
              summary
                  + "；"
                  + (bandsFixIntervals
                      ? TimetableBuildReportText.describePlannedIntervalsFailure(atTarget)
                      : TimetableBuildReportText.describeSearchFailure(
                          targetHeadway,
                          targetHeadway * HEADWAY_SEARCH_MAX_MULTIPLIER,
                          RELAX_SOLVE_LIMIT,
                          atTarget)));
          reasons.addAll(
              TimetableBuildReportText.describeUnabsorbable(
                  target.unabsorbable(), options.serviceStartSecondOfDay()));
          separationHint(prepared, input, options, builtAt).ifPresent(hint -> reasons.add(1, hint));
          return new Built(
              targetFailure(String.join("\n", reasons), prepared, target),
              Optional.of(prepared),
              Optional.empty(),
              options);
        }
        chosenPrepared = relaxed.get().prepared();
        chosen = relaxed.get().attempt();
        warnings.addAll(relaxed.get().notes());
        String outcome =
            widenedIntervals(
                    target.intervals(),
                    target.headwaySeconds(),
                    chosen.intervals(),
                    chosen.headwaySeconds())
                .map(widened -> "，已回退到搜索找到的可行间隔 " + widened)
                .orElse("，间隔不变");
        warnings.add(
            summary
                + outcome
                + "（--strict 可改为构建失败）"
                + (target.conflicts().external().isEmpty()
                    ? ""
                    : "；其中 " + target.conflicts().external().size() + " 处是与已发布邻表的冲突，只能挪自己"));
      }
      warnings.addAll(
          TimetableBuildReportText.describeConflicts(
              target.conflicts(), options.serviceStartSecondOfDay()));
    }

    // ---- 5. 汇总与判据 ---------------------------------------------------
    for (WeightedTripAllocator.ShareReport share : chosen.shares()) {
      if (share.deviationPercentPoints() > SHARE_WARN_PERCENT_POINTS) {
        warnings.add(
            String.format(
                Locale.ROOT,
                "%s 目标 %.1f%% 实际 %.1f%%：线路能力、全程时分或出库/回库覆盖不足以排满它应得的份额",
                share.key(),
                share.targetShare() * 100.0D,
                share.achievedShare() * 100.0D));
      }
    }
    for (TimetableBuildResult.InfeasibleRoute route : prepared.infeasible()) {
      warnings.add("route " + route.routeCode() + " 排除：" + route.reason());
    }
    warnings.addAll(TimetableBuildReportText.describeDropped(chosen.dropped()));
    warnings.addAll(chosen.couplingNotes());
    List<VehicleDuty> duties = chosen.timetable().duties();
    VehicleDutyPlanner.Result finalDuties =
        new VehicleDutyPlanner.Result(duties, List.of(), duties.size(), List.of());
    if (!finalDuties.allDutiesReturnToStorage()) {
      // 理论上 VehicleDuty 的构造器已经挡住了这种情况；留一条断言式警告，避免静默降级。
      warnings.add("存在没有回库端点的 duty，这是一个不应发生的状态");
    }

    Timetable timetable = chosen.timetable();

    int longestTrip =
        chosenPrepared.operationPlans().stream()
            .mapToInt(TimetableRoutePlan::totalRunSeconds)
            .max()
            .orElse(0);
    // 与某份邻表的"接触"= 目标间隔下与它的真冲突 + 给它让的车：基线要记下所有影响过我的表。
    Map<String, Integer> externalByOwner = new HashMap<>(target.conflicts().externalByOwner());
    for (TripYield yield : target.yields()) {
      yield.firstOwner().ifPresent(owner -> externalByOwner.merge(owner, 1, Integer::sum));
    }
    List<TimetableBuildResult.NeighborSummary> neighborSummaries = new ArrayList<>();
    List<TimetableBaseline> baselines = new ArrayList<>();
    for (NeighborTimetable neighbor : input.neighbors()) {
      int conflictsWith = externalByOwner.getOrDefault(neighbor.displayCode(), 0);
      neighborSummaries.add(TimetableBuildResult.NeighborSummary.of(neighbor, conflictsWith));
      baselines.add(TimetableBaseline.of(input.timetableId(), neighbor, conflictsWith));
      for (String warning : neighbor.warnings()) {
        warnings.add("邻表 " + neighbor.displayCode() + "：" + warning);
      }
    }
    List<String> consistNotes =
        new ArrayList<>(
            ConsistReport.notes(
                input.fleet(),
                chosenPrepared.operationPlans(),
                timetable,
                chosenPrepared.profiles(),
                chosenPrepared.graphIndex().nodeTypes(),
                warnings));
    consistCatchUp(chosenPrepared, timetable, chosen.yields(), options)
        .ifPresent(consistNotes::add);
    TimetableBuildResult result =
        new TimetableBuildResult(
            Optional.of(timetable),
            chosen.shares(),
            prepared.infeasible(),
            chosen.dropped(),
            duties.size(),
            finalDuties.spawnedVehicles(),
            finalDuties.peakConcurrentVehicles(),
            finalDuties.maxTripsInAnyDuty(),
            finalDuties.maxDutyDurationSeconds(),
            finalDuties.allDutiesReturnToStorage(),
            longestTrip,
            targetHeadway,
            chosen.headwaySeconds(),
            target.conflicts().conflicts(),
            List.copyOf(neighborSummaries),
            List.copyOf(baselines),
            chosen.shifts(),
            chosen.yields(),
            groupIntervals(target, chosen),
            chosen.interleaves(),
            dutyShapes(chosen.timetable()),
            chosen.phaseNotes(),
            // 残余取最终选中的那一次：报告说的是"这张表发布后运行时要让几次车"。
            chosen.absorbable(),
            chosen.unabsorbable(),
            List.of(),
            chosen.residues(),
            List.copyOf(warnings),
            TimetableBuildResult.CatchUp.NONE,
            CapacityReport.Report.NONE,
            consistNotes);
    return new Built(result, Optional.of(prepared), Optional.of(chosenPrepared), options);
  }

  /** 一份制式候选完整排一次的结果；排不出时 {@code attempt} 为空、{@code failure} 是原因。 */
  private record RegimeTry(Solved solved, Attempt attempt, String failure) {}

  /** 解一份制式候选并完整排一次；构建失败或超周期太长时记下原因，不连累同一批的别的候选。 */
  private RegimeTry tryRegime(
      Supplier<Solved> candidate, TimetableBuildOptions options, Instant builtAt) {
    try {
      Solved solved = candidate.get();
      Attempt attempt =
          attemptAtPhases(solved.prepared(), options, solved.input(), builtAt, solved.phases());
      return new RegimeTry(solved, attempt, null);
    } catch (BuildFailure | PeriodicSolver.HyperperiodTooLong failure) {
      return new RegimeTry(null, null, failure.getMessage());
    }
  }

  private void stage(TimetableBuildProgress.Stage stage) {
    progress.stage(stage);
  }

  private void detail(String text) {
    progress.detail(text);
  }

  private static int separationOf(TimetableBuildOptions options) {
    return (int) Math.min(Integer.MAX_VALUE, options.separation().toSeconds());
  }

  private static Map<UUID, String> routeCodes(Prepared prepared) {
    Map<UUID, String> out = new HashMap<>();
    for (TimetableRoutePlan plan : prepared.plans()) {
      out.put(plan.routeId(), plan.routeCode());
    }
    return out;
  }

  /** 各组目标与实际间隔，按组名排序（尝试里的间隔表是 {@code Map.copyOf}，遍历顺序每个进程都不同）。 */
  private static List<TimetableBuildResult.GroupInterval> groupIntervals(
      Attempt target, Attempt chosen) {
    List<TimetableBuildResult.GroupInterval> out = new ArrayList<>();
    new TreeMap<>(target.intervals())
        .forEach(
            (group, seconds) ->
                out.add(
                    new TimetableBuildResult.GroupInterval(
                        group, seconds, chosen.intervals().getOrDefault(group, seconds))));
    return List.copyOf(out);
  }

  /**
   * 搜索选中的间隔比目标宽在哪里。
   *
   * <p>有交路组时只列仍被放宽的组（逐组收紧后最小的组间隔已不能代表"放宽到多少"），没有时比总间隔。 一组也没放宽时为空：目标间隔下靠喂车方向多停就排开了，不能说成"回退"。
   *
   * @param targetIntervals 各组目标间隔
   * @param targetHeadway 目标间隔
   * @param chosenIntervals 选中的各组间隔
   * @param chosenHeadway 选中的间隔
   * @return 放宽的描述，如 {@code tight 120→210s}；没有放宽时为空
   */
  static Optional<String> widenedIntervals(
      Map<String, Integer> targetIntervals,
      int targetHeadway,
      Map<String, Integer> chosenIntervals,
      int chosenHeadway) {
    if (targetIntervals.size() <= 1) {
      return chosenHeadway > targetHeadway ? Optional.of(chosenHeadway + "s") : Optional.empty();
    }
    List<String> widened = new ArrayList<>();
    new TreeMap<>(targetIntervals)
        .forEach(
            (group, seconds) -> {
              int effective = chosenIntervals.getOrDefault(group, seconds);
              if (effective > seconds) {
                widened.add(group + " " + seconds + "→" + effective + "s");
              }
            });
    return widened.isEmpty() ? Optional.empty() : Optional.of(String.join("、", widened));
  }

  /** 交路形状：跑几班的交路各有多少条；运营者看它判断出入库班配得多不多。 */
  private static List<TimetableBuildResult.DutyShape> dutyShapes(Timetable timetable) {
    Map<Integer, Integer> counts = new TreeMap<>();
    for (VehicleDuty duty : timetable.duties()) {
      counts.merge(duty.tripIds().size(), 1, Integer::sum);
    }
    List<TimetableBuildResult.DutyShape> out = new ArrayList<>();
    counts.forEach((trips, duties) -> out.add(new TimetableBuildResult.DutyShape(trips, duties)));
    return List.copyOf(out);
  }

  // ------------------------------------------------------------ 第 1 步

  /**
   * 算时分、分类 route、建走行段索引、做线路级校验。失败抛 {@link BuildFailure}，不可行清单已写入 {@code infeasible}。
   *
   * @param knownIndex 已经建好的图索引（同一张图）；为空时现建
   */
  private Prepared prepare(
      BuildInput input,
      TimetableBuildOptions options,
      List<TimetableBuildResult.InfeasibleRoute> infeasible,
      TimetableConflictChecker.GraphIndex knownIndex) {
    UUID timetableId = input.timetableId();
    List<TimetableRoutePlan> plans = new ArrayList<>();
    List<OperationPlan> operations = new ArrayList<>();
    // 图索引只建一次：站台映射要用节点类型，冲突扫描要用容量与单线区段。
    TimetableConflictChecker.GraphIndex graphIndex =
        knownIndex != null ? knownIndex : TimetableConflictChecker.GraphIndex.of(input.graph());
    List<VehicleDutyPlanner.Leg> createLegs = new ArrayList<>();
    List<VehicleDutyPlanner.Leg> returnLegs = new ArrayList<>();
    Map<UUID, String> legStation = new HashMap<>();
    Map<UUID, TimetableConflictChecker.RouteProfile> profiles = new HashMap<>();
    Set<UUID> passengerLegs = new HashSet<>();
    Map<UUID, Integer> runByRoute = new HashMap<>();
    // 车型变体：基础 route → 车型键 → 变体 ID。不区分车型时为空。
    Map<UUID, Map<String, UUID>> variants = new HashMap<>();
    // 区分车型时各 route 用哪个走行模型出逐点轨迹：变体用自己的车型，基础 route 用最慢的那个车型（与它的时分一致）。
    Map<UUID, RunTimeModel> modelByRoute = new HashMap<>();
    for (RouteInput route : input.sortedRoutes()) {
      Optional<String> blocked = input.fleet().blockedReason(route.routeId());
      if (blocked.isPresent()) {
        infeasible.add(new TimetableBuildResult.InfeasibleRoute(route.routeCode(), blocked.get()));
        continue;
      }
      // 区分车型时，每个允许的车型各算一份时分；基础时分取其中最慢的那份（相位锚定、网格可行性按它）。
      Map<String, TimetableTimingCalculator.TimingResult> variantTimings =
          variantTimings(input, route, options);
      if (variantTimings.isEmpty() && !input.fleet().sharesFor(route.routeId()).isEmpty()) {
        // 允许的车型一个都跑不了：不能退回基础走行模型，那等于让方案之外的车跑这条 route。
        infeasible.add(
            new TimetableBuildResult.InfeasibleRoute(route.routeCode(), "允许的车型都算不出这条 route 的时分"));
        continue;
      }
      TimetableTimingCalculator.TimingResult timing =
          variantTimings.isEmpty()
              ? timingCalculator.compute(
                  input.graph(),
                  input.runTimeModel(),
                  route.definition(),
                  route.stops(),
                  options.defaultDwell())
              : slowest(variantTimings);
      if (!timing.ok()) {
        infeasible.add(
            new TimetableBuildResult.InfeasibleRoute(
                route.routeCode(), timing.failure().orElse("时分无法计算")));
        continue;
      }
      List<NodeId> waypoints = route.definition().waypoints();
      String origin = waypoints.get(0).value();
      String terminal = waypoints.get(waypoints.size() - 1).value();
      if (route.declaredAs().isPresent() && route.declaredAs().get() != route.operationType()) {
        // 运营 route 的 metadata 指定它做出库/回库线路，但它本身不是那种类型：不能拿运营线路当回库线路用。
        infeasible.add(
            new TimetableBuildResult.InfeasibleRoute(
                route.routeCode(),
                "指定的出库/回库线路类型不符：被指定为 "
                    + route.declaredAs().get().name()
                    + "，实际是 "
                    + route.operationType().name()));
        continue;
      }
      boolean declared = route.declaredAs().isPresent();
      // 归属决定它是"我的班次"还是"我借来的走行"：别的线的带客 CREATE/RETURN 只做交路两头。
      boolean owned = input.owns(route.routeId());
      runByRoute.put(route.routeId(), timing.totalRunSeconds());
      Map<String, UUID> variantIds = new LinkedHashMap<>();
      for (Map.Entry<String, TimetableTimingCalculator.TimingResult> variant :
          variantTimings.entrySet()) {
        UUID variantId = ConsistFleet.variantId(route.routeId(), variant.getKey());
        variantIds.put(variant.getKey(), variantId);
        runByRoute.put(variantId, variant.getValue().totalRunSeconds());
      }
      if (!variantIds.isEmpty()) {
        variants.put(route.routeId(), Map.copyOf(variantIds));
        modelByRoute.put(
            route.routeId(), input.fleet().consists().get(slowestKey(variantTimings)).model());
        variantIds.forEach(
            (key, variantId) ->
                modelByRoute.put(variantId, input.fleet().consists().get(key).model()));
      }
      switch (route.operationType()) {
        case CREATE -> {
          if (!startsAtDepot(route.stops())) {
            // 发车侧会把没有 CRET 的 CREATE 票无限重排（create-without-cret），这条线路等于不存在。
            infeasible.add(
                new TimetableBuildResult.InfeasibleRoute(
                    route.routeCode(), "CREATE 线路首站没有 CRET 指令，无法实体化列车"));
            continue;
          }
          if (owned
              && !route.dispatchByOperation()
              && ServiceGroupClassifier.carriesPassengers(route)) {
            // 带客的出库班是班次：上它所属组的子网格，从车库实体化（与 OPERATION + CRET 同一条路径），而不是当走行段。
            // 别的线的带客出库班不在此列——我只是借它出库，把它排成我的班次会连人家自己的 headway 票一起拦掉。
            operations.add(new OperationPlan(route, true, endsAtDepot(route.stops())));
            break;
          }
          if (owned && ServiceGroupClassifier.carriesPassengers(route)) {
            passengerLegs.add(route.routeId());
            passengerLegs.addAll(variantIds.values());
          }
          createLegs.add(
              new VehicleDutyPlanner.Leg(
                  route.routeId(), route.routeCode(), origin, timing.totalRunSeconds(), declared));
          legStation.put(route.routeId(), terminal);
          variantIds.forEach(
              (key, variantId) -> {
                createLegs.add(
                    new VehicleDutyPlanner.Leg(
                        variantId,
                        route.routeCode(),
                        origin,
                        runByRoute.get(variantId),
                        declared,
                        Optional.of(key)));
                legStation.put(variantId, terminal);
              });
        }
        case RETURN -> {
          boolean passenger = owned && ServiceGroupClassifier.carriesPassengers(route);
          if (passenger) {
            // 带客的回库班：仍由派车器在交路收尾处生成（到达 + 折返），但落 trip 行给 PIDS 与导出。
            passengerLegs.add(route.routeId());
          }
          returnLegs.add(
              new VehicleDutyPlanner.Leg(
                  route.routeId(),
                  route.routeCode(),
                  terminal,
                  timing.totalRunSeconds(),
                  declared));
          legStation.put(route.routeId(), origin);
          variantIds.forEach(
              (key, variantId) -> {
                if (passenger) {
                  passengerLegs.add(variantId);
                }
                returnLegs.add(
                    new VehicleDutyPlanner.Leg(
                        variantId,
                        route.routeCode(),
                        terminal,
                        runByRoute.get(variantId),
                        declared,
                        Optional.of(key)));
                legStation.put(variantId, origin);
              });
        }
        case OPERATION -> {
          if (!owned) {
            // 今天不会出现（命令层只收本线的 OPERATION）；真出现了说明调用方搞错了归属，说清楚而不是排进表里。
            infeasible.add(
                new TimetableBuildResult.InfeasibleRoute(
                    route.routeCode(), "运营线路不属于本时刻表的线路，不能排进本表"));
            continue;
          }
          operations.add(
              new OperationPlan(route, startsAtDepot(route.stops()), endsAtDepot(route.stops())));
        }
      }
      TimetableRoutePlan plan =
          new TimetableRoutePlan(
              route.routeId(),
              route.routeCode(),
              route.operationType(),
              route.weight(),
              timing.stops(),
              origin,
              terminal,
              route.depotNodeId(),
              Optional.empty(),
              // 借来的走行线路一律标 external：它所在线路自己的 headway 票不能被我拦掉。
              route.external() || !owned);
      plans.add(plan);
      profiles.put(
          route.routeId(),
          new TimetableConflictChecker.RouteProfile(
              route.routeId(),
              route.routeCode(),
              timing.stops(),
              timing.segments(),
              TimetableConflictChecker.platformsOf(
                  timing.stops(), route.stops(), waypoints, graphIndex.nodeTypes())));
      // 车型变体：时分与足迹按各自的车型；车尾出清只多算比最短车型长出来的那一截。
      for (Map.Entry<String, UUID> variant : variantIds.entrySet()) {
        String key = variant.getKey();
        UUID variantId = variant.getValue();
        TimetableTimingCalculator.TimingResult variantTiming = variantTimings.get(key);
        ConsistFleet.Consist consist = input.fleet().consists().get(key);
        double tail = input.fleet().tailBlocks(key);
        plans.add(
            plan.asVariant(
                variantId,
                new TimetableRoutePlan.ConsistVariant(
                    key, route.routeId(), consist.lengthBlocks(), tail),
                variantTiming.stops()));
        profiles.put(
            variantId,
            new TimetableConflictChecker.RouteProfile(
                variantId,
                route.routeCode(),
                variantTiming.stops(),
                variantTiming.segments(),
                TimetableConflictChecker.platformsOf(
                    variantTiming.stops(), route.stops(), waypoints, graphIndex.nodeTypes()),
                tail));
      }
    }
    if (operations.isEmpty()) {
      throw new BuildFailure("没有任何运营 route 能算出计划时分");
    }
    boolean anyCreateAccess =
        !createLegs.isEmpty() || operations.stream().anyMatch(OperationPlan::startsAtDepot);
    if (!anyCreateAccess) {
      throw new BuildFailure("线路没有任何出库途径：需要至少一条 CREATE 线路（车库 → 首站），或一条首站带 CRET 指令的运营线路");
    }
    boolean anyReturnAccess =
        !returnLegs.isEmpty() || operations.stream().anyMatch(OperationPlan::endsAtDepot);
    if (!anyReturnAccess) {
      throw new BuildFailure("线路没有任何回库途径：需要至少一条 RETURN 线路（末站 → 车库），或一条以 DSTY 收尾的运营线路");
    }
    List<TimetableRoutePlan> operationPlans =
        operations.stream().map(op -> planOf(plans, op.route().routeId())).toList();
    // 候选权重取 RouteInput 的：TimetableRoutePlan 对 CREATE 类型把 weight 归零，而带客的出库班要按它配的权重切份额。
    List<WeightedTripAllocator.Candidate> candidates =
        operations.stream()
            .map(
                op ->
                    new WeightedTripAllocator.Candidate(
                        op.route().routeCode(), op.route().weight()))
            .toList();
    if (candidates.stream().allMatch(candidate -> candidate.weight() <= 0)) {
      throw new BuildFailure("所有运营 route 的 weight 都不是正数，无法分配服务比例");
    }
    // 加解编：支线交路由主干派生，不上网格；写法有问题（切不开、读不到编组）编表直接报错
    CouplingPlanner.Analysis couplings =
        couplingsOf(
            input,
            plans,
            operations.stream()
                .map(op -> op.route().routeId())
                .collect(java.util.stream.Collectors.toSet()));
    if (!couplings.errors().isEmpty()) {
      throw new BuildFailure("加解编写法有误：" + String.join("；", couplings.errors()));
    }
    // 只对进发车表的 route 分组分方向：走行段不上网格。
    List<RouteInput> scheduled =
        operations.stream()
            .map(OperationPlan::route)
            .filter(route -> !couplings.branchRoutes().contains(route.routeId()))
            .toList();
    ServiceGroupClassifier.Classification classification =
        ServiceGroupClassifier.classify(scheduled);
    BlockingTimes.Trajectories trajectories = trajectoriesOf(input, modelByRoute);
    return new Prepared(
        timetableId,
        // 足迹另登记闭塞格（跟车规则未启用时不登记）：周期层、预订表与终判都从这个索引取足迹，口径因此只有一个。
        graphIndex.withBlocking(options.following(), trajectories),
        List.copyOf(plans),
        List.copyOf(operations),
        operationPlans,
        candidates,
        VehicleDutyPlanner.Legs.of(createLegs, returnLegs, legStation),
        Map.copyOf(profiles),
        List.copyOf(infeasible),
        classification,
        Set.copyOf(passengerLegs),
        Map.copyOf(runByRoute),
        trajectories,
        Map.copyOf(variants),
        baseByVariant(variants),
        couplings);
  }

  /** 推出加解编结构（基础时分）。 */
  private static CouplingPlanner.Analysis couplingsOf(
      BuildInput input, List<TimetableRoutePlan> plans, Set<UUID> gridRoutes) {
    if (input.couplings() == CouplingInputs.NONE
        && input.routes().stream()
            .flatMap(route -> route.stops().stream())
            .noneMatch(
                stop ->
                    org.fetarute
                            .fetaruteTCAddon
                            .dispatcher
                            .route
                            .RouteCouplingDirectives
                            .rawDecouple(stop)
                            .isPresent()
                        || org.fetarute
                            .fetaruteTCAddon
                            .dispatcher
                            .route
                            .RouteCouplingDirectives
                            .rawCouple(stop)
                            .isPresent())) {
      return CouplingPlanner.Analysis.NONE;
    }
    Map<UUID, TimetableRoutePlan> byRoute = new HashMap<>();
    for (TimetableRoutePlan plan : plans) {
      if (plan.consist().isEmpty()) {
        byRoute.putIfAbsent(plan.routeId(), plan);
      }
    }
    Set<UUID> planned = new HashSet<>();
    for (RouteInput route : input.routes()) {
      if (!input.fleet().sharesFor(route.routeId()).isEmpty()) {
        planned.add(route.routeId());
      }
    }
    return CouplingPlanner.analyze(
        input.sortedRoutes(),
        byRoute,
        input.couplings(),
        planned,
        gridRoutes,
        input::poolOf,
        org.fetarute.fetaruteTCAddon.dispatcher.route.RouteCouplingDirectives.coupleSeconds());
  }

  /**
   * 闭塞时间用的逐点轨迹：每条 route 用它自己的走行模型（区分车型时见 {@code modelByRoute}），其余用编表的走行模型。
   *
   * @param modelByRoute route（含车型变体）→ 走行模型；不区分车型时为空
   */
  private static BlockingTimes.Trajectories trajectoriesOf(
      BuildInput input, Map<UUID, RunTimeModel> modelByRoute) {
    RunTimeModel base = input.runTimeModel();
    RailGraph graph = input.graph();
    Map<UUID, RunTimeModel> models = Map.copyOf(modelByRoute);
    return new BlockingTimes.Trajectories() {
      @Override
      public Optional<RunTimeModel.Trajectory> of(RunTimeModel.Run run) {
        return base.trajectory(graph, run);
      }

      @Override
      public BlockingTimes.Trajectories forRoute(UUID routeId) {
        RunTimeModel model = models.get(routeId);
        return model == null ? this : run -> model.trajectory(graph, run);
      }
    };
  }

  /** 全程最慢的那份时分是哪个车型的；并列时取车型键最小的（与 {@link #slowest} 同一份）。 */
  private static String slowestKey(Map<String, TimetableTimingCalculator.TimingResult> timings) {
    String slowest = null;
    int run = -1;
    for (Map.Entry<String, TimetableTimingCalculator.TimingResult> entry : timings.entrySet()) {
      if (slowest == null || entry.getValue().totalRunSeconds() > run) {
        slowest = entry.getKey();
        run = entry.getValue().totalRunSeconds();
      }
    }
    return slowest;
  }

  /** 变体 → 基础 route 的反查表。 */
  private static Map<UUID, UUID> baseByVariant(Map<UUID, Map<String, UUID>> variants) {
    Map<UUID, UUID> out = new HashMap<>();
    variants.forEach((base, byKey) -> byKey.values().forEach(variant -> out.put(variant, base)));
    return Map.copyOf(out);
  }

  /**
   * 一条 route 按它允许的每个车型各算一份时分；某个车型算不出来就不进变体（它不能跑这条 route）。不区分车型时为空。
   *
   * <p>车型键按方案里的顺序；算出来的变体之间按键排序，结果与 Map 的遍历顺序无关。
   */
  private Map<String, TimetableTimingCalculator.TimingResult> variantTimings(
      BuildInput input, RouteInput route, TimetableBuildOptions options) {
    Map<String, TimetableTimingCalculator.TimingResult> out = new TreeMap<>();
    for (ConsistFleet.Share share : input.fleet().sharesFor(route.routeId())) {
      ConsistFleet.Consist consist = input.fleet().consists().get(share.key());
      TimetableTimingCalculator.TimingResult timing =
          timingCalculator.compute(
              input.graph(),
              consist.model(),
              route.definition(),
              route.stops(),
              options.defaultDwell());
      if (timing.ok()) {
        out.put(share.key(), timing);
      }
    }
    return out;
  }

  /** 全程最慢的那份时分；并列时取车型键最小的，确定。 */
  private static TimetableTimingCalculator.TimingResult slowest(
      Map<String, TimetableTimingCalculator.TimingResult> timings) {
    TimetableTimingCalculator.TimingResult slowest = null;
    for (TimetableTimingCalculator.TimingResult timing : timings.values()) {
      if (slowest == null || timing.totalRunSeconds() > slowest.totalRunSeconds()) {
        slowest = timing;
      }
    }
    return slowest;
  }

  // ------------------------------------------------------------ 第 2–4 步

  /** 按给定相位完整排一次：铺网格、派车、预订表落位、编号、冲突检查与残余分类。 */
  private Attempt attemptAtPhases(
      Prepared prepared,
      TimetableBuildOptions options,
      BuildInput input,
      Instant builtAt,
      PhasePlanner.Phases phases) {
    return attemptAtPhases(prepared, options, input, builtAt, phases, Set.of());
  }

  /**
   * 同上，另外取消几个名义格子上的班次（严格模式 {@code --max-cancel}）：派车照常把它们算进去（与不取消时派出同一套交路）， 派完再从各自的交路里拿掉（{@link
   * DutyWithdrawal}），记为 {@link
   * VehicleDutyPlanner.UnassignedReason#CONFLICT_CANCELLED}；别的交路不动，之后照常落进预订表。
   *
   * @param cancelled 要取消的名义格子
   */
  private Attempt attemptAtPhases(
      Prepared prepared,
      TimetableBuildOptions options,
      BuildInput input,
      Instant builtAt,
      PhasePlanner.Phases phases,
      Set<CancelSlot> cancelled) {
    // 加解编把几条交路连在一起：派车之后预订表截掉了卷进加解编的班次，就带着这些截断重派——
    // 主干被截掉的下一轮不排，只有派生的支线被截掉的下一轮照跑主干、不拆挂。轮数有上限，用完按最后一轮的结果。
    Set<CancelSlot> excluded = new HashSet<>();
    Set<CancelSlot> uncoupled = new HashSet<>();
    Once previous = null;
    for (int round = 0; ; round++) {
      Once once;
      try {
        once =
            attemptOnce(prepared, options, input, builtAt, phases, cancelled, excluded, uncoupled);
      } catch (BuildFailure failure) {
        // 重派那一轮反倒排不出（例如截掉的主干正是剩下的全部）：用上一轮的结果
        if (previous == null) {
          throw failure;
        }
        return previous.attempt();
      }
      if ((once.brokenTrunks().isEmpty() && once.brokenBranches().isEmpty())
          || round >= COUPLING_ROUNDS) {
        return once.attempt();
      }
      excluded.addAll(once.brokenTrunks());
      uncoupled.addAll(once.brokenBranches());
      previous = once;
    }
  }

  /** 加解编截断后重派的轮数上限。 */
  private static final int COUPLING_ROUNDS = 3;

  /**
   * 一次完整构建与它截断的加解编班次。
   *
   * @param attempt 构建结果
   * @param brokenTrunks 被截掉的主干（下一轮不排）
   * @param brokenBranches 派生的支线被截掉的主干（下一轮照跑、不拆挂）
   */
  private record Once(
      Attempt attempt, Set<CancelSlot> brokenTrunks, Set<CancelSlot> brokenBranches) {}

  private Once attemptOnce(
      Prepared prepared,
      TimetableBuildOptions options,
      BuildInput input,
      Instant builtAt,
      PhasePlanner.Phases phases,
      Set<CancelSlot> cancelled,
      Set<CancelSlot> excluded,
      Set<CancelSlot> uncoupled) {
    progress.fullBuild();
    UUID timetableId = prepared.timetableId();
    int horizon = options.horizonSeconds();
    int separation = (int) Math.min(Integer.MAX_VALUE, options.separation().toSeconds());
    List<TimetableRoutePlan> operationPlans = prepared.operationPlans();
    Map<UUID, Integer> candidateIndexByRoute = new HashMap<>();
    for (int i = 0; i < operationPlans.size(); i++) {
      candidateIndexByRoute.put(operationPlans.get(i).routeId(), i);
    }

    // ---- 2. 每个交路组每个方向一张规整子网格，相位由周期层求解给出（buildOnce）。 ----
    List<ServiceGroupClassifier.Group> groups = prepared.classification().groups();
    Map<String, Integer> intervalByGroup = new TreeMap<>();
    for (ServiceGroupClassifier.Group group : groups) {
      intervalByGroup.put(group.name(), options.intervalFor(group.name()));
    }
    long headwaySeconds =
        intervalByGroup.values().stream()
            .mapToInt(Integer::intValue)
            .min()
            .orElse((int) Math.max(1L, options.headway().toSeconds()));
    // 只有真正上网格的方向才有相位：带客回库班由派车器在交路收尾处生成（到达 + 折返），不是周期流。
    // 留在相位层里它会被当成一条幻影流——与反向配成往返对去锚定别人，还参与合流点打分。
    List<ServiceGroupClassifier.Group> gridGroups =
        gridGroupsOf(groups, candidateIndexByRoute.keySet());
    List<GroupGrid.DirectionGrid> grids = new ArrayList<>();
    List<Placed> placed = new ArrayList<>();
    for (ServiceGroupClassifier.Group group : gridGroups) {
      int interval = intervalByGroup.get(group.name());
      for (ServiceGroupClassifier.Direction direction : group.directions()) {
        List<GroupGrid.Segment> segments =
            segmentsOf(phases, group.name(), direction.key(), interval, horizon);
        List<Integer> times = GroupGrid.times(segments);
        // 可行性：这一班必须在计划窗口内跑完。全程时分长的 route 因此会在窗口末尾被自然挤出，
        // 而它的 deficit 留在分配器里——这正是"约束恢复后能追回份额"的机制。
        WeightedTripAllocator.FeasibilityCheck feasibility =
            (slot, index, assigned) -> {
              UUID routeId = direction.routeIds().get(index);
              Integer run = prepared.runByRoute().get(routeId);
              return candidateIndexByRoute.containsKey(routeId)
                  && times.get(slot) + (long) (run == null ? 0 : run) <= horizon;
            };
        GroupGrid.DirectionGrid grid = GroupGrid.ofSegments(direction, segments, feasibility);
        grids.add(grid);
        for (GroupGrid.Slot slot : grid.slots()) {
          placed.add(new Placed(slot, direction, false));
        }
      }
    }
    List<String> pinNotes = new ArrayList<>();
    placed =
        PinnedSlots.apply(
            placed,
            options,
            gridGroups,
            operationPlans,
            candidateIndexByRoute,
            prepared.runByRoute(),
            turnsOf(input, prepared, options),
            pinNotes);
    placed.sort(
        Comparator.comparingInt((Placed p) -> p.slot().departureSeconds())
            .thenComparing(
                p ->
                    operationPlans.get(candidateIndexByRoute.get(p.slot().routeId())).routeCode()));
    if (placed.isEmpty()) {
      throw new BuildFailure("计划窗口内排不下任何班次：检查间隔、首末班时刻与全程时分");
    }

    // 派车前的班次用临时主键：取消一部分之后要重新编号，主键由最终车次号派生。
    List<VehicleDutyPlanner.PlannedTrip> plannedTrips = new ArrayList<>(placed.size());
    Map<UUID, WeightedTripAllocator.Allocation> allocationByProvisional = new HashMap<>();
    Map<UUID, Placed> placedByProvisional = new HashMap<>();
    List<TimetableBuildResult.DroppedTrip> dropped = new ArrayList<>();
    // 取消的格子照常进派车（派车结果与不取消时逐条相同），派完再从各自的交路里拿掉，见 DutyWithdrawal。
    Set<UUID> withdrawn = new HashSet<>();
    Set<UUID> pinnedProvisional = new HashSet<>();
    for (int i = 0; i < placed.size(); i++) {
      Placed item = placed.get(i);
      int candidateIndex = candidateIndexByRoute.get(item.slot().routeId());
      OperationPlan op = prepared.operations().get(candidateIndex);
      TimetableRoutePlan plan = operationPlans.get(candidateIndex);
      int departureSeconds = item.slot().departureSeconds();
      if (excluded.contains(new CancelSlot(plan.routeId(), departureSeconds))) {
        // 加解编：上一次构建里预订表截断了这一班主干，这一次不排
        dropped.add(
            new TimetableBuildResult.DroppedTrip(
                plan.routeCode(),
                clock(secondOfDay(departureSeconds, options)),
                VehicleDutyPlanner.UnassignedReason.STUB_SATURATED));
        continue;
      }
      UUID provisional =
          UUID.nameUUIDFromBytes(
              ("provisional:" + timetableId + ":" + i)
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      allocationByProvisional.put(
          provisional, new WeightedTripAllocator.Allocation(item.slot().slot(), candidateIndex));
      placedByProvisional.put(provisional, item);
      if (item.pinned()) {
        pinnedProvisional.add(provisional);
      }
      if (cancelled.contains(new CancelSlot(plan.routeId(), departureSeconds))) {
        withdrawn.add(provisional);
      }
      VehicleDutyPlanner.PlannedTrip trip =
          new VehicleDutyPlanner.PlannedTrip(
              provisional,
              plan.routeId(),
              String.format(Locale.ROOT, "%s@%06d", plan.routeCode(), departureSeconds),
              plan.originNodeId(),
              plan.terminalNodeId(),
              departureSeconds,
              plan.totalRunSeconds(),
              op.startsAtDepot(),
              op.endsAtDepot(),
              input.poolOf(plan.routeId()),
              tripConsists(input, prepared, plan));
      plannedTrips.add(trip);
    }
    // 加解编：按每一班主干派生支线班次（不上网格、与主干同频），连同拆挂计划交给派车。主干被取消，派生的一并取消。
    VehicleDutyPlanner.Couplings couplingPlan = VehicleDutyPlanner.Couplings.none();
    Map<UUID, UUID> derivedParent = new HashMap<>();
    List<String> couplingNotes = List.of();
    if (!prepared.couplings().isEmpty()) {
      Map<UUID, TimetableRoutePlan> planById = new HashMap<>();
      Map<UUID, boolean[]> flags = new HashMap<>();
      for (int i = 0; i < operationPlans.size(); i++) {
        planById.put(operationPlans.get(i).routeId(), operationPlans.get(i));
        OperationPlan op = prepared.operations().get(i);
        flags.put(op.route().routeId(), new boolean[] {op.startsAtDepot(), op.endsAtDepot()});
      }
      CouplingPlanner.Derived derived =
          CouplingPlanner.derive(
              prepared.couplings(),
              plannedTrips.stream()
                  .filter(
                      trip ->
                          !uncoupled.contains(
                              new CancelSlot(trip.routeId(), trip.departureSeconds())))
                  .toList(),
              planById,
              flags,
              org.fetarute.fetaruteTCAddon.dispatcher.route.RouteCouplingDirectives
                  .coupleSeconds());
      for (VehicleDutyPlanner.PlannedTrip trip : derived.trips()) {
        Integer candidateIndex = candidateIndexByRoute.get(trip.routeId());
        if (candidateIndex == null) {
          continue;
        }
        allocationByProvisional.put(
            trip.tripId(), new WeightedTripAllocator.Allocation(0, candidateIndex));
        if (withdrawn.contains(derived.parentOf().get(trip.tripId()))) {
          withdrawn.add(trip.tripId());
        }
        plannedTrips.add(trip);
      }
      couplingPlan = derived.couplings();
      derivedParent.putAll(derived.parentOf());
      couplingNotes = derived.notes();
    }

    // 各起点上的名义发车时刻：派车器用它回答"这辆车在终点还要等多久才有下一班"，
    // 等过头的按 IDLE_LIMIT 回库，与运行时的闲置回收同一条规则。区分车型时另按车型记一份：车等的是自己车型能跑的下一班
    // ——列了车型的班次记进这些车型，不分车型的班次谁都能跑，记进每个车型。
    Map<String, NavigableSet<Integer>> nextSlotByOrigin = new HashMap<>();
    List<String> fleetKeys = input.fleet().keys();
    for (VehicleDutyPlanner.PlannedTrip trip : plannedTrips) {
      nextSlotByOrigin
          .computeIfAbsent(trip.originNodeId(), key -> new TreeSet<>())
          .add(trip.departureSeconds());
      List<String> runnableBy =
          trip.consists().isEmpty()
              ? fleetKeys
              : trip.consists().stream().map(VehicleDutyPlanner.ConsistOption::key).toList();
      for (String consist : runnableBy) {
        nextSlotByOrigin
            .computeIfAbsent(
                VehicleDutyPlanner.slotKey(trip.originNodeId(), Optional.of(consist)),
                key -> new TreeSet<>())
            .add(trip.departureSeconds());
      }
    }
    TimetableDepotAllocator allocator = depotAllocator(prepared, plannedTrips, input, separation);
    VehicleDutyPlanner.Result planned =
        DutyWithdrawal.withdraw(
            timetableId,
            VehicleDutyPlanner.plan(
                timetableId,
                plannedTrips,
                prepared.legs(),
                options.dutyLimits(),
                nextSlotByOrigin,
                preferredFeeders(phases.connections()),
                allocator,
                couplingPlan),
            plannedTrips,
            withdrawn,
            prepared.legs(),
            options.dutyLimits(),
            allocator);
    Map<UUID, VehicleDutyPlanner.UnassignedTrip> unassigned = new HashMap<>();
    List<String> stranded = new ArrayList<>();
    for (VehicleDutyPlanner.UnassignedTrip trip : planned.unassigned()) {
      if (trip.reason() == VehicleDutyPlanner.UnassignedReason.DECOUPLE_STRANDED) {
        // 解下的车就地待命或回库，但解编站回不了库、也没有班次接它：用户定编表报错，须改写法
        stranded.add(trip.tripCode());
        continue;
      }
      unassigned.put(trip.tripId(), trip);
    }
    if (!stranded.isEmpty()) {
      throw new BuildFailure(
          "解编切下的车回不了库：解编站没有回库线路，也没有班次接它（就地待命、回库，或 GOTO 的支线班次排不上）（"
              + String.join("、", stranded.stream().limit(5).toList())
              + (stranded.size() > 5 ? " 等 " + stranded.size() + " 班" : "")
              + "）；请给解编站配一条回库线路（首站是解编站）");
    }
    Map<UUID, UUID> dutyByProvisional = new HashMap<>();
    Map<UUID, Optional<String>> consistByDuty = new HashMap<>();
    for (VehicleDuty duty : planned.duties()) {
      consistByDuty.put(duty.id(), duty.consist());
      for (UUID tripId : duty.tripIds()) {
        dutyByProvisional.put(tripId, duty.id());
      }
    }

    // ---- 3. 只保留派上车的班次，其余取消并说明原因。编号要等预订表落位之后再做：落位会改时刻、也会改先后。
    Map<UUID, Integer> nominalByProvisional = new HashMap<>();
    List<TimetableTrip> provisionalTrips = new ArrayList<>(plannedTrips.size());
    for (VehicleDutyPlanner.PlannedTrip provisional : plannedTrips) {
      WeightedTripAllocator.Allocation allocation =
          allocationByProvisional.get(provisional.tripId());
      TimetableRoutePlan plan = operationPlans.get(allocation.candidateIndex());
      VehicleDutyPlanner.UnassignedTrip missing = unassigned.get(provisional.tripId());
      if (missing != null || !dutyByProvisional.containsKey(provisional.tripId())) {
        dropped.add(
            new TimetableBuildResult.DroppedTrip(
                plan.routeCode(),
                clock(secondOfDay(provisional.departureSeconds(), options)),
                missing == null
                    ? VehicleDutyPlanner.UnassignedReason.NO_RETURN_ACCESS
                    : missing.reason()));
        continue;
      }
      nominalByProvisional.put(provisional.tripId(), provisional.departureSeconds());
      // 区分车型时，班次改挂它那辆车的车型对应的变体：之后的串行、让车、冲突检查都按这个车型的时分与足迹算。
      UUID dutyId = dutyByProvisional.get(provisional.tripId());
      UUID runRoute =
          prepared.variantOf(plan.routeId(), consistByDuty.getOrDefault(dutyId, Optional.empty()));
      // 临时表的时刻约定：零点 + 相对秒，不取模；串行只在相对秒上算，取模留给编号。
      provisionalTrips.add(
          new TimetableTrip(
              provisional.tripId(),
              timetableId,
              runRoute,
              provisionalTrips.size(),
              provisional.tripCode(),
              shiftToServiceDay(provisional.departureSeconds(), options),
              Optional.of(dutyByProvisional.get(provisional.tripId()))));
    }
    if (provisionalTrips.isEmpty()) {
      throw new BuildFailure("排定的班次没有一趟能配上出库与回库线路：检查 CREATE/RETURN 线路是否覆盖各起终点");
    }
    List<VehicleDuty> provisionalDuties = new ArrayList<>(planned.duties().size());
    for (VehicleDuty duty : planned.duties()) {
      provisionalDuties.add(
          new VehicleDuty(
              duty.id(),
              duty.timetableId(),
              duty.sequence(),
              duty.dutyCode(),
              duty.startDepotNodeId(),
              duty.endDepotNodeId(),
              duty.createRouteId(),
              duty.returnRouteId(),
              duty.tripIds(),
              shiftToServiceDay(duty.plannedStartSecondOfDay(), options),
              shiftToServiceDay(duty.returnSecondOfDay(), options),
              shiftToServiceDay(duty.plannedEndSecondOfDay(), options),
              duty.closeReason(),
              duty.consist(),
              duty.coupling()));
    }
    Timetable provisionalTable =
        timetableOf(input, options, prepared, provisionalTrips, provisionalDuties, builtAt);

    // ---- 3.5 统一预订表：运营班次按名义时刻落位（只延后），出入库走行插进空档、让运营车（见 ReservationScheduler）。
    Set<UUID> endingAtDepot = new HashSet<>();
    for (OperationPlan op : prepared.operations()) {
      if (op.endsAtDepot()) {
        endingAtDepot.add(op.route().routeId());
        endingAtDepot.addAll(
            prepared.variants().getOrDefault(op.route().routeId(), Map.of()).values());
      }
    }
    ReservationScheduler.Result repaired =
        ReservationScheduler.schedule(
            new ReservationScheduler.Input(
                provisionalTable,
                prepared.profiles(),
                prepared.graphIndex(),
                options.serviceStartSecondOfDay(),
                horizon,
                separation,
                options.repair().maxWaitSeconds(),
                options.repair().toleranceSeconds(),
                prepared.legs(),
                options.dutyLimits(),
                endingAtDepot,
                input.neighbors(),
                pinnedProvisional));
    for (UUID truncated : repaired.truncatedTripIds()) {
      WeightedTripAllocator.Allocation allocation = allocationByProvisional.get(truncated);
      if (allocation == null) {
        continue;
      }
      dropped.add(
          new TimetableBuildResult.DroppedTrip(
              operationPlans.get(allocation.candidateIndex()).routeCode(),
              clock(secondOfDay(nominalByProvisional.getOrDefault(truncated, 0), options)),
              VehicleDutyPlanner.UnassignedReason.STUB_SATURATED));
    }
    if (repaired.timetable().trips().isEmpty()) {
      throw new BuildFailure("延后累计超限，没有一班能在计划窗口内跑完：检查裕量、折返时间与 --max-wait");
    }

    // ---- 3.65 加解编对齐：串行、让车挪了主干之后，派生的支线班次跟着推后；回程支线晚了主干在联挂站等它 ----
    Timetable alignedTable =
        CouplingAlignment.align(
            repaired.timetable(),
            prepared.couplings(),
            routeId -> planOf(prepared.plans(), routeId),
            prepared::baseOf,
            options.dutyLimits().turnaround(),
            org.fetarute.fetaruteTCAddon.dispatcher.route.RouteCouplingDirectives.coupleSeconds());

    // ---- 3.6 按实际发车顺序编号、派生主键、替换 duty 引用 ----------------------------
    TimetableTripNumbering.Numbered numbered =
        TimetableTripNumbering.number(timetableId, options, alignedTable, nominalByProvisional);
    Map<UUID, String> routeCodeById = new HashMap<>();
    for (TimetableRoutePlan plan : prepared.plans()) {
      routeCodeById.put(plan.routeId(), plan.routeCode());
    }
    List<TimetableTrip> withReturns =
        TimetableTripNumbering.appendPassengerLegTrips(
            timetableId,
            options,
            numbered.trips(),
            numbered.duties(),
            prepared.passengerLegs(),
            routeCodeById);
    Timetable timetable =
        timetableOf(input, options, prepared, withReturns, numbered.duties(), builtAt);
    Map<UUID, String> codeByFinalId = new HashMap<>();
    for (TimetableTrip trip : timetable.trips()) {
      codeByFinalId.put(trip.id(), trip.tripCode());
    }
    Map<String, String> finalCodeByProvisionalCode = new HashMap<>();
    // 运营班次的最终车次号 → 名义格子：严格模式按它取消，重排之后车次号会变，格子不变。
    Map<String, CancelSlot> slotByCode = new HashMap<>();
    Set<String> pinnedCodes = new HashSet<>();
    for (TimetableTrip trip : repaired.timetable().trips()) {
      UUID finalId = numbered.finalByProvisional().get(trip.id());
      if (finalId != null) {
        String finalCode = codeByFinalId.getOrDefault(finalId, "?");
        finalCodeByProvisionalCode.put(trip.tripCode(), finalCode);
        Placed item = placedByProvisional.get(trip.id());
        if (item != null) {
          slotByCode.put(
              finalCode, new CancelSlot(item.slot().routeId(), item.slot().departureSeconds()));
          if (item.pinned()) {
            pinnedCodes.add(finalCode);
          }
        }
      }
    }
    List<TripYield> yields = TripYield.renamed(repaired.yields(), finalCodeByProvisionalCode);
    List<DutyRetiming.Shift> allShifts = repaired.shifts();
    List<TimetableBuildResult.TripShift> shifts = new ArrayList<>(allShifts.size());
    for (DutyRetiming.Shift shift : allShifts) {
      UUID finalId = numbered.finalByProvisional().get(shift.tripId());
      if (finalId == null) {
        continue;
      }
      shifts.add(
          new TimetableBuildResult.TripShift(
              codeByFinalId.getOrDefault(finalId, "?"),
              secondOfDay(shift.nominalSeconds(), options),
              secondOfDay(shift.actualSeconds(), options),
              shift.reason()));
    }

    // ---- 4. 查冲突：把成品表投影成全部运行 + 站台待命，零点 = 计划窗口起点 ------------
    TimetableOccupancyProjector.Occupancy occupancy =
        TimetableOccupancyProjector.project(
            timetable, prepared.profiles(), options.serviceStartSecondOfDay());
    // 邻表的运行原样加入：搜索可行 headway 时只重排我的，它们一动不动——这就是路权先到先得。
    Map<UUID, TimetableConflictChecker.RouteProfile> profiles = new HashMap<>(prepared.profiles());
    List<TimetableConflictChecker.Movement> movements = new ArrayList<>(occupancy.movements());
    List<TimetableConflictChecker.Stay> stays = new ArrayList<>(occupancy.stays());
    for (NeighborTimetable neighbor : input.neighbors()) {
      neighbor.profiles().forEach(profiles::putIfAbsent);
      movements.addAll(neighbor.movements());
      stays.addAll(neighbor.stays());
    }
    TimetableConflictChecker.Report conflicts =
        TimetableConflictChecker.check(
            prepared.graphIndex(),
            profiles,
            movements,
            stays,
            separation,
            TimetableConflictChecker.vehicleOf(timetable));

    // 残余分类：预订表落位之后剩下的每一处，判运行时能不能让、在哪让、让多久。
    // 成功判据是"零不可吸收残余"而不是"零冲突"——运行时对这类冲突的处理本来就是后车在资源前等。
    List<ConflictAbsorption.Residual> residuals =
        ConflictAbsorption.classify(
            conflicts,
            timetable,
            new TimetableOccupancyProjector.Occupancy(movements, stays),
            prepared.graphIndex(),
            separation,
            options.repair().maxWaitSeconds(),
            options.dutyLimits().turnaround(),
            profiles,
            recoveryOf(options, input));

    // 份额按方向报：weight 只在同方向多 route 之间切，跨方向、跨组比没有意义。
    // 数的必须是<b>预订表落位之后</b>还留在表上的班次：落位时还会截断一批，按派车后的表数会把
    // 已经取消的班次算进份额，报告与表对不上（`sharesMatchEmittedTrips` 钉住这一条）。
    Map<String, List<WeightedTripAllocator.Allocation>> keptByDirection = new TreeMap<>();
    for (TimetableTrip trip : repaired.timetable().trips()) {
      Placed item = placedByProvisional.get(trip.id());
      if (item != null) {
        keptByDirection
            .computeIfAbsent(item.direction().key(), key -> new ArrayList<>())
            .add(
                new WeightedTripAllocator.Allocation(
                    item.slot().slot(), item.slot().candidateIndex()));
      }
    }
    List<WeightedTripAllocator.ShareReport> shares = new ArrayList<>();
    for (ServiceGroupClassifier.Group group : groups) {
      for (ServiceGroupClassifier.Direction direction : group.directions()) {
        shares.addAll(
            WeightedTripAllocator.report(
                direction.candidates(), keptByDirection.getOrDefault(direction.key(), List.of())));
      }
    }
    Set<CancelSlot> brokenTrunks = new HashSet<>();
    Set<CancelSlot> brokenBranches = new HashSet<>();
    if (!derivedParent.isEmpty()) {
      Map<UUID, VehicleDutyPlanner.PlannedTrip> plannedById = new HashMap<>();
      plannedTrips.forEach(trip -> plannedById.put(trip.tripId(), trip));
      Set<UUID> coupledTrunks = new HashSet<>(derivedParent.values());
      Set<UUID> truncatedAll = new HashSet<>(repaired.truncatedTripIds());
      for (UUID truncatedId : truncatedAll) {
        UUID parent = derivedParent.get(truncatedId);
        VehicleDutyPlanner.PlannedTrip trunk =
            plannedById.get(parent != null ? parent : truncatedId);
        if (trunk == null || (parent == null && !coupledTrunks.contains(truncatedId))) {
          continue;
        }
        CancelSlot slot = new CancelSlot(trunk.routeId(), trunk.departureSeconds());
        (parent == null ? brokenTrunks : brokenBranches).add(slot);
      }
    }
    return new Once(
        new Attempt(
            (int) headwaySeconds,
            timetable,
            List.copyOf(dropped),
            List.copyOf(shares),
            conflicts,
            residuals,
            List.copyOf(shifts),
            yields,
            Map.copyOf(intervalByGroup),
            PhasePlanner.interleaves(grids),
            phases.notes(),
            PhasePlanner.residues(
                gridGroups,
                intervalByGroup,
                prepared.runByRoute(),
                options.dutyLimits().turnaround(),
                inPlaceTurnbackRoutes(prepared.operationPlans(), prepared.legs())),
            phases,
            Map.copyOf(slotByCode),
            Set.copyOf(pinnedCodes),
            java.util.stream.Stream.concat(couplingNotes.stream(), pinNotes.stream()).toList()),
        brokenTrunks,
        brokenBranches);
  }

  /**
   * 顺推判据用的停站压缩：最短停站来自参数，档案里多算的停站开销来自走行模型（编表时加进停站的就是它），车门下限取运行时的缺省， 换模加时按各 route
   * 的停靠表推算（与时分档案加进停站的是同一份）。
   */
  private static ConflictAbsorption.Recovery recoveryOf(
      TimetableBuildOptions options, BuildInput input) {
    Map<UUID, List<Integer>> switches = new HashMap<>();
    for (RouteInput route : input.routes()) {
      int[] plan =
          org.fetarute.fetaruteTCAddon.dispatcher.route.RouteStopExtraDwell.plan(route.stops());
      if (java.util.Arrays.stream(plan).anyMatch(seconds -> seconds > 0)) {
        switches.put(route.routeId(), java.util.Arrays.stream(plan).boxed().toList());
      }
    }
    return new ConflictAbsorption.Recovery(
        options.recoveryMinDwellSeconds(),
        input.runTimeModel().stationStopOverheadSeconds(),
        ConflictAbsorption.Recovery.RUNTIME_DOOR_FLOOR_SECONDS,
        switches);
  }

  /** 名义运营班次、邻表与已选补车共同构成出库压力；每次完整相位试排重新建账。 */
  private static TimetableDepotAllocator depotAllocator(
      Prepared prepared,
      List<VehicleDutyPlanner.PlannedTrip> trips,
      BuildInput input,
      int separation) {
    Map<UUID, TimetableConflictChecker.RouteProfile> profiles = new HashMap<>(prepared.profiles());
    List<TimetableConflictChecker.Movement> movements = new ArrayList<>();
    List<TimetableConflictChecker.Stay> stays = new ArrayList<>();
    for (VehicleDutyPlanner.PlannedTrip trip : trips) {
      movements.add(
          new TimetableConflictChecker.Movement(
              trip.tripCode(), trip.routeId(), trip.departureSeconds()));
    }
    for (NeighborTimetable neighbor : input.neighbors()) {
      neighbor.profiles().forEach(profiles::putIfAbsent);
      movements.addAll(neighbor.movements());
      stays.addAll(neighbor.stays());
    }
    // 班次与出入库走行用的是车型变体 ID，线路归属登记在基础 route 上。
    return new TimetableDepotAllocator(
        prepared.graphIndex(),
        profiles,
        movements,
        stays,
        separation,
        route -> Optional.ofNullable(input.lineByRoute().get(prepared.baseOf(route))));
  }

  /** 派车眼里这条 route 一班允许的车型：加解编派生的方案优先，否则按车型份额。 */
  private static List<VehicleDutyPlanner.ConsistOption> tripConsists(
      BuildInput input, Prepared prepared, TimetableRoutePlan plan) {
    return prepared
        .couplings()
        .consistOptions(plan.routeId(), plan.totalRunSeconds())
        .orElseGet(() -> consistOptions(input, prepared, plan.routeId()));
  }

  /**
   * 这条 route 的车在 {@code node} 上的出库（{@code outbound}）或回库走行段，与派车同口径：按这条 route 允许的车型找按该车型算的段，
   * 不区分车型时只认不区分车型的段。别的车型（例如同站别的线）的走行段不算——那条线的车库这辆车进不去。加解编的编组键与派车一样 当作不区分车型（{@code
   * Legs#withAliases}）。
   */
  /** 定点挪动顺着车辆接续传下去要用的端点规则（{@link PinnedSlots.Turns}）；没有定点时不算。 */
  private static PinnedSlots.Turns turnsOf(
      BuildInput input, Prepared prepared, TimetableBuildOptions options) {
    if (options.schedule().pins().isEmpty()) {
      return PinnedSlots.Turns.NONE;
    }
    Map<UUID, String> origin = new HashMap<>();
    Map<UUID, String> terminal = new HashMap<>();
    Map<UUID, String> pool = new HashMap<>();
    Map<UUID, Integer> turnaround = new HashMap<>();
    Set<UUID> swaps = new HashSet<>();
    List<TimetableRoutePlan> plans = prepared.operationPlans();
    for (int i = 0; i < plans.size(); i++) {
      TimetableRoutePlan plan = plans.get(i);
      UUID routeId = plan.routeId();
      origin.put(routeId, plan.originNodeId());
      terminal.put(routeId, plan.terminalNodeId());
      pool.put(routeId, input.poolOf(routeId));
      turnaround.put(routeId, options.dutyLimits().turnaround().secondsFor(routeId));
      if (prepared.operations().get(i).endsAtDepot()
          || legFor(input, prepared, plan, plan.terminalNodeId(), false).isPresent()) {
        swaps.add(routeId);
      }
    }
    return new PinnedSlots.Turns(
        origin, terminal, pool, turnaround, swaps, options.dutyLimits().maxIdleSeconds());
  }

  private static Optional<VehicleDutyPlanner.Leg> legFor(
      BuildInput input, Prepared prepared, TimetableRoutePlan plan, String node, boolean outbound) {
    VehicleDutyPlanner.Legs legs = prepared.legs().withAliases(prepared.couplings().consists());
    List<VehicleDutyPlanner.ConsistOption> consists = tripConsists(input, prepared, plan);
    List<Optional<String>> keys =
        consists.isEmpty()
            ? List.of(Optional.empty())
            : consists.stream().map(option -> Optional.of(option.key())).toList();
    for (Optional<String> consist : keys) {
      Optional<VehicleDutyPlanner.Leg> leg =
          outbound ? legs.createLegAt(node, consist) : legs.returnLegAt(node, null, consist);
      if (leg.isPresent()) {
        return leg;
      }
    }
    return Optional.empty();
  }

  /** 一班允许的车型、份额权重与按各车型跑的全程时分（按方案里的顺序）。不区分车型、或这条 route 一个车型的时分都算不出来时为空。 */
  private static List<VehicleDutyPlanner.ConsistOption> consistOptions(
      BuildInput input, Prepared prepared, UUID routeId) {
    Map<String, UUID> variantIds = prepared.variants().getOrDefault(routeId, Map.of());
    if (variantIds.isEmpty()) {
      return List.of();
    }
    List<VehicleDutyPlanner.ConsistOption> options = new ArrayList<>();
    for (ConsistFleet.Share share : input.fleet().sharesFor(routeId)) {
      UUID variantId = variantIds.get(share.key());
      if (variantId != null) {
        options.add(
            new VehicleDutyPlanner.ConsistOption(
                share.key(), share.weight(), prepared.runByRoute().get(variantId)));
      }
    }
    return List.copyOf(options);
  }

  /** 用同一份归属信息与计划组一张表；临时表与成品表只差 trips/duties。 */
  private static Timetable timetableOf(
      BuildInput input,
      TimetableBuildOptions options,
      Prepared prepared,
      List<TimetableTrip> trips,
      List<VehicleDuty> duties,
      Instant builtAt) {
    return new Timetable(
        input.timetableId(),
        input.companyId(),
        input.operatorId(),
        input.lineId(),
        input.code(),
        input.name(),
        TimetableStatus.DRAFT,
        options.zoneId(),
        options.serviceStartSecondOfDay(),
        options.serviceEndSecondOfDay(),
        prepared.plans(),
        trips,
        duties,
        input.notes(),
        builtAt,
        builtAt);
  }

  /** 相对秒换成当日秒数（取模）。 */
  private static int secondOfDay(int relativeSeconds, TimetableBuildOptions options) {
    return Math.floorMod(
        options.serviceStartSecondOfDay() + relativeSeconds, TimetableTrip.SECONDS_PER_DAY);
  }

  /**
   * 严格模式取消让不掉的班次：目标间隔仍有不可吸收残余时，按残余的顺序取第一处里要等的那一班（后车）取消， 在已选的相位上重排一次、重新分类，直到干净，或取消数到 {@code
   * --max-cancel} 上限。
   *
   * <p>取消了一班、让不掉的残余却没有变少（预订表重新落位后冲突挪到了别处，甚至更多）时，这一班放回去、记为无效，换下一处去试；
   * 试的次数至多是上限的两倍。不这样做，取消会一路越撤越多，最后报的残余比没撤时还多。
   *
   * <p>取消发生在相位选择与完整窗口复核之后，不回头改相位：相位层"不拿取消换冲突"比的是相位候选，这里是相位定了之后拿掉个别班次。
   * 后车不是运营班次（出入库走行、待命、带客回库班）或已经取消过时取对方；两方都取消不了就看下一处，一处也取消不了就停。
   *
   * <p>取消是局部的：派车与不取消时相同，只从卷进去的交路里拿掉这一班（{@link DutyWithdrawal}），别的交路的车辆、班次与出入库不动。
   * 那辆车在被取消那一班的起点上待命到下一班，接不上就在那里回库、后面的班次另出一辆车；因此另外截断的班次照常记为取消，一并计入上限。
   * 预订表照常在整张表上重新落位（只改时刻、不换车）。不重新派车：重新派车会让一大片交路换车，端点上的冲突整片换一批。
   */
  private Cancellation cancelUnabsorbable(
      Prepared prepared,
      TimetableBuildOptions options,
      BuildInput input,
      Instant builtAt,
      Attempt target,
      int limit) {
    // 报告用目标间隔原表里的车次号：每取消一班就重新编号，取消时的车次号前后对不上。
    Map<CancelSlot, String> targetCodes = new HashMap<>();
    target.slotByCode().forEach((code, slot) -> targetCodes.put(slot, code));
    Map<CancelSlot, Cancelled> cancelled = new LinkedHashMap<>();
    Set<CancelSlot> useless = new HashSet<>();
    Attempt current = target;
    int count = 0;
    int tries = 0;
    boolean stop = false;
    while (!stop && !current.clean() && cancelled.size() < limit && tries < 2 * limit) {
      // 接下来依次要试的几班：前一班无效放回时，下一班就是排除它之后的那一班，不依赖它的试排结果，可以一起编。
      Set<CancelSlot> skip = new HashSet<>(cancelled.keySet());
      skip.addAll(useless);
      List<Cancelled> batch = new ArrayList<>();
      int room = Math.min(pool.threads(), 2 * limit - tries);
      while (batch.size() < room) {
        Optional<Cancelled> found = nextCancel(current, skip);
        if (found.isEmpty()) {
          break;
        }
        skip.add(found.get().slot());
        batch.add(
            new Cancelled(
                found.get().slot(),
                targetCodes.getOrDefault(found.get().slot(), found.get().tripCode()),
                found.get().cause()));
      }
      if (batch.isEmpty()) {
        break;
      }
      Set<CancelSlot> kept = Set.copyOf(cancelled.keySet());
      List<BuildPool.Evaluated<Optional<Attempt>>> retried =
          pool.evaluate(
              batch,
              next -> {
                Set<CancelSlot> withNext = new HashSet<>(kept);
                withNext.add(next.slot());
                progress.trial(TimetableBuildProgress.Trial.CANCEL_RETRY);
                try {
                  return Optional.of(
                      attemptAtPhases(
                          prepared,
                          options,
                          input,
                          builtAt,
                          target.phases(),
                          Set.copyOf(withNext)));
                } catch (BuildFailure failure) {
                  return Optional.empty();
                }
              });
      // 按原顺序逐班消费：第一班有效就收下、本批其余作废（取消的班次变了，它们的试排不再对应）；无效的放回、看下一班。
      stop = true;
      for (int i = 0; i < batch.size(); i++) {
        Cancelled next = batch.get(i);
        tries++;
        detail("严格模式取消让不掉的班次：第 " + (cancelled.size() + 1) + " 班（上限 " + limit + "）");
        Optional<Attempt> one = retried.get(i).get();
        if (one.isEmpty()) {
          break;
        }
        if (one.get().unabsorbable().size() >= current.unabsorbable().size()) {
          useless.add(next.slot());
          if (i == batch.size() - 1) {
            stop = false;
          }
          continue;
        }
        cancelled.put(next.slot(), next);
        current = one.get();
        count = Math.max(cancelled.size(), current.dropped().size() - target.dropped().size());
        stop = count > limit;
        break;
      }
    }
    return new Cancellation(current, List.copyOf(cancelled.values()), count);
  }

  /** 试更小的间隔裕量时每次往下减几秒。 */
  static final int SEPARATION_PROBE_STEP = 5;

  /** 试更小的间隔裕量时最多试几档（不含当前值）：每档一次周期层求解加一次完整构建。 */
  static final int SEPARATION_PROBE_LIMIT = 5;

  /**
   * 目标表排不开时，按 {@link #SEPARATION_PROBE_STEP} 秒一档往下试间隔裕量，找最大的、周期层排得开且按它完整构建没有让不掉冲突的那一档，
   * 写进失败原因，由用户决定要不要用。不自动降：裕量是用户定的运行余量，降不降是取舍，不是编表能替用户做的事。
   *
   * <p>每档都完整构建，以它为准：周期层每圈剩一两班接不上车时，派车照样排得开，只看周期层会漏掉能用的那一档。 全部试完都不行时也给一句，说明冲突与裕量无关。裕量已经是一档以内时不试。
   */
  private Optional<String> separationHint(
      Prepared prepared, BuildInput input, TimetableBuildOptions options, Instant builtAt) {
    int current = separationOf(options);
    if (current < SEPARATION_PROBE_STEP) {
      return Optional.empty();
    }
    stage(TimetableBuildProgress.Stage.SEPARATION);
    List<SeparationTry> trials = new ArrayList<>();
    int lowest = current;
    for (int step = 1; step <= SEPARATION_PROBE_LIMIT; step++) {
      int candidate = current - step * SEPARATION_PROBE_STEP;
      if (candidate < 0) {
        break;
      }
      lowest = candidate;
      TimetableBuildOptions at = options.withSeparation(Duration.ofSeconds(candidate));
      for (Supplier<Solved> regime : solveRegimes(prepared, input, at)) {
        trials.add(new SeparationTry(candidate, at, regime));
      }
    }
    // 一批编线程数个，按原顺序第一个干净的就是答案；同一批里排在它后面的白编，批不大于线程数，浪费有限。
    for (int from = 0; from < trials.size(); from += pool.threads()) {
      List<SeparationTry> batch =
          trials.subList(from, Math.min(trials.size(), from + pool.threads()));
      List<BuildPool.Evaluated<Boolean>> clean =
          pool.evaluate(batch, trial -> separationClean(trial, builtAt));
      for (int i = 0; i < clean.size(); i++) {
        if (clean.get(i).get()) {
          return Optional.of(
              TimetableBuildReportText.separationHint(current, batch.get(i).separation()));
        }
      }
    }
    return lowest < current
        ? Optional.of(TimetableBuildReportText.separationHintNone(current, lowest))
        : Optional.empty();
  }

  /** 间隔裕量探测的一档里的一份制式候选。 */
  private record SeparationTry(
      int separation, TimetableBuildOptions options, Supplier<Solved> regime) {}

  /** 按这一档裕量完整构建一次，有没有让不掉的冲突；这一档本身构建不了时当作不行。 */
  private boolean separationClean(SeparationTry trial, Instant builtAt) {
    detail("间隔裕量试 " + trial.separation() + "s");
    try {
      Solved solved = trial.regime().get();
      return attemptAtPhases(
              solved.prepared(), trial.options(), solved.input(), builtAt, solved.phases())
          .clean();
    } catch (BuildFailure | PeriodicSolver.HyperperiodTooLong failure) {
      return false;
    }
  }

  /** 下一班取消谁：按残余的顺序，先后车、再对方；只取本表的运营班次，已经取消过的与定点班次跳过。 */
  private static Optional<Cancelled> nextCancel(Attempt attempt, Set<CancelSlot> done) {
    for (ConflictAbsorption.Residual residual : attempt.unabsorbable()) {
      TimetableConflictChecker.Conflict conflict = residual.conflict();
      List<String> codes = new ArrayList<>(2);
      codes.add(residual.mover());
      if (residual.mover().equals(conflict.second()) && conflict.firstOwner().isEmpty()) {
        codes.add(conflict.first());
      } else if (residual.mover().equals(conflict.first()) && conflict.secondOwner().isEmpty()) {
        codes.add(conflict.second());
      }
      for (String code : codes) {
        CancelSlot slot = attempt.slotByCode().get(code);
        if (slot != null && !done.contains(slot) && !attempt.pinnedCodes().contains(code)) {
          return Optional.of(new Cancelled(slot, code, residual));
        }
      }
    }
    return Optional.empty();
  }

  /** 取消了哪几班、各因哪一处：报告里一班一行。 */
  private static List<String> describeCancelled(
      List<Cancelled> cancelled, Prepared prepared, TimetableBuildOptions options) {
    List<String> out = new ArrayList<>(cancelled.size());
    for (Cancelled item : cancelled) {
      out.add(
          TimetableBuildReportText.describeCancelled(
              item.tripCode(),
              planOf(prepared.operationPlans(), item.slot().routeId()).routeCode(),
              clock(secondOfDay(item.slot().nominalSeconds(), options)),
              item.cause(),
              options.serviceStartSecondOfDay()));
    }
    return out;
  }

  /** 取消上限的写法：给了 {@code --max-cancel} 就照写，没给时写明是按运营班次的比例算出来的。 */
  private static String cancelLimitText(TimetableBuildOptions options, int limit, int trips) {
    return options.maxCancelByShare()
        ? "上限 "
            + limit
            + " 班，即运营班次 "
            + trips
            + " 班的 "
            + TimetableBuildOptions.DEFAULT_MAX_CANCEL_PERCENT
            + "%，可用 --max-cancel 改"
        : "--max-cancel " + limit;
  }

  /** 目标间隔排不开的失败结果：带上目标间隔的冲突、残余与第三层的相位说明。 */
  private static TimetableBuildResult targetFailure(
      String reason, Prepared prepared, Attempt target) {
    return TimetableBuildResult.failure(
        reason,
        prepared.infeasible(),
        target.conflicts().conflicts(),
        target.absorbable(),
        target.unabsorbable(),
        target.phaseNotes());
  }

  /** 首站带 CRET 指令：列车在这条 route 上从车库实体化，与发车侧 {@code startsWithCret} 判法一致。 */
  private static boolean startsAtDepot(List<RouteStop> stops) {
    return stops != null
        && !stops.isEmpty()
        && SpawnDirectiveParser.findDirectiveTarget(stops.get(0), "CRET").isPresent();
  }

  /** 以销毁收尾：与运行时 {@link RouteDefinition#resolveMode} 同一条规则。 */
  private static boolean endsAtDepot(List<RouteStop> stops) {
    return RouteDefinition.resolveMode(stops) == RouteLifecycleMode.DESTROY_AFTER_TERM;
  }

  private static TimetableRoutePlan planOf(List<TimetableRoutePlan> plans, UUID routeId) {
    for (TimetableRoutePlan plan : plans) {
      if (plan.routeId().equals(routeId)) {
        return plan;
      }
    }
    throw new IllegalStateException("route plan 缺失: " + routeId);
  }

  /** 相对窗口起点的秒数 → 相对服务日的秒数。duty 不取模：跨零点用超出一天的值表示，早于零点用负数表示。 */
  private static int shiftToServiceDay(int relativeSeconds, TimetableBuildOptions options) {
    return options.serviceStartSecondOfDay() + relativeSeconds;
  }

  private static String clock(int secondOfDay) {
    return TimetableCsvExporter.clock(secondOfDay);
  }

  /**
   * 折返时间表：{@code --turnaround} 显式覆盖时原样保留，否则按各 route 终到停站建表。
   *
   * <p>秒数由 {@link TimetableTimingCalculator#terminalStopSeconds} 算出，与行程时分同一套规则—— 折返不是新造的事实，就是 route
   * 定义里那个终到 dwell，车站终到再加停站开销（运行时车在开门计时结束后才进入待命）。
   */
  private static TurnaroundTable resolveTurnarounds(
      BuildInput input, TimetableBuildOptions options) {
    TurnaroundTable requested = options.dutyLimits().turnaround();
    if (requested.fixed()) {
      return requested;
    }
    int fallback = (int) options.defaultDwell().toSeconds();
    Map<UUID, Integer> secondsByRoute = new LinkedHashMap<>();
    for (RouteInput route : input.sortedRoutes()) {
      int seconds =
          TimetableTimingCalculator.terminalStopSeconds(
              input.graph(), input.runTimeModel(), route.definition(), route.stops(), fallback);
      secondsByRoute.put(route.routeId(), seconds);
      // 车型变体的折返与基础 route 相同：终到站 dwell 加车站停站开销，都与车型无关。
      for (ConsistFleet.Share share : input.fleet().sharesFor(route.routeId())) {
        secondsByRoute.put(ConsistFleet.variantId(route.routeId(), share.key()), seconds);
      }
    }
    return TurnaroundTable.ofSeconds(secondsByRoute, fallback);
  }

  /**
   * 各方向沿途的合流点，供第二层在<b>共用区段</b>上交错，而不只是共用起点。
   *
   * <p>合流键是 {@code 本站台组→下一站台组}：带上走向才能把反向的车排除掉（乘客在某站等的是往一个方向去的车）；
   * 用站台组而不是具体股道，是因为同一车站同方向的几股道对乘客可以互换。终到站不产生键——乘客不在终点上车。
   *
   * <p>取方向里第一条候选 route 作代表（候选已按 code 排序，确定）：同方向的几条 route 走同一条线，差别只在 停不停某些小站，用哪条算合流点都一样。
   */
  private static Map<String, List<PhasePlanner.StopCall>> stopCalls(
      List<ServiceGroupClassifier.Group> groups, Prepared prepared) {
    Map<String, List<PhasePlanner.StopCall>> out = new LinkedHashMap<>();
    for (ServiceGroupClassifier.Group group : groups) {
      for (ServiceGroupClassifier.Direction direction : group.directions()) {
        // 挨个试这个方向下的 route，而不是只认第一条：第一条要是没有投影（被筛掉、或者时分算不出来），
        // 整个方向就没有合流点了，于是它在相位第二层上与谁都对不上、也不会有任何提示。
        TimetableConflictChecker.RouteProfile profile = null;
        for (UUID routeId : direction.routeIds()) {
          TimetableConflictChecker.RouteProfile candidate = prepared.profiles().get(routeId);
          if (candidate != null) {
            profile = candidate;
            break;
          }
        }
        if (profile == null) {
          continue;
        }
        List<PhasePlanner.StopCall> calls = new ArrayList<>();
        int count = Math.min(profile.platforms().size(), profile.stops().size());
        for (int i = 0; i < count; i++) {
          String here = groupAt(profile, i);
          if (here.isBlank()) {
            continue;
          }
          String next = "";
          for (int j = i + 1; j < count && next.isBlank(); j++) {
            String candidate = groupAt(profile, j);
            next = candidate.equals(here) ? "" : candidate;
          }
          if (next.isBlank()) {
            continue; // 终到站：乘客不在这里上车，不算合流点
          }
          calls.add(
              new PhasePlanner.StopCall(
                  here + "→" + next, profile.stops().get(i).departureOffsetSeconds()));
        }
        if (!calls.isEmpty()) {
          out.put(direction.key(), List.copyOf(calls));
        }
      }
    }
    return out;
  }

  private static String groupAt(TimetableConflictChecker.RouteProfile profile, int index) {
    TimetableConflictChecker.Platform platform = profile.platforms().get(index);
    return platform.absent() ? "" : platform.group();
  }

  /** 每组只留下至少有一条 route 真正上网格的方向。 */
  private static List<ServiceGroupClassifier.Group> gridGroupsOf(
      List<ServiceGroupClassifier.Group> groups, Set<UUID> gridRoutes) {
    List<ServiceGroupClassifier.Group> out = new ArrayList<>(groups.size());
    for (ServiceGroupClassifier.Group group : groups) {
      List<ServiceGroupClassifier.Direction> directions = new ArrayList<>();
      for (ServiceGroupClassifier.Direction direction : group.directions()) {
        if (direction.routeIds().stream().anyMatch(gridRoutes::contains)) {
          directions.add(direction);
        }
      }
      out.add(new ServiceGroupClassifier.Group(group.name(), directions, group.pureLegs()));
    }
    return List.copyOf(out);
  }

  /**
   * 终到正线折返点的运营 route：末站 TERMINATE，终点节点是正线折返点（{@link RouteTerminals#isMainlineTurnback}）。
   *
   * <p>车在那里停着会挡住同一股道的后车：往返对要把锚点放在这一端（见 {@link PhasePlanner#isForward}），后面的层也不往这里加等待。
   */
  static Set<UUID> mainlineTurnbackRoutes(List<TimetableRoutePlan> operationPlans) {
    Set<UUID> routes = new HashSet<>();
    for (TimetableRoutePlan plan : operationPlans) {
      if (terminates(plan) && RouteTerminals.isMainlineTurnback(plan.terminalNodeId())) {
        routes.add(plan.routeId());
      }
    }
    return Set.copyOf(routes);
  }

  /**
   * 车只能在终点原地折返的运营 route：{@link #mainlineTurnbackRoutes} 加上末站 TERMINATE、终点没有 RETURN 线路的那些（{@link
   * VehicleDutyPlanner.Legs#returnLegAt}，与派车器的"能不能回库"同一口径）。
   *
   * <p>没有出入库线路的站台（例如某条线在别的线终点上折返、那里没有本线的车库）车停着不挡人，但一辆车也调不走，只能等本端的下一班：周期余量若堆在那一端，
   * 超出闲置上限就接不上。往返对因此锚在这一端（余量落在另一端）并整对平移，第三层不能单独动一边。
   */
  static Set<UUID> inPlaceTurnbackRoutes(
      List<TimetableRoutePlan> operationPlans, VehicleDutyPlanner.Legs legs) {
    Set<UUID> routes = new HashSet<>(mainlineTurnbackRoutes(operationPlans));
    for (TimetableRoutePlan plan : operationPlans) {
      if (terminates(plan) && legs.returnLegAt(plan.terminalNodeId()).isEmpty()) {
        routes.add(plan.routeId());
      }
    }
    return Set.copyOf(routes);
  }

  /** 末站是 TERMINATE（终到后待命复用），而不是销毁收尾的回库形态。 */
  private static boolean terminates(TimetableRoutePlan plan) {
    List<TimetableStop> stops = plan.stops();
    return !stops.isEmpty()
        && stops.get(stops.size() - 1).passType() == RouteStopPassType.TERMINATE;
  }

  /** 按车接续：被接 route → 喂车 route，交给派车器优先选喂车方向的车。 */
  private static Map<UUID, Set<UUID>> preferredFeeders(List<PhasePlanner.Connection> connections) {
    Map<UUID, Set<UUID>> out = new HashMap<>();
    for (PhasePlanner.Connection connection : connections) {
      for (UUID fed : connection.fedRoutes()) {
        out.computeIfAbsent(fed, key -> new HashSet<>()).addAll(connection.feederRoutes());
      }
    }
    return out;
  }

  private record OperationPlan(RouteInput route, boolean startsAtDepot, boolean endsAtDepot) {}

  /** 第 1 步的产物：与 headway 无关的一切。 */
  private record Prepared(
      UUID timetableId,
      TimetableConflictChecker.GraphIndex graphIndex,
      List<TimetableRoutePlan> plans,
      List<OperationPlan> operations,
      List<TimetableRoutePlan> operationPlans,
      List<WeightedTripAllocator.Candidate> candidates,
      VehicleDutyPlanner.Legs legs,
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
      List<TimetableBuildResult.InfeasibleRoute> infeasible,
      ServiceGroupClassifier.Classification classification,
      Set<UUID> passengerLegs,
      Map<UUID, Integer> runByRoute,
      BlockingTimes.Trajectories trajectories,
      Map<UUID, Map<String, UUID>> variants,
      Map<UUID, UUID> baseByVariant,
      CouplingPlanner.Analysis couplings) {

    /** route 按某个车型跑的变体 ID；不区分车型、或这个车型不能跑这条 route 时就是 route 本身。 */
    UUID variantOf(UUID routeId, Optional<String> consist) {
      if (consist.isEmpty()) {
        return routeId;
      }
      return variants.getOrDefault(routeId, Map.of()).getOrDefault(consist.get(), routeId);
    }

    /** 变体 → 基础 route；不是变体时就是它自己。 */
    UUID baseOf(UUID routeId) {
      return baseByVariant.getOrDefault(routeId, routeId);
    }

    /** 这几条 route 的全程走行各加 {@code seconds}，其余不变：结构预筛估喂车多停的效果，只有走行进相位层。 */
    Prepared withExtraRun(Collection<UUID> routeIds, int seconds) {
      Map<UUID, Integer> run = new HashMap<>(runByRoute);
      for (UUID routeId : routeIds) {
        run.computeIfPresent(routeId, (id, value) -> value + seconds);
      }
      return new Prepared(
          timetableId,
          graphIndex,
          plans,
          operations,
          operationPlans,
          candidates,
          legs,
          profiles,
          infeasible,
          classification,
          passengerLegs,
          Map.copyOf(run),
          trajectories,
          variants,
          baseByVariant,
          couplings);
    }
  }

  /** 在第 {@code at} 个停靠点多停 {@code seconds} 秒：没配 dwell 的按缺省停站算起。 */
  static RouteInput heldAt(RouteInput route, int at, int seconds, Duration defaultDwell) {
    if (seconds <= 0 || at < 0 || at >= route.stops().size()) {
      return route;
    }
    List<RouteStop> stops = new ArrayList<>(route.stops());
    RouteStop stop = stops.get(at);
    int dwell = stop.dwellSeconds().orElse((int) defaultDwell.toSeconds());
    stops.set(
        at,
        new RouteStop(
            stop.routeId(),
            stop.sequence(),
            stop.stationId(),
            stop.waypointNodeId(),
            Optional.of(dwell + seconds),
            stop.passType(),
            stop.notes()));
    return route.withStops(stops);
  }

  // ---------------------------------------------------------------- 周期层求解

  /** 放宽间隔时最多做几次完整构建：周期层判可行之后才完整构建，正常一两次就够，这里只防病态输入。 */
  static final int RELAX_FULL_BUILDS = 12;

  /** 逐组收紧时每组最多做几次完整构建。 */
  static final int TIGHTEN_FULL_BUILDS_PER_GROUP = 2;

  /** 放宽间隔的扫描步长取最小组间隔的这么多分之一（至少 1 秒）：周期层每次只要毫秒到一秒，步长可以很细。 */
  static final int RELAX_STEPS_PER_INTERVAL = 60;

  /** 放宽与收紧合计最多问几次周期层：到上限就停在当前可行解（或报找不到），按次数计，结果确定。放宽之后超周期常到小时级， 一次要几秒，这个数决定了排不开时最久等多久。 */
  static final int RELAX_SOLVE_LIMIT = 32;

  /** 粗档步长取最小组间隔的这么多分之一：先粗找到第一个排得开的，再在那一档里按细档补查。 */
  static final int RELAX_COARSE_STEPS_PER_INTERVAL = 6;

  /**
   * 周期层求解的结果与它对应的准备。
   *
   * @param prepared 周期层给了多停时是按多停改过停站、重算过时分的一份；否则就是传入的那份
   * @param input 同上
   * @param phases 交给网格与派车的相位（跨组接班作为接续偏好）
   * @param solution 求解器的解
   */
  private record Solved(
      Prepared prepared,
      BuildInput input,
      PhasePlanner.Phases phases,
      PeriodicSolver.Solution solution) {}

  /**
   * 放宽之后的结果。
   *
   * @param attempt 选中的完整构建
   * @param prepared 它用的准备
   * @param notes 搜索与收紧的说明
   */
  private record Relaxed(Attempt attempt, Prepared prepared, List<String> notes) {}

  /**
   * 周期层求解的输入：每组每方向一条周期流（SWRR 一轮的格序），每条运营 route 一份形状（足迹、可多停车站、端点与折返）。
   *
   * <p>可多停的车站是途中停车的车站（有站台、TERMINATE 与首站除外）：运行时 AutoStation 在这里按早到等点扣车，表上加长的停站兑现得了。
   * 区间停车点不多停——那里没有站台，车停着挡后车。
   */
  private PeriodicSolver.Input solverInput(
      Prepared prepared, BuildInput input, TimetableBuildOptions options) {
    return solverInput(
        prepared, input, options, options.feederHoldLimitSeconds(), Map.of(), Set.of());
  }

  /**
   * 同上，另给多停上限与参考相位：分时段编表里，非高峰制式沿用高峰制式定下的多停（多停是 route 的停站，全天一份），只重选相位，
   * 并以高峰制式的相位为参考（相同的解里偏离最小的优先），交界处少一些跳变。
   */
  private PeriodicSolver.Input solverInput(
      Prepared prepared,
      BuildInput input,
      TimetableBuildOptions options,
      int holdLimit,
      Map<String, Integer> preferred,
      Set<String> keepPreferred) {
    Set<UUID> gridRoutes = new HashSet<>();
    Map<UUID, OperationPlan> operationByRoute = new HashMap<>();
    for (int i = 0; i < prepared.operationPlans().size(); i++) {
      UUID routeId = prepared.operationPlans().get(i).routeId();
      if (prepared.profiles().containsKey(routeId)) {
        gridRoutes.add(routeId);
        operationByRoute.put(routeId, prepared.operations().get(i));
      }
    }
    List<ServiceGroupClassifier.Group> groups =
        gridGroupsOf(prepared.classification().groups(), gridRoutes);
    int separation = separationOf(options);
    Set<UUID> fast =
        RapidCatchUp.fastRoutes(groups, prepared.profiles(), prepared.graphIndex(), separation);
    Map<String, List<PhasePlanner.StopCall>> calls = stopCalls(groups, prepared);
    TimetableConflictChecker.Footprints footprints =
        new TimetableConflictChecker.Footprints(prepared.graphIndex());
    Map<UUID, RouteInput> routes = new HashMap<>();
    for (RouteInput route : input.routes()) {
      routes.put(route.routeId(), route);
    }
    Set<UUID> blocking = mainlineTurnbackRoutes(prepared.operationPlans());
    List<PeriodicSolver.Flow> flows = new ArrayList<>();
    Map<UUID, PeriodicSolver.Shape> shapes = new HashMap<>();
    for (ServiceGroupClassifier.Group group : groups) {
      int interval = options.intervalFor(group.name());
      for (ServiceGroupClassifier.Direction direction : group.directions()) {
        IntPredicate eligible = index -> gridRoutes.contains(direction.routeIds().get(index));
        List<UUID> cycle = new ArrayList<>();
        for (WeightedTripAllocator.Allocation allocation :
            WeightedTripAllocator.allocate(
                direction.candidates(),
                weightCycle(direction.candidates(), eligible),
                WeightedTripAllocator.byIndex(eligible))) {
          cycle.add(direction.routeIds().get(allocation.candidateIndex()));
        }
        if (cycle.isEmpty()) {
          continue;
        }
        flows.add(
            new PeriodicSolver.Flow(
                direction.key(),
                group.name(),
                interval,
                cycle,
                cycle.stream().anyMatch(fast::contains)));
        for (UUID routeId : cycle) {
          shapes.computeIfAbsent(
              routeId,
              id ->
                  shapeOf(
                      planOf(prepared.operationPlans(), id),
                      operationByRoute.get(id),
                      prepared,
                      Optional.ofNullable(routes.get(id)),
                      input,
                      options,
                      footprints,
                      calls.getOrDefault(direction.key(), List.of()),
                      blocking.contains(id)));
        }
      }
    }
    return new PeriodicSolver.Input(
        flows,
        shapes,
        footprints,
        separation,
        options.dutyLimits().maxIdleSeconds(),
        holdLimit,
        preferred,
        options.dutyLimits().maxTripsPerDuty(),
        neighborCells(input, footprints, options, PeriodicSolver.hyperperiodOf(flows)),
        // 沿用参考相位的制式不整体旋转：转了交界处就接不上上一段。
        preferred.isEmpty() ? options.horizonSeconds() : 0,
        keepPreferred);
  }

  /**
   * 邻表的占用折到周期层：计划窗口里的全部运行与停留都按超周期取模叠到环上。邻表是不可移动的路权事实，周期与我相同或成倍时
   * 叠出来就是它的样子；不成倍时会在环上铺得很密——那正是两份表全天总会在某处相撞的如实反映，交给残余判定与放宽。
   */
  private static List<PeriodicSolver.Fixed> neighborCells(
      BuildInput input,
      TimetableConflictChecker.Footprints footprints,
      TimetableBuildOptions options,
      int hyperperiod) {
    if (input.neighbors().isEmpty() || hyperperiod <= 0) {
      return List.of();
    }
    int from = 0;
    int to = options.horizonSeconds();
    List<PeriodicSolver.Fixed> out = new ArrayList<>();
    for (NeighborTimetable neighbor : input.neighbors()) {
      for (TimetableConflictChecker.Movement movement : neighbor.movements()) {
        TimetableConflictChecker.RouteProfile profile = neighbor.profiles().get(movement.routeId());
        if (profile == null || movement.startSeconds() < from || movement.startSeconds() >= to) {
          continue;
        }
        for (TimetableConflictChecker.Slot cell :
            footprints.of(movement.routeId(), profile).slots()) {
          out.add(new PeriodicSolver.Fixed(cell, movement.startSeconds()));
        }
      }
      for (TimetableConflictChecker.Stay stay : neighbor.stays()) {
        if (stay.to() < from || stay.from() >= to) {
          continue;
        }
        for (TimetableConflictChecker.Slot cell :
            TimetableConflictChecker.staySlots(
                stay.platform(), stay.from(), stay.to(), footprints)) {
          out.add(new PeriodicSolver.Fixed(cell, 0));
        }
      }
    }
    return out;
  }

  /** SWRR 一轮的格数：可上网格候选的权重和除以它们的最大公约数（一轮里每条 route 都恰好轮到目标比例那么多次）。 */
  private static int weightCycle(
      List<WeightedTripAllocator.Candidate> candidates, IntPredicate eligible) {
    long sum = 0L;
    long divisor = 0L;
    for (int i = 0; i < candidates.size(); i++) {
      int weight = candidates.get(i).weight();
      if (weight <= 0 || !eligible.test(i)) {
        continue;
      }
      sum += weight;
      long a = divisor;
      long b = weight;
      while (b != 0) {
        long t = a % b;
        a = b;
        b = t;
      }
      divisor = a;
    }
    return divisor == 0L ? 0 : (int) Math.min(MAX_WEIGHT_CYCLE, sum / divisor);
  }

  /** SWRR 一轮格数的上限：权重写得很零碎时，超周期会被它撑得过大。 */
  static final int MAX_WEIGHT_CYCLE = 24;

  /** 一条运营 route 在周期层的形状。 */
  private static PeriodicSolver.Shape shapeOf(
      TimetableRoutePlan plan,
      OperationPlan operation,
      Prepared prepared,
      Optional<RouteInput> route,
      BuildInput input,
      TimetableBuildOptions options,
      TimetableConflictChecker.Footprints footprints,
      List<PhasePlanner.StopCall> calls,
      boolean blocksAtTerminal) {
    TimetableConflictChecker.RouteProfile profile = prepared.profiles().get(plan.routeId());
    List<TimetableStop> stops = profile.stops();
    // 多停写回 route 的停靠配置，下标与时分档案对齐时才多停；对不上（不该发生）就不多停，宁可少一个松弛量。
    boolean aligned = route.isPresent() && route.get().stops().size() == stops.size();
    List<Integer> holdable = new ArrayList<>();
    for (int i = 1; aligned && i + 1 < stops.size(); i++) {
      TimetableStop stop = stops.get(i);
      boolean station =
          stop.stationCode().isPresent()
              && i < profile.platforms().size()
              && !profile.platforms().get(i).absent();
      if (stop.passType() == RouteStopPassType.STOP && station) {
        holdable.add(i);
      }
    }
    int[] departures = new int[holdable.size()];
    int[] indices = new int[holdable.size()];
    for (int j = 0; j < holdable.size(); j++) {
      indices[j] = holdable.get(j);
      departures[j] = stops.get(holdable.get(j)).departureOffsetSeconds();
    }
    int arrival = stops.isEmpty() ? 0 : stops.get(stops.size() - 1).arrivalOffsetSeconds();
    boolean startsAtDepot = operation != null && operation.startsAtDepot();
    boolean endsAtDepot = operation != null && operation.endsAtDepot();
    Optional<VehicleDutyPlanner.Leg> create =
        startsAtDepot ? Optional.empty() : legFor(input, prepared, plan, plan.originNodeId(), true);
    Optional<VehicleDutyPlanner.Leg> back =
        endsAtDepot
            ? Optional.empty()
            : legFor(input, prepared, plan, plan.terminalNodeId(), false);
    return new PeriodicSolver.Shape(
        plan.routeId(),
        input.poolOf(plan.routeId()),
        footprints.of(plan.routeId(), profile).slots(),
        departures,
        indices,
        arrival,
        profile.origin(),
        profile.terminal(),
        options.dutyLimits().turnaround().secondsFor(plan.routeId()),
        endsAtDepot || back.isPresent(),
        startsAtDepot || create.isPresent(),
        RouteTerminals.isMainlineTurnback(plan.originNodeId()),
        calls,
        create.flatMap(leg -> legShape(leg, prepared, options, footprints, true)),
        back.flatMap(leg -> legShape(leg, prepared, options, footprints, false)),
        blocksAtTerminal,
        plan.originNodeId(),
        plan.terminalNodeId());
  }

  /** 出入库走行在周期层的形状；算不出投影时为空（周期层就看不见它，交给预订表）。 */
  private static Optional<PeriodicSolver.Leg> legShape(
      VehicleDutyPlanner.Leg leg,
      Prepared prepared,
      TimetableBuildOptions options,
      TimetableConflictChecker.Footprints footprints,
      boolean outbound) {
    TimetableConflictChecker.RouteProfile profile = prepared.profiles().get(leg.routeId());
    if (profile == null) {
      return Optional.empty();
    }
    return Optional.of(
        new PeriodicSolver.Leg(
            footprints.of(leg.routeId(), profile).slots(),
            leg.runSeconds(),
            outbound ? options.dutyLimits().turnaround().secondsFor(leg.routeId()) : 0));
  }

  /** 把周期层的解落到准备上：有多停就把多停写进各 route 的停靠（停站加长），重算时分与投影；相位与跨组接班交给网格与派车。 */
  private Solved materialize(
      Prepared prepared,
      BuildInput input,
      TimetableBuildOptions options,
      PeriodicSolver.Solution solution) {
    List<PhasePlanner.Connection> connections = new ArrayList<>();
    solution
        .feeders()
        .forEach(
            (fed, feeders) ->
                connections.add(new PhasePlanner.Connection(List.copyOf(feeders), List.of(fed))));
    List<String> notes = new ArrayList<>();
    notes.add(solution.describe());
    BuildInput held = input;
    if (!solution.holds().isEmpty()) {
      Map<UUID, String> codes = routeCodes(prepared);
      List<RouteInput> routes = new ArrayList<>(input.routes().size());
      for (RouteInput route : input.routes()) {
        // 按停靠点顺序：说明的行序与 JVM 的散列种子无关。
        Map<Integer, Integer> holds =
            new TreeMap<>(solution.holds().getOrDefault(route.routeId(), Map.of()));
        RouteInput next = route;
        for (Map.Entry<Integer, Integer> hold : holds.entrySet()) {
          next = heldAt(next, hold.getKey(), hold.getValue(), options.defaultDwell());
          notes.add(
              "周期层多停："
                  + codes.getOrDefault(route.routeId(), route.routeCode())
                  + " 在 "
                  + route.definition().waypoints().get(hold.getKey()).value()
                  + " 多停 "
                  + hold.getValue()
                  + "s（写进停站，运行时按早到等点扣留）");
        }
        routes.add(next);
      }
      held = input.withRoutes(routes);
    }
    Prepared heldPrepared =
        held == input ? prepared : prepare(held, options, new ArrayList<>(), prepared.graphIndex());
    PhasePlanner.Phases phases = new PhasePlanner.Phases(solution.phases(), notes, connections);
    return new Solved(heldPrepared, held, phases, solution);
  }

  /**
   * 周期层定相位与多停。没有运行计划书时全天一个制式，就是一次求解；有分时段频率时按 {@link ServiceRegimes} 切成几个制式，
   * 间隔组合相同的段共用一份相位，网格按段铺（{@link GroupGrid#ofSegments}）。
   *
   * <p>多停全天只有一份，由谁来定是取舍：按累计时长最长的那组（通常是平峰）定，平峰乘客不背高峰的多停，但高峰可能因多停不够排不开；
   * 按最密的那组定，高峰排得开，平峰多停一些。两种各给一份候选，由 {@link #buildOnce} 各完整排一次、按 {@link #REGIME_ORDER} 取更好的。
   */
  private List<Supplier<Solved>> solveRegimes(
      Prepared prepared, BuildInput input, TimetableBuildOptions options) {
    Map<String, Integer> base = new TreeMap<>();
    for (ServiceGroupClassifier.Group group : prepared.classification().groups()) {
      base.put(group.name(), options.intervalFor(group.name()));
    }
    List<ServiceRegimes.Regime> regimes =
        ServiceRegimes.of(
            base,
            options.schedule().bandsByGroup(),
            options.serviceStartSecondOfDay(),
            options.horizonSeconds());
    // 计划书的时段盖住整个窗口时只切出一个制式，但它的间隔不是基准间隔：照样按制式铺格，不能退回基准间隔。
    if (regimes.isEmpty() || (regimes.size() == 1 && regimes.get(0).intervals().equals(base))) {
      return List.of(
          () ->
              materialize(
                  prepared,
                  input,
                  options,
                  PeriodicSolver.solve(solverInput(prepared, input, options))));
    }
    Map<Map<String, Integer>, Integer> durations = new LinkedHashMap<>();
    for (ServiceRegimes.Regime regime : regimes) {
      durations.merge(regime.intervals(), regime.to() - regime.from(), Integer::sum);
    }
    Map<String, Integer> longest =
        durations.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElseThrow();
    Map<String, Integer> densest =
        durations.keySet().stream()
            .max(
                Comparator.comparingDouble(
                    (Map<String, Integer> intervals) ->
                        intervals.values().stream().mapToDouble(seconds -> 1.0D / seconds).sum()))
            .orElseThrow();
    List<Supplier<Solved>> out = new ArrayList<>();
    out.add(() -> solveAround(prepared, input, options, regimes, durations.keySet(), longest));
    if (!densest.equals(longest)) {
      out.add(() -> solveAround(prepared, input, options, regimes, durations.keySet(), densest));
    }
    return out;
  }

  /**
   * 以某一组间隔为主导解全天：它定下多停（多停写进 route 的停站，全天一份），其余间隔组合沿用这份多停、只给间隔变了的组重选相位， 间隔没变的组固定主导相位（交界处的车照常接得上）。
   */
  private Solved solveAround(
      Prepared prepared,
      BuildInput input,
      TimetableBuildOptions options,
      List<ServiceRegimes.Regime> regimes,
      Collection<Map<String, Integer>> combinations,
      Map<String, Integer> dominant) {
    TimetableBuildOptions atDominant = intervalsAt(options, dominant);
    Solved solved =
        materialize(
            prepared,
            input,
            atDominant,
            PeriodicSolver.solve(solverInput(prepared, input, atDominant)));
    Map<Map<String, Integer>, PeriodicSolver.Solution> byIntervals = new LinkedHashMap<>();
    byIntervals.put(dominant, solved.solution());
    for (Map<String, Integer> intervals : combinations) {
      byIntervals.computeIfAbsent(
          intervals,
          key ->
              PeriodicSolver.solve(
                  solverInput(
                      solved.prepared(),
                      solved.input(),
                      intervalsAt(options, key),
                      0,
                      solved.phases().phaseByDirection(),
                      unchangedFlows(solved.prepared(), dominant, key))));
    }
    List<PhasePlanner.Regime> phased = new ArrayList<>();
    List<String> notes = new ArrayList<>(solved.phases().notes());
    for (ServiceRegimes.Regime regime : regimes) {
      PeriodicSolver.Solution solution = byIntervals.get(regime.intervals());
      phased.add(
          new PhasePlanner.Regime(
              regime.from(), regime.to(), regime.intervals(), solution.phases()));
      notes.add(
          "制式 "
              + clock(secondOfDay(regime.from(), options))
              + "–"
              + clock(secondOfDay(regime.to(), options))
              + " "
              + new TreeMap<>(regime.intervals())
              + "："
              + solution.describe());
    }
    return new Solved(
        solved.prepared(),
        solved.input(),
        new PhasePlanner.Phases(
            solved.phases().phaseByDirection(), notes, solved.phases().connections(), phased),
        solved.solution());
  }

  /** 间隔与主导制式相同的组的方向：它们在别的制式里沿用主导相位，交界处的车照常接得上。 */
  private static Set<String> unchangedFlows(
      Prepared prepared, Map<String, Integer> dominant, Map<String, Integer> intervals) {
    Set<String> out = new TreeSet<>();
    for (ServiceGroupClassifier.Group group : prepared.classification().groups()) {
      if (Objects.equals(dominant.get(group.name()), intervals.get(group.name()))) {
        group.directions().forEach(direction -> out.add(direction.key()));
      }
    }
    return out;
  }

  /**
   * 分时段候选的比较：预计丢掉的班次少的好（{@link #expectedLosses}），其次不可吸收残余少，其次写进表的让车总秒数少。
   *
   * <p>不能先比不可吸收残余：严格模式下几处让不掉的冲突撤几班就解决了，派车时整组接不上的候选却一下丢掉上百班，残余反而是零。
   */
  private static final Comparator<Attempt> REGIME_ORDER =
      Comparator.comparingInt(TimetableBuilder::expectedLosses)
          .thenComparingInt(attempt -> attempt.unabsorbable().size())
          .thenComparingLong(
              attempt -> attempt.yields().stream().mapToLong(TripYield::waitSeconds).sum());

  /** 一份候选预计丢掉的班次：已经取消的，加上让不掉的残余里要让的那一方（同一班只算一次）——严格模式取消的就是它们。 */
  private static int expectedLosses(Attempt attempt) {
    Set<String> movers = new HashSet<>();
    for (ConflictAbsorption.Residual residual : attempt.unabsorbable()) {
      movers.add(residual.mover().isBlank() ? residual.conflict().second() : residual.mover());
    }
    return attempt.dropped().size() + movers.size();
  }

  /** 换成某个制式的各组间隔。 */
  private static TimetableBuildOptions intervalsAt(
      TimetableBuildOptions options, Map<String, Integer> intervals) {
    return options.withIntervals(Duration.ofSeconds(minInterval(intervals)), intervals);
  }

  /**
   * 目标间隔排不开（非严格模式）：先只用周期层找各组等比放宽后第一个排得开的间隔，完整构建确认；再逐组往回收紧，同样先问周期层、排得开才完整构建。
   *
   * <p>周期层看得见稳态里的出入库（端点配不上车时）与邻表的一个超周期，看不见首末班；它判可行的间隔完整构建后仍可能有让不掉的冲突，
   * 所以每次都要完整构建确认；它判不可行的间隔不做完整构建——稳态里每圈都撞的冲突，完整构建也让不掉。
   *
   * <p>扫描先粗后细（{@link IntervalSearch#firstFeasible}）：粗档找到第一个排得开的，再在它与上一个粗档之间按细档补查。可行性不随间隔单调，
   * 细档只在这一段里补，是用次数换来的近似；问周期层与完整构建的次数都有上限，结果确定。
   */
  private Optional<Relaxed> relax(
      Prepared prepared,
      BuildInput input,
      TimetableBuildOptions options,
      Instant builtAt,
      Attempt target) {
    stage(TimetableBuildProgress.Stage.RELAX);
    IntervalSearch search = new IntervalSearch(prepared, input, options, builtAt);
    Map<String, Integer> targets = target.intervals();
    int base = minInterval(targets);
    int fine = Math.max(1, base / RELAX_STEPS_PER_INTERVAL);
    int coarse = Math.max(fine, base / RELAX_COARSE_STEPS_PER_INTERVAL);
    IntFunction<Map<String, Integer>> scaled = proportional(targets, coarse);
    // 先找连出入库走行也排得开的间隔；走行在环上怎么都避不开时（它们多半能在运行时让掉），再只看运营班次。
    for (boolean depotClean : new boolean[] {true, false}) {
      search.depotClean = depotClean;
      int from = base + fine;
      for (int full = 0; full < RELAX_FULL_BUILDS; full++) {
        OptionalInt headway =
            search.firstFeasible(from, base * HEADWAY_SEARCH_MAX_MULTIPLIER, scaled, coarse, fine);
        if (headway.isEmpty()) {
          break;
        }
        detail("周期层排得开 " + headway.getAsInt() + "s（目标 " + base + "s），完整构建确认");
        Optional<Relaxed> found = search.confirm(scaled.apply(headway.getAsInt()));
        if (found.isPresent() && found.get().attempt().clean()) {
          return Optional.of(tighten(search, targets, found.get()));
        }
        from = headway.getAsInt() + fine;
      }
    }
    return Optional.empty();
  }

  /**
   * 各组等比放宽到"最小的组 = {@code headway}"。各组目标间隔有较大的公约数 {@code g} 时（最小的组是 {@code g} 的不多几倍，至多一个粗档）， 按
   * {@code g} 的整数倍放宽，各组的比例严格不变，超周期只随倍数线性变大；否则逐组向上取整——比例近似，零碎的间隔组合超周期会很长， 由周期层判为排不开（{@link
   * PeriodicSolver.HyperperiodTooLong}），换下一档。
   */
  static IntFunction<Map<String, Integer>> proportional(Map<String, Integer> targets, int coarse) {
    int base = minInterval(targets);
    int divisor = 0;
    for (int seconds : targets.values()) {
      divisor = gcd(divisor, seconds);
    }
    int unit = divisor <= 0 ? base : base / divisor;
    if (divisor > 0 && unit <= Math.max(1, coarse)) {
      int g = divisor;
      return headway -> {
        int multiple = Math.max(1, (headway + unit - 1) / unit);
        Map<String, Integer> out = new TreeMap<>();
        targets.forEach((group, seconds) -> out.put(group, seconds / g * multiple));
        return out;
      };
    }
    return headway -> {
      Map<String, Integer> out = new TreeMap<>();
      targets.forEach(
          (group, seconds) -> out.put(group, (int) Math.ceil((double) seconds * headway / base)));
      return out;
    };
  }

  private static int gcd(int a, int b) {
    while (b != 0) {
      int t = a % b;
      a = b;
      b = t;
    }
    return Math.abs(a);
  }

  /** 等比放宽之后逐组往回收紧：放宽得多的组先试，从目标间隔起先粗后细往上找周期层排得开的、再完整构建确认，干净的就收下，其余组不动。 每一步收下的表里所有组都不比上一步慢。 */
  private Relaxed tighten(IntervalSearch search, Map<String, Integer> targets, Relaxed relaxed) {
    stage(TimetableBuildProgress.Stage.TIGHTEN);
    Relaxed current = relaxed;
    List<String> notes = new ArrayList<>();
    List<String> order = new ArrayList<>(targets.keySet());
    Map<String, Integer> start = relaxed.attempt().intervals();
    order.sort(
        Comparator.comparingInt(
                (String group) ->
                    targets.get(group) - start.getOrDefault(group, targets.get(group)))
            .thenComparing(Comparator.naturalOrder()));
    for (String group : order) {
      int now = current.attempt().intervals().getOrDefault(group, targets.get(group));
      int fine = Math.max(1, targets.get(group) / RELAX_STEPS_PER_INTERVAL);
      int coarse = Math.max(fine, targets.get(group) / RELAX_COARSE_STEPS_PER_INTERVAL);
      Map<String, Integer> others = current.attempt().intervals();
      IntFunction<Map<String, Integer>> trial =
          seconds -> {
            Map<String, Integer> out = new TreeMap<>(others);
            out.put(group, seconds);
            return out;
          };
      int from = targets.get(group);
      for (int full = 0; full < TIGHTEN_FULL_BUILDS_PER_GROUP; full++) {
        OptionalInt seconds = search.firstFeasible(from, now - 1, trial, coarse, fine);
        if (seconds.isEmpty()) {
          break;
        }
        detail("周期层收紧 " + group + " 到 " + seconds.getAsInt() + "s，完整构建确认");
        Optional<Relaxed> found = search.confirm(trial.apply(seconds.getAsInt()));
        if (found.isPresent() && found.get().attempt().clean()) {
          notes.add("逐组收紧：" + group + " " + now + "s → " + seconds.getAsInt() + "s");
          current = found.get();
          break;
        }
        from = seconds.getAsInt() + fine;
      }
    }
    return new Relaxed(current.attempt(), current.prepared(), List.copyOf(notes));
  }

  /** 放宽、收紧共用的一次间隔搜索：同一组间隔只问一次周期层，问的次数合计有上限。 */
  private final class IntervalSearch {
    private final Prepared prepared;
    private final BuildInput input;
    private final TimetableBuildOptions options;
    private final Instant builtAt;
    private final Map<Map<String, Integer>, Optional<PeriodicSolver.Solution>> solved =
        new HashMap<>();

    /** 提前并行解好、扫描还没问到的档（{@link #prefetch}）：问到时才移进 {@link #solved}，次数照问到的算。 */
    private final Map<Map<String, Integer>, BuildPool.Evaluated<Optional<PeriodicSolver.Solution>>>
        prefetched = new HashMap<>();

    /** 只认出入库走行也排开的解（{@link PeriodicSolver.Solution#depotConflicts} 为 0）。 */
    boolean depotClean;

    IntervalSearch(
        Prepared prepared, BuildInput input, TimetableBuildOptions options, Instant builtAt) {
      this.prepared = prepared;
      this.input = input;
      this.options = options;
      this.builtAt = builtAt;
    }

    /**
     * {@code [from, to]} 里第一个周期层排得开的值：先按 {@code coarse} 找到第一个排得开的粗档，再在它前一个粗档与它之间按 {@code fine} 补查。
     * 问的次数到 {@link #RELAX_SOLVE_LIMIT} 就停，当作没找到。
     */
    OptionalInt firstFeasible(
        int from, int to, IntFunction<Map<String, Integer>> intervalsAt, int coarse, int fine) {
      for (int x = from; x <= to; x += coarse) {
        if (solved.size() >= RELAX_SOLVE_LIMIT) {
          return OptionalInt.empty();
        }
        prefetch(x, to, coarse, intervalsAt);
        if (accepted(intervalsAt.apply(x)).isEmpty()) {
          continue;
        }
        prefetch(Math.max(from, x - coarse + fine), x - 1, fine, intervalsAt);
        for (int y = Math.max(from, x - coarse + fine); y < x; y += fine) {
          if (solved.size() < RELAX_SOLVE_LIMIT && accepted(intervalsAt.apply(y)).isPresent()) {
            return OptionalInt.of(y);
          }
        }
        return OptionalInt.of(x);
      }
      return OptionalInt.empty();
    }

    /** 周期层在这组间隔下排得开、且合当前口径（{@link #depotClean}）时的解。 */
    private Optional<PeriodicSolver.Solution> accepted(Map<String, Integer> intervals) {
      return periodic(intervals).filter(solution -> !depotClean || solution.depotConflicts() == 0);
    }

    /** 周期层在这组间隔下排得开时的解；排不开为空。 */
    Optional<PeriodicSolver.Solution> periodic(Map<String, Integer> intervals) {
      Map<String, Integer> key = Map.copyOf(intervals);
      if (solved.containsKey(key)) {
        return solved.get(key);
      }
      BuildPool.Evaluated<Optional<PeriodicSolver.Solution>> early = prefetched.remove(key);
      Optional<PeriodicSolver.Solution> answer = early != null ? early.get() : solve(key);
      solved.put(key, answer);
      return answer;
    }

    private Optional<PeriodicSolver.Solution> solve(Map<String, Integer> key) {
      detail("周期层试 " + key);
      try {
        PeriodicSolver.Solution solution =
            PeriodicSolver.solve(solverInput(prepared, input, at(key)));
        return solution.conflicts() == 0 ? Optional.of(solution) : Optional.empty();
      } catch (PeriodicSolver.HyperperiodTooLong tooLong) {
        // 这组间隔的超周期太长、环放不下：当作排不开，换下一档。
        return Optional.empty();
      }
    }

    /**
     * 扫描接下来要问的几档（从 {@code from} 起每 {@code step} 一档、不超过 {@code to}，至多线程数档、不超过剩余次数）先并行解好。
     * 扫描照旧按原顺序逐档问、逐档计数，所以找到的档与串行相同；早停之后没问到的白解。串行时不做。
     */
    private void prefetch(
        int from, int to, int step, IntFunction<Map<String, Integer>> intervalsAt) {
      if (pool.threads() < 2) {
        return;
      }
      int room = Math.min(pool.threads(), RELAX_SOLVE_LIMIT - solved.size());
      List<Map<String, Integer>> batch = new ArrayList<>();
      for (int value = from; value <= to && batch.size() < room; value += step) {
        Map<String, Integer> key = Map.copyOf(intervalsAt.apply(value));
        if (!solved.containsKey(key) && !prefetched.containsKey(key) && !batch.contains(key)) {
          batch.add(key);
        }
      }
      if (batch.size() < 2) {
        return;
      }
      List<BuildPool.Evaluated<Optional<PeriodicSolver.Solution>>> out =
          pool.evaluate(batch, this::solve);
      for (int i = 0; i < out.size(); i++) {
        prefetched.put(batch.get(i), out.get(i));
      }
    }

    /** 按周期层的解完整构建一次；周期层排不开或构建失败时为空。 */
    Optional<Relaxed> confirm(Map<String, Integer> intervals) {
      Optional<PeriodicSolver.Solution> solution = accepted(intervals);
      if (solution.isEmpty()) {
        return Optional.empty();
      }
      progress.trial(TimetableBuildProgress.Trial.RELAX_CONFIRM);
      TimetableBuildOptions at = at(intervals);
      try {
        Solved built = materialize(prepared, input, at, solution.get());
        Attempt attempt =
            attemptAtPhases(built.prepared(), at, built.input(), builtAt, built.phases());
        return Optional.of(new Relaxed(attempt, built.prepared(), List.of()));
      } catch (BuildFailure failure) {
        return Optional.empty();
      }
    }

    private TimetableBuildOptions at(Map<String, Integer> intervals) {
      return options.withIntervals(Duration.ofSeconds(minInterval(intervals)), intervals);
    }
  }

  private static int minInterval(Map<String, Integer> intervals) {
    return intervals.values().stream().mapToInt(Integer::intValue).min().orElse(1);
  }

  /**
   * @param slotByCode 运营班次的车次号 → 名义格子（严格模式取消用）；出入库走行与带客回库班不在里面
   * @param pinnedCodes 其中定点班次的车次号：定点是硬约束，严格模式不取消它们
   * @param couplingNotes 加解编没能派生的主干班次（进编表报告）
   */
  private record Attempt(
      int headwaySeconds,
      Timetable timetable,
      List<TimetableBuildResult.DroppedTrip> dropped,
      List<WeightedTripAllocator.ShareReport> shares,
      TimetableConflictChecker.Report conflicts,
      List<ConflictAbsorption.Residual> residuals,
      List<TimetableBuildResult.TripShift> shifts,
      List<TripYield> yields,
      Map<String, Integer> intervals,
      List<PhasePlanner.Interleave> interleaves,
      List<String> phaseNotes,
      List<PhasePlanner.Residue> residues,
      PhasePlanner.Phases phases,
      Map<String, CancelSlot> slotByCode,
      Set<String> pinnedCodes,
      List<String> couplingNotes) {

    /** 成功判据：只看运行时让不掉的那些。可吸收残余照常发布，运行时会在让车点等一会儿。 */
    boolean clean() {
      return ConflictAbsorption.unabsorbable(residuals).isEmpty();
    }

    List<ConflictAbsorption.Residual> unabsorbable() {
      return ConflictAbsorption.unabsorbable(residuals);
    }

    List<ConflictAbsorption.Residual> absorbable() {
      return ConflictAbsorption.absorbable(residuals);
    }
  }

  /** 一个格子连同它所属的方向：份额按方向报，主键与交路按全局候选下标找。 */
  /**
   * 网格上的一班。
   *
   * @param slot 格子
   * @param direction 方向
   * @param pinned 定点班次：一秒不动，别的车让它
   */
  record Placed(GroupGrid.Slot slot, ServiceGroupClassifier.Direction direction, boolean pinned) {}

  /** 这个方向在各制式里的段；全天一个制式时就是整个窗口一段（窗口末那一秒也能发车）。 */
  private static List<GroupGrid.Segment> segmentsOf(
      PhasePlanner.Phases phases, String group, String direction, int interval, int horizon) {
    int phase = phases.phaseOf(direction);
    if (phases.regimes().isEmpty()) {
      return List.of(new GroupGrid.Segment(0, horizon + 1, interval, phase));
    }
    List<GroupGrid.Segment> out = new ArrayList<>();
    for (PhasePlanner.Regime regime : phases.regimes()) {
      out.add(
          new GroupGrid.Segment(
              regime.from(),
              regime.to() >= horizon ? horizon + 1 : regime.to(),
              regime.intervals().getOrDefault(group, interval),
              regime.phases().getOrDefault(direction, phase)));
    }
    return out;
  }

  /**
   * 严格模式取消的名义格子：运营 route + 名义发车（相对计划窗口起点）。重排之后车次号与派车都会变，格子不变。
   *
   * @param routeId 运营 route（基础 route，不是车型变体）
   * @param nominalSeconds 名义发车
   */
  private record CancelSlot(UUID routeId, int nominalSeconds) {}

  /**
   * 严格模式取消的一班。
   *
   * @param slot 名义格子
   * @param tripCode 目标间隔原表（一班都没取消时）里的车次号
   * @param cause 让它取消的那处残余
   */
  private record Cancelled(CancelSlot slot, String tripCode, ConflictAbsorption.Residual cause) {}

  /**
   * 严格模式取消的结果。
   *
   * @param attempt 最后一次试排
   * @param cancelled 按先后取消的班次
   * @param count 计入上限的取消数：取消的班次，加上交路因此接不上、截断而多取消的班次
   */
  private record Cancellation(Attempt attempt, List<Cancelled> cancelled, int count) {

    /** "N 班"；因取消另外截断了班次时写明其中几班是截断的。 */
    String countText() {
      int induced = count - cancelled.size();
      return count + " 班" + (induced > 0 ? "（其中因此另外截断 " + induced + " 班）" : "");
    }
  }

  /** 构建失败：只带原因，不可行 route 清单由调用方持有。 */
  private static final class BuildFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private BuildFailure(String message) {
      super(message);
    }
  }

  /**
   * 构建输入：归属信息 + 参与的 route + 路网。
   *
   * @param timetableId 时刻表 UUID
   * @param companyId 公司
   * @param operatorId 运营商
   * @param lineId 线路
   * @param code 时刻表 code
   * @param name 展示名
   * @param routes 参与的 route（OPERATION 进发车表，CREATE/RETURN 提供出库/回库走行）
   * @param graph 调度图快照
   * @param runTimeModel 走行时分模型（生产上按运行曲线，单元测试可用逐边累计）
   * @param notes 备注
   * @param neighbors 已投影到我零点的邻表：它们的运行是不可移动的路权事实，只有我的运行会为了避让它们放宽 headway
   * @param lineByRoute 多线联编时每条 route 属于哪条线（车池）；没列出的按 {@code lineId}。单线为空
   * @param fleet 车型：没有车型时编表与从前逐字相同（见 {@link ConsistFleet}）
   */
  public record BuildInput(
      UUID timetableId,
      UUID companyId,
      UUID operatorId,
      UUID lineId,
      String code,
      String name,
      List<RouteInput> routes,
      RailGraph graph,
      RunTimeModel runTimeModel,
      Optional<String> notes,
      List<NeighborTimetable> neighbors,
      Map<UUID, UUID> lineByRoute,
      ConsistFleet fleet,
      CouplingInputs couplings) {

    /** 没有加解编输入的构建。 */
    public BuildInput(
        UUID timetableId,
        UUID companyId,
        UUID operatorId,
        UUID lineId,
        String code,
        String name,
        List<RouteInput> routes,
        RailGraph graph,
        RunTimeModel runTimeModel,
        Optional<String> notes,
        List<NeighborTimetable> neighbors,
        Map<UUID, UUID> lineByRoute,
        ConsistFleet fleet) {
      this(
          timetableId,
          companyId,
          operatorId,
          lineId,
          code,
          name,
          routes,
          graph,
          runTimeModel,
          notes,
          neighbors,
          lineByRoute,
          fleet,
          CouplingInputs.NONE);
    }

    /** 不区分车型的构建。 */
    public BuildInput(
        UUID timetableId,
        UUID companyId,
        UUID operatorId,
        UUID lineId,
        String code,
        String name,
        List<RouteInput> routes,
        RailGraph graph,
        RunTimeModel runTimeModel,
        Optional<String> notes,
        List<NeighborTimetable> neighbors,
        Map<UUID, UUID> lineByRoute) {
      this(
          timetableId,
          companyId,
          operatorId,
          lineId,
          code,
          name,
          routes,
          graph,
          runTimeModel,
          notes,
          neighbors,
          lineByRoute,
          ConsistFleet.none());
    }

    public BuildInput {
      Objects.requireNonNull(timetableId, "timetableId");
      Objects.requireNonNull(companyId, "companyId");
      Objects.requireNonNull(operatorId, "operatorId");
      Objects.requireNonNull(lineId, "lineId");
      routes = routes == null ? List.of() : List.copyOf(routes);
      notes = notes == null ? Optional.empty() : notes;
      neighbors = neighbors == null ? List.of() : List.copyOf(neighbors);
      lineByRoute = lineByRoute == null ? Map.of() : Map.copyOf(lineByRoute);
      fleet = fleet == null ? ConsistFleet.none() : fleet;
      couplings = couplings == null ? CouplingInputs.NONE : couplings;
    }

    /** 换一份 route 列表，其余不变。 */
    BuildInput withRoutes(List<RouteInput> nextRoutes) {
      return new BuildInput(
          timetableId,
          companyId,
          operatorId,
          lineId,
          code,
          name,
          nextRoutes,
          graph,
          runTimeModel,
          notes,
          neighbors,
          lineByRoute,
          fleet,
          couplings);
    }

    /** 换一组车型，其余不变。 */
    public BuildInput withFleet(ConsistFleet nextFleet) {
      return new BuildInput(
          timetableId,
          companyId,
          operatorId,
          lineId,
          code,
          name,
          routes,
          graph,
          runTimeModel,
          notes,
          neighbors,
          lineByRoute,
          nextFleet,
          couplings);
    }

    /** 换一份加解编输入，其余不变。 */
    public BuildInput withCouplings(CouplingInputs nextCouplings) {
      return new BuildInput(
          timetableId,
          companyId,
          operatorId,
          lineId,
          code,
          name,
          routes,
          graph,
          runTimeModel,
          notes,
          neighbors,
          lineByRoute,
          fleet,
          nextCouplings);
    }

    /** 单线构建：所有 route 同一车池。 */
    public BuildInput(
        UUID timetableId,
        UUID companyId,
        UUID operatorId,
        UUID lineId,
        String code,
        String name,
        List<RouteInput> routes,
        RailGraph graph,
        RunTimeModel runTimeModel,
        Optional<String> notes,
        List<NeighborTimetable> neighbors) {
      this(
          timetableId,
          companyId,
          operatorId,
          lineId,
          code,
          name,
          routes,
          graph,
          runTimeModel,
          notes,
          neighbors,
          Map.of());
    }

    /** 这条 route 的车池：多线联编时是它所属的线，单线时是本表的线。 */
    String poolOf(UUID routeId) {
      return lineByRoute.getOrDefault(routeId, lineId).toString();
    }

    /**
     * 这条 route 归本次构建管辖吗。
     *
     * <p>{@code lineByRoute} 为空表示"全部归我"——只有单元测试会这样，生产路径（单线与联编）都由命令层填满。 不在表里的是<b>借来的走行线路</b>：别的线或别的
     * operator 的，进足迹、进交路，但不进受管辖集合。
     */
    boolean owns(UUID routeId) {
      return lineByRoute.isEmpty() || lineByRoute.containsKey(routeId);
    }

    /** 没有邻表的构建（单元测试与不需要作用域的场景）。 */
    public BuildInput(
        UUID timetableId,
        UUID companyId,
        UUID operatorId,
        UUID lineId,
        String code,
        String name,
        List<RouteInput> routes,
        RailGraph graph,
        RunTimeModel runTimeModel,
        Optional<String> notes) {
      this(
          timetableId,
          companyId,
          operatorId,
          lineId,
          code,
          name,
          routes,
          graph,
          runTimeModel,
          notes,
          List.of());
    }

    /** 按 route code 稳定排序，保证分配器的 tie-break 与输入顺序无关。 */
    List<RouteInput> sortedRoutes() {
      return routes.stream()
          .filter(Objects::nonNull)
          .sorted(Comparator.comparing(RouteInput::routeCode))
          .toList();
    }
  }

  /**
   * 参与构建的一条 route。
   *
   * @param routeId Route UUID
   * @param routeCode Route code
   * @param operationType route 类型；带客 CREATE 缺省独立发车，其余出入库提供交路辅助段
   * @param weight 目标服务比例权重（仅 OPERATION 有意义）
   * @param definition 已解析的交路定义
   * @param stops route 的停靠配置，索引与 waypoints 对齐
   * @param depotNodeId 出库点
   * @param declaredAs 这条线路是被某条运营 route 在 metadata 里显式指定为出库（CREATE）或回库（RETURN）走行线路的 （见 {@link
   *     TimetableRouteMetadata}），可能来自别的 operator；与 {@code operationType} 不符时判为不可行
   * @param external 属于别的 operator：进足迹、进交路，但不受本表管辖（见 {@link TimetableRoutePlan#external()}）
   * @param spawnGroup 交路组（route metadata 的 spawn_group）；空则进 {@link
   *     ServiceGroupClassifier#DEFAULT_GROUP}
   * @param dispatchByOperation 出入库仅由运营交路驱动，保留停站，但不生成独立周期班次
   */
  public record RouteInput(
      UUID routeId,
      String routeCode,
      RouteOperationType operationType,
      int weight,
      RouteDefinition definition,
      List<RouteStop> stops,
      Optional<String> depotNodeId,
      Optional<RouteOperationType> declaredAs,
      boolean external,
      Optional<String> spawnGroup,
      boolean dispatchByOperation) {

    /** 兼容既有输入：缺省仍按原来的客运班次分类。 */
    public RouteInput(
        UUID routeId,
        String routeCode,
        RouteOperationType operationType,
        int weight,
        RouteDefinition definition,
        List<RouteStop> stops,
        Optional<String> depotNodeId,
        Optional<RouteOperationType> declaredAs,
        boolean external,
        Optional<String> spawnGroup) {
      this(
          routeId,
          routeCode,
          operationType,
          weight,
          definition,
          stops,
          depotNodeId,
          declaredAs,
          external,
          spawnGroup,
          false);
    }

    /** 保持停站与带客属性，仅修改出入库需求来源。 */
    public RouteInput withDispatchByOperation(boolean enabled) {
      return new RouteInput(
          routeId,
          routeCode,
          operationType,
          weight,
          definition,
          stops,
          depotNodeId,
          declaredAs,
          external,
          spawnGroup,
          enabled);
    }

    /** 修改交路组并保留出入库需求来源；联合排班加线路前缀时不改变 route 的调度语义。 */
    public RouteInput withSpawnGroup(Optional<String> nextGroup) {
      return new RouteInput(
          routeId,
          routeCode,
          operationType,
          weight,
          definition,
          stops,
          depotNodeId,
          declaredAs,
          external,
          nextGroup,
          dispatchByOperation);
    }

    public RouteInput {
      Objects.requireNonNull(routeId, "routeId");
      Objects.requireNonNull(definition, "definition");
      operationType = operationType == null ? RouteOperationType.OPERATION : operationType;
      if (dispatchByOperation && operationType == RouteOperationType.OPERATION) {
        throw new IllegalArgumentException("dispatchByOperation 仅允许 CREATE/RETURN");
      }
      routeCode = routeCode == null ? "" : routeCode.trim();
      stops = stops == null ? List.of() : List.copyOf(stops);
      depotNodeId = depotNodeId == null ? Optional.empty() : depotNodeId;
      declaredAs = declaredAs == null ? Optional.empty() : declaredAs;
      spawnGroup =
          spawnGroup == null
              ? Optional.empty()
              : spawnGroup.map(String::trim).filter(g -> !g.isBlank());
    }

    /** 换一份停靠配置，其余不变。 */
    RouteInput withStops(List<RouteStop> nextStops) {
      return new RouteInput(
          routeId,
          routeCode,
          operationType,
          weight,
          definition,
          nextStops,
          depotNodeId,
          declaredAs,
          external,
          spawnGroup,
          dispatchByOperation);
    }

    /** 没有交路组信息的构造：进默认组。 */
    public RouteInput(
        UUID routeId,
        String routeCode,
        RouteOperationType operationType,
        int weight,
        RouteDefinition definition,
        List<RouteStop> stops,
        Optional<String> depotNodeId,
        Optional<RouteOperationType> declaredAs,
        boolean external) {
      this(
          routeId,
          routeCode,
          operationType,
          weight,
          definition,
          stops,
          depotNodeId,
          declaredAs,
          external,
          Optional.empty());
    }

    /** 本 operator 范围内的线路（可能被显式指定）。 */
    public RouteInput(
        UUID routeId,
        String routeCode,
        RouteOperationType operationType,
        int weight,
        RouteDefinition definition,
        List<RouteStop> stops,
        Optional<String> depotNodeId,
        Optional<RouteOperationType> declaredAs) {
      this(
          routeId,
          routeCode,
          operationType,
          weight,
          definition,
          stops,
          depotNodeId,
          declaredAs,
          false);
    }

    /** 本 operator 自己收集到的线路：没有被显式指定。 */
    public RouteInput(
        UUID routeId,
        String routeCode,
        RouteOperationType operationType,
        int weight,
        RouteDefinition definition,
        List<RouteStop> stops,
        Optional<String> depotNodeId) {
      this(
          routeId,
          routeCode,
          operationType,
          weight,
          definition,
          stops,
          depotNodeId,
          Optional.empty());
    }

    /** 运营 route 的便捷构造。 */
    public RouteInput(
        UUID routeId,
        String routeCode,
        int weight,
        RouteDefinition definition,
        List<RouteStop> stops,
        Optional<String> depotNodeId) {
      this(
          routeId, routeCode, RouteOperationType.OPERATION, weight, definition, stops, depotNodeId);
    }
  }

  /** 供命令层构造默认参数时使用。 */
  public static TimetableBuildOptions defaultOptions(ZoneId zoneId, Duration headway) {
    TimetableBuildOptions defaults = TimetableBuildOptions.defaults(zoneId);
    return new TimetableBuildOptions(
        defaults.serviceStartSecondOfDay(),
        defaults.serviceEndSecondOfDay(),
        headway == null ? defaults.headway() : headway,
        defaults.defaultDwell(),
        defaults.dutyLimits(),
        defaults.tripCodePrefix(),
        zoneId);
  }
}
