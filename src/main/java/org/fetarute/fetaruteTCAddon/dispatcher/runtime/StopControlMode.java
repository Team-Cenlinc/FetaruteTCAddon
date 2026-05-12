package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

/** STOP 信号落地到 TrainCarts 时的控车模式。 */
public enum StopControlMode {
  /** 闭塞、授权失败、硬 blocker 等安全边界：立即撤销运动意图并硬停。 */
  HARD_STOP,

  /** 有计划停车点前的制动曲线，例如 STOP waypoint approach。 */
  BRAKING_TO_PLANNED_STOP,

  /** 已停稳后的保持状态，不允许发车。 */
  DWELL_HOLD
}
