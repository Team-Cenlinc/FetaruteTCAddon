package org.fetarute.fetaruteTCAddon.display.pids.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Names;
import org.fetarute.fetaruteTCAddon.display.pids.view.SignalboxView;
import org.fetarute.fetaruteTCAddon.display.pids.yard.YardGeometry;
import org.fetarute.fetaruteTCAddon.signaller.console.ConsoleTarget;
import org.fetarute.fetaruteTCAddon.signaller.console.SignalboxState;

/**
 * 信号员控制台一帧的版面：标题栏的牌子、站场图上的入口信号与可改停的股道、车次窗、列车表的行、操作按钮各在哪里，点到的是什么。
 *
 * <p>只按布局、视图与文字宽度推出，不依赖画的过程：画（{@link PidsSignalboxPainter}）与点击（{@link #target}）用同一份， 画在哪里就在哪里点得到。
 * 所有点击区不小于 {@value #MIN_TARGET}×{@value #MIN_TARGET} 像素：站在墙前 3–4 格时约 1/8 格宽，点得准。
 */
final class PidsSignalboxPlan {

  /** 点击区的最小边长（像素）。 */
  static final int MIN_TARGET = 16;

  /** 标题栏牌子（模式牌、呼叫牌）的高。 */
  static final int BADGE_HEIGHT = 20;

  /** 牌子里文字的左右留白。 */
  static final int BADGE_PADDING = 6;

  /** 标题栏牌子、值守者与提示的字号。 */
  static final int HEADER_TEXT = 12;

  /** 标题栏各项之间的间距。 */
  static final int HEADER_GAP = 8;

  /** 标题栏里一项至少要有这么宽才写，再窄就不写。 */
  static final int HEADER_MIN_ROOM = 24;

  /** 入口信号方块的边长。 */
  static final int SIGNAL = 8;

  /** 入口信号方块离区内那一端的距离（沿出口方向）。 */
  static final int SIGNAL_OFFSET = 4;

  /** 入口信号与轨道线之间的空隙。 */
  static final int SIGNAL_CLEARANCE = 3;

  /** 自动通过标记 “A” 的字号。 */
  static final int AUTO_PASS_TEXT = 10;

  /** 自动通过标记。 */
  static final String AUTO_PASS = "A";

  /** 可改停股道方块的边长。 */
  static final int CHOICE = 10;

  /** 可改停股道的方块画在站台块右端往里这么多（方块中心）。 */
  static final int CHOICE_INSET = 8;

  /** 站台块与股道线相距不超过这么多才算靠着这条股道。 */
  static final int PLATFORM_REACH = 8;

  /**
   * 矩形。
   *
   * @param x 左
   * @param y 上
   * @param width 宽
   * @param height 高
   */
  record Box(int x, int y, int width, int height) {

    boolean contains(int px, int py) {
      return px >= x && px < x + width && py >= y && py < y + height;
    }

    int right() {
      return x + width;
    }

    int bottom() {
      return y + height;
    }

    /** 以中心为准放大到至少 {@code min}×{@code min}（点击区）。 */
    Box atLeast(int min) {
      int w = Math.max(width, min);
      int h = Math.max(height, min);
      return new Box(x - (w - width) / 2, y - (h - height) / 2, w, h);
    }
  }

  /**
   * 标题栏的一项。
   *
   * @param box 所占的矩形（牌子为整块牌子，文字为文字的范围）
   * @param text 写的字（已按宽度省略）
   */
  record Item(Box box, String text) {}

  /**
   * 一架入口信号。
   *
   * @param signal 信号
   * @param square 方块
   * @param letter 自动通过标记 “A” 的左上角（宽高为字宽与字号）；没设自动通过时为空
   * @param hit 点击区
   */
  record SignalSpot(SignalboxState.EntrySignal signal, Box square, Optional<Box> letter, Box hit) {}

  /**
   * 一条可改停的股道。
   *
   * @param choice 股道
   * @param square 方块
   * @param hit 点击区
   */
  record ChoiceSpot(SignalboxState.PlatformChoice choice, Box square, Box hit) {}

  /**
   * 列车表的一行。
   *
   * @param row 行数据
   * @param box 整行（点击区）
   * @param selected 是选中的车
   */
  record RowSpot(SignalboxState.TrainRow row, Box box, boolean selected) {}

  /**
   * 操作区的一个按钮或一条回复。
   *
   * @param target 点到时回传的东西
   * @param box 按钮（点击区）
   * @param label 按钮上的字
   * @param enabled 能不能按（不能按的灰显，点了照样回传，由信号员模块回提示）
   */
  record ButtonSpot(ConsoleTarget target, Box box, Names label, boolean enabled) {}

  private final SignalboxView view;
  private final Optional<PidsLayout.Yard> yard;
  private final Optional<Item> mode;
  private final Optional<Item> signaller;
  private final Optional<Item> call;
  private final Optional<Item> notice;
  private final List<PidsYardPainter.Describer> describers;
  private final List<SignalSpot> signals;
  private final List<ChoiceSpot> platforms;
  private final List<RowSpot> rows;
  private final List<ButtonSpot> buttons;

  private PidsSignalboxPlan(
      SignalboxView view,
      Optional<PidsLayout.Yard> yard,
      Optional<Item> mode,
      Optional<Item> signaller,
      Optional<Item> call,
      Optional<Item> notice,
      List<PidsYardPainter.Describer> describers,
      List<SignalSpot> signals,
      List<ChoiceSpot> platforms,
      List<RowSpot> rows,
      List<ButtonSpot> buttons) {
    this.view = view;
    this.yard = yard;
    this.mode = mode;
    this.signaller = signaller;
    this.call = call;
    this.notice = notice;
    this.describers = List.copyOf(describers);
    this.signals = List.copyOf(signals);
    this.platforms = List.copyOf(platforms);
    this.rows = List.copyOf(rows);
    this.buttons = List.copyOf(buttons);
  }

  /**
   * 推出一帧的版面。
   *
   * @param layout 布局
   * @param view 一帧
   * @param measure 量文字的尺子（与画的时候同一字体、同一仿粗体起始字号）
   */
  static PidsSignalboxPlan of(PidsLayout layout, SignalboxView view, PidsTextMeasure measure) {
    Objects.requireNonNull(layout, "layout");
    Objects.requireNonNull(view, "view");
    Objects.requireNonNull(measure, "measure");
    Optional<PidsLayout.Yard> yard = layout.yard();
    Optional<YardGeometry> geometry = view.yard().picture().geometry();
    List<PidsYardPainter.Describer> describers =
        yard.isPresent() && geometry.isPresent()
            ? PidsYardPainter.describers(
                yard.get(), view.yard().picture().trains(), geometry.get().platforms(), measure)
            : List.of();
    Optional<SignalboxState> found = view.state();
    if (found.isEmpty()) {
      return new PidsSignalboxPlan(
          view,
          yard,
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          describers,
          List.of(),
          List.of(),
          List.of(),
          List.of());
    }
    SignalboxState state = found.get();
    Header header = header(layout, view, state, measure);
    List<SignalSpot> signals = new ArrayList<>();
    List<ChoiceSpot> platforms = new ArrayList<>();
    if (yard.isPresent() && geometry.isPresent()) {
      signals.addAll(signals(yard.get(), geometry.get(), state, measure));
      platforms.addAll(platforms(geometry.get(), state));
    }
    return new PidsSignalboxPlan(
        view,
        yard,
        header.mode(),
        header.signaller(),
        header.call(),
        header.notice(),
        describers,
        signals,
        platforms,
        layout.trainList().map(list -> rows(list, state)).orElse(List.of()),
        layout.signalButtons().map(widget -> buttons(widget, view, state)).orElse(List.of()));
  }

  private record Header(
      Optional<Item> mode, Optional<Item> signaller, Optional<Item> call, Optional<Item> notice) {}

  /** 标题栏：控制区名后面依次是模式牌、值守者、呼叫牌，临时提示靠右写在时钟左边。放不下时先省略值守者，再省略提示；模式牌与呼叫牌总是画。 */
  private static Header header(
      PidsLayout layout, SignalboxView view, SignalboxState state, PidsTextMeasure measure) {
    SignalboxView.Labels labels = view.labels();
    Optional<PidsLayout.StationTitle> title =
        layout.widgets().stream()
            .filter(PidsLayout.StationTitle.class::isInstance)
            .map(PidsLayout.StationTitle.class::cast)
            .findFirst();
    Optional<PidsLayout.Clock> clock =
        layout.widgets().stream()
            .filter(PidsLayout.Clock.class::isInstance)
            .map(PidsLayout.Clock.class::cast)
            .findFirst();
    int top =
        title.map(t -> t.y() + (t.sizes().get(0) - BADGE_HEIGHT) / 2).orElse(HEADER_GAP / 2 + 2);
    int textTop = top + (BADGE_HEIGHT - HEADER_TEXT) / 2;
    int left = title.map(t -> t.x() + titleWidth(t, view.yard().title(), measure) + 12).orElse(8);
    int right = layout.width() - 8;
    if (clock.isPresent()) {
      PidsLayout.Clock c = clock.get();
      int clockLeft =
          c.align() == PidsLayout.Align.RIGHT
              ? c.x() - measure.width(view.yard().clock(), c.size())
              : c.x();
      if (clockLeft > left) {
        right = clockLeft - 10;
      }
    }
    String modeText = state.mode() == SignalboxState.Mode.MANUAL ? labels.manual() : labels.auto();
    Box modeBox =
        new Box(left, top, measure.width(modeText, HEADER_TEXT) + 2 * BADGE_PADDING, BADGE_HEIGHT);
    int x = modeBox.right() + HEADER_GAP;
    Optional<String> callText =
        state
            .call()
            .map(
                c ->
                    labels
                        .call()
                        .replace("<train>", trainLabel(state, c.trainName()))
                        .replace("<caller>", labels.callers().getOrDefault(c.role(), ""))
                        .replace("<reason>", labels.callReasons().getOrDefault(c.reason(), "")));
    int callSpace =
        callText
            .map(text -> measure.width(text, HEADER_TEXT) + 2 * BADGE_PADDING + HEADER_GAP)
            .orElse(0);
    Optional<Item> noticeItem = Optional.empty();
    Optional<String> noticeText = state.notice().map(n -> noticeText(labels, state, n));
    int noticeRoom = right - x - callSpace;
    if (noticeText.isPresent() && noticeRoom >= HEADER_MIN_ROOM) {
      String shown = measure.ellipsize(noticeText.get(), HEADER_TEXT, noticeRoom);
      int width = measure.width(shown, HEADER_TEXT);
      noticeItem =
          Optional.of(new Item(new Box(right - width, textTop, width, HEADER_TEXT), shown));
    }
    int signallerRight = noticeItem.map(item -> item.box().x() - 2 * HEADER_GAP).orElse(right);
    int signallerRoom = signallerRight - x - callSpace;
    String signallerText =
        state
            .signaller()
            .map(name -> labels.signaller().replace("<name>", name))
            .orElse(labels.unattended());
    Optional<Item> signallerItem = Optional.empty();
    if (signallerRoom >= HEADER_MIN_ROOM) {
      String shown = measure.ellipsize(signallerText, HEADER_TEXT, signallerRoom);
      int width = measure.width(shown, HEADER_TEXT);
      signallerItem = Optional.of(new Item(new Box(x, textTop, width, HEADER_TEXT), shown));
      x += width + HEADER_GAP;
    }
    int callX = x;
    Optional<Item> callItem =
        callText.map(
            text ->
                new Item(
                    new Box(
                        callX,
                        top,
                        measure.width(text, HEADER_TEXT) + 2 * BADGE_PADDING,
                        BADGE_HEIGHT),
                    text));
    return new Header(
        Optional.of(new Item(modeBox, modeText)), signallerItem, callItem, noticeItem);
  }

  /** 控制区名实际画了多宽：与站台屏画站名的取舍相同（按字号依次试，都放不下时去掉英文、省略中文）。 */
  static int titleWidth(PidsLayout.StationTitle title, Names names, PidsTextMeasure measure) {
    for (int size : title.sizes()) {
      int width = measure.width(names.primary(), size);
      int total =
          names.secondary().isEmpty()
              ? width
              : width
                  + title.gap()
                  + measure.regularWidth(names.secondary(), title.secondarySize());
      if (total <= title.width()) {
        return total;
      }
    }
    int smallest = title.sizes().get(title.sizes().size() - 1);
    return measure.width(measure.ellipsize(names.primary(), smallest, title.width()), smallest);
  }

  /** 列车在控制台上显示的车次：列车表里有它时取那一行的车次号，否则为列车名。 */
  static String trainLabel(SignalboxState state, String trainName) {
    return state.rows().stream()
        .filter(row -> row.trainName().equalsIgnoreCase(trainName))
        .map(SignalboxState.TrainRow::label)
        .findFirst()
        .orElse(trainName);
  }

  /** 临时提示的文字：进路被某列车占用时写出是哪列车；其余带列车的提示把 {@code <train>} 换成车次。 */
  static String noticeText(
      SignalboxView.Labels labels, SignalboxState state, SignalboxState.Notice notice) {
    if (notice.kind() == SignalboxState.NoticeKind.ROUTE_BLOCKED && notice.train().isPresent()) {
      return labels.routeBlockedBy().replace("<train>", trainLabel(state, notice.train().get()));
    }
    String text = labels.notices().getOrDefault(notice.kind(), notice.kind().name());
    return text.replace(
        "<train>", notice.train().map(train -> trainLabel(state, train)).orElse(""));
  }

  /**
   * 入口信号：画在出口区间区内那一端、朝出口那一侧，方块在轨道线上方；上方压到站台块、别的信号或伸出站场图时改在下方，两边都压到时仍画在上方。 设了自动通过的在方块外侧写
   * “A”。找不到对应出口区间的信号不画。
   */
  private static List<SignalSpot> signals(
      PidsLayout.Yard yard, YardGeometry geometry, SignalboxState state, PidsTextMeasure measure) {
    List<SignalSpot> out = new ArrayList<>();
    for (SignalboxState.EntrySignal signal : state.signals()) {
      EdgeId edge = normalized(signal.exit());
      Optional<YardGeometry.Stub> stub =
          geometry.stubs().stream().filter(s -> normalized(s.edge()).equals(edge)).findFirst();
      if (stub.isEmpty()) {
        continue;
      }
      YardGeometry.Point inner = stub.get().inner();
      YardGeometry.Point toward = stub.get().toward();
      int direction = toward.x() >= inner.x() ? 1 : -1;
      int left = direction > 0 ? inner.x() + SIGNAL_OFFSET : inner.x() - SIGNAL_OFFSET - SIGNAL + 1;
      Box above = new Box(left, inner.y() - SIGNAL_CLEARANCE - SIGNAL, SIGNAL, SIGNAL);
      Box below =
          new Box(left, inner.y() + YardGeometry.TRACK_LINE + SIGNAL_CLEARANCE, SIGNAL, SIGNAL);
      Box square = above;
      if (!free(above, yard, geometry, out)) {
        square = free(below, yard, geometry, out) || above.y() < yard.y() + 2 ? below : above;
      }
      int top = square.y();
      Optional<Box> letter = Optional.empty();
      if (signal.autoPass()) {
        int width = measure.width(AUTO_PASS, AUTO_PASS_TEXT);
        int x = direction > 0 ? square.right() + 2 : square.x() - 2 - width;
        letter = Optional.of(new Box(x, top - 1, width, AUTO_PASS_TEXT));
      }
      out.add(new SignalSpot(signal, square, letter, square.atLeast(MIN_TARGET)));
    }
    return out;
  }

  /** 信号方块放在这里不伸出站场图、不压站台块（含股道号）与已放好的信号。 */
  private static boolean free(
      Box square, PidsLayout.Yard yard, YardGeometry geometry, List<SignalSpot> placed) {
    if (square.y() < yard.y() + 2 || square.bottom() > yard.y() + yard.height() - 2) {
      return false;
    }
    for (YardGeometry.Platform platform : geometry.platforms()) {
      if (overlaps(
          square,
          new Box(
              platform.left(),
              platform.top(),
              platform.right() - platform.left(),
              platform.bottom() - platform.top()))) {
        return false;
      }
    }
    return placed.stream().noneMatch(spot -> overlaps(square, spot.square()));
  }

  private static boolean overlaps(Box a, Box b) {
    return a.x() < b.right() && b.x() < a.right() && a.y() < b.bottom() && b.y() < a.bottom();
  }

  /** 可改停的股道：方块压在股道线上、靠站台块右端；找不到站台块时放在站台节点右边。股道节点不在图上的不画。 */
  private static List<ChoiceSpot> platforms(YardGeometry geometry, SignalboxState state) {
    List<ChoiceSpot> out = new ArrayList<>();
    for (SignalboxState.PlatformChoice choice : state.platforms()) {
      Optional<YardGeometry.Point> at = geometry.nodeAt(choice.node());
      if (at.isEmpty()) {
        continue;
      }
      YardGeometry.Point node = at.get();
      int center =
          geometry.platforms().stream()
              .filter(
                  platform ->
                      platform.left() <= node.x()
                          && node.x() < platform.right()
                          && (Math.abs(platform.top() - (node.y() + YardGeometry.TRACK_LINE))
                                  <= PLATFORM_REACH
                              || Math.abs(platform.bottom() - node.y()) <= PLATFORM_REACH))
              .findFirst()
              .map(platform -> platform.right() - CHOICE_INSET)
              .orElse(node.x() + CHOICE_INSET + CHOICE / 2);
      Box square =
          new Box(
              center - CHOICE / 2,
              node.y() + YardGeometry.TRACK_LINE / 2 - CHOICE / 2,
              CHOICE,
              CHOICE);
      out.add(new ChoiceSpot(choice, square, square.atLeast(MIN_TARGET)));
    }
    return out;
  }

  /** 列车表画得下的几行；选中的车排在画不下的地方时顶替最后一行，按钮作用的那列车总在表上看得见。 */
  private static List<RowSpot> rows(PidsLayout.TrainList list, SignalboxState state) {
    List<RowSpot> out = new ArrayList<>();
    int count = Math.min(list.rows(), state.rows().size());
    List<SignalboxState.TrainRow> shown = new ArrayList<>(state.rows().subList(0, count));
    if (count > 0 && shown.stream().noneMatch(row -> isSelected(state, row.trainName()))) {
      state.rows().stream()
          .filter(row -> isSelected(state, row.trainName()))
          .findFirst()
          .ifPresent(row -> shown.set(count - 1, row));
    }
    for (int i = 0; i < count; i++) {
      SignalboxState.TrainRow row = shown.get(i);
      out.add(
          new RowSpot(
              row,
              new Box(list.x(), list.rowTop(i), list.width(), list.rowHeight()),
              isSelected(state, row.trainName())));
    }
    return out;
  }

  /** 平时按 {@link SignalboxState.Button} 的先后排成 2 列 × 3 行；回复呼叫时换成 1 列 × 4 行的四条固定回复。 */
  private static List<ButtonSpot> buttons(
      PidsLayout.SignalButtons widget, SignalboxView view, SignalboxState state) {
    SignalboxView.Labels labels = view.labels();
    List<ButtonSpot> out = new ArrayList<>();
    if (state.replying()) {
      SignalboxState.Reply[] replies = SignalboxState.Reply.values();
      for (int i = 0; i < replies.length; i++) {
        SignalboxState.Reply reply = replies[i];
        out.add(
            new ButtonSpot(
                new ConsoleTarget.Answer(reply),
                new Box(
                    widget.x(),
                    widget.y() + i * (widget.replyHeight() + widget.gapY()),
                    widget.width(),
                    widget.replyHeight()),
                labels.replies().getOrDefault(reply, new Names(reply.name(), "")),
                true));
      }
      return out;
    }
    SignalboxState.Button[] buttons = SignalboxState.Button.values();
    for (int i = 0; i < buttons.length; i++) {
      SignalboxState.Button button = buttons[i];
      int column = i % 2;
      int row = i / 2;
      Names label =
          button == SignalboxState.Button.MODE
              ? (state.mode() == SignalboxState.Mode.MANUAL ? labels.toAuto() : labels.toManual())
              : button == SignalboxState.Button.AUTO_PASS && selectedAutoPass(state)
                  ? labels.cancelAutoPass()
                  : labels.buttons().getOrDefault(button, new Names(button.name(), ""));
      out.add(
          new ButtonSpot(
              new ConsoleTarget.Press(button),
              new Box(
                  widget.x() + column * (widget.buttonWidth() + widget.gapX()),
                  widget.y() + row * (widget.buttonHeight() + widget.gapY()),
                  widget.buttonWidth(),
                  widget.buttonHeight()),
              label,
              state.enabled(button)));
    }
    return out;
  }

  /** 选中的车要走的进路此刻设了自动通过（自动通过按钮这时写“取消自动通过”）。 */
  private static boolean selectedAutoPass(SignalboxState state) {
    return state.rows().stream()
        .anyMatch(row -> isSelected(state, row.trainName()) && row.autoPass());
  }

  /**
   * 点在 ({@code x}, {@code y}) 上是什么（右键）。先看标题栏的呼叫牌与操作区，再看列车表，最后看站场图（可改停的股道、有车在等或设了自动通过的入口信号、车次窗）；
   * 其余地方、没有控制台数据时都是取消选择。
   */
  ConsoleTarget target(int x, int y) {
    if (view.state().isEmpty()) {
      return new ConsoleTarget.Deselect();
    }
    if (call.isPresent() && call.get().box().atLeast(MIN_TARGET).contains(x, y)) {
      return new ConsoleTarget.CallBadge();
    }
    for (ButtonSpot button : buttons) {
      if (button.box().contains(x, y)) {
        return button.target();
      }
    }
    for (RowSpot row : rows) {
      if (row.box().contains(x, y)) {
        return new ConsoleTarget.Train(row.row().trainName());
      }
    }
    if (yard.isEmpty() || !inYard(yard.get(), x, y)) {
      return new ConsoleTarget.Deselect();
    }
    for (ChoiceSpot choice : platforms) {
      if (choice.hit().contains(x, y)) {
        return new ConsoleTarget.Platform(choice.choice().node());
      }
    }
    for (SignalSpot signal : signals) {
      if (!signal.hit().contains(x, y)) {
        continue;
      }
      if (signal.signal().waitingTrain().isPresent()) {
        return new ConsoleTarget.Train(signal.signal().waitingTrain().get());
      }
      if (signal.signal().autoPass()) {
        // 没有车在等、旁边写着 “A”：撤掉这个入口的自动通过
        return new ConsoleTarget.Signal(signal.signal().exit());
      }
    }
    // 后画的车次窗压在先画的上面：倒着找
    for (int i = describers.size() - 1; i >= 0; i--) {
      PidsYardPainter.Describer describer = describers.get(i);
      if (describerBox(describer).atLeast(MIN_TARGET).contains(x, y)) {
        return new ConsoleTarget.Train(describer.train().name());
      }
    }
    return new ConsoleTarget.Deselect();
  }

  private static boolean inYard(PidsLayout.Yard yard, int x, int y) {
    return x >= yard.x()
        && x < yard.x() + yard.width()
        && y >= yard.y()
        && y < yard.y() + yard.height();
  }

  static Box describerBox(PidsYardPainter.Describer describer) {
    return new Box(describer.x(), describer.y(), describer.width(), describer.height());
  }

  /** 这列车是选中的车（列车名不分大小写）。 */
  static boolean isSelected(SignalboxState state, String trainName) {
    return state.selected().map(selected -> selected.equalsIgnoreCase(trainName)).orElse(false);
  }

  /** 区间按无向规整后比较：控制台给的区间与站场图的区间端点先后可能不同。 */
  static EdgeId normalized(EdgeId edge) {
    return EdgeId.undirected(edge.a(), edge.b());
  }

  /** 一组区间规整后的集合。 */
  static Set<EdgeId> normalized(Set<EdgeId> edges) {
    return edges.stream().map(PidsSignalboxPlan::normalized).collect(Collectors.toSet());
  }

  SignalboxView view() {
    return view;
  }

  /** 站场图组件；布局里没有时为空。 */
  Optional<PidsLayout.Yard> yard() {
    return yard;
  }

  Optional<Item> mode() {
    return mode;
  }

  Optional<Item> signaller() {
    return signaller;
  }

  Optional<Item> call() {
    return call;
  }

  Optional<Item> notice() {
    return notice;
  }

  List<PidsYardPainter.Describer> describers() {
    return describers;
  }

  List<SignalSpot> signals() {
    return signals;
  }

  List<ChoiceSpot> platforms() {
    return platforms;
  }

  List<RowSpot> rows() {
    return rows;
  }

  List<ButtonSpot> buttons() {
    return buttons;
  }
}
