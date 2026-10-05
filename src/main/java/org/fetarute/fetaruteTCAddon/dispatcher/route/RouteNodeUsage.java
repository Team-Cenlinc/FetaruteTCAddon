package org.fetarute.fetaruteTCAddon.dispatcher.route;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * 哪些图节点被交路用到的索引：途经点、停靠（含同站其他股道与 DYNAMIC 范围）、出车/回库指令的目标、线路的出车车库。
 *
 * <p>只看声明，不看两站之间最短路经过的中间点：TrainCarts destination 只写声明过的节点，中间点牌子缺失只会让占用释放更保守。
 *
 * <p>交路缓存变化一次建一次；之后每次查询只做几次哈希查找，WorldEdit 一次拆掉很多牌子也不会每块都把全部交路扫一遍。匹配规则与 {@link
 * DynamicStopMatcher#matchesStop} 一致，站点/车库本体（四段）还按"同站"放宽，宁可多判在用。
 */
public final class RouteNodeUsage {

  private static final List<String> DIRECTIVES = List.of("CRET", "DSTY");

  /** 精确节点 ID（小写）→ 用途。 */
  private final Map<String, String> exactUses = new HashMap<>();

  /** 站点/车库本体的同站键 → 用途。 */
  private final Map<String, String> bodyStationUses = new HashMap<>();

  /** 咽喉的同站键 → 用途：停靠声明的咽喉容许同站其他咽喉匹配（与 matchesStop 一致）。 */
  private final Map<String, String> throatStationUses = new HashMap<>();

  /** DYNAMIC 规则按同站键分组。 */
  private final Map<String, List<DynamicUse>> dynamicUses = new HashMap<>();

  private RouteNodeUsage() {}

  /** 由交路缓存的全部条目建索引。 */
  public static RouteNodeUsage index(Collection<RouteDefinitionCache.RouteEntry> entries) {
    RouteNodeUsage usage = new RouteNodeUsage();
    if (entries == null) {
      return usage;
    }
    Set<UUID> indexedLines = new HashSet<>();
    for (RouteDefinitionCache.RouteEntry entry : entries) {
      String route = "交路 " + entry.definition().id().value();
      List<NodeId> waypoints = entry.definition().waypoints();
      for (int i = 0; i < waypoints.size(); i++) {
        usage.addNodeOrStation(waypoints.get(i).value(), route + " 第 " + (i + 1) + " 个途经点");
      }
      for (RouteStop stop : entry.stops()) {
        String where = route + " 第 " + (stop.sequence() + 1) + " 站";
        stop.waypointNodeId().ifPresent(node -> usage.addStopNode(node, where));
        DynamicStopMatcher.parseDynamicSpec(stop).ifPresent(spec -> usage.addDynamic(spec, where));
        for (String directive : DIRECTIVES) {
          SpawnDirectiveParser.findDirectiveTarget(stop, directive)
              .ifPresent(target -> usage.addTarget(target, route + " 的 " + directive + " 目标"));
        }
      }
      Line line = entry.record().line();
      if (line != null && indexedLines.add(line.id())) {
        for (SpawnDepot depot : LineSpawnMetadata.parseDepots(line.metadata())) {
          usage.addTarget(depot.nodeId(), "线路 " + line.code() + " 的出车车库");
        }
      }
    }
    return usage;
  }

  /**
   * 找出用到 {@code node} 的一处地方。
   *
   * @return 用途说明（如"交路 SURC:MT:MT-3 第 5 个途经点"）；没有交路用到时为空
   */
  public Optional<String> findUse(NodeId node) {
    if (node == null || node.value() == null) {
      return Optional.empty();
    }
    String value = node.value().trim();
    String exact = exactUses.get(value.toLowerCase(Locale.ROOT));
    if (exact != null) {
      return Optional.of(exact);
    }
    Optional<String> key = DynamicStopMatcher.extractStationKey(value);
    if (key.isEmpty()) {
      return Optional.empty();
    }
    if (isThroat(value)) {
      return Optional.ofNullable(throatStationUses.get(key.get()));
    }
    String sameStation = bodyStationUses.get(key.get());
    if (sameStation != null) {
      return Optional.of(sameStation);
    }
    for (DynamicUse dynamic : dynamicUses.getOrDefault(key.get(), List.of())) {
      if (DynamicStopMatcher.matches(value, dynamic.spec())) {
        return Optional.of(dynamic.where());
      }
    }
    return Optional.empty();
  }

  /** 途经点、指令目标、出车车库：精确相同，或同为站点/车库本体且同站。 */
  private void addNodeOrStation(String node, String where) {
    if (node == null || node.isBlank()) {
      return;
    }
    String value = node.trim();
    exactUses.putIfAbsent(value.toLowerCase(Locale.ROOT), where);
    if (isBody(value)) {
      DynamicStopMatcher.extractStationKey(value)
          .ifPresent(key -> bodyStationUses.putIfAbsent(key, where));
    }
  }

  /** 停靠声明的节点：同站容错与 matchesStop 一致——本体对本体、咽喉对咽喉。 */
  private void addStopNode(String node, String where) {
    addNodeOrStation(node, where);
    String value = node.trim();
    if (isThroat(value)) {
      DynamicStopMatcher.extractStationKey(value)
          .ifPresent(key -> throatStationUses.putIfAbsent(key, where));
    }
  }

  private void addTarget(String target, String where) {
    if (target == null || target.isBlank()) {
      return;
    }
    if (SpawnDirectiveParser.isDynamicTarget(target)) {
      DynamicStopMatcher.parseDynamicSpec(target).ifPresent(spec -> addDynamic(spec, where));
      return;
    }
    addNodeOrStation(target, where);
  }

  private void addDynamic(DynamicStopMatcher.DynamicSpec spec, String where) {
    dynamicUses
        .computeIfAbsent(DynamicStopMatcher.specToStationKey(spec), ignored -> new ArrayList<>())
        .add(new DynamicUse(spec, where));
  }

  /** 站点/车库本体：{@code OP:S|D:NAME:TRACK} 四段。 */
  private static boolean isBody(String value) {
    return value.split(":", -1).length == 4;
  }

  /** 站咽喉/车库咽喉：{@code OP:S|D:NAME:TRACK:SEQ} 五段。 */
  private static boolean isThroat(String value) {
    String[] parts = value.split(":", -1);
    if (parts.length != 5) {
      return false;
    }
    String marker = parts[1].trim().toUpperCase(Locale.ROOT);
    return "S".equals(marker) || "D".equals(marker);
  }

  private record DynamicUse(DynamicStopMatcher.DynamicSpec spec, String where) {}
}
