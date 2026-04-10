package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.util.List;

/** 提供占用队列快照的扩展接口。 */
public interface OccupancyQueueSupport {

  /**
   * 获取当前排队中的资源快照。
   *
   * <p>用于诊断占用方向与排队顺序，不建议在高频路径上调用。
   */
  List<OccupancyQueueSnapshot> snapshotQueues();

  /**
   * 刷新指定请求在冲突队列中的排队位次（不写入占用 claim）。
   *
   * <p>用于“停站等待发车”这类场景：列车仍停在当前位置，但需要提前声明自己对前方冲突区的排队顺序，避免后车在等待期间抢到更靠前的队列位。
   *
   * <p>默认实现为空，便于不支持队列位次预占的实现保持兼容。
   *
   * @param request 需要刷新位次的请求
   */
  default void touchQueues(OccupancyRequest request) {}
}
