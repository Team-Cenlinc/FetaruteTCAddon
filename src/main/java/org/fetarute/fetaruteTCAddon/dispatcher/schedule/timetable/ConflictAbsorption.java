package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 残余冲突的分类：运行时能不能让、在哪让、让多久。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>编表此前的成功判据是"全表零冲突"，而运行时对这一类冲突的处理一律是"后车在资源前等"，而且<b>没有上限</b>。 要求全表零冲突等于把运行时每天在做的事判成不可行——实测 WS@300
 * 的 1909 处残余里，让车机制修不掉的那部分 单步等待都在 30 秒以内，纯粹是连锁问题，运行时根本不会当回事。
 *
 * <p>于是 {@code attempt} 的产物分三类：<b>让车</b>（已经写进表的延后）、<b>可吸收残余</b>（写不进表但运行时能让）、
 * <b>不可吸收残余</b>。成功判据只看第三类，搜索放宽与 {@code --strict} 也只看它。
 *
 * <h2>可吸收的五条判据</h2>
 *
 * <p>按顺序判，第一条命中即出结论，因此判决是确定的：
 *
 * <ol>
 *   <li><b>邻表</b>：一方是已发布邻表 → 不可吸收。我挪不动它，路权先到先得。
 *   <li><b>容量 1 的端点</b>：让车发生在只有一股道的站台，或它的进站单线桥链上 → 不可吸收。在这里等就是堵死岔线。
 *   <li><b>预计等待超过 {@code --max-wait}</b> → 不可吸收。与让车修复同一个预算。
 *   <li><b>让车点没有容量</b>：后车要等的地方在那一刻已经站满 → 不可吸收。
 *   <li><b>连锁过深</b>：这一等会把后续两班以上顶掉 → 不可吸收。
 * </ol>
 *
 * <p>单线对向本身是<b>可吸收</b>的：运行时按区段互斥，后车在区段外等。不可吸收的是"等的地方是容量 1 站台"那一种， 已被第 2 条覆盖。
 *
 * <p>本类不落库：build 与 publish 重检各算一遍，两边必须得到同一结果——所以它只依赖成品表、投影与图索引， 不依赖构建过程里的任何中间状态。
 */
public final class ConflictAbsorption {

  /** 连锁深度上限：更深的让车会把交路后段整体顶掉，不再是"等一下"。 */
  public static final int MAX_CHAIN_DEPTH = 2;

  private ConflictAbsorption() {}

  /** 判决。 */
  public enum Verdict {
    /** 运行时能让：后车在让车点等一会儿即可。 */
    ABSORBABLE,
    /** 一方是已发布邻表：我挪不动它。 */
    EXTERNAL,
    /** 让车点是容量 1 的端点或它的进站单线：在这里等会堵死岔线。 */
    STUB_TERMINAL,
    /** 预计等待超过单步上限。 */
    OVER_MAX_WAIT,
    /** 让车点在那一刻没有空位。 */
    NO_WAITING_CAPACITY,
    /** 连锁过深：顶掉的后续班次超过上限。 */
    CHAIN_TOO_DEEP
  }

  /**
   * 一处残余。
   *
   * @param conflict 冲突本身
   * @param mover 后车的 code（车次号或交路号）——运行时要等的是它
   * @param waitSeconds 预计等待秒数
   * @param waitingPoint 让车点（站台组、车库，或判不出来时为空）
   * @param chainDepth 因这一等而被顶后的后续班次数
   * @param verdict 判决
   */
  public record Residual(
      TimetableConflictChecker.Conflict conflict,
      String mover,
      int waitSeconds,
      Optional<String> waitingPoint,
      int chainDepth,
      Verdict verdict) {

    public Residual {
      Objects.requireNonNull(conflict, "conflict");
      Objects.requireNonNull(verdict, "verdict");
      mover = mover == null ? "" : mover;
      waitSeconds = Math.max(0, waitSeconds);
      waitingPoint = waitingPoint == null ? Optional.empty() : waitingPoint;
      chainDepth = Math.max(0, chainDepth);
    }

    public boolean absorbable() {
      return verdict == Verdict.ABSORBABLE;
    }
  }

  /**
   * 分类。
   *
   * @param report 让车修复之后剩下的冲突报告
   * @param timetable 最终表：用来找后车所在的交路与它的起点
   * @param occupancy 最终表的投影：数让车点在那一刻有几辆车在待命
   * @param index 图索引：站台组容量
   * @param separationSeconds 相邻占用裕量
   * @param maxWaitSeconds 单步让车上限
   * @return 每处冲突一条，顺序与 {@code report} 一致
   */
  public static List<Residual> classify(
      TimetableConflictChecker.Report report,
      Timetable timetable,
      TimetableOccupancyProjector.Occupancy occupancy,
      TimetableConflictChecker.GraphIndex index,
      int separationSeconds,
      int maxWaitSeconds) {
    if (report == null || report.conflicts().isEmpty()) {
      return List.of();
    }
    Context context = Context.of(timetable, occupancy, index);
    int separation = Math.max(0, separationSeconds);
    int maxWait = Math.max(0, maxWaitSeconds);
    List<Residual> out = new ArrayList<>(report.conflicts().size());
    for (TimetableConflictChecker.Conflict conflict : report.conflicts()) {
      out.add(classifyOne(conflict, context, separation, maxWait));
    }
    return List.copyOf(out);
  }

  /** 报告用：按判决计数，缺的判决不出现。 */
  public static Map<Verdict, Integer> countByVerdict(List<Residual> residuals) {
    Map<Verdict, Integer> out = new EnumMap<>(Verdict.class);
    if (residuals != null) {
      for (Residual residual : residuals) {
        if (residual != null) {
          out.merge(residual.verdict(), 1, Integer::sum);
        }
      }
    }
    return out;
  }

  /** 只要可吸收的那些。 */
  public static List<Residual> absorbable(List<Residual> residuals) {
    return filter(residuals, true);
  }

  /** 只要不可吸收的那些——成功判据、搜索放宽与 {@code --strict} 都只看它。 */
  public static List<Residual> unabsorbable(List<Residual> residuals) {
    return filter(residuals, false);
  }

  private static List<Residual> filter(List<Residual> residuals, boolean absorbable) {
    if (residuals == null || residuals.isEmpty()) {
      return List.of();
    }
    List<Residual> out = new ArrayList<>();
    for (Residual residual : residuals) {
      if (residual != null && residual.absorbable() == absorbable) {
        out.add(residual);
      }
    }
    return List.copyOf(out);
  }

  // ------------------------------------------------------------------ 判据

  private static Residual classifyOne(
      TimetableConflictChecker.Conflict conflict, Context context, int separation, int maxWait) {
    // 1. 邻表：谁先发布谁占路权，我挪不动它。
    if (conflict.external()) {
      return new Residual(conflict, moverOf(conflict), 0, Optional.empty(), 0, Verdict.EXTERNAL);
    }
    // 后车与预计等待：与 ResourceRepair.moveFor 同一口径，内部冲突里后车恒为 second。
    String mover = moverOf(conflict);
    int wait = conflict.firstTo() + separation - conflict.secondFrom();
    wait = Math.max(0, wait);
    Optional<String> waitingPoint = context.waitingPointOf(mover);

    // 2. 容量 1 的端点：在这里等就是堵死岔线。
    if (context.isStubResource(conflict.resource())
        || waitingPoint.map(context::isStubGroup).orElse(false)) {
      return new Residual(conflict, mover, wait, waitingPoint, 0, Verdict.STUB_TERMINAL);
    }
    // 3. 预算：与让车修复同一个上限。
    if (wait > maxWait) {
      return new Residual(conflict, mover, wait, waitingPoint, 0, Verdict.OVER_MAX_WAIT);
    }
    // 4. 让车点有没有空位。
    if (waitingPoint.isPresent() && !context.hasRoom(waitingPoint.get(), conflict.secondFrom())) {
      return new Residual(conflict, mover, wait, waitingPoint, 0, Verdict.NO_WAITING_CAPACITY);
    }
    // 5. 连锁：这一等会顶掉后面几班。
    int depth = context.chainDepthAfter(mover, wait);
    if (depth > MAX_CHAIN_DEPTH) {
      return new Residual(conflict, mover, wait, waitingPoint, depth, Verdict.CHAIN_TOO_DEEP);
    }
    return new Residual(conflict, mover, wait, waitingPoint, depth, Verdict.ABSORBABLE);
  }

  /** 内部冲突的后车恒为 {@code second}：{@link ResourceRepair#moveFor} 只在对方是邻表时才改挪自己。 */
  private static String moverOf(TimetableConflictChecker.Conflict conflict) {
    return conflict.secondOwner().isEmpty() ? conflict.second() : conflict.first();
  }

  // ------------------------------------------------------------------ 上下文

  /** 分类需要的只读索引：全部从成品表与投影算出来，不含构建过程的中间状态。 */
  private record Context(
      Map<String, String> waitingPointByCode,
      Map<String, List<int[]>> staysByGroup,
      Map<String, Integer> capacityByGroup,
      Set<String> stubGroups,
      Map<String, List<Integer>> chainDeparturesAfter) {

    static Context of(
        Timetable timetable,
        TimetableOccupancyProjector.Occupancy occupancy,
        TimetableConflictChecker.GraphIndex index) {
      Map<String, Integer> capacity = index == null ? Map.of() : index.platformCapacity();
      Set<String> stubs = new HashSet<>();
      capacity.forEach(
          (group, tracks) -> {
            if (tracks != null && tracks <= 1) {
              stubs.add(group);
            }
          });

      Map<String, String> waitingPoint = new HashMap<>();
      Map<String, List<Integer>> chain = new HashMap<>();
      if (timetable != null) {
        Map<UUID, TimetableTrip> tripById = new HashMap<>();
        for (TimetableTrip trip : timetable.trips()) {
          tripById.put(trip.id(), trip);
          timetable
              .routePlan(trip.routeId())
              .ifPresent(
                  plan ->
                      waitingPoint.put(
                          trip.tripCode(), TimetableConflictChecker.groupOf(plan.originNodeId())));
        }
        for (VehicleDuty duty : timetable.duties()) {
          // 交路里这一班之后还有几班：连锁深度按它数，与让车修复沿链传播的口径一致。
          List<Integer> departures = new ArrayList<>();
          for (UUID tripId : duty.tripIds()) {
            TimetableTrip trip = tripById.get(tripId);
            departures.add(trip == null ? Integer.MIN_VALUE : trip.departureSecondOfDay());
          }
          for (int i = 0; i < duty.tripIds().size(); i++) {
            TimetableTrip trip = tripById.get(duty.tripIds().get(i));
            if (trip != null) {
              chain.put(trip.tripCode(), List.copyOf(departures.subList(i, departures.size())));
            }
          }
          // 待命与走行的 code 是交路号：让车点是车库，容量视为无限（空串表示不限）。
          waitingPoint.put(duty.dutyCode(), "");
        }
      }

      Map<String, List<int[]>> stays = new HashMap<>();
      if (occupancy != null) {
        for (TimetableConflictChecker.Stay stay : occupancy.stays()) {
          if (stay.platform() != null && !stay.platform().group().isBlank()) {
            stays
                .computeIfAbsent(stay.platform().group(), key -> new ArrayList<>())
                .add(new int[] {stay.from(), stay.to()});
          }
        }
      }
      return new Context(waitingPoint, stays, capacity, stubs, chain);
    }

    /** 后车要在哪里等：班次等在它的起点站台组，待命与走行等在车库（空串）。判不出来时为空。 */
    Optional<String> waitingPointOf(String code) {
      String group = waitingPointByCode.get(code);
      if (group == null) {
        return Optional.empty();
      }
      return group.isBlank() ? Optional.empty() : Optional.of(group);
    }

    boolean isStubGroup(String group) {
      return stubGroups.contains(group);
    }

    /** 资源本身就在容量 1 的端点上：站台组、具体股道，或名字里带该端点节点的单线桥链。 */
    boolean isStubResource(String resource) {
      if (resource == null) {
        return false;
      }
      if (resource.startsWith("platform-group:")) {
        return isStubGroup(resource.substring("platform-group:".length()));
      }
      if (resource.startsWith("platform:")) {
        return isStubGroup(
            TimetableConflictChecker.groupOf(resource.substring("platform:".length())));
      }
      if (resource.startsWith("single:")) {
        for (String stub : stubGroups) {
          if (resource.contains(stub + ":")) {
            return true;
          }
        }
      }
      return false;
    }

    /** 让车点在这一刻还有没有空位：容量减去那一刻已经在待命的车。 */
    boolean hasRoom(String group, int atSecond) {
      int capacity = capacityByGroup.getOrDefault(group, 1);
      int occupied = 0;
      for (int[] window : staysByGroup.getOrDefault(group, List.of())) {
        if (window[0] <= atSecond && atSecond < window[1]) {
          occupied++;
        }
      }
      return capacity - occupied >= 1;
    }

    /** 后车整趟延后 {@code wait} 秒之后，同一交路里被顶到晚于名义时刻的后续班次数。 */
    int chainDepthAfter(String code, int wait) {
      List<Integer> departures = chainDeparturesAfter.get(code);
      if (departures == null || departures.size() <= 1 || wait <= 0) {
        return 0;
      }
      int depth = 0;
      int pushedTo = departures.get(0) + wait;
      for (int i = 1; i < departures.size(); i++) {
        int nominal = departures.get(i);
        if (nominal == Integer.MIN_VALUE || nominal >= pushedTo) {
          break;
        }
        depth++;
        pushedTo = nominal + wait;
      }
      return depth;
    }
  }
}
