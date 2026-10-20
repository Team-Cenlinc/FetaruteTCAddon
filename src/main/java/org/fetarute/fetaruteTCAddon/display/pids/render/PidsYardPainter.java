package org.fetarute.fetaruteTCAddon.display.pids.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

  private static final String DEPOT_PLACEHOLDER = "<depot>";

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
      drawDepotPanels(geometry.get());
      drawTracks(geometry.get(), view.picture().marks());
      List<int[]> texts =
          new ArrayList<>(drawDepots(yard, geometry.get(), view.depot(), view.depotTrack()));
      texts.addAll(drawPlatformLabels(yard, geometry.get(), view.track()));
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

  /** 收成面板的车库先铺底色，库线短条随后照常画在上面。 */
  private void drawDepotPanels(YardGeometry geometry) {
    for (YardGeometry.Depot depot : geometry.depots()) {
      if (depot.panel()) {
        p.fill(
            depot.left(),
            depot.top(),
            depot.right() - depot.left(),
            depot.bottom() - depot.top(),
            p.theme().panel());
      }
    }
  }

  /**
   * 车库的字与框。照画库线的车库：库线尽头画车挡、写库号，外面围一圈虚线框（留出库号与库名的位置），框里左上写库名。 收成面板的车库：库名写在第一行，库号写在各条短条左边。
   *
   * @return 写下的文字所占的矩形 {左, 上, 右, 下}，去向标签据此避让
   */
  private List<int[]> drawDepots(
      PidsLayout.Yard yard, YardGeometry geometry, Names depotName, Names depotTrack) {
    int size = yard.labelSize();
    int muted = p.theme().muted();
    List<int[]> drawn = new ArrayList<>();
    for (YardGeometry.Depot depot : geometry.depots()) {
      String name =
          depot.name().isEmpty()
              ? ""
              : depotName.primary().replace(DEPOT_PLACEHOLDER, depot.name());
      if (depot.panel()) {
        int room = depot.right() - depot.left() - 8;
        if (!name.isEmpty()) {
          drawn.add(
              platformText(List.of(name), size, depot.left() + 4, depot.top() + 2, room, muted));
        }
        for (YardGeometry.DepotTrack track : depot.tracks()) {
          String label = depotTrack.primary().replace(TRACK_PLACEHOLDER, track.number());
          int x = depot.left() + 4;
          drawn.add(
              platformText(
                  List.of(label),
                  size,
                  x,
                  track.end().y() - size / 2,
                  Math.max(size, track.end().x() - x - 4),
                  muted));
        }
        continue;
      }
      int left = depot.left() - 6;
      int right = depot.right() + 6;
      int top = depot.top() - size - 8;
      int bottom = depot.bottom() + 6;
      for (YardGeometry.DepotTrack track : depot.tracks()) {
        YardGeometry.Point end = track.end();
        if (track.buffer()) {
          p.fill(
              track.outward() > 0 ? end.x() : end.x() - 1,
              end.y() - 3,
              2,
              YardGeometry.TRACK_LINE + 6,
              p.theme().outline());
        }
        String label = depotTrack.primary().replace(TRACK_PLACEHOLDER, track.number());
        int width = p.width(label, size);
        int x = track.outward() > 0 ? end.x() + 5 : end.x() - 5 - width;
        // 写在尽头外侧，压到站台块或别的字时改写在尽头上方，仍压到就不写
        int[] box = null;
        for (int y :
            new int[] {end.y() + YardGeometry.TRACK_LINE / 2 - size / 2, end.y() - size - 3}) {
          int[] candidate = {x, y, x + width, y + size};
          if (!blocked(candidate, geometry, drawn)) {
            box = candidate;
            break;
          }
        }
        if (box == null) {
          continue;
        }
        p.text(label, size, box[0], box[1], muted);
        drawn.add(box);
        left = Math.min(left, box[0] - 4);
        right = Math.max(right, box[2] + 4);
        top = Math.min(top, box[1] - size - 6);
      }
      if (!name.isEmpty()) {
        int width = p.width(name, size);
        right = Math.max(right, left + width + 8);
        p.text(name, size, left + 4, top + 2, muted);
        drawn.add(new int[] {left + 4, top + 2, left + 4 + width, top + 2 + size});
      }
      dashedBox(left, top, right, bottom, p.theme().inverseMuted());
    }
    return drawn;
  }

  /** 文字的矩形压到站台块或已写下的字。 */
  private static boolean blocked(int[] box, YardGeometry geometry, List<int[]> drawn) {
    for (YardGeometry.Platform platform : geometry.platforms()) {
      if (intersects(
          box, new int[] {platform.left(), platform.top(), platform.right(), platform.bottom()})) {
        return true;
      }
    }
    return drawn.stream().anyMatch(other -> intersects(box, other));
  }

  /** 1 像素的虚线框：画 4 像素、空 3 像素。 */
  private void dashedBox(int left, int top, int right, int bottom, int rgb) {
    for (int x = left; x <= right; x += 7) {
      int run = Math.min(4, right - x + 1);
      p.fill(x, top, run, 1, rgb);
      p.fill(x, bottom, run, 1, rgb);
    }
    for (int y = top; y <= bottom; y += 7) {
      int run = Math.min(4, bottom - y + 1);
      p.fill(left, y, 1, run, rgb);
      p.fill(right, y, 1, run, rgb);
    }
  }

  private void drawPath(YardGeometry.Path path, int thickness, int rgb) {
    for (YardGeometry.Segment s : path.segments()) {
      p.line(s.x0(), s.y0(), s.x1(), s.y1(), thickness, rgb);
    }
  }

  /**
   * 股道号写在站台块里、靠左：岛式站台上面那条股道写在块的上沿、下面那条写在下沿；块太薄放不下两行时并排写在一行，侧式站台竖向居中。
   * 一张图里有几个车站时，站名写在第一个股道号前面（写在块外会压到股道线）；块窄放不下时依次改写“站码 1 道”“站码 1”，股道号也只写数字， 都放不下才截断。
   * 同一车站只要有一块站台写不下站名，这个车站的站台就都改写站码，免得一站两种写法。
   *
   * @return 写下的文字所占的矩形 {左, 上, 右, 下}，去向标签据此避让
   */
  private List<int[]> drawPlatformLabels(PidsLayout.Yard yard, YardGeometry geometry, Names track) {
    int size = yard.labelSize();
    int muted = p.theme().muted();
    Set<String> coded = new HashSet<>();
    for (YardGeometry.Platform platform : geometry.platforms()) {
      if (platform.station().isPresent() && platform.code().isPresent()) {
        String full = track.primary().replace(TRACK_PLACEHOLDER, platform.tracks().get(0));
        if (p.width(platform.station().get() + " " + full, size) > textRoom(platform, size)) {
          coded.add(platform.station().get());
        }
      }
    }
    List<int[]> drawn = new ArrayList<>();
    for (YardGeometry.Platform platform : geometry.platforms()) {
      List<List<String>> texts = new ArrayList<>();
      for (String number : platform.tracks()) {
        String full = track.primary().replace(TRACK_PLACEHOLDER, number);
        List<String> candidates = new ArrayList<>();
        if (texts.isEmpty() && platform.station().isPresent()) {
          if (!coded.contains(platform.station().get())) {
            candidates.add(platform.station().get() + " " + full);
          }
          platform.code().ifPresent(code -> candidates.add(code + " " + full));
          platform.code().ifPresent(code -> candidates.add(code + " " + number));
        } else {
          candidates.add(full);
          candidates.add(number);
        }
        texts.add(candidates);
      }
      int left = platform.left() + 4;
      int room = textRoom(platform, size);
      int height = platform.bottom() - platform.top();
      if (texts.size() == 2 && height >= 2 * size + 4) {
        drawn.add(platformText(texts.get(0), size, left, platform.top() + 2, room, muted));
        drawn.add(
            platformText(texts.get(1), size, left, platform.bottom() - size - 2, room, muted));
        continue;
      }
      int top = platform.top() + (height - size) / 2;
      int x = left;
      for (List<String> candidates : texts) {
        int[] box = platformText(candidates, size, x, top, Math.max(size, left + room - x), muted);
        drawn.add(box);
        x = box[2] + 8;
      }
    }
    return drawn;
  }

  /** 站台块里能写字的宽度（左边留 4 像素、右边留 2 像素）。 */
  private static int textRoom(YardGeometry.Platform platform, int size) {
    return Math.max(size, platform.right() - platform.left() - 6);
  }

  /** 依次试写法，写第一个放得下的；都放不下时截断最后一个。 */
  private int[] platformText(List<String> candidates, int size, int x, int top, int room, int rgb) {
    String shown =
        candidates.stream()
            .filter(text -> p.width(text, size) <= room)
            .findFirst()
            .orElseGet(() -> p.ellipsize(candidates.get(candidates.size() - 1), size, room));
    int width = p.text(shown, size, x, top, rgb);
    return new int[] {x, top, x + width, top + size};
  }

  /**
   * 去向写在出口末端下方，放不下（碰到下边缘、压到股道线、股道号或别的去向）时改写在上方；站名上下都放不下时改写站码，仍放不下就不写。
   *
   * @param taken 已写下的文字所占的矩形
   */
  private void drawLabels(PidsLayout.Yard yard, YardGeometry geometry, List<int[]> taken) {
    int size = yard.labelSize();
    List<int[]> occupied = new ArrayList<>(taken);
    List<YardGeometry.Segment> lines =
        geometry.paths().stream().flatMap(path -> path.segments().stream()).toList();
    for (YardGeometry.Label exit : geometry.exits()) {
      List<String> texts = new ArrayList<>();
      texts.add(p.ellipsize(exit.text(), size, yard.trackWidth() / 3));
      exit.code().filter(code -> !code.equals(exit.text())).ifPresent(texts::add);
      placeLabel(yard, exit, texts, size, occupied, lines);
    }
  }

  /** 按写法的先后、先下后上找第一个不压字的位置写下。 */
  private void placeLabel(
      PidsLayout.Yard yard,
      YardGeometry.Label exit,
      List<String> texts,
      int size,
      List<int[]> occupied,
      List<YardGeometry.Segment> lines) {
    int bottomEdge = yard.y() + yard.height() - 2;
    for (String text : texts) {
      int width = p.width(text, size);
      int x = exit.alignRight() ? exit.at().x() - width : exit.at().x();
      x = Math.max(yard.x() + 2, Math.min(x, yard.x() + yard.width() - 2 - width));
      for (int top : List.of(exit.at().y() + 4, exit.at().y() - size - 4)) {
        int[] box = {x - 1, top - 1, x + width + 1, top + size + 1};
        if (top >= yard.y() + 1
            && top + size <= bottomEdge
            && occupied.stream().noneMatch(other -> intersects(box, other))
            && lines.stream().noneMatch(line -> crosses(box, line))) {
          label(text, size, x, top);
          occupied.add(box);
          return;
        }
      }
    }
  }

  /** 线段穿过矩形 {左, 上, 右, 下}（取线上逐点判断，线都很短）。 */
  private static boolean crosses(int[] box, YardGeometry.Segment line) {
    int steps = Math.max(Math.abs(line.x1() - line.x0()), Math.abs(line.y1() - line.y0()));
    for (int i = 0; i <= steps; i++) {
      double t = steps == 0 ? 0.0 : (double) i / steps;
      double x = line.x0() + t * (line.x1() - line.x0());
      double y = line.y0() + t * (line.y1() - line.y0());
      if (x > box[0] && x < box[2] && y > box[1] - 1 && y < box[3] + 1) {
        return true;
      }
    }
    return false;
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
