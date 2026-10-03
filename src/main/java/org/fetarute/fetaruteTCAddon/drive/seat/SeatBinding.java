package org.fetarute.fetaruteTCAddon.drive.seat;

import java.util.Objects;

/**
 * 驾驶员与座位的绑定：列车名 + 第几节车 + 该节车内第几个座位。
 *
 * <p>列车跨世界传送后实体会重建，但列车名和编组次序不变，所以用它们而不是实体引用来找回座位。座位序号是该节车模型里座位的出现顺序。
 *
 * @param trainName 列车名
 * @param memberIndex 座位所在车厢在编组中的序号（从车头起，0 起）
 * @param seatIndex 座位在该节车厢所有座位中的序号（0 起）
 */
public record SeatBinding(String trainName, int memberIndex, int seatIndex) {

  public SeatBinding {
    Objects.requireNonNull(trainName, "trainName");
    if (memberIndex < 0 || seatIndex < 0) {
      throw new IllegalArgumentException("memberIndex 与 seatIndex 不能为负");
    }
  }

  /**
   * 驾驶员面朝的方向相对编组“前进方向”的符号：座位在前半列车为 +1，后半列车为 -1。
   *
   * <p>编组只有一节，或座位正好在中间时取 +1。这是按车厢位置做的近似，遇到反向装配的驾驶室时用换向手柄修正。
   *
   * @param memberCount 编组节数
   */
  public int cabSign(int memberCount) {
    if (memberCount <= 1) {
      return 1;
    }
    return memberIndex * 2 <= memberCount - 1 ? 1 : -1;
  }
}
