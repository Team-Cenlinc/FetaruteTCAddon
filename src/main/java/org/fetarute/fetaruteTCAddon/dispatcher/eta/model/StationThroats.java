package org.fetarute.fetaruteTCAddon.dispatcher.eta.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;

/**
 * 站台节点对应的咽喉节点，按调度图实例缓存。
 *
 * <p>找咽喉要扫一遍全图节点，而站牌重算时每列开往本站的车都要问一次。调度图快照不可变（重建即换新实例，跨世界路网的视图也随路网重建换新）， 所以同一张图上同一站台的结果不变：按（图实例,
 * 站台节点）记住。图按弱引用持有，换图后旧图连同它的结果随之回收。
 *
 * <p>线程安全：主线程（单车 ETA）与后台线程（站牌刷新）共用一份。
 */
public final class StationThroats {

  private final Map<RailGraph, Map<NodeId, List<NodeId>>> byGraph =
      Collections.synchronizedMap(new WeakHashMap<>());

  /**
   * 目标站台的咽喉节点。
   *
   * <p>仅当目标是 STATION/DEPOT 类型时才会查找；若目标本身就是咽喉则返回空列表。
   *
   * <p>站咽喉格式：{@code Operator:S:Station:Track:Seq}（5 段）。 站点格式：{@code Operator:S:Station:Track}（4 段）。
   * 匹配规则：咽喉的 operator/station/track 与目标站点相同。
   *
   * @param graph 调度图
   * @param target 目标节点（通常是站点）
   * @return 该站点的咽喉节点列表（按图节点顺序）；若无咽喉则返回空列表
   */
  public List<NodeId> of(RailGraph graph, NodeId target) {
    if (graph == null || target == null) {
      return List.of();
    }
    Map<NodeId, List<NodeId>> known =
        byGraph.computeIfAbsent(graph, g -> new ConcurrentHashMap<>());
    List<NodeId> cached = known.get(target);
    if (cached != null) {
      return cached;
    }
    List<NodeId> found = scan(graph, target);
    known.putIfAbsent(target, found);
    return found;
  }

  /** 当前记着结果的图实例数（测试用）。 */
  int graphCount() {
    synchronized (byGraph) {
      return byGraph.size();
    }
  }

  private static List<NodeId> scan(RailGraph graph, NodeId target) {
    Optional<RailNode> nodeOpt = graph.findNode(target);
    if (nodeOpt.isEmpty()) {
      return List.of();
    }
    Optional<WaypointMetadata> metaOpt = nodeOpt.get().waypointMetadata();
    if (metaOpt.isEmpty()) {
      return List.of();
    }
    WaypointMetadata meta = metaOpt.get();
    // 只为 STATION/DEPOT 查找咽喉
    if (meta.kind() != WaypointKind.STATION && meta.kind() != WaypointKind.DEPOT) {
      return List.of();
    }
    WaypointKind throatKind =
        meta.kind() == WaypointKind.STATION
            ? WaypointKind.STATION_THROAT
            : WaypointKind.DEPOT_THROAT;
    String operator = meta.operator();
    String station = meta.originStation();
    int track = meta.trackNumber();

    List<NodeId> throats = new ArrayList<>();
    for (RailNode n : graph.nodes()) {
      Optional<WaypointMetadata> nMetaOpt = n.waypointMetadata();
      if (nMetaOpt.isEmpty()) {
        continue;
      }
      WaypointMetadata nMeta = nMetaOpt.get();
      if (nMeta.kind() == throatKind
          && nMeta.operator().equalsIgnoreCase(operator)
          && nMeta.originStation().equalsIgnoreCase(station)
          && nMeta.trackNumber() == track) {
        throats.add(n.id());
      }
    }
    return List.copyOf(throats);
  }
}
