package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import com.bergerkiller.bukkit.tc.events.SignChangeActionEvent;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SignActionTest {

    /**
     * 验证 Waypoint 牌子建造后被注册，供 TC 路由查询 destination。
     */
    @Test
    void waypointRegistersDestination() {
        SignNodeRegistry registry = new SignNodeRegistry();
        WaypointSignAction action = new WaypointSignAction(registry);
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
        assertTrue(action.getRailDestinationName(queryEvent).equals("SURN:PTK:GPT:1:00"));
    }

    /**
     * 验证销毁牌子会清理注册表条目。
     */
    @Test
    void destroyRemovesRegistryEntry() {
        SignNodeRegistry registry = new SignNodeRegistry();
        DepotSignAction action = new DepotSignAction(registry);
        Block block = mockBlock();

        SignChangeActionEvent buildEvent = mock(SignChangeActionEvent.class);
        when(buildEvent.isTrainSign()).thenReturn(true);
        when(buildEvent.isCartSign()).thenReturn(true);
        when(buildEvent.getLine(2)).thenReturn("SURN:D:LVT:1:00");
        when(buildEvent.getBlock()).thenReturn(block);
        action.build(buildEvent);
        assertTrue(registry.get(block).isPresent());

        SignActionEvent destroyEvent = mock(SignActionEvent.class);
        when(destroyEvent.getBlock()).thenReturn(block);
        action.destroy(destroyEvent);
        assertFalse(registry.get(block).isPresent());
    }

    /**
     * AutoStation 仅接受站咽喉（第二段 S），否则忽略。
     */
    @Test
    void autoStationRejectsInterval() {
        SignNodeRegistry registry = new SignNodeRegistry();
        AutoStationSignAction action = new AutoStationSignAction(registry);
        Block block = mockBlock();

        SignChangeActionEvent buildEvent = mock(SignChangeActionEvent.class);
        when(buildEvent.isTrainSign()).thenReturn(true);
        when(buildEvent.isCartSign()).thenReturn(true);
        when(buildEvent.getLine(2)).thenReturn("SURN:PTK:GPT:1:00");
        when(buildEvent.getBlock()).thenReturn(block);

        action.build(buildEvent);
        assertTrue(registry.get(block).isEmpty());
    }

    private Block mockBlock() {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        Block block = mock(Block.class);
        when(block.getLocation()).thenReturn(new Location(world, 1, 64, 2));
        return block;
    }
}
