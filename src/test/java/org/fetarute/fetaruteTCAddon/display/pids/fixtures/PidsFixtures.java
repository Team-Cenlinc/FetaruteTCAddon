package org.fetarute.fetaruteTCAddon.display.pids.fixtures;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
    String path = "pids/layouts/" + id + ".yml";
    try (InputStream stream = PidsFixtures.class.getClassLoader().getResourceAsStream(path)) {
      if (stream == null) {
        throw new IllegalStateException("缺少内置布局 " + path);
      }
      YamlConfiguration yaml = new YamlConfiguration();
      yaml.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
      PidsLayoutParser.Result result = PidsLayoutParser.parse(id, yaml);
      return result
          .layout()
          .orElseThrow(() -> new IllegalStateException(id + " 有问题: " + result.problems()));
    } catch (IOException | InvalidConfigurationException ex) {
      throw new IllegalStateException("读取内置布局失败 " + path, ex);
    }
  }
}
