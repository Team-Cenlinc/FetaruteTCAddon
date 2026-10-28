package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 相位：每个方向的子网格从几秒开始，以及报告用的余数与合成间隔。
 *
 * <p>相位本身由周期层求解（{@link PeriodicSolver}）给出；这里只放它交给完整构建的结果（{@link Phases}）与报告用的几样东西。
 */
public final class PhasePlanner {

  private PhasePlanner() {}

  /**
   * 相位结果。
   *
   * @param phaseByDirection 方向键 → 相位（方向键在全部交路组里唯一，见 {@link ServiceGroupClassifier.Direction}）
   * @param notes 说明（周期层求解的结论、多停）
   * @param connections 跨组接班：派车按它优先用喂车方向的车接
   */
  public record Phases(
      Map<String, Integer> phaseByDirection,
      List<String> notes,
      List<Connection> connections,
      List<Regime> regimes) {
    public Phases {
      phaseByDirection = phaseByDirection == null ? Map.of() : Map.copyOf(phaseByDirection);
      notes = notes == null ? List.of() : List.copyOf(notes);
      connections = connections == null ? List.of() : List.copyOf(connections);
      regimes = regimes == null ? List.of() : List.copyOf(regimes);
    }

    /** 全天一个制式。 */
    public Phases(
        Map<String, Integer> phaseByDirection, List<String> notes, List<Connection> connections) {
      this(phaseByDirection, notes, connections, List.of());
    }

    /** 这个方向的相位；没有给出时为 0。 */
    public int phaseOf(String directionKey) {
      return phaseByDirection.getOrDefault(directionKey, 0);
    }
  }

  /**
   * 分时段编表里的一个制式：这一段里各组的间隔与各方向的相位。
   *
   * @param from 起点（相对计划窗口起点，含）
   * @param to 终点（不含）
   * @param intervals 组 → 间隔
   * @param phases 方向键 → 相位
   */
  public record Regime(
      int from, int to, Map<String, Integer> intervals, Map<String, Integer> phases) {
    public Regime {
      intervals = Map.copyOf(intervals);
      phases = Map.copyOf(phases);
    }
  }

  /**
   * 一次跨组接班：喂车 route 的车在端点折返后接被接 route 的下一班。
   *
   * @param feederRoutes 喂车 route
   * @param fedRoutes 被接 route
   */
  public record Connection(List<UUID> feederRoutes, List<UUID> fedRoutes) {
    public Connection {
      feederRoutes = feederRoutes == null ? List.of() : List.copyOf(feederRoutes);
      fedRoutes = fedRoutes == null ? List.of() : List.copyOf(fedRoutes);
    }
  }

  /**
   * 往返对的相位余数：{@code (走行 + 折返) mod 间隔}。
   *
   * <p><b>只进报告，不参与决策。</b>余数与冲突数没有稳定关系：余数小的间隔可能冲突很多，余数大的反而可能一处没有。 据它跳档会跳掉本来干净的间隔。
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

  /**
   * 各往返对的余数，按正向键排序；正向的取法见 {@link #isForward}。
   *
   * @param inPlaceTurnbackRoutes 终到原地折返端的 route
   */
  public static List<Residue> residues(
      List<ServiceGroupClassifier.Group> groups,
      Map<String, Integer> intervalByGroup,
      Map<UUID, Integer> runSecondsByRoute,
      TurnaroundTable turnarounds,
      Set<UUID> inPlaceTurnbackRoutes) {
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
        if (reverse == null || !isForward(direction, reverse, inPlaceTurnbackRoutes)) {
          continue;
        }
        UUID anchor = anchorRoute(direction, reverse, runSecondsByRoute, inPlaceTurnbackRoutes);
        int run = runOf(anchor, direction, runSecondsByRoute);
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

  /**
   * 往返对里这个方向是不是正向（报告的余数按正向终点算）。
   *
   * <p>恰有一个方向含终到原地折返端的 route 时它是正向：车在那里只能原地等下一班，折返要最短，余量放到另一端。两种原地折返端：
   *
   * <ul>
   *   <li>正线路径点：停车会挡住同一股道的后车。小交路过某站后在正线路径点终到折返、反向从那里发车时，站台组却都记作该站； 只按键的字典序时锚点可能落在另一端，正线折返点反而拿到周期余数。
   *   <li>没有出入库线路的站台：车调不走，余量堆在那里就超出闲置上限、接不上下一班。只按字典序锚定时， 余量可能全堆在这一端，整组一班都排不出来。
   * </ul>
   *
   * 两个方向都有或都没有原地折返端时，键较小的是正向。
   *
   * @param direction 待判定的方向
   * @param reverse 它的反向
   * @param inPlaceTurnbackRoutes 终到原地折返端的 route
   */
  static boolean isForward(
      ServiceGroupClassifier.Direction direction,
      ServiceGroupClassifier.Direction reverse,
      Set<UUID> inPlaceTurnbackRoutes) {
    boolean turnsBack = hasAnyRoute(direction, inPlaceTurnbackRoutes);
    if (turnsBack != hasAnyRoute(reverse, inPlaceTurnbackRoutes)) {
      return turnsBack;
    }
    return direction.key().compareTo(reverse.key()) <= 0;
  }

  /** 方向里有没有属于 {@code routes} 的 route。 */
  static boolean hasAnyRoute(ServiceGroupClassifier.Direction direction, Set<UUID> routes) {
    return routes != null && direction.routeIds().stream().anyMatch(routes::contains);
  }

  /** 正向里用来锚定的 route：按原地折返端锚定时取终到那里、走行最短的 route（同方向还有回库车时，不能拿回库车的走行与折返去锚原地折返）；否则取方向内走行最短的。 */
  private static UUID anchorRoute(
      ServiceGroupClassifier.Direction forward,
      ServiceGroupClassifier.Direction reverse,
      Map<UUID, Integer> runSecondsByRoute,
      Set<UUID> inPlaceTurnbackRoutes) {
    boolean inPlaceRule =
        hasAnyRoute(forward, inPlaceTurnbackRoutes) && !hasAnyRoute(reverse, inPlaceTurnbackRoutes);
    if (!inPlaceRule) {
      return minRunRoute(forward, runSecondsByRoute);
    }
    UUID best = null;
    int min = Integer.MAX_VALUE;
    for (UUID routeId : forward.routeIds()) {
      Integer run = runSecondsByRoute.get(routeId);
      if (inPlaceTurnbackRoutes.contains(routeId) && run != null && run < min) {
        min = run;
        best = routeId;
      }
    }
    return best == null ? minRunRoute(forward, runSecondsByRoute) : best;
  }

  /** 锚定 route 的走行；取不到时退回方向内最短走行（与旧规则相同）。 */
  private static int runOf(
      UUID anchorRoute,
      ServiceGroupClassifier.Direction direction,
      Map<UUID, Integer> runSecondsByRoute) {
    Integer run = anchorRoute == null ? null : runSecondsByRoute.get(anchorRoute);
    return run == null ? minRun(direction, runSecondsByRoute) : run;
  }

  /** 方向内走行最短的那条 route。 */
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
}
