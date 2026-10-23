package org.fetarute.fetaruteTCAddon.dispatcher.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.junit.jupiter.api.Test;

/** 加解编指令的写法与跨交路校验。 */
class RouteCouplingDirectivesTest {

  private static final UUID ROUTE = UUID.randomUUID();

  private static RouteStop stop(int seq, String node, RouteStopPassType pass, String notes) {
    return new RouteStop(
        ROUTE,
        seq,
        Optional.empty(),
        Optional.of(node),
        Optional.empty(),
        pass,
        Optional.ofNullable(notes));
  }

  @Test
  void decoupleParsesAllThreeActions() {
    RouteCouplingDirectives.Decouple go =
        RouteCouplingDirectives.parseDecouple("REAR:4:GOTO:LINE-B").value().orElseThrow();
    assertEquals(RouteCouplingDirectives.End.REAR, go.end());
    assertEquals(4, go.cars());
    assertEquals(RouteCouplingDirectives.Action.GOTO, go.action());
    assertEquals(Optional.of("LINE-B"), go.target());

    assertEquals(
        RouteCouplingDirectives.Action.IDLE,
        RouteCouplingDirectives.parseDecouple("front:2:idle").value().orElseThrow().action());
    assertEquals(
        RouteCouplingDirectives.Action.RETURN,
        RouteCouplingDirectives.parseDecouple("FRONT:2:RETURN").value().orElseThrow().action());
  }

  @Test
  void decoupleErrorsNameTheWrongPart() {
    assertEquals(
        Optional.of("decouple-end"), RouteCouplingDirectives.parseDecouple("MID:4:IDLE").error());
    assertEquals(
        Optional.of("decouple-cars"), RouteCouplingDirectives.parseDecouple("REAR:0:IDLE").error());
    assertEquals(
        Optional.of("decouple-cars"), RouteCouplingDirectives.parseDecouple("REAR:x:IDLE").error());
    assertEquals(
        Optional.of("decouple-action"),
        RouteCouplingDirectives.parseDecouple("REAR:4:STAY").error());
    assertEquals(
        Optional.of("decouple-goto-missing"),
        RouteCouplingDirectives.parseDecouple("REAR:4:GOTO").error());
    assertEquals(
        Optional.of("decouple-syntax"),
        RouteCouplingDirectives.parseDecouple("REAR:4:IDLE:X").error());
    assertEquals(
        Optional.of("decouple-syntax"), RouteCouplingDirectives.parseDecouple("REAR").error());
  }

  @Test
  void coupleParsesWithAndWithoutWait() {
    RouteCouplingDirectives.Couple plain =
        RouteCouplingDirectives.parseCouple("FROM:LINE-BU").value().orElseThrow();
    assertEquals("LINE-BU", plain.from());
    assertEquals(OptionalInt.empty(), plain.waitSeconds());
    assertEquals(
        OptionalInt.of(90),
        RouteCouplingDirectives.parseCouple("from:LINE-BU:wait:90")
            .value()
            .orElseThrow()
            .waitSeconds());
    assertEquals(
        Optional.of("couple-wait"), RouteCouplingDirectives.parseCouple("FROM:X:WAIT:-1").error());
    assertEquals(Optional.of("couple-syntax"), RouteCouplingDirectives.parseCouple("TO:X").error());
    assertEquals(
        Optional.of("couple-syntax"), RouteCouplingDirectives.parseCouple("FROM:X:HOLD:9").error());
  }

  @Test
  void directivesAreReadFromTheStopNotes() {
    RouteStop at =
        stop(1, "OP:S:MID:1", RouteStopPassType.STOP, "CHANGE:OP:L2\nDECP REAR:4:GOTO:LINE-B");
    assertEquals(
        RouteCouplingDirectives.Action.GOTO,
        RouteCouplingDirectives.decouple(at).orElseThrow().action());
    assertTrue(RouteCouplingDirectives.couple(at).isEmpty());
  }

  @Test
  void localRulesRejectEndsPassesAndDuplicates() {
    List<RouteStop> stops =
        List.of(
            stop(0, "OP:S:A:1", RouteStopPassType.STOP, "DECP REAR:4:IDLE"),
            stop(1, "OP:S:B:1", RouteStopPassType.PASS, "COPL FROM:X"),
            stop(2, "OP:S:C:1", RouteStopPassType.STOP, "DECP REAR:4:IDLE\nDECP FRONT:2:IDLE"),
            stop(3, "OP:S:D:1", RouteStopPassType.STOP, "DECP REAR:4:STAY"),
            stop(4, "OP:S:E:1", RouteStopPassType.TERMINATE, null));

    List<String> keys =
        RouteCouplingDirectives.validateLocal(stops).stream()
            .map(RouteCouplingDirectives.Issue::key)
            .toList();

    assertEquals(
        List.of("coupling-at-end", "coupling-at-pass", "coupling-duplicate", "decouple-action"),
        keys);
  }

  @Test
  void cuttingOffTheHeadToIdleIsRejected() {
    List<RouteStop> stops =
        List.of(
            stop(0, "OP:S:A:1", RouteStopPassType.STOP, null),
            stop(1, "OP:S:B:1", RouteStopPassType.STOP, "DECP FRONT:2:IDLE"),
            stop(2, "OP:S:C:1", RouteStopPassType.STOP, "DECP REAR:2:IDLE"),
            stop(3, "OP:S:D:1", RouteStopPassType.TERMINATE, null));

    List<RouteCouplingDirectives.Issue> issues = RouteCouplingDirectives.validateLocal(stops);

    assertEquals(
        List.of(new RouteCouplingDirectives.Issue("decouple-front-idle", Map.of("seq", "1"))),
        issues,
        "切车尾就地待命不挡路");
  }

  @Test
  void targetsMustBeInTheSameGroupAndStartOrEndHere() {
    RouteCouplingDirectives.RouteRef trunk =
        new RouteCouplingDirectives.RouteRef(
            "TRUNK",
            "main",
            List.of(
                stop(0, "OP:S:A:1", RouteStopPassType.STOP, null),
                stop(
                    1,
                    "OP:S:MID:2",
                    RouteStopPassType.STOP,
                    "DECP REAR:4:GOTO:BRANCH\nCOPL FROM:BACK"),
                stop(2, "OP:S:Z:1", RouteStopPassType.TERMINATE, null)));
    RouteCouplingDirectives.RouteRef branch =
        new RouteCouplingDirectives.RouteRef(
            "BRANCH",
            "main",
            List.of(
                stop(0, "OP:S:MID:1", RouteStopPassType.STOP, null),
                stop(1, "OP:S:T:1", RouteStopPassType.TERMINATE, null)));
    RouteCouplingDirectives.RouteRef back =
        new RouteCouplingDirectives.RouteRef(
            "BACK",
            "main",
            List.of(
                stop(0, "OP:S:T:1", RouteStopPassType.STOP, null),
                stop(1, "OP:S:MID:1", RouteStopPassType.TERMINATE, null)));

    assertTrue(
        RouteCouplingDirectives.validateTargets(trunk, List.of(trunk, branch, back)).isEmpty(),
        "同站不同站台算本站");

    RouteCouplingDirectives.RouteRef otherGroup =
        new RouteCouplingDirectives.RouteRef("BRANCH", "other", branch.stops());
    RouteCouplingDirectives.RouteRef wrongEnd =
        new RouteCouplingDirectives.RouteRef("BACK", "main", branch.stops());
    List<String> keys =
        RouteCouplingDirectives.validateTargets(trunk, List.of(trunk, otherGroup, wrongEnd))
            .stream()
            .map(RouteCouplingDirectives.Issue::key)
            .toList();
    assertEquals(List.of("coupling-target-group", "couple-target-end"), keys);
    assertEquals(
        List.of("coupling-target-missing", "coupling-target-missing"),
        RouteCouplingDirectives.validateTargets(trunk, List.of(trunk)).stream()
            .map(RouteCouplingDirectives.Issue::key)
            .toList());
  }
}
