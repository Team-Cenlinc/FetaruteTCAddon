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
   * 一名玩家某种方式最近的记录，新的在前（例如车掌的 {@link DriveTaskRecord#MODE_GUARD}）。
   *
   * @param mode 记录的方式
   */
  List<DriveTaskRecord> listByPlayerAndMode(UUID playerId, String mode, int limit);

  /**
   * 排行：统计期内完成的任务按驾驶员汇总，总分高的在前。
   *
   * @param since 统计期开始；为 {@code null} 时统计全部
   */
  List<DriveLeaderboardRow> leaderboard(Instant since, int limit);

  /**
   * 删掉结束时刻早于 {@code cutoff} 的记录（驾驶记录只保留一段时间）。
   *
   * @return 删掉的条数
   */
  int deleteFinishedBefore(Instant cutoff);

  /** 一名驾驶员的累计成绩（在库里汇总，不读明细）。 */
  PlayerTotals totalsByPlayer(UUID playerId);

  /**
   * 一名玩家某种方式的累计成绩（例如车掌的 {@link DriveTaskRecord#MODE_GUARD}），口径同 {@link #totalsByPlayer}。
   *
   * @param mode 记录的方式
   */
  PlayerTotals totalsByPlayerAndMode(UUID playerId, String mode);

  /**
   * 累计成绩。
   *
   * @param tasks 开过车的任务数
   * @param completed 其中开完的任务数
   * @param completedPoints 开完的任务的总得分（与排行同一口径）
   * @param bestGrade 最好的评级（S/A/B/C/D）；没有记录时为空串
   */
  record PlayerTotals(int tasks, int completed, long completedPoints, String bestGrade) {}
}
