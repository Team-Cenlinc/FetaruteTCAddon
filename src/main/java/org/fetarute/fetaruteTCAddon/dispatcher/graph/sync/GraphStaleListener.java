package org.fetarute.fetaruteTCAddon.dispatcher.graph.sync;

import java.util.Objects;
import java.util.Optional;
import org.bukkit.World;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;

/**
 * 节点牌子增删使调度图失效或恢复时的回调；由插件层决定如何告知控制台与管理员。回调在主线程触发。
 *
 * <p>失效分两级：变化的节点没有交路在用时只打标记、继续用旧图（{@link Level#RETAINED}）；有交路在用时旧图移出内存， 该世界在重建前拿不到图（{@link
 * Level#EVICTED}）。
 */
public interface GraphStaleListener {

  /** 调度图的失效程度，按严重度递增。 */
  enum Level {
    /** 图与节点牌子一致。 */
    NONE,
    /** 不一致，但变化的节点没有交路在用，旧图继续在用。 */
    RETAINED,
    /** 不一致且旧图已移出内存。 */
    EVICTED
  }

  /**
   * 一次节点变更后图处于失效状态。
   *
   * @param world 所在世界
   * @param change 引起失效的节点变更
   * @param before 变更前的失效程度
   * @param after 变更后的失效程度
   */
  void onStale(World world, NodeChange change, Level before, Level after);

  /** 变更后节点签名与快照重新一致，图已从存储重新载入。 */
  void onRecovered(World world);

  static GraphStaleListener noop() {
    return new GraphStaleListener() {
      @Override
      public void onStale(World world, NodeChange change, Level before, Level after) {}

      @Override
      public void onRecovered(World world) {}
    };
  }

  /**
   * 一次节点牌子变更。
   *
   * @param definition 节点定义
   * @param x 牌子方块 X
   * @param y 牌子方块 Y
   * @param z 牌子方块 Z
   * @param removed true 为移除，false 为新增或改写
   * @param usage 用到该节点的交路（判过才有）
   */
  record NodeChange(
      SignNodeDefinition definition, int x, int y, int z, boolean removed, Optional<String> usage) {
    public NodeChange {
      Objects.requireNonNull(definition, "definition");
      usage = usage == null ? Optional.empty() : usage;
    }

    public NodeChange(SignNodeDefinition definition, int x, int y, int z, boolean removed) {
      this(definition, x, y, z, removed, Optional.empty());
    }

    NodeChange withUsage(Optional<String> found) {
      return new NodeChange(definition, x, y, z, removed, found);
    }
  }
}
