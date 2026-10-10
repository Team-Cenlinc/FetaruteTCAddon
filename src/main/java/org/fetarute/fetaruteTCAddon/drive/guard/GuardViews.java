package org.fetarute.fetaruteTCAddon.drive.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.fetarute.fetaruteTCAddon.api.drive.DriveApi;
import org.fetarute.fetaruteTCAddon.api.drive.GuardApi;
import org.fetarute.fetaruteTCAddon.drive.driver.score.ScoreRules;

/** 车掌的内部对象换成公开 API 的快照。本类不依赖服务器对象，便于单测。 */
public final class GuardViews {

  /** 一公里多少格。 */
  private static final double BLOCKS_PER_KM = 1000.0;

  private GuardViews() {}

  /** 任务快照。 */
  public static GuardApi.TaskView of(GuardTask task) {
    return new GuardApi.TaskView(
        task.taskId(),
        task.playerId(),
        task.key().timetableId(),
        task.key().tripCode(),
        task.key().serviceDate(),
        task.routeCode(),
        new DriveApi.StationRef(
            task.stationCode(), task.stationName(), task.takeoverStopSequence()),
        task.handoverStopSequence() >= 0
            ? Optional.of(
                new DriveApi.StationRef(
                    task.handoverStationCode(),
                    task.handoverStationName(),
                    task.handoverStopSequence()))
            : Optional.empty(),
        task.plannedDeparture(),
        GuardApi.TaskState.valueOf(task.state().name()),
        Optional.ofNullable(task.trainName()).filter(name -> !name.isBlank()),
        task.source(),
        task.metadata(),
        task.endReason(),
        task.points() >= 0 ? OptionalInt.of(task.points()) : OptionalInt.empty(),
        Optional.of(task.grade())
            .filter(grade -> !grade.isBlank() && !ScoreRules.UNGRADED.equals(grade)));
  }

  /** 一站的作业。 */
  public static GuardApi.StopWork work(GuardScore.Stop stop) {
    return new GuardApi.StopWork(
        stop.station(),
        stop.forcedOpen(),
        stop.forcedClose(),
        stop.forcedSignal(),
        stop.wrongDoor(),
        stop.closedEarly(),
        stop.closingWatch(),
        stop.departureWatch(),
        stop.incidents());
  }

  /** 一趟的成绩。 */
  public static GuardApi.TripScore score(
      GuardScore score, ScoreRules.Result result, double blocks) {
    List<GuardApi.StopWork> stops = new ArrayList<>();
    for (GuardScore.Stop stop : score.stops()) {
      stops.add(work(stop));
    }
    return new GuardApi.TripScore(
        result.points(), result.grade().name(), stops, Math.max(0.0, blocks) / BLOCKS_PER_KM);
  }

  /** 异常报告原因换成公开的写法。 */
  public static GuardApi.Incident incident(GuardSessionManager.IncidentReason reason) {
    return GuardApi.Incident.valueOf(reason.name());
  }
}
