package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.control.EdgeOverrideRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.network.RailNetwork;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * 展开计划（{@link ExpandedRoutePlan}）的共用缓存，按图快照分开记。
 *
 * <p>运行时每个信号周期都要为每列车构建两三次占用请求，每次都把当前位置到交路终点的整段剩余交路展开一遍、 再对整段路径做一次单线方向判定；后者与路径长度近似平方增长，是请求构建里最贵的一步。
 * 同一列车在两个途经点之间、以及同一交路同一位置的不同列车，用的都是同一串节点，结果完全相同。
 *
 * <p><b>键</b>：底层图快照（不可变，重建即换新实例）+ 此刻因运维覆盖而封锁的边 + 实际传给构建器的节点串 （已含 DYNAMIC 选台与当前节点改写）。计划只读图的节点、
 * 邻接、封锁、走廊与单线 section 索引——只有下面三种图实现能保证这些由快照内容决定、覆盖视图只加封锁：{@link SimpleRailGraph}、跨世界路网的 {@link
 * RailNetwork.View}，以及二者之上的 {@link EdgeOverrideRailGraph}。其他实现（测试替身、改写走廊信息的包装等）一律不共用，每次现算。
 *
 * <p><b>不进缓存的</b>：截断成授权窗口（依赖速度、车长、车头位置算出的距离）、资源收集、尾部保护（读实际到达记录）、 联锁与冲突出口窗口、有向上下文，全部仍由每次申请在主线程现算。
 *
 * <p><b>回收</b>：快照不再被引用时整张表随之回收；每张表按最近使用至多留 {@link #capacityPerGraph()} 份。可在任意线程读写：后台预算写入、
 * 主线程查询互不干扰；两个线程同时未命中时各算一遍、先后写入同一结果。主线程从不等后台：没赶上的照旧同步算。
 *
 * <p><b>逐次比对</b>：系统属性 {@value #VERIFY_PROPERTY} 为 {@code true} 时，每次共用都再现算一份逐项核对，
 * 构建器还会把整次申请不共用再算一遍、连同日志逐条比较； 任何不一致都抛 {@link AssertionError}。只用于测试。
 */
public final class ExpandedRoutePlans {

  /** 打开逐次比对的系统属性。 */
  public static final String VERIFY_PROPERTY = "fetarute.expandedRoutePlan.verify";

  /** 每张图快照最多留多少份计划。 */
  private static final int LIMIT_PER_GRAPH = 2048;

  private static volatile boolean verifyEveryUse = Boolean.getBoolean(VERIFY_PROPERTY);

  private static final Map<RailGraph, Map<PlanKey, ExpandedRoutePlan>> PLANS =
      Collections.synchronizedMap(new WeakHashMap<>());

  private ExpandedRoutePlans() {}

  /**
   * 后台预算一串节点的展开计划（含整段方向判定），放进这张图的缓存。
   *
   * <p>只读不可变的图快照与覆盖视图，不碰 Bukkit API，不输出日志。图不可共用、节点串不足两个或含 null 时什么也不做。
   *
   * @param graph 运行时查询用的图（与控车同一个底层实例、同一组覆盖封锁）
   * @param nodes 交路节点串
   * @return 这次是否真的算了什么（计划与方向判定都已有则为 false）
   */
  public static boolean prewarm(RailGraph graph, List<NodeId> nodes) {
    if (graph == null || !cacheable(nodes) || nodes.size() < 2 || slotOf(graph) == null) {
      return false;
    }
    return new OccupancyRequestBuilder(graph, 1, 0, 0, 0).prewarmPlan(nodes);
  }

  /**
   * 这张图上这串节点的计划是否已经在缓存里（只查不算，也不改变淘汰顺序）。
   *
   * <p>覆盖视图按此刻被覆盖封锁的边区分。
   */
  public static boolean remembers(RailGraph graph, List<NodeId> nodes) {
    Slot slot = graph == null ? null : slotOf(graph);
    if (slot == null || !cacheable(nodes)) {
      return false;
    }
    Map<PlanKey, ExpandedRoutePlan> table = PLANS.get(slot.snapshot());
    return table != null && table.containsKey(new PlanKey(slot.overrideBlocked(), nodes));
  }

  /** 每张图快照最多留多少份计划；预算据此给运行时留出余量。 */
  public static int capacityPerGraph() {
    return LIMIT_PER_GRAPH;
  }

  /** 是否打开了逐次比对。 */
  static boolean verifying() {
    return verifyEveryUse;
  }

  /** 测试用：打开或关闭逐次比对。 */
  static void verifyEveryUse(boolean enabled) {
    verifyEveryUse = enabled;
  }

  /** 测试用：缓存里这串节点的计划；不可共用或没有时为空。 */
  static Optional<ExpandedRoutePlan> cachedPlan(RailGraph graph, List<NodeId> nodes) {
    Slot slot = graph == null ? null : slotOf(graph);
    if (slot == null || !cacheable(nodes)) {
      return Optional.empty();
    }
    Map<PlanKey, ExpandedRoutePlan> table = PLANS.get(slot.snapshot());
    return table == null
        ? Optional.empty()
        : Optional.ofNullable(table.get(new PlanKey(slot.overrideBlocked(), nodes)));
  }

  /** 测试用：把一份计划直接放进缓存，用来验证逐次比对能抓住不一致。 */
  static void put(RailGraph graph, List<NodeId> nodes, ExpandedRoutePlan plan) {
    Slot slot = slotOf(graph);
    if (slot == null || !cacheable(nodes)) {
      throw new IllegalArgumentException("图或节点串不可共用");
    }
    PLANS
        .computeIfAbsent(slot.snapshot(), ignored -> newTable())
        .put(new PlanKey(slot.overrideBlocked(), List.copyOf(nodes)), plan);
  }

  /**
   * 构建器取计划：能共用时查缓存，命中直接用、未命中现算后放进去；不能共用时现算。
   *
   * @param builder 现算用的构建器（其图即 {@code graph}）
   * @param cacheEnabled 构建器是否允许共用
   */
  static ExpandedRoutePlan planFor(
      OccupancyRequestBuilder builder, RailGraph graph, List<NodeId> nodes, boolean cacheEnabled) {
    Slot slot = cacheEnabled && cacheable(nodes) ? slotOf(graph) : null;
    if (slot == null) {
      return builder.computePlan(nodes);
    }
    Map<PlanKey, ExpandedRoutePlan> table =
        PLANS.computeIfAbsent(slot.snapshot(), ignored -> newTable());
    ExpandedRoutePlan cached = table.get(new PlanKey(slot.overrideBlocked(), nodes));
    if (cached != null) {
      if (verifyEveryUse) {
        cached.assertSameAs(builder.computePlan(nodes));
      }
      return cached;
    }
    ExpandedRoutePlan computed = builder.computePlan(nodes);
    table.put(new PlanKey(slot.overrideBlocked(), computed.nodes()), computed);
    return computed;
  }

  /**
   * 逐次比对整次申请：共用计划与全部现算的结果、路径上下文、诊断日志必须逐项相同。
   *
   * <p>有向上下文的 requestId 每次构建随机生成，比较前统一抹掉；其余各项按 {@code equals} 与文本表示双重比较。
   */
  static void assertSameBuild(
      Optional<OccupancyRequestContext> shared,
      Optional<OccupancyRequestContext> fresh,
      List<String> sharedLog,
      List<String> freshLog) {
    Optional<OccupancyRequestContext> left = shared.map(ExpandedRoutePlans::withoutRequestId);
    Optional<OccupancyRequestContext> right = fresh.map(ExpandedRoutePlans::withoutRequestId);
    if (!Objects.equals(left, right) || !String.valueOf(left).equals(String.valueOf(right))) {
      throw new AssertionError("共用展开计划的请求与现算不一致\n  shared=" + left + "\n  fresh =" + right);
    }
    if (!Objects.equals(sharedLog, freshLog)) {
      throw new AssertionError(
          "共用展开计划的诊断日志与现算不一致\n  shared=" + sharedLog + "\n  fresh =" + freshLog);
    }
  }

  private static OccupancyRequestContext withoutRequestId(OccupancyRequestContext context) {
    Optional<DirectedTraversalContext> directed =
        context.directedContext().map(ExpandedRoutePlans::withoutRequestId);
    OccupancyRequest request =
        context
            .request()
            .withDirectedContext(
                context.request().directedContext().map(ExpandedRoutePlans::withoutRequestId));
    return new OccupancyRequestContext(
        request,
        context.pathNodes(),
        context.edges(),
        directed,
        context.minimumSafeAuthorityDistanceBlocks());
  }

  private static DirectedTraversalContext withoutRequestId(DirectedTraversalContext context) {
    return new DirectedTraversalContext(
        context.trainKey(),
        context.routeId(),
        context.currentIndex(),
        context.currentNode(),
        context.lastPassedGraphNode(),
        context.effectiveFromNode(),
        context.effectiveToNode(),
        context.expandedPathNodes(),
        context.directedEdges(),
        context.singleConflictDirections(),
        context.switcherPathSignatures(),
        context.source(),
        context.occupancyVersion(),
        context.progressVersion(),
        "-",
        context.authorityTokenId(),
        context.canonicalRearRetainPathPlan());
  }

  /** 节点串可以作缓存键：非空、不含 null。 */
  private static boolean cacheable(List<NodeId> nodes) {
    if (nodes == null || nodes.isEmpty()) {
      return false;
    }
    for (NodeId node : nodes) {
      if (node == null) {
        return false;
      }
    }
    return true;
  }

  /**
   * 计划落在哪张表、带哪组覆盖封锁；不可共用的图实现返回 {@code null}。
   *
   * <p>计划用到的图查询（节点、邻接、封锁、走廊/section 索引）对这三种实现都只取决于快照内容；覆盖视图只在其上加封锁， 所以“底层快照 +
   * 此刻被覆盖封锁的边”相同，计划就相同。新增图实现前必须先确认这一点，不能只因为它能做最短路记忆就加进来。
   */
  private static Slot slotOf(RailGraph graph) {
    if (graph instanceof SimpleRailGraph || graph instanceof RailNetwork.View) {
      return new Slot(graph, Set.of());
    }
    if (graph instanceof EdgeOverrideRailGraph view
        && (view.delegate() instanceof SimpleRailGraph
            || view.delegate() instanceof RailNetwork.View)) {
      return new Slot(view.delegate(), view.overrideBlockedEdges());
    }
    return null;
  }

  private record Slot(RailGraph snapshot, Set<EdgeId> overrideBlocked) {}

  /**
   * 表内的键。
   *
   * <p>查询时直接拿调用方的节点串当键（{@link List#equals} 按内容比较），只有放进表时才存不可变副本。
   */
  private record PlanKey(Set<EdgeId> overrideBlocked, List<NodeId> nodes) {}

  private static Map<PlanKey, ExpandedRoutePlan> newTable() {
    return Collections.synchronizedMap(
        new LinkedHashMap<>(16, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(Map.Entry<PlanKey, ExpandedRoutePlan> eldest) {
            return size() > LIMIT_PER_GRAPH;
          }
        });
  }
}
