package org.fetarute.fetaruteTCAddon.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.control.RailSpeed;
import org.junit.jupiter.api.Test;

final class FtaGraphCommandParsingTest {

  @Test
  void parsesSpeedDefaultsToKmhWhenUnitMissing() throws Exception {
    Optional<RailSpeed> speedOpt = parseSpeed("80");
    assertTrue(speedOpt.isPresent());
    assertEquals(80.0 / 3.6, speedOpt.get().blocksPerSecond(), 1e-6);
  }

  @Test
  void parsesSpeedKmhUnitVariants() throws Exception {
    Optional<RailSpeed> kmh = parseSpeed("80kmh");
    Optional<RailSpeed> kmhSlash = parseSpeed("80km/h");
    Optional<RailSpeed> kph = parseSpeed("80kph");

    assertTrue(kmh.isPresent());
    assertTrue(kmhSlash.isPresent());
    assertTrue(kph.isPresent());
    assertEquals(kmh.get().blocksPerSecond(), kmhSlash.get().blocksPerSecond(), 1e-9);
    assertEquals(kmh.get().blocksPerSecond(), kph.get().blocksPerSecond(), 1e-9);
  }

  @Test
  void parsesSpeedAcceptsWhitespaceAndUppercaseUnits() throws Exception {
    Optional<RailSpeed> kmh = parseSpeed(" 80 KMH ");
    Optional<RailSpeed> kmhSlash = parseSpeed(" 80 km/h ");
    Optional<RailSpeed> bps = parseSpeed(" 8 BPS ");
    Optional<RailSpeed> bpt = parseSpeed(" 0.4 BPT ");

    assertTrue(kmh.isPresent());
    assertTrue(kmhSlash.isPresent());
    assertTrue(bps.isPresent());
    assertTrue(bpt.isPresent());
    assertEquals(kmh.get().blocksPerSecond(), kmhSlash.get().blocksPerSecond(), 1e-9);
    assertEquals(8.0, bps.get().blocksPerSecond(), 1e-9);
    assertEquals(8.0, bpt.get().blocksPerSecond(), 1e-9);
  }

  @Test
  void parsesSpeedAllowsLeadingDot() throws Exception {
    Optional<RailSpeed> bpt = parseSpeed(".4bpt");
    assertTrue(bpt.isPresent());
    assertEquals(8.0, bpt.get().blocksPerSecond(), 1e-9);
  }

  @Test
  void parsesSpeedBpsAndBpt() throws Exception {
    Optional<RailSpeed> bps = parseSpeed("8bps");
    Optional<RailSpeed> bpt = parseSpeed("0.4bpt");

    assertTrue(bps.isPresent());
    assertTrue(bpt.isPresent());
    assertEquals(8.0, bps.get().blocksPerSecond(), 1e-9);
    assertEquals(8.0, bpt.get().blocksPerSecond(), 1e-9);
  }

  @Test
  void rejectsInvalidSpeed() throws Exception {
    assertTrue(parseSpeed(null).isEmpty());
    assertTrue(parseSpeed(" ").isEmpty());
    assertTrue(parseSpeed("0").isEmpty());
    assertTrue(parseSpeed("-1kmh").isEmpty());
    assertTrue(parseSpeed("abc").isEmpty());
    assertTrue(parseSpeed("1ms").isEmpty());
    assertTrue(parseSpeed("80kmhs").isEmpty());
  }

  @Test
  void parsesTtlSimpleAndComposite() throws Exception {
    assertEquals(Duration.ofSeconds(90), parseTtl("90s").orElseThrow());
    assertEquals(Duration.ofMinutes(1), parseTtl("1m").orElseThrow());
    assertEquals(Duration.ofSeconds(90), parseTtl("1m30s").orElseThrow());
    assertEquals(Duration.ofMinutes(90), parseTtl("1h30m").orElseThrow());
    assertEquals(Duration.ofDays(1), parseTtl("1d").orElseThrow());
  }

  @Test
  void parsesTtlIsCaseInsensitive() throws Exception {
    assertEquals(Duration.ofSeconds(90), parseTtl("90S").orElseThrow());
    assertEquals(Duration.ofMinutes(90), parseTtl("1H30M").orElseThrow());
  }

  @Test
  void rejectsInvalidTtl() throws Exception {
    assertTrue(parseTtl("").isEmpty());
    assertTrue(parseTtl(null).isEmpty());
    assertTrue(parseTtl("0s").isEmpty());
    assertTrue(parseTtl("1m 30s").isEmpty());
    assertTrue(parseTtl("1x").isEmpty());
    assertTrue(parseTtl("999999999999999999999999d").isEmpty());
  }

  @SuppressWarnings("unchecked")
  private static Optional<RailSpeed> parseSpeed(String raw) throws Exception {
    Method method = FtaGraphCommand.class.getDeclaredMethod("parseSpeedArg", String.class);
    assertTrue(method.trySetAccessible());
    return (Optional<RailSpeed>) method.invoke(null, raw);
  }

  @SuppressWarnings("unchecked")
  private static Optional<Duration> parseTtl(String raw) throws Exception {
    Method method = FtaGraphCommand.class.getDeclaredMethod("parseTtlArg", String.class);
    assertTrue(method.trySetAccessible());
    return (Optional<Duration>) method.invoke(null, raw);
  }
}
