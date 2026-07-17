package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;

/**
 * 无向轨道图拓扑快照：集中提供归一化邻接表与桥集合。
 *
 * <p>该内部模块只处理物理拓扑，不解释限速、封锁或业务节点语义。调用方可传入切点集合；任何接触切点的边都不进入派生拓扑，便于在已证明的安全会让点处截断桥链分析。
 */
final class UndirectedRailGraphTopology {

  private final List<RailEdge> edges;
  private final Map<NodeId, List<RailEdge>> adjacency;
  private final Set<EdgeId> bridges;

  private UndirectedRailGraphTopology(
      List<RailEdge> edges, Map<NodeId, List<RailEdge>> adjacency, Set<EdgeId> bridges) {
    this.edges = List.copyOf(edges);
    this.adjacency = Map.copyOf(adjacency);
    this.bridges = Set.copyOf(bridges);
  }

  /** 从图快照构建拓扑；接触 {@code cutNodes} 的边会在进入 Tarjan 前被切除。 */
  static UndirectedRailGraphTopology fromGraph(RailGraph graph, Set<NodeId> cutNodes) {
    Objects.requireNonNull(graph, "graph");
    Set<NodeId> cuts = cutNodes == null ? Set.of() : Set.copyOf(cutNodes);
    Set<NodeId> knownNodes = new HashSet<>();
    Collection<RailNode> graphNodes = graph.nodes();
    if (graphNodes != null) {
      for (RailNode node : graphNodes) {
        if (node != null && node.id() != null && !cuts.contains(node.id())) {
          knownNodes.add(node.id());
        }
      }
    }
    List<RailEdge> edges = sortedEdges(graph.edges(), cuts);
    Map<NodeId, List<RailEdge>> adjacency = sortedAdjacency(knownNodes, edges);
    return new UndirectedRailGraphTopology(edges, adjacency, findBridges(adjacency));
  }

  List<RailEdge> edges() {
    return edges;
  }

  Map<NodeId, List<RailEdge>> adjacency() {
    return adjacency;
  }

  Set<EdgeId> bridges() {
    return bridges;
  }

  private static List<RailEdge> sortedEdges(Collection<RailEdge> graphEdges, Set<NodeId> cutNodes) {
    if (graphEdges == null || graphEdges.isEmpty()) {
      return List.of();
    }
    Map<EdgeId, RailEdge> unique = new LinkedHashMap<>();
    graphEdges.stream()
        .filter(UndirectedRailGraphTopology::isValidEdge)
        .filter(edge -> !cutNodes.contains(edge.from()) && !cutNodes.contains(edge.to()))
        .sorted(Comparator.comparing(UndirectedRailGraphTopology::edgeSortKey))
        .forEach(edge -> unique.putIfAbsent(normalizedId(edge), edge));
    return List.copyOf(unique.values());
  }

  private static Map<NodeId, List<RailEdge>> sortedAdjacency(
      Set<NodeId> knownNodes, List<RailEdge> edges) {
    Map<NodeId, List<RailEdge>> adjacency = new LinkedHashMap<>();
    sortedNodes(knownNodes).forEach(node -> adjacency.put(node, new ArrayList<>()));
    for (RailEdge edge : edges) {
      adjacency.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge);
      adjacency.computeIfAbsent(edge.to(), ignored -> new ArrayList<>()).add(edge);
    }
    adjacency
        .values()
        .forEach(
            values -> values.sort(Comparator.comparing(UndirectedRailGraphTopology::edgeSortKey)));
    Map<NodeId, List<RailEdge>> frozen = new LinkedHashMap<>();
    adjacency.forEach((node, values) -> frozen.put(node, List.copyOf(values)));
    return Map.copyOf(frozen);
  }

  /** 使用迭代 Tarjan low-link 找出 simple undirected graph 中的桥。 */
  private static Set<EdgeId> findBridges(Map<NodeId, List<RailEdge>> adjacency) {
    Map<NodeId, List<GraphLink>> linksByNode = graphLinks(adjacency);
    Map<NodeId, Integer> discovered = new HashMap<>();
    Map<NodeId, Integer> low = new HashMap<>();
    Set<EdgeId> bridges = new HashSet<>();
    int discoveryClock = 0;
    for (NodeId root : sortedNodes(linksByNode.keySet())) {
      if (discovered.containsKey(root)) {
        continue;
      }
      discoveryClock++;
      discovered.put(root, discoveryClock);
      low.put(root, discoveryClock);
      ArrayDeque<BridgeFrame> stack = new ArrayDeque<>();
      stack.push(new BridgeFrame(root, null, null, linksByNode.getOrDefault(root, List.of())));
      while (!stack.isEmpty()) {
        BridgeFrame frame = stack.peek();
        if (frame.hasNext()) {
          GraphLink link = frame.next();
          if (link.edge().equals(frame.parentEdge())) {
            continue;
          }
          NodeId next = link.other();
          Integer nextDiscovery = discovered.get(next);
          if (nextDiscovery != null) {
            low.put(frame.node(), Math.min(low.get(frame.node()), nextDiscovery));
            continue;
          }
          discoveryClock++;
          discovered.put(next, discoveryClock);
          low.put(next, discoveryClock);
          stack.push(
              new BridgeFrame(
                  next, frame.node(), link.edge(), linksByNode.getOrDefault(next, List.of())));
          continue;
        }
        stack.pop();
        if (frame.parent() == null || frame.parentEdge() == null) {
          continue;
        }
        low.put(frame.parent(), Math.min(low.get(frame.parent()), low.get(frame.node())));
        if (low.get(frame.node()) > discovered.get(frame.parent())) {
          bridges.add(frame.parentEdge());
        }
      }
    }
    return Set.copyOf(bridges);
  }

  private static Map<NodeId, List<GraphLink>> graphLinks(Map<NodeId, List<RailEdge>> adjacency) {
    Map<NodeId, List<GraphLink>> result = new LinkedHashMap<>();
    for (NodeId node : sortedNodes(adjacency.keySet())) {
      List<GraphLink> links = new ArrayList<>();
      for (RailEdge edge : adjacency.getOrDefault(node, List.of())) {
        EdgeId edgeId = normalizedId(edge);
        NodeId other = otherEndpoint(edge, node);
        if (edgeId != null && other != null) {
          links.add(new GraphLink(edgeId, other));
        }
      }
      links.sort(
          Comparator.comparing((GraphLink link) -> edgeSortKey(link.edge()))
              .thenComparing(link -> link.other().value()));
      result.put(node, List.copyOf(links));
    }
    return Map.copyOf(result);
  }

  static EdgeId normalizedId(RailEdge edge) {
    return isValidEdge(edge) ? EdgeId.undirected(edge.from(), edge.to()) : null;
  }

  static NodeId otherEndpoint(RailEdge edge, NodeId current) {
    if (!isValidEdge(edge) || current == null) {
      return null;
    }
    if (current.equals(edge.from())) {
      return edge.to();
    }
    if (current.equals(edge.to())) {
      return edge.from();
    }
    return null;
  }

  static List<NodeId> sortedNodes(Collection<NodeId> nodes) {
    if (nodes == null || nodes.isEmpty()) {
      return List.of();
    }
    return nodes.stream()
        .filter(Objects::nonNull)
        .sorted(Comparator.comparing(NodeId::value))
        .toList();
  }

  private static boolean isValidEdge(RailEdge edge) {
    return edge != null && edge.from() != null && edge.to() != null;
  }

  private static String edgeSortKey(RailEdge edge) {
    return edgeSortKey(normalizedId(edge));
  }

  private static String edgeSortKey(EdgeId edge) {
    return edge == null ? "" : edge.a().value() + "\u0000" + edge.b().value();
  }

  private record GraphLink(EdgeId edge, NodeId other) {}

  private static final class BridgeFrame {
    private final NodeId node;
    private final NodeId parent;
    private final EdgeId parentEdge;
    private final List<GraphLink> links;
    private int nextIndex;

    private BridgeFrame(NodeId node, NodeId parent, EdgeId parentEdge, List<GraphLink> links) {
      this.node = node;
      this.parent = parent;
      this.parentEdge = parentEdge;
      this.links = links == null ? List.of() : links;
    }

    private NodeId node() {
      return node;
    }

    private NodeId parent() {
      return parent;
    }

    private EdgeId parentEdge() {
      return parentEdge;
    }

    private boolean hasNext() {
      return nextIndex < links.size();
    }

    private GraphLink next() {
      return links.get(nextIndex++);
    }
  }
}
