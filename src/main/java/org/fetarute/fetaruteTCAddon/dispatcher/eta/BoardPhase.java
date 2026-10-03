package org.fetarute.fetaruteTCAddon.dispatcher.eta;

/** 站牌行所处的阶段，按离本站由远到近排列。 */
public enum BoardPhase {
  /** 未出票的预测班次（按发车计划或时刻表推算）。 */
  FORECAST,
  /** 已出票、尚未发车。 */
  PENDING,
  /** 运行中，尚未临近本站。 */
  EN_ROUTE,
  /** 即将到达或通过本站。 */
  ARRIVING,
  /** 已停在本站，尚未获准发车。 */
  AT_STATION
}
