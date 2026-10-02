package org.fetarute.fetaruteTCAddon.display.pids.render;

/**
 * 站台屏配色。
 *
 * <p>颜色均为 {@code 0xRRGGBB}，且都是 Minecraft 地图调色板里存在的颜色：取官网 palette 的语义，落到调色板上最接近、
 * 对比度足够的一色，渲染结果即游戏内所见。 调色板没有官网那种亮琥珀与珊瑚红，深色主题的晚点用亮黄、取消用纯红（12 号字的笔画只有 1 像素，暗红在深底上会糊）；
 * 浅色主题的底与首行面板在调色板里只能取白与浅灰，层次比官网更分明。
 *
 * @param background 底色
 * @param panel 首行底与分隔线
 * @param text 正文
 * @param muted 次要文字
 * @param outline 空心框（取消、计划、回库）
 * @param inverseBackground 反白块底色（进站、站台号）
 * @param inverseText 反白块文字
 * @param inverseMuted 反白块次要文字
 * @param amber 晚点；安全提示页的图标块
 * @param red 严重晚点、取消
 * @param info 宣传页的图标块（钢蓝，两种主题相同）
 * @param green 空位页“座位充足”的车厢
 */
public record PidsTheme(
    int background,
    int panel,
    int text,
    int muted,
    int outline,
    int inverseBackground,
    int inverseText,
    int inverseMuted,
    int amber,
    int red,
    int info,
    int green) {

  /** 深色（夜间）。 */
  public static final PidsTheme DARK =
      new PidsTheme(
          0x191919, 0x282828, 0xFFFFFF, 0xB4B4B4, 0x696969, 0xFFFFFF, 0x191919, 0x3D4040, 0xFAEE4D,
          0xFF0000, 0x365172, 0x7FCC19);

  /** 浅色（白天）。 */
  public static final PidsTheme LIGHT =
      new PidsTheme(
          0xFFFFFF, 0xDCDCDC, 0x191919, 0x646464, 0xB4B4B4, 0x191919, 0xFFFFFF, 0xC7C7C7, 0x985924,
          0xBD3031, 0x365172, 0x007C00);

  /** 线路色牌上的深色字。 */
  public static final int INK = 0x191919;

  /** 线路色牌上的浅色字。 */
  public static final int PAPER = 0xFFFFFF;

  /**
   * 线路色牌上的文字色：线路色相对亮度低于 0.25 用浅色字，否则用深色字（与官网 {@code getRailwayLineTextColor} 一致）。
   *
   * @param lineColor 线路色
   */
  public static int textOn(int lineColor) {
    return relativeLuminance(lineColor) < 0.25 ? PAPER : INK;
  }

  /** sRGB 相对亮度，与官网 {@code palette.ts} 同一公式。 */
  static double relativeLuminance(int rgb) {
    return 0.2126 * linear((rgb >> 16) & 0xFF)
        + 0.7152 * linear((rgb >> 8) & 0xFF)
        + 0.0722 * linear(rgb & 0xFF);
  }

  private static double linear(int channel) {
    double c = channel / 255.0;
    return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  }
}
