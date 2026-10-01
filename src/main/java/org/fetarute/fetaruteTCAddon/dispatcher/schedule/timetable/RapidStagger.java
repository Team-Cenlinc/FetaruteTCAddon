package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;

/**
 * 快车错峰：用完整编表逐个试快车组的平移量与快车停站，按成品表实测的快车被卡秒数挑。
 *
 * <h2>为什么要用完整编表来挑</h2>
 *
 * <p>快车跟在慢车后面进同一段共线就会被一路拖住（{@link CorridorCatchUp}）。第三层的周期评估能估这件事，但估不准：
 * 它只看各方向自己的相位，看不到让车修复按"谁先进资源"把快车推到慢车后面，也看不到一个方向被推后之后、原地折返接续的反方向跟着晚发。
 * 周期评估选出的位置在成品表上仍可能大半班次被卡。所以这里每个候选都完整编一遍，在成品表上量。
 *
 * <h2>试什么</h2>
 *
 * <ol>
 *   <li>含快车的交路组整组平移（往返两个方向一起，车的周转不变），每 {@value #STEP_SECONDS} 秒一档扫一个相对周期，再在最好的位置两侧各试 {@value
 *       #REFINE_SECONDS} 秒。每次编表照常带着喂车多停的搜索：周围接续的车加一点停站，往往正是错开快车的那一下。
 *   <li>前几个最好的位置上，再给仍被拖住的快车在那段共线之前的最后一个停车站加停（{@link #DWELL_STEPS}）： 两段共线各有各的空档时，起点平移对不上第二段，中途停站可以。
 * </ol>
 *
 * <h2>怎么挑</h2>
 *
 * <p>候选必须在目标间隔下排得开（不放宽）、班次不比原表少、高峰车数不比原表多；在这些里取快车被卡秒数最少的，并列时取运行时残余少、 让车少、改动小的。比原表好才换。被卡秒数为 0
 * 已经到底：平移扫到第一段完全错开的窗口、把这段扫完就停，也不再试加停—— 每个候选都是一次完整编表，大路网上一次十几秒到半分钟。
 */
final class RapidStagger {

  /** 平移的扫描步长。 */
  static final int STEP_SECONDS = 10;

  /** 在最好的平移两侧各再试的秒数。 */
  static final int REFINE_SECONDS = 5;

  /** 快车中途加停试哪几档。 */
  static final List<Integer> DWELL_STEPS = List.of(15, 30, 45, 60);

  /** 在前几个最好的平移上试加停。 */
  static final int DWELL_SHORTLIST = 3;

  private RapidStagger() {}

  /**
   * 成品表上的快车被卡情况。
   *
   * @param seconds 被拖住、超过一个裕量的秒数合计
   * @param trips 被拖住的班次数
   * @param caught 逐条明细
   */
  record Measure(long seconds, int trips, List<CorridorCatchUp.Caught> caught) {
    Measure {
      caught = List.copyOf(caught);
    }
  }

  /**
   * 量一张成品表。
   *
   * @param table 成品表
   * @param profiles 交路投影
   * @param index 图索引
   * @param separation 裕量
   * @param zeroSecondOfDay 计划窗口起点（日内秒）
   */
  static Measure measure(
      Timetable table,
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
      TimetableConflictChecker.GraphIndex index,
      int separation,
      int zeroSecondOfDay) {
    CorridorCatchUp corridors = new CorridorCatchUp(profiles, index, separation);
    List<CorridorCatchUp.Caught> caught =
        corridors.caught(
            TimetableOccupancyProjector.project(table, profiles, zeroSecondOfDay).movements());
    long seconds = 0L;
    Set<String> trips = new TreeSet<>();
    for (CorridorCatchUp.Caught one : caught) {
      seconds += one.seconds();
      trips.add(one.code());
    }
    return new Measure(seconds, trips.size(), caught);
  }

  /** 含快车（某段共线上比别的交路明显更快）的交路组名，按名字排序。 */
  static List<String> fastGroups(
      List<ServiceGroupClassifier.Group> groups,
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
      TimetableConflictChecker.GraphIndex index,
      int separation) {
    Set<UUID> routes = new TreeSet<>();
    for (ServiceGroupClassifier.Group group : groups) {
      for (ServiceGroupClassifier.Direction direction : group.directions()) {
        routes.addAll(direction.routeIds());
      }
    }
    Set<UUID> faster = new CorridorCatchUp(profiles, index, separation).fasterRoutes(routes);
    List<String> out = new ArrayList<>();
    for (ServiceGroupClassifier.Group group : groups) {
      boolean fast =
          group.directions().stream()
              .anyMatch(direction -> direction.routeIds().stream().anyMatch(faster::contains));
      if (fast) {
        out.add(group.name());
      }
    }
    out.sort(Comparator.naturalOrder());
    return List.copyOf(out);
  }

  /**
   * 平移的相对周期：组间隔 I 与其它组间隔 I_k 之间，平移 gcd(I, I_k) 的整数倍对第 k 组是同一个相对位置；对所有组都一样的最小平移就是各个 gcd
   * 的最小公倍数。扫这一段就够了。
   */
  static int period(int interval, Collection<Integer> others) {
    long out = 1L;
    for (int other : others) {
      long gcd = gcd(interval, other);
      out = out / gcd(out, gcd) * gcd;
    }
    // 各 gcd 都整除 interval，它们的最小公倍数也整除 interval；全为 1（间隔互质）时只能扫满一个间隔。
    return out == 1L ? interval : (int) out;
  }

  /** 一个周期内的平移档（不含 0）。 */
  static List<Integer> shifts(int period) {
    List<Integer> out = new ArrayList<>();
    for (int shift = STEP_SECONDS; shift < period; shift += STEP_SECONDS) {
      out.add(shift);
    }
    return List.copyOf(out);
  }

  /**
   * 一次编表结果里挑选要看的几个数。
   *
   * @param success 排出了表
   * @param relaxed 放宽了间隔
   * @param trips 班次数
   * @param peak 高峰车数
   * @param absorbable 留给运行时去让的残余冲突数
   * @param yields 写进表的让车数
   */
  record Outcome(
      boolean success, boolean relaxed, int trips, int peak, int absorbable, int yields) {
    static Outcome of(TimetableBuildResult result) {
      return new Outcome(
          result.success(),
          result.headwayRelaxed(),
          result.tripCount(),
          result.peakConcurrentVehicles(),
          result.absorbable().size(),
          result.yields().size());
    }
  }

  /** 候选可用：目标间隔下排得开（不放宽）、班次不少、高峰车数不多。 */
  static boolean acceptable(Outcome base, Outcome candidate) {
    return candidate.success()
        && !candidate.relaxed()
        && candidate.trips() >= base.trips()
        && candidate.peak() <= base.peak();
  }

  /**
   * 一个候选。
   *
   * @param shift 交路组 → 平移秒数
   * @param dwell 快车中途加停（没有时为空）
   * @param result 编表结果
   * @param outcome 结果里挑选要看的几个数
   * @param measure 实测
   */
  record Candidate(
      Map<String, Integer> shift,
      Optional<Dwell> dwell,
      TimetableBuildResult result,
      Outcome outcome,
      Measure measure) {
    Candidate {
      shift = Collections.unmodifiableSortedMap(new TreeMap<>(shift));
    }

    /** 改动大小：平移秒数与加停秒数之和，并列时取改得少的。 */
    int change() {
      return shift.values().stream().mapToInt(Math::abs).sum()
          + dwell.map(Dwell::seconds).orElse(0);
    }
  }

  /**
   * 快车在某个中途停车站加停。
   *
   * @param routeId 快车交路
   * @param routeCode 交路 code（报告用）
   * @param stopIndex 停靠点下标（与停靠配置、路径点对齐）
   * @param nodeId 停靠点节点（报告用）
   * @param seconds 加停秒数
   */
  record Dwell(UUID routeId, String routeCode, int stopIndex, String nodeId, int seconds) {}

  /** 挑选顺序：被卡秒数少的优先，并列时留给运行时去让的残余少的、写进表的让车少的、改动小的。 */
  static final Comparator<Candidate> ORDER =
      Comparator.comparingLong((Candidate c) -> c.measure().seconds())
          .thenComparingInt(c -> c.outcome().absorbable())
          .thenComparingInt(c -> c.outcome().yields())
          .thenComparingInt(Candidate::change);

  /**
   * 快车在被拖住最多的那段共线之前的最后一个中途停车站（不含起终点）。
   *
   * @param measure 实测
   * @param profiles 交路投影
   * @param routeCodes 交路 code
   * @return 每条仍被拖住的快车交路一个加停点（停站秒数为 0，由调用方填档位）
   */
  static List<Dwell> dwellPoints(
      Measure measure,
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
      Map<UUID, String> routeCodes) {
    Map<UUID, Map<Integer, Long>> byEntry = new TreeMap<>();
    for (CorridorCatchUp.Caught one : measure.caught()) {
      byEntry
          .computeIfAbsent(one.routeId(), key -> new TreeMap<>())
          .merge(one.corridorEntry(), (long) one.seconds(), Long::sum);
    }
    List<Dwell> out = new ArrayList<>();
    byEntry.forEach(
        (routeId, entries) -> {
          int entry =
              entries.entrySet().stream()
                  .max(Map.Entry.<Integer, Long>comparingByValue())
                  .map(Map.Entry::getKey)
                  .orElse(0);
          TimetableConflictChecker.RouteProfile profile = profiles.get(routeId);
          if (profile == null) {
            return;
          }
          List<TimetableStop> stops = profile.stops();
          for (int i = stops.size() - 2; i >= 1; i--) {
            TimetableStop stop = stops.get(i);
            if (stop.passType() == RouteStopPassType.STOP
                && stop.departureOffsetSeconds() <= entry) {
              out.add(
                  new Dwell(
                      routeId,
                      routeCodes.getOrDefault(routeId, routeId.toString()),
                      i,
                      stop.nodeId().orElse("#" + i),
                      0));
              return;
            }
          }
        });
    return List.copyOf(out);
  }

  /** 报告一行：成品表上快车被卡的情况，按快车交路分开，点名拖住它最多的慢车。 */
  static String describe(Measure measure, Map<UUID, String> routeCodes) {
    if (measure.seconds() == 0L) {
      return "快车被卡（成品表实测）：无";
    }
    Map<String, Long> byRoute = new TreeMap<>();
    Map<String, Set<String>> tripsByRoute = new HashMap<>();
    Map<String, Map<String, Long>> blockers = new HashMap<>();
    for (CorridorCatchUp.Caught one : measure.caught()) {
      String code = routeCodes.getOrDefault(one.routeId(), one.routeId().toString());
      byRoute.merge(code, (long) one.seconds(), Long::sum);
      tripsByRoute.computeIfAbsent(code, key -> new TreeSet<>()).add(one.code());
      blockers
          .computeIfAbsent(code, key -> new TreeMap<>())
          .merge(
              routeCodes.getOrDefault(one.blockingRouteId(), one.blockingRouteId().toString()),
              (long) one.seconds(),
              Long::sum);
    }
    List<String> parts = new ArrayList<>();
    byRoute.forEach(
        (code, seconds) -> {
          String blocker =
              blockers.get(code).entrySet().stream()
                  .max(Map.Entry.<String, Long>comparingByValue())
                  .map(Map.Entry::getKey)
                  .orElse("-");
          parts.add(
              String.format(
                  Locale.ROOT,
                  "%s %d 班、共 %ds（主要被 %s 拖住）",
                  code,
                  tripsByRoute.get(code).size(),
                  seconds,
                  blocker));
        });
    return "快车被卡（成品表实测）：" + String.join("；", parts);
  }

  /** 编一个候选并量成品表；不可用（排不开、放宽、少班次、加车）时为空。 */
  interface Evaluator {
    /** 各组按 {@code shift} 平移。 */
    Optional<Candidate> shift(Map<String, Integer> shift);

    /** 各组按 {@code shift} 平移，再让一班快车中途加停。 */
    Optional<Candidate> dwell(Map<String, Integer> shift, Dwell dwell);
  }

  /**
   * 搜索结果。
   *
   * @param improved 比原表被卡更少的最好候选；没有时为空
   * @param tried 编了几个候选
   */
  record Search(Optional<Candidate> improved, int tried) {}

  /**
   * 搜索：先整组平移，再试快车中途加停，取 {@link #ORDER} 最前、且被卡秒数比原表少的候选。
   *
   * <ol>
   *   <li>每个快车组按名字序扫自己的相对周期（每 {@value #STEP_SECONDS} 秒一档，不含 0），再在最好的位置两侧各试 {@value #REFINE_SECONDS}
   *       秒；组与组之间贪心，前面定下的不再动。扫到第一段完全错开（被卡 0）的窗口、把这段扫完就停。
   *   <li>平移没能完全错开时，在前 {@value #DWELL_SHORTLIST} 个最好的平移上（一个都没有就在原表上）给仍被拖住的快车加停。
   * </ol>
   *
   * @param periods 快车组 → 平移的相对周期
   * @param base 原表
   * @param dwellPoints 由实测给出各快车的加停点
   * @param evaluator 编候选
   */
  static Search search(
      Map<String, Integer> periods,
      Candidate base,
      Function<Measure, List<Dwell>> dwellPoints,
      Evaluator evaluator) {
    // 每个候选都带着一整张表：只留排名前几的（第二轮要用），其余算完即丢，免得几十张表一起压在堆上。
    List<Candidate> top = new ArrayList<>();
    Candidate best = null;
    Map<String, Integer> shift = new TreeMap<>();
    int tried = 0;
    for (Map.Entry<String, Integer> entry : new TreeMap<>(periods).entrySet()) {
      String group = entry.getKey();
      int period = entry.getValue();
      List<Integer> offsets = shifts(period);
      Integer bestOffset = null;
      for (int round = 0; round < 2; round++) {
        boolean inClearWindow = false;
        for (int offset : offsets) {
          Map<String, Integer> next = new TreeMap<>(shift);
          next.put(group, offset);
          tried++;
          Optional<Candidate> candidate = evaluator.shift(next);
          boolean clear = candidate.isPresent() && candidate.get().measure().seconds() == 0L;
          if (inClearWindow && !clear) {
            // 第一段完全错开的窗口扫完了：被卡 0 已到底，后面的位置只可能在次要指标上略好，不值得每个再编一遍。
            break;
          }
          inClearWindow |= clear;
          if (candidate.isEmpty()) {
            continue;
          }
          keepTop(top, candidate.get());
          if (best == null || ORDER.compare(candidate.get(), best) < 0) {
            best = candidate.get();
            bestOffset = offset;
          }
        }
        if (bestOffset == null) {
          break;
        }
        int around = bestOffset;
        offsets =
            List.of(around - REFINE_SECONDS, around + REFINE_SECONDS).stream()
                .filter(offset -> offset > 0 && offset < period)
                .toList();
      }
      if (best != null) {
        shift.putAll(best.shift());
      }
    }
    // 平移已经完全错开时加停不会更好，不试。
    if (best == null || best.measure().seconds() > 0L) {
      List<Candidate> starts = top.isEmpty() ? List.of(base) : List.copyOf(top);
      for (Candidate start : starts) {
        if (start.measure().seconds() == 0L) {
          continue;
        }
        for (Dwell point : dwellPoints.apply(start.measure())) {
          for (int seconds : DWELL_STEPS) {
            tried++;
            Optional<Candidate> candidate =
                evaluator.dwell(
                    start.shift(),
                    new Dwell(
                        point.routeId(),
                        point.routeCode(),
                        point.stopIndex(),
                        point.nodeId(),
                        seconds));
            if (candidate.isPresent()
                && (best == null || ORDER.compare(candidate.get(), best) < 0)) {
              best = candidate.get();
            }
          }
        }
      }
    }
    boolean improved = best != null && best.measure().seconds() < base.measure().seconds();
    return new Search(improved ? Optional.of(best) : Optional.empty(), tried);
  }

  /** 把候选放进按 {@link #ORDER} 排好的前 {@link #DWELL_SHORTLIST} 名里，多出来的丢掉。 */
  static void keepTop(List<Candidate> top, Candidate candidate) {
    top.add(candidate);
    top.sort(ORDER);
    while (top.size() > DWELL_SHORTLIST) {
      top.remove(top.size() - 1);
    }
  }

  /** 报告一行：错峰搜索的结论；没有比原表更好的候选时 {@code chosen} 为空。 */
  static String describeSearch(Measure base, Optional<Candidate> chosen, int tried, long millis) {
    if (chosen.isEmpty()) {
      return String.format(
          Locale.ROOT,
          "快车错峰：试了 %d 个位置（目标间隔排得开、班次不少、不加车），没有比原表被卡更少的，保持原表（被卡 %ds），用时 %ds",
          tried,
          base.seconds(),
          millis / 1000L);
    }
    Candidate best = chosen.get();
    List<String> changes = new ArrayList<>();
    best.shift()
        .forEach(
            (group, seconds) -> {
              if (seconds != 0) {
                changes.add(group + " 整组再平移 " + seconds + "s");
              }
            });
    best.dwell()
        .ifPresent(
            dwell ->
                changes.add(
                    dwell.routeCode() + " 在 " + dwell.nodeId() + " 多停 " + dwell.seconds() + "s"));
    return String.format(
        Locale.ROOT,
        "快车错峰：%s，快车被卡 %ds → %ds（%d → %d 班），试了 %d 个位置，用时 %ds",
        String.join("、", changes),
        base.seconds(),
        best.measure().seconds(),
        base.trips(),
        best.measure().trips(),
        tried,
        millis / 1000L);
  }

  private static long gcd(long a, long b) {
    long x = Math.abs(a);
    long y = Math.abs(b);
    while (y != 0) {
      long t = x % y;
      x = y;
      y = t;
    }
    return Math.max(1L, x);
  }
}
