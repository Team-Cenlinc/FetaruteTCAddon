package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import org.bukkit.block.BlockFace;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.StopApproach;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfig;
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
    if (train != null) {
      train.stop();
    }
  }

  /** 立即执行闭塞硬 STOP：不使用制动曲线，不保留 launch action。 */
  public void stopHard(RuntimeTrainHandle train, TrainProperties properties) {
    launchManager.releaseSpeedRamp(train);
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
    launchManager.releaseSpeedRamp(train);
    properties.setSpeedLimit(targetBpt);
    train.forceRelaunch(direction, targetBpt, accelBpt2);
  }

  /**
   * 进站限速，与编表运行曲线（{@code RunCurveModel}）同一口径。
   *
   * <p>进站限速区由 {@link StopApproach#zones} 划定（编表与控车共用）。区内不超过进站限速；区外按列车减速度制动至前方每个限速区的起点并取最低，即 √(v² +
   * 2·a·到区起点的距离)——运行曲线反向推算的“理想司机”就是这样开的，控车照此执行，表定时分才对得上实际。
   *
   * @param approachLimitBps 进站限速，必须为正
   * @param decelBps2 列车减速度；非正或非有限时只在区内限速，区外不设限
   * @param zones 限速区，里程从本周期取样时的车头量起
   * @param traveledBlocks 取样后又走过的距离
   * @return 限速上限；此处不设限时返回 {@link Double#POSITIVE_INFINITY}
   */
  static double approachSpeedLimit(
      double approachLimitBps,
      double decelBps2,
      List<StopApproach.Zone> zones,
      double traveledBlocks) {
    if (!Double.isFinite(approachLimitBps) || approachLimitBps <= 0.0 || zones == null) {
      return Double.POSITIVE_INFINITY;
    }
    boolean brakeInto = Double.isFinite(decelBps2) && decelBps2 > 0.0;
    double limit = Double.POSITIVE_INFINITY;
    for (StopApproach.Zone zone : zones) {
      if (zone.toBlocks() < traveledBlocks) {
        continue;
      }
      double ahead = zone.fromBlocks() - traveledBlocks;
      if (ahead <= 0.0) {
        return approachLimitBps;
      }
      if (brakeInto) {
        limit =
            Math.min(
                limit, Math.sqrt(approachLimitBps * approachLimitBps + 2.0 * decelBps2 * ahead));
      }
    }
    return limit;
  }

  /**
   * 进站限速的逐 tick 形式，供 {@link SpeedLimitRamp} 在调度周期之间按实际走过的距离求值；公式即 {@link #approachSpeedLimit}。
   *
   * <p>它给出的是制动至限速区的物理上界，不含周期取样时的边限速或 caution 速度，驶过节点后依然成立，因此可以作推进放行的保持约束。
   */
  static SpeedEnvelope.Constraint approachConstraint(
      double approachLimitBps, double decelBps2, List<StopApproach.Zone> zones) {
    List<StopApproach.Zone> fixed = zones == null ? List.of() : List.copyOf(zones);
    return traveled -> approachSpeedLimit(approachLimitBps, decelBps2, fixed, traveled);
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
