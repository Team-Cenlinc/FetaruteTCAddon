package org.fetarute.fetaruteTCAddon.drive.setup;

import com.bergerkiller.bukkit.tc.attachments.animation.AnimationOptions;
import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import java.util.Collection;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.action.QueuedAnimations;

/**
 * 启动流程的模型动画：升弓正向播放受电方式对应的动画（{@code ptg5}/{@code ptg6}），降弓反向播放。超级电容车的充电升降弓由站台上的 TC 牌子负责，插件不播。
 *
 * <p>动画名不区分大小写；车模型没有该动画时什么也不做。
 */
public final class SetupAnimations {

  private SetupAnimations() {}

  /**
   * 播放受电动画：排队、不 {@code reset}（见 {@link QueuedAnimations}），升弓正向播放、降弓倒放。不会清掉 TC 牌子在受电弓附件上排进去的动画。
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
    options.setSpeed(raise ? 1.0 : -1.0);
    return QueuedAnimations.withFallback(QueuedAnimations.playNamed(group, options)).played();
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
