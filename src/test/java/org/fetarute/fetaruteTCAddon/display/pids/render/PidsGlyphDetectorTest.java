package org.fetarute.fetaruteTCAddon.display.pids.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.Font;
import org.fetarute.fetaruteTCAddon.display.pids.PidsGlyphForm;
import org.junit.jupiter.api.Test;

/** 按内容选字形：假名为日文，简繁专用字多者胜，判断不出用默认；间隔号与长音不算假名。 */
class PidsGlyphDetectorTest {

  private final PidsGlyphDetector detector = PidsGlyphDetector.builtIn();

  @Test
  void kanaMeansJapanese() {
    assertEquals(PidsGlyphForm.JA, detector.detect("しんじゅく", PidsGlyphForm.ZH_HANS));
    assertEquals(PidsGlyphForm.JA, detector.detect("新宿方面ゆき", PidsGlyphForm.ZH_HANS));
    assertEquals(PidsGlyphForm.JA, detector.detect("ｼﾝｼﾞｭｸ", PidsGlyphForm.ZH_HANT), "半角片假名");
  }

  @Test
  void middleDotAndProlongedMarkAreNotKana() {
    assertEquals(PidsGlyphForm.ZH_HANS, detector.detect("新笛矢・壑湖", PidsGlyphForm.ZH_HANS));
    assertEquals(PidsGlyphForm.ZH_HANT, detector.detect("新笛矢・壑湖", PidsGlyphForm.ZH_HANT));
  }

  @Test
  void exclusiveCharactersDecideSimplifiedOrTraditional() {
    assertEquals(PidsGlyphForm.ZH_HANT, detector.detect("大港城東", PidsGlyphForm.ZH_HANS));
    assertEquals(PidsGlyphForm.ZH_HANS, detector.detect("大港城东", PidsGlyphForm.ZH_HANT));
    assertEquals(PidsGlyphForm.ZH_HANS, detector.detect("河滨道", PidsGlyphForm.ZH_HANT));
  }

  @Test
  void sharedCharactersKeepTheDefault() {
    assertEquals(PidsGlyphForm.ZH_HANT, detector.detect("大港城", PidsGlyphForm.ZH_HANT));
    assertEquals(PidsGlyphForm.ZH_HANS, detector.detect("站台", PidsGlyphForm.ZH_HANS));
    assertEquals(PidsGlyphForm.JA, detector.detect("大港城", PidsGlyphForm.JA));
    assertEquals(PidsGlyphForm.ZH_HANS, detector.detect("", PidsGlyphForm.ZH_HANS));
  }

  @Test
  void japaneseDefaultKeepsKanjiThatLooksTraditional() {
    assertEquals(PidsGlyphForm.JA, detector.detect("澤", PidsGlyphForm.JA));
    assertEquals(PidsGlyphForm.ZH_HANS, detector.detect("东京", PidsGlyphForm.JA), "简体专用字仍按简体");
  }

  @Test
  void fontsFollowTheDetectedForm() {
    PidsFonts detecting = PidsFonts.builtIn(PidsGlyphForm.ZH_HANS, true);
    PidsFonts fixed = PidsFonts.builtIn(PidsGlyphForm.ZH_HANS);

    Font traditional = detecting.fontFor("車站", 12);
    assertNotEquals(fixed.fontFor("車站", 12).getFontName(), traditional.getFontName());
    assertEquals(fixed.fontFor("站台", 12).getFontName(), detecting.fontFor("站台", 12).getFontName());
    assertEquals(
        fixed.font(PidsFonts.Script.LATIN, 12).getFontName(),
        detecting.fontFor("Platform 1", 12).getFontName());
    assertEquals(
        fixed.font(PidsFonts.Script.LATIN, 12).getFontName(),
        detecting.fontFor("King’s Cross…", 12).getFontName(),
        "英文省略后、带弯引号仍用拉丁版，量宽与绘制同一字体");
    assertNotEquals(
        fixed.font(PidsFonts.Script.LATIN, 12).getFontName(),
        detecting.fontFor("—", 12).getFontName(),
        "破折号按中文字体全宽显示");
    assertThrows(IllegalArgumentException.class, () -> detecting.fontFor("車站", 16));
  }
}
