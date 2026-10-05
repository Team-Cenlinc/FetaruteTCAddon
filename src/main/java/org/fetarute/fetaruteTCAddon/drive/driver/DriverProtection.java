package org.fetarute.fetaruteTCAddon.drive.driver;

import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StopControlMode;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverDirective;

/**
 * 驾驶员控制的列车的保护包络（相当于 ATP）：把调度层的指令换算成此刻的容许速度，并决定是否介入。
 *
 * <ul>
 *   <li>容许速度：指令的容许速度、随距离收紧的包络、到停车点的常用制动曲线、进站曲线（最远停到越站阈值处）、指令过期时的限制速度，取最小；
 *   <li>超过容许速度一个容差：常用制动（至少 B4），降到容许速度以下一段回差才松开；
 *   <li>超出容许速度一个容差再加“超速比例”与容差中较大的一档，或闭塞硬停：紧急制动；调度要求停车只用常用制动；
 *   <li>停车信号下按紧急制动也停不到停车点前：立即停住（调度层的防撞保证优先于真实感）。中途站停过头不强制停车：越过越站阈值时由站台按越站处理； 终点站没有越站可言，最远只许越过停车点
 *       {@link DriverConfig#terminalOverrunBlocks()}：按紧急制动也停不进时紧急制动，到界限仍在动就立即停住；
 *   <li>行驶中达到容许速度、停稳时遇到停车信号或不允许起步：切断牵引。
 * </ul>
 *
 * <p>本类不依赖任何服务器对象。
 */
public final class DriverProtection {

  /** 介入方式，按严重程度递增。 */
  public enum Intervention {
    NONE,
    SERVICE,
    EMERGENCY,
    CLAMP
  }

  /**
   * 一次评估的输入。
   *
   * @param speedBps 当前车速
   * @param stopped 是否已停稳
   * @param directive 最近一次指令；还没收到时为 {@code null}
   * @param ticksSinceDirective 收到指令后过了多少 tick
   * @param travelledBlocks 收到指令后走了多远
   * @param serviceDecelBps2 常用全制动的减速度
   * @param emergencyDecelBps2 紧急制动的减速度
   * @param reactionSeconds 制动力爬升到位需要的时间（折算为反应距离）
   * @param serviceStopRequested 调度层要求停车（自动运行下的立即停车）
   * @param serviceLatched 上一次评估是否处于常用制动介入
   * @param stationRemainingBlocks 列车中心到前方停车点的距离（越过为负）；没有停车点时为 {@code NaN}
   * @param stationPrecise 停车点距离是站台按实际位置量出的（否则是估计，不据此强制停车）
   * @param stationTerminal 前方停车点是本交路的终点站：后面没有下一站可去，冲过头不能算越站继续开（尽头线再往前就出轨）
   */
  public record Input(
      double speedBps,
      boolean stopped,
      DriverDirective directive,
      long ticksSinceDirective,
      double travelledBlocks,
      double serviceDecelBps2,
      double emergencyDecelBps2,
      double reactionSeconds,
      boolean serviceStopRequested,
      boolean serviceLatched,
      double stationRemainingBlocks,
      boolean stationPrecise,
      boolean stationTerminal) {

    /** 没有前方停车点。 */
    public Input(
        double speedBps,
        boolean stopped,
        DriverDirective directive,
        long ticksSinceDirective,
        double travelledBlocks,
        double serviceDecelBps2,
        double emergencyDecelBps2,
        double reactionSeconds,
        boolean serviceStopRequested,
        boolean serviceLatched) {
      this(
          speedBps,
          stopped,
          directive,
          ticksSinceDirective,
          travelledBlocks,
          serviceDecelBps2,
          emergencyDecelBps2,
          reactionSeconds,
          serviceStopRequested,
          serviceLatched,
          Double.NaN,
          false,
          false);
    }
  }

  /**
   * 评估结果。
   *
   * @param intervention 介入方式
   * @param permittedBps 此刻的容许速度
   * @param tractionInhibited 是否切断牵引
   * @param handbackRequested 指令长时间中断，应停车交还自动运行
   */
  public record Decision(
      Intervention intervention,
      double permittedBps,
      boolean tractionInhibited,
      boolean handbackRequested) {}

  private DriverProtection() {}

  /** 评估此刻是否需要介入。 */
  public static Decision evaluate(Input in, DriverConfig config) {
    double v = Math.max(0.0, in.speedBps());
    DriverDirective d = in.directive();
    if (d == null) {
      // 还没收到指令：停着就不许起步，动着就按限制速度。
      double permitted = in.stopped() ? 0.0 : config.restrictedSpeedBps();
      Intervention iv =
          !in.stopped() && v > permitted + config.overspeedToleranceBps()
              ? Intervention.SERVICE
              : Intervention.NONE;
      return new Decision(iv, permitted, true, false);
    }
    double permitted = Math.max(0.0, d.permittedBps());
    double travelled = Math.max(0.0, in.travelledBlocks());
    if (d.envelope() != null && !d.envelope().isEmpty()) {
      permitted = Math.min(permitted, d.envelope().limitBps(travelled));
    }
    double remaining = Double.NaN;
    if (d.isStop()) {
      if (d.distanceBlocks().isPresent()) {
        remaining = d.distanceBlocks().getAsLong() - travelled;
        permitted =
            Math.min(
                permitted,
                brakingCurveBps(
                    remaining - config.stopMarginBlocks(),
                    in.serviceDecelBps2(),
                    in.reactionSeconds()));
      } else {
        permitted = 0.0;
      }
    }
    // 进站曲线。中途站：最远只许冲到越站阈值处，过了停车点不再收紧——冲过阈值就是越站，由站台处理，列车继续开（用户定的越站行为，
    // 防护不在阈值前把车刹停）。终点站：没有越站可言，界限是停车点后 terminalOverrunBlocks，曲线再留停车余量；
    // 站台量出实际距离后，按紧急制动也停不进界限就立即停住（尽头线再往前就出轨），估计距离只用常用制动。
    boolean station = Double.isFinite(in.stationRemainingBlocks());
    double terminalLeft = Double.NaN;
    if (station) {
      double distance;
      if (in.stationTerminal()) {
        terminalLeft = in.stationRemainingBlocks() + config.terminalOverrunBlocks();
        // 停车余量不超过界限的一半：界限配得比余量还小时，曲线也不能在停车点之前就降到 0。
        double margin = Math.min(config.stopMarginBlocks(), config.terminalOverrunBlocks() / 2.0);
        distance = Math.max(0.0, terminalLeft - margin);
      } else {
        distance = Math.max(0.0, in.stationRemainingBlocks()) + config.stopSkipBlocks();
      }
      permitted =
          Math.min(
              permitted, brakingCurveBps(distance, in.serviceDecelBps2(), in.reactionSeconds()));
    }
    boolean moving = !in.stopped();
    if (moving && in.ticksSinceDirective() > config.directiveStaleTicks()) {
      permitted = Math.min(permitted, config.restrictedSpeedBps());
    }
    // 紧急制动按行车许可判断；调度要求停车（含请求交还）只用常用制动停下。
    // 没有距离的停车信号（就地停车，例如区间停车点）在限制速度以下只用常用制动。
    double emergencyBasis =
        d.isStop() && d.distanceBlocks().isEmpty()
            ? Math.max(permitted, config.restrictedSpeedBps())
            : permitted;
    if (in.serviceStopRequested()) {
      permitted = 0.0;
    }
    boolean handback = moving && in.ticksSinceDirective() > config.staleHandbackTicks();

    Intervention iv = Intervention.NONE;
    if (moving) {
      if (d.isStop() && !Double.isNaN(remaining)) {
        double ebDistance =
            v * v / (2.0 * Math.max(0.01, in.emergencyDecelBps2()))
                + v * in.reactionSeconds() * 0.5;
        if (remaining <= 0.0 || ebDistance > remaining) {
          iv = Intervention.CLAMP;
        }
      }
      if (iv == Intervention.NONE && d.isStop() && d.stopMode() == StopControlMode.HARD_STOP) {
        iv = Intervention.EMERGENCY;
      }
      if (iv == Intervention.NONE && !Double.isNaN(terminalLeft)) {
        double ebDistance =
            v * v / (2.0 * Math.max(0.01, in.emergencyDecelBps2()))
                + v * in.reactionSeconds() * 0.5;
        if (in.stationPrecise() && terminalLeft <= 0.0) {
          // 终点站：到了界限还在动——立即停住，保证不越过界限。
          iv = Intervention.CLAMP;
        } else if (in.stationPrecise() && ebDistance > terminalLeft) {
          // 紧急制动也停不进界限：先紧急制动（估计距离切到实测时可能突然变短，不直接把速度归零），到界限仍在动再立即停住。
          iv = Intervention.EMERGENCY;
        } else if (terminalLeft <= 0.0) {
          // 估计距离已到界限：不论多慢都制动，容差内的低速也不能再往前溜。
          iv = Intervention.SERVICE;
        }
      }
      if (iv == Intervention.NONE) {
        double tolerance = config.overspeedToleranceBps();
        // 紧急制动线比常用制动线再高出“超速比例”与容差中较大的一档，低速时不至于一超就紧急制动。
        double emergencyMargin =
            tolerance + Math.max(emergencyBasis * config.emergencyOverspeedRatio(), tolerance);
        if (v > emergencyBasis + emergencyMargin) {
          iv = Intervention.EMERGENCY;
        } else if (v > permitted + tolerance
            || (in.serviceStopRequested() && v > 0.0)
            || (in.serviceLatched()
                && v > Math.max(0.0, permitted - config.serviceReleaseHysteresisBps()))) {
          iv = Intervention.SERVICE;
        }
      }
    }
    // 牵引切除：动着时到了容许速度就不再给牵引（超过容差才制动）；停着时停车信号、不许起步、要求停车都不给牵引。
    boolean inhibited =
        iv != Intervention.NONE
            || (moving && v >= permitted)
            || (in.stopped() && (d.isStop() || !d.allowLaunch() || in.serviceStopRequested()));
    return new Decision(iv, permitted, inhibited, handback);
  }

  /**
   * 剩余距离内用给定减速度刹停的最高速度，扣除制动力爬升期间走过的距离。
   *
   * @return 剩余距离不为正时为 0
   */
  static double brakingCurveBps(double remainingBlocks, double decelBps2, double reactionSeconds) {
    if (!(remainingBlocks > 0.0) || !(decelBps2 > 0.0)) {
      return 0.0;
    }
    // v·t + v²/(2a) = s 的正根。
    double t = Math.max(0.0, reactionSeconds);
    double a = decelBps2;
    return Math.max(0.0, -a * t + Math.sqrt(a * a * t * t + 2.0 * a * remainingBlocks));
  }
}
