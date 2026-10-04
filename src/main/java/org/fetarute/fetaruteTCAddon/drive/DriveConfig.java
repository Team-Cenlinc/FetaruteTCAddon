package org.fetarute.fetaruteTCAddon.drive;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;
import org.fetarute.fetaruteTCAddon.drive.cab.CabConfig;
import org.fetarute.fetaruteTCAddon.drive.cab.CabConfigSections;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverConfig;
import org.fetarute.fetaruteTCAddon.drive.dynamics.Notch;
import org.fetarute.fetaruteTCAddon.drive.setup.PowerSupply;
import org.fetarute.fetaruteTCAddon.drive.setup.SetupTimings;
import org.fetarute.fetaruteTCAddon.drive.sound.DriveSoundConfig;

/**
 * 手动驾驶配置（{@code drive.yml}）。
 *
 * <p>缺失或非法的项回退为默认值并经 {@code warn} 提示，不会让整段配置失效。
 *
 * @param enabled 是否允许手动驾驶
 * @param level 仿真等级
 * @param defaultMaxSpeedBps 列车未设最高速度标签时的速度上限（格/秒）
 * @param coastDragBps2 惰行阻力（格/秒²）：列车在动时始终叠加的减速度
 * @param emergencyMultiplier 紧急制动力相对常用制动的倍数
 * @param effortRatePerSecond 牵引/制动力每秒的最大变化量（满力为 1），控制冲击
 * @param emergencyRatePerSecond 施加紧急制动时牵引/制动力每秒的最大变化量
 * @param tractionFractions P1、P2、P3 的牵引力占满牵引的比例
 * @param brakeFractions B1–B4 的制动力占常用制动的比例
 * @param muReferenceMotorFraction 动车组加速度预设对应的动车占比
 * @param locoReferenceCars 机车加速度预设对应的牵引车厢数
 * @param stoppedSpeedBps 低于它视为停稳（格/秒）
 * @param reseatTimeoutTicks 驾驶员脱离座位后尝试重新入座的时限（tick）
 * @param exitSneakWindowTicks 按下潜行之后多少 tick 内离座视为主动离座
 * @param hudIntervalTicks 动作栏刷新间隔（tick）
 * @param allowCreativeMode 创造模式下是否允许驾驶
 * @param startMaxSpeedBps 低于它（格/秒）的列车视为停着，接管时清掉残余速度；更快的视为在溜，顺着驾驶员前进方向时沿用当前车速接管
 * @param speedLimitOverride 是否允许超过 TrainCarts 的限速：为真时限速只作提示，超速在动作栏标黄、标红
 * @param overspeedRedRatio 超出限速的比例达到它时动作栏标红，未达到则标黄
 * @param defaultPower 列车未设 {@code FTA_TRAIN_POWER} 标签时的受电方式
 * @param setupTimings 启动流程各步骤的耗时
 * @param coldAfterMinutes 列车无人驾驶超过多少分钟后按冷车处理（受电、主断路器、辅助电源全部断开）
 * @param cab simulation 级车上系统（气压、停放制动、电空制动、制动管、制动试验、警惕装置、恒功率、故障）的参数
 * @param sidebar 是否在驾驶员的侧边栏（计分板）显示车上系统的详细状态
 * @param driver 驾驶调度列车（DRIVER 模式）的参数
 * @param sounds 驾驶提示音与鸣笛
 * @param ebGraceTicks 驾驶员自己选到紧急制动后，多少 tick 内回拨可撤销（防误触）；0 表示立即锁定
 */
public record DriveConfig(
    boolean enabled,
    SimulationLevel level,
    double defaultMaxSpeedBps,
    double coastDragBps2,
    double emergencyMultiplier,
    double effortRatePerSecond,
    double emergencyRatePerSecond,
    List<Double> tractionFractions,
    List<Double> brakeFractions,
    double muReferenceMotorFraction,
    int locoReferenceCars,
    double stoppedSpeedBps,
    int reseatTimeoutTicks,
    int exitSneakWindowTicks,
    int hudIntervalTicks,
    boolean allowCreativeMode,
    double startMaxSpeedBps,
    boolean speedLimitOverride,
    double overspeedRedRatio,
    PowerSupply defaultPower,
    SetupTimings setupTimings,
    int coldAfterMinutes,
    CabConfig cab,
    boolean sidebar,
    DriverConfig driver,
    DriveSoundConfig sounds,
    int ebGraceTicks) {

  private static final int TRACTION_STEPS = 3;
  private static final int BRAKE_STEPS = 4;
  private static final int TICKS_PER_SECOND = 20;

  /** 单个启动步骤最长耗时（秒）。 */
  private static final double MAX_SETUP_SECONDS = 600.0;

  public DriveConfig {
    Objects.requireNonNull(level, "level");
    Objects.requireNonNull(defaultPower, "defaultPower");
    Objects.requireNonNull(setupTimings, "setupTimings");
    Objects.requireNonNull(cab, "cab");
    Objects.requireNonNull(driver, "driver");
    sounds = sounds == null ? DriveSoundConfig.defaults() : sounds;
    ebGraceTicks = Math.max(0, ebGraceTicks);
    tractionFractions = List.copyOf(tractionFractions);
    brakeFractions = List.copyOf(brakeFractions);
    if (tractionFractions.size() != TRACTION_STEPS || brakeFractions.size() != BRAKE_STEPS) {
      throw new IllegalArgumentException("牵引档需要 3 个比例，制动档需要 4 个比例");
    }
  }

  /** 内置默认值。 */
  public static DriveConfig defaults() {
    return new DriveConfig(
        true,
        SimulationLevel.STANDARD,
        22.0,
        0.03,
        1.4,
        1.5,
        8.0,
        List.of(0.33, 0.67, 1.0),
        List.of(0.25, 0.5, 0.75, 1.0),
        0.5,
        6,
        0.05,
        100,
        10,
        5,
        true,
        0.2,
        true,
        0.1,
        PowerSupply.PTG5,
        SetupTimings.defaults(),
        10,
        CabConfig.defaults(),
        true,
        DriverConfig.defaults(),
        DriveSoundConfig.defaults(),
        TICKS_PER_SECOND);
  }

  /** 给定档位的牵引力比例（占满牵引）；非牵引档为 0。 */
  public double tractionFraction(Notch notch) {
    return notch.isTraction() ? tractionFractions.get(notch.step() - 1) : 0.0;
  }

  /** 给定档位的制动力比例（占常用制动；紧急制动取倍数）；非制动档为 0。 */
  public double brakeFraction(Notch notch) {
    return switch (notch.kind()) {
      case BRAKE -> brakeFractions.get(notch.step() - 1);
      case EMERGENCY -> emergencyMultiplier;
      default -> 0.0;
    };
  }

  /**
   * 从配置段解析；段缺失时返回默认值。
   *
   * @param section {@code drive.yml} 的根配置段，可为空
   * @param warn 非法项提示输出
   */
  public static DriveConfig from(ConfigurationSection section, Consumer<String> warn) {
    DriveConfig defaults = defaults();
    if (section == null) {
      return defaults;
    }
    Consumer<String> sink = warn != null ? warn : message -> {};
    SimulationLevel level = defaults.level;
    String rawLevel = section.getString("level");
    if (rawLevel != null) {
      level =
          SimulationLevel.parse(rawLevel)
              .orElseGet(
                  () -> {
                    sink.accept("drive.yml 的 level 无效，使用默认值 " + defaults.level);
                    return defaults.level;
                  });
    }
    return new DriveConfig(
        section.getBoolean("enabled", defaults.enabled),
        level,
        positive(section, "default-max-speed-bps", defaults.defaultMaxSpeedBps, sink),
        nonNegative(section, "coast-drag-bps2", defaults.coastDragBps2, sink),
        atLeastOne(section, "emergency-multiplier", defaults.emergencyMultiplier, sink),
        positive(section, "effort-rate-per-second", defaults.effortRatePerSecond, sink),
        positive(section, "emergency-rate-per-second", defaults.emergencyRatePerSecond, sink),
        fractions(section, "traction-fractions", TRACTION_STEPS, defaults.tractionFractions, sink),
        fractions(section, "brake-fractions", BRAKE_STEPS, defaults.brakeFractions, sink),
        fraction(section, "mu-reference-motor-fraction", defaults.muReferenceMotorFraction, sink),
        positiveInt(section, "loco-reference-cars", defaults.locoReferenceCars, sink),
        positive(section, "stopped-speed-bps", defaults.stoppedSpeedBps, sink),
        positiveInt(section, "reseat-timeout-ticks", defaults.reseatTimeoutTicks, sink),
        positiveInt(section, "exit-sneak-window-ticks", defaults.exitSneakWindowTicks, sink),
        positiveInt(section, "hud-interval-ticks", defaults.hudIntervalTicks, sink),
        section.getBoolean("allow-creative-mode", defaults.allowCreativeMode),
        positive(section, "start-max-speed-bps", defaults.startMaxSpeedBps, sink),
        section.getBoolean("speed-limit-override", defaults.speedLimitOverride),
        positive(section, "overspeed-red-ratio", defaults.overspeedRedRatio, sink),
        power(section, defaults.defaultPower, sink),
        timings(section.getConfigurationSection("setup-seconds"), defaults.setupTimings, sink),
        positiveInt(section, "cold-after-minutes", defaults.coldAfterMinutes, sink),
        cab(section.getConfigurationSection("simulation"), defaults.cab, sink),
        section.getBoolean("sidebar", defaults.sidebar),
        DriverConfig.from(section.getConfigurationSection("driver"), sink),
        DriveSoundConfig.from(section.getConfigurationSection("sounds"), sink),
        (int)
            Math.round(
                nonNegative(
                        section,
                        "eb-grace-seconds",
                        defaults.ebGraceTicks / (double) TICKS_PER_SECOND,
                        sink)
                    * TICKS_PER_SECOND));
  }

  private static CabConfig cab(
      ConfigurationSection section, CabConfig fallback, Consumer<String> warn) {
    if (section == null) {
      return fallback;
    }
    Consumer<String> scoped =
        message -> warn.accept(message.replace("drive.yml 的 ", "drive.yml 的 simulation."));
    double max =
        positive(section, "main-reservoir-max-kpa", fallback.mainReservoirMaxKpa(), scoped);
    CabConfig parsed =
        new CabConfig(
            max,
            positive(section, "compressor-cut-in-kpa", fallback.compressorCutInKpa(), scoped),
            positive(section, "compressor-fill-seconds", fallback.compressorFillSeconds(), scoped),
            nonNegative(section, "traction-lockout-kpa", fallback.tractionLockoutKpa(), scoped),
            positive(section, "full-brake-kpa", fallback.fullBrakeKpa(), scoped),
            nonNegative(section, "parking-release-kpa", fallback.parkingReleaseKpa(), scoped),
            nonNegative(section, "parking-auto-apply-kpa", fallback.parkingAutoApplyKpa(), scoped),
            positive(section, "brake-cylinder-max-kpa", fallback.brakeCylinderMaxKpa(), scoped),
            nonNegative(
                section, "brake-cylinder-consumption", fallback.brakeCylinderConsumption(), scoped),
            nonNegative(section, "leak-kpa-per-minute", fallback.leakKpaPerMinute(), scoped),
            positive(section, "brake-test-apply-kpa", fallback.brakeTestApplyKpa(), scoped),
            nonNegative(section, "brake-test-release-kpa", fallback.brakeTestReleaseKpa(), scoped),
            (int)
                Math.round(
                    positive(
                            section,
                            "vigilance-interval-seconds",
                            fallback.vigilanceIntervalTicks() / (double) TICKS_PER_SECOND,
                            scoped)
                        * TICKS_PER_SECOND),
            (int)
                Math.round(
                    positive(
                            section,
                            "vigilance-warning-seconds",
                            fallback.vigilanceWarningTicks() / (double) TICKS_PER_SECOND,
                            scoped)
                        * TICKS_PER_SECOND),
            CabConfigSections.blendedBrake(
                section.getConfigurationSection("blended-brake"), fallback.blendedBrake(), warn),
            CabConfigSections.constantPower(
                section.getConfigurationSection("constant-power"), fallback.constantPower(), warn),
            CabConfigSections.brakePipe(
                section.getConfigurationSection("brake-pipe"), fallback.brakePipe(), warn),
            CabConfigSections.faults(
                section.getConfigurationSection("faults"), fallback.faults(), warn));
    if (parsed.compressorCutInKpa() >= max
        || parsed.tractionLockoutKpa() > max
        || parsed.parkingReleaseKpa() > max
        || parsed.parkingAutoApplyKpa() > parsed.parkingReleaseKpa()
        || parsed.brakeTestApplyKpa() <= parsed.brakeTestReleaseKpa()
        || parsed.brakePipe().nominalKpa() > max) {
      warn.accept(
          "drive.yml 的 simulation 段压力关系不合理（启动压力须低于满压，停放制动自动施加压力不能高于缓解压力，试验施加压力须高于缓解压力，制动管定压不能高于主风缸满压），整段使用默认值");
      return fallback;
    }
    return parsed;
  }

  private static PowerSupply power(
      ConfigurationSection section, PowerSupply fallback, Consumer<String> warn) {
    String raw = section.getString("default-power");
    if (raw == null) {
      return fallback;
    }
    return PowerSupply.parse(raw)
        .orElseGet(
            () -> {
              warn.accept(
                  "drive.yml 的 default-power 无效（ptg5/ptg6/shoe/diesel），使用默认值 " + fallback.key());
              return fallback;
            });
  }

  private static SetupTimings timings(
      ConfigurationSection section, SetupTimings fallback, Consumer<String> warn) {
    if (section == null) {
      return fallback;
    }
    return new SetupTimings(
        seconds(section, "key", fallback.keyTicks(), warn),
        seconds(section, "pantograph", fallback.pantographTicks(), warn),
        seconds(section, "shoe", fallback.shoeTicks(), warn),
        seconds(section, "engine", fallback.engineTicks(), warn),
        seconds(section, "breaker", fallback.breakerTicks(), warn),
        seconds(section, "aux", fallback.auxTicks(), warn));
  }

  /** 读取以秒为单位的耗时并换算成 tick；缺失或非法时取默认值。 */
  private static int seconds(
      ConfigurationSection section, String key, int fallbackTicks, Consumer<String> warn) {
    if (!section.contains(key)) {
      return fallbackTicks;
    }
    double value = section.getDouble(key, Double.NaN);
    if (!Double.isFinite(value) || value < 0.0 || value > MAX_SETUP_SECONDS) {
      warn.accept(
          "drive.yml 的 setup-seconds."
              + key
              + " 必须在 0 到 "
              + (int) MAX_SETUP_SECONDS
              + " 秒之间，使用默认值 "
              + fallbackTicks / (double) TICKS_PER_SECOND
              + " 秒");
      return fallbackTicks;
    }
    return (int) Math.round(value * TICKS_PER_SECOND);
  }

  private static double positive(
      ConfigurationSection section, String key, double fallback, Consumer<String> warn) {
    if (!section.contains(key)) {
      return fallback;
    }
    double value = section.getDouble(key, Double.NaN);
    if (!Double.isFinite(value) || value <= 0.0) {
      warn.accept("drive.yml 的 " + key + " 必须为正数，使用默认值 " + fallback);
      return fallback;
    }
    return value;
  }

  private static int positiveInt(
      ConfigurationSection section, String key, int fallback, Consumer<String> warn) {
    if (!section.contains(key)) {
      return fallback;
    }
    int value = section.getInt(key, 0);
    if (value <= 0) {
      warn.accept("drive.yml 的 " + key + " 必须为正整数，使用默认值 " + fallback);
      return fallback;
    }
    return value;
  }

  private static double nonNegative(
      ConfigurationSection section, String key, double fallback, Consumer<String> warn) {
    if (!section.contains(key)) {
      return fallback;
    }
    double value = section.getDouble(key, Double.NaN);
    if (!Double.isFinite(value) || value < 0.0) {
      warn.accept("drive.yml 的 " + key + " 不能为负数，使用默认值 " + fallback);
      return fallback;
    }
    return value;
  }

  private static double atLeastOne(
      ConfigurationSection section, String key, double fallback, Consumer<String> warn) {
    if (!section.contains(key)) {
      return fallback;
    }
    double value = section.getDouble(key, Double.NaN);
    if (!Double.isFinite(value) || value < 1.0) {
      warn.accept("drive.yml 的 " + key + " 不能小于 1，使用默认值 " + fallback);
      return fallback;
    }
    return value;
  }

  private static double fraction(
      ConfigurationSection section, String key, double fallback, Consumer<String> warn) {
    if (!section.contains(key)) {
      return fallback;
    }
    double value = section.getDouble(key, Double.NaN);
    if (!Double.isFinite(value) || value <= 0.0 || value > 1.0) {
      warn.accept("drive.yml 的 " + key + " 必须在 (0, 1] 内，使用默认值 " + fallback);
      return fallback;
    }
    return value;
  }

  private static List<Double> fractions(
      ConfigurationSection section,
      String key,
      int expectedSize,
      List<Double> fallback,
      Consumer<String> warn) {
    if (!section.contains(key)) {
      return fallback;
    }
    List<?> raw = section.getList(key);
    List<Double> parsed = new ArrayList<>();
    if (raw != null) {
      for (Object item : raw) {
        if (item instanceof Number number) {
          parsed.add(number.doubleValue());
        }
      }
    }
    boolean valid = parsed.size() == expectedSize;
    double previous = 0.0;
    for (double value : parsed) {
      if (!Double.isFinite(value) || value <= previous || value > 1.0) {
        valid = false;
        break;
      }
      previous = value;
    }
    if (!valid) {
      warn.accept(
          "drive.yml 的 " + key + " 需要 " + expectedSize + " 个递增且不超过 1 的比例，使用默认值 " + fallback);
      return fallback;
    }
    return parsed;
  }
}
