package org.fetarute.fetaruteTCAddon.display.pids.render;

import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.P1;
import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.SW2;
import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.T1;
import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.cell;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
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

/**
 * 只读站场图屏（{@code yard-3x5}、{@code yard-4x8}）的画面逐像素锁定：信号员控制台的叠加层只画在 {@code signalbox-*} 布局上，
 * 站场图的几何与画法为它增加的东西不许改变只读站场图屏的任何一个像素。
 *
 * <p>摘要是整帧 RGB 的 SHA-256。画法有意改动时，确认新画面后再更新这里的摘要。
 */
class PidsYardPixelLockTest {

  private static final int LINE = 0xF28C28;

  private final PidsRenderer renderer = new PidsRenderer(PidsFonts.builtIn(PidsGlyphForm.ZH_HANS));

  @Test
  void stationYardOn3x5IsUnchanged() {
    PidsLayout layout = PidsFixtures.builtInLayout("yard-3x5");
    YardGeometry geometry =
        geometry(layout, YardFixtures.graph(), YardArea.of(YardFixtures.graph(), "SURC", "ABC"));
    YardPicture picture =
        new YardPicture(
            Optional.of(geometry),
            List.of(
                new YardPicture.EdgeMark(EdgeId.undirected(T1, P1), YardPicture.Mark.ROUTE),
                new YardPicture.EdgeMark(EdgeId.undirected(P1, SW2), YardPicture.Mark.HELD)),
            List.of(
                new YardPicture.TrainMark(
                    "1234",
                    Optional.of(LINE),
                    Lateness.ON_TIME,
                    List.of(List.of(at(geometry, 30), at(geometry, 28), at(geometry, 26)))),
                new YardPicture.TrainMark(
                    "G5678",
                    Optional.empty(),
                    Lateness.LATE,
                    List.of(List.of(at(geometry, -10), at(geometry, -12)))),
                new YardPicture.TrainMark(
                    "9001",
                    Optional.of(0x3366CC),
                    Lateness.SEVERELY_LATE,
                    List.of(List.of(at(geometry, -8))))),
            Optional.empty());

    assertEquals(
        "8542bd71d04988d314fb26fc3a145b19d12daf367549e7c0569ddcb1d02fa007",
        digest(renderer.renderYard(layout, view(PidsTheme.DARK, picture, Optional.empty()))),
        "yard-3x5 深色：轨道、占用、车身与车次窗");
    assertEquals(
        "ad573c888812320991ba5c7ddfdded3ec220ec61defbe2353e48650146ab0426",
        digest(renderer.renderYard(layout, view(PidsTheme.LIGHT, picture, Optional.empty()))),
        "yard-3x5 浅色");
  }

  @Test
  void zoneYardWithDepotsOn4x8IsUnchanged() {
    PidsLayout layout = PidsFixtures.builtInLayout("yard-4x8");
    for (int tracks : new int[] {2, 5}) {
      SimpleRailGraph graph = YardFixtures.depot(tracks);
      YardGeometry geometry =
          geometry(
              layout,
              graph,
              YardArea.of(
                  graph,
                  "SURC",
                  List.of(YardArea.Member.station("ABC"), YardArea.Member.depot("DEP"))));
      YardPicture picture =
          new YardPicture(Optional.of(geometry), List.of(), List.of(), Optional.empty());
      assertEquals(
          tracks == 2
              ? "5fa12d02a95084cfef20bc3e83023680d58ef679d0499cceae0e037b265c9351"
              : "7746fa6b53bb9fdc60c2f2f6383b6ce1a3b7c6a6ad9d273227ae9aebe322c11b",
          digest(
              renderer.renderYard(
                  layout,
                  view(
                      PidsTheme.DARK,
                      picture,
                      Optional.of(new Names("部分区间缺少轨道几何", "Some trains may be missing"))))),
          "yard-4x8 车库 " + tracks + " 条库线");
    }
  }

  @Test
  void corridorAndMessagesAreUnchanged() {
    PidsLayout small = PidsFixtures.builtInLayout("yard-3x5");
    SimpleRailGraph graph = YardFixtures.corridor();
    YardGeometry geometry =
        geometry(
            small,
            graph,
            YardArea.of(
                graph,
                "SURC",
                List.of(
                    YardArea.Member.station("ABC"),
                    YardArea.Member.station("DEF"),
                    YardArea.Member.depot("DEP"))));
    assertEquals(
        "d6203c133b4fea95fdd4387311f3112eba3fb5dec61be26372e87fb343d8e0fc",
        digest(
            renderer.renderYard(
                small,
                view(
                    PidsTheme.DARK,
                    new YardPicture(Optional.of(geometry), List.of(), List.of(), Optional.empty()),
                    Optional.empty()))),
        "yard-3x5 控制区：两站一库");
    PidsLayout large = PidsFixtures.builtInLayout("yard-4x8");
    assertEquals(
        "a56cfb8f472d96dcf56d384d1804c84d83b40eb8a58467e19e44aaff7bec1571",
        digest(
            renderer.renderYard(
                large,
                new PidsYardView(
                    PidsTheme.LIGHT,
                    "09:05",
                    new Names("示例站", "Example"),
                    legend(),
                    new Names("<track> 道", "Track <track>"),
                    new Names("<depot>车库", "<depot> Depot"),
                    new Names("库 <track>", "Road <track>"),
                    YardPicture.unavailable(YardPicture.Problem.BUILDING),
                    Optional.of(new Names("正在绘制站场图", "Drawing the yard")),
                    Optional.empty()))),
        "yard-4x8 画不出来时的说明");
  }

  private static YardGeometry geometry(
      PidsLayout layout, SimpleRailGraph graph, Optional<YardArea> area) {
    PidsLayout.Yard yard = layout.yard().orElseThrow();
    return YardGeometry.of(
        graph,
        area.orElseThrow(),
        graph.interlockingState().footprintCellsByEdge(),
        new YardGeometry.Rect(
            yard.trackLeft(), yard.trackTop(), yard.trackWidth(), yard.trackHeight()),
        code -> code.equals("DEF") ? "东湾" : code);
  }

  /** 1 道上东西坐标为 {@code x} 的方块在屏幕上的位置。 */
  private static YardGeometry.Point at(YardGeometry geometry, int x) {
    return geometry.pointOf(cell(x, 0, false)).orElseThrow();
  }

  private static PidsYardView view(PidsTheme theme, YardPicture picture, Optional<Names> note) {
    return new PidsYardView(
        theme,
        "14:32",
        new Names("示例站", "Example"),
        legend(),
        new Names("<track> 道", "Track <track>"),
        new Names("<depot>车库", "<depot> Depot"),
        new Names("库 <track>", "Road <track>"),
        picture,
        Optional.empty(),
        note);
  }

  private static PidsYardView.Legend legend() {
    return new PidsYardView.Legend(
        new Names("空闲", "Free"),
        new Names("进路锁闭", "Route locked"),
        new Names("保护占用", "Protected"),
        new Names("车占用", "Occupied"),
        new Names("车次窗", "Train"),
        new Names("站台", "Platform"),
        new Names("车次窗文字琥珀色为晚点超过 1 分钟", "Train label: amber 1+ min late"));
  }

  private static String digest(BufferedImage image) {
    try {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      ByteBuffer buffer = ByteBuffer.allocate(8 + image.getWidth() * image.getHeight() * 4);
      buffer.putInt(image.getWidth()).putInt(image.getHeight());
      for (int y = 0; y < image.getHeight(); y++) {
        for (int x = 0; x < image.getWidth(); x++) {
          buffer.putInt(image.getRGB(x, y) & 0xFFFFFF);
        }
      }
      return HexFormat.of().formatHex(sha.digest(buffer.array()));
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
