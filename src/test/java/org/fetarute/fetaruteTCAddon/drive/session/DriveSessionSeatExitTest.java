package org.fetarute.fetaruteTCAddon.drive.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;
import org.fetarute.fetaruteTCAddon.drive.DriveConfig;
import org.fetarute.fetaruteTCAddon.drive.cab.CabSystems;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverLink;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveMode;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveParams;
import org.fetarute.fetaruteTCAddon.drive.dynamics.Notch;
import org.fetarute.fetaruteTCAddon.drive.seat.SeatBinding;
import org.fetarute.fetaruteTCAddon.drive.setup.PowerSupply;
import org.fetarute.fetaruteTCAddon.drive.setup.SetupTimings;
import org.fetarute.fetaruteTCAddon.drive.setup.TrainSetup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("驾驶会话里的潜行键与离座判定")
class DriveSessionSeatExitTest {

  private static DriveSession session() {
    List<ItemStack> hotbar = new ArrayList<>();
    for (int i = 0; i < Notch.SLOT_COUNT; i++) {
      hotbar.add(mock(ItemStack.class));
    }
    return new DriveSession(
        UUID.randomUUID(),
        "Steve",
        new SeatBinding("T1", 0, 0),
        new DriveParams(DriveMode.MU, 1.0, 1.0, 22.0, 0.5),
        DriveConfig.defaults(),
        hotbar,
        new TrainSetup(PowerSupply.PTG5, SetupTimings.defaults()),
        CabSystems.disabled());
  }

  @Test
  @DisplayName("输入事件只在潜行键重新按下时记一次潜行，按住期间别的键变化不算")
  void sneakInputRecordsPressEdgesOnly() {
    DriveSession session = session();
    assertFalse(session.sneakEdges());
    session.noteSneakInput(true, 100);
    session.noteSneakInput(true, 130);
    assertEquals(100, session.lastSneakTick(), "按住不放不重复记");
    assertTrue(session.sneakHeld());
    assertTrue(session.sneakEdges());
    session.noteSneakInput(false, 140);
    assertFalse(session.sneakHeld());
    session.noteSneakInput(true, 150);
    assertEquals(150, session.lastSneakTick());
  }

  @Test
  @DisplayName("按键后掉出座位算主动离座；这次按键的离座请求被拦下时不算，按住等到放行时算")
  void blockedPressDoesNotCountAsLeaving() {
    int window = DriveConfig.defaults().exitSneakWindowTicks();
    DriveSession pressed = session();
    pressed.noteSneakInput(true, 100);
    assertTrue(pressed.sneakedRecently(101));
    assertFalse(pressed.sneakedRecently(100 + window + 1));

    DriveSession blocked = session();
    blocked.noteSneakInput(true, 100);
    blocked.noteExitBlocked(100);
    assertFalse(blocked.sneakedRecently(101), "被拦下的这次按键之后掉出座位：送回座位");

    blocked.noteExitAllowed(200);
    assertTrue(blocked.sneakedRecently(201), "按住等到停车后放行");
    blocked.noteSneakInput(false, 300);
    blocked.noteSneakInput(true, 310);
    assertTrue(blocked.sneakedRecently(311), "之后新的一次按键照常算");
  }

  @Test
  @DisplayName("只有驾驶调度列车时离座才会放弃任务；非调度列车上领着的车次不受影响")
  void onlyDispatchSessionsAbandonTasks() {
    DriveSession free = session();
    assertFalse(DriveSessionManager.exitAbandonsTask(free, true));
    DriveSession dispatch = session();
    dispatch.attachDriverLink(new DriverLink(UUID.randomUUID(), "T1", null, () -> 0.0, () -> 0L));
    assertTrue(DriveSessionManager.exitAbandonsTask(dispatch, true));
    assertFalse(DriveSessionManager.exitAbandonsTask(dispatch, false));
  }

  @Test
  @DisplayName("终点站离座引导：等接续下一趟或列车在待命、且没有开着的一趟时才引导")
  void walkIsAllowedOnlyWhileWaitingForTheNextTrip() {
    assertTrue(DriveSessionManager.walkAllowed(false, true, false), "结算后等接续");
    assertTrue(DriveSessionManager.walkAllowed(false, false, true), "接管待命车（含按间隔发车的线路）");
    assertFalse(DriveSessionManager.walkAllowed(true, true, true), "还开着一趟：先结算");
    assertFalse(DriveSessionManager.walkAllowed(false, false, false));
  }

  @Test
  @DisplayName("刚被直接送进驾驶室的几个 tick 内不当成走远了")
  void cabMoveSettleWindow() {
    DriveSession session = session();
    assertFalse(session.cabMovedRecently(100));
    session.noteCabMove(100);
    assertTrue(session.cabMovedRecently(100));
    assertTrue(session.cabMovedRecently(105));
    assertFalse(session.cabMovedRecently(106));
  }
}
