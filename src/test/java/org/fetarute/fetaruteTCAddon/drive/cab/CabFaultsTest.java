package org.fetarute.fetaruteTCAddon.drive.cab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class CabFaultsTest {

  private static final FaultConfig DEFAULTS = FaultConfig.defaults();

  /** 固定输出的随机数：nextDouble 恒为 {@code value}，nextInt 恒为 {@code index}。 */
  private static RandomGenerator fixed(double value, int index) {
    return new RandomGenerator() {
      @Override
      public long nextLong() {
        return 0L;
      }

      @Override
      public double nextDouble() {
        return value;
      }

      @Override
      public int nextInt(int bound) {
        return Math.min(index, bound - 1);
      }
    };
  }

  @Test
  void dieselTrainsHaveNoLineOrBreakerFaults() {
    CabFaults diesel = new CabFaults(DEFAULTS, false);

    assertEquals(CabFaults.Outcome.NOT_APPLICABLE, diesel.inject(CabFault.LINE_LOSS, 0));
    assertEquals(CabFaults.Outcome.NOT_APPLICABLE, diesel.inject(CabFault.BREAKER_TRIP, 0));
    assertEquals(CabFaults.Outcome.INJECTED, diesel.inject(CabFault.COMPRESSOR, 0));
    assertEquals(CabFaults.Outcome.ALREADY_ACTIVE, diesel.inject(CabFault.COMPRESSOR, 0));
  }

  @Test
  void lineVoltageReturnsByItselfAfterTheConfiguredTime() {
    CabFaults faults = new CabFaults(DEFAULTS, true);
    faults.inject(CabFault.LINE_LOSS, 100);
    assertEquals(
        List.of(new CabFaults.Event(CabFault.LINE_LOSS, CabFaults.Kind.INJECTED)),
        faults.takeEvents());
    assertTrue(faults.linePowerLost());

    faults.tick(100 + DEFAULTS.lineLossTicks() - 1, false);
    assertTrue(faults.active(CabFault.LINE_LOSS));

    faults.tick(100 + DEFAULTS.lineLossTicks(), false);
    assertFalse(faults.active(CabFault.LINE_LOSS));
    assertFalse(faults.linePowerLost());
    assertEquals(
        List.of(new CabFaults.Event(CabFault.LINE_LOSS, CabFaults.Kind.RECOVERED)),
        faults.takeEvents());
  }

  @Test
  void aTrippedBreakerIsResetByOpeningAndThenClosingIt() {
    CabFaults faults = new CabFaults(DEFAULTS, true);
    assertEquals(CabFaults.BreakerClick.NOT_TRIPPED, faults.clickBreaker(0, 60));

    faults.inject(CabFault.BREAKER_TRIP, 0);
    assertEquals(Optional.of(CabFaults.BreakerStage.TRIPPED), faults.breakerStage());
    assertTrue(faults.linePowerLost(), "主断跳闸后没有网压");

    assertEquals(CabFaults.BreakerClick.OPENED, faults.clickBreaker(10, 60));
    assertEquals(Optional.of(CabFaults.BreakerStage.OPENED), faults.breakerStage());
    assertEquals(CabFaults.BreakerClick.CLOSING, faults.clickBreaker(20, 60));
    assertEquals(CabFaults.BreakerClick.BUSY, faults.clickBreaker(30, 60));
    assertEquals(40, faults.breakerRemainingTicks(40));

    faults.tick(79, false);
    assertTrue(faults.active(CabFault.BREAKER_TRIP));
    faults.tick(80, false);
    assertFalse(faults.active(CabFault.BREAKER_TRIP));
    assertTrue(faults.breakerStage().isEmpty());
    assertEquals(CabFaults.BreakerClick.NOT_TRIPPED, faults.clickBreaker(90, 60));
  }

  @Test
  void theDoorBypassLiftsTheDoorLoopBlockAndStaysUntilTheDriverRestoresIt() {
    CabFaults faults = new CabFaults(DEFAULTS, true);
    faults.inject(CabFault.DOOR, 0);
    assertTrue(faults.doorCircuitOpen());

    assertTrue(faults.toggleDoorBypass());
    assertFalse(faults.doorCircuitOpen());

    assertEquals(1, faults.clearAll());
    assertTrue(faults.doorBypassed(), "门旁路是驾驶员的开关，清除故障不复位");
    assertFalse(faults.toggleDoorBypass());
  }

  @Test
  void clearingReportsEveryFaultAndResetsTheBreaker() {
    CabFaults faults = new CabFaults(DEFAULTS, true);
    faults.inject(CabFault.BREAKER_TRIP, 0);
    faults.inject(CabFault.BRAKE_LEAK, 0);
    faults.takeEvents();

    assertEquals(2, faults.clearAll());

    assertFalse(faults.any());
    assertTrue(faults.breakerStage().isEmpty());
    assertEquals(
        List.of(
            new CabFaults.Event(CabFault.BREAKER_TRIP, CabFaults.Kind.CLEARED),
            new CabFaults.Event(CabFault.BRAKE_LEAK, CabFaults.Kind.CLEARED)),
        faults.takeEvents());
    assertEquals(0, faults.clearAll());
  }

  @Test
  void randomFaultsNeedTheSwitchAndAnEligibleSession() {
    FaultConfig enabled = new FaultConfig(true, 0.5, EnumSet.allOf(CabFault.class), 600, 6.0);
    CabFaults off = new CabFaults(DEFAULTS, true, fixed(0.0, 0));
    CabFaults notEligible = new CabFaults(enabled, true, fixed(0.0, 0));
    CabFaults on = new CabFaults(enabled, true, fixed(0.0, 2));

    for (long t = 0; t < 100; t++) {
      off.tick(t, true);
      notEligible.tick(t, false);
      on.tick(t, true);
    }

    assertFalse(off.any(), "随机故障默认关闭");
    assertFalse(notEligible.any(), "驾驶员不在座或列车未启动时不发生");
    assertEquals(List.of(CabFault.COMPRESSOR), on.activeFaults());
    assertEquals(
        List.of(new CabFaults.Event(CabFault.COMPRESSOR, CabFaults.Kind.RANDOM)), on.takeEvents());
  }

  @Test
  void randomFaultsRollOncePerSecondAndOnlyPickApplicableTypes() {
    FaultConfig lineOnly = new FaultConfig(true, 0.5, EnumSet.of(CabFault.LINE_LOSS), 600, 6.0);
    CabFaults diesel = new CabFaults(lineOnly, false, fixed(0.0, 0));
    for (long t = 0; t < 200; t++) {
      diesel.tick(t, true);
    }
    assertFalse(diesel.any(), "内燃车没有可选的随机故障");

    double justAbove = lineOnly.chancePerSecond() * 1.01;
    CabFaults unlucky = new CabFaults(lineOnly, true, fixed(justAbove, 0));
    for (long t = 0; t < 2000; t++) {
      unlucky.tick(t, true);
    }
    assertFalse(unlucky.any(), "掷出的数不低于每秒概率就不发生");
  }

  @Test
  void theHourlyChanceAddsUpOverAnHourOfSeconds() {
    FaultConfig config = new FaultConfig(true, 0.5, EnumSet.allOf(CabFault.class), 600, 6.0);
    double perSecond = config.chancePerSecond();

    assertEquals(0.5, 1.0 - Math.pow(1.0 - perSecond, 3600), 1e-9);
    assertEquals(
        0.0, new FaultConfig(true, 0.0, EnumSet.allOf(CabFault.class), 600, 6.0).chancePerSecond());
  }
}
