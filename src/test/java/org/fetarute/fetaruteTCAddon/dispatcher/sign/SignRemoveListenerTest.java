package org.fetarute.fetaruteTCAddon.dispatcher.sign;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.junit.jupiter.api.Test;

class SignRemoveListenerTest {

  @Test
  void doesNotRemoveRegistryWhenBreakCancelled() {
    SignNodeRegistry registry = new SignNodeRegistry();
    LocaleManager locale = mock(LocaleManager.class);
    SignRemoveListener listener = new SignRemoveListener(registry, locale);
    Block block = mockBlock();

    registry.put(
        block,
        new SignNodeDefinition(
            NodeId.of("SURN:PTK:GPT:1:00"),
            NodeType.WAYPOINT,
            Optional.of("dest"),
            Optional.empty()));

    BlockBreakEvent event = mock(BlockBreakEvent.class);
    when(event.isCancelled()).thenReturn(true);
    when(event.getBlock()).thenReturn(block);

    listener.onBlockBreak(event);

    assertTrue(registry.get(block).isPresent());
    verify(locale, never()).component("sign.removed", Map.of("node", "SURN:PTK:GPT:1:00"));
  }

  @Test
  void removesRegistryAndNotifiesPlayerWhenBreakAllowed() {
    SignNodeRegistry registry = new SignNodeRegistry();
    LocaleManager locale = mock(LocaleManager.class);
    SignRemoveListener listener = new SignRemoveListener(registry, locale);
    Block block = mockBlock();

    registry.put(
        block,
        new SignNodeDefinition(
            NodeId.of("SURN:PTK:GPT:1:00"),
            NodeType.WAYPOINT,
            Optional.of("dest"),
            Optional.empty()));

    Player player = mock(Player.class);
    BlockBreakEvent event = mock(BlockBreakEvent.class);
    when(event.isCancelled()).thenReturn(false);
    when(event.getBlock()).thenReturn(block);
    when(event.getPlayer()).thenReturn(player);
    when(locale.component("sign.removed", Map.of("node", "SURN:PTK:GPT:1:00")))
        .thenReturn(Component.text("removed"));

    listener.onBlockBreak(event);

    assertTrue(registry.get(block).isEmpty());
    verify(player).sendMessage(Component.text("removed"));
  }

  private Block mockBlock() {
    World world = mock(World.class);
    when(world.getUID()).thenReturn(UUID.randomUUID());
    when(world.getName()).thenReturn("world");

    Location location = mock(Location.class);
    when(location.getWorld()).thenReturn(world);
    when(location.getBlockX()).thenReturn(1);
    when(location.getBlockY()).thenReturn(64);
    when(location.getBlockZ()).thenReturn(2);

    Block block = mock(Block.class);
    when(block.getLocation()).thenReturn(location);
    return block;
  }
}
