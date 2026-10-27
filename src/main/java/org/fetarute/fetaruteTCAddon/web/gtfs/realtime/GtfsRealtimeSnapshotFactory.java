package org.fetarute.fetaruteTCAddon.web.gtfs.realtime;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.fetarute.fetaruteTCAddon.api.FetaruteApi;
import org.fetarute.fetaruteTCAddon.api.train.TrainApi;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainRuntimeSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainSnapshotStore;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableService;
import org.fetarute.fetaruteTCAddon.display.pids.PidsService;
import org.fetarute.fetaruteTCAddon.display.pids.bulletin.PidsBulletin;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsLineStatus;

/**
 * 插件侧的实时数据源：把公开 API 的车次绑定、取消登记，运行时列车快照、站台屏公告与线路运行状况拼成 {@link GtfsRealtimeSnapshot}。
 *
 * <p>全部读内存快照：车次绑定在 tick 内缓存、列车快照是并发安全的拷贝、公告板是并发 Map、线路状况是站台屏的汇总。可在 web 线程调用。
 *
 * <p>取消登记取发布前后各 3 小时：更早的取消对行程规划已经没有意义，更远期的取消还没进登记。
 */
public final class GtfsRealtimeSnapshotFactory implements GtfsRealtimeSource {

  /** 取消登记窗口：现在前后各 3 小时。 */
  private static final Duration CANCELLATION_WINDOW = Duration.ofHours(3);

  private final Supplier<Optional<FetaruteApi>> api;
  private final Supplier<Optional<TrainSnapshotStore>> trains;
  private final Supplier<Optional<PidsService>> pids;
  private final Supplier<Optional<TimetableService>> timetables;

  /**
   * @param api 公开 API（车次绑定、取消、在线列车状态）
   * @param trains 运行时列车快照（位置、速度、载客）
   * @param pids 站台屏服务（公告与线路运行状况）
   * @param timetables 时刻表服务（越站查询）
   */
  public GtfsRealtimeSnapshotFactory(
      Supplier<Optional<FetaruteApi>> api,
      Supplier<Optional<TrainSnapshotStore>> trains,
      Supplier<Optional<PidsService>> pids,
      Supplier<Optional<TimetableService>> timetables) {
    this.api = Objects.requireNonNull(api, "api");
    this.trains = Objects.requireNonNull(trains, "trains");
    this.pids = Objects.requireNonNull(pids, "pids");
    this.timetables = Objects.requireNonNull(timetables, "timetables");
  }

  /** 取当前车次绑定、取消登记、列车位置与站台屏公告的一份快照；公开 API 还没初始化时为空。 */
  @Override
  public Optional<GtfsRealtimeSnapshot> snapshot(Instant now) {
    Optional<FetaruteApi> current = api.get();
    if (current.isEmpty()) {
      return Optional.empty();
    }
    FetaruteApi api = current.get();
    Map<String, TrainRuntimeSnapshot> running =
        trains.get().map(TrainSnapshotStore::snapshot).orElse(Map.of());
    Set<String> outOfService =
        api.trains().listAllActiveTrains(false).stream()
            .filter(TrainApi.TrainSnapshot::outOfService)
            .map(TrainApi.TrainSnapshot::trainName)
            .collect(Collectors.toUnmodifiableSet());
    Optional<PidsService> pidsService = pids.get();
    List<PidsBulletin> bulletins =
        pidsService.map(service -> service.bulletins().all()).orElse(List.of());
    BiFunction<String, String, Optional<PidsLineStatus>> lineStatus =
        (operator, line) -> pidsService.flatMap(service -> service.lineStatus(operator, line));
    Optional<TimetableService> timetableService = timetables.get();
    return Optional.of(
        new GtfsRealtimeSnapshot(
            now,
            List.copyOf(api.timetables().listAssignments()),
            api.timetables()
                .cancellations(now.minus(CANCELLATION_WINDOW), now.plus(CANCELLATION_WINDOW)),
            running,
            outOfService,
            bulletins,
            lineStatus,
            (timetableId, tripId, serviceDate) ->
                timetableService
                    .map(service -> service.skippedStops(timetableId, tripId, serviceDate))
                    .orElse(Set.of())));
  }
}
