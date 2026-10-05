package org.fetarute.fetaruteTCAddon.dispatcher.graph.sync;

import java.util.Optional;
import org.bukkit.World;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;

/** 节点是否有交路在用：决定节点牌子增删后旧图能否继续用。 */
@FunctionalInterface
public interface GraphNodeUsage {

  /**
   * @return 用途说明；没有在用时为空。判不了（数据没就绪等）必须返回非空，让旧图按在用处理。
   */
  Optional<String> findUse(World world, SignNodeDefinition definition);

  /** 未接入判定时一律视为在用：保持节点变更即移出旧图的原行为。 */
  static GraphNodeUsage alwaysInUse() {
    return (world, definition) -> Optional.of("未接入交路判定");
  }
}
