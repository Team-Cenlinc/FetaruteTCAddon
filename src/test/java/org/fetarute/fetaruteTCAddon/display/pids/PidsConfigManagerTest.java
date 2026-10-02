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
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
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
    assertEquals(leafKeys(template()), leafKeys(merged), "广播段的新键留在广播段，不并进前面补上的段");
    assertTrue(Files.exists(tempDir.resolve("pids.yml.bak")), "备份应跟随文件名");
    assertFalse(Files.exists(tempDir.resolve("config.yml.bak")));
  }

  @Test
  // 只写了停留时间的版本 1 文件：补进其余各段的同时迁移停留时间，补完是合法的 YAML、各键都在自己的段里。
  void aSparseOldFileIsCompletedAndMigrated() throws Exception {
    Path file = tempDir.resolve(PidsConfigManager.FILE_NAME);
    Files.writeString(
        file,
        String.join(
            "\n",
            "config-version: 1",
            "render:",
            "  slide-main-seconds: 12",
            "  slide-notice-seconds: 4",
            ""),
        StandardCharsets.UTF_8);
    PidsConfigManager manager = newManager();

    manager.reload();

    assertEquals(20, manager.current().render().slideMainSeconds());
    assertEquals(5, manager.current().render().slideNoticeSeconds());
    assertEquals(6, manager.current().render().englishSeconds());
    assertEquals(leafKeys(template()), leafKeys(Files.readString(file, StandardCharsets.UTF_8)));
  }

  @Test
  // 版本 1 的文件：还停在旧默认值（主页 12、副页 4）的改成新默认值，行尾注释保留。
  void oldDefaultTimingsAreMigrated() throws IOException {
    Files.writeString(
        tempDir.resolve(PidsConfigManager.FILE_NAME),
        oldTemplate(12, "4 # 旧注释"),
        StandardCharsets.UTF_8);
    PidsConfigManager manager = newManager();

    manager.reload();

    assertEquals(20, manager.current().render().slideMainSeconds());
    assertEquals(5, manager.current().render().slideNoticeSeconds());
    String migrated =
        Files.readString(tempDir.resolve(PidsConfigManager.FILE_NAME), StandardCharsets.UTF_8);
    assertTrue(migrated.contains("slide-notice-seconds: 5 # 旧注释"), migrated);
    assertTrue(migrated.contains("config-version: 2"), migrated);
    assertTrue(migrated.contains("english-seconds: 6"), "新键照常补进");
  }

  @Test
  // 用户自己调过的停留时间不动；已是新版本的文件不再迁移（改回 4 是用户的选择）。
  void customizedOrCurrentTimingsAreKept() throws IOException {
    Path file = tempDir.resolve(PidsConfigManager.FILE_NAME);
    Files.writeString(file, oldTemplate(15, "4"), StandardCharsets.UTF_8);
    PidsConfigManager manager = newManager();
    manager.reload();
    assertEquals(15, manager.current().render().slideMainSeconds());
    assertEquals(5, manager.current().render().slideNoticeSeconds());

    Files.writeString(
        file,
        Files.readString(file, StandardCharsets.UTF_8)
            .replace("slide-notice-seconds: 5", "slide-notice-seconds: 4"),
        StandardCharsets.UTF_8);
    manager.reload();

    assertEquals(4, manager.current().render().slideNoticeSeconds());
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

  /** 按旧版（版本 1）的样子改写内置模板：没有英文停留时间，主页、副页停留时间按给的值。 */
  private static String oldTemplate(int mainSeconds, String noticeSeconds) throws IOException {
    try (InputStream in = openTemplate()) {
      String template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      String old =
          template
              .replace("config-version: 2", "config-version: 1")
              .replace("slide-main-seconds: 20", "slide-main-seconds: " + mainSeconds)
              .replace("slide-notice-seconds: 5", "slide-notice-seconds: " + noticeSeconds)
              .replace("  english-seconds: 6\n", "");
      if (old.equals(template)) {
        throw new IllegalStateException("模板里找不到要改写的键");
      }
      return old;
    }
  }

  private static String template() throws IOException {
    try (InputStream in = openTemplate()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /** 全部叶子键的完整路径；文本不是合法 YAML 时用例失败。 */
  private static Set<String> leafKeys(String yamlText) {
    YamlConfiguration yaml = new YamlConfiguration();
    try {
      yaml.loadFromString(yamlText);
    } catch (InvalidConfigurationException ex) {
      throw new AssertionError("不是合法的 YAML:\n" + yamlText, ex);
    }
    return yaml.getKeys(true).stream()
        .filter(key -> !yaml.isConfigurationSection(key))
        .collect(Collectors.toSet());
  }

  private static InputStream openTemplate() {
    return Objects.requireNonNull(
        PidsConfigManagerTest.class.getClassLoader().getResourceAsStream("pids.yml"));
  }
}
