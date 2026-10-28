package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 周期层求解：一个制式（各组间隔都不变的时段）里，给每条周期流（交路组的一个方向）定相位、给各条 route 的途中车站定多停， 使全部班次与站台停留在超周期内不超任何资源的容量。
 *
 * <p>为什么在周期层做：全天的表在稳态里每个超周期都一样，冲突也每圈都一样。在一个超周期的环上一次排开，比编完整天再逐处让车、
 * 再按完整构建的结果挑相位便宜几个数量级（毫秒级对十几秒），而且能看见完整构建的局部修复看不见的东西——让慢车在某站多停一会儿， 后面整段都错开了。
 *
 * <p>模型（与 {@link TimetableConflictChecker} 同一套资源与容量）：
 *
 * <ul>
 *   <li>超周期 {@code H} 是各流"间隔 × SWRR 一轮的格数"的最小公倍数，太大时退成各间隔的最小公倍数；时间对 {@code H} 取模成环。
 *   <li>一班车的每个足迹格占 {@code [发车 + 进入, 发车 + 离开 + 裕量)}；在途中可多停的车站多停 {@code h} 秒，这一站之后的格整体后移、
 *       这一站的站台格加长，与运行时早到扣留兑现表上加长的停站一致。
 *   <li>端点上的停留（折返、待命）按车辆先到先走配对：到达 + 折返之后最早就绪的车接下一班（正线折返点取最晚就绪，与派车同一规则），
 *       同一辆车从到达到发车占着站台；等不到下一班、又能回库的车在折返后走回库走行离开，没有到达车可接的班次从车库走出库走行来。
 *       出入库走行不算硬冲突（它们在运行时可以让、可以在库里等），只在冲突数、碰邻表数之后作排序依据：能不撞就不撞。
 *       一条交路只跑一班时不配对，每班都出库、回库。原地折返端（正线折返点、没有回库线路的终点）上停留超过折返的秒数也是代价：
 *       车在正线上挡后车、或调不走，往返对应锚在这一端的最短折返，余量放到有车库的另一端。正线折返点总是锚定；没有回库线路的终点
 *       锚与不锚各解一次，按窗口两端落空的班数取舍（锚定会把多停挤到别处，只在末班带不走车时才值得）。
 *   <li>邻表的占用折到环上单独一层：不占本表容量（让车由预订表与终判按路权处理），候选排序里紧跟冲突数之后，能避开就避开。
 * </ul>
 *
 * <p>搜索：逐流放置，每个相位都沿停靠顺序做"最早可行多停"（哪一格撞上，就把它之前最近那一站的多停加到刚好避开，超上限算冲突），
 * 按（冲突，碰邻表数，乘客受影响的多停秒数，原地折返多等的秒数，碰出入库走行数，合流点合成班距，偏离，相位）字典序排出前几个候选；
 * 先按排第一的贪心走到底，运营与走行都排开即采用，否则在扫描预算（{@link #BACKTRACK_SCAN_FACTOR}）内回溯，按（冲突，走行碰撞，多停）
 * 保留最好的。预算按次数计，不看墙钟，同一份输入永远给出同一个解。
 *
 * <p>快车：同一份输入按两种放置顺序各解一次——自然顺序（快车跟着别人放，被卡就自己多停）与快车优先（慢车围着快车放，
 * 在有另一股道的车站多停让快车越过，即待避越行）。取冲突更少、其次乘客多停总秒数更少的那份：只有快车少等的秒数多于慢车多停的秒数， 快车优先才会胜出。
 */
final class PeriodicSolver {

  /** 每条流进入回溯的候选数。 */
  static final int BEAM = 6;

  /**
   * 回溯预算：贪心走到底之后，只有仍有冲突时才回溯；回溯里所有节点合计扫描的相位数不超过"流数 × 最长间隔的扫描格数 × 这个倍数"，
   * 到上限即交回已知最好的解。按次数计，不看墙钟，同一份输入永远给出同一个解。
   */
  static final int BACKTRACK_SCAN_FACTOR = 2;

  /**
   * 超周期上限：各流"间隔 × SWRR 一轮"的最小公倍数超过它时退成各间隔的最小公倍数（SWRR 一轮按格号取模近似）；各间隔的最小公倍数也超过它时 不解（{@link
   * HyperperiodTooLong}）。环按秒给每个资源一份计数，超周期就是它的长度。
   */
  static final int MAX_HYPERPERIOD = 4 * 3600;

  /**
   * 一班接不上车（无库端点等不到下一班、起点出不了车、车卡在没有回库线路的终点）在比较里抵几处资源冲突。
   *
   * <p>资源冲突在运行时多半等一会儿就让掉了，至多撤掉相撞的那一班；接不上车的格每个超周期都接不上，派车会把这条交路全天的班次一路退掉。 两者同权时，求解器会为少撞一两处而拆掉整组的接续。
   */
  static final int STRANDED_WEIGHT = 64;

  /** 各组间隔的最小公倍数超过 {@link #MAX_HYPERPERIOD}：环放不下，这组间隔不解。 */
  static final class HyperperiodTooLong extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    HyperperiodTooLong(String message) {
      super(message);
    }
  }

  /** 相位扫描的格数上限：间隔很长时按步长扫，步长取整到秒。 */
  static final int MAX_PHASE_STEPS = 900;

  /** 一次放置里"多停 → 重查"的轮数上限。 */
  private static final int MAX_HOLD_ROUNDS = 32;

  private PeriodicSolver() {}

  /**
   * 一条周期流。
   *
   * @param key 方向键
   * @param group 交路组名
   * @param interval 间隔（秒）
   * @param cycle SWRR 一轮里各格的 route（按格号）
   * @param fast 含快车交路（无越行共线段上明显更快）
   */
  record Flow(String key, String group, int interval, List<UUID> cycle, boolean fast) {
    Flow {
      Objects.requireNonNull(key, "key");
      group = group == null ? "" : group;
      cycle = List.copyOf(cycle);
      if (interval <= 0 || cycle.isEmpty()) {
        throw new IllegalArgumentException("周期流须有正间隔与至少一条 route: " + key);
      }
    }
  }

  /**
   * 一条 route 在周期层的形状。
   *
   * @param routeId route
   * @param pool 车池（一辆车不跨池接班）
   * @param cells 运行足迹（相对发车；起终点的站台由停留负责，不在其中）
   * @param holdDepartures 可多停车站的发车偏移，升序
   * @param holdSequences 与 {@code holdDepartures} 对齐的停靠序号（{@code RouteStop#sequence}）
   * @param arrivalOffset 到达终点的偏移
   * @param origin 首站站台
   * @param terminal 末站站台
   * @param turnaround 终到折返
   * @param returnsAtTerminal 终点有回库线路（等不到下一班可以回库）
   * @param suppliedAtOrigin 起点有出库线路（没有到达车可接时从车库出车）
   * @param latestFirstAtOrigin 起点是正线折返点：接班取最晚就绪的车
   * @param calls 沿途合流点（乘客均匀度）
   * @param create 起点的出库走行（没有到达车可接、从车库出车时走它）；起点就是车库或没有出库线路时为空
   * @param back 终点的回库走行（等不到下一班、回库时走它）；以车库收尾或没有回库线路时为空
   * @param blocksAtTerminal 终点是正线折返点：车停着挡同一股道的后车，停留越短越好
   * @param originNode 首站节点：首站不是站台（正线折返点等区间路径点）时按它配对接班
   * @param terminalNode 末站节点：同上
   */
  record Shape(
      UUID routeId,
      String pool,
      List<TimetableConflictChecker.Slot> cells,
      int[] holdDepartures,
      int[] holdSequences,
      int arrivalOffset,
      Optional<TimetableConflictChecker.Platform> origin,
      Optional<TimetableConflictChecker.Platform> terminal,
      int turnaround,
      boolean returnsAtTerminal,
      boolean suppliedAtOrigin,
      boolean latestFirstAtOrigin,
      List<PhasePlanner.StopCall> calls,
      Optional<Leg> create,
      Optional<Leg> back,
      boolean blocksAtTerminal,
      String originNode,
      String terminalNode) {
    Shape {
      Objects.requireNonNull(routeId, "routeId");
      pool = pool == null ? "" : pool;
      cells = List.copyOf(cells);
      holdDepartures = holdDepartures.clone();
      holdSequences = holdSequences.clone();
      origin = origin == null ? Optional.empty() : origin;
      terminal = terminal == null ? Optional.empty() : terminal;
      calls = calls == null ? List.of() : List.copyOf(calls);
      create = create == null ? Optional.empty() : create;
      back = back == null ? Optional.empty() : back;
      originNode = originNode == null ? "" : originNode;
      terminalNode = terminalNode == null ? "" : terminalNode;
      if (holdDepartures.length != holdSequences.length) {
        throw new IllegalArgumentException("多停站的偏移与序号须对齐");
      }
    }

    /** 不带出入库走行、终点不挡道的形状（合成夹具用）。 */
    Shape(
        UUID routeId,
        String pool,
        List<TimetableConflictChecker.Slot> cells,
        int[] holdDepartures,
        int[] holdSequences,
        int arrivalOffset,
        Optional<TimetableConflictChecker.Platform> origin,
        Optional<TimetableConflictChecker.Platform> terminal,
        int turnaround,
        boolean returnsAtTerminal,
        boolean suppliedAtOrigin,
        boolean latestFirstAtOrigin,
        List<PhasePlanner.StopCall> calls) {
      this(
          routeId,
          pool,
          cells,
          holdDepartures,
          holdSequences,
          arrivalOffset,
          origin,
          terminal,
          turnaround,
          returnsAtTerminal,
          suppliedAtOrigin,
          latestFirstAtOrigin,
          calls,
          Optional.empty(),
          Optional.empty(),
          false,
          "",
          "");
    }

    /** 首站的配对键：站台组；首站不是站台时取节点本身。 */
    String originGroup() {
      return origin.map(TimetableConflictChecker.Platform::group).orElse(originNode);
    }

    /** 末站的配对键：站台组；末站不是站台时取节点本身。 */
    String terminalGroup() {
      return terminal.map(TimetableConflictChecker.Platform::group).orElse(terminalNode);
    }
  }

  /**
   * 一段出入库走行在周期层的形状。
   *
   * @param cells 足迹（相对走行出发）
   * @param run 走行时分（出发到终点）
   * @param ready 出库走行到首站后的就绪时间（{@code VehicleDutyPlanner#openingStart} 同一口径）；回库走行为 0
   */
  record Leg(List<TimetableConflictChecker.Slot> cells, int run, int ready) {
    Leg {
      cells = List.copyOf(cells);
    }
  }

  /**
   * 不可移动的一格占用（邻表）。
   *
   * @param cell 资源格
   * @param start 它的偏移基准：实际占用 {@code [start + from, start + to + 裕量)}
   */
  record Fixed(TimetableConflictChecker.Slot cell, int start) {}

  /**
   * 求解输入。
   *
   * @param flows 周期流
   * @param shapes route → 形状
   * @param footprints 足迹与站台资格（与冲突检查同一份图索引）
   * @param separation 裕量
   * @param maxIdle 端点闲置上限
   * @param holdLimit 单站多停上限（0 不多停）
   * @param preferred 各流的参考相位（上一个制式的解）；没有的取 0
   * @param maxTripsPerDuty 一条交路最多几班：为 1 时每班都从车库出、跑完回库，端点上不配对接班
   * @param fixed 邻表的占用（不可移动，按超周期折到环上）
   * @param horizonSeconds 计划窗口长度：环上的解整体转多少落到窗口里（{@link #rotated}）；0 表示不转
   * @param keepPreferred 相位固定为参考相位、不重选的流（分时段编表里间隔没变的组：交界处与上一段连续）
   */
  record Input(
      List<Flow> flows,
      Map<UUID, Shape> shapes,
      TimetableConflictChecker.Footprints footprints,
      int separation,
      int maxIdle,
      int holdLimit,
      Map<String, Integer> preferred,
      int maxTripsPerDuty,
      List<Fixed> fixed,
      int horizonSeconds,
      Set<String> keepPreferred) {
    Input {
      keepPreferred = keepPreferred == null ? Set.of() : Set.copyOf(keepPreferred);
      flows = List.copyOf(flows);
      shapes = Map.copyOf(shapes);
      Objects.requireNonNull(footprints, "footprints");
      separation = Math.max(0, separation);
      maxIdle = Math.max(0, maxIdle);
      holdLimit = Math.max(0, holdLimit);
      preferred = preferred == null ? Map.of() : Map.copyOf(preferred);
      maxTripsPerDuty = Math.max(1, maxTripsPerDuty);
      fixed = fixed == null ? List.of() : List.copyOf(fixed);
      horizonSeconds = Math.max(0, horizonSeconds);
    }

    /** 交路不限班数、没有邻表（合成夹具用）。 */
    Input(
        List<Flow> flows,
        Map<UUID, Shape> shapes,
        TimetableConflictChecker.Footprints footprints,
        int separation,
        int maxIdle,
        int holdLimit,
        Map<String, Integer> preferred) {
      this(
          flows,
          shapes,
          footprints,
          separation,
          maxIdle,
          holdLimit,
          preferred,
          Integer.MAX_VALUE,
          List.of(),
          0,
          Set.of());
    }
  }

  /**
   * 解。
   *
   * @param phases 方向键 → 相位
   * @param holds route → 停靠序号 → 多停秒数（只含大于 0 的）
   * @param conflicts 超周期内仍撞上的格数加上接不上车的班数（0 = 周期层排开）
   * @param stranded 其中接不上车的班数（比较时按 {@link #STRANDED_WEIGHT} 加权）
   * @param depotConflicts 出入库走行（软占用）碰上运营占用的格数：冲突数相同时越少越好
   * @param heldSeconds 超周期内乘客受影响的多停秒数之和（按班计）
   * @param hyperperiod 超周期
   * @param nodes 展开的节点数
   * @param fastFirst 采用的是快车优先的放置顺序
   * @param feeders 跨组接班：被接 route → 喂车 route（交给派车做接续偏好）
   */
  record Solution(
      Map<String, Integer> phases,
      Map<UUID, Map<Integer, Integer>> holds,
      int conflicts,
      int stranded,
      int depotConflicts,
      long heldSeconds,
      int hyperperiod,
      int nodes,
      boolean fastFirst,
      Map<UUID, Set<UUID>> feeders) {
    Solution {
      phases = Map.copyOf(phases);
      Map<UUID, Map<Integer, Integer>> copy = new LinkedHashMap<>();
      holds.forEach((route, bySequence) -> copy.put(route, Map.copyOf(bySequence)));
      holds = Map.copyOf(copy);
      Map<UUID, Set<UUID>> feederCopy = new LinkedHashMap<>();
      feeders.forEach((route, from) -> feederCopy.put(route, Set.copyOf(from)));
      feeders = Map.copyOf(feederCopy);
    }

    /** 比较用的冲突分：接不上车的班按 {@link #STRANDED_WEIGHT} 计。 */
    long score() {
      return (long) conflicts - stranded + (long) stranded * STRANDED_WEIGHT;
    }

    /** 报告用的一句话。 */
    String describe() {
      StringBuilder out = new StringBuilder();
      out.append(
          String.format(
              Locale.ROOT,
              "周期层求解：超周期 %d s、%d 个方向，%s，展开 %d 个节点；",
              hyperperiod,
              phases.size(),
              fastFirst ? "快车优先放置（慢车待避越行）" : "按约束强度放置",
              nodes));
      out.append(conflicts == 0 ? "超周期内排开" : "超周期内仍有 " + conflicts + " 处撞上，交给让车与残余判定");
      if (stranded > 0) {
        out.append("（其中 ").append(stranded).append(" 班在端点接不上车）");
      }
      long held =
          holds.values().stream()
              .flatMap(map -> map.values().stream())
              .mapToLong(Integer::longValue)
              .sum();
      if (held > 0) {
        out.append("；途中多停 ").append(held).append(" s（各班相同）");
      }
      return out.toString();
    }
  }

  /**
   * 求解：放置顺序（自然、快车优先）× 是否把没有回库线路的终点锚在最短折返，各解一次、各自整体旋转，取最好的那份。
   *
   * <p>好坏按字典序：环上冲突数，窗口两端落空的班数（{@link #edgeLoss}），出入库走行碰撞数，乘客受影响的多停秒数，最后按尝试的先后。
   * 锚定只是搜索的偏好（候选排序里压短原地折返端的停留），它的用处在窗口两端：停留短，末班到了还有车次把车带走。锚不锚哪个更好由这些
   * 结果说话，而不是写死——锚定会把多停挤到别处。没有原地折返端时锚不锚一样，只解一种。
   */
  static Solution solve(Input input) {
    Objects.requireNonNull(input, "input");
    if (input.flows().isEmpty()) {
      return new Solution(Map.of(), Map.of(), 0, 0, 0, 0L, 0, 0, false, Map.of());
    }
    int hyperperiod = hyperperiodOf(input.flows());
    Map<String, Flow> flows = new TreeMap<>();
    input.flows().forEach(flow -> flows.put(flow.key(), flow));
    List<Flow> natural = naturalOrder(input.flows());
    List<List<Flow>> orders = new ArrayList<>();
    orders.add(natural);
    if (natural.stream().anyMatch(Flow::fast)) {
      List<Flow> fastFirst = new ArrayList<>();
      natural.stream().filter(Flow::fast).forEach(fastFirst::add);
      natural.stream().filter(flow -> !flow.fast()).forEach(fastFirst::add);
      if (!fastFirst.equals(natural)) {
        orders.add(fastFirst);
      }
    }
    boolean inPlaceEnds =
        input.shapes().values().stream().anyMatch(shape -> !shape.returnsAtTerminal());
    List<Boolean> anchors = inPlaceEnds ? List.of(true, false) : List.of(true);
    Solution best = null;
    int bestLoss = Integer.MAX_VALUE;
    for (boolean anchor : anchors) {
      for (List<Flow> order : orders) {
        Solution solution =
            rotated(
                new Search(input, hyperperiod, order, order != natural, anchor).run(),
                input,
                hyperperiod);
        int loss = edgeLoss(solution, input, flows, 0);
        if (best == null || better(solution, loss, best, bestLoss)) {
          best = solution;
          bestLoss = loss;
        }
      }
    }
    return best;
  }

  private static boolean better(Solution a, int lossA, Solution b, int lossB) {
    if (a.score() != b.score()) {
      return a.score() < b.score();
    }
    if (lossA != lossB) {
      return lossA < lossB;
    }
    if (a.depotConflicts() != b.depotConflicts()) {
      return a.depotConflicts() < b.depotConflicts();
    }
    return a.heldSeconds() < b.heldSeconds();
  }

  /** 解整体转 {@code r} 之后，本表运行碰到邻表占用的格数（没有邻表时恒为 0）。 */
  private static final class NeighborTouches {
    private final Solution solution;
    private final Map<String, Flow> flows;
    private final int separation;
    private final Ring ring;
    private final Map<UUID, Compiled> compiled = new HashMap<>();
    private final Map<UUID, int[]> prefixes = new HashMap<>();

    NeighborTouches(Solution solution, Input input, Map<String, Flow> flows, int hyperperiod) {
      this.solution = solution;
      this.flows = flows;
      this.separation = input.separation();
      this.ring = new Ring(hyperperiod);
      for (Fixed fixed : input.fixed()) {
        TimetableConflictChecker.Slot cell = fixed.cell();
        ring.markFixed(
            ring.idOf(cell),
            fixed.start() + cell.from(),
            fixed.start() + cell.to() + TimetableConflictChecker.marginOf(cell.key(), separation),
            cell.direction());
      }
      if (!ring.hasFixed()) {
        return;
      }
      for (Flow flow : flows.values()) {
        for (UUID routeId : flow.cycle()) {
          Shape shape = input.shapes().get(routeId);
          if (shape == null || compiled.containsKey(routeId)) {
            continue;
          }
          Compiled route = new Compiled(shape, ring, separation);
          int[] hold = new int[route.holdCount()];
          Map<Integer, Integer> bySequence = solution.holds().getOrDefault(routeId, Map.of());
          for (int j = 0; j < hold.length; j++) {
            hold[j] = bySequence.getOrDefault(shape.holdSequences()[j], 0);
          }
          compiled.put(routeId, route);
          prefixes.put(routeId, route.prefix(hold));
        }
      }
    }

    int at(int r) {
      if (!ring.hasFixed()) {
        return 0;
      }
      int count = 0;
      for (Map.Entry<String, Integer> entry : solution.phases().entrySet()) {
        Flow flow = flows.get(entry.getKey());
        if (flow == null) {
          continue;
        }
        int phase = Math.floorMod(entry.getValue() + r, flow.interval());
        int slots = ring.period / flow.interval();
        for (int k = 0; k < slots; k++) {
          UUID routeId = flow.cycle().get(k % flow.cycle().size());
          Compiled route = compiled.get(routeId);
          if (route == null) {
            continue;
          }
          int[] prefix = prefixes.get(routeId);
          int departure = phase + k * flow.interval();
          for (int c = 0; c < route.size(); c++) {
            if (ring.touchesFixed(
                route.res[c],
                departure + route.from[c] + prefix[route.startAt[c]],
                departure + route.to[c] + prefix[route.endAt[c]] + route.margin[c],
                route.dir[c])) {
              count++;
            }
          }
        }
      }
      return count;
    }
  }

  /** 整体旋转的扫描格数上限：超周期很长时按步长转，步长取整到秒。 */
  static final int MAX_ROTATION_STEPS = 720;

  /**
   * 把环上的解整体转一个角度落到计划窗口里：所有相位同加 {@code r}（各对自己的间隔取模），环上的相对位置不变，冲突与多停都不变；
   * 变的只是窗口从环上哪一点切开（邻表不随本表转，所以碰到邻表更多的角度不取）。切点决定首末班的边缘：原地折返端（起点出不了车）的第一班有没有车接、没有回库线路的终点上最后一辆车
   * 后面还有没有车次带走它。按窗口里先到先走配对数出这两种落空的班数，取最少的，平局取最小的 {@code r}。
   */
  static Solution rotated(Solution solution, Input input, int hyperperiod) {
    if (input.horizonSeconds() <= 0 || solution.phases().isEmpty()) {
      return solution;
    }
    Map<String, Flow> flows = new TreeMap<>();
    input.flows().forEach(flow -> flows.put(flow.key(), flow));
    int step = Math.max(1, (hyperperiod + MAX_ROTATION_STEPS - 1) / MAX_ROTATION_STEPS);
    NeighborTouches touches = new NeighborTouches(solution, input, flows, hyperperiod);
    int unrotated = touches.at(0);
    int best = 0;
    int bestLoss = Integer.MAX_VALUE;
    for (int r = 0; r < hyperperiod; r += step) {
      if (!keepsCycleAlignment(solution, flows, r)) {
        continue;
      }
      int loss = edgeLoss(solution, input, flows, r);
      // 邻表不随本表转：转过去碰到的邻表占用不能比不转时多，否则周期层避开邻表的功夫就白费了。
      if (loss < bestLoss && (r == 0 || touches.at(r) <= unrotated)) {
        bestLoss = loss;
        best = r;
      }
      if (loss == 0) {
        break;
      }
    }
    if (best == 0) {
      return solution;
    }
    Map<String, Integer> phases = new TreeMap<>();
    int shift = best;
    solution
        .phases()
        .forEach(
            (key, phase) -> {
              Flow flow = flows.get(key);
              phases.put(key, flow == null ? phase : Math.floorMod(phase + shift, flow.interval()));
            });
    return new Solution(
        phases,
        solution.holds(),
        solution.conflicts(),
        solution.stranded(),
        solution.depotConflicts(),
        solution.heldSeconds(),
        solution.hyperperiod(),
        solution.nodes(),
        solution.fastFirst(),
        solution.feeders());
  }

  /**
   * 转 {@code r} 之后，各条多 route 流窗口里的第一格是否仍是 SWRR 一轮的第一格。网格从窗口起点按 SWRR 重新数格（第 0 格归一轮的第一条 route）， 环上第 k
   * 格归 {@code cycle[k mod s]}：转过整数个间隔 {@code q = ⌊(相位 + r) / 间隔⌋} 时，窗口第 0 格是环上第 −q 格， {@code q}
   * 不是一轮格数的倍数，大小交路就错了位，多停也加到了别的班次上。{@code r = 0} 总是成立。
   */
  private static boolean keepsCycleAlignment(Solution solution, Map<String, Flow> flows, int r) {
    for (Map.Entry<String, Integer> entry : solution.phases().entrySet()) {
      Flow flow = flows.get(entry.getKey());
      if (flow == null || flow.cycle().size() <= 1) {
        continue;
      }
      int q = Math.floorDiv(entry.getValue() + r, flow.interval());
      if (Math.floorMod(q, flow.cycle().size()) != 0) {
        return false;
      }
    }
    return true;
  }

  /** 转 {@code r} 之后窗口两端落空的班数：在窗口里按车池、站台组先到先走配对，起点出不了车又没车可接的发车，加上终点回不了库、 后面又没有车次带走的到达，各算一班。 */
  private static int edgeLoss(Solution solution, Input input, Map<String, Flow> flows, int r) {
    int horizon = input.horizonSeconds();
    // {时刻, 1 = 必须有人接/带走} ：到达按就绪时刻，发车按发车时刻
    Map<String, List<int[]>> arrivals = new TreeMap<>();
    Map<String, List<int[]>> departures = new TreeMap<>();
    Set<String> constrained = new TreeSet<>();
    for (Map.Entry<String, Integer> entry : solution.phases().entrySet()) {
      Flow flow = flows.get(entry.getKey());
      if (flow == null) {
        continue;
      }
      int phase = Math.floorMod(entry.getValue() + r, flow.interval());
      for (int k = 0; phase + (long) k * flow.interval() <= horizon; k++) {
        int departure = phase + k * flow.interval();
        Shape shape = input.shapes().get(flow.cycle().get(k % flow.cycle().size()));
        int held =
            solution.holds().getOrDefault(shape.routeId(), Map.of()).values().stream()
                .mapToInt(Integer::intValue)
                .sum();
        int arrival = departure + shape.arrivalOffset() + held;
        if (arrival > horizon) {
          continue;
        }
        String from = shape.pool() + "|" + shape.originGroup();
        String to = shape.pool() + "|" + shape.terminalGroup();
        departures
            .computeIfAbsent(from, key -> new ArrayList<>())
            .add(new int[] {departure, shape.suppliedAtOrigin() ? 0 : 1});
        arrivals
            .computeIfAbsent(to, key -> new ArrayList<>())
            .add(new int[] {arrival + shape.turnaround(), shape.returnsAtTerminal() ? 0 : 1});
        if (!shape.suppliedAtOrigin()) {
          constrained.add(from);
        }
        if (!shape.returnsAtTerminal()) {
          constrained.add(to);
        }
      }
    }
    int loss = 0;
    for (String key : constrained) {
      List<int[]> ready = new ArrayList<>(arrivals.getOrDefault(key, List.of()));
      List<int[]> leaving = new ArrayList<>(departures.getOrDefault(key, List.of()));
      ready.sort(Comparator.comparingInt((int[] e) -> e[0]).thenComparingInt(e -> e[1]));
      leaving.sort(Comparator.comparingInt((int[] e) -> e[0]).thenComparingInt(e -> e[1]));
      java.util.ArrayDeque<int[]> waiting = new java.util.ArrayDeque<>();
      int i = 0;
      for (int[] departure : leaving) {
        while (i < ready.size() && ready.get(i)[0] <= departure[0]) {
          waiting.addLast(ready.get(i++));
        }
        if (!waiting.isEmpty()) {
          waiting.pollFirst();
        } else if (departure[1] == 1) {
          loss++;
        }
      }
      for (int[] left : waiting) {
        loss += left[1];
      }
      for (; i < ready.size(); i++) {
        loss += ready.get(i)[1];
      }
    }
    return loss;
  }

  /**
   * 超周期：各流"间隔 × SWRR 一轮"的最小公倍数；超过上限时退成各间隔的最小公倍数。
   *
   * @throws HyperperiodTooLong 各间隔的最小公倍数也超过 {@link #MAX_HYPERPERIOD}
   */
  static int hyperperiodOf(List<Flow> flows) {
    long withCycles = 1L;
    long intervals = 1L;
    for (Flow flow : flows) {
      intervals = cappedLcm(intervals, flow.interval());
      withCycles = cappedLcm(withCycles, (long) flow.interval() * flow.cycle().size());
    }
    if (withCycles <= MAX_HYPERPERIOD) {
      return (int) withCycles;
    }
    if (intervals <= MAX_HYPERPERIOD) {
      return (int) intervals;
    }
    Set<Integer> distinct = new TreeSet<>();
    flows.forEach(flow -> distinct.add(flow.interval()));
    throw new HyperperiodTooLong(
        "各组间隔 "
            + distinct
            + " 的最小公倍数超过 "
            + MAX_HYPERPERIOD / 3600
            + " 小时（"
            + (intervals >= LCM_CAP ? "超过 " + LCM_CAP + " s" : intervals + " s")
            + "），周期层排不了：请让各组间隔有较大的公约数（例如都取 30 s 的倍数）");
  }

  /** 自然放置顺序：间隔短的组先放（它们在环上最密、最难挪），同间隔按组名；组内按方向键。确定，与输入顺序无关。 */
  static List<Flow> naturalOrder(List<Flow> flows) {
    List<Flow> out = new ArrayList<>(flows);
    out.sort(
        Comparator.comparingInt(Flow::interval)
            .thenComparing(Flow::group)
            .thenComparing(Flow::key));
    return out;
  }

  /** 最小公倍数算到这里为止：超过超周期上限之后具体多大已经无关紧要，只要不溢出。 */
  private static final long LCM_CAP = Integer.MAX_VALUE;

  private static long cappedLcm(long a, long b) {
    long reduced = a / gcd(a, b);
    return reduced > LCM_CAP / b ? LCM_CAP : Math.min(LCM_CAP, reduced * b);
  }

  private static long gcd(long a, long b) {
    while (b != 0) {
      long t = a % b;
      a = b;
      b = t;
    }
    return a;
  }

  // ---------------------------------------------------------------- 搜索

  /** 一次按给定顺序的回溯搜索。 */
  private static final class Search {
    private final Input input;
    private final int period;
    private final List<Flow> order;
    private final boolean fastFirst;
    private final boolean anchorIdle;
    private final Ring ring;
    private final Map<UUID, Compiled> compiled = new HashMap<>();
    private final Map<String, List<Mark>> staysByGroup = new TreeMap<>();
    private final Map<String, List<Mark>> softByGroup = new TreeMap<>();

    /** 出入库走行单独一层：资源号与 {@link #ring} 相同，只用来数走行与走行之间的碰撞。 */
    private final Ring softRing;

    private final Map<String, Map<UUID, Set<UUID>>> feedersByGroup = new TreeMap<>();
    private final Map<String, List<Integer>> callsByKey = new HashMap<>();
    private final List<Placed> placed = new ArrayList<>();
    private final Set<String> pairingGroups = new TreeSet<>();
    private final Map<Leg, List<int[]>> legCells = new java.util.IdentityHashMap<>();
    private final Map<String, GroupEvents> groupEvents = new HashMap<>();
    private final Map<TimetableConflictChecker.Platform, List<int[]>> platformCells =
        new HashMap<>();
    private final long scanBudget;
    private long scanned;
    private int nodes;
    private Best best;

    /**
     * @param anchorIdle 没有回库线路的终点也按最短折返锚定（正线折返点总是锚定）
     */
    Search(Input input, int period, List<Flow> order, boolean fastFirst, boolean anchorIdle) {
      this.input = input;
      this.period = period;
      this.order = order;
      this.fastFirst = fastFirst;
      this.anchorIdle = anchorIdle;
      this.ring = new Ring(period);
      this.softRing = new Ring(period);
      ring.shadow = softRing;
      // 邻表单独一层：它不占本表的容量（让车由预订表与终判按路权处理），只在候选排序里紧跟冲突数之后，能避开就避开。
      for (Fixed fixed : input.fixed()) {
        TimetableConflictChecker.Slot cell = fixed.cell();
        ring.markFixed(
            ring.idOf(cell),
            fixed.start() + cell.from(),
            fixed.start()
                + cell.to()
                + TimetableConflictChecker.marginOf(cell.key(), input.separation()),
            cell.direction());
      }
      for (Flow flow : order) {
        for (UUID routeId : flow.cycle()) {
          Shape shape = input.shapes().get(routeId);
          if (shape == null) {
            throw new IllegalArgumentException("周期流 " + flow.key() + " 的 route 没有形状: " + routeId);
          }
          compiled.computeIfAbsent(routeId, id -> new Compiled(shape, ring, input.separation()));
        }
      }
      // 只有接不上车会被罚、或停留本身有代价的站台组才需要逐相位配对：两端都有车库的，配不上就出入库，罚分恒为 0。
      for (Compiled route : compiled.values()) {
        Shape shape = route.shape;
        if (!shape.originGroup().isEmpty() && !shape.suppliedAtOrigin()) {
          pairingGroups.add(shape.originGroup());
        }
        if (!shape.terminalGroup().isEmpty()
            && (!shape.returnsAtTerminal() || shape.blocksAtTerminal())) {
          pairingGroups.add(shape.terminalGroup());
        }
      }
      long widest = 0L;
      for (Flow flow : order) {
        widest = Math.max(widest, scanSteps(flow));
      }
      this.scanBudget = (long) order.size() * widest * BACKTRACK_SCAN_FACTOR;
    }

    /** 一条流要扫几个相位。 */
    private static long scanSteps(Flow flow) {
      int step = Math.max(1, (flow.interval() + MAX_PHASE_STEPS - 1) / MAX_PHASE_STEPS);
      return (flow.interval() + step - 1) / step;
    }

    Solution run() {
      dfs(0, 0, 0L);
      Best chosen = best;
      Map<UUID, Map<Integer, Integer>> holds = new TreeMap<>();
      chosen.holds.forEach(
          (routeId, values) -> {
            Compiled route = compiled.get(routeId);
            Map<Integer, Integer> bySequence = new TreeMap<>();
            for (int j = 0; j < values.length; j++) {
              if (values[j] > 0) {
                bySequence.put(route.shape.holdSequences()[j], values[j]);
              }
            }
            if (!bySequence.isEmpty()) {
              holds.put(routeId, bySequence);
            }
          });
      return new Solution(
          chosen.phases,
          holds,
          chosen.conflicts,
          chosen.stranded,
          chosen.depot,
          chosen.held,
          period,
          nodes,
          fastFirst,
          chosen.feeders);
    }

    /**
     * 先按每层排第一的候选贪心走到底（第一片叶子）；之后的回溯受扫描预算约束：预算用完、或已找到零冲突的解就停。
     *
     * @param score 沿路径累加的冲突分（接不上车的班按 {@link #STRANDED_WEIGHT} 计）
     */
    private void dfs(int level, long score, long held) {
      if (best != null && (best.settled() || scanned >= scanBudget)) {
        return;
      }
      if (level == order.size()) {
        int stranded = strandedTrips();
        int conflicts = resourceConflicts() + stranded;
        long exact = (long) conflicts - stranded + (long) stranded * STRANDED_WEIGHT;
        int depot = depotConflicts();
        if (best == null
            || exact < best.score
            || (exact == best.score
                && (depot < best.depot || (depot == best.depot && held < best.held)))) {
          best = snapshot(exact, conflicts, stranded, depot, held);
        }
        return;
      }
      if (best != null && best.depot == 0 && score >= best.score && held >= best.held) {
        return;
      }
      nodes++;
      Flow flow = order.get(level);
      List<Candidate> candidates = candidates(flow);
      for (Candidate candidate : candidates) {
        Placed applied = apply(flow, candidate);
        dfs(level + 1, score + candidate.total(), held + candidate.held);
        undo(applied);
        if (best != null && (best.settled() || scanned >= scanBudget)) {
          return;
        }
      }
    }

    /** 出入库走行（软占用，不进运营的环）碰上运营占用、或彼此相撞的格数。 */
    private int depotConflicts() {
      int count = 0;
      for (List<Mark> marks : softByGroup.values()) {
        for (Mark mark : marks) {
          if (ring.blocked(mark.res, mark.start, mark.end, mark.dir)
              || softRing.overloaded(mark.res, mark.start, mark.end)) {
            count++;
          }
        }
      }
      return count;
    }

    /**
     * 整张环重新数一遍：每一格只要碰到超容量的时刻就记一处（撞上的两辆车各记一处）。端点接不上车的班数另由 {@link #strandedTrips} 数。
     * 沿路径累加的数会把同一端点的停留重复计入，只用于剪枝排序，采用的解按这两个数比。
     */
    private int resourceConflicts() {
      int count = 0;
      for (Placed p : placed) {
        for (Mark mark : p.moving) {
          if (ring.overloaded(mark.res(), mark.start(), mark.end())) {
            count++;
          }
        }
      }
      for (List<Mark> marks : staysByGroup.values()) {
        for (Mark mark : marks) {
          if (ring.overloaded(mark.res(), mark.start(), mark.end())) {
            count++;
          }
        }
      }
      return count;
    }

    /** 各端点等不到车或接不上车的班数。 */
    private int strandedTrips() {
      int count = 0;
      for (String group : staysByGroup.keySet()) {
        if (pairingGroups.contains(group)) {
          count += staysAt(group, null, false).overIdle;
        }
      }
      return count;
    }

    private Best snapshot(long score, int conflicts, int stranded, int depot, long held) {
      Map<String, Integer> phases = new TreeMap<>();
      Map<UUID, int[]> holds = new TreeMap<>();
      for (Placed p : placed) {
        phases.put(p.flow.key(), p.phase);
        p.holds.forEach((route, values) -> holds.merge(route, values.clone(), Search::maxOf));
      }
      Map<UUID, Set<UUID>> feeders = new TreeMap<>();
      feedersByGroup
          .values()
          .forEach(
              map ->
                  map.forEach(
                      (fed, from) ->
                          feeders.computeIfAbsent(fed, k -> new TreeSet<>()).addAll(from)));
      return new Best(phases, holds, score, conflicts, stranded, depot, held, feeders);
    }

    private static int[] maxOf(int[] a, int[] b) {
      int[] out = a.clone();
      for (int i = 0; i < out.length && i < b.length; i++) {
        out[i] = Math.max(out[i], b[i]);
      }
      return out;
    }

    // ---------------------------------------------------------------- 候选

    /** 扫遍相位，排出前 {@link #BEAM} 个候选（含端点停留的冲突）。 */
    private List<Candidate> candidates(Flow flow) {
      int step = Math.max(1, (flow.interval() + MAX_PHASE_STEPS - 1) / MAX_PHASE_STEPS);
      int preferred = Math.floorMod(input.preferred().getOrDefault(flow.key(), 0), flow.interval());
      List<Candidate> evaluated = new ArrayList<>();
      int minConflicts = Integer.MAX_VALUE;
      int span = input.keepPreferred().contains(flow.key()) ? 1 : flow.interval();
      for (int offset = 0; offset < span; offset += step) {
        int phase = (preferred + offset) % flow.interval();
        scanned++;
        Candidate candidate = holdsAt(flow, phase);
        candidate.deviation = Math.min(offset, flow.interval() - offset);
        // 端点接不接得上车（无库端点等不到下一班）每个相位都要先看：它往往只在一个窄窗口里成立，偏离排序看不见。
        Stays pairing = pairingWith(flow, candidate);
        candidate.stay = pairing.overIdle * STRANDED_WEIGHT;
        candidate.idle = pairing.idle;
        minConflicts = Math.min(minConflicts, candidate.total());
        evaluated.add(candidate);
      }
      // 合流点均匀度只给冲突最少的那一档算：它只在可行的候选之间排先后。
      for (Candidate candidate : evaluated) {
        if (candidate.total() == minConflicts) {
          candidate.merge = mergeScore(flow, candidate);
        }
      }
      evaluated.sort(Candidate.ORDER);
      List<Candidate> top =
          new ArrayList<>(evaluated.subList(0, Math.min(evaluated.size(), BEAM * 2)));
      for (Candidate candidate : top) {
        Placed trial = apply(flow, candidate);
        candidate.stay = trial.stayConflicts;
        candidate.neighbor += trial.stayNeighbor;
        candidate.depot += trial.depotTouches;
        undo(trial);
      }
      top.sort(Candidate.ORDER);
      return List.copyOf(top.subList(0, Math.min(top.size(), BEAM)));
    }

    /** 这一相位下的最早可行多停：哪一格撞上，就把它之前最近那一站的多停加到刚好避开；超上限的算冲突。 */
    private Candidate holdsAt(Flow flow, int phase) {
      Map<UUID, int[]> holds = new LinkedHashMap<>();
      for (UUID routeId : flow.cycle()) {
        holds.computeIfAbsent(routeId, id -> new int[compiled.get(id).holdCount()]);
      }
      int slots = period / flow.interval();
      int conflicts = 0;
      for (int round = 0; round < MAX_HOLD_ROUNDS; round++) {
        conflicts = 0;
        boolean changed = false;
        outer:
        for (int k = 0; k < slots; k++) {
          UUID routeId = flow.cycle().get(k % flow.cycle().size());
          Compiled route = compiled.get(routeId);
          int[] hold = holds.get(routeId);
          int[] prefix = route.prefix(hold);
          int departure = phase + k * flow.interval();
          for (int c = 0; c < route.size(); c++) {
            int start = departure + route.from[c] + prefix[route.startAt[c]];
            int end = departure + route.to[c] + prefix[route.endAt[c]] + route.margin[c];
            if (!ring.blocked(route.res[c], start, end, route.dir[c])) {
              continue;
            }
            int at = route.startAt[c] - 1;
            int room = at < 0 ? 0 : input.holdLimit() - hold[at];
            int shift =
                room <= 0 ? -1 : ring.clearShift(route.res[c], start, end, route.dir[c], room);
            if (shift > 0) {
              hold[at] += shift;
              changed = true;
              break outer;
            }
            conflicts++;
          }
        }
        if (!changed) {
          break;
        }
      }
      long held = 0L;
      int neighbor = 0;
      int depot = 0;
      boolean anyDepot = !softByGroup.isEmpty();
      for (int k = 0; k < slots; k++) {
        UUID routeId = flow.cycle().get(k % flow.cycle().size());
        int[] hold = holds.get(routeId);
        for (int value : hold) {
          held += value;
        }
        if (!ring.hasFixed() && !anyDepot) {
          continue;
        }
        Compiled route = compiled.get(routeId);
        int[] prefix = route.prefix(hold);
        int departure = phase + k * flow.interval();
        for (int c = 0; c < route.size(); c++) {
          int start = departure + route.from[c] + prefix[route.startAt[c]];
          int end = departure + route.to[c] + prefix[route.endAt[c]] + route.margin[c];
          if (ring.hasFixed() && ring.touchesFixed(route.res[c], start, end, route.dir[c])) {
            neighbor++;
          }
          // 已放好的出入库走行不进运营的环：这一班碰上它们不算冲突，但要数出来，能不碰就不碰。
          if (anyDepot && softRing.blocked(route.res[c], start, end, route.dir[c])) {
            depot++;
          }
        }
      }
      Candidate candidate = new Candidate(phase, holds, conflicts, held);
      candidate.neighbor = neighbor;
      candidate.depot = depot;
      return candidate;
    }

    /** 合流点上的合成班距：已放好的流与这一候选叠加后，各合流点最大相邻间隔之和（只算已有别的流经过的点）。 */
    private long mergeScore(Flow flow, Candidate candidate) {
      Map<String, List<Integer>> mine = callsOf(flow, candidate);
      long score = 0L;
      for (Map.Entry<String, List<Integer>> entry : mine.entrySet()) {
        List<Integer> others = callsByKey.get(entry.getKey());
        if (others == null || others.isEmpty()) {
          continue;
        }
        int[] merged = new int[others.size() + entry.getValue().size()];
        int i = 0;
        for (int value : others) {
          merged[i++] = value;
        }
        for (int value : entry.getValue()) {
          merged[i++] = value;
        }
        Arrays.sort(merged);
        int maxGap = merged[0] + period - merged[merged.length - 1];
        for (int j = 1; j < merged.length; j++) {
          maxGap = Math.max(maxGap, merged[j] - merged[j - 1]);
        }
        score += maxGap;
      }
      return score;
    }

    private Map<String, List<Integer>> callsOf(Flow flow, Candidate candidate) {
      Map<String, List<Integer>> out = new TreeMap<>();
      int slots = period / flow.interval();
      for (int k = 0; k < slots; k++) {
        UUID routeId = flow.cycle().get(k % flow.cycle().size());
        Compiled route = compiled.get(routeId);
        int[] prefix = route.prefix(candidate.holds.get(routeId));
        int departure = candidate.phase + k * flow.interval();
        for (PhasePlanner.StopCall call : route.shape.calls()) {
          int shift = prefix[route.holdsAtOrBefore(call.offsetSeconds())];
          out.computeIfAbsent(call.key(), key -> new ArrayList<>())
              .add(Math.floorMod(departure + call.offsetSeconds() + shift, period));
        }
      }
      return out;
    }

    /** 这一候选放下之后，它的起终点上的配对结果：几班接不上车、原地折返端多停了几秒（不动环，只配对）。 */
    private Stays pairingWith(Flow flow, Candidate candidate) {
      Placed extra = new Placed(flow, candidate.phase, candidate.holds, List.of());
      int overIdle = 0;
      long idle = 0L;
      for (String group : groupsOf(flow)) {
        if (!pairingGroups.contains(group)) {
          continue;
        }
        Stays stays = staysAt(group, extra, false);
        overIdle += stays.overIdle;
        idle += stays.idle;
      }
      return new Stays(List.of(), List.of(), Map.of(), overIdle, idle);
    }

    private Set<String> groupsOf(Flow flow) {
      Set<String> groups = new TreeSet<>();
      for (UUID routeId : new LinkedHashSet<>(flow.cycle())) {
        Compiled route = compiled.get(routeId);
        if (!route.originGroup.isEmpty()) {
          groups.add(route.originGroup);
        }
        if (!route.terminalGroup.isEmpty()) {
          groups.add(route.terminalGroup);
        }
      }
      return groups;
    }

    // ---------------------------------------------------------------- 放置与撤销

    private Placed apply(Flow flow, Candidate candidate) {
      List<Mark> moving = new ArrayList<>();
      int slots = period / flow.interval();
      for (int k = 0; k < slots; k++) {
        UUID routeId = flow.cycle().get(k % flow.cycle().size());
        Compiled route = compiled.get(routeId);
        int[] prefix = route.prefix(candidate.holds.get(routeId));
        int departure = candidate.phase + k * flow.interval();
        for (int c = 0; c < route.size(); c++) {
          Mark mark =
              new Mark(
                  route.res[c],
                  departure + route.from[c] + prefix[route.startAt[c]],
                  departure + route.to[c] + prefix[route.endAt[c]] + route.margin[c],
                  route.dir[c]);
          ring.mark(mark, 1);
          moving.add(mark);
        }
      }
      Placed placedFlow = new Placed(flow, candidate.phase, candidate.holds, moving);
      placed.add(placedFlow);
      groupsOf(flow).forEach(groupEvents::remove);
      for (Map.Entry<String, List<Integer>> entry : callsOf(flow, candidate).entrySet()) {
        callsByKey
            .computeIfAbsent(entry.getKey(), key -> new ArrayList<>())
            .addAll(entry.getValue());
      }
      placedFlow.calls = callsOf(flow, candidate);
      for (String group : groupsOf(flow)) {
        List<Mark> before = staysByGroup.getOrDefault(group, List.of());
        for (Mark mark : before) {
          ring.mark(mark, -1);
        }
        placedFlow.staysBefore.put(group, before);
        List<Mark> softBefore = softByGroup.getOrDefault(group, List.of());
        for (Mark mark : softBefore) {
          softRing.mark(mark, -1);
        }
        placedFlow.softBefore.put(group, softBefore);
        placedFlow.feedersBefore.put(group, feedersByGroup.getOrDefault(group, Map.of()));
        Stays stays = staysAt(group, null, true);
        // 先全部查、再全部放：边查边放会让每一次查询都重建前缀和。
        for (Mark mark : stays.marks) {
          if (ring.blocked(mark.res, mark.start, mark.end, mark.dir)) {
            placedFlow.stayConflicts++;
          }
          if (ring.touchesFixed(mark.res, mark.start, mark.end, mark.dir)) {
            placedFlow.stayNeighbor++;
          }
        }
        for (Mark mark : stays.marks) {
          ring.mark(mark, 1);
        }
        for (Mark mark : stays.soft) {
          if (ring.blocked(mark.res, mark.start, mark.end, mark.dir)
              || softRing.blocked(mark.res, mark.start, mark.end, mark.dir)
              || ring.touchesFixed(mark.res, mark.start, mark.end, mark.dir)) {
            placedFlow.depotTouches++;
          }
        }
        for (Mark mark : stays.soft) {
          softRing.mark(mark, 1);
        }
        placedFlow.stayConflicts += stays.overIdle * STRANDED_WEIGHT;
        staysByGroup.put(group, stays.marks);
        softByGroup.put(group, stays.soft);
        feedersByGroup.put(group, stays.feeders);
      }
      return placedFlow;
    }

    private void undo(Placed applied) {
      applied.softBefore.forEach(
          (group, soft) -> {
            for (Mark mark : softByGroup.getOrDefault(group, List.of())) {
              softRing.mark(mark, -1);
            }
            for (Mark mark : soft) {
              softRing.mark(mark, 1);
            }
            if (soft.isEmpty()) {
              softByGroup.remove(group);
            } else {
              softByGroup.put(group, soft);
            }
          });
      for (Map.Entry<String, List<Mark>> entry : applied.staysBefore.entrySet()) {
        for (Mark mark : staysByGroup.getOrDefault(entry.getKey(), List.of())) {
          ring.mark(mark, -1);
        }
        for (Mark mark : entry.getValue()) {
          ring.mark(mark, 1);
        }
        if (entry.getValue().isEmpty()) {
          staysByGroup.remove(entry.getKey());
        } else {
          staysByGroup.put(entry.getKey(), entry.getValue());
        }
      }
      applied.feedersBefore.forEach(
          (group, feeders) -> {
            if (feeders.isEmpty()) {
              feedersByGroup.remove(group);
            } else {
              feedersByGroup.put(group, feeders);
            }
          });
      for (Mark mark : applied.moving) {
        ring.mark(mark, -1);
      }
      applied.calls.forEach(
          (key, values) -> {
            List<Integer> list = callsByKey.get(key);
            for (Integer value : values) {
              list.remove(value);
            }
          });
      placed.remove(placed.size() - 1);
      groupsOf(applied.flow).forEach(groupEvents::remove);
    }

    // ---------------------------------------------------------------- 端点停留

    /**
     * 一个站台组上全部已放好的到发，按车池先到先走配对，得出停留占用。
     *
     * @param extra 另算一条还没放下的流（只配对、不动环）；为空时只看已放好的
     * @param withMarks 是否生成停留的占用格；只要配对结果时不生成
     */
    private Stays staysAt(String group, Placed extra, boolean withMarks) {
      // 已放好的流在这一组上的到发排好序缓存起来（放置、撤销时作废），试相位时只把候选那几班并进去。
      GroupEvents base = groupEvents.computeIfAbsent(group, this::eventsOfPlaced);
      List<Event> arrivals = base.arrivals();
      List<Event> departures = base.departures();
      if (extra != null) {
        List<Event> extraArrivals = new ArrayList<>();
        List<Event> extraDepartures = new ArrayList<>();
        collectEvents(extra, group, extraArrivals, extraDepartures);
        arrivals = merged(arrivals, extraArrivals, Event.READY_ORDER);
        departures = merged(departures, extraDepartures, Event.ORDER);
      }
      boolean[] used = new boolean[arrivals.size()];
      List<Mark> marks = new ArrayList<>();
      List<Mark> soft = new ArrayList<>();
      Map<UUID, Set<UUID>> feeders = new TreeMap<>();
      int overIdle = 0;
      long idle = 0L;
      boolean pairs = input.maxTripsPerDuty() > 1;
      Map<String, java.util.ArrayDeque<Integer>> waiting = new HashMap<>();
      int next = 0;
      for (Event departure : departures) {
        int chosen = -1;
        if (pairs) {
          while (next < arrivals.size() && arrivals.get(next).ready <= departure.time) {
            waiting
                .computeIfAbsent(
                    arrivals.get(next).shape.pool(), pool -> new java.util.ArrayDeque<>())
                .addLast(next);
            next++;
          }
          java.util.ArrayDeque<Integer> queue = waiting.get(departure.shape.pool());
          // 能回库的车等过闲置上限就回库了，不再等这一班：从队头清掉（发车时刻只增不减，清掉的不会再被要）。
          while (queue != null
              && !queue.isEmpty()
              && arrivals.get(queue.peekFirst()).shape.returnsAtTerminal()
              && departure.time - arrivals.get(queue.peekFirst()).ready > input.maxIdle()) {
            queue.pollFirst();
          }
          if (queue != null && !queue.isEmpty()) {
            chosen = departure.shape.latestFirstAtOrigin() ? queue.pollLast() : queue.pollFirst();
          }
        }
        if (chosen < 0) {
          if (departure.copy != 0) {
            continue;
          }
          if (!departure.shape.suppliedAtOrigin()) {
            // 没有到达车可接、起点又出不了车：派车只能取消这一班。
            overIdle++;
          } else if (withMarks && departure.shape.create().isPresent()) {
            Leg create = departure.shape.create().get();
            addLeg(soft, create, departure.time - create.ready() - create.run());
            departure
                .shape
                .origin()
                .ifPresent(
                    platform ->
                        addStay(soft, platform, departure.time - create.ready(), departure.time));
          }
          continue;
        }
        used[chosen] = true;
        Event arrival = arrivals.get(chosen);
        if (departure.copy != 0) {
          continue;
        }
        // 闲置从折返完、车就绪起算，与派车（VehicleDutyPlanner）同一口径。
        if (!arrival.shape.returnsAtTerminal()
            && departure.time - arrival.ready > input.maxIdle()) {
          overIdle++;
        }
        // 原地折返端要锚在最短折返：多等的秒数是代价，余量放到有车库的另一端。正线折返点（车停着挡道）总是锚定；没有回库线路的终点
        // （车调不走）看 anchorIdle，锚与不锚各解一次，由窗口两端落空的班数决定（见 solve）。
        if (arrival.shape.blocksAtTerminal()
            || (anchorIdle && !arrival.shape.returnsAtTerminal())) {
          idle += Math.max(0, departure.time - arrival.ready);
        }
        if (!withMarks) {
          continue;
        }
        // 末站不是站台（正线折返点）时没有站台可占：车停在区间上，挡道由停留秒数的代价体现。
        arrival
            .shape
            .terminal()
            .ifPresent(
                terminal ->
                    addStay(
                        marks,
                        departure.shape.origin().map(terminal::intersect).orElse(terminal),
                        arrival.time,
                        departure.time));
        if (!arrival.shape.routeId().equals(departure.shape.routeId())) {
          feeders
              .computeIfAbsent(departure.shape.routeId(), k -> new TreeSet<>())
              .add(arrival.shape.routeId());
        }
      }
      for (int i = 0; i < arrivals.size(); i++) {
        Event arrival = arrivals.get(i);
        if (used[i] || arrival.copy != 0) {
          continue;
        }
        if (arrival.shape.returnsAtTerminal()) {
          if (!withMarks) {
            continue;
          }
          arrival
              .shape
              .terminal()
              .ifPresent(terminal -> addStay(soft, terminal, arrival.time, arrival.ready));
          arrival.shape.back().ifPresent(back -> addLeg(soft, back, arrival.ready));
        } else {
          // 没有回库线路、又等不到下一班：车会卡在这里，派车只能取消它前面的班次。
          overIdle++;
        }
      }
      return new Stays(List.copyOf(marks), List.copyOf(soft), feeders, overIdle, idle);
    }

    /** 已放好的流在一个站台组上的到发，排好序。 */
    private GroupEvents eventsOfPlaced(String group) {
      List<Event> arrivals = new ArrayList<>();
      List<Event> departures = new ArrayList<>();
      for (Placed p : placed) {
        collectEvents(p, group, arrivals, departures);
      }
      arrivals.sort(Event.READY_ORDER);
      departures.sort(Event.ORDER);
      return new GroupEvents(List.copyOf(arrivals), List.copyOf(departures));
    }

    /** 一条流在站台组上的到达与发车，各铺前后各一个超周期（环上的配对要看得见跨周期的车）。 */
    private void collectEvents(
        Placed p, String group, List<Event> arrivals, List<Event> departures) {
      int slots = period / p.flow.interval();
      for (int k = 0; k < slots; k++) {
        UUID routeId = p.flow.cycle().get(k % p.flow.cycle().size());
        Compiled route = compiled.get(routeId);
        boolean arrivesHere = group.equals(route.terminalGroup);
        boolean departsHere = group.equals(route.originGroup);
        if (!arrivesHere && !departsHere) {
          continue;
        }
        Shape shape = route.shape;
        int departure = p.phase + k * p.flow.interval();
        int held = arrivesHere ? route.prefix(p.holds.get(routeId))[route.holdCount()] : 0;
        for (int copy = -1; copy <= 1; copy++) {
          int base = departure + copy * period;
          if (arrivesHere) {
            int arrival = base + shape.arrivalOffset() + held;
            arrivals.add(new Event(arrival, arrival + shape.turnaround(), shape, copy));
          }
          if (departsHere) {
            departures.add(new Event(base, base, shape, copy));
          }
        }
      }
    }

    /** 两段各自有序的事件并成一段有序的。 */
    private static List<Event> merged(
        List<Event> sorted, List<Event> extra, Comparator<Event> order) {
      if (extra.isEmpty()) {
        return sorted;
      }
      extra.sort(order);
      List<Event> out = new ArrayList<>(sorted.size() + extra.size());
      int i = 0;
      int j = 0;
      while (i < sorted.size() || j < extra.size()) {
        if (j >= extra.size()
            || (i < sorted.size() && order.compare(sorted.get(i), extra.get(j)) <= 0)) {
          out.add(sorted.get(i++));
        } else {
          out.add(extra.get(j++));
        }
      }
      return out;
    }

    /** 一段出入库走行从 {@code start} 出发的占用格。 */
    private void addLeg(List<Mark> marks, Leg leg, int start) {
      for (int[] cell : legCells.computeIfAbsent(leg, this::compileCells)) {
        marks.add(new Mark(cell[0], start + cell[1], start + cell[2], cell[3]));
      }
    }

    private void addStay(
        List<Mark> marks, TimetableConflictChecker.Platform platform, int from, int to) {
      for (int[] cell : platformCells.computeIfAbsent(platform, this::compilePlatform)) {
        marks.add(new Mark(cell[0], from, Math.max(from, to) + input.separation(), cell[3]));
      }
    }

    /** 走行足迹编成 {资源号, 进入, 离开加余量, 方向}：同一段走行每次放置都要用，资源号只查一次。 */
    private List<int[]> compileCells(Leg leg) {
      List<int[]> out = new ArrayList<>(leg.cells().size());
      for (TimetableConflictChecker.Slot cell : leg.cells()) {
        out.add(
            new int[] {
              ring.idOf(cell),
              cell.from(),
              cell.to() + TimetableConflictChecker.marginOf(cell.key(), input.separation()),
              cell.direction()
            });
      }
      return out;
    }

    /** 一个站台停留占哪几个资源（股道、站台组）：与停留的起止无关，只算一次。 */
    private List<int[]> compilePlatform(TimetableConflictChecker.Platform platform) {
      List<int[]> out = new ArrayList<>();
      for (TimetableConflictChecker.Slot slot :
          TimetableConflictChecker.staySlots(platform, 0, 0, input.footprints())) {
        out.add(new int[] {ring.idOf(slot), 0, 0, slot.direction()});
      }
      return out;
    }
  }

  // ---------------------------------------------------------------- 数据

  /**
   * @param score 冲突分（接不上车的班按 {@link #STRANDED_WEIGHT} 计），比较用
   * @param conflicts 资源冲突数加接不上车的班数，报告用
   * @param stranded 接不上车的班数
   */
  private record Best(
      Map<String, Integer> phases,
      Map<UUID, int[]> holds,
      long score,
      int conflicts,
      int stranded,
      int depot,
      long held,
      Map<UUID, Set<UUID>> feeders) {

    /** 运营与出入库走行都排开了，不必再找。 */
    boolean settled() {
      return conflicts == 0 && depot == 0;
    }
  }

  private record Mark(int res, int start, int end, int dir) {}

  /** 一个站台组上已放好的到达（按就绪排）与发车（按时刻排）。 */
  private record GroupEvents(List<Event> arrivals, List<Event> departures) {}

  /**
   * 一个站台组上的配对结果。
   *
   * @param marks 运营车的端点停留：硬占用，放进环里算冲突
   * @param soft 出入库走行与它们在端点上的等候：不进环、不算冲突（运行时由让车与残余判定吸收），只按碰上已放占用的格数作排序依据
   * @param feeders 跨 route 接班：被接 route → 喂车 route
   * @param overIdle 接不上车的班数（无库端点等不到下一班、起点出不了车）
   * @param idle 原地折返端（正线折返点、没有回库线路的终点）上超出折返的停留秒数
   */
  private record Stays(
      List<Mark> marks, List<Mark> soft, Map<UUID, Set<UUID>> feeders, int overIdle, long idle) {}

  private record Event(int time, int ready, Shape shape, int copy) {
    static final Comparator<Event> ORDER =
        Comparator.comparingInt(Event::time)
            .thenComparingInt(Event::ready)
            .thenComparing(event -> event.shape().routeId());

    /** 到达按就绪排：先到先走的队列序。 */
    static final Comparator<Event> READY_ORDER =
        Comparator.comparingInt(Event::ready)
            .thenComparingInt(Event::time)
            .thenComparing(event -> event.shape().routeId());
  }

  /** 一次放置：撤销时按它恢复。 */
  private static final class Placed {
    final Flow flow;
    final int phase;
    final Map<UUID, int[]> holds;
    final List<Mark> moving;
    final Map<String, List<Mark>> staysBefore = new TreeMap<>();
    final Map<String, List<Mark>> softBefore = new TreeMap<>();
    final Map<String, Map<UUID, Set<UUID>>> feedersBefore = new TreeMap<>();
    Map<String, List<Integer>> calls = Map.of();
    int stayConflicts;
    int stayNeighbor;
    int depotTouches;

    Placed(Flow flow, int phase, Map<UUID, int[]> holds, List<Mark> moving) {
      this.flow = flow;
      this.phase = phase;
      this.holds = holds;
      this.moving = moving;
    }
  }

  /** 一个相位候选。 */
  private static final class Candidate {
    static final Comparator<Candidate> ORDER =
        Comparator.comparingInt(Candidate::total)
            .thenComparingInt(candidate -> candidate.neighbor)
            .thenComparingLong(candidate -> candidate.held)
            .thenComparingLong(candidate -> candidate.idle)
            .thenComparingInt(candidate -> candidate.depot)
            .thenComparingLong(candidate -> candidate.merge)
            .thenComparingInt(candidate -> candidate.deviation)
            .thenComparingInt(candidate -> candidate.phase);

    final int phase;
    final Map<UUID, int[]> holds;
    final int moving;
    final long held;
    long merge;
    int deviation;
    int stay;
    long idle;
    int neighbor;
    int depot;

    Candidate(int phase, Map<UUID, int[]> holds, int moving, long held) {
      this.phase = phase;
      this.holds = holds;
      this.moving = moving;
      this.held = held;
    }

    /** 冲突分：运行格的冲突加上端点停留（其中接不上车的班已按 {@link #STRANDED_WEIGHT} 计）。 */
    int total() {
      return moving + stay;
    }
  }

  /** 一条 route 的足迹编成数组：资源号、进入、离开、离开之后的余量、方向，以及每格前后各有几站可多停。 */
  private static final class Compiled {
    final Shape shape;
    final int[] res;
    final int[] from;
    final int[] to;
    final int[] margin;
    final int[] dir;
    final int[] startAt;
    final int[] endAt;
    final String originGroup;
    final String terminalGroup;

    /**
     * @param separation 占用格之后的余量；闭塞格不留（{@link TimetableConflictChecker#marginOf}）
     */
    Compiled(Shape shape, Ring ring, int separation) {
      this.shape = shape;
      this.originGroup = shape.originGroup();
      this.terminalGroup = shape.terminalGroup();
      List<TimetableConflictChecker.Slot> cells = shape.cells();
      int n = cells.size();
      res = new int[n];
      from = new int[n];
      to = new int[n];
      margin = new int[n];
      dir = new int[n];
      startAt = new int[n];
      endAt = new int[n];
      for (int c = 0; c < n; c++) {
        TimetableConflictChecker.Slot cell = cells.get(c);
        res[c] = ring.idOf(cell);
        from[c] = cell.from();
        to[c] = cell.to();
        margin[c] = TimetableConflictChecker.marginOf(cell.key(), separation);
        dir[c] = cell.direction();
        startAt[c] = holdsAtOrBefore(cell.from());
        endAt[c] = holdsAtOrBefore(cell.to());
      }
    }

    int size() {
      return res.length;
    }

    int holdCount() {
      return shape.holdDepartures().length;
    }

    /** 发车偏移不晚于 {@code offset} 的可多停车站数：这一时刻之前的多停都已经发生。 */
    int holdsAtOrBefore(int offset) {
      int count = 0;
      for (int departure : shape.holdDepartures()) {
        if (departure <= offset) {
          count++;
        }
      }
      return count;
    }

    int[] prefix(int[] hold) {
      int[] out = new int[hold.length + 1];
      for (int j = 0; j < hold.length; j++) {
        out[j + 1] = out[j] + hold[j];
      }
      return out;
    }
  }

  /** 超周期的环：每个资源一份逐秒占用计数（单线按方向分两份），容量 1 的资源配前缀和做 O(1) 区间查询。 */
  private static final class Ring {
    private boolean anyFixed;

    /** 资源号与本环同步的另一层（出入库走行层）；没有时为空。 */
    private Ring shadow;

    private final int period;
    private final Map<String, Integer> ids = new HashMap<>();
    private final List<Resource> resources = new ArrayList<>();

    Ring(int period) {
      this.period = period;
    }

    int idOf(TimetableConflictChecker.Slot slot) {
      Integer known = ids.get(slot.key());
      if (known != null) {
        return known;
      }
      int id = resources.size();
      ids.put(slot.key(), id);
      if (shadow != null) {
        shadow.idOf(slot);
      }
      resources.add(
          new Resource(
              Math.max(1, slot.capacity()),
              slot.kind() == TimetableConflictChecker.Kind.SINGLE_LINE,
              period));
      return id;
    }

    /** 记一段占用；比一个超周期还长的停留在环上叠成几层（每整圈一层，余下的一段再加一层）。 */
    void mark(Mark mark, int delta) {
      Resource resource = resources.get(mark.res());
      int length = Math.max(0, mark.end() - mark.start());
      int start = Math.floorMod(mark.start(), period);
      if (resource.singleLine) {
        if (mark.dir() >= 0) {
          resource.add(resource.forward, start, length, delta);
        }
        if (mark.dir() <= 0) {
          resource.add(resource.backward, start, length, delta);
        }
        if (mark.dir() == 0) {
          resource.add(resource.neutral, start, length, delta);
        }
      } else {
        resource.add(resource.count, start, length, delta);
      }
    }

    boolean blocked(int res, int startAbs, int endAbs, int dir) {
      Resource resource = resources.get(res);
      int length = Math.min(period, Math.max(0, endAbs - startAbs));
      if (length == 0) {
        return false;
      }
      int start = Math.floorMod(startAbs, period);
      if (resource.singleLine) {
        boolean againstForward = dir <= 0 && resource.any(resource.forward, start, length);
        boolean againstBackward = dir >= 0 && resource.any(resource.backward, start, length);
        return againstForward || againstBackward;
      }
      if (resource.capacity == 1) {
        return resource.any(resource.count, start, length);
      }
      return resource.max(start, length) >= resource.capacity;
    }

    /** 记一段邻表占用（单独一层，不算本表容量）；单线按方向分开记，与本表占用的同向跟驰规则一致。 */
    void markFixed(int res, int startAbs, int endAbs, int dir) {
      Resource resource = resources.get(res);
      int length = Math.max(0, endAbs - startAbs);
      int start = Math.floorMod(startAbs, period);
      if (!resource.singleLine) {
        resource.add(resource.fixedLayer(0), start, length, 1);
      } else {
        if (dir >= 0) {
          resource.add(resource.fixedLayer(1), start, length, 1);
        }
        if (dir <= 0) {
          resource.add(resource.fixedLayer(-1), start, length, 1);
        }
      }
      anyFixed = true;
    }

    boolean hasFixed() {
      return anyFixed;
    }

    /** 这一段碰没碰到邻表的占用（单线只看对向，同 {@link #blocked}）。 */
    boolean touchesFixed(int res, int startAbs, int endAbs, int dir) {
      Resource resource = resources.get(res);
      int length = Math.min(period, Math.max(0, endAbs - startAbs));
      if (length == 0) {
        return false;
      }
      int start = Math.floorMod(startAbs, period);
      if (!resource.singleLine) {
        return resource.fixed != null && resource.any(resource.fixed, start, length);
      }
      boolean againstForward =
          dir <= 0
              && resource.fixedForward != null
              && resource.any(resource.fixedForward, start, length);
      boolean againstBackward =
          dir >= 0
              && resource.fixedBackward != null
              && resource.any(resource.fixedBackward, start, length);
      return againstForward || againstBackward;
    }

    /** 已放好的占用里，这一段有没有超容量的时刻（单线：两个方向同时在场，或判不出方向的与别人同在）。 */
    boolean overloaded(int res, int startAbs, int endAbs) {
      Resource resource = resources.get(res);
      int length = Math.min(period, Math.max(0, endAbs - startAbs));
      return length > 0 && resource.overloaded(Math.floorMod(startAbs, period), length);
    }

    /** 这一格整体后移多少秒能避开（1 到 {@code room}）；避不开为 −1。 */
    int clearShift(int res, int start, int end, int dir, int room) {
      for (int shift = 1; shift <= room; shift++) {
        if (!blocked(res, start + shift, end + shift, dir)) {
          return shift;
        }
      }
      return -1;
    }
  }

  /** 没有现成前缀和时，不超过这么多秒的区间直接逐秒查，不为它重建前缀和。 */
  private static final int DIRECT_SCAN_SECONDS = 256;

  /** 一个资源的逐秒计数。 */
  private static final class Resource {
    final int capacity;
    final boolean singleLine;
    final short[] count;
    final short[] forward;
    final short[] backward;
    final short[] neutral;
    short[] fixed;
    short[] fixedForward;
    short[] fixedBackward;
    private int[] overloadPrefix;
    private final Map<short[], int[]> prefixes = new HashMap<>();

    Resource(int capacity, boolean singleLine, int period) {
      this.capacity = capacity;
      this.singleLine = singleLine;
      this.count = singleLine ? null : new short[period];
      this.forward = singleLine ? new short[period] : null;
      this.backward = singleLine ? new short[period] : null;
      this.neutral = singleLine ? new short[period] : null;
    }

    void add(short[] array, int start, int length, int delta) {
      int period = array.length;
      if (length >= period) {
        short whole = (short) (delta * (length / period));
        for (int i = 0; i < period; i++) {
          array[i] += whole;
        }
        length %= period;
      }
      for (int i = 0; i < length; i++) {
        int at = start + i;
        if (at >= period) {
          at -= period;
        }
        array[at] += (short) delta;
      }
      prefixes.remove(array);
      overloadPrefix = null;
    }

    /** 邻表那一层，用到才建：普通资源一层，单线按方向（1 正向、−1 反向）各一层。 */
    short[] fixedLayer(int dir) {
      int period = singleLine ? forward.length : count.length;
      if (dir > 0) {
        if (fixedForward == null) {
          fixedForward = new short[period];
        }
        return fixedForward;
      }
      if (dir < 0) {
        if (fixedBackward == null) {
          fixedBackward = new short[period];
        }
        return fixedBackward;
      }
      if (fixed == null) {
        fixed = new short[period];
      }
      return fixed;
    }

    /** {@code [start, start + length)}（环上）里有没有超容量的时刻；前缀和在改动之后第一次查询时重建一次。 */
    boolean overloaded(int start, int length) {
      if (overloadPrefix == null) {
        int period = singleLine ? forward.length : count.length;
        overloadPrefix = new int[period + 1];
        for (int i = 0; i < period; i++) {
          overloadPrefix[i + 1] = overloadPrefix[i] + (overloadedAt(i) ? 1 : 0);
        }
      }
      int period = overloadPrefix.length - 1;
      if (length >= period) {
        return overloadPrefix[period] > 0;
      }
      int end = start + length;
      if (end <= period) {
        return overloadPrefix[end] - overloadPrefix[start] > 0;
      }
      return overloadPrefix[period] - overloadPrefix[start] + overloadPrefix[end - period] > 0;
    }

    private boolean overloadedAt(int i) {
      if (!singleLine) {
        return count[i] > capacity;
      }
      int both = neutral[i];
      int onlyForward = forward[i] - both;
      int onlyBackward = backward[i] - both;
      return (onlyForward > 0 && onlyBackward > 0)
          || (both > 0 && onlyForward + onlyBackward + both > 1);
    }

    boolean any(short[] array, int start, int length) {
      int period = array.length;
      int[] prefix = prefixes.get(array);
      if (prefix == null && length <= DIRECT_SCAN_SECONDS) {
        // 插入与查询交替时（端点停留、出入库走行逐格先查后放），每次改动都重建前缀和是 O(超周期)；短区间直接逐秒看更便宜。
        for (int i = 0; i < length; i++) {
          int at = start + i;
          if (at >= period) {
            at -= period;
          }
          if (array[at] > 0) {
            return true;
          }
        }
        return false;
      }
      if (prefix == null) {
        prefix = indicatorPrefix(array);
        prefixes.put(array, prefix);
      }
      if (length >= period) {
        return prefix[period] > 0;
      }
      int end = start + length;
      if (end <= period) {
        return prefix[end] - prefix[start] > 0;
      }
      return prefix[period] - prefix[start] + prefix[end - period] > 0;
    }

    int max(int start, int length) {
      int period = count.length;
      int best = 0;
      for (int i = 0; i < length; i++) {
        int at = start + i;
        if (at >= period) {
          at -= period;
        }
        best = Math.max(best, count[at]);
      }
      return best;
    }

    private static int[] indicatorPrefix(short[] array) {
      int[] prefix = new int[array.length + 1];
      for (int i = 0; i < array.length; i++) {
        prefix[i + 1] = prefix[i] + (array[i] > 0 ? 1 : 0);
      }
      return prefix;
    }
  }
}
