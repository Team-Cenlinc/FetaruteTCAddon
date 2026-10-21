package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequestBuilder.CorridorDirectionResolution;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequestBuilder.TurnbackSplit;

/**
 * 一串交路节点在一张图快照上的展开计划：逐段展开后的图节点、在折返处切分后的路径、切分后路径的各段图边， 以及按需算出的单线方向判定。
 *
 * <p>这些只取决于图的内容（底层快照与此刻被覆盖封锁的边）和这串节点，与列车、占用、进度、时钟都无关， 所以同一串节点的计划可以被多次申请共用，见 {@link
 * ExpandedRoutePlans}。截断成授权窗口、收集资源、尾部保护等步骤仍由每次申请按自己的设置与实时量现算。
 *
 * <p>展开、取边与切分时产生的诊断日志不在这里输出：失败日志原文与切分位置作为数据存下， 由使用计划的构建器按本次的列车、交路与用途重新输出，与不共用时逐条相同。
 *
 * <p>除两项方向判定外全部在构造时算好；方向判定第一次用到时才算（整段方向判定是请求构建里最贵的一步），
 * 之后共用。可在任意线程读取：后台预算与主线程使用互不干扰，两边同时算同一项时结果相同，留哪一份都一样。
 */
final class ExpandedRoutePlan {

  private final List<NodeId> nodes;
  private final List<NodeId> expanded;
  private final List<String> expandLog;
  private final Optional<TurnbackSplit> split;
  private final List<NodeId> splitPath;
  private final List<RailEdge> splitEdges;
  private final List<String> splitEdgesLog;

  /** 切分后整条路径的方向判定；未算时为 null。 */
  private volatile CorridorDirectionResolution directions;

  /** 切分后路径各前缀（按节点数）的方向判定，供授权窗口共用。 */
  private final Map<Integer, CorridorDirectionResolution> prefixDirections =
      new ConcurrentHashMap<>();

  /**
   * @param nodes 输入节点串
   * @param expanded 逐段展开结果；不连通时为空
   * @param expandLog 展开时的诊断日志
   * @param split 折返处的切分；没有时为空
   * @param splitPath 切分后的路径（没有切分时就是展开结果）
   * @param splitEdges 切分后路径的各段图边；有一段取不到时为空
   * @param splitEdgesLog 取边时的诊断日志
   */
  ExpandedRoutePlan(
      List<NodeId> nodes,
      List<NodeId> expanded,
      List<String> expandLog,
      Optional<TurnbackSplit> split,
      List<NodeId> splitPath,
      List<RailEdge> splitEdges,
      List<String> splitEdgesLog) {
    // 输入可能含 null（展开时按不连通处理），不能用 List.copyOf。
    this.nodes = Collections.unmodifiableList(new ArrayList<>(nodes));
    this.expanded = List.copyOf(expanded);
    this.expandLog = List.copyOf(expandLog);
    this.split = Objects.requireNonNull(split, "split");
    this.splitPath = List.copyOf(splitPath);
    this.splitEdges = List.copyOf(splitEdges);
    this.splitEdgesLog = List.copyOf(splitEdgesLog);
  }

  /** 输入节点串。 */
  List<NodeId> nodes() {
    return nodes;
  }

  /** 逐段展开结果；不连通时为空。 */
  List<NodeId> expanded() {
    return expanded;
  }

  /** 展开时的诊断日志，使用时原样输出。 */
  List<String> expandLog() {
    return expandLog;
  }

  /** 折返处的切分；使用时按本次的列车、交路与用途输出切分日志。 */
  Optional<TurnbackSplit> split() {
    return split;
  }

  /** 切分后的路径。 */
  List<NodeId> splitPath() {
    return splitPath;
  }

  /** 切分后路径的各段图边；有一段取不到时为空。 */
  List<RailEdge> splitEdges() {
    return splitEdges;
  }

  /** 取边时的诊断日志，使用时原样输出。 */
  List<String> splitEdgesLog() {
    return splitEdgesLog;
  }

  /** 整条路径的方向判定是否已经算过。 */
  boolean hasDirections() {
    return directions != null;
  }

  /**
   * 切分后整条路径的单线方向判定。
   *
   * @param compute 现算方向判定（构建器自己的实现）；第一次用到时算，打开逐次比对时每次共用都再算一份核对
   */
  CorridorDirectionResolution directions(
      Function<List<NodeId>, CorridorDirectionResolution> compute) {
    CorridorDirectionResolution cached = directions;
    if (cached == null) {
      CorridorDirectionResolution computed = compute.apply(splitPath);
      directions = computed;
      return computed;
    }
    if (ExpandedRoutePlans.verifying()) {
      assertSame("directions", cached, compute.apply(splitPath));
    }
    return cached;
  }

  /**
   * 切分后路径某个前缀（授权窗口）的单线方向判定。
   *
   * <p>窗口不是本计划切分后路径的前缀时不共用，直接现算。
   *
   * @param window 授权窗口节点
   * @param compute 现算方向判定（构建器自己的实现）
   */
  CorridorDirectionResolution prefixDirections(
      List<NodeId> window, Function<List<NodeId>, CorridorDirectionResolution> compute) {
    if (!isPrefixOfSplitPath(window)) {
      return compute.apply(window);
    }
    Integer size = window.size();
    CorridorDirectionResolution cached = prefixDirections.get(size);
    if (cached == null) {
      CorridorDirectionResolution computed = compute.apply(window);
      // 并发时两边算出的结果相同，后写覆盖先写无妨。
      prefixDirections.put(size, computed);
      return computed;
    }
    if (ExpandedRoutePlans.verifying()) {
      assertSame("prefixDirections[" + size + "]", cached, compute.apply(window));
    }
    return cached;
  }

  private boolean isPrefixOfSplitPath(List<NodeId> window) {
    if (window == null || window.size() > splitPath.size()) {
      return false;
    }
    for (int index = 0; index < window.size(); index++) {
      if (!splitPath.get(index).equals(window.get(index))) {
        return false;
      }
    }
    return true;
  }

  /**
   * 与同一输入现算的计划逐项比较，不同就抛 {@link AssertionError}。
   *
   * <p>只比构造时算好的各项；按需算的方向判定在各自取用时核对。
   */
  void assertSameAs(ExpandedRoutePlan fresh) {
    assertSame("nodes", nodes, fresh.nodes);
    assertSame("expanded", expanded, fresh.expanded);
    assertSame("expandLog", expandLog, fresh.expandLog);
    assertSame("split", split, fresh.split);
    assertSame("splitPath", splitPath, fresh.splitPath);
    assertSame("splitEdges", splitEdges, fresh.splitEdges);
    assertSame("splitEdgesLog", splitEdgesLog, fresh.splitEdgesLog);
  }

  private void assertSame(String field, Object cached, Object fresh) {
    if (!Objects.equals(cached, fresh) || !String.valueOf(cached).equals(String.valueOf(fresh))) {
      throw new AssertionError(
          "展开计划共用结果与现算不一致: field="
              + field
              + " nodes="
              + nodes
              + "\n  cached="
              + cached
              + "\n  fresh ="
              + fresh);
    }
  }
}
