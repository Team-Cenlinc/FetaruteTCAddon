package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailTravelTimeModel;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLifecycleMode;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnDirectiveParser;

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

  /** 取消班次的警告里最多列出多少个时刻。 */
  private static final int DROPPED_TIMES_IN_WARNING = 6;

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
  public TimetableBuildResult build(BuildInput input, TimetableBuildOptions options, Instant now) {
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(options, "options");
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

    // ---- 2–4. 按目标 headway 排一次 --------------------------------------
    int targetHeadway = (int) Math.max(1L, options.headway().toSeconds());
    Attempt target;
    try {
      target = attempt(prepared, options);
    } catch (BuildFailure failure) {
      return TimetableBuildResult.failure(failure.getMessage(), prepared.infeasible());
    }

    Attempt chosen = target;
    List<String> warnings = new ArrayList<>();
    if (!target.conflicts().clean()) {
      String summary = summarizeConflicts(target.conflicts(), targetHeadway);
      if (options.strictConflicts()) {
        List<String> reasons = new ArrayList<>();
        reasons.add(summary + "；严格模式下不回退");
        reasons.addAll(describeConflicts(target.conflicts(), options));
        return TimetableBuildResult.failure(String.join("\n", reasons), prepared.infeasible());
      }
      Optional<Attempt> fallback = searchFeasibleHeadway(prepared, options, targetHeadway);
      if (fallback.isEmpty()) {
        return TimetableBuildResult.failure(
            summary
                + "；放宽到 "
                + targetHeadway * HEADWAY_SEARCH_MAX_MULTIPLIER
                + "s 仍找不到无冲突的间隔，检查单线区段、站台数量与折返时间",
            prepared.infeasible());
      }
      chosen = fallback.get();
      warnings.add(summary + "，已回退到最小可行间隔 " + chosen.headwaySeconds() + "s（--strict 可改为构建失败）");
      warnings.addAll(describeConflicts(target.conflicts(), options));
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
    warnings.addAll(describeDropped(chosen.dropped()));
    VehicleDutyPlanner.Result finalDuties =
        new VehicleDutyPlanner.Result(
            chosen.duties(), List.of(), chosen.duties().size(), List.of());
    if (!finalDuties.allDutiesReturnToStorage()) {
      // 理论上 VehicleDuty 的构造器已经挡住了这种情况；留一条断言式警告，避免静默降级。
      warnings.add("存在没有回库端点的 duty，这是一个不应发生的状态");
    }

    Timetable timetable =
        new Timetable(
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
            chosen.trips(),
            chosen.duties(),
            input.notes(),
            builtAt,
            builtAt);

    int longestTrip =
        prepared.operationPlans().stream()
            .mapToInt(TimetableRoutePlan::totalRunSeconds)
            .max()
            .orElse(0);
    return new TimetableBuildResult(
        Optional.of(timetable),
        chosen.shares(),
        prepared.infeasible(),
        chosen.dropped(),
        chosen.duties().size(),
        finalDuties.spawnedVehicles(),
        finalDuties.peakConcurrentVehicles(),
        finalDuties.maxTripsInAnyDuty(),
        finalDuties.maxDutyDurationSeconds(),
        finalDuties.allDutiesReturnToStorage(),
        longestTrip,
        targetHeadway,
        chosen.headwaySeconds(),
        target.conflicts().conflicts(),
        List.copyOf(warnings));
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
    List<VehicleDutyPlanner.Leg> createLegs = new ArrayList<>();
    List<VehicleDutyPlanner.Leg> returnLegs = new ArrayList<>();
    Map<UUID, String> legStation = new HashMap<>();
    Map<UUID, TimetableConflictChecker.RouteProfile> profiles = new HashMap<>();
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
      switch (route.operationType()) {
        case CREATE -> {
          if (!startsAtDepot(route.stops())) {
            // 发车侧会把没有 CRET 的 CREATE 票无限重排（create-without-cret），这条线路等于不存在。
            infeasible.add(
                new TimetableBuildResult.InfeasibleRoute(
                    route.routeCode(), "CREATE 线路首站没有 CRET 指令，无法实体化列车"));
            continue;
          }
          createLegs.add(
              new VehicleDutyPlanner.Leg(
                  route.routeId(), route.routeCode(), origin, timing.totalRunSeconds()));
          legStation.put(route.routeId(), terminal);
        }
        case RETURN -> {
          returnLegs.add(
              new VehicleDutyPlanner.Leg(
                  route.routeId(), route.routeCode(), terminal, timing.totalRunSeconds()));
          legStation.put(route.routeId(), origin);
        }
        case OPERATION -> operations.add(
            new OperationPlan(route, startsAtDepot(route.stops()), endsAtDepot(route.stops())));
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
              Optional.empty());
      plans.add(plan);
      profiles.put(
          route.routeId(),
          new TimetableConflictChecker.RouteProfile(
              route.routeId(),
              route.routeCode(),
              timing.stops(),
              timing.segments(),
              TimetableConflictChecker.platformsOf(timing.stops(), route.stops(), waypoints)));
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
    List<WeightedTripAllocator.Candidate> candidates =
        operationPlans.stream()
            .map(plan -> new WeightedTripAllocator.Candidate(plan.routeCode(), plan.weight()))
            .toList();
    if (candidates.stream().allMatch(candidate -> candidate.weight() <= 0)) {
      throw new BuildFailure("所有运营 route 的 weight 都不是正数，无法分配服务比例");
    }
    return new Prepared(
        timetableId,
        TimetableConflictChecker.GraphIndex.of(input.graph()),
        List.copyOf(plans),
        List.copyOf(operations),
        operationPlans,
        candidates,
        VehicleDutyPlanner.Legs.of(createLegs, returnLegs, legStation),
        Map.copyOf(profiles),
        List.copyOf(infeasible));
  }

  // ------------------------------------------------------------ 第 2–4 步

  /** 按 {@code options.headway()} 排班、派车、查冲突。排不出任何班次时抛 {@link BuildFailure}。 */
  private Attempt attempt(Prepared prepared, TimetableBuildOptions options) {
    UUID timetableId = prepared.timetableId();
    int slots = options.slotCount();
    long headwaySeconds = Math.max(1L, options.headway().toSeconds());
    int horizon = options.horizonSeconds();
    List<TimetableRoutePlan> operationPlans = prepared.operationPlans();

    // 可行性：这一班必须在计划窗口内跑完。全程时分长的 route 因此会在窗口末尾被自然挤出，
    // 而它的 deficit 留在分配器里——这正是"约束恢复后能追回份额"的机制。
    WeightedTripAllocator.FeasibilityCheck feasibility =
        (slot, index, assigned) -> {
          long departure = slot * headwaySeconds;
          return departure + operationPlans.get(index).totalRunSeconds() <= horizon;
        };
    List<WeightedTripAllocator.Allocation> assignment =
        WeightedTripAllocator.allocate(prepared.candidates(), slots, feasibility);
    if (assignment.isEmpty()) {
      throw new BuildFailure("计划窗口内排不下任何班次：检查 headway、首末班时刻与全程时分");
    }

    // 派车前的班次用临时主键：取消一部分之后要重新编号，主键由最终车次号派生。
    List<VehicleDutyPlanner.PlannedTrip> plannedTrips = new ArrayList<>(assignment.size());
    Map<UUID, WeightedTripAllocator.Allocation> allocationByProvisional = new HashMap<>();
    Map<UUID, VehicleDutyPlanner.PlannedTrip> tripByProvisional = new HashMap<>();
    for (int i = 0; i < assignment.size(); i++) {
      WeightedTripAllocator.Allocation allocation = assignment.get(i);
      OperationPlan op = prepared.operations().get(allocation.candidateIndex());
      TimetableRoutePlan plan = operationPlans.get(allocation.candidateIndex());
      int departureSeconds = (int) (allocation.slot() * headwaySeconds);
      UUID provisional =
          UUID.nameUUIDFromBytes(
              ("provisional:" + timetableId + ":" + i)
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      allocationByProvisional.put(provisional, allocation);
      VehicleDutyPlanner.PlannedTrip trip =
          new VehicleDutyPlanner.PlannedTrip(
              provisional,
              String.format(Locale.ROOT, "%s@%06d", plan.routeCode(), departureSeconds),
              plan.originNodeId(),
              plan.terminalNodeId(),
              departureSeconds,
              plan.totalRunSeconds(),
              op.startsAtDepot(),
              op.endsAtDepot());
      plannedTrips.add(trip);
      tripByProvisional.put(provisional, trip);
    }

    VehicleDutyPlanner.Result planned =
        VehicleDutyPlanner.plan(timetableId, plannedTrips, prepared.legs(), options.dutyLimits());
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

    // 只保留派上车的班次，按最终车次号重新派生主键，再把 duty 里的引用换过来。
    Map<String, Integer> perRouteCounter = new LinkedHashMap<>();
    Map<UUID, UUID> finalByProvisional = new HashMap<>();
    Map<UUID, String> codeByProvisional = new HashMap<>();
    List<TimetableTrip> trips = new ArrayList<>(plannedTrips.size());
    List<WeightedTripAllocator.Allocation> keptAllocations = new ArrayList<>(plannedTrips.size());
    List<TimetableBuildResult.DroppedTrip> dropped = new ArrayList<>();
    int emitted = 0;
    for (VehicleDutyPlanner.PlannedTrip provisional : plannedTrips) {
      WeightedTripAllocator.Allocation allocation =
          allocationByProvisional.get(provisional.tripId());
      TimetableRoutePlan plan = operationPlans.get(allocation.candidateIndex());
      int departureSecondOfDay =
          Math.floorMod(
              options.serviceStartSecondOfDay() + provisional.departureSeconds(),
              TimetableTrip.SECONDS_PER_DAY);
      VehicleDutyPlanner.UnassignedTrip missing = unassigned.get(provisional.tripId());
      if (missing != null || !dutyByProvisional.containsKey(provisional.tripId())) {
        dropped.add(
            new TimetableBuildResult.DroppedTrip(
                plan.routeCode(),
                clock(departureSecondOfDay),
                missing == null
                    ? VehicleDutyPlanner.UnassignedReason.NO_RETURN_ACCESS
                    : missing.reason()));
        continue;
      }
      int serial = perRouteCounter.merge(plan.routeCode(), 1, Integer::sum);
      String tripCode =
          String.format(
              Locale.ROOT, "%s%s-%03d", options.tripCodePrefix(), plan.routeCode(), serial);
      UUID tripId = deterministicTripId(timetableId, tripCode);
      finalByProvisional.put(provisional.tripId(), tripId);
      codeByProvisional.put(provisional.tripId(), tripCode);
      trips.add(
          new TimetableTrip(
              tripId,
              timetableId,
              plan.routeId(),
              emitted,
              tripCode,
              departureSecondOfDay,
              Optional.of(dutyByProvisional.get(provisional.tripId()))));
      keptAllocations.add(allocation);
      emitted++;
    }
    if (trips.isEmpty()) {
      throw new BuildFailure("排定的班次没有一趟能配上出库与回库线路：检查 CREATE/RETURN 线路是否覆盖各起终点");
    }

    // ---- 4. 查冲突：全部运行 + 站台待命，时刻仍相对窗口起点 -------------------
    List<TimetableConflictChecker.Movement> movements = new ArrayList<>();
    List<TimetableConflictChecker.Stay> stays = new ArrayList<>();
    for (VehicleDutyPlanner.PlannedTrip provisional : plannedTrips) {
      String code = codeByProvisional.get(provisional.tripId());
      if (code == null) {
        continue;
      }
      WeightedTripAllocator.Allocation allocation =
          allocationByProvisional.get(provisional.tripId());
      movements.add(
          new TimetableConflictChecker.Movement(
              code,
              operationPlans.get(allocation.candidateIndex()).routeId(),
              provisional.departureSeconds()));
    }
    List<VehicleDuty> duties = new ArrayList<>(planned.duties().size());
    for (VehicleDuty duty : planned.duties()) {
      collectDutyOccupancy(
          duty,
          tripByProvisional,
          allocationByProvisional,
          operationPlans,
          prepared.profiles(),
          movements,
          stays);
      List<UUID> finalTripIds = new ArrayList<>(duty.tripIds().size());
      for (UUID provisional : duty.tripIds()) {
        UUID finalId = finalByProvisional.get(provisional);
        if (finalId != null) {
          finalTripIds.add(finalId);
        }
      }
      duties.add(
          new VehicleDuty(
              duty.id(),
              duty.timetableId(),
              duty.sequence(),
              duty.dutyCode(),
              duty.startDepotNodeId(),
              duty.endDepotNodeId(),
              duty.createRouteId(),
              duty.returnRouteId(),
              finalTripIds,
              shiftToServiceDay(duty.plannedStartSecondOfDay(), options),
              shiftToServiceDay(duty.returnSecondOfDay(), options),
              shiftToServiceDay(duty.plannedEndSecondOfDay(), options),
              duty.closeReason()));
    }
    TimetableConflictChecker.Report conflicts =
        TimetableConflictChecker.check(
            prepared.graphIndex(),
            prepared.profiles(),
            movements,
            stays,
            (int) Math.min(Integer.MAX_VALUE, options.separation().toSeconds()));

    List<WeightedTripAllocator.ShareReport> shares =
        WeightedTripAllocator.report(prepared.candidates(), keptAllocations);
    return new Attempt(
        (int) headwaySeconds,
        List.copyOf(trips),
        List.copyOf(duties),
        List.copyOf(dropped),
        shares,
        conflicts);
  }

  /**
   * 一个 duty 在冲突模型里的贡献：两段走行是运行，到站等首班、两班之间折返、末班到发回库票是站台待命。
   *
   * <p>待命必须按 duty 建模而不是按班次：同一辆车"到站 → 折返 → 再发车"是一段连续占用，拆成两个班次各自的到发区间 会在中间留出一个并不存在的空档。
   */
  private static void collectDutyOccupancy(
      VehicleDuty duty,
      Map<UUID, VehicleDutyPlanner.PlannedTrip> tripByProvisional,
      Map<UUID, WeightedTripAllocator.Allocation> allocationByProvisional,
      List<TimetableRoutePlan> operationPlans,
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
      List<TimetableConflictChecker.Movement> movements,
      List<TimetableConflictChecker.Stay> stays) {
    List<VehicleDutyPlanner.PlannedTrip> chain = new ArrayList<>(duty.tripIds().size());
    List<TimetableConflictChecker.RouteProfile> chainProfiles = new ArrayList<>();
    for (UUID provisional : duty.tripIds()) {
      VehicleDutyPlanner.PlannedTrip trip = tripByProvisional.get(provisional);
      WeightedTripAllocator.Allocation allocation = allocationByProvisional.get(provisional);
      if (trip == null || allocation == null) {
        continue;
      }
      TimetableConflictChecker.RouteProfile profile =
          profiles.get(operationPlans.get(allocation.candidateIndex()).routeId());
      if (profile == null) {
        continue;
      }
      chain.add(trip);
      chainProfiles.add(profile);
    }
    if (chain.isEmpty()) {
      return;
    }
    VehicleDutyPlanner.PlannedTrip first = chain.get(0);
    VehicleDutyPlanner.PlannedTrip last = chain.get(chain.size() - 1);
    duty.createRouteId()
        .ifPresent(
            routeId -> {
              movements.add(
                  new TimetableConflictChecker.Movement(
                      duty.dutyCode() + "-CREATE", routeId, duty.plannedStartSecondOfDay()));
              TimetableConflictChecker.RouteProfile create = profiles.get(routeId);
              int arrival =
                  duty.plannedStartSecondOfDay()
                      + (create == null ? 0 : lastArrival(create.stops()));
              chainProfiles
                  .get(0)
                  .origin()
                  .ifPresent(
                      platform ->
                          stays.add(
                              new TimetableConflictChecker.Stay(
                                  duty.dutyCode(),
                                  platform,
                                  Math.min(arrival, first.departureSeconds()),
                                  first.departureSeconds())));
            });
    for (int i = 0; i + 1 < chain.size(); i++) {
      VehicleDutyPlanner.PlannedTrip current = chain.get(i);
      VehicleDutyPlanner.PlannedTrip next = chain.get(i + 1);
      int arrival = current.departureSeconds() + current.durationSeconds();
      int index = i;
      chainProfiles
          .get(index)
          .terminal()
          .ifPresent(
              platform ->
                  stays.add(
                      new TimetableConflictChecker.Stay(
                          duty.dutyCode(),
                          platform,
                          arrival,
                          Math.max(arrival, next.departureSeconds()))));
    }
    duty.returnRouteId()
        .ifPresent(
            routeId -> {
              movements.add(
                  new TimetableConflictChecker.Movement(
                      duty.dutyCode() + "-RETURN", routeId, duty.returnSecondOfDay()));
              int arrival = last.departureSeconds() + last.durationSeconds();
              chainProfiles
                  .get(chainProfiles.size() - 1)
                  .terminal()
                  .ifPresent(
                      platform ->
                          stays.add(
                              new TimetableConflictChecker.Stay(
                                  duty.dutyCode(),
                                  platform,
                                  arrival,
                                  Math.max(arrival, duty.returnSecondOfDay()))));
            });
  }

  private static int lastArrival(List<TimetableStop> stops) {
    return stops.isEmpty() ? 0 : stops.get(stops.size() - 1).arrivalOffsetSeconds();
  }

  /**
   * 从目标 headway 向上逐步放宽，找第一个排出来没有冲突的间隔。
   *
   * <p>只放宽 headway、不挪动单个班次：表的结构（SWRR 序列、duty 链）在任何间隔下都用同一套规则生成， 因此"建议值"是一个可以直接写回配置的数，而不是一次性的手工调整。
   */
  private Optional<Attempt> searchFeasibleHeadway(
      Prepared prepared, TimetableBuildOptions options, int targetHeadway) {
    int limit = targetHeadway * HEADWAY_SEARCH_MAX_MULTIPLIER;
    for (int headway = targetHeadway + HEADWAY_SEARCH_STEP_SECONDS;
        headway <= limit;
        headway += HEADWAY_SEARCH_STEP_SECONDS) {
      Attempt candidate;
      try {
        candidate = attempt(prepared, options.withHeadway(Duration.ofSeconds(headway)));
      } catch (BuildFailure ignored) {
        continue;
      }
      if (candidate.conflicts().clean()) {
        return Optional.of(candidate);
      }
    }
    return Optional.empty();
  }

  private static String summarizeConflicts(
      TimetableConflictChecker.Report report, int targetHeadway) {
    StringBuilder kinds = new StringBuilder();
    report
        .countByKind()
        .forEach(
            (kind, count) -> {
              if (kinds.length() > 0) {
                kinds.append("、");
              }
              kinds.append(describe(kind)).append(' ').append(count);
            });
    return "目标间隔 " + targetHeadway + "s 有 " + report.conflicts().size() + " 处冲突（" + kinds + "）";
  }

  /** 冲突明细，最多列前几条；时刻换算成当日时钟。 */
  private static List<String> describeConflicts(
      TimetableConflictChecker.Report report, TimetableBuildOptions options) {
    List<String> out = new ArrayList<>();
    int shown = Math.min(report.conflicts().size(), TimetableBuildResult.CONFLICT_DETAIL_LIMIT);
    for (int i = 0; i < shown; i++) {
      out.add(
          "  · "
              + report
                  .conflicts()
                  .get(i)
                  .describe(seconds -> clock(options.serviceStartSecondOfDay() + seconds)));
    }
    if (report.conflicts().size() > shown) {
      out.add("  · … 另有 " + (report.conflicts().size() - shown) + " 处");
    }
    return out;
  }

  private static String describe(TimetableConflictChecker.Kind kind) {
    return switch (kind) {
      case TRACK -> "区间";
      case PLATFORM -> "站台";
      case SINGLE_LINE -> "单线对向";
      case JUNCTION -> "道岔";
    };
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

  /** 报告里的时刻统一走导出器的格式：跨零点带 +1，早于零点带 -1，不会把前一夜的出库票显示成当天深夜。 */
  private static String clock(int secondOfDay) {
    return TimetableCsvExporter.clock(secondOfDay);
  }

  /** 取消的班次按 route + 原因归组，每组列出前几个时刻。 */
  private static List<String> describeDropped(List<TimetableBuildResult.DroppedTrip> dropped) {
    Map<String, List<TimetableBuildResult.DroppedTrip>> groups = new LinkedHashMap<>();
    for (TimetableBuildResult.DroppedTrip trip : dropped) {
      groups
          .computeIfAbsent(trip.routeCode() + "|" + trip.reason(), key -> new ArrayList<>())
          .add(trip);
    }
    List<String> out = new ArrayList<>(groups.size());
    for (List<TimetableBuildResult.DroppedTrip> group : groups.values()) {
      TimetableBuildResult.DroppedTrip first = group.get(0);
      StringBuilder times = new StringBuilder();
      for (int i = 0; i < Math.min(group.size(), DROPPED_TIMES_IN_WARNING); i++) {
        if (i > 0) {
          times.append(", ");
        }
        times.append(group.get(i).departureText());
      }
      if (group.size() > DROPPED_TIMES_IN_WARNING) {
        times.append(" …");
      }
      out.add(
          String.format(
              Locale.ROOT,
              "route %s 取消 %d 班（%s）: %s",
              first.routeCode(),
              group.size(),
              describe(first.reason()),
              times));
    }
    return out;
  }

  private static String describe(VehicleDutyPlanner.UnassignedReason reason) {
    return switch (reason) {
      case NO_CREATE_ACCESS -> "起点没有 CREATE 线路，也没有接得上的待命车";
      case NO_RETURN_ACCESS -> "终点没有 RETURN 线路，后面也接不上能回库的班次";
      case EXCEEDS_DUTY_LIMITS -> "单独一班连同出库、回库走行就超过交路时长上限";
    };
  }

  /**
   * 由时刻表 ID 与车次号派生稳定的 trip UUID。
   *
   * <p>用 {@code randomUUID} 会让"同样输入构建两次结果一致"这条性质只在字段层面成立、在主键层面不成立， 而主键会进数据库、会被 duty
   * 引用，也会出现在导出里。名字派生让整份产物逐字节可复现。
   */
  private static UUID deterministicTripId(UUID timetableId, String tripCode) {
    return UUID.nameUUIDFromBytes(
        ("trip:" + timetableId + ":" + tripCode).getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
      List<TimetableBuildResult.InfeasibleRoute> infeasible) {}

  /** 按某个 headway 排出来的一份完整计划及其冲突报告。 */
  private record Attempt(
      int headwaySeconds,
      List<TimetableTrip> trips,
      List<VehicleDuty> duties,
      List<TimetableBuildResult.DroppedTrip> dropped,
      List<WeightedTripAllocator.ShareReport> shares,
      TimetableConflictChecker.Report conflicts) {}

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
      Optional<String> notes) {

    public BuildInput {
      Objects.requireNonNull(timetableId, "timetableId");
      Objects.requireNonNull(companyId, "companyId");
      Objects.requireNonNull(operatorId, "operatorId");
      Objects.requireNonNull(lineId, "lineId");
      routes = routes == null ? List.of() : List.copyOf(routes);
      notes = notes == null ? Optional.empty() : notes;
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
   */
  public record RouteInput(
      UUID routeId,
      String routeCode,
      RouteOperationType operationType,
      int weight,
      RouteDefinition definition,
      List<RouteStop> stops,
      Optional<String> depotNodeId) {

    public RouteInput {
      Objects.requireNonNull(routeId, "routeId");
      Objects.requireNonNull(definition, "definition");
      operationType = operationType == null ? RouteOperationType.OPERATION : operationType;
      routeCode = routeCode == null ? "" : routeCode.trim();
      stops = stops == null ? List.of() : List.copyOf(stops);
      depotNodeId = depotNodeId == null ? Optional.empty() : depotNodeId;
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
