package org.fetarute.fetaruteTCAddon.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("route set：交路通配")
class FtaRouteCommandWildcardTest {

  private static final UUID LINE = UUID.randomUUID();

  @Test
  @DisplayName("* 匹配全部，前缀与中间通配按代码匹配，不分大小写，按代码排序")
  void matchesRouteCodes() {
    List<Route> routes =
        List.of(route("WS-2N"), route("MT-3O"), route("MT-2F"), route("mt-3_dpexp"), route("DS-1"));

    assertEquals(List.of("DS-1", "MT-2F", "mt-3_dpexp", "MT-3O", "WS-2N"), codes(routes, "*"));
    assertEquals(List.of("MT-2F", "mt-3_dpexp", "MT-3O"), codes(routes, "MT-*"));
    assertEquals(List.of("mt-3_dpexp", "MT-3O"), codes(routes, "mt-3*"));
    assertEquals(List.of("MT-2F"), codes(routes, "*-2f"));
    assertEquals(List.of(), codes(routes, "LR-*"));
    assertEquals(List.of("DS-1"), codes(routes, "D*1"), "正则字符按字面匹配");
  }

  @Test
  @DisplayName("? 代表一个字符；出库与回送交路同样匹配")
  void questionMarkAndAllOperationTypes() {
    List<Route> routes =
        List.of(
            route("MT-1N", RouteOperationType.OPERATION),
            route("MT-1O", RouteOperationType.OPERATION),
            route("MT-1NC", RouteOperationType.CREATE),
            route("MT-1ND", RouteOperationType.RETURN));

    assertEquals(List.of("MT-1N", "MT-1O"), codes(routes, "MT-1?"));
    assertEquals(List.of("MT-1NC", "MT-1ND"), codes(routes, "mt-1n?"));
    assertEquals(List.of("MT-1N", "MT-1NC", "MT-1ND", "MT-1O"), codes(routes, "*"));
    assertTrue(FtaRouteCommand.isRoutePattern("MT-?"));
    assertTrue(FtaRouteCommand.isRoutePattern("*"));
    assertFalse(FtaRouteCommand.isRoutePattern("MT-1N"));
  }

  @Test
  @DisplayName("补全：空输入给 * 与前缀通配，输入前缀时给输入加 *，只列以输入开头的项")
  void patternSuggestions() {
    List<String> codes = List.of("MT-1N_Short", "MT-1N_ShortR", "MT-1O_ShortR", "MT-3N_DPExp");

    assertEquals(
        List.of(
            "<route>",
            "*",
            "MT-*",
            "MT-1N_*",
            "MT-1N_Short",
            "MT-1N_ShortR",
            "MT-1O_ShortR",
            "MT-3N_DPExp"),
        FtaRouteCommand.routePatternSuggestions(codes, ""));
    assertEquals(
        List.of("MT-1*", "MT-1N_*", "MT-1N_Short", "MT-1N_ShortR", "MT-1O_ShortR"),
        FtaRouteCommand.routePatternSuggestions(codes, "mt-1"));
    assertEquals(List.of("MT-3N_DPExp"), FtaRouteCommand.routePatternSuggestions(codes, "mt-3"));
    assertEquals(List.of(), FtaRouteCommand.routePatternSuggestions(codes, "mt-*"));
  }

  private static List<String> codes(List<Route> routes, String pattern) {
    return FtaRouteCommand.matchingRoutes(routes, pattern).stream().map(Route::code).toList();
  }

  private static Route route(String code) {
    return route(code, RouteOperationType.OPERATION);
  }

  private static Route route(String code, RouteOperationType operation) {
    return new Route(
        UUID.randomUUID(),
        code,
        LINE,
        code,
        Optional.empty(),
        RoutePatternType.LOCAL,
        operation,
        Optional.empty(),
        Optional.empty(),
        Map.of(),
        Instant.EPOCH,
        Instant.EPOCH);
  }
}
