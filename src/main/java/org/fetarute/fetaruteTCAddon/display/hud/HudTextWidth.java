package org.fetarute.fetaruteTCAddon.display.hud;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * HUD 文字宽度：按原版默认字体估算像素宽度，并把过宽的组件截短。
 *
 * <p>只是防护：计分板会按最长一行撑宽，站名、模板写长了会遮住半个屏幕。宽度按原版字体的字距估算（含 1 像素间隔）：
 *
 * <ul>
 *   <li>ASCII 按原版 {@code ascii.png} 的字宽（多数字母 6 像素，{@code i}、{@code .} 等 2 像素，空格 4 像素）
 *   <li>拉丁字母扩展按 6 像素
 *   <li>其余字符（汉字、假名、方块与制表符等，原版用 Unifont 绘制）按 9 像素
 *   <li>粗体每个字多 1 像素；非文字组件（按键、精灵图等）按 9 像素
 * </ul>
 */
public final class HudTextWidth {

  /** 截断时补的省略号。 */
  static final String ELLIPSIS = "…";

  private static final int WIDE = 9;
  private static final int LATIN = 6;

  /** ASCII 0x20–0x7E 的字宽（含间隔）。 */
  private static final int[] ASCII = new int[0x7F];

  static {
    Arrays.fill(ASCII, LATIN);
    set(" ", 4);
    set("!.,:;|'i", 2);
    set("l`", 3);
    set("It[]", 4);
    set("fk<>(){}\"*", 5);
    set("@~", 7);
  }

  private HudTextWidth() {}

  private static void set(String chars, int width) {
    chars.chars().forEach(ch -> ASCII[ch] = width);
  }

  /** 单个字符的宽度（像素，含字间隔）。 */
  public static int advance(int codePoint) {
    if (codePoint >= 0x20 && codePoint < 0x7F) {
      return ASCII[codePoint];
    }
    if (codePoint < 0x20) {
      return 0;
    }
    return codePoint < 0x0250 ? LATIN : WIDE;
  }

  /** 纯文本宽度（像素）。 */
  public static int width(String text) {
    return width(text, false);
  }

  private static int width(String text, boolean bold) {
    int total = 0;
    for (int i = 0; i < text.length(); ) {
      int cp = text.codePointAt(i);
      total += advance(cp) + (bold && cp >= 0x20 ? 1 : 0);
      i += Character.charCount(cp);
    }
    return total;
  }

  /** 组件宽度（像素）。 */
  public static int width(Component component) {
    return width(component, false);
  }

  private static int width(Component component, boolean inheritedBold) {
    boolean bold = bold(component, inheritedBold);
    int total =
        component instanceof TextComponent text
            ? width(text.content(), bold)
            : leafWidth(component);
    for (Component child : component.children()) {
      total += width(child, bold);
    }
    return total;
  }

  /**
   * 把组件截到不超过 {@code maxWidth} 像素，超出时末尾补省略号；样式保持不变。
   *
   * @param component 组件
   * @param maxWidth 最大宽度（像素）；非正数表示不限
   * @return 截短后的组件；本来就不超宽时原样返回
   */
  public static Component truncate(Component component, int maxWidth) {
    if (component == null || maxWidth <= 0 || width(component) <= maxWidth) {
      return component;
    }
    int[] budget = {Math.max(0, maxWidth - width(ELLIPSIS))};
    return cut(component, budget, false).append(Component.text(ELLIPSIS));
  }

  /** 按剩余预算深度优先截取；预算用完后的兄弟与子组件全部丢弃。 */
  private static Component cut(Component component, int[] budget, boolean inheritedBold) {
    boolean bold = bold(component, inheritedBold);
    Component head;
    if (component instanceof TextComponent text) {
      head = text.content(fit(text.content(), budget, bold)).children(List.of());
    } else {
      int leaf = leafWidth(component);
      if (leaf > budget[0]) {
        budget[0] = 0;
        return Component.empty();
      }
      budget[0] -= leaf;
      head = component.children(List.of());
    }
    List<Component> children = new ArrayList<>();
    for (Component child : component.children()) {
      if (budget[0] <= 0) {
        break;
      }
      children.add(cut(child, budget, bold));
    }
    return head.children(children);
  }

  private static String fit(String text, int[] budget, boolean bold) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < text.length(); ) {
      int cp = text.codePointAt(i);
      int w = advance(cp) + (bold && cp >= 0x20 ? 1 : 0);
      if (w > budget[0]) {
        budget[0] = 0;
        break;
      }
      budget[0] -= w;
      out.appendCodePoint(cp);
      i += Character.charCount(cp);
    }
    return out.toString();
  }

  private static int leafWidth(Component component) {
    return component instanceof TextComponent ? 0 : WIDE;
  }

  private static boolean bold(Component component, boolean inherited) {
    TextDecoration.State state = component.style().decoration(TextDecoration.BOLD);
    return state == TextDecoration.State.NOT_SET ? inherited : state == TextDecoration.State.TRUE;
  }
}
