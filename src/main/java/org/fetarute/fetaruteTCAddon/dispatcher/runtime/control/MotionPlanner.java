package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * 运动规划器：统一处理列车加减速计算。
 *
 * <p>核心职责：
 *
 * <ul>
 *   <li>减速规划：根据到目标距离计算安全制动速度上限
 *   <li>加速规划：根据前方限速点距离限制加速上限，避免"刚加速就要减速"
 *   <li>平滑过渡：计算当前 tick 应达到的目标速度
 * </ul>
 *
 * <p>性能注意：本类为无状态工具类，所有方法均为纯函数。
 */
public final class MotionPlanner {

  private MotionPlanner() {}

  /**
   * 运动规划输入参数。
   *
   * @param currentSpeedBps 当前速度（blocks/s）
   * @param targetSpeedBps 目标速度上限（blocks/s，来自信号/边限速）
   * @param accelBps2 加速度（blocks/s²）
   * @param decelBps2 减速度（blocks/s²）
   * @param distanceToConstraint 到前方约束点的距离（blocks）
   * @param constraintSpeedBps 约束点的限速（blocks/s，如 approaching 限速）
   */
  public record MotionInput(
      double currentSpeedBps,
      double targetSpeedBps,
      double accelBps2,
      double decelBps2,
      OptionalLong distanceToConstraint,
      OptionalDouble constraintSpeedBps) {

    public MotionInput {
      Objects.requireNonNull(distanceToConstraint, "distanceToConstraint");
      Objects.requireNonNull(constraintSpeedBps, "constraintSpeedBps");
    }

    /** 快捷构造：无前方约束。 */
    public static MotionInput simple(
        double currentSpeedBps, double targetSpeedBps, double accelBps2, double decelBps2) {
      return new MotionInput(
          currentSpeedBps,
          targetSpeedBps,
          accelBps2,
          decelBps2,
          OptionalLong.empty(),
          OptionalDouble.empty());
    }

    /** 快捷构造：有前方约束。 */
    public static MotionInput withConstraint(
        double currentSpeedBps,
        double targetSpeedBps,
        double accelBps2,
        double decelBps2,
        long distanceBlocks,
        double constraintSpeedBps) {
      return new MotionInput(
          currentSpeedBps,
          targetSpeedBps,
          accelBps2,
          decelBps2,
          OptionalLong.of(distanceBlocks),
          OptionalDouble.of(constraintSpeedBps));
    }
  }

  /**
   * 运动规划输出结果。
   *
   * @param recommendedSpeedBps 建议的目标速度（blocks/s）
   * @param shouldBrake 是否应该制动（而非仅限速）
   * @param brakingDistanceBlocks 当前速度的制动距离（blocks）
   */
  public record MotionOutput(
      double recommendedSpeedBps, boolean shouldBrake, double brakingDistanceBlocks) {

    /** 空输出：保持当前状态。 */
    public static MotionOutput maintain(double currentSpeedBps) {
      return new MotionOutput(currentSpeedBps, false, 0.0);
    }
  }

  /**
   * 计算建议速度：综合考虑加速、减速与前方约束。
   *
   * <p>算法：
   *
   * <ol>
   *   <li>计算制动限速：到约束点的安全制动速度（v = √(2·a·d)）
   *   <li>计算加速限速：加速后能否在约束点前减速到目标
   *   <li>取最小值作为建议速度
   * </ol>
   */
  public static MotionOutput plan(MotionInput input) {
    if (input == null) {
      return MotionOutput.maintain(0.0);
    }

    double currentSpeed = Math.max(0.0, input.currentSpeedBps());
    double targetSpeed = Math.max(0.0, input.targetSpeedBps());
    double accel = Math.max(0.0, input.accelBps2());
    double decel = Math.max(0.001, input.decelBps2()); // 避免除零

    // 1. 基础目标速度
    double recommendedSpeed = targetSpeed;

    // 2. 计算当前速度的制动距离
    double brakingDistance = (currentSpeed * currentSpeed) / (2.0 * decel);

    // 3. 如果有前方约束，计算制动限速
    if (input.distanceToConstraint().isPresent() && input.constraintSpeedBps().isPresent()) {
      long distance = input.distanceToConstraint().getAsLong();
      double constraintSpeed = input.constraintSpeedBps().getAsDouble();

      if (distance <= 0) {
        // 已到达约束点，直接使用约束速度
        recommendedSpeed = Math.min(recommendedSpeed, constraintSpeed);
      } else {
        // 计算安全制动速度：从当前位置以该速度行驶，能在约束点前减速到 constraintSpeed
        // v² = v_end² + 2·a·d  =>  v = √(v_end² + 2·a·d)
        double safeSpeed = Math.sqrt(constraintSpeed * constraintSpeed + 2.0 * decel * distance);
        recommendedSpeed = Math.min(recommendedSpeed, safeSpeed);

        // 4. 加速优化：如果当前速度低于目标，检查加速是否合理
        if (currentSpeed < recommendedSpeed && accel > 0.0) {
          // 计算加速后的速度（假设加速 1 秒）
          double acceleratedSpeed = currentSpeed + accel;
          // 计算加速后的制动距离
          double acceleratedBrakingDistance =
              (acceleratedSpeed * acceleratedSpeed - constraintSpeed * constraintSpeed)
                  / (2.0 * decel);
          // 计算加速 1 秒行驶的距离
          double accelDistance = currentSpeed + 0.5 * accel; // s = v0·t + 0.5·a·t²，t=1
          // 如果加速后制动距离 + 加速距离 > 剩余距离，则限制加速
          if (acceleratedBrakingDistance + accelDistance > distance) {
            // 限制加速：使用当前安全速度
            recommendedSpeed = Math.min(recommendedSpeed, safeSpeed);
          }
        }
      }
    }

    // 5. 确保不超过目标速度
    recommendedSpeed = Math.min(recommendedSpeed, targetSpeed);

    // 6. 判断是否需要制动
    boolean shouldBrake = recommendedSpeed < currentSpeed - 0.1; // 容差 0.1 bps

    return new MotionOutput(
        Math.max(0.0, recommendedSpeed), shouldBrake, Math.max(0.0, brakingDistance));
  }

  /**
   * 计算从当前速度到目标速度需要的距离（加速或减速）。
   *
   * @param v0 初始速度（blocks/s）
   * @param v1 目标速度（blocks/s）
   * @param accelOrDecel 加速度或减速度（blocks/s²，正值）
   * @return 需要的距离（blocks）
   */
  public static double distanceForSpeedChange(double v0, double v1, double accelOrDecel) {
    if (accelOrDecel <= 0.0 || !Double.isFinite(accelOrDecel)) {
      return 0.0;
    }
    // d = |v1² - v0²| / (2·a)
    return Math.abs(v1 * v1 - v0 * v0) / (2.0 * accelOrDecel);
  }

  /**
   * 计算给定距离内能达到的最大速度（从 v0 加速，然后减速到 vEnd）。
   *
   * <p>用于三角形/梯形速度曲线计算。
   *
   * @param v0 初始速度（blocks/s）
   * @param vEnd 终点速度（blocks/s）
   * @param distance 总距离（blocks）
   * @param accel 加速度（blocks/s²）
   * @param decel 减速度（blocks/s²）
   * @return 可达的最大速度
   */
  public static double maxReachableSpeed(
      double v0, double vEnd, double distance, double accel, double decel) {
    if (distance <= 0.0 || accel <= 0.0 || decel <= 0.0) {
      return Math.max(v0, vEnd);
    }
    // 梯形曲线峰值速度：vMax² = (2·a·b·d + b·v0² + a·vEnd²) / (a + b)
    // 其中 a=accel, b=decel, d=distance
    double numerator = 2.0 * accel * decel * distance + decel * v0 * v0 + accel * vEnd * vEnd;
    double denominator = accel + decel;
    if (denominator <= 0.0) {
      return Math.max(v0, vEnd);
    }
    double vMaxSquared = numerator / denominator;
    if (vMaxSquared <= 0.0) {
      return Math.max(v0, vEnd);
    }
    return Math.sqrt(vMaxSquared);
  }

  /**
   * 平滑速度过渡：计算本 tick 应达到的速度。
   *
   * @param currentSpeedBps 当前速度
   * @param targetSpeedBps 目标速度
   * @param accelBps2 加速度
   * @param decelBps2 减速度
   * @param deltaTicks tick 数（通常为 1）
   * @return 本 tick 应达到的速度
   */
  public static double smoothTransition(
      double currentSpeedBps,
      double targetSpeedBps,
      double accelBps2,
      double decelBps2,
      int deltaTicks) {
    if (deltaTicks <= 0) {
      return currentSpeedBps;
    }
    double deltaSeconds = deltaTicks / 20.0;

    if (targetSpeedBps > currentSpeedBps) {
      // 加速
      double maxAccel = accelBps2 * deltaSeconds;
      return Math.min(targetSpeedBps, currentSpeedBps + maxAccel);
    } else if (targetSpeedBps < currentSpeedBps) {
      // 减速
      double maxDecel = decelBps2 * deltaSeconds;
      return Math.max(targetSpeedBps, currentSpeedBps - maxDecel);
    }
    return targetSpeedBps;
  }
}
