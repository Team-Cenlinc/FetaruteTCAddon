package org.fetarute.fetaruteTCAddon.display.hud.bossbar;

import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.Consumer;

public final class BossBarProgressExpression {

  private BossBarProgressExpression() {}

  public static OptionalDouble evaluate(
      String expression, Map<String, String> placeholders, Consumer<String> debugLogger) {
    if (expression == null || expression.isBlank() || placeholders == null) {
      return OptionalDouble.empty();
    }
    String trimmed = expression.trim();
    String direct = placeholders.get(trimmed);
    if (direct != null) {
      OptionalDouble parsed = parseDouble(direct);
      if (parsed.isPresent()) {
        return parsed;
      }
    }
    String resolved = BossBarHudTemplateRenderer.applyPlaceholders(trimmed, placeholders);
    try {
      double value = new Parser(resolved).parse();
      if (Double.isFinite(value)) {
        return OptionalDouble.of(value);
      }
      return OptionalDouble.empty();
    } catch (IllegalArgumentException ex) {
      if (debugLogger != null) {
        debugLogger.accept("BossBar progress expression parse failed: " + ex.getMessage());
      }
      return OptionalDouble.empty();
    }
  }

  private static OptionalDouble parseDouble(String value) {
    if (value == null || value.isBlank()) {
      return OptionalDouble.empty();
    }
    try {
      double parsed = Double.parseDouble(value.trim());
      if (!Double.isFinite(parsed)) {
        return OptionalDouble.empty();
      }
      return OptionalDouble.of(parsed);
    } catch (NumberFormatException ex) {
      return OptionalDouble.empty();
    }
  }

  private static final class Parser {
    private final String input;
    private int index;

    private Parser(String input) {
      this.input = input == null ? "" : input;
    }

    private double parse() {
      double value = parseExpression();
      skipWhitespace();
      if (index < input.length()) {
        throw new IllegalArgumentException("Invalid expression: " + input);
      }
      return value;
    }

    private double parseExpression() {
      double value = parseTerm();
      while (true) {
        skipWhitespace();
        if (match('+')) {
          value += parseTerm();
        } else if (match('-')) {
          value -= parseTerm();
        } else {
          break;
        }
      }
      return value;
    }

    private double parseTerm() {
      double value = parseFactor();
      while (true) {
        skipWhitespace();
        if (match('*')) {
          value *= parseFactor();
        } else if (match('/')) {
          value /= parseFactor();
        } else {
          break;
        }
      }
      return value;
    }

    private double parseFactor() {
      skipWhitespace();
      if (match('+')) {
        return parseFactor();
      }
      if (match('-')) {
        return -parseFactor();
      }
      if (match('(')) {
        double value = parseExpression();
        skipWhitespace();
        if (!match(')')) {
          throw new IllegalArgumentException("Missing closing parenthesis: " + input);
        }
        return value;
      }
      return parseNumber();
    }

    private double parseNumber() {
      skipWhitespace();
      int start = index;
      boolean seenDot = false;
      while (index < input.length()) {
        char ch = input.charAt(index);
        if (ch == '.') {
          if (seenDot) {
            break;
          }
          seenDot = true;
          index++;
          continue;
        }
        if (!Character.isDigit(ch)) {
          break;
        }
        index++;
      }
      if (start == index) {
        throw new IllegalArgumentException("Missing number: " + input);
      }
      String number = input.substring(start, index);
      return Double.parseDouble(number);
    }

    private void skipWhitespace() {
      while (index < input.length()) {
        char ch = input.charAt(index);
        if (!Character.isWhitespace(ch)) {
          break;
        }
        index++;
      }
    }

    private boolean match(char target) {
      if (index < input.length() && input.charAt(index) == target) {
        index++;
        return true;
      }
      return false;
    }
  }
}
