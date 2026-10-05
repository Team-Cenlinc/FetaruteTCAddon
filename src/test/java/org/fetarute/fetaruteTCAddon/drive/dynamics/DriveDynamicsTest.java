package org.fetarute.fetaruteTCAddon.drive.dynamics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fetarute.fetaruteTCAddon.drive.DriveConfig;
import org.junit.jupiter.api.Test;

class DriveDynamicsTest {

  private static final double DT = 1.0 / 20.0;
  private static final double CAP = 22.0;

  private final DriveConfig config = DriveConfig.defaults();
  private final DriveParams params = new DriveParams(DriveMode.MU, 1.0, 1.0, 22.0, 0.5);

  private DriveDynamics newDynamics() {
    return new DriveDynamics(params, config);
  }

  private static void run(DriveDynamics dynamics, Notch notch, double cap, int ticks) {
    for (int i = 0; i < ticks; i++) {
      dynamics.step(DT, notch, cap);
    }
  }

  @Test
  void tractionRaisesSpeedUpToTheCapAndNotBeyond() {
    DriveDynamics dynamics = newDynamics();
    double previous = 0.0;
    for (int i = 0; i < 20 * 60; i++) {
      double speed = dynamics.step(DT, Notch.P3, CAP);
      assertTrue(speed >= previous - 1e-12, "牵引时速度不应下降");
      assertTrue(speed <= CAP + 1e-12);
      previous = speed;
    }
    assertEquals(CAP, dynamics.speedBps(), 1e-9);
  }

  @Test
  void effortRisesAtTheConfiguredRatherThanInstantly() {
    DriveDynamics dynamics = newDynamics();

    dynamics.step(DT, Notch.P3, CAP);

    assertEquals(config.effortRatePerSecond() * DT, dynamics.effort(), 1e-12);
    assertTrue(dynamics.effort() < config.tractionFraction(Notch.P3));
  }

  @Test
  void higherTractionNotchAccelerateFaster() {
    DriveDynamics low = newDynamics();
    DriveDynamics high = newDynamics();

    run(low, Notch.P1, CAP, 100);
    run(high, Notch.P3, CAP, 100);

    assertTrue(high.speedBps() > low.speedBps());
  }

  @Test
  void brakingStopsTheTrainAndNeverGoesNegative() {
    DriveDynamics dynamics = newDynamics();
    dynamics.reset(15.0);

    for (int i = 0; i < 20 * 60; i++) {
      double speed = dynamics.step(DT, Notch.B4, CAP);
      assertTrue(speed >= 0.0);
    }
    assertEquals(0.0, dynamics.speedBps(), 1e-12);
  }

  @Test
  void emergencyBrakeStopsFasterThanTheStrongestServiceBrake() {
    DriveDynamics service = newDynamics();
    DriveDynamics emergency = newDynamics();
    service.reset(20.0);
    emergency.reset(20.0);

    int serviceTicks = ticksToStop(service, Notch.B4);
    int emergencyTicks = ticksToStop(emergency, Notch.EB);

    assertTrue(emergencyTicks < serviceTicks, emergencyTicks + " 应小于 " + serviceTicks);
  }

  private static int ticksToStop(DriveDynamics dynamics, Notch notch) {
    int ticks = 0;
    while (dynamics.speedBps() > 0.0 && ticks < 20 * 120) {
      dynamics.step(DT, notch, CAP);
      ticks++;
    }
    return ticks;
  }

  @Test
  void coastingOnlyLosesSpeedToDrag() {
    DriveDynamics dynamics = newDynamics();
    dynamics.reset(10.0);

    run(dynamics, Notch.N, CAP, 20 * 10);

    assertEquals(10.0 - config.coastDragBps2() * 10.0, dynamics.speedBps(), 1e-6);
  }

  @Test
  void aStandingTrainStaysStandingAtCoast() {
    DriveDynamics dynamics = newDynamics();

    run(dynamics, Notch.N, CAP, 100);

    assertEquals(0.0, dynamics.speedBps(), 0.0);
  }

  @Test
  void speedFollowsALowerLimitAndTheTrainMaximum() {
    DriveDynamics dynamics = newDynamics();

    run(dynamics, Notch.P3, 5.0, 20 * 30);
    assertEquals(5.0, dynamics.speedBps(), 1e-9);

    run(dynamics, Notch.P3, 3.0, 1);
    assertEquals(3.0, dynamics.speedBps(), 1e-9);

    DriveDynamics capped = newDynamics();
    run(capped, Notch.P3, 99.0, 20 * 120);
    assertEquals(params.maxSpeedBps(), capped.speedBps(), 1e-9);
  }

  @Test
  void changingFromTractionToBrakeTakesTimeToSwingTheForce() {
    DriveDynamics dynamics = newDynamics();
    dynamics.reset(10.0);
    run(dynamics, Notch.P3, CAP, 40);
    assertTrue(dynamics.effort() > 0.0);

    dynamics.step(DT, Notch.B4, CAP);
    assertTrue(dynamics.effort() > 0.0, "换档一 tick 后牵引力还没有反向");

    run(dynamics, Notch.B4, CAP, 40);
    assertEquals(-config.brakeFraction(Notch.B4), dynamics.effort(), 1e-9);
  }

  @Test
  void resetClearsTheForceAndKeepsTheGivenSpeed() {
    DriveDynamics dynamics = newDynamics();
    run(dynamics, Notch.P3, CAP, 40);

    dynamics.reset(7.5);

    assertEquals(7.5, dynamics.speedBps(), 0.0);
    assertEquals(0.0, dynamics.effort(), 0.0);
    assertEquals(0.0, dynamics.accelerationBps2(), 0.0);
  }

  @Test
  void nonPositiveStepsLeaveTheStateUntouched() {
    DriveDynamics dynamics = newDynamics();
    dynamics.reset(4.0);

    assertEquals(4.0, dynamics.step(0.0, Notch.P3, CAP), 0.0);
    assertEquals(4.0, dynamics.step(-1.0, Notch.P3, CAP), 0.0);
    assertEquals(4.0, dynamics.step(Double.NaN, Notch.P3, CAP), 0.0);
  }

  @Test
  void aReducedBrakeScaleWeakensBrakingButNotTraction() {
    DriveDynamics full = newDynamics();
    DriveDynamics weak = newDynamics();
    full.reset(15.0);
    weak.reset(15.0);
    for (int i = 0; i < 40; i++) {
      full.step(DT, Notch.B4, CAP, 1.0);
      weak.step(DT, Notch.B4, CAP, 0.5);
    }
    double fullLoss = 15.0 - full.speedBps();
    double weakLoss = 15.0 - weak.speedBps();
    assertTrue(weakLoss < fullLoss, "制动力打折后减速更少");

    DriveDynamics tractionWeak = newDynamics();
    DriveDynamics tractionFull = newDynamics();
    for (int i = 0; i < 40; i++) {
      tractionFull.step(DT, Notch.P3, CAP, 1.0);
      tractionWeak.step(DT, Notch.P3, CAP, 0.0);
    }
    assertEquals(tractionFull.speedBps(), tractionWeak.speedBps(), 1e-12, "牵引不受风压影响");
  }
}
