package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingState;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;

/**
 * 线程安全的不可变调度图快照实现。
 *
 * <h2>遍历顺序（跨进程确定）</h2>
 *
 * <ul>
 *   <li>{@link #nodes()} 按 {@link NodeId} 自然序；
 *   <li>{@link #edges()} 按 {@code edgesById} 键（{@link EdgeId}）的自然序；
 *   <li>{@link #edgesFrom(NodeId)} 是 {@link #edges()} 中与该节点相邻者的子序列，顺序相同（对规范化 {@link EdgeId} 等价于按
 *       另一端点的 {@link NodeId} 自然序）。
 * </ul>
 *
 * <p>为什么不能用 {@code Set.copyOf}/{@code Map.copyOf}：JDK 不可变集合的遍历顺序取决于 {@code
 * ImmutableCollections.SALT32L}，每个 JVM 启动时随机一次。于是凡是"先遍历到谁就选谁"的消费方（最短路的等长平局、冲突走廊/单线 section
 * 的代表节点等）会在每次重启后换一个结果，而同一进程内又完全稳定——测试里只表现为"场景结局随进程二选一"。
 *
 * <p>本顺序只为可复现而定，不表达运营偏好；需要选择语义的地方（选台、路径平局）必须在消费方显式写出规则，而不是依赖这里的顺序。
 */
public final class SimpleRailGraph
    implements RailGraph, RailGraphSectionSupport, RailGraphInterlockingSupport {

  private final Map<NodeId, RailNode> nodesById;
  private final Map<EdgeId, RailEdge> edgesById;
  private final Map<NodeId, Set<RailEdge>> edgesFrom;
  private final Set<EdgeId> blockedEdges;
  private final RailInterlockingState interlockingState;
  private volatile RailGraphConflictIndex conflictIndex;
  private volatile SingleLineSectionIndex sectionIndex;

  public SimpleRailGraph(
      Map<NodeId, RailNode> nodesById, Map<EdgeId, RailEdge> edgesById, Set<EdgeId> blockedEdges) {
    this(nodesById, edgesById, blockedEdges, RailInterlockingState.unavailable());
  }

  /**
   * 创建携带世界级物理联锁状态的不可变图快照。
   *
   * @param nodesById 节点快照
   * @param edgesById 区间快照
   * @param blockedEdges 已封锁区间
   * @param interlockingState 与区间 universe 同版本的联锁状态
   */
  public SimpleRailGraph(
      Map<NodeId, RailNode> nodesById,
      Map<EdgeId, RailEdge> edgesById,
      Set<EdgeId> blockedEdges,
      RailInterlockingState interlockingState) {
    Objects.requireNonNull(nodesById, "nodesById");
    Objects.requireNonNull(edgesById, "edgesById");
    Objects.requireNonNull(blockedEdges, "blockedEdges");
    this.nodesById = sortedByKey(nodesById);
    this.edgesById = sortedByKey(edgesById);
    this.blockedEdges = Set.copyOf(blockedEdges);
    this.interlockingState = Objects.requireNonNull(interlockingState, "interlockingState");
    this.edgesFrom = buildAdjacency(this.nodesById, this.edgesById);
  }

  /**
   * @return 空图快照。
   */
  public static SimpleRailGraph empty() {
    return new SimpleRailGraph(Map.of(), Map.of(), Set.of());
  }

  @Override
  public Collection<RailNode> nodes() {
    return nodesById.values();
  }

  @Override
  public Collection<RailEdge> edges() {
    return edgesById.values();
  }

  /** 使用不可变 edge 索引执行常数时间查询。 */
  @Override
  public Optional<RailEdge> findEdge(EdgeId id) {
    if (id == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(edgesById.get(EdgeId.undirected(id.a(), id.b())));
  }

  @Override
  public Optional<RailNode> findNode(NodeId id) {
    Objects.requireNonNull(id, "id");
    return Optional.ofNullable(nodesById.get(id));
  }

  @Override
  public Set<RailEdge> edgesFrom(NodeId id) {
    Objects.requireNonNull(id, "id");
    return edgesFrom.getOrDefault(id, Set.of());
  }

  @Override
  public boolean isBlocked(EdgeId id) {
    Objects.requireNonNull(id, "id");
    return blockedEdges.contains(id);
  }

  /** 返回与当前图快照同时构建的物理联锁状态。 */
  @Override
  public RailInterlockingState interlockingState() {
    return interlockingState;
  }

  /**
   * 查询指定边的冲突组 key。
   *
   * <p>索引懒加载并缓存，避免每次查询重算。
   */
  @Override
  public Optional<String> conflictKeyForEdge(EdgeId edgeId) {
    if (edgeId == null || edgeId.a() == null || edgeId.b() == null) {
      return Optional.empty();
    }
    RailGraphConflictIndex index = conflictIndex;
    if (index == null) {
      synchronized (this) {
        index = conflictIndex;
        if (index == null) {
          index = RailGraphConflictIndex.fromGraph(this);
          conflictIndex = index;
        }
      }
    }
    return index.conflictKeyForEdge(edgeId);
  }

  /**
   * 查询指定边所属的走廊信息。
   *
   * <p>索引懒加载并缓存，避免重复构建。
   */
  @Override
  public Optional<RailGraphCorridorInfo> corridorInfoForEdge(EdgeId edgeId) {
    if (edgeId == null || edgeId.a() == null || edgeId.b() == null) {
      return Optional.empty();
    }
    RailGraphConflictIndex index = conflictIndex;
    if (index == null) {
      synchronized (this) {
        index = conflictIndex;
        if (index == null) {
          index = RailGraphConflictIndex.fromGraph(this);
          conflictIndex = index;
        }
      }
    }
    return index.corridorInfoForEdge(edgeId);
  }

  /**
   * 查询指定边所属的单线 section 信息。
   *
   * <p>section 索引是 {@link RailGraphConflictIndex} 的叠加层，只归并能够由桥证明不存在替代路径的连续链，不改变既有 corridor/switcher
   * 诊断 key。
   */
  @Override
  public Optional<SingleLineSectionInfo> sectionInfoForEdge(EdgeId edgeId) {
    if (edgeId == null || edgeId.a() == null || edgeId.b() == null) {
      return Optional.empty();
    }
    SingleLineSectionIndex index = sectionIndex;
    if (index == null) {
      synchronized (this) {
        index = sectionIndex;
        if (index == null) {
          index = SingleLineSectionIndex.fromGraph(this);
          sectionIndex = index;
        }
      }
    }
    return index.sectionInfoForEdge(edgeId);
  }

  /**
   * 按键的自然序复制为不可变 map：查找仍是 O(1)，遍历顺序与 JVM 无关。
   *
   * <p>与此前的 {@code Map.copyOf} 一样拒绝 null 键/值。
   */
  private static <K extends Comparable<K>, V> Map<K, V> sortedByKey(Map<K, V> source) {
    List<K> keys = new ArrayList<>(source.size());
    for (Map.Entry<K, V> entry : source.entrySet()) {
      keys.add(Objects.requireNonNull(entry.getKey(), "key"));
      Objects.requireNonNull(entry.getValue(), "value");
    }
    Collections.sort(keys);
    Map<K, V> sorted = new LinkedHashMap<>(Math.max(16, (int) (keys.size() / 0.75f) + 1));
    for (K key : keys) {
      sorted.put(key, source.get(key));
    }
    return Collections.unmodifiableMap(sorted);
  }

  /**
   * 构建邻接表（无向图）。
   *
   * <p>按 {@code edges} 的遍历顺序（已按 {@link EdgeId} 自然序）逐条挂到两端，因此每个节点的邻接集合天然是 {@link #edges()}
   * 的子序列，不需要再排序。
   */
  private static Map<NodeId, Set<RailEdge>> buildAdjacency(
      Map<NodeId, RailNode> nodes, Map<EdgeId, RailEdge> edges) {
    Map<NodeId, Set<RailEdge>> adjacency = new HashMap<>();
    for (NodeId nodeId : nodes.keySet()) {
      adjacency.put(nodeId, new LinkedHashSet<>());
    }
    for (RailEdge edge : edges.values()) {
      adjacency.computeIfAbsent(edge.from(), ignored -> new LinkedHashSet<>()).add(edge);
      adjacency.computeIfAbsent(edge.to(), ignored -> new LinkedHashSet<>()).add(edge);
    }
    Map<NodeId, Set<RailEdge>> frozen = new HashMap<>();
    for (Map.Entry<NodeId, Set<RailEdge>> entry : adjacency.entrySet()) {
      frozen.put(entry.getKey(), Collections.unmodifiableSet(entry.getValue()));
    }
    return Collections.unmodifiableMap(frozen);
  }
}
