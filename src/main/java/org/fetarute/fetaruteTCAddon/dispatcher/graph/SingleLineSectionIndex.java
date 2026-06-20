package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;

/**
 * 单线 section 索引：把既有 corridor 微段归并成“进入前必须持有方向 token”的物理单线段。
 *
 * <p>该索引不会改变 {@link RailGraphConflictIndex} 的微段划分；普通道岔仍可切出 corridor key，但不会单独成为 section
 * 边界。只有能保守识别为会让点、站/库扇出或线路终端的节点才会截断 section。
 */
public final class SingleLineSectionIndex {

  private static final String SECTION_PREFIX = "single:section:";
  private static final String CYCLE_SEGMENT = "cycle:";

  private final Map<EdgeId, SingleLineSectionInfo> sectionByEdge;

  private SingleLineSectionIndex(Map<EdgeId, SingleLineSectionInfo> sectionByEdge) {
    this.sectionByEdge = Map.copyOf(sectionByEdge);
  }

  /** 从调度图快照构建 section 索引。 */
  public static SingleLineSectionIndex fromGraph(RailGraph graph) {
    Objects.requireNonNull(graph, "graph");
    Map<NodeId, RailNode> nodesById = new LinkedHashMap<>();
    Map<NodeId, Integer> degrees = new HashMap<>();
    for (RailNode node : graph.nodes()) {
      if (node == null || node.id() == null) {
        continue;
      }
      nodesById.put(node.id(), node);
      degrees.put(node.id(), graph.edgesFrom(node.id()).size());
    }

    Set<NodeId> boundaries = sectionBoundaries(graph, nodesById.values(), degrees);
    RailGraphComponentIndex componentIndex = RailGraphComponentIndex.fromGraph(graph);
    RailGraphConflictIndex corridorIndex = RailGraphConflictIndex.fromGraph(graph);
    Map<EdgeId, SingleLineSectionInfo> result = new LinkedHashMap<>();
    Set<EdgeId> assigned = new HashSet<>();

    for (RailEdge edge : graph.edges()) {
      if (edge == null || edge.from() == null || edge.to() == null) {
        continue;
      }
      EdgeId edgeId = EdgeId.undirected(edge.from(), edge.to());
      if (assigned.contains(edgeId)) {
        continue;
      }
      SectionBuild build = collectSection(graph, edge, boundaries, assigned);
      SectionBuildResult section = buildSectionInfo(graph, componentIndex, corridorIndex, build);
      for (EdgeId id : section.memberEdges()) {
        result.put(id, section.info());
        assigned.add(id);
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

  private static Set<NodeId> sectionBoundaries(
      RailGraph graph, Collection<RailNode> nodes, Map<NodeId, Integer> degrees) {
    Map<String, Set<String>> platformGroups = platformGroups(nodes);
    Set<NodeId> boundaries = new LinkedHashSet<>();
    for (RailNode node : nodes) {
      if (node == null || node.id() == null) {
        continue;
      }
      if (node.type() == NodeType.SWITCHER) {
        continue;
      }
      int degree = degrees.getOrDefault(node.id(), graph.edgesFrom(node.id()).size());
      if (isSectionBoundary(node, degree, platformGroups)) {
        boundaries.add(node.id());
      }
    }
    return boundaries;
  }

  private static boolean isSectionBoundary(
      RailNode node, int degree, Map<String, Set<String>> platformGroups) {
    if (degree <= 1) {
      return true;
    }
    if (isMultiTrackPassingPoint(node, platformGroups)) {
      return true;
    }
    return (node.type() == NodeType.STATION || node.type() == NodeType.DEPOT) && degree != 2;
  }

  private static Map<String, Set<String>> platformGroups(Collection<RailNode> nodes) {
    Map<String, Set<String>> groups = new HashMap<>();
    for (RailNode node : nodes) {
      if (node == null || node.id() == null) {
        continue;
      }
      Optional<PlatformKey> key = platformKey(node);
      key.ifPresent(
          value ->
              groups.computeIfAbsent(value.group(), ignored -> new HashSet<>()).add(value.track()));
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

  private static SectionBuild collectSection(
      RailGraph graph, RailEdge start, Set<NodeId> boundaries, Set<EdgeId> assigned) {
    ArrayDeque<RailEdge> queue = new ArrayDeque<>();
    Set<EdgeId> edges = new LinkedHashSet<>();
    Set<NodeId> nodes = new LinkedHashSet<>();
    Set<NodeId> sectionBoundaries = new LinkedHashSet<>();
    queue.add(start);

    while (!queue.isEmpty()) {
      RailEdge edge = queue.removeFirst();
      EdgeId edgeId = EdgeId.undirected(edge.from(), edge.to());
      if (assigned.contains(edgeId) || !edges.add(edgeId)) {
        continue;
      }
      nodes.add(edge.from());
      nodes.add(edge.to());
      scanEndpoint(graph, edge.from(), boundaries, sectionBoundaries, edges, assigned, queue);
      scanEndpoint(graph, edge.to(), boundaries, sectionBoundaries, edges, assigned, queue);
    }
    return new SectionBuild(edges, nodes, sectionBoundaries);
  }

  private static void scanEndpoint(
      RailGraph graph,
      NodeId node,
      Set<NodeId> boundaries,
      Set<NodeId> sectionBoundaries,
      Set<EdgeId> edges,
      Set<EdgeId> assigned,
      ArrayDeque<RailEdge> queue) {
    if (node == null) {
      return;
    }
    if (boundaries.contains(node)) {
      sectionBoundaries.add(node);
      return;
    }
    for (RailEdge next : graph.edgesFrom(node)) {
      if (next == null) {
        continue;
      }
      EdgeId nextId = EdgeId.undirected(next.from(), next.to());
      if (!edges.contains(nextId) && !assigned.contains(nextId)) {
        queue.addLast(next);
      }
    }
  }

  private static SectionBuildResult buildSectionInfo(
      RailGraph graph,
      RailGraphComponentIndex componentIndex,
      RailGraphConflictIndex corridorIndex,
      SectionBuild build) {
    List<NodeId> sortedBoundaries = sortedNodes(build.boundaries());
    Optional<PathBetweenBoundaries> axis =
        farthestBoundaryAxis(graph, build.edges(), sortedBoundaries);
    String componentKey = resolveComponentKey(componentIndex, build.nodes());
    List<String> corridorKeys = corridorKeys(corridorIndex, build.edges());
    if (axis.isEmpty()) {
      NodeId minNode = sortedNodes(build.nodes()).stream().findFirst().orElse(null);
      String nodeValue = minNode == null ? "unknown" : minNode.value();
      String key = SECTION_PREFIX + componentKey + ":" + CYCLE_SEGMENT + nodeValue;
      SingleLineSectionInfo info =
          new SingleLineSectionInfo(
              key, null, null, List.of(), sortedBoundaries, corridorKeys, false);
      return new SectionBuildResult(info, Set.of());
    }

    PathBetweenBoundaries path = axis.get();
    Set<EdgeId> memberEdges = axisEdges(path.nodes(), build.edges());
    NodeId left = path.start();
    NodeId right = path.end();
    List<NodeId> nodes = new ArrayList<>(path.nodes());
    if (left.value().compareTo(right.value()) > 0) {
      NodeId swap = left;
      left = right;
      right = swap;
      java.util.Collections.reverse(nodes);
    }
    String key = SECTION_PREFIX + componentKey + ":" + left.value() + "~" + right.value();
    SingleLineSectionInfo info =
        new SingleLineSectionInfo(
            key,
            left,
            right,
            nodes,
            sortedBoundaries,
            corridorKeys(corridorIndex, memberEdges),
            true);
    return new SectionBuildResult(info, memberEdges);
  }

  private static Set<EdgeId> axisEdges(List<NodeId> axisNodes, Set<EdgeId> sectionEdges) {
    if (axisNodes == null || axisNodes.size() < 2 || sectionEdges == null) {
      return Set.of();
    }
    Set<EdgeId> edges = new LinkedHashSet<>();
    for (int i = 0; i + 1 < axisNodes.size(); i++) {
      NodeId from = axisNodes.get(i);
      NodeId to = axisNodes.get(i + 1);
      if (from == null || to == null) {
        continue;
      }
      EdgeId edgeId = EdgeId.undirected(from, to);
      if (sectionEdges.contains(edgeId)) {
        edges.add(edgeId);
      }
    }
    return Set.copyOf(edges);
  }

  private static Optional<PathBetweenBoundaries> farthestBoundaryAxis(
      RailGraph graph, Set<EdgeId> sectionEdges, List<NodeId> boundaries) {
    if (boundaries.size() < 2) {
      return Optional.empty();
    }
    PathBetweenBoundaries best = null;
    for (int i = 0; i < boundaries.size(); i++) {
      for (int j = i + 1; j < boundaries.size(); j++) {
        Optional<PathBetweenBoundaries> candidate =
            shortestPathWithinSection(graph, sectionEdges, boundaries.get(i), boundaries.get(j));
        if (candidate.isEmpty()) {
          continue;
        }
        if (best == null || compareAxis(candidate.get(), best) > 0) {
          best = candidate.get();
        }
      }
    }
    return Optional.ofNullable(best);
  }

  private static int compareAxis(PathBetweenBoundaries candidate, PathBetweenBoundaries current) {
    int byDistance = Long.compare(candidate.distance(), current.distance());
    if (byDistance != 0) {
      return byDistance;
    }
    return axisKey(current).compareTo(axisKey(candidate));
  }

  private static String axisKey(PathBetweenBoundaries axis) {
    String left = axis.start().value();
    String right = axis.end().value();
    return left.compareTo(right) <= 0 ? left + "~" + right : right + "~" + left;
  }

  private static Optional<PathBetweenBoundaries> shortestPathWithinSection(
      RailGraph graph, Set<EdgeId> sectionEdges, NodeId start, NodeId end) {
    PriorityQueue<NodeDistance> queue =
        new PriorityQueue<>(Comparator.comparingLong(NodeDistance::distance));
    Map<NodeId, Long> distances = new HashMap<>();
    Map<NodeId, NodeId> previous = new HashMap<>();
    queue.add(new NodeDistance(start, 0L));
    distances.put(start, 0L);

    while (!queue.isEmpty()) {
      NodeDistance current = queue.poll();
      if (current.distance() > distances.getOrDefault(current.node(), Long.MAX_VALUE)) {
        continue;
      }
      if (current.node().equals(end)) {
        return Optional.of(
            new PathBetweenBoundaries(
                start, end, current.distance(), reconstructPath(previous, start, end)));
      }
      for (RailEdge edge : graph.edgesFrom(current.node())) {
        if (edge == null) {
          continue;
        }
        EdgeId edgeId = EdgeId.undirected(edge.from(), edge.to());
        if (!sectionEdges.contains(edgeId)) {
          continue;
        }
        NodeId next = current.node().equals(edge.from()) ? edge.to() : edge.from();
        long nextDistance = current.distance() + Math.max(1, edge.lengthBlocks());
        if (nextDistance >= distances.getOrDefault(next, Long.MAX_VALUE)) {
          continue;
        }
        distances.put(next, nextDistance);
        previous.put(next, current.node());
        queue.add(new NodeDistance(next, nextDistance));
      }
    }
    return Optional.empty();
  }

  private static List<NodeId> reconstructPath(
      Map<NodeId, NodeId> previous, NodeId start, NodeId end) {
    ArrayList<NodeId> nodes = new ArrayList<>();
    NodeId current = end;
    while (current != null) {
      nodes.add(current);
      if (current.equals(start)) {
        break;
      }
      current = previous.get(current);
    }
    java.util.Collections.reverse(nodes);
    return List.copyOf(nodes);
  }

  private static String resolveComponentKey(
      RailGraphComponentIndex componentIndex, Set<NodeId> nodes) {
    for (NodeId node : sortedNodes(nodes)) {
      String key = componentIndex.componentKey(node);
      if (key != null && !key.isBlank()) {
        return key;
      }
    }
    return "unknown";
  }

  private static List<String> corridorKeys(
      RailGraphConflictIndex corridorIndex, Set<EdgeId> edges) {
    Set<String> keys = new LinkedHashSet<>();
    for (EdgeId edge : edges) {
      corridorIndex.conflictKeyForEdge(edge).ifPresent(keys::add);
    }
    return List.copyOf(keys);
  }

  private static List<NodeId> sortedNodes(Set<NodeId> nodes) {
    return nodes.stream()
        .filter(Objects::nonNull)
        .sorted(Comparator.comparing(NodeId::value))
        .toList();
  }

  private record SectionBuild(Set<EdgeId> edges, Set<NodeId> nodes, Set<NodeId> boundaries) {}

  private record SectionBuildResult(SingleLineSectionInfo info, Set<EdgeId> memberEdges) {
    private SectionBuildResult {
      Objects.requireNonNull(info, "info");
      memberEdges = memberEdges == null ? Set.of() : Set.copyOf(memberEdges);
    }
  }

  private record PlatformKey(String group, String track) {}

  private record NodeDistance(NodeId node, long distance) {}

  private record PathBetweenBoundaries(
      NodeId start, NodeId end, long distance, List<NodeId> nodes) {}
}
