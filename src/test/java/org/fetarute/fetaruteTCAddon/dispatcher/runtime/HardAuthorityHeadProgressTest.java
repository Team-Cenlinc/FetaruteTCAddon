package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.sectionlessGraph;
import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.testConfigView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.bergerkiller.bukkit.tc.controller.components.RailState;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.FakeTrain;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.TagStore;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.AuthorityHandoffSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ClaimRole;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceIntent;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.junit.jupiter.api.Test;

/**
 * 硬授权窗口要覆盖“预计停车点 + 安全余量”，从车头量起；停着时也一样。
 *
 * <p>夹具：一条 300 格的长边 A→B，其后 15 格是道岔 S，道岔后 C、D、E。车头已驶过 A 290 格时，前方只剩 10 格，窗口必须伸过 B、碰到 S，拿不到就停车； 车头刚离开
 * A 时窗口到 B 足够，照常放行。实服 2026-09-27：回库 MT 从 MLU:2:001 起的 47 格窗口一路“够长”，车头越过 MLU:2:002 才被拒， 冲出 17
 * 格停在渡线道岔尖轨上。停着时若只要一条边，被挡停下的车下一拍就按一条边放行、起步后又被挡——“停下又放行”与起步闪烁都出在这里。
 */
class HardAuthorityHeadProgressTest {

  private static final String TRAIN = "SURC-MT-LO-6900";
  private static final NodeId A = NodeId.of("SURC:OFL:MLU:2:001");
  private static final NodeId B = NodeId.of("SURC:OFL:MLU:2:002");
  private static final NodeId S = NodeId.of("SWITCHER:Towny:-520:77:2212");
  private static final NodeId C = NodeId.of("SURC:OFL:MLU:1:003");
  private static final NodeId D = NodeId.of("SURC:OFL:MLU:1:004");
  private static final NodeId E = NodeId.of("SURC:S:TERM:1");

  /** 车头只剩 10 格：窗口伸过 B 碰到被占的道岔，当拍停车。 */
  @Test
  void movingTrainNearTheWindowEndExtendsIntoTheSwitchAndStops() {
    TickResult result = signalTick(A, true, 290.0, S);

    assertTrue(result.requestedBlocked(), "车头离窗口终点只剩 10 格，窗口必须伸到道岔");
    assertEquals(SignalAspect.STOP, result.aspect());
  }

  /** 车头刚离开 A：到 B 还有 290 格，窗口不必伸过 B，照常放行。 */
  @Test
  void movingTrainFarFromTheWindowEndKeepsTheShortWindow() {
    TickResult result = signalTick(A, true, 10.0, S);

    assertFalse(result.requestedBlocked());
    assertNotEquals(SignalAspect.STOP, result.aspect());
  }

  /** 停着、离被占的道岔只剩 10 格：停着也要“车头 + 余量”，窗口伸到道岔被拒，不起步——不会起步后下一拍又被挡。 */
  @Test
  void stationaryTrainNearABlockerDoesNotStart() {
    TickResult result = signalTick(A, false, 290.0, S);

    assertTrue(result.requestedBlocked(), "停着也要覆盖安全余量");
    assertEquals(SignalAspect.STOP, result.aspect());
  }

  /** 停着、离被占的道岔还远：余量够，照常起步。 */
  @Test
  void stationaryTrainFarFromABlockerStarts() {
    TickResult result = signalTick(A, false, 10.0, S);

    assertFalse(result.requestedBlocked());
    assertNotEquals(SignalAspect.STOP, result.aspect());
  }

  /**
   * 停在道岔上的车保持单边窗口：道岔出清要能先动，不因余量里远处的占用而原地不动。
   *
   * <p>路线要比余量长（S→E 三条边），否则窗口盖满整份计划、先走“退回单边”的另一条例外，测不到这一条。
   */
  @Test
  void trainStandingOnASwitchKeepsTheOneEdgeWindow() {
    TickResult result = signalTick(S, false, 316.0, D);

    assertFalse(result.requestedBlocked(), "单边窗口只到 C，不该去碰 D");
    assertNotEquals(SignalAspect.STOP, result.aspect());
  }

  /** 本拍的硬授权请求有没有伸到被占的节点，以及最终发布的信号。 */
  private record TickResult(boolean requestedBlocked, SignalAspect aspect) {}

  /**
   * 从 {@code from} 出发跑一拍信号 tick，{@code blocked} 节点已被别的车占住。
   *
   * @param headX 车头 x 坐标（节点坐标沿 x 轴与边长一致）
   */
  private static TickResult signalTick(NodeId from, boolean moving, double headX, NodeId blocked) {
    UUID worldId = UUID.randomUUID();
    RouteDefinition route =
        new RouteDefinition(RouteId.of("MT-1O_ShortD"), List.of(from, E), Optional.empty());
    TagStore tags =
        new TagStore(
            TRAIN,
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    sectionlessGraph(longEdgeBeforeSwitch()), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(
            any(), any(), any(), anyDouble(), anyDouble()))
        .thenReturn(1000.0);
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    OccupancyManager occupancyManager =
        mock(OccupancyManager.class, withSettings().extraInterfaces(AuthorityHandoffSupport.class));
    when(((AuthorityHandoffSupport) occupancyManager).holdsHardAuthority(any())).thenReturn(true);
    when(((AuthorityHandoffSupport) occupancyManager)
            .migrateAuthorityOwner(anyString(), anyString()))
        .thenReturn(true);
    when(occupancyManager.snapshotClaims()).thenReturn(List.of());
    OccupancyResource blockedNode = OccupancyResource.forNode(blocked);
    OccupancyClaim occupant =
        new OccupancyClaim(
            blockedNode,
            "SURC-DS-LH-0368",
            Optional.empty(),
            Instant.now(),
            Duration.ZERO,
            Optional.empty(),
            ClaimRole.MOVEMENT_REQUIRED);
    List<OccupancyRequest> hardRequests = new ArrayList<>();
    when(occupancyManager.canEnter(any()))
        .thenAnswer(
            invocation -> {
              OccupancyRequest request = invocation.getArgument(0);
              if (request.resourceList().stream()
                  .anyMatch(
                      resource ->
                          request.intentFor(resource) == ResourceIntent.MOVEMENT_REQUIRED)) {
                hardRequests.add(request);
              }
              return request.resourceList().contains(blockedNode)
                  ? new OccupancyDecision(
                      false, request.now(), SignalAspect.STOP, List.of(occupant))
                  : new OccupancyDecision(true, request.now(), SignalAspect.PROCEED, List.of());
            });
    when(occupancyManager.acquire(any()))
        .thenAnswer(
            invocation -> {
              OccupancyRequest request = invocation.getArgument(0);
              return new OccupancyDecision(true, request.now(), SignalAspect.PROCEED, List.of());
            });
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags(TRAIN, tags.properties(), route);
    RuntimeDispatchService service =
        new RuntimeDispatchService(
            occupancyManager,
            railGraphService,
            routeDefinitions,
            registry,
            mock(SignNodeRegistry.class),
            mock(LayoverRegistry.class),
            new DwellRegistry(),
            configManager,
            null,
            new TrainConfigResolver(),
            message -> {});
    FakeTrain train = new FakeTrain(worldId, tags.properties(), moving, moving ? 0.2 : 0.0);
    RailState head = mock(RailState.class);
    when(head.positionLocation()).thenReturn(new Location(null, headX, 64.0, 0.0));
    train.railState = Optional.of(head);

    service.handleSignalTick(train, false);

    boolean requestedBlocked =
        hardRequests.stream().anyMatch(request -> request.resourceList().contains(blockedNode));
    return new TickResult(requestedBlocked, registry.get(TRAIN).orElseThrow().lastSignal());
  }

  /** A —300— B —15— S —10— C —100— D —100— E，节点坐标沿 x 轴与边长一致，供车头位置插值。 */
  private static SimpleRailGraph longEdgeBeforeSwitch() {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    nodes.put(A, node(A, NodeType.WAYPOINT, 0.0));
    nodes.put(B, node(B, NodeType.WAYPOINT, 300.0));
    nodes.put(S, node(S, NodeType.SWITCHER, 315.0));
    nodes.put(C, node(C, NodeType.WAYPOINT, 325.0));
    nodes.put(D, node(D, NodeType.WAYPOINT, 425.0));
    nodes.put(E, node(E, NodeType.STATION, 525.0));
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    edge(edges, A, B, 300);
    edge(edges, B, S, 15);
    edge(edges, S, C, 10);
    edge(edges, C, D, 100);
    edge(edges, D, E, 100);
    return new SimpleRailGraph(nodes, edges, java.util.Set.of());
  }

  private static RailNode node(NodeId id, NodeType type, double x) {
    return new SignRailNode(id, type, new Vector(x, 64.0, 0.0), Optional.empty(), Optional.empty());
  }

  private static void edge(Map<EdgeId, RailEdge> edges, NodeId from, NodeId to, int lengthBlocks) {
    EdgeId id = EdgeId.undirected(from, to);
    edges.put(id, new RailEdge(id, from, to, lengthBlocks, -1.0, true, Optional.empty()));
  }
}
