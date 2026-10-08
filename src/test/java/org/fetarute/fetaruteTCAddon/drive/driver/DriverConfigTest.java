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
            "stop-accept-blocks: 8",
            "pickup-wait-seconds: 120",
            "pickup-teleport: false"));
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
    assertEquals(120, config.pickupWaitSeconds());
    assertFalse(config.pickupTeleport());
    assertEquals(90, DriverConfig.defaults().pickupWaitSeconds());
    assertTrue(DriverConfig.defaults().pickupTeleport());
    assertEquals(
        new org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopWindow(3.0, 8.0, 12.0),
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

  @Test
  @DisplayName("接车等待不超过上限（车库扣车的发车门控会先失效）")
  void pickupWaitIsCapped() throws Exception {
    YamlConfiguration yaml = new YamlConfiguration();
    yaml.loadFromString("pickup-wait-seconds: 600");
    List<String> warnings = new ArrayList<>();

    DriverConfig config = DriverConfig.from(yaml, warnings::add);

    assertEquals(DriverConfig.MAX_PICKUP_WAIT_SECONDS, config.pickupWaitSeconds());
    assertEquals(1, warnings.size(), warnings::toString);
  }

  @Test
  @DisplayName("终点站越过停车点的上限默认 3 格，不能大于可开门范围")
  void terminalOverrunIsCappedByTheAcceptWindow() throws Exception {
    assertEquals(3.0, DriverConfig.defaults().terminalOverrunBlocks(), 1.0e-9);

    YamlConfiguration yaml = new YamlConfiguration();
    yaml.loadFromString(String.join("\n", "stop-accept-blocks: 5", "terminal-overrun-blocks: 8"));
    List<String> warnings = new ArrayList<>();
    DriverConfig config = DriverConfig.from(yaml, warnings::add);
    assertEquals(5.0, config.terminalOverrunBlocks(), 1.0e-9);
    assertTrue(warnings.stream().anyMatch(w -> w.contains("terminal-overrun-blocks")));

    yaml.loadFromString("terminal-overrun-blocks: 2");
    assertEquals(2.0, DriverConfig.from(yaml, message -> {}).terminalOverrunBlocks(), 1.0e-9);
  }

  @Test
  @DisplayName("越站处置默认同一趟 2 次交还，可改、可写 0 关闭，负数回退默认值")
  void skipStationHandback() throws Exception {
    assertEquals(2, DriverConfig.defaults().skipStationHandback());

    YamlConfiguration yaml = new YamlConfiguration();
    yaml.loadFromString("skip-station-handback: 0");
    assertEquals(0, DriverConfig.from(yaml, message -> {}).skipStationHandback());

    yaml.loadFromString("skip-station-handback: 3");
    assertEquals(3, DriverConfig.from(yaml, message -> {}).skipStationHandback());

    yaml.loadFromString("skip-station-handback: -1");
    List<String> warnings = new ArrayList<>();
    assertEquals(2, DriverConfig.from(yaml, warnings::add).skipStationHandback());
    assertTrue(warnings.stream().anyMatch(w -> w.contains("skip-station-handback")));
  }
}
