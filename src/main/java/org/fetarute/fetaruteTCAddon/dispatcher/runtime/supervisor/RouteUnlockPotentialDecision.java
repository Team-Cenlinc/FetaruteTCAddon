package org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor;

/** 互卡 route-unlock 只读预览结论。 */
public enum RouteUnlockPotentialDecision {
  A_CAN_DRAIN_TO_UNLOCK_B,
  B_CAN_DRAIN_TO_UNLOCK_A,
  BOTH_CAN_DRAIN,
  NEITHER_CAN_DRAIN,
  UNSAFE_OPPOSITE_SINGLE_REGION,
  UNKNOWN_FAIL_SAFE
}
