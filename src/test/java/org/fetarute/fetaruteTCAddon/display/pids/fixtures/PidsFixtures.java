package org.fetarute.fetaruteTCAddon.display.pids.fixtures;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.UnaryOperator;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayoutParser;

/** 站台屏测试夹具。 */
public final class PidsFixtures {

  private PidsFixtures() {}

  /**
   * 读取并解析内置布局。
   *
   * @throws IllegalStateException 布局有问题（内置布局必须合法）
   */
  public static PidsLayout builtInLayout(String id) {
    return parse(id, builtInText(id));
  }

  /**
   * 读取内置布局、按 {@code edit} 改写文本后解析，用来模拟用户在内置布局上改出的自定义布局。
   *
   * @throws IllegalStateException 改写后的布局有问题，或改写没有改动任何内容
   */
  public static PidsLayout builtInLayout(String id, UnaryOperator<String> edit) {
    String text = builtInText(id);
    String edited = edit.apply(text);
    if (edited.equals(text)) {
      throw new IllegalStateException("改写没有改动内置布局 " + id);
    }
    return parse(id, edited);
  }

  private static String builtInText(String id) {
    String path = "pids/layouts/" + id + ".yml";
    try (InputStream stream = PidsFixtures.class.getClassLoader().getResourceAsStream(path)) {
      if (stream == null) {
        throw new IllegalStateException("缺少内置布局 " + path);
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new IllegalStateException("读取内置布局失败 " + path, ex);
    }
  }

  private static PidsLayout parse(String id, String text) {
    YamlConfiguration yaml = new YamlConfiguration();
    try {
      yaml.loadFromString(text);
    } catch (InvalidConfigurationException ex) {
      throw new IllegalStateException("布局 " + id + " 不是合法的 YAML", ex);
    }
    PidsLayoutParser.Result result = PidsLayoutParser.parse(id, yaml);
    return result
        .layout()
        .orElseThrow(() -> new IllegalStateException(id + " 有问题: " + result.problems()));
  }
}
