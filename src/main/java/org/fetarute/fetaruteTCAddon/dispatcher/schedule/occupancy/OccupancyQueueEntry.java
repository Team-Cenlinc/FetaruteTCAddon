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
 * @param enqueueSequence 本冲突队列内的稳定到达序号（越小越早）
 */
public record OccupancyQueueEntry(
    String trainName,
    CorridorDirection direction,
    Instant firstSeen,
    Instant lastSeen,
    int priority,
    int entryOrder,
    long enqueueSequence) {

  /**
   * 构造不参与实时仲裁的诊断队列条目。
   *
   * <p>实时队列由占用管理器显式分配 {@code enqueueSequence}；外部测试夹具与诊断快照沿用该构造器时，默认不获得插队优势。
   */
  public OccupancyQueueEntry(
      String trainName,
      CorridorDirection direction,
      Instant firstSeen,
      Instant lastSeen,
      int priority,
      int entryOrder) {
    this(trainName, direction, firstSeen, lastSeen, priority, entryOrder, Long.MAX_VALUE);
  }

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
    if (enqueueSequence < 0L) {
      throw new IllegalArgumentException("enqueueSequence 不能为负");
    }
  }
}
