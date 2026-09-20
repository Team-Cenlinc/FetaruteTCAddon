package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 派车与端点串行之后的收尾：按<b>实际</b>发车顺序给班次编号、按名字派生主键、把 duty 里的临时引用换成正式主键。
 *
 * <p>从 {@link TimetableBuilder#attempt} 抽出来，因为编号必须在串行之后做：串行会让同一条 route 的两班交换先后，
 * 而车次号是给人看的顺序、主键又由车次号派生，所以"谁先发谁 001"只能按最终时刻定。 排序键是（实际发车，名义发车，临时 code），全部确定。
 */
final class TimetableTripNumbering {

  private TimetableTripNumbering() {}

  /**
   * 编号结果。
   *
   * @param trips 正式班次（当日秒数已取模）
   * @param duties 正式交路（引用正式主键）
   * @param finalByProvisional 临时主键 → 正式主键
   */
  record Numbered(
      List<TimetableTrip> trips, List<VehicleDuty> duties, Map<UUID, UUID> finalByProvisional) {}

  /**
   * 编号。
   *
   * @param timetableId 时刻表主键（主键派生用）
   * @param options 运营参数（车次号前缀、零点）
   * @param serialized 串行后的临时表：trips 的时刻是 {@code zero + 相对秒}（未取模），code 是临时 code
   * @param nominalByProvisional 每班的名义时隙（相对秒），排序的第二键
   */
  static Numbered number(
      UUID timetableId,
      TimetableBuildOptions options,
      Timetable serialized,
      Map<UUID, Integer> nominalByProvisional) {
    Objects.requireNonNull(timetableId, "timetableId");
    Objects.requireNonNull(options, "options");
    Objects.requireNonNull(serialized, "serialized");
    List<TimetableTrip> ordered = new ArrayList<>(serialized.trips());
    ordered.sort(
        Comparator.comparingInt(TimetableTrip::departureSecondOfDay)
            .thenComparingInt(trip -> nominalByProvisional.getOrDefault(trip.id(), 0))
            .thenComparing(TimetableTrip::tripCode));
    Map<String, Integer> perRouteCounter = new LinkedHashMap<>();
    Map<UUID, UUID> finalByProvisional = new HashMap<>();
    List<TimetableTrip> trips = new ArrayList<>(ordered.size());
    int emitted = 0;
    for (TimetableTrip trip : ordered) {
      String routeCode =
          serialized.routePlan(trip.routeId()).map(TimetableRoutePlan::routeCode).orElse("?");
      int serial = perRouteCounter.merge(routeCode, 1, Integer::sum);
      String tripCode =
          String.format(Locale.ROOT, "%s%s-%03d", options.tripCodePrefix(), routeCode, serial);
      UUID tripId = deterministicTripId(timetableId, tripCode);
      finalByProvisional.put(trip.id(), tripId);
      trips.add(
          new TimetableTrip(
              tripId,
              timetableId,
              trip.routeId(),
              emitted,
              tripCode,
              Math.floorMod(trip.departureSecondOfDay(), TimetableTrip.SECONDS_PER_DAY),
              trip.dutyId()));
      emitted++;
    }
    List<VehicleDuty> duties = new ArrayList<>(serialized.duties().size());
    for (VehicleDuty duty : serialized.duties()) {
      List<UUID> ids = new ArrayList<>(duty.tripIds().size());
      for (UUID provisional : duty.tripIds()) {
        UUID finalId = finalByProvisional.get(provisional);
        if (finalId != null) {
          ids.add(finalId);
        }
      }
      if (ids.isEmpty()) {
        continue;
      }
      duties.add(
          new VehicleDuty(
              duty.id(),
              duty.timetableId(),
              duty.sequence(),
              duty.dutyCode(),
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
    // 交路引用换成正式主键后，班次的 dutyId 不变（duty 主键在派车时已按 dutyCode 派生）。
    return new Numbered(List.copyOf(trips), List.copyOf(duties), Map.copyOf(finalByProvisional));
  }

  /**
   * 由时刻表 ID 与车次号派生稳定的 trip UUID。
   *
   * <p>主键会进数据库、会被 duty 引用、会出现在导出里，随机化会让"同样输入构建两次结果一致"这条性质在主键层面失效。
   */
  static UUID deterministicTripId(UUID timetableId, String tripCode) {
    return UUID.nameUUIDFromBytes(
        ("trip:" + timetableId + ":" + tripCode).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
}
