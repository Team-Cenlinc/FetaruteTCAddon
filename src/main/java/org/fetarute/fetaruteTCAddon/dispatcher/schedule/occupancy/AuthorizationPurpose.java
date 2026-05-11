package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

/**
 * 占用请求来源。
 *
 * <p>不同来源的安全边界不同：Depot/Station/Layover 的出发门控必须按普通闭塞拒绝处理，只有运行时已经证明列车正在清空冲突区出口时， 才能升级为 {@link
 * #CONFLICT_CLEARING} 申请冲突释放。
 */
public enum AuthorizationPurpose {
  /** Depot 生成列车前后的出库门控。 */
  DEPOT_SPAWN,

  /** AutoStation/Station 停站结束后的出站门控。 */
  STATION_DEPARTURE,

  /** Layover 待命列车复用发车。 */
  LAYOVER_REUSE,

  /** 常规运行时信号 tick / 占用刷新。 */
  RUNTIME_MOVE,

  /** 已证明列车在冲突区内，目标是清空同一冲突区出口。 */
  CONFLICT_CLEARING
}
