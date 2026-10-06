package org.fetarute.fetaruteTCAddon.api.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.api.FetaruteApi;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi;
import org.fetarute.fetaruteTCAddon.api.station.StationApi;
import org.fetarute.fetaruteTCAddon.api.station.StationApi.ServingLine;
import org.fetarute.fetaruteTCAddon.api.station.StationApi.TransferType;
import org.fetarute.fetaruteTCAddon.company.api.StationDirectory;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.StationGroupMember;
import org.fetarute.fetaruteTCAddon.company.model.StationTransferType;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.storage.SampleTransitNetwork;
import org.fetarute.fetaruteTCAddon.storage.TransitTestStorage;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 车站组与停靠线路：换乘由 FTCA 算好，查询只查内存快照。 */
class StationApiServingLinesTest {

  @TempDir Path dir;
  private TransitTestStorage storage;
  private SampleTransitNetwork net;
  private RouteDefinitionCache routes;
  private StationDirectory directory;
  private StationApi stations;
  private RouteApi routeApi;

  @BeforeEach
  void setUp() throws Exception {
    storage = TransitTestStorage.open(dir);
    net = new SampleTransitNetwork(storage);
    StorageProvider counting = storage.counting();
    routes = new RouteDefinitionCache(message -> {});
    directory = new StationDirectory(routes, message -> {});
    routes.reload(counting);
    directory.reload(counting);
    stations =
        new StationApiImpl(
            counting.stations(), counting.companies(), counting.operators(), directory);
    routeApi = new RouteApiImpl(routes, directory);
  }

  @AfterEach
  void tearDown() {
    storage.close();
  }

  private static List<String> codes(List<ServingLine> lines) {
    return lines.stream().map(line -> line.operatorCode() + ":" + line.lineCode()).toList();
  }

  @Test
  void ownLinesComeWithoutTransferTypeAndGroupLinesCarryIt() {
    List<ServingLine> lines = stations.linesServing(net.ppk.id());

    assertEquals(List.of("SURC:WS", "FTA:SL"), codes(lines), "先按成员 sortOrder，再按运营商、线路代码");
    ServingLine own = lines.get(0);
    assertEquals(net.ws.id(), own.lineId());
    assertEquals("西海线", own.lineName());
    assertEquals(Optional.of("#E60012"), own.color());
    assertEquals(net.ppk.id(), own.stationId());
    assertEquals("PPK", own.stationCode());
    assertTrue(own.transferType().isEmpty(), "查询站自身的线路没有换乘方式");
    assertTrue(own.walkSeconds().isEmpty());

    ServingLine transfer = lines.get(1);
    assertEquals(net.ftaPpk.id(), transfer.stationId(), "实际停靠的是同组的 FTA 坪洲");
    assertEquals(Optional.of(TransferType.OUT_OF_STATION), transfer.transferType());
    assertEquals(OptionalInt.of(90), transfer.walkSeconds());

    // 从 FTA 那一侧查：自己的 SL 没有换乘方式，SURC 的 WS 带 SURC 成员的换乘方式
    List<ServingLine> fromFta = stations.linesServing(net.ftaPpk.id());
    assertEquals(List.of("SURC:WS", "FTA:SL"), codes(fromFta));
    assertEquals(Optional.of(TransferType.IN_STATION), fromFta.get(0).transferType());
    assertTrue(fromFta.get(1).transferType().isEmpty());
  }

  @Test
  void passStopsDoNotCountAndDynamicStopsCountAtTheirStation() {
    // HHU：WS 只通过，DS 终到
    assertEquals(List.of("SURC:DS"), codes(stations.linesServing(net.hhu.id())));
    // WYB：WS 的 DYNAMIC 终到 + DS 停车
    assertEquals(List.of("SURC:DS", "SURC:WS"), codes(stations.linesServing(net.wyb.id())));
    // DS 没有线路色：回退运营商主题色
    assertEquals(Optional.of("#00AAFF"), stations.linesServing(net.hhu.id()).get(0).color());
    // 各阶段交路都统计：SL-1 是出库交路
    assertEquals(List.of("FTA:SL"), codes(stations.linesServing(net.sla.id())));
  }

  @Test
  void nodeQueriesAcceptPlatformThroatAndDynamicPlaceholder() {
    List<ServingLine> byStation = stations.linesServing(net.ppk.id());
    assertEquals(byStation, stations.linesServingNode("SURC:S:PPK:1"));
    assertEquals(byStation, stations.linesServingNode("SURC:S:PPK:1:001"), "咽喉归到车站");
    assertEquals(byStation, stations.linesServingNode("SURC:S:PPK:7"), "未出现在交路里的股道同样归到车站");
    assertEquals(
        stations.linesServing(net.wyb.id()),
        stations.linesServingNode("SURC:S:WYB:1"),
        "DYNAMIC 占位");
    assertTrue(stations.linesServingNode("SURC:PPK:HHU:1:001").isEmpty(), "区间点不是车站");
    assertTrue(stations.linesServingNode("SURC:D:LWN:1").isEmpty(), "车库不是车站");
    assertTrue(stations.linesServingNode("").isEmpty());
    assertTrue(stations.linesServingNode(null).isEmpty());
    assertTrue(stations.linesServingNode("SURC:S:ZZZ:1").isEmpty(), "没有车站记录");

    // 同一站码在两个运营商下各是各的
    assertEquals(net.ftaPpk.id(), stations.linesServingNode("FTA:S:PPK:1").get(1).stationId());
  }

  @Test
  void groupsAreListedWithMembers() {
    StationApi.StationGroupInfo group = stations.findGroupOfStation(net.ftaPpk.id()).orElseThrow();
    assertEquals(net.ppkGroup.id(), group.id());
    assertEquals(net.surc.id(), group.companyId());
    assertEquals("PPK", group.code());
    assertEquals(Optional.of("Ping Ping Hau"), group.secondaryName());
    assertEquals(2, group.members().size());
    StationApi.StationGroupMember fta = group.members().get(1);
    assertEquals(net.ftaPpk.id(), fta.stationId());
    assertEquals(net.ftaOp.id(), fta.operatorId());
    assertEquals("FTA", fta.operatorCode());
    assertEquals("PPK", fta.stationCode());
    assertEquals("坪洲", fta.stationName());
    assertEquals(TransferType.OUT_OF_STATION, fta.transferType());
    assertEquals(OptionalInt.of(90), fta.walkSeconds());
    assertEquals(1, fta.sortOrder());

    assertEquals(Optional.of(group), stations.findGroupOfNode("SURC:S:PPK:2:003"));
    assertTrue(stations.findGroupOfStation(net.hhu.id()).isEmpty());
    assertTrue(stations.findGroupOfNode("SURC:S:HHU:1").isEmpty());
    assertEquals(List.of(group), List.copyOf(stations.listStationGroups()));
    assertThrows(
        UnsupportedOperationException.class,
        () -> stations.linesServing(net.ppk.id()).add(null),
        "返回不可变快照");
  }

  @Test
  void repeatedQueriesNeverTouchStorage() {
    UUID ws = net.ws1.id();
    stations.linesServingNode("SURC:S:PPK:1:009"); // 首次解析未知节点后缓存
    storage.resetRepositoryCalls();

    for (int i = 0; i < 2_000; i++) {
      stations.linesServingNode("SURC:S:PPK:1");
      stations.linesServingNode("SURC:S:PPK:1:009");
      stations.linesServingNode("SURC:S:WYB:1");
      stations.linesServing(net.hhu.id());
      stations.findGroupOfNode("FTA:S:PPK:1");
      stations.findGroupOfStation(net.ppk.id());
      stations.listStationGroups();
      routeApi.getRoute(ws);
      routeApi.findByCode("SURC", "WS", "WS-1");
      routeApi.listRoutes();
    }

    assertEquals(0, storage.repositoryCalls(), "查询只读内存快照");
    List<ServingLine> first = stations.linesServingNode("SURC:S:PPK:1");
    assertSame(first, stations.linesServingNode("SURC:S:PPK:1"), "同一版本返回同一份快照");
  }

  @Test
  void groupChangesBumpRevisionAndShowUpImmediately() {
    long before = directory.revision();
    storage
        .provider()
        .stationGroups()
        .saveMember(
            new StationGroupMember(
                net.ppkGroup.id(),
                net.hhu.id(),
                StationTransferType.SAME_PLATFORM,
                Optional.of(15),
                2));

    directory.reload(storage.provider());

    assertTrue(directory.revision() > before);
    List<ServingLine> lines = stations.linesServing(net.ppk.id());
    assertEquals(List.of("SURC:WS", "FTA:SL", "SURC:DS"), codes(lines));
    assertEquals(Optional.of(TransferType.SAME_PLATFORM), lines.get(2).transferType());
    assertEquals(OptionalInt.of(15), lines.get(2).walkSeconds());
  }

  @Test
  void routeChangesBumpRevisionWithoutAnExplicitReload() {
    long before = directory.revision();
    // WS 在 HHU 由通过改为停车
    storage.stops(
        net.ws1,
        new String[] {"STOP", "SURC:S:KPO:1"},
        new String[] {"STOP", "SURC:S:HHU:1"},
        new String[] {"TERMINATE", null, "DYNAMIC:SURC:S:WYB:[1:2]"});

    routes.refresh(storage.provider(), net.surcOp, net.ws, net.ws1);

    assertTrue(directory.revision() > before, "交路缓存刷新时自动重建");
    assertEquals(List.of("SURC:DS", "SURC:WS"), codes(stations.linesServing(net.hhu.id())));
    assertEquals(
        List.of("FTA:SL"), codes(stations.linesServing(net.ppk.id())), "WS 不再停 PPK，只剩换乘线路");
  }

  @Test
  void refreshingOneRouteReusesTheOthersResolution() {
    List<org.fetarute.fetaruteTCAddon.company.model.RouteStop> dsStops =
        routes.listStops(routes.findById(net.dsR.id()).orElseThrow().id());
    var before = directory.snapshot().stopStations(net.dsR.id(), dsStops, Optional.of(net.surcOp));

    routes.refresh(storage.provider(), net.surcOp, net.ws, net.ws1);

    var after = directory.snapshot().stopStations(net.dsR.id(), dsStops, Optional.of(net.surcOp));
    assertSame(before, after, "主数据与 DS 停靠表都没变：沿用上一版解析结果，不重算");
  }

  @Test
  void reloadDropsRoutesDeletedFromStorage() {
    var slDefinition = routes.findById(net.sl1.id()).orElseThrow();
    storage.provider().routes().delete(net.sl1.id());

    routes.reload(storage.provider());

    assertTrue(routes.findById(net.sl1.id()).isEmpty());
    assertTrue(routes.listStops(slDefinition.id()).isEmpty(), "停靠表缓存同样清掉");
    assertTrue(routes.entries().stream().noneMatch(entry -> entry.routeId().equals(net.sl1.id())));
    assertTrue(stations.linesServing(net.sla.id()).isEmpty());
    assertEquals(List.of("SURC:WS"), codes(stations.linesServing(net.ppk.id())));
  }

  @Test
  void masterDataChangesShowUpAfterReload() {
    storage
        .provider()
        .lines()
        .save(
            new Line(
                net.ds.id(),
                net.ds.code(),
                net.ds.operatorId(),
                "东山支线",
                Optional.empty(),
                net.ds.serviceType(),
                Optional.of("#123456"),
                net.ds.status(),
                Optional.empty(),
                net.ds.metadata(),
                net.ds.createdAt(),
                Instant.now()));
    storage.provider().lines().delete(net.sl.id());
    long before = directory.revision();

    directory.reload(storage.provider());

    assertTrue(directory.revision() > before);
    ServingLine ds = stations.linesServing(net.hhu.id()).get(0);
    assertEquals("东山支线", ds.lineName());
    assertEquals(Optional.of("#123456"), ds.color());
    assertEquals(List.of("SURC:WS"), codes(stations.linesServing(net.ppk.id())), "已删除的线路不再出现");
  }

  @Test
  void dataRevisionIsExposedOnTheApiEntryPoint() {
    try {
      FetaruteApi.initialize(
          null, null, routeApi, null, stations, null, null, null, null, directory::revision);
      FetaruteApi api = FetaruteApi.getInstance();
      assertEquals("1.12.0", api.version());
      long before = api.dataRevision();
      assertEquals(directory.revision(), before);
      directory.reload(storage.provider());
      assertTrue(api.dataRevision() > before);
    } finally {
      FetaruteApi.shutdown();
    }
  }
}
