package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.graphWithConflictFreeLinearPath;
import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.routeStop;
import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.testConfigView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.FakeTrain;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.TagStore;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.junit.jupiter.api.Test;

/**
 * 列车所跑的交路以 {@code FTA_ROUTE_ID} 为准。
 *
 * <p>直通运转（CHANGE）只是通知：它把运营商、线路标签改写成对乘客显示的线路，交路与管理归属不变。 换线后标签三元组是（新运营商, 新线路,
 * 原交路代码），新线路恰有同码交路时，按三元组会把列车解析成那条交路。
 */
class RuntimeRouteIdentityTest {

  private static final NodeId A = NodeId.of("OP:S:AAA:1");
  private static final NodeId B = NodeId.of("OP:S:BBB:1");
  private static final NodeId X = NodeId.of("OP:S:XXX:1");

  /** 列车真正跑的交路：WS 线 R1，A → B（在 A 起直通 DS）。 */
  private static final RouteDefinition OWN =
      new RouteDefinition(RouteId.of("OP:WS:R1"), List.of(A, B), Optional.empty());

  /** DS 线上恰好同码的另一条交路：X → A，不经过 B。 */
  private static final RouteDefinition COLLISION =
      new RouteDefinition(RouteId.of("OP:DS:R1"), List.of(X, A), Optional.empty());

  private record Fixture(
      RuntimeDispatchService service, FakeTrain train, RouteProgressRegistry registry) {

    /** 到达 B：只有真正跑的交路上有这一站。 */
    void arriveAtB() {
      service.handleStationArrival(
          train, new SignNodeDefinition(B, NodeType.STATION, Optional.empty(), Optional.empty()));
    }

    RouteProgressRegistry.RouteProgressEntry progress() {
      return registry.get("thru").orElseThrow();
    }
  }

  private static Fixture fixture(UUID ownUuid, String... tags) {
    TagStore tagStore = new TagStore("thru", tags);
    UUID worldId = UUID.randomUUID();
    ConfigManager config = mock(ConfigManager.class);
    when(config.current()).thenReturn(testConfigView(20, 20.0));
    RailGraphService graphs = mock(RailGraphService.class);
    when(graphs.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithConflictFreeLinearPath(List.of(X, A, B), 80), Instant.now())));
    when(graphs.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble(), anyDouble()))
        .thenReturn(1000.0);
    RouteDefinitionCache routes = mock(RouteDefinitionCache.class);
    when(routes.findById(ownUuid)).thenReturn(Optional.of(OWN));
    when(routes.findByCodes("OP", "WS", "R1")).thenReturn(Optional.of(OWN));
    when(routes.findByCodes("OP", "DS", "R1")).thenReturn(Optional.of(COLLISION));
    when(routes.findStop(OWN.id(), 1))
        .thenReturn(Optional.of(routeStop(1, B, RouteStopPassType.STOP)));
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags("thru", tagStore.properties(), OWN);
    RuntimeDispatchService service =
        new RuntimeDispatchService(
            new SimpleOccupancyManager(
                (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy()),
            graphs,
            routes,
            registry,
            mock(SignNodeRegistry.class),
            mock(LayoverRegistry.class),
            new DwellRegistry(),
            config,
            null,
            new TrainConfigResolver(),
            message -> {});
    return new Fixture(service, new FakeTrain(worldId, tagStore.properties(), false), registry);
  }

  @Test
  void changedLineTagsDoNotResolveToASameCodeRouteOnTheNewLine() {
    UUID ownUuid = UUID.randomUUID();
    Fixture fixture =
        fixture(
            ownUuid,
            "FTA_ROUTE_ID=" + ownUuid,
            "FTA_OPERATOR_CODE=OP",
            "FTA_LINE_CODE=DS",
            "FTA_ROUTE_CODE=R1",
            "FTA_ROUTE_INDEX=0");

    fixture.arriveAtB();

    assertEquals(OWN.id(), fixture.progress().routeId(), "仍按 FTA_ROUTE_ID 的交路推进");
    assertEquals(1, fixture.progress().currentIndex(), "B 在原交路上是第 1 站，DS 同码交路上没有它");
  }

  @Test
  void trainsWithoutRouteIdStillResolveByCodes() {
    Fixture fixture =
        fixture(
            UUID.randomUUID(),
            "FTA_OPERATOR_CODE=OP",
            "FTA_LINE_CODE=WS",
            "FTA_ROUTE_CODE=R1",
            "FTA_ROUTE_INDEX=0");

    fixture.arriveAtB();

    assertEquals(1, fixture.progress().currentIndex());
  }

  @Test
  void unknownRouteIdFallsBackToCodes() {
    // 交路删后重建：旧 UUID 不在缓存里，按代码三元组找回。
    Fixture fixture =
        fixture(
            UUID.randomUUID(),
            "FTA_ROUTE_ID=" + UUID.randomUUID(),
            "FTA_OPERATOR_CODE=OP",
            "FTA_LINE_CODE=WS",
            "FTA_ROUTE_CODE=R1",
            "FTA_ROUTE_INDEX=0");

    fixture.arriveAtB();

    assertEquals(1, fixture.progress().currentIndex());
  }
}
