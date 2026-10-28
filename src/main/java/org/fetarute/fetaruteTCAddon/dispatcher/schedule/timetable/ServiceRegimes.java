package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.plan.Band;

/**
 * 把分时段频率切成制式：计划窗口里各交路组间隔都不变的连续时段。
 *
 * <p>周期层求解只认"一组固定间隔"，所以每个制式各解一次；相邻两段间隔完全相同的合并成一段，没有计划书时整个窗口就是一个制式。
 *
 * <p>同一组键的时段按优先级排好交进来（先限定了线路的、后不限线路的）：某一时刻取第一个包含它的时段，都不包含时用基准间隔。
 * 计划书里的时刻相对当天零点，这里换成相对计划窗口起点的秒数，窗口外的部分裁掉。
 */
final class ServiceRegimes {

  private ServiceRegimes() {}

  /**
   * 一个制式。
   *
   * @param from 起点（相对计划窗口起点，含）
   * @param to 终点（不含）
   * @param intervals 组键 → 这一段的间隔
   */
  record Regime(int from, int to, Map<String, Integer> intervals) {
    Regime {
      if (to <= from) {
        throw new IllegalArgumentException("制式须满足 from < to: " + from + "-" + to);
      }
      intervals = Map.copyOf(new TreeMap<>(intervals));
    }
  }

  /**
   * 切制式。
   *
   * @param base 组键 → 基准间隔（时段之外用它）
   * @param bands 组键 → 这个组的时段，按优先级排好；没有计划书的组不在其中
   * @param serviceStartSecondOfDay 计划窗口起点（当天秒）
   * @param horizonSeconds 计划窗口长度
   * @return 按时间排好的制式，首尾相接覆盖整个窗口
   */
  static List<Regime> of(
      Map<String, Integer> base,
      Map<String, List<Band>> bands,
      int serviceStartSecondOfDay,
      int horizonSeconds) {
    Objects.requireNonNull(base, "base");
    Map<String, List<Band>> byGroup = bands == null ? Map.of() : bands;
    TreeSet<Integer> cuts = new TreeSet<>();
    cuts.add(0);
    cuts.add(horizonSeconds);
    for (Map.Entry<String, List<Band>> entry : byGroup.entrySet()) {
      if (!base.containsKey(entry.getKey())) {
        continue;
      }
      for (Band band : entry.getValue()) {
        for (int edge : new int[] {band.fromSecond(), band.toSecond()}) {
          int relative = edge - serviceStartSecondOfDay;
          if (relative > 0 && relative < horizonSeconds) {
            cuts.add(relative);
          }
        }
      }
    }
    List<Regime> out = new ArrayList<>();
    Integer previous = null;
    for (int cut : cuts) {
      if (previous != null) {
        Map<String, Integer> intervals = new TreeMap<>();
        int at = previous + serviceStartSecondOfDay;
        base.forEach(
            (group, seconds) -> intervals.put(group, intervalAt(byGroup.get(group), at, seconds)));
        Regime last = out.isEmpty() ? null : out.get(out.size() - 1);
        if (last != null && last.intervals().equals(intervals)) {
          out.set(out.size() - 1, new Regime(last.from(), cut, intervals));
        } else {
          out.add(new Regime(previous, cut, intervals));
        }
      }
      previous = cut;
    }
    return List.copyOf(out);
  }

  private static int intervalAt(List<Band> bands, int secondOfDay, int fallback) {
    if (bands != null) {
      for (Band band : bands) {
        if (band.contains(secondOfDay)) {
          return band.intervalSeconds();
        }
      }
    }
    return fallback;
  }
}
