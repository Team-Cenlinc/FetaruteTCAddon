package org.fetarute.fetaruteTCAddon.dispatcher.route;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 所有调用入口共用解析器；旧 Station 格式、单值、逆序区间、末尾冒号、空白与全角数字保留兼容语义。 */
class DynamicSpecCompatibilityTest {

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "OP:S:STATION:[1:3]| S| STATION| 1| 3",
        "OP:D:DEPOT:[2:4]| D| DEPOT| 2| 4",
        "OP:S:STATION:2| S| STATION| 2| 2",
        "OP:STATION:[1:3]| S| STATION| 1| 3",
        "OP:STATION:2| S| STATION| 2| 2",
        "DYNAMIC:OP:S:STATION:[1:3]| S| STATION| 1| 3",
        "OP:S:STATION:1:3| S| STATION| 1| 3",
        "OP:S:STATION:[5:2]| S| STATION| 2| 5",
        "OP:S:STATION:[1 : 3]| S| STATION| 1| 3",
        "OP:S:STATION: [1:3]| S| STATION| 1| 3",
        "OP:S:STATION:[ 1:3 ]| S| STATION| 1| 3",
        "OP:S:STATION:1 : 3| S| STATION| 1| 3",
        "OP:S:STATION:[１:３]| S| STATION| 1| 3",
        "OP:S:STATION:２| S| STATION| 2| 2",
        "DYNAMIC: OP:S:STATION:[1 - 3]| S| STATION| 1| 3"
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

  /** 集合内逗号两侧的空白不影响成员。 */
  @Test
  void whitespaceAroundCommasIsTolerated() {
    var spec = DynamicStopMatcher.parseDynamicSpec("OP:S:STATION:[1, 3 ,５]").orElseThrow();
    assertTrue(spec.tracks().contains(1));
    assertFalse(spec.tracks().contains(2));
    assertTrue(spec.tracks().contains(3));
    assertTrue(spec.tracks().contains(5));
  }

  /** 旧版本把末尾冒号当作没写选择器：不限该站现有股道。 */
  @ParameterizedTest
  @ValueSource(strings = {"OP:S:STATION:", "OP:D:DEPOT:", "OP:STATION:", "OP:S:STATION:  "})
  void aTrailingColonMeansEveryTrack(String raw) {
    assertTrue(DynamicStopMatcher.parseDynamicSpec(raw).orElseThrow().unbounded());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        " ",
        "INVALID",
        "OP:S:STATION:[]",
        "OP:S:STATION:[ ]",
        "OP:S:STATION:[1,,3]",
        "OP:S:STATION:[1,]",
        "OP:S:STATION:[1-]",
        "OP:S:STATION:[0,1]",
        "OP:S:STATION:[2147483648]",
        "OP:S:STATION:+3",
        "OP:S:STATION:[+3]",
        "OP:S:STATION:[1 3]",
        "OP:S:STATION:1 3"
      })
  void invalidSelectorNeverBecomesUnbounded(String raw) {
    assertTrue(DynamicStopMatcher.parseDynamicSpec(raw).isEmpty());
  }

  /** 备注里的写法：选择器内的空白不截断，选择器之后同一行的其它内容不并进来；运行时与只读入口取到同一段。 */
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "DYNAMIC:OP:S:STATION:[1, 3]| [1,3]",
        "DYNAMIC:OP:S:STATION: [1:3] CHANGE:OP:L2| [1-3]",
        "STOP DYNAMIC:OP:S:STATION:[1 : 3] 起步线路=OP:L1| [1-3]",
        "CRET OP:D:DEPOT:1 DYNAMIC:OP:S:STATION:[1:3] CHANGE:OP:L2| [1-3]",
        "DYNAMIC:OP:S:STATION:2 CHANGE:OP:L2| 2",
        "DYNAMIC:OP:S:STATION: CHANGE:OP:L2| ''",
        "DYNAMIC OP:S:STATION:[１,２]| [1-2]"
      })
  void notesKeepTheWholeSelector(String notes, String selector) {
    RouteStop stop = stop(notes);
    var spec = DynamicStopMatcher.parseDynamicSpec(stop).orElseThrow();
    assertEquals(selector, spec.tracks().formatSelector().orElse(""));
    assertEquals(
        Optional.of(spec),
        RouteStopDirectives.target(stop, "DYNAMIC").flatMap(DynamicStopMatcher::parseDynamicSpec));
  }

  /** 等价写法的空白与全角差异不算定义变化，已选站台不因此作废。 */
  @Test
  void whitespaceVariantsAreTheSameDefinition() {
    assertTrue(
        DynamicStopMatcher.sameDefinition(
            Optional.of(stop("DYNAMIC:OP:S:STATION:[1, 3]\nCHANGE:OP:L1")),
            Optional.of(stop("DYNAMIC:OP:S:STATION:[1,３]\nCHANGE:OP:L1"))));
  }

  private static RouteStop stop(String notes) {
    return new RouteStop(
        UUID.nameUUIDFromBytes("route".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        1,
        Optional.empty(),
        Optional.empty(),
        Optional.of(20),
        RouteStopPassType.STOP,
        Optional.of(notes));
  }
}
