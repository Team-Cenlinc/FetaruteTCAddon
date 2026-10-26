package org.fetarute.fetaruteTCAddon.signaller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import com.bergerkiller.bukkit.tc.actions.Action;
import com.bergerkiller.bukkit.tc.actions.WaitAction;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 取消车次时判车停稳：不在动、动作队列里只有等待与人工驾驶的驾驶动作。 */
class PluginTrainDeskTest {

  /** TrainCarts 的等待动作：WaitAction 是接口，排在队列里的是实现它的 Action。 */
  private static Action waiting() {
    return mock(Action.class, withSettings().extraInterfaces(WaitAction.class));
  }

  @Test
  void aTrainIsSettledWhenStillAndOnlyWaiting() {
    assertTrue(PluginTrainDesk.settled(false, List.of()));
    assertTrue(PluginTrainDesk.settled(false, List.of(waiting())));
    assertFalse(PluginTrainDesk.settled(true, List.of()), "在动");
    assertFalse(PluginTrainDesk.settled(false, List.of(mock(Action.class))), "排着别的动作，车还要动");
  }

  /** 人工驾驶的车停在站台上：驾驶动作一直挂着，也算停稳（驾驶动作怎么认见 DriveSessionManager#isDriveAction）。 */
  @Test
  void theManualDriveActionDoesNotCountAsMoving() {
    Action drive = mock(Action.class);

    assertTrue(PluginTrainDesk.settled(false, List.of(drive), action -> action == drive));
    assertFalse(PluginTrainDesk.settled(false, List.of(drive), action -> false));
  }
}
