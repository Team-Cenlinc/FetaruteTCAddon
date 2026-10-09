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
      };

  /** 这名玩家正在考车掌：考试中做的这一趟不发奖励。 */
  boolean examining(UUID playerId);

  /** 做完一站作业（出站监视也采完了）。 */
  void onStopWorked(Player player, GuardScore.Stop stop);

  /** 值乘结束。 */
  void onDutyEnded(UUID playerId, GuardSession.EndReason reason);
}
