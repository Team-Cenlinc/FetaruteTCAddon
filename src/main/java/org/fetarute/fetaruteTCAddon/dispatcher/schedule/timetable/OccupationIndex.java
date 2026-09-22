package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * 冲突检查的资源索引形态：占用按资源键分桶，可以只换一辆车的占用、只扫给定的资源。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>{@link ResourceRepair} 每施加一处让车就要知道"现在还剩哪些冲突"，此前的做法是把整张表重新投影一遍再全扫。 实测 WS@300 有 902 班，一次 attempt
 * 要重来几千次，光这一项就是四十多秒。可是一处让车只改一条交路的时刻， 别的车在别的资源上的占用一个字都没变——重算它们纯属浪费。
 *
 * <p>于是把投影的产物留下来：{@link #replaceVehicle} 只换掉一辆车的占用并交回这次改动碰到的资源键， {@link #scan} 只扫这些资源。复杂度从 O(全表) 降到
 * O(改动的那几条运行 × 它们的资源)。
 *
 * <h2>只换真正变了的那几条</h2>
 *
 * <p>索引记着上一次投影进去的每辆车的运行与待命。换车时按记录做差集：时刻没动过的班次（记录逐字段相同）
 * <b>连碰都不碰</b>，只撤掉消失的、加上新增的。一条交路四班车里往往只有后半截跟着让车后移， 整车重投影会把前半截连同它们沿途上百个资源一起白白重扫一遍。
 *
 * <h2>与 {@code check} 的关系</h2>
 *
 * <p>投影与扫描都直接调 {@link TimetableConflictChecker} 的那两半，本类不复制任何比较逻辑：{@link #scanAll} 必须与 {@link
 * TimetableConflictChecker#check} 逐字段相等，{@code OccupationIndexTest.scanAllEqualsCheck} 钉住这一条。
 *
 * <p>本类<b>不是</b>线程安全的，也不打算是：它只在一次 build 的修复循环里活着。
 */
public final class OccupationIndex {

  private final TimetableConflictChecker.GraphIndex index;
  private final Map<UUID, TimetableConflictChecker.RouteProfile> profiles;
  private final Function<String, String> vehicleOf;

  /**
   * 资源键 → 占用桶。
   *
   * <p>用 {@link LinkedHashMap} 而不是有序表：资源键是七十来字符的长串，有序表每查一个桶要做十来次全串比较，
   * 而一次修复要查上百万次。遍历序仍是确定的（投影序），报告的序由 {@code CONFLICT_ORDER} 自己保证， 不靠资源键的字典序。
   */
  private final Map<String, TimetableConflictChecker.Resource> resources = new LinkedHashMap<>();

  /** 车辆身份 → 上一次投影进去的运行与待命。换车时按它做差集。 */
  private final Map<String, Projection> projected = new HashMap<>();

  /** 一辆车当前在索引里的那份投影输入。 */
  private record Projection(
      List<TimetableConflictChecker.Movement> movements,
      List<TimetableConflictChecker.Stay> stays) {

    static final Projection EMPTY = new Projection(List.of(), List.of());
  }

  private OccupationIndex(
      TimetableConflictChecker.GraphIndex index,
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
      Function<String, String> vehicleOf) {
    this.index = index == null ? TimetableConflictChecker.GraphIndex.of(null) : index;
    this.profiles = profiles == null ? Map.of() : Map.copyOf(profiles);
    this.vehicleOf = vehicleOf == null ? Function.identity() : vehicleOf;
  }

  /**
   * 一次投影全部占用。
   *
   * @param index 图索引
   * @param profiles 各 route 的投影
   * @param movements 全部运行（我的 + 邻表的）
   * @param stays 全部站台待命
   * @param vehicleOf code → 车辆身份；语义同 {@link TimetableConflictChecker#check}
   */
  public static OccupationIndex of(
      TimetableConflictChecker.GraphIndex index,
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
      List<TimetableConflictChecker.Movement> movements,
      List<TimetableConflictChecker.Stay> stays,
      Function<String, String> vehicleOf) {
    OccupationIndex out = new OccupationIndex(index, profiles, vehicleOf);
    TimetableConflictChecker.projectInto(
        out.resources, out.index, out.profiles, movements, stays, out.vehicleOf);
    out.remember(movements, stays);
    return out;
  }

  /**
   * 换掉一辆车的占用。
   *
   * @param vehicle 车辆身份（{@code |duty 号}）；用 {@link TimetableConflictChecker#vehicleKey} 拼
   * @param movements 这辆车新的运行
   * @param stays 这辆车新的待命
   * @return 这次改动碰到的资源键——撤掉的与新加的两边都算进去，只看新的会漏掉"车挪走之后原来那处不再冲突"； 没被碰到的资源上的占用一条没动，它们的冲突也就不会变
   */
  public Set<String> replaceVehicle(
      String vehicle,
      List<TimetableConflictChecker.Movement> movements,
      List<TimetableConflictChecker.Stay> stays) {
    Objects.requireNonNull(vehicle, "vehicle");
    List<TimetableConflictChecker.Movement> next =
        movements == null ? List.of() : List.copyOf(movements);
    List<TimetableConflictChecker.Stay> nextStays = stays == null ? List.of() : List.copyOf(stays);
    // 交进来的每一条都必须真的属于 vehicle。不属于的话，它在 of() 里是按自己的身份归档的，这里却按
    // vehicle 归档：下次换车做差集时两边对不上，撤不掉的占用会永远留在桶里变成幽灵冲突——而且不抛
    // 异常、不报错，只是让修复循环拿着一张假账做取舍。宁可当场炸掉。
    for (TimetableConflictChecker.Movement movement : next) {
      requireOwnedBy(vehicle, movement.code(), movement.owner());
    }
    for (TimetableConflictChecker.Stay stay : nextStays) {
      requireOwnedBy(vehicle, stay.code(), stay.owner());
    }
    Projection last = projected.getOrDefault(vehicle, Projection.EMPTY);
    Set<String> touched =
        new LinkedHashSet<>(
            remove(subtract(last.movements(), next), subtract(last.stays(), nextStays)));
    touched.addAll(
        TimetableConflictChecker.projectInto(
            resources,
            index,
            profiles,
            subtract(next, last.movements()),
            subtract(nextStays, last.stays()),
            vehicleOf));
    projected.put(vehicle, new Projection(next, nextStays));
    return touched;
  }

  /**
   * 只扫这些资源。
   *
   * @param resourceKeys 资源键；表里没有的忽略
   * @param separationSeconds 相邻占用之间的最小间隔
   */
  public TimetableConflictChecker.Report scan(Set<String> resourceKeys, int separationSeconds) {
    if (resourceKeys == null || resourceKeys.isEmpty()) {
      return TimetableConflictChecker.Report.none();
    }
    List<TimetableConflictChecker.Resource> picked = new ArrayList<>(resourceKeys.size());
    for (String key : resourceKeys) {
      TimetableConflictChecker.Resource resource = resources.get(key);
      if (resource != null) {
        picked.add(resource);
      }
    }
    return TimetableConflictChecker.scanResources(picked, separationSeconds);
  }

  /** 全扫：与 {@link TimetableConflictChecker#check} 等价。 */
  public TimetableConflictChecker.Report scanAll(int separationSeconds) {
    return TimetableConflictChecker.scanResources(resources.values(), separationSeconds);
  }

  /** 撤掉这些运行与待命的占用：它们原样再投影一遍就是当初加进去的那些，按值撤掉即可。 */
  private Set<String> remove(
      List<TimetableConflictChecker.Movement> movements,
      List<TimetableConflictChecker.Stay> stays) {
    if (movements.isEmpty() && stays.isEmpty()) {
      return Set.of();
    }
    Map<String, TimetableConflictChecker.Resource> scratch = new LinkedHashMap<>();
    Set<String> keys =
        TimetableConflictChecker.projectInto(scratch, index, profiles, movements, stays, vehicleOf);
    for (String key : keys) {
      TimetableConflictChecker.Resource target = resources.get(key);
      TimetableConflictChecker.Resource produced = scratch.get(key);
      if (target != null && produced != null) {
        target.removeAll(produced.occupations());
      }
    }
    return keys;
  }

  /** 把投影输入按车记下来，供下次做差集。 */
  private void remember(
      List<TimetableConflictChecker.Movement> movements,
      List<TimetableConflictChecker.Stay> stays) {
    Map<String, List<TimetableConflictChecker.Movement>> byVehicle = new LinkedHashMap<>();
    Map<String, List<TimetableConflictChecker.Stay>> staysByVehicle = new LinkedHashMap<>();
    for (TimetableConflictChecker.Movement movement :
        movements == null ? List.<TimetableConflictChecker.Movement>of() : movements) {
      byVehicle
          .computeIfAbsent(keyOf(movement.code(), movement.owner()), key -> new ArrayList<>())
          .add(movement);
    }
    for (TimetableConflictChecker.Stay stay :
        stays == null ? List.<TimetableConflictChecker.Stay>of() : stays) {
      staysByVehicle
          .computeIfAbsent(keyOf(stay.code(), stay.owner()), key -> new ArrayList<>())
          .add(stay);
    }
    projected.clear();
    Set<String> vehicles = new LinkedHashSet<>(byVehicle.keySet());
    vehicles.addAll(staysByVehicle.keySet());
    for (String vehicle : vehicles) {
      projected.put(
          vehicle,
          new Projection(
              List.copyOf(byVehicle.getOrDefault(vehicle, List.of())),
              List.copyOf(staysByVehicle.getOrDefault(vehicle, List.of()))));
    }
  }

  private void requireOwnedBy(String vehicle, String code, Optional<String> owner) {
    String actual = keyOf(code, owner);
    if (!vehicle.equals(actual)) {
      throw new IllegalArgumentException("占用 " + code + " 的车辆身份是 " + actual + "，不是 " + vehicle);
    }
  }

  private String keyOf(String code, Optional<String> owner) {
    return TimetableConflictChecker.vehicleKey(code, owner, vehicleOf);
  }

  /** 多重集差集 {@code a − b}：{@code b} 里有几份就从 {@code a} 里抵掉几份。 */
  private static <T> List<T> subtract(List<T> a, List<T> b) {
    if (a.isEmpty()) {
      return List.of();
    }
    if (b.isEmpty()) {
      return a;
    }
    Map<T, Integer> counts = new HashMap<>();
    for (T item : b) {
      counts.merge(item, 1, Integer::sum);
    }
    List<T> out = new ArrayList<>();
    for (T item : a) {
      Integer left = counts.get(item);
      if (left == null || left == 0) {
        out.add(item);
      } else {
        counts.put(item, left - 1);
      }
    }
    return out;
  }
}
