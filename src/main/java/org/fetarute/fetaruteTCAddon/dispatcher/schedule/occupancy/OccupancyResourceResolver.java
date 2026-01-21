package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphConflictSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;

/**
 * 负责将轨道图元素映射为占用资源集合。
 *
 * <p>最小可用版本规则：区间边（edge）必占用自身资源；若端点为 switcher，则额外占用冲突资源。
 */
public final class OccupancyResourceResolver {

  private static final String SWITCHER_CONFLICT_PREFIX = "switcher:";

  private OccupancyResourceResolver() {}

  public static List<OccupancyResource> resourcesForEdge(RailGraph graph, RailEdge edge) {
    if (edge == null) {
      return List.of();
    }
    List<OccupancyResource> resources = new ArrayList<>();
    resources.add(OccupancyResource.forEdge(edge.id()));
    if (graph != null) {
      graph.findNode(edge.from()).ifPresent(node -> addSwitcherConflict(node, resources));
      graph.findNode(edge.to()).ifPresent(node -> addSwitcherConflict(node, resources));
      if (graph instanceof RailGraphConflictSupport conflictSupport) {
        conflictSupport
            .conflictKeyForEdge(edge.id())
            .ifPresent(key -> resources.add(OccupancyResource.forConflict(key)));
      }
    }
    return List.copyOf(resources);
  }

  public static List<OccupancyResource> resourcesForNode(RailNode node) {
    if (node == null) {
      return List.of();
    }
    List<OccupancyResource> resources = new ArrayList<>();
    resources.add(OccupancyResource.forNode(node.id()));
    addSwitcherConflict(node, resources);
    return List.copyOf(resources);
  }

  private static void addSwitcherConflict(RailNode node, List<OccupancyResource> resources) {
    if (node == null || resources == null) {
      return;
    }
    if (node.type() != NodeType.SWITCHER) {
      return;
    }
    String conflictId = SWITCHER_CONFLICT_PREFIX + node.id().value();
    resources.add(OccupancyResource.forConflict(conflictId));
  }

  static String switcherConflictId(RailNode node) {
    Objects.requireNonNull(node, "node");
    return SWITCHER_CONFLICT_PREFIX + node.id().value();
  }
}
