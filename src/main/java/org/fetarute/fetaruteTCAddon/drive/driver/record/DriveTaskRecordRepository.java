package org.fetarute.fetaruteTCAddon.drive.driver.record;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 驾驶任务记录仓库。 */
public interface DriveTaskRecordRepository {

  /** 保存一条记录。 */
  void save(DriveTaskRecord record);

  /** 一名驾驶员最近的记录，新的在前。 */
  List<DriveTaskRecord> listByPlayer(UUID playerId, int limit);

  /**
   * 排行：统计期内完成的任务按驾驶员汇总，总分高的在前。
   *
   * @param since 统计期开始；为 {@code null} 时统计全部
   */
  List<DriveLeaderboardRow> leaderboard(Instant since, int limit);
}
