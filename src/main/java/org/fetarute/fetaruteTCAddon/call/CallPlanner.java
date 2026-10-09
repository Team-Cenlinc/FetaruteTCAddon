package org.fetarute.fetaruteTCAddon.call;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.RunCurveModel;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPath;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPathFinder;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLifecycleMode;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TerminalKeyResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.DepotSpawnPattern;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.OnDemandTrip;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnPatternLength;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableEdgeSpeeds;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableStop;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableTimingCalculator;

/**
 * 叫车派哪辆车、多久能到。
 *
 * <p>三种车源取预计最快到站的：首站的待命车（折返复用）、本站上游的区间点直接生成、首站是车库的从车库出车。 待命车比最快的慢不到 {@link #STANDBY_PREFERENCE}
 * 时仍用它——车已经在网里，不必再造一辆。回库交路只区间生成（{@link CallCatalog.Origin#ENTRY}）。
 *
 * <p>区间生成沿交路实际走的图路径往上游找：交路节点表里多是车站，信号用的区间点只在图上。生成点离本站的轨道距离不少于 {@link #ENTRY_MIN_DISTANCE_BLOCKS}，附近
 * {@link #ENTRY_PLAYER_RADIUS_BLOCKS} 格内有玩家就换更远的点；车身铺在生成点后方，那一段（车长加余量） 不能有道岔、咽喉、车站。
 */
final class CallPlanner {

  /** 车源。 */
  enum Source {
    /** 首站的待命车。 */
    STANDBY,
    /** 本站上游的区间点生成。 */
    WAYPOINT,
    /** 从车库出车。 */
    DEPOT,
    /** 折返车：先开进叫车交路的首站（终点）再折返。 */
    TURNBACK
  }

  /**
   * 折返车：在开往叫车交路首站这个终点、终点进待命池的同线路交路上区间生成，开进终点后由这一单的票接走。
   *
   * @param routeId 开进终点的交路
   * @param entry 在那条交路上的生成点
   * @param departSeconds 预计几秒后从终点折返开出；估不出时为空
   */
  record Turnback(UUID routeId, Entry entry, OptionalInt departSeconds) {
    Turnback {
      Objects.requireNonNull(routeId, "routeId");
      Objects.requireNonNull(entry, "entry");
      Objects.requireNonNull(departSeconds, "departSeconds");
    }
  }

  /**
   * 区间生成点。
   *
   * @param index 生成点所在的交路区段：生成点就是交路节点 {@code index}，或在节点 {@code index} 与下一个节点之间
   * @param node 生成点（图上的区间点）
   * @param offsetBlocks 生成点离交路节点 {@code index} 的轨道距离
   * @param legBlocks 节点 {@code index} 到下一个节点的轨道距离
   */
  record Entry(int index, NodeId node, long offsetBlocks, long legBlocks) {
    Entry {
      Objects.requireNonNull(node, "node");
    }

    /** 写进叫车票的入路。 */
    OnDemandTrip.Entry ticketEntry() {
      return new OnDemandTrip.Entry(index, Optional.of(node));
    }
  }

  /**
   * 一趟叫车的安排。
   *
   * @param routeId 跑哪条交路
   * @param stopIndex 本站在交路里的下标
   * @param source 车源
   * @param entry 区间生成点（车源为 {@link Source#WAYPOINT} 时）
   * @param etaSeconds 预计多少秒后到站；估不出时为空
   * @param turnback 折返车（车源为 {@link Source#TURNBACK} 时）
   */
  record Plan(
      UUID routeId,
      int stopIndex,
      Source source,
      Optional<Entry> entry,
      OptionalInt etaSeconds,
      Optional<Turnback> turnback) {

    Plan(
        UUID routeId, int stopIndex, Source source, Optional<Entry> entry, OptionalInt etaSeconds) {
      this(routeId, stopIndex, source, entry, etaSeconds, Optional.empty());
    }

    OptionalInt etaMinutes() {
      return etaSeconds.isPresent()
          ? OptionalInt.of(Math.max(1, (etaSeconds.getAsInt() + 59) / 60))
          : OptionalInt.empty();
    }
  }

  /** 排车源时现场的约束：哪些待命车能接、哪些交路此刻不该从车库出车。 */
  interface Constraints {

    /** 这辆首站待命车能不能接这条交路的叫车。 */
    boolean acceptsStandby(UUID routeId, LayoverRegistry.LayoverCandidate candidate);

    /** 这条交路的车库此刻要让给按表出库的车（不从车库出车）。 */
    boolean depotYields(UUID routeId, Instant now);

    /** 不加约束：待命车都能接，车库不让。 */
    Constraints NONE =
        new Constraints() {
          @Override
          public boolean acceptsStandby(UUID routeId, LayoverRegistry.LayoverCandidate candidate) {
            return true;
          }

          @Override
          public boolean depotYields(UUID routeId, Instant now) {
            return false;
          }
        };
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
  static final int BODY_MARGIN_BLOCKS = 4;
  private static final int STANDBY_OVERHEAD_SECONDS = 15;
  private static final int ENTRY_OVERHEAD_SECONDS = 20;
  private static final int DEPOT_OVERHEAD_SECONDS = 40;

  /** 折返车开进终点到再开出：停站、换端、派票。 */
  static final int TURNBACK_OVERHEAD_SECONDS = 45;

  private static final double FALLBACK_SPEED_BPS = 8.0D;
  private static final Duration TIMING_TTL = Duration.ofMinutes(10);
  private static final Duration DEFAULT_DWELL = Duration.ofSeconds(20);

  private final FetaruteTCAddon plugin;
  private final Map<UUID, CachedTiming> timings = new ConcurrentHashMap<>();
  private final Map<UUID, RouteGeometry> geometries = new ConcurrentHashMap<>();

  /** 各交路的车长（格）：量车长要把编组方案连同各车厢的存档整个解析一遍，站台屏每次判定都要用，按交路记住。 */
  private final Map<UUID, Long> trainLengths = new ConcurrentHashMap<>();

  private final TimetableTimingCalculator timingCalculator = new TimetableTimingCalculator();
  private final RailGraphPathFinder pathFinder = new RailGraphPathFinder();

  CallPlanner(FetaruteTCAddon plugin) {
    this.plugin = plugin;
  }

  /**
   * 为一个方向排一趟车。
   *
   * @param direction 方向
   * @param constraints 现场约束（待命车能不能接、车库让不让）
   * @param now 当前时刻
   * @return 安排；没有车可派时为空
   */
  Optional<Plan> plan(CallCatalog.CallDirection direction, Constraints constraints, Instant now) {
    Optional<RouteDefinitionCache> cacheOpt = plugin.getRouteDefinitionCache();
    if (cacheOpt.isEmpty()) {
      return Optional.empty();
    }
    Constraints rules = constraints == null ? Constraints.NONE : constraints;
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
      if (route.origin() == CallCatalog.Origin.STANDBY) {
        OptionalLong readyIn = standbyReadyIn(route, rules, now);
        if (readyIn.isPresent()) {
          OptionalInt eta =
              timing
                  .map(
                      t ->
                          OptionalInt.of(
                              t[0][stop] + (int) readyIn.getAsLong() + STANDBY_OVERHEAD_SECONDS))
                  .orElse(OptionalInt.empty());
          Plan plan = new Plan(route.routeId(), stop, Source.STANDBY, Optional.empty(), eta);
          bestStandby = faster(bestStandby, plan);
          best = faster(best, plan);
        }
      } else if (route.origin() == CallCatalog.Origin.DEPOT
          && !rules.depotYields(route.routeId(), now)) {
        OptionalInt eta =
            timing
                .map(t -> OptionalInt.of(t[0][stop] + DEPOT_OVERHEAD_SECONDS))
                .orElse(OptionalInt.empty());
        best = faster(best, new Plan(route.routeId(), stop, Source.DEPOT, Optional.empty(), eta));
      }
      if (graph.isPresent()) {
        Optional<Entry> entry =
            pickEntry(
                route.routeId(),
                definition,
                stops,
                stop,
                graph.get(),
                trainLengthBlocks(route.routeId()));
        if (entry.isPresent()) {
          OptionalInt eta =
              timing
                  .map(t -> OptionalInt.of(entryEtaSeconds(t, stop, entry.get())))
                  .orElse(OptionalInt.empty());
          best = faster(best, new Plan(route.routeId(), stop, Source.WAYPOINT, entry, eta));
        }
      }
      if (route.origin() != CallCatalog.Origin.DEPOT) {
        Optional<Plan> turnback = turnbackPlan(cache, route, timing);
        if (turnback.isPresent()) {
          best = faster(best, turnback.get());
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

  /**
   * 折返车：在开往叫车交路首站的交路上区间生成，开进终点再折返。取预计最快的那条交路与生成点；没有能开进来的交路、或它们上游都生成不了车时为空。
   *
   * @param timing 叫车交路的走行时分
   */
  private Optional<Plan> turnbackPlan(
      RouteDefinitionCache cache, CallCatalog.CallRoute route, Optional<int[][]> timing) {
    Plan best = null;
    for (RouteDefinitionCache.RouteEntry inbound :
        inboundRoutes(cache.entries(), route.routeId())) {
      RouteDefinition definition = inbound.definition();
      Optional<WorldGraph> graph = graphOf(definition);
      if (graph.isEmpty()) {
        continue;
      }
      int terminal = definition.waypoints().size() - 1;
      Optional<Entry> entry =
          pickEntry(
              inbound.routeId(),
              definition,
              inbound.stops(),
              terminal,
              graph.get(),
              trainLengthBlocks(inbound.routeId()));
      if (entry.isEmpty()) {
        continue;
      }
      Optional<int[][]> inboundTiming =
          timingOf(inbound.routeId(), definition, inbound.stops(), graph);
      OptionalInt depart =
          inboundTiming.isPresent()
              ? OptionalInt.of(
                  entryEtaSeconds(inboundTiming.get(), terminal, entry.get())
                      + TURNBACK_OVERHEAD_SECONDS)
              : OptionalInt.empty();
      OptionalInt eta =
          depart.isPresent() && timing.isPresent()
              ? OptionalInt.of(depart.getAsInt() + timing.get()[0][route.stopIndex()])
              : OptionalInt.empty();
      best =
          faster(
              best,
              new Plan(
                  route.routeId(),
                  route.stopIndex(),
                  Source.TURNBACK,
                  Optional.empty(),
                  eta,
                  Optional.of(new Turnback(inbound.routeId(), entry.get(), depart))));
    }
    return Optional.ofNullable(best);
  }

  /**
   * 能当折返车开进叫车交路首站的交路：同一线路、终点进待命池（不是开进车库或终点销毁的）、终点就是叫车交路首站所在的车站（同站别的站台、 DYNAMIC 写法都算）。
   *
   * @param entries 交路缓存的全部交路
   * @param callRouteId 叫车跑的交路
   */
  static List<RouteDefinitionCache.RouteEntry> inboundRoutes(
      java.util.Collection<RouteDefinitionCache.RouteEntry> entries, UUID callRouteId) {
    if (entries == null || callRouteId == null) {
      return List.of();
    }
    RouteDefinitionCache.RouteEntry call = null;
    for (RouteDefinitionCache.RouteEntry entry : entries) {
      if (entry != null && callRouteId.equals(entry.routeId())) {
        call = entry;
        break;
      }
    }
    if (call == null || call.definition().waypoints().isEmpty() || call.stops().isEmpty()) {
      return List.of();
    }
    NodeId start = call.definition().waypoints().get(0);
    RouteStop startStop = call.stops().get(0);
    List<RouteDefinitionCache.RouteEntry> out = new ArrayList<>();
    for (RouteDefinitionCache.RouteEntry entry : entries) {
      if (entry == null
          || callRouteId.equals(entry.routeId())
          || !call.record().line().id().equals(entry.record().line().id())
          || entry.definition().lifecycleMode() != RouteLifecycleMode.REUSE_AT_TERM
          || entry.definition().waypoints().size() < 2) {
        continue;
      }
      List<NodeId> waypoints = entry.definition().waypoints();
      NodeId terminal = waypoints.get(waypoints.size() - 1);
      if (DynamicStopMatcher.matchesStop(terminal, startStop)
          || TerminalKeyResolver.matches(
              TerminalKeyResolver.toTerminalKey(terminal),
              TerminalKeyResolver.toTerminalKey(start))) {
        out.add(entry);
      }
    }
    return out;
  }

  /** 叫车交路能不能靠折返车出车：有交路能开进它的首站、上游又生成得了车（只看轨道几何，交路校验用）。 */
  boolean turnbackPossible(UUID routeId) {
    Optional<RouteDefinitionCache> cache = plugin.getRouteDefinitionCache();
    if (cache.isEmpty()) {
      return false;
    }
    for (RouteDefinitionCache.RouteEntry inbound : inboundRoutes(cache.get().entries(), routeId)) {
      if (entryPossible(inbound.routeId(), inbound.definition().waypoints().size() - 1)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 区间生成的车几秒后到站：从生成点起步的时刻按所在区段的轨道距离比例插值（车停着起步，加减速的出入由固定开销兜住）。
   *
   * @param timing {@code [0]} 各节点到达、{@code [1]} 各节点发车
   */
  static int entryEtaSeconds(int[][] timing, int stop, Entry entry) {
    int from = timing[1][entry.index()];
    if (entry.offsetBlocks() > 0L
        && entry.legBlocks() > 0L
        && entry.index() + 1 < timing[0].length) {
      double fraction = Math.min(1.0D, (double) entry.offsetBlocks() / entry.legBlocks());
      from += (int) Math.round((timing[0][entry.index() + 1] - from) * fraction);
    }
    return Math.max(0, timing[0][stop] - from) + ENTRY_OVERHEAD_SECONDS;
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
  private OptionalLong standbyReadyIn(
      CallCatalog.CallRoute route, Constraints constraints, Instant now) {
    Optional<LayoverRegistry> registry = plugin.getLayoverRegistry();
    if (registry.isEmpty()) {
      return OptionalLong.empty();
    }
    long best = Long.MAX_VALUE;
    for (LayoverRegistry.LayoverCandidate candidate :
        registry.get().findCandidates(route.startNode())) {
      if (candidate == null
          || candidate.dispatchAttempt().isPresent()
          || !constraints.acceptsStandby(route.routeId(), candidate)) {
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
   * 本站上游的区间生成点：沿交路走的图路径从本站往回找，取第一个离本站够远、附近没有玩家、没被占用、车身那一段放得下又没有道岔的区间点。
   *
   * <p>轨道几何（各点到本站的距离、车身段是否平直）按交路缓存，这里只现查占用与附近玩家。
   *
   * @return 生成点；找不到时为空
   */
  Optional<Entry> pickEntry(
      UUID routeId,
      RouteDefinition definition,
      List<RouteStop> stops,
      int stopIndex,
      WorldGraph worldGraph,
      long trainLength) {
    RouteGeometry geometry = geometryOf(routeId, definition, stops, worldGraph, trainLength);
    World world = Bukkit.getWorld(worldGraph.worldId());
    OccupancyManager occupancy = plugin.getOccupancyManager();
    for (PathPoint point : geometry.candidatesFor(stopIndex)) {
      if (occupancy != null && occupancy.isNodeOccupied(point.node())) {
        continue;
      }
      Optional<Vector> position =
          worldGraph.graph().findNode(point.node()).map(RailNode::worldPosition);
      if (position.isEmpty() || playerNearby(world, position.get())) {
        continue;
      }
      return Optional.of(geometry.entryAt(point));
    }
    return Optional.empty();
  }

  /**
   * 本站上游有没有能生成车的区间点：只看轨道几何（离本站够远、车身那一段放得下又没有道岔），不看此刻的占用与附近玩家（交路校验用）。
   *
   * @return 交路或调度图查不到时为 false
   */
  boolean entryPossible(UUID routeId, int stopIndex) {
    Optional<RouteDefinitionCache> cache = plugin.getRouteDefinitionCache();
    Optional<RouteDefinition> definition = cache.flatMap(c -> c.findById(routeId));
    Optional<WorldGraph> graph = definition.flatMap(this::graphOf);
    if (definition.isEmpty() || graph.isEmpty()) {
      return false;
    }
    List<RouteStop> stops = cache.get().listStops(definition.get().id());
    return !geometryOf(routeId, definition.get(), stops, graph.get(), trainLengthBlocks(routeId))
        .candidatesFor(stopIndex)
        .isEmpty();
  }

  /** 交路的轨道几何（沿图路径），按交路与车长缓存 {@link #TIMING_TTL}。 */
  private RouteGeometry geometryOf(
      UUID routeId,
      RouteDefinition definition,
      List<RouteStop> stops,
      WorldGraph worldGraph,
      long trainLength) {
    Instant now = Instant.now();
    RouteGeometry cached = geometries.get(routeId);
    if (cached != null
        && cached.trainLength() == trainLength
        && now.isBefore(cached.computedAt().plus(TIMING_TTL))) {
      return cached;
    }
    RouteGeometry computed =
        RouteGeometry.build(
            now, trainLength, definition.waypoints(), stops, worldGraph.graph(), pathFinder);
    geometries.put(routeId, computed);
    return computed;
  }

  /**
   * 交路走的图路径上的一个点。
   *
   * @param node 图节点
   * @param segment 所在区段：是交路节点 {@code segment} 本身，或在它与下一个交路节点之间
   * @param routeNode 是交路节点本身
   * @param distance 从交路首个节点起的轨道距离
   * @param entryOk 能当生成点：是区间点、所在区段可以从中途起跑、身后车长加余量那一段只有区间点
   */
  record PathPoint(NodeId node, int segment, boolean routeNode, long distance, boolean entryOk) {}

  /**
   * 交路的轨道几何：沿各段最短路展开的图路径。
   *
   * @param points 路径上的点，按行车顺序
   * @param routeNodePositions 交路节点 {@code j} 在 {@code points} 里的位置；路径到不了它时为 -1
   * @param legBlocks 交路节点 {@code j} 到下一个节点的轨道距离；不可达为 -1
   */
  record RouteGeometry(
      Instant computedAt,
      long trainLength,
      List<PathPoint> points,
      int[] routeNodePositions,
      long[] legBlocks) {

    /**
     * 沿各段最短路展开交路，标出能当生成点的点。
     *
     * <p>生成点要求：
     *
     * <ul>
     *   <li>是区间点（{@code INTERVAL}）；
     *   <li>所在区段的起点不是交路首个节点（从首站起跑的车走首站的车源），区段两端都不是 DYNAMIC 停靠——运行时按交路节点之间的最短路认车的位置， DYNAMIC
     *       选台后路径可能不经过它；
     *   <li>身后车长加 {@link #BODY_MARGIN_BLOCKS} 格那一段只有区间点（没有道岔、咽喉、车站、车库），也没有越过交路首个节点。
     * </ul>
     */
    static RouteGeometry build(
        Instant now,
        long trainLength,
        List<NodeId> nodes,
        List<RouteStop> stops,
        RailGraph graph,
        RailGraphPathFinder pathFinder) {
      int size = nodes.size();
      int[] positions = new int[size];
      long[] legs = new long[size];
      java.util.Arrays.fill(positions, -1);
      java.util.Arrays.fill(legs, -1L);
      List<NodeId> pathNodes = new ArrayList<>();
      List<Integer> segments = new ArrayList<>();
      List<Boolean> routeNodes = new ArrayList<>();
      List<Long> distances = new ArrayList<>();
      if (size == 0) {
        return new RouteGeometry(now, trainLength, List.of(), positions, legs);
      }
      pathNodes.add(nodes.get(0));
      segments.add(0);
      routeNodes.add(true);
      distances.add(0L);
      positions[0] = 0;
      for (int i = 0; i + 1 < size; i++) {
        Optional<RailGraphPath> path =
            pathFinder.shortestPath(
                graph,
                nodes.get(i),
                nodes.get(i + 1),
                RailGraphPathFinder.Options.shortestDistance());
        if (path.isEmpty()) {
          break;
        }
        legs[i] = path.get().totalLengthBlocks();
        long base = distances.get(distances.size() - 1);
        List<NodeId> legNodes = path.get().nodes();
        List<RailEdge> legEdges = path.get().edges();
        long walked = 0L;
        for (int k = 1; k < legNodes.size(); k++) {
          walked += Math.max(0, legEdges.get(k - 1).lengthBlocks());
          boolean last = k == legNodes.size() - 1;
          pathNodes.add(legNodes.get(k));
          segments.add(last ? i + 1 : i);
          routeNodes.add(last);
          distances.add(base + (last ? legs[i] : walked));
        }
        positions[i + 1] = pathNodes.size() - 1;
      }
      boolean[] plain = new boolean[pathNodes.size()];
      for (int k = 0; k < pathNodes.size(); k++) {
        plain[k] =
            graph.findNode(pathNodes.get(k)).map(RouteGeometry::isIntervalWaypoint).orElse(false);
      }
      long body = trainLength + BODY_MARGIN_BLOCKS;
      List<PathPoint> points = new ArrayList<>(pathNodes.size());
      for (int k = 0; k < pathNodes.size(); k++) {
        int segment = segments.get(k);
        boolean routeNode = routeNodes.get(k);
        boolean ok =
            plain[k]
                && segment >= 1
                && segment < size - 1
                && (routeNode || !dynamicAt(stops, segment) && !dynamicAt(stops, segment + 1))
                && distances.get(k) >= body
                && bodyPlain(plain, distances, k, body);
        points.add(new PathPoint(pathNodes.get(k), segment, routeNode, distances.get(k), ok));
      }
      return new RouteGeometry(now, trainLength, List.copyOf(points), positions, legs);
    }

    /** 点 {@code k} 身后 {@code body} 格以内（不含它自己）的点都是区间点。 */
    private static boolean bodyPlain(boolean[] plain, List<Long> distances, int k, long body) {
      long head = distances.get(k);
      for (int m = k - 1; m >= 0 && head - distances.get(m) <= body; m--) {
        if (!plain[m]) {
          return false;
        }
      }
      return true;
    }

    private static boolean dynamicAt(List<RouteStop> stops, int index) {
      return stops != null
          && index >= 0
          && index < stops.size()
          && DynamicStopMatcher.isDynamicStop(stops.get(index));
    }

    private static boolean isIntervalWaypoint(RailNode node) {
      return node.type() == NodeType.WAYPOINT
          && node.waypointMetadata()
              .map(meta -> meta.kind() == WaypointKind.INTERVAL)
              .orElse(false);
    }

    /** 本站上游能当生成点的点，离本站由近到远（至少 {@link #ENTRY_MIN_DISTANCE_BLOCKS} 格）。 */
    List<PathPoint> candidatesFor(int stopIndex) {
      if (stopIndex < 0 || stopIndex >= routeNodePositions.length) {
        return List.of();
      }
      int stopPosition = routeNodePositions[stopIndex];
      if (stopPosition < 0) {
        return List.of();
      }
      long stopDistance = points.get(stopPosition).distance();
      List<PathPoint> out = new ArrayList<>();
      for (int k = stopPosition - 1; k >= 0; k--) {
        PathPoint point = points.get(k);
        if (point.entryOk() && stopDistance - point.distance() >= ENTRY_MIN_DISTANCE_BLOCKS) {
          out.add(point);
        }
      }
      return out;
    }

    /** 生成点写成交路区段与偏移。 */
    Entry entryAt(PathPoint point) {
      int segment = point.segment();
      long offset =
          point.routeNode()
              ? 0L
              : point.distance() - points.get(routeNodePositions[segment]).distance();
      return new Entry(segment, point.node(), offset, legBlocks[segment]);
    }
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

  /**
   * 交路编组的车长（交路写了 spawn_train_pattern 时按它算），否则取保守的默认值。按交路记住，交路改了随 {@link #clearTimings} 作废；
   * 写了编组却量不出来（TrainCarts 还没读入存档车）时不记，下次再量。
   */
  long trainLengthBlocks(UUID routeId) {
    Long known = trainLengths.get(routeId);
    if (known != null) {
      return known;
    }
    Optional<String> pattern =
        plugin
            .getRouteDefinitionCache()
            .flatMap(cache -> cache.findRecord(routeId))
            .flatMap(record -> DepotSpawnPattern.fromRoute(record.route()));
    OptionalLong measured = pattern.map(SpawnPatternLength::of).orElse(OptionalLong.empty());
    long length = measured.orElse(DEFAULT_TRAIN_LENGTH_BLOCKS);
    if (pattern.isEmpty() || measured.isPresent()) {
      trainLengths.put(routeId, length);
    }
    return length;
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

  /** 交路改了以后旧的时分、几何与车长不能再用。 */
  void clearTimings() {
    timings.clear();
    geometries.clear();
    trainLengths.clear();
  }

  /** 交路所在世界与该世界的调度图。 */
  record WorldGraph(UUID worldId, RailGraph graph) {}

  private record CachedTiming(Instant computedAt, Optional<int[][]> timing) {}
}
