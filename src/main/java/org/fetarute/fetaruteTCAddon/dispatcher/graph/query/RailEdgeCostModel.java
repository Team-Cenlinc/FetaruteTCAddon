package org.fetarute.fetaruteTCAddon.dispatcher.graph.query;

import java.util.OptionalDouble;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * 区间代价模型：用于计算从一个节点穿越某条边到相邻节点的权重。
 *
 * <p>设计目的：为后续“按时间最短 / 按距离最短 / 按限速/封锁权重”的路径规划预留扩展点。
 *
 * <p>注意：本接口仅描述“代价”，不强制代价单位；命令侧可将其解释为 blocks、meters 或 milliseconds。
 */
public interface RailEdgeCostModel {

  /**
   * 返回从 {@code from} 走到 {@code to} 的代价。
   *
   * @return {@link OptionalDouble#empty()} 表示该方向不可通行（例如单向、封锁、维护策略禁止等）
   */
  OptionalDouble cost(RailGraph graph, RailEdge edge, NodeId from, NodeId to);
}
