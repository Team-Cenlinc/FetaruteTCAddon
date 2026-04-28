package org.fetarute.fetaruteTCAddon.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
