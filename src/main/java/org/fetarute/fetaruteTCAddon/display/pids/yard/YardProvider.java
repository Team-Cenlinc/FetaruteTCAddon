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
import java.util.function.Function;
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
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout;

/**
 * 站场图的数据：几何按调度图快照缓存，占用与列车位置每次现取。
 *
 * <ul>
 *   <li>几何只在调度图换了（重建、刷新、增补）之后重算；同一世界的各站共用一份按区间整理好的轨道方块。
 *   <li>占用只认区间（EDGE）资源：MOVEMENT_REQUIRED 记为进路锁闭；PROTECTIVE_RETAIN、HOLD_ONLY 与 PHYSICAL_FOOTPRINT
 *       记为保护占用， 两者都有时按保护占用（还没出清）。排队位、前瞻预览与解锁预约不画。
 *   <li>车身按各节车厢所在的轨道方块定位，比按占用推算准；只画落在站场图上的车厢。
 * </ul>
 *
 * <p>只读：不改占用、不碰列车。在主线程调用（列车位置要读 TrainCarts）。
 */
public final class YardProvider implements PidsYardSource {

  private final Function<UUID, Optional<RailGraph>> graphs;
  private final Supplier<List<OccupancyClaim>> claims;
  private final Function<UUID, List<YardTrain>> trains;
  private final Function<String, Optional<String>> stationNames;

  private final Map<Key, Cached> cache = new ConcurrentHashMap<>();
  private final Map<UUID, Footprints> footprints = new ConcurrentHashMap<>();

  /**
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
    this.graphs = Objects.requireNonNull(graphs, "graphs");
    this.claims = Objects.requireNonNull(claims, "claims");
    this.trains = Objects.requireNonNull(trains, "trains");
    this.stationNames = Objects.requireNonNull(stationNames, "stationNames");
  }

  private record Key(UUID worldId, PidsStationKey station, YardGeometry.Rect rect) {}

  /**
   * @param graph 算几何用的图快照（按对象身份判断换没换）
   * @param geometry 几何；图里没有本站时为空
   * @param edges 占用资源键 → 区间，只含站场图上画了的区间
   */
  private record Cached(
      RailGraph graph, Optional<YardGeometry> geometry, Map<String, EdgeId> edges) {}

  private record Footprints(RailGraph graph, Map<EdgeId, Set<RailFootprintCell>> cells) {}

  @Override
  public YardPicture picture(PidsStationKey station, UUID worldId, PidsLayout.Yard widget) {
    Optional<RailGraph> graph = graphs.apply(worldId);
    if (graph.isEmpty()) {
      return YardPicture.unavailable(YardPicture.Problem.GRAPH_NOT_READY);
    }
    YardGeometry.Rect rect =
        new YardGeometry.Rect(
            widget.trackLeft(), widget.trackTop(), widget.trackWidth(), widget.trackHeight());
    Key key = new Key(worldId, station, rect);
    Cached cached = cache.get(key);
    if (cached == null || cached.graph() != graph.get()) {
      cached = build(graph.get(), worldId, station, rect);
      cache.put(key, cached);
    }
    if (cached.geometry().isEmpty()) {
      return YardPicture.unavailable(YardPicture.Problem.STATION_NOT_IN_GRAPH);
    }
    YardGeometry geometry = cached.geometry().get();
    return new YardPicture(
        cached.geometry(),
        marks(cached.edges(), claims.get()),
        trainMarks(geometry, trains.apply(worldId)),
        Optional.empty());
  }

  private Cached build(
      RailGraph graph, UUID worldId, PidsStationKey station, YardGeometry.Rect rect) {
    Optional<YardArea> area = YardArea.of(graph, station.operatorCode(), station.stationCode());
    if (area.isEmpty()) {
      return new Cached(graph, Optional.empty(), Map.of());
    }
    YardGeometry geometry =
        YardGeometry.of(
            graph,
            area.get(),
            footprintsOf(worldId, graph),
            rect,
            code -> stationNames.apply(station.operatorCode() + ":" + code).orElse(code));
    Map<String, EdgeId> edges = new HashMap<>();
    for (YardGeometry.Path path : geometry.paths()) {
      edges.put(OccupancyResource.forEdge(path.edge()).key(), path.edge());
    }
    return new Cached(graph, Optional.of(geometry), Map.copyOf(edges));
  }

  /** 同一世界的各站共用：按区间整理轨道方块要扫一遍全图的方块索引。 */
  private Map<EdgeId, Set<RailFootprintCell>> footprintsOf(UUID worldId, RailGraph graph) {
    Footprints known = footprints.get(worldId);
    if (known != null && known.graph() == graph) {
      return known.cells();
    }
    Map<EdgeId, Set<RailFootprintCell>> cells = Map.of();
    if (graph instanceof RailGraphInterlockingSupport support) {
      RailInterlockingState state = support.interlockingState();
      if (state.cellCoverageAvailable()) {
        cells = Map.copyOf(state.footprintCellsByEdge());
      }
    }
    footprints.put(worldId, new Footprints(graph, cells));
    return cells;
  }

  /** 站场图上各区间的占用标记；保护占用压过进路锁闭。 */
  static List<YardPicture.EdgeMark> marks(Map<String, EdgeId> edges, List<OccupancyClaim> claims) {
    Map<EdgeId, YardPicture.Mark> marks = new TreeMap<>();
    for (OccupancyClaim claim : claims) {
      if (claim.resource().kind() != ResourceKind.EDGE) {
        continue;
      }
      EdgeId edge = edges.get(claim.resource().key());
      if (edge == null) {
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
                  edge,
                  found,
                  (a, b) ->
                      a == YardPicture.Mark.HELD || b == YardPicture.Mark.HELD
                          ? YardPicture.Mark.HELD
                          : YardPicture.Mark.ROUTE));
    }
    List<YardPicture.EdgeMark> out = new ArrayList<>(marks.size());
    marks.forEach((edge, mark) -> out.add(new YardPicture.EdgeMark(edge, mark)));
    return out;
  }

  /** 落在站场图上的列车：连续在图上的车厢为一段，相邻车厢落在同一像素时只记一次。 */
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
        found.add(
            new YardTrainEntry(
                train.name(),
                new YardPicture.TrainMark(
                    train.label(), train.lineColor(), Lateness.of(train.delaySeconds()), runs)));
      }
    }
    found.sort(
        Comparator.comparing((YardTrainEntry entry) -> entry.mark().label())
            .thenComparing(YardTrainEntry::name));
    return found.stream().map(YardTrainEntry::mark).toList();
  }

  private record YardTrainEntry(String name, YardPicture.TrainMark mark) {}
}
