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
}
