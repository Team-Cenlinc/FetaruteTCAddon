package org.fetarute.fetaruteTCAddon.display.pids.render;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout.TextStyle;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsStopListView;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsStopListView.Note;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsStopListView.Stop;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsStopListView.Train;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Arrival;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Badge;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Label;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Names;

/**
 * 2×1 停站屏主体（{@link PidsLayout.StopList}）的画法。
 *
 * <ul>
 *   <li>首行：色牌（与站台屏同样式）与靠右的多久到达（分钟数加“分 / min”，进站、停靠中写提示）
 *   <li>终点：中文仿粗体、英文次要色，各自放不下时省略
 *   <li>终点下面一行：方形标签（直通 / thru 或经由 / via，中英文上下叠放）加两行字；直通时线路名后加线路代码小牌
 *   <li>停站表：左侧一条竖线串起各站圆点，竖线按列车所属线路着色、换线站之后换成新线路的颜色。下一站小圆点用正文色，经由站琥珀色大圆点，
 *       换线站大圆点上半旧线路色、下半新线路色，终点空心大圆点；经由站与换线站在站名后加标签。站名右侧小方块是可换乘的其他线路。
 *       前面还有站（第二页起）时竖线从上沿接下来，后面还有站时竖线通到下沿并画向下的箭头
 *   <li>最底下一行：左侧写要提醒的状态（站台待定、站台变更、晚点），多于一页时右侧写页码“2/3”
 * </ul>
 *
 * <p>终点下面一行依次写：下一班之前本站台被取消的班次、直通、经由（{@link PidsStopListView#note()}）。
 */
final class PidsStopListPainter {

  /** 竖线左缘（相对组件左缘）与宽。 */
  private static final int LINE_X = 7;

  private static final int LINE_WIDTH = 2;

  /** 圆点中心的横坐标（相对组件左缘）。 */
  private static final int DOT_CENTER = 8;

  /** 站名左缘（相对组件左缘）。 */
  private static final int TEXT_X = 16;

  /** 下一站与普通站的小圆点、经由站换线站与终点的大圆点、终点空心的边长。 */
  private static final int SMALL_DOT = 6;

  private static final int BIG_DOT = 8;
  private static final int HOLE = 4;

  /** 可换乘线路小方块的边长与间距。 */
  private static final int CHIP = 5;

  private static final int CHIP_GAP = 2;

  /** 站名后标签：字号、色块左右内边距、与站名的间距。 */
  private static final int TAG_SIZE = 10;

  private static final int TAG_PAD = 1;
  private static final int TAG_GAP = 2;

  /** 终点下面一行：方形标签与右侧文字的间距，两行字的字号，线路代码小牌的内边距与间距。 */
  private static final int NOTE_TEXT_GAP = 4;

  private static final int NOTE_PRIMARY = 12;
  private static final int NOTE_SECONDARY = 10;
  private static final int NOTE_CHIP_PAD = 2;
  private static final int NOTE_CHIP_GAP = 3;

  /** 分钟数与“分 / min”的间距，也是分钟数与色牌至少留的空隙。 */
  private static final int MINUTES_GAP = 2;

  /** 最底下一行状态中英文之间、状态与页码之间的间距。 */
  private static final int FOOTER_GAP = 4;

  /** 向下箭头的宽与高。 */
  private static final int ARROW_WIDTH = 8;

  private static final int ARROW_HEIGHT = 5;

  private final PidsRenderer renderer;
  private final PidsRenderer.Painter p;
  private final PidsTheme theme;

  PidsStopListPainter(PidsRenderer renderer, PidsRenderer.Painter painter) {
    this.renderer = Objects.requireNonNull(renderer, "renderer");
    this.p = Objects.requireNonNull(painter, "painter");
    this.theme = painter.theme();
  }

  void draw(PidsLayout.StopList list, PidsStopListView view) {
    Optional<Train> train = view.train();
    if (train.isPresent()) {
      drawHeader(list, train.get().badge(), train.get().arrival(), view.labels().minutes());
      drawStacked(list, train.get().destination(), list.destination(), theme.text(), theme.muted());
    } else {
      // 用站名的字号：终点那么大的字写不下“暂无后续列车”
      drawStacked(list, view.labels().noMoreTrains(), list.stop(), theme.muted(), theme.muted());
    }
    view.note().ifPresent(note -> drawNote(list, note));
    boolean noted = view.note().isPresent();
    int pages = train.map(found -> list.pages(found.stops().size(), noted)).orElse(1);
    int page = Math.floorMod(view.page(), pages);
    train.ifPresent(found -> drawStops(list, found, noted, page, view.labels()));
    drawFooter(list, train.flatMap(Train::status), page, pages);
  }

  /** 首行：色牌，靠右的多久到达；分钟数伸到色牌上时改用小字号（与空位页同一画法）。 */
  private void drawHeader(PidsLayout.StopList list, Badge badge, Arrival arrival, Names minutes) {
    int top = list.y();
    int badgeX = list.x() + list.badgeX();
    renderer.drawBadgeAt(
        p, badgeX, top + (list.headerHeight() - list.badge().height()) / 2, list.badge(), badge);
    TextStyle unit = list.unit();
    renderer.drawStackedArrival(
        p,
        arrival,
        minutes,
        unit,
        list.minutesSize(),
        MINUTES_GAP,
        badgeX + list.badge().width() + MINUTES_GAP,
        list.x() + list.width() - list.inset(),
        top + (list.headerHeight() - unit.size() - unit.gap() - unit.secondarySize()) / 2);
  }

  /** 最底下一行：左侧要提醒的状态（站台待定、站台变更、晚点，按色调着色），右侧页码（多于一页时）。 */
  private void drawFooter(PidsLayout.StopList list, Optional<Label> status, int page, int pages) {
    int size = list.footerSize();
    int top = list.y() + list.height() - list.footerHeight() + (list.footerHeight() - size) / 2;
    int left = list.x() + list.inset();
    int right = list.x() + list.width() - list.inset();
    int statusRight = right;
    if (pages > 1) {
      String label = (page + 1) + "/" + pages;
      p.textRight(label, size, right, top, theme.text());
      statusRight -= p.width(label, size) + FOOTER_GAP;
    }
    if (status.isPresent()) {
      int color = p.color(status.get().tone());
      p.inline(
          status.get().text(),
          new TextStyle(size, size, FOOTER_GAP),
          left,
          top,
          statusRight - left,
          color,
          color);
    }
  }

  /** 终点一块：中英文上下叠放、竖向居中，各自放不下时省略（英文按词）。 */
  private void drawStacked(
      PidsLayout.StopList list, Names names, TextStyle style, int primaryRgb, int secondaryRgb) {
    int x = list.x() + list.inset();
    int width = list.width() - list.inset() * 2;
    int top =
        list.destinationTop()
            + (list.destinationHeight() - style.size() - style.gap() - style.secondarySize()) / 2;
    p.text(p.ellipsize(names.primary(), style.size(), width), style.size(), x, top, primaryRgb);
    p.regular(
        p.ellipsizeWords(names.secondary(), style.secondarySize(), width),
        style.secondarySize(),
        x,
        top + style.size() + style.gap(),
        secondaryRgb,
        false);
  }

  /** 终点下面一行：左侧中英叠放的标签（至少见方，英文长时加宽），右侧两行说明。 */
  private void drawNote(PidsLayout.StopList list, Note note) {
    int x = list.x() + list.inset();
    int top = list.noteTop();
    int box = list.noteHeight();
    int tagWidth =
        Math.max(
            box,
            Math.max(
                    p.width(note.tag().primary(), TAG_SIZE),
                    p.width(note.tag().secondary(), TAG_SIZE))
                + TAG_PAD * 2);
    int ink = PidsTheme.textOn(note.color());
    p.fill(x, top, tagWidth, box, note.color());
    int tagTop = top + (box - TAG_SIZE * 2 - 1) / 2;
    p.textCentered(note.tag().primary(), TAG_SIZE, x + tagWidth / 2, tagTop, ink);
    p.textCentered(note.tag().secondary(), TAG_SIZE, x + tagWidth / 2, tagTop + TAG_SIZE + 1, ink);

    int textX = x + tagWidth + NOTE_TEXT_GAP;
    int right = list.x() + list.width() - list.inset();
    int textTop = top + (box - NOTE_PRIMARY - NOTE_SECONDARY) / 2;
    int chipWidth =
        note.chip().map(code -> p.regularWidth(code, TAG_SIZE) + NOTE_CHIP_PAD * 2).orElse(0);
    int primaryRoom = right - textX - (chipWidth > 0 ? chipWidth + NOTE_CHIP_GAP : 0);
    int primaryWidth =
        p.text(
            p.ellipsize(note.primary(), NOTE_PRIMARY, primaryRoom),
            NOTE_PRIMARY,
            textX,
            textTop,
            theme.text());
    note.chip()
        .ifPresent(
            code -> {
              int chipX = textX + primaryWidth + NOTE_CHIP_GAP;
              p.fill(chipX, textTop + 1, chipWidth, TAG_SIZE, note.color());
              p.regular(code, TAG_SIZE, chipX + NOTE_CHIP_PAD, textTop + 1, ink, false);
            });
    p.regular(
        p.ellipsizeWords(note.secondary(), NOTE_SECONDARY, right - textX),
        NOTE_SECONDARY,
        textX,
        textTop + NOTE_PRIMARY,
        theme.muted(),
        false);
  }

  private void drawStops(
      PidsLayout.StopList list,
      Train train,
      boolean noted,
      int page,
      PidsStopListView.Labels labels) {
    List<Stop> stops = train.stops();
    if (stops.isEmpty()) {
      return;
    }
    int rows = list.rowsPerPage(noted);
    int from = page * rows;
    int to = Math.min(stops.size(), from + rows);
    List<Stop> shown = stops.subList(from, to);
    int top = list.listTop(noted);
    int bottom = list.listBottom();
    int lineX = list.x() + LINE_X;
    int centerOffset = list.stop().size() / 2;

    if (from > 0) {
      p.fill(lineX, top, LINE_WIDTH, centerOffset, shown.get(0).color());
    }
    for (int i = 0; i + 1 < shown.size(); i++) {
      int center = top + i * list.rowHeight() + centerOffset;
      p.fill(lineX, center, LINE_WIDTH, list.rowHeight(), shown.get(i).after());
    }
    if (to < stops.size()) {
      int last = top + (shown.size() - 1) * list.rowHeight() + centerOffset;
      int tail = shown.get(shown.size() - 1).after();
      p.fill(lineX, last, LINE_WIDTH, bottom - ARROW_HEIGHT - last, tail);
      int cx = list.x() + DOT_CENTER;
      p.triangle(
          new int[] {cx - ARROW_WIDTH / 2, cx + ARROW_WIDTH / 2, cx},
          new int[] {bottom - ARROW_HEIGHT, bottom - ARROW_HEIGHT, bottom},
          tail);
    }
    for (int i = 0; i < shown.size(); i++) {
      drawStop(list, shown.get(i), top + i * list.rowHeight(), labels);
    }
  }

  private void drawStop(
      PidsLayout.StopList list, Stop stop, int rowTop, PidsStopListView.Labels labels) {
    TextStyle style = list.stop();
    int cx = list.x() + DOT_CENTER;
    int cy = rowTop + style.size() / 2;
    drawDot(stop, cx, cy);

    int textX = list.x() + TEXT_X;
    int right = list.x() + list.width() - list.inset();
    Optional<Tag> tag =
        switch (stop.kind()) {
          case VIA -> Optional.of(new Tag(labels.via().primary(), theme.amber()));
          case CHANGE -> Optional.of(new Tag(labels.through().primary(), stop.after()));
          default -> Optional.empty();
        };
    int tagWidth = tag.map(t -> p.regularWidth(t.text(), TAG_SIZE) + TAG_PAD * 2).orElse(0);
    int tagRoom = tagWidth > 0 ? TAG_GAP + tagWidth : 0;

    // 站名与标签优先：放不下时先不画可换乘线路的小方块，再省略站名
    int chips = stop.transfers().size();
    int chipsWidth = chips == 0 ? 0 : chips * CHIP + (chips - 1) * CHIP_GAP;
    int chipX = right - chipsWidth;
    boolean showChips =
        chips > 0
            && textX + p.width(stop.names().primary(), style.size()) + tagRoom
                <= chipX - CHIP_GAP * 2;
    if (showChips) {
      for (int i = 0; i < chips; i++) {
        p.fill(chipX + i * (CHIP + CHIP_GAP), cy - CHIP / 2, CHIP, CHIP, stop.transfers().get(i));
      }
    }
    int nameRight = showChips ? chipX - CHIP_GAP * 2 : right;
    int nameRoom = nameRight - textX - tagRoom;
    int nameWidth =
        p.text(
            p.ellipsize(stop.names().primary(), style.size(), nameRoom),
            style.size(),
            textX,
            rowTop,
            theme.text());
    tag.ifPresent(
        found -> {
          int tagX = textX + nameWidth + TAG_GAP;
          p.fill(tagX, rowTop + 1, tagWidth, TAG_SIZE, found.color());
          p.regular(
              found.text(),
              TAG_SIZE,
              tagX + TAG_PAD,
              rowTop + 1,
              PidsTheme.textOn(found.color()),
              false);
        });
    if (style.hasSecondary()) {
      p.regular(
          p.ellipsizeWords(stop.names().secondary(), style.secondarySize(), right - textX),
          style.secondarySize(),
          textX,
          rowTop + style.size() + style.gap(),
          theme.muted(),
          false);
    }
  }

  private void drawDot(Stop stop, int cx, int cy) {
    switch (stop.kind()) {
      case NEXT -> octagon(cx, cy, SMALL_DOT, theme.text());
      case STOP -> octagon(cx, cy, SMALL_DOT, stop.color());
      case VIA -> octagon(cx, cy, BIG_DOT, theme.amber());
      case CHANGE -> {
        octagon(cx, cy, BIG_DOT, stop.after());
        p.clip(cx - BIG_DOT / 2, cy - BIG_DOT / 2, BIG_DOT, BIG_DOT / 2);
        octagon(cx, cy, BIG_DOT, stop.color());
        p.unclip();
      }
      case TERMINAL -> {
        octagon(cx, cy, BIG_DOT, stop.color());
        p.fill(cx - HOLE / 2, cy - HOLE / 2, HOLE, HOLE, theme.background());
      }
    }
  }

  /** 中心在 ({@code cx}, {@code cy}) 的像素圆点：小圆点切去 1 像素角，大圆点切去 2 像素角。 */
  private void octagon(int cx, int cy, int size, int color) {
    int x = cx - size / 2;
    int y = cy - size / 2;
    int corner = size >= BIG_DOT ? 2 : 1;
    p.fill(x + corner, y, size - corner * 2, size, color);
    p.fill(x, y + corner, size, size - corner * 2, color);
    if (corner > 1) {
      p.fill(x + 1, y + 1, size - 2, size - 2, color);
    }
  }

  /** 站名后的标签。 */
  private record Tag(String text, int color) {}
}
