package org.fetarute.fetaruteTCAddon.utils;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigUpdaterTest {

  @TempDir private Path tempDir;

  @Test
  void mergesNewKeyWithCommentsAndKeepsExistingValues() throws IOException {
    String template =
        String.join(
            "\n",
            "# config 版本",
            "config-version: 2",
            "runtime:",
            "  # A",
            "  speed-curve-enabled: true",
            "  # B",
            "  speed-curve-type: \"physics\"",
            "storage:",
            "  backend: sqlite",
            "");
    String existing =
        String.join(
            "\n",
            "config-version: 1",
            "runtime:",
            "  speed-curve-enabled: false",
            "storage:",
            "  backend: mysql",
            "");

    String merged = runUpdate(template, existing);

    assertTrue(merged.contains("speed-curve-enabled: false"));
    assertTrue(merged.contains("# B"));
    assertTrue(merged.contains("speed-curve-type: \"physics\""));
    assertTrue(merged.contains("backend: mysql"));
    assertTrue(merged.contains("config-version: 2"));
  }

  @Test
  void insertsMissingSectionWithComments() throws IOException {
    String template =
        String.join(
            "\n",
            "# config 版本",
            "config-version: 1",
            "runtime:",
            "  # A",
            "  speed-curve-enabled: true",
            "  # B",
            "  speed-curve-type: \"physics\"",
            "storage:",
            "  backend: sqlite",
            "");
    String existing = String.join("\n", "config-version: 1", "storage:", "  backend: sqlite", "");

    String merged = runUpdate(template, existing);

    assertTrue(merged.contains("runtime:"));
    assertTrue(merged.contains("# A"));
    assertTrue(merged.contains("speed-curve-enabled: true"));
    assertTrue(merged.contains("# B"));
    assertTrue(merged.contains("speed-curve-type: \"physics\""));
  }

  private String runUpdate(String template, String existing) throws IOException {
    Path config = tempDir.resolve("config.yml");
    Files.writeString(config, existing, StandardCharsets.UTF_8);
    LoggerManager logger = new LoggerManager(Logger.getLogger("ConfigUpdaterTest"));
    ConfigUpdater updater =
        new ConfigUpdater(
            config.toFile(),
            () -> new ByteArrayInputStream(template.getBytes(StandardCharsets.UTF_8)),
            logger);
    updater.update();
    return Files.readString(config, StandardCharsets.UTF_8);
  }
}
