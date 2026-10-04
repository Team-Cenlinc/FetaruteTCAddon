package org.fetarute.fetaruteTCAddon.drive.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("驾驶记录保留期与提前接车配置")
class DriverConfigRetentionTest {

  @Test
  @DisplayName("默认保留 30 天、提前 300 秒接车")
  void defaults() {
    assertEquals(30, DriverConfig.defaults().recordRetentionDays());
    assertEquals(300, DriverConfig.defaults().pickupAdvanceSeconds());
  }

  @Test
  @DisplayName("读取配置；0 表示一直保留 / 不提前")
  void parses() throws Exception {
    YamlConfiguration yaml = new YamlConfiguration();
    yaml.loadFromString("record-retention-days: 7\npickup-advance-seconds: 0");
    DriverConfig config = DriverConfig.from(yaml, message -> {});

    assertEquals(7, config.recordRetentionDays());
    assertEquals(0, config.pickupAdvanceSeconds());

    yaml.loadFromString("record-retention-days: 0");
    assertEquals(0, DriverConfig.from(yaml, message -> {}).recordRetentionDays());
  }
}
