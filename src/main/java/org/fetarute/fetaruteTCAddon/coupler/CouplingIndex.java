package org.fetarute.fetaruteTCAddon.coupler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteCouplingDirectives;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TerminalKeyResolver;

/**
 * 交路里写的联挂（{@code COPL FROM:<交路>}）：哪条主干交路在第几站挂哪条回程支线交路。由交路缓存建，交路变了重建。纯计算。
 *
 * <p>回程支线交路按交路码在同一条线、同一交路组里找；找不到、写法不对的联挂不进索引（define 与 validate 已报错）。
 */
final class CouplingIndex {

  /** 空索引。 */
  static final CouplingIndex EMPTY = new CouplingIndex(Map.of(), Map.of());

  /**
   * 一处联挂。
   *
   * @param trunkRoute 主干交路
   * @param stopIndex 联挂站在主干交路里的下标
   * @param stopNode 主干交路在联挂站声明的节点
   * @param feederRoute 回程支线交路
   * @param feederEndIndex 回程支线交路终点（联挂站）的下标
   * @param waitSeconds 指令写的等候秒数；没写时为空（取配置）
   * @param fixedPlatform 主干在联挂站停的是写死的股道（不是 DYNAMIC）：回程支线车选台时停这条
   */
  record Join(
      UUID trunkRoute,
      int stopIndex,
      NodeId stopNode,
      UUID feederRoute,
      int feederEndIndex,
      OptionalInt waitSeconds,
      boolean fixedPlatform) {

    Join {
      Objects.requireNonNull(trunkRoute, "trunkRoute");
      Objects.requireNonNull(stopNode, "stopNode");
      Objects.requireNonNull(feederRoute, "feederRoute");
      Objects.requireNonNull(waitSeconds, "waitSeconds");
    }

    /** 节点与联挂站是不是同一站（允许不同站台）。 */
    boolean atStation(NodeId node) {
      return node != null && TerminalKeyResolver.matches(node.value(), stopNode.value());
    }
  }

  /**
   * 建索引用的一条交路。
   *
   * @param id 交路
   * @param lineId 所属线路
   * @param code 交路码
   * @param group 交路组
   * @param stops 停靠表
   * @param waypoints 路径点（与停靠表下标一致）
   */
  record RouteInfo(
      UUID id,
      UUID lineId,
      String code,
      String group,
      List<RouteStop> stops,
      List<NodeId> waypoints) {

    RouteInfo {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(lineId, "lineId");
      code = code == null ? "" : code;
      group = group == null ? RouteCouplingDirectives.DEFAULT_GROUP : group;
      stops = List.copyOf(stops);
      waypoints = List.copyOf(waypoints);
    }
  }

  private final Map<UUID, List<Join>> byTrunk;
  private final Map<UUID, List<Join>> byFeeder;

  private CouplingIndex(Map<UUID, List<Join>> byTrunk, Map<UUID, List<Join>> byFeeder) {
    this.byTrunk = byTrunk;
    this.byFeeder = byFeeder;
  }

  /** 由全部交路建索引。 */
  static CouplingIndex build(List<RouteInfo> routes) {
    Map<String, RouteInfo> byCode = new HashMap<>();
    for (RouteInfo route : routes) {
      byCode.put(codeKey(route.lineId(), route.group(), route.code()), route);
    }
    Map<UUID, List<Join>> byTrunk = new HashMap<>();
    Map<UUID, List<Join>> byFeeder = new HashMap<>();
    for (RouteInfo route : routes) {
      for (int i = 0; i < route.stops().size() && i < route.waypoints().size(); i++) {
        Optional<RouteCouplingDirectives.Couple> couple =
            RouteCouplingDirectives.couple(route.stops().get(i));
        if (couple.isEmpty()) {
          continue;
        }
        RouteInfo feeder = byCode.get(codeKey(route.lineId(), route.group(), couple.get().from()));
        if (feeder == null || feeder.id().equals(route.id()) || feeder.waypoints().isEmpty()) {
          continue;
        }
        int feederEnd = feeder.waypoints().size() - 1;
        NodeId stopNode = route.waypoints().get(i);
        if (!TerminalKeyResolver.matches(
            feeder.waypoints().get(feederEnd).value(), stopNode.value())) {
          continue;
        }
        Join join =
            new Join(
                route.id(),
                i,
                stopNode,
                feeder.id(),
                feederEnd,
                couple.get().waitSeconds(),
                !DynamicStopMatcher.isDynamicStop(route.stops().get(i)));
        byTrunk.computeIfAbsent(route.id(), key -> new ArrayList<>()).add(join);
        byFeeder.computeIfAbsent(feeder.id(), key -> new ArrayList<>()).add(join);
      }
    }
    if (byTrunk.isEmpty()) {
      return EMPTY;
    }
    Map<UUID, List<Join>> trunks = new HashMap<>();
    byTrunk.forEach((key, value) -> trunks.put(key, List.copyOf(value)));
    Map<UUID, List<Join>> feeders = new HashMap<>();
    byFeeder.forEach((key, value) -> feeders.put(key, List.copyOf(value)));
    return new CouplingIndex(Map.copyOf(trunks), Map.copyOf(feeders));
  }

  /** 有没有任何联挂。 */
  boolean isEmpty() {
    return byTrunk.isEmpty();
  }

  /** 主干交路第 {@code stopIndex} 站的联挂。 */
  Optional<Join> atTrunkStop(UUID trunkRoute, int stopIndex) {
    for (Join join : byTrunk.getOrDefault(trunkRoute, List.of())) {
      if (join.stopIndex() == stopIndex) {
        return Optional.of(join);
      }
    }
    return Optional.empty();
  }

  /** 主干交路上的全部联挂。 */
  List<Join> ofTrunk(UUID trunkRoute) {
    return trunkRoute == null ? List.of() : byTrunk.getOrDefault(trunkRoute, List.of());
  }

  /** 以这条交路为回程支线的全部联挂。 */
  List<Join> ofFeeder(UUID feederRoute) {
    return feederRoute == null ? List.of() : byFeeder.getOrDefault(feederRoute, List.of());
  }

  /** 回程支线联挂之后接着跑的那一处：这条交路只挂上一条主干时就是它；挂上不同的主干（去向说不准）或不挂时为空。 */
  Optional<Join> continuationOf(UUID feederRoute) {
    List<Join> joins = ofFeeder(feederRoute);
    if (joins.isEmpty()) {
      return Optional.empty();
    }
    UUID trunk = joins.get(0).trunkRoute();
    return joins.stream().allMatch(join -> join.trunkRoute().equals(trunk))
        ? Optional.of(joins.get(0))
        : Optional.empty();
  }

  private static String codeKey(UUID lineId, String group, String code) {
    return lineId
        + "|"
        + group.trim().toLowerCase(Locale.ROOT)
        + "|"
        + code.trim().toLowerCase(Locale.ROOT);
  }
}
