package org.fetarute.fetaruteTCAddon.api.event;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.event.Event;

/** 车站到发事件的公共字段；请监听具体子类。 */
public abstract class TrainStationEvent extends Event {

  private final String trainName;
  private final Optional<UUID> routeId;
  private final String routeKey;
  private final int stopIndex;
  private final String nodeId;
  private final boolean terminal;
  private final Instant at;

  protected TrainStationEvent(
      String trainName,
      Optional<UUID> routeId,
      String routeKey,
      int stopIndex,
      String nodeId,
      boolean terminal,
      Instant at) {
    this.trainName = Objects.requireNonNull(trainName, "trainName");
    this.routeId = routeId == null ? Optional.empty() : routeId;
    this.routeKey = routeKey == null ? "" : routeKey;
    this.stopIndex = stopIndex;
    this.nodeId = nodeId == null ? "" : nodeId;
    this.terminal = terminal;
    this.at = Objects.requireNonNull(at, "at");
  }

  /** 列车名。 */
  public String getTrainName() {
    return trainName;
  }

  /** 交路 ID（仅由代码定义的交路为空）。 */
  public Optional<UUID> getRouteId() {
    return routeId;
  }

  /** 交路 key（{@code 运营商:线路:交路}），始终存在。 */
  public String getRouteKey() {
    return routeKey;
  }

  /**
   * 本站停靠序号：交路节点的 0 起下标，与 RouteApi 停靠表的 {@code sequence}、TimetableApi 的 {@code stopSequence} 同一口径。
   */
  public int getStopIndex() {
    return stopIndex;
  }

  /** 实际停靠的调度图节点（含股道，如 {@code SURC:S:HHU:2}）。 */
  public String getNodeId() {
    return nodeId;
  }

  /** 是否为交路的最后一个节点。 */
  public boolean isTerminal() {
    return terminal;
  }

  /** 事实发生时刻（事件在下一 tick 才发出，以此为准）。 */
  public Instant getAt() {
    return at;
  }
}
