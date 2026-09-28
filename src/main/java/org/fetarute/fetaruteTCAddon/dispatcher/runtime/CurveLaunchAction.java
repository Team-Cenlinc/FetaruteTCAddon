package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.TrainCarts;
import com.bergerkiller.bukkit.tc.actions.MemberActionLaunchDirection;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.controller.components.ActionTracker;
import com.bergerkiller.bukkit.tc.offline.train.format.OfflineDataBlock;
import com.bergerkiller.bukkit.tc.utils.LauncherConfig;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.SpeedCurve;

/**
 * 按 {@link SpeedCurve} 提速的发车/调速动作，替代 TrainCarts 自带的 launch 曲线。
 *
 * <p>TrainCarts 的 launch 只能按恒加速度（linear）或固定时长的 bezier 规划，且每遇 speedLimit 变化就从当前速度重新规划、 丢一 tick
 * 的加速。本动作每 tick 按"当前速度、本段起点速度、当前限速"直接算下一 tick 的速度，与编表运行曲线（{@code RunCurve}） 是同一条 S 形曲线；限速变化只改变这一
 * tick 的上限，不打断曲线，也不丢加速。
 *
 * <p>继承带方向的 launch，沿用 TrainCarts 的动作队列、状态显示与首次起动后的方向校正；只替换逐 tick 的速度计算。 动作在以下情况结束：
 *
 * <ul>
 *   <li>到达目标速度；
 *   <li>被低于目标的限速贴住（正在沿制动曲线减速）：结束以免挡住停站等后续动作，限速回升后由调度层重新下发，作为新的一段加速；
 *   <li>起动后某节车厢停住（碰撞、被挡）。
 * </ul>
 *
 * <p>目标低于当前速度时第一 tick 就把速度设到目标并结束，起的是把 TrainCarts 速度向量重置为目标值的作用（截速不缩短向量）。
 *
 * <p>区块卸载时随列车保存（{@link #registerSerializer}）：重新加载后按保存时的速度与本段起点接着走，不重新起步。
 */
public final class CurveLaunchAction extends MemberActionLaunchDirection {

  /** 在 TrainCarts 动作注册表里的标识。 */
  static final String REGISTRY_ID = "fetarute:curve_launch";

  private static final String STATE_CHILD = "fta-curve-launch";

  private static final double TICKS_PER_SECOND = 20.0;

  /** 低于它视为停住（blocks/tick），与 TrainCarts launch 相同。 */
  private static final double STOPPED_BPT = 0.001;

  /** 判定到速、贴住限速的容差（blocks/tick）。 */
  private static final double REACHED_TOLERANCE_BPT = 1.0e-6;

  private final Stepper stepper;
  private int advancedTick = -1;

  /**
   * @param accelBps2 满加速度（格/秒²），必须为正
   * @param targetBpt 目标速度（blocks/tick）
   * @param direction 起动方向；不指定时用 {@link BlockFace#SELF}（沿当前方向）
   */
  CurveLaunchAction(double accelBps2, double targetBpt, BlockFace direction) {
    this.stepper = new Stepper(accelBps2, targetBpt);
    LauncherConfig config = new LauncherConfig();
    // 只供 TrainCarts 的状态显示与父类初始化；逐 tick 速度由 Stepper 计算。
    config.setAcceleration(accelBps2 / (TICKS_PER_SECOND * TICKS_PER_SECOND));
    init(config, stepper.targetBpt, direction == null ? BlockFace.SELF : direction);
  }

  @Override
  public void start() {
    super.start();
    stepper.start(getMember().getRealSpeedLimited());
  }

  /**
   * 每个物理小步调用一次；速度只在进入新的一 tick 时推进，同一 tick 的各小步施加同一前进力（与 TrainCarts launch 相同）。
   *
   * @return 动作是否结束
   */
  @Override
  public boolean update() {
    int tick = elapsedTicks();
    boolean done = false;
    if (tick != advancedTick) {
      advancedTick = tick;
      done = stepper.advance(getGroup().getProperties().getSpeedLimit());
      if (tick > 0 && stepper.detectsStall() && anyMemberStopped()) {
        return true;
      }
    }
    getGroup().setForwardForce(stepper.velocityBpt() / getGroup().getUpdateStepCount());
    correctDirectionOnce();
    return done;
  }

  /** 在 TrainCarts 动作注册表里登记，区块卸载时随列车保存。插件启用时调用。 */
  public static void registerSerializer(TrainCarts trainCarts) {
    if (trainCarts != null) {
      trainCarts
          .getActionRegistry()
          .register(REGISTRY_ID, CurveLaunchAction.class, new Serializer());
    }
  }

  /** 撤销登记；此后保存的数据加载时被 TrainCarts 跳过。插件停用时调用。 */
  public static void unregisterSerializer(TrainCarts trainCarts) {
    if (trainCarts != null) {
      trainCarts.getActionRegistry().unregister(REGISTRY_ID);
    }
  }

  private boolean anyMemberStopped() {
    for (MinecartMember<?> member : getGroup()) {
      if (member.getRealSpeed() < STOPPED_BPT) {
        return true;
      }
    }
    return false;
  }

  /** 与父类相同：起动后按实际速度方向校正一次，跑反了就把整列调头。 */
  private void correctDirectionOnce() {
    if (isDirectionCorrected()) {
      return;
    }
    Vector velocity = getMember().getEntity().getVelocity();
    if (velocity.lengthSquared() > 1e-20) {
      setDirectionCorrected(true);
      if (velocity.dot(getDirectionVector()) < 0.0) {
        getGroup().reverse();
      }
    }
  }

  /** 逐 tick 推速状态（测试与序列化用）。 */
  Stepper stepper() {
    return stepper;
  }

  /**
   * 逐 tick 的推速与结束判定，与 TrainCarts 对象无关：每 tick 按 {@link SpeedCurve#nextSpeedBps} 推一步，
   * 上限取目标与当前限速的较小者，到达上限即结束。
   */
  static final class Stepper {
    private final double accelBps2;
    private final double targetBpt;
    private double velocityBpt;
    private double phaseStartBps;

    /**
     * @param accelBps2 满加速度（格/秒²），必须为正
     * @param targetBpt 目标速度（blocks/tick）
     */
    Stepper(double accelBps2, double targetBpt) {
      if (!Double.isFinite(accelBps2) || accelBps2 <= 0.0) {
        throw new IllegalArgumentException("accelBps2 必须为正数");
      }
      this.accelBps2 = accelBps2;
      this.targetBpt = Math.max(0.0, targetBpt);
    }

    /** 动作开始：以当前速度为本段加速的起点。 */
    void start(double currentBpt) {
      velocityBpt = Math.max(0.0, currentBpt);
      phaseStartBps = velocityBpt * TICKS_PER_SECOND;
    }

    /**
     * 推进一 tick。
     *
     * @param speedLimitBpt 当前限速（blocks/tick）
     * @return 是否已到上限（目标或限速），动作应结束
     */
    boolean advance(double speedLimitBpt) {
      double capBpt = Math.min(targetBpt, speedLimitBpt);
      velocityBpt =
          SpeedCurve.nextSpeedBps(
                  accelBps2,
                  velocityBpt * TICKS_PER_SECOND,
                  phaseStartBps,
                  capBpt * TICKS_PER_SECOND,
                  1.0 / TICKS_PER_SECOND)
              / TICKS_PER_SECOND;
      return velocityBpt >= capBpt - REACHED_TOLERANCE_BPT;
    }

    /** 速度已足够大，车厢若停住就说明被挡（与 TrainCarts launch 的判定相同）。 */
    boolean detectsStall() {
      return velocityBpt > 10.0 * STOPPED_BPT;
    }

    double velocityBpt() {
      return velocityBpt;
    }

    double phaseStartBps() {
      return phaseStartBps;
    }
  }

  /** 保存父类的发车与方向状态，另存本类的加速度、目标、当前速度与本段起点。 */
  static final class Serializer
      extends MemberActionLaunchDirection.BaseSerializer<CurveLaunchAction> {
    @Override
    public boolean save(CurveLaunchAction action, OfflineDataBlock data, ActionTracker tracker)
        throws IOException {
      super.save(action, data, tracker);
      data.addChild(STATE_CHILD, stream -> writeState(stream, action.stepper));
      return true;
    }

    @Override
    public CurveLaunchAction create(OfflineDataBlock data) throws IOException {
      try (DataInputStream stream = data.findChildOrThrow(STATE_CHILD).readData()) {
        return readState(stream);
      }
    }

    /** 写本类的推速状态（父类状态由 TrainCarts 的基类序列化器写）。 */
    static void writeState(DataOutputStream stream, Stepper stepper) throws IOException {
      stream.writeDouble(stepper.accelBps2);
      stream.writeDouble(stepper.targetBpt);
      stream.writeDouble(stepper.velocityBpt);
      stream.writeDouble(stepper.phaseStartBps);
    }

    /** 按保存的推速状态重建动作：接着原来的速度与本段起点走，不重新起步。 */
    static CurveLaunchAction readState(DataInputStream stream) throws IOException {
      CurveLaunchAction action =
          new CurveLaunchAction(stream.readDouble(), stream.readDouble(), BlockFace.SELF);
      action.stepper.velocityBpt = stream.readDouble();
      action.stepper.phaseStartBps = stream.readDouble();
      return action;
    }
  }
}
