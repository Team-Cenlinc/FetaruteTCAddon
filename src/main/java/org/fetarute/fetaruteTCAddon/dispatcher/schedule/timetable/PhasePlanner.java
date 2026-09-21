package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 相位：每个方向的子网格从几秒开始。
 *
 * <p>两层，顺序固定。第一层<b>往返对锚定</b>：同组内起终点互换的一对方向，反向的相位 = 正向相位 + 正向走行 + 折返（对反向间隔取模）——
 * 一辆车到达端点折返完正好是下一班的时隙，端点零等待。第二层<b>组间交错</b>：组按名字排序，第一组相位 0；后面每一组在 {@code [0, 最小间隔)} 上以 10 s
 * 步长扫描一个整体偏移，目标是<b>共用起点站台组上相邻发车的最大间隔最小</b>，并列时<b>最小间隔最大</b>（否则两组同时发车与均匀错开会打平）， 再并列取最小偏移。
 * 大小交路的价值全在这一层：Full 与 Short 在共用区间上错开，乘客感受到的是叠加后的间隔。
 *
 * <p>全部确定：组、方向、候选都按稳定键排序，扫描步长固定，没有随机源。
 */
public final class PhasePlanner {

  /** 扫描步长。 */
  public static final int SCAN_STEP_SECONDS = 10;

  private PhasePlanner() {}

  /**
   * 相位结果。
   *
   * @param phaseByDirection 方向键 → 相位
   * @param offsetByGroup 组 → 整体偏移（第二层的选择，供报告）
   * @param notes 说明（哪些方向锚定了、哪些组按共用起点交错了）
   */
  public record Phases(
      Map<String, Integer> phaseByDirection,
      Map<String, Integer> offsetByGroup,
      Map<String, Integer> deltaByDirection,
      List<String> notes,
      List<String> resourceNotes) {
    public Phases {
      phaseByDirection = phaseByDirection == null ? Map.of() : Map.copyOf(phaseByDirection);
      offsetByGroup = offsetByGroup == null ? Map.of() : Map.copyOf(offsetByGroup);
      deltaByDirection = deltaByDirection == null ? Map.of() : Map.copyOf(deltaByDirection);
      notes = notes == null ? List.of() : List.copyOf(notes);
      resourceNotes = resourceNotes == null ? List.of() : List.copyOf(resourceNotes);
    }

    /** 前两层的结果：第三层还没跑过。 */
    public Phases(
        Map<String, Integer> phaseByDirection,
        Map<String, Integer> offsetByGroup,
        List<String> notes) {
      this(phaseByDirection, offsetByGroup, Map.of(), notes, List.of());
    }

    /** 这个方向最终的相位：锚定/交错给的，加上第三层的端点多等。 */
    public int effectivePhaseOf(String directionKey) {
      return phaseByDirection.getOrDefault(directionKey, 0)
          + deltaByDirection.getOrDefault(directionKey, 0);
    }
  }

  /**
   * 往返对的相位余数：{@code (走行 + 折返) mod 间隔}。
   *
   * <p><b>只进报告，不参与决策。</b>实测（290–430 逐档扫 WS）余数与冲突数没有关系——余数 5 有 1909 处、 余数 185 一处也没有、余数 265 有 1129
   * 处。据它跳档会跳掉 420 这种干净档。
   *
   * @param forwardKey 正向方向键
   * @param reverseKey 反向方向键
   * @param runSeconds 正向走行（方向内最短的候选）
   * @param turnaroundSeconds 该 route 的折返
   * @param intervalSeconds 这一组的间隔
   * @param residue 余数
   */
  public record Residue(
      String forwardKey,
      String reverseKey,
      int runSeconds,
      int turnaroundSeconds,
      int intervalSeconds,
      int residue) {}

  /** 各往返对的余数，按正向键排序。 */
  public static List<Residue> residues(
      List<ServiceGroupClassifier.Group> groups,
      Map<String, Integer> intervalByGroup,
      Map<UUID, Integer> runSecondsByRoute,
      TurnaroundTable turnarounds) {
    List<Residue> out = new ArrayList<>();
    if (groups == null) {
      return out;
    }
    for (ServiceGroupClassifier.Group group : groups) {
      int interval = intervalByGroup.getOrDefault(group.name(), 0);
      if (interval <= 0) {
        continue;
      }
      Map<String, ServiceGroupClassifier.Direction> byKey = new LinkedHashMap<>();
      for (ServiceGroupClassifier.Direction direction : group.directions()) {
        byKey.put(direction.key(), direction);
      }
      for (ServiceGroupClassifier.Direction direction : group.directions()) {
        ServiceGroupClassifier.Direction reverse = byKey.get(direction.reverseKey());
        if (reverse == null || direction.key().compareTo(reverse.key()) > 0) {
          continue;
        }
        UUID anchor = minRunRoute(direction, runSecondsByRoute);
        int run = minRun(direction, runSecondsByRoute);
        int turnaround = turnarounds == null ? 0 : turnarounds.secondsFor(anchor);
        out.add(
            new Residue(
                direction.key(),
                reverse.key(),
                run,
                turnaround,
                interval,
                Math.floorMod(run + turnaround, interval)));
      }
    }
    out.sort(Comparator.comparing(Residue::forwardKey));
    return List.copyOf(out);
  }

  /**
   * 共用起点站台组上的合成间隔。
   *
   * @param originGroup 起点站台组
   * @param departures 叠加后的发车数
   * @param minGap 最小相邻间隔
   * @param medianGap 中位相邻间隔
   * @param maxGap 最大相邻间隔
   */
  public record Interleave(
      String originGroup, int departures, int minGap, int medianGap, int maxGap) {}

  /**
   * 一个方向沿途的一次停靠，用来找"哪些车在同一个站台上按同一个走向合流"。
   *
   * @param key 合流键：{@code 本站台组→下一站台组}。带上下一站是为了区分方向——乘客在某站等的是往某个方向去的车，
   *     反向的车停在对面站台，不该被算进合成间隔。用站台组而不是具体股道，是因为同一个车站同方向的几股道对乘客 是可以互换的（大交路停 1 道、小交路停 2 道，乘客照样两班都能坐）
   * @param offsetSeconds 从本方向发车起算，在这一站发车的偏移
   */
  public record StopCall(String key, int offsetSeconds) {
    public StopCall {
      key = key == null ? "" : key;
      offsetSeconds = Math.max(0, offsetSeconds);
    }
  }

  /** 旧签名：只按共用起点交错。 */
  public static Phases plan(
      List<ServiceGroupClassifier.Group> groups,
      Map<String, Integer> intervalByGroup,
      Map<UUID, Integer> runSecondsByRoute,
      TurnaroundTable turnarounds,
      int horizonSeconds) {
    return plan(groups, intervalByGroup, runSecondsByRoute, turnarounds, horizonSeconds, Map.of());
  }

  /**
   * 选相位。
   *
   * @param groups 分类结果里的组（按名字排序）
   * @param intervalByGroup 每组的间隔（秒）
   * @param runSecondsByRoute 每条 route 的全程时分
   * @param turnarounds 折返时间表
   * @param horizonSeconds 计划窗口长度
   * @param callsByDirection 方向键 → 沿途合流点；空表示退回只按共用起点交错
   */
  public static Phases plan(
      List<ServiceGroupClassifier.Group> groups,
      Map<String, Integer> intervalByGroup,
      Map<UUID, Integer> runSecondsByRoute,
      TurnaroundTable turnarounds,
      int horizonSeconds,
      Map<String, List<StopCall>> callsByDirection) {
    Objects.requireNonNull(groups, "groups");
    Map<String, Integer> phases = new TreeMap<>();
    Map<String, Integer> offsets = new TreeMap<>();
    List<String> notes = new ArrayList<>();
    Map<String, List<StopCall>> calls = callsByDirection == null ? Map.of() : callsByDirection;
    // 已放好的组在各<b>合流点</b>（站台组 + 走向）上的发车时刻，供后面的组交错。
    //
    // 原来只按"共用起点站台组"匹配，于是两组只要不在同一个站始发就永远不会被拿来比。实测 WS 的小交路从
    // 林湾车库始发、大交路从克罗顿高地与南渡始发，三个起点互不相同，两组在长达六站的重合区段上从未被错开过——
    // 240/240 的表在重合段是"两班隔 55 秒挤在一起、再空 185 秒"，而不是均匀的 120 秒。改成沿途每一站都算合流点。
    Map<String, List<List<Integer>>> placedByStop = new TreeMap<>();

    for (ServiceGroupClassifier.Group group : groups) {
      if (group.directions().isEmpty()) {
        continue;
      }
      int interval = Math.max(1, intervalByGroup.getOrDefault(group.name(), 1));
      // 第一层：组内相对相位（偏移 0 时的相位），往返对锚定。
      Map<String, Integer> relative =
          anchorReturnPairs(group, interval, runSecondsByRoute, turnarounds, notes);
      // 第二层：整体偏移。
      int offset = 0;
      List<String> shared = new ArrayList<>();
      for (ServiceGroupClassifier.Direction direction : group.directions()) {
        for (StopCall call : stopsOf(direction, calls)) {
          if (placedByStop.containsKey(call.key()) && !shared.contains(call.key())) {
            shared.add(call.key());
          }
        }
      }
      if (!shared.isEmpty()) {
        int bestOffset = 0;
        int bestGap = Integer.MAX_VALUE;
        int bestTightest = -1;
        for (int candidate = 0; candidate < interval; candidate += SCAN_STEP_SECONDS) {
          int worst = 0;
          int tightest = Integer.MAX_VALUE;
          for (String stop : shared) {
            List<List<Integer>> streams = new ArrayList<>(placedByStop.get(stop));
            for (ServiceGroupClassifier.Direction direction : group.directions()) {
              for (StopCall call : stopsOf(direction, calls)) {
                if (!call.key().equals(stop)) {
                  continue;
                }
                int phase = Math.floorMod(relative.get(direction.key()) + candidate, interval);
                streams.add(streamOf(phase + call.offsetSeconds(), interval, horizonSeconds));
              }
            }
            int[] gaps = gapRange(streams);
            tightest = Math.min(tightest, gaps[0]);
            worst = Math.max(worst, gaps[1]);
          }
          if (worst < bestGap || (worst == bestGap && tightest > bestTightest)) {
            bestGap = worst;
            bestTightest = tightest;
            bestOffset = candidate;
          }
        }
        offset = bestOffset;
        notes.add(
            "交路组 "
                + group.name()
                + " 在共用合流点 "
                + String.join("、", shared.size() > 3 ? shared.subList(0, 3) : shared)
                + (shared.size() > 3 ? " 等 " + shared.size() + " 处" : "")
                + " 上交错，偏移 "
                + offset
                + "s，最大合成间隔 "
                + bestGap
                + "s");
      }
      offsets.put(group.name(), offset);
      for (ServiceGroupClassifier.Direction direction : group.directions()) {
        int phase = Math.floorMod(relative.get(direction.key()) + offset, interval);
        phases.put(direction.key(), phase);
        for (StopCall call : stopsOf(direction, calls)) {
          placedByStop
              .computeIfAbsent(call.key(), key -> new ArrayList<>())
              .add(streamOf(phase + call.offsetSeconds(), interval, horizonSeconds));
        }
      }
    }
    return new Phases(phases, offsets, notes);
  }

  /** 一个方向的合流点；没给停靠点信息时退回「起点站台组，偏移 0」，与只按共用起点交错的老行为一致。 */
  private static List<StopCall> stopsOf(
      ServiceGroupClassifier.Direction direction, Map<String, List<StopCall>> calls) {
    List<StopCall> out = calls.get(direction.key());
    if (out != null && !out.isEmpty()) {
      return out;
    }
    return direction.originGroup().isBlank()
        ? List.of()
        : List.of(new StopCall(direction.originGroup(), 0));
  }

  /** 由生成好的子网格算各起点站台组的合成间隔，供报告；只列有两条以上发车的起点。 */
  public static List<Interleave> interleaves(List<GroupGrid.DirectionGrid> grids) {
    Map<String, List<Integer>> byOrigin = new TreeMap<>();
    for (GroupGrid.DirectionGrid grid : grids) {
      List<Integer> departures =
          byOrigin.computeIfAbsent(grid.direction().originGroup(), key -> new ArrayList<>());
      for (GroupGrid.Slot slot : grid.slots()) {
        departures.add(slot.departureSeconds());
      }
    }
    List<Interleave> out = new ArrayList<>();
    byOrigin.forEach(
        (origin, departures) -> {
          if (departures.size() < 2) {
            return;
          }
          List<Integer> sorted = new ArrayList<>(departures);
          Collections.sort(sorted);
          List<Integer> gaps = new ArrayList<>(sorted.size() - 1);
          for (int i = 1; i < sorted.size(); i++) {
            gaps.add(sorted.get(i) - sorted.get(i - 1));
          }
          Collections.sort(gaps);
          out.add(
              new Interleave(
                  origin,
                  sorted.size(),
                  gaps.get(0),
                  gaps.get(gaps.size() / 2),
                  gaps.get(gaps.size() - 1)));
        });
    return List.copyOf(out);
  }

  /** 往返对锚定：起终点互换的两个方向里，键较小的为正向、相位 0；反向相位 = 正向走行（方向内最短的候选）+ 折返，对间隔取模。 没有配对的方向相位 0。 */
  private static Map<String, Integer> anchorReturnPairs(
      ServiceGroupClassifier.Group group,
      int interval,
      Map<UUID, Integer> runSecondsByRoute,
      TurnaroundTable turnarounds,
      List<String> notes) {
    Map<String, Integer> relative = new LinkedHashMap<>();
    Map<String, ServiceGroupClassifier.Direction> byKey = new LinkedHashMap<>();
    for (ServiceGroupClassifier.Direction direction : group.directions()) {
      byKey.put(direction.key(), direction);
    }
    for (ServiceGroupClassifier.Direction direction : group.directions()) {
      if (relative.containsKey(direction.key())) {
        continue;
      }
      ServiceGroupClassifier.Direction reverse = byKey.get(direction.reverseKey());
      if (reverse == null || direction.key().compareTo(reverse.key()) > 0) {
        if (reverse == null) {
          relative.put(direction.key(), 0);
        }
        continue;
      }
      relative.put(direction.key(), 0);
      // 折返取正向里<b>走行最短那条 route</b> 自己的：锚定用的是同一趟车，走行与折返必须来自同一条线路。
      UUID anchorRoute = minRunRoute(direction, runSecondsByRoute);
      int run = minRun(direction, runSecondsByRoute);
      int turnaroundSeconds = turnarounds.secondsFor(anchorRoute);
      int anchored = Math.floorMod(run + turnaroundSeconds, interval);
      relative.put(reverse.key(), anchored);
      notes.add(
          "交路组 "
              + group.name()
              + " 往返对 "
              + direction.key()
              + " / "
              + reverse.key()
              + " 锚定：反向相位 = 走行 "
              + run
              + " + 折返 "
              + turnaroundSeconds
              + " ≡ "
              + anchored
              + "s");
    }
    return relative;
  }

  /** 方向内走行最短的那条 route；相位与折返都以它为锚。 */
  private static UUID minRunRoute(
      ServiceGroupClassifier.Direction direction, Map<UUID, Integer> runSecondsByRoute) {
    UUID best = null;
    int min = Integer.MAX_VALUE;
    for (UUID routeId : direction.routeIds()) {
      Integer run = runSecondsByRoute.get(routeId);
      if (run != null && run < min) {
        min = run;
        best = routeId;
      }
    }
    return best;
  }

  private static int minRun(
      ServiceGroupClassifier.Direction direction, Map<UUID, Integer> runSecondsByRoute) {
    int min = Integer.MAX_VALUE;
    for (UUID routeId : direction.routeIds()) {
      Integer run = runSecondsByRoute.get(routeId);
      if (run != null) {
        min = Math.min(min, run);
      }
    }
    return min == Integer.MAX_VALUE ? 0 : min;
  }

  /** 相邻发车的 {最小, 最大} 间隔；不足两条发车时都是 0。 */
  /** 一条周期流在窗口内的时刻。 */
  private static List<Integer> streamOf(int first, int interval, int horizonSeconds) {
    List<Integer> out = new ArrayList<>();
    for (int t = first; t <= horizonSeconds; t += Math.max(1, interval)) {
      out.add(t);
    }
    return out;
  }

  /**
   * 几条周期流合起来的最小与最大间隔。
   *
   * <p>只在<b>所有流都覆盖的区间</b>里算。各条流的首班时刻能差出很远（大交路从线路另一头开过来，小交路几分钟前
   * 才出库），直接把展开后的序列拼起来，开头和末尾那段"只有一条流"会冒出一个整整一个间隔大的空档，把真正的 合成间隔淹掉——扫描于是看哪个偏移都一样差，只能靠第二判据瞎选。
   */
  private static int[] gapRange(List<List<Integer>> streams) {
    int from = Integer.MIN_VALUE;
    int to = Integer.MAX_VALUE;
    int present = 0;
    for (List<Integer> stream : streams) {
      if (stream == null || stream.isEmpty()) {
        continue;
      }
      present++;
      from = Math.max(from, stream.get(0));
      to = Math.min(to, stream.get(stream.size() - 1));
    }
    if (present == 0 || from > to) {
      return new int[] {0, 0};
    }
    List<Integer> merged = new ArrayList<>();
    for (List<Integer> stream : streams) {
      if (stream == null) {
        continue;
      }
      for (int t : stream) {
        if (t >= from && t <= to) {
          merged.add(t);
        }
      }
    }
    if (merged.size() < 2) {
      return new int[] {0, 0};
    }
    Collections.sort(merged);
    int min = Integer.MAX_VALUE;
    int max = 0;
    for (int i = 1; i < merged.size(); i++) {
      int gap = merged.get(i) - merged.get(i - 1);
      min = Math.min(min, gap);
      max = Math.max(max, gap);
    }
    return new int[] {min, max};
  }
}
