package org.fetarute.fetaruteTCAddon.dispatcher.graph.query;

import java.util.Objects;
import java.util.OptionalDouble;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;

/** {@link RailEdgeCostModel} 的常用实现集合。 */
public final class RailEdgeCostModels {

  private RailEdgeCostModels() {}

  /**
   * 进路规划用的代价：区间长度再加每条区间 1 格。
   *
   * <p>建图从道岔端点量区间长度，每段短区间都会少算不到 1 格；剪刀渡线的"斜线—菱形中心—斜线"由三段短区间串成，少算的累加起来， 穿菱形就可能比实车走的直股还短 1～2
   * 格，规划进路于是穿菱形，凭空和对面股道的进路冲突。每条区间补 1 格抵掉这份少算，差得不多时直股至少打平，再由等长平局规则（区间少者优先）选回直股。 只影响选哪条路，路径长度仍按
   * {@link RailEdge#lengthBlocks()} 合计。
   */
  public static RailEdgeCostModel lengthPlusIntervalBlocks() {
    return (graph, edge, from, to) -> {
      Objects.requireNonNull(edge, "edge");
      int length = edge.lengthBlocks();
      if (length <= 0) {
        return OptionalDouble.empty();
      }
      return OptionalDouble.of(length + 1.0);
    };
  }

  /**
   * 叠加过滤：当边被封锁时返回不可通行。
   *
   * <p>注意：该过滤依赖 {@link RailGraph#isBlocked} 的运行时状态。
   */
  public static RailEdgeCostModel rejectBlocked(RailEdgeCostModel delegate) {
    Objects.requireNonNull(delegate, "delegate");
    return (graph, edge, from, to) -> {
      if (graph != null && edge != null && graph.isBlocked(edge.id())) {
        return OptionalDouble.empty();
      }
      return delegate.cost(graph, edge, from, to);
    };
  }
}
