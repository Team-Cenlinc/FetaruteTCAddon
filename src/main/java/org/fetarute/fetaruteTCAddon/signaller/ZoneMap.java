package org.fetarute.fetaruteTCAddon.signaller;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceKind;
import org.fetarute.fetaruteTCAddon.display.pids.yard.YardArea;

/**
 * 一片控制区在某张调度图上的资源：区内节点、两端都在区内的区间、区内道岔的冲突资源，以及出入口与股道。按图快照缓存，图换了重算。
 *
 * <p>出入口区间（一端在区外）不算控制区资源：在入口外等着的车就停在这条区间上，算进来它就成了“已在区内”。单线区段与联锁资源可能跨出控制区，不整体并入。
 *
 * @param graph 推导用的图快照（按对象身份判断换没换）
 * @param area 站场
 * @param resources 控制区资源
 * @param exits 出入口区间 → 出入口
 * @param tracks 股道节点 → 股道（车站站台与车库库线）
 */
record ZoneMap(
    RailGraph graph,
    YardArea area,
    Set<OccupancyResource> resources,
    Map<EdgeId, YardArea.Exit> exits,
    Map<NodeId, YardArea.Track> tracks) {

  ZoneMap {
    Objects.requireNonNull(graph, "graph");
    Objects.requireNonNull(area, "area");
    resources = Set.copyOf(resources);
    exits = Map.copyOf(exits);
    tracks = Map.copyOf(tracks);
  }

  /** 由站场推出资源。 */
  static ZoneMap of(RailGraph graph, YardArea area) {
    Set<OccupancyResource> resources = new HashSet<>();
    for (NodeId node : area.nodes()) {
      resources.add(OccupancyResource.forNode(node));
      resources.add(OccupancyResource.forConflict("switcher:" + node.value()));
    }
    for (EdgeId edge : area.edges()) {
      resources.add(OccupancyResource.forEdge(edge));
    }
    Map<EdgeId, YardArea.Exit> exits = new HashMap<>();
    for (YardArea.Exit exit : area.exits()) {
      exits.put(EdgeId.undirected(exit.edge().a(), exit.edge().b()), exit);
    }
    Map<NodeId, YardArea.Track> tracks = new HashMap<>();
    for (YardArea.Track track : area.tracks()) {
      tracks.put(track.node(), track);
    }
    for (YardArea.Track track : area.depotTracks()) {
      tracks.putIfAbsent(track.node(), track);
    }
    return new ZoneMap(graph, area, resources, exits, tracks);
  }

  boolean contains(OccupancyResource resource) {
    return resource != null && resources.contains(resource);
  }

  boolean inside(NodeId node) {
    return node != null && area.nodes().contains(node);
  }

  /** 这批资源里有没有控制区的。 */
  boolean touches(Collection<OccupancyResource> requested) {
    if (requested == null) {
      return false;
    }
    for (OccupancyResource resource : requested) {
      if (resources.contains(resource)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 这次申请是从哪个入口进来的：申请里有出入口区间就是它；没有时取申请里第一个区内节点，只有一个出入口通向它时就是那个。
   *
   * @param requested 按路径顺序的申请资源
   */
  Optional<EdgeId> entryOf(Collection<OccupancyResource> requested) {
    if (requested == null) {
      return Optional.empty();
    }
    Map<OccupancyResource, EdgeId> exitResources = new HashMap<>();
    for (EdgeId edge : exits.keySet()) {
      exitResources.put(OccupancyResource.forEdge(edge), edge);
    }
    for (OccupancyResource resource : requested) {
      EdgeId edge = exitResources.get(resource);
      if (edge != null) {
        return Optional.of(edge);
      }
    }
    for (OccupancyResource resource : requested) {
      if (!resources.contains(resource) || resource.kind() != ResourceKind.NODE) {
        continue;
      }
      NodeId node = NodeId.of(resource.key());
      List<EdgeId> found = new ArrayList<>();
      for (YardArea.Exit exit : exits.values()) {
        if (exit.inner().equals(node)) {
          found.add(EdgeId.undirected(exit.edge().a(), exit.edge().b()));
        }
      }
      return found.size() == 1 ? Optional.of(found.get(0)) : Optional.empty();
    }
    return Optional.empty();
  }
}
