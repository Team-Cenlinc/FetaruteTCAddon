package org.fetarute.fetaruteTCAddon.display.pids.yard;

import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.P1;
import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.P2;
import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.SW1;
import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.SW2;
import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.T1;
import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.cell;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.junit.jupiter.api.Test;

/** 站场图几何：股道画成水平的行，股道之间只用 45° 斜线；道岔在直股那一行，侧股从道岔处拐出。 */
class YardGeometryTest {

  private static final YardGeometry.Rect RECT = new YardGeometry.Rect(6, 52, 628, 206);

  @Test
  void everythingFitsInsideTheRect() {
    YardGeometry geometry = geometry(YardFixtures.graph());

    for (YardGeometry.Path path : geometry.paths()) {
      for (YardGeometry.Segment s : path.segments()) {
        assertInside(s.x0(), s.y0());
        assertInside(s.x1(), s.y1());
      }
    }
    assertFalse(geometry.partial());
  }

  @Test
  void onlyHorizontalLinesAndFortyFiveDegreeDiagonals() {
    YardGeometry geometry = geometry(YardFixtures.graph());

    for (YardGeometry.Path path : geometry.paths()) {
      for (YardGeometry.Segment s : path.segments()) {
        int dx = Math.abs(s.x1() - s.x0());
        int dy = Math.abs(s.y1() - s.y0());
        assertTrue(dy == 0 || dx == dy, () -> path.edge() + " 有既不水平也不是 45° 的线: " + s);
      }
    }
  }

  @Test
  void platformTracksAreEvenlySpacedRowsInGeographicOrder() {
    YardGeometry geometry = geometry(YardFixtures.graph());
    YardGeometry.Point one = geometry.pointOf(cell(0, 0, false)).orElseThrow();
    YardGeometry.Point two = geometry.pointOf(cell(0, 4, false)).orElseThrow();
    YardGeometry.Point west = geometry.pointOf(cell(-40, 0, false)).orElseThrow();

    assertEquals(one.x(), two.x(), "两股道的站台节点上下对齐");
    assertEquals(YardGeometry.ROW_GAP, two.y() - one.y(), "1 道在北、2 道在南，隔一个行距");
    assertTrue(west.x() < one.x(), "西在左");
  }

  @Test
  void switchesSitOnTheirThroughRowAndBranchesLeaveAtFortyFiveDegrees() {
    YardGeometry geometry = geometry(YardFixtures.graph());
    YardGeometry.Point one = geometry.pointOf(cell(0, 0, false)).orElseThrow();
    // 道岔那一格同时属于三条区间，取直股那条线的端点作道岔的位置
    YardGeometry.Segment westThrough = path(geometry, EdgeId.undirected(SW1, T1)).segments().get(0);
    YardGeometry.Segment eastThrough = path(geometry, EdgeId.undirected(P1, SW2)).segments().get(0);
    YardGeometry.Point switchWest = new YardGeometry.Point(westThrough.x0(), westThrough.y0());
    YardGeometry.Point switchEast = new YardGeometry.Point(eastThrough.x1(), eastThrough.y1());
    YardGeometry.Path turnout = path(geometry, EdgeId.undirected(SW1, P2));

    assertEquals(one.y(), switchWest.y(), "西头道岔在 1 道（直股）那一行");
    assertEquals(one.y(), switchEast.y(), "东头道岔也是");
    assertTrue(
        turnout
            .segments()
            .contains(
                new YardGeometry.Segment(
                    switchWest.x(),
                    switchWest.y(),
                    switchWest.x() + YardGeometry.ROW_GAP,
                    switchWest.y() + YardGeometry.ROW_GAP)),
        () -> "侧股从道岔处 45° 拐到 2 道: " + turnout.segments());
    assertEquals(2, turnout.segments().size(), "然后沿 2 道水平到站台");
  }

  @Test
  void northSouthYardsAreRotatedToHorizontal() {
    YardGeometry geometry = geometry(YardFixtures.graph(true, false, Set.of()));
    YardGeometry.Point one = geometry.pointOf(cell(0, 0, true)).orElseThrow();
    YardGeometry.Point west = geometry.pointOf(cell(-40, 0, true)).orElseThrow();
    YardGeometry.Point two = geometry.pointOf(cell(0, 4, true)).orElseThrow();

    assertTrue(Math.abs(west.x() - one.x()) > 100, "沿线方向铺开在横向");
    assertEquals(one.y(), west.y(), "正线画成水平");
    assertEquals(YardGeometry.ROW_GAP, Math.abs(two.y() - one.y()), "股道上下分开一个行距");
  }

  /** 原版铁轨的斜线是一格一格的台阶，示意图里只按两端节点连线：同一行就是一条水平线。 */
  @Test
  void curvesAndStairsBetweenTwoNodesAreDrawnStraight() {
    SimpleRailGraph graph = YardFixtures.staircase();
    YardGeometry geometry = geometry(graph);
    YardGeometry.Path stairs = path(geometry, EdgeId.undirected(P1, YardFixtures.SW_FAR));

    assertEquals(1, stairs.segments().size(), () -> "一条线: " + stairs.segments());
    YardGeometry.Segment line = stairs.segments().get(0);
    assertEquals(line.y0(), line.y1(), "水平");
    for (int x = 1; x < 30; x++) {
      YardGeometry.Point car = geometry.pointOf(cell(x, x / 3, false)).orElseThrow();
      assertEquals(line.y0(), car.y(), () -> "车厢落在线上: " + car);
    }
  }

  @Test
  void edgesWithoutFootprintsStillDrawButTheirCarsCannotBePlaced() {
    EdgeId throat = EdgeId.undirected(T1, P1);
    SimpleRailGraph graph = YardFixtures.graph(false, false, Set.of(throat));
    YardGeometry geometry = geometry(graph);

    assertTrue(geometry.partial());
    assertEquals(1, path(geometry, throat).segments().size(), "两端节点之间一条线");
    assertTrue(geometry.pointOf(cell(-10, 0, false)).isEmpty(), "没有方块，车厢无从定位");
  }

  @Test
  void exitsAreLabelledOnTheirOwnSide() {
    YardGeometry geometry = geometry(YardFixtures.graph());
    Map<String, Boolean> sides = new HashMap<>();
    geometry.exits().forEach(label -> sides.put(label.text(), label.alignRight()));

    assertEquals(Map.of("西站", false, "DEF", true), sides, "查不到站名时写代码");
  }

  @Test
  void cellsOutsideTheYardHaveNoPoint() {
    YardGeometry geometry = geometry(YardFixtures.graph());

    assertTrue(geometry.pointOf(cell(-100, 0, false)).isEmpty(), "出口只取靠站场的 24 格");
    assertTrue(geometry.pointOf(cell(-60, 0, false)).isPresent());
  }

  @Test
  void carsFollowTheirOwnTrack() {
    YardGeometry geometry = geometry(YardFixtures.graph());
    YardGeometry.Point onTwo = geometry.pointOf(cell(20, 4, false)).orElseThrow();
    YardGeometry.Point onOne = geometry.pointOf(cell(20, 0, false)).orElseThrow();
    YardGeometry.Point east = geometry.pointOf(cell(40, 0, false)).orElseThrow();

    assertEquals(onOne.y() + YardGeometry.ROW_GAP, onTwo.y(), "2 道上的车厢画在 2 道那一行");
    assertTrue(onOne.x() < east.x(), "按沿线先后排");
    assertTrue(geometry.pointOf(cell(0, 0, false)).isPresent());
    assertTrue(
        geometry.paths().stream().anyMatch(p -> p.edge().equals(EdgeId.undirected(P2, SW2))));
  }

  /** 两条都没写开门方向、相隔 10 格：岛式站台，画在两行之间，股道号上下各一个。 */
  @Test
  void widelySpacedTracksWithoutDoorsShareAnIslandPlatform() {
    assertIsland(YardFixtures.twoPlatforms(10), 10);
  }

  /** 两条都没写开门方向、只隔 4 格（两侧式站台常见的间距，中间空着或只有栏杆）或 8 格（中间可能夹着图里没有的正线）：各自画在外侧。 */
  @Test
  void closeTracksWithoutDoorsGetPlatformsOnTheOutside() {
    assertOutside(YardFixtures.twoPlatforms(8), 8);
    assertOutside(YardFixtures.twoPlatforms(4), 4);
    assertOutside(YardFixtures.twoPlatforms(2), 2);
  }

  /** 开门相对：岛式站台，哪怕只隔 4 格。只有一条写了、朝着对方开也算；写了两侧都开的也算。 */
  @Test
  void doorsFacingEachOtherMakeAnIsland() {
    assertIsland(doors(4, "S", "N"), 4);
    assertIsland(doors(4, "s", ""), 4);
    assertIsland(doors(4, "", "NORTH"), 4);
    assertIsland(doors(4, "BOTH", "N"), 4);
  }

  /** 有一条背着对方开：不是岛式，哪怕隔得很宽；侧式站台画在开门那一侧。 */
  @Test
  void doorsFacingAwayMakeSidePlatforms() {
    assertOutside(doors(14, "N", "S"), 14);
    assertOutside(doors(6, "N", ""), 6);

    YardGeometry geometry = geometry(doors(4, "S", "S"));
    YardGeometry.Point one = geometry.pointOf(cell(0, 0, false)).orElseThrow();
    YardGeometry.Point two = geometry.pointOf(cell(0, 4, false)).orElseThrow();
    assertEquals(2, geometry.platforms().size(), () -> "两块侧式站台: " + geometry.platforms());
    YardGeometry.Platform first = geometry.platforms().get(0);
    YardGeometry.Platform second = geometry.platforms().get(1);
    assertTrue(first.top() > one.y() && first.bottom() < two.y(), () -> "1 道朝南开，站台在南侧: " + first);
    assertTrue(second.top() > two.y(), () -> "2 道朝南开，站台在南侧: " + second);
  }

  /** 南北向的站：开门方向按世界方位，转成横排后照样判断（东在上，所以 2 道画在上面）。 */
  @Test
  void doorsWorkOnNorthSouthStations() {
    SimpleRailGraph facing =
        YardFixtures.twoPlatforms(4, 0, Optional.of("E"), Optional.of("W"), true);
    SimpleRailGraph away =
        YardFixtures.twoPlatforms(4, 0, Optional.of("W"), Optional.of("E"), true);

    assertEquals(1, geometry(facing).platforms().size(), () -> "相对开: " + geometry(facing));
    assertEquals(2, geometry(away).platforms().size(), () -> "背对开: " + geometry(away));
  }

  /** 节点坐标取的是牌子，牌子常在轨道外侧一两格：间距按轨道量，牌子隔 6 格、轨道只隔 4 格时仍是两侧式。 */
  @Test
  void gapsAreMeasuredBetweenRailsNotSigns() {
    YardGeometry geometry =
        geometry(YardFixtures.twoPlatforms(4, 1, Optional.empty(), Optional.empty(), false));

    assertEquals(2, geometry.platforms().size(), () -> "两块侧式站台: " + geometry.platforms());
  }

  /** 两条站台股道中间夹着一条正线：隔得再宽也不是两线共用的一块岛式站台。 */
  @Test
  void aTrackInBetweenRulesOutAnIsland() {
    assertIsland(YardFixtures.twoPlatforms(12), 12);
    YardGeometry geometry = geometry(YardFixtures.twoPlatformsAroundAThroughTrack(12));

    assertEquals(2, geometry.platforms().size(), () -> "两块侧式站台: " + geometry.platforms());
  }

  private static SimpleRailGraph doors(int gap, String door1, String door2) {
    return YardFixtures.twoPlatforms(
        gap, 0, Optional.of(door1).filter(s -> !s.isEmpty()), Optional.of(door2), false);
  }

  private static void assertIsland(SimpleRailGraph graph, int gap) {
    YardGeometry geometry = geometry(graph);
    YardGeometry.Point one = geometry.pointOf(cell(0, 0, false)).orElseThrow();
    YardGeometry.Point two = geometry.pointOf(cell(0, gap, false)).orElseThrow();

    assertEquals(1, geometry.platforms().size(), () -> "一块岛式站台: " + geometry.platforms());
    YardGeometry.Platform island = geometry.platforms().get(0);
    assertEquals(List.of("1", "2"), island.tracks());
    assertTrue(island.top() > one.y() && island.bottom() < two.y(), () -> "夹在两行之间: " + island);
    assertTrue(island.left() <= one.x() && island.right() > one.x(), "站台节点在站台块范围内");
  }

  private static void assertOutside(SimpleRailGraph graph, int gap) {
    YardGeometry geometry = geometry(graph);
    YardGeometry.Point one = geometry.pointOf(cell(0, 0, false)).orElseThrow();
    YardGeometry.Point two = geometry.pointOf(cell(0, gap, false)).orElseThrow();

    assertEquals(2, geometry.platforms().size(), () -> "两块侧式站台: " + geometry.platforms());
    YardGeometry.Platform north = geometry.platforms().get(0);
    YardGeometry.Platform south = geometry.platforms().get(1);
    assertEquals(List.of("1"), north.tracks());
    assertTrue(north.bottom() <= one.y(), () -> "1 道的站台在北侧: " + north);
    assertEquals(List.of("2"), south.tracks());
    assertTrue(south.top() > two.y(), () -> "2 道的站台在南侧: " + south);
  }

  @Test
  void platformsStopShortOfTheNextNodeOnTheirRow() {
    YardGeometry geometry = geometry(YardFixtures.graph());
    YardGeometry.Segment east = path(geometry, EdgeId.undirected(P1, SW2)).segments().get(0);

    for (YardGeometry.Platform platform : geometry.platforms()) {
      assertTrue(platform.right() < east.x1(), () -> "不越过东头道岔: " + platform);
    }
  }

  /** 控制区里一条贯通的线（车库—ABC—区间点—DEF）整条画在同一行。 */
  @Test
  void aThroughLineStaysOnOneRow() {
    YardGeometry geometry =
        zone(
            YardFixtures.corridor(),
            YardArea.Member.station("ABC"),
            YardArea.Member.station("DEF"),
            YardArea.Member.depot("DEP"));
    int y = geometry.pointOf(cell(0, 0, false)).orElseThrow().y();

    for (int x : new int[] {-60, -30, 100, 150, 200}) {
      assertEquals(y, geometry.pointOf(cell(x, 0, false)).orElseThrow().y(), () -> "x=" + x);
    }
  }

  /** 中途岔出的线另起一行；几个车站的站台各带站码，站名写不下时改写站码。 */
  @Test
  void aBranchingLineGetsItsOwnRow() {
    YardGeometry geometry =
        zone(
            YardFixtures.junction(),
            YardArea.Member.station("ABC"),
            YardArea.Member.station("DEF"));
    YardGeometry.Point abc = geometry.pointOf(cell(0, 0, false)).orElseThrow();
    YardGeometry.Point def = geometry.pointOf(cell(200, 40, false)).orElseThrow();

    assertTrue(def.y() != abc.y(), () -> "两站不在同一行: " + abc + " " + def);
    assertEquals(
        Set.of(Optional.of("ABC"), Optional.of("DEF")),
        geometry.platforms().stream().map(YardGeometry.Platform::code).collect(Collectors.toSet()));
  }

  /** 不同区间的水平线不在同一行上重叠（端点相接不算），否则看不出是两条线。 */
  @Test
  void horizontalLinesOfDifferentEdgesNeverRunAlongEachOther() {
    for (YardGeometry geometry : samples()) {
      List<int[]> lines = new ArrayList<>();
      List<EdgeId> owners = new ArrayList<>();
      for (YardGeometry.Path path : geometry.paths()) {
        for (YardGeometry.Segment s : path.segments()) {
          if (s.y0() == s.y1() && s.x0() != s.x1()) {
            lines.add(new int[] {s.y0(), Math.min(s.x0(), s.x1()), Math.max(s.x0(), s.x1())});
            owners.add(path.edge());
          }
        }
      }
      for (int i = 0; i < lines.size(); i++) {
        for (int j = i + 1; j < lines.size(); j++) {
          int[] a = lines.get(i);
          int[] b = lines.get(j);
          if (owners.get(i).equals(owners.get(j)) || a[0] != b[0]) {
            continue;
          }
          int shared = Math.min(a[2], b[2]) - Math.max(a[1], b[1]);
          EdgeId first = owners.get(i);
          EdgeId second = owners.get(j);
          assertTrue(shared <= 2, () -> first + " 与 " + second + " 重叠 " + shared + " 像素");
        }
      }
    }
  }

  /** 斜线不穿过站台块，免得压在站台与股道号上。 */
  @Test
  void diagonalsStayOutOfPlatforms() {
    for (YardGeometry geometry : samples()) {
      for (YardGeometry.Platform platform : geometry.platforms()) {
        for (YardGeometry.Path path : geometry.paths()) {
          for (YardGeometry.Segment s : path.segments()) {
            if (s.y0() == s.y1()) {
              continue;
            }
            int steps = Math.abs(s.y1() - s.y0());
            for (int i = 0; i <= steps; i++) {
              int x = s.x0() + (s.x1() - s.x0()) * i / steps;
              int y = s.y0() + (s.y1() - s.y0()) * i / steps;
              boolean inside =
                  x > platform.left()
                      && x < platform.right()
                      && y > platform.top()
                      && y < platform.bottom();
              assertFalse(inside, () -> path.edge() + " 穿过站台 " + platform);
            }
          }
        }
      }
    }
  }

  @Test
  void principalAngleSnapsToFortyFiveDegrees() {
    List<double[]> alongZ = List.of(new double[] {0, 0}, new double[] {1, 50});
    List<double[]> diagonal = List.of(new double[] {0, 0}, new double[] {30, 31});

    assertEquals(Math.PI / 2, YardGeometry.principalAngle(alongZ), 1e-9);
    assertEquals(Math.PI / 4, YardGeometry.principalAngle(diagonal), 1e-9);
    assertEquals(0.0, YardGeometry.principalAngle(List.of(new double[] {3, 3})));
  }

  private static YardGeometry geometry(SimpleRailGraph graph) {
    YardArea area = YardArea.of(graph, "SURC", "ABC").orElseThrow();
    return YardGeometry.of(
        graph,
        area,
        graph.interlockingState().footprintCellsByEdge(),
        RECT,
        code -> code.equals("XYZ") ? "西站" : code);
  }

  private static YardGeometry zone(SimpleRailGraph graph, YardArea.Member... members) {
    YardArea area = YardArea.of(graph, "SURC", List.of(members)).orElseThrow();
    return YardGeometry.of(
        graph, area, graph.interlockingState().footprintCellsByEdge(), RECT, code -> code);
  }

  /** 几种站场：单站带两头道岔、走廊式控制区、中途岔出的控制区。 */
  private static List<YardGeometry> samples() {
    return List.of(
        geometry(YardFixtures.graph()),
        geometry(YardFixtures.twoPlatforms(4)),
        zone(
            YardFixtures.corridor(),
            YardArea.Member.station("ABC"),
            YardArea.Member.station("DEF"),
            YardArea.Member.depot("DEP")),
        zone(
            YardFixtures.junction(),
            YardArea.Member.station("ABC"),
            YardArea.Member.station("DEF")));
  }

  private static YardGeometry.Path path(YardGeometry geometry, EdgeId edge) {
    return geometry.paths().stream()
        .filter(path -> path.edge().equals(edge))
        .findFirst()
        .orElseThrow();
  }

  private static void assertInside(int x, int y) {
    assertTrue(
        x >= RECT.left()
            && x < RECT.left() + RECT.width()
            && y >= RECT.top()
            && y < RECT.top() + RECT.height(),
        () -> "超出区域: " + x + "," + y);
  }
}
