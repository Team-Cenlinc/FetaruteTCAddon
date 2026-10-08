package org.fetarute.fetaruteTCAddon.drive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.fetarute.fetaruteTCAddon.utils.LoggerManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DriveConfigFileTest {

  private static final String TEMPLATE =
      "config-version: 2\n"
          + "enabled: true\n"
          + "level: standard\n"
          + "coast-drag-bps2: 0.03\n"
          + "hud-interval-ticks: 5\n";

  private final LoggerManager logger = new LoggerManager(Logger.getLogger("drive-config-test"));

  private static InputStream template() {
    return new ByteArrayInputStream(TEMPLATE.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void createsTheFileFromTheTemplateAndReadsIt(@TempDir Path dir) {
    DriveConfig config = DriveConfigFile.load(dir.toFile(), DriveConfigFileTest::template, logger);

    assertTrue(new File(dir.toFile(), DriveConfigFile.FILE_NAME).isFile());
    assertEquals(SimulationLevel.STANDARD, config.level());
    assertEquals(0.03, config.coastDragBps2());
  }

  @Test
  void renamesOldWritingsBeforeFillingInTemplateKeys(@TempDir Path dir) throws IOException {
    Files.writeString(
        dir.resolve(DriveConfigFile.FILE_NAME),
        "config-version: 2\n"
            + "license:\n"
            + "  classes:\n"
            + "    free:\n"
            + "      name: \"自由驾驶证\"\n"
            + "      exam: tutorial\n"
            + "    dispatch:\n"
            + "      name: \"调度驾驶证\"\n"
            + "      requires:\n"
            + "        - free\n"
            + "      exam: dispatch\n"
            + "      min-points: 80\n"
            + "driver:\n"
            + "  breaker-held-trains: 9\n"
            + "sounds:\n"
            + "  signal-confirmed:\n"
            + "    volume: 0.3\n",
        StandardCharsets.UTF_8);
    String withLicense =
        TEMPLATE
            + "license:\n"
            + "  classes:\n"
            + "    learner:\n"
            + "      name: \"见习驾驶证\"\n"
            + "      exam: tutorial\n"
            + "    driver:\n"
            + "      name: \"正式驾驶证\"\n"
            + "      requires:\n"
            + "        - learner\n"
            + "      exam: road-test\n"
            + "      min-points: 70\n"
            + "driver:\n"
            + "  protection-held-trains: 5\n"
            + "sounds:\n"
            + "  signal-acknowledged:\n"
            + "    volume: 0.6\n";

    DriveConfig config =
        DriveConfigFile.load(
            dir.toFile(),
            () -> new ByteArrayInputStream(withLicense.getBytes(StandardCharsets.UTF_8)),
            logger);

    assertEquals(
        java.util.List.of("learner", "driver"),
        config.license().classes().stream()
            .map(org.fetarute.fetaruteTCAddon.drive.license.LicenseClass::id)
            .toList(),
        "旧的两级改名后不与模板的新键并存");
    assertEquals(80, config.license().find("driver").orElseThrow().minPoints(), "改过的值保留");
    assertEquals("正式驾驶证", config.license().find("driver").orElseThrow().name());
    assertEquals(
        org.fetarute.fetaruteTCAddon.drive.license.LicenseClass.Exam.ROAD_TEST,
        config.license().find("driver").orElseThrow().exam());
    assertEquals(9, config.driver().recovery().protectionHeldTrains(), "改名的键沿用旧键上改过的值");
    assertEquals(
        0.3f,
        config
            .sounds()
            .spec(org.fetarute.fetaruteTCAddon.drive.sound.DriveCue.SIGNAL_ACKNOWLEDGED)
            .orElseThrow()
            .volume());
    String migrated =
        Files.readString(dir.resolve(DriveConfigFile.FILE_NAME), StandardCharsets.UTF_8);
    assertTrue(migrated.contains("exam: road-test") && !migrated.contains("exam: dispatch"));
    assertTrue(
        migrated.contains("protection-held-trains: 9") && !migrated.contains("breaker-held"),
        "旧键改名，不与补全的新键并存");
    assertTrue(migrated.contains("signal-acknowledged:") && !migrated.contains("signal-confirmed"));
    String backup =
        Files.readString(dir.resolve(DriveConfigFile.MIGRATION_BACKUP), StandardCharsets.UTF_8);
    assertTrue(backup.contains("    free:"), "迁移前的原件单独备份，不被补全新键时的备份盖掉");
  }

  @Test
  void keepsUserValuesAndFillsInKeysTheTemplateAdded(@TempDir Path dir) throws IOException {
    Files.writeString(
        dir.resolve(DriveConfigFile.FILE_NAME),
        "config-version: 1\nlevel: simulation\nhud-interval-ticks: 10\n",
        StandardCharsets.UTF_8);

    DriveConfig config = DriveConfigFile.load(dir.toFile(), DriveConfigFileTest::template, logger);

    assertEquals(SimulationLevel.SIMULATION, config.level(), "用户改过的值不被模板覆盖");
    assertEquals(10, config.hudIntervalTicks());
    String merged =
        Files.readString(dir.resolve(DriveConfigFile.FILE_NAME), StandardCharsets.UTF_8);
    assertTrue(merged.contains("coast-drag-bps2"), "模板新增的键被补进文件");
    assertTrue(merged.contains("config-version: 2"));
  }

  @Test
  void backsUpUnderItsOwnNameAndLeavesTheMainConfigBackupAlone(@TempDir Path dir)
      throws IOException {
    Files.writeString(
        dir.resolve(DriveConfigFile.FILE_NAME),
        "config-version: 1\nlevel: simulation\n",
        StandardCharsets.UTF_8);
    Path mainBackup = dir.resolve("config.yml.bak");
    Files.writeString(mainBackup, "main config backup", StandardCharsets.UTF_8);

    DriveConfigFile.load(dir.toFile(), DriveConfigFileTest::template, logger);

    assertTrue(Files.exists(dir.resolve("drive.yml.bak")));
    assertEquals("main config backup", Files.readString(mainBackup, StandardCharsets.UTF_8));
  }

  @Test
  void anUnchangedFileIsNotRewrittenOrBackedUp(@TempDir Path dir) {
    DriveConfigFile.load(dir.toFile(), DriveConfigFileTest::template, logger);
    File backup = new File(dir.toFile(), "drive.yml.bak");
    assertFalse(backup.exists());

    DriveConfigFile.load(dir.toFile(), DriveConfigFileTest::template, logger);

    assertFalse(backup.exists());
  }
}
