package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.testConfigView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartDispatcherMode;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.junit.jupiter.api.Test;

/**
 * 阻塞状态快照：记录**当前状态**，而不是状态变化。
 *
 * <p>现有诊断全是事件（enter/clear、acquire/release），只回答"发生了什么变化"。要回答"此刻是什么状态" 就只能拿事件流去推——这在 2026-09-13
 * 至少骗过我两次：「列车名不再出现」被推成「车停了」 （其实是 layover 复用改名），「最后一个事件是 acquire」被推成「资源搁浅」（其实是刷新周期里的重新取得）。
 * 两次都是把日志的一个切面当成了系统状态。
 *
 * <p>白名单用例（{@code DiagnosticBudgetConfigurableTest}）只证明"它不会被预算丢掉"，不证明"它真的被输出过"。
 * 本用例补上后者，并逐字段钉住——少任何一个，下一轮就又要靠猜。
 */
class BlockingStateSnapshotTest {

  private static final Instant NOW = Instant.parse("2026-09-13T18:30:00Z");

  @Test
  void blockedTrainProducesAStateSnapshotWithTheFieldsDiagnosisNeeds() throws Exception {
    List<String> debug = new ArrayList<>();
    RuntimeDispatchService service = service(debug);
    // 停车已经持续 3 分钟：实服 PROTECTIVE_RETAIN_HOLD 的中位就是 183 秒。
    installStopState(
        service,
        RuntimeStopState.occupancyHold(
            "train-1",
            "PROTECTIVE_RETAIN_HOLD",
            null,
            "protective-retain:no-self-retain-candidate",
            NOW.minus(Duration.ofMinutes(3))));

    service.traceSmartDispatchGlobalSnapshot(new LinkedHashSet<>(List.of("train-1")), NOW);

    String snapshot =
        debug.stream()
            .filter(l -> l.startsWith("SMART_BLOCKING_SNAPSHOT"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("没有产生阻塞状态快照：\n" + debug));

    assertTrue(snapshot.contains("heldSeconds=180"), "必须记录已经卡了多久：" + snapshot);
    for (String field :
        List.of(
            "train=",
            "reasonCode=PROTECTIVE_RETAIN_HOLD",
            "detail=protective-retain:no-self-retain-candidate",
            "releaseCondition=",
            "holdsByRole=",
            "holds=",
            "blockedBy=",
            "selfRetainReleaseCandidate=",
            "routeIndex=",
            "lastPassedGraphNode=",
            "movementToken=")) {
      assertTrue(snapshot.contains(field), "状态快照缺字段 " + field + "：\n" + snapshot);
    }
  }

  /**
   * 同一辆车必须按间隔节流。
   *
   * <p>这条 trace 在必留名单里，绕过重复窗口与预算两道闸——**去重责任全在生产端**。 周期方法生产端约每秒调用一次，实服同时卡 30 秒以上的车峰值约 9 辆；不节流就是 2600
   * × 9 ≈ 2.3 万行、约 10 MB，把日志体积翻倍。
   */
  @Test
  void repeatedCyclesDoNotReEmitTheSameTrainEveryTick() throws Exception {
    List<String> debug = new ArrayList<>();
    RuntimeDispatchService service = service(debug);
    installStopState(
        service,
        RuntimeStopState.occupancyHold(
            "train-1", "PROTECTIVE_RETAIN_HOLD", null, "stuck", NOW.minus(Duration.ofMinutes(3))));
    LinkedHashSet<String> active = new LinkedHashSet<>(List.of("train-1"));

    // 模拟 10 个周期（生产端约每秒一次），只跨过 1 个节流窗口。
    for (int i = 0; i < 10; i++) {
      service.traceSmartDispatchGlobalSnapshot(active, NOW.plusSeconds(i));
    }

    assertEquals(
        1,
        debug.stream().filter(l -> l.startsWith("SMART_BLOCKING_SNAPSHOT")).count(),
        "同一辆车在一个节流窗口内只该输出一次：\n" + debug);

    // 跨过窗口之后必须重新输出——节流不能变成静音。
    service.traceSmartDispatchGlobalSnapshot(active, NOW.plusSeconds(40));
    assertEquals(
        2,
        debug.stream().filter(l -> l.startsWith("SMART_BLOCKING_SNAPSHOT")).count(),
        "跨过节流窗口后必须继续采样：\n" + debug);
  }

  /** 刚进入停因的车不输出，避免把正常的短暂等待也刷成快照。 */
  @Test
  void freshHoldsAreNotSnapshotted() throws Exception {
    List<String> debug = new ArrayList<>();
    RuntimeDispatchService service = service(debug);
    installStopState(
        service,
        RuntimeStopState.occupancyHold(
            "train-1", "PROTECTIVE_RETAIN_HOLD", null, "just-started", NOW.minusSeconds(5)));

    service.traceSmartDispatchGlobalSnapshot(new LinkedHashSet<>(List.of("train-1")), NOW);

    assertEquals(
        0,
        debug.stream().filter(l -> l.startsWith("SMART_BLOCKING_SNAPSHOT")).count(),
        "刚停 5 秒不该输出快照：\n" + debug);
  }

  /**
   * 发车门控停车必须带上它自己的阻塞者，且没有记录时要自报，不许看起来像"没有阻塞者"。
   *
   * <p>代价是实打实的：2026-09-14 第十轮，WS-LH-0483 连续 106 条快照写着 {@code blockedBy=[]}，
   * 于是"它没有被任何东西挡住"被当成了事实。它其实正被自己的受害者 WS-LC-2008 挡着—— 一个 45 分钟的互锁环，而真正的阻塞者只出现在另一条独立日志上。
   */
  @Test
  void departureGateHoldCarriesItsOwnBlockersAndSelfReportsWhenItHasNone() throws Exception {
    List<String> debug = new ArrayList<>();
    RuntimeDispatchService service = service(debug);
    installStopState(
        service,
        RuntimeStopState.occupancyHold(
            "train-1",
            "DEPARTURE_GATE_HOLD",
            null,
            "autostation_dwell@sid-1",
            NOW.minus(Duration.ofMinutes(3))));

    // 每次采样都要推进时钟：快照有 15 秒最小间隔，同一时刻连采三次只会出第一条。
    Instant first = NOW;
    Instant second = NOW.plusSeconds(60);
    Instant third = NOW.plusSeconds(120);

    // 一：没有记录时必须自报 not-recorded，绝不能让读者误以为"没有阻塞者"。
    service.traceSmartDispatchGlobalSnapshot(new LinkedHashSet<>(List.of("train-1")), first);
    String empty = snapshotLine(debug);
    assertTrue(empty.contains("departureGateBlockedBy=not-recorded"), "没有记录时必须自报，而不是沉默：" + empty);

    // 二：有记录时必须把阻塞者显示出来——就是当初缺的那条信息。
    debug.clear();
    installDepartureGateBlockers(service, "CONFLICT:switcher:705@WS-LC-2008", second);
    service.traceSmartDispatchGlobalSnapshot(new LinkedHashSet<>(List.of("train-1")), second);
    String recorded = snapshotLine(debug);
    assertTrue(
        recorded.contains("departureGateBlockedBy=CONFLICT:switcher:705@WS-LC-2008"),
        "发车门控的阻塞者必须出现在快照里：" + recorded);

    // 三：过期的记录必须标成 stale，不能把旧切面当成当前状态——2026-09-13 栽过一次的正是这个。
    debug.clear();
    installDepartureGateBlockers(
        service, "CONFLICT:switcher:705@WS-LC-2008", third.minusSeconds(120));
    service.traceSmartDispatchGlobalSnapshot(new LinkedHashSet<>(List.of("train-1")), third);
    String stale = snapshotLine(debug);
    assertTrue(stale.contains("departureGateBlockedBy=stale@"), "过期记录必须自报过期：" + stale);
  }

  private static String snapshotLine(List<String> debug) {
    return debug.stream()
        .filter(l -> l.startsWith("SMART_BLOCKING_SNAPSHOT"))
        .findFirst()
        .orElseThrow(() -> new AssertionError("没有产生阻塞状态快照：\n" + debug));
  }

  @SuppressWarnings("unchecked")
  private static void installDepartureGateBlockers(
      RuntimeDispatchService service, String summary, Instant at) throws Exception {
    var field = RuntimeDispatchService.class.getDeclaredField("departureGateBlockers");
    field.setAccessible(true);
    var recordClass =
        Class.forName(
            "org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService$DepartureGateBlockers");
    var ctor = recordClass.getDeclaredConstructor(String.class, Instant.class);
    ctor.setAccessible(true);
    ((ConcurrentMap<String, Object>) field.get(service))
        .put("train-1", ctor.newInstance(summary, at));
  }

  @SuppressWarnings("unchecked")
  private static void installStopState(RuntimeDispatchService service, RuntimeStopState state)
      throws Exception {
    var field = RuntimeDispatchService.class.getDeclaredField("activeStopStates");
    field.setAccessible(true);
    ((ConcurrentMap<String, RuntimeStopState>) field.get(service)).put("train-1", state);
  }

  private static RuntimeDispatchService service(List<String> debug) {
    ConfigManager configManager = mock(ConfigManager.class);
    ConfigManager.ConfigView base = testConfigView(20, 20.0);
    when(configManager.current())
        .thenReturn(
            new ConfigManager.ConfigView(
                base.configVersion(),
                base.debugEnabled(),
                base.locale(),
                base.storageSettings(),
                base.graphSettings(),
                base.autoStationSettings(),
                base.runtimeSettings(),
                base.spawnSettings(),
                base.trainConfigSettings(),
                base.reclaimSettings(),
                new ConfigManager.SmartDispatcherSettings(SmartDispatcherMode.ENFORCE),
                base.healthSettings()));
    return new RuntimeDispatchService(
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy()),
        mock(RailGraphService.class),
        mock(RouteDefinitionCache.class),
        new RouteProgressRegistry(),
        mock(SignNodeRegistry.class),
        mock(LayoverRegistry.class),
        new DwellRegistry(),
        configManager,
        null,
        new TrainConfigResolver(),
        debug::add);
  }
}
