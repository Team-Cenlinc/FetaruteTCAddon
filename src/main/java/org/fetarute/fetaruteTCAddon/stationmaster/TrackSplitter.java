package org.fetarute.fetaruteTCAddon.stationmaster;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 后来的站长从别人手里分走一份股道。本类不依赖服务器对象。
 *
 * <p>只分股道号相连的一段（同一个岛式站台两侧的股道号通常相邻，站在中间两边都能接），留给原来的人至少一条。每种分法按“工作量离目标差多少 +
 * 分开后两人各自股道停站时段重叠的次数”计代价——一次重叠就是一列顾不上的车，与差一个车次同等看待；代价相同取股道少的，再取股道号小的。
 */
final class TrackSplitter {

  private TrackSplitter() {}

  /**
   * 从 {@code owned} 里分出一段。
   *
   * @param owned 原来的人管的股道（在 {@code workload} 里的下标），按股道号排好
   * @param workload 全站各股道的工作量
   * @param target 每人应分的工作量（全站工作量 ÷ 在岗人数）
   * @return 分出去的股道下标；原来的人只管一条时为空
   */
  static Optional<List<Integer>> split(List<Integer> owned, TrackWorkload workload, double target) {
    int count = owned.size();
    if (count < 2) {
      return Optional.empty();
    }
    List<Integer> best = null;
    double bestCost = Double.MAX_VALUE;
    for (int from = 0; from < count; from++) {
      for (int to = from + 1; to <= count; to++) {
        if (to - from == count) {
          continue;
        }
        List<Integer> piece = owned.subList(from, to);
        List<Integer> rest = new ArrayList<>(owned.subList(0, from));
        rest.addAll(owned.subList(to, count));
        double cost =
            Math.abs(load(piece, workload) - target)
                + overlaps(piece, workload)
                + overlaps(rest, workload);
        if (cost < bestCost - 1e-9 || (Math.abs(cost - bestCost) <= 1e-9 && fewer(piece, best))) {
          best = piece;
          bestCost = cost;
        }
      }
    }
    return Optional.of(List.copyOf(best));
  }

  /** 股道更少（等长时先遍历到的股道号更小，保持原样）。 */
  private static boolean fewer(List<Integer> piece, List<Integer> best) {
    return best == null || piece.size() < best.size();
  }

  private static double load(List<Integer> tracks, TrackWorkload workload) {
    double total = 0.0;
    for (int track : tracks) {
      total += workload.load(track);
    }
    return total;
  }

  /** 一组股道内两两停站时段重叠的次数。 */
  static int overlaps(List<Integer> tracks, TrackWorkload workload) {
    int total = 0;
    for (int i = 0; i < tracks.size(); i++) {
      for (int j = i + 1; j < tracks.size(); j++) {
        total += workload.overlap(tracks.get(i), tracks.get(j));
      }
    }
    return total;
  }
}
