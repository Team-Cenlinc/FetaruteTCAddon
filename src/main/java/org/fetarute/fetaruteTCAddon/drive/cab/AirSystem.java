package org.fetarute.fetaruteTCAddon.drive.cab;

import java.util.Objects;

/**
 * 气压系统：压缩机、主风缸与制动缸，以及靠风压缓解的停放制动。
 *
 * <ul>
 *   <li>压缩机由辅助电源供电。自动压缩机（动车组）在主风缸低于启动压力时启动、到满压停止；手动压缩机（机车）还要打开压缩机开关；
 *   <li>制动缸压力跟随制动力度，升压时消耗主风缸；主风缸不足时制动力按比例下降，低于封锁线时封锁牵引；
 *   <li>停放制动在主风缸达到缓解压力后才能缓解；主风缸跌到自动施加压力以下时，停放制动（弹簧制动）自动施加，失风时列车总能停下。
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

  private final CabConfig config;
  private final boolean manualCompressor;
  private double mainReservoirKpa;
  private double brakeCylinderKpa;
  private boolean compressorSwitch;
  private boolean compressorRunning;
  private boolean parkingApplied = true;
  private boolean parkingAutoApplied;

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
    if (!(seconds > 0.0)) {
      return;
    }
    boolean enabled = auxPowered && (!manualCompressor || compressorSwitch);
    if (!enabled || mainReservoirKpa >= config.mainReservoirMaxKpa()) {
      compressorRunning = false;
    } else if (mainReservoirKpa <= config.compressorCutInKpa()) {
      compressorRunning = true;
    }
    if (compressorRunning) {
      double rate = config.mainReservoirMaxKpa() / Math.max(1.0, config.compressorFillSeconds());
      mainReservoirKpa = Math.min(config.mainReservoirMaxKpa(), mainReservoirKpa + rate * seconds);
    }
    double target =
        Math.min(mainReservoirKpa, Math.max(0.0, brakeDemand) * config.brakeCylinderMaxKpa());
    if (target > brakeCylinderKpa) {
      mainReservoirKpa =
          Math.max(
              0.0,
              mainReservoirKpa - (target - brakeCylinderKpa) * config.brakeCylinderConsumption());
    }
    brakeCylinderKpa = target;
    if (mainReservoirKpa < config.parkingAutoApplyKpa() && !parkingApplied) {
      parkingApplied = true;
      parkingAutoApplied = true;
    }
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
