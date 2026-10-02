package org.fetarute.fetaruteTCAddon.company.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 经由站配置：命令写法与库里存的列表都能读，去空白、去重（不区分大小写）、保持顺序。 */
class RouteViaMetadataTest {

  @Test
  void parsesCommaChineseCommaEnumerationCommaAndSpaces() {
    assertEquals(List.of("HHU", "SPB", "KPO", "ofl"), RouteViaMetadata.parse(" HHU,SPB，KPO、ofl "));
    assertEquals(List.of("HHU", "SPB"), RouteViaMetadata.parse("HHU  SPB"));
    assertEquals(List.of(), RouteViaMetadata.parse(" , "));
  }

  @Test
  void duplicatesAreDroppedIgnoringCase() {
    assertEquals(List.of("HHU", "SPB"), RouteViaMetadata.parse("HHU,hhu,SPB"));
  }

  @Test
  void readsAListOrAString() {
    assertEquals(
        List.of("HHU", "SPB"),
        RouteViaMetadata.read(Map.of(RouteViaMetadata.KEY, List.of("HHU", " SPB ", ""))));
    assertEquals(List.of("HHU"), RouteViaMetadata.read(Map.of(RouteViaMetadata.KEY, "HHU")));
    assertEquals(List.of(), RouteViaMetadata.read(Map.of(RouteViaMetadata.KEY, 3)));
    assertEquals(List.of(), RouteViaMetadata.read(Map.of()));
    assertEquals(List.of(), RouteViaMetadata.read(null));
  }
}
