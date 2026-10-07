package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.TrainPositionResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ClaimRole;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.DirectedTraversalContext;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.MovementPlanSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;

/**
 * 延伸授权被拒时，沿列车已持有的移动授权刹停。
 *
 * <p>信号 tick 每拍都要求“车头 + 制动距离 + 安全余量”拿得到；拿不到时以前是当拍把速度清零（{@code group.stop()}），并把前方已持有的授权放掉、
 * 只留当前边。可上一拍授予的窗口本来就覆盖了制动距离：那段路仍由本车以 {@code MOVEMENT_REQUIRED} 独占，别的车拿不到，沿它刹到终点前是安全的。
 * 这里给出刹车期间要继续保留的资源，以及车头到“授权终点 − 停车余量”的距离，交给 STOP 速度曲线逐拍收敛；停稳以后照旧收缩到当前位置。
 *
 * <p>授权终点取当前有效 token 的 {@link MovementAuthorizationToken#authorityEndNode()}；若下一个 route
 * 节点更近就停在它前面—— 越过 route 节点要由推进点改写 TrainCarts destination，刹车途中不做这件事，道岔寻路也只认 destination。
 *
 * <p>任一证据缺失都不刹车，退回当拍停车（fail-closed）：列车静止、没有有效 token、token 终点不在本拍行车计划前方、计划与车头所在边对不上、 token
 * 里前方的任一资源已不再由本车以 {@code MOVEMENT_REQUIRED} 持有、车头到终点的路径不全在 token 内、边长或车头位置读不到。 车头位置用 {@link
 * TrainPositionResolver} 的坐标插值；插值被钳到边末端时无法判断越过了多少，同样按读不到处理。
 */
final class HeldAuthorityBraking {

  /** 判定列车仍在运动的最低速度（blocks/tick），与 STOP 曲线把速度判为 0 的阈值一致。 */
  private static final double MOVING_SPEED_EPSILON_BPT = 0.001;

  /** 信号最终校验的失败原因：停车时记下的阻挡仍归外车，本拍不放行。 */
  static final String STOP_BLOCKER_STILL_HELD = "active-occupancy-stop-blocker-still-held";

  private HeldAuthorityBraking() {}

  /**
   * 信号最终校验失败时，能否改为沿已持有授权刹车（判据仍由 {@link #resolve} 给出）。
   *
   * <p>只限 {@link #STOP_BLOCKER_STILL_HELD}，且列车确有带阻挡的停因：本拍窗口已经取得，只是原停因还没解除。其余失败——单线硬屏障、快照过期、
   * 硬授权不在手、令牌失效等——说明本拍授权本身有问题，照旧作废授权、当拍停车。
   *
   * @param reason 最终校验的失败原因
   * @param activeStop 列车当前的停车状态
   * @return 是否可以改为刹车
   */
  static boolean appliesToFinalValidationFailure(String reason, RuntimeStopState activeStop) {
    return STOP_BLOCKER_STILL_HELD.equals(reason)
        && activeStop != null
        && !activeStop.blockers().isEmpty();
  }

  /**
   * 刹车计划。
   *
   * @param retainedResources 刹车期间继续保留、不得在停车保持里释放的前方资源
   * @param stopDistanceBlocks 车头到停车点（授权终点 − 停车余量）的距离，恒为正
   * @param endNode 刹车终点所在的图节点
   */
  record Plan(Set<OccupancyResource> retainedResources, long stopDistanceBlocks, NodeId endNode) {
    Plan {
      retainedResources = Set.copyOf(retainedResources);
    }
  }

  /**
   * 判定结果。
   *
   * @param plan 可以沿已持有授权刹停时的计划
   * @param reason 计划缺失时为什么退回当拍停车；有计划时为 {@code held-authority}
   * @param moving 判定时列车是否仍在运动
   */
  record Decision(Optional<Plan> plan, String reason, boolean moving) {

    /** 不适用：调用方没有可判定的现场（例如健康恢复重发），按旧行为处理。 */
    static Decision notApplicable() {
      return new Decision(Optional.empty(), "not-applicable", false);
    }

    private static Decision instant(String reason, boolean moving) {
      return new Decision(Optional.empty(), reason, moving);
    }

    /** 刹车期间要保护的资源；没有计划时为空。 */
    Set<OccupancyResource> retainedResources() {
      return plan.map(Plan::retainedResources).orElse(Set.of());
    }

    /** 交给 STOP 曲线的停车距离；没有计划时为空（当拍停车）。 */
    OptionalLong stopDistanceBlocks() {
      return plan.map(value -> OptionalLong.of(value.stopDistanceBlocks()))
          .orElse(OptionalLong.empty());
    }

    /** 运行中被拒时写进停因明细的后缀；静止列车不加，停车保持的明细与旧版一致。 */
    String detailSuffix() {
      if (!moving) {
        return "";
      }
      return plan.isPresent() ? ":braking=held-authority" : ":braking=instant:" + reason;
    }
  }

  /**
   * 判定能否沿已持有的授权刹停。
   *
   * @param token 列车当前的移动授权 token
   * @param refusedRequest 本拍被拒的硬授权请求，其行车计划从 {@code currentNode} 起算
   * @param currentNode 本拍窗口起点（最近经过的图节点）
   * @param nextRouteNode 下一个 route 节点；刹车不越过它
   * @param selfClaims 本车此刻在账本里的全部 claim
   * @param graph 调度图
   * @param train 列车句柄，用于读速度与车头位置
   * @param stopMarginBlocks 停在授权终点之前的余量
   * @return 判定结果
   */
  static Decision resolve(
      MovementAuthorizationToken token,
      OccupancyRequest refusedRequest,
      NodeId currentNode,
      NodeId nextRouteNode,
      Collection<OccupancyClaim> selfClaims,
      RailGraph graph,
      RuntimeTrainHandle train,
      double stopMarginBlocks) {
    if (train == null
        || !train.isMoving()
        || !(train.currentSpeedBlocksPerTick() > MOVING_SPEED_EPSILON_BPT)) {
      return Decision.instant("stationary", false);
    }
    if (token == null
        || !token.active()
        || !token.hasPhysicalAuthorityBoundary()
        || token.authorityEndNode().isEmpty()) {
      return Decision.instant("no-active-authority", true);
    }
    Optional<MovementPlanSnapshot> planOpt =
        refusedRequest == null ? Optional.empty() : refusedRequest.movementPlanSnapshot();
    if (planOpt.isEmpty() || graph == null || currentNode == null) {
      return Decision.instant("movement-plan-missing", true);
    }
    List<NodeId> nodes = planOpt.get().expandedPathNodes();
    List<DirectedTraversalContext.DirectedEdge> edges = planOpt.get().directedEdges();
    if (nodes.size() < 2 || !currentNode.equals(nodes.get(0))) {
      return Decision.instant("plan-not-from-current-node", true);
    }
    int endIndex = nodes.subList(1, nodes.size()).indexOf(token.authorityEndNode().get()) + 1;
    if (endIndex <= 0) {
      return Decision.instant("authority-end-not-ahead", true);
    }
    if (nextRouteNode != null) {
      int routeNodeIndex = nodes.subList(1, nodes.size()).indexOf(nextRouteNode) + 1;
      if (routeNodeIndex > 0) {
        endIndex = Math.min(endIndex, routeNodeIndex);
      }
    }
    if (edges.size() < endIndex) {
      return Decision.instant("plan-inconsistent", true);
    }
    Set<OccupancyResource> heldMovement = new LinkedHashSet<>();
    for (OccupancyClaim claim : selfClaims == null ? List.<OccupancyClaim>of() : selfClaims) {
      if (claim != null && claim.role() == ClaimRole.MOVEMENT_REQUIRED) {
        heldMovement.add(claim.resource());
      }
    }
    Set<OccupancyResource> tokenAhead = new LinkedHashSet<>(token.resources());
    tokenAhead.retainAll(Set.copyOf(refusedRequest.resourceList()));
    if (!heldMovement.containsAll(tokenAhead)) {
      return Decision.instant("authority-released", true);
    }
    long pathLength = 0L;
    for (int i = 0; i < endIndex; i++) {
      DirectedTraversalContext.DirectedEdge edge = edges.get(i);
      if (!edge.fromNode().equals(nodes.get(i)) || !edge.toNode().equals(nodes.get(i + 1))) {
        return Decision.instant("plan-inconsistent", true);
      }
      if (!tokenAhead.contains(OccupancyResource.forEdge(edge.edgeId()))
          || !tokenAhead.contains(OccupancyResource.forNode(edge.toNode()))) {
        return Decision.instant("path-outside-authority", true);
      }
      OptionalLong length = edgeLength(graph, edge.fromNode(), edge.edgeId());
      if (length.isEmpty()) {
        return Decision.instant("edge-length-unknown", true);
      }
      pathLength += length.getAsLong();
    }
    OptionalLong headProgress = headProgressBlocks(train, graph, edges.get(0));
    if (headProgress.isEmpty()) {
      return Decision.instant("head-position-unknown", true);
    }
    long stopDistance =
        pathLength
            - headProgress.getAsLong()
            - (long)
                Math.ceil(
                    Math.max(0.0, Double.isFinite(stopMarginBlocks) ? stopMarginBlocks : 0.0));
    if (stopDistance <= 0L) {
      return Decision.instant("authority-end-reached", true);
    }
    return new Decision(
        Optional.of(new Plan(tokenAhead, stopDistance, nodes.get(endIndex))),
        "held-authority",
        true);
  }

  /** 车头已驶过首条边起点的距离，向上取整；插值读不到或被钳到末端时为空。 */
  private static OptionalLong headProgressBlocks(
      RuntimeTrainHandle train, RailGraph graph, DirectedTraversalContext.DirectedEdge firstEdge) {
    OptionalLong length = edgeLength(graph, firstEdge.fromNode(), firstEdge.edgeId());
    if (length.isEmpty()) {
      return OptionalLong.empty();
    }
    OptionalDouble ratio =
        TrainPositionResolver.resolve(
                train, graph, firstEdge.fromNode(), firstEdge.toNode(), length.getAsLong())
            .edgeProgressRatio();
    if (ratio.isEmpty() || !(ratio.getAsDouble() < 1.0)) {
      return OptionalLong.empty();
    }
    return OptionalLong.of((long) Math.ceil(ratio.getAsDouble() * length.getAsLong()));
  }

  private static OptionalLong edgeLength(RailGraph graph, NodeId from, EdgeId edgeId) {
    for (RailEdge edge : graph.edgesFrom(from)) {
      if (edge != null && edgeId.equals(edge.id())) {
        return OptionalLong.of(Math.max(0L, edge.lengthBlocks()));
      }
    }
    return OptionalLong.empty();
  }
}
