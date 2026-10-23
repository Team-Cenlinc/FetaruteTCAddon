package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.junit.jupiter.api.Test;

/**
 * 两个交路组起终点站台组相同（慢车、快车都在 A、E 两站之间往返，各用自己的股道）：方向键同名，相位、往返对锚定、周期模板 不能互相覆盖。
 *
 * <p>形状：慢车 A1→E1 / E1→A1，快车 A2→E2 / E2→A2，B–C–D 上下行各一股道两组共用。A 端两个站台各有出入库线路，E 没有（原地折返端）， 所以两组的往返对都以
 * A→E 为正向，反向在 E 按"本组正向走行 + 本组折返"锚定。慢车每站停、快车不停，两组的锚定相位不同。
 */
class TimetableBuilderSharedEndpointsTest {

  private static final Instant BUILT_AT = Instant.parse("2026-03-01T00:00:00Z");
  private static final ZoneId ZONE = ZoneId.of("UTC");
  private static final String DEP = "OP:D:DEP:1";
  private static final String A1 = "OP:S:A:1";
  private static final String A2 = "OP:S:A:2";
  private static final String B1 = "OP:S:B:1";
  private static final String C1 = "OP:S:C:1";
  private static final String D1 = "OP:S:D:1";
  private static final String E1 = "OP:S:E:1";
  private static final String E2 = "OP:S:E:2";
  private static final String B2 = "OP:S:B:2";
  private static final String C2 = "OP:S:C:2";
  private static final String D2 = "OP:S:D:2";
  private static final int LOCAL_DWELL = 60;

  private final UUID local = TimetableTestFixtures.routeId("LOC");
  private final UUID localBack = TimetableTestFixtures.routeId("LOCR");
  private final UUID rapid = TimetableTestFixtures.routeId("RAP");
  private final UUID rapidBack = TimetableTestFixtures.routeId("RAPR");

  /** 两组都排得出、一班不取消；每组的反向相对本组正向逐班同一间距（各自锚定），两组的间距不同（不是一组抄了另一组的相位）。 */
  @Test
  void bothGroupsAnchorTheirOwnReturnPair() {
    TimetableBuildResult result = new TimetableBuilder().build(input(), options(), BUILT_AT);

    assertTrue(result.success(), result::toString);
    assertTrue(result.droppedTrips().isEmpty(), () -> "取消了：" + result.droppedTrips());
    int localGap = uniformGap(result, local, localBack);
    int rapidGap = uniformGap(result, rapid, rapidBack);
    assertTrue(localGap > rapidGap, () -> "慢车每站停，锚定间距应更长：" + localGap + " / " + rapidGap);
    for (String group : List.of("local", "rapid")) {
      assertTrue(
          result.phaseNotes().stream()
              .anyMatch(note -> note.startsWith("交路组 " + group + " 往返对") && note.contains("锚定")),
          () -> group + " 没有锚定：" + result.phaseNotes());
    }
  }

  /** 各班反向发车减同一序号的正向发车：每一对都相同时返回它。 */
  private static int uniformGap(TimetableBuildResult result, UUID forwardRoute, UUID reverseRoute) {
    List<Integer> forward = departures(result, forwardRoute);
    List<Integer> reverse = departures(result, reverseRoute);
    assertFalse(forward.isEmpty(), result::toString);
    assertEquals(forward.size(), reverse.size(), result::toString);
    int gap = reverse.get(0) - forward.get(0);
    for (int i = 0; i < forward.size(); i++) {
      assertEquals(gap, reverse.get(i) - forward.get(i), "第 " + i + " 对");
    }
    return gap;
  }

  private static TimetableBuildOptions options() {
    return new TimetableBuildOptions(
        5 * 3600,
        8 * 3600,
        Duration.ofSeconds(400),
        Duration.ofSeconds(20),
        new VehicleDutyPlanner.Limits(100, 7200, TurnaroundTable.none()),
        "",
        ZONE,
        Duration.ofSeconds(TimetableBuildOptions.DEFAULT_SEPARATION_SECONDS),
        false,
        Map.of("local", 600, "rapid", 1200));
  }

  private TimetableBuilder.BuildInput input() {
    List<TimetableBuilder.RouteInput> routes =
        List.of(
            operation(local, "LOC", List.of(A1, B1, C1, D1, E1), LOCAL_DWELL, "local"),
            operation(localBack, "LOCR", List.of(E1, D2, C2, B2, A1), LOCAL_DWELL, "local"),
            operation(rapid, "RAP", List.of(A2, B1, C1, D1, E2), 0, "rapid"),
            operation(rapidBack, "RAPR", List.of(E2, D2, C2, B2, A2), 0, "rapid"),
            leg("CRL", RouteOperationType.CREATE, List.of(DEP, A1)),
            leg("REL", RouteOperationType.RETURN, List.of(A1, DEP)),
            leg("CRR", RouteOperationType.CREATE, List.of(DEP, A2)),
            leg("RER", RouteOperationType.RETURN, List.of(A2, DEP)));
    return new TimetableBuilder.BuildInput(
        UUID.nameUUIDFromBytes("SE".getBytes(StandardCharsets.UTF_8)),
        UUID.nameUUIDFromBytes("C".getBytes(StandardCharsets.UTF_8)),
        UUID.nameUUIDFromBytes("O".getBytes(StandardCharsets.UTF_8)),
        UUID.nameUUIDFromBytes("L".getBytes(StandardCharsets.UTF_8)),
        "TT1",
        "共用起终点",
        routes,
        graph(),
        TimetableTestFixtures.perEdgeSpeedModel(),
        Optional.empty());
  }

  private static TimetableBuilder.RouteInput operation(
      UUID id, String code, List<String> nodes, int dwell, String group) {
    return new TimetableBuilder.RouteInput(
        id,
        code,
        RouteOperationType.OPERATION,
        1,
        TimetableTestFixtures.route(code, nodes),
        TimetableTestFixtures.stops(id, nodes.size(), dwell),
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

  private static List<Integer> departures(TimetableBuildResult result, UUID route) {
    return result.timetable().orElseThrow().trips().stream()
        .filter(trip -> trip.routeId().equals(route))
        .map(TimetableTrip::departureSecondOfDay)
        .sorted()
        .toList();
  }

  private static RailGraph graph() {
    Map<String, NodeType> nodes = new LinkedHashMap<>();
    nodes.put(DEP, NodeType.DEPOT);
    for (String station : List.of(A1, A2, B1, C1, D1, E1, E2, B2, C2, D2)) {
      nodes.put(station, NodeType.STATION);
    }
    return TimetableTestFixtures.graph(
        nodes,
        List.of(
            new TimetableTestFixtures.Edge(DEP, A1, 50, 10.0),
            new TimetableTestFixtures.Edge(DEP, A2, 50, 10.0),
            new TimetableTestFixtures.Edge(A1, B1, 100, 10.0),
            new TimetableTestFixtures.Edge(A2, B1, 100, 10.0),
            new TimetableTestFixtures.Edge(B1, C1, 100, 10.0),
            new TimetableTestFixtures.Edge(C1, D1, 100, 10.0),
            new TimetableTestFixtures.Edge(D1, E1, 100, 10.0),
            new TimetableTestFixtures.Edge(D1, E2, 100, 10.0),
            new TimetableTestFixtures.Edge(E1, D2, 100, 10.0),
            new TimetableTestFixtures.Edge(E2, D2, 100, 10.0),
            new TimetableTestFixtures.Edge(D2, C2, 100, 10.0),
            new TimetableTestFixtures.Edge(C2, B2, 100, 10.0),
            new TimetableTestFixtures.Edge(B2, A1, 100, 10.0),
            new TimetableTestFixtures.Edge(B2, A2, 100, 10.0)));
  }
}
