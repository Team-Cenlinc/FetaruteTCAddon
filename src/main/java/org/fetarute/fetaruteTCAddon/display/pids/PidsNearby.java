package org.fetarute.fetaruteTCAddon.display.pids;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.api.graph.GraphApi;
import org.fetarute.fetaruteTCAddon.display.pids.screen.PidsScreen;

/**
 * 按调度图里的站台节点找屏幕附近的车站与站台。
 *
 * <p>节点坐标是站台牌子的位置，屏幕一般挂在站台边，取最近的站台节点即可；图里没有的站台（未建图）找不到，由配置棍手动指定。
 *
 * @param platforms 范围内的站台节点，由近到远
 */
public record PidsNearby(List<Platform> platforms) {

  /** 识别半径（方块）。 */
  public static final double RADIUS = 48.0;

  /**
   * @param node 站台节点
   * @param distance 到屏幕中心的距离
   */
  public record Platform(PidsPlatformNode node, double distance) {}

  public PidsNearby {
    platforms = List.copyOf(platforms);
  }

  /**
   * @param nodes 世界调度图的节点
   * @param center 屏幕中心
   * @param radius 识别半径
   */
  public static PidsNearby of(
      Collection<GraphApi.ApiNode> nodes, PidsScreen.Position center, double radius) {
    Objects.requireNonNull(center, "center");
    double limit = radius * radius;
    return new PidsNearby(
        nodes.stream()
            .flatMap(
                node ->
                    PidsPlatformNode.parse(node.id())
                        .map(platform -> new Platform(platform, distance2(node, center)))
                        .stream())
            .filter(candidate -> candidate.distance() <= limit)
            .map(candidate -> new Platform(candidate.node(), Math.sqrt(candidate.distance())))
            .sorted(
                Comparator.comparingDouble(Platform::distance)
                    .thenComparing(p -> p.node().station().toString())
                    .thenComparing(p -> p.node().platform(), PidsPlatformNode.PLATFORM_ORDER))
            .toList());
  }

  /** 最近的站台。 */
  public Optional<PidsPlatformNode> nearest() {
    return platforms.stream().findFirst().map(Platform::node);
  }

  /** 范围内的车站，由近到远、去重。 */
  public List<PidsStationKey> stations() {
    LinkedHashSet<PidsStationKey> stations = new LinkedHashSet<>();
    platforms.forEach(platform -> stations.add(platform.node().station()));
    return List.copyOf(stations);
  }

  /**
   * 图里某个车站的全部站台，按站台号排序。
   *
   * @param nodes 世界调度图的节点
   */
  public static List<String> platformsOf(
      Collection<GraphApi.ApiNode> nodes, PidsStationKey station) {
    return nodes.stream()
        .flatMap(node -> PidsPlatformNode.parse(node.id()).stream())
        .filter(node -> node.station().equals(station))
        .map(PidsPlatformNode::platform)
        .distinct()
        .sorted(PidsPlatformNode.PLATFORM_ORDER)
        .toList();
  }

  private static double distance2(GraphApi.ApiNode node, PidsScreen.Position center) {
    double dx = node.position().x() - (center.x() + 0.5);
    double dy = node.position().y() - (center.y() + 0.5);
    double dz = node.position().z() - (center.z() + 0.5);
    return dx * dx + dy * dy + dz * dz;
  }
}
