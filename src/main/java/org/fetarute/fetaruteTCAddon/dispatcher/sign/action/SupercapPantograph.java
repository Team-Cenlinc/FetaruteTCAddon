package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.drive.session.DriveSessionManager;
import org.fetarute.fetaruteTCAddon.drive.setup.PowerSupply;
import org.fetarute.fetaruteTCAddon.drive.setup.TrainPower;

/**
 * 超级电容车的受电弓：升弓由站台上的 TC {@code animate} 牌子负责；停站结束放行发车时，受电弓还升着就排一个降弓（倒放）。
 *
 * <p>降弓排队、不 {@code reset}（见 {@link QueuedAnimations}）：牌子的升弓还没放完就等它放完再降，也不会清掉别的排队动画。
 * 没放升弓牌子的站（第三轨充电）弓本来就是降着的，什么也不做。多模车只在当前用超级电容时算电容车。
 */
final class SupercapPantograph {

  private SupercapPantograph() {}

  /**
   * 超级电容车停站结束：把还升着的受电弓降下。
   *
   * @return 排了降弓的附件数
   */
  static int lowerIfRaised(FetaruteTCAddon plugin, MinecartGroup group) {
    if (group == null || !group.isValid() || !isSupercap(plugin, group)) {
      return 0;
    }
    int lowered = 0;
    for (String name : Pantographs.NAMES) {
      lowered += Pantographs.set(group, name, false);
    }
    return lowered;
  }

  private static boolean isSupercap(FetaruteTCAddon plugin, MinecartGroup group) {
    DriveSessionManager drive = plugin == null ? null : plugin.getDriveSessionManager();
    PowerSupply fallback = drive == null ? PowerSupply.PTG5 : drive.config().defaultPower();
    return TrainPower.of(group.getProperties(), fallback).storesEnergy();
  }
}
