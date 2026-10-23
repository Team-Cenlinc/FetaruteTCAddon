package org.fetarute.fetaruteTCAddon.drive.inventory;

/** 驾驶员按键产生的输入信号。这些按键本来会动用玩家的真实物品，驾驶期间在数据包层被截获，只当作信号使用。 */
public enum InputSignal {
  /** {@code Q}：丢弃一件。 */
  DROP,
  /** Ctrl+{@code Q}：丢弃整组。 */
  DROP_ALL,
  /** {@code F}：与副手交换。 */
  SWAP_HANDS,
  /** 右键使用（客户端的“使用物品”包）：准星没对着方块或实体，或对着方块、实体时在交互之后另发的那一个。 */
  USE,
  /** 准星对着方块时右键：客户端先发对方块使用，方块没有吃掉这一下时再另发一个 {@link #USE}。按住时同样每 4 tick 重发。 */
  USE_ON_BLOCK,
  /**
   * 准星对着实体时右键（站台屏的展示框、空车厢等）：客户端发的是与实体交互，按住时同样每 4 tick 重发；对多数实体客户端不再另发“使用”，只看 {@link #USE} 会漏掉这些右键。
   */
  USE_ENTITY,
  /** 松开右键：只在客户端处于使用状态（拿着“可使用”的物品按住右键，如车掌的发车铃）时才发。 */
  RELEASE_USE,
  /** 点击背包或在创造模式拿取物品。 */
  INVENTORY
}
