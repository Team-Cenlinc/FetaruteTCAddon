package org.fetarute.fetaruteTCAddon.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.storage.dialect.MySqlDialect;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqlDialect;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqliteDialect;
import org.fetarute.fetaruteTCAddon.storage.provider.UnavailableStorageProvider;
import org.fetarute.fetaruteTCAddon.storage.schema.StorageSchema;
import org.fetarute.fetaruteTCAddon.utils.LoggerManager;

/**
 * 管理存储后端生命周期：读取配置、选择 SQL 方言、生成 schema 与创建 StorageProvider。
 *
 * <p>目前 provider 仍为占位实现，但暴露 readiness 标记便于命令层做友好提示。
 */
public final class StorageManager {

  private final FetaruteTCAddon plugin;
  private final LoggerManager logger;
  private ConfigManager.StorageSettings storageSettings;
  private StorageSchema storageSchema = new StorageSchema();
  private SqlDialect dialect = new SqliteDialect();
  private StorageProvider storageProvider;

  public StorageManager(FetaruteTCAddon plugin, LoggerManager logger) {
    this.plugin = plugin;
    this.logger = logger;
  }

  /**
   * 应用最新配置，后续可在此初始化连接或迁移。
   *
   * @param configView 已解析的配置视图
   */
  public void apply(ConfigManager.ConfigView configView) {
    this.storageSettings = configView.storageSettings();
    this.dialect = resolveDialect(storageSettings.backend());
    this.storageSchema = resolveSchema(storageSettings);
    bootstrapProvider();
    applySchemaIfReady();
    logCurrentBackend();
  }

  public ConfigManager.StorageBackend backend() {
    if (storageSettings == null) {
      return ConfigManager.StorageBackend.SQLITE;
    }
    return storageSettings.backend();
  }

  public ConfigManager.SqliteSettings sqliteSettings() {
    return storageSettings == null ? null : storageSettings.sqliteSettings();
  }

  public Optional<ConfigManager.MySqlSettings> mySqlSettings() {
    if (storageSettings == null) {
      return Optional.empty();
    }
    return storageSettings.mySqlSettings();
  }

  public StorageSchema schema() {
    return storageSchema;
  }

  public SqlDialect dialect() {
    return dialect;
  }

  public Optional<StorageProvider> provider() {
    return Optional.ofNullable(storageProvider);
  }

  public boolean isReady() {
    return storageProvider != null && !(storageProvider instanceof UnavailableStorageProvider);
  }

  public void shutdown() {
    closeProviderQuietly();
  }

  private void logCurrentBackend() {
    if (storageSettings == null) {
      logger.warn("存储后端尚未配置，跳过状态输出");
      return;
    }
    if (storageSettings.backend() == ConfigManager.StorageBackend.MYSQL) {
      storageSettings
          .mySqlSettings()
          .ifPresent(
              settings ->
                  logger.debug(
                      "存储后端: mysql @"
                          + settings.address()
                          + ":"
                          + settings.port()
                          + "/"
                          + settings.database()
                          + " prefix="
                          + settings.tablePrefix()
                          + " dialect="
                          + dialect.name()));
    } else {
      logger.debug(
          "存储后端: sqlite file="
              + storageSettings.sqliteSettings().file()
              + " dialect="
              + dialect.name());
    }
    if (!isReady()) {
      logger.warn("当前存储后端尚未初始化完毕，命令层写入操作会暂时不可用");
    }
  }

  private StorageSchema resolveSchema(ConfigManager.StorageSettings settings) {
    Optional<ConfigManager.MySqlSettings> mysql = settings.mySqlSettings();
    if (mysql.isPresent()) {
      return new StorageSchema(mysql.get().tablePrefix());
    }
    return new StorageSchema();
  }

  private SqlDialect resolveDialect(ConfigManager.StorageBackend backend) {
    return switch (backend) {
      case MYSQL -> new MySqlDialect();
      case SQLITE -> new SqliteDialect();
    };
  }

  private void bootstrapProvider() {
    closeProviderQuietly();
    File dataFolder =
        plugin == null
            ? new File(System.getProperty("java.io.tmpdir"), "FetaruteTCAddon")
            : plugin.getDataFolder();
    this.storageProvider =
        StorageProviderFactory.create(storageSettings, storageSchema, dialect, logger, dataFolder);
  }

  private void applySchemaIfReady() {
    if (!(storageProvider
        instanceof org.fetarute.fetaruteTCAddon.storage.jdbc.JdbcStorageProvider jdbcProvider)) {
      return;
    }
    try (var connection = jdbcProvider.dataSource().getConnection();
        var statement = connection.createStatement()) {
      for (String sql : storageSchema.statements(dialect)) {
        statement.execute(sql);
      }
      applyCompatibilityMigrations(connection);
      if (!connection.getAutoCommit()) {
        connection.commit();
      }
    } catch (SQLException | RuntimeException ex) {
      logger.warn("初始化数据库表失败: " + ex.getMessage());
      storageProvider =
          new UnavailableStorageProvider("Schema initialization failed: " + ex.getMessage());
    }
  }

  /**
   * 兼容性迁移：修正历史字符串枚举值，避免升级后旧数据无法被解析。
   *
   * <p>当前包含：
   *
   * <ul>
   *   <li>routes.operation_type 列补齐（默认 OPERATION）
   *   <li>RoutePatternType 历史值迁移
   * </ul>
   *
   * <ul>
   *   <li>SEMI_EXPRESS → RAPID
   *   <li>LTD_EXPRESS → LIMITED_EXPRESS
   * </ul>
   */
  private void applyCompatibilityMigrations(Connection connection) throws SQLException {
    ensureRailGraphSnapshotSignatureColumn(connection);
    ensureRailEdgeFootprintColumn(connection);
    ensureRouteOperationTypeColumn(connection);
    migrateRoutePatternTypeEnums(connection);
    ensureTimetableDutyConsistColumn(connection);
    ensurePidsScreenPagesColumn(connection);
    migrateLicenseClassIds(connection);
  }

  /**
   * 兼容性迁移：驾驶证等级改名（free → learner，dispatch → driver），驾驶证与练习次数两张表一起改。
   *
   * <p>只做一次：两张表里已有新 ID 的记录就说明迁移过了，不再改——以后若有人自定义了叫 free、dispatch 的等级，它的记录不会被每次启动改名。 同一玩家已有新 ID
   * 的记录时跳过那一行（主键冲突），旧行留着也不会被认出。
   */
  private void migrateLicenseClassIds(java.sql.Connection connection) {
    List<String> tables = List.of("drive_licenses", "drive_license_training");
    for (String raw : tables) {
      String sql =
          org.fetarute.fetaruteTCAddon.drive.license.LicenseClassIdMigration.migratedSql(
              storageSchema.tablePrefix() + raw);
      try (var statement = connection.prepareStatement(sql)) {
        int i = 1;
        for (String renamed :
            org.fetarute.fetaruteTCAddon.drive.license.LicenseClassIdMigration.RENAMED.values()) {
          statement.setString(i++, renamed);
        }
        try (var result = statement.executeQuery()) {
          if (result.next() && result.getLong(1) > 0) {
            return;
          }
        }
      } catch (Exception ex) {
        logger.warn("检查驾驶证等级迁移失败: " + raw + ": " + ex.getMessage());
        return;
      }
    }
    for (String raw : tables) {
      String table = storageSchema.tablePrefix() + raw;
      for (Map.Entry<String, String> rename :
          org.fetarute.fetaruteTCAddon.drive.license.LicenseClassIdMigration.RENAMED.entrySet()) {
        String sql =
            org.fetarute.fetaruteTCAddon.drive.license.LicenseClassIdMigration.renameSql(table);
        try (var statement = connection.prepareStatement(sql)) {
          statement.setString(1, rename.getValue());
          statement.setString(2, rename.getKey());
          statement.setString(3, rename.getValue());
          int updated = statement.executeUpdate();
          if (updated > 0) {
            logger.info(
                "已应用兼容性迁移: "
                    + raw
                    + ".class_id "
                    + rename.getKey()
                    + " -> "
                    + rename.getValue()
                    + "（"
                    + updated
                    + " 行）");
          }
        } catch (Exception ex) {
          logger.warn(
              "应用兼容性迁移失败: " + raw + ".class_id " + rename.getKey() + ": " + ex.getMessage());
        }
      }
    }
  }

  /** 兼容性迁移：为旧版 pids_screens 补齐 page_layouts 列（组合翻页的其余布局；旧表为空，只用主布局）。 */
  private void ensurePidsScreenPagesColumn(java.sql.Connection connection) {
    String screensTable = storageSchema.tablePrefix() + "pids_screens";
    String sql = "ALTER TABLE " + screensTable + " ADD COLUMN page_layouts " + dialect.jsonType();
    try (var statement = connection.createStatement()) {
      statement.executeUpdate(sql);
      logger.debug("已应用兼容性迁移: pids_screens.page_layouts (added)");
    } catch (java.sql.SQLException ex) {
      String message =
          ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(java.util.Locale.ROOT);
      if (message.contains("duplicate") || message.contains("already exists")) {
        return;
      }
      logger.warn("应用兼容性迁移失败: 添加 pids_screens.page_layouts: " + ex.getMessage());
    } catch (Exception ex) {
      logger.warn("应用兼容性迁移失败: 添加 pids_screens.page_layouts: " + ex.getMessage());
    }
  }

  /** 兼容性迁移：为旧版 timetable_duties 补齐 consist_key 列（交路的车型；旧表为空，出车按 route 的编组）。 */
  private void ensureTimetableDutyConsistColumn(java.sql.Connection connection) {
    String dutiesTable = storageSchema.tablePrefix() + "timetable_duties";
    String sql = "ALTER TABLE " + dutiesTable + " ADD COLUMN consist_key " + dialect.stringType();
    try (var statement = connection.createStatement()) {
      statement.executeUpdate(sql);
      logger.debug("已应用兼容性迁移: timetable_duties.consist_key (added)");
    } catch (java.sql.SQLException ex) {
      String message =
          ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(java.util.Locale.ROOT);
      if (message.contains("duplicate") || message.contains("already exists")) {
        return;
      }
      logger.warn("应用兼容性迁移失败: 添加 timetable_duties.consist_key: " + ex.getMessage());
    } catch (Exception ex) {
      logger.warn("应用兼容性迁移失败: 添加 timetable_duties.consist_key: " + ex.getMessage());
    }
  }

  /** 兼容性迁移：为旧版 rail_graph_snapshots 补齐 node_signature 列。 */
  private void ensureRailGraphSnapshotSignatureColumn(java.sql.Connection connection) {
    String snapshotsTable = storageSchema.tablePrefix() + "rail_graph_snapshots";
    String sql =
        "ALTER TABLE "
            + snapshotsTable
            + " ADD COLUMN node_signature "
            + dialect.stringType()
            + " NOT NULL DEFAULT ''";
    try (var statement = connection.createStatement()) {
      statement.executeUpdate(sql);
      logger.debug("已应用兼容性迁移: rail_graph_snapshots.node_signature (added)");
    } catch (java.sql.SQLException ex) {
      String message =
          ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(java.util.Locale.ROOT);
      if (message.contains("duplicate") || message.contains("already exists")) {
        return;
      }
      logger.warn("应用兼容性迁移失败: 添加 rail_graph_snapshots.node_signature: " + ex.getMessage());
    } catch (Exception ex) {
      logger.warn("应用兼容性迁移失败: 添加 rail_graph_snapshots.node_signature: " + ex.getMessage());
    }
  }

  /**
   * 兼容性迁移：为旧版 rail_edges 表补齐 footprint_json 列。
   *
   * <p>旧版表不持久化逐边足迹：它只在图构建时存在 （`RailEdge` 不带它，`RailInterlockingState.from(...)` 建完索引就消费掉了）。
   * 没有这一列，每次从快照恢复图，cell→edge 索引必然为空、{@code cellCoverageAvailable()} 为假， 一切以实测覆盖为放行条件的机制（尾部保护释放 /
   * Phase 4）全部 fail-closed 到一个都不放。
   *
   * <p>附加式、幂等，与既有两处迁移同形：加列失败且原因是"已存在"时静默返回。
   */
  private void ensureRailEdgeFootprintColumn(java.sql.Connection connection) {
    String edgesTable = storageSchema.tablePrefix() + "rail_edges";
    String sql =
        "ALTER TABLE "
            + edgesTable
            + " ADD COLUMN footprint_json "
            + dialect.stringType()
            + " NOT NULL DEFAULT ''";
    try (var statement = connection.createStatement()) {
      statement.executeUpdate(sql);
      logger.debug("已应用兼容性迁移: rail_edges.footprint_json (added)");
    } catch (java.sql.SQLException ex) {
      String message =
          ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(java.util.Locale.ROOT);
      if (message.contains("duplicate") || message.contains("already exists")) {
        return;
      }
      logger.warn("应用兼容性迁移失败: 添加 rail_edges.footprint_json: " + ex.getMessage());
    } catch (Exception ex) {
      logger.warn("应用兼容性迁移失败: 添加 rail_edges.footprint_json: " + ex.getMessage());
    }
  }

  /** 兼容性迁移：为旧版 routes 表补齐 operation_type 列。 */
  private void ensureRouteOperationTypeColumn(java.sql.Connection connection) {
    String routesTable = storageSchema.tablePrefix() + "routes";
    String sql =
        "ALTER TABLE "
            + routesTable
            + " ADD COLUMN operation_type "
            + dialect.stringType()
            + " NOT NULL DEFAULT 'OPERATION'";
    try (var statement = connection.createStatement()) {
      statement.executeUpdate(sql);
      logger.debug("已应用兼容性迁移: routes.operation_type (added)");
    } catch (java.sql.SQLException ex) {
      String message =
          ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(java.util.Locale.ROOT);
      if (message.contains("duplicate") || message.contains("already exists")) {
        return;
      }
      logger.warn("应用兼容性迁移失败: 添加 routes.operation_type: " + ex.getMessage());
    } catch (Exception ex) {
      logger.warn("应用兼容性迁移失败: 添加 routes.operation_type: " + ex.getMessage());
    }
  }

  private void migrateRoutePatternTypeEnums(java.sql.Connection connection) {
    try (var statement = connection.createStatement()) {
      String routesTable = storageSchema.tablePrefix() + "routes";
      int updatedSemi =
          statement.executeUpdate(
              "UPDATE "
                  + routesTable
                  + " SET pattern_type = 'RAPID' WHERE UPPER(pattern_type) = 'SEMI_EXPRESS'");
      int updatedLtd =
          statement.executeUpdate(
              "UPDATE "
                  + routesTable
                  + " SET pattern_type = 'LIMITED_EXPRESS' WHERE UPPER(pattern_type) = 'LTD_EXPRESS'");
      if (updatedSemi > 0 || updatedLtd > 0) {
        logger.debug(
            "已应用兼容性迁移: routes.pattern_type (SEMI_EXPRESS->RAPID="
                + updatedSemi
                + ", LTD_EXPRESS->LIMITED_EXPRESS="
                + updatedLtd
                + ")");
      }
    } catch (Exception ex) {
      logger.warn("应用兼容性迁移失败: routes.pattern_type: " + ex.getMessage());
    }
  }

  private void closeProviderQuietly() {
    if (storageProvider == null) {
      return;
    }
    try {
      storageProvider.close();
    } catch (Exception ex) {
      logger.warn("关闭存储后端时出错: " + ex.getMessage());
    } finally {
      storageProvider = null;
    }
  }
}
