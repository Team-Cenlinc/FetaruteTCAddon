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
