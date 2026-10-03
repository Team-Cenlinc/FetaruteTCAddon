package org.fetarute.fetaruteTCAddon.drive;

import java.io.File;
import java.io.InputStream;
import java.util.function.Supplier;
import org.bukkit.configuration.file.YamlConfiguration;
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
    new ConfigUpdater(file, template, logger).update();
    YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
    return DriveConfig.from(yaml, logger::warn);
  }
}
