package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.scope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.Timetable;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableConflictChecker;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableRoutePlan;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableStatus;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableStop;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableTestFixtures;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableTimingCalculator;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableTrip;
import org.junit.jupiter.api.Test;

/**
 * 谁算邻表：与我足迹相交的已发布表；同 line 的表不算；无表但共资源的线路只报告。
 *
 * <p>路网是直链 A–B–C–D。我的线跑 A→C，邻线跑 B→D，两者共用 B–C 这条边与 B、C 两站。
 */
class TimetableNeighborhoodLoaderTest {

  private static final String A = "OP:S:A:1";
  private static final String B = "OP:S:B:1";
  private static final String C = "OP:S:C:1";
  private static final String D = "OP:S:D:1";

  private final RailGraph graph =
      TimetableTestFixtures.chain(
          List.of(A, B, C, D), new int[] {100, 100, 100}, new double[] {10.0, 10.0, 10.0});
  private final TimetableConflictChecker.GraphIndex index =
      TimetableConflictChecker.GraphIndex.of(graph);
  private final Map<UUID, RouteDefinition> definitions = new HashMap<>();
  private final Map<UUID, List<RouteStop>> stops = new HashMap<>();

  @Test
  void publishedTablesSharingResourcesAreNeighbors() {
    UUID mineRoute = route("MINE", List.of(A, C));
    UUID otherRoute = route("OTHER", List.of(B, D));
    UUID farRoute = route("FAR", List.of(D, D));
    UUID myLine = UUID.randomUUID();
    Timetable other = timetable(UUID.randomUUID(), "OTHER-TT", otherRoute, "OTHER");
    Timetable far = timetable(UUID.randomUUID(), "FAR-TT", farRoute, "FAR");
    TimetableNeighborhoodLoader loader = loader();
    TimetableFootprint mine =
        loader.footprintOf(
            UUID.randomUUID(),
            "C/O/MINE",
            List.of(new TimetableNeighborhoodLoader.RouteCandidate(mineRoute, "MINE", "C/O/MINE")),
            graph,
            index);

    List<TimetableNeighborhoodLoader.FootprintNeighbor> neighbors =
        loader.footprints(List.of(other, far), myLine, graph, index, mine);

    assertEquals(1, neighbors.size(), () -> neighbors.toString());
    assertEquals(other.id(), neighbors.get(0).timetableId());
    assertTrue(neighbors.get(0).sharedKeys().contains("edge:" + B + "~" + C));
    assertTrue(neighbors.get(0).sharedKeys().contains("platform:" + C));
    assertTrue(neighbors.get(0).sharedResources() >= 2);
  }

  /** 同一条 line 名下的已发布表不算邻表：它与我是替代关系。 */
  @Test
  void sameLineTablesAreExcluded() {
    UUID mineRoute = route("MINE", List.of(A, C));
    UUID myLine = UUID.randomUUID();
    Timetable previous = timetable(myLine, "OLD", mineRoute, "MINE");
    TimetableNeighborhoodLoader loader = loader();
    TimetableFootprint mine =
        loader.footprintOf(
            UUID.randomUUID(),
            "C/O/MINE",
            List.of(new TimetableNeighborhoodLoader.RouteCandidate(mineRoute, "MINE", "C/O/MINE")),
            graph,
            index);

    assertTrue(loader.footprints(List.of(previous), myLine, graph, index, mine).isEmpty());
  }

  /** 邻表里某条 route 在当前图上不可达：足迹按空计并留下警告，不阻塞。 */
  @Test
  void unreachableNeighborRouteIsWarnedNotFatal() {
    UUID mineRoute = route("MINE", List.of(A, C));
    UUID otherRoute = route("OTHER", List.of(B, D));
    UUID ghostRoute = route("GHOST", List.of(B, "OP:S:NOWHERE:1"));
    Timetable other =
        new Timetable(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "OTHER-TT",
            "other",
            TimetableStatus.PUBLISHED,
            ZoneId.of("UTC"),
            5 * 3600,
            6 * 3600,
            List.of(
                plan(otherRoute, "OTHER", B, D), plan(ghostRoute, "GHOST", B, "OP:S:NOWHERE:1")),
            List.of(),
            List.of(),
            Optional.empty(),
            Instant.EPOCH,
            Instant.EPOCH);
    TimetableNeighborhoodLoader loader = loader();
    TimetableFootprint mine =
        loader.footprintOf(
            UUID.randomUUID(),
            "C/O/MINE",
            List.of(new TimetableNeighborhoodLoader.RouteCandidate(mineRoute, "MINE", "C/O/MINE")),
            graph,
            index);

    List<TimetableNeighborhoodLoader.FootprintNeighbor> neighbors =
        loader.footprints(List.of(other), UUID.randomUUID(), graph, index, mine);

    assertEquals(1, neighbors.size());
    assertFalse(neighbors.get(0).warnings().isEmpty(), "不可达的 route 要有警告");
    assertTrue(neighbors.get(0).warnings().get(0).contains("GHOST"));
  }

  /** 无表线路按 line 归组报告共用资源。 */
  @Test
  void unscheduledLinesAreGroupedByLine() {
    UUID mineRoute = route("MINE", List.of(A, C));
    UUID x1 = route("X1", List.of(B, D));
    UUID x2 = route("X2", List.of(D, B));
    UUID y1 = route("Y1", List.of(D, D));
    TimetableNeighborhoodLoader loader = loader();
    TimetableFootprint mine =
        loader.footprintOf(
            UUID.randomUUID(),
            "C/O/MINE",
            List.of(new TimetableNeighborhoodLoader.RouteCandidate(mineRoute, "MINE", "C/O/MINE")),
            graph,
            index);

    List<TimetableNeighborhoodLoader.UnscheduledNeighbor> unscheduled =
        loader.unscheduled(
            List.of(
                new TimetableNeighborhoodLoader.RouteCandidate(x1, "X1", "C/O/X"),
                new TimetableNeighborhoodLoader.RouteCandidate(x2, "X2", "C/O/X"),
                new TimetableNeighborhoodLoader.RouteCandidate(y1, "Y1", "C/O/Y")),
            graph,
            index,
            mine);

    assertEquals(1, unscheduled.size(), () -> unscheduled.toString());
    assertEquals("C/O/X", unscheduled.get(0).displayCode());
    assertTrue(unscheduled.get(0).sharedResources() >= 2);
  }

  /** 跨日对齐：邻表 23:50 的班次投影到我 00:00 起的窗口里是 -600 秒，而不是被丢掉或落在 +23 小时。 */
  @Test
  void projectionAlignsNeighborTripsAcrossMidnight() {
    UUID mineRoute = route("MINE", List.of(A, C));
    UUID otherRoute = route("OTHER", List.of(B, D));
    Timetable other =
        publishedWithTrip(
            UUID.randomUUID(),
            "NIGHT",
            otherRoute,
            "OTHER",
            23 * 3600,
            25 * 3600,
            23 * 3600 + 50 * 60);
    TimetableNeighborhoodLoader loader = loader();
    TimetableFootprint mine = footprintOfMine(loader, mineRoute);

    List<NeighborTimetable> neighbors =
        loader.project(
            List.of(other),
            UUID.randomUUID(),
            graph,
            index,
            mine,
            0,
            2 * 3600,
            ZoneId.of("UTC"),
            LocalDate.of(2026, 3, 2));

    assertEquals(1, neighbors.size());
    List<Integer> starts =
        neighbors.get(0).movements().stream().map(m -> m.startSeconds()).sorted().toList();
    assertEquals(List.of(-600), starts, "只保留落在我窗口附近的那一份");
    assertEquals(Optional.of("C/O/NIGHT"), neighbors.get(0).movements().get(0).owner());
  }

  /** 邻表落库的时分与当前图重算不一致：标 stale 并警告，但运行照常投影、时刻用落库值。 */
  @Test
  void staleNeighborIsFlaggedButStillProjected() {
    UUID mineRoute = route("MINE", List.of(A, C));
    UUID otherRoute = route("OTHER", List.of(B, D));
    Timetable other =
        new Timetable(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "STALE",
            "stale",
            TimetableStatus.PUBLISHED,
            ZoneId.of("UTC"),
            5 * 3600,
            6 * 3600,
            List.of(
                new TimetableRoutePlan(
                    otherRoute,
                    "OTHER",
                    1,
                    List.of(
                        new TimetableStop(0, Optional.empty(), Optional.of(B), 0, 0),
                        // 当前图上 B→D 是 20 秒，落库却写着 60 秒：邻表基于旧图。
                        new TimetableStop(1, Optional.empty(), Optional.of(D), 60, 60)),
                    B,
                    D,
                    Optional.empty(),
                    Optional.empty())),
            List.of(
                new TimetableTrip(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    otherRoute,
                    0,
                    "OTHER-001",
                    5 * 3600 + 600,
                    Optional.empty())),
            List.of(),
            Optional.empty(),
            Instant.EPOCH,
            Instant.EPOCH);
    TimetableNeighborhoodLoader loader = loader();
    TimetableFootprint mine = footprintOfMine(loader, mineRoute);

    List<NeighborTimetable> neighbors =
        loader.project(
            List.of(other),
            UUID.randomUUID(),
            graph,
            index,
            mine,
            5 * 3600,
            3600,
            ZoneId.of("UTC"),
            LocalDate.of(2026, 3, 2));

    assertEquals(1, neighbors.size());
    assertTrue(neighbors.get(0).staleAgainstGraph());
    assertFalse(neighbors.get(0).warnings().isEmpty());
    assertFalse(neighbors.get(0).movements().isEmpty(), "旧图的表照样参与检查");
    TimetableConflictChecker.RouteProfile profile = neighbors.get(0).profiles().get(otherRoute);
    assertEquals(
        60,
        profile.segments().get(0).exitOffset(profile.segments().get(0).edges().size() - 1),
        "时刻以落库值为准");
  }

  /** 时区不同：按参考日零点偏移换算并标记。邻表在东八区 08:00 的班次，对 UTC 零点的我来说就是 0 秒。 */
  @Test
  void zoneDifferenceIsApproximatedAndFlagged() {
    UUID mineRoute = route("MINE", List.of(A, C));
    UUID otherRoute = route("OTHER", List.of(B, D));
    Timetable other =
        new Timetable(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "CST",
            "cst",
            TimetableStatus.PUBLISHED,
            ZoneId.of("Asia/Shanghai"),
            7 * 3600,
            9 * 3600,
            List.of(plan(otherRoute, "OTHER", B, D)),
            List.of(
                new TimetableTrip(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    otherRoute,
                    0,
                    "OTHER-001",
                    8 * 3600,
                    Optional.empty())),
            List.of(),
            Optional.empty(),
            Instant.EPOCH,
            Instant.EPOCH);
    TimetableNeighborhoodLoader loader = loader();
    TimetableFootprint mine = footprintOfMine(loader, mineRoute);

    List<NeighborTimetable> neighbors =
        loader.project(
            List.of(other),
            UUID.randomUUID(),
            graph,
            index,
            mine,
            0,
            2 * 3600,
            ZoneId.of("UTC"),
            LocalDate.of(2026, 3, 2));

    assertEquals(1, neighbors.size());
    assertTrue(neighbors.get(0).zoneApproximated());
    assertTrue(
        neighbors.get(0).movements().stream().anyMatch(m -> m.startSeconds() == 0),
        () -> neighbors.get(0).movements().toString());
  }

  private TimetableFootprint footprintOfMine(TimetableNeighborhoodLoader loader, UUID mineRoute) {
    return loader.footprintOf(
        UUID.randomUUID(),
        "C/O/MINE",
        List.of(new TimetableNeighborhoodLoader.RouteCandidate(mineRoute, "MINE", "C/O/MINE")),
        graph,
        index);
  }

  private Timetable publishedWithTrip(
      UUID lineId, String code, UUID routeId, String routeCode, int start, int end, int departure) {
    RouteDefinition definition = definitions.get(routeId);
    return new Timetable(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        lineId,
        code,
        code,
        TimetableStatus.PUBLISHED,
        ZoneId.of("UTC"),
        start,
        end,
        List.of(
            plan(
                routeId,
                routeCode,
                definition.waypoints().get(0).value(),
                definition.waypoints().get(1).value())),
        List.of(
            new TimetableTrip(
                UUID.randomUUID(),
                UUID.randomUUID(),
                routeId,
                0,
                routeCode + "-001",
                Math.floorMod(departure, 86400),
                Optional.empty())),
        List.of(),
        Optional.empty(),
        Instant.EPOCH,
        Instant.EPOCH);
  }

  // ------------------------------------------------------------------ 夹具

  private TimetableNeighborhoodLoader loader() {
    return new TimetableNeighborhoodLoader(
        new TimetableTimingCalculator(),
        TimetableTestFixtures.perEdgeSpeedModel(),
        id -> Optional.ofNullable(definitions.get(id)),
        id -> stops.getOrDefault(id, List.of()),
        timetable -> "C/O/" + timetable.code());
  }

  private UUID route(String code, List<String> nodes) {
    UUID id = TimetableTestFixtures.routeId(code);
    definitions.put(id, TimetableTestFixtures.route(code, nodes));
    stops.put(id, TimetableTestFixtures.stops(id, nodes.size(), 0));
    return id;
  }

  private static TimetableRoutePlan plan(
      UUID routeId, String code, String origin, String terminal) {
    return new TimetableRoutePlan(
        routeId,
        code,
        1,
        List.of(
            new TimetableStop(0, Optional.empty(), Optional.of(origin), 0, 0),
            new TimetableStop(1, Optional.empty(), Optional.of(terminal), 20, 20)),
        origin,
        terminal,
        Optional.empty(),
        Optional.empty());
  }

  private static Timetable timetable(UUID lineId, String code, UUID routeId, String routeCode) {
    RouteDefinition definition = TimetableTestFixtures.route(routeCode, List.of(A, C));
    return new Timetable(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        lineId,
        code,
        code,
        TimetableStatus.PUBLISHED,
        ZoneId.of("UTC"),
        5 * 3600,
        6 * 3600,
        List.of(
            plan(
                routeId,
                routeCode,
                definition.waypoints().get(0).value(),
                definition.waypoints().get(1).value())),
        List.of(),
        List.of(),
        Optional.empty(),
        Instant.EPOCH,
        Instant.EPOCH);
  }
}
