package org.fetarute.fetaruteTCAddon.drive.setup;

import com.bergerkiller.bukkit.tc.attachments.animation.AnimationOptions;
import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import java.util.Collection;
import java.util.Optional;

/**
 * 启动流程的模型动画：升弓正向播放受电方式对应的动画（{@code ptg5}/{@code ptg6}），降弓反向播放。
 *
 * <p>动画名不区分大小写；车模型没有该动画时什么也不做。
 */
public final class SetupAnimations {

  private SetupAnimations() {}

  /**
   * 播放受电动画。
   *
   * @param raise {@code true} 为升弓，{@code false} 为降弓
   * @return 是否触发了动画
   */
  public static boolean playPower(MinecartGroup group, PowerSupply supply, boolean raise) {
    Optional<String> wanted = supply.animation();
    if (group == null || wanted.isEmpty()) {
      return false;
    }
    String name = findName(group.getAnimationNames(), wanted.get());
    if (name == null) {
      return false;
    }
    AnimationOptions options = new AnimationOptions(name);
    options.setReset(true);
    options.setSpeed(raise ? 1.0 : -1.0);
    return group.playNamedAnimation(options);
  }

  /**
   * 超级电容充电时升降受电弓：先找 {@code ptg5}，没有再找 {@code ptg6}；车模型都没有时什么也不做（第三轨充电）。
   *
   * @param raise {@code true} 为升弓，{@code false} 为降弓
   * @return 是否触发了动画
   */
  public static boolean playCharging(MinecartGroup group, boolean raise) {
    if (group == null) {
      return false;
    }
    for (PowerSupply pantograph : new PowerSupply[] {PowerSupply.PTG5, PowerSupply.PTG6}) {
      if (findName(group.getAnimationNames(), pantograph.animation().orElseThrow()) != null) {
        return playPower(group, pantograph, raise);
      }
    }
    return false;
  }

  private static String findName(Collection<String> names, String wanted) {
    if (names == null) {
      return null;
    }
    for (String name : names) {
      if (name != null && name.equalsIgnoreCase(wanted)) {
        return name;
      }
    }
    return null;
  }
}
