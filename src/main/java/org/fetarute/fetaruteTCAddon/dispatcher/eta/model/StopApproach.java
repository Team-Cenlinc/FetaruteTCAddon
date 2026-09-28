package org.fetarute.fetaruteTCAddon.dispatcher.eta.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignTextParser;

/**
 * 停车点的进站判定：停在哪一类节点上，以及列车沿路径经过哪些节点时算"已经在进这个站"。
 *
 * <p>规则与运行时 {@code RuntimeDispatchService#resolveApproachControl} 相同，是它的图内版本——运行时还会从牌子注册表补节点元数据，
 * 这里只读调度图与节点 ID，因此同一份图永远给出同一个结论：
 *
 * <ul>
 *   <li>目标是车站本体或站咽喉 → {@link Kind#STATION}；车库本体或车库咽喉 → {@link Kind#DEPOT}；其余 → {@link
 *       Kind#WAYPOINT}（区间停车点）。
 *   <li>触发节点：与目标同运营商、同站、同股道的本体或咽喉，或与这样的节点直接相连的道岔；区间停车点与解析不出站点的目标只认它自己。
 * </ul>
 *
 * <p>进站限速区由 {@link #zones} 划定，编表运行曲线与运行时控车调同一个函数：区的起点落在图节点上，列车在区内不超过进站限速， 区外按车型减速度制动至区起点。
 */
public final class StopApproach {

  private StopApproach() {}

  /**
   * 进站限速区：里程 {@code [fromBlocks, toBlocks]} 内不超过进站限速。
   *
   * @param fromBlocks 起点里程（格）
   * @param toBlocks 终点里程（格），不小于起点
   */
  public record Zone(double fromBlocks, double toBlocks) {}

  /**
   * 沿路径划出进站限速区。编表运行曲线（{@link RunCurveModel}）与运行时控车共用这一个判据，两边的进站速度曲线因此一致。
   *
   * <p>逐节点看它之后的第一个触发节点：两者相距不超过进站窗口的格数，或相隔不超过窗口的边数时，从这个节点到下一个节点按进站限速运行。
   * 起点节点不算触发节点（列车已在它上面或已驶过），终点一定算。相邻的区合并为一个。
   *
   * @param positions 各节点里程（格），单调不减
   * @param triggers 各节点是否触发节点，与 {@code positions} 等长
   * @param rule 进站规则
   * @return 按里程排列的限速区；节点少于两个时为空
   */
  public static List<Zone> zones(double[] positions, boolean[] triggers, Rule rule) {
    Objects.requireNonNull(positions, "positions");
    Objects.requireNonNull(triggers, "triggers");
    Objects.requireNonNull(rule, "rule");
    if (positions.length != triggers.length) {
      throw new IllegalArgumentException("positions 与 triggers 数量不匹配");
    }
    int last = positions.length - 1;
    if (last < 1) {
      return List.of();
    }
    int[] nextTrigger = new int[positions.length];
    int upcoming = last;
    for (int i = last; i >= 0; i--) {
      nextTrigger[i] = upcoming;
      if (i > 0 && triggers[i]) {
        upcoming = i;
      }
    }
    List<Zone> zones = new ArrayList<>();
    for (int i = 0; i < last; i++) {
      int trigger = nextTrigger[i];
      if (!rule.covers(positions[trigger] - positions[i], trigger - i)) {
        continue;
      }
      int previous = zones.size() - 1;
      if (previous >= 0 && zones.get(previous).toBlocks() >= positions[i]) {
        zones.set(previous, new Zone(zones.get(previous).fromBlocks(), positions[i + 1]));
      } else {
        zones.add(new Zone(positions[i], positions[i + 1]));
      }
    }
    return List.copyOf(zones);
  }

  /**
   * 进站规则，对应配置 {@code runtime.approach-*}；编表运行曲线与运行时控车读同一组值。
   *
   * @param windowBlocks 到触发节点的距离不超过它时进入进站窗口；0 表示不按距离触发
   * @param windowEdges 到触发节点的边数不超过它时也进入窗口；0 表示不按边数触发
   * @param stationSpeedBps 车站与区间停车点的进站限速；0 表示不限速
   * @param depotSpeedBps 进库限速；0 表示不限速
   */
  public record Rule(
      double windowBlocks, int windowEdges, double stationSpeedBps, double depotSpeedBps) {

    public Rule {
      windowBlocks = Double.isFinite(windowBlocks) ? Math.max(0.0, windowBlocks) : 0.0;
      windowEdges = Math.max(0, windowEdges);
      stationSpeedBps = Double.isFinite(stationSpeedBps) ? Math.max(0.0, stationSpeedBps) : 0.0;
      depotSpeedBps = Double.isFinite(depotSpeedBps) ? Math.max(0.0, depotSpeedBps) : 0.0;
    }

    /** 不做进站限速。 */
    public static Rule disabled() {
      return new Rule(0.0, 0, 0.0, 0.0);
    }

    /** 读运行时配置 {@code runtime.approach-*}；编表与控车用同一组值。 */
    public static Rule fromRuntime(ConfigManager.RuntimeSettings runtime) {
      Objects.requireNonNull(runtime, "runtime");
      return new Rule(
          runtime.approachWindowBlocks(),
          runtime.approachWindowEdges(),
          runtime.approachSpeedBps(),
          runtime.approachDepotSpeedBps());
    }

    /**
     * 节点离它之后的第一个触发节点这么远时，是否已在进站窗口内。
     *
     * @param distanceBlocks 到触发节点的距离（格）
     * @param edges 到触发节点的边数
     */
    public boolean covers(double distanceBlocks, int edges) {
      return (windowBlocks > 0.0 && distanceBlocks <= windowBlocks)
          || (windowEdges > 0 && edges <= windowEdges);
    }

    /** 停车点类别对应的进站限速。 */
    public double speedFor(Kind kind) {
      return kind == Kind.DEPOT ? depotSpeedBps : stationSpeedBps;
    }
  }

  /** 停车点的类别：决定进站限速取哪个值、以及终点是否要停稳。 */
  public enum Kind {
    /** 车站：AutoStation 在站牌处接管，把车居中刹停。 */
    STATION,
    /** 车库：按进库限速进场。 */
    DEPOT,
    /** 区间停车点：调度层在节点处把车刹停。 */
    WAYPOINT
  }

  /**
   * 解析一个停车点。
   *
   * @param graph 调度图；节点不在图里时退回按节点 ID 解析
   * @param stopNode 停车点节点
   * @return 停车点及其触发规则
   */
  public static Target targetOf(RailGraph graph, NodeId stopNode) {
    Objects.requireNonNull(stopNode, "stopNode");
    Optional<WaypointMetadata> metadata = metadataOf(graph, stopNode);
    Kind kind =
        isStationLike(graph, stopNode, metadata)
            ? Kind.STATION
            : isDepotLike(graph, stopNode, metadata) ? Kind.DEPOT : Kind.WAYPOINT;
    Optional<Key> key = kind == Kind.WAYPOINT ? Optional.empty() : metadata.flatMap(Key::of);
    return new Target(stopNode, kind, key);
  }

  /**
   * 一个停车点。
   *
   * @param node 停车点节点
   * @param kind 类别
   * @param key 站点键；区间停车点或解析不出站点时为空
   */
  public record Target(NodeId node, Kind kind, Optional<Key> key) {

    public Target {
      Objects.requireNonNull(node, "node");
      Objects.requireNonNull(kind, "kind");
      key = key == null ? Optional.empty() : key;
    }

    /**
     * 列车经过这个节点时，是否算进入了本停车点的进站范围。
     *
     * @param graph 调度图
     * @param candidate 路径上的节点
     */
    public boolean triggeredBy(RailGraph graph, NodeId candidate) {
      if (candidate == null) {
        return false;
      }
      if (candidate.equals(node)) {
        return true;
      }
      if (key.isEmpty()) {
        return false;
      }
      Optional<WaypointMetadata> metadata = metadataOf(graph, candidate);
      boolean sameStop = metadata.flatMap(Key::of).map(key.get()::equals).orElse(false);
      if (sameStop
          && (kind == Kind.STATION
              ? isStationLike(graph, candidate, metadata)
              : isDepotLike(graph, candidate, metadata))) {
        return true;
      }
      return isSwitcher(graph, candidate, metadata) && adjacentToKey(graph, candidate, key.get());
    }
  }

  /**
   * 站点键：运营商 + 站（车库）+ 股道。本体与咽喉共享同一个键。
   *
   * @param depot 是否车库
   * @param operator 运营商（小写）
   * @param station 站或车库代码（小写）
   * @param track 股道号
   */
  public record Key(boolean depot, String operator, String station, int track) {

    static Optional<Key> of(WaypointMetadata metadata) {
      return switch (metadata.kind()) {
        case STATION, STATION_THROAT -> Optional.of(key(false, metadata));
        case DEPOT, DEPOT_THROAT -> Optional.of(key(true, metadata));
        default -> Optional.empty();
      };
    }

    private static Key key(boolean depot, WaypointMetadata metadata) {
      return new Key(
          depot,
          metadata.operator().trim().toLowerCase(Locale.ROOT),
          metadata.originStation().trim().toLowerCase(Locale.ROOT),
          metadata.trackNumber());
    }
  }

  private static boolean adjacentToKey(RailGraph graph, NodeId switcher, Key key) {
    if (graph == null) {
      return false;
    }
    for (RailEdge edge : graph.edgesFrom(switcher)) {
      if (edge == null) {
        continue;
      }
      NodeId neighbor = switcher.equals(edge.from()) ? edge.to() : edge.from();
      if (neighbor != null
          && metadataOf(graph, neighbor).flatMap(Key::of).map(key::equals).orElse(false)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isStationLike(
      RailGraph graph, NodeId node, Optional<WaypointMetadata> metadata) {
    return typeOf(graph, node).map(type -> type == NodeType.STATION).orElse(false)
        || metadata
            .map(
                value ->
                    value.kind() == WaypointKind.STATION
                        || value.kind() == WaypointKind.STATION_THROAT)
            .orElse(false);
  }

  private static boolean isDepotLike(
      RailGraph graph, NodeId node, Optional<WaypointMetadata> metadata) {
    return typeOf(graph, node).map(type -> type == NodeType.DEPOT).orElse(false)
        || metadata
            .map(
                value ->
                    value.kind() == WaypointKind.DEPOT || value.kind() == WaypointKind.DEPOT_THROAT)
            .orElse(false);
  }

  private static boolean isSwitcher(
      RailGraph graph, NodeId node, Optional<WaypointMetadata> metadata) {
    return typeOf(graph, node).map(type -> type == NodeType.SWITCHER).orElse(false)
        || metadata.map(value -> value.kind() == WaypointKind.SWITCHER).orElse(false);
  }

  private static Optional<NodeType> typeOf(RailGraph graph, NodeId node) {
    return graph == null ? Optional.empty() : graph.findNode(node).map(RailNode::type);
  }

  /** 节点元数据：图里有就用图的，没有再按节点 ID 解析（与运行时同一个解析器）。 */
  private static Optional<WaypointMetadata> metadataOf(RailGraph graph, NodeId node) {
    Optional<WaypointMetadata> fromGraph =
        graph == null ? Optional.empty() : graph.findNode(node).flatMap(RailNode::waypointMetadata);
    if (fromGraph.isPresent()) {
      return fromGraph;
    }
    return SignTextParser.parseWaypointLike(node.value(), NodeType.WAYPOINT)
        .flatMap(SignNodeDefinition::waypointMetadata);
  }
}
