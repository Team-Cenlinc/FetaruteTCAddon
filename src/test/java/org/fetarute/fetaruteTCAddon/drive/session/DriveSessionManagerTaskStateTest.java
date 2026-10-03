package org.fetarute.fetaruteTCAddon.drive.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.fetarute.fetaruteTCAddon.drive.driver.task.DriverTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("驾驶结束原因对应的任务终态")
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
  }
}
