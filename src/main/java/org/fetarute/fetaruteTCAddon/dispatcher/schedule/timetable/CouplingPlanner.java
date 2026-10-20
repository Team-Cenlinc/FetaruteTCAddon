package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.consist.CompositeSpawnPattern;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteCouplingDirectives;

/**
 * 编表认加解编：从交路备注（{@code DECP} / {@code COPL}）与出车编组推出哪一条主干在哪一站切、切下与留下的各是什么编组、哪些是支线交路， 再按每一班主干班次派生支线班次，
 * 交给派车（{@link VehicleDutyPlanner.Couplings}）。纯计算。
 *
 * <ul>
 *   <li>支线交路（被 {@code GOTO} 指向的、被 {@code FROM} 指向的）不上网格、不分份额：每一班主干派生一班，与主干同频。
 *   <li>解编：GOTO 班次在主干离开解编站的那一刻从解编站发车，由解下的车跑。
 *   <li>联挂：回程支线班次按表比主干早 {@code couple-seconds} 到联挂站，在股道上等主干进站挂上。
 *   <li>编组键：写解编的主干出车是完整的组合写法（{@code A+B}），切出留下与切下两段；写联挂的主干按完整写法，出发时少了回程支线带来的那一段。
 * </ul>
 *
 * <p>约束（不满足时编表报错，见 {@link Analysis#errors()}）：一条交路至多一条 DECP 或一条
 * COPL，不同时写；写加解编的主干要上发车网格（运营交路或带客的出库交路，回库交路只是收尾走行段）；写了加解编的主干要读得到组合写法的出车编组，
 * 切点要落在组合写法的分界上（指定那头切不开、另一头正好是同样节数时改切另一头，与运行时同一条回退）；绑了编组方案的交路不支持加解编。
 */
public final class CouplingPlanner {

  private CouplingPlanner() {}

  /**
   * 一条主干交路的解编。
   *
   * @param stationNodeId 解编站节点
   * @param departureOffset 自主干发车起到离开解编站的秒数（含解编加时）
   * @param kept 留下的编组
   * @param detached 切下的编组
   * @param action 切下的去哪
   * @param target GOTO 的支线交路
   */
  public record TrunkSplit(
      String stationNodeId,
      int departureOffset,
      String kept,
      String detached,
      RouteCouplingDirectives.Action action,
      Optional<UUID> target) {}

  /**
   * 一条主干交路的联挂。
   *
   * @param stationNodeId 联挂站节点
   * @param arrivalOffset 自主干发车起到进联挂站的秒数
   * @param departureOffset 自主干发车起到离开联挂站的秒数（含联挂加时）
   * @param merged 联挂之后的编组（主干的完整写法）
   * @param feeder 挂上来的回程支线交路
   */
  public record TrunkJoin(
      String stationNodeId, int arrivalOffset, int departureOffset, String merged, UUID feeder) {}

  /**
   * 推出的加解编结构。
   *
   * @param branchRoutes 支线交路：不上网格、只派生
   * @param splits 主干交路 → 解编
   * @param joins 主干交路 → 联挂
   * @param consistByRoute 交路 → 出发时的编组键（派车用；只给加解编牵涉到的车池里读得到编组的交路）
   * @param partial 切出来的短编组
   * @param errors 编表报错
   */
  public record Analysis(
      Set<UUID> branchRoutes,
      Map<UUID, TrunkSplit> splits,
      Map<UUID, TrunkJoin> joins,
      Map<UUID, String> consistByRoute,
      Set<String> partial,
      List<String> errors) {

    /** 没有加解编。 */
    public static final Analysis NONE =
        new Analysis(Set.of(), Map.of(), Map.of(), Map.of(), Set.of(), List.of());

    public Analysis {
      branchRoutes = Set.copyOf(branchRoutes);
      splits = Map.copyOf(splits);
      joins = Map.copyOf(joins);
      consistByRoute = Map.copyOf(consistByRoute);
      partial = Set.copyOf(partial);
      errors = List.copyOf(errors);
    }

    /** 有没有加解编。 */
    public boolean isEmpty() {
      return splits.isEmpty() && joins.isEmpty();
    }

    /** 这条交路派车用的车型选项；没有时为空（照原来的车型选项）。 */
    Optional<List<VehicleDutyPlanner.ConsistOption>> consistOptions(
        UUID routeId, int durationSeconds) {
      String key = consistByRoute.get(routeId);
      return key == null
          ? Optional.empty()
          : Optional.of(List.of(new VehicleDutyPlanner.ConsistOption(key, 1, durationSeconds)));
    }

    /** 全部编组键（派车查走行段时当作不区分车型）。 */
    Set<String> consists() {
      Set<String> out = new LinkedHashSet<>(consistByRoute.values());
      splits.values().forEach(split -> out.addAll(List.of(split.kept(), split.detached())));
      joins.values().forEach(join -> out.add(join.merged()));
      return out;
    }
  }

  // ---------------------------------------------------------------- 分析

  /**
   * 推出加解编结构。
   *
   * @param routes 参与编表的交路（含走行线路）
   * @param plans 交路 → 时分（基础 route 的）
   * @param inputs 出车编组与组合写法各段节数
   * @param consistPlanRoutes 绑了编组方案的交路
   * @param gridRoutes 上发车网格的交路（运营交路、带客的出库交路）：拆挂只从它们的班次派生
   * @param poolOf 交路 → 车池
   * @param coupleSeconds 联挂加时（回程支线提前到站的秒数）
   */
  public static Analysis analyze(
      List<TimetableBuilder.RouteInput> routes,
      Map<UUID, TimetableRoutePlan> plans,
      CouplingInputs inputs,
      Set<UUID> consistPlanRoutes,
      Set<UUID> gridRoutes,
      Function<UUID, String> poolOf,
      int coupleSeconds) {
    Map<String, TimetableBuilder.RouteInput> byCode = new LinkedHashMap<>();
    for (TimetableBuilder.RouteInput route : routes) {
      byCode.putIfAbsent(route.routeCode().toLowerCase(Locale.ROOT), route);
    }
    List<String> errors = new ArrayList<>();
    Set<UUID> branches = new LinkedHashSet<>();
    Map<UUID, TrunkSplit> splits = new LinkedHashMap<>();
    Map<UUID, TrunkJoin> joins = new LinkedHashMap<>();
    Map<UUID, String> consistByRoute = new LinkedHashMap<>();
    Set<String> partial = new LinkedHashSet<>();
    List<PendingJoin> pendingJoins = new ArrayList<>();
    for (TimetableBuilder.RouteInput route : routes) {
      List<Integer> decoupleAt = indexes(route.stops(), true);
      List<Integer> coupleAt = indexes(route.stops(), false);
      if (decoupleAt.isEmpty() && coupleAt.isEmpty()) {
        continue;
      }
      String code = route.routeCode();
      if (decoupleAt.size() + coupleAt.size() > 1) {
        errors.add(code + "：一条交路至多写一条 DECP 或一条 COPL");
        continue;
      }
      if (consistPlanRoutes.contains(route.routeId())) {
        errors.add(code + "：绑了编组方案的交路不支持加解编");
        continue;
      }
      if (!gridRoutes.contains(route.routeId())) {
        // 回库交路、不载客的出库交路是派车时在交路两头补上的走行段，没有自己的班次，拆挂无从派生
        errors.add(code + "：加解编要写在运营交路（或带客的出库交路）上，回库与不载客的出库交路排不出拆挂；请把它改成运营交路");
        continue;
      }
      TimetableRoutePlan plan = plans.get(route.routeId());
      if (plan == null) {
        continue;
      }
      Optional<String> pattern = inputs.pattern(route.routeId());
      if (pattern.isEmpty()) {
        errors.add(code + "：写了加解编，但读不到出车编组（route 的 spawn_train_pattern 或首站 CRET 的车库牌子）");
        continue;
      }
      List<String> parts = CompositeSpawnPattern.parts(pattern.get());
      Optional<List<Integer>> sizes = inputs.sizes(pattern.get());
      if (parts.size() < 2) {
        errors.add(code + "：出车编组 " + pattern.get() + " 只有一段，切不开；请写成组合写法（如 A+B）");
        continue;
      }
      if (sizes.isEmpty() || sizes.get().size() != parts.size()) {
        errors.add(code + "：出车编组 " + pattern.get() + " 解析不出各段节数");
        continue;
      }
      if (!decoupleAt.isEmpty()) {
        int index = decoupleAt.get(0);
        RouteStop stop = route.stops().get(index);
        RouteCouplingDirectives.Decouple decouple =
            RouteCouplingDirectives.decouple(stop).orElseThrow();
        Optional<Cut> cut = cut(parts, sizes.get(), decouple.end(), decouple.cars());
        if (cut.isEmpty()) {
          errors.add(
              code
                  + "：DECP "
                  + decouple.end()
                  + ":"
                  + decouple.cars()
                  + " 在编组 "
                  + pattern.get()
                  + " 的分界上切不开");
          continue;
        }
        Optional<UUID> target = Optional.empty();
        if (decouple.action() == RouteCouplingDirectives.Action.GOTO) {
          TimetableBuilder.RouteInput goTo =
              byCode.get(decouple.target().orElse("").toLowerCase(Locale.ROOT));
          if (goTo == null || !plans.containsKey(goTo.routeId())) {
            errors.add(code + "：DECP 指向的交路 " + decouple.target().orElse("?") + " 不在这次编表里");
            continue;
          }
          target = Optional.of(goTo.routeId());
        }
        Optional<TimetableStop> timing = stopAt(plan, stop.sequence());
        if (timing.isEmpty()) {
          errors.add(code + "：DECP 所在的第 " + stop.sequence() + " 站在时分里找不到");
          continue;
        }
        target.ifPresent(
            id -> {
              branches.add(id);
              consistByRoute.put(id, cut.get().detached());
            });
        splits.put(
            route.routeId(),
            new TrunkSplit(
                timing.get().nodeId().orElse(plan.originNodeId()),
                timing.get().departureOffsetSeconds(),
                cut.get().kept(),
                cut.get().detached(),
                decouple.action(),
                target));
        consistByRoute.put(route.routeId(), pattern.get());
        partial.add(cut.get().kept());
        partial.add(cut.get().detached());
        continue;
      }
      int index = coupleAt.get(0);
      RouteStop stop = route.stops().get(index);
      RouteCouplingDirectives.Couple couple = RouteCouplingDirectives.couple(stop).orElseThrow();
      TimetableBuilder.RouteInput from = byCode.get(couple.from().toLowerCase(Locale.ROOT));
      if (from == null || !plans.containsKey(from.routeId())) {
        errors.add(code + "：COPL 指向的交路 " + couple.from() + " 不在这次编表里");
        continue;
      }
      Optional<TimetableStop> timing = stopAt(plan, stop.sequence());
      if (timing.isEmpty()) {
        errors.add(code + "：COPL 所在的第 " + stop.sequence() + " 站在时分里找不到");
        continue;
      }
      branches.add(from.routeId());
      pendingJoins.add(
          new PendingJoin(route, from, pattern.get(), parts, timing.get(), plan.originNodeId()));
    }
    // 联挂要知道回程支线带来的是哪一段：回程支线自己读得到编组就用它的，否则取同组解编切下的那一段
    for (PendingJoin pending : pendingJoins) {
      Optional<String> branch =
          inputs
              .pattern(pending.from().routeId())
              .or(() -> detachedOfSameGroup(pending.trunk(), routes, splits));
      Optional<String> kept =
          branch.flatMap(key -> without(pending.parts(), CompositeSpawnPattern.parts(key)));
      if (branch.isEmpty() || kept.isEmpty()) {
        errors.add(
            pending.trunk().routeCode()
                + "：COPL 推不出联挂前主干的编组（回程支线 "
                + pending.from().routeCode()
                + " 的编组要是主干编组 "
                + pending.pattern()
                + " 的首段或末段）");
        continue;
      }
      joins.put(
          pending.trunk().routeId(),
          new TrunkJoin(
              pending.timing().nodeId().orElse(pending.origin()),
              pending.timing().arrivalOffsetSeconds(),
              pending.timing().departureOffsetSeconds(),
              pending.pattern(),
              pending.from().routeId()));
      consistByRoute.put(pending.trunk().routeId(), kept.get());
      consistByRoute.put(pending.from().routeId(), branch.get());
      partial.add(kept.get());
      partial.add(branch.get());
    }
    // 牵涉到的车池里，其余读得到编组的交路按自己的编组派车：完整编组的车才接它们，短编组不接
    if (!splits.isEmpty() || !joins.isEmpty()) {
      Set<String> pools = new LinkedHashSet<>();
      splits.keySet().forEach(id -> pools.add(poolOf.apply(id)));
      joins.keySet().forEach(id -> pools.add(poolOf.apply(id)));
      for (TimetableBuilder.RouteInput route : routes) {
        if (consistByRoute.containsKey(route.routeId())
            || consistPlanRoutes.contains(route.routeId())
            || !pools.contains(poolOf.apply(route.routeId()))) {
          continue;
        }
        inputs.pattern(route.routeId()).ifPresent(key -> consistByRoute.put(route.routeId(), key));
      }
    }
    return new Analysis(branches, splits, joins, consistByRoute, partial, errors);
  }

  private record PendingJoin(
      TimetableBuilder.RouteInput trunk,
      TimetableBuilder.RouteInput from,
      String pattern,
      List<String> parts,
      TimetableStop timing,
      String origin) {}

  /** 同一交路组里解编切下的那一段（回程支线读不到自己的编组时用）。 */
  private static Optional<String> detachedOfSameGroup(
      TimetableBuilder.RouteInput trunk,
      List<TimetableBuilder.RouteInput> routes,
      Map<UUID, TrunkSplit> splits) {
    String group = trunk.spawnGroup().orElse("");
    for (TimetableBuilder.RouteInput route : routes) {
      TrunkSplit split = splits.get(route.routeId());
      if (split != null && route.spawnGroup().orElse("").equals(group)) {
        return Optional.of(split.detached());
      }
    }
    return Optional.empty();
  }

  /** 写了解编（{@code decouple}）或联挂的停靠点下标。 */
  private static List<Integer> indexes(List<RouteStop> stops, boolean decouple) {
    List<Integer> out = new ArrayList<>();
    for (int i = 0; i < stops.size(); i++) {
      RouteStop stop = stops.get(i);
      if (decouple
          ? RouteCouplingDirectives.decouple(stop).isPresent()
          : RouteCouplingDirectives.couple(stop).isPresent()) {
        out.add(i);
      }
    }
    return out;
  }

  private static Optional<TimetableStop> stopAt(TimetableRoutePlan plan, int sequence) {
    return plan.stops().stream().filter(stop -> stop.stopSequence() == sequence).findFirst();
  }

  /**
   * 切一刀的结果。
   *
   * @param kept 留下的编组
   * @param detached 切下的编组
   * @param end 实际切的那一头（可能因回退换了一头）
   */
  record Cut(String kept, String detached, RouteCouplingDirectives.End end) {}

  /**
   * 在组合写法的分界上切下 {@code cars} 节：指定那一头的若干整段节数和正好等于 {@code cars}；切不开时若另一头正好是同样节数就改切另一头。纯计算。
   *
   * @param parts 组合写法各段（车头到车尾）
   * @param sizes 各段节数
   */
  static Optional<Cut> cut(
      List<String> parts, List<Integer> sizes, RouteCouplingDirectives.End end, int cars) {
    Optional<Cut> direct = cutAt(parts, sizes, end, cars);
    if (direct.isPresent()) {
      return direct;
    }
    return cutAt(
        parts,
        sizes,
        end == RouteCouplingDirectives.End.FRONT
            ? RouteCouplingDirectives.End.REAR
            : RouteCouplingDirectives.End.FRONT,
        cars);
  }

  private static Optional<Cut> cutAt(
      List<String> parts, List<Integer> sizes, RouteCouplingDirectives.End end, int cars) {
    int sum = 0;
    for (int count = 1; count < parts.size(); count++) {
      int index = end == RouteCouplingDirectives.End.FRONT ? count - 1 : parts.size() - count;
      sum += sizes.get(index);
      if (sum == cars) {
        List<String> detached =
            end == RouteCouplingDirectives.End.FRONT
                ? parts.subList(0, count)
                : parts.subList(parts.size() - count, parts.size());
        List<String> kept =
            end == RouteCouplingDirectives.End.FRONT
                ? parts.subList(count, parts.size())
                : parts.subList(0, parts.size() - count);
        return Optional.of(new Cut(join(kept), join(detached), end));
      }
      if (sum > cars) {
        return Optional.empty();
      }
    }
    return Optional.empty();
  }

  /**
   * 写了联挂（COPL）的主干联挂之前的编组：完整写法去掉回程支线带来的那一段。与编表推出的同一条规则：回程支线读得到自己的编组就用它，否则取同组解编切下的那一段。
   * 不按表运行时出车用（按表时由编表推好、写在票上）。纯计算。
   *
   * @param trunk 主干交路
   * @param trunkPattern 主干的出车编组（联挂之后的完整写法）
   * @param lineRoutes 同一条线上的交路
   * @param patternOf 交路码 → 出车编组写法
   * @param sizesOf 编组写法 → 各段节数（车头到车尾）
   * @return 联挂之前的编组；主干没写联挂或推不出时为空
   */
  public static Optional<String> beforeJoin(
      RouteCouplingDirectives.RouteRef trunk,
      String trunkPattern,
      List<RouteCouplingDirectives.RouteRef> lineRoutes,
      Function<String, Optional<String>> patternOf,
      Function<String, Optional<List<Integer>>> sizesOf) {
    Optional<RouteCouplingDirectives.Couple> couple =
        trunk.stops().stream()
            .map(RouteCouplingDirectives::couple)
            .flatMap(Optional::stream)
            .findFirst();
    if (couple.isEmpty() || trunkPattern == null) {
      return Optional.empty();
    }
    Optional<String> branch = patternOf.apply(couple.get().from());
    for (RouteCouplingDirectives.RouteRef route : lineRoutes) {
      if (branch.isPresent()) {
        break;
      }
      if (!route.group().equals(trunk.group())) {
        continue;
      }
      Optional<RouteCouplingDirectives.Decouple> decouple =
          route.stops().stream()
              .map(RouteCouplingDirectives::decouple)
              .flatMap(Optional::stream)
              .findFirst();
      Optional<String> pattern = decouple.flatMap(found -> patternOf.apply(route.code()));
      if (pattern.isEmpty()) {
        continue;
      }
      List<String> parts = CompositeSpawnPattern.parts(pattern.get());
      branch =
          sizesOf
              .apply(pattern.get())
              .filter(sizes -> sizes.size() == parts.size())
              .flatMap(sizes -> cut(parts, sizes, decouple.get().end(), decouple.get().cars()))
              .map(Cut::detached);
    }
    return branch.flatMap(
        key ->
            without(CompositeSpawnPattern.parts(trunkPattern), CompositeSpawnPattern.parts(key)));
  }

  /** 组合写法去掉首段或末段的 {@code branch}；都不是时为空。纯计算。 */
  static Optional<String> without(List<String> parts, List<String> branch) {
    if (branch.isEmpty() || branch.size() >= parts.size()) {
      return Optional.empty();
    }
    int n = branch.size();
    if (equalsIgnoreCase(parts.subList(parts.size() - n, parts.size()), branch)) {
      return Optional.of(join(parts.subList(0, parts.size() - n)));
    }
    if (equalsIgnoreCase(parts.subList(0, n), branch)) {
      return Optional.of(join(parts.subList(n, parts.size())));
    }
    return Optional.empty();
  }

  private static boolean equalsIgnoreCase(List<String> first, List<String> second) {
    for (int i = 0; i < first.size(); i++) {
      if (!first.get(i).equalsIgnoreCase(second.get(i))) {
        return false;
      }
    }
    return true;
  }

  private static String join(List<String> parts) {
    return String.join("+", parts);
  }

  // ---------------------------------------------------------------- 派生

  /**
   * 派生的结果。
   *
   * @param trips 派生出的支线班次
   * @param couplings 交给派车的加解编计划
   * @param parentOf 派生班次 → 它的主干班次
   * @param notes 没能派生的主干班次（进编表报告）
   */
  public record Derived(
      List<VehicleDutyPlanner.PlannedTrip> trips,
      VehicleDutyPlanner.Couplings couplings,
      Map<UUID, UUID> parentOf,
      List<String> notes) {}

  /**
   * 按每一班主干班次派生支线班次。
   *
   * @param analysis 推出的结构
   * @param trunkTrips 网格上排出的班次（含非主干的）
   * @param plans 交路 → 时分
   * @param routeFlags 交路 → [首站是车库, 以销毁收尾]
   * @param coupleSeconds 回程支线提前到联挂站的秒数
   */
  public static Derived derive(
      Analysis analysis,
      List<VehicleDutyPlanner.PlannedTrip> trunkTrips,
      Map<UUID, TimetableRoutePlan> plans,
      Map<UUID, boolean[]> routeFlags,
      int coupleSeconds) {
    Objects.requireNonNull(analysis, "analysis");
    List<VehicleDutyPlanner.PlannedTrip> derived = new ArrayList<>();
    Map<UUID, VehicleDutyPlanner.Split> splits = new LinkedHashMap<>();
    Map<UUID, UUID> pinned = new LinkedHashMap<>();
    Map<UUID, VehicleDutyPlanner.Join> joins = new LinkedHashMap<>();
    Map<UUID, UUID> feeders = new LinkedHashMap<>();
    Map<UUID, UUID> parentOf = new LinkedHashMap<>();
    // 交路码 → 没能派生的班数
    Map<String, Integer> unjoined = new LinkedHashMap<>();
    Map<String, Integer> unsplit = new LinkedHashMap<>();
    for (VehicleDutyPlanner.PlannedTrip trip : trunkTrips) {
      TrunkSplit split = analysis.splits().get(trip.routeId());
      if (split != null) {
        splits.put(
            trip.tripId(),
            new VehicleDutyPlanner.Split(
                split.stationNodeId(),
                split.departureOffset(),
                Optional.of(split.kept()),
                Optional.of(split.detached()),
                split.action()));
        if (split.target().isPresent()) {
          TimetableRoutePlan plan = plans.get(split.target().get());
          if (plan != null) {
            int departure = trip.departureSeconds() + split.departureOffset();
            VehicleDutyPlanner.PlannedTrip branch =
                derivedTrip(trip, plan, departure, split.detached(), routeFlags, "GOTO");
            derived.add(branch);
            pinned.put(branch.tripId(), trip.tripId());
            parentOf.put(branch.tripId(), trip.tripId());
          } else {
            unsplit.merge(routeCodeOf(trip), 1, Integer::sum);
          }
        }
      }
      TrunkJoin join = analysis.joins().get(trip.routeId());
      if (join != null) {
        TimetableRoutePlan plan = plans.get(join.feeder());
        String branchKey = analysis.consistByRoute().get(join.feeder());
        if (plan != null && branchKey != null) {
          int arrival = trip.departureSeconds() + join.arrivalOffset() - coupleSeconds;
          int departure = arrival - plan.totalRunSeconds();
          if (departure >= 0) {
            VehicleDutyPlanner.PlannedTrip feeder =
                derivedTrip(trip, plan, departure, branchKey, routeFlags, "FROM");
            derived.add(feeder);
            feeders.put(feeder.tripId(), trip.tripId());
            parentOf.put(feeder.tripId(), trip.tripId());
            joins.put(
                trip.tripId(),
                new VehicleDutyPlanner.Join(join.stationNodeId(), Optional.of(join.merged())));
          } else {
            unjoined.merge(routeCodeOf(trip), 1, Integer::sum);
          }
        } else {
          unjoined.merge(routeCodeOf(trip), 1, Integer::sum);
        }
      }
    }
    List<String> notes = new ArrayList<>();
    unjoined.forEach(
        (code, trips) ->
            notes.add(
                "联挂：主干 "
                    + code
                    + " 有 "
                    + trips
                    + " 班派不出回程支线（回程支线要在服务日开始之前发车，或读不到它的时分与编组），这几班不挂车，按联挂之前的编组跑完全程"));
    unsplit.forEach(
        (code, trips) ->
            notes.add(
                "解编：主干 " + code + " 有 " + trips + " 班派不出 GOTO 的支线班次（读不到支线的时分），这几班解下的那一段没有支线班次可跑"));
    return new Derived(
        List.copyOf(derived),
        new VehicleDutyPlanner.Couplings(
            splits, pinned, joins, feeders, analysis.consists(), analysis.partial()),
        Map.copyOf(parentOf),
        List.copyOf(notes));
  }

  /** 班次的交路码（车次写法 {@code 交路码@发车秒}）。 */
  private static String routeCodeOf(VehicleDutyPlanner.PlannedTrip trip) {
    String code = trip.tripCode();
    int at = code.lastIndexOf('@');
    return at < 0 ? code : code.substring(0, at);
  }

  private static VehicleDutyPlanner.PlannedTrip derivedTrip(
      VehicleDutyPlanner.PlannedTrip parent,
      TimetableRoutePlan plan,
      int departure,
      String consist,
      Map<UUID, boolean[]> routeFlags,
      String kind) {
    boolean[] flags = routeFlags.getOrDefault(plan.routeId(), new boolean[] {false, false});
    UUID id =
        UUID.nameUUIDFromBytes(
            ("derived:" + kind + ":" + parent.tripId()).getBytes(StandardCharsets.UTF_8));
    return new VehicleDutyPlanner.PlannedTrip(
        id,
        plan.routeId(),
        String.format(Locale.ROOT, "%s@%06d", plan.routeCode(), departure),
        plan.originNodeId(),
        plan.terminalNodeId(),
        departure,
        plan.totalRunSeconds(),
        flags[0],
        flags[1],
        parent.pool(),
        List.of(new VehicleDutyPlanner.ConsistOption(consist, 1, plan.totalRunSeconds())));
  }
}
