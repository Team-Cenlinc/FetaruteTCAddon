package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;

/**
 * 基于 Waypoint 语义解析单线资源方向。
 *
 * <p>冲突资源 key 仍然需要稳定归一化，但列车方向不能依赖 key 两端的字典序。这个解析器把资源附近的 {@link WaypointKind#INTERVAL}
 * 元数据当作物理方向轴，例如 {@code PPK -> RVS}。方向优先从当前 movement path 上的局部锚点解析，
 * 避免站咽喉、库线、支线等旁路元数据污染当前列车实际路径；若当前路径或资源邻域出现多个不可比较的站间轴， 则拒绝给出方向，让占用层按 UNKNOWN fail-closed 处理。
 */
final class SemanticCorridorDirectionResolver {

  private final RailGraph graph;

  SemanticCorridorDirectionResolver(RailGraph graph) {
    this.graph = Objects.requireNonNull(graph, "graph");
  }

  Result resolve(List<NodeId> resourceNodes, List<NodeId> pathNodes, NodeId from, NodeId to) {
    Result pathResult = resolveWithAxis(resolvePathAxis(pathNodes), pathNodes, from, to);
    if (pathResult.status() != Status.NO_AXIS) {
      return pathResult;
    }
    return resolveWithAxis(resolveAxis(resourceNodes), pathNodes, from, to);
  }

  private Result resolveWithAxis(
      AxisResolution axisResolution, List<NodeId> pathNodes, NodeId from, NodeId to) {
    if (axisResolution.status() == Status.NO_AXIS) {
      return Result.noAxis();
    }
    if (axisResolution.status() == Status.AMBIGUOUS) {
      return Result.ambiguous();
    }
    SemanticAxis axis = axisResolution.axis().orElseThrow();
    Optional<DirectedStationPair> flow = resolveFlow(axis, pathNodes, from, to);
    if (flow.isEmpty()) {
      return Result.unresolved();
    }
    return axis.directionOf(flow.get()).map(Result::resolved).orElseGet(Result::ambiguous);
  }

  private AxisResolution resolvePathAxis(List<NodeId> pathNodes) {
    if (pathNodes == null || pathNodes.isEmpty()) {
      return AxisResolution.noAxis();
    }
    List<DirectedStationPair> pairs = new ArrayList<>();
    for (NodeId node : pathNodes) {
      Optional<WaypointMetadata> metadata = metadata(node);
      if (metadata.isEmpty() || metadata.get().kind() != WaypointKind.INTERVAL) {
        continue;
      }
      DirectedStationPair.from(metadata.get()).ifPresent(pairs::add);
    }
    return resolveAxisFromPairs(pairs);
  }

  private AxisResolution resolveAxis(List<NodeId> resourceNodes) {
    Set<NodeId> candidateNodes = expandResourceNeighborhood(resourceNodes);
    List<DirectedStationPair> pairs = new ArrayList<>();
    for (NodeId node : candidateNodes) {
      Optional<WaypointMetadata> metadata = metadata(node);
      if (metadata.isEmpty() || metadata.get().kind() != WaypointKind.INTERVAL) {
        continue;
      }
      DirectedStationPair.from(metadata.get()).ifPresent(pairs::add);
    }
    return resolveAxisFromPairs(pairs);
  }

  private AxisResolution resolveAxisFromPairs(Iterable<DirectedStationPair> pairs) {
    Map<StationPair, DirectedStationPair> directedPairs = new LinkedHashMap<>();
    for (DirectedStationPair directed : pairs) {
      if (directed == null) {
        continue;
      }
      StationPair pair = StationPair.of(directed.from(), directed.to());
      DirectedStationPair previous = directedPairs.putIfAbsent(pair, directed);
      if (previous != null && !previous.sameOrientation(directed)) {
        return AxisResolution.ambiguous();
      }
    }
    if (directedPairs.isEmpty()) {
      return AxisResolution.noAxis();
    }
    return SemanticAxis.from(directedPairs.values())
        .map(AxisResolution::resolved)
        .orElseGet(AxisResolution::ambiguous);
  }

  private Set<NodeId> expandResourceNeighborhood(List<NodeId> resourceNodes) {
    Set<NodeId> nodes = new LinkedHashSet<>();
    if (resourceNodes == null) {
      return nodes;
    }
    for (NodeId node : resourceNodes) {
      if (node == null) {
        continue;
      }
      nodes.add(node);
      for (RailEdge edge : graph.edgesFrom(node)) {
        if (edge == null) {
          continue;
        }
        NodeId other = node.equals(edge.from()) ? edge.to() : edge.from();
        if (other != null) {
          nodes.add(other);
        }
      }
    }
    return nodes;
  }

  private Optional<DirectedStationPair> resolveFlow(
      SemanticAxis axis, List<NodeId> pathNodes, NodeId from, NodeId to) {
    if (axis == null || pathNodes == null || pathNodes.size() < 2 || from == null || to == null) {
      return Optional.empty();
    }
    int edgeIndex = edgeIndex(pathNodes, from, to);
    if (edgeIndex < 0) {
      return Optional.empty();
    }
    List<IndexedPathAnchor> anchors = indexedAnchors(axis, pathNodes);
    if (anchors.isEmpty()) {
      return Optional.empty();
    }
    List<IndexedPathAnchor> leftNearest = new ArrayList<>();
    List<IndexedPathAnchor> right = new ArrayList<>();
    List<IndexedPathAnchor> left = new ArrayList<>();
    for (IndexedPathAnchor anchor : anchors) {
      if (anchor.index() <= edgeIndex) {
        left.add(anchor);
        leftNearest.add(0, anchor);
      } else {
        right.add(anchor);
      }
    }
    Optional<DirectedStationPair> spanning = flowAcrossEdge(axis, leftNearest, right);
    if (spanning.isPresent()) {
      return spanning;
    }
    Optional<DirectedStationPair> forward = flowWithinPathOrder(axis, right);
    if (forward.isPresent()) {
      return forward;
    }
    Optional<DirectedStationPair> backward = flowWithinPathOrder(axis, left);
    if (backward.isPresent()) {
      return backward;
    }
    return inferFlowFromSingleStationAnchor(axis, leftNearest, right);
  }

  private Optional<DirectedStationPair> flowBetween(
      SemanticAxis axis, PathAnchor left, PathAnchor right) {
    Optional<String> leftStation = left.station();
    Optional<String> rightStation = right.station();
    if (leftStation.isPresent() && rightStation.isPresent()) {
      if (leftStation.get().equals(rightStation.get())) {
        return Optional.empty();
      }
      return Optional.of(new DirectedStationPair(leftStation.get(), rightStation.get()))
          .filter(axis::contains);
    }
    if (leftStation.isPresent() && right.interval().isPresent()) {
      return flowFromStation(axis, leftStation.get(), right.interval().get());
    }
    if (left.interval().isPresent() && rightStation.isPresent()) {
      return flowToStation(axis, left.interval().get(), rightStation.get());
    }
    if (left.interval().isPresent() && right.interval().isPresent()) {
      return flowBetweenIntervals(axis, left.interval().get(), right.interval().get());
    }
    return Optional.empty();
  }

  private Optional<DirectedStationPair> flowFromStation(
      SemanticAxis axis, String station, IntervalAnchor interval) {
    if (!axis.containsStation(station) || !axis.contains(interval.direction())) {
      return Optional.empty();
    }
    if (interval.direction().from().equals(station)) {
      return Optional.of(interval.direction());
    }
    if (interval.direction().to().equals(station)) {
      return Optional.of(interval.direction().reverse());
    }
    return Optional.empty();
  }

  private Optional<DirectedStationPair> flowToStation(
      SemanticAxis axis, IntervalAnchor interval, String station) {
    if (!axis.contains(interval.direction()) || !axis.containsStation(station)) {
      return Optional.empty();
    }
    if (interval.direction().to().equals(station)) {
      return Optional.of(interval.direction());
    }
    if (interval.direction().from().equals(station)) {
      return Optional.of(interval.direction().reverse());
    }
    return Optional.empty();
  }

  private Optional<DirectedStationPair> flowBetweenIntervals(
      SemanticAxis axis, IntervalAnchor left, IntervalAnchor right) {
    if (!axis.contains(left.direction()) || !axis.contains(right.direction())) {
      return Optional.empty();
    }
    Optional<DirectedStationPair> chained = flowBetweenChainedIntervals(left, right);
    if (chained.isPresent()) {
      return chained.filter(axis::contains);
    }
    if (left.sequence().isEmpty() || right.sequence().isEmpty()) {
      return Optional.empty();
    }
    int compare = Integer.compare(left.sequence().getAsInt(), right.sequence().getAsInt());
    if (compare == 0) {
      return Optional.empty();
    }
    DirectedStationPair increasing = left.direction();
    DirectedStationPair flow = compare < 0 ? increasing : increasing.reverse();
    return Optional.of(flow).filter(axis::contains);
  }

  private Optional<DirectedStationPair> flowBetweenChainedIntervals(
      IntervalAnchor left, IntervalAnchor right) {
    if (left.direction().to().equals(right.direction().from())) {
      return Optional.of(new DirectedStationPair(left.direction().from(), right.direction().to()));
    }
    if (left.direction().from().equals(right.direction().to())) {
      return Optional.of(new DirectedStationPair(left.direction().to(), right.direction().from()));
    }
    return Optional.empty();
  }

  private List<IndexedPathAnchor> indexedAnchors(SemanticAxis axis, List<NodeId> pathNodes) {
    List<IndexedPathAnchor> anchors = new ArrayList<>();
    if (pathNodes == null) {
      return anchors;
    }
    for (int i = 0; i < pathNodes.size(); i++) {
      Optional<PathAnchor> anchor = anchorFor(axis, pathNodes.get(i));
      if (anchor.isPresent()) {
        anchors.add(new IndexedPathAnchor(i, anchor.get()));
      }
    }
    return anchors;
  }

  private Optional<DirectedStationPair> flowAcrossEdge(
      SemanticAxis axis, List<IndexedPathAnchor> leftNearest, List<IndexedPathAnchor> right) {
    for (IndexedPathAnchor left : leftNearest) {
      for (IndexedPathAnchor rightAnchor : right) {
        Optional<DirectedStationPair> flow = flowBetween(axis, left.anchor(), rightAnchor.anchor());
        if (flow.isPresent()) {
          return flow;
        }
      }
    }
    return Optional.empty();
  }

  private Optional<DirectedStationPair> flowWithinPathOrder(
      SemanticAxis axis, List<IndexedPathAnchor> anchors) {
    for (int i = 0; i < anchors.size(); i++) {
      for (int j = i + 1; j < anchors.size(); j++) {
        Optional<DirectedStationPair> flow =
            flowBetween(axis, anchors.get(i).anchor(), anchors.get(j).anchor());
        if (flow.isPresent()) {
          return flow;
        }
      }
    }
    return Optional.empty();
  }

  private Optional<DirectedStationPair> inferFlowFromSingleStationAnchor(
      SemanticAxis axis, List<IndexedPathAnchor> leftNearest, List<IndexedPathAnchor> right) {
    for (IndexedPathAnchor anchor : right) {
      Optional<String> station = anchor.anchor().station();
      if (station.isPresent()) {
        return axis.flowToward(station.get());
      }
    }
    for (IndexedPathAnchor anchor : leftNearest) {
      Optional<String> station = anchor.anchor().station();
      if (station.isPresent()) {
        return axis.flowAwayFrom(station.get());
      }
    }
    return Optional.empty();
  }

  private Optional<PathAnchor> anchorFor(SemanticAxis axis, NodeId node) {
    Optional<WaypointMetadata> metadata = metadata(node);
    if (metadata.isEmpty()) {
      return Optional.empty();
    }
    WaypointMetadata value = metadata.get();
    if (value.kind() == WaypointKind.INTERVAL) {
      DirectedStationPair direction = DirectedStationPair.from(value).orElse(null);
      if (direction == null || !axis.contains(direction)) {
        return Optional.empty();
      }
      return Optional.of(new PathAnchor(Optional.empty(), Optional.of(IntervalAnchor.from(value))));
    }
    if (axis.containsStation(value.originStation())) {
      return Optional.of(new PathAnchor(Optional.of(value.originStation()), Optional.empty()));
    }
    return Optional.empty();
  }

  private Optional<WaypointMetadata> metadata(NodeId node) {
    if (node == null) {
      return Optional.empty();
    }
    return graph.findNode(node).flatMap(railNode -> railNode.waypointMetadata());
  }

  private int edgeIndex(List<NodeId> pathNodes, NodeId from, NodeId to) {
    for (int i = 0; i + 1 < pathNodes.size(); i++) {
      if (from.equals(pathNodes.get(i)) && to.equals(pathNodes.get(i + 1))) {
        return i;
      }
    }
    return -1;
  }

  enum Status {
    RESOLVED,
    NO_AXIS,
    AMBIGUOUS,
    UNRESOLVED
  }

  record Result(Status status, CorridorDirection direction) {
    Result {
      Objects.requireNonNull(status, "status");
      direction = direction == null ? CorridorDirection.UNKNOWN : direction;
    }

    static Result resolved(CorridorDirection direction) {
      return new Result(Status.RESOLVED, direction);
    }

    static Result noAxis() {
      return new Result(Status.NO_AXIS, CorridorDirection.UNKNOWN);
    }

    static Result ambiguous() {
      return new Result(Status.AMBIGUOUS, CorridorDirection.UNKNOWN);
    }

    static Result unresolved() {
      return new Result(Status.UNRESOLVED, CorridorDirection.UNKNOWN);
    }

    boolean resolved() {
      return status == Status.RESOLVED && direction != CorridorDirection.UNKNOWN;
    }

    boolean blocksLegacyFallback() {
      return status == Status.AMBIGUOUS || status == Status.UNRESOLVED;
    }
  }

  private record AxisResolution(Status status, Optional<SemanticAxis> axis) {
    private AxisResolution {
      Objects.requireNonNull(status, "status");
      axis = axis == null ? Optional.empty() : axis;
    }

    static AxisResolution resolved(SemanticAxis axis) {
      return new AxisResolution(Status.RESOLVED, Optional.of(axis));
    }

    static AxisResolution noAxis() {
      return new AxisResolution(Status.NO_AXIS, Optional.empty());
    }

    static AxisResolution ambiguous() {
      return new AxisResolution(Status.AMBIGUOUS, Optional.empty());
    }
  }

  private record SemanticAxis(List<String> stations) {
    private SemanticAxis {
      Objects.requireNonNull(stations, "stations");
      stations = List.copyOf(stations);
      if (stations.size() < 2) {
        throw new IllegalArgumentException("语义方向轴至少需要两个站点");
      }
    }

    static Optional<SemanticAxis> from(Iterable<DirectedStationPair> pairs) {
      Map<String, String> nextByStation = new LinkedHashMap<>();
      Map<String, String> previousByStation = new LinkedHashMap<>();
      Set<String> stations = new LinkedHashSet<>();
      int edgeCount = 0;
      for (DirectedStationPair pair : pairs) {
        if (pair == null || pair.from().equals(pair.to())) {
          return Optional.empty();
        }
        stations.add(pair.from());
        stations.add(pair.to());
        String previousNext = nextByStation.putIfAbsent(pair.from(), pair.to());
        if (previousNext != null && !previousNext.equals(pair.to())) {
          return Optional.empty();
        }
        String previousPrevious = previousByStation.putIfAbsent(pair.to(), pair.from());
        if (previousPrevious != null && !previousPrevious.equals(pair.from())) {
          return Optional.empty();
        }
        edgeCount++;
      }
      if (edgeCount == 0) {
        return Optional.empty();
      }
      List<String> starts =
          stations.stream()
              .filter(station -> nextByStation.containsKey(station))
              .filter(station -> !previousByStation.containsKey(station))
              .toList();
      if (starts.size() != 1) {
        return Optional.empty();
      }
      List<String> ordered = new ArrayList<>();
      Set<String> visited = new LinkedHashSet<>();
      String current = starts.get(0);
      while (current != null) {
        if (!visited.add(current)) {
          return Optional.empty();
        }
        ordered.add(current);
        current = nextByStation.get(current);
      }
      if (ordered.size() != edgeCount + 1) {
        return Optional.empty();
      }
      return Optional.of(new SemanticAxis(ordered));
    }

    boolean contains(DirectedStationPair flow) {
      return flow != null && stations.contains(flow.from()) && stations.contains(flow.to());
    }

    boolean containsStation(String station) {
      return stations.contains(station);
    }

    Optional<CorridorDirection> directionOf(DirectedStationPair flow) {
      if (flow == null) {
        return Optional.empty();
      }
      int fromIndex = stations.indexOf(flow.from());
      int toIndex = stations.indexOf(flow.to());
      if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) {
        return Optional.empty();
      }
      if (fromIndex < toIndex) {
        return Optional.of(CorridorDirection.A_TO_B);
      }
      if (fromIndex > toIndex) {
        return Optional.of(CorridorDirection.B_TO_A);
      }
      return Optional.empty();
    }

    Optional<DirectedStationPair> flowToward(String station) {
      int index = stations.indexOf(station);
      if (index < 0) {
        return Optional.empty();
      }
      if (index == 0 && stations.size() == 2) {
        return Optional.of(new DirectedStationPair(stations.get(1), station));
      }
      if (index == stations.size() - 1) {
        return Optional.of(new DirectedStationPair(stations.get(index - 1), station));
      }
      return Optional.empty();
    }

    Optional<DirectedStationPair> flowAwayFrom(String station) {
      int index = stations.indexOf(station);
      if (index < 0) {
        return Optional.empty();
      }
      if (index == 0) {
        return Optional.of(new DirectedStationPair(station, stations.get(1)));
      }
      if (index == stations.size() - 1 && stations.size() == 2) {
        return Optional.of(new DirectedStationPair(station, stations.get(index - 1)));
      }
      return Optional.empty();
    }
  }

  private record StationPair(String first, String second) {
    private StationPair {
      Objects.requireNonNull(first, "first");
      Objects.requireNonNull(second, "second");
    }

    static StationPair of(String left, String right) {
      List<String> stations = new ArrayList<>(List.of(left, right));
      stations.sort(Comparator.naturalOrder());
      return new StationPair(stations.get(0), stations.get(1));
    }
  }

  private record DirectedStationPair(String from, String to) {
    private DirectedStationPair {
      Objects.requireNonNull(from, "from");
      Objects.requireNonNull(to, "to");
    }

    static Optional<DirectedStationPair> from(WaypointMetadata metadata) {
      if (metadata == null || metadata.destinationStation().isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(
          new DirectedStationPair(metadata.originStation(), metadata.destinationStation().get()));
    }

    DirectedStationPair reverse() {
      return new DirectedStationPair(to, from);
    }

    boolean sameOrientation(DirectedStationPair other) {
      return other != null && from.equals(other.from()) && to.equals(other.to());
    }
  }

  private record PathAnchor(Optional<String> station, Optional<IntervalAnchor> interval) {
    private PathAnchor {
      station = station == null ? Optional.empty() : station;
      interval = interval == null ? Optional.empty() : interval;
    }
  }

  private record IndexedPathAnchor(int index, PathAnchor anchor) {
    private IndexedPathAnchor {
      Objects.requireNonNull(anchor, "anchor");
    }
  }

  private record IntervalAnchor(DirectedStationPair direction, OptionalInt sequence) {
    private IntervalAnchor {
      Objects.requireNonNull(direction, "direction");
      sequence = sequence == null ? OptionalInt.empty() : sequence;
    }

    static IntervalAnchor from(WaypointMetadata metadata) {
      return new IntervalAnchor(
          DirectedStationPair.from(metadata).orElseThrow(), parseSequence(metadata));
    }
  }

  private static OptionalInt parseSequence(WaypointMetadata metadata) {
    if (metadata == null || metadata.sequence().isEmpty()) {
      return OptionalInt.empty();
    }
    try {
      return OptionalInt.of(Integer.parseInt(metadata.sequence().get()));
    } catch (NumberFormatException ignored) {
      return OptionalInt.empty();
    }
  }
}
