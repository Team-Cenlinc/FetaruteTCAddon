package org.fetarute.fetaruteTCAddon.display.pids;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.fetarute.fetaruteTCAddon.utils.ConfigUpdater;
import org.fetarute.fetaruteTCAddon.utils.LoggerManager;

/**
 * 负责加载并缓存站台 PIDS 的独立配置文件 {@code pids.yml}。
 *
 * <p>每次 {@link #reload()} 先用 {@link ConfigUpdater} 把内置模板中新增的键与注释补进用户文件（保留用户已改的值、写回前备份），再解析为不可变的
 * {@link PidsSettings}。与 {@code config.yml} 的 {@code ConfigManager} 相互独立，避免继续膨胀主配置。
 *
 * <p>补键只补缺的，已有的值原样保留；默认值改过的键另做迁移：版本 {@value #TIMING_VERSION} 起主页 20 秒、副页 5 秒，
 * 旧文件里还停在旧默认值（12、4）的改成新值，用户自己调过的不动。
 */
public final class PidsConfigManager {

  /** 配置文件名，位于插件数据目录。 */
  public static final String FILE_NAME = "pids.yml";

  /** 主页、副页停留时间改了默认值的配置版本。 */
  static final int TIMING_VERSION = 2;

  /** 旧默认值仍未改动时换成新默认值的键。 */
  private static final List<TimingDefault> TIMING_DEFAULTS =
      List.of(
          new TimingDefault("slide-main-seconds", 12, 20),
          new TimingDefault("slide-notice-seconds", 4, 5));

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

  /** 补全缺失键、迁移旧默认值后重新读取磁盘并更新缓存；文件不存在时先由内置模板生成。 */
  public void reload() {
    File file = new File(dataFolder, FILE_NAME);
    int before =
        file.exists()
            ? YamlConfiguration.loadConfiguration(file).getInt("config-version", 0)
            : TIMING_VERSION;
    ConfigUpdater.forFile(dataFolder, FILE_NAME, templateSupplier, logger).update();
    if (before < TIMING_VERSION) {
      migrateTimings(file);
    }
    current = PidsSettings.parse(YamlConfiguration.loadConfiguration(file), logger.underlying());
  }

  /** 把还停在旧默认值的停留时间改成新默认值；按行改写，保留注释与其余内容。读写失败只告警，照旧值运行。 */
  private void migrateTimings(File file) {
    try {
      List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
      boolean changed = false;
      for (int i = 0; i < lines.size(); i++) {
        for (TimingDefault timing : TIMING_DEFAULTS) {
          Matcher matcher = timing.pattern().matcher(lines.get(i));
          if (matcher.matches()) {
            lines.set(i, matcher.group(1) + timing.now() + matcher.group(2));
            changed = true;
          }
        }
      }
      if (changed) {
        Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
        logger.info("pids.yml 的主页、副页停留时间仍是旧默认值，已改为新默认值（主页 20 秒、副页 5 秒）");
      }
    } catch (IOException ex) {
      logger.warn("迁移 pids.yml 的停留时间失败，沿用文件里的值: " + ex.getMessage());
    }
  }

  /**
   * 默认值改过的一个键。
   *
   * @param key 键名（{@code render} 下）
   * @param before 旧默认值
   * @param now 新默认值
   */
  private record TimingDefault(String key, int before, int now) {

    /** 整行就是“键: 旧默认值”（可带行尾注释）。 */
    Pattern pattern() {
      return Pattern.compile("^(\\s*" + Pattern.quote(key) + ":\\s*)" + before + "(\\s*(?:#.*)?)$");
    }
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
