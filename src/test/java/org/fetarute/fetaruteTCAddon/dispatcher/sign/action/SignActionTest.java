package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import com.bergerkiller.bukkit.tc.events.SignChangeActionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeStorageSynchronizer;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignTextParser;
import org.junit.jupiter.api.Test;

class SignActionTest {

  /** 验证 Waypoint 牌子建造后被注册，并向 TC 报告 destination。 */
  @Test
  void waypointReportsDestination() {
    SignNodeRegistry registry = new SignNodeRegistry();
    WaypointSignAction action = new WaypointSignAction(registry, message -> {}, null);
    Block block = mockBlock();

    SignChangeActionEvent buildEvent = mock(SignChangeActionEvent.class);
    when(buildEvent.isTrainSign()).thenReturn(true);
    when(buildEvent.isCartSign()).thenReturn(true);
    when(buildEvent.getLine(2)).thenReturn("SURN:PTK:GPT:1:00");
    when(buildEvent.getBlock()).thenReturn(block);

    assertTrue(action.build(buildEvent));
    assertTrue(registry.get(block).isPresent());

    SignActionEvent queryEvent = mock(SignActionEvent.class);
    when(queryEvent.getBlock()).thenReturn(block);
    when(queryEvent.getLine(2)).thenReturn("SURN:PTK:GPT:1:00");
    assertEquals("SURN:PTK:GPT:1:00", action.getRailDestinationName(queryEvent));
  }

  /** 验证销毁牌子会清理注册表条目。 */
  @Test
  void destroyRemovesRegistryEntry() {
    SignNodeRegistry registry = new SignNodeRegistry();
    DepotSignAction action = new DepotSignAction(registry, message -> {}, null);
    Block block = mockBlock();

    SignChangeActionEvent buildEvent = mock(SignChangeActionEvent.class);
    when(buildEvent.isTrainSign()).thenReturn(true);
    when(buildEvent.isCartSign()).thenReturn(true);
    when(buildEvent.getLine(2)).thenReturn("SURN:D:LVT:1");
    when(buildEvent.getBlock()).thenReturn(block);
    action.build(buildEvent);
    assertTrue(registry.get(block).isPresent());

    SignActionEvent destroyEvent = mock(SignActionEvent.class);
    when(destroyEvent.getBlock()).thenReturn(block);
    action.destroy(destroyEvent);
    assertFalse(registry.get(block).isPresent());
  }

  /** AutoStation 不接受区间点（Waypoint interval），否则会与图节点职责混淆。 */
  @Test
  void autoStationRejectsInterval() {
    SignNodeRegistry registry = new SignNodeRegistry();
    AutoStationSignAction action = new AutoStationSignAction(registry, message -> {}, null);
    Block block = mockBlock();

    SignChangeActionEvent buildEvent = mock(SignChangeActionEvent.class);
    when(buildEvent.isTrainSign()).thenReturn(true);
    when(buildEvent.isCartSign()).thenReturn(true);
    when(buildEvent.getLine(2)).thenReturn("SURN:PTK:GPT:1:00");
    when(buildEvent.getBlock()).thenReturn(block);

    action.build(buildEvent);
    assertTrue(registry.get(block).isEmpty());
  }

  /** Waypoint 也应接受站咽喉/车库咽喉（它们属于图节点，而非行为节点）。 */
  @Test
  void waypointAcceptsThroats() {
    SignNodeRegistry registry = new SignNodeRegistry();
    WaypointSignAction action = new WaypointSignAction(registry, message -> {}, null);
    Block block = mockBlock();

    SignChangeActionEvent buildEvent = mock(SignChangeActionEvent.class);
    when(buildEvent.isTrainSign()).thenReturn(true);
    when(buildEvent.isCartSign()).thenReturn(true);
    when(buildEvent.getLine(2)).thenReturn("SURN:S:PTK:1:01");
    when(buildEvent.getBlock()).thenReturn(block);

    assertTrue(action.build(buildEvent));
    assertTrue(registry.get(block).isPresent());
  }

  /** AutoStation 只接受站点（4 段 S:Station:Track），不接受站咽喉（5 段）。 */
  @Test
  void autoStationRejectsStationThroat() {
    SignNodeRegistry registry = new SignNodeRegistry();
    AutoStationSignAction action = new AutoStationSignAction(registry, message -> {}, null);
    Block block = mockBlock();

    SignChangeActionEvent buildEvent = mock(SignChangeActionEvent.class);
    when(buildEvent.isTrainSign()).thenReturn(true);
    when(buildEvent.isCartSign()).thenReturn(true);
    when(buildEvent.getLine(2)).thenReturn("SURN:S:PTK:1:01");
    when(buildEvent.getBlock()).thenReturn(block);

    action.build(buildEvent);
    assertTrue(registry.get(block).isEmpty());
  }

  /** Depot 只接受车库（4 段 D:Depot:Track），不接受车库咽喉（5 段）。 */
  @Test
  void depotRejectsDepotThroat() {
    SignNodeRegistry registry = new SignNodeRegistry();
    DepotSignAction action = new DepotSignAction(registry, message -> {}, null);
    Block block = mockBlock();

    SignChangeActionEvent buildEvent = mock(SignChangeActionEvent.class);
    when(buildEvent.isTrainSign()).thenReturn(true);
    when(buildEvent.isCartSign()).thenReturn(true);
    when(buildEvent.getLine(2)).thenReturn("SURN:D:AAA:1:01");
    when(buildEvent.getBlock()).thenReturn(block);

    action.build(buildEvent);
    assertTrue(registry.get(block).isEmpty());
  }

  /** 调试日志应包含节点坐标，便于排查建牌/销毁。 */
  @Test
  void debugLogsIncludeLocationOnBuildAndDestroy() {
    List<String> logs = new ArrayList<>();
    SignNodeRegistry registry = new SignNodeRegistry(logs::add);
    WaypointSignAction action = new WaypointSignAction(registry, logs::add, null);
    Block block = mockBlock();

    SignChangeActionEvent buildEvent = mock(SignChangeActionEvent.class);
    when(buildEvent.isTrainSign()).thenReturn(true);
    when(buildEvent.isCartSign()).thenReturn(true);
    when(buildEvent.getLine(2)).thenReturn("SURN:PTK:GPT:1:00");
    when(buildEvent.getBlock()).thenReturn(block);
    action.build(buildEvent);

    SignActionEvent destroyEvent = mock(SignActionEvent.class);
    when(destroyEvent.getBlock()).thenReturn(block);
    action.destroy(destroyEvent);

    assertTrue(
        logs.stream()
            .anyMatch(msg -> msg.contains("注册 WAYPOINT 节点") && msg.contains("world (1,64,2)")));
    assertTrue(
        logs.stream()
            .anyMatch(msg -> msg.contains("销毁 WAYPOINT 节点牌子") && msg.contains("world (1,64,2)")));
  }

  /** 同名 NodeId 冲突时应取消建牌，避免世界里留下“未注册但看起来有效”的幽灵牌子。 */
  @Test
  void buildCancelsWhenNodeIdConflicts() {
    SignNodeRegistry registry = new SignNodeRegistry();
    WaypointSignAction action = new WaypointSignAction(registry, message -> {}, null);

    World world = mock(World.class);
    when(world.getUID()).thenReturn(UUID.randomUUID());
    when(world.getName()).thenReturn("world");
    Block blockA = mockBlock(world, 1, 64, 2);
    Block blockB = mockBlock(world, 9, 64, 9);

    SignChangeActionEvent buildA = mock(SignChangeActionEvent.class);
    when(buildA.isTrainSign()).thenReturn(true);
    when(buildA.isCartSign()).thenReturn(true);
    when(buildA.getLine(2)).thenReturn("SURN:PTK:GPT:1:00");
    when(buildA.getBlock()).thenReturn(blockA);
    action.build(buildA);
    assertTrue(registry.get(blockA).isPresent());

    SignChangeActionEvent buildB = mock(SignChangeActionEvent.class);
    when(buildB.isTrainSign()).thenReturn(true);
    when(buildB.isCartSign()).thenReturn(true);
    when(buildB.getLine(2)).thenReturn("SURN:PTK:GPT:1:00");
    when(buildB.getBlock()).thenReturn(blockB);

    action.build(buildB);

    verify(buildB).setCancelled(true);
    assertTrue(registry.get(blockB).isEmpty());
  }

  /** 冲突位置若已不存在节点牌子，应在冲突检查阶段自愈，避免“牌子已拆但仍冲突”。 */
  @Test
  void buildAutoRepairsStaleConflictWhenConflictingBlockMissing() {
    SignNodeRegistry registry = new SignNodeRegistry();
    SignNodeStorageSynchronizer storageSync = mock(SignNodeStorageSynchronizer.class);
    WaypointSignAction action = new WaypointSignAction(registry, message -> {}, null, storageSync);

    World world = mock(World.class);
    when(world.getUID()).thenReturn(UUID.randomUUID());
    when(world.getName()).thenReturn("world");
    when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
    Block defaultBlock = mockBlock(world, 0, 64, 0);
    when(defaultBlock.getState()).thenReturn(mock(BlockState.class));
    when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(defaultBlock);

    Block oldBlock = mockBlock(world, 1, 64, 2);
    SignNodeDefinition oldDef =
        SignTextParser.parseWaypointLike("SURN:PTK:GPT:1:00", NodeType.WAYPOINT).orElseThrow();
    registry.put(oldBlock, oldDef);

    Block staleBlock = mockBlock(world, 1, 64, 2);
    when(world.getBlockAt(1, 64, 2)).thenReturn(staleBlock);
    when(staleBlock.getState()).thenReturn(mock(BlockState.class));

    Block newBlock = mockBlock(world, 9, 64, 9);
    SignChangeActionEvent buildEvent = mock(SignChangeActionEvent.class);
    when(buildEvent.isTrainSign()).thenReturn(true);
    when(buildEvent.isCartSign()).thenReturn(true);
    when(buildEvent.getLine(2)).thenReturn("SURN:PTK:GPT:1:00");
    when(buildEvent.getBlock()).thenReturn(newBlock);

    action.build(buildEvent);

    verify(buildEvent, never()).setCancelled(true);
    assertTrue(registry.get(oldBlock).isEmpty());
    assertTrue(registry.get(newBlock).isPresent());
    verify(storageSync).delete(staleBlock, oldDef);
    verify(storageSync)
        .upsert(
            org.mockito.ArgumentMatchers.eq(newBlock),
            org.mockito.ArgumentMatchers.argThat(
                def ->
                    def != null
                        && def.nodeId().value().equals("SURN:PTK:GPT:1:00")
                        && def.nodeType() == NodeType.WAYPOINT
                        && def.trainCartsDestination().equals(Optional.of("SURN:PTK:GPT:1:00"))));
  }

  private Block mockBlock() {
    World world = mock(World.class);
    when(world.getUID()).thenReturn(UUID.randomUUID());
    when(world.getName()).thenReturn("world");
    return mockBlock(world, 1, 64, 2);
  }

  private Block mockBlock(World world, int x, int y, int z) {
    var location = mock(Location.class);
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
