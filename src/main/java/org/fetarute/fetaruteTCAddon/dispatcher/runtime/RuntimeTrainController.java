package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import org.bukkit.block.BlockFace;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfig;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverInterrupt;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.SpeedEnvelope;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;

/**
 * 运行时控车门面。
 *
 * <p>该类是动态运行系统落地 TrainCarts 控制动作的统一入口。调度层负责决定 route progress、资源归属、destination
 * 与发车授权；本类只根据已经给出的信号与速度约束执行限速、停车、发车和强制重发。
 *
 * <p>当前实现保守包装 {@link TrainLaunchManager}，不引入第二套运动规划主流程。后续若需要继续收敛控车节流、诊断或曲线计算，应优先在这里扩展。
 */
public final class RuntimeTrainController {

  private static final double TICKS_PER_SECOND = 20.0;

  private final TrainLaunchManager launchManager;

  /** 使用默认 {@link TrainLaunchManager} 构建控车门面。 */
  public RuntimeTrainController() {
    this(new TrainLaunchManager());
  }

  /**
   * 使用指定控车执行器构建门面。
   *
   * @param launchManager 既有控车执行器
   */
  public RuntimeTrainController(TrainLaunchManager launchManager) {
    this.launchManager = Objects.requireNonNull(launchManager, "launchManager");
  }

  /**
   * 按信号与目标速度应用一次控车命令。
   *
   * @param train 运行时列车句柄
   * @param properties TrainCarts 属性
   * @param aspect 当前应执行的信号
   * @param targetBps 目标速度，单位 blocks/s
   * @param config 列车速度配置
   * @param allowLaunch 是否允许本次命令触发发车/牵引
   * @param distanceOpt 到约束点的距离，用于 STOP/approach 速度曲线
   * @param launchFallbackDirection TrainCarts 自动寻路缺少方向时的兜底方向
   * @param runtimeSettings 运行时控车配置
   * @return 控车执行结果，用于诊断
   */
  public TrainLaunchManager.ControlApplicationResult applyControl(
      RuntimeTrainHandle train,
      TrainProperties properties,
      SignalAspect aspect,
      double targetBps,
      TrainConfig config,
      boolean allowLaunch,
      OptionalLong distanceOpt,
      Optional<BlockFace> launchFallbackDirection,
      ConfigManager.RuntimeSettings runtimeSettings) {
    return launchManager.applyControl(
        train,
        properties,
        aspect,
        targetBps,
        config,
        allowLaunch,
        distanceOpt,
        launchFallbackDirection,
        runtimeSettings);
  }

  /** 按指定 STOP 模式应用一次控车命令。 */
  public TrainLaunchManager.ControlApplicationResult applyControl(
      RuntimeTrainHandle train,
      TrainProperties properties,
      SignalAspect aspect,
      double targetBps,
      TrainConfig config,
      boolean allowLaunch,
      OptionalLong distanceOpt,
      Optional<BlockFace> launchFallbackDirection,
      ConfigManager.RuntimeSettings runtimeSettings,
      StopControlMode stopMode) {
    return launchManager.applyControl(
        train,
        properties,
        aspect,
        targetBps,
        config,
        allowLaunch,
        distanceOpt,
        launchFallbackDirection,
        runtimeSettings,
        stopMode);
  }

  /**
   * 带速度包络应用一次控车命令（信号 tick 的完整判定）。
   *
   * @param speedEnvelope 从车头量起的随距离约束；{@code null} 表示没有速度上下文
   * @see TrainLaunchManager#applyControl(RuntimeTrainHandle, TrainProperties, SignalAspect, double,
   *     TrainConfig, boolean, OptionalLong, Optional, ConfigManager.RuntimeSettings,
   *     StopControlMode, SpeedEnvelope)
   */
  public TrainLaunchManager.ControlApplicationResult applyControl(
      RuntimeTrainHandle train,
      TrainProperties properties,
      SignalAspect aspect,
      double targetBps,
      TrainConfig config,
      boolean allowLaunch,
      OptionalLong distanceOpt,
      Optional<BlockFace> launchFallbackDirection,
      ConfigManager.RuntimeSettings runtimeSettings,
      StopControlMode stopMode,
      SpeedEnvelope speedEnvelope) {
    return launchManager.applyControl(
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

  /**
   * 逐 tick 斜坡按实际里程推算的车头位置（车头已驶过 {@code nodeKey} 的距离）；推算不可用时为空。
   *
   * @param train 运行时列车句柄
   * @param nodeKey 车头之前最近经过的图节点
   */
  public java.util.OptionalDouble headProgressBlocks(RuntimeTrainHandle train, String nodeKey) {
    return launchManager.headProgressBlocks(train, nodeKey);
  }

  /** 这列车是否由驾驶员物理控制（调度层的对位、等待动作要跳过）。 */
  boolean isDriverControlled(TrainProperties properties) {
    return properties != null && launchManager.authority().isDriverControlled(properties);
  }

  /**
   * 立即保持停车。
   *
   * <p>用于没有下一节点或异常状态下的兜底停车。常规 STOP 制动仍应优先通过 {@link #applyControl(RuntimeTrainHandle,
   * TrainProperties, SignalAspect, double, TrainConfig, boolean, OptionalLong, Optional,
   * ConfigManager.RuntimeSettings)} 下发，以保留制动曲线。
   *
   * @param train 运行时列车句柄
   */
  public void stopNow(RuntimeTrainHandle train) {
    launchManager.releaseSpeedRamp(train);
    TrainProperties properties = train != null ? train.properties() : null;
    if (properties != null && launchManager.authority().isDriverControlled(properties)) {
      launchManager.authority().interrupt(properties, DriverInterrupt.SERVICE_STOP);
      return;
    }
    if (train != null) {
      train.stop();
    }
  }

  /** 立即执行闭塞硬 STOP：不使用制动曲线，不保留 launch action。 */
  public void stopHard(RuntimeTrainHandle train, TrainProperties properties) {
    launchManager.releaseSpeedRamp(train);
    if (properties != null && launchManager.authority().isDriverControlled(properties)) {
      // 驾驶员控制的列车：不写限速、不清动作队列，由驾驶侧施加紧急制动。
      launchManager.authority().interrupt(properties, DriverInterrupt.EMERGENCY);
      return;
    }
    if (properties != null) {
      properties.setSpeedLimit(0.0);
    }
    if (train != null) {
      train.stopHard();
    }
  }

  /**
   * 设置临时速度限制。
   *
   * <p>仅用于 TrainCarts 行为动作自身需要移动的受控场景，例如 waypoint 居中。常规运行限速必须走 {@link #applyControl}。
   *
   * @param properties TrainCarts 属性
   * @param speedBlocksPerTick 速度限制，单位 blocks/tick
   */
  public void setTemporarySpeedLimit(TrainProperties properties, double speedBlocksPerTick) {
    if (properties != null && launchManager.authority().isDriverControlled(properties)) {
      return;
    }
    if (properties != null) {
      properties.setSpeedLimit(Math.max(0.0, speedBlocksPerTick));
    }
  }

  /**
   * 强制重发列车。
   *
   * <p>该动作会先由 TrainCarts 归零/重置动作，再按指定方向发车；只允许由健康修复或明确回退修复路径调用。
   *
   * @param train 运行时列车句柄
   * @param properties TrainCarts 属性
   * @param direction 重发方向
   * @param targetBps 目标速度，单位 blocks/s
   * @param config 列车速度配置
   */
  public void forceRelaunch(
      RuntimeTrainHandle train,
      TrainProperties properties,
      BlockFace direction,
      double targetBps,
      TrainConfig config) {
    if (train == null || properties == null || direction == null || config == null) {
      return;
    }
    if (launchManager.authority().isDriverControlled(properties)) {
      return;
    }
    double targetBpt = toBlocksPerTick(targetBps);
    double accelBpt2 = toBlocksPerTickSquared(config.accelBps2());
    launchManager.releaseSpeedRamp(train);
    properties.setSpeedLimit(targetBpt);
    train.forceRelaunch(direction, targetBpt, accelBpt2);
  }

  private static double toBlocksPerTick(double blocksPerSecond) {
    if (!Double.isFinite(blocksPerSecond) || blocksPerSecond <= 0.0) {
      return 0.0;
    }
    return blocksPerSecond / TICKS_PER_SECOND;
  }

  private static double toBlocksPerTickSquared(double blocksPerSecondSquared) {
    if (!Double.isFinite(blocksPerSecondSquared) || blocksPerSecondSquared <= 0.0) {
      return 0.0;
    }
    return blocksPerSecondSquared / (TICKS_PER_SECOND * TICKS_PER_SECOND);
  }
}
