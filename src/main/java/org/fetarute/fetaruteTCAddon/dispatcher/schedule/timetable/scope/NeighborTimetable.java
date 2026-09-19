package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.scope;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableConflictChecker;

/**
 * 一份邻表在冲突模型里的形态：它的运行已经按我的零点对齐、按服务日 -1/0/+1 展开并裁剪到我的窗口附近。
 *
 * <p>movements / stays 的 owner 都是本表的 {@link #displayCode()}，冲突检查据此把它们当作不可移动的路权事实。
 *
 * @param timetableId 邻表 UUID
 * @param displayCode {@code company/operator/line/code}
 * @param updatedAt 邻表最后更新时间；基线用它判断"变了没有"
 * @param zoneId 邻表时区
 * @param sharedResources 与我共用的资源数
 * @param staleAgainstGraph 某条 route 重算时分与落库值不一致（邻表基于旧图）
 * @param zoneApproximated 与我时区不同，按参考日的零点偏移换算
 * @param profiles 邻表各 route 的投影（路径来自当前图、时刻来自落库值）
 * @param movements 运行
 * @param stays 站台待命
 * @param warnings 例如"route X 在当前图上不可达，足迹按空计"
 */
public record NeighborTimetable(
    UUID timetableId,
    String displayCode,
    Instant updatedAt,
    ZoneId zoneId,
    int sharedResources,
    boolean staleAgainstGraph,
    boolean zoneApproximated,
    Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
    List<TimetableConflictChecker.Movement> movements,
    List<TimetableConflictChecker.Stay> stays,
    List<String> warnings) {

  public NeighborTimetable {
    Objects.requireNonNull(timetableId, "timetableId");
    Objects.requireNonNull(updatedAt, "updatedAt");
    Objects.requireNonNull(zoneId, "zoneId");
    displayCode = displayCode == null ? "" : displayCode;
    profiles = profiles == null ? Map.of() : Map.copyOf(profiles);
    movements = movements == null ? List.of() : List.copyOf(movements);
    stays = stays == null ? List.of() : List.copyOf(stays);
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
  }
}
