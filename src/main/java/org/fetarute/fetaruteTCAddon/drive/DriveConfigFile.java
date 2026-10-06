package org.fetarute.fetaruteTCAddon.drive;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.function.Supplier;
import org.bukkit.configuration.file.YamlConfiguration;
import org.fetarute.fetaruteTCAddon.drive.license.LicenseClassIdMigration;
import org.fetarute.fetaruteTCAddon.utils.ConfigUpdater;
import org.fetarute.fetaruteTCAddon.utils.LoggerManager;

/**
 * 手动驾驶的独立配置文件 {@code drive.yml}。
 *
 * <p>文件不存在时由内置模板创建；已存在时把模板里新增的键补进去（保留用户已改的值和注释），再解析成 {@link DriveConfig}。 与主配置 {@code config.yml}
 * 互不影响。
 */
public final class DriveConfigFile {

  /** 配置文件名。 */
  public static final String FILE_NAME = "drive.yml";

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
    // 驾驶证等级改名要在补全新键之前做：否则补全按模板加上新的两级，与旧键并存。
    migrateLicenseClassIds(file, logger);
    new ConfigUpdater(file, template, logger).update();
    YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
    return DriveConfig.from(yaml, logger::warn);
  }

  /** 把旧的驾驶证等级 ID 与名称改成新的（见 {@link LicenseClassIdMigration}）；改动前先备份为 {@code drive.yml.bak}。 */
  private static void migrateLicenseClassIds(File file, LoggerManager logger) {
    if (!file.isFile()) {
      return;
    }
    try {
      List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
      List<String> migrated = LicenseClassIdMigration.migrate(lines);
      if (migrated.equals(lines)) {
        return;
      }
      Files.copy(
          file.toPath(),
          file.toPath().resolveSibling(FILE_NAME + ".bak"),
          StandardCopyOption.REPLACE_EXISTING);
      Files.write(file.toPath(), migrated, StandardCharsets.UTF_8);
      logger.info("drive.yml：驾驶证等级 free、dispatch 已改名为 learner（见习驾驶证）、driver（正式驾驶证）");
    } catch (IOException ex) {
      logger.warn("迁移 drive.yml 的驾驶证等级失败: " + ex.getMessage());
    }
  }
}
