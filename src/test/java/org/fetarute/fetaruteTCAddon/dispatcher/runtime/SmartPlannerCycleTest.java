package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartDispatcherPlannerMode;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartWaitForPlanner;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.CorridorDirection;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.junit.jupiter.api.Test;

/**
 * {@link SmartPlannerCycle} 的编排：空图早退、后台规划与同步规划结论一致、过期结果丢弃、未算完跳过、执行只在主线程且只在复核通过时发生。
 *
 * <p>用假现场驱动，规划器用真的 {@link SmartWaitForPlanner}。
 */
class SmartPlannerCycleTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void emptyWaitGraphSkipsTrainStatesAndPlanner() {
    FakeHost host = new FakeHost();
    List<String> debug = new ArrayList<>();
    SmartPlannerCycle cycle = new SmartPlannerCycle(host, debug::add);

    cycle.runRound(Set.of("A", "B"), Map.of(), T0, List.of());
    cycle.runRound(Set.of("A", "B"), Map.of(), T0.plusSeconds(3), List.of());

    assertEquals(2, host.observeCalls);
    assertEquals(0, host.trainStateCalls, "没有车被挡时不得采集列车状态");
    assertEquals(0, host.planCalls.get(), "没有车被挡时不得调用规划器");
    assertEquals(List.of(SmartWaitForPlanner.emptyGraphResult(true)), host.traced);
    assertTrue(
        debug.stream()
            .anyMatch(line -> line.startsWith("SMART_DISPATCH_PLAN_UNCHANGED_SUPPRESSED")),
        debug.toString());
  }

  @Test
  void statesAreRequestedOnlyWithTheEdgesThatProduceThem() {
    FakeHost host = deadlockedHost();
    SmartPlannerCycle cycle = new SmartPlannerCycle(host, line -> {});

    cycle.runRound(Set.of("WS-A", "WS-B", "WS-C"), Map.of(), T0, List.of());

    assertEquals(1, host.trainStateCalls);
    assertEquals(host.edges, host.lastStateEdges);
    assertEquals(1, host.planCalls.get());
    assertEquals(1, host.executed.size(), "同步执行器：同一轮回收并执行");
  }

  /** 后台规划的结论（trace、选中方案、执行动作）与调用线程直接规划逐项相同，只是晚一轮。 */
  @Test
  void backgroundPlanMatchesSynchronousPlanOneRoundLater() throws Exception {
    FakeHost syncHost = deadlockedHost();
    SmartPlannerCycle syncCycle = new SmartPlannerCycle(syncHost, line -> {});
    syncCycle.runRound(Set.of(), Map.of(), T0, List.of());

    FakeHost asyncHost = deadlockedHost();
    SmartPlannerCycle asyncCycle = new SmartPlannerCycle(asyncHost, line -> {});
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try {
      asyncCycle.useExecutor(worker);
      asyncCycle.runRound(Set.of(), Map.of(), T0, List.of());
      assertTrue(asyncHost.executed.isEmpty(), "提交的这一轮不得执行");
      worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
      asyncHost.clearRecords();
      asyncCycle.runRound(Set.of(), Map.of(), T0.plusSeconds(3), List.of());
    } finally {
      worker.shutdownNow();
    }

    assertEquals(syncHost.traced.get(0), asyncHost.traced.get(0), "规划结果逐项相同");
    assertEquals(syncHost.executed, asyncHost.executed, "执行的方案相同");
    assertEquals(1, asyncHost.executed.size());
  }

  /** 后台执行器当轮就算完，结论也留到下一轮：落在哪一轮不取决于线程调度。 */
  @Test
  void backgroundResultThatFinishesInlineStillWaitsForTheNextRound() {
    FakeHost host = deadlockedHost();
    SmartPlannerCycle cycle = new SmartPlannerCycle(host, line -> {});
    cycle.useExecutor(Runnable::run);

    cycle.runRound(Set.of(), Map.of(), T0, List.of());

    assertTrue(host.executed.isEmpty(), "提交的这一轮不得执行");
    assertTrue(cycle.hasPendingPlan());
    cycle.runRound(Set.of(), Map.of(), T0.plusSeconds(3), List.of());
    assertEquals(1, host.executed.size(), "下一轮回收并执行");
  }

  @Test
  void executionHappensOnlyOnTheCallingThreadAfterTheRecheckPasses() throws Exception {
    FakeHost host = deadlockedHost();
    SmartPlannerCycle cycle = new SmartPlannerCycle(host, line -> {});
    cycle.useBackgroundExecutor();
    try {
      cycle.runRound(Set.of(), Map.of(), T0, List.of());
      awaitPlanned(cycle);
      host.skipReason = "NO_RELEASE_COOLDOWN";
      cycle.runRound(Set.of(), Map.of(), T0.plusSeconds(3), List.of());
      assertTrue(host.executed.isEmpty(), "复核不过不得执行");
      assertEquals(List.of("NO_RELEASE_COOLDOWN"), host.skipped);

      awaitPlanned(cycle);
      host.skipReason = "-";
      cycle.runRound(Set.of(), Map.of(), T0.plusSeconds(6), List.of());
    } finally {
      cycle.shutdown();
    }

    assertEquals(1, host.executed.size());
    String caller = Thread.currentThread().getName();
    assertEquals(List.of(caller), host.executeThreads, "执行动作只在调用线程（主线程）");
    assertTrue(
        host.planThreads.stream().allMatch(SmartPlannerCycle.WORKER_THREAD_NAME::equals),
        "规划只在后台线程: " + host.planThreads);
  }

  @Test
  void unfinishedPlanSkipsTheRoundWithoutBlocking() {
    FakeHost host = deadlockedHost();
    ManualExecutor executor = new ManualExecutor();
    SmartPlannerCycle cycle = new SmartPlannerCycle(host, line -> {});
    cycle.useExecutor(executor);

    cycle.runRound(Set.of(), Map.of(), T0, List.of());
    assertEquals(1, executor.queued());
    assertTrue(cycle.hasPendingPlan());

    cycle.runRound(Set.of(), Map.of(), T0.plusSeconds(3), List.of());
    assertEquals(1, host.inputEdgeCalls, "上一份没算完：本轮不采集、不提交");
    assertEquals(1, executor.queued(), "不得叠第二份规划");
    assertTrue(host.executed.isEmpty());
    assertTrue(host.traced.isEmpty());

    executor.runAll();
    cycle.runRound(Set.of(), Map.of(), T0.plusSeconds(6), List.of());
    assertEquals(1, host.executed.size(), "算完后下一轮回收并执行");
  }

  @Test
  void resultIsDiscardedWhenTheReleaseEdgeIsGone() {
    FakeHost host = deadlockedHost();
    ManualExecutor executor = new ManualExecutor();
    List<String> debug = new ArrayList<>();
    SmartPlannerCycle cycle = new SmartPlannerCycle(host, debug::add);
    cycle.useExecutor(executor);

    cycle.runRound(Set.of(), Map.of(), T0, List.of());
    executor.runAll();
    host.edges = List.of(edge("WS-C", "WS-D", "CONFLICT:single:EAST"));
    host.occupancyVersion++;
    cycle.runRound(Set.of(), Map.of(), T0.plusSeconds(3), List.of());

    assertTrue(host.executed.isEmpty(), "等待边已经变了，上一轮的结论不得执行");
    assertTrue(host.skipped.isEmpty(), "过期结果不进入执行复核");
    assertTrue(
        debug.stream()
            .anyMatch(
                line ->
                    line.startsWith("SMART_DISPATCH_PLAN_STALE_DISCARDED")
                        && line.contains("reason=RELEASE_EDGE_GONE")),
        debug.toString());
  }

  @Test
  void resultIsDiscardedWhenASubjectTrainMoved() {
    FakeHost host = deadlockedHost();
    ManualExecutor executor = new ManualExecutor();
    List<String> debug = new ArrayList<>();
    SmartPlannerCycle cycle = new SmartPlannerCycle(host, debug::add);
    cycle.useExecutor(executor);

    cycle.runRound(Set.of(), Map.of(), T0, List.of());
    executor.runAll();
    host.windows.put("WS-A", "route|2|node-2");
    host.progressVersion++;
    cycle.runRound(Set.of(), Map.of(), T0.plusSeconds(3), List.of());

    assertTrue(host.executed.isEmpty());
    assertTrue(
        debug.stream().anyMatch(line -> line.contains("reason=PROGRESS_MOVED")), debug.toString());
  }

  @Test
  void resultIsDiscardedWhenPlannerSettingsChanged() {
    FakeHost host = deadlockedHost();
    ManualExecutor executor = new ManualExecutor();
    List<String> debug = new ArrayList<>();
    SmartPlannerCycle cycle = new SmartPlannerCycle(host, debug::add);
    cycle.useExecutor(executor);

    cycle.runRound(Set.of(), Map.of(), T0, List.of());
    executor.runAll();
    host.settings = settings(SmartDispatcherPlannerMode.ENFORCE_MINIMAL_FORWARD, 3);
    cycle.runRound(Set.of(), Map.of(), T0.plusSeconds(3), List.of());

    assertTrue(host.executed.isEmpty());
    assertTrue(
        debug.stream().anyMatch(line -> line.contains("reason=SETTINGS_CHANGED")),
        debug.toString());
  }

  /** 全网版本变了但规划依据（释放边、相关车进度）都没变：照常执行，不能被别处的车动一下就作废。 */
  @Test
  void unrelatedVersionChangesDoNotDiscardTheResult() {
    FakeHost host = deadlockedHost();
    ManualExecutor executor = new ManualExecutor();
    SmartPlannerCycle cycle = new SmartPlannerCycle(host, line -> {});
    cycle.useExecutor(executor);

    cycle.runRound(Set.of(), Map.of(), T0, List.of());
    executor.runAll();
    host.occupancyVersion += 17;
    host.progressVersion += 5;
    host.windows.put("WS-Z", "elsewhere|9|z");
    cycle.runRound(Set.of(), Map.of(), T0.plusSeconds(3), List.of());

    assertEquals(1, host.executed.size());
  }

  @Test
  void observeOnlyTracesBackgroundResultsWithoutExecuting() {
    FakeHost host = deadlockedHost();
    host.settings = settings(SmartDispatcherPlannerMode.OBSERVE_ONLY, 4);
    ManualExecutor executor = new ManualExecutor();
    SmartPlannerCycle cycle = new SmartPlannerCycle(host, line -> {});
    cycle.useExecutor(executor);

    cycle.runRound(Set.of(), Map.of(), T0, List.of());
    executor.runAll();
    cycle.runRound(Set.of(), Map.of(), T0.plusSeconds(3), List.of());

    assertEquals(1, host.traced.size());
    assertTrue(host.traced.get(0).selectedPlan().isPresent());
    assertTrue(host.executed.isEmpty());
    assertTrue(host.skipped.isEmpty());
  }

  @Test
  void plannerFailureIsRethrownOnTheCallingThread() {
    FakeHost host = deadlockedHost();
    IllegalStateException failure = new IllegalStateException("boom");
    host.planFunction =
        input -> {
          throw failure;
        };
    ManualExecutor executor = new ManualExecutor();
    SmartPlannerCycle cycle = new SmartPlannerCycle(host, line -> {});
    cycle.useExecutor(executor);

    cycle.runRound(Set.of(), Map.of(), T0, List.of());
    executor.runAll();
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> cycle.runRound(Set.of(), Map.of(), T0.plusSeconds(3), List.of()));
    assertSame(failure, thrown);

    SmartPlannerCycle direct = new SmartPlannerCycle(host, line -> {});
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class, () -> direct.runRound(Set.of(), Map.of(), T0, List.of())));
  }

  @Test
  void disablingThePlannerDropsThePendingResult() {
    FakeHost host = deadlockedHost();
    ManualExecutor executor = new ManualExecutor();
    SmartPlannerCycle cycle = new SmartPlannerCycle(host, line -> {});
    cycle.useExecutor(executor);

    cycle.runRound(Set.of(), Map.of(), T0, List.of());
    host.settings = settings(SmartDispatcherPlannerMode.OFF, 4);
    cycle.runRound(Set.of(), Map.of(), T0.plusSeconds(3), List.of());
    assertFalse(cycle.hasPendingPlan());
    executor.runAll();
    host.settings = settings(SmartDispatcherPlannerMode.ENFORCE_MINIMAL_FORWARD, 4);
    host.edges = List.of();
    cycle.runRound(Set.of(), Map.of(), T0.plusSeconds(6), List.of());
    assertTrue(host.executed.isEmpty());
  }

  @Test
  void directionAuditUsesCaptureTimeAndExpires() {
    FakeHost host = new FakeHost();
    host.edges =
        List.of(
            edge("WS-A", "WS-B", "CONFLICT:single:WS", CorridorDirection.UNKNOWN),
            edge("WS-B", "WS-A", "CONFLICT:single:WS", CorridorDirection.UNKNOWN));
    host.states = Map.of();
    SmartPlannerCycle cycle = new SmartPlannerCycle(host, line -> {});

    cycle.runRound(Set.of(), Map.of(), T0, List.of());

    assertEquals(
        java.util.Optional.of("INSUFFICIENT_DIRECTION_EVIDENCE"),
        cycle.recentDirectionAuditReason("ws-a", Duration.ofSeconds(10), T0.plusSeconds(5)));
    assertTrue(
        cycle
            .recentDirectionAuditReason("WS-A", Duration.ofSeconds(10), T0.plusSeconds(11))
            .isEmpty());
  }

  @Test
  void relevantTrainsCloseOverResolvedNames() {
    List<SmartWaitForPlanner.InputEdge> edges = List.of(edge("A", "B", "CONFLICT:single:X"));
    // C 解析成 b~1（与 B 同规范名）→ 相关；D 解析成 c（C 的规范名，经 C 才并进来）→ 相关；E 无关。
    boolean[] relevant =
        SmartPlannerCycle.plannerRelevantTrains(
            List.of("E", "D", "C", "a", "B"), List.of("E", "c", "b~1", "a", "B"), edges);
    assertArrayEquals(new boolean[] {false, true, true, true, true}, relevant);
  }

  /** 测试线程可以等；生产主线程从不等后台，只在下一轮看它算没算完。 */
  private static void awaitPlanned(SmartPlannerCycle cycle) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!cycle.pendingPlanReady() && System.nanoTime() < deadline) {
      Thread.sleep(5L);
    }
    assertTrue(cycle.pendingPlanReady(), "后台规划应在 10 秒内算完");
  }

  private static FakeHost deadlockedHost() {
    FakeHost host = new FakeHost();
    host.edges =
        List.of(
            edge("WS-A", "WS-B", "CONFLICT:single:WS"), edge("WS-B", "WS-A", "CONFLICT:single:WS"));
    host.states = Map.of("WS-A", state("WS-A", 20), "WS-B", state("WS-B", 40));
    host.windows.put("WS-A", "route|1|node-1");
    host.windows.put("WS-B", "route|1|node-1");
    return host;
  }

  private static SmartWaitForPlanner.InputEdge edge(
      String blocked, String blocker, String resource) {
    return edge(blocked, blocker, resource, CorridorDirection.A_TO_B);
  }

  private static SmartWaitForPlanner.InputEdge edge(
      String blocked, String blocker, String resource, CorridorDirection direction) {
    return new SmartWaitForPlanner.InputEdge(
        blocked,
        blocker,
        resource,
        null,
        "HARD_OCCUPANCY",
        "MOVEMENT_REQUIRED",
        "MOVEMENT_REQUIRED",
        "test",
        direction,
        10,
        true);
  }

  private static SmartWaitForPlanner.TrainState state(String train, long stuckSeconds) {
    return new SmartWaitForPlanner.TrainState(
        train,
        "COMP:OP:LINE:route",
        1,
        train + ":current",
        train + ":next",
        train + ":current",
        CorridorDirection.A_TO_B,
        "RUNTIME_ROUTE_CONTEXT",
        "-",
        stuckSeconds,
        true,
        false,
        false,
        false,
        false,
        false,
        "NONE");
  }

  private static ConfigManager.SmartDispatcherPlannerSettings settings(
      SmartDispatcherPlannerMode mode, int maxReservationResources) {
    return new ConfigManager.SmartDispatcherPlannerSettings(
        true, mode, maxReservationResources, 1200, 10_000L, true, false, false, true);
  }

  /** 只在被要求时才跑任务的执行器，模拟「后台还没算完」。 */
  private static final class ManualExecutor implements java.util.concurrent.Executor {
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

    @Override
    public void execute(Runnable command) {
      tasks.add(command);
    }

    int queued() {
      return tasks.size();
    }

    void runAll() {
      while (!tasks.isEmpty()) {
        tasks.removeFirst().run();
      }
    }
  }

  /** 记录每个回调的假现场。 */
  private static final class FakeHost implements SmartPlannerCycle.Host {
    ConfigManager.SmartDispatcherPlannerSettings settings =
        settings(SmartDispatcherPlannerMode.ENFORCE_MINIMAL_FORWARD, 4);
    List<SmartWaitForPlanner.InputEdge> edges = List.of();
    Map<String, SmartWaitForPlanner.TrainState> states = Map.of();
    final Map<String, String> windows = new HashMap<>();
    long occupancyVersion = 1L;
    long progressVersion = 1L;
    String skipReason = "-";
    Function<SmartWaitForPlanner.PlannerInput, SmartWaitForPlanner.PlanResult> planFunction =
        new SmartWaitForPlanner()::plan;

    int observeCalls;
    int inputEdgeCalls;
    int trainStateCalls;
    final AtomicInteger planCalls = new AtomicInteger();
    List<SmartWaitForPlanner.InputEdge> lastStateEdges;
    final List<String> planThreads = Collections.synchronizedList(new ArrayList<>());
    final List<SmartWaitForPlanner.PlanResult> traced = new ArrayList<>();
    final List<SmartWaitForPlanner.UnlockCandidate> executed = new ArrayList<>();
    final List<String> executeThreads = new ArrayList<>();
    final List<String> skipped = new ArrayList<>();

    void clearRecords() {
      traced.clear();
      executed.clear();
      executeThreads.clear();
      skipped.clear();
    }

    @Override
    public ConfigManager.SmartDispatcherPlannerSettings plannerSettings() {
      return settings;
    }

    @Override
    public boolean dispatcherOff() {
      return false;
    }

    @Override
    public void observeReservations(Instant now) {
      observeCalls++;
    }

    @Override
    public List<OccupancyClaim> snapshotClaims() {
      return List.of();
    }

    @Override
    public List<SmartWaitForPlanner.InputEdge> inputEdges(
        Map<String, RouteProgressRegistry.RouteProgressEntry> progress,
        Set<String> activeTrainNames,
        Instant now,
        long blockerSnapshotTtlMs,
        List<OccupancyClaim> liveClaims) {
      inputEdgeCalls++;
      return edges;
    }

    @Override
    public Map<String, SmartWaitForPlanner.TrainState> trainStates(
        Map<String, RouteProgressRegistry.RouteProgressEntry> progress,
        Set<String> activeTrainNames,
        Instant now,
        long blockerSnapshotTtlMs,
        List<OccupancyClaim> liveClaims,
        List<SmartWaitForPlanner.InputEdge> stateEdges) {
      trainStateCalls++;
      lastStateEdges = stateEdges;
      return states;
    }

    @Override
    public Set<String> activeCycleReservations() {
      return Set.of();
    }

    @Override
    public Set<String> activeReservationTrains() {
      return Set.of();
    }

    @Override
    public long occupancyVersion() {
      return occupancyVersion;
    }

    @Override
    public long progressVersion() {
      return progressVersion;
    }

    @Override
    public String progressWindow(String trainName) {
      return windows.getOrDefault(trainName, "-");
    }

    @Override
    public SmartWaitForPlanner.PlanResult plan(SmartWaitForPlanner.PlannerInput input) {
      planCalls.incrementAndGet();
      planThreads.add(Thread.currentThread().getName());
      return planFunction.apply(input);
    }

    @Override
    public void tracePlan(SmartWaitForPlanner.PlanResult result) {
      traced.add(result);
    }

    @Override
    public void traceSelected(
        SmartWaitForPlanner.UnlockCandidate plan,
        SmartWaitForPlanner.PlannerInput input,
        boolean suppressedAsUnchanged,
        List<OccupancyClaim> liveClaims) {}

    @Override
    public String executorSkipReason(
        SmartWaitForPlanner.UnlockCandidate plan,
        ConfigManager.SmartDispatcherPlannerSettings config,
        Instant now,
        List<OccupancyClaim> liveClaims) {
      return skipReason;
    }

    @Override
    public void traceExecutorSkipped(SmartWaitForPlanner.UnlockCandidate plan, String reason) {
      skipped.add(reason);
    }

    @Override
    public void execute(
        SmartWaitForPlanner.UnlockCandidate plan,
        ConfigManager.SmartDispatcherPlannerSettings config,
        Instant now,
        List<OccupancyClaim> liveClaims) {
      executed.add(plan);
      executeThreads.add(Thread.currentThread().getName());
    }
  }
}
