package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;

/**
 * 运行时线路索引解析器：优先相信 tag 中的 index，再在"附近"做容错匹配。
 *
 * <p>用于支持存在重复节点/回环的线路定义，避免简单的 {@code indexOf} 把列车进度误回退到最早出现处。
 *
 * <p>支持 DYNAMIC stop 匹配：当 stop 包含 DYNAMIC 指令时，会检查实际 nodeId 是否在 DYNAMIC 范围内。
 */
public final class RouteIndexResolver {

  private RouteIndexResolver() {}

  /**
   * 解析"当前已抵达节点"的索引。
   *
   * <p>匹配策略：
   *
   * <ul>
   *   <li>tag index 存在且节点一致：直接使用
   *   <li>tag index 存在但节点不一致：向前搜索下一次出现（用于回环/重复节点）
   *   <li>以上都失败：回退为全量扫描的首次出现
   * </ul>
   *
   * <p>容错匹配规则：
   *
   * <ul>
   *   <li>同站不同站台（4 段 vs 4 段）：允许，用于动态站台/自动进路
   *   <li>咽喉 vs 站点本体（5 段 vs 4 段）：<b>不允许</b>，避免咽喉误触发站点推进
   *   <li>咽喉 vs 咽喉（5 段 vs 5 段）：允许，用于同站不同咽喉
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

    // 终端站容错：同站不同站台（例如动态站台/自动进路）时，允许用 Station/Depot 级别 key 匹配。
    // 但咽喉节点（5 段）不应容错匹配到站点本体（4 段），避免咽喉触发误推进站点 index。
    String currentValue =
        currentNode.value() != null ? currentNode.value().toLowerCase(Locale.ROOT) : null;
    Optional<String> currentStationKey = TerminalKeyResolver.extractStationKey(currentValue);
    boolean currentIsThroat = isThroatFormat(currentValue);

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
        if (currentStationKey.isPresent()) {
          for (int i = index; i < nodes.size(); i++) {
            NodeId candidate = nodes.get(i);
            if (candidate == null || candidate.value() == null) {
              continue;
            }
            String candidateValue = candidate.value().toLowerCase(Locale.ROOT);
            // 咽喉 vs 站点本体：不允许容错匹配
            if (currentIsThroat != isThroatFormat(candidateValue)) {
              continue;
            }
            Optional<String> candidateKey = TerminalKeyResolver.extractStationKey(candidateValue);
            if (candidateKey.isPresent() && candidateKey.get().equals(currentStationKey.get())) {
              return i;
            }
          }
        }
      }
    }

    for (int i = 0; i < nodes.size(); i++) {
      if (currentNode.equals(nodes.get(i))) {
        return i;
      }
    }
    if (currentStationKey.isPresent()) {
      for (int i = 0; i < nodes.size(); i++) {
        NodeId candidate = nodes.get(i);
        if (candidate == null || candidate.value() == null) {
          continue;
        }
        String candidateValue = candidate.value().toLowerCase(Locale.ROOT);
        // 咽喉 vs 站点本体：不允许容错匹配
        if (currentIsThroat != isThroatFormat(candidateValue)) {
          continue;
        }
        Optional<String> candidateKey = TerminalKeyResolver.extractStationKey(candidateValue);
        if (candidateKey.isPresent() && candidateKey.get().equals(currentStationKey.get())) {
          return i;
        }
      }
    }
    return -1;
  }

  /**
   * 解析"当前已抵达节点"的索引（支持 DYNAMIC stop 匹配）。
   *
   * <p>当 route 中的 stop 包含 DYNAMIC 指令时，会检查实际 nodeId 是否在 DYNAMIC 范围内。
   *
   * @param route 路线定义
   * @param stops 路线停靠表（用于 DYNAMIC 匹配）
   * @param tagIndex tag 中记录的当前索引
   * @param currentNode 当前实际节点
   * @return 找不到时返回 -1
   */
  public static int resolveCurrentIndexWithDynamic(
      RouteDefinition route, List<RouteStop> stops, OptionalInt tagIndex, NodeId currentNode) {
    // 先尝试普通匹配
    int basicResult = resolveCurrentIndex(route, tagIndex, currentNode);
    if (basicResult >= 0) {
      return basicResult;
    }
    // 尝试 DYNAMIC 匹配
    if (stops == null || stops.isEmpty() || currentNode == null) {
      return -1;
    }
    int startIdx = (tagIndex != null && tagIndex.isPresent()) ? tagIndex.getAsInt() : 0;
    if (startIdx < 0) {
      startIdx = 0;
    }
    // 从当前索引向前搜索 DYNAMIC stop
    for (int i = startIdx; i < stops.size(); i++) {
      RouteStop stop = stops.get(i);
      if (stop == null) {
        continue;
      }
      if (DynamicStopMatcher.matchesStop(currentNode, stop)) {
        return i;
      }
    }
    // 从头搜索
    for (int i = 0; i < Math.min(startIdx, stops.size()); i++) {
      RouteStop stop = stops.get(i);
      if (stop == null) {
        continue;
      }
      if (DynamicStopMatcher.matchesStop(currentNode, stop)) {
        return i;
      }
    }
    return -1;
  }

  /**
   * 解析"当前已抵达节点"的索引（支持 DYNAMIC stop 匹配，使用 RouteDefinitionCache）。
   *
   * @param route 路线定义
   * @param cache 路线定义缓存
   * @param tagIndex tag 中记录的当前索引
   * @param currentNode 当前实际节点
   * @return 找不到时返回 -1
   */
  public static int resolveCurrentIndexWithDynamic(
      RouteDefinition route, RouteDefinitionCache cache, OptionalInt tagIndex, NodeId currentNode) {
    if (route == null) {
      return -1;
    }
    if (cache == null) {
      return resolveCurrentIndex(route, tagIndex, currentNode);
    }
    List<RouteStop> stops = cache.listStops(route.id());
    return resolveCurrentIndexWithDynamic(route, stops, tagIndex, currentNode);
  }

  /**
   * 判断 NodeId 值是否为咽喉格式（5 段，且第二段是 S/D）。
   *
   * <p>格式：{@code Operator:S/D:Name:Track:Seq}
   */
  private static boolean isThroatFormat(String nodeIdValue) {
    if (nodeIdValue == null || nodeIdValue.isBlank()) {
      return false;
    }
    String[] parts = nodeIdValue.split(":");
    if (parts.length != 5) {
      return false;
    }
    String typeMarker = parts[1].toUpperCase(Locale.ROOT);
    return "S".equals(typeMarker) || "D".equals(typeMarker);
  }
}
