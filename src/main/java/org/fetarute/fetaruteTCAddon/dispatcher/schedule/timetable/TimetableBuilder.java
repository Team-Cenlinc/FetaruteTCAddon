package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailTravelTimeModel;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLifecycleMode;
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
  public static final int HEADWAY_SEARCH_STEP_SECONDS = 10;

  /** 搜索可行 headway 时最多放宽到目标的多少倍。 */
  public static final int HEADWAY_SEARCH_MAX_MULTIPLIER = 4;

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
    // 报告与搜索都以"最小的组间隔"为标量：放宽时所有组等比。
    int targetHeadway = target.headwaySeconds();

    Attempt chosen = target;
    List<String> warnings = new ArrayList<>();
    if (!target.conflicts().clean()) {
      String summary =
          TimetableBuildReportText.summarizeConflicts(target.conflicts(), targetHeadway);
      if (options.strictConflicts()) {
        List<String> reasons = new ArrayList<>();
        reasons.add(summary + "；严格模式下不回退");
        reasons.addAll(
            TimetableBuildReportText.describeConflicts(
                target.conflicts(), options.serviceStartSecondOfDay()));
        return TimetableBuildResult.failure(String.join("\n", reasons), prepared.infeasible());
      }
      Optional<Attempt> fallback = searchFeasibleHeadway(prepared, options, target, input, builtAt);
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
      chosen = fallback.get();
      warnings.add(
          summary
              + "，已回退到最小可行间隔 "
              + chosen.headwaySeconds()
              + "s（--strict 可改为构建失败）"
              + (target.conflicts().external().isEmpty()
                  ? ""
                  : "；其中 " + target.conflicts().external().size() + " 处是与已发布邻表的冲突，只能挪自己"));
      warnings.addAll(
          TimetableBuildReportText.describeConflicts(
              target.conflicts(), options.serviceStartSecondOfDay()));
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
        prepared.operationPlans().stream()
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
        groupIntervals(target, chosen),
        chosen.interleaves(),
        dutyShapes(chosen.timetable()),
        chosen.phaseNotes(),
        List.copyOf(warnings));
  }

  private static List<TimetableBuildResult.GroupInterval> groupIntervals(
      Attempt target, Attempt chosen) {
    List<TimetableBuildResult.GroupInterval> out = new ArrayList<>();
    target
        .intervals()
        .forEach(
            (group, seconds) ->
                out.add(
                    new TimetableBuildResult.GroupInterval(
                        group, seconds, chosen.intervals().getOrDefault(group, seconds))));
    return List.copyOf(out);
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
    UUID timetableId = input.timetableId();
    List<TimetableRoutePlan> plans = new ArrayList<>();
    List<OperationPlan> operations = new ArrayList<>();
    // 图索引只建一次：站台映射要用节点类型，冲突扫描要用容量与单线区段。
    TimetableConflictChecker.GraphIndex graphIndex =
        TimetableConflictChecker.GraphIndex.of(input.graph());
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
              input.travelTimeModel(),
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
    PhasePlanner.Phases phases =
        PhasePlanner.plan(
            groups,
            intervalByGroup,
            prepared.runByRoute(),
            options.dutyLimits().turnaround(),
            horizon);
    List<GroupGrid.DirectionGrid> grids = new ArrayList<>();
    List<Placed> placed = new ArrayList<>();
    for (ServiceGroupClassifier.Group group : groups) {
      int interval = intervalByGroup.get(group.name());
      for (ServiceGroupClassifier.Direction direction : group.directions()) {
        int phase = phases.phaseByDirection().getOrDefault(direction.key(), 0);
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
            timetableId, plannedTrips, prepared.legs(), options.dutyLimits(), nextSlotByOrigin);
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
    int separation = (int) Math.min(Integer.MAX_VALUE, options.separation().toSeconds());
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

    // 份额按方向报：weight 只在同方向多 route 之间切，跨方向、跨组比没有意义。
    Map<String, List<WeightedTripAllocator.Allocation>> keptByDirection = new TreeMap<>();
    for (TimetableTrip trip : serialized.timetable().trips()) {
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
        List.copyOf(shifts),
        yields,
        serialized.terminals(),
        Map.copyOf(intervalByGroup),
        PhasePlanner.interleaves(grids),
        phases.notes());
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
   * 从目标 headway 向上逐步放宽，找第一个排出来没有冲突的间隔。
   *
   * <p>只放宽 headway、不挪动单个班次：表的结构（SWRR 序列、duty 链）在任何间隔下都用同一套规则生成， 因此"建议值"是一个可以直接写回配置的数，而不是一次性的手工调整。
   */
  private Optional<Attempt> searchFeasibleHeadway(
      Prepared prepared,
      TimetableBuildOptions options,
      Attempt target,
      BuildInput input,
      Instant builtAt) {
    int targetHeadway = target.headwaySeconds();
    int limit = targetHeadway * HEADWAY_SEARCH_MAX_MULTIPLIER;
    long fallbackHeadway = Math.max(1L, options.headway().toSeconds());
    for (int headway = targetHeadway + HEADWAY_SEARCH_STEP_SECONDS;
        headway <= limit;
        headway += HEADWAY_SEARCH_STEP_SECONDS) {
      // 所有组等比放宽：最小的组间隔走到 headway，其余按同一比例——组间比例不变，回写时才对得上。
      double factor = (double) headway / targetHeadway;
      Map<String, Integer> scaled = new TreeMap<>();
      target
          .intervals()
          .forEach((group, base) -> scaled.put(group, (int) Math.round(base * factor)));
      TimetableBuildOptions relaxed =
          options.withIntervals(Duration.ofSeconds(Math.round(fallbackHeadway * factor)), scaled);
      Attempt candidate;
      try {
        candidate = attempt(prepared, relaxed, input, builtAt);
      } catch (BuildFailure ignored) {
        continue;
      }
      if (candidate.conflicts().clean()) {
        return Optional.of(candidate);
      }
    }
    return Optional.empty();
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
   * 折返时间表：{@code --turnaround} 显式覆盖时原样保留，否则按各 route 终到停靠点的 dwell 建表。
   *
   * <p>dwell 的解析走 {@link TimetableTimingCalculator#terminalDwellSeconds}，与行程时分同一套规则—— 折返不是新造的事实，就是
   * route 定义里那个一直没有消费者的数。
   */
  private static TurnaroundTable resolveTurnarounds(
      BuildInput input, TimetableBuildOptions options) {
    TurnaroundTable requested = options.dutyLimits().turnaround();
    if (requested.fixed()) {
      return requested;
    }
    Map<UUID, List<RouteStop>> stopsByRoute = new LinkedHashMap<>();
    for (RouteInput route : input.sortedRoutes()) {
      stopsByRoute.put(route.routeId(), route.stops());
    }
    return TurnaroundTable.ofStops(stopsByRoute, (int) options.defaultDwell().toSeconds());
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
      Map<UUID, Integer> runByRoute) {}

  /** 按某个 headway 排出来的一份完整计划及其冲突报告。 */
  private record Attempt(
      int headwaySeconds,
      Timetable timetable,
      List<TimetableBuildResult.DroppedTrip> dropped,
      List<WeightedTripAllocator.ShareReport> shares,
      TimetableConflictChecker.Report conflicts,
      List<TimetableBuildResult.TripShift> shifts,
      List<ResourceRepair.Yield> yields,
      List<TerminalSerializer.TerminalReport> terminals,
      Map<String, Integer> intervals,
      List<PhasePlanner.Interleave> interleaves,
      List<String> phaseNotes) {}

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
   * @param travelTimeModel 行程时间模型
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
      RailTravelTimeModel travelTimeModel,
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
        RailTravelTimeModel travelTimeModel,
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
          travelTimeModel,
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
        RailTravelTimeModel travelTimeModel,
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
          travelTimeModel,
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
