package org.fetarute.fetaruteTCAddon.stationmaster;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.Timetable;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableRuns;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableStop;

/**
 * 一个车站各条股道的工作量，供站长分股道（见 {@link TrackSplitter}）。只求分得大致均匀，不追求精确。本类不依赖服务器对象。
 *
 * <ul>
 *   <li>工作量：每条股道先算 1（无表线路的车、凌晨空窗也要有人照看），再加已发布的时刻表在窗口内于这条股道停站的车次数；DYNAMIC 停站按候选股道平分；
 *   <li>冲突：两条股道的停站时段（到站到发车）互相重叠的次数——同一个人顾不上同时停在两条股道上的车；DYNAMIC 停站股道未定，不计。
 * </ul>
 */
final class TrackWorkload {

  private final double[] loads;
  private final int[][] overlaps;

  private TrackWorkload(double[] loads, int[][] overlaps) {
    this.loads = loads;
    this.overlaps = overlaps;
  }

  /** 股道数。 */
  int size() {
    return loads.length;
  }

  /** 第 {@code track} 条股道的工作量（与传入的股道顺序一致）。 */
  double load(int track) {
    return loads[track];
  }

  /** 两条股道停站时段的重叠次数（对称，同一条为 0）。 */
  int overlap(int a, int b) {
    return overlaps[a][b];
  }

  /** 全站的工作量。 */
  double total() {
    double total = 0.0;
    for (double load : loads) {
      total += load;
    }
    return total;
  }

  /**
   * 算一个车站各股道的工作量。
   *
   * @param tracks 车站的股道
   * @param station 车站
   * @param timetables 已发布的时刻表
   * @param from 窗口起点
   * @param window 窗口长度
   */
  static TrackWorkload of(
      List<StationMasterWorld.Track> tracks,
      StationMasterWorld.Station station,
      List<Timetable> timetables,
      Instant from,
      Duration window) {
    int count = tracks.size();
    double[] loads = new double[count];
    Arrays.fill(loads, 1.0);
    List<List<Instant[]>> windows = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      windows.add(new ArrayList<>());
    }
    Instant to = from.plus(window);
    TimetableRuns.forEach(
        timetables,
        from,
        to,
        (stops, start) -> {
          for (TimetableStop stop : stops) {
            Instant arrival = start.plusSeconds(stop.arrivalOffsetSeconds());
            Instant departure = start.plusSeconds(stop.departureOffsetSeconds());
            if (!stop.stops() || !arrival.isBefore(to) || departure.isBefore(from)) {
              continue;
            }
            assign(tracks, station, stop, arrival, departure, loads, windows);
          }
        });
    int[][] overlaps = new int[count][count];
    for (int i = 0; i < count; i++) {
      for (int j = i + 1; j < count; j++) {
        int overlap = countOverlaps(windows.get(i), windows.get(j));
        overlaps[i][j] = overlap;
        overlaps[j][i] = overlap;
      }
    }
    return new TrackWorkload(loads, overlaps);
  }

  /** 一次停站记到哪条股道：按节点认；DYNAMIC 写法按候选股道平分。 */
  private static void assign(
      List<StationMasterWorld.Track> tracks,
      StationMasterWorld.Station station,
      TimetableStop stop,
      Instant arrival,
      Instant departure,
      double[] loads,
      List<List<Instant[]>> windows) {
    Optional<String> node = stop.nodeId();
    if (node.isEmpty()) {
      return;
    }
    Optional<DynamicStopMatcher.DynamicSpec> spec =
        node.get().regionMatches(true, 0, "DYNAMIC:", 0, "DYNAMIC:".length())
            ? DynamicStopMatcher.parseDynamicSpec(node.get())
            : Optional.empty();
    if (spec.isEmpty()) {
      NodeId id = NodeId.of(node.get());
      for (int i = 0; i < tracks.size(); i++) {
        if (tracks.get(i).node().equals(id)) {
          loads[i] += 1.0;
          windows.get(i).add(new Instant[] {arrival, departure});
        }
      }
      return;
    }
    DynamicStopMatcher.DynamicSpec dynamic = spec.get();
    if (!"S".equals(dynamic.nodeType())
        || !dynamic.operatorCode().equalsIgnoreCase(station.operator())
        || !dynamic.nodeName().equalsIgnoreCase(station.code())) {
      return;
    }
    List<Integer> candidates = new ArrayList<>();
    for (int i = 0; i < tracks.size(); i++) {
      if (dynamic.tracks().contains(tracks.get(i).number())) {
        candidates.add(i);
      }
    }
    for (int i : candidates) {
      loads[i] += 1.0 / candidates.size();
    }
  }

  /** 两条股道的停站时段两两重叠的次数。 */
  private static int countOverlaps(List<Instant[]> a, List<Instant[]> b) {
    int count = 0;
    for (Instant[] x : a) {
      for (Instant[] y : b) {
        if (x[0].isBefore(y[1]) && y[0].isBefore(x[1])) {
          count++;
        }
      }
    }
    return count;
  }
}
