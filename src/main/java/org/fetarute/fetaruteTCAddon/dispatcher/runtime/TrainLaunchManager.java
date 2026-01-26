package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.OptionalLong;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.SpeedCurveType;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfig;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;

/**
 * 列车控车执行器：把目标速度/加减速参数落到 TrainCarts。
 *
 * <p>职责：速度曲线、限速、发车/停车动作。计算目标速度上限/占用判定仍由调度层完成。
 *
 * <p>加减速优化：
 *
 * <ul>
 *   <li>减速：使用物理公式 v = √(2·a·d) 计算安全制动速度
 *   <li>加速：考虑前方约束点，避免"刚加速就要减速"
 * </ul>
 */
public final class TrainLaunchManager {

  private static final double TICKS_PER_SECOND = 20.0;

  /**
   * 应用控车动作：限速、加减速曲线、发车/停车。
   *
   * @param targetBps 目标速度（blocks/s，已考虑信号与边限速）
   * @param distanceOpt 可选“到下一节点的剩余距离”（用于提前减速）
   */
  public void applyControl(
      RuntimeTrainHandle train,
      TrainProperties properties,
      SignalAspect aspect,
      double targetBps,
      TrainConfig config,
      boolean allowLaunch,
      OptionalLong distanceOpt,
      ConfigManager.RuntimeSettings runtimeSettings) {
    if (properties == null || aspect == null || config == null || runtimeSettings == null) {
      return;
    }
    double accelBpt2 = toBlocksPerTickSquared(config.accelBps2());
    double decelBpt2 = toBlocksPerTickSquared(config.decelBps2());
    if (accelBpt2 > 0.0 && decelBpt2 > 0.0) {
      properties.setWaitAcceleration(accelBpt2, decelBpt2);
    }

    if (aspect == SignalAspect.STOP) {
      // STOP 信号：根据剩余距离与减速度计算“目标限速”，让 TrainCarts 逐步刹停
      double curveSpeed = resolveStopSpeed(train, config, distanceOpt, runtimeSettings);
      double curveSpeedBpt = toBlocksPerTick(curveSpeed);
      properties.setSpeedLimit(curveSpeedBpt);
      // 只在列车真正停下来后调用 stop() 确保完全停止
      if (train != null && curveSpeedBpt < 0.001 && !train.isMoving()) {
        train.stop();
      }
      return;
    }

    double adjustedBps = applySpeedCurve(targetBps, config, distanceOpt, runtimeSettings);
    double targetBpt = toBlocksPerTick(adjustedBps);
    properties.setSpeedLimit(targetBpt);
    // 非 STOP 信号：允许发车或对运动中列车补充能量
    if (train != null && allowLaunch) {
      if (!train.isMoving()) {
        // 静止时正常发车
        train.launch(targetBpt, accelBpt2);
      } else {
        // 运动中且速度低于目标时，补充能量加速（经过 waypoint 时维持速度）
        train.accelerateTo(targetBpt, accelBpt2);
      }
    }
  }

  /**
   * 基于剩余距离与制动能力，计算“提前减速”的目标限速。
   *
   * <p>PHYSICS 按 v = sqrt(2ad) 计算；其他曲线按剩余距离比例缩放速度。
   */
  private double applySpeedCurve(
      double targetBps,
      TrainConfig config,
      OptionalLong distanceOpt,
      ConfigManager.RuntimeSettings runtimeSettings) {
    if (!runtimeSettings.speedCurveEnabled()) {
      return targetBps;
    }
    if (distanceOpt == null || distanceOpt.isEmpty()) {
      return targetBps;
    }
    long distanceBlocks = distanceOpt.getAsLong();
    if (distanceBlocks <= 0) {
      return 0.0;
    }
    double decel = config.decelBps2();
    if (!Double.isFinite(decel) || decel <= 0.0) {
      return targetBps;
    }
    double effectiveDistance =
        Math.max(0.0, distanceBlocks - runtimeSettings.speedCurveEarlyBrakeBlocks());
    SpeedCurveType curveType = runtimeSettings.speedCurveType();
    if (curveType == SpeedCurveType.PHYSICS) {
      double curveLimit =
          Math.sqrt(2.0 * decel * effectiveDistance * runtimeSettings.speedCurveFactor());
      if (!Double.isFinite(curveLimit) || curveLimit <= 0.0) {
        return 0.0;
      }
      return Math.min(targetBps, curveLimit);
    }
    double brakingDistance =
        (targetBps * targetBps) / (2.0 * decel) * runtimeSettings.speedCurveFactor();
    if (!Double.isFinite(brakingDistance) || brakingDistance <= 0.0) {
      return targetBps;
    }
    double ratio = Math.max(0.0, Math.min(1.0, effectiveDistance / brakingDistance));
    if (ratio <= 0.0) {
      return 0.0;
    }
    double exponent =
        switch (curveType) {
          case LINEAR -> 1.0;
          case QUADRATIC -> 2.0;
          case CUBIC -> 3.0;
          default -> 1.0;
        };
    double adjusted = targetBps * Math.pow(ratio, exponent);
    return Math.min(targetBps, adjusted);
  }

  private double resolveStopSpeed(
      RuntimeTrainHandle train,
      TrainConfig config,
      OptionalLong distanceOpt,
      ConfigManager.RuntimeSettings runtimeSettings) {
    if (train == null || config == null || runtimeSettings == null) {
      return 0.0;
    }
    if (!runtimeSettings.speedCurveEnabled() || distanceOpt == null || distanceOpt.isEmpty()) {
      return 0.0;
    }
    double currentBps = train.currentSpeedBlocksPerTick() * TICKS_PER_SECOND;
    if (!Double.isFinite(currentBps) || currentBps <= 0.0) {
      return 0.0;
    }
    double decel = config.decelBps2();
    if (!Double.isFinite(decel) || decel <= 0.0) {
      return 0.0;
    }
    long distanceBlocks = distanceOpt.getAsLong();
    if (distanceBlocks <= 0) {
      return 0.0;
    }
    double effectiveDistance =
        Math.max(0.0, distanceBlocks - runtimeSettings.speedCurveEarlyBrakeBlocks());
    SpeedCurveType curveType = runtimeSettings.speedCurveType();
    double limit;
    if (curveType == SpeedCurveType.PHYSICS) {
      limit = Math.sqrt(2.0 * decel * effectiveDistance * runtimeSettings.speedCurveFactor());
    } else {
      double brakingDistance =
          (currentBps * currentBps) / (2.0 * decel) * runtimeSettings.speedCurveFactor();
      if (!Double.isFinite(brakingDistance) || brakingDistance <= 0.0) {
        return 0.0;
      }
      double ratio = Math.max(0.0, Math.min(1.0, effectiveDistance / brakingDistance));
      if (ratio <= 0.0) {
        return 0.0;
      }
      double exponent =
          switch (curveType) {
            case LINEAR -> 1.0;
            case QUADRATIC -> 2.0;
            case CUBIC -> 3.0;
            default -> 1.0;
          };
      limit = currentBps * Math.pow(ratio, exponent);
    }
    if (!Double.isFinite(limit) || limit <= 0.0) {
      return 0.0;
    }
    return Math.min(currentBps, limit);
  }

  /** blocks/s -> blocks/tick，非法输入返回 0。 */
  private static double toBlocksPerTick(double blocksPerSecond) {
    if (!Double.isFinite(blocksPerSecond) || blocksPerSecond <= 0.0) {
      return 0.0;
    }
    return blocksPerSecond / TICKS_PER_SECOND;
  }

  /** blocks/s^2 -> blocks/tick^2，非法输入返回 0。 */
  private static double toBlocksPerTickSquared(double blocksPerSecondSquared) {
    if (!Double.isFinite(blocksPerSecondSquared) || blocksPerSecondSquared <= 0.0) {
      return 0.0;
    }
    return blocksPerSecondSquared / (TICKS_PER_SECOND * TICKS_PER_SECOND);
  }
}
