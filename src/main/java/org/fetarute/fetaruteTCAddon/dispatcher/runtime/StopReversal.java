package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPath;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPathFinder;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher;
import org.fetarute.fetaruteTCAddon.dispatcher.route.PlatformApproach;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;

/**
 * 交路在某一站换不换向（{@link StopSkips.Reversal} 的调度侧实现）：信号员越站不能越过中途换向的站。
 *
 * <p>换向没有指令可认，只能从图上看：把这一站前后两段交路按调度图展开（有直连区间就直接走，否则取最短路，与占用申请展开路径同一做法），
 * 站前最后一个节点与站后第一个节点相同，就是从哪来往哪回。判不了的一律按换向算（不让越）：
 *
 * <ul>
 *   <li>站、前后路径点不在同一张调度图上（图没建好、跨了世界），或走不通；
 *   <li>站节点接了三条以上区间，哪两条是一进一出说不准；
 *   <li>站两头接的节点在同一侧（点积大于 {@link #SAME_SIDE_DOT}），看不出直通还是折返；
 *   <li>交路首尾，没有前后两段。
 * </ul>
 *
 * <p>DYNAMIC 停站不管选没选台，都把规范里图上存在的候选股道逐条代进去，每种组合都直通才算直通。结果按调度图版本缓存，交路变了也作废。只在主线程调用。
 */
final class StopReversal implements StopSkips.Reversal {

  /** 站两头的方向点积大于它就算在同一侧：直通站两头接的节点大体相对（点积接近 -1）。 */
  static final double SAME_SIDE_DOT = -0.5;

  private final Supplier<? extends Collection<RailGraph>> graphs;
  private final LongSupplier version;
  private final RouteDefinitionCache routes;
  private final BiFunction<String, RouteDefinition, List<NodeId>> effective;
  private final RailGraphPathFinder paths = new RailGraphPathFinder();
  private final Map<Key, Boolean> memo = new ConcurrentHashMap<>();
  private volatile long memoVersion = Long.MIN_VALUE;

  /** 一次判断的输入：交路、下标与前后三个实际节点。 */
  private record Key(RouteId route, int index, NodeId previous, NodeId station, NodeId next) {}

  /**
   * @param graphs 当前各世界的调度图
   * @param version 调度图版本（换图后缓存作废）
   * @param routes 交路缓存（认 DYNAMIC 停站）
   * @param effective 列车眼中的实际节点（DYNAMIC 已选台处为选中的股道）；取不到时返回 null，按声明节点
   */
  StopReversal(
      Supplier<? extends Collection<RailGraph>> graphs,
      LongSupplier version,
      RouteDefinitionCache routes,
      BiFunction<String, RouteDefinition, List<NodeId>> effective) {
    this.graphs = Objects.requireNonNull(graphs, "graphs");
    this.version = Objects.requireNonNull(version, "version");
    this.routes = Objects.requireNonNull(routes, "routes");
    this.effective = Objects.requireNonNull(effective, "effective");
    routes.addChangeListener(memo::clear);
  }

  /** 接调度：图取各世界当前快照，实际节点取调度解析的有效节点。 */
  static StopReversal of(
      RailGraphService graphs,
      RouteDefinitionCache routes,
      BiFunction<String, RouteDefinition, List<NodeId>> effective) {
    return new StopReversal(
        () ->
            graphs.snapshotAll().values().stream()
                .map(RailGraphService.RailGraphSnapshot::graph)
                .toList(),
        graphs::snapshotVersion,
        routes,
        effective);
  }

  @Override
  public boolean through(String trainName, RouteDefinition route, int stopIndex) {
    List<NodeId> declared = route.waypoints();
    if (stopIndex <= 0 || stopIndex + 1 >= declared.size()) {
      return false;
    }
    List<NodeId> nodes = effectiveNodes(trainName, route);
    long current = version.getAsLong();
    if (current != memoVersion) {
      memo.clear();
      memoVersion = current;
    }
    Key key =
        new Key(
            route.id(),
            stopIndex,
            nodes.get(stopIndex - 1),
            nodes.get(stopIndex),
            nodes.get(stopIndex + 1));
    return memo.computeIfAbsent(key, ignored -> judge(route, nodes, stopIndex));
  }

  private List<NodeId> effectiveNodes(String trainName, RouteDefinition route) {
    List<NodeId> declared = route.waypoints();
    try {
      List<NodeId> resolved = effective.apply(trainName, route);
      return resolved != null && resolved.size() == declared.size() ? resolved : declared;
    } catch (RuntimeException ex) {
      return declared;
    }
  }

  private boolean judge(RouteDefinition route, List<NodeId> nodes, int index) {
    try {
      Optional<RailGraph> found =
          graphs.get().stream()
              .filter(graph -> graph.findNode(nodes.get(index)).isPresent())
              .findFirst();
      if (found.isEmpty()) {
        return false;
      }
      RailGraph graph = found.get();
      List<NodeId> previous = candidates(graph, route, nodes, index - 1);
      List<NodeId> stations = candidates(graph, route, nodes, index);
      List<NodeId> next = candidates(graph, route, nodes, index + 1);
      if (previous.isEmpty() || stations.isEmpty() || next.isEmpty()) {
        return false;
      }
      for (NodeId station : stations) {
        if (!throughAt(graph, station, previous, next)) {
          return false;
        }
      }
      return true;
    } catch (RuntimeException ex) {
      return false;
    }
  }

  /** 这一下标在图上可能的节点：实际节点，DYNAMIC 停站再加规范里图上存在的全部候选股道。 */
  private List<NodeId> candidates(
      RailGraph graph, RouteDefinition route, List<NodeId> nodes, int index) {
    Set<NodeId> out = new LinkedHashSet<>();
    NodeId node = nodes.get(index);
    if (node != null && graph.findNode(node).isPresent()) {
      out.add(node);
    }
    routes
        .findStop(route.id(), index)
        .filter(DynamicStopMatcher::isDynamicStop)
        .flatMap(DynamicStopMatcher::parseDynamicSpec)
        .ifPresent(spec -> out.addAll(DynamicStopMatcher.candidateNodes(spec, graph)));
    return List.copyOf(out);
  }

  /** 从任一前一点进站、往任一后一点出站，都走站的两头、两头相对。 */
  private boolean throughAt(
      RailGraph graph, NodeId station, List<NodeId> previous, List<NodeId> next) {
    Set<NodeId> neighbours = neighbours(graph, station);
    if (neighbours.isEmpty() || neighbours.size() > 2) {
      return false;
    }
    Set<NodeId> entries = new LinkedHashSet<>();
    for (NodeId from : previous) {
      Optional<NodeId> entry = beside(graph, station, from, neighbours, true);
      if (entry.isEmpty()) {
        return false;
      }
      entries.add(entry.get());
    }
    Set<NodeId> exits = new LinkedHashSet<>();
    for (NodeId to : next) {
      Optional<NodeId> exit = beside(graph, station, to, neighbours, false);
      if (exit.isEmpty()) {
        return false;
      }
      exits.add(exit.get());
    }
    for (NodeId entry : entries) {
      for (NodeId exit : exits) {
        if (entry.equals(exit)) {
          return false;
        }
        OptionalDouble dot = dot(graph, station, entry, exit);
        if (dot.isEmpty() || dot.getAsDouble() > SAME_SIDE_DOT) {
          return false;
        }
      }
    }
    return true;
  }

  /**
   * 站与另一点之间展开路径上紧挨着站的那个节点：有直连区间就是那一点，否则取最短路。
   *
   * @param arriving true 为从 {@code other} 开到站（取站前一个），false 为从站开到 {@code other}（取站后一个）
   */
  private Optional<NodeId> beside(
      RailGraph graph, NodeId station, NodeId other, Set<NodeId> neighbours, boolean arriving) {
    if (other.equals(station)) {
      return Optional.empty();
    }
    if (neighbours.contains(other)) {
      return Optional.of(other);
    }
    Optional<RailGraphPath> path =
        arriving
            ? paths.shortestPath(
                graph, other, station, RailGraphPathFinder.Options.shortestDistance())
            : paths.shortestPath(
                graph, station, other, RailGraphPathFinder.Options.shortestDistance());
    if (path.isEmpty() || path.get().nodes().size() < 2) {
      return Optional.empty();
    }
    List<NodeId> nodes = path.get().nodes();
    NodeId beside = arriving ? nodes.get(nodes.size() - 2) : nodes.get(1);
    return neighbours.contains(beside) ? Optional.of(beside) : Optional.empty();
  }

  /** 站在图上接着的节点。 */
  private static Set<NodeId> neighbours(RailGraph graph, NodeId station) {
    Set<NodeId> out = new LinkedHashSet<>();
    for (RailEdge edge : graph.edgesFrom(station)) {
      NodeId other = edge.from().equals(station) ? edge.to() : edge.from();
      if (!other.equals(station)) {
        out.add(other);
      }
    }
    return out;
  }

  /** 从站看两个节点方向的点积（X/Z 平面）；位置算不出时为空。 */
  private static OptionalDouble dot(RailGraph graph, NodeId station, NodeId a, NodeId b) {
    Optional<Vector> toA = PlatformApproach.direction(graph, station, a);
    Optional<Vector> toB = PlatformApproach.direction(graph, station, b);
    if (toA.isEmpty() || toB.isEmpty()) {
      return OptionalDouble.empty();
    }
    return OptionalDouble.of(
        toA.get().getX() * toB.get().getX() + toA.get().getZ() * toB.get().getZ());
  }
}
