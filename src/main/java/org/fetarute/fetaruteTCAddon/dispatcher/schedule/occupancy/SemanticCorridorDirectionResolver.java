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
 * 元数据当作物理方向轴，例如 {@code PPK -> RVS}。方向优先从当前 movement path 上覆盖待判定边的连续线性锚点段解析， 避免远端
 * hub、站咽喉、库线、支线等旁路元数据污染当前列车实际路径；若当前边正落在本地分叉 gap， 则拒绝给出方向，让占用层按 UNKNOWN fail-closed 处理。
 */
final class SemanticCorridorDirectionResolver {

  private final RailGraph graph;

  SemanticCorridorDirectionResolver(RailGraph graph) {
    this.graph = Objects.requireNonNull(graph, "graph");
  }

  /**
   * 为同一条 movement path 建立可复用的本地语义方向索引。
   *
   * <p>一次占用请求会为路径上的多个 single conflict 查询方向。路径轴、分叉 gap 与 interval 锚点只取决于这条路径，不能为每一条边重复 构建；调用方应在处理同一
   * {@code pathNodes} 的所有冲突前只调用一次本方法。
   */
  PathAxisIndex indexPath(List<NodeId> pathNodes) {
    if (pathNodes == null || pathNodes.isEmpty()) {
      return new PathAxisIndex(List.of(), List.of());
    }
    List<IndexedDirectedStationPair> anchors = indexedDirectedPairs(pathNodes);
    List<PathAxisSegment> segments =
        anchors.isEmpty() ? List.of() : pathAxisSegments(pathNodes, anchors);
    List<PathAxisMatch> matches = new ArrayList<>();
    for (PathAxisSegment segment : segments) {
      List<IndexedPathAnchor> pathAnchors =
          segment
              .axisResolution()
              .axis()
              .map(axis -> indexedAnchors(axis, segment.pathNodes(), AnchorMode.PATH_LOCAL))
              .orElseGet(List::of);
      matches.add(new PathAxisMatch(segment, pathAnchors));
    }
    return new PathAxisIndex(pathNodes, matches);
  }

  Result resolve(List<NodeId> resourceNodes, PathAxisIndex pathAxisIndex, NodeId from, NodeId to) {
    PathAxisLookup pathAxis = resolvePathAxis(pathAxisIndex, from, to);
    Result pathResult =
        resolveWithAxis(
            pathAxis.resolution().axisResolution(),
            pathAxis.resolution().pathNodes(),
            pathAxis.pathAnchors(),
            from,
            to,
            AnchorMode.PATH_LOCAL);
    if (pathResult.status() != Status.NO_AXIS) {
      return pathResult;
    }
    return resolveWithAxis(
        resolveAxis(resourceNodes),
        pathAxis.resolution().pathNodes(),
        null,
        from,
        to,
        AnchorMode.RESOURCE_FALLBACK);
  }

  private Result resolveWithAxis(
      AxisResolution axisResolution,
      List<NodeId> pathNodes,
      List<IndexedPathAnchor> cachedPathAnchors,
      NodeId from,
      NodeId to,
      AnchorMode anchorMode) {
    if (axisResolution.status() == Status.NO_AXIS) {
      return Result.noAxis();
    }
    if (axisResolution.status() == Status.AMBIGUOUS) {
      return Result.ambiguous();
    }
    SemanticAxis axis = axisResolution.axis().orElseThrow();
    Optional<DirectedStationPair> flow =
        resolveFlow(axis, pathNodes, cachedPathAnchors, from, to, anchorMode);
    if (flow.isEmpty()) {
      return Result.unresolved();
    }
    return axis.directionOf(flow.get()).map(Result::resolved).orElseGet(Result::ambiguous);
  }

  private PathAxisLookup resolvePathAxis(PathAxisIndex pathAxisIndex, NodeId from, NodeId to) {
    if (pathAxisIndex == null || pathAxisIndex.pathNodes().isEmpty()) {
      return PathAxisLookup.noAxis(List.of());
    }
    List<NodeId> pathNodes = pathAxisIndex.pathNodes();
    int edgeIndex = edgeIndex(pathNodes, from, to);
    if (edgeIndex < 0) {
      return PathAxisLookup.noAxis(pathNodes);
    }
    List<PathAxisMatch> matches = pathAxisIndex.matches();
    if (matches.isEmpty()) {
      return PathAxisLookup.noAxis(pathNodes);
    }
    for (PathAxisMatch match : matches) {
      if (match.segment().containsEdge(edgeIndex)) {
        return PathAxisLookup.of(match);
      }
    }
    if (isPathAxisBranchGap(matches, edgeIndex)) {
      return PathAxisLookup.ambiguous(pathNodes);
    }
    return PathAxisLookup.noAxis(pathNodes);
  }

  private List<IndexedDirectedStationPair> indexedDirectedPairs(List<NodeId> pathNodes) {
    List<IndexedDirectedStationPair> pairs = new ArrayList<>();
    for (int i = 0; i < pathNodes.size(); i++) {
      int index = i;
      NodeId node = pathNodes.get(i);
      Optional<WaypointMetadata> metadata = metadata(node);
      if (metadata.isEmpty() || metadata.get().kind() != WaypointKind.INTERVAL) {
        continue;
      }
      DirectedStationPair.from(metadata.get())
          .ifPresent(pair -> pairs.add(new IndexedDirectedStationPair(index, pair)));
    }
    return pairs;
  }

  private List<PathAxisSegment> pathAxisSegments(
      List<NodeId> pathNodes, List<IndexedDirectedStationPair> anchors) {
    List<RawPathAxisSegment> rawSegments = new ArrayList<>();
    List<IndexedDirectedStationPair> current = new ArrayList<>();
    boolean startsAfterBranch = false;
    for (IndexedDirectedStationPair anchor : anchors) {
      if (current.isEmpty()) {
        current.add(anchor);
        continue;
      }
      List<DirectedStationPair> trialPairs = directionsOf(current);
      trialPairs.add(anchor.direction());
      AxisResolution trial = resolveAxisFromPairs(trialPairs);
      if (trial.status() == Status.RESOLVED) {
        current.add(anchor);
        continue;
      }
      rawSegments.add(RawPathAxisSegment.from(current, startsAfterBranch));
      current = new ArrayList<>();
      current.add(anchor);
      startsAfterBranch = true;
    }
    if (!current.isEmpty()) {
      rawSegments.add(RawPathAxisSegment.from(current, startsAfterBranch));
    }

    List<PathAxisSegment> segments = new ArrayList<>();
    int lastEdgeIndex = pathNodes.size() - 2;
    for (int i = 0; i < rawSegments.size(); i++) {
      RawPathAxisSegment raw = rawSegments.get(i);
      int startEdge = raw.startsAfterBranch() ? raw.firstAnchorIndex() : 0;
      if (i > 0 && !raw.startsAfterBranch()) {
        startEdge = raw.firstAnchorIndex() - 1;
      }
      int endEdge =
          i + 1 < rawSegments.size() && rawSegments.get(i + 1).startsAfterBranch()
              ? raw.lastAnchorIndex()
              : lastEdgeIndex;
      int fromIndex = Math.max(0, startEdge);
      int toIndex = Math.min(pathNodes.size(), endEdge + 2);
      segments.add(
          new PathAxisSegment(
              startEdge,
              endEdge,
              pathNodes.subList(fromIndex, toIndex),
              resolveAxisFromPairs(raw.directions()),
              raw.startsAfterBranch()));
    }
    return segments;
  }

  private boolean isPathAxisBranchGap(List<PathAxisMatch> matches, int edgeIndex) {
    for (int i = 0; i + 1 < matches.size(); i++) {
      PathAxisSegment left = matches.get(i).segment();
      PathAxisSegment right = matches.get(i + 1).segment();
      if (right.startsAfterBranch()
          && edgeIndex > left.endEdgeInclusive()
          && edgeIndex < right.startEdgeInclusive()) {
        return true;
      }
    }
    return false;
  }

  private List<DirectedStationPair> directionsOf(List<IndexedDirectedStationPair> pairs) {
    List<DirectedStationPair> directions = new ArrayList<>();
    for (IndexedDirectedStationPair pair : pairs) {
      directions.add(pair.direction());
    }
    return directions;
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
      SemanticAxis axis,
      List<NodeId> pathNodes,
      List<IndexedPathAnchor> cachedPathAnchors,
      NodeId from,
      NodeId to,
      AnchorMode anchorMode) {
    if (axis == null || pathNodes == null || pathNodes.size() < 2 || from == null || to == null) {
      return Optional.empty();
    }
    int edgeIndex = edgeIndex(pathNodes, from, to);
    if (edgeIndex < 0) {
      return Optional.empty();
    }
    List<IndexedPathAnchor> anchors =
        cachedPathAnchors == null ? indexedAnchors(axis, pathNodes, anchorMode) : cachedPathAnchors;
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

  private List<IndexedPathAnchor> indexedAnchors(
      SemanticAxis axis, List<NodeId> pathNodes, AnchorMode anchorMode) {
    List<IndexedPathAnchor> anchors = new ArrayList<>();
    if (pathNodes == null) {
      return anchors;
    }
    for (int i = 0; i < pathNodes.size(); i++) {
      Optional<PathAnchor> anchor = anchorFor(axis, pathNodes.get(i), anchorMode);
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

  /**
   * 把图节点转换为语义方向锚点。
   *
   * <p>路径本身已有 interval anchor 时只认真实 {@link WaypointKind#STATION}，避免 Depot 或 throat 的 {@code
   * originStation} 覆盖区间序列证据，导致 depot 授权与运行中请求对同一 single conflict 给出相反方向。只有在路径没有本地区间轴、需要回退到资源邻域轴时，
   * 才允许 Depot/throat 作为终端锚点推断进库/出库方向。
   */
  private Optional<PathAnchor> anchorFor(SemanticAxis axis, NodeId node, AnchorMode anchorMode) {
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
    if (isStationAnchorKind(value.kind(), anchorMode)
        && axis.containsStation(value.originStation())) {
      return Optional.of(new PathAnchor(Optional.of(value.originStation()), Optional.empty()));
    }
    return Optional.empty();
  }

  private boolean isStationAnchorKind(WaypointKind kind, AnchorMode anchorMode) {
    if (kind == WaypointKind.STATION) {
      return true;
    }
    return anchorMode == AnchorMode.RESOURCE_FALLBACK
        && (kind == WaypointKind.STATION_THROAT
            || kind == WaypointKind.DEPOT
            || kind == WaypointKind.DEPOT_THROAT);
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

  private enum AnchorMode {
    PATH_LOCAL,
    RESOURCE_FALLBACK
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

  /**
   * 针对当前待判定边裁剪后的路径轴结果。
   *
   * <p>当路径跨越多个不连续站间轴时，只把覆盖当前边的线性段交给方向解析，避免远端 hub 或支线锚点污染当前边。
   */
  private record PathAxisResolution(AxisResolution axisResolution, List<NodeId> pathNodes) {
    private PathAxisResolution {
      Objects.requireNonNull(axisResolution, "axisResolution");
      pathNodes = pathNodes == null ? List.of() : List.copyOf(pathNodes);
    }

    static PathAxisResolution of(AxisResolution axisResolution, List<NodeId> pathNodes) {
      return new PathAxisResolution(axisResolution, pathNodes);
    }

    static PathAxisResolution noAxis(List<NodeId> pathNodes) {
      return new PathAxisResolution(AxisResolution.noAxis(), pathNodes);
    }

    static PathAxisResolution ambiguous(List<NodeId> pathNodes) {
      return new PathAxisResolution(AxisResolution.ambiguous(), pathNodes);
    }
  }

  /** 已定位到单条 path edge 的语义轴结果与预解析锚点。 */
  private record PathAxisLookup(
      PathAxisResolution resolution, List<IndexedPathAnchor> pathAnchors) {
    private PathAxisLookup {
      Objects.requireNonNull(resolution, "resolution");
      pathAnchors = pathAnchors == null ? List.of() : List.copyOf(pathAnchors);
    }

    static PathAxisLookup of(PathAxisMatch match) {
      return new PathAxisLookup(
          PathAxisResolution.of(match.segment().axisResolution(), match.segment().pathNodes()),
          match.pathAnchors());
    }

    static PathAxisLookup noAxis(List<NodeId> pathNodes) {
      return new PathAxisLookup(PathAxisResolution.noAxis(pathNodes), List.of());
    }

    static PathAxisLookup ambiguous(List<NodeId> pathNodes) {
      return new PathAxisLookup(PathAxisResolution.ambiguous(pathNodes), List.of());
    }
  }

  /** 带路径下标的站间方向锚点，用于把同一条 movement path 切成局部语义段。 */
  private record IndexedDirectedStationPair(int index, DirectedStationPair direction) {
    private IndexedDirectedStationPair {
      Objects.requireNonNull(direction, "direction");
    }
  }

  /**
   * 尚未换算成 edge 范围的语义方向段。
   *
   * <p>{@code startsAfterBranch} 标记该段前方出现过不可合并的方向轴，后续会据此把段前 gap 视为 UNKNOWN。
   */
  private record RawPathAxisSegment(
      int firstAnchorIndex,
      int lastAnchorIndex,
      List<DirectedStationPair> directions,
      boolean startsAfterBranch) {
    private RawPathAxisSegment {
      Objects.requireNonNull(directions, "directions");
      directions = List.copyOf(directions);
    }

    static RawPathAxisSegment from(
        List<IndexedDirectedStationPair> pairs, boolean startsAfterBranch) {
      if (pairs == null || pairs.isEmpty()) {
        throw new IllegalArgumentException("语义方向段至少需要一个锚点");
      }
      int firstIndex = pairs.get(0).index();
      int lastIndex = pairs.get(pairs.size() - 1).index();
      List<DirectedStationPair> directions = new ArrayList<>();
      for (IndexedDirectedStationPair pair : pairs) {
        directions.add(pair.direction());
      }
      return new RawPathAxisSegment(firstIndex, lastIndex, directions, startsAfterBranch);
    }
  }

  /**
   * 可直接匹配待判定 edge 的局部语义段。
   *
   * <p>只有 {@code containsEdge} 命中的段可以参与当前边方向判定；落在两个段之间的 edge 保持 ambiguous。
   */
  private record PathAxisSegment(
      int startEdgeInclusive,
      int endEdgeInclusive,
      List<NodeId> pathNodes,
      AxisResolution axisResolution,
      boolean startsAfterBranch) {
    private PathAxisSegment {
      Objects.requireNonNull(pathNodes, "pathNodes");
      Objects.requireNonNull(axisResolution, "axisResolution");
      pathNodes = List.copyOf(pathNodes);
    }

    boolean containsEdge(int edgeIndex) {
      return edgeIndex >= startEdgeInclusive && edgeIndex <= endEdgeInclusive;
    }
  }

  /** 单一局部语义段及其只读 path 锚点。 */
  private record PathAxisMatch(PathAxisSegment segment, List<IndexedPathAnchor> pathAnchors) {
    private PathAxisMatch {
      Objects.requireNonNull(segment, "segment");
      pathAnchors = pathAnchors == null ? List.of() : List.copyOf(pathAnchors);
    }
  }

  /** 同一 movement path 的不可变语义方向索引，仅在本包内传递。 */
  static final class PathAxisIndex {

    private final List<NodeId> pathNodes;
    private final List<PathAxisMatch> matches;

    private PathAxisIndex(List<NodeId> pathNodes, List<PathAxisMatch> matches) {
      this.pathNodes = pathNodes == null ? List.of() : List.copyOf(pathNodes);
      this.matches = matches == null ? List.of() : List.copyOf(matches);
    }

    private List<NodeId> pathNodes() {
      return pathNodes;
    }

    private List<PathAxisMatch> matches() {
      return matches;
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
