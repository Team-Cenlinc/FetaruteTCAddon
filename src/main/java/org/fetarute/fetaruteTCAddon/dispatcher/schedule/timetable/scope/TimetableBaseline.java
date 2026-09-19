package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.scope;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 基线：build 时读到的一份邻表的身份，以及与它的冲突计数。
 *
 * <p>"同一份网络 + 同一份配置 + 同一组已发布邻表 → 同一张表"里的第三项就是这张记录：邻表以 {@code timetableId + updatedAt} 逐一标识，publish
 * 时拿它判断邻表集合有没有变、要不要重检。
 *
 * @param timetableId 我这份表
 * @param neighborTimetableId 邻表
 * @param neighborCode 邻表显示码 {@code company/operator/line/code}
 * @param neighborUpdatedAt 邻表 build 时的最后更新时间
 * @param sharedResources 足迹交集大小
 * @param conflictsAtTarget build 时目标间隔下与该邻表的外部冲突数
 * @param staleAgainstGraph 邻表某条 route 的落库时分与当前图重算不一致
 */
public record TimetableBaseline(
    UUID timetableId,
    UUID neighborTimetableId,
    String neighborCode,
    Instant neighborUpdatedAt,
    int sharedResources,
    int conflictsAtTarget,
    boolean staleAgainstGraph) {

  public TimetableBaseline {
    Objects.requireNonNull(timetableId, "timetableId");
    Objects.requireNonNull(neighborTimetableId, "neighborTimetableId");
    Objects.requireNonNull(neighborUpdatedAt, "neighborUpdatedAt");
    neighborCode = neighborCode == null ? "" : neighborCode;
    sharedResources = Math.max(0, sharedResources);
    conflictsAtTarget = Math.max(0, conflictsAtTarget);
  }

  /** 这条基线还对得上当前的邻表吗（同一份表、同一次更新）。 */
  public boolean matches(NeighborTimetable neighbor) {
    return neighbor != null
        && neighborTimetableId.equals(neighbor.timetableId())
        && neighborUpdatedAt.equals(neighbor.updatedAt());
  }
}
