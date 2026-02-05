package org.fetarute.fetaruteTCAddon.dispatcher.signal.event;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;

/**
 * 占用资源释放事件。
 *
 * <p>当列车释放一组资源后发布，通知订阅者这些资源已可用，等待这些资源的列车可重新评估信号。
 */
public record OccupancyReleasedEvent(
    Instant timestamp, String trainName, List<OccupancyResource> releasedResources)
    implements SignalEvent {

  public OccupancyReleasedEvent {
    Objects.requireNonNull(timestamp, "timestamp");
    Objects.requireNonNull(trainName, "trainName");
    releasedResources = releasedResources != null ? List.copyOf(releasedResources) : List.of();
  }

  @Override
  public String eventType() {
    return "OCCUPANCY_RELEASED";
  }
}
