package org.fetarute.fetaruteTCAddon.drive.hud;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverDoorSide;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 每种车站提示在动作栏、侧边栏（停站中的还有 Boss 栏）都有文案。 */
class DriverStationHintMessagesTest {

  private static YamlConfiguration lang(String localeTag) throws Exception {
    try (InputStream stream =
        DriverStationHintMessagesTest.class
            .getClassLoader()
            .getResourceAsStream("lang/" + localeTag + ".yml")) {
      YamlConfiguration yaml = new YamlConfiguration();
      yaml.loadFromString(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
      return yaml;
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"zh_CN", "en_US"})
  void everyHintHasMessages(String localeTag) throws Exception {
    YamlConfiguration lang = lang(localeTag);
    List<String> keys = new ArrayList<>();
    for (DriverStationHint.Kind kind : DriverStationHint.Kind.values()) {
      List<String> variants = new ArrayList<>();
      if (kind == DriverStationHint.Kind.OPEN_DOORS) {
        for (DriverDoorSide side : DriverDoorSide.values()) {
          variants.add(side.name().toLowerCase(Locale.ROOT));
        }
      } else {
        variants.add("");
      }
      for (String variant : variants) {
        DriverStationHint.Hint hint = new DriverStationHint.Hint(kind, variant, Map.of());
        keys.add(hint.key());
        keys.add(hint.sidebarKey());
        if (hint.atStation()) {
          keys.add("drive.bossbar.station." + hint.key().substring("drive.hud.station.".length()));
        }
      }
    }
    for (String key : keys) {
      assertTrue(lang.isString(key), localeTag + " 缺少 " + key);
    }
  }
}
