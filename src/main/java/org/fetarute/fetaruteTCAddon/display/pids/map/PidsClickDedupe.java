package org.fetarute.fetaruteTCAddon.display.pids.map;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 同一玩家同一 tick 只认一次点击：右键展示框时主手、副手各触发一次交互事件，BKC 各转成一次地图点击；点到方块边缘时还可能多一次方块交互。
 * 不去重的话一次右键会把按钮按两下（例如人工、自动来回切换）。
 *
 * <p>只记当前这一 tick 点过的玩家，换了 tick 就清空，不会越积越多。只在服务器主线程用。
 */
final class PidsClickDedupe {

  private final Set<UUID> clicked = new HashSet<>();
  private long tick = Long.MIN_VALUE;

  /**
   * @param player 点击的玩家
   * @param now 当前 tick
   * @return 是这名玩家这一 tick 的第一次点击
   */
  boolean first(UUID player, long now) {
    if (now != tick) {
      tick = now;
      clicked.clear();
    }
    return clicked.add(player);
  }
}
