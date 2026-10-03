package org.fetarute.fetaruteTCAddon.dispatcher.graph.portal;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * 一条传送门连接：列车从 {@code from} 门进入，出现在 {@code to} 门。方向性的：反向要另一条连接。
 *
 * @param fromWorld 入口所在世界
 * @param fromNode 入口传送门节点
 * @param toWorld 出口所在世界
 * @param toNode 出口传送门节点
 * @param source 自动解析（MyWorlds）还是手动指定
 * @param transitBlocks 过门按多少格计（ETA、占用、运行时分）
 * @param updatedAt 更新时刻
 */
public record PortalLink(
    UUID fromWorld,
    NodeId fromNode,
    UUID toWorld,
    NodeId toNode,
    Source source,
    double transitBlocks,
    Instant updatedAt) {

  /** 过门默认按 4 格计。 */
  public static final double DEFAULT_TRANSIT_BLOCKS = 4.0;

  /** 连接来源。 */
  public enum Source {
    AUTO,
    MANUAL
  }

  public PortalLink {
    Objects.requireNonNull(fromWorld, "fromWorld");
    Objects.requireNonNull(fromNode, "fromNode");
    Objects.requireNonNull(toWorld, "toWorld");
    Objects.requireNonNull(toNode, "toNode");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (fromNode.equals(toNode)) {
      throw new IllegalArgumentException("传送门不能连到自己");
    }
    transitBlocks =
        Double.isFinite(transitBlocks) && transitBlocks > 0.0
            ? transitBlocks
            : DEFAULT_TRANSIT_BLOCKS;
  }

  /** 跨不跨世界。 */
  public boolean crossWorld() {
    return !fromWorld.equals(toWorld);
  }
}
