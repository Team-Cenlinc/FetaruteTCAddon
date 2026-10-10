package org.fetarute.fetaruteTCAddon.drive.guard;

import com.bergerkiller.bukkit.tc.actions.GroupAction;
import com.bergerkiller.bukkit.tc.controller.MinecartGroup;

/**
 * 车掌拉下紧急停车时自动运行（含 ATO）的车的紧急制动：从拉下时的车速起，按紧急制动减速度逐 tick 减速，停稳后结束；之后由车掌的紧急停车扣着，
 * 调度不替它起步。与手动驾驶的控车动作一样，速度只在进入新的一 tick 时推进，同一 tick 的各小步施加同一前进力。
 *
 * <p>车速只降不升：每 tick 先取实际车速与上一 tick 算出的车速中较小的一个再减速。扣车期间列车按驾驶员控制处理，调度的硬停车只把车速归零、不清动作队列，
 * 不这样做下一步就会把车推回原来的速度。
 */
final class GuardEmergencyBrakeAction extends GroupAction {

  private static final double TICKS_PER_SECOND = 20.0;

  private final double decelBps2;
  private double speedBps = -1.0;
  private int advancedTick = -1;

  /**
   * @param decelBps2 紧急制动减速度（格/秒²）
   */
  GuardEmergencyBrakeAction(double decelBps2) {
    this.decelBps2 = Math.max(0.01, decelBps2);
  }

  @Override
  public boolean update() {
    MinecartGroup group = getGroup();
    int tick = elapsedTicks();
    if (tick != advancedTick) {
      advancedTick = tick;
      speedBps =
          nextSpeed(
              brakingFrom(speedBps, group.head().getRealSpeedLimited() * TICKS_PER_SECOND),
              decelBps2);
    }
    if (speedBps <= 0.0) {
      group.setForwardForce(0.0);
      group.stop();
      return true;
    }
    group.setForwardForce(speedBps / TICKS_PER_SECOND / Math.max(1, group.getUpdateStepCount()));
    return false;
  }

  /**
   * 这一 tick 从多快开始减速：取上一 tick 算出的车速与实际车速中较小的一个，第一 tick 取实际车速。
   *
   * @param heldBps 上一 tick 算出的车速；还没算过时为负
   * @param actualBps 实际车速（格/秒）
   */
  static double brakingFrom(double heldBps, double actualBps) {
    double actual = Math.max(0.0, actualBps);
    return heldBps < 0.0 ? actual : Math.min(heldBps, actual);
  }

  /** 减速一 tick 后的车速（格/秒），不低于 0。 */
  static double nextSpeed(double speedBps, double decelBps2) {
    return Math.max(0.0, speedBps - Math.max(0.01, decelBps2) / TICKS_PER_SECOND);
  }
}
