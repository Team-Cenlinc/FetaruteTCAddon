package org.fetarute.fetaruteTCAddon.drive.guard;

import java.util.List;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * 车掌坐在自己的座位上时，方块与实体的交互距离降为 0。
 *
 * <p>准星落不到站台、隧道壁或车厢上，右键一律是“使用物品”：客户端按住发车铃时一直处于使用状态、松开时另发一个包，按住多久就是多久。对着方块右键时服务器取消这一下，Paper
 * 随即让客户端停止使用，客户端改为每 4 tick 重发一次，按住的时长只能按重发间隔猜，网络或客户端卡一下就断开。坐着时车掌本来就只用快捷栏，不需要碰方块与实体。
 *
 * <p>离开座位（下到站台、点列车回座、换端）时撤掉。用临时修饰符：不写进玩家存档，掉线、崩服都不会留下。只在主线程调用。
 */
final class GuardSeatReach {

  private final NamespacedKey key;

  GuardSeatReach(Plugin plugin) {
    this.key = new NamespacedKey(plugin, "guard_seated_reach");
  }

  /** 按是否坐着加上或撤掉；已经是这个状态时不动。 */
  void update(Player player, boolean seated) {
    for (Attribute attribute :
        List.of(Attribute.BLOCK_INTERACTION_RANGE, Attribute.ENTITY_INTERACTION_RANGE)) {
      AttributeInstance instance = player.getAttribute(attribute);
      if (instance == null) {
        continue;
      }
      boolean lowered = instance.getModifier(key) != null;
      if (seated && !lowered) {
        // 乘以 (1 - 1)：不论原来是多少都降为 0
        instance.addTransientModifier(
            new AttributeModifier(key, -1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
      } else if (!seated && lowered) {
        instance.removeModifier(key);
      }
    }
  }
}
