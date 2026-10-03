package org.fetarute.fetaruteTCAddon.drive.dynamics;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainType;
import org.fetarute.fetaruteTCAddon.drive.DriveConfig;

/**
 * 一列车手动驾驶时的动力学参数。
 *
 * <p>加减速度来自车种预设或用户标签；动力配置方式再把加速度折算成有效值：
 *
 * <ul>
 *   <li>动车组：{@code 预设加速度 × min(1, 动车占比 ÷ 基准动车占比)}，未给动拖比时按基准动车占比，即不折算；
 *   <li>机车牵引：{@code 预设加速度 × min(1, 基准车厢数 ÷ 实际车厢数)}。
 * </ul>
 *
 * <p>常用制动减速度不随动拖比变化。
 *
 * @param mode 动力配置方式
 * @param accelBps2 折算后的满牵引加速度（格/秒²）
 * @param decelBps2 常用制动减速度（格/秒²）
 * @param maxSpeedBps 最高速度（格/秒）
 * @param motorFraction 折算时采用的动车占比；机车牵引为 1
 */
public record DriveParams(
    DriveMode mode, double accelBps2, double decelBps2, double maxSpeedBps, double motorFraction) {

  public DriveParams {
    Objects.requireNonNull(mode, "mode");
    requirePositive(accelBps2, "accelBps2");
    requirePositive(decelBps2, "decelBps2");
    requirePositive(maxSpeedBps, "maxSpeedBps");
    requirePositive(motorFraction, "motorFraction");
  }

  /**
   * 按车种预设、用户标签和编组折算参数。
   *
   * @param type 车种
   * @param baseAccelBps2 车种或用户标签给出的加速度
   * @param baseDecelBps2 车种或用户标签给出的常用制动减速度
   * @param modeOverride 用户指定的动力配置方式；缺省按车种推断
   * @param motorFraction 用户给出的动车占比
   * @param maxSpeedOverride 用户给出的最高速度（格/秒）
   * @param carCount 编组节数
   * @param config 手动驾驶配置
   */
  public static DriveParams resolve(
      TrainType type,
      double baseAccelBps2,
      double baseDecelBps2,
      Optional<DriveMode> modeOverride,
      OptionalDouble motorFraction,
      OptionalDouble maxSpeedOverride,
      int carCount,
      DriveConfig config) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(config, "config");
    DriveMode mode = modeOverride.orElseGet(() -> defaultMode(type));
    double effectiveAccel;
    double effectiveMotorFraction;
    if (mode == DriveMode.MU) {
      double reference = config.muReferenceMotorFraction();
      effectiveMotorFraction = motorFraction.orElse(reference);
      effectiveAccel = baseAccelBps2 * Math.min(1.0, effectiveMotorFraction / reference);
    } else {
      effectiveMotorFraction = 1.0;
      int cars = Math.max(1, carCount);
      effectiveAccel = baseAccelBps2 * Math.min(1.0, (double) config.locoReferenceCars() / cars);
    }
    double maxSpeed =
        maxSpeedOverride.isPresent() && maxSpeedOverride.getAsDouble() > 0.0
            ? maxSpeedOverride.getAsDouble()
            : config.defaultMaxSpeedBps();
    return new DriveParams(mode, effectiveAccel, baseDecelBps2, maxSpeed, effectiveMotorFraction);
  }

  /** 车种默认的动力配置方式：机车车种按机车牵引，其余按动车组。 */
  public static DriveMode defaultMode(TrainType type) {
    return switch (type) {
      case ELECTRIC_LOCO, DIESEL_PUSH_PULL -> DriveMode.LOCO;
      default -> DriveMode.MU;
    };
  }

  private static void requirePositive(double value, String name) {
    if (!Double.isFinite(value) || value <= 0.0) {
      throw new IllegalArgumentException(name + " 必须为正数");
    }
  }
}
