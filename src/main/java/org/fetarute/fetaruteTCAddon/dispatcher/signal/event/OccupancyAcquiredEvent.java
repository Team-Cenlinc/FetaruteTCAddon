package org.fetarute.fetaruteTCAddon.dispatcher.signal.event;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;

/**
 * 占用资源获取事件。
 *
 * <p>当列车成功获取（acquire）一组资源后发布，通知订阅者该列车已占用这些资源，其他等待这些资源的列车需要重新评估信号。
 */
public record OccupancyAcquiredEvent(
    Instant timestamp,
    String trainName,
    List<OccupancyResource> resources,
    List<String> affectedTrains)
    implements SignalEvent {

  public OccupancyAcquiredEvent {
    Objects.requireNonNull(timestamp, "timestamp");
    Objects.requireNonNull(trainName, "trainName");
    resources = resources != null ? List.copyOf(resources) : List.of();
    affectedTrains = affectedTrains != null ? List.copyOf(affectedTrains) : List.of();
  }

  @Override
  public String eventType() {
    return "OCCUPANCY_ACQUIRED";
  }
}
