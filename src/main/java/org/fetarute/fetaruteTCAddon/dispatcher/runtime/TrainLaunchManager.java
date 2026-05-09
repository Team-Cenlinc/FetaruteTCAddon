package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.OptionalDouble;
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
  private static final long TICK_MILLIS = 50L;
  private static final double MOVING_CONTROL_EPSILON_BPT = 0.005;
  private static final String TAG_LAST_LAUNCH_AT = "FTA_LAST_LAUNCH_AT";
  private static final String TAG_LAST_SPEED_CMD_BPS = "FTA_LAST_SPEED_CMD_BPS";
  private static final String TAG_LAST_SPEED_CMD_AT = "FTA_LAST_SPEED_CMD_AT";

  /**
   * 一次控车命令的速度落地结果。
   *
   * <p>调度层已经计算出信号、边限速、移动授权等上限；执行层仍可能因为停车速度曲线或速度命令限幅进一步下压。该结果用于 {@code /fta train debug} 解释最终
   * speedLimit 的来源。
   *
   * @param requestedTargetBps 调度层传入的目标速度
   * @param speedCurveLimitBps 执行层速度曲线限制后的速度；未触发时为空
   * @param finalTargetBps 最终写入 TrainCarts 前的速度
   * @param finalLimiterSource 执行层最终限制来源
   */
  public record ControlApplicationResult(
      double requestedTargetBps,
      OptionalDouble speedCurveLimitBps,
      double finalTargetBps,
      String finalLimiterSource) {
    public ControlApplicationResult {
      speedCurveLimitBps = speedCurveLimitBps == null ? OptionalDouble.empty() : speedCurveLimitBps;
      finalLimiterSource =
          finalLimiterSource == null || finalLimiterSource.isBlank()
              ? "none"
              : finalLimiterSource.trim();
    }
  }

  /**
   * 应用控车动作：限速、加减速曲线、发车/停车。
   *
   * @param targetBps 目标速度（blocks/s，已考虑信号与边限速）
   * @param distanceOpt 可选“到下一节点的剩余距离”（用于提前减速）
   * @implNote 运动中列车在信号变化/强制刷新时补充控车动作；若目标速度低于当前速度，也会下发一次平滑减速动作， 避免 approach/限速只硬切 {@link
   *     TrainProperties#setSpeedLimit(double)}。
   */
  public ControlApplicationResult applyControl(
      RuntimeTrainHandle train,
      TrainProperties properties,
      SignalAspect aspect,
      double targetBps,
      TrainConfig config,
      boolean allowLaunch,
      OptionalLong distanceOpt,
      java.util.Optional<org.bukkit.block.BlockFace> launchFallbackDirection,
      ConfigManager.RuntimeSettings runtimeSettings) {
    if (properties == null || aspect == null || config == null || runtimeSettings == null) {
      return new ControlApplicationResult(
          targetBps, OptionalDouble.empty(), Math.max(0.0, targetBps), "none");
    }
    double accelBpt2 = toBlocksPerTickSquared(config.accelBps2());
    double decelBpt2 = toBlocksPerTickSquared(config.decelBps2());
    if (accelBpt2 > 0.0 && decelBpt2 > 0.0) {
      properties.setWaitAcceleration(accelBpt2, decelBpt2);
    }

    if (aspect == SignalAspect.STOP) {
      // STOP 是闭塞硬约束，但控车仍按剩余授权距离做制动曲线；距离缺失或已到停车点时才硬停。
      double curveSpeed =
          Math.max(0.0, resolveStopSpeed(train, config, distanceOpt, runtimeSettings));
      rememberSpeedCommand(properties, curveSpeed);
      double curveSpeedBpt = toBlocksPerTick(curveSpeed);
      properties.setSpeedLimit(curveSpeedBpt);
      if (train != null) {
        if (curveSpeedBpt < 0.001) {
          train.stop();
        } else if (train.isMoving()) {
          train.accelerateTo(curveSpeedBpt, decelBpt2);
        }
      }
      OptionalDouble curveLimit =
          runtimeSettings.speedCurveEnabled() && distanceOpt != null && distanceOpt.isPresent()
              ? OptionalDouble.of(curveSpeed)
              : OptionalDouble.empty();
      return new ControlApplicationResult(
          targetBps, curveLimit, curveSpeed, curveSpeed > 0.0 ? "stop_curve" : "stop");
    }

    double curveAdjustedBps = applySpeedCurve(targetBps, config, distanceOpt, runtimeSettings);
    OptionalDouble speedCurveLimit =
        curveAdjustedBps < Math.max(0.0, targetBps) - 1.0e-6
            ? OptionalDouble.of(curveAdjustedBps)
            : OptionalDouble.empty();
    double adjustedBps = curveAdjustedBps;
    adjustedBps =
        applySpeedCommandRateLimit(
            train,
            properties,
            adjustedBps,
            config,
            runtimeSettings,
            false,
            // 发车/信号放行瞬间不应再被“上行限幅”二次压速，避免列车起步过慢。
            allowLaunch);
    double targetBpt = toBlocksPerTick(adjustedBps);
    properties.setSpeedLimit(targetBpt);
    // 非 STOP 信号：允许发车或对运动中列车补充能量
    if (train != null) {
      if (!train.isMoving()) {
        // 静止时需要发车：受 allowLaunch 和冷却时间限制
        if (allowLaunch && canIssueLaunch(properties, runtimeSettings)) {
          train.launchWithFallback(launchFallbackDirection, targetBpt, accelBpt2);
        }
      } else {
        boolean needsMovingControl = shouldIssueMovingControl(train, targetBpt);
        if (allowLaunch || needsMovingControl) {
          double controlAcceleration = needsMovingControl ? decelBpt2 : accelBpt2;
          // 运动中：放行/信号变化时补充牵引；目标速度下降时也下发一次 launch，让 TrainCarts
          // 按加减速度平滑收敛到 approach/限速目标，而不是只硬切 speedLimit。
          train.accelerateTo(targetBpt, controlAcceleration);
        }
      }
    }
    String limiterSource = "none";
    if (adjustedBps < curveAdjustedBps - 1.0e-6) {
      limiterSource = "speed_command_rate_limit";
    } else if (speedCurveLimit.isPresent()) {
      limiterSource = "speed_curve";
    }
    return new ControlApplicationResult(targetBps, speedCurveLimit, adjustedBps, limiterSource);
  }

  /** 判断运动中列车是否需要补发控速动作。 */
  private boolean shouldIssueMovingControl(RuntimeTrainHandle train, double targetBlocksPerTick) {
    if (train == null) {
      return false;
    }
    double current = train.currentSpeedBlocksPerTick();
    return Double.isFinite(current)
        && Double.isFinite(targetBlocksPerTick)
        && current > targetBlocksPerTick + MOVING_CONTROL_EPSILON_BPT;
  }

  /** 节流：同一列车在 cooldown 窗口内最多下发一次 launch/加速动作。 */
  private boolean canIssueLaunch(
      TrainProperties properties, ConfigManager.RuntimeSettings runtimeSettings) {
    if (properties == null || runtimeSettings == null) {
      return false;
    }
    int cooldownTicks = runtimeSettings.launchCooldownTicks();
    if (cooldownTicks <= 0) {
      return true;
    }
    long now = System.currentTimeMillis();
    long last = TrainTagHelper.readLongTag(properties, TAG_LAST_LAUNCH_AT).orElse(0L);
    if (last > 0L && now - last < cooldownTicks * TICK_MILLIS) {
      return false;
    }
    TrainTagHelper.writeTag(properties, TAG_LAST_LAUNCH_AT, String.valueOf(now));
    return true;
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
    if (effectiveDistance <= 0.0) {
      return 0.0;
    }
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

  /**
   * 速度命令限幅与迟滞。
   *
   * <p>目标：
   *
   * <ul>
   *   <li>限制单次速度指令变化幅度，避免瞬时剧烈跳变导致附件模型抖动或解挂风险；
   *   <li>对微小速度变化施加迟滞，抑制道岔密集区的高频抖动。
   * </ul>
   */
  private double applySpeedCommandRateLimit(
      RuntimeTrainHandle train,
      TrainProperties properties,
      double requestedBps,
      TrainConfig config,
      ConfigManager.RuntimeSettings runtimeSettings,
      boolean bypassHysteresis,
      boolean bypassAccelerationLimit) {
    if (properties == null || config == null || runtimeSettings == null) {
      return Math.max(0.0, requestedBps);
    }
    double requested = Math.max(0.0, requestedBps);
    long nowMs = System.currentTimeMillis();
    long lastAtMs = TrainTagHelper.readLongTag(properties, TAG_LAST_SPEED_CMD_AT).orElse(nowMs);
    double deltaSeconds = Math.max(0.05, (nowMs - lastAtMs) / 1000.0);
    java.util.Optional<Double> lastCommandOpt =
        TrainTagHelper.readDoubleTag(properties, TAG_LAST_SPEED_CMD_BPS).filter(Double::isFinite);

    // 首次下发不做限幅，避免从 0 速起步被过度限制。
    if (lastCommandOpt.isEmpty()) {
      TrainTagHelper.writeTag(properties, TAG_LAST_SPEED_CMD_BPS, Double.toString(requested));
      TrainTagHelper.writeTag(properties, TAG_LAST_SPEED_CMD_AT, Long.toString(nowMs));
      return requested;
    }

    double accelLimitPerSecond =
        Math.max(0.0, config.accelBps2() * runtimeSettings.speedCommandAccelFactor());
    double referenceSpeed =
        lastCommandOpt.orElseGet(() -> resolveCurrentSpeedBps(train, requested));

    double limited = requested;
    boolean lowering = requested < referenceSpeed;
    if (requested > referenceSpeed) {
      if (bypassAccelerationLimit) {
        limited = requested;
      } else {
        double maxIncrease = accelLimitPerSecond * deltaSeconds;
        limited = Math.min(requested, referenceSpeed + maxIncrease);
      }
    } else if (lowering) {
      // 降低 speedLimit 是安全约束，不能被命令限幅延迟；实际平滑制动由 TrainCarts WaitAcceleration 接管。
      limited = requested;
    }

    double hysteresis = Math.max(0.0, runtimeSettings.speedCommandHysteresisBps());
    if (!lowering && !bypassHysteresis && Math.abs(limited - referenceSpeed) < hysteresis) {
      limited = referenceSpeed;
    }
    limited = Math.max(0.0, limited);

    TrainTagHelper.writeTag(properties, TAG_LAST_SPEED_CMD_BPS, Double.toString(limited));
    TrainTagHelper.writeTag(properties, TAG_LAST_SPEED_CMD_AT, Long.toString(nowMs));
    return limited;
  }

  private void rememberSpeedCommand(TrainProperties properties, double speedBps) {
    if (properties == null) {
      return;
    }
    double safeSpeed = Math.max(0.0, speedBps);
    long nowMs = System.currentTimeMillis();
    TrainTagHelper.writeTag(properties, TAG_LAST_SPEED_CMD_BPS, Double.toString(safeSpeed));
    TrainTagHelper.writeTag(properties, TAG_LAST_SPEED_CMD_AT, Long.toString(nowMs));
  }

  private double resolveCurrentSpeedBps(RuntimeTrainHandle train, double fallbackBps) {
    if (train == null) {
      return fallbackBps;
    }
    double current = train.currentSpeedBlocksPerTick() * TICKS_PER_SECOND;
    if (!Double.isFinite(current) || current < 0.0) {
      return fallbackBps;
    }
    return current;
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
