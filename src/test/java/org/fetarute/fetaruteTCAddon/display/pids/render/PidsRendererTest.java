package org.fetarute.fetaruteTCAddon.display.pids.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.display.pids.PidsGlyphForm;
import org.fetarute.fetaruteTCAddon.display.pids.fixtures.PidsFixtures;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsNotice;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsNoticeView;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsTestCard;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsVacancyView;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Arrival;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.ArrivalMode;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Badge;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Destination;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Label;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Names;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.PlatformCell;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Tone;
import org.junit.jupiter.api.Test;

/**
 * 渲染器：同样的输入得到同样的像素；按布局坐标检查几处关键像素。
 *
 * <p>坐标取自内置布局 platform-1x3：到发表左缘 66、首行 4–56、到站列 256–376、色带 120–128。
 */
class PidsRendererTest {

  private static final int WS = 0x70DEEE;
  private static final PidsView.Labels LABELS =
      new PidsView.Labels(
          new Names("站台", "Platform"),
          new Names("分", "min"),
          new Names("暂无后续列车", "No further trains"),
          new Names("线路", "Line"),
          new Names("终点", "Destination"),
          new Names("站台", "Platform"),
          new Names("到站", "Arrival"));

  private final PidsRenderer renderer = new PidsRenderer(PidsFonts.builtIn(PidsGlyphForm.ZH_HANS));

  @Test
  void rendersAtTheLayoutSizeDeterministically() throws Exception {
    PidsLayout layout = PidsFixtures.builtInLayout("platform-1x3");
    PidsView view = platformView(PidsTheme.DARK, List.of(countdown(), countdown()));

    BufferedImage first = renderer.render(layout, view);
    BufferedImage second = renderer.render(layout, view);

    assertEquals(384, first.getWidth());
    assertEquals(128, first.getHeight());
    assertArrayEquals(pixels(first), pixels(second));
  }

  @Test
  void onlyPaletteColorsAreUsed() throws Exception {
    PidsLayout layout = PidsFixtures.builtInLayout("platform-1x3");
    BufferedImage image =
        renderer.render(layout, platformView(PidsTheme.DARK, List.of(countdown())));

    Set<Integer> allowed =
        Set.copyOf(
            List.of(
                PidsTheme.DARK.background(),
                PidsTheme.DARK.panel(),
                PidsTheme.DARK.text(),
                PidsTheme.DARK.muted(),
                PidsTheme.DARK.inverseBackground(),
                PidsTheme.DARK.inverseText(),
                WS,
                PidsTheme.INK));
    for (int rgb : pixels(image)) {
      assertTrue(
          allowed.contains(rgb & 0xFFFFFF),
          () -> "出现调色板外的颜色（抗锯齿未关闭？）: " + Integer.toHexString(rgb));
    }
  }

  @Test
  void keyRegionsHaveTheirColors() throws Exception {
    PidsLayout layout = PidsFixtures.builtInLayout("platform-1x3");
    PidsView view =
        platformView(
            PidsTheme.DARK,
            List.of(
                withArrival(
                    countdown(),
                    new Arrival(
                        ArrivalMode.HIGHLIGHT,
                        0,
                        Tone.NORMAL,
                        Optional.of(Label.of(new Names("进站", "Arriving"), Tone.NORMAL))))));

    BufferedImage image = renderer.render(layout, view);

    assertEquals(PidsTheme.DARK.background(), rgb(image, 2, 2), "画布底色");
    assertEquals(WS, rgb(image, 200, 124), "线路色带");
    assertEquals(PidsTheme.DARK.panel(), rgb(image, 200, 6), "首行面板");
    assertEquals(PidsTheme.DARK.inverseBackground(), rgb(image, 374, 6), "进站时到站格反白");
    assertEquals(PidsTheme.DARK.inverseBackground(), rgb(image, 9, 6), "站台号方块");
  }

  @Test
  void cancelledBadgeIsHollow() throws Exception {
    PidsLayout layout = PidsFixtures.builtInLayout("platform-1x3");
    PidsView.Row cancelled =
        new PidsView.Row(
            new Badge("WS", Optional.of("各停"), WS, true),
            new Destination(new Names("大港城", "The Port City"), Tone.MUTED, true),
            new PlatformCell("1", true),
            new Arrival(
                ArrivalMode.DASH,
                0,
                Tone.MUTED,
                Optional.of(Label.of(new Names("取消", "Cancelled"), Tone.RED))));

    BufferedImage image = renderer.render(layout, platformView(PidsTheme.DARK, List.of(cancelled)));

    // 首行色牌 42×28，在 62 宽的列内居中：左上角 (76, 16)。
    assertEquals(WS, rgb(image, 76, 16), "空心框边线");
    assertEquals(PidsTheme.DARK.panel(), rgb(image, 79, 19), "框内透出面板色");
  }

  @Test
  // 仿粗体只加在 20 号起的主文字上：时钟（20）变粗，12 号的状态词不变。
  void boldThickensLargeTextOnly() throws Exception {
    PidsLayout bold = PidsFixtures.builtInLayout("platform-1x3");
    PidsLayout regular =
        new PidsLayout(bold.id(), bold.name(), bold.tileRows(), bold.tileCols(), 0, bold.widgets());
    PidsView view = platformView(PidsTheme.DARK, List.of(countdown()));

    BufferedImage thick = renderer.render(bold, view);
    BufferedImage thin = renderer.render(regular, view);

    int text = PidsTheme.DARK.text();
    assertTrue(
        countColor(thick, 8, 92, 60, 20, text) > countColor(thin, 8, 92, 60, 20, text), "时钟加粗");
    assertEquals(
        countColor(thin, 300, 10, 70, 40, text),
        countColor(thick, 300, 10, 70, 40, text),
        "12 号状态词不加粗");
  }

  @Test
  // “严重晚点 6 分”在小行放不下：改用短写法，不能压到分钟数上。
  void longStatusFallsBackToItsCompactForm() throws Exception {
    PidsLayout layout = PidsFixtures.builtInLayout("platform-1x3");
    PidsView.Row severe =
        new PidsView.Row(
            new Badge("WS", Optional.of("各停"), WS, false),
            new Destination(new Names("南渡", "Nam Toa"), Tone.NORMAL, false),
            new PlatformCell("1", false),
            new Arrival(
                ArrivalMode.COUNTDOWN,
                12,
                Tone.NORMAL,
                Optional.of(
                    Label.of(new Names("严重晚点 6 分", "Late 6 min"), Tone.RED)
                        .withCompact(new Names("晚点 6 分", "Late 6 min")))));

    BufferedImage image =
        renderer.render(layout, platformView(PidsTheme.DARK, List.of(countdown(), severe)));

    // 第 2 行（58–86）：分钟数与单位在 262–300，状态必须在其右侧。
    int red = PidsTheme.DARK.red();
    assertEquals(0, countColor(image, 256, 58, 46, 28, red), "状态没有压到分钟数");
    assertTrue(countColor(image, 302, 58, 74, 28, red) > 0, "状态仍然显示");
  }

  @Test
  void emptySlotsShowTheNoMoreTrainsMessageOnce() throws Exception {
    PidsLayout layout = PidsFixtures.builtInLayout("platform-1x3");
    BufferedImage image =
        renderer.render(layout, platformView(PidsTheme.DARK, List.of(countdown())));

    // 第 2 行（58–86）的终点列有次要色文字，第 3 行（88–116）为空。
    assertTrue(countColor(image, 134, 58, 120, 28, PidsTheme.DARK.muted()) > 0);
    assertEquals(0, countColor(image, 134, 88, 120, 28, PidsTheme.DARK.muted()));
  }

  @Test
  void stationScreenRendersHeaderAndLightTheme() throws Exception {
    PidsLayout layout = PidsFixtures.builtInLayout("station-3x5");
    PidsView view =
        new PidsView(
            PidsTheme.LIGHT,
            "09:30",
            List.of(),
            Optional.of(new Names("新笛矢·壑湖", "Neo Fueya - Hor Huu")),
            List.of(
                new PidsView.LineChip("DS-01", 0xF6A000, new Names("探索线", "Discover Line")),
                new PidsView.LineChip("MT-08", 0xD920D9, new Names("大都会线", "Metropolitan Line"))),
            List.of(),
            List.of(countdown()),
            LABELS);

    BufferedImage image = renderer.render(layout, view);

    assertEquals(640, image.getWidth());
    assertEquals(384, image.getHeight());
    assertEquals(PidsTheme.LIGHT.background(), rgb(image, 2, 2));
    assertEquals(0xF6A000, rgb(image, 100, 70), "换乘色带左半");
    assertEquals(0xD920D9, rgb(image, 500, 70), "换乘色带右半");
    assertEquals(PidsTheme.LIGHT.panel(), rgb(image, 300, 120), "到发行面板");
    assertTrue(countColor(image, 579, 39, 49, 20, PidsTheme.LIGHT.text()) > 0, "右上角时钟");
    assertEquals(0, countColor(image, 629, 0, 11, 64, PidsTheme.LIGHT.text()), "时钟右对齐，不越过右缘");
  }

  @Test
  void testCardShowsTheTileGridNumbersAndHint() {
    PidsTestCard card =
        new PidsTestCard(
            new Names("站台屏待配置", "PIDS not configured"),
            List.of("布局：站台屏 1×3", "车站：新笛矢·壑湖 3 站台"),
            "手持配置棍右键屏幕进行配置",
            1,
            3);

    BufferedImage image = renderer.renderTestCard(card);

    assertEquals(384, image.getWidth());
    assertEquals(128, image.getHeight());
    assertEquals(PidsTheme.LIGHT.background(), rgb(image, 64, 126));
    assertEquals(PidsTheme.LIGHT.outline(), rgb(image, 128, 64), "块与块之间的格线");
    assertTrue(countColor(image, 360, 114, 20, 10, PidsTheme.LIGHT.muted()) > 0, "第 3 块编号");
    assertTrue(countColor(image, 8, 100, 200, 12, PidsTheme.LIGHT.amber()) > 0, "底部提示");
  }

  @Test
  void groupScreensDrawOneBoxPerPlatformAndAPlatformColumn() {
    PidsLayout layout = PidsFixtures.builtInLayout("platform-group-1x3");
    PidsView view =
        new PidsView(
            PidsTheme.DARK,
            "21:40",
            List.of("2", "3", "4"),
            Optional.empty(),
            List.of(),
            List.of(WS),
            List.of(countdown()),
            LABELS);

    BufferedImage image = renderer.render(layout, view);

    int box = PidsTheme.DARK.inverseBackground();
    assertEquals(box, rgb(image, 9, 6), "第一个站台方块");
    assertEquals(box, rgb(image, 37, 6), "第二个站台方块在右边");
    assertEquals(box, rgb(image, 9, 34), "第三个换到下一行");
    assertEquals(PidsTheme.DARK.background(), rgb(image, 37, 34), "只有三个站台");
    assertTrue(countColor(image, 66 + 62, 4, 26, 52, box) > 0, "首行到发在色牌后面写自己的站台号（反白方块）");
  }

  @Test
  void noticePagesDrawIconTitleBodyAndTheSameBand() {
    PidsLayout layout = PidsFixtures.builtInLayout("platform-1x3");

    BufferedImage order =
        renderer.renderNotice(
            layout,
            new PidsNoticeView(
                PidsTheme.DARK,
                PidsNotice.ORDER,
                new Names("先下后上", "Off first, then on"),
                new Names("请让乘客先下车", "Let passengers off first"),
                List.of(WS)));
    BufferedImage passing =
        renderer.renderNotice(
            layout,
            new PidsNoticeView(
                PidsTheme.DARK,
                PidsNotice.PASSING,
                new Names("列车通过", "Train passing"),
                new Names("请勿靠近站台边缘", "Stand clear of the platform edge"),
                List.of(WS)));

    assertEquals(384, order.getWidth());
    // 图标块 64×64，在第一块地图、色带以上的范围内居中：(32, 28)
    assertEquals(PidsTheme.DARK.info(), rgb(order, 33, 29), "宣传页用信息色");
    assertTrue(countColor(order, 32, 28, 64, 64, PidsTheme.PAPER) > 0, "深底上用浅色图标");
    assertTrue(countColor(order, 136, 20, 112, 80, PidsTheme.DARK.text()) > 0, "标题在中间");
    assertTrue(countColor(order, 264, 20, 112, 80, PidsTheme.DARK.muted()) > 0, "说明在最后一块");
    assertEquals(WS, rgb(order, 200, 124), "色带与主页同一位置");
    assertEquals(PidsTheme.DARK.amber(), rgb(passing, 33, 29), "安全提示用警示色");
    assertTrue(countColor(passing, 32, 28, 64, 64, PidsTheme.INK) > 0, "亮黄底上用深色图标");
  }

  /** 空位页：车厢按座位情况着色（充足绿、紧张红），车头一节在前进方向一端挖小窗；3 节这样的短编组不拉宽、整列居中；站台线与图例都在， 色带与主页同一位置。 */
  @Test
  void vacancyPageDrawsTheTrainThePlatformAndTheLegend() throws Exception {
    PidsLayout layout = PidsFixtures.builtInLayout("platform-1x3");
    PidsVacancyView vacancy =
        new PidsVacancyView(
            PidsTheme.DARK,
            new Badge("WS", Optional.of("各停"), WS, false),
            new Names("南渡", "Nam Toa"),
            new Arrival(
                ArrivalMode.COUNTDOWN,
                3,
                Tone.NORMAL,
                Optional.of(Label.of(new Names("准点", "On time"), Tone.NORMAL))),
            List.of(
                new PidsVacancyView.Car(PidsVacancyView.Level.MANY, 8),
                new PidsVacancyView.Car(PidsVacancyView.Level.SOME, 3),
                new PidsVacancyView.Car(PidsVacancyView.Level.FEW, 0)),
            Optional.of(PidsVacancyView.Front.RIGHT),
            new PidsVacancyView.Labels(
                new Names("分", "min"),
                new Names("请优先考虑较空的车厢", "Please use less crowded cars"),
                new Names("座位充足", "Many seats"),
                new Names("座位较少", "Some seats"),
                new Names("座位紧张", "Few seats")),
            List.of(WS));

    BufferedImage image = renderer.renderVacancy(layout, vacancy);

    assertEquals(384, image.getWidth());
    assertTrue(countColor(image, 300, 4, 76, 30, PidsTheme.DARK.text()) > 0, "右上写多久到达");
    // 车厢行 44–68；三节各 42 宽、间距 4，整列 134 宽居中于 8–376：125–259
    assertEquals(PidsTheme.DARK.background(), rgb(image, 100, 56), "短编组不拉宽：左侧空着");
    assertEquals(PidsTheme.DARK.red(), rgb(image, 140, 60), "车头在右：最后一节（紧张，红）在左");
    assertEquals(PidsTheme.DARK.green(), rgb(image, 225, 60), "车头一节（充足，绿）在右");
    assertEquals(PidsTheme.DARK.background(), rgb(image, 254, 49), "车头小窗");
    assertTrue(countColor(image, 8, 71, 368, 2, PidsTheme.DARK.muted()) > 300, "站台线");
    assertTrue(countColor(image, 8, 88, 368, 24, PidsTheme.DARK.text()) > 0, "提示与图例");
    assertEquals(WS, rgb(image, 200, 124), "色带与主页同一位置");
  }

  /** 站台变更过的站台方块用琥珀色。 */
  @Test
  void aChangedPlatformBoxIsAmber() throws Exception {
    PidsLayout layout = PidsFixtures.builtInLayout("station-3x5");
    PidsView.Row row = countdown();
    PidsView.Row changed =
        new PidsView.Row(
            row.badge(), row.destination(), new PlatformCell("1", false, true), row.arrival());

    BufferedImage image =
        renderer.render(
            layout,
            new PidsView(
                PidsTheme.DARK,
                "21:40",
                List.of(),
                Optional.of(new Names("南渡", "Nam Toa")),
                List.of(),
                List.of(WS),
                List.of(changed),
                LABELS));

    // 首行站台方块：到发表左缘 4 + 站台列 380 + 内缩 8，首行顶 118、方块 28 竖向居中
    assertEquals(PidsTheme.DARK.amber(), rgb(image, 394, 127));
  }

  private static PidsView platformView(PidsTheme theme, List<PidsView.Row> rows) {
    return new PidsView(
        theme, "21:40", List.of("1"), Optional.empty(), List.of(), List.of(WS), rows, LABELS);
  }

  private static PidsView.Row countdown() {
    return new PidsView.Row(
        new Badge("WS", Optional.of("各停"), WS, false),
        new Destination(new Names("南渡", "Nam Toa"), Tone.NORMAL, false),
        new PlatformCell("1", false),
        new Arrival(
            ArrivalMode.COUNTDOWN,
            2,
            Tone.NORMAL,
            Optional.of(Label.of(new Names("准点", "On time"), Tone.NORMAL))));
  }

  private static PidsView.Row withArrival(PidsView.Row row, Arrival arrival) {
    return new PidsView.Row(row.badge(), row.destination(), row.platform(), arrival);
  }

  private static int rgb(BufferedImage image, int x, int y) {
    return image.getRGB(x, y) & 0xFFFFFF;
  }

  private static int[] pixels(BufferedImage image) {
    return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
  }

  private static int countColor(
      BufferedImage image, int x, int y, int width, int height, int color) {
    int count = 0;
    for (int dx = 0; dx < width; dx++) {
      for (int dy = 0; dy < height; dy++) {
        if (rgb(image, x + dx, y + dy) == color) {
          count++;
        }
      }
    }
    return count;
  }
}
