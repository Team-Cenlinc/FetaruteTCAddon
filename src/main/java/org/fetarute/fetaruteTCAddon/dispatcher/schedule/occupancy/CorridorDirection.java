package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

/** 单线走廊的占用方向（A/B 对应冲突 key 中的左右端点）。 */
public enum CorridorDirection {
  A_TO_B,
  B_TO_A,
  UNKNOWN;

  /**
   * @return 方向反转；UNKNOWN 保持不变。
   */
  public CorridorDirection opposite() {
    return switch (this) {
      case A_TO_B -> B_TO_A;
      case B_TO_A -> A_TO_B;
      case UNKNOWN -> UNKNOWN;
    };
  }
}
