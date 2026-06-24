package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

/**
 * 单线资源的占用方向。
 *
 * <p>A/B 是同一个资源内的二值方向标签，优先由站间语义轴（如 PPK→RVS）映射得到；缺少语义元数据时才退回旧的物理端点顺序。
 */
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
