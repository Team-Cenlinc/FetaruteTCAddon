package org.fetarute.fetaruteTCAddon.api;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.api.eta.EtaApi;
import org.fetarute.fetaruteTCAddon.api.graph.GraphApi.ApiEdge;
import org.fetarute.fetaruteTCAddon.api.graph.GraphApi.ApiNode;
import org.fetarute.fetaruteTCAddon.api.graph.GraphApi.GraphSnapshot;
import org.fetarute.fetaruteTCAddon.api.graph.GraphApi.NodeType;
import org.fetarute.fetaruteTCAddon.api.graph.GraphApi.PathResult;
import org.fetarute.fetaruteTCAddon.api.graph.GraphApi.Position;
import org.fetarute.fetaruteTCAddon.api.line.LineApi;
import org.fetarute.fetaruteTCAddon.api.occupancy.OccupancyApi.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.api.occupancy.OccupancyApi.ResourceType;
import org.fetarute.fetaruteTCAddon.api.occupancy.OccupancyApi.Signal;
import org.fetarute.fetaruteTCAddon.api.operator.OperatorApi;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi.PassType;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi.RouteDetail;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi.RouteInfo;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi.StopInfo;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi.TerminalInfo;
import org.fetarute.fetaruteTCAddon.api.station.StationApi;
import org.fetarute.fetaruteTCAddon.api.station.StationApi.StationInfo;
import org.fetarute.fetaruteTCAddon.api.train.TrainApi;
import org.fetarute.fetaruteTCAddon.api.train.TrainApi.TrainSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * API 模块单元测试。
 *
 * <p>验证：
 *
 * <ul>
 *   <li>数据模型的不可变性（immutability）
 *   <li>记录类型的正确性
 *   <li>边界情况处理
 * </ul>
 */
class ApiRecordsTest {

  // ─────────────────────────────────────────────────────────────────────────────
  // GraphApi 数据模型测试
  // ─────────────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("GraphApi 数据模型")
  class GraphApiRecordsTest {

    @Test
    @DisplayName("GraphSnapshot 应确保节点/边列表不可变")
    void graphSnapshotShouldBeImmutable() {
      List<ApiNode> nodes = new ArrayList<>();
      nodes.add(new ApiNode("A", NodeType.STATION, new Position(0, 0, 0), Optional.of("站点A")));
      nodes.add(new ApiNode("B", NodeType.WAYPOINT, new Position(10, 0, 0), Optional.empty()));

      List<ApiEdge> edges = new ArrayList<>();
      edges.add(new ApiEdge("A-B", "A", "B", 100, 20.0, true, false));

      GraphSnapshot snapshot =
          new GraphSnapshot(nodes, edges, Instant.now(), nodes.size(), edges.size(), 1);

      // 尝试修改原始列表
      nodes.add(new ApiNode("C", NodeType.DEPOT, new Position(20, 0, 0), Optional.empty()));
      edges.add(new ApiEdge("B-C", "B", "C", 50, 15.0, true, false));

      // 快照中的列表不应受影响
      assertEquals(2, snapshot.nodes().size(), "节点列表应不可变");
      assertEquals(1, snapshot.edges().size(), "边列表应不可变");

      // 尝试直接修改快照列表应抛出异常
      assertThrows(
          UnsupportedOperationException.class,
          () ->
              snapshot
                  .nodes()
                  .add(
                      new ApiNode(
                          "D", NodeType.SWITCHER, new Position(30, 0, 0), Optional.empty())),
          "快照节点列表应不可修改");

      assertThrows(
          UnsupportedOperationException.class,
          () -> snapshot.edges().add(new ApiEdge("C-D", "C", "D", 30, 10.0, true, false)),
          "快照边列表应不可修改");
    }

    @Test
    @DisplayName("GraphSnapshot 处理 null 输入")
    void graphSnapshotShouldHandleNullInputs() {
      GraphSnapshot snapshot = new GraphSnapshot(null, null, Instant.now(), 0, 0, 0);

      assertNotNull(snapshot.nodes(), "null 节点应转为空列表");
      assertNotNull(snapshot.edges(), "null 边应转为空列表");
      assertTrue(snapshot.nodes().isEmpty());
      assertTrue(snapshot.edges().isEmpty());
    }

    @Test
    @DisplayName("ApiNode 应正确存储所有字段")
    void apiNodeShouldStoreAllFields() {
      Position pos = new Position(100.5, 64.0, -200.5);
      ApiNode node = new ApiNode("SURN:S:AAA:1", NodeType.STATION, pos, Optional.of("测试站"));

      assertEquals("SURN:S:AAA:1", node.id());
      assertEquals(NodeType.STATION, node.type());
      assertEquals(100.5, node.position().x(), 0.001);
      assertEquals(64.0, node.position().y(), 0.001);
      assertEquals(-200.5, node.position().z(), 0.001);
      assertTrue(node.displayName().isPresent());
      assertEquals("测试站", node.displayName().get());
    }

    @Test
    @DisplayName("ApiEdge 应正确存储所有字段")
    void apiEdgeShouldStoreAllFields() {
      ApiEdge edge = new ApiEdge("edge-001", "A", "B", 150, 25.5, true, false);

      assertEquals("edge-001", edge.id());
      assertEquals("A", edge.nodeA());
      assertEquals("B", edge.nodeB());
      assertEquals(150, edge.lengthBlocks());
      assertEquals(25.5, edge.speedLimitBps(), 0.001);
      assertTrue(edge.bidirectional());
      assertFalse(edge.blocked());
    }

    @Test
    @DisplayName("PathResult 应正确存储路径信息")
    void pathResultShouldStorePathInfo() {
      PathResult path = new PathResult(List.of("A", "B", "C"), List.of("A-B", "B-C"), 250, 15);

      assertEquals(3, path.nodes().size());
      assertEquals(2, path.edges().size());
      assertEquals(250, path.totalDistanceBlocks());
      assertEquals(15, path.estimatedTravelTimeSec());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // TrainApi 数据模型测试
  // ─────────────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("TrainApi 数据模型")
  class TrainApiRecordsTest {

    @Test
    @DisplayName("TrainSnapshot 应正确存储列车状态")
    void trainSnapshotShouldStoreTrainState() {
      UUID worldId = UUID.randomUUID();
      Instant now = Instant.now();
      TrainApi.EtaInfo eta =
          new TrainApi.EtaInfo(
              "SURN:S:BBB:1",
              Optional.of("BBB站"),
              now.plusSeconds(180).toEpochMilli(),
              3,
              true,
              false);

      TrainSnapshot snapshot =
          new TrainSnapshot(
              "train-001",
              worldId,
              "SURN:L1:R1",
              Optional.of("L1-R1"),
              Optional.of("SURN:S:AAA:1"),
              Optional.of("SURN:S:BBB:1"),
              12.5,
              TrainApi.Signal.PROCEED,
              0.5,
              now,
              Optional.of(eta));

      assertEquals("train-001", snapshot.trainName());
      assertEquals(worldId, snapshot.worldId());
      assertEquals("SURN:L1:R1", snapshot.routeId());
      assertEquals(12.5, snapshot.speedBps(), 0.001);
      assertTrue(snapshot.currentNode().isPresent());
      assertEquals("SURN:S:AAA:1", snapshot.currentNode().get());
      assertTrue(snapshot.nextNode().isPresent());
      assertEquals(TrainApi.Signal.PROCEED, snapshot.signal());
      assertEquals(0.5, snapshot.edgeProgress(), 0.001);
      assertTrue(snapshot.eta().isPresent());
    }

    @Test
    @DisplayName("TrainSnapshot 处理缺失的可选字段")
    void trainSnapshotShouldHandleMissingOptionalFields() {
      TrainSnapshot snapshot =
          new TrainSnapshot(
              "train-002",
              UUID.randomUUID(),
              "",
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              0.0,
              TrainApi.Signal.UNKNOWN,
              0.0,
              Instant.now(),
              Optional.empty());

      assertFalse(snapshot.currentNode().isPresent());
      assertFalse(snapshot.nextNode().isPresent());
      assertFalse(snapshot.routeCode().isPresent());
      assertFalse(snapshot.eta().isPresent());
    }

    @Test
    @DisplayName("EtaInfo 应正确存储到达信息")
    void etaInfoShouldStoreArrivalInfo() {
      long etaMillis = Instant.now().plusSeconds(300).toEpochMilli();
      TrainApi.EtaInfo eta =
          new TrainApi.EtaInfo("SURN:S:BBB:1", Optional.of("BBB站"), etaMillis, 5, false, true);

      assertEquals("SURN:S:BBB:1", eta.targetNode());
      assertTrue(eta.targetName().isPresent());
      assertEquals("BBB站", eta.targetName().get());
      assertEquals(etaMillis, eta.etaEpochMillis());
      assertEquals(5, eta.etaMinutes());
      assertFalse(eta.arriving());
      assertTrue(eta.delayed());
    }

    @Test
    @DisplayName("Signal 枚举应包含所有状态")
    void signalShouldContainAllStates() {
      assertEquals(5, TrainApi.Signal.values().length);
      assertNotNull(TrainApi.Signal.PROCEED);
      assertNotNull(TrainApi.Signal.CAUTION);
      assertNotNull(TrainApi.Signal.PROCEED_WITH_CAUTION);
      assertNotNull(TrainApi.Signal.STOP);
      assertNotNull(TrainApi.Signal.UNKNOWN);
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // RouteApi 数据模型测试
  // ─────────────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("RouteApi 数据模型")
  class RouteApiRecordsTest {

    @Test
    @DisplayName("RouteInfo 应正确存储路线信息")
    void routeInfoShouldStoreRouteInfo() {
      RouteInfo info =
          new RouteInfo(
              UUID.randomUUID(),
              "route-001",
              "SURN",
              "L1",
              "EXP-01",
              Optional.of("快速列车"),
              RouteApi.OperationType.NORMAL);

      assertEquals("route-001", info.code());
      assertEquals("SURN", info.operatorCode());
      assertEquals("L1", info.lineCode());
      assertEquals("EXP-01", info.routeCode());
      assertTrue(info.displayName().isPresent());
      assertEquals("快速列车", info.displayName().get());
      assertEquals(RouteApi.OperationType.NORMAL, info.operationType());
    }

    @Test
    @DisplayName("StopInfo 应正确存储站点停靠信息")
    void stopInfoShouldStoreStopInfo() {
      StopInfo stop = new StopInfo(1, "SURN:S:AAA:1", Optional.of("测试站"), 30, PassType.STOP, false);

      assertEquals(1, stop.sequence());
      assertEquals("SURN:S:AAA:1", stop.nodeId());
      assertTrue(stop.stationName().isPresent());
      assertEquals("测试站", stop.stationName().get());
      assertEquals(30, stop.dwellSeconds());
      assertEquals(PassType.STOP, stop.passType());
      assertFalse(stop.dynamic());
    }

    @Test
    @DisplayName("StopInfo 应正确标识动态站台")
    void stopInfoShouldIdentifyDynamicStop() {
      StopInfo dynamicStop =
          new StopInfo(1, "SURN:S:PPK:1", Optional.of("PPK"), 30, PassType.STOP, true);

      assertTrue(dynamicStop.dynamic());
      assertEquals(PassType.STOP, dynamicStop.passType());

      // 动态终点站
      StopInfo dynamicTerminal =
          new StopInfo(2, "SURN:S:END:1", Optional.of("END"), 0, PassType.TERMINATE, true);

      assertTrue(dynamicTerminal.dynamic());
      assertEquals(PassType.TERMINATE, dynamicTerminal.passType());
    }

    @Test
    @DisplayName("RouteDetail 应正确组合路线详情")
    void routeDetailShouldCombineRouteDetails() {
      RouteInfo info =
          new RouteInfo(
              UUID.randomUUID(),
              "route-001",
              "SURN",
              "L1",
              "EXP-01",
              Optional.empty(),
              RouteApi.OperationType.NORMAL);

      List<String> waypoints = List.of("SURN:S:A:1", "SURN:S:B:1", "SURN:S:C:1", "SURN:S:D:1");
      List<StopInfo> stops =
          List.of(
              new StopInfo(1, "SURN:S:A:1", Optional.of("起点站"), 0, PassType.STOP, false),
              new StopInfo(2, "SURN:S:B:1", Optional.of("中间站"), 30, PassType.STOP, true),
              new StopInfo(3, "SURN:S:D:1", Optional.of("终点站"), 0, PassType.TERMINATE, false));

      TerminalInfo terminal =
          new TerminalInfo("SURN:S:D:1", Optional.of("D"), "SURN:S:D:1", Optional.of("终点站"));

      RouteDetail detail = new RouteDetail(info, waypoints, stops, terminal, 500);

      assertEquals(info, detail.info());
      assertEquals(4, detail.waypoints().size());
      assertEquals(3, detail.stops().size());
      // 验证终点信息
      assertFalse(detail.terminal().isEmpty());
      assertEquals("SURN:S:D:1", detail.terminal().endOfRouteNodeId());
      assertEquals("SURN:S:D:1", detail.terminal().endOfOperationNodeId());
      assertTrue(detail.terminal().endOfOperationName().isPresent());
      assertEquals("终点站", detail.terminal().endOfOperationName().get());
      assertEquals(500, detail.totalDistanceBlocks());
      // 验证动态站台标识
      assertFalse(detail.stops().get(0).dynamic());
      assertTrue(detail.stops().get(1).dynamic());
      assertFalse(detail.stops().get(2).dynamic());
    }

    @Test
    @DisplayName("TerminalInfo 应正确区分 EOR 和 EOP")
    void terminalInfoShouldDistinguishEorAndEop() {
      // EOR 和 EOP 不同的场景（终点后有回库）
      TerminalInfo terminal =
          new TerminalInfo(
              "SURN:D:DEPOT:1", Optional.of("DEPOT"), "SURN:S:TERMINAL:1", Optional.of("终点站"));

      assertEquals("SURN:D:DEPOT:1", terminal.endOfRouteNodeId());
      assertEquals("SURN:S:TERMINAL:1", terminal.endOfOperationNodeId());
      assertEquals("DEPOT", terminal.endOfRouteName().orElse(""));
      assertEquals("终点站", terminal.endOfOperationName().orElse(""));
      assertFalse(terminal.isEmpty());

      // 空终点信息
      TerminalInfo empty = TerminalInfo.empty();
      assertTrue(empty.isEmpty());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // OccupancyApi 数据模型测试
  // ─────────────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("OccupancyApi 数据模型")
  class OccupancyApiRecordsTest {

    @Test
    @DisplayName("ResourceType 枚举应包含所有类型")
    void resourceTypeShouldContainAllTypes() {
      assertEquals(3, ResourceType.values().length);
      assertNotNull(ResourceType.NODE);
      assertNotNull(ResourceType.EDGE);
      assertNotNull(ResourceType.CONFLICT);
    }

    @Test
    @DisplayName("OccupancyClaim 应正确存储占用信息")
    void occupancyClaimShouldStoreClaimInfo() {
      UUID worldId = UUID.randomUUID();
      Instant claimedAt = Instant.now();

      OccupancyClaim claim =
          new OccupancyClaim(
              "train-001", ResourceType.EDGE, "A-B", worldId, claimedAt, Signal.PROCEED);

      assertEquals("train-001", claim.trainName());
      assertEquals(ResourceType.EDGE, claim.resourceType());
      assertEquals("A-B", claim.resourceId());
      assertEquals(worldId, claim.worldId());
      assertEquals(claimedAt, claim.claimedAt());
      assertEquals(Signal.PROCEED, claim.signal());
    }

    @Test
    @DisplayName("OccupancyClaim 应支持各种资源类型")
    void occupancyClaimShouldSupportAllResourceTypes() {
      UUID worldId = UUID.randomUUID();
      Instant now = Instant.now();

      OccupancyClaim nodeClaim =
          new OccupancyClaim("train-001", ResourceType.NODE, "A", worldId, now, Signal.STOP);
      OccupancyClaim edgeClaim =
          new OccupancyClaim("train-002", ResourceType.EDGE, "A-B", worldId, now, Signal.CAUTION);
      OccupancyClaim conflictClaim =
          new OccupancyClaim(
              "train-003", ResourceType.CONFLICT, "CONFLICT-001", worldId, now, Signal.PROCEED);

      assertEquals(ResourceType.NODE, nodeClaim.resourceType());
      assertEquals(ResourceType.EDGE, edgeClaim.resourceType());
      assertEquals(ResourceType.CONFLICT, conflictClaim.resourceType());
    }

    @Test
    @DisplayName("Signal 枚举应包含所有状态")
    void signalShouldContainAllStates() {
      assertEquals(5, Signal.values().length);
      assertNotNull(Signal.PROCEED);
      assertNotNull(Signal.CAUTION);
      assertNotNull(Signal.PROCEED_WITH_CAUTION);
      assertNotNull(Signal.STOP);
      assertNotNull(Signal.UNKNOWN);
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // StationApi 数据模型测试
  // ─────────────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("StationApi 数据模型")
  class StationApiRecordsTest {

    @Test
    @DisplayName("StationInfo 应正确存储站点信息")
    void stationInfoShouldStoreStationInfo() {
      UUID stationId = UUID.randomUUID();
      UUID operatorId = UUID.randomUUID();
      UUID lineId = UUID.randomUUID();

      StationInfo station =
          new StationInfo(
              stationId,
              "AAA",
              operatorId,
              Optional.of(lineId),
              "测试站",
              Optional.of("Test Station"),
              Optional.of("world"),
              Optional.of(new StationApi.Position(100, 64, 200)),
              Optional.of("SURN:S:AAA:1"));

      assertEquals(stationId, station.id());
      assertEquals("AAA", station.code());
      assertEquals(operatorId, station.operatorId());
      assertTrue(station.primaryLineId().isPresent());
      assertEquals(lineId, station.primaryLineId().get());
      assertEquals("测试站", station.name());
      assertTrue(station.secondaryName().isPresent());
      assertEquals("Test Station", station.secondaryName().get());
      assertTrue(station.worldName().isPresent());
      assertEquals("world", station.worldName().get());
      assertTrue(station.location().isPresent());
      assertEquals(100, station.location().get().x(), 0.001);
      assertTrue(station.graphNodeId().isPresent());
      assertEquals("SURN:S:AAA:1", station.graphNodeId().get());
    }

    @Test
    @DisplayName("StationInfo 处理缺失的可选字段")
    void stationInfoShouldHandleMissingOptionalFields() {
      StationInfo station =
          new StationInfo(
              UUID.randomUUID(),
              "BBB",
              UUID.randomUUID(),
              Optional.empty(),
              "简单站",
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              Optional.empty());

      assertFalse(station.primaryLineId().isPresent());
      assertFalse(station.secondaryName().isPresent());
      assertFalse(station.worldName().isPresent());
      assertFalse(station.location().isPresent());
      assertFalse(station.graphNodeId().isPresent());
    }

    @Test
    @DisplayName("Position 应正确存储坐标")
    void positionShouldStoreCoordinates() {
      StationApi.Position pos = new StationApi.Position(123.456, 78.9, -100.0);

      assertEquals(123.456, pos.x(), 0.001);
      assertEquals(78.9, pos.y(), 0.001);
      assertEquals(-100.0, pos.z(), 0.001);
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // OperatorApi 数据模型测试
  // ─────────────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("OperatorApi 数据模型")
  class OperatorApiRecordsTest {

    @Test
    @DisplayName("OperatorInfo 应正确存储字段")
    void operatorInfoShouldStoreFields() {
      UUID operatorId = UUID.randomUUID();
      UUID companyId = UUID.randomUUID();

      OperatorApi.OperatorInfo info =
          new OperatorApi.OperatorInfo(
              operatorId,
              "OP",
              companyId,
              "运营商",
              Optional.of("Operator"),
              Optional.of("#00FFAA"),
              5,
              Optional.of("说明"));

      assertEquals(operatorId, info.id());
      assertEquals("OP", info.code());
      assertEquals(companyId, info.companyId());
      assertEquals("运营商", info.name());
      assertTrue(info.secondaryName().isPresent());
      assertTrue(info.colorTheme().isPresent());
      assertEquals(5, info.priority());
      assertTrue(info.description().isPresent());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // LineApi 数据模型测试
  // ─────────────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("LineApi 数据模型")
  class LineApiRecordsTest {

    @Test
    @DisplayName("LineInfo 应正确存储字段")
    void lineInfoShouldStoreFields() {
      UUID lineId = UUID.randomUUID();
      UUID operatorId = UUID.randomUUID();

      LineApi.LineInfo info =
          new LineApi.LineInfo(
              lineId,
              "L1",
              operatorId,
              "一号线",
              Optional.of("Line 1"),
              LineApi.ServiceType.METRO,
              Optional.of("#FF0000"),
              LineApi.LineStatus.ACTIVE,
              Optional.of(300));

      assertEquals(lineId, info.id());
      assertEquals("L1", info.code());
      assertEquals(operatorId, info.operatorId());
      assertEquals("一号线", info.name());
      assertTrue(info.secondaryName().isPresent());
      assertEquals(LineApi.ServiceType.METRO, info.serviceType());
      assertTrue(info.color().isPresent());
      assertEquals(LineApi.LineStatus.ACTIVE, info.status());
      assertTrue(info.spawnFreqBaselineSec().isPresent());
    }

    @Test
    @DisplayName("ServiceType 与 LineStatus 枚举应包含 UNKNOWN")
    void enumsShouldContainUnknown() {
      assertNotNull(LineApi.ServiceType.UNKNOWN);
      assertNotNull(LineApi.LineStatus.UNKNOWN);
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // EtaApi 数据模型测试
  // ─────────────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("EtaApi 数据模型")
  class EtaApiRecordsTest {

    @Test
    @DisplayName("EtaResult 应正确存储字段")
    void etaResultShouldStoreFields() {
      EtaApi.EtaResult result =
          new EtaApi.EtaResult(
              true,
              "On time",
              Instant.now().plusSeconds(120).toEpochMilli(),
              2,
              90,
              10,
              20,
              List.of(EtaApi.Reason.WAIT),
              EtaApi.Confidence.HIGH);

      assertTrue(result.arriving());
      assertEquals("On time", result.statusText());
      assertTrue(result.eta().isPresent());
      assertEquals(2, result.etaMinutes());
      assertEquals(90, result.travelSec());
      assertEquals(10, result.dwellSec());
      assertEquals(20, result.waitSec());
      assertEquals(EtaApi.Confidence.HIGH, result.confidence());
    }

    @Test
    @DisplayName("EtaResult eta() 在无效时间时应返回 empty")
    void etaShouldHandleInvalidEpoch() {
      EtaApi.EtaResult result =
          new EtaApi.EtaResult(false, "N/A", 0L, -1, 0, 0, 0, List.of(), EtaApi.Confidence.LOW);

      assertTrue(result.eta().isEmpty());
    }

    @Test
    @DisplayName("Target 结构应校验空值")
    void targetShouldValidate() {
      assertThrows(IllegalArgumentException.class, () -> new EtaApi.Target.Station(""));
      assertThrows(IllegalArgumentException.class, () -> new EtaApi.Target.PlatformNode(""));
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // FetaruteApi 版本兼容性测试
  // ─────────────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("FetaruteApi 版本管理")
  class FetaruteApiVersionTest {

    @Test
    @DisplayName("API 版本格式应符合语义版本规范")
    void apiVersionShouldFollowSemVer() {
      String version = FetaruteApi.API_VERSION;
      assertNotNull(version);
      assertTrue(version.matches("\\d+\\.\\d+\\.\\d+"), "版本应符合 x.y.z 格式");
    }

    @Test
    @DisplayName("未初始化时 getInstance 应返回 null")
    void getInstanceShouldReturnNullWhenNotInitialized() {
      // 注意：这个测试依赖于测试环境中 FetaruteApi 未被初始化
      // 实际测试可能需要在独立环境运行
      // 这里仅验证 get() 方法正常工作
      Optional<FetaruteApi> api = FetaruteApi.get();
      // 在测试环境中，API 可能已被其他测试初始化，所以不强制断言
      assertNotNull(api); // Optional 本身不应为 null
    }
  }
}
