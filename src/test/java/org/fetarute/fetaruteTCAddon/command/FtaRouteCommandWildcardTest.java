package org.fetarute.fetaruteTCAddon.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

  private static List<String> codes(List<Route> routes, String pattern) {
    return FtaRouteCommand.matchingRoutes(routes, pattern).stream().map(Route::code).toList();
  }

  private static Route route(String code) {
    return new Route(
        UUID.randomUUID(),
        code,
        LINE,
        code,
        Optional.empty(),
        RoutePatternType.LOCAL,
        RouteOperationType.OPERATION,
        Optional.empty(),
        Optional.empty(),
        Map.of(),
        Instant.EPOCH,
        Instant.EPOCH);
  }
}
