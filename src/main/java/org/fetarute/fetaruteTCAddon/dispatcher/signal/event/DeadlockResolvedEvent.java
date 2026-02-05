package org.fetarute.fetaruteTCAddon.dispatcher.signal.event;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;

/**
 * 死锁解决事件。
 *
 * <p>当死锁被解决（某列车被放行）时发布，通知其他列车重新评估信号状态。
 */
public record DeadlockResolvedEvent(
    Instant timestamp,
    String releasedTrain,
    OccupancyResource conflictResource,
    Duration lockDuration)
    implements SignalEvent {

  public DeadlockResolvedEvent {
    Objects.requireNonNull(timestamp, "timestamp");
    Objects.requireNonNull(releasedTrain, "releasedTrain");
    Objects.requireNonNull(conflictResource, "conflictResource");
    Objects.requireNonNull(lockDuration, "lockDuration");
  }

  @Override
  public String eventType() {
    return "DEADLOCK_RESOLVED";
  }
}
