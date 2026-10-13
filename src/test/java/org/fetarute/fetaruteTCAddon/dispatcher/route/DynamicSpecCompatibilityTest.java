package org.fetarute.fetaruteTCAddon.dispatcher.route;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 所有调用入口共用解析器；旧 Station 格式、单值和逆序区间保留兼容语义。 */
class DynamicSpecCompatibilityTest {

  @ParameterizedTest
  @CsvSource({
    "OP:S:STATION:[1:3], S, STATION, 1, 3",
    "OP:D:DEPOT:[2:4], D, DEPOT, 2, 4",
    "OP:S:STATION:2, S, STATION, 2, 2",
    "OP:STATION:[1:3], S, STATION, 1, 3",
    "OP:STATION:2, S, STATION, 2, 2",
    "DYNAMIC:OP:S:STATION:[1:3], S, STATION, 1, 3",
    "OP:S:STATION:1:3, S, STATION, 1, 3",
    "OP:S:STATION:[5:2], S, STATION, 2, 5"
  })
  void legacyFormatsKeepTheirMembership(String raw, String type, String name, int first, int last) {
    var spec = DynamicStopMatcher.parseDynamicSpec(raw).orElseThrow();
    assertEquals("OP", spec.operatorCode());
    assertEquals(type, spec.nodeType());
    assertEquals(name, spec.nodeName());
    assertTrue(spec.tracks().contains(first));
    assertTrue(spec.tracks().contains(last));
    assertFalse(spec.tracks().contains(first - 1));
    assertFalse(spec.tracks().contains(last + 1));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        " ",
        "INVALID",
        "OP:S:STATION:[]",
        "OP:S:STATION:[1,,3]",
        "OP:S:STATION:[1,]",
        "OP:S:STATION:[1-]",
        "OP:S:STATION:[0,1]",
        "OP:S:STATION:[2147483648]",
        "OP:S:STATION:",
        "OP:D:DEPOT:",
        "OP:S:STATION:[1, 3]"
      })
  void invalidSelectorNeverBecomesUnbounded(String raw) {
    assertTrue(DynamicStopMatcher.parseDynamicSpec(raw).isEmpty());
  }
}
