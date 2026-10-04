package org.fetarute.fetaruteTCAddon.drive.cab;

import java.util.Objects;

/**
 * 气压系统：压缩机、主风缸与制动缸，以及靠风压缓解的停放制动。
 *
 * <ul>
 *   <li>压缩机由辅助电源供电。自动压缩机（动车组）在主风缸低于启动压力时启动、到满压停止；手动压缩机（机车）还要打开压缩机开关；
 *   <li>制动缸压力跟随制动力度，升压时消耗主风缸；主风缸不足时制动力按比例下降，低于封锁线时封锁牵引；
 *   <li>停放制动在主风缸达到缓解压力后才能缓解；主风缸跌到自动施加压力以下时，停放制动（弹簧制动）自动施加，失风时列车总能停下；
 *   <li>压缩机故障时主风缸不再回升；制动缸漏泄时制动缸压力在指令不变期间不断下降，只有指令升高（重新施加制动）才会再充上。
 * </ul>
 *
 * <p>本类不依赖任何服务器对象，时间由调用方传入（秒）。
 */
public final class AirSystem {

  /** 切换停放制动的结果。 */
  public enum ParkingResult {
    RELEASED,
    APPLIED,
    NOT_ENOUGH_AIR
  }

  /** 制动缸指令升高超过它（kPa）才算重新施加制动。 */
  private static final double TARGET_EPSILON_KPA = 0.5;

  private final CabConfig config;
  private final boolean manualCompressor;
  private double mainReservoirKpa;
  private double brakeCylinderKpa;
  private boolean compressorSwitch;
  private boolean compressorRunning;
  private boolean parkingApplied = true;
  private boolean parkingAutoApplied;
  private boolean compressorFailed;
  private double cylinderLeakKpaPerSecond;
  private double lastCylinderTargetKpa;

  /**
   * @param manualCompressor 压缩机是否要手动打开（机车）；否则自动运转（动车组）
   * @param initialMainReservoirKpa 主风缸初始压力
   * @param compressorSwitch 手动压缩机开关的初始状态
   */
  public AirSystem(
      CabConfig config,
      boolean manualCompressor,
      double initialMainReservoirKpa,
      boolean compressorSwitch) {
    this.config = Objects.requireNonNull(config, "config");
    this.manualCompressor = manualCompressor;
    this.mainReservoirKpa = clamp(initialMainReservoirKpa, 0.0, config.mainReservoirMaxKpa());
    this.compressorSwitch = compressorSwitch;
  }

  /**
   * 推进一步。
   *
   * @param seconds 时间步长（秒）
   * @param auxPowered 辅助电源是否接通（压缩机的电源）
   * @param brakeDemand 制动力度：常用全制动为 1，紧急制动可超过 1，不制动为 0
   */
  public void tick(double seconds, boolean auxPowered, double brakeDemand) {
    tickCylinder(seconds, auxPowered, Math.max(0.0, brakeDemand) * config.brakeCylinderMaxKpa());
  }

  /**
   * 推进一步，制动缸压力的指令由调用方给出（电空制动只把空气制动的部分折算成制动缸压力，机车由制动管减压量决定）。
   *
   * @param seconds 时间步长（秒）
   * @param compressorPowered 压缩机是否有电（辅助电源接通，且电力牵引的列车没有失电）
   * @param cylinderTargetKpa 制动缸压力的指令；实际压力不会超过主风缸
   */
  public void tickCylinder(double seconds, boolean compressorPowered, double cylinderTargetKpa) {
    if (!(seconds > 0.0)) {
      return;
    }
    boolean enabled =
        compressorPowered && !compressorFailed && (!manualCompressor || compressorSwitch);
    if (!enabled || mainReservoirKpa >= config.mainReservoirMaxKpa()) {
      compressorRunning = false;
    } else if (mainReservoirKpa <= config.compressorCutInKpa()) {
      compressorRunning = true;
    }
    if (compressorRunning) {
      double rate = config.mainReservoirMaxKpa() / Math.max(1.0, config.compressorFillSeconds());
      mainReservoirKpa = Math.min(config.mainReservoirMaxKpa(), mainReservoirKpa + rate * seconds);
    }
    double target = Math.min(mainReservoirKpa, Math.max(0.0, cylinderTargetKpa));
    boolean reapplied = target > lastCylinderTargetKpa + TARGET_EPSILON_KPA;
    if (cylinderLeakKpaPerSecond > 0.0 && !reapplied) {
      // 漏泄：指令不变时保不住压力，缓解照常生效。
      brakeCylinderKpa =
          Math.min(target, Math.max(0.0, brakeCylinderKpa - cylinderLeakKpaPerSecond * seconds));
    } else {
      if (target > brakeCylinderKpa) {
        mainReservoirKpa =
            Math.max(
                0.0,
                mainReservoirKpa - (target - brakeCylinderKpa) * config.brakeCylinderConsumption());
      }
      brakeCylinderKpa = target;
    }
    lastCylinderTargetKpa = target;
    if (mainReservoirKpa < config.parkingAutoApplyKpa() && !parkingApplied) {
      parkingApplied = true;
      parkingAutoApplied = true;
    }
  }

  /** 从主风缸取风（机车向制动管充风）。 */
  public void consume(double kpa) {
    if (kpa > 0.0 && Double.isFinite(kpa)) {
      mainReservoirKpa = Math.max(0.0, mainReservoirKpa - kpa);
    }
  }

  /** 压缩机故障：为真时压缩机不再运转。 */
  public void setCompressorFailed(boolean failed) {
    this.compressorFailed = failed;
    if (failed) {
      compressorRunning = false;
    }
  }

  public boolean compressorFailed() {
    return compressorFailed;
  }

  /** 制动缸漏泄速率（kPa/秒）；0 为不漏。 */
  public void setCylinderLeak(double kpaPerSecond) {
    this.cylinderLeakKpaPerSecond =
        Double.isFinite(kpaPerSecond) ? Math.max(0.0, kpaPerSecond) : 0.0;
  }

  /** 制动缸是否在漏泄。 */
  public boolean cylinderLeaking() {
    return cylinderLeakKpaPerSecond > 0.0;
  }

  /** 空气制动可发挥的比例（0–1）：主风缸压力不足时下降。 */
  public double brakeScale() {
    if (mainReservoirKpa >= config.fullBrakeKpa()) {
      return 1.0;
    }
    return clamp(mainReservoirKpa / Math.max(1.0, config.fullBrakeKpa()), 0.0, 1.0);
  }

  /** 主风缸压力低于封锁线，牵引被封锁。 */
  public boolean tractionLocked() {
    return mainReservoirKpa < config.tractionLockoutKpa();
  }

  /** 切换停放制动：施加着的尝试缓解（风压不足时拒绝），已缓解的重新施加。 */
  public ParkingResult toggleParking() {
    if (!parkingApplied) {
      parkingApplied = true;
      return ParkingResult.APPLIED;
    }
    if (mainReservoirKpa < config.parkingReleaseKpa()) {
      return ParkingResult.NOT_ENOUGH_AIR;
    }
    parkingApplied = false;
    return ParkingResult.RELEASED;
  }

  /**
   * 切换手动压缩机开关。
   *
   * @return 切换后的开关状态；自动压缩机没有开关，返回 {@code true} 且不做任何事
   */
  public boolean toggleCompressor() {
    if (!manualCompressor) {
      return true;
    }
    compressorSwitch = !compressorSwitch;
    if (!compressorSwitch) {
      compressorRunning = false;
    }
    return compressorSwitch;
  }

  public double mainReservoirKpa() {
    return mainReservoirKpa;
  }

  public double brakeCylinderKpa() {
    return brakeCylinderKpa;
  }

  public boolean manualCompressor() {
    return manualCompressor;
  }

  public boolean compressorSwitch() {
    return compressorSwitch;
  }

  public boolean compressorRunning() {
    return compressorRunning;
  }

  public boolean parkingApplied() {
    return parkingApplied;
  }

  /** 取出并清除“失风自动施加了停放制动”的记号。 */
  public boolean takeParkingAutoApplied() {
    boolean applied = parkingAutoApplied;
    parkingAutoApplied = false;
    return applied;
  }

  private static double clamp(double value, double min, double max) {
    return Math.max(min, Math.min(max, value));
  }
}
