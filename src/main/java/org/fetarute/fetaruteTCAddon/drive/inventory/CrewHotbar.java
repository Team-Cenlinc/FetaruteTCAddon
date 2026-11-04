package org.fetarute.fetaruteTCAddon.drive.inventory;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * 驾驶之外占着快捷栏的岗位（车掌、站长）：玩家当班时，发往客户端的快捷栏换成这个岗位的按钮，按键由它处理。
 *
 * <p>数据包监听只有一个（驾驶会话管理器），它先看玩家是不是在驾驶，不是时按登记顺序问各岗位。各方法在网络线程调用，实现必须线程安全、便宜、不抛异常。
 */
public interface CrewHotbar {

  /** 玩家此刻是否在这个岗位上当班（当班时快捷栏归它）。 */
  boolean isOnDuty(UUID playerId);

  /** 快捷栏的改写规则；不在岗时为空。 */
  Optional<HotbarRewriter<ItemStack>> rewriterFor(UUID playerId);

  /** 打开着的菜单上半部分的槽位数；没有打开时为 0。 */
  int menuTopSize(UUID playerId);

  /** 这一格没有按钮、空着手走动时可以用手开门（手里的真实物品照样用不了）。 */
  default boolean allowsBlockUse(int heldSlot) {
    return false;
  }

  /** 按键（数据包层截获）。要动服务器状态时自己调度回主线程。 */
  void onInput(Player player, InputSignal signal);
}
