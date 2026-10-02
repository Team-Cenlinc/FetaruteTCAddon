package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;

/**
 * 车库咽喉：同一座车库出库与回库共用的那段轨道。
 *
 * <p>单股道的出入段线上，出库车与回库车只能一辆一辆过（对向互斥，同一条边也互斥）。端点串行只管站台组， 这段轨道若不纳入串行资源，出库流与回库流在这里对撞会被判成"可吸收"
 * （后车可以在车库里等），可车在库里一等就晚到下一个单股道端点，那一头的冲突才是真的。
 *
 * <p>咽喉按路网自己算，不靠配置：出库 route（从车库始发的运营 route 与 CREATE 走行）从车库到第一个车站之前走过的边，与 回库 route（以车库收尾的运营 route 与
 * RETURN 走行）从最后一个车站到车库走过的边，两者的<b>交集</b>就是这座车库的咽喉。 出入段分线（交集为空）的车库没有咽喉，不串行。每条经过咽喉的 route 记下它占用咽喉的时段
 * [最早进入, 最晚离开]，相对它自己的发车。
 */
final class DepotThroats {

  /**
   * 一条 route 经过某座车库咽喉的时段，相对这条 route 的发车时刻。
   *
   * @param depot 车库站台组
   * @param enterOffset 最早进入咽喉
   * @param exitOffset 最晚离开咽喉
   */
  record Passage(String depot, int enterOffset, int exitOffset) {}

  private static final DepotThroats NONE = new DepotThroats(Map.of(), Map.of(), Map.of());

  private final Map<UUID, Passage> outbound;
  private final Map<UUID, Passage> inbound;
  private final Map<String, Integer> edgeCountByDepot;

  private DepotThroats(
      Map<UUID, Passage> outbound,
      Map<UUID, Passage> inbound,
      Map<String, Integer> edgeCountByDepot) {
    this.outbound = Map.copyOf(outbound);
    this.inbound = Map.copyOf(inbound);
    this.edgeCountByDepot = Map.copyOf(edgeCountByDepot);
  }

  /**
   * 一张表的全部车库咽喉：出库候选 = 运营 route 与 CREATE 走行，回库候选 = 运营 route 与 RETURN 走行 （首末站是不是车库由 {@link #of}
   * 自己判）。端点串行与相位层共用这一份判定。
   */
  static DepotThroats of(
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
      Collection<UUID> operationRoutes,
      VehicleDutyPlanner.Legs legs) {
    List<UUID> outbound = new ArrayList<>(operationRoutes);
    List<UUID> inbound = new ArrayList<>(operationRoutes);
    for (VehicleDutyPlanner.Leg leg : legs.createByStation().values()) {
      outbound.add(leg.routeId());
    }
    for (List<VehicleDutyPlanner.Leg> candidates : legs.returnCandidates().values()) {
      for (VehicleDutyPlanner.Leg leg : candidates) {
        inbound.add(leg.routeId());
      }
    }
    return of(profiles, outbound, inbound);
  }

  /**
   * 从路网投影算出各车库的咽喉。
   *
   * @param profiles 各 route 的投影（含 CREATE/RETURN）
   * @param outboundCandidates 可能出库的 route：首站是车库才算
   * @param inboundCandidates 可能回库的 route：末站是车库才算
   */
  static DepotThroats of(
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
      Collection<UUID> outboundCandidates,
      Collection<UUID> inboundCandidates) {
    Map<String, Set<EdgeId>> outEdges = new TreeMap<>();
    Map<UUID, String> outDepot = new HashMap<>();
    for (UUID routeId : outboundCandidates) {
      TimetableConflictChecker.RouteProfile profile = profiles.get(routeId);
      Optional<String> depot = depotOf(profile, true);
      if (depot.isEmpty()) {
        continue;
      }
      outDepot.put(routeId, depot.get());
      outEdges
          .computeIfAbsent(depot.get(), key -> new LinkedHashSet<>())
          .addAll(edgesBetween(profile, 0, firstStation(profile)));
    }
    Map<String, Set<EdgeId>> inEdges = new TreeMap<>();
    Map<UUID, String> inDepot = new HashMap<>();
    for (UUID routeId : inboundCandidates) {
      TimetableConflictChecker.RouteProfile profile = profiles.get(routeId);
      Optional<String> depot = depotOf(profile, false);
      if (depot.isEmpty()) {
        continue;
      }
      inDepot.put(routeId, depot.get());
      inEdges
          .computeIfAbsent(depot.get(), key -> new LinkedHashSet<>())
          .addAll(edgesBetween(profile, lastStation(profile), profile.stops().size() - 1));
    }
    Map<String, Set<EdgeId>> throats = new TreeMap<>();
    outEdges.forEach(
        (depot, edges) -> {
          Set<EdgeId> shared = new LinkedHashSet<>(edges);
          shared.retainAll(inEdges.getOrDefault(depot, Set.of()));
          if (!shared.isEmpty()) {
            throats.put(depot, shared);
          }
        });
    if (throats.isEmpty()) {
      return NONE;
    }
    Map<UUID, Passage> outbound = passages(profiles, outDepot, throats);
    Map<UUID, Passage> inbound = passages(profiles, inDepot, throats);
    Map<String, Integer> edgeCount = new TreeMap<>();
    throats.forEach((depot, edges) -> edgeCount.put(depot, edges.size()));
    return new DepotThroats(outbound, inbound, edgeCount);
  }

  boolean isEmpty() {
    return edgeCountByDepot.isEmpty();
  }

  /** 有咽喉的车库，按站台组键排序。 */
  List<String> depots() {
    return List.copyOf(new TreeMap<>(edgeCountByDepot).keySet());
  }

  int edgeCount(String depot) {
    return edgeCountByDepot.getOrDefault(depot, 0);
  }

  Optional<Passage> outbound(UUID routeId) {
    return routeId == null ? Optional.empty() : Optional.ofNullable(outbound.get(routeId));
  }

  Optional<Passage> inbound(UUID routeId) {
    return routeId == null ? Optional.empty() : Optional.ofNullable(inbound.get(routeId));
  }

  private static Map<UUID, Passage> passages(
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
      Map<UUID, String> depotByRoute,
      Map<String, Set<EdgeId>> throats) {
    Map<UUID, Passage> out = new HashMap<>();
    depotByRoute.forEach(
        (routeId, depot) -> {
          Set<EdgeId> edges = throats.get(depot);
          if (edges == null) {
            return;
          }
          int enter = Integer.MAX_VALUE;
          int exit = Integer.MIN_VALUE;
          for (TimetableTimingCalculator.SegmentTiming segment : profiles.get(routeId).segments()) {
            for (int k = 0; k < segment.edges().size(); k++) {
              if (edges.contains(segment.edges().get(k).id())) {
                enter = Math.min(enter, segment.enterOffset(k));
                exit = Math.max(exit, segment.exitOffset(k));
              }
            }
          }
          if (enter <= exit) {
            out.put(routeId, new Passage(depot, enter, exit));
          }
        });
    return out;
  }

  /** 首站（出库）或末站（回库）是车库时给出车库站台组。 */
  private static Optional<String> depotOf(
      TimetableConflictChecker.RouteProfile profile, boolean fromOrigin) {
    if (profile == null) {
      return Optional.empty();
    }
    return (fromOrigin ? profile.origin() : profile.terminal())
        .map(TimetableConflictChecker.Platform::group)
        .filter(DepotThroats::isDepotGroup);
  }

  /** 出库走到的第一个车站停靠点；没有车站时取末站（纯走行 CREATE 的整段都算出库）。 */
  private static int firstStation(TimetableConflictChecker.RouteProfile profile) {
    List<TimetableConflictChecker.Platform> platforms = profile.platforms();
    for (int i = 1; i < platforms.size(); i++) {
      if (isStation(platforms.get(i))) {
        return i;
      }
    }
    return profile.stops().size() - 1;
  }

  /** 回库前经过的最后一个车站停靠点；没有车站时取首站（纯走行 RETURN 的整段都算回库）。 */
  private static int lastStation(TimetableConflictChecker.RouteProfile profile) {
    List<TimetableConflictChecker.Platform> platforms = profile.platforms();
    for (int i = platforms.size() - 2; i >= 0; i--) {
      if (isStation(platforms.get(i))) {
        return i;
      }
    }
    return 0;
  }

  private static boolean isStation(TimetableConflictChecker.Platform platform) {
    return !platform.absent() && isStationGroup(platform.group());
  }

  private static boolean isDepotGroup(String group) {
    String[] parts = group.split(":");
    return parts.length >= 3 && parts[1].equals("D");
  }

  private static boolean isStationGroup(String group) {
    String[] parts = group.split(":");
    return parts.length >= 3 && parts[1].equals("S");
  }

  /** 停靠序号落在 [from, to] 之间的各段走过的边。 */
  private static List<EdgeId> edgesBetween(
      TimetableConflictChecker.RouteProfile profile, int fromStop, int toStop) {
    List<EdgeId> out = new ArrayList<>();
    for (TimetableTimingCalculator.SegmentTiming segment : profile.segments()) {
      if (segment.fromStop() >= fromStop && segment.toStop() <= toStop) {
        segment.edges().forEach(edge -> out.add(edge.id()));
      }
    }
    return out;
  }
}
