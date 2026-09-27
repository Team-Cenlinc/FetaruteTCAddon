package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.junit.jupiter.api.Test;

/**
 * 资源索引：与全扫逐字段相等，且只碰被换那辆车的资源。
 *
 * <p>路网刻意把四类资源都凑齐：DEP – A:1/A:2 – J（道岔）– B:1 – C:1/C:2。DEP 与 J 之间经 A:1、A:2 成环，所以那四条边不是桥；
 * J–B:1、B:1–C:1、B:1–C:2 是桥，落成单线区段。A 站两股道（组容量 2）、B 站一股（组容量 1）、C 站两股。 再掺两份邻表的占用与一段站台待命，让 owner
 * 与车辆身份两条支路都被走到。
 */
class OccupationIndexTest {

  private static final String DEP = "OP:D:DEP:1";
  private static final String A1 = "OP:S:A:1";
  private static final String A2 = "OP:S:A:2";
  private static final String J = "OP:X:J:1";
  private static final String B1 = "OP:S:B:1";
  private static final String C1 = "OP:S:C:1";
  private static final String C2 = "OP:S:C:2";
  private static final int SEPARATION = 30;
  private static final String NEIGHBOR_N = "C/O/N/N-TT";
  private static final String NEIGHBOR_M = "C/O/M/M-TT";

  private final RailGraph graph =
      TimetableTestFixtures.graph(
          nodes(),
          List.of(
              new TimetableTestFixtures.Edge(DEP, A1, 50, 10.0),
              new TimetableTestFixtures.Edge(DEP, A2, 50, 10.0),
              new TimetableTestFixtures.Edge(A1, J, 100, 10.0),
              new TimetableTestFixtures.Edge(A2, J, 100, 10.0),
              new TimetableTestFixtures.Edge(J, B1, 100, 10.0),
              new TimetableTestFixtures.Edge(B1, C1, 100, 10.0),
              new TimetableTestFixtures.Edge(B1, C2, 100, 10.0)));
  private final TimetableConflictChecker.GraphIndex index =
      TimetableConflictChecker.GraphIndex.of(graph);
  private final Map<UUID, TimetableConflictChecker.RouteProfile> profiles = new LinkedHashMap<>();
  private final UUID ra = profile("RA", List.of(A1, B1, C1));
  private final UUID rb = profile("RB", List.of(A2, B1, C2));
  private final UUID rc = profile("RC", List.of(C1, B1, A1));

  /** 同一辆车的班次与待命不互撞：T1 与 D001 是一辆，T2 与 D002 是一辆。 */
  private final Function<String, String> vehicleOf =
      code ->
          switch (code) {
            case "T1", "D001" -> "D001";
            case "T2", "D002" -> "D002";
            default -> code;
          };

  /** 全扫必须与 {@code check} 逐字段相等——不是只比条数，是每一处冲突的每个字段。 */
  @Test
  void scanAllEqualsCheck() {
    List<TimetableConflictChecker.Movement> movements = movements();
    List<TimetableConflictChecker.Stay> stays = stays();

    TimetableConflictChecker.Report expected =
        TimetableConflictChecker.check(index, profiles, movements, stays, SEPARATION, vehicleOf);
    TimetableConflictChecker.Report actual =
        OccupationIndex.of(index, profiles, movements, stays, vehicleOf).scanAll(SEPARATION);

    // 夹具本身要够脏：四类资源都有、内外都有，否则这条等价性没有说服力。
    assertFalse(expected.clean(), "夹具必须造出冲突");
    assertEquals(
        Set.of(
            TimetableConflictChecker.Kind.TRACK,
            TimetableConflictChecker.Kind.PLATFORM,
            TimetableConflictChecker.Kind.SINGLE_LINE,
            TimetableConflictChecker.Kind.JUNCTION),
        expected.countByKind().keySet(),
        () -> "四类资源各要有一点: " + expected.conflicts());
    assertFalse(expected.external().isEmpty(), "要有邻表冲突");
    assertFalse(expected.internal().isEmpty(), "要有内部冲突");

    assertEquals(expected.conflicts(), actual.conflicts(), "逐项逐字段相等");
    assertEquals(expected, actual);
  }

  /** 换掉一辆车只动它自己的资源、自己的时段：没被返回的资源键上，冲突一条不变；返回的资源上，时段之外的冲突也一条不变。 按时段重扫得到的，正好是全扫里落在时段内的那些。 */
  @Test
  void replaceVehicleTouchesOnlyItsResources() {
    List<TimetableConflictChecker.Movement> movements = movements();
    List<TimetableConflictChecker.Stay> stays = stays();
    OccupationIndex occupations = OccupationIndex.of(index, profiles, movements, stays, vehicleOf);
    Map<String, List<TimetableConflictChecker.Conflict>> before =
        byResource(occupations.scanAll(SEPARATION));

    // D002 那辆车整体后移 200 s：班次 T2 与它在 B:1 的待命一起挪。
    Map<String, TimetableConflictChecker.Window> touched =
        occupations.replaceVehicle(
            "|D002",
            List.of(new TimetableConflictChecker.Movement("T2", rb, 205)),
            List.of(new TimetableConflictChecker.Stay("D002", platform(B1), 260, 400)));

    Map<String, List<TimetableConflictChecker.Conflict>> after =
        byResource(occupations.scanAll(SEPARATION));
    for (String key : union(before.keySet(), after.keySet())) {
      TimetableConflictChecker.Window window = touched.get(key);
      assertEquals(
          outside(before.getOrDefault(key, List.of()), window),
          outside(after.getOrDefault(key, List.of()), window),
          () -> "资源 " + key + " 上时段之外的冲突不该变");
    }
    // 换完之后索引与从头投影一遍等价。
    List<TimetableConflictChecker.Movement> replaced = new ArrayList<>();
    for (TimetableConflictChecker.Movement movement : movements) {
      replaced.add(
          movement.code().equals("T2")
              ? new TimetableConflictChecker.Movement("T2", rb, 205)
              : movement);
    }
    List<TimetableConflictChecker.Stay> replacedStays = new ArrayList<>();
    for (TimetableConflictChecker.Stay stay : stays) {
      replacedStays.add(
          stay.code().equals("D002")
              ? new TimetableConflictChecker.Stay("D002", platform(B1), 260, 400)
              : stay);
    }
    assertEquals(
        TimetableConflictChecker.check(
            index, profiles, replaced, replacedStays, SEPARATION, vehicleOf),
        occupations.scanAll(SEPARATION));
    // 只扫被碰过的资源上被改动的时段，得到的就是全扫里落在这些时段内的冲突。
    TimetableConflictChecker.Report partial = occupations.scan(touched, SEPARATION);
    List<TimetableConflictChecker.Conflict> expected = new ArrayList<>();
    for (TimetableConflictChecker.Conflict conflict : occupations.scanAll(SEPARATION).conflicts()) {
      TimetableConflictChecker.Window window = touched.get(conflict.resource());
      if (window != null && window.covers(conflict, SEPARATION)) {
        expected.add(conflict);
      }
    }
    assertFalse(expected.isEmpty(), "前置：挪完之后时段内要有冲突，否则这条断言什么也没证明");
    assertEquals(expected, partial.conflicts());
  }

  /** 时段之外的冲突；没有时段（资源没被碰到）就是全部。 */
  private static List<TimetableConflictChecker.Conflict> outside(
      List<TimetableConflictChecker.Conflict> conflicts, TimetableConflictChecker.Window window) {
    return window == null
        ? conflicts
        : conflicts.stream().filter(conflict -> !window.covers(conflict, SEPARATION)).toList();
  }

  // ------------------------------------------------------------------ 夹具

  private static Map<String, NodeType> nodes() {
    Map<String, NodeType> out = new LinkedHashMap<>();
    out.put(DEP, NodeType.DEPOT);
    out.put(A1, NodeType.STATION);
    out.put(A2, NodeType.STATION);
    out.put(J, NodeType.SWITCHER);
    out.put(B1, NodeType.STATION);
    out.put(C1, NodeType.STATION);
    out.put(C2, NodeType.STATION);
    return out;
  }

  /** T1/T2 在 A 侧几乎同时出发（撞道岔、撞 B:1 的单股道），T3 对向从 C:1 回来（撞单线）；两份邻表各插一趟。 */
  private List<TimetableConflictChecker.Movement> movements() {
    return List.of(
        new TimetableConflictChecker.Movement("T1", ra, 0),
        new TimetableConflictChecker.Movement("T2", rb, 5),
        new TimetableConflictChecker.Movement("T3", rc, 20),
        new TimetableConflictChecker.Movement("N-001", ra, 8, Optional.of(NEIGHBOR_N)),
        new TimetableConflictChecker.Movement("M-001", rb, 12, Optional.of(NEIGHBOR_M)));
  }

  /** 我在 B:1 的待命（与 T2 同车）、邻表在 C:1 的待命：站台组容量与 owner 两条支路都走到。 */
  private List<TimetableConflictChecker.Stay> stays() {
    return List.of(
        new TimetableConflictChecker.Stay("D002", platform(B1), 60, 200),
        new TimetableConflictChecker.Stay("N-D01", platform(C1), 0, 100, Optional.of(NEIGHBOR_N)),
        new TimetableConflictChecker.Stay("D001", platform(A1), -100, 0));
  }

  private static TimetableConflictChecker.Platform platform(String nodeId) {
    return new TimetableConflictChecker.Platform(
        nodeId, TimetableConflictChecker.groupOf(nodeId), false);
  }

  private static Map<String, List<TimetableConflictChecker.Conflict>> byResource(
      TimetableConflictChecker.Report report) {
    Map<String, List<TimetableConflictChecker.Conflict>> out = new LinkedHashMap<>();
    for (TimetableConflictChecker.Conflict conflict : report.conflicts()) {
      out.computeIfAbsent(conflict.resource(), key -> new ArrayList<>()).add(conflict);
    }
    return out;
  }

  private static Set<String> union(Set<String> a, Set<String> b) {
    Set<String> out = new java.util.LinkedHashSet<>(a);
    out.addAll(b);
    return out;
  }

  private UUID profile(String code, List<String> nodeIds) {
    UUID id = TimetableTestFixtures.routeId(code);
    RouteDefinition route = TimetableTestFixtures.route(code, nodeIds);
    List<RouteStop> stops = TimetableTestFixtures.stops(id, nodeIds.size(), 0);
    TimetableTimingCalculator.TimingResult timing =
        new TimetableTimingCalculator()
            .compute(graph, TimetableTestFixtures.perEdgeSpeedModel(), route, stops, Duration.ZERO);
    assertTrue(timing.ok(), () -> code + ": " + timing.failure());
    profiles.put(
        id,
        new TimetableConflictChecker.RouteProfile(
            id,
            code,
            timing.stops(),
            timing.segments(),
            TimetableConflictChecker.platformsOf(
                timing.stops(), stops, route.waypoints(), index.nodeTypes())));
    return id;
  }
}
