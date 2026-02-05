package org.fetarute.fetaruteTCAddon.dispatcher.signal.event;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;

/**
 * 死锁检测事件。
 *
 * <p>当检测到多列车循环依赖（如 A→B→A）时发布，通知死锁解决器介入。
 */
public record DeadlockDetectedEvent(
    Instant timestamp,
    List<String> involvedTrains,
    OccupancyResource conflictResource,
    String description)
    implements SignalEvent {

  public DeadlockDetectedEvent {
    Objects.requireNonNull(timestamp, "timestamp");
    involvedTrains = involvedTrains != null ? List.copyOf(involvedTrains) : List.of();
    Objects.requireNonNull(conflictResource, "conflictResource");
  }

  @Override
  public String eventType() {
    return "DEADLOCK_DETECTED";
  }
}
