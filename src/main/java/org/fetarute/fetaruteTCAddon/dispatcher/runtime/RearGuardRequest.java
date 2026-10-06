package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.AuthorizationPurpose;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.MovementPlanSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequestBuilder;

/** 运行中补回列尾防护用的请求：车身（累计到覆盖车长）与车尾之后若干条边。 */
final class RearGuardRequest {

  private RearGuardRequest() {}

  /**
   * @param builder 已配置列尾防护边数、车长与实际到达锚点的请求构建器
   * @param plan 本拍行车计划；有计划时按计划的有向路径取身后区段，否则按交路节点
   */
  static OccupancyRequest build(
      OccupancyRequestBuilder builder,
      String trainName,
      Optional<RouteId> routeId,
      List<NodeId> effectiveNodes,
      int currentIndex,
      Instant now,
      Optional<MovementPlanSnapshot> plan) {
    if (plan != null && plan.isPresent()) {
      return builder.buildRearGuardRequestFromPlan(
          trainName,
          routeId,
          effectiveNodes,
          currentIndex,
          now,
          0,
          AuthorizationPurpose.RUNTIME_MOVE,
          plan.get());
    }
    return builder.buildRearGuardRequestFromNodes(
        trainName,
        routeId,
        effectiveNodes,
        currentIndex,
        now,
        0,
        AuthorizationPurpose.RUNTIME_MOVE);
  }
}
