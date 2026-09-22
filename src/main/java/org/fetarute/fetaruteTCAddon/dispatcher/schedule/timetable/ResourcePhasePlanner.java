package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 相位第三层：资源相位。
 *
 * <h2>前两层看不见什么</h2>
 *
 * <p>第一层把往返对锚在一起（反向相位 = 走行 + 折返），目标是<b>端点零等待</b>；第二层在<b>共用起点</b>上
 * 把各组错开。两层都只看端点——沿线哪里交会、车库咽喉上出库流和回库流什么时候相遇，它们一无所知。
 *
 * <p>实测：MT 的两个组在 SPB 汇合之后前后只差 20 秒，一路撞到 PPK；WS 剩下的 189 处不可吸收冲突全在 LWN 车库咽喉那根单线桥链上，撞的是「出库流 ×
 * 回库流」。这两件事是同一件事——<b>两条周期流在同一资源上的 相对相位</b>，而第二层的"共用起点"只是它的一个特例。
 *
 * <h2>怎么选</h2>
 *
 * <p>给每个方向选一个 δ：
 *
 * <ul>
 *   <li><b>正向</b>方向的 δ 是整组平移，不改变组内往返对的相对关系。
 *   <li><b>反向</b>方向的 δ 是<b>端点多等</b>：车到终点之后多停一会儿再发，所以上限是 {@code --max-idle} （再久运行时就把它收回库了）。相位 = 锚定 +
 *       δ，子网格与派车器都不用改，{@code readyAt} 天然吃下这段等待。
 * </ul>
 *
 * <p>评估不另写几何模型，直接把模板铺 {@link #CYCLES} 个周期交给 {@link TimetableConflictChecker}， 再用 {@link
 * ConflictAbsorption} 分成可吸收/不可吸收——与最终判据同一套口径。分数按 {@code (不可吸收数, 总数, 总预计等待)} 字典序比较。
 *
 * <p><b>δ=0 永远是候选，并列时胜出</b>：锚定给的端点零等待是缺省，只有真能减少不可吸收数时才付出端点等待。
 *
 * <p>确定性：候选来自固定步长，排序键全是整数，组按名字序、方向按键序遍历，资源表用 {@link TreeMap}， 前面定下的方向不再回头改（贪心）。同一份输入永远得到同一组 δ。
 */
public final class ResourcePhasePlanner {

  /** 扫描步长。 */
  public static final int SCAN_STEP_SECONDS = 10;

  /** 铺几个周期来评估。周期流的相对相位在整个窗口里重复，前几个周期已经代表全部。 */
  public static final int CYCLES = 3;

  private ResourcePhasePlanner() {}

  /**
   * 评分：越小越好，字典序。
   *
   * @param unabsorbable 运行时让不掉的冲突数——这才是决定一张表行不行的量
   * @param total 冲突总数
   * @param waitSeconds 预计等待合计
   */
  record Score(int unabsorbable, int total, long waitSeconds) implements Comparable<Score> {
    @Override
    public int compareTo(Score other) {
      if (unabsorbable != other.unabsorbable) {
        return Integer.compare(unabsorbable, other.unabsorbable);
      }
      if (total != other.total) {
        return Integer.compare(total, other.total);
      }
      return Long.compare(waitSeconds, other.waitSeconds);
    }
  }

  /**
   * 为每个方向选 δ。
   *
   * @param phases 前两层的结果
   * @param groups 分类
   * @param intervalByGroup 各组间隔
   * @param templates 各方向的周期模板，键 = 方向键
   * @param profiles 各 route 的投影
   * @param index 图索引
   * @param separationSeconds 裕量
   * @param maxWaitSeconds 可吸收判据用的单步上限
   * @param maxIdleSeconds 反向 δ 的上限（端点多等不能超过运行时的闲置回收）
   * @return 带 {@code deltaByDirection} 与 {@code resourceNotes} 的新 {@code Phases}
   */
  public static PhasePlanner.Phases refine(
      PhasePlanner.Phases phases,
      List<ServiceGroupClassifier.Group> groups,
      Map<String, Integer> intervalByGroup,
      Map<String, PeriodicTemplate> templates,
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
      TimetableConflictChecker.GraphIndex index,
      int separationSeconds,
      int maxWaitSeconds,
      int maxIdleSeconds) {
    if (phases == null || groups == null || templates == null || templates.isEmpty()) {
      return phases;
    }
    // 方向的遍历序：组按名字、组内按键。贪心逐个定下来，前面的不回头改。
    List<Direction> order = orderOf(groups, phases, intervalByGroup);
    if (order.size() < 2) {
      // 只有一个周期流时没有"相对相位"可言。
      return phases;
    }
    Map<String, Integer> delta = new LinkedHashMap<>();
    List<String> notes = new ArrayList<>();
    for (Direction direction : order) {
      int limit = direction.reverse() ? Math.max(0, maxIdleSeconds) : direction.interval();
      Score best = null;
      int bestDelta = 0;
      for (int candidate = 0; candidate <= limit; candidate += SCAN_STEP_SECONDS) {
        delta.put(direction.key(), candidate);
        Score score =
            score(
                order,
                phases,
                delta,
                templates,
                profiles,
                index,
                separationSeconds,
                maxWaitSeconds);
        // δ=0 先被评，并列时保持它——端点零等待是缺省，只有真能减少不可吸收数才付出等待。
        if (best == null || score.compareTo(best) < 0) {
          best = score;
          bestDelta = candidate;
        }
      }
      delta.put(direction.key(), bestDelta);
      if (bestDelta > 0) {
        notes.add(
            String.format(
                Locale.ROOT,
                "%s %s %ds（周期评估：让不掉的 %d 处、共 %d 处）",
                direction.key(),
                direction.reverse() ? "端点多等" : "整组平移",
                bestDelta,
                best == null ? 0 : best.unabsorbable(),
                best == null ? 0 : best.total()));
      }
    }
    if (notes.isEmpty()) {
      notes.add("各方向都取零偏移：周期评估里没有哪个 δ 能减少让不掉的冲突");
    }
    return new PhasePlanner.Phases(
        phases.phaseByDirection(),
        phases.offsetByGroup(),
        Map.copyOf(delta),
        phases.notes(),
        List.copyOf(notes));
  }

  // ------------------------------------------------------------------ 内部

  /**
   * 一个待定相位的方向。
   *
   * @param key 方向键
   * @param interval 该组间隔
   * @param reverse 是不是往返对里的反向（它的 δ 是端点多等，有上限）
   */
  /**
   * @param originGroup 起点站台组：残余判据要的"后车在哪等"就是它
   */
  private record Direction(String key, int interval, boolean reverse, String originGroup) {
    Direction {
      originGroup = originGroup == null ? "" : originGroup;
    }
  }

  private static List<Direction> orderOf(
      List<ServiceGroupClassifier.Group> groups,
      PhasePlanner.Phases phases,
      Map<String, Integer> intervalByGroup) {
    List<Direction> out = new ArrayList<>();
    for (ServiceGroupClassifier.Group group : groups) {
      int interval = intervalByGroup.getOrDefault(group.name(), 0);
      if (interval <= 0) {
        continue;
      }
      for (ServiceGroupClassifier.Direction direction : group.directions()) {
        // 锚定给了非零相位的就是反向：它的 δ 是车在端点多等，受闲置上限约束。
        boolean reverse = phases.phaseByDirection().getOrDefault(direction.key(), 0) != 0;
        out.add(new Direction(direction.key(), interval, reverse, direction.originGroup()));
      }
    }
    return List.copyOf(out);
  }

  /** 把所有方向按当前 δ 铺开，过一遍冲突模型，按可吸收/不可吸收打分。 */
  private static Score score(
      List<Direction> order,
      PhasePlanner.Phases phases,
      Map<String, Integer> delta,
      Map<String, PeriodicTemplate> templates,
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
      TimetableConflictChecker.GraphIndex index,
      int separationSeconds,
      int maxWaitSeconds) {
    List<TimetableConflictChecker.Movement> movements = new ArrayList<>();
    // 每条流从哪个站台组始发——这就是残余判据要的"后车在哪等"。手上没有成品表，但这一条是知道的。
    Map<String, String> waitingPoints = new LinkedHashMap<>();
    for (Direction direction : order) {
      PeriodicTemplate template = templates.get(direction.key());
      if (template == null) {
        continue;
      }
      int phase =
          phases.phaseByDirection().getOrDefault(direction.key(), 0)
              + delta.getOrDefault(direction.key(), 0);
      List<TimetableConflictChecker.Movement> unrolled = template.unroll(phase, CYCLES);
      // 起点站台组判不出来的就不记：留成"判不出"退回按资源保守判，别让空串被当成车库。
      if (!direction.originGroup().isBlank()) {
        for (TimetableConflictChecker.Movement movement : unrolled) {
          waitingPoints.putIfAbsent(movement.code(), direction.originGroup());
        }
      }
      movements.addAll(unrolled);
    }
    if (movements.isEmpty()) {
      return new Score(0, 0, 0L);
    }
    TimetableConflictChecker.Report report =
        TimetableConflictChecker.check(index, profiles, movements, List.of(), separationSeconds);
    // 与 builder 的成功判据同一把尺子：都按"后车在哪等"判，而不是按冲突落在哪个资源上。
    // 这里没有成品表，但每条流的始发站台组是知道的，足够喂进同一条判据。
    List<ConflictAbsorption.Residual> residuals =
        ConflictAbsorption.classify(
            report, waitingPoints, index, separationSeconds, maxWaitSeconds);
    int unabsorbable = ConflictAbsorption.unabsorbable(residuals).size();
    long wait = residuals.stream().mapToLong(ConflictAbsorption.Residual::waitSeconds).sum();
    return new Score(unabsorbable, report.conflicts().size(), wait);
  }
}
