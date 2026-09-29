package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.dynamicStop;
import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.routeStop;
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
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
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
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteMetadata;
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
 * 折返复用车的初始线路标签。
 *
 * <p>直通运转的起步 CHANGE 存在首站备注里；折返复用的列车已经停在首站，CHANGE 不会再被“抵达”执行，所以复用时就要把线路标签写成 起步线路。交路代码、交路 ID
 * 仍是交路自身的（管理归属不变）。
 */
class RuntimeStartLineTagTest {

  /** 在 SURC:S:TERM:1 折返复用，首站备注为 {@code firstStopNotes}，返回复用后的列车标签。 */
  private static TagStore reuseAtFirstStop(String firstStopNotes) {
    String previousTrainName = "turning-train";
    NodeId approach = NodeId.of("SURC:TERM:APPROACH:1");
    NodeId terminal = NodeId.of("SURC:S:TERM:1");
    NodeId throat = NodeId.of("SURC:S:TERM:1:001");
    NodeId clear = NodeId.of("SURC:TERM:NEXT:1:001");
    RailEdge oldApproach =
        new RailEdge(
            EdgeId.undirected(approach, terminal),
            approach,
            terminal,
            10,
            -1.0,
            true,
            Optional.empty());
    RailEdge terminalThroat =
        new RailEdge(
            EdgeId.undirected(terminal, throat),
            terminal,
            throat,
            10,
            -1.0,
            true,
            Optional.empty());
    RailEdge throatClear =
        new RailEdge(
            EdgeId.undirected(throat, clear), throat, clear, 10, -1.0, true, Optional.empty());
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
            RouteId.of("SURC:MT:MT-3N_DPExp"),
            List.of(terminal, throat, clear),
            Optional.of(RouteMetadata.of("SURC", "MT", "MT-3N_DPExp", null)));
    UUID ticketRouteId = UUID.randomUUID();
    UUID worldId = UUID.randomUUID();
    OccupancyResource oldApproachResource = OccupancyResource.forEdge(oldApproach.id());
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    assertTrue(
        manager
            .acquire(
                new OccupancyRequest(
                    previousTrainName,
                    Optional.empty(),
                    Instant.now(),
                    List.of(oldApproachResource, OccupancyResource.forNode(terminal)),
                    Map.of()))
            .allowed());

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(1, 20.0));
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(20.0);
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findById(ticketRouteId)).thenReturn(Optional.of(route));
    when(routeDefinitions.listStops(route.id()))
        .thenReturn(
            List.of(
                dynamicStop(0, terminal, firstStopNotes),
                routeStop(1, throat, RouteStopPassType.PASS),
                routeStop(2, clear, RouteStopPassType.STOP)));
    LayoverRegistry layoverRegistry = new LayoverRegistry();
    layoverRegistry.register(
        previousTrainName,
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
    // 上一趟服务的线路标签（WS 线）：复用时必须被新服务的起步线路整体覆盖
    TagStore tags = new TagStore(previousTrainName, "FTA_OPERATOR_CODE=SURC", "FTA_LINE_CODE=WS");
    FakeTrain train = new FakeTrain(worldId, tags.properties(), false);
    LayoverRegistry.LayoverCandidate candidate =
        layoverRegistry.get(previousTrainName).orElseThrow();
    ServiceTicket ticket =
        new ServiceTicket(
            "ticket-turnback",
            Instant.now(),
            ticketRouteId,
            candidate.terminalKey(),
            10,
            ServiceTicket.TicketMode.OPERATION);

    LayoverDispatchResult result =
        service.dispatchLayover(candidate, ticket, tags.properties(), train);

    assertTrue(result.dispatched(), result.reason());
    assertEquals(1, train.launchCalls);
    return tags;
  }

  private static String tag(TagStore tags, String key) {
    return TrainTagHelper.readTagValue(tags.properties(), key).orElse("<无>");
  }

  @Test
  void reusedTrainStartsOnTheFirstStopChangeLine() {
    TagStore tags = reuseAtFirstStop("CHANGE:SURC:WS");

    assertEquals("SURC", tag(tags, "FTA_OPERATOR_CODE"));
    assertEquals("WS", tag(tags, "FTA_LINE_CODE"));
    assertEquals("MT-3N_DPExp", tag(tags, "FTA_ROUTE_CODE"), "交路代码是交路自身的，管理归属不变");
  }

  @Test
  void reusedTrainStartsOnAChangeTargetOfAnotherOperator() {
    TagStore tags = reuseAtFirstStop("DYNAMIC:SURC:S:TERM:[1:2]\nCHANGE:FTA:SL");

    assertEquals("FTA", tag(tags, "FTA_OPERATOR_CODE"));
    assertEquals("SL", tag(tags, "FTA_LINE_CODE"));
  }

  @Test
  void reusedTrainKeepsTheRouteLineWithoutAUsableFirstStopChange() {
    for (String notes :
        List.of("DYNAMIC:SURC:S:TERM:[1:2]", "CHANGE:SURC:MT", "CHANGE:SURC", "CHANGE::WS")) {
      TagStore tags = reuseAtFirstStop(notes);
      assertEquals("SURC", tag(tags, "FTA_OPERATOR_CODE"), notes);
      assertEquals("MT", tag(tags, "FTA_LINE_CODE"), notes);
    }
  }
}
