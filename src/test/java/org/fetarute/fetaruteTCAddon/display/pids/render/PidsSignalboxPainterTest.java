package org.fetarute.fetaruteTCAddon.display.pids.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.display.pids.PidsGlyphForm;
import org.fetarute.fetaruteTCAddon.display.pids.fixtures.SignalboxFixtures;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout;
import org.fetarute.fetaruteTCAddon.display.pids.map.PidsMapPalette;
import org.fetarute.fetaruteTCAddon.display.pids.view.SignalboxView;
import org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures;
import org.fetarute.fetaruteTCAddon.display.pids.yard.YardGeometry;
import org.fetarute.fetaruteTCAddon.display.pids.yard.YardPicture;
import org.fetarute.fetaruteTCAddon.signaller.console.SignalboxState;
import org.junit.jupiter.api.Test;

/** 信号员控制台的画面：各种状态都画得出、只用调色板里的颜色，信号、预显示、选中框、进度条与灰显按钮各用各的颜色。 */
class PidsSignalboxPainterTest {

  /** 夹具里各列车的线路色。 */
  private static final Set<Integer> LINE_COLORS = Set.of(0xF28C28, 0x3366CC, 0x7FCC19);

  private final PidsFonts fonts = PidsFonts.builtIn(PidsGlyphForm.ZH_HANS);
  private final PidsRenderer renderer = new PidsRenderer(fonts);
  private final PidsLayout layout = SignalboxFixtures.layout();
  private final YardGeometry geometry = SignalboxFixtures.geometry(layout);

  @Test
  void everyKindOfStateRendersWithPaletteColoursOnly() {
    SignalboxState base = SignalboxFixtures.state();
    PidsMapPalette palette = PidsMapPalette.minecraft();
    for (PidsTheme theme : List.of(PidsTheme.DARK, PidsTheme.LIGHT)) {
      for (SignalboxState state : variants(base)) {
        for (Optional<SignalboxState> shown :
            List.of(Optional.of(state), Optional.<SignalboxState>empty())) {
          for (YardPicture picture :
              List.of(
                  SignalboxFixtures.picture(geometry),
                  YardPicture.unavailable(YardPicture.Problem.BUILDING))) {
            BufferedImage image =
                renderer.renderSignalbox(
                    layout,
                    SignalboxFixtures.view(SignalboxFixtures.yardView(theme, picture), shown));
            assertEquals(640, image.getWidth());
            assertEquals(384, image.getHeight());
            for (int y = 0; y < image.getHeight(); y++) {
              for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y) & 0xFFFFFF;
                // 线路色是数据里的颜色，换算成地图帧时才取最近色
                assertTrue(
                    LINE_COLORS.contains(rgb) || rgb == palette.rgb(palette.code(rgb)),
                    () -> "出现调色板外的颜色: " + Integer.toHexString(rgb));
              }
            }
          }
        }
      }
    }
  }

  @Test
  void signalsShowTheirAspectsAndTheSelectionIsFramed() {
    SignalboxView view = view(SignalboxFixtures.state());
    BufferedImage image = renderer.renderSignalbox(layout, view);
    PidsSignalboxPlan plan =
        PidsSignalboxPlan.of(layout, view, new PidsTextMeasure(fonts, layout.boldFrom()));
    PidsTheme theme = PidsTheme.DARK;
    Map<EdgeId, Integer> expected =
        Map.of(
            SignalboxFixtures.WEST_ENTRY, theme.red(), SignalboxFixtures.EAST_ENTRY, theme.green());
    for (PidsSignalboxPlan.SignalSpot spot : plan.signals()) {
      PidsSignalboxPlan.Box square = spot.square();
      assertEquals(
          expected.get(spot.signal().exit()).intValue(),
          rgb(image, square.x() + 4, square.y() + 4),
          "入口信号 " + spot.signal().aspect());
    }
    PidsYardPainter.Describer selected =
        plan.describers().stream()
            .filter(d -> d.train().name().equals(SignalboxFixtures.TRAIN_A))
            .findFirst()
            .orElseThrow();
    assertEquals(theme.amber(), rgb(image, selected.x() - 2, selected.y() + 2), "选中车的车次窗加琥珀色框");
    PidsYardPainter.Describer other =
        plan.describers().stream()
            .filter(d -> d.train().name().equals(SignalboxFixtures.TRAIN_B))
            .findFirst()
            .orElseThrow();
    assertNotEquals(theme.amber(), rgb(image, other.x() - 2, other.y() + 2), "没选中的不加框");
  }

  /** 让给小区（车站控制）的那一片围一圈琥珀色虚线框，没有让出时不画。 */
  @Test
  void delegatedAreasAreFramedInAmber() {
    SignalboxState base = SignalboxFixtures.state();
    SignalboxState delegated =
        new SignalboxState(
            base.mode(),
            base.signaller(),
            base.selected(),
            base.rows(),
            base.buttons(),
            base.call(),
            base.replying(),
            base.notice(),
            base.preview(),
            base.pending(),
            base.signals(),
            base.platforms(),
            List.of(
                new SignalboxState.Delegated(
                    "示例站 1 道", "Station", Set.of(YardFixtures.P1, YardFixtures.P2))));
    PidsLayout.Yard yard = layout.yard().orElseThrow();

    BufferedImage framed = renderer.renderSignalbox(layout, view(delegated));
    BufferedImage plain = renderer.renderSignalbox(layout, view(base));

    int framedAmber = 0;
    int plainAmber = 0;
    for (int x = yard.x(); x < yard.x() + yard.width(); x++) {
      for (int y = yard.y(); y < yard.y() + yard.height(); y++) {
        framedAmber += rgb(framed, x, y) == PidsTheme.DARK.amber() ? 1 : 0;
        plainAmber += rgb(plain, x, y) == PidsTheme.DARK.amber() ? 1 : 0;
      }
    }
    assertTrue(
        framedAmber - plainAmber >= 60, "多出一圈琥珀色虚线框与标注: " + plainAmber + " → " + framedAmber);
  }

  @Test
  void previewAndPendingRoutesAreDrawnUnderLockedRoutes() {
    SignalboxState base = SignalboxFixtures.state();
    EdgeId previewEdge = EdgeId.undirected(YardFixtures.T1, YardFixtures.P1);
    EdgeId pendingEdge = EdgeId.undirected(YardFixtures.SW1, YardFixtures.P2);
    SignalboxView view = view(base);
    BufferedImage image = renderer.renderSignalbox(layout, view);
    YardGeometry.Segment preview = horizontal(previewEdge);
    int middle = (preview.x0() + preview.x1()) / 2;
    assertEquals(PidsSignalboxPainter.PREVIEW_DARK, rgb(image, middle, preview.y0()), "计划进路画浅蓝");

    YardGeometry.Segment pending = horizontal(pendingEdge);
    int white = 0;
    int other = 0;
    for (int x = pending.x0(); x <= pending.x1(); x++) {
      if (rgb(image, x, pending.y0()) == PidsTheme.DARK.text()) {
        white++;
      } else {
        other++;
      }
    }
    assertTrue(white > 0 && other > 0, "排列中画虚线（亮暗相间）: 亮 " + white + " 暗 " + other);

    // 区间锁闭（占用标记）盖在预显示上面
    EdgeId locked = EdgeId.undirected(YardFixtures.P1, YardFixtures.SW2);
    SignalboxState both =
        new SignalboxState(
            base.mode(),
            base.signaller(),
            base.selected(),
            base.rows(),
            base.buttons(),
            base.call(),
            base.replying(),
            base.notice(),
            Set.of(locked),
            Set.of(),
            base.signals(),
            List.of());
    BufferedImage lockedImage = renderer.renderSignalbox(layout, view(both));
    YardGeometry.Segment route = horizontal(locked);
    assertEquals(
        PidsTheme.DARK.text(),
        rgb(lockedImage, (route.x0() + route.x1()) / 2 + 1, route.y0()),
        "进路锁闭盖在预显示上");
  }

  @Test
  void waitingRowsShowTheirProgressAndGreyedButtonsAreDimmed() {
    SignalboxView view = view(SignalboxFixtures.state());
    BufferedImage image = renderer.renderSignalbox(layout, view);
    PidsLayout.TrainList list = layout.trainList().orElseThrow();
    int barY = list.rowTop(0) + list.rowHeight() - 1;
    int length = (int) Math.round(list.width() * 0.6);
    assertEquals(PidsTheme.DARK.amber(), rgb(image, list.x() + 1, barY), "进度条从左起");
    assertEquals(PidsTheme.DARK.amber(), rgb(image, list.x() + length - 1, barY));
    assertNotEquals(PidsTheme.DARK.amber(), rgb(image, list.x() + length + 2, barY), "按进度画到六成");

    PidsLayout.SignalButtons widget = layout.signalButtons().orElseThrow();
    int enabledFrame = rgb(image, widget.x(), widget.y() + 10);
    int greyedFrame =
        rgb(image, widget.x() + widget.buttonWidth() + widget.gapX(), widget.y() + 10);
    assertEquals(PidsTheme.DARK.muted(), enabledFrame, "能按的按钮框");
    assertEquals(PidsTheme.DARK.inverseMuted(), greyedFrame, "灰显的按钮框");
  }

  @Test
  void manualModeBadgeIsAmberAndAutoIsGrey() {
    SignalboxView manual = view(SignalboxFixtures.state());
    PidsSignalboxPlan plan =
        PidsSignalboxPlan.of(layout, manual, new PidsTextMeasure(fonts, layout.boldFrom()));
    PidsSignalboxPlan.Box badge = plan.mode().orElseThrow().box();
    assertEquals(
        PidsTheme.DARK.amber(),
        rgb(renderer.renderSignalbox(layout, manual), badge.x() + 1, badge.y() + 1));
    SignalboxState base = SignalboxFixtures.state();
    SignalboxState auto =
        new SignalboxState(
            SignalboxState.Mode.AUTO,
            Optional.empty(),
            base.selected(),
            base.rows(),
            base.buttons(),
            Optional.empty(),
            false,
            Optional.empty(),
            base.preview(),
            base.pending(),
            base.signals(),
            base.platforms());
    assertEquals(
        PidsTheme.DARK.outline(),
        rgb(renderer.renderSignalbox(layout, view(auto)), badge.x() + 1, badge.y() + 1));
  }

  @Test
  void delaysAreWrittenAsSignedMinutesAndSeconds() {
    SignalboxView.Labels labels = SignalboxFixtures.vocabulary().signalboxLabels();
    PidsTheme theme = PidsTheme.DARK;
    assertEquals("正点", PidsSignalboxPainter.delay(labels, OptionalLong.of(0), theme).text());
    assertEquals("正点", PidsSignalboxPainter.delay(labels, OptionalLong.of(29), theme).text());
    assertEquals("+1:20", PidsSignalboxPainter.delay(labels, OptionalLong.of(80), theme).text());
    assertEquals("-0:30", PidsSignalboxPainter.delay(labels, OptionalLong.of(-30), theme).text());
    assertEquals(
        theme.red(), PidsSignalboxPainter.delay(labels, OptionalLong.of(400), theme).color());
    assertEquals(
        PidsSignalboxPainter.NO_DELAY,
        PidsSignalboxPainter.delay(labels, OptionalLong.empty(), theme).text());
  }

  /** 各种状态：回复呼叫、没人值守的自动模式、各种临时提示、各种列车状态、没有列车、超长文字。 */
  private static List<SignalboxState> variants(SignalboxState base) {
    List<SignalboxState> out = new java.util.ArrayList<>();
    out.add(base);
    out.add(with(base, true, Optional.empty(), base.rows()));
    for (SignalboxState.NoticeKind kind : SignalboxState.NoticeKind.values()) {
      out.add(
          with(
              base,
              false,
              Optional.of(new SignalboxState.Notice(kind, Optional.of("blocking-train"))),
              base.rows()));
    }
    List<SignalboxState.TrainRow> rows = new java.util.ArrayList<>();
    for (SignalboxState.RowStatus status : SignalboxState.RowStatus.values()) {
      rows.add(
          new SignalboxState.TrainRow(
              "t-" + status,
              "一个很长很长的车次号" + status.ordinal(),
              Optional.empty(),
              Optional.of("一个很长很长很长很长的站码"),
              status,
              123456,
              OptionalLong.of(-99999),
              2.5,
              true,
              true));
    }
    out.add(with(base, false, Optional.empty(), rows));
    out.add(with(base, false, Optional.empty(), List.of()));
    out.add(
        new SignalboxState(
            SignalboxState.Mode.AUTO,
            Optional.empty(),
            Optional.empty(),
            List.of(),
            Map.of(),
            Optional.empty(),
            false,
            Optional.empty(),
            Set.of(),
            Set.of(),
            List.of(
                new SignalboxState.EntrySignal(
                    SignalboxFixtures.WEST_ENTRY,
                    SignalboxState.Aspect.PENDING,
                    Optional.empty(),
                    true),
                new SignalboxState.EntrySignal(
                    EdgeId.undirected(YardFixtures.IB, YardFixtures.SW3),
                    SignalboxState.Aspect.STOP,
                    Optional.of("ghost"),
                    false)),
            List.of(
                new SignalboxState.PlatformChoice(
                    org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId.of("SURC:S:NOPE:9"),
                    "9",
                    false))));
    return out;
  }

  private static SignalboxState with(
      SignalboxState base,
      boolean replying,
      Optional<SignalboxState.Notice> notice,
      List<SignalboxState.TrainRow> rows) {
    return new SignalboxState(
        base.mode(),
        base.signaller(),
        base.selected(),
        rows,
        base.buttons(),
        base.call(),
        replying,
        notice,
        base.preview(),
        base.pending(),
        base.signals(),
        base.platforms());
  }

  /** 区间画出来的第一段水平线。 */
  private YardGeometry.Segment horizontal(EdgeId edge) {
    return geometry.paths().stream()
        .filter(path -> path.edge().equals(edge))
        .flatMap(path -> path.segments().stream())
        .filter(segment -> segment.y0() == segment.y1() && segment.x1() - segment.x0() > 8)
        .findFirst()
        .orElseThrow(() -> new AssertionError("区间没有水平线: " + edge));
  }

  private SignalboxView view(SignalboxState state) {
    return SignalboxFixtures.view(
        SignalboxFixtures.yardView(SignalboxFixtures.picture(geometry)), Optional.of(state));
  }

  private static int rgb(BufferedImage image, int x, int y) {
    return image.getRGB(x, y) & 0xFFFFFF;
  }
}
