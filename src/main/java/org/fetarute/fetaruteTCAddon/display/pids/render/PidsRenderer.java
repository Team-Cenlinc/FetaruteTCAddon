package org.fetarute.fetaruteTCAddon.display.pids.render;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout.ArrivalStyle;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout.BadgeStyle;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout.Column;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout.Departures;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout.DestinationStyle;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout.PlatformStyle;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout.RowStyle;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout.TextStyle;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsNotice;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsNoticeView;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsTestCard;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Arrival;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Label;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Names;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Tone;

/**
 * 把 {@link PidsView} 按 {@link PidsLayout} 画成一帧 RGB 图像。
 *
 * <p>纯函数：同样的布局与视图得到同样的像素，不依赖服务器，单元测试可直接比对。关闭抗锯齿、只用纯色：地图调色板没有半透明， 灰阶边缘量化后会变成杂色。
 * 每个单元格只在自己的范围内绘制，长文字不会压到相邻列。
 */
public final class PidsRenderer {

  /** 空心框、分隔线与删除线的粗细。 */
  private static final int STROKE = 2;

  /** 空心“计划”框的左右内边距。 */
  private static final int BOX_PADDING = 4;

  private static final String DASH = "—";

  /** 一块地图的边长。 */
  private static final int TILE = 128;

  private static final int CARD_TITLE = 20;
  private static final int CARD_TEXT = 12;
  private static final int CARD_SMALL = 10;
  private static final int CARD_INSET = 4;

  /** 宣传页图标块边长与其中图标的边长。 */
  private static final int NOTICE_BLOCK = 64;

  private static final int NOTICE_ICON = 48;

  /** 宣传页标题字号（仿粗体）。 */
  private static final int NOTICE_TITLE = 24;

  private static final int NOTICE_INSET = 8;
  private static final int NOTICE_GAP = 4;
  private static final int NOTICE_BODY_LINES = 2;

  private final PidsFonts fonts;

  public PidsRenderer(PidsFonts fonts) {
    this.fonts = Objects.requireNonNull(fonts, "fonts");
  }

  /** 渲染一帧。 */
  public BufferedImage render(PidsLayout layout, PidsView view) {
    Objects.requireNonNull(layout, "layout");
    Objects.requireNonNull(view, "view");
    return paint(
        layout.width(),
        layout.height(),
        view.theme(),
        layout.boldFrom(),
        painter -> {
          for (PidsLayout.Widget widget : layout.widgets()) {
            drawWidget(painter, widget, view);
          }
        });
  }

  /**
   * 渲染测试卡：白底，按地图画格线并在每块右下角写编号（行优先、从 1 起），左上角写标题与说明，底部写提示。
   *
   * <p>编号让装屏的人一眼看出展示框顺序是否正确。
   */
  public BufferedImage renderTestCard(PidsTestCard card) {
    Objects.requireNonNull(card, "card");
    int width = card.tileCols() * TILE;
    int height = card.tileRows() * TILE;
    PidsTheme theme = PidsTheme.LIGHT;
    return paint(
        width,
        height,
        theme,
        PidsLayout.DEFAULT_BOLD_FROM,
        p -> {
          for (int col = 1; col < card.tileCols(); col++) {
            p.fill(col * TILE, 0, 1, height, theme.outline());
          }
          for (int row = 1; row < card.tileRows(); row++) {
            p.fill(0, row * TILE, width, 1, theme.outline());
          }
          for (int row = 0; row < card.tileRows(); row++) {
            for (int col = 0; col < card.tileCols(); col++) {
              p.regularRight(
                  Integer.toString(row * card.tileCols() + col + 1),
                  CARD_SMALL,
                  (col + 1) * TILE - CARD_INSET,
                  (row + 1) * TILE - CARD_SMALL - CARD_INSET,
                  theme.muted());
            }
          }
          int maxWidth = width - CARD_INSET * 4;
          p.text(card.title().primary(), CARD_TITLE, CARD_INSET * 2, CARD_INSET * 2, theme.text());
          p.regular(
              p.ellipsizeWords(card.title().secondary(), CARD_SMALL, maxWidth),
              CARD_SMALL,
              CARD_INSET * 2,
              CARD_INSET * 2 + CARD_TITLE + 3,
              theme.muted(),
              false);
          int top = CARD_INSET * 2 + CARD_TITLE + CARD_SMALL + 8;
          int hintTop = height - CARD_SMALL - CARD_TEXT - 6;
          for (String line : card.lines()) {
            if (top + CARD_TEXT > hintTop - 2) {
              break;
            }
            p.text(
                p.ellipsize(line, CARD_TEXT, maxWidth),
                CARD_TEXT,
                CARD_INSET * 2,
                top,
                theme.text());
            top += CARD_TEXT + 2;
          }
          p.text(
              p.ellipsize(card.hint(), CARD_TEXT, maxWidth),
              CARD_TEXT,
              CARD_INSET * 2,
              hintTop,
              theme.amber());
        });
  }

  /**
   * 渲染宣传页或安全提示页，尺寸与布局相同。
   *
   * <p>第一块地图放图标（宣传页用信息色、安全提示页用警示色的方块，图标颜色按底色取深浅），最后一块放说明（中文一行、英文按词最多两行），
   * 中间各块放标题；只有一两块地图时省掉说明。色带取布局里的线路色带组件，与主页同一位置、同一配色，翻页时色带不动。
   */
  public BufferedImage renderNotice(PidsLayout layout, PidsNoticeView view) {
    Objects.requireNonNull(layout, "layout");
    Objects.requireNonNull(view, "view");
    int width = layout.width();
    Optional<PidsLayout.LineBand> band =
        layout.widgets().stream()
            .filter(PidsLayout.LineBand.class::isInstance)
            .map(PidsLayout.LineBand.class::cast)
            .findFirst();
    int content = band.map(PidsLayout.LineBand::y).orElse(layout.height());
    boolean withBody = layout.tileCols() >= 3;
    PidsTheme theme = view.theme();
    return paint(
        width,
        layout.height(),
        theme,
        layout.boldFrom(),
        p -> {
          int blockColor = view.notice().warning() ? theme.amber() : theme.info();
          int blockX = (TILE - NOTICE_BLOCK) / 2;
          int blockY = (content - NOTICE_BLOCK) / 2;
          p.fill(blockX, blockY, NOTICE_BLOCK, NOTICE_BLOCK, blockColor);
          int inset = (NOTICE_BLOCK - NOTICE_ICON) / 2;
          drawNoticeIcon(
              p, view.notice(), blockX + inset, blockY + inset, PidsTheme.textOn(blockColor));

          int titleX = TILE + NOTICE_INSET;
          int titleWidth = width - TILE * (withBody ? 2 : 1) - NOTICE_INSET * 2;
          int titleTop = (content - (NOTICE_TITLE + NOTICE_GAP + CARD_TEXT)) / 2;
          p.text(
              p.ellipsize(view.title().primary(), NOTICE_TITLE, titleWidth),
              NOTICE_TITLE,
              titleX,
              titleTop,
              theme.text());
          p.regular(
              p.ellipsizeWords(view.title().secondary(), CARD_TEXT, titleWidth),
              CARD_TEXT,
              titleX,
              titleTop + NOTICE_TITLE + NOTICE_GAP,
              theme.muted(),
              false);

          if (withBody) {
            int bodyX = width - TILE + NOTICE_INSET;
            int bodyWidth = TILE - NOTICE_INSET * 2;
            List<String> lines =
                wordLines(view.body().secondary(), CARD_SMALL, bodyWidth, NOTICE_BODY_LINES, p);
            int height =
                CARD_TEXT + NOTICE_GAP + lines.size() * CARD_SMALL + (lines.size() - 1) * 2;
            int top = (content - height) / 2;
            p.text(
                p.ellipsize(view.body().primary(), CARD_TEXT, bodyWidth),
                CARD_TEXT,
                bodyX,
                top,
                theme.text());
            int lineTop = top + CARD_TEXT + NOTICE_GAP;
            for (String line : lines) {
              p.regular(line, CARD_SMALL, bodyX, lineTop, theme.muted(), false);
              lineTop += CARD_SMALL + 2;
            }
          }
          band.ifPresent(found -> drawLineBand(p, found, view.bandColors()));
        });
  }

  /** 48×48 像素图标，坐标都是整数，笔画与像素对齐。 */
  private static void drawNoticeIcon(Painter p, PidsNotice notice, int x, int y, int rgb) {
    switch (notice) {
      case ORDER -> {
        // 上方向左、下方向右的两支箭头：先下后上
        p.triangle(new int[] {x + 3, x + 15, x + 15}, new int[] {y + 13, y + 4, y + 22}, rgb);
        p.fill(x + 15, y + 9, 29, 9, rgb);
        p.triangle(new int[] {x + 45, x + 33, x + 33}, new int[] {y + 35, y + 26, y + 44}, rgb);
        p.fill(x + 4, y + 31, 29, 9, rgb);
      }
      case QUEUE -> {
        for (int i = 0; i < 3; i++) {
          p.fill(x + 4 + i * 16, y + 7, 8, 8, rgb);
          p.fill(x + 1 + i * 16, y + 19, 14, 22, rgb);
        }
      }
      case DOORS -> {
        p.fill(x + 4, y + 4, 17, 40, rgb);
        p.fill(x + 27, y + 4, 17, 40, rgb);
        p.fill(x + 23, y + 18, 2, 12, rgb);
      }
      case PASSING -> {
        p.fill(x + 20, y + 4, 8, 28, rgb);
        p.fill(x + 20, y + 36, 8, 8, rgb);
      }
    }
  }

  /** 英文按词排成最多 {@code maxLines} 行，放不下的部分在最后一行省略。 */
  private List<String> wordLines(String text, int size, int maxWidth, int maxLines, Painter p) {
    List<String> lines = new ArrayList<>();
    String rest = text.strip();
    while (!rest.isEmpty() && lines.size() < maxLines - 1) {
      int cut = rest.length();
      while (p.regularWidth(rest.substring(0, cut), size) > maxWidth) {
        int space = rest.lastIndexOf(' ', cut - 1);
        if (space <= 0) {
          break;
        }
        cut = space;
      }
      if (p.regularWidth(rest.substring(0, cut), size) > maxWidth) {
        break;
      }
      lines.add(rest.substring(0, cut).strip());
      rest = rest.substring(cut).strip();
    }
    if (!rest.isEmpty()) {
      lines.add(p.ellipsizeWords(rest, size, maxWidth));
    }
    return lines;
  }

  private BufferedImage paint(
      int width, int height, PidsTheme theme, int boldFrom, Consumer<Painter> body) {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
      g.setRenderingHint(
          RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
      g.setRenderingHint(
          RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
      Painter painter = new Painter(g, theme, boldFrom);
      painter.fill(0, 0, width, height, theme.background());
      body.accept(painter);
    } finally {
      g.dispose();
    }
    return image;
  }

  private void drawWidget(Painter p, PidsLayout.Widget widget, PidsView view) {
    if (widget instanceof PidsLayout.Platform platform) {
      drawPlatform(p, platform, view);
    } else if (widget instanceof PidsLayout.Clock clock) {
      if (clock.align() == PidsLayout.Align.RIGHT) {
        p.textRight(view.clock(), clock.size(), clock.x(), clock.y(), p.theme.text());
      } else {
        p.text(view.clock(), clock.size(), clock.x(), clock.y(), p.theme.text());
      }
    } else if (widget instanceof PidsLayout.LineBand band) {
      drawLineBand(p, band, view.bandColors());
    } else if (widget instanceof PidsLayout.StationTitle title) {
      view.station().ifPresent(names -> drawStationTitle(p, title, names));
    } else if (widget instanceof PidsLayout.LineStrip strip) {
      drawLineStrip(p, strip, view.lines());
    } else if (widget instanceof Departures departures) {
      drawDepartures(p, departures, view);
    }
  }

  // ─── 站台号、色带、站名、换乘条 ───────────────────────────────────────────────

  /** 色带按线路条数等分；没有线路时铺面板色。 */
  private void drawLineBand(Painter p, PidsLayout.LineBand band, List<Integer> colors) {
    if (colors.isEmpty()) {
      p.fill(band.x(), band.y(), band.width(), band.height(), p.theme.panel());
      return;
    }
    int segment = band.width() / colors.size();
    for (int i = 0; i < colors.size(); i++) {
      int x = band.x() + i * segment;
      int width = i == colors.size() - 1 ? band.x() + band.width() - x : segment;
      p.fill(x, band.y(), width, band.height(), colors.get(i));
    }
  }

  /** 每个站台一个反白方块，按布局的每行个数排开，至多 {@code max} 个；标签跟在最后一行方块下面。 */
  private void drawPlatform(Painter p, PidsLayout.Platform platform, PidsView view) {
    List<String> platforms =
        view.platforms().subList(0, Math.min(view.platforms().size(), platform.max()));
    if (platforms.isEmpty()) {
      return;
    }
    int box = platform.box();
    for (int i = 0; i < platforms.size(); i++) {
      int x = platform.x() + (i % platform.perRow()) * (box + platform.gap());
      int y = platform.y() + (i / platform.perRow()) * (box + platform.gap());
      p.fill(x, y, box, box, p.theme.inverseBackground());
      // 站台号原字号放不下时（如“12”“A1”）改用 12 号，仍放不下再省略
      String label = platforms.get(i);
      int size = platform.numberSize();
      if (p.width(label, size) > box - STROKE && size > CARD_TEXT) {
        size = CARD_TEXT;
      }
      p.textCentered(
          p.ellipsize(label, size, box - STROKE),
          size,
          x + box / 2,
          y + (box - size) / 2,
          p.theme.inverseText());
    }
    int rows = (platforms.size() + platform.perRow() - 1) / platform.perRow();
    p.stacked(
        view.labels().platform(),
        platform.label(),
        platform.x(),
        platform.y() + rows * box + (rows - 1) * platform.gap() + platform.labelGap(),
        p.theme.text(),
        p.theme.muted());
  }

  private void drawStationTitle(Painter p, PidsLayout.StationTitle title, Names names) {
    int baseline = title.y() + p.baseline(names.primary(), title.sizes().get(0));
    Font secondary = p.font(names.secondary(), title.secondarySize());
    for (int size : title.sizes()) {
      int width = p.width(names.primary(), size);
      int total =
          names.secondary().isEmpty()
              ? width
              : width + title.gap() + p.regularWidth(names.secondary(), title.secondarySize());
      if (total <= title.width()) {
        p.textAtBaseline(
            names.primary(),
            p.font(names.primary(), size),
            title.x(),
            baseline,
            p.theme.text(),
            p.bold(size));
        p.textAtBaseline(
            names.secondary(),
            secondary,
            title.x() + width + title.gap(),
            baseline,
            p.theme.muted(),
            false);
        return;
      }
    }
    int smallest = title.sizes().get(title.sizes().size() - 1);
    p.textAtBaseline(
        p.ellipsize(names.primary(), smallest, title.width()),
        p.font(names.primary(), smallest),
        title.x(),
        baseline,
        p.theme.text(),
        p.bold(smallest));
  }

  private void drawLineStrip(Painter p, PidsLayout.LineStrip strip, List<PidsView.LineChip> lines) {
    if (lines.isEmpty()) {
      return;
    }
    int segment = strip.width() / lines.size();
    for (int i = 0; i < lines.size(); i++) {
      PidsView.LineChip line = lines.get(i);
      int x = strip.x() + i * segment;
      int width = i == lines.size() - 1 ? strip.x() + strip.width() - x : segment;
      p.fill(x, strip.y(), width, strip.bandHeight(), line.color());
      int chipX = x + strip.inset();
      int tickTop = strip.y() + strip.bandHeight();
      p.fill(
          chipX + strip.tickOffset(), tickTop, strip.tickWidth(), strip.tickHeight(), line.color());
      int chipTop = tickTop + strip.tickHeight();
      int codeWidth = p.width(line.code(), strip.chipSize());
      int chipWidth = codeWidth + strip.chipPadding() * 2;
      p.fill(chipX, chipTop, chipWidth, strip.chipHeight(), line.color());
      p.text(
          line.code(),
          strip.chipSize(),
          chipX + strip.chipPadding(),
          chipTop + (strip.chipHeight() - strip.chipSize()) / 2,
          PidsTheme.textOn(line.color()));
      int nameX = chipX + chipWidth + strip.nameGap();
      p.clip(x, chipTop, width, strip.chipHeight());
      p.inline(
          line.name(),
          strip.name(),
          nameX,
          chipTop + (strip.chipHeight() - strip.name().size()) / 2,
          x + width - nameX,
          p.theme.text(),
          p.theme.muted());
      p.unclip();
    }
  }

  // ─── 到发表 ─────────────────────────────────────────────────────────────────

  private void drawDepartures(Painter p, Departures departures, PidsView view) {
    departures.header().ifPresent(header -> drawHeader(p, departures, header, view.labels()));
    int y = departures.firstRowY();
    boolean messageShown = false;
    for (int i = 0; i < departures.rowCapacity(); i++) {
      RowStyle style = departures.styleOf(i);
      if (style.divider() > 0) {
        p.fill(departures.x(), y, departures.width(), style.divider(), p.theme.panel());
      }
      int top = y + style.divider();
      int height = style.height() - style.divider();
      if (style.panel()) {
        p.fill(departures.x(), top, departures.width(), height, p.theme.panel());
      }
      Cell cell = new Cell(departures, style, top, height);
      if (i < view.rows().size()) {
        drawRow(p, cell, view.rows().get(i), view.labels());
      } else if (!messageShown) {
        drawNoMoreTrains(p, cell, view.labels().noMoreTrains());
        messageShown = true;
      }
      y += style.height() + style.gap();
    }
  }

  private void drawHeader(
      Painter p, Departures departures, PidsLayout.Header header, PidsView.Labels labels) {
    PidsLayout.Columns columns = departures.columns();
    int top = departures.y() + (header.height() - header.label().size()) / 2;
    drawHeaderLabel(p, departures, columns.badge(), header, labels.headerLine(), top);
    drawHeaderLabel(p, departures, columns.destination(), header, labels.headerDestination(), top);
    columns
        .platform()
        .ifPresent(
            column -> drawHeaderLabel(p, departures, column, header, labels.headerPlatform(), top));
    drawHeaderLabel(p, departures, columns.arrival(), header, labels.headerArrival(), top);
  }

  private void drawHeaderLabel(
      Painter p,
      Departures departures,
      Column column,
      PidsLayout.Header header,
      Names label,
      int top) {
    int x = departures.x() + column.x() + header.inset();
    p.inline(
        label,
        header.label(),
        x,
        top,
        column.width() - header.inset(),
        p.theme.muted(),
        p.theme.muted());
  }

  private void drawRow(Painter p, Cell cell, PidsView.Row row, PidsView.Labels labels) {
    PidsLayout.Columns columns = cell.departures.columns();
    p.clip(cell.columnX(columns.badge()), cell.top, columns.badge().width(), cell.height);
    drawBadge(p, cell, columns.badge(), cell.style.badge(), row.badge());
    p.clip(
        cell.columnX(columns.destination()), cell.top, columns.destination().width(), cell.height);
    drawDestination(p, cell, columns.destination(), cell.style.destination(), row.destination());
    if (columns.platform().isPresent()) {
      Column column = columns.platform().get();
      p.clip(cell.columnX(column), cell.top, column.width(), cell.height);
      drawPlatformCell(p, cell, column, cell.style.platform(), row.platform(), labels.platform());
    }
    p.clip(cell.columnX(columns.arrival()), cell.top, columns.arrival().width(), cell.height);
    drawArrival(p, cell, columns.arrival(), cell.style.arrival(), row.arrival(), labels.minutes());
    p.unclip();
  }

  private void drawBadge(
      Painter p, Cell cell, Column column, BadgeStyle style, PidsView.Badge badge) {
    int x =
        style.inset() < 0
            ? cell.columnX(column) + (column.width() - style.width()) / 2
            : cell.columnX(column) + style.inset();
    int y = cell.top + (cell.height - style.height()) / 2;
    int ink;
    if (badge.hollow()) {
      p.outline(x, y, style.width(), style.height(), badge.color());
      ink = badge.color();
    } else {
      p.fill(x, y, style.width(), style.height(), badge.color());
      ink = PidsTheme.textOn(badge.color());
    }
    int codeWidth = badge.type().isPresent() ? style.codeWidth() : style.width();
    p.textCentered(
        badge.code(),
        style.codeSize(),
        x + codeWidth / 2,
        y + (style.height() - style.codeSize()) / 2,
        ink);
    if (badge.type().isEmpty()) {
      return;
    }
    p.fill(x + codeWidth, y, STROKE, style.height(), ink);
    int typeX = x + codeWidth + STROKE;
    int typeWidth = style.width() - codeWidth - STROKE;
    List<String> chars =
        badge.type().get().codePoints().limit(2).mapToObj(Character::toString).toList();
    int block = chars.size() * style.typeSize() + (chars.size() - 1) * style.typeGap();
    int charTop = y + (style.height() - block) / 2;
    for (String ch : chars) {
      p.textCentered(ch, style.typeSize(), typeX + typeWidth / 2, charTop, ink);
      charTop += style.typeSize() + style.typeGap();
    }
  }

  private void drawDestination(
      Painter p, Cell cell, Column column, DestinationStyle style, PidsView.Destination dest) {
    int x = cell.columnX(column) + style.inset();
    int available = column.width() - style.inset();
    int primaryColor = dest.tone() == Tone.MUTED ? p.theme.muted() : p.color(dest.tone());
    int secondaryColor = p.theme.muted();
    TextStyle text = style.text();
    Names names = dest.names();
    if (style.inline()) {
      int top = cell.top + (cell.height - text.size()) / 2;
      p.inline(names, text, x, top, available, primaryColor, secondaryColor, dest.struck());
      return;
    }
    if (p.width(names.primary(), text.size()) <= available) {
      p.stackedIn(names, text, x, cell, available, primaryColor, secondaryColor, dest.struck());
      return;
    }
    if (style.wrap()) {
      Optional<List<String>> lines =
          splitInTwo(
              p.font(names.primary(), text.size()),
              names.primary(),
              available - (p.bold(text.size()) ? 1 : 0));
      if (lines.isPresent() && text.size() * 2 + text.gap() <= cell.height) {
        int top = cell.top + (cell.height - text.size() * 2 - text.gap()) / 2;
        p.text(lines.get().get(0), text.size(), x, top, primaryColor, dest.struck());
        p.text(
            lines.get().get(1),
            text.size(),
            x,
            top + text.size() + text.gap(),
            primaryColor,
            dest.struck());
        return;
      }
    }
    if (style.fallbackSize() > 0) {
      TextStyle fallback = new TextStyle(style.fallbackSize(), text.secondarySize(), text.gap());
      if (p.width(names.primary(), style.fallbackSize()) <= available || !style.wrap()) {
        p.stackedIn(
            names, fallback, x, cell, available, primaryColor, secondaryColor, dest.struck());
        return;
      }
      List<String> lines =
          greedyTwoLines(
              p.font(names.primary(), style.fallbackSize()),
              names.primary(),
              available - (p.bold(style.fallbackSize()) ? 1 : 0));
      int secondaryHeight =
          text.hasSecondary() && !names.secondary().isEmpty()
              ? text.gap() + text.secondarySize()
              : 0;
      int block = style.fallbackSize() * 2 + text.gap() + secondaryHeight;
      int top = cell.top + (cell.height - block) / 2;
      p.text(lines.get(0), style.fallbackSize(), x, top, primaryColor, dest.struck());
      top += style.fallbackSize() + text.gap();
      p.text(lines.get(1), style.fallbackSize(), x, top, primaryColor, dest.struck());
      if (secondaryHeight > 0) {
        top += style.fallbackSize() + text.gap();
        p.regular(
            p.ellipsizeWords(names.secondary(), text.secondarySize(), available),
            text.secondarySize(),
            x,
            top,
            secondaryColor,
            dest.struck());
      }
      return;
    }
    p.stackedIn(names, text, x, cell, available, primaryColor, secondaryColor, dest.struck());
  }

  private void drawNoMoreTrains(Painter p, Cell cell, Names message) {
    Column column = cell.departures.columns().destination();
    DestinationStyle style = cell.style.destination();
    int x = cell.columnX(column) + style.inset();
    int available = cell.departures.width() - column.x() - style.inset();
    p.clip(x, cell.top, available, cell.height);
    if (style.inline()) {
      p.inline(
          message,
          style.text(),
          x,
          cell.top + (cell.height - style.text().size()) / 2,
          available,
          p.theme.muted(),
          p.theme.muted());
    } else {
      p.stackedIn(
          message, style.text(), x, cell, available, p.theme.muted(), p.theme.muted(), false);
    }
    p.unclip();
  }

  private void drawPlatformCell(
      Painter p,
      Cell cell,
      Column column,
      PlatformStyle style,
      PidsView.PlatformCell platform,
      Names label) {
    int x = cell.columnX(column) + style.inset();
    int y = cell.top + (cell.height - style.box()) / 2;
    int numberColor;
    if (platform.hollow()) {
      p.outline(x, y, style.box(), style.box(), p.theme.outline());
      numberColor = p.theme.muted();
    } else {
      p.fill(x, y, style.box(), style.box(), p.theme.inverseBackground());
      numberColor = p.theme.inverseText();
    }
    p.textCentered(
        platform.number(),
        style.size(),
        x + style.box() / 2,
        y + (style.box() - style.size()) / 2,
        numberColor);
    if (style.labelSize() > 0) {
      // “站台”与方块底边对齐：像素字形的最后一行墨迹就在基线那一行，让它与方块最后一行同高。
      p.text(
          label.primary(),
          style.labelSize(),
          x + style.box() + style.gap(),
          y + style.box() - 1 - p.baseline(label.primary(), style.labelSize()),
          p.theme.muted());
    }
  }

  private void drawArrival(
      Painter p, Cell cell, Column column, ArrivalStyle style, Arrival arrival, Names unit) {
    int cellX = cell.columnX(column);
    int left = cellX + style.inset();
    int right = cellX + column.width() - style.insetRight();
    switch (arrival.mode()) {
      case HIGHLIGHT -> {
        p.fill(cellX, cell.top, column.width(), cell.height, p.theme.inverseBackground());
        arrival
            .status()
            .ifPresent(
                label ->
                    drawHighlight(
                        p,
                        cell,
                        style,
                        label,
                        left,
                        right,
                        p.theme.inverseText(),
                        p.theme.inverseMuted()));
      }
      case DASH -> {
        if (style.dash()) {
          int numberTop = cell.top + (cell.height - style.numberSize()) / 2;
          int width = p.width(DASH, style.numberSize());
          int box = Math.max(style.numberBox(), width);
          int x = style.numberAlign() == PidsLayout.Align.RIGHT ? left + box - width : left;
          p.text(DASH, style.numberSize(), x, numberTop, p.theme.muted());
          arrival
              .status()
              .ifPresent(
                  label ->
                      drawStatus(
                          p,
                          cell,
                          style,
                          label,
                          left,
                          right,
                          numberTop + p.baseline(DASH, style.numberSize()),
                          box));
        } else {
          arrival
              .status()
              .ifPresent(
                  label ->
                      drawHighlight(
                          p,
                          cell,
                          style,
                          label,
                          left,
                          right,
                          p.color(label.tone()),
                          p.color(label.tone())));
        }
      }
      case COUNTDOWN -> drawCountdown(p, cell, style, arrival, unit, left, right);
    }
  }

  private void drawCountdown(
      Painter p, Cell cell, ArrivalStyle style, Arrival arrival, Names unit, int left, int right) {
    String number = Integer.toString(arrival.minutes());
    int numberColor = arrival.minutesTone() == Tone.MUTED ? p.theme.muted() : p.theme.text();
    int width = p.width(number, style.numberSize());
    int box = Math.max(style.numberBox(), width);
    int numberTop = cell.top + (cell.height - style.numberSize()) / 2;
    int numberX = style.numberAlign() == PidsLayout.Align.RIGHT ? left + box - width : left;
    p.text(number, style.numberSize(), numberX, numberTop, numberColor);
    int baseline = numberTop + p.baseline(number, style.numberSize());
    int unitX = left + box + style.numberGap();
    int unitWidth;
    if (style.unitStacked()) {
      unitWidth =
          p.stackedIn(
              unit,
              style.unit(),
              unitX,
              cell,
              Integer.MAX_VALUE,
              numberColor,
              p.theme.muted(),
              false);
    } else {
      int size = style.unit().size();
      p.textAtBaseline(
          unit.primary(), p.font(unit.primary(), size), unitX, baseline, numberColor, p.bold(size));
      unitWidth = p.width(unit.primary(), size);
    }
    int group = box + style.numberGap() + unitWidth;
    arrival
        .status()
        .ifPresent(label -> drawStatus(p, cell, style, label, left, right, baseline, group));
  }

  /** 状态：叠放时靠右、竖向居中；同行时只显示中文，从 {@code statusOffset} 起与数字基线对齐。 */
  private void drawStatus(
      Painter p,
      Cell cell,
      ArrivalStyle style,
      Label label,
      int left,
      int right,
      int baseline,
      int groupWidth) {
    int primaryColor = p.color(label.tone());
    int secondaryColor = label.tone() == Tone.NORMAL ? p.theme.muted() : primaryColor;
    TextStyle text = style.status();
    if (style.statusStacked()) {
      if (label.boxed()) {
        int width = p.width(label.text().primary(), text.size()) + BOX_PADDING * 2;
        int top = cell.top + (cell.height - text.size() - STROKE * 2) / 2;
        drawBoxed(p, label.text().primary(), text.size(), right - width, top, primaryColor);
        return;
      }
      int available = right - left - groupWidth - style.numberGap();
      Names names = fit(p, label, text, available, true);
      p.stackedRight(names, text, right, cell, primaryColor, secondaryColor);
      return;
    }
    int x = left + Math.max(style.statusOffset(), groupWidth + style.numberGap());
    String primary = label.text().primary();
    if (label.boxed()) {
      int top = baseline - p.baseline(primary, text.size()) - STROKE;
      drawBoxed(p, primary, text.size(), x, top, p.theme.muted());
      return;
    }
    String fitted = fit(p, label, text, right - x, false).primary();
    p.textAtBaseline(
        fitted, p.font(fitted, text.size()), x, baseline, primaryColor, p.bold(text.size()));
  }

  /** 状态放进可用宽度：先全称，再短写法，再去掉中文与数字间的空格（“晚点12分”），仍放不下才省略。 */
  private Names fit(Painter p, Label label, TextStyle text, int available, boolean secondary) {
    List<Names> written =
        label.compact().map(c -> List.of(label.text(), c)).orElse(List.of(label.text()));
    List<Names> candidates = new ArrayList<>(written);
    for (Names names : written) {
      candidates.add(new Names(names.primary().replace(" ", ""), names.secondary()));
    }
    for (Names candidate : candidates) {
      boolean primaryFits = p.width(candidate.primary(), text.size()) <= available;
      boolean secondaryFits =
          !secondary
              || !text.hasSecondary()
              || candidate.secondary().isEmpty()
              || p.regularWidth(candidate.secondary(), text.secondarySize()) <= available;
      if (primaryFits && secondaryFits) {
        return candidate;
      }
    }
    Names last = candidates.get(candidates.size() - 1);
    return new Names(
        p.ellipsize(last.primary(), text.size(), available),
        secondary && text.hasSecondary()
            ? p.ellipsizeWords(last.secondary(), text.secondarySize(), available)
            : last.secondary());
  }

  private void drawBoxed(Painter p, String text, int size, int x, int top, int textColor) {
    int width = p.width(text, size) + BOX_PADDING * 2;
    p.outline(x, top, width, size + STROKE * 2, p.theme.outline());
    p.text(text, size, x + BOX_PADDING, top + STROKE, textColor);
  }

  /** 整格提示：中文在左；英文靠右（spread）或紧跟中文、基线对齐。 */
  private void drawHighlight(
      Painter p,
      Cell cell,
      ArrivalStyle style,
      Label label,
      int left,
      int right,
      int primaryColor,
      int secondaryColor) {
    TextStyle text = style.highlight();
    Names names = label.text();
    int top = cell.top + (cell.height - text.size()) / 2;
    if (!style.highlightSpread()) {
      p.inline(names, text, left, top, right - left, primaryColor, secondaryColor);
      return;
    }
    int primaryWidth = p.text(names.primary(), text.size(), left, top, primaryColor);
    boolean secondaryFits =
        primaryWidth + text.gap() + p.regularWidth(names.secondary(), text.secondarySize())
            <= right - left;
    if (text.hasSecondary() && !names.secondary().isEmpty() && secondaryFits) {
      int secondaryTop = cell.top + (cell.height - text.secondarySize()) / 2;
      p.regularRight(names.secondary(), text.secondarySize(), right, secondaryTop, secondaryColor);
    }
  }

  // ─── 断行 ───────────────────────────────────────────────────────────────────

  /** 在“·”处（没有时从中间）断成两行；两行都放得下才返回。 */
  private Optional<List<String>> splitInTwo(Font font, String text, int maxWidth) {
    int dot = text.indexOf('·');
    String first;
    String second;
    if (dot > 0 && dot < text.length() - 1) {
      first = text.substring(0, dot).strip();
      second = text.substring(dot + 1).strip();
    } else {
      int count = text.codePointCount(0, text.length());
      int middle = text.offsetByCodePoints(0, (count + 1) / 2);
      first = text.substring(0, middle).strip();
      second = text.substring(middle).strip();
    }
    if (fonts.width(font, first) <= maxWidth && fonts.width(font, second) <= maxWidth) {
      return Optional.of(List.of(first, second));
    }
    return Optional.empty();
  }

  /** 第一行尽量多放，余下放第二行，超出时省略。 */
  private List<String> greedyTwoLines(Font font, String text, int maxWidth) {
    int end = text.length();
    while (end > 0 && fonts.width(font, text.substring(0, end)) > maxWidth) {
      end = text.offsetByCodePoints(end, -1);
    }
    String first = text.substring(0, end).strip();
    String rest = text.substring(end).strip();
    return List.of(first, fonts.ellipsize(font, rest, maxWidth));
  }

  // ─── 绘制辅助 ───────────────────────────────────────────────────────────────

  /** 一行在到发表中的位置。 */
  private record Cell(Departures departures, RowStyle style, int top, int height) {

    int columnX(Column column) {
      return departures.x() + column.x();
    }
  }

  /**
   * 一帧的绘制状态：画笔、配色、字体与仿粗体规则。
   *
   * <p>主文字（中文名、数字）从 {@code boldFrom} 字号起仿粗体，宽度多 1 像素；次要文字（英文副名）始终常规。量宽、省略、右对齐都按同一规则，
   * 加粗不会把右对齐的文字吃进边界。
   */
  private final class Painter {
    private final Graphics2D g;
    private final PidsTheme theme;
    private final int boldFrom;

    private Painter(Graphics2D g, PidsTheme theme, int boldFrom) {
      this.g = g;
      this.theme = theme;
      this.boldFrom = boldFrom;
    }

    int color(Tone tone) {
      return switch (tone) {
        case NORMAL -> theme.text();
        case MUTED -> theme.muted();
        case AMBER -> theme.amber();
        case RED -> theme.red();
      };
    }

    /** 纯 ASCII 用拉丁版，其余用中文版。 */
    Font font(String text, int size) {
      return fonts.fontFor(text, size);
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
      return text == null || text.isEmpty() ? 0 : fonts.width(font(text, size), text) + extra(size);
    }

    /** 次要文字宽度。 */
    int regularWidth(String text, int size) {
      return fonts.width(font(text, size), text);
    }

    int baseline(String text, int size) {
      return fonts.baseline(font(text, size));
    }

    /** 主文字超宽时省略。 */
    String ellipsize(String text, int size, int maxWidth) {
      return fonts.ellipsize(font(text, size), text, maxWidth - extra(size));
    }

    /** 次要文字（英文）超宽时按词省略。 */
    String ellipsizeWords(String text, int size, int maxWidth) {
      return fonts.ellipsizeWords(font(text, size), text, maxWidth);
    }

    void fill(int x, int y, int width, int height, int rgb) {
      if (width <= 0 || height <= 0) {
        return;
      }
      g.setColor(new Color(rgb));
      g.fillRect(x, y, width, height);
    }

    /** 填充三角形（像素图标的箭头）。 */
    void triangle(int[] xs, int[] ys, int rgb) {
      g.setColor(new Color(rgb));
      g.fillPolygon(xs, ys, 3);
    }

    void outline(int x, int y, int width, int height, int rgb) {
      fill(x, y, width, STROKE, rgb);
      fill(x, y + height - STROKE, width, STROKE, rgb);
      fill(x, y, STROKE, height, rgb);
      fill(x + width - STROKE, y, STROKE, height, rgb);
    }

    void clip(int x, int y, int width, int height) {
      g.setClip(x, y, Math.max(0, width), Math.max(0, height));
    }

    void unclip() {
      g.setClip(null);
    }

    /** 主文字，以文本框顶边定位，返回宽度。 */
    int text(String text, int size, int x, int top, int rgb) {
      return text(text, size, x, top, rgb, false);
    }

    int text(String text, int size, int x, int top, int rgb, boolean struck) {
      return draw(text, size, x, top, rgb, struck, bold(size));
    }

    /** 次要文字，以文本框顶边定位，返回宽度。 */
    int regular(String text, int size, int x, int top, int rgb, boolean struck) {
      return draw(text, size, x, top, rgb, struck, false);
    }

    private int draw(String text, int size, int x, int top, int rgb, boolean struck, boolean bold) {
      if (text == null || text.isEmpty()) {
        return 0;
      }
      Font font = font(text, size);
      textAtBaseline(text, font, x, top + fonts.baseline(font), rgb, bold);
      int width = fonts.width(font, text) + (bold ? 1 : 0);
      if (struck) {
        fill(x, top + size / 2 - STROKE / 2, width, STROKE, rgb);
      }
      return width;
    }

    /** 按基线绘制；仿粗体时向右错 1 像素再画一次。 */
    void textAtBaseline(String text, Font font, int x, int baseline, int rgb, boolean bold) {
      if (text == null || text.isEmpty()) {
        return;
      }
      g.setFont(font);
      g.setColor(new Color(rgb));
      g.drawString(text, x, baseline);
      if (bold) {
        g.drawString(text, x + 1, baseline);
      }
    }

    void textCentered(String text, int size, int centerX, int top, int rgb) {
      text(text, size, centerX - width(text, size) / 2, top, rgb);
    }

    void textRight(String text, int size, int right, int top, int rgb) {
      text(text, size, right - width(text, size), top, rgb);
    }

    void regularRight(String text, int size, int right, int top, int rgb) {
      regular(text, size, right - regularWidth(text, size), top, rgb, false);
    }

    /** 中英文上下叠放，返回较宽一行的宽度。 */
    int stacked(Names names, TextStyle style, int x, int top, int primaryRgb, int secondaryRgb) {
      int width = text(names.primary(), style.size(), x, top, primaryRgb);
      if (style.hasSecondary() && !names.secondary().isEmpty()) {
        width =
            Math.max(
                width,
                regular(
                    names.secondary(),
                    style.secondarySize(),
                    x,
                    top + style.size() + style.gap(),
                    secondaryRgb,
                    false));
      }
      return width;
    }

    /** 在行内竖向居中叠放；中文、英文各自超宽时省略（英文按词）。返回较宽一行的宽度。 */
    int stackedIn(
        Names names,
        TextStyle style,
        int x,
        Cell cell,
        int maxWidth,
        int primaryRgb,
        int secondaryRgb,
        boolean struck) {
      boolean secondary = style.hasSecondary() && !names.secondary().isEmpty();
      int block = style.size() + (secondary ? style.gap() + style.secondarySize() : 0);
      int top = cell.top + (cell.height - block) / 2;
      int width =
          text(
              ellipsize(names.primary(), style.size(), maxWidth),
              style.size(),
              x,
              top,
              primaryRgb,
              struck);
      if (secondary) {
        width =
            Math.max(
                width,
                regular(
                    ellipsizeWords(names.secondary(), style.secondarySize(), maxWidth),
                    style.secondarySize(),
                    x,
                    top + style.size() + style.gap(),
                    secondaryRgb,
                    struck));
      }
      return width;
    }

    /** 在行内竖向居中叠放、靠右对齐。 */
    void stackedRight(
        Names names, TextStyle style, int right, Cell cell, int primaryRgb, int secondaryRgb) {
      boolean secondary = style.hasSecondary() && !names.secondary().isEmpty();
      int block = style.size() + (secondary ? style.gap() + style.secondarySize() : 0);
      int top = cell.top + (cell.height - block) / 2;
      textRight(names.primary(), style.size(), right, top, primaryRgb);
      if (secondary) {
        regularRight(
            names.secondary(),
            style.secondarySize(),
            right,
            top + style.size() + style.gap(),
            secondaryRgb);
      }
    }

    /** 中英文同行、基线对齐；放不下时先去掉英文，再省略中文。 */
    void inline(
        Names names,
        TextStyle style,
        int x,
        int top,
        int maxWidth,
        int primaryRgb,
        int secondaryRgb) {
      inline(names, style, x, top, maxWidth, primaryRgb, secondaryRgb, false);
    }

    void inline(
        Names names,
        TextStyle style,
        int x,
        int top,
        int maxWidth,
        int primaryRgb,
        int secondaryRgb,
        boolean struck) {
      int primaryWidth = width(names.primary(), style.size());
      boolean secondary = style.hasSecondary() && !names.secondary().isEmpty();
      if (secondary) {
        int total =
            primaryWidth + style.gap() + regularWidth(names.secondary(), style.secondarySize());
        if (total <= maxWidth) {
          text(names.primary(), style.size(), x, top, primaryRgb, struck);
          int baseline = top + baseline(names.primary(), style.size());
          int secondaryTop = baseline - baseline(names.secondary(), style.secondarySize());
          regular(
              names.secondary(),
              style.secondarySize(),
              x + primaryWidth + style.gap(),
              secondaryTop,
              secondaryRgb,
              struck);
          return;
        }
      }
      text(
          ellipsize(names.primary(), style.size(), maxWidth),
          style.size(),
          x,
          top,
          primaryRgb,
          struck);
    }
  }
}
