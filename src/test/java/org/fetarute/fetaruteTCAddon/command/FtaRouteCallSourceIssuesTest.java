package org.fetarute.fetaruteTCAddon.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.call.CallCatalog;
import org.fetarute.fetaruteTCAddon.call.CallService;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;
import org.junit.jupiter.api.Test;

/** 交路校验报没有车源的叫车方向：挂在本次校验范围里能跑这一趟的交路上，范围外的方向不报。 */
class FtaRouteCallSourceIssuesTest {

  private static final UUID N1 = UUID.randomUUID();
  private static final UUID N2 = UUID.randomUUID();
  private static final UUID S1 = UUID.randomUUID();

  @Test
  void issuesAttachToTheValidatedRoutesOfEachDirection() {
    List<CallService.UnsourcedDirection> unsourced =
        List.of(direction("NTA", N1, "WS-N1", N2, "WS-N2"), direction("AAA", S1, "WS-S1"));
    Set<String> issueRoutes = new LinkedHashSet<>();
    List<FtaRouteCommand.RouteValidationEntry> entries = new ArrayList<>();

    FtaRouteCommand.addCallSourceIssues(
        unsourced,
        Set.of(N1, N2),
        direction -> Map.of("destination", direction.direction().destination().stationCode()),
        issueRoutes,
        entries);

    assertEquals(1, entries.size(), "开往 AAA 的方向不在本次校验范围里，不报");
    assertEquals("WS-N1/WS-N2", entries.get(0).routeCode());
    assertEquals("command.route.validate.call-no-source", entries.get(0).issue().key());
    assertEquals("NTA", entries.get(0).issue().params().get("destination"));
    assertEquals(Set.of("WS-N1", "WS-N2"), issueRoutes);
  }

  @Test
  void onlyTheValidatedRouteIsNamed() {
    Set<String> issueRoutes = new LinkedHashSet<>();
    List<FtaRouteCommand.RouteValidationEntry> entries = new ArrayList<>();

    FtaRouteCommand.addCallSourceIssues(
        List.of(direction("NTA", N1, "WS-N1", N2, "WS-N2")),
        Set.of(N2),
        direction -> Map.of(),
        issueRoutes,
        entries);

    assertEquals("WS-N2", entries.get(0).routeCode());
    assertTrue(issueRoutes.contains("WS-N2") && !issueRoutes.contains("WS-N1"));
  }

  private static CallService.UnsourcedDirection direction(String destination, Object... routes) {
    List<CallCatalog.CallRoute> list = new ArrayList<>();
    for (int i = 0; i < routes.length; i += 2) {
      list.add(
          new CallCatalog.CallRoute(
              (UUID) routes[i],
              (String) routes[i + 1],
              1,
              CallCatalog.Origin.STANDBY,
              "SURC:S:AAA:1"));
    }
    return new CallService.UnsourcedDirection(
        new PidsStationKey("SURC", "PPK"),
        new CallCatalog.CallDirection(
            "key-" + destination,
            "SURC",
            UUID.randomUUID(),
            "WS",
            RoutePatternType.LOCAL,
            new PidsStationKey("SURC", destination),
            Set.of(),
            list));
  }
}
