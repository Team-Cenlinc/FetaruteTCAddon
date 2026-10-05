package org.fetarute.fetaruteTCAddon.drive.dynamics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.fetarute.fetaruteTCAddon.drive.DriveConfig;
import org.junit.jupiter.api.Test;

/** 牵引力与制动力分别打折的推进方式。 */
class DriveDynamicsScaleTest {

  private static final double DT = 1.0 / 20.0;
  private final DriveConfig config = DriveConfig.defaults();
  private final DriveParams params = new DriveParams(DriveMode.MU, 1.0, 1.0, 22.0, 0.5);

  /** 引入分别打折之前的推进算法，逐位比较用。 */
  private static final class Reference {
    private final DriveParams params;
    private final DriveConfig config;
    private double speed;
    private double effort;

    Reference(DriveParams params, DriveConfig config) {
      this.params = params;
      this.config = config;
    }

    void step(double seconds, Notch notch, double capBps, double brakeScale) {
      double target =
          switch (notch.kind()) {
            case TRACTION -> config.tractionFraction(notch);
            case COAST -> 0.0;
            case BRAKE, EMERGENCY -> -config.brakeFraction(notch);
          };
      double rate =
          notch == Notch.EB ? config.emergencyRatePerSecond() : config.effortRatePerSecond();
      double maxDelta = rate * seconds;
      effort += Math.max(-maxDelta, Math.min(maxDelta, target - effort));
      double scale = Math.max(0.0, Math.min(1.0, brakeScale));
      double a = effort >= 0.0 ? effort * params.accelBps2() : effort * params.decelBps2() * scale;
      if (speed > 0.0) {
        a -= config.coastDragBps2();
      }
      double cap = Math.max(0.0, Math.min(capBps, params.maxSpeedBps()));
      speed = Math.max(0.0, Math.min(cap, speed + a * seconds));
    }
  }

  @Test
  void fullScalesBehaveExactlyLikeTheOriginalStep() {
    Reference reference = new Reference(params, config);
    DriveDynamics plain = new DriveDynamics(params, config);
    DriveDynamics scaled = new DriveDynamics(params, config);
    List<Notch> sequence =
        List.of(Notch.P3, Notch.P2, Notch.N, Notch.P1, Notch.B2, Notch.B4, Notch.N, Notch.EB);

    for (Notch notch : sequence) {
      for (int i = 0; i < 60; i++) {
        reference.step(DT, notch, 20.0, 1.0);
        plain.step(DT, notch, 20.0);
        scaled.step(DT, notch, 20.0, 1.0, demand -> 1.0);
        assertEquals(reference.speed, plain.speedBps(), 0.0, "standard 级的速度曲线不得改变");
        assertEquals(reference.speed, scaled.speedBps(), 0.0);
        assertEquals(reference.effort, scaled.effort(), 0.0);
      }
    }
  }

  @Test
  void aConstantBrakeScaleMatchesTheOriginalStepToo() {
    Reference reference = new Reference(params, config);
    DriveDynamics dynamics = new DriveDynamics(params, config);
    reference.speed = 18.0;
    dynamics.reset(18.0);

    for (int i = 0; i < 200; i++) {
      reference.step(DT, Notch.B3, 22.0, 0.7);
      dynamics.step(DT, Notch.B3, 22.0, 0.7);
      assertEquals(reference.speed, dynamics.speedBps(), 0.0);
    }
  }

  @Test
  void theTractionScaleOnlyAffectsTractionAndTheBrakeScaleOnlyBraking() {
    DriveDynamics halfTraction = new DriveDynamics(params, config);
    halfTraction.reset(5.0);
    for (int i = 0; i < 40; i++) {
      halfTraction.step(DT, Notch.P3, 22.0, 0.5, demand -> 0.0);
    }
    assertEquals(0.5 - config.coastDragBps2(), halfTraction.accelerationBps2(), 1e-9);

    DriveDynamics weakBrake = new DriveDynamics(params, config);
    weakBrake.reset(15.0);
    double[] seen = {0.0};
    for (int i = 0; i < 40; i++) {
      weakBrake.step(
          DT,
          Notch.B4,
          22.0,
          0.0,
          demand -> {
            seen[0] = demand;
            return 0.85;
          });
    }
    assertEquals(1.0, seen[0], 1e-9, "制动力度按本步实际力度传给打折函数");
    assertEquals(-0.85 - config.coastDragBps2(), weakBrake.accelerationBps2(), 1e-9);
  }
}
