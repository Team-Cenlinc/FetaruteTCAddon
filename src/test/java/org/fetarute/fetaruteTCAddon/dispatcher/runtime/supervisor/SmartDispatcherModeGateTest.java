package org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.junit.jupiter.api.Test;

/** {@link SmartDispatcherModeGate} 的安全隔离回归测试。 */
class SmartDispatcherModeGateTest {

  @Test
  void smartDispatcherObserveOnlyDoesNotChangeAspect() {
    assertFalse(
        SmartDispatcherModeGate.permissions(
                SmartDispatcherMode.OBSERVE_ONLY, DispatchEffectClass.SIGNAL_ADVISORY)
            .canChangeAspect());
  }

  @Test
  void smartDispatcherObserveOnlyDoesNotChangeTargetSpeed() {
    assertFalse(
        SmartDispatcherModeGate.permissions(
                SmartDispatcherMode.OBSERVE_ONLY, DispatchEffectClass.SIGNAL_ADVISORY)
            .canChangeTargetSpeed());
  }

  @Test
  void smartDispatcherObserveOnlyDoesNotClearDestination() {
    assertFalse(
        SmartDispatcherModeGate.permissions(
                SmartDispatcherMode.OBSERVE_ONLY, DispatchEffectClass.AUTHORITY_PRECHECK)
            .canClearDestination());
  }

  @Test
  void smartDispatcherObserveOnlyDoesNotInvalidateToken() {
    assertFalse(
        SmartDispatcherModeGate.permissions(
                SmartDispatcherMode.OBSERVE_ONLY, DispatchEffectClass.AUTHORITY_PRECHECK)
            .canInvalidateToken());
  }

  @Test
  void smartDispatcherObserveOnlyDoesNotSetMovementInhibited() {
    assertFalse(
        SmartDispatcherModeGate.permissions(
                SmartDispatcherMode.OBSERVE_ONLY, DispatchEffectClass.AUTHORITY_PRECHECK)
            .canSetMovementInhibited());
  }

  @Test
  void smartDispatcherObserveOnlyDoesNotMutateOccupancy() {
    assertFalse(
        SmartDispatcherModeGate.permissions(
                SmartDispatcherMode.OBSERVE_ONLY, DispatchEffectClass.OCCUPANCY_MUTATION)
            .canMutateOccupancy());
  }

  @Test
  void smartDispatcherObserveOnlyDoesNotDestroy() {
    assertFalse(
        SmartDispatcherModeGate.permissions(
                SmartDispatcherMode.OBSERVE_ONLY, DispatchEffectClass.DESTROY_ACTION)
            .canDestroy());
  }

  @Test
  void smartDispatcherObserveOnlyDoesNotRequestSignalReevaluation() {
    assertFalse(
        SmartDispatcherModeGate.permissions(
                SmartDispatcherMode.OBSERVE_ONLY, DispatchEffectClass.SIGNAL_REEVALUATION_REQUEST)
            .canRequestSignalReevaluation());
  }

  @Test
  void smartDispatcherEnforceCanRequestSignalReevaluationWithoutOtherEffects() {
    SmartDispatcherModeGate.EffectPermissions permissions =
        SmartDispatcherModeGate.permissions(
            SmartDispatcherMode.ENFORCE, DispatchEffectClass.SIGNAL_REEVALUATION_REQUEST);

    assertTrue(permissions.canRequestSignalReevaluation());
    assertFalse(permissions.canChangeAspect());
    assertFalse(permissions.canMutateOccupancy());
    assertFalse(permissions.canDestroy());
  }

  @Test
  void smartDispatcherOffDoesNotApplyDecision() {
    DispatchDecision decision = signalAdvisoryDecision();

    assertFalse(
        SmartDispatcherModeGate.permissions(SmartDispatcherMode.OFF, decision.effectClass())
            .canChangeAspect());
    assertFalse(
        SmartDispatcherModeGate.permissions(SmartDispatcherMode.OFF, decision.effectClass())
            .canChangeTargetSpeed());
  }

  @Test
  void smartDispatcherEnforceCanApplySignalAdvisory() {
    DispatchDecision decision = signalAdvisoryDecision();

    SmartDispatcherModeGate.EffectPermissions permissions =
        SmartDispatcherModeGate.permissions(SmartDispatcherMode.ENFORCE, decision.effectClass());

    assertTrue(permissions.canChangeAspect());
    assertTrue(permissions.canChangeTargetSpeed());
  }

  @Test
  void enforceSmartSignalConstraintRequiresEffectGate() {
    SmartDispatcherModeGate.EffectPermissions permissions =
        SmartDispatcherModeGate.permissions(
            SmartDispatcherMode.ENFORCE, DispatchEffectClass.SIGNAL_CONSTRAINT);

    assertTrue(permissions.canChangeAspect());
    assertTrue(permissions.canChangeTargetSpeed());
    assertFalse(permissions.canClearDestination());
    assertFalse(permissions.canInvalidateToken());
    assertFalse(permissions.canMutateOccupancy());
    assertFalse(permissions.canDestroy());
  }

  @Test
  void enforceSmartOccupancyMutationRequiresEffectGate() {
    SmartDispatcherModeGate.EffectPermissions permissions =
        SmartDispatcherModeGate.permissions(
            SmartDispatcherMode.ENFORCE, DispatchEffectClass.OCCUPANCY_MUTATION);

    assertTrue(permissions.canMutateOccupancy());
    assertFalse(permissions.canChangeAspect());
    assertFalse(permissions.canClearDestination());
    assertFalse(permissions.canInvalidateToken());
    assertFalse(permissions.canDestroy());
  }

  @Test
  void enforceSmartDestroyRequiresEffectGate() {
    SmartDispatcherModeGate.EffectPermissions permissions =
        SmartDispatcherModeGate.permissions(
            SmartDispatcherMode.ENFORCE, DispatchEffectClass.DESTROY_ACTION);

    assertTrue(permissions.canDestroy());
    assertFalse(permissions.canChangeAspect());
    assertFalse(permissions.canClearDestination());
    assertFalse(permissions.canInvalidateToken());
    assertFalse(permissions.canMutateOccupancy());
  }

  @Test
  void smartDispatcherDestroyActionSuppressedInObserveOnly() {
    assertTrue(
        SmartDispatcherModeGate.suppresses(
            SmartDispatcherMode.OBSERVE_ONLY, DispatchEffectClass.DESTROY_ACTION));
  }

  @Test
  void smartDispatcherOccupancyMutationSuppressedInObserveOnly() {
    assertTrue(
        SmartDispatcherModeGate.suppresses(
            SmartDispatcherMode.OBSERVE_ONLY, DispatchEffectClass.OCCUPANCY_MUTATION));
  }

  @Test
  void allDispatcherMutationsHaveRegisteredAction() {
    assertTrue(
        DispatchAction.RELEASE_SELF_OWNED_STALE_PROTECTIVE_RETAIN.executableDispatcherAction());
    assertTrue(DispatchAction.ACQUIRE_SPECULATIVE_UNLOCK_RESERVATION.executableDispatcherAction());
    assertTrue(DispatchAction.RELEASE_SPECULATIVE_UNLOCK_RESERVATION.executableDispatcherAction());
    assertTrue(DispatchAction.REQUEST_UNLOCK_AUTHORITY_REEVALUATION.executableDispatcherAction());
    assertTrue(DispatchAction.SMART_HEAD_ON_YIELD.executableDispatcherAction());
    assertTrue(
        DispatchAction.ACQUIRE_VERIFIED_SWITCHER_DRAIN_AUTHORITY.executableDispatcherAction());
    assertTrue(DispatchAction.SMART_ADMISSION_HOLD.executableDispatcherAction());
    assertTrue(DispatchAction.SMART_HEALTH_SIGNAL_RECOVERY.executableDispatcherAction());
    assertTrue(DispatchAction.SMART_DRAIN_UNLOCK_SIGNAL_ADVISORY.executableDispatcherAction());
    assertTrue(DispatchAction.SMART_FORWARD_UNLOCK_SIGNAL_ADVISORY.executableDispatcherAction());
    assertTrue(DispatchAction.EXECUTE_VERIFIED_DEADLOCK_DESTROY.executableDispatcherAction());
    assertTrue(DispatchAction.EXECUTE_VERIFIED_STUCK_CLEANUP.executableDispatcherAction());
    assertTrue(DispatchAction.PROCEED_WITH_CAUTION.executableDispatcherAction());
    assertTrue(DispatchAction.CAUTION_SPEED_LIMIT.executableDispatcherAction());
  }

  @Test
  void forbiddenDispatcherActionsAreNotExecutable() {
    assertFalse(DispatchAction.FORCE_PROCEED.executableDispatcherAction());
    assertFalse(DispatchAction.DESTROY_TRAIN.executableDispatcherAction());
    assertFalse(DispatchAction.CLEAR_EXTERNAL_OCCUPANCY.executableDispatcherAction());
    assertFalse(DispatchAction.CLEAR_DESTINATION.executableDispatcherAction());
    assertFalse(DispatchAction.INVALIDATE_MOVEMENT_TOKEN.executableDispatcherAction());
    assertFalse(DispatchAction.ALLOW_OPPOSITE_DIRECTION_BYPASS.executableDispatcherAction());
    assertFalse(DispatchAction.ALLOW_TURNBACK_BYPASS.executableDispatcherAction());
    assertFalse(DispatchAction.SAME_DIRECTION_FOLLOW_THROUGH_ALLOW.executableDispatcherAction());
  }

  @Test
  void observeOnlySuppressesEveryRegisteredMutationAction() {
    for (DispatchAction action : DispatchAction.values()) {
      if (!action.executableDispatcherAction()) {
        continue;
      }
      assertTrue(
          SmartDispatcherModeGate.suppresses(
              SmartDispatcherMode.OBSERVE_ONLY, action.effectClass()));
    }
  }

  @Test
  void enforceAllowsOnlyWhitelistedMutationActions() {
    for (DispatchAction action : DispatchAction.values()) {
      if (action.forbiddenDispatcherAction()) {
        assertFalse(action.executableDispatcherAction());
        continue;
      }
      if (action.dispatcherMutation()) {
        assertTrue(action.executableDispatcherAction());
      }
    }
  }

  @Test
  void typedActionGateRejectsForbiddenActionEvenInEnforce() {
    assertTrue(
        SmartDispatcherModeGate.allows(
            SmartDispatcherMode.ENFORCE, DispatchAction.SMART_HEALTH_SIGNAL_RECOVERY));
    assertTrue(
        SmartDispatcherModeGate.allows(
            SmartDispatcherMode.ENFORCE, DispatchAction.EXECUTE_VERIFIED_DEADLOCK_DESTROY));
    assertTrue(
        SmartDispatcherModeGate.allows(
            SmartDispatcherMode.ENFORCE, DispatchAction.EXECUTE_VERIFIED_STUCK_CLEANUP));
    assertFalse(
        SmartDispatcherModeGate.allows(SmartDispatcherMode.ENFORCE, DispatchAction.FORCE_PROCEED));
  }

  private static DispatchDecision signalAdvisoryDecision() {
    return new DispatchDecision(
        "train",
        DispatchAction.PROCEED_WITH_CAUTION,
        SignalAspect.PROCEED_WITH_CAUTION,
        4.0,
        OptionalLong.of(32L),
        OptionalLong.empty(),
        "none",
        RiskSource.ACTIVE_OPPOSITE_CONFLICT,
        DispatchEffectClass.SIGNAL_ADVISORY,
        "test",
        "test",
        "test",
        true,
        false,
        null);
  }
}
