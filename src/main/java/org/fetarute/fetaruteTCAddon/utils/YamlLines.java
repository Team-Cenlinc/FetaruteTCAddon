package org.fetarute.fetaruteTCAddon.utils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按行改 YAML 配置时共用的识别规则：不经 YAML 往返，免得重写整个文件、丢掉注释。
 *
 * <p>只认块式映射（每个键一行、靠缩进分层），流式映射（{@code {a: 1}}）不认。
 */
public final class YamlLines {

  /** 键行：缩进、可选的引号、键名、同一引号，冒号后可带值或注释（行内映射也认）。 */
  public static final Pattern KEY = Pattern.compile("^(\\s*)([\"']?)([A-Za-z0-9_-]+)\\2:(\\s.*)?$");

  private YamlLines() {}

  /** 这一行是否已离开缩进为 {@code indent} 的块：非空、非注释且缩进不超过它。 */
  public static boolean endsBlock(String line, int indent) {
    String trimmed = line.trim();
    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
      return false;
    }
    int leading = line.length() - line.stripLeading().length();
    return leading <= indent;
  }

  /** 第 {@code start} 行（缩进为 {@code indent} 的键）的块到哪一行为止（不含）。 */
  public static int blockEnd(List<String> lines, int start, int indent) {
    int end = start + 1;
    while (end < lines.size() && !endsBlock(lines.get(end), indent)) {
      end++;
    }
    return end;
  }

  /**
   * 块内第一层的键。
   *
   * @param indent 第一层键的缩进（块内第一个键行的缩进）；块内没有键时为 -1
   * @param keys 第一层的键名
   */
  public record Children(int indent, Set<String> keys) {
    public Children {
      keys = Set.copyOf(keys);
    }
  }

  /** 第 {@code from} 行（含）到第 {@code to} 行（不含）之间第一层的键。 */
  public static Children children(List<String> lines, int from, int to) {
    int indent = -1;
    Set<String> keys = new HashSet<>();
    for (int i = from; i < to; i++) {
      Matcher key = KEY.matcher(lines.get(i));
      if (!key.matches()) {
        continue;
      }
      if (indent < 0) {
        indent = key.group(1).length();
      }
      if (key.group(1).length() == indent) {
        keys.add(key.group(3));
      }
    }
    return new Children(indent, keys);
  }

  /** 把已匹配 {@link #KEY} 的键行改成新键名，缩进、引号、值与注释保留。 */
  public static String renamed(Matcher key, String name) {
    return key.group(1)
        + key.group(2)
        + name
        + key.group(2)
        + ":"
        + (key.group(4) == null ? "" : key.group(4));
  }
}
