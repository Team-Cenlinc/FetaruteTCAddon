package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 票上已选出库点的校验：派票器与出车器共用同一口径。 */
class SpawnDirectiveParserTest {

  private static final String SOURCE = "DYNAMIC:OP:D:DEP:[1:3]";

  @Test
  void aSelectedTrackInsideTheAllowedSetIsUsed() {
    assertEquals(
        Optional.of("OP:D:DEP:2"),
        SpawnDirectiveParser.effectiveDepotSpec(SOURCE, Optional.of(" OP:D:DEP:2 ")));
  }

  @Test
  void aSelectedTrackOutsideTheAllowedSetIsRejected() {
    assertEquals(
        Optional.empty(),
        SpawnDirectiveParser.effectiveDepotSpec(SOURCE, Optional.of("OP:D:DEP:4")));
  }

  @Test
  void aBlankSelectionFallsBackToTheRouteDepot() {
    assertEquals(
        Optional.of(SOURCE), SpawnDirectiveParser.effectiveDepotSpec(SOURCE, Optional.of("  ")));
    assertEquals(Optional.of(SOURCE), SpawnDirectiveParser.effectiveDepotSpec(SOURCE, null));
  }

  /** 线路车库池里的 DYNAMIC 车库在派票时没选出具体股道：原样交给出车时再选。 */
  @Test
  void anUnresolvedDynamicSelectionIsPassedThrough() {
    assertEquals(
        Optional.of("DYNAMIC:OP:D:POOL"),
        SpawnDirectiveParser.effectiveDepotSpec(SOURCE, Optional.of("DYNAMIC:OP:D:POOL")));
  }

  @Test
  void anInvalidRouteDepotRejectsEverySelection() {
    assertEquals(
        Optional.empty(),
        SpawnDirectiveParser.effectiveDepotSpec("DYNAMIC:OP:S:STA:[1:3]", Optional.empty()));
    assertEquals(
        Optional.empty(),
        SpawnDirectiveParser.effectiveDepotSpec("DYNAMIC:OP:D:DEP:[]", Optional.of("OP:D:DEP:1")));
  }

  @Test
  void aFixedRouteDepotAcceptsTheLineDepotPool() {
    assertEquals(
        Optional.of("OP:D:OTHER:1"),
        SpawnDirectiveParser.effectiveDepotSpec("OP:D:DEP:1", Optional.of("OP:D:OTHER:1")));
    assertEquals(Optional.empty(), SpawnDirectiveParser.effectiveDepotSpec(" ", Optional.empty()));
  }

  /** DYNAMIC 车库写法只认带前缀、能解析、类型为 D 的；派票、出车与车库排队共用。 */
  @Test
  void dynamicDepotRequiresAValidDepotSpec() {
    assertEquals(true, SpawnDirectiveParser.dynamicDepot("DYNAMIC:OP:D:DEP:[1:3]").isPresent());
    assertEquals(true, SpawnDirectiveParser.dynamicDepot("dynamic:op:d:dep").isPresent());
    assertEquals(false, SpawnDirectiveParser.dynamicDepot("DYNAMIC:OP:S:STA").isPresent());
    assertEquals(false, SpawnDirectiveParser.dynamicDepot("DYNAMIC:OP:D:DEP:[]").isPresent());
    assertEquals(false, SpawnDirectiveParser.dynamicDepot("OP:D:DEP:1").isPresent());
  }
}
