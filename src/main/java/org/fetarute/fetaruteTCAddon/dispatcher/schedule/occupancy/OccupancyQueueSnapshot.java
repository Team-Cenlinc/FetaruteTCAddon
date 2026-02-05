package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 占用队列快照：记录某个资源的排队条目与当前占用方向。
 *
 * <p>activeDirection 仅适用于单线走廊冲突，用于诊断方向锁状态。
 *
 * <p>entries 中包含 priority/entryOrder，便于定位冲突区放行与队列排序细节。
 *
 * @param resource 冲突资源
 * @param activeDirection 当前占用方向（无方向时 empty）
 * @param activeClaims 当前已占用数量
 * @param entries 排队条目（按入队顺序）
 */
public record OccupancyQueueSnapshot(
    OccupancyResource resource,
    Optional<CorridorDirection> activeDirection,
    int activeClaims,
    List<OccupancyQueueEntry> entries) {

  public OccupancyQueueSnapshot {
    Objects.requireNonNull(resource, "resource");
    Objects.requireNonNull(activeDirection, "activeDirection");
    Objects.requireNonNull(entries, "entries");
    entries = List.copyOf(entries);
  }
}
