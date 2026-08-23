package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 初始化由 spawn pattern 生成列车的 FTA 身份与生命周期标签。
 *
 * <p>TrainCarts pattern 会复制模板列车的 tags，因此新车不能用 {@code addTags} 追加本次状态。这里对插件拥有的 key
 * 执行规范化覆盖，并删除本次未提供的可选值与上一列车遗留的瞬态控车状态。
 */
public final class TrainSpawnTagInitializer {

  /** 新车的实际 Depot 起点仍可用于 route index 0 恢复；驶离或折返 handoff 后写为 false。 */
  public static final String TAG_SPAWN_ORIGIN_PENDING = "FTA_SPAWN_ORIGIN_PENDING";

  /** 已实体化编组必须被物理销毁、不得在重载后恢复运营。 */
  public static final String TAG_MATERIALIZED_ROLLBACK_PENDING =
      "FTA_MATERIALIZED_ROLLBACK_PENDING";

  private static final List<String> LIFECYCLE_TAG_KEYS =
      List.of(
          "FTA_RUN_ID",
          RouteProgressRegistry.TAG_ROUTE_ID,
          RouteProgressRegistry.TAG_ROUTE_CODE,
          RouteProgressRegistry.TAG_LINE_CODE,
          RouteProgressRegistry.TAG_OPERATOR_CODE,
          "FTA_PATTERN",
          "FTA_DEPOT_ID",
          TAG_SPAWN_ORIGIN_PENDING,
          TAG_MATERIALIZED_ROLLBACK_PENDING,
          "FTA_SPAWN_PATTERN",
          "FTA_DEST_CODE",
          "FTA_DEST_NAME",
          "FTA_RUN_AT",
          RouteProgressRegistry.TAG_ROUTE_INDEX,
          RouteProgressRegistry.TAG_ROUTE_UPDATED_AT,
          "FTA_OP_TRIPS",
          "FTA_OP_MAX",
          "FTA_SPAWN_GROUP",
          "FTA_TICKET_ID");

  private static final List<String> TRANSIENT_CONTROL_TAG_KEYS =
      List.of(
          "FTA_LAST_LAUNCH_AT",
          "FTA_LAST_SPEED_CMD_BPS",
          "FTA_LAST_SPEED_CMD_AT",
          "FTA_DOOR_FIRST_STOP_DONE",
          "FTA_HAS_PASSENGERS",
          "FTA_MANUAL_HOLD",
          "FTA_MAINTENANCE_HOLD",
          "FTA_OPERATOR",
          "FTA_LINE",
          "FTA_ROUTE");

  private TrainSpawnTagInitializer() {}

  /**
   * 同时覆盖 TrainCarts 名称与插件 owner tag。
   *
   * @param properties 新生成列车属性
   * @param trainName 本次调度分配的正式列车名
   */
  public static void initializeOwner(TrainProperties properties, String trainName) {
    Objects.requireNonNull(properties, "properties");
    if (trainName == null || trainName.isBlank()) {
      throw new IllegalArgumentException("trainName 不能为空");
    }
    String owner = trainName.trim();
    TrainTagHelper.writeTag(properties, RouteProgressRegistry.TAG_TRAIN_NAME, owner);
    properties.setTrainName(owner);
  }

  /**
   * 用本次 spawn 生命周期替换模板继承状态。
   *
   * <p>只接受插件声明拥有的生命周期 key；缺失或清洗后为空的 key 会被删除。每次 spawn 还会清除旧 route progress、ticket、门/乘客状态、人工/维护
   * Hold、 launch/speed 冷却与 legacy 路由身份，避免模板上一列车的运行事实污染新任务。车型级能力标签（例如人工 priority 与牌子 bypass）不在清理范围内。
   *
   * @param properties 新生成列车属性
   * @param lifecycleTags 本次生命周期标签原始值
   */
  public static void replaceLifecycleTags(
      TrainProperties properties, Map<String, String> lifecycleTags) {
    Objects.requireNonNull(properties, "properties");
    Map<String, String> values = lifecycleTags == null ? Map.of() : lifecycleTags;
    for (String key : LIFECYCLE_TAG_KEYS) {
      String value = sanitizeTagValue(values.get(key));
      if (value.isEmpty()) {
        TrainTagHelper.removeTagKey(properties, key);
      } else {
        TrainTagHelper.writeTag(properties, key, value);
      }
    }
    for (String key : TRANSIENT_CONTROL_TAG_KEYS) {
      TrainTagHelper.removeTagKey(properties, key);
    }
  }

  private static String sanitizeTagValue(String raw) {
    if (raw == null || raw.isBlank()) {
      return "";
    }
    String normalized = raw.trim().replace('=', '-').replace('|', '-');
    return normalized.replaceAll("\\s+", "_");
  }
}
