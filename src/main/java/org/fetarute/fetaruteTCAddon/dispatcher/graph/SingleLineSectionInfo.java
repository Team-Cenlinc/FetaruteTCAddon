package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import java.util.List;
import java.util.Objects;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * 单线 section 信息：表示两个桥链边界之间的一整段方向性单线区。
 *
 * <p>section 是叠加在既有 corridor 微段之上的占用粒度。既有 {@link RailGraphConflictIndex} 仍负责提供微段 {@code
 * single:<component>:<endA>~<endB>}；section 用于在进入单线前持有更粗的方向 token，避免对向列车从两端各自进入不同微段。
 *
 * @param key section 级冲突资源 key；桥链使用固定 {@code bridge} 命名空间与归一化边界端点，避免图快照新增旁支节点时因 componentKey 漂移而换
 *     key
 * @param left 归一化参考端点；用于稳定 key 与旧图方向回退，不等同于业务方向 A
 * @param right 归一化参考端点；用于稳定 key 与旧图方向回退，不等同于业务方向 B
 * @param nodes 从 left 到 right 的完整桥链节点
 * @param boundaries 桥链两端的会让点、分支或线路端点
 * @param corridorKeys section 覆盖的既有 corridor 微段 key
 * @param directional 是否可尝试解析方向；桥链 section 当前恒为 true，保留字段用于接口兼容
 */
public record SingleLineSectionInfo(
    String key,
    NodeId left,
    NodeId right,
    List<NodeId> nodes,
    List<NodeId> boundaries,
    List<String> corridorKeys,
    boolean directional) {

  public SingleLineSectionInfo {
    Objects.requireNonNull(key, "key");
    nodes = nodes == null ? List.of() : List.copyOf(nodes);
    boundaries = boundaries == null ? List.of() : List.copyOf(boundaries);
    corridorKeys = corridorKeys == null ? List.of() : List.copyOf(corridorKeys);
  }
}
