package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;

/**
 * 单线区间冲突组索引：把“度数=2 连续链路”压缩成走廊，并为区间生成 conflict key。
 *
 * <p>边界判定：度数≠2 或节点类型为 {@link NodeType#SWITCHER}。
 */
public final class RailGraphConflictIndex {

  private static final String SINGLE_PREFIX = "single:";
  private static final String CYCLE_SEGMENT = "cycle:";

  private final Map<EdgeId, String> conflictByEdge;

  private RailGraphConflictIndex(Map<EdgeId, String> conflictByEdge) {
    this.conflictByEdge = Map.copyOf(conflictByEdge);
  }

  public static RailGraphConflictIndex fromGraph(RailGraph graph) {
    Objects.requireNonNull(graph, "graph");
    Map<EdgeId, String> conflictByEdge = new HashMap<>();
    Set<EdgeId> assigned = new HashSet<>();
    Map<NodeId, RailNode> nodesById = new HashMap<>();
    Map<NodeId, Integer> degrees = new HashMap<>();

    for (RailNode node : graph.nodes()) {
      if (node == null || node.id() == null) {
        continue;
      }
      nodesById.put(node.id(), node);
      degrees.put(node.id(), graph.edgesFrom(node.id()).size());
    }

    Set<NodeId> boundaries = new HashSet<>();
    for (Map.Entry<NodeId, RailNode> entry : nodesById.entrySet()) {
      NodeId id = entry.getKey();
      RailNode node = entry.getValue();
      int degree = degrees.getOrDefault(id, 0);
      if (node.type() == NodeType.SWITCHER || degree != 2) {
        boundaries.add(id);
      }
    }

    RailGraphComponentIndex componentIndex = RailGraphComponentIndex.fromGraph(graph);

    for (NodeId boundary : boundaries) {
      for (RailEdge edge : graph.edgesFrom(boundary)) {
        EdgeId edgeId = EdgeId.undirected(edge.from(), edge.to());
        if (assigned.contains(edgeId)) {
          continue;
        }
        Corridor corridor = walkCorridor(graph, boundary, edge, boundaries);
        String key = buildCorridorKey(componentIndex, corridor.start(), corridor.end());
        for (EdgeId id : corridor.edges()) {
          conflictByEdge.put(id, key);
          assigned.add(id);
        }
      }
    }

    for (RailEdge edge : graph.edges()) {
      EdgeId edgeId = EdgeId.undirected(edge.from(), edge.to());
      if (assigned.contains(edgeId)) {
        continue;
      }
      Cycle cycle = walkCycle(graph, edge);
      String key = buildCycleKey(componentIndex, cycle.minNode());
      for (EdgeId id : cycle.edges()) {
        conflictByEdge.put(id, key);
        assigned.add(id);
      }
    }

    return new RailGraphConflictIndex(conflictByEdge);
  }

  public Optional<String> conflictKeyForEdge(EdgeId edgeId) {
    if (edgeId == null || edgeId.a() == null || edgeId.b() == null) {
      return Optional.empty();
    }
    EdgeId normalized = EdgeId.undirected(edgeId.a(), edgeId.b());
    return Optional.ofNullable(conflictByEdge.get(normalized));
  }

  public Map<EdgeId, String> snapshot() {
    return Map.copyOf(conflictByEdge);
  }

  private static Corridor walkCorridor(
      RailGraph graph, NodeId start, RailEdge startEdge, Set<NodeId> boundaries) {
    List<EdgeId> edges = new ArrayList<>();
    Set<EdgeId> visited = new HashSet<>();
    NodeId current = start;
    RailEdge edge = startEdge;
    while (true) {
      EdgeId edgeId = EdgeId.undirected(edge.from(), edge.to());
      if (!visited.add(edgeId)) {
        break;
      }
      edges.add(edgeId);
      NodeId next = current.equals(edge.from()) ? edge.to() : edge.from();
      if (next == null) {
        break;
      }
      if (boundaries.contains(next)) {
        return new Corridor(start, next, edges);
      }
      RailEdge nextEdge = nextEdge(graph, next, current);
      if (nextEdge == null) {
        return new Corridor(start, next, edges);
      }
      current = next;
      edge = nextEdge;
    }
    return new Corridor(start, current, edges);
  }

  private static Cycle walkCycle(RailGraph graph, RailEdge startEdge) {
    List<EdgeId> edges = new ArrayList<>();
    Set<EdgeId> visited = new HashSet<>();
    NodeId start = startEdge.from();
    NodeId current = start;
    NodeId previous = null;
    RailEdge edge = startEdge;
    NodeId minNode = minNode(start, startEdge.from(), startEdge.to());
    while (true) {
      EdgeId edgeId = EdgeId.undirected(edge.from(), edge.to());
      if (!visited.add(edgeId)) {
        break;
      }
      edges.add(edgeId);
      minNode = minNode(minNode, edge.from(), edge.to());
      NodeId next = current.equals(edge.from()) ? edge.to() : edge.from();
      if (next == null) {
        break;
      }
      previous = current;
      current = next;
      RailEdge nextEdge = nextEdge(graph, current, previous);
      if (nextEdge == null || current.equals(start)) {
        break;
      }
      edge = nextEdge;
    }
    return new Cycle(minNode != null ? minNode : start, edges);
  }

  private static RailEdge nextEdge(RailGraph graph, NodeId current, NodeId previous) {
    if (graph == null || current == null) {
      return null;
    }
    for (RailEdge edge : graph.edgesFrom(current)) {
      if (edge == null) {
        continue;
      }
      NodeId other = current.equals(edge.from()) ? edge.to() : edge.from();
      if (other == null) {
        continue;
      }
      if (previous == null || !other.equals(previous)) {
        return edge;
      }
    }
    return null;
  }

  private static String buildCorridorKey(
      RailGraphComponentIndex componentIndex, NodeId start, NodeId end) {
    String componentKey = resolveComponentKey(componentIndex, start, end);
    String left = start != null ? start.value() : "unknown";
    String right = end != null ? end.value() : "unknown";
    if (left.compareTo(right) > 0) {
      String swap = left;
      left = right;
      right = swap;
    }
    return SINGLE_PREFIX + componentKey + ":" + left + "~" + right;
  }

  private static String buildCycleKey(RailGraphComponentIndex componentIndex, NodeId minNode) {
    String componentKey = resolveComponentKey(componentIndex, minNode, minNode);
    String nodeValue = minNode != null ? minNode.value() : "unknown";
    return SINGLE_PREFIX + componentKey + ":" + CYCLE_SEGMENT + nodeValue;
  }

  private static String resolveComponentKey(
      RailGraphComponentIndex componentIndex, NodeId start, NodeId end) {
    if (componentIndex != null) {
      if (start != null) {
        String key = componentIndex.componentKey(start);
        if (key != null && !key.isBlank()) {
          return key;
        }
      }
      if (end != null) {
        String key = componentIndex.componentKey(end);
        if (key != null && !key.isBlank()) {
          return key;
        }
      }
    }
    return "unknown";
  }

  private static NodeId minNode(NodeId base, NodeId a, NodeId b) {
    NodeId current = base;
    if (a != null && compareNode(a, current) < 0) {
      current = a;
    }
    if (b != null && compareNode(b, current) < 0) {
      current = b;
    }
    return current;
  }

  private static int compareNode(NodeId left, NodeId right) {
    if (left == null || left.value() == null) {
      return 1;
    }
    if (right == null || right.value() == null) {
      return -1;
    }
    return left.value().compareTo(right.value());
  }

  private record Corridor(NodeId start, NodeId end, List<EdgeId> edges) {}

  private record Cycle(NodeId minNode, List<EdgeId> edges) {}
}
