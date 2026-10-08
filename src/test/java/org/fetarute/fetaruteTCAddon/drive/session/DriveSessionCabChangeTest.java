package org.fetarute.fetaruteTCAddon.drive.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;
import org.fetarute.fetaruteTCAddon.drive.DriveConfig;
import org.fetarute.fetaruteTCAddon.drive.driver.CabChange;
import org.fetarute.fetaruteTCAddon.drive.driver.task.DriverTask;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveMode;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveParams;
import org.fetarute.fetaruteTCAddon.drive.dynamics.Notch;
import org.fetarute.fetaruteTCAddon.drive.seat.CabSeats;
import org.fetarute.fetaruteTCAddon.drive.seat.SeatBinding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("驾驶会话在折返换端期间")
class DriveSessionCabChangeTest {

  private static final Instant T0 = Instant.parse("2026-10-04T08:00:00Z");

  private static DriveSession newSession() {
    List<ItemStack> hotbar = new ArrayList<>();
    for (int i = 0; i < Notch.SLOT_COUNT; i++) {
      hotbar.add(mock(ItemStack.class));
    }
    return new DriveSession(
        UUID.randomUUID(),
        "Steve",
        new SeatBinding("T1", 5, 0),
        new DriveParams(DriveMode.MU, 1.0, 1.0, 22.0, 0.5),
        DriveConfig.defaults(),
        hotbar);
  }

  private static CabChange.Input released(CabSeats.End seat, long atSecond) {
    return new CabChange.Input(
        true,
        false,
        CabSeats.Departure.EITHER,
        seat,
        true,
        T0.plusSeconds(atSecond),
        20L,
        null,
        false,
        0L,
        6,
        false);
  }

  @Test
  @DisplayName("计时换端时手柄按自动制动处理，牵引不起作用；完成后恢复手柄档位")
  void handleIsOverriddenWhileChangingEnds() {
    DriveSession session = newSession();
    session.selector().force(Notch.P3);
    assertEquals(Notch.P3, session.notch());

    session.cabChange().tick(released(CabSeats.End.TAIL, 0));
    assertTrue(session.cabChange().holding());
    assertEquals(Notch.B3, session.notch(), "坐在原来那一端也不算在岗");

    session.cabChange().tick(released(CabSeats.End.HEAD, 5));
    assertEquals(Notch.P3, session.notch());
  }

  @Test
  @DisplayName("换端超时按任务未完成收尾")
  void timeoutFailsTheTask() {
    assertEquals(
        DriverTask.State.FAILED,
        DriveSessionManager.taskStateFor(DriveSession.EndReason.CAB_CHANGE_TIMEOUT));
  }
}
