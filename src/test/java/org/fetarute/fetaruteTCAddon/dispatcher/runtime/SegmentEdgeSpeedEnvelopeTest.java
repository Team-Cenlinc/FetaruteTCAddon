package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.sectionlessGraph;
import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.testConfigView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.bergerkiller.bukkit.tc.controller.components.RailState;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.SpeedCeiling;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.SpeedCurve;
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
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfig;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.AuthorityHandoffSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.junit.jupiter.api.Test;

/**
 * 线路只写车站时，站间的速度基准是本段路径的限速包络，不是整段最小限速。
 *
 * <p>夹具仿 2026-09-27 实服 WS LWN:2→SWN:2：站台 A —26— 道岔 SW —48（默认 8 格/秒）— X —600— 下一站 B，其余 22.2。
 * 旧规则取整段最小 8，列车全段 8 格/秒、每趟比表慢 51 秒；编表与 ETA 按逐边限速算。
 */
class SegmentEdgeSpeedEnvelopeTest {

  private static final String TRAIN = "SURC-WS-LN-4589";
  private static final NodeId A = NodeId.of("SURC:S:LWN:2");
  private static final NodeId SW = NodeId.of("SWITCHER:Towny:887:80:1175");
  private static final NodeId X = NodeId.of("SURC:LWN:SWN:2:001");
  private static final NodeId B = NodeId.of("SURC:S:SWN:2");
  private static final double SLOW = 8.0;
  private static final double FAST = 22.2;

  /** 刚出站：基准是刹得住前方 26 格外那条 8 格/秒道岔边的速度。 */
  @Test
  void departingTrainBrakesForTheSlowSwitchAheadInsteadOfCrawlingTheWholeSegment() {
    Tick tick = signalTick(Optional.empty(), 0.0);

    assertEquals(
        SpeedCeiling.brakingLimitBps(tick.curve(), FAST, SLOW, 26), tick.edgeLimitBps(), 1e-6);
    assertTrue(tick.edgeLimitBps() > SLOW);
  }

  /** 车头已驶过站台节点 20 格：离道岔只剩 6 格，基准随之收紧，而不是等过了下一个节点才收。 */
  @Test
  void theSlowEdgeIsMeasuredFromTheHead() {
    Tick tick = signalTick(Optional.empty(), 20.0);

    assertEquals(
        SpeedCeiling.brakingLimitBps(tick.curve(), FAST, SLOW, 6), tick.edgeLimitBps(), 1e-6);
  }

  /** 驶过道岔之后，前方全是 22.2：基准回到 22.2（配合执行层补牵引，列车才能提速）。 */
  @Test
  void pastTheSlowSwitchTheRestOfTheSegmentRunsAtTheLineSpeed() {
    Tick tick = signalTick(Optional.of(X), 100.0);

    assertEquals(FAST, tick.edgeLimitBps(), 1e-6);
  }

  /** 与编表同一条 S 形加减速曲线（本车车种）。 */
  private record Tick(double edgeLimitBps, SpeedCurve curve) {}

  private static Tick signalTick(Optional<NodeId> lastPassed, double headX) {
    UUID worldId = UUID.randomUUID();
    RouteDefinition route =
        new RouteDefinition(RouteId.of("WS-2C_FullR"), List.of(A, B), Optional.empty());
    TagStore tags =
        new TagStore(
            TRAIN,
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    ConfigManager.ConfigView view = testConfigView(20, SLOW, 3, 2);
    assertTrue(view.runtimeSettings().speedCurveEnabled(), "夹具前提：速度曲线开启");
    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(view);
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    sectionlessGraph(lwnToSwn()), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenAnswer(
            invocation -> {
              RailEdge edge = invocation.getArgument(1);
              return edge.id().equals(EdgeId.undirected(SW, X)) ? SLOW : FAST;
            });
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    OccupancyManager occupancyManager =
        mock(OccupancyManager.class, withSettings().extraInterfaces(AuthorityHandoffSupport.class));
    when(((AuthorityHandoffSupport) occupancyManager).holdsHardAuthority(any())).thenReturn(true);
    when(((AuthorityHandoffSupport) occupancyManager)
            .migrateAuthorityOwner(anyString(), anyString()))
        .thenReturn(true);
    when(occupancyManager.snapshotClaims()).thenReturn(List.of());
    when(occupancyManager.canEnter(any()))
        .thenAnswer(
            invocation -> {
              OccupancyRequest request = invocation.getArgument(0);
              return new OccupancyDecision(true, request.now(), SignalAspect.PROCEED, List.of());
            });
    when(occupancyManager.acquire(any()))
        .thenAnswer(
            invocation -> {
              OccupancyRequest request = invocation.getArgument(0);
              return new OccupancyDecision(true, request.now(), SignalAspect.PROCEED, List.of());
            });
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags(TRAIN, tags.properties(), route);
    lastPassed.ifPresent(node -> registry.updateLastPassedGraphNode(TRAIN, node, Instant.now()));
    TrainConfigResolver trainConfigs = new TrainConfigResolver();
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
            trainConfigs,
            message -> {});
    FakeTrain train = new FakeTrain(worldId, tags.properties(), true, 0.4);
    RailState head = mock(RailState.class);
    when(head.positionLocation()).thenReturn(new Location(null, headX, 64.0, 0.0));
    train.railState = Optional.of(head);

    service.handleSignalTick(train, false);

    TrainConfig config = trainConfigs.resolve(tags.properties(), view);
    return new Tick(
        service.getDiagnostics(TRAIN).orElseThrow().edgeLimitBps(),
        new SpeedCurve(config.accelBps2(), config.decelBps2()));
  }

  /** A —26— SW —48— X —600— B，节点坐标沿 x 轴与边长一致，供车头位置插值。 */
  private static SimpleRailGraph lwnToSwn() {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    nodes.put(A, node(A, NodeType.STATION, 0.0));
    nodes.put(SW, node(SW, NodeType.SWITCHER, 26.0));
    nodes.put(X, node(X, NodeType.WAYPOINT, 74.0));
    nodes.put(B, node(B, NodeType.STATION, 674.0));
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    edge(edges, A, SW, 26);
    edge(edges, SW, X, 48);
    edge(edges, X, B, 600);
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
