package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphConflictSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphInterlockingSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphSectionSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SingleLineSectionInfo;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;

/**
 * 负责将轨道图元素映射为占用资源集合。
 *
 * <p>每条选定 edge 必须占用自身资源；端点为 switcher 时额外占用该道岔冲突资源。只有经桥判定证明不存在替代路径的 single-line section
 * 才会叠加整段方向资源。处于同一非桥网格本身不构成冲突，列车是否互斥取决于各自有序 Movement Plan 实际共享的 edge、node 或 switcher。
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
      Optional<SingleLineSectionInfo> section =
          graph instanceof RailGraphSectionSupport sectionSupport
              ? sectionSupport.sectionInfoForEdge(edge.id())
              : Optional.empty();
      boolean bridgeSection = section.isPresent();
      section.ifPresent(info -> resources.add(OccupancyResource.forConflict(info.key())));
      if (graph instanceof RailGraphConflictSupport conflictSupport) {
        conflictSupport
            .conflictKeyForEdge(edge.id())
            .filter(
                key ->
                    !(graph instanceof RailGraphSectionSupport)
                        || bridgeSection
                        || isBoundarylessCycleConflict(key))
            .ifPresent(key -> resources.add(OccupancyResource.forConflict(key)));
      }
      if (graph instanceof RailGraphInterlockingSupport interlockingSupport) {
        interlockingSupport
            .zoneKeysForEdge(edge.id())
            .forEach(key -> resources.add(OccupancyResource.forConflict(key)));
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

  /** 判断资源是否为物理足迹推导的严格联锁区。 */
  public static boolean isInterlockingConflict(OccupancyResource resource) {
    return resource != null
        && resource.kind() == ResourceKind.CONFLICT
        && resource.key().startsWith("interlocking:");
  }

  private static boolean isBoundarylessCycleConflict(String key) {
    return key != null && key.startsWith("single:") && key.contains(":cycle:");
  }
}
