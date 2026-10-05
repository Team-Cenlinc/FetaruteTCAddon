package org.fetarute.fetaruteTCAddon.dispatcher.graph.build;

import java.util.Optional;

/** 一个世界上正在进行的调度图构建任务（build / continue / refresh / extend）；同一世界同时只跑一个。 */
public interface RailGraphBuildTask {

  /** 当前进度；尚未开始时为空。 */
  Optional<RailGraphBuildJob.RailGraphBuildStatus> getStatus();

  /**
   * 取消任务并释放它持有的区块票。
   *
   * @return 任务是否仍在运行（已结束时返回 false）
   */
  boolean cancel();
}
