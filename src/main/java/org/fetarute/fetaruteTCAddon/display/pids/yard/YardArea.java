package org.fetarute.fetaruteTCAddon.display.pids.yard;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
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
 *   <li>控制区里的两个车站之间若经过别的区间（例如一条线在另一条线的区间中途岔出），只有一端是成员车站的区间点与经由它们走到的道岔先试着算进来；
 *       只留下把两个不同成员连起来的几片，以及四周全是区内节点、夹在区内两处之间的几片（例如站台与本站咽喉之间的头一个区间点），再把其中走不回区内的支线一段段剪掉。
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
 * @param depotTracks 成员车库的库线（{@link Track#station()} 为车库代码）
 */
public record YardArea(
    Set<NodeId> nodes,
    Set<EdgeId> edges,
    List<Exit> exits,
    List<Track> tracks,
    List<Track> depotTracks) {

  /** 道岔与无名目的地节点离本站站台或咽喉的最远水平距离（格）。 */
  public static final double MAX_RADIUS_BLOCKS = 256.0;

  /** 区内节点的上限。 */
  public static final int MAX_NODES = 512;

  /** 推到 {@value #MAX_NODES} 个节点就停了：区内可能还有没推进来的部分。 */
  public boolean truncated() {
    return nodes.size() >= MAX_NODES;
  }

  public YardArea {
    nodes = Set.copyOf(nodes);
    edges = Set.copyOf(edges);
    exits = List.copyOf(exits);
    tracks = List.copyOf(tracks);
    depotTracks = List.copyOf(depotTracks);
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
    for (RailNode seed : seeds) {
      inner.add(seed.id());
    }
    expand(
        graph,
        inner,
        new ArrayDeque<>(seeds),
        node -> inside(node, operator, stations, depots, seeds));
    // 第二轮：试着把一端是成员车站的区间点与经由它们走到的道岔也算进来，再剪掉走不回区内的支线
    Set<NodeId> definite = Set.copyOf(inner);
    Set<NodeId> tentative = new LinkedHashSet<>();
    Deque<RailNode> frontier = new ArrayDeque<>();
    inner.forEach(id -> graph.findNode(id).ifPresent(frontier::add));
    Set<NodeId> before = Set.copyOf(inner);
    expand(
        graph,
        inner,
        frontier,
        node ->
            inside(node, operator, stations, depots, seeds)
                || touchesMember(node, operator, stations)
                || (isJunction(node) && nearestSeedDistance(node, seeds) <= MAX_RADIUS_BLOCKS));
    inner.stream().filter(id -> !before.contains(id)).forEach(tentative::add);
    keepBridges(graph, inner, tentative, definite, seeds, operator, stations, depots);
    prune(graph, inner, tentative, definite);
    Set<EdgeId> edges = new LinkedHashSet<>();
    List<Exit> exits = new ArrayList<>();
    Set<EdgeId> exitEdges = new LinkedHashSet<>();
    for (NodeId id : inner) {
      List<RailEdge> around =
          graph.edgesFrom(id).stream().sorted(Comparator.comparing(RailEdge::id)).toList();
      for (RailEdge edge : around) {
        NodeId otherId = edge.from().equals(id) ? edge.to() : edge.from();
        if (inner.contains(otherId)) {
          edges.add(edge.id());
        } else if (exitEdges.add(edge.id())) {
          exits.add(
              new Exit(
                  edge.id(),
                  id,
                  otherId,
                  graph.findNode(otherId).flatMap(o -> towards(o, stations, depots))));
        }
      }
    }
    List<Track> tracks = new ArrayList<>();
    List<Track> depotTracks = new ArrayList<>();
    for (RailNode seed : seeds) {
      RouteTerminals.stationRefOfNode(seed.id().value())
          .ifPresent(
              ref ->
                  tracks.add(
                      new Track(
                          seed.id(),
                          RouteTerminals.platformOf(seed.id().value()),
                          ref.stationCode().toUpperCase(Locale.ROOT))));
      if (seed.type() != NodeType.SWITCHER) {
        seed.waypointMetadata()
            .filter(m -> m.kind() == WaypointKind.DEPOT)
            .ifPresent(
                m ->
                    depotTracks.add(
                        new Track(
                            seed.id(),
                            String.valueOf(m.trackNumber()),
                            m.originStation().toUpperCase(Locale.ROOT))));
      }
    }
    return Optional.of(new YardArea(inner, edges, exits, tracks, depotTracks));
  }

  /** 从 {@code queue} 里的节点沿区间向外展开，{@code inside} 认可的节点进区内，到 {@value #MAX_NODES} 个为止。 */
  private static void expand(
      RailGraph graph, Set<NodeId> inner, Deque<RailNode> queue, Predicate<RailNode> inside) {
    while (!queue.isEmpty()) {
      RailNode node = queue.poll();
      List<RailEdge> around =
          graph.edgesFrom(node.id()).stream().sorted(Comparator.comparing(RailEdge::id)).toList();
      for (RailEdge edge : around) {
        NodeId otherId = edge.from().equals(node.id()) ? edge.to() : edge.from();
        if (inner.contains(otherId) || inner.size() >= MAX_NODES) {
          continue;
        }
        Optional<RailNode> other = graph.findNode(otherId);
        if (other.isPresent() && inside.test(other.get())) {
          inner.add(otherId);
          queue.add(other.get());
        }
      }
    }
  }

  /**
   * 只留连起两个不同成员的几片：试着算进来的节点按彼此相连分片，每片看它碰到的第一轮区内节点各属哪个成员（按第一轮区内的连通片认），碰不到两个不同成员的整片去掉。
   * 四周全是第一轮区内节点、碰到两个以上的一片也留下：它夹在区内两处之间（例如站台与本站咽喉之间隔着的区间点），不往区外伸，去掉了站台与咽喉就断开了。
   */
  private static void keepBridges(
      RailGraph graph,
      Set<NodeId> inner,
      Set<NodeId> tentative,
      Set<NodeId> definite,
      List<RailNode> seeds,
      String operator,
      Set<String> stations,
      Set<String> depots) {
    Map<NodeId, Integer> piece = pieces(graph, definite);
    Map<Integer, Set<String>> membersOf = new HashMap<>();
    for (RailNode seed : seeds) {
      membersOf
          .computeIfAbsent(piece.get(seed.id()), ignored -> new LinkedHashSet<>())
          .add(memberKey(seed, operator, stations, depots));
    }
    Set<NodeId> visited = new LinkedHashSet<>();
    for (NodeId start : List.copyOf(tentative)) {
      if (!visited.add(start)) {
        continue;
      }
      Set<NodeId> group = new LinkedHashSet<>(List.of(start));
      Set<String> touched = new LinkedHashSet<>();
      Set<NodeId> bounds = new HashSet<>();
      boolean enclosed = true;
      Deque<NodeId> queue = new ArrayDeque<>(List.of(start));
      while (!queue.isEmpty()) {
        NodeId id = queue.poll();
        for (RailEdge edge : graph.edgesFrom(id)) {
          NodeId other = edge.from().equals(id) ? edge.to() : edge.from();
          if (tentative.contains(other)) {
            if (visited.add(other)) {
              group.add(other);
              queue.add(other);
            }
          } else if (definite.contains(other)) {
            touched.addAll(membersOf.getOrDefault(piece.get(other), Set.of()));
            bounds.add(other);
          } else {
            enclosed = false;
          }
        }
      }
      if (touched.size() < 2 && !(enclosed && bounds.size() >= 2)) {
        inner.removeAll(group);
        tentative.removeAll(group);
      }
    }
  }

  /** 一组节点按彼此相连分片：节点 → 片号。 */
  private static Map<NodeId, Integer> pieces(RailGraph graph, Set<NodeId> nodes) {
    Map<NodeId, Integer> piece = new HashMap<>();
    int next = 0;
    for (NodeId start : nodes) {
      if (piece.containsKey(start)) {
        continue;
      }
      int id = next++;
      Deque<NodeId> queue = new ArrayDeque<>(List.of(start));
      piece.put(start, id);
      while (!queue.isEmpty()) {
        NodeId at = queue.poll();
        for (RailEdge edge : graph.edgesFrom(at)) {
          NodeId other = edge.from().equals(at) ? edge.to() : edge.from();
          if (nodes.contains(other) && !piece.containsKey(other)) {
            piece.put(other, id);
            queue.add(other);
          }
        }
      }
    }
    return piece;
  }

  /** 起点属于哪个成员：车站为 {@code S:站码}，车库为 {@code D:车库代码}。 */
  private static String memberKey(
      RailNode seed, String operator, Set<String> stations, Set<String> depots) {
    return RouteTerminals.stationIdentityOfNode(seed.id().value())
        .filter(ref -> ref.operatorCode().equalsIgnoreCase(operator))
        .map(ref -> "S:" + ref.stationCode().toUpperCase(Locale.ROOT))
        .orElseGet(
            () ->
                "D:"
                    + seed.waypointMetadata()
                        .map(m -> m.originStation().toUpperCase(Locale.ROOT))
                        .orElse(seed.id().value()));
  }

  /**
   * 剪支线：试着算进来的节点在区内的邻居不到两个就剪掉，一直剪到没有可剪的；第一轮就在区内的节点不剪。 只剩两个邻居、且两边朝同一侧离开（V
   * 形）的也剪：那是从道岔的一条侧股进、另一条侧股出，车走不通。
   */
  private static void prune(
      RailGraph graph, Set<NodeId> inner, Set<NodeId> tentative, Set<NodeId> definite) {
    boolean changed = true;
    while (changed) {
      changed = false;
      for (NodeId id : List.copyOf(tentative)) {
        List<NodeId> neighbours =
            graph.edgesFrom(id).stream()
                .map(edge -> edge.from().equals(id) ? edge.to() : edge.from())
                .filter(inner::contains)
                .distinct()
                .toList();
        boolean dead =
            neighbours.size() < 2 || (neighbours.size() == 2 && sameSide(graph, id, neighbours));
        if (dead && !definite.contains(id)) {
          inner.remove(id);
          tentative.remove(id);
          changed = true;
        }
      }
    }
  }

  /** 两个邻居在节点的同一侧（夹角小于 90°）。 */
  private static boolean sameSide(RailGraph graph, NodeId id, List<NodeId> neighbours) {
    Optional<RailNode> at = graph.findNode(id);
    Optional<RailNode> a = graph.findNode(neighbours.get(0));
    Optional<RailNode> b = graph.findNode(neighbours.get(1));
    if (at.isEmpty() || a.isEmpty() || b.isEmpty()) {
      return false;
    }
    Vector origin = at.get().worldPosition();
    Vector toA = a.get().worldPosition().clone().subtract(origin);
    Vector toB = b.get().worldPosition().clone().subtract(origin);
    return toA.getX() * toB.getX() + toA.getZ() * toB.getZ() > 0;
  }

  /** 一端是成员车站的区间点（本运营商）。 */
  private static boolean touchesMember(RailNode node, String operator, Set<String> stations) {
    return node.type() != NodeType.SWITCHER
        && node.waypointMetadata()
            .filter(m -> m.kind() == WaypointKind.INTERVAL)
            .filter(m -> m.operator().equalsIgnoreCase(operator))
            .filter(
                m ->
                    stations.contains(m.originStation().toUpperCase(Locale.ROOT))
                        || m.destinationStation()
                            .map(to -> stations.contains(to.toUpperCase(Locale.ROOT)))
                            .orElse(false))
            .isPresent();
  }

  private static boolean isJunction(RailNode node) {
    return node.type() == NodeType.SWITCHER
        || (node.type() == NodeType.DESTINATION && node.waypointMetadata().isEmpty());
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
    if (isJunction(node)) {
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
