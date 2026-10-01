package org.fetarute.fetaruteTCAddon.display.pids;

import java.io.File;
import java.io.InputStream;
import java.util.Objects;
import java.util.function.Supplier;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.fetarute.fetaruteTCAddon.utils.ConfigUpdater;
import org.fetarute.fetaruteTCAddon.utils.LoggerManager;

/**
 * 负责加载并缓存站台 PIDS 的独立配置文件 {@code pids.yml}。
 *
 * <p>每次 {@link #reload()} 先用 {@link ConfigUpdater} 把内置模板中新增的键与注释补进用户文件（保留用户已改的值、写回前备份），再解析为不可变的
 * {@link PidsSettings}。与 {@code config.yml} 的 {@code ConfigManager} 相互独立，避免继续膨胀主配置。
 */
public final class PidsConfigManager {

  /** 配置文件名，位于插件数据目录。 */
  public static final String FILE_NAME = "pids.yml";

  private final File dataFolder;
  private final Supplier<InputStream> templateSupplier;
  private final LoggerManager logger;
  private volatile PidsSettings current = PidsSettings.defaults();

  /**
   * 构造加载器；不触碰磁盘，需调用 {@link #reload()} 才会生成与读取文件。
   *
   * @param dataFolder 插件数据目录
   * @param templateSupplier 内置模板 {@code pids.yml} 的输入流提供者
   * @param logger 日志出口
   */
  PidsConfigManager(File dataFolder, Supplier<InputStream> templateSupplier, LoggerManager logger) {
    this.dataFolder = Objects.requireNonNull(dataFolder, "dataFolder");
    this.templateSupplier = Objects.requireNonNull(templateSupplier, "templateSupplier");
    this.logger = Objects.requireNonNull(logger, "logger");
  }

  /**
   * 为插件创建加载器，模板取自插件 jar 内的 {@code pids.yml}。
   *
   * @param plugin 宿主插件
   * @param logger 日志出口
   */
  public static PidsConfigManager forPlugin(JavaPlugin plugin, LoggerManager logger) {
    Objects.requireNonNull(plugin, "plugin");
    return new PidsConfigManager(
        plugin.getDataFolder(), () -> plugin.getResource(FILE_NAME), logger);
  }

  /** 补全缺失键后重新读取磁盘并更新缓存；文件不存在时先由内置模板生成。 */
  public void reload() {
    ConfigUpdater.forFile(dataFolder, FILE_NAME, templateSupplier, logger).update();
    File file = new File(dataFolder, FILE_NAME);
    current = PidsSettings.parse(YamlConfiguration.loadConfiguration(file), logger.underlying());
  }

  /**
   * 返回当前配置快照。
   *
   * @return 不可变配置；尚未 {@link #reload()} 时为全部默认值
   */
  public PidsSettings current() {
    return current;
  }
}
