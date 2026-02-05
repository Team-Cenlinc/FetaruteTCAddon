package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import java.time.Instant;

/** 提供发车队列重置能力的扩展接口。 */
public interface SpawnResetSupport {

  /**
   * 重置发车队列与内部状态。
   *
   * @param now 当前时间（用于重置时间基准）
   * @return 重置结果
   */
  SpawnResetResult reset(Instant now);

  /** 重置结果统计。 */
  record SpawnResetResult(int clearedQueue, int clearedStates, boolean planReset) {
    public SpawnResetResult {
      if (clearedQueue < 0) {
        throw new IllegalArgumentException("clearedQueue 不能为负数");
      }
      if (clearedStates < 0) {
        throw new IllegalArgumentException("clearedStates 不能为负数");
      }
    }

    public static SpawnResetResult empty() {
      return new SpawnResetResult(0, 0, false);
    }
  }
}
