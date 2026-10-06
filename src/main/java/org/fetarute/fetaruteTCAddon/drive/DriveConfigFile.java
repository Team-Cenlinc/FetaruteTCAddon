package org.fetarute.fetaruteTCAddon.drive;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.bukkit.configuration.file.YamlConfiguration;
import org.fetarute.fetaruteTCAddon.drive.license.LicenseClassIdMigration;
import org.fetarute.fetaruteTCAddon.utils.ConfigUpdater;
import org.fetarute.fetaruteTCAddon.utils.LoggerManager;

/**
 * 驾驶功能的独立配置文件 {@code drive.yml}。
 *
 * <p>文件不存在时由内置模板创建；已存在时把模板里新增的键补进去（保留用户已改的值和注释），再解析成 {@link DriveConfig}。 与主配置 {@code config.yml}
 * 互不影响。
 */
public final class DriveConfigFile {

  /** 配置文件名。 */
  public static final String FILE_NAME = "drive.yml";

  /** 旧写法改名前的原件备份。 */
  static final String MIGRATION_BACKUP = FILE_NAME + ".before-rename.bak";

  private DriveConfigFile() {}

  /**
   * 创建或补全配置文件并读取。
   *
   * @param dataFolder 插件数据目录
   * @param template 内置模板的输入流提供者
   * @param logger 日志输出
   */
  public static DriveConfig load(
      File dataFolder, Supplier<InputStream> template, LoggerManager logger) {
    File file = new File(dataFolder, FILE_NAME);
    // 改名要在补全新键之前做：否则补全按模板加上新键，与旧键并存。
    migrateRenamed(file, logger);
    // 按行没改成名的旧键（如流式写法）：补全会按模板加上新键，读配置时改用旧键的值。
    YamlConfiguration before = YamlConfiguration.loadConfiguration(file);
    Map<String, String> unmigrated = DriveConfigKeyRenames.unmigrated(before);
    new ConfigUpdater(file, template, logger).update();
    YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
    DriveConfigKeyRenames.carryOver(before, unmigrated, yaml);
    unmigrated.forEach(
        (from, to) -> logger.warn("drive.yml 的 " + from + " 没能自动改名为 " + to + "，本次按旧键的值读取，请手动改名"));
    return DriveConfig.from(yaml, logger::warn);
  }

  /**
   * 把旧写法改成新的：驾驶证等级 ID、名称与考试方式（见 {@link LicenseClassIdMigration}），以及改过名的键（见 {@link
   * DriveConfigKeyRenames}）；改动前先备份为 {@value #MIGRATION_BACKUP}。
   *
   * <p>备份另起文件名：随后补全新键时 {@code ConfigUpdater} 会把文件另存为 {@code drive.yml.bak}，同名就会把迁移前的原件盖掉。
   */
  private static void migrateRenamed(File file, LoggerManager logger) {
    if (!file.isFile()) {
      return;
    }
    try {
      List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
      List<String> licenses = LicenseClassIdMigration.migrate(lines);
      DriveConfigKeyRenames.Result keys = DriveConfigKeyRenames.rename(licenses);
      List<String> migrated = keys.lines();
      if (migrated.equals(lines)) {
        return;
      }
      Files.copy(
          file.toPath(),
          file.toPath().resolveSibling(MIGRATION_BACKUP),
          StandardCopyOption.REPLACE_EXISTING);
      Files.write(file.toPath(), migrated, StandardCharsets.UTF_8);
      if (!licenses.equals(lines)) {
        logger.info(
            "drive.yml：驾驶证等级 free、dispatch 已改名为 learner（见习驾驶证）、driver（正式驾驶证），"
                + "考试方式 dispatch 已改写为 road-test");
      }
      if (!keys.renamed().isEmpty()) {
        logger.info("drive.yml：已改名的键 " + String.join("，", keys.renamed()));
      }
      logger.info("drive.yml 改名前的原件已另存为 " + MIGRATION_BACKUP);
    } catch (IOException ex) {
      logger.warn("迁移 drive.yml 的旧写法失败: " + ex.getMessage());
    }
  }
}
