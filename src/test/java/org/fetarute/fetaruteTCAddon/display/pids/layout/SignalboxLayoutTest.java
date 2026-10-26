package org.fetarute.fetaruteTCAddon.display.pids.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.fetarute.fetaruteTCAddon.display.pids.fixtures.PidsFixtures;
import org.junit.jupiter.api.Test;

/**
 * 信号员控制台布局：内置 {@code signalbox-3x5} 与 {@code signalbox-4x8} 合法、排在内置布局末尾；列车表与操作按钮只放行在站场图屏里，点击区不得小于
 * 16×16。
 */
class SignalboxLayoutTest {

  private static final String YARD =
      """
      format: 1
      tiles: {rows: 3, cols: 5}
      widgets:
        - type: yard
          x: 0
          y: 32
          width: 640
          height: 228
      """;

  @Test
  void theBuiltInConsoleMatchesTheDesign() {
    PidsLayout layout = PidsFixtures.builtInLayout("signalbox-3x5");
    assertEquals(640, layout.width());
    assertEquals(384, layout.height());
    assertTrue(layout.signalbox());
    PidsLayout.Yard yard = layout.yard().orElseThrow();
    assertEquals(
        List.of(4, 34, 632, 228), List.of(yard.x(), yard.y(), yard.width(), yard.height()));
    PidsLayout.TrainList list = layout.trainList().orElseThrow();
    assertEquals(List.of(4, 264, 416), List.of(list.x(), list.y(), list.width()));
    assertEquals(14, list.headerHeight());
    assertEquals(10, list.headerSize());
    assertEquals(4, list.rows());
    assertEquals(24, list.rowHeight());
    assertEquals(12, list.textSize());
    assertTrue(list.y() + list.height() <= 384);
    PidsLayout.SignalButtons buttons = layout.signalButtons().orElseThrow();
    assertEquals(428, buttons.x());
    assertEquals(636, buttons.x() + buttons.width());
    assertEquals(100, buttons.buttonWidth());
    assertEquals(34, buttons.buttonHeight());
    assertTrue(buttons.replyHeight() >= 16, "回复呼叫时四条回复各不小于 16 像素高");

    assertFalse(PidsFixtures.builtInLayout("yard-3x5").signalbox(), "只读站场图屏不是控制台");
    assertFalse(PidsFixtures.builtInLayout("yard-4x8").signalbox());
  }

  /** 大控制台：站场图占满宽度，列车表多两行；按钮更大，回复呼叫时四条回复也放得下中英文两行。 */
  @Test
  void theLargeConsoleFitsMoreTrainsAndBiggerButtons() {
    PidsLayout layout = PidsFixtures.builtInLayout("signalbox-4x8");
    assertEquals(1024, layout.width());
    assertEquals(512, layout.height());
    assertTrue(layout.signalbox());
    PidsLayout.Yard yard = layout.yard().orElseThrow();
    assertEquals(
        List.of(4, 34, 1016, 300), List.of(yard.x(), yard.y(), yard.width(), yard.height()));
    PidsLayout.TrainList list = layout.trainList().orElseThrow();
    assertEquals(6, list.rows());
    assertTrue(list.y() >= yard.y() + yard.height(), "列车表在站场图下面");
    assertTrue(list.y() + list.height() <= 512);
    PidsLayout.SignalButtons buttons = layout.signalButtons().orElseThrow();
    assertTrue(buttons.x() >= list.x() + list.width(), "按钮在列车表右边");
    assertTrue(buttons.y() >= yard.y() + yard.height());
    assertTrue(buttons.y() + buttons.height() <= 512);
    assertTrue(buttons.x() + buttons.width() <= 1024);
    PidsLayout.TextStyle label = buttons.label();
    int bilingual = label.size() + label.gap() + label.secondarySize();
    // 上下各留 4 像素：字的下伸部分不压到框线
    assertTrue(buttons.buttonHeight() >= bilingual + 8, "按钮放得下中英文两行");
    assertTrue(buttons.replyHeight() >= bilingual + 8, "回复呼叫时每条回复也放得下中英文两行");
  }

  @Test
  void theConsolesAreAppendedToTheBuiltInLayouts() {
    List<String> builtIn = PidsLayoutRegistry.BUILT_IN;
    assertEquals(
        List.of("signalbox-3x5", "signalbox-4x8"),
        builtIn.subList(builtIn.size() - 2, builtIn.size()),
        "新布局追加在末尾，同尺寸退回时仍先取车站统屏与只读站场图");
    assertTrue(builtIn.indexOf("station-3x5") < builtIn.indexOf("signalbox-3x5"));
    assertTrue(builtIn.indexOf("yard-4x8") < builtIn.indexOf("signalbox-4x8"));
  }

  @Test
  void consoleWidgetsParseWithTheirDefaults() throws Exception {
    PidsLayoutParser.Result result =
        parse(
            YARD
                + """
                  - type: train-list
                    x: 4
                    y: 264
                    width: 416
                  - type: signal-buttons
                    x: 428
                    y: 266
                    width: 208
                    height: 114
                """);
    assertEquals(List.of(), result.problems());
    PidsLayout layout = result.layout().orElseThrow();
    PidsLayout.TrainList list = layout.trainList().orElseThrow();
    assertEquals(
        List.of(14, 10, 4, 24, 12),
        List.of(
            list.headerHeight(),
            list.headerSize(),
            list.rows(),
            list.rowHeight(),
            list.textSize()));
    assertEquals(
        List.of(76, 180, 352), List.of(list.destinationX(), list.statusX(), list.delayX()));
    PidsLayout.SignalButtons buttons = layout.signalButtons().orElseThrow();
    assertEquals(8, buttons.gapX());
    assertEquals(6, buttons.gapY());
    assertEquals(new PidsLayout.TextStyle(12, 10, 2), buttons.label());
  }

  @Test
  void consoleWidgetsNeedAYardAndRoomToClick() throws Exception {
    PidsLayoutParser.Result alone =
        parse(
            """
            format: 1
            tiles: {rows: 1, cols: 3}
            widgets:
              - type: signal-buttons
                x: 0
                y: 0
                width: 384
                height: 128
            """);
    assertEquals(List.of("widgets[0]: 列车表与操作按钮要与站场图组件放在同一个布局里"), alone.problems());

    PidsLayoutParser.Result cramped =
        parse(
            YARD
                + """
                  - type: signal-buttons
                    x: 428
                    y: 300
                    width: 208
                    height: 50
                """);
    assertEquals(List.of("widgets[1]: 按钮与回复的点击区不足 16×16"), cramped.problems());

    PidsLayoutParser.Result columns =
        parse(
            YARD
                + """
                  - type: train-list
                    x: 4
                    y: 264
                    width: 416
                    rows: {height: 12}
                    columns: {destination: 200, status: 180}
                """);
    assertEquals(
        List.of(
            "widgets[1].rows.height: 行高至少为 16，且比字号多 4 像素",
            "widgets[1].columns: 列须从左到右排开（0 < destination < status < delay < width）"),
        columns.problems());

    PidsLayoutParser.Result outside =
        parse(
            YARD
                + """
                  - type: train-list
                    x: 4
                    y: 300
                    width: 416
                """);
    assertTrue(
        outside.problems().get(0).startsWith("widgets[1]: 超出画布"), outside.problems().toString());
  }

  private static PidsLayoutParser.Result parse(String text) throws InvalidConfigurationException {
    YamlConfiguration yaml = new YamlConfiguration();
    yaml.loadFromString(text);
    return PidsLayoutParser.parse("test", yaml);
  }
}
