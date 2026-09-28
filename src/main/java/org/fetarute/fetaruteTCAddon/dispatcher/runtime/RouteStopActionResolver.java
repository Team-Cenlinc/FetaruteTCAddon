package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.util.List;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLineChanges;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteStopDirectives;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;

/**
 * RouteStop notes/action 解析器。
 *
 * <p>该组件只把 {@link RouteStop#notes()} 中的 DSL 解析为调度意图，例如 CHANGE、DYNAMIC、DSTY。它不修改 TrainProperties，不推进
 * routeIndex，不申请/释放 occupancy，也不下发 TrainCarts 控车动作；这些副作用必须由 RuntimeDispatchService 在明确边界内执行。
 */
final class RouteStopActionResolver {

  /** 解析 CHANGE action（规则见 {@link RouteLineChanges#directive}，展示侧与执行侧同一口径）。 */
  Optional<ChangeIntent> changeIntent(RouteStop stop) {
    return RouteLineChanges.directive(stop)
        .map(
            directive ->
                directive.valid()
                    ? ChangeIntent.valid(
                        directive.operatorCode(), directive.lineCode(), directive.raw())
                    : ChangeIntent.invalid(directive.raw(), directive.reason()));
  }

  /** 从 DSTY action 中提取内联 DYNAMIC 规范。 */
  Optional<String> dynamicFromDsty(RouteStop stop) {
    Optional<String> dstyTarget = RouteStopDirectives.target(stop, "DSTY");
    if (dstyTarget.isEmpty()) {
      return Optional.empty();
    }
    String target = dstyTarget.get();
    if (target.length() > 8 && target.regionMatches(true, 0, "DYNAMIC:", 0, 8)) {
      return Optional.of(target.substring(8));
    }
    return Optional.empty();
  }

  /** 返回 DYNAMIC action 或 DSTY 内联 DYNAMIC 的 payload。 */
  Optional<String> dynamicTarget(RouteStop stop) {
    Optional<String> direct = RouteStopDirectives.target(stop, "DYNAMIC");
    return direct.isPresent() ? direct : dynamicFromDsty(stop);
  }

  /** 当前节点是否匹配 DSTY 目标。 */
  boolean shouldDestroyAt(RouteStop stop, NodeId currentNode) {
    if (stop == null || currentNode == null) {
      return false;
    }
    Optional<String> targetOpt = RouteStopDirectives.target(stop, "DSTY");
    if (targetOpt.isEmpty()) {
      return false;
    }
    return matchesDstyTarget(targetOpt.get(), currentNode);
  }

  /**
   * DSTY 兜底判定：当 stop 未携带指令 notes 时，允许“最后一站 + depot + PASS”触发销毁。
   *
   * <p>这是历史数据兼容规则，只返回判定结果，不执行销毁。
   */
  boolean shouldDestroyAtFallback(
      RouteStop stop, int currentIndex, RouteDefinition route, SignNodeDefinition definition) {
    if (stop == null || route == null || definition == null) {
      return false;
    }
    if (definition.nodeType() != NodeType.DEPOT) {
      return false;
    }
    if (stop.notes().isPresent() && !stop.notes().get().isBlank()) {
      return false;
    }
    if (stop.passType() != RouteStopPassType.PASS) {
      return false;
    }
    int lastIndex = Math.max(0, route.waypoints().size() - 1);
    return currentIndex == lastIndex;
  }

  /** 线路内任一 DSTY action 是否匹配当前节点。 */
  boolean routeMatchesDstyTarget(List<RouteStop> stops, NodeId currentNode) {
    if (stops == null || stops.isEmpty() || currentNode == null) {
      return false;
    }
    for (RouteStop stop : stops) {
      Optional<String> targetOpt = RouteStopDirectives.target(stop, "DSTY");
      if (targetOpt.isPresent() && matchesDstyTarget(targetOpt.get(), currentNode)) {
        return true;
      }
    }
    return false;
  }

  /** 查找 DSTY DYNAMIC depot 规范。 */
  Optional<DynamicStopMatcher.DynamicSpec> dstyDynamicDepotSpec(List<RouteStop> stops) {
    if (stops == null || stops.isEmpty()) {
      return Optional.empty();
    }
    for (RouteStop stop : stops) {
      Optional<String> dstyTarget = RouteStopDirectives.target(stop, "DSTY");
      if (dstyTarget.isEmpty()) {
        continue;
      }
      Optional<DynamicStopMatcher.DynamicSpec> specOpt =
          DynamicStopMatcher.parseDynamicSpec(dstyTarget.get());
      if (specOpt.isPresent() && specOpt.get().isDepot()) {
        return specOpt;
      }
    }
    return Optional.empty();
  }

  private boolean matchesDstyTarget(String target, NodeId currentNode) {
    if (target == null || target.isBlank() || currentNode == null) {
      return false;
    }
    if (currentNode.value().equalsIgnoreCase(target)) {
      return true;
    }
    Optional<DynamicStopMatcher.DynamicSpec> specOpt = DynamicStopMatcher.parseDynamicSpec(target);
    return specOpt.isPresent() && DynamicStopMatcher.matches(currentNode, specOpt.get());
  }

  /** CHANGE action 解析结果。 */
  record ChangeIntent(
      boolean valid, String operatorCode, String lineCode, String raw, String reason) {
    static ChangeIntent valid(String operatorCode, String lineCode, String raw) {
      return new ChangeIntent(true, operatorCode, lineCode, raw, "ok");
    }

    static ChangeIntent invalid(String raw, String reason) {
      return new ChangeIntent(false, "", "", raw == null ? "" : raw, reason);
    }
  }
}
