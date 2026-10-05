package org.fetarute.fetaruteTCAddon.drive.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop;
import org.fetarute.fetaruteTCAddon.drive.driver.task.DriverTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("驾驶结束原因对应的任务终态与终点站结算时机")
class DriveSessionManagerTaskStateTest {

  @Test
  void mapping() {
    assertEquals(
        DriverTask.State.COMPLETED,
        DriveSessionManager.taskStateFor(DriveSession.EndReason.TASK_COMPLETE));
    assertEquals(
        DriverTask.State.FAILED, DriveSessionManager.taskStateFor(DriveSession.EndReason.WATCHDOG));
    assertEquals(
        DriverTask.State.ABANDONED,
        DriveSessionManager.taskStateFor(DriveSession.EndReason.LEFT_SEAT));
    assertEquals(
        DriverTask.State.INTERRUPTED,
        DriveSessionManager.taskStateFor(DriveSession.EndReason.HANDBACK));
    assertEquals(
        DriverTask.State.INTERRUPTED,
        DriveSessionManager.taskStateFor(DriveSession.EndReason.DISPATCH_ABORT));
    assertEquals(
        DriverTask.State.COMPLETED,
        DriveSessionManager.taskStateFor(DriveSession.EndReason.SERVICE_END),
        "开到收车地点正常收车算完成，不算调度收回");
  }

  @Test
  @DisplayName("终点站开门后才结算：等开门、进站不算")
  void settlesOnlyAfterDoorsOpen() {
    assertFalse(DriverStationStop.Phase.APPROACH.doorsOpened());
    assertFalse(DriverStationStop.Phase.OPEN_DOORS.doorsOpened());
    assertTrue(DriverStationStop.Phase.DWELL.doorsOpened());
    assertTrue(DriverStationStop.Phase.CLOSE_DOORS.doorsOpened());
    assertTrue(DriverStationStop.Phase.WAIT_DEPARTURE.doorsOpened());
    assertTrue(DriverStationStop.Phase.DEPART.doorsOpened());
  }
}
