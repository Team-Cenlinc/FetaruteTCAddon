package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;

/**
 * 运行时线路索引解析器：优先相信 tag 中的 index，再在“附近”做容错匹配。
 *
 * <p>用于支持存在重复节点/回环的线路定义，避免简单的 {@code indexOf} 把列车进度误回退到最早出现处。
 */
public final class RouteIndexResolver {

  private RouteIndexResolver() {}

  /**
   * 解析“当前已抵达节点”的索引。
   *
   * <p>匹配策略：
   *
   * <ul>
   *   <li>tag index 存在且节点一致：直接使用
   *   <li>tag index 存在但节点不一致：向前搜索下一次出现（用于回环/重复节点）
   *   <li>以上都失败：回退为全量扫描的首次出现
   * </ul>
   *
   * @return 找不到时返回 -1
   */
  public static int resolveCurrentIndex(
      RouteDefinition route, OptionalInt tagIndex, NodeId currentNode) {
    Objects.requireNonNull(route, "route");
    if (currentNode == null) {
      return -1;
    }
    List<NodeId> nodes = route.waypoints();
    if (nodes == null || nodes.isEmpty()) {
      return -1;
    }

    if (tagIndex != null && tagIndex.isPresent()) {
      int index = tagIndex.getAsInt();
      if (index >= 0 && index < nodes.size()) {
        if (currentNode.equals(nodes.get(index))) {
          return index;
        }
        for (int i = index + 1; i < nodes.size(); i++) {
          if (currentNode.equals(nodes.get(i))) {
            return i;
          }
        }
      }
    }

    for (int i = 0; i < nodes.size(); i++) {
      if (currentNode.equals(nodes.get(i))) {
        return i;
      }
    }
    return -1;
  }
}
