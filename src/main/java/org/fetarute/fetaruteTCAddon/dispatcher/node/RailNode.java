package org.fetarute.fetaruteTCAddon.dispatcher.node;

import org.bukkit.util.Vector;

import java.util.Optional;

/**
 * 轨道节点的公共抽象，封装与世界坐标、TrainCarts waypoint 的映射关系。
 */
public interface RailNode {

    /**
     * @return 调度图内部使用的唯一标识。
     */
    NodeId id();

    /**
     * @return 节点类型，用于选择对应的调度行为。
     */
    NodeType type();

    /**
     * @return 轨道在世界中的粗略坐标
     *（常用于可视化或寻路起点）。
     */
    Vector worldPosition();

    /**
     * @return 可选的 TrainCarts destination / waypoint 名称，便于与 TC 路由桥接。
     */
    Optional<String> trainCartsDestination();

    /**
     * 当节点来源于 Waypoint 形式的编码（如 SURN:PTK:GPT:1:00）时，返回解析后的结构化信息。
     * 非 Waypoint 节点可直接返回 Optional.empty()。
     */
    default Optional<WaypointMetadata> waypointMetadata() {
        return Optional.empty();
    }
}
