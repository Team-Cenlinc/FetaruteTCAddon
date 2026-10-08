package org.fetarute.fetaruteTCAddon.display.pids.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.display.pids.PidsGlyphForm;
import org.fetarute.fetaruteTCAddon.display.pids.fixtures.PidsFixtures;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsFollowingView;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsLineStatusView;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsNotice;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsNoticeView;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsStopListView;
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

  /** 整格提示：英文在中文下方、与中文同一左缘；到站格右半边不写字。 */
  @Test
  void highlightStacksTheEnglishUnderTheChinese() throws Exception {
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

    // 到站列 256–376、内缩 6，首行 4–56。
    int primary = PidsTheme.DARK.inverseText();
    int secondary = PidsTheme.DARK.inverseMuted();
    int[] chinese = textRows(image, 256, 4, 120, 52, primary);
    int[] english = textRows(image, 256, 4, 120, 52, secondary);
    assertTrue(english[0] > chinese[1], "英文在中文下方");
    assertTrue(countColor(image, 262, 4, 6, 52, primary) > 0, "中文从内缩处起");
    assertTrue(countColor(image, 262, 4, 6, 52, secondary) > 0, "英文与中文左对齐");
    assertEquals(0, countColor(image, 320, 4, 56, 52, secondary), "英文不再靠右");
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

  /** 每张宣传页都有自己的图标：图标块里有浅色笔画，且各张互不相同。 */
  @Test
  void everyCourtesyPageHasItsOwnIcon() {
    PidsLayout layout = PidsFixtures.builtInLayout("platform-1x3");
    List<List<Integer>> icons = new ArrayList<>();

    for (PidsNotice notice : PidsNotice.courtesy()) {
      BufferedImage image =
          renderer.renderNotice(
              layout,
              new PidsNoticeView(
                  PidsTheme.DARK,
                  notice,
                  new Names("确认终点", "Check your train"),
                  new Names("快速列车跨站停靠", "Rapid trains skip some stations"),
                  List.of(WS)));
      assertTrue(
          countColor(image, 40, 36, 48, 48, PidsTheme.PAPER) > 48 * 4, () -> notice + " 有图标");
      icons.add(Arrays.stream(image.getRGB(40, 36, 48, 48, null, 0, 48)).boxed().toList());
    }

    assertEquals(icons.size(), Set.copyOf(icons).size(), "各张宣传页的图标互不相同");
  }

  /** 2×1 后续列车页：首行写页标题，每班色牌与多久到达一行、终点中英文两行，班与班之间一条分隔线；取消的班次在右侧写“取消 / Cancelled”， 晚点写在终点中英文后面。 */
  @Test
  void followingPagesListTheLaterTrains() {
    PidsLayout layout = PidsFixtures.builtInLayout("platform-2x1");
    Badge mt = new Badge("MT", Optional.of("各停"), WS, false);
    PidsView.Row normal =
        new PidsView.Row(
            mt,
            new Destination(new Names("南渡", "Nam Toa"), Tone.NORMAL, false),
            new PlatformCell("2", false),
            new Arrival(ArrivalMode.COUNTDOWN, 12, Tone.NORMAL, Optional.empty()));
    PidsView.Row cancelled =
        new PidsView.Row(
            new Badge("MT", Optional.of("各停"), WS, true),
            new Destination(new Names("绿洲农场", "Oasis Farmland"), Tone.MUTED, true),
            new PlatformCell("2", true),
            new Arrival(
                ArrivalMode.DASH,
                0,
                Tone.MUTED,
                Optional.of(Label.of(new Names("取消", "Cancelled"), Tone.RED))));
    PidsView.Row late =
        new PidsView.Row(
            mt,
            new Destination(new Names("南渡", "Nam Toa"), Tone.NORMAL, false),
            new PlatformCell("2", false),
            new Arrival(
                ArrivalMode.COUNTDOWN,
                19,
                Tone.NORMAL,
                Optional.of(Label.of(new Names("晚点 3 分", "Late 3 min"), Tone.AMBER))));
    PidsFollowingView view =
        new PidsFollowingView(
            PidsTheme.DARK,
            "21:40",
            List.of("2"),
            List.of(normal, cancelled, late),
            new Names("后续列车", "Following trains"),
            new Names("分", "min"),
            List.of(WS));

    BufferedImage image = renderer.renderFollowing(layout, view);

    assertEquals(128, image.getWidth());
    assertEquals(256, image.getHeight());
    assertTrue(countColor(image, 32, 0, 92, 28, PidsTheme.DARK.text()) > 0, "首行写页标题");
    // 每班 50 高、间隔 4：第一班 32 起，第二班 86 起，第三班 140 起；分隔线在间隔中间
    assertEquals(PidsTheme.DARK.panel(), rgb(image, 20, 84), "第一、二班之间的分隔线");
    assertTrue(countColor(image, 80, 86, 44, 12, PidsTheme.DARK.red()) > 0, "取消：右侧写中文");
    assertTrue(countColor(image, 70, 99, 54, 10, PidsTheme.DARK.red()) > 0, "取消：中文下面写英文 Cancelled");
    assertTrue(countColor(image, 4, 167, 120, 12, PidsTheme.DARK.amber()) > 0, "晚点写在终点中文后面");
    assertTrue(
        countColor(image, 4, 180, 120, 10, PidsTheme.DARK.amber()) > 0, "英文终点后面写 Late 3 min");
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

  /** 轮到备注时英文那一格画成标签色块：浅色主题的琥珀是深棕底，标签字反白。 */
  @Test
  void remarksReplaceTheEnglishNameWithATagBlock() throws Exception {
    PidsLayout layout = PidsFixtures.builtInLayout("platform-1x3");
    PidsView.Row plain = countdown();
    PidsView.Row remark = withRemark(plain, PidsTheme.LIGHT.amber(), "经由", "新笛矢·壑湖");

    BufferedImage before = renderer.render(layout, platformView(PidsTheme.LIGHT, List.of(plain)));
    BufferedImage after = renderer.render(layout, platformView(PidsTheme.LIGHT, List.of(remark)));

    // 首行终点列：到发表左缘 66 + 色牌 62 = 128 起，宽 128；首行 4–56
    assertEquals(0, countColor(before, 128, 4, 128, 52, PidsTheme.LIGHT.amber()));
    assertTrue(countColor(after, 128, 4, 128, 52, PidsTheme.LIGHT.amber()) > 0);
    assertTrue(
        countColor(after, 128, 4, 128, 52, PidsTheme.PAPER)
            > countColor(before, 128, 4, 128, 52, PidsTheme.PAPER),
        "深色底上的标签字用白色");
    assertEquals(
        countColor(before, 128, 4, 128, 30, PidsTheme.LIGHT.text()),
        countColor(after, 128, 4, 128, 30, PidsTheme.LIGHT.text()),
        "中文终点名不动");
  }

  /** 小字行的备注色块与上面的中文名之间至少空 1 像素：内置布局中英间距为 2，自定义布局间距只有 1 时色块不再向上伸。 */
  @Test
  void remarkTagsNeverTouchTheNameAbove() throws Exception {
    PidsView.Row base = countdown();
    PidsView.Row oasis =
        new PidsView.Row(
            base.badge(),
            new Destination(new Names("绿洲农场", "Oasis Farmland"), Tone.NORMAL, false),
            base.platform(),
            base.arrival());
    PidsView view =
        platformView(
            PidsTheme.DARK, List.of(base, withRemark(oasis, PidsTheme.DARK.amber(), "经由", "主城湾")));
    PidsLayout tight =
        PidsFixtures.builtInLayout(
            "platform-1x3",
            text ->
                text.replace(
                    "destination: {size: 12, secondary: 10, gap: 2, inset: 6}",
                    "destination: {size: 12, secondary: 10, gap: 1, inset: 6}"));

    for (PidsLayout layout : List.of(PidsFixtures.builtInLayout("platform-1x3"), tight)) {
      BufferedImage image = renderer.render(layout, view);

      // 第二行终点列：到发表左缘 66 + 色牌 62 = 128 起，宽 128；首行之下 56–120
      assertTrue(countColor(image, 128, 56, 128, 64, PidsTheme.DARK.amber()) > 0, "第二行写着备注");
      assertEquals(
          0,
          stacked(image, 128, 56, 128, 64, PidsTheme.DARK.text(), PidsTheme.DARK.amber()),
          "中文名下面紧挨着就是色块");
    }
  }

  /** 车站统屏的英文写在中文名后面：备注也写在那里，用剩下的宽度。 */
  @Test
  void stationScreensPutTheRemarkAfterTheName() throws Exception {
    PidsLayout layout = PidsFixtures.builtInLayout("station-3x5");
    PidsView.Row plain = countdown();
    PidsView.Row remark = withRemark(plain, WS, "直通", "浦蓝线");

    BufferedImage before = renderer.render(layout, stationView(plain));
    BufferedImage after = renderer.render(layout, stationView(remark));

    // 首行终点列：到发表左缘 4 + 124 = 128 起，宽 256；首行 118–160
    assertEquals(0, countColor(before, 128, 118, 256, 42, WS));
    assertTrue(countColor(after, 128, 118, 256, 42, WS) > 0);
    assertTrue(
        countColor(after, 128, 118, 256, 42, PidsTheme.INK)
            > countColor(before, 128, 118, 256, 42, PidsTheme.INK),
        "浅色底上的标签字用深色");
  }

  private static PidsView stationView(PidsView.Row row) {
    return new PidsView(
        PidsTheme.DARK,
        "21:40",
        List.of(),
        Optional.of(new Names("南渡", "Nam Toa")),
        List.of(),
        List.of(WS),
        List.of(row),
        LABELS);
  }

  /** 2×1 停站屏：经由站琥珀大圆点、多页时写页码并画向下的箭头，翻到最后一页时没有箭头。 */
  @Test
  void stopListScreensDrawTheDotsThePageAndTheArrow() throws Exception {
    PidsLayout layout = PidsFixtures.builtInLayout("platform-2x1");
    PidsStopListView view = stopListView(countdown().arrival(), Optional.empty(), 9);

    BufferedImage first = renderer.renderStopList(layout, view);
    BufferedImage last = renderer.renderStopList(layout, view.withPage(1));

    assertEquals(128, first.getWidth());
    assertEquals(256, first.getHeight());
    // 停站表无终点下面一行时从 64 起，每站 24；圆点竖条在 4–12，竖线在 7–9
    assertTrue(countColor(first, 4, 88, 9, 24, PidsTheme.DARK.amber()) > 0, "第二站是经由站");
    assertTrue(countColor(first, 100, 236, 24, 12, PidsTheme.DARK.text()) > 0, "两页时写页码");
    assertTrue(countColor(first, 4, 229, 9, 5, WS) > 0, "后面还有站：向下的箭头");
    assertEquals(0, countColor(last, 4, 229, 9, 5, WS), "最后一页没有箭头");
    assertEquals(WS, rgb(last, 7, 64), "第二页竖线从上沿接下来");
  }

  /** 2×1 最底下一行左侧写要提醒的状态，按色调着色。 */
  @Test
  void stopListScreensWriteTheStatusAtTheBottom() throws Exception {
    PidsLayout layout = PidsFixtures.builtInLayout("platform-2x1");
    Label pending = Label.of(new Names("站台待定", "Platform TBD"), Tone.AMBER);

    BufferedImage plain =
        renderer.renderStopList(layout, stopListView(countdown().arrival(), Optional.empty(), 3));
    BufferedImage flagged =
        renderer.renderStopList(
            layout, stopListView(countdown().arrival(), Optional.of(pending), 3));

    assertEquals(0, countColor(plain, 4, 236, 90, 12, PidsTheme.DARK.amber()));
    assertTrue(countColor(flagged, 4, 236, 90, 12, PidsTheme.DARK.amber()) > 0);
  }

  /** 分钟数伸到色牌上时改用小字号：色牌（32–72）里不出现分钟数的白字。 */
  @Test
  void longMinutesDoNotRunIntoTheBadge() throws Exception {
    PidsLayout layout = PidsFixtures.builtInLayout("platform-2x1");
    Arrival late = new Arrival(ArrivalMode.COUNTDOWN, 105, Tone.NORMAL, Optional.empty());

    BufferedImage image = renderer.renderStopList(layout, stopListView(late, Optional.empty(), 3));

    assertEquals(0, countColor(image, 32, 2, 40, 24, PidsTheme.DARK.text()));
    assertTrue(countColor(image, 73, 2, 51, 24, PidsTheme.DARK.text()) > 0, "分钟数照样写出");
  }

  /** 终点下面一行的标签按英文加宽：“Cancelled”整个落在红色块里，说明文字从色块右边起。 */
  @Test
  void noteTagsWidenForLongEnglish() throws Exception {
    PidsLayout layout = PidsFixtures.builtInLayout("platform-2x1");
    PidsLayout.StopList list = layout.stopList().orElseThrow();
    PidsStopListView base = stopListView(countdown().arrival(), Optional.empty(), 3);
    PidsStopListView view =
        new PidsStopListView(
            base.theme(),
            base.clock(),
            base.platforms(),
            base.train(),
            Optional.of(
                new PidsStopListView.Note(
                    new Names("取消", "Cancelled"),
                    PidsTheme.DARK.red(),
                    "南渡 21:41",
                    Optional.empty(),
                    "Nam Toa")),
            0,
            base.labels(),
            base.bandColors());

    BufferedImage image = renderer.renderStopList(layout, view);

    int box = list.noteHeight();
    int tagRight = list.x() + list.inset() + box;
    assertTrue(
        countColor(image, tagRight, list.noteTop(), 8, box, PidsTheme.DARK.red()) > 0, "色块比见方宽");
    assertEquals(
        PidsTheme.DARK.red(),
        rgb(image, list.x() + list.inset(), list.noteTop() + box - 1),
        "色块左下角仍是底色，英文没有伸出左缘");
  }

  /** 竖屏的安全提示页：图标块在上方居中，标题在下方，色带不动。 */
  @Test
  void portraitScreensStackTheNotice() throws Exception {
    PidsLayout layout = PidsFixtures.builtInLayout("platform-2x1");
    PidsNoticeView notice =
        new PidsNoticeView(
            PidsTheme.DARK,
            PidsNotice.PASSING,
            new Names("列车通过", "Train passing"),
            new Names("请勿靠近站台边缘", "Stay back from the platform edge"),
            List.of(WS));

    BufferedImage image = renderer.renderNotice(layout, notice);

    assertEquals(128, image.getWidth());
    assertEquals(256, image.getHeight());
    assertTrue(countColor(image, 32, 0, 64, 128, PidsTheme.DARK.amber()) > 64 * 30, "警示色图标块");
    assertTrue(countColor(image, 0, 120, 128, 120, PidsTheme.DARK.text()) > 0, "标题与说明在下方");
    assertEquals(WS, rgb(image, 64, 254), "色带");
  }

  /** 窄测试卡（2×1）：说明行放不下时折行写完，不截断。 */
  @Test
  void narrowTestCardsWrapTheirLines() throws Exception {
    PidsTestCard card =
        new PidsTestCard(
            new Names("站台屏待配置", "Screen not configured"),
            List.of("车站：新笛矢 · 壑湖（HHU）· 站台 3"),
            "用配置棍右键调整",
            2,
            1);

    BufferedImage image = renderer.renderTestCard(card);

    // 标题改用 12 号：首行说明在 38–50，折到下一行在 52–64
    assertTrue(countColor(image, 8, 38, 112, 12, PidsTheme.LIGHT.text()) > 0);
    assertTrue(countColor(image, 8, 52, 112, 12, PidsTheme.LIGHT.text()) > 0, "放不下的部分折到下一行");
  }

  private static PidsStopListView stopListView(
      Arrival arrival, Optional<Label> status, int stopCount) {
    List<PidsStopListView.Stop> stops = new ArrayList<>();
    for (int i = 0; i < stopCount; i++) {
      PidsStopListView.Kind kind =
          i == 0
              ? PidsStopListView.Kind.NEXT
              : i == 1
                  ? PidsStopListView.Kind.VIA
                  : i == stopCount - 1
                      ? PidsStopListView.Kind.TERMINAL
                      : PidsStopListView.Kind.STOP;
      stops.add(
          new PidsStopListView.Stop(new Names("站" + i, "Stop " + i), kind, WS, WS, List.of()));
    }
    return new PidsStopListView(
        PidsTheme.DARK,
        "21:40",
        List.of("2"),
        Optional.of(
            new PidsStopListView.Train(
                "train:t1",
                new Badge("WS", Optional.of("各停"), WS, false),
                new Names("南渡", "Nam Toa"),
                arrival,
                status,
                Optional.empty(),
                stops)),
        Optional.empty(),
        0,
        new PidsStopListView.Labels(
            new Names("分", "min"),
            new Names("暂无后续列车", "No further trains"),
            new Names("经由", "via"),
            new Names("直通", "thru")),
        List.of(WS));
  }

  private static PidsView.Row withRemark(PidsView.Row row, int color, String tag, String text) {
    Destination plain = row.destination();
    return new PidsView.Row(
        row.badge(),
        new Destination(
            plain.names(),
            plain.tone(),
            plain.struck(),
            Optional.of(
                new PidsView.Remark(
                    List.of(
                        new PidsView.RemarkPart(tag, color, List.of(text), Optional.empty()))))),
        row.platform(),
        row.arrival());
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

  /**
   * 线路运行状况屏（大行）：每条线路一行面板色铺底，色牌在行内竖向居中；运行正常写绿字、不画色块、不写说明， 延误画琥珀色块（深字），停运画红色块（白字）；只有一页时不写页码。行从 118
   * 起，每行 84、间隔 2。
   */
  @Test
  void lineStatusRowsColourTheirStatus() {
    PidsLayout layout = PidsFixtures.builtInLayout("status-3x5");
    PidsTheme theme = PidsTheme.DARK;
    PidsLineStatusView view =
        statusView(
            true,
            0,
            1,
            statusRow("WS", "浦蓝线", PidsLineStatusView.Style.GOOD, Optional.empty()),
            statusRow(
                "MT",
                "大都会线",
                PidsLineStatusView.Style.AMBER,
                Optional.of(new Names("晚点最多 8 分钟", "Up to 8 min late"))),
            statusRow(
                "DS",
                "探索线",
                PidsLineStatusView.Style.RED,
                Optional.of(new Names("主城湾—海兴 暂停运营", "No service Spawn Bay – Hai Hsing"))));

    BufferedImage image = renderer.renderLineStatus(layout, view);

    assertEquals(640, image.getWidth());
    assertEquals(384, image.getHeight());
    assertEquals(theme.panel(), rgb(image, 5, 119), "第一行面板");
    assertEquals(theme.background(), rgb(image, 5, 203), "行间距露出底色");
    assertEquals(WS, rgb(image, 13, 139), "色牌在行内竖向居中：(84 - 44) / 2 = 20");
    assertTrue(countColor(image, 300, 118, 140, 84, theme.green()) > 0, "运行正常写绿字");
    assertEquals(0, countColor(image, 292, 118, 156, 84, theme.amber()), "运行正常不画色块");
    assertEquals(0, countColor(image, 456, 118, 172, 84, theme.text()), "运行正常不写说明");
    assertEquals(theme.amber(), rgb(image, 293, 223), "延误画琥珀色块");
    assertTrue(countColor(image, 300, 222, 140, 48, PidsTheme.INK) > 0, "亮黄底上写深字");
    assertTrue(countColor(image, 456, 204, 172, 84, theme.text()) > 0, "延误写说明");
    assertEquals(theme.red(), rgb(image, 293, 309), "停运画红色块");
    assertTrue(countColor(image, 300, 308, 140, 48, PidsTheme.PAPER) > 0, "红底上写白字");
    assertEquals(0, countColor(image, 560, 70, 68, 14, theme.text()), "只有一页不写页码");
    assertTrue(countColor(image, 12, 24, 300, 36, theme.text()) > 0, "页标题");
    assertTrue(countColor(image, 580, 39, 48, 20, theme.text()) > 0, "右上角时钟");
  }

  /** 小行：多于一页时运营商名一行右侧写页码；中文线路名大字放不下时改用小一档字号，不压到状况列。 */
  @Test
  void compactLineStatusPagesAndShrinksLongLineNames() {
    PidsLayout layout = PidsFixtures.builtInLayout("status-3x5");
    PidsTheme theme = PidsTheme.DARK;
    PidsLineStatusView view =
        statusView(
            false,
            0,
            2,
            statusRow("FRn", "新远洛克威支线延长线", PidsLineStatusView.Style.MUTED, Optional.empty()),
            statusRow("WS", "浦蓝线", PidsLineStatusView.Style.GOOD, Optional.empty()));

    BufferedImage image = renderer.renderLineStatus(layout, view);

    assertTrue(countColor(image, 590, 70, 38, 14, theme.text()) > 0, "页码 1/2 靠右");
    int[] longName = textRows(image, 96, 118, 186, 50, theme.text());
    int[] shortName = textRows(image, 96, 170, 186, 50, theme.text());
    assertTrue(longName[1] - longName[0] < 20, () -> "长名改用 20 号: " + Arrays.toString(longName));
    assertTrue(shortName[1] - shortName[0] >= 20, () -> "短名用 24 号: " + Arrays.toString(shortName));
    assertEquals(0, countColor(image, 286, 118, 6, 50, theme.text()), "线路名不压到状况列");
    assertEquals(theme.panel(), rgb(image, 5, 118 + 52), "第二行在 52 之后");
  }

  @Test
  void anEmptyLineStatusSaysSo() {
    PidsLayout layout = PidsFixtures.builtInLayout("status-3x5");

    BufferedImage image = renderer.renderLineStatus(layout, statusView(true, 0, 1));

    assertTrue(countColor(image, 12, 118, 300, 84, PidsTheme.DARK.muted()) > 0, "第一行写暂无线路信息");
    assertEquals(PidsTheme.DARK.background(), rgb(image, 5, 210), "其余不画行");
  }

  private static PidsLineStatusView statusView(
      boolean roomy, int page, int pages, PidsLineStatusView.Row... rows) {
    return new PidsLineStatusView(
        PidsTheme.DARK,
        "21:40",
        new Names("线路运行状况", "Service status"),
        new Names("南城铁路", "SURcentral"),
        new PidsLineStatusView.Labels(
            new Names("线路", "Line"),
            new Names("运行状况", "Status"),
            new Names("说明", "Details"),
            new Names("暂无线路信息", "No line information")),
        List.of(rows),
        roomy,
        page,
        pages);
  }

  private static PidsLineStatusView.Row statusRow(
      String code, String name, PidsLineStatusView.Style style, Optional<Names> detail) {
    return new PidsLineStatusView.Row(
        new PidsView.LineChip(
            code, code.equals("WS") ? WS : 0xD920D9, new Names(name, code + " Line")),
        new Names("状况", "Status"),
        style,
        detail);
  }

  /** 区域里 {@code color} 色像素所在的最上一行与最下一行（绝对坐标）；没有时为 {@code [MAX, MIN]}。 */
  private static int[] textRows(
      BufferedImage image, int x, int y, int width, int height, int color) {
    int top = Integer.MAX_VALUE;
    int bottom = Integer.MIN_VALUE;
    for (int dy = 0; dy < height; dy++) {
      for (int dx = 0; dx < width; dx++) {
        if (rgb(image, x + dx, y + dy) == color) {
          top = Math.min(top, y + dy);
          bottom = Math.max(bottom, y + dy);
        }
      }
    }
    return new int[] {top, bottom};
  }

  private static int rgb(BufferedImage image, int x, int y) {
    return image.getRGB(x, y) & 0xFFFFFF;
  }

  private static int[] pixels(BufferedImage image) {
    return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
  }

  /** 区域里 {@code upper} 色像素正下方紧挨着 {@code lower} 色像素的处数。 */
  private static int stacked(
      BufferedImage image, int x, int y, int width, int height, int upper, int lower) {
    int count = 0;
    for (int dx = 0; dx < width; dx++) {
      for (int dy = 0; dy + 1 < height; dy++) {
        if (rgb(image, x + dx, y + dy) == upper && rgb(image, x + dx, y + dy + 1) == lower) {
          count++;
        }
      }
    }
    return count;
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
