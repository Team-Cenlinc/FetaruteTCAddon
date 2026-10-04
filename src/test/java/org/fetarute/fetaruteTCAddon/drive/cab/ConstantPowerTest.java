package org.fetarute.fetaruteTCAddon.drive.cab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainType;
import org.fetarute.fetaruteTCAddon.drive.DriveConfig;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveDynamics;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveMode;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveParams;
import org.fetarute.fetaruteTCAddon.drive.dynamics.Notch;
import org.junit.jupiter.api.Test;

class ConstantPowerTest {

  private static final double KMH = 1.0 / 3.6;

  @Test
  void tractionFallsAsKneeOverSpeedAboveTheKnee() {
    double knee = 40 * KMH;

    assertEquals(1.0, ConstantPowerConfig.tractionScale(0.0, knee));
    assertEquals(1.0, ConstantPowerConfig.tractionScale(knee, knee));
    assertEquals(0.5, ConstantPowerConfig.tractionScale(80 * KMH, knee), 1e-12);
    assertEquals(40.0 / 60.0, ConstantPowerConfig.tractionScale(60 * KMH, knee), 1e-12);
  }

  @Test
  void theKneeCanBeOverriddenPerTrainType() {
    ConstantPowerConfig config = new ConstantPowerConfig(40 * KMH, Map.of(TrainType.EMU, 50 * KMH));

    assertEquals(50 * KMH, config.kneeBpsFor(TrainType.EMU), 1e-12);
    assertEquals(40 * KMH, config.kneeBpsFor(TrainType.METRO), 1e-12, "未覆盖的车种用全局默认");
    assertEquals(40 * KMH, config.kneeBpsFor(null), 1e-12);
  }

  @Test
  void simulationCabsReduceTractionAboveTheKneeWhileTheStandardLevelDoesNot() {
    CabConfig config = CabConfig.defaults();
    CabSystems simulation =
        CabSystems.simulation(
            config, new CabVehicle(DriveMode.MU, true, 6, 40 * KMH), 900, true, 0);

    assertEquals(0.5, simulation.tractionScale(80 * KMH), 1e-12);
    assertEquals(1.0, CabSystems.disabled().tractionScale(80 * KMH));
  }

  @Test
  void aboveTheKneeTheTrainAcceleratesMoreSlowly() {
    DriveConfig config = DriveConfig.defaults();
    DriveParams params = new DriveParams(DriveMode.MU, 1.0, 1.0, 30.0, 0.5);
    DriveDynamics full = new DriveDynamics(params, config);
    DriveDynamics powerLimited = new DriveDynamics(params, config);
    double knee = 40 * KMH;
    full.reset(20.0);
    powerLimited.reset(20.0);

    for (int i = 0; i < 40; i++) {
      full.step(0.05, Notch.P3, 30.0);
      powerLimited.step(
          0.05,
          Notch.P3,
          30.0,
          ConstantPowerConfig.tractionScale(powerLimited.speedBps(), knee),
          demand -> 1.0);
    }

    assertTrue(powerLimited.speedBps() < full.speedBps());
    // 速度始终不低于 20 格/秒，牵引加速度不会超过 拐点速度 ÷ 20。
    double bound = config.tractionFraction(Notch.P3) * knee / 20.0;
    assertTrue(powerLimited.accelerationBps2() + config.coastDragBps2() <= bound + 1e-9);
  }
}
