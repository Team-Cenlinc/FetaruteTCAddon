package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.testConfigView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.FakeTrain;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.TagStore;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.junit.jupiter.api.Test;

/**
 * 折返复用换交路后，列车的种别标签与车名字母跟着新交路走。
 *
 * <p>2026-09-29 实服：MT-3（RAPID）没有自己的车库出车，车全靠折返复用接班。复用原先不写 {@code FTA_PATTERN}，车名字母又写死 LOCAL，于是快速列车的
 * HUD 一直显示「各站停」，车名也是 {@code MT-LN-…}。
 *
 * <p>独立成类：{@code RuntimeDispatchServiceTest} 已贴着 SpotBugs 单类 1000 方法的上限。
 */
class RuntimeDispatchLayoverPatternTest {

  private static final String PREVIOUS = "turning-train";

  private record Reused(String trainName, TagStore tags) {}

  /**
   * 在 TERM 站复用一条交路；列车身上带着 {@code initialPatternTag} 标签。
   *
   * @param patternInCache 交路缓存里该交路的种别；为空表示缓存里查不到这条交路
   */
  private Reused reuseOnto(Optional<RoutePatternType> patternInCache, String initialPatternTag) {
    NodeId approach = NodeId.of("SURC:TERM:APPROACH:1");
    NodeId terminal = NodeId.of("SURC:S:TERM:1");
    NodeId throat = NodeId.of("SURC:S:TERM:1:001");
    NodeId clear = NodeId.of("SURC:TERM:NEXT:1:001");
    RailEdge oldApproach = edge(approach, terminal);
    RailEdge terminalThroat = edge(terminal, throat);
    RailEdge throatClear = edge(throat, clear);
    RailGraph graph =
        new SimpleRailGraph(
            Map.of(
                approach, new RailNodeTest(approach),
                terminal, new RailNodeTest(terminal),
                throat, new RailNodeTest(throat),
                clear, new RailNodeTest(clear)),
            Map.of(
                oldApproach.id(), oldApproach,
                terminalThroat.id(), terminalThroat,
                throatClear.id(), throatClear),
            Set.of());
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("turnback"), List.of(terminal, throat, clear), Optional.empty());
    UUID ticketRouteId = UUID.randomUUID();
    UUID worldId = UUID.randomUUID();
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    assertTrue(
        manager
            .acquire(
                new OccupancyRequest(
                    PREVIOUS,
                    Optional.empty(),
                    Instant.now(),
                    List.of(
                        OccupancyResource.forEdge(oldApproach.id()),
                        OccupancyResource.forNode(terminal)),
                    Map.of()))
            .allowed());

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(1, 20.0));
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(
            any(), any(), any(), anyDouble(), anyDouble()))
        .thenReturn(20.0);
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findById(ticketRouteId)).thenReturn(Optional.of(route));
    when(routeDefinitions.listStops(route.id())).thenReturn(List.of());

    if (patternInCache.isPresent()) {
      Route stored =
          new Route(
              ticketRouteId,
              "MT-3N_DPExp",
              UUID.randomUUID(),
              "两港快线",
              Optional.empty(),
              patternInCache.get(),
              RouteOperationType.OPERATION,
              Optional.empty(),
              Optional.empty(),
              Map.of(),
              Instant.now(),
              Instant.now());
      when(routeDefinitions.findRecord(ticketRouteId))
          .thenReturn(
              Optional.of(
                  new RouteDefinitionCache.RouteRecord(
                      mock(Operator.class), mock(Line.class), stored)));
    }

    LayoverRegistry layoverRegistry = new LayoverRegistry();
    layoverRegistry.register(
        PREVIOUS,
        TerminalKeyResolver.toTerminalKey(terminal),
        terminal,
        Instant.now().minusSeconds(1),
        Map.of());
    RuntimeDispatchService service =
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
            null);
    TagStore tags = new TagStore(PREVIOUS, "FTA_PATTERN=" + initialPatternTag);
    FakeTrain train = new FakeTrain(worldId, tags.properties(), false);
    LayoverRegistry.LayoverCandidate candidate = layoverRegistry.get(PREVIOUS).orElseThrow();
    ServiceTicket ticket =
        new ServiceTicket(
            "ticket-pattern",
            Instant.now(),
            ticketRouteId,
            candidate.terminalKey(),
            10,
            ServiceTicket.TicketMode.OPERATION);

    LayoverDispatchResult result =
        service.dispatchLayover(candidate, ticket, tags.properties(), train);

    assertTrue(result.dispatched(), result.reason());
    return new Reused(result.trainName().orElseThrow(), tags);
  }

  private static RailEdge edge(NodeId from, NodeId to) {
    return new RailEdge(EdgeId.undirected(from, to), from, to, 10, -1.0, true, Optional.empty());
  }

  @Test
  void reuseRewritesThePatternTagToTheNewRoute() {
    Reused reused = reuseOnto(Optional.of(RoutePatternType.RAPID), "LOCAL");

    assertEquals(
        Optional.of("RAPID"),
        TrainTagHelper.readTagValue(reused.tags().properties(), "FTA_PATTERN"));
  }

  @Test
  void reuseNamesTheTrainAfterTheNewRoutePattern() {
    Reused reused = reuseOnto(Optional.of(RoutePatternType.RAPID), "LOCAL");

    // <OP>-<LINE>-<PATTERN><DEST>-<SEQ>：第三段首字母是种别（RAPID → R），不再永远是 L
    String[] parts = reused.trainName().split("-");
    assertTrue(parts.length >= 4, reused.trainName());
    assertEquals('R', parts[2].charAt(0), reused.trainName());
  }

  @Test
  void aLocalRouteStillGetsTheLocalPattern() {
    Reused reused = reuseOnto(Optional.of(RoutePatternType.LOCAL), "RAPID");

    assertEquals(
        Optional.of("LOCAL"),
        TrainTagHelper.readTagValue(reused.tags().properties(), "FTA_PATTERN"));
    assertEquals('L', reused.trainName().split("-")[2].charAt(0), reused.trainName());
  }

  /** 交路缓存答不上来时，不能把原本可能正确的标签覆盖成默认的 LOCAL；车名字母回退 L。 */
  @Test
  void anUnresolvedPatternLeavesTheTagAlone() {
    Reused reused = reuseOnto(Optional.empty(), "EXPRESS");

    assertEquals(
        Optional.of("EXPRESS"),
        TrainTagHelper.readTagValue(reused.tags().properties(), "FTA_PATTERN"));
    assertEquals('L', reused.trainName().split("-")[2].charAt(0), reused.trainName());
  }
}
