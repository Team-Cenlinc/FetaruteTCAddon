package org.fetarute.fetaruteTCAddon.drive.guard;

import com.bergerkiller.bukkit.tc.actions.GroupAction;
import com.bergerkiller.bukkit.tc.controller.MinecartGroup;

/**
 * 车掌拉下紧急停车时自动运行（含 ATO）的车的紧急制动：从拉下时的车速起，按紧急制动减速度逐 tick 减速，停稳后结束；之后由车掌的紧急停车扣着，
 * 调度不替它起步。与手动驾驶的控车动作一样，速度只在进入新的一 tick 时推进，同一 tick 的各小步施加同一前进力。
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
    if (speedBps < 0.0) {
      speedBps = group.head().getRealSpeed() * TICKS_PER_SECOND;
    }
    int tick = elapsedTicks();
    if (tick != advancedTick) {
      advancedTick = tick;
      speedBps = nextSpeed(speedBps, decelBps2);
    }
    if (speedBps <= 0.0) {
      group.setForwardForce(0.0);
      group.stop();
      return true;
    }
    group.setForwardForce(speedBps / TICKS_PER_SECOND / Math.max(1, group.getUpdateStepCount()));
    return false;
  }

  /** 减速一 tick 后的车速（格/秒），不低于 0。 */
  static double nextSpeed(double speedBps, double decelBps2) {
    return Math.max(0.0, speedBps - Math.max(0.01, decelBps2) / TICKS_PER_SECOND);
  }
}
