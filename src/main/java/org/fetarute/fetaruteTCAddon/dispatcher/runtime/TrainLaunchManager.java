package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.SpeedCurveType;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfig;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.SpeedEnvelope;
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
  private static final double RESUME_TRACTION_TOLERANCE_RATIO = 0.01;
  private static final String TAG_LAST_LAUNCH_AT = "FTA_LAST_LAUNCH_AT";
  private static final String TAG_PENDING_LAUNCH_COMMAND = "FTA_PENDING_LAUNCH_COMMAND";
  private static final String TAG_LAST_SPEED_CMD_BPS = "FTA_LAST_SPEED_CMD_BPS";
  private static final String TAG_LAST_SPEED_CMD_AT = "FTA_LAST_SPEED_CMD_AT";

  /** 逐 tick 斜坡在未被刷新时至少保留的 tick 数；实际取三个调度周期与它的较大者。 */
  private static final int MIN_SPEED_RAMP_TTL_TICKS = 40;

  private final SpeedLimitRamp speedLimitRamp;

  /** 使用 Bukkit 调度器驱动的逐 tick 限速斜坡。 */
  public TrainLaunchManager() {
    this(new SpeedLimitRamp());
  }

  /**
   * @param speedLimitRamp 逐 tick 限速斜坡
   */
  public TrainLaunchManager(SpeedLimitRamp speedLimitRamp) {
    this.speedLimitRamp = java.util.Objects.requireNonNull(speedLimitRamp, "speedLimitRamp");
  }

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
   * @param launchCommandAccepted 允许发车时，底层已接受 launch action、已有待执行 action 或列车已经移动；非发车控制为 {@code
   *     false}
   */
  public record ControlApplicationResult(
      double requestedTargetBps,
      OptionalDouble speedCurveLimitBps,
      double finalTargetBps,
      String finalLimiterSource,
      boolean launchCommandAccepted) {
    public ControlApplicationResult(
        double requestedTargetBps,
        OptionalDouble speedCurveLimitBps,
        double finalTargetBps,
        String finalLimiterSource) {
      this(requestedTargetBps, speedCurveLimitBps, finalTargetBps, finalLimiterSource, false);
    }

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
   * @implNote 运动中列车在信号变化/强制刷新时补充控车动作。{@link TrainProperties#setSpeedLimit(double)}
   *     降低后下一物理步即截速，TrainCarts 不会平滑它；周期之间的平滑下调见 {@link SpeedLimitRamp}。
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
    return applyControl(
        train,
        properties,
        aspect,
        targetBps,
        config,
        allowLaunch,
        distanceOpt,
        launchFallbackDirection,
        runtimeSettings,
        StopControlMode.BRAKING_TO_PLANNED_STOP);
  }

  /**
   * 应用控车动作：限速、加减速曲线、发车/停车。
   *
   * <p>调用方没有速度上下文（不带包络），等同于 {@code speedEnvelope == null}。
   *
   * @param stopMode STOP 信号的落地模式；硬 STOP 不允许速度曲线和 launch action
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
      ConfigManager.RuntimeSettings runtimeSettings,
      StopControlMode stopMode) {
    return applyControl(
        train,
        properties,
        aspect,
        targetBps,
        config,
        allowLaunch,
        distanceOpt,
        launchFallbackDirection,
        runtimeSettings,
        stopMode,
        null);
  }

  /**
   * 应用控车动作：限速、加减速曲线、发车/停车。
   *
   * <p>非 STOP 命令分两类：
   *
   * <ul>
   *   <li>带包络（{@code speedEnvelope != null}，信号 tick 的完整判定）：命令即权威，运行中列车交给 {@link SpeedLimitRamp}
   *       在周期之间沿包络继续下调；
   *   <li>不带包络（过节点时的推进放行等）：调用方只知道“可以走”，不知道前方要进站或有慢速边，所以不得越过斜坡登记的保持约束
   *       （到下一停车点的速度天花板），避免把正在减速的车先抬回线路速度、下一拍再砍回去。其余情况照旧放行并补牵引——周期命令值里有上调限幅的滞后， 拿它封顶会扣住减速解除后唯一的补牵引。
   * </ul>
   *
   * @param stopMode STOP 信号的落地模式；硬 STOP 不允许速度曲线和 launch action
   * @param speedEnvelope 调度层给出的、从车头量起的随距离约束；{@code null} 表示调用方没有速度上下文
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
      ConfigManager.RuntimeSettings runtimeSettings,
      StopControlMode stopMode,
      SpeedEnvelope speedEnvelope) {
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
      speedLimitRamp.release(train);
      clearPendingLaunchCommand(properties);
      StopControlMode resolvedStopMode =
          stopMode == null ? StopControlMode.BRAKING_TO_PLANNED_STOP : stopMode;
      if (resolvedStopMode == StopControlMode.HARD_STOP) {
        rememberSpeedCommand(properties, 0.0);
        properties.setSpeedLimit(0.0);
        if (train != null) {
          train.stopHard();
        }
        return new ControlApplicationResult(targetBps, OptionalDouble.empty(), 0.0, "hard_stop");
      }
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
    double heldBps = curveAdjustedBps;
    if (speedEnvelope == null) {
      OptionalDouble hold = speedLimitRamp.holdLimitBps(train);
      if (hold.isPresent() && hold.getAsDouble() < heldBps) {
        heldBps = hold.getAsDouble();
      }
    }
    boolean resumeTraction = !allowLaunch && shouldResumeTraction(train, toBlocksPerTick(heldBps));
    double adjustedBps =
        applySpeedCommandRateLimit(
            train,
            properties,
            heldBps,
            config,
            runtimeSettings,
            false,
            // 发车/信号放行/运行中补牵引都由 launch 动作按加速度爬升，速度上限不再“上行限幅”二次压速，避免起步或提速过慢。
            allowLaunch || resumeTraction);
    double targetBpt = toBlocksPerTick(adjustedBps);
    properties.setSpeedLimit(targetBpt);
    if (speedEnvelope == null) {
      speedLimitRamp.acknowledgeWrite(train, properties);
    } else if (train != null && train.isMoving() && !speedEnvelope.isEmpty()) {
      speedLimitRamp.arm(
          train, properties, adjustedBps, speedEnvelope, speedRampTtlTicks(runtimeSettings));
    } else {
      speedLimitRamp.release(train);
    }
    boolean launchCommandAccepted = false;
    // 非 STOP 信号：允许发车或对运动中列车补充能量
    if (train != null) {
      if (train.isMoving()) {
        // 物理运动是唯一能消费待发车命令的正向证据；此后相同授权无需保留发车去重标记。
        clearPendingLaunchCommand(properties);
        if (allowLaunch) {
          launchCommandAccepted = true;
        }
        boolean needsMovingControl = shouldIssueMovingControl(train, targetBpt);
        if (allowLaunch || needsMovingControl || resumeTraction) {
          double controlAcceleration = needsMovingControl ? decelBpt2 : accelBpt2;
          // 运动中：放行/信号变化时补充牵引；目标回升（驶过慢速边、授权延伸）时同样补牵引——TrainCarts 列车
          // 不会因为 speedLimit 调高就自己加速。目标速度下降时这次 launch 并不起平滑作用——speedLimit 已在下一
          // 物理步截速，launch 起点被夹到新上限后立即完成——它的作用是把 TrainCarts 速度向量重置为目标值，
          // 清掉被截住但仍留在向量里的旧速度，免得之后限速一抬就瞬间弹回。减速的平滑由 SpeedLimitRamp 负责。
          train.accelerateTo(targetBpt, controlAcceleration);
        }
      } else {
        // 静止时需要发车：受 allowLaunch 和冷却时间限制
        String commandSignature =
            pendingLaunchCommandSignature(aspect, targetBpt, accelBpt2, launchFallbackDirection);
        if (!allowLaunch) {
          clearPendingLaunchCommand(properties);
        } else if (hasPendingLaunchCommand(properties, commandSignature)) {
          // 相同授权已由 TrainCarts 接受。不能以“仍静止”作为重置 action 队列的理由；必须等待
          // 物理进度、STOP/撤销或新的授权参数，才能再次向执行层写入命令。
          launchCommandAccepted = true;
        } else if (canIssueLaunch(properties, runtimeSettings)) {
          launchCommandAccepted =
              train.requestLaunchWithFallback(launchFallbackDirection, targetBpt, accelBpt2);
          if (launchCommandAccepted) {
            markLaunchIssued(properties, runtimeSettings);
            rememberPendingLaunchCommand(properties, commandSignature);
          }
        }
      }
    }
    String limiterSource = "none";
    if (adjustedBps < heldBps - 1.0e-6) {
      limiterSource = "speed_command_rate_limit";
    } else if (heldBps < curveAdjustedBps - 1.0e-6) {
      limiterSource = "speed_ceiling_hold";
    } else if (speedCurveLimit.isPresent()) {
      limiterSource = "speed_curve";
    }
    return new ControlApplicationResult(
        targetBps, speedCurveLimit, adjustedBps, limiterSource, launchCommandAccepted);
  }

  /**
   * 运行中列车的目标速度明显高于当前车速时补牵引。
   *
   * <p>"明显"与 {@link TrainCartsRuntimeHandle#accelerateTo} 的已接近目标判定一致（目标的 1%，至少 {@value
   * #MOVING_CONTROL_EPSILON_BPT} 格/tick）。身上挂着别的 TrainCarts 动作（停站等待、居中）时不补：launch 会排在它后面执行。
   */
  private boolean shouldResumeTraction(RuntimeTrainHandle train, double targetBlocksPerTick) {
    if (train == null || !train.isMoving() || !Double.isFinite(targetBlocksPerTick)) {
      return false;
    }
    double current = train.currentSpeedBlocksPerTick();
    double tolerance =
        Math.max(MOVING_CONTROL_EPSILON_BPT, targetBlocksPerTick * RESUME_TRACTION_TOLERANCE_RATIO);
    return Double.isFinite(current)
        && targetBlocksPerTick > current + tolerance
        && !train.hasForeignAction();
  }

  /** 撤销该车的逐 tick 限速斜坡（硬停、重发等绕过 {@link #applyControl} 的控车路径调用）。 */
  public void releaseSpeedRamp(RuntimeTrainHandle train) {
    speedLimitRamp.release(train);
  }

  private static int speedRampTtlTicks(ConfigManager.RuntimeSettings runtimeSettings) {
    return Math.max(MIN_SPEED_RAMP_TTL_TICKS, runtimeSettings.dispatchTickIntervalTicks() * 3);
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

  /** 只读检查同一列车是否已离开 launch cooldown 窗口。 */
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
    return true;
  }

  /**
   * 在 TrainCarts 明确接受 launch 动作后记录冷却起点。
   *
   * <p>被底层拒绝的动作不能消耗 cooldown，否则列车会在最需要重试的静止窗口被调度层自行抑制。
   */
  private void markLaunchIssued(
      TrainProperties properties, ConfigManager.RuntimeSettings runtimeSettings) {
    if (properties == null
        || runtimeSettings == null
        || runtimeSettings.launchCooldownTicks() <= 0) {
      return;
    }
    TrainTagHelper.writeTag(
        properties, TAG_LAST_LAUNCH_AT, String.valueOf(System.currentTimeMillis()));
  }

  /**
   * 判断静止列车是否仍持有与本次授权完全相同的已接受发车命令。
   *
   * <p>该标记不是 Movement Authority，也不替代 Gate Queue；它只记录执行层已经接受过的幂等 action。列车发生物理运动、收到
   * STOP/撤销，或授权参数变化后， 调用方必须重新进入正常的调度判定。
   */
  private static boolean hasPendingLaunchCommand(
      TrainProperties properties, String commandSignature) {
    return TrainTagHelper.readTagValue(properties, TAG_PENDING_LAUNCH_COMMAND)
        .filter(commandSignature::equals)
        .isPresent();
  }

  /** 记录已被 TrainCarts 接受、但尚未由物理进度消费的发车命令。 */
  private static void rememberPendingLaunchCommand(
      TrainProperties properties, String commandSignature) {
    TrainTagHelper.writeTag(properties, TAG_PENDING_LAUNCH_COMMAND, commandSignature);
  }

  /** 清除当前列车的待发车幂等标记。 */
  private static void clearPendingLaunchCommand(TrainProperties properties) {
    TrainTagHelper.removeTagKey(properties, TAG_PENDING_LAUNCH_COMMAND);
  }

  /**
   * 生成执行层发车命令的稳定身份。
   *
   * <p>只采用实际写入 TrainCarts 的参数；时间戳、周期编号和日志字段均不得进入签名，否则同一授权会再次变成新命令。
   */
  private static String pendingLaunchCommandSignature(
      SignalAspect aspect,
      double targetBlocksPerTick,
      double accelBlocksPerTickSquared,
      java.util.Optional<org.bukkit.block.BlockFace> fallbackDirection) {
    String aspectName = aspect == null ? "UNKNOWN" : aspect.name();
    String fallback =
        fallbackDirection == null || fallbackDirection.isEmpty()
            ? "-"
            : fallbackDirection.get().name();
    return aspectName
        + ':'
        + Long.toUnsignedString(Double.doubleToLongBits(targetBlocksPerTick), 16)
        + ':'
        + Long.toUnsignedString(Double.doubleToLongBits(accelBlocksPerTickSquared), 16)
        + ':'
        + fallback;
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
      // 降低 speedLimit 是安全约束，不能被命令限幅延迟。注意 TrainCarts 不会平滑 speedLimit 的下调
      // （WaitAcceleration 只管跟车/互斥区），周期之间的平滑下调由 SpeedLimitRamp 沿速度包络完成。
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
