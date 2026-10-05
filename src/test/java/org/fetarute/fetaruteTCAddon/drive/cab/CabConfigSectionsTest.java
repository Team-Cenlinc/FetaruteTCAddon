package org.fetarute.fetaruteTCAddon.drive.cab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.bukkit.configuration.MemoryConfiguration;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainType;
import org.fetarute.fetaruteTCAddon.drive.DriveConfig;
import org.junit.jupiter.api.Test;

class CabConfigSectionsTest {

  private static final double KMH = 1.0 / 3.6;

  @Test
  void readsTheNewSimulationSubsectionsWithSpeedsInKmh() {
    MemoryConfiguration section = new MemoryConfiguration();
    section.set("simulation.blended-brake.electric-fraction", 0.6);
    section.set("simulation.blended-brake.electric-exit-kmh", 10);
    section.set("simulation.constant-power.knee-kmh", 36);
    section.set("simulation.constant-power.knee-kmh-by-type.metro", 45);
    section.set("simulation.brake-pipe.nominal-kpa", 600);
    section.set("simulation.brake-pipe.test-hold-seconds", 5);
    section.set("simulation.faults.enabled", true);
    section.set("simulation.faults.chance-per-hour", 0.2);
    section.set("simulation.faults.types", List.of("door", "brake_leak"));
    section.set("simulation.faults.line-loss-seconds", 12);
    List<String> warnings = new ArrayList<>();

    CabConfig cab = DriveConfig.from(section, warnings::add).cab();

    assertTrue(warnings.isEmpty(), warnings.toString());
    assertEquals(0.6, cab.blendedBrake().electricFraction());
    assertEquals(10 * KMH, cab.blendedBrake().electricExitBps(), 1e-12);
    assertEquals(0.85, cab.blendedBrake().airFraction(), "没写的项取默认");
    assertEquals(36 * KMH, cab.constantPower().kneeBps(), 1e-12);
    assertEquals(45 * KMH, cab.constantPower().kneeBpsFor(TrainType.METRO), 1e-12);
    assertEquals(36 * KMH, cab.constantPower().kneeBpsFor(TrainType.EMU), 1e-12, "写了覆盖表就只用表里的车种");
    assertEquals(600.0, cab.brakePipe().nominalKpa());
    assertEquals(5.0, cab.brakePipe().testHoldSeconds());
    assertTrue(cab.faults().enabled());
    assertEquals(0.2, cab.faults().chancePerHour());
    assertEquals(EnumSet.of(CabFault.DOOR, CabFault.BRAKE_LEAK), cab.faults().types());
    assertEquals(240, cab.faults().lineLossTicks());
  }

  @Test
  void invalidValuesFallBackWithWarnings() {
    MemoryConfiguration section = new MemoryConfiguration();
    section.set("simulation.blended-brake.air-fraction", 1.5);
    section.set("simulation.constant-power.knee-kmh-by-type.tram", 30);
    section.set("simulation.faults.chance-per-hour", 1.0);
    section.set("simulation.faults.types", List.of("door", "flat-tyre"));
    List<String> warnings = new ArrayList<>();

    CabConfig cab = DriveConfig.from(section, warnings::add).cab();

    assertEquals(0.85, cab.blendedBrake().airFraction());
    assertEquals(0.5, cab.faults().chancePerHour());
    assertEquals(EnumSet.of(CabFault.DOOR), cab.faults().types());
    assertEquals(4, warnings.size(), warnings.toString());
  }

  @Test
  void anInconsistentBrakePipeFallsBackAsAWhole() {
    MemoryConfiguration section = new MemoryConfiguration();
    section.set("simulation.brake-pipe.emergency-kpa", 400);
    section.set("simulation.brake-pipe.charge-base-seconds", 20);
    List<String> warnings = new ArrayList<>();

    CabConfig cab = DriveConfig.from(section, warnings::add).cab();

    assertEquals(BrakePipeConfig.defaults(), cab.brakePipe());
    assertEquals(1, warnings.size(), warnings.toString());
  }

  @Test
  void aBrakePipeAboveTheMainReservoirFallsBackTheWholeSimulationSection() {
    MemoryConfiguration section = new MemoryConfiguration();
    section.set("simulation.brake-pipe.nominal-kpa", 800);
    section.set("simulation.main-reservoir-max-kpa", 700);
    section.set("simulation.compressor-cut-in-kpa", 600);
    List<String> warnings = new ArrayList<>();

    CabConfig cab = DriveConfig.from(section, warnings::add).cab();

    assertEquals(CabConfig.defaults(), cab);
    assertFalse(warnings.isEmpty());
  }

  @Test
  void theBundledDefaultsMatchTheRecordDefaults() {
    assertEquals(CabConfig.defaults(), DriveConfig.defaults().cab());
    assertEquals(FaultConfig.defaults().types(), EnumSet.allOf(CabFault.class));
    assertFalse(FaultConfig.defaults().enabled(), "随机故障默认关闭");
  }
}
