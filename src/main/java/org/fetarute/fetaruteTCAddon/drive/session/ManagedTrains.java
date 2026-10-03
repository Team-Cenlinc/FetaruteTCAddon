package org.fetarute.fetaruteTCAddon.drive.session;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainTagHelper;

/**
 * 判断一列车是否由调度系统管理。
 *
 * <p>口径与运行时调度一致：带有交路 UUID 标签，或带有运营商、线路、交路任一编码标签（含旧标签名）都算受管。调度系统初始化到一半的列车也算， 手动驾驶不得接管它们。
 */
final class ManagedTrains {

  private static final String LEGACY_OPERATOR_TAG = "FTA_OPERATOR";
  private static final String LEGACY_LINE_TAG = "FTA_LINE";
  private static final String LEGACY_ROUTE_TAG = "FTA_ROUTE";

  private ManagedTrains() {}

  /** 列车是否带有调度系统写入的身份标签。 */
  static boolean isFtaManaged(TrainProperties properties) {
    if (properties == null) {
      return false;
    }
    if (hasValue(properties, RouteProgressRegistry.TAG_ROUTE_ID)) {
      return true;
    }
    return hasAnyValue(properties, RouteProgressRegistry.TAG_OPERATOR_CODE, LEGACY_OPERATOR_TAG)
        || hasAnyValue(properties, RouteProgressRegistry.TAG_LINE_CODE, LEGACY_LINE_TAG)
        || hasAnyValue(properties, RouteProgressRegistry.TAG_ROUTE_CODE, LEGACY_ROUTE_TAG);
  }

  private static boolean hasAnyValue(TrainProperties properties, String key, String legacyKey) {
    return hasValue(properties, key) || hasValue(properties, legacyKey);
  }

  private static boolean hasValue(TrainProperties properties, String key) {
    Optional<String> value = TrainTagHelper.readTagValue(properties, key);
    return value.isPresent() && !value.get().isBlank();
  }
}
