package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SingleLineSectionIndex;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SingleLineSectionInfo;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher;

/**
 * 把排定的运行投影到共享资源上，扫描重叠——这是时刻表的"第二层"：多车交互。
 *
 * <p>第一层（{@link TimetableTimingCalculator}）模拟的是一辆孤立的车，它永远不会遇到单线会让、站台占满、道岔冲突， 而这三样正是决定"baseline
 * 能不能达到"的东西。本类不跑调度器，只做纯数据运算：每一趟车按第一层的逐边时分算出它在每个资源上的 {@code [进入, 离开]}
 * 区间，再逐资源扫描。确定性、毫秒级、可以在构建产物上直接断言。
 *
 * <h2>资源模型</h2>
 *
 * <table>
 * <tr><th>资源</th><th>来源</th><th>规则</th></tr>
 * <tr><td>TRACK 边</td><td>{@code SegmentTiming.edges}</td><td>互斥（物理占用唯一），不分方向；相邻占用之间要留 separation</td></tr>
 * <tr><td>PLATFORM 站台</td><td>停靠、待命、不停靠经过</td><td>按容量：具体股道容量 1；站台组（{@code OP:S:NAME}）容量 = 图里该站的股道数</td></tr>
 * <tr><td>SINGLE_LINE 单线区段</td><td>{@link SingleLineSectionIndex}</td><td>对向互斥；同向追踪由边互斥保证</td></tr>
 * <tr><td>JUNCTION 道岔</td><td>路径穿越的 SWITCHER 节点</td><td>两次通过之间要留 separation</td></tr>
 * </table>
 *
 * <p>每次停靠同时登记两层站台占用：站台组一层抓"车比股道多"，具体股道一层抓"同一股道被两辆车用"。DYNAMIC 停靠只有组一层—— 它到底停哪股道是运行时才决定的。
 *
 * <p>本类刻意<b>不</b>建模的东西：授权窗口、制动距离扩展的 lookahead、恢复链。那些属于真调度器；回放（阶段 8）如果发现本模型漏了约束， 修的是本模型，不是让 build
 * 去依赖回放。
 */
public final class TimetableConflictChecker {

  private TimetableConflictChecker() {}

  /**
   * 检查一组运行是否有资源冲突。
   *
   * @param graph 调度图快照
   * @param profiles 各 route 的逐边时分与站台资源
   * @param movements 全部运行（运营班次 + 出库/回库走行），时刻相对同一个零点
   * @param stays 车辆在站台上的待命区间（出库到站等首班、两班之间折返、末班到发回库票）
   * @param separationSeconds 相邻占用之间的最小间隔
   * @return 冲突报告
   */
  public static Report check(
      RailGraph graph,
      Map<UUID, RouteProfile> profiles,
      List<Movement> movements,
      List<Stay> stays,
      int separationSeconds) {
    return check(GraphIndex.of(graph), profiles, movements, stays, separationSeconds);
  }

  /**
   * 用预先建好的图索引检查。build 在搜索可行 headway 时会反复调用本方法，图索引只依赖图，应当只建一次。
   *
   * @param index 图索引
   * @param profiles 各 route 的逐边时分与站台资源
   * @param movements 全部运行
   * @param stays 站台待命
   * @param separationSeconds 相邻占用之间的最小间隔
   * @return 冲突报告
   */
  public static Report check(
      GraphIndex index,
      Map<UUID, RouteProfile> profiles,
      List<Movement> movements,
      List<Stay> stays,
      int separationSeconds) {
    return check(index, profiles, movements, stays, separationSeconds, Function.identity());
  }

  /**
   * 冲突扫描，带车辆身份。
   *
   * <p>{@code vehicleOf} 把占用的 code 映射成车辆标识（通常是 duty 号）：同一辆车的待命（code 是 duty 号）与它自己的班次 （code
   * 是车次号）、出库/回库走行（{@code Dxxx-CREATE/RETURN}）之间不报冲突。不传时按 code 自身，
   * 那会让每辆在端点折返的车都"撞上自己"——进站经过站台组内的路径点与随后的待命只差几秒。 邻表的占用不经过这个映射：两份邻表之间本来就不报，邻表与我之间的 code 不可能是同一辆车。
   */
  public static Report check(
      GraphIndex index,
      Map<UUID, RouteProfile> profiles,
      List<Movement> movements,
      List<Stay> stays,
      int separationSeconds,
      Function<String, String> vehicleOf) {
    // 桶用 LinkedHashMap 而不是 TreeMap：资源键是七十来字符的长串，TreeMap 每次查桶要做十来次全串比较，
    // 而增量重扫一次 attempt 要查近三百万次。遍历序仍是确定的（投影序），报告的序由 CONFLICT_ORDER 自己保证。
    Map<String, Resource> resources = new LinkedHashMap<>();
    projectInto(resources, index, profiles, movements, stays, vehicleOf);
    return scanResources(resources.values(), separationSeconds);
  }

  // ------------------------------------------------------------------ 投影与扫描的两半

  /**
   * 投影：把运行与待命摊进资源桶。{@link #check} 与 {@link OccupationIndex} 共用这一段——占用怎么算只能有一份实现，
   * 否则"全扫说撞、增量说不撞"这种事会出现在同一份表上。
   *
   * <p>车辆身份在这里就算好存进 {@link Occupation}，不留到扫描时再算：增量索引要按车移除占用，现算的身份没法拿来比。
   *
   * @param resources 落点，已有的桶会被追加
   * @return 这次投影碰到的资源键——增量替换靠它知道该重扫哪些资源
   */
  static Set<String> projectInto(
      Map<String, Resource> resources,
      GraphIndex index,
      Map<UUID, RouteProfile> profiles,
      List<Movement> movements,
      List<Stay> stays,
      Function<String, String> vehicleOf) {
    Objects.requireNonNull(profiles, "profiles");
    GraphIndex graphIndex = index == null ? GraphIndex.of(null) : index;
    Sink sink =
        new Sink(
            resources,
            new LinkedHashSet<>(),
            vehicleOf == null ? Function.identity() : vehicleOf,
            graphIndex.platformCapacity());
    for (Movement movement : movements == null ? List.<Movement>of() : movements) {
      RouteProfile profile = profiles.get(movement.routeId());
      if (profile == null) {
        continue;
      }
      project(movement, profile, sink, graphIndex.sections(), graphIndex.nodeTypes());
    }
    for (Stay stay : stays == null ? List.<Stay>of() : stays) {
      addPlatform(sink, stay.platform(), stay.code(), stay.from(), stay.to(), stay.owner());
    }
    return sink.touched();
  }

  /** 扫描：给定资源逐个扫，合成一张报告。 */
  static Report scanResources(Collection<Resource> resources, int separationSeconds) {
    int separation = Math.max(0, separationSeconds);
    List<Conflict> conflicts = new ArrayList<>();
    Active active = new Active();
    for (Resource resource : resources) {
      conflicts.addAll(resource.scan(separation, active));
    }
    return report(conflicts);
  }

  /**
   * 扫描时"仍在场"的占用，一个可复用的缓冲。
   *
   * <p>原本是每扫一个资源新建一个 {@code ArrayList}，再对每一条占用调一次 {@code removeIf}——那个 lambda 捕获了后车，
   * 于是<b>每条占用都要新造一个 lambda 对象</b>。全表扫一遍还能忍，增量重扫一次 attempt 要扫近三百万个资源桶，
   * 这些短命对象就成了大头（实测占总耗时的七成）。改成手写的原地压缩，扫描本身一个对象都不分配。
   */
  private static final class Active {
    private Occupation[] items = new Occupation[16];
    private int size;

    void reset() {
      size = 0;
    }

    /** 送走已经腾空的：{@code to + separation <= from} 的不再在场。判据与原来逐字一致。 */
    void expire(int separation, int from) {
      int kept = 0;
      for (int i = 0; i < size; i++) {
        if (items[i].to() + separation > from) {
          items[kept++] = items[i];
        }
      }
      size = kept;
    }

    void add(Occupation occupation) {
      if (size == items.length) {
        items = java.util.Arrays.copyOf(items, size * 2);
      }
      items[size++] = occupation;
    }

    int size() {
      return size;
    }

    Occupation get(int index) {
      return items[index];
    }
  }

  private static Report report(List<Conflict> conflicts) {
    conflicts.sort(CONFLICT_ORDER);
    return new Report(List.copyOf(conflicts));
  }

  /**
   * 冲突的全序。
   *
   * <p>原本只比 {@code (firstFrom, resource, first, second)}，其余靠资源遍历序与桶内插入序兜底。增量重扫两样都保不住——
   * 换一辆车会把它的占用挪到桶尾，只扫一部分资源又不经过完整的遍历序。所以把剩下的字段也比完：并列的两处冲突字段全同， 报哪一处都是同一条记录，报告因此与遍历顺序无关。
   */
  static final Comparator<Conflict> CONFLICT_ORDER =
      Comparator.comparingInt(Conflict::firstFrom)
          .thenComparing(Conflict::resource)
          .thenComparing(Conflict::first)
          .thenComparing(Conflict::second)
          .thenComparingInt(Conflict::firstTo)
          .thenComparingInt(Conflict::secondFrom)
          .thenComparingInt(Conflict::secondTo)
          .thenComparing(conflict -> conflict.firstOwner().orElse(""))
          .thenComparing(conflict -> conflict.secondOwner().orElse(""))
          .thenComparing(conflict -> conflict.kind().name());

  /** 车辆身份：邻表的占用按 {@code owner|code}（邻表的车次号可能与我的同名），我自己的按 {@code |duty 号}。 */
  static String vehicleKey(
      String code, Optional<String> owner, Function<String, String> vehicleOf) {
    return owner.isPresent() ? owner.get() + "|" + code : "|" + vehicleOf.apply(code);
  }

  /** 投影的落点：资源表、这次碰过的键、车辆身份映射与站台组容量。 */
  private record Sink(
      Map<String, Resource> resources,
      Set<String> touched,
      Function<String, String> vehicleOf,
      Map<String, Integer> platformCapacity) {

    void add(
        String key,
        Kind kind,
        int capacity,
        String code,
        int from,
        int to,
        int direction,
        Optional<String> owner) {
      touched.add(key);
      resources
          .computeIfAbsent(key, k -> new Resource(k, kind, capacity))
          .add(code, from, to, direction, owner, vehicleKey(code, owner, vehicleOf));
    }
  }

  /** 从 route 的停靠配置得出每个停靠点的站台资源。 */
  public static List<Platform> platformsOf(
      List<TimetableStop> stops, List<RouteStop> routeStops, List<NodeId> waypoints) {
    return platformsOf(stops, routeStops, waypoints, Map.of());
  }

  /**
   * 站台映射：只有图里类型为 STATION / DEPOT 的节点才是站台。
   *
   * <p>路径点常常命名在站台的命名空间下（{@code OP:S:CHT:3:003} 是 CHT 三号道的进站路径点），按名字解析会把它算进站台组 {@code
   * OP:S:CHT}，而站台组容量只数真站台——占用与容量口径不一致，一辆车在进站路径上也在消耗站台容量。 DYNAMIC 停靠只有组一层，节点 id 是占位串，不查类型。{@code
   * nodeTypes} 为空（没有图）时退回按名字解析。
   */
  public static List<Platform> platformsOf(
      List<TimetableStop> stops,
      List<RouteStop> routeStops,
      List<NodeId> waypoints,
      Map<NodeId, NodeType> nodeTypes) {
    List<Platform> out = new ArrayList<>(stops.size());
    for (int i = 0; i < stops.size(); i++) {
      String nodeId = waypoints != null && i < waypoints.size() ? waypoints.get(i).value() : "";
      RouteStop routeStop = routeStops != null && i < routeStops.size() ? routeStops.get(i) : null;
      boolean dynamic = routeStop != null && DynamicStopMatcher.isDynamicStop(routeStop);
      if (!dynamic && nodeTypes != null && !nodeTypes.isEmpty() && !nodeId.isBlank()) {
        NodeType type = nodeTypes.get(NodeId.of(nodeId));
        if (type != NodeType.STATION && type != NodeType.DEPOT) {
          out.add(Platform.none());
          continue;
        }
      }
      out.add(new Platform(nodeId, groupOf(nodeId), dynamic));
    }
    return List.copyOf(out);
  }

  /**
   * 一条 route 跑一遍会触及的全部资源键：边、穿越的道岔与车站股道、单线区段、每个停靠点的站台（组 + 具体股道）。
   *
   * <p>这是"足迹"的唯一出处：作用域判定问"两份表有没有共用资源"，用的键必须和这里投影占用时拼出来的键一字不差， 否则会出现"足迹说不相交、冲突检查却撞上"或反过来的假象。所以
   * {@link #project} 也只能通过下面这几个 {@code *Key} 方法拼键。
   */
  public static Set<String> resourceKeysOf(RouteProfile profile, GraphIndex index) {
    Objects.requireNonNull(profile, "profile");
    GraphIndex graphIndex = index == null ? GraphIndex.of(null) : index;
    Set<String> keys = new LinkedHashSet<>();
    for (TimetableTimingCalculator.SegmentTiming segment : profile.segments()) {
      for (RailEdge edge : segment.edges()) {
        keys.add(edgeKey(edge));
        if (graphIndex.sections() != null) {
          graphIndex
              .sections()
              .sectionInfoForEdge(edge.id())
              .ifPresent(info -> keys.add(singleLineKey(info.key())));
        }
      }
      List<NodeId> nodes = segment.nodes();
      for (int k = 1; k + 1 < nodes.size(); k++) {
        NodeId node = nodes.get(k);
        NodeType type = graphIndex.nodeTypes().get(node);
        if (type == NodeType.SWITCHER) {
          keys.add(junctionKey(node));
        } else if (type == NodeType.STATION) {
          keys.addAll(platformKeys(new Platform(node.value(), groupOf(node.value()), false)));
        }
      }
    }
    for (Platform platform : profile.platforms()) {
      keys.addAll(platformKeys(platform));
    }
    return Set.copyOf(keys);
  }

  static String edgeKey(RailEdge edge) {
    EdgeId edgeId = edge.id();
    return "edge:" + edgeId.a().value() + "~" + edgeId.b().value();
  }

  static String junctionKey(NodeId node) {
    return "junction:" + node.value();
  }

  static String singleLineKey(String sectionKey) {
    return "single:" + sectionKey;
  }

  /** 站台的两层键：站台组一层抓"车比股道多"，具体股道一层抓"同一股道被两辆车用"；DYNAMIC 停靠只有组一层。 */
  static List<String> platformKeys(Platform platform) {
    List<String> keys = new ArrayList<>(2);
    if (!platform.group().isBlank()) {
      keys.add("platform-group:" + platform.group());
    }
    if (!platform.dynamic() && !platform.nodeId().isBlank()) {
      keys.add("platform:" + platform.nodeId());
    }
    return keys;
  }

  private static void project(
      Movement movement,
      RouteProfile profile,
      Sink sink,
      SingleLineSectionIndex sections,
      Map<NodeId, NodeType> nodeTypes) {
    int base = movement.startSeconds();
    for (TimetableTimingCalculator.SegmentTiming segment : profile.segments()) {
      List<RailEdge> edges = segment.edges();
      // 边：互斥。
      for (int k = 0; k < edges.size(); k++) {
        RailEdge edge = edges.get(k);
        sink.add(
            edgeKey(edge),
            Kind.TRACK,
            1,
            movement.code(),
            base + segment.enterOffset(k),
            base + segment.exitOffset(k),
            0,
            movement.owner());
      }
      // 路径中间穿越的节点：道岔两次通过之间要留间隔；不停靠而经过的车站股道也是一次占用——
      // 一辆在单股道车站待命的车必须能挡住从它身上碾过去的对向车。
      List<NodeId> nodes = segment.nodes();
      for (int k = 1; k + 1 < nodes.size(); k++) {
        NodeId node = nodes.get(k);
        NodeType type = nodeTypes.get(node);
        int at = base + segment.nodeOffsets().get(k);
        if (type == NodeType.SWITCHER) {
          sink.add(
              junctionKey(node), Kind.JUNCTION, 1, movement.code(), at, at, 0, movement.owner());
        } else if (type == NodeType.STATION) {
          addPlatform(
              sink,
              new Platform(node.value(), groupOf(node.value()), false),
              movement.code(),
              at,
              at,
              movement.owner());
        }
      }
      // 单线区段：连续落在同一 section 的边合并成一个带方向的占用区间。
      if (sections != null) {
        String currentKey = null;
        int enter = 0;
        int exit = 0;
        int direction = 0;
        SingleLineSectionInfo current = null;
        for (int k = 0; k < edges.size(); k++) {
          Optional<SingleLineSectionInfo> info = sections.sectionInfoForEdge(edges.get(k).id());
          String key = info.map(SingleLineSectionInfo::key).orElse(null);
          if (key != null && key.equals(currentKey)) {
            exit = base + segment.exitOffset(k);
            continue;
          }
          if (currentKey != null) {
            sink.add(
                singleLineKey(currentKey),
                Kind.SINGLE_LINE,
                1,
                movement.code(),
                enter,
                exit,
                direction,
                movement.owner());
          }
          currentKey = key;
          current = info.orElse(null);
          if (key != null) {
            enter = base + segment.enterOffset(k);
            exit = base + segment.exitOffset(k);
            direction = directionOf(current, nodes.get(k), nodes.get(k + 1));
          }
        }
        if (currentKey != null) {
          sink.add(
              singleLineKey(currentKey),
              Kind.SINGLE_LINE,
              1,
              movement.code(),
              enter,
              exit,
              direction,
              movement.owner());
        }
      }
    }
    // 中间停靠：站台按容量。起终点的占用由 Stay 负责（它跨越到站、折返、再发车的整段）。
    List<TimetableStop> stops = profile.stops();
    for (int i = 1; i + 1 < stops.size(); i++) {
      TimetableStop stop = stops.get(i);
      Platform platform = i < profile.platforms().size() ? profile.platforms().get(i) : null;
      if (platform == null) {
        continue;
      }
      addPlatform(
          sink,
          platform,
          movement.code(),
          base + stop.arrivalOffsetSeconds(),
          base + stop.departureOffsetSeconds(),
          movement.owner());
    }
  }

  private static void addPlatform(
      Sink sink, Platform platform, String code, int from, int to, Optional<String> owner) {
    if (platform == null) {
      return;
    }
    for (String key : platformKeys(platform)) {
      int capacity =
          key.startsWith("platform-group:")
              ? Math.max(1, sink.platformCapacity().getOrDefault(platform.group(), 1))
              : 1;
      sink.add(key, Kind.PLATFORM, capacity, code, from, to, 0, owner);
    }
  }

  /** 方向：沿 section 节点序列的正向为 +1、反向为 −1；判不出来记 0，与任何方向都视为对向（保守）。 */
  private static int directionOf(SingleLineSectionInfo info, NodeId from, NodeId to) {
    if (info == null) {
      return 0;
    }
    int a = info.nodes().indexOf(from);
    int b = info.nodes().indexOf(to);
    if (a < 0 || b < 0 || a == b) {
      return 0;
    }
    return b > a ? 1 : -1;
  }

  /** 站台组容量：图里该站有几股道。 */
  private static Map<String, Integer> platformCapacity(RailGraph graph) {
    Map<String, Integer> out = new HashMap<>();
    for (RailNode node : graph.nodes()) {
      if (node == null || node.id() == null) {
        continue;
      }
      if (node.type() != NodeType.STATION && node.type() != NodeType.DEPOT) {
        continue;
      }
      String group = groupOf(node.id().value());
      if (!group.isBlank()) {
        out.merge(group, 1, Integer::sum);
      }
    }
    return out;
  }

  private static Map<NodeId, NodeType> nodeTypes(RailGraph graph) {
    Map<NodeId, NodeType> out = new HashMap<>();
    for (RailNode node : graph.nodes()) {
      if (node != null && node.id() != null && node.type() != null) {
        out.put(node.id(), node.type());
      }
    }
    return out;
  }

  /** 节点 ID 形如 {@code OP:S:NAME:track}：站台组是前三段。解析不出来就没有组。 */
  static String groupOf(String nodeId) {
    if (nodeId == null) {
      return "";
    }
    String[] parts = nodeId.trim().split(":");
    if (parts.length < 4 || !(parts[1].equals("S") || parts[1].equals("D"))) {
      return "";
    }
    return parts[0] + ":" + parts[1] + ":" + parts[2];
  }

  // ------------------------------------------------------------------ 模型

  /**
   * 只依赖图的那部分输入：单线区段索引、站台组股道数、节点类型。
   *
   * <p>单线索引要跑一遍迭代 Tarjan 找桥，在大图上是十几到几十毫秒；headway 搜索最多几十上百次 attempt，不能每次重建。
   *
   * @param sections 单线区段索引；无图时为 null
   * @param platformCapacity 站台组 → 股道数
   * @param nodeTypes 节点 → 类型
   */
  public record GraphIndex(
      SingleLineSectionIndex sections,
      Map<String, Integer> platformCapacity,
      Map<NodeId, NodeType> nodeTypes) {

    public GraphIndex {
      platformCapacity = platformCapacity == null ? Map.of() : Map.copyOf(platformCapacity);
      nodeTypes = nodeTypes == null ? Map.of() : Map.copyOf(nodeTypes);
    }

    /** 从图快照建索引；{@code null} 图得到空索引（只剩边互斥）。 */
    public static GraphIndex of(RailGraph graph) {
      if (graph == null) {
        return new GraphIndex(null, Map.of(), Map.of());
      }
      return new GraphIndex(
          SingleLineSectionIndex.fromGraph(graph),
          TimetableConflictChecker.platformCapacity(graph),
          TimetableConflictChecker.nodeTypes(graph));
    }
  }

  /** 冲突类型。 */
  public enum Kind {
    TRACK,
    PLATFORM,
    SINGLE_LINE,
    JUNCTION
  }

  /**
   * 一条 route 的投影输入。
   *
   * @param routeId Route UUID
   * @param routeCode Route code
   * @param stops 站间时分档案
   * @param segments 逐边时分
   * @param platforms 每个停靠点的站台资源，与 {@code stops} 对齐
   */
  public record RouteProfile(
      UUID routeId,
      String routeCode,
      List<TimetableStop> stops,
      List<TimetableTimingCalculator.SegmentTiming> segments,
      List<Platform> platforms) {

    public RouteProfile {
      Objects.requireNonNull(routeId, "routeId");
      routeCode = routeCode == null ? "" : routeCode;
      stops = stops == null ? List.of() : List.copyOf(stops);
      segments = segments == null ? List.of() : List.copyOf(segments);
      platforms = platforms == null ? List.of() : List.copyOf(platforms);
    }

    /** 首站站台；首站不是站台节点时为空。 */
    public Optional<Platform> origin() {
      return platforms.isEmpty()
          ? Optional.empty()
          : Optional.of(platforms.get(0)).filter(platform -> !platform.absent());
    }

    /** 末站站台；末站不是站台节点时为空。 */
    public Optional<Platform> terminal() {
      return platforms.isEmpty()
          ? Optional.empty()
          : Optional.of(platforms.get(platforms.size() - 1)).filter(platform -> !platform.absent());
    }
  }

  /**
   * 一个停靠点对应的站台资源。
   *
   * @param nodeId 具体股道节点（DYNAMIC 时是占位的首股道，不用于具体股道层）
   * @param group 站台组 {@code OP:S:NAME}；解析不出来为空
   * @param dynamic 是否 DYNAMIC 停靠（运行时才选股道，只登记组一层）
   */
  public record Platform(String nodeId, String group, boolean dynamic) {
    public Platform {
      nodeId = nodeId == null ? "" : nodeId.trim();
      group = group == null ? "" : group.trim();
    }

    /** 占位：这个停靠点不是站台（路径点、道岔），不登记任何站台资源。 */
    public static Platform none() {
      return new Platform("", "", false);
    }

    public boolean absent() {
      return nodeId.isBlank() && group.isBlank();
    }
  }

  /**
   * 一次运行：某条 route 从某个时刻开始跑一遍。
   *
   * @param code 展示用标识（车次号或 duty 走行代号）
   * @param routeId 跑的 route
   * @param startSeconds 首站发车时刻（相对统一零点）
   * @param owner 属于哪份邻表（显示码）；空 = 自己
   */
  public record Movement(String code, UUID routeId, int startSeconds, Optional<String> owner) {
    public Movement {
      code = code == null ? "" : code;
      Objects.requireNonNull(routeId, "routeId");
      owner = owner == null ? Optional.empty() : owner.filter(text -> !text.isBlank());
    }

    /** 自己的运行。 */
    public Movement(String code, UUID routeId, int startSeconds) {
      this(code, routeId, startSeconds, Optional.empty());
    }
  }

  /**
   * 车辆在站台上的一段待命。
   *
   * @param code 展示用标识
   * @param platform 站台资源
   * @param from 开始
   * @param to 结束
   * @param owner 属于哪份邻表（显示码）；空 = 自己
   */
  public record Stay(String code, Platform platform, int from, int to, Optional<String> owner) {
    public Stay {
      code = code == null ? "" : code;
      Objects.requireNonNull(platform, "platform");
      if (to < from) {
        throw new IllegalArgumentException("待命区间结束不能早于开始");
      }
      owner = owner == null ? Optional.empty() : owner.filter(text -> !text.isBlank());
    }

    /** 自己的待命。 */
    public Stay(String code, Platform platform, int from, int to) {
      this(code, platform, from, to, Optional.empty());
    }
  }

  /**
   * 一处冲突。
   *
   * @param kind 类型
   * @param resource 资源键
   * @param first 先到的运行
   * @param second 后到的运行
   * @param firstFrom 先到运行的占用开始
   * @param firstTo 先到运行的占用结束
   * @param secondFrom 后到运行的占用开始
   * @param secondTo 后到运行的占用结束
   */
  public record Conflict(
      Kind kind,
      String resource,
      String first,
      String second,
      int firstFrom,
      int firstTo,
      int secondFrom,
      int secondTo,
      Optional<String> firstOwner,
      Optional<String> secondOwner) {

    public Conflict {
      firstOwner = firstOwner == null ? Optional.empty() : firstOwner;
      secondOwner = secondOwner == null ? Optional.empty() : secondOwner;
    }

    /** 一方是邻表：我不能挪它，只能挪自己。 */
    public boolean external() {
      return firstOwner.isPresent() || secondOwner.isPresent();
    }

    /** 对方邻表的显示码；内部冲突为空。 */
    public Optional<String> otherOwner() {
      return firstOwner.isPresent() ? firstOwner : secondOwner;
    }

    /** 供报告使用的一行描述；时刻由调用方按需换算。 */
    public String describe(java.util.function.IntFunction<String> clock) {
      return String.format(
          Locale.ROOT,
          "%s %s: %s [%s–%s] 与 %s [%s–%s]",
          kind.name(),
          resource,
          label(first, firstOwner),
          clock.apply(firstFrom),
          clock.apply(firstTo),
          label(second, secondOwner),
          clock.apply(secondFrom),
          clock.apply(secondTo));
    }

    private static String label(String code, Optional<String> owner) {
      return owner.map(text -> text + " " + code).orElse(code);
    }
  }

  /** 冲突报告。 */
  public record Report(List<Conflict> conflicts) {

    public Report {
      conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
    }

    public static Report none() {
      return new Report(List.of());
    }

    public boolean clean() {
      return conflicts.isEmpty();
    }

    /** 双方都是自己的冲突。 */
    public List<Conflict> internal() {
      return conflicts.stream().filter(conflict -> !conflict.external()).toList();
    }

    /** 与邻表撞上的冲突。 */
    public List<Conflict> external() {
      return conflicts.stream().filter(Conflict::external).toList();
    }

    /** 外部冲突按对方邻表计数。 */
    public Map<String, Integer> externalByOwner() {
      Map<String, Integer> out = new LinkedHashMap<>();
      for (Conflict conflict : conflicts) {
        conflict.otherOwner().ifPresent(owner -> out.merge(owner, 1, Integer::sum));
      }
      return out;
    }

    /** 冲突最多的前几个资源键（含类型），供失败文案点名瓶颈。 */
    public List<String> topResources(int limit) {
      Map<String, Integer> counts = new LinkedHashMap<>();
      for (Conflict conflict : conflicts) {
        counts.merge(conflict.kind().name() + " " + conflict.resource(), 1, Integer::sum);
      }
      return counts.entrySet().stream()
          .sorted(
              Map.Entry.<String, Integer>comparingByValue()
                  .reversed()
                  .thenComparing(Map.Entry.comparingByKey()))
          .limit(Math.max(0, limit))
          .map(entry -> entry.getKey() + "×" + entry.getValue())
          .toList();
    }

    /** 按类型计数。 */
    public Map<Kind, Integer> countByKind() {
      Map<Kind, Integer> out = new LinkedHashMap<>();
      for (Conflict conflict : conflicts) {
        out.merge(conflict.kind(), 1, Integer::sum);
      }
      return out;
    }
  }

  /**
   * 一份表的车辆身份映射：车次号 → 它所属 duty 的 dutyCode；{@code Dxxx-CREATE} / {@code Dxxx-RETURN} → {@code Dxxx}；
   * duty 号 → 自身；查不到的 code 原样返回。供 {@link #check(GraphIndex, Map, List, List, int, Function)} 使用。
   */
  public static Function<String, String> vehicleOf(Timetable timetable) {
    Objects.requireNonNull(timetable, "timetable");
    Map<UUID, String> dutyCodeById = new HashMap<>();
    Map<String, String> byCode = new HashMap<>();
    for (VehicleDuty duty : timetable.duties()) {
      dutyCodeById.put(duty.id(), duty.dutyCode());
      byCode.put(duty.dutyCode(), duty.dutyCode());
      byCode.put(duty.dutyCode() + "-CREATE", duty.dutyCode());
      byCode.put(duty.dutyCode() + "-RETURN", duty.dutyCode());
    }
    for (TimetableTrip trip : timetable.trips()) {
      trip.dutyId()
          .map(dutyCodeById::get)
          .ifPresent(dutyCode -> byCode.put(trip.tripCode(), dutyCode));
    }
    Map<String, String> frozen = Map.copyOf(byCode);
    return code -> frozen.getOrDefault(code, code);
  }

  // ------------------------------------------------------------------ 扫描

  /**
   * @param vehicle 车辆身份：{@code owner|vehicleOf(code)}。同一辆车的占用之间不报冲突；带上 owner 是因为邻表的车次号可能与我的同名。
   */
  record Occupation(
      String code, int from, int to, int direction, Optional<String> owner, String vehicle) {

    /** 邻表之间的冲突不属于我：它们在各自发布时已经被检查过，报出来只会淹没我的问题。 */
    boolean bothExternal(Occupation other) {
      return owner.isPresent() && other.owner.isPresent();
    }

    boolean sameVehicle(Occupation other) {
      return vehicle.equals(other.vehicle);
    }
  }

  /**
   * 桶内的全序。
   *
   * <p>原本只比 {@code (from, code)}，并列的靠插入序兜底。增量索引换一辆车会把它的占用挪到桶尾，插入序保不住了——
   * 所以把剩下的字段也比完。并列的两条占用字段全同（{@code vehicle} 里带着 owner），取哪一条报出来都是同一条 {@link Conflict}，
   * 插在并列的哪一侧也就无所谓。
   */
  private static final Comparator<Occupation> OCCUPATION_ORDER =
      Comparator.comparingInt(Occupation::from)
          .thenComparing(Occupation::code)
          .thenComparingInt(Occupation::to)
          .thenComparingInt(Occupation::direction)
          .thenComparing(Occupation::vehicle);

  static final class Resource {
    private final String key;
    private final Kind kind;
    private final int capacity;
    private final List<Occupation> occupations = new ArrayList<>();

    private Resource(String key, Kind kind, int capacity) {
      this.key = key;
      this.kind = kind;
      this.capacity = Math.max(1, capacity);
    }

    /** 按 {@link #OCCUPATION_ORDER} 插进去，桶因此始终有序：扫描不必再排，增量重扫每步省掉一次全桶排序。 */
    private void add(
        String code, int from, int to, int direction, Optional<String> owner, String vehicle) {
      Occupation occupation =
          new Occupation(code, from, Math.max(from, to), direction, owner, vehicle);
      int at = Collections.binarySearch(occupations, occupation, OCCUPATION_ORDER);
      occupations.add(at < 0 ? -at - 1 : at, occupation);
    }

    /** 桶里的占用，按 {@link #OCCUPATION_ORDER} 有序。{@link OccupationIndex} 拿它来按值精确撤销。 */
    List<Occupation> occupations() {
      return occupations;
    }

    /**
     * 精确撤掉这些占用，每条撤一份。
     *
     * <p>桶是有序的，按 {@link #OCCUPATION_ORDER} 二分找。并列的几条字段全同（{@code vehicle} 里带着 owner），
     * 撤哪一条都一样，所以二分落在并列区间的哪个位置都不要紧。
     */
    void removeAll(List<Occupation> gone) {
      for (Occupation occupation : gone) {
        int at = Collections.binarySearch(occupations, occupation, OCCUPATION_ORDER);
        if (at >= 0) {
          occupations.remove(at);
        }
      }
    }

    private List<Conflict> scan(int separation, Active active) {
      active.reset();
      return switch (kind) {
        case TRACK, JUNCTION -> scanExclusive(occupations, separation, active);
        case PLATFORM -> scanCapacity(occupations, separation, active);
        case SINGLE_LINE -> scanDirectional(occupations, separation, active);
      };
    }

    /**
     * 容量 1：后一个占用必须在前面所有占用结束 + separation 之后开始。
     *
     * <p>要和所有仍在场的占用比而不是只和最晚离开的那个比：两份邻表之间的重叠不报，若只看最晚的那个， 夹在中间的邻表占用会把我与后一份邻表的冲突挡掉。
     */
    private List<Conflict> scanExclusive(List<Occupation> sorted, int separation, Active active) {
      List<Conflict> out = null;
      for (int i = 0; i < sorted.size(); i++) {
        Occupation next = sorted.get(i);
        active.expire(separation, next.from());
        for (int k = 0; k < active.size(); k++) {
          Occupation current = active.get(k);
          if (!current.sameVehicle(next) && !current.bothExternal(next)) {
            out = out == null ? new ArrayList<>() : out;
            out.add(conflict(current, next));
            break;
          }
        }
        active.add(next);
      }
      return out == null ? List.of() : out;
    }

    /**
     * 容量 n：同时在场的占用超过 n 就冲突；报的是新来的与在场里最早的那个<b>可归责</b>的占用。
     *
     * <p>"可归责"指不是两份邻表之间的对：新来的若是邻表，就找在场的我；新来的若是我，在场里谁都算。 只拿在场最早的那个比会被两份邻表夹住——它们之间的重叠不报，我夹在中间的占用就被漏掉。
     */
    private List<Conflict> scanCapacity(List<Occupation> sorted, int separation, Active active) {
      List<Conflict> out = null;
      for (int i = 0; i < sorted.size(); i++) {
        Occupation next = sorted.get(i);
        active.expire(separation, next.from());
        if (active.size() >= capacity) {
          // 在场里最早的那个可归责的；并列取先遇到的，与原来 Stream.min 的取法一致。
          Occupation partner = null;
          for (int k = 0; k < active.size(); k++) {
            Occupation current = active.get(k);
            if (current.sameVehicle(next) || current.bothExternal(next)) {
              continue;
            }
            if (partner == null || current.from() < partner.from()) {
              partner = current;
            }
          }
          if (partner != null) {
            out = out == null ? new ArrayList<>() : out;
            out.add(conflict(partner, next));
          }
        }
        active.add(next);
      }
      return out == null ? List.of() : out;
    }

    /** 对向互斥：不同方向（或方向未知）的占用不能重叠；同向追踪交给边互斥。 */
    private List<Conflict> scanDirectional(List<Occupation> sorted, int separation, Active active) {
      List<Conflict> out = null;
      for (int i = 0; i < sorted.size(); i++) {
        Occupation next = sorted.get(i);
        active.expire(separation, next.from());
        for (int k = 0; k < active.size(); k++) {
          Occupation current = active.get(k);
          boolean opposite =
              current.direction() == 0
                  || next.direction() == 0
                  || current.direction() != next.direction();
          if (opposite && !current.sameVehicle(next) && !current.bothExternal(next)) {
            out = out == null ? new ArrayList<>() : out;
            out.add(conflict(current, next));
            break;
          }
        }
        active.add(next);
      }
      return out == null ? List.of() : out;
    }

    private Conflict conflict(Occupation first, Occupation second) {
      return new Conflict(
          kind,
          key,
          first.code(),
          second.code(),
          first.from(),
          first.to(),
          second.from(),
          second.to(),
          first.owner(),
          second.owner());
    }
  }
}
