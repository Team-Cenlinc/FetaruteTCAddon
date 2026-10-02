package org.fetarute.fetaruteTCAddon.dispatcher.node;

import java.util.Objects;

/**
 * 代表轨道网络中的一个逻辑节点（站台、道岔、车库等）的唯一标识。 推荐遵循 <运营商>:<from>:<to>:<track>:<seq> 的编码方式， 例如
 * SURN:PTK:GPT:1:00、SURN:S:PTK:1（站点）、SURN:D:LVT:1（车库）、SURN:S:PTK:1:00（站咽喉）、SURN:D:LVT:1:00（车库咽喉）。
 *
 * <p><b>自然序</b>：按 {@link #value()} 的 {@link String#compareTo(String)}（UTF-16
 * 码元字典序）。这是调度图唯一的遍历与平局规则——{@code SimpleRailGraph} 的节点/区间遍历顺序与 {@code RailGraphPathFinder}
 * 的等长平局都按它来，与 {@code EdgeId.undirected} 规范化端点用的是同一个比较。它只为跨进程可复现而存在，不表达任何运营偏好（例如"优先 1 道"）。
 */
public record NodeId(String value) implements Comparable<NodeId> {

  public NodeId {
    Objects.requireNonNull(value, "value");
  }

  /** 简化构造入口，便于后续统一校验逻辑。 */
  public static NodeId of(String raw) {
    return new NodeId(raw);
  }

  /** 按 {@link #value()} 的字符串自然序比较；与 {@link #equals(Object)} 一致。 */
  @Override
  public int compareTo(NodeId other) {
    return value.compareTo(other.value);
  }

  @Override
  public String toString() {
    return value;
  }
}
