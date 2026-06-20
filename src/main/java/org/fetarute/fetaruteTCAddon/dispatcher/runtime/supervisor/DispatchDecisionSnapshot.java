package org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor;

import java.time.Instant;
import java.util.List;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.SignalComputationTrace;

/**
 * Smart Dispatcher 决策输入快照。
 *
 * <p>该 record 只承载只读证据，不持有可变 runtime 集合。Dispatcher discovery/preview 可以读取它；effect layer 执行 mutation
 * 前必须用当前 runtime version 重新检查 freshness。
 */
public record DispatchDecisionSnapshot(
    long decisionVersion,
    long occupancyVersion,
    long blockerSnapshotTimestamp,
    long snapshotAgeMs,
    String trainId,
    String currentResource,
    String requestedResource,
    List<String> blockers,
    String ownerRelation,
    String directionRelation,
    SignalComputationTrace.TokenState movementTokenState,
    boolean destinationPresent,
    boolean authorityWindowPresent,
    SmartDispatcherMode mode,
    DispatchAction actionCandidate,
    String leaderTrain,
    String conflictZone,
    boolean sameDirection,
    boolean oppositeDirection,
    boolean externalBlockerPresent,
    boolean repeatedEdge,
    boolean turnback,
    boolean leaderExitVisible,
    boolean safeGapKnown,
    boolean safeGapSatisfied) {

  public DispatchDecisionSnapshot {
    blockers = blockers == null ? List.of() : List.copyOf(blockers);
    trainId = blankToDash(trainId);
    currentResource = blankToDash(currentResource);
    requestedResource = blankToDash(requestedResource);
    ownerRelation = blankToDash(ownerRelation);
    directionRelation = blankToDash(directionRelation);
    movementTokenState =
        movementTokenState == null ? SignalComputationTrace.TokenState.NONE : movementTokenState;
    mode = mode == null ? SmartDispatcherMode.OBSERVE_ONLY : mode;
    actionCandidate = actionCandidate == null ? DispatchAction.NONE : actionCandidate;
    leaderTrain = blankToDash(leaderTrain);
    conflictZone = blankToDash(conflictZone);
  }

  /** 基于 occupancy version 与快照年龄的最小 freshness 判定。 */
  public boolean freshFor(long currentOccupancyVersion, long maxAgeMs, Instant now) {
    if (occupancyVersion >= 0
        && currentOccupancyVersion >= 0
        && occupancyVersion != currentOccupancyVersion) {
      return false;
    }
    if (maxAgeMs < 0 || blockerSnapshotTimestamp <= 0L || now == null) {
      return true;
    }
    return now.toEpochMilli() - blockerSnapshotTimestamp <= maxAgeMs;
  }

  private static String blankToDash(String value) {
    return value == null || value.isBlank() ? "-" : value.trim();
  }
}
