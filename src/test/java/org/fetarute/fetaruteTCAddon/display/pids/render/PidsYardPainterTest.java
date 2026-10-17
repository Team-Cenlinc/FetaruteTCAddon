package org.fetarute.fetaruteTCAddon.display.pids.render;

import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.P1;
import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.T1;
import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.cell;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.display.Lateness;
import org.fetarute.fetaruteTCAddon.display.pids.PidsGlyphForm;
import org.fetarute.fetaruteTCAddon.display.pids.fixtures.PidsFixtures;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Names;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsYardView;
import org.fetarute.fetaruteTCAddon.display.pids.yard.YardArea;
import org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures;
import org.fetarute.fetaruteTCAddon.display.pids.yard.YardGeometry;
import org.fetarute.fetaruteTCAddon.display.pids.yard.YardPicture;
import org.junit.jupiter.api.Test;

/** 站场图的像素：空闲、进路锁闭与车身各用各的颜色，只用调色板里的颜色。 */
class PidsYardPainterTest {

  private static final int LINE = 0xF28C28;
  private static final PidsYardView.Legend LEGEND =
      new PidsYardView.Legend(
          new Names("空闲", "Free"),
          new Names("进路锁闭", "Route locked"),
          new Names("保护占用", "Protected"),
          new Names("车占用", "Occupied"),
          new Names("车次窗", "Train"),
          new Names("车次窗文字琥珀色为晚点超过 1 分钟", "Train label: amber 1+ min late"));

  private final PidsRenderer renderer = new PidsRenderer(PidsFonts.builtIn(PidsGlyphForm.ZH_HANS));
  private final PidsLayout layout = PidsFixtures.builtInLayout("yard-3x5");

  @Test
  void tracksRoutesAndTrainsUseTheirOwnColours() {
    YardGeometry geometry = geometry();
    YardPicture picture =
        new YardPicture(
            Optional.of(geometry),
            List.of(new YardPicture.EdgeMark(EdgeId.undirected(T1, P1), YardPicture.Mark.ROUTE)),
            List.of(
                new YardPicture.TrainMark(
                    "1234",
                    Optional.of(LINE),
                    Lateness.ON_TIME,
                    List.of(
                        List.of(point(geometry, 30), point(geometry, 28), point(geometry, 26))))),
            Optional.empty());
    BufferedImage image = renderer.renderYard(layout, view(picture));

    YardGeometry.Point free = point(geometry, 10);
    YardGeometry.Point route = point(geometry, -15);
    YardGeometry.Point body = point(geometry, 28);
    assertEquals(PidsTheme.DARK.outline(), rgb(image, free), "没有占用的区间画空闲色");
    assertEquals(PidsTheme.DARK.text(), rgb(image, route), "进路锁闭画白色");
    assertEquals(PidsTheme.DARK.red(), rgb(image, body), "车身画红色");
  }

  @Test
  void onlyPaletteColoursAreUsed() {
    YardGeometry geometry = geometry();
    YardPicture picture =
        new YardPicture(
            Optional.of(geometry),
            List.of(new YardPicture.EdgeMark(EdgeId.undirected(T1, P1), YardPicture.Mark.HELD)),
            List.of(
                new YardPicture.TrainMark(
                    "1234",
                    Optional.of(LINE),
                    Lateness.LATE,
                    List.of(List.of(point(geometry, 20))))),
            Optional.empty());
    BufferedImage image = renderer.renderYard(layout, view(picture));

    PidsTheme theme = PidsTheme.DARK;
    Set<Integer> allowed =
        Set.of(
            theme.background(),
            theme.panel(),
            theme.text(),
            theme.muted(),
            theme.outline(),
            theme.inverseMuted(),
            theme.amber(),
            theme.red(),
            PidsYardPainter.HELD_DARK,
            LINE);
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        int rgb = image.getRGB(x, y) & 0xFFFFFF;
        assertTrue(allowed.contains(rgb), () -> "出现调色板外的颜色: " + Integer.toHexString(rgb));
      }
    }
  }

  @Test
  void aMissingYardShowsTheReasonInsteadOfTracks() {
    BufferedImage image =
        renderer.renderYard(
            layout,
            view(
                YardPicture.unavailable(YardPicture.Problem.GRAPH_NOT_READY),
                Optional.of(new Names("调度图未就绪", "Rail graph not ready"))));

    PidsLayout.Yard yard = layout.yard().orElseThrow();
    boolean text = false;
    for (int x = yard.x(); x < yard.x() + yard.width(); x++) {
      for (int y = yard.y() + yard.height() / 2 - 14; y < yard.y() + yard.height() / 2 + 14; y++) {
        text |= (image.getRGB(x, y) & 0xFFFFFF) == PidsTheme.DARK.text();
      }
    }
    assertTrue(text, "站场图中间写说明");
  }

  private static YardGeometry geometry() {
    SimpleRailGraph graph = YardFixtures.graph();
    YardArea area = YardArea.of(graph, "SURC", "ABC").orElseThrow();
    PidsLayout.Yard yard = PidsFixtures.builtInLayout("yard-3x5").yard().orElseThrow();
    return YardGeometry.of(
        graph,
        area,
        graph.interlockingState().footprintCellsByEdge(),
        new YardGeometry.Rect(
            yard.trackLeft(), yard.trackTop(), yard.trackWidth(), yard.trackHeight()),
        code -> code);
  }

  /** 1 道上东西坐标为 {@code x} 的方块在屏幕上的位置。 */
  private static YardGeometry.Point point(YardGeometry geometry, int x) {
    return geometry.pointOf(cell(x, 0, false)).orElseThrow();
  }

  private static int rgb(BufferedImage image, YardGeometry.Point point) {
    return image.getRGB(point.x(), point.y()) & 0xFFFFFF;
  }

  private static PidsYardView view(YardPicture picture) {
    return view(picture, Optional.empty());
  }

  /** 测试用的一帧：深色、14:32、站名“示例站”。 */
  private static PidsYardView view(YardPicture picture, Optional<Names> message) {
    return new PidsYardView(
        PidsTheme.DARK,
        "14:32",
        new Names("示例站", "Example"),
        LEGEND,
        picture,
        message,
        Optional.empty());
  }
}
