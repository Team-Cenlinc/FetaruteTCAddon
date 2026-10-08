package org.fetarute.fetaruteTCAddon.drive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("drive.yml 改过名的键")
class DriveConfigKeyRenamesTest {

  private static List<String> lines(String text) {
    return text.lines().toList();
  }

  @Test
  @DisplayName("只改所属段下一层的旧键，值与注释保留；别的段同名键不动")
  void renamesKeysInTheirSectionOnly() {
    List<String> migrated =
        DriveConfigKeyRenames.migrate(
            lines(
                """
                breaker-held-trains: 1
                driver:
                  # 拥堵保护
                  breaker-held-trains: 9   # 列
                  breaker-held-seconds: 45
                  cab-change:
                    breaker-held-trains: 2
                sounds:
                  signal-confirmed:
                    volume: 0.3
                  "breaker-held-trains": x
                """));

    assertEquals(
        lines(
            """
            breaker-held-trains: 1
            driver:
              # 拥堵保护
              protection-held-trains: 9   # 列
              protection-held-seconds: 45
              cab-change:
                breaker-held-trains: 2
            sounds:
              signal-acknowledged:
                volume: 0.3
              "breaker-held-trains": x
            """),
        migrated);
  }

  @Test
  @DisplayName("新键已写时旧键保持原样，不改成重名；再迁移一次不变")
  void keepsOldKeyWhenNewOneExists() {
    List<String> both =
        lines(
            """
            driver:
              protection-held-trains: 6
              breaker-held-trains: 9
            """);
    assertEquals(both, DriveConfigKeyRenames.migrate(both));

    List<String> once =
        DriveConfigKeyRenames.migrate(lines("driver:\n  breaker-cooldown-minutes: 20\n"));
    assertEquals(List.of("driver:", "  protection-cooldown-minutes: 20"), once);
    assertEquals(once, DriveConfigKeyRenames.migrate(once));
  }

  @Test
  @DisplayName("报出改了哪些键，只报真改了的")
  void reportsRenamedKeys() {
    DriveConfigKeyRenames.Result result =
        DriveConfigKeyRenames.rename(lines("sounds:\n  signal-confirmed:\n    volume: 0.3\n"));
    assertEquals(List.of("sounds.signal-confirmed → signal-acknowledged"), result.renamed());
  }

  @Test
  @DisplayName("按行改不成的旧键（流式写法）：补全加上新键后，读配置时沿用旧键的值")
  void carriesOverValuesTheLineMigrationMissed() throws Exception {
    YamlConfiguration before = new YamlConfiguration();
    before.loadFromString(
        "driver: {breaker-held-trains: 9, protection-held-seconds: 30}\n"
            + "sounds: {signal-confirmed: {key: \"a:b\", volume: 0.3}}\n");
    assertEquals(
        List.of("driver.breaker-held-trains", "sounds.signal-confirmed").stream().sorted().toList(),
        DriveConfigKeyRenames.unmigrated(before).keySet().stream().sorted().toList(),
        "新键已写的旧键不算");

    YamlConfiguration filled = new YamlConfiguration();
    filled.set("driver.protection-held-trains", 5);
    filled.set("sounds.signal-acknowledged.key", "minecraft:ui.button.click");
    filled.set("sounds.signal-acknowledged.pitch", 1.2);
    DriveConfigKeyRenames.carryOver(before, DriveConfigKeyRenames.unmigrated(before), filled);

    assertEquals(9, filled.getInt("driver.protection-held-trains"));
    assertEquals("a:b", filled.getString("sounds.signal-acknowledged.key"));
    assertEquals(0.3, filled.getDouble("sounds.signal-acknowledged.volume"));
    assertFalse(filled.contains("sounds.signal-acknowledged.pitch"), "整段换成旧键的值，不与模板默认值混在一起");
  }
}
