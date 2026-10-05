package org.fetarute.fetaruteTCAddon.dispatcher.route;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.LineSpawnMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnDepot;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnDirectiveParser;

/**
 * 判断一个图节点是否被交路用到：途经点、停靠（含同站其他股道与 DYNAMIC 范围）、出车/回库指令的目标、线路的出车车库。
 *
 * <p>只看声明，不看两站之间最短路经过的中间点：TrainCarts destination 只写声明过的节点，中间点牌子缺失只会让占用释放更保守。
 * 站点/车库本体（四段）按"同站"比较，比运行时的匹配更宽，宁可多判在用；咽喉与区间点只认精确相同。
 */
public final class RouteNodeUsage {

  private static final List<String> DIRECTIVES = List.of("CRET", "DSTY");

  private RouteNodeUsage() {}

  /**
   * 找出第一处用到 {@code node} 的地方。
   *
   * @return 用途说明（如"交路 SURC:MT:MT-3 第 5 个途经点"）；没有交路用到时为空
   */
  public static Optional<String> findUse(
      Collection<RouteDefinitionCache.RouteEntry> entries, NodeId node) {
    if (entries == null || node == null || node.value() == null) {
      return Optional.empty();
    }
    Set<UUID> checkedLines = new HashSet<>();
    for (RouteDefinitionCache.RouteEntry entry : entries) {
      String route = "交路 " + entry.definition().id().value();
      List<NodeId> waypoints = entry.definition().waypoints();
      for (int i = 0; i < waypoints.size(); i++) {
        if (sameNodeOrStation(node, waypoints.get(i).value())) {
          return Optional.of(route + " 第 " + (i + 1) + " 个途经点");
        }
      }
      for (RouteStop stop : entry.stops()) {
        if (DynamicStopMatcher.matchesStop(node, stop)) {
          return Optional.of(route + " 第 " + (stop.sequence() + 1) + " 站");
        }
        for (String directive : DIRECTIVES) {
          Optional<String> target = SpawnDirectiveParser.findDirectiveTarget(stop, directive);
          if (target.isPresent() && targetMatches(node, target.get())) {
            return Optional.of(route + " 的 " + directive + " 目标");
          }
        }
      }
      Line line = entry.record().line();
      if (line != null && checkedLines.add(line.id())) {
        for (SpawnDepot depot : LineSpawnMetadata.parseDepots(line.metadata())) {
          if (targetMatches(node, depot.nodeId())) {
            return Optional.of("线路 " + line.code() + " 的出车车库");
          }
        }
      }
    }
    return Optional.empty();
  }

  /** 指令目标或出车车库：可以是节点 ID，也可以是 DYNAMIC 规范。 */
  static boolean targetMatches(NodeId node, String target) {
    if (target == null || target.isBlank()) {
      return false;
    }
    if (SpawnDirectiveParser.isDynamicTarget(target)) {
      return DynamicStopMatcher.parseDynamicSpec(target)
          .map(spec -> DynamicStopMatcher.matches(node, spec))
          .orElse(false);
    }
    return sameNodeOrStation(node, target);
  }

  static boolean sameNodeOrStation(NodeId node, String other) {
    if (other == null) {
      return false;
    }
    String value = node.value();
    if (value.equalsIgnoreCase(other.trim())) {
      return true;
    }
    if (!isStationBody(value) || !isStationBody(other)) {
      return false;
    }
    Optional<String> key = DynamicStopMatcher.extractStationKey(value);
    return key.isPresent() && key.equals(DynamicStopMatcher.extractStationKey(other));
  }

  /** 站点/车库本体：{@code OP:S|D:NAME:TRACK} 四段；五段的是咽喉。 */
  private static boolean isStationBody(String value) {
    return value.trim().split(":", -1).length == 4;
  }
}
