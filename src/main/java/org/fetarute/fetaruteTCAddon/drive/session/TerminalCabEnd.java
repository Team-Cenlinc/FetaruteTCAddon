package org.fetarute.fetaruteTCAddon.drive.session;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import java.util.Optional;
import java.util.Set;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopAlignment;
import org.fetarute.fetaruteTCAddon.drive.seat.CabSeats;

/**
 * 终点站待命车下一趟由哪一端驾驶：从待命登记取所在节点，从线路图看它是不是尽头式站台、出口在哪边，交给 {@link CabSeats#terminalDeparture}
 * 判定。查不到的一律当作要到派车才知道。只在服务器主线程调用。
 */
final class TerminalCabEnd {

  private TerminalCabEnd() {}

  /**
   * @return 列车不在待命、图里查不到、或分不出时为 {@link CabSeats.Departure#EITHER}
   */
  static CabSeats.Departure of(FetaruteTCAddon plugin, MinecartGroup group) {
    if (group == null || !group.isValid() || group.isEmpty() || group.getWorld() == null) {
      return CabSeats.Departure.EITHER;
    }
    String trainName = group.getProperties().getTrainName();
    Optional<NodeId> node =
        plugin
            .getLayoverRegistry()
            .flatMap(registry -> registry.get(trainName))
            .map(LayoverRegistry.LayoverCandidate::locationNodeId);
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
    if (edges.size() != 1) {
      return CabSeats.terminalDeparture(edges.size(), Double.NaN, Double.NaN, group.size());
    }
    RailEdge edge = edges.iterator().next();
    NodeId exitNode = edge.from().equals(node.get()) ? edge.to() : edge.from();
    Vector exit = graph.get().findNode(exitNode).map(RailNode::worldPosition).orElse(null);
    if (exit == null) {
      return CabSeats.Departure.EITHER;
    }
    Vector head = StopAlignment.head(group);
    Vector tail = group.tail().getEntity().getLocation().toVector();
    return CabSeats.terminalDeparture(1, head.distance(exit), tail.distance(exit), group.size());
  }
}
