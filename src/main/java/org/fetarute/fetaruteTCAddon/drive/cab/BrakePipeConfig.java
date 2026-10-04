package org.fetarute.fetaruteTCAddon.drive.cab;

/**
 * 机车牵引的制动管参数（{@code drive.yml} 的 {@code simulation.brake-pipe} 段）。压力单位为 kPa。
 *
 * <p>缓解位时主风缸向制动管充风到定压，编组越长充风越久；施加制动就是制动管减压，制动缸压力与减压量成比例，减压量达到常用全制动减压量时制动缸达到常用全制动压力。
 *
 * @param nominalKpa 制动管定压
 * @param fullServiceReductionKpa 常用全制动（B4）的减压量
 * @param chargeBaseSeconds 制动管从 0 充到定压的基础秒数
 * @param chargeSecondsPerCar 每节车厢增加的充风秒数
 * @param serviceRateKpaPerSecond 常用制动的减压速率
 * @param emergencyKpa 制动管压力跌破它（不是驾驶员拉紧急制动造成的）时自动紧急制动
 * @param consumptionPerCar 制动管每升高 1 kPa、每节车厢消耗的主风缸压力
 * @param testReductionKpa 制动试验中减压量至少要达到的值
 * @param testHoldSeconds 制动试验保压的秒数
 * @param testMaxLeakKpa 保压期间制动缸允许下降的最大值，超过即试验不通过
 */
public record BrakePipeConfig(
    double nominalKpa,
    double fullServiceReductionKpa,
    double chargeBaseSeconds,
    double chargeSecondsPerCar,
    double serviceRateKpaPerSecond,
    double emergencyKpa,
    double consumptionPerCar,
    double testReductionKpa,
    double testHoldSeconds,
    double testMaxLeakKpa) {

  public BrakePipeConfig {
    requirePositive(nominalKpa, "nominalKpa");
    requirePositive(fullServiceReductionKpa, "fullServiceReductionKpa");
    requireNonNegative(chargeBaseSeconds, "chargeBaseSeconds");
    requireNonNegative(chargeSecondsPerCar, "chargeSecondsPerCar");
    requirePositive(serviceRateKpaPerSecond, "serviceRateKpaPerSecond");
    requireNonNegative(emergencyKpa, "emergencyKpa");
    requireNonNegative(consumptionPerCar, "consumptionPerCar");
    requirePositive(testReductionKpa, "testReductionKpa");
    requirePositive(testHoldSeconds, "testHoldSeconds");
    requireNonNegative(testMaxLeakKpa, "testMaxLeakKpa");
    if (chargeBaseSeconds + chargeSecondsPerCar <= 0.0) {
      throw new IllegalArgumentException("充风时长必须为正数");
    }
    if (fullServiceReductionKpa >= nominalKpa
        || emergencyKpa >= nominalKpa - fullServiceReductionKpa
        || testReductionKpa > fullServiceReductionKpa) {
      throw new IllegalArgumentException("常用全制动减压量须小于定压，紧急制动压力须低于常用全制动后的制动管压力，试验减压量不能超过常用全制动减压量");
    }
  }

  /** 内置默认值：定压 500，全制动减压 150，充风 10 秒 + 每节 4 秒，失压 250 以下自动紧急制动，试验减压 100、保压 10 秒、漏泄不超过 10。 */
  public static BrakePipeConfig defaults() {
    return new BrakePipeConfig(500, 150, 10, 4, 40, 250, 0.04, 100, 10, 10);
  }

  /** 制动管从 0 充到定压所需的秒数。 */
  public double chargeSeconds(int cars) {
    return chargeBaseSeconds + chargeSecondsPerCar * Math.max(1, cars);
  }

  /** 常用全制动后的制动管压力：低于它说明制动没有缓解到能牵引的程度。 */
  public double fullServiceKpa() {
    return nominalKpa - fullServiceReductionKpa;
  }

  private static void requirePositive(double value, String name) {
    if (!Double.isFinite(value) || value <= 0.0) {
      throw new IllegalArgumentException(name + " 必须为正数");
    }
  }

  private static void requireNonNegative(double value, String name) {
    if (!Double.isFinite(value) || value < 0.0) {
      throw new IllegalArgumentException(name + " 不能为负数");
    }
  }
}
