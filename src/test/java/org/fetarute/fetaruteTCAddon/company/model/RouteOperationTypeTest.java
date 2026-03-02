package org.fetarute.fetaruteTCAddon.company.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class RouteOperationTypeTest {

  @Test
  void shouldParseOperationTypesAndAliases() {
    assertEquals(RouteOperationType.CREATE, RouteOperationType.fromToken("create").orElseThrow());
    assertEquals(RouteOperationType.CREATE, RouteOperationType.fromToken("crt").orElseThrow());
    assertEquals(
        RouteOperationType.OPERATION, RouteOperationType.fromToken("operation").orElseThrow());
    assertEquals(RouteOperationType.OPERATION, RouteOperationType.fromToken("op").orElseThrow());
    assertEquals(RouteOperationType.RETURN, RouteOperationType.fromToken("return").orElseThrow());
    assertEquals(RouteOperationType.RETURN, RouteOperationType.fromToken("ret").orElseThrow());
  }

  @Test
  void shouldRejectUnknownOperationType() {
    assertTrue(RouteOperationType.fromToken("unknown").isEmpty());
    assertTrue(RouteOperationType.fromToken("").isEmpty());
    assertTrue(RouteOperationType.fromToken(null).isEmpty());
  }
}
