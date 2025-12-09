package org.fetarute.fetaruteTCAddon.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.logging.Logger;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocaleManagerTest {

  @TempDir Path tempDir;

  @Test
  void shouldLoadLocaleAndRenderPlaceholders() {
    LoggerManager loggerManager = new LoggerManager(Logger.getLogger("locale-test"));
    LocaleManager manager =
        new LocaleManager(localeAccess(tempDir, loggerManager), "zh_CN", loggerManager);

    manager.reload();

    String rendered = plain(manager.component("command.info.version", Map.of("version", "1.0")));
    assertTrue(rendered.contains("版本"));
    assertTrue(rendered.contains("1.0"));
    String withPrefix = plain(manager.component("command.help.header"));
    assertTrue(withPrefix.contains("FTA"));
  }

  @Test
  void shouldFallbackToDefaultWhenLocaleMissing() {
    LoggerManager loggerManager = new LoggerManager(Logger.getLogger("locale-test"));
    LocaleManager manager =
        new LocaleManager(localeAccess(tempDir, loggerManager), "ja_JP", loggerManager);

    manager.reload();

    assertEquals("zh_CN", manager.getCurrentLocale());
    String rendered = plain(manager.component("non.existing.key", Map.of()));
    assertTrue(rendered.contains("non.existing.key"));
  }

  @Test
  void shouldUseBuiltInMissingKeyTemplateWhenTemplateAbsent() throws IOException {
    LoggerManager loggerManager = new LoggerManager(Logger.getLogger("locale-test"));
    Path langDir = tempDir.resolve("lang");
    Files.createDirectories(langDir);
    Path localeFile = langDir.resolve("test_locale.yml");
    Files.writeString(localeFile, "prefix: ''\n"); // 不包含 error.missing-key
    LocaleManager manager =
        new LocaleManager(localeAccess(tempDir, loggerManager), "test_locale", loggerManager);

    manager.reload();

    String rendered = plain(manager.component("non.existing.key", Map.of()));
    assertTrue(rendered.contains("缺少语言键"));
  }

  private LocaleManager.LocaleAccess localeAccess(Path dataFolder, LoggerManager loggerManager) {
    return new LocaleManager.LocaleAccess(
        dataFolder.toFile(),
        loggerManager,
        (path, replace) -> {
          try {
            copyResourceToDataFolder(path, dataFolder.toFile(), replace);
          } catch (IOException ex) {
            throw new RuntimeException(ex);
          }
        });
  }

  private void copyResourceToDataFolder(String path, File dataFolder, boolean replace)
      throws IOException {
    try (InputStream input = LocaleManagerTest.class.getClassLoader().getResourceAsStream(path)) {
      if (input == null) {
        throw new IllegalArgumentException("Resource not found: " + path);
      }
      File target = new File(dataFolder, path);
      File parent = target.getParentFile();
      if (parent != null && !parent.exists()) {
        boolean created = parent.mkdirs();
        if (!created && !parent.exists()) {
          throw new IOException("无法创建语言目录：" + parent);
        }
      }
      if (!replace && target.exists()) {
        return;
      }
      Files.copy(input, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private String plain(net.kyori.adventure.text.Component component) {
    return PlainTextComponentSerializer.plainText().serialize(component);
  }
}
