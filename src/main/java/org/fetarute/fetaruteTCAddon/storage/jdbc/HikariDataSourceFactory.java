package org.fetarute.fetaruteTCAddon.storage.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.Locale;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.utils.LoggerManager;

/**
 * 基于配置创建 HikariDataSource，统一封装 SQLite/MySQL 的 URL 与池参数。
 *
 * <p>SQLite 强制单连接池以避免锁竞争；MySQL 按配置值构建，保持 JDBC URL 一致性。
 */
public final class HikariDataSourceFactory {

  private HikariDataSourceFactory() {}

  /**
   * 构建数据源，调用方负责关闭。
   *
   * @param settings 存储配置
   * @param dataFolder 插件数据目录，用于定位 sqlite 文件
   * @param logger 日志工具
   * @return HikariDataSource 实例
   */
  public static HikariDataSource create(
      ConfigManager.StorageSettings settings, File dataFolder, LoggerManager logger) {
    ConfigManager.PoolSettings pool = settings.poolSettings();
    HikariConfig config = new HikariConfig();
    config.setPoolName("fta-" + settings.backend().name().toLowerCase(Locale.ROOT));
    config.setMaximumPoolSize(pool.maximumPoolSize());
    config.setConnectionTimeout(pool.connectionTimeoutMillis());
    config.setIdleTimeout(pool.idleTimeoutMillis());
    config.setMaxLifetime(pool.maxLifetimeMillis());

    switch (settings.backend()) {
      case SQLITE -> configureSqlite(config, settings.sqliteSettings(), dataFolder, logger);
      case MYSQL -> configureMySql(config, settings.mySqlSettings().orElseThrow(), logger);
    }
    return new HikariDataSource(config);
  }

  private static void configureSqlite(
      HikariConfig config,
      ConfigManager.SqliteSettings sqlite,
      File dataFolder,
      LoggerManager logger) {
    File base = dataFolder == null ? new File("data") : dataFolder;
    Path dbPath = base.toPath().resolve(sqlite.file());
    Path parentPath = dbPath.getParent();
    if (parentPath != null) {
      File parent = parentPath.toFile();
      if (!parent.exists()) {
        boolean created = parent.mkdirs();
        if (!created && !parent.exists()) {
          throw new IllegalStateException("无法创建 SQLite 数据目录：" + parent);
        }
      }
    }

    // 如果文件存在，检查是否是有效的 SQLite 数据库
    File dbFile = dbPath.toFile();
    if (dbFile.exists() && dbFile.length() > 0) {
      verifySqliteHeader(dbFile, logger);
    }

    config.setJdbcUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
    config.setDriverClassName("org.sqlite.JDBC");
    config.setMaximumPoolSize(1);
    // 注意：SQLite 的 PRAGMA foreign_keys 需要在没有事务时开启；若连接默认 autoCommit=false，
    // JDBC driver 可能在初始化阶段进入事务导致 PRAGMA 不生效，因此这里保持默认 autoCommit=true。
    config.setAutoCommit(true);
    config.setConnectionInitSql("PRAGMA foreign_keys=ON");
    logger.debug("SQLite 数据库文件: " + dbPath.toAbsolutePath());
  }

  /** SQLite 文件的魔数头（前 16 字节）。 */
  private static final byte[] SQLITE_HEADER =
      "SQLite format 3\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

  /**
   * 验证 SQLite 文件头是否有效。
   *
   * @param dbFile 数据库文件
   * @param logger 日志工具
   * @throws IllegalStateException 如果文件头无效或损坏
   */
  private static void verifySqliteHeader(File dbFile, LoggerManager logger) {
    byte[] header = new byte[16];
    try (RandomAccessFile raf = new RandomAccessFile(dbFile, "r")) {
      int read = raf.read(header);
      if (read < 16) {
        throw new IllegalStateException("SQLite 数据库文件损坏或截断（文件大小不足）: " + dbFile.getAbsolutePath());
      }
      for (int i = 0; i < SQLITE_HEADER.length; i++) {
        if (header[i] != SQLITE_HEADER[i]) {
          throw new IllegalStateException(
              "SQLite 数据库文件损坏（无效文件头）: " + dbFile.getAbsolutePath() + "。请删除该文件或从备份恢复后重试。");
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException(
          "无法读取 SQLite 数据库文件: " + dbFile.getAbsolutePath() + " - " + e.getMessage(), e);
    }
    logger.debug("SQLite 文件头验证通过: " + dbFile.getAbsolutePath());
  }

  private static void configureMySql(
      HikariConfig config, ConfigManager.MySqlSettings mysql, LoggerManager logger) {
    String jdbcUrl =
        "jdbc:mysql://"
            + mysql.address()
            + ":"
            + mysql.port()
            + "/"
            + mysql.database()
            + "?useSSL=false&allowPublicKeyRetrieval=true&useUnicode=true&characterEncoding=utf8mb4"
            + "&serverTimezone=UTC";
    config.setJdbcUrl(jdbcUrl);
    config.setUsername(mysql.username());
    config.setPassword(mysql.password());
    config.setConnectionTestQuery("SELECT 1");
    logger.debug("MySQL JDBC URL: " + jdbcUrl);
  }
}
