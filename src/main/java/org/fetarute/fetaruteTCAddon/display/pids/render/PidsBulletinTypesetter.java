package org.fetarute.fetaruteTCAddon.display.pids.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout;

/**
 * 公告页排版：把标题与正文排进一块屏幕，放不下时分页。发布时检查长度与站台屏显示用同一套排法，页数不会对不上。
 *
 * <ul>
 *   <li>横屏（两块地图宽以上，如 1×3、1×4）：左上图标块与中文标题（24 号）、其下英文标题（10 号），右上角标签“公告”； 正文占满整屏宽，中文 12 号、英文 10
 *       号。标题每页相同，分页只分正文。
 *   <li>竖屏（一块地图宽，如 2×1）：顶部图标块与标签；其下标题与正文自上而下排开，中文标题 20 号至多两行。
 *   <li>中英文先试排在同一页；放不下时分两页：先中文页、后英文页（竖屏的英文页标题改用英文 12 号）。每页仍放不下的部分在最后一行省略， 并标记为截断，发布时据此拒绝。
 *   <li>折行：中文逐字，英文与数字（如“22:00”“WS”）按词不拆开，过长的词才逐字断开；行首不放“，。、；：？！）”等标点， 遇到时把上一个字一起移到下一行。正文里的换行照样换行。
 * </ul>
 */
public final class PidsBulletinTypesetter {

  /** 页边距。 */
  public static final int INSET = 8;

  /** 图标块边长。 */
  public static final int CHIP = 24;

  /** 横屏：图标块、标题顶边。 */
  public static final int LANDSCAPE_TOP = 4;

  /** 横屏：标题左缘（图标块右侧）。 */
  public static final int LANDSCAPE_TITLE_X = INSET + CHIP + INSET;

  /** 横屏：中文标题字号。 */
  public static final int LANDSCAPE_TITLE = 24;

  /** 横屏：英文标题（与页码同一行）顶边。 */
  public static final int LANDSCAPE_SECONDARY_TOP = 30;

  /** 横屏：正文顶边。 */
  public static final int LANDSCAPE_BODY_TOP = 46;

  /** 竖屏：图标块顶边。 */
  public static final int PORTRAIT_CHIP_TOP = 8;

  /** 竖屏：标题顶边。 */
  public static final int PORTRAIT_TOP = 44;

  /** 竖屏：分页时底部留给页码的高度。 */
  public static final int PORTRAIT_PAGE_ROW = 22;

  /** 正文离色带（或屏幕底边）至少留的空隙。 */
  private static final int BOTTOM_GAP = 2;

  private static final int PRIMARY = 12;
  private static final int SECONDARY = 10;
  private static final int PORTRAIT_TITLE = 20;
  private static final int PORTRAIT_TITLE_LINES = 2;
  private static final int SECONDARY_TITLE_LINES = 2;

  /** 中文与英文正文、标题与正文之间的空隙。 */
  private static final int LANGUAGE_GAP = 6;

  private static final int TITLE_GAP = 12;
  private static final String ELLIPSIS = "…";

  /** 不放在行首的标点。 */
  private static final String NO_LINE_START = "，。、；：？！）」』】》〉,.;:?!)]}%";

  /** 英文与数字里连在一起不拆开的字符。 */
  private static final String WORD_JOINERS = ":.'-/%&+#";

  /** 量宽：{@code primary} 为主文字（含仿粗体多出的宽度），否则为次要文字。 */
  @FunctionalInterface
  public interface Measure {
    int width(String text, int size, boolean primary);
  }

  /**
   * 一组同字号、同颜色的行。
   *
   * @param lines 各行
   * @param size 字号
   * @param primary 主文字（正文色，可仿粗体）；否则为次要文字（次要色、常规）
   * @param gapBefore 与上一组之间的空隙；第一组为 0
   */
  public record Block(List<String> lines, int size, boolean primary, int gapBefore) {

    public Block {
      lines = List.copyOf(lines);
    }

    /** 行距：大字号多留 2 像素。 */
    public int pitch() {
      return size + (size >= PORTRAIT_TITLE ? 4 : 2);
    }

    int height() {
      return lines.isEmpty() ? 0 : lines.size() * pitch() - (pitch() - size);
    }
  }

  /**
   * 一页：横屏只含正文，竖屏含标题与正文；自上而下依次排。
   *
   * @param blocks 各组
   */
  public record Page(List<Block> blocks) {

    public Page {
      blocks = List.copyOf(blocks);
    }

    int height() {
      int height = 0;
      for (Block block : blocks) {
        height += block.gapBefore() + block.height();
      }
      return height;
    }
  }

  /**
   * 排版结果（横屏还是竖屏由布局决定，见 {@link #landscape(PidsLayout)}）。
   *
   * @param pages 各页（至少一页）
   * @param titleTruncated 标题放不下、被省略
   * @param bodyTruncated 正文分两页仍放不下、被省略
   */
  public record Result(List<Page> pages, boolean titleTruncated, boolean bodyTruncated) {

    public Result {
      pages = List.copyOf(pages);
    }

    /** 标题与正文都完整显示。 */
    public boolean fits() {
      return !titleTruncated && !bodyTruncated;
    }
  }

  private final Measure measure;

  public PidsBulletinTypesetter(Measure measure) {
    this.measure = Objects.requireNonNull(measure, "measure");
  }

  /** 正文区的底边：色带顶边（没有色带时为屏幕底边）以上留一点空隙。 */
  static int contentBottom(PidsLayout layout) {
    int bottom =
        layout.widgets().stream()
            .filter(PidsLayout.LineBand.class::isInstance)
            .map(PidsLayout.LineBand.class::cast)
            .findFirst()
            .map(PidsLayout.LineBand::y)
            .orElse(layout.height());
    return bottom - BOTTOM_GAP;
  }

  /** 横屏排法：两块地图宽及以上。 */
  public static boolean landscape(PidsLayout layout) {
    return layout.tileCols() >= 2;
  }

  /**
   * 排一条公告。
   *
   * @param layout 屏幕布局
   * @param titlePrimary 中文标题
   * @param titleSecondary 英文标题（可为空串）
   * @param bodyPrimary 中文正文（可为空串）
   * @param bodySecondary 英文正文（可为空串）
   * @param labelWidth 横屏右上角标签的宽度（标题让出这么宽再加一个页边距）
   */
  public Result typeset(
      PidsLayout layout,
      String titlePrimary,
      String titleSecondary,
      String bodyPrimary,
      String bodySecondary,
      int labelWidth) {
    return landscape(layout)
        ? landscapePages(layout, titlePrimary, bodyPrimary, bodySecondary, labelWidth)
        : portraitPages(layout, titlePrimary, titleSecondary, bodyPrimary, bodySecondary);
  }

  private Result landscapePages(
      PidsLayout layout, String title, String bodyPrimary, String bodySecondary, int labelWidth) {
    int titleRoom = layout.width() - INSET - labelWidth - INSET - LANDSCAPE_TITLE_X;
    boolean titleTruncated = measure.width(title, LANDSCAPE_TITLE, true) > titleRoom;
    int width = layout.width() - INSET * 2;
    int room = contentBottom(layout) - LANDSCAPE_BODY_TOP;
    List<String> primary = wrap(bodyPrimary, PRIMARY, width, true);
    List<String> secondary = wrap(bodySecondary, SECONDARY, width, false);
    Page together = new Page(body(primary, secondary));
    if (together.height() <= room) {
      return new Result(List.of(together), titleTruncated, false);
    }
    List<Page> pages = new ArrayList<>();
    boolean truncated = false;
    if (!primary.isEmpty()) {
      List<String> fitted = fit(primary, PRIMARY, room, width, true);
      truncated |= fitted.size() < primary.size();
      pages.add(new Page(List.of(new Block(fitted, PRIMARY, true, 0))));
    }
    if (!secondary.isEmpty()) {
      List<String> fitted = fit(secondary, SECONDARY, room, width, false);
      truncated |= fitted.size() < secondary.size();
      pages.add(new Page(List.of(new Block(fitted, SECONDARY, false, 0))));
    }
    return new Result(pages, titleTruncated, truncated);
  }

  private Result portraitPages(
      PidsLayout layout,
      String titlePrimary,
      String titleSecondary,
      String bodyPrimary,
      String bodySecondary) {
    int width = layout.width() - INSET * 2;
    int bottom = contentBottom(layout);
    List<String> title = wrap(titlePrimary, PORTRAIT_TITLE, width, true);
    boolean titleTruncated = title.size() > PORTRAIT_TITLE_LINES;
    title = clip(title, PORTRAIT_TITLE_LINES, PORTRAIT_TITLE, width, true);
    List<String> primary = wrap(bodyPrimary, PRIMARY, width, true);
    List<String> secondary = wrap(bodySecondary, SECONDARY, width, false);

    List<Block> together = new ArrayList<>();
    together.add(new Block(title, PORTRAIT_TITLE, true, 0));
    List<String> smallTitle = wrap(titleSecondary, SECONDARY, width, false);
    if (!smallTitle.isEmpty()) {
      together.add(
          new Block(
              clip(smallTitle, SECONDARY_TITLE_LINES, SECONDARY, width, false),
              SECONDARY,
              false,
              2));
    }
    List<Block> body = body(primary, secondary);
    if (!body.isEmpty()) {
      together.add(withGap(body.get(0), TITLE_GAP));
      together.addAll(body.subList(1, body.size()));
    }
    Page one = new Page(together);
    if (PORTRAIT_TOP + one.height() <= bottom) {
      return new Result(List.of(one), titleTruncated, false);
    }

    int pagedBottom = bottom - PORTRAIT_PAGE_ROW;
    List<Page> pages = new ArrayList<>();
    boolean truncated = false;
    Block chineseTitle = new Block(title, PORTRAIT_TITLE, true, 0);
    int chineseRoom = pagedBottom - PORTRAIT_TOP - chineseTitle.height() - TITLE_GAP;
    List<String> chinese = fit(primary, PRIMARY, chineseRoom, width, true);
    truncated |= chinese.size() < primary.size();
    pages.add(
        new Page(
            chinese.isEmpty()
                ? List.of(chineseTitle)
                : List.of(chineseTitle, new Block(chinese, PRIMARY, true, TITLE_GAP))));
    List<String> englishTitle =
        clip(
            wrap(titleSecondary, PRIMARY, width, false),
            SECONDARY_TITLE_LINES,
            PRIMARY,
            width,
            false);
    if (!secondary.isEmpty() || !englishTitle.isEmpty()) {
      List<Block> blocks = new ArrayList<>();
      int used = 0;
      if (!englishTitle.isEmpty()) {
        Block block = new Block(englishTitle, PRIMARY, true, 0);
        blocks.add(block);
        used = block.height() + TITLE_GAP - 2;
      }
      List<String> english =
          fit(secondary, SECONDARY, pagedBottom - PORTRAIT_TOP - used, width, false);
      truncated |= english.size() < secondary.size();
      if (!english.isEmpty()) {
        blocks.add(new Block(english, SECONDARY, false, blocks.isEmpty() ? 0 : TITLE_GAP - 2));
      }
      pages.add(new Page(blocks));
    }
    return new Result(pages, titleTruncated, truncated);
  }

  /** 正文：中文一组、英文一组，两组都有时中间留空隙。 */
  private static List<Block> body(List<String> primary, List<String> secondary) {
    List<Block> blocks = new ArrayList<>();
    if (!primary.isEmpty()) {
      blocks.add(new Block(primary, PRIMARY, true, 0));
    }
    if (!secondary.isEmpty()) {
      blocks.add(new Block(secondary, SECONDARY, false, blocks.isEmpty() ? 0 : LANGUAGE_GAP));
    }
    return blocks;
  }

  private static Block withGap(Block block, int gap) {
    return new Block(block.lines(), block.size(), block.primary(), gap);
  }

  /** 在 {@code room} 高度内能放下的行；放不下的在最后一行省略。 */
  private List<String> fit(List<String> lines, int size, int room, int width, boolean primary) {
    int pitch = new Block(List.of(), size, primary, 0).pitch();
    int capacity = Math.max(0, (room + pitch - size) / pitch);
    return clip(lines, capacity, size, width, primary);
  }

  /** 至多 {@code max} 行；多出来的在最后一行末尾加省略号。 */
  private List<String> clip(List<String> lines, int max, int size, int width, boolean primary) {
    if (lines.size() <= max) {
      return lines;
    }
    if (max <= 0) {
      return List.of();
    }
    List<String> kept = new ArrayList<>(lines.subList(0, max));
    kept.set(max - 1, ellipsize(kept.get(max - 1), size, width, primary));
    return kept;
  }

  /** 在行尾加省略号，放不下就从末尾去字。 */
  private String ellipsize(String line, int size, int width, boolean primary) {
    String base = line.stripTrailing();
    while (!base.isEmpty() && measure.width(base + ELLIPSIS, size, primary) > width) {
      base = base.substring(0, base.offsetByCodePoints(base.length(), -1)).stripTrailing();
    }
    return base + ELLIPSIS;
  }

  /**
   * 折行。
   *
   * @param text 文本；其中的换行照样换行
   * @param size 字号
   * @param width 行宽
   * @param primary 按主文字量宽
   */
  List<String> wrap(String text, int size, int width, boolean primary) {
    List<String> lines = new ArrayList<>();
    if (text == null || text.isBlank()) {
      return lines;
    }
    for (String paragraph : text.strip().split("\\R")) {
      wrapParagraph(paragraph.strip(), size, width, primary, lines);
    }
    return lines;
  }

  private void wrapParagraph(
      String paragraph, int size, int width, boolean primary, List<String> lines) {
    if (paragraph.isEmpty()) {
      return;
    }
    List<String> line = new ArrayList<>();
    boolean breakPending = false;
    for (String token : tokens(paragraph)) {
      if (line.isEmpty() && token.isBlank()) {
        continue;
      }
      if (!breakPending && fits(line, token, size, width, primary)) {
        line.add(token);
        continue;
      }
      if (token.isBlank()) {
        // 行尾放不下的空格：丢掉并记下这里要换行，等下一个词再换；下一个词若是标点，还能按下面的规则带字换行
        breakPending = true;
        continue;
      }
      breakPending = false;
      List<String> carried = new ArrayList<>();
      if (startsWithForbidden(token)) {
        while (!line.isEmpty() && line.get(line.size() - 1).isBlank()) {
          line.remove(line.size() - 1);
        }
        if (line.size() < 2) {
          // 这一行只有一个词，带不走：标点悬挂在行尾
          line.add(token);
          continue;
        }
        carried.add(line.remove(line.size() - 1));
      }
      if (!line.isEmpty()) {
        lines.add(join(line));
      }
      line = carried;
      if (fits(line, token, size, width, primary)) {
        line.add(token);
      } else {
        line = breakLong(join(line) + token, size, width, primary, lines);
      }
    }
    if (!line.isEmpty()) {
      lines.add(join(line));
    }
  }

  /** 单个词比一行还宽：逐字断开，前面的整行放进 {@code lines}，返回最后不满一行的部分。 */
  private List<String> breakLong(
      String text, int size, int width, boolean primary, List<String> lines) {
    StringBuilder current = new StringBuilder();
    for (int i = 0; i < text.length(); ) {
      int codePoint = text.codePointAt(i);
      String next = current + Character.toString(codePoint);
      if (!current.isEmpty() && measure.width(next, size, primary) > width) {
        lines.add(current.toString().strip());
        current.setLength(0);
      }
      current.appendCodePoint(codePoint);
      i += Character.charCount(codePoint);
    }
    List<String> rest = new ArrayList<>();
    if (!current.isEmpty()) {
      rest.add(current.toString());
    }
    return rest;
  }

  private boolean fits(List<String> line, String token, int size, int width, boolean primary) {
    return measure.width((join(line) + token).strip(), size, primary) <= width;
  }

  private static String join(List<String> tokens) {
    return String.join("", tokens).strip();
  }

  private static boolean startsWithForbidden(String token) {
    return !token.isEmpty() && NO_LINE_START.indexOf(token.charAt(0)) >= 0;
  }

  /** 切词：连续的拉丁字母、数字（含 {@link #WORD_JOINERS}）是一个词，空白是一个词，其余每个字一个词。 */
  static List<String> tokens(String text) {
    List<String> tokens = new ArrayList<>();
    StringBuilder word = new StringBuilder();
    for (int i = 0; i < text.length(); ) {
      int codePoint = text.codePointAt(i);
      i += Character.charCount(codePoint);
      if (latin(codePoint) || (!word.isEmpty() && WORD_JOINERS.indexOf(codePoint) >= 0)) {
        word.appendCodePoint(codePoint);
        continue;
      }
      if (!word.isEmpty()) {
        tokens.add(word.toString());
        word.setLength(0);
      }
      String single = Character.toString(codePoint);
      if (Character.isWhitespace(codePoint)) {
        single = " ";
      }
      tokens.add(single);
    }
    if (!word.isEmpty()) {
      tokens.add(word.toString());
    }
    return tokens;
  }

  private static boolean latin(int codePoint) {
    return codePoint < 0x2000 && Character.isLetterOrDigit(codePoint);
  }
}
