package org.fetarute.fetaruteTCAddon.drive.inventory;

import com.bergerkiller.bukkit.common.events.PacketReceiveEvent;
import com.bergerkiller.bukkit.common.events.PacketSendEvent;
import com.bergerkiller.bukkit.common.protocol.PacketListener;
import com.bergerkiller.bukkit.common.protocol.PacketType;
import com.bergerkiller.bukkit.common.utils.PacketUtil;
import com.bergerkiller.generated.net.minecraft.network.protocol.game.PacketPlayInBlockDigHandle;
import com.bergerkiller.generated.net.minecraft.network.protocol.game.PacketPlayInWindowClickHandle;
import com.bergerkiller.generated.net.minecraft.network.protocol.game.PacketPlayOutSetSlotHandle;
import com.bergerkiller.generated.net.minecraft.network.protocol.game.PacketPlayOutWindowItemsHandle;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * 驾驶期间的背包数据包处理：出站时把快捷栏改写成驾驶物品，入站时截获会动用真实物品的按键。
 *
 * <p>服务器端的玩家背包从不被修改：驾驶物品只出现在发给客户端的数据包里，所以掉线、崩服、死亡、插件卸载都不会让玩家丢物品。
 *
 * <p>入站按键在数据包层截获，而不是只靠 Bukkit 事件：客户端按它看到的手持物品决定发不发包，驾驶期间每个槽位在它眼里都有物品，按键照常发包； 但服务器端那一格可能是空的，Bukkit
 * 的丢弃、使用事件未必会触发，只靠事件会漏掉输入。
 *
 * <p>回调运行在网络线程：这里只做无锁查询与取消数据包，需要触碰服务器状态的动作交给 {@link Host} 切回主线程。
 */
public final class DrivePacketListener implements PacketListener {

  /** 监听器需要的宿主能力。 */
  public interface Host {

    /** 该玩家正在驾驶时返回快捷栏改写规则，否则返回 {@code null}。运行在网络线程，实现必须线程安全。 */
    HotbarRewriter<ItemStack> rewriterFor(UUID playerId);

    /** 驾驶员的按键输入。运行在网络线程，实现必须自行切回主线程处理。 */
    void onInput(Player player, InputSignal signal);

    /** 驾驶员打开着的停车后菜单上半部分的槽位数；没有打开菜单时为 0。运行在网络线程，实现必须线程安全。 */
    default int menuTopSize(UUID playerId) {
      return 0;
    }

    /** 改写发给驾驶员的背包数据包失败（此时客户端会看到真实的快捷栏物品）。运行在网络线程，实现应当限频。 */
    default void onRewriteFailed(Player player, RuntimeException cause) {}

    /** 驾驶员按下了潜行键（离座意图）。运行在网络线程，实现必须自行切回主线程处理。 */
    default void onSneak(Player player) {}

    /** 诊断输出：驾驶员触发了某个数据包。运行在网络线程。 */
    default void trace(Player player, String message) {}
  }

  private static final PacketType[] WATCHED = {
    PacketType.OUT_WINDOW_SET_SLOT,
    PacketType.OUT_WINDOW_ITEMS,
    PacketType.IN_BLOCK_DIG,
    PacketType.IN_BLOCK_PLACE,
    PacketType.IN_USE_ITEM,
    PacketType.IN_WINDOW_CLICK,
    PacketType.IN_SET_CREATIVE_SLOT,
    PacketType.IN_ENTITY_ACTION
  };

  private final Host host;

  public DrivePacketListener(Host host) {
    this.host = host;
  }

  /** 注册到 BKCommonLib。 */
  public void register(Plugin plugin) {
    PacketUtil.addPacketListener(plugin, this, WATCHED);
  }

  /** 注销本插件注册的全部数据包监听。 */
  public static void unregister(Plugin plugin) {
    PacketUtil.removePacketListeners(plugin);
  }

  @Override
  public void onPacketSend(PacketSendEvent event) {
    Player player = event.getPlayer();
    if (player == null) {
      return;
    }
    HotbarRewriter<ItemStack> rewriter = host.rewriterFor(player.getUniqueId());
    if (rewriter == null) {
      return;
    }
    PacketType type = event.getType();
    if (type == PacketType.OUT_WINDOW_SET_SLOT) {
      PacketPlayOutSetSlotHandle packet =
          PacketPlayOutSetSlotHandle.createHandle(event.getPacket().getHandle());
      if (rewriter.isHotbarSlot(packet.getWindowId(), packet.getSlot())) {
        packet.setItem(
            rewriter.itemFor(
                packet.getWindowId(), packet.getSlot(), packet.getItem(), ItemStack::clone));
      }
    } else if (type == PacketType.OUT_WINDOW_ITEMS) {
      PacketPlayOutWindowItemsHandle packet =
          PacketPlayOutWindowItemsHandle.createHandle(event.getPacket().getHandle());
      try {
        // 就地改写：该包是 record，不能整个替换列表字段，只能改它携带的那个列表里的元素。
        int windowId = packet.getWindowId();
        if (rewriter.rewriteInPlace(windowId, packet.getItems(), ItemStack::clone)
            && !rewriter.showsDriveItems(windowId, packet.getItems(), DrivePacketListener::same)) {
          // 拿到的列表若是副本，改写不会抛异常却也写不进数据包：读回来确认。
          host.onRewriteFailed(player, new IllegalStateException("改写没有写进背包数据包"));
        }
      } catch (RuntimeException ex) {
        host.onRewriteFailed(player, ex);
      }
    }
  }

  private static boolean same(ItemStack sent, ItemStack driveItem) {
    return sent != null && sent.isSimilar(driveItem);
  }

  @Override
  public void onPacketReceive(PacketReceiveEvent event) {
    Player player = event.getPlayer();
    if (player == null || host.rewriterFor(player.getUniqueId()) == null) {
      return;
    }
    PacketType type = event.getType();
    if (type == PacketType.IN_BLOCK_DIG) {
      handleDig(event, player);
    } else if (type == PacketType.IN_BLOCK_PLACE) {
      // 对空气右键：没有方块预测需要回滚，直接取消。
      event.setCancelled(true);
      host.trace(player, "右键使用(空气)");
      host.onInput(player, InputSignal.USE);
    } else if (type == PacketType.IN_USE_ITEM) {
      // 对方块右键：取消数据包会让客户端的方块预测悬空，这里只读取信号，由事件拦截取消并让服务器纠正客户端。
      host.trace(player, "右键使用(方块)");
      host.onInput(player, InputSignal.USE);
    } else if (type == PacketType.IN_ENTITY_ACTION) {
      // 只读取，不取消：离座由 TrainCarts 自己处理，我们只需要知道这是主动离座。
      handleEntityAction(event, player);
    } else if (type == PacketType.IN_WINDOW_CLICK && isMenuButtonClick(event, player)) {
      // 停车后菜单上半部分的左键、右键点击放行到服务器，由事件监听取消并处理；其余点击一律截获。
      host.trace(player, "菜单点击");
    } else if (type == PacketType.IN_WINDOW_CLICK || type == PacketType.IN_SET_CREATIVE_SLOT) {
      event.setCancelled(true);
      host.trace(player, "背包点击");
      host.onInput(player, InputSignal.INVENTORY);
    }
  }

  /** 是不是对停车后菜单上半部分槽位的普通左键或右键点击。读不出来就按不是处理（即截获）。 */
  private boolean isMenuButtonClick(PacketReceiveEvent event, Player player) {
    int top = host.menuTopSize(player.getUniqueId());
    if (top <= 0) {
      return false;
    }
    try {
      PacketPlayInWindowClickHandle packet =
          PacketPlayInWindowClickHandle.createHandle(event.getPacket().getHandle());
      return packet.getWindowId() > 0
          && packet.getSlot() >= 0
          && packet.getSlot() < top
          && "PICKUP".equals(packet.getMode().name())
          && (packet.getButton() == 0 || packet.getButton() == 1);
    } catch (RuntimeException | LinkageError ex) {
      return false;
    }
  }

  private void handleEntityAction(PacketReceiveEvent event, Player player) {
    try {
      String action = ((Enum<?>) event.getPacket().read(PacketType.IN_ENTITY_ACTION.action)).name();
      if (action.equals("START_SNEAKING") || action.equals("PRESS_SHIFT_KEY")) {
        host.trace(player, "潜行");
        host.onSneak(player);
      }
    } catch (RuntimeException | LinkageError ex) {
      host.trace(player, "无法读取实体动作数据包: " + ex);
    }
  }

  private void handleDig(PacketReceiveEvent event, Player player) {
    String action;
    try {
      PacketPlayInBlockDigHandle packet =
          PacketPlayInBlockDigHandle.createHandle(event.getPacket().getHandle());
      action = ((Enum<?>) packet.getDigType().getRaw()).name();
    } catch (RuntimeException | LinkageError ex) {
      // 读不出动作就无法确认它不是丢弃：驾驶员坐在车里，取消一个挖掘类数据包没有代价。
      event.setCancelled(true);
      host.trace(player, "无法识别的动作数据包，已取消: " + ex);
      return;
    }
    switch (action) {
      case "DROP_ITEM" -> {
        event.setCancelled(true);
        host.trace(player, "丢弃");
        host.onInput(player, InputSignal.DROP);
      }
      case "DROP_ALL_ITEMS" -> {
        event.setCancelled(true);
        host.trace(player, "丢弃整组");
        host.onInput(player, InputSignal.DROP_ALL);
      }
      case "SWAP_ITEM_WITH_OFFHAND" -> {
        event.setCancelled(true);
        host.trace(player, "与副手交换");
        host.onInput(player, InputSignal.SWAP_HANDS);
      }
      case "RELEASE_USE_ITEM" -> event.setCancelled(true);
      default -> {
        // 挖掘方块等动作放行，由事件拦截统一处理。
      }
    }
  }
}
