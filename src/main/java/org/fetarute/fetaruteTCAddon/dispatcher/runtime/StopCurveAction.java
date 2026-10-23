package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.TrainCarts;
import com.bergerkiller.bukkit.tc.actions.MemberActionLaunchDirection;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.controller.components.ActionTracker;
import com.bergerkiller.bukkit.tc.offline.train.format.OfflineDataBlock;
import com.bergerkiller.bukkit.tc.utils.LauncherConfig;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.SpeedCeiling;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.SpeedCurve;

/**
 * 把列车沿自动运行的制动曲线送到前方 {@code distance} 格处停住：车站停车时 AutoStation 在列车压上站牌后挂上它，替代 TrainCarts 自带的居中与距离式
 * launch。
 *
 * <p>TrainCarts 的距离式 launch（bezier）按固定时长规划，平均减速度是 {@code
 * v²/(2·距离)}，与车型无关：剩余距离短时刹得极急，长时最后两成时间几乎在原地爬。 本动作每 tick 按第一节车厢实际走过的距离（实体位置的位移，弯道与 TCC
 * 上就是走过的弧长）扣掉余下的距离，速度跟着同一条 S 形制动曲线（{@link SpeedCurve}，与编表、调度控车同一条：一段刹到停车点，
 * 停车前缓解制动）在余下距离处允许的值，平稳停住。任何一 tick 都不会越过停车点。
 *
 * <p>进站速度比曲线略低时（调度压牌前按整格向下取整估停车点）惰行，等曲线降下来再接着刹，不先加速再刹；低得多时（调度按保守的停车点估计提前刹了）
 * 先按加速曲线回到曲线上；比曲线高时按常用制动追回。起动后车被挡住、不再前进时结束，交给 AutoStation 的停稳判定。停住时把前进力置 0：TrainCarts
 * 截速不缩短速度向量，残留的向量会在限速抬起时把车弹出去。
 *
 * <p>区块卸载时随列车保存（{@link #registerSerializer}）：重新加载后按保存时的余下距离与速度接着走。
 */
public final class StopCurveAction extends MemberActionLaunchDirection {

  /** 在 TrainCarts 动作注册表里的标识。 */
  static final String REGISTRY_ID = "fetarute:stop_curve";

  private static final String STATE_CHILD = "fta-stop-curve";

  private static final double TICKS_PER_SECOND = 20.0;

  /** 速度上限至少取这么多（格/秒）：车停着、调度的限速又是 0 时也要试着动一动，动不了才按被挡住结束（见 {@link #STALL_TICKS}）。 */
  public static final double MIN_CAP_BPS = 1.0;

  /** 实测速度低于曲线的这个比例才提速回到曲线上；差得不多时惰行，等曲线降下来。 */
  static final double CLIMB_BELOW = 0.8;

  /** 建制动曲线时的“巡航速度”：取一个远高于实际车速的值，曲线上就没有制动开始时的柔和段，只有满减速度与停车前的缓解——与调度沿线路速度刹过来的那条曲线在站台这一段相同。 */
  private static final double PROFILE_CRUISE_BPS = 1000.0;

  /**
   * 停车前最后这么多格（格）按细步长另算一张速度表：制动天花板 0.25 格一个采样点、点间按匀减速插值，停车前的缓解段只有半格左右， 落不到几个采样点，逐 tick
   * 跟着走会感到减速度一级一级地跳。
   */
  private static final double FINE_STEP_BLOCKS = 0.02;

  private static final int FINE_SAMPLES = 100;

  private static final double FINE_BLOCKS = FINE_SAMPLES * FINE_STEP_BLOCKS;

  /** 离停车点不到这么多格算到了。 */
  static final double ARRIVED_BLOCKS = 0.02;

  /** 允许速度不低于它（格/秒）而车连续这么多 tick 几乎没动，就是被挡住了。 */
  private static final int STALL_TICKS = 20;

  private static final double STALL_COMMAND_BPS = 0.2;

  private static final double STALL_MOVED_BLOCKS = 1.0e-4;

  private final Stepper stepper;
  private int advancedTick = -1;
  private Vector lastPosition;

  /**
   * @param curve 自动运行的加减速曲线（加速度用于回到曲线，减速度即制动曲线）
   * @param serviceDecelBps2 常用制动减速度（格/秒²）：追回曲线与最后刹停用
   * @param distanceBlocks 第一节车厢还要走多远（格），必须为正
   * @param direction 行进方向；不指定时用 {@link BlockFace#SELF}（沿当前方向）
   */
  public StopCurveAction(
      SpeedCurve curve, double serviceDecelBps2, double distanceBlocks, BlockFace direction) {
    this(new Stepper(curve, serviceDecelBps2, distanceBlocks, MIN_CAP_BPS), direction);
  }

  private StopCurveAction(Stepper stepper, BlockFace direction) {
    this.stepper = stepper;
    LauncherConfig config = new LauncherConfig();
    // 只供 TrainCarts 的状态显示与父类初始化；逐 tick 速度由 Stepper 计算。
    config.setAcceleration(stepper.serviceDecelBps2 / (TICKS_PER_SECOND * TICKS_PER_SECOND));
    init(config, 0.0, direction == null ? BlockFace.SELF : direction);
  }

  /**
   * 照理想情况（每 tick 走足指令速度）走完要多少 tick：AutoStation 等停稳时多留这么久。
   *
   * @param currentBpt 当前车速（blocks/tick）
   * @param limitBpt 列车此刻的限速（blocks/tick）
   */
  public int estimatedTicks(double currentBpt, double limitBpt) {
    return Stepper.estimateTicks(stepper, currentBpt, limitBpt);
  }

  @Override
  public void start() {
    super.start();
    stepper.start(getMember().getRealSpeedLimited(), getGroup().getProperties().getSpeedLimit());
  }

  /**
   * 每个物理小步调用一次；速度只在进入新的一 tick 时推进，同一 tick 的各小步施加同一前进力（与 TrainCarts launch 相同）。
   *
   * <p>开始（或区块重新加载后接着走）的那一 tick 只记下起点、照起始速度走：这一 tick 车还没动，位移为 0 不说明车停了。
   *
   * @return 动作是否结束
   */
  @Override
  public boolean update() {
    int tick = elapsedTicks();
    if (tick != advancedTick) {
      advancedTick = tick;
      Vector now = position(getMember());
      Vector previous = lastPosition;
      lastPosition = now;
      double moved =
          previous == null
              ? Double.NaN
              : now == null ? stepper.velocityBpt() : now.distance(previous);
      if (!Double.isNaN(moved) && stepper.advance(moved)) {
        getGroup().setForwardForce(0.0);
        return true;
      }
    }
    getGroup().setForwardForce(stepper.velocityBpt() / getGroup().getUpdateStepCount());
    correctDirectionOnce();
    return false;
  }

  /** 在 TrainCarts 动作注册表里登记，区块卸载时随列车保存。插件启用时调用。 */
  public static void registerSerializer(TrainCarts trainCarts) {
    if (trainCarts != null) {
      trainCarts.getActionRegistry().register(REGISTRY_ID, StopCurveAction.class, new Serializer());
    }
  }

  /** 撤销登记；此后保存的数据加载时被 TrainCarts 跳过。插件停用时调用。 */
  public static void unregisterSerializer(TrainCarts trainCarts) {
    if (trainCarts != null) {
      trainCarts.getActionRegistry().unregister(REGISTRY_ID);
    }
  }

  @SuppressFBWarnings(
      value = "BC_UNCONFIRMED_CAST_OF_RETURN_VALUE",
      justification = "TrainCarts 的 MinecartMember#getEntity 泛型契约保证返回该成员对应的 CommonMinecart")
  private static Vector position(MinecartMember<?> member) {
    Entity entity =
        member == null || member.getEntity() == null ? null : member.getEntity().getEntity();
    return entity == null || !entity.isValid() ? null : entity.getLocation().toVector();
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

  /** 还要走多远（格）。 */
  public double remainingBlocks() {
    return Math.max(0.0, stepper.remainingBlocks());
  }

  /** 逐 tick 推速状态（测试与序列化用）。 */
  Stepper stepper() {
    return stepper;
  }

  /** 逐 tick 的推速与结束判定，与 TrainCarts 对象无关：按实际走过的距离扣掉余下距离，速度跟着制动曲线在余下距离处允许的值走。 */
  static final class Stepper {
    private final SpeedCurve curve;
    private final double serviceDecelBps2;
    private final double distanceBlocks;
    private final double minCapBps;
    private double cruiseBps;
    private SpeedCeiling braking;
    private double brakingLength;
    private double[] fineSpeeds;
    private double travelled;
    private double velocityBpt;
    private double phaseStartBps;
    private boolean accelerating;
    private int stillTicks;

    /**
     * @param curve 自动运行的加减速曲线
     * @param serviceDecelBps2 常用制动减速度（格/秒²），必须为正
     * @param distanceBlocks 要走的距离（格），必须为正
     * @param minCapBps 速度上限的下限（格/秒），必须为正
     */
    Stepper(SpeedCurve curve, double serviceDecelBps2, double distanceBlocks, double minCapBps) {
      if (curve == null) {
        throw new IllegalArgumentException("curve 不能为空");
      }
      if (!(serviceDecelBps2 > 0.0) || !Double.isFinite(serviceDecelBps2)) {
        throw new IllegalArgumentException("serviceDecelBps2 必须为正数");
      }
      if (!(distanceBlocks > 0.0) || !Double.isFinite(distanceBlocks)) {
        throw new IllegalArgumentException("distanceBlocks 必须为正数");
      }
      if (!(minCapBps > 0.0) || !Double.isFinite(minCapBps)) {
        throw new IllegalArgumentException("minCapBps 必须为正数");
      }
      this.curve = curve;
      this.serviceDecelBps2 = serviceDecelBps2;
      this.distanceBlocks = distanceBlocks;
      this.minCapBps = minCapBps;
    }

    /**
     * 动作开始：建一次制动曲线。速度上限取当前车速、列车此刻的限速与 {@link #MIN_CAP_BPS} 三者的最大者：车本来就不会超过限速，
     * 进站比曲线慢得多时可以提速回到曲线上，而不是被压在进站时的速度上爬完。
     *
     * @param currentBpt 当前车速（blocks/tick）
     * @param limitBpt 列车此刻的限速（blocks/tick）；非有限值时不计
     */
    void start(double currentBpt, double limitBpt) {
      double entryBps = Math.max(0.0, currentBpt) * TICKS_PER_SECOND;
      double limitBps =
          Double.isFinite(limitBpt) ? Math.max(0.0, limitBpt) * TICKS_PER_SECOND : 0.0;
      cruiseBps = Math.max(Math.max(entryBps, limitBps), minCapBps) + 1.0e-6;
      // 第一 tick 照起始速度走（还没有实测位移），同样不越过停车点。
      velocityBpt = Math.min(entryBps / TICKS_PER_SECOND, distanceBlocks);
      phaseStartBps = velocityBpt * TICKS_PER_SECOND;
      buildBraking();
    }

    private void buildBraking() {
      brakingLength = SpeedCeiling.quantize(distanceBlocks);
      braking =
          SpeedCeiling.of(
              new double[] {brakingLength},
              new double[] {PROFILE_CRUISE_BPS},
              List.of(),
              0.0,
              curve);
      fineSpeeds = fineStop(curve);
      if (brakingLength > FINE_BLOCKS) {
        // 两段在交界处接上：细表按交界处的天花板值整体缩放（两者只差积分步长带来的百分之一左右），不在那一 tick 顿一下。
        double coarse = braking.limitBps(brakingLength - FINE_BLOCKS);
        double fine = fineSpeeds[fineSpeeds.length - 1];
        if (coarse > 0.0 && fine > 0.0) {
          double scale = coarse / fine;
          for (int k = 0; k < fineSpeeds.length; k++) {
            fineSpeeds[k] *= scale;
          }
        }
      }
    }

    /** 离停车点 {@code k·FINE_STEP_BLOCKS} 格处的速度，从停车点往回按同一条曲线积分（中点法）。 */
    private static double[] fineStop(SpeedCurve curve) {
      double[] speeds = new double[FINE_SAMPLES + 1];
      for (int k = 1; k <= FINE_SAMPLES; k++) {
        double v = speeds[k - 1];
        double half =
            Math.sqrt(
                v * v + curve.decelerationBps2(v, PROFILE_CRUISE_BPS, 0.0) * FINE_STEP_BLOCKS);
        double decel = curve.decelerationBps2(half, PROFILE_CRUISE_BPS, 0.0);
        speeds[k] = Math.sqrt(v * v + 2.0 * decel * FINE_STEP_BLOCKS);
      }
      return speeds;
    }

    /** 余下的距离（格）。 */
    double remainingBlocks() {
      return distanceBlocks - travelled;
    }

    /**
     * 推进一 tick。
     *
     * @param movedBlocks 上一 tick 实际走过的距离（格）
     * @return 是否已到停车点（或被挡住），动作应结束
     */
    boolean advance(double movedBlocks) {
      double moved = Double.isFinite(movedBlocks) ? Math.max(0.0, movedBlocks) : 0.0;
      travelled += moved;
      double remaining = remainingBlocks();
      if (remaining <= ARRIVED_BLOCKS) {
        velocityBpt = 0.0;
        return true;
      }
      double commandedBps = velocityBpt * TICKS_PER_SECOND;
      double allowed = allowedBps(remaining);
      // 曲线允许走、车却不动：被挡住了（指令会因实测速度为 0 一直贴着 0，不能拿它判）。
      stillTicks = allowed >= STALL_COMMAND_BPS && moved < STALL_MOVED_BLOCKS ? stillTicks + 1 : 0;
      if (stillTicks >= STALL_TICKS) {
        velocityBpt = 0.0;
        return true;
      }
      // 当前速度取实测的位移：TrainCarts 按限速截速时实际比指令慢，拿指令值算刹车会晚刹、冲过停车点。
      double speed = Math.min(commandedBps, moved * TICKS_PER_SECOND);
      double next;
      if (speed < CLIMB_BELOW * allowed) {
        if (!accelerating) {
          accelerating = true;
          phaseStartBps = speed;
        }
        next =
            SpeedCurve.nextSpeedBps(
                curve.accelBps2(), speed, phaseStartBps, allowed, 1.0 / TICKS_PER_SECOND);
      } else if (speed < allowed && !accelerating) {
        // 略低于曲线：惰行，等曲线降下来再接着刹（不先加速再刹）。
        next = speed;
      } else if (speed < allowed) {
        // 正在提速回曲线：接着按加速曲线贴上去。
        next =
            SpeedCurve.nextSpeedBps(
                curve.accelBps2(), speed, phaseStartBps, allowed, 1.0 / TICKS_PER_SECOND);
      } else {
        accelerating = false;
        next = Math.max(allowed, speed - serviceDecelBps2 / TICKS_PER_SECOND);
      }
      // 这一 tick 不越过停车点。
      next = Math.min(next, remaining * TICKS_PER_SECOND);
      velocityBpt = Math.max(0.0, next) / TICKS_PER_SECOND;
      return false;
    }

    /** 余下 {@code remaining} 格时允许的速度：制动曲线（停车前缓解制动），不超过速度上限。 */
    double allowedBps(double remaining) {
      double left = Math.max(0.0, remaining);
      double onCurve;
      if (left < FINE_BLOCKS) {
        double position = left / FINE_STEP_BLOCKS;
        int k = (int) Math.floor(position);
        double from = fineSpeeds[k];
        double to = fineSpeeds[Math.min(k + 1, fineSpeeds.length - 1)];
        // 采样点之间按 v² 线性插值（点间匀减速），与天花板相同。
        onCurve = Math.sqrt(from * from + (to * to - from * from) * (position - k));
      } else {
        onCurve = braking.limitBps(Math.max(0.0, brakingLength - left));
      }
      return Math.min(cruiseBps, onCurve);
    }

    double velocityBpt() {
      return velocityBpt;
    }

    /** 拿一份同样参数的推速器，按理想情况（每 tick 走足指令速度）走完，数要多少 tick；上限 2400 tick。 */
    static int estimateTicks(Stepper template, double currentBpt, double limitBpt) {
      Stepper probe =
          new Stepper(
              template.curve,
              template.serviceDecelBps2,
              template.distanceBlocks,
              template.minCapBps);
      probe.start(currentBpt, limitBpt);
      for (int tick = 1; tick <= 2400; tick++) {
        if (probe.advance(probe.velocityBpt())) {
          return tick;
        }
      }
      return 2400;
    }
  }

  /** 保存父类的发车与方向状态，另存本类的曲线参数、余下距离与当前速度。 */
  static final class Serializer
      extends MemberActionLaunchDirection.BaseSerializer<StopCurveAction> {
    @Override
    public boolean save(StopCurveAction action, OfflineDataBlock data, ActionTracker tracker)
        throws IOException {
      super.save(action, data, tracker);
      data.addChild(STATE_CHILD, stream -> writeState(stream, action.stepper));
      return true;
    }

    @Override
    public StopCurveAction create(OfflineDataBlock data) throws IOException {
      try (DataInputStream stream = data.findChildOrThrow(STATE_CHILD).readData()) {
        return readState(stream);
      }
    }

    static void writeState(DataOutputStream stream, Stepper stepper) throws IOException {
      stream.writeDouble(stepper.curve.accelBps2());
      stream.writeDouble(stepper.curve.decelBps2());
      stream.writeDouble(stepper.serviceDecelBps2);
      stream.writeDouble(stepper.distanceBlocks);
      stream.writeDouble(stepper.minCapBps);
      stream.writeDouble(stepper.cruiseBps);
      stream.writeDouble(stepper.travelled);
      stream.writeDouble(stepper.velocityBpt);
      stream.writeDouble(stepper.phaseStartBps);
      stream.writeBoolean(stepper.accelerating);
    }

    /** 按保存的状态重建动作：接着原来的余下距离与速度走，不重新起步。 */
    static StopCurveAction readState(DataInputStream stream) throws IOException {
      SpeedCurve curve = new SpeedCurve(stream.readDouble(), stream.readDouble());
      Stepper stepper =
          new Stepper(curve, stream.readDouble(), stream.readDouble(), stream.readDouble());
      stepper.cruiseBps = stream.readDouble();
      stepper.buildBraking();
      stepper.travelled = stream.readDouble();
      stepper.velocityBpt = stream.readDouble();
      stepper.phaseStartBps = stream.readDouble();
      stepper.accelerating = stream.readBoolean();
      return new StopCurveAction(stepper, BlockFace.SELF);
    }
  }
}
