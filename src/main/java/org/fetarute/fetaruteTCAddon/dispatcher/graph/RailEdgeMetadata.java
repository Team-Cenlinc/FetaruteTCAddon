package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;

import java.util.Optional;

/**
 * 区间两端节点的补充信息。由于图是无向的，端点没有“起点/终点”之分。
 * 每个端点都可能附带 waypoint 编码，也可能是非 waypoint 节点（如 Destination）。
 */
public record RailEdgeMetadata(
        Optional<WaypointMetadata> endpointA,
        Optional<WaypointMetadata> endpointB
) {
}
