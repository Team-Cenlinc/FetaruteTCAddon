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
