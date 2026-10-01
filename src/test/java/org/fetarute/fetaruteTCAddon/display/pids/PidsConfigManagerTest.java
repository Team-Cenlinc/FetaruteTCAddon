package org.fetarute.fetaruteTCAddon.display.pids;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.logging.Logger;
import org.fetarute.fetaruteTCAddon.utils.LoggerManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PidsConfigManagerTest {

  @TempDir private Path tempDir;

  @Test
  void currentIsDefaultsBeforeFirstReload() {
    assertEquals(PidsSettings.defaults(), newManager().current());
  }

  @Test
  // 数据目录里没有 pids.yml 时，reload 由内置模板生成并读到默认值。
  void reloadCreatesFileFromTemplate() {
    PidsConfigManager manager = newManager();

    manager.reload();

    assertTrue(Files.exists(tempDir.resolve(PidsConfigManager.FILE_NAME)));
    assertEquals(PidsSettings.defaults(), manager.current());
  }

  @Test
  // 旧文件缺新键时：补进缺失键、保留用户已改的值，并按文件名写出备份。
  void reloadKeepsUserValuesAndFillsMissingKeys() throws IOException {
    Files.writeString(
        tempDir.resolve(PidsConfigManager.FILE_NAME),
        String.join(
            "\n", "config-version: 0", "enabled: false", "broadcast:", "  range-blocks: 20", ""),
        StandardCharsets.UTF_8);
    PidsConfigManager manager = newManager();

    manager.reload();

    PidsSettings settings = manager.current();
    assertFalse(settings.enabled());
    assertEquals(20, settings.broadcast().rangeBlocks());
    assertEquals(PidsSettings.defaults().render(), settings.render());
    String merged =
        Files.readString(tempDir.resolve(PidsConfigManager.FILE_NAME), StandardCharsets.UTF_8);
    assertTrue(merged.contains("check-interval-ticks"), "缺失的键应被补入");
    assertTrue(merged.contains("range-blocks: 20"), "用户已改的值不能被模板覆盖");
    assertTrue(Files.exists(tempDir.resolve("pids.yml.bak")), "备份应跟随文件名");
    assertFalse(Files.exists(tempDir.resolve("config.yml.bak")));
  }

  @Test
  // 用户在 reload 之间改了文件，下一次 reload 必须读到新值。
  void reloadPicksUpEditsBetweenReloads() throws IOException {
    PidsConfigManager manager = newManager();
    manager.reload();
    Path file = tempDir.resolve(PidsConfigManager.FILE_NAME);
    String edited =
        Files.readString(file, StandardCharsets.UTF_8)
            .replace("max-screens: 200", "max-screens: 12");
    Files.writeString(file, edited, StandardCharsets.UTF_8);

    manager.reload();

    assertEquals(12, manager.current().limits().maxScreens());
  }

  private PidsConfigManager newManager() {
    return new PidsConfigManager(
        tempDir.toFile(),
        PidsConfigManagerTest::openTemplate,
        new LoggerManager(Logger.getLogger("pids-config-test")));
  }

  private static InputStream openTemplate() {
    return Objects.requireNonNull(
        PidsConfigManagerTest.class.getClassLoader().getResourceAsStream("pids.yml"));
  }
}
