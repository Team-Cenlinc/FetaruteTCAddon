package org.fetarute.fetaruteTCAddon.drive.driver;

import java.util.List;
import java.util.Objects;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.SignalLookahead;

/**
 * 驾驶调度列车时的行车引导：前方最要紧的目标（停车信号的授权末端、车站停车点、限速降低处）、离它多远、建议速度，以及该不该开始制动。
 *
 * <p>对每个候选目标，用“舒适制动”（常用全制动乘 {@link DriverGuidanceConfig#adviceBrakeRatio()}）反推此刻最高能跑多快，
 * 反推出来最低的那个就是要显示的目标。建议速度取调度目标速度、容许速度减余量与这个反推速度中最小的一个。 车速超出建议速度一个容差、且起作用的是前方目标（不是此刻的限速）时，提示开始制动；
 * 降回建议速度以下才解除。
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
    double cap = Math.max(0.0, permitted - config.adviceMarginBps());
    if (Double.isFinite(in.requestedBps()) && in.requestedBps() >= 0.0) {
      cap = Math.min(cap, in.requestedBps());
    }
    double decel = Math.max(0.0, in.serviceDecelBps2()) * config.adviceBrakeRatio();
    double range = config.rangeBlocks();

    Target best = new Target(TargetKind.CLEAR, Double.NaN, permitted);
    double bestCurve = Double.POSITIVE_INFINITY;
    if (Double.isFinite(in.stopSignalBlocks())) {
      double distance = Math.max(0.0, in.stopSignalBlocks() - in.stopMarginBlocks());
      double curve = curveBps(distance, 0.0, decel, in.reactionSeconds());
      if (distance <= range && curve < bestCurve) {
        best = new Target(TargetKind.STOP_SIGNAL, distance, 0.0);
        bestCurve = curve;
      }
    }
    if (Double.isFinite(in.stationBlocks())) {
      double distance = Math.max(0.0, in.stationBlocks());
      double curve = curveBps(distance, 0.0, decel, in.reactionSeconds());
      if (distance <= range && curve < bestCurve) {
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
      double curve = curveBps(distance, edge.speedLimitBps(), decel, in.reactionSeconds());
      if (curve < bestCurve) {
        best = new Target(TargetKind.SPEED_LIMIT, distance, edge.speedLimitBps());
        bestCurve = curve;
      }
    }
    double suggested = Math.max(0.0, Math.min(cap, bestCurve));
    boolean binding = bestCurve < cap;
    double speed = Math.max(0.0, in.speedBps());
    double threshold = in.wasAdvising() ? suggested : suggested + config.brakeAdviceToleranceBps();
    boolean brake = !in.stopped() && binding && speed > MOVING_BPS && speed > threshold;
    return new Advice(best, suggested, brake);
  }

  /**
   * 距离 {@code distance} 处须降到 {@code endBps} 时，用减速度 {@code decel} 此刻最高能跑多快（扣除制动力爬升期间走过的距离）。
   *
   * <p>解 {@code v·t + (v² − v_end²)/(2a) = d} 的正根；减速度不为正时没有约束。
   */
  static double curveBps(double distance, double endBps, double decel, double reactionSeconds) {
    if (!(decel > 0.0)) {
      return Double.POSITIVE_INFINITY;
    }
    double end = Math.max(0.0, endBps);
    double d = Math.max(0.0, distance);
    double t = Math.max(0.0, reactionSeconds);
    double v = -decel * t + Math.sqrt(decel * decel * t * t + 2.0 * decel * d + end * end);
    return Math.max(end, v);
  }

  private static double finiteOr(double value, double fallback) {
    return Double.isFinite(value) ? value : fallback;
  }
}
