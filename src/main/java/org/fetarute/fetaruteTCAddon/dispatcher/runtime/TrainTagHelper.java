package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.properties.CartProperties;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
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

  /** 读取 long tag，解析失败时返回 empty。 */
  public static Optional<Long> readLongTag(TrainProperties properties, String key) {
    Optional<String> valueOpt = readTagValue(properties, key);
    if (valueOpt.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(Long.parseLong(valueOpt.get().trim()));
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
  }

  /**
   * 写入/覆盖 tag。
   *
   * <p>写入前会移除已有的同名 key，保证唯一性。每节车厢上该 key 都已经只有这一条、值也相同时不动：TrainCarts 每次增删 tag
   * 都要逐节车厢同步配置里的列表，运行时每个信号周期都会重写列车名等不变的 tag。
   */
  public static void writeTag(TrainProperties properties, String key, String value) {
    if (properties == null || key == null || key.isBlank()) {
      return;
    }
    String normalizedKey = key.trim();
    String normalizedValue = value == null ? "" : value.trim();
    String tag = normalizedKey + "=" + normalizedValue;
    if (everyCartHasOnly(properties, normalizedKey.toLowerCase(Locale.ROOT), tag)) {
      return;
    }
    removeTagKey(properties, normalizedKey);
    properties.addTags(tag);
  }

  /** 删除指定 key 的 tag。 */
  public static void removeTagKey(TrainProperties properties, String key) {
    if (properties == null || key == null || key.isBlank() || !properties.hasTags()) {
      return;
    }
    String target = key.trim().toLowerCase(Locale.ROOT);
    List<String> removals = new ArrayList<>();
    for (String tag : properties.getTags()) {
      if (matchesKey(tag, target)) {
        // TrainCarts 按 tag 原始字符串执行删除；匹配时可以 trim，但删除值必须保留原样。
        removals.add(tag);
      }
    }
    if (!removals.isEmpty()) {
      properties.removeTags(removals.toArray(new String[0]));
    }
  }

  /**
   * 每节车厢上该 key 的 tag 是否都恰好只有 {@code tag} 这一条（按原始字符串比较）。
   *
   * <p>逐节车厢看，不看整列的并集：并集只说明有车厢带着它，后挂上来的车厢可能没有；照常写一遍才能补齐，之后拆分出去的那一截才不会丢标签。 拿不到车厢时照常写。
   */
  private static boolean everyCartHasOnly(
      TrainProperties properties, String lowerCaseKey, String tag) {
    Iterator<CartProperties> carts = properties.iterator();
    if (carts == null || !carts.hasNext()) {
      return false;
    }
    while (carts.hasNext()) {
      CartProperties cart = carts.next();
      if (cart == null || !hasOnly(cart.getTags(), lowerCaseKey, tag)) {
        return false;
      }
    }
    return true;
  }

  private static boolean hasOnly(Collection<String> tags, String lowerCaseKey, String tag) {
    if (tags == null) {
      return false;
    }
    boolean found = false;
    for (String current : tags) {
      if (!matchesKey(current, lowerCaseKey)) {
        continue;
      }
      if (!tag.equals(current)) {
        return false;
      }
      found = true;
    }
    return found;
  }

  /** tag 的 key（去空白、转小写后）是否等于 {@code lowerCaseKey}；没有 {@code =} 的 tag 整条视为 key。 */
  private static boolean matchesKey(String tag, String lowerCaseKey) {
    if (tag == null) {
      return false;
    }
    String trimmed = tag.trim();
    if (trimmed.isEmpty()) {
      return false;
    }
    int idx = trimmed.indexOf('=');
    String currentKey = idx > 0 ? trimmed.substring(0, idx).trim() : trimmed;
    return currentKey.toLowerCase(Locale.ROOT).equals(lowerCaseKey);
  }
}
