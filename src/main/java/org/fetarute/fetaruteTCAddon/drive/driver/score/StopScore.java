package org.fetarute.fetaruteTCAddon.drive.driver.score;

import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopAlignment;

/**
 * 一次停站的表现。
 *
 * @param station 站名
 * @param offsetBlocks 停妥时列车中心相对停车点的偏移；量不出时为 {@code NaN}
 * @param outcome 停车结果
 * @param wrongDoor 开过非站台侧的门
 * @param doorsTakenOver 迟迟不开门，由站台代开
 */
public record StopScore(
    String station,
    double offsetBlocks,
    StopAlignment.Outcome outcome,
    boolean wrongDoor,
    boolean doorsTakenOver) {

  /** 从结束的停站取数；越站记为越站；没停妥（如进站途中交还）时为 {@code null}。 */
  public static StopScore of(DriverStationStop stop) {
    if (stop.skipped()) {
      return new StopScore(
          stop.stationName(), stop.offsetBlocks(), StopAlignment.Outcome.SKIPPED, false, false);
    }
    if (!stop.stopped()) {
      return null;
    }
    double offset = stop.stoppedOffsetBlocks();
    return new StopScore(
        stop.stationName(),
        offset,
        stop.window().classify(offset),
        stop.wrongDoorOpened(),
        stop.doorsTakenOver());
  }
}
