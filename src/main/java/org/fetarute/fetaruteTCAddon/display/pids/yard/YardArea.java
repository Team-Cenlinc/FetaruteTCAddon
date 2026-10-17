package org.fetarute.fetaruteTCAddon.display.pids.yard;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.bukkit.util.Vector;
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
 * 一个车站的站场：本站的站台与咽喉节点，经道岔与无名目的地节点连成的一片，以及伸向站外的出口。
 *
 * <ul>
 *   <li>起点是本站的站台（{@code OP:S:站:股道}）与咽喉（{@code OP:S:站:股道:序号}）节点。
 *   <li>沿区间向外展开：道岔与无名目的地节点算站场内，但离本站最近的站台或咽喉超过 {@value #MAX_RADIUS_BLOCKS} 格的不算，
 *       免得没有区间点隔开的正线把整条线都卷进来。
 *   <li>碰到区间点、别的车站、车库或跨世界节点就停下，这条区间记为出口，只画靠站场的一小段。
 *   <li>节点超过 {@value #MAX_NODES} 个时不再展开。
 * </ul>
 *
 * <p>道岔节点的 ID 只有坐标、不带车站，所以只能这样按拓扑推出来。
 *
 * @param nodes 站场内的节点
 * @param edges 两端都在站场内的区间
 * @param exits 出口：一端在站场内的区间
 * @param tracks 本站的站台股道
 */
public record YardArea(Set<NodeId> nodes, Set<EdgeId> edges, List<Exit> exits, List<Track> tracks) {

  /** 道岔与无名目的地节点离本站站台或咽喉的最远水平距离（格）。 */
  public static final double MAX_RADIUS_BLOCKS = 256.0;

  /** 站场内节点的上限。 */
  public static final int MAX_NODES = 256;

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
   */
  public record Track(NodeId node, String number) {}

  /**
   * 推出一个车站的站场。
   *
   * @param graph 车站所在世界的调度图
   * @param operator 运营商代码（不分大小写）
   * @param station 站码（不分大小写）
   * @return 图里找不到本站的站台或咽喉节点时为空
   */
  public static Optional<YardArea> of(RailGraph graph, String operator, String station) {
    Objects.requireNonNull(graph, "graph");
    List<RailNode> seeds =
        graph.nodes().stream()
            .filter(node -> belongsTo(node, operator, station))
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
            && insideYard(other.get(), operator, station, seeds)) {
          inner.add(otherId);
          edges.add(edge.id());
          queue.add(other.get());
        } else {
          exits.add(
              new Exit(edge.id(), node.id(), otherId, other.flatMap(o -> towards(o, station))));
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
    List<Track> tracks =
        seeds.stream()
            .filter(node -> RouteTerminals.stationRefOfNode(node.id().value()).isPresent())
            .map(node -> new Track(node.id(), RouteTerminals.platformOf(node.id().value())))
            .toList();
    return Optional.of(new YardArea(inner, edges, exits, tracks));
  }

  /** 本站的站台或咽喉节点。道岔节点的 ID 会被误解析成区间点，先按类型排除。 */
  private static boolean belongsTo(RailNode node, String operator, String station) {
    if (node.type() == NodeType.SWITCHER) {
      return false;
    }
    return RouteTerminals.stationIdentityOfNode(node.id().value())
        .filter(ref -> ref.operatorCode().equalsIgnoreCase(operator))
        .filter(ref -> ref.stationCode().equalsIgnoreCase(station))
        .isPresent();
  }

  private static boolean insideYard(
      RailNode node, String operator, String station, List<RailNode> seeds) {
    if (belongsTo(node, operator, station)) {
      return true;
    }
    boolean junction =
        node.type() == NodeType.SWITCHER
            || (node.type() == NodeType.DESTINATION && node.waypointMetadata().isEmpty());
    return junction && nearestSeedDistance(node, seeds) <= MAX_RADIUS_BLOCKS;
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

  /** 站外节点通向哪个车站或车库：区间点取不是本站的那一端，其余取节点自己的站码或车库代码。 */
  private static Optional<String> towards(RailNode node, String station) {
    if (node.type() == NodeType.SWITCHER) {
      return Optional.empty();
    }
    Optional<WaypointMetadata> meta = node.waypointMetadata();
    if (meta.isEmpty()) {
      return Optional.empty();
    }
    WaypointMetadata m = meta.get();
    if (m.kind() == WaypointKind.INTERVAL) {
      String from = m.originStation();
      String to = m.destinationStation().orElse("");
      String far = from.equalsIgnoreCase(station) ? to : from;
      return far.isBlank() ? Optional.empty() : Optional.of(far.toUpperCase(Locale.ROOT));
    }
    String code = m.originStation();
    return code.isBlank() || code.equalsIgnoreCase(station)
        ? Optional.empty()
        : Optional.of(code.toUpperCase(Locale.ROOT));
  }
}
