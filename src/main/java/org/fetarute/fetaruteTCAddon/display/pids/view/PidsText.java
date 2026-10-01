package org.fetarute.fetaruteTCAddon.display.pids.view;

import java.util.regex.Pattern;

/** 站台屏的文字规整。 */
public final class PidsText {

  /** 间隔号（·、・）两侧的空白。 */
  private static final Pattern SPACED_SEPARATOR = Pattern.compile("\\s*([·・])\\s*");

  private PidsText() {}

  /**
   * 去掉中文名里间隔号两侧的空格。
   *
   * <p>像素字体的中文间隔号是全角，自带左右留白；再加空格，“新笛矢 · 壑湖”就比终点列宽，首行被迫断成两行。
   *
   * @param text 原文；为 {@code null} 时返回空串
   */
  public static String compactSeparators(String text) {
    if (text == null) {
      return "";
    }
    return SPACED_SEPARATOR.matcher(text.strip()).replaceAll("$1");
  }
}
