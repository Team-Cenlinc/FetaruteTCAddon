package org.fetarute.fetaruteTCAddon.dispatcher.eta.model;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailTravelTimeModel;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * 动态旅行时间模型：综合边限速与加减速曲线计算 ETA。
 *
 * <h2>计算方式</h2>
 *
 * <ul>
 *   <li>取边的 {@code baseSpeedLimit}（若为 0 或未设则使用 fallback）
 *   <li>考虑初速（当前速度）与末速（下一边限速或 0）
 *   <li>若需加速/减速，用运动学公式计算实际用时
 * </ul>
 *
 * <h2>简化假设</h2>
 *
 * <ul>
 *   <li>加速度/减速度为常量（来自列车配置）
 *   <li>边内速度先加速至限速，再匀速，最后减速至末速
 *   <li>若距离不足以完成加减速，按三角形速度曲线近似
 * </ul>
 *
 * @see TrainMotionParams
 */
public final class DynamicTravelTimeModel implements RailTravelTimeModel {

  private final TrainMotionParams motionParams;
  private final double fallbackSpeedBps;

  /**
   * 构造动态模型。
   *
   * @param motionParams 列车运动参数（加减速）
   * @param fallbackSpeedBps 当边未设限速时的默认速度（blocks/s），必须为正
   */
  public DynamicTravelTimeModel(TrainMotionParams motionParams, double fallbackSpeedBps) {
    this.motionParams = Objects.requireNonNull(motionParams, "motionParams");
    if (!Double.isFinite(fallbackSpeedBps) || fallbackSpeedBps <= 0.0) {
      throw new IllegalArgumentException("fallbackSpeedBps 必须为正数");
    }
    this.fallbackSpeedBps = fallbackSpeedBps;
  }

  @Override
  public Optional<Duration> edgeTravelTime(RailGraph graph, RailEdge edge, NodeId from, NodeId to) {
    if (edge == null) {
      return Optional.empty();
    }
    int lengthBlocks = edge.lengthBlocks();
    if (lengthBlocks <= 0) {
      return Optional.empty();
    }

    double targetSpeed = resolveEdgeSpeed(edge);
    double seconds = computeTravelTime(lengthBlocks, targetSpeed, motionParams);
    if (!Double.isFinite(seconds) || seconds < 0.0) {
      return Optional.empty();
    }
    long millis = Math.round(seconds * 1000.0);
    return Optional.of(Duration.ofMillis(Math.max(0L, millis)));
  }

  /**
   * 带初速和末速的精确旅行时间计算。
   *
   * @param lengthBlocks 边长度（blocks）
   * @param initialSpeedBps 初速（blocks/s），非负
   * @param targetSpeedBps 边限速（blocks/s），正数
   * @param finalSpeedBps 末速（blocks/s），非负，通常为下一边限速或 0
   * @return 旅行时间（秒）
   */
  public double computeTravelTimeWithSpeeds(
      int lengthBlocks, double initialSpeedBps, double targetSpeedBps, double finalSpeedBps) {
    if (lengthBlocks <= 0) {
      return 0.0;
    }
    double accel = motionParams.accelBps2();
    double decel = motionParams.decelBps2();

    double v0 = Math.max(0.0, initialSpeedBps);
    double vMax = targetSpeedBps > 0 ? targetSpeedBps : fallbackSpeedBps;
    double vEnd = Math.max(0.0, Math.min(finalSpeedBps, vMax));
    double distance = lengthBlocks;

    // 阶段 1：从 v0 加速到 vMax
    double accelDist = 0.0;
    double accelTime = 0.0;
    if (v0 < vMax && accel > 0) {
      // s = (vMax^2 - v0^2) / (2 * a)
      accelDist = (vMax * vMax - v0 * v0) / (2.0 * accel);
      accelTime = (vMax - v0) / accel;
    }

    // 阶段 3：从 vMax 减速到 vEnd
    double decelDist = 0.0;
    double decelTime = 0.0;
    if (vMax > vEnd && decel > 0) {
      decelDist = (vMax * vMax - vEnd * vEnd) / (2.0 * decel);
      decelTime = (vMax - vEnd) / decel;
    }

    double totalAccelDecel = accelDist + decelDist;

    if (totalAccelDecel >= distance) {
      // 距离不足以完成完整加减速，使用三角形近似
      // 计算峰值速度 vPeak 使得加速距离 + 减速距离 = distance
      // s1 + s2 = distance, where s1 = (vPeak^2 - v0^2)/(2a), s2 = (vPeak^2 - vEnd^2)/(2d)
      // 解 vPeak：distance = (vPeak^2 - v0^2)/(2a) + (vPeak^2 - vEnd^2)/(2d)
      // vPeak^2 * (1/(2a) + 1/(2d)) = distance + v0^2/(2a) + vEnd^2/(2d)
      double coeffA = accel > 0 ? 1.0 / (2.0 * accel) : 0.0;
      double coeffD = decel > 0 ? 1.0 / (2.0 * decel) : 0.0;
      double sumCoeff = coeffA + coeffD;
      if (sumCoeff <= 0) {
        // 无法加速也无法减速，直接匀速
        return distance / Math.max(v0, 0.01);
      }
      double rhs = distance + (v0 * v0 * coeffA) + (vEnd * vEnd * coeffD);
      double vPeakSq = rhs / sumCoeff;
      if (vPeakSq < v0 * v0 || vPeakSq < vEnd * vEnd) {
        // 理论上不应出现，使用最保守估计
        vPeakSq = Math.max(v0 * v0, vEnd * vEnd);
      }
      double vPeak = Math.sqrt(vPeakSq);

      // 限制 vPeak 不超过 vMax
      vPeak = Math.min(vPeak, vMax);

      double t1 = accel > 0 ? Math.abs(vPeak - v0) / accel : 0.0;
      double t2 = decel > 0 ? Math.abs(vPeak - vEnd) / decel : 0.0;
      return t1 + t2;
    }

    // 正常情况：加速 + 匀速 + 减速
    double cruiseDist = distance - totalAccelDecel;
    double cruiseTime = cruiseDist / vMax;

    return accelTime + cruiseTime + decelTime;
  }

  /**
   * 简化版：假设初速为限速、末速为限速（边内稳定行驶）。
   *
   * <p>适用于无法获取实际速度时的估算。
   */
  double computeTravelTime(int lengthBlocks, double targetSpeedBps, TrainMotionParams params) {
    if (lengthBlocks <= 0) {
      return 0.0;
    }
    double speed = targetSpeedBps > 0 ? targetSpeedBps : fallbackSpeedBps;
    // 简化：不考虑加减速，直接匀速
    // 后续可改为使用 computeTravelTimeWithSpeeds(lengthBlocks, speed, speed, speed)
    return lengthBlocks / speed;
  }

  private double resolveEdgeSpeed(RailEdge edge) {
    double limit = edge.baseSpeedLimit();
    if (limit > 0.0 && Double.isFinite(limit)) {
      return limit;
    }
    return fallbackSpeedBps;
  }

  /**
   * 列车运动参数。
   *
   * @param accelBps2 加速度（blocks/s²），必须为正
   * @param decelBps2 减速度（blocks/s²），必须为正
   */
  public record TrainMotionParams(double accelBps2, double decelBps2) {
    public TrainMotionParams {
      if (!Double.isFinite(accelBps2) || accelBps2 <= 0.0) {
        throw new IllegalArgumentException("accelBps2 必须为正数");
      }
      if (!Double.isFinite(decelBps2) || decelBps2 <= 0.0) {
        throw new IllegalArgumentException("decelBps2 必须为正数");
      }
    }

    /** 默认参数（EMU 默认值）。 */
    public static TrainMotionParams defaults() {
      return new TrainMotionParams(1.0, 1.2);
    }
  }
}
