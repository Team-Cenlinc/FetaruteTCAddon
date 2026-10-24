package org.fetarute.fetaruteTCAddon.display.pids.yard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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
import java.util.PriorityQueue;
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
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.action.AutoStationDoorDirection;

/**
 * 站场图的几何：按信号楼示意图排布——股道画成水平的行，股道之间只用 45° 斜线相连，区间中间的弯道一律拉直。
 *
 * <ul>
 *   <li>节点：站场内的节点，加上每个出口的末端（出口区间只取离站场节点 {@value #STUB_BLOCKS} 格以内的一段）。
 *   <li>横坐标：沿线拉直。最长的一条直股连线作脊线（有线路分叉时取分叉来路一侧最长的一条，按来路自己的走向定头尾），按轨道长度展开成水平
 *       （东西走向的站东在右、北在上，南北走向的南在右、东在上），弯道与几个车站之间的拐弯都拉直；分叉岔出去的各股各用自己的脊线定里程（接着分叉处）与上下，
 *       和来路垂直的支线上的车站照样分得出股道上下与开门方向，各股整体按实地位置一上一下、不互相穿插。
 *       其余的链从接在已排好节点上的那一点起、顺着离开的方向排，按垂足定先后；垂足挪不动（斜着岔出去的支线）或挪得比轨道长得多（和脊线几乎垂直的库线）时
 *       改按轨道长度接着排，之后与脊线平行的区间再逐段收回到垂足上（复线的两条不因一段弯道整条错开）；经线路分叉接得上的链先接。两头都接在已排好的线上、从一头排出去会把另一头拉开
 *       {@value #BRIDGE_STRETCH_BLOCKS}
 *       格以上的链（三角线的联络线、迂回线）改在两头之间排：两头一路朝一个方向时途经车站的站台与咽喉对齐本站、其余按轨道长度均摊；两头离开的走向相反（U 形，
 *       例如联络线从两站靠分叉的一头出站）时按各点的垂足排成两段、在折返点断开成两行，站台留在本站，联络线从站台靠分叉的一头伸出去再拐过去，
 *       不从站台上横穿。互不相连的几片轨道各取各的脊线，从最长的一片起，依次按各自脊线中点在离它最近、已经摆好的那片脊线上的位置摆放。
 *       相邻两处的距离按平方根压缩，长直线段缩短、道岔密集的咽喉相对拉开，并保证每段跨行的区间有足够的横向距离画成 45°；链中间只连两段直股的经过点（区间点、咽喉点）
 *       不单独占位置，按里程插在前后两点之间。
 *   <li>行：道岔看各条区间离开它的走向，最接近一条直线的两条是直股，另一条是侧股，从道岔处拐出去；两条区间朝同一侧离开的节点没有直股。
 *       同一区间相邻两个区间点之间经过的道岔，按这条区间连成直股（几何上分不出时正线照线路走）。线路分叉不分主次：相隔不超过 {@value #JUNCTION_GAP_BLOCKS}
 *       格的几组道岔并成一群，往外各股按行车方向（不在道岔处折返）最先到的车站、车库或出口有三处以上不同时，
 *       来路那一行在群里结束，岔出去的各股（哪怕有一股在实地是直着走的）都在群外各起一行、从分叉处朝同一方向斜着分开，画成 Y 形；
 *       只是来路在区内、另外只有一处通往区内、其余是出口时不算分叉，两处之间经群里的道岔连成直股（接不上时仍算分叉），出口从旁边岔出。直股连成的一条链整条占一行，
 *       几个车站之间的同一条正线因此画成一条直线；落在同一条股道延长线上（横向偏差不到 {@value #SAME_ROW_BLOCKS} 格）的站台股道并为一行，分叉岔出去的链除外。
 *       各行按横向位置从上到下压进尽量少的层：横向范围不重叠的行共用一层，首尾相接的两行（各至少 {@value #ROW_SHARE_BLOCKS}
 *       格长）接在同一层；接上后画出的水平线与别的区间重叠时，这几行改为不接、重排。没连着上面哪一行的行再往下压到紧挨着下面与它重叠的一行。层距最多 {@value #ROW_GAP}
 *       像素，层多要压缩层距时上下各留出一块侧式站台的位置。
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

  /** 线路分叉处的道岔彼此相隔不超过多少格时算同一群（复线分叉常由几组道岔组成）。 */
  static final double JUNCTION_GAP_BLOCKS = 32.0;

  /** 链从一头接上后，另一头连着的已排节点被拉开超过多少格（扣掉区间本身的长度）时，改为在两头之间按轨道长度均摊（三角线的联络线）。 */
  static final double BRIDGE_STRETCH_BLOCKS = 64.0;

  /** 分叉岔出去的各股在横向上整体错开多少格：远大于任何一股自己的宽度，各股的行排层时不互相穿插。 */
  static final double BRANCH_SPACING_BLOCKS = 100_000.0;

  /** 出口区间取多长（格）。 */
  public static final double STUB_BLOCKS = 24.0;

  /** 行距上限（像素）：股道之间放得下一行车次窗。 */
  public static final int ROW_GAP = 32;

  /** 判断道岔直股、侧股时，沿每条区间看多远（格）：道岔叉开的角度小，太近分不出来。 */
  static final double BRANCH_PROBE_BLOCKS = 16.0;

  /** 同一去向的出口末端彼此横竖都在这么多像素以内时只标一次去向（从同一片咽喉岔出的另见 Layout#exitPieces）。 */
  static final int EXIT_LABEL_REACH = 3 * ROW_GAP;

  /** 上一个节点的里程与垂足错开、本段与脊线平行时往垂足收，但这一步至少要走这么多倍区间长（不挤到上一个节点上）。 */
  static final double MIN_CATCH_UP = 0.2;

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
  private final List<List<Point>> routes;
  private final Map<RailFootprintCell, Along> along;

  /**
   * 车厢方块落在哪条区间的折线上、从折线起点沿线走了多远（像素）。
   *
   * @param route {@link #routes} 里的下标
   * @param at 沿线走过的长度
   */
  private record Along(int route, double at) {}

  private YardGeometry(
      List<Path> paths,
      List<Platform> platforms,
      List<Label> exits,
      List<Depot> depots,
      Map<RailFootprintCell, Point> cells,
      boolean partial,
      List<Stub> stubs,
      Map<String, Point> nodes,
      List<List<Point>> routes,
      Map<RailFootprintCell, Along> along) {
    this.paths = List.copyOf(paths);
    this.platforms = List.copyOf(platforms);
    this.exits = List.copyOf(exits);
    this.depots = List.copyOf(depots);
    this.cells = Map.copyOf(cells);
    this.partial = partial;
    this.stubs = List.copyOf(stubs);
    this.nodes = Map.copyOf(nodes);
    this.routes = routes.stream().map(List::copyOf).toList();
    this.along = Map.copyOf(along);
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

  /**
   * 车身上两节相邻车厢之间沿画出的轨道走的折线（含两头）：在同一条区间上时取这条区间折线的那一段，在相接的两条区间上时经相接的那一头；
   * 其余情况（车库面板里的库线、两条区间不相接）直接连线。有一头不在图上时为空。
   */
  public List<Point> trace(RailFootprintCell from, RailFootprintCell to) {
    Point p = cells.get(from);
    Point q = cells.get(to);
    if (p == null || q == null) {
      return List.of();
    }
    Along a = along.get(from);
    Along b = along.get(to);
    if (a == null || b == null) {
      return List.of(p, q);
    }
    if (a.route() == b.route()) {
      List<Point> out = new ArrayList<>();
      out.add(p);
      out.addAll(corners(routes.get(a.route()), a.at(), b.at()));
      out.add(q);
      return out;
    }
    List<Point> ra = routes.get(a.route());
    List<Point> rb = routes.get(b.route());
    List<Point> best = null;
    double shortest = Double.MAX_VALUE;
    for (int ea : new int[] {0, ra.size() - 1}) {
      for (int eb : new int[] {0, rb.size() - 1}) {
        if (!ra.get(ea).equals(rb.get(eb))) {
          continue;
        }
        double endA = ea == 0 ? 0.0 : polylineLength(ra);
        double endB = eb == 0 ? 0.0 : polylineLength(rb);
        double walked = Math.abs(a.at() - endA) + Math.abs(b.at() - endB);
        if (walked < shortest) {
          shortest = walked;
          best = new ArrayList<>();
          best.add(p);
          best.addAll(corners(ra, a.at(), endA));
          best.add(ra.get(ea));
          best.addAll(corners(rb, endB, b.at()));
          best.add(q);
        }
      }
    }
    return best == null ? List.of(p, q) : best;
  }

  /** 折线上沿线位置在 {@code from} 与 {@code to} 之间（不含两头）的拐点，按从 {@code from} 走到 {@code to} 的先后。 */
  private static List<Point> corners(List<Point> route, double from, double to) {
    List<Point> out = new ArrayList<>();
    double walked = 0.0;
    for (int i = 1; i + 1 < route.size(); i++) {
      walked += distance(route.get(i - 1), route.get(i));
      if (walked > Math.min(from, to) && walked < Math.max(from, to)) {
        out.add(route.get(i));
      }
    }
    if (from > to) {
      Collections.reverse(out);
    }
    return out;
  }

  /** 折线全长（像素）。 */
  private static double polylineLength(List<Point> route) {
    double total = 0.0;
    for (int i = 1; i < route.size(); i++) {
      total += distance(route.get(i - 1), route.get(i));
    }
    return total;
  }

  private static double distance(Point a, Point b) {
    return Math.hypot(b.x() - a.x(), b.y() - a.y());
  }

  /** 折线上沿线走了 {@code at} 像素处的点。 */
  private static Point pointAlong(List<Point> route, double at) {
    double walked = 0.0;
    for (int i = 1; i < route.size(); i++) {
      Point p = route.get(i - 1);
      Point q = route.get(i);
      double step = distance(p, q);
      if (step > 0.0 && walked + step >= at) {
        double t = Math.max(0.0, Math.min(1.0, (at - walked) / step));
        return new Point(
            (int) Math.round(p.x() + t * (q.x() - p.x())),
            (int) Math.round(p.y() + t * (q.y() - p.y())));
      }
      walked += step;
    }
    return route.get(route.size() - 1);
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
      Set<RailFootprintCell> cells = sortedCells(footprints.getOrDefault(edge, Set.of()));
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
      for (RailFootprintCell cell : sortedCells(footprints.getOrDefault(exit.edge(), Set.of()))) {
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
    // 分叉判定用：节点属于哪个车站或车库、出口通往哪里（站台、咽喉、库线与库内咽喉，出口末端）
    Map<String, String> endOf = new HashMap<>();
    for (NodeId id : area.nodes()) {
      graph
          .findNode(id)
          .filter(node -> node.type() != NodeType.SWITCHER)
          .flatMap(RailNode::waypointMetadata)
          .ifPresent(
              m -> {
                String code = m.originStation().toUpperCase(Locale.ROOT);
                switch (m.kind()) {
                  case STATION, STATION_THROAT -> endOf.put(id.value(), "S:" + code);
                  case DEPOT, DEPOT_THROAT -> endOf.put(id.value(), "D:" + code);
                  default -> {}
                }
                if (m.kind() == WaypointKind.DEPOT_THROAT) {
                  throatOf.put(id.value(), code);
                }
              });
    }
    // 出口按通往的车站算一处：复线的两条出口去同一站，咽喉不因此被当成线路分叉；认不出去向的各算各的
    exitOf.forEach((key, exit) -> endOf.put(key, "X:" + exit.towards().orElse(key)));
    // 区间点（含出口外端那一个）在哪条区间上、第几个：排直股时按区间连起相邻的两个
    Map<String, IntervalMark> intervals = new LinkedHashMap<>();
    for (String key : spots.keySet()) {
      YardArea.Exit exit = exitOf.get(key);
      NodeId id = exit == null ? NodeId.of(key) : exit.outer();
      graph
          .findNode(id)
          .filter(node -> node.type() != NodeType.SWITCHER)
          .flatMap(RailNode::waypointMetadata)
          .flatMap(IntervalMark::of)
          .ifPresent(mark -> intervals.put(key, mark));
    }
    return new Layout(spots, links, rect, doors, railAt, area, throatOf, endOf, intervals)
        .build(area, exitOf, stationName, partial);
  }

  /**
   * 区间点在区间上的位置。
   *
   * @param route 区间（运营商:起点:终点，大写）
   * @param track 股道号
   * @param sequence 序号
   */
  private record IntervalMark(String route, int track, int sequence) {

    static Optional<IntervalMark> of(WaypointMetadata metadata) {
      if (metadata.kind() != WaypointKind.INTERVAL || metadata.sequence().isEmpty()) {
        return Optional.empty();
      }
      try {
        int sequence = Integer.parseInt(metadata.sequence().get().trim());
        String route =
            (metadata.operator()
                    + ":"
                    + metadata.originStation()
                    + ":"
                    + metadata.destinationStation().orElse(""))
                .toUpperCase(Locale.ROOT);
        return Optional.of(new IntervalMark(route, metadata.trackNumber(), sequence));
      } catch (NumberFormatException ex) {
        return Optional.empty();
      }
    }
  }

  /** 轨道方块按坐标排好：调度图给的集合遍历次序每次启动都可能不同，按它求和、排序会在平局处翻来翻去，同一张图画出不一样的站场。排布里遍历方块一律经过这里。 */
  private static Set<RailFootprintCell> sortedCells(Set<RailFootprintCell> cells) {
    return Collections.unmodifiableSet(new LinkedHashSet<>(cells.stream().sorted().toList()));
  }

  /** 保持给定次序的不可变集合（{@link Set#copyOf} 的遍历次序每次启动都不同，排布不能用它）。 */
  private static <T> Set<T> ordered(Collection<T> items) {
    return Collections.unmodifiableSet(new LinkedHashSet<>(items));
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
    private final Map<String, String> endOf;
    private final Map<String, IntervalMark> intervals;
    private final Map<Link, Double> lengths = new HashMap<>();
    private final Map<String, Junction> junctionOf = new HashMap<>();
    // 各个分叉按找到的先后（遍历分叉一律用它，不用 junctionOf 的值集合）
    private final List<Junction> junctions = new ArrayList<>();
    // 按进路定下的直股，优先于按几何判的（先后见构造函数）
    private final Map<String, Set<Link>> forcedThrough = new HashMap<>();
    // 节点 → 量它的垂足用的那条脊线（分叉岔出去的各股各有一条）
    private final Map<String, Frame> frameOf = new HashMap<>();
    // U 形的链在折返点断开成两行（见 turnBack）
    private final Set<Link> rowBreaks = new HashSet<>();
    // 断开处那条区间：折返在里程小的一侧（true）还是大的一侧，折返点，实地折返最远处的里程（见 turnExtents）
    private final Map<Link, Boolean> turnLow = new HashMap<>();
    private final Map<Link, String> turnNode = new HashMap<>();
    private final Map<Link, Double> turnExtent = new HashMap<>();
    // 最近一次排出的各列：里程与横坐标（见 columns），任意里程据此换算横坐标
    private double[] columnU = new double[0];
    private double[] columnX = new double[0];

    /**
     * @param spots 节点（可改：收成面板的车库在这里换成一个库门点）
     * @param links 区间（同上）
     * @param doors 站台股道节点 → AutoStation 牌子写的开门方向（只含写了方向的）
     * @param railAt 轨道方块 → 经过它的区间（全图）；查不到时为空集合
     * @param area 站场
     * @param throatOf 车库咽喉节点 → 所属车库代码
     * @param endOf 节点 → 它属于的车站（{@code S:站码}）、车库（{@code D:库码}）或出口（{@code X:出口}），分叉判定用
     * @param intervals 区间点（出口末端按它外端的区间点）→ 在区间上的位置
     */
    Layout(
        Map<String, Spot> spots,
        List<Link> links,
        Rect rect,
        Map<String, AutoStationDoorDirection> doors,
        Function<RailFootprintCell, Set<EdgeId>> railAt,
        YardArea area,
        Map<String, String> throatOf,
        Map<String, String> endOf,
        Map<String, IntervalMark> intervals) {
      this.spots = spots;
      this.links = links;
      this.rect = rect;
      this.doors = doors;
      this.railAt = railAt;
      this.area = area;
      this.throatOf = throatOf;
      this.endOf = new HashMap<>(endOf);
      this.intervals = intervals;
      index();
      this.collapsed = collapse();
      index();
      this.collapsed.keySet().forEach(key -> this.endOf.put(key, "D:" + key));
      // 直股按优先级定，后一级只补前一级没定的节点：
      // 1. 道岔群里区内两处之间的进路（findJunctions 里的 routeThrough）；
      // 2. 同一区间相邻两个区间点之间的进路（followIntervals，跳过分叉群与第 1 级定过的节点）；
      // 3. 其余按道岔几何（throughLinks），分叉群里岔出去的各股不当直股
      findJunctions();
      followIntervals();
      for (Spot spot : spots.values()) {
        Junction junction = junctionOf.get(spot.key());
        List<Link> mine = around.getOrDefault(spot.key(), List.of());
        // 分叉处岔出去的各股不当直股：来路连进群里，岔出去的各股在群外各起一行
        through.put(
            spot.key(),
            forcedThrough.containsKey(spot.key())
                ? forcedThrough.get(spot.key())
                : throughLinks(
                    spot,
                    junction == null
                        ? mine
                        : mine.stream()
                            .filter(link -> !junction.branches().contains(link))
                            .toList()));
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
        Map<String, Frame> branchFrames = branchFrames(part, frame);
        for (Chain chain : part) {
          for (String key : chain.spots()) {
            Spot spot = spots.get(key);
            Frame local = branchFrames.getOrDefault(key, frame);
            Frame.Located at = local.locate(spot.x(), spot.z());
            frameOf.put(key, local);
            located.put(key, at);
            vOf.put(key, at.v());
          }
        }
        placeChains(part);
        turnExtents(key -> branchFrames.getOrDefault(key, frame));
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
      Chain spine = spine(part);
      pending.remove(spine);
      place(spine);
      boolean progress = true;
      while (!pending.isEmpty() && progress) {
        progress = false;
        // 经分叉群接得上的链先接：岔出去的各股要从分叉处顺着排，不能先被车站另一头的渡线带着反过来
        for (java.util.Iterator<Chain> it = pending.iterator(); it.hasNext(); ) {
          if (attach(it.next(), true)) {
            it.remove();
            progress = true;
          }
        }
        if (progress) {
          continue;
        }
        for (java.util.Iterator<Chain> it = pending.iterator(); it.hasNext(); ) {
          if (attach(it.next(), false)) {
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
     *
     * @param viaJunction 只经一头在分叉群里的区间接
     */
    private boolean attach(Chain chain, boolean viaJunction) {
      List<String> keys = chain.spots();
      for (int index = 0; index < keys.size(); index++) {
        String key = keys.get(index);
        for (Link link : around.getOrDefault(key, List.of())) {
          String from = link.other(key);
          if (chain.links().contains(link)
              || !uOf.containsKey(from)
              || (viaJunction && !junctionOf.containsKey(key) && !junctionOf.containsKey(from))) {
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
          bridge(chain, index);
          return true;
        }
      }
      return false;
    }

    /**
     * 链从第 {@code index} 个节点接上后，若链上另一个节点也经区间连着已排好的节点、却被拉开了 {@value #BRIDGE_STRETCH_BLOCKS}
     * 格以上（两头各连一条已排好的线，例如三角线里两条支线之间的联络线），就把那个节点从它那头接过来，两者之间按 {@link #spread}（一路朝一个方向） 或 {@link
     * #turnBack}（U 形）排，再往外的节点顺着两头那一段的方向接着排。
     */
    private void bridge(Chain chain, int index) {
      List<String> keys = chain.spots();
      Set<String> mine = Set.copyOf(keys);
      int far = -1;
      Link farLink = null;
      double worst = BRIDGE_STRETCH_BLOCKS;
      for (int j = 0; j < keys.size(); j++) {
        if (j == index) {
          continue;
        }
        for (Link link : around.getOrDefault(keys.get(j), List.of())) {
          String other = link.other(keys.get(j));
          if (mine.contains(other) || !uOf.containsKey(other)) {
            continue;
          }
          double stretch = Math.abs(uOf.get(keys.get(j)) - uOf.get(other)) - length(link);
          if (stretch > worst) {
            worst = stretch;
            far = j;
            farLink = link;
          }
        }
      }
      if (far < 0) {
        return;
      }
      String anchor = farLink.other(keys.get(far));
      next(anchor, keys.get(far), farLink, leaving(anchor, farLink));
      int step = far > index ? 1 : -1;
      // 两头离开的走向（按垂足）相反时是 U 形（例如联络线从两站靠分叉的一头出站）：往回走到折返点再走到另一头
      // 走向只按垂足比：排布里程可能按轨道长度排过，与垂足差出十几格，混着比会把一路朝前的链误判成 U 形
      double out = located.get(keys.get(index + step)).u() - located.get(keys.get(index)).u();
      double in = located.get(keys.get(far)).u() - located.get(keys.get(far - step)).u();
      if (index + step != far && out * in < 0) {
        turnBack(chain, index, far, step, out < 0);
      } else {
        spread(chain, index, far, step);
      }
      double first = uOf.get(keys.get(index + step)) - uOf.get(keys.get(index));
      double last = uOf.get(keys.get(far)) - uOf.get(keys.get(far - step));
      for (int i = far + step; i >= 0 && i < keys.size(); i += step) {
        next(
            keys.get(i - step),
            keys.get(i),
            chain.links().get(step > 0 ? i - 1 : i),
            last >= 0 ? 1.0 : -1.0);
      }
      for (int i = index - step; i >= 0 && i < keys.size(); i -= step) {
        next(
            keys.get(i + step),
            keys.get(i),
            chain.links().get(step > 0 ? i : i - 1),
            first >= 0 ? -1.0 : 1.0);
      }
    }

    /** 两头之间一路朝一个方向走的链：车站的站台与咽喉节点取垂足（与同站别的股道对齐），其余节点在相邻两个定好的节点之间按轨道长度均摊。 */
    private void spread(Chain chain, int index, int far, int step) {
      List<String> keys = chain.spots();
      List<Integer> fixed = new ArrayList<>(List.of(index));
      for (int i = index + step; i != far; i += step) {
        if (endOf.getOrDefault(keys.get(i), "").startsWith("S:")) {
          uOf.put(keys.get(i), located.get(keys.get(i)).u());
          fixed.add(i);
        }
      }
      fixed.add(far);
      for (int f = 0; f + 1 < fixed.size(); f++) {
        int from = fixed.get(f);
        int to = fixed.get(f + 1);
        double total = 0.0;
        for (int i = from; i != to; i += step) {
          total += length(chain.links().get(step > 0 ? i : i - 1));
        }
        double start = uOf.get(keys.get(from));
        double end = uOf.get(keys.get(to));
        double walked = 0.0;
        for (int i = from + step; i != to; i += step) {
          walked += length(chain.links().get(step > 0 ? i - 1 : i));
          uOf.put(keys.get(i), start + (end - start) * walked / Math.max(1e-6, total));
        }
      }
    }

    /**
     * U 形的链：两头之间垂足最靠外（{@code low} 时最小）的节点是折返点；从 {@code index} 到折返点、从折返点到 {@code far} 各按垂足排，
     * 往回挪的垂足压平，各自一路朝一个方向。两段各占一行，在折返点旁边落差大的那条区间处断开：两头的站台各留在本站那几层，
     * 一段从站台靠分叉的一头往外伸到折返点，两段之间那条区间在两头外侧竖着连（见 {@link #turn}），不从站台上横穿过去。
     */
    private void turnBack(Chain chain, int index, int far, int step, boolean low) {
      List<String> keys = chain.spots();
      int turn = index + step;
      for (int i = index + step; i != far; i += step) {
        double at = located.get(keys.get(i)).u();
        double best = located.get(keys.get(turn)).u();
        if (low ? at < best : at > best) {
          turn = i;
        }
      }
      // 压平按垂足做，再整体平移到这一头的排布里程上：头上的节点可能按轨道长度排过，直接拿排布里程与垂足取最值会把半段节点压到同一列
      double u = located.get(keys.get(index)).u();
      double shift = uOf.get(keys.get(index)) - u;
      for (int i = index + step; ; i += step) {
        double at = located.get(keys.get(i)).u();
        u = low ? Math.min(u, at) : Math.max(u, at);
        uOf.put(keys.get(i), u + shift);
        if (i == turn) {
          break;
        }
      }
      u = located.get(keys.get(far)).u();
      shift = uOf.get(keys.get(far)) - u;
      for (int i = far - step; i != turn; i -= step) {
        double at = located.get(keys.get(i)).u();
        u = low ? Math.min(u, at) : Math.max(u, at);
        uOf.put(keys.get(i), u + shift);
      }
      double before = Math.abs(uOf.get(keys.get(turn)) - uOf.get(keys.get(turn - step)));
      double after = Math.abs(uOf.get(keys.get(turn + step)) - uOf.get(keys.get(turn)));
      Link broken =
          chain
              .links()
              .get(before >= after ? Math.min(turn - step, turn) : Math.min(turn, turn + step));
      rowBreaks.add(broken);
      turnLow.put(broken, low);
      turnNode.put(broken, keys.get(turn));
    }

    /**
     * U 形断开处那条区间实地折返到多远：轨道方块投到折返点所在的局部坐标上，取最靠外（{@link #turnLow} 时最小）的里程；不比折返点更靠里。
     *
     * @param frameOf 节点 → 它所在那股的局部坐标
     */
    private void turnExtents(Function<String, Frame> frameOf) {
      for (Link link : rowBreaks) {
        if (turnExtent.containsKey(link)) {
          continue;
        }
        String node = turnNode.get(link);
        boolean low = turnLow.get(link);
        Frame frame = frameOf.apply(node);
        // 方块的垂足只与折返点的垂足比，再平移到折返点的排布里程上
        double extent = located.get(node).u();
        for (RailFootprintCell cell : link.cells()) {
          double[] c = center(cell);
          double u = frame.locate(c[0], c[1]).u();
          extent = low ? Math.min(extent, u) : Math.max(extent, u);
        }
        turnExtent.put(link, extent + uOf.get(node) - located.get(node).u());
      }
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

    /** 一片轨道的脊线：有线路分叉时取分叉来路那一侧最长的一条链（各股从分叉处朝同一方向分开，来路定整片的走向，车站因此按实地先后排开），否则取最长的一条链。 */
    private Chain spine(List<Chain> chains) {
      return stemOf(chains)
          .orElseGet(
              () ->
                  chains.stream()
                      .max(Comparator.comparingDouble(Chain::length))
                      .orElse(new Chain(List.of(), List.of(), 0.0)));
    }

    /** 这片轨道里线路分叉来路那一侧最长的一条链；没有分叉时为空。 */
    private Optional<Chain> stemOf(List<Chain> chains) {
      Set<Chain> mine = new HashSet<>(chains);
      Set<Chain> stems = new HashSet<>();
      for (Junction junction : junctions) {
        for (Link link : junction.stem()) {
          Chain chain = chainOf.get(outer(junction, link));
          if (mine.contains(chain)) {
            stems.add(chain);
          }
        }
      }
      // 上下行两条来路一样长时按起头节点取，画面不随集合次序变
      return stems.stream()
          .max(
              Comparator.comparingDouble(Chain::length)
                  .thenComparing(chain -> chain.spots().get(0), Comparator.reverseOrder()));
    }

    /**
     * 一片轨道的脊线（见 {@link #spine}），沿轨道取样；按这片节点的主方向定头尾（东西走向东在右，南北走向南在右），脊线是分叉来路时按来路自己的走向定。
     * 只有孤立节点时退回一条沿主方向的直线。
     */
    private Frame frame(List<Chain> chains) {
      List<Spot> members =
          chains.stream().flatMap(chain -> chain.spots().stream()).map(spots::get).toList();
      Optional<Chain> stem = stemOf(chains);
      Chain spine = stem.orElseGet(() -> spine(chains));
      List<Spot> heading =
          stem.isPresent() ? spine.spots().stream().map(spots::get).toList() : members;
      double angle =
          principalAngle(heading.stream().map(s -> new double[] {s.x(), s.z()}).toList());
      double[] axis = {Math.cos(angle), Math.sin(angle)};
      List<double[]> kept = samples(spine);
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

    /** 沿一条链的轨道取样，相邻两点至少相隔 {@value #SPINE_SAMPLE_BLOCKS} 格。 */
    private List<double[]> samples(Chain chain) {
      List<double[]> samples = new ArrayList<>();
      for (int i = 0; i < chain.links().size(); i++) {
        List<double[]> traced = trace(chain.links().get(i), chain.spots().get(i));
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
      return kept;
    }

    /**
     * 脊线是分叉来路时，岔出去的各股各用一条自己的脊线：每个节点归到沿轨道最近的那一股（来路一侧的仍用整片的脊线），那一股里最长的一条链作它的脊线，
     * 里程从分叉处接着整片的里程往外排，横向距离按这一股自己的走向量——岔出去的线和来路几乎垂直时（例如往东拐的支线接在南北向的来路上），
     * 车站的几条股道照样分得出上下，开门方向也按这一股的走向判断。各股按在整片脊线上的横向位置排先后，来路夹在中间（两股时一上一下），各股的行不互相穿插。
     *
     * @return 节点 → 它所在那一股的脊线；没有分叉、或分叉不在脊线上时为空
     */
    private Map<String, Frame> branchFrames(List<Chain> part, Frame main) {
      Optional<Junction> found =
          stemOf(part)
              .flatMap(
                  stem ->
                      junctions.stream()
                          .filter(
                              candidate ->
                                  candidate.stem().stream()
                                      .anyMatch(
                                          link -> chainOf.get(outer(candidate, link)) == stem))
                          .findFirst());
      if (found.isEmpty()) {
        return Map.of();
      }
      Junction junction = found.get();
      // 各节点归到沿轨道最近的那一股：来路记 -1，岔出去的第 k 股记 k
      record Visit(String key, double distance, int group) {}
      PriorityQueue<Visit> queue = new PriorityQueue<>(Comparator.comparingDouble(Visit::distance));
      Map<String, Double> best = new HashMap<>();
      Map<String, Integer> groupOf = new HashMap<>();
      List<Set<Link>> legs = new ArrayList<>(junction.groups());
      for (int k = -1; k < legs.size(); k++) {
        for (Link link : k < 0 ? junction.stem() : legs.get(k)) {
          queue.add(new Visit(outer(junction, link), length(link), k));
        }
      }
      while (!queue.isEmpty()) {
        Visit visit = queue.poll();
        if (visit.distance() >= best.getOrDefault(visit.key(), Double.MAX_VALUE)) {
          continue;
        }
        best.put(visit.key(), visit.distance());
        groupOf.put(visit.key(), visit.group());
        for (Link link : around.getOrDefault(visit.key(), List.of())) {
          String next = link.other(visit.key());
          if (!junction.nodes().contains(next)) {
            queue.add(new Visit(next, visit.distance() + length(link), visit.group()));
          }
        }
      }
      double cx =
          junction.nodes().stream().mapToDouble(key -> spots.get(key).x()).average().orElse(0);
      double cz =
          junction.nodes().stream().mapToDouble(key -> spots.get(key).z()).average().orElse(0);
      double atJunction = main.locate(cx, cz).u();
      double[] middle = main.middle();
      double flow = atJunction >= main.locate(middle[0], middle[1]).u() ? 1.0 : -1.0;
      // 各股按在整片脊线上的平均横向位置排先后
      List<Integer> order = new ArrayList<>();
      Map<Integer, Double> across = new HashMap<>();
      for (int k = 0; k < legs.size(); k++) {
        int group = k;
        double v =
            groupOf.entrySet().stream()
                .filter(entry -> entry.getValue() == group)
                .mapToDouble(
                    entry ->
                        main.locate(spots.get(entry.getKey()).x(), spots.get(entry.getKey()).z())
                            .v())
                .average()
                .orElse(0.0);
        across.put(k, v);
        order.add(k);
      }
      order.sort(Comparator.comparingDouble(across::get));
      Map<String, Frame> out = new HashMap<>();
      for (int rank = 0; rank < order.size(); rank++) {
        int group = order.get(rank);
        Optional<Chain> longest =
            part.stream()
                .filter(
                    chain ->
                        chain.spots().stream()
                            .allMatch(key -> groupOf.getOrDefault(key, -1) == group))
                .max(Comparator.comparingDouble(Chain::length));
        if (longest.isEmpty()) {
          continue;
        }
        List<double[]> kept = new ArrayList<>(samples(longest.get()));
        if (kept.size() < 2) {
          continue;
        }
        // 取样按整片里程增大的方向排：顺流时从分叉一头起，逆流时到分叉一头止
        double nearFirst = Math.hypot(kept.get(0)[0] - cx, kept.get(0)[1] - cz);
        double nearLast =
            Math.hypot(kept.get(kept.size() - 1)[0] - cx, kept.get(kept.size() - 1)[1] - cz);
        if ((flow > 0) != (nearFirst <= nearLast)) {
          java.util.Collections.reverse(kept);
        }
        // 里程在这一股伸出分叉的几处与整片脊线对齐（分叉附近两者差不多），这一股的里程因此紧接着来路
        Frame local = new Frame(kept);
        double shift =
            legs.get(group).stream()
                .map(link -> spots.get(outer(junction, link)))
                .mapToDouble(
                    at -> main.locate(at.x(), at.z()).u() - local.locate(at.x(), at.z()).u())
                .average()
                .orElse(0.0);
        Frame frame =
            new Frame(kept, shift, (rank - (order.size() - 1) / 2.0) * BRANCH_SPACING_BLOCKS);
        groupOf.forEach(
            (key, k) -> {
              if (k == group) {
                out.put(key, frame);
              }
            });
      }
      return out;
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
     *
     * <p>上一个节点在弯道里按轨道长度排过、与它的垂足错开时，后面与脊线平行的区间（垂足自己朝前挪了半个到一个半区间长）逐段把这点落差收回来： 每段走的里程夹在 {@value
     * #MIN_CATCH_UP} 到 {@value #MAX_PROJECTED_STEP} 倍区间长之间、尽量落到垂足上，
     * 不把落差一路带下去（否则复线的另一条整条错开，两站之间的渡线被拉歪），也不往回退、不挤到上一个节点上。
     */
    private void next(String from, String to, Link link, double sign) {
      double before = uOf.get(from);
      double projected = located.get(to).u();
      double length = Math.max(1.0, trackLength(link));
      double moved = sign * (projected - before);
      // 与脊线平行与否只在两头量垂足用的是同一条脊线时才比（分叉处两头可能分属来路与支股两条脊线，两套里程不能相减）
      boolean sameFrame = frameOf.get(from) == frameOf.get(to);
      double parallel = sign * (projected - located.get(from).u());
      double step;
      if (moved >= 0.5 * length && moved <= MAX_PROJECTED_STEP * length) {
        step = moved;
      } else if (sameFrame && parallel >= 0.5 * length && parallel <= MAX_PROJECTED_STEP * length) {
        step = Math.max(MIN_CATCH_UP * length, Math.min(MAX_PROJECTED_STEP * length, moved));
      } else {
        step = length;
      }
      uOf.put(to, before + sign * step);
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
      // 分叉岔出去的几行不与来路首尾相接成一层（画成 Y 形，几股平级）
      Set<Row> separate = new HashSet<>();
      for (Junction junction : junctions) {
        for (Link link : junction.branches()) {
          separate.add(rows.get(outer(junction, link)));
        }
      }
      // 首尾相接的行接在同一层若画出重叠的水平线（道岔群里常见），这些行改为不接、重排；最多试三次
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
      List<List<Point>> drawn = new ArrayList<>();
      Map<RailFootprintCell, Along> along = new HashMap<>();
      Map<Integer, List<Span>> spans = new HashMap<>();
      List<Segment> diagonals = new ArrayList<>();
      Map<Link, List<Point>> routes = routes(points).routes();
      for (Link link : links) {
        List<Point> route = routes.get(link);
        paths.add(new Path(link.edge(), segments(route)));
        placeCells(link, route, drawn.size(), cells, along);
        drawn.add(route);
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
      // 同一去向的几条出口只标一次：挨在一起的（复线两条），或从同一片咽喉岔出的（几条去同一处的线从同一组道岔分出去）；
      // 离得远、又不在同一片咽喉的（同一车站从两处出区）各标各的
      Map<String, String> pieceOf = exitPieces(exitOf);
      record Labelled(Label label, String piece) {}
      List<Labelled> labelled = new ArrayList<>();
      exitOf.forEach(
          (key, exit) ->
              exit.towards()
                  .ifPresent(
                      code -> {
                        Point at = points.get(key);
                        String text = stationName.apply(code);
                        boolean right = at.x() > middle;
                        String piece = pieceOf.get(key);
                        boolean shown =
                            labelled.stream()
                                .anyMatch(
                                    other ->
                                        other.label().text().equals(text)
                                            && ((other.label().alignRight() == right
                                                    && near(other.label().at(), at))
                                                || piece.equals(other.piece())));
                        if (!shown) {
                          labelled.add(
                              new Labelled(new Label(at, text, right, Optional.of(code)), piece));
                        }
                      }));
      List<Label> exits = labelled.stream().map(Labelled::label).toList();
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
          paths, platforms, List.copyOf(exits), depots, cells, partial, stubs, nodes, drawn, along);
    }

    private static boolean near(Point a, Point b) {
      return Math.abs(a.x() - b.x()) <= EXIT_LABEL_REACH
          && Math.abs(a.y() - b.y()) <= EXIT_LABEL_REACH;
    }

    /**
     * 出口所在的咽喉分片：经道岔、区间点连着（不经车站、车库）的节点算一片，出口归到它在区内那一头所在的一片；几条出口通到区外同一个节点（例如一个区间点既接着站台端、
     * 又接着站外的道岔）时也算一片。
     *
     * @return 出口末端的键 → 所在那一片的代表
     */
    private Map<String, String> exitPieces(Map<String, YardArea.Exit> exitOf) {
      Map<String, String> parent = new HashMap<>();
      for (String key : spots.keySet()) {
        if (!endOf.containsKey(key)) {
          parent.put(key, key);
        }
      }
      for (Link link : links) {
        if (parent.containsKey(link.a()) && parent.containsKey(link.b())) {
          union(parent, link.a(), link.b());
        }
      }
      exitOf.forEach(
          (key, exit) -> {
            String outer = "outer:" + exit.outer().value();
            parent.putIfAbsent(outer, outer);
            if (parent.containsKey(exit.inner().value())) {
              union(parent, exit.inner().value(), outer);
            }
          });
      Map<String, String> out = new HashMap<>();
      exitOf.forEach((key, exit) -> out.put(key, root(parent, "outer:" + exit.outer().value())));
      return out;
    }

    private static void union(Map<String, String> parent, String first, String second) {
      String a = root(parent, first);
      String b = root(parent, second);
      if (!a.equals(b)) {
        parent.put(a.compareTo(b) < 0 ? b : a, a.compareTo(b) < 0 ? a : b);
      }
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
     * 格才当作岛式；侧式站台放在放得下的一侧，两侧都放得下时放在离邻线更远的一侧（图边算无限远）；相邻两条按这个规则都要放进它俩之间的同一条缝时，也当作岛式。
     * 两侧都开门的股道可以同时是上下两块岛式站台的一边（例如三条股道夹着两块岛式站台）。
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
        Optional<Span> down = neighbour(at, spans, 1);
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
                        .filter(
                            other -> island(key, other) || facing(key, other, points, spans, rowOf))
                        .findFirst());
        if (partner.isPresent()) {
          int[] other = extent(partner.get(), points);
          islandBelow.add(partner.get());
          int top = at.y() + TRACK_LINE + PLATFORM_MARGIN;
          int bottom = points.get(partner.get()).y() - PLATFORM_MARGIN;
          // 岛式站台取两条股道的并集，但与邻站站台的中线分界是硬边界，哪一行的都不越过
          int left = Math.min(range[0], other[0]);
          int right = Math.max(range[1], other[1]);
          for (int[] bound : List.of(range, other)) {
            if (bound[2] != 0) {
              left = Math.max(left, bound[0]);
            }
            if (bound[3] != 0) {
              right = Math.min(right, bound[1]);
            }
          }
          int[] box = clear(new int[] {left, right}, top, bottom, at.x(), diagonals);
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
        boolean above = sideAbove(key, points, spans, rowOf);
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

    /** 侧式站台画在股道上方：写了开门方向的画在开门那一侧；没写的放在放得下的一侧，两侧都放得下时放在离邻线更远的一侧（图边算无限远）。 */
    private boolean sideAbove(
        String key,
        Map<String, Point> points,
        Map<Integer, List<Span>> spans,
        Map<String, Row> rowOf) {
      Point at = points.get(key);
      double v = rowOf.get(key).v();
      double upGap =
          neighbour(at, spans, -1)
              .map(span -> Math.abs(v - span.row().v()))
              .orElse(Double.MAX_VALUE);
      double downGap =
          neighbour(at, spans, 1)
              .map(span -> Math.abs(v - span.row().v()))
              .orElse(Double.MAX_VALUE);
      Optional<Boolean> opensDown =
          doors.get(key) == AutoStationDoorDirection.BOTH ? Optional.empty() : opens(key, 1);
      return opensDown
          .map(downward -> !downward)
          .orElseGet(
              () ->
                  upGap >= MIN_PLATFORM_BLOCKS && downGap >= MIN_PLATFORM_BLOCKS
                      ? upGap >= downGap
                      : upGap >= MIN_PLATFORM_BLOCKS || downGap < upGap);
    }

    /**
     * 两条都没写开门方向的上下相邻站台股道，按 {@link #sideAbove} 各自把侧式站台放进它俩之间的同一条缝（两条外侧都挨着别的线）：两块会叠在一起，
     * 其实是两线共用的一块岛式站台。
     */
    private boolean facing(
        String upper,
        String lower,
        Map<String, Point> points,
        Map<Integer, List<Span>> spans,
        Map<String, Row> rowOf) {
      return opens(upper, 1).isEmpty()
          && opens(lower, -1).isEmpty()
          && !trackBetween(upper, lower)
          && lateral(upper, lower) >= MIN_PLATFORM_BLOCKS
          && !sideAbove(upper, points, spans, rowOf)
          && sideAbove(lower, points, spans, rowOf);
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

    /** 站台块的左右范围：以站台节点为中心，不越过同一行相邻的节点（相邻的也是站台股道时两块各占一半，不叠在一起），也不出画面。 */
    private int[] extent(String key, Map<String, Point> points) {
      Point at = points.get(key);
      int left = Math.max(rect.left(), at.x() - PLATFORM_HALF);
      int right = Math.min(rect.left() + rect.width() - 1, at.x() + PLATFORM_HALF);
      boolean sharedLeft = false;
      boolean sharedRight = false;
      Set<String> tracks = new HashSet<>();
      area.tracks().forEach(track -> tracks.add(track.node().value()));
      for (Map.Entry<String, Point> entry : points.entrySet()) {
        Point other = entry.getValue();
        if (entry.getKey().equals(key)
            || other.y() != at.y()
            || passThrough.contains(entry.getKey())) {
          continue;
        }
        boolean shared = tracks.contains(entry.getKey());
        int middle = (at.x() + other.x()) / 2;
        if (other.x() < at.x()) {
          int limit = shared ? middle + 3 : other.x() + 6;
          if (limit > left) {
            left = limit;
            sharedLeft = shared;
          }
        } else if (other.x() > at.x()) {
          int limit = shared ? middle - 3 : other.x() - 6;
          if (limit < right) {
            right = limit;
            sharedRight = shared;
          }
        }
      }
      if (right - left < 24) {
        return new int[] {at.x() - 12, at.x() + 12, 0, 0};
      }
      return new int[] {left, right, sharedLeft ? 1 : 0, sharedRight ? 1 : 0};
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
     * 直股：只连两条区间的节点两条都是（两条朝同一边离开的 V 形除外）；只连一条的就是那一条；三条以上的先看道岔所在的轨道方块：从相对两面接出去的两条就是直股
     * （轨道只能从方块的四个面接出去，直股在道岔处就是直着过去的）；认不出各条从哪一面接出去、或相对两面的两条朝同一边离开（V 形，例如两条渡线汇到中间一段）时，
     * 取离开方向最接近一直线的两条。
     *
     * <p>离开方向取这条区间离节点 {@value #BRANCH_PROBE_BLOCKS} 格以内轨道方块的重心；没有方块时取另一头节点的方向。重心会被出了道岔就拐弯的正线带偏好几度，
     * 缓的渡线与弯走的正线这样分不清，所以先看接出去的面。
     */
    private Set<Link> throughLinks(Spot spot, List<Link> mine) {
      if (mine.size() == 2) {
        // 两条区间朝同一边离开（V 形，道岔的第三条腿不在图上）：不算直股，否则这一行会折回来、线叠在一起
        double[] a = direction(spot, mine.get(0));
        double[] b = direction(spot, mine.get(1));
        return a[0] * b[0] + a[1] * b[1] > 0 ? Set.of() : ordered(mine);
      }
      if (mine.size() < 2) {
        return ordered(mine);
      }
      List<double[]> directions = mine.stream().map(link -> direction(spot, link)).toList();
      int[] faces = mine.stream().mapToInt(link -> face(spot, link)).toArray();
      int bestI = -1;
      int bestJ = -1;
      double best = Double.MAX_VALUE;
      for (boolean byFace : new boolean[] {true, false}) {
        for (int i = 0; i < mine.size(); i++) {
          for (int j = i + 1; j < mine.size(); j++) {
            double dot =
                directions.get(i)[0] * directions.get(j)[0]
                    + directions.get(i)[1] * directions.get(j)[1];
            boolean opposed = faces[i] >= 0 && faces[j] >= 0 && (faces[i] ^ 1) == faces[j];
            if (byFace && (!opposed || dot >= 0.0)) {
              continue;
            }
            if (dot < best) {
              best = dot;
              bestI = i;
              bestJ = j;
            }
          }
        }
        if (bestI >= 0) {
          break;
        }
      }
      return ordered(List.of(mine.get(bestI), mine.get(bestJ)));
    }

    /** 轨道方块的四个面：东、西、南、北（相对的两面下标只差最低位）。 */
    private static final int[][] FACES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /**
     * 区间从节点所在的轨道方块哪一面接出去（{@link #FACES} 的下标）：那一面的邻格是它的，并且从邻格还接着往外有它的格子。道岔周围的几格常被各条区间都带上，
     * 只看邻格分不出是哪一条的；认不出、或不止一面像时为 -1。
     */
    private static int face(Spot spot, Link link) {
      int x = (int) Math.floor(spot.x());
      int z = (int) Math.floor(spot.z());
      Set<Long> columns = new HashSet<>();
      for (RailFootprintCell cell : link.cells()) {
        columns.add(column(cell.x(), cell.z()));
      }
      int found = -1;
      for (int k = 0; k < FACES.length; k++) {
        int nx = x + FACES[k][0];
        int nz = z + FACES[k][1];
        if (!columns.contains(column(nx, nz))) {
          continue;
        }
        boolean onward = false;
        for (int[] step : FACES) {
          int ox = nx + step[0];
          int oz = nz + step[1];
          // 往外接着的格子：不是道岔本身，也不是道岔的另一个邻格
          if (Math.abs(ox - x) + Math.abs(oz - z) >= 2 && columns.contains(column(ox, oz))) {
            onward = true;
          }
        }
        if (onward) {
          if (found >= 0) {
            return -1;
          }
          found = k;
        }
      }
      return found;
    }

    /** 轨道方块所在的一列（不分高低，坡道上的邻格也认）。 */
    private static long column(int x, int z) {
      return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    /**
     * 线路分叉处的一群道岔。
     *
     * @param nodes 群里的节点
     * @param stem 来路：往外伸出的区间里通往同一处、离开方向与其余各股最相反的那一组
     * @param branches 岔出去的各股：其余往外伸出的区间（去车库的不算）
     * @param groups 岔出去的各股按通往的地方分组
     */
    private record Junction(
        Set<String> nodes, Set<Link> stem, Set<Link> branches, List<Set<Link>> groups) {}

    /** 分叉往外伸出的一条区间在群外的那一头。 */
    private static String outer(Junction junction, Link link) {
      return junction.nodes().contains(link.a()) ? link.b() : link.a();
    }

    /**
     * 找线路分叉：不属于车站、车库、出口而连着三条以上区间的节点，彼此之间的区间不长于 {@value #JUNCTION_GAP_BLOCKS}
     * 格的并成一群（复线分叉常由几组道岔组成）；这一群往外伸出的区间沿线最先到达的车站或出口有三处以上不同（去车库的不算）就是分叉。
     * 通往同一车站不同股道的咽喉、上下行之间的渡线往外只到两处，不算；出口按通往的车站算，复线的两条出口去同一站只算一处。
     *
     * <p>分叉的几股不分主次：来路连进群里，群内的道岔顺着来路排；岔出去的各股都在群外起新行、各占一层，从群里朝同一方向分开，画成 Y 形，不把哪一股当成来路的直线延续。
     */
    private void findJunctions() {
      Map<String, String> parent = new HashMap<>();
      for (Map.Entry<String, List<Link>> entry : around.entrySet()) {
        if (entry.getValue().size() >= 3 && !endOf.containsKey(entry.getKey())) {
          parent.put(entry.getKey(), entry.getKey());
        }
      }
      for (Link link : links) {
        if (parent.containsKey(link.a())
            && parent.containsKey(link.b())
            && length(link) <= JUNCTION_GAP_BLOCKS) {
          String a = root(parent, link.a());
          String b = root(parent, link.b());
          if (!a.equals(b)) {
            parent.put(a.compareTo(b) < 0 ? b : a, a.compareTo(b) < 0 ? a : b);
          }
        }
      }
      Map<String, Set<String>> groups = new LinkedHashMap<>();
      for (String key : parent.keySet().stream().sorted().toList()) {
        groups.computeIfAbsent(root(parent, key), ignored -> new LinkedHashSet<>()).add(key);
      }
      for (Set<String> group : groups.values()) {
        // 往外伸出的区间按沿线最先到达的车站或出口分组，记下各自从群里哪个节点伸出
        Map<String, List<Link>> legs = new LinkedHashMap<>();
        Map<Link, String> inner = new HashMap<>();
        for (String key : group) {
          for (Link link : around.getOrDefault(key, List.of())) {
            if (group.contains(link.other(key))) {
              continue;
            }
            Optional<String> end = nearestEnd(group, key, link);
            if (end.isPresent() && !end.get().startsWith("D:")) {
              legs.computeIfAbsent(end.get(), ignored -> new ArrayList<>()).add(link);
              inner.put(link, key);
            }
          }
        }
        if (legs.size() < 3) {
          continue;
        }
        String stemEnd = null;
        double best = Double.MAX_VALUE;
        for (Map.Entry<String, List<Link>> entry : legs.entrySet()) {
          double score = 0.0;
          for (Link mine : entry.getValue()) {
            double[] a = direction(spots.get(inner.get(mine)), mine);
            for (Map.Entry<String, List<Link>> other : legs.entrySet()) {
              if (other.getKey().equals(entry.getKey())) {
                continue;
              }
              for (Link theirs : other.getValue()) {
                double[] b = direction(spots.get(inner.get(theirs)), theirs);
                score += a[0] * b[0] + a[1] * b[1];
              }
            }
          }
          score /= entry.getValue().size();
          if (score < best) {
            best = score;
            stemEnd = entry.getKey();
          }
        }
        // 来路在区内、往外只有另一股通往区内、其余都是出口：不是线路分叉，是正线旁岔出区外的支线。正线经群里的道岔直着接过去（见 routeThrough），
        // 出口照常从旁边岔出。来路本身是出口（区内只有岔出去的两股）、或两处之间接不上进路时，照样是分叉
        List<String> inZone = legs.keySet().stream().filter(end -> !end.startsWith("X:")).toList();
        if (inZone.size() == 2
            && inZone.contains(stemEnd)
            && routeThrough(group, legs.get(inZone.get(0)), legs.get(inZone.get(1)), inner)) {
          continue;
        }
        Set<Link> stem = ordered(legs.get(stemEnd));
        List<Link> branches = new ArrayList<>();
        List<Set<Link>> branchGroups = new ArrayList<>();
        for (Map.Entry<String, List<Link>> entry : legs.entrySet()) {
          if (!entry.getKey().equals(stemEnd)) {
            branches.addAll(entry.getValue());
            branchGroups.add(ordered(entry.getValue()));
          }
        }
        Junction junction =
            new Junction(ordered(group), stem, ordered(branches), List.copyOf(branchGroups));
        junctions.add(junction);
        group.forEach(key -> junctionOf.put(key, junction));
      }
    }

    /**
     * 区间点的名字写着区间（运营商:起点:终点）、股道与序号：同一区间相邻两个区间点（序号不必连续：同一股道上下一个有的，或整条区间里下一个有的）之间经过的道岔，按这条区间连成直股。
     * 道岔两股与来路的夹角只差一两度、几何上分不出直股侧股时，正线因此照线路画成直线；同一区间中途换股道（序号接着数、股道号变了）照样接上。
     * 两两配对时先配同一股道的，再按路程从短到长；进路按行车方向走（不在道岔处折返）、只经道岔一类节点，各条进路不共用节点，经过分叉群或已定下直股的节点的不用。
     * 出口外端的区间点也算：出口因此接在它进站的那一行上。
     */
    private void followIntervals() {
      record Candidate(
          String from, String to, List<Link> links, boolean sameTrack, double length) {}
      // 区间 → 序号 → 区间点；区间 + 股道 → 序号 → 区间点（同一股道上下一个有的序号）
      Map<String, java.util.TreeMap<Integer, List<String>>> byRoute = new LinkedHashMap<>();
      Map<String, java.util.TreeMap<Integer, List<String>>> byTrack = new LinkedHashMap<>();
      intervals.forEach(
          (key, mark) -> {
            byRoute
                .computeIfAbsent(mark.route(), ignored -> new java.util.TreeMap<>())
                .computeIfAbsent(mark.sequence(), ignored -> new ArrayList<>())
                .add(key);
            byTrack
                .computeIfAbsent(
                    mark.route() + "#" + mark.track(), ignored -> new java.util.TreeMap<>())
                .computeIfAbsent(mark.sequence(), ignored -> new ArrayList<>())
                .add(key);
          });
      List<Candidate> candidates = new ArrayList<>();
      intervals.forEach(
          (from, mark) -> {
            // 下一个：同一股道上序号最近的，和整条区间里序号最近的（中途换股道时序号接着数）
            Set<String> targets = new LinkedHashSet<>();
            Optional.ofNullable(
                    byTrack.get(mark.route() + "#" + mark.track()).higherEntry(mark.sequence()))
                .ifPresent(entry -> targets.addAll(entry.getValue()));
            Optional.ofNullable(byRoute.get(mark.route()).higherEntry(mark.sequence()))
                .ifPresent(entry -> targets.addAll(entry.getValue()));
            if (targets.isEmpty()) {
              return;
            }
            // 只经道岔一类节点（不是车站、车库、出口或区间点）
            Map<String, Reach> reached =
                walk(
                    from,
                    null,
                    key -> true,
                    key -> !endOf.containsKey(key) && !intervals.containsKey(key));
            for (String to : targets) {
              Reach reach = reached.get(to);
              if (reach != null && !reach.links().isEmpty()) {
                candidates.add(
                    new Candidate(
                        from,
                        to,
                        reach.links(),
                        mark.track() == intervals.get(to).track(),
                        reach.distance()));
              }
            }
          });
      candidates.sort(
          Comparator.comparing((Candidate c) -> !c.sameTrack())
              .thenComparingDouble(Candidate::length)
              .thenComparing(Candidate::from)
              .thenComparing(Candidate::to));
      Set<String> usedFrom = new HashSet<>();
      Set<String> usedTo = new HashSet<>();
      Set<String> usedNodes = new HashSet<>();
      Map<String, List<Link>> continuing = new LinkedHashMap<>();
      for (Candidate candidate : candidates) {
        if (usedFrom.contains(candidate.from()) || usedTo.contains(candidate.to())) {
          continue;
        }
        List<String> along = nodesAlong(candidate.from(), candidate.links());
        List<String> between = along.subList(1, along.size() - 1);
        if (between.stream()
            .anyMatch(
                key ->
                    usedNodes.contains(key)
                        || junctionOf.containsKey(key)
                        || forcedThrough.containsKey(key))) {
          continue;
        }
        usedFrom.add(candidate.from());
        usedTo.add(candidate.to());
        usedNodes.addAll(between);
        continuing
            .computeIfAbsent(candidate.from(), ignored -> new ArrayList<>())
            .add(candidate.links().get(0));
        continuing
            .computeIfAbsent(candidate.to(), ignored -> new ArrayList<>())
            .add(candidate.links().get(candidate.links().size() - 1));
        for (int i = 0; i < between.size(); i++) {
          continuing.put(
              between.get(i), List.of(candidate.links().get(i), candidate.links().get(i + 1)));
        }
      }
      continuing.forEach(
          (key, both) -> {
            if (both.size() == 2
                && both.get(0) != both.get(1)
                && !junctionOf.containsKey(key)
                && !forcedThrough.containsKey(key)
                && !turnsBack(key, both.get(0), both.get(1))) {
              forcedThrough.put(key, ordered(both));
            }
          });
    }

    /**
     * 道岔群往外只有两处通往区内（其余是出口）时，把两处的各股经群内的道岔两两接起来，接上的进路在途经各节点上定为直股（不按道岔的几何走向）：
     * 实地往出口一侧是直着走、往区内一侧是拐过去时，正线也照样连成一条链、画成一条直线，出口从旁边岔出。进路按行车方向走（不在道岔处折返）， 两两配对时先取群内路程最短的，各条进路不共用节点。
     * 一股走到另一处某一股的外端时只认最短的那条：经别的股更近（例如这股绕远路汇进来）就不接，那股的道岔交给几何定直股。
     *
     * @param group 群里的节点
     * @param from 一处的各股
     * @param to 另一处的各股
     * @param inner 往外伸出的区间 → 它在群里的那一头
     * @return 接上了至少一条进路
     */
    private boolean routeThrough(
        Set<String> group, List<Link> from, List<Link> to, Map<Link, String> inner) {
      record Candidate(List<String> nodes, List<Link> links, double length) {}
      List<Candidate> candidates = new ArrayList<>();
      for (Link a : from) {
        for (Link b : to) {
          String exit = b.other(inner.get(b));
          // 从 a 进群，只经群里的节点，经 b 出群；走到 b 外端最近的不是经 b（另一股更近）时不接：
          // 绕远路的那股强接成直股会把它整条拉成一行，交给道岔几何定直股反而画得对
          Reach reach =
              walk(inner.get(a), a, key -> group.contains(key) || key.equals(exit), group::contains)
                  .get(exit);
          if (reach == null || reach.links().get(reach.links().size() - 1) != b) {
            continue;
          }
          List<Link> links = new ArrayList<>(List.of(a));
          links.addAll(reach.links());
          List<String> nodes = nodesAlong(inner.get(a), reach.links());
          candidates.add(
              new Candidate(nodes.subList(0, nodes.size() - 1), links, reach.distance()));
        }
      }
      candidates.sort(Comparator.comparingDouble(Candidate::length));
      Set<Link> usedLegs = new HashSet<>();
      Set<String> usedNodes = new HashSet<>();
      for (Candidate candidate : candidates) {
        Link a = candidate.links().get(0);
        Link b = candidate.links().get(candidate.links().size() - 1);
        if (usedLegs.contains(a)
            || usedLegs.contains(b)
            || candidate.nodes().stream().anyMatch(usedNodes::contains)) {
          continue;
        }
        usedLegs.add(a);
        usedLegs.add(b);
        usedNodes.addAll(candidate.nodes());
        for (int i = 0; i < candidate.nodes().size(); i++) {
          forcedThrough.put(
              candidate.nodes().get(i),
              ordered(List.of(candidate.links().get(i), candidate.links().get(i + 1))));
        }
      }
      return !usedLegs.isEmpty();
    }

    /** 从 {@code from} 起依次经过 {@code links} 走到的各节点（含两头）。 */
    private static List<String> nodesAlong(String from, List<Link> links) {
      List<String> nodes = new ArrayList<>(List.of(from));
      for (Link link : links) {
        nodes.add(link.other(nodes.get(nodes.size() - 1)));
      }
      return nodes;
    }

    /** 在节点处从 {@code in} 换到 {@code out} 是折返：两条区间朝同一边离开（V 形，车走不通）。 */
    private boolean turnsBack(String key, Link in, Link out) {
      double[] a = direction(spots.get(key), in);
      double[] b = direction(spots.get(key), out);
      return a[0] * b[0] + a[1] * b[1] > 0;
    }

    /**
     * 走到一个节点的最短路。
     *
     * @param distance 沿轨道的距离（格）
     * @param links 从起点起依次经过的区间
     */
    private record Reach(double distance, List<Link> links) {}

    /**
     * 从 {@code start} 沿轨道按行车方向走（到一个节点后不折返：不沿与进来那条区间朝同一边离开的区间走），按距离从近到远记下走到的各节点的最短路。
     *
     * @param start 起点
     * @param arrived 进到起点的那条区间（起点各方向都能走时为 null）
     * @param enter 能走进的节点
     * @param pass 走进后还能接着往前走的节点（起点总能）；不能的只记下、不再往前
     * @return 走到的节点 → 最短路，按距离从近到远
     */
    private Map<String, Reach> walk(
        String start,
        Link arrived,
        java.util.function.Predicate<String> enter,
        java.util.function.Predicate<String> pass) {
      record State(String key, Link via) {}
      record Visit(State state, double distance) {}
      Map<State, Double> best = new HashMap<>();
      Map<State, State> previous = new HashMap<>();
      PriorityQueue<Visit> queue = new PriorityQueue<>(Comparator.comparingDouble(Visit::distance));
      State origin = new State(start, arrived);
      best.put(origin, 0.0);
      queue.add(new Visit(origin, 0.0));
      Map<String, Reach> reached = new LinkedHashMap<>();
      while (!queue.isEmpty()) {
        Visit visit = queue.poll();
        State at = visit.state();
        if (visit.distance() > best.get(at)) {
          continue;
        }
        if (!reached.containsKey(at.key())) {
          List<Link> links = new ArrayList<>();
          for (State step = at; previous.containsKey(step); step = previous.get(step)) {
            links.add(0, step.via());
          }
          reached.put(at.key(), new Reach(visit.distance(), List.copyOf(links)));
        }
        if (!at.equals(origin) && !pass.test(at.key())) {
          continue;
        }
        for (Link link : around.getOrDefault(at.key(), List.of())) {
          String next = link.other(at.key());
          if (link == at.via()
              || !enter.test(next)
              || (at.via() != null && turnsBack(at.key(), at.via(), link))) {
            continue;
          }
          State state = new State(next, link);
          double distance = visit.distance() + length(link);
          if (distance < best.getOrDefault(state, Double.MAX_VALUE)) {
            best.put(state, distance);
            previous.put(state, at);
            queue.add(new Visit(state, distance));
          }
        }
      }
      return reached;
    }

    /** 从群里的 {@code from} 经 {@code first} 往外按行车方向走（不再进这一群、不在道岔处折返），沿轨道最近的车站、车库或出口。 */
    private Optional<String> nearestEnd(Set<String> group, String from, Link first) {
      return walk(
              first.other(from), first, key -> !group.contains(key), key -> !endOf.containsKey(key))
          .keySet()
          .stream()
          .filter(endOf::containsKey)
          .findFirst()
          .map(endOf::get);
    }

    private double length(Link link) {
      return lengths.computeIfAbsent(link, this::trackLength);
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
     * 行：直股连成的一条链整条占一行，几个车站之间的同一条正线因此画成一条直线。站台股道所在的链先排，落在同一条股道延长线上的站台股道并为一行（并进来会与这一行已有的一段横向重叠的不并）；
     * 其余的链各占一行。行的横向位置取起头的站台股道，没有站台的链取链上各节点的平均。分叉岔出去的链不与别的链并行：它的站台哪怕正落在来路的延长线上也另起一行。
     */
    private Map<String, Row> rows(YardArea area) {
      Map<String, Row> rowOf = new HashMap<>();
      List<Row> seeds = new ArrayList<>();
      List<Spot> seedSpots = new ArrayList<>();
      Set<Chain> branched = new HashSet<>();
      for (Junction junction : junctions) {
        for (Link link : junction.branches()) {
          branched.add(chainOf.get(outer(junction, link)));
        }
      }
      List<Spot> platforms =
          area.tracks().stream()
              .map(track -> spots.get(track.node().value()))
              .filter(Objects::nonNull)
              .sorted(Comparator.comparingDouble(s -> vOf.get(s.key())))
              .toList();
      // 各行已占的里程范围：并进来的一段与它们横向重叠时不并（否则两条股道画在同一行的同一处）
      Map<Row, List<double[]>> taken = new HashMap<>();
      for (Spot spot : platforms) {
        if (rowOf.containsKey(spot.key())) {
          continue;
        }
        List<String> run = runOf(spot.key());
        double[] range = {
          run.stream().mapToDouble(uOf::get).min().orElse(0.0),
          run.stream().mapToDouble(uOf::get).max().orElse(0.0)
        };
        Row row = null;
        for (int i = 0; i < seeds.size(); i++) {
          if (inLine(seedSpots.get(i), spot)
              && !branched.contains(chainOf.get(spot.key()))
              && !branched.contains(chainOf.get(seedSpots.get(i).key()))
              && taken.get(seeds.get(i)).stream()
                  .noneMatch(other -> overlaps(other, range, -0.5))) {
            row = seeds.get(i);
          }
        }
        if (row == null) {
          row = new Row(vOf.get(spot.key()));
          seeds.add(row);
          seedSpots.add(spot);
        }
        taken.computeIfAbsent(row, ignored -> new ArrayList<>()).add(range);
        for (String key : run) {
          rowOf.put(key, row);
        }
      }
      // 其余各段：只接在一行上的（出口短线、尽头侧线）按它离接点的横向偏移排在那一行旁边——横向位置只和接点比，不和别处量的比
      // （股道出了站常收拢，站台处与咽喉处的横向位置差得远）；接点须与它用同一条脊线量。接在几行之间的（渡线中段）和接不上已有行的，
      // 按自身横向位置：几行的行位置各在自己的站台处量，彼此不能拿来推算
      List<List<String>> pending = new ArrayList<>();
      for (Chain chain : chains) {
        for (List<String> run : runs(chain)) {
          if (!rowOf.containsKey(run.get(0))) {
            pending.add(run);
          }
        }
      }
      boolean progress = true;
      while (!pending.isEmpty() && progress) {
        progress = false;
        for (java.util.Iterator<List<String>> it = pending.iterator(); it.hasNext(); ) {
          List<String> run = it.next();
          List<String> anchors = anchorsOf(run, rowOf);
          if (anchors.isEmpty()) {
            continue;
          }
          double v = run.stream().mapToDouble(vOf::get).average().orElse(0.0);
          boolean oneRow = anchors.stream().map(rowOf::get).distinct().count() == 1;
          Row row =
              new Row(
                  oneRow
                      ? anchors.stream()
                          .mapToDouble(anchor -> rowOf.get(anchor).v() + v - vOf.get(anchor))
                          .average()
                          .orElse(v)
                      : v);
          run.forEach(key -> rowOf.put(key, row));
          it.remove();
          progress = true;
        }
      }
      for (List<String> run : pending) {
        Row row = new Row(run.stream().mapToDouble(vOf::get).average().orElse(0.0));
        run.forEach(key -> rowOf.put(key, row));
      }
      return rowOf;
    }

    /** 一段经区间直接连着的、已排进行里的各节点（与这一段用同一条脊线量横向位置），按段内先后。 */
    private List<String> anchorsOf(List<String> run, Map<String, Row> rowOf) {
      List<String> anchors = new ArrayList<>();
      for (String key : run) {
        for (Link link : around.getOrDefault(key, List.of())) {
          String other = link.other(key);
          if (!run.contains(other)
              && rowOf.containsKey(other)
              && frameOf.get(other) == frameOf.get(key)) {
            anchors.add(other);
          }
        }
      }
      return anchors;
    }

    /** 链在 {@link #rowBreaks} 处断开后的几段，按链上先后。 */
    private List<List<String>> runs(Chain chain) {
      List<List<String>> out = new ArrayList<>();
      List<String> run = new ArrayList<>(List.of(chain.spots().get(0)));
      for (int i = 0; i < chain.links().size(); i++) {
        if (rowBreaks.contains(chain.links().get(i))) {
          out.add(run);
          run = new ArrayList<>();
        }
        run.add(chain.spots().get(i + 1));
      }
      out.add(run);
      return out;
    }

    /** 节点所在的那一段（见 {@link #runs}）。 */
    private List<String> runOf(String key) {
      return runs(chainOf.get(key)).stream()
          .filter(run -> run.contains(key))
          .findFirst()
          .orElseThrow();
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
      // 最上、最下两层外侧可能还有侧式站台：层多到要压缩层距时先给它们留出位置
      int room = Math.max(1, height - 2 * (PLATFORM_MARGIN + SIDE_PLATFORM_DEPTH));
      int gap = count > 1 ? Math.min(ROW_GAP, room / (count - 1)) : 0;
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
      // 行按对象身份存，横向位置与左端都相同时按行里最小的节点键排，次序不随启动变
      Map<Row, String> firstKey = new HashMap<>();
      rowOf.forEach(
          (key, row) -> {
            widen(own, row, uOf.get(key));
            widen(drawn, row, uOf.get(key));
            firstKey.merge(row, key, (a, b) -> a.compareTo(b) <= 0 ? a : b);
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
                  Comparator.comparingDouble(Row::v)
                      .thenComparingDouble(row -> own.get(row)[0])
                      .thenComparing(firstKey::get))
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
      // 自下而上再走一遍：每行往下压到紧挨着与它重叠的下一行，侧线贴着它岔出的正线，不浮到最上面。
      // 经区间连着上面某一行的不压：它已经贴着自己的正线了，再压会越过别的行、贴到另一股分叉的股道旁边去
      for (int i = ordered.size() - 2; i >= 0; i--) {
        Row row = ordered.get(i);
        int at = level.get(row);
        if (linked.getOrDefault(row, Set.of()).stream().anyMatch(other -> level.get(other) < at)) {
          continue;
        }
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
     * 横坐标：节点按沿线先后排开。相邻两处之间的距离按平方根压缩（长直线段缩短，道岔密集处相对拉开），再给站台块、车库留够宽度，给每段跨行的区间留出横向距离：跨两层以内的留够画 45°
     * 斜线，跨更多层的只按层数的平方根留（至少两层的宽，斜线变陡），免得一条横跨全图的线把道岔区拉得比车站还宽；U 形联络线折返处那条区间在两头外侧竖着连，
     * 不在这里留距离，只在折返点落在最边上一列时在图边留出拐角的宽（每次最多放大 {@value #MAX_STRETCH} 倍，反复几轮），最后按宽度铺满。
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
          // U 形折返处那条区间在两头外侧竖着连（见 turn），不占两头之间的横向距离
          if (passThrough.contains(link.a())
              || passThrough.contains(link.b())
              || rowBreaks.contains(link)) {
            continue;
          }
          int rows =
              Math.abs(rowIndex.get(rowOf.get(link.a())) - rowIndex.get(rowOf.get(link.b())));
          int low = Math.min(levelOf.get(link.a()), levelOf.get(link.b()));
          int high = Math.max(levelOf.get(link.a()), levelOf.get(link.b()));
          double have = scale * sum(weights, low, high);
          // 跨两层以内留够画 45°；跨更多层的只按层数的平方根留（至少两层的宽），斜线变陡
          double need = (rows <= 2 ? rows : Math.max(2.0, Math.sqrt(rows))) * gap;
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
      // U 形折返点落在最左或最右一列时，在那一侧留出“⊂”拐角的宽（见 turn），否则画不下、退回从两头之间斜穿
      int corner = ROW_GAP / 2 + TRACK_LINE + 1;
      for (Link link : rowBreaks) {
        Integer level = levelOf.get(turnNode.get(link));
        if (level == null || levels.size() < 2) {
          continue;
        }
        if (turnLow.get(link) && level == 0) {
          reserveLeft = Math.max(reserveLeft, corner);
        } else if (!turnLow.get(link) && level == levels.size() - 1) {
          reserveRight = Math.max(reserveRight, corner);
        }
      }
      double usable = Math.max(1, width - reserveLeft - reserveRight);
      columnU = levels.stream().mapToDouble(Double::doubleValue).toArray();
      columnX = new double[at.length];
      for (int i = 0; i < at.length; i++) {
        columnX[i] = rect.left() + reserveLeft + (total == 0.0 ? 0.5 : at[i] / total) * usable;
      }
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
      // U 形链折返处两头的节点自己占列：它们不在前后两个占列节点之间，按里程插进去会挤到一处
      Set<String> turns = new HashSet<>();
      rowBreaks.forEach(
          link -> {
            turns.add(link.a());
            turns.add(link.b());
          });
      for (Chain chain : chains) {
        List<String> keys = chain.spots();
        for (int i = 1; i + 1 < keys.size(); i++) {
          String key = keys.get(i);
          if (around.getOrDefault(key, List.of()).size() == 2
              && !platformKeys.contains(key)
              && !exitKey(key)
              && !turns.contains(key)
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
     * 像素；邻居也是站台股道时两块站台在中间分界，要留两份；邻居是岔出侧线的道岔时再多留一个层距（斜线的宽）。
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
          rooms.add(new int[] {levelOf.get(left), column, room(left, platformKeys, gap)});
        }
        if (right != null) {
          rooms.add(new int[] {column, levelOf.get(right), room(right, platformKeys, gap)});
        }
      }
      return rooms;
    }

    /** 站台节点到同一层邻居之间要留的像素（见 {@link #platformRooms}）。 */
    private int room(String neighbour, Set<String> platformKeys, int gap) {
      if (platformKeys.contains(neighbour)) {
        return 2 * PLATFORM_ROOM;
      }
      return PLATFORM_ROOM + (branching(neighbour) ? gap : 0);
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

    /**
     * U 形链折返处那条区间：两头各沿本行往外（折返那一侧）走到实地折返最远处，在那里竖着连上下两行，拐角切成 45°（“⊂”形），
     * 不从两头之间斜穿过车站与别的线。外伸的水平部分会顺着别的线走、或伸出画面时，按普通区间画（{@link #route}）。
     */
    private List<Point> turn(
        Link link, Map<String, Point> points, Map<Integer, List<int[]>> rails) {
      Point a = points.get(link.a());
      Point b = points.get(link.b());
      boolean low = turnLow.get(link);
      int rise = Math.abs(b.y() - a.y());
      if (rise == 0) {
        return route(link, points, rails);
      }
      int corner = Math.min(rise / 2, ROW_GAP / 2);
      int edge = low ? Math.min(a.x(), b.x()) - corner : Math.max(a.x(), b.x()) + corner;
      int x = (int) Math.round(xAt(turnExtent.getOrDefault(link, uOf.get(turnNode.get(link)))));
      x = low ? Math.min(x, edge) : Math.max(x, edge);
      if (x < rect.left() || x >= rect.left() + rect.width()) {
        return route(link, points, rails);
      }
      int inward = low ? corner : -corner;
      int dy = b.y() > a.y() ? 1 : -1;
      List<Point> out = new ArrayList<>();
      for (Point p :
          List.of(
              a,
              new Point(x + inward, a.y()),
              new Point(x, a.y() + dy * corner),
              new Point(x, b.y() - dy * corner),
              new Point(x + inward, b.y()),
              b)) {
        if (out.isEmpty() || !out.get(out.size() - 1).equals(p)) {
          out.add(p);
        }
      }
      return clearOf(out, rails) ? out : route(link, points, rails);
    }

    /** 里程换算成横坐标：在最近一次排出的相邻两列之间按里程插值，排出去的部分取最边上那一列。 */
    private double xAt(double u) {
      if (columnU.length == 0) {
        return rect.left() + rect.width() / 2.0;
      }
      if (u <= columnU[0]) {
        return columnX[0];
      }
      for (int i = 1; i < columnU.length; i++) {
        if (u <= columnU[i]) {
          double span = columnU[i] - columnU[i - 1];
          double t = span <= 0.0 ? 0.0 : (u - columnU[i - 1]) / span;
          return columnX[i - 1] + t * (columnX[i] - columnX[i - 1]);
        }
      }
      return columnX[columnX.length - 1];
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
        List<Point> route =
            rowBreaks.contains(link) ? turn(link, points, taken) : route(link, points, taken);
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

    /** 车厢方块按它沿轨道走过区间的比例，落到这条区间画出的折线上同样比例的位置（按折线长度量，拐弯、折返处也在线上）。 */
    private void placeCells(
        Link link,
        List<Point> route,
        int index,
        Map<RailFootprintCell, Point> cells,
        Map<RailFootprintCell, Along> along) {
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
      double drawnLength = polylineLength(route);
      for (int i = 0; i < ordered.size(); i++) {
        RailFootprintCell cell = ordered.get(i);
        if (cells.containsKey(cell)) {
          continue;
        }
        double t = total < 1e-6 ? 0.5 : Math.max(0.0, Math.min(1.0, walked[i] / total));
        double at = t * drawnLength;
        cells.put(cell, pointAlong(route, at));
        along.put(cell, new Along(index, at));
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
