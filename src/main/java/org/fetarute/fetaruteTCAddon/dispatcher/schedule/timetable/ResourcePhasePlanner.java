package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 * <p>典型的两类冲突：两个组在合流点汇合之后前后贴得太近，沿共用区段一路相撞；车库咽喉的单线上「出库流 × 回库流」相撞。 这两件事是同一件事——<b>两条周期流在同一资源上的
 * 相对相位</b>，而第二层的"共用起点"只是它的一个特例。
 *
 * <h2>怎么选</h2>
 *
 * <p>给每个方向选一个 δ：
 *
 * <ul>
 *   <li><b>正向</b>方向的 δ 是整组平移，不改变组内往返对的相对关系。
 *   <li><b>反向</b>方向的 δ 是<b>端点多等</b>：车到终点之后多停一会儿再发，所以上限是 {@code --max-idle} （再久运行时就把它收回库了）。相位 = 锚定 +
 *       δ，子网格与派车器都不用改，{@code readyAt} 天然吃下这段等待。
 *   <li>按车接续链、以及锚在原地折返端（正线折返点，或没有出入库线路的端点）的往返对，整体一起平移（见 {@link #unitsOf}）：前者拆开会换接车，
 *       后者单独动一边会把等待加到车只能原地折返的那一端——正线上挡后车，无库端点上超出闲置上限、接不上下一班。
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

  /**
   * 每次间隔尝试至多再完成多少个相位候选（所有单元合计）；这是搜索预算，不是物理可行性证明。
   *
   * <p>实际预算是 {@code min(本上限, 单元数 × }{@link #COMPLETION_CANDIDATES_PER_UNIT}{@code
   * )}：单元不多时每个单元的候选都试完； 单元多到封顶时先平分、余下的按优先序补，没试完的单元写进资源相位说明。
   */
  public static final int COMPLETION_CANDIDATE_LIMIT = 96;

  /** 一个单元至多有几个完整候选：δ=0、起点 ±{@link #FINE_STEPS_SECONDS} 的细档、四个间隔/5 的粗档。 */
  public static final int COMPLETION_CANDIDATES_PER_UNIT = 11;

  /**
   * 起点附近的细档。粗档（间隔/5，340 秒间隔时 68 秒一档）太粗：真实库回放里组间隔每变 5 秒，不可吸收数在 0 到 400 之间跳， 可行相位往往就在周期评估给的起点旁边几十秒内。
   */
  static final int[] FINE_STEPS_SECONDS = {10, 20, 30};

  /** 扫描步长。 */
  public static final int SCAN_STEP_SECONDS = 10;

  /**
   * 铺几个周期来评估。周期流的相对相位在整个窗口里重复，前几个周期已经代表全部。
   *
   * <p>周期数按<b>最长的那个间隔</b>算成一个共同的时间视野，所有流都铺满这一段（{@link #cyclesFor}）：间隔不同的组（例如 300 秒的干线与 600 秒的速达）
   * 各铺自己的 3 个周期，短间隔的流只覆盖 900 秒，长间隔的流的后半段就没有对手可撞，评估会把它们判成"零冲突"。
   */
  public static final int CYCLES = 3;

  /** 单条流最多铺几个周期：间隔悬殊（60 秒对 3600 秒）时视野会逼出上百个周期，冲突检查的代价跟着涨；封顶后短间隔的流覆盖不满视野，只在这些方向上保留"视野后段缺少对手"的局限。 */
  static final int MAX_CYCLES = 4 * CYCLES;

  private ResourcePhasePlanner() {}

  /**
   * 完成交路后的评分：硬冲突优先，其次取消数、发车偏移平方和、总冲突与等待量。
   *
   * <p>周期模板不含按需求补车和待命，不能替代这个判据。偏移平方和惩罚集中延后，避免只把平均频率排好。
   */
  /**
   * @param unabsorbableByDirection 方向键 → 它的班次卷进了几处不可吸收残余；不参与比较，只决定单元先后
   */
  public record CompletionScore(
      int unabsorbable,
      int dropped,
      long displacement,
      int conflicts,
      long waitSeconds,
      Map<String, Integer> unabsorbableByDirection)
      implements Comparable<CompletionScore> {

    public CompletionScore {
      unabsorbableByDirection =
          unabsorbableByDirection == null ? Map.of() : Map.copyOf(unabsorbableByDirection);
    }

    /** 不按方向分摊残余的评分。 */
    public CompletionScore(
        int unabsorbable, int dropped, long displacement, int conflicts, long waitSeconds) {
      this(unabsorbable, dropped, displacement, conflicts, waitSeconds, Map.of());
    }

    @Override
    public int compareTo(CompletionScore other) {
      int value = Integer.compare(unabsorbable, other.unabsorbable);
      if (value == 0) {
        value = Integer.compare(dropped, other.dropped);
      }
      if (value == 0) {
        value = Long.compare(displacement, other.displacement);
      }
      if (value == 0) {
        value = Integer.compare(conflicts, other.conflicts);
      }
      return value == 0 ? Long.compare(waitSeconds, other.waitSeconds) : value;
    }
  }

  /** 按完整派车、出入库与等待投影重选相位；评分器必须返回对应相位实际构建的结果。 */
  public static PhasePlanner.Phases refineCompleted(
      PhasePlanner.Phases phases,
      List<ServiceGroupClassifier.Group> groups,
      Map<String, Integer> intervals,
      int maxIdleSeconds,
      Set<UUID> inPlaceTurnbackRoutes,
      java.util.function.Function<PhasePlanner.Phases, CompletionScore> complete) {
    return refineCompleted(
        phases,
        groups,
        intervals,
        maxIdleSeconds,
        inPlaceTurnbackRoutes,
        complete,
        BuildPool.serial());
  }

  /**
   * 同上，一个单元的各候选并行评分。
   *
   * <p>单元内各候选只差这个单元的 δ，互不依赖；评完按原顺序逐个比，并列仍保留先评的那个，挑选与串行相同。单元之间有先后（后面的单元以前面定下的 δ 为起点），仍逐个来。
   *
   * <p>单元先后按它在起点评分的不可吸收残余里出现的次数排，多的先定，平局按原来的组名序：卡住的单元先挑， 后面的单元在它定下的相位上再挑。候选预算按单元分（{@link
   * #budgets}），前面的单元吃不掉后面单元的份额。
   *
   * @param pool 并行池；{@code complete} 会在池内线程上被并发调用，必须线程安全
   */
  public static PhasePlanner.Phases refineCompleted(
      PhasePlanner.Phases phases,
      List<ServiceGroupClassifier.Group> groups,
      Map<String, Integer> intervals,
      int maxIdleSeconds,
      Set<UUID> inPlaceTurnbackRoutes,
      java.util.function.Function<PhasePlanner.Phases, CompletionScore> complete,
      BuildPool pool) {
    List<Direction> order = orderOf(groups, phases, intervals);
    Map<String, Integer> delta = new LinkedHashMap<>(phases.deltaByDirection());
    PhasePlanner.Phases bestPhases = phases;
    CompletionScore best = complete.apply(phases);
    List<String> notes = new ArrayList<>(phases.resourceNotes());
    List<Unit> units =
        prioritized(
            unitsOf(order, groups, phases.connections(), inPlaceTurnbackRoutes),
            best.unabsorbableByDirection());
    // 单元的 δ 只在轮到它时才改，起点与候选在开始前就定了。
    List<List<Integer>> candidates = new ArrayList<>(units.size());
    for (Unit unit : units) {
      int limit = unit.reverse() ? Math.min(maxIdleSeconds, unit.interval()) : unit.interval();
      candidates.add(
          candidatesOf(delta.getOrDefault(unit.keys().get(0), 0), unit.interval(), limit));
    }
    int[] budgets = budgets(candidates.stream().mapToInt(List::size).toArray());
    List<String> shortened = new ArrayList<>();
    for (int u = 0; u < units.size(); u++) {
      Unit unit = units.get(u);
      int start = delta.getOrDefault(unit.keys().get(0), 0);
      List<Integer> tried = candidates.get(u).subList(0, budgets[u]);
      if (budgets[u] < candidates.get(u).size()) {
        shortened.add(
            String.join(" + ", unit.keys())
                + " 少试 "
                + (candidates.get(u).size() - budgets[u])
                + " 个");
      }
      List<PhasePlanner.Phases> trials = new ArrayList<>(tried.size());
      for (int candidate : tried) {
        Map<String, Integer> trial = new LinkedHashMap<>(delta);
        for (String key : unit.keys()) {
          trial.put(key, candidate);
        }
        trials.add(withDelta(phases, trial, notes));
      }
      List<BuildPool.Evaluated<CompletionScore>> scores = pool.evaluate(trials, complete);
      int chosen = start;
      for (int i = 0; i < scores.size(); i++) {
        CompletionScore score = scores.get(i).get();
        // 禁止通过额外取消班次换取更低冲突数；并列保留原锚定相位。
        if (score.dropped() <= best.dropped() && score.compareTo(best) < 0) {
          best = score;
          chosen = tried.get(i);
          bestPhases = trials.get(i);
        }
      }
      for (String key : unit.keys()) {
        delta.put(key, chosen);
      }
      if (chosen != start) {
        notes.add(
            String.join(" + ", unit.keys())
                + " 完整交路相位 "
                + chosen
                + "s（让不掉的 "
                + best.unabsorbable()
                + " 处）");
      }
    }
    if (!shortened.isEmpty()) {
      notes.add(
          "完整交路相位候选预算 "
              + COMPLETION_CANDIDATE_LIMIT
              + " 个用完，没试完的单元："
              + String.join("、", shortened));
    }
    return bestPhases.equals(phases) && shortened.isEmpty()
        ? phases
        : withDelta(phases, delta, notes);
  }

  /**
   * 一个单元的完整候选，按试的先后：δ=0、起点 ±10/±20/±30 秒的细档、起点 + 间隔/5 的四个粗档（取模）。去掉起点本身与落在 [0, {@code limit}]
   * 之外的，去重。
   */
  static List<Integer> candidatesOf(int start, int interval, int limit) {
    Set<Integer> out = new java.util.LinkedHashSet<>();
    out.add(0);
    for (int step : FINE_STEPS_SECONDS) {
      out.add(start - step);
      out.add(start + step);
    }
    for (int fraction = 1; fraction < 5; fraction++) {
      out.add((int) Math.floorMod((long) start + (long) fraction * interval / 5, interval));
    }
    out.removeIf(candidate -> candidate == start || candidate < 0 || candidate > limit);
    return List.copyOf(out);
  }

  /**
   * 各单元能试几个候选。总预算 {@code min(}{@link #COMPLETION_CANDIDATE_LIMIT}{@code , 单元数 × }{@link
   * #COMPLETION_CANDIDATES_PER_UNIT}{@code )}：先给每个单元平分的一份（不超过它的候选数），余下的按单元先后补满。
   *
   * @param sizes 各单元的候选数，按单元先后
   */
  static int[] budgets(int[] sizes) {
    int total =
        (int)
            Math.min(
                COMPLETION_CANDIDATE_LIMIT, (long) sizes.length * COMPLETION_CANDIDATES_PER_UNIT);
    int[] out = new int[sizes.length];
    if (sizes.length == 0) {
      return out;
    }
    int fair = total / sizes.length;
    int left = total;
    for (int i = 0; i < sizes.length; i++) {
      out[i] = Math.min(sizes[i], fair);
      left -= out[i];
    }
    for (int i = 0; i < sizes.length && left > 0; i++) {
      int extra = Math.min(left, sizes[i] - out[i]);
      out[i] += extra;
      left -= extra;
    }
    return out;
  }

  /** 单元按卷进不可吸收残余的次数从多到少排；稳定排序，平局保持原来的组名序。 */
  private static List<Unit> prioritized(List<Unit> units, Map<String, Integer> byDirection) {
    if (byDirection.isEmpty()) {
      return units;
    }
    List<Unit> out = new ArrayList<>(units);
    out.sort(
        java.util.Comparator.comparingInt(
            (Unit unit) ->
                -unit.keys().stream().mapToInt(key -> byDirection.getOrDefault(key, 0)).sum()));
    return List.copyOf(out);
  }

  private static PhasePlanner.Phases withDelta(
      PhasePlanner.Phases base, Map<String, Integer> delta, List<String> notes) {
    return new PhasePlanner.Phases(
        base.phaseByDirection(),
        base.offsetByGroup(),
        delta,
        base.notes(),
        notes,
        base.connections());
  }

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
   * @param maxIdleSeconds 反向 δ 的上限（端点多等不能超过运行时的闲置回收）；另外不超过一个间隔
   * @param inPlaceTurnbackRoutes 终到原地折返端的 route：含它们的往返对锚在这一端，只能整对平移
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
      int maxIdleSeconds,
      Set<UUID> inPlaceTurnbackRoutes) {
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
    int horizonSeconds = horizonOf(order);
    for (Unit unit : unitsOf(order, groups, phases.connections(), inPlaceTurnbackRoutes)) {
      // 多等超过一个间隔与减去一个间隔后的周期完全相同，只会多等；回收关着时闲置上限是一整天，不夹住就要扫上千次。
      int limit =
          unit.reverse() ? Math.max(0, Math.min(maxIdleSeconds, unit.interval())) : unit.interval();
      Score best = null;
      int bestDelta = 0;
      for (int candidate = 0; candidate <= limit; candidate += SCAN_STEP_SECONDS) {
        for (String key : unit.keys()) {
          delta.put(key, candidate);
        }
        Score score =
            score(
                order,
                phases,
                delta,
                templates,
                profiles,
                index,
                separationSeconds,
                maxWaitSeconds,
                horizonSeconds);
        // δ=0 先被评，并列时保持它——端点零等待是缺省，只有真能减少不可吸收数才付出等待。
        if (best == null || score.compareTo(best) < 0) {
          best = score;
          bestDelta = candidate;
        }
      }
      for (String key : unit.keys()) {
        delta.put(key, bestDelta);
      }
      if (bestDelta > 0) {
        notes.add(
            String.format(
                Locale.ROOT,
                "%s %s %ds（周期评估：让不掉的 %d 处、共 %d 处）",
                String.join(" + ", unit.keys()),
                unit.reverse() ? "端点多等" : (unit.keys().size() > 1 ? "整体平移" : "整组平移"),
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
        List.copyOf(notes),
        phases.connections());
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

  /**
   * 一次一起取 δ 的方向。
   *
   * @param keys 方向键；多于一个时是一条按车接续链，整体平移
   * @param interval 间隔
   * @param reverse 单个往返对反向方向：δ 是端点多等，上限是闲置上限
   */
  private record Unit(List<String> keys, int interval, boolean reverse) {}

  /**
   * 按车接续把几个方向的相位钉在一起（喂车方向到站 + 折返 = 被接方向发车；被接方向的往返对反向在远端多等）， 单独给其中任何一个 δ
   * 都会把接续拆开——拆开之后派车器会改接另一辆车，交路形态整个翻掉。所以接续链上的方向 （含它们各自的往返对）并成一个单元，只能一起平移；其余方向照旧一个一个定。
   *
   * <p>锚在原地折返端的往返对同样整对平移：那一端的停留 = 折返 + 反向 δ − 正向 δ，单独给任何一边 δ 都会把等待加回车只能原地折返的一端。
   */
  private static List<Unit> unitsOf(
      List<Direction> order,
      List<ServiceGroupClassifier.Group> groups,
      List<PhasePlanner.Connection> connections,
      Set<UUID> inPlaceTurnbackRoutes) {
    Map<String, String> parent = new LinkedHashMap<>();
    for (Direction direction : order) {
      parent.put(direction.key(), direction.key());
    }
    Set<String> linked = new HashSet<>();
    for (PhasePlanner.Connection connection : connections) {
      union(parent, connection.feederKey(), connection.fedKey());
      linked.add(connection.feederKey());
    }
    for (ServiceGroupClassifier.Group group : groups) {
      for (ServiceGroupClassifier.Direction direction : group.directions()) {
        if (PhasePlanner.hasAnyRoute(direction, inPlaceTurnbackRoutes)) {
          linked.add(direction.key());
        }
      }
    }
    if (!linked.isEmpty()) {
      for (ServiceGroupClassifier.Group group : groups) {
        for (ServiceGroupClassifier.Direction direction : group.directions()) {
          union(parent, direction.key(), direction.reverseKey());
        }
      }
    }
    Set<String> linkedRoots = new HashSet<>();
    for (String key : linked) {
      if (parent.containsKey(key)) {
        linkedRoots.add(find(parent, key));
      }
    }
    List<Unit> out = new ArrayList<>();
    Map<String, List<String>> chains = new LinkedHashMap<>();
    for (Direction direction : order) {
      String root = find(parent, direction.key());
      if (!linkedRoots.contains(root)) {
        out.add(new Unit(List.of(direction.key()), direction.interval(), direction.reverse()));
        continue;
      }
      List<String> chain = chains.get(root);
      if (chain == null) {
        chain = new ArrayList<>();
        chains.put(root, chain);
        // 占住它在遍历序里第一次出现的位置，整条链在这里一起定。
        out.add(new Unit(chain, direction.interval(), false));
      }
      chain.add(direction.key());
    }
    List<Unit> frozen = new ArrayList<>(out.size());
    for (Unit unit : out) {
      frozen.add(new Unit(List.copyOf(unit.keys()), unit.interval(), unit.reverse()));
    }
    return List.copyOf(frozen);
  }

  private static void union(Map<String, String> parent, String a, String b) {
    if (!parent.containsKey(a) || !parent.containsKey(b)) {
      return;
    }
    String ra = find(parent, a);
    String rb = find(parent, b);
    if (!ra.equals(rb)) {
      // 键小的当根：结果与遍历序无关。
      if (ra.compareTo(rb) < 0) {
        parent.put(rb, ra);
      } else {
        parent.put(ra, rb);
      }
    }
  }

  private static String find(Map<String, String> parent, String key) {
    String root = key;
    while (!parent.get(root).equals(root)) {
      root = parent.get(root);
    }
    return root;
  }

  /** 评估视野：{@link #CYCLES} 个最长间隔。 */
  private static int horizonOf(List<Direction> order) {
    int longest = order.stream().mapToInt(Direction::interval).max().orElse(0);
    return CYCLES * longest;
  }

  /** 这条流在共同视野里要铺几个周期：至少 {@link #CYCLES} 个，间隔更短的流铺得更多，好覆盖同一段时间。 */
  static int cyclesFor(int intervalSeconds, int horizonSeconds) {
    if (intervalSeconds <= 0) {
      return CYCLES;
    }
    return Math.min(MAX_CYCLES, Math.max(CYCLES, Math.ceilDiv(horizonSeconds, intervalSeconds)));
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
      int maxWaitSeconds,
      int horizonSeconds) {
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
      List<TimetableConflictChecker.Movement> unrolled =
          template.unroll(phase, cyclesFor(direction.interval(), horizonSeconds));
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
