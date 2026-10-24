package org.fetarute.fetaruteTCAddon.display.pids.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.fetarute.fetaruteTCAddon.display.pids.PidsGlyphForm;
import org.fetarute.fetaruteTCAddon.display.pids.fixtures.SignalboxFixtures;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout;
import org.fetarute.fetaruteTCAddon.display.pids.view.SignalboxView;
import org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures;
import org.fetarute.fetaruteTCAddon.display.pids.yard.YardGeometry;
import org.fetarute.fetaruteTCAddon.signaller.console.ConsoleTarget;
import org.fetarute.fetaruteTCAddon.signaller.console.SignalboxState;
import org.junit.jupiter.api.Test;

/** 控制台的点击区：只按布局与视图推出，各区域点到对的东西，所有点击区不小于 16×16，没有控制台数据时一律取消选择。 */
class PidsSignalboxPlanTest {

  private final PidsFonts fonts = PidsFonts.builtIn(PidsGlyphForm.ZH_HANS);
  private final PidsRenderer renderer = new PidsRenderer(fonts);
  private final PidsLayout layout = SignalboxFixtures.layout();
  private final YardGeometry geometry = SignalboxFixtures.geometry(layout);

  @Test
  void rowsAndTheirEdgesPickTheirTrains() {
    SignalboxView view = view(SignalboxFixtures.state());
    PidsLayout.TrainList list = layout.trainList().orElseThrow();

    assertEquals(train(SignalboxFixtures.WAITING), at(view, list.x(), list.rowTop(0)), "第一行左上角");
    assertEquals(
        train(SignalboxFixtures.WAITING),
        at(view, list.x() + list.width() - 1, list.rowTop(1) - 1),
        "第一行右下角");
    assertEquals(
        train(SignalboxFixtures.TRAIN_A), at(view, list.x() + 200, list.rowTop(1)), "第二行顶边");
    assertEquals(train("train-c"), at(view, list.x() + 300, list.rowTop(3) + 12));
    assertEquals(new ConsoleTarget.Deselect(), at(view, list.x() + 10, list.y() + 2), "表头不是哪列车");
    assertEquals(
        new ConsoleTarget.Deselect(),
        at(view, list.x() + list.width(), list.rowTop(0) + 5),
        "列车表右边的空隙");
  }

  @Test
  void emptyRowsAreBlank() {
    SignalboxState state = SignalboxFixtures.state();
    SignalboxState two =
        new SignalboxState(
            state.mode(),
            state.signaller(),
            state.selected(),
            state.rows().subList(0, 2),
            state.buttons(),
            state.call(),
            state.replying(),
            state.notice(),
            state.preview(),
            state.pending(),
            state.signals(),
            state.platforms());
    PidsLayout.TrainList list = layout.trainList().orElseThrow();
    assertEquals(new ConsoleTarget.Deselect(), at(view(two), list.x() + 40, list.rowTop(2) + 5));
  }

  @Test
  void buttonsFollowTheDeclarationOrderAndGreyedOnesStillAnswer() {
    SignalboxView view = view(SignalboxFixtures.state());
    PidsLayout.SignalButtons widget = layout.signalButtons().orElseThrow();
    SignalboxState.Button[] buttons = SignalboxState.Button.values();
    for (int i = 0; i < buttons.length; i++) {
      int x = widget.x() + (i % 2) * (widget.buttonWidth() + widget.gapX());
      int y = widget.y() + (i / 2) * (widget.buttonHeight() + widget.gapY());
      ConsoleTarget expected = new ConsoleTarget.Press(buttons[i]);
      assertEquals(expected, at(view, x, y), buttons[i] + " 左上角");
      assertEquals(
          expected,
          at(view, x + widget.buttonWidth() - 1, y + widget.buttonHeight() - 1),
          buttons[i] + " 右下角");
    }
    // 取消进路此刻不能按（灰显），点了照样回传，由信号员模块回提示
    assertEquals(
        new ConsoleTarget.Press(SignalboxState.Button.CANCEL_ROUTE),
        at(view, widget.x() + widget.buttonWidth() + widget.gapX() + 50, widget.y() + 10));
    assertEquals(
        new ConsoleTarget.Deselect(),
        at(view, widget.x() + widget.buttonWidth() + 2, widget.y() + 10),
        "两列按钮之间的空隙");
  }

  @Test
  void replyingTurnsTheButtonsIntoFourReplies() {
    SignalboxState state = SignalboxFixtures.state();
    SignalboxState replying =
        new SignalboxState(
            state.mode(),
            state.signaller(),
            state.selected(),
            state.rows(),
            state.buttons(),
            state.call(),
            true,
            state.notice(),
            state.preview(),
            state.pending(),
            state.signals(),
            state.platforms());
    SignalboxView view = view(replying);
    PidsLayout.SignalButtons widget = layout.signalButtons().orElseThrow();
    SignalboxState.Reply[] replies = SignalboxState.Reply.values();
    assertEquals(4, replies.length);
    for (int i = 0; i < replies.length; i++) {
      int top = widget.y() + i * (widget.replyHeight() + widget.gapY());
      assertEquals(
          new ConsoleTarget.Answer(replies[i]), at(view, widget.x() + 1, top), replies[i] + " 顶边");
      assertEquals(
          new ConsoleTarget.Answer(replies[i]),
          at(view, widget.x() + widget.width() - 1, top + widget.replyHeight() - 1),
          replies[i] + " 底边");
    }
    assertTrue(widget.replyHeight() >= PidsSignalboxPlan.MIN_TARGET, "回复的点击区不小于 16 像素高");
  }

  @Test
  void trainOperationsTurnTheButtonsIntoThreeFullWidthRows() {
    SignalboxState state = SignalboxFixtures.state();
    SignalboxState trainOps =
        new SignalboxState(
            state.mode(),
            state.signaller(),
            state.selected(),
            state.rows(),
            state.buttons(),
            state.call(),
            false,
            state.notice(),
            state.preview(),
            state.pending(),
            state.signals(),
            state.platforms(),
            state.delegated(),
            Optional.of(new SignalboxState.TrainOps(false)));
    SignalboxView view = view(trainOps);
    PidsLayout.SignalButtons widget = layout.signalButtons().orElseThrow();
    SignalboxState.TrainAction[] actions = SignalboxState.TrainAction.values();
    for (int i = 0; i < actions.length; i++) {
      int top = widget.y() + i * (widget.buttonHeight() + widget.gapY());
      ConsoleTarget expected = new ConsoleTarget.Act(actions[i]);
      assertEquals(expected, at(view, widget.x() + 1, top), actions[i] + " 左上角");
      assertEquals(
          expected,
          at(view, widget.x() + widget.width() - 1, top + widget.buttonHeight() - 1),
          actions[i] + " 右下角");
    }
  }

  @Test
  void anArmedCancellationNamesTheTrainAndIsMarkedDangerous() {
    SignalboxState state = SignalboxFixtures.state();
    SignalboxState armed =
        new SignalboxState(
            state.mode(),
            state.signaller(),
            state.selected(),
            state.rows(),
            state.buttons(),
            state.call(),
            false,
            state.notice(),
            state.preview(),
            state.pending(),
            state.signals(),
            state.platforms(),
            state.delegated(),
            Optional.of(new SignalboxState.TrainOps(true)));
    PidsSignalboxPlan.ButtonSpot cancel =
        plan(armed).buttons().stream()
            .filter(
                spot ->
                    spot.target().equals(new ConsoleTarget.Act(SignalboxState.TrainAction.CANCEL)))
            .findFirst()
            .orElseThrow();

    assertEquals("确认取消 1234", cancel.label().primary());
    assertTrue(cancel.danger());
  }

  @Test
  void theSkipRowNamesTheStationAndTurnsIntoRevokeOnceOrdered() {
    assertEquals("越站 东湾", skipLabel(new SignalboxState.TrainOps(false, Optional.of("DEF"), false)));
    assertEquals(
        "撤销越站 东湾", skipLabel(new SignalboxState.TrainOps(false, Optional.of("DEF"), true)));
    assertEquals("越站", skipLabel(new SignalboxState.TrainOps(false)));
  }

  private String skipLabel(SignalboxState.TrainOps ops) {
    SignalboxState state = SignalboxFixtures.state();
    SignalboxState page =
        new SignalboxState(
            state.mode(),
            state.signaller(),
            state.selected(),
            state.rows(),
            state.buttons(),
            state.call(),
            false,
            state.notice(),
            state.preview(),
            state.pending(),
            state.signals(),
            state.platforms(),
            state.delegated(),
            Optional.of(ops));
    return plan(page).buttons().stream()
        .filter(
            spot -> spot.target().equals(new ConsoleTarget.Act(SignalboxState.TrainAction.SKIP)))
        .findFirst()
        .orElseThrow()
        .label()
        .primary();
  }

  @Test
  void aTrainOrderedToSkipSaysWhichStationInItsStatus() {
    SignalboxState.TrainRow row =
        new SignalboxState.TrainRow(
            "t",
            "1234",
            Optional.empty(),
            Optional.empty(),
            SignalboxState.RowStatus.IN_ZONE,
            0,
            OptionalLong.empty(),
            0.0,
            false,
            false,
            Optional.of("DEF"));

    assertEquals("区内运行 · 越站东湾", PidsSignalboxPlan.statusText(view(SignalboxFixtures.state()), row));
  }

  @Test
  void theHoldKeySaysReleaseWhileTheSelectedTrainIsHeld() {
    SignalboxState state = SignalboxFixtures.state();
    assertEquals("扣车", holdLabel(state));

    List<SignalboxState.TrainRow> rows =
        state.rows().stream()
            .map(
                row ->
                    row.trainName().equals(SignalboxFixtures.TRAIN_A)
                        ? new SignalboxState.TrainRow(
                            row.trainName(),
                            row.label(),
                            row.lineColor(),
                            row.destination(),
                            SignalboxState.RowStatus.HELD,
                            35,
                            row.delaySeconds(),
                            row.autoProgress(),
                            row.autoPass(),
                            row.autoRouted())
                        : row)
            .toList();
    SignalboxState held =
        new SignalboxState(
            state.mode(),
            state.signaller(),
            state.selected(),
            rows,
            state.buttons(),
            state.call(),
            state.replying(),
            state.notice(),
            state.preview(),
            state.pending(),
            state.signals(),
            state.platforms());
    assertEquals("放行", holdLabel(held));
  }

  private String holdLabel(SignalboxState state) {
    return plan(state).buttons().stream()
        .filter(spot -> spot.target().equals(new ConsoleTarget.Press(SignalboxState.Button.HOLD)))
        .findFirst()
        .orElseThrow()
        .label()
        .primary();
  }

  @Test
  void theCallBadgeStartsAReply() {
    PidsSignalboxPlan plan = plan(SignalboxFixtures.state());
    PidsSignalboxPlan.Box badge = plan.call().orElseThrow().box();
    assertEquals(
        new ConsoleTarget.CallBadge(),
        at(view(SignalboxFixtures.state()), badge.x() + 2, badge.y() + 2));
    assertTrue(plan.mode().orElseThrow().box().right() <= badge.x(), "呼叫牌在模式牌后面");

    SignalboxState state = SignalboxFixtures.state();
    SignalboxState quiet =
        new SignalboxState(
            state.mode(),
            state.signaller(),
            state.selected(),
            state.rows(),
            state.buttons(),
            Optional.empty(),
            false,
            Optional.empty(),
            state.preview(),
            state.pending(),
            state.signals(),
            state.platforms());
    assertEquals(
        new ConsoleTarget.Deselect(), at(view(quiet), badge.x() + 2, badge.y() + 2), "没有呼叫时那里是空白");
  }

  @Test
  void aSignalWithAWaitingTrainSelectsIt() {
    PidsSignalboxPlan plan = plan(SignalboxFixtures.state());
    assertEquals(2, plan.signals().size(), "两架入口信号都找到了出口区间");
    PidsSignalboxPlan.SignalSpot west =
        plan.signals().stream()
            .filter(s -> s.signal().exit().equals(SignalboxFixtures.WEST_ENTRY))
            .findFirst()
            .orElseThrow();
    PidsSignalboxPlan.Box square = west.square();
    assertEquals(PidsSignalboxPlan.SIGNAL, square.width());
    SignalboxView view = view(SignalboxFixtures.state());
    assertEquals(train(SignalboxFixtures.WAITING), at(view, square.x() + 3, square.y() + 3));
    // 方块 8×8，点击区 16×16：方块外 4 像素仍然点得到
    assertEquals(train(SignalboxFixtures.WAITING), at(view, square.x() - 4, square.y() - 4));
    assertEquals(
        train(SignalboxFixtures.WAITING), at(view, square.right() + 3, square.bottom() + 3));
    // 入口信号画在区内那一端（道岔 SW1）朝出口一侧
    YardGeometry.Point inner =
        geometry.stubs().stream()
            .filter(s -> s.edge().equals(SignalboxFixtures.WEST_ENTRY))
            .findFirst()
            .orElseThrow()
            .inner();
    assertTrue(square.right() <= inner.x(), "西头出口朝左，信号在道岔左边");
    assertTrue(square.bottom() <= inner.y(), "信号在轨道线上方");

    PidsSignalboxPlan.SignalSpot east =
        plan.signals().stream()
            .filter(s -> s.signal().exit().equals(SignalboxFixtures.EAST_ENTRY))
            .findFirst()
            .orElseThrow();
    assertTrue(east.letter().isPresent(), "东头设了自动通过，写 A");
    PidsSignalboxPlan.Box blank = east.square();
    assertEquals(
        new ConsoleTarget.Signal(SignalboxFixtures.EAST_ENTRY),
        at(view, blank.x() + 2, blank.y() + 2),
        "没有车在等、设了自动通过的信号：撤掉这个入口的自动通过");
  }

  /** 选中的车排在列车表画不下的地方时顶替最后一行：按钮作用的那列车总看得见。 */
  @Test
  void theSelectedTrainStaysVisibleBeyondTheLastRow() {
    SignalboxState base = SignalboxFixtures.state();
    List<SignalboxState.TrainRow> rows = new ArrayList<>(base.rows());
    for (int i = 0; i < 3; i++) {
      rows.add(
          new SignalboxState.TrainRow(
              "extra-" + i,
              "E" + i,
              Optional.empty(),
              Optional.empty(),
              SignalboxState.RowStatus.IN_ZONE,
              0,
              OptionalLong.empty(),
              0.0,
              false,
              false));
    }

    PidsSignalboxPlan plan = plan(copy(base, rows, Optional.of("extra-2")));

    assertEquals(4, plan.rows().size());
    assertEquals(SignalboxFixtures.WAITING, plan.rows().get(0).row().trainName());
    assertEquals("extra-2", plan.rows().get(3).row().trainName());
    assertTrue(plan.rows().get(3).selected());
  }

  /** 选中车的进路已设自动通过时，自动通过按钮写“取消自动通过”。 */
  @Test
  void theAutoPassButtonOffersToCancelWhenTheSelectedRouteHasIt() {
    SignalboxState base = SignalboxFixtures.state();

    String plain = autoPassLabel(plan(base));
    String set =
        autoPassLabel(plan(copy(base, base.rows(), Optional.of(SignalboxFixtures.TRAIN_B))));

    assertEquals("自动通过", plain);
    assertEquals("取消自动通过", set);
  }

  private static String autoPassLabel(PidsSignalboxPlan plan) {
    return plan.buttons().stream()
        .filter(
            button ->
                button.target().equals(new ConsoleTarget.Press(SignalboxState.Button.AUTO_PASS)))
        .findFirst()
        .orElseThrow()
        .label()
        .primary();
  }

  private static SignalboxState copy(
      SignalboxState state, List<SignalboxState.TrainRow> rows, Optional<String> selected) {
    return new SignalboxState(
        state.mode(),
        state.signaller(),
        selected,
        rows,
        state.buttons(),
        state.call(),
        state.replying(),
        state.notice(),
        state.preview(),
        state.pending(),
        state.signals(),
        state.platforms());
  }

  @Test
  void platformChoicesChangeTheTrack() {
    PidsSignalboxPlan plan = plan(SignalboxFixtures.state());
    assertEquals(2, plan.platforms().size());
    SignalboxView view = view(SignalboxFixtures.state());
    for (PidsSignalboxPlan.ChoiceSpot spot : plan.platforms()) {
      PidsSignalboxPlan.Box square = spot.square();
      assertEquals(
          new ConsoleTarget.Platform(spot.choice().node()),
          at(view, square.x() + square.width() / 2, square.y() + square.height() / 2));
      YardGeometry.Point node = geometry.nodeAt(spot.choice().node()).orElseThrow();
      assertTrue(square.y() <= node.y() && node.y() < square.bottom(), "方块压在股道线上：" + spot.choice());
    }
    assertEquals(
        List.of(YardFixtures.P1, YardFixtures.P2),
        plan.platforms().stream().map(spot -> spot.choice().node()).toList());
  }

  @Test
  void describersSelectTheirTrainAndBlankYardDeselects() {
    PidsSignalboxPlan plan = plan(SignalboxFixtures.state());
    SignalboxView view = view(SignalboxFixtures.state());
    assertEquals(2, plan.describers().size());
    for (PidsYardPainter.Describer describer : plan.describers()) {
      assertEquals(
          train(describer.train().name()),
          at(view, describer.x() + describer.width() / 2, describer.y() + 1),
          describer.text());
    }
    PidsLayout.Yard yard = layout.yard().orElseThrow();
    assertEquals(
        new ConsoleTarget.Deselect(),
        at(view, yard.x() + yard.width() - 3, yard.y() + yard.height() - 3),
        "站场图右下角空白");
    assertEquals(new ConsoleTarget.Deselect(), at(view, 300, 2), "标题栏空白");
  }

  @Test
  void everyTargetIsAtLeastSixteenSquare() {
    PidsSignalboxPlan plan = plan(SignalboxFixtures.state());
    List<PidsSignalboxPlan.Box> targets = new ArrayList<>();
    plan.call().ifPresent(item -> targets.add(item.box().atLeast(PidsSignalboxPlan.MIN_TARGET)));
    plan.buttons().forEach(button -> targets.add(button.box()));
    plan.rows().forEach(row -> targets.add(row.box()));
    plan.signals().forEach(signal -> targets.add(signal.hit()));
    plan.platforms().forEach(choice -> targets.add(choice.hit()));
    plan.describers()
        .forEach(
            describer ->
                targets.add(
                    PidsSignalboxPlan.describerBox(describer)
                        .atLeast(PidsSignalboxPlan.MIN_TARGET)));
    assertEquals(1 + 6 + 4 + 2 + 2 + 2, targets.size());
    for (PidsSignalboxPlan.Box box : targets) {
      assertTrue(
          box.width() >= PidsSignalboxPlan.MIN_TARGET
              && box.height() >= PidsSignalboxPlan.MIN_TARGET,
          () -> "点击区太小: " + box);
    }
  }

  @Test
  void withoutConsoleDataEverythingDeselects() {
    SignalboxView view =
        SignalboxFixtures.view(
            SignalboxFixtures.yardView(SignalboxFixtures.picture(geometry)), Optional.empty());
    for (int y = 0; y < layout.height(); y += 7) {
      for (int x = 0; x < layout.width(); x += 7) {
        assertEquals(new ConsoleTarget.Deselect(), renderer.signalboxTarget(layout, view, x, y));
      }
    }
  }

  @Test
  void theHeaderShortensTheSignallerBeforeDroppingTheBadges() {
    SignalboxState state = SignalboxFixtures.state();
    SignalboxState crowded =
        new SignalboxState(
            state.mode(),
            Optional.of("AVeryLongSignallerNameThatDoesNotFit"),
            state.selected(),
            state.rows(),
            state.buttons(),
            state.call(),
            false,
            Optional.of(
                new SignalboxState.Notice(SignalboxState.NoticeKind.NO_ROUTE, Optional.empty())),
            state.preview(),
            state.pending(),
            state.signals(),
            state.platforms());
    PidsSignalboxPlan plan = plan(crowded);
    assertTrue(plan.mode().isPresent());
    assertTrue(plan.call().isPresent());
    PidsSignalboxPlan.Box call = plan.call().orElseThrow().box();
    plan.notice().ifPresent(notice -> assertTrue(call.right() <= notice.box().x(), "提示不压呼叫牌"));
    plan.signaller()
        .ifPresent(signaller -> assertTrue(signaller.box().right() <= call.x(), "值守者不压呼叫牌"));
    PidsLayout.Clock clock =
        layout.widgets().stream()
            .filter(PidsLayout.Clock.class::isInstance)
            .map(PidsLayout.Clock.class::cast)
            .findFirst()
            .orElseThrow();
    int clockLeft = clock.x() - new PidsTextMeasure(fonts, layout.boldFrom()).width("14:32", 20);
    plan.notice().ifPresent(notice -> assertTrue(notice.box().right() <= clockLeft, "提示不压时钟"));
  }

  private PidsSignalboxPlan plan(SignalboxState state) {
    return PidsSignalboxPlan.of(layout, view(state), new PidsTextMeasure(fonts, layout.boldFrom()));
  }

  private SignalboxView view(SignalboxState state) {
    return SignalboxFixtures.view(
        SignalboxFixtures.yardView(SignalboxFixtures.picture(geometry)), Optional.of(state));
  }

  private ConsoleTarget at(SignalboxView view, int x, int y) {
    return renderer.signalboxTarget(layout, view, x, y);
  }

  private static ConsoleTarget train(String name) {
    return new ConsoleTarget.Train(name);
  }
}
