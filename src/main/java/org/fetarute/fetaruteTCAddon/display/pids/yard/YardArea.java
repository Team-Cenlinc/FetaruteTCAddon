package org.fetarute.fetaruteTCAddon.display.pids.yard;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.company.model.ControlZoneMember.Kind;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteTerminals;

/**
 * 一片站场：一个车站，或一个控制区里的几个车站与车库，经道岔、无名目的地节点和成员之间的区间连成的一片，以及伸向区外的出口。
 *
 * <ul>
 *   <li>起点是成员车站的站台（{@code OP:S:站:股道}）与咽喉（{@code OP:S:站:股道:序号}）节点，以及成员车库的股道（{@code OP:D:库:股道}）与咽喉节点。
 *   <li>沿区间向外展开：道岔与无名目的地节点算区内，但离最近的起点超过 {@value #MAX_RADIUS_BLOCKS} 格的不算，免得没有区间点隔开的正线把整条线都卷进来；
 *       两端都是成员车站的区间点也算区内，几个车站因此连成一张图。
 *   <li>碰到其余的区间点、别的车站、车库或跨世界节点就停下，这条区间记为出口，只画靠区内的一小段。
 *   <li>节点超过 {@value #MAX_NODES} 个时不再展开。
 * </ul>
 *
 * <p>道岔节点的 ID 只有坐标、不带车站，所以只能这样按拓扑推出来。
 *
 * @param nodes 区内的节点
 * @param edges 两端都在区内的区间
 * @param exits 出口：一端在区内的区间
 * @param tracks 成员车站的站台股道
 */
public record YardArea(Set<NodeId> nodes, Set<EdgeId> edges, List<Exit> exits, List<Track> tracks) {

  /** 道岔与无名目的地节点离本站站台或咽喉的最远水平距离（格）。 */
  public static final double MAX_RADIUS_BLOCKS = 256.0;

  /** 区内节点的上限。 */
  public static final int MAX_NODES = 512;

  public YardArea {
    nodes = Set.copyOf(nodes);
    edges = Set.copyOf(edges);
    exits = List.copyOf(exits);
    tracks = List.copyOf(tracks);
  }

  /**
   * 出口：从站场内的 {@code inner} 伸向站外的 {@code outer}。
   *
   * @param edge 区间
   * @param inner 站场内的一端
   * @param outer 站外的一端
   * @param towards 站外一端所属的车站或车库代码（区间点取不是本站的那一端）；判断不出时为空
   */
  public record Exit(EdgeId edge, NodeId inner, NodeId outer, Optional<String> towards) {
    public Exit {
      Objects.requireNonNull(edge, "edge");
      Objects.requireNonNull(inner, "inner");
      Objects.requireNonNull(outer, "outer");
      towards = towards == null ? Optional.empty() : towards;
    }
  }

  /**
   * 站台股道。
   *
   * @param node 站台节点
   * @param number 股道号
   * @param station 所属车站的站码（大写）
   */
  public record Track(NodeId node, String number, String station) {}

  /**
   * 成员：一个车站或一个车库（与控制区成员同一种类）。
   *
   * @param kind 种类
   * @param code 站码或车库代码（大写）
   */
  public record Member(Kind kind, String code) {

    public Member {
      Objects.requireNonNull(kind, "kind");
      code = Objects.requireNonNull(code, "code").trim().toUpperCase(Locale.ROOT);
    }

    /** 车站成员。 */
    public static Member station(String code) {
      return new Member(Kind.STATION, code);
    }

    /** 车库成员。 */
    public static Member depot(String code) {
      return new Member(Kind.DEPOT, code);
    }
  }

  /**
   * 推出一个车站的站场。
   *
   * @param graph 车站所在世界的调度图
   * @param operator 运营商代码（不分大小写）
   * @param station 站码（不分大小写）
   * @return 图里找不到本站的站台或咽喉节点时为空
   */
  public static Optional<YardArea> of(RailGraph graph, String operator, String station) {
    return of(graph, operator, List.of(Member.station(station)));
  }

  /**
   * 推出一片控制区。
   *
   * @param graph 所在世界的调度图
   * @param operator 运营商代码（不分大小写）
   * @param members 成员车站与车库
   * @return 图里一个成员的节点都找不到时为空
   */
  public static Optional<YardArea> of(
      RailGraph graph, String operator, Collection<Member> members) {
    Objects.requireNonNull(graph, "graph");
    Objects.requireNonNull(operator, "operator");
    Set<String> stations = codes(members, Kind.STATION);
    Set<String> depots = codes(members, Kind.DEPOT);
    List<RailNode> seeds =
        graph.nodes().stream()
            .filter(node -> belongsTo(node, operator, stations, depots))
            .sorted(Comparator.comparing(node -> node.id().value()))
            .toList();
    if (seeds.isEmpty()) {
      return Optional.empty();
    }
    Set<NodeId> inner = new LinkedHashSet<>();
    Deque<RailNode> queue = new ArrayDeque<>();
    for (RailNode seed : seeds) {
      inner.add(seed.id());
      queue.add(seed);
    }
    Set<EdgeId> edges = new LinkedHashSet<>();
    List<Exit> exits = new ArrayList<>();
    Set<EdgeId> seen = new LinkedHashSet<>();
    while (!queue.isEmpty()) {
      RailNode node = queue.poll();
      List<RailEdge> around =
          graph.edgesFrom(node.id()).stream().sorted(Comparator.comparing(RailEdge::id)).toList();
      for (RailEdge edge : around) {
        if (!seen.add(edge.id())) {
          continue;
        }
        NodeId otherId = edge.from().equals(node.id()) ? edge.to() : edge.from();
        if (inner.contains(otherId)) {
          edges.add(edge.id());
          continue;
        }
        Optional<RailNode> other = graph.findNode(otherId);
        if (other.isPresent()
            && inner.size() < MAX_NODES
            && inside(other.get(), operator, stations, depots, seeds)) {
          inner.add(otherId);
          edges.add(edge.id());
          queue.add(other.get());
        } else {
          exits.add(
              new Exit(
                  edge.id(), node.id(), otherId, other.flatMap(o -> towards(o, stations, depots))));
        }
      }
    }
    // 先当成出口、后来另一端也进了站场的区间
    exits.removeIf(exit -> inner.contains(exit.outer()));
    for (EdgeId id : seen) {
      if (inner.contains(id.a()) && inner.contains(id.b())) {
        edges.add(id);
      }
    }
    List<Track> tracks = new ArrayList<>();
    for (RailNode seed : seeds) {
      RouteTerminals.stationRefOfNode(seed.id().value())
          .ifPresent(
              ref ->
                  tracks.add(
                      new Track(
                          seed.id(),
                          RouteTerminals.platformOf(seed.id().value()),
                          ref.stationCode().toUpperCase(Locale.ROOT))));
    }
    return Optional.of(new YardArea(inner, edges, exits, tracks));
  }

  private static Set<String> codes(Collection<Member> members, Kind kind) {
    Set<String> codes = new LinkedHashSet<>();
    for (Member member : members) {
      if (member.kind() == kind) {
        codes.add(member.code());
      }
    }
    return codes;
  }

  /** 成员车站的站台、咽喉节点，或成员车库的股道、咽喉节点。道岔节点的 ID 会被误解析成区间点，先按类型排除。 */
  private static boolean belongsTo(
      RailNode node, String operator, Set<String> stations, Set<String> depots) {
    if (node.type() == NodeType.SWITCHER) {
      return false;
    }
    boolean station =
        RouteTerminals.stationIdentityOfNode(node.id().value())
            .filter(ref -> ref.operatorCode().equalsIgnoreCase(operator))
            .filter(ref -> stations.contains(ref.stationCode().toUpperCase(Locale.ROOT)))
            .isPresent();
    return station
        || node.waypointMetadata()
            .filter(m -> m.kind() == WaypointKind.DEPOT || m.kind() == WaypointKind.DEPOT_THROAT)
            .filter(m -> m.operator().equalsIgnoreCase(operator))
            .filter(m -> depots.contains(m.originStation().toUpperCase(Locale.ROOT)))
            .isPresent();
  }

  /** 节点算不算区内：成员的节点、离起点不远的道岔与无名目的地节点、两端都是成员车站的区间点。 */
  private static boolean inside(
      RailNode node,
      String operator,
      Set<String> stations,
      Set<String> depots,
      List<RailNode> seeds) {
    if (belongsTo(node, operator, stations, depots)) {
      return true;
    }
    boolean junction =
        node.type() == NodeType.SWITCHER
            || (node.type() == NodeType.DESTINATION && node.waypointMetadata().isEmpty());
    if (junction) {
      return nearestSeedDistance(node, seeds) <= MAX_RADIUS_BLOCKS;
    }
    return node.type() != NodeType.SWITCHER
        && node.waypointMetadata()
            .filter(m -> m.kind() == WaypointKind.INTERVAL)
            .filter(m -> m.operator().equalsIgnoreCase(operator))
            .filter(m -> stations.contains(m.originStation().toUpperCase(Locale.ROOT)))
            .filter(
                m ->
                    m.destinationStation()
                        .map(to -> stations.contains(to.toUpperCase(Locale.ROOT)))
                        .orElse(false))
            .isPresent();
  }

  private static double nearestSeedDistance(RailNode node, List<RailNode> seeds) {
    Vector at = node.worldPosition();
    double best = Double.MAX_VALUE;
    for (RailNode seed : seeds) {
      Vector from = seed.worldPosition();
      double dx = at.getX() - from.getX();
      double dz = at.getZ() - from.getZ();
      best = Math.min(best, Math.sqrt(dx * dx + dz * dz));
    }
    return best;
  }

  /** 区外节点通向哪个车站或车库：区间点取不是成员车站的那一端，其余取节点自己的站码或车库代码。 */
  private static Optional<String> towards(RailNode node, Set<String> stations, Set<String> depots) {
    if (node.type() == NodeType.SWITCHER) {
      return Optional.empty();
    }
    Optional<WaypointMetadata> meta = node.waypointMetadata();
    if (meta.isEmpty()) {
      return Optional.empty();
    }
    WaypointMetadata m = meta.get();
    if (m.kind() == WaypointKind.INTERVAL) {
      String from = m.originStation().toUpperCase(Locale.ROOT);
      String to = m.destinationStation().orElse("").toUpperCase(Locale.ROOT);
      String far = stations.contains(from) ? to : from;
      return far.isBlank() ? Optional.empty() : Optional.of(far);
    }
    String code = m.originStation().toUpperCase(Locale.ROOT);
    return code.isBlank() || stations.contains(code) || depots.contains(code)
        ? Optional.empty()
        : Optional.of(code);
  }
}
