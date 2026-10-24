package org.fetarute.fetaruteTCAddon.display.pids.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.display.Lateness;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout.TextStyle;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Names;
import org.fetarute.fetaruteTCAddon.display.pids.view.SignalboxView;
import org.fetarute.fetaruteTCAddon.display.pids.yard.YardGeometry;
import org.fetarute.fetaruteTCAddon.signaller.console.SignalboxState;

/**
 * 画信号员控制台：标题栏的模式牌、值守者、呼叫牌与临时提示，站场图上的叠加层（计划进路、排列中的进路、入口信号、可改停的股道、选中车的车次窗框）， 列车表与操作按钮。
 *
 * <p>位置全部取自 {@link PidsSignalboxPlan}，与点击区是同一份。颜色都是地图调色板里有的颜色。
 */
final class PidsSignalboxPainter {

  /** 红底按钮上的字：深浅主题都用白色。 */
  private static final int DANGER_TEXT = 0xFFFFFF;

  /** 深色主题的计划进路预显示（浅蓝）。 */
  static final int PREVIEW_DARK = 0x6699D8;

  /** 浅色主题的计划进路预显示（白底上浅蓝看不清，取深一档的蓝）。 */
  static final int PREVIEW_LIGHT = 0x334CB2;

  /** 列车表行底进度条的高。 */
  static final int PROGRESS = 2;

  /** 列车表车次一列左缘的线路色条宽。 */
  static final int STRIP = 2;

  /** 列车表各列文字距列左缘的距离（车次一列在色条之后）。 */
  static final int CELL_INSET = 6;

  /** 偏差不到这么多秒（含早到）写“正点”。 */
  static final long ON_TIME_SECONDS = 30L;

  /** 没有时刻表时晚点一列写的符号。 */
  static final String NO_DELAY = "—";

  private final PidsRenderer.Painter p;
  private final PidsSignalboxPlan plan;

  PidsSignalboxPainter(PidsRenderer.Painter painter, PidsSignalboxPlan plan) {
    this.p = painter;
    this.plan = plan;
  }

  /** 计划进路预显示的颜色。 */
  static int previewColor(PidsTheme theme) {
    return PidsTheme.LIGHT.equals(theme) ? PREVIEW_LIGHT : PREVIEW_DARK;
  }

  // ─── 标题栏 ───────────────────────────────────────────────────────────────

  /** 模式牌（人工为琥珀色底、自动为灰底）、值守者、呼叫牌（琥珀色底）与临时提示（琥珀色字）。 */
  void drawHeader() {
    PidsTheme theme = p.theme();
    boolean manual =
        plan.view().state().map(s -> s.mode() == SignalboxState.Mode.MANUAL).orElse(false);
    plan.mode().ifPresent(item -> badge(item, manual ? theme.amber() : theme.outline()));
    plan.signaller()
        .ifPresent(
            item ->
                p.text(
                    item.text(),
                    PidsSignalboxPlan.HEADER_TEXT,
                    item.box().x(),
                    item.box().y(),
                    theme.muted()));
    plan.call().ifPresent(item -> badge(item, theme.amber()));
    plan.notice()
        .ifPresent(
            item ->
                p.text(
                    item.text(),
                    PidsSignalboxPlan.HEADER_TEXT,
                    item.box().x(),
                    item.box().y(),
                    theme.amber()));
  }

  private void badge(PidsSignalboxPlan.Item item, int background) {
    PidsSignalboxPlan.Box box = item.box();
    p.fill(box.x(), box.y(), box.width(), box.height(), background);
    p.text(
        item.text(),
        PidsSignalboxPlan.HEADER_TEXT,
        box.x() + PidsSignalboxPlan.BADGE_PADDING,
        box.y() + (box.height() - PidsSignalboxPlan.HEADER_TEXT) / 2,
        PidsTheme.textOn(background));
  }

  // ─── 站场图叠加层 ─────────────────────────────────────────────────────────

  /** 叠在站场图上的东西；没有控制台数据时什么都不叠。 */
  PidsYardPainter.Overlay overlay(PidsYardPainter yard) {
    Optional<SignalboxState> found = plan.view().state();
    if (found.isEmpty()) {
      return PidsYardPainter.Overlay.NONE;
    }
    SignalboxState state = found.get();
    return new PidsYardPainter.Overlay() {
      @Override
      public void routes(YardGeometry geometry) {
        drawDelegated(geometry, state);
        drawRoutes(yard, geometry, state);
      }

      @Override
      public List<int[]> reserved(YardGeometry geometry) {
        List<int[]> out = new ArrayList<>();
        for (PidsSignalboxPlan.SignalSpot spot : plan.signals()) {
          out.add(around(spot.square()));
          spot.letter().ifPresent(letter -> out.add(around(letter)));
        }
        return out;
      }

      @Override
      public void top(YardGeometry geometry, List<PidsYardPainter.Describer> describers) {
        drawSignals();
        drawPlatformChoices();
        drawSelection(state, describers);
      }
    };
  }

  /** 让出去的小区围框离小区里的线与站台块多远（像素）。 */
  private static final int DELEGATED_MARGIN = 4;

  /**
   * 让给小区（车站控制）的那几片：围着小区里的节点、两端都在小区里的线与小区站台的站台块画一圈虚线框，框的上沿外写“小区名 ·
   * 车站控制（信号员）”。画在轨道之后、占用之前，车与占用照常盖在上面。
   */
  private void drawDelegated(YardGeometry geometry, SignalboxState state) {
    int size = plan.yard().map(PidsLayout.Yard::labelSize).orElse(10);
    String template = plan.view().labels().delegated();
    PidsTheme theme = p.theme();
    for (SignalboxState.Delegated area : state.delegated()) {
      int[] bounds = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
      List<YardGeometry.Point> points = new ArrayList<>();
      for (NodeId node : area.nodes()) {
        geometry.nodeAt(node).ifPresent(points::add);
      }
      points.forEach(at -> widen(bounds, at.x(), at.y(), at.x(), at.y()));
      for (YardGeometry.Path path : geometry.paths()) {
        if (area.nodes().contains(path.edge().a()) && area.nodes().contains(path.edge().b())) {
          path.segments().forEach(s -> widen(bounds, s.x0(), s.y0(), s.x1(), s.y1()));
        }
      }
      // 小区站台的站台块：块横向罩着小区里的节点、纵向挨着它那一行
      for (YardGeometry.Platform block : geometry.platforms()) {
        for (YardGeometry.Point at : points) {
          if (block.left() <= at.x()
              && at.x() <= block.right()
              && block.top() - YardGeometry.ROW_GAP <= at.y()
              && at.y() <= block.bottom() + YardGeometry.ROW_GAP) {
            widen(bounds, block.left(), block.top(), block.right(), block.bottom());
            break;
          }
        }
      }
      if (bounds[0] > bounds[2]) {
        continue;
      }
      int left = bounds[0] - DELEGATED_MARGIN;
      int top = bounds[1] - DELEGATED_MARGIN;
      int right = bounds[2] + DELEGATED_MARGIN;
      int bottom = bounds[3] + DELEGATED_MARGIN;
      dashedFrame(left, top, right, bottom, theme.amber());
      String text = template.replace("<zone>", area.zone()).replace("<name>", area.signaller());
      int width = p.width(text, size);
      p.fill(left, top - size - 3, width + 4, size + 2, theme.background());
      p.text(text, size, left + 2, top - size - 2, theme.amber());
    }
  }

  /** 把矩形 {左, 上, 右, 下} 扩到包住这两点。 */
  private static void widen(int[] bounds, int x0, int y0, int x1, int y1) {
    bounds[0] = Math.min(bounds[0], Math.min(x0, x1));
    bounds[1] = Math.min(bounds[1], Math.min(y0, y1));
    bounds[2] = Math.max(bounds[2], Math.max(x0, x1));
    bounds[3] = Math.max(bounds[3], Math.max(y0, y1));
  }

  /** 1 像素虚线框：每 7 像素画 4 像素。 */
  private void dashedFrame(int left, int top, int right, int bottom, int rgb) {
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

  /** 方框外扩一像素的 {左, 上, 右, 下}。 */
  private static int[] around(PidsSignalboxPlan.Box box) {
    return new int[] {box.x() - 1, box.y() - 1, box.right() + 1, box.y() + box.height() + 1};
  }

  /** 选中车的计划进路画浅蓝实线，已给许可、内核还没放行的进路画白色虚线；锁闭与占用随后照常盖在上面。 */
  private void drawRoutes(PidsYardPainter yard, YardGeometry geometry, SignalboxState state) {
    Set<EdgeId> preview = PidsSignalboxPlan.normalized(state.preview());
    Set<EdgeId> pending = PidsSignalboxPlan.normalized(state.pending());
    int blue = previewColor(p.theme());
    for (YardGeometry.Path path : geometry.paths()) {
      if (preview.contains(PidsSignalboxPlan.normalized(path.edge()))) {
        yard.drawPath(path, PidsYardPainter.TRACK, blue);
      }
    }
    for (YardGeometry.Path path : geometry.paths()) {
      if (pending.contains(PidsSignalboxPlan.normalized(path.edge()))) {
        for (YardGeometry.Segment segment : path.segments()) {
          dashed(segment, p.theme().text());
        }
      }
    }
  }

  /** 2 像素粗的虚线：沿线每 2 步画、2 步空（水平线与 45° 斜线都按步数算）。 */
  private void dashed(YardGeometry.Segment segment, int rgb) {
    int dx = segment.x1() - segment.x0();
    int dy = segment.y1() - segment.y0();
    int steps = Math.max(Math.abs(dx), Math.abs(dy));
    for (int i = 0; i <= steps; i++) {
      if ((i / 2) % 2 != 0) {
        continue;
      }
      int x = steps == 0 ? segment.x0() : segment.x0() + Math.round((float) i * dx / steps);
      int y = steps == 0 ? segment.y0() : segment.y0() + Math.round((float) i * dy / steps);
      p.fill(x, y, PidsYardPainter.TRACK, PidsYardPainter.TRACK, rgb);
    }
  }

  /** 入口信号：停车红、排列中黄、开放绿；设了自动通过的旁边写绿色 “A”。 */
  private void drawSignals() {
    PidsTheme theme = p.theme();
    for (PidsSignalboxPlan.SignalSpot spot : plan.signals()) {
      int color =
          switch (spot.signal().aspect()) {
            case STOP -> theme.red();
            case PENDING -> theme.amber();
            case PROCEED -> theme.green();
          };
      PidsSignalboxPlan.Box square = spot.square();
      p.fill(square.x(), square.y(), square.width(), square.height(), color);
      spot.letter()
          .ifPresent(
              letter ->
                  p.text(
                      PidsSignalboxPlan.AUTO_PASS,
                      PidsSignalboxPlan.AUTO_PASS_TEXT,
                      letter.x(),
                      letter.y(),
                      theme.green()));
    }
  }

  /** 可改停的股道：空心浅蓝方块，现在要停的那条为实心。 */
  private void drawPlatformChoices() {
    int blue = previewColor(p.theme());
    for (PidsSignalboxPlan.ChoiceSpot spot : plan.platforms()) {
      PidsSignalboxPlan.Box square = spot.square();
      if (spot.choice().current()) {
        p.fill(square.x(), square.y(), square.width(), square.height(), blue);
      } else {
        p.outline(square.x(), square.y(), square.width(), square.height(), blue);
      }
    }
  }

  /** 选中车的车次窗外加 2 像素琥珀色框。 */
  private void drawSelection(SignalboxState state, List<PidsYardPainter.Describer> describers) {
    for (PidsYardPainter.Describer describer : describers) {
      if (PidsSignalboxPlan.isSelected(state, describer.train().name())) {
        p.outline(
            describer.x() - 2,
            describer.y() - 2,
            describer.width() + 4,
            describer.height() + 4,
            p.theme().amber());
      }
    }
  }

  // ─── 列车表 ───────────────────────────────────────────────────────────────

  /** 表头（画不下的车数写在右端 “+N”）、各行（选中行铺高亮底；等许可、扣车的行底画琥珀色进度条）；没有控制台数据时写说明。 */
  void drawTrainList(PidsLayout.TrainList list) {
    PidsTheme theme = p.theme();
    SignalboxView view = plan.view();
    SignalboxView.Labels labels = view.labels();
    int headerTop = list.y() + (list.headerHeight() - list.headerSize()) / 2;
    p.text(
        labels.headerTrain(), list.headerSize(), list.x() + CELL_INSET, headerTop, theme.muted());
    p.text(
        labels.headerDestination(),
        list.headerSize(),
        list.x() + list.destinationX(),
        headerTop,
        theme.muted());
    p.text(
        labels.headerStatus(),
        list.headerSize(),
        list.x() + list.statusX(),
        headerTop,
        theme.muted());
    p.text(
        labels.headerDelay(),
        list.headerSize(),
        list.x() + list.delayX(),
        headerTop,
        theme.muted());
    p.fill(list.x(), list.y() + list.headerHeight() - 1, list.width(), 1, theme.panel());
    if (view.state().isEmpty()) {
      view.placeholder().ifPresent(message -> drawPlaceholder(list, message));
      return;
    }
    SignalboxState state = view.state().get();
    int hidden = state.rows().size() - plan.rows().size();
    if (hidden > 0) {
      String more = "+" + hidden;
      p.text(
          more,
          list.headerSize(),
          list.x() + list.width() - CELL_INSET - p.width(more, list.headerSize()),
          headerTop,
          theme.amber());
    }
    if (plan.rows().isEmpty()) {
      p.text(
          labels.noTrains(),
          list.textSize(),
          list.x() + CELL_INSET,
          list.rowTop(0) + (list.rowHeight() - PROGRESS - list.textSize()) / 2,
          theme.muted());
      return;
    }
    for (PidsSignalboxPlan.RowSpot spot : plan.rows()) {
      drawRow(list, state, spot);
    }
  }

  private void drawRow(
      PidsLayout.TrainList list, SignalboxState state, PidsSignalboxPlan.RowSpot spot) {
    PidsTheme theme = p.theme();
    SignalboxView.Labels labels = plan.view().labels();
    SignalboxState.TrainRow row = spot.row();
    PidsSignalboxPlan.Box box = spot.box();
    int size = list.textSize();
    if (spot.selected()) {
      p.fill(box.x(), box.y(), box.width(), box.height(), theme.inverseMuted());
    }
    p.fill(box.x(), box.y() + 3, STRIP, box.height() - 6, row.lineColor().orElse(theme.outline()));
    int top = box.y() + (box.height() - PROGRESS - size) / 2;
    // 车次（设了自动通过的进路后面写绿色 “A”）
    int trainRoom = list.destinationX() - CELL_INSET - 4;
    int letterWidth =
        row.autoPass()
            ? p.width(PidsSignalboxPlan.AUTO_PASS, PidsSignalboxPlan.AUTO_PASS_TEXT) + 3
            : 0;
    int labelWidth =
        p.text(
            p.ellipsize(row.label(), size, trainRoom - letterWidth),
            size,
            box.x() + CELL_INSET,
            top,
            theme.text());
    if (row.autoPass()) {
      p.text(
          PidsSignalboxPlan.AUTO_PASS,
          PidsSignalboxPlan.AUTO_PASS_TEXT,
          box.x() + CELL_INSET + labelWidth + 3,
          top + (size - PidsSignalboxPlan.AUTO_PASS_TEXT) / 2,
          theme.green());
    }
    // 去向：站码换成站名，查不到写站码
    row.destination()
        .ifPresent(
            code ->
                p.text(
                    p.ellipsize(
                        plan.view().destinations().getOrDefault(code, code),
                        size,
                        list.statusX() - list.destinationX() - 6),
                    size,
                    box.x() + list.destinationX(),
                    top,
                    theme.text()));
    // 状态（等许可、扣车带秒数；系统代排的另行标明；已令越站的写出被越的站）
    String status = PidsSignalboxPlan.statusText(plan.view(), row);
    p.text(
        p.ellipsize(status, size, list.delayX() - list.statusX() - 6),
        size,
        box.x() + list.statusX(),
        top,
        statusColor(theme, row.status()));
    // 晚点
    Delay delay = delay(labels, row.delaySeconds(), theme);
    p.text(
        p.ellipsize(delay.text(), size, list.width() - list.delayX() - 2),
        size,
        box.x() + list.delayX(),
        top,
        delay.color());
    // 离超时自动排列、离扣车上限的进度
    if (row.status() == SignalboxState.RowStatus.WAITING
        || row.status() == SignalboxState.RowStatus.HELD) {
      double progress = Math.max(0.0, Math.min(1.0, row.autoProgress()));
      int length = (int) Math.round(box.width() * progress);
      p.fill(box.x(), box.bottom() - PROGRESS, length, PROGRESS, theme.amber());
    }
  }

  private static int statusColor(PidsTheme theme, SignalboxState.RowStatus status) {
    return switch (status) {
      case APPROACHING -> theme.muted();
      case WAITING, HELD -> theme.amber();
      case ROUTE_SET -> theme.green();
      case ROUTE_PENDING, IN_ZONE, DWELLING -> theme.text();
    };
  }

  /**
   * 晚点一列写的字与颜色。
   *
   * @param text 字
   * @param color 颜色
   */
  record Delay(String text, int color) {}

  /** 偏差不到 {@value #ON_TIME_SECONDS} 秒写“正点”，否则写带正负号的分秒（+1:20、-0:30）；晚点超过 1 分钟琥珀色、超过 5 分钟红色。 */
  static Delay delay(SignalboxView.Labels labels, OptionalLong delaySeconds, PidsTheme theme) {
    if (delaySeconds.isEmpty()) {
      return new Delay(NO_DELAY, theme.muted());
    }
    long seconds = delaySeconds.getAsLong();
    if (Math.abs(seconds) < ON_TIME_SECONDS) {
      return new Delay(labels.onTime(), theme.text());
    }
    long abs = Math.abs(seconds);
    String text =
        (seconds > 0 ? "+" : "-") + abs / 60 + ":" + String.format(Locale.ROOT, "%02d", abs % 60);
    int color =
        switch (Lateness.of(seconds)) {
          case ON_TIME -> theme.text();
          case LATE -> theme.amber();
          case SEVERELY_LATE -> theme.red();
        };
    return new Delay(text, color);
  }

  private void drawPlaceholder(PidsLayout.TrainList list, Names message) {
    PidsTheme theme = p.theme();
    int rowsTop = list.y() + list.headerHeight();
    int rowsHeight = list.rows() * list.rowHeight();
    int center = list.x() + list.width() / 2;
    int top = rowsTop + rowsHeight / 2 - 12;
    p.textCentered(
        p.ellipsize(message.primary(), 12, list.width() - 8), 12, center, top, theme.text());
    if (!message.secondary().isEmpty()) {
      String secondary = p.ellipsizeWords(message.secondary(), 10, list.width() - 8);
      int width = p.regularWidth(secondary, 10);
      p.regular(secondary, 10, center - width / 2, top + 16, theme.muted(), false);
    }
  }

  // ─── 操作区 ───────────────────────────────────────────────────────────────

  /** 按钮：面板色底加框，中英文上下叠放居中；不能按的灰显。回复呼叫时的四条回复加琥珀色框；等着确认的取消车次红底白字。 */
  void drawButtons(PidsLayout.SignalButtons widget) {
    if (plan.view().state().isEmpty()) {
      return;
    }
    PidsTheme theme = p.theme();
    boolean replying = plan.view().state().get().replying();
    for (PidsSignalboxPlan.ButtonSpot button : plan.buttons()) {
      PidsSignalboxPlan.Box box = button.box();
      if (button.danger()) {
        p.fill(box.x(), box.y(), box.width(), box.height(), theme.red());
        label(button.label(), widget.label(), box, DANGER_TEXT, DANGER_TEXT);
        continue;
      }
      int frame =
          replying ? theme.amber() : button.enabled() ? theme.muted() : theme.inverseMuted();
      int primary = button.enabled() ? theme.text() : theme.outline();
      int secondary = button.enabled() ? theme.muted() : theme.outline();
      p.fill(box.x(), box.y(), box.width(), box.height(), theme.panel());
      p.outline(box.x(), box.y(), box.width(), box.height(), frame);
      label(button.label(), widget.label(), box, primary, secondary);
    }
  }

  /** 按钮上的字：中英文上下叠放、整块竖向居中；英文放不下（高或宽）时只写中文，中文超宽时省略。 */
  private void label(
      Names label, TextStyle style, PidsSignalboxPlan.Box box, int primary, int secondary) {
    int room = box.width() - 8;
    boolean english =
        style.hasSecondary()
            && !label.secondary().isEmpty()
            && style.size() + style.gap() + style.secondarySize() <= box.height() - 2
            && p.regularWidth(label.secondary(), style.secondarySize()) <= room;
    int block = style.size() + (english ? style.gap() + style.secondarySize() : 0);
    int top = box.y() + (box.height() - block) / 2;
    int center = box.x() + box.width() / 2;
    p.textCentered(
        p.ellipsize(label.primary(), style.size(), room), style.size(), center, top, primary);
    if (english) {
      int width = p.regularWidth(label.secondary(), style.secondarySize());
      p.regular(
          label.secondary(),
          style.secondarySize(),
          center - width / 2,
          top + style.size() + style.gap(),
          secondary,
          false);
    }
  }
}
