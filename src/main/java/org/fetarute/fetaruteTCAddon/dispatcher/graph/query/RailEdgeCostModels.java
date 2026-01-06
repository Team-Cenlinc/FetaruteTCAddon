package org.fetarute.fetaruteTCAddon.dispatcher.graph.query;

import java.util.Objects;
import java.util.OptionalDouble;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;

/** {@link RailEdgeCostModel} 的常用实现集合。 */
public final class RailEdgeCostModels {

  private RailEdgeCostModels() {}

  /** 基于区间长度（blocks）的最短路：代价为 {@link RailEdge#lengthBlocks()}。 */
  public static RailEdgeCostModel lengthBlocks() {
    return (graph, edge, from, to) -> {
      Objects.requireNonNull(edge, "edge");
      int length = edge.lengthBlocks();
      if (length <= 0) {
        return OptionalDouble.empty();
      }
      return OptionalDouble.of(length);
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
