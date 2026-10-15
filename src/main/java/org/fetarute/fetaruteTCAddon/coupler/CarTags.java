package org.fetarute.fetaruteTCAddon.coupler;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.properties.CartProperties;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.consist.ConsistKey;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainTagHelper;

/** 车厢级标签（{@code 键=值}，记在这一节车上、随车厢走）：车钩状态与来源编组。列车级标签由 {@link TrainTagHelper} 写到每一节车上，这里只动单节。 */
public final class CarTags {

  /** 车厢级的键：联挂前后要逐节保存与恢复（TrainCarts 联挂时会把一方的列车属性整份装进另一方）。 */
  static final Set<String> CAR_KEYS =
      Set.of(CouplerState.TAG_FRONT, CouplerState.TAG_REAR, CarConsist.TAG_CAR_CONSIST);

  private CarTags() {}

  /** 读这节车的一个标签。 */
  public static Optional<String> read(MinecartMember<?> member, String key) {
    CartProperties properties = member == null ? null : member.getProperties();
    if (properties == null || !properties.hasTags()) {
      return Optional.empty();
    }
    String prefix = key + "=";
    for (String tag : properties.getTags()) {
      if (tag != null && tag.regionMatches(true, 0, prefix, 0, prefix.length())) {
        return Optional.of(tag.substring(prefix.length()));
      }
    }
    return Optional.empty();
  }

  /** 写这节车的一个标签（先删同键的旧值）。 */
  public static void write(MinecartMember<?> member, String key, String value) {
    remove(member, key);
    CartProperties properties = member == null ? null : member.getProperties();
    if (properties != null) {
      properties.addTags(key + "=" + value);
    }
  }

  /** 删这节车的一个标签。 */
  public static void remove(MinecartMember<?> member, String key) {
    CartProperties properties = member == null ? null : member.getProperties();
    if (properties == null || !properties.hasTags()) {
      return;
    }
    String prefix = key + "=";
    List<String> stale = new ArrayList<>();
    for (String tag : properties.getTags()) {
      if (tag != null
          && (tag.equalsIgnoreCase(key)
              || tag.regionMatches(true, 0, prefix, 0, prefix.length()))) {
        stale.add(tag);
      }
    }
    if (!stale.isEmpty()) {
      properties.removeTags(stale.toArray(String[]::new));
    }
  }

  /** 逐节保存车厢级标签（按车厢实体 ID）。 */
  static Map<UUID, Map<String, String>> snapshot(MinecartGroup group) {
    Map<UUID, Map<String, String>> out = new HashMap<>();
    for (MinecartMember<?> member : group) {
      UUID id = entityId(member);
      if (id == null) {
        continue;
      }
      Map<String, String> values = new HashMap<>();
      for (String key : CAR_KEYS) {
        read(member, key).ifPresent(value -> values.put(key, value));
      }
      out.put(id, values);
    }
    return out;
  }

  /** 按保存的值逐节恢复车厢级标签（没保存的车厢不动）。 */
  static void restore(MinecartGroup group, Map<UUID, Map<String, String>> saved) {
    for (MinecartMember<?> member : group) {
      Map<String, String> values = saved.get(entityId(member));
      if (values == null) {
        continue;
      }
      for (String key : CAR_KEYS) {
        String value = values.get(key);
        if (value == null) {
          remove(member, key);
        } else {
          write(member, key, value);
        }
      }
    }
  }

  /** 给还没有来源的车厢打上来源编组：这些车厢算作一个来源编组（编组键取列车当前的，没有时为 {@code ?}）。 */
  static void stampOrigins(MinecartGroup group) {
    List<MinecartMember<?>> unstamped = new ArrayList<>();
    for (MinecartMember<?> member : group) {
      if (read(member, CarConsist.TAG_CAR_CONSIST).flatMap(CarConsist.Origin::parse).isEmpty()) {
        unstamped.add(member);
      }
    }
    if (unstamped.isEmpty()) {
      return;
    }
    TrainProperties properties = group.getProperties();
    String pattern =
        TrainTagHelper.readTagValue(properties, ConsistKey.TRAIN_TAG)
            .flatMap(ConsistKey::tidy)
            .orElse("?");
    CarConsist.Origin origin =
        new CarConsist.Origin(pattern, UUID.randomUUID().toString(), unstamped.size());
    for (MinecartMember<?> member : unstamped) {
      write(member, CarConsist.TAG_CAR_CONSIST, origin.format());
    }
  }

  /** 按各节车的来源重写列车的编组键与“不完整编组”标记；有车没有来源时不改。 */
  static void rewriteConsist(MinecartGroup group) {
    List<Optional<CarConsist.Origin>> cars = new ArrayList<>();
    for (MinecartMember<?> member : group) {
      cars.add(read(member, CarConsist.TAG_CAR_CONSIST).flatMap(CarConsist.Origin::parse));
    }
    CarConsist.derive(cars)
        .ifPresent(
            derived -> {
              TrainProperties properties = group.getProperties();
              TrainTagHelper.writeTag(properties, ConsistKey.TRAIN_TAG, derived.pattern());
              if (derived.partial()) {
                TrainTagHelper.writeTag(properties, CarConsist.TAG_CONSIST_PARTIAL, "true");
              } else {
                TrainTagHelper.removeTagKey(properties, CarConsist.TAG_CONSIST_PARTIAL);
              }
            });
  }

  static UUID entityId(MinecartMember<?> member) {
    try {
      return member == null || member.getEntity() == null
          ? null
          : member.getEntity().getEntity().getUniqueId();
    } catch (RuntimeException ex) {
      return null;
    }
  }
}
