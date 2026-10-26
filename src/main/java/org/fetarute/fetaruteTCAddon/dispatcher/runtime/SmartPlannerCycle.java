package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartDispatcherPlannerMode;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartWaitForPlanner;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.TrainNameNormalizer;
import org.fetarute.fetaruteTCAddon.utils.DiagnosticSink;

/**
 * Smart 调度等待图规划的每轮编排：主线程采集、执行器规划、主线程回收与执行。
 *
 * <p>一轮分三段。主线程先观察生效中的解锁预约，再采集等待边；有边才为边上的车采集列车状态，拼成不可变的 {@link
 * SmartWaitForPlanner.PlannerInput}。规划是纯函数，交给执行器去算。结果回到主线程后，才记方向审计、输出 trace、过节流，并在 {@code
 * ENFORCE_MINIMAL_FORWARD} 下经每轮复核后执行。会写账本与预约的动作因此只在主线程发生，执行器上只跑纯函数。
 *
 * <p>执行器默认是调用线程直接执行：提交即算完，结果在同一轮回收，行为与把规划写在主线程里完全一样。换成后台执行器后，本轮提交的结果留到下一轮回收；
 * 上一份还没算完就整轮跳过，不等、也不叠第二份。下一轮回收时先核对规划依据此刻是否仍成立（见 {@link #staleReason}），不成立只留痕、不执行。
 *
 * <p>没有任何等待边时不采集列车状态、不提交规划，直接按 {@link SmartWaitForPlanner#emptyGraphResult} 走节流与 trace。
 *
 * <p>本类的全部状态只在主线程读写；方向审计表另由健康监控读取，用并发表。
 */
public final class SmartPlannerCycle {

  /** 后台规划线程名。 */
  static final String WORKER_THREAD_NAME = "FetaruteTCAddon-SmartPlanner";

  private static final Executor CALLER_THREAD = Runnable::run;

  /**
   * 规划依赖的调度现场。
   *
   * <p>除 {@link #plan} 外，全部方法只在主线程调用；{@link #plan} 必须是不读可变现场的纯函数，可在执行器线程上调用。
   */
  interface Host {

    ConfigManager.SmartDispatcherPlannerSettings plannerSettings();

    /** 全局 Smart 调度是否关闭。 */
    boolean dispatcherOff();

    void observeReservations(Instant now);

    List<OccupancyClaim> snapshotClaims();

    List<SmartWaitForPlanner.InputEdge> inputEdges(
        Map<String, RouteProgressRegistry.RouteProgressEntry> progress,
        Set<String> activeTrainNames,
        Instant now,
        long blockerSnapshotTtlMs,
        List<OccupancyClaim> liveClaims);

    /** 只为等待边涉及的车采集状态；规划器只按边上的车名查表。 */
    Map<String, SmartWaitForPlanner.TrainState> trainStates(
        Map<String, RouteProgressRegistry.RouteProgressEntry> progress,
        Set<String> activeTrainNames,
        Instant now,
        long blockerSnapshotTtlMs,
        List<OccupancyClaim> liveClaims,
        List<SmartWaitForPlanner.InputEdge> edges);

    Set<String> activeCycleReservations();

    Set<String> activeReservationTrains();

    long occupancyVersion();

    long progressVersion();

    /** 列车此刻的进度窗口（线路、序号、最近经过节点）；没有进度时为 {@code "-"}。 */
    String progressWindow(String trainName);

    /** 纯函数：可在执行器线程上调用。 */
    SmartWaitForPlanner.PlanResult plan(SmartWaitForPlanner.PlannerInput input);

    void tracePlan(SmartWaitForPlanner.PlanResult result);

    void traceSelected(
        SmartWaitForPlanner.UnlockCandidate plan,
        SmartWaitForPlanner.PlannerInput input,
        boolean suppressedAsUnchanged,
        List<OccupancyClaim> liveClaims);

    /** 现有每轮复核；{@code "-"} 表示可以执行。 */
    String executorSkipReason(
        SmartWaitForPlanner.UnlockCandidate plan,
        ConfigManager.SmartDispatcherPlannerSettings config,
        Instant now,
        List<OccupancyClaim> liveClaims);

    void traceExecutorSkipped(SmartWaitForPlanner.UnlockCandidate plan, String reason);

    /** 会写账本或预约的执行动作。 */
    void execute(
        SmartWaitForPlanner.UnlockCandidate plan,
        ConfigManager.SmartDispatcherPlannerSettings config,
        Instant now,
        List<OccupancyClaim> liveClaims);
  }

  /**
   * 一份已提交的规划。
   *
   * @param progressWindows 提交时等待边上各车的进度窗口，键为规范化车名；只在跨轮回收时用来核对
   */
  private record Job(
      SmartWaitForPlanner.PlannerInput input,
      ConfigManager.SmartDispatcherPlannerSettings config,
      long occupancyVersion,
      long progressVersion,
      Map<String, String> progressWindows,
      CompletableFuture<SmartWaitForPlanner.PlanResult> result) {}

  private record DirectionAudit(String trainName, String reason, Instant capturedAt) {}

  private final Host host;
  private final Consumer<String> debugLogger;
  private final ConcurrentMap<String, DirectionAudit> directionAudits = new ConcurrentHashMap<>();
  private Executor executor = CALLER_THREAD;
  private ExecutorService ownedExecutor;
  private Job pending;
  private String lastThrottleKey = "";

  SmartPlannerCycle(Host host, Consumer<String> debugLogger) {
    this.host = Objects.requireNonNull(host, "host");
    this.debugLogger = debugLogger == null ? message -> {} : debugLogger;
  }

  /**
   * 把规划交给自有的单线程守护执行器。
   *
   * <p>调用后，每轮提交的规划在下一轮回收。插件停用时须调 {@link #shutdown()}。重复调用不会再建线程。
   */
  public void useBackgroundExecutor() {
    if (ownedExecutor == null || ownedExecutor.isShutdown()) {
      ownedExecutor =
          Executors.newSingleThreadExecutor(
              runnable -> {
                Thread thread = new Thread(runnable, WORKER_THREAD_NAME);
                thread.setDaemon(true);
                return thread;
              });
    }
    executor = ownedExecutor;
  }

  /** 关掉自有执行器，丢弃未回收的规划，之后回到调用线程直接规划。 */
  public void shutdown() {
    pending = null;
    executor = CALLER_THREAD;
    ExecutorService owned = ownedExecutor;
    ownedExecutor = null;
    if (owned != null) {
      owned.shutdownNow();
    }
  }

  /** 换执行器；{@code null} 表示调用线程直接执行。已提交未回收的规划照旧在下一轮回收。 */
  void useExecutor(Executor executor) {
    this.executor = executor == null ? CALLER_THREAD : executor;
  }

  /** 规划依赖的调度现场。 */
  Host host() {
    return host;
  }

  /** 是否有一份已提交、尚未回收的规划。 */
  boolean hasPendingPlan() {
    return pending != null;
  }

  /** 未回收的那份规划是否已经算完，下一轮即可回收。 */
  boolean pendingPlanReady() {
    Job job = pending;
    return job != null && job.result().isDone();
  }

  /**
   * 跑一轮规划。只在主线程调用。
   *
   * @param activeTrainNames 本轮确认存活的逻辑列车名
   * @param progress 本轮的进度表快照
   * @param now 本轮时刻
   * @param liveClaims 本轮的同一份账本快照
   */
  void runRound(
      Set<String> activeTrainNames,
      Map<String, RouteProgressRegistry.RouteProgressEntry> progress,
      Instant now,
      List<OccupancyClaim> liveClaims) {
    ConfigManager.SmartDispatcherPlannerSettings config = host.plannerSettings();
    if (!config.enabled()
        || config.mode() == SmartDispatcherPlannerMode.OFF
        || host.dispatcherOff()) {
      pending = null;
      return;
    }
    host.observeReservations(now);
    Job previous = pending;
    if (previous != null && !previous.result().isDone()) {
      if (DiagnosticSink.enabled(debugLogger)) {
        debugLogger.accept(
            "SMART_DISPATCH_PLAN_PENDING_ROUND_SKIPPED capturedAt="
                + previous.input().capturedAt()
                + " capturedOccupancyVersion="
                + previous.occupancyVersion());
      }
      return;
    }
    pending = null;
    List<OccupancyClaim> claims = liveClaims == null ? List.of() : liveClaims;
    long ttlMs = config.blockerSnapshotTtlMs();
    List<SmartWaitForPlanner.InputEdge> edges =
        host.inputEdges(progress, activeTrainNames, now, ttlMs, claims);
    if (previous != null && consume(previous, false, now, config, edges, claims)) {
      // 执行动作改过账本与预约：本轮输入按执行后的现场重采，等同于同步规划时「下一轮看得见上一轮的动作」。
      claims = host.snapshotClaims();
      edges = host.inputEdges(progress, activeTrainNames, now, ttlMs, claims);
    }
    boolean tracing = DiagnosticSink.enabled(debugLogger);
    if (edges.isEmpty()) {
      SmartWaitForPlanner.PlannerInput idleInput =
          plannerInput(now, config, List.of(), Map.of(), tracing);
      consume(
          new Job(
              idleInput,
              config,
              -1L,
              -1L,
              Map.of(),
              CompletableFuture.completedFuture(SmartWaitForPlanner.emptyGraphResult(tracing))),
          true,
          now,
          config,
          edges,
          claims);
      return;
    }
    SmartWaitForPlanner.PlannerInput input =
        plannerInput(
            now,
            config,
            edges,
            host.trainStates(progress, activeTrainNames, now, ttlMs, claims, edges),
            tracing);
    long occupancyVersion = host.occupancyVersion();
    long progressVersion = host.progressVersion();
    CompletableFuture<SmartWaitForPlanner.PlanResult> result = submit(input);
    // 只有调用线程直接执行时才算「同一轮出结论」；后台执行器即使抢先算完，也按约定留到下一轮回收，
    // 免得结论落在哪一轮取决于线程调度。
    if (executor == CALLER_THREAD) {
      consume(
          new Job(input, config, occupancyVersion, progressVersion, Map.of(), result),
          true,
          now,
          config,
          edges,
          claims);
      return;
    }
    pending =
        new Job(input, config, occupancyVersion, progressVersion, progressWindows(edges), result);
  }

  /**
   * 列车最近一次规划方向审计原因；过期或不存在时为空。
   *
   * @param trainName 列车名
   * @param ttl 有效期；为空或非正时不过期
   * @param now 当前时刻
   */
  Optional<String> recentDirectionAuditReason(String trainName, Duration ttl, Instant now) {
    if (trainName == null || trainName.isBlank()) {
      return Optional.empty();
    }
    Duration effectiveTtl = ttl == null || ttl.isNegative() ? Duration.ZERO : ttl;
    String key = TrainNameNormalizer.normalizeKey(trainName);
    DirectionAudit audit = directionAudits.get(key);
    if (audit == null) {
      return Optional.empty();
    }
    if (!effectiveTtl.isZero() && audit.capturedAt().plus(effectiveTtl).isBefore(now)) {
      directionAudits.remove(key, audit);
      return Optional.empty();
    }
    return Optional.of(audit.reason());
  }

  private SmartWaitForPlanner.PlannerInput plannerInput(
      Instant now,
      ConfigManager.SmartDispatcherPlannerSettings config,
      List<SmartWaitForPlanner.InputEdge> edges,
      Map<String, SmartWaitForPlanner.TrainState> trainStates,
      boolean tracing) {
    return new SmartWaitForPlanner.PlannerInput(
        now,
        plannerSettings(config),
        edges,
        trainStates,
        host.activeCycleReservations(),
        host.activeReservationTrains(),
        tracing);
  }

  private static SmartWaitForPlanner.PlannerSettings plannerSettings(
      ConfigManager.SmartDispatcherPlannerSettings config) {
    return new SmartWaitForPlanner.PlannerSettings(
        config.enabled(),
        config.mode(),
        config.maxReservationResources(),
        config.reservationTtlTicks(),
        config.blockerSnapshotTtlMs(),
        config.requireSameDirection(),
        config.allowReverse(),
        config.allowTurnbackBeforeBoundary(),
        config.oneActiveReservationPerCycle());
  }

  /** 交给执行器；执行器拒收（例如停用途中已关闭）时就地规划，不丢这一轮。 */
  private CompletableFuture<SmartWaitForPlanner.PlanResult> submit(
      SmartWaitForPlanner.PlannerInput input) {
    try {
      return CompletableFuture.supplyAsync(() -> host.plan(input), executor);
    } catch (RejectedExecutionException rejected) {
      return CompletableFuture.supplyAsync(() -> host.plan(input), CALLER_THREAD);
    }
  }

  private Map<String, String> progressWindows(List<SmartWaitForPlanner.InputEdge> edges) {
    Map<String, String> windows = new HashMap<>();
    for (SmartWaitForPlanner.InputEdge edge : edges) {
      rememberProgressWindow(windows, edge.blockedTrain());
      rememberProgressWindow(windows, edge.blockerTrain());
    }
    return Map.copyOf(windows);
  }

  private void rememberProgressWindow(Map<String, String> windows, String trainName) {
    String key = TrainNameNormalizer.normalizeKey(trainName);
    if (!key.isEmpty() && !windows.containsKey(key)) {
      windows.put(key, host.progressWindow(trainName));
    }
  }

  /**
   * 在主线程回收一份规划：方向审计、trace、节流，ENFORCE 下复核后执行。
   *
   * @param sameRound 是否与提交在同一轮；同一轮的输入就是此刻的现场，不再核对
   * @return 是否调用了执行动作
   */
  private boolean consume(
      Job job,
      boolean sameRound,
      Instant now,
      ConfigManager.SmartDispatcherPlannerSettings config,
      List<SmartWaitForPlanner.InputEdge> currentEdges,
      List<OccupancyClaim> liveClaims) {
    SmartWaitForPlanner.PlanResult result = completedResult(job.result());
    rememberDirectionAudit(result, job.input().capturedAt());
    if (job.input().collectTraces() && result.traceLines().isEmpty()) {
      return false;
    }
    Optional<SmartWaitForPlanner.UnlockCandidate> selected = result.selectedPlan();
    if (result.throttleKey().equals(lastThrottleKey)) {
      selected.ifPresent(plan -> host.traceSelected(plan, job.input(), true, liveClaims));
      if (DiagnosticSink.enabled(debugLogger)) {
        debugLogger.accept(
            "SMART_DISPATCH_PLAN_UNCHANGED_SUPPRESSED graphHash="
                + result.graphHash()
                + " selectedPlanHash="
                + result.selectedPlanHash()
                + " throttleKey="
                + result.throttleKey());
      }
    } else {
      lastThrottleKey = result.throttleKey();
      host.tracePlan(result);
      selected.ifPresent(plan -> host.traceSelected(plan, job.input(), false, liveClaims));
    }
    // 节流只管重复 trace；执行资格每轮复核，否则预约回滚后的冷却到期也会因等待图未变而永远失去重试机会。
    if (config.mode() != SmartDispatcherPlannerMode.ENFORCE_MINIMAL_FORWARD || selected.isEmpty()) {
      return false;
    }
    SmartWaitForPlanner.UnlockCandidate plan = selected.orElseThrow();
    if (!sameRound) {
      String stale = staleReason(job, plan, config, currentEdges);
      if (!"-".equals(stale)) {
        traceStaleDiscarded(job, plan, stale);
        return false;
      }
    }
    String skipReason = host.executorSkipReason(plan, config, now, liveClaims);
    if (!"-".equals(skipReason)) {
      host.traceExecutorSkipped(plan, skipReason);
      return false;
    }
    host.execute(plan, config, now, liveClaims);
    return true;
  }

  /**
   * 一份上一轮的规划此刻还能不能执行；{@code "-"} 表示依据仍成立。
   *
   * <p>不拿全网的占用版本与进度版本做闸：别处任何一辆车动一下两者都会推进，繁忙线网里几乎每轮都对不上，等于把执行关掉。 这里核对的是这份规划实际依赖的东西：
   *
   * <ol>
   *   <li>规划设置没变；
   *   <li>规划要释放的那几条等待边（被选车挡着谁、挡在哪个资源上）此刻仍按同一套入图规则进图——边上的现场复核用的是本轮账本；
   *   <li>被选车与它挡着的车都还在原来的进度窗口上；进度版本没动时整网进度未变，免查。
   * </ol>
   */
  private String staleReason(
      Job job,
      SmartWaitForPlanner.UnlockCandidate plan,
      ConfigManager.SmartDispatcherPlannerSettings config,
      List<SmartWaitForPlanner.InputEdge> currentEdges) {
    if (!job.config().equals(config)) {
      return "SETTINGS_CHANGED";
    }
    Set<String> captured =
        releaseEdgeKeys(
            SmartWaitForPlanner.admittedEdges(job.input().edges(), job.input().settings()), plan);
    Set<String> current =
        releaseEdgeKeys(
            SmartWaitForPlanner.admittedEdges(currentEdges, plannerSettings(config)), plan);
    if (captured.isEmpty() || !current.containsAll(captured)) {
      return "RELEASE_EDGE_GONE";
    }
    if (host.progressVersion() == job.progressVersion()) {
      return "-";
    }
    Set<String> subjects = new LinkedHashSet<>();
    subjects.add(plan.train());
    for (SmartWaitForPlanner.InputEdge edge : job.input().edges()) {
      if (captured.contains(releaseEdgeKey(edge))) {
        subjects.add(edge.blockedTrain());
      }
    }
    for (String train : subjects) {
      String recorded = job.progressWindows().get(TrainNameNormalizer.normalizeKey(train));
      if (recorded == null || !recorded.equals(host.progressWindow(train))) {
        return "PROGRESS_MOVED";
      }
    }
    return "-";
  }

  /** 被选车作为挡车方、挡在其释放资源上的等待边，按（被挡车, 资源）去重。 */
  private static Set<String> releaseEdgeKeys(
      Collection<SmartWaitForPlanner.InputEdge> edges, SmartWaitForPlanner.UnlockCandidate plan) {
    Set<String> resources = new HashSet<>(plan.releaseResources());
    Set<String> keys = new HashSet<>();
    for (SmartWaitForPlanner.InputEdge edge : edges) {
      if (TrainNameNormalizer.sameLogicalTrain(edge.blockerTrain(), plan.train())
          && resources.contains(edge.resource())) {
        keys.add(releaseEdgeKey(edge));
      }
    }
    return keys;
  }

  private static String releaseEdgeKey(SmartWaitForPlanner.InputEdge edge) {
    return TrainNameNormalizer.normalizeKey(edge.blockedTrain())
        + "|"
        + TrainNameNormalizer.normalizeKey(edge.blockerTrain())
        + "|"
        + edge.resource();
  }

  private void traceStaleDiscarded(
      Job job, SmartWaitForPlanner.UnlockCandidate plan, String reason) {
    if (!DiagnosticSink.enabled(debugLogger)) {
      return;
    }
    debugLogger.accept(
        "SMART_DISPATCH_PLAN_STALE_DISCARDED train="
            + plan.train()
            + " cycleId="
            + plan.cycleId()
            + " planHash="
            + plan.planHash()
            + " reason="
            + reason
            + " capturedAt="
            + job.input().capturedAt()
            + " capturedOccupancyVersion="
            + job.occupancyVersion()
            + " occupancyVersion="
            + host.occupancyVersion()
            + " capturedProgressVersion="
            + job.progressVersion()
            + " progressVersion="
            + host.progressVersion());
  }

  private void rememberDirectionAudit(SmartWaitForPlanner.PlanResult result, Instant capturedAt) {
    if (result == null || result.candidates().isEmpty()) {
      return;
    }
    for (SmartWaitForPlanner.UnlockCandidate candidate : result.candidates()) {
      if (candidate == null || !directionAuditReason(candidate.rejectReason())) {
        continue;
      }
      directionAudits.put(
          TrainNameNormalizer.normalizeKey(candidate.train()),
          new DirectionAudit(candidate.train(), candidate.rejectReason(), capturedAt));
    }
  }

  private static boolean directionAuditReason(String reason) {
    if (reason == null || reason.isBlank()) {
      return false;
    }
    String normalized = reason.trim().toUpperCase(java.util.Locale.ROOT);
    return normalized.equals("INSUFFICIENT_DIRECTION_EVIDENCE")
        || normalized.equals("NEED_DIRECTION_AUDIT")
        || normalized.equals("WOULD_CREATE_OPPOSITE_DIRECTION_CLAIM");
  }

  /**
   * 取已完成的规划结果，不阻塞。
   *
   * <p>规划抛出的异常在主线程原样重抛，与规划写在主线程里时一样交给巡检的 fail-closed 边界处理。
   */
  private static SmartWaitForPlanner.PlanResult completedResult(
      CompletableFuture<SmartWaitForPlanner.PlanResult> result) {
    if (!result.isCompletedExceptionally()) {
      return result.resultNow();
    }
    Throwable failure = result.exceptionNow();
    if (failure instanceof CompletionException && failure.getCause() != null) {
      failure = failure.getCause();
    }
    if (failure instanceof RuntimeException runtime) {
      throw runtime;
    }
    if (failure instanceof Error error) {
      throw error;
    }
    throw new IllegalStateException("smart planner failed", failure);
  }

  /**
   * 判定车名集合里哪些车需要采集规划状态。
   *
   * <p>规划器只按等待边上的车名查状态表，而状态表的键是解析后的运行时名，可能与采集时的名字不同（改名、拆编别名）。 因此一辆车只要自己的规范名或解析名落在关注集合里就要采集；
   * 采集它会读写以这两个规范名为键的缓存，所以把它的两个规范名也并进关注集合，直到不再扩大。 这样留下的车与被跳过的车互不共享任何以规范名为键的状态，跳过不会改变留下那些车的采集结果。
   *
   * @param names 采集候选，按原顺序
   * @param resolvedNames 与 {@code names} 一一对应的解析名
   * @param edges 本轮等待边
   * @return 与 {@code names} 一一对应的「需要采集」标记
   */
  static boolean[] plannerRelevantTrains(
      List<String> names,
      List<String> resolvedNames,
      Collection<SmartWaitForPlanner.InputEdge> edges) {
    boolean[] relevant = new boolean[names.size()];
    Set<String> keys = new HashSet<>();
    for (SmartWaitForPlanner.InputEdge edge : edges) {
      keys.add(TrainNameNormalizer.normalizeKey(edge.blockedTrain()));
      keys.add(TrainNameNormalizer.normalizeKey(edge.blockerTrain()));
    }
    keys.remove("");
    List<String> ownKeys = new ArrayList<>(names.size());
    List<String> resolvedKeys = new ArrayList<>(names.size());
    for (int i = 0; i < names.size(); i++) {
      ownKeys.add(TrainNameNormalizer.normalizeKey(names.get(i)));
      resolvedKeys.add(TrainNameNormalizer.normalizeKey(resolvedNames.get(i)));
    }
    boolean grew = true;
    while (grew) {
      grew = false;
      for (int i = 0; i < names.size(); i++) {
        if (relevant[i]) {
          continue;
        }
        String own = ownKeys.get(i);
        String resolved = resolvedKeys.get(i);
        if (!keys.contains(own) && !keys.contains(resolved)) {
          continue;
        }
        relevant[i] = true;
        if (!own.isEmpty() && keys.add(own)) {
          grew = true;
        }
        if (!resolved.isEmpty() && keys.add(resolved)) {
          grew = true;
        }
      }
    }
    return relevant;
  }
}
