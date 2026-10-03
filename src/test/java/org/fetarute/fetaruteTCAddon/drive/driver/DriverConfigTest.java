package org.fetarute.fetaruteTCAddon.drive.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DriverConfig 解析")
class DriverConfigTest {

  @Test
  @DisplayName("没有 driver 段时用默认值")
  void missingSectionUsesDefaults() {
    assertEquals(DriverConfig.defaults(), DriverConfig.from(null, message -> {}));
  }

  @Test
  @DisplayName("读取各项，秒换算成 tick")
  void parsesValues() throws Exception {
    YamlConfiguration yaml = new YamlConfiguration();
    yaml.loadFromString(
        String.join(
            "\n",
            "enabled: false",
            "hot-handover: false",
            "overspeed-tolerance-bps: 2.0",
            "restricted-speed-bps: 4.0",
            "directive-stale-seconds: 3",
            "stale-handback-seconds: 12",
            "stop-margin-blocks: 0.5",
            "stop-accurate-blocks: 3",
            "stop-accept-blocks: 8"));
    List<String> warnings = new ArrayList<>();

    DriverConfig config = DriverConfig.from(yaml, warnings::add);

    assertFalse(config.enabled());
    assertFalse(config.hotHandover());
    assertEquals(2.0, config.overspeedToleranceBps(), 1.0e-9);
    assertEquals(4.0, config.restrictedSpeedBps(), 1.0e-9);
    assertEquals(60, config.directiveStaleTicks());
    assertEquals(240, config.staleHandbackTicks());
    assertEquals(0.5, config.stopMarginBlocks(), 1.0e-9);
    assertEquals(3.0, config.stopAccurateBlocks(), 1.0e-9);
    assertEquals(8.0, config.stopAcceptBlocks(), 1.0e-9);
    assertEquals(
        new org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopWindow(3.0, 8.0),
        config.stopWindow());
    assertTrue(warnings.isEmpty(), warnings::toString);
  }

  @Test
  @DisplayName("非法值回退默认值并提示")
  void invalidValuesFallBack() throws Exception {
    YamlConfiguration yaml = new YamlConfiguration();
    yaml.loadFromString(
        String.join(
            "\n",
            "overspeed-tolerance-bps: -1",
            "directive-stale-seconds: 10",
            "stale-handback-seconds: 5"));
    List<String> warnings = new ArrayList<>();

    DriverConfig config = DriverConfig.from(yaml, warnings::add);
    DriverConfig defaults = DriverConfig.defaults();

    assertEquals(defaults.overspeedToleranceBps(), config.overspeedToleranceBps(), 1.0e-9);
    assertEquals(defaults.directiveStaleTicks(), config.directiveStaleTicks());
    assertEquals(defaults.staleHandbackTicks(), config.staleHandbackTicks());
    assertEquals(2, warnings.size(), warnings::toString);
  }
}
