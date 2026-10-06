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
  @DisplayName("单个键与整段都搬；新键已有不同文案时两个都留着并报出旧键；搬空的段删掉")
  void movesKeysAndSections() {
    YamlConfiguration config = new YamlConfiguration();
    config.set("drive.command.breaker.status", "自定义状态");
    config.set("drive.command.breaker.reset", "自定义解除");
    config.set("drive.task.claim.breaker-open", "自定义暂停");
    config.set("drive.task.claim.claimed", "别的键");
    config.set("drive.hud.driver.confirm-signal", "旧文案");
    config.set("drive.hud.driver.acknowledge-signal", "新键上已有的");
    config.set("drive.driver.signal.confirmed", "同一句");
    config.set("drive.driver.signal.acknowledged", "同一句");

    LocaleKeyMoves.Result result = LocaleKeyMoves.apply(config);
    List<String> moved = result.moved();

    assertEquals("自定义状态", config.getString("drive.command.congestion.status"));
    assertEquals("自定义解除", config.getString("drive.command.congestion.reset"));
    assertEquals("自定义暂停", config.getString("drive.task.claim.protection-active"));
    assertEquals("新键上已有的", config.getString("drive.hud.driver.acknowledge-signal"));
    assertFalse(config.contains("drive.command.breaker"), "搬空的段删掉");
    assertNull(config.get("drive.task.claim.breaker-open"));
    assertEquals("旧文案", config.getString("drive.hud.driver.confirm-signal"), "新旧文案不同时旧键不删");
    assertEquals(List.of("drive.hud.driver.confirm-signal"), result.kept());
    assertNull(config.get("drive.driver.signal.confirmed"), "新旧文案相同时旧键直接删掉");
    assertEquals("别的键", config.getString("drive.task.claim.claimed"));
    assertTrue(moved.contains("drive.command.congestion.status"));
    assertFalse(moved.contains("drive.hud.driver.acknowledge-signal"), "新键已有值时不算搬过");
    assertEquals(List.of(), LocaleKeyMoves.apply(config).moved(), "再搬一次没有变化");
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
