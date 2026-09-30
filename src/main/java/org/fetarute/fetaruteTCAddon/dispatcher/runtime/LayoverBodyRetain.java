package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResourceResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceIntent;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceKind;

/**
 * 终点折返待命车的停车保持：停稳且整列车身位置完整时，只保持车身实际压着的轨道。
 *
 * <p>停车保持沿用尾部保护——从站台节点往回按保守车长整边覆盖，再留 {@code rear-guard-edges} 条边。可列车以站牌为中心停车，
 * 车头越过站台节点约半个车长，从节点往回量会多盖约半个车长；双站台终点的两条进站路在身后不远就汇到同一个道岔上， 多盖的那一截正好压住它，
 * 另一个站台就进不去了：分到另一站台的后车只能一直等到待命车按表折返开走。
 *
 * <p>待命车只会原地等票或折返，不会再沿进站方向往前走，身后的轨道对它没有用。车身所在区间的两端节点仍在保持范围内
 * （覆盖集合特意把端点算进去），同一条线上开往同一站台的后车照样进不来；只有走另一条岔线、与车身隔着至少一整段区间的车能通过。
 *
 * <p>两种证据同时成立才收窄：列车停稳，整列车身覆盖完整。任一不成立原样返回，保持原有的尾部保护。结果永远是原请求的子集——只删不加。
 * 冲突资源只保留由保留下来的区间、节点推出的那部分；实时联锁区由调用方在收窄之后另行并入。
 */
final class LayoverBodyRetain {

  private LayoverBodyRetain() {}

  /**
   * 把待命车的停车保持请求收窄到车身实际覆盖的轨道。
   *
   * @param request 按尾部保护规则构建的停车保持请求
   * @param body 整列车身的实时区间覆盖；非待命车为空
   * @param stationary 读取车身位置时列车已停稳
   * @param graph 与请求同一份的调度图
   * @param currentNode 列车当前所在的图节点（站台节点），无论覆盖与否都保留
   * @return 收窄后的请求；证据不足或没有可删的资源时原样返回
   */
  static OccupancyRequest narrow(
      OccupancyRequest request,
      Optional<RuntimeDispatchService.LivePhysicalEdgeCoverage> body,
      boolean stationary,
      RailGraph graph,
      NodeId currentNode) {
    if (request == null
        || graph == null
        || currentNode == null
        || !stationary
        || body == null
        || body.isEmpty()
        || !body.get().complete()) {
      return request;
    }
    Set<OccupancyResource> covered = body.get().resources();
    OccupancyResource currentNodeResource = OccupancyResource.forNode(currentNode);
    Set<OccupancyResource> keptTrack = new LinkedHashSet<>();
    for (OccupancyResource resource : request.resourceList()) {
      if (resource.kind() != ResourceKind.CONFLICT
          && (covered.contains(resource) || resource.equals(currentNodeResource))) {
        keptTrack.add(resource);
      }
    }
    Set<OccupancyResource> keptConflicts = conflictsOf(request.resourceList(), keptTrack, graph);
    List<OccupancyResource> kept = new ArrayList<>();
    for (OccupancyResource resource : request.resourceList()) {
      if (resource.kind() == ResourceKind.CONFLICT
          ? keptConflicts.contains(resource)
          : keptTrack.contains(resource)) {
        kept.add(resource);
      }
    }
    if (kept.size() == request.resourceList().size()) {
      return request;
    }
    Set<String> keptConflictKeys = new LinkedHashSet<>();
    for (OccupancyResource resource : kept) {
      if (resource.kind() == ResourceKind.CONFLICT) {
        keptConflictKeys.add(resource.key());
      }
    }
    Map<OccupancyResource, ResourceIntent> intents = new LinkedHashMap<>(request.resourceIntents());
    intents.keySet().retainAll(kept);
    return new OccupancyRequest(
        request.trainName(),
        request.routeId(),
        request.now(),
        kept,
        retainKeys(request.corridorDirections(), keptConflictKeys),
        retainKeys(request.conflictEntryOrders(), keptConflictKeys),
        request.priority(),
        request.purpose(),
        retainKeys(request.conflictReleaseHints(), keptConflictKeys),
        intents,
        request.directedContext(),
        request.unresolvedDirectionKeys().stream()
            .filter(keptConflictKeys::contains)
            .collect(Collectors.toUnmodifiableSet()));
  }

  /**
   * 保留下来的节点与区间各自带出的冲突资源（道岔、单线区段、联锁区），与构建请求时同一套解析规则。
   *
   * <p>区间经原请求里任一节点的邻接边找回（资源 key 不反解析）：车身区间的端点都在尾部保护路径上。
   */
  private static Set<OccupancyResource> conflictsOf(
      List<OccupancyResource> requested, Set<OccupancyResource> keptTrack, RailGraph graph) {
    Set<OccupancyResource> conflicts = new LinkedHashSet<>();
    for (OccupancyResource resource : requested) {
      if (resource.kind() != ResourceKind.NODE) {
        continue;
      }
      NodeId nodeId = NodeId.of(resource.key());
      if (keptTrack.contains(resource)) {
        graph
            .findNode(nodeId)
            .ifPresent(node -> conflicts.addAll(OccupancyResourceResolver.resourcesForNode(node)));
      }
      for (RailEdge edge : graph.edgesFrom(nodeId)) {
        if (keptTrack.contains(OccupancyResource.forEdge(edge.id()))) {
          conflicts.addAll(OccupancyResourceResolver.resourcesForEdge(graph, edge));
        }
      }
    }
    return conflicts;
  }

  private static <V> Map<String, V> retainKeys(Map<String, V> source, Set<String> keys) {
    Map<String, V> kept = new LinkedHashMap<>(source);
    kept.keySet().retainAll(keys);
    return kept;
  }
}
