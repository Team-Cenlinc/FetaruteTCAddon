package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.testConfigView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingCoverage;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingState;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.FakeTrain;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.TagStore;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.junit.jupiter.api.Test;

/**
 * 折返复用改名后，启动水合 marker 跟着占用账本的属主一起迁到新名。
 *
 * <p>强制启动恢复回到 READY 之后，信号 tick 按当前车名查"该物理编组的足迹已水合"的 marker。复用链路逐个迁移各注册表与授权属主， 漏掉这一张的话，新名查不到
 * marker，同一辆车被当成迟加载的陌生实体：硬停、重新水合，硬停会清掉复用刚挂上的发车动作。
 *
 * <p>marker 只认物理 identity：换一个编组顶着新名出现，仍按重复属主隔离。
 *
 * <p>独立成类：{@code RuntimeDispatchServiceTest} 已贴着 SpotBugs 单类 1000 方法的上限。
 */
class LayoverReuseHydrationOwnerTest {

  private static final String PREVIOUS = "turning-train";

  private static final NodeId APPROACH = NodeId.of("SURC:TERM:APPROACH:1");
  private static final NodeId TERMINAL = NodeId.of("SURC:S:TERM:1");
  private static final NodeId THROAT = NodeId.of("SURC:S:TERM:1:001");
  private static final NodeId CLEAR = NodeId.of("SURC:TERM:NEXT:1:001");

  /** 一次复用现场：READY 之后、以旧名水合过的终点待命车。 */
  private static final class Scene {
    final List<String> debug = new ArrayList<>();
    final UUID worldId = UUID.randomUUID();
    final UUID arrivalRouteId = UUID.randomUUID();
    final UUID ticketRouteId = UUID.randomUUID();
    final AtomicReference<String> trainCartsName = new AtomicReference<>(PREVIOUS);
    final LayoverRegistry layoverRegistry = new LayoverRegistry();
    final SimpleOccupancyManager occupancy;
    final RuntimeDispatchService service;
    final TagStore tags;
    final FakeTrain train;

    Scene(boolean failHardAuthorityVerificationAfterRename) {
      RailGraph graph = graph(worldId);
      SimpleOccupancyManager real =
          new SimpleOccupancyManager(
              (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
      occupancy = failHardAuthorityVerificationAfterRename ? spy(real) : real;
      if (failHardAuthorityVerificationAfterRename) {
        // 只让改名之后的那次硬授权校验失败，触发复用链路的整体回滚。
        doAnswer(
                inv -> {
                  OccupancyRequest request = inv.getArgument(0);
                  return PREVIOUS.equals(request.trainName()) && (boolean) inv.callRealMethod();
                })
            .when(occupancy)
            .holdsHardAuthority(any());
      }

      ConfigManager configManager = mock(ConfigManager.class);
      when(configManager.current()).thenReturn(testConfigView(1, 20.0));
      RailGraphService railGraphService = mock(RailGraphService.class);
      when(railGraphService.getSnapshot(worldId))
          .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
      when(railGraphService.effectiveSpeedLimitBlocksPerSecond(
              any(), any(), any(), anyDouble(), anyDouble()))
          .thenReturn(20.0);
      RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
      RouteDefinition arrival =
          new RouteDefinition(RouteId.of("arrival"), List.of(APPROACH, TERMINAL), Optional.empty());
      RouteDefinition turnback =
          new RouteDefinition(
              RouteId.of("turnback"), List.of(TERMINAL, THROAT, CLEAR), Optional.empty());
      when(routeDefinitions.findById(arrivalRouteId)).thenReturn(Optional.of(arrival));
      when(routeDefinitions.findById(ticketRouteId)).thenReturn(Optional.of(turnback));

      service =
          new RuntimeDispatchService(
              occupancy,
              railGraphService,
              routeDefinitions,
              new RouteProgressRegistry(),
              mock(SignNodeRegistry.class),
              layoverRegistry,
              new DwellRegistry(),
              configManager,
              null,
              new TrainConfigResolver(),
              debug::add);
      tags = renamableTags(PREVIOUS, trainCartsName, arrivalRouteId);
      train = new FakeTrain(worldId, tags.properties(), false);
    }

    /** 回到"强制启动恢复且已 READY"的常态，旧名的足迹与 marker 在账本里。 */
    void hydrateAtTerminal() {
      assertTrue(
          service.rebuildOccupancySnapshot(List.of(train)),
          "启动水合没有完成：\n  " + String.join("\n  ", debug));
      layoverRegistry.register(
          PREVIOUS,
          TerminalKeyResolver.toTerminalKey(TERMINAL),
          TERMINAL,
          Instant.now().minusSeconds(1),
          Map.of());
    }

    LayoverDispatchResult reuse() {
      LayoverRegistry.LayoverCandidate candidate = layoverRegistry.get(PREVIOUS).orElseThrow();
      ServiceTicket ticket =
          new ServiceTicket(
              "ticket-hydration",
              Instant.now(),
              ticketRouteId,
              candidate.terminalKey(),
              10,
              ServiceTicket.TicketMode.OPERATION);
      return service.dispatchLayover(candidate, ticket, tags.properties(), train);
    }

    List<String> signalTick(FakeTrain target) {
      debug.clear();
      service.handleSignalTick(target, false);
      return List.copyOf(debug);
    }
  }

  /** TagStore 的车名是写死的；复用要靠 setTrainName 真正改名，信号 tick 才会按新名查 marker。 */
  private static TagStore renamableTags(
      String initialName, AtomicReference<String> name, UUID routeId) {
    TagStore tags = new TagStore(initialName, "FTA_ROUTE_ID=" + routeId, "FTA_ROUTE_INDEX=1");
    when(tags.properties().getTrainName()).thenAnswer(inv -> name.get());
    doAnswer(
            inv -> {
              name.set(inv.getArgument(0));
              return null;
            })
        .when(tags.properties())
        .setTrainName(anyString());
    return tags;
  }

  /** 与生产同构的最小现场：进站线、终点、咽喉、出清点，带完整联锁目录（缺目录会让启动水合直接失败）。 */
  private static RailGraph graph(UUID worldId) {
    RailEdge approach = edge(APPROACH, TERMINAL);
    RailEdge throat = edge(TERMINAL, THROAT);
    RailEdge clear = edge(THROAT, CLEAR);
    Map<NodeId, RailNode> nodes =
        Map.of(
            APPROACH, new RailNodeTest(APPROACH),
            TERMINAL, new RailNodeTest(TERMINAL),
            THROAT, new RailNodeTest(THROAT),
            CLEAR, new RailNodeTest(CLEAR));
    Map<EdgeId, RailEdge> edges =
        Map.of(approach.id(), approach, throat.id(), throat, clear.id(), clear);
    return new SimpleRailGraph(
        nodes,
        edges,
        Set.of(),
        RailInterlockingState.fromSnapshot(
            worldId,
            edges.keySet(),
            new RailInterlockingCoverage(edges.size(), edges.size(), true),
            Map.of()));
  }

  private static RailEdge edge(NodeId from, NodeId to) {
    return new RailEdge(EdgeId.undirected(from, to), from, to, 10, -1.0, true, Optional.empty());
  }

  private static boolean quarantined(List<String> lines) {
    return lines.stream()
        .anyMatch(
            line -> line.contains("SMART_SAFETY_STATE_UNAVAILABLE") && line.contains("quarantine"));
  }

  @Test
  void reusedTrainIsNotQuarantinedAsLateLoadedAfterRename() {
    Scene scene = new Scene(false);
    scene.hydrateAtTerminal();

    LayoverDispatchResult result = scene.reuse();
    assertTrue(result.dispatched(), result.reason());
    String renamed = result.trainName().orElseThrow();
    assertNotEquals(PREVIOUS, renamed);
    assertEquals(renamed, scene.trainCartsName.get());
    int hardStopsBefore = scene.train.hardStopCalls;

    List<String> tick = scene.signalTick(scene.train);

    assertFalse(quarantined(tick), "复用改名后的同一辆车被当成迟加载实体隔离：\n  " + String.join("\n  ", tick));
    assertFalse(
        tick.stream().anyMatch(line -> line.contains("SMART_LATE_LOAD_PHYSICAL_HYDRATION")),
        "复用改名后的同一辆车被重新水合：\n  " + String.join("\n  ", tick));
    assertEquals(hardStopsBefore, scene.train.hardStopCalls, "复用刚挂上的发车动作被硬停清掉");
  }

  @Test
  void anotherPhysicalTrainCarryingTheNewNameIsStillQuarantined() {
    Scene scene = new Scene(false);
    scene.hydrateAtTerminal();
    LayoverDispatchResult result = scene.reuse();
    assertTrue(result.dispatched(), result.reason());
    String renamed = result.trainName().orElseThrow();

    TagStore impostorTags = new TagStore(renamed, scene.tags.tags.toArray(new String[0]));
    FakeTrain impostor = new FakeTrain(scene.worldId, impostorTags.properties(), false);
    List<String> tick = scene.signalTick(impostor);

    assertTrue(
        tick.stream()
            .anyMatch(
                line -> line.contains("SMART_DUPLICATE_LOGICAL_OWNER_IDENTITY train=" + renamed)),
        "新名下的 marker 应只属于原物理编组，冒名编组必须按重复属主隔离：\n  " + String.join("\n  ", tick));
  }

  @Test
  void rolledBackReuseKeepsTheMarkerUnderThePreviousName() {
    Scene scene = new Scene(true);
    scene.hydrateAtTerminal();

    LayoverDispatchResult result = scene.reuse();
    assertFalse(result.dispatched(), "硬授权校验应失败并回滚");
    assertEquals("hard-authority-verification-failed", result.reason());
    assertEquals(PREVIOUS, scene.trainCartsName.get(), "回滚应恢复 TrainCarts 车名");

    List<String> tick = scene.signalTick(scene.train);

    assertFalse(quarantined(tick), "回滚到旧名后 marker 没有跟着迁回：\n  " + String.join("\n  ", tick));
  }
}
