package org.fetarute.fetaruteTCAddon.call;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.LineServiceType;
import org.fetarute.fetaruteTCAddon.company.model.LineStatus;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SimpleTicketAssigner;
import org.fetarute.fetaruteTCAddon.display.pids.PidsRow;
import org.junit.jupiter.api.Test;

/** 有表线路上叫来的车的生命周期：接哪些待命车、回库途中、终点等多久、车库让表定、回哪个车库。 */
class CallLifecycleTest {

  private static final Instant T = Instant.EPOCH;
  private static final Predicate<String> BOUND = name -> name.startsWith("bound");

  /** 待命车：叫来的车都能接；按表运行的交路只接叫来的车；其余交路不接绑着时刻表交路的车。 */
  @Test
  void standbyTrainsForCalls() {
    LayoverRegistry.LayoverCandidate called =
        candidate("called-1", Map.of(SimpleTicketAssigner.TAG_CALLED_TRAIN, "x@SURC:PPK"));
    LayoverRegistry.LayoverCandidate free = candidate("free-1", Map.of());
    LayoverRegistry.LayoverCandidate bound = candidate("bound-1", Map.of());

    assertTrue(CallService.acceptsStandby(called, true, BOUND));
    assertFalse(CallService.acceptsStandby(free, true, BOUND), "没绑交路的车可能正等着跑首班或等回收");
    assertTrue(CallService.acceptsStandby(free, false, BOUND));
    assertFalse(CallService.acceptsStandby(bound, false, BOUND));
    assertFalse(CallService.acceptsStandby(null, false, BOUND));
  }

  /** 回库途中：跑在回库交路上、又不是这一趟叫车跑的交路。叫车方向本身是回库交路时那一趟照常算叫来的车。 */
  @Test
  void returningMeansOnAnotherReturnRoute() {
    UUID returnRoute = UUID.randomUUID();
    UUID callRoute = UUID.randomUUID();
    Optional<RouteOperationType> ret = Optional.of(RouteOperationType.RETURN);

    assertTrue(CallService.returning(ret, Optional.of(returnRoute), Optional.of(callRoute)));
    assertFalse(
        CallService.returning(ret, Optional.of(returnRoute), Optional.of(returnRoute)),
        "叫的就是回库交路上的一趟");
    assertTrue(CallService.returning(ret, Optional.of(returnRoute), Optional.empty()), "没记叫车交路的旧车");
    assertFalse(
        CallService.returning(
            Optional.of(RouteOperationType.OPERATION),
            Optional.of(callRoute),
            Optional.of(callRoute)));
    assertFalse(CallService.returning(Optional.empty(), Optional.empty(), Optional.empty()));
  }

  /** 终点等候：刚跑完的交路按表运行时不等（终点股道留给表定列车），其余照配置等。 */
  @Test
  void terminalWaitIsSkippedOnTimetableRoutes() {
    UUID managed = UUID.randomUUID();
    UUID free = UUID.randomUUID();
    Duration wait = Duration.ofSeconds(60);
    Predicate<UUID> timetable = managed::equals;

    assertEquals(
        Duration.ZERO,
        CallService.terminalWait(
            candidate("c", Map.of("FTA_ROUTE_ID", managed.toString())), timetable, wait));
    assertEquals(
        wait,
        CallService.terminalWait(
            candidate("c", Map.of("FTA_ROUTE_ID", free.toString())), timetable, wait));
    assertEquals(wait, CallService.terminalWait(candidate("c", Map.of()), timetable, wait));
  }

  /** 车库让表定：固定股道比股道，有一边是车库池（DYNAMIC）时比车库。 */
  @Test
  void depotsMatchByTrackOrByPool() {
    assertTrue(CallService.sameDepot("SURC:D:HHU:3", "surc:d:hhu:3"));
    assertFalse(CallService.sameDepot("SURC:D:HHU:3", "SURC:D:HHU:2"), "同车库不同固定股道不挡");
    assertTrue(CallService.sameDepot("DYNAMIC:SURC:D:OFL", "SURC:D:OFL:1"));
    assertTrue(CallService.sameDepot("SURC:D:OFL:2", "DYNAMIC:SURC:D:OFL"));
    assertFalse(CallService.sameDepot("DYNAMIC:SURC:D:OFL", "DYNAMIC:SURC:D:HHU"));
    assertFalse(CallService.sameDepot("SURC:D:OFL:1", ""));
  }

  /** 回库先走同一线路、同一交路组、从车所在终点出发的回库交路：车从哪个车库来就回哪个车库。 */
  @Test
  void returnRoutePreferenceFollowsTheRouteGroup() {
    Line mt = line("MT");
    RouteDefinitionCache.RouteEntry out =
        entry(mt, "MT-1N", RouteOperationType.OPERATION, "MT-1", "SURC:S:AAA:1");
    RouteDefinitionCache.RouteEntry otherBack =
        entry(mt, "MT-2O", RouteOperationType.RETURN, "MT-2", "SURC:S:PPK:1");
    RouteDefinitionCache.RouteEntry farEnd =
        entry(mt, "MT-1A", RouteOperationType.RETURN, "MT-1", "SURC:S:AAA:1");
    RouteDefinitionCache.RouteEntry back =
        entry(mt, "MT-1O", RouteOperationType.RETURN, "MT-1", "SURC:S:PPK:1");
    RouteDefinitionCache.RouteEntry ungrouped =
        entry(mt, "MT-9", RouteOperationType.OPERATION, null, "SURC:S:AAA:1");
    List<RouteDefinitionCache.RouteEntry> entries =
        List.of(out, otherBack, farEnd, back, ungrouped);

    assertEquals(
        Optional.of(back.routeId()),
        CallService.sameGroupReturnRoute(
            entries, out.routeId(), Optional.of(NodeId.of("SURC:S:PPK:2"))),
        "另一端出发的同组回库交路不挑；同站别的站台也算这个终点");
    assertEquals(
        Optional.of(farEnd.routeId()),
        CallService.sameGroupReturnRoute(entries, out.routeId(), Optional.empty()),
        "不知道车在哪时不按出发终点筛");
    assertEquals(
        Optional.empty(),
        CallService.sameGroupReturnRoute(entries, ungrouped.routeId(), Optional.empty()),
        "交路没写交路组时不挑");
  }

  /** 到站预计的后车间隔：同一站台本车之后最早的那一班；本车不在行里时不知道，身后没车时无穷远。 */
  @Test
  void rearGapComesFromTheNextStationForecast() {
    Instant at = Instant.parse("2026-10-09T12:00:00Z");
    PidsRow own = row("called-1", "1", at, PidsRow.Status.EN_ROUTE);
    PidsRow behind = row("ws-1", "1", at.plusSeconds(90), PidsRow.Status.EN_ROUTE);
    PidsRow further = row("ws-2", "1", at.plusSeconds(400), PidsRow.Status.PLANNED);
    PidsRow otherTrack = row("ws-3", "2", at.plusSeconds(30), PidsRow.Status.EN_ROUTE);
    PidsRow ahead = row("ws-0", "1", at.minusSeconds(60), PidsRow.Status.EN_ROUTE);
    PidsRow cancelled = row("ws-4", "1", at.plusSeconds(20), PidsRow.Status.CANCELLED);

    assertEquals(
        java.util.OptionalLong.of(90),
        CallService.rearGapSeconds(
            List.of(ahead, own, otherTrack, cancelled, behind, further), "Called-1"));
    assertEquals(
        java.util.OptionalLong.of(Long.MAX_VALUE),
        CallService.rearGapSeconds(List.of(ahead, own, otherTrack), "called-1"),
        "身后没车");
    assertEquals(
        java.util.OptionalLong.empty(),
        CallService.rearGapSeconds(List.of(ahead, behind), "called-1"),
        "本车不在下一站的行里");
  }

  private static LayoverRegistry.LayoverCandidate candidate(String name, Map<String, String> tags) {
    return new LayoverRegistry.LayoverCandidate(
        name, "SURC:S:PPK", NodeId.of("SURC:S:PPK:1"), T, tags);
  }

  private static PidsRow row(String train, String platform, Instant at, PidsRow.Status status) {
    return new PidsRow(
        status,
        "MT",
        "r",
        "PPK",
        Optional.of("SURC:PPK"),
        platform,
        at,
        java.util.OptionalLong.empty(),
        1,
        false,
        false,
        false,
        Optional.of(train),
        false,
        List.of(),
        List.of());
  }

  private static RouteDefinitionCache.RouteEntry entry(
      Line line, String code, RouteOperationType type, String group, String firstNode) {
    UUID routeId = UUID.randomUUID();
    Route route =
        new Route(
            routeId,
            code,
            line.id(),
            code,
            Optional.empty(),
            RoutePatternType.LOCAL,
            type,
            Optional.empty(),
            Optional.empty(),
            group == null ? Map.of() : Map.of("spawn_group", group),
            T,
            T);
    Operator operator =
        new Operator(
            UUID.randomUUID(),
            "SURC",
            UUID.randomUUID(),
            "SURC",
            Optional.empty(),
            Optional.empty(),
            0,
            Optional.empty(),
            Map.of(),
            T,
            T);
    return new RouteDefinitionCache.RouteEntry(
        routeId,
        new RouteDefinition(
            new RouteId("SURC:" + line.code() + ":" + code),
            List.of(
                NodeId.of(firstNode),
                NodeId.of(firstNode.contains("PPK") ? "SURC:S:AAA:1" : "SURC:S:PPK:1")),
            Optional.empty()),
        new RouteDefinitionCache.RouteRecord(operator, line, route),
        List.of());
  }

  private static Line line(String code) {
    return new Line(
        UUID.randomUUID(),
        code,
        UUID.randomUUID(),
        code,
        Optional.empty(),
        LineServiceType.METRO,
        Optional.empty(),
        LineStatus.ACTIVE,
        Optional.empty(),
        Map.of(),
        T,
        T);
  }
}
