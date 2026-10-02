package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.junit.jupiter.api.Test;

/** 分类落在 route 上：中途有 STOP 才是班次；组名来自 spawn_group，没配就一个默认组。 */
class ServiceGroupClassifierTest {

  private static final String DEP = "OP:D:DEP:1";
  private static final String A = "OP:S:A:1";
  private static final String B = "OP:S:B:1";
  private static final String C = "OP:S:C:1";

  /** OPERATION 永远带客（两站小交路也是）；CREATE 两站的是纯走行，三站中途有 STOP 的带客；中途只 PASS 的纯走行。 */
  @Test
  void passengersMeansAnIntermediateStopForDepotLegs() {
    assertTrue(ServiceGroupClassifier.carriesPassengers(operation("RA", List.of(A, B, C), null)));
    assertTrue(ServiceGroupClassifier.carriesPassengers(operation("RX", List.of(A, B), null)));
    assertFalse(ServiceGroupClassifier.carriesPassengers(create("CRT", List.of(DEP, A), null)));
    assertTrue(ServiceGroupClassifier.carriesPassengers(create("DS1", List.of(DEP, A, B), null)));
    UUID id = TimetableTestFixtures.routeId("RP");
    List<RouteStop> passing =
        List.of(
            stop(id, 0, RouteStopPassType.STOP),
            stop(id, 1, RouteStopPassType.PASS),
            stop(id, 2, RouteStopPassType.TERMINATE));
    TimetableBuilder.RouteInput route =
        new TimetableBuilder.RouteInput(
            id,
            "RP",
            RouteOperationType.RETURN,
            1,
            TimetableTestFixtures.route("RP", List.of(A, B, C)),
            passing,
            Optional.empty());
    assertFalse(ServiceGroupClassifier.carriesPassengers(route));
  }

  /** 没配 spawn_group 的全进默认组：往返两条 route 是同组里互为反向的两个方向，纯走行进 pureLegs。 */
  @Test
  void routesWithoutSpawnGroupShareTheDefaultGroup() {
    List<TimetableBuilder.RouteInput> routes = new ArrayList<>();
    routes.add(operation("RB", List.of(C, B, A), null));
    routes.add(operation("RA", List.of(A, B, C), null));
    routes.add(create("CRT", List.of(DEP, A), null));

    ServiceGroupClassifier.Classification classification = ServiceGroupClassifier.classify(routes);

    assertEquals(1, classification.groups().size());
    ServiceGroupClassifier.Group group = classification.groups().get(0);
    assertEquals(ServiceGroupClassifier.DEFAULT_GROUP, group.name());
    assertEquals(2, group.directions().size());
    ServiceGroupClassifier.Direction forward = group.directions().get(0);
    ServiceGroupClassifier.Direction backward = group.directions().get(1);
    assertEquals("OP:S:A→OP:S:C", forward.key());
    assertEquals(backward.key(), forward.reverseKey());
    assertEquals(List.of(TimetableTestFixtures.routeId("CRT")), group.pureLegs());
    assertEquals(
        ServiceGroupClassifier.DEFAULT_GROUP, classification.groupOf(group.pureLegs().get(0)));
    assertTrue(classification.warnings().isEmpty());
  }

  /** spawn_group 分组：同方向多条 route 按 code 排序成候选、权重原样带入；只有纯走行的组给出提示。 */
  @Test
  void spawnGroupsSplitRoutesAndSortCandidatesByCode() {
    List<TimetableBuilder.RouteInput> routes =
        List.of(
            operation("RZ", List.of(A, B, C), "full", 3),
            operation("RA", List.of(A, B, C), "full", 1),
            operation("RS", List.of(A, B), "short", 1),
            create("CRT", List.of(DEP, A), "yard"));

    ServiceGroupClassifier.Classification classification = ServiceGroupClassifier.classify(routes);

    assertEquals(
        List.of("full", "short", "yard"),
        classification.groups().stream().map(ServiceGroupClassifier.Group::name).toList());
    ServiceGroupClassifier.Direction full = classification.groups().get(0).directions().get(0);
    assertEquals(
        List.of("RA", "RZ"),
        full.candidates().stream().map(WeightedTripAllocator.Candidate::key).toList());
    assertEquals(
        List.of(1, 3),
        full.candidates().stream().map(WeightedTripAllocator.Candidate::weight).toList());
    assertEquals("OP:S:A→OP:S:B", classification.groups().get(1).directions().get(0).key());
    assertEquals("short", classification.groupOf(TimetableTestFixtures.routeId("RS")));
    assertEquals(1, classification.warnings().size());
    assertTrue(classification.warnings().get(0).contains("yard"));
  }

  /**
   * 方向键取<b>乘客首末站</b>：车库与走行路径点不是站。
   *
   * <p>按首末路径点取会把往返拆成两个互不相识的方向（出库端在车库、入库端在车库），于是往返对锚不到一起、 组间也找不到共用起点。实测里 MT-1N_Short 与 MT-1N_ShortR
   * 就是这么被拆开、把进站流量算成两倍的。
   */
  @Test
  void directionKeysUsePassengerEndpoints() {
    // 出库班：路径点是 DEP → A → B，首个停靠是车库（CRET），方向起点应当是 A 而不是车库。
    TimetableBuilder.RouteInput depotStart = create("DS1", List.of(DEP, A, B), null);
    assertEquals("OP:S:A", ServiceGroupClassifier.originGroupOf(depotStart));
    assertEquals("OP:S:B", ServiceGroupClassifier.terminalGroupOf(depotStart));

    // 末站是走行路径点（不是车站）时，终点取最后一个落在车站上的停靠。
    UUID id = TimetableTestFixtures.routeId("RW");
    List<RouteStop> stops =
        List.of(
            stop(id, 0, RouteStopPassType.STOP),
            stop(id, 1, RouteStopPassType.STOP),
            stop(id, 2, RouteStopPassType.TERMINATE));
    TimetableBuilder.RouteInput toWaypoint =
        new TimetableBuilder.RouteInput(
            id,
            "RW",
            RouteOperationType.OPERATION,
            1,
            TimetableTestFixtures.route("RW", List.of(A, B, "OP:A:B:1:003")),
            stops,
            Optional.empty());
    assertEquals("OP:S:A", ServiceGroupClassifier.originGroupOf(toWaypoint));
    assertEquals("OP:S:B", ServiceGroupClassifier.terminalGroupOf(toWaypoint), "路径点不是站");
  }

  /** 从车库始发和从站台始发的两条 route 归到同一个方向：它们对乘客是同一条"从 A 到 C"。 */
  @Test
  void depotStartAndSidingStartShareADirection() {
    TimetableBuilder.RouteInput fromDepot = create("DS2", List.of(DEP, A, B, C), null);
    TimetableBuilder.RouteInput fromPlatform = operation("RA", List.of(A, B, C), null);

    assertEquals(
        ServiceGroupClassifier.originGroupOf(fromPlatform),
        ServiceGroupClassifier.originGroupOf(fromDepot));
    assertEquals(
        ServiceGroupClassifier.terminalGroupOf(fromPlatform),
        ServiceGroupClassifier.terminalGroupOf(fromDepot));

    ServiceGroupClassifier.Classification classification =
        ServiceGroupClassifier.classify(List.of(fromDepot, fromPlatform));
    assertEquals(1, classification.groups().size());
    assertEquals(
        1,
        classification.groups().get(0).directions().size(),
        "两条 route 对乘客是同一个方向，应当共用一张子网格按 weight 切份额");
  }

  /**
   * 车库端不是站：出库走行 DEP→A 的两端都落在 A 上——车库不参与"从哪到哪"。
   *
   * <p>纯走行不进方向表（它们在 {@code pureLegs} 里），所以两端同键无害；这条钉的是"车库不是站"这条规则本身。
   */
  @Test
  void depotEndIsNotAStation() {
    TimetableBuilder.RouteInput leg = create("CRT", List.of(DEP, A), null);

    assertEquals("OP:S:A", ServiceGroupClassifier.originGroupOf(leg), "车库端跳过，取第一个车站");
    assertEquals("OP:S:A", ServiceGroupClassifier.terminalGroupOf(leg));
  }

  /** 一个落在车站上的停靠都没有（全是 PASS 的走行链）：退回首末路径点，与旧行为一致。 */
  @Test
  void routesWithoutAnyStationStopFallBackToWaypoints() {
    UUID id = TimetableTestFixtures.routeId("RL");
    List<RouteStop> allPassing =
        List.of(
            stop(id, 0, RouteStopPassType.PASS),
            stop(id, 1, RouteStopPassType.PASS),
            stop(id, 2, RouteStopPassType.PASS));
    TimetableBuilder.RouteInput leg =
        new TimetableBuilder.RouteInput(
            id,
            "RL",
            RouteOperationType.RETURN,
            0,
            TimetableTestFixtures.route("RL", List.of(A, B, DEP)),
            allPassing,
            Optional.empty());

    assertEquals("OP:S:A", ServiceGroupClassifier.originGroupOf(leg));
    assertEquals("OP:D:DEP", ServiceGroupClassifier.terminalGroupOf(leg), "退回末路径点");
  }

  private static TimetableBuilder.RouteInput operation(
      String code, List<String> nodes, String group) {
    return operation(code, nodes, group, 1);
  }

  private static TimetableBuilder.RouteInput operation(
      String code, List<String> nodes, String group, int weight) {
    UUID id = TimetableTestFixtures.routeId(code);
    return new TimetableBuilder.RouteInput(
        id,
        code,
        RouteOperationType.OPERATION,
        weight,
        TimetableTestFixtures.route(code, nodes),
        TimetableTestFixtures.stops(id, nodes.size(), 0),
        Optional.empty(),
        Optional.empty(),
        false,
        Optional.ofNullable(group));
  }

  private static TimetableBuilder.RouteInput create(String code, List<String> nodes, String group) {
    UUID id = TimetableTestFixtures.routeId(code);
    return new TimetableBuilder.RouteInput(
        id,
        code,
        RouteOperationType.CREATE,
        0,
        TimetableTestFixtures.route(code, nodes),
        TimetableTestFixtures.createStops(id, nodes.size(), DEP),
        Optional.empty(),
        Optional.empty(),
        false,
        Optional.ofNullable(group));
  }

  private static RouteStop stop(UUID routeId, int index, RouteStopPassType type) {
    return new RouteStop(
        routeId, index, Optional.empty(), Optional.empty(), Optional.of(0), type, Optional.empty());
  }
}
