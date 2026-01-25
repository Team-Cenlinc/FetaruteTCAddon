package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequestContext;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;

/**
 * 信号前瞻：沿路径计算到前方各限制点的距离。
 *
 * <p>用于提前减速：列车不仅考虑"到下一节点"的距离，还考虑"到前方最近红灯/限速信号"的距离。
 *
 * <p>性能注意：结果应在同一 tick 内复用，避免重复计算。
 */
public final class SignalLookahead {

  private SignalLookahead() {}

  /**
   * 前瞻结果：包含到各类限制点的距离。
   *
   * @param distanceToBlocker 到首个阻塞资源的距离（STOP 信号来源）
   * @param distanceToCaution 到首个 CAUTION 限速区域的距离
   * @param distanceToApproach 到需要 approaching 限速的节点（Station/Depot）的距离
   * @param effectiveSignal 综合信号（考虑前瞻后的最严格信号）
   */
  public record LookaheadResult(
      OptionalLong distanceToBlocker,
      OptionalLong distanceToCaution,
      OptionalLong distanceToApproach,
      SignalAspect effectiveSignal) {

    public LookaheadResult {
      Objects.requireNonNull(distanceToBlocker, "distanceToBlocker");
      Objects.requireNonNull(distanceToCaution, "distanceToCaution");
      Objects.requireNonNull(distanceToApproach, "distanceToApproach");
      Objects.requireNonNull(effectiveSignal, "effectiveSignal");
    }

    /** 获取到最近限制点的距离（用于速度曲线计算）。 */
    public OptionalLong minConstraintDistance() {
      return minStopConstraintDistance();
    }

    /**
     * 获取到最近停车约束点的距离（blocker/caution，不含 approaching）。
     *
     * <p>Approaching 限速有独立的目标速度（如 4 bps），不应与 stop（0 bps）混用。
     */
    public OptionalLong minStopConstraintDistance() {
      long min = Long.MAX_VALUE;
      if (distanceToBlocker.isPresent()) {
        min = Math.min(min, distanceToBlocker.getAsLong());
      }
      if (distanceToCaution.isPresent()) {
        min = Math.min(min, distanceToCaution.getAsLong());
      }
      // 注意：不包含 distanceToApproach，因为 approaching 有独立目标速度
      return min == Long.MAX_VALUE ? OptionalLong.empty() : OptionalLong.of(min);
    }

    /** 空结果：无任何前方限制。 */
    public static LookaheadResult empty(SignalAspect currentSignal) {
      return new LookaheadResult(
          OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty(), currentSignal);
    }
  }

  /**
   * 计算前瞻结果。
   *
   * @param decision 当前占用判定结果
   * @param context 占用请求上下文（含路径信息）
   * @param currentSignal 当前信号状态
   * @param approachNodePredicate 判断节点是否需要 approaching 限速
   * @return 前瞻结果
   */
  public static LookaheadResult compute(
      OccupancyDecision decision,
      OccupancyRequestContext context,
      SignalAspect currentSignal,
      java.util.function.Predicate<NodeId> approachNodePredicate) {
    if (decision == null || context == null || currentSignal == null) {
      return LookaheadResult.empty(currentSignal != null ? currentSignal : SignalAspect.STOP);
    }

    List<NodeId> nodes = context.pathNodes();
    List<RailEdge> edges = context.edges();
    if (nodes.isEmpty() || edges.isEmpty()) {
      return LookaheadResult.empty(currentSignal);
    }

    // 构建节点距离表（一次遍历）
    List<Long> nodeDistances = computeNodeDistances(nodes, edges);

    // 1. 到阻塞资源的距离
    OptionalLong distanceToBlocker = resolveBlockerDistance(decision, context, nodeDistances);

    // 2. 到 CAUTION 区域的距离（如果当前信号不是 CAUTION，查找前方首个 CAUTION）
    OptionalLong distanceToCaution = OptionalLong.empty();
    if (currentSignal == SignalAspect.PROCEED
        && decision.signal() == SignalAspect.PROCEED_WITH_CAUTION) {
      // 当前 PROCEED 但判定为 PROCEED_WITH_CAUTION，说明前方有限速区
      distanceToCaution = distanceToBlocker; // 复用阻塞距离作为近似
    }

    // 3. 到 approaching 节点的距离
    OptionalLong distanceToApproach = OptionalLong.empty();
    if (approachNodePredicate != null) {
      distanceToApproach = findApproachNodeDistance(nodes, nodeDistances, approachNodePredicate);
    }

    // 综合信号：取最严格
    SignalAspect effectiveSignal = currentSignal;
    if (distanceToBlocker.isPresent() && distanceToBlocker.getAsLong() <= 0) {
      effectiveSignal = SignalAspect.STOP;
    } else if (!decision.allowed()) {
      effectiveSignal = SignalAspect.STOP;
    }

    return new LookaheadResult(
        distanceToBlocker, distanceToCaution, distanceToApproach, effectiveSignal);
  }

  /** 计算路径上每个节点到起点的累计距离。 */
  private static List<Long> computeNodeDistances(List<NodeId> nodes, List<RailEdge> edges) {
    List<Long> distances = new ArrayList<>(nodes.size());
    long distance = 0;
    distances.add(0L); // 起点距离为 0

    int maxIndex = Math.min(edges.size(), nodes.size() - 1);
    for (int i = 0; i < maxIndex; i++) {
      RailEdge edge = edges.get(i);
      if (edge != null) {
        distance += Math.max(0, edge.lengthBlocks());
      }
      distances.add(distance);
    }
    return distances;
  }

  /** 计算到首个阻塞资源的距离。 */
  private static OptionalLong resolveBlockerDistance(
      OccupancyDecision decision, OccupancyRequestContext context, List<Long> nodeDistances) {
    if (decision.blockers().isEmpty()) {
      return OptionalLong.empty();
    }

    List<NodeId> nodes = context.pathNodes();
    List<RailEdge> edges = context.edges();

    // 构建资源到距离的映射
    java.util.Map<String, Long> resourceDistances = new java.util.HashMap<>();
    long distance = 0;
    for (int i = 0; i < nodes.size(); i++) {
      NodeId node = nodes.get(i);
      if (node != null) {
        resourceDistances.put(node.value(), distance);
      }
      if (i < edges.size() && edges.get(i) != null) {
        RailEdge edge = edges.get(i);
        String edgeKey = OccupancyResource.forEdge(edge.id()).key();
        resourceDistances.put(edgeKey, distance);
        distance += Math.max(0, edge.lengthBlocks());
      }
    }

    long best = Long.MAX_VALUE;
    for (OccupancyClaim claim : decision.blockers()) {
      if (claim == null || claim.resource() == null) {
        continue;
      }
      OccupancyResource resource = claim.resource();
      String key = resource.key();
      Long resourceDistance = resourceDistances.get(key);
      if (resourceDistance != null && resourceDistance < best) {
        best = resourceDistance;
      }
    }

    return best == Long.MAX_VALUE ? OptionalLong.empty() : OptionalLong.of(best);
  }

  /** 查找路径上首个需要 approaching 限速的节点。 */
  private static OptionalLong findApproachNodeDistance(
      List<NodeId> nodes,
      List<Long> nodeDistances,
      java.util.function.Predicate<NodeId> predicate) {
    // 从第二个节点开始查找（跳过当前节点）
    for (int i = 1; i < nodes.size() && i < nodeDistances.size(); i++) {
      NodeId node = nodes.get(i);
      if (node != null && predicate.test(node)) {
        return OptionalLong.of(nodeDistances.get(i));
      }
    }
    return OptionalLong.empty();
  }
}
