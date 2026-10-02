package org.fetarute.fetaruteTCAddon.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 逗号分隔的列表补全：客户端不认不带引号的逗号，多个值时候选一律带双引号。 */
class CommaListInputTest {

  @Test
  void aSingleUnquotedValueStaysPlain() {
    CommaListInput typed = CommaListInput.of("m");

    assertFalse(typed.blank());
    assertEquals("m", typed.prefix());
    assertEquals(List.of("MT"), typed.complete("MT"));
    assertTrue(CommaListInput.of("").blank());
  }

  @Test
  void aCommaSwitchesToQuotedCandidates() {
    CommaListInput typed = CommaListInput.of("MT,w");

    assertEquals("MT,", typed.head());
    assertEquals("w", typed.prefix());
    assertEquals(Set.of("mt"), typed.chosen());
    assertEquals(List.of("\"MT,WS\"", "\"MT,WS,"), typed.complete("WS"), "补成带引号的，不留下会判错的逗号");
  }

  @Test
  void anOpeningQuoteIsKeptAndAClosedListCanBeExtended() {
    assertEquals(List.of("\"MT\"", "\"MT,"), CommaListInput.of("\"").complete("MT"));
    CommaListInput closed = CommaListInput.of("\"MT,WS\"");
    assertEquals("MT,", closed.head());
    assertEquals("ws", closed.prefix());
    assertEquals(List.of("\"MT,WS\"", "\"MT,WS,"), closed.complete("WS"));
  }
}
