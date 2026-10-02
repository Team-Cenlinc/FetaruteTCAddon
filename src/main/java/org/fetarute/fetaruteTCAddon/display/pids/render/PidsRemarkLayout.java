package org.fetarute.fetaruteTCAddon.display.pids.render;

import java.awt.Font;
import java.util.List;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.RemarkPart;

/**
 * 备注在英文那一格里的排版：放不下时删减到放得下为止，再算出宽度。
 *
 * <p>每段是一个标签色块加其后的文字，段与段并排。删减顺序见 {@link #fit}：保留的段数从多到少，每种段数下依次试完整写法、经由只留第一站、
 * 再把直通线路名换成线路代码。只剩一段还放不下时截断它的文字；连标签都放不下就整条不写，照常显示英文。
 *
 * <p>色块比标签字左右各宽 {@value #TAG_PAD} 像素：第一段的色块伸进左侧留白，标签字与上面的中文名左对齐；宽度只量到文字为止。
 */
final class PidsRemarkLayout {

  /** 色块比标签字左右各宽的像素。 */
  static final int TAG_PAD = 2;

  /** 色块与其后文字的间距。 */
  static final int TAG_GAP = 4;

  /** 两段之间的间距（含后一段色块伸出的 {@link #TAG_PAD}）。 */
  static final int PART_GAP = 6;

  /** 同一段里几条文字（经由站）之间的分隔。 */
  static final String SEPARATOR = "、";

  private PidsRemarkLayout() {}

  /**
   * 排好的一段。
   *
   * @param tag 标签字
   * @param color 色块颜色
   * @param text 标签后的文字；可为空串
   */
  record Placed(String tag, int color, String text) {}

  /**
   * 按宽度删减备注。
   *
   * @param fonts 字体
   * @param remark 备注
   * @param size 字号（英文那一格的字号）
   * @param maxWidth 可用宽度
   * @return 放得下的各段；一段也放不下时为空
   */
  static Optional<List<Placed>> fit(
      PidsFonts fonts, PidsView.Remark remark, int size, int maxWidth) {
    List<RemarkPart> parts = remark.parts();
    for (int kept = parts.size(); kept >= 1; kept--) {
      List<RemarkPart> head = parts.subList(0, kept);
      for (Shrink shrink : Shrink.values()) {
        List<Placed> placed = head.stream().map(part -> place(part, shrink)).toList();
        if (width(fonts, placed, size) <= maxWidth) {
          return Optional.of(placed);
        }
      }
    }
    return truncated(fonts, parts.get(0), size, maxWidth).map(List::of);
  }

  /** 排好的各段总宽：从第一个标签字的左边算到最后一段文字（或色块）的右边。 */
  static int width(PidsFonts fonts, List<Placed> parts, int size) {
    int width = 0;
    for (int i = 0; i < parts.size(); i++) {
      if (i > 0) {
        width += PART_GAP;
      }
      width += partWidth(fonts, parts.get(i), size);
    }
    return width;
  }

  /** 一段的宽度：标签字、色块右侧伸出的部分、文字。 */
  static int partWidth(PidsFonts fonts, Placed part, int size) {
    int width = textWidth(fonts, part.tag(), size) + TAG_PAD;
    if (!part.text().isEmpty()) {
      width += TAG_GAP + textWidth(fonts, part.text(), size);
    }
    return width;
  }

  private static int textWidth(PidsFonts fonts, String text, int size) {
    return fonts.width(fonts.fontFor(text, size), text);
  }

  /** 只剩第一段还放不下：截断文字；截到一个字都不剩、或连标签都放不下时为空。 */
  private static Optional<Placed> truncated(
      PidsFonts fonts, RemarkPart part, int size, int maxWidth) {
    Placed shortest = place(part, Shrink.COMPACT);
    if (shortest.text().isEmpty()) {
      return Optional.empty();
    }
    int room = maxWidth - textWidth(fonts, shortest.tag(), size) - TAG_PAD - TAG_GAP;
    if (room <= 0) {
      return Optional.empty();
    }
    Font font = fonts.fontFor(shortest.text(), size);
    String text = fonts.ellipsize(font, shortest.text(), room);
    return text.isEmpty()
        ? Optional.empty()
        : Optional.of(new Placed(shortest.tag(), shortest.color(), text));
  }

  private static Placed place(RemarkPart part, Shrink shrink) {
    List<String> items =
        shrink != Shrink.NONE && part.items().size() > 1
            ? part.items().subList(0, 1)
            : part.items();
    String text = String.join(SEPARATOR, items);
    if (shrink == Shrink.COMPACT && items.size() == 1 && part.compact().isPresent()) {
      text = part.compact().get();
    }
    return new Placed(part.tag(), part.color(), text);
  }

  /** 每种段数下依次试的写法。 */
  private enum Shrink {
    /** 完整。 */
    NONE,
    /** 经由只留第一站。 */
    FIRST_ITEM,
    /** 再把直通线路名换成线路代码。 */
    COMPACT
  }
}
