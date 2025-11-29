package org.fetarute.fetaruteTCAddon.dispatcher.sign;

import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;

import java.util.Optional;

/**
 * 解析 Waypoint/站台类牌子的文本编码，统一处理 S/D 保留段与轨道号校验。
 */
public final class SignTextParser {

    private static final int EXPECTED_SEGMENTS = 5;

    private SignTextParser() {
    }

    /**
     * 解析形如 Operator:From:To:Track:Seq 的编码，兼容站咽喉与 Depot throat。
     * 校验字段数量与轨道号合法性，严格返回 Optional 以避免空指针。
     *
     * @param rawId    牌子填写的节点 ID
     * @param nodeType 解析后写入的节点类型
     * @return 成功则返回节点定义，失败返回 Optional.empty()
     */
    public static Optional<SignNodeDefinition> parseWaypointLike(String rawId, NodeType nodeType) {
        if (rawId == null) {
            return Optional.empty();
        }
        String trimmed = rawId.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        String[] segments = trimmed.split(":");
        if (segments.length != EXPECTED_SEGMENTS) {
            return Optional.empty();
        }

        String operator = segments[0].trim();
        String second = segments[1].trim();
        String third = segments[2].trim();
        String trackSegment = segments[3].trim();
        String sequence = segments[4].trim();
        if (operator.isEmpty() || second.isEmpty() || third.isEmpty() || sequence.isEmpty()) {
            return Optional.empty();
        }

        int trackNumber;
        try {
            trackNumber = Integer.parseInt(trackSegment);
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }

        WaypointMetadata waypointMetadata;
        if ("S".equalsIgnoreCase(second)) {
            waypointMetadata = WaypointMetadata.throat(operator, third, trackNumber, sequence);
        } else if ("D".equalsIgnoreCase(second)) {
            waypointMetadata = WaypointMetadata.depot(operator, third, trackNumber, sequence);
        } else {
            waypointMetadata = WaypointMetadata.interval(operator, second, third, trackNumber, sequence);
        }

        return Optional.of(new SignNodeDefinition(
                NodeId.of(trimmed),
                nodeType,
                Optional.of(trimmed),
                Optional.of(waypointMetadata)
        ));
    }
}
