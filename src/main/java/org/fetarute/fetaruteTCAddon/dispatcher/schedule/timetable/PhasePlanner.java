package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
 * <p>第二层有一个例外：<b>跨组按车接续</b>。某组的一个方向终到容量 1 的端点，而先放好的别组方向正从这个端点始发、 且那一端不是它自己往返对的锚定端（没有车喂它）——例如 WS
 * 的小交路 1L 到克罗顿高地后接大交路 2C——这时本组的相位由车决定，不由乘客间隔决定：到站 + 折返正好是那一班的发车。
 * 按乘客间隔扫出来的偏移会把周期余数留在单股道端点上，车在那里要么空等占着股道，要么被串行器整批推后。
 * 锚定之后，被接那一班所在往返对的反向车在<b>远端</b>多等一段（受远端单股道一个周期容得下的时间限制），
 * 让它回到端点的时刻与喂车方向到站错开半个周期——这就是现实排班里"周转余量放在能力富余的那一端"。 接续关系随结果交给派车器（优先用喂车方向的车接）与第三层（整条锚定链一起平移，不拆开）。
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
   * @param deltaByDirection 第三层给的 δ
   * @param notes 说明（哪些方向锚定了、哪些组按共用起点交错了）
   * @param resourceNotes 第三层的说明
   * @param connections 跨组按车接续（按组名、方向键排序）
   */
  public record Phases(
      Map<String, Integer> phaseByDirection,
      Map<String, Integer> offsetByGroup,
      Map<String, Integer> deltaByDirection,
      List<String> notes,
      List<String> resourceNotes,
      List<Connection> connections) {
    public Phases {
      phaseByDirection = phaseByDirection == null ? Map.of() : Map.copyOf(phaseByDirection);
      offsetByGroup = offsetByGroup == null ? Map.of() : Map.copyOf(offsetByGroup);
      deltaByDirection = deltaByDirection == null ? Map.of() : Map.copyOf(deltaByDirection);
      notes = notes == null ? List.of() : List.copyOf(notes);
      resourceNotes = resourceNotes == null ? List.of() : List.copyOf(resourceNotes);
      connections = connections == null ? List.of() : List.copyOf(connections);
    }

    /** 前两层的结果：第三层还没跑过，也没有跨组接续。 */
    public Phases(
        Map<String, Integer> phaseByDirection,
        Map<String, Integer> offsetByGroup,
        List<String> notes) {
      this(phaseByDirection, offsetByGroup, Map.of(), notes, List.of(), List.of());
    }

    /** 这个方向最终的相位：锚定/交错给的，加上第三层的端点多等。 */
    public int effectivePhaseOf(String directionKey) {
      return phaseByDirection.getOrDefault(directionKey, 0)
          + deltaByDirection.getOrDefault(directionKey, 0);
    }
  }

  /**
   * 一次跨组按车接续：喂车方向的车在端点折返后接被接方向的下一班。
   *
   * @param terminal 接续发生的端点站台组（容量 1）
   * @param feederKey 喂车方向键（终到该端点）
   * @param fedKey 被接方向键（从该端点始发）
   * @param backKey 被接方向往返对里的反向（回到该端点的那一路）；没有时为空串
   * @param feederRoutes 喂车方向的 route
   * @param fedRoutes 被接方向的 route
   * @param farEndWaitSeconds 反向车在远端多等的秒数（周期余数落点）
   * @param clearanceSeconds 选定多等之后，端点上两次折返、车库咽喉上出库与回库两处间隙里较小的那个；负数 = 上限内错不开，
   *     这个间隔下端点或咽喉必有冲突（搜索用它逐秒预筛）；没有反向车可调时为 {@link Integer#MAX_VALUE}
   */
  public record Connection(
      String terminal,
      String feederKey,
      String fedKey,
      String backKey,
      List<UUID> feederRoutes,
      List<UUID> fedRoutes,
      int farEndWaitSeconds,
      int clearanceSeconds) {
    public Connection {
      terminal = terminal == null ? "" : terminal;
      feederKey = feederKey == null ? "" : feederKey;
      fedKey = fedKey == null ? "" : fedKey;
      backKey = backKey == null ? "" : backKey;
      feederRoutes = feederRoutes == null ? List.of() : List.copyOf(feederRoutes);
      fedRoutes = fedRoutes == null ? List.of() : List.copyOf(fedRoutes);
      farEndWaitSeconds = Math.max(0, farEndWaitSeconds);
    }
  }

  /**
   * 远端一次折返的占用（不含多等）：进站走行 + 折返 + 出站走行 + 裕量。远端多等的上限 = 间隔 − 它， 否则下一班到达时上一辆车还占着那股道。与 {@link
   * TerminalSerializer} 的端点占用同一口径。
   */
  @FunctionalInterface
  public interface FarEndCost {
    int occupiedSeconds(String group, UUID arrivingRoute, UUID departingRoute);
  }

  /**
   * 喂车方向出库、与被接往返对反向车回库，各占车库咽喉的时段（出口都已含裕量）。
   *
   * @param outEnter 喂车方向进入咽喉，相对它的发车
   * @param outExit 喂车方向离开咽喉 + 裕量，相对它的发车
   * @param inEnter 反向车回库进入咽喉，相对它到达端点（含端点折返）
   * @param inExit 反向车回库离开咽喉 + 裕量，相对它到达端点
   */
  public record ThroatWindows(int outEnter, int outExit, int inEnter, int inExit) {}

  /** 车库咽喉的几何：喂车方向从车库出库、被接往返对的反向车在端点折返后回同一座车库时，两段咽喉占用的时段； 不经过同一个咽喉时为空。 */
  @FunctionalInterface
  public interface ThroatGeometry {
    Optional<ThroatWindows> windows(UUID feederRoute, UUID backRoute);
  }

  /**
   * 相位层需要知道的路网形状。
   *
   * @param stubTerminals 容量 1 的端点站台组（{@link TerminalSerializer#terminalGroups} 的结果）
   * @param farEndCost 一次折返的占用（远端多等的上限、端点两次折返的间距都用它）；离开的 route 为空时取回库线路
   * @param throatGeometry 车库咽喉的几何；远端多等要同时让端点与咽喉都错得开
   */
  public record Topology(
      Set<String> stubTerminals, FarEndCost farEndCost, ThroatGeometry throatGeometry) {
    public Topology {
      stubTerminals = stubTerminals == null ? Set.of() : Set.copyOf(stubTerminals);
      farEndCost = farEndCost == null ? (group, arriving, departing) -> 0 : farEndCost;
      throatGeometry = throatGeometry == null ? (feeder, back) -> Optional.empty() : throatGeometry;
    }

    /** 不看车库咽喉。 */
    public Topology(Set<String> stubTerminals, FarEndCost farEndCost) {
      this(stubTerminals, farEndCost, null);
    }

    /** 不知道路网形状：不做跨组按车接续，行为与只有两层时相同。 */
    public static Topology none() {
      return new Topology(Set.of(), null, null);
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

  /**
   * 选相位（不知道路网形状，不做跨组按车接续）。
   *
   * @see #plan(List, Map, Map, TurnaroundTable, int, Map, Topology)
   */
  public static Phases plan(
      List<ServiceGroupClassifier.Group> groups,
      Map<String, Integer> intervalByGroup,
      Map<UUID, Integer> runSecondsByRoute,
      TurnaroundTable turnarounds,
      int horizonSeconds,
      Map<String, List<StopCall>> callsByDirection) {
    return plan(
        groups,
        intervalByGroup,
        runSecondsByRoute,
        turnarounds,
        horizonSeconds,
        callsByDirection,
        Topology.none());
  }

  /**
   * 选相位。
   *
   * @param groups 分类结果里的组（按名字排序）；只应包含真正上网格的方向——由派车器在交路收尾处生成的带客回库班不是周期流， 放进来会被当成一条幻影流参与锚定与交错
   * @param intervalByGroup 每组的间隔（秒）
   * @param runSecondsByRoute 每条 route 的全程时分
   * @param turnarounds 折返时间表
   * @param horizonSeconds 计划窗口长度
   * @param callsByDirection 方向键 → 沿途合流点；空表示退回只按共用起点交错
   * @param topology 路网形状：容量 1 的端点与远端占用，用于跨组按车接续
   */
  public static Phases plan(
      List<ServiceGroupClassifier.Group> groups,
      Map<String, Integer> intervalByGroup,
      Map<UUID, Integer> runSecondsByRoute,
      TurnaroundTable turnarounds,
      int horizonSeconds,
      Map<String, List<StopCall>> callsByDirection,
      Topology topology) {
    Objects.requireNonNull(groups, "groups");
    Topology shape = topology == null ? Topology.none() : topology;
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
    // 算不出沿途合流点、只能按起点交错的方向。它们与算得出的那些方向永远对不上（键的形状不同，
    // 见 stopsOf），于是会悄悄退出第二层——所以要记一条 note 说出来。
    List<String> withoutCalls = new ArrayList<>();
    // 跨组按车接续要回看已经放好的方向：它的相位、间隔，以及它的起点是不是自己往返对的锚定端（那一端已经有车喂）。
    Map<String, ServiceGroupClassifier.Direction> placed = new LinkedHashMap<>();
    Map<String, Integer> placedIntervals = new LinkedHashMap<>();
    Set<String> reverseAnchored = new HashSet<>();
    List<Connection> connections = new ArrayList<>();

    for (ServiceGroupClassifier.Group group : groups) {
      if (group.directions().isEmpty()) {
        continue;
      }
      int interval = Math.max(1, intervalByGroup.getOrDefault(group.name(), 1));
      // 第一层：组内相对相位（偏移 0 时的相位），往返对锚定。
      Map<String, Integer> relative =
          anchorReturnPairs(group, interval, runSecondsByRoute, turnarounds, notes);
      reverseAnchored.addAll(reverseKeysOf(group));
      // 第二层的例外：本组有方向能按车接到先放好的别组方向上，整组偏移由车决定，不再按合流点扫描。
      Anchor anchor =
          vehicleAnchorOf(
              group,
              interval,
              relative,
              phases,
              placed,
              placedIntervals,
              reverseAnchored,
              shape.stubTerminals(),
              runSecondsByRoute,
              turnarounds);
      // 第二层：整体偏移。
      int offset = anchor == null ? 0 : anchor.offset();
      if (anchor != null) {
        connections.add(anchor.connection());
        notes.add(
            "交路组 "
                + group.name()
                + " 按车接续："
                + anchor.connection().feederKey()
                + " 到 "
                + anchor.connection().terminal()
                + " 折返 "
                + anchor.feederTurnaround()
                + "s 后接 "
                + anchor.connection().fedKey()
                + "，偏移 "
                + offset
                + "s（不按合流点交错）");
      }
      List<String> shared = new ArrayList<>();
      for (ServiceGroupClassifier.Direction direction : group.directions()) {
        List<StopCall> own = calls.get(direction.key());
        if (!calls.isEmpty() && (own == null || own.isEmpty())) {
          withoutCalls.add(direction.key());
        }
        for (StopCall call : stopsOf(direction, calls)) {
          if (placedByStop.containsKey(call.key()) && !shared.contains(call.key())) {
            shared.add(call.key());
          }
        }
      }
      if (anchor == null && !shared.isEmpty()) {
        int bestOffset = 0;
        int bestGap = Integer.MAX_VALUE;
        int bestTightest = -1;
        for (int candidate = 0; candidate < interval; candidate += SCAN_STEP_SECONDS) {
          int worst = 0;
          int tightest = Integer.MAX_VALUE;
          int measured = 0;
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
            if (gaps == NOT_MEASURABLE) {
              continue; // 这个合流点量不出来（窗口太短、只有一条流），不参与打分
            }
            measured++;
            tightest = Math.min(tightest, gaps[0]);
            worst = Math.max(worst, gaps[1]);
          }
          if (measured == 0) {
            continue; // 这个候选偏移一个合流点都量不出来，没有可比性
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
        placed.put(direction.key(), direction);
        placedIntervals.put(direction.key(), interval);
        for (StopCall call : stopsOf(direction, calls)) {
          placedByStop
              .computeIfAbsent(call.key(), key -> new ArrayList<>())
              .add(streamOf(phase + call.offsetSeconds(), interval, horizonSeconds));
        }
      }
    }
    // 周期余数挪到远端：被接那一班的往返对反向车在远端多等，回到端点时与喂车方向到站错开最远。
    List<Connection> placedConnections = new ArrayList<>(connections.size());
    for (Connection connection : connections) {
      placedConnections.add(
          placeRemainderAtFarEnd(
              connection,
              intervalOfConnection(connection, placedIntervals),
              phases,
              placed,
              runSecondsByRoute,
              turnarounds,
              shape,
              notes));
    }
    if (!withoutCalls.isEmpty()) {
      notes.add("这些方向算不出沿途合流点，只按起点交错，不会与算得出的方向互相错开：" + String.join("、", withoutCalls));
    }
    return new Phases(phases, offsets, Map.of(), notes, List.of(), placedConnections);
  }

  /**
   * 一个方向的合流点；没给停靠点信息时退回「起点站台组，偏移 0」，与只按共用起点交错的老行为一致。
   *
   * <p>注意这条退路的键是<b>光秃秃的站台组</b>，而正常路径的键是 {@code 本站台组→下一站台组}，两者永远
   * 对不上。所以退路上的方向只跟同样走退路的方向交错，跟算得出合流点的方向不会。这是有意的——连它在哪 停靠都不知道，就没有依据说它和谁在同一个站台上合流——但不能悄悄发生，{@link
   * #plan} 会为此记一条 note。
   */
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

  /**
   * 一次按车接续定下的整组偏移。
   *
   * @param connection 接续关系（远端多等尚未定）
   * @param offset 本组因此取的整体偏移
   * @param feederTurnaround 喂车方向在端点的折返
   */
  private record Anchor(Connection connection, int offset, int feederTurnaround) {}

  /** 组内往返对里被锚定的反向方向：它的起点就是锚定端，那一端已经有本对的车喂。 */
  private static Set<String> reverseKeysOf(ServiceGroupClassifier.Group group) {
    Set<String> present = new HashSet<>();
    for (ServiceGroupClassifier.Direction direction : group.directions()) {
      present.add(direction.key());
    }
    Set<String> keys = new HashSet<>();
    for (ServiceGroupClassifier.Direction direction : group.directions()) {
      if (present.contains(direction.reverseKey())
          && direction.key().compareTo(direction.reverseKey()) > 0) {
        keys.add(direction.key());
      }
    }
    return keys;
  }

  /**
   * 本组里第一个能按车接到先放好方向上的喂车方向：它终到容量 1 的端点，先放好的别组方向从同一端点始发、同一间隔， 且那一端不是被接方向自己往返对的锚定端。容量 1
   * 的端点只有一股道，终到与始发必然是同一个节点，派车器接得上。
   *
   * <p>只取第一个：一组只有一个整体偏移，两处接续要求不同偏移时后者照常由派车器按就绪顺序接。
   */
  private static Anchor vehicleAnchorOf(
      ServiceGroupClassifier.Group group,
      int interval,
      Map<String, Integer> relative,
      Map<String, Integer> phases,
      Map<String, ServiceGroupClassifier.Direction> placed,
      Map<String, Integer> placedIntervals,
      Set<String> reverseAnchored,
      Set<String> stubTerminals,
      Map<UUID, Integer> runSecondsByRoute,
      TurnaroundTable turnarounds) {
    if (stubTerminals.isEmpty() || placed.isEmpty()) {
      return null;
    }
    for (ServiceGroupClassifier.Direction feeder : group.directions()) {
      String terminal = feeder.terminalGroup();
      if (!stubTerminals.contains(terminal)) {
        continue;
      }
      for (ServiceGroupClassifier.Direction fed : placed.values()) {
        if (!fed.originGroup().equals(terminal)
            || placedIntervals.getOrDefault(fed.key(), -1) != interval
            || reverseAnchored.contains(fed.key())) {
          continue;
        }
        UUID feederRoute = minRunRoute(feeder, runSecondsByRoute);
        int turnaround =
            feederRoute == null || turnarounds == null ? 0 : turnarounds.secondsFor(feederRoute);
        int wanted =
            Math.floorMod(
                phases.get(fed.key()) - turnaround - minRun(feeder, runSecondsByRoute), interval);
        int offset = Math.floorMod(wanted - relative.get(feeder.key()), interval);
        return new Anchor(
            new Connection(
                terminal,
                feeder.key(),
                fed.key(),
                "",
                feeder.routeIds(),
                fed.routeIds(),
                0,
                Integer.MAX_VALUE),
            offset,
            turnaround);
      }
    }
    return null;
  }

  private static int intervalOfConnection(
      Connection connection, Map<String, Integer> placedIntervals) {
    return Math.max(1, placedIntervals.getOrDefault(connection.fedKey(), 1));
  }

  /**
   * 周期余数落点：被接方向往返对的反向车在远端多等 {@code w}，挑让端点上两次折返、车库咽喉上出库与回库都错得最开的那个。
   *
   * <p>端点上：喂车方向那次折返占 {@code [到站, 到站 + 占用)}，反向车那次同理（离开走的是回库线路）；两段在一个周期里的最小间隙。
   * 咽喉上：喂车方向出库那段与反向车折返后回库那段的最小间隙（出入段分线、或根本不经过同一个咽喉时不看）。 取两个间隙里较小的那个最大，并列取较小的 {@code w}。
   * 只看端点会把咽喉挤到正好贴着：实测 WS@150 端点 75/75 而咽喉间隙 0，咽喉一串行，库里多等几秒的出库车晚到端点，全天积成滞后。
   *
   * <p>{@code w} 的上限 = 间隔 − 远端一次折返的占用：多等得再久，下一班到远端时上一辆车还占着那股道。 没有反向（被接方向不回这个端点）时什么都不做。
   */
  private static Connection placeRemainderAtFarEnd(
      Connection connection,
      int interval,
      Map<String, Integer> phases,
      Map<String, ServiceGroupClassifier.Direction> placed,
      Map<UUID, Integer> runSecondsByRoute,
      TurnaroundTable turnarounds,
      Topology topology,
      List<String> notes) {
    ServiceGroupClassifier.Direction fed = placed.get(connection.fedKey());
    ServiceGroupClassifier.Direction feeder = placed.get(connection.feederKey());
    ServiceGroupClassifier.Direction back = fed == null ? null : placed.get(fed.reverseKey());
    if (feeder == null || back == null || !back.terminalGroup().equals(connection.terminal())) {
      return connection;
    }
    UUID feederRoute = minRunRoute(feeder, runSecondsByRoute);
    UUID fedRoute = minRunRoute(fed, runSecondsByRoute);
    UUID backRoute = minRunRoute(back, runSecondsByRoute);
    int feederDeparture = phases.get(feeder.key());
    int feederArrival =
        Math.floorMod(feederDeparture + minRun(feeder, runSecondsByRoute), interval);
    int arrival = Math.floorMod(phases.get(back.key()) + minRun(back, runSecondsByRoute), interval);
    FarEndCost cost = topology.farEndCost();
    int feederVisit = cost.occupiedSeconds(connection.terminal(), feederRoute, fedRoute);
    int backVisit = cost.occupiedSeconds(connection.terminal(), backRoute, null);
    Optional<ThroatWindows> throat = topology.throatGeometry().windows(feederRoute, backRoute);
    int farCost = cost.occupiedSeconds(fed.terminalGroup(), fedRoute, backRoute);
    int maxWait = Math.max(0, interval - farCost);
    int best = 0;
    int bestScore = Integer.MIN_VALUE;
    int bestTerminalGap = 0;
    int bestThroatGap = 0;
    for (int w = 0; w <= maxWait; w++) {
      int backArrival = arrival + w;
      int terminalGap =
          arcGap(
              feederArrival,
              feederArrival + feederVisit,
              backArrival,
              backArrival + backVisit,
              interval);
      int throatGap =
          throat
              .map(
                  t ->
                      arcGap(
                          feederDeparture + t.outEnter(),
                          feederDeparture + t.outExit(),
                          backArrival + t.inEnter(),
                          backArrival + t.inExit(),
                          interval))
              .orElse(Integer.MAX_VALUE);
      int score = Math.min(terminalGap, throatGap);
      if (score > bestScore) {
        bestScore = score;
        best = w;
        bestTerminalGap = terminalGap;
        bestThroatGap = throatGap;
      }
    }
    phases.put(back.key(), Math.floorMod(phases.get(back.key()) + best, interval));
    notes.add(
        back.key()
            + " 在 "
            + fed.terminalGroup()
            + " 多等 "
            + best
            + "s（上限 "
            + maxWait
            + "s = 间隔 − 远端占用 "
            + farCost
            + "s）：回到 "
            + connection.terminal()
            + " 与 "
            + connection.feederKey()
            + " 到站相距 "
            + circularDistance(arrival + best, feederArrival, interval)
            + "s、两次折返间隙 "
            + bestTerminalGap
            + "s"
            + (throat.isPresent() ? "，车库咽喉上出库与回库间隙 " + bestThroatGap + "s" : "")
            + (bestScore < 0 ? "（上限内错不开，这个间隔下端点或咽喉必有冲突）" : ""));
    return new Connection(
        connection.terminal(),
        connection.feederKey(),
        connection.fedKey(),
        back.key(),
        connection.feederRoutes(),
        connection.fedRoutes(),
        best,
        bestScore);
  }

  /** 圆周（周长 = 间隔）上两段弧之间的最小间隙；负数是重叠的秒数。 */
  static int arcGap(int aFrom, int aTo, int bFrom, int bTo, int period) {
    int lengthA = aTo - aFrom;
    int lengthB = bTo - bFrom;
    if (lengthA + lengthB >= period) {
      return period - lengthA - lengthB;
    }
    int aThenB = Math.floorMod(bFrom - aTo, period);
    int bThenA = Math.floorMod(aFrom - bTo, period);
    int startGap = Math.floorMod(bFrom - aFrom, period);
    if (startGap < lengthA) {
      return -Math.min(lengthA - startGap, lengthB);
    }
    if (period - startGap < lengthB) {
      return -Math.min(lengthB - (period - startGap), lengthA);
    }
    return Math.min(aThenB, bThenA);
  }

  private static int circularDistance(int a, int b, int interval) {
    int diff = Math.floorMod(a - b, interval);
    return Math.min(diff, interval - diff);
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

  /** {@link #gapRange} 量不出来时的回答：与"间隔为 0"必须分开，见那里的说明。 */
  private static final int[] NOT_MEASURABLE = {0, 0};

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
   * <p>量不出来时交回 {@link #NOT_MEASURABLE} 而不是 {@code {0, 0}}：扫描按"最大间隔最小"选偏移， 而 0 是最小的可能值——把量不出来当成
   * 0，等于给这个候选判了满分，它会盖过真正交错得好的那些。
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
    // 宽度为 0 的公共区间同样量不出来：各流的首末班正好重合时，合起来只有一个时刻，相邻间隔算出来是
    // 0——而 0 是"最大间隔最小"这个目标下的满分。也就是说"所有车同时发"会拿到最好的分数。
    if (present == 0 || from >= to) {
      return NOT_MEASURABLE;
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
      return NOT_MEASURABLE;
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
