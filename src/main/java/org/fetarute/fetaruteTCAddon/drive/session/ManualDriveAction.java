package org.fetarute.fetaruteTCAddon.drive.session;

import com.bergerkiller.bukkit.tc.actions.GroupAction;
import org.bukkit.Bukkit;

/**
 * 手动驾驶的控车动作：挂在编组的动作队列上，每个物理小步把会话积分出的速度写给 TrainCarts。
 *
 * <p>与自动发车的 {@code CurveLaunchAction} 一样，速度只在进入新的一 tick 时推进，同一 tick 的各小步施加同一前进力。
 * 动作不会自己结束，直到会话结束（{@link DriveSession.Phase#ENDED}）；动作不做序列化，区块卸载时随列车丢弃，由会话管理器在列车重新出现后重新挂上。
 */
final class ManualDriveAction extends GroupAction {

  private final DriveSession session;
  private final int generation;
  private int advancedTick = -1;

  ManualDriveAction(DriveSession session) {
    this.session = session;
    this.generation = session.nextActionGeneration();
  }

  @Override
  public boolean update() {
    if (generation != session.actionGeneration()) {
      // 已被新一代动作取代：自行结束，不再写速度。
      return true;
    }
    if (session.phase() == DriveSession.Phase.ENDED) {
      getGroup().setForwardForce(0.0);
      return true;
    }
    int tick = elapsedTicks();
    if (tick != advancedTick) {
      advancedTick = tick;
      session.advance(getGroup(), Bukkit.getCurrentTick());
    }
    getGroup().setForwardForce(session.forcePerStep(getGroup().getUpdateStepCount()));
    return session.phase() == DriveSession.Phase.ENDED;
  }
}
