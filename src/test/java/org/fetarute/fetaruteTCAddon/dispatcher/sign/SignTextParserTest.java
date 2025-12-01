package org.fetarute.fetaruteTCAddon.dispatcher.sign;

import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignTextParserTest {

    @Test
    // 验证标准区间编码解析出的节点/元数据字段
    void parseIntervalWaypoint() {
        Optional<SignNodeDefinition> parsed = SignTextParser.parseWaypointLike("SURN:PTK:GPT:1:00", NodeType.WAYPOINT);
        assertTrue(parsed.isPresent());
        SignNodeDefinition definition = parsed.get();
        assertEquals("SURN:PTK:GPT:1:00", definition.nodeId().value());
        assertEquals(NodeType.WAYPOINT, definition.nodeType());
        assertEquals(Optional.of("SURN:PTK:GPT:1:00"), definition.trainCartsDestination());
        assertEquals(WaypointKind.INTERVAL, definition.waypointMetadata().orElseThrow().kind());
        assertEquals("PTK", definition.waypointMetadata().orElseThrow().originStation());
        assertEquals(Optional.of("GPT"), definition.waypointMetadata().orElseThrow().destinationStation());
        assertEquals(1, definition.waypointMetadata().orElseThrow().trackNumber());
        assertEquals("00", definition.waypointMetadata().orElseThrow().sequence());
    }

    @Test
    // 验证站咽喉编码（第二段 S）解析为 STATION_THROAT
    void parseStationThroat() {
        Optional<SignNodeDefinition> parsed = SignTextParser.parseWaypointLike("SURN:S:PTK:3:10", NodeType.STATION);
        assertTrue(parsed.isPresent());
        SignNodeDefinition definition = parsed.get();
        assertEquals(NodeType.STATION, definition.nodeType());
        assertEquals(WaypointKind.STATION_THROAT, definition.waypointMetadata().orElseThrow().kind());
        assertEquals("PTK", definition.waypointMetadata().orElseThrow().originStation());
        assertEquals(Optional.empty(), definition.waypointMetadata().orElseThrow().destinationStation());
        assertEquals(3, definition.waypointMetadata().orElseThrow().trackNumber());
        assertEquals("10", definition.waypointMetadata().orElseThrow().sequence());
    }

    @Test
    // 验证 Depot throat 编码（第二段 D）解析为 DEPOT
    void parseDepotThroat() {
        Optional<SignNodeDefinition> parsed = SignTextParser.parseWaypointLike("SURN:D:LVT:2:05", NodeType.DEPOT);
        assertTrue(parsed.isPresent());
        SignNodeDefinition definition = parsed.get();
        assertEquals(NodeType.DEPOT, definition.nodeType());
        assertEquals(WaypointKind.DEPOT, definition.waypointMetadata().orElseThrow().kind());
        assertEquals("LVT", definition.waypointMetadata().orElseThrow().originStation());
        assertEquals(Optional.empty(), definition.waypointMetadata().orElseThrow().destinationStation());
    }

    @Test
    // 段数不为 5 的输入应被拒绝
    void rejectInvalidSegments() {
        assertTrue(SignTextParser.parseWaypointLike("SURN:PTK:GPT:1", NodeType.WAYPOINT).isEmpty());
        assertTrue(SignTextParser.parseWaypointLike("SURN:PTK:GPT:1:00:EXTRA", NodeType.WAYPOINT).isEmpty());
    }

    @Test
    // 非数字轨道号应被拒绝
    void rejectInvalidTrack() {
        assertTrue(SignTextParser.parseWaypointLike("SURN:PTK:GPT:x:00", NodeType.WAYPOINT).isEmpty());
    }

    @Test
    // 空串或 null 不应产生解析结果
    void rejectBlank() {
        assertTrue(SignTextParser.parseWaypointLike("   ", NodeType.WAYPOINT).isEmpty());
        assertTrue(SignTextParser.parseWaypointLike(null, NodeType.WAYPOINT).isEmpty());
    }
}
