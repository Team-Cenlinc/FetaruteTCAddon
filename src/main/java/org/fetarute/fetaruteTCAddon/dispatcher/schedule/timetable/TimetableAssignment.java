package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * “这辆车正在跑哪一趟表定车次、属于哪个车辆交路”的运行时绑定。
 *
 * <p>绑定只存在于内存。重启后所有车都回到自由运行，直到各自下一次经过某个能唯一匹配车次的停靠点——
 * 这是刻意的取舍：错误的绑定会让车被扣在站里等一个不属于它的时刻，而丢失绑定只会退回现状。宁可少绑，不可错绑。
 *
 * <p>{@code dutyId} 让运行时知道这辆车的交路边界在哪：跑完 duty 的最后一班之后，它<b>不允许</b>再接班， 必须回库。这条约束由 {@link
 * TimetableService#allowsLayoverReuse} 执行。
 *
 * @param trainName 规范列车名
 * @param timetableId 时刻表 UUID
 * @param tripId 车次 UUID
 * @param tripCode 车次号
 * @param routeId 本班次执行的 Route
 * @param dutyId 所属车辆交路；表里没有 duty 时为空
 * @param serviceDate 服务日期（时刻表所在时区）
 * @param assignedAt 绑定时间
 * @param assignedAtStopIndex 绑定发生在哪个停靠点
 * @param initialDeviationSeconds 绑定时相对计划的偏差（正数为晚点）
 */
public record TimetableAssignment(
    String trainName,
    UUID timetableId,
    UUID tripId,
    String tripCode,
    UUID routeId,
    Optional<UUID> dutyId,
    LocalDate serviceDate,
    Instant assignedAt,
    int assignedAtStopIndex,
    long initialDeviationSeconds) {

  public TimetableAssignment {
    Objects.requireNonNull(timetableId, "timetableId");
    Objects.requireNonNull(tripId, "tripId");
    Objects.requireNonNull(routeId, "routeId");
    Objects.requireNonNull(serviceDate, "serviceDate");
    Objects.requireNonNull(assignedAt, "assignedAt");
    trainName = trainName == null ? "" : trainName.trim();
    tripCode = tripCode == null ? "" : tripCode.trim();
    dutyId = dutyId == null ? Optional.empty() : dutyId;
  }
}
