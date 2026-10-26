package org.fetarute.fetaruteTCAddon.web.gtfs;

import java.util.Optional;

/**
 * 静态 GTFS 的数据源：给出一次生成所需的完整快照。
 *
 * <p>约定：{@link #snapshot()} 可能被 web 线程调用，实现只能读内存快照，不能读库、不能碰服务器主线程对象（Bukkit 世界、实体、方块）。服务还没起来或
 * 车站目录为空时返回空，接口回 {@code 503} 而不是空包。
 */
@FunctionalInterface
public interface GtfsSource {

  /**
   * 取一次快照。
   *
   * @return 快照；服务未就绪时为空
   */
  Optional<GtfsSnapshot> snapshot();
}
