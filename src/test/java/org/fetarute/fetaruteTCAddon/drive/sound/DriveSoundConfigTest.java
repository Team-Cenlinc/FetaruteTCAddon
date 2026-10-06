package org.fetarute.fetaruteTCAddon.drive.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("提示音配置")
class DriveSoundConfigTest {

  @Test
  @DisplayName("没有 sounds 段时每种提示音都用内置默认值")
  void defaultsCoverEveryCue() {
    DriveSoundConfig config = DriveSoundConfig.from(null, message -> {});
    for (DriveCue cue : DriveCue.values()) {
      assertEquals(cue.defaultSpec(), config.spec(cue).orElseThrow());
    }
    assertEquals(20, config.hornCooldownTicks());
    assertEquals(60, config.hornHoldTicks());
  }

  @Test
  @DisplayName("读取各项；音效留空即关闭；简写只给音效键；总开关关掉全部")
  void parsesEntries() throws Exception {
    YamlConfiguration yaml = new YamlConfiguration();
    yaml.loadFromString(
        String.join(
            "\n",
            "horn-cooldown-seconds: 5",
            "horn-hold-seconds: 0",
            "horn:",
            "  key: \"fetarute:drive.horn\"",
            "  volume: 2.5",
            "  pitch: 0.9",
            "overspeed:",
            "  key: \"\"",
            "depart: \"minecraft:block.note_block.flute\""));
    DriveSoundConfig config = DriveSoundConfig.from(yaml, message -> {});

    assertEquals(
        new DriveSoundConfig.Spec("fetarute:drive.horn", 2.5f, 0.9f),
        config.spec(DriveCue.HORN).orElseThrow());
    assertTrue(config.spec(DriveCue.OVERSPEED).isEmpty());
    assertEquals(
        "minecraft:block.note_block.flute", config.spec(DriveCue.DEPART).orElseThrow().sound());
    assertEquals(100, config.hornCooldownTicks());
    assertEquals(0, config.hornHoldTicks());

    yaml.set("enabled", false);
    assertFalse(DriveSoundConfig.from(yaml, message -> {}).spec(DriveCue.HORN).isPresent());
  }

  @Test
  @DisplayName("非法音量与音高回退默认值并提示")
  void invalidValuesFallBack() throws Exception {
    YamlConfiguration yaml = new YamlConfiguration();
    yaml.loadFromString(String.join("\n", "depart:", "  volume: -1", "  pitch: 5"));
    List<String> warnings = new ArrayList<>();
    DriveSoundConfig config = DriveSoundConfig.from(yaml, warnings::add);

    assertEquals(DriveCue.DEPART.defaultSpec(), config.spec(DriveCue.DEPART).orElseThrow());
    assertEquals(2, warnings.size());
  }

  @Test
  @DisplayName("改过名的键：新键没写时读旧键，两个都写时以新键为准")
  void readsLegacyKeyWhenNewOneIsMissing() throws Exception {
    YamlConfiguration legacy = new YamlConfiguration();
    legacy.loadFromString("signal-confirmed:\n  volume: 0.2\n");
    assertEquals(
        0.2f,
        DriveSoundConfig.from(legacy, message -> {})
            .spec(DriveCue.SIGNAL_ACKNOWLEDGED)
            .orElseThrow()
            .volume());

    YamlConfiguration both = new YamlConfiguration();
    both.loadFromString("signal-confirmed:\n  volume: 0.2\nsignal-acknowledged:\n  volume: 0.4\n");
    assertEquals(
        0.4f,
        DriveSoundConfig.from(both, message -> {})
            .spec(DriveCue.SIGNAL_ACKNOWLEDGED)
            .orElseThrow()
            .volume());
  }
}
