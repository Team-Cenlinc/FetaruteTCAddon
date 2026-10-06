package org.fetarute.fetaruteTCAddon.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class CommandUxTest {

  @Test
  void quoteCommandArgumentWrapsSimpleValue() {
    assertEquals("\"SURN:A:B:1:00\"", CommandUx.quoteCommandArgument("SURN:A:B:1:00"));
  }

  @Test
  void quoteCommandArgumentEscapesQuotesAndBackslashes() {
    assertEquals("\"A\\\\B\\\"C\"", CommandUx.quoteCommandArgument("A\\B\"C"));
  }

  @Test
  void quoteCommandArgumentHandlesMissingValueAsEmptyQuotedArgument() {
    assertEquals("\"\"", CommandUx.quoteCommandArgument(null));
  }

  @Test
  void commandArgumentQuotesSafeTokensToo() {
    assertEquals("\"SURC:LT-1\"", CommandUx.commandArgument("SURC:LT-1"));
  }

  @Test
  void commandArgumentQuotesUnsafeTokens() {
    assertEquals("\"Line A\"", CommandUx.commandArgument("Line A"));
  }

  @Test
  void unquoteCommandArgumentRestoresEscapedText() {
    assertEquals("A\\B\"C", CommandUx.unquoteCommandArgument("\"A\\\\B\\\"C\""));
  }

  @Test
  void unquotedSafeFollowsBrigadierRules() {
    assertTrue(CommandUx.unquotedSafe("MT-1N_Short.v2+"));
    assertFalse(CommandUx.unquotedSafe("OFL:HHU"));
    assertFalse(CommandUx.unquotedSafe("*"));
    assertFalse(CommandUx.unquotedSafe("整体利用方案"));
    assertFalse(CommandUx.unquotedSafe("a b"));
    assertFalse(CommandUx.unquotedSafe(""));
  }

  @Test
  void suggestionQuotesOnlyWhenNeededOrAlreadyQuoted() {
    assertEquals("HHU", CommandUx.suggestion("HHU", false));
    assertEquals("\"HHU\"", CommandUx.suggestion("HHU", true));
    assertEquals("\"OFL:HHU\"", CommandUx.suggestion("OFL:HHU", false));
  }

  @Test
  void suggestionsFilterByPrefixIgnoringLeadingQuote() {
    List<String> values = List.of("HHU", "OFL:HHU", "SPB");
    assertEquals(List.of("\"OFL:HHU\"", "HHU", "SPB"), sorted(CommandUx.suggestions(values, "")));
    assertEquals(List.of("\"OFL:HHU\""), CommandUx.suggestions(values, "\"ofl"));
    assertEquals(List.of("\"HHU\""), CommandUx.suggestions(values, "\"h"));
    assertEquals("ofl:h", CommandUx.suggestionPrefix(" \"OFL:H "));
  }

  private static List<String> sorted(List<String> values) {
    return values.stream().sorted().toList();
  }
}
