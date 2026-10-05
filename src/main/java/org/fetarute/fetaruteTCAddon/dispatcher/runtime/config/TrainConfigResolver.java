package org.fetarute.fetaruteTCAddon.dispatcher.runtime.config;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainTagHelper;

/**
 * 解析/写入列车配置（基于 TrainProperties tags + config 默认模板）。
 *
 * <p>当 tags 缺失时回退为配置默认值。
 *
 * <h2>加减速标签的两种来源</h2>
 *
 * <ul>
 *   <li>用户设定（{@code /fta train config set}、模板列车自带）：解析时优先于车种配置；
 *   <li>出车写入（{@link SpawnMotionTags}，带 {@link #TAG_TRAIN_CONFIG_SOURCE}{@code =}{@link
 *       #SOURCE_SPAWN}）：只是车种配置的镜像，写下时与车种配置相同。解析时仍按车种配置取值，配置重载后标签尚未刷新的列车也照新配置控车，与没有写入标签时完全一致。
 * </ul>
 */
public final class TrainConfigResolver {

  public static final String TAG_TRAIN_TYPE = "FTA_TRAIN_TYPE";
  public static final String TAG_TRAIN_ACCEL_BPS2 = "FTA_TRAIN_ACCEL_BPS2";
  public static final String TAG_TRAIN_DECEL_BPS2 = "FTA_TRAIN_DECEL_BPS2";
  public static final String TAG_TRAIN_CONFIG_AT = "FTA_TRAIN_CONFIG_AT";

  /**
   * 加减速标签的来源。值为 {@link #SOURCE_SPAWN} 时 {@link #TAG_TRAIN_ACCEL_BPS2}/{@link #TAG_TRAIN_DECEL_BPS2}
   * 是出车时按车种配置写下的，不是用户设定；用户经 {@link #writeConfig} 设定加减速时撤掉该标记。
   */
  public static final String TAG_TRAIN_CONFIG_SOURCE = "FTA_TRAIN_CONFIG_SOURCE";

  /** {@link #TAG_TRAIN_CONFIG_SOURCE} 的取值：出车时写入。 */
  public static final String SOURCE_SPAWN = "spawn";

  /** 动力配置方式（{@code mu} 动车组 / {@code loco} 机车牵引），缺省按车种推断；用于手动驾驶。 */
  public static final String TAG_TRAIN_MODE = "FTA_TRAIN_MODE";

  /** 动拖比，如 {@code 4M2T} 或 {@code 0.67}；用于手动驾驶。 */
  public static final String TAG_TRAIN_MT = "FTA_TRAIN_MT";

  /** 受电方式：{@code ptg5} / {@code ptg6} 受电弓、{@code shoe} 集电靴、{@code diesel} 内燃；用于手动驾驶的启动流程。 */
  public static final String TAG_TRAIN_POWER = "FTA_TRAIN_POWER";

  /** 最高速度上限（格/秒）：手动驾驶的动力学上限，也是自动控车目标速度的上限（编组方案按车型写入）。 */
  public static final String TAG_TRAIN_MAX_BPS = "FTA_TRAIN_MAX_BPS";

  /**
   * 解析列车配置，优先读取 TrainProperties tags，缺失时回退到配置默认值。
   *
   * <p>出车写入的加减速标签（{@link
   * #isSpawnStamped}）不参与解析，按车种配置取值：写下时两者本就相同，配置重载后则以当下配置为准。车型最高速度标签不是出车写入的，照常读取。
   *
   * <p>巡航速度不在 tags 中维护，运行时使用图默认速度与边限速作为基准。
   */
  public TrainConfig resolve(TrainProperties properties, ConfigManager.ConfigView config) {
    Objects.requireNonNull(config, "config");
    TrainType type = readType(properties).orElse(config.trainConfigSettings().defaultTrainType());
    ConfigManager.TrainTypeSettings defaults = config.trainConfigSettings().forType(type);
    // 车型最高速度不是出车写入的（来自编组方案或用户），出车写过加减速的列车同样要按它封顶。
    OptionalDouble maxSpeed =
        TrainTagHelper.readDoubleTag(properties, TAG_TRAIN_MAX_BPS)
            .filter(value -> value > 0.0)
            .map(OptionalDouble::of)
            .orElse(OptionalDouble.empty());
    if (isSpawnStamped(properties)) {
      return new TrainConfig(type, defaults.accelBps2(), defaults.decelBps2(), maxSpeed);
    }
    double accel =
        TrainTagHelper.readDoubleTag(properties, TAG_TRAIN_ACCEL_BPS2)
            .filter(value -> value > 0.0)
            .orElse(defaults.accelBps2());
    double decel =
        TrainTagHelper.readDoubleTag(properties, TAG_TRAIN_DECEL_BPS2)
            .filter(value -> value > 0.0)
            .orElse(defaults.decelBps2());
    return new TrainConfig(type, accel, decel, maxSpeed);
  }

  /** 加减速标签是否为出车时写入（带 {@link #TAG_TRAIN_CONFIG_SOURCE}{@code =}{@link #SOURCE_SPAWN}）。 */
  public boolean isSpawnStamped(TrainProperties properties) {
    return TrainTagHelper.readTagValue(properties, TAG_TRAIN_CONFIG_SOURCE)
        .filter(SOURCE_SPAWN::equalsIgnoreCase)
        .isPresent();
  }

  /**
   * 列车是否带有用户设定的加减速标签：加速度或减速度标签任一存在、且不是出车写入的。
   *
   * <p>值写错（非正数、不是数字）的标签也算：解析时它会回退为车种配置，但那是用户的标签，不得替用户改写。
   */
  public boolean hasUserMotionTags(TrainProperties properties) {
    if (isSpawnStamped(properties)) {
      return false;
    }
    return TrainTagHelper.readTagValue(properties, TAG_TRAIN_ACCEL_BPS2).isPresent()
        || TrainTagHelper.readTagValue(properties, TAG_TRAIN_DECEL_BPS2).isPresent();
  }

  /** 从列车 tags 解析车种（FTA_TRAIN_TYPE）。 */
  public Optional<TrainType> readType(TrainProperties properties) {
    return TrainTagHelper.readTagValue(properties, TAG_TRAIN_TYPE).flatMap(TrainType::parse);
  }

  /**
   * 将列车配置写回 tags（车种/加减速）。
   *
   * <p>调用方可通过 Optional 覆盖单个字段，未提供的字段保留当前配置值。写入后加减速归用户所有：撤掉出车写入的来源标记，之后出车、折返复用都不再改写。
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
    TrainTagHelper.removeTagKey(properties, TAG_TRAIN_CONFIG_SOURCE);
  }
}
