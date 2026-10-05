package org.fetarute.fetaruteTCAddon.api.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi.LineRef;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi.StopInfo;
import org.fetarute.fetaruteTCAddon.api.station.StationApi;
import org.fetarute.fetaruteTCAddon.api.station.StationApi.ServingLine;
import org.fetarute.fetaruteTCAddon.api.train.TrainApi;
import org.fetarute.fetaruteTCAddon.api.train.TrainApi.TrainSnapshot;
import org.fetarute.fetaruteTCAddon.company.api.StationDirectory;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainRuntimeSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainSnapshotStore;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLineChanges;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.storage.SampleTransitNetwork;
import org.fetarute.fetaruteTCAddon.storage.TransitTestStorage;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 直通运转（CHANGE）与回库（1.7.0）：停靠表标出换线站，列车快照给出当前线路与是否退出服务， 停靠线路按列车在该站所属的线路统计。 */
class ThroughServiceApiTest {

  @TempDir Path dir;
  private TransitTestStorage storage;
  private SampleTransitNetwork net;
  private RouteDefinitionCache routes;
  private StationDirectory directory;
  private RouteApi routeApi;
  private StationApi stations;
  private TrainSnapshotStore snapshots;
  private TrainApi trains;

  /** WS 交路在 PPK 起直通 DS（指令写成小写，与主数据大小写不同）：KPO 停 → PPK 停（换线）→ HHU:1 停 → WYB:1 终到。 */
  private Route wsThrough;

  /** DS 线的交路，起步即按 WS 运营（首站备注 CHANGE）：KPO:3 停 → WYB:3 终到。 */
  private Route dsStart;

  /** WS 交路里写了一条不存在的线路：KPO 停 → PPK 停（CHANGE:SURC:XX）→ HHU:3 停 → WYB:2 终到。 */
  private Route wsUnknown;

  @BeforeEach
  void setUp() throws Exception {
    storage = TransitTestStorage.open(dir);
    net = new SampleTransitNetwork(storage);
    wsThrough =
        storage.route(net.ws, "WS-DS", RoutePatternType.LOCAL, RouteOperationType.OPERATION);
    storage.stops(
        wsThrough,
        new String[] {"STOP", "SURC:S:KPO:1", "CHANGE:SURC:WS"},
        new String[] {"STOP", "SURC:S:PPK:1", "CHANGE:surc:ds"},
        new String[] {"STOP", "SURC:S:HHU:1"},
        new String[] {"TERMINATE", "SURC:S:WYB:1"});
    dsStart =
        storage.route(net.ds, "DS-START", RoutePatternType.LOCAL, RouteOperationType.OPERATION);
    storage.stops(
        dsStart,
        new String[] {"STOP", "SURC:S:KPO:3", "CHANGE:SURC:WS"},
        new String[] {"TERMINATE", "SURC:S:WYB:3"});
    wsUnknown =
        storage.route(net.ws, "WS-XX", RoutePatternType.LOCAL, RouteOperationType.OPERATION);
    storage.stops(
        wsUnknown,
        new String[] {"STOP", "SURC:S:KPO:2"},
        new String[] {"STOP", "SURC:S:PPK:2", "CHANGE:SURC:XX"},
        new String[] {"STOP", "SURC:S:HHU:3"},
        new String[] {"TERMINATE", "SURC:S:WYB:2"});
    StorageProvider provider = storage.provider();
    routes = new RouteDefinitionCache(message -> {});
    directory = new StationDirectory(routes, message -> {});
    routes.reload(provider);
    directory.reload(provider);
    routeApi = new RouteApiImpl(routes, directory);
    stations =
        new StationApiImpl(
            provider.stations(), provider.companies(), provider.operators(), directory);
    snapshots = new TrainSnapshotStore();
    trains = new TrainApiImpl(snapshots, new RouteProgressRegistry(), routes, null, directory);
  }

  @AfterEach
  void tearDown() {
    storage.close();
  }

  private static List<String> codes(List<ServingLine> lines) {
    return lines.stream().map(line -> line.operatorCode() + ":" + line.lineCode()).toList();
  }

  private void sample(
      String trainName, Route route, int routeIndex, String operatorTag, String lineTag) {
    snapshots.update(
        trainName,
        new TrainRuntimeSnapshot(
            1L,
            Instant.parse("2026-09-27T00:00:00Z"),
            UUID.randomUUID(),
            route.id(),
            routes.findById(route.id()).orElseThrow().id(),
            routeIndex,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            OptionalDouble.empty(),
            OptionalInt.empty(),
            OptionalInt.empty(),
            OptionalDouble.empty(),
            TrainRuntimeSnapshot.HoldTimeline.EMPTY,
            RouteLineChanges.LineRef.of(operatorTag, lineTag)));
  }

  @Test
  void stopInfoMarksOnlyTheRealChangeStation() {
    List<StopInfo> stops = routeApi.getRoute(wsThrough.id()).orElseThrow().stops();

    assertEquals(Optional.empty(), stops.get(0).lineChange(), "写了本线的 CHANGE 不算换线");
    assertEquals(Optional.of(new LineRef("SURC", "DS")), stops.get(1).lineChange(), "代码按主数据的写法给出");
    assertEquals(Optional.empty(), stops.get(2).lineChange());
    assertEquals(Optional.empty(), stops.get(3).lineChange());

    List<StopInfo> unknown = routeApi.getRoute(wsUnknown.id()).orElseThrow().stops();
    assertEquals(Optional.of(new LineRef("SURC", "XX")), unknown.get(1).lineChange(), "线路不存在时原样给出");
  }

  @Test
  void routesWithoutChangeHaveNoLineChange() {
    assertTrue(
        routeApi.getRoute(net.ws1.id()).orElseThrow().stops().stream()
            .allMatch(stop -> stop.lineChange().isEmpty()));
  }

  @Test
  void servingLinesCountStationsAfterTheChangeForTheNewLine() {
    // HHU：WS-1 只通过、DS-R 终到；直通车以 DS 身份停靠，换到不存在线路的车也不再是 WS——都不能把 WS 算进来。
    assertEquals(List.of("SURC:DS"), codes(stations.linesServing(net.hhu.id())));
    // 换线站：以 WS 到达、以 DS 发车，两条都算。
    assertTrue(
        codes(stations.linesServing(net.ppk.id())).containsAll(List.of("SURC:WS", "SURC:DS")));
    // 换线前的车站只有 WS。
    assertEquals(List.of("SURC:WS"), codes(stations.linesServing(net.kpo.id())));
  }

  @Test
  void trainSnapshotCarriesTheTrainsConsist() {
    snapshots.update(
        "t-consist",
        new TrainRuntimeSnapshot(
            1L,
            Instant.parse("2026-09-27T00:00:00Z"),
            UUID.randomUUID(),
            wsThrough.id(),
            routes.findById(wsThrough.id()).orElseThrow().id(),
            2,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            OptionalDouble.empty(),
            OptionalInt.empty(),
            OptionalInt.empty(),
            OptionalDouble.empty(),
            TrainRuntimeSnapshot.HoldTimeline.EMPTY,
            Optional.empty(),
            Optional.empty(),
            TrainRuntimeSnapshot.Motion.NONE,
            Optional.of("sh_a8")));
    assertEquals(
        Optional.of("sh_a8"), trains.getTrainSnapshot("t-consist").orElseThrow().consist());

    sample("t-plain", wsThrough, 2, null, null);
    assertTrue(trains.getTrainSnapshot("t-plain").orElseThrow().consist().isEmpty(), "车上没有车型标签");
  }

  @Test
  void trainSnapshotFollowsLineTagsThenTheRouteLine() {
    sample("t-tag", wsThrough, 2, "surc", "ds");
    TrainSnapshot tagged = trains.getTrainSnapshot("t-tag").orElseThrow();
    assertEquals(Optional.of("SURC"), tagged.operatorCode());
    assertEquals(Optional.of("DS"), tagged.lineCode(), "标签里的小写代码换成主数据的写法");
    assertEquals("SURC:WS:WS-DS", tagged.routeId(), "交路代码仍是交路本身的线路（管理归属）");

    // 没有线路标签：取交路本身的线路，不按进度去猜（运行时执行 CHANGE 必然写标签）。
    sample("t-untagged", wsThrough, 2, null, null);
    TrainSnapshot untagged = trains.getTrainSnapshot("t-untagged").orElseThrow();
    assertEquals(Optional.of("SURC"), untagged.operatorCode());
    assertEquals(Optional.of("WS"), untagged.lineCode());
  }

  @Test
  void trainSnapshotAtTheStartShowsTheFirstStopChangeLine() {
    // 首站的 CHANGE 是起步线路：出车（或折返复用）按 entryLine 写下标签，起点的快照就显示目标线路
    List<RouteStop> stops = storage.provider().routeStops().listByRoute(dsStart.id());
    RouteLineChanges.LineRef spawnLine =
        RouteLineChanges.entryLine(stops, 0, new RouteLineChanges.LineRef("SURC", "DS"));
    sample("t-start", dsStart, 0, spawnLine.operatorCode(), spawnLine.lineCode());

    TrainSnapshot atStart = trains.getTrainSnapshot("t-start").orElseThrow();
    assertEquals(Optional.of("SURC"), atStart.operatorCode());
    assertEquals(Optional.of("WS"), atStart.lineCode(), "起点按 WS 对乘客运营");
    assertEquals("SURC:DS:DS-START", atStart.routeId(), "交路与管理归属仍是 DS");
    // 交路 API 给出的起点线路与快照一致：首站标出起步线路（列车从来不是以 DS 到达首站的）
    List<StopInfo> routeStops = routeApi.getRoute(dsStart.id()).orElseThrow().stops();
    assertEquals(Optional.of(new LineRef("SURC", "WS")), routeStops.get(0).lineChange());
    assertEquals(Optional.empty(), routeStops.get(1).lineChange());
    // 首站只按目标线路统计停靠线路，DS 不会因为交路归属 DS 就算进 KPO
    assertEquals(List.of("SURC:WS"), codes(stations.linesServing(net.kpo.id())));
  }

  @Test
  void trainSnapshotReportsOutOfServiceOnlyPastTheEndOfOperation() {
    // DS-R（回库）：WYB:2 停 → HHU:2 终到（运营终点）→ LWN 车库
    sample("r-0", net.dsR, 0, "SURC", "DS");
    sample("r-1", net.dsR, 1, "SURC", "DS");
    sample("r-2", net.dsR, 2, "SURC", "DS");
    assertFalse(trains.getTrainSnapshot("r-0").orElseThrow().outOfService());
    assertFalse(trains.getTrainSnapshot("r-1").orElseThrow().outOfService(), "停在运营终点仍载客");
    assertTrue(trains.getTrainSnapshot("r-2").orElseThrow().outOfService());

    // 整趟没有载客车站的回库交路：从一开始就是回库
    Route deadhead =
        storage.route(net.ds, "DS-D", RoutePatternType.LOCAL, RouteOperationType.RETURN);
    storage.stops(
        deadhead, new String[] {"PASS", "SURC:S:HHU:4"}, new String[] {"PASS", "SURC:D:LWN:1"});
    routes.reload(storage.provider());
    sample("d-0", deadhead, 0, "SURC", "DS");
    assertTrue(trains.getTrainSnapshot("d-0").orElseThrow().outOfService(), "没有运营终点：全程回库");

    // 运营交路与出库交路恒为 false
    sample("w-last", net.ws1, 7, "SURC", "WS");
    sample("s-last", net.sl1, 1, "FTA", "SL");
    assertFalse(trains.getTrainSnapshot("w-last").orElseThrow().outOfService());
    assertFalse(trains.getTrainSnapshot("s-last").orElseThrow().outOfService());
  }

  @Test
  void oldSnapshotConstructorKeepsDefaults() {
    TrainSnapshot snapshot =
        new TrainSnapshot(
            "t",
            UUID.randomUUID(),
            "SURC:WS:WS-1",
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            0.0,
            TrainApi.Signal.UNKNOWN,
            0.5,
            Instant.EPOCH,
            Optional.empty());
    assertEquals(Optional.empty(), snapshot.operatorCode());
    assertEquals(Optional.empty(), snapshot.lineCode());
    assertFalse(snapshot.outOfService());

    StopInfo stop =
        new StopInfo(
            0, "SURC:S:KPO:1", Optional.empty(), 0, RouteApi.PassType.STOP, false, null, null);
    assertEquals(Optional.empty(), stop.lineChange());
    assertEquals(Optional.empty(), stop.stationId(), "null 规整为空");
  }
}
