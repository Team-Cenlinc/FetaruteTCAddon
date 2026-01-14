package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;

/**
 * TrainProperties tag 读取与写入工具，避免重复解析逻辑。
 *
 * <p>约定 tag 采用 {@code key=value} 形式，忽略大小写匹配 key；空值会被视为缺失。
 */
public final class TrainTagHelper {

  private TrainTagHelper() {}

  /**
   * 读取指定 key 的 tag 值。
   *
   * <p>若 tag 缺失或值为空则返回 empty。
   */
  public static Optional<String> readTagValue(TrainProperties properties, String key) {
    if (properties == null || key == null || key.isBlank() || !properties.hasTags()) {
      return Optional.empty();
    }
    Collection<String> tags = properties.getTags();
    if (tags == null || tags.isEmpty()) {
      return Optional.empty();
    }
    for (String tag : tags) {
      if (tag == null) {
        continue;
      }
      String trimmed = tag.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      int idx = trimmed.indexOf('=');
      if (idx <= 0) {
        continue;
      }
      String currentKey = trimmed.substring(0, idx).trim();
      if (!key.equalsIgnoreCase(currentKey)) {
        continue;
      }
      String value = trimmed.substring(idx + 1).trim();
      if (!value.isEmpty()) {
        return Optional.of(value);
      }
    }
    return Optional.empty();
  }

  /** 读取整数 tag，解析失败时返回 empty。 */
  public static Optional<Integer> readIntTag(TrainProperties properties, String key) {
    Optional<String> valueOpt = readTagValue(properties, key);
    if (valueOpt.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(Integer.parseInt(valueOpt.get().trim()));
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
  }

  /** 读取 double tag，解析失败时返回 empty。 */
  public static Optional<Double> readDoubleTag(TrainProperties properties, String key) {
    Optional<String> valueOpt = readTagValue(properties, key);
    if (valueOpt.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(Double.parseDouble(valueOpt.get().trim()));
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
  }

  /**
   * 写入/覆盖 tag。
   *
   * <p>写入前会移除已有的同名 key，保证唯一性。
   */
  public static void writeTag(TrainProperties properties, String key, String value) {
    if (properties == null || key == null || key.isBlank()) {
      return;
    }
    String normalizedKey = key.trim();
    String normalizedValue = value == null ? "" : value.trim();
    removeTagKey(properties, normalizedKey);
    properties.addTags(normalizedKey + "=" + normalizedValue);
  }

  /** 删除指定 key 的 tag。 */
  public static void removeTagKey(TrainProperties properties, String key) {
    if (properties == null || key == null || key.isBlank() || !properties.hasTags()) {
      return;
    }
    String target = key.trim().toLowerCase(Locale.ROOT);
    for (String tag : properties.getTags()) {
      if (tag == null) {
        continue;
      }
      String trimmed = tag.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      int idx = trimmed.indexOf('=');
      String currentKey = idx > 0 ? trimmed.substring(0, idx).trim() : trimmed;
      if (currentKey.toLowerCase(Locale.ROOT).equals(target)) {
        properties.removeTags(trimmed);
        return;
      }
    }
  }
}
