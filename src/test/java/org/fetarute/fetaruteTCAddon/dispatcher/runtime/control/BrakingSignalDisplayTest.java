package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.OptionalLong;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StopControlMode;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.junit.jupiter.api.Test;

/** 停车信号只在“还在动、有停车点、扣掉提前减速缓冲后的剩余距离够按车型减速度刹停”时显示为减速。 */
class BrakingSignalDisplayTest {

  private static final StopControlMode CURVE = StopControlMode.BRAKING_TO_PLANNED_STOP;
  private static final ConfigManager.RuntimeSettings SETTINGS = settings(true, 0.0);

  private static ConfigManager.RuntimeSettings settings(boolean curve, double earlyBrakeBlocks) {
    ConfigManager.RuntimeSettings settings = mock(ConfigManager.RuntimeSettings.class);
    when(settings.speedCurveEnabled()).thenReturn(curve);
    when(settings.speedCurveEarlyBrakeBlocks()).thenReturn(earlyBrakeBlocks);
    return settings;
  }

  @Test
  void theEarlyBrakeBufferIsNotCountedAsRoom() {
    assertEquals(
        SignalAspect.STOP,
        BrakingSignalDisplay.displayAspect(
            SignalAspect.STOP, CURVE, OptionalLong.of(61), 10.0, 1.0, settings(true, 12.0)));
    assertEquals(
        SignalAspect.CAUTION,
        BrakingSignalDisplay.displayAspect(
            SignalAspect.STOP, CURVE, OptionalLong.of(62), 10.0, 1.0, settings(true, 12.0)));
  }

  @Test
  void withoutTheSpeedCurveStopIsImmediateAndShownAsStop() {
    assertEquals(
        SignalAspect.STOP,
        BrakingSignalDisplay.displayAspect(
            SignalAspect.STOP, CURVE, OptionalLong.of(500), 10.0, 1.0, settings(false, 0.0)));
  }

  @Test
  void brakingToAStopPointWithRoomToSpareIsShownAsCaution() {
    assertEquals(
        SignalAspect.CAUTION,
        BrakingSignalDisplay.displayAspect(
            SignalAspect.STOP, CURVE, OptionalLong.of(50), 10.0, 1.0, SETTINGS));
  }

  @Test
  void notEnoughRoomToBrakeStaysStop() {
    assertEquals(
        SignalAspect.STOP,
        BrakingSignalDisplay.displayAspect(
            SignalAspect.STOP, CURVE, OptionalLong.of(49), 10.0, 1.0, SETTINGS));
  }

  @Test
  void hardStopsStandingTrainsAndMissingDistancesStayStop() {
    assertEquals(
        SignalAspect.STOP,
        BrakingSignalDisplay.displayAspect(
            SignalAspect.STOP,
            StopControlMode.HARD_STOP,
            OptionalLong.of(500),
            10.0,
            1.0,
            SETTINGS));
    assertEquals(
        SignalAspect.STOP,
        BrakingSignalDisplay.displayAspect(
            SignalAspect.STOP,
            StopControlMode.DWELL_HOLD,
            OptionalLong.of(500),
            10.0,
            1.0,
            SETTINGS));
    assertEquals(
        SignalAspect.STOP,
        BrakingSignalDisplay.displayAspect(
            SignalAspect.STOP, CURVE, OptionalLong.of(500), 0.0, 1.0, SETTINGS));
    assertEquals(
        SignalAspect.STOP,
        BrakingSignalDisplay.displayAspect(
            SignalAspect.STOP, CURVE, OptionalLong.empty(), 10.0, 1.0, SETTINGS));
  }

  @Test
  void movementAspectsAreShownAsTheyAre() {
    assertEquals(
        SignalAspect.PROCEED_WITH_CAUTION,
        BrakingSignalDisplay.displayAspect(
            SignalAspect.PROCEED_WITH_CAUTION, CURVE, OptionalLong.of(5), 10.0, 1.0, SETTINGS));
  }
}
