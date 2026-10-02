package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.junit.jupiter.api.Test;

/** 按组频率端到端：带客的 CREATE/RETURN 是班次；每组每方向各自的间隔；共用起点上的交错进报告。 */
class TimetableBuilderGroupsTest {

  private static final Instant BUILT_AT = Instant.parse("2026-03-01T00:00:00Z");
  private static final ZoneId ZONE = ZoneId.of("UTC");
  private static final String DEP = "OP:D:DEP:1";
  private static final String A = "OP:S:A:1";
  private static final String A2 = "OP:S:A:2";
  private static final String B = "OP:S:B:1";
  private static final String B2 = "OP:S:B:2";
  private static final String C = "OP:S:C:1";

  /** 直链 DEP - A - B - C 给 DS 形态用。 */
  private final RailGraph graph =
      TimetableTestFixtures.chain(
          List.of(DEP, A, B, C), new int[] {50, 100, 100}, new double[] {10.0, 10.0, 10.0});

  /**
   * 大小交路的路网：大交路走 A:1 – B:1 – C，小交路走 A:2 – B:2，各有自己的股道（小交路在 B 折返不能占着大交路要过的 B:1）； 车库连着 A:1 与 A:2。A、B
   * 都是两股道的站台组。
   */
  private final RailGraph twoTrackGraph =
      TimetableTestFixtures.graph(
          new java.util.LinkedHashMap<>(
              Map.of(
                  DEP, NodeType.DEPOT,
                  A, NodeType.STATION,
                  A2, NodeType.STATION,
                  B, NodeType.STATION,
                  B2, NodeType.STATION,
                  C, NodeType.STATION)),
          List.of(
              new TimetableTestFixtures.Edge(DEP, A, 50, 10.0),
              new TimetableTestFixtures.Edge(DEP, A2, 50, 10.0),
              new TimetableTestFixtures.Edge(A, B, 100, 10.0),
              new TimetableTestFixtures.Edge(B, C, 100, 10.0),
              new TimetableTestFixtures.Edge(A2, B2, 100, 10.0)));

  /**
   * DS 形态：只有带客的 CREATE（DEP→A→B→C）与带客的 RETURN（C→B→A→DEP），没有 OPERATION。 出库班是班次、上网格；每条交路以回库班收尾，回库班也落
   * trip 行、挂在交路上，但不进 duty.tripIds（进度与回库票仍按走行段处理）。
   */
  @Test
  void createToReturnPairFormsCompleteDuties() {
    UUID crt = TimetableTestFixtures.routeId("DS1");
    UUID ret = TimetableTestFixtures.routeId("DS2");
    List<TimetableBuilder.RouteInput> routes =
        List.of(
            new TimetableBuilder.RouteInput(
                crt,
                "DS1",
                RouteOperationType.CREATE,
                1,
                TimetableTestFixtures.route("DS1", List.of(DEP, A, B, C)),
                TimetableTestFixtures.createStops(crt, 4, DEP),
                Optional.empty()),
            new TimetableBuilder.RouteInput(
                ret,
                "DS2",
                RouteOperationType.RETURN,
                1,
                TimetableTestFixtures.route("DS2", List.of(C, B, A, DEP)),
                TimetableTestFixtures.returnStops(ret, 4, DEP),
                Optional.empty()));

    TimetableBuildResult result = build(graph, routes, options(600, Map.of()));
    Timetable timetable = result.timetable().orElseThrow();

    assertTrue(result.success(), () -> result.warnings().toString());
    assertTrue(result.allDutiesReturnToStorage());
    List<TimetableTrip> outbound =
        timetable.trips().stream().filter(t -> t.routeId().equals(crt)).toList();
    List<TimetableTrip> inbound =
        timetable.trips().stream().filter(t -> t.routeId().equals(ret)).toList();
    assertFalse(outbound.isEmpty(), "带客的出库班是班次");
    assertEquals(outbound.size(), inbound.size(), "每班出库都有一班回库收尾");
    assertEquals(outbound.size(), timetable.duties().size(), "每辆车一去一回就是一条交路");
    Set<UUID> inDuties =
        timetable.duties().stream()
            .flatMap(duty -> duty.tripIds().stream())
            .collect(Collectors.toSet());
    assertTrue(outbound.stream().allMatch(t -> inDuties.contains(t.id())));
    assertTrue(inbound.stream().noneMatch(t -> inDuties.contains(t.id())), "回库班不进 duty.tripIds");
    for (VehicleDuty duty : timetable.duties()) {
      assertEquals(Optional.of(ret), duty.returnRouteId());
      TimetableTrip back =
          inbound.stream()
              .filter(t -> t.dutyId().equals(Optional.of(duty.id())))
              .findFirst()
              .orElseThrow();
      assertEquals(duty.returnSecondOfDay(), back.departureSecondOfDay(), "回库班发车 = 回库票");
    }
    // 序号连续、回库班与出库班各自从 001 起。
    assertEquals("DS1-001", outbound.get(0).tripCode());
    assertEquals("DS2-001", inbound.get(0).tripCode());
    for (int i = 0; i < timetable.trips().size(); i++) {
      assertEquals(i, timetable.trips().get(i).sequence());
    }
    assertTrue(result.dutyShapes().stream().anyMatch(shape -> shape.trips() == 1));
  }

  /** 大小交路：full（A:1↔C）600 s、short（A:2↔B:2）300 s。各组按自己的间隔发车，共用起点站台组 A 上交错，报告里两组的目标间隔与合成间隔都在。 */
  @Test
  void perGroupIntervalsDriveEachDirection() {
    UUID fa = TimetableTestFixtures.routeId("FA");
    UUID sa = TimetableTestFixtures.routeId("SA");
    List<TimetableBuilder.RouteInput> routes = bigAndSmallLoops();

    TimetableBuildResult result =
        build(twoTrackGraph, routes, options(600, Map.of("full", 600, "short", 300)));

    assertTrue(result.success(), () -> result.warnings().toString());
    Timetable timetable = result.timetable().orElseThrow();
    Map<String, Integer> target =
        result.groupIntervals().stream()
            .collect(Collectors.toMap(g -> g.group(), g -> g.targetSeconds()));
    assertEquals(Map.of("full", 600, "short", 300), target);
    Map<String, Integer> effective =
        result.groupIntervals().stream()
            .collect(Collectors.toMap(g -> g.group(), g -> g.effectiveSeconds()));
    assertEquals(effective.get("short") * 2, effective.get("full"), "放宽按同一比例：大小交路的比例不变");
    long full = timetable.trips().stream().filter(t -> t.routeId().equals(fa)).count();
    long shortTurn = timetable.trips().stream().filter(t -> t.routeId().equals(sa)).count();
    assertTrue(shortTurn >= 2 * full - 1 && shortTurn <= 2 * full + 1, full + " vs " + shortTurn);
    List<TimetableTrip> saTrips =
        timetable.trips().stream().filter(t -> t.routeId().equals(sa)).toList();
    for (int i = 1; i < saTrips.size(); i++) {
      assertEquals(
          effective.get("short"),
          saTrips.get(i).departureSecondOfDay() - saTrips.get(i - 1).departureSecondOfDay(),
          "同方向发车规整");
    }
    PhasePlanner.Interleave atA =
        result.interleaves().stream()
            .filter(item -> item.originGroup().equals("OP:S:A"))
            .findFirst()
            .orElseThrow();
    assertEquals((int) (full + shortTurn), atA.departures());
    assertTrue(atA.maxGap() < effective.get("full"), "交错后 A 上的合成间隔小于大交路自己的间隔");
    assertTrue(result.phaseNotes().stream().anyMatch(note -> note.contains("交错")));
  }

  /** 同样输入两次构建，含相位与交错在内逐字段相等。 */
  @Test
  void groupedBuildIsDeterministic() {
    List<TimetableBuilder.RouteInput> routes = bigAndSmallLoops();
    TimetableBuildOptions options = options(600, Map.of("full", 600, "short", 300));

    TimetableBuildResult first = build(twoTrackGraph, routes, options);
    TimetableBuildResult second = build(twoTrackGraph, routes, options);

    assertTrue(first.success(), () -> first.warnings().toString());
    assertEquals(first.timetable().orElseThrow().trips(), second.timetable().orElseThrow().trips());
    assertEquals(
        first.timetable().orElseThrow().duties(), second.timetable().orElseThrow().duties());
    assertEquals(first.interleaves(), second.interleaves());
    assertEquals(first.phaseNotes(), second.phaseNotes());
  }

  /** 大交路 FA/FB A:1↔C（经 B:1），小交路 SA/SB A:2↔B:2；各自的出库、回库走行。 */
  private static List<TimetableBuilder.RouteInput> bigAndSmallLoops() {
    return List.of(
        operation(TimetableTestFixtures.routeId("FA"), "FA", List.of(A, B, C), "full"),
        operation(TimetableTestFixtures.routeId("FB"), "FB", List.of(C, B, A), "full"),
        operation(TimetableTestFixtures.routeId("SA"), "SA", List.of(A2, B2), "short"),
        operation(TimetableTestFixtures.routeId("SB"), "SB", List.of(B2, A2), "short"),
        leg("CRT1", RouteOperationType.CREATE, List.of(DEP, A)),
        leg("RET1", RouteOperationType.RETURN, List.of(A, DEP)),
        leg("CRT2", RouteOperationType.CREATE, List.of(DEP, A2)),
        leg("RET2", RouteOperationType.RETURN, List.of(A2, DEP)));
  }

  private static TimetableBuilder.RouteInput operation(
      UUID id, String code, List<String> nodes, String group) {
    return new TimetableBuilder.RouteInput(
        id,
        code,
        RouteOperationType.OPERATION,
        1,
        TimetableTestFixtures.route(code, nodes),
        TimetableTestFixtures.stops(id, nodes.size(), 0),
        Optional.empty(),
        Optional.empty(),
        false,
        Optional.of(group));
  }

  private static TimetableBuilder.RouteInput leg(
      String code, RouteOperationType type, List<String> nodes) {
    UUID id = TimetableTestFixtures.routeId(code);
    return new TimetableBuilder.RouteInput(
        id,
        code,
        type,
        0,
        TimetableTestFixtures.route(code, nodes),
        type == RouteOperationType.CREATE
            ? TimetableTestFixtures.createStops(id, nodes.size(), DEP)
            : TimetableTestFixtures.returnStops(id, nodes.size(), DEP),
        Optional.empty());
  }

  private static TimetableBuildOptions options(int headway, Map<String, Integer> groups) {
    return new TimetableBuildOptions(
        5 * 3600,
        6 * 3600,
        Duration.ofSeconds(headway),
        Duration.ofSeconds(20),
        new VehicleDutyPlanner.Limits(100, 7200, 60),
        "",
        ZONE,
        Duration.ofSeconds(TimetableBuildOptions.DEFAULT_SEPARATION_SECONDS),
        false,
        groups);
  }

  private static TimetableBuildResult build(
      RailGraph graph, List<TimetableBuilder.RouteInput> routes, TimetableBuildOptions options) {
    return new TimetableBuilder()
        .build(
            new TimetableBuilder.BuildInput(
                UUID.nameUUIDFromBytes("GRP".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "TT1",
                "分组表",
                routes,
                graph,
                TimetableTestFixtures.perEdgeSpeedModel(),
                Optional.empty()),
            options,
            BUILT_AT);
  }
}
