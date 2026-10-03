package org.fetarute.fetaruteTCAddon.drive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.MemoryConfiguration;
import org.fetarute.fetaruteTCAddon.drive.cab.CabConfig;
import org.fetarute.fetaruteTCAddon.drive.dynamics.Notch;
import org.junit.jupiter.api.Test;

class DriveConfigTest {

  @Test
  void missingSectionGivesTheDefaults() {
    assertEquals(DriveConfig.defaults(), DriveConfig.from(null, message -> {}));
  }

  @Test
  void readsEveryConfiguredValue() {
    MemoryConfiguration section = new MemoryConfiguration();
    section.set("enabled", false);
    section.set("level", "Simulation");
    section.set("default-max-speed-bps", 30.0);
    section.set("coast-drag-bps2", 0.1);
    section.set("emergency-multiplier", 1.6);
    section.set("traction-fractions", List.of(0.2, 0.5, 0.9));
    section.set("brake-fractions", List.of(0.1, 0.2, 0.3, 0.4));
    section.set("loco-reference-cars", 8);
    section.set("hud-interval-ticks", 10);

    DriveConfig config = DriveConfig.from(section, message -> {});

    assertFalse(config.enabled());
    assertEquals(SimulationLevel.SIMULATION, config.level());
    assertEquals(30.0, config.defaultMaxSpeedBps());
    assertEquals(0.1, config.coastDragBps2());
    assertEquals(1.6, config.emergencyMultiplier());
    assertEquals(0.5, config.tractionFraction(Notch.P2));
    assertEquals(0.4, config.brakeFraction(Notch.B4));
    assertEquals(1.6, config.brakeFraction(Notch.EB), "紧急制动取倍数");
    assertEquals(8, config.locoReferenceCars());
    assertEquals(10, config.hudIntervalTicks());
  }

  @Test
  void fractionsOfOtherNotchKindsAreZero() {
    DriveConfig config = DriveConfig.defaults();

    assertEquals(0.0, config.tractionFraction(Notch.N));
    assertEquals(0.0, config.tractionFraction(Notch.B1));
    assertEquals(0.0, config.brakeFraction(Notch.P3));
    assertEquals(0.0, config.brakeFraction(Notch.N));
  }

  @Test
  void invalidValuesFallBackToTheDefaultsAndAreReported() {
    MemoryConfiguration section = new MemoryConfiguration();
    section.set("level", "turbo");
    section.set("default-max-speed-bps", -3.0);
    section.set("coast-drag-bps2", -1.0);
    section.set("emergency-multiplier", 0.5);
    section.set("traction-fractions", List.of(0.5, 0.4, 0.9));
    section.set("brake-fractions", List.of(0.5, 1.0));
    section.set("mu-reference-motor-fraction", 2.0);
    section.set("loco-reference-cars", 0);
    List<String> warnings = new ArrayList<>();

    DriveConfig config = DriveConfig.from(section, warnings::add);

    DriveConfig defaults = DriveConfig.defaults();
    assertEquals(defaults.level(), config.level());
    assertEquals(defaults.defaultMaxSpeedBps(), config.defaultMaxSpeedBps());
    assertEquals(defaults.coastDragBps2(), config.coastDragBps2());
    assertEquals(defaults.emergencyMultiplier(), config.emergencyMultiplier());
    assertEquals(defaults.tractionFractions(), config.tractionFractions());
    assertEquals(defaults.brakeFractions(), config.brakeFractions());
    assertEquals(defaults.muReferenceMotorFraction(), config.muReferenceMotorFraction());
    assertEquals(defaults.locoReferenceCars(), config.locoReferenceCars());
    assertEquals(8, warnings.size(), warnings.toString());
  }

  @Test
  void absentKeysKeepTheirDefaultsWithoutWarnings() {
    MemoryConfiguration section = new MemoryConfiguration();
    section.set("level", "simulation");
    List<String> warnings = new ArrayList<>();

    DriveConfig config = DriveConfig.from(section, warnings::add);

    assertEquals(SimulationLevel.SIMULATION, config.level());
    assertEquals(DriveConfig.defaults().coastDragBps2(), config.coastDragBps2());
    assertTrue(warnings.isEmpty());
  }

  @Test
  void creativeModeAndSpeedLimitOptionsHaveSensibleDefaults() {
    DriveConfig defaults = DriveConfig.defaults();

    assertTrue(defaults.allowCreativeMode());
    assertTrue(defaults.speedLimitOverride());
    assertEquals(0.1, defaults.overspeedRedRatio());
    assertEquals(0.2, defaults.startMaxSpeedBps());
  }

  @Test
  void readsCreativeStartAndOverspeedOptions() {
    MemoryConfiguration section = new MemoryConfiguration();
    section.set("allow-creative-mode", false);
    section.set("start-max-speed-bps", 0.5);
    section.set("speed-limit-override", false);
    section.set("overspeed-red-ratio", 0.25);

    DriveConfig config = DriveConfig.from(section, message -> {});

    assertFalse(config.allowCreativeMode());
    assertEquals(0.5, config.startMaxSpeedBps());
    assertFalse(config.speedLimitOverride());
    assertEquals(0.25, config.overspeedRedRatio());
  }

  @Test
  void nonPositiveStartSpeedAndRedRatioFallBackWithWarnings() {
    MemoryConfiguration section = new MemoryConfiguration();
    section.set("start-max-speed-bps", 0.0);
    section.set("overspeed-red-ratio", -0.2);
    List<String> warnings = new ArrayList<>();

    DriveConfig config = DriveConfig.from(section, warnings::add);

    assertEquals(DriveConfig.defaults().startMaxSpeedBps(), config.startMaxSpeedBps());
    assertEquals(DriveConfig.defaults().overspeedRedRatio(), config.overspeedRedRatio());
    assertEquals(2, warnings.size(), warnings.toString());
  }

  @Test
  void readsTheSimulationSectionAndConvertsSecondsToTicks() {
    MemoryConfiguration section = new MemoryConfiguration();
    section.set("simulation.main-reservoir-max-kpa", 1000);
    section.set("simulation.traction-lockout-kpa", 600);
    section.set("simulation.vigilance-interval-seconds", 30);
    section.set("simulation.vigilance-warning-seconds", 4);

    DriveConfig config = DriveConfig.from(section, message -> {});

    assertEquals(1000.0, config.cab().mainReservoirMaxKpa());
    assertEquals(600.0, config.cab().tractionLockoutKpa());
    assertEquals(600, config.cab().vigilanceIntervalTicks());
    assertEquals(80, config.cab().vigilanceWarningTicks());
    assertEquals(CabConfig.defaults().fullBrakeKpa(), config.cab().fullBrakeKpa());
  }

  @Test
  void anInconsistentSimulationSectionFallsBackAsAWhole() {
    MemoryConfiguration section = new MemoryConfiguration();
    section.set("simulation.compressor-cut-in-kpa", 950);
    List<String> warnings = new ArrayList<>();

    DriveConfig config = DriveConfig.from(section, warnings::add);

    assertEquals(CabConfig.defaults(), config.cab());
    assertEquals(1, warnings.size(), warnings.toString());
  }
}
