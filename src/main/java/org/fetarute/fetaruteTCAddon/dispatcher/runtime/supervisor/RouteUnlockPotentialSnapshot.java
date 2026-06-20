package org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor;

import java.util.List;

/** Route-unlock 潜力诊断的不可变输入快照。 */
public record RouteUnlockPotentialSnapshot(
    String trainA,
    String trainB,
    String conflictZone,
    boolean singleRegion,
    String directionRelation,
    String trainACurrentResource,
    String trainBCurrentResource,
    boolean trainADestinationPresent,
    boolean trainBDestinationPresent,
    boolean trainARouteWindowPresent,
    boolean trainBRouteWindowPresent,
    List<String> trainAHeldResources,
    List<String> trainBHeldResources,
    List<String> trainARequestedResources,
    List<String> trainBRequestedResources,
    boolean trainAWouldDrain,
    boolean trainBWouldDrain,
    boolean trainAWouldReleaseBlockerForB,
    boolean trainBWouldReleaseBlockerForA,
    boolean externalOppositeOccupantPresent,
    long snapshotAgeMs,
    long occupancyVersion,
    long decisionVersion) {

  public RouteUnlockPotentialSnapshot {
    trainA = blankToDash(trainA);
    trainB = blankToDash(trainB);
    conflictZone = blankToDash(conflictZone);
    directionRelation = blankToDash(directionRelation);
    trainACurrentResource = blankToDash(trainACurrentResource);
    trainBCurrentResource = blankToDash(trainBCurrentResource);
    trainAHeldResources =
        trainAHeldResources == null ? List.of() : List.copyOf(trainAHeldResources);
    trainBHeldResources =
        trainBHeldResources == null ? List.of() : List.copyOf(trainBHeldResources);
    trainARequestedResources =
        trainARequestedResources == null ? List.of() : List.copyOf(trainARequestedResources);
    trainBRequestedResources =
        trainBRequestedResources == null ? List.of() : List.copyOf(trainBRequestedResources);
  }

  private static String blankToDash(String value) {
    return value == null || value.isBlank() ? "-" : value.trim();
  }
}
