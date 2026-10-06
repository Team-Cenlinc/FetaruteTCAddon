package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequestContext;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceKind;

/**
 * 计划停车点之后足够远的道岔：它的冲突键不作为进站前的停车约束。
 *
 * <p>凡一端连着道岔的区间都带这个道岔的冲突键，前瞻把冲突的起点量在“通往道岔那条区间”的起点上。车站后面紧接一段长区间再到道岔时，这个起点就是站台节点：
 * 别的车经过道岔，驶向站台的车就被当成“前方站台节点处有硬约束”，在制动距离内当拍停在站外，而它本来就要在站台停车。
 *
 * <p>道岔离停车点的距离不小于 {@code clearanceBlocks}（保守车长 + 停车余量）时，停站时车头越过站台节点也碰不到它，此时只去掉这把抽象冲突键；
 * 道岔节点、区间等物理占用照常算距离。出站时硬授权窗口仍要取得这把冲突键，出站照样被挡。只作用于前瞻测距，不改准入。
 */
final class PlannedStopSwitcherClearance {

  private static final String SWITCHER_CONFLICT_PREFIX = "switcher:";

  private PlannedStopSwitcherClearance() {}

  /**
   * 去掉计划停车点之后足够远的道岔冲突键，返回供前瞻测距的判定。
   *
   * @param decision 前瞻的占用判定
   * @param context 前瞻路径（第一个节点是当前节点）
   * @param nextStop 下一路径点的 RouteStop；只有非通过（列车会在那里停车）才放宽
   * @param nextNode 下一路径点解析后的图节点
   * @param clearanceBlocks 道岔离停车点至少要这么远才去掉；车长未知时传 {@link Long#MAX_VALUE}
   * @return 没有可去掉的冲突键时原样返回
   */
  static OccupancyDecision forLookahead(
      OccupancyDecision decision,
      OccupancyRequestContext context,
      Optional<RouteStop> nextStop,
      NodeId nextNode,
      long clearanceBlocks) {
    boolean plannedStop =
        nextStop != null
            && nextStop.isPresent()
            && nextStop.get().passType() != RouteStopPassType.PASS;
    if (decision == null
        || decision.blockers().isEmpty()
        || context == null
        || !plannedStop
        || nextNode == null
        || clearanceBlocks <= 0L
        || clearanceBlocks == Long.MAX_VALUE) {
      return decision;
    }
    List<NodeId> nodes = context.pathNodes();
    int stopIndex = nodes.indexOf(nextNode);
    if (stopIndex <= 0) {
      return decision;
    }
    List<Long> distances = nodeDistances(nodes, context.edges());
    if (stopIndex >= distances.size()) {
      return decision;
    }
    List<OccupancyClaim> kept = new ArrayList<>();
    for (OccupancyClaim claim : decision.blockers()) {
      if (!switcherClearOfStop(claim, nodes, distances, stopIndex, clearanceBlocks)) {
        kept.add(claim);
      }
    }
    if (kept.size() == decision.blockers().size()) {
      return decision;
    }
    return new OccupancyDecision(
        decision.allowed(),
        decision.earliestTime(),
        decision.signal(),
        List.copyOf(kept),
        decision.conflictRelease(),
        decision.reason());
  }

  /** 道岔冲突键，且道岔首次出现在停车点之后、离停车点不小于净空。 */
  private static boolean switcherClearOfStop(
      OccupancyClaim claim,
      List<NodeId> nodes,
      List<Long> distances,
      int stopIndex,
      long clearanceBlocks) {
    if (claim == null || claim.resource() == null) {
      return false;
    }
    OccupancyResource resource = claim.resource();
    if (resource.kind() != ResourceKind.CONFLICT
        || !resource.key().startsWith(SWITCHER_CONFLICT_PREFIX)) {
      return false;
    }
    NodeId switcher = NodeId.of(resource.key().substring(SWITCHER_CONFLICT_PREFIX.length()));
    int switcherIndex = nodes.indexOf(switcher);
    if (switcherIndex <= stopIndex || switcherIndex >= distances.size()) {
      return false;
    }
    return distances.get(switcherIndex) - distances.get(stopIndex) >= clearanceBlocks;
  }

  /** 路径上每个节点到第一个节点的累计边长；边缺失时到此为止。 */
  private static List<Long> nodeDistances(List<NodeId> nodes, List<RailEdge> edges) {
    List<Long> distances = new ArrayList<>(nodes.size());
    distances.add(0L);
    long distance = 0L;
    for (int index = 0; index < edges.size() && index + 1 < nodes.size(); index++) {
      RailEdge edge = edges.get(index);
      if (edge == null) {
        break;
      }
      distance += Math.max(0L, edge.lengthBlocks());
      distances.add(distance);
    }
    return distances;
  }
}
