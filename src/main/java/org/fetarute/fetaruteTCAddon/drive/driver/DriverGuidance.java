package org.fetarute.fetaruteTCAddon.drive.driver;

import java.util.List;
import java.util.Objects;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.SpeedCeiling;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.SpeedCurve;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.SignalLookahead;

/**
 * 驾驶调度列车时的行车引导：前方最要紧的目标（停车信号的授权末端、车站停车点、限速降低处）、离它多远、建议速度，以及该不该开始制动。
 *
 * <p>对每个候选目标，按编表运行曲线同一条 S 形制动（{@link SpeedCeiling#brakingLimitBps}）反推此刻最高能跑多快，减速度取本车此刻能发挥的常用全制动乘
 * {@link DriverGuidanceConfig#adviceBrakeFraction()}，并扣掉制动力爬升期间走过的距离；反推出来最低的那个就是要显示的目标。
 * 建议速度取调度目标速度、容许速度与这个反推速度中最小的一个：巡航贴着容许速度，到点按常用制动刹下来。比例留出的那部分制动力给驾驶员修正反应迟滞—— 逐 tick 模拟验证过（{@code
 * ManualDrivingAchievabilityTest}）：照着开能停准，且不比编表慢。车速超出建议速度一个容差、且起作用的是前方目标
 * （不是此刻的限速）时，提示开始制动；降回建议速度以下才解除。
 *
 * <p>本类不依赖服务器对象。
 */
public final class DriverGuidance {

  /** 目标种类。 */
  public enum TargetKind {
    /** 引导范围内没有要减速的目标。 */
    CLEAR,
    /** 前方限速降低。 */
    SPEED_LIMIT,
    /** 前方车站停车点。 */
    STATION,
    /** 停车信号的授权末端。 */
    STOP_SIGNAL
  }

  /**
   * 目标。
   *
   * @param kind 种类
   * @param distanceBlocks 离目标多远（格）；{@link TargetKind#CLEAR} 时为 {@code NaN}
   * @param endSpeedBps 到目标处的速度上限：停车为 0，限速为该处限速，{@link TargetKind#CLEAR} 时为此刻容许速度
   */
  public record Target(TargetKind kind, double distanceBlocks, double endSpeedBps) {}

  /**
   * 一次评估的输入。距离都从车头（车站停车点按站台的对准部位）量起，越过为负。
   *
   * @param speedBps 当前车速
   * @param stopped 是否已停稳
   * @param requestedBps 调度的目标速度（已含晚点追赶等）；没有时为无穷大
   * @param permittedBps 此刻的容许速度
   * @param stopSignalBlocks 停车信号下还能走多远；不是停车信号或没有距离时为非有限值
   * @param stationBlocks 前方停车点还有多远；没有停车点时为 {@code NaN}
   * @param edges 前方限速边（距离从收到指令时的车头量起）
   * @param travelledBlocks 收到指令后又走了多远
   * @param serviceDecelBps2 常用全制动的减速度
   * @param reactionSeconds 制动力爬升到位的时间
   * @param stopMarginBlocks 停车信号前留出的余量
   * @param wasAdvising 上一次评估是否在提示开始制动
   */
  public record Input(
      double speedBps,
      boolean stopped,
      double requestedBps,
      double permittedBps,
      double stopSignalBlocks,
      double stationBlocks,
      List<SignalLookahead.EdgeSpeedConstraint> edges,
      double travelledBlocks,
      double serviceDecelBps2,
      double reactionSeconds,
      double stopMarginBlocks,
      boolean wasAdvising) {

    public Input {
      edges = edges == null ? List.of() : List.copyOf(edges);
    }
  }

  /**
   * 评估结果。
   *
   * @param target 要显示的目标
   * @param suggestedBps 建议速度
   * @param brake 是否提示开始制动
   */
  public record Advice(Target target, double suggestedBps, boolean brake) {

    /** 目标离车头的距离占引导范围的比例（Boss 栏进度）：越近越短，没有目标时为满格。 */
    public double progress(double rangeBlocks) {
      if (target.kind() == TargetKind.CLEAR || !(rangeBlocks > 0.0)) {
        return 1.0;
      }
      return Math.max(0.0, Math.min(1.0, target.distanceBlocks() / rangeBlocks));
    }
  }

  /** 低于它（格/秒）算没在动，不提示制动。 */
  private static final double MOVING_BPS = 0.3;

  private DriverGuidance() {}

  /** 评估。 */
  public static Advice evaluate(Input in, DriverGuidanceConfig config) {
    Objects.requireNonNull(in, "in");
    Objects.requireNonNull(config, "config");
    double permitted = finiteOr(in.permittedBps(), 0.0);
    double cap = Math.max(0.0, permitted);
    if (Double.isFinite(in.requestedBps()) && in.requestedBps() >= 0.0) {
      cap = Math.min(cap, in.requestedBps());
    }
    double speed = Math.max(0.0, in.speedBps());
    double decel = Math.max(0.0, in.serviceDecelBps2()) * config.adviceBrakeFraction();
    // S 形制动的形状取决于制动开始前的巡航速度：取所在区段的容许速度（超速时取车速）。
    double cruise = Math.max(permitted, speed);
    double range = config.rangeBlocks();

    Target best = new Target(TargetKind.CLEAR, Double.NaN, permitted);
    double bestCurve = Double.POSITIVE_INFINITY;
    if (Double.isFinite(in.stopSignalBlocks())) {
      double distance = Math.max(0.0, in.stopSignalBlocks() - in.stopMarginBlocks());
      double curve = curveBps(distance, 0.0, cruise, decel, in.reactionSeconds(), speed);
      if (distance <= range && closer(curve, distance, bestCurve, best)) {
        best = new Target(TargetKind.STOP_SIGNAL, distance, 0.0);
        bestCurve = curve;
      }
    }
    if (Double.isFinite(in.stationBlocks())) {
      double distance = Math.max(0.0, in.stationBlocks());
      double curve = curveBps(distance, 0.0, cruise, decel, in.reactionSeconds(), speed);
      if (distance <= range && closer(curve, distance, bestCurve, best)) {
        best = new Target(TargetKind.STATION, distance, 0.0);
        bestCurve = curve;
      }
    }
    double travelled = Math.max(0.0, finiteOr(in.travelledBlocks(), 0.0));
    for (SignalLookahead.EdgeSpeedConstraint edge : in.edges()) {
      double distance = edge.distanceBlocks() - travelled;
      // 已驶入的限速边已经体现在容许速度里；只看前方比此刻容许速度低的。
      if (!(distance > 0.0) || distance > range || edge.speedLimitBps() >= permitted - 1.0e-6) {
        continue;
      }
      double curve =
          curveBps(distance, edge.speedLimitBps(), cruise, decel, in.reactionSeconds(), speed);
      if (closer(curve, distance, bestCurve, best)) {
        best = new Target(TargetKind.SPEED_LIMIT, distance, edge.speedLimitBps());
        bestCurve = curve;
      }
    }
    double suggested = Math.max(0.0, Math.min(cap, bestCurve));
    boolean binding = bestCurve < cap;
    double threshold = in.wasAdvising() ? suggested : suggested + config.brakeAdviceToleranceBps();
    boolean brake = !in.stopped() && binding && speed > MOVING_BPS && speed > threshold;
    return new Advice(best, suggested, brake);
  }

  /** 两个目标反推速度相同（多半都不收紧、都等于巡航速度）时，显示近的那个。 */
  private static boolean closer(double curve, double distance, double bestCurve, Target best) {
    if (curve < bestCurve) {
      return true;
    }
    return curve == bestCurve
        && Double.isFinite(best.distanceBlocks())
        && distance < best.distanceBlocks();
  }

  /**
   * 距离 {@code distance} 处须降到 {@code endBps} 时，此刻最高能跑多快：编表同一条 S 形常用制动曲线，先扣掉制动力爬升期间 （{@code
   * reactionSeconds}）按当前车速走过的距离。不超过巡航速度；减速度不为正时没有约束。
   *
   * @param cruiseBps 制动开始前的巡航速度
   * @param decel 常用全制动的减速度
   * @param speedBps 当前车速
   */
  static double curveBps(
      double distance,
      double endBps,
      double cruiseBps,
      double decel,
      double reactionSeconds,
      double speedBps) {
    if (!(decel > 0.0) || !Double.isFinite(decel)) {
      return Double.POSITIVE_INFINITY;
    }
    double end = Math.max(0.0, endBps);
    double reach = Math.max(0.0, speedBps) * Math.max(0.0, reactionSeconds);
    double d = Math.max(0.0, distance - reach);
    // 只用到减速度：加速度一栏随便填个正数。
    return SpeedCeiling.brakingLimitBps(new SpeedCurve(decel, decel), cruiseBps, end, d);
  }

  private static double finiteOr(double value, double fallback) {
    return Double.isFinite(value) ? value : fallback;
  }
}
