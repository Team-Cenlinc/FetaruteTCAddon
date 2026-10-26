package org.fetarute.fetaruteTCAddon.display.pids.render;

import java.util.Objects;

/**
 * 量文字：画的时候与算点击区的时候用同一把尺子，车次窗、标题栏的牌子画在哪里就在哪里点得到。
 *
 * <p>主文字从 {@code boldFrom} 字号起仿粗体，多占 1 像素（见 {@link PidsRenderer} 的画笔）。
 */
final class PidsTextMeasure {

  private final PidsFonts fonts;
  private final int boldFrom;

  /**
   * @param fonts 字体
   * @param boldFrom 主文字从这个字号起仿粗体；0 表示不加粗
   */
  PidsTextMeasure(PidsFonts fonts, int boldFrom) {
    this.fonts = Objects.requireNonNull(fonts, "fonts");
    this.boldFrom = boldFrom;
  }

  /** 主文字在这个字号是否仿粗体。 */
  boolean bold(int size) {
    return boldFrom > 0 && size >= boldFrom;
  }

  private int extra(int size) {
    return bold(size) ? 1 : 0;
  }

  /** 主文字宽度（含仿粗体多出的 1 像素）。 */
  int width(String text, int size) {
    return text == null || text.isEmpty()
        ? 0
        : fonts.width(fonts.fontFor(text, size), text) + extra(size);
  }

  /** 次要文字宽度。 */
  int regularWidth(String text, int size) {
    return fonts.width(fonts.fontFor(text, size), text);
  }

  /** 主文字超宽时省略。 */
  String ellipsize(String text, int size, int maxWidth) {
    return fonts.ellipsize(fonts.fontFor(text, size), text, maxWidth - extra(size));
  }
}
