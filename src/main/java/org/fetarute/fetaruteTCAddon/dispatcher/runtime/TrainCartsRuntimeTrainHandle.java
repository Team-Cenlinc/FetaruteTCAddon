package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.utils.LauncherConfig;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * TrainCarts 运行时列车句柄实现。
 *
 * <p>仅封装“是否移动/发车/停车”等控车动作，避免运行时逻辑直接依赖 TrainCarts API。
 */
public final class TrainCartsRuntimeTrainHandle implements RuntimeTrainHandle {

  private final MinecartGroup group;

  public TrainCartsRuntimeTrainHandle(MinecartGroup group) {
    this.group = Objects.requireNonNull(group, "group");
  }

  /**
   * @return TrainCarts MinecartGroup 是否仍有效。
   */
  @Override
  public boolean isValid() {
    return group.isValid();
  }

  /**
   * @return 列车是否处于移动状态。
   */
  @Override
  public boolean isMoving() {
    return group.isMoving();
  }

  /**
   * @return head cart 的实时速度（blocks/tick），若异常返回 0。
   */
  @Override
  public double currentSpeedBlocksPerTick() {
    MinecartMember<?> head = group.head();
    if (head == null) {
      return 0.0;
    }
    double speed = head.getRealSpeed();
    return Double.isFinite(speed) ? Math.abs(speed) : 0.0;
  }

  @Override
  public UUID worldId() {
    if (group.getWorld() == null) {
      return new UUID(0L, 0L);
    }
    return group.getWorld().getUID();
  }

  /**
   * @return TrainProperties，用于读写 tags/destination 等。
   */
  @Override
  public TrainProperties properties() {
    return group.getProperties();
  }

  /** 执行紧急停车（不触发目的地逻辑）。 */
  @Override
  public void stop() {
    group.stop(false);
  }

  /**
   * 发车至目标速度。
   *
   * <p>若列车仍在移动则跳过。
   */
  @Override
  public void launch(double targetBlocksPerTick, double accelBlocksPerTickSquared) {
    if (group.isMoving()) {
      return;
    }
    MinecartMember<?> head = group.head();
    if (head == null) {
      return;
    }
    head.getActions().clear();
    LauncherConfig launchConfig = LauncherConfig.createDefault();
    if (accelBlocksPerTickSquared > 0.0) {
      launchConfig.setAcceleration(accelBlocksPerTickSquared);
    }
    head.getActions().addActionLaunch(launchConfig, targetBlocksPerTick);
  }

  @Override
  public void destroy() {
    if (!group.isValid()) {
      return;
    }
    // 避免在 TrainCarts doPhysics / SignTracker 刷新过程中直接 destroy() 导致 members array 出现 dead entity。
    // DSTY 往往在推进点（SignActionEvent）内触发，延迟 1 tick 执行更安全。
    Plugin plugin = null;
    try {
      plugin = JavaPlugin.getProvidingPlugin(TrainCartsRuntimeTrainHandle.class);
    } catch (Throwable ignored) {
      plugin = null;
    }
    if (plugin == null || !plugin.isEnabled()) {
      group.destroy();
      return;
    }
    Bukkit.getScheduler()
        .runTaskLater(
            plugin,
            () -> {
              if (group.isValid()) {
                group.destroy();
              }
            },
            1L);
  }
}
