package org.fetarute.fetaruteTCAddon.display.pids.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.api.graph.GraphApi;
import org.fetarute.fetaruteTCAddon.api.line.LineApi;
import org.fetarute.fetaruteTCAddon.api.operator.OperatorApi;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi.PassType;
import org.fetarute.fetaruteTCAddon.api.station.StationApi;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsDirectory.LineStyle;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsDirectory.RouteStop;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Names;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 公开 API 名称目录：索引建好前为空；站台线路由停靠表推出，换线站两条线都算。 */
class ApiPidsDirectoryTest {

  private static final UUID SURC = UUID.randomUUID();
  private static final UUID HHU = UUID.randomUUID();
  private static final UUID TPC = UUID.randomUUID();
  private static final PidsStationKey HHU_KEY = new PidsStationKey("SURC", "HHU");

  private final OperatorApi operators = mock(OperatorApi.class);
  private final LineApi lines = mock(LineApi.class);
  private final StationApi stations = mock(StationApi.class);
  private final RouteApi routes = mock(RouteApi.class);
  private final List<String> warnings = new ArrayList<>();
  private final UUID rapid = UUID.randomUUID();
  private final UUID local = UUID.randomUUID();
  private ApiPidsDirectory directory;

  @BeforeEach
  void setUp() {
    when(operators.listAllOperators())
        .thenReturn(
            List.of(
                new OperatorApi.OperatorInfo(
                    SURC,
                    "SURC",
                    UUID.randomUUID(),
                    "南城",
                    Optional.empty(),
                    Optional.of("#336699"),
                    0,
                    Optional.empty())));
    when(lines.listAllLines())
        .thenReturn(
            List.of(
                line("MT", "大都会线", Optional.of("#D920D9")),
                line("WS", "浦蓝线", Optional.of("70deee")),
                line("DS", "探索线", Optional.empty())));
    when(stations.listAllStations())
        .thenReturn(List.of(station(HHU, "HHU", "新笛矢·壑湖"), station(TPC, "TPC", "大港城")));
    when(stations.linesServing(HHU)).thenReturn(List.of(serving("MT"), serving("WS")));
    when(stations.linesServing(TPC)).thenReturn(List.of());
    when(routes.listRoutes())
        .thenReturn(
            List.of(
                route(rapid, "MT", "MT-3O_DPExp", RouteApi.OperationType.RAPID),
                route(local, "DS", "DS-1F_Full", RouteApi.OperationType.LOCAL)));
    // MT-3O 在壑湖 3 站台换成 WS，之后到大港城 1 站台；壑湖 2 站台只是通过
    when(routes.getRoute(rapid))
        .thenReturn(
            Optional.of(
                detail(
                    stop(0, "SURC:D:LWN:1", PassType.STOP, Optional.empty()),
                    stop(1, "SURC:S:HHU:2", PassType.PASS, Optional.empty()),
                    stop(
                        2,
                        "SURC:S:HHU:3",
                        PassType.STOP,
                        Optional.of(new RouteApi.LineRef("SURC", "WS"))),
                    stop(3, "SURC:S:TPC:1:01", PassType.PASS, Optional.empty()),
                    stop(4, "SURC:S:TPC:1", PassType.TERMINATE, Optional.empty()))));
    when(routes.getRoute(local))
        .thenReturn(Optional.of(detail(stop(0, "SURC:S:HHU:3", PassType.STOP, Optional.empty()))));
    directory = new ApiPidsDirectory(operators, lines, stations, routes, warnings::add);
  }

  @Test
  void everythingIsEmptyUntilTheFirstRefresh() {
    assertTrue(directory.stationName("SURC:HHU").isEmpty());
    assertTrue(directory.linesServing(HHU_KEY).isEmpty());
  }

  @Test
  void resolvesNamesColorsAndServiceTypesCaseInsensitively() {
    directory.refresh();

    assertEquals(Optional.of(new Names("新笛矢·壑湖", "Neo")), directory.stationName("surc:hhu"));
    assertEquals(Optional.of(new LineStyle("WS", 0x70DEEE)), directory.line("SURC", "ws"));
    assertEquals(
        Optional.of(new LineStyle("DS", 0x336699)), directory.line("SURC", "DS"), "线路无色时用运营商主题色");
    assertEquals(
        Optional.of(RouteApi.OperationType.RAPID), directory.serviceType("SURC:MT:MT-3O_DPExp"));
    assertEquals(List.of("MT", "WS"), codes(directory.linesServing(HHU_KEY)));
  }

  @Test
  void platformLinesFollowTheStopListAndLineChanges() {
    directory.refresh();

    assertEquals(
        List.of("DS", "MT", "WS"),
        codes(directory.linesServingPlatform(HHU_KEY, "3")),
        "换线站以原线路到达、以新线路发车");
    assertEquals(
        List.of("WS"),
        codes(directory.linesServingPlatform(new PidsStationKey("SURC", "TPC"), "1")),
        "换线后各站算新线路，咽喉不算站台");
    assertTrue(directory.linesServingPlatform(HHU_KEY, "2").isEmpty(), "通过不算");
  }

  @Test
  void routeStopsViaPlatformCountsAndLineNamesAreIndexedForRemarks() {
    when(routes.getRoute(rapid))
        .thenReturn(
            Optional.of(
                withVia(
                    detail(
                        stop(0, "SURC:D:LWN:1", PassType.STOP, Optional.empty()),
                        stop(
                            1,
                            "SURC:S:HHU:3",
                            PassType.STOP,
                            Optional.of(new RouteApi.LineRef("SURC", "WS"))),
                        stop(2, "SURC:S:TPC:1:01", PassType.PASS, Optional.empty()),
                        stop(3, "SURC:S:TPC:1", PassType.TERMINATE, Optional.empty())),
                    List.of("HHU"))));
    GraphApi graph = mock(GraphApi.class);
    when(graph.listAllSnapshots())
        .thenReturn(
            List.of(
                new GraphApi.WorldGraphEntry(
                    UUID.randomUUID(),
                    new GraphApi.GraphSnapshot(
                        List.of(
                            node("SURC:S:HHU:1", GraphApi.NodeType.STATION),
                            node("SURC:S:HHU:2", GraphApi.NodeType.STATION),
                            node("SURC:S:HHU:3", GraphApi.NodeType.STATION),
                            node("SURC:S:HHU:3:01", GraphApi.NodeType.WAYPOINT),
                            node("SURC:S:TPC:1", GraphApi.NodeType.STATION)),
                        List.of(),
                        Instant.EPOCH,
                        5,
                        0,
                        1))));
    directory = new ApiPidsDirectory(operators, lines, stations, routes, graph, warnings::add);

    directory.refresh();

    assertEquals(
        List.of(
            new RouteStop(Optional.empty(), true, Optional.empty()),
            new RouteStop(
                Optional.of("SURC:HHU"), true, Optional.of(new RouteApi.LineRef("SURC", "WS"))),
            new RouteStop(Optional.empty(), false, Optional.empty()),
            new RouteStop(Optional.of("SURC:TPC"), true, Optional.empty())),
        directory.stops("surc:mt:mt-3o_dpexp"),
        "车库、咽喉不算车站");
    assertEquals(List.of("HHU"), directory.via("SURC:MT:MT-3O_DPExp"));
    assertEquals(List.of(), directory.via("SURC:DS:DS-1F_Full"), "没有配置为空");
    assertEquals(3, directory.platformCount("surc:hhu"), "咽喉不算站台");
    assertEquals(1, directory.platformCount("SURC:TPC"));
    assertEquals(Optional.of(new Names("浦蓝线", "WS Line")), directory.lineName("surc", "ws"));
  }

  @Test
  void aFailedRefreshKeepsTheOldIndex() {
    directory.refresh();
    when(lines.listAllLines()).thenThrow(new IllegalStateException("存储不可用"));

    try {
      directory.refresh();
    } catch (IllegalStateException expected) {
      // 调用方负责记录
    }

    assertEquals(Optional.of(new LineStyle("MT", 0xD920D9)), directory.line("SURC", "MT"));
  }

  @Test
  void oneFailingRouteOnlySkipsThatRoute() {
    when(routes.getRoute(local)).thenThrow(new IllegalStateException("交路缓存损坏"));

    directory.refresh();

    assertEquals(
        List.of("MT", "WS"), codes(directory.linesServingPlatform(HHU_KEY, "3")), "DS 那条读不出，其余照常");
    assertEquals(Optional.of(new Names("新笛矢·壑湖", "Neo")), directory.stationName("SURC:HHU"));
    assertEquals(1, warnings.size(), () -> warnings.toString());
    directory.refresh();
    assertEquals(1, warnings.size(), "同样的失败不重复告警");
  }

  @Test
  void operatorCodesSharedByTwoCompaniesAreNotIndexed() {
    when(operators.listAllOperators())
        .thenReturn(
            List.of(
                new OperatorApi.OperatorInfo(
                    SURC,
                    "SURC",
                    UUID.randomUUID(),
                    "南城",
                    Optional.empty(),
                    Optional.empty(),
                    0,
                    Optional.empty()),
                new OperatorApi.OperatorInfo(
                    UUID.randomUUID(),
                    "surc",
                    UUID.randomUUID(),
                    "冒名",
                    Optional.empty(),
                    Optional.empty(),
                    0,
                    Optional.empty())));

    directory.refresh();

    assertTrue(directory.stationName("SURC:HHU").isEmpty(), "宁可显示站码，也不张冠李戴");
    assertTrue(directory.line("SURC", "MT").isEmpty());
    assertTrue(directory.serviceType("SURC:MT:MT-3O_DPExp").isEmpty());
    assertEquals(1, warnings.size(), () -> warnings.toString());
  }

  @Test
  void operatorLinesSkipPlanningLinesAndRoutesKnowTheirLine() {
    when(lines.listAllLines())
        .thenReturn(
            List.of(
                line("WS", "浦蓝线", Optional.empty(), LineApi.LineStatus.MAINTENANCE),
                line("MT", "大都会线", Optional.empty(), LineApi.LineStatus.ACTIVE),
                line("DS", "探索线", Optional.empty(), LineApi.LineStatus.PLANNING)));

    directory.refresh();

    List<PidsDirectory.OperatorLine> found = directory.operatorLines("surc");
    assertEquals(List.of("MT", "WS"), found.stream().map(line -> line.chip().code()).toList());
    assertEquals(LineApi.LineStatus.MAINTENANCE, found.get(1).status());
    assertEquals(new Names("浦蓝线", "WS Line"), found.get(1).chip().name());
    assertTrue(found.stream().allMatch(line -> line.suspended().isEmpty()), "没有调度图时不判封锁");
    assertEquals(Optional.of(new Names("南城", "")), directory.operatorName("Surc"));
    assertEquals(
        Optional.of(new RouteApi.LineRef("SURC", "MT")), directory.lineOfRoute(rapid), "管理归属，不随换线");
    assertTrue(directory.operatorLines("TPC").isEmpty());
    assertEquals(
        Optional.of(RouteApi.RouteStage.OPERATION), directory.routeStage("surc:mt:mt-3o_dpexp"));
    assertEquals(
        List.of(new RouteApi.LineRef("SURC", "MT"), new RouteApi.LineRef("SURC", "WS")),
        directory.lineRefsServing(HHU_KEY),
        "停靠线路带运营商");
  }

  @Test
  void lineCodesSortNumbersByValue() {
    List<String> codes = new ArrayList<>(List.of("L10", "l2", "L1", "MT", "L2A", "A"));

    codes.sort(ApiPidsDirectory.LINE_CODE_ORDER);

    assertEquals(List.of("A", "L1", "l2", "L2A", "L10", "MT"), codes);
  }

  @Test
  void aBlockedEdgeSuspendsTheSectionBetweenTheNearestStopsOfTheLineShownThere() {
    activeLines();
    GraphApi graph = graph(edge("SURC:S:HHU:3", "SURC:S:TPC:1:01", true));
    directory = new ApiPidsDirectory(operators, lines, stations, routes, graph, warnings::add);

    directory.refresh();

    assertEquals(
        Optional.of(new PidsDirectory.Section("SURC:HHU", "SURC:TPC")),
        suspended("WS"),
        "壑湖换成 WS 发车，断在咽喉前；前后最近的停车站是壑湖与大港城");
    assertTrue(suspended("MT").isEmpty(), "换线前的区间没断");
  }

  @Test
  void aBlockedEdgeWithADetourDoesNotSuspendTheLine() {
    activeLines();
    GraphApi graph =
        graph(
            edge("SURC:S:HHU:3", "SURC:S:TPC:1:01", true),
            edge("SURC:S:HHU:3", "SURC:W:X:1", false),
            edge("SURC:W:X:1", "SURC:S:TPC:1:01", false));
    directory = new ApiPidsDirectory(operators, lines, stations, routes, graph, warnings::add);

    directory.refresh();

    assertTrue(suspended("WS").isEmpty(), "绕得过去就不算停运");
    assertTrue(suspended("MT").isEmpty());
  }

  private void activeLines() {
    when(lines.listAllLines())
        .thenReturn(
            List.of(
                line("MT", "大都会线", Optional.empty(), LineApi.LineStatus.ACTIVE),
                line("WS", "浦蓝线", Optional.empty(), LineApi.LineStatus.ACTIVE)));
  }

  private Optional<PidsDirectory.Section> suspended(String code) {
    return directory.operatorLines("SURC").stream()
        .filter(line -> line.chip().code().equals(code))
        .findFirst()
        .orElseThrow()
        .suspended();
  }

  /** MT-3O 全程连成一串的调度图，外加 {@code extra} 里的边。 */
  private static GraphApi graph(GraphApi.ApiEdge... extra) {
    List<GraphApi.ApiEdge> edges = new ArrayList<>();
    edges.add(edge("SURC:D:LWN:1", "SURC:S:HHU:2", false));
    edges.add(edge("SURC:S:HHU:2", "SURC:S:HHU:3", false));
    edges.add(edge("SURC:S:TPC:1:01", "SURC:S:TPC:1", false));
    edges.addAll(List.of(extra));
    GraphApi graph = mock(GraphApi.class);
    when(graph.listAllSnapshots())
        .thenReturn(
            List.of(
                new GraphApi.WorldGraphEntry(
                    UUID.randomUUID(),
                    new GraphApi.GraphSnapshot(
                        List.of(), edges, Instant.EPOCH, 0, edges.size(), 1))));
    return graph;
  }

  private static GraphApi.ApiEdge edge(String a, String b, boolean blocked) {
    return new GraphApi.ApiEdge(a + "~" + b, a, b, 10, 0, true, blocked);
  }

  private static List<String> codes(List<PidsView.LineChip> chips) {
    return chips.stream().map(PidsView.LineChip::code).toList();
  }

  private static LineApi.LineInfo line(String code, String name, Optional<String> color) {
    return line(code, name, color, LineApi.LineStatus.values()[0]);
  }

  private static LineApi.LineInfo line(
      String code, String name, Optional<String> color, LineApi.LineStatus status) {
    return new LineApi.LineInfo(
        UUID.randomUUID(),
        code,
        SURC,
        name,
        Optional.of(code + " Line"),
        LineApi.ServiceType.values()[0],
        color,
        status,
        Optional.empty());
  }

  private static StationApi.StationInfo station(UUID id, String code, String name) {
    return new StationApi.StationInfo(
        id,
        code,
        SURC,
        Optional.empty(),
        name,
        Optional.of("Neo"),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  private static StationApi.ServingLine serving(String line) {
    return new StationApi.ServingLine(
        UUID.randomUUID(),
        "SURC",
        line,
        line,
        Optional.empty(),
        HHU,
        "HHU",
        Optional.empty(),
        OptionalInt.empty());
  }

  private static RouteApi.RouteInfo route(
      UUID id, String line, String code, RouteApi.OperationType type) {
    return new RouteApi.RouteInfo(
        id,
        "SURC:" + line + ":" + code,
        "SURC",
        line,
        code,
        Optional.empty(),
        type,
        RouteApi.RouteStage.OPERATION);
  }

  private static RouteApi.RouteDetail detail(RouteApi.StopInfo... stops) {
    return new RouteApi.RouteDetail(
        null,
        List.of(stops).stream().map(RouteApi.StopInfo::nodeId).toList(),
        List.of(stops),
        RouteApi.TerminalInfo.empty(),
        0);
  }

  private static RouteApi.RouteDetail withVia(RouteApi.RouteDetail detail, List<String> via) {
    return new RouteApi.RouteDetail(
        detail.info(),
        detail.waypoints(),
        detail.stops(),
        detail.terminal(),
        detail.totalDistanceBlocks(),
        via);
  }

  private static GraphApi.ApiNode node(String id, GraphApi.NodeType type) {
    return new GraphApi.ApiNode(id, type, new GraphApi.Position(0, 64, 0), Optional.empty());
  }

  private static RouteApi.StopInfo stop(
      int sequence, String nodeId, PassType passType, Optional<RouteApi.LineRef> change) {
    return new RouteApi.StopInfo(
        sequence,
        nodeId,
        Optional.empty(),
        0,
        passType,
        false,
        Optional.empty(),
        Optional.empty(),
        change);
  }
}
