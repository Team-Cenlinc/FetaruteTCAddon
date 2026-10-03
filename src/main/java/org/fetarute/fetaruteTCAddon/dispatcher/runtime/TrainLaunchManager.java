package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.SpeedCurveType;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfig;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.ControlAuthority;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverDirective;
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

  /**
   * 补牵引门槛（blocks/tick）：车速低于目标超过它就补牵引，目标就是限速本身。0.001 格/tick = 0.02 格/秒，HUD 上不到 0.1 km/h。
   *
   * <p>TrainCarts 摩擦已关（{@link #disableSlowdown}），到速后车速不会自己往下掉，门槛不必留余量；旧的“目标的 1%”会让限速的小幅回升
   * 永远不补牵引，车一直比编表曲线慢。
   */
  static final double TRACTION_EPSILON_BPT = 0.001;

  private static final String TAG_LAST_LAUNCH_AT = "FTA_LAST_LAUNCH_AT";
  private static final String TAG_PENDING_LAUNCH_COMMAND = "FTA_PENDING_LAUNCH_COMMAND";

  /**
   * 静止列车被要求发车、却没发出去（冷却未过、TrainCarts 拒绝 launch）时记下的欠账。
   *
   * <p>调度层只在信号变化或强制刷新的那一拍要求发车；那一拍发不出去，之后信号不变就再也不会要求，车停在 PROCEED 下等健康监控补发。 有欠账时后续非 STOP 周期照常补发；任何
   * STOP、物理移动、发车被接受或驾驶员接管都销账。补发仍走同一套冷却与幂等判定， 不比冷却为 0 时更激进。
   */
  private static final String TAG_LAUNCH_OWED = "FTA_LAUNCH_OWED";

  private static final String TAG_LAST_SPEED_CMD_BPS = "FTA_LAST_SPEED_CMD_BPS";
  private static final String TAG_LAST_SPEED_CMD_AT = "FTA_LAST_SPEED_CMD_AT";

  /** 逐 tick 斜坡在未被刷新时至少保留的 tick 数；实际取三个调度周期与它的较大者。 */
  private static final int MIN_SPEED_RAMP_TTL_TICKS = 40;

  /** 多久没再下发命令的速度命令参照视为不再受控（列车已销毁或不归调度管），清理时丢掉。 */
  private static final long SPEED_COMMAND_RETENTION_MS = 5L * 60L * 1000L;

  /** 每记多少次速度命令顺带清理一次过期参照。 */
  private static final int SPEED_COMMAND_PRUNE_INTERVAL = 256;

  private final SpeedLimitRamp speedLimitRamp;

  /** 列车的物理控制权：驾驶员控制的列车不写限速、不发车，只把决定交给驾驶员。 */
  private final ControlAuthority authority;

  /**
   * 每列车上一次下发的速度命令：限幅与迟滞的参照。
   *
   * <p>按列车属性对象的身份记——{@link TrainProperties} 继承集合、按车厢内容算 hash，不能放进普通哈希表。原先写在 tag 里，
   * 时间戳每次控车都变，TrainCarts 每写一次 tag 都要逐节车厢同步配置列表。仅在服务器主线程读写。
   */
  private final Map<TrainProperties, SpeedCommand> speedCommands = new IdentityHashMap<>();

  private int speedCommandsSincePrune;

  /** 使用 Bukkit 调度器驱动的逐 tick 限速斜坡；控制权经插件实例查找。 */
  public TrainLaunchManager() {
    this(new SpeedLimitRamp(), ControlAuthority.pluginLookup());
  }

  /**
   * 全部按自动运行控车。
   *
   * @param speedLimitRamp 逐 tick 限速斜坡
   */
  public TrainLaunchManager(SpeedLimitRamp speedLimitRamp) {
    this(speedLimitRamp, ControlAuthority.NONE);
  }

  /**
   * @param speedLimitRamp 逐 tick 限速斜坡
   * @param authority 列车的物理控制权
   */
  public TrainLaunchManager(SpeedLimitRamp speedLimitRamp, ControlAuthority authority) {
    this.speedLimitRamp = java.util.Objects.requireNonNull(speedLimitRamp, "speedLimitRamp");
    this.authority = java.util.Objects.requireNonNull(authority, "authority");
  }

  /** 列车的物理控制权。 */
  ControlAuthority authority() {
    return authority;
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
   * @param launchCommandAccepted 允许发车（或补发先前没发出去的发车）时，底层已接受 launch action、已有待执行 action
   *     或列车已经移动；非发车控制为 {@code false}
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
    if (authority.isDriverControlled(properties)) {
      return publishDriverDirective(
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
          speedEnvelope);
    }
    disableSlowdown(properties);
    double accelBpt2 = toBlocksPerTickSquared(config.accelBps2());
    double decelBpt2 = toBlocksPerTickSquared(config.decelBps2());
    if (accelBpt2 > 0.0 && decelBpt2 > 0.0) {
      properties.setWaitAcceleration(accelBpt2, decelBpt2);
    }

    if (aspect == SignalAspect.STOP) {
      speedLimitRamp.release(train);
      StopControlMode resolvedStopMode =
          stopMode == null ? StopControlMode.BRAKING_TO_PLANNED_STOP : stopMode;
      if (resolvedStopMode == StopControlMode.HARD_STOP) {
        rememberSpeedCommand(properties, 0.0);
        properties.setSpeedLimit(0.0);
        if (train == null) {
          clearLaunchBookkeeping(properties);
        } else {
          try {
            train.stopHard();
          } finally {
            // 冷却保护的是已交给 TrainCarts、尚在执行的 launch；硬停把动作队列整个清空，它已不存在。
            // 不清掉的话，冷却期内重新放行的那一拍发不了车。与待发车标记、发车欠账一起删，只扫一遍 tag。
            TrainTagHelper.removeTagKeys(
                properties, TAG_PENDING_LAUNCH_COMMAND, TAG_LAUNCH_OWED, TAG_LAST_LAUNCH_AT);
          }
        }
        return new ControlApplicationResult(targetBps, OptionalDouble.empty(), 0.0, "hard_stop");
      }
      clearLaunchBookkeeping(properties);
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
    // 先前要求过发车却没发出去：这一拍信号虽未变化，仍按发车处理（见 TAG_LAUNCH_OWED）。
    boolean launchOwed = train != null && !train.isMoving() && hasLaunchOwed(properties);
    boolean launchRequested = allowLaunch || launchOwed;
    // 发车/信号放行/运行中补牵引都由 launch 动作按加速度爬升：速度上限不再“上行限幅”二次压速，也不按迟滞留在旧命令上——
    // 牵引目标就是限速本身，迟滞会让车一直比限速（编表曲线）慢一截。
    boolean tractionIssued = launchRequested || resumeTraction;
    double adjustedBps =
        applySpeedCommandRateLimit(
            properties, heldBps, config, runtimeSettings, tractionIssued, tractionIssued);
    double targetBpt = toBlocksPerTick(adjustedBps);
    properties.setSpeedLimit(targetBpt);
    if (speedEnvelope == null) {
      speedLimitRamp.acknowledgeWrite(train, properties);
    } else if (train != null && train.isMoving() && !speedEnvelope.isEmpty()) {
      speedLimitRamp.arm(
          train,
          properties,
          adjustedBps,
          speedEnvelope,
          speedRampTtlTicks(runtimeSettings),
          accelBpt2);
    } else {
      speedLimitRamp.release(train);
    }
    boolean launchCommandAccepted = false;
    // 非 STOP 信号：允许发车或对运动中列车补充能量
    if (train != null) {
      if (train.isMoving()) {
        // 物理运动是唯一能消费待发车命令的正向证据；此后相同授权无需保留发车去重标记，欠账也随之销掉。
        clearLaunchBookkeeping(properties);
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
        // 静止时需要发车：受 allowLaunch（或欠账）和冷却时间限制
        String commandSignature =
            pendingLaunchCommandSignature(aspect, targetBpt, accelBpt2, launchFallbackDirection);
        if (!launchRequested) {
          clearPendingLaunchCommand(properties);
        } else {
          if (hasPendingLaunchCommand(properties, commandSignature)) {
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
          if (launchCommandAccepted && launchOwed) {
            TrainTagHelper.removeTagKey(properties, TAG_LAUNCH_OWED);
          } else if (!launchCommandAccepted && !launchOwed) {
            TrainTagHelper.writeTag(properties, TAG_LAUNCH_OWED, "true");
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
   * 驾驶员控制的列车：照常算出自动运行下会写入的速度，但不写限速、不发车、不挂斜坡，只把决定交给驾驶员。
   *
   * <p>自动运行的 {@code allowLaunch} 只在信号变化或强制刷新的那一拍为真（是否下发发车动作）；驾驶员能否起步只看是不是停车信号。 “发车已接受”仍按 {@code
   * allowLaunch} 报告：起步由驾驶员完成，调度层不应因为没看到发车动作而反复重试。
   */
  private ControlApplicationResult publishDriverDirective(
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
    speedLimitRamp.release(train);
    // 驾驶员接管后起步归驾驶员；交还自动运行时不能再拿接管前的欠账替他发车。
    clearLaunchBookkeeping(properties);
    if (allowLaunch
        && aspect != SignalAspect.STOP
        && train != null
        && !train.isMoving()
        && authority.takeTurnback(properties)) {
      // 终点站折返由驾驶员接班：按自动发车同一套寻路判定方向，车头朝反了先调头（车不动），再把发车交给驾驶员。
      train.faceDepartureDirection(
          launchFallbackDirection == null ? java.util.Optional.empty() : launchFallbackDirection);
    }
    // 人工驾驶不受进站限速：停车由驾驶员自己掌握，越过停车点另有防护。
    if (speedEnvelope != null) {
      SpeedEnvelope.ManualView manual = speedEnvelope.manual(targetBps);
      targetBps = manual.targetBps();
      speedEnvelope = manual.envelope();
    }
    StopControlMode resolvedStopMode =
        stopMode == null ? StopControlMode.BRAKING_TO_PLANNED_STOP : stopMode;
    double permittedBps;
    OptionalDouble curveLimit = OptionalDouble.empty();
    String source;
    if (aspect == SignalAspect.STOP) {
      if (resolvedStopMode == StopControlMode.HARD_STOP) {
        permittedBps = 0.0;
        source = "driver_hard_stop";
      } else {
        permittedBps = Math.max(0.0, resolveStopSpeed(train, config, distanceOpt, runtimeSettings));
        source = "driver_stop_curve";
      }
    } else {
      permittedBps = applySpeedCurve(targetBps, config, distanceOpt, runtimeSettings);
      if (permittedBps < Math.max(0.0, targetBps) - 1.0e-6) {
        curveLimit = OptionalDouble.of(permittedBps);
      }
      source = "driver";
    }
    authority.publish(
        properties,
        new DriverDirective(
            aspect,
            resolvedStopMode,
            targetBps,
            permittedBps,
            aspect != SignalAspect.STOP,
            distanceOpt == null ? OptionalLong.empty() : distanceOpt,
            speedEnvelope));
    return new ControlApplicationResult(
        targetBps, curveLimit, permittedBps, source, allowLaunch && aspect != SignalAspect.STOP);
  }

  /**
   * 运行中列车的目标速度高于当前车速时补牵引。
   *
   * <p>门槛 {@value #TRACTION_EPSILON_BPT} 格/tick，与 {@link TrainCartsRuntimeHandle#accelerateTo}
   * 一致。身上挂着别的 TrainCarts 动作（停站等待、居中）时不补：launch 会排在它后面执行。
   */
  private boolean shouldResumeTraction(RuntimeTrainHandle train, double targetBlocksPerTick) {
    if (train == null || !train.isMoving() || !Double.isFinite(targetBlocksPerTick)) {
      return false;
    }
    double current = train.currentSpeedBlocksPerTick();
    return Double.isFinite(current)
        && targetBlocksPerTick > current + TRACTION_EPSILON_BPT
        && !train.hasForeignAction();
  }

  /**
   * 关掉 TrainCarts 的摩擦与坡道重力。
   *
   * <p>存档模板没写 {@code slowDown} 时 TrainCarts 默认全开：摩擦每 tick 乘 0.997（22.2 格/秒时约 −1.3 格/秒²）。
   * 发车动作到速即结束，之后速度一路往下掉，车速就在限速下方来回浮动。列车的加减速全由本插件控制，编表运行曲线也不计摩擦与坡度， 与其一致。全服列车都关（{@link
   * RuntimeSignalMonitor} 巡检时调用，含非本插件的车）；脱轨车由巡检直接回收，不依赖重力落地。 已关闭时不再写，避免反复触发属性变更。
   *
   * @param properties 列车属性；为 {@code null} 时忽略
   */
  static void disableSlowdown(TrainProperties properties) {
    if (properties != null && !properties.isSlowingDownNone()) {
      properties.setSlowingDown(false);
    }
  }

  /**
   * 逐 tick 斜坡按实际里程推算的车头位置，见 {@link SpeedLimitRamp#headProgressBlocks}。
   *
   * @param nodeKey 车头之前最近经过的图节点
   */
  public OptionalDouble headProgressBlocks(RuntimeTrainHandle train, String nodeKey) {
    return speedLimitRamp.headProgressBlocks(train, nodeKey);
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

  /** 是否欠着一次没发出去的发车。 */
  private static boolean hasLaunchOwed(TrainProperties properties) {
    return TrainTagHelper.readTagValue(properties, TAG_LAUNCH_OWED).isPresent();
  }

  /** 一并清除待发车幂等标记与发车欠账（一次遍历 tag）。 */
  private static void clearLaunchBookkeeping(TrainProperties properties) {
    TrainTagHelper.removeTagKeys(properties, TAG_PENDING_LAUNCH_COMMAND, TAG_LAUNCH_OWED);
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
    SpeedCommand last = lastSpeedCommand(properties, nowMs);

    // 首次下发不做限幅，避免从 0 速起步被过度限制。
    if (last == null) {
      rememberSpeedCommand(properties, requested, nowMs);
      return requested;
    }

    double deltaSeconds = Math.max(0.05, (nowMs - last.atMs()) / 1000.0);
    double accelLimitPerSecond =
        Math.max(0.0, config.accelBps2() * runtimeSettings.speedCommandAccelFactor());
    double referenceSpeed = last.bps();

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

    rememberSpeedCommand(properties, limited, nowMs);
    return limited;
  }

  /**
   * 上一次下发的速度命令；没有时为 {@code null}。
   *
   * <p>内存里没有时读一次旧版本写在 tag 里的参照：升级前就在跑的列车还带着它，接着按它限幅才不会在换版本后第一次控车时跳变。
   */
  private SpeedCommand lastSpeedCommand(TrainProperties properties, long nowMs) {
    SpeedCommand remembered = speedCommands.get(properties);
    if (remembered != null) {
      return remembered;
    }
    java.util.Optional<Double> legacyBps =
        TrainTagHelper.readDoubleTag(properties, TAG_LAST_SPEED_CMD_BPS).filter(Double::isFinite);
    if (legacyBps.isEmpty()) {
      return null;
    }
    long legacyAtMs = TrainTagHelper.readLongTag(properties, TAG_LAST_SPEED_CMD_AT).orElse(nowMs);
    return new SpeedCommand(legacyBps.get(), legacyAtMs);
  }

  private void rememberSpeedCommand(TrainProperties properties, double speedBps) {
    if (properties == null) {
      return;
    }
    rememberSpeedCommand(properties, speedBps, System.currentTimeMillis());
  }

  private void rememberSpeedCommand(TrainProperties properties, double speedBps, long nowMs) {
    speedCommands.put(properties, new SpeedCommand(Math.max(0.0, speedBps), nowMs));
    if (++speedCommandsSincePrune >= SPEED_COMMAND_PRUNE_INTERVAL) {
      speedCommandsSincePrune = 0;
      Iterator<SpeedCommand> commands = speedCommands.values().iterator();
      while (commands.hasNext()) {
        if (nowMs - commands.next().atMs() > SPEED_COMMAND_RETENTION_MS) {
          commands.remove();
        }
      }
    }
  }

  /**
   * 上一次下发的速度命令。
   *
   * @param bps 命令速度（格/秒）
   * @param atMs 下发时刻（毫秒时间戳）
   */
  private record SpeedCommand(double bps, long atMs) {}

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
