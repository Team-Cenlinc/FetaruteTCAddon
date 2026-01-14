package org.fetarute.fetaruteTCAddon.dispatcher.runtime.train;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainTagHelper;

/**
 * 解析/写入列车配置（基于 TrainProperties tags + config 默认模板）。
 *
 * <p>当 tags 缺失时回退为配置默认值。
 */
public final class TrainConfigResolver {

  public static final String TAG_TRAIN_TYPE = "FTA_TRAIN_TYPE";
  public static final String TAG_TRAIN_CRUISE_BPS = "FTA_TRAIN_CRUISE_BPS";
  public static final String TAG_TRAIN_CAUTION_BPS = "FTA_TRAIN_CAUTION_BPS";
  public static final String TAG_TRAIN_ACCEL_BPS2 = "FTA_TRAIN_ACCEL_BPS2";
  public static final String TAG_TRAIN_DECEL_BPS2 = "FTA_TRAIN_DECEL_BPS2";
  public static final String TAG_TRAIN_CONFIG_AT = "FTA_TRAIN_CONFIG_AT";

  public TrainConfig resolve(TrainProperties properties, ConfigManager.ConfigView config) {
    Objects.requireNonNull(config, "config");
    TrainType type = readType(properties).orElse(config.trainConfigSettings().defaultTrainType());
    ConfigManager.TrainTypeSettings defaults = config.trainConfigSettings().forType(type);
    double cruise =
        TrainTagHelper.readDoubleTag(properties, TAG_TRAIN_CRUISE_BPS)
            .filter(value -> value > 0.0)
            .orElse(defaults.cruiseSpeedBps());
    double caution =
        TrainTagHelper.readDoubleTag(properties, TAG_TRAIN_CAUTION_BPS)
            .filter(value -> value >= 0.0)
            .orElse(defaults.cautionSpeedBps());
    double accel =
        TrainTagHelper.readDoubleTag(properties, TAG_TRAIN_ACCEL_BPS2)
            .filter(value -> value > 0.0)
            .orElse(defaults.accelBps2());
    double decel =
        TrainTagHelper.readDoubleTag(properties, TAG_TRAIN_DECEL_BPS2)
            .filter(value -> value > 0.0)
            .orElse(defaults.decelBps2());
    return new TrainConfig(type, cruise, caution, accel, decel);
  }

  public Optional<TrainType> readType(TrainProperties properties) {
    return TrainTagHelper.readTagValue(properties, TAG_TRAIN_TYPE).flatMap(TrainType::parse);
  }

  public void writeConfig(
      TrainProperties properties,
      TrainConfig config,
      Optional<Double> cruiseOverride,
      Optional<Double> cautionOverride,
      Optional<Double> accelOverride,
      Optional<Double> decelOverride) {
    Objects.requireNonNull(config, "config");
    if (properties == null) {
      return;
    }
    TrainTagHelper.writeTag(properties, TAG_TRAIN_TYPE, config.type().name());
    TrainTagHelper.writeTag(
        properties,
        TAG_TRAIN_CRUISE_BPS,
        String.valueOf(cruiseOverride.orElse(config.cruiseSpeedBps())));
    TrainTagHelper.writeTag(
        properties,
        TAG_TRAIN_CAUTION_BPS,
        String.valueOf(cautionOverride.orElse(config.cautionSpeedBps())));
    TrainTagHelper.writeTag(
        properties, TAG_TRAIN_ACCEL_BPS2, String.valueOf(accelOverride.orElse(config.accelBps2())));
    TrainTagHelper.writeTag(
        properties, TAG_TRAIN_DECEL_BPS2, String.valueOf(decelOverride.orElse(config.decelBps2())));
    TrainTagHelper.writeTag(
        properties, TAG_TRAIN_CONFIG_AT, String.valueOf(Instant.now().toEpochMilli()));
  }
}
