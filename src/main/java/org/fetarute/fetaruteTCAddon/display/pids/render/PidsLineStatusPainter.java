package org.fetarute.fetaruteTCAddon.display.pids.render;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout.StatusRow;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout.TextStyle;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsLineStatusView;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsLineStatusView.Row;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.LineChip;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Names;

/**
 * 线路运行状况屏主体（{@link PidsLayout.LineStatus}）的画法。
 *
 * <ul>
 *   <li>页标题中英文同行；其下运营商名（次要色），多于一页时右侧写页码“1/2”。按运营商轮播时右侧改为一排运营商代码标签（按轮播顺序），
 *       正在显示的那家反白，这一家多于一页时页码写进它的标签里；标签占到这一行一半以上时只画正在显示的那家，它一个也放不下时不画标签、照常写页码
 *   <li>表头：线路、运行状况、说明，各列中英文同行
 *   <li>每条线路一行，面板色铺底：线路色牌写线路代码；线路名中英文上下叠放，中文放不下时改用小一档字号；
 *       状况中英文上下叠放——延误与取消画琥珀色块、严重延误与停运画红色块（字色按底色亮度取黑白），运行正常写绿字，其余灰字；说明中英文上下叠放
 *   <li>没有线路时第一行写“暂无线路信息”
 * </ul>
 */
final class PidsLineStatusPainter {

  /** 线路名与状况列、状况文字与说明列之间至少留的空隙。 */
  private static final int COLUMN_GAP = 6;

  /** 状况文字距色块右缘至少留的空隙。 */
  private static final int STATUS_PAD = 2;

  /** 色牌上线路代码左右至少留的空隙。 */
  private static final int BADGE_PAD = 3;

  /** 线路代码在原字号放不下时改用的字号。 */
  private static final int BADGE_SMALL = 12;

  /** 运营商名与页码之间至少留的空隙。 */
  private static final int PAGE_GAP = 8;

  /** 轮播标签：文字上下、左右各留的空隙，标签之间的空隙，标签里代码与页码之间的空隙。 */
  private static final int TAB_PAD_Y = 3;

  private static final int TAB_PAD_X = 5;

  private static final int TAB_GAP = 4;

  private static final int TAB_PAGE_GAP = 4;

  private final PidsRenderer.Painter p;
  private final PidsTheme theme;

  PidsLineStatusPainter(PidsRenderer.Painter painter) {
    this.p = Objects.requireNonNull(painter, "painter");
    this.theme = painter.theme();
  }

  void draw(PidsLayout.LineStatus widget, PidsLineStatusView view) {
    int left = widget.x() + widget.inset();
    int right = widget.x() + widget.width() - widget.inset();
    p.inline(
        view.title(), widget.title(), left, widget.y(), right - left, theme.text(), theme.muted());
    drawOperator(widget, view, left, right);
    StatusRow row = view.roomy() ? widget.roomy() : widget.compact();
    drawHeader(widget, row, view.labels());
    if (view.rows().isEmpty()) {
      drawEmpty(widget, row, view.labels().empty());
      return;
    }
    int top = widget.rowsTop();
    for (Row line : view.rows()) {
      drawRow(widget, row, top, line);
      top += row.height() + row.gap();
    }
  }

  /** 运营商名；多于一页时右侧写页码，按运营商轮播时右侧画轮播标签，运营商名让出它们的位置。 */
  private void drawOperator(
      PidsLayout.LineStatus widget, PidsLineStatusView view, int left, int right) {
    int top = widget.y() + widget.operatorY();
    int size = widget.operator().size();
    int end = right;
    OptionalInt tags =
        view.rotating() ? drawRotation(view, left, right, top, size) : OptionalInt.empty();
    if (tags.isPresent()) {
      end = tags.getAsInt() - PAGE_GAP;
    } else if (view.pages() > 1) {
      String page = pageText(view);
      p.textRight(page, size, end, top, theme.text());
      end -= p.width(page, size) + PAGE_GAP;
    }
    p.inline(
        view.operator(),
        widget.operator(),
        left,
        top,
        Math.max(0, end - left),
        theme.muted(),
        theme.muted());
  }

  /**
   * 轮播标签靠右排开：正在显示的那家反白（多于一页时代码后写页码），其余描边灰字。
   *
   * @return 标签的左缘；正在显示的那家一个标签也放不下这一行时不画，为空（照不轮播写页码）
   */
  private OptionalInt drawRotation(
      PidsLineStatusView view, int left, int right, int top, int size) {
    List<String> codes = view.rotation();
    int current = view.rotationIndex();
    int[] widths = new int[codes.size()];
    int total = -TAB_GAP;
    for (int i = 0; i < codes.size(); i++) {
      widths[i] = tabWidth(view, codes.get(i), i == current, size);
      total += widths[i] + TAB_GAP;
    }
    int from = 0;
    int to = codes.size();
    if (widths[current] > right - left) {
      return OptionalInt.empty();
    }
    if (total > (right - left) / 2) {
      from = current;
      to = current + 1;
      total = widths[current];
    }
    int x = right - total;
    int start = x;
    int tabTop = top - TAB_PAD_Y;
    int tabHeight = size + TAB_PAD_Y * 2;
    for (int i = from; i < to; i++) {
      String code = codes.get(i);
      if (i == current) {
        p.fill(x, tabTop, widths[i], tabHeight, theme.text());
        int ink = PidsTheme.textOn(theme.text());
        int textX = x + TAB_PAD_X;
        textX += p.text(code, size, textX, top, ink);
        if (view.pages() > 1) {
          p.regular(pageText(view), size, textX + TAB_PAGE_GAP, top, ink, false);
        }
      } else {
        p.outline(x, tabTop, widths[i], tabHeight, theme.muted());
        p.regular(code, size, x + TAB_PAD_X, top, theme.muted(), false);
      }
      x += widths[i] + TAB_GAP;
    }
    return OptionalInt.of(start);
  }

  private int tabWidth(PidsLineStatusView view, String code, boolean current, int size) {
    if (!current) {
      return p.regularWidth(code, size) + TAB_PAD_X * 2;
    }
    int width = p.width(code, size) + TAB_PAD_X * 2;
    return view.pages() > 1 ? width + TAB_PAGE_GAP + p.regularWidth(pageText(view), size) : width;
  }

  private static String pageText(PidsLineStatusView view) {
    return (view.page() + 1) + "/" + view.pages();
  }

  /** 表头：状况一列与状况文字对齐（色块内缩进）。 */
  private void drawHeader(
      PidsLayout.LineStatus widget, StatusRow row, PidsLineStatusView.Labels labels) {
    int top = widget.y() + widget.headerY();
    int x = widget.x();
    int statusX = widget.statusX() + row.statusInset();
    header(labels.line(), widget, x + widget.inset(), top, widget.statusX() - widget.inset());
    header(labels.status(), widget, x + statusX, top, widget.detailX() - statusX);
    header(
        labels.details(),
        widget,
        x + widget.detailX(),
        top,
        widget.width() - widget.detailX() - widget.inset());
  }

  private void header(Names names, PidsLayout.LineStatus widget, int x, int top, int maxWidth) {
    p.inline(names, widget.header(), x, top, maxWidth, theme.muted(), theme.muted());
  }

  private void drawEmpty(PidsLayout.LineStatus widget, StatusRow row, Names empty) {
    int top = widget.rowsTop();
    p.fill(widget.x(), top, widget.width(), row.height(), theme.panel());
    stacked(
        empty,
        row.status(),
        row.status().size(),
        widget.x() + widget.inset(),
        top,
        row.height(),
        widget.width() - widget.inset() * 2,
        theme.muted(),
        theme.muted());
  }

  private void drawRow(PidsLayout.LineStatus widget, StatusRow row, int top, Row line) {
    int x = widget.x();
    p.fill(x, top, widget.width(), row.height(), theme.panel());
    drawBadge(row, x + widget.inset(), top + (row.height() - row.badgeHeight()) / 2, line.line());
    drawName(
        row,
        x + widget.nameX(),
        top,
        widget.statusX() - widget.nameX() - COLUMN_GAP,
        line.line().name());
    drawStatus(widget, row, top, line);
    line.detail()
        .ifPresent(
            detail ->
                stacked(
                    detail,
                    row.detail(),
                    row.detail().size(),
                    x + widget.detailX(),
                    top,
                    row.height(),
                    widget.width() - widget.detailX() - widget.inset(),
                    theme.text(),
                    theme.muted()));
  }

  /** 线路色牌：代码居中；原字号放不下时改用 12 号，再放不下省略。 */
  private void drawBadge(StatusRow row, int left, int top, LineChip chip) {
    p.fill(left, top, row.badgeWidth(), row.badgeHeight(), chip.color());
    int room = row.badgeWidth() - BADGE_PAD * 2;
    int size =
        p.width(chip.code(), row.badgeSize()) <= room
            ? row.badgeSize()
            : Math.min(BADGE_SMALL, row.badgeSize());
    p.textCentered(
        p.ellipsize(chip.code(), size, room),
        size,
        left + row.badgeWidth() / 2,
        top + (row.badgeHeight() - size) / 2,
        PidsTheme.textOn(chip.color()));
  }

  /** 线路名：中文放不下时改用小一档字号（再放不下省略）。 */
  private void drawName(StatusRow row, int x, int top, int maxWidth, Names names) {
    int size =
        row.nameFallback() > 0 && p.width(names.primary(), row.name().size()) > maxWidth
            ? row.nameFallback()
            : row.name().size();
    stacked(names, row.name(), size, x, top, row.height(), maxWidth, theme.text(), theme.muted());
  }

  /** 状况：琥珀色、红色两种画色块反白；运行正常绿字，其余灰字。 */
  private void drawStatus(PidsLayout.LineStatus widget, StatusRow row, int rowTop, Row line) {
    int left = widget.x() + widget.statusX();
    int textX = left + row.statusInset();
    OptionalInt block =
        switch (line.style()) {
          case AMBER -> OptionalInt.of(theme.amber());
          case RED -> OptionalInt.of(theme.red());
          case GOOD, MUTED -> OptionalInt.empty();
        };
    if (block.isPresent()) {
      p.fill(
          left,
          rowTop + (row.height() - row.statusHeight()) / 2,
          row.statusWidth(),
          row.statusHeight(),
          block.getAsInt());
      int ink = PidsTheme.textOn(block.getAsInt());
      stacked(
          line.status(),
          row.status(),
          row.status().size(),
          textX,
          rowTop,
          row.height(),
          row.statusWidth() - row.statusInset() - STATUS_PAD,
          ink,
          ink);
      return;
    }
    stacked(
        line.status(),
        row.status(),
        row.status().size(),
        textX,
        rowTop,
        row.height(),
        widget.detailX() - widget.statusX() - row.statusInset() - COLUMN_GAP,
        line.style() == PidsLineStatusView.Style.GOOD ? theme.green() : theme.muted(),
        theme.muted());
  }

  /** 中英文上下叠放、在行内竖向居中；各自超宽时省略（英文按词）。 */
  private void stacked(
      Names names,
      TextStyle style,
      int size,
      int x,
      int rowTop,
      int rowHeight,
      int maxWidth,
      int primaryRgb,
      int secondaryRgb) {
    boolean secondary = style.hasSecondary() && !names.secondary().isBlank();
    int block = size + (secondary ? style.gap() + style.secondarySize() : 0);
    int top = rowTop + (rowHeight - block) / 2;
    p.text(p.ellipsize(names.primary(), size, maxWidth), size, x, top, primaryRgb);
    if (secondary) {
      p.regular(
          p.ellipsizeWords(names.secondary(), style.secondarySize(), maxWidth),
          style.secondarySize(),
          x,
          top + size + style.gap(),
          secondaryRgb,
          false);
    }
  }
}
