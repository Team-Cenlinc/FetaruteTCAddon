package org.fetarute.fetaruteTCAddon.dispatcher.sign;

import org.bukkit.block.Block;

/** 建牌/拆牌时对 rail_nodes 做增量同步的钩子接口。 */
public interface SignNodeStorageSynchronizer {

  /** 写入或更新一个节点定义（通常在建牌/改牌时触发）。 */
  default void upsert(Block block, SignNodeDefinition definition) {}

  /** 删除一个节点定义（通常在拆牌/破坏时触发）。 */
  default void delete(Block block, SignNodeDefinition definition) {}

  static SignNodeStorageSynchronizer noop() {
    return new SignNodeStorageSynchronizer() {};
  }
}
