package org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor;

/**
 * Smart Dispatcher 动作的副作用等级。
 *
 * <p>该枚举只描述动作可能触达的运行时边界，不代表动作一定会执行。运行时必须先经过 {@link SmartDispatcherModeGate}
 * 判定，才能把诊断决策转换成真实控车、闭塞或销毁副作用。
 */
public enum DispatchEffectClass {
  /** 只产生诊断 trace，不允许改变任何运行时状态。 */
  DIAGNOSTIC_ONLY,
  /** 信号建议，例如黄灯候选或建议限速。 */
  SIGNAL_ADVISORY,
  /** 信号约束，例如保持红灯或优先级等待。 */
  SIGNAL_CONSTRAINT,
  /** 授权前置审查，只能计算是否具备后续动作资格。 */
  AUTHORITY_PRECHECK,
  /** 请求标准信号链路在下一 tick 重新计算；请求本身不得签发授权或改写占用。 */
  SIGNAL_REEVALUATION_REQUEST,
  /** 会改动占用、队列、道岔保持或 stale claim 的动作。 */
  OCCUPANCY_MUTATION,
  /** 会销毁列车或触发销毁后清理的动作。 */
  DESTROY_ACTION
}
