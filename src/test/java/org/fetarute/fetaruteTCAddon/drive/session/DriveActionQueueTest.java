package org.fetarute.fetaruteTCAddon.drive.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import com.bergerkiller.bukkit.tc.actions.Action;
import com.bergerkiller.bukkit.tc.actions.WaitAction;
import org.junit.jupiter.api.Test;

/** 人工驾驶期间驾驶动作一直挂在编组的动作队列里：别的模块判“车停稳了没有”时要认得它，不能当成车要动。 */
class DriveActionQueueTest {

  @Test
  void theManualDriveActionIsRecognisedAndOthersAreNot() {
    assertTrue(DriveSessionManager.isDriveAction(mock(ManualDriveAction.class)));
    assertFalse(
        DriveSessionManager.isDriveAction(
            mock(Action.class, withSettings().extraInterfaces(WaitAction.class))));
    assertFalse(DriveSessionManager.isDriveAction(mock(Action.class)));
    assertFalse(DriveSessionManager.isDriveAction(null));
  }
}
