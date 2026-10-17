package org.fetarute.fetaruteTCAddon.display.pids.yard;

import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.P1;
import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.P2;
import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.SW1;
import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.T1;
import static org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures.cell;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.junit.jupiter.api.Test;

/** 站场图几何：旋转成横向、横纵分开缩放、相邻方块连线、缺足迹时用直线代替。 */
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
  void eastWestYardsStayHorizontalWithTracksPulledApart() {
    YardGeometry geometry = geometry(YardFixtures.graph());
    YardGeometry.Point one = geometry.pointOf(cell(0, 0, false)).orElseThrow();
    YardGeometry.Point two = geometry.pointOf(cell(0, 4, false)).orElseThrow();
    YardGeometry.Point west = geometry.pointOf(cell(-40, 0, false)).orElseThrow();

    assertEquals(one.x(), two.x(), "两股道的站台节点上下对齐");
    assertTrue(two.y() - one.y() >= 4 * 4, "4 格的股道间距至少放大到每格 4 像素");
    assertTrue(west.x() < one.x(), "西在左");
  }

  @Test
  void northSouthYardsAreRotatedToHorizontal() {
    YardGeometry geometry = geometry(YardFixtures.graph(true, false, Set.of()));
    YardGeometry.Point one = geometry.pointOf(cell(0, 0, true)).orElseThrow();
    YardGeometry.Point west = geometry.pointOf(cell(-40, 0, true)).orElseThrow();
    YardGeometry.Point two = geometry.pointOf(cell(0, 4, true)).orElseThrow();

    assertTrue(Math.abs(west.x() - one.x()) > 100, "沿线方向铺开在横向");
    assertTrue(Math.abs(west.y() - one.y()) <= 1, "正线画成水平");
    assertTrue(Math.abs(two.y() - one.y()) >= 16, "股道上下分开");
  }

  @Test
  void diagonalStairsAreJoinedIntoOneLine() {
    YardGeometry geometry = geometry(YardFixtures.graph());
    YardGeometry.Path turnout = path(geometry, EdgeId.undirected(SW1, P2));

    for (YardGeometry.Segment s : turnout.segments()) {
      assertFalse(s.x0() == s.x1() && s.y0() == s.y1(), () -> "台阶上的方块都应连上，不应剩孤立的点: " + s);
    }
    YardGeometry.Point start = geometry.pointOf(cell(-40, 0, false)).orElseThrow();
    YardGeometry.Point stair = geometry.pointOf(cell(-39, 1, false)).orElseThrow();
    assertTrue(
        turnout.segments().stream()
            .anyMatch(
                s ->
                    (s.x0() == start.x()
                            && s.y0() == start.y()
                            && s.x1() == stair.x()
                            && s.y1() == stair.y())
                        || (s.x1() == start.x()
                            && s.y1() == start.y()
                            && s.x0() == stair.x()
                            && s.y0() == stair.y())),
        "斜向相邻、没有直角拐点时连斜线");
  }

  @Test
  void edgesWithoutFootprintsAreDrawnStraightAndFlagged() {
    EdgeId throat = EdgeId.undirected(T1, P1);
    SimpleRailGraph graph = YardFixtures.graph(false, false, Set.of(throat));
    YardGeometry geometry = geometry(graph);

    assertTrue(geometry.partial());
    YardGeometry.Path path = path(geometry, throat);
    assertEquals(1, path.segments().size(), "两端节点之间一条直线");
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

    assertTrue(geometry.pointOf(cell(-100, 0, false)).isEmpty(), "出口只画靠站场的 24 格");
    assertTrue(geometry.pointOf(cell(-60, 0, false)).isPresent());
  }

  @Test
  void principalAngleSnapsToFortyFiveDegrees() {
    List<double[]> alongZ = List.of(new double[] {0, 0}, new double[] {1, 50});
    List<double[]> diagonal = List.of(new double[] {0, 0}, new double[] {30, 31});

    assertEquals(Math.PI / 2, YardGeometry.Projection.principalAngle(alongZ), 1e-9);
    assertEquals(Math.PI / 4, YardGeometry.Projection.principalAngle(diagonal), 1e-9);
    assertEquals(0.0, YardGeometry.Projection.principalAngle(List.of(new double[] {3, 3})));
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
