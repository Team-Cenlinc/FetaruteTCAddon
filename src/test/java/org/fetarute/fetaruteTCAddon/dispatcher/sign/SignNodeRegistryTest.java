package org.fetarute.fetaruteTCAddon.dispatcher.sign;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SignNodeRegistryTest {

    @Test
    // 放入、查询、删除应按坐标键生效
    void putGetRemove() {
        SignNodeRegistry registry = new SignNodeRegistry();
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

        SignNodeDefinition definition = new SignNodeDefinition(
                NodeId.of("SURN:PTK:GPT:1:00"),
                NodeType.WAYPOINT,
                Optional.of("SURN:PTK:GPT:1:00"),
                Optional.of(WaypointMetadata.interval("SURN", "PTK", "GPT", 1, "00"))
        );
        registry.put(block, definition);

        Optional<SignNodeDefinition> fetched = registry.get(block);
        assertTrue(fetched.isPresent());
        assertEquals(definition, fetched.get());

        registry.remove(block);
        assertFalse(registry.get(block).isPresent());
    }

    @Test
    // snapshot 返回不可变视图，防止外部篡改内部状态
    void snapshotIsImmutable() {
        SignNodeRegistry registry = new SignNodeRegistry();
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
        SignNodeDefinition definition = new SignNodeDefinition(
                NodeId.of("SURN:PTK:GPT:1:00"),
                NodeType.WAYPOINT,
                Optional.of("SURN:PTK:GPT:1:00"),
                Optional.of(WaypointMetadata.interval("SURN", "PTK", "GPT", 1, "00"))
        );
        registry.put(block, definition);

        Map<String, SignNodeDefinition> snapshot = registry.snapshot();
        assertEquals(1, snapshot.size());

        // 修改快照不影响原始数据
        assertThrowsUnsupported(snapshot);
    }

    private void assertThrowsUnsupported(Map<String, SignNodeDefinition> snapshot) {
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
    }

    @Test
    // 调试日志应包含节点 ID 与方块位置
    void debugLogsIncludeLocation() {
        List<String> logs = new ArrayList<>();
        SignNodeRegistry registry = new SignNodeRegistry(logs::add);
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
        SignNodeDefinition definition = new SignNodeDefinition(
                NodeId.of("SURN:PTK:GPT:1:00"),
                NodeType.WAYPOINT,
                Optional.of("SURN:PTK:GPT:1:00"),
                Optional.of(WaypointMetadata.interval("SURN", "PTK", "GPT", 1, "00"))
        );

        registry.put(block, definition);
        registry.remove(block);

        assertEquals(2, logs.size());
        assertTrue(logs.get(0).contains("SURN:PTK:GPT:1:00"));
        assertTrue(logs.get(0).contains("world (1,64,2)"));
        assertTrue(logs.get(1).contains("world (1,64,2)"));
    }
}
