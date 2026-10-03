package org.fetarute.fetaruteTCAddon.drive.inventory;

/** 驾驶员按键产生的输入信号。这些按键本来会动用玩家的真实物品，驾驶期间在数据包层被截获，只当作信号使用。 */
public enum InputSignal {
  /** {@code Q}：丢弃一件。 */
  DROP,
  /** Ctrl+{@code Q}：丢弃整组。 */
  DROP_ALL,
  /** {@code F}：与副手交换。 */
  SWAP_HANDS,
  /** 右键使用。 */
  USE,
  /** 点击背包或在创造模式拿取物品。 */
  INVENTORY
}
