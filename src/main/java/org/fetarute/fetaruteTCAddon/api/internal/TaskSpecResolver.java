package org.fetarute.fetaruteTCAddon.api.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.api.timetable.TimetableApi;
import org.fetarute.fetaruteTCAddon.company.api.StationDirectory;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteTerminals;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableService;
import org.fetarute.fetaruteTCAddon.drive.driver.task.DriverTaskManager;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskKey;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskStations;

/** 把派任务的请求（车次、接班站、交班站）换成任务规格：驾驶任务与车掌任务共用同一套车次与车站的核对。只在服务器主线程调用。 */
final class TaskSpecResolver {

  /** 换不成规格的原因。 */
  enum Failure {
    /** 找不到这个车次，或它在接班站已取消。 */
    UNAVAILABLE,
    /** 接班站或交班站不是这趟车停车的车站，或交班站不在接班站之后。 */
    INVALID_STATIONS,
    /** 列车已经开过接班站，或还没对上列车而接班站计划发车早已过去。 */
    DEPARTED
  }

  /**
   * 换算结果。
   *
   * @param spec 任务规格；失败时为 {@code null}
   * @param failure 失败原因；成功时为 {@code null}
   */
  record Result(DriverTaskManager.TaskSpec spec, Failure failure) {}

  /**
   * 派任务的请求。
   *
   * @param takeoverStation 接班站站码；为空时从第一个停车的车站接班
   * @param takeoverStopSequence 接班站的停靠序号；-1 时取该站码的第一次停靠
   * @param handoverStation 交班站站码；为空时到终点站
   */
  record Request(
      UUID timetableId,
      String tripCode,
      LocalDate serviceDate,
      Optional<String> takeoverStation,
      int takeoverStopSequence,
      Optional<String> handoverStation,
      boolean depotPickup,
      String source,
      Map<String, String> metadata,
      boolean rewards) {}

  private TaskSpecResolver() {}

  static Result resolve(FetaruteTCAddon plugin, Request request) {
    Optional<TimetableService> timetables = plugin.getTimetableService();
    Optional<TimetableService.TripPlan> plan =
        timetables.flatMap(
            service ->
                service.tripPlan(request.timetableId(), request.tripCode(), request.serviceDate()));
    if (plan.isEmpty()) {
      return new Result(null, Failure.UNAVAILABLE);
    }
    List<TaskStations.Stop> stops = new ArrayList<>();
    for (TimetableService.PlannedStop stop : plan.get().stops()) {
      stops.add(new TaskStations.Stop(stop.stopSequence(), stop.stationCode(), stop.stops()));
    }
    Optional<TaskStations.Resolved> resolved =
        TaskStations.resolve(
            stops,
            request.takeoverStation(),
            request.takeoverStopSequence(),
            request.handoverStation());
    if (resolved.isEmpty()) {
      return new Result(null, Failure.INVALID_STATIONS);
    }
    TimetableService.PlannedStop takeover =
        plannedStop(plan.get(), resolved.get().takeover().sequence());
    if (takeover == null || takeover.departure().isEmpty()) {
      return new Result(null, Failure.UNAVAILABLE);
    }
    boolean cancelled =
        timetables
            .flatMap(
                service ->
                    service.cancellationOf(
                        request.timetableId(), plan.get().tripId(), request.serviceDate()))
            .filter(cancellation -> cancellation.covers(takeover.stopSequence()))
            .isPresent();
    if (cancelled) {
      return new Result(null, Failure.UNAVAILABLE);
    }
    TaskKey key = new TaskKey(request.timetableId(), plan.get().tripCode(), request.serviceDate());
    Optional<TimetableApi.TrainAssignment> assignment = assignmentOf(plugin, key);
    if (assignment
        .flatMap(TimetableApi.TrainAssignment::lastStopSequence)
        .filter(last -> last > takeover.stopSequence())
        .isPresent()) {
      return new Result(null, Failure.DEPARTED);
    }
    if (assignment.isEmpty()
        && Instant.now().isAfter(takeover.departure().get().plus(DriverTaskManager.EXPIRE_AFTER))) {
      // 还没对上列车、计划发车又早已过去：这一班已经跑完或不会来了，派出去也会立即作废。
      return new Result(null, Failure.DEPARTED);
    }
    Optional<TimetableService.PlannedStop> handover =
        resolved
            .get()
            .handover()
            .map(stop -> plannedStop(plan.get(), stop.sequence()))
            .filter(Objects::nonNull);
    StationName takeoverName = stationName(plugin, takeover);
    Optional<StationName> handoverName = handover.map(stop -> stationName(plugin, stop));
    return new Result(
        new DriverTaskManager.TaskSpec(
            key,
            plan.get().routeCode(),
            takeoverName.operatorCode(),
            takeoverName.code(),
            takeoverName.name(),
            takeover.nodeId().orElse(null),
            takeover.stopSequence(),
            takeover.departure().get(),
            assignment.map(TimetableApi.TrainAssignment::trainName).orElse(null),
            handover.map(TimetableService.PlannedStop::stopSequence).orElse(-1),
            handoverName.map(StationName::code).orElse(""),
            handoverName.map(StationName::name).orElse(""),
            request.depotPickup(),
            request.source(),
            request.metadata(),
            request.rewards()),
        null);
  }

  /** 担当这一班的列车绑定：驾驶模块在时先按原始绑定对上再换算这一列（不把全网列车都算一遍），否则查整份列表。 */
  private static Optional<TimetableApi.TrainAssignment> assignmentOf(
      FetaruteTCAddon plugin, TaskKey key) {
    org.fetarute.fetaruteTCAddon.drive.session.DriveSessionManager drive =
        plugin.getDriveSessionManager();
    if (drive != null) {
      return drive.tasks().assignmentOf(key);
    }
    return DriverTaskManager.timetables()
        .flatMap(
            api ->
                api.listAssignments().stream()
                    .filter(
                        candidate ->
                            key.matches(
                                candidate.timetableId(),
                                candidate.tripCode(),
                                candidate.serviceDate()))
                    .findFirst());
  }

  private static TimetableService.PlannedStop plannedStop(
      TimetableService.TripPlan plan, int sequence) {
    for (TimetableService.PlannedStop stop : plan.stops()) {
      if (stop.stopSequence() == sequence) {
        return stop;
      }
    }
    return null;
  }

  /** 车站的运营商、站码与站名。 */
  private record StationName(String operatorCode, String code, String name) {}

  private static StationName stationName(
      FetaruteTCAddon plugin, TimetableService.PlannedStop stop) {
    String code = stop.stationCode().orElse("");
    String operator =
        stop.nodeId()
            .flatMap(RouteTerminals::stationIdentityOfNode)
            .map(RouteTerminals.StationRef::operatorCode)
            .orElse("");
    String name =
        plugin
            .getStationDirectory()
            .flatMap(directory -> directory.snapshot().findStation(operator, code))
            .map(StationDirectory.StationEntry::name)
            .orElse(code);
    return new StationName(operator, code, name);
  }
}
