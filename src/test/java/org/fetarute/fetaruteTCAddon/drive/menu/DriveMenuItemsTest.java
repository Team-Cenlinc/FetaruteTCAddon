package org.fetarute.fetaruteTCAddon.drive.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.bukkit.Material;
import org.fetarute.fetaruteTCAddon.drive.setup.PowerSupply;
import org.junit.jupiter.api.Test;

class DriveMenuItemsTest {

  @Test
  void reverserButtonsKeepTheirOwnDyeWhetherSelectedOrNot() {
    assertEquals(
        Material.LIME_DYE,
        DriveMenuItems.materialOf(ButtonView.simple(MenuAction.REVERSER_FORWARD, false)));
    assertEquals(
        Material.LIME_DYE,
        DriveMenuItems.materialOf(ButtonView.simple(MenuAction.REVERSER_FORWARD, true)));
    assertEquals(
        Material.GRAY_DYE,
        DriveMenuItems.materialOf(ButtonView.simple(MenuAction.REVERSER_NEUTRAL, true)));
    assertEquals(
        Material.LIGHT_BLUE_DYE,
        DriveMenuItems.materialOf(ButtonView.simple(MenuAction.REVERSER_REVERSE, false)));
  }

  @Test
  void doorButtonsAreGreenWhenOpenAndGreyWhenClosed() {
    assertEquals(
        Material.LIME_DYE,
        DriveMenuItems.materialOf(ButtonView.simple(MenuAction.DOOR_LEFT, true)));
    assertEquals(
        Material.GRAY_DYE,
        DriveMenuItems.materialOf(ButtonView.simple(MenuAction.DOOR_LEFT, false)));
    assertEquals(
        Material.LIME_DYE,
        DriveMenuItems.materialOf(ButtonView.simple(MenuAction.DOOR_RIGHT, true)));
    assertEquals(
        Material.GRAY_DYE,
        DriveMenuItems.materialOf(ButtonView.simple(MenuAction.DOOR_RIGHT, false)));
  }

  @Test
  void doorNamesDependOnTheStateAndTheSide() {
    assertEquals(
        "drive.menu.item.door-left-open",
        DriveMenuItems.nameKey(ButtonView.simple(MenuAction.DOOR_LEFT, true)));
    assertEquals(
        "drive.menu.item.door-left-closed",
        DriveMenuItems.nameKey(ButtonView.simple(MenuAction.DOOR_LEFT, false)));
    assertEquals(
        "drive.menu.item.door-right-open",
        DriveMenuItems.nameKey(ButtonView.simple(MenuAction.DOOR_RIGHT, true)));
    assertNotEquals(
        DriveMenuItems.nameKey(ButtonView.simple(MenuAction.DOOR_LEFT, true)),
        DriveMenuItems.nameKey(ButtonView.simple(MenuAction.DOOR_RIGHT, true)));
  }

  @Test
  void reverserNamesDoNotChangeWithTheSelection() {
    assertEquals(
        DriveMenuItems.nameKey(ButtonView.simple(MenuAction.REVERSER_FORWARD, true)),
        DriveMenuItems.nameKey(ButtonView.simple(MenuAction.REVERSER_FORWARD, false)));
    assertEquals(
        "drive.menu.item.reverser-reverse",
        DriveMenuItems.nameKey(ButtonView.simple(MenuAction.REVERSER_REVERSE, false)));
  }

  private static ButtonView setup(MenuAction action, boolean on, boolean busy, boolean clickable) {
    return new ButtonView(action, on, busy, PowerSupply.PTG6, busy ? 3 : -1, clickable);
  }

  @Test
  void startUpSwitchesAreGreyOffYellowWhileStartingAndGreenOn() {
    assertEquals(
        Material.GRAY_DYE, DriveMenuItems.materialOf(setup(MenuAction.POWER, false, false, true)));
    assertEquals(
        Material.YELLOW_DYE, DriveMenuItems.materialOf(setup(MenuAction.POWER, false, true, true)));
    assertEquals(
        Material.LIME_DYE, DriveMenuItems.materialOf(setup(MenuAction.BREAKER, true, false, true)));
    assertEquals(
        Material.RED_DYE, DriveMenuItems.materialOf(setup(MenuAction.START, false, false, true)));
  }

  @Test
  void thePowerSwitchIsNamedAfterTheSupply() {
    assertEquals(
        "drive.menu.item.ptg6-busy",
        DriveMenuItems.nameKey(setup(MenuAction.POWER, false, true, true)));
    assertEquals(
        "drive.menu.item.engine-on",
        DriveMenuItems.nameKey(
            new ButtonView(MenuAction.POWER, true, false, PowerSupply.DIESEL, -1, true)));
    assertEquals(
        "drive.menu.item.start-off",
        DriveMenuItems.nameKey(setup(MenuAction.START, false, false, true)));
  }

  @Test
  void switchesThatCannotBeClickedSayToUseTheStartButton() {
    assertEquals(
        "drive.menu.hint.setup-lamp",
        DriveMenuItems.hintKey(setup(MenuAction.KEY, false, false, false)));
    assertEquals(
        "drive.menu.hint.setup-switch",
        DriveMenuItems.hintKey(setup(MenuAction.KEY, false, false, true)));
  }
}
