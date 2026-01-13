package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

/**
 * 运行许可信号：用于调度层对“可进入/需等待”的粗粒度提示。
 *
 * <p>调度层不直接驱动车辆制动，仅输出许可等级供运行时层或信息系统使用。
 */
public enum SignalAspect {
  PROCEED,
  PROCEED_WITH_CAUTION,
  CAUTION,
  STOP
}
