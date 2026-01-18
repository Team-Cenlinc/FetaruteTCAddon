package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * 基于占用阻塞信息估算前方阻塞距离。
 *
 * <p>只解析 EDGE/NODE 类型的阻塞资源；CONFLICT 资源不参与距离计算。
 */
public final class OccupancyLookaheadResolver {

  private OccupancyLookaheadResolver() {}

  /**
   * 估算从路径起点到“首个阻塞资源”的距离（blocks）。
   *
   * @return 未匹配到阻塞资源时返回 empty
   */
  public static OptionalLong resolveBlockerDistance(
      OccupancyDecision decision, OccupancyRequestContext context) {
    if (decision == null || context == null || decision.blockers().isEmpty()) {
      return OptionalLong.empty();
    }
    List<NodeId> nodes = context.pathNodes();
    List<RailEdge> edges = context.edges();
    if (nodes.isEmpty() || edges.isEmpty()) {
      return OptionalLong.empty();
    }
    Map<String, Long> edgeDistance = new HashMap<>();
    Map<String, Long> nodeDistance = new HashMap<>();
    long distance = 0;
    nodeDistance.put(nodes.get(0).value(), 0L);
    int maxIndex = Math.min(edges.size(), nodes.size() - 1);
    for (int i = 0; i < maxIndex; i++) {
      RailEdge edge = edges.get(i);
      if (edge == null) {
        continue;
      }
      String edgeKey = OccupancyResource.forEdge(edge.id()).key();
      edgeDistance.put(edgeKey, distance);
      distance += Math.max(0, edge.lengthBlocks());
      NodeId to = nodes.get(i + 1);
      if (to != null) {
        nodeDistance.put(to.value(), distance);
      }
    }
    long best = Long.MAX_VALUE;
    for (OccupancyClaim claim : decision.blockers()) {
      if (claim == null || claim.resource() == null) {
        continue;
      }
      OccupancyResource resource = claim.resource();
      Long resourceDistance = null;
      if (resource.kind() == ResourceKind.EDGE) {
        resourceDistance = edgeDistance.get(resource.key());
      } else if (resource.kind() == ResourceKind.NODE) {
        resourceDistance = nodeDistance.get(resource.key());
      }
      if (resourceDistance != null && resourceDistance < best) {
        best = resourceDistance;
      }
    }
    return best == Long.MAX_VALUE ? OptionalLong.empty() : OptionalLong.of(best);
  }
}
