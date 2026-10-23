package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.properties.TrainPropertiesStore;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableDutyStore;

/**
 * 将交路恢复标记存进 TrainCarts 车辆标签，随实体和离线编组一起保存。
 *
 * <p>属性库可能保留没有实体的模板或旧属性，只有有效 group 或 OfflineGroup 才能占据交路。 本类只读取已存在的车辆，不创建属性、不加载区块、不主动唤醒离线车辆。
 */
public final class TrainCartsTimetableDutyStore implements TimetableDutyStore {
  public static final String TAG_DUTY = "FTA_TIMETABLE_DUTY";

  @Override
  public Map<String, String> read() {
    Map<String, String> tokens = new HashMap<>();
    for (TrainProperties properties : TrainPropertiesStore.getAll()) {
      if (exists(properties)
          && !TrainTagHelper.readTagValue(
                  properties, TrainSpawnTagInitializer.TAG_MATERIALIZED_ROLLBACK_PENDING)
              .isPresent()) {
        TrainTagHelper.readTagValue(properties, TAG_DUTY)
            .ifPresent(token -> tokens.put(properties.getTrainName(), token));
      }
    }
    return Map.copyOf(tokens);
  }

  /** 账本每变一次就写一次：按车名直接取属性（名字大小写不一致时退回宽松查找），不扫全部属性，查不到不新建。 */
  @Override
  public void write(String trainName, Optional<String> token) {
    if (trainName == null || trainName.isBlank()) {
      return;
    }
    TrainProperties properties = TrainPropertiesStore.get(trainName);
    if (properties == null) {
      properties = TrainPropertiesStore.getRelaxed(trainName);
    }
    if (properties == null) {
      return;
    }
    TrainProperties target = properties;
    token.ifPresentOrElse(
        value -> TrainTagHelper.writeTag(target, TAG_DUTY, value),
        () -> TrainTagHelper.removeTagKey(target, TAG_DUTY));
  }

  private static boolean exists(TrainProperties properties) {
    return properties != null
        && ((properties.getHolder() != null && properties.getHolder().isValid())
            || properties.getTrainCarts().getOfflineGroups().contains(properties.getTrainName()));
  }
}
