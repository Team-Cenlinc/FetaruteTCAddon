package org.fetarute.fetaruteTCAddon.display.pids.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Font;
import org.fetarute.fetaruteTCAddon.display.pids.PidsGlyphForm;
import org.fetarute.fetaruteTCAddon.display.pids.render.PidsFonts.Script;
import org.junit.jupiter.api.Test;

/** 内置像素字体：只接受能像素对齐的字号，按整数倍放大后宽度与基线同样按倍数放大。 */
class PidsFontsTest {

  private final PidsFonts fonts = PidsFonts.builtIn(PidsGlyphForm.ZH_HANS);

  @Test
  void onlyMultiplesOfTheNativeSizesAreSupported() {
    for (int size : new int[] {10, 12, 20, 24, 30, 36}) {
      assertTrue(PidsFonts.supports(size), "支持 " + size);
    }
    for (int size : new int[] {0, -12, 11, 16, 18}) {
      assertFalse(PidsFonts.supports(size), "不支持 " + size);
    }
    assertThrows(IllegalArgumentException.class, () -> fonts.font(Script.CJK, 16));
  }

  @Test
  // 文本框顶边到基线：内容区居中于字号高的文本框，与设计稿 CSS line-height: 1 一致。
  void baselineScalesWithTheNativeGrid() {
    assertEquals(11, fonts.baseline(fonts.font(Script.CJK, 12)));
    assertEquals(22, fonts.baseline(fonts.font(Script.CJK, 24)));
    assertEquals(33, fonts.baseline(fonts.font(Script.CJK, 36)));
    assertEquals(9, fonts.baseline(fonts.font(Script.LATIN, 10)));
    assertEquals(18, fonts.baseline(fonts.font(Script.LATIN, 20)));
  }

  @Test
  void widthScalesByIntegerMultiples() {
    String text = "新笛矢·壑湖 12";
    int native12 = fonts.width(fonts.font(Script.CJK, 12), text);
    assertEquals(native12 * 2, fonts.width(fonts.font(Script.CJK, 24), text));
    assertEquals(native12 * 3, fonts.width(fonts.font(Script.CJK, 36), text));
    int native10 = fonts.width(fonts.font(Script.LATIN, 10), "Nam Toa");
    assertEquals(native10 * 2, fonts.width(fonts.font(Script.LATIN, 20), "Nam Toa"));
  }

  @Test
  void cjkGlyphFormSelectsItsOwnFile() {
    Font hans = fonts.font(Script.CJK, 12);
    Font hant = PidsFonts.builtIn(PidsGlyphForm.ZH_HANT).font(Script.CJK, 12);
    Font latin = fonts.font(Script.LATIN, 12);

    assertNotEquals(hans.getFontName(), hant.getFontName());
    assertNotEquals(hans.getFontName(), latin.getFontName());
    assertEquals(-1, hans.canDisplayUpTo("新笛矢·壑湖 本站终到"));
  }

  @Test
  void ellipsizeKeepsWhatFits() {
    Font font = fonts.font(Script.CJK, 12);
    String text = "十个字以上的站名全称会被省略";
    int width = fonts.width(font, "十个字以上") + fonts.width(font, "…");

    assertEquals(text, fonts.ellipsize(font, text, fonts.width(font, text)));
    assertEquals("十个字以上…", fonts.ellipsize(font, text, width));
  }

  @Test
  void englishIsCutAtWordBoundaries() {
    Font font = fonts.font(Script.LATIN, 10);
    String text = "A very long station name";
    int width = fonts.width(font, "A very long…");

    assertEquals("A very long…", fonts.ellipsizeWords(font, text, width));
    assertEquals("A very long…", fonts.ellipsizeWords(font, text, width + 3), "不截在词中间");
  }
}
