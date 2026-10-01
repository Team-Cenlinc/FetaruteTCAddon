package org.fetarute.fetaruteTCAddon.display.pids;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.fetarute.fetaruteTCAddon.display.pids.PidsSettings.AppearanceMode;
import org.fetarute.fetaruteTCAddon.display.pids.PidsSettings.AppearanceSettings;
import org.junit.jupiter.api.Test;

class PidsSettingsTest {

  @Test
  // 内置模板解析结果必须与 defaults() 一致，防止模板与代码默认值各改各的。
  void bundledTemplateMatchesDefaults() throws Exception {
    List<String> warnings = new ArrayList<>();
    PidsSettings parsed = PidsSettings.parse(loadTemplate(), loggerCollecting(warnings));

    assertEquals(PidsSettings.defaults(), parsed);
    assertTrue(warnings.isEmpty(), () -> "内置模板不应产生警告: " + warnings);
    assertEquals(PidsSettings.EXPECTED_CONFIG_VERSION, parsed.configVersion());
  }

  @Test
  // 空配置整体回退为默认值且不产生警告：缺键不是错误，只是用默认。
  void emptyConfigFallsBackToDefaultsWithoutWarnings() {
    List<String> warnings = new ArrayList<>();

    PidsSettings parsed = PidsSettings.parse(new YamlConfiguration(), loggerCollecting(warnings));

    assertEquals(PidsSettings.defaults(), parsed);
    assertTrue(warnings.isEmpty());
  }

  @Test
  void parsesCustomValues() {
    YamlConfiguration yaml = new YamlConfiguration();
    yaml.set("enabled", false);
    yaml.set("render.check-interval-ticks", 10);
    yaml.set("render.slide-main-seconds", 20);
    yaml.set("render.notice-pin-seconds", 8);
    yaml.set("limits.max-screens", 64);
    yaml.set("font.cjk-glyphs", " ZH-HANT ");
    yaml.set("font.detect-glyphs", false);
    yaml.set("layout.platform", "platform-1x2");
    yaml.set("appearance.mode", "Dark");
    yaml.set("broadcast.range-blocks", 20);
    yaml.set("broadcast.dedupe-seconds", 0);
    yaml.set("broadcast.trigger-delayed", false);
    yaml.set("broadcast.channel-sound", false);

    PidsSettings parsed = PidsSettings.parse(yaml, Logger.getAnonymousLogger());

    assertFalse(parsed.enabled());
    assertEquals(10, parsed.render().checkIntervalTicks());
    assertEquals(20, parsed.render().slideMainSeconds());
    assertEquals(8, parsed.render().noticePinSeconds());
    assertEquals(64, parsed.limits().maxScreens());
    assertEquals(PidsGlyphForm.ZH_HANT, parsed.font().cjkGlyphs());
    assertFalse(parsed.font().detectGlyphs());
    assertEquals("platform-1x2", parsed.layout().platform());
    assertEquals("station-3x5", parsed.layout().station());
    assertEquals(AppearanceMode.DARK, parsed.appearance().mode());
    assertEquals(20, parsed.broadcast().rangeBlocks());
    assertEquals(0, parsed.broadcast().dedupeSeconds());
    assertFalse(parsed.broadcast().triggers().delayed());
    assertTrue(parsed.broadcast().triggers().arriving());
    assertFalse(parsed.broadcast().channelSound());
    assertTrue(parsed.broadcast().channelText());
  }

  @Test
  // 无效值逐项回退并各给一条警告，其余有效项不受影响。
  void invalidValuesFallBackAndWarn() {
    YamlConfiguration yaml = new YamlConfiguration();
    yaml.set("render.check-interval-ticks", 0);
    yaml.set("render.horizon-minutes", -5);
    yaml.set("limits.max-screens", -1);
    yaml.set("broadcast.range-blocks", 0);
    yaml.set("broadcast.dedupe-seconds", -3);
    yaml.set("appearance.mode", "sepia");
    yaml.set("font.cjk-glyphs", "ko");
    yaml.set("render.slide-main-seconds", 9);
    List<String> warnings = new ArrayList<>();

    PidsSettings parsed = PidsSettings.parse(yaml, loggerCollecting(warnings));

    PidsSettings defaults = PidsSettings.defaults();
    assertEquals(defaults.render().checkIntervalTicks(), parsed.render().checkIntervalTicks());
    assertEquals(defaults.render().horizonMinutes(), parsed.render().horizonMinutes());
    assertEquals(defaults.limits().maxScreens(), parsed.limits().maxScreens());
    assertEquals(defaults.broadcast().rangeBlocks(), parsed.broadcast().rangeBlocks());
    assertEquals(defaults.broadcast().dedupeSeconds(), parsed.broadcast().dedupeSeconds());
    assertEquals(AppearanceMode.MC_TIME, parsed.appearance().mode());
    assertEquals(PidsGlyphForm.ZH_HANS, parsed.font().cjkGlyphs(), "未内置的字形版本回退为简体");
    assertTrue(parsed.font().detectGlyphs(), "字形版本无效不影响按内容判断");
    assertEquals(9, parsed.render().slideMainSeconds());
    assertEquals(7, warnings.size(), () -> "每个无效项一条警告: " + warnings);
    assertTrue(warnings.stream().allMatch(message -> message.contains("pids.yml")));
  }

  @Test
  void glyphFormAcceptsHyphenAndAnyCase() {
    assertEquals(Optional.of(PidsGlyphForm.JA), PidsGlyphForm.fromConfig(" JA "));
    assertEquals(Optional.of(PidsGlyphForm.ZH_HANT), PidsGlyphForm.fromConfig("zh-hant"));
    assertEquals(Optional.empty(), PidsGlyphForm.fromConfig("ko"));
    assertEquals(Optional.empty(), PidsGlyphForm.fromConfig(null));
  }

  @Test
  void appearanceModeAcceptsUnderscoreAndAnyCase() {
    assertEquals(Optional.of(AppearanceMode.MC_TIME), AppearanceMode.fromConfig("MC_TIME"));
    assertEquals(Optional.of(AppearanceMode.MC_TIME), AppearanceMode.fromConfig(" mc-time "));
    assertEquals(Optional.of(AppearanceMode.LIGHT), AppearanceMode.fromConfig("Light"));
    assertEquals(Optional.empty(), AppearanceMode.fromConfig("auto"));
    assertEquals(Optional.empty(), AppearanceMode.fromConfig(null));
  }

  @Test
  // 默认窗口：12500 起为夜间，23000 起回到白天；边界刻本身落在新状态上。
  void mcTimeSwitchesAtConfiguredTicks() {
    AppearanceSettings settings = AppearanceSettings.DEFAULT;

    assertFalse(settings.darkAt(0));
    assertFalse(settings.darkAt(6000));
    assertFalse(settings.darkAt(12499));
    assertTrue(settings.darkAt(12500));
    assertTrue(settings.darkAt(18000));
    assertTrue(settings.darkAt(22999));
    assertFalse(settings.darkAt(23000));
    assertFalse(settings.darkAt(23999));
  }

  @Test
  // 世界总时间会无限增长，必须按一天取模；负数同样回绕到合法范围。
  void mcTimeReducesAbsoluteWorldTimeModuloOneDay() {
    AppearanceSettings settings = AppearanceSettings.DEFAULT;

    assertTrue(settings.darkAt(24000L * 7 + 18000));
    assertFalse(settings.darkAt(24000L * 7 + 6000));
    assertTrue(settings.darkAt(-6000));
  }

  @Test
  // 夜间窗口跨越零点（darkFrom > lightFrom）时，窗口是 [darkFrom, 24000) 与 [0, lightFrom)。
  void mcTimeSupportsWindowWrappingMidnight() {
    AppearanceSettings settings = new AppearanceSettings(AppearanceMode.MC_TIME, 20000, 3000);

    assertTrue(settings.darkAt(20000));
    assertTrue(settings.darkAt(23999));
    assertTrue(settings.darkAt(0));
    assertTrue(settings.darkAt(2999));
    assertFalse(settings.darkAt(3000));
    assertFalse(settings.darkAt(12000));
    assertFalse(settings.darkAt(19999));
  }

  @Test
  void fixedModesIgnoreTime() {
    AppearanceSettings light = new AppearanceSettings(AppearanceMode.LIGHT, 12500, 23000);
    AppearanceSettings dark = new AppearanceSettings(AppearanceMode.DARK, 12500, 23000);

    assertFalse(light.darkAt(18000));
    assertTrue(dark.darkAt(6000));
  }

  @Test
  // 夜间与白天起点相同或越界无法构成窗口：整对回退为默认值，并只给一条警告。
  void invalidAppearanceWindowFallsBackAsAPair() {
    YamlConfiguration yaml = new YamlConfiguration();
    yaml.set("appearance.dark-from-tick", 5000);
    yaml.set("appearance.light-from-tick", 5000);
    List<String> warnings = new ArrayList<>();

    PidsSettings parsed = PidsSettings.parse(yaml, loggerCollecting(warnings));

    assertEquals(12500, parsed.appearance().darkFromTick());
    assertEquals(23000, parsed.appearance().lightFromTick());
    assertEquals(1, warnings.size());

    YamlConfiguration outOfRange = new YamlConfiguration();
    outOfRange.set("appearance.dark-from-tick", 24000);
    List<String> rangeWarnings = new ArrayList<>();
    PidsSettings rangeParsed = PidsSettings.parse(outOfRange, loggerCollecting(rangeWarnings));

    assertEquals(12500, rangeParsed.appearance().darkFromTick());
    assertEquals(1, rangeWarnings.size());
  }

  private static YamlConfiguration loadTemplate() throws Exception {
    try (Reader reader =
        new InputStreamReader(
            Objects.requireNonNull(
                PidsSettingsTest.class.getClassLoader().getResourceAsStream("pids.yml")),
            StandardCharsets.UTF_8)) {
      return YamlConfiguration.loadConfiguration(reader);
    }
  }

  /** 构造一个把警告收集进列表的独立日志器，不向父级传播，避免污染测试输出。 */
  private static Logger loggerCollecting(List<String> warnings) {
    Logger logger = Logger.getAnonymousLogger();
    logger.setUseParentHandlers(false);
    logger.addHandler(
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
              warnings.add(record.getMessage());
            }
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        });
    return logger;
  }
}
