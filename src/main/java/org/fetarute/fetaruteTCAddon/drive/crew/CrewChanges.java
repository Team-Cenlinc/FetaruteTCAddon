package org.fetarute.fetaruteTCAddon.drive.crew;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskKey;

/**
 * 乘务员（驾驶员、车掌）在哪一站上下车：信号员越站不能越过这些站。
 *
 * <ul>
 *   <li>上车：领了任务、在接班站等这一班的人。列车按车次绑定认，车还没到时任务上没有车名。
 *   <li>下车：正在值乘、到交班站结束的人（区间任务）。开到终点站的任务没有交班站，终点站本来就不能越。
 * </ul>
 *
 * <p>本类不依赖服务器对象，便于单测。
 */
public final class CrewChanges {

  private CrewChanges() {}

  /**
   * 一名乘务员的一段值乘。
   *
   * @param trip 等的车次；正在值乘时为空
   * @param trainName 在值乘的列车名；还在等车时为空串
   * @param operatorCode 运营商代码
   * @param stationCode 等车时为接班站，值乘中为交班站；没有时为空串
   */
  public record Duty(
      Optional<TaskKey> trip, String trainName, String operatorCode, String stationCode) {
    public Duty {
      trip = trip == null ? Optional.empty() : trip;
      trainName = trainName == null ? "" : trainName;
      operatorCode = operatorCode == null ? "" : operatorCode;
      stationCode = stationCode == null ? "" : stationCode;
    }

    /** 领了任务、在接班站等这一班。 */
    public static Duty waiting(TaskKey trip, String operatorCode, String takeoverStation) {
      return new Duty(
          Optional.of(Objects.requireNonNull(trip, "trip")), "", operatorCode, takeoverStation);
    }

    /** 正在值乘，到交班站结束；开到终点站的任务交班站为空串。 */
    public static Duty onBoard(String trainName, String operatorCode, String handoverStation) {
      return new Duty(Optional.empty(), trainName, operatorCode, handoverStation);
    }
  }

  /**
   * 这列车在这一站有没有乘务员上下车。
   *
   * @param duties 未结束的值乘
   * @param trip 列车当前绑定的车次；没有绑定时为空
   * @param trainName 列车名
   * @param operatorCode 车站所属运营商
   * @param stationCode 站码
   */
  public static boolean at(
      List<Duty> duties,
      Optional<TaskKey> trip,
      String trainName,
      String operatorCode,
      String stationCode) {
    for (Duty duty : duties) {
      if (duty.stationCode().isEmpty()
          || !duty.stationCode().equalsIgnoreCase(stationCode)
          || !duty.operatorCode().equalsIgnoreCase(operatorCode)) {
        continue;
      }
      boolean ours =
          duty.trip().isPresent()
              ? trip.isPresent() && duty.trip().get().equals(trip.get())
              : duty.trainName().equalsIgnoreCase(trainName);
      if (ours) {
        return true;
      }
    }
    return false;
  }
}
