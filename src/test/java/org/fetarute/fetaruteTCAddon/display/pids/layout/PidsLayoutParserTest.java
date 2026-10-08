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

  /** 2×1 停站屏：竖屏 128×256，有直通或经由一行时每页 6 站、没有时 7 站；后续列车页每页 4 班。 */
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
    assertEquals(32, list.followingTop(), "后续列车页：首行 28 写标题，隔 4 起列");
    assertEquals(50, list.followingRowHeight(), "色牌 24 + 3 + 站名 12 + 1 + 英文 10");
    assertEquals(4, list.followingRows(), "后续列车页每页 4 班");
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
  void theBuiltInLineStatusLayoutFitsThreeRoomyOrFiveCompactRows() {
    PidsLayout layout = PidsFixtures.builtInLayout("status-3x5");
    PidsLayout.LineStatus status = layout.lineStatus().orElseThrow();

    assertEquals(640, layout.width());
    assertEquals(384, layout.height());
    assertEquals(3, status.rowsPerPage(status.roomy()));
    assertEquals(5, status.rowsPerPage(status.compact()));
    assertEquals(118, status.rowsTop());
    assertEquals(
        384,
        status.rowsTop() + 5 * status.compact().height() + 4 * status.compact().gap() + 8,
        "五条小行之下留 8 像素");
  }

  /** 线路运行状况屏只画时钟与状况表；省略的样式键取内置 3×5 的值。 */
  @Test
  void lineStatusLayoutsRejectOtherWidgetsAndDefaultTheirStyles() throws Exception {
    String status =
        """
        format: 1
        tiles: {rows: 3, cols: 5}
        widgets:
          - type: clock
            x: 628
            y: 39
            align: right
          - type: line-status
            x: 4
            y: 24
            width: 632
            height: 360
        """;
    PidsLayout layout = parse(status).layout().orElseThrow();
    PidsLayout.LineStatus widget = layout.lineStatus().orElseThrow();
    PidsLayout reference = PidsFixtures.builtInLayout("status-3x5");

    assertEquals(reference.lineStatus().orElseThrow(), widget, "内置布局写出的值与默认值一致");
    assertEquals(
        List.of("widgets[2]: 线路运行状况屏只画时钟与状况表，不支持这个组件"),
        parse(status + "  - type: line-band\n    x: 0\n    y: 0\n    width: 640\n    height: 4\n")
            .problems());
  }

  @Test
  void lineStatusColumnsMustRunLeftToRightAndRowsMustFit() throws Exception {
    PidsLayoutParser.Result result =
        parse(
            """
            format: 1
            tiles: {rows: 1, cols: 5}
            widgets:
              - type: line-status
                x: 0
                y: 0
                width: 640
                height: 128
                columns: {name: 92, status: 460, detail: 452}
            """);

    assertTrue(
        result
            .problems()
            .contains("widgets[0].columns: 列须从左到右排开（inset < name < status < detail < width）"),
        () -> result.problems().toString());
    assertTrue(
        result.problems().contains("widgets[0].roomy: 一行也放不下"), () -> result.problems().toString());
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

  /** 整格提示默认上下叠放；只写了 spread 的布局照旧，写了 stacked 时以它为准。 */
  @Test
  void highlightArrangementDefaultsToStackedAndHonoursSpread() throws Exception {
    assertEquals(PidsLayout.HighlightArrangement.STACKED, highlightOf(MINIMAL), "未写时上下叠放");
    assertEquals(
        PidsLayout.HighlightArrangement.SPREAD, highlightOf(withHighlight("{spread: true}")));
    assertEquals(
        PidsLayout.HighlightArrangement.INLINE, highlightOf(withHighlight("{spread: false}")));
    assertEquals(
        PidsLayout.HighlightArrangement.STACKED,
        highlightOf(withHighlight("{stacked: true, spread: true}")));
    assertEquals(
        PidsLayout.HighlightArrangement.SPREAD, highlightOf(withHighlight("{stacked: false}")));

    for (String id :
        List.of("platform-1x3", "platform-1x4", "platform-group-1x3", "platform-group-1x4")) {
      PidsLayout.Departures departures = PidsFixtures.builtInLayout(id).departures().orElseThrow();
      for (int row = 0; row < departures.rows().size(); row++) {
        assertEquals(
            PidsLayout.HighlightArrangement.STACKED,
            departures.rows().get(row).arrival().highlightArrangement(),
            id + " 第 " + row + " 种行样式");
      }
    }
    assertEquals(
        PidsLayout.HighlightArrangement.INLINE,
        PidsFixtures.builtInLayout("station-3x5")
            .departures()
            .orElseThrow()
            .styleOf(0)
            .arrival()
            .highlightArrangement(),
        "统屏一行一格、英文紧跟中文");
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

  private static String withHighlight(String highlight) {
    return MINIMAL + "        arrival: {highlight: " + highlight + "}\n";
  }

  private static PidsLayout.HighlightArrangement highlightOf(String text)
      throws InvalidConfigurationException {
    return parse(text)
        .layout()
        .orElseThrow()
        .departures()
        .orElseThrow()
        .styleOf(0)
        .arrival()
        .highlightArrangement();
  }

  private static PidsLayoutParser.Result parse(String text) throws InvalidConfigurationException {
    YamlConfiguration yaml = new YamlConfiguration();
    yaml.loadFromString(text);
    PidsLayoutParser.Result result = PidsLayoutParser.parse("test", yaml);
    assertEquals(result.problems().isEmpty(), result.layout().isPresent());
    return result;
  }
}
