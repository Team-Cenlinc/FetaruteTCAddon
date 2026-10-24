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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
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

  /**
   * 四条股道相隔 4、8、4 格、都没写开门方向：中间两条按间距各自要把侧式站台放进它俩之间那 8 格（外侧都只隔 4 格），两块会叠在一起，
   * 所以画成两线共用的一块岛式站台；最外两条的站台在外侧。
   */
  @Test
  void sidePlatformsThatWouldShareOneGapBecomeAnIsland() {
    YardGeometry geometry = geometry(YardFixtures.stationTracks(0, 4, 12, 16));

    List<List<String>> tracks =
        geometry.platforms().stream().map(YardGeometry.Platform::tracks).toList();
    assertEquals(
        List.of(List.of("1"), List.of("2", "3"), List.of("4")),
        tracks,
        () -> "站台块: " + geometry.platforms());
    assertNoPlatformsOverlap(geometry);
  }

  /** 一条线上挨着的两个车站、画面又窄：两站之间留够两块站台的宽度（按里程只分到四十来像素），站台块在中间分界，不叠在一起。 */
  @Test
  void neighbouringStationsDoNotShareTheirPlatformBlocks() {
    SimpleRailGraph graph = YardFixtures.closeStations();
    YardArea area =
        YardArea.of(
                graph,
                "SURC",
                List.of(
                    YardArea.Member.station("ABC"),
                    YardArea.Member.station("DEF"),
                    YardArea.Member.station("XYZ")))
            .orElseThrow();
    YardGeometry geometry =
        YardGeometry.of(
            graph,
            area,
            graph.interlockingState().footprintCellsByEdge(),
            new YardGeometry.Rect(0, 0, 200, 80),
            code -> code);

    assertEquals(3, geometry.platforms().size(), () -> "站台块: " + geometry.platforms());
    assertNoPlatformsOverlap(geometry);
    int abc = geometry.nodeAt(P1).orElseThrow().x();
    int def = geometry.nodeAt(YardFixtures.Q1).orElseThrow().x();
    assertTrue(def - abc >= 2 * YardGeometry.PLATFORM_ROOM, () -> "两站相隔 " + (def - abc));
  }

  /** 挨着的两站都是岛式站台、两条股道的站台节点错开：岛式站台取两条股道的并集，但不越过与邻站的中线分界。 */
  @Test
  void neighbouringIslandsWithStaggeredTracksDoNotOverlap() {
    SimpleRailGraph graph = YardFixtures.closeIslands();
    YardArea area =
        YardArea.of(
                graph,
                "SURC",
                List.of(
                    YardArea.Member.station("ABC"),
                    YardArea.Member.station("DEF"),
                    YardArea.Member.station("XYZ")))
            .orElseThrow();
    YardGeometry geometry =
        YardGeometry.of(
            graph,
            area,
            graph.interlockingState().footprintCellsByEdge(),
            new YardGeometry.Rect(0, 0, 220, 80),
            code -> code);

    assertTrue(
        geometry.platforms().stream().anyMatch(p -> p.tracks().size() == 2),
        () -> "两站都应是岛式站台: " + geometry.platforms());
    assertNoPlatformsOverlap(geometry);
  }

  private static void assertNoPlatformsOverlap(YardGeometry geometry) {
    List<YardGeometry.Platform> blocks = geometry.platforms();
    for (int i = 0; i < blocks.size(); i++) {
      for (int j = i + 1; j < blocks.size(); j++) {
        YardGeometry.Platform a = blocks.get(i);
        YardGeometry.Platform b = blocks.get(j);
        assertFalse(
            a.left() < b.right()
                && b.left() < a.right()
                && a.top() < b.bottom()
                && b.top() < a.bottom(),
            () -> "站台块叠在一起: " + a + " " + b);
      }
    }
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

  /** 经过点（只连两段直股的区间点）不单独占一列：按里程插在两头之间，长线不会被一串区间点拉长。 */
  @Test
  void passThroughWaypointsSitBetweenTheirNeighbours() {
    YardGeometry geometry =
        zone(
            YardFixtures.corridor(),
            YardArea.Member.station("ABC"),
            YardArea.Member.station("DEF"));
    int abc = geometry.pointOf(cell(0, 0, false)).orElseThrow().x();
    int def = geometry.pointOf(cell(200, 0, false)).orElseThrow().x();
    int waypoint = geometry.pointOf(cell(100, 0, false)).orElseThrow().x();

    assertEquals((abc + def) / 2.0, waypoint, 2.0, "区间点在两站的正中");
  }

  /** 照画库线的车库：和正线几乎垂直的库线也排在分岔道岔后面；记下库名与各条库线的尽头、车挡。 */
  @Test
  void depotTracksFanOutPastTheirSwitchAndAreLabelled() {
    YardGeometry geometry =
        zone(YardFixtures.depot(2), YardArea.Member.station("ABC"), YardArea.Member.depot("DEP"));
    int fork =
        path(geometry, EdgeId.undirected(P1, YardFixtures.depotSwitch(1))).segments().stream()
            .mapToInt(s -> Math.max(s.x0(), s.x1()))
            .max()
            .orElseThrow();

    assertEquals(1, geometry.depots().size());
    YardGeometry.Depot depot = geometry.depots().get(0);
    assertFalse(depot.panel());
    assertEquals("DEP", depot.code());
    assertEquals("DEP", depot.name(), "查不到站名时写代码");
    assertEquals(
        List.of("1", "2"), depot.tracks().stream().map(YardGeometry.DepotTrack::number).toList());
    for (YardGeometry.DepotTrack track : depot.tracks()) {
      assertTrue(track.buffer(), () -> "尽头是车挡: " + track);
      assertTrue(track.end().x() > fork, () -> "库线排在分岔道岔后面: " + track + " 道岔 x=" + fork);
      assertEquals(1, track.outward(), "库号写在尽头外侧");
      assertTrue(
          track.end().x() >= depot.left()
              && track.end().x() <= depot.right()
              && track.end().y() >= depot.top()
              && track.end().y() <= depot.bottom(),
          () -> "尽头在车库范围内: " + depot);
    }
  }

  /** 库线超过 4 条的车库收成面板：库内不再照画，每条库线是面板里的一条短条，库线上的车厢落在短条上，库门里的道岔连线不画。 */
  @Test
  void depotsWithManyTracksCollapseIntoAPanel() {
    YardGeometry geometry =
        zone(YardFixtures.depot(5), YardArea.Member.station("ABC"), YardArea.Member.depot("DEP"));

    assertEquals(1, geometry.depots().size());
    YardGeometry.Depot depot = geometry.depots().get(0);
    assertTrue(depot.panel());
    assertEquals(
        List.of("1", "2", "3", "4", "5"),
        depot.tracks().stream().map(YardGeometry.DepotTrack::number).toList());
    for (int k = 1; k <= 5; k++) {
      EdgeId edge =
          EdgeId.undirected(YardFixtures.depotSwitch(Math.min(k, 4)), YardFixtures.depotTrack(k));
      List<YardGeometry.Segment> bar = path(geometry, edge).segments();
      assertEquals(1, bar.size(), () -> "一条短条: " + bar);
      YardGeometry.Segment s = bar.get(0);
      assertEquals(s.y0(), s.y1(), "水平");
      assertTrue(
          s.x0() >= depot.left()
              && s.x1() <= depot.right()
              && s.y0() > depot.top()
              && s.y0() < depot.bottom(),
          () -> "短条在面板里: " + s + " " + depot);
    }
    YardGeometry.Point car = geometry.pointOf(cell(69, 20, false)).orElseThrow();
    assertTrue(
        car.x() >= depot.left()
            && car.x() <= depot.right()
            && car.y() > depot.top()
            && car.y() < depot.bottom(),
        () -> "库线 1 上的车厢落在面板里: " + car);
    assertTrue(
        geometry.paths().stream()
            .noneMatch(
                p ->
                    p.edge()
                        .equals(
                            EdgeId.undirected(
                                YardFixtures.depotSwitch(1), YardFixtures.depotSwitch(2)))),
        "库门里的道岔连线不画");
  }

  /** 两头都通库外的大车库也收成面板：库门在链中间也照样定位，最后一条库线只朝库内画成短条。 */
  @Test
  void depotsOpenAtBothEndsStillCollapse() {
    YardGeometry geometry =
        zone(
            YardFixtures.depot(5, true),
            YardArea.Member.station("ABC"),
            YardArea.Member.depot("DEP"));

    assertEquals(1, geometry.depots().size());
    YardGeometry.Depot depot = geometry.depots().get(0);
    assertTrue(depot.panel());
    assertEquals(5, depot.tracks().size());
    List<YardGeometry.Segment> bar =
        path(geometry, EdgeId.undirected(YardFixtures.depotSwitch(4), YardFixtures.depotTrack(5)))
            .segments();
    assertEquals(1, bar.size(), () -> "库线 5 画成一条短条: " + bar);
    assertTrue(
        bar.get(0).y0() > depot.top() && bar.get(0).y0() < depot.bottom(),
        () -> "短条在面板里: " + bar + " " + depot);
  }

  /** 从一条和它垂直的联络线接进来的平行线：分不出顺逆时按脊线走向排，西头仍在左、东头仍在右。 */
  @Test
  void chainsJoinedByAPerpendicularLinkKeepTheirOrder() {
    YardGeometry geometry = geometry(YardFixtures.perpendicularLink());

    for (int z : new int[] {0, 12}) {
      int west = geometry.pointOf(cell(5, z, false)).orElseThrow().x();
      int east = geometry.pointOf(cell(95, z, false)).orElseThrow().x();
      assertTrue(west < east, () -> "z=" + z + " 的线西在左: " + west + " / " + east);
    }
  }

  /** V 形道岔（两条斜线朝同一边离开）：两条斜线都按 45° 画，在道岔那一点汇成 Y 形，不退成别的角度的斜线。 */
  @Test
  void vShapedSwitchesMergeAsAY() {
    YardGeometry geometry = geometry(YardFixtures.scissors());
    NodeId c1 = NodeId.of("SWITCHER:world:52:64:2");
    NodeId c2 = NodeId.of("SWITCHER:world:68:64:2");
    YardGeometry.Segment centre = path(geometry, EdgeId.undirected(c1, c2)).segments().get(0);
    // 线段按横坐标排好，C1 在 C2 西边，所以中间那段的西端就是 C1
    YardGeometry.Point join = new YardGeometry.Point(centre.x0(), centre.y0());

    for (NodeId a :
        List.of(NodeId.of("SWITCHER:world:40:64:0"), NodeId.of("SWITCHER:world:40:64:4"))) {
      YardGeometry.Path arm = path(geometry, EdgeId.undirected(a, c1));
      for (YardGeometry.Segment s : arm.segments()) {
        int dx = Math.abs(s.x1() - s.x0());
        int dy = Math.abs(s.y1() - s.y0());
        assertTrue(dy == 0 || dx == dy, () -> arm.edge() + " 有不是 45° 的斜线: " + s);
      }
      assertTrue(
          arm.segments().stream()
              .anyMatch(
                  s ->
                      (s.x0() == join.x() && s.y0() == join.y())
                          || (s.x1() == join.x() && s.y1() == join.y())),
          () -> arm.edge() + " 汇到 C1 " + join + ": " + arm.segments());
    }
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

  /**
   * 控制区只有 ABC、DEF：经道岔连起两站的是区内的正线，画在同一行（实地直着走的是去区外 XYZ 的一股也一样），去 XYZ 的一股只是出口，从道岔斜着岔出。
   * 几个车站的站台各带站码，站名写不下时改写站码。
   */
  @Test
  void aLineLeavingTheZoneBranchesOffTheMainLine() {
    YardGeometry geometry =
        zone(
            YardFixtures.junction(),
            YardArea.Member.station("ABC"),
            YardArea.Member.station("DEF"));
    YardGeometry.Point abc = geometry.pointOf(cell(0, 0, false)).orElseThrow();
    YardGeometry.Point def = geometry.pointOf(cell(200, 40, false)).orElseThrow();
    YardGeometry.Point junction = geometry.nodeAt(YardFixtures.JUNCTION).orElseThrow();
    YardGeometry.Path exit =
        path(geometry, EdgeId.undirected(YardFixtures.JUNCTION, YardFixtures.TO_XYZ_FAR));

    assertEquals(abc.y(), def.y(), () -> "两站在同一行: " + abc + " " + def);
    assertEquals(abc.y(), junction.y(), () -> "道岔在正线那一行: " + junction);
    assertTrue(
        exit.segments().stream().anyMatch(s -> s.y0() != s.y1()), () -> "去 XYZ 的出口从道岔岔出: " + exit);
    assertEquals(
        Set.of(Optional.of("ABC"), Optional.of("DEF")),
        geometry.platforms().stream().map(YardGeometry.Platform::code).collect(Collectors.toSet()));
  }

  /** 分叉的两股对称斜出、只有一股通往区内时，来路、分叉道岔与区内那一股画成一条直线。 */
  @Test
  void aForkWithOneBranchInTheZoneIsDrawnStraightThrough() {
    YardGeometry geometry =
        zone(
            YardFixtures.fork(false),
            YardArea.Member.station("ABC"),
            YardArea.Member.station("DEF"));
    YardGeometry.Point stem = geometry.nodeAt(P1).orElseThrow();
    YardGeometry.Point fork = geometry.nodeAt(YardFixtures.FORK).orElseThrow();
    YardGeometry.Point def = geometry.nodeAt(YardFixtures.Q1).orElseThrow();

    assertEquals(stem.y(), fork.y(), () -> "分叉道岔在来路那一行: " + stem + " " + fork);
    assertEquals(stem.y(), def.y(), () -> "DEF 接着来路直着画: " + stem + " " + def);
  }

  /** 复线的一条中途绕一大圈（按轨道长度排会比另一条超前一大截）：绕出来以后几段内重新与另一条对齐，两头车站的两条股道上下对齐。 */
  @Test
  void aParallelTrackRealignsAfterADetour() {
    YardGeometry geometry =
        zone(
            YardFixtures.doubleTrackLoop(),
            YardArea.Member.station("ABC"),
            YardArea.Member.station("DEF"),
            YardArea.Member.station("GHI"));
    int abc1 = geometry.nodeAt(P1).orElseThrow().x();
    int abc2 = geometry.nodeAt(P2).orElseThrow().x();
    int def1 = geometry.nodeAt(YardFixtures.Q1).orElseThrow().x();
    int def2 = geometry.nodeAt(YardFixtures.Q2).orElseThrow().x();

    assertEquals(abc1, abc2, "ABC 两条股道对齐");
    assertEquals(def1, def2, "DEF 两条股道对齐");
  }

  /**
   * 同一区间相邻两个区间点之间经过的道岔按区间连成直股：哪怕实地直着走的是另一条股道的区间点，ABC 1 道仍与同一股道通往的 DEF 1 道画在一行； 1
   * 道的序号中间缺一个（另一条股道上正好有那个序号）也一样。
   */
  @Test
  void consecutiveIntervalWaypointsMakeTheThroughRoute() {
    for (boolean gap : List.of(false, true)) {
      YardGeometry geometry =
          zone(
              YardFixtures.intervalTurnout(gap),
              YardArea.Member.station("ABC"),
              YardArea.Member.station("DEF"));
      YardGeometry.Point abc = geometry.nodeAt(P1).orElseThrow();
      YardGeometry.Point turnout = geometry.nodeAt(YardFixtures.TURNOUT).orElseThrow();
      YardGeometry.Point def1 = geometry.nodeAt(YardFixtures.Q1).orElseThrow();
      YardGeometry.Point def2 = geometry.nodeAt(YardFixtures.Q2).orElseThrow();
      String label = gap ? "序号缺一个" : "序号连续";

      assertEquals(abc.y(), turnout.y(), () -> label + ": 道岔在 1 道那一行 " + abc + " " + turnout);
      assertEquals(abc.y(), def1.y(), () -> label + ": DEF 1 道接着 ABC 1 道 " + abc + " " + def1);
      assertTrue(def2.y() != abc.y(), () -> label + ": DEF 2 道从道岔岔出、另起一行 " + def2);
    }
  }

  /** 分叉的一股进站前先经过一组往回岔出区外的道岔：出口沿轨道虽然更近，但要折返才走得到，这一股仍算通往 XYZ 站，分叉照样画成 Y 形（来路一行、两股各起一行）。 */
  @Test
  void aBranchPassingAnExitTurnoutStillLeadsToItsStation() {
    YardGeometry geometry =
        zone(
            YardFixtures.forkPastExit(),
            YardArea.Member.station("ABC"),
            YardArea.Member.station("DEF"),
            YardArea.Member.station("XYZ"));
    YardGeometry.Point stem = geometry.nodeAt(P1).orElseThrow();
    YardGeometry.Point fork = geometry.nodeAt(YardFixtures.FORK).orElseThrow();
    YardGeometry.Point def = geometry.nodeAt(YardFixtures.Q1).orElseThrow();
    YardGeometry.Point xyz = geometry.nodeAt(YardFixtures.X1).orElseThrow();
    String where = "ABC " + stem + " 分叉 " + fork + " DEF " + def + " XYZ " + xyz;

    assertEquals(stem.y(), fork.y(), () -> "分叉道岔在来路那一行: " + where);
    assertTrue(def.y() != stem.y(), () -> "去 DEF 的一股另起一层: " + where);
    assertTrue(xyz.y() != stem.y(), () -> "去 XYZ 的一股另起一层: " + where);
    assertTrue(def.y() != xyz.y(), () -> "两股不在同一层: " + where);
  }

  /** 来路在区外、区内只有岔出去的两股：仍是线路分叉（两股平级），两股都不接着来路画成直线，各起一层。 */
  @Test
  void aForkWhoseStemLeavesTheZoneIsStillAFork() {
    for (boolean straightOn : List.of(false, true)) {
      YardGeometry geometry =
          zone(
              YardFixtures.forkFromOutside(straightOn),
              YardArea.Member.station("DEF"),
              YardArea.Member.station("XYZ"));
      YardGeometry.Point fork = geometry.nodeAt(YardFixtures.FORK).orElseThrow();
      YardGeometry.Point def = geometry.nodeAt(YardFixtures.Q1).orElseThrow();
      YardGeometry.Point xyz = geometry.nodeAt(YardFixtures.X1).orElseThrow();
      String where =
          (straightOn ? "一股顺着来路直走" : "两股对称斜出") + ": 分叉 " + fork + " DEF " + def + " XYZ " + xyz;

      assertTrue(def.y() != fork.y(), () -> "去 DEF 的一股另起一层: " + where);
      assertTrue(xyz.y() != fork.y(), () -> "去 XYZ 的一股另起一层: " + where);
      assertTrue(def.y() != xyz.y(), () -> "两股不在同一层: " + where);
    }
  }

  /** 出口从站外收拢处往外侧岔出：按它离道岔的横向偏移排，画在 1 道外侧（上方），不按整片的横向位置落到 1、2 道之间。 */
  @Test
  void anExitStubSitsOnTheSideItLeavesTheThroat() {
    YardGeometry geometry = geometry(YardFixtures.throatExit());
    YardGeometry.Point one = geometry.nodeAt(P1).orElseThrow();
    YardGeometry.Point two = geometry.nodeAt(P2).orElseThrow();
    YardGeometry.Point fork = geometry.nodeAt(YardFixtures.THROAT_SWITCH).orElseThrow();
    YardGeometry.Point exit = geometry.exits().get(0).at();
    String where = "1 道 " + one + " 2 道 " + two + " 道岔 " + fork + " 出口 " + exit;

    assertTrue(one.y() < two.y(), () -> "1 道在北、画在上: " + where);
    assertTrue(exit.y() < fork.y(), () -> "出口往北岔出、画在 1 道上方: " + where);
  }

  /**
   * 渡线后两股汇成单线，2 道绕远路才汇进来：从 2 道经渡线换 1 道去汇合点更近，2 道不强接成直通汇合点的直股（强接会把绕行段整条拉成一行、把 1 道挤反）， 1 道直通汇合点去
   * DEF，各节点自西向东。
   */
  @Test
  void aTrackThatDetoursToTheMergeIsNotForcedStraight() {
    YardGeometry geometry =
        zone(
            YardFixtures.crossoverBeforeMerge(),
            YardArea.Member.station("ABC"),
            YardArea.Member.station("DEF"));
    YardGeometry.Point one = geometry.nodeAt(P1).orElseThrow();
    YardGeometry.Point two = geometry.nodeAt(P2).orElseThrow();
    YardGeometry.Point cross = geometry.nodeAt(YardFixtures.CROSS_TWO).orElseThrow();
    YardGeometry.Point merge = geometry.nodeAt(YardFixtures.CROSSOVER_MERGE).orElseThrow();
    YardGeometry.Point def = geometry.nodeAt(YardFixtures.Q1).orElseThrow();
    String where = "1 道 " + one + " 2 道 " + two + " 渡线 " + cross + " 汇合 " + merge + " DEF " + def;

    assertEquals(one.y(), merge.y(), () -> "1 道直通汇合点: " + where);
    assertEquals(one.y(), def.y(), () -> "DEF 接着 1 道: " + where);
    assertEquals(two.y(), cross.y(), () -> "2 道道岔在 2 道那一行: " + where);
    assertTrue(
        one.x() < cross.x() && cross.x() < merge.x() && merge.x() < def.x(),
        () -> "自西向东: " + where);
  }

  /** 道岔的直股按轨道方块上接出去的面定：正线出了道岔就拐弯、岔线很缓时，按附近轨道的重心会把岔线当直股，正线就断成两行。 */
  @Test
  void theStraightOfASwitchIsTheRailThatLeavesOppositeTheWayIn() {
    YardGeometry geometry =
        zone(
            YardFixtures.curvingMainLine(),
            YardArea.Member.station("ABC"),
            YardArea.Member.station("DEF"));
    YardGeometry.Point abc = geometry.nodeAt(P1).orElseThrow();
    YardGeometry.Point main = geometry.nodeAt(YardFixtures.Q1).orElseThrow();
    YardGeometry.Point branch = geometry.nodeAt(YardFixtures.Q2).orElseThrow();
    String where = "ABC 1 道 " + abc + " DEF 1 道 " + main + " DEF 2 道 " + branch;

    assertEquals(abc.y(), main.y(), () -> "正线接着来路画成一行: " + where);
    assertTrue(branch.y() < abc.y(), () -> "岔线从北面岔出、画在上方: " + where);
  }

  /** 复线的两条出口同去一站，咽喉不当线路分叉：两股各自直着出站，出口短线接在各自那一行上、不斜着岔出去。 */
  @Test
  void twoExitsToTheSameStationLeaveStraight() {
    YardGeometry geometry = geometry(YardFixtures.doubleTrackThroat());
    YardGeometry.Point one = geometry.nodeAt(P1).orElseThrow();
    YardGeometry.Point two = geometry.nodeAt(P2).orElseThrow();
    List<YardGeometry.Stub> stubs = geometry.stubs();
    String where = "1 道 " + one + " 2 道 " + two + " 出口 " + stubs;

    assertEquals(2, stubs.size(), () -> where);
    for (YardGeometry.Stub stub : stubs) {
      assertEquals(stub.inner().y(), stub.toward().y(), () -> "出口直着出去: " + where);
    }
    assertEquals(
        Set.of(one.y(), two.y()),
        stubs.stream().map(stub -> stub.inner().y()).collect(Collectors.toSet()),
        () -> "两条出口各在 1、2 道那一行: " + where);
  }

  /** 只连着正线的侧线紧贴正线：不因下面有横跨全站的长侧线、旁边一层被别的侧线占着，就被往下压到别的侧线那一层。 */
  @Test
  void aSidingStaysRightUnderTheLineItLeaves() {
    YardGeometry geometry = geometry(YardFixtures.sidings());
    YardGeometry.Point main = geometry.nodeAt(P1).orElseThrow();
    YardGeometry.Point left = geometry.nodeAt(YardFixtures.SIDING_LEFT).orElseThrow();
    YardGeometry.Point under = geometry.nodeAt(YardFixtures.SIDING_UNDER_LEFT).orElseThrow();
    YardGeometry.Point right = geometry.nodeAt(YardFixtures.SIDING_RIGHT).orElseThrow();
    String where = "正线 " + main + " 西侧线 " + left + " 其下 " + under + " 东侧线 " + right;

    assertTrue(left.y() > main.y() && under.y() > left.y(), () -> "西头两条侧线依次在下: " + where);
    assertEquals(left.y(), right.y(), () -> "东头侧线与西头侧线同在正线下一层: " + where);
  }

  /** 排布不随轨道方块的遍历次序变：调度图给的集合次序每次启动都可能不同，同一张图要画成一样。 */
  @Test
  void layoutDoesNotDependOnTheOrderOfFootprintCells() {
    List<SimpleRailGraph> graphs =
        List.of(
            YardFixtures.graph(),
            YardFixtures.scissors(),
            YardFixtures.triangle(),
            YardFixtures.junction(),
            YardFixtures.doubleTrackLoop(),
            YardFixtures.tiedSpur());
    List<YardArea.Member> members =
        List.of(
            YardArea.Member.station("ABC"),
            YardArea.Member.station("DEF"),
            YardArea.Member.station("GHI"),
            YardArea.Member.station("XYZ"));
    for (SimpleRailGraph graph : graphs) {
      YardArea area = YardArea.of(graph, "SURC", members).orElseThrow();
      YardGeometry ascending = withCellOrder(graph, area, false);
      YardGeometry descending = withCellOrder(graph, area, true);

      assertEquals(ascending.paths(), descending.paths());
      assertEquals(ascending.platforms(), descending.platforms());
      assertEquals(ascending.stubs(), descending.stubs());
    }
  }

  private static YardGeometry withCellOrder(
      SimpleRailGraph graph, YardArea area, boolean descending) {
    Map<EdgeId, Set<RailFootprintCell>> cells = new LinkedHashMap<>();
    graph
        .interlockingState()
        .footprintCellsByEdge()
        .forEach(
            (edge, set) -> {
              List<RailFootprintCell> sorted = new ArrayList<>(set);
              sorted.sort(descending ? Comparator.reverseOrder() : Comparator.naturalOrder());
              cells.put(edge, new LinkedHashSet<>(sorted));
            });
    return YardGeometry.of(graph, area, cells, RECT, code -> code);
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
            // 末位记下这条区间是不是斜线带水平部分（多于一段），两条这样的线在同一点汇合成 Y 形是允许的
            lines.add(
                new int[] {
                  s.y0(),
                  Math.min(s.x0(), s.x1()),
                  Math.max(s.x0(), s.x1()),
                  path.segments().size() > 1 ? 1 : 0
                });
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
          boolean merging = a[3] == 1 && b[3] == 1 && (a[1] == b[1] || a[2] == b[2]);
          if (merging) {
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

  /** 线路分叉不分主次：岔出去的两股都不画成来路那一行的直线延续，各占一层，从分叉处朝同一方向分开——哪怕其中一股在实地是顺着来路直着走的。 */
  @Test
  void junctionBranchesAreDrawnAsEqualsLeavingTheSameWay() {
    for (boolean straightOn : List.of(false, true)) {
      YardGeometry geometry =
          zone(
              YardFixtures.fork(straightOn),
              YardArea.Member.station("ABC"),
              YardArea.Member.station("DEF"),
              YardArea.Member.station("XYZ"));
      YardGeometry.Point stem = geometry.nodeAt(P1).orElseThrow();
      YardGeometry.Point fork = geometry.nodeAt(YardFixtures.FORK).orElseThrow();
      YardGeometry.Point def = geometry.nodeAt(YardFixtures.Q1).orElseThrow();
      YardGeometry.Point xyz = geometry.nodeAt(YardFixtures.X1).orElseThrow();
      String label = straightOn ? "一股顺着来路直走" : "两股对称斜出";

      assertEquals(stem.y(), fork.y(), () -> label + ": 分叉道岔在来路那一行");
      assertTrue(def.y() != stem.y(), () -> label + ": 去 DEF 的一股另起一层 " + def + " " + stem);
      assertTrue(xyz.y() != stem.y(), () -> label + ": 去 XYZ 的一股另起一层 " + xyz + " " + stem);
      assertTrue(def.y() != xyz.y(), () -> label + ": 两股不在同一层 " + def + " " + xyz);
      assertTrue(
          def.x() > fork.x() && xyz.x() > fork.x(),
          () -> label + ": 两股都从分叉处往同一边分开 " + fork + " " + def + " " + xyz);
    }
  }

  /**
   * 三角线按实地位置排：来路在一边、岔出去的两股都在另一边，往北拐的一股在上、直着往东的一股在下，来路夹在中间；和来路垂直的 XYZ 站按它自己的走向分出 1、2
   * 道的上下；联络线两头的站台留在本站（与同站的 1 道相邻）。联络线从两站靠分叉的一头出站（U 形），所以从站台往分叉一侧伸出去再拐上去，不从站台上横穿。
   */
  @Test
  void triangleFollowsTheGround() {
    YardGeometry geometry =
        zone(
            YardFixtures.triangle(),
            YardArea.Member.station("ABC"),
            YardArea.Member.station("DEF"),
            YardArea.Member.station("XYZ"));
    YardGeometry.Point stem = geometry.nodeAt(P1).orElseThrow();
    YardGeometry.Point fork = geometry.nodeAt(YardFixtures.FORK).orElseThrow();
    YardGeometry.Point q1 = geometry.nodeAt(YardFixtures.Q1).orElseThrow();
    YardGeometry.Point q2 = geometry.nodeAt(YardFixtures.Q2).orElseThrow();
    YardGeometry.Point x1 = geometry.nodeAt(YardFixtures.X1).orElseThrow();
    YardGeometry.Point x2 = geometry.nodeAt(YardFixtures.X2).orElseThrow();
    String where =
        "ABC " + stem + " 分叉 " + fork + " DEF " + q1 + " " + q2 + " XYZ " + x1 + " " + x2;

    assertTrue(stem.x() < fork.x(), () -> "来路在分叉左边: " + where);
    assertTrue(q1.x() > fork.x() && x1.x() > fork.x(), () -> "两股都在分叉右边: " + where);
    assertTrue(x1.y() < x2.y(), () -> "XYZ 1 道在西，往北走时在左、画在上: " + where);
    assertTrue(x2.y() < q2.y(), () -> "往北拐的一股整个在直着往东的一股上面，不互相穿插: " + where);
    assertTrue(q2.y() < q1.y(), () -> "DEF 2 道在北、画在 1 道上面: " + where);
    int first = geometry.nodeAt(YardFixtures.CHORD_1).orElseThrow().x();
    int second = geometry.nodeAt(YardFixtures.CHORD_2).orElseThrow().x();
    assertTrue(first < q2.x(), () -> "联络线从 DEF 2 道西头（靠分叉一头）伸出: x=" + first + " " + where);
    assertTrue(second < x2.x(), () -> "联络线从 XYZ 2 道南头（靠分叉一头）进站: x=" + second + " " + where);
  }

  /** 三角线联络线折返处那条区间画成“⊂”形：从两头沿本行往外走到折返最远处，竖着连上下两行，拐角切成 45°；整段在两头外侧，不从两头之间斜穿过站台。 */
  @Test
  void theTurnOfAChordBendsOutsideBothEnds() {
    YardGeometry geometry =
        zone(
            YardFixtures.triangle(),
            YardArea.Member.station("ABC"),
            YardArea.Member.station("DEF"),
            YardArea.Member.station("XYZ"));
    YardGeometry.Path turn =
        geometry.paths().stream()
            .filter(
                path ->
                    path.segments().stream().anyMatch(s -> s.x0() == s.x1() && s.y0() != s.y1()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("没有竖着连两行的折返段: " + geometry.paths()));
    int a = geometry.nodeAt(turn.edge().a()).orElseThrow().x();
    int b = geometry.nodeAt(turn.edge().b()).orElseThrow().x();
    int left = turn.segments().stream().mapToInt(s -> Math.min(s.x0(), s.x1())).min().orElseThrow();
    int right =
        turn.segments().stream().mapToInt(s -> Math.max(s.x0(), s.x1())).max().orElseThrow();

    assertTrue(
        left < Math.min(a, b) || right > Math.max(a, b),
        () -> "折返段伸到两头外侧: " + turn + " 两头 x=" + a + "," + b);
    for (YardGeometry.Segment s : turn.segments()) {
      int dx = Math.abs(s.x1() - s.x0());
      int dy = Math.abs(s.y1() - s.y0());
      assertTrue(dx == 0 || dy == 0 || dx == dy, () -> "只有水平、竖直与 45° 拐角: " + s);
      for (YardGeometry.Platform platform : geometry.platforms()) {
        assertFalse(
            Math.max(s.x0(), s.x1()) > platform.left()
                && Math.min(s.x0(), s.x1()) < platform.right()
                && Math.max(s.y0(), s.y1()) > platform.top()
                && Math.min(s.y0(), s.y1()) < platform.bottom(),
            () -> "折返段压到站台块: " + s + " " + platform);
      }
    }
  }

  /** 两头都接在已排好的线上、沿轨道又长得多的线（迂回线、三角线的联络线）在两头之间均摊，不从一头按轨道长度一直排出去、冲过下一站再折回来。 */
  @Test
  void lineAnchoredAtBothEndsStaysBetweenThem() {
    YardGeometry geometry =
        zone(YardFixtures.detour(), YardArea.Member.station("ABC"), YardArea.Member.station("DEF"));
    int west = geometry.nodeAt(YardFixtures.DETOUR_WEST).orElseThrow().x();
    int first = geometry.nodeAt(YardFixtures.DETOUR_1).orElseThrow().x();
    int second = geometry.nodeAt(YardFixtures.DETOUR_2).orElseThrow().x();
    int third = geometry.nodeAt(YardFixtures.DETOUR_3).orElseThrow().x();
    int station = geometry.nodeAt(YardFixtures.Q1).orElseThrow().x();

    assertTrue(
        west < first && first < second && second < third,
        () -> "依次往东: " + west + " " + first + " " + second + " " + third);
    assertTrue(third < station, () -> "不冲过 DEF: " + third + " " + station);
  }

  /** 车厢落在自己那条区间画出的线上；同一条区间上相邻两节之间按 {@link YardGeometry#trace} 连，每一段都顺着画出的线（三角线 U 形联络线的拐弯处也不抄近路）。 */
  @Test
  void carsAndTheBodyBetweenThemStayOnTheDrawnTrack() {
    List<SimpleRailGraph> graphs =
        List.of(YardFixtures.graph(), YardFixtures.triangle(), YardFixtures.fork(true));
    for (SimpleRailGraph graph : graphs) {
      YardGeometry geometry =
          zone(
              graph,
              YardArea.Member.station("ABC"),
              YardArea.Member.station("DEF"),
              YardArea.Member.station("XYZ"));
      List<YardGeometry.Segment> drawn =
          geometry.paths().stream().flatMap(path -> path.segments().stream()).toList();
      graph
          .interlockingState()
          .footprintCellsByEdge()
          .forEach(
              (edge, cells) -> {
                List<YardGeometry.Segment> own =
                    geometry.paths().stream()
                        .filter(path -> path.edge().equals(edge))
                        .flatMap(path -> path.segments().stream())
                        .toList();
                List<RailFootprintCell> ordered =
                    cells.stream()
                        .filter(cell -> geometry.pointOf(cell).isPresent())
                        .sorted(
                            java.util.Comparator.comparingDouble(
                                cell -> {
                                  org.bukkit.util.Vector a =
                                      graph.findNode(edge.a()).orElseThrow().worldPosition();
                                  return Math.hypot(cell.x() - a.getX(), cell.z() - a.getZ());
                                }))
                        .toList();
                for (RailFootprintCell cell : ordered) {
                  YardGeometry.Point at = geometry.pointOf(cell).orElseThrow();
                  assertTrue(
                      drawn.stream().anyMatch(s -> near(at, s)),
                      () -> edge + " 的车厢 " + cell + " 落在 " + at + "，不在任何画出的线上");
                  if (!own.isEmpty() && cellOfOneEdge(graph, cell)) {
                    assertTrue(
                        own.stream().anyMatch(s -> near(at, s)),
                        () -> edge + " 的车厢 " + cell + " 落在 " + at + "，不在这条区间的线上: " + own);
                  }
                }
                for (int i = 1; i < ordered.size(); i++) {
                  List<YardGeometry.Point> body =
                      geometry.trace(ordered.get(i - 1), ordered.get(i));
                  for (int k = 1; k < body.size(); k++) {
                    YardGeometry.Point p = body.get(k - 1);
                    YardGeometry.Point q = body.get(k);
                    assertTrue(
                        drawn.stream().anyMatch(s -> near(p, s) && near(q, s)),
                        () -> edge + " 上相邻两节之间的车身 " + p + "→" + q + " 不顺着画出的线: " + body);
                  }
                }
              });
    }
  }

  /** 只属于一条区间的轨道方块（道岔那几格各股共用，落在哪一股都对）。 */
  private static boolean cellOfOneEdge(SimpleRailGraph graph, RailFootprintCell cell) {
    return graph.interlockingState().footprintCellsByEdge().values().stream()
            .filter(cells -> cells.contains(cell))
            .count()
        == 1;
  }

  /** 点在线段上（容一像素取整误差）。 */
  private static boolean near(YardGeometry.Point p, YardGeometry.Segment s) {
    double dx = s.x1() - s.x0();
    double dy = s.y1() - s.y0();
    double length = dx * dx + dy * dy;
    double t =
        length == 0.0
            ? 0.0
            : Math.max(
                0.0, Math.min(1.0, ((p.x() - s.x0()) * dx + (p.y() - s.y0()) * dy) / length));
    return Math.hypot(s.x0() + t * dx - p.x(), s.y0() + t * dy - p.y()) <= 1.0;
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
            YardArea.Member.station("DEF")),
        zone(YardFixtures.depot(2), YardArea.Member.station("ABC"), YardArea.Member.depot("DEP")),
        zone(
            YardFixtures.fork(true),
            YardArea.Member.station("ABC"),
            YardArea.Member.station("DEF"),
            YardArea.Member.station("XYZ")),
        zone(
            YardFixtures.triangle(),
            YardArea.Member.station("ABC"),
            YardArea.Member.station("DEF"),
            YardArea.Member.station("XYZ")),
        geometry(YardFixtures.perpendicularLink()),
        geometry(YardFixtures.scissors()));
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
