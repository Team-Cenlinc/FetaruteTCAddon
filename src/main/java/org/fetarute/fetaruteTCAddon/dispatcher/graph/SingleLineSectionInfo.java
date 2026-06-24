package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import java.util.List;
import java.util.Objects;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * 单线 section 信息：表示两个可会让边界之间的一整段单线互斥区。
 *
 * <p>section 是叠加在既有 corridor 微段之上的占用粒度。既有 {@link RailGraphConflictIndex} 仍负责提供微段 {@code
 * single:<component>:<endA>~<endB>}；section 用于在进入单线前持有更粗的方向 token，避免对向列车从两端各自进入不同微段。
 *
 * @param key section 级冲突资源 key
 * @param left 归一化参考端点；用于稳定 key 与旧图方向回退，不等同于业务方向 A
 * @param right 归一化参考端点；用于稳定 key 与旧图方向回退，不等同于业务方向 B
 * @param nodes 从 left 到 right 的参考路径节点；复杂分叉 section 可能只保存主轴路径
 * @param boundaries section 内可识别的会让/端点边界
 * @param corridorKeys section 覆盖的既有 corridor 微段 key
 * @param directional 是否可尝试解析方向；优先使用站间语义轴，缺少元数据时才使用参考端点回退
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
