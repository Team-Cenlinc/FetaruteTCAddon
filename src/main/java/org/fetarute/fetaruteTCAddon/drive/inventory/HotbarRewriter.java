package org.fetarute.fetaruteTCAddon.drive.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.IntSupplier;
import java.util.function.UnaryOperator;

/**
 * 背包数据包里快捷栏槽位的改写规则：把发往客户端的快捷栏九格换成驾驶物品，服务器端的真实背包不动。
 *
 * <p>玩家背包窗口（编号 0）里快捷栏是槽位 36–44；旧版协议还可能用窗口编号 -2 直接按 0–8 更新玩家背包槽位，这里一并处理。
 * 驾驶员打开停车后菜单时，菜单窗口（编号为正）的下半部分是玩家背包，最后九格同样是快捷栏，也要改写。其它窗口与槽位原样放行。 规则与物品类型无关，便于单测。
 *
 * @param <T> 物品类型
 */
public final class HotbarRewriter<T> {

  /** 玩家背包窗口的编号。 */
  public static final int PLAYER_WINDOW = 0;

  /** 玩家背包窗口里快捷栏的第一个槽位。 */
  public static final int HOTBAR_FIRST_SLOT = 36;

  /** 直接更新玩家背包槽位的特殊窗口编号（槽位 0–8 即快捷栏）。 */
  public static final int DIRECT_INVENTORY_WINDOW = -2;

  /** 玩家背包窗口的完整槽位数：合成输出、合成格、盔甲、主背包、快捷栏与副手。 */
  public static final int PLAYER_WINDOW_SIZE = 46;

  private static final int HOTBAR_SIZE = 9;

  /** 箱子式窗口下半部分玩家背包里，快捷栏之前的主背包格数。 */
  private static final int MAIN_INVENTORY_SIZE = 27;

  private final List<T> hotbar;
  private final IntSupplier menuTopSize;

  /**
   * @param hotbar 九格驾驶物品，按快捷栏槽位顺序
   */
  public HotbarRewriter(List<T> hotbar) {
    this(hotbar, () -> 0);
  }

  /**
   * @param hotbar 九格驾驶物品，按快捷栏槽位顺序
   * @param menuTopSize 当前打开的菜单窗口上半部分的槽位数；没有打开菜单时为 0。运行在网络线程，必须线程安全
   */
  public HotbarRewriter(List<T> hotbar, IntSupplier menuTopSize) {
    Objects.requireNonNull(hotbar, "hotbar");
    if (hotbar.size() != HOTBAR_SIZE) {
      throw new IllegalArgumentException("快捷栏需要 9 格物品");
    }
    this.hotbar = List.copyOf(hotbar);
    this.menuTopSize = Objects.requireNonNull(menuTopSize, "menuTopSize");
  }

  /** 该窗口与槽位是否属于快捷栏。 */
  public boolean isHotbarSlot(int windowId, int slot) {
    return hotbarIndex(windowId, slot) >= 0;
  }

  /**
   * 单个槽位更新的替换物品。
   *
   * @param copier 取出驾驶物品时的复制方式（物品对象可变，不能把同一个实例发给多个数据包）
   * @return 快捷栏槽位返回驾驶物品的副本；其它槽位返回原物品
   */
  public T itemFor(int windowId, int slot, T original, UnaryOperator<T> copier) {
    int index = hotbarIndex(windowId, slot);
    return index < 0 ? original : copier.apply(hotbar.get(index));
  }

  /**
   * 整个窗口内容更新的替换结果（新列表，不改动传入的原列表）。
   *
   * @return 玩家背包窗口或菜单窗口且槽位数对得上时返回改写后的新列表；其它情况返回原列表
   */
  public List<T> contentsFor(int windowId, List<T> original, UnaryOperator<T> copier) {
    if (original == null) {
      return original;
    }
    int first = firstHotbarSlot(windowId, original.size());
    if (first < 0) {
      return original;
    }
    List<T> rewritten = new ArrayList<>(original);
    replaceHotbar(rewritten, first, copier);
    return rewritten;
  }

  /**
   * 就地改写整个窗口内容更新里的快捷栏。
   *
   * <p>服务端的内容更新包是不可替换字段的 record，只能改它携带的那个列表本身：这个列表是发送时新建的副本，改它的元素只影响这一个包， 不会碰到服务器上的玩家背包。
   *
   * @param items 数据包携带的列表；必须可写，否则抛出 {@link UnsupportedOperationException}
   * @return 是否属于需要改写的窗口并已改写
   */
  public boolean rewriteInPlace(int windowId, List<T> items, UnaryOperator<T> copier) {
    if (items == null) {
      return false;
    }
    int first = firstHotbarSlot(windowId, items.size());
    if (first < 0) {
      return false;
    }
    replaceHotbar(items, first, copier);
    return true;
  }

  /**
   * 窗口内容里的快捷栏是不是已经是驾驶物品。用于确认就地改写真的写进了数据包。
   *
   * @param same 判断数据包里的物品与驾驶物品是否相同
   * @return 不需要改写的窗口返回 {@code true}
   */
  public boolean showsDriveItems(int windowId, List<T> items, BiPredicate<T, T> same) {
    if (items == null) {
      return true;
    }
    int first = firstHotbarSlot(windowId, items.size());
    if (first < 0) {
      return true;
    }
    for (int i = 0; i < HOTBAR_SIZE; i++) {
      if (!same.test(items.get(first + i), hotbar.get(i))) {
        return false;
      }
    }
    return true;
  }

  private void replaceHotbar(List<T> target, int first, UnaryOperator<T> copier) {
    for (int i = 0; i < HOTBAR_SIZE; i++) {
      target.set(first + i, copier.apply(hotbar.get(i)));
    }
  }

  /** 窗口内容里快捷栏的起始槽位；这个窗口不需要改写时为 -1。 */
  private int firstHotbarSlot(int windowId, int size) {
    if (windowId == PLAYER_WINDOW) {
      return size >= HOTBAR_FIRST_SLOT + HOTBAR_SIZE ? HOTBAR_FIRST_SLOT : -1;
    }
    int top = menuTopSize.getAsInt();
    if (windowId > 0 && top > 0 && size == top + MAIN_INVENTORY_SIZE + HOTBAR_SIZE) {
      return top + MAIN_INVENTORY_SIZE;
    }
    return -1;
  }

  private int hotbarIndex(int windowId, int slot) {
    if (windowId == PLAYER_WINDOW
        && slot >= HOTBAR_FIRST_SLOT
        && slot < HOTBAR_FIRST_SLOT + HOTBAR_SIZE) {
      return slot - HOTBAR_FIRST_SLOT;
    }
    if (windowId == DIRECT_INVENTORY_WINDOW && slot >= 0 && slot < HOTBAR_SIZE) {
      return slot;
    }
    int top = menuTopSize.getAsInt();
    if (windowId > 0 && top > 0) {
      int first = top + MAIN_INVENTORY_SIZE;
      if (slot >= first && slot < first + HOTBAR_SIZE) {
        return slot - first;
      }
    }
    return -1;
  }
}
