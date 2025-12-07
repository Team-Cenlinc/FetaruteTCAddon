package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import org.bukkit.World;

/** 负责从世界中的 SignAction/轨道标记增量构建 RailGraph。 具体实现需保证 Bukkit API 访问发生在主线程，然后把结果交给异步线程加工。 */
public interface RailGraphBuilder {

  /**
   * 扫描目标世界并生成新的图实例。
   *
   * @param world Minecraft 世界
   * @return 不可变的图快照
   */
  RailGraph build(World world);
}
