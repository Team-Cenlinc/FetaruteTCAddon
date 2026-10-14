package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.repository.TimetableRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.junit.jupiter.api.Test;

/**
 * 多线联编：两条线共用 B 站，一次编表、每线一张表。
 *
 * <p>路网：DEP 连 A:1 与 A:2；A:1 – B – C:1 是 L1 线（FA/FB 往返），A:2 – B – C:2 是 L2 线（SA/SB 往返）；B 一股道，两条线都在 B
 * 停。 各线各有出库 DEP→A、回库 A→DEP 走行。L1 600 s、L2 300 s。
 */
class TimetableSetBuilderTest {

  private static final Instant BUILT_AT = Instant.parse("2026-03-01T00:00:00Z");
  private static final ZoneId ZONE = ZoneId.of("UTC");
  private static final UUID COMPANY =
      UUID.nameUUIDFromBytes("co".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  private static final UUID OPERATOR =
      UUID.nameUUIDFromBytes("op".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  private static final UUID L1 =
      UUID.nameUUIDFromBytes("line-l1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  private static final UUID L2 =
      UUID.nameUUIDFromBytes("line-l2".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  private static final UUID L1_TT =
      UUID.nameUUIDFromBytes("tt-l1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  private static final UUID L2_TT =
      UUID.nameUUIDFromBytes("tt-l2".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  private static final String DEP = "OP:D:DEP:1";
  private static final String A1 = "OP:S:A:1";
  private static final String A2 = "OP:S:A:2";
  private static final String B = "OP:S:B:1";
  private static final String C1 = "OP:S:C:1";
  private static final String C2 = "OP:S:C:2";

  private final RailGraph graph =
      TimetableTestFixtures.graph(
          new LinkedHashMap<>(
              Map.of(
                  DEP, NodeType.DEPOT,
                  A1, NodeType.STATION,
                  A2, NodeType.STATION,
                  B, NodeType.STATION,
                  C1, NodeType.STATION,
                  C2, NodeType.STATION)),
          List.of(
              new TimetableTestFixtures.Edge(DEP, A1, 50, 10.0),
              new TimetableTestFixtures.Edge(DEP, A2, 50, 10.0),
              new TimetableTestFixtures.Edge(A1, B, 100, 10.0),
              new TimetableTestFixtures.Edge(A2, B, 100, 10.0),
              new TimetableTestFixtures.Edge(B, C1, 100, 10.0),
              new TimetableTestFixtures.Edge(B, C2, 100, 10.0)));

  private final Set<UUID> l1Routes = Set.of(id("FA"), id("FB"), id("CRT1"), id("RET1"));
  private final Set<UUID> l2Routes = Set.of(id("SA"), id("SB"), id("CRT2"), id("RET2"));

  /** 联编添加线路组前缀后，带客出库仍只依附运营交路，不能重新进入独立频率网格。 */
  @Test
  void jointBuildPreservesOperationDispatchedPassengerCreate() {
    TimetableBuilder.BuildInput l1 = l1Input();
    var create =
        leg("CRT1", RouteOperationType.CREATE, List.of(DEP, B, A1)).withDispatchByOperation(true);
    var routes =
        l1.routes().stream()
            .map(route -> route.routeId().equals(create.routeId()) ? create : route)
            .toList();
    var demand =
        new TimetableBuilder.BuildInput(
            l1.timetableId(),
            l1.companyId(),
            l1.operatorId(),
            l1.lineId(),
            l1.code(),
            l1.name(),
            routes,
            l1.graph(),
            l1.runTimeModel(),
            l1.notes());
    var result =
        new TimetableSetBuilder()
            .build(
                new TimetableSetBuilder.SetInput(
                    List.of(
                        new TimetableSetBuilder.Member(demand, "L1", "co/op/L1", l1Routes),
                        new TimetableSetBuilder.Member(l2Input(), "L2", "co/op/L2", l2Routes)),
                    List.of()),
                options(Map.of("L1/default", 600, "L2/default", 300)),
                BUILT_AT);

    assertTrue(result.success(), () -> result.joint().warnings().toString());
    assertTrue(
        result.joint().shares().stream().noneMatch(share -> share.key().contains("CRT1")),
        "按运营调度的出库段不占运营网格份额");
    Timetable table = result.tables().get(L1);
    assertFalse(table.duties().isEmpty());
    assertEquals(
        (long) table.duties().size(),
        table.trips().stream().filter(trip -> trip.routeId().equals(create.routeId())).count());
    assertTrue(
        table.duties().stream()
            .allMatch(duty -> duty.createRouteId().filter(create.routeId()::equals).isPresent()));
  }

  /**
   * L1 没有自己的出库线路，借 L2 的带客出库线路 CRTX（DEP–A2–B–A1，按运营交路调度）出车：这些出入库展示车次跟着交路进 L1 表， 带交路与车型、是展示车次；L2
   * 表里不留没有交路的副本，按表出票时也不会为它们另发运营票。
   */
  @Test
  void borrowedPassengerLegTripsFollowTheirDuty() {
    UUID crtx = id("CRTX");
    var borrowed =
        leg("CRTX", RouteOperationType.CREATE, List.of(DEP, A2, B, A1))
            .withDispatchByOperation(true);
    ConsistFleet fleet =
        new ConsistFleet(
            Map.of(
                "m6",
                new ConsistFleet.Consist(
                    "m6", "SH_A6", TimetableTestFixtures.perEdgeSpeedModel(), 60.0)),
            Map.of());
    List<TimetableBuilder.RouteInput> l1RoutesIn = new ArrayList<>();
    l1RoutesIn.add(operation("FA", List.of(A1, B, C1)));
    l1RoutesIn.add(operation("FB", List.of(C1, B, A1)));
    l1RoutesIn.add(borrowed);
    l1RoutesIn.add(leg("RET1", RouteOperationType.RETURN, List.of(A1, DEP)));
    List<TimetableBuilder.RouteInput> l2RoutesIn = new ArrayList<>();
    l2RoutesIn.add(operation("SA", List.of(A2, B, C2)));
    l2RoutesIn.add(operation("SB", List.of(C2, B, A2)));
    l2RoutesIn.add(leg("CRT2", RouteOperationType.CREATE, List.of(DEP, A2)));
    l2RoutesIn.add(leg("RET2", RouteOperationType.RETURN, List.of(A2, DEP)));
    l2RoutesIn.add(borrowed);
    TimetableBuilder.BuildInput l1 = withRoutes(l1Input(), l1RoutesIn).withFleet(fleet);
    TimetableBuilder.BuildInput l2 = withRoutes(l2Input(), l2RoutesIn).withFleet(fleet);
    Set<UUID> l1Own = Set.of(id("FA"), id("FB"), id("RET1"));
    Set<UUID> l2Own = Set.of(id("SA"), id("SB"), id("CRT2"), id("RET2"), crtx);

    TimetableSetBuilder.SetResult result =
        new TimetableSetBuilder()
            .build(
                new TimetableSetBuilder.SetInput(
                    List.of(
                        new TimetableSetBuilder.Member(l1, "L1", "co/op/L1", l1Own),
                        new TimetableSetBuilder.Member(l2, "L2", "co/op/L2", l2Own)),
                    List.of()),
                options(Map.of("L1/default", 600, "L2/default", 300)),
                BUILT_AT);

    assertTrue(result.success(), () -> result.joint().warnings().toString());
    Timetable l1Table = result.tables().get(L1);
    Timetable l2Table = result.tables().get(L2);
    assertFalse(l1Table.duties().isEmpty());
    assertTrue(
        l1Table.duties().stream()
            .allMatch(duty -> duty.createRouteId().filter(crtx::equals).isPresent()),
        "L1 只能借 CRTX 出库");
    // L2 表：不留挂在别表交路上的出库展示车次。
    assertTrue(
        l2Table.trips().stream().noneMatch(trip -> trip.routeId().equals(crtx)),
        () -> "L2 表里有借出的出库车次：" + l2Table.trips());
    for (TimetableTrip trip : l2Table.trips()) {
      assertTrue(trip.dutyId().flatMap(l2Table::duty).isPresent(), trip.tripCode() + " 没有交路");
    }
    // L1 表：每个交路一趟出库展示车次，带交路与车型。
    List<TimetableTrip> legs =
        l1Table.trips().stream().filter(trip -> trip.routeId().equals(crtx)).toList();
    assertEquals(l1Table.duties().size(), legs.size());
    for (TimetableTrip trip : legs) {
      assertTrue(l1Table.isLegTrip(trip), trip.tripCode());
      assertEquals(Optional.of("m6"), l1Table.consistOf(trip), trip.tripCode());
    }
    for (VehicleDuty duty : l1Table.duties()) {
      assertTrue(l1Table.legTrip(duty, RouteOperationType.CREATE).isPresent(), duty.dutyCode());
    }
    TimetableRoutePlan plan = l1Table.routePlan(crtx).orElseThrow();
    assertFalse(plan.external(), "同一 operator 的走行线路：借用方按表管辖它的车次");

    // 两张表都发布后，按表出票不会为出入库展示车次另发运营票。
    TimetableService service = new TimetableService(() -> BUILT_AT, ignored -> {});
    service.applySettings(
        new TimetableService.Settings(
            true, true, Duration.ofSeconds(120), Duration.ofSeconds(300), Duration.ofSeconds(300)));
    StorageProvider provider = mock(StorageProvider.class);
    TimetableRepository repository = mock(TimetableRepository.class);
    when(provider.timetables()).thenReturn(repository);
    when(repository.listPublished()).thenReturn(List.of(published(l1Table), published(l2Table)));
    service.reload(provider);
    Instant day = BUILT_AT.plusSeconds(86400);
    List<TimetableService.DueTrip> due = service.tripsBetween(day, day.plusSeconds(86400));
    assertFalse(due.isEmpty());
    assertTrue(
        due.stream().noneMatch(trip -> trip.trip().routeId().equals(crtx)), "出入库展示车次只由交路两端的票执行");
  }

  /** 每线一张表：同 code、同 updatedAt；班次与交路都在自己的表里，主键按自己的表 id 派生；走行线路进引用它的表。 */
  @Test
  void twoLinesBuildIntoOneTablePerLine() {
    TimetableSetBuilder.SetResult result =
        build(options(Map.of("L1/default", 600, "L2/default", 300)));

    assertTrue(result.success(), () -> result.joint().warnings().toString());
    assertEquals(2, result.tables().size());
    Timetable l1 = result.tables().get(L1);
    Timetable l2 = result.tables().get(L2);
    assertEquals("TT1", l1.code());
    assertEquals("TT1", l2.code());
    assertEquals(BUILT_AT, l1.updatedAt());
    assertEquals(BUILT_AT, l2.updatedAt());
    assertEquals(L1_TT, l1.id());
    assertEquals(L2_TT, l2.id());
    assertFalse(l1.trips().isEmpty());
    assertFalse(l2.trips().isEmpty());
    for (TimetableTrip trip : l1.trips()) {
      assertTrue(l1Routes.contains(trip.routeId()), trip.tripCode());
      assertEquals(L1_TT, trip.timetableId());
      assertEquals(TimetableTripNumbering.deterministicTripId(L1_TT, trip.tripCode()), trip.id());
    }
    for (TimetableTrip trip : l2.trips()) {
      assertTrue(l2Routes.contains(trip.routeId()), trip.tripCode());
    }
    // 序号连续、duty 号每表从 D001 起。
    for (int i = 0; i < l1.trips().size(); i++) {
      assertEquals(i, l1.trips().get(i).sequence());
    }
    for (int i = 0; i < l2.duties().size(); i++) {
      assertEquals(String.format("D%03d", i + 1), l2.duties().get(i).dutyCode());
      assertEquals(L2_TT, l2.duties().get(i).timetableId());
    }
    // 走行线路：L1 表里有 CRT1/RET1 的计划，没有 L2 的运营 route。
    Set<UUID> l1Plans = new HashSet<>();
    l1.routePlans().forEach(plan -> l1Plans.add(plan.routeId()));
    assertTrue(l1Plans.contains(id("CRT1")) && l1Plans.contains(id("RET1")));
    assertFalse(l1Plans.contains(id("SA")));
    // 交路组键带线前缀。
    Set<String> groups = new HashSet<>();
    result.joint().groupIntervals().forEach(g -> groups.add(g.group()));
    assertEquals(Set.of("L1/default", "L2/default"), groups);
  }

  /**
   * 交路首班的 route 不属于任何一条线时，拆表哪张表都不收它和挂在上面的班次：报一条提示，不悄悄丢掉。
   *
   * <p>正常编出来的联编表归属齐全，不报；同一张表换成 L1 的运营 route 无主的成员表，L1 的交路全报出来。
   */
  @Test
  void dutiesHeadedByAnUnownedRouteAreReported() {
    TimetableSetBuilder.SetResult result =
        build(options(Map.of("L1/default", 600, "L2/default", 300)));
    assertTrue(result.success(), () -> result.joint().warnings().toString());
    Timetable joint = result.joint().timetable().orElseThrow();
    assertTrue(
        result.joint().warnings().stream().noneMatch(line -> line.startsWith("联编拆表")),
        () -> result.joint().warnings().toString());

    List<String> warnings =
        TimetableSetBuilder.unownedDuties(
            joint,
            List.of(
                new TimetableSetBuilder.Member(
                    l1Input(), "L1", "co/op/L1", Set.of(id("CRT1"), id("RET1"))),
                new TimetableSetBuilder.Member(l2Input(), "L2", "co/op/L2", l2Routes)));

    assertEquals(1, warnings.size(), warnings::toString);
    assertTrue(warnings.get(0).startsWith("联编拆表："), warnings.get(0));
    assertFalse(result.tables().get(L1).duties().isEmpty());
    assertTrue(
        warnings.get(0).contains(result.tables().get(L1).duties().size() + " 条交路"),
        warnings.get(0));
  }

  /** 车不跨线：每条交路的班次全部属于同一条线。 */
  @Test
  void vehiclesNeverCrossLines() {
    TimetableSetBuilder.SetResult result =
        build(options(Map.of("L1/default", 600, "L2/default", 300)));

    for (Timetable table : result.tables().values()) {
      Set<UUID> own = table.lineId().equals(L1) ? l1Routes : l2Routes;
      Map<UUID, TimetableTrip> byId = new LinkedHashMap<>();
      table.trips().forEach(trip -> byId.put(trip.id(), trip));
      for (VehicleDuty duty : table.duties()) {
        assertFalse(duty.tripIds().isEmpty());
        for (UUID tripId : duty.tripIds()) {
          TimetableTrip trip = byId.get(tripId);
          assertTrue(trip != null && own.contains(trip.routeId()), duty.dutyCode());
        }
      }
    }
  }

  /** 基线互相引用：每张表记下另一张（id + builtAt）。 */
  @Test
  void baselinesReferenceEachOther() {
    TimetableSetBuilder.SetResult result =
        build(options(Map.of("L1/default", 600, "L2/default", 300)));

    var l1Baselines = result.baselines().get(L1);
    var l2Baselines = result.baselines().get(L2);
    assertEquals(1, l1Baselines.size());
    assertEquals(L2_TT, l1Baselines.get(0).neighborTimetableId());
    assertEquals(BUILT_AT, l1Baselines.get(0).neighborUpdatedAt());
    assertEquals("co/op/L2/TT1", l1Baselines.get(0).neighborCode());
    assertEquals(1, l2Baselines.size());
    assertEquals(L1_TT, l2Baselines.get(0).neighborTimetableId());
  }

  /** 两次联编逐字段相等。 */
  @Test
  void jointBuildIsDeterministic() {
    TimetableBuildOptions options = options(Map.of("L1/default", 600, "L2/default", 300));

    TimetableSetBuilder.SetResult first = build(options);
    TimetableSetBuilder.SetResult second = build(options);

    assertEquals(first.tables().get(L1).trips(), second.tables().get(L1).trips());
    assertEquals(first.tables().get(L1).duties(), second.tables().get(L1).duties());
    assertEquals(first.tables().get(L2).trips(), second.tables().get(L2).trips());
    assertEquals(first.tables().get(L2).duties(), second.tables().get(L2).duties());
  }

  /** 单线是一元情况：与直接调单线 builder 逐字段一致，组键不加前缀。 */
  @Test
  void singleLineSetEqualsSingleBuild() {
    TimetableBuilder.BuildInput l1 = l1Input();
    TimetableBuildOptions options = options(Map.of("default", 600));

    TimetableSetBuilder.SetResult set =
        new TimetableSetBuilder()
            .build(
                new TimetableSetBuilder.SetInput(
                    List.of(new TimetableSetBuilder.Member(l1, "L1", "co/op/L1", l1Routes)),
                    List.of()),
                options,
                BUILT_AT);
    TimetableBuildResult single = new TimetableBuilder().build(l1, options, BUILT_AT);

    assertTrue(set.success());
    assertEquals(single.timetable().orElseThrow().trips(), set.tables().get(L1).trips());
    assertEquals(single.timetable().orElseThrow().duties(), set.tables().get(L1).duties());
    assertTrue(set.baselines().get(L1).isEmpty());
    assertEquals("default", set.joint().groupIntervals().get(0).group());
  }

  // ------------------------------------------------------------------ 夹具

  private static TimetableBuilder.BuildInput withRoutes(
      TimetableBuilder.BuildInput input, List<TimetableBuilder.RouteInput> routes) {
    return new TimetableBuilder.BuildInput(
        input.timetableId(),
        input.companyId(),
        input.operatorId(),
        input.lineId(),
        input.code(),
        input.name(),
        routes,
        input.graph(),
        input.runTimeModel(),
        input.notes());
  }

  private static Timetable published(Timetable draft) {
    return new Timetable(
        draft.id(),
        draft.companyId(),
        draft.operatorId(),
        draft.lineId(),
        draft.code(),
        draft.name(),
        TimetableStatus.PUBLISHED,
        draft.zoneId(),
        draft.serviceStartSecondOfDay(),
        draft.serviceEndSecondOfDay(),
        draft.routePlans(),
        draft.trips(),
        draft.duties(),
        draft.notes(),
        draft.createdAt(),
        draft.updatedAt());
  }

  private TimetableSetBuilder.SetResult build(TimetableBuildOptions options) {
    return new TimetableSetBuilder()
        .build(
            new TimetableSetBuilder.SetInput(
                List.of(
                    new TimetableSetBuilder.Member(l1Input(), "L1", "co/op/L1", l1Routes),
                    new TimetableSetBuilder.Member(l2Input(), "L2", "co/op/L2", l2Routes)),
                List.of()),
            options,
            BUILT_AT);
  }

  private TimetableBuilder.BuildInput l1Input() {
    List<TimetableBuilder.RouteInput> routes = new ArrayList<>();
    routes.add(operation("FA", List.of(A1, B, C1)));
    routes.add(operation("FB", List.of(C1, B, A1)));
    routes.addAll(legs());
    return new TimetableBuilder.BuildInput(
        L1_TT,
        COMPANY,
        OPERATOR,
        L1,
        "TT1",
        "L1 表",
        routes,
        graph,
        TimetableTestFixtures.perEdgeSpeedModel(),
        Optional.empty());
  }

  private TimetableBuilder.BuildInput l2Input() {
    List<TimetableBuilder.RouteInput> routes = new ArrayList<>();
    routes.add(operation("SA", List.of(A2, B, C2)));
    routes.add(operation("SB", List.of(C2, B, A2)));
    routes.addAll(legs());
    return new TimetableBuilder.BuildInput(
        L2_TT,
        COMPANY,
        OPERATOR,
        L2,
        "TT1",
        "L2 表",
        routes,
        graph,
        TimetableTestFixtures.perEdgeSpeedModel(),
        Optional.empty());
  }

  /** 本 operator 全部走行线路：命令层对每条线都会把它们收进来。 */
  private static List<TimetableBuilder.RouteInput> legs() {
    return List.of(
        leg("CRT1", RouteOperationType.CREATE, List.of(DEP, A1)),
        leg("RET1", RouteOperationType.RETURN, List.of(A1, DEP)),
        leg("CRT2", RouteOperationType.CREATE, List.of(DEP, A2)),
        leg("RET2", RouteOperationType.RETURN, List.of(A2, DEP)));
  }

  private static TimetableBuilder.RouteInput operation(String code, List<String> nodes) {
    UUID routeId = id(code);
    return new TimetableBuilder.RouteInput(
        routeId,
        code,
        1,
        TimetableTestFixtures.route(code, nodes),
        TimetableTestFixtures.stops(routeId, nodes.size(), 0),
        Optional.empty());
  }

  private static TimetableBuilder.RouteInput leg(
      String code, RouteOperationType type, List<String> nodes) {
    UUID routeId = id(code);
    return new TimetableBuilder.RouteInput(
        routeId,
        code,
        type,
        0,
        TimetableTestFixtures.route(code, nodes),
        type == RouteOperationType.CREATE
            ? TimetableTestFixtures.createStops(routeId, nodes.size(), DEP)
            : TimetableTestFixtures.returnStops(routeId, nodes.size(), DEP),
        Optional.empty());
  }

  private static TimetableBuildOptions options(Map<String, Integer> groups) {
    return new TimetableBuildOptions(
        5 * 3600,
        6 * 3600,
        Duration.ofSeconds(600),
        Duration.ofSeconds(0),
        new VehicleDutyPlanner.Limits(100, 7200, 60),
        "",
        ZONE,
        Duration.ofSeconds(TimetableBuildOptions.DEFAULT_SEPARATION_SECONDS),
        false,
        groups);
  }

  private static UUID id(String code) {
    return TimetableTestFixtures.routeId(code);
  }
}
