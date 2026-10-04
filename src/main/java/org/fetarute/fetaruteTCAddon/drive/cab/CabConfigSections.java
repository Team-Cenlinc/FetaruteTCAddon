package org.fetarute.fetaruteTCAddon.drive.cab;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainType;

/**
 * 解析 {@code drive.yml} 的 {@code simulation} 段里的子段：电空制动、恒功率、制动管与车上故障。
 *
 * <p>与其余配置一样，缺失或非法的项回退为默认值并经 {@code warn} 提示；子段内各项之间的关系不成立时整个子段回退。配置里的速度写 km/h。
 */
public final class CabConfigSections {

  private static final double KMH_PER_BPS = 3.6;
  private static final int TICKS_PER_SECOND = 20;

  private CabConfigSections() {}

  /** {@code simulation.blended-brake}。 */
  public static BlendedBrakeConfig blendedBrake(
      ConfigurationSection section, BlendedBrakeConfig fallback, Consumer<String> warn) {
    if (section == null) {
      return fallback;
    }
    Reader read = new Reader(section, "simulation.blended-brake.", warn);
    return new BlendedBrakeConfig(
        read.fraction("electric-fraction", fallback.electricFraction()),
        read.nonNegative("electric-exit-kmh", fallback.electricExitBps() * KMH_PER_BPS)
            / KMH_PER_BPS,
        read.fraction("air-fraction", fallback.airFraction()));
  }

  /** {@code simulation.constant-power}。 */
  public static ConstantPowerConfig constantPower(
      ConfigurationSection section, ConstantPowerConfig fallback, Consumer<String> warn) {
    if (section == null) {
      return fallback;
    }
    Reader read = new Reader(section, "simulation.constant-power.", warn);
    double knee = read.positive("knee-kmh", fallback.kneeBps() * KMH_PER_BPS) / KMH_PER_BPS;
    ConfigurationSection byTypeSection = section.getConfigurationSection("knee-kmh-by-type");
    if (byTypeSection == null) {
      return new ConstantPowerConfig(knee, fallback.kneeBpsByType());
    }
    Reader byTypeRead =
        new Reader(byTypeSection, "simulation.constant-power.knee-kmh-by-type.", warn);
    Map<TrainType, Double> byType = new EnumMap<>(TrainType.class);
    for (String key : byTypeSection.getKeys(false)) {
      Optional<TrainType> type = TrainType.parse(key);
      if (type.isEmpty()) {
        warn.accept(
            "drive.yml 的 simulation.constant-power.knee-kmh-by-type." + key + " 不是已知车种，已忽略");
        continue;
      }
      double fallbackKmh = fallback.kneeBpsFor(type.get()) * KMH_PER_BPS;
      byType.put(type.get(), byTypeRead.positive(key, fallbackKmh) / KMH_PER_BPS);
    }
    return new ConstantPowerConfig(knee, byType);
  }

  /** {@code simulation.brake-pipe}；各项关系不成立时整段回退。 */
  public static BrakePipeConfig brakePipe(
      ConfigurationSection section, BrakePipeConfig fallback, Consumer<String> warn) {
    if (section == null) {
      return fallback;
    }
    Reader read = new Reader(section, "simulation.brake-pipe.", warn);
    try {
      return new BrakePipeConfig(
          read.positive("nominal-kpa", fallback.nominalKpa()),
          read.positive("full-service-reduction-kpa", fallback.fullServiceReductionKpa()),
          read.nonNegative("charge-base-seconds", fallback.chargeBaseSeconds()),
          read.nonNegative("charge-seconds-per-car", fallback.chargeSecondsPerCar()),
          read.positive("service-rate-kpa-per-second", fallback.serviceRateKpaPerSecond()),
          read.nonNegative("emergency-kpa", fallback.emergencyKpa()),
          read.nonNegative("consumption-per-car", fallback.consumptionPerCar()),
          read.positive("test-reduction-kpa", fallback.testReductionKpa()),
          read.positive("test-hold-seconds", fallback.testHoldSeconds()),
          read.nonNegative("test-max-leak-kpa", fallback.testMaxLeakKpa()));
    } catch (IllegalArgumentException ex) {
      warn.accept("drive.yml 的 simulation.brake-pipe 段关系不合理（" + ex.getMessage() + "），整段使用默认值");
      return fallback;
    }
  }

  /** {@code simulation.faults}。 */
  public static FaultConfig faults(
      ConfigurationSection section, FaultConfig fallback, Consumer<String> warn) {
    if (section == null) {
      return fallback;
    }
    Reader read = new Reader(section, "simulation.faults.", warn);
    double chance = fallback.chancePerHour();
    if (section.contains("chance-per-hour")) {
      double value = section.getDouble("chance-per-hour", Double.NaN);
      if (Double.isFinite(value) && value >= 0.0 && value < 1.0) {
        chance = value;
      } else {
        warn.accept(
            "drive.yml 的 simulation.faults.chance-per-hour 必须在 0 到 1 之间（不含 1），使用默认值 "
                + fallback.chancePerHour());
      }
    }
    return new FaultConfig(
        section.getBoolean("enabled", fallback.enabled()),
        chance,
        types(section, fallback.types(), warn),
        (int)
            Math.round(
                read.positive(
                        "line-loss-seconds", fallback.lineLossTicks() / (double) TICKS_PER_SECOND)
                    * TICKS_PER_SECOND),
        read.positive("brake-leak-kpa-per-second", fallback.brakeLeakKpaPerSecond()));
  }

  private static Set<CabFault> types(
      ConfigurationSection section, Set<CabFault> fallback, Consumer<String> warn) {
    if (!section.contains("types")) {
      return fallback;
    }
    List<String> raw = section.getStringList("types");
    Set<CabFault> types = EnumSet.noneOf(CabFault.class);
    for (String entry : raw) {
      Optional<CabFault> fault = CabFault.parse(entry);
      if (fault.isPresent()) {
        types.add(fault.get());
      } else {
        warn.accept(
            "drive.yml 的 simulation.faults.types 里的 "
                + entry
                + " 不是已知故障（line-loss/breaker-trip/compressor/brake-leak/door），已忽略");
      }
    }
    return types;
  }

  /** 带键名前缀的数值读取：缺失取默认，非法时提示并取默认。 */
  private record Reader(ConfigurationSection section, String prefix, Consumer<String> warn) {

    double positive(String key, double fallback) {
      double value = raw(key, fallback);
      if (!Double.isFinite(value) || value <= 0.0) {
        warn.accept("drive.yml 的 " + prefix + key + " 必须为正数，使用默认值 " + fallback);
        return fallback;
      }
      return value;
    }

    double nonNegative(String key, double fallback) {
      double value = raw(key, fallback);
      if (!Double.isFinite(value) || value < 0.0) {
        warn.accept("drive.yml 的 " + prefix + key + " 不能为负数，使用默认值 " + fallback);
        return fallback;
      }
      return value;
    }

    double fraction(String key, double fallback) {
      double value = raw(key, fallback);
      if (!Double.isFinite(value) || value <= 0.0 || value > 1.0) {
        warn.accept("drive.yml 的 " + prefix + key + " 必须在 (0, 1] 内，使用默认值 " + fallback);
        return fallback;
      }
      return value;
    }

    private double raw(String key, double fallback) {
      return section.contains(key) ? section.getDouble(key, Double.NaN) : fallback;
    }
  }
}
