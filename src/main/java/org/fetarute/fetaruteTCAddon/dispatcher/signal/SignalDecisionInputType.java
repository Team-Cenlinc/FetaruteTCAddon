package org.fetarute.fetaruteTCAddon.dispatcher.signal;

/** 信号判定输入类型。用于区分“前向行车授权”和“占用保留请求”，避免保留请求反向放行列车。 */
public enum SignalDecisionInputType {
  /** 真实前向行车请求，且包含 MOVEMENT_REQUIRED 资源。 */
  FORWARD_MOVEMENT,

  /** 已验证的冲突区清空请求。 */
  CONFLICT_CLEARING,

  /** drain-through 行车请求，必须同时具备有效 drain authority。 */
  DRAIN_THROUGH,

  /** 停车/hold 时保留既有资源的请求。 */
  HOLD_RETAIN,

  /** 当前位置或车尾保护资源请求。 */
  PROTECTIVE_RETAIN,

  /** 非前向授权的位置保持请求。 */
  POSITION_RETAIN,

  /** 无法安全分类的请求。 */
  UNKNOWN
}
