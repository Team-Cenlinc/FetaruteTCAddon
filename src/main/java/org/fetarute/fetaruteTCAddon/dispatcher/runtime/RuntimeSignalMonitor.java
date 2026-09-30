package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartGroupStore;
import com.bergerkiller.bukkit.tc.controller.status.TrainStatus;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.EtaRuntimeSampler;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainSnapshotStore;
import org.fetarute.fetaruteTCAddon.dispatcher.health.TrainHealthMonitor;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;

/**
 * 运行时巡检器。
 *
 * <p>该类只负责周期性扫描在线列车、清理异常编组、采样 ETA 以及把结果送入 {@link RuntimeDispatchService}。真正的信号控制核心仍位于 {@link
 * RuntimeDispatchService#handleSignalTick(RuntimeTrainHandle, boolean)}，这里不直接承担运行时控车决策。
 *
 * <p>执行频率由配置 {@code runtime.dispatch-tick-interval-ticks} 控制。单轮候选处理受固定预算约束；预算耗尽后保留同一轮候选快照， 下一个调度
 * tick 继续，避免列车数量或单个调度链过长时独占服务器主线程。
 */
public final class RuntimeSignalMonitor implements Runnable {

  private static final Duration DEFAULT_CANDIDATE_WORK_BUDGET = Duration.ofMillis(5);

  private final RuntimeDispatchService dispatchService;
  private final EtaRuntimeSampler etaSampler;
  private final TrainSnapshotStore snapshotStore;
  private final DwellRegistry dwellRegistry;
  private final RouteProgressRegistry routeProgressRegistry;
  private final RouteDefinitionCache routeDefinitions;
  private final RuntimeDispatchWorkCycle<GroupTickTarget> candidateWorkCycle;
  private final Map<MinecartGroup, Boolean> lastObservedMovementByGroup = new IdentityHashMap<>();
  private final int scanIntervalTicks;
  private RuntimeCycleSnapshot activeCycle;
  private long nextCycleEligibleTick = Long.MIN_VALUE;

  /**
   * FTA tag 存在但 route 无法解析的列车，累计被观测到的 tick 次数。超过阈值视为"脱管"并清理。
   *
   * <p>避免因瞬时加载延迟误杀正在初始化的列车。
   */
  private final java.util.Map<String, Integer> staleTrainTicks =
      new java.util.concurrent.ConcurrentHashMap<>();

  /** "脱管"列车被判定为异常前需连续被观测到的 tick 次数。 */
  private static final int STALE_THRESHOLD_TICKS = 60;

  /**
   * 停车列车即使占用版本没动，也至少这么久重评估一次。
   *
   * <p>占用版本驱动（见 {@link #heldRecheckDue}）覆盖「阻塞者释放了」这一主因；这条节拍是兜底，
   * 覆盖停因不由占用变化解除的情形（发车门控到期、折返停站结束、上游进度写入等）。
   *
   * <p>取值依据：没有这条节拍时，阻塞者清空后的空等与健康监控兜底都在数分钟量级。 只要这个节拍远小于这一量级，兜底就不再是唯一出路；而开销上界是明确的—— 每辆**停着的**车每 5
   * 秒一次完整 tick，与车队规模同阶，不随 tick 放大。
   */
  private static final Duration HELD_TRAIN_RECHECK_INTERVAL = Duration.ofSeconds(5);

  /** 停车列车上一次完整重评估时看到的占用版本。 */
  private final java.util.Map<String, Long> heldRecheckVersions =
      new java.util.concurrent.ConcurrentHashMap<>();

  /** 停车列车上一次完整重评估的时刻。 */
  private final java.util.Map<String, Instant> heldRecheckAt =
      new java.util.concurrent.ConcurrentHashMap<>();

  public RuntimeSignalMonitor(
      RuntimeDispatchService dispatchService,
      EtaRuntimeSampler etaSampler,
      TrainSnapshotStore snapshotStore,
      DwellRegistry dwellRegistry,
      RouteProgressRegistry routeProgressRegistry,
      RouteDefinitionCache routeDefinitions) {
    this(
        dispatchService,
        etaSampler,
        snapshotStore,
        dwellRegistry,
        routeProgressRegistry,
        routeDefinitions,
        DEFAULT_CANDIDATE_WORK_BUDGET,
        System::nanoTime,
        1);
  }

  /**
   * 使用候选快照间隔创建巡检器。
   *
   * <p>调用方应以每 tick heartbeat 调度 {@link #run()}。该间隔只限制新一轮候选快照的开始；已经开始的周期仍由下一 tick 续跑，避免预算切分放大信号响应延迟。
   *
   * @param scanIntervalTicks 两次完整候选周期之间的最小间隔
   */
  public RuntimeSignalMonitor(
      RuntimeDispatchService dispatchService,
      EtaRuntimeSampler etaSampler,
      TrainSnapshotStore snapshotStore,
      DwellRegistry dwellRegistry,
      RouteProgressRegistry routeProgressRegistry,
      RouteDefinitionCache routeDefinitions,
      int scanIntervalTicks) {
    this(
        dispatchService,
        etaSampler,
        snapshotStore,
        dwellRegistry,
        routeProgressRegistry,
        routeDefinitions,
        DEFAULT_CANDIDATE_WORK_BUDGET,
        System::nanoTime,
        scanIntervalTicks);
  }

  /**
   * 使用指定候选工作周期创建巡检器。
   *
   * <p>该构造入口把主线程预算放在 {@link RuntimeSignalMonitor} 的单一 seam；生产装配使用默认预算，测试或未来配置适配可替换时间源与预算，
   * 无需复制巡检逻辑或暴露内部候选类型。
   */
  RuntimeSignalMonitor(
      RuntimeDispatchService dispatchService,
      EtaRuntimeSampler etaSampler,
      TrainSnapshotStore snapshotStore,
      DwellRegistry dwellRegistry,
      RouteProgressRegistry routeProgressRegistry,
      RouteDefinitionCache routeDefinitions,
      Duration candidateWorkBudget,
      LongSupplier nanoTime,
      int scanIntervalTicks) {
    this.dispatchService = Objects.requireNonNull(dispatchService, "dispatchService");
    this.etaSampler = etaSampler;
    this.snapshotStore = snapshotStore;
    this.dwellRegistry = dwellRegistry;
    this.routeProgressRegistry = routeProgressRegistry;
    this.routeDefinitions = routeDefinitions;
    this.candidateWorkCycle =
        new RuntimeDispatchWorkCycle<>(
            Objects.requireNonNull(candidateWorkBudget, "candidateWorkBudget"),
            Objects.requireNonNull(nanoTime, "nanoTime"));
    if (scanIntervalTicks <= 0) {
      throw new IllegalArgumentException("scanIntervalTicks 必须为正数");
    }
    this.scanIntervalTicks = scanIntervalTicks;
  }

  @Override
  public void run() {
    // 时钟跳变检测要在**本轮任何工作之前**：发车门锁会在 hasDepartureGate 读取时过期，
    // 而那条路径就在本任务里。若补偿晚于它执行，跳变后的第一次读取就会让所有门锁同时过期。
    dispatchService.observeSchedulerTick(java.time.Instant.now());
    runWithFailClosedBoundary(
        "-", this::runGuardedCycle, dispatchService::failClosedAfterSignalReevaluationFailure);
  }

  /** 执行一轮周期巡检；任何未被逐组边界吸收的运行时或 ABI 错误都由 {@link #run()} 关闭全局授权门。 */
  private void runGuardedCycle() {
    RuntimeCycleSnapshot cycle = activeCycle;
    if (cycle == null) {
      long currentTick = currentTick();
      if (currentTick < nextCycleEligibleTick) {
        return;
      }
      cycle = collectCycleSnapshot();
      if (cycle == null) {
        return;
      }
      activeCycle = cycle;
    }

    try {
      RuntimeDispatchWorkCycle.CycleResult result =
          candidateWorkCycle.run(
              cycle.candidates(),
              candidate -> {
                Instant now = Instant.now();
                long tick = now.toEpochMilli() / 50L;
                if (!runWithFailClosedBoundary(
                    candidate.trainName(),
                    () -> processCandidate(candidate, tick, now),
                    dispatchService::failClosedAfterSignalReevaluationFailure)) {
                  throw CycleProcessingInterrupted.INSTANCE;
                }
              });
      if (!result.completed()) {
        return;
      }
    } catch (CycleProcessingInterrupted ignored) {
      return;
    }
    activeCycle = null;
    completeCycleCleanup();
    nextCycleEligibleTick = nextCycleEligibleTick(currentTick(), scanIntervalTicks);
  }

  private static long currentTick() {
    return Instant.now().toEpochMilli() / 50L;
  }

  /** 计算完整周期结束后允许创建下一份候选快照的 tick，饱和到 {@link Long#MAX_VALUE}。 */
  static long nextCycleEligibleTick(long completedAtTick, int intervalTicks) {
    if (intervalTicks <= 0) {
      throw new IllegalArgumentException("intervalTicks 必须为正数");
    }
    return completedAtTick > Long.MAX_VALUE - intervalTicks
        ? Long.MAX_VALUE
        : completedAtTick + intervalTicks;
  }

  /**
   * 判断周期巡检是否需要进入完整 Movement Authority 流程。
   *
   * <p>首次观测必须完成一次恢复授权；运动中列车仍需持续控制。稳定静止的列车已经在上一轮写入 STOP/queue 状态，重复构建进路**通常**不能创造新的
   * authority，只会重做方向解析并重新触碰占用状态。
   *
   * <p><b>但「通常」不是「总是」。</b>「等待资源释放、明确生命周期事件或健康恢复再次触发完整重评估」这三个触发里， 只有健康恢复真实存在：{@code
   * RuntimeStopState.retryTrigger()} 的 {@code PERIODIC_RECHECK} 只用于日志与命令展示，从不驱动任何重检； 也没有任何「占用变化 →
   * 重新评估」的监听。
   *
   * <p>若仅靠这些，调度实际靠 {@code PROGRESS_STUCK} 超时兜底运转，阻塞清空后的车仍会长时间空等。 **阻塞者已经释放时，重新构建进路恰恰能创造新的
   * authority。**
   *
   * <p>因此补上第四个条件 {@code heldRecheckDue}（见 {@link #heldRecheckDue}）： 它不放宽任何判据，{@code
   * canEnter}、终局授权校验、单线硬屏障一个不动， 只是让这些门在停车列车身上**被执行到**。
   *
   * @param previouslyMoving 上一次成功观测到的物理运动状态；首次为 {@code null}
   * @param currentlyMoving 本次物理运动状态
   * @param heldRecheckDue 该车处于停车态且已到重评估条件（占用版本变化或兜底节拍到期）
   * @return 是否应执行完整信号与授权处理
   */
  /**
   * 判断一辆**停车中**的列车此刻是否该再走一次完整信号与授权流程。
   *
   * <p>两条触发，缺一不可地互补：
   *
   * <ol>
   *   <li><b>占用版本变化</b>——主因。阻塞者释放时版本必然推进，于是「等资源释放」这句话 第一次真的有人执行。用版本而不是事件是有意的：本项目的事件流已被证明不完备 （{@code
   *       SMART_RESOURCE_LIFECYCLE} 在某些移除路径上不发 release），版本号不会漏。
   *   <li><b>兜底节拍</b>——{@link #HELD_TRAIN_RECHECK_INTERVAL}。覆盖停因不由占用变化解除的
   *       情形（发车门控到期、折返停站结束等）。没有它，只靠事件驱动会留下同一个洞。
   * </ol>
   *
   * <p>不在停车态的列车一律返回 {@code false}——正常行驶的判据完全不受影响， 静止且无停因的车（入库、待命）也不会被这条路径唤醒，开销上界仍是「停着的车数 × 节拍」。
   *
   * @param trainName 持久化的运行时列车名
   * @param now 本次观测时刻
   * @return 是否应为这辆停车列车执行一次完整重评估
   */
  private boolean heldRecheckDue(String trainName, Instant now) {
    if (trainName == null || trainName.isBlank() || now == null) {
      return false;
    }
    java.util.OptionalLong held = dispatchService.heldTrainOccupancyVersion(trainName);
    if (held.isEmpty()) {
      // 没有停因就没有要重评估的东西；顺手让状态随车收敛，不必等周期清理。
      heldRecheckVersions.remove(trainName);
      heldRecheckAt.remove(trainName);
      return false;
    }
    long version = held.getAsLong();
    boolean due =
        heldRecheckDue(
            heldRecheckVersions.get(trainName),
            heldRecheckAt.get(trainName),
            version,
            now,
            HELD_TRAIN_RECHECK_INTERVAL);
    if (due) {
      heldRecheckVersions.put(trainName, version);
      heldRecheckAt.put(trainName, now);
    }
    return due;
  }

  /**
   * 「这辆停车列车该不该重评估」的纯判据。
   *
   * <p>拆成纯函数是为了能单独钉住策略本身：实例侧那一半只做 map 读写， 而这里的三条分支各自对应一个真实成因，漏掉任何一条都会退回到「只能等 182 秒兜底」。
   *
   * @param lastVersion 上次重评估时看到的占用版本；从未评估过为 {@code null}
   * @param lastAt 上次重评估时刻；从未评估过为 {@code null}
   * @param version 当前占用版本
   * @param now 当前时刻
   * @param interval 兜底节拍
   * @return 是否应重评估
   */
  static boolean heldRecheckDue(
      Long lastVersion, Instant lastAt, long version, Instant now, Duration interval) {
    // 首次见到这辆停车列车：必须评估一次，否则它要等到版本变化才有第一次机会。
    if (lastVersion == null || lastAt == null) {
      return true;
    }
    // 主因：占用版本推进 ⇒ 有 claim 变动过，「等资源释放」这句话在这里兑现。
    if (lastVersion.longValue() != version) {
      return true;
    }
    // 兜底：停因不由占用变化解除的情形（发车门控到期、折返停站结束等）。
    return !now.isBefore(lastAt.plus(interval));
  }

  static boolean shouldRunFullSignalTick(
      Boolean previouslyMoving, boolean currentlyMoving, boolean heldRecheckDue) {
    return previouslyMoving == null
        || currentlyMoving
        || previouslyMoving.booleanValue() != currentlyMoving
        || heldRecheckDue;
  }

  /** 收集一份不会在预算续跑期间被新到列车替换的巡检候选快照。 */
  private RuntimeCycleSnapshot collectCycleSnapshot() {
    Collection<MinecartGroup> groups = MinecartGroupStore.getGroups();
    if (groups == null) {
      return null;
    }
    Instant now = Instant.now();
    List<GroupTickTarget> candidates = new ArrayList<>();
    Map<String, List<GroupTickTarget>> groupsByLogicalName = new LinkedHashMap<>();
    boolean scanComplete = true;
    for (MinecartGroup group : groups) {
      String trainHint = safeRuntimeTrainName(group);
      boolean inspected =
          runWithFailClosedBoundary(
              trainHint,
              () -> collectCandidate(group, candidates, groupsByLogicalName),
              dispatchService::failClosedAfterSignalReevaluationFailure);
      scanComplete &= inspected;
    }
    if (!scanComplete) {
      return null;
    }

    Set<MinecartGroup> duplicateGroups = cleanupDuplicateLogicalTrains(groupsByLogicalName);
    Set<String> activeTrainNames = new HashSet<>();
    for (GroupTickTarget candidate : candidates) {
      if (duplicateGroups.contains(candidate.group())) {
        continue;
      }
      String trainName = candidate.trainName();
      activeTrainNames.addAll(
          activeRuntimeOwnerNames(
              trainName,
              TrainTagHelper.readTagValue(
                      candidate.group().getProperties(), RouteProgressRegistry.TAG_TRAIN_NAME)
                  .orElse(null)));
    }
    dispatchService.traceSmartDispatchGlobalSnapshot(activeTrainNames, now);
    List<GroupTickTarget> uniqueCandidates =
        candidates.stream()
            .filter(candidate -> !duplicateGroups.contains(candidate.group()))
            .toList();
    return new RuntimeCycleSnapshot(uniqueCandidates);
  }

  /**
   * 在完整候选周期之后按最新现场完成收尾。
   *
   * <p>不能复用周期开始时的 active 名单：预算续跑期间可能出现 Depot materialization、改名或实体销毁。收尾前重新扫描可避免把新实体的授权误清为孤儿。
   */
  private void completeCycleCleanup() {
    Set<String> activeTrainNames = activeRuntimeOwnerNamesNow();
    dispatchService.cleanupOrphanOccupancyClaims(activeTrainNames);
    cleanupSnapshotStore(activeTrainNames);
    staleTrainTicks.keySet().removeIf(name -> !activeTrainNames.contains(name));
    heldRecheckVersions.keySet().removeIf(name -> !activeTrainNames.contains(name));
    heldRecheckAt.keySet().removeIf(name -> !activeTrainNames.contains(name));
    if (dwellRegistry != null) {
      dwellRegistry.retain(activeTrainNames);
    }
    retainObservedMovementGroups();
  }

  /** 清除已经销毁的编组的运动采样，防止长期运行时保留过期实体引用。 */
  private void retainObservedMovementGroups() {
    Collection<MinecartGroup> groups = MinecartGroupStore.getGroups();
    if (groups == null) {
      return;
    }
    Set<MinecartGroup> activeGroups = Collections.newSetFromMap(new IdentityHashMap<>());
    for (MinecartGroup group : groups) {
      if (group != null && group.isValid()) {
        activeGroups.add(group);
      }
    }
    lastObservedMovementByGroup.keySet().removeIf(group -> !activeGroups.contains(group));
  }

  /** 读取当前已存活编组的逻辑 owner，供预算周期完成后的 orphan cleanup 使用。 */
  private Set<String> activeRuntimeOwnerNamesNow() {
    Collection<MinecartGroup> groups = MinecartGroupStore.getGroups();
    if (groups == null || groups.isEmpty()) {
      return Set.of();
    }
    Set<String> activeNames = new HashSet<>();
    for (MinecartGroup group : groups) {
      if (group == null || !group.isValid()) {
        continue;
      }
      TrainProperties properties = group.getProperties();
      if (properties == null) {
        continue;
      }
      boolean ftaTagged = dispatchService.hasFtaRuntimeTag(properties);
      if (!ftaTagged || isDerailed(group)) {
        continue;
      }
      activeNames.addAll(
          activeRuntimeOwnerNames(
              resolveLogicalTrainName(group),
              TrainTagHelper.readTagValue(properties, RouteProgressRegistry.TAG_TRAIN_NAME)
                  .orElse(null)));
    }
    return Set.copyOf(activeNames);
  }

  /** 收集一个稳定候选；本方法由逐组 fail-closed 边界调用。 */
  private void collectCandidate(
      MinecartGroup group,
      List<GroupTickTarget> candidates,
      Map<String, List<GroupTickTarget>> groupsByLogicalName) {
    if (group == null || !group.isValid()) {
      return;
    }
    TrainProperties properties = group.getProperties();
    // 全服列车（含非本插件的车）都关掉 TrainCarts 摩擦与重力：到速后保持限速，与编表运行曲线一致；脱轨车在下面直接回收。
    TrainLaunchManager.disableSlowdown(properties);
    boolean ftaTagged = dispatchService.hasFtaRuntimeTag(properties);
    boolean derailed = isDerailed(group);
    if (!shouldInspectRuntimeGroup(ftaTagged, derailed)) {
      return;
    }
    if (derailed) {
      dispatchService.handleAbnormalGroup(group, "status-derailed");
      return;
    }
    String rawTrainName = resolveRawTrainName(group);
    String trainName = resolveLogicalTrainName(group);
    GroupTickTarget candidate = new GroupTickTarget(group, trainName, rawTrainName);
    if (trainName != null && !trainName.isBlank()) {
      groupsByLogicalName.computeIfAbsent(trainName, unused -> new ArrayList<>()).add(candidate);
    }
    candidates.add(candidate);
  }

  /** 对一个候选执行必要的信号重评估、脱管检测与 ETA 采样；异常时不得继续本轮孤儿释放。 */
  private void processCandidate(GroupTickTarget candidate, long tick, Instant now) {
    MinecartGroup group = candidate.group();
    String trainName = candidate.trainName();
    boolean currentlyMoving = group.isMoving();
    Boolean previouslyMoving = lastObservedMovementByGroup.get(group);
    // 停车态要按**持久化的运行时名**去查（停因就是按它记的），所以名字解析必须提前到判据之前。
    // 这个解析是 static 且只读一个 tag，没有副作用，提前无代价。
    String runtimeOwnerName = resolvePersistedRuntimeOwnerName(group, trainName);
    if (shouldRunFullSignalTick(
        previouslyMoving, currentlyMoving, heldRecheckDue(runtimeOwnerName, now))) {
      dispatchService.handleSignalTick(group);
      currentlyMoving = group.isMoving();
    }
    lastObservedMovementByGroup.put(group, currentlyMoving);
    if (runtimeOwnerName != null && !runtimeOwnerName.isBlank()) {
      detectStaleFtaTrain(group, runtimeOwnerName);
    }
    if (etaSampler == null || runtimeOwnerName == null || runtimeOwnerName.isBlank()) {
      return;
    }
    Optional<Integer> dwellRemainingSec =
        dwellRegistry != null ? dwellRegistry.remainingSeconds(runtimeOwnerName) : Optional.empty();
    NodeSampleInfo nodeInfo = resolveNodeInfo(runtimeOwnerName);
    etaSampler.sample(
        group,
        tick,
        now,
        nodeInfo.currentNodeId,
        nodeInfo.lastPassedNodeId,
        dwellRemainingSec,
        nodeInfo.signalAspect);
  }

  /** 尽力提取错误审计用列车名；该辅助不得让 ABI 错误逃出巡检边界。 */
  private String safeRuntimeTrainName(MinecartGroup group) {
    try {
      String logicalName = resolveLogicalTrainName(group);
      return logicalName == null || logicalName.isBlank() ? "-" : logicalName;
    } catch (RuntimeException | LinkageError ignored) {
      return "-";
    }
  }

  /**
   * 为周期任务建立同时覆盖 {@link RuntimeException} 与 {@link LinkageError} 的 fail-closed 边界。
   *
   * @return 操作完整成功时为 {@code true}；已触发恢复时为 {@code false}
   */
  static boolean runWithFailClosedBoundary(
      String trainName, Runnable operation, BiConsumer<String, Throwable> failureHandler) {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(failureHandler, "failureHandler");
    try {
      operation.run();
      return true;
    } catch (RuntimeException | LinkageError failure) {
      failureHandler.accept(trainName, failure);
      return false;
    }
  }

  /**
   * 清理“同一逻辑列车名对应多个实体”的异常场景。
   *
   * <p>TrainCarts split 后可能短时间内留下多个携带相同 {@code FTA_TRAIN_NAME} 的 group；若继续让它们并行进入信号/占用流程，会共同读写同一份
   * progress 与 claim，导致调度状态迅速混乱。此处采用保守策略：真实重复直接清理；split 过渡态只保留一个主编组继续驱动，临时别名跳过本轮巡检。
   *
   * @param groupsByLogicalName 按逻辑列车名分组后的实体
   * @return 本轮已按重复异常处理的 group 集合
   */
  private Set<MinecartGroup> cleanupDuplicateLogicalTrains(
      Map<String, List<GroupTickTarget>> groupsByLogicalName) {
    if (groupsByLogicalName == null || groupsByLogicalName.isEmpty()) {
      return Set.of();
    }
    Map<String, Integer> groupCounts = new LinkedHashMap<>();
    for (Map.Entry<String, List<GroupTickTarget>> entry : groupsByLogicalName.entrySet()) {
      List<GroupTickTarget> sameTrainGroups = entry.getValue();
      groupCounts.put(entry.getKey(), sameTrainGroups == null ? 0 : sameTrainGroups.size());
    }
    Set<String> duplicateTrainNames = findDuplicateLogicalTrainNames(groupCounts);
    if (duplicateTrainNames.isEmpty()) {
      return Set.of();
    }
    Set<MinecartGroup> duplicates = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Map.Entry<String, List<GroupTickTarget>> entry : groupsByLogicalName.entrySet()) {
      if (!duplicateTrainNames.contains(entry.getKey())) {
        continue;
      }
      List<GroupTickTarget> sameTrainGroups = entry.getValue();
      if (sameTrainGroups == null || sameTrainGroups.isEmpty()) {
        continue;
      }
      List<String> rawTrainNames = new ArrayList<>();
      for (GroupTickTarget sameTrainGroup : sameTrainGroups) {
        if (sameTrainGroup == null) {
          continue;
        }
        if (sameTrainGroup.rawTrainName() != null && !sameTrainGroup.rawTrainName().isBlank()) {
          rawTrainNames.add(sameTrainGroup.rawTrainName());
        }
      }
      if (isLikelySplitTransitionFamily(entry.getKey(), rawTrainNames)) {
        GroupTickTarget canonicalGroup = null;
        for (GroupTickTarget sameTrainGroup : sameTrainGroups) {
          if (sameTrainGroup == null || sameTrainGroup.group() == null) {
            continue;
          }
          if (sameTrainGroup.rawTrainName() != null
              && sameTrainGroup.rawTrainName().equalsIgnoreCase(entry.getKey())) {
            canonicalGroup = sameTrainGroup;
            break;
          }
        }
        GroupTickTarget keepGroup =
            canonicalGroup != null ? canonicalGroup : sameTrainGroups.get(0);
        for (GroupTickTarget sameTrainGroup : sameTrainGroups) {
          if (sameTrainGroup == null || sameTrainGroup.group() == null) {
            continue;
          }
          if (sameTrainGroup == keepGroup) {
            continue;
          }
          duplicates.add(sameTrainGroup.group());
        }
        continue;
      }
      String detail = buildDuplicateLogicalTrainDetail(entry.getKey(), sameTrainGroups.size());
      for (GroupTickTarget sameTrainGroup : sameTrainGroups) {
        if (sameTrainGroup == null || sameTrainGroup.group() == null) {
          continue;
        }
        duplicates.add(sameTrainGroup.group());
        dispatchService.handleAbnormalGroup(
            sameTrainGroup.group(), "duplicate-logical-train", detail);
      }
    }
    return duplicates;
  }

  /**
   * 计算“同一逻辑列车名被多个实体同时占用”的名称集合。
   *
   * <p>抽成纯逻辑方法，便于单测覆盖 split/重复实体判定，而不依赖 TrainCarts 重量级运行时类型。
   *
   * @param groupCounts 每个逻辑列车名对应的实体数量
   * @return 重复的逻辑列车名集合
   */
  static Set<String> findDuplicateLogicalTrainNames(Map<String, Integer> groupCounts) {
    if (groupCounts == null || groupCounts.isEmpty()) {
      return Set.of();
    }
    Set<String> duplicates = new HashSet<>();
    for (Map.Entry<String, Integer> entry : groupCounts.entrySet()) {
      String trainName = entry.getKey();
      Integer count = entry.getValue();
      if (trainName == null || trainName.isBlank() || count == null || count <= 1) {
        continue;
      }
      duplicates.add(trainName);
    }
    return Set.copyOf(duplicates);
  }

  /**
   * 生成重复逻辑列车告警的附加诊断文本。
   *
   * <p>抽成纯逻辑方法，便于单测覆盖日志上下文而不依赖 TrainCarts 实体。
   *
   * @param trainName 逻辑列车名
   * @param groupCount 同名实体数量
   * @return 用于异常日志的 detail 文本
   */
  static String buildDuplicateLogicalTrainDetail(String trainName, int groupCount) {
    StringBuilder builder = new StringBuilder();
    RuntimeDiagnosticFormatter.appendKeyValue(builder, "logicalTrain", trainName);
    if (groupCount > 0) {
      builder.append(" groups=").append(groupCount);
    }
    return builder.length() == 0 ? null : builder.toString();
  }

  /**
   * 判定一组同名编组是否更像 TrainCarts split 的过渡态，而不是“真实重复”。
   *
   * <p>只要这组原始名称全部满足以下条件，就视为过渡态并跳过重复清理：
   *
   * <ul>
   *   <li>原始名称等于逻辑名
   *   <li>或原始名称是逻辑名的 split 临时别名，例如 {@code main~a}
   * </ul>
   */
  static boolean isLikelySplitTransitionFamily(
      String logicalTrainName, List<String> rawTrainNames) {
    if (logicalTrainName == null || logicalTrainName.isBlank()) {
      return false;
    }
    if (rawTrainNames == null || rawTrainNames.isEmpty()) {
      return false;
    }
    boolean hasSplitAlias = false;
    for (String rawTrainName : rawTrainNames) {
      if (rawTrainName == null || rawTrainName.isBlank()) {
        return false;
      }
      if (rawTrainName.equalsIgnoreCase(logicalTrainName)) {
        continue;
      }
      if (!isSplitAliasName(rawTrainName, logicalTrainName)) {
        return false;
      }
      hasSplitAlias = true;
    }
    return hasSplitAlias;
  }

  /**
   * 检测"脱管" FTA 列车：有 FTA tag 但 route 无法解析或 progressRegistry 无条目。
   *
   * <p>这类列车不会被 {@link TrainHealthMonitor} 检测（因无 progress entry），也不会被 {@code isDerailed}
   * 捕获。连续观测超过阈值后视为异常并清理，避免因瞬时加载延迟误杀正在初始化的列车。
   */
  private void detectStaleFtaTrain(MinecartGroup group, String trainName) {
    if (routeProgressRegistry == null) {
      return;
    }
    // 已有 progress 条目的列车由 TrainHealthMonitor 监控
    boolean hasProgressEntry = routeProgressRegistry.get(trainName).isPresent();
    if (hasProgressEntry) {
      staleTrainTicks.remove(trainName);
      return;
    }
    TrainProperties properties = group.getProperties();
    if (properties == null) {
      return;
    }
    // 检查是否有 FTA route tag（只检测曾经被 FTA 管控的列车）
    boolean hasRouteIndex =
        TrainTagHelper.readIntTag(properties, RouteProgressRegistry.TAG_ROUTE_INDEX).isPresent();
    boolean hasRouteId =
        TrainTagHelper.readTagValue(properties, RouteProgressRegistry.TAG_ROUTE_ID)
            .filter(v -> !v.isBlank())
            .isPresent();
    boolean hasOperator =
        TrainTagHelper.readTagValue(properties, RouteProgressRegistry.TAG_OPERATOR_CODE)
            .filter(v -> !v.isBlank())
            .isPresent();
    if (!shouldTrackStaleFtaTrain(hasProgressEntry, hasRouteIndex, hasRouteId, hasOperator)) {
      // 非 FTA 列车，不检测
      staleTrainTicks.remove(trainName);
      return;
    }
    int ticks = staleTrainTicks.merge(trainName, 1, Integer::sum);
    if (ticks >= STALE_THRESHOLD_TICKS) {
      staleTrainTicks.remove(trainName);
      dispatchService.handleAbnormalGroup(group, "stale-no-progress");
    }
  }

  /**
   * 判断当前列车是否应进入“脱管 FTA 列车”连续计数。
   *
   * <p>已有 progress entry 的列车可能正在正常红灯、dwell 或 queue 等待，不能按 no-progress 清理；只有没有 progress 且仍携带 FTA
   * 线路标签的列车，才视为可能脱管。
   */
  static boolean shouldTrackStaleFtaTrain(
      boolean hasProgressEntry, boolean hasRouteIndex, boolean hasRouteId, boolean hasOperator) {
    if (hasProgressEntry) {
      return false;
    }
    return hasRouteIndex || hasRouteId || hasOperator;
  }

  /**
   * 判断周期巡检是否需要处理某个 TrainCarts 编组。
   *
   * <p>普通非 FTA 列车不进入 dispatch/ETA/orphan active 集合，避免与 FTA 状态同名时影响清理；但明确 derailed 的普通列车仍必须进入安全销毁兜底。
   */
  static boolean shouldInspectRuntimeGroup(boolean hasFtaRuntimeTag, boolean derailed) {
    return hasFtaRuntimeTag || derailed;
  }

  /** 判断 TrainCarts 当前状态列表中是否包含明确脱轨标记，供巡检与事件侧共用。 */
  static boolean isDerailed(MinecartGroup group) {
    if (group == null) {
      return false;
    }
    List<TrainStatus> statuses = group.getStatusInfo();
    if (statuses == null || statuses.isEmpty()) {
      return false;
    }
    for (TrainStatus status : statuses) {
      if (status instanceof TrainStatus.Derailed) {
        return true;
      }
    }
    return false;
  }

  private String resolveLogicalTrainName(MinecartGroup group) {
    if (group == null || group.getProperties() == null) {
      return null;
    }
    return dispatchService
        .resolveTrackedTrainName(group.getProperties())
        .orElse(group.getProperties().getTrainName());
  }

  private String resolveRawTrainName(MinecartGroup group) {
    if (group == null || group.getProperties() == null) {
      return null;
    }
    String trainName = group.getProperties().getTrainName();
    return trainName == null || trainName.isBlank() ? null : trainName.trim();
  }

  /**
   * 收集本轮必须视为存活的运行时 owner。
   *
   * <p>真实 TrainCarts 改名发生在巡检取样之后；若 owner 原子迁移失败，调度层会故意保留 tag 中的旧 owner 与全部硬授权。orphan cleanup
   * 因而必须同时看见当前解析名和持久化 tag，不能在同一轮把旧方向/折返 footprint 当成幽灵占用释放。
   */
  static Set<String> activeRuntimeOwnerNames(String resolvedName, String taggedName) {
    Set<String> names = new HashSet<>();
    if (resolvedName != null && !resolvedName.isBlank()) {
      names.add(resolvedName.trim());
    }
    if (taggedName != null && !taggedName.isBlank()) {
      names.add(taggedName.trim());
    }
    return Set.copyOf(names);
  }

  /** handleSignalTick 完成后以实际写回的 owner tag 驱动 stale/ETA，迁移成功时该值就是新名称。 */
  private static String resolvePersistedRuntimeOwnerName(MinecartGroup group, String fallbackName) {
    if (group == null || group.getProperties() == null) {
      return fallbackName;
    }
    return TrainTagHelper.readTagValue(group.getProperties(), RouteProgressRegistry.TAG_TRAIN_NAME)
        .filter(name -> !name.isBlank())
        .map(String::trim)
        .orElse(fallbackName);
  }

  /** 判断当前列车名是否只是另一逻辑列车名的 split 别名。 */
  private static boolean isSplitAliasName(String currentTrainName, String taggedTrainName) {
    if (currentTrainName == null || taggedTrainName == null) {
      return false;
    }
    if (currentTrainName.length() <= taggedTrainName.length()
        || !currentTrainName.regionMatches(true, 0, taggedTrainName, 0, taggedTrainName.length())) {
      return false;
    }
    int index = taggedTrainName.length();
    while (index < currentTrainName.length()) {
      if (currentTrainName.charAt(index) != '~') {
        return false;
      }
      index++;
      int segmentStart = index;
      while (index < currentTrainName.length() && currentTrainName.charAt(index) != '~') {
        char c = currentTrainName.charAt(index);
        if (!Character.isLetterOrDigit(c)) {
          return false;
        }
        index++;
      }
      if (segmentStart == index) {
        return false;
      }
    }
    return true;
  }

  private NodeSampleInfo resolveNodeInfo(String trainName) {
    if (routeProgressRegistry == null || routeDefinitions == null) {
      return NodeSampleInfo.EMPTY;
    }
    Optional<RouteProgressRegistry.RouteProgressEntry> entryOpt =
        routeProgressRegistry.get(trainName);
    if (entryOpt.isEmpty()) {
      return NodeSampleInfo.EMPTY;
    }
    RouteProgressRegistry.RouteProgressEntry entry = entryOpt.get();
    if (entry.routeUuid() == null) {
      return new NodeSampleInfo(
          Optional.empty(), Optional.empty(), Optional.ofNullable(entry.lastSignal()));
    }
    Optional<RouteDefinition> routeOpt = routeDefinitions.findById(entry.routeUuid());
    if (routeOpt.isEmpty()) {
      return new NodeSampleInfo(
          Optional.empty(), Optional.empty(), Optional.ofNullable(entry.lastSignal()));
    }
    RouteDefinition route = routeOpt.get();
    List<NodeId> waypoints = route.waypoints();
    int currentIndex = entry.currentIndex();
    Optional<NodeId> currentNodeId =
        (currentIndex >= 0 && currentIndex < waypoints.size())
            ? Optional.of(waypoints.get(currentIndex))
            : Optional.empty();
    // 优先使用 RouteProgressEntry 中的 lastPassedGraphNode（中间 waypoint 触发会更新）
    // 若为空则回退到 route waypoints 上一个节点
    Optional<NodeId> lastPassedNodeId = entry.lastPassedGraphNode();
    if (lastPassedNodeId.isEmpty() && currentIndex > 0 && currentIndex - 1 < waypoints.size()) {
      lastPassedNodeId = Optional.of(waypoints.get(currentIndex - 1));
    }
    return new NodeSampleInfo(
        currentNodeId, lastPassedNodeId, Optional.ofNullable(entry.lastSignal()));
  }

  private record NodeSampleInfo(
      Optional<NodeId> currentNodeId,
      Optional<NodeId> lastPassedNodeId,
      Optional<SignalAspect> signalAspect) {
    static final NodeSampleInfo EMPTY =
        new NodeSampleInfo(Optional.empty(), Optional.empty(), Optional.empty());
  }

  /** 同一候选处理已进入 fail-closed 边界后，停止本次预算并保留游标的内部信号。 */
  private static final class CycleProcessingInterrupted extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private static final CycleProcessingInterrupted INSTANCE = new CycleProcessingInterrupted();

    private CycleProcessingInterrupted() {
      super(null, null, false, false);
    }
  }

  /** 一个不可被后续候选快照替换的巡检周期。 */
  private record RuntimeCycleSnapshot(List<GroupTickTarget> candidates) {
    private RuntimeCycleSnapshot {
      candidates = List.copyOf(candidates);
    }
  }

  private record GroupTickTarget(MinecartGroup group, String trainName, String rawTrainName) {}

  private void cleanupSnapshotStore(Set<String> activeTrainNames) {
    if (snapshotStore == null || activeTrainNames == null) {
      return;
    }
    for (String trainName : snapshotStore.snapshot().keySet()) {
      if (!activeTrainNames.contains(trainName)) {
        snapshotStore.remove(trainName);
      }
    }
  }
}
