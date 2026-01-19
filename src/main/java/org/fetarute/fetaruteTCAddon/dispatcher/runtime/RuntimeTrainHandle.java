package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.UUID;

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
   * 执行紧急停车（不包含目的地/进度处理）。
   *
   * <p>实现需保证不会误触发 destination 逻辑。
   */
  void stop();

  /**
   * 在列车静止时发车；实现应自行处理“正在移动时跳过”的保护。
   *
   * @param targetBlocksPerTick 目标速度（blocks/tick）
   * @param accelBlocksPerTickSquared 加速度（blocks/tick^2）
   */
  void launch(double targetBlocksPerTick, double accelBlocksPerTickSquared);

  /**
   * 销毁列车实体（用于 DSTY 终点回收）。
   *
   * <p>实现应避免在 TrainCarts 内部 tick 过程中直接销毁导致异常。
   */
  void destroy();
}
