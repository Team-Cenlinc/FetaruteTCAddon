package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.testConfigView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.junit.jupiter.api.Test;

/**
 * 折返 guard 在运行时层的释放行为。
 *
 * <p>独立成类：{@code RuntimeDispatchServiceTest} 已贴着 SpotBugs 单类 1000 方法的上限，超过后整类被跳过分析，
 * 连带其它测试夹具报出一串"字段从未写入"的假警告。
 */
class RuntimeDispatchTurnbackGuardTest {

  /**
   * 2026-09-29 实服 NTA：节点事件链断了（漏掉中间节点）会把 guard 封存，此后 fail-retain 到车被销毁，旧站台挡住咽喉道岔。
   * 路线路径点按序到达且累计前进超过阈值时必须兜底释放。
   */
  @Test
  void sealedTurnbackGuardIsReleasedByRouteProgressFarBeyondTheRearClearance() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    OccupancyResource oldApproach = OccupancyResource.forNode(NodeId.of("OLD"));
    OccupancyRequest inbound =
        new OccupancyRequest(
            "turning-train", Optional.empty(), Instant.now(), List.of(oldApproach), Map.of());
    assertTrue(manager.acquire(inbound).allowed());

    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service = service(manager, debugMessages);
    Set<OccupancyResource> footprint = service.snapshotTurnbackFootprintResources("turning-train");
    assertEquals(Set.of(oldApproach), footprint);

    // 原子交接：旧进路降为物理 footprint，只能由 guard 证据释放
    NodeId terminal = NodeId.of("TERM");
    NodeId middle = NodeId.of("MID");
    NodeId clear = NodeId.of("CLEAR");
    NodeId far = NodeId.of("FAR");
    OccupancyResource outboundEdge = OccupancyResource.forEdge(EdgeId.undirected(terminal, middle));
    OccupancyRequest outbound =
        new OccupancyRequest(
            "turning-train", Optional.empty(), Instant.now(), List.of(outboundEdge), Map.of());
    assertTrue(manager.handoffAuthority(outbound).allowed());

    RuntimeTrainHandle train = mock(RuntimeTrainHandle.class);
    when(train.estimatedTrainLengthBlocks()).thenReturn(OptionalDouble.of(2.0));
    service.registerTurnbackFootprintGuard(
        train,
        "turning-train",
        terminal,
        footprint,
        List.of(
            new TurnbackFootprintGuardRegistry.ForwardPathEdge(terminal, middle, 10.0),
            new TurnbackFootprintGuardRegistry.ForwardPathEdge(middle, clear, 10.0),
            new TurnbackFootprintGuardRegistry.ForwardPathEdge(clear, far, 40.0)),
        1,
        new TurnbackFootprintGuardRegistry.RouteEvidence(
            "far-route", List.of(terminal, clear, far), 0));

    RouteDefinition farRoute =
        new RouteDefinition(
            RouteId.of("far-route"), List.of(terminal, clear, far), Optional.empty());
    // MID 的节点事件丢了：直接看到 CLEAR，节点链断开，guard 封存；此处累计 20 格，没到阈值 44 格
    service.observeTurnbackFootprintProgress("turning-train", clear, farRoute, 1);
    assertTrue(
        manager.snapshotClaims().stream().anyMatch(claim -> oldApproach.equals(claim.resource())),
        "封存后、未走够阈值前旧进路必须保留");

    service.observeTurnbackFootprintProgress("turning-train", far, farRoute, 2);
    assertFalse(
        manager.snapshotClaims().stream().anyMatch(claim -> oldApproach.equals(claim.resource())),
        "路线路径点按序到达且累计 60 格超过阈值后，封存的 guard 应当释放 " + debugMessages);
  }

  private static RuntimeDispatchService service(
      OccupancyManager occupancyManager, List<String> debugMessages) {
    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));
    return new RuntimeDispatchService(
        occupancyManager,
        mock(RailGraphService.class),
        mock(RouteDefinitionCache.class),
        new RouteProgressRegistry(),
        mock(SignNodeRegistry.class),
        mock(LayoverRegistry.class),
        new DwellRegistry(),
        configManager,
        null,
        new TrainConfigResolver(),
        debugMessages::add);
  }
}
