package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.model.ServiceTrip;

/**
 * 将 schedule 层的 {@link ServiceTrip} 转换为现有 spawn 层可执行的对象。
 *
 * <p>这是第一阶段的最小适配层：不替换 {@link StorageSpawnManager} 的出票逻辑，也不改变 TrainCarts destination
 * 下发策略。后续若真正启用静态时刻表快照，可以先通过该适配器派生 {@link SpawnTicket}，再交给现有 {@link TicketAssigner} 走
 * SpawnControl、occupancy 和 depot 行为。
 */
public final class ServiceTripSpawnAdapter {

  private ServiceTripSpawnAdapter() {}

  /** 从 ServiceTrip 派生 SpawnService，保留线路/route 标识与 depot 起点。 */
  public static SpawnService toSpawnService(ServiceTrip trip, Duration defaultHeadway) {
    Objects.requireNonNull(trip, "trip");
    if (trip.depotCandidates().isEmpty()) {
      throw new IllegalArgumentException("ServiceTrip 缺少 depotCandidates，无法派生 SpawnService");
    }
    String depotNodeId = trip.depotCandidates().get(0).nodeId();
    Duration headway =
        defaultHeadway == null || defaultHeadway.isZero() || defaultHeadway.isNegative()
            ? Duration.ofSeconds(1)
            : defaultHeadway;
    return new SpawnService(
        new SpawnServiceKey(trip.routeId()),
        trip.companyId(),
        trip.companyCode(),
        trip.operatorId(),
        trip.operatorCode(),
        trip.lineId(),
        trip.lineCode(),
        trip.routeId(),
        trip.routeCode(),
        headway,
        depotNodeId);
  }

  /**
   * 从 ServiceTrip 生成一张可入队票据。
   *
   * <p>{@code sequenceNumber} 由调用方提供，以便与现有队列排序兼容。票据 due/notBefore 均使用 {@link
   * ServiceTrip#plannedDeparture()}。
   */
  public static SpawnTicket toSpawnTicket(
      ServiceTrip trip, Duration defaultHeadway, long sequenceNumber) {
    SpawnService service = toSpawnService(trip, defaultHeadway);
    return new SpawnTicket(
        UUID.randomUUID(),
        service,
        trip.plannedDeparture(),
        trip.plannedDeparture(),
        0,
        sequenceNumber,
        Optional.empty(),
        Optional.empty(),
        Optional.of(trip.tripId()),
        trip.source(),
        trip.priority());
  }
}
