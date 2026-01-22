package org.fetarute.fetaruteTCAddon.dispatcher.eta;

/**
 * ETA 原因/诊断标签。
 *
 * <p>HUD 与内部占位符可用该信息做额外提示；命令与调试输出亦可复用。
 */
public enum EtaReason {
  /** 无列车或列车未绑定线路。 */
  NO_VEHICLE,

  /** 线路/运行图不可用或缺少图快照。 */
  NO_ROUTE,

  /** 无法解析到目标节点（例如已终到）。 */
  NO_TARGET,

  /** 图不可达或缺少边长度导致无法估算。 */
  NO_PATH,

  /** 咽喉/道岔冲突导致硬停。 */
  THROAT,

  /** 单线走廊冲突导致硬停。 */
  SINGLELINE,

  /** 站台占用导致硬停。 */
  PLATFORM,

  /** 车库 gate 阻塞（出库/进库门控）。 */
  DEPOT_GATE,

  /** 由于占用/信号导致等待（延误）。 */
  WAIT
}
