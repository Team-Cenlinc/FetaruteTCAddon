package org.fetarute.fetaruteTCAddon.drive.guard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.bukkit.Material;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop.Phase;
import org.fetarute.fetaruteTCAddon.drive.DriveConfig;
import org.fetarute.fetaruteTCAddon.drive.seat.CabSeats;
import org.junit.jupiter.api.Test;

/** 车掌快捷栏与菜单的贴图键、无材质包时的染料与光效，以及上岗、开门的规则。 */
class GuardButtonsTest {

  private static GuardHotbar.View view(
      boolean left, boolean closing, GuardHotbar.ConfirmLamp lamp, boolean ringing) {
    return new GuardHotbar.View(left, false, closing, false, lamp, ringing, false);
  }

  @Test
  void slotsMatchTheDesign() {
    assertEquals(Optional.of(GuardHotbar.Button.DOOR_LEFT), GuardHotbar.Button.fromSlot(0));
    assertEquals(Optional.of(GuardHotbar.Button.BUZZER), GuardHotbar.Button.fromSlot(6));
    assertEquals(Optional.of(GuardHotbar.Button.EMERGENCY), GuardHotbar.Button.fromSlot(8));
    assertEquals(Optional.empty(), GuardHotbar.Button.fromSlot(4), "5 号格空着");
    assertEquals(Optional.empty(), GuardHotbar.Button.fromSlot(7), "8 号格空着");
  }

  /** 车门沿用驾驶台的车门贴图，其余是车掌自己的贴图。 */
  @Test
  void modelKeysFollowTheState() {
    GuardHotbar.View idle = view(false, false, GuardHotbar.ConfirmLamp.STOP, false);
    GuardHotbar.View busy = view(true, true, GuardHotbar.ConfirmLamp.DONE, true);
    assertEquals(
        "drive/panel/door_l_off", GuardHotbar.modelKey(GuardHotbar.Button.DOOR_LEFT, idle));
    assertEquals("drive/panel/door_l_on", GuardHotbar.modelKey(GuardHotbar.Button.DOOR_LEFT, busy));
    assertEquals(
        "drive/guard/door_close_on", GuardHotbar.modelKey(GuardHotbar.Button.DOOR_CLOSE, busy));
    assertEquals(
        "drive/guard/confirm_stop", GuardHotbar.modelKey(GuardHotbar.Button.CONFIRM, idle));
    assertEquals(
        "drive/guard/confirm_done", GuardHotbar.modelKey(GuardHotbar.Button.CONFIRM, busy));
    assertEquals("drive/guard/buzzer_on", GuardHotbar.modelKey(GuardHotbar.Button.BUZZER, busy));
    assertEquals("drive/guard/emergency", GuardHotbar.modelKey(GuardHotbar.Button.EMERGENCY, idle));
  }

  /** 没装材质包：染料颜色区分，“亮起”加附魔光效。 */
  @Test
  void dyeFallbackAndGlint() {
    GuardHotbar.View idle = view(false, false, GuardHotbar.ConfirmLamp.STOP, false);
    GuardHotbar.View busy = view(true, true, GuardHotbar.ConfirmLamp.DONE, true);
    assertEquals(Material.GRAY_DYE, GuardHotbar.materialOf(GuardHotbar.Button.DOOR_LEFT, idle));
    assertEquals(Material.LIME_DYE, GuardHotbar.materialOf(GuardHotbar.Button.DOOR_LEFT, busy));
    assertEquals(Material.RED_DYE, GuardHotbar.materialOf(GuardHotbar.Button.CONFIRM, idle));
    assertFalse(GuardHotbar.lit(GuardHotbar.Button.BUZZER, idle));
    assertTrue(GuardHotbar.lit(GuardHotbar.Button.BUZZER, busy));
    assertTrue(GuardHotbar.lit(GuardHotbar.Button.CONFIRM, busy));
  }

  @Test
  void menuReusesThePanelTextures() {
    assertEquals(
        "drive/panel/end_confirm",
        GuardMenu.modelKey(GuardMenu.Action.END, new GuardMenu.View(true, false)));
    assertEquals(
        "drive/guard/report_used",
        GuardMenu.modelKey(GuardMenu.Action.REPORT, new GuardMenu.View(false, true)));
    assertEquals(Optional.of(GuardMenu.Action.SEAT), GuardMenu.actionAt(0));
    assertEquals(Optional.empty(), GuardMenu.actionAt(1));
  }

  /** 车掌坐车尾驾驶室；单节车坐另一头的驾驶室即可。 */
  @Test
  void theGuardSitsInTheRearCab() {
    assertTrue(GuardSessionManager.guardCab(CabSeats.End.TAIL, 4));
    assertFalse(GuardSessionManager.guardCab(CabSeats.End.HEAD, 4));
    assertFalse(GuardSessionManager.guardCab(CabSeats.End.NONE, 4));
    assertTrue(GuardSessionManager.guardCab(CabSeats.End.HEAD, 1));
  }

  /** 车掌能开门：停妥以后、放行以前（关门后再开也行）。 */
  @Test
  void doorsOpenOnlyBetweenStoppingAndRelease() {
    assertFalse(GuardSessionManager.doorsReleasable(Phase.APPROACH));
    assertTrue(GuardSessionManager.doorsReleasable(Phase.OPEN_DOORS));
    assertTrue(GuardSessionManager.doorsReleasable(Phase.WAIT_DEPARTURE));
    assertFalse(GuardSessionManager.doorsReleasable(Phase.DEPART));
  }

  @Test
  void spectatorsCannotBeGuards() {
    DriveConfig config = DriveConfig.defaults();
    assertTrue(GuardSessionManager.gameModeAllowed(org.bukkit.GameMode.SURVIVAL, config));
    assertFalse(GuardSessionManager.gameModeAllowed(org.bukkit.GameMode.SPECTATOR, config));
  }
}
