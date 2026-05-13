package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * 占用请求构建产物：包含请求本体与对应的路径信息。
 *
 * <p>pathNodes/edges 用于诊断与运行时 lookahead 计算，不参与持久化。
 */
public record OccupancyRequestContext(
    OccupancyRequest request,
    List<NodeId> pathNodes,
    List<RailEdge> edges,
    Optional<DirectedTraversalContext> directedContext) {

  public OccupancyRequestContext(
      OccupancyRequest request, List<NodeId> pathNodes, List<RailEdge> edges) {
    this(requireRequest(request), pathNodes, edges, requireRequest(request).directedContext());
  }

  public OccupancyRequestContext {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(pathNodes, "pathNodes");
    Objects.requireNonNull(edges, "edges");
    directedContext = directedContext == null ? Optional.empty() : directedContext;
    pathNodes = List.copyOf(pathNodes);
    edges = List.copyOf(edges);
  }

  private static OccupancyRequest requireRequest(OccupancyRequest request) {
    return Objects.requireNonNull(request, "request");
  }
}
