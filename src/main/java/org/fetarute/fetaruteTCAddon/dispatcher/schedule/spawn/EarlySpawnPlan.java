package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 一次手动提前出车：要提前出的那一班，以及它在车库等候期间别的车对车库的计划使用，供挑股道。
 *
 * @param ticket 提前出的票（出库点尚未定）；出库点规范取 {@code ticket.service().depotNodeId()}
 * @param requestedNode 命令里指定的车库节点
 * @param tripCode 要开的车次
 * @param plannedDeparture 计划从车库发车的时刻
 * @param departures 计划发车之前要出库的别的票（含已在队列里等出库的）
 * @param arrivals 计划发车之前按时刻表回库的交路
 */
public record EarlySpawnPlan(
    SpawnTicket ticket,
    String requestedNode,
    String tripCode,
    Instant plannedDeparture,
    List<SpawnTicket> departures,
    List<DepotArrival> arrivals) {

  public EarlySpawnPlan {
    Objects.requireNonNull(ticket, "ticket");
    Objects.requireNonNull(requestedNode, "requestedNode");
    Objects.requireNonNull(plannedDeparture, "plannedDeparture");
    tripCode = tripCode == null ? "" : tripCode;
    departures = departures == null ? List.of() : List.copyOf(departures);
    arrivals = arrivals == null ? List.of() : List.copyOf(arrivals);
  }

  /**
   * 一个交路按时刻表回库。
   *
   * @param subject 交路号
   * @param routeId 回库所走的线路（回库走行，或以销毁收尾的末班线路）；查不到时为空
   * @param depotNodeId 时刻表记下的回库点
   * @param at 计划到达车库的时刻
   */
  public record DepotArrival(
      String subject, Optional<UUID> routeId, String depotNodeId, Instant at) {
    public DepotArrival {
      subject = subject == null ? "" : subject;
      routeId = routeId == null ? Optional.empty() : routeId;
      depotNodeId = depotNodeId == null ? "" : depotNodeId;
      Objects.requireNonNull(at, "at");
    }
  }

  /** 挑股道：现场情况由发车侧收集（见 {@link SimpleTicketAssigner#chooseEarlySpawnTrack}）。 */
  @FunctionalInterface
  public interface TrackChooser {
    EarlySpawnYard.Decision choose(EarlySpawnPlan plan);
  }
}
