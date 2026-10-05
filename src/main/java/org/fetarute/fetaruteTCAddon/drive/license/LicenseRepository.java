package org.fetarute.fetaruteTCAddon.drive.license;

import java.util.List;
import java.util.UUID;

/** 驾驶证的存储：每名玩家持有哪些等级，以及各级的路考练习次数。 */
public interface LicenseRepository {

  /** 这名玩家持有的全部等级。 */
  List<LicenseRecord> listByPlayer(UUID playerId);

  /** 发证（已持有时更新发证信息）。 */
  void grant(LicenseRecord record);

  /**
   * 吊销一级。
   *
   * @return 原来是否持有
   */
  boolean revoke(UUID playerId, String classId);

  /** 这名玩家各级的练习次数。 */
  List<TrainingRecord> trainingByPlayer(UUID playerId);

  /** 记下练习次数（覆盖原值）。 */
  void saveTraining(TrainingRecord record);
}
