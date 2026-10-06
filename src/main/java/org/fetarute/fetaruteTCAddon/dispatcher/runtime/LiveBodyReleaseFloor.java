package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphInterlockingSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingState;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.AuthorizationPurpose;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceIntent;
import org.fetarute.fetaruteTCAddon.utils.StableCollections;

/**
 * 车体实测压着的图区间，以及据此得到的释放下限。
 *
 * <p>信号 tick、推进点、发车门控与停车保持都先按本拍请求收缩本车 claim：车头身后的区段先被放掉，再由后续步骤以列尾防护取回。这一放一取是有意的——已持有的
 * MOVEMENT_REQUIRED 只有放掉再以保护性意图取回才会降为
 * PROTECTIVE_RETAIN（同车刷新保留硬授权角色）。但“取回”依赖逻辑窗口（交路进度、估算车长）和每条分支都记得去取； 任何一处缺了，车身压着的 NODE/EDGE 就在下一次完整
 * tick 之前不归任何车，后车可以对它们取得硬授权。
 *
 * <p>这里给出一条不依赖那套逻辑的下限：本拍放掉的资源里，凡是车体现场方块仍压着的，紧接着（同一次同步调用里，别的车没有机会插进来）以 PROTECTIVE_RETAIN 取回。
 * 只取回本拍刚放掉的，从不新拿本车原本没有持有的资源。覆盖证据不完整时下限为空，调用方的行为与没有下限时相同。
 */
final class LiveBodyReleaseFloor {

  private LiveBodyReleaseFloor() {}

  /**
   * 由现场轨道方块反查车体压着的区间。
   *
   * <p><b>缺任何一环都返回不完整</b>：图不带联锁能力、逐边足迹索引不可用或不完整（只有部分区间带足迹时，没足迹的区间在索引里看不见，车压在上面也查不出来）、
   * 或一条区间都定位不到。“没覆盖”与“无从判断”必须分得开。
   *
   * @param graph 与现场世界对应的图快照
   * @param cells 各车厢当前的轨道方块
   * @return 覆盖结果；只做查询，不改变任何状态
   */
  static Coverage coverage(RailGraph graph, Collection<RailFootprintCell> cells) {
    if (!(graph instanceof RailGraphInterlockingSupport support)) {
      return Coverage.incomplete("interlocking-unsupported-graph");
    }
    RailInterlockingState interlocking = support.interlockingState();
    if (interlocking == null || !interlocking.cellCoverageAvailable()) {
      return Coverage.incomplete("cell-coverage-index-unavailable");
    }
    if (!interlocking.coverage().complete()) {
      return Coverage.incomplete("cell-coverage-partial");
    }
    Set<EdgeId> edges = new TreeSet<>();
    if (cells != null) {
      for (RailFootprintCell cell : cells) {
        if (cell == null) {
          return Coverage.incomplete("live-footprint-null-cell");
        }
        edges.addAll(interlocking.edgesForCell(cell));
      }
    }
    if (edges.isEmpty()) {
      // 一条区间都定位不到 ⇒ 车在哪儿无从判断 ⇒ 不能把“看不见”当成“已离开”。
      return Coverage.incomplete("no-edge-located-for-live-cells");
    }
    return new Coverage(true, "-", edges);
  }

  /**
   * 本拍放掉的资源里车体仍压着的，以 PROTECTIVE_RETAIN 立即取回。
   *
   * @param released 本拍释放掉的资源
   * @param floor {@link Coverage#releaseFloor()}；为空时不取回任何资源
   * @return 取回之后仍然放掉的资源
   */
  static List<OccupancyResource> reacquireBody(
      OccupancyManager occupancyManager,
      String trainName,
      Optional<RouteId> routeId,
      Instant now,
      List<OccupancyResource> released,
      Set<OccupancyResource> floor) {
    if (occupancyManager == null
        || trainName == null
        || trainName.isBlank()
        || released == null
        || released.isEmpty()
        || floor == null
        || floor.isEmpty()) {
      return released == null ? List.of() : released;
    }
    List<OccupancyResource> body = new ArrayList<>();
    List<OccupancyResource> stillReleased = new ArrayList<>();
    for (OccupancyResource resource : released) {
      if (floor.contains(resource)) {
        body.add(resource);
      } else {
        stillReleased.add(resource);
      }
    }
    if (body.isEmpty()) {
      return released;
    }
    List<OccupancyResource> ordered =
        List.copyOf(StableCollections.copySorted(body, OccupancyResource.STABLE_ORDER));
    Map<OccupancyResource, ResourceIntent> intents = new LinkedHashMap<>();
    for (OccupancyResource resource : ordered) {
      intents.put(resource, ResourceIntent.PROTECTIVE_RETAIN);
    }
    occupancyManager.acquire(
        new OccupancyRequest(
            trainName,
            routeId == null ? Optional.empty() : routeId,
            now == null ? Instant.now() : now,
            ordered,
            Map.of(),
            Map.of(),
            0,
            AuthorizationPurpose.RUNTIME_MOVE,
            Map.of(),
            intents));
    return List.copyOf(stillReleased);
  }

  /**
   * 车体压着的区间。
   *
   * @param complete 证据是否完整；为假表示无从判断，不是“没覆盖”
   * @param incompleteReason 不完整的原因；完整时为 {@code -}
   * @param edges 车体现场方块所在的区间
   */
  record Coverage(boolean complete, String incompleteReason, Set<EdgeId> edges) {

    Coverage {
      incompleteReason =
          incompleteReason == null || incompleteReason.isBlank() ? "-" : incompleteReason;
      edges = edges == null ? Set.of() : StableCollections.copySorted(edges, EdgeId::compareTo);
    }

    static Coverage incomplete(String reason) {
      return new Coverage(false, reason, Set.of());
    }

    /**
     * 区间加上每条区间的两个端点，用于证明“车体已经离开某资源”。
     *
     * <p>端点一并视为“车体可能压着”是 NODE 侧唯一站得住的推导方向：它<b>放大</b>覆盖集合，只会让释放判据更严、更少放行。反向推导（“不是任何已覆盖区间的端点就算已离开”）
     * 依赖光栅化无缝隙这一未验证前提，推错就是在车实际压着的节点上解除保护。不完整时为空，调用方必须按“无从判断”处理。
     */
    Set<OccupancyResource> edgesWithEndpoints() {
      if (!complete) {
        return Set.of();
      }
      Set<OccupancyResource> resources = new LinkedHashSet<>();
      for (EdgeId edge : edges) {
        resources.add(OccupancyResource.forEdge(edge));
        resources.add(OccupancyResource.forNode(edge.a()));
        resources.add(OccupancyResource.forNode(edge.b()));
      }
      return StableCollections.copySorted(resources, OccupancyResource.STABLE_ORDER);
    }

    /**
     * 释放下限：车体压着的区间，以及车体跨过的节点。
     *
     * <p>节点只取至少两条已覆盖区间共用的端点——车体从一条区间进入另一条时必然压过它。车头前方、车尾后方那条区间的另一端不算：
     * 下限用于“不许放”，往前多算一个节点，停在道岔前的车就会把道岔节点一直攥在手里。不完整时为空。
     */
    Set<OccupancyResource> releaseFloor() {
      if (!complete) {
        return Set.of();
      }
      Map<NodeId, Integer> incidence = new HashMap<>();
      Set<OccupancyResource> resources = new LinkedHashSet<>();
      for (EdgeId edge : edges) {
        resources.add(OccupancyResource.forEdge(edge));
        incidence.merge(edge.a(), 1, Integer::sum);
        incidence.merge(edge.b(), 1, Integer::sum);
      }
      for (Map.Entry<NodeId, Integer> entry : incidence.entrySet()) {
        if (entry.getValue() >= 2) {
          resources.add(OccupancyResource.forNode(entry.getKey()));
        }
      }
      return StableCollections.copySorted(resources, OccupancyResource.STABLE_ORDER);
    }
  }
}
