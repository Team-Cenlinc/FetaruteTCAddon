package org.fetarute.fetaruteTCAddon.drive.guard;

import java.util.UUID;
import org.bukkit.entity.Player;

/** 车掌考法的考官：车掌每做完一站作业、值乘结束时告诉它。由驾驶证服务实现。只在服务器主线程调用。 */
public interface GuardExaminer {

  /** 没有考官：不在考试。 */
  GuardExaminer NONE =
      new GuardExaminer() {
        @Override
        public boolean examining(UUID playerId) {
          return false;
        }

        @Override
        public void onStopWorked(Player player, GuardScore.Stop stop) {}

        @Override
        public void onDutyEnded(UUID playerId, GuardSession.EndReason reason) {}

        @Override
        public int drillDue(UUID playerId, String trainName) {
          return 0;
        }

        @Override
        public void onDrillDone(Player player, String station, boolean handled, double seconds) {}
      };

  /** 这名玩家正在考车掌（含练习）：考试中做的这一趟不发奖励。 */
  boolean examining(UUID playerId);

  /** 这名玩家正在做车掌练习：练习中做的这一趟也不写进车掌记录。 */
  default boolean practicing(UUID playerId) {
    return false;
  }

  /** 做完一站作业（出站监视也采完了）。 */
  void onStopWorked(Player player, GuardScore.Stop stop);

  /** 值乘结束。 */
  void onDutyEnded(UUID playerId, GuardSession.EndReason reason);

  /**
   * 车掌按下关门：这一站要不要演练夹人夹物（考试中只安排一次）。
   *
   * @param trainName 列车（晚点过大时不安排）
   * @return 处置时限（秒）；不演练时为 0
   */
  int drillDue(UUID playerId, String trainName);

  /**
   * 演练结束：处置完成，或过了时限还没处置完。
   *
   * @param seconds 处置用时；没处置完时为时限
   */
  void onDrillDone(Player player, String station, boolean handled, double seconds);
}
