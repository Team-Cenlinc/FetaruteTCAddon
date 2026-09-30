package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.dynamicStop;
import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.graphWithConflictFreeLinearPath;
import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.testConfigView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.FakeTrain;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.TagStore;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.junit.jupiter.api.Test;

/**
 * 首站 CHANGE 是起步线路：出车（或折返复用）时标签已经写成目标线路，列车随后“抵达”首站时不能再当成到站换线重复执行、重复记日志。
 *
 * <p>中途站的 CHANGE 仍是抵达后执行；标签与目标不一致（例如没有经过出车入口的列车）时首站抵达仍会补写，保持自愈。
 */
class RuntimeStartLineArrivalTest {

  private static final NodeId A = NodeId.of("OP:S:AAA:1");
  private static final NodeId B = NodeId.of("OP:S:BBB:1");
  private static final String CHANGE_LOG = "CHANGE 移交成功";

  /** MT 线上的交路 R1：A（首站）→ B。 */
  private static final RouteDefinition ROUTE =
      new RouteDefinition(
          RouteId.of("OP:MT:R1"),
          List.of(A, B),
          Optional.of(RouteMetadata.of("OP", "MT", "R1", null)));

  private record Fixture(
      RuntimeDispatchService service, FakeTrain train, TagStore tags, List<String> logs) {

    void arriveAt(NodeId node) {
      service.handleStationArrival(
          train,
          new SignNodeDefinition(node, NodeType.STATION, Optional.empty(), Optional.empty()));
    }

    String tag(String key) {
      return TrainTagHelper.readTagValue(tags.properties(), key).orElse("<无>");
    }

    long changeLogs() {
      return logs.stream().filter(line -> line.contains(CHANGE_LOG)).count();
    }
  }

  /** 列车刚在 A 出车（route index 0），线路标签为 {@code operator}/{@code line}。 */
  private static Fixture fixture(
      String firstStopNotes, String secondStopNotes, String operator, String line) {
    UUID routeUuid = UUID.randomUUID();
    TagStore tagStore =
        new TagStore(
            "spawned",
            "FTA_ROUTE_ID=" + routeUuid,
            "FTA_OPERATOR_CODE=" + operator,
            "FTA_LINE_CODE=" + line,
            "FTA_ROUTE_CODE=R1",
            "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();
    ConfigManager config = mock(ConfigManager.class);
    when(config.current()).thenReturn(testConfigView(20, 20.0));
    RailGraphService graphs = mock(RailGraphService.class);
    when(graphs.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithConflictFreeLinearPath(List.of(A, B), 80), Instant.now())));
    when(graphs.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble(), anyDouble()))
        .thenReturn(1000.0);
    RouteDefinitionCache routes = mock(RouteDefinitionCache.class);
    when(routes.findById(routeUuid)).thenReturn(Optional.of(ROUTE));
    var first = dynamicStop(0, A, firstStopNotes);
    var second = dynamicStop(1, B, secondStopNotes);
    when(routes.findStop(ROUTE.id(), 0)).thenReturn(Optional.of(first));
    when(routes.findStop(ROUTE.id(), 1)).thenReturn(Optional.of(second));
    when(routes.listStops(ROUTE.id())).thenReturn(List.of(first, second));
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags("spawned", tagStore.properties(), ROUTE);
    List<String> logs = new ArrayList<>();
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
            logs::add);
    return new Fixture(
        service, new FakeTrain(worldId, tagStore.properties(), false), tagStore, logs);
  }

  @Test
  void arrivingAtTheFirstStopDoesNotRepeatTheStartLineChange() {
    // 出车时标签已是起步线路 WS
    Fixture fixture = fixture("CHANGE:OP:WS", "STOP", "OP", "WS");

    fixture.arriveAt(A);

    assertEquals("WS", fixture.tag("FTA_LINE_CODE"));
    assertEquals(0, fixture.changeLogs(), "已在目标线路上：不是到站换线，不重复写标签、不记“移交成功”");
  }

  @Test
  void startLineComparisonIgnoresCase() {
    Fixture fixture = fixture("CHANGE:op:ws", "STOP", "OP", "WS");

    fixture.arriveAt(A);

    assertEquals("WS", fixture.tag("FTA_LINE_CODE"));
    assertEquals(0, fixture.changeLogs());
  }

  @Test
  void arrivingAtTheFirstStopStillHealsATrainNotOnTheStartLine() {
    // 没有经过出车入口写标签的列车（仍是交路自身的 MT）：首站抵达时补写，与旧行为一致
    Fixture fixture = fixture("CHANGE:OP:WS", "STOP", "OP", "MT");

    fixture.arriveAt(A);

    assertEquals("WS", fixture.tag("FTA_LINE_CODE"));
    assertEquals(1, fixture.changeLogs());
  }

  @Test
  void aChangeAtALaterStopStillRunsOnArrival() {
    Fixture fixture = fixture("DYNAMIC:OP:S:AAA:[1:1]", "CHANGE:OP:DS", "OP", "MT");

    fixture.arriveAt(B);

    assertEquals("DS", fixture.tag("FTA_LINE_CODE"));
    assertEquals(1, fixture.changeLogs());
  }

  @Test
  void aMalformedFirstStopChangeStaysAParseFailureAndNeverChangesTheTags() {
    Fixture fixture = fixture("CHANGE:OP", "STOP", "OP", "MT");

    fixture.arriveAt(A);

    assertEquals("MT", fixture.tag("FTA_LINE_CODE"));
    assertEquals(0, fixture.changeLogs());
    assertEquals(1, fixture.logs.stream().filter(line -> line.contains("CHANGE 解析失败")).count());
  }
}
