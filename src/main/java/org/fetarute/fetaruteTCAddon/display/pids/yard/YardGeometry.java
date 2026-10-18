package org.fetarute.fetaruteTCAddon.display.pids.yard;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
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
 * 站场图的几何：按信号楼示意图排布——股道画成水平的行，股道之间只用 45° 斜线相连，区间中间的弯道一律拉直。
 *
 * <ul>
 *   <li>节点：站场内的节点，加上每个出口的末端（出口区间只取离站场节点 {@value #STUB_BLOCKS} 格以内的一段）。
 *   <li>横坐标：沿线拉直。最长的一条直股连线作脊线，按轨道长度展开成水平（东西走向的站东在右、北在上，南北走向的南在右、东在上），弯道与几个车站之间的拐弯都拉直；
 *       其余节点按它在脊线上的垂足定先后，与脊线不平行（垂足挪不动）的支线改按轨道长度接着排。互不相连的几片轨道各取各的脊线，从最长的一片起，
 *       依次按各自脊线中点在离它最近、已经摆好的那片脊线上的位置摆放。相邻两处的距离按平方根压缩，长直线段缩短、道岔密集的咽喉相对拉开， 并保证每段跨行的区间有足够的横向距离画成 45°。
 *   <li>行：每个站台股道各占一行，落在同一条股道延长线上（横向偏差不到 {@value #SAME_ROW_BLOCKS}
 *       格）的并为一行。道岔看各条区间离开它的走向，最接近一条直线的两条是直股，
 *       道岔和直股另一头同一行；另一条是侧股，从道岔处拐出去。行沿直股传开；传不到的节点按它的横向位置另起一行。各行按横向位置排序、等距排开，行距最多 {@value #ROW_GAP}
 *       像素。
 *   <li>区间：两端同行画一条水平线；不同行画水平线加一段 45° 斜线，斜线贴着分出这条侧股的道岔，判断不出时放在中间；横向距离不够 45° 时直接连斜线。
 *   <li>车厢：按它在区间里沿轨道走过的比例落到这条区间的线上。
 *   <li>站台：每个站台股道旁一块面板色的站台，以站台节点为中心、不越过同一行相邻的节点。相邻两条站台股道之间真实间距至少 {@value #MIN_PLATFORM_BLOCKS}
 *       格时当作岛式站台、画在两行之间，否则画在放得下的一侧。
 *   <li>区间没有轨道方块（旧图）时照样画（本来就只连节点），但道岔走向只能按两端节点判断、车厢也无法定位，记为不完整。
 * </ul>
 *
 * <p>只依赖调度图快照，算好后不可变；站台屏按快照缓存，图重建后重算。相等按对象身份，屏幕内容标识比较它不需要逐点比较。
 */
public final class YardGeometry {

  /** 出口区间取多长（格）。 */
  public static final double STUB_BLOCKS = 24.0;

  /** 行距上限（像素）：股道之间放得下一行车次窗。 */
  public static final int ROW_GAP = 32;

  /** 判断道岔直股、侧股时，沿每条区间看多远（格）：道岔叉开的角度小，太近分不出来。 */
  static final double BRANCH_PROBE_BLOCKS = 16.0;

  /** 判断走向时略过道岔近旁这么多格（道岔那一格各股共用）。 */
  static final double BRANCH_SKIP_BLOCKS = 2.0;

  /** 一次跨行的斜线最多把两头之间的横向距离放大几倍。 */
  static final double MAX_STRETCH = 4.0;

  /** 轨道线宽（像素）：线从所在行往下画这么粗。 */
  public static final int TRACK_LINE = 2;

  /** 站台块以站台节点为中心向两边各伸多远（像素），不越过同一行相邻的节点。 */
  static final int PLATFORM_HALF = 72;

  /** 侧式站台块的厚度（像素），放得下一行股道号。 */
  static final int SIDE_PLATFORM_DEPTH = 14;

  /** 站台块与股道线之间的空隙（像素）。 */
  static final int PLATFORM_MARGIN = 3;

  /** 两条股道之间至少隔这么多格才放得下站台。 */
  static final double MIN_PLATFORM_BLOCKS = 3.0;

  /** 横向位置相差不到这么多（格）的站台股道并为一行。 */
  static final double SAME_ROW_BLOCKS = 1.0;

  /** 脊线取样间距（格）。 */
  static final double SPINE_SAMPLE_BLOCKS = 2.0;

  /** 脊线走向取前后各这么多个取样点之间的连线，弯道上也稳。 */
  static final int SPINE_DIRECTION_SAMPLES = 3;

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
   * 站台：面板色的块，块里写股道号。
   *
   * @param left 左
   * @param top 上
   * @param right 右（不含）
   * @param bottom 下（不含）
   * @param tracks 靠着这块站台的股道号，从上到下：岛式站台两条（上面一条写在块的上沿，下面一条写在下沿），侧式站台一条
   * @param station 站名：一张图里有几个车站时写在站台块上方，股道号才分得清；只有一个车站时为空
   */
  public record Platform(
      int left, int top, int right, int bottom, List<String> tracks, Optional<String> station) {
    public Platform {
      tracks = List.copyOf(tracks);
      station = station == null ? Optional.empty() : station;
    }
  }

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
  private final List<Platform> platforms;
  private final List<Label> exits;
  private final Map<RailFootprintCell, Point> cells;
  private final boolean partial;

  private YardGeometry(
      List<Path> paths,
      List<Platform> platforms,
      List<Label> exits,
      Map<RailFootprintCell, Point> cells,
      boolean partial) {
    this.paths = List.copyOf(paths);
    this.platforms = List.copyOf(platforms);
    this.exits = List.copyOf(exits);
    this.cells = Map.copyOf(cells);
    this.partial = partial;
  }

  /** 各区间的像素线段：站场内的区间按区间排序，其后是出口。 */
  public List<Path> paths() {
    return paths;
  }

  /** 站台块，从上到下、从左到右。 */
  public List<Platform> platforms() {
    return platforms;
  }

  /** 去向：同一侧同名的只留一个。 */
  public List<Label> exits() {
    return exits;
  }

  /** 有区间缺少轨道方块：道岔走向按两端节点判断，这些区间上的车厢无法定位。 */
  public boolean partial() {
    return partial;
  }

  /** 轨道方块在屏幕上的位置；不在站场图上时为空（车身定位用）。 */
  public Optional<Point> pointOf(RailFootprintCell cell) {
    return Optional.ofNullable(cells.get(cell));
  }

  /** 节点或出口末端。 */
  private record Spot(String key, double x, double z) {}

  /** 两个 {@link Spot} 之间的一条区间（出口只含站场一侧的那段方块）。按对象身份比较，不拿方块算哈希。 */
  private static final class Link {
    private final EdgeId edge;
    private final String a;
    private final String b;
    private final Set<RailFootprintCell> cells;

    Link(EdgeId edge, String a, String b, Set<RailFootprintCell> cells) {
      this.edge = edge;
      this.a = a;
      this.b = b;
      this.cells = cells;
    }

    EdgeId edge() {
      return edge;
    }

    String a() {
      return a;
    }

    String b() {
      return b;
    }

    Set<RailFootprintCell> cells() {
      return cells;
    }

    String other(String spot) {
      return a.equals(spot) ? b : a;
    }
  }

  /** 一行：起头节点（站台股道或另起一行的那个节点）的横向位置决定上下次序。 */
  private static final class Row {
    private final double v;

    Row(double v) {
      this.v = v;
    }

    double v() {
      return v;
    }
  }

  /**
   * 算出站场图的几何。
   *
   * @param graph 调度图
   * @param area 站场
   * @param footprints 区间的轨道方块
   * @param rect 画轨道的区域
   * @param stationName 站码或车库代码 → 显示的名字（去向与站台上方的站名）
   */
  public static YardGeometry of(
      RailGraph graph,
      YardArea area,
      Map<EdgeId, Set<RailFootprintCell>> footprints,
      Rect rect,
      Function<String, String> stationName) {
    Objects.requireNonNull(graph, "graph");
    Objects.requireNonNull(area, "area");
    Objects.requireNonNull(footprints, "footprints");
    Objects.requireNonNull(rect, "rect");
    Objects.requireNonNull(stationName, "stationName");
    // 节点坐标是方块角，轨道方块按中心算；节点也取方块中心，否则判道岔走向时会带进半格的偏差
    Map<String, Spot> spots = new LinkedHashMap<>();
    for (NodeId id : area.nodes().stream().sorted().toList()) {
      graph
          .findNode(id)
          .map(RailNode::worldPosition)
          .ifPresent(
              at ->
                  spots.put(
                      id.value(),
                      new Spot(
                          id.value(), Math.floor(at.getX()) + 0.5, Math.floor(at.getZ()) + 0.5)));
    }
    List<Link> links = new ArrayList<>();
    boolean partial = false;
    for (EdgeId edge : area.edges().stream().sorted().toList()) {
      Set<RailFootprintCell> cells = footprints.getOrDefault(edge, Set.of());
      partial |= cells.isEmpty();
      if (spots.containsKey(edge.a().value()) && spots.containsKey(edge.b().value())) {
        links.add(new Link(edge, edge.a().value(), edge.b().value(), cells));
      }
    }
    Map<String, YardArea.Exit> exitOf = new LinkedHashMap<>();
    for (YardArea.Exit exit : area.exits()) {
      Spot inner = spots.get(exit.inner().value());
      if (inner == null) {
        continue;
      }
      Set<RailFootprintCell> near = new LinkedHashSet<>();
      for (RailFootprintCell cell : footprints.getOrDefault(exit.edge(), Set.of())) {
        if (distance(center(cell), inner) <= STUB_BLOCKS) {
          near.add(cell);
        }
      }
      Optional<double[]> end =
          near.stream()
              .max(Comparator.comparingDouble(cell -> distance(center(cell), inner)))
              .map(YardGeometry::center);
      if (end.isEmpty()) {
        partial = true;
        end =
            graph
                .findNode(exit.outer())
                .map(RailNode::worldPosition)
                .map(outer -> toward(inner, outer, STUB_BLOCKS));
      }
      if (end.isEmpty()) {
        continue;
      }
      String key = "exit:" + exit.edge().a().value() + "~" + exit.edge().b().value();
      spots.put(key, new Spot(key, end.get()[0], end.get()[1]));
      links.add(new Link(exit.edge(), inner.key(), key, near));
      exitOf.put(key, exit);
    }
    return new Layout(spots, links, rect).build(area, exitOf, stationName, partial);
  }

  /** 排布：主方向、直股侧股、分行、横坐标与连线。 */
  private static final class Layout {
    private final Map<String, Spot> spots;
    private final List<Link> links;
    private final Rect rect;
    private final Map<String, List<Link>> around = new HashMap<>();
    private final Map<String, Set<Link>> through = new HashMap<>();
    private final Map<String, Double> uOf = new HashMap<>();
    private final Map<String, Double> vOf = new HashMap<>();

    Layout(Map<String, Spot> spots, List<Link> links, Rect rect) {
      this.spots = spots;
      this.links = links;
      this.rect = rect;
      for (Link link : links) {
        around.computeIfAbsent(link.a(), ignored -> new ArrayList<>()).add(link);
        around.computeIfAbsent(link.b(), ignored -> new ArrayList<>()).add(link);
      }
      for (Spot spot : spots.values()) {
        through.put(spot.key(), throughLinks(spot));
      }
      Map<String, String> component = components();
      Map<String, List<Chain>> byComponent = new LinkedHashMap<>();
      for (Chain chain : chains()) {
        byComponent
            .computeIfAbsent(component.get(chain.spots().get(0)), ignored -> new ArrayList<>())
            .add(chain);
      }
      List<List<Chain>> parts =
          byComponent.values().stream()
              .sorted(
                  Comparator.comparingDouble(
                      (List<Chain> part) ->
                          -part.stream().mapToDouble(Chain::length).max().orElse(0.0)))
              .toList();
      List<Frame> placed = new ArrayList<>();
      for (List<Chain> part : parts) {
        Frame own = frame(part);
        Frame frame =
            placed.stream()
                .min(Comparator.comparingDouble(other -> other.distanceTo(own.middle())))
                .map(own::alignedTo)
                .orElse(own);
        placed.add(frame);
        for (Chain chain : part) {
          for (String key : chain.spots()) {
            Spot spot = spots.get(key);
            vOf.put(key, frame.project(spot.x(), spot.z())[1]);
          }
        }
        for (Chain chain : part) {
          place(chain, frame);
        }
      }
    }

    /** 连通的几片：节点 → 所在那片的代表节点。 */
    private Map<String, String> components() {
      Map<String, String> parent = new HashMap<>();
      spots.keySet().forEach(key -> parent.put(key, key));
      for (Link link : links) {
        String a = root(parent, link.a());
        String b = root(parent, link.b());
        if (!a.equals(b)) {
          parent.put(a.compareTo(b) < 0 ? b : a, a.compareTo(b) < 0 ? a : b);
        }
      }
      Map<String, String> out = new HashMap<>();
      spots.keySet().forEach(key -> out.put(key, root(parent, key)));
      return out;
    }

    private static String root(Map<String, String> parent, String key) {
      String at = key;
      while (!parent.get(at).equals(at)) {
        at = parent.get(at);
      }
      return at;
    }

    /**
     * 直股连成的链：相邻两个节点之间的区间在两头都是直股。每个节点只在一条链上；链按沿线先后排好，成环时从任一处断开。
     *
     * @param spots 链上的节点
     * @param links 相邻节点之间的区间，比节点少一个
     * @param length 沿轨道的总长（格）
     */
    private record Chain(List<String> spots, List<Link> links, double length) {}

    private List<Chain> chains() {
      Set<String> seen = new HashSet<>();
      List<Chain> out = new ArrayList<>();
      for (String key : spots.keySet()) {
        if (seen.contains(key)) {
          continue;
        }
        String end = walk(key, new ArrayList<>(), new ArrayList<>()).get(0);
        List<String> chainSpots = new ArrayList<>();
        List<Link> chainLinks = new ArrayList<>();
        walk(end, chainSpots, chainLinks);
        seen.addAll(chainSpots);
        double length = chainLinks.stream().mapToDouble(this::trackLength).sum();
        out.add(new Chain(List.copyOf(chainSpots), List.copyOf(chainLinks), length));
      }
      return out;
    }

    /** 从 {@code start} 沿直股一直走到头，把经过的节点与区间依次记下；返回走到的最后一个节点（单元素列表）。 */
    private List<String> walk(String start, List<String> visited, List<Link> via) {
      String at = start;
      Link came = null;
      visited.add(at);
      while (true) {
        Link from = came;
        String here = at;
        Optional<Link> step =
            around.getOrDefault(here, List.of()).stream()
                .filter(link -> link != from)
                .filter(link -> isThrough(here, link) && isThrough(link.other(here), link))
                .findFirst();
        if (step.isEmpty() || visited.contains(step.get().other(here))) {
          return List.of(here);
        }
        came = step.get();
        at = came.other(here);
        visited.add(at);
        via.add(came);
      }
    }

    /** 一片轨道的脊线：最长的一条链，沿轨道取样；按这片节点的主方向定头尾（东西走向东在右，南北走向南在右）。只有孤立节点时退回一条沿主方向的直线。 */
    private Frame frame(List<Chain> chains) {
      List<Spot> members =
          chains.stream().flatMap(chain -> chain.spots().stream()).map(spots::get).toList();
      double angle =
          principalAngle(members.stream().map(s -> new double[] {s.x(), s.z()}).toList());
      double[] axis = {Math.cos(angle), Math.sin(angle)};
      Chain spine =
          chains.stream()
              .max(Comparator.comparingDouble(Chain::length))
              .orElse(new Chain(List.of(), List.of(), 0.0));
      List<double[]> samples = new ArrayList<>();
      for (int i = 0; i < spine.links().size(); i++) {
        List<double[]> traced = trace(spine.links().get(i), spine.spots().get(i));
        samples.addAll(samples.isEmpty() ? traced : traced.subList(1, traced.size()));
      }
      List<double[]> kept = new ArrayList<>();
      for (double[] sample : samples) {
        if (kept.isEmpty()
            || Math.hypot(
                    sample[0] - kept.get(kept.size() - 1)[0],
                    sample[1] - kept.get(kept.size() - 1)[1])
                >= SPINE_SAMPLE_BLOCKS) {
          kept.add(sample);
        }
      }
      if (kept.size() < 2) {
        double cx = members.stream().mapToDouble(Spot::x).average().orElse(0.0);
        double cz = members.stream().mapToDouble(Spot::z).average().orElse(0.0);
        kept = List.of(new double[] {cx, cz}, new double[] {cx + axis[0], cz + axis[1]});
      }
      double[] first = kept.get(0);
      double[] last = kept.get(kept.size() - 1);
      if ((last[0] - first[0]) * axis[0] + (last[1] - first[1]) * axis[1] < 0) {
        kept = new ArrayList<>(kept);
        java.util.Collections.reverse(kept);
      }
      return new Frame(kept);
    }

    /**
     * 一条链的横坐标：离脊线最近的节点取它在脊线上的垂足；沿链往两头走，下一个节点的垂足朝前挪了至少半个区间长时取垂足（与脊线平行的股道和脊线对齐），
     * 否则按轨道长度接着排（斜着岔出去的支线）。
     */
    private void place(Chain chain, Frame frame) {
      List<String> keys = chain.spots();
      int anchor = 0;
      for (int i = 1; i < keys.size(); i++) {
        if (Math.abs(vOf.get(keys.get(i))) < Math.abs(vOf.get(keys.get(anchor)))) {
          anchor = i;
        }
      }
      Spot at = spots.get(keys.get(anchor));
      uOf.put(at.key(), frame.project(at.x(), at.z())[0]);
      double sign = 1.0;
      if (!chain.links().isEmpty()) {
        double[] along =
            anchor < chain.links().size()
                ? direction(at, chain.links().get(anchor))
                : negate(direction(at, chain.links().get(anchor - 1)));
        double[] axis = frame.direction(at.x(), at.z());
        sign = along[0] * axis[0] + along[1] * axis[1] >= 0 ? 1.0 : -1.0;
      }
      for (int i = anchor + 1; i < keys.size(); i++) {
        next(keys.get(i - 1), keys.get(i), chain.links().get(i - 1), sign, frame);
      }
      for (int i = anchor - 1; i >= 0; i--) {
        next(keys.get(i + 1), keys.get(i), chain.links().get(i), -sign, frame);
      }
    }

    private void next(String from, String to, Link link, double sign, Frame frame) {
      double before = uOf.get(from);
      Spot spot = spots.get(to);
      double projected = frame.project(spot.x(), spot.z())[0];
      double length = Math.max(1.0, trackLength(link));
      uOf.put(to, sign * (projected - before) >= 0.5 * length ? projected : before + sign * length);
    }

    /** 区间沿轨道的点：{@code from} 一头、按先后排好的轨道方块、另一头。没有方块时只有两头。 */
    private List<double[]> trace(Link link, String from) {
      Spot a = spots.get(from);
      Spot b = spots.get(link.other(from));
      List<double[]> points = new ArrayList<>();
      points.add(new double[] {a.x(), a.z()});
      link.cells().stream()
          .map(YardGeometry::center)
          .sorted(Comparator.comparingDouble(c -> distance(c, a) - distance(c, b)))
          .forEach(points::add);
      points.add(new double[] {b.x(), b.z()});
      return points;
    }

    /** 区间沿轨道的长度（格）。 */
    private double trackLength(Link link) {
      List<double[]> points = trace(link, link.a());
      double length = 0.0;
      for (int i = 1; i < points.size(); i++) {
        length +=
            Math.hypot(
                points.get(i)[0] - points.get(i - 1)[0], points.get(i)[1] - points.get(i - 1)[1]);
      }
      return length;
    }

    YardGeometry build(
        YardArea area,
        Map<String, YardArea.Exit> exitOf,
        Function<String, String> stationName,
        boolean partial) {
      Map<String, Row> rows = rows(area);
      Map<String, Point> points = points(rows);
      List<Path> paths = new ArrayList<>();
      Map<RailFootprintCell, Point> cells = new HashMap<>();
      Map<Integer, List<int[]>> spans = new HashMap<>();
      for (Link link : links) {
        List<Point> route = route(link, points);
        paths.add(new Path(link.edge(), segments(route)));
        placeCells(link, route, points, cells);
        for (int i = 0; i + 1 < route.size(); i++) {
          Point p = route.get(i);
          Point q = route.get(i + 1);
          if (p.y() == q.y()) {
            spans
                .computeIfAbsent(p.y(), ignored -> new ArrayList<>())
                .add(new int[] {Math.min(p.x(), q.x()), Math.max(p.x(), q.x())});
          }
        }
      }
      List<Platform> platforms = platforms(area, rows, points, spans, stationName);
      int middle = rect.left() + rect.width() / 2;
      Map<String, Label> exits = new LinkedHashMap<>();
      exitOf.forEach(
          (key, exit) ->
              exit.towards()
                  .ifPresent(
                      code -> {
                        Point at = points.get(key);
                        String text = stationName.apply(code);
                        boolean right = at.x() > middle;
                        exits.putIfAbsent(text + (right ? "|R" : "|L"), new Label(at, text, right));
                      }));
      return new YardGeometry(paths, platforms, List.copyOf(exits.values()), cells, partial);
    }

    /**
     * 站台块。站台在股道哪一侧按真实股道间距推断：相邻两条站台股道（在站台位置都画了线）之间隔着至少 {@value #MIN_PLATFORM_BLOCKS}
     * 格时当作两线共用的岛式站台（只配同一个车站的股道）；否则站台放在放得下的一侧，两侧都放得下时放在离邻线更远的一侧（图边算无限远）。
     */
    private List<Platform> platforms(
        YardArea area,
        Map<String, Row> rowOf,
        Map<String, Point> points,
        Map<Integer, List<int[]>> spans,
        Function<String, String> stationName) {
      List<String> keys = new ArrayList<>();
      Map<String, String> numberOf = new HashMap<>();
      Map<String, String> stationOf = new HashMap<>();
      for (YardArea.Track track : area.tracks()) {
        String key = track.node().value();
        if (points.containsKey(key)) {
          keys.add(key);
          numberOf.put(key, track.number());
          stationOf.put(key, track.station());
        }
      }
      boolean several = stationOf.values().stream().distinct().count() > 1;
      Function<String, Optional<String>> caption =
          key -> several ? Optional.of(stationName.apply(stationOf.get(key))) : Optional.empty();
      keys.sort(
          Comparator.comparingInt((String key) -> points.get(key).y())
              .thenComparingInt(key -> points.get(key).x()));
      Map<Integer, Row> rowAtY = new HashMap<>();
      rowOf.forEach((key, row) -> rowAtY.put(points.get(key).y(), row));
      Set<String> placed = new HashSet<>();
      List<Platform> out = new ArrayList<>();
      for (String key : keys) {
        if (placed.contains(key)) {
          continue;
        }
        Point at = points.get(key);
        int[] range = extent(key, points);
        double v = rowOf.get(key).v();
        Optional<Integer> up = neighbour(at, spans, -1);
        Optional<Integer> down = neighbour(at, spans, 1);
        double upGap = up.map(y -> Math.abs(v - rowAtY.get(y).v())).orElse(Double.MAX_VALUE);
        double downGap = down.map(y -> Math.abs(v - rowAtY.get(y).v())).orElse(Double.MAX_VALUE);
        Optional<String> partner =
            down.filter(y -> downGap >= MIN_PLATFORM_BLOCKS)
                .flatMap(
                    y ->
                        keys.stream()
                            .filter(other -> !placed.contains(other) && !other.equals(key))
                            .filter(other -> stationOf.get(other).equals(stationOf.get(key)))
                            .filter(other -> points.get(other).y() == y)
                            .filter(other -> overlaps(range, extent(other, points)))
                            .findFirst());
        placed.add(key);
        if (partner.isPresent()) {
          int[] other = extent(partner.get(), points);
          placed.add(partner.get());
          out.add(
              new Platform(
                  Math.min(range[0], other[0]),
                  at.y() + TRACK_LINE + PLATFORM_MARGIN,
                  Math.max(range[1], other[1]),
                  points.get(partner.get()).y() - PLATFORM_MARGIN,
                  List.of(numberOf.get(key), numberOf.get(partner.get())),
                  caption.apply(key)));
          continue;
        }
        boolean above =
            upGap >= MIN_PLATFORM_BLOCKS && downGap >= MIN_PLATFORM_BLOCKS
                ? upGap >= downGap
                : upGap >= MIN_PLATFORM_BLOCKS || downGap < upGap;
        int top =
            above
                ? at.y() - PLATFORM_MARGIN - SIDE_PLATFORM_DEPTH
                : at.y() + TRACK_LINE + PLATFORM_MARGIN;
        out.add(
            new Platform(
                range[0],
                top,
                range[1],
                top + SIDE_PLATFORM_DEPTH,
                List.of(numberOf.get(key)),
                caption.apply(key)));
      }
      return out;
    }

    /** 站台块的左右范围：以站台节点为中心，不越过同一行相邻的节点，也不出画面。 */
    private int[] extent(String key, Map<String, Point> points) {
      Point at = points.get(key);
      int left = Math.max(rect.left(), at.x() - PLATFORM_HALF);
      int right = Math.min(rect.left() + rect.width() - 1, at.x() + PLATFORM_HALF);
      for (Map.Entry<String, Point> entry : points.entrySet()) {
        Point other = entry.getValue();
        if (entry.getKey().equals(key) || other.y() != at.y()) {
          continue;
        }
        if (other.x() < at.x()) {
          left = Math.max(left, other.x() + 6);
        } else if (other.x() > at.x()) {
          right = Math.min(right, other.x() - 6);
        }
      }
      if (right - left < 24) {
        return new int[] {at.x() - 12, at.x() + 12};
      }
      return new int[] {left, right};
    }

    private static boolean overlaps(int[] a, int[] b) {
      return a[0] < b[1] && b[0] < a[1];
    }

    /** 往上（{@code direction} 为 -1）或往下最近一条在这个横坐标画了水平线的行。 */
    private static Optional<Integer> neighbour(
        Point at, Map<Integer, List<int[]>> spans, int direction) {
      Optional<Integer> best = Optional.empty();
      for (Map.Entry<Integer, List<int[]>> entry : spans.entrySet()) {
        int y = entry.getKey();
        boolean side = direction < 0 ? y < at.y() : y > at.y();
        boolean covers =
            entry.getValue().stream().anyMatch(span -> span[0] <= at.x() && at.x() <= span[1]);
        if (side
            && covers
            && (best.isEmpty() || Math.abs(y - at.y()) < Math.abs(best.get() - at.y()))) {
          best = Optional.of(y);
        }
      }
      return best;
    }

    /**
     * 直股：只连两条区间的节点两条都是；只连一条的就是那一条；三条以上的取离开方向最接近一直线的两条。
     *
     * <p>离开方向取这条区间离节点 {@value #BRANCH_PROBE_BLOCKS} 格以内轨道方块的重心；没有方块时取另一头节点的方向。
     */
    private Set<Link> throughLinks(Spot spot) {
      List<Link> mine = around.getOrDefault(spot.key(), List.of());
      if (mine.size() <= 2) {
        return Set.copyOf(mine);
      }
      List<double[]> directions = mine.stream().map(link -> direction(spot, link)).toList();
      int bestI = 0;
      int bestJ = 1;
      double best = Double.MAX_VALUE;
      for (int i = 0; i < mine.size(); i++) {
        for (int j = i + 1; j < mine.size(); j++) {
          double dot =
              directions.get(i)[0] * directions.get(j)[0]
                  + directions.get(i)[1] * directions.get(j)[1];
          if (dot < best) {
            best = dot;
            bestI = i;
            bestJ = j;
          }
        }
      }
      return Set.of(mine.get(bestI), mine.get(bestJ));
    }

    private double[] direction(Spot spot, Link link) {
      double sumX = 0.0;
      double sumZ = 0.0;
      int count = 0;
      for (RailFootprintCell cell : link.cells()) {
        double[] c = center(cell);
        double d = distance(c, spot);
        if (d > BRANCH_SKIP_BLOCKS && d <= BRANCH_PROBE_BLOCKS) {
          sumX += c[0];
          sumZ += c[1];
          count++;
        }
      }
      Spot other = spots.get(link.other(spot.key()));
      double dx = count > 0 ? sumX / count - spot.x() : other.x() - spot.x();
      double dz = count > 0 ? sumZ / count - spot.z() : other.z() - spot.z();
      double length = Math.hypot(dx, dz);
      return length == 0.0 ? new double[] {0.0, 0.0} : new double[] {dx / length, dz / length};
    }

    private boolean isThrough(String spot, Link link) {
      return through.getOrDefault(spot, Set.of()).contains(link);
    }

    /** 站台股道起头，沿直股传开；传不到的另起一行。 */
    private Map<String, Row> rows(YardArea area) {
      Map<String, Row> rowOf = new HashMap<>();
      Deque<String> queue = new ArrayDeque<>();
      List<Row> seeds = new ArrayList<>();
      List<Spot> seedSpots = new ArrayList<>();
      List<Spot> platforms =
          area.tracks().stream()
              .map(track -> spots.get(track.node().value()))
              .filter(Objects::nonNull)
              .sorted(Comparator.comparingDouble(s -> vOf.get(s.key())))
              .toList();
      for (Spot spot : platforms) {
        Row row = null;
        for (int i = 0; i < seeds.size(); i++) {
          if (inLine(seedSpots.get(i), spot)) {
            row = seeds.get(i);
          }
        }
        if (row == null) {
          row = new Row(vOf.get(spot.key()));
          seeds.add(row);
          seedSpots.add(spot);
        }
        assign(spot.key(), row, rowOf, queue);
      }
      spread(rowOf, queue);
      while (rowOf.size() < spots.size()) {
        String next =
            spots.keySet().stream()
                .filter(key -> !rowOf.containsKey(key))
                .filter(key -> neighbourAssigned(key, rowOf))
                .findFirst()
                .orElseGet(
                    () ->
                        spots.keySet().stream()
                            .filter(key -> !rowOf.containsKey(key))
                            .findFirst()
                            .orElseThrow());
        assign(next, new Row(vOf.get(next)), rowOf, queue);
        spread(rowOf, queue);
      }
      return rowOf;
    }

    /** {@code other} 落在 {@code spot} 所在股道的延长线上：沿股道走向的横向偏差不到 {@value #SAME_ROW_BLOCKS} 格。 */
    private boolean inLine(Spot spot, Spot other) {
      List<Link> mine = around.getOrDefault(spot.key(), List.of());
      if (mine.isEmpty()) {
        return Math.abs(vOf.get(spot.key()) - vOf.get(other.key())) < SAME_ROW_BLOCKS;
      }
      double[] along = direction(spot, mine.get(0));
      double ox = other.x() - spot.x();
      double oz = other.z() - spot.z();
      return Math.abs(-ox * along[1] + oz * along[0]) < SAME_ROW_BLOCKS;
    }

    private boolean neighbourAssigned(String key, Map<String, Row> rowOf) {
      return around.getOrDefault(key, List.of()).stream()
          .anyMatch(link -> rowOf.containsKey(link.other(key)));
    }

    private static void assign(String key, Row row, Map<String, Row> rowOf, Deque<String> queue) {
      rowOf.put(key, row);
      queue.add(key);
    }

    /** 沿直股传：这条区间在两头都是直股，另一头才跟着进同一行。 */
    private void spread(Map<String, Row> rowOf, Deque<String> queue) {
      while (!queue.isEmpty()) {
        String key = queue.poll();
        for (Link link : around.getOrDefault(key, List.of())) {
          String other = link.other(key);
          if (!rowOf.containsKey(other) && isThrough(key, link) && isThrough(other, link)) {
            assign(other, rowOf.get(key), rowOf, queue);
          }
        }
      }
    }

    /** 各行按起头节点的横向位置排序、等距排开并在区域内居中；横坐标见 {@link #columns}。 */
    private Map<String, Point> points(Map<String, Row> rowOf) {
      List<Row> ordered =
          rowOf.values().stream().distinct().sorted(Comparator.comparingDouble(Row::v)).toList();
      int height = Math.max(1, rect.height() - 1);
      int gap = ordered.size() > 1 ? Math.min(ROW_GAP, height / (ordered.size() - 1)) : 0;
      int top = rect.top() + (height - gap * (ordered.size() - 1)) / 2;
      Map<Row, Integer> rowIndex = new HashMap<>();
      for (int i = 0; i < ordered.size(); i++) {
        rowIndex.put(ordered.get(i), i);
      }
      Map<String, Integer> xs = columns(rowOf, rowIndex, gap);
      Map<String, Point> points = new HashMap<>();
      for (Spot spot : spots.values()) {
        points.put(
            spot.key(),
            new Point(xs.get(spot.key()), top + rowIndex.get(rowOf.get(spot.key())) * gap));
      }
      return points;
    }

    /**
     * 横坐标：节点按沿线先后排开。相邻两处之间的距离按平方根压缩（长直线段缩短，道岔密集处相对拉开），再给每段跨行的区间留够画 45° 斜线的横向距离 （每次最多放大 {@value
     * #MAX_STRETCH} 倍，反复几轮），最后按宽度铺满。
     */
    private Map<String, Integer> columns(
        Map<String, Row> rowOf, Map<Row, Integer> rowIndex, int gap) {
      List<Double> levels = new ArrayList<>();
      uOf.values().stream()
          .sorted()
          .forEach(
              u -> {
                if (levels.isEmpty() || u - levels.get(levels.size() - 1) > 0.5) {
                  levels.add(u);
                }
              });
      Map<String, Integer> levelOf = new HashMap<>();
      for (Spot spot : spots.values()) {
        double u = uOf.get(spot.key());
        int level = 0;
        for (int i = 0; i < levels.size(); i++) {
          if (Math.abs(levels.get(i) - u) < Math.abs(levels.get(level) - u)) {
            level = i;
          }
        }
        levelOf.put(spot.key(), level);
      }
      double[] weights = new double[Math.max(0, levels.size() - 1)];
      for (int i = 0; i < weights.length; i++) {
        weights[i] = Math.sqrt(Math.max(0.5, levels.get(i + 1) - levels.get(i)));
      }
      double width = Math.max(1, rect.width() - 1);
      for (int pass = 0; pass < 6 && gap > 0 && weights.length > 0; pass++) {
        double scale = width / sum(weights, 0, weights.length);
        boolean stretched = false;
        for (Link link : links) {
          int rows =
              Math.abs(rowIndex.get(rowOf.get(link.a())) - rowIndex.get(rowOf.get(link.b())));
          int low = Math.min(levelOf.get(link.a()), levelOf.get(link.b()));
          int high = Math.max(levelOf.get(link.a()), levelOf.get(link.b()));
          double have = scale * sum(weights, low, high);
          double need = (double) rows * gap;
          if (rows > 0 && high > low && have + 0.5 < need) {
            double factor = Math.min(MAX_STRETCH, need / have);
            for (int i = low; i < high; i++) {
              weights[i] *= factor;
            }
            stretched = true;
          }
        }
        if (!stretched) {
          break;
        }
      }
      double total = sum(weights, 0, weights.length);
      double[] at = new double[levels.size()];
      for (int i = 1; i < at.length; i++) {
        at[i] = at[i - 1] + weights[i - 1];
      }
      Map<String, Integer> xs = new HashMap<>();
      for (Spot spot : spots.values()) {
        double fraction = total == 0.0 ? 0.5 : at[levelOf.get(spot.key())] / total;
        xs.put(spot.key(), (int) Math.round(rect.left() + fraction * width));
      }
      return xs;
    }

    /** 两端同行一条水平线；不同行加一段 45° 斜线，贴着分出这条侧股的一头。 */
    private List<Point> route(Link link, Map<String, Point> points) {
      Point a = points.get(link.a());
      Point b = points.get(link.b());
      if (a.y() == b.y()) {
        return List.of(a, b);
      }
      int rise = Math.abs(b.y() - a.y());
      int run = b.x() - a.x();
      if (Math.abs(run) < rise) {
        return List.of(a, b);
      }
      int step = run >= 0 ? rise : -rise;
      boolean fromA = branchesAt(link.a(), link);
      boolean fromB = branchesAt(link.b(), link);
      if (fromA && !fromB) {
        return List.of(a, new Point(a.x() + step, b.y()), b);
      }
      if (fromB && !fromA) {
        return List.of(a, new Point(b.x() - step, a.y()), b);
      }
      int middle = (a.x() + b.x()) / 2;
      return List.of(
          a, new Point(middle - step / 2, a.y()), new Point(middle - step / 2 + step, b.y()), b);
    }

    /** 这条区间是不是从这个节点分出去的侧股。 */
    private boolean branchesAt(String spot, Link link) {
      return around.getOrDefault(spot, List.of()).size() >= 3 && !isThrough(spot, link);
    }

    /** 车厢方块按它沿轨道走过区间的比例，落到这条区间的线上。 */
    private void placeCells(
        Link link,
        List<Point> route,
        Map<String, Point> points,
        Map<RailFootprintCell, Point> cells) {
      Spot a = spots.get(link.a());
      Spot b = spots.get(link.b());
      List<RailFootprintCell> ordered =
          link.cells().stream()
              .sorted(
                  Comparator.comparingDouble(
                      cell -> distance(center(cell), a) - distance(center(cell), b)))
              .toList();
      double[] walked = new double[ordered.size()];
      double[] previous = {a.x(), a.z()};
      double total = 0.0;
      for (int i = 0; i < ordered.size(); i++) {
        double[] c = center(ordered.get(i));
        total += Math.hypot(c[0] - previous[0], c[1] - previous[1]);
        walked[i] = total;
        previous = c;
      }
      total += Math.hypot(b.x() - previous[0], b.z() - previous[1]);
      Point pa = points.get(link.a());
      Point pb = points.get(link.b());
      for (int i = 0; i < ordered.size(); i++) {
        RailFootprintCell cell = ordered.get(i);
        if (cells.containsKey(cell)) {
          continue;
        }
        double t = total < 1e-6 ? 0.5 : Math.max(0.0, Math.min(1.0, walked[i] / total));
        int x = (int) Math.round(pa.x() + t * (pb.x() - pa.x()));
        cells.put(cell, new Point(x, yAt(route, x)));
      }
    }
  }

  /** 脊线坐标：沿脊线的里程与偏离脊线的横向距离（行进方向右侧为正，画面上方为左侧）。脊线外的点取最近的取样点，按那里的走向换算。 */
  private static final class Frame {
    private final double[] xs;
    private final double[] zs;
    private final double[] chainage;
    private final double[] dx;
    private final double[] dz;
    private final double shiftU;
    private final double shiftV;

    Frame(List<double[]> samples) {
      this(samples, 0.0, 0.0);
    }

    /**
     * @param samples 脊线取样点，按里程先后
     * @param shiftU 里程整体平移
     * @param shiftV 横向距离整体平移
     */
    private Frame(List<double[]> samples, double shiftU, double shiftV) {
      this.shiftU = shiftU;
      this.shiftV = shiftV;
      int n = samples.size();
      xs = new double[n];
      zs = new double[n];
      chainage = new double[n];
      dx = new double[n];
      dz = new double[n];
      for (int i = 0; i < n; i++) {
        xs[i] = samples.get(i)[0];
        zs[i] = samples.get(i)[1];
        if (i > 0) {
          chainage[i] = chainage[i - 1] + Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]);
        }
      }
      for (int i = 0; i < n; i++) {
        int back = Math.max(0, i - SPINE_DIRECTION_SAMPLES);
        int ahead = Math.min(n - 1, i + SPINE_DIRECTION_SAMPLES);
        double ex = xs[ahead] - xs[back];
        double ez = zs[ahead] - zs[back];
        double length = Math.hypot(ex, ez);
        dx[i] = length == 0.0 ? 1.0 : ex / length;
        dz[i] = length == 0.0 ? 0.0 : ez / length;
      }
    }

    /** {里程, 横向距离}。 */
    double[] project(double x, double z) {
      int i = nearest(x, z);
      double ox = x - xs[i];
      double oz = z - zs[i];
      return new double[] {
        shiftU + chainage[i] + ox * dx[i] + oz * dz[i], shiftV - ox * dz[i] + oz * dx[i]
      };
    }

    /** 脊线中点。 */
    double[] middle() {
      return new double[] {xs[xs.length / 2], zs[zs.length / 2]};
    }

    /** 到脊线最近处的距离（格）。 */
    double distanceTo(double[] point) {
      int i = nearest(point[0], point[1]);
      return Math.hypot(xs[i] - point[0], zs[i] - point[1]);
    }

    /** 另一片轨道的脊线：保持自己的走向，平移到它的中点在 {@code reference}（离它最近、已经摆好的一片）上的位置，几片平行的股道因此保持真实的左右间距。 */
    Frame alignedTo(Frame reference) {
      int middle = xs.length / 2;
      double[] at = reference.project(xs[middle], zs[middle]);
      List<double[]> samples = new ArrayList<>();
      for (int i = 0; i < xs.length; i++) {
        samples.add(new double[] {xs[i], zs[i]});
      }
      return new Frame(samples, at[0] - chainage[middle], at[1]);
    }

    /** 最近处脊线的走向（单位向量）。 */
    double[] direction(double x, double z) {
      int i = nearest(x, z);
      return new double[] {dx[i], dz[i]};
    }

    private int nearest(double x, double z) {
      int best = 0;
      double bestDistance = Double.MAX_VALUE;
      for (int i = 0; i < xs.length; i++) {
        double d = (xs[i] - x) * (xs[i] - x) + (zs[i] - z) * (zs[i] - z);
        if (d < bestDistance) {
          bestDistance = d;
          best = i;
        }
      }
      return best;
    }
  }

  private static double[] negate(double[] vector) {
    return new double[] {-vector[0], -vector[1]};
  }

  private static double sum(double[] values, int from, int to) {
    double total = 0.0;
    for (int i = from; i < to; i++) {
      total += values[i];
    }
    return total;
  }

  /** 折线在横坐标 {@code x} 处的纵坐标（折线横向单调）。 */
  private static int yAt(List<Point> route, int x) {
    for (int i = 0; i + 1 < route.size(); i++) {
      Point p = route.get(i);
      Point q = route.get(i + 1);
      int low = Math.min(p.x(), q.x());
      int high = Math.max(p.x(), q.x());
      if (x >= low && x <= high) {
        if (p.x() == q.x()) {
          return p.y();
        }
        return (int) Math.round(p.y() + (double) (x - p.x()) * (q.y() - p.y()) / (q.x() - p.x()));
      }
    }
    Point first = route.get(0);
    Point last = route.get(route.size() - 1);
    return Math.abs(x - first.x()) <= Math.abs(x - last.x()) ? first.y() : last.y();
  }

  /** 折线 → 线段（左端在前），去掉长度为零与重复的。 */
  private static List<Segment> segments(List<Point> route) {
    Set<Segment> out = new LinkedHashSet<>();
    for (int i = 0; i + 1 < route.size(); i++) {
      Point a = route.get(i);
      Point b = route.get(i + 1);
      if (a.equals(b) && route.size() > 2) {
        continue;
      }
      boolean ordered = a.x() < b.x() || (a.x() == b.x() && a.y() <= b.y());
      out.add(
          ordered
              ? new Segment(a.x(), a.y(), b.x(), b.y())
              : new Segment(b.x(), b.y(), a.x(), a.y()));
    }
    return List.copyOf(out);
  }

  private static double[] toward(Spot from, Vector target, double length) {
    double dx = target.getX() - from.x();
    double dz = target.getZ() - from.z();
    double distance = Math.hypot(dx, dz);
    if (distance <= length || distance == 0.0) {
      return new double[] {target.getX(), target.getZ()};
    }
    return new double[] {from.x() + dx / distance * length, from.z() + dz / distance * length};
  }

  private static double distance(double[] point, Spot spot) {
    return Math.hypot(point[0] - spot.x(), point[1] - spot.z());
  }

  private static double[] center(RailFootprintCell cell) {
    return new double[] {cell.x() + 0.5, cell.z() + 0.5};
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
    return Math.floorMod(Math.round(angle / step), 4L) * step;
  }
}
