package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import java.util.Objects;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * 用于标记两个节点之间的区间。为了保证无向图的稳定性，内部会自动排序端点。
 *
 * <p><b>自然序</b>：先比 {@link #a()}，再比 {@link #b()}，均按 {@link NodeId} 的自然序。对 {@link #undirected} 规范化后的
 * id（{@code a <= b}），某节点的全部邻接区间按此序排列，等价于按"另一端点"的 {@link NodeId} 自然序排列。
 */
public record EdgeId(NodeId a, NodeId b) implements Comparable<EdgeId> {

  public EdgeId {
    Objects.requireNonNull(a, "a");
    Objects.requireNonNull(b, "b");
    if (a.equals(b)) {
      throw new IllegalArgumentException("Edge 端点不能相同: " + a);
    }
  }

  /** 构建一个方向无关的 EdgeId，自动对端点排序以便在 Map 中共用键。 */
  public static EdgeId undirected(NodeId first, NodeId second) {
    if (first.value().compareTo(second.value()) <= 0) {
      return new EdgeId(first, second);
    }
    return new EdgeId(second, first);
  }

  /** 先比 {@link #a()} 再比 {@link #b()}；与 {@link #equals(Object)} 一致。 */
  @Override
  public int compareTo(EdgeId other) {
    int byA = a.compareTo(other.a);
    return byA != 0 ? byA : b.compareTo(other.b);
  }
}
