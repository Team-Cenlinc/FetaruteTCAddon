package org.fetarute.fetaruteTCAddon.drive.driver.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.fetarute.fetaruteTCAddon.api.drive.DriveApi;
import org.fetarute.fetaruteTCAddon.drive.driver.DrivingMode;
import org.fetarute.fetaruteTCAddon.drive.driver.score.StopScore;
import org.fetarute.fetaruteTCAddon.drive.driver.score.TaskScore;

/** 驾驶任务的内部对象换成公开 API 的快照。本类不依赖服务器对象，便于单测。 */
public final class TaskViews {

  private TaskViews() {}

  /** 任务快照。 */
  public static DriveApi.TaskView of(DriverTask task) {
    return new DriveApi.TaskView(
        task.taskId(),
        task.playerId(),
        task.key().timetableId(),
        task.key().tripCode(),
        task.key().serviceDate(),
        task.routeCode(),
        new DriveApi.StationRef(task.stationCode(), task.stationName(), task.boardStopSequence()),
        task.alightStopSequence() >= 0
            ? Optional.of(
                new DriveApi.StationRef(
                    task.alightStationCode(), task.alightStationName(), task.alightStopSequence()))
            : Optional.empty(),
        task.plannedDeparture(),
        mode(task.mode()),
        DriveApi.TaskState.valueOf(task.state().name()),
        Optional.ofNullable(task.trainName()).filter(name -> !name.isBlank()),
        task.depotPickup(),
        task.source(),
        task.metadata(),
        task.endReason(),
        task.points() >= 0 ? OptionalInt.of(task.points()) : OptionalInt.empty(),
        Optional.of(task.grade()).filter(grade -> !grade.isBlank()));
  }

  public static DriveApi.Mode mode(DrivingMode mode) {
    return mode == DrivingMode.ATO ? DriveApi.Mode.ATO : DriveApi.Mode.MANUAL;
  }

  public static DrivingMode mode(DriveApi.Mode mode) {
    return mode == DriveApi.Mode.ATO ? DrivingMode.ATO : DrivingMode.MANUAL;
  }

  /** 一站的成绩。 */
  public static DriveApi.StopResult stop(StopScore stop) {
    return new DriveApi.StopResult(
        stop.station(),
        stop.offsetBlocks(),
        DriveApi.StopWindow.valueOf(stop.window().name()),
        stop.wrongDoor(),
        stop.doorsTakenOver());
  }

  /** 任务成绩。 */
  public static DriveApi.TaskScore score(TaskScore score, int points, String grade) {
    List<DriveApi.StopResult> stops = new ArrayList<>();
    for (StopScore stop : score.stops()) {
      stops.add(stop(stop));
    }
    return new DriveApi.TaskScore(
        points,
        grade,
        stops,
        score.serviceInterventions(),
        score.emergencyInterventions(),
        score.forcedStops(),
        score.signalMisses(),
        score.delayGainedSeconds());
  }
}
