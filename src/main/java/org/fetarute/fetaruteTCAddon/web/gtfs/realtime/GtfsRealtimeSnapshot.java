package org.fetarute.fetaruteTCAddon.web.gtfs.realtime;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import org.fetarute.fetaruteTCAddon.api.timetable.TimetableApi;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainRuntimeSnapshot;
import org.fetarute.fetaruteTCAddon.display.pids.bulletin.PidsBulletin;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsLineStatus;

/**
 * 生成一版 GTFS-Realtime 需要的运行时事实，一次读齐。
 *
 * <p>与静态包一样，全部取自内存快照，可在 web 线程调用：
 *
 * <ul>
 *   <li>车次绑定、上一站/下一站与晚点：{@link TimetableApi#listAssignments()}（tick 内缓存，锁外算）；
 *   <li>取消：{@link TimetableApi#cancellations(Instant, Instant)}（命中窗口内仍有效的登记）；
 *   <li>在线列车：运行时列车快照（{@code TrainSnapshotStore}），含车头坐标、速度、载客与停站剩余时间；
 *   <li>越站：{@link SkippedStopsLookup}（按车次实例列出停靠序号）；
 *   <li>公告：站台屏公告板；线路运行状况：站台屏同一份汇总。
 * </ul>
 *
 * <p>{@code outOfServiceTrains} 是已经越过运营终点（EOP）的回库车：它们不再对乘客发布车次与位置（见设计稿 Q10）。
 *
 * @param generatedAt 快照时刻
 * @param assignments 当前车次绑定
 * @param cancellations 取消登记
 * @param trains 在线列车快照（列车名 → 快照）
 * @param outOfServiceTrains 已越过 EOP 的列车名
 * @param bulletins 站台屏公告
 * @param lineStatus 线路运行状况（运营商代码、线路代码）；站台屏没启用时返回空
 * @param skippedStops 越站查询
 */
public record GtfsRealtimeSnapshot(
    Instant generatedAt,
    List<TimetableApi.TrainAssignment> assignments,
    List<TimetableApi.CancelledTrip> cancellations,
    Map<String, TrainRuntimeSnapshot> trains,
    Set<String> outOfServiceTrains,
    List<PidsBulletin> bulletins,
    BiFunction<String, String, Optional<PidsLineStatus>> lineStatus,
    SkippedStopsLookup skippedStops) {

  public GtfsRealtimeSnapshot {
    Objects.requireNonNull(generatedAt, "generatedAt");
    assignments = assignments == null ? List.of() : List.copyOf(assignments);
    cancellations = cancellations == null ? List.of() : List.copyOf(cancellations);
    trains = trains == null ? Map.of() : Map.copyOf(trains);
    outOfServiceTrains = outOfServiceTrains == null ? Set.of() : Set.copyOf(outOfServiceTrains);
    bulletins = bulletins == null ? List.of() : List.copyOf(bulletins);
    lineStatus = lineStatus == null ? (operator, line) -> Optional.empty() : lineStatus;
    skippedStops = skippedStops == null ? SkippedStopsLookup.none() : skippedStops;
  }

  /** 什么都没起来时的一版空快照：报文仍然合法，只是没有实体。 */
  public static GtfsRealtimeSnapshot empty(Instant now) {
    return new GtfsRealtimeSnapshot(
        now,
        List.of(),
        List.of(),
        Map.of(),
        Set.of(),
        List.of(),
        (operator, line) -> Optional.empty(),
        SkippedStopsLookup.none());
  }

  /** 按车次实例的越站查询。 */
  @FunctionalInterface
  public interface SkippedStopsLookup {

    /**
     * @param timetableId 时刻表
     * @param tripId 车次 UUID
     * @param serviceDate 起点发车所在日期
     * @return 越站的停靠序号
     */
    Set<Integer> skipped(UUID timetableId, UUID tripId, LocalDate serviceDate);

    /** 没有越站来源：一律为空集。 */
    static SkippedStopsLookup none() {
      return (timetableId, tripId, serviceDate) -> Set.of();
    }
  }
}
