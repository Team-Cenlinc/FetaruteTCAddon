package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ClaimRole;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.DirectedTraversalContext;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.MovementPlanSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResourceResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.TrainNameNormalizer;

/**
 * 已授予、仍在车前方的移动授权：释放"不在本拍请求里的资源"时不得把它拆开。
 *
 * <p>信号周期与推进点每次都按本拍请求释放多余的占用。本拍请求比上一拍授予的窗口短时——硬授权构建失败、退回没配泊位距离的前瞻请求，或推进点拿前瞻请求当保留集——
 * 已授予的进路就被截掉后半段：车还留着前半段，别的车先排上后半段的队，本车再去要时排在它后面，两边互等。2026-09-27 实服 OFL 车库口：回库 MT 18:43:32
 * 原子拿下渡线到车库的整条进路，8 秒后硬授权在 MLU:2:002 构建失败、退回前瞻请求，车库岔口之后全部释放；对向 DS 随即排上岔口队首，MT 占着渡线、DS 要过渡线，互等 18 分钟。
 *
 * <p>要保留的是三者的交集：当前有效令牌授予的资源、本车仍以 {@code MOVEMENT_REQUIRED} 持有、沿本拍行车计划从当前节点走到令牌授权终点为止的那一段。
 * 以令牌终点为界，环线计划绕回车后也不会把身后的占用留住；令牌终点不在本拍计划上（改道、换站台）时不保留，照旧释放。结果只会让本车多留、不会让任何车多拿。
 */
final class HeldForwardAuthority {

  private HeldForwardAuthority() {}

  /**
   * 计算本拍释放时必须保留的已授予前方资源。
   *
   * @param token 本车当前有效的移动授权；空、未激活、没有授权终点或受限时传 null
   * @param plan 本拍请求携带的行车计划，从当前节点起
   * @param selfClaims 本车当前持有的占用（按令牌车名再过滤一遍）
   * @param graph 调度图
   * @return 需保留的资源；任一证据缺失时为空集
   */
  static Set<OccupancyResource> resolve(
      MovementAuthorizationToken token,
      Optional<MovementPlanSnapshot> plan,
      Collection<OccupancyClaim> selfClaims,
      RailGraph graph) {
    if (token == null
        || !token.active()
        || token.authorityEndNode().isEmpty()
        || token.resources().isEmpty()
        || plan == null
        || plan.isEmpty()
        || selfClaims == null
        || graph == null) {
      return Set.of();
    }
    Set<OccupancyResource> ahead =
        planUpTo(plan.get(), token.authorityEndNode().get(), graph).orElse(Set.of());
    if (ahead.isEmpty()) {
      return Set.of();
    }
    Set<OccupancyResource> granted = Set.copyOf(token.resources());
    Set<OccupancyResource> retained = new LinkedHashSet<>();
    for (OccupancyClaim claim : selfClaims) {
      if (claim != null
          && claim.role() == ClaimRole.MOVEMENT_REQUIRED
          && TrainNameNormalizer.sameLogicalTrain(claim.trainName(), token.trainName())
          && granted.contains(claim.resource())
          && ahead.contains(claim.resource())) {
        retained.add(claim.resource());
      }
    }
    return Set.copyOf(retained);
  }

  /**
   * 保留集里本拍保留集之外、原本要被释放的那部分，用于诊断。
   *
   * @param retained {@link #resolve} 的结果
   * @param keep 本拍请求要保留的资源
   * @return 因本规则而没被释放的资源，保持稳定顺序
   */
  static List<OccupancyResource> savedFromRelease(
      Set<OccupancyResource> retained, Collection<OccupancyResource> keep) {
    if (retained == null || retained.isEmpty()) {
      return List.of();
    }
    Set<OccupancyResource> kept = keep == null ? Set.of() : Set.copyOf(keep);
    List<OccupancyResource> saved = new ArrayList<>();
    for (OccupancyResource resource : retained) {
      if (!kept.contains(resource)) {
        saved.add(resource);
      }
    }
    saved.sort((left, right) -> left.toString().compareTo(right.toString()));
    return List.copyOf(saved);
  }

  /**
   * 诊断行按车去重：同一辆车只在"来源、硬授权是否构建成功、被保下的资源"变化时输出一行。
   *
   * <p>这行是必留诊断，不受观察预算与重复窗口约束，所以由生产端去重；硬授权持续退回时它每拍都会命中。按列车名记最近一行，
   * 记满时整表清空重来（改名后的旧名不会再来，清空最多让每辆车多输出一行）。
   */
  static final class TraceDedup {
    private static final int MAX_TRAINS = 256;

    private final Map<String, String> lastByTrain = new HashMap<>();

    /**
     * 生成诊断行；与这辆车上一行相同时返回空。
     *
     * @param trainName 列车名
     * @param source 调用来源（SIGNAL_TICK / PROGRESS_TRIGGER）
     * @param hardAuthorityBuilt 本拍硬授权是否构建成功；失败即退回了前瞻请求
     * @param saved {@link #savedFromRelease} 的结果；为空时不输出并清掉记录
     * @return 需要输出的诊断行
     */
    synchronized Optional<String> line(
        String trainName,
        String source,
        boolean hardAuthorityBuilt,
        List<OccupancyResource> saved) {
      if (trainName == null || trainName.isBlank()) {
        return Optional.empty();
      }
      if (saved == null || saved.isEmpty()) {
        lastByTrain.remove(trainName);
        return Optional.empty();
      }
      String line =
          "SMART_FORWARD_AUTHORITY_RETAINED train="
              + trainName
              + " source="
              + source
              + " hardAuthority="
              + (hardAuthorityBuilt ? "built" : "fallback")
              + " retained="
              + saved.size()
              + " resources="
              + saved;
      if (lastByTrain.size() >= MAX_TRAINS && !lastByTrain.containsKey(trainName)) {
        lastByTrain.clear();
      }
      if (line.equals(lastByTrain.put(trainName, line))) {
        return Optional.empty();
      }
      return Optional.of(line);
    }
  }

  /** 行车计划从当前节点到 {@code end}（首次出现）为止的节点、区间及其冲突资源；计划上没有 {@code end} 时为空。 */
  private static Optional<Set<OccupancyResource>> planUpTo(
      MovementPlanSnapshot plan, NodeId end, RailGraph graph) {
    List<NodeId> nodes = plan.expandedPathNodes();
    int endIndex = nodes.indexOf(end);
    if (endIndex < 0) {
      return Optional.empty();
    }
    Set<OccupancyResource> resources = new LinkedHashSet<>();
    for (int index = 0; index <= endIndex; index++) {
      NodeId nodeId = nodes.get(index);
      resources.add(OccupancyResource.forNode(nodeId));
      graph
          .findNode(nodeId)
          .ifPresent(node -> resources.addAll(OccupancyResourceResolver.resourcesForNode(node)));
    }
    List<DirectedTraversalContext.DirectedEdge> edges = plan.directedEdges();
    for (int index = 0; index < Math.min(endIndex, edges.size()); index++) {
      DirectedTraversalContext.DirectedEdge edge = edges.get(index);
      resources.add(OccupancyResource.forEdge(edge.edgeId()));
      graph
          .findEdge(edge.edgeId())
          .ifPresent(
              railEdge ->
                  resources.addAll(OccupancyResourceResolver.resourcesForEdge(graph, railEdge)));
    }
    return Optional.of(resources);
  }
}
