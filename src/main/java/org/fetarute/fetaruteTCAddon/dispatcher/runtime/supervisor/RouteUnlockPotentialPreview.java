package org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor;

import java.util.Locale;

/** Route-based unlock 推理的 observe-only 预览器。 */
public final class RouteUnlockPotentialPreview {

  private RouteUnlockPotentialPreview() {}

  /** 只读评估两车是否存在可证明的 drain-to-unlock 潜力。 */
  public static RouteUnlockPotentialDecision evaluate(RouteUnlockPotentialSnapshot snapshot) {
    if (snapshot == null) {
      return RouteUnlockPotentialDecision.UNKNOWN_FAIL_SAFE;
    }
    String relation = snapshot.directionRelation().toUpperCase(Locale.ROOT);
    if (snapshot.singleRegion()
        && (snapshot.externalOppositeOccupantPresent()
            || relation.contains("OPPOSITE")
            || relation.contains("NOT_SAME"))) {
      return RouteUnlockPotentialDecision.UNSAFE_OPPOSITE_SINGLE_REGION;
    }
    if (snapshot.snapshotAgeMs() < 0
        || relation.contains("UNKNOWN")
        || !snapshot.trainADestinationPresent()
        || !snapshot.trainBDestinationPresent()
        || !snapshot.trainARouteWindowPresent()
        || !snapshot.trainBRouteWindowPresent()) {
      return RouteUnlockPotentialDecision.UNKNOWN_FAIL_SAFE;
    }
    boolean aCanDrain = snapshot.trainAWouldDrain() && snapshot.trainAWouldReleaseBlockerForB();
    boolean bCanDrain = snapshot.trainBWouldDrain() && snapshot.trainBWouldReleaseBlockerForA();
    if (aCanDrain && bCanDrain) {
      return RouteUnlockPotentialDecision.BOTH_CAN_DRAIN;
    }
    if (aCanDrain) {
      return RouteUnlockPotentialDecision.A_CAN_DRAIN_TO_UNLOCK_B;
    }
    if (bCanDrain) {
      return RouteUnlockPotentialDecision.B_CAN_DRAIN_TO_UNLOCK_A;
    }
    return RouteUnlockPotentialDecision.NEITHER_CAN_DRAIN;
  }
}
