package org.fetarute.fetaruteTCAddon.dispatcher.graph.sync;

import java.util.Objects;
import org.bukkit.World;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;

/**
 * 节点牌子增删使调度图失效或恢复时的回调。
 *
 * <p>失效时该世界的内存图快照已被移除，调度在重建前拿不到图；由插件层决定如何告知控制台与管理员。回调在主线程触发。
 */
public interface GraphStaleListener {

  /**
   * 一次节点变更后图处于失效状态。
   *
   * @param world 所在世界
   * @param change 引起失效的节点变更
   * @param wasStale 变更前该世界是否已经失效
   */
  void onStale(World world, NodeChange change, boolean wasStale);

  /** 变更后节点签名与快照重新一致，图已从存储重新载入。 */
  void onRecovered(World world);

  static GraphStaleListener noop() {
    return new GraphStaleListener() {
      @Override
      public void onStale(World world, NodeChange change, boolean wasStale) {}

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
   */
  record NodeChange(SignNodeDefinition definition, int x, int y, int z, boolean removed) {
    public NodeChange {
      Objects.requireNonNull(definition, "definition");
    }
  }
}
