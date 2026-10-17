package org.fetarute.fetaruteTCAddon.display.pids.yard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;

/**
 * 站场图的几何：每条区间投影到屏幕上的像素线段，以及股道号、去向的位置。
 *
 * <ul>
 *   <li>轨道取自建图时记下的每条区间的轨道方块（足迹）。方块本身没有先后顺序，相邻（同一区间内前后左右一格、高差不超过一格）的方块连成线段；
 *       斜向相邻的只在没有直角拐点可走时才连，免得台阶状轨道画成一串三角形。
 *   <li>区间没有足迹（还没按新格式建图）时用两端节点之间的直线代替，并记为不完整。
 *   <li>按全部轨道点的主方向旋转成横向，主方向取最近的 45° 倍数，正东西、正南北与斜 45° 的站场都画成水平。
 *   <li>顺着轨道的方向按宽度铺满；垂直方向单独放大（每格最多 {@value #CROSS_SCALE} 像素），让相邻股道分得开。真实信号楼的站场图本来就是示意图，不追求比例。
 *   <li>出口区间只画离站场节点 {@value #STUB_BLOCKS} 格以内的一段，末端写去向。
 * </ul>
 *
 * <p>只依赖调度图快照，算好后不可变；站台屏按快照缓存，图重建后重算。相等按对象身份，屏幕内容标识比较它不需要逐点比较。
 */
public final class YardGeometry {

  /** 出口区间画多长（格）。 */
  public static final double STUB_BLOCKS = 24.0;

  /** 垂直于轨道方向每格最多放大到多少像素：常见的 4 格股道间距画成 24 像素，45° 道岔画出来约 50°。 */
  public static final double CROSS_SCALE = 6.0;

  private static final int[][] ORTHOGONAL = {{1, 0}, {0, 1}};
  private static final int[][] DIAGONAL = {{1, 1}, {1, -1}};

  /** 像素点。 */
  public record Point(int x, int y) {}

  /** 像素线段；两端相同时是一个点。 */
  public record Segment(int x0, int y0, int x1, int y1) {}

  /** 一条区间的像素线段。 */
  public record Path(EdgeId edge, List<Segment> segments) {
    public Path {
      Objects.requireNonNull(edge, "edge");
      segments = List.copyOf(segments);
    }
  }

  /**
   * 标签。
   *
   * @param at 锚点：股道号为站台节点的位置，去向为出口末端
   * @param text 文字
   * @param alignRight 去向在右半边时右对齐，免得伸出画面
   */
  public record Label(Point at, String text, boolean alignRight) {}

  /**
   * 画轨道的区域（像素）。
   *
   * @param left 左
   * @param top 上
   * @param width 宽
   * @param height 高
   */
  public record Rect(int left, int top, int width, int height) {}

  private final List<Path> paths;
  private final List<Label> tracks;
  private final List<Label> exits;
  private final Map<RailFootprintCell, Point> cells;
  private final boolean partial;

  private YardGeometry(
      List<Path> paths,
      List<Label> tracks,
      List<Label> exits,
      Map<RailFootprintCell, Point> cells,
      boolean partial) {
    this.paths = List.copyOf(paths);
    this.tracks = List.copyOf(tracks);
    this.exits = List.copyOf(exits);
    this.cells = Map.copyOf(cells);
    this.partial = partial;
  }

  /** 各区间的像素线段，按区间排序。 */
  public List<Path> paths() {
    return paths;
  }

  /** 股道号。 */
  public List<Label> tracks() {
    return tracks;
  }

  /** 去向：同一侧同名的只留一个。 */
  public List<Label> exits() {
    return exits;
  }

  /** 有区间缺少轨道方块，用直线代替了。 */
  public boolean partial() {
    return partial;
  }

  /** 轨道方块在屏幕上的位置；不在站场图上时为空（车身定位用）。 */
  public Optional<Point> pointOf(RailFootprintCell cell) {
    return Optional.ofNullable(cells.get(cell));
  }

  /**
   * 算出站场图的几何。
   *
   * @param graph 调度图
   * @param area 站场
   * @param footprints 区间的轨道方块；缺的区间用直线代替
   * @param rect 画轨道的区域
   * @param exitName 去向代码 → 显示的名字
   */
  public static YardGeometry of(
      RailGraph graph,
      YardArea area,
      Map<EdgeId, Set<RailFootprintCell>> footprints,
      Rect rect,
      Function<String, String> exitName) {
    Objects.requireNonNull(graph, "graph");
    Objects.requireNonNull(area, "area");
    Objects.requireNonNull(footprints, "footprints");
    Objects.requireNonNull(rect, "rect");
    Objects.requireNonNull(exitName, "exitName");
    Map<EdgeId, Shape> shapes = new LinkedHashMap<>();
    boolean partial = false;
    for (EdgeId edge : area.edges().stream().sorted().toList()) {
      Set<RailFootprintCell> cells = footprints.getOrDefault(edge, Set.of());
      if (cells.isEmpty()) {
        partial = true;
        Optional<Shape> line = straight(graph, edge.a(), edge.b());
        line.ifPresent(shape -> shapes.put(edge, shape));
      } else {
        shapes.put(edge, Shape.of(cells));
      }
    }
    Map<EdgeId, double[]> stubEnds = new HashMap<>();
    for (YardArea.Exit exit : area.exits()) {
      Optional<Vector> inner = graph.findNode(exit.inner()).map(RailNode::worldPosition);
      if (inner.isEmpty()) {
        continue;
      }
      Set<RailFootprintCell> near = new LinkedHashSet<>();
      for (RailFootprintCell cell : footprints.getOrDefault(exit.edge(), Set.of())) {
        if (horizontal(cell, inner.get()) <= STUB_BLOCKS) {
          near.add(cell);
        }
      }
      if (!near.isEmpty()) {
        RailFootprintCell end =
            near.stream()
                .max(Comparator.comparingDouble(cell -> horizontal(cell, inner.get())))
                .orElseThrow();
        shapes.put(exit.edge(), Shape.of(near));
        stubEnds.put(exit.edge(), center(end));
        continue;
      }
      partial = true;
      Optional<Vector> outer = graph.findNode(exit.outer()).map(RailNode::worldPosition);
      if (outer.isPresent()) {
        double[] from = {inner.get().getX(), inner.get().getZ()};
        double[] to = toward(from, outer.get(), STUB_BLOCKS);
        shapes.put(exit.edge(), new Shape(List.of(new double[] {from[0], from[1], to[0], to[1]})));
        stubEnds.put(exit.edge(), to);
      }
    }
    List<double[]> points = new ArrayList<>();
    shapes.values().forEach(shape -> shape.addEnds(points));
    for (NodeId node : area.nodes()) {
      graph
          .findNode(node)
          .map(RailNode::worldPosition)
          .ifPresent(at -> points.add(new double[] {at.getX(), at.getZ()}));
    }
    Projection projection = Projection.fit(points, rect);
    List<Path> paths = new ArrayList<>();
    shapes.forEach((edge, shape) -> paths.add(new Path(edge, shape.project(projection))));
    Map<RailFootprintCell, Point> cells = new HashMap<>();
    for (Shape shape : shapes.values()) {
      for (RailFootprintCell cell : shape.cells) {
        double[] c = center(cell);
        cells.put(cell, projection.apply(c[0], c[1]));
      }
    }
    List<Label> tracks = new ArrayList<>();
    for (YardArea.Track track : area.tracks()) {
      graph
          .findNode(track.node())
          .map(RailNode::worldPosition)
          .ifPresent(
              at ->
                  tracks.add(
                      new Label(projection.apply(at.getX(), at.getZ()), track.number(), false)));
    }
    int middle = rect.left() + rect.width() / 2;
    Map<String, Label> exits = new LinkedHashMap<>();
    for (YardArea.Exit exit : area.exits()) {
      double[] end = stubEnds.get(exit.edge());
      if (end == null || exit.towards().isEmpty()) {
        continue;
      }
      Point at = projection.apply(end[0], end[1]);
      String text = exitName.apply(exit.towards().get());
      boolean right = at.x() > middle;
      exits.putIfAbsent(text + (right ? "|R" : "|L"), new Label(at, text, right));
    }
    return new YardGeometry(paths, tracks, List.copyOf(exits.values()), cells, partial);
  }

  private static Optional<Shape> straight(RailGraph graph, NodeId a, NodeId b) {
    Optional<Vector> from = graph.findNode(a).map(RailNode::worldPosition);
    Optional<Vector> to = graph.findNode(b).map(RailNode::worldPosition);
    if (from.isEmpty() || to.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new Shape(
            List.of(
                new double[] {
                  from.get().getX(), from.get().getZ(), to.get().getX(), to.get().getZ()
                })));
  }

  private static double[] toward(double[] from, Vector target, double length) {
    double dx = target.getX() - from[0];
    double dz = target.getZ() - from[1];
    double distance = Math.sqrt(dx * dx + dz * dz);
    if (distance <= length || distance == 0.0) {
      return new double[] {target.getX(), target.getZ()};
    }
    return new double[] {from[0] + dx / distance * length, from[1] + dz / distance * length};
  }

  private static double horizontal(RailFootprintCell cell, Vector at) {
    double[] c = center(cell);
    double dx = c[0] - at.getX();
    double dz = c[1] - at.getZ();
    return Math.sqrt(dx * dx + dz * dz);
  }

  private static double[] center(RailFootprintCell cell) {
    return new double[] {cell.x() + 0.5, cell.z() + 0.5};
  }

  /** 一条区间在世界平面上的线段（x、z）与它的轨道方块。 */
  private static final class Shape {
    private final List<double[]> segments;
    private final Set<RailFootprintCell> cells;

    private Shape(List<double[]> segments) {
      this(segments, Set.of());
    }

    private Shape(List<double[]> segments, Set<RailFootprintCell> cells) {
      this.segments = segments;
      this.cells = cells;
    }

    /** 相邻方块连成线段；孤立的方块画成一个点。 */
    static Shape of(Set<RailFootprintCell> cells) {
      Set<Long> flat = new HashSet<>();
      for (RailFootprintCell cell : cells) {
        flat.add(flatKey(cell.x(), cell.z()));
      }
      List<double[]> segments = new ArrayList<>();
      Set<RailFootprintCell> linked = new HashSet<>();
      for (RailFootprintCell cell : cells) {
        for (int[] step : ORTHOGONAL) {
          link(cells, cell, step, segments, linked);
        }
        for (int[] step : DIAGONAL) {
          boolean corner =
              flat.contains(flatKey(cell.x() + step[0], cell.z()))
                  || flat.contains(flatKey(cell.x(), cell.z() + step[1]));
          if (!corner) {
            link(cells, cell, step, segments, linked);
          }
        }
      }
      for (RailFootprintCell cell : cells) {
        if (!linked.contains(cell)) {
          double[] c = center(cell);
          segments.add(new double[] {c[0], c[1], c[0], c[1]});
        }
      }
      return new Shape(segments, Set.copyOf(cells));
    }

    private static void link(
        Set<RailFootprintCell> cells,
        RailFootprintCell cell,
        int[] step,
        List<double[]> segments,
        Set<RailFootprintCell> linked) {
      for (int dy = -1; dy <= 1; dy++) {
        RailFootprintCell next =
            new RailFootprintCell(cell.x() + step[0], cell.y() + dy, cell.z() + step[1]);
        if (cells.contains(next)) {
          double[] a = center(cell);
          double[] b = center(next);
          segments.add(new double[] {a[0], a[1], b[0], b[1]});
          linked.add(cell);
          linked.add(next);
        }
      }
    }

    private static long flatKey(int x, int z) {
      return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    void addEnds(List<double[]> points) {
      for (double[] s : segments) {
        points.add(new double[] {s[0], s[1]});
        points.add(new double[] {s[2], s[3]});
      }
    }

    /** 投影到像素并去掉重合的线段（缩得很小时许多方块落在同一像素）。 */
    List<Segment> project(Projection projection) {
      Set<Segment> out = new LinkedHashSet<>();
      for (double[] s : segments) {
        Point a = projection.apply(s[0], s[1]);
        Point b = projection.apply(s[2], s[3]);
        boolean ordered = a.x() < b.x() || (a.x() == b.x() && a.y() <= b.y());
        out.add(
            ordered
                ? new Segment(a.x(), a.y(), b.x(), b.y())
                : new Segment(b.x(), b.y(), a.x(), a.y()));
      }
      return List.copyOf(out);
    }
  }

  /** 世界平面（x、z）到屏幕像素：旋转到主方向、横纵分开缩放、在区域内居中。 */
  record Projection(
      double cos,
      double sin,
      double minU,
      double minV,
      double scaleU,
      double scaleV,
      double offsetX,
      double offsetY) {

    static Projection fit(List<double[]> points, Rect rect) {
      double angle = principalAngle(points);
      double cos = Math.cos(angle);
      double sin = Math.sin(angle);
      double minU = Double.MAX_VALUE;
      double maxU = -Double.MAX_VALUE;
      double minV = Double.MAX_VALUE;
      double maxV = -Double.MAX_VALUE;
      for (double[] p : points) {
        double u = p[0] * cos + p[1] * sin;
        double v = -p[0] * sin + p[1] * cos;
        minU = Math.min(minU, u);
        maxU = Math.max(maxU, u);
        minV = Math.min(minV, v);
        maxV = Math.max(maxV, v);
      }
      if (points.isEmpty()) {
        minU = maxU = minV = maxV = 0.0;
      }
      double spanU = Math.max(1.0, maxU - minU);
      double spanV = Math.max(1.0, maxV - minV);
      double width = Math.max(1, rect.width() - 1);
      double height = Math.max(1, rect.height() - 1);
      double uniform = Math.min(width / spanU, height / spanV);
      double scaleV = Math.min(height / spanV, Math.max(uniform, CROSS_SCALE));
      double offsetX = rect.left() + (width - spanU * uniform) / 2.0;
      double offsetY = rect.top() + (height - spanV * scaleV) / 2.0;
      return new Projection(cos, sin, minU, minV, uniform, scaleV, offsetX, offsetY);
    }

    Point apply(double x, double z) {
      double u = x * cos + z * sin;
      double v = -x * sin + z * cos;
      return new Point(
          (int) Math.round(offsetX + (u - minU) * scaleU),
          (int) Math.round(offsetY + (v - minV) * scaleV));
    }

    /** 点集的主方向，取最近的 45° 倍数；点太少或没有方向时为 0（东向右、北向上）。 */
    static double principalAngle(List<double[]> points) {
      if (points.size() < 2) {
        return 0.0;
      }
      double meanX = 0.0;
      double meanZ = 0.0;
      for (double[] p : points) {
        meanX += p[0];
        meanZ += p[1];
      }
      meanX /= points.size();
      meanZ /= points.size();
      double xx = 0.0;
      double zz = 0.0;
      double xz = 0.0;
      for (double[] p : points) {
        double dx = p[0] - meanX;
        double dz = p[1] - meanZ;
        xx += dx * dx;
        zz += dz * dz;
        xz += dx * dz;
      }
      if (xx == 0.0 && zz == 0.0) {
        return 0.0;
      }
      double angle = 0.5 * Math.atan2(2.0 * xz, xx - zz);
      double step = Math.PI / 4.0;
      double snapped = Math.round(angle / step) * step;
      return Math.floorMod(Math.round(snapped / step), 4L) * step;
    }
  }
}
