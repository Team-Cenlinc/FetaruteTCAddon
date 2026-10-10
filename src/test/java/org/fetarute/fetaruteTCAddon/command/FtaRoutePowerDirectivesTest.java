package org.fetarute.fetaruteTCAddon.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.junit.jupiter.api.Test;

/** 交路备注 POWER 的校验：认不出时报错（define 拦），涉及内燃写在不停车的点上只提示（validate）。 */
class FtaRoutePowerDirectivesTest {

  private static final UUID ROUTE = UUID.randomUUID();

  private static RouteStop stop(int sequence, boolean pass, String power) {
    return new RouteStop(
        ROUTE,
        sequence,
        Optional.empty(),
        Optional.of("SURC:S:N" + sequence + ":1"),
        Optional.empty(),
        pass ? RouteStopPassType.PASS : RouteStopPassType.STOP,
        power == null ? Optional.empty() : Optional.of("POWER:" + power));
  }

  @Test
  void anUnknownSupplyIsAnError() {
    List<FtaRouteCommand.RouteValidationIssue> issues = new ArrayList<>();

    FtaRouteCommand.validatePowerDirectives(
        List.of(stop(0, false, "ptg5"), stop(1, false, "steam")), issues, false);

    assertEquals(1, issues.size());
    assertEquals("command.route.define.power-invalid", issues.get(0).key());
    assertEquals("steam", issues.get(0).params().get("value"));
  }

  @Test
  void dieselOnAPassWaypointIsOnlyAHint() {
    List<RouteStop> stops =
        List.of(stop(0, false, "ptg5"), stop(1, true, "diesel"), stop(2, true, "ptg6"));
    List<FtaRouteCommand.RouteValidationIssue> errors = new ArrayList<>();
    List<FtaRouteCommand.RouteValidationIssue> hints = new ArrayList<>();

    FtaRouteCommand.validatePowerDirectives(stops, errors, false);
    FtaRouteCommand.validatePowerDirectives(stops, hints, true);

    assertTrue(errors.isEmpty(), "define 不拦");
    assertEquals(
        List.of("command.route.define.power-deferred", "command.route.define.power-deferred"),
        hints.stream().map(FtaRouteCommand.RouteValidationIssue::key).toList(),
        "换成内燃、从内燃换回都要停车");
    assertEquals("1", hints.get(0).params().get("seq"));
  }

  @Test
  void aRouteWithPowerButNoneAtTheStartGetsAHint() {
    List<FtaRouteCommand.RouteValidationIssue> hints = new ArrayList<>();
    FtaRouteCommand.validatePowerDirectives(
        List.of(stop(0, false, null), stop(1, false, "ptg6")), hints, true);
    assertEquals(
        List.of("command.route.define.power-no-start"),
        hints.stream().map(FtaRouteCommand.RouteValidationIssue::key).toList());

    List<FtaRouteCommand.RouteValidationIssue> none = new ArrayList<>();
    FtaRouteCommand.validatePowerDirectives(
        List.of(stop(0, false, "ptg5"), stop(1, false, "ptg6")), none, true);
    assertTrue(none.isEmpty(), "首站写了就不提示");
  }
}
