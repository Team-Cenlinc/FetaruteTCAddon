package org.fetarute.fetaruteTCAddon.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.storage.SampleTransitNetwork;
import org.fetarute.fetaruteTCAddon.storage.TransitTestStorage;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 运行图编辑书的回显：首站的 CHANGE 是起步线路，一律渲染在第一行（首站那一行之前）；中途站的 CHANGE 仍写在所属站之后。
 *
 * <p>读一遍再写回去，旧写法（CHANGE 写在首站下一行）的书就归一到新写法。
 */
class FtaRouteCommandRenderStopTest {

  @TempDir Path dir;
  private TransitTestStorage storage;
  private SampleTransitNetwork net;
  private FtaRouteCommand command;
  private final UUID routeId = UUID.randomUUID();

  @BeforeEach
  void setUp() throws Exception {
    storage = TransitTestStorage.open(dir);
    net = new SampleTransitNetwork(storage);
    FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
    when(plugin.getName()).thenReturn("FetaruteTCAddon");
    when(plugin.namespace()).thenReturn("fetarutetcaddon");
    command = new FtaRouteCommand(plugin);
  }

  @AfterEach
  void tearDown() {
    storage.close();
  }

  private RouteStop stop(int sequence, String node, RouteStopPassType type, String notes) {
    return new RouteStop(
        routeId,
        sequence,
        Optional.empty(),
        Optional.ofNullable(node),
        Optional.empty(),
        type,
        Optional.ofNullable(notes));
  }

  private List<String> render(RouteStop stop, boolean first) {
    return command.renderStopLines(storage.provider(), net.surcOp.id(), stop, first);
  }

  @Test
  void firstStopChangeIsRenderedBeforeTheFirstStopLine() {
    assertEquals(
        List.of("CHANGE:SURC:WS", "STOP DYNAMIC:SURC:S:NTA:[1:3]"),
        render(
            stop(0, null, RouteStopPassType.STOP, "DYNAMIC:SURC:S:NTA:[1:3]\nCHANGE:SURC:WS"),
            true));
    // 备注里只有 CHANGE：stop 行仍是这个站本身，CHANGE 挪到前面
    assertEquals(
        List.of("CHANGE:SURC:WS", "STOP SURC:S:NTA:1"),
        render(stop(0, "SURC:S:NTA:1", RouteStopPassType.STOP, "CHANGE:SURC:WS"), true));
    // 出库指令的首站
    assertEquals(
        List.of("CHANGE:SURC:WS", "CRET SURC:D:LWN:1"),
        render(
            stop(0, "SURC:D:LWN:1", RouteStopPassType.PASS, "CRET SURC:D:LWN:1\nCHANGE:SURC:WS"),
            true));
  }

  @Test
  void firstStopChangeKeepsTheStationAndDwellOfTheStopLine() {
    RouteStop stationStop =
        new RouteStop(
            routeId,
            0,
            Optional.of(net.kpo.id()),
            Optional.empty(),
            Optional.of(30),
            RouteStopPassType.STOP,
            Optional.of("CHANGE:SURC:WS"));
    assertEquals(List.of("CHANGE:SURC:WS", "STOP KPO dwell=30"), render(stationStop, true));
  }

  @Test
  void onlyTheFirstStopChangeMovesUpAndOtherNotesStayInPlace() {
    assertEquals(
        List.of("CHANGE:SURC:WS", "STOP SURC:S:NTA:1", "ACTION:foo", "CHANGE:SURC:DS"),
        render(
            stop(
                0,
                "SURC:S:NTA:1",
                RouteStopPassType.STOP,
                "CHANGE:SURC:WS\nACTION:foo\nCHANGE:SURC:DS"),
            true));
  }

  @Test
  void theStartLineIsTheChangeThatTheRuntimeActuallyUses() {
    // 运行时取第一条内容非空的 CHANGE：写着空内容的 CHANGE 不算起步线路，不能被挪到第一行
    assertEquals(
        List.of("CHANGE:SURC:WS", "STOP SURC:S:NTA:1", "CHANGE"),
        render(stop(0, "SURC:S:NTA:1", RouteStopPassType.STOP, "CHANGE\nCHANGE:SURC:WS"), true));
    // 内容非空但格式错误的 CHANGE（运行时不执行）也挪到第一行，写回时原样保留，不丢内容
    assertEquals(
        List.of("CHANGE:SURC", "STOP SURC:S:NTA:1"),
        render(stop(0, "SURC:S:NTA:1", RouteStopPassType.STOP, "CHANGE:SURC"), true));
  }

  @Test
  void laterStopChangeStaysAfterItsStopAndKeepsTheStopIdentity() {
    // 中途站的 CHANGE 是“到站后换线”：stop 行仍要写出这个站本身，CHANGE 写在下一行
    assertEquals(
        List.of("STOP SURC:S:HHU:1", "CHANGE:SURC:MT"),
        render(stop(1, "SURC:S:HHU:1", RouteStopPassType.STOP, "CHANGE:SURC:MT"), false));
    assertEquals(
        List.of("STOP DYNAMIC:SURC:S:HHU:[1:2]", "CHANGE:SURC:MT"),
        render(
            stop(1, null, RouteStopPassType.STOP, "DYNAMIC:SURC:S:HHU:[1:2]\nCHANGE:SURC:MT"),
            false));
    // 不是首站时，即使下标是 0 的备注也不上移——是否首站只看它在停靠表里的位置
    assertEquals(
        List.of("STOP SURC:S:NTA:1", "CHANGE:SURC:WS"),
        render(stop(0, "SURC:S:NTA:1", RouteStopPassType.STOP, "CHANGE:SURC:WS"), false));
  }

  @Test
  void editorPagesWriteTheStartLineFirstAndRoundTripToTheSameStops() {
    LocaleManager locale = mock(LocaleManager.class);
    when(locale.component(anyString(), anyMap())).thenReturn(Component.text("dummy"));
    // 旧写法的书：CHANGE 在首站下一行
    List<RouteStop> stops =
        parse(
            locale,
            "STOP DYNAMIC:SURC:S:NTA:[1:3]",
            "CHANGE:SURC:WS",
            "STOP SURC:S:HHU:1 dwell=30",
            "CHANGE:SURC:MT",
            "TERM SURC:S:PPK:1");

    List<String> rendered = bookLines(stops);
    int startLine = rendered.indexOf("CHANGE:SURC:WS");
    int firstStop = rendered.indexOf("STOP DYNAMIC:SURC:S:NTA:[1:3]");
    assertTrue(startLine >= 0 && firstStop == startLine + 1, "起步线路在首站那一行之前: " + rendered);
    int laterStop = rendered.indexOf("STOP SURC:S:HHU:1 dwell=30");
    assertEquals("CHANGE:SURC:MT", rendered.get(laterStop + 1), "中途站的 CHANGE 仍在所属站之后");

    // 读一遍再写回去：存储结果不变，第二次渲染与第一次逐字一致（归一到新写法）
    List<RouteStop> reparsed = parse(locale, rendered.toArray(String[]::new));
    assertEquals(stops.size(), reparsed.size());
    for (int i = 0; i < stops.size(); i++) {
      assertEquals(stops.get(i).notes(), reparsed.get(i).notes(), "seq=" + i);
      assertEquals(stops.get(i).waypointNodeId(), reparsed.get(i).waypointNodeId(), "seq=" + i);
      assertEquals(stops.get(i).passType(), reparsed.get(i).passType(), "seq=" + i);
      assertEquals(stops.get(i).dwellSeconds(), reparsed.get(i).dwellSeconds(), "seq=" + i);
    }
    assertEquals(rendered, bookLines(reparsed));
  }

  @Test
  void debugLabelShowsTheFirstStopChangeAsTheStartLineNotAsAChangeAtArrival() {
    LocaleManager locale = mock(LocaleManager.class);
    when(locale.text("command.route.define.debug.start-line")).thenReturn("起步线路");
    // 首站：CHANGE 是起步线路
    assertEquals(
        "STOP 起步线路=SURC:WS",
        FtaRouteCommand.resolveStopPassLabel(
            locale, stop(0, "SURC:S:NTA:1", RouteStopPassType.STOP, "CHANGE:SURC:WS"), true));
    assertEquals(
        "STOP DYNAMIC:SURC:S:NTA:[1:3] 起步线路=SURC:WS",
        FtaRouteCommand.resolveStopPassLabel(
            locale,
            stop(0, null, RouteStopPassType.STOP, "DYNAMIC:SURC:S:NTA:[1:3]\nCHANGE:SURC:WS"),
            true));
    // 中途站：CHANGE 仍是到站换线，显示保持原样
    assertEquals(
        "STOP CHANGE:SURC:MT",
        FtaRouteCommand.resolveStopPassLabel(
            locale, stop(1, "SURC:S:HHU:1", RouteStopPassType.STOP, "CHANGE:SURC:MT"), false));
    // 首站没有 CHANGE，或 CHANGE 格式错误（运行时不执行）：不标起步线路
    assertEquals(
        "STOP DYNAMIC:SURC:S:NTA:[1:3]",
        FtaRouteCommand.resolveStopPassLabel(
            locale, stop(0, null, RouteStopPassType.STOP, "DYNAMIC:SURC:S:NTA:[1:3]"), true));
    assertEquals(
        "STOP CHANGE:SURC",
        FtaRouteCommand.resolveStopPassLabel(
            locale, stop(0, "SURC:S:NTA:1", RouteStopPassType.STOP, "CHANGE:SURC"), true));
    assertEquals(
        "PASS",
        FtaRouteCommand.resolveStopPassLabel(
            locale, stop(0, "SURC:S:NTA:1", RouteStopPassType.PASS, null), true));
  }

  private List<RouteStop> parse(LocaleManager locale, String... texts) {
    List<FtaRouteCommand.BookLine> lines = new ArrayList<>();
    for (int i = 0; i < texts.length; i++) {
      lines.add(new FtaRouteCommand.BookLine(i + 1, texts[i]));
    }
    return command
        .parseStopsFromBook(
            locale, storage.provider(), net.surcOp.id(), routeId, mock(Player.class), lines)
        .orElseThrow();
  }

  /** 编辑书回显的全部文本行（去掉 # 开头的说明行与空行）。 */
  private List<String> bookLines(List<RouteStop> stops) {
    List<String> lines = new ArrayList<>();
    for (String page :
        command.buildRouteEditorPages(storage.provider(), net.surcOp, net.ws, net.ws1, stops)) {
      for (String line : page.split("\n", -1)) {
        if (!line.isBlank() && !line.startsWith("#")) {
          lines.add(line);
        }
      }
    }
    return lines;
  }
}
