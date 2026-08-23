package org.fetarute.fetaruteTCAddon.dispatcher.signal.event;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;

/**
 * Gate Queue 仲裁状态变化事件。
 *
 * <p>该事件只表示指定资源的排队顺序、方向或队首资格已经变化，不表示任何物理占用 claim 已释放。订阅者必须重读最新队列并进入完整授权链，不能据此直接发布可行车信号。
 *
 * @param timestamp 状态变化时间
 * @param sourceTrainName 触发变化的逻辑列车名；批量过期等无单一来源时使用 {@code *}
 * @param affectedResources 仲裁状态发生变化的资源
 * @param eligibleTrainNames 此次变化实际获得重新授权机会的 Gate Queue 队首；仅该列表可触发完整授权
 */
public record OccupancyQueueChangedEvent(
    Instant timestamp,
    String sourceTrainName,
    List<OccupancyResource> affectedResources,
    List<String> eligibleTrainNames)
    implements SignalEvent {

  /** 兼容仅用于诊断的队列状态变更；没有新的队首资格时不得唤醒完整授权。 */
  public OccupancyQueueChangedEvent(
      Instant timestamp, String sourceTrainName, List<OccupancyResource> affectedResources) {
    this(timestamp, sourceTrainName, affectedResources, List.of());
  }

  public OccupancyQueueChangedEvent {
    Objects.requireNonNull(timestamp, "timestamp");
    sourceTrainName =
        sourceTrainName == null || sourceTrainName.isBlank() ? "*" : sourceTrainName.trim();
    affectedResources = affectedResources == null ? List.of() : List.copyOf(affectedResources);
    eligibleTrainNames =
        eligibleTrainNames == null
            ? List.of()
            : eligibleTrainNames.stream()
                .filter(trainName -> trainName != null && !trainName.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
  }

  @Override
  public String eventType() {
    return "OCCUPANCY_QUEUE_CHANGED";
  }
}
