package org.fetarute.fetaruteTCAddon.drive.session;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import java.util.Optional;
import java.util.Set;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPathFinder;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopAlignment;
import org.fetarute.fetaruteTCAddon.drive.seat.CabSeats;

/**
 * 终点站待命车下一趟由哪一端驾驶：从待命登记取所在节点（还在终点站停站、没转入待命时用停站的车站节点），找出下一趟离开时的出口——尽头式站台就是唯一那条区间的另一端，其余按列车下一趟车次
 * （交路绑定）的下一个途经点沿线路图找——再交给 {@link CabSeats#terminalDeparture}
 * 按车头、车尾哪一端离出口近判定。查不到的一律当作要到派车才知道。只在服务器主线程调用。
 */
final class TerminalCabEnd {

  private static final RailGraphPathFinder PATH_FINDER = new RailGraphPathFinder();

  private TerminalCabEnd() {}

  /**
   * @return 列车不在待命、图里查不到、或分不出时为 {@link CabSeats.Departure#EITHER}
   */
  static CabSeats.Departure of(FetaruteTCAddon plugin, MinecartGroup group) {
    return of(plugin, group, null);
  }

  /**
   * @param stationNode 列车停站所在的车站节点（终点站开门后、还没转入待命时用）；可为 {@code null}
   * @return 列车不在待命也不在终点站停站、图里查不到、或分不出时为 {@link CabSeats.Departure#EITHER}
   */
  static CabSeats.Departure of(FetaruteTCAddon plugin, MinecartGroup group, NodeId stationNode) {
    if (group == null || !group.isValid() || group.isEmpty() || group.getWorld() == null) {
      return CabSeats.Departure.EITHER;
    }
    String trainName = group.getProperties().getTrainName();
    Optional<NodeId> node =
        plugin
            .getLayoverRegistry()
            .flatMap(registry -> registry.get(trainName))
            .map(LayoverRegistry.LayoverCandidate::locationNodeId)
            .or(() -> Optional.ofNullable(stationNode));
    RailGraphService graphs = plugin.getRailGraphService();
    if (node.isEmpty() || graphs == null) {
      return CabSeats.Departure.EITHER;
    }
    Optional<RailGraph> graph =
        graphs
            .getNetworkSnapshot(group.getWorld().getUID())
            .map(RailGraphService.RailGraphSnapshot::graph);
    if (graph.isEmpty()) {
      return CabSeats.Departure.EITHER;
    }
    Set<RailEdge> edges = graph.get().edgesFrom(node.get());
    Optional<NodeId> exitNode;
    if (edges.size() == 1) {
      RailEdge edge = edges.iterator().next();
      exitNode = Optional.of(edge.from().equals(node.get()) ? edge.to() : edge.from());
    } else {
      // 贯通式站台、站后折返线：下一趟的车次已经定了（交路绑定），按它的下一个途经点沿线路图找出口。
      exitNode = exitToward(plugin, graph.get(), node.get(), trainName);
    }
    if (exitNode.isEmpty()) {
      return CabSeats.terminalDeparture(edges.size(), Double.NaN, Double.NaN, group.size());
    }
    Vector exit = graph.get().findNode(exitNode.get()).map(RailNode::worldPosition).orElse(null);
    if (exit == null) {
      return CabSeats.Departure.EITHER;
    }
    Vector head = StopAlignment.head(group);
    Vector tail = group.tail().getEntity().getLocation().toVector();
    return CabSeats.terminalDeparture(1, head.distance(exit), tail.distance(exit), group.size());
  }

  /**
   * 下一趟出发时离开待命节点的第一个图节点：取列车下一趟车次（交路绑定）交路上第一个不是待命节点的途经点，沿最短路走出去的第一步。
   *
   * @return 列车没有下一趟、交路查不到或走不到时为空
   */
  private static Optional<NodeId> exitToward(
      FetaruteTCAddon plugin, RailGraph graph, NodeId from, String trainName) {
    Optional<NodeId> toward =
        plugin
            .getTimetableService()
            .flatMap(timetables -> timetables.nextDepartureOf(trainName))
            .flatMap(due -> plugin.findRouteDefinitionById(due.trip().routeId()))
            .flatMap(
                route ->
                    route.waypoints().stream()
                        .filter(waypoint -> !waypoint.equals(from))
                        .findFirst());
    if (toward.isEmpty()) {
      return Optional.empty();
    }
    return PATH_FINDER
        .shortestPath(graph, from, toward.get(), RailGraphPathFinder.Options.shortestDistance())
        .filter(path -> path.nodes().size() >= 2)
        .map(path -> path.nodes().get(1));
  }
}
