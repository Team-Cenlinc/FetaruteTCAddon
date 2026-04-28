package org.fetarute.fetaruteTCAddon.dispatcher.schedule.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnDepot;

/**
 * 一趟可服务车次的静态计划描述。
 *
 * <p>ServiceTrip 是“静态 schedule backbone + On-Demand overlay + 动态 fallback”的共同承载层： 静态 headway
 * 生成的车、按需加开的车、恢复车和手工车都可以用同一个不可变模型表达。它只描述计划意图，不保存 delay、当前停靠索引、已占用资源或 hold 状态，这些 mutable 信息必须留在运行时层。
 *
 * @param tripId 稳定车次 ID，供人工审阅、导出与后续快照关联使用
 * @param source 车次来源
 * @param companyId 公司 UUID
 * @param companyCode 公司 code
 * @param operatorId 运营商 UUID
 * @param operatorCode 运营商 code
 * @param lineId 线路 UUID
 * @param lineCode 线路 code
 * @param routeId Route UUID
 * @param routeCode Route code
 * @param direction 方向或运营方向标记；第一阶段没有配置时为空
 * @param plannedDeparture 计划出库或复用发车时间
 * @param plannedStops 计划停靠点列表
 * @param priority 发车/资源仲裁优先级，值越大越优先
 * @param depotCandidates 可用 depot 候选；无线路 depot pool 时包含 route 起点 depot
 * @param maxOperationTrips 交路组最大运营圈数；未配置时为空
 * @param notes 计划生成器补充说明
 */
public record ServiceTrip(
    String tripId,
    TripSource source,
    UUID companyId,
    String companyCode,
    UUID operatorId,
    String operatorCode,
    UUID lineId,
    String lineCode,
    UUID routeId,
    String routeCode,
    Optional<String> direction,
    Instant plannedDeparture,
    List<ScheduledStop> plannedStops,
    int priority,
    List<SpawnDepot> depotCandidates,
    Optional<Integer> maxOperationTrips,
    Optional<String> notes) {

  public ServiceTrip {
    Objects.requireNonNull(tripId, "tripId");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(companyId, "companyId");
    Objects.requireNonNull(operatorId, "operatorId");
    Objects.requireNonNull(lineId, "lineId");
    Objects.requireNonNull(routeId, "routeId");
    Objects.requireNonNull(plannedDeparture, "plannedDeparture");
    tripId = tripId.trim();
    if (tripId.isBlank()) {
      throw new IllegalArgumentException("tripId 不能为空");
    }
    companyCode = normalizeRequiredCode(companyCode, "companyCode");
    operatorCode = normalizeRequiredCode(operatorCode, "operatorCode");
    lineCode = normalizeRequiredCode(lineCode, "lineCode");
    routeCode = normalizeRequiredCode(routeCode, "routeCode");
    direction = normalizeOptional(direction);
    plannedStops = plannedStops == null ? List.of() : List.copyOf(plannedStops);
    depotCandidates = depotCandidates == null ? List.of() : List.copyOf(depotCandidates);
    maxOperationTrips =
        maxOperationTrips == null
            ? Optional.empty()
            : maxOperationTrips.filter(value -> value != null && value > 0);
    notes = normalizeOptional(notes);
  }

  private static String normalizeRequiredCode(String value, String name) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isBlank()) {
      throw new IllegalArgumentException(name + " 不能为空");
    }
    return normalized;
  }

  private static Optional<String> normalizeOptional(Optional<String> value) {
    if (value == null) {
      return Optional.empty();
    }
    return value.map(String::trim).filter(text -> !text.isBlank());
  }
}
