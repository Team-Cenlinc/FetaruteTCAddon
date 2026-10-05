package org.fetarute.fetaruteTCAddon.drive.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("折返换端的时间预留与配置")
class CabChangeConfigTest {

  @Test
  @DisplayName("时间预留 = 基础余量 + 车身长度 ÷ 步行速度，向上取整")
  void reserveGrowsWithTrainLength() {
    CabChangeConfig config = new CabChangeConfig(15.0, 4.0, 30.0);

    assertEquals(15L, config.reserveSeconds(0.0));
    assertEquals(24L, config.reserveSeconds(34.0), "34 格 ÷ 4 = 8.5 秒，向上取整");
    assertEquals(40L, config.reserveSeconds(100.0));
    assertEquals(15L, config.reserveSeconds(-5.0), "非法长度按 0");
    assertEquals(15L, config.reserveSeconds(Double.NaN));
  }

  @Test
  @DisplayName("制动试验时间按整秒向上取整")
  void brakeTestSeconds() {
    assertEquals(13L, new CabChangeConfig(15.0, 4.0, 12.2).brakeTestWholeSeconds());
  }

  @Test
  @DisplayName("没有 cab-change 段时用默认值")
  void missingSection() throws Exception {
    YamlConfiguration yaml = new YamlConfiguration();
    yaml.loadFromString("enabled: true");

    assertEquals(CabChangeConfig.defaults(), CabChangeConfig.from(yaml, message -> {}));
    assertEquals(CabChangeConfig.defaults(), CabChangeConfig.from(null, message -> {}));
  }

  @Test
  @DisplayName("读取各项；非法值回退默认值并提示")
  void parsesAndFallsBack() throws Exception {
    YamlConfiguration yaml = new YamlConfiguration();
    yaml.loadFromString(
        String.join(
            "\n",
            "cab-change:",
            "  base-seconds: 20",
            "  walk-speed-bps: 0",
            "  brake-test-seconds: -3"));
    List<String> warnings = new ArrayList<>();

    CabChangeConfig config = CabChangeConfig.from(yaml, warnings::add);

    assertEquals(20.0, config.baseSeconds(), 1.0e-9);
    assertEquals(CabChangeConfig.defaults().walkSpeedBps(), config.walkSpeedBps(), 1.0e-9);
    assertEquals(CabChangeConfig.defaults().brakeTestSeconds(), config.brakeTestSeconds(), 1.0e-9);
    assertEquals(2, warnings.size(), warnings::toString);
  }

  @Test
  @DisplayName("驾驶座名单：转小写去重，空列表表示不认标记，写成非列表时用默认名单")
  void cabSeatNames() throws Exception {
    YamlConfiguration yaml = new YamlConfiguration();
    yaml.loadFromString("cab-seat-names: [' Driver ', CAB, driver, '']");
    List<String> warnings = new ArrayList<>();

    assertEquals(List.of("driver", "cab"), DriverConfig.from(yaml, warnings::add).cabSeatNames());
    assertTrue(warnings.isEmpty(), warnings::toString);

    YamlConfiguration empty = new YamlConfiguration();
    empty.loadFromString("cab-seat-names: []");
    assertTrue(DriverConfig.from(empty, warnings::add).cabSeatNames().isEmpty());

    YamlConfiguration scalar = new YamlConfiguration();
    scalar.loadFromString("cab-seat-names: driver");
    assertEquals(
        DriverConfig.defaults().cabSeatNames(),
        DriverConfig.from(scalar, warnings::add).cabSeatNames());
    assertFalse(warnings.isEmpty());
  }

  @Test
  @DisplayName("默认名单含中英文常用名")
  void defaultNames() {
    assertTrue(DriverConfig.defaults().cabSeatNames().containsAll(List.of("driver", "cab", "驾驶")));
    assertEquals(CabChangeConfig.defaults(), DriverConfig.defaults().cabChange());
  }
}
