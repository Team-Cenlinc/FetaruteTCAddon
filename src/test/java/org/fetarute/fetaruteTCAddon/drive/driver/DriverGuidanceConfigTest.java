package org.fetarute.fetaruteTCAddon.drive.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("行车引导配置")
class DriverGuidanceConfigTest {

  @Test
  @DisplayName("没有 guidance 段时用默认值；driver 段默认值里也带着它")
  void defaults() {
    assertEquals(DriverGuidanceConfig.defaults(), DriverGuidanceConfig.from(null, message -> {}));
    assertEquals(DriverGuidanceConfig.defaults(), DriverConfig.defaults().guidance());
  }

  @Test
  @DisplayName("读取各项")
  void parses() throws Exception {
    YamlConfiguration yaml = new YamlConfiguration();
    yaml.loadFromString(
        String.join(
            "\n",
            "guidance:",
            "  boss-bar: false",
            "  range-blocks: 600",
            "  advice-brake-fraction: 0.9",
            "  brake-advice-tolerance-bps: 0.5"));
    DriverGuidanceConfig config = DriverConfig.from(yaml, message -> {}).guidance();

    assertFalse(config.bossBar());
    assertEquals(600.0, config.rangeBlocks(), 1.0e-9);
    assertEquals(0.9, config.adviceBrakeFraction(), 1.0e-9);
    assertEquals(0.5, config.brakeAdviceToleranceBps(), 1.0e-9);
  }

  @Test
  @DisplayName("非法项回退默认值并提示")
  void invalid() throws Exception {
    YamlConfiguration yaml = new YamlConfiguration();
    yaml.loadFromString(
        String.join(
            "\n",
            "guidance:",
            "  range-blocks: 0",
            "  advice-brake-fraction: 1.5",
            "  brake-advice-tolerance-bps: -1"));
    List<String> warnings = new ArrayList<>();
    DriverGuidanceConfig config = DriverGuidanceConfig.from(yaml, warnings::add);

    assertEquals(DriverGuidanceConfig.defaults(), config);
    assertEquals(3, warnings.size());
  }
}
