package org.fetarute.fetaruteTCAddon.web.gtfs.realtime;

import java.time.Instant;
import java.util.Optional;

/**
 * GTFS-Realtime 的数据源：给出某一刻的运行时事实快照。
 *
 * <p>约定：{@link #snapshot(Instant)} 可能被 web 线程调用，实现只能读内存快照与只读查询，不能读库、不能碰服务器主线程对象。还没起来时返回空，接口回 503
 * 而不是一版时间戳比数据还新的空报文。
 */
@FunctionalInterface
public interface GtfsRealtimeSource {

  /**
   * @param now 这一次生成所用的时刻
   * @return 快照；运行时服务未就绪时为空
   */
  Optional<GtfsRealtimeSnapshot> snapshot(Instant now);
}
