package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;

/**
 * 让车点的等待容量：整趟晚发时的始发站，或冲突之前最后一个途中停车站，在已有占用上试放完整等待窗口。
 *
 * <p>复用冲突模型投影与扫描，避免把待命记录条数当成车辆数，也避免只看站台组总容量而忽略具体股道或 DYNAMIC 资格。 资源仅在创建时投影一次；每次查询只复制等待涉及的站台桶与窗口内占用。
 */
final class TimetableWaitingCapacity {
  private final Map<String, TimetableConflictChecker.Resource> resources = new LinkedHashMap<>();
  private final Map<String, TimetableConflictChecker.Platform> origins;
  private final Map<String, Run> runs;
  private final TimetableConflictChecker.Footprints footprints;
  private final Function<String, String> vehicleOf;
  private final Set<String> endpointGroups;
  private final Map<Hold, Boolean> answers = new HashMap<>();
  private final Map<Hold, EnRouteRoom> enRouteAnswers = new HashMap<>();
  private final Map<UUID, Path> paths = new HashMap<>();
  private final Map<String, List<String>> trackKeysByGroup = new HashMap<>();

  private record Hold(
      String code,
      TimetableConflictChecker.Platform platform,
      int from,
      int until,
      int separation) {}

  /**
   * 一次本表运行：从几时起按哪份档案跑。
   *
   * @param leaves 各停靠点车尾离开站台的偏移（{@link TimetableConflictChecker#stopLeaveOffsets}）
   */
  private record Run(
      int start, TimetableConflictChecker.RouteProfile profile, List<Integer> leaves) {}

  /**
   * 途中停车点上的等待：后车在冲突资源之前最后一个停车的车站发车前被挡住。
   *
   * @param stopIndex 停靠序号（不是起点）
   * @param platform 那一站的站台
   * @param departure 那一站的表定发车（相对零点）
   * @param leaves 按原时刻车尾离开那一站站台的时刻（相对零点），即本车在那里的原占用到几时为止
   */
  record EnRoute(
      int stopIndex, TimetableConflictChecker.Platform platform, int departure, int leaves) {
    String group() {
      return platform.group();
    }
  }

  /**
   * 途中停车点上多等的结论。
   *
   * @param fits 放得下：这段等待没有新挡住会与它互等或交叉的车
   * @param followers 被这段等待新挡住、同方向同路径跟在后面的本表后车，code → 它要多等的秒数（按 code 排序）
   */
  record EnRouteRoom(boolean fits, Map<String, Integer> followers) {
    static final EnRouteRoom FULL = new EnRouteRoom(false, Map.of());
    static final EnRouteRoom EMPTY = new EnRouteRoom(true, Map.of());
  }

  /**
   * 一条 route 的路径：节点按经过顺序（段与段相接处只记一次），与各节点相对发车的时刻。
   *
   * @param edges 第 i 条是 {@code nodes[i] → nodes[i+1]} 走的边；段与段接不上的地方为 {@code null}
   * @param stopSequences 节点是某个停靠点（段的端点）时为它的停靠序号，否则为 -1
   */
  private record Path(
      List<String> nodes, List<Integer> times, List<EdgeId> edges, List<Integer> stopSequences) {

    /**
     * 第 {@code index} 个节点之前、本车最后一个停车的停靠点在路径上的下标；之前没有停车点时为 0（起点）。
     *
     * @param stops 档案的停靠点，按停靠序号查停车方式
     */
    int previousStop(int index, List<TimetableStop> stops) {
      for (int i = index - 1; i > 0; i--) {
        int sequence = stopSequences.get(i);
        if (sequence >= 0 && stopsAt(stops, sequence)) {
          return i;
        }
      }
      return 0;
    }

    private static boolean stopsAt(List<TimetableStop> stops, int sequence) {
      for (TimetableStop stop : stops) {
        if (stop.stopSequence() == sequence) {
          return stop.stops();
        }
      }
      return false;
    }
  }

  /**
   * 对方是不是一路跟在本车后面进这一站的同方向同路径后车：从本车上一个停车点（下标 {@code approachFrom}）到进站节点（{@code mine}）这一段，
   * 对方进站之前走的是同一串节点、同一串边——至少要有紧邻前驱与进站边相同。对方起点在这一段中途时，只比它起点之后的部分（它从本车来的路上出发， 同样在本车身后）。
   *
   * <p>只看紧邻前驱会把这几种车误认作后车：支线在站前道岔汇入（它的前驱就是本车也走过的道岔）、环线上反向绕行的车、途中折返之后第二次进站时
   * 从另一侧来的车（它们的前驱都是本车早先走过的节点）。
   */
  private static boolean follows(
      Path myPath, int approachFrom, int mine, Path otherPath, int theirs) {
    int span = Math.min(mine - approachFrom, theirs);
    if (span < 1) {
      return false;
    }
    for (int back = 0; back <= span; back++) {
      if (!myPath.nodes().get(mine - back).equals(otherPath.nodes().get(theirs - back))) {
        return false;
      }
      if (back > 0) {
        EdgeId myEdge = myPath.edges().get(mine - back);
        EdgeId theirEdge = otherPath.edges().get(theirs - back);
        if (myEdge == null || !myEdge.equals(theirEdge)) {
          return false;
        }
      }
    }
    return true;
  }

  private TimetableWaitingCapacity(
      Map<String, TimetableConflictChecker.Platform> origins,
      Map<String, Run> runs,
      Set<String> endpointGroups,
      TimetableConflictChecker.GraphIndex index,
      Function<String, String> vehicleOf) {
    this.origins = Map.copyOf(origins);
    this.runs = Map.copyOf(runs);
    this.endpointGroups = Set.copyOf(endpointGroups);
    this.footprints = new TimetableConflictChecker.Footprints(index);
    this.vehicleOf = vehicleOf;
  }

  /** 从同一次成品表检查的全部运行与待命建立只读基础投影，邻表占用也必须包含在内。 */
  static TimetableWaitingCapacity of(
      Timetable timetable,
      TimetableOccupancyProjector.Occupancy occupancy,
      TimetableConflictChecker.GraphIndex index,
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles) {
    Map<String, TimetableConflictChecker.Platform> origins = new HashMap<>();
    if (timetable != null) {
      for (TimetableTrip trip : timetable.trips()) {
        timetable
            .routePlan(trip.routeId())
            .ifPresent(
                plan -> {
                  String node = plan.originNodeId();
                  origins.put(
                      trip.tripCode(),
                      new TimetableConflictChecker.Platform(
                          node, TimetableConflictChecker.groupOf(node), false));
                });
      }
    }
    TimetableOccupancyProjector.Occupancy actual =
        occupancy == null
            ? new TimetableOccupancyProjector.Occupancy(List.of(), List.of())
            : occupancy;
    Function<String, String> vehicleOf =
        timetable == null ? Function.identity() : TimetableConflictChecker.vehicleOf(timetable);
    Set<String> endpoints = endpointGroupsOf(actual.movements(), profiles);
    for (TimetableConflictChecker.Stay stay : actual.stays()) {
      if (!stay.platform().group().isBlank()) {
        endpoints.add(stay.platform().group());
      }
    }
    TimetableWaitingCapacity out =
        new TimetableWaitingCapacity(
            origins,
            runsOf(actual.movements(), profiles, origins),
            endpoints,
            index == null ? TimetableConflictChecker.GraphIndex.of(null) : index,
            vehicleOf);
    TimetableConflictChecker.projectInto(
        out.resources, out.footprints, profiles, actual.movements(), actual.stays(), vehicleOf);
    return out;
  }

  /**
   * 只认运行、不投影占用：相位第三层手上只有按周期铺开的运行与档案，没有成品表与待命。
   *
   * <p>途中让车点与成品表同一口径认得出来；容量查询面对的是空投影，一律有空位——第三层只在几个 δ 之间做相对比较， 不是最终的成功判据。
   */
  static TimetableWaitingCapacity ofRuns(
      List<TimetableConflictChecker.Movement> movements,
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
      TimetableConflictChecker.GraphIndex index) {
    Map<String, TimetableConflictChecker.Platform> origins = new HashMap<>();
    List<TimetableConflictChecker.Movement> all = movements == null ? List.of() : movements;
    Map<UUID, TimetableConflictChecker.RouteProfile> known = profiles == null ? Map.of() : profiles;
    Map<String, Run> runs = runsOf(all, known, origins);
    return new TimetableWaitingCapacity(
        origins,
        runs,
        endpointGroupsOf(all, known),
        index == null ? TimetableConflictChecker.GraphIndex.of(null) : index,
        Function.identity());
  }

  /** 有车始发或终到的站台组（含邻表）：端点与折返站。车在这里多等挡住的任何车都算放不下。 */
  private static Set<String> endpointGroupsOf(
      List<TimetableConflictChecker.Movement> movements,
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles) {
    Set<String> out = new HashSet<>();
    Set<UUID> seen = new HashSet<>();
    for (TimetableConflictChecker.Movement movement : movements) {
      TimetableConflictChecker.RouteProfile profile = profiles.get(movement.routeId());
      if (profile == null || !seen.add(movement.routeId()) || profile.platforms().isEmpty()) {
        continue;
      }
      for (TimetableConflictChecker.Platform end :
          List.of(
              profile.platforms().get(0),
              profile.platforms().get(profile.platforms().size() - 1))) {
        if (!end.absent() && !end.group().isBlank()) {
          out.add(end.group());
        }
      }
    }
    return out;
  }

  /** 本表运行（邻表的不算：它们不是我要让的车）；起点站台顺手按档案补上。 */
  private static Map<String, Run> runsOf(
      List<TimetableConflictChecker.Movement> movements,
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
      Map<String, TimetableConflictChecker.Platform> origins) {
    Map<String, Run> runs = new HashMap<>();
    Map<UUID, List<Integer>> leavesByRoute = new HashMap<>();
    for (TimetableConflictChecker.Movement movement : movements) {
      TimetableConflictChecker.RouteProfile profile = profiles.get(movement.routeId());
      if (profile != null && movement.owner().isEmpty()) {
        profile.origin().ifPresent(platform -> origins.put(movement.code(), platform));
        List<Integer> leaves =
            leavesByRoute.computeIfAbsent(
                movement.routeId(), ignored -> TimetableConflictChecker.stopLeaveOffsets(profile));
        runs.put(movement.code(), new Run(movement.startSeconds(), profile, leaves));
      }
    }
    return runs;
  }

  /** 车库等待不占站台；其余等待必须在完整窗口及安全裕量内满足同车去重与股道资格。 */
  boolean hasRoom(String code, String group, int from, int waitSeconds, int separation) {
    if (group == null || group.isBlank()) {
      return true;
    }
    return hasRoom(
        code,
        origins.getOrDefault(code, new TimetableConflictChecker.Platform("", group, true)),
        from,
        waitSeconds,
        separation);
  }

  /**
   * 途中停车点上的等待：股道取那一站的站台，在已有占用上试放 {@code [表定发车, 原车尾离开 + 等待]}，看它新挡住了谁。
   *
   * <p>只数这段等待<b>新造成</b>的冲突（{@link TimetableConflictChecker#conflictsAddedBy}）：本车按原时刻停到车尾离开为止，
   * 那段时间里本就挡得住的车（前车刚离开、裕量盖住了发车，或紧跟着进站的车）是到站时就有的冲突，别的车之间原有的冲突也各有记录， 都不是多等造成的。恰在原离开 +
   * 裕量进入的车按原时刻不冲突、多等就撞上，算挡住。
   *
   * <p>新挡住的车分两种。<b>同方向同路径跟在后面的本表后车</b>：从本车上一个停车点起到进这一站，它走的是与本车同一串节点、 同一条进站边（{@link
   * #follows}）——它在本车身后同一段线路上，运行时在前一个闭塞区间排队，是连锁延误、不会与本车互卡，
   * 不算放不下，记下它要多等多久交给连锁判据。其余都算放不下：对向来车、从别的路径汇入或交叉的车（哪怕只在站前最后一个道岔才并上来）、
   * 邻表的车（挪不动，也算不了它的连锁）、待命车（不知道从哪来），以及判不清的（档案没有逐边路径、同一站台组经过多次分不出是哪一次）。
   * 这一站本身是有车始发或终到的端点、折返站时，挡住任何车都算放不下；这一条排在固定股道那一层之前，是有意的保守：端点上的超员
   * 哪怕只是站台组一层多算一辆，也会卡住折返与出入库，不去细分。不是端点时，先去掉只在站台组一层超员、没真被挡住的车（{@link #blockedOnTheirTrack}）。
   */
  EnRouteRoom room(String code, EnRoute stop, int waitSeconds, int separation) {
    int sep = Math.max(0, separation);
    int from = stop.departure();
    int until = (int) Math.min(Integer.MAX_VALUE, (long) stop.leaves() + Math.max(0, waitSeconds));
    Hold hold = new Hold(code, stop.platform(), from, until, sep);
    return enRouteAnswers.computeIfAbsent(
        hold,
        key -> {
          List<TimetableConflictChecker.Conflict> added =
              TimetableConflictChecker.conflictsAddedBy(
                  resources,
                  footprints,
                  new TimetableConflictChecker.Stay(
                      code, stop.platform(), from, until, Optional.empty()),
                  vehicleOf,
                  sep);
          return judge(code, stop, until, sep, added);
        });
  }

  private EnRouteRoom judge(
      String code,
      EnRoute stop,
      int until,
      int separation,
      List<TimetableConflictChecker.Conflict> added) {
    if (added.isEmpty()) {
      return EnRouteRoom.EMPTY;
    }
    String group = stop.group();
    if (endpointGroups.contains(group)) {
      return EnRouteRoom.FULL;
    }
    List<TimetableConflictChecker.Conflict> blocked = blockedOnTheirTrack(stop.platform(), added);
    if (blocked.isEmpty()) {
      return EnRouteRoom.EMPTY;
    }
    Run mine = runs.get(code);
    Optional<Integer> myVisit =
        mine == null ? Optional.empty() : visit(mine, group, stop.departure());
    if (myVisit.isEmpty() || myVisit.get() == 0) {
      return EnRouteRoom.FULL;
    }
    Path myPath = path(mine);
    int approachFrom = myPath.previousStop(myVisit.get(), mine.profile().stops());
    Map<String, Integer> followers = new TreeMap<>();
    for (TimetableConflictChecker.Conflict conflict : blocked) {
      Run other = conflict.secondOwner().isPresent() ? null : runs.get(conflict.second());
      Optional<Integer> at =
          other == null ? Optional.empty() : visit(other, group, conflict.secondFrom());
      if (at.isEmpty() || !follows(myPath, approachFrom, myVisit.get(), path(other), at.get())) {
        return EnRouteRoom.FULL;
      }
      int wait = (int) Math.max(0L, (long) until + separation - conflict.secondFrom());
      followers.merge(conflict.second(), wait, Math::max);
    }
    return new EnRouteRoom(true, java.util.Collections.unmodifiableMap(followers));
  }

  /**
   * 去掉只在站台组一层超员、没真被挡住的那些：本车停的是自己的固定股道时，另一辆停在别的固定股道上的车， 若在它自己的股道上没有新增冲突，站台组的超员只是按"车辆数 +
   * 裕量"多算了一辆（例如它那股道上前车刚走、裕量还没过），本车并没有挡住它。
   *
   * <p>本车是 DYNAMIC 停靠或带股道资格要求时没有自己的固定股道，站台组层的超员就是真的少一股道，照旧算；被挡的车没有固定股道（DYNAMIC）时同理。
   * 等待只占站台资源，被挡的车在区间、道岔上不会因它新增冲突，只看股道一层就够。
   */
  private List<TimetableConflictChecker.Conflict> blockedOnTheirTrack(
      TimetableConflictChecker.Platform platform, List<TimetableConflictChecker.Conflict> added) {
    if (platform.dynamic() || !platform.requirements().isEmpty() || platform.nodeId().isBlank()) {
      return added;
    }
    List<TimetableConflictChecker.Conflict> kept = new ArrayList<>(added.size());
    for (TimetableConflictChecker.Conflict conflict : added) {
      if (!conflict.resource().startsWith(GROUP_PREFIX)
          || onOwnTrack(added, conflict)
          || !hasFixedTrack(platform.group(), conflict)) {
        kept.add(conflict);
      }
    }
    return kept;
  }

  private static final String GROUP_PREFIX = "platform-group:";
  private static final String TRACK_PREFIX = "platform:";

  /** 同一辆被挡的车在某一股道上也有新增冲突：真被挡住了。 */
  private static boolean onOwnTrack(
      List<TimetableConflictChecker.Conflict> added, TimetableConflictChecker.Conflict blocked) {
    for (TimetableConflictChecker.Conflict other : added) {
      if (other.resource().startsWith(TRACK_PREFIX)
          && other.second().equals(blocked.second())
          && other.secondFrom() == blocked.secondFrom()
          && other.secondOwner().equals(blocked.secondOwner())) {
        return true;
      }
    }
    return false;
  }

  /** 被挡的这一次进站登记在这一站台组的某条固定股道上（DYNAMIC 停靠只有站台组一层）。 */
  private boolean hasFixedTrack(String group, TimetableConflictChecker.Conflict blocked) {
    for (String key : trackKeys(group)) {
      for (TimetableConflictChecker.Occupation occupation : resources.get(key).occupations()) {
        if (occupation.from() > blocked.secondFrom()) {
          break;
        }
        if (occupation.from() == blocked.secondFrom()
            && occupation.code().equals(blocked.second())
            && occupation.owner().equals(blocked.secondOwner())) {
          return true;
        }
      }
    }
    return false;
  }

  /** 基础投影里属于这一站台组的具体股道键。 */
  private List<String> trackKeys(String group) {
    return trackKeysByGroup.computeIfAbsent(
        group,
        ignored -> {
          List<String> keys = new ArrayList<>();
          for (String key : resources.keySet()) {
            if (key.startsWith(TRACK_PREFIX)
                && group.equals(
                    TimetableConflictChecker.groupOf(key.substring(TRACK_PREFIX.length())))) {
              keys.add(key);
            }
          }
          return List.copyOf(keys);
        });
  }

  /**
   * 这次运行在站台组 {@code group} 的哪一次经过（路径下标）：取时刻离 {@code atSecond} 最近的那一次。
   *
   * <p>没有逐边路径、没经过这一组，或最近的有两次分不出时为空——调用方按放不下处理。
   */
  private Optional<Integer> visit(Run run, String group, int atSecond) {
    Path path = path(run);
    int best = -1;
    long bestGap = Long.MAX_VALUE;
    boolean tie = false;
    for (int i = 0; i < path.nodes().size(); i++) {
      if (!group.equals(TimetableConflictChecker.groupOf(path.nodes().get(i)))) {
        continue;
      }
      long gap = Math.abs((long) run.start() + path.times().get(i) - atSecond);
      if (gap < bestGap) {
        best = i;
        bestGap = gap;
        tie = false;
      } else if (gap == bestGap) {
        tie = true;
      }
    }
    return best < 0 || tie ? Optional.empty() : Optional.of(best);
  }

  private Path path(Run run) {
    return paths.computeIfAbsent(run.profile().routeId(), ignored -> pathOf(run.profile()));
  }

  private static Path pathOf(TimetableConflictChecker.RouteProfile profile) {
    List<String> nodes = new ArrayList<>();
    List<Integer> times = new ArrayList<>();
    List<EdgeId> edges = new ArrayList<>();
    List<Integer> sequences = new ArrayList<>();
    for (TimetableTimingCalculator.SegmentTiming segment : profile.segments()) {
      for (int k = 0; k < segment.nodes().size(); k++) {
        String node = segment.nodes().get(k).value();
        int sequence =
            k == 0 ? segment.fromStop() : k == segment.nodes().size() - 1 ? segment.toStop() : -1;
        if (k == 0 && !nodes.isEmpty() && nodes.get(nodes.size() - 1).equals(node)) {
          continue;
        }
        if (!nodes.isEmpty()) {
          // 第 k 个节点之前的那条边；段首节点与上一段末节点接不上时没有边。
          edges.add(k == 0 ? null : segment.edges().get(k - 1).id());
        }
        nodes.add(node);
        times.add(segment.nodeOffsets().get(k));
        sequences.add(sequence);
      }
    }
    edges.add(null);
    return new Path(
        List.copyOf(nodes),
        List.copyOf(times),
        java.util.Collections.unmodifiableList(edges),
        List.copyOf(sequences));
  }

  private boolean hasRoom(
      String code,
      TimetableConflictChecker.Platform platform,
      int from,
      int waitSeconds,
      int separation) {
    int until = (int) Math.min(Integer.MAX_VALUE, (long) from + Math.max(0, waitSeconds));
    Hold hold = new Hold(code, platform, from, until, Math.max(0, separation));
    return answers.computeIfAbsent(
        hold,
        key ->
            TimetableConflictChecker.accommodatesStay(
                resources,
                footprints,
                new TimetableConflictChecker.Stay(code, platform, from, until, Optional.empty()),
                vehicleOf,
                key.separation()));
  }

  /**
   * 后车在冲突资源之前最后一个停车的车站：运行时它在那一站的发车门控被挡住（或驶出后在区间信号前等），不会回到起点去等。
   *
   * <p>取表定发车不晚于进入冲突资源时刻的最后一个途中停车点（PASS 不算）。冲突在第一个停车点之前（含到站本身）、 那一站不是站台、或手上没有这次运行的档案时为空，仍按起点判。
   *
   * @param code 后车的运行 code
   * @param enterSecond 后车进入冲突资源的时刻（相对零点）
   */
  Optional<EnRoute> enRoute(String code, int enterSecond) {
    Run run = runs.get(code);
    if (run == null) {
      return Optional.empty();
    }
    List<TimetableStop> stops = run.profile().stops();
    List<TimetableConflictChecker.Platform> platforms = run.profile().platforms();
    for (int i = stops.size() - 2; i >= 1; i--) {
      TimetableStop stop = stops.get(i);
      if (!stop.stops()) {
        continue;
      }
      int departure = run.start() + stop.departureOffsetSeconds();
      if (departure > enterSecond) {
        continue;
      }
      if (i >= platforms.size() || platforms.get(i).absent()) {
        return Optional.empty();
      }
      int leaves = run.start() + (i < run.leaves().size() ? run.leaves().get(i) : 0);
      return Optional.of(new EnRoute(i, platforms.get(i), departure, Math.max(departure, leaves)));
    }
    return Optional.empty();
  }
}
