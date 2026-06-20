package org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor;

import org.fetarute.fetaruteTCAddon.dispatcher.signal.SignalComputationTrace;

/**
 * 同向跟驰放行的只读预览器。
 *
 * <p>第一阶段只产生日志决策，不改变 admission、occupancy、token、destination 或 signal。缺少任何安全证据时 fail-safe。
 */
public final class SameDirectionFollowThroughPreview {

  private SameDirectionFollowThroughPreview() {}

  /** 根据不可变快照给出 observe-only 预览结论。 */
  public static SameDirectionFollowThroughDecision evaluate(DispatchDecisionSnapshot snapshot) {
    if (snapshot == null) {
      return SameDirectionFollowThroughDecision.UNKNOWN_FAIL_SAFE;
    }
    if (!snapshot.sameDirection() && !snapshot.oppositeDirection()) {
      return SameDirectionFollowThroughDecision.UNKNOWN_FAIL_SAFE;
    }
    if (snapshot.oppositeDirection()
        || snapshot.externalBlockerPresent()
        || snapshot.repeatedEdge()
        || snapshot.turnback()) {
      return SameDirectionFollowThroughDecision.WOULD_DENY_UNSAFE;
    }
    if (!snapshot.sameDirection()
        || "-".equals(snapshot.leaderTrain())
        || snapshot.movementTokenState() != SignalComputationTrace.TokenState.ACTIVE
        || !snapshot.destinationPresent()
        || !snapshot.authorityWindowPresent()
        || snapshot.snapshotAgeMs() < 0) {
      return SameDirectionFollowThroughDecision.UNKNOWN_FAIL_SAFE;
    }
    if (!snapshot.leaderExitVisible()) {
      return SameDirectionFollowThroughDecision.WOULD_HOLD_FOR_LEADER_EXIT;
    }
    if (!snapshot.safeGapKnown()) {
      return SameDirectionFollowThroughDecision.UNKNOWN_FAIL_SAFE;
    }
    if (!snapshot.safeGapSatisfied()) {
      return SameDirectionFollowThroughDecision.WOULD_DENY_UNSAFE;
    }
    return SameDirectionFollowThroughDecision.WOULD_ALLOW_FOLLOW_THROUGH;
  }
}
