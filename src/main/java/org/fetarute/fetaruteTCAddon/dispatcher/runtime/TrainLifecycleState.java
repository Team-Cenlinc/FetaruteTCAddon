package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

/** 列车在调度系统中的生命周期状态。 */
public enum TrainLifecycleState {
  /** 正常运营中（执行 Route 任务）。 */
  IN_SERVICE,

  /** 正在进入终到流程（接近或停靠 TERM 站点）。 */
  TERMINATING,

  /** 在终到站/存车线待命（已完成停站，等待下一张票据）。 */
  LAYOVER,

  /** 待命已就绪（门已关、位置合法，可随时接受调度）。 */
  LAYOVER_READY,

  /** 已分配新票据，准备出站。 */
  DISPATCHING,

  /** 停放在侧线缓冲池中（等待腾挪回站台）。 */
  SIDING_PARKED,

  /** 回库/回送中（非载客任务）。 */
  DEADHEADING,

  /** 已销毁或即将销毁。 */
  DESTROYED
}
