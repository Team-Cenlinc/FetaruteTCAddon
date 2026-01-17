package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;

/**
 * 调度图连通分量索引：将每个节点映射到其所属分量的稳定 key。
 *
 * <p>当前 key 策略：取该连通分量内字典序最小的 {@link NodeId#value()} 作为 componentKey，便于人工识别与配置。
 */
public final class RailGraphComponentIndex {

  private final Map<NodeId, String> byNode;

  private RailGraphComponentIndex(Map<NodeId, String> byNode) {
    this.byNode = byNode;
  }

  public static RailGraphComponentIndex fromGraph(RailGraph graph) {
    Objects.requireNonNull(graph, "graph");
    Map<NodeId, String> componentByNode = new HashMap<>();
    Set<NodeId> visited = new HashSet<>();
    for (RailNode node : graph.nodes()) {
      if (node == null || node.id() == null) {
        continue;
      }
      NodeId seed = node.id();
      if (!visited.add(seed)) {
        continue;
      }

      Set<NodeId> componentNodes = new HashSet<>();
      ArrayDeque<NodeId> queue = new ArrayDeque<>();
      componentNodes.add(seed);
      queue.add(seed);
      while (!queue.isEmpty()) {
        NodeId current = queue.poll();
        for (RailEdge edge : graph.edgesFrom(current)) {
          if (edge == null) {
            continue;
          }
          NodeId neighbor = current.equals(edge.from()) ? edge.to() : edge.from();
          if (neighbor == null) {
            continue;
          }
          if (componentNodes.add(neighbor)) {
            queue.add(neighbor);
          }
        }
      }

      visited.addAll(componentNodes);
      String componentKey = minKey(componentNodes);
      for (NodeId id : componentNodes) {
        if (id == null) {
          continue;
        }
        componentByNode.put(id, componentKey);
      }
    }
    return new RailGraphComponentIndex(Map.copyOf(componentByNode));
  }

  public String componentKey(NodeId nodeId) {
    Objects.requireNonNull(nodeId, "nodeId");
    return byNode.get(nodeId);
  }

  public Map<NodeId, String> snapshot() {
    return Map.copyOf(byNode);
  }

  private static String minKey(Set<NodeId> nodes) {
    String min = null;
    for (NodeId id : nodes) {
      if (id == null || id.value() == null) {
        continue;
      }
      String raw = id.value();
      if (min == null || raw.compareTo(min) < 0) {
        min = raw;
      }
    }
    return min != null ? min : "unknown";
  }
}
