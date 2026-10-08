package org.fetarute.fetaruteTCAddon.drive.inventory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.events.seat.MemberBeforeSeatExitEvent;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.plugin.Plugin;
import org.fetarute.fetaruteTCAddon.drive.session.DriveSessionManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("防误离座的事件接线")
class DriveListenerSeatExitTest {

  private final DriveSessionManager manager = mock(DriveSessionManager.class);
  private final DriveListener listener = new DriveListener(mock(Plugin.class), manager);
  private final Player player = mock(Player.class);

  private MemberBeforeSeatExitEvent seatExit(boolean playerInitiated, boolean seatChange) {
    MemberBeforeSeatExitEvent event = mock(MemberBeforeSeatExitEvent.class);
    when(event.isPlayerInitiated()).thenReturn(playerInitiated);
    when(event.isSeatChange()).thenReturn(seatChange);
    when(event.getEntity()).thenReturn(player);
    return event;
  }

  @Test
  @DisplayName("玩家自己离座且判定不放行时取消；换座位、插件挪人不问判定")
  void seatExitFilter() {
    when(manager.allowSeatExit(player)).thenReturn(false);
    MemberBeforeSeatExitEvent blocked = seatExit(true, false);
    listener.onSeatExit(blocked);
    verify(blocked).setCancelled(true);

    MemberBeforeSeatExitEvent change = seatExit(true, true);
    listener.onSeatExit(change);
    verify(change, never()).setCancelled(anyBoolean());

    MemberBeforeSeatExitEvent programmatic = seatExit(false, false);
    listener.onSeatExit(programmatic);
    verify(programmatic, never()).setCancelled(anyBoolean());
    verify(manager, times(1)).allowSeatExit(any());
  }

  @Test
  @DisplayName("原版下车事件：可取消且判定不放行时取消，判定放行或不可取消时不动")
  void dismountFallback() {
    EntityDismountEvent event = new EntityDismountEvent(player, mock(Entity.class));
    when(manager.allowDismount(player)).thenReturn(false);
    listener.onDismount(event);
    org.junit.jupiter.api.Assertions.assertTrue(event.isCancelled());

    EntityDismountEvent allowed = new EntityDismountEvent(player, mock(Entity.class));
    when(manager.allowDismount(player)).thenReturn(true);
    listener.onDismount(allowed);
    org.junit.jupiter.api.Assertions.assertFalse(allowed.isCancelled());

    EntityDismountEvent forced = new EntityDismountEvent(player, mock(Entity.class), false);
    when(manager.allowDismount(player)).thenReturn(false);
    listener.onDismount(forced);
    org.junit.jupiter.api.Assertions.assertFalse(forced.isCancelled());
  }
}
