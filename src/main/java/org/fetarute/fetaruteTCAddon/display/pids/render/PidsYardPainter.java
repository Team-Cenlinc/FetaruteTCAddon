package org.fetarute.fetaruteTCAddon.display.pids.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout.TextStyle;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Names;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsYardView;
import org.fetarute.fetaruteTCAddon.display.pids.yard.YardGeometry;
import org.fetarute.fetaruteTCAddon.display.pids.yard.YardPicture;

/**
 * 画站场图与图例。
 *
 * <p>先画全部轨道（空闲色），再按占用盖上进路锁闭与保护占用，然后是股道号、去向、车身与车次窗。车身比轨道粗一档；车次窗放在车头上方， 和已经放好的车次窗重叠时改放下方或往右挪。
 */
final class PidsYardPainter {

  /** 轨道线宽。 */
  static final int TRACK = YardGeometry.TRACK_LINE;

  /** 股道号里代表股道的占位符。 */
  private static final String TRACK_PLACEHOLDER = "<track>";

  /** 车身线宽。 */
  static final int BODY = 3;

  /** 车次窗文字最宽多少像素，超出省略。 */
  private static final int LABEL_MAX = 60;

  /** 深色主题的保护占用（暗红）。 */
  static final int HELD_DARK = 0x993333;

  /** 浅色主题的保护占用（粉红，与红色的车身分得开）。 */
  static final int HELD_LIGHT = 0xF27FA5;

  /** 车次窗左边色条的宽度。 */
  private static final int STRIP = 4;

  private final PidsRenderer.Painter p;

  PidsYardPainter(PidsRenderer.Painter painter) {
    this.p = painter;
  }

  /** 保护占用的颜色。 */
  static int heldColor(PidsTheme theme) {
    return PidsTheme.LIGHT.equals(theme) ? HELD_LIGHT : HELD_DARK;
  }

  void draw(PidsLayout.Yard yard, PidsYardView view) {
    PidsTheme theme = p.theme();
    p.fill(yard.x(), yard.y(), yard.width(), 1, theme.inverseMuted());
    p.fill(yard.x(), yard.y() + yard.height() - 1, yard.width(), 1, theme.inverseMuted());
    Optional<YardGeometry> geometry = view.picture().geometry();
    if (geometry.isEmpty()) {
      view.message().ifPresent(message -> drawMessage(yard, message));
      return;
    }
    p.clip(yard.x(), yard.y() + 1, yard.width(), yard.height() - 2);
    try {
      for (YardGeometry.Platform platform : geometry.get().platforms()) {
        p.fill(
            platform.left(),
            platform.top(),
            platform.right() - platform.left(),
            platform.bottom() - platform.top(),
            theme.panel());
      }
      drawTracks(geometry.get(), view.picture().marks());
      List<int[]> texts = drawPlatformLabels(yard, geometry.get(), view.track());
      drawLabels(yard, geometry.get(), texts);
      List<int[]> placed = new ArrayList<>();
      for (YardPicture.TrainMark train : view.picture().trains()) {
        drawBody(train);
      }
      for (YardPicture.TrainMark train : view.picture().trains()) {
        drawDescriber(yard, train, placed);
      }
      view.note()
          .ifPresent(
              note ->
                  p.inline(
                      note,
                      new TextStyle(yard.labelSize(), yard.labelSize(), 6),
                      yard.trackLeft(),
                      yard.y() + yard.height() - 2 - yard.labelSize(),
                      yard.trackWidth(),
                      theme.muted(),
                      theme.muted()));
    } finally {
      p.unclip();
    }
  }

  private void drawTracks(YardGeometry geometry, List<YardPicture.EdgeMark> marks) {
    PidsTheme theme = p.theme();
    for (YardGeometry.Path path : geometry.paths()) {
      drawPath(path, TRACK, theme.outline());
    }
    Map<EdgeId, YardPicture.Mark> marked = new HashMap<>();
    marks.forEach(mark -> marked.put(mark.edge(), mark.mark()));
    for (YardGeometry.Path path : geometry.paths()) {
      YardPicture.Mark mark = marked.get(path.edge());
      if (mark != null) {
        drawPath(path, TRACK, mark == YardPicture.Mark.ROUTE ? theme.text() : heldColor(theme));
      }
    }
  }

  private void drawPath(YardGeometry.Path path, int thickness, int rgb) {
    for (YardGeometry.Segment s : path.segments()) {
      p.line(s.x0(), s.y0(), s.x1(), s.y1(), thickness, rgb);
    }
  }

  /**
   * 股道号写在站台块里、靠左：岛式站台上面那条股道写在块的上沿、下面那条写在下沿；块太薄放不下两行时并排写在一行，侧式站台竖向居中。
   * 一张图里有几个车站时，站名写在第一个股道号前面（写在块外会压到股道线）。
   *
   * @return 写下的文字所占的矩形 {左, 上, 右, 下}，去向标签据此避让
   */
  private List<int[]> drawPlatformLabels(PidsLayout.Yard yard, YardGeometry geometry, Names track) {
    int size = yard.labelSize();
    int muted = p.theme().muted();
    List<int[]> drawn = new ArrayList<>();
    for (YardGeometry.Platform platform : geometry.platforms()) {
      List<String> texts = new ArrayList<>();
      for (String number : platform.tracks()) {
        String text = track.primary().replace(TRACK_PLACEHOLDER, number);
        texts.add(
            texts.isEmpty() && platform.station().isPresent()
                ? platform.station().get() + " " + text
                : text);
      }
      int left = platform.left() + 4;
      int room = Math.max(size, platform.right() - left - 2);
      int height = platform.bottom() - platform.top();
      if (texts.size() == 2 && height >= 2 * size + 4) {
        drawn.add(platformText(texts.get(0), size, left, platform.top() + 2, room, muted));
        drawn.add(
            platformText(texts.get(1), size, left, platform.bottom() - size - 2, room, muted));
        continue;
      }
      int top = platform.top() + (height - size) / 2;
      int x = left;
      for (String text : texts) {
        int[] box = platformText(text, size, x, top, Math.max(size, left + room - x), muted);
        drawn.add(box);
        x = box[2] + 8;
      }
    }
    return drawn;
  }

  private int[] platformText(String text, int size, int x, int top, int room, int rgb) {
    String shown = p.ellipsize(text, size, room);
    int width = p.text(shown, size, x, top, rgb);
    return new int[] {x, top, x + width, top + size};
  }

  /**
   * 去向写在出口末端下方，放不下（碰到下边缘、压到股道号或别的去向）时改写在上方；上下都压到股道号或别的去向时不写。 文字下铺底色，轨道穿过时也看得清。
   *
   * @param taken 已写下的文字所占的矩形
   */
  private void drawLabels(PidsLayout.Yard yard, YardGeometry geometry, List<int[]> taken) {
    int size = yard.labelSize();
    List<int[]> occupied = new ArrayList<>(taken);
    for (YardGeometry.Label exit : geometry.exits()) {
      String text = p.ellipsize(exit.text(), size, yard.trackWidth() / 3);
      int width = p.width(text, size);
      int x = exit.alignRight() ? exit.at().x() - width : exit.at().x();
      x = Math.max(yard.x() + 2, Math.min(x, yard.x() + yard.width() - 2 - width));
      int bottomEdge = yard.y() + yard.height() - 2;
      int below = exit.at().y() + 4;
      int above = exit.at().y() - size - 4;
      for (int top : List.of(below, above)) {
        int[] box = {x - 1, top - 1, x + width + 1, top + size + 1};
        if (top >= yard.y() + 1
            && top + size <= bottomEdge
            && occupied.stream().noneMatch(other -> intersects(box, other))) {
          label(text, size, x, top);
          occupied.add(box);
          break;
        }
      }
    }
  }

  private static boolean intersects(int[] a, int[] b) {
    return a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3];
  }

  private void label(String text, int size, int x, int top) {
    PidsTheme theme = p.theme();
    p.fill(x - 1, top - 1, p.width(text, size) + 2, size + 2, theme.background());
    p.text(text, size, x, top, theme.muted());
  }

  /** 每段车厢依次连线；只有一节的段画一个点。 */
  private void drawBody(YardPicture.TrainMark train) {
    int red = p.theme().red();
    for (List<YardGeometry.Point> run : train.runs()) {
      YardGeometry.Point previous = run.get(0);
      p.line(previous.x(), previous.y(), previous.x(), previous.y(), BODY, red);
      for (YardGeometry.Point car : run.subList(1, run.size())) {
        p.line(previous.x(), previous.y(), car.x(), car.y(), BODY, red);
        previous = car;
      }
    }
  }

  /** 车次窗：车头上方，与已放好的重叠时改放下方，仍重叠就往右挪。 */
  private void drawDescriber(
      PidsLayout.Yard yard, YardPicture.TrainMark train, List<int[]> placed) {
    PidsTheme theme = p.theme();
    int size = yard.labelSize();
    String text = p.ellipsize(train.label(), size, LABEL_MAX);
    int width = STRIP + 2 + p.width(text, size) + 3;
    int height = size + 3;
    YardGeometry.Point head = train.anchor();
    int left = yard.x() + 1;
    int right = yard.x() + yard.width() - 1 - width;
    int x = Math.max(left, Math.min(head.x() - width / 2, right));
    int above = head.y() - height - 4;
    int below = head.y() + 5;
    int y = above < yard.y() + 2 ? below : above;
    if (overlaps(placed, x, y, width, height)) {
      y = y == above ? below : above;
    }
    for (int tries = 0; tries < 6 && overlaps(placed, x, y, width, height); tries++) {
      x = Math.min(right, x + width / 2 + 2);
    }
    placed.add(new int[] {x, y, width, height});
    p.fill(x, y, width, height, theme.panel());
    p.fill(x, y, STRIP, height, train.lineColor().orElse(theme.outline()));
    int color =
        switch (train.lateness()) {
          case ON_TIME -> theme.text();
          case LATE -> theme.amber();
          case SEVERELY_LATE -> theme.red();
        };
    p.text(text, size, x + STRIP + 2, y + 2, color);
  }

  private static boolean overlaps(List<int[]> placed, int x, int y, int width, int height) {
    for (int[] box : placed) {
      if (x < box[0] + box[2] && box[0] < x + width && y < box[1] + box[3] && box[1] < y + height) {
        return true;
      }
    }
    return false;
  }

  private void drawMessage(PidsLayout.Yard yard, Names message) {
    PidsTheme theme = p.theme();
    int centerX = yard.x() + yard.width() / 2;
    int top = yard.y() + yard.height() / 2 - 14;
    p.textCentered(message.primary(), 12, centerX, top, theme.text());
    if (!message.secondary().isEmpty()) {
      int width = p.regularWidth(message.secondary(), 10);
      p.regular(message.secondary(), 10, centerX - width / 2, top + 16, theme.muted(), false);
    }
  }

  /** 图例：两行样例（空闲、进路锁闭、保护占用 / 车占用、车次窗、站台），第三行是车次窗颜色说明。 */
  void drawLegend(PidsLayout.YardLegend legend, PidsYardView view) {
    PidsTheme theme = p.theme();
    p.fill(legend.x(), legend.y(), legend.width(), 1, theme.inverseMuted());
    TextStyle style = legend.label();
    int rowHeight = style.size() + 14;
    int left = legend.x() + 12;
    int column = (legend.width() - 24) / 3;
    int top = legend.y() + 10;
    PidsYardView.Legend text = view.legend();
    sampleLine(left, top, style, TRACK, theme.outline(), text.free(), column);
    sampleLine(left + column, top, style, TRACK, theme.text(), text.route(), column);
    sampleLine(left + 2 * column, top, style, TRACK, heldColor(theme), text.held(), column);
    sampleLine(left, top + rowHeight, style, BODY, theme.red(), text.occupied(), column);
    sampleDescriber(left + column, top + rowHeight, style, text.train(), column);
    int platformTop = top + rowHeight + (style.size() - 10) / 2;
    p.fill(left + 2 * column, platformTop, 28, 10, theme.panel());
    p.inline(
        text.platform(),
        style,
        left + 2 * column + 36,
        top + rowHeight,
        column - 40,
        theme.text(),
        theme.muted());
    p.inline(
        text.note(),
        new TextStyle(legend.noteSize(), legend.noteSize(), 6),
        left,
        top + 2 * rowHeight + 2,
        legend.width() - 24,
        theme.muted(),
        theme.muted());
  }

  private void sampleLine(
      int x, int top, TextStyle style, int thickness, int rgb, Names label, int column) {
    int middle = top + style.size() / 2;
    p.line(x, middle, x + 27, middle, thickness, rgb);
    p.inline(label, style, x + 36, top, column - 40, p.theme().text(), p.theme().muted());
  }

  private void sampleDescriber(int x, int top, TextStyle style, Names label, int column) {
    PidsTheme theme = p.theme();
    int size = 10;
    String sample = "1234";
    int width = STRIP + 2 + p.width(sample, size) + 3;
    int y = top + (style.size() - size - 3) / 2;
    p.fill(x, y, width, size + 3, theme.panel());
    p.fill(x, y, STRIP, size + 3, theme.outline());
    p.text(sample, size, x + STRIP + 2, y + 2, theme.text());
    p.inline(label, style, x + 36, top, column - 40, theme.text(), theme.muted());
  }
}
