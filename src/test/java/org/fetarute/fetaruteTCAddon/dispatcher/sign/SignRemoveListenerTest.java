package org.fetarute.fetaruteTCAddon.dispatcher.sign;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.WallSign;
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
    SignRemoveListener listener = new SignRemoveListener(registry, locale, message -> {});
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
    verify(locale, never()).component(eq("sign.removed"), anyMap());
  }

  @Test
  void removesRegistryAndNotifiesPlayerWhenBreakAllowed() {
    SignNodeRegistry registry = new SignNodeRegistry();
    LocaleManager locale = mock(LocaleManager.class);
    SignRemoveListener listener = new SignRemoveListener(registry, locale, message -> {});
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
    when(locale.component(eq("sign.type.waypoint"))).thenReturn(Component.text("区间点"));
    when(locale.component(eq("sign.removed"), anyMap())).thenReturn(Component.text("removed"));

    listener.onBlockBreak(event);

    assertTrue(registry.get(block).isEmpty());
    verify(player).sendMessage(Component.text("removed"));
  }

  @Test
  void removesAttachedNodeSignWhenSupportBlockBroken() {
    SignNodeRegistry registry = new SignNodeRegistry();
    LocaleManager locale = mock(LocaleManager.class);
    SignRemoveListener listener = new SignRemoveListener(registry, locale, message -> {});

    World world = mockWorld();
    Block supportBlock = mockBlockAt(world, 0, 64, 0);
    Block signBlock = mockBlockAt(world, 1, 64, 0);

    // supportBlock 右侧有一个 wall sign，且该 sign 依附于 supportBlock
    when(supportBlock.getRelative(BlockFace.EAST)).thenReturn(signBlock);
    BlockFace[] neighborFaces =
        new BlockFace[] {
          BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST
        };
    for (BlockFace face : neighborFaces) {
      Block other = mock(Block.class);
      when(other.getBlockData()).thenReturn(mock(BlockData.class));
      when(supportBlock.getRelative(face)).thenReturn(other);
    }

    WallSign wallSign = mock(WallSign.class);
    when(wallSign.getFacing()).thenReturn(BlockFace.EAST);
    when(signBlock.getBlockData()).thenReturn(wallSign);
    when(signBlock.getRelative(BlockFace.WEST)).thenReturn(supportBlock);

    registry.put(
        signBlock,
        new SignNodeDefinition(
            NodeId.of("SURN:PTK:GPT:1:00"),
            NodeType.WAYPOINT,
            Optional.of("dest"),
            Optional.empty()));

    Player player = mock(Player.class);
    BlockBreakEvent event = mock(BlockBreakEvent.class);
    when(event.isCancelled()).thenReturn(false);
    when(event.getBlock()).thenReturn(supportBlock);
    when(event.getPlayer()).thenReturn(player);
    when(locale.component(eq("sign.type.waypoint"))).thenReturn(Component.text("区间点"));
    when(locale.component(eq("sign.removed"), anyMap())).thenReturn(Component.text("removed"));

    listener.onBlockBreak(event);

    assertTrue(registry.get(signBlock).isEmpty());
    verify(player, times(1)).sendMessage(Component.text("removed"));
  }

  private Block mockBlock() {
    return mockBlockAt(mockWorld(), 1, 64, 2);
  }

  private World mockWorld() {
    World world = mock(World.class);
    when(world.getUID()).thenReturn(UUID.randomUUID());
    when(world.getName()).thenReturn("world");
    return world;
  }

  private Block mockBlockAt(World world, int x, int y, int z) {
    Location location = mock(Location.class);
    when(location.getWorld()).thenReturn(world);
    when(location.getBlockX()).thenReturn(x);
    when(location.getBlockY()).thenReturn(y);
    when(location.getBlockZ()).thenReturn(z);

    Block block = mock(Block.class);
    when(block.getLocation()).thenReturn(location);
    when(block.getWorld()).thenReturn(world);
    return block;
  }
}
