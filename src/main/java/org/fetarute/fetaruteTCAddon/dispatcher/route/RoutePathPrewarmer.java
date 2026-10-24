package org.fetarute.fetaruteTCAddon.dispatcher.route;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService.RailGraphSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.control.EdgeOverrideRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPathFinder;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher.DynamicSpec;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache.RouteEntry;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ExpandedRoutePlans;

/**
 * 交路路径记忆预热：图变了之后，在后台把各交路相邻途经点之间的最短路先算好，填进 {@link RailGraphPathFinder} 的记忆。
 *
 * <p>运行时展开交路路径（占用申请、限速包络、选台、ETA）都按“底层图实例 + 此刻被覆盖封锁的边 + 起讫点”查最短路记忆；换图、 改覆盖或跨世界路网换新之后记忆对不上，
 * 第一轮所有列车都在主线程上同步搜索。预热分两步：
 *
 * <ol>
 *   <li>主线程：取各世界运行时实际查询用的图对象（{@link RailGraphService#runtimeQueryGraph}，与控车同一个底层实例、同一组覆盖封锁）与交路表。
 *   <li>后台：由交路定义静态得出起讫点对（相邻途经点；DYNAMIC 停靠展开成图上实际的候选股道），逐对搜索。
 * </ol>
 *
 * <p>记忆以图实例为键，预热只会填进采集时那张图的表：旧图上的结果天然用不到新图，不需要回主线程复核。图又变了时新一轮预热开始、
 * 旧的一轮在下一对之前停下。预热没赶上的查询照旧在主线程同步搜索，与没有预热时一样；预热结果只是最短路，不是任何放行许可。
 *
 * <p>每张图至多预热记忆容量的一半，给运行时自己的查询（列车当前位置到下一途经点等）留出余量，免得预热把它们挤掉。
 *
 * <p>最短路之后接着预算占用请求的展开计划（{@link ExpandedRoutePlans}）：每条交路从每个途经点到终点（遇到 DYNAMIC 停靠截在它之前，与运行时未选台时
 * 的授权边界一致）各一份，连同整段单线方向判定。列车停在途经点上时，运行时的节点串恰好就是这些，直接命中；不在这上面的（车在两个途经点之间、 DYNAMIC
 * 已选台）照旧在主线程第一次用到时同步算。计划只放进控车用的那张图的缓存（ETA 不用），同样至多占缓存容量的一半。
 */
public final class RoutePathPrewarmer implements AutoCloseable {

  /** 每段最多算多少对：算完一段重新提交，让排在后面的后台任务（如路网重建）插进来。 */
  static final int CHUNK_PAIRS = 64;

  /** 每段最多预算多少份展开计划：一份要把整段剩余交路的单线方向判一遍，比一对最短路贵得多。 */
  static final int CHUNK_PLANS = 4;

  private final RailGraphService graphs;
  private final RouteDefinitionCache routes;
  private final Executor background;
  private final Executor mainThread;
  private final Consumer<String> debugLogger;
  private final Clock clock;
  private final RailGraphPathFinder finder = new RailGraphPathFinder();
  private final AtomicLong generation = new AtomicLong();
  private final AtomicBoolean pending = new AtomicBoolean();
  private volatile boolean closed;

  /**
   * @param graphs 调度图服务
   * @param routes 交路缓存
   * @param background 后台执行器（纯计算，不碰 Bukkit API）
   * @param mainThread 投递到主线程的执行器（采集图与交路表）
   * @param debugLogger 诊断日志
   */
  public RoutePathPrewarmer(
      RailGraphService graphs,
      RouteDefinitionCache routes,
      Executor background,
      Executor mainThread,
      Consumer<String> debugLogger) {
    this(graphs, routes, background, mainThread, debugLogger, Clock.systemUTC());
  }

  RoutePathPrewarmer(
      RailGraphService graphs,
      RouteDefinitionCache routes,
      Executor background,
      Executor mainThread,
      Consumer<String> debugLogger,
      Clock clock) {
    this.graphs = Objects.requireNonNull(graphs, "graphs");
    this.routes = Objects.requireNonNull(routes, "routes");
    this.background = Objects.requireNonNull(background, "background");
    this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
    this.debugLogger = debugLogger != null ? debugLogger : message -> {};
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * 请求一轮预热：可在任意线程调用（图服务的变化回调、交路缓存的变更监听）。
   *
   * <p>连续多次请求合并成一次：投递到主线程后才采集，采集时取的是那一刻最新的图与交路表。
   */
  public void requestPrewarm() {
    if (closed || !pending.compareAndSet(false, true)) {
      return;
    }
    try {
      mainThread.execute(this::prewarmNow);
    } catch (RuntimeException ex) {
      pending.set(false);
      debugLogger.accept("交路路径预热投递失败: " + ex);
    }
  }

  /** 主线程：采集各世界运行时查询用的图与交路表，交给后台；同时让还在跑的上一轮停下。 */
  void prewarmNow() {
    pending.set(false);
    if (closed) {
      return;
    }
    Instant now = clock.instant();
    List<Target> targets = new ArrayList<>();
    for (Map.Entry<UUID, RailGraphSnapshot> entry : graphs.snapshotAll().entrySet()) {
      UUID worldId = entry.getKey();
      RailGraph snapshotGraph = entry.getValue().graph();
      RailGraph query = graphs.runtimeQueryGraph(worldId, now).orElse(null);
      if (query == null) {
        continue;
      }
      int budget = Math.max(1, RailGraphPathFinder.memoCapacityPerGraph() / 2);
      int planBudget = Math.max(1, ExpandedRoutePlans.capacityPerGraph() / 2);
      // ETA 按不叠覆盖的图查：此刻有覆盖封锁时两者的记忆键不同，另预热一份；两份落在同一张表里，分摊预热额度。
      // 展开计划只有控车用，只跟着控车用的那张图预算。
      if (query instanceof EdgeOverrideRailGraph view && !view.overrideBlockedEdges().isEmpty()) {
        int half = Math.max(1, budget / 2);
        targets.add(new Target(worldId, snapshotGraph, query, half, planBudget));
        targets.add(new Target(worldId, snapshotGraph, view.delegate(), half, 0));
      } else {
        targets.add(new Target(worldId, snapshotGraph, query, budget, planBudget));
      }
    }
    Collection<RouteEntry> entries = routes.entries();
    long current = generation.incrementAndGet();
    if (targets.isEmpty() || entries.isEmpty()) {
      return;
    }
    submit(new Job(current, List.copyOf(targets), List.copyOf(entries)));
  }

  /** 停止：不再接受请求，正在跑的一轮在下一对之前停下。不关后台执行器（由创建者关）。 */
  @Override
  public void close() {
    closed = true;
    generation.incrementAndGet();
  }

  private void submit(Job job) {
    try {
      background.execute(job);
    } catch (RejectedExecutionException ex) {
      // 后台执行器已关（插件停用中）：不预热，运行时照旧同步搜索。
    }
  }

  /**
   * 由交路定义静态得出的起讫点对，按运行时查询的口径：相邻途经点原样一对（含 DYNAMIC 占位节点）；DYNAMIC 停靠再展开成图上实际的候选股道， 与前后停靠（或它们的候选）各配一对。
   *
   * <p>两端都要在图上；直接相邻的跳过（展开路径时先查直连边，查不到才搜索，相邻的搜索也很快）。按优先级排：固定途经点之间、 一端是 DYNAMIC 候选、两端都是候选；至多 {@code
   * limit} 对。
   */
  static List<NodePair> collectPairs(Collection<RouteEntry> entries, RailGraph graph, int limit) {
    List<Set<NodePair>> tiers =
        List.of(new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>());
    for (RouteEntry entry : entries) {
      List<NodeId> waypoints = entry.definition().waypoints();
      List<RouteStop> stops = entry.stops();
      List<List<NodeId>> choices = new ArrayList<>(waypoints.size());
      boolean[] dynamic = new boolean[waypoints.size()];
      for (int i = 0; i < waypoints.size(); i++) {
        List<NodeId> candidates =
            i < stops.size() ? dynamicCandidates(stops.get(i), graph) : List.of();
        dynamic[i] = !candidates.isEmpty();
        choices.add(dynamic[i] ? candidates : List.of(waypoints.get(i)));
      }
      for (int i = 0; i + 1 < waypoints.size(); i++) {
        addPair(tiers.get(0), graph, waypoints.get(i), waypoints.get(i + 1));
        int tier = (dynamic[i] ? 1 : 0) + (dynamic[i + 1] ? 1 : 0);
        if (tier == 0) {
          continue;
        }
        for (NodeId from : choices.get(i)) {
          for (NodeId to : choices.get(i + 1)) {
            addPair(tiers.get(tier), graph, from, to);
          }
        }
      }
    }
    LinkedHashSet<NodePair> ordered = new LinkedHashSet<>();
    for (Set<NodePair> tier : tiers) {
      for (NodePair pair : tier) {
        if (ordered.size() >= limit) {
          return List.copyOf(ordered);
        }
        ordered.add(pair);
      }
    }
    return List.copyOf(ordered);
  }

  private static List<NodeId> dynamicCandidates(RouteStop stop, RailGraph graph) {
    if (stop == null) {
      return List.of();
    }
    return DynamicStopMatcher.parseDynamicSpec(stop)
        .map((DynamicSpec spec) -> DynamicStopMatcher.candidateNodes(spec, graph))
        .orElse(List.of());
  }

  private static void addPair(Set<NodePair> into, RailGraph graph, NodeId from, NodeId to) {
    if (from == null || to == null || from.equals(to)) {
      return;
    }
    if (graph.findNode(from).isEmpty() || graph.findNode(to).isEmpty()) {
      return;
    }
    for (RailEdge edge : graph.edgesFrom(from)) {
      if ((edge.from().equals(from) && edge.to().equals(to))
          || (edge.from().equals(to) && edge.to().equals(from))) {
        return;
      }
    }
    into.add(new NodePair(from, to));
  }

  /** 一对起讫点（有方向）。 */
  record NodePair(NodeId from, NodeId to) {}

  /**
   * 要预算展开计划的节点串：每条交路从每个途经点起到终点各一串，按运行时传给占用请求构建器的口径。
   *
   * <p>运行时在第一个未选台的 DYNAMIC 停靠之前截断授权（见调度服务的 DYNAMIC 授权边界），所以起点之后遇到 DYNAMIC 停靠时截在它之前；
   * 截完不足两个途经点的不要（运行时那里直接 fail-closed，用不到计划）。按起点下标从小到大、各交路轮流排： 起点越靠前剩余交路越长，计划越贵，越值得先算；至多 {@code
   * limit} 串，去重。
   */
  static List<List<NodeId>> collectPlanNodes(Collection<RouteEntry> entries, int limit) {
    List<List<List<NodeId>>> perRoute = new ArrayList<>();
    int longest = 0;
    for (RouteEntry entry : entries) {
      List<NodeId> waypoints = entry.definition().waypoints();
      List<RouteStop> stops = entry.stops();
      List<List<NodeId>> suffixes = new ArrayList<>();
      for (int start = 0; start + 1 < waypoints.size(); start++) {
        int end = waypoints.size();
        for (int index = start + 1; index < waypoints.size(); index++) {
          if (index < stops.size() && DynamicStopMatcher.isDynamicStop(stops.get(index))) {
            end = index;
            break;
          }
        }
        if (end - start < 2) {
          continue;
        }
        List<NodeId> suffix = new ArrayList<>(waypoints.subList(start, end));
        if (suffix.stream().anyMatch(Objects::isNull)) {
          continue;
        }
        suffixes.add(List.copyOf(suffix));
      }
      perRoute.add(suffixes);
      longest = Math.max(longest, suffixes.size());
    }
    LinkedHashSet<List<NodeId>> ordered = new LinkedHashSet<>();
    for (int position = 0; position < longest; position++) {
      for (List<List<NodeId>> suffixes : perRoute) {
        if (position >= suffixes.size()) {
          continue;
        }
        if (ordered.size() >= limit) {
          return List.copyOf(ordered);
        }
        ordered.add(suffixes.get(position));
      }
    }
    return List.copyOf(ordered);
  }

  /**
   * 一个世界要预热的图。
   *
   * @param snapshotGraph 采集时这个世界的快照图，用来判断图有没有被换掉
   * @param graph 运行时查询用的图对象（记忆按它的底层实例记）
   * @param limit 至多预热多少对
   * @param planLimit 至多预算多少份展开计划；0 表示这张图不预算
   */
  private record Target(
      UUID worldId, RailGraph snapshotGraph, RailGraph graph, int limit, int planLimit) {}

  /** 一轮预热；同一时刻只有一个线程在跑它（每段跑完才提交下一段）。 */
  private final class Job implements Runnable {
    private final long generation;
    private final List<Target> targets;
    private final List<RouteEntry> entries;
    private int targetIndex;
    private List<NodePair> pairs;
    private int pairIndex;
    private int searched;
    private long startedNanos;
    private boolean pairsDone;
    private List<List<NodeId>> plans;
    private int planIndex;
    private int planned;
    private long planStartedNanos;

    private Job(long generation, List<Target> targets, List<RouteEntry> entries) {
      this.generation = generation;
      this.targets = targets;
      this.entries = entries;
    }

    private boolean cancelled() {
      return closed || RoutePathPrewarmer.this.generation.get() != generation;
    }

    @Override
    public void run() {
      int budget = CHUNK_PAIRS;
      int planBudget = CHUNK_PLANS;
      while (targetIndex < targets.size()) {
        if (cancelled()) {
          return;
        }
        Target target = targets.get(targetIndex);
        if (graphs.getSnapshot(target.worldId()).map(RailGraphSnapshot::graph).orElse(null)
            != target.snapshotGraph()) {
          // 这个世界的图已经换掉：新图的预热由换图触发的下一轮负责。
          nextTarget();
          continue;
        }
        if (pairs == null) {
          pairs = collectPairs(entries, target.graph(), target.limit());
          pairIndex = 0;
          searched = 0;
          startedNanos = System.nanoTime();
        }
        while (pairIndex < pairs.size()) {
          if (budget-- <= 0) {
            submit(this);
            return;
          }
          if (cancelled()) {
            return;
          }
          NodePair pair = pairs.get(pairIndex++);
          if (!RailGraphPathFinder.remembers(target.graph(), pair.from(), pair.to())) {
            searched++;
          }
          finder.shortestPath(
              target.graph(),
              pair.from(),
              pair.to(),
              RailGraphPathFinder.Options.shortestDistance());
        }
        if (!pairsDone) {
          pairsDone = true;
          debugLogger.accept(
              "ROUTE_PATH_PREWARM world="
                  + target.worldId()
                  + " graph="
                  + target.graph().getClass().getSimpleName()
                  + " pairs="
                  + pairs.size()
                  + " searched="
                  + searched
                  + " ms="
                  + (System.nanoTime() - startedNanos) / 1_000_000L);
        }
        if (target.planLimit() > 0) {
          if (plans == null) {
            plans = collectPlanNodes(entries, target.planLimit());
            planIndex = 0;
            planned = 0;
            planStartedNanos = System.nanoTime();
          }
          while (planIndex < plans.size()) {
            if (planBudget-- <= 0) {
              submit(this);
              return;
            }
            if (cancelled()) {
              return;
            }
            if (ExpandedRoutePlans.prewarm(target.graph(), plans.get(planIndex++))) {
              planned++;
            }
          }
          debugLogger.accept(
              "ROUTE_PLAN_PREWARM world="
                  + target.worldId()
                  + " graph="
                  + target.graph().getClass().getSimpleName()
                  + " plans="
                  + plans.size()
                  + " computed="
                  + planned
                  + " ms="
                  + (System.nanoTime() - planStartedNanos) / 1_000_000L);
        }
        nextTarget();
      }
    }

    private void nextTarget() {
      targetIndex++;
      pairs = null;
      pairsDone = false;
      plans = null;
    }
  }
}
