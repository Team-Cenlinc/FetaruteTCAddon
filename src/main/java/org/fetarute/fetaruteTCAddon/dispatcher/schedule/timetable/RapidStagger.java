package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;

/**
 * 快车错峰：用完整编表逐个试原地折返端的折返、快车组的平移量与快车停站，按成品表实测的快车被卡秒数挑。
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
 *   <li>原地折返端加长折返：快车从一个原地折返端出发、接的是反方向快车终到折返的那辆车时，整组平移把往返两个方向一起挪，
 *       一个方向错开了另一个方向就撞上；只把这一端的折返加长，才能把这个方向单独往后推。终点站长时间停车不占正线，加多少由实测给起点（被拖住秒数）， 再每 {@value
 *       #TURNBACK_STEP_SECONDS} 秒一档往长试到 {@value #TURNBACK_REACH_SECONDS} 秒：刚好错开的那一档往往只是把被卡改成表里的等待，
 *       多停一点才干净。只编十来次，所以排在最前，错开了就不再平移。原表已放宽时只试这一步：加长折返本身可能就让目标间隔排得开。
 *   <li>含快车的交路组整组平移（往返两个方向一起，车的周转不变），每 {@value #STEP_SECONDS} 秒一档扫一个相对周期，再在最好的位置两侧各试 {@value
 *       #REFINE_SECONDS} 秒。每次编表照常带着喂车多停的搜索：周围接续的车加一点停站，往往正是错开快车的那一下。
 *   <li>前几个最好的位置上，再给仍被拖住的快车在那段共线之前的最后一个停车站加停（{@link #DWELL_STEPS}）： 两段共线各有各的空档时，起点平移对不上第二段，中途停站可以。
 * </ol>
 *
 * <h2>怎么挑</h2>
 *
 * <p>候选必须在目标间隔下排得开（不放宽）、班次不比原表少、高峰车数最多比原表多 {@value #MAX_EXTRA_VEHICLES} 列。在这些里先比全网损失：
 * 快车被卡秒数加上表里写的所有班次让车等待——快车不被卡、却靠表里让普通车或快车自己等出来的，并没有更好。再依次比快车损失（被卡加快车自己的等待）、
 * 运行时残余、用车、让车处数、改动大小。快车损失比原表少、全网损失与运行时残余都不比原表多才换：不能拿普通车往后推去换快车， 写进表的等待与留到运行时才让的冲突都算。
 *
 * <p>被卡秒数为 0 已经到底：平移扫到第一段完全错开的窗口、把这段扫完就停，也不再试加停—— 每个候选都是一次完整编表，大路网上一次十几秒到半分钟。
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

  /** 原地折返端加长折返：在实测被拖住秒数之上每档多加的秒数。 */
  static final int TURNBACK_STEP_SECONDS = 30;

  /** 原地折返端加长折返：在实测被拖住秒数之上最多再加的秒数。 */
  static final int TURNBACK_REACH_SECONDS = 300;

  /** 原地折返端加长折返：在实测被拖住秒数之上依次加的余量，从 0 起。 */
  static final List<Integer> TURNBACK_MARGINS =
      IntStream.rangeClosed(0, TURNBACK_REACH_SECONDS / TURNBACK_STEP_SECONDS)
          .mapToObj(step -> step * TURNBACK_STEP_SECONDS)
          .toList();

  /** 为了快车不被卡，高峰最多比原表多用几列车。 */
  static final int MAX_EXTRA_VEHICLES = 1;

  private RapidStagger() {}

  /**
   * 成品表上的快车被卡情况。
   *
   * @param seconds 被拖住、超过一个裕量的秒数合计
   * @param trips 被拖住的班次数
   * @param caught 逐条明细
   * @param held 表里写给快车的让车等待合计：被卡改成在表里等，快车照样慢了这么多
   */
  record Measure(long seconds, int trips, List<CorridorCatchUp.Caught> caught, long held) {
    Measure {
      caught = List.copyOf(caught);
    }

    /** 快车损失：被卡加表里的等待。 */
    long lost() {
      return seconds + held;
    }
  }

  /**
   * 量一张成品表：只算快车交路的班次被拖住。
   *
   * <p>投影里还有出库、回库走行，它们不停站，在共线段跟着慢车也会被"拖住"，但那不是快车——算进来会让报告点名走行线路、
   * 让错峰去搜一个没有快车组可挪的问题。挡在前面的车则什么都算：走行挡住快车也是挡。
   *
   * @param table 成品表
   * @param profiles 交路投影
   * @param index 图索引
   * @param separation 裕量
   * @param following 跟车规则：启用时被拖住按闭塞时间算（与运行时放行同一口径），否则按占用区间
   * @param trajectories 闭塞时间用的逐点轨迹
   * @param zeroSecondOfDay 计划窗口起点（日内秒）
   * @param fastRoutes 快车交路（{@link #fastRoutes}）
   * @param yields 表里写的让车（量快车自己的等待）
   */
  static Measure measure(
      Timetable table,
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
      TimetableConflictChecker.GraphIndex index,
      int separation,
      TimetableBuildOptions.Following following,
      BlockingTimes.Trajectories trajectories,
      int zeroSecondOfDay,
      Set<UUID> fastRoutes,
      List<ResourceRepair.Yield> yields) {
    CorridorCatchUp corridors =
        new CorridorCatchUp(profiles, index, separation, following, trajectories);
    List<CorridorCatchUp.Caught> caught =
        corridors
            .caught(
                TimetableOccupancyProjector.project(table, profiles, zeroSecondOfDay).movements())
            .stream()
            .filter(one -> fastRoutes.contains(one.routeId()))
            .toList();
    long seconds = 0L;
    Set<String> trips = new TreeSet<>();
    for (CorridorCatchUp.Caught one : caught) {
      seconds += one.seconds();
      trips.add(one.code());
    }
    Set<String> fastTrips = new HashSet<>();
    for (TimetableTrip trip : table.trips()) {
      if (fastRoutes.contains(trip.routeId())) {
        fastTrips.add(trip.tripCode());
      }
    }
    long held =
        yields.stream()
            .filter(yield -> fastTrips.contains(yield.second()))
            .mapToLong(ResourceRepair.Yield::waitSeconds)
            .sum();
    return new Measure(seconds, trips.size(), caught, held);
  }

  /** 实测的合计，交给构建结果。 */
  static TimetableBuildResult.CatchUp total(Measure measure) {
    return new TimetableBuildResult.CatchUp(measure.seconds(), measure.trips(), measure.held());
  }

  /** 快车交路：排进发车表的运营交路里，某段共线上比另一条明显更快（快出一个裕量以上）的。 */
  static Set<UUID> fastRoutes(
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
    return new CorridorCatchUp(profiles, index, separation).fasterRoutes(routes);
  }

  /** 含快车交路的交路组名，按名字排序。 */
  static List<String> fastGroups(List<ServiceGroupClassifier.Group> groups, Set<UUID> fastRoutes) {
    List<String> out = new ArrayList<>();
    for (ServiceGroupClassifier.Group group : groups) {
      boolean fast =
          group.directions().stream()
              .anyMatch(direction -> direction.routeIds().stream().anyMatch(fastRoutes::contains));
      if (fast) {
        out.add(group.name());
      }
    }
    out.sort(Comparator.naturalOrder());
    return List.copyOf(out);
  }

  /** 一次构建拿到实测之后怎么办。 */
  enum Plan {
    /** 没开错峰，或快车没有损失：只报实测。 */
    MEASURE_ONLY,
    /** 开了错峰，但原表本身已放宽：平移与中途加停的候选在目标间隔下多半排不开，只试原地折返端加长折返。 */
    TURNBACK_ONLY,
    /** 搜。 */
    SEARCH
  }

  /** 开了错峰、快车有损失（被卡或在表里让车等待）时才搜；原表已放宽时只试加长折返。 */
  static Plan plan(boolean enabled, Measure measure, boolean relaxed) {
    if (!enabled || measure.lost() == 0L) {
      return Plan.MEASURE_ONLY;
    }
    return relaxed ? Plan.TURNBACK_ONLY : Plan.SEARCH;
  }

  /** 把实测写进构建结果：报告一行说明，加上结构化的合计（报告据此决定给不给错峰重建按钮）。 */
  static TimetableBuildResult annotate(
      TimetableBuildResult result, Measure measure, Map<UUID, String> routeCodes) {
    return result.withRapidCatchUp(total(measure)).withPhaseNote(describe(measure, routeCodes));
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
   * @param waited 班次在表里的让车等待秒数合计（所有班次，含快车自己；见 {@link #tripWaitSeconds}）
   */
  record Outcome(
      boolean success,
      boolean relaxed,
      int trips,
      int peak,
      int absorbable,
      int yields,
      long waited) {

    static Outcome of(TimetableBuildResult result) {
      return new Outcome(
          result.success(),
          result.headwayRelaxed(),
          result.tripCount(),
          result.peakConcurrentVehicles(),
          result.absorbable().size(),
          result.yields().size(),
          tripWaitSeconds(result));
    }
  }

  /**
   * 班次在表里的让车等待合计。待命（让车方是交路号）与出入库走行（{@code -CREATE}/{@code -RETURN}）的等待不耽误乘客，不算。
   *
   * @param result 编表结果
   * @return 等待秒数
   */
  static long tripWaitSeconds(TimetableBuildResult result) {
    Set<String> trips = new HashSet<>();
    result
        .timetable()
        .ifPresent(table -> table.trips().forEach(trip -> trips.add(trip.tripCode())));
    return result.yields().stream()
        .filter(yield -> trips.contains(yield.second()))
        .mapToLong(ResourceRepair.Yield::waitSeconds)
        .sum();
  }

  /**
   * 候选可用：目标间隔下排得开（不放宽）、班次不少、高峰最多多用 {@value #MAX_EXTRA_VEHICLES} 列车。
   *
   * <p>加长折返让车在端点多停，交路周转变长，有时要多一列车才排得下；为了快车一路不被拖住，值得多这一列。
   */
  static boolean acceptable(Outcome base, Outcome candidate) {
    return candidate.success()
        && !candidate.relaxed()
        && candidate.trips() >= base.trips()
        && candidate.peak() <= base.peak() + MAX_EXTRA_VEHICLES;
  }

  /**
   * 一个候选。
   *
   * @param shift 交路组 → 平移秒数
   * @param dwell 快车中途加停或终到折返加长（没有时为空）
   * @param result 编表结果
   * @param outcome 结果里挑选要看的几个数
   * @param measure 搜索用的实测：搜索里的取舍与早停看它（按占用区间量，见 {@link Picks}）
   * @param reported 报告与最终挑选用的实测（按闭塞时间量）；没有跟车规则时与 {@code measure} 相同
   */
  record Candidate(
      Map<String, Integer> shift,
      Optional<Dwell> dwell,
      TimetableBuildResult result,
      Outcome outcome,
      Measure measure,
      Measure reported) {
    Candidate {
      shift = Collections.unmodifiableSortedMap(new TreeMap<>(shift));
      reported = reported == null ? measure : reported;
    }

    /** 搜索与报告同一口径。 */
    Candidate(
        Map<String, Integer> shift,
        Optional<Dwell> dwell,
        TimetableBuildResult result,
        Outcome outcome,
        Measure measure) {
      this(shift, dwell, result, outcome, measure, measure);
    }

    /** 按报告口径看这个候选：挑最终答案、写报告用。 */
    Candidate asReported() {
      return new Candidate(shift, dwell, result, outcome, reported, reported);
    }

    /** 改动大小：平移秒数与加停秒数之和，并列时取改得少的。 */
    int change() {
      return shift.values().stream().mapToInt(Math::abs).sum()
          + dwell.map(Dwell::seconds).orElse(0);
    }

    /** 快车损失：被卡加快车自己在表里的等待。 */
    long rapidLost() {
      return measure.lost();
    }

    /** 全网损失：快车被卡加所有班次在表里的让车等待。 */
    long networkLost() {
      return measure.seconds() + outcome.waited();
    }

    /**
     * 比原表好：快车损失更少，全网损失不更多，留给运行时去让的残余也不更多。
     *
     * <p>后两条都是不拿普通车往后推去换快车：一条管写进表的等待，一条管没写进表、到运行时才让的冲突。
     */
    boolean betterThan(Candidate base) {
      return rapidLost() < base.rapidLost()
          && networkLost() <= base.networkLost()
          && outcome.absorbable() <= base.outcome().absorbable();
    }
  }

  /**
   * 快车在某个停靠点多停：中途停车站加停，或终到站加长折返（终到停靠的停站时间就是折返时间）。
   *
   * @param routeId 快车交路
   * @param routeCode 交路 code（报告用）
   * @param stopIndex 停靠点下标（与停靠配置、路径点对齐）
   * @param nodeId 停靠点节点（报告用）
   * @param seconds 多停秒数
   * @param turnback 是终到折返加长（报告措辞不同）
   */
  record Dwell(
      UUID routeId, String routeCode, int stopIndex, String nodeId, int seconds, boolean turnback) {

    /** 中途停车站加停。 */
    Dwell(UUID routeId, String routeCode, int stopIndex, String nodeId, int seconds) {
      this(routeId, routeCode, stopIndex, nodeId, seconds, false);
    }

    /** 同一个点、换一个秒数。 */
    Dwell withSeconds(int next) {
      return new Dwell(routeId, routeCode, stopIndex, nodeId, next, turnback);
    }
  }

  /**
   * 挑选顺序：全网损失少的优先，并列时快车损失少的、留给运行时去让的残余少的、用车少的、写进表的让车少的、改动小的。
   *
   * <p>用车排在残余之后：多用的那一列在 {@link #acceptable} 的额度内，就是为了换一张更干净的表。
   */
  static final Comparator<Candidate> ORDER =
      Comparator.comparingLong(Candidate::networkLost)
          .thenComparingLong(Candidate::rapidLost)
          .thenComparingInt(c -> c.outcome().absorbable())
          .thenComparingInt(c -> c.outcome().peak())
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

  /**
   * 每一班的前一班：同一辆车在这一班之前跑的那一班的交路（车次号 → 交路），交路里的第一班没有。
   *
   * @param table 成品表
   * @return 车次号 → 前一班的交路
   */
  static Map<String, UUID> previousRoutes(Timetable table) {
    Map<UUID, TimetableTrip> trips = new HashMap<>();
    for (TimetableTrip trip : table.trips()) {
      trips.put(trip.id(), trip);
    }
    Map<String, UUID> out = new HashMap<>();
    for (VehicleDuty duty : table.duties()) {
      List<UUID> chain = duty.tripIds();
      for (int i = 1; i < chain.size(); i++) {
        TimetableTrip previous = trips.get(chain.get(i - 1));
        TimetableTrip trip = trips.get(chain.get(i));
        if (previous != null && trip != null) {
          out.put(trip.tripCode(), previous.routeId());
        }
      }
    }
    return out;
  }

  /**
   * 原地折返端的折返加长点：被拖住的快车班次，同一辆车上一班是在它起点所在车站终到的反方向快车（原地折返接续）时，就在那条快车的终到停靠加长折返。
   *
   * <p>被拖住的各班接的若不是同一条交路，取接得最多的那条。秒数取接这条快车的各班里被拖住最多的、向上取整到 10 秒——成品表上把这个方向整体往后推这么多，
   * 被拖住的那段就错开了；调用方在此之上再加几档余量（{@link #TURNBACK_MARGINS}）。从车库出发、或接的不是快车的班次，加长折返推不动它们，既不给加长点、也不参与定秒数。
   *
   * @param measure 实测
   * @param previousRoutes 车次号 → 同一辆车上一班的交路（{@link #previousRoutes}）
   * @param profiles 交路投影
   * @param routeCodes 交路 code
   * @param fastRoutes 快车交路
   * @return 每条被拖住的快车至多一个加长点
   */
  static List<Dwell> turnbackPoints(
      Measure measure,
      Map<String, UUID> previousRoutes,
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
      Map<UUID, String> routeCodes,
      Set<UUID> fastRoutes) {
    // 被拖住的快车 → 接它的快车 → {接了几班, 这几班里被拖住最多的秒数}
    Map<UUID, Map<UUID, int[]>> feeders = new TreeMap<>();
    for (CorridorCatchUp.Caught one : measure.caught()) {
      UUID previous = previousRoutes.get(one.code());
      if (previous == null || !fastRoutes.contains(previous) || previous.equals(one.routeId())) {
        continue;
      }
      int[] fed =
          feeders
              .computeIfAbsent(one.routeId(), key -> new TreeMap<>())
              .computeIfAbsent(previous, key -> new int[2]);
      fed[0]++;
      fed[1] = Math.max(fed[1], one.seconds());
    }
    List<Dwell> out = new ArrayList<>();
    feeders.forEach(
        (routeId, byFeeder) -> {
          TimetableConflictChecker.RouteProfile caught = profiles.get(routeId);
          if (caught == null || caught.stops().isEmpty()) {
            return;
          }
          String origin = stationOf(caught.stops().get(0));
          byFeeder.entrySet().stream()
              .filter(feeder -> endsAt(profiles.get(feeder.getKey()), origin))
              .max(Comparator.comparingInt(feeder -> feeder.getValue()[0]))
              .ifPresent(
                  feeder -> {
                    UUID feederId = feeder.getKey();
                    List<TimetableStop> stops = profiles.get(feederId).stops();
                    int last = stops.size() - 1;
                    out.add(
                        new Dwell(
                            feederId,
                            routeCodes.getOrDefault(feederId, feederId.toString()),
                            last,
                            stops.get(last).nodeId().orElse("#" + last),
                            (feeder.getValue()[1] + 9) / 10 * 10,
                            true));
                  });
        });
    return List.copyOf(out);
  }

  /** 交路终到在这个车站。 */
  private static boolean endsAt(TimetableConflictChecker.RouteProfile profile, String station) {
    return profile != null
        && !profile.stops().isEmpty()
        && stationOf(profile.stops().get(profile.stops().size() - 1)).equals(station);
  }

  /** 停靠点所在车站（站台组，同一站不同股道算同一站）。 */
  private static String stationOf(TimetableStop stop) {
    return ServiceGroupClassifier.stationGroupOf(stop.nodeId().orElse(""));
  }

  /**
   * 加长点的来源：折返时间由 {@code --turnaround} 固定时，终到停站改多少折返都不变，加长折返编出来的只是原表，不给。
   *
   * @param turnaround 编表用的折返时间表
   * @param table 原表成品表（认接续用）
   * @param profiles 交路投影
   * @param routeCodes 交路 code
   * @param fastRoutes 快车交路
   * @return 实测 → 加长点
   */
  static Function<Measure, List<Dwell>> turnbackSource(
      TurnaroundTable turnaround,
      Timetable table,
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
      Map<UUID, String> routeCodes,
      Set<UUID> fastRoutes) {
    if (turnaround.fixed()) {
      return measure -> List.of();
    }
    Map<String, UUID> previous = previousRoutes(table);
    return measure -> turnbackPoints(measure, previous, profiles, routeCodes, fastRoutes);
  }

  /** 报告一行：成品表上快车被卡的情况，按快车交路分开，点名拖住它最多的慢车；表里另有快车等待时一并写出。 */
  static String describe(Measure measure, Map<UUID, String> routeCodes) {
    String held = measure.held() > 0L ? "；表里另有快车让车等待共 " + measure.held() + "s" : "";
    if (measure.seconds() == 0L) {
      return "快车被卡（成品表实测）：无" + held;
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
    return "快车被卡（成品表实测）：" + String.join("；", parts) + held;
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
   * @param improved 比原表好（{@link Candidate#betterThan}）的最好候选；没有时为空
   * @param tried 编了几个候选
   */
  record Search(Optional<Candidate> improved, int tried) {}

  /**
   * 最终答案：只从比原表好（{@link Candidate#betterThan}）的候选里取 {@link #ORDER} 最前的。
   *
   * <p>两个口径分开用。搜索里的早停按搜索口径（占用区间）：闭塞时间口径下，慢车间隔比快车所需的小几秒就是结构缺口，被卡到不了 0， 按它早停会把候选全编一遍。
   * 最终答案在编过的候选里按报告口径（闭塞时间）重新挑：编一个候选要整张表重排，量一遍只是在成品表上逐段比，几乎不花时间。
   */
  private static final class Picks {
    private final Candidate base;
    private Candidate searchBest;
    private Candidate chosen;

    Picks(Candidate base) {
      this.base = base;
    }

    void offer(Candidate candidate) {
      if (candidate.betterThan(base)
          && (searchBest == null || ORDER.compare(candidate, searchBest) < 0)) {
        searchBest = candidate;
      }
      Candidate reported = candidate.asReported();
      if (reported.betterThan(base.asReported())
          && (chosen == null || ORDER.compare(reported, chosen.asReported()) < 0)) {
        chosen = candidate;
      }
    }

    /** 按搜索口径已经选到一个快车完全不被卡的：再找只可能在次要指标上略好。 */
    boolean settled() {
      return searchBest != null && searchBest.measure().seconds() == 0L;
    }

    Search finish(int tried) {
      return new Search(Optional.ofNullable(chosen), tried);
    }
  }

  /**
   * 搜索：先试原地折返端加长折返，再整组平移，再试快车中途加停，在比原表好的候选里取 {@link #ORDER} 最前的。
   *
   * <ol>
   *   <li>原地折返端加长折返（{@link #turnbackPoints}），每个加长点按 {@link #TURNBACK_MARGINS} 由短到长各编一次，全网损失到 0
   *       就不再往长试。 刚好错开的一档往往只是把被卡改成了表里的等待，所以错开之后照样往长试，由 {@link #ORDER} 挑。
   *   <li>每个快车组按名字序扫自己的相对周期（每 {@value #STEP_SECONDS} 秒一档，不含 0），再在平移候选里最好的位置两侧各试 {@value
   *       #REFINE_SECONDS} 秒（第 1 步的候选不平移，不当细试中心）；组与组之间贪心，前面定下的不再动。扫到第一段完全错开（被卡
   *       0）的窗口、把这段扫完就停，后面的组也不再扫。
   *   <li>平移没能完全错开时，在前 {@value #DWELL_SHORTLIST} 个最好的平移与原表上给仍被拖住的快车加停。
   * </ol>
   *
   * <p>按搜索口径已经选到快车完全不被卡的候选（{@link Picks#settled}）就不再往下一步；最终答案在编过的候选里按报告口径挑。
   *
   * @param periods 快车组 → 平移的相对周期
   * @param base 原表
   * @param turnbackPoints 由实测给出原地折返端的加长点
   * @param dwellPoints 由实测给出各快车的加停点
   * @param turnbackOnly 只试加长折返（原表已放宽）
   * @param evaluator 编候选
   */
  static Search search(
      Map<String, Integer> periods,
      Candidate base,
      Function<Measure, List<Dwell>> turnbackPoints,
      Function<Measure, List<Dwell>> dwellPoints,
      boolean turnbackOnly,
      Evaluator evaluator) {
    Picks picks = new Picks(base);
    int tried = 0;
    for (Dwell point : turnbackPoints.apply(base.measure())) {
      for (int margin : TURNBACK_MARGINS) {
        tried++;
        Optional<Candidate> candidate =
            evaluator.dwell(Map.of(), point.withSeconds(point.seconds() + margin));
        if (candidate.isEmpty()) {
          continue;
        }
        picks.offer(candidate.get());
        if (candidate.get().networkLost() == 0L) {
          // 余量从小到大：全网已经没有一秒损失，再加只是多停。
          break;
        }
      }
    }
    if (turnbackOnly || picks.settled()) {
      return picks.finish(tried);
    }
    // 每个候选都带着一整张表：只留排名前几的（第二轮要用），其余算完即丢，免得几十张表一起压在堆上。
    List<Candidate> top = new ArrayList<>();
    // 平移的细试中心与逐组固定只看平移候选：加长折返的候选不平移，拿它当基准会让平移阶段既不细试也不定组。
    Candidate shiftBest = null;
    Map<String, Integer> shift = new TreeMap<>();
    for (Map.Entry<String, Integer> entry : new TreeMap<>(periods).entrySet()) {
      if (picks.settled()) {
        // 被卡 0 已到底：后面的快车组只可能在次要指标上略好，不值得每个位置再编一遍。
        break;
      }
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
          picks.offer(candidate.get());
          if (shiftBest == null || ORDER.compare(candidate.get(), shiftBest) < 0) {
            shiftBest = candidate.get();
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
      if (shiftBest != null) {
        shift.putAll(shiftBest.shift());
      }
    }
    // 平移已经完全错开时加停不会更好，不试。原表也是起点：所有平移都比原表差时，原表上加停可能才是最好的。
    if (!picks.settled()) {
      List<Candidate> starts = new ArrayList<>(top);
      starts.add(base);
      for (Candidate start : starts) {
        if (start.measure().seconds() == 0L) {
          continue;
        }
        for (Dwell point : dwellPoints.apply(start.measure())) {
          for (int seconds : DWELL_STEPS) {
            tried++;
            evaluator.dwell(start.shift(), point.withSeconds(seconds)).ifPresent(picks::offer);
          }
        }
      }
    }
    return picks.finish(tried);
  }

  /** 把候选放进按 {@link #ORDER} 排好的前 {@link #DWELL_SHORTLIST} 名里，多出来的丢掉。 */
  static void keepTop(List<Candidate> top, Candidate candidate) {
    top.add(candidate);
    top.sort(ORDER);
    while (top.size() > DWELL_SHORTLIST) {
      top.remove(top.size() - 1);
    }
  }

  /**
   * 报告一行：错峰搜索的结论；没有比原表更好的候选时 {@code chosen} 为空。
   *
   * @param base 原表
   * @param chosen 选中的候选
   * @param tried 编了几个候选
   * @param millis 用时
   * @param turnbackOnly 原表已放宽，只试了加长折返
   */
  static String describeSearch(
      Candidate base, Optional<Candidate> chosen, int tried, long millis, boolean turnbackOnly) {
    if (chosen.isEmpty()) {
      String scope =
          turnbackOnly
              ? tried == 0
                  ? "目标间隔本身排不开（已放宽），平移与中途加停要先把间隔排开才比得了；也没有可加长的原地折返端（或折返时间由 --turnaround 固定），这次没有搜"
                  : String.format(
                      Locale.ROOT,
                      "目标间隔本身排不开（已放宽），平移与中途加停要先把间隔排开才比得了；只试了原地折返端加长折返 %d 档，没有在目标间隔下排得开、比原表好的",
                      tried)
              : String.format(
                  Locale.ROOT,
                  "试了 %d 个位置（目标间隔排得开、班次不少、最多多用 %d 列车），没有快车损失更少、且不增加全网让车等待的",
                  tried,
                  MAX_EXTRA_VEHICLES);
      return String.format(
          Locale.ROOT,
          "快车错峰：%s，保持原表（被卡 %ds），用时 %ds",
          scope,
          base.measure().seconds(),
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
                    dwell.routeCode()
                        + " 在 "
                        + dwell.nodeId()
                        + (dwell.turnback() ? " 终到折返多停 " : " 多停 ")
                        + dwell.seconds()
                        + "s"));
    int basePeak = base.outcome().peak();
    int peak = best.outcome().peak();
    String vehicles = peak > basePeak ? "，高峰 " + basePeak + " → " + peak + " 列车" : "";
    return String.format(
        Locale.ROOT,
        "快车错峰：%s，快车被卡 %ds → %ds（%d → %d 班），快车让车等待 %ds → %ds，全网让车等待 %ds → %ds%s，试了 %d 个位置，用时 %ds",
        String.join("、", changes),
        base.measure().seconds(),
        best.measure().seconds(),
        base.measure().trips(),
        best.measure().trips(),
        base.measure().held(),
        best.measure().held(),
        base.outcome().waited(),
        best.outcome().waited(),
        vehicles,
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
