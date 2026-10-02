package org.fetarute.fetaruteTCAddon.display.pids.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.fetarute.fetaruteTCAddon.display.pids.fixtures.PidsFixtures;
import org.junit.jupiter.api.Test;

/** 布局文件解析：内置布局合法；任何一处问题都让整份布局作废，并带键路径报出。 */
class PidsLayoutParserTest {

  private static final String MINIMAL =
      """
      format: 1
      tiles: {rows: 1, cols: 3}
      widgets:
        - type: clock
          x: 8
          y: 92
        - type: departures
          x: 66
          y: 4
          width: 310
          columns:
            badge: {x: 0, width: 62}
            destination: {x: 62, width: 128}
            arrival: {x: 190, width: 120}
          rows:
            - height: 30
              count: 3
              badge: {width: 42, height: 26}
      """;

  @Test
  void builtInLayoutsAreValid() {
    PidsLayout platform = PidsFixtures.builtInLayout("platform-1x3");
    PidsLayout station = PidsFixtures.builtInLayout("station-3x5");
    PidsLayout longPlatform = PidsFixtures.builtInLayout("platform-1x4");
    assertEquals(512, longPlatform.width());
    assertEquals(3, longPlatform.rowCapacity());
    assertEquals(
        256,
        longPlatform.departures().orElseThrow().columns().destination().width(),
        "1×4 的终点列跨两块地图");

    for (String id : List.of("platform-group-1x3", "platform-group-1x4")) {
      PidsLayout group = PidsFixtures.builtInLayout(id);
      assertEquals(3, group.rowCapacity());
      assertTrue(group.departures().orElseThrow().columns().platform().isPresent(), "多站台屏每行写站台号");
    }

    assertEquals(384, platform.width());
    assertEquals(128, platform.height());
    assertEquals(3, platform.rowCapacity());
    assertTrue(platform.departures().orElseThrow().columns().platform().isEmpty(), "站台屏不显示站台列");
    assertEquals(640, station.width());
    assertEquals(384, station.height());
    assertEquals(6, station.rowCapacity());
    assertTrue(station.departures().orElseThrow().header().isPresent());
  }

  /** 2×1 停站屏：竖屏 128×256，有直通或经由一行时每页 6 站、没有时 7 站。 */
  @Test
  void theBuiltInStopListLayoutFitsSixOrSevenStops() {
    PidsLayout layout = PidsFixtures.builtInLayout("platform-2x1");
    PidsLayout.StopList list = layout.stopList().orElseThrow();

    assertEquals(128, layout.width());
    assertEquals(256, layout.height());
    assertTrue(layout.departures().isEmpty());
    assertEquals(6, list.rowsPerPage(true));
    assertEquals(7, list.rowsPerPage(false));
    assertEquals(2, list.pages(8, false));
    assertEquals(1, list.pages(0, true));
  }

  /** 停站屏只画站台号、时钟、色带与停站表：带上到发表、站名、换乘条的布局作废，免得写了却不显示。 */
  @Test
  void stopListLayoutsRejectWidgetsTheyCannotDraw() throws Exception {
    PidsLayoutParser.Result result =
        parse(
            """
            format: 1
            tiles: {rows: 2, cols: 1}
            widgets:
              - type: clock
                x: 4
                y: 230
              - type: station-title
                x: 0
                y: 0
                width: 128
              - type: stop-list
                x: 0
                y: 0
                width: 128
                height: 248
            """);

    assertEquals(
        List.of("widgets[1]: 停站屏只画站台号、时钟、线路色带与停站表，不支持这个组件"), result.problems(), "时钟可以，站名不行");
  }

  @Test
  void aStopListTooShortForOneStopIsRejected() throws Exception {
    PidsLayoutParser.Result result =
        parse(
            """
            format: 1
            tiles: {rows: 1, cols: 1}
            widgets:
              - type: stop-list
                x: 0
                y: 0
                width: 128
                height: 100
            """);

    assertTrue(
        result.problems().contains("widgets[0].stops: 有直通或经由一行时一站也放不下"),
        () -> result.problems().toString());
  }

  @Test
  void omittedStyleKeysTakeDefaults() throws Exception {
    PidsLayout layout = parse(MINIMAL).layout().orElseThrow();

    PidsLayout.Clock clock = (PidsLayout.Clock) layout.widgets().get(0);
    PidsLayout.RowStyle row = layout.departures().orElseThrow().styleOf(2);
    assertEquals(20, clock.size());
    assertEquals(PidsLayout.Align.LEFT, clock.align());
    assertEquals(layout.id(), layout.name(), "未写名称时用 ID");
    assertEquals(21, row.badge().codeWidth(), "代码区默认占一半");
    assertEquals(12, row.destination().text().size());
    assertEquals(PidsLayout.Align.RIGHT, row.arrival().numberAlign());
    assertTrue(row.arrival().dash());
    assertEquals(row.arrival().inset(), row.arrival().insetRight(), "右边距默认同左边距");
    assertEquals(PidsLayout.DEFAULT_BOLD_FROM, layout.boldFrom());
  }

  @Test
  void rightAlignedClockIsAnchoredAtItsRightEdge() throws Exception {
    PidsLayoutParser.Result atRightEdge =
        parse(MINIMAL.replace("    x: 8\n", "    x: 384\n    align: right\n"));
    PidsLayoutParser.Result atLeftEdge =
        parse(MINIMAL.replace("    x: 8\n", "    x: 0\n    align: right\n"));

    PidsLayout.Clock clock = (PidsLayout.Clock) atRightEdge.layout().orElseThrow().widgets().get(0);
    assertEquals(PidsLayout.Align.RIGHT, clock.align());
    assertTrue(
        atLeftEdge.problems().get(0).startsWith("widgets[0]: 超出画布"),
        () -> atLeftEdge.problems().toString());
  }

  @Test
  void negativeBoldFromIsRejected() throws Exception {
    PidsLayoutParser.Result result = parse("bold-from: -1\n" + MINIMAL);

    assertEquals(List.of("bold-from 不能为负数"), result.problems());
  }

  @Test
  void missingRequiredKeysAreReportedWithTheirPath() throws Exception {
    PidsLayoutParser.Result result = parse(MINIMAL.replace("    y: 92\n", ""));

    assertTrue(result.layout().isEmpty());
    assertEquals(List.of("widgets[0].y: 缺少必填项"), result.problems());
  }

  @Test
  void unsupportedFontSizesAreRejected() throws Exception {
    PidsLayoutParser.Result result =
        parse(MINIMAL.replace("    y: 92\n", "    y: 92\n    size: 16\n"));

    assertTrue(result.layout().isEmpty());
    assertTrue(result.problems().get(0).contains("字号 16"), () -> result.problems().toString());
  }

  @Test
  void widgetsMustStayOnTheCanvas() throws Exception {
    PidsLayoutParser.Result tooManyRows = parse(MINIMAL.replace("count: 3", "count: 5"));
    PidsLayoutParser.Result columnOverflow =
        parse(MINIMAL.replace("arrival: {x: 190, width: 120}", "arrival: {x: 190, width: 130}"));

    assertTrue(
        tooManyRows.problems().get(0).startsWith("widgets[1]: 超出画布"),
        () -> tooManyRows.problems().toString());
    assertEquals(List.of("widgets[1].columns.arrival: 超出到发表宽度"), columnOverflow.problems());
  }

  @Test
  void unknownWidgetTypesAndFormatsAreRejected() throws Exception {
    PidsLayoutParser.Result unknownType = parse(MINIMAL.replace("type: clock", "type: weather"));
    PidsLayoutParser.Result newerFormat = parse(MINIMAL.replace("format: 1", "format: 2"));

    assertEquals(List.of("widgets[0].type: 未知组件类型 weather"), unknownType.problems());
    assertFalse(newerFormat.problems().isEmpty());
    assertTrue(newerFormat.layout().isEmpty());
  }

  @Test
  void wrongValueTypesAreReported() throws Exception {
    PidsLayoutParser.Result result = parse(MINIMAL.replace("x: 8", "x: left"));

    assertEquals(List.of("widgets[0].x: 应为整数，实际为 left"), result.problems());
  }

  private static PidsLayoutParser.Result parse(String text) throws InvalidConfigurationException {
    YamlConfiguration yaml = new YamlConfiguration();
    yaml.loadFromString(text);
    PidsLayoutParser.Result result = PidsLayoutParser.parse("test", yaml);
    assertEquals(result.problems().isEmpty(), result.layout().isPresent());
    return result;
  }
}
