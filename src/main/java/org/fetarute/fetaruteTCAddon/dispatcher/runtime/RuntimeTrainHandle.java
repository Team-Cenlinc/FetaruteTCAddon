package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;

/** 运行时控车抽象：隔离 TrainCarts 具体实现，便于单测与后续扩展。 */
public interface RuntimeTrainHandle {

  /** 是否仍为有效列车（实体未被卸载/销毁）。 */
  boolean isValid();

  /** 当前是否处于移动状态。 */
  boolean isMoving();

  /** 当前速度（blocks per tick），用于低速 failover 判定。 */
  double currentSpeedBlocksPerTick();

  /** 运行时所在世界 ID（用于查询调度图快照）。 */
  UUID worldId();

  /** 获取 TrainCarts 属性对象，用于读写 tags/速度/目的地等。 */
  TrainProperties properties();

  /**
   * 停着的列车按发车方向调头：与自动发车同一套寻路判定方向，车头朝反了就把编组前后对调（车不动）。
   *
   * <p>只用于驾驶员控制的列车在终点站折返发车：自动运行由发车动作自己调头，驾驶员控制时不下发发车动作。
   *
   * @param fallbackDirection 寻路判定不出方向时按调度图给出的方向
   * @return 是否调了头
   */
  default boolean faceDepartureDirection(Optional<org.bukkit.block.BlockFace> fallbackDirection) {
    return false;
  }

  /**
   * 返回用于列尾清空判定的保守列车长度估计（blocks）。
   *
   * <p>默认不猜测长度。调用方在结果缺失、非有限或非正数时必须 fail-retain，不能退回车数、节点数或时间窗口提前释放旧进路。
   */
  default OptionalDouble estimatedTrainLengthBlocks() {
    return OptionalDouble.empty();
  }

  /**
   * 返回整列车头到列尾当前覆盖的 TrainCarts RailPath 栅格足迹。
   *
   * <p>该证据用于启动/迟加载现场占用水合；只要任一车厢未被有效轨迹覆盖、轨迹断开、跨世界或无效，实现就必须返回 empty，调用方不得用实体位置或 routeIndex 猜测。
   */
  default Optional<Set<RailFootprintCell>> liveRailFootprintCells() {
    return Optional.empty();
  }

  /**
   * 返回带结构化失败原因的整列实时轨道足迹。
   *
   * <p>兼容旧测试句柄与其它实现：只实现 {@link #liveRailFootprintCells()} 时，缺失统一标记为 {@link
   * LiveRailFootprintObservation.FailureReason#LEGACY_UNAVAILABLE}。TrainCarts 适配器应覆盖本方法并报告精确阶段。
   */
  default LiveRailFootprintObservation observeLiveRailFootprint() {
    Optional<Set<RailFootprintCell>> cells = liveRailFootprintCells();
    if (cells != null && cells.isPresent() && !cells.orElseThrow().isEmpty()) {
      return LiveRailFootprintObservation.available(cells.orElseThrow());
    }
    return LiveRailFootprintObservation.unavailable(
        LiveRailFootprintObservation.FailureReason.LEGACY_UNAVAILABLE,
        "runtime-handle-did-not-report-cells");
  }

  /**
   * 返回当前物理编组实例的稳定身份。
   *
   * <p>同一逻辑列车名可能在 split/link/create 过渡期同时对应多个实体编组；启动水合 marker 必须同时匹配逻辑 owner 与该实例身份，不能只按列车名复用。
   */
  default Object physicalRuntimeIdentity() {
    return this;
  }

  /**
   * 执行紧急停车（不包含目的地/进度处理）。
   *
   * <p>实现需保证不会误触发 destination 逻辑。
   */
  void stop();

  /**
   * 执行硬安全停车。
   *
   * <p>硬 STOP 用于闭塞、授权失败、互卡兜底等安全边界。实现应立即归零速度并清除 TrainCarts 已排队动作，尤其是 launch action，避免旧 destination
   * 继续牵引列车。
   */
  default void stopHard() {
    stop();
  }

  /**
   * 在列车静止时发车；实现应自行处理“正在移动时跳过”的保护。
   *
   * @param targetBlocksPerTick 目标速度（blocks/tick）
   * @param accelBlocksPerTickSquared 加速度（blocks/tick^2）
   */
  void launch(double targetBlocksPerTick, double accelBlocksPerTickSquared);

  /**
   * 在列车静止时发车（含可选方向兜底）。
   *
   * <p>默认实现忽略兜底方向，行为等同 {@link #launch(double, double)}。
   *
   * @param fallbackDirection 兜底发车方向（调度图推导）
   * @param targetBlocksPerTick 目标速度（blocks/tick）
   * @param accelBlocksPerTickSquared 加速度（blocks/tick^2）
   */
  default void launchWithFallback(
      Optional<org.bukkit.block.BlockFace> fallbackDirection,
      double targetBlocksPerTick,
      double accelBlocksPerTickSquared) {
    launch(targetBlocksPerTick, accelBlocksPerTickSquared);
  }

  /**
   * 请求执行一次带方向兜底的发车，并报告底层是否接受命令。
   *
   * <p>默认实现用于测试句柄与兼容实现：调用既有 {@link #launchWithFallback(Optional, double, double)} 后视为已接受。TrainCarts
   * 实现必须以列车已经移动、已有有效 launch action 或成功新增 action 作为成功证据。
   *
   * @return 发车命令是否已由底层接受
   */
  default boolean requestLaunchWithFallback(
      Optional<org.bukkit.block.BlockFace> fallbackDirection,
      double targetBlocksPerTick,
      double accelBlocksPerTickSquared) {
    launchWithFallback(fallbackDirection, targetBlocksPerTick, accelBlocksPerTickSquared);
    return true;
  }

  /**
   * 平滑调整到目标速度（无论列车是否在运动）。
   *
   * <p>用于在经过 waypoint/switcher 时补充牵引，也用于 approach/限速场景下按 TrainCarts launch 动作平滑减速。
   *
   * @param targetBlocksPerTick 目标速度（blocks/tick）
   * @param accelBlocksPerTickSquared 加减速度（blocks/tick^2）
   */
  default void accelerateTo(double targetBlocksPerTick, double accelBlocksPerTickSquared) {
    // 默认实现：如果静止，尝试发车加速；运动中由 WaitAcceleration + speedLimit 接管
    if (!isMoving()) {
      launch(targetBlocksPerTick, accelBlocksPerTickSquared);
    }
  }

  /**
   * 列车身上是否挂着本插件发车动作以外的 TrainCarts 动作（停站等待、停稳居中、其它牌子下发的动作等）。
   *
   * <p>运行中补牵引只在没有这类动作时下发：launch 动作排在队尾，要等前面的动作结束才执行，接在停站等待后面就等于绕过发车门控。
   * 默认视为有——实现报告不了时不补牵引，保持原来"只在信号变化时补牵引"的行为。
   *
   * @return 有别的动作，或无法判断时为 {@code true}
   */
  default boolean hasForeignAction() {
    return true;
  }

  /**
   * 强制重发列车（用于回退检测后纠正方向）。
   *
   * <p>与 {@link #launchWithFallback} 不同，此方法会：
   *
   * <ul>
   *   <li>先强制停车（瞬时归零速度）
   *   <li>然后立即向指定方向发车，不跳过"正在移动"检查
   * </ul>
   *
   * @param direction 发车方向（必填）
   * @param targetBlocksPerTick 目标速度（blocks/tick）
   * @param accelBlocksPerTickSquared 加速度（blocks/tick^2）
   */
  default void forceRelaunch(
      org.bukkit.block.BlockFace direction,
      double targetBlocksPerTick,
      double accelBlocksPerTickSquared) {
    stop();
    launchWithFallback(Optional.of(direction), targetBlocksPerTick, accelBlocksPerTickSquared);
  }

  /**
   * 销毁列车实体（用于 DSTY 终点回收）。
   *
   * <p>实现应避免在 TrainCarts 内部 tick 过程中直接销毁导致异常。
   */
  void destroy();

  /**
   * 正常收车：列车按交路开到销毁点（DSTY，通常是车库）后销毁。车上有驾驶员时按驾驶正常结束处理，而不是调度收回。
   *
   * <p>默认与 {@link #destroy()} 相同。
   */
  default void retire() {
    destroy();
  }

  /** 设置列车当前的 route index。 */
  void setRouteIndex(int index);

  /** 设置列车当前的 route ID。 */
  void setRouteId(String routeId);

  /** 设置列车目的地（destination）。 */
  void setDestination(String destination);

  /** 获取列车当前朝向（用于判定发车方向）。 */
  java.util.Optional<org.bukkit.block.BlockFace> forwardDirection();

  /** 获取列车的轨道状态（用于 TrainCarts 寻路方向判断）。 */
  default Optional<com.bergerkiller.bukkit.tc.controller.components.RailState> railState() {
    return Optional.empty();
  }

  /** 反向列车朝向（用于终到折返）。 */
  void reverse();
}
