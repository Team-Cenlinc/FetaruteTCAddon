package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.time.Instant;
import java.util.Optional;

/**
 * 发车计划源：回答“这辆车在这个停靠点应该几点开”。
 *
 * <p>时刻表是目前唯一的实现。调度层只消费返回值，不关心计划从哪来，也不负责判断计划是否合理。
 *
 * <p>契约里有三条是安全边界，实现必须遵守：
 *
 * <ul>
 *   <li>返回空表示“本车此刻不受计划约束”。任何不确定的情况都必须返回空而不是返回一个猜的时间—— 返回空只会退回现状（按信号发车），返回错误时间会把车扣在站里。
 *   <li>返回值必须与传入的 {@code now} 出自同一时钟。调度层会直接比较二者，混用系统时钟与注入时钟会让扣留时长变成随机数。
 *   <li>实现不得阻塞：该方法在出站门控路径上被调用，每辆停站列车每秒一次。
 * </ul>
 */
@FunctionalInterface
public interface ScheduledDeparturePlan {

  /**
   * 查询计划发车时间。
   *
   * @param event 当前停靠点的事实描述，{@code at} 为调度层当前时间
   * @return 计划发车时间；不受计划约束时返回空
   */
  Optional<Instant> scheduledDepartureAt(StationStopEvent event);
}
