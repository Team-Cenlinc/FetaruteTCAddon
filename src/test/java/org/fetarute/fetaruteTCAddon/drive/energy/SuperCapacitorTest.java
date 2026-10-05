package org.fetarute.fetaruteTCAddon.drive.energy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("超级电容的储能")
class SuperCapacitorTest {

  private static final double DT = 0.05;
  private static final double LINE = 80.0 / 3.6;

  /** 不计辅助负载，便于对账。 */
  private static SuperCapacitorConfig noAux() {
    SuperCapacitorConfig d = SuperCapacitorConfig.defaults();
    return new SuperCapacitorConfig(
        d.capacityKwhPerTon(),
        d.fullChargeSeconds(),
        d.tractionEfficiency(),
        d.regenEfficiency(),
        d.regenCutoffBps(),
        0.0,
        d.lowFraction());
  }

  @Test
  @DisplayName("起步到 80 km/h 耗电 = v²/2 ÷ 牵引效率；默认满电约够两次半")
  void accelerationCostsKineticEnergyOverEfficiency() {
    SuperCapacitorConfig config = noAux();
    SuperCapacitor cap = new SuperCapacitor(config, 1.0);
    double accel = 1.1;
    int steps = (int) Math.ceil(LINE / (accel * DT));
    for (int i = 0; i < steps; i++) {
      double v = i * accel * DT;
      double step = Math.min(accel * DT, LINE - v);
      cap.drive(step / DT, 0.0, v + step / 2.0, DT);
    }
    double used = (1.0 - cap.fraction()) * config.capacityEnergy();
    assertEquals(LINE * LINE / 2.0 / config.tractionEfficiency(), used, 1.0);
    assertEquals(2.5, new SuperCapacitor(config, 1.0).startsLeft(LINE), 0.1);
  }

  @Test
  @DisplayName("常用制动在退出速度以上再生，以下不再生")
  void regenerationOnlyAboveCutoff() {
    SuperCapacitorConfig config = noAux();
    SuperCapacitor cap = new SuperCapacitor(config, 0.5);
    cap.drive(0.0, 1.2, 20.0, 1.0);
    assertEquals(
        0.5 + 1.2 * 20.0 * config.regenEfficiency() / config.capacityEnergy(),
        cap.fraction(),
        1e-9);
    double before = cap.fraction();
    cap.drive(0.0, 1.2, config.regenCutoffBps() - 0.1, 1.0);
    assertEquals(before, cap.fraction(), 1e-12);
  }

  @Test
  @DisplayName("停站充电：满充时间内从空充满，充满后不再算充电中")
  void chargesLinearlyToFull() {
    SuperCapacitor cap = new SuperCapacitor(noAux(), 0.0);
    assertTrue(cap.depleted());
    assertEquals(SuperCapacitor.Level.DEPLETED, cap.level());
    for (int i = 0; i < 300; i++) {
      cap.charge(DT);
    }
    assertEquals(0.5, cap.fraction(), 1e-9);
    assertTrue(cap.charging());
    for (int i = 0; i < 400; i++) {
      cap.charge(DT);
    }
    assertTrue(cap.full());
    cap.charge(DT);
    assertFalse(cap.charging(), "充满后不再充电");
  }

  @Test
  @DisplayName("耗尽后不会变负；电量状态随预警线变化")
  void depletesAndReportsLevels() {
    SuperCapacitor cap = new SuperCapacitor(noAux(), 0.25);
    assertEquals(SuperCapacitor.Level.NORMAL, cap.level());
    cap.reset(0.1);
    assertEquals(SuperCapacitor.Level.LOW, cap.level());
    cap.drive(1.1, 0.0, LINE, 60.0);
    assertEquals(0.0, cap.fraction(), 0.0);
    assertTrue(cap.depleted());
  }

  @Test
  @DisplayName("辅助负载按时间耗电")
  void auxiliaryLoadDrainsOverTime() {
    SuperCapacitor cap = new SuperCapacitor(SuperCapacitorConfig.defaults(), 1.0);
    cap.drive(0.0, 0.0, 0.0, 60.0);
    assertEquals(0.99, cap.fraction(), 1e-9);
  }

  @Test
  @DisplayName("读取 supercap 段；非法项回退默认值并提示")
  void parsesConfig() throws Exception {
    YamlConfiguration yaml = new YamlConfiguration();
    yaml.loadFromString(
        String.join(
            "\n",
            "capacity-kwh-per-ton: 0.5",
            "full-charge-seconds: 20",
            "regen-cutoff-kmh: 10",
            "aux-drain-percent-per-minute: 2",
            "low-percent: 30",
            "traction-efficiency: 0",
            "regen-efficiency: 1.5"));
    List<String> warnings = new ArrayList<>();
    SuperCapacitorConfig config = SuperCapacitorConfig.from(yaml, warnings::add);
    assertEquals(0.5 * 3600.0, config.capacityEnergy(), 1e-9);
    assertEquals(20.0, config.fullChargeSeconds(), 1e-9);
    assertEquals(10.0 / 3.6, config.regenCutoffBps(), 1e-9);
    assertEquals(0.02 / 60.0, config.auxDrainPerSecond(), 1e-12);
    assertEquals(0.3, config.lowFraction(), 1e-9);
    assertEquals(SuperCapacitorConfig.defaults().tractionEfficiency(), config.tractionEfficiency());
    assertEquals(SuperCapacitorConfig.defaults().regenEfficiency(), config.regenEfficiency());
    assertEquals(2, warnings.size());
  }
}
