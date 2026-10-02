package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.storage.SampleTransitNetwork;
import org.fetarute.fetaruteTCAddon.storage.TransitTestStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 出库车的初始线路标签。
 *
 * <p>直通运转的起步 CHANGE 写在定义书第一站之前，存进首站备注；CHANGE 是“抵达该站后”执行的，出库车没有抵达首站，所以出车时就要把标签写成目标线路，
 * 交路自身的线路（管理归属）不变。
 */
class TrainCartsDepotSpawnerStartLineTest {

  private static final Instant NOW = Instant.parse("2026-09-29T00:00:00Z");

  @TempDir Path dir;
  private TransitTestStorage storage;
  private SampleTransitNetwork net;
  private Line mt;
  private int routeCounter;

  @BeforeEach
  void setUp() throws Exception {
    storage = TransitTestStorage.open(dir);
    net = new SampleTransitNetwork(storage);
    mt = storage.line(net.surcOp, "MT", "地铁线", "#123456");
  }

  @AfterEach
  void tearDown() {
    storage.close();
  }

  private Map<String, String> tagsOfRouteWithFirstStop(String firstStopNotes) {
    // 一个用例里会造多条交路，交路代码在同一线路下唯一
    String routeCode = "MT-3N_DPExp" + (routeCounter++ == 0 ? "" : "_" + routeCounter);
    Route route =
        storage.route(mt, routeCode, RoutePatternType.RAPID, RouteOperationType.OPERATION);
    storage.stops(
        route,
        new String[] {"STOP", "SURC:S:KPO:1", firstStopNotes},
        new String[] {"STOP", "SURC:S:HHU:1", "CHANGE:SURC:MT"},
        new String[] {"TERMINATE", "SURC:S:WYB:1"});
    SpawnService service =
        new SpawnService(
            new SpawnServiceKey(route.id()),
            net.surc.id(),
            "SURC",
            net.surcOp.id(),
            "SURC",
            mt.id(),
            "MT",
            route.id(),
            route.code(),
            Duration.ofSeconds(600),
            "SURC:D:LWN:1");
    return TrainCartsDepotSpawner.spawnTags(
        UUID.randomUUID(),
        service,
        NodeId.of("SURC:D:LWN:1"),
        "pattern",
        route,
        storage.provider(),
        NOW);
  }

  @Test
  void firstStopChangeBecomesTheSpawnLineTags() {
    Map<String, String> tags = tagsOfRouteWithFirstStop("CHANGE:SURC:WS");
    assertEquals("WS", tags.get("FTA_LINE_CODE"));
    assertEquals("SURC", tags.get("FTA_OPERATOR_CODE"));
    assertEquals("MT-3N_DPExp", tags.get("FTA_ROUTE_CODE"), "交路代码仍是交路自身的，管理归属不变");
    assertEquals("SURC:D:LWN:1", tags.get("FTA_DEPOT_ID"));
  }

  @Test
  void firstStopChangeMayCrossOperators() {
    Map<String, String> tags = tagsOfRouteWithFirstStop("CHANGE:FTA:SL");
    assertEquals("SL", tags.get("FTA_LINE_CODE"));
    assertEquals("FTA", tags.get("FTA_OPERATOR_CODE"));
  }

  @Test
  void firstStopChangeAmongOtherNoteLinesIsFound() {
    Map<String, String> tags = tagsOfRouteWithFirstStop("DYNAMIC:SURC:S:KPO:[1:2]\nCHANGE:SURC:WS");
    assertEquals("WS", tags.get("FTA_LINE_CODE"));
  }

  @Test
  void routesWithoutAFirstStopChangeKeepTheirOwnLine() {
    // 首站没有 CHANGE（HHU 处的 CHANGE 在第二站，抵达后才执行）
    Map<String, String> none = tagsOfRouteWithFirstStop("DYNAMIC:SURC:S:KPO:[1:2]");
    assertEquals("MT", none.get("FTA_LINE_CODE"));
    assertEquals("SURC", none.get("FTA_OPERATOR_CODE"));
    // 首站 CHANGE 与交路同线
    Map<String, String> same = tagsOfRouteWithFirstStop("CHANGE:SURC:MT");
    assertEquals("MT", same.get("FTA_LINE_CODE"));
    assertEquals("SURC", same.get("FTA_OPERATOR_CODE"));
    // 格式错误的 CHANGE 运行时不执行，出车也保持交路自身的线路
    for (String malformed : new String[] {"CHANGE:SURC", "CHANGE::WS"}) {
      Map<String, String> tags = tagsOfRouteWithFirstStop(malformed);
      assertEquals("MT", tags.get("FTA_LINE_CODE"), malformed);
      assertEquals("SURC", tags.get("FTA_OPERATOR_CODE"), malformed);
    }
  }
}
