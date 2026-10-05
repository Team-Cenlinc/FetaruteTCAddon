package org.fetarute.fetaruteTCAddon.display.pids.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.display.pids.PidsGlyphForm;
import org.fetarute.fetaruteTCAddon.display.pids.bulletin.PidsBulletin;
import org.fetarute.fetaruteTCAddon.display.pids.bulletin.PidsBulletinFixtures;
import org.fetarute.fetaruteTCAddon.display.pids.fixtures.PidsFixtures;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsBulletinView;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Names;
import org.junit.jupiter.api.Test;

/** 公告页排版：切词、行首标点、中英同页与分页、截断，以及按排版结果渲染。 */
class PidsBulletinTypesetterTest {

  private static final Names LABEL = new Names("公告", "Notice");
  private static final int BAND = 0xD920D9;

  /** 等宽量法：中文一字一个字号宽，拉丁字母半个字号宽。 */
  private static final PidsBulletinTypesetter MONO =
      new PidsBulletinTypesetter(
          (text, size, primary) ->
              text.codePoints().map(cp -> cp < 0x2000 ? size / 2 : size).sum());

  private final PidsRenderer renderer = new PidsRenderer(PidsFonts.builtIn(PidsGlyphForm.ZH_HANS));

  @Test
  void latinWordsAndClockTimesStayTogether() {
    assertEquals(
        List.of("在", " ", "22:00", " ", "改", "乘", " ", "WS", " ", "线", "，", "Exit", " ", "2."),
        PidsBulletinTypesetter.tokens("在 22:00 改乘 WS 线，Exit 2."));
  }

  /** 行首不放标点：放不下的逗号带着上一个字一起换行。 */
  @Test
  void punctuationNeverStartsALine() {
    assertEquals(List.of("一二三四", "五，六"), MONO.wrap("一二三四五，六", 12, 60, true));
    assertEquals(List.of("一二三四五", "六七"), MONO.wrap("一二三四五六七", 12, 60, true));
    assertEquals(List.of("一二三四", "五。"), MONO.wrap("一二三四五 。", 12, 60, true), "行尾空格换不了行时标点照样带字");
    assertEquals(
        List.of("Abcdefghij,", "klm"), MONO.wrap("Abcdefghij, klm", 10, 50, false), "一行只有一个词时标点悬挂");
    assertEquals(
        List.of("ab cd", "i"), MONO.wrap("ab cd i", 10, 26, false), "放不下的空格之后另起一行，不把下一个词粘上");
  }

  @Test
  void hardLineBreaksAndOverlongWordsWrap() {
    assertEquals(List.of("第一行", "第二行"), MONO.wrap("第一行\n第二行", 12, 60, true));
    assertEquals(
        List.of("Abcdefghij", "klmno"), MONO.wrap("Abcdefghijklmno", 10, 50, false), "过长的词逐字断开");
  }

  /** 设计稿的两条公告在 1×3、1×4 上一页排完，2×1 先中文页、后英文页。 */
  @Test
  void designSamplesFitEveryPlatformLayout() {
    for (PidsBulletin bulletin :
        List.of(PidsBulletinFixtures.exitClosed(), PidsBulletinFixtures.lineSuspended())) {
      for (String id : List.of("platform-1x3", "platform-1x4", "platform-group-1x3")) {
        PidsBulletinTypesetter.Result result = typeset(id, bulletin);
        assertTrue(result.fits(), id);
        assertEquals(1, result.pages().size(), id);
        assertTrue(PidsBulletinTypesetter.landscape(PidsFixtures.builtInLayout(id)), id);
      }
      PidsBulletinTypesetter.Result portrait = typeset("platform-2x1", bulletin);
      assertTrue(portrait.fits());
      assertFalse(PidsBulletinTypesetter.landscape(PidsFixtures.builtInLayout("platform-2x1")));
      assertEquals(2, portrait.pages().size());
      PidsBulletinTypesetter.Page english = portrait.pages().get(1);
      assertEquals(12, english.blocks().get(0).size(), "英文页标题用 12 号");
    }
  }

  @Test
  void overlongTextIsTruncatedAndFlagged() {
    PidsBulletin wordy =
        PidsBulletinFixtures.bulletin(
            Set.of(),
            Set.of(),
            PidsBulletin.Level.NORMAL,
            new PidsBulletin.Text("这是一条标题非常非常非常长的公告", ""),
            new PidsBulletin.Text("正文很长。".repeat(40), ""),
            Optional.empty(),
            Optional.empty());

    PidsBulletinTypesetter.Result result = typeset("platform-1x3", wordy);

    assertTrue(result.titleTruncated());
    assertTrue(result.bodyTruncated());
    List<String> lines = result.pages().get(0).blocks().get(0).lines();
    assertTrue(lines.get(lines.size() - 1).endsWith("…"));
  }

  /** 渲染：画面与布局同尺寸；图标块一般为钢蓝、重要为警示色；色带与主页同色。 */
  @Test
  void rendersTheChipAndBand() {
    PidsLayout layout = PidsFixtures.builtInLayout("platform-1x3");
    PidsBulletin normal = PidsBulletinFixtures.exitClosed();
    PidsBulletin important = PidsBulletinFixtures.lineSuspended();

    BufferedImage plain = renderer.renderBulletin(layout, view(layout, normal, PidsTheme.DARK));
    BufferedImage urgent = renderer.renderBulletin(layout, view(layout, important, PidsTheme.DARK));

    assertEquals(384, plain.getWidth());
    assertEquals(128, plain.getHeight());
    assertEquals(PidsTheme.DARK.info(), plain.getRGB(9, 5) & 0xFFFFFF);
    assertEquals(PidsTheme.DARK.amber(), urgent.getRGB(9, 5) & 0xFFFFFF);
    assertEquals(BAND, plain.getRGB(0, 124) & 0xFFFFFF);
  }

  private PidsBulletinTypesetter.Result typeset(String layoutId, PidsBulletin bulletin) {
    return renderer.typesetBulletin(
        PidsFixtures.builtInLayout(layoutId),
        bulletin.important() ? new Names("重要公告", "Important") : LABEL,
        new Names(bulletin.title().primary(), bulletin.title().secondary()),
        new Names(bulletin.body().primary(), bulletin.body().secondary()));
  }

  private PidsBulletinView view(PidsLayout layout, PidsBulletin bulletin, PidsTheme theme) {
    PidsBulletinTypesetter.Result result = typeset(layout.id(), bulletin);
    return new PidsBulletinView(
        theme,
        bulletin.important(),
        LABEL,
        new Names(bulletin.title().primary(), bulletin.title().secondary()),
        result.pages().get(0),
        0,
        result.pages().size(),
        List.of(BAND));
  }
}
