package org.fetarute.fetaruteTCAddon.display.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 默认模板升级：没改过的旧版默认模板换成新版，改过的不动。 */
class HudDefaultTemplateServiceTest {

  @TempDir Path dir;
  private final List<String> logs = new ArrayList<>();

  private HudDefaultTemplateService service() {
    return new HudDefaultTemplateService(
        dir.toFile(),
        (name, replace) -> {
          Path target = dir.resolve(name);
          if (!replace && Files.exists(target)) {
            return;
          }
          try (InputStream in = resource(name)) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
          } catch (IOException ex) {
            throw new UncheckedIOException(ex);
          }
        },
        HudDefaultTemplateServiceTest::resource,
        logs::add,
        logs::add);
  }

  private static InputStream resource(String name) {
    return HudDefaultTemplateServiceTest.class.getClassLoader().getResourceAsStream(name);
  }

  private static String bundled(HudTemplateType type) throws IOException {
    try (InputStream in = resource("default_hud_template.yml")) {
      return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8))
          .getString(type.name().toLowerCase(Locale.ROOT) + ".template");
    }
  }

  private void writePrevious() throws IOException {
    try (InputStream in = resource("hud/default_hud_template-previous.yml")) {
      Files.copy(in, dir.resolve("default_hud_template.yml"));
    }
  }

  @Test
  void missingFileIsCopiedFromThePlugin() throws IOException {
    HudDefaultTemplateService service = service();
    service.reload();

    assertTrue(Files.exists(dir.resolve("default_hud_template.yml")));
    assertEquals(
        bundled(HudTemplateType.ACTIONBAR),
        service.resolveTemplate(HudTemplateType.ACTIONBAR).orElseThrow());
  }

  @Test
  void untouchedPreviousDefaultIsReplacedByTheNewOne() throws IOException {
    writePrevious();
    HudDefaultTemplateService service = service();
    service.reload();

    for (HudTemplateType type :
        List.of(
            HudTemplateType.BOSSBAR, HudTemplateType.ACTIONBAR, HudTemplateType.PLAYER_DISPLAY)) {
      assertEquals(bundled(type), service.resolveTemplate(type).orElseThrow(), type.name());
    }
    assertEquals(
        Files.readString(dir.resolve("default_hud_template.yml")),
        new String(resource("default_hud_template.yml").readAllBytes(), StandardCharsets.UTF_8),
        "全是旧版默认模板时整份文件换成新版");
    assertTrue(Files.exists(dir.resolve("default_hud_template.yml.bak")), "改写前另存一份");
  }

  @Test
  void blankChannelStillFallsBackToTheLanguageFileAndBlocksTheRewrite() throws IOException {
    writePrevious();
    Path file = dir.resolve("default_hud_template.yml");
    YamlConfiguration config = YamlConfiguration.loadConfiguration(file.toFile());
    config.set("bossbar.template", "");
    config.save(file.toFile());
    String before = Files.readString(file);

    HudDefaultTemplateService service = service();
    service.reload();

    assertTrue(service.resolveTemplate(HudTemplateType.BOSSBAR).isEmpty(), "留空表示回退语言文件");
    assertEquals(
        bundled(HudTemplateType.ACTIONBAR),
        service.resolveTemplate(HudTemplateType.ACTIONBAR).orElseThrow());
    assertEquals(before, Files.readString(file), "整份改写会把留空的通道填回去，所以不改写");
  }

  @Test
  void channelsThePluginDoesNotShipAreKept() throws IOException {
    writePrevious();
    Path file = dir.resolve("default_hud_template.yml");
    YamlConfiguration config = YamlConfiguration.loadConfiguration(file.toFile());
    config.set("announcement.template", "自己加的广播模板");
    config.save(file.toFile());

    HudDefaultTemplateService service = service();
    service.reload();

    assertEquals("自己加的广播模板", service.resolveTemplate(HudTemplateType.ANNOUNCEMENT).orElseThrow());
    assertTrue(Files.readString(file).contains("自己加的广播模板"), "插件不内置的通道算改过，整份改写会把它丢掉");
  }

  @Test
  void editedTemplatesAreKeptAndTheFileIsNotRewritten() throws IOException {
    writePrevious();
    Path file = dir.resolve("default_hud_template.yml");
    YamlConfiguration config = YamlConfiguration.loadConfiguration(file.toFile());
    config.set("actionbar.template", "IN_TRIP: 自己写的");
    config.save(file.toFile());
    String before = Files.readString(file);

    HudDefaultTemplateService service = service();
    service.reload();

    assertEquals("IN_TRIP: 自己写的", service.resolveTemplate(HudTemplateType.ACTIONBAR).orElseThrow());
    assertEquals(
        bundled(HudTemplateType.BOSSBAR),
        service.resolveTemplate(HudTemplateType.BOSSBAR).orElseThrow(),
        "没改过的通道仍按新版显示");
    assertEquals(before, Files.readString(file), "文件里有改过的模板，不改写");
  }

  @Test
  void fingerprintIgnoresTrailingWhitespaceAndBlankEdges() {
    assertEquals(
        HudDefaultTemplateService.fingerprint("a\nb"),
        HudDefaultTemplateService.fingerprint("\na  \nb\n\n"));
  }
}
