package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.time.Instant;
import java.util.ArrayList;
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
import java.util.TreeSet;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.scope.NeighborTimetable;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.scope.TimetableBaseline;

/**
 * 多线联编：同一 operator 下的几条线一次编表——共享零点、子网格、相位、让车修复与搜索，产出每线一张表。
 *
 * <p>做法是把各线的 route 合成一次 {@link TimetableBuilder#build}：分类器按 {@code 线/组}
 * 分组（相位第二层跨线交错），冲突检查与让车修复天然覆盖全部线； 派车器按<b>车池</b>（每条线一个）接班，一辆车不跨线——不变量"一辆车不跨两份表"不变。编完再按 route
 * 归属拆成每线一张表：主键按各表自己的 id 重新派生， duty 号每表从 D001 起，{@code updatedAt} 全部等于 {@code builtAt}；每张表的基线互相引用（id
 * + updatedAt），外部邻表的基线原样复制。
 *
 * <p>单线是它的一元情况：一个成员时直接返回单线构建的结果，逐字段一致。
 */
public final class TimetableSetBuilder {

  private final TimetableBuildProgress progress;

  public TimetableSetBuilder() {
    this(TimetableBuildProgress.untracked());
  }

  /**
   * @param progress 进度，交给内部的单次构建去报
   */
  public TimetableSetBuilder(TimetableBuildProgress progress) {
    this.progress = Objects.requireNonNull(progress, "progress");
  }

  /**
   * 一条线。
   *
   * @param input 这条线的单线构建输入（routes 含本 operator 的走行线路，与单线 build 一样）
   * @param lineCode 线路 code，交路组键的前缀
   * @param displayCode 显示码 {@code company/operator/line}，写进别的成员的基线
   * @param ownRouteIds 属于这条线的 route（决定车池与拆表归属；走行线路可在多条线之间共用）
   */
  public record Member(
      TimetableBuilder.BuildInput input,
      String lineCode,
      String displayCode,
      Set<UUID> ownRouteIds) {
    public Member {
      Objects.requireNonNull(input, "input");
      lineCode = lineCode == null ? "" : lineCode;
      displayCode = displayCode == null ? "" : displayCode;
      ownRouteIds = ownRouteIds == null ? Set.of() : Set.copyOf(ownRouteIds);
    }
  }

  /**
   * 输入。
   *
   * @param members 参与的线，顺序即报告顺序；两条以上时各成员的 route 交路组会加 {@code 线/} 前缀
   * @param neighbors 已投影的外部邻表（不含成员自己的表）
   */
  public record SetInput(List<Member> members, List<NeighborTimetable> neighbors) {
    public SetInput {
      members = members == null ? List.of() : List.copyOf(members);
      neighbors = neighbors == null ? List.of() : List.copyOf(neighbors);
      if (members.isEmpty()) {
        throw new IllegalArgumentException("至少一条线");
      }
      Set<String> codes = new TreeSet<>();
      Set<UUID> operators = new TreeSet<>();
      for (Member member : members) {
        codes.add(member.input().code());
        operators.add(member.input().operatorId());
      }
      if (codes.size() > 1) {
        throw new IllegalArgumentException("联编的几条线必须用同一个 code：" + codes);
      }
      if (operators.size() > 1) {
        throw new IllegalArgumentException("联编只能在同一 operator 内：跨 operator 走邻表");
      }
    }
  }

  /**
   * 输出。
   *
   * @param joint 联编的报告：冲突、让车、相位、份额都是全部线一起的
   * @param tables 每线一张表，按成员顺序；构建失败时为空
   * @param baselines 每张表的基线（外部邻表 + 其他成员），键是 lineId
   */
  public record SetResult(
      TimetableBuildResult joint,
      Map<UUID, Timetable> tables,
      Map<UUID, List<TimetableBaseline>> baselines) {
    public SetResult {
      Objects.requireNonNull(joint, "joint");
      tables = tables == null ? Map.of() : new LinkedHashMap<>(tables);
      baselines = baselines == null ? Map.of() : new LinkedHashMap<>(baselines);
    }

    public boolean success() {
      return joint.success();
    }
  }

  /** 多线时的交路组键：{@code 线/组}，没配组的用默认组名。单线不加前缀。 */
  public static String groupKey(String lineCode, Optional<String> spawnGroup, boolean prefixed) {
    String group =
        spawnGroup == null
            ? ServiceGroupClassifier.DEFAULT_GROUP
            : spawnGroup
                .map(String::trim)
                .filter(text -> !text.isEmpty())
                .orElse(ServiceGroupClassifier.DEFAULT_GROUP);
    return prefixed ? lineCode + "/" + group : group;
  }

  /** 联编的（临时）表 id：由 code 与全部线 id 派生，确定。 */
  public static UUID jointTimetableId(String code, List<UUID> lineIds) {
    List<String> sorted = new ArrayList<>();
    for (UUID id : lineIds) {
      sorted.add(id.toString());
    }
    sorted.sort(Comparator.naturalOrder());
    return UUID.nameUUIDFromBytes(
        ("timetable-set:" + code + ":" + String.join(",", sorted))
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  public SetResult build(SetInput input, TimetableBuildOptions options, Instant builtAt) {
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(options, "options");
    Objects.requireNonNull(builtAt, "builtAt");
    List<Member> members = input.members();
    if (members.size() == 1) {
      Member only = members.get(0);
      // 单线也要填归属：不填的话 builder 认为"全部 route 都归我"，别的线的带客走行会被当成我的班次排进表。
      Map<UUID, UUID> ownership = new HashMap<>();
      for (UUID routeId : only.ownRouteIds()) {
        ownership.put(routeId, only.input().lineId());
      }
      TimetableBuilder.BuildInput single =
          withNeighbors(only.input(), input.neighbors(), ownership);
      TimetableBuildResult result = new TimetableBuilder(progress).build(single, options, builtAt);
      Map<UUID, Timetable> tables = new LinkedHashMap<>();
      Map<UUID, List<TimetableBaseline>> baselines = new LinkedHashMap<>();
      result
          .timetable()
          .ifPresent(
              table -> {
                tables.put(only.input().lineId(), table);
                baselines.put(only.input().lineId(), result.baselines());
              });
      return new SetResult(result, tables, baselines);
    }

    // 合并：route 去重（走行线路在各线的列表里都出现，取第一份），交路组加线前缀，车池按归属。
    Map<UUID, TimetableBuilder.RouteInput> routes = new LinkedHashMap<>();
    Map<UUID, UUID> lineByRoute = new HashMap<>();
    List<UUID> lineIds = new ArrayList<>();
    for (Member member : members) {
      lineIds.add(member.input().lineId());
      for (TimetableBuilder.RouteInput route : member.input().routes()) {
        boolean own = member.ownRouteIds().contains(route.routeId());
        if (own) {
          lineByRoute.put(route.routeId(), member.input().lineId());
        }
        TimetableBuilder.RouteInput tagged =
            own ? withGroup(route, groupKey(member.lineCode(), route.spawnGroup(), true)) : route;
        if (own || !routes.containsKey(route.routeId())) {
          routes.put(route.routeId(), tagged);
        }
      }
    }
    Member first = members.get(0);
    TimetableBuilder.BuildInput joint =
        new TimetableBuilder.BuildInput(
            jointTimetableId(first.input().code(), lineIds),
            first.input().companyId(),
            first.input().operatorId(),
            first.input().lineId(),
            first.input().code(),
            first.input().name(),
            new ArrayList<>(routes.values()),
            first.input().graph(),
            first.input().runTimeModel(),
            first.input().notes(),
            input.neighbors(),
            lineByRoute);
    TimetableBuildResult result = new TimetableBuilder(progress).build(joint, options, builtAt);
    if (result.timetable().isEmpty()) {
      return new SetResult(result, Map.of(), Map.of());
    }
    Timetable table = result.timetable().get();
    Map<UUID, Timetable> tables = new LinkedHashMap<>();
    for (Member member : members) {
      tables.put(member.input().lineId(), split(table, member, options, builtAt));
    }
    Map<UUID, List<TimetableBaseline>> baselines = new LinkedHashMap<>();
    for (Member member : members) {
      List<TimetableBaseline> mine = new ArrayList<>();
      UUID myId = member.input().timetableId();
      for (TimetableBaseline external : result.baselines()) {
        mine.add(
            new TimetableBaseline(
                myId,
                external.neighborTimetableId(),
                external.neighborCode(),
                external.neighborUpdatedAt(),
                external.sharedResources(),
                external.conflictsAtTarget(),
                external.staleAgainstGraph()));
      }
      for (Member other : members) {
        if (other == member) {
          continue;
        }
        mine.add(
            new TimetableBaseline(
                myId,
                other.input().timetableId(),
                other.displayCode() + "/" + other.input().code(),
                builtAt,
                0,
                0,
                false));
      }
      baselines.put(member.input().lineId(), List.copyOf(mine));
    }
    return new SetResult(result, tables, baselines);
  }

  /**
   * 从联编的表里拆出一条线的表：本线 route 的班次、这些班次所在的交路（车池保证交路不跨线）、交路引用的走行线路；主键按本表 id 重新派生， duty 号从 D001 起、序号连续。
   */
  static Timetable split(
      Timetable joint, Member member, TimetableBuildOptions options, Instant builtAt) {
    UUID timetableId = member.input().timetableId();
    Set<UUID> own = member.ownRouteIds();
    Map<UUID, TimetableTrip> tripsById = new HashMap<>();
    for (TimetableTrip trip : joint.trips()) {
      tripsById.put(trip.id(), trip);
    }
    // 交路归属：首班的 route 属于本线。
    List<VehicleDuty> duties = new ArrayList<>();
    for (VehicleDuty duty : joint.duties()) {
      if (duty.tripIds().isEmpty()) {
        continue;
      }
      TimetableTrip head = tripsById.get(duty.tripIds().get(0));
      if (head != null && own.contains(head.routeId())) {
        duties.add(duty);
      }
    }
    duties.sort(
        Comparator.comparingInt(VehicleDuty::plannedStartSecondOfDay)
            .thenComparing(VehicleDuty::dutyCode));
    Map<UUID, UUID> dutyIdByOld = new HashMap<>();
    Map<UUID, String> dutyCodeByOld = new HashMap<>();
    for (int i = 0; i < duties.size(); i++) {
      String code = String.format(Locale.ROOT, "D%03d", i + 1);
      dutyIdByOld.put(
          duties.get(i).id(), VehicleDutyPlanner.deterministicDutyId(timetableId, code));
      dutyCodeByOld.put(duties.get(i).id(), code);
    }
    // 班次归属：本线 route 的班次，或挂在本线交路上的班次（带客的回库班）。
    List<TimetableTrip> trips = new ArrayList<>();
    Map<UUID, UUID> tripIdByOld = new HashMap<>();
    for (TimetableTrip trip : joint.trips()) {
      boolean mine =
          own.contains(trip.routeId()) || trip.dutyId().map(dutyIdByOld::containsKey).orElse(false);
      if (!mine) {
        continue;
      }
      UUID id = TimetableTripNumbering.deterministicTripId(timetableId, trip.tripCode());
      tripIdByOld.put(trip.id(), id);
      trips.add(
          new TimetableTrip(
              id,
              timetableId,
              trip.routeId(),
              trips.size(),
              trip.tripCode(),
              trip.departureSecondOfDay(),
              trip.dutyId().map(dutyIdByOld::get)));
    }
    List<VehicleDuty> rewritten = new ArrayList<>(duties.size());
    Set<UUID> referencedRoutes = new LinkedHashSet<>();
    for (int i = 0; i < duties.size(); i++) {
      VehicleDuty duty = duties.get(i);
      List<UUID> ids = new ArrayList<>();
      for (UUID old : duty.tripIds()) {
        UUID id = tripIdByOld.get(old);
        if (id != null) {
          ids.add(id);
        }
      }
      duty.createRouteId().ifPresent(referencedRoutes::add);
      duty.returnRouteId().ifPresent(referencedRoutes::add);
      rewritten.add(
          new VehicleDuty(
              dutyIdByOld.get(duty.id()),
              timetableId,
              i,
              dutyCodeByOld.get(duty.id()),
              duty.startDepotNodeId(),
              duty.endDepotNodeId(),
              duty.createRouteId(),
              duty.returnRouteId(),
              ids,
              duty.plannedStartSecondOfDay(),
              duty.returnSecondOfDay(),
              duty.plannedEndSecondOfDay(),
              duty.closeReason()));
    }
    for (TimetableTrip trip : trips) {
      referencedRoutes.add(trip.routeId());
    }
    List<TimetableRoutePlan> plans = new ArrayList<>();
    for (TimetableRoutePlan plan : joint.routePlans()) {
      if (own.contains(plan.routeId()) || referencedRoutes.contains(plan.routeId())) {
        plans.add(plan);
      }
    }
    return new Timetable(
        timetableId,
        member.input().companyId(),
        member.input().operatorId(),
        member.input().lineId(),
        member.input().code(),
        member.input().name(),
        TimetableStatus.DRAFT,
        options.zoneId(),
        options.serviceStartSecondOfDay(),
        options.serviceEndSecondOfDay(),
        plans,
        trips,
        rewritten,
        member.input().notes(),
        builtAt,
        builtAt);
  }

  private static TimetableBuilder.BuildInput withNeighbors(
      TimetableBuilder.BuildInput input,
      List<NeighborTimetable> neighbors,
      Map<UUID, UUID> lineByRoute) {
    return new TimetableBuilder.BuildInput(
        input.timetableId(),
        input.companyId(),
        input.operatorId(),
        input.lineId(),
        input.code(),
        input.name(),
        input.routes(),
        input.graph(),
        input.runTimeModel(),
        input.notes(),
        neighbors.isEmpty() ? input.neighbors() : neighbors,
        lineByRoute);
  }

  private static TimetableBuilder.RouteInput withGroup(
      TimetableBuilder.RouteInput route, String group) {
    return new TimetableBuilder.RouteInput(
        route.routeId(),
        route.routeCode(),
        route.operationType(),
        route.weight(),
        route.definition(),
        route.stops(),
        route.depotNodeId(),
        route.declaredAs(),
        route.external(),
        Optional.of(group));
  }
}
