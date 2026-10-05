package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.Optional;
import java.util.UUID;

/**
 * 列车标签 {@value #TAG_DRIVER}：这列车由哪名玩家驾驶。
 *
 * <p>运行时以驾驶注册表为准，标签只用于三件事：调度层在拿不到注册表的地方内联判断（死锁、卡死清理的豁免）、服务器重启后清理残留，以及跨服移交时随车携带。
 * 标签存在但注册表里没有绑定时一律按自动运行处理，崩服后不会把车冻住。
 */
public final class DriverControlTags {

  /** 驾驶员的玩家 UUID。 */
  public static final String TAG_DRIVER = "FTA_DRIVER";

  private DriverControlTags() {}

  /** 列车上是否有驾驶员标签。 */
  public static boolean present(TrainProperties properties) {
    return properties != null && TrainTagHelper.readTagValue(properties, TAG_DRIVER).isPresent();
  }

  /** 标签记录的驾驶员；没有或格式不对时为空。 */
  public static Optional<UUID> driver(TrainProperties properties) {
    if (properties == null) {
      return Optional.empty();
    }
    return TrainTagHelper.readTagValue(properties, TAG_DRIVER)
        .flatMap(
            raw -> {
              try {
                return Optional.of(UUID.fromString(raw.trim()));
              } catch (IllegalArgumentException ex) {
                return Optional.empty();
              }
            });
  }

  /** 写入驾驶员标签。 */
  public static void write(TrainProperties properties, UUID playerId) {
    if (properties != null && playerId != null) {
      TrainTagHelper.writeTag(properties, TAG_DRIVER, playerId.toString());
    }
  }

  /** 清掉驾驶员标签。 */
  public static void clear(TrainProperties properties) {
    if (properties != null) {
      TrainTagHelper.removeTagKey(properties, TAG_DRIVER);
    }
  }
}
