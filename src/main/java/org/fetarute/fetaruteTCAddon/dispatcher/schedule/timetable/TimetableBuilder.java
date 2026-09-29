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
import java.util.function.IntPredicate;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.RunTimeModel;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SingleLineSectionIndex;
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
 * <p>四步，各自只做一件事：
 *
 * <ol>
 *   <li><b>算时分</b>（{@link TimetableTimingCalculator}）：每条 route 按实际穿越的区段限速积分出站间时分与逐边时分。 算不出来的 route
 *       直接标为不可行并说明原因，绝不用一个全线平均速度糊过去。CREATE/RETURN 线路同样在这里算， 它们给出车辆交路两端的出库/回库走行时分。
 *   <li><b>排班次</b>（{@link WeightedTripAllocator}）：把计划窗口切成等间隔时隙，按 weight 做 smooth weighted
 *       round-robin。weight 是目标比例，不是抽签。只有 OPERATION 参与。
 *   <li><b>派车</b>（{@link VehicleDutyPlanner}）：在<b>已经排定</b>的班次上指派车辆， 每个 duty 都被硬上限夹住、从有 CREATE
 *       线路的起点开、在有 RETURN 线路的终点收。
 *   <li><b>查冲突</b>（{@link TimetableConflictChecker}）：把全部运行（班次 + 出库/回库走行 +
 *       站台待命）投影到边、道岔、站台、单线区段上扫描重叠。 目标 headway 排出来有冲突时，向上搜索<b>最小可行
 *       headway</b>：默认回退到它并明确警告，严格模式则构建失败。
 * </ol>
 *
 * <p>前三步的顺序不可交换。特别是第三步绝不能反过来影响第二步：一旦允许"某终点恰好停着车，所以多发这条线"， 车辆周转就会扭曲服务比例，而那种扭曲在运营上是看不见的。
 * 第三步唯一能对第二步做的是<b>减法</b>：起点没有出库途径、终点没有回库途径的班次被取消并逐条报告——那是物理约束，不是周转偏好。 第四步同理只做减法：它只会把 headway
 * 放宽，从不挪动单个班次去"绕开"冲突——那会让表的结构依赖于冲突的偶然分布。
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

  /** 搜索可行 headway 时的步长（秒）。 */
  public static final int HEADWAY_SEARCH_STEP_SECONDS = 5;

  /** 搜索可行 headway 时最多放宽到目标的多少倍。 */
  public static final int HEADWAY_SEARCH_MAX_MULTIPLIER = 4;

  /**
   * 按车接续的喂车方向在中途最多多停几秒。
   *
   * <p>多停写进表里，运行时靠"早到等点"执行（{@code timetable.hold-max-seconds}，默认 120 秒；调度层另有 150 秒硬上限）。
   * 上限取得远低于它，保证表上的每一次多停运行时都真的会扣。
   */
  public static final int FEEDER_HOLD_LIMIT_SECONDS = 60;

  /** 逐组收紧最多走几遍：后收紧的单元可能给先前收不紧的腾出空间，走到一遍里谁都收不动为止。 */
  static final int TIGHTEN_PASSES = 3;

  /** 结构上不必多停时，按这个步长抽样试多停（沿线错开只有完整构建看得见）。 */
  static final int FEEDER_HOLD_GRID_SECONDS = 15;

  /** 同一个间隔下最多完整构建几种多停（候选里取最短的几个）：每种都是一次完整构建，不能无限试。 */
  static final int FEEDER_HOLD_TRIES = 3;

  private final TimetableTimingCalculator timingCalculator;

  public TimetableBuilder() {
    this(new TimetableTimingCalculator());
  }

  public TimetableBuilder(TimetableTimingCalculator timingCalculator) {
    this.timingCalculator = Objects.requireNonNull(timingCalculator, "timingCalculator");
  }

  /**
   * 构建时刻表。
   *
   * @param input 归属信息、route 集合与路网
   * @param options 运营参数
   * @param now 构建时间
   * @return 构建结果
   */
  public TimetableBuildResult build(
      BuildInput input, TimetableBuildOptions requested, Instant now) {
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(requested, "requested");
    // 折返时间不是常数：没有 --turnaround 覆盖时按各 route 终到停靠点的 dwell 算（唯一来源是 route 定义）。
    TimetableBuildOptions options = requested.withTurnaround(resolveTurnarounds(input, requested));
    Instant builtAt = now == null ? Instant.now() : now;

    // ---- 1. 算时分（与 headway 无关，只做一次） ------------------------------
    // 不可行 route 的清单由这里持有：prepare 中途失败时它已经有内容，失败结果要把它带出去。
    List<TimetableBuildResult.InfeasibleRoute> infeasible = new ArrayList<>();
    Prepared prepared;
    try {
      prepared = prepare(input, options, infeasible);
    } catch (BuildFailure failure) {
      return TimetableBuildResult.failure(failure.getMessage(), List.copyOf(infeasible));
    }

    // ---- 2–4. 按目标间隔排一次 --------------------------------------
    Attempt target;
    try {
      target = attempt(prepared, options, input, builtAt);
    } catch (BuildFailure failure) {
      return TimetableBuildResult.failure(failure.getMessage(), prepared.infeasible());
    }
    // 报告与搜索都以"最小的组间隔"为标量：搜索时所有组等比放宽，找到之后再逐组收紧（tightenGroups）。
    int targetHeadway = target.headwaySeconds();

    Attempt chosen = target;
    // 选中的那份准备：喂车方向多停时是改过那几条 route 时分的一份（时分、投影、走行都跟着变）。
    Prepared chosenPrepared = prepared;
    List<String> warnings = new ArrayList<>();
    // 只有"运行时也让不掉"的残余才算目标间隔不可行；可吸收的那些照常发布。
    if (!target.clean()) {
      String summary =
          TimetableBuildReportText.summarizeUnabsorbable(target.unabsorbable(), targetHeadway);
      if (options.strictConflicts()) {
        List<String> reasons = new ArrayList<>();
        reasons.add(summary + "；严格模式下不回退");
        reasons.addAll(
            TimetableBuildReportText.describeConflicts(
                target.conflicts(), options.serviceStartSecondOfDay()));
        return TimetableBuildResult.failure(String.join("\n", reasons), prepared.infeasible());
      }
      List<String> searchNotes = new ArrayList<>();
      // 已经排不开的各组间隔：搜索与收紧都往里记，收紧时用它剪掉注定失败的完整构建。
      List<Failure> failed = new ArrayList<>();
      failed.add(new Failure(FeederHold.NONE, target.intervals()));
      Optional<Found> fallback =
          searchFeasibleHeadway(prepared, options, target, input, builtAt, searchNotes, failed);
      if (fallback.isEmpty()) {
        return TimetableBuildResult.failure(
            summary
                + "；"
                + TimetableBuildReportText.describeSearchFailure(
                    targetHeadway,
                    targetHeadway * HEADWAY_SEARCH_MAX_MULTIPLIER,
                    target.conflicts(),
                    target.terminals(),
                    options.dutyLimits().turnaround()),
            prepared.infeasible());
      }
      Found found = fallback.get();
      chosenPrepared = found.prepared();
      chosen =
          tightenGroups(
              found.prepared(),
              options,
              target,
              found.attempt(),
              found.input(),
              builtAt,
              searchNotes,
              failed,
              found.hold());
      warnings.addAll(searchNotes);
      warnings.add(
          summary
              + "，已回退到最小可行间隔 "
              + describeRelaxed(target, chosen)
              + "（--strict 可改为构建失败）"
              + (target.conflicts().external().isEmpty()
                  ? ""
                  : "；其中 " + target.conflicts().external().size() + " 处是与已发布邻表的冲突，只能挪自己"));
      warnings.addAll(
          TimetableBuildReportText.describeConflicts(
              target.conflicts(), options.serviceStartSecondOfDay()));
    }

    for (TerminalSerializer.ThroatReport throat : target.throats()) {
      if (throat.utilization() > 1.0D) {
        warnings.add(
            String.format(
                Locale.ROOT,
                "车库 %s 的咽喉（出入段共用的单线）在目标间隔下利用率 %.0f%%：出库与回库挤不下，目标间隔本身在结构上不可能",
                throat.depot(),
                throat.utilization() * 100.0D));
      }
    }
    for (TerminalSerializer.TerminalReport terminal : target.terminals()) {
      if (terminal.utilization() > 1.0D) {
        warnings.add(
            String.format(
                Locale.ROOT,
                "端点 %s（单股道）在目标间隔下利用率 %.0f%%：目标间隔本身在结构上不可能，放宽后的表才排得开",
                terminal.group(),
                terminal.utilization() * 100.0D));
      }
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
    for (ResourceRepair.Yield yield : target.yields()) {
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
    return new TimetableBuildResult(
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
        chosen.terminals(),
        chosen.throats(),
        groupIntervals(target, chosen),
        chosen.interleaves(),
        dutyShapes(chosen.timetable()),
        chosen.phaseNotes(),
        // 残余取最终选中的那一次：报告说的是"这张表发布后运行时要让几次车"。
        chosen.absorbable(),
        chosen.unabsorbable(),
        chosen.resourceNotes(),
        chosen.residues(),
        List.copyOf(warnings));
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

  /** 回退提示里的间隔：只有一组时就是一个数；多组时只列被放宽的组，逐组收紧后回到目标的组不列。 */
  private static String describeRelaxed(Attempt target, Attempt chosen) {
    if (target.intervals().size() <= 1) {
      return chosen.headwaySeconds() + "s";
    }
    List<String> relaxed = new ArrayList<>();
    new TreeMap<>(target.intervals())
        .forEach(
            (group, seconds) -> {
              int effective = chosen.intervals().getOrDefault(group, seconds);
              if (effective > seconds) {
                relaxed.add(group + " " + seconds + "→" + effective + "s");
              }
            });
    return relaxed.isEmpty() ? chosen.headwaySeconds() + "s" : String.join("、", relaxed);
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

  /** 算时分、分类 route、建走行段索引、做线路级校验。失败抛 {@link BuildFailure}，不可行清单已写入 {@code infeasible}。 */
  private Prepared prepare(
      BuildInput input,
      TimetableBuildOptions options,
      List<TimetableBuildResult.InfeasibleRoute> infeasible) {
    return prepare(input, options, infeasible, null);
  }

  /**
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
    Set<UUID> passengerReturns = new HashSet<>();
    Map<UUID, Integer> runByRoute = new HashMap<>();
    for (RouteInput route : input.sortedRoutes()) {
      TimetableTimingCalculator.TimingResult timing =
          timingCalculator.compute(
              input.graph(),
              input.runTimeModel(),
              route.definition(),
              route.stops(),
              options.defaultDwell());
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
      switch (route.operationType()) {
        case CREATE -> {
          if (!startsAtDepot(route.stops())) {
            // 发车侧会把没有 CRET 的 CREATE 票无限重排（create-without-cret），这条线路等于不存在。
            infeasible.add(
                new TimetableBuildResult.InfeasibleRoute(
                    route.routeCode(), "CREATE 线路首站没有 CRET 指令，无法实体化列车"));
            continue;
          }
          if (owned && ServiceGroupClassifier.carriesPassengers(route)) {
            // 带客的出库班是班次：上它所属组的子网格，从车库实体化（与 OPERATION + CRET 同一条路径）；不再当走行段。
            // 别的线的带客出库班不在此列——我只是借它出库，把它排成我的班次会连人家自己的 headway 票一起拦掉。
            operations.add(new OperationPlan(route, true, endsAtDepot(route.stops())));
            break;
          }
          createLegs.add(
              new VehicleDutyPlanner.Leg(
                  route.routeId(), route.routeCode(), origin, timing.totalRunSeconds(), declared));
          legStation.put(route.routeId(), terminal);
        }
        case RETURN -> {
          if (owned && ServiceGroupClassifier.carriesPassengers(route)) {
            // 带客的回库班：仍由派车器在交路收尾处生成（到达 + 折返），但落 trip 行给 PIDS 与导出。
            passengerReturns.add(route.routeId());
          }
          returnLegs.add(
              new VehicleDutyPlanner.Leg(
                  route.routeId(),
                  route.routeCode(),
                  terminal,
                  timing.totalRunSeconds(),
                  declared));
          legStation.put(route.routeId(), origin);
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
    // 只对进发车表的 route 分组分方向：走行段不上网格。
    List<RouteInput> scheduled = operations.stream().map(OperationPlan::route).toList();
    ServiceGroupClassifier.Classification classification =
        ServiceGroupClassifier.classify(scheduled);
    return new Prepared(
        timetableId,
        graphIndex,
        List.copyOf(plans),
        List.copyOf(operations),
        operationPlans,
        candidates,
        VehicleDutyPlanner.Legs.of(createLegs, returnLegs, legStation),
        Map.copyOf(profiles),
        List.copyOf(infeasible),
        classification,
        Set.copyOf(passengerReturns),
        Map.copyOf(runByRoute));
  }

  // ------------------------------------------------------------ 第 2–4 步

  /** 每组每方向铺子网格、选相位、派车、串行、编号、查冲突。排不出任何班次时抛 {@link BuildFailure}。 */
  private Attempt attempt(
      Prepared prepared, TimetableBuildOptions options, BuildInput input, Instant builtAt) {
    UUID timetableId = prepared.timetableId();
    int horizon = options.horizonSeconds();
    int separation = (int) Math.min(Integer.MAX_VALUE, options.separation().toSeconds());
    List<TimetableRoutePlan> operationPlans = prepared.operationPlans();
    Map<UUID, Integer> candidateIndexByRoute = new HashMap<>();
    for (int i = 0; i < operationPlans.size(); i++) {
      candidateIndexByRoute.put(operationPlans.get(i).routeId(), i);
    }

    // ---- 2. 每个交路组每个方向一张规整子网格；相位先锚定往返对，再在共用起点上交错各组。 ----
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
    PhasePlanner.Phases phases = planPhases(prepared, options, intervalByGroup, gridGroups);
    // ---- 2.5 第三层：前两层只看端点，沿线哪里交会、咽喉上出库流与回库流什么时候相遇它们看不见。
    // 给每个方向选一个 δ，用同一套冲突模型按周期评估；按车接续链整体平移，不拆开。
    phases =
        ResourcePhasePlanner.refine(
            phases,
            gridGroups,
            intervalByGroup,
            periodicTemplates(prepared, gridGroups, intervalByGroup, options),
            prepared.profiles(),
            prepared.graphIndex(),
            separation,
            options.repair().maxWaitSeconds(),
            options.dutyLimits().maxIdleSeconds(),
            inPlaceTurnbackRoutes(prepared.operationPlans(), prepared.legs()));
    List<GroupGrid.DirectionGrid> grids = new ArrayList<>();
    List<Placed> placed = new ArrayList<>();
    for (ServiceGroupClassifier.Group group : gridGroups) {
      int interval = intervalByGroup.get(group.name());
      for (ServiceGroupClassifier.Direction direction : group.directions()) {
        int phase = phases.effectivePhaseOf(direction.key());
        // 可行性：这一班必须在计划窗口内跑完。全程时分长的 route 因此会在窗口末尾被自然挤出，
        // 而它的 deficit 留在分配器里——这正是"约束恢复后能追回份额"的机制。
        WeightedTripAllocator.FeasibilityCheck feasibility =
            (slot, index, assigned) -> {
              UUID routeId = direction.routeIds().get(index);
              Integer run = prepared.runByRoute().get(routeId);
              return candidateIndexByRoute.containsKey(routeId)
                  && phase + (long) slot * interval + (run == null ? 0 : run) <= horizon;
            };
        GroupGrid.DirectionGrid grid =
            GroupGrid.of(direction, interval, phase, horizon, feasibility);
        grids.add(grid);
        for (GroupGrid.Slot slot : grid.slots()) {
          placed.add(new Placed(slot, direction));
        }
      }
    }
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
    for (int i = 0; i < placed.size(); i++) {
      Placed item = placed.get(i);
      int candidateIndex = candidateIndexByRoute.get(item.slot().routeId());
      OperationPlan op = prepared.operations().get(candidateIndex);
      TimetableRoutePlan plan = operationPlans.get(candidateIndex);
      int departureSeconds = item.slot().departureSeconds();
      UUID provisional =
          UUID.nameUUIDFromBytes(
              ("provisional:" + timetableId + ":" + i)
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      allocationByProvisional.put(
          provisional, new WeightedTripAllocator.Allocation(item.slot().slot(), candidateIndex));
      placedByProvisional.put(provisional, item);
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
              input.poolOf(plan.routeId()));
      plannedTrips.add(trip);
    }

    // 各起点上的名义发车时刻：派车器用它回答"这辆车在终点还要等多久才有下一班"，
    // 等过头的按 IDLE_LIMIT 回库，与运行时的闲置回收同一条规则。
    Map<String, NavigableSet<Integer>> nextSlotByOrigin = new HashMap<>();
    for (VehicleDutyPlanner.PlannedTrip trip : plannedTrips) {
      nextSlotByOrigin
          .computeIfAbsent(trip.originNodeId(), key -> new TreeSet<>())
          .add(trip.departureSeconds());
    }
    VehicleDutyPlanner.Result planned =
        VehicleDutyPlanner.plan(
            timetableId,
            plannedTrips,
            prepared.legs(),
            options.dutyLimits(),
            nextSlotByOrigin,
            preferredFeeders(phases.connections()));
    Map<UUID, VehicleDutyPlanner.UnassignedTrip> unassigned = new HashMap<>();
    for (VehicleDutyPlanner.UnassignedTrip trip : planned.unassigned()) {
      unassigned.put(trip.tripId(), trip);
    }
    Map<UUID, UUID> dutyByProvisional = new HashMap<>();
    for (VehicleDuty duty : planned.duties()) {
      for (UUID tripId : duty.tripIds()) {
        dutyByProvisional.put(tripId, duty.id());
      }
    }

    // ---- 3. 只保留派上车的班次，其余取消并说明原因。编号要等端点串行之后再做：串行会改时刻、也会改先后。
    Map<UUID, Integer> nominalByProvisional = new HashMap<>();
    List<TimetableTrip> provisionalTrips = new ArrayList<>(plannedTrips.size());
    List<TimetableBuildResult.DroppedTrip> dropped = new ArrayList<>();
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
      // 临时表的时刻约定：零点 + 相对秒，不取模；串行只在相对秒上算，取模留给编号。
      provisionalTrips.add(
          new TimetableTrip(
              provisional.tripId(),
              timetableId,
              plan.routeId(),
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
              duty.closeReason()));
    }
    Timetable provisionalTable =
        timetableOf(input, options, prepared, provisionalTrips, provisionalDuties, builtAt);

    // ---- 3.5 端点串行：容量 1 的端点按资源串行，只改时刻不增减班次（排队超限时截断交路并上报）。
    Set<UUID> endingAtDepot = new HashSet<>();
    for (OperationPlan op : prepared.operations()) {
      if (op.endsAtDepot()) {
        endingAtDepot.add(op.route().routeId());
      }
    }
    TerminalSerializer.Result serialized =
        TerminalSerializer.serialize(
            new TerminalSerializer.Input(
                provisionalTable,
                prepared.profiles(),
                prepared.graphIndex(),
                options.serviceStartSecondOfDay(),
                horizon,
                separation,
                prepared.legs(),
                options.dutyLimits(),
                endingAtDepot,
                prepared.candidates(),
                operationPlans,
                input.neighbors()));
    for (UUID truncated : serialized.truncatedTripIds()) {
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
    if (serialized.timetable().trips().isEmpty()) {
      throw new BuildFailure("端点排队超限，没有一班能在计划窗口内跑完：检查折返时间、端点权重与股道数");
    }

    // ---- 3.7 让车写进表：其余资源上的冲突，后车整趟延后不超过 --max-wait 的写进表；超过的留作真冲突交给搜索。
    ResourceRepair.Result repaired =
        ResourceRepair.repair(
            new ResourceRepair.Input(
                serialized.timetable(),
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
                input.neighbors()));
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
      throw new BuildFailure("让车累计超限，没有一班能在计划窗口内跑完：检查裕量、折返时间与 --max-wait");
    }

    // ---- 3.6 按实际发车顺序编号、派生主键、替换 duty 引用 ----------------------------
    TimetableTripNumbering.Numbered numbered =
        TimetableTripNumbering.number(
            timetableId, options, repaired.timetable(), nominalByProvisional);
    Map<UUID, String> routeCodeById = new HashMap<>();
    for (TimetableRoutePlan plan : prepared.plans()) {
      routeCodeById.put(plan.routeId(), plan.routeCode());
    }
    List<TimetableTrip> withReturns =
        TimetableTripNumbering.appendReturnTrips(
            timetableId,
            options,
            numbered.trips(),
            numbered.duties(),
            prepared.passengerReturns(),
            routeCodeById);
    Timetable timetable =
        timetableOf(input, options, prepared, withReturns, numbered.duties(), builtAt);
    Map<UUID, String> codeByFinalId = new HashMap<>();
    for (TimetableTrip trip : timetable.trips()) {
      codeByFinalId.put(trip.id(), trip.tripCode());
    }
    Map<String, String> finalCodeByProvisionalCode = new HashMap<>();
    for (TimetableTrip trip : repaired.timetable().trips()) {
      UUID finalId = numbered.finalByProvisional().get(trip.id());
      if (finalId != null) {
        finalCodeByProvisionalCode.put(trip.tripCode(), codeByFinalId.getOrDefault(finalId, "?"));
      }
    }
    List<ResourceRepair.Yield> yields =
        ResourceRepair.renamed(repaired.yields(), finalCodeByProvisionalCode);
    List<TerminalSerializer.Shift> allShifts = mergeShifts(serialized.shifts(), repaired.shifts());
    List<TimetableBuildResult.TripShift> shifts = new ArrayList<>(allShifts.size());
    for (TerminalSerializer.Shift shift : allShifts) {
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

    // 残余分类：让车修复之后剩下的每一处，判运行时能不能让、在哪让、让多久。
    // 成功判据从"零冲突"改成"零不可吸收残余"——运行时对这类冲突的处理本来就是后车在资源前等。
    List<ConflictAbsorption.Residual> residuals =
        ConflictAbsorption.classify(
            conflicts,
            timetable,
            occupancy,
            prepared.graphIndex(),
            separation,
            options.repair().maxWaitSeconds());

    // 份额按方向报：weight 只在同方向多 route 之间切，跨方向、跨组比没有意义。
    // 数的必须是<b>让车修复之后</b>还留在表上的班次：端点串行之后让车还会再截断一批，按串行后的表数会把
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
    return new Attempt(
        (int) headwaySeconds,
        timetable,
        List.copyOf(dropped),
        List.copyOf(shares),
        conflicts,
        residuals,
        List.copyOf(shifts),
        yields,
        serialized.terminals(),
        serialized.throats(),
        Map.copyOf(intervalByGroup),
        PhasePlanner.interleaves(grids),
        phases.notes(),
        phases.resourceNotes(),
        PhasePlanner.residues(
            gridGroups,
            intervalByGroup,
            prepared.runByRoute(),
            options.dutyLimits().turnaround(),
            inPlaceTurnbackRoutes(prepared.operationPlans(), prepared.legs())));
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
   * 目标间隔有冲突时往上找最小可行间隔，所有组等比放宽（找到之后由 {@link #tightenGroups} 逐组收紧）。
   *
   * <p>可行性不随间隔单调：实测 WS 135 可行、140 不可行、150 又可行。原来按 10 秒一档往上搜，从 120 直接跳到 150，漏掉 135。 现在分两层：
   *
   * <ul>
   *   <li><b>结构预筛，逐秒</b>：有跨组按车接续时，每一秒都只跑一遍相位层（毫秒级），端点或车库咽喉在远端多等的上限内错不开的 间隔直接跳过，不做完整构建；
   *   <li><b>完整构建</b>：只在 {@value #HEADWAY_SEARCH_STEP_SECONDS} 秒一档的格点上，以及预筛里每一段"错得开"区间的第一秒上做。
   *       可行窗口只有一两秒宽时也找得到它的起点。
   * </ul>
   *
   * 没有按车接续的线路没有东西可预筛，只走格点。按升序试，第一个干净的就是答案。
   *
   * <p>只放宽间隔、不挪动单个班次：表的结构（SWRR 序列、duty 链）在任何间隔下都用同一套规则生成，因此找到的间隔是一个可以直接写回配置的数， 而不是一次性的手工调整。
   *
   * <p>结构预筛之外还有一个可调量：按车接续的喂车方向在第一个中途停车点多停几秒（{@link HoldSearch}）。相位层原本只能让被接往返对的反向车在远端多等，
   * 这一等把它回到端点与回库过咽喉两件事一起平移，而喂车方向到端点与出库过咽喉之间差的是它自己的走行——两个约束的相对位置被走行锁死， 时分一变就可能无解（实服：停站开销从 4 秒改成 2
   * 秒，WS 最小可行间隔反而从 176 秒变成 185 秒）。喂车方向中途多停 h 秒， 它出库过咽喉就早 h
   * 秒、到端点不变，两个约束解开；它还让喂车方向与同向别的方向在共用站台上错开，这一层只有完整构建看得见。每个间隔先试不多停，再从短到长试 {@link HoldSearch#feasible}
   * 给出的几种多停（实服三线联编：WS 176 → 156）。
   *
   * @param searchNotes 搜索过程的说明（跳过了哪些结构上不可行的间隔、喂车方向多停了多少），进报告
   * @param failed 排不开的间隔（连同当时的多停），搜索往里追加
   */
  private Optional<Found> searchFeasibleHeadway(
      Prepared prepared,
      TimetableBuildOptions options,
      Attempt target,
      BuildInput input,
      Instant builtAt,
      List<String> searchNotes,
      List<Failure> failed) {
    int targetHeadway = target.headwaySeconds();
    HoldSearch holds = new HoldSearch(prepared, options, input);
    // 目标间隔不多停已经试过；结构上排不开时先看喂车方向中途多停能不能错开，再往上放宽。
    for (FeederHold hold : holds.feasible(options)) {
      if (hold.isNone()) {
        continue;
      }
      Optional<Found> found = tryAt(holds, hold, options, builtAt, failed);
      if (found.isPresent()) {
        searchNotes.add(holds.describe(hold));
        return found;
      }
    }
    boolean structural = structuralClearance(prepared, options).isPresent();
    HeadwayCandidates candidates =
        new HeadwayCandidates(
            targetHeadway,
            targetHeadway * HEADWAY_SEARCH_MAX_MULTIPLIER,
            HEADWAY_SEARCH_STEP_SECONDS,
            structural
                ? headway -> !holds.feasible(relaxedOptions(options, target, headway)).isEmpty()
                : null);
    for (OptionalInt next = candidates.next(); next.isPresent(); next = candidates.next()) {
      TimetableBuildOptions relaxed = relaxedOptions(options, target, next.getAsInt());
      List<FeederHold> tries = structural ? holds.feasible(relaxed) : List.of(FeederHold.NONE);
      for (FeederHold hold : tries) {
        Optional<Found> found = tryAt(holds, hold, relaxed, builtAt, failed);
        if (found.isEmpty()) {
          continue;
        }
        if (candidates.skipped() > 0) {
          searchNotes.add(
              String.format(
                  Locale.ROOT,
                  "搜索逐秒预筛：%d–%ds 之间 %d 档间隔端点或车库咽喉在结构上错不开（喂车多停 %d 秒内也不行），没有逐一构建",
                  candidates.skippedFrom(),
                  candidates.skippedTo(),
                  candidates.skipped(),
                  FEEDER_HOLD_LIMIT_SECONDS));
        }
        if (!hold.isNone()) {
          searchNotes.add(holds.describe(hold));
        }
        return found;
      }
    }
    return Optional.empty();
  }

  /** 按某种多停完整构建一次；干净就交回，否则把失败的间隔记进 {@code failed}。 */
  private Optional<Found> tryAt(
      HoldSearch holds,
      FeederHold hold,
      TimetableBuildOptions options,
      Instant builtAt,
      List<Failure> failed) {
    Prepared variant;
    try {
      variant = holds.prepared(hold);
    } catch (BuildFailure ignored) {
      return Optional.empty();
    }
    BuildInput variantInput = holds.input(hold);
    Attempt candidate;
    try {
      candidate = attempt(variant, options, variantInput, builtAt);
    } catch (BuildFailure ignored) {
      return Optional.empty();
    }
    if (!candidate.clean()) {
      failed.add(new Failure(hold, candidate.intervals()));
      return Optional.empty();
    }
    return Optional.of(new Found(candidate, variant, variantInput, hold));
  }

  /**
   * 等比放宽找到可行间隔之后，逐个单元往回收紧。
   *
   * <p>等比放宽把没卡住的组也一起拖慢：实服三线联编里卡住的是 WS 在 CHT 的折返与车库咽喉，DS 并不参与，却跟着从 200 秒放到 235 秒； 显式给 WS/MT 176、DS
   * 200 照样排得开。
   *
   * <p>按车接续连在一起的组必须同一个间隔，并成一个单元一起收紧，单元内保持目标比例。单元按放宽的秒数从多到少、同秒按名字依次试：
   * 先试目标间隔，再按格点与预筛岛的起点往上，直到当前间隔之前，第一个干净的就收下，其余单元不动。每一步收下的表里所有组都不比上一步慢， 所以结果不会比等比放宽差。最多走 {@value
   * #TIGHTEN_PASSES} 遍：后收紧的单元可能给先前没收紧成功的单元腾出空间（实服三线联编里 WS 排在最后，它不先收紧 MT 就收不下来），一遍里谁都收不动就停。
   *
   * <p>剪枝：候选间隔若已经排不开过、而且当时单元外每一组都不比现在紧，就不再完整构建——单元外更紧只会多添约束。
   * 可行性对间隔并不单调，这是经验规则，换来的是省掉注定失败的构建（实服三线联编里一次 71 秒）。
   *
   * @param relaxed 等比放宽找到的可行尝试
   * @param notes 收紧了哪些组，进报告
   * @param failed 排不开的间隔；收紧失败的也往里追加
   * @param hold 放宽时选中的喂车多停；收紧沿用它，只有同一种多停下的失败才拿来剪枝
   * @return 收紧后的尝试；一个单元也收不紧时就是 {@code relaxed}
   */
  private Attempt tightenGroups(
      Prepared prepared,
      TimetableBuildOptions options,
      Attempt target,
      Attempt relaxed,
      BuildInput input,
      Instant builtAt,
      List<String> notes,
      List<Failure> failed,
      FeederHold hold) {
    Attempt current = relaxed;
    List<Set<String>> units = tighteningUnits(prepared, options, target, relaxed);
    for (int pass = 0; pass < TIGHTEN_PASSES; pass++) {
      boolean changed = false;
      for (Set<String> unit : units) {
        int targetMin = minInterval(target.intervals(), unit);
        int currentMin = minInterval(current.intervals(), unit);
        if (currentMin <= targetMin) {
          continue;
        }
        Attempt base = current;
        IntPredicate structuralPass =
            structuralClearance(prepared, options).isPresent()
                ? headway -> {
                  OptionalInt clearance =
                      structuralClearance(
                          prepared, unitOptions(options, target, base, unit, headway));
                  return clearance.isEmpty() || clearance.getAsInt() >= 0;
                }
                : null;
        Optional<Attempt> tightened = Optional.empty();
        if (structuralPass == null || structuralPass.test(targetMin)) {
          tightened =
              tryTightening(
                  prepared,
                  unitOptions(options, target, base, unit, targetMin),
                  unit,
                  input,
                  builtAt,
                  failed,
                  hold);
        }
        HeadwayCandidates candidates =
            new HeadwayCandidates(
                targetMin, currentMin - 1, HEADWAY_SEARCH_STEP_SECONDS, structuralPass);
        for (OptionalInt next = candidates.next();
            tightened.isEmpty() && next.isPresent();
            next = candidates.next()) {
          tightened =
              tryTightening(
                  prepared,
                  unitOptions(options, target, base, unit, next.getAsInt()),
                  unit,
                  input,
                  builtAt,
                  failed,
                  hold);
        }
        if (tightened.isPresent()) {
          current = tightened.get();
          changed = true;
          List<String> changes = new ArrayList<>();
          for (String group : unit) {
            changes.add(
                group
                    + " "
                    + base.intervals().get(group)
                    + "s → "
                    + current.intervals().get(group)
                    + "s");
          }
          notes.add(
              "逐组收紧："
                  + String.join("、", changes)
                  + (minInterval(current.intervals(), unit) == targetMin ? "（回到目标）" : ""));
        }
      }
      if (!changed) {
        break;
      }
    }
    return current;
  }

  /**
   * 收紧一个单元的一次尝试，只在干净时返回；构建失败或留有让不掉的冲突都算不行，失败的间隔记进 {@code failed}。 已被 {@code failed}
   * 里某次失败覆盖的候选直接跳过（见 {@link #tightenGroups} 的剪枝）。
   */
  private Optional<Attempt> tryTightening(
      Prepared prepared,
      TimetableBuildOptions options,
      Set<String> unit,
      BuildInput input,
      Instant builtAt,
      List<Failure> failed,
      FeederHold hold) {
    Map<String, Integer> intervals = new TreeMap<>();
    for (ServiceGroupClassifier.Group group : prepared.classification().groups()) {
      intervals.put(group.name(), options.intervalFor(group.name()));
    }
    for (Failure known : failed) {
      // 多停不同，时分就不同：别的多停下的失败说明不了这里。
      if (known.hold().equals(hold) && dominates(known.intervals(), intervals, unit)) {
        return Optional.empty();
      }
    }
    try {
      Attempt attempt = attempt(prepared, options, input, builtAt);
      if (attempt.clean()) {
        return Optional.of(attempt);
      }
      failed.add(new Failure(hold, attempt.intervals()));
    } catch (BuildFailure ignored) {
      failed.add(new Failure(hold, intervals));
    }
    return Optional.empty();
  }

  /** {@code known} 这次失败是否覆盖 {@code candidate}：单元内间隔相同，单元外每一组都不比候选紧。 */
  static boolean dominates(
      Map<String, Integer> known, Map<String, Integer> candidate, Set<String> unit) {
    for (Map.Entry<String, Integer> entry : candidate.entrySet()) {
      Integer seen = known.get(entry.getKey());
      if (seen == null) {
        return false;
      }
      boolean inUnit = unit.contains(entry.getKey());
      if (inUnit ? !seen.equals(entry.getValue()) : seen < entry.getValue()) {
        return false;
      }
    }
    return true;
  }

  /**
   * 收紧的单元：按车接续连在一起的组并成一个单元（接续要求两组同一个间隔），只看真正上网格的组。
   *
   * <p>顺序：放宽得多的先试，同秒按单元里最小的组名——确定，与遍历顺序无关。
   */
  private static List<Set<String>> tighteningUnits(
      Prepared prepared, TimetableBuildOptions options, Attempt target, Attempt relaxed) {
    Set<UUID> gridRoutes = new HashSet<>();
    for (TimetableRoutePlan plan : prepared.operationPlans()) {
      gridRoutes.add(plan.routeId());
    }
    List<ServiceGroupClassifier.Group> gridGroups =
        gridGroupsOf(prepared.classification().groups(), gridRoutes);
    Map<UUID, String> groupByRoute = new HashMap<>();
    Map<String, String> parent = new TreeMap<>();
    for (ServiceGroupClassifier.Group group : gridGroups) {
      parent.put(group.name(), group.name());
      for (ServiceGroupClassifier.Direction direction : group.directions()) {
        for (UUID routeId : direction.routeIds()) {
          groupByRoute.put(routeId, group.name());
        }
      }
    }
    PhasePlanner.Phases phases =
        planPhases(
            prepared,
            options.withIntervals(options.headway(), relaxed.intervals()),
            relaxed.intervals(),
            gridGroups);
    for (PhasePlanner.Connection connection : phases.connections()) {
      List<UUID> linked = new ArrayList<>(connection.feederRoutes());
      linked.addAll(connection.fedRoutes());
      String anchor = null;
      for (UUID routeId : linked) {
        String group = groupByRoute.get(routeId);
        if (group == null) {
          continue;
        }
        if (anchor == null) {
          anchor = group;
        } else {
          parent.put(root(parent, group), root(parent, anchor));
        }
      }
    }
    Map<String, Set<String>> byRoot = new TreeMap<>();
    for (String group : parent.keySet()) {
      byRoot.computeIfAbsent(root(parent, group), key -> new TreeSet<>()).add(group);
    }
    List<Set<String>> units = new ArrayList<>(byRoot.values());
    units.sort(
        Comparator.comparingInt(
                (Set<String> unit) ->
                    minInterval(target.intervals(), unit) - minInterval(relaxed.intervals(), unit))
            .thenComparing(unit -> unit.iterator().next()));
    return units;
  }

  private static String root(Map<String, String> parent, String group) {
    String current = group;
    while (!parent.get(current).equals(current)) {
      current = parent.get(current);
    }
    return current;
  }

  private static int minInterval(Map<String, Integer> intervals, Set<String> unit) {
    return unit.stream().mapToInt(intervals::get).min().orElse(0);
  }

  /** 单元里最小的组间隔走到 {@code headway}，单元内按目标比例；单元外的组保持 {@code base} 的间隔。 */
  private static TimetableBuildOptions unitOptions(
      TimetableBuildOptions options, Attempt target, Attempt base, Set<String> unit, int headway) {
    double factor = (double) headway / minInterval(target.intervals(), unit);
    Map<String, Integer> intervals = new TreeMap<>(base.intervals());
    for (String group : unit) {
      intervals.put(group, (int) Math.round(target.intervals().get(group) * factor));
    }
    return options.withIntervals(options.headway(), intervals);
  }

  /** 搜索要完整构建的间隔，升序、惰性：有结构预筛时逐秒问一遍，预筛不过的跳过（计数）；过了的只在格点上、 以及每一段"过"的区间的第一秒上交出去。没有预筛时只交格点。 */
  static final class HeadwayCandidates {
    private final int limit;
    private final int step;
    private final int target;
    private final IntPredicate structuralPass;
    private int headway;
    private boolean previousPassed;
    private int skipped;
    private int skippedFrom;
    private int skippedTo;

    /**
     * @param target 目标间隔（不含：它已经试过）
     * @param limit 上限（含）
     * @param step 格点步长
     * @param structuralPass 结构预筛；为 null 时没有预筛
     */
    HeadwayCandidates(int target, int limit, int step, IntPredicate structuralPass) {
      this.target = target;
      this.limit = limit;
      this.step = Math.max(1, step);
      this.structuralPass = structuralPass;
      this.headway = target;
    }

    OptionalInt next() {
      while (headway < limit) {
        headway++;
        boolean onLattice = (headway - target) % step == 0;
        if (structuralPass == null) {
          if (onLattice) {
            return OptionalInt.of(headway);
          }
          continue;
        }
        boolean passed = structuralPass.test(headway);
        boolean islandStart = passed && !previousPassed;
        previousPassed = passed;
        if (!passed) {
          skipped++;
          skippedFrom = skippedFrom == 0 ? headway : skippedFrom;
          skippedTo = headway;
          continue;
        }
        if (onLattice || islandStart) {
          return OptionalInt.of(headway);
        }
      }
      return OptionalInt.empty();
    }

    int skipped() {
      return skipped;
    }

    int skippedFrom() {
      return skippedFrom;
    }

    int skippedTo() {
      return skippedTo;
    }
  }

  /** 所有组等比放宽：最小的组间隔走到 {@code headway}，其余按同一比例——组间比例不变，回写时才对得上。 */
  private static TimetableBuildOptions relaxedOptions(
      TimetableBuildOptions options, Attempt target, int headway) {
    int targetHeadway = target.headwaySeconds();
    double factor = (double) headway / targetHeadway;
    long fallbackHeadway = Math.max(1L, options.headway().toSeconds());
    Map<String, Integer> scaled = new TreeMap<>();
    target.intervals().forEach((group, base) -> scaled.put(group, (int) Math.round(base * factor)));
    return options.withIntervals(Duration.ofSeconds(Math.round(fallbackHeadway * factor)), scaled);
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
      secondsByRoute.put(
          route.routeId(),
          TimetableTimingCalculator.terminalStopSeconds(
              input.graph(), input.runTimeModel(), route.definition(), route.stops(), fallback));
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

  /** 前两层相位与跨组接续：{@link #attempt} 与搜索的结构预筛共用，同一组间隔两边算出同一个相位。 */
  private static PhasePlanner.Phases planPhases(
      Prepared prepared,
      TimetableBuildOptions options,
      Map<String, Integer> intervalByGroup,
      List<ServiceGroupClassifier.Group> gridGroups) {
    int separation = (int) Math.min(Integer.MAX_VALUE, options.separation().toSeconds());
    return PhasePlanner.plan(
        gridGroups,
        intervalByGroup,
        prepared.runByRoute(),
        options.dutyLimits().turnaround(),
        options.horizonSeconds(),
        stopCalls(gridGroups, prepared),
        topologyOf(prepared, prepared.operationPlans(), options, separation));
  }

  /**
   * 结构预筛：只跑相位层（毫秒级），给出按车接续在端点与车库咽喉上留下的最小间隙；没有按车接续时为空。
   * 负数说明远端多等在上限内怎么挑都错不开——这个间隔下端点或咽喉必有冲突，不用完整构建就知道。
   */
  private static OptionalInt structuralClearance(Prepared prepared, TimetableBuildOptions options) {
    return clearanceOf(structuralPhases(prepared, options));
  }

  /** 按车接续留下的最小间隙；没有按车接续时为空。 */
  private static OptionalInt clearanceOf(PhasePlanner.Phases phases) {
    return phases.connections().stream().mapToInt(PhasePlanner.Connection::clearanceSeconds).min();
  }

  /** 结构预筛用的前两层相位（含跨组按车接续与远端多等）。 */
  private static PhasePlanner.Phases structuralPhases(
      Prepared prepared, TimetableBuildOptions options) {
    Map<String, Integer> intervalByGroup = new TreeMap<>();
    for (ServiceGroupClassifier.Group group : prepared.classification().groups()) {
      intervalByGroup.put(group.name(), options.intervalFor(group.name()));
    }
    Set<UUID> gridRoutes = new HashSet<>();
    for (TimetableRoutePlan plan : prepared.operationPlans()) {
      gridRoutes.add(plan.routeId());
    }
    List<ServiceGroupClassifier.Group> gridGroups =
        gridGroupsOf(prepared.classification().groups(), gridRoutes);
    return planPhases(prepared, options, intervalByGroup, gridGroups);
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
   * 相位层要的路网形状：容量 1 的端点（与端点串行同一份判定）、一次折返的占用（与端点串行同一个成本公式： 进站走行 + 折返 + 出站走行 +
   * 裕量；没有下一班时离开走的是回库线路），以及车库咽喉的几何（与端点串行同一份咽喉判定）。
   */
  private static PhasePlanner.Topology topologyOf(
      Prepared prepared,
      List<TimetableRoutePlan> operationPlans,
      TimetableBuildOptions options,
      int separation) {
    List<TimetableConflictChecker.RouteProfile> operationProfiles = new ArrayList<>();
    List<UUID> operationRoutes = new ArrayList<>();
    for (TimetableRoutePlan plan : operationPlans) {
      operationRoutes.add(plan.routeId());
      TimetableConflictChecker.RouteProfile profile = prepared.profiles().get(plan.routeId());
      if (profile != null) {
        operationProfiles.add(profile);
      }
    }
    Set<String> stubs =
        new HashSet<>(TerminalSerializer.terminalGroups(prepared.graphIndex(), operationProfiles));
    Map<UUID, TimetableRoutePlan> planById = new HashMap<>();
    for (TimetableRoutePlan plan : prepared.plans()) {
      planById.put(plan.routeId(), plan);
    }
    TurnaroundTable turnarounds = options.dutyLimits().turnaround();
    SingleLineSectionIndex sections = prepared.graphIndex().sections();
    VehicleDutyPlanner.Legs legs = prepared.legs();
    PhasePlanner.FarEndCost cost =
        (group, arriving, departing) -> {
          UUID leaving =
              departing != null
                  ? departing
                  : returnAfter(planById.get(arriving), null, legs).orElse(null);
          TimetableConflictChecker.RouteProfile in =
              arriving == null ? null : prepared.profiles().get(arriving);
          TimetableConflictChecker.RouteProfile out =
              leaving == null ? null : prepared.profiles().get(leaving);
          return (in == null ? 0 : TerminalSerializer.approachIn(in, group, sections))
              + (arriving == null ? 0 : turnarounds.secondsFor(arriving))
              + (out == null ? 0 : TerminalSerializer.approachOut(out, group, sections))
              + Math.max(0, separation);
        };
    DepotThroats throats = DepotThroats.of(prepared.profiles(), operationRoutes, legs);
    PhasePlanner.ThroatGeometry geometry =
        (feederRoute, backRoute) -> {
          Optional<DepotThroats.Passage> out = throats.outbound(feederRoute);
          TimetableRoutePlan feederPlan = planById.get(feederRoute);
          Optional<DepotThroats.Passage> in =
              returnAfter(
                      planById.get(backRoute),
                      feederPlan == null ? null : feederPlan.originNodeId(),
                      legs)
                  .flatMap(throats::inbound);
          if (out.isEmpty() || in.isEmpty() || !out.get().depot().equals(in.get().depot())) {
            return Optional.empty();
          }
          int turnaround = turnarounds.secondsFor(backRoute);
          int sep = Math.max(0, separation);
          return Optional.of(
              new PhasePlanner.ThroatWindows(
                  out.get().enterOffset(),
                  out.get().exitOffset() + sep,
                  turnaround + in.get().enterOffset(),
                  turnaround + in.get().exitOffset() + sep));
        };
    return new PhasePlanner.Topology(
        stubs,
        cost,
        geometry,
        inPlaceTurnbackRoutes(operationPlans, legs),
        mainlineTurnbackRoutes(operationPlans));
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
   * <p>没有出入库线路的站台（例如 WS 的终点 NTA）车停着不挡人，但一辆车也调不走，只能等本端的下一班：周期余量若堆在那一端，
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

  /** 这条 route 跑完之后回库走的线路：与派车器同一条选法（优先回出库的那座车库）。 */
  private static Optional<UUID> returnAfter(
      TimetableRoutePlan plan, String preferredDepotNodeId, VehicleDutyPlanner.Legs legs) {
    if (plan == null) {
      return Optional.empty();
    }
    return legs.returnLegAt(plan.terminalNodeId(), preferredDepotNodeId)
        .map(VehicleDutyPlanner.Leg::routeId);
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

  /**
   * 各方向的周期模板：第三层拿它按周期铺开评估相位。
   *
   * <p>「单班交路」的判据是 {@code maxTripsPerDuty == 1}，或这个方向的班次从车库始发（出库班跑一趟就回库， DS 与 MT-2
   * 就是这个形态）。这类流的回库走行每周期都会经过车库咽喉，不放进模板就看不见 WS 那 189 处冲突。
   */
  private static Map<String, PeriodicTemplate> periodicTemplates(
      Prepared prepared,
      List<ServiceGroupClassifier.Group> groups,
      Map<String, Integer> intervalByGroup,
      TimetableBuildOptions options) {
    Map<UUID, String> terminalByRoute = new HashMap<>();
    Map<UUID, Boolean> startsAtDepotByRoute = new HashMap<>();
    for (TimetableRoutePlan plan : prepared.plans()) {
      terminalByRoute.put(plan.routeId(), plan.terminalNodeId());
    }
    for (OperationPlan op : prepared.operations()) {
      startsAtDepotByRoute.put(op.route().routeId(), op.startsAtDepot());
    }
    Map<String, PeriodicTemplate> out = new LinkedHashMap<>();
    for (ServiceGroupClassifier.Group group : groups) {
      int interval = intervalByGroup.getOrDefault(group.name(), 0);
      if (interval <= 0) {
        continue;
      }
      for (ServiceGroupClassifier.Direction direction : group.directions()) {
        boolean singleTrip =
            options.dutyLimits().maxTripsPerDuty() == 1
                || direction.routeIds().stream()
                    .anyMatch(id -> startsAtDepotByRoute.getOrDefault(id, false));
        out.put(
            direction.key(),
            PeriodicTemplate.of(
                direction,
                interval,
                prepared.runByRoute(),
                prepared.legs(),
                options.dutyLimits().turnaround(),
                terminalByRoute,
                singleTrip));
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
      Set<UUID> passengerReturns,
      Map<UUID, Integer> runByRoute) {

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
          passengerReturns,
          Map.copyOf(run));
    }
  }

  /**
   * 按车接续的喂车方向中途多停：这几条 route 在各自第一个中途停车点多停 {@code seconds} 秒。
   *
   * @param routeIds 喂车方向的 route
   * @param seconds 多停秒数；0 表示不多停
   */
  record FeederHold(List<UUID> routeIds, int seconds) {
    static final FeederHold NONE = new FeederHold(List.of(), 0);

    FeederHold {
      routeIds = routeIds == null ? List.of() : List.copyOf(routeIds);
    }

    boolean isNone() {
      return seconds <= 0;
    }
  }

  /** 一次排不开的完整构建：当时的多停与各组间隔。 */
  private record Failure(FeederHold hold, Map<String, Integer> intervals) {}

  /** 搜索找到的可行尝试，连同它用的准备、输入与多停。 */
  private record Found(Attempt attempt, Prepared prepared, BuildInput input, FeederHold hold) {}

  /**
   * 喂车方向中途多停的搜索：给定间隔下结构上排得开的多停有哪些，以及某种多停对应的输入与准备。
   *
   * <p>多停加在喂车方向每条 route 的第一个中途停车点——过了出库咽喉、还没到端点，于是出库过咽喉提前、到端点不变。 结构判断只把这几条 route
   * 的全程走行加上多停秒数，让相位层重算按车接续（毫秒级）；真要完整构建时才改 route 的停站、重算时分与投影。 表上写的就是加长的停站，运行时靠"早到等点"执行。
   */
  private final class HoldSearch {
    private final Prepared base;
    private final TimetableBuildOptions options;
    private final BuildInput input;
    private final Map<Map<String, Integer>, List<FeederHold>> feasibleByIntervals = new HashMap<>();
    private final Map<FeederHold, Prepared> preparedByHold = new HashMap<>();
    private final Map<FeederHold, BuildInput> inputByHold = new HashMap<>();

    HoldSearch(Prepared base, TimetableBuildOptions options, BuildInput input) {
      this.base = base;
      this.options = options;
      this.input = input;
    }

    /**
     * 这组间隔下值得完整构建的多停，不多停在前、其余按秒数从短到长，最多 {@value #FEEDER_HOLD_TRIES} 种多停。
     *
     * <p>多停有两种作用。一是结构上的：喂车方向到端点不变、出库过咽喉提前，把端点与咽喉两个约束解开——相位层看得见，
     * 结构上排不开的多停一律不试。二是沿线的：喂车方向到端点前的那一段整体后移，与同向别的方向在共用站台上错开 （实服 1L 与 2N 同向共用
     * HHU:2、KPO:2、LYM:2、PHI:2）——相位层看不见，
     * 此时远端多等会把多停抵消掉，结构间隙对多停不敏感，只有完整构建才知道。所以候选取两类：结构上排得开的每一段区间里间隙最大的那一秒， 以及每 {@value
     * #FEEDER_HOLD_GRID_SECONDS} 秒一档里结构上排得开的那些。实服 WS@156：不多停撞，多停 10 秒撞，20–45 秒排得开，60 秒又撞。
     *
     * <p>没有按车接续时只有 NONE（交给完整构建判）。
     */
    List<FeederHold> feasible(TimetableBuildOptions at) {
      return feasibleByIntervals.computeIfAbsent(intervalsOf(base, at), key -> scan(at));
    }

    private List<FeederHold> scan(TimetableBuildOptions at) {
      PhasePlanner.Phases phases = structuralPhases(base, at);
      OptionalInt clearance = clearanceOf(phases);
      List<FeederHold> out = new ArrayList<>();
      if (clearance.isEmpty() || clearance.getAsInt() >= 0) {
        out.add(FeederHold.NONE);
      }
      if (clearance.isEmpty()) {
        return out;
      }
      List<UUID> feeders = holdableFeeders(phases.connections());
      if (feeders.isEmpty()) {
        return out;
      }
      int limit = Math.min(FEEDER_HOLD_LIMIT_SECONDS, minInterval(intervalsOf(base, at)) - 1);
      int[] gap = new int[limit + 1];
      gap[0] = clearance.getAsInt();
      for (int h = 1; h <= limit; h++) {
        OptionalInt held = clearanceOf(structuralPhases(base.withExtraRun(feeders, h), at));
        gap[h] = held.isPresent() ? held.getAsInt() : Integer.MIN_VALUE;
      }
      Set<Integer> picks = new TreeSet<>();
      // 结构上排得开的每一段区间取间隙最大的那一秒（并列取较短的）；从 0 起的那一段由 NONE 代表。
      int h = 1;
      while (h <= limit) {
        if (gap[h] < 0) {
          h++;
          continue;
        }
        int start = h;
        int best = h;
        while (h <= limit && gap[h] >= 0) {
          if (gap[h] > gap[best]) {
            best = h;
          }
          h++;
        }
        if (start > 1 || gap[0] < 0) {
          picks.add(best);
        }
      }
      for (int step = FEEDER_HOLD_GRID_SECONDS; step <= limit; step += FEEDER_HOLD_GRID_SECONDS) {
        if (gap[step] >= 0) {
          picks.add(step);
        }
      }
      for (int pick : picks) {
        if (out.size() - (out.contains(FeederHold.NONE) ? 1 : 0) >= FEEDER_HOLD_TRIES) {
          break;
        }
        out.add(new FeederHold(feeders, pick));
      }
      return out;
    }

    /** 喂车方向的 route；有一条找不到中途停车点就整体不多停——同一方向的车必须一起挪，否则相位对不上。 */
    private List<UUID> holdableFeeders(List<PhasePlanner.Connection> connections) {
      Set<UUID> feeders = new java.util.LinkedHashSet<>();
      for (PhasePlanner.Connection connection : connections) {
        feeders.addAll(connection.feederRoutes());
      }
      for (UUID routeId : feeders) {
        Optional<RouteInput> route = routeOf(routeId);
        if (route.isEmpty() || firstIntermediateStop(route.get().stops()).isEmpty()) {
          return List.of();
        }
      }
      return List.copyOf(feeders);
    }

    BuildInput input(FeederHold hold) {
      if (hold.isNone()) {
        return input;
      }
      return inputByHold.computeIfAbsent(
          hold,
          key -> {
            List<RouteInput> routes = new ArrayList<>(input.routes().size());
            for (RouteInput route : input.routes()) {
              routes.add(
                  key.routeIds().contains(route.routeId())
                      ? held(route, key.seconds(), options.defaultDwell())
                      : route);
            }
            return input.withRoutes(routes);
          });
    }

    /** 按多停重算的准备：停站变了，时分、投影、走行都跟着变；图索引沿用。 */
    Prepared prepared(FeederHold hold) {
      if (hold.isNone()) {
        return base;
      }
      Prepared known = preparedByHold.get(hold);
      if (known == null) {
        known = prepare(input(hold), options, new ArrayList<>(), base.graphIndex());
        preparedByHold.put(hold, known);
      }
      return known;
    }

    /** 报告里的一行：谁在哪一站多停了多少。 */
    String describe(FeederHold hold) {
      List<String> parts = new ArrayList<>();
      for (UUID routeId : hold.routeIds()) {
        routeOf(routeId)
            .ifPresent(
                route ->
                    firstIntermediateStop(route.stops())
                        .ifPresent(
                            index ->
                                parts.add(
                                    route.routeCode()
                                        + " 在 "
                                        + route.definition().waypoints().get(index).value())));
      }
      return "按车接续：喂车方向 "
          + String.join("、", parts)
          + " 多停 "
          + hold.seconds()
          + "s（写进停站，运行时按早到等点扣留）";
    }

    private Optional<RouteInput> routeOf(UUID routeId) {
      return input.routes().stream().filter(route -> route.routeId().equals(routeId)).findFirst();
    }
  }

  /** 第一个中途停车点（首末之间第一个 STOP）；停靠配置与 waypoints 按下标对齐。 */
  static Optional<Integer> firstIntermediateStop(List<RouteStop> stops) {
    for (int i = 1; i + 1 < stops.size(); i++) {
      if (stops.get(i).passType() == RouteStopPassType.STOP) {
        return Optional.of(i);
      }
    }
    return Optional.empty();
  }

  /** 在第一个中途停车点多停 {@code seconds} 秒：没配 dwell 的按缺省停站算起。找不到中途停车点时原样返回。 */
  static RouteInput held(RouteInput route, int seconds, Duration defaultDwell) {
    Optional<Integer> at = firstIntermediateStop(route.stops());
    if (at.isEmpty() || seconds <= 0) {
      return route;
    }
    List<RouteStop> stops = new ArrayList<>(route.stops());
    RouteStop stop = stops.get(at.get());
    int dwell = stop.dwellSeconds().orElse((int) defaultDwell.toSeconds());
    stops.set(
        at.get(),
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

  private static Map<String, Integer> intervalsOf(
      Prepared prepared, TimetableBuildOptions options) {
    Map<String, Integer> intervals = new TreeMap<>();
    for (ServiceGroupClassifier.Group group : prepared.classification().groups()) {
      intervals.put(group.name(), options.intervalFor(group.name()));
    }
    return intervals;
  }

  private static int minInterval(Map<String, Integer> intervals) {
    return intervals.values().stream().mapToInt(Integer::intValue).min().orElse(1);
  }

  /** 按某个 headway 排出来的一份完整计划及其冲突报告。 */
  private record Attempt(
      int headwaySeconds,
      Timetable timetable,
      List<TimetableBuildResult.DroppedTrip> dropped,
      List<WeightedTripAllocator.ShareReport> shares,
      TimetableConflictChecker.Report conflicts,
      List<ConflictAbsorption.Residual> residuals,
      List<TimetableBuildResult.TripShift> shifts,
      List<ResourceRepair.Yield> yields,
      List<TerminalSerializer.TerminalReport> terminals,
      List<TerminalSerializer.ThroatReport> throats,
      Map<String, Integer> intervals,
      List<PhasePlanner.Interleave> interleaves,
      List<String> phaseNotes,
      List<String> resourceNotes,
      List<PhasePlanner.Residue> residues) {

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

  /**
   * 端点串行与让车修复两轮偏离合成一份：名义时隙取第一轮的（网格），实际发车取最后一轮的，原因取后一轮改过它的那个。 一班在两轮里都动过时只报一条，否则"偏离网格 N 班"会把同一班数两次。
   */
  static List<TerminalSerializer.Shift> mergeShifts(
      List<TerminalSerializer.Shift> first, List<TerminalSerializer.Shift> second) {
    Map<UUID, TerminalSerializer.Shift> merged = new java.util.LinkedHashMap<>();
    for (TerminalSerializer.Shift shift : first) {
      merged.put(shift.tripId(), shift);
    }
    for (TerminalSerializer.Shift shift : second) {
      TerminalSerializer.Shift earlier = merged.get(shift.tripId());
      merged.put(
          shift.tripId(),
          new TerminalSerializer.Shift(
              shift.tripId(),
              earlier == null ? shift.nominalSeconds() : earlier.nominalSeconds(),
              shift.actualSeconds(),
              shift.reason()));
    }
    List<TerminalSerializer.Shift> out = new ArrayList<>(merged.values());
    out.sort(
        Comparator.comparingInt(TerminalSerializer.Shift::actualSeconds)
            .thenComparing(shift -> shift.tripId().toString()));
    return out;
  }

  /** 一个格子连同它所属的方向：份额按方向报，主键与交路按全局候选下标找。 */
  private record Placed(GroupGrid.Slot slot, ServiceGroupClassifier.Direction direction) {}

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
      Map<UUID, UUID> lineByRoute) {

    public BuildInput {
      Objects.requireNonNull(timetableId, "timetableId");
      Objects.requireNonNull(companyId, "companyId");
      Objects.requireNonNull(operatorId, "operatorId");
      Objects.requireNonNull(lineId, "lineId");
      routes = routes == null ? List.of() : List.copyOf(routes);
      notes = notes == null ? Optional.empty() : notes;
      neighbors = neighbors == null ? List.of() : List.copyOf(neighbors);
      lineByRoute = lineByRoute == null ? Map.of() : Map.copyOf(lineByRoute);
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
          lineByRoute);
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
   * @param operationType route 类型；CREATE/RETURN 不进发车表，只提供出库/回库走行
   * @param weight 目标服务比例权重（仅 OPERATION 有意义）
   * @param definition 已解析的交路定义
   * @param stops route 的停靠配置，索引与 waypoints 对齐
   * @param depotNodeId 出库点
   * @param declaredAs 这条线路是被某条运营 route 在 metadata 里显式指定为出库（CREATE）或回库（RETURN）走行线路的 （见 {@link
   *     TimetableRouteMetadata}），可能来自别的 operator；与 {@code operationType} 不符时判为不可行
   * @param external 属于别的 operator：进足迹、进交路，但不受本表管辖（见 {@link TimetableRoutePlan#external()}）
   * @param spawnGroup 交路组（route metadata 的 spawn_group）；空则进 {@link
   *     ServiceGroupClassifier#DEFAULT_GROUP}
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
      Optional<String> spawnGroup) {

    public RouteInput {
      Objects.requireNonNull(routeId, "routeId");
      Objects.requireNonNull(definition, "definition");
      operationType = operationType == null ? RouteOperationType.OPERATION : operationType;
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
          spawnGroup);
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
