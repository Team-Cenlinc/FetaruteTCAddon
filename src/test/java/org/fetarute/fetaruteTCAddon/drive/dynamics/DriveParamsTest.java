package org.fetarute.fetaruteTCAddon.drive.dynamics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import java.util.OptionalDouble;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainType;
import org.fetarute.fetaruteTCAddon.drive.DriveConfig;
import org.junit.jupiter.api.Test;

class DriveParamsTest {

  private final DriveConfig config = DriveConfig.defaults();

  private DriveParams resolve(
      TrainType type,
      Optional<DriveMode> mode,
      OptionalDouble motorFraction,
      OptionalDouble maxSpeed,
      int cars) {
    return DriveParams.resolve(type, 1.0, 1.2, mode, motorFraction, maxSpeed, cars, config);
  }

  @Test
  void unitsWithoutAMotorRatioKeepTheirPresetAcceleration() {
    DriveParams params =
        resolve(
            TrainType.METRO, Optional.empty(), OptionalDouble.empty(), OptionalDouble.empty(), 6);

    assertEquals(DriveMode.MU, params.mode());
    assertEquals(1.0, params.accelBps2(), 1e-12);
    assertEquals(1.2, params.decelBps2(), 1e-12);
    assertEquals(config.muReferenceMotorFraction(), params.motorFraction(), 1e-12);
  }

  @Test
  void fewerMotorCarsLowerTheAccelerationProportionally() {
    DriveParams params =
        resolve(
            TrainType.EMU,
            Optional.empty(),
            OptionalDouble.of(config.muReferenceMotorFraction() / 2.0),
            OptionalDouble.empty(),
            6);

    assertEquals(0.5, params.accelBps2(), 1e-12);
    assertEquals(1.2, params.decelBps2(), 1e-12, "常用制动不随动拖比变化");
  }

  @Test
  void moreMotorCarsThanTheReferenceDoNotBoostTheAcceleration() {
    DriveParams params =
        resolve(TrainType.EMU, Optional.empty(), OptionalDouble.of(1.0), OptionalDouble.empty(), 6);

    assertEquals(1.0, params.accelBps2(), 1e-12);
  }

  @Test
  void locomotivesLoseAccelerationWithEveryCarBeyondTheReference() {
    DriveParams reference =
        resolve(
            TrainType.ELECTRIC_LOCO,
            Optional.empty(),
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            config.locoReferenceCars());
    DriveParams heavy =
        resolve(
            TrainType.ELECTRIC_LOCO,
            Optional.empty(),
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            config.locoReferenceCars() * 2);
    DriveParams light =
        resolve(
            TrainType.DIESEL_PUSH_PULL,
            Optional.empty(),
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            2);

    assertEquals(DriveMode.LOCO, reference.mode());
    assertEquals(1.0, reference.accelBps2(), 1e-12);
    assertEquals(0.5, heavy.accelBps2(), 1e-12);
    assertEquals(1.0, light.accelBps2(), 1e-12, "编组比基准短不额外加速");
  }

  @Test
  void anExplicitModeOverridesTheTypeDefault() {
    DriveParams params =
        resolve(
            TrainType.METRO,
            Optional.of(DriveMode.LOCO),
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            12);

    assertEquals(DriveMode.LOCO, params.mode());
    assertEquals(0.5, params.accelBps2(), 1e-12);
  }

  @Test
  void maximumSpeedComesFromTheOverrideOtherwiseTheConfigDefault() {
    assertEquals(
        config.defaultMaxSpeedBps(),
        resolve(TrainType.EMU, Optional.empty(), OptionalDouble.empty(), OptionalDouble.empty(), 4)
            .maxSpeedBps(),
        1e-12);
    assertEquals(
        30.0,
        resolve(TrainType.EMU, Optional.empty(), OptionalDouble.empty(), OptionalDouble.of(30.0), 4)
            .maxSpeedBps(),
        1e-12);
    assertEquals(
        config.defaultMaxSpeedBps(),
        resolve(TrainType.EMU, Optional.empty(), OptionalDouble.empty(), OptionalDouble.of(-5.0), 4)
            .maxSpeedBps(),
        1e-12);
  }

  @Test
  void typeDefaultsFollowTheTractionLayout() {
    assertEquals(DriveMode.MU, DriveParams.defaultMode(TrainType.METRO));
    assertEquals(DriveMode.MU, DriveParams.defaultMode(TrainType.EMU));
    assertEquals(DriveMode.MU, DriveParams.defaultMode(TrainType.DMU));
    assertEquals(DriveMode.LOCO, DriveParams.defaultMode(TrainType.ELECTRIC_LOCO));
    assertEquals(DriveMode.LOCO, DriveParams.defaultMode(TrainType.DIESEL_PUSH_PULL));
  }

  @Test
  void rejectsNonPositiveValues() {
    assertThrows(
        IllegalArgumentException.class, () -> new DriveParams(DriveMode.MU, 0.0, 1.0, 10.0, 0.5));
    assertThrows(
        IllegalArgumentException.class, () -> new DriveParams(DriveMode.MU, 1.0, 1.0, 0.0, 0.5));
  }
}
