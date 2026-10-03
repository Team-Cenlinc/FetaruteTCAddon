package org.fetarute.fetaruteTCAddon.drive.driver.score;

import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopAlignment;

/**
 * 一次停站的表现。
 *
 * @param station 站名
 * @param offsetBlocks 停妥时列车中心相对停车点的偏移；量不出时为 {@code NaN}
 * @param window 停车窗口
 * @param wrongDoor 开过非站台侧的门
 * @param doorsTakenOver 迟迟不开门，由站台代开
 */
public record StopScore(
    String station,
    double offsetBlocks,
    StopAlignment.Window window,
    boolean wrongDoor,
    boolean doorsTakenOver) {

  /** 从结束的停站取数；没停妥（如进站途中交还）时为 {@code null}。 */
  public static StopScore of(DriverStationStop stop) {
    if (!stop.stopped()) {
      return null;
    }
    double offset = stop.stoppedOffsetBlocks();
    return new StopScore(
        stop.stationName(),
        offset,
        StopAlignment.classify(offset),
        stop.wrongDoorOpened(),
        stop.doorsTakenOver());
  }
}
