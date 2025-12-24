package org.fetarute.fetaruteTCAddon.company.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class RoutePatternTypeTest {

  @Test
  void shouldParsePatternTypesAndAliases() {
    assertEquals(RoutePatternType.LOCAL, RoutePatternType.fromToken("local").orElseThrow());
    assertEquals(RoutePatternType.RAPID, RoutePatternType.fromToken("rapid").orElseThrow());
    assertEquals(RoutePatternType.NEO_RAPID, RoutePatternType.fromToken("neo_rapid").orElseThrow());
    assertEquals(RoutePatternType.EXPRESS, RoutePatternType.fromToken("express").orElseThrow());
    assertEquals(
        RoutePatternType.LIMITED_EXPRESS, RoutePatternType.fromToken("ltd_express").orElseThrow());
  }

  @Test
  void shouldRejectUnknownPatternType() {
    assertTrue(RoutePatternType.fromToken("unknown").isEmpty());
    assertTrue(RoutePatternType.fromToken("").isEmpty());
  }
}
