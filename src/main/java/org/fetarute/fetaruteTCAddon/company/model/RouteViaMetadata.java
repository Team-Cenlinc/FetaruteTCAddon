package org.fetarute.fetaruteTCAddon.company.model;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 交路在 metadata 里显式配置的“经由”站：站台屏在终点下面轮换显示“经由 某站”。
 *
 * <p>键 {@link #KEY}，值为站码列表（按配置顺序）。没有配置时站台屏按换乘线路数、股道数与直通站自行推断，配置了就只用配置的站。 这里只做字符串层面的解析，不核对站码是否在交路上。
 */
public final class RouteViaMetadata {

  /** 经由站码列表。 */
  public static final String KEY = "via";

  private RouteViaMetadata() {}

  /**
   * 读出配置的经由站码。
   *
   * <p>接受字符串列表，也接受一段用逗号、顿号或空白分隔的文本（手改数据库时常见）；去掉空白与重复（不区分大小写），保持原写法与顺序。
   *
   * @return 未配置或格式不对时为空列表
   */
  public static List<String> read(Map<String, Object> metadata) {
    if (metadata == null) {
      return List.of();
    }
    Object raw = metadata.get(KEY);
    if (raw instanceof String text) {
      return parse(text);
    }
    if (raw instanceof Collection<?> values) {
      return distinct(values.stream().map(value -> value == null ? "" : value.toString()).toList());
    }
    return List.of();
  }

  /** 解析命令里写的经由站：逗号、中文逗号、顿号或空白分隔。 */
  public static List<String> parse(String raw) {
    if (raw == null) {
      return List.of();
    }
    return distinct(List.of(raw.split("[,，、\\s]+")));
  }

  private static List<String> distinct(List<String> codes) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String code : codes) {
      String trimmed = code.trim();
      if (!trimmed.isEmpty()) {
        out.putIfAbsent(trimmed.toUpperCase(Locale.ROOT), trimmed);
      }
    }
    return List.copyOf(out.values());
  }
}
