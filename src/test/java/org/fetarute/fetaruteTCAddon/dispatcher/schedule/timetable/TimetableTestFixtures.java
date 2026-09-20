package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;

/** 时刻表用例共用的最小路网与 route 夹具。 */
public final class TimetableTestFixtures {

  private TimetableTestFixtures() {}

  /**
   * 造一条直链路网。
   *
   * @param nodeIds 节点序列
   * @param lengths 每段的 blocks 长度（size = nodeIds.size() - 1）
   * @param speeds 每段的限速（blocks/s）
   */
  public static RailGraph chain(List<String> nodeIds, int[] lengths, double[] speeds) {
    List<NodeType> types = new ArrayList<>(nodeIds.size());
    for (int i = 0; i < nodeIds.size(); i++) {
      types.add(NodeType.STATION);
    }
    return chain(nodeIds, types, lengths, speeds);
  }

  /** 带节点类型的直链：路径点写 WAYPOINT、车库写 DEPOT，站台组容量与站台映射才与实服一致（chain 默认全部 STATION， 会把进站路径点也算成一股道）。 */
  public static RailGraph chain(
      List<String> nodeIds, List<NodeType> types, int[] lengths, double[] speeds) {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    for (int i = 0; i < nodeIds.size(); i++) {
      NodeId id = NodeId.of(nodeIds.get(i));
      nodes.put(
          id,
          new SignRailNode(
              id, types.get(i), new Vector(i, 64.0, 0.0), Optional.empty(), Optional.empty()));
    }
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    for (int i = 0; i + 1 < nodeIds.size(); i++) {
      NodeId from = NodeId.of(nodeIds.get(i));
      NodeId to = NodeId.of(nodeIds.get(i + 1));
      EdgeId edgeId = EdgeId.undirected(from, to);
      edges.put(
          edgeId, new RailEdge(edgeId, from, to, lengths[i], speeds[i], true, Optional.empty()));
    }
    return new SimpleRailGraph(nodes, edges, Set.of());
  }

  /** 一条边：两端节点、长度（blocks）、限速（blocks/s）。 */
  public record Edge(String from, String to, int length, double speed) {}

  /** 任意拓扑的路网：节点按给定类型，边按列表；支持同一车站多股道、分叉。 */
  public static RailGraph graph(Map<String, NodeType> nodeTypes, List<Edge> edgeList) {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    int i = 0;
    for (Map.Entry<String, NodeType> entry : nodeTypes.entrySet()) {
      NodeId id = NodeId.of(entry.getKey());
      nodes.put(
          id,
          new SignRailNode(
              id,
              entry.getValue(),
              new Vector(i++, 64.0, 0.0),
              Optional.empty(),
              Optional.empty()));
    }
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    for (Edge edge : edgeList) {
      NodeId from = NodeId.of(edge.from());
      NodeId to = NodeId.of(edge.to());
      EdgeId edgeId = EdgeId.undirected(from, to);
      edges.put(
          edgeId,
          new RailEdge(edgeId, from, to, edge.length(), edge.speed(), true, Optional.empty()));
    }
    return new SimpleRailGraph(nodes, edges, Set.of());
  }

  /** 造一条按节点序列定义的交路。 */
  public static RouteDefinition route(String code, List<String> nodeIds) {
    List<NodeId> waypoints = nodeIds.stream().map(NodeId::of).toList();
    return new RouteDefinition(RouteId.of(code), waypoints, Optional.empty());
  }

  /**
   * 按每条边自己的 {@code baseSpeedLimit} 估时的最小模型。
   *
   * <p>用它而不是常速模型，是为了让"改路网限速就改表定时分"这件事在用例里真的被驱动—— 常速模型会让任何路网都得到同样的时分，那样就测不到"时分来自路网"这条不变量。
   */
  public static org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailTravelTimeModel
      perEdgeSpeedModel() {
    return (graph, edge, from, to) -> {
      if (edge == null || edge.lengthBlocks() <= 0 || edge.baseSpeedLimit() <= 0.0) {
        return Optional.empty();
      }
      double seconds = edge.lengthBlocks() / edge.baseSpeedLimit();
      return Optional.of(java.time.Duration.ofMillis(Math.round(seconds * 1000.0)));
    };
  }

  /**
   * 造运营 route 的停靠配置：每站统一 dwell，末站 TERMINATE（终到后进入待命复用，与生产的运营线路一致）。
   *
   * <p>末站不标 TERMINATE 的 route 按运行时规则算作"以销毁收尾"，那是回库线路的形态，不是运营线路的。
   */
  public static List<RouteStop> stops(UUID routeId, int count, Integer dwellSeconds) {
    List<RouteStop> out = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      boolean last = i == count - 1;
      out.add(
          new RouteStop(
              routeId,
              i,
              Optional.empty(),
              Optional.empty(),
              Optional.ofNullable(dwellSeconds),
              last ? RouteStopPassType.TERMINATE : RouteStopPassType.STOP,
              Optional.empty()));
    }
    return out;
  }

  /** 造出库线路的停靠配置：首站带 {@code CRET <depot>} 指令，末站 TERMINATE（到首站后待命）。 */
  public static List<RouteStop> createStops(UUID routeId, int count, String depotNodeId) {
    List<RouteStop> out = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      boolean last = i == count - 1;
      out.add(
          new RouteStop(
              routeId,
              i,
              Optional.empty(),
              Optional.empty(),
              Optional.of(0),
              last ? RouteStopPassType.TERMINATE : RouteStopPassType.STOP,
              i == 0 ? Optional.of("CRET " + depotNodeId) : Optional.empty()));
    }
    return out;
  }

  /** 造回库线路的停靠配置：末站带 {@code DSTY <depot>} 指令，到车库即销毁。 */
  public static List<RouteStop> returnStops(UUID routeId, int count, String depotNodeId) {
    List<RouteStop> out = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      boolean last = i == count - 1;
      out.add(
          new RouteStop(
              routeId,
              i,
              Optional.empty(),
              Optional.empty(),
              Optional.of(0),
              last ? RouteStopPassType.TERMINATE : RouteStopPassType.STOP,
              last ? Optional.of("DSTY " + depotNodeId) : Optional.empty()));
    }
    return out;
  }

  /** 按 code 派生稳定的 route UUID。 */
  public static UUID routeId(String code) {
    return UUID.nameUUIDFromBytes(code.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
}
