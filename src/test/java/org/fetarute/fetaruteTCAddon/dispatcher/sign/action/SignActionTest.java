package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import com.bergerkiller.bukkit.tc.events.SignChangeActionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
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

  private Block mockBlock() {
    World world = mock(World.class);
    when(world.getUID()).thenReturn(UUID.randomUUID());
    when(world.getName()).thenReturn("world");
    var location = mock(Location.class);
    when(location.getWorld()).thenReturn(world);
    when(location.getBlockX()).thenReturn(1);
    when(location.getBlockY()).thenReturn(64);
    when(location.getBlockZ()).thenReturn(2);

    Block block = mock(Block.class);
    when(block.getLocation()).thenReturn(location);
    return block;
  }
}
