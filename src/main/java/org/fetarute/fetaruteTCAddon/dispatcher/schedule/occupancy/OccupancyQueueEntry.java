package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.time.Instant;
import java.util.Objects;

/**
 * 占用队列条目，用于诊断输出。
 *
 * @param trainName 列车名（标准化后用于去重）
 * @param direction 进入走廊的方向
 * @param firstSeen 首次进入排队时间
 * @param lastSeen 最近刷新时间
 * @param priority 排队优先级（越大越优先）
 * @param entryOrder 进入冲突区的边序号（越小越接近入口）
 */
public record OccupancyQueueEntry(
    String trainName,
    CorridorDirection direction,
    Instant firstSeen,
    Instant lastSeen,
    int priority,
    int entryOrder) {

  public OccupancyQueueEntry {
    Objects.requireNonNull(trainName, "trainName");
    Objects.requireNonNull(direction, "direction");
    Objects.requireNonNull(firstSeen, "firstSeen");
    Objects.requireNonNull(lastSeen, "lastSeen");
    if (trainName.isBlank()) {
      throw new IllegalArgumentException("trainName 不能为空");
    }
    if (entryOrder < 0) {
      throw new IllegalArgumentException("entryOrder 不能为负");
    }
  }
}
