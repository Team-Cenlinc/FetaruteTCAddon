package org.fetarute.fetaruteTCAddon.web.gtfs;

import java.util.Optional;
import java.util.UUID;

/**
 * 编表时排定的计划股道：某趟车在某个停靠点的股道节点 ID。
 *
 * <p>动态站台的列车进站前才选台，静态包只能用编表时排定的股道；没有排上时退回车站级的 {@code …:DYN} 停靠点。读取方在 web 线程调用，实现必须只读内存快照（不能读库）。
 */
@FunctionalInterface
public interface GtfsPlatformPlans {

  /**
   * @param tripId 车次 UUID
   * @param stopSequence 停靠序号（与交路路径点下标同一口径）
   * @return 计划股道节点 ID；不是动态站台、编表时没有空闲股道或计划表不可读时为空
   */
  Optional<String> plannedPlatform(UUID tripId, int stopSequence);

  /** 没有计划股道（时刻表未启用、测试）：一律为空。 */
  static GtfsPlatformPlans none() {
    return (tripId, stopSequence) -> Optional.empty();
  }
}
