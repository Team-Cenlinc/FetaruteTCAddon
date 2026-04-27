package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bergerkiller.bukkit.tc.signactions.SignActionType;
import org.junit.jupiter.api.Test;

class RuntimeDispatchListenerTest {

  @Test
  void shouldAdvancePassStationOnTrainGroupEnter() {
    assertTrue(
        RuntimeDispatchListener.shouldHandleStationPassProgress(SignActionType.GROUP_ENTER, false));
  }

  @Test
  void shouldAdvancePassStationOnCartHeadMemberEnter() {
    assertTrue(
        RuntimeDispatchListener.shouldHandleStationPassProgress(SignActionType.MEMBER_ENTER, true));
  }

  @Test
  void shouldNotAdvanceStationOnTrainMemberEnter() {
    assertFalse(
        RuntimeDispatchListener.shouldHandleStationPassProgress(
            SignActionType.MEMBER_ENTER, false));
  }
}
