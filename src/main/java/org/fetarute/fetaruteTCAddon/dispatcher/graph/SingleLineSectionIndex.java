package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;

/**
 * 单线 section 索引：把无向图中的桥按连续链归并成方向准入资源。
 *
 * <p>桥是移除后会切断连通分量的边，因此能够证明它没有替代路径，适合作为物理单线 section 的最小拓扑证据。任何位于环内的非桥边都不生成 {@code
 * single:section:*}；平交网格、渡线与会让环继续由规范 Movement Plan 中实际共享的 EDGE、NODE 和 switcher 资源约束，不能被压成一个全局
 * strict mutex。{@link RailGraphConflictIndex} 的非桥 micro corridor 仅保留为拓扑诊断信息，不进入运行时授权。
 *
 * <p>桥森林中的度数不等于 2 的节点、分支 switcher，以及既有多股道安全会让点都会截断链。度数为 2 的普通 switcher
 * 保持透明，使线性单线不会被拆成多个可从两端分别进入的微段。
 */
public final class SingleLineSectionIndex {

  private static final String SECTION_PREFIX = "single:section:";

  private final Map<EdgeId, SingleLineSectionInfo> sectionByEdge;

  private SingleLineSectionIndex(Map<EdgeId, SingleLineSectionInfo> sectionByEdge) {
    this.sectionByEdge = Map.copyOf(sectionByEdge);
  }

  /**
   * 从调度图快照构建桥链 section 索引。
   *
   * <p>构建只读取不可变图快照。桥识别使用迭代 Tarjan，避免大型 Minecraft 图在首次预热派生索引时递归栈溢出。
   */
  public static SingleLineSectionIndex fromGraph(RailGraph graph) {
    Objects.requireNonNull(graph, "graph");
    Map<NodeId, RailNode> nodesById = nodesById(graph.nodes());
    UndirectedRailGraphTopology topology = UndirectedRailGraphTopology.fromGraph(graph, Set.of());
    Map<NodeId, List<RailEdge>> adjacency = topology.adjacency();
    Set<EdgeId> bridges = topology.bridges();
    if (bridges.isEmpty()) {
      return new SingleLineSectionIndex(Map.of());
    }

    Map<NodeId, Integer> fullDegrees = degrees(adjacency, null);
    Map<NodeId, Integer> bridgeDegrees = degrees(adjacency, bridges);
    Set<NodeId> boundaries =
        sectionBoundaries(nodesById, fullDegrees, bridgeDegrees, adjacency.keySet());
    RailGraphConflictSupport conflictSupport = conflictSupport(graph);
    Map<EdgeId, SingleLineSectionInfo> result = new LinkedHashMap<>();
    Set<EdgeId> assigned = new HashSet<>();

    for (NodeId boundary : sortedNodes(boundaries)) {
      for (RailEdge edge : adjacency.getOrDefault(boundary, List.of())) {
        EdgeId edgeId = normalizedId(edge);
        if (edgeId == null || !bridges.contains(edgeId) || assigned.contains(edgeId)) {
          continue;
        }
        BridgeChain chain =
            walkBridgeChain(boundary, edge, adjacency, bridges, boundaries, assigned);
        if (chain.edges().isEmpty() || chain.nodes().size() < 2) {
          continue;
        }
        SingleLineSectionInfo info = buildSectionInfo(conflictSupport, chain);
        for (EdgeId member : chain.edges()) {
          result.put(member, info);
          assigned.add(member);
        }
      }
    }

    return new SingleLineSectionIndex(result);
  }

  /** 查询指定边所属 section。 */
  public Optional<SingleLineSectionInfo> sectionInfoForEdge(EdgeId edgeId) {
    if (edgeId == null || edgeId.a() == null || edgeId.b() == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(sectionByEdge.get(EdgeId.undirected(edgeId.a(), edgeId.b())));
  }

  /** 返回 edge -> section 的只读快照，主要用于测试与诊断。 */
  public Map<EdgeId, SingleLineSectionInfo> snapshot() {
    return Map.copyOf(sectionByEdge);
  }

  private static Map<NodeId, RailNode> nodesById(Collection<RailNode> nodes) {
    Map<NodeId, RailNode> result = new LinkedHashMap<>();
    if (nodes == null) {
      return result;
    }
    for (RailNode node : nodes) {
      if (node != null && node.id() != null) {
        result.put(node.id(), node);
      }
    }
    return result;
  }

  /**
   * 计算桥链边界。
   *
   * <p>桥森林度数负责截断树的端点与分支；完整图度数负责识别“桥恰好从环的同一 switcher 两侧伸出”的分支入口；多股道站/库沿用既有安全会让语义。
   */
  private static Set<NodeId> sectionBoundaries(
      Map<NodeId, RailNode> nodesById,
      Map<NodeId, Integer> fullDegrees,
      Map<NodeId, Integer> bridgeDegrees,
      Set<NodeId> graphNodes) {
    Map<String, Set<String>> platformGroups = platformGroups(nodesById.values());
    Set<NodeId> boundaries = new LinkedHashSet<>();
    for (NodeId nodeId : sortedNodes(graphNodes)) {
      RailNode node = nodesById.get(nodeId);
      int bridgeDegree = bridgeDegrees.getOrDefault(nodeId, 0);
      int fullDegree = fullDegrees.getOrDefault(nodeId, 0);
      boolean branchSwitcher = node != null && node.type() == NodeType.SWITCHER && fullDegree != 2;
      if (bridgeDegree != 2
          || branchSwitcher
          || (node != null && isMultiTrackPassingPoint(node, platformGroups))) {
        boundaries.add(nodeId);
      }
    }
    return boundaries;
  }

  private static Map<String, Set<String>> platformGroups(Collection<RailNode> nodes) {
    Map<String, Set<String>> groups = new HashMap<>();
    for (RailNode node : nodes) {
      if (node == null || node.id() == null) {
        continue;
      }
      platformKey(node)
          .ifPresent(
              value ->
                  groups
                      .computeIfAbsent(value.group(), ignored -> new HashSet<>())
                      .add(value.track()));
    }
    return groups;
  }

  private static boolean isMultiTrackPassingPoint(
      RailNode node, Map<String, Set<String>> platformGroups) {
    Optional<PlatformKey> key = platformKey(node);
    return key.map(value -> platformGroups.getOrDefault(value.group(), Set.of()).size() >= 2)
        .orElse(false);
  }

  private static Optional<PlatformKey> platformKey(RailNode node) {
    if (node.type() != NodeType.STATION && node.type() != NodeType.DEPOT) {
      return Optional.empty();
    }
    String[] parts = node.id().value().split(":");
    if (parts.length < 4) {
      return Optional.empty();
    }
    String marker = node.type() == NodeType.STATION ? "S" : "D";
    if (!marker.equals(parts[1])) {
      return Optional.empty();
    }
    return Optional.of(new PlatformKey(parts[0] + ":" + parts[1] + ":" + parts[2], parts[3]));
  }

  private static BridgeChain walkBridgeChain(
      NodeId start,
      RailEdge startEdge,
      Map<NodeId, List<RailEdge>> adjacency,
      Set<EdgeId> bridges,
      Set<NodeId> boundaries,
      Set<EdgeId> assigned) {
    List<NodeId> nodes = new ArrayList<>();
    Set<EdgeId> edges = new LinkedHashSet<>();
    nodes.add(start);
    NodeId current = start;
    RailEdge edge = startEdge;

    while (edge != null) {
      EdgeId edgeId = normalizedId(edge);
      if (edgeId == null
          || !bridges.contains(edgeId)
          || assigned.contains(edgeId)
          || !edges.add(edgeId)) {
        break;
      }
      NodeId next = otherEndpoint(edge, current);
      if (next == null) {
        break;
      }
      nodes.add(next);
      if (boundaries.contains(next)) {
        break;
      }
      RailEdge continuation = null;
      for (RailEdge candidate : adjacency.getOrDefault(next, List.of())) {
        EdgeId candidateId = normalizedId(candidate);
        if (candidateId == null
            || candidateId.equals(edgeId)
            || !bridges.contains(candidateId)
            || assigned.contains(candidateId)
            || edges.contains(candidateId)) {
          continue;
        }
        if (continuation != null) {
          continuation = null;
          break;
        }
        continuation = candidate;
      }
      current = next;
      edge = continuation;
    }
    return new BridgeChain(nodes, edges);
  }

  private static SingleLineSectionInfo buildSectionInfo(
      RailGraphConflictSupport conflictSupport, BridgeChain chain) {
    List<NodeId> nodes = new ArrayList<>(chain.nodes());
    NodeId left = nodes.get(0);
    NodeId right = nodes.get(nodes.size() - 1);
    if (left.value().compareTo(right.value()) > 0) {
      NodeId swap = left;
      left = right;
      right = swap;
      java.util.Collections.reverse(nodes);
    }
    String key = SECTION_PREFIX + "bridge:" + left.value() + "~" + right.value();
    return new SingleLineSectionInfo(
        key,
        left,
        right,
        nodes,
        List.of(left, right),
        conflictKeys(conflictSupport, chain.edges()),
        true);
  }

  private static RailGraphConflictSupport conflictSupport(RailGraph graph) {
    if (graph instanceof RailGraphConflictSupport support) {
      return support;
    }
    RailGraphConflictIndex index = RailGraphConflictIndex.fromGraph(graph);
    return index::conflictKeyForEdge;
  }

  private static Map<NodeId, Integer> degrees(
      Map<NodeId, List<RailEdge>> adjacency, Set<EdgeId> includedEdges) {
    Map<NodeId, Integer> result = new HashMap<>();
    adjacency.forEach(
        (node, edges) -> {
          if (includedEdges == null) {
            result.put(node, edges.size());
            return;
          }
          int degree = 0;
          for (RailEdge edge : edges) {
            EdgeId edgeId = normalizedId(edge);
            if (edgeId != null && includedEdges.contains(edgeId)) {
              degree++;
            }
          }
          result.put(node, degree);
        });
    return result;
  }

  private static EdgeId normalizedId(RailEdge edge) {
    return UndirectedRailGraphTopology.normalizedId(edge);
  }

  private static NodeId otherEndpoint(RailEdge edge, NodeId current) {
    return UndirectedRailGraphTopology.otherEndpoint(edge, current);
  }

  private static List<String> conflictKeys(
      RailGraphConflictSupport conflictSupport, Set<EdgeId> edges) {
    Set<String> keys = new LinkedHashSet<>();
    for (EdgeId edge : edges) {
      conflictSupport.conflictKeyForEdge(edge).ifPresent(keys::add);
    }
    return keys.stream().sorted().toList();
  }

  private static List<NodeId> sortedNodes(Collection<NodeId> nodes) {
    return UndirectedRailGraphTopology.sortedNodes(nodes);
  }

  private record BridgeChain(List<NodeId> nodes, Set<EdgeId> edges) {
    private BridgeChain {
      nodes = nodes == null ? List.of() : List.copyOf(nodes);
      edges = edges == null ? Set.of() : Set.copyOf(edges);
    }
  }

  private record PlatformKey(String group, String track) {}
}
