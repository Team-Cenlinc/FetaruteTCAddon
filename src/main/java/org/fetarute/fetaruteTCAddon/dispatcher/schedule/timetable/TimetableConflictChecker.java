package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

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
 * <p>每次停靠同时登记两层站台占用：站台组一层检验车辆与允许股道之间是否存在可行分配，具体股道一层抓固定股道互斥。 DYNAMIC
 * 停靠只有组一层，但携带实际股道允许集合；同一车辆连续停留取各段允许集合的交集。
 *
 * <p>本类刻意<b>不</b>建模的东西：授权窗口、制动距离扩展的 lookahead、恢复链。那些属于真调度器；回放如果发现本模型漏了约束， 修的是本模型，不是让 build 去依赖回放。
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
   * @return 这次投影碰到的资源键，连同每个资源上这批占用覆盖的时段——增量替换靠它知道该重扫哪些资源、重扫哪一段
   */
  static Map<String, Window> projectInto(
      Map<String, Resource> resources,
      GraphIndex index,
      Map<UUID, RouteProfile> profiles,
      List<Movement> movements,
      List<Stay> stays,
      Function<String, String> vehicleOf) {
    return projectInto(
        resources,
        new Footprints(index == null ? GraphIndex.of(null) : index),
        profiles,
        movements,
        stays,
        vehicleOf);
  }

  /**
   * 投影，足迹由调用方缓存：{@link OccupationIndex} 在修复循环里一次次换车，同一条 route 的足迹只算一次。
   *
   * @param footprints 足迹缓存；只能配同一份 {@code profiles} 使用
   */
  static Map<String, Window> projectInto(
      Map<String, Resource> resources,
      Footprints footprints,
      Map<UUID, RouteProfile> profiles,
      List<Movement> movements,
      List<Stay> stays,
      Function<String, String> vehicleOf) {
    Objects.requireNonNull(profiles, "profiles");
    Objects.requireNonNull(footprints, "footprints");
    Sink sink =
        new Sink(
            resources,
            new LinkedHashMap<>(),
            vehicleOf == null ? Function.identity() : vehicleOf,
            footprints);
    for (Movement movement : movements == null ? List.<Movement>of() : movements) {
      RouteProfile profile = profiles.get(movement.routeId());
      if (profile == null) {
        continue;
      }
      project(movement, footprints.of(movement.routeId(), profile), sink);
    }
    for (Stay stay : stays == null ? List.<Stay>of() : stays) {
      addPlatform(sink, stay.platform(), stay.code(), stay.from(), stay.to(), stay.owner());
    }
    Map<String, Window> touched = new LinkedHashMap<>();
    sink.touched().forEach((key, span) -> touched.put(key, new Window(span[0], span[1])));
    return touched;
  }

  /**
   * 一批占用在某个资源上覆盖的时段 {@code [from, to]}。
   *
   * <p>改动这批占用只会改变"后到占用在 {@code [from, to + separation]} 内进入"的那些冲突：更早进入的，在场的只有进入得更早的占用，
   * 里面没有这一批；更晚进入的，这一批在它进入前已经腾空（在场判据是 {@code to + separation > 进入时刻}）。增量重扫因此只扫这一段。
   */
  record Window(int from, int to) {

    /** 受影响的后到占用最晚在这一刻进入（含）。 */
    int affectedUntil(int separation) {
      return (int) Math.min(Integer.MAX_VALUE, (long) to + Math.max(0, separation));
    }

    /** 这条冲突是否落在受影响的时段里：按后到占用的进入时刻判。 */
    boolean covers(Conflict conflict, int separation) {
      return conflict.secondFrom() >= from && conflict.secondFrom() <= affectedUntil(separation);
    }

    Window merge(Window other) {
      return new Window(Math.min(from, other.from), Math.max(to, other.to));
    }
  }

  /** 扫描：给定资源逐个扫，合成一张报告。 */
  static Report scanResources(Collection<Resource> resources, int separationSeconds) {
    int separation = Math.max(0, separationSeconds);
    List<Conflict> conflicts = new ArrayList<>();
    Active active = new Active();
    for (Resource resource : resources) {
      conflicts.addAll(resource.scan(separation, active, Integer.MIN_VALUE, Integer.MAX_VALUE));
    }
    return report(conflicts);
  }

  /**
   * 在现有投影上试放一段站台等待，不修改原资源桶。
   *
   * <p>只复制这段等待涉及的站台资源及其窗口内占用，继续使用同一套车辆身份、股道资格、容量与安全裕量判据。 其它时刻或其它股道上的冲突不能替这段等待判失败。
   */
  static boolean accommodatesStay(
      Map<String, Resource> resources,
      Footprints footprints,
      Stay waiting,
      Function<String, String> vehicleOf,
      int separationSeconds) {
    Map<String, Resource> candidate = new LinkedHashMap<>();
    Map<String, Window> windows =
        projectInto(candidate, footprints, Map.of(), List.of(), List.of(waiting), vehicleOf);
    mergeOverlapping(candidate, resources, separationSeconds);
    return scanWindows(candidate, windows, separationSeconds).conflicts().isEmpty();
  }

  /**
   * 在现有投影上试放一段站台等待，只取它<b>新造成</b>的冲突：同一窗口分别扫"有这段等待"与"没有这段等待"两次，被挡住的占用只在前一次出现才算。
   *
   * <p>途中停车点的等待用它：那辆车本来就停在这股道上（原占用到车尾离开为止），按原时刻也挡得住的车、别的车之间原有的冲突都另有记录，
   * 不是多等造成的。这段等待自己作为后到占用被挡的那一条也不算——它与本车原占用接在一起，那一刻在场的车按原时刻同样在场。
   *
   * @return 新增的冲突，按 {@link #CONFLICT_ORDER}；每条的后到占用就是被这段等待新挡住的那辆车
   */
  static List<Conflict> conflictsAddedBy(
      Map<String, Resource> resources,
      Footprints footprints,
      Stay waiting,
      Function<String, String> vehicleOf,
      int separationSeconds) {
    Map<String, Resource> candidate = new LinkedHashMap<>();
    Map<String, Window> windows =
        projectInto(candidate, footprints, Map.of(), List.of(), List.of(waiting), vehicleOf);
    Map<String, List<Occupation>> own = new LinkedHashMap<>();
    candidate.forEach((key, resource) -> own.put(key, List.copyOf(resource.occupations())));
    mergeOverlapping(candidate, resources, separationSeconds);
    List<Conflict> with = scanWindows(candidate, windows, separationSeconds).conflicts();
    if (with.isEmpty()) {
      return List.of();
    }
    own.forEach((key, gone) -> candidate.get(key).removeAll(gone));
    Set<Blocked> before = new HashSet<>();
    for (Conflict conflict : scanWindows(candidate, windows, separationSeconds).conflicts()) {
      before.add(Blocked.of(conflict));
    }
    List<Conflict> added = new ArrayList<>();
    for (Conflict conflict : with) {
      boolean itself =
          conflict.second().equals(waiting.code())
              && conflict.secondFrom() == waiting.from()
              && conflict.secondOwner().equals(waiting.owner());
      if (!itself && !before.contains(Blocked.of(conflict))) {
        added.add(conflict);
      }
    }
    return List.copyOf(added);
  }

  /** 一条冲突里被挡住的那条占用：每条后到占用在一个资源上至多报一次，用它认"同一处冲突"。 */
  private record Blocked(String resource, String code, int from, int to, Optional<String> owner) {
    static Blocked of(Conflict conflict) {
      return new Blocked(
          conflict.resource(),
          conflict.second(),
          conflict.secondFrom(),
          conflict.secondTo(),
          conflict.secondOwner());
    }
  }

  /** 将试放足迹涉及的资源与时窗从基础投影复制过来；只修改试放桶，可用于等待容量和补车成本比较。 */
  static void mergeOverlapping(
      Map<String, Resource> candidate, Map<String, Resource> resources, int separationSeconds) {
    for (Map.Entry<String, Resource> entry : candidate.entrySet()) {
      Resource existing = resources.get(entry.getKey());
      if (existing != null) {
        int from =
            entry.getValue().occupations().stream().mapToInt(Occupation::from).min().orElse(0);
        int to =
            entry.getValue().occupations().stream().mapToInt(Occupation::to).max().orElse(from);
        entry.getValue().addOverlapping(existing, from, to, separationSeconds);
      }
    }
  }

  /**
   * 只扫各资源上受影响的时段（见 {@link Window}）：报出后到占用在窗内进入的冲突，窗外的冲突不报也不会变。
   *
   * @param resources 资源桶
   * @param windows 资源键 → 改动覆盖的时段；表里没有的键忽略
   * @param separationSeconds 相邻占用之间的最小间隔
   */
  static Report scanWindows(
      Map<String, Resource> resources, Map<String, Window> windows, int separationSeconds) {
    int separation = Math.max(0, separationSeconds);
    List<Conflict> conflicts = new ArrayList<>();
    Active active = new Active();
    for (Map.Entry<String, Window> entry : windows.entrySet()) {
      Resource resource = resources.get(entry.getKey());
      if (resource == null) {
        continue;
      }
      Window window = entry.getValue();
      conflicts.addAll(
          resource.scan(separation, active, window.from(), window.affectedUntil(separation)));
    }
    return report(conflicts);
  }

  /**
   * 扫描时"仍在场"的占用，一个可复用的缓冲。
   *
   * <p>不用"每个资源新建一个 {@code ArrayList}、每条占用调一次 {@code removeIf}"：那个 lambda 捕获了后车， 于是<b>每条占用都要新造一个
   * lambda 对象</b>。全表扫一遍还能忍，增量重扫一次 attempt 要扫数以百万计的资源桶， 这些短命对象就成了主要开销。这里用手写的原地压缩，扫描本身一个对象都不分配。
   */
  private static final class Active {
    private Occupation[] items = new Occupation[16];
    private int size;

    void reset() {
      size = 0;
    }

    /** 送走已经腾空的：{@code to + separation <= from} 的不再在场。 */
    void expire(int separation, int from) {
      int kept = 0;
      for (int i = 0; i < size; i++) {
        if ((long) items[i].to() + separation > from) {
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
   * <p>不能只比 {@code (firstFrom, resource, first, second)}、其余靠资源遍历序与桶内插入序兜底：增量重扫两样都保不住——
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
      Map<String, int[]> touched,
      Function<String, String> vehicleOf,
      Footprints footprints) {

    void add(
        String key,
        Kind kind,
        int capacity,
        String code,
        int from,
        int to,
        int direction,
        Optional<String> owner,
        String vehicle,
        Optional<PlatformEligibility> eligibility) {
      // 记下这批占用在该资源上覆盖的时段 [from, to]；to 的取法与 Resource.add 一致。
      int[] span =
          touched.computeIfAbsent(key, k -> new int[] {Integer.MAX_VALUE, Integer.MIN_VALUE});
      span[0] = Math.min(span[0], from);
      span[1] = Math.max(span[1], Math.max(from, to));
      resources
          .computeIfAbsent(key, k -> new Resource(k, kind, capacity))
          .add(code, from, to, direction, owner, vehicle, eligibility);
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
      boolean declaredDynamic = routeStop != null && DynamicStopMatcher.isDynamicStop(routeStop);
      boolean dynamic =
          declaredDynamic && !DynamicStopMatcher.isFixedDepotDirectiveTarget(nodeId, routeStop);
      if (!dynamic && nodeTypes != null && !nodeTypes.isEmpty() && !nodeId.isBlank()) {
        NodeType type = nodeTypes.get(NodeId.of(nodeId));
        if (type != NodeType.STATION && type != NodeType.DEPOT) {
          out.add(Platform.none());
          continue;
        }
      }
      Optional<DynamicStopMatcher.DynamicSpec> parsed =
          declaredDynamic ? DynamicStopMatcher.parseDynamicSpec(routeStop) : Optional.empty();
      if (declaredDynamic && parsed.isEmpty()) {
        throw new IllegalArgumentException("无效 DYNAMIC 停靠: " + routeStop.notes().orElse(""));
      }
      Optional<DynamicStopMatcher.DynamicSpec> spec = dynamic ? parsed : Optional.empty();
      String group =
          spec.map(value -> value.operatorCode() + ":" + value.nodeType() + ":" + value.nodeName())
              .orElseGet(() -> groupOf(nodeId));
      out.add(new Platform(nodeId, group, dynamic, spec));
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

  /**
   * 一条 route 的占用足迹：各资源上相对发车的进入与离开偏移，按投影的调用顺序排列。
   *
   * <p>一次构建里路网与 route 的投影都不变：足迹只算一次，投影一趟车就是把它平移到这趟车的发车时刻。 否则每投影一趟车都要现拼资源键、按名字切站台组、
   * 查单线区段，而修复循环每换一次车，撤掉的与新加的班次都要各投影一遍，这部分开销相当可观。
   *
   * @param slots 各格，顺序即投影顺序
   */
  record Footprint(List<Slot> slots) {
    Footprint {
      slots = slots == null ? List.of() : List.copyOf(slots);
    }
  }

  /**
   * 足迹里的一格。
   *
   * @param key 资源键
   * @param kind 资源类型
   * @param capacity 容量
   * @param from 相对发车的进入偏移
   * @param to 相对发车的离开偏移
   * @param direction 单线方向（其余资源为 0）
   */
  record Slot(
      String key,
      Kind kind,
      int capacity,
      int from,
      int to,
      int direction,
      Optional<PlatformEligibility> eligibility) {
    Slot(String key, Kind kind, int capacity, int from, int to, int direction) {
      this(key, kind, capacity, from, to, direction, Optional.empty());
    }
  }

  /**
   * 图上实际可用的股道集合。排序键随集合一次生成，增量撤销占用时可精确比较，避免扫描时重复拼接。
   *
   * @param nodes 可用节点的稳定排序列表；空集合意味着该停靠无合法股道
   * @param orderKey 集合的全序键
   * @param restricted 允许集合是否小于该站实际股道数（{@link DynamicStopMatcher#restricts}）
   */
  record PlatformEligibility(List<String> nodes, String orderKey, boolean restricted) {
    PlatformEligibility {
      nodes = List.copyOf(nodes);
    }

    static PlatformEligibility of(List<String> nodes, boolean restricted) {
      List<String> sorted = nodes.stream().distinct().sorted().toList();
      return new PlatformEligibility(sorted, String.join("\n", sorted), restricted);
    }
  }

  /** 足迹缓存：按 route 记住。只能配同一份 {@code profiles} 与图索引用（一次全扫、一个 {@link OccupationIndex} 的生命周期）。 */
  static final class Footprints {
    private final GraphIndex index;
    private final Map<UUID, Footprint> byRoute = new HashMap<>();
    private final Map<String, List<NodeId>> nodesByGroup = new HashMap<>();
    private final Map<Platform, Optional<PlatformEligibility>> eligibilityByPlatform =
        new HashMap<>();

    Footprints(GraphIndex index) {
      this.index = Objects.requireNonNull(index, "index");
      index
          .nodeTypes()
          .forEach(
              (node, type) -> {
                if ((type == NodeType.STATION || type == NodeType.DEPOT)
                    && node.value().split(":", -1).length == 4) {
                  String group = groupOf(node.value()).toLowerCase(Locale.ROOT);
                  nodesByGroup.computeIfAbsent(group, key -> new ArrayList<>()).add(node);
                }
              });
    }

    GraphIndex index() {
      return index;
    }

    Footprint of(UUID routeId, RouteProfile profile) {
      return byRoute.computeIfAbsent(routeId, id -> footprintOf(profile, this));
    }

    /** 同一份图与停靠定义只求一次资格；headway 修复与车辆重投影复用结果。 */
    Optional<PlatformEligibility> eligibility(Platform platform) {
      Optional<PlatformEligibility> cached = eligibilityByPlatform.get(platform);
      if (cached != null) {
        return cached;
      }
      Optional<PlatformEligibility> result = computeEligibility(platform);
      eligibilityByPlatform.put(platform, result);
      return result;
    }

    private Optional<PlatformEligibility> computeEligibility(Platform key) {
      List<NodeId> group = nodesByGroup.get(key.group().toLowerCase(Locale.ROOT));
      if (group == null) {
        return Optional.empty();
      }
      List<String> nodes;
      boolean restricted;
      if (!key.requirements().isEmpty()) {
        Set<String> intersection = new LinkedHashSet<>(group.stream().map(NodeId::value).toList());
        for (Platform requirement : key.requirements()) {
          eligibility(requirement).ifPresent(value -> intersection.retainAll(value.nodes()));
        }
        nodes = List.copyOf(intersection);
        restricted = nodes.size() < stationTrackCount(group);
      } else if (!key.dynamic()) {
        if (key.nodeId().split(":", -1).length != 4) {
          return Optional.empty();
        }
        nodes = List.of(key.nodeId());
        restricted = nodes.size() < stationTrackCount(group);
      } else if (key.spec().isPresent()) {
        // 与运行时选台同一份候选（同样只评估数字顺序前 20 条）；受限与否按完整允许集合判断，不受评估上限影响。
        nodes =
            DynamicStopMatcher.matchingNodes(key.spec().get(), group).stream()
                .limit(DynamicStopMatcher.MAX_TRACK_CANDIDATES)
                .map(NodeId::value)
                .toList();
        restricted = DynamicStopMatcher.restricts(key.spec().get(), group);
      } else {
        nodes = group.stream().map(NodeId::value).toList();
        restricted = false;
      }
      return Optional.of(PlatformEligibility.of(nodes, restricted));
    }
  }

  /** 站台组里的数字股道数：“受限”与它比较，与 {@link DynamicStopMatcher#restricts} 同一口径。 */
  private static int stationTrackCount(List<NodeId> group) {
    return (int)
        group.stream().filter(node -> DynamicStopMatcher.trackNumber(node).isPresent()).count();
  }

  /** 把足迹平移到这趟车的发车时刻落进资源桶。 */
  private static void project(Movement movement, Footprint footprint, Sink sink) {
    int base = movement.startSeconds();
    String vehicle = vehicleKey(movement.code(), movement.owner(), sink.vehicleOf());
    for (Slot slot : footprint.slots()) {
      sink.add(
          slot.key(),
          slot.kind(),
          slot.capacity(),
          movement.code(),
          base + slot.from(),
          base + slot.to(),
          slot.direction(),
          movement.owner(),
          vehicle,
          slot.eligibility());
    }
  }

  /**
   * 一条 route 的足迹：边互斥、穿越的道岔与车站、单线区段、中间停靠的站台，顺序与投影一致。
   *
   * <p>档案带车尾出清（{@link RouteProfile#tailBlocks()}）时，每个资源的离开时刻改用车尾离开它的时刻（{@link TailClock}）。
   */
  private static Footprint footprintOf(RouteProfile profile, Footprints footprints) {
    GraphIndex index = footprints.index();
    List<Slot> slots = new ArrayList<>();
    SingleLineSectionIndex sections = index.sections();
    Map<NodeId, NodeType> nodeTypes = index.nodeTypes();
    TailClock tail = TailClock.of(profile);
    double segmentStart = 0.0;
    for (TimetableTimingCalculator.SegmentTiming segment : profile.segments()) {
      List<RailEdge> edges = segment.edges();
      double[] nodePosition = new double[segment.nodes().size()];
      nodePosition[0] = segmentStart;
      for (int k = 0; k < edges.size(); k++) {
        nodePosition[k + 1] = nodePosition[k] + edges.get(k).lengthBlocks();
      }
      segmentStart = nodePosition[nodePosition.length - 1];
      // 边：互斥。
      for (int k = 0; k < edges.size(); k++) {
        slots.add(
            new Slot(
                edgeKey(edges.get(k)),
                Kind.TRACK,
                1,
                segment.enterOffset(k),
                tail.leaves(nodePosition[k + 1], segment.exitOffset(k)),
                0));
      }
      // 路径中间穿越的节点：道岔两次通过之间要留间隔；不停靠而经过的车站股道也是一次占用——
      // 一辆在单股道车站待命的车必须能挡住从它身上碾过去的对向车。
      List<NodeId> nodes = segment.nodes();
      for (int k = 1; k + 1 < nodes.size(); k++) {
        NodeId node = nodes.get(k);
        NodeType type = nodeTypes.get(node);
        int at = segment.nodeOffsets().get(k);
        int clear = tail.leaves(nodePosition[k], at);
        if (type == NodeType.SWITCHER) {
          slots.add(new Slot(junctionKey(node), Kind.JUNCTION, 1, at, clear, 0));
        } else if (type == NodeType.STATION) {
          addPlatformSlots(
              slots,
              new Platform(node.value(), groupOf(node.value()), false),
              at,
              clear,
              footprints);
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
            exit = tail.leaves(nodePosition[k + 1], segment.exitOffset(k));
            continue;
          }
          if (currentKey != null) {
            slots.add(
                new Slot(singleLineKey(currentKey), Kind.SINGLE_LINE, 1, enter, exit, direction));
          }
          currentKey = key;
          current = info.orElse(null);
          if (key != null) {
            enter = segment.enterOffset(k);
            exit = tail.leaves(nodePosition[k + 1], segment.exitOffset(k));
            direction = directionOf(current, nodes.get(k), nodes.get(k + 1));
          }
        }
        if (currentKey != null) {
          slots.add(
              new Slot(singleLineKey(currentKey), Kind.SINGLE_LINE, 1, enter, exit, direction));
        }
      }
    }
    // 中间停靠：站台按容量。起终点的占用由 Stay 负责（它跨越到站、折返、再发车的整段）。
    List<TimetableStop> stops = profile.stops();
    for (int i = 1; i + 1 < stops.size(); i++) {
      TimetableStop stop = stops.get(i);
      Platform platform = i < profile.platforms().size() ? profile.platforms().get(i) : null;
      addPlatformSlots(
          slots,
          platform,
          stop.arrivalOffsetSeconds(),
          tail.leavesStop(i, stop.departureOffsetSeconds()),
          footprints);
    }
    return new Footprint(slots);
  }

  /**
   * 各停靠点上车尾离开站台的时刻（相对发车）：与足迹里中间停靠那一格的离开同一口径，档案不带车尾出清时就是表定发车。
   *
   * @param profile route 档案
   * @return 按停靠序号排列
   */
  static List<Integer> stopLeaveOffsets(RouteProfile profile) {
    TailClock tail = TailClock.of(profile);
    List<TimetableStop> stops = profile.stops();
    List<Integer> out = new ArrayList<>(stops.size());
    for (int i = 0; i < stops.size(); i++) {
      out.add(tail.leavesStop(i, stops.get(i).departureOffsetSeconds()));
    }
    return List.copyOf(out);
  }

  /**
   * 车尾时钟：车尾经过里程 {@code p} 的时刻 = 车头走到 {@code p + L} 的时刻（{@code L} 为车尾出清长度）。
   *
   * <p>车头的"里程—时刻"由各段节点时刻连成一条折线，停站时里程不动、时刻前进（到站 → 发车）；两节点之间按里程线性插值。 车头走到 {@code p + L}
   * 之前就到了终点的，取终点到达时刻：终点待命（Stay）只占站台，离终点不足 {@code L} 的道岔在待命期间不算占用，编表报告对这种布置单独警告。 {@code L} 为 0
   * 时一律返回原时刻。
   */
  private static final class TailClock {
    private static final TailClock NONE = new TailClock(new double[0], new int[0], 0.0, Map.of());

    private final double[] positions;
    private final int[] times;
    private final double tail;
    private final Map<Integer, Double> stopPositions;

    private TailClock(
        double[] positions, int[] times, double tail, Map<Integer, Double> stopPositions) {
      this.positions = positions;
      this.times = times;
      this.tail = tail;
      this.stopPositions = stopPositions;
    }

    static TailClock of(RouteProfile profile) {
      if (profile.tailBlocks() <= 0.0 || profile.segments().isEmpty()) {
        return NONE;
      }
      List<Double> positions = new ArrayList<>();
      List<Integer> times = new ArrayList<>();
      Map<Integer, Double> stopPositions = new java.util.HashMap<>();
      double start = 0.0;
      for (TimetableTimingCalculator.SegmentTiming segment : profile.segments()) {
        double position = start;
        stopPositions.putIfAbsent(segment.fromStop(), position);
        for (int k = 0; k < segment.nodes().size(); k++) {
          if (k > 0) {
            position += segment.edges().get(k - 1).lengthBlocks();
          }
          positions.add(position);
          times.add(segment.nodeOffsets().get(k));
        }
        stopPositions.put(segment.toStop(), position);
        start = position;
      }
      double[] p = new double[positions.size()];
      int[] t = new int[times.size()];
      for (int i = 0; i < p.length; i++) {
        p[i] = positions.get(i);
        t[i] = times.get(i);
      }
      return new TailClock(p, t, profile.tailBlocks(), Map.copyOf(stopPositions));
    }

    /**
     * 车尾离开里程 {@code position} 的时刻，不早于车头离开的时刻。
     *
     * @param position 资源所在里程（从首站起算）
     * @param headLeaves 车头离开的时刻
     */
    int leaves(double position, int headLeaves) {
      if (tail <= 0.0) {
        return headLeaves;
      }
      return Math.max(headLeaves, headReaches(position + tail));
    }

    /** 途中第 {@code stopIndex} 个停靠点：车尾在车头发车后再走完车尾出清长度才离开站台。 */
    int leavesStop(int stopIndex, int headDeparts) {
      Double position = stopPositions.get(stopIndex);
      return position == null ? headDeparts : leaves(position, headDeparts);
    }

    private int headReaches(double target) {
      for (int i = 0; i < positions.length; i++) {
        if (positions[i] >= target) {
          if (i == 0) {
            return times[0];
          }
          double span = positions[i] - positions[i - 1];
          if (span <= 0.0) {
            return times[i];
          }
          double fraction = (target - positions[i - 1]) / span;
          return (int) Math.ceil(times[i - 1] + fraction * (times[i] - times[i - 1]));
        }
      }
      return times[times.length - 1];
    }
  }

  private static void addPlatformSlots(
      List<Slot> slots, Platform platform, int from, int to, Footprints footprints) {
    if (platform == null) {
      return;
    }
    for (String key : platformKeys(platform)) {
      slots.add(
          new Slot(
              key,
              Kind.PLATFORM,
              platformCapacityOf(key, platform, footprints.index().platformCapacity()),
              from,
              to,
              0,
              key.startsWith("platform-group:")
                  ? footprints.eligibility(platform)
                  : Optional.empty()));
    }
  }

  /** 站台键的容量：站台组一层取图里该站的股道数，具体股道一层是 1。 */
  private static int platformCapacityOf(
      String key, Platform platform, Map<String, Integer> platformCapacity) {
    return key.startsWith("platform-group:")
        ? Math.max(1, platformCapacity.getOrDefault(platform.group(), 1))
        : 1;
  }

  private static void addPlatform(
      Sink sink, Platform platform, String code, int from, int to, Optional<String> owner) {
    if (platform == null) {
      return;
    }
    String vehicle = vehicleKey(code, owner, sink.vehicleOf());
    for (String key : platformKeys(platform)) {
      sink.add(
          key,
          Kind.PLATFORM,
          platformCapacityOf(key, platform, sink.footprints().index().platformCapacity()),
          code,
          from,
          to,
          0,
          owner,
          vehicle,
          key.startsWith("platform-group:")
              ? sink.footprints().eligibility(platform)
              : Optional.empty());
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
   * @param tailBlocks 车尾出清多算的长度（格）：区间、道岔、途中站台的离开时刻推迟到车头再走这么远之后。 只跑一种车型时为 0，足迹与从前逐字相同
   */
  public record RouteProfile(
      UUID routeId,
      String routeCode,
      List<TimetableStop> stops,
      List<TimetableTimingCalculator.SegmentTiming> segments,
      List<Platform> platforms,
      double tailBlocks) {

    /** 不算车尾出清的档案。 */
    public RouteProfile(
        UUID routeId,
        String routeCode,
        List<TimetableStop> stops,
        List<TimetableTimingCalculator.SegmentTiming> segments,
        List<Platform> platforms) {
      this(routeId, routeCode, stops, segments, platforms, 0.0);
    }

    public RouteProfile {
      Objects.requireNonNull(routeId, "routeId");
      routeCode = routeCode == null ? "" : routeCode;
      stops = stops == null ? List.of() : List.copyOf(stops);
      segments = segments == null ? List.of() : List.copyOf(segments);
      platforms = platforms == null ? List.of() : List.copyOf(platforms);
      tailBlocks = Double.isFinite(tailBlocks) && tailBlocks > 0.0 ? tailBlocks : 0.0;
    }

    /** 换一个 route ID 与车尾出清，时分不变（车型变体登记用）。 */
    public RouteProfile asVariant(UUID variantId, double variantTailBlocks) {
      return new RouteProfile(variantId, routeCode, stops, segments, platforms, variantTailBlocks);
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
   * @param spec 原始动态声明；外部旧档案可能只有站台组
   * @param requirements 连续停留合并后的原始资格要求；候选取交集
   */
  public record Platform(
      String nodeId,
      String group,
      boolean dynamic,
      Optional<DynamicStopMatcher.DynamicSpec> spec,
      List<Platform> requirements) {
    public Platform {
      nodeId = nodeId == null ? "" : nodeId.trim();
      group = group == null ? "" : group.trim();
      spec = spec == null ? Optional.empty() : spec;
      requirements = requirements == null ? List.of() : List.copyOf(requirements);
    }

    /** 带动态声明的停靠资源。 */
    public Platform(
        String nodeId,
        String group,
        boolean dynamic,
        Optional<DynamicStopMatcher.DynamicSpec> spec) {
      this(nodeId, group, dynamic, spec, List.of());
    }

    /** 兼容只有站台组的外部档案；没有声明时动态组按现有物理股道建模。 */
    public Platform(String nodeId, String group, boolean dynamic) {
      this(nodeId, group, dynamic, Optional.empty());
    }

    /** 占位：这个停靠点不是站台（路径点、道岔），不登记任何站台资源。 */
    public static Platform none() {
      return new Platform("", "", false);
    }

    /**
     * 同站连续停留必须同时满足到站与下一班始发的股道资格；保持全部原始要求，图索引建候选时取交集。 不同站台组不能合并；交路衔接的站点一致性仍由构建校验负责。
     *
     * <p>两端都是固定股道时不合并，只保留到站股道：两条不同的固定股道交集必然为空，会让现有交路编表必然失败。
     */
    public Platform intersect(Platform other) {
      if (other == null
          || !group.equalsIgnoreCase(other.group())
          || equals(other)
          || (!dynamic && !other.dynamic())) {
        return this;
      }
      List<Platform> combined = new ArrayList<>();
      combined.addAll(requirements.isEmpty() ? List.of(this) : requirements);
      combined.addAll(other.requirements().isEmpty() ? List.of(other) : other.requirements());
      return new Platform(nodeId, group, true, Optional.empty(), combined);
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
      String code,
      int from,
      int to,
      int direction,
      Optional<String> owner,
      String vehicle,
      Optional<PlatformEligibility> eligibility) {

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
   * <p>不能只比 {@code (from, code)}、并列靠插入序兜底：增量索引换一辆车会把它的占用挪到桶尾，插入序保不住——
   * 所以把剩下的字段也比完。并列的两条占用字段全同（{@code vehicle} 里带着 owner），取哪一条报出来都是同一条 {@link Conflict}，
   * 插在并列的哪一侧也就无所谓。
   */
  private static final Comparator<Occupation> OCCUPATION_ORDER =
      Comparator.comparingInt(Occupation::from)
          .thenComparing(Occupation::code)
          .thenComparingInt(Occupation::to)
          .thenComparingInt(Occupation::direction)
          .thenComparing(Occupation::vehicle)
          .thenComparing(
              value -> value.eligibility().map(PlatformEligibility::orderKey).orElse(""));

  static final class Resource {
    private final String key;
    private final Kind kind;
    private final int capacity;
    private final List<Occupation> occupations = new ArrayList<>();

    /** 桶里出现过的最长一条占用；撤掉占用时不回缩——偏大只会让窗口扫描多扫几条，不会漏。 */
    private int maxSpan;

    private boolean hasRestrictedEligibility;

    private Resource(String key, Kind kind, int capacity) {
      this.key = key;
      this.kind = kind;
      this.capacity = Math.max(1, capacity);
    }

    /** 按 {@link #OCCUPATION_ORDER} 插进去，桶因此始终有序：扫描不必再排，增量重扫每步省掉一次全桶排序。 */
    private void add(
        String code,
        int from,
        int to,
        int direction,
        Optional<String> owner,
        String vehicle,
        Optional<PlatformEligibility> eligibility) {
      Occupation occupation =
          new Occupation(code, from, Math.max(from, to), direction, owner, vehicle, eligibility);
      hasRestrictedEligibility |= eligibility.map(PlatformEligibility::restricted).orElse(false);
      int at = Collections.binarySearch(occupations, occupation, OCCUPATION_ORDER);
      occupations.add(at < 0 ? -at - 1 : at, occupation);
      maxSpan = Math.max(maxSpan, occupation.to() - occupation.from());
    }

    /** 桶里的占用，按 {@link #OCCUPATION_ORDER} 有序。{@link OccupationIndex} 拿它来按值精确撤销。 */
    List<Occupation> occupations() {
      return occupations;
    }

    /** 将等待窗口内仍占用或仍处于安全裕量中的记录复制进试放桶，原桶保持不变。 */
    private void addOverlapping(Resource existing, int from, int to, int separation) {
      int margin = Math.max(0, separation);
      for (Occupation value : existing.occupations) {
        if ((long) value.from() > (long) to + margin) {
          break;
        }
        if ((long) value.to() + margin >= from) {
          add(
              value.code(),
              value.from(),
              value.to(),
              value.direction(),
              value.owner(),
              value.vehicle(),
              value.eligibility());
        }
      }
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

    /**
     * 扫这个桶，只报后到占用在 {@code [lo, hi]} 内进入的冲突；全扫传 {@code MIN_VALUE, MAX_VALUE}。
     *
     * <p>从"之前的占用在 {@code lo} 时一定已经腾空"的位置起扫：进入早于 {@code lo − maxSpan − separation} 的占用， 离开 + 裕量也早于
     * {@code lo}。这样扫到窗内时在场集合的内容与顺序都与从头扫一致，报出的冲突逐字相同。
     */
    private List<Conflict> scan(int separation, Active active, int lo, int hi) {
      active.reset();
      int start =
          lo == Integer.MIN_VALUE ? 0 : firstEnteringAtOrAfter((long) lo - maxSpan - separation);
      return switch (kind) {
        case TRACK, JUNCTION -> scanExclusive(occupations, separation, active, start, lo, hi);
        case PLATFORM -> scanCapacity(occupations, separation, active, start, lo, hi);
        case SINGLE_LINE -> scanDirectional(occupations, separation, active, start, lo, hi);
      };
    }

    /** 第一条进入时刻不早于 {@code bound} 的占用的下标；桶按进入时刻有序。 */
    private int firstEnteringAtOrAfter(long bound) {
      int low = 0;
      int high = occupations.size();
      while (low < high) {
        int mid = (low + high) >>> 1;
        if (occupations.get(mid).from() < bound) {
          low = mid + 1;
        } else {
          high = mid;
        }
      }
      return low;
    }

    /**
     * 容量 1：后一个占用必须在前面所有占用结束 + separation 之后开始。
     *
     * <p>要和所有仍在场的占用比而不是只和最晚离开的那个比：两份邻表之间的重叠不报，若只看最晚的那个， 夹在中间的邻表占用会把我与后一份邻表的冲突挡掉。
     */
    private List<Conflict> scanExclusive(
        List<Occupation> sorted, int separation, Active active, int start, int lo, int hi) {
      List<Conflict> out = null;
      for (int i = start; i < sorted.size(); i++) {
        Occupation next = sorted.get(i);
        if (next.from() > hi) {
          break;
        }
        active.expire(separation, next.from());
        if (next.from() < lo) {
          active.add(next);
          continue;
        }
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
     * 容量 n：同时在场的车超过 n 就冲突；报的是新来的与在场里最先腾出股道的那辆<b>可归责</b>的车（同一辆车按它最晚离开的那条占用算）。
     *
     * <p>"可归责"指不是两份邻表之间的对：新来的若是邻表，就找在场的我；新来的若是我，在场里谁都算。 只拿在场最早的那个比会被两份邻表夹住——它们之间的重叠不报，我夹在中间的占用就被漏掉。
     */
    private List<Conflict> scanCapacity(
        List<Occupation> sorted, int separation, Active active, int start, int lo, int hi) {
      List<Conflict> out = null;
      for (int i = start; i < sorted.size(); i++) {
        Occupation next = sorted.get(i);
        if (next.from() > hi) {
          break;
        }
        active.expire(separation, next.from());
        if (next.from() < lo) {
          active.add(next);
          continue;
        }
        Optional<Occupation> partner = capacityConflict(active, next);
        if (partner.isEmpty() && hasRestrictedEligibility) {
          partner = eligibilityConflict(active, next);
        }
        if (partner.isPresent()) {
          out = out == null ? new ArrayList<>() : out;
          out.add(conflict(partner.get(), next));
        }
        active.add(next);
      }
      return out == null ? List.of() : out;
    }

    /** 无子集限制时保留容量快速路径，并把同一辆车的重复到发投影只计一次。 */
    private Optional<Occupation> capacityConflict(Active active, Occupation next) {
      if (active.size() < capacity) {
        return Optional.empty();
      }
      Set<String> vehicles = new HashSet<>();
      vehicles.add(next.vehicle());
      for (int i = 0; i < active.size(); i++) {
        vehicles.add(active.get(i).vehicle());
      }
      return vehicles.size() > capacity
          ? Optional.ofNullable(firstToClear(active, next, null))
          : Optional.empty();
    }

    /**
     * 容量满时后来的车等的是最先腾出的那一股道：同一辆车在场的几条占用（到站、待命、发车的重复投影）按它最晚离开的那条算——它走之前那股道一直被它占着；
     * 车与车之间取最先腾出的，同时腾出取先到的。
     *
     * @param among 只在这些车里取；为空时在场的可归责车辆都算
     */
    private static Occupation firstToClear(Active active, Occupation next, Set<String> among) {
      Map<String, Occupation> lastByVehicle = new LinkedHashMap<>();
      for (int i = 0; i < active.size(); i++) {
        Occupation current = active.get(i);
        if (current.sameVehicle(next)
            || current.bothExternal(next)
            || (among != null && !among.contains(current.vehicle()))) {
          continue;
        }
        lastByVehicle.merge(
            current.vehicle(), current, (kept, other) -> clearsEarlier(kept, other) ? other : kept);
      }
      Occupation partner = null;
      for (Occupation candidate : lastByVehicle.values()) {
        if (partner == null || clearsEarlier(candidate, partner)) {
          partner = candidate;
        }
      }
      return partner;
    }

    /** {@code a} 比 {@code b} 先腾出：离开更早，同时离开时到得更早。 */
    private static boolean clearsEarlier(Occupation a, Occupation b) {
      return a.to() < b.to() || (a.to() == b.to() && a.from() < b.from());
    }

    /**
     * 对每辆车与允许股道做二分匹配，捕获容量总数看不出的共享子集瓶颈。
     *
     * <p>同一车辆时间相交的到站、待命、发车合并为停留并取允许集合交集；先后停留仍在安全裕量内时，分别保留对其它车辆的股道限制。
     * 不能将先后停留取空交集，否则前车无法进入匹配，反而会漏报新到车辆的真实争用。这里验证瞬时可分配性；整段固定股道由 {@link
     * TimetablePlatformPlanner.Result#requireFeasibleRestrictedAssignments()} 在编表保存前保证。
     * 先匹配仍在场车辆，再插入本次车辆； 失败的增广路径给出真实争用股道的车辆，只向它们归责。未知图的外部档案仍按容量判断。
     */
    private Optional<Occupation> eligibilityConflict(Active active, Occupation next) {
      if (next.eligibility().isEmpty()) {
        return capacityConflict(active, next);
      }
      record Visit(Occupation representative, int end) {}
      Map<Occupation, Set<String>> candidates = new LinkedHashMap<>();
      Map<String, Visit> latestVisits = new HashMap<>();
      for (int i = 0; i <= active.size(); i++) {
        Occupation value = i == active.size() ? next : active.get(i);
        // 同车已经离开的停靠只保留对其他车辆的安全裕量，不要求本车在两个先后股道同时停留。
        if (value.sameVehicle(next) && value.to() < next.from()) {
          continue;
        }
        if (value.eligibility().isEmpty()) {
          return capacityConflict(active, next);
        }
        Set<String> nodes = new LinkedHashSet<>(value.eligibility().get().nodes());
        Visit previous = latestVisits.get(value.vehicle());
        if (previous == null || previous.end() < value.from()) {
          candidates.put(value, nodes);
          latestVisits.put(value.vehicle(), new Visit(value, value.to()));
        } else {
          candidates.get(previous.representative()).retainAll(nodes);
          latestVisits.put(
              value.vehicle(),
              new Visit(previous.representative(), Math.max(previous.end(), value.to())));
        }
      }
      Occupation targetVisit = latestVisits.get(next.vehicle()).representative();
      Map<String, Occupation> assigned = new HashMap<>();
      for (Occupation visit : candidates.keySet()) {
        if (!visit.equals(targetVisit)) {
          augment(visit, candidates, assigned, new HashSet<>(), new LinkedHashSet<>());
        }
      }
      Set<Occupation> blocked = new LinkedHashSet<>();
      if (augment(targetVisit, candidates, assigned, new HashSet<>(), blocked)) {
        return Optional.empty();
      }
      // 空交集意味着同一车辆的连续停留本身不可行；保留冲突使构建失败，而不是发布空计划。
      if (candidates.get(targetVisit).isEmpty()) {
        return Optional.of(targetVisit);
      }
      // 与容量路径同一口径：争用股道的车里按车取最晚离开，再取最先腾出的那辆。
      Set<String> contenders = new HashSet<>();
      for (Occupation value : blocked) {
        contenders.add(value.vehicle());
      }
      return Optional.ofNullable(firstToClear(active, next, contenders));
    }

    /** 允许重新分配尚未落实的计划股道；遍历节点集合受实际股道数与候选预算限制。 */
    private static boolean augment(
        Occupation visit,
        Map<Occupation, Set<String>> candidates,
        Map<String, Occupation> assigned,
        Set<String> seenNodes,
        Set<Occupation> seenVisits) {
      if (!seenVisits.add(visit)) {
        return false;
      }
      for (String node : candidates.get(visit)) {
        if (!seenNodes.add(node)) {
          continue;
        }
        Occupation owner = assigned.get(node);
        if (owner == null || augment(owner, candidates, assigned, seenNodes, seenVisits)) {
          assigned.put(node, visit);
          return true;
        }
      }
      return false;
    }

    /** 对向互斥：不同方向（或方向未知）的占用不能重叠；同向追踪交给边互斥。 */
    private List<Conflict> scanDirectional(
        List<Occupation> sorted, int separation, Active active, int start, int lo, int hi) {
      List<Conflict> out = null;
      for (int i = start; i < sorted.size(); i++) {
        Occupation next = sorted.get(i);
        if (next.from() > hi) {
          break;
        }
        active.expire(separation, next.from());
        if (next.from() < lo) {
          active.add(next);
          continue;
        }
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
