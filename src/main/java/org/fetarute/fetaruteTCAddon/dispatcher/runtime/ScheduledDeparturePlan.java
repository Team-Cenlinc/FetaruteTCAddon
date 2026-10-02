package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

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

  /**
   * 只读：列车<b>已绑定</b>的车次在这个停靠点的计划发车时刻，供晚点车压缩停站。
   *
   * <p>与 {@link #scheduledDepartureAt} 不同，它不建立、不解除绑定：到站那一刻就要算停站，而车次匹配仍只在出站门控上做。 列车没绑车次、或绑定的车次不在
   * {@code event} 的交路上时必须返回空——空只会让本站按计划停站。
   *
   * @param event 当前停靠点的事实描述
   * @return 计划发车时刻；无从判断时为空
   */
  default Optional<Instant> boundDepartureAt(StationStopEvent event) {
    return Optional.empty();
  }

  /**
   * 只读：列车在当前车次上最近一次到站或发车相对计划的偏差（秒，正数为晚点），供晚点追赶判断要不要放宽线路限速。
   *
   * <p>控车每个信号 tick 都会问，实现只能查内存。没绑车次、或本车次还没有到发记录时为空。
   *
   * @param trainName 列车名（大小写不敏感）
   * @return 最近一次偏差
   */
  default OptionalLong currentDelaySeconds(String trainName) {
    return OptionalLong.empty();
  }

  /**
   * 只读：列车在当前交路某个停靠点的计划站台（时刻表排定的股道），DYNAMIC 选台在空闲候选里优先选它。
   *
   * <p>选台每个信号 tick 都可能问，实现只能查内存。没有计划时为空——空只会照常按进站方向选台。
   *
   * @param trainName 列车名（大小写不敏感）
   * @param routeId 列车当前交路
   * @param stopIndex 停靠序号
   * @return 计划股道节点
   */
  default Optional<String> plannedPlatformOf(String trainName, UUID routeId, int stopIndex) {
    return Optional.empty();
  }
}
