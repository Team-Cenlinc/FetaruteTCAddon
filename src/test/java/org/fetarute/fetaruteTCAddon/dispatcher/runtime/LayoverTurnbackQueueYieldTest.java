package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.testConfigView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.FakeTrain;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.TagStore;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ClaimRole;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.junit.jupiter.api.Test;

/**
 * 待命折返车割等待环上的排队边。
 *
 * <p>形态：待命车 A 停在尽头式终点，身后的进站咽喉还占着；后车 B 去另一个站台，先在出站要过的道岔上排了队，却被 A 身后的占用挡在站外。A 折返出站要过那个道岔，排在 B 后面。
 * 健康层不巡检待命车，割排队边只从被排队位挡住的一方出发，于是这个环没人割。A 交接被挡时自己证实成环、割掉 B 的排队位，当场再交接。
 *
 * <p>独立成类：{@code RuntimeDispatchServiceTest} 已贴着 SpotBugs 单类 1000 方法的上限。
 */
class LayoverTurnbackQueueYieldTest {

  private static final String TURNING = "turning-train";
  private static final String FOLLOWER = "follower-train";
  private static final NodeId APPROACH = NodeId.of("SURC:TERM:APPROACH:1");
  private static final NodeId TERMINAL = NodeId.of("SURC:S:TERM:1");
  private static final NodeId THROAT = NodeId.of("SWITCHER:TEST:TERM-THROAT");
  private static final NodeId CLEAR = NodeId.of("SURC:TERM:NEXT:1:001");
  private static final OccupancyResource THROAT_SWITCH =
      OccupancyResource.forConflict("switcher:" + THROAT.value());
  private static final OccupancyResource REAR_GUARD = OccupancyResource.forNode(APPROACH);
  private static final int FOLLOWER_PRIORITY = 50;

  /** 成环：B 被 A 身后的占用挡着 —— 割掉 B 的排队位，A 当场交接成功。 */
  @Test
  void aProvenCycleIsCutAndTheTurnbackGoesAtOnce() {
    Scene scene = new Scene(true);

    LayoverDispatchResult result = scene.dispatch();

    assertTrue(
        result.dispatched(), () -> result.reason() + "\n  " + String.join("\n  ", scene.debug));
    assertFalse(scene.followerQueued(), "B 的排队位已割掉");
    assertTrue(
        scene.debug.stream().anyMatch(line -> line.startsWith("SMART_QUEUE_POSITION_YIELDED ")),
        () -> String.join("\n  ", scene.debug));
  }

  /** 不成环：B 被别的车挡着，它排队是正当的 —— 不割，A 照旧被挡。 */
  @Test
  void aLegitimateQueueIsLeftAlone() {
    Scene scene = new Scene(false);

    LayoverDispatchResult result = scene.dispatch();

    assertFalse(result.dispatched());
    assertEquals("authority-handoff-blocked", result.reason());
    assertTrue(scene.followerQueued(), "B 的排队位不得被割");
  }

  /** 一次折返现场：A 停在 TERM 待命、占着身后的进站节点；B 在折返要过的道岔上排队。 */
  private static final class Scene {
    final List<String> debug = new ArrayList<>();
    final UUID worldId = UUID.randomUUID();
    final UUID ticketRouteId = UUID.randomUUID();
    final SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    final LayoverRegistry layoverRegistry = new LayoverRegistry();
    final RuntimeDispatchService service;
    final TagStore tags = new TagStore(TURNING);
    final FakeTrain train;

    /**
     * @param followerBlockedByTurning B 是否正被 A 身后的占用挡着（成环）；否则挡它的是别的车
     */
    Scene(boolean followerBlockedByTurning) {
      RailEdge approach = edge(APPROACH, TERMINAL);
      RailEdge throat = edge(TERMINAL, THROAT);
      RailEdge clear = edge(THROAT, CLEAR);
      RailGraph graph =
          new SimpleRailGraph(
              Map.of(
                  APPROACH, new RailNodeTest(APPROACH),
                  TERMINAL, new RailNodeTest(TERMINAL),
                  THROAT, new RailNodeTest(THROAT, NodeType.SWITCHER, Optional.empty()),
                  CLEAR, new RailNodeTest(CLEAR)),
              Map.of(approach.id(), approach, throat.id(), throat, clear.id(), clear),
              Set.of());
      RouteDefinition turnback =
          new RouteDefinition(
              RouteId.of("turnback"), List.of(TERMINAL, THROAT, CLEAR), Optional.empty());

      // A 占着站台与身后的进站节点
      assertTrue(
          manager
              .acquire(
                  new OccupancyRequest(
                      TURNING,
                      Optional.empty(),
                      Instant.now(),
                      List.of(
                          REAR_GUARD,
                          OccupancyResource.forEdge(approach.id()),
                          OccupancyResource.forNode(TERMINAL)),
                      Map.of()))
              .allowed());
      // B 在道岔上排队：先有人占着、B 进队列，占着的车随后开走，B 的排队位留着
      assertTrue(
          manager
              .acquire(
                  new OccupancyRequest(
                      "passing-train",
                      Optional.empty(),
                      Instant.now(),
                      List.of(THROAT_SWITCH),
                      Map.of()))
              .allowed());
      // B 的排队优先级高于折返票：只靠仲裁先后，A 排不到它前面
      assertFalse(
          manager
              .acquire(
                  new OccupancyRequest(
                      FOLLOWER,
                      Optional.empty(),
                      Instant.now(),
                      List.of(THROAT_SWITCH),
                      Map.of(),
                      FOLLOWER_PRIORITY))
              .allowed());
      manager.releaseByTrain("passing-train");
      assertTrue(followerQueued(), "前提：B 在道岔上排着队");

      ConfigManager configManager = mock(ConfigManager.class);
      when(configManager.current()).thenReturn(testConfigView(1, 20.0));
      RailGraphService railGraphService = mock(RailGraphService.class);
      when(railGraphService.getSnapshot(worldId))
          .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
      when(railGraphService.effectiveSpeedLimitBlocksPerSecond(
              any(), any(), any(), anyDouble(), anyDouble()))
          .thenReturn(20.0);
      RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
      when(routeDefinitions.findById(ticketRouteId)).thenReturn(Optional.of(turnback));
      when(routeDefinitions.listStops(turnback.id())).thenReturn(List.of());
      layoverRegistry.register(
          TURNING,
          TerminalKeyResolver.toTerminalKey(TERMINAL),
          TERMINAL,
          Instant.now().minusSeconds(1),
          Map.of());
      service =
          new RuntimeDispatchService(
              manager,
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
      train = new FakeTrain(worldId, tags.properties(), false);
      // B 此刻被谁挡着（它自己的信号 tick 记下的）
      service.updateBlockerSnapshot(
          FOLLOWER,
          List.of(
              followerBlockedByTurning
                  ? claim(TURNING, REAR_GUARD, ClaimRole.PROTECTIVE_RETAIN)
                  : claim(
                      "another-train",
                      OccupancyResource.forNode(NodeId.of("ELSEWHERE")),
                      ClaimRole.MOVEMENT_REQUIRED)),
          null,
          Instant.now(),
          "test");
    }

    LayoverDispatchResult dispatch() {
      LayoverRegistry.LayoverCandidate candidate = layoverRegistry.get(TURNING).orElseThrow();
      ServiceTicket ticket =
          new ServiceTicket(
              "ticket-turnback",
              Instant.now(),
              ticketRouteId,
              candidate.terminalKey(),
              10,
              ServiceTicket.TicketMode.OPERATION);
      return service.dispatchLayover(candidate, ticket, tags.properties(), train);
    }

    boolean followerQueued() {
      return manager.snapshotQueues().stream()
          .flatMap(queue -> queue.entries().stream())
          .anyMatch(entry -> FOLLOWER.equals(entry.trainName()));
    }
  }

  private static OccupancyClaim claim(String train, OccupancyResource resource, ClaimRole role) {
    return new OccupancyClaim(
        resource, train, Optional.empty(), Instant.now(), Duration.ZERO, Optional.empty(), role);
  }

  private static RailEdge edge(NodeId from, NodeId to) {
    return new RailEdge(EdgeId.undirected(from, to), from, to, 10, -1.0, true, Optional.empty());
  }
}
