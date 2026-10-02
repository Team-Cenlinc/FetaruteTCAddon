package org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor;

/** Same-direction follow-through 第一阶段只读预览结果。 */
public enum SameDirectionFollowThroughDecision {
  WOULD_ALLOW_FOLLOW_THROUGH,
  WOULD_HOLD_FOR_LEADER_EXIT,
  WOULD_DENY_UNSAFE,
  UNKNOWN_FAIL_SAFE
}
