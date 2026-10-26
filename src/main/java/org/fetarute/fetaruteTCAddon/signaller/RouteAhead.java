package org.fetarute.fetaruteTCAddon.signaller;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPath;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPathFinder;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;

/**
 * 列车前方在控制区里要走的路：把交路从当前位置起的途经点按最短路展开成图节点，取出区内的区间、从哪个入口进来、要停哪条股道。
 *
 * <p>只给控制台显示与认“同一进路”用（预显示、入口信号、自动通过），不参与放行：实际走哪条由调度内核定。 最短路查询按图快照记忆，同一对途经点只算一次。
 *
 * @param zoneEdges 两端都在区内、要走的区间（按先后）
 * @param entry 进入控制区的那条出入口区间；已在区内或不经过控制区时为空
 * @param target 区内第一条要到的股道（车站站台或车库库线）
 * @param reaches 前方确实经过控制区
 */
record RouteAhead(
    List<EdgeId> zoneEdges, Optional<EdgeId> entry, Optional<NodeId> target, boolean reaches) {

  /** 最多展开几个途经点。 */
  static final int MAX_WAYPOINTS = 8;

  /** 展开出的节点最多多少个。 */
  static final int MAX_NODES = 256;

  static final RouteAhead NONE =
      new RouteAhead(List.of(), Optional.empty(), Optional.empty(), false);

  RouteAhead {
    zoneEdges = List.copyOf(zoneEdges);
    entry = entry == null ? Optional.empty() : entry;
    target = target == null ? Optional.empty() : target;
  }

  /**
   * 展开。
   *
   * @param map 控制区
   * @param progress 列车进度
   * @param route 列车的交路
   * @param dynamic 动态停站的下标与要去的股道（替换交路里的占位节点）；没有时为空
   * @param finder 最短路
   */
  static RouteAhead of(
      ZoneMap map,
      RouteProgressRegistry.RouteProgressEntry progress,
      RouteDefinition route,
      Optional<DynamicStop> dynamic,
      RailGraphPathFinder finder) {
    Objects.requireNonNull(map, "map");
    if (progress == null || route == null) {
      return NONE;
    }
    RailGraph graph = map.graph();
    List<NodeId> waypoints = new ArrayList<>(route.waypoints());
    dynamic.ifPresent(
        stop -> {
          if (stop.index() >= 0 && stop.index() < waypoints.size()) {
            waypoints.set(stop.index(), stop.platform());
          }
        });
    int current = Math.max(0, Math.min(progress.currentIndex(), waypoints.size() - 1));
    NodeId from = progress.lastPassedGraphNode().orElseGet(() -> waypoints.get(current));
    List<NodeId> path = new ArrayList<>();
    path.add(from);
    boolean entered = map.inside(from);
    for (int i = current + 1; i < waypoints.size() && i <= current + MAX_WAYPOINTS; i++) {
      NodeId to = waypoints.get(i);
      if (to.equals(from)) {
        continue;
      }
      Optional<RailGraphPath> leg =
          graph.findNode(from).isPresent() && graph.findNode(to).isPresent()
              ? finder.shortestPath(graph, from, to, RailGraphPathFinder.Options.shortestDistance())
              : Optional.empty();
      if (leg.isEmpty()) {
        break;
      }
      List<NodeId> nodes = leg.get().nodes();
      for (int k = 1; k < nodes.size(); k++) {
        path.add(nodes.get(k));
      }
      from = to;
      boolean insideNow = map.inside(to);
      entered |= nodes.stream().anyMatch(map::inside);
      // 进过控制区又走出去了，后面的不用再展开
      if (entered && !insideNow) {
        break;
      }
      if (path.size() > MAX_NODES) {
        break;
      }
    }
    return fromPath(map, path);
  }

  /** 由展开好的节点序列取出区内区间、入口与股道。 */
  static RouteAhead fromPath(ZoneMap map, List<NodeId> path) {
    Set<EdgeId> edges = new LinkedHashSet<>();
    Optional<EdgeId> entry = Optional.empty();
    Optional<NodeId> target = Optional.empty();
    boolean reaches = false;
    boolean left = false;
    for (int i = 0; i < path.size(); i++) {
      NodeId node = path.get(i);
      boolean inside = map.inside(node);
      reaches |= inside;
      if (i > 0 && !left) {
        NodeId previous = path.get(i - 1);
        EdgeId edge = EdgeId.undirected(previous, node);
        boolean previousInside = map.inside(previous);
        if (inside && previousInside && map.area().edges().contains(edge)) {
          edges.add(edge);
        } else if (inside && !previousInside && entry.isEmpty() && edges.isEmpty()) {
          if (map.exits().containsKey(edge)) {
            entry = Optional.of(edge);
          }
        } else if (!inside && previousInside) {
          left = true;
        }
      }
      if (!left && i > 0 && target.isEmpty() && map.tracks().containsKey(node)) {
        target = Optional.of(node);
      }
    }
    return new RouteAhead(List.copyOf(edges), entry, target, reaches);
  }

  /**
   * 交路的去向：最后一个车站节点的站码。
   *
   * @param graph 调度图
   * @param route 交路
   */
  static Optional<String> destination(RailGraph graph, RouteDefinition route) {
    if (graph == null || route == null) {
      return Optional.empty();
    }
    List<NodeId> waypoints = route.waypoints();
    for (int i = waypoints.size() - 1; i >= 0; i--) {
      Optional<String> station =
          graph
              .findNode(waypoints.get(i))
              .flatMap(RailNode::waypointMetadata)
              .filter(meta -> meta.kind() == WaypointKind.STATION)
              .map(meta -> meta.originStation().toUpperCase(Locale.ROOT));
      if (station.isPresent()) {
        return station;
      }
    }
    return Optional.empty();
  }

  /**
   * 动态停站。
   *
   * @param index 停站在交路里的下标
   * @param platform 要去的股道
   */
  record DynamicStop(int index, NodeId platform) {}
}
