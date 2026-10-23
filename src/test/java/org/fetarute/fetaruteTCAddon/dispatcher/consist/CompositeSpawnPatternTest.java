package org.fetarute.fetaruteTCAddon.dispatcher.consist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 组合编组写法按 + 分段。 */
class CompositeSpawnPatternTest {

  @Test
  void plusSplitsThePatternHeadToTail() {
    assertEquals(List.of("EMU4", "EMU2"), CompositeSpawnPattern.parts(" EMU4 + EMU2 "));
    assertTrue(CompositeSpawnPattern.isComposite("EMU4+EMU4"));
  }

  @Test
  void aPlainPatternIsOnePart() {
    assertEquals(List.of("metro 8"), CompositeSpawnPattern.parts("metro 8"));
    assertFalse(CompositeSpawnPattern.isComposite("EMU8"));
  }

  @Test
  void emptyPartsAreDropped() {
    assertEquals(List.of("EMU4"), CompositeSpawnPattern.parts("EMU4+ +"));
    assertFalse(CompositeSpawnPattern.isComposite("EMU4+"));
    assertTrue(CompositeSpawnPattern.parts("  ").isEmpty());
    assertTrue(CompositeSpawnPattern.parts(null).isEmpty());
  }
}
