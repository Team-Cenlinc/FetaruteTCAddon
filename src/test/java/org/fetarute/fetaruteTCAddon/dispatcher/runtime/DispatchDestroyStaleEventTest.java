package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.testConfigView;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.FakeTrain;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.TagStore;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.junit.jupiter.api.Test;

/**
 * 调度销毁之后到达的滞后事件，不得关闭全局授权门。
 *
 * <h3>实服现场（2026-09-13 13:18:27）</h3>
 *
 * <pre>
 *   调度销毁: reason=DSTY train=SURC-WS-LH-7510
 *   触发节点 SURC:D:LWN:1 train=SURC-WS-LH-7510 action=MEMBER_ENTER   ← 已销毁的车又来一个到站事件
 *   SMART_SAFETY_STATE_UNAVAILABLE reason=late-load-quarantine-progress-trigger
 *   SMART_SAFETY_STATE_UNAVAILABLE reason=startup-route-evidence-missing
 *   SMART_STARTUP_OCCUPANCY_RECONSTRUCTION state=STOP_FIRST epoch=3
 *   SMART_STARTUP_OCCUPANCY_RECONSTRUCTION state=STOP_FIRST epoch=4
 *   占用事件信号重评估桥已停止
 * </pre>
 *
 * <p>此后全局重建再未回到 READY，剩下 8 分钟里每辆车每个 tick 都以 {@code startup-occupancy-stop_first-signal-tick}
 * fail-closed，MT/WS/DS 三条线一起冻住。
 *
 * <h3>成因</h3>
 *
 * <p>{@code handleDestroy} 立刻清掉进度、运行时缓存与 route tag，但 {@code train.destroy()} 的物理销毁<b>延迟 1
 * tick</b>——这是有意的，同步释放占用会让 SpawnMonitor 在物理销毁前 acquire 并 spawn 新车导致撞车。于是存在一个窗口： 实体还活着、还能触发事件，而它的
 * route 证据已经被我们自己抹掉了。迟加载隔离看到这样一辆"没有任何证据的陌生列车"， 判定现场异常并关闭<b>全局</b>授权门。
 *
 * <p>修复只做一件事：认出"这是我们自己刚销毁的那辆车"，丢弃它的滞后事件。对真正陌生的实体，隔离行为完全不变—— 由 {@code
 * unresolvedLateLoadedTrainClosesGlobalGateAndRequestsRecovery} 继续守住。
 */
class DispatchDestroyStaleEventTest {

  private static final NodeId A = NodeId.of("A");

  private static final NodeId B = NodeId.of("B");

  private RuntimeDispatchService service(
      UUID worldId, RouteDefinition route, SimpleOccupancyManager occupancy, List<String> debug) {
    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));
    RailGraph graph = startupPhysicalGraph(worldId);
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
    return new RuntimeDispatchService(
        occupancy,
        railGraphService,
        routeDefinitions,
        new RouteProgressRegistry(),
        mock(SignNodeRegistry.class),
        mock(LayoverRegistry.class),
        new DwellRegistry(),
        configManager,
        null,
        new TrainConfigResolver(),
        debug::add);
  }

  /** 与生产同构的最小启动现场：单边 + 完整联锁目录（缺目录会让启动水合直接失败）。 */
  private static RailGraph startupPhysicalGraph(UUID worldId) {
    org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge edge =
        new org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge(
            org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId.undirected(A, B),
            A,
            B,
            10,
            -1.0,
            true,
            Optional.empty());
    java.util.Map<NodeId, org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> nodes =
        java.util.Map.of(A, new RailNodeTest(A), B, new RailNodeTest(B));
    java.util.Map<
            org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId,
            org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge>
        edges = java.util.Map.of(edge.id(), edge);
    return new org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph(
        nodes,
        edges,
        java.util.Set.of(),
        org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingState
            .fromSnapshot(
                worldId,
                edges.keySet(),
                new org.fetarute
                    .fetaruteTCAddon
                    .dispatcher
                    .graph
                    .interlocking
                    .RailInterlockingCoverage(1, 1, true),
                java.util.Map.of()));
  }

  private FakeTrain train(UUID worldId, String name) {
    return new FakeTrain(
        worldId,
        new TagStore(
                name,
                "FTA_OPERATOR_CODE=op",
                "FTA_LINE_CODE=l1",
                "FTA_ROUTE_CODE=r1",
                "FTA_ROUTE_INDEX=0")
            .properties(),
        false);
  }

  private void dispatchDestroy(RuntimeDispatchService service, FakeTrain train, String name)
      throws Exception {
    Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "handleDestroy",
            RuntimeTrainHandle.class,
            TrainProperties.class,
            String.class,
            String.class);
    method.setAccessible(true);
    method.invoke(service, train, train.properties(), name, "DSTY");
  }

  private static boolean closedGlobalGate(List<String> debug) {
    return debug.stream()
        .anyMatch(line -> line.contains("SMART_STARTUP_OCCUPANCY_RECONSTRUCTION state=STOP_FIRST"));
  }

  @Test
  void staleEventFromDispatchDestroyedTrainDoesNotCloseGlobalGate() throws Exception {
    UUID worldId = UUID.randomUUID();
    RouteDefinition route = new RouteDefinition(RouteId.of("r"), List.of(A, B), Optional.empty());
    List<String> debug = new ArrayList<>();
    SimpleOccupancyManager occupancy =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    RuntimeDispatchService service = service(worldId, route, occupancy, debug);
    FakeTrain doomed = train(worldId, "doomed");
    // 进入"已强制启动恢复且已回到 READY"的常态：这正是实服出事时的状态。
    assertTrue(service.rebuildOccupancySnapshot(List.of(doomed)));

    dispatchDestroy(service, doomed, "doomed");
    debug.clear();
    // 物理实体还没消失，这一 tick 仍会把事件送进来。
    service.handleSignalTick(doomed, false);

    assertFalse(closedGlobalGate(debug), "已销毁列车的滞后事件关闭了全局授权门：\n  " + String.join("\n  ", debug));
    assertTrue(
        debug.stream()
            .anyMatch(line -> line.contains("SMART_DISPATCH_DESTROY_STALE_EVENT_IGNORED")),
        "滞后事件没有被识别为\"自己刚销毁的列车\"：\n  " + String.join("\n  ", debug));
  }

  @Test
  void identityIsForgottenOnceTheEntityActuallyDisappears() throws Exception {
    UUID worldId = UUID.randomUUID();
    RouteDefinition route = new RouteDefinition(RouteId.of("r"), List.of(A, B), Optional.empty());
    List<String> debug = new ArrayList<>();
    SimpleOccupancyManager occupancy =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    RuntimeDispatchService service = service(worldId, route, occupancy, debug);
    FakeTrain doomed = train(worldId, "doomed");
    assertTrue(service.rebuildOccupancySnapshot(List.of(doomed)));

    dispatchDestroy(service, doomed, "doomed");
    service.handleTrainRemoved(doomed);
    debug.clear();
    service.handleSignalTick(doomed, false);

    // 豁免必须是一次性的：实体已确认消失之后再出现同一身份，就不再是"刚销毁的滞后事件"。
    assertFalse(
        debug.stream()
            .anyMatch(line -> line.contains("SMART_DISPATCH_DESTROY_STALE_EVENT_IGNORED")),
        "实体已确认移除后仍在豁免该身份：\n  " + String.join("\n  ", debug));
  }
}
