package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.scope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
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
            new TimetableStop(1, Optional.empty(), Optional.of(terminal), 10, 10)),
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
