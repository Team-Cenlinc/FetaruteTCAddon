package org.fetarute.fetaruteTCAddon.dispatcher.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLineChanges.LineRef;
import org.junit.jupiter.api.Test;

/** 直通运转（CHANGE）：列车在交路每一站属于哪条线。 */
class RouteLineChangesTest {

  private static final UUID ROUTE = UUID.randomUUID();
  private static final LineRef WS = new LineRef("SURC", "WS");
  private static final LineRef DS = new LineRef("SURC", "DS");

  private static RouteStop stop(String node, String notes) {
    return new RouteStop(
        ROUTE,
        0,
        Optional.empty(),
        Optional.ofNullable(node),
        Optional.empty(),
        RouteStopPassType.STOP,
        Optional.ofNullable(notes));
  }

  /** KPO → PPK（本站起直通 DS）→ HHU → WYB。 */
  private static List<RouteStop> throughRoute() {
    List<RouteStop> stops = new ArrayList<>();
    stops.add(stop("SURC:S:KPO:1", null));
    stops.add(stop("SURC:S:PPK:1", "CHANGE:SURC:DS"));
    stops.add(stop("SURC:S:HHU:1", null));
    stops.add(stop("SURC:S:WYB:1", null));
    return stops;
  }

  @Test
  void directiveParsesValidAndInvalidForms() {
    RouteLineChanges.Directive valid =
        RouteLineChanges.directive(stop(null, "change : SURC:DS")).orElseThrow();
    assertTrue(valid.valid());
    assertEquals(Optional.of(DS), valid.target());

    RouteLineChanges.Directive format =
        RouteLineChanges.directive(stop(null, "CHANGE:SURC")).orElseThrow();
    assertFalse(format.valid());
    assertEquals("format", format.reason());
    assertEquals(Optional.empty(), format.target(), "无效指令不给目标");

    assertEquals(
        "blank", RouteLineChanges.directive(stop(null, "CHANGE::DS")).orElseThrow().reason());
    assertTrue(RouteLineChanges.directive(stop(null, null)).isEmpty());
    assertTrue(RouteLineChanges.directive(stop(null, "DSTY SURC:D:LWN:1")).isEmpty());
  }

  @Test
  void directiveIsFoundAmongOtherNoteLines() {
    RouteStop stop = stop(null, "DYNAMIC:SURC:S:WYB:[1:2]\nCHANGE:SURC:DS extra");
    assertEquals(Optional.of(DS), RouteLineChanges.target(stop));
  }

  @Test
  void linesByIndexSwitchesAtTheChangeStation() {
    assertEquals(List.of(WS, DS, DS, DS), RouteLineChanges.linesByIndex(throughRoute(), WS));
    assertEquals(List.of(), RouteLineChanges.linesByIndex(List.of(), WS));
  }

  @Test
  void lineAtHandlesBeforeDepartureAndPastTheEnd() {
    List<RouteStop> stops = throughRoute();
    assertEquals(WS, RouteLineChanges.lineAt(stops, -1, WS), "尚未到达第一站");
    assertEquals(WS, RouteLineChanges.lineAt(stops, 0, WS));
    assertEquals(DS, RouteLineChanges.lineAt(stops, 1, WS), "换线站按新线路：到站即改写标签");
    assertEquals(DS, RouteLineChanges.lineAt(stops, 99, WS));
  }

  @Test
  void changesByIndexMarksOnlyRealChanges() {
    assertEquals(
        List.of(Optional.empty(), Optional.of(DS), Optional.empty(), Optional.empty()),
        RouteLineChanges.changesByIndex(throughRoute(), WS));

    List<RouteStop> redundant =
        List.of(
            stop("SURC:S:KPO:1", "CHANGE:surc:ws"),
            stop("SURC:S:PPK:1", "CHANGE:SURC"),
            stop("SURC:S:HHU:1", "CHANGE:SURC:DS"),
            stop("SURC:S:WYB:1", "CHANGE:SURC:ds"));
    assertEquals(
        List.of(Optional.empty(), Optional.empty(), Optional.of(DS), Optional.empty()),
        RouteLineChanges.changesByIndex(redundant, WS),
        "同一条线、格式无效、重复换到当前线路都不算换线");
    assertEquals(List.of(), RouteLineChanges.changesByIndex(List.of(), WS));
  }

  @Test
  void nextChangeAfterLooksStrictlyAhead() {
    List<RouteStop> stops = throughRoute();
    RouteLineChanges.Change change = RouteLineChanges.nextChangeAfter(stops, 0, WS).orElseThrow();
    assertEquals(1, change.index());
    assertEquals(WS, change.from());
    assertEquals(DS, change.to());
    assertEquals(1, RouteLineChanges.nextChangeAfter(stops, -1, WS).orElseThrow().index());
    assertTrue(RouteLineChanges.nextChangeAfter(stops, 1, WS).isEmpty(), "到站即已换线");
  }

  @Test
  void currentPrefersCompleteLineTagsThenTheRouteLine() {
    assertEquals(
        Optional.of(new LineRef("FTA", "SL")),
        RouteLineChanges.current(LineRef.of("FTA", "SL"), Optional.of(WS)));
    assertEquals(
        Optional.of(WS),
        RouteLineChanges.current(LineRef.of("SURC", null), Optional.of(WS)),
        "标签不全时为交路本身的线路，不按进度去猜");
    assertEquals(Optional.empty(), RouteLineChanges.current(Optional.empty(), Optional.empty()));
  }

  @Test
  void entryLineTakesTheFirstStopChangeAsTheStartingLine() {
    // 起步 CHANGE 存在首站备注里（定义书写在第一站之前）：出车没有“抵达首站”，标签要一开始就是目标线路。
    List<RouteStop> stops = new ArrayList<>();
    stops.add(stop("SURC:S:NTA:1", "CHANGE:SURC:WS"));
    stops.add(stop("SURC:S:HHU:1", "CHANGE:SURC:MT"));
    stops.add(stop("SURC:S:PPK:1", null));
    assertEquals(WS, RouteLineChanges.entryLine(stops, 0, new LineRef("SURC", "MT")));
    // 与显示口径同一把尺子：起点显示的线路就是出车写下的标签
    assertEquals(
        RouteLineChanges.lineAt(stops, 0, new LineRef("SURC", "MT")),
        RouteLineChanges.entryLine(stops, 0, new LineRef("SURC", "MT")));
  }

  @Test
  void entryLineKeepsTheRouteLineWithoutAValidFirstStopChange() {
    LineRef route = new LineRef("SURC", "MT");
    // 首站没有 CHANGE：CHANGE 只写在中途站，出车时仍是交路自身的线路
    assertEquals(route, RouteLineChanges.entryLine(throughRoute(), 0, route));
    // 首站 CHANGE 与交路同线（大小写不同也算）：结果就是交路自身的线路
    List<RouteStop> sameLine = List.of(stop("SURC:S:KPO:1", "CHANGE:surc:mt"));
    assertTrue(RouteLineChanges.entryLine(sameLine, 0, route).sameLine(route));
    // 格式错误的 CHANGE 运行时不执行，这里也不算
    for (String notes : List.of("CHANGE:SURC", "CHANGE::WS", "CHANGE:SURC:", "CHANGE")) {
      assertEquals(
          route, RouteLineChanges.entryLine(List.of(stop("SURC:S:KPO:1", notes)), 0, route), notes);
    }
    // 没有停靠表
    assertEquals(route, RouteLineChanges.entryLine(List.of(), 0, route));
    assertEquals(route, RouteLineChanges.entryLine(null, 0, route));
  }

  @Test
  void entryLineHonoursChangesUpToTheEntryStop() {
    LineRef route = new LineRef("SURC", "MT");
    List<RouteStop> stops = new ArrayList<>();
    stops.add(stop("SURC:S:NTA:1", "DYNAMIC:SURC:S:NTA:[1:3]\nCHANGE:SURC:WS"));
    stops.add(stop("SURC:S:HHU:1", "CHANGE:SURC:MT"));
    stops.add(stop("SURC:S:PPK:1", null));
    assertEquals(WS, RouteLineChanges.entryLine(stops, 0, route), "首站备注里多行动作也能找到 CHANGE");
    // 折返复用从中途下标入路：前面的 CHANGE 不会由“抵达”来执行，入路时按该处应属的线路
    assertEquals(route, RouteLineChanges.entryLine(stops, 1, route));
    assertEquals(WS, RouteLineChanges.entryLine(stops.subList(0, 1), 5, route), "越过末尾按最后一站算");
    assertEquals(WS, RouteLineChanges.entryLine(stops, -1, route), "负下标按首站算，不像 lineAt 那样取交路自身线路");
  }

  @Test
  void sameLineIgnoresCase() {
    assertTrue(new LineRef("surc", " ds ").sameLine(DS));
    assertFalse(WS.sameLine(DS));
    assertFalse(WS.sameLine(null));
    assertTrue(LineRef.of("SURC", " ").isEmpty());
  }
}
