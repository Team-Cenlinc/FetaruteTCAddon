package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.testConfigView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.AuthorityHandoffSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.AuthorizationPurpose;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ClaimRole;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.DirectedTraversalContext;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceIntent;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.SignalComputationTrace;
import org.junit.jupiter.api.Test;

/**
 * 实时 blocker 快照的“进度锚点”判定。
 *
 * <p>异步占用结论只能写回产生它的那个进度窗口，所以写入前要判断请求是否已经过期。判断需要一个基线：请求建立那一刻列车在哪。 但 {@code OccupancyRequestBuilder}
 * 访问不到进度表，它构造的 {@code DirectedTraversalContext} 里 {@code lastPassedGraphNode}
 * 恒为空，历史实现因此退而拿请求的<b>窗口起点</b>去和进度表里的图节点比较。
 *
 * <p>窗口起点不是锚点。它由 {@code resolveEffectiveCurrentNodeForSignal} 决定：只有当 lastPassedGraphNode 落在
 * currentIndex -&gt; next 的最短路上时才采用它，否则退回 route 节点——列车停在咽喉、最短路查不到、或已经是最后一个 waypoint 时都会走后者。 于是“窗口起点
 * != 进度锚点”这件在请求建立时刻就已经成立、与列车有没有移动毫无关系的事，被读成了“列车已移动”。
 *
 * <p>后果不是判错一次，而是 blocker 证据整体缺席：{@code expectedSmartUnlockBlockersReleased} 只能报
 * BLOCKER_SNAPSHOT_MISSING。实服 129 次死锁处置里 92 次（71%）卡在这一步，列车清理与 unlock 恢复都拿不到可用证据。
 *
 * <p>写入侧放宽是安全的：快照消费侧另有一道 {@code blockerSnapshotProgressCurrent} 检查，它比对的是写入时从<b>进度表</b> 采下来的
 * progressWindow，真正过期的快照会在那里被移除。
 */
class LiveBlockerSnapshotProgressAnchorTest {

  private static final RouteDefinition ROUTE =
      new RouteDefinition(
          RouteId.of("anchor"),
          List.of(NodeId.of("A"), NodeId.of("B"), NodeId.of("C"), NodeId.of("D")),
          Optional.empty());

  @Test
  void missingProgressAnchorDoesNotCountAsMovement() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    // 列车实际停在两个 route waypoint 之间的中间图节点上；它不是 route 声明节点。
    RouteProgressRegistry registry = registryAt(2, NodeId.of("C-MID"));
    RuntimeDispatchService service = service(registry, debugMessages);
    OccupancyResource resource = OccupancyResource.forNode(NodeId.of("D"));
    // 窗口起点是 route 节点 C，锚点未绑定——正是 builder 构造出来的形态。
    OccupancyRequest request =
        unanchoredRequest(2, NodeId.of("C"), registry.version(), List.of(resource));

    updateLiveBlockerSnapshot(service, request, blockedDecision(resource));

    assertFalse(
        service.recentDeadlockBlockers("train-1", Duration.ofSeconds(30)).blockers().isEmpty(),
        "锚点缺失只表示无从判断，不得丢弃 blocker 证据: " + debugMessages);
    assertTrue(
        debugMessages.stream()
            .noneMatch(message -> message.contains("reason=STALE_PROGRESS_CONTEXT")),
        debugMessages.toString());
  }

  @Test
  void boundProgressAnchorStillDetectsMovement() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    RouteProgressRegistry registry = registryAt(2, NodeId.of("C-MID-2"));
    RuntimeDispatchService service = service(registry, debugMessages);
    OccupancyResource resource = OccupancyResource.forNode(NodeId.of("D"));
    // 绑定过锚点、而且锚点确实变了，才算真的过期。
    OccupancyRequest request =
        unanchoredRequest(2, NodeId.of("C"), registry.version(), List.of(resource))
            .withDirectedLastPassedGraphNode(Optional.of(NodeId.of("C-MID-1")));

    updateLiveBlockerSnapshot(service, request, blockedDecision(resource));

    assertTrue(
        service.recentDeadlockBlockers("train-1", Duration.ofSeconds(30)).blockers().isEmpty());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("reason=STALE_PROGRESS_CONTEXT")
                        && message.contains("requestLastPassed=C-MID-1")
                        && message.contains("currentLastPassed=C-MID-2")),
        debugMessages.toString());
  }

  @Test
  void routeIndexMovementIsStillDetectedWithoutAnAnchor() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    RouteProgressRegistry registry = registryAt(3, NodeId.of("D-MID"));
    RuntimeDispatchService service = service(registry, debugMessages);
    OccupancyResource resource = OccupancyResource.forNode(NodeId.of("D"));
    // 放宽的只有图节点这一维；route index 前进仍然必须判定为过期。
    OccupancyRequest request =
        unanchoredRequest(2, NodeId.of("C"), registry.version(), List.of(resource));

    updateLiveBlockerSnapshot(service, request, blockedDecision(resource));

    assertTrue(
        service.recentDeadlockBlockers("train-1", Duration.ofSeconds(30)).blockers().isEmpty(),
        debugMessages.toString());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("reason=STALE_PROGRESS_CONTEXT")
                        && message.contains("requestCurrentIndex=2")
                        && message.contains("currentIndex=3")),
        debugMessages.toString());
  }

  @Test
  void markDirectedRequestBindsCurrentProgressAnchor() throws Exception {
    RouteProgressRegistry registry = registryAt(1, NodeId.of("B-MID"));
    RuntimeDispatchService service = service(registry, new ArrayList<>());
    OccupancyRequest request =
        unanchoredRequest(
            1,
            NodeId.of("B"),
            registry.version(),
            List.of(OccupancyResource.forNode(NodeId.of("C"))));

    Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "markDirectedRequest", OccupancyRequest.class, SignalComputationTrace.Source.class);
    method.setAccessible(true);
    OccupancyRequest directed =
        (OccupancyRequest)
            method.invoke(service, request, SignalComputationTrace.Source.PERIODIC_TICK);

    assertEquals(
        Optional.of(NodeId.of("B-MID")),
        directed.directedContext().orElseThrow().lastPassedGraphNode(),
        "下发前必须绑定进度表里的真实锚点，否则上面那条比较永远拿不到基线");
  }

  // ---------------------------------------------------------------- fixtures

  private RouteProgressRegistry registryAt(int routeIndex, NodeId lastPassedGraphNode) {
    RuntimeDispatchTestFixtures.TagStore tags =
        new RuntimeDispatchTestFixtures.TagStore("train-1", "FTA_ROUTE_INDEX=" + routeIndex);
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags("train-1", tags.properties(), ROUTE);
    registry.updateLastPassedGraphNode("train-1", lastPassedGraphNode, Instant.now());
    return registry;
  }

  private RuntimeDispatchService service(
      RouteProgressRegistry progressRegistry, List<String> debugMessages) {
    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));
    OccupancyManager occupancyManager =
        mock(OccupancyManager.class, withSettings().extraInterfaces(AuthorityHandoffSupport.class));
    when(((AuthorityHandoffSupport) occupancyManager).holdsHardAuthority(any())).thenReturn(true);
    when(((AuthorityHandoffSupport) occupancyManager)
            .migrateAuthorityOwner(anyString(), anyString()))
        .thenReturn(true);
    return new RuntimeDispatchService(
        occupancyManager,
        mock(RailGraphService.class),
        mock(RouteDefinitionCache.class),
        progressRegistry,
        mock(SignNodeRegistry.class),
        mock(LayoverRegistry.class),
        new DwellRegistry(),
        configManager,
        null,
        new TrainConfigResolver(),
        debugMessages::add);
  }

  /** builder 刚构造出来的形态：有窗口起点，但尚未绑定进度锚点。 */
  private OccupancyRequest unanchoredRequest(
      int currentIndex,
      NodeId currentNode,
      long progressVersion,
      List<OccupancyResource> resources) {
    DirectedTraversalContext context =
        new DirectedTraversalContext(
            "train-1",
            Optional.of(ROUTE.id()),
            currentIndex,
            Optional.of(currentNode),
            Optional.empty(),
            Optional.of(currentNode),
            Optional.of(NodeId.of("D")),
            List.of(currentNode, NodeId.of("D")),
            List.of(),
            Map.of(),
            Map.of(),
            "EVENT",
            1L,
            progressVersion,
            "anchor-request",
            Optional.empty());
    Map<OccupancyResource, ResourceIntent> intents = new LinkedHashMap<>();
    for (OccupancyResource resource : resources) {
      intents.put(resource, ResourceIntent.MOVEMENT_REQUIRED);
    }
    return new OccupancyRequest(
        "train-1",
        Optional.of(ROUTE.id()),
        Instant.now(),
        resources,
        Map.of(),
        Map.of(),
        0,
        AuthorizationPurpose.RUNTIME_MOVE,
        Map.of(),
        intents,
        Optional.of(context));
  }

  private OccupancyDecision blockedDecision(OccupancyResource resource) {
    OccupancyClaim blocker =
        new OccupancyClaim(
            resource,
            "blocker",
            Optional.of(ROUTE.id()),
            Instant.now(),
            Duration.ZERO,
            Optional.empty(),
            ClaimRole.MOVEMENT_REQUIRED);
    return new OccupancyDecision(false, Instant.now(), SignalAspect.STOP, List.of(blocker));
  }

  private void updateLiveBlockerSnapshot(
      RuntimeDispatchService service, OccupancyRequest request, OccupancyDecision decision)
      throws Exception {
    Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "updateLiveBlockerSnapshot",
            String.class,
            OccupancyDecision.class,
            OccupancyRequest.class,
            Instant.class,
            String.class);
    method.setAccessible(true);
    method.invoke(service, "train-1", decision, request, Instant.now(), "canEnterPreview:blockers");
  }
}
