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
 * @param transitBlocks 过门按多少格计（保留字段；路网里连接边一律按 1 格）
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

  /** 过门按 1 格计（TrainCarts 过门是瞬时的；路网里连接边的长度见 RailNetwork）。 */
  public static final double DEFAULT_TRANSIT_BLOCKS = 1.0;

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
