package org.fetarute.fetaruteTCAddon.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("改过名的语言键：旧键文案搬到新键")
class LocaleKeyMovesTest {

  @Test
  @DisplayName("单个键与整段都搬；新键已有值时保留新键；搬空的段删掉")
  void movesKeysAndSections() {
    YamlConfiguration config = new YamlConfiguration();
    config.set("drive.command.breaker.status", "自定义状态");
    config.set("drive.command.breaker.reset", "自定义解除");
    config.set("drive.task.claim.breaker-open", "自定义暂停");
    config.set("drive.task.claim.claimed", "别的键");
    config.set("drive.hud.driver.confirm-signal", "旧文案");
    config.set("drive.hud.driver.acknowledge-signal", "新键上已有的");

    List<String> moved = LocaleKeyMoves.apply(config);

    assertEquals("自定义状态", config.getString("drive.command.congestion.status"));
    assertEquals("自定义解除", config.getString("drive.command.congestion.reset"));
    assertEquals("自定义暂停", config.getString("drive.task.claim.protection-active"));
    assertEquals("新键上已有的", config.getString("drive.hud.driver.acknowledge-signal"));
    assertFalse(config.contains("drive.command.breaker"), "搬空的段删掉");
    assertNull(config.get("drive.task.claim.breaker-open"));
    assertNull(config.get("drive.hud.driver.confirm-signal"));
    assertEquals("别的键", config.getString("drive.task.claim.claimed"));
    assertTrue(moved.contains("drive.command.congestion.status"));
    assertFalse(moved.contains("drive.hud.driver.acknowledge-signal"), "新键已有值时不算搬过");
    assertEquals(List.of(), LocaleKeyMoves.apply(config), "再搬一次没有变化");
  }

  @Test
  @DisplayName("新键都在内置语言文件里，旧键都已不在")
  void targetsExistInBundledLocales() {
    for (String locale : List.of("zh_CN", "en_US")) {
      YamlConfiguration bundled = bundled(locale);
      for (Map.Entry<String, String> entry : LocaleKeyMoves.MOVED.entrySet()) {
        String from = trim(entry.getKey());
        String to = trim(entry.getValue());
        assertTrue(bundled.contains(to), locale + " 缺少新键 " + to);
        assertFalse(bundled.contains(from), locale + " 仍有旧键 " + from);
      }
    }
  }

  private static String trim(String key) {
    return key.endsWith(".") ? key.substring(0, key.length() - 1) : key;
  }

  private static YamlConfiguration bundled(String locale) {
    return YamlConfiguration.loadConfiguration(
        new InputStreamReader(
            LocaleKeyMovesTest.class
                .getClassLoader()
                .getResourceAsStream("lang/" + locale + ".yml"),
            StandardCharsets.UTF_8));
  }
}
