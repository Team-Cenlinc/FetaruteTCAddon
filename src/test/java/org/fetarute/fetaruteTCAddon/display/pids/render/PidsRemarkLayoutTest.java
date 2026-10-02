package org.fetarute.fetaruteTCAddon.display.pids.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.display.pids.PidsGlyphForm;
import org.fetarute.fetaruteTCAddon.display.pids.render.PidsRemarkLayout.Placed;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Remark;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.RemarkPart;
import org.junit.jupiter.api.Test;

/** 备注排版：放不下时依次把经由减到一站、直通改写线路代码、去掉最后一段；只剩一段还放不下时截断。 */
class PidsRemarkLayoutTest {

  private static final int SIZE = 10;
  private static final PidsFonts FONTS = PidsFonts.builtIn(PidsGlyphForm.ZH_HANS);
  private static final RemarkPart LAST =
      new RemarkPart("末班车", 0xFF0000, List.of(), Optional.empty());
  private static final RemarkPart THROUGH =
      new RemarkPart("直通", 0x70DEEE, List.of("浦蓝线"), Optional.of("WS"));
  private static final RemarkPart VIA =
      new RemarkPart("经由", 0xFAEE4D, List.of("新笛矢·壑湖", "主城湾"), Optional.empty());
  private static final Remark REMARK = new Remark(List.of(LAST, THROUGH, VIA));

  @Test
  void everythingIsWrittenWhenItFits() {
    List<Placed> full =
        List.of(
            new Placed("末班车", 0xFF0000, ""),
            new Placed("直通", 0x70DEEE, "浦蓝线"),
            new Placed("经由", 0xFAEE4D, "新笛矢·壑湖、主城湾"));

    assertEquals(Optional.of(full), fit(width(full)));
  }

  @Test
  void shrinkingKeepsOneViaThenUsesTheLineCodeThenDropsTheLastPart() {
    List<Placed> full =
        List.of(
            new Placed("末班车", 0xFF0000, ""),
            new Placed("直通", 0x70DEEE, "浦蓝线"),
            new Placed("经由", 0xFAEE4D, "新笛矢·壑湖、主城湾"));
    List<Placed> oneVia =
        List.of(
            new Placed("末班车", 0xFF0000, ""),
            new Placed("直通", 0x70DEEE, "浦蓝线"),
            new Placed("经由", 0xFAEE4D, "新笛矢·壑湖"));
    List<Placed> code =
        List.of(
            new Placed("末班车", 0xFF0000, ""),
            new Placed("直通", 0x70DEEE, "WS"),
            new Placed("经由", 0xFAEE4D, "新笛矢·壑湖"));
    List<Placed> noVia =
        List.of(new Placed("末班车", 0xFF0000, ""), new Placed("直通", 0x70DEEE, "浦蓝线"));

    assertEquals(Optional.of(oneVia), fit(width(full) - 1));
    assertEquals(Optional.of(code), fit(width(oneVia) - 1));
    assertEquals(Optional.of(noVia), fit(width(code) - 1), "去掉经由后直通又写得下全名");
    assertEquals(
        Optional.of(List.of(new Placed("末班车", 0xFF0000, ""))),
        fit(width(List.of(new Placed("末班车", 0xFF0000, ""), new Placed("直通", 0x70DEEE, "WS"))) - 1));
  }

  @Test
  void aSinglePartThatStillDoesNotFitIsTruncated() {
    Remark via =
        new Remark(List.of(new RemarkPart("经由", 0xFAEE4D, List.of("新笛矢·壑湖"), Optional.empty())));
    int tag = PidsRemarkLayout.width(FONTS, List.of(new Placed("经由", 0xFAEE4D, "")), SIZE);

    Optional<List<Placed>> fitted = PidsRemarkLayout.fit(FONTS, via, SIZE, tag + 30);

    assertTrue(fitted.isPresent());
    assertTrue(fitted.get().get(0).text().endsWith("…"), fitted.toString());
    assertTrue(PidsRemarkLayout.width(FONTS, fitted.get(), SIZE) <= tag + 30);
  }

  @Test
  void nothingIsWrittenWhenNotEvenTheTagFits() {
    assertEquals(Optional.empty(), fit(10));
  }

  private static Optional<List<Placed>> fit(int width) {
    return PidsRemarkLayout.fit(FONTS, REMARK, SIZE, width);
  }

  private static int width(List<Placed> parts) {
    return PidsRemarkLayout.width(FONTS, parts, SIZE);
  }
}
