package org.fetarute.fetaruteTCAddon.display.pids.yard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphInterlockingSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.action.AutoStationDoorDirection;

/**
 * 站场图的几何：按信号楼示意图排布——股道画成水平的行，股道之间只用 45° 斜线相连，区间中间的弯道一律拉直。
 *
 * <ul>
 *   <li>节点：站场内的节点，加上每个出口的末端（出口区间只取离站场节点 {@value #STUB_BLOCKS} 格以内的一段）。
 *   <li>横坐标：沿线拉直。最长的一条直股连线作脊线，按轨道长度展开成水平（东西走向的站东在右、北在上，南北走向的南在右、东在上），弯道与几个车站之间的拐弯都拉直；
 *       其余的链从接在已排好节点上的那一点起、顺着离开的方向排，按垂足定先后；垂足挪不动（斜着岔出去的支线）或挪得比轨道长得多（和脊线几乎垂直的库线）时
 *       改按轨道长度接着排。互不相连的几片轨道各取各的脊线，从最长的一片起，依次按各自脊线中点在离它最近、已经摆好的那片脊线上的位置摆放。
 *       相邻两处的距离按平方根压缩，长直线段缩短、道岔密集的咽喉相对拉开，并保证每段跨行的区间有足够的横向距离画成 45°；链中间只连两段直股的经过点（区间点、咽喉点）
 *       不单独占位置，按里程插在前后两点之间。
 *   <li>行：道岔看各条区间离开它的走向，最接近一条直线的两条是直股，另一条是侧股，从道岔处拐出去；两条区间朝同一侧离开的节点没有直股。直股连成的一条链整条占一行，
 *       几个车站之间的同一条正线因此画成一条直线；落在同一条股道延长线上（横向偏差不到 {@value #SAME_ROW_BLOCKS} 格）的站台股道并为一行。
 *       各行按横向位置从上到下压进尽量少的层：横向范围不重叠的行共用一层，首尾相接的两行（各至少 {@value #ROW_SHARE_BLOCKS}
 *       格长）接在同一层；接上后画出的水平线与别的区间重叠时，这几行改为不接、重排。层距最多 {@value #ROW_GAP} 像素。
 *   <li>区间：两端同层画一条水平线；不同层画水平线加一段 45° 斜线，斜线贴着分出这条侧股的道岔，判断不出时放在中间；水平部分不顺着同一层别的股道走，
 *       也不和前面已排好的区间的水平部分叠在一起，几种摆法都不行或横向距离不够 45° 时直接连斜线。
 *   <li>车库：照画库线的车库记下库线与分岔道岔所占的范围和各条库线的尽头（画法加虚线框、库名、库号与车挡）；库线超过 {@value #MAX_DEPOT_TRACKS}
 *       条时库内收成一块面板，每条库线一行短条。
 *   <li>车厢：按它在区间里沿轨道走过的比例落到这条区间的线上。
 *   <li>站台：每个站台股道旁一块面板色的站台，以站台节点为中心、不越过同一层相邻的节点，节点两侧至少留出 {@value #PLATFORM_ROOM}
 *       像素；站台块让开经过它的斜线。站台在哪一侧先看 AutoStation 牌子写的开门方向：相邻两条站台股道（最多隔一层）开门相对时当作岛式站台、画在两层之间，
 *       侧式站台画在开门那一侧；没写开门方向时按轨道间距推断（两条都没写时相隔至少 {@value #MIN_ISLAND_BLOCKS} 格才算岛式）。
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

  /**
   * 两条股道都没写开门方向时，至少隔这么多格才当作岛式站台：两侧式站台的两条股道常常只隔 4 格（中间空着或只有栏杆）， 中间再夹一条不停站的正线也只有 8 格，而那条正线不一定在调度图里。
   */
  static final double MIN_ISLAND_BLOCKS = 9.0;

  /** 两个走向的夹角余弦不到这么多时（几乎垂直）分不出顺逆，改看脊线的走向。 */
  static final double DIRECTION_MIN_COSINE = 0.3;

  /** 沿链排下一个节点时，垂足最多比轨道长度多挪这么多倍才采用；再远就是垂足落到了脊线的别处，改按轨道长度接着排。 */
  static final double MAX_PROJECTED_STEP = 1.5;

  /** 一座车库的库线超过这么多条时收成一块面板，不再逐条照画。 */
  static final int MAX_DEPOT_TRACKS = 4;

  /** 照画的库线尽头在图的最左或最右一列时，在那一侧给库号与虚线框留出的宽度（像素）。 */
  static final int DEPOT_LABEL_ROOM = 40;

  /** 占用的水平线不连着哪个可以汇合的节点（直股）。 */
  private static final int NO_JOIN = Integer.MIN_VALUE;

  /** 出口末端节点的键前缀。 */
  private static final String EXIT_PREFIX = "exit:";

  /** 收成面板的车库库门点的键前缀。 */
  private static final String DEPOT_PREFIX = "depot:";

  /** 库号的先后：位数少的在前，同位数按字面（“2”在“10”前）。 */
  private static final Comparator<String> TRACK_ORDER =
      Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder());

  /** 车库面板每行的高度（像素）：第一行写库名，以下每条库线一行。 */
  static final int DEPOT_PANEL_ROW = 14;

  /** 车库面板的宽度（像素）。 */
  static final int DEPOT_PANEL_WIDTH = 120;

  /** 车库面板里库号一栏的宽度（像素），库线短条从这里往右画。 */
  static final int DEPOT_PANEL_LABEL = 44;

  /** 节点（牌子）离轨道最多这么远（格），量股道间距时取最近的轨道方块代替节点位置。 */
  static final double RAIL_SNAP_BLOCKS = 3.0;

  /** 开门方向与股道横向的夹角余弦不到这么多时（几乎顺着股道），当作判断不出在哪一侧。 */
  static final double DOOR_SIDE_MIN_COSINE = 0.3;

  /** 横向位置相差不到这么多（格）的站台股道并为一行。 */
  static final double SAME_ROW_BLOCKS = 1.0;

  /** 首尾相接的两行各自至少这么长（格）才接在同一层。 */
  static final double ROW_SHARE_BLOCKS = 4.0;

  /** 不相连的两行共用一层时，横向至少隔开这么多（格）。 */
  static final double ROW_CLEARANCE_BLOCKS = 8.0;

  /** 站台节点两边各至少留这么宽（像素），站台块里写得下“站码 股道号”。 */
  static final int PLATFORM_ROOM = 32;

  /** 站台块让开斜线后，节点两边至少还留这么宽（像素）。 */
  static final int MIN_PLATFORM_HALF = 8;

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
   * @param code 站码：站名放不下时改写站码
   */
  public record Label(Point at, String text, boolean alignRight, Optional<String> code) {
    public Label {
      code = code == null ? Optional.empty() : code;
    }
  }

  /**
   * 站台：面板色的块，块里写股道号。
   *
   * @param left 左
   * @param top 上
   * @param right 右（不含）
   * @param bottom 下（不含）
   * @param tracks 靠着这块站台的股道号，从上到下：岛式站台两条（上面一条写在块的上沿，下面一条写在下沿），侧式站台一条
   * @param station 站名：一张图里有几个车站时写在第一个股道号前面，股道号才分得清；只有一个车站时为空
   * @param code 站码：站台块窄、站名放不下时改写站码；只有一个车站时为空
   */
  public record Platform(
      int left,
      int top,
      int right,
      int bottom,
      List<String> tracks,
      Optional<String> station,
      Optional<String> code) {
    public Platform {
      tracks = List.copyOf(tracks);
      station = station == null ? Optional.empty() : station;
      code = code == null ? Optional.empty() : code;
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

  /**
   * 一座车库（控制区成员）在图上的一片库线。库线照常画在轨道里（{@code panel} 为假）时，{@code left} 到 {@code bottom}
   * 是库线与分岔道岔所占的范围，画法在外面加虚线框、库名与库号；库线超过 {@value #MAX_DEPOT_TRACKS} 条时收成一块面板（{@code panel}
   * 为真），这时它是面板本身的范围，库线画成面板里的短条（也在 {@link #paths()} 里），库号写在短条左边。
   *
   * @param code 车库代码
   * @param name 显示的名字（按车库代码查站名，查不到时为代码）
   * @param left 左（像素）
   * @param top 上
   * @param right 右
   * @param bottom 下
   * @param tracks 各条库线的尽头（面板里为短条的左端）
   * @param panel 收成了面板
   */
  public record Depot(
      String code,
      String name,
      int left,
      int top,
      int right,
      int bottom,
      List<DepotTrack> tracks,
      boolean panel) {
    public Depot {
      tracks = List.copyOf(tracks);
    }
  }

  /**
   * 库线尽头。
   *
   * @param number 库线号
   * @param end 尽头在图上的位置
   * @param buffer 尽头是车挡（只连一条区间）
   * @param outward 库号写在尽头哪一侧：1 为右，-1 为左
   */
  public record DepotTrack(String number, Point end, boolean buffer, int outward) {}

  /**
   * 出口区间（一端在站场内、一端在站场外）在图上的起头：信号员控制台把控制区的入口信号画在区内那一端。
   *
   * @param edge 出口区间
   * @param inner 区内那一端在图上的位置
   * @param toward 从区内那一端朝外画的下一个点（拐点或出口末端），据此知道出口朝哪边
   */
  public record Stub(EdgeId edge, Point inner, Point toward) {
    public Stub {
      Objects.requireNonNull(edge, "edge");
      Objects.requireNonNull(inner, "inner");
      Objects.requireNonNull(toward, "toward");
    }
  }

  private final List<Path> paths;
  private final List<Platform> platforms;
  private final List<Label> exits;
  private final List<Depot> depots;
  private final Map<RailFootprintCell, Point> cells;
  private final boolean partial;
  private final List<Stub> stubs;
  private final Map<String, Point> nodes;

  private YardGeometry(
      List<Path> paths,
      List<Platform> platforms,
      List<Label> exits,
      List<Depot> depots,
      Map<RailFootprintCell, Point> cells,
      boolean partial,
      List<Stub> stubs,
      Map<String, Point> nodes) {
    this.paths = List.copyOf(paths);
    this.platforms = List.copyOf(platforms);
    this.exits = List.copyOf(exits);
    this.depots = List.copyOf(depots);
    this.cells = Map.copyOf(cells);
    this.partial = partial;
    this.stubs = List.copyOf(stubs);
    this.nodes = Map.copyOf(nodes);
  }

  /** 各条出口区间的起头，与 {@link #paths()} 里出口的先后相同。 */
  public List<Stub> stubs() {
    return stubs;
  }

  /** 站场内节点在图上的位置（股道节点即站台股道所在的行）；不在站场图上时为空。 */
  public Optional<Point> nodeAt(NodeId node) {
    return Optional.ofNullable(nodes.get(node.value()));
  }

  /** 控制区里的车库。 */
  public List<Depot> depots() {
    return depots;
  }

  /** 各区间的像素线段：站场内的区间与出口，最后是收成面板的车库里各条库线的短条。顺序不代表区间种类，要分辨按 {@link Path#edge()} 查。 */
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

  /** 一层上的一段水平线：纵坐标、左右端与它所属的行。 */
  private record Span(int y, int left, int right, Row row) {}

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
   * @param stationName 站码或车库代码 → 显示的名字（去向与站台块里的站名）
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
      String key = EXIT_PREFIX + exit.edge().a().value() + "~" + exit.edge().b().value();
      spots.put(key, new Spot(key, end.get()[0], end.get()[1]));
      links.add(new Link(exit.edge(), inner.key(), key, near));
      exitOf.put(key, exit);
    }
    Map<String, AutoStationDoorDirection> doors = new HashMap<>();
    for (YardArea.Track track : area.tracks()) {
      graph
          .findNode(track.node())
          .flatMap(RailNode::doorDirection)
          .map(AutoStationDoorDirection::parse)
          .filter(door -> door != AutoStationDoorDirection.NONE)
          .ifPresent(door -> doors.put(track.node().value(), door));
    }
    Function<RailFootprintCell, Set<EdgeId>> railAt =
        graph instanceof RailGraphInterlockingSupport support
                && support.interlockingState().cellCoverageAvailable()
            ? support.interlockingState()::edgesForCell
            : cell -> Set.of();
    Map<String, String> throatOf = new HashMap<>();
    for (NodeId id : area.nodes()) {
      graph
          .findNode(id)
          .filter(node -> node.type() != NodeType.SWITCHER)
          .flatMap(RailNode::waypointMetadata)
          .filter(m -> m.kind() == WaypointKind.DEPOT_THROAT)
          .ifPresent(m -> throatOf.put(id.value(), m.originStation().toUpperCase(Locale.ROOT)));
    }
    return new Layout(spots, links, rect, doors, railAt, area, throatOf)
        .build(area, exitOf, stationName, partial);
  }

  /**
   * 收成面板的车库。
   *
   * @param code 车库代码
   * @param numbers 各条库线的库号，按库号排
   * @param lines 各条库线由哪几段组成，从尽头往库门排
   * @param ladder 库门里不归哪条库线的区间（分岔道岔之间的连线）
   */
  private record Collapsed(
      String code, List<String> numbers, List<List<Piece>> lines, List<Link> ladder) {}

  /**
   * 面板里一条库线的一段。
   *
   * @param link 区间
   * @param length 沿轨道的长度（格）
   * @param cells 轨道方块，从靠尽头的一端排起
   */
  private record Piece(Link link, double length, List<RailFootprintCell> cells) {}

  /**
   * 一次排好的各区间折线。
   *
   * @param points 按哪一份节点位置排的（按对象身份认，同一份位置不重复排）
   * @param routes 区间 → 折线
   * @param blocked 跨层、横向距离也够画 45°，却因为几种摆法都会和别的线叠在一起而退成斜线的区间
   */
  private record Routed(
      Map<String, Point> points, Map<Link, List<Point>> routes, Set<Link> blocked) {}

  /** 排布：主方向、直股侧股、分行、横坐标与连线。 */
  private static final class Layout {
    private final Map<String, Spot> spots;
    private final List<Link> links;
    private final Rect rect;
    private final Map<String, List<Link>> around = new HashMap<>();
    private final Map<String, Set<Link>> through = new HashMap<>();
    private final Map<String, Double> uOf = new HashMap<>();
    private final Map<String, Double> vOf = new HashMap<>();
    private final Map<String, Frame.Located> located = new HashMap<>();
    private final List<Chain> chains;
    private final Map<String, Chain> chainOf = new HashMap<>();
    private final Map<String, AutoStationDoorDirection> doors;
    private final Function<RailFootprintCell, Set<EdgeId>> railAt;
    private final Map<String, Optional<RailFootprintCell>> rails = new HashMap<>();
    private Set<String> passThrough = Set.of();
    private Routed routed;

    private final YardArea area;
    private final Map<String, String> throatOf;
    private final Map<String, Collapsed> collapsed;

    /**
     * @param spots 节点（可改：收成面板的车库在这里换成一个库门点）
     * @param links 区间（同上）
     * @param doors 站台股道节点 → AutoStation 牌子写的开门方向（只含写了方向的）
     * @param railAt 轨道方块 → 经过它的区间（全图）；查不到时为空集合
     * @param area 站场
     * @param throatOf 车库咽喉节点 → 所属车库代码
     */
    Layout(
        Map<String, Spot> spots,
        List<Link> links,
        Rect rect,
        Map<String, AutoStationDoorDirection> doors,
        Function<RailFootprintCell, Set<EdgeId>> railAt,
        YardArea area,
        Map<String, String> throatOf) {
      this.spots = spots;
      this.links = links;
      this.rect = rect;
      this.doors = doors;
      this.railAt = railAt;
      this.area = area;
      this.throatOf = throatOf;
      index();
      this.collapsed = collapse();
      index();
      for (Spot spot : spots.values()) {
        through.put(spot.key(), throughLinks(spot));
      }
      Map<String, String> component = components();
      Map<String, List<Chain>> byComponent = new LinkedHashMap<>();
      this.chains = chains();
      for (Chain chain : chains) {
        chain.spots().forEach(key -> chainOf.put(key, chain));
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
            Frame.Located at = frame.locate(spot.x(), spot.z());
            located.put(key, at);
            vOf.put(key, at.v());
          }
        }
        placeChains(part);
      }
    }

    /** 节点 → 连到它的区间。 */
    private void index() {
      around.clear();
      for (Link link : links) {
        around.computeIfAbsent(link.a(), ignored -> new ArrayList<>()).add(link);
        around.computeIfAbsent(link.b(), ignored -> new ArrayList<>()).add(link);
      }
    }

    /**
     * 库线超过 {@value #MAX_DEPOT_TRACKS} 条的车库收成面板：库内节点（见 {@link #depotInterior}）从排布里拿掉，换成一个库门点；
     * 从库外连进来的区间改连到库门点，库内各条库线记下组成它的区间，排好后画进面板。
     *
     * @return 库门点 → 收起来的车库
     */
    private Map<String, Collapsed> collapse() {
      Map<String, Collapsed> out = new LinkedHashMap<>();
      for (Map.Entry<String, Map<String, String>> entry :
          depotTracksByCode(spots::containsKey).entrySet()) {
        String code = entry.getKey();
        Map<String, String> numberOf = entry.getValue();
        if (numberOf.size() <= MAX_DEPOT_TRACKS) {
          continue;
        }
        Set<String> inside = depotInterior(code, numberOf.keySet(), spots.keySet());
        List<String> ends =
            numberOf.keySet().stream()
                .sorted(Comparator.comparing(numberOf::get, TRACK_ORDER))
                .toList();
        Set<Link> used = new HashSet<>();
        List<List<Piece>> lines = new ArrayList<>();
        for (String end : ends) {
          lines.add(trackLine(end, inside, used));
        }
        List<Link> ladder = new ArrayList<>();
        List<Link> boundary = new ArrayList<>();
        for (Link link : links) {
          boolean a = inside.contains(link.a());
          boolean b = inside.contains(link.b());
          if (a && b && !used.contains(link)) {
            ladder.add(link);
          } else if (a != b) {
            boundary.add(link);
          }
        }
        String door = DEPOT_PREFIX + code;
        List<Spot> doorSide =
            boundary.stream()
                .map(link -> spots.get(inside.contains(link.a()) ? link.a() : link.b()))
                .toList();
        List<Spot> anchors =
            doorSide.isEmpty() ? inside.stream().map(spots::get).toList() : doorSide;
        double x = anchors.stream().mapToDouble(Spot::x).average().orElse(0.0);
        double z = anchors.stream().mapToDouble(Spot::z).average().orElse(0.0);
        links.removeIf(link -> inside.contains(link.a()) || inside.contains(link.b()));
        for (Link link : boundary) {
          String outside = inside.contains(link.a()) ? link.b() : link.a();
          links.add(new Link(link.edge(), outside, door, link.cells()));
        }
        inside.forEach(spots::remove);
        spots.put(door, new Spot(door, x, z));
        out.put(
            door, new Collapsed(code, ends.stream().map(numberOf::get).toList(), lines, ladder));
        // 下一座车库按改过的区间找库内节点
        index();
      }
      return out;
    }

    /** 收成面板的一条库线：从尽头沿库内的区间往库门走，经过只连两条区间的点，走到分岔道岔（含）为止。尽头另一头还接着库外（两头都通的库线）时只往库内那头走。 */
    private List<Piece> trackLine(String end, Set<String> inside, Set<Link> used) {
      List<Piece> line = new ArrayList<>();
      String at = end;
      Link came = null;
      while (true) {
        Link from = came;
        String here = at;
        List<Link> onward =
            around.getOrDefault(here, List.of()).stream()
                .filter(link -> link != from && inside.contains(link.other(here)))
                .toList();
        if (onward.size() != 1) {
          break;
        }
        Link step = onward.get(0);
        line.add(new Piece(step, Math.max(1.0, trackLength(step)), cellsFrom(step, here)));
        used.add(step);
        came = step;
        at = step.other(here);
        if (around.getOrDefault(at, List.of()).size() != 2) {
          break;
        }
      }
      return line;
    }

    /** 成员车库的库线按车库代码分组：库线节点 → 库号，只留 {@code present} 认可的节点。 */
    private Map<String, Map<String, String>> depotTracksByCode(
        java.util.function.Predicate<String> present) {
      Map<String, Map<String, String>> byCode = new LinkedHashMap<>();
      for (YardArea.Track track : area.depotTracks()) {
        if (present.test(track.node().value())) {
          byCode
              .computeIfAbsent(track.station(), ignored -> new LinkedHashMap<>())
              .put(track.node().value(), track.number());
        }
      }
      return byCode;
    }

    /** 车库内部：库线、紧挨库线的本库咽喉点，再反复加上有两条以上区间连进来的节点（分岔道岔）。站台股道、出口末端与别的车库的库线不算。 */
    private Set<String> depotInterior(String code, Set<String> tracks, Set<String> keys) {
      Set<String> platforms = new HashSet<>();
      area.tracks().forEach(track -> platforms.add(track.node().value()));
      Set<String> otherDepots = new HashSet<>();
      area.depotTracks().stream()
          .filter(track -> !track.station().equals(code))
          .forEach(track -> otherDepots.add(track.node().value()));
      Set<String> inside = new HashSet<>(tracks);
      for (String key : tracks) {
        for (Link link : around.getOrDefault(key, List.of())) {
          String other = link.other(key);
          if (keys.contains(other) && code.equals(throatOf.get(other))) {
            inside.add(other);
          }
        }
      }
      boolean grew = true;
      while (grew) {
        grew = false;
        for (String key : keys) {
          if (inside.contains(key)
              || exitKey(key)
              || platforms.contains(key)
              || otherDepots.contains(key)) {
            continue;
          }
          long into =
              around.getOrDefault(key, List.of()).stream()
                  .filter(link -> inside.contains(link.other(key)))
                  .count();
          if (into >= 2) {
            inside.add(key);
            grew = true;
          }
        }
      }
      return inside;
    }

    /** 区间的轨道方块，从 {@code from} 那头排起。 */
    private List<RailFootprintCell> cellsFrom(Link link, String from) {
      Spot a = spots.get(from);
      Spot b = spots.get(link.other(from));
      return link.cells().stream()
          .sorted(Comparator.comparingDouble(c -> distance(center(c), a) - distance(center(c), b)))
          .toList();
    }

    /**
     * 画面板：贴着库门点、朝离开库外的方向伸出去，上面一行写库名，下面每条库线一行短条（库号写在左边）。短条按组成库线的各段长度分开，
     * 每段是一条区间的线，按占用着色；库线上的车厢按在区间里的先后落到短条上，库门里的车厢落在库门点。
     */
    private Depot panel(
        String key,
        Collapsed depot,
        Map<String, Point> points,
        Function<String, String> stationName,
        List<Path> paths,
        Map<RailFootprintCell, Point> cells) {
      Point door = points.get(key);
      double outside =
          around.getOrDefault(key, List.of()).stream()
              .mapToInt(link -> points.get(link.other(key)).x())
              .average()
              .orElse(door.x() - 1.0);
      int side = outside <= door.x() ? 1 : -1;
      int count = depot.lines().size();
      int height = DEPOT_PANEL_ROW * (count + 1) + 4;
      int width = DEPOT_PANEL_WIDTH;
      int maxRight = rect.left() + rect.width() - 1;
      int maxBottom = rect.top() + rect.height() - 1;
      int left = side > 0 ? door.x() + 2 : door.x() - 2 - width;
      left = Math.max(rect.left(), Math.min(left, maxRight - width));
      int top = Math.max(rect.top(), Math.min(door.y() - height / 2, maxBottom - height));
      int barLeft = left + DEPOT_PANEL_LABEL;
      int barRight = left + width - 6;
      List<DepotTrack> drawn = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        int y = top + DEPOT_PANEL_ROW * (i + 1) + DEPOT_PANEL_ROW / 2;
        List<Piece> line = depot.lines().get(i);
        double total = line.stream().mapToDouble(Piece::length).sum();
        // 尽头在离库门远的一头
        int endX = side > 0 ? barRight : barLeft;
        int doorX = side > 0 ? barLeft : barRight;
        double walked = 0.0;
        for (Piece piece : line) {
          int x0 = (int) Math.round(endX + (doorX - endX) * walked / total);
          walked += piece.length();
          int x1 = (int) Math.round(endX + (doorX - endX) * walked / total);
          paths.add(
              new Path(
                  piece.link().edge(),
                  List.of(new Segment(Math.min(x0, x1), y, Math.max(x0, x1), y))));
          int n = piece.cells().size();
          for (int c = 0; c < n; c++) {
            cells.putIfAbsent(
                piece.cells().get(c),
                new Point((int) Math.round(x0 + (x1 - x0) * (c + 0.5) / n), y));
          }
        }
        drawn.add(new DepotTrack(depot.numbers().get(i), new Point(barLeft, y), false, -1));
      }
      for (Link link : depot.ladder()) {
        link.cells().forEach(cell -> cells.putIfAbsent(cell, door));
      }
      return new Depot(
          depot.code(),
          stationName.apply(depot.code()),
          left,
          top,
          left + width,
          top + height,
          drawn,
          true);
    }

    /**
     * 一片轨道的各条链依次定横坐标：先排脊线，再一条条排一头接在已排好节点上的链（侧股从它岔出的道岔起，顺着岔出的方向接着排， 和脊线几乎垂直的库线因此不会排到道岔后面去）；
     * 接不上的照旧按垂足排。
     */
    private void placeChains(List<Chain> part) {
      List<Chain> pending = new ArrayList<>(part);
      Chain spine = pending.stream().max(Comparator.comparingDouble(Chain::length)).orElseThrow();
      pending.remove(spine);
      place(spine);
      boolean progress = true;
      while (!pending.isEmpty() && progress) {
        progress = false;
        for (java.util.Iterator<Chain> it = pending.iterator(); it.hasNext(); ) {
          if (attach(it.next())) {
            it.remove();
            progress = true;
          }
        }
      }
      pending.forEach(this::place);
    }

    /**
     * 链上某个节点经一条区间接在已排好的节点上时（侧股接在道岔上，或两条平行线之间的渡线），从那个节点顺着这条区间离开的方向排到这个节点，
     * 再沿链往两头排：顺着进来方向的那头同向，另一头反向；进来的方向和链几乎垂直时按链在脊线走向上朝哪边排（同 {@link #place}）。接不上时返回 false。
     */
    private boolean attach(Chain chain) {
      List<String> keys = chain.spots();
      for (int index = 0; index < keys.size(); index++) {
        String key = keys.get(index);
        for (Link link : around.getOrDefault(key, List.of())) {
          String from = link.other(key);
          if (chain.links().contains(link) || !uOf.containsKey(from)) {
            continue;
          }
          double sign = leaving(from, link);
          next(from, key, link, sign);
          Spot at = spots.get(key);
          double[] arriving = negate(direction(at, link));
          Frame.Located where = located.get(key);
          double forward = sign;
          if (keys.size() > 1) {
            // 沿链往后（序号增大）那头的走向
            double[] ahead =
                index + 1 < keys.size()
                    ? direction(at, chain.links().get(index))
                    : negate(direction(at, chain.links().get(index - 1)));
            double along = arriving[0] * ahead[0] + arriving[1] * ahead[1];
            // 进来的方向和链几乎垂直时分不出顺逆，改看链在脊线走向上朝哪边
            forward =
                Math.abs(along) >= DIRECTION_MIN_COSINE
                    ? (along >= 0 ? sign : -sign)
                    : (ahead[0] * where.dirX() + ahead[1] * where.dirZ() >= 0 ? 1.0 : -1.0);
          }
          for (int i = index + 1; i < keys.size(); i++) {
            next(keys.get(i - 1), keys.get(i), chain.links().get(i - 1), forward);
          }
          for (int i = index - 1; i >= 0; i--) {
            next(keys.get(i + 1), keys.get(i), chain.links().get(i), -forward);
          }
          return true;
        }
      }
      return false;
    }

    /**
     * 从已排好的 {@code from} 经 {@code link} 离开时沿线往哪边走（+1 里程增大）：看它贴着 {@code from} 的哪条直股离开——同侧就跟那条直股同向，
     * 反侧（直着穿过去）就反向；{@code from} 没有别的直股、或离开方向和直股几乎垂直分不出顺逆时，按离开方向在脊线走向上的投影。
     */
    private double leaving(String from, Link link) {
      Spot at = spots.get(from);
      double[] out = direction(at, link);
      Link best = null;
      double bestDot = Double.NEGATIVE_INFINITY;
      for (Link other : through.getOrDefault(from, Set.of())) {
        if (other == link || !uOf.containsKey(other.other(from))) {
          continue;
        }
        double[] d = direction(at, other);
        double dot = d[0] * out[0] + d[1] * out[1];
        if (dot > bestDot) {
          best = other;
          bestDot = dot;
        }
      }
      if (best != null && Math.abs(bestDot) >= DIRECTION_MIN_COSINE) {
        double du = uOf.get(best.other(from)) - uOf.get(from);
        if (Math.abs(du) > 1e-6) {
          return (bestDot >= 0 ? 1.0 : -1.0) * Math.signum(du);
        }
      }
      Frame.Located where = located.get(from);
      return out[0] * where.dirX() + out[1] * where.dirZ() >= 0 ? 1.0 : -1.0;
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
     * 一条链的横坐标：离本片脊线最近的节点（按不含平移的横向距离）取它在脊线上的垂足；沿链往两头走，下一个节点的垂足朝前挪了至少半个区间长时取垂足（与脊线平行的股道和脊线对齐），
     * 否则按轨道长度接着排（斜着岔出去的支线）。
     */
    private void place(Chain chain) {
      List<String> keys = chain.spots();
      int anchor = 0;
      for (int i = 1; i < keys.size(); i++) {
        if (Math.abs(located.get(keys.get(i)).ownV())
            < Math.abs(located.get(keys.get(anchor)).ownV())) {
          anchor = i;
        }
      }
      Spot at = spots.get(keys.get(anchor));
      Frame.Located where = located.get(at.key());
      uOf.put(at.key(), where.u());
      double sign = 1.0;
      if (!chain.links().isEmpty()) {
        double[] along =
            anchor < chain.links().size()
                ? direction(at, chain.links().get(anchor))
                : negate(direction(at, chain.links().get(anchor - 1)));
        sign = along[0] * where.dirX() + along[1] * where.dirZ() >= 0 ? 1.0 : -1.0;
      }
      for (int i = anchor + 1; i < keys.size(); i++) {
        next(keys.get(i - 1), keys.get(i), chain.links().get(i - 1), sign);
      }
      for (int i = anchor - 1; i >= 0; i--) {
        next(keys.get(i + 1), keys.get(i), chain.links().get(i), -sign);
      }
    }

    /**
     * 下一个节点：垂足朝前挪了半个到一个半区间长时取垂足（与脊线平行的股道与脊线对齐），否则按轨道长度接着排——垂足挪不动是斜着岔出去的支线，
     * 挪得比轨道还长很多是垂足落到了脊线的别处（例如和脊线几乎垂直的库线）。
     */
    private void next(String from, String to, Link link, double sign) {
      double before = uOf.get(from);
      double projected = located.get(to).u();
      double length = Math.max(1.0, trackLength(link));
      double moved = sign * (projected - before);
      uOf.put(
          to,
          moved >= 0.5 * length && moved <= MAX_PROJECTED_STEP * length
              ? projected
              : before + sign * length);
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
      Set<String> platformKeys = new HashSet<>();
      area.tracks().forEach(track -> platformKeys.add(track.node().value()));
      // 首尾相接的行接在同一层若画出重叠的水平线（道岔群里常见），这些行改为不接、重排；最多试三次
      Set<Row> separate = new HashSet<>();
      Map<String, Point> laid = points(rows, platformKeys, separate);
      for (int attempt = 0; attempt < 3; attempt++) {
        Set<Row> crowded = overlapping(rows, laid);
        crowded.removeAll(separate);
        if (crowded.isEmpty()) {
          break;
        }
        separate.addAll(crowded);
        laid = points(rows, platformKeys, separate);
      }
      Map<String, Point> points = laid;
      List<Path> paths = new ArrayList<>();
      Map<RailFootprintCell, Point> cells = new HashMap<>();
      Map<Integer, List<Span>> spans = new HashMap<>();
      List<Segment> diagonals = new ArrayList<>();
      Map<Link, List<Point>> routes = routes(points).routes();
      for (Link link : links) {
        List<Point> route = routes.get(link);
        paths.add(new Path(link.edge(), segments(route)));
        placeCells(link, route, points, cells);
        for (int i = 0; i + 1 < route.size(); i++) {
          Point p = route.get(i);
          Point q = route.get(i + 1);
          if (p.y() != q.y()) {
            diagonals.add(new Segment(p.x(), p.y(), q.x(), q.y()));
          } else {
            Row row = p.y() == points.get(link.a()).y() ? rows.get(link.a()) : rows.get(link.b());
            spans
                .computeIfAbsent(p.y(), ignored -> new ArrayList<>())
                .add(new Span(p.y(), Math.min(p.x(), q.x()), Math.max(p.x(), q.x()), row));
          }
        }
      }
      List<Platform> platforms = platforms(area, rows, points, spans, diagonals, stationName);
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
                        exits.putIfAbsent(
                            text + (right ? "|R" : "|L"),
                            new Label(at, text, right, Optional.of(code)));
                      }));
      List<Stub> stubs = new ArrayList<>();
      Map<String, Point> nodes = new HashMap<>();
      for (Link link : links) {
        if (exitOf.containsKey(link.b())) {
          List<Point> route = routes.get(link);
          stubs.add(new Stub(link.edge(), route.get(0), route.get(1)));
        }
      }
      points.forEach(
          (key, at) -> {
            if (!key.startsWith(EXIT_PREFIX) && !key.startsWith(DEPOT_PREFIX)) {
              nodes.put(key, at);
            }
          });
      List<Depot> depots = new ArrayList<>(depots(points, stationName));
      collapsed.forEach(
          (key, depot) -> depots.add(panel(key, depot, points, stationName, paths, cells)));
      return new YardGeometry(
          paths, platforms, List.copyOf(exits.values()), depots, cells, partial, stubs, nodes);
    }

    /** 照画库线的车库：库线、紧挨库线的库内咽喉点，以及有两条以上区间连进来的分岔道岔，按彼此相连分片，每片一个范围。 库名只写在库线最多的那片上（另几片名字为空）。 */
    private List<Depot> depots(Map<String, Point> points, Function<String, String> stationName) {
      List<Depot> out = new ArrayList<>();
      depotTracksByCode(points::containsKey)
          .forEach(
              (code, numberOf) -> {
                Set<String> inside = depotInterior(code, numberOf.keySet(), points.keySet());
                List<Set<String>> groups = groups(inside);
                groups.sort(
                    Comparator.comparingInt(
                        (Set<String> group) ->
                            -(int) group.stream().filter(numberOf::containsKey).count()));
                for (int g = 0; g < groups.size(); g++) {
                  Set<String> group = groups.get(g);
                  List<String> ends =
                      group.stream()
                          .filter(numberOf::containsKey)
                          .sorted(Comparator.comparing(numberOf::get, TRACK_ORDER))
                          .toList();
                  if (ends.isEmpty()) {
                    continue;
                  }
                  int left = Integer.MAX_VALUE;
                  int right = Integer.MIN_VALUE;
                  int top = Integer.MAX_VALUE;
                  int bottom = Integer.MIN_VALUE;
                  for (String key : group) {
                    Point at = points.get(key);
                    left = Math.min(left, at.x());
                    right = Math.max(right, at.x());
                    top = Math.min(top, at.y());
                    bottom = Math.max(bottom, at.y() + TRACK_LINE);
                  }
                  List<DepotTrack> drawn = new ArrayList<>();
                  for (String key : ends) {
                    List<Link> attached = around.getOrDefault(key, List.of());
                    Point end = points.get(key);
                    int outward = 1;
                    if (attached.size() == 1) {
                      outward = points.get(attached.get(0).other(key)).x() <= end.x() ? 1 : -1;
                    }
                    drawn.add(
                        new DepotTrack(numberOf.get(key), end, attached.size() == 1, outward));
                  }
                  out.add(
                      new Depot(
                          code,
                          g == 0 ? stationName.apply(code) : "",
                          left,
                          top,
                          right,
                          bottom,
                          drawn,
                          false));
                }
              });
      return out;
    }

    /** 一组节点按区间彼此相连分片。 */
    private List<Set<String>> groups(Set<String> keys) {
      List<Set<String>> out = new ArrayList<>();
      Set<String> seen = new HashSet<>();
      for (String start : keys.stream().sorted().toList()) {
        if (!seen.add(start)) {
          continue;
        }
        Set<String> group = new LinkedHashSet<>(List.of(start));
        java.util.Deque<String> queue = new java.util.ArrayDeque<>(List.of(start));
        while (!queue.isEmpty()) {
          String at = queue.poll();
          for (Link link : around.getOrDefault(at, List.of())) {
            String other = link.other(at);
            if (keys.contains(other) && seen.add(other)) {
              group.add(other);
              queue.add(other);
            }
          }
        }
        out.add(group);
      }
      return out;
    }

    /**
     * 站台块。站台在股道哪一侧先看 AutoStation 牌子写的开门方向：同一车站上下相邻的两条站台股道开门相对（或写了两侧都开）时当作两线共用的岛式站台，
     * 有一条背着对方开、或两条中间夹着别的轨道就不是；侧式站台画在开门那一侧。没写开门方向时按真实股道间距推断：两条都没写、相隔至少 {@value #MIN_ISLAND_BLOCKS}
     * 格才当作岛式；侧式站台放在放得下的一侧，两侧都放得下时放在离邻线更远的一侧（图边算无限远）。 两侧都开门的股道可以同时是上下两块岛式站台的一边（例如三条股道夹着两块岛式站台）。
     */
    private List<Platform> platforms(
        YardArea area,
        Map<String, Row> rowOf,
        Map<String, Point> points,
        Map<Integer, List<Span>> spans,
        List<Segment> diagonals,
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
      Function<String, Optional<String>> code =
          key -> several ? Optional.of(stationOf.get(key)) : Optional.empty();
      keys.sort(
          Comparator.comparingInt((String key) -> points.get(key).y())
              .thenComparingInt(key -> points.get(key).x()));
      int pitch = rowPitch(points);
      // 已经是上面某块岛式站台下沿的股道：不再配给别人，也不再单画侧式站台，但还可以往下再配一块岛式站台
      Set<String> islandBelow = new HashSet<>();
      List<Platform> out = new ArrayList<>();
      for (String key : keys) {
        Point at = points.get(key);
        int[] range = extent(key, points);
        double v = rowOf.get(key).v();
        Optional<Span> up = neighbour(at, spans, -1);
        Optional<Span> down = neighbour(at, spans, 1);
        double upGap = up.map(span -> Math.abs(v - span.row().v())).orElse(Double.MAX_VALUE);
        double downGap = down.map(span -> Math.abs(v - span.row().v())).orElse(Double.MAX_VALUE);
        // 岛式站台一般只配近处的下一条线（最多隔一层，比如中间夹着渡线的交叉点）：凭间距猜的隔得更远，画一块站台会变成一根竖条；
        // 两条都写了开门方向、面对面开时不限（两行之间在这个位置上本来就没有别的线）
        Optional<String> partner =
            down.flatMap(
                span ->
                    keys.stream()
                        .filter(other -> !islandBelow.contains(other) && !other.equals(key))
                        .filter(other -> stationOf.get(other).equals(stationOf.get(key)))
                        .filter(other -> points.get(other).y() == span.y())
                        .filter(other -> overlaps(range, extent(other, points)))
                        .filter(
                            other ->
                                pitch == 0
                                    || span.y() - at.y() <= 2 * pitch
                                    || (opens(key, 1).orElse(false)
                                        && opens(other, -1).orElse(false)))
                        .filter(other -> island(key, other))
                        .findFirst());
        if (partner.isPresent()) {
          int[] other = extent(partner.get(), points);
          islandBelow.add(partner.get());
          int top = at.y() + TRACK_LINE + PLATFORM_MARGIN;
          int bottom = points.get(partner.get()).y() - PLATFORM_MARGIN;
          int[] box =
              clear(
                  new int[] {Math.min(range[0], other[0]), Math.max(range[1], other[1])},
                  top,
                  bottom,
                  at.x(),
                  diagonals);
          out.add(
              new Platform(
                  box[0],
                  top,
                  box[1],
                  bottom,
                  List.of(numberOf.get(key), numberOf.get(partner.get())),
                  caption.apply(key),
                  code.apply(key)));
          continue;
        }
        if (islandBelow.contains(key)) {
          continue;
        }
        Optional<Boolean> opensDown =
            doors.get(key) == AutoStationDoorDirection.BOTH ? Optional.empty() : opens(key, 1);
        boolean above =
            opensDown
                .map(downward -> !downward)
                .orElseGet(
                    () ->
                        upGap >= MIN_PLATFORM_BLOCKS && downGap >= MIN_PLATFORM_BLOCKS
                            ? upGap >= downGap
                            : upGap >= MIN_PLATFORM_BLOCKS || downGap < upGap);
        int top =
            above
                ? at.y() - PLATFORM_MARGIN - SIDE_PLATFORM_DEPTH
                : at.y() + TRACK_LINE + PLATFORM_MARGIN;
        int[] box = clear(range, top, top + SIDE_PLATFORM_DEPTH, at.x(), diagonals);
        out.add(
            new Platform(
                box[0],
                top,
                box[1],
                top + SIDE_PLATFORM_DEPTH,
                List.of(numberOf.get(key)),
                caption.apply(key),
                code.apply(key)));
      }
      return out;
    }

    /**
     * 上下相邻的两条站台股道（{@code upper} 在上）之间是不是一块岛式站台：写了开门方向的那条（或两条）都朝对方开，且中间放得下站台； 两条都没写时要相隔至少 {@value
     * #MIN_ISLAND_BLOCKS} 格。
     */
    private boolean island(String upper, String lower) {
      if (trackBetween(upper, lower)) {
        return false;
      }
      double gap = lateral(upper, lower);
      Optional<Boolean> downward = opens(upper, 1);
      Optional<Boolean> upward = opens(lower, -1);
      if (downward.isEmpty() && upward.isEmpty()) {
        return gap >= MIN_ISLAND_BLOCKS;
      }
      return downward.orElse(true) && upward.orElse(true) && gap >= MIN_PLATFORM_BLOCKS;
    }

    /**
     * 站台股道朝画面下方（{@code toward} 为正）或上方（为负）开门：开门的世界方位投到这一处的横向上（横向距离增大的一侧画在下面）。
     * 两侧都开时两边都算；没写开门方向或方向几乎顺着股道时为空。
     */
    private Optional<Boolean> opens(String key, int toward) {
      AutoStationDoorDirection door = doors.get(key);
      if (door == null) {
        return Optional.empty();
      }
      if (door == AutoStationDoorDirection.BOTH) {
        return Optional.of(true);
      }
      Optional<BlockFace> face = door.toBlockFace();
      Frame.Located at = located.get(key);
      if (face.isEmpty() || at == null) {
        return Optional.empty();
      }
      double fx = face.get().getModX();
      double fz = face.get().getModZ();
      double side = (-fx * at.dirZ() + fz * at.dirX()) / Math.hypot(fx, fz);
      if (Math.abs(side) < DOOR_SIDE_MIN_COSINE) {
        return Optional.empty();
      }
      return Optional.of(side * toward > 0);
    }

    /**
     * 站台块让开斜线：在块的上下范围内经过的斜线，在站台节点左边的把块的左沿推到它右边，在右边的把右沿推到它左边，线就不会压在站台与股道号上。 至少给节点两边各留 {@value
     * #MIN_PLATFORM_HALF} 像素。
     */
    private static int[] clear(
        int[] range, int top, int bottom, int anchor, List<Segment> diagonals) {
      int left = range[0];
      int right = range[1];
      for (Segment segment : diagonals) {
        int low = Math.min(segment.y0(), segment.y1());
        int high = Math.max(segment.y0(), segment.y1());
        if (high <= top || low >= bottom) {
          continue;
        }
        int[] across = xAcross(segment, Math.max(top, low), Math.min(bottom, high));
        if (across[1] <= anchor) {
          left = Math.max(left, across[1] + 3);
        } else if (across[0] >= anchor) {
          right = Math.min(right, across[0] - 3);
        }
      }
      left = Math.min(left, anchor - MIN_PLATFORM_HALF);
      right = Math.max(right, anchor + MIN_PLATFORM_HALF);
      return new int[] {left, right};
    }

    /** 斜线在纵坐标 [from, to] 之间扫过的横坐标范围 {最小, 最大}。 */
    private static int[] xAcross(Segment segment, int from, int to) {
      int a = xAt(segment, from);
      int b = xAt(segment, to);
      return new int[] {Math.min(a, b), Math.max(a, b)};
    }

    private static int xAt(Segment segment, int y) {
      if (segment.y1() == segment.y0()) {
        return segment.x0();
      }
      return (int)
          Math.round(
              segment.x0()
                  + (double) (y - segment.y0())
                      * (segment.x1() - segment.x0())
                      / (segment.y1() - segment.y0()));
    }

    /** 同一层上不同区间的水平线互相重叠时，两条区间两端所在的行；因为会叠线才退成斜线的区间两端所在的行也算。 */
    private Set<Row> overlapping(Map<String, Row> rowOf, Map<String, Point> points) {
      Map<Integer, List<int[]>> byLayer = new HashMap<>();
      List<Link> drawn = new ArrayList<>();
      Routed routed = routes(points);
      Map<Link, List<Point>> routes = routed.routes();
      Set<Row> out = new HashSet<>();
      // 几种摆法都会叠线、只好退成斜线的区间，也当作重叠：拆开它两端的行重排一次，可能就放得下 45° 了
      for (Link link : routed.blocked()) {
        out.add(rowOf.get(link.a()));
        out.add(rowOf.get(link.b()));
      }
      for (Link link : links) {
        List<Point> route = routes.get(link);
        for (int i = 0; i + 1 < route.size(); i++) {
          Point p = route.get(i);
          Point q = route.get(i + 1);
          if (p.y() == q.y() && p.x() != q.x()) {
            byLayer
                .computeIfAbsent(p.y(), ignored -> new ArrayList<>())
                .add(
                    new int[] {
                      Math.min(p.x(), q.x()),
                      Math.max(p.x(), q.x()),
                      drawn.size(),
                      route.size() > 2 ? joins(route, i) : NO_JOIN
                    });
          }
        }
        drawn.add(link);
      }
      for (List<int[]> segments : byLayer.values()) {
        for (int i = 0; i < segments.size(); i++) {
          for (int j = i + 1; j < segments.size(); j++) {
            int[] a = segments.get(i);
            int[] b = segments.get(j);
            boolean merging = a[3] != NO_JOIN && a[3] == b[3];
            if (a[2] != b[2] && !merging && Math.min(a[1], b[1]) - Math.max(a[0], b[0]) > 2) {
              for (Link link : List.of(drawn.get(a[2]), drawn.get(b[2]))) {
                out.add(rowOf.get(link.a()));
                out.add(rowOf.get(link.b()));
              }
            }
          }
        }
      }
      return out;
    }

    /** 相邻两层的间距（像素）；只有一层时为 0。 */
    private static int rowPitch(Map<String, Point> points) {
      List<Integer> ys = points.values().stream().map(Point::y).distinct().sorted().toList();
      int pitch = 0;
      for (int i = 1; i < ys.size(); i++) {
        int gap = ys.get(i) - ys.get(i - 1);
        pitch = pitch == 0 ? gap : Math.min(pitch, gap);
      }
      return pitch;
    }

    /** 站台块的左右范围：以站台节点为中心，不越过同一行相邻的节点，也不出画面。 */
    private int[] extent(String key, Map<String, Point> points) {
      Point at = points.get(key);
      int left = Math.max(rect.left(), at.x() - PLATFORM_HALF);
      int right = Math.min(rect.left() + rect.width() - 1, at.x() + PLATFORM_HALF);
      for (Map.Entry<String, Point> entry : points.entrySet()) {
        Point other = entry.getValue();
        if (entry.getKey().equals(key)
            || other.y() != at.y()
            || passThrough.contains(entry.getKey())) {
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

    /** 往上（{@code direction} 为 -1）或往下最近一段在这个横坐标画了水平线的跨度。 */
    private static Optional<Span> neighbour(
        Point at, Map<Integer, List<Span>> spans, int direction) {
      Optional<Span> best = Optional.empty();
      for (Map.Entry<Integer, List<Span>> entry : spans.entrySet()) {
        int y = entry.getKey();
        if (direction < 0 ? y >= at.y() : y <= at.y()) {
          continue;
        }
        Optional<Span> covering =
            entry.getValue().stream()
                .filter(span -> span.left() <= at.x() && at.x() <= span.right())
                .findFirst();
        if (covering.isPresent()
            && (best.isEmpty() || Math.abs(y - at.y()) < Math.abs(best.get().y() - at.y()))) {
          best = covering;
        }
      }
      return best;
    }

    /**
     * 直股：只连两条区间的节点两条都是（两条朝同一边离开的 V 形除外）；只连一条的就是那一条；三条以上的取离开方向最接近一直线的两条。
     *
     * <p>离开方向取这条区间离节点 {@value #BRANCH_PROBE_BLOCKS} 格以内轨道方块的重心；没有方块时取另一头节点的方向。
     */
    private Set<Link> throughLinks(Spot spot) {
      List<Link> mine = around.getOrDefault(spot.key(), List.of());
      if (mine.size() == 2) {
        // 两条区间朝同一边离开（V 形，道岔的第三条腿不在图上）：不算直股，否则这一行会折回来、线叠在一起
        double[] a = direction(spot, mine.get(0));
        double[] b = direction(spot, mine.get(1));
        return a[0] * b[0] + a[1] * b[1] > 0 ? Set.of() : Set.copyOf(mine);
      }
      if (mine.size() < 2) {
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

    /**
     * 行：直股连成的一条链整条占一行，几个车站之间的同一条正线因此画成一条直线。站台股道所在的链先排，落在同一条股道延长线上的站台股道并为一行；
     * 其余的链各占一行。行的横向位置取起头的站台股道，没有站台的链取链上各节点的平均。
     */
    private Map<String, Row> rows(YardArea area) {
      Map<String, Row> rowOf = new HashMap<>();
      List<Row> seeds = new ArrayList<>();
      List<Spot> seedSpots = new ArrayList<>();
      List<Spot> platforms =
          area.tracks().stream()
              .map(track -> spots.get(track.node().value()))
              .filter(Objects::nonNull)
              .sorted(Comparator.comparingDouble(s -> vOf.get(s.key())))
              .toList();
      for (Spot spot : platforms) {
        if (rowOf.containsKey(spot.key())) {
          continue;
        }
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
        for (String key : chainOf.get(spot.key()).spots()) {
          rowOf.put(key, row);
        }
      }
      for (Chain chain : chains) {
        if (rowOf.containsKey(chain.spots().get(0))) {
          continue;
        }
        Row row = new Row(chain.spots().stream().mapToDouble(vOf::get).average().orElse(0.0));
        chain.spots().forEach(key -> rowOf.put(key, row));
      }
      return rowOf;
    }

    /** {@code other} 落在 {@code spot} 所在股道（取直股的走向）的延长线上：横向偏差不到 {@value #SAME_ROW_BLOCKS} 格。 */
    private boolean inLine(Spot spot, Spot other) {
      return lateral(spot.key(), other.key()) < SAME_ROW_BLOCKS;
    }

    /** {@code other} 偏离 {@code key} 所在股道（取直股的走向）的横向距离（格），按两处的轨道方块量；没有直股时按离脊线的距离之差。 */
    private double lateral(String key, String other) {
      Optional<Link> straight =
          through.getOrDefault(key, Set.of()).stream().min(Comparator.comparing(Link::edge));
      if (straight.isEmpty()) {
        return Math.abs(vOf.get(key) - vOf.get(other));
      }
      double[] along = direction(spots.get(key), straight.get());
      double[] from = rail(key);
      double[] to = rail(other);
      double ox = to[0] - from[0];
      double oz = to[1] - from[1];
      return Math.abs(-ox * along[1] + oz * along[0]);
    }

    /** 节点处的轨道方块中心（见 {@link #railCell}）；附近没有轨道方块时用节点自己的位置。 */
    private double[] rail(String key) {
      Spot spot = spots.get(key);
      return railCell(key).map(YardGeometry::center).orElse(new double[] {spot.x(), spot.z()});
    }

    /**
     * 节点处的轨道方块：取连到它的区间里离节点最近的一格。节点坐标是牌子的位置，常在轨道旁边或下面差一两格，量股道间距要按轨道。离节点超过 {@value #RAIL_SNAP_BLOCKS}
     * 格都没有时为空。
     */
    private Optional<RailFootprintCell> railCell(String key) {
      return rails.computeIfAbsent(
          key,
          ignored -> {
            Spot spot = spots.get(key);
            RailFootprintCell best = null;
            double bestDistance = RAIL_SNAP_BLOCKS;
            for (Link link : around.getOrDefault(key, List.of())) {
              for (RailFootprintCell cell : link.cells()) {
                double d = distance(center(cell), spot);
                if (d <= bestDistance) {
                  bestDistance = d;
                  best = cell;
                }
              }
            }
            return Optional.ofNullable(best);
          });
    }

    /**
     * 两条股道的轨道之间（从一条的站台节点处横穿到另一条）还夹着别的区间的轨道，例如两条站台股道中间的正线：那就不是两线共用的一块岛式站台。 查不到方块所属的区间（索引不可用）时当作没有。
     */
    private boolean trackBetween(String upper, String lower) {
      Optional<RailFootprintCell> from = railCell(upper);
      Optional<RailFootprintCell> to = railCell(lower);
      if (from.isEmpty() || to.isEmpty()) {
        return false;
      }
      Set<EdgeId> own = new HashSet<>();
      for (String key : List.of(upper, lower)) {
        around.getOrDefault(key, List.of()).forEach(link -> own.add(link.edge()));
      }
      int dx = to.get().x() - from.get().x();
      int dz = to.get().z() - from.get().z();
      int steps = Math.max(Math.abs(dx), Math.abs(dz));
      for (int i = 1; i < steps; i++) {
        int x = from.get().x() + (int) Math.round((double) dx * i / steps);
        int z = from.get().z() + (int) Math.round((double) dz * i / steps);
        for (int dy = -1; dy <= 1; dy++) {
          for (EdgeId edge : railAt.apply(new RailFootprintCell(x, from.get().y() + dy, z))) {
            if (!own.contains(edge)) {
              return true;
            }
          }
        }
      }
      return false;
    }

    /** 各行分层（见 {@link #levels}）、各层等距排开并在区域内居中；横坐标见 {@link #columns}。 */
    private Map<String, Point> points(
        Map<String, Row> rowOf, Set<String> platformKeys, Set<Row> separate) {
      Map<Row, Integer> rowIndex = levels(rowOf, separate);
      int count = rowIndex.values().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
      int height = Math.max(1, rect.height() - 1);
      int gap = count > 1 ? Math.min(ROW_GAP, height / (count - 1)) : 0;
      int top = rect.top() + (height - gap * (count - 1)) / 2;
      Map<String, Integer> xs = columns(rowOf, rowIndex, gap, platformKeys);
      Map<String, Point> points = new HashMap<>();
      for (Spot spot : spots.values()) {
        points.put(
            spot.key(),
            new Point(xs.get(spot.key()), top + rowIndex.get(rowOf.get(spot.key())) * gap));
      }
      return points;
    }

    /**
     * 行压紧：按横向位置从上到下依次放，每行放在与它横向范围重叠的已放各行的下一层，不重叠的行共用一层；再自下而上把每行压到紧挨着它下面重叠的那一行。
     * 横向范围按画出来的水平线算（含区间另一头）；不相连的两行之间还要隔开 {@value #ROW_CLEARANCE_BLOCKS}
     * 格，直接相连的两行只看自己的节点，首尾相接的同一条股道因此接在同一层、画成直线。
     */
    private Map<Row, Integer> levels(Map<String, Row> rowOf, Set<Row> separate) {
      Map<Row, double[]> own = new HashMap<>();
      Map<Row, double[]> drawn = new HashMap<>();
      rowOf.forEach(
          (key, row) -> {
            widen(own, row, uOf.get(key));
            widen(drawn, row, uOf.get(key));
          });
      Map<Row, Set<Row>> linked = new HashMap<>();
      for (Link link : links) {
        Row a = rowOf.get(link.a());
        Row b = rowOf.get(link.b());
        widen(drawn, a, uOf.get(link.b()));
        widen(drawn, b, uOf.get(link.a()));
        if (a != b) {
          linked.computeIfAbsent(a, ignored -> new HashSet<>()).add(b);
          linked.computeIfAbsent(b, ignored -> new HashSet<>()).add(a);
        }
      }
      List<Row> ordered =
          own.keySet().stream()
              .sorted(
                  Comparator.comparingDouble(Row::v).thenComparingDouble(row -> own.get(row)[0]))
              .toList();
      // 首尾相接只认两头都有一段长度的行：道岔群里只有一个节点的行若也算，几段短线会在同一层上叠在一起
      java.util.function.BiPredicate<Row, Row> clash =
          (row, other) ->
              linked.getOrDefault(row, Set.of()).contains(other)
                      && !separate.contains(row)
                      && !separate.contains(other)
                      && width(own.get(row)) >= ROW_SHARE_BLOCKS
                      && width(own.get(other)) >= ROW_SHARE_BLOCKS
                  ? overlaps(own.get(row), own.get(other), 0.0)
                  : overlaps(drawn.get(row), drawn.get(other), ROW_CLEARANCE_BLOCKS);
      Map<Row, Integer> level = new HashMap<>();
      for (Row row : ordered) {
        int at = 0;
        for (Map.Entry<Row, Integer> placed : level.entrySet()) {
          if (clash.test(row, placed.getKey())) {
            at = Math.max(at, placed.getValue() + 1);
          }
        }
        level.put(row, at);
      }
      // 自下而上再走一遍：每行往下压到紧挨着与它重叠的下一行，侧线贴着它岔出的正线，不浮到最上面
      for (int i = ordered.size() - 2; i >= 0; i--) {
        Row row = ordered.get(i);
        int limit = Integer.MAX_VALUE;
        for (int j = i + 1; j < ordered.size(); j++) {
          Row below = ordered.get(j);
          if (clash.test(row, below)) {
            limit = Math.min(limit, level.get(below) - 1);
          }
        }
        if (limit != Integer.MAX_VALUE && limit > level.get(row)) {
          level.put(row, limit);
        }
      }
      return level;
    }

    private static void widen(Map<Row, double[]> ranges, Row row, double u) {
      double[] range = ranges.computeIfAbsent(row, ignored -> new double[] {u, u});
      range[0] = Math.min(range[0], u);
      range[1] = Math.max(range[1], u);
    }

    private static double width(double[] range) {
      return range[1] - range[0];
    }

    private static boolean overlaps(double[] a, double[] b, double clearance) {
      return a[0] < b[1] + clearance && b[0] < a[1] + clearance;
    }

    /**
     * 横坐标：节点按沿线先后排开。相邻两处之间的距离按平方根压缩（长直线段缩短，道岔密集处相对拉开），再给每段跨行的区间留够画 45° 斜线的横向距离 （每次最多放大 {@value
     * #MAX_STRETCH} 倍，反复几轮），最后按宽度铺满。
     */
    private Map<String, Integer> columns(
        Map<String, Row> rowOf, Map<Row, Integer> rowIndex, int gap, Set<String> platformKeys) {
      passThrough = passThrough(platformKeys);
      List<Double> levels = new ArrayList<>();
      spots.keySet().stream()
          .filter(key -> !passThrough.contains(key))
          .map(uOf::get)
          .sorted()
          .forEach(
              u -> {
                if (levels.isEmpty() || u - levels.get(levels.size() - 1) > 0.5) {
                  levels.add(u);
                }
              });
      Map<String, Integer> levelOf = new HashMap<>();
      for (Spot spot : spots.values()) {
        if (passThrough.contains(spot.key())) {
          continue;
        }
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
      Map<Integer, List<String>> byLayer = new HashMap<>();
      for (String key : levelOf.keySet()) {
        byLayer
            .computeIfAbsent(rowIndex.get(rowOf.get(key)), ignored -> new ArrayList<>())
            .add(key);
      }
      List<int[]> rooms =
          new ArrayList<>(platformRooms(byLayer, rowOf, rowIndex, levelOf, platformKeys, gap));
      rooms.addAll(depotRooms(byLayer, rowOf, rowIndex, levelOf, levels.size()));
      double width = Math.max(1, rect.width() - 1);
      for (int pass = 0; pass < 6 && weights.length > 0; pass++) {
        double scale = width / sum(weights, 0, weights.length);
        boolean stretched = false;
        for (int[] room : rooms) {
          double have = scale * sum(weights, room[0], room[1]);
          double need = room[2];
          if (have + 0.5 < need) {
            double factor = Math.min(MAX_STRETCH, need / Math.max(have, 0.5));
            for (int i = room[0]; i < room[1]; i++) {
              weights[i] *= factor;
            }
            stretched = true;
          }
        }
        for (Link link : links) {
          if (passThrough.contains(link.a()) || passThrough.contains(link.b())) {
            continue;
          }
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
      // 收成面板的车库库门在最左或最右一列时，面板朝外伸，在那一侧留出面板的宽度；照画的库线尽头在那里时留出写库号的位置
      int reserveLeft = 0;
      int reserveRight = 0;
      Map<String, Integer> reserve = new HashMap<>();
      collapsed.keySet().forEach(door -> reserve.put(door, DEPOT_PANEL_WIDTH + 4));
      area.depotTracks().stream()
          .map(track -> track.node().value())
          .filter(levelOf::containsKey)
          .forEach(key -> reserve.merge(key, DEPOT_LABEL_ROOM, Math::max));
      for (Map.Entry<String, Integer> entry : reserve.entrySet()) {
        Integer level = levelOf.get(entry.getKey());
        if (level == null) {
          continue;
        }
        if (levels.size() > 1 && level == levels.size() - 1) {
          reserveRight = Math.max(reserveRight, entry.getValue());
        } else if (levels.size() > 1 && level == 0) {
          reserveLeft = Math.max(reserveLeft, entry.getValue());
        }
      }
      double usable = Math.max(1, width - reserveLeft - reserveRight);
      Map<String, Integer> xs = new HashMap<>();
      for (Spot spot : spots.values()) {
        if (passThrough.contains(spot.key())) {
          continue;
        }
        double fraction = total == 0.0 ? 0.5 : at[levelOf.get(spot.key())] / total;
        xs.put(spot.key(), (int) Math.round(rect.left() + reserveLeft + fraction * usable));
      }
      for (String key : passThrough) {
        xs.put(key, between(key, xs));
      }
      return xs;
    }

    /**
     * 经过点：链中间只连两条直股的节点（区间点、咽喉点等），站台股道、出口末端与车库面板的库门除外（库门要定面板的位置，两头都通库外时它也在链中间）。
     * 它们不画出来，不单独占一列，按里程插在链上前后两个占列的节点之间：长出入库线上的一串区间点因此不会把线拉长。
     */
    private Set<String> passThrough(Set<String> platformKeys) {
      Set<String> out = new HashSet<>();
      for (Chain chain : chains) {
        List<String> keys = chain.spots();
        for (int i = 1; i + 1 < keys.size(); i++) {
          String key = keys.get(i);
          if (around.getOrDefault(key, List.of()).size() == 2
              && !platformKeys.contains(key)
              && !exitKey(key)
              && !collapsed.containsKey(key)) {
            out.add(key);
          }
        }
      }
      return out;
    }

    /** 经过点的横坐标：按里程插在同一条链上前后最近的占列节点之间。 */
    private int between(String key, Map<String, Integer> xs) {
      List<String> keys = chainOf.get(key).spots();
      int index = keys.indexOf(key);
      int before = index - 1;
      while (passThrough.contains(keys.get(before))) {
        before--;
      }
      int after = index + 1;
      while (passThrough.contains(keys.get(after))) {
        after++;
      }
      double from = uOf.get(keys.get(before));
      double to = uOf.get(keys.get(after));
      double t =
          Math.abs(to - from) < 1e-6
              ? (double) (index - before) / (after - before)
              : Math.max(0.0, Math.min(1.0, (uOf.get(key) - from) / (to - from)));
      int x0 = xs.get(keys.get(before));
      int x1 = xs.get(keys.get(after));
      return (int) Math.round(x0 + t * (x1 - x0));
    }

    private static boolean exitKey(String key) {
      return key.startsWith(EXIT_PREFIX);
    }

    /** 这个节点岔出侧线：旁边要画一段斜线。 */
    private boolean branching(String key) {
      return around.getOrDefault(key, List.of()).size() >= 3;
    }

    /**
     * 站台节点与同一层左右最近的节点之间要留出的列区间 {低列, 高列, 要留的像素}：站台块以节点为中心，两边各要 {@value #PLATFORM_ROOM}
     * 像素，邻居是岔出侧线的道岔时再多留一个层距（斜线的宽）。
     *
     * @param byLayer 层 → 这一层上占列的节点
     */
    private List<int[]> platformRooms(
        Map<Integer, List<String>> byLayer,
        Map<String, Row> rowOf,
        Map<Row, Integer> rowIndex,
        Map<String, Integer> levelOf,
        Set<String> platformKeys,
        int gap) {
      List<int[]> rooms = new ArrayList<>();
      for (String key : platformKeys) {
        if (!levelOf.containsKey(key)) {
          continue;
        }
        int column = levelOf.get(key);
        String left = nearestOnLayer(byLayer, rowOf, rowIndex, levelOf, key, -1);
        String right = nearestOnLayer(byLayer, rowOf, rowIndex, levelOf, key, 1);
        if (left != null) {
          rooms.add(
              new int[] {levelOf.get(left), column, PLATFORM_ROOM + (branching(left) ? gap : 0)});
        }
        if (right != null) {
          rooms.add(
              new int[] {column, levelOf.get(right), PLATFORM_ROOM + (branching(right) ? gap : 0)});
        }
      }
      return rooms;
    }

    /**
     * 车库要留出的列区间 {低列, 高列, 要留的像素}：收成面板的车库，库门到朝外那一侧下一列之间放得下面板（任何一层上的节点都算，面板会盖住几层）；
     * 照画的车库，车挡尽头到同一层朝外那一侧最近的节点之间写得下库号。最左、最右一列另由 {@link #columns} 在图边留出。
     *
     * @param byLayer 层 → 这一层上占列的节点
     * @param levelCount 列数
     */
    private List<int[]> depotRooms(
        Map<Integer, List<String>> byLayer,
        Map<String, Row> rowOf,
        Map<Row, Integer> rowIndex,
        Map<String, Integer> levelOf,
        int levelCount) {
      List<int[]> rooms = new ArrayList<>();
      for (String door : collapsed.keySet()) {
        Integer column = levelOf.get(door);
        if (column == null) {
          continue;
        }
        int outward = outward(door);
        if (outward > 0 && column + 1 < levelCount) {
          rooms.add(new int[] {column, column + 1, DEPOT_PANEL_WIDTH + 8});
        } else if (outward < 0 && column > 0) {
          rooms.add(new int[] {column - 1, column, DEPOT_PANEL_WIDTH + 8});
        }
      }
      for (YardArea.Track track : area.depotTracks()) {
        String key = track.node().value();
        if (!levelOf.containsKey(key) || around.getOrDefault(key, List.of()).size() != 1) {
          continue;
        }
        int column = levelOf.get(key);
        int outward = outward(key);
        String other = nearestOnLayer(byLayer, rowOf, rowIndex, levelOf, key, outward);
        if (other != null) {
          int at = levelOf.get(other);
          rooms.add(new int[] {Math.min(column, at), Math.max(column, at), DEPOT_LABEL_ROOM});
        }
      }
      return rooms;
    }

    /** 节点朝哪边“往外”：连到它的节点平均在它左边（里程小）时为 1，否则为 -1。 */
    private int outward(String key) {
      double u = uOf.get(key);
      double neighbours =
          around.getOrDefault(key, List.of()).stream()
              .mapToDouble(link -> uOf.get(link.other(key)))
              .average()
              .orElse(u - 1.0);
      return neighbours <= u ? 1 : -1;
    }

    /** 同一层上 {@code key} 左边（{@code side} 为 -1）或右边最近的占列节点；没有时为空。 */
    private static String nearestOnLayer(
        Map<Integer, List<String>> byLayer,
        Map<String, Row> rowOf,
        Map<Row, Integer> rowIndex,
        Map<String, Integer> levelOf,
        String key,
        int side) {
      int column = levelOf.get(key);
      String best = null;
      for (String other : byLayer.getOrDefault(rowIndex.get(rowOf.get(key)), List.of())) {
        int at = levelOf.get(other);
        if (side < 0
            ? at < column && (best == null || at > levelOf.get(best))
            : at > column && (best == null || at < levelOf.get(best))) {
          best = other;
        }
      }
      return best;
    }

    /**
     * 跨行的区间依次试：斜线贴着分出侧股的一头（判断不出时放中间），不行就换另一头、中间；水平部分都不许顺着别的股道走（与同一层两端同行的区间重叠），都不行就直接连一条斜线。
     *
     * @param rails 各层上已占用的水平线 {左, 右, 连着的节点横坐标}（见 {@link #routes}）
     */
    private List<Point> route(
        Link link, Map<String, Point> points, Map<Integer, List<int[]>> rails) {
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
      int middle = (a.x() + b.x()) / 2;
      List<Point> atA = List.of(a, new Point(a.x() + step, b.y()), b);
      List<Point> atB = List.of(a, new Point(b.x() - step, a.y()), b);
      List<Point> inMiddle =
          List.of(
              a,
              new Point(middle - step / 2, a.y()),
              new Point(middle - step / 2 + step, b.y()),
              b);
      boolean fromA = branchesAt(link.a(), link);
      boolean fromB = branchesAt(link.b(), link);
      List<List<Point>> candidates =
          fromA && !fromB
              ? List.of(atA, atB, inMiddle)
              : fromB && !fromA ? List.of(atB, atA, inMiddle) : List.of(inMiddle, atA, atB);
      for (List<Point> candidate : candidates) {
        if (clearOf(candidate, rails)) {
          return candidate;
        }
      }
      return List.of(a, b);
    }

    /** 折线的水平部分不与同一层的直股水平线重叠（端点相接不算）。 */
    private static boolean clearOf(List<Point> route, Map<Integer, List<int[]>> rails) {
      for (int i = 0; i + 1 < route.size(); i++) {
        Point p = route.get(i);
        Point q = route.get(i + 1);
        if (p.y() != q.y()) {
          continue;
        }
        int low = Math.min(p.x(), q.x());
        int high = Math.max(p.x(), q.x());
        int node = joins(route, i);
        for (int[] rail : rails.getOrDefault(p.y(), List.of())) {
          if (Math.min(high, rail[1]) - Math.max(low, rail[0]) > 2
              && !(rail[2] != NO_JOIN && rail[2] == node)) {
            return false;
          }
        }
      }
      return true;
    }

    /**
     * 折线第 {@code i} 段（水平）连着的那一头节点的横坐标：跨层折线的水平部分总是一头连着区间的一端。两条斜线的水平部分连着同一个节点时 （V
     * 形道岔两条朝同一边离开的区间，两股并成一股），叠在一起画成 Y 形是本来的样子，不算叠线。
     */
    private static int joins(List<Point> route, int i) {
      return i == 0 ? route.get(0).x() : route.get(route.size() - 1).x();
    }

    /**
     * 各区间的折线：按区间先后依次排，每条跨层区间的水平部分避开同一层直股的水平线，也避开前面已排好的区间的水平部分； 只有两条斜线的水平部分连着同一个节点（在那里汇成 Y
     * 形）时可以叠在一起。
     */
    private Routed routes(Map<String, Point> points) {
      if (routed != null && routed.points() == points) {
        return routed;
      }
      Map<Integer, List<int[]>> taken = rails(points);
      Map<Link, List<Point>> out = new HashMap<>();
      Set<Link> blocked = new HashSet<>();
      for (Link link : links) {
        List<Point> route = route(link, points, taken);
        out.put(link, route);
        Point a = route.get(0);
        Point b = route.get(route.size() - 1);
        if (route.size() == 2
            && a.y() != b.y()
            && Math.abs(b.x() - a.x()) >= Math.abs(b.y() - a.y())) {
          blocked.add(link);
        }
        for (int i = 0; i + 1 < route.size(); i++) {
          Point p = route.get(i);
          Point q = route.get(i + 1);
          if (p.y() == q.y() && p.x() != q.x() && route.size() > 2) {
            taken
                .computeIfAbsent(p.y(), ignored -> new ArrayList<>())
                .add(new int[] {Math.min(p.x(), q.x()), Math.max(p.x(), q.x()), joins(route, i)});
          }
        }
      }
      routed = new Routed(points, out, blocked);
      return routed;
    }

    /** 各层上两端同行的区间画出的水平线 {左, 右, {@link #NO_JOIN}}：直股不让别的线靠着它汇合。 */
    private Map<Integer, List<int[]>> rails(Map<String, Point> points) {
      Map<Integer, List<int[]>> rails = new HashMap<>();
      for (Link link : links) {
        Point a = points.get(link.a());
        Point b = points.get(link.b());
        if (a.y() == b.y() && a.x() != b.x()) {
          rails
              .computeIfAbsent(a.y(), ignored -> new ArrayList<>())
              .add(new int[] {Math.min(a.x(), b.x()), Math.max(a.x(), b.x()), NO_JOIN});
        }
      }
      return rails;
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

    /**
     * 一处在脊线坐标下的位置。
     *
     * @param u 里程（含平移）
     * @param v 横向距离（含平移）
     * @param ownV 离本脊线的横向距离（不含平移）
     * @param dirX 最近处脊线走向 x
     * @param dirZ 最近处脊线走向 z
     */
    record Located(double u, double v, double ownV, double dirX, double dirZ) {}

    /** 只查一次最近的取样点，得出里程、横向距离与走向。 */
    Located locate(double x, double z) {
      int i = nearest(x, z);
      double ox = x - xs[i];
      double oz = z - zs[i];
      double lateral = -ox * dz[i] + oz * dx[i];
      return new Located(
          shiftU + chainage[i] + ox * dx[i] + oz * dz[i], shiftV + lateral, lateral, dx[i], dz[i]);
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
      Located at = reference.locate(xs[middle], zs[middle]);
      List<double[]> samples = new ArrayList<>();
      for (int i = 0; i < xs.length; i++) {
        samples.add(new double[] {xs[i], zs[i]});
      }
      return new Frame(samples, at.u() - chainage[middle], at.v());
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
