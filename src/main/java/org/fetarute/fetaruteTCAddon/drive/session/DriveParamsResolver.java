package org.fetarute.fetaruteTCAddon.drive.session;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainTagHelper;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfig;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.drive.DriveConfig;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveMode;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveParams;
import org.fetarute.fetaruteTCAddon.drive.dynamics.MotorRatio;

/**
 * 从列车标签解析手动驾驶的动力学参数。
 *
 * <p>车种与加减速走调度控车同一个解析器（{@link
 * TrainConfigResolver}）：无论出车写入的标签、用户设定的标签还是车种配置，取到的数都与自动运行完全相同；没打车种标签的列车取到的就是编表运行曲线所用的默认车种那组数。
 *
 * <p>动力配置方式、动拖比、最高速度只有手动驾驶使用，由 {@link DriveParams#resolve} 在此基础上折算。
 */
final class DriveParamsResolver {

  private static final TrainConfigResolver RESOLVER = new TrainConfigResolver();

  private DriveParamsResolver() {}

  /**
   * 解析一列车的驾驶参数。
   *
   * @param properties 列车属性
   * @param carCount 编组节数
   * @param dispatchConfig 调度配置（车种与加减速）
   * @param driveConfig 手动驾驶配置
   */
  static DriveParams resolve(
      TrainProperties properties,
      int carCount,
      ConfigManager.ConfigView dispatchConfig,
      DriveConfig driveConfig) {
    Objects.requireNonNull(dispatchConfig, "dispatchConfig");
    Objects.requireNonNull(driveConfig, "driveConfig");
    TrainConfig base = RESOLVER.resolve(properties, dispatchConfig);
    Optional<DriveMode> mode =
        TrainTagHelper.readTagValue(properties, TrainConfigResolver.TAG_TRAIN_MODE)
            .flatMap(DriveMode::parse);
    OptionalDouble motorFraction =
        TrainTagHelper.readTagValue(properties, TrainConfigResolver.TAG_TRAIN_MT)
            .map(MotorRatio::parse)
            .orElse(OptionalDouble.empty());
    OptionalDouble maxSpeed =
        TrainTagHelper.readDoubleTag(properties, TrainConfigResolver.TAG_TRAIN_MAX_BPS)
            .map(OptionalDouble::of)
            .orElse(OptionalDouble.empty());
    return DriveParams.resolve(
        base.type(),
        base.accelBps2(),
        base.decelBps2(),
        mode,
        motorFraction,
        maxSpeed,
        carCount,
        driveConfig);
  }
}
