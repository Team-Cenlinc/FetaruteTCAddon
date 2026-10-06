package org.fetarute.fetaruteTCAddon.drive.tutorial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.fetarute.fetaruteTCAddon.drive.DriveConfig;
import org.fetarute.fetaruteTCAddon.drive.cab.CabSystems;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveMode;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveParams;
import org.fetarute.fetaruteTCAddon.drive.dynamics.Notch;
import org.fetarute.fetaruteTCAddon.drive.seat.SeatBinding;
import org.fetarute.fetaruteTCAddon.drive.session.DriveSession;
import org.fetarute.fetaruteTCAddon.drive.setup.PowerSupply;
import org.fetarute.fetaruteTCAddon.drive.setup.SetupTimings;
import org.fetarute.fetaruteTCAddon.drive.setup.TrainSetup;
import org.fetarute.fetaruteTCAddon.drive.sound.DriveSounds;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("新手教程：从头开始与本次作废的通知")
class DriveTutorialsTest {

  private final List<DriveTutorials.Forfeit> forfeits = new ArrayList<>();
  private DriveTutorials tutorials;
  private Player player;
  private DriveSession session;

  @BeforeEach
  void setUp() {
    Plugin plugin = mock(Plugin.class);
    when(plugin.namespace()).thenReturn("fetarutetcaddon");
    when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
    LocaleManager locale = mock(LocaleManager.class);
    when(locale.component(anyString())).thenReturn(Component.empty());
    when(locale.component(anyString(), any(TagResolver.class))).thenReturn(Component.empty());
    when(locale.component(anyString(), anyMap())).thenReturn(Component.empty());
    tutorials = new DriveTutorials(plugin, () -> locale, mock(DriveSounds.class));
    tutorials.onForfeit((who, reason) -> forfeits.add(reason));

    player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(UUID.randomUUID());
    when(player.hasPermission(anyString())).thenReturn(true);
    when(player.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));

    // standard 级非调度列车、尚未启动：教程从「按启动按钮」（练习步骤）开始，接着是换向（练习步骤）。
    List<ItemStack> hotbar = new ArrayList<>();
    for (int i = 0; i < Notch.SLOT_COUNT; i++) {
      hotbar.add(mock(ItemStack.class));
    }
    session =
        new DriveSession(
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
  @DisplayName("跳过练习步骤只通知一次；从头开始后重新计")
  void skippingPracticeForfeitsOncePerRun() {
    assertNull(tutorials.start(player, session, 0L));
    tutorials.skip(player, session, 1L);
    tutorials.skip(player, session, 2L);
    assertEquals(List.of(DriveTutorials.Forfeit.SKIPPED_PRACTICE), forfeits);

    assertNull(tutorials.restart(player, session, 3L));
    tutorials.skip(player, session, 4L);
    assertEquals(
        List.of(DriveTutorials.Forfeit.SKIPPED_PRACTICE, DriveTutorials.Forfeit.SKIPPED_PRACTICE),
        forfeits);
  }

  @Test
  @DisplayName("进行中再开始只提示正在进行；从头开始直接重来、不算作废")
  void restartReplacesTheRunningTutorial() {
    tutorials.start(player, session, 0L);
    assertEquals("drive.tutorial.command.already-running", tutorials.start(player, session, 1L));
    assertNull(tutorials.restart(player, session, 2L));
    assertEquals(List.of(), forfeits);
  }

  @Test
  @DisplayName("不在驾驶时从头开始：放弃这一次，下次开始驾驶时开始")
  void restartWithoutSessionArms() {
    tutorials.start(player, session, 0L);
    assertEquals("drive.tutorial.command.armed", tutorials.restart(player, null, 1L));
    assertEquals("drive.tutorial.command.not-running", tutorials.skip(player, session, 2L));
  }

  @Test
  @DisplayName("退出进行中的教程通知一次；已因跳过作废的不再重复，没在进行的不通知")
  void exitingForfeits() {
    tutorials.start(player, session, 0L);
    tutorials.stop(player);
    assertEquals(List.of(DriveTutorials.Forfeit.EXITED), forfeits);

    forfeits.clear();
    tutorials.start(player, session, 1L);
    tutorials.skip(player, session, 2L);
    tutorials.stop(player);
    assertEquals(List.of(DriveTutorials.Forfeit.SKIPPED_PRACTICE), forfeits);

    forfeits.clear();
    tutorials.stop(player);
    assertEquals(List.of(), forfeits);
  }
}
