package org.fetarute.fetaruteTCAddon.stationmaster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.fetarute.fetaruteTCAddon.drive.inventory.InputSignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/** 站长快捷栏的按键：时刻按客户端时钟读，按住时的重发与同一次右键的连发都只算一下，网络抖动不改变结果。 */
class StationMasterHotbarTest {

  private final UUID id = UUID.randomUUID();
  private final List<StationMasterHotbar.Button> pressed = new ArrayList<>();
  private int menus;
  private long clientMillis = 10_000L;
  private Player player;
  private StationMasterHotbar hotbar;
  private BukkitScheduler scheduler;

  @BeforeEach
  void setUp() {
    player = mock(Player.class);
    PlayerInventory inventory = mock(PlayerInventory.class);
    when(player.getUniqueId()).thenReturn(id);
    when(player.getInventory()).thenReturn(inventory);
    when(inventory.getHeldItemSlot()).thenReturn(StationMasterHotbar.Button.HOLD.slot());
    scheduler = mock(BukkitScheduler.class);
    when(scheduler.runTask(any(Plugin.class), any(Runnable.class)))
        .thenAnswer(
            call -> {
              call.getArgument(1, Runnable.class).run();
              return null;
            });
    hotbar =
        new StationMasterHotbar(
            mock(Plugin.class),
            () -> null,
            (who, button) -> pressed.add(button),
            who -> menus++,
            player -> clientMillis);
  }

  private void input(InputSignal signal) {
    try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
      hotbar.onInput(player, signal);
    }
  }

  @Test
  void holdingTheButtonIsOnePress() {
    input(InputSignal.USE);
    for (int i = 0; i < 10; i++) {
      clientMillis += 200L;
      input(InputSignal.USE);
    }
    assertEquals(1, pressed.size(), "按住时客户端每 4 tick 重发，不算再按");
  }

  @Test
  void theSameClickSentTwiceIsOnePress() {
    input(InputSignal.USE_ENTITY);
    input(InputSignal.USE);
    assertEquals(1, pressed.size(), "读数相同：同一拍里的两个包");
  }

  @Test
  void aSecondClickCountsHoweverThePacketsArrive() {
    input(InputSignal.USE);
    clientMillis += 500L;
    input(InputSignal.USE);
    assertEquals(2, pressed.size(), "客户端上隔了 10 tick，到达时挤在一起也是两下");
  }

  @Test
  void fOpensTheMenu() {
    input(InputSignal.SWAP_HANDS);
    assertEquals(1, menus);
    assertTrue(pressed.isEmpty());
  }

  @Test
  void emptySlotsAllowUsingBlocksButButtonsDoNot() {
    assertTrue(hotbar.allowsBlockUse(3));
    assertFalse(hotbar.allowsBlockUse(StationMasterHotbar.Button.SIGNAL.slot()));
  }
}
