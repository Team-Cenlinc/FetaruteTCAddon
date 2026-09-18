package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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
    Objects.requireNonNull(profiles, "profiles");
    int separation = Math.max(0, separationSeconds);
    Map<String, Integer> platformCapacity = graph == null ? Map.of() : platformCapacity(graph);
    SingleLineSectionIndex sections =
        graph == null ? null : SingleLineSectionIndex.fromGraph(graph);
    Map<NodeId, NodeType> nodeTypes = graph == null ? Map.of() : nodeTypes(graph);

    Map<String, Resource> resources = new LinkedHashMap<>();
    for (Movement movement : movements == null ? List.<Movement>of() : movements) {
      RouteProfile profile = profiles.get(movement.routeId());
      if (profile == null) {
        continue;
      }
      project(movement, profile, resources, sections, nodeTypes, platformCapacity);
    }
    for (Stay stay : stays == null ? List.<Stay>of() : stays) {
      addPlatform(
          resources, platformCapacity, stay.platform(), stay.code(), stay.from(), stay.to());
    }

    List<Conflict> conflicts = new ArrayList<>();
    for (Resource resource : resources.values()) {
      conflicts.addAll(resource.scan(separation));
    }
    conflicts.sort(
        Comparator.comparingInt(Conflict::firstFrom)
            .thenComparing(Conflict::resource)
            .thenComparing(Conflict::first)
            .thenComparing(Conflict::second));
    return new Report(List.copyOf(conflicts));
  }

  /** 从 route 的停靠配置得出每个停靠点的站台资源。 */
  public static List<Platform> platformsOf(
      List<TimetableStop> stops, List<RouteStop> routeStops, List<NodeId> waypoints) {
    List<Platform> out = new ArrayList<>(stops.size());
    for (int i = 0; i < stops.size(); i++) {
      String nodeId = waypoints != null && i < waypoints.size() ? waypoints.get(i).value() : "";
      RouteStop routeStop = routeStops != null && i < routeStops.size() ? routeStops.get(i) : null;
      boolean dynamic = routeStop != null && DynamicStopMatcher.isDynamicStop(routeStop);
      out.add(new Platform(nodeId, groupOf(nodeId), dynamic));
    }
    return List.copyOf(out);
  }

  private static void project(
      Movement movement,
      RouteProfile profile,
      Map<String, Resource> resources,
      SingleLineSectionIndex sections,
      Map<NodeId, NodeType> nodeTypes,
      Map<String, Integer> platformCapacity) {
    int base = movement.startSeconds();
    for (TimetableTimingCalculator.SegmentTiming segment : profile.segments()) {
      List<RailEdge> edges = segment.edges();
      // 边：互斥。
      for (int k = 0; k < edges.size(); k++) {
        RailEdge edge = edges.get(k);
        EdgeId edgeId = edge.id();
        String key = "edge:" + edgeId.a().value() + "~" + edgeId.b().value();
        resource(resources, key, Kind.TRACK, 1)
            .add(movement.code(), base + segment.enterOffset(k), base + segment.exitOffset(k), 0);
      }
      // 路径中间穿越的节点：道岔两次通过之间要留间隔；不停靠而经过的车站股道也是一次占用——
      // 一辆在单股道车站待命的车必须能挡住从它身上碾过去的对向车。
      List<NodeId> nodes = segment.nodes();
      for (int k = 1; k + 1 < nodes.size(); k++) {
        NodeId node = nodes.get(k);
        NodeType type = nodeTypes.get(node);
        int at = base + segment.nodeOffsets().get(k);
        if (type == NodeType.SWITCHER) {
          resource(resources, "junction:" + node.value(), Kind.JUNCTION, 1)
              .add(movement.code(), at, at, 0);
        } else if (type == NodeType.STATION) {
          addPlatform(
              resources,
              platformCapacity,
              new Platform(node.value(), groupOf(node.value()), false),
              movement.code(),
              at,
              at);
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
            resource(resources, "single:" + currentKey, Kind.SINGLE_LINE, 1)
                .add(movement.code(), enter, exit, direction);
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
          resource(resources, "single:" + currentKey, Kind.SINGLE_LINE, 1)
              .add(movement.code(), enter, exit, direction);
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
          resources,
          platformCapacity,
          platform,
          movement.code(),
          base + stop.arrivalOffsetSeconds(),
          base + stop.departureOffsetSeconds());
    }
  }

  private static void addPlatform(
      Map<String, Resource> resources,
      Map<String, Integer> platformCapacity,
      Platform platform,
      String code,
      int from,
      int to) {
    if (platform == null) {
      return;
    }
    if (!platform.group().isBlank()) {
      int capacity = Math.max(1, platformCapacity.getOrDefault(platform.group(), 1));
      resource(resources, "platform-group:" + platform.group(), Kind.PLATFORM, capacity)
          .add(code, from, to, 0);
    }
    if (!platform.dynamic() && !platform.nodeId().isBlank()) {
      resource(resources, "platform:" + platform.nodeId(), Kind.PLATFORM, 1).add(code, from, to, 0);
    }
  }

  private static Resource resource(
      Map<String, Resource> resources, String key, Kind kind, int capacity) {
    return resources.computeIfAbsent(key, k -> new Resource(k, kind, capacity));
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

    /** 首站站台。 */
    public Optional<Platform> origin() {
      return platforms.isEmpty() ? Optional.empty() : Optional.of(platforms.get(0));
    }

    /** 末站站台。 */
    public Optional<Platform> terminal() {
      return platforms.isEmpty()
          ? Optional.empty()
          : Optional.of(platforms.get(platforms.size() - 1));
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
  }

  /**
   * 一次运行：某条 route 从某个时刻开始跑一遍。
   *
   * @param code 展示用标识（车次号或 duty 走行代号）
   * @param routeId 跑的 route
   * @param startSeconds 首站发车时刻（相对统一零点）
   */
  public record Movement(String code, UUID routeId, int startSeconds) {
    public Movement {
      code = code == null ? "" : code;
      Objects.requireNonNull(routeId, "routeId");
    }
  }

  /**
   * 车辆在站台上的一段待命。
   *
   * @param code 展示用标识
   * @param platform 站台资源
   * @param from 开始
   * @param to 结束
   */
  public record Stay(String code, Platform platform, int from, int to) {
    public Stay {
      code = code == null ? "" : code;
      Objects.requireNonNull(platform, "platform");
      if (to < from) {
        throw new IllegalArgumentException("待命区间结束不能早于开始");
      }
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
      int secondTo) {

    /** 供报告使用的一行描述；时刻由调用方按需换算。 */
    public String describe(java.util.function.IntFunction<String> clock) {
      return String.format(
          Locale.ROOT,
          "%s %s: %s [%s–%s] 与 %s [%s–%s]",
          kind.name(),
          resource,
          first,
          clock.apply(firstFrom),
          clock.apply(firstTo),
          second,
          clock.apply(secondFrom),
          clock.apply(secondTo));
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

    /** 按类型计数。 */
    public Map<Kind, Integer> countByKind() {
      Map<Kind, Integer> out = new LinkedHashMap<>();
      for (Conflict conflict : conflicts) {
        out.merge(conflict.kind(), 1, Integer::sum);
      }
      return out;
    }
  }

  // ------------------------------------------------------------------ 扫描

  private record Occupation(String code, int from, int to, int direction) {}

  private static final class Resource {
    private final String key;
    private final Kind kind;
    private final int capacity;
    private final List<Occupation> occupations = new ArrayList<>();

    private Resource(String key, Kind kind, int capacity) {
      this.key = key;
      this.kind = kind;
      this.capacity = Math.max(1, capacity);
    }

    private void add(String code, int from, int to, int direction) {
      occupations.add(new Occupation(code, from, Math.max(from, to), direction));
    }

    private List<Conflict> scan(int separation) {
      List<Occupation> sorted = new ArrayList<>(occupations);
      sorted.sort(Comparator.comparingInt(Occupation::from).thenComparing(Occupation::code));
      return switch (kind) {
        case TRACK, JUNCTION -> scanExclusive(sorted, separation);
        case PLATFORM -> scanCapacity(sorted, separation);
        case SINGLE_LINE -> scanDirectional(sorted, separation);
      };
    }

    /** 容量 1：后一个占用必须在前面所有占用结束 + separation 之后开始。 */
    private List<Conflict> scanExclusive(List<Occupation> sorted, int separation) {
      List<Conflict> out = new ArrayList<>();
      Occupation latest = null;
      for (Occupation next : sorted) {
        if (latest != null
            && !latest.code().equals(next.code())
            && next.from() < latest.to() + separation) {
          out.add(conflict(latest, next));
        }
        if (latest == null || next.to() > latest.to()) {
          latest = next;
        }
      }
      return out;
    }

    /** 容量 n：同时在场的占用超过 n 就冲突；报的是新来的与最早那一个。 */
    private List<Conflict> scanCapacity(List<Occupation> sorted, int separation) {
      List<Conflict> out = new ArrayList<>();
      List<Occupation> active = new ArrayList<>();
      for (Occupation next : sorted) {
        active.removeIf(current -> current.to() + separation <= next.from());
        if (active.size() >= capacity) {
          Occupation earliest =
              active.stream().min(Comparator.comparingInt(Occupation::from)).orElseThrow();
          if (!earliest.code().equals(next.code())) {
            out.add(conflict(earliest, next));
          }
        }
        active.add(next);
      }
      return out;
    }

    /** 对向互斥：不同方向（或方向未知）的占用不能重叠；同向追踪交给边互斥。 */
    private List<Conflict> scanDirectional(List<Occupation> sorted, int separation) {
      List<Conflict> out = new ArrayList<>();
      List<Occupation> active = new ArrayList<>();
      for (Occupation next : sorted) {
        active.removeIf(current -> current.to() + separation <= next.from());
        for (Occupation current : active) {
          boolean opposite =
              current.direction() == 0
                  || next.direction() == 0
                  || current.direction() != next.direction();
          if (opposite && !current.code().equals(next.code())) {
            out.add(conflict(current, next));
            break;
          }
        }
        active.add(next);
      }
      return out;
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
          second.to());
    }
  }
}
