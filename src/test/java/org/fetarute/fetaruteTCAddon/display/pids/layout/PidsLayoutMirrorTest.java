package org.fetarute.fetaruteTCAddon.display.pids.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fetarute.fetaruteTCAddon.display.pids.fixtures.PidsFixtures;
import org.junit.jupiter.api.Test;

/** 站台号在右的内置布局由同尺寸站台屏左右对调得出：站台号与时钟贴右缘，到发表移到左边、列的先后与宽度不变。 */
class PidsLayoutMirrorTest {

  @Test
  void theRightVariantMirrorsTheLeftOne() {
    PidsLayout left = PidsFixtures.builtInLayout("platform-1x3");
    PidsLayout right = PidsLayoutRegistry.mirror("platform-1x3-right", left);

    assertEquals("platform-1x3-right", right.id());
    assertEquals(left.name() + "（站台号在右）", right.name());
    PidsLayout.Platform platform = (PidsLayout.Platform) right.widgets().get(0);
    assertEquals(376, platform.x(), "右缘");
    assertEquals(PidsLayout.Align.RIGHT, platform.align());
    PidsLayout.Clock clock = (PidsLayout.Clock) right.widgets().get(1);
    assertEquals(376, clock.x());
    assertEquals(PidsLayout.Align.RIGHT, clock.align());
    PidsLayout.Departures departures = right.departures().orElseThrow();
    PidsLayout.Departures original = left.departures().orElseThrow();
    assertEquals(8, departures.x(), "到发表从左缘 8 起");
    assertEquals(original.width(), departures.width());
    assertEquals(original.columns(), departures.columns(), "列的先后与宽度不变");
    assertEquals(original.rows(), departures.rows());
    assertEquals(left.widgets().get(2), right.widgets().get(2), "色带铺满整宽，对调后不变");
  }

  @Test
  void mirroringTwiceGivesTheOriginalWidgets() {
    PidsLayout left = PidsFixtures.builtInLayout("platform-1x4");

    PidsLayout back = left.mirrored("a", "a").mirrored(left.id(), left.name());

    assertEquals(left, back);
  }

  @Test
  void layoutsWithOtherWidgetsCannotBeMirrored() {
    PidsLayout station = PidsFixtures.builtInLayout("station-3x5");

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> assertNull(station.mirrored("x", "x")));
    assertTrue(error.getMessage().contains("station-3x5"), error::getMessage);
  }
}
