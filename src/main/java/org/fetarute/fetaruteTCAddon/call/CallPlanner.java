package org.fetarute.fetaruteTCAddon.call;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.RunCurveModel;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPath;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPathFinder;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.DepotSpawnPattern;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnPatternLength;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableEdgeSpeeds;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableStop;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableTimingCalculator;

/**
 * 叫车派哪辆车、多久能到。
 *
 * <p>三种车源取预计最快到站的：首站的待命车（折返复用）、本站上游的区间点直接生成、首站是车库的从车库出车。 待命车比最快的慢不到 {@link #STANDBY_PREFERENCE}
 * 时仍用它——车已经在网里，不必再造一辆。
 *
 * <p>区间生成只在本站上游、轨道距离不少于 {@link #ENTRY_MIN_DISTANCE_BLOCKS} 的区间点进行，附近 {@link
 * #ENTRY_PLAYER_RADIUS_BLOCKS} 格内有玩家就换更远的点；车身铺在区间点后方，那一段不能有道岔、咽喉。
 */
final class CallPlanner {

  /** 车源。 */
  enum Source {
    /** 首站的待命车。 */
    STANDBY,
    /** 本站上游的区间点生成。 */
    WAYPOINT,
    /** 从车库出车。 */
    DEPOT
  }

  /**
   * 一趟叫车的安排。
   *
   * @param routeId 跑哪条交路
   * @param stopIndex 本站在交路里的下标
   * @param source 车源
   * @param entryIndex 区间生成的下标（车源为 {@link Source#WAYPOINT} 时）
   * @param etaSeconds 预计多少秒后到站；估不出时为空
   */
  record Plan(
      UUID routeId, int stopIndex, Source source, OptionalInt entryIndex, OptionalInt etaSeconds) {

    OptionalInt etaMinutes() {
      return etaSeconds.isPresent()
          ? OptionalInt.of(Math.max(1, (etaSeconds.getAsInt() + 59) / 60))
          : OptionalInt.empty();
    }
  }

  static final Duration STANDBY_PREFERENCE = Duration.ofMinutes(2);
  static final int ENTRY_MIN_DISTANCE_BLOCKS = 64;
  static final double ENTRY_PLAYER_RADIUS_BLOCKS =
      org.fetarute
          .fetaruteTCAddon
          .dispatcher
          .schedule
          .spawn
          .TrainCartsDepotSpawner
          .ENTRY_PLAYER_RADIUS_BLOCKS;
  private static final long DEFAULT_TRAIN_LENGTH_BLOCKS = 48L;
  private static final int BODY_MARGIN_BLOCKS = 4;
  private static final int STANDBY_OVERHEAD_SECONDS = 15;
  private static final int ENTRY_OVERHEAD_SECONDS = 20;
  private static final int DEPOT_OVERHEAD_SECONDS = 40;
  private static final double FALLBACK_SPEED_BPS = 8.0D;
  private static final Duration TIMING_TTL = Duration.ofMinutes(10);
  private static final Duration DEFAULT_DWELL = Duration.ofSeconds(20);

  private final FetaruteTCAddon plugin;
  private final Map<UUID, CachedTiming> timings = new ConcurrentHashMap<>();
  private final Map<UUID, RouteGeometry> geometries = new ConcurrentHashMap<>();
  private final TimetableTimingCalculator timingCalculator = new TimetableTimingCalculator();
  private final RailGraphPathFinder pathFinder = new RailGraphPathFinder();

  CallPlanner(FetaruteTCAddon plugin) {
    this.plugin = plugin;
  }

  /**
   * 为一个方向排一趟车。
   *
   * @param direction 方向
   * @param dutyBound 列车是否绑着时刻表交路（这样的待命车不接）
   * @param now 当前时刻
   * @return 安排；没有车可派时为空
   */
  Optional<Plan> plan(
      CallCatalog.CallDirection direction, Predicate<String> dutyBound, Instant now) {
    Optional<RouteDefinitionCache> cacheOpt = plugin.getRouteDefinitionCache();
    if (cacheOpt.isEmpty()) {
      return Optional.empty();
    }
    RouteDefinitionCache cache = cacheOpt.get();
    Plan best = null;
    Plan bestStandby = null;
    for (CallCatalog.CallRoute route : direction.routes()) {
      Optional<RouteDefinition> definitionOpt = cache.findById(route.routeId());
      if (definitionOpt.isEmpty()) {
        continue;
      }
      RouteDefinition definition = definitionOpt.get();
      List<RouteStop> stops = cache.listStops(definition.id());
      Optional<WorldGraph> graph = graphOf(definition);
      Optional<int[][]> timing = timingOf(route.routeId(), definition, stops, graph);
      int stop = route.stopIndex();
      if (!route.fromDepot()) {
        OptionalLong readyIn = standbyReadyIn(route.startNode(), dutyBound, now);
        if (readyIn.isPresent()) {
          OptionalInt eta =
              timing
                  .map(
                      t ->
                          OptionalInt.of(
                              t[0][stop] + (int) readyIn.getAsLong() + STANDBY_OVERHEAD_SECONDS))
                  .orElse(OptionalInt.empty());
          Plan plan = new Plan(route.routeId(), stop, Source.STANDBY, OptionalInt.empty(), eta);
          bestStandby = faster(bestStandby, plan);
          best = faster(best, plan);
        }
      } else {
        OptionalInt eta =
            timing
                .map(t -> OptionalInt.of(t[0][stop] + DEPOT_OVERHEAD_SECONDS))
                .orElse(OptionalInt.empty());
        best =
            faster(best, new Plan(route.routeId(), stop, Source.DEPOT, OptionalInt.empty(), eta));
      }
      if (graph.isPresent()) {
        OptionalInt entry =
            pickEntry(
                route.routeId(), definition, stop, graph.get(), trainLengthBlocks(route.routeId()));
        if (entry.isPresent()) {
          int j = entry.getAsInt();
          OptionalInt eta =
              timing
                  .map(t -> OptionalInt.of(t[0][stop] - t[1][j] + ENTRY_OVERHEAD_SECONDS))
                  .orElse(OptionalInt.empty());
          best = faster(best, new Plan(route.routeId(), stop, Source.WAYPOINT, entry, eta));
        }
      }
    }
    if (bestStandby != null && best != null && bestStandby != best) {
      OptionalInt standbyEta = bestStandby.etaSeconds();
      OptionalInt bestEta = best.etaSeconds();
      if (standbyEta.isEmpty()
          || bestEta.isEmpty()
          || standbyEta.getAsInt() - bestEta.getAsInt() < STANDBY_PREFERENCE.toSeconds()) {
        best = bestStandby;
      }
    }
    return Optional.ofNullable(best);
  }

  /** 预计更快的那个；估不出时间的排在估得出的后面，同为估不出时先到先得。 */
  private static Plan faster(Plan current, Plan candidate) {
    if (current == null) {
      return candidate;
    }
    if (candidate.etaSeconds().isEmpty()) {
      return current;
    }
    if (current.etaSeconds().isEmpty()) {
      return candidate;
    }
    return candidate.etaSeconds().getAsInt() < current.etaSeconds().getAsInt()
        ? candidate
        : current;
  }

  /** 首站有可接的待命车时，返回它还要几秒才能发车（已就绪为 0）；没有时为空。 */
  private OptionalLong standbyReadyIn(String startNode, Predicate<String> dutyBound, Instant now) {
    Optional<LayoverRegistry> registry = plugin.getLayoverRegistry();
    if (registry.isEmpty()) {
      return OptionalLong.empty();
    }
    long best = Long.MAX_VALUE;
    for (LayoverRegistry.LayoverCandidate candidate : registry.get().findCandidates(startNode)) {
      if (candidate == null
          || candidate.dispatchAttempt().isPresent()
          || dutyBound.test(candidate.trainName())) {
        continue;
      }
      long wait =
          candidate.readyAt() == null
              ? 0L
              : Math.max(0L, Duration.between(now, candidate.readyAt()).toSeconds());
      best = Math.min(best, wait);
    }
    return best == Long.MAX_VALUE ? OptionalLong.empty() : OptionalLong.of(best);
  }

  /**
   * 本站上游的区间生成点：从本站往回走，取第一个离本站够远、附近没有玩家、车身那一段放得下又没有道岔的区间点。
   *
   * <p>轨道几何（区段长度、车身段是否平直）按交路缓存，这里只现查占用与附近玩家。
   *
   * @return 区间点在交路节点表里的下标；找不到时为空
   */
  OptionalInt pickEntry(
      UUID routeId,
      RouteDefinition definition,
      int stopIndex,
      WorldGraph worldGraph,
      long trainLength) {
    RouteGeometry geometry = geometryOf(routeId, definition, worldGraph, trainLength);
    List<NodeId> nodes = definition.waypoints();
    World world = Bukkit.getWorld(worldGraph.worldId());
    OccupancyManager occupancy = plugin.getOccupancyManager();
    long distance = 0L;
    for (int j = stopIndex - 1; j >= 1; j--) {
      long leg = geometry.legBlocks()[j];
      if (leg < 0L) {
        return OptionalInt.empty();
      }
      distance += leg;
      if (distance < ENTRY_MIN_DISTANCE_BLOCKS || !geometry.entryOk()[j]) {
        continue;
      }
      if (occupancy != null && occupancy.isNodeOccupied(nodes.get(j))) {
        continue;
      }
      Optional<Vector> position =
          worldGraph.graph().findNode(nodes.get(j)).map(RailNode::worldPosition);
      if (position.isEmpty() || playerNearby(world, position.get())) {
        continue;
      }
      return OptionalInt.of(j);
    }
    return OptionalInt.empty();
  }

  /**
   * 本站上游有没有能生成车的区间点：只看轨道几何（离本站够远、车身那一段放得下又没有道岔），不看此刻的占用与附近玩家（交路校验用）。
   *
   * @return 交路或调度图查不到时为 false
   */
  boolean entryPossible(UUID routeId, int stopIndex) {
    Optional<RouteDefinition> definition =
        plugin.getRouteDefinitionCache().flatMap(cache -> cache.findById(routeId));
    Optional<WorldGraph> graph = definition.flatMap(this::graphOf);
    if (definition.isEmpty() || graph.isEmpty()) {
      return false;
    }
    RouteGeometry geometry =
        geometryOf(routeId, definition.get(), graph.get(), trainLengthBlocks(routeId));
    long distance = 0L;
    for (int j = stopIndex - 1; j >= 1; j--) {
      long leg = geometry.legBlocks()[j];
      if (leg < 0L) {
        return false;
      }
      distance += leg;
      if (distance >= ENTRY_MIN_DISTANCE_BLOCKS && geometry.entryOk()[j]) {
        return true;
      }
    }
    return false;
  }

  /**
   * 交路的轨道几何：{@code legBlocks[i]} 是节点 i 到 i+1 的轨道长度（不可达为 -1）；{@code entryOk[j]} 表示节点 j 是区间点、
   * 它后方那一段放得下整列车且没有道岔、咽喉、车站。
   */
  private RouteGeometry geometryOf(
      UUID routeId, RouteDefinition definition, WorldGraph worldGraph, long trainLength) {
    Instant now = Instant.now();
    RouteGeometry cached = geometries.get(routeId);
    if (cached != null
        && cached.trainLength() == trainLength
        && now.isBefore(cached.computedAt().plus(TIMING_TTL))) {
      return cached;
    }
    List<NodeId> nodes = definition.waypoints();
    RailGraph graph = worldGraph.graph();
    long[] legs = new long[nodes.size()];
    boolean[] entryOk = new boolean[nodes.size()];
    java.util.Arrays.fill(legs, -1L);
    for (int i = 0; i + 1 < nodes.size(); i++) {
      Optional<RailGraphPath> path =
          pathFinder.shortestPath(
              graph,
              nodes.get(i),
              nodes.get(i + 1),
              RailGraphPathFinder.Options.shortestDistance());
      if (path.isEmpty()) {
        continue;
      }
      legs[i] = path.get().totalLengthBlocks();
      int j = i + 1;
      entryOk[j] =
          j < nodes.size() - 1
              && graph.findNode(nodes.get(j)).map(CallPlanner::isIntervalWaypoint).orElse(false)
              && path.get().totalLengthBlocks() >= trainLength + BODY_MARGIN_BLOCKS
              && plainTrack(graph, path.get());
    }
    RouteGeometry computed = new RouteGeometry(now, trainLength, legs, entryOk);
    geometries.put(routeId, computed);
    return computed;
  }

  private static boolean isIntervalWaypoint(RailNode node) {
    return node.type() == NodeType.WAYPOINT
        && node.waypointMetadata().map(meta -> meta.kind() == WaypointKind.INTERVAL).orElse(false);
  }

  /** 车身那一段（两端之间）没有道岔、咽喉、车站：生成时车身不会压在道岔上、也不会伸进站台。 */
  private static boolean plainTrack(RailGraph graph, RailGraphPath path) {
    List<NodeId> nodes = path.nodes();
    for (int i = 1; i < nodes.size() - 1; i++) {
      Optional<RailNode> node = graph.findNode(nodes.get(i));
      if (node.isEmpty()) {
        continue;
      }
      NodeType type = node.get().type();
      if (type == NodeType.SWITCHER || type == NodeType.STATION || type == NodeType.DEPOT) {
        return false;
      }
      boolean interval =
          node.get()
              .waypointMetadata()
              .map(meta -> meta.kind() == WaypointKind.INTERVAL)
              .orElse(true);
      if (!interval) {
        return false;
      }
    }
    return true;
  }

  private static boolean playerNearby(World world, Vector position) {
    if (world == null || position == null) {
      return false;
    }
    double limit = ENTRY_PLAYER_RADIUS_BLOCKS * ENTRY_PLAYER_RADIUS_BLOCKS;
    for (Player player : world.getPlayers()) {
      if (player.getLocation().toVector().distanceSquared(position) <= limit) {
        return true;
      }
    }
    return false;
  }

  /** 交路编组的车长（交路写了 spawn_train_pattern 时按它算），否则取保守的默认值。 */
  private long trainLengthBlocks(UUID routeId) {
    return plugin
        .getRouteDefinitionCache()
        .flatMap(cache -> cache.findRecord(routeId))
        .flatMap(record -> DepotSpawnPattern.fromRoute(record.route()))
        .map(SpawnPatternLength::of)
        .filter(OptionalLong::isPresent)
        .map(OptionalLong::getAsLong)
        .orElse(DEFAULT_TRAIN_LENGTH_BLOCKS);
  }

  Optional<WorldGraph> graphOf(RouteDefinition definition) {
    RailGraphService service = plugin.getRailGraphService();
    if (service == null) {
      return Optional.empty();
    }
    return service
        .findNetworkWorldForPath(definition.waypoints())
        .flatMap(
            worldId ->
                service
                    .getNetworkSnapshot(worldId)
                    .map(snapshot -> new WorldGraph(worldId, snapshot.graph())));
  }

  /** 交路的走行时分（与编表同一个模型）：{@code [0]} 各节点到达、{@code [1]} 各节点发车，秒，从首站发车起算。按交路缓存 {@link #TIMING_TTL}。 */
  private Optional<int[][]> timingOf(
      UUID routeId, RouteDefinition definition, List<RouteStop> stops, Optional<WorldGraph> graph) {
    Instant now = Instant.now();
    CachedTiming cached = timings.get(routeId);
    if (cached != null && now.isBefore(cached.computedAt().plus(TIMING_TTL))) {
      return cached.timing();
    }
    Optional<int[][]> computed = graph.flatMap(g -> computeTiming(g, definition, stops));
    timings.put(routeId, new CachedTiming(now, computed));
    return computed;
  }

  private Optional<int[][]> computeTiming(
      WorldGraph graph, RouteDefinition definition, List<RouteStop> stops) {
    try {
      ConfigManager configManager = plugin.getConfigManager();
      if (configManager == null) {
        return Optional.empty();
      }
      RunCurveModel model =
          new RunCurveModel(
              RunCurveModel.Settings.fromConfig(configManager.current(), FALLBACK_SPEED_BPS),
              TimetableEdgeSpeeds.resolver(
                  plugin.getRailGraphService().edgeOverrides(graph.worldId())));
      TimetableTimingCalculator.TimingResult result =
          timingCalculator.compute(graph.graph(), model, definition, stops, DEFAULT_DWELL);
      if (!result.ok() || result.stops().size() != definition.waypoints().size()) {
        return Optional.empty();
      }
      int size = result.stops().size();
      int[][] out = new int[2][size];
      for (int i = 0; i < size; i++) {
        TimetableStop stop = result.stops().get(i);
        out[0][i] = stop.arrivalOffsetSeconds();
        out[1][i] = stop.departureOffsetSeconds();
      }
      return Optional.of(out);
    } catch (RuntimeException ex) {
      return Optional.empty();
    }
  }

  /** 交路改了以后旧的时分与几何不能再用。 */
  void clearTimings() {
    timings.clear();
    geometries.clear();
  }

  /** 交路所在世界与该世界的调度图。 */
  record WorldGraph(UUID worldId, RailGraph graph) {}

  private record CachedTiming(Instant computedAt, Optional<int[][]> timing) {}

  private record RouteGeometry(
      Instant computedAt, long trainLength, long[] legBlocks, boolean[] entryOk) {}
}
