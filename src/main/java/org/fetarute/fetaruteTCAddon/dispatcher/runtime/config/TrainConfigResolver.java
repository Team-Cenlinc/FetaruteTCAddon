package org.fetarute.fetaruteTCAddon.dispatcher.runtime.config;

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
  public static final String TAG_TRAIN_ACCEL_BPS2 = "FTA_TRAIN_ACCEL_BPS2";
  public static final String TAG_TRAIN_DECEL_BPS2 = "FTA_TRAIN_DECEL_BPS2";
  public static final String TAG_TRAIN_CONFIG_AT = "FTA_TRAIN_CONFIG_AT";

  /** 动力配置方式（{@code mu} 动车组 / {@code loco} 机车牵引），缺省按车种推断；用于手动驾驶。 */
  public static final String TAG_TRAIN_MODE = "FTA_TRAIN_MODE";

  /** 动拖比，如 {@code 4M2T} 或 {@code 0.67}；用于手动驾驶。 */
  public static final String TAG_TRAIN_MT = "FTA_TRAIN_MT";

  /** 受电方式：{@code ptg5} / {@code ptg6} 受电弓、{@code shoe} 集电靴、{@code diesel} 内燃；用于手动驾驶的启动流程。 */
  public static final String TAG_TRAIN_POWER = "FTA_TRAIN_POWER";

  /** 最高速度上限（格/秒）；用于手动驾驶。 */
  public static final String TAG_TRAIN_MAX_BPS = "FTA_TRAIN_MAX_BPS";

  /**
   * 解析列车配置，优先读取 TrainProperties tags，缺失时回退到配置默认值。
   *
   * <p>巡航速度不在 tags 中维护，运行时使用图默认速度与边限速作为基准。
   */
  public TrainConfig resolve(TrainProperties properties, ConfigManager.ConfigView config) {
    Objects.requireNonNull(config, "config");
    TrainType type = readType(properties).orElse(config.trainConfigSettings().defaultTrainType());
    ConfigManager.TrainTypeSettings defaults = config.trainConfigSettings().forType(type);
    double accel =
        TrainTagHelper.readDoubleTag(properties, TAG_TRAIN_ACCEL_BPS2)
            .filter(value -> value > 0.0)
            .orElse(defaults.accelBps2());
    double decel =
        TrainTagHelper.readDoubleTag(properties, TAG_TRAIN_DECEL_BPS2)
            .filter(value -> value > 0.0)
            .orElse(defaults.decelBps2());
    return new TrainConfig(type, accel, decel);
  }

  /** 从列车 tags 解析车种（FTA_TRAIN_TYPE）。 */
  public Optional<TrainType> readType(TrainProperties properties) {
    return TrainTagHelper.readTagValue(properties, TAG_TRAIN_TYPE).flatMap(TrainType::parse);
  }

  /**
   * 将列车配置写回 tags（车种/加减速）。
   *
   * <p>调用方可通过 Optional 覆盖单个字段，未提供的字段保留当前配置值。
   */
  public void writeConfig(
      TrainProperties properties,
      TrainConfig config,
      Optional<Double> accelOverride,
      Optional<Double> decelOverride) {
    Objects.requireNonNull(config, "config");
    if (properties == null) {
      return;
    }
    TrainTagHelper.writeTag(properties, TAG_TRAIN_TYPE, config.type().name());
    TrainTagHelper.writeTag(
        properties, TAG_TRAIN_ACCEL_BPS2, String.valueOf(accelOverride.orElse(config.accelBps2())));
    TrainTagHelper.writeTag(
        properties, TAG_TRAIN_DECEL_BPS2, String.valueOf(decelOverride.orElse(config.decelBps2())));
    TrainTagHelper.writeTag(
        properties, TAG_TRAIN_CONFIG_AT, String.valueOf(Instant.now().toEpochMilli()));
  }
}
