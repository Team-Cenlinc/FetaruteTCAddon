package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.utils.LauncherConfig;
import java.util.Objects;
import java.util.UUID;

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

  @Override
  public boolean isValid() {
    return group.isValid();
  }

  @Override
  public boolean isMoving() {
    return group.isMoving();
  }

  @Override
  public UUID worldId() {
    if (group.getWorld() == null) {
      return new UUID(0L, 0L);
    }
    return group.getWorld().getUID();
  }

  @Override
  public TrainProperties properties() {
    return group.getProperties();
  }

  @Override
  public void stop() {
    group.stop(false);
  }

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
}
