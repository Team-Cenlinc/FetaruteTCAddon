package org.fetarute.fetaruteTCAddon.drive.cab;

import java.util.Objects;

/**
 * 机车牵引的制动管（列车管）。
 *
 * <ul>
 *   <li>缓解位：主风缸向制动管充风到定压，充风速率按编组节数折算（车厢越多越慢），充风消耗主风缸；制动管压力不会高于主风缸；
 *   <li>常用制动：制动管按制动力度减压，常用全制动减压 {@link BrakePipeConfig#fullServiceReductionKpa()}，制动缸压力与减压量成比例；
 *   <li>紧急制动：制动管快速排空；
 *   <li>失压：制动管压力跌破 {@link BrakePipeConfig#emergencyKpa()}（不是驾驶员拉紧急制动造成的）时记一次失压，由调用方施加紧急制动。
 *       只在跌破的那一刻记一次，充风回升时不会再触发，免得停车后无法缓解充风。
 * </ul>
 *
 * <p>本类不依赖任何服务器对象，时间由调用方传入（秒）。
 */
public final class BrakePipe {

  /** 紧急制动时制动管的排风速率（kPa/秒），两秒左右排空。 */
  static final double EMERGENCY_VENT_KPA_PER_SECOND = 250.0;

  private final BrakePipeConfig config;
  private final double chargeKpaPerSecond;
  private final double consumptionPerKpa;
  private double pressureKpa;
  private boolean lossPending;

  /**
   * @param cars 编组节数，决定充风速率与充风耗风
   * @param initialKpa 制动管初始压力
   */
  public BrakePipe(BrakePipeConfig config, int cars, double initialKpa) {
    this.config = Objects.requireNonNull(config, "config");
    int carCount = Math.max(1, cars);
    this.chargeKpaPerSecond = config.nominalKpa() / Math.max(0.05, config.chargeSeconds(carCount));
    this.consumptionPerKpa = config.consumptionPerCar() * carCount;
    this.pressureKpa = Math.max(0.0, Math.min(config.nominalKpa(), initialKpa));
  }

  /**
   * 推进一步。
   *
   * @param seconds 时间步长（秒）
   * @param serviceRatio 常用制动的减压比例：0 为缓解，1 为常用全制动
   * @param venting 是否在紧急制动排风
   * @param mainReservoirKpa 主风缸当前压力，制动管不会充到比它高
   * @return 这一步充风消耗的主风缸压力（kPa）
   */
  public double tick(
      double seconds, double serviceRatio, boolean venting, double mainReservoirKpa) {
    if (!(seconds > 0.0)) {
      return 0.0;
    }
    double before = pressureKpa;
    double consumed = 0.0;
    if (venting) {
      pressureKpa = Math.max(0.0, pressureKpa - EMERGENCY_VENT_KPA_PER_SECOND * seconds);
    } else {
      double ratio = Math.max(0.0, Math.min(1.0, serviceRatio));
      double target =
          Math.min(
              config.nominalKpa() - ratio * config.fullServiceReductionKpa(),
              Math.max(0.0, mainReservoirKpa));
      if (target > pressureKpa) {
        double rise = Math.min(target - pressureKpa, chargeKpaPerSecond * seconds);
        pressureKpa += rise;
        consumed = rise * consumptionPerKpa;
      } else if (target < pressureKpa) {
        pressureKpa = Math.max(target, pressureKpa - config.serviceRateKpaPerSecond() * seconds);
      }
      if (before >= config.emergencyKpa() && pressureKpa < config.emergencyKpa()) {
        lossPending = true;
      }
    }
    return consumed;
  }

  /** 制动管压力（kPa）。 */
  public double pressureKpa() {
    return pressureKpa;
  }

  /** 减压量（kPa）：定压减去当前压力。 */
  public double reductionKpa() {
    return Math.max(0.0, config.nominalKpa() - pressureKpa);
  }

  /** 按减压量折算的制动缸压力指令：减压到常用全制动时为常用全制动压力，再多也不超过它（紧急制动另行加压）。 */
  public double cylinderTargetKpa(double brakeCylinderMaxKpa) {
    return brakeCylinderMaxKpa * Math.min(1.0, reductionKpa() / config.fullServiceReductionKpa());
  }

  /** 制动管压力是否不到常用全制动后的压力（紧急制动后、失风后尚未充回来）。 */
  public boolean belowFullService() {
    return pressureKpa < config.fullServiceKpa();
  }

  /** 取出并清除“制动管失压”的记号。 */
  public boolean takeLoss() {
    boolean loss = lossPending;
    lossPending = false;
    return loss;
  }

  public BrakePipeConfig config() {
    return config;
  }
}
