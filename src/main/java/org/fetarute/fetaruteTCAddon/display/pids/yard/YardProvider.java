package org.fetarute.fetaruteTCAddon.display.pids.yard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphInterlockingSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingState;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceKind;
import org.fetarute.fetaruteTCAddon.display.Lateness;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout;

/**
 * 站场图的数据：几何按调度图快照缓存，占用与列车位置每次现取。
 *
 * <ul>
 *   <li>几何只在调度图换了（重建、刷新、增补）之后重算，交给 {@code builder} 在后台算；算好之前照旧显示上一张图，第一次算时显示“正在绘制”。
 *       同一世界的各站共用一份按区间整理好的轨道方块。图换了之后，旧图的几何（连同它拖着的整张旧图）在下一次重算时清掉。
 *   <li>占用只认区间（EDGE）资源：MOVEMENT_REQUIRED 记为进路锁闭；PROTECTIVE_RETAIN、HOLD_ONLY 与 PHYSICAL_FOOTPRINT
 *       记为保护占用， 两者都有时按保护占用（还没出清）。排队位、前瞻预览与解锁预约不画。
 *   <li>车身按各节车厢所在的轨道方块定位，比按占用推算准；只画落在站场图上的车厢，车次窗信息也只查这些车。
 *   <li>全网占用整理成的区间标记与每个世界的列车位置在 {@code shareNanos} 内由各块屏幕共用，几块屏同时在看时不必各取一遍。
 * </ul>
 *
 * <p>只读：不改占用、不碰列车。{@link #picture} 在主线程调用（列车位置要读 TrainCarts）。
 */
public final class YardProvider implements PidsYardSource {

  private final Function<UUID, Optional<RailGraph>> graphs;
  private final Supplier<List<OccupancyClaim>> claims;
  private final Function<UUID, List<YardTrain>> trains;
  private final Function<String, Optional<String>> stationNames;
  private final Executor builder;
  private final LongSupplier nanos;
  private final long shareNanos;

  private final Map<Key, Cached> cache = new ConcurrentHashMap<>();
  private final Map<Key, RailGraph> building = new ConcurrentHashMap<>();
  private final Map<UUID, Footprints> footprints = new ConcurrentHashMap<>();
  private final Map<UUID, Shared<List<YardTrain>>> sharedTrains = new ConcurrentHashMap<>();
  private volatile Shared<Map<String, YardPicture.Mark>> sharedMarks;

  /**
   * 几何当场算、占用与列车每次现取（测试用）。
   *
   * @param graphs 世界 → 当前调度图快照
   * @param claims 当前全部占用
   * @param trains 世界 → 其中已加载的调度列车
   * @param stationNames {@code 运营商:站码} → 站名（去向用）
   */
  public YardProvider(
      Function<UUID, Optional<RailGraph>> graphs,
      Supplier<List<OccupancyClaim>> claims,
      Function<UUID, List<YardTrain>> trains,
      Function<String, Optional<String>> stationNames) {
    this(graphs, claims, trains, stationNames, Runnable::run, System::nanoTime, 0L);
  }

  /**
   * @param graphs 世界 → 当前调度图快照
   * @param claims 当前全部占用
   * @param trains 世界 → 其中已加载的调度列车
   * @param stationNames {@code 运营商:站码} → 站名（去向用），后台线程里也会调用
   * @param builder 算几何的执行器（插件的异步任务）
   * @param nanos 单调时钟（纳秒）
   * @param shareNanos 占用标记与列车位置在多长时间内由各块屏幕共用；0 为每次现取
   */
  public YardProvider(
      Function<UUID, Optional<RailGraph>> graphs,
      Supplier<List<OccupancyClaim>> claims,
      Function<UUID, List<YardTrain>> trains,
      Function<String, Optional<String>> stationNames,
      Executor builder,
      LongSupplier nanos,
      long shareNanos) {
    this.graphs = Objects.requireNonNull(graphs, "graphs");
    this.claims = Objects.requireNonNull(claims, "claims");
    this.trains = Objects.requireNonNull(trains, "trains");
    this.stationNames = Objects.requireNonNull(stationNames, "stationNames");
    this.builder = Objects.requireNonNull(builder, "builder");
    this.nanos = Objects.requireNonNull(nanos, "nanos");
    this.shareNanos = Math.max(0L, shareNanos);
  }

  private record Key(UUID worldId, YardScope scope, YardGeometry.Rect rect) {}

  /**
   * @param graph 算几何用的图快照（按对象身份判断换没换）
   * @param geometry 几何；图里没有本站（控制区成员）时为空
   * @param edges 占用资源键 → 区间，只含站场图上画了的区间
   */
  private record Cached(
      RailGraph graph, Optional<YardGeometry> geometry, Map<String, EdgeId> edges) {}

  private record Footprints(RailGraph graph, Map<EdgeId, Set<RailFootprintCell>> cells) {}

  /** 取到的时刻（纳秒）与值。 */
  private record Shared<T>(long at, T value) {}

  @Override
  public YardPicture picture(YardScope scope, UUID worldId, PidsLayout.Yard widget) {
    Optional<RailGraph> graph = graphs.apply(worldId);
    if (graph.isEmpty()) {
      return YardPicture.unavailable(YardPicture.Problem.GRAPH_NOT_READY);
    }
    YardGeometry.Rect rect =
        new YardGeometry.Rect(
            widget.trackLeft(), widget.trackTop(), widget.trackWidth(), widget.trackHeight());
    Key key = new Key(worldId, scope, rect);
    Cached cached = cache.get(key);
    if (cached == null || cached.graph() != graph.get()) {
      schedule(key, graph.get());
      cached = cache.get(key);
      if (cached == null) {
        return YardPicture.unavailable(YardPicture.Problem.BUILDING);
      }
    }
    if (cached.geometry().isEmpty()) {
      return YardPicture.unavailable(YardPicture.Problem.NOT_IN_GRAPH);
    }
    YardGeometry geometry = cached.geometry().get();
    return new YardPicture(
        cached.geometry(),
        marks(cached.edges(), edgeMarks()),
        trainMarks(geometry, trainsIn(worldId)),
        Optional.empty());
  }

  /** 交给后台算这张图的几何；同一张图已经在算就不再排。顺手清掉旧图的几何与轨道方块（正在重算的除外，算好前还要显示它）。 */
  private void schedule(Key key, RailGraph graph) {
    if (building.get(key) == graph) {
      return;
    }
    building.put(key, graph);
    cache
        .entrySet()
        .removeIf(
            entry ->
                !building.containsKey(entry.getKey())
                    && entry.getValue().graph() != current(entry.getKey().worldId()));
    footprints.entrySet().removeIf(entry -> entry.getValue().graph() != current(entry.getKey()));
    builder.execute(
        () -> {
          try {
            cache.put(key, build(graph, key));
          } finally {
            building.remove(key, graph);
          }
        });
  }

  private RailGraph current(UUID worldId) {
    return graphs.apply(worldId).orElse(null);
  }

  private Cached build(RailGraph graph, Key key) {
    YardScope scope = key.scope();
    Optional<YardArea> area = YardArea.of(graph, scope.operator(), scope.members());
    if (area.isEmpty()) {
      return new Cached(graph, Optional.empty(), Map.of());
    }
    YardGeometry geometry =
        YardGeometry.of(
            graph,
            area.get(),
            footprintsOf(key.worldId(), graph),
            key.rect(),
            code -> stationNames.apply(scope.operator() + ":" + code).orElse(code));
    Map<String, EdgeId> edges = new HashMap<>();
    for (YardGeometry.Path path : geometry.paths()) {
      edges.put(OccupancyResource.forEdge(path.edge()).key(), path.edge());
    }
    return new Cached(graph, Optional.of(geometry), Map.copyOf(edges));
  }

  /** 同一世界的各站共用：按区间整理轨道方块要扫一遍全图的方块索引（结果本身不可变）。 */
  private Map<EdgeId, Set<RailFootprintCell>> footprintsOf(UUID worldId, RailGraph graph) {
    Footprints known = footprints.get(worldId);
    if (known != null && known.graph() == graph) {
      return known.cells();
    }
    Map<EdgeId, Set<RailFootprintCell>> cells = Map.of();
    if (graph instanceof RailGraphInterlockingSupport support) {
      RailInterlockingState state = support.interlockingState();
      if (state.cellCoverageAvailable()) {
        cells = state.footprintCellsByEdge();
      }
    }
    footprints.put(worldId, new Footprints(graph, cells));
    return cells;
  }

  /** 全网区间占用整理成的标记，在共用时间内各屏共用一份。 */
  private Map<String, YardPicture.Mark> edgeMarks() {
    long now = nanos.getAsLong();
    Shared<Map<String, YardPicture.Mark>> held = sharedMarks;
    if (held == null || now - held.at() >= shareNanos) {
      held = new Shared<>(now, edgeMarks(claims.get()));
      sharedMarks = held;
    }
    return held.value();
  }

  /** 世界里的列车位置，在共用时间内各屏共用一份。 */
  private List<YardTrain> trainsIn(UUID worldId) {
    long now = nanos.getAsLong();
    Shared<List<YardTrain>> held = sharedTrains.get(worldId);
    if (held == null || now - held.at() >= shareNanos) {
      held = new Shared<>(now, List.copyOf(trains.apply(worldId)));
      sharedTrains.put(worldId, held);
    }
    return held.value();
  }

  /** 占用资源键 → 区间标记：只认区间资源，保护占用压过进路锁闭。 */
  static Map<String, YardPicture.Mark> edgeMarks(List<OccupancyClaim> claims) {
    Map<String, YardPicture.Mark> marks = new HashMap<>();
    for (OccupancyClaim claim : claims) {
      if (claim.resource().kind() != ResourceKind.EDGE) {
        continue;
      }
      Optional<YardPicture.Mark> mark =
          switch (claim.role()) {
            case MOVEMENT_REQUIRED -> Optional.of(YardPicture.Mark.ROUTE);
            case PROTECTIVE_RETAIN, HOLD_ONLY, PHYSICAL_FOOTPRINT -> Optional.of(
                YardPicture.Mark.HELD);
            case QUEUE_POSITION, LOOKAHEAD_PREVIEW, UNLOCK_RESERVATION -> Optional.empty();
          };
      mark.ifPresent(
          found ->
              marks.merge(
                  claim.resource().key(),
                  found,
                  (a, b) ->
                      a == YardPicture.Mark.HELD || b == YardPicture.Mark.HELD
                          ? YardPicture.Mark.HELD
                          : YardPicture.Mark.ROUTE));
    }
    return marks;
  }

  /** 站场图上各区间的占用标记，按区间排序。 */
  static List<YardPicture.EdgeMark> marks(
      Map<String, EdgeId> edges, Map<String, YardPicture.Mark> index) {
    Map<EdgeId, YardPicture.Mark> marks = new TreeMap<>();
    edges.forEach(
        (key, edge) -> {
          YardPicture.Mark mark = index.get(key);
          if (mark != null) {
            marks.put(edge, mark);
          }
        });
    List<YardPicture.EdgeMark> out = new ArrayList<>(marks.size());
    marks.forEach((edge, mark) -> out.add(new YardPicture.EdgeMark(edge, mark)));
    return out;
  }

  /** 落在站场图上的列车：连续在图上的车厢为一段，相邻车厢落在同一像素时只记一次；车次窗信息只查这些车。 */
  static List<YardPicture.TrainMark> trainMarks(YardGeometry geometry, List<YardTrain> trains) {
    List<YardTrainEntry> found = new ArrayList<>();
    for (YardTrain train : trains) {
      List<List<YardGeometry.Point>> runs = new ArrayList<>();
      List<YardGeometry.Point> run = new ArrayList<>();
      for (RailFootprintCell cell : train.cars()) {
        Optional<YardGeometry.Point> point = geometry.pointOf(cell);
        if (point.isEmpty()) {
          if (!run.isEmpty()) {
            runs.add(run);
            run = new ArrayList<>();
          }
        } else if (run.isEmpty() || !run.get(run.size() - 1).equals(point.get())) {
          run.add(point.get());
        }
      }
      if (!run.isEmpty()) {
        runs.add(run);
      }
      if (!runs.isEmpty()) {
        YardTrain.Details details = train.details().get();
        found.add(
            new YardTrainEntry(
                train.name(),
                new YardPicture.TrainMark(
                    details.label(),
                    details.lineColor(),
                    Lateness.of(details.delaySeconds()),
                    runs)));
      }
    }
    found.sort(
        Comparator.comparing((YardTrainEntry entry) -> entry.mark().label())
            .thenComparing(YardTrainEntry::name));
    return found.stream().map(YardTrainEntry::mark).toList();
  }

  private record YardTrainEntry(String name, YardPicture.TrainMark mark) {}
}
