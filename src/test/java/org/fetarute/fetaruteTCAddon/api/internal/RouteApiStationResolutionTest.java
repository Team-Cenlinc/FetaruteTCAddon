package org.fetarute.fetaruteTCAddon.api.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi.OperationType;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi.RouteDetail;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi.RouteInfo;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi.RouteStage;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi.StopInfo;
import org.fetarute.fetaruteTCAddon.company.api.StationDirectory;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.storage.SampleTransitNetwork;
import org.fetarute.fetaruteTCAddon.storage.TransitTestStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 路线 ID、运营类型与交路阶段、停靠点车站身份（实服形态：停靠点不绑 stationId）。 */
class RouteApiStationResolutionTest {

  @TempDir Path dir;
  private TransitTestStorage storage;
  private SampleTransitNetwork net;
  private RouteDefinitionCache routes;
  private StationDirectory directory;
  private RouteApi api;

  @BeforeEach
  void setUp() throws Exception {
    storage = TransitTestStorage.open(dir);
    net = new SampleTransitNetwork(storage);
    routes = new RouteDefinitionCache(message -> {});
    directory = new StationDirectory(routes, message -> {});
    routes.reload(storage.provider());
    directory.reload(storage.provider());
    api = new RouteApiImpl(routes, directory);
  }

  @AfterEach
  void tearDown() {
    storage.close();
  }

  @Test
  void routeIdIsTheSameAcrossListGetAndFind() {
    Map<String, UUID> byCode =
        api.listRoutes().stream().collect(Collectors.toMap(RouteInfo::routeCode, RouteInfo::id));
    assertEquals(Map.of("WS-1", net.ws1.id(), "DS-R", net.dsR.id(), "SL-1", net.sl1.id()), byCode);

    RouteInfo got = api.getRoute(net.ws1.id()).orElseThrow().info();
    RouteInfo found = api.findByCode("surc", "ws", "ws-1").orElseThrow().info();
    assertEquals(net.ws1.id(), got.id());
    assertEquals(net.ws1.id(), found.id());
    assertEquals(got, found);
    for (RouteInfo info : api.listRoutes()) {
      assertNotNull(info.id());
      assertEquals(info, api.getRoute(info.id()).orElseThrow().info());
    }
  }

  @Test
  void operationTypeComesFromPatternAndStageFromOperationType() {
    RouteInfo ws = api.getRoute(net.ws1.id()).orElseThrow().info();
    RouteInfo ds = api.getRoute(net.dsR.id()).orElseThrow().info();
    RouteInfo sl = api.getRoute(net.sl1.id()).orElseThrow().info();

    assertEquals(OperationType.LOCAL, ws.operationType());
    assertEquals(OperationType.RAPID, ds.operationType(), "新快速并入快速");
    assertEquals(OperationType.EXPRESS, sl.operationType(), "限定特急并入特急");
    assertEquals(RouteStage.OPERATION, ws.stage());
    assertEquals(RouteStage.RETURN, ds.stage());
    assertEquals(RouteStage.CREATE, sl.stage());
  }

  @Test
  void stopsWithoutStationIdGetRealStationNames() {
    List<StopInfo> stops = api.getRoute(net.ws1.id()).orElseThrow().stops();

    assertStop(stops.get(0), "葵坪", net.kpo);
    assertStop(stops.get(1), "平坪口", net.ppk); // 咽喉 SURC:S:PPK:1:001
    assertStop(stops.get(2), "平坪口", net.ppk);
    // 区间点：不是车站
    assertEquals(Optional.empty(), stops.get(3).stationName());
    assertEquals(Optional.empty(), stops.get(3).stationId());
    assertEquals(Optional.empty(), stops.get(3).stationCode());
    assertStop(stops.get(4), "海湖", net.hhu); // 通过站同样给身份
    // 站码查不到车站记录：退回站码
    assertEquals(Optional.of("ZZZ"), stops.get(5).stationName());
    assertEquals(Optional.of("ZZZ"), stops.get(5).stationCode());
    assertEquals(Optional.empty(), stops.get(5).stationId());
    // DYNAMIC：真实站名（此前是站码 WYB）
    StopInfo dynamic = stops.get(6);
    assertTrue(dynamic.dynamic());
    assertEquals("SURC:S:WYB:1", dynamic.nodeId());
    assertStop(dynamic, "湾油埠", net.wyb);
    // 车库：不是车站，三项皆空（线路终点的「LWN Depot」标签见 TerminalInfo）
    assertEquals(Optional.empty(), stops.get(7).stationName());
    assertEquals(Optional.empty(), stops.get(7).stationId());
    assertEquals(Optional.empty(), stops.get(7).stationCode());
  }

  @Test
  void sameStationCodeUnderAnotherOperatorIsNotMixedUp() {
    List<StopInfo> sl = api.getRoute(net.sl1.id()).orElseThrow().stops();
    assertStop(sl.get(0), "坪洲", net.ftaPpk);
    assertStop(sl.get(1), "狮岭", net.sla);
    assertStop(api.getRoute(net.ws1.id()).orElseThrow().stops().get(2), "平坪口", net.ppk);
  }

  @Test
  void sameOperatorCodeInAnotherCompanyDoesNotHijackRouteStops() {
    // 另一家公司也有代码为 SURC 的运营商和 PPK 站：交路按自己的运营商解析；全局口径先到先得。
    var other = storage.company("OTHER");
    var otherSurc = storage.operator(other, "SURC", null);
    Station otherPpk = storage.station(otherSurc, "PPK", "别家平坪口");
    var line = storage.line(otherSurc, "XX", "别家线", null);
    var route =
        storage.route(
            line,
            "XX-1",
            org.fetarute.fetaruteTCAddon.company.model.RoutePatternType.LOCAL,
            org.fetarute.fetaruteTCAddon.company.model.RouteOperationType.OPERATION);
    storage.stops(
        route, new String[] {"STOP", "SURC:S:PPK:1"}, new String[] {"TERMINATE", "SURC:S:HHU:1"});
    routes.reload(storage.provider());
    directory.reload(storage.provider());

    assertStop(api.getRoute(route.id()).orElseThrow().stops().get(0), "别家平坪口", otherPpk);
    assertStop(api.getRoute(net.ws1.id()).orElseThrow().stops().get(2), "平坪口", net.ppk);
    assertEquals(
        net.ppk.id(),
        directory.snapshot().findStation("surc", "ppk").orElseThrow().id(),
        "全局口径：公司列表中先出现的 SURC");
  }

  @Test
  void graphNodeBindingIsTheSameFallbackOnAndOffRoutes() {
    Station bound =
        storage
            .provider()
            .stations()
            .save(
                new Station(
                    net.kpo.id(),
                    net.kpo.code(),
                    net.kpo.operatorId(),
                    Optional.empty(),
                    net.kpo.name(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of("KPO-PLATFORM-A"),
                    Optional.empty(),
                    List.of(),
                    Map.of(),
                    net.kpo.createdAt(),
                    Instant.now()));
    storage.stops(
        net.dsR,
        new String[] {"STOP", "KPO-PLATFORM-A"},
        new String[] {"STOP", "SURC:S:WYB:2"},
        new String[] {"TERMINATE", "SURC:S:HHU:2"});
    directory.reload(storage.provider());
    routes.reload(storage.provider());

    // 节点解析不出车站时，按车站绑定的图节点归站；交路途经与否结果一致。
    assertStop(api.getRoute(net.dsR.id()).orElseThrow().stops().get(0), "葵坪", bound);
    assertEquals(Optional.of(bound.id()), directory.snapshot().stationIdOfNode("KPO-PLATFORM-A"));
    // 能按站码解析的节点以站码为准
    assertEquals(Optional.of(net.hhu.id()), directory.snapshot().stationIdOfNode("SURC:S:HHU:9"));
  }

  @Test
  void terminalNamesFollowTheSameRules() {
    RouteDetail ws = api.getRoute(net.ws1.id()).orElseThrow();
    assertEquals("SURC:D:LWN:1", ws.terminal().endOfRouteNodeId());
    assertEquals(Optional.of("LWN Depot"), ws.terminal().endOfRouteName());
    assertEquals("SURC:S:WYB:1", ws.terminal().endOfOperationNodeId());
    assertEquals(Optional.of("湾油埠"), ws.terminal().endOfOperationName());

    RouteDetail sl = api.getRoute(net.sl1.id()).orElseThrow();
    assertEquals(Optional.of("狮岭"), sl.terminal().endOfRouteName());
    assertEquals(Optional.of("狮岭"), sl.terminal().endOfOperationName());
  }

  @Test
  void renamedStationShowsUpAfterDirectoryReload() {
    storage
        .provider()
        .stations()
        .save(
            new Station(
                net.hhu.id(),
                net.hhu.code(),
                net.hhu.operatorId(),
                Optional.empty(),
                "海湖东",
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                Map.of(),
                net.hhu.createdAt(),
                Instant.now()));
    assertEquals(
        Optional.of("海湖"), api.getRoute(net.dsR.id()).orElseThrow().stops().get(1).stationName());

    directory.reload(storage.provider());

    RouteDetail ds = api.getRoute(net.dsR.id()).orElseThrow();
    assertEquals(Optional.of("海湖东"), ds.stops().get(1).stationName());
    assertEquals(Optional.of("海湖东"), ds.terminal().endOfOperationName());
  }

  @Test
  void withoutDirectoryNamesFallBackToCodes() {
    RouteApi bare = new RouteApiImpl(routes, null);
    List<StopInfo> stops = bare.getRoute(net.ws1.id()).orElseThrow().stops();
    assertEquals(Optional.of("PPK"), stops.get(2).stationName());
    assertEquals(Optional.of("PPK"), stops.get(2).stationCode());
    assertFalse(stops.get(2).stationId().isPresent());
    assertEquals(Optional.of("WYB"), stops.get(6).stationName());
    assertEquals(3, bare.listRoutes().size());
  }

  @Test
  void legacyConstructorsStayAvailable() {
    RouteInfo info =
        new RouteInfo(
            UUID.randomUUID(), "A:B:C", "A", "B", "C", Optional.empty(), OperationType.RAPID);
    assertEquals(RouteStage.UNKNOWN, info.stage());
    StopInfo stop = new StopInfo(0, "A:S:X:1", Optional.of("X"), 0, RouteApi.PassType.STOP, false);
    assertTrue(stop.stationId().isEmpty());
    assertTrue(stop.stationCode().isEmpty());
  }

  private static void assertStop(StopInfo stop, String name, Station station) {
    assertEquals(Optional.of(name), stop.stationName(), stop.nodeId());
    assertEquals(Optional.of(station.id()), stop.stationId(), stop.nodeId());
    assertEquals(Optional.of(station.code()), stop.stationCode(), stop.nodeId());
  }
}
