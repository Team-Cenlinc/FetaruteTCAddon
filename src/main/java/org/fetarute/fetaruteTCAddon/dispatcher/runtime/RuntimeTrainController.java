package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import org.bukkit.block.BlockFace;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfig;
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
   * 立即保持停车。
   *
   * <p>用于没有下一节点或异常状态下的兜底停车。常规 STOP 制动仍应优先通过 {@link #applyControl(RuntimeTrainHandle,
   * TrainProperties, SignalAspect, double, TrainConfig, boolean, OptionalLong, Optional,
   * ConfigManager.RuntimeSettings)} 下发，以保留制动曲线。
   *
   * @param train 运行时列车句柄
   */
  public void stopNow(RuntimeTrainHandle train) {
    if (train != null) {
      train.stop();
    }
  }

  /** 立即执行闭塞硬 STOP：不使用制动曲线，不保留 launch action。 */
  public void stopHard(RuntimeTrainHandle train, TrainProperties properties) {
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
    double targetBpt = toBlocksPerTick(targetBps);
    double accelBpt2 = toBlocksPerTickSquared(config.accelBps2());
    properties.setSpeedLimit(targetBpt);
    train.forceRelaunch(direction, targetBpt, accelBpt2);
  }

  /**
   * 计算 approach 速度包络。
   *
   * <p>信号/调度层只提供“需要降速的目标、距离与配置”；这里统一把 preview 区线性下压、正式 approach 限速和物理制动包络合成最终速度上限。
   *
   * @param currentTargetBps 当前基础目标速度
   * @param approachLimitBps approach 目标速度
   * @param decelBps2 列车制动能力
   * @param distanceBlocks 到 approach 目标的距离
   * @param targetEdgeDistanceBlocks approach 目标边界距离
   * @param edgeCount 到目标的边数量
   * @param runtime 运行时控车配置
   * @param previewDistanceBlocks 正式 approach 窗口外的预制动距离
   * @return 合成后的 approach 速度上限
   */
  static double resolveApproachSpeedEnvelope(
      double currentTargetBps,
      double approachLimitBps,
      double decelBps2,
      OptionalLong distanceBlocks,
      OptionalLong targetEdgeDistanceBlocks,
      int edgeCount,
      ConfigManager.RuntimeSettings runtime,
      double previewDistanceBlocks) {
    if (!Double.isFinite(currentTargetBps) || currentTargetBps <= 0.0) {
      return 0.0;
    }
    if (!Double.isFinite(approachLimitBps) || approachLimitBps <= 0.0) {
      return currentTargetBps;
    }
    if (runtime == null || distanceBlocks == null || distanceBlocks.isEmpty()) {
      return Math.min(currentTargetBps, approachLimitBps);
    }
    long distance = distanceBlocks.getAsLong();
    double previewRatio =
        resolveApproachPreviewRatio(runtime, previewDistanceBlocks, distance, edgeCount);
    double previewEnvelope =
        approachPreviewSpeedLimit(currentTargetBps, approachLimitBps, previewRatio);
    if (!runtime.speedCurveEnabled()
        || targetEdgeDistanceBlocks == null
        || targetEdgeDistanceBlocks.isEmpty()
        || !Double.isFinite(decelBps2)
        || decelBps2 <= 0.0) {
      return previewEnvelope;
    }
    double brakingDistance = Math.max(0.0, distance - targetEdgeDistanceBlocks.getAsLong());
    double brakingEnvelope =
        Math.sqrt(
            approachLimitBps * approachLimitBps
                + 2.0 * decelBps2 * brakingDistance * runtime.speedCurveFactor());
    if (!Double.isFinite(brakingEnvelope) || brakingEnvelope <= 0.0) {
      return previewEnvelope;
    }
    return Math.min(previewEnvelope, Math.max(approachLimitBps, brakingEnvelope));
  }

  static double approachPreviewRatio(
      double approachWindowBlocks, double previewDistanceBlocks, long distanceBlocks) {
    if (!Double.isFinite(approachWindowBlocks)
        || approachWindowBlocks < 0.0
        || !Double.isFinite(previewDistanceBlocks)
        || previewDistanceBlocks <= 0.0
        || distanceBlocks < 0L) {
      return 0.0;
    }
    if (distanceBlocks <= approachWindowBlocks) {
      return 1.0;
    }
    double distanceToBoundary = distanceBlocks - approachWindowBlocks;
    if (distanceToBoundary >= previewDistanceBlocks) {
      return 0.0;
    }
    return Math.max(0.0, Math.min(1.0, 1.0 - distanceToBoundary / previewDistanceBlocks));
  }

  static double approachPreviewSpeedLimit(
      double currentTargetBps, double approachLimitBps, double ratio) {
    if (!Double.isFinite(currentTargetBps) || currentTargetBps <= 0.0) {
      return 0.0;
    }
    if (!Double.isFinite(approachLimitBps)
        || approachLimitBps <= 0.0
        || approachLimitBps >= currentTargetBps) {
      return currentTargetBps;
    }
    double clampedRatio = Double.isFinite(ratio) ? Math.max(0.0, Math.min(1.0, ratio)) : 0.0;
    return approachLimitBps + (currentTargetBps - approachLimitBps) * (1.0 - clampedRatio);
  }

  private static double resolveApproachPreviewRatio(
      ConfigManager.RuntimeSettings runtime,
      double previewDistanceBlocks,
      long distanceBlocks,
      int edgeCount) {
    if (runtime == null) {
      return 0.0;
    }
    if (withinApproachWindow(runtime, distanceBlocks, edgeCount)) {
      return 1.0;
    }
    return approachPreviewRatio(
        runtime.approachWindowBlocks(), previewDistanceBlocks, distanceBlocks);
  }

  private static boolean withinApproachWindow(
      ConfigManager.RuntimeSettings runtime, long distanceBlocks, int edgeCount) {
    if (runtime == null) {
      return false;
    }
    double windowBlocks = runtime.approachWindowBlocks();
    if (Double.isFinite(windowBlocks) && windowBlocks > 0.0 && distanceBlocks <= windowBlocks) {
      return true;
    }
    int windowEdges = runtime.approachWindowEdges();
    return windowEdges > 0 && edgeCount >= 0 && edgeCount <= windowEdges;
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
