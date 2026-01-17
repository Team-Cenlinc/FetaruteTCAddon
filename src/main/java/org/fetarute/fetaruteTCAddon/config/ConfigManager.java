package org.fetarute.fetaruteTCAddon.config;

import java.util.Locale;
import java.util.Optional;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;

/**
 * 负责读取并缓存 config.yml，统一暴露调试开关、调度图相关配置与存储后端配置。
 *
 * <p>注意：配置文件由 {@code ConfigUpdater} 负责“补缺失键 + 备份后写回”，因此 {@code config-version} 与新增键会随内置模板升级。
 */
public final class ConfigManager {

  private static final int EXPECTED_CONFIG_VERSION = 4;
  private static final String DEFAULT_LOCALE = "zh_CN";
  private static final double DEFAULT_GRAPH_SPEED_BLOCKS_PER_SECOND = 8.0;
  private static final String DEFAULT_AUTOSTATION_DOOR_CLOSE_SOUND = "BLOCK_NOTE_BLOCK_BELL";
  private static final float DEFAULT_AUTOSTATION_DOOR_CLOSE_VOLUME = 1.0f;
  private static final float DEFAULT_AUTOSTATION_DOOR_CLOSE_PITCH = 1.2f;
  private static final int DEFAULT_DISPATCH_TICK_INTERVAL = 10;
  private static final int DEFAULT_OCCUPANCY_LOOKAHEAD_EDGES = 2;
  private static final double DEFAULT_APPROACH_SPEED_BPS = 4.0;
  private static final double DEFAULT_CAUTION_SPEED_BPS = 6.0;
  private static final double DEFAULT_EMU_ACCEL_BPS2 = 0.8;
  private static final double DEFAULT_EMU_DECEL_BPS2 = 1.0;
  private static final double DEFAULT_DMU_ACCEL_BPS2 = 0.7;
  private static final double DEFAULT_DMU_DECEL_BPS2 = 0.9;
  private static final double DEFAULT_DIESEL_PP_ACCEL_BPS2 = 0.6;
  private static final double DEFAULT_DIESEL_PP_DECEL_BPS2 = 0.8;
  private static final double DEFAULT_ELECTRIC_LOCO_ACCEL_BPS2 = 0.9;
  private static final double DEFAULT_ELECTRIC_LOCO_DECEL_BPS2 = 1.1;
  private final FetaruteTCAddon plugin;
  private final java.util.logging.Logger logger;
  private ConfigView current;

  public ConfigManager(FetaruteTCAddon plugin) {
    this.plugin = plugin;
    this.logger = plugin.getLogger();
  }

  /** 重新读取磁盘配置，更新缓存。 */
  public void reload() {
    plugin.reloadConfig();
    FileConfiguration config = plugin.getConfig();
    current = parse(config, logger);
  }

  public ConfigView current() {
    return current;
  }

  /** 解析配置，供生产与测试共用。 */
  public static ConfigView parse(FileConfiguration config, java.util.logging.Logger logger) {
    int version = config.getInt("config-version", 0);
    if (version != EXPECTED_CONFIG_VERSION) {
      logger.warning(
          "config-version 不匹配，当前: " + version + "，期望: " + EXPECTED_CONFIG_VERSION + "。请备份后更新配置模板。");
    }
    boolean debugEnabled = config.getBoolean("debug.enabled", false);
    String localeTag = config.getString("locale", DEFAULT_LOCALE);
    ConfigurationSection storageSection = config.getConfigurationSection("storage");
    StorageSettings storageSettings = parseStorage(storageSection, logger);
    ConfigurationSection graphSection = config.getConfigurationSection("graph");
    GraphSettings graphSettings = parseGraph(graphSection, logger);
    ConfigurationSection autoStationSection = config.getConfigurationSection("autostation");
    AutoStationSettings autoStationSettings = parseAutoStation(autoStationSection, logger);
    ConfigurationSection runtimeSection = config.getConfigurationSection("runtime");
    RuntimeSettings runtimeSettings = parseRuntime(runtimeSection, logger);
    ConfigurationSection trainSection = config.getConfigurationSection("train");
    TrainConfigSettings trainConfigSettings = parseTrain(trainSection, logger);
    return new ConfigView(
        version,
        debugEnabled,
        localeTag,
        storageSettings,
        graphSettings,
        autoStationSettings,
        runtimeSettings,
        trainConfigSettings);
  }

  private static StorageSettings parseStorage(
      ConfigurationSection storageSection, java.util.logging.Logger logger) {
    if (storageSection == null) {
      logger.warning("缺少 storage 配置段，已回退为 SQLite");
      return new StorageSettings(
          StorageBackend.SQLITE, defaultSqlite(), Optional.empty(), defaultPool());
    }
    String rawBackend = storageSection.getString("backend", "sqlite");
    StorageBackend backend = StorageBackend.from(rawBackend);
    if (!backend.name().equalsIgnoreCase(rawBackend)) {
      logger.warning(
          "存储后端配置无效: " + rawBackend + "，已回退为 " + backend.name().toLowerCase(Locale.ROOT));
    }

    ConfigurationSection sqliteSection = storageSection.getConfigurationSection("sqlite");
    SqliteSettings sqliteSettings = parseSqlite(sqliteSection);

    ConfigurationSection mysqlSection = storageSection.getConfigurationSection("mysql");
    Optional<MySqlSettings> mySqlSettings = parseMySql(mysqlSection);

    PoolSettings poolSettings = parsePool(storageSection.getConfigurationSection("pool"));

    return new StorageSettings(backend, sqliteSettings, mySqlSettings, poolSettings);
  }

  private static GraphSettings parseGraph(
      ConfigurationSection graphSection, java.util.logging.Logger logger) {
    double defaultSpeedBlocksPerSecond = DEFAULT_GRAPH_SPEED_BLOCKS_PER_SECOND;
    if (graphSection == null) {
      return new GraphSettings(defaultSpeedBlocksPerSecond);
    }
    double speed =
        graphSection.getDouble(
            "default-speed-blocks-per-second", DEFAULT_GRAPH_SPEED_BLOCKS_PER_SECOND);
    if (!Double.isFinite(speed) || speed <= 0.0) {
      logger.warning("graph.default-speed-blocks-per-second 配置无效: " + speed + "，已回退为默认值");
      speed = DEFAULT_GRAPH_SPEED_BLOCKS_PER_SECOND;
    }
    return new GraphSettings(speed);
  }

  private static AutoStationSettings parseAutoStation(
      ConfigurationSection section, java.util.logging.Logger logger) {
    String sound = DEFAULT_AUTOSTATION_DOOR_CLOSE_SOUND;
    float volume = DEFAULT_AUTOSTATION_DOOR_CLOSE_VOLUME;
    float pitch = DEFAULT_AUTOSTATION_DOOR_CLOSE_PITCH;
    if (section != null) {
      String configured = section.getString("door-close-sound", sound);
      if (configured != null && !configured.trim().isEmpty()) {
        sound = configured.trim();
      }
      double configuredVolume = section.getDouble("door-close-sound-volume", volume);
      if (Double.isFinite(configuredVolume) && configuredVolume > 0.0) {
        volume = (float) configuredVolume;
      } else {
        logger.warning(
            "autostation.door-close-sound-volume 配置无效: " + configuredVolume + "，已回退为默认值");
      }
      double configuredPitch = section.getDouble("door-close-sound-pitch", pitch);
      if (Double.isFinite(configuredPitch) && configuredPitch > 0.0) {
        pitch = (float) configuredPitch;
      } else {
        logger.warning("autostation.door-close-sound-pitch 配置无效: " + configuredPitch + "，已回退为默认值");
      }
    }
    return new AutoStationSettings(sound, volume, pitch);
  }

  private static RuntimeSettings parseRuntime(
      ConfigurationSection section, java.util.logging.Logger logger) {
    int tickInterval = DEFAULT_DISPATCH_TICK_INTERVAL;
    int lookaheadEdges = DEFAULT_OCCUPANCY_LOOKAHEAD_EDGES;
    double approachSpeed = DEFAULT_APPROACH_SPEED_BPS;
    double cautionSpeed = DEFAULT_CAUTION_SPEED_BPS;
    if (section != null) {
      int configuredInterval = section.getInt("dispatch-tick-interval-ticks", tickInterval);
      if (configuredInterval > 0) {
        tickInterval = configuredInterval;
      } else {
        logger.warning("runtime.dispatch-tick-interval-ticks 配置无效: " + configuredInterval);
      }
      int configuredLookahead = section.getInt("lookahead-edges", lookaheadEdges);
      if (configuredLookahead > 0) {
        lookaheadEdges = configuredLookahead;
      } else {
        logger.warning("runtime.lookahead-edges 配置无效: " + configuredLookahead);
      }
      double configuredApproach = section.getDouble("approach-speed-bps", approachSpeed);
      if (Double.isFinite(configuredApproach) && configuredApproach >= 0.0) {
        approachSpeed = configuredApproach;
      } else {
        logger.warning("runtime.approach-speed-bps 配置无效: " + configuredApproach);
      }
      double configuredCaution = section.getDouble("caution-speed-bps", cautionSpeed);
      if (Double.isFinite(configuredCaution) && configuredCaution > 0.0) {
        cautionSpeed = configuredCaution;
      } else {
        logger.warning("runtime.caution-speed-bps 配置无效: " + configuredCaution);
      }
    }
    return new RuntimeSettings(tickInterval, lookaheadEdges, approachSpeed, cautionSpeed);
  }

  private static TrainConfigSettings parseTrain(
      ConfigurationSection section, java.util.logging.Logger logger) {
    ConfigurationSection types = section != null ? section.getConfigurationSection("types") : null;
    TrainTypeSettings emu = parseTrainType(types, "emu", defaultsEmu(), logger);
    TrainTypeSettings dmu = parseTrainType(types, "dmu", defaultsDmu(), logger);
    TrainTypeSettings diesel =
        parseTrainType(types, "diesel_push_pull", defaultsDieselPushPull(), logger);
    TrainTypeSettings electric =
        parseTrainType(types, "electric_loco", defaultsElectricLoco(), logger);
    String defaultType = section != null ? section.getString("default-type", "emu") : "emu";
    return new TrainConfigSettings(defaultType, emu, dmu, diesel, electric);
  }

  private static TrainTypeSettings parseTrainType(
      ConfigurationSection parent,
      String key,
      TrainTypeSettings defaults,
      java.util.logging.Logger logger) {
    if (parent == null) {
      return defaults;
    }
    ConfigurationSection section = parent.getConfigurationSection(key);
    if (section == null) {
      return defaults;
    }
    double accel = section.getDouble("accel-bps2", defaults.accelBps2());
    double decel = section.getDouble("decel-bps2", defaults.decelBps2());
    if (!Double.isFinite(accel) || accel <= 0.0) {
      logger.warning("train.types." + key + ".accel-bps2 无效，使用默认值");
      accel = defaults.accelBps2();
    }
    if (!Double.isFinite(decel) || decel <= 0.0) {
      logger.warning("train.types." + key + ".decel-bps2 无效，使用默认值");
      decel = defaults.decelBps2();
    }
    return new TrainTypeSettings(accel, decel);
  }

  private static SqliteSettings parseSqlite(ConfigurationSection sqliteSection) {
    if (sqliteSection == null) {
      return defaultSqlite();
    }
    String file = sqliteSection.getString("file", "data/fetarute.sqlite");
    return new SqliteSettings(file);
  }

  private static Optional<MySqlSettings> parseMySql(ConfigurationSection mysqlSection) {
    if (mysqlSection == null) {
      return Optional.empty();
    }
    String address = mysqlSection.getString("db_address", "127.0.0.1");
    int port = mysqlSection.getInt("db_port", 3306);
    String database = mysqlSection.getString("db_table", "fetarute_tc");
    String username = mysqlSection.getString("db_username", "fta");
    String password = mysqlSection.getString("db_password", "change-me");
    String tablePrefix = mysqlSection.getString("table_prefix", "fta_");
    MySqlSettings settings =
        new MySqlSettings(address, port, database, username, password, tablePrefix);
    return Optional.of(settings);
  }

  private static PoolSettings parsePool(ConfigurationSection poolSection) {
    if (poolSection == null) {
      return defaultPool();
    }
    int maxPoolSize = poolSection.getInt("maximum-pool-size", 5);
    long connectionTimeoutMs = poolSection.getLong("connection-timeout-ms", 30000);
    long idleTimeoutMs = poolSection.getLong("idle-timeout-ms", 600000);
    long maxLifetimeMs = poolSection.getLong("max-lifetime-ms", 1800000);
    return new PoolSettings(maxPoolSize, connectionTimeoutMs, idleTimeoutMs, maxLifetimeMs);
  }

  private static SqliteSettings defaultSqlite() {
    return new SqliteSettings("data/fetarute.sqlite");
  }

  private static PoolSettings defaultPool() {
    return new PoolSettings(5, 30000, 600000, 1800000);
  }

  /** 调试开关与存储设置的不可变视图。 */
  public record ConfigView(
      int configVersion,
      boolean debugEnabled,
      String locale,
      StorageSettings storageSettings,
      GraphSettings graphSettings,
      AutoStationSettings autoStationSettings,
      RuntimeSettings runtimeSettings,
      TrainConfigSettings trainConfigSettings) {}

  /** 调度图相关配置。 */
  public record GraphSettings(double defaultSpeedBlocksPerSecond) {
    public GraphSettings {
      if (!Double.isFinite(defaultSpeedBlocksPerSecond) || defaultSpeedBlocksPerSecond <= 0.0) {
        throw new IllegalArgumentException("defaultSpeedBlocksPerSecond 必须为正数");
      }
    }

    /**
     * 返回默认配置（与内置模板保持一致）。
     *
     * <p>默认速度用于诊断/查询命令中的 ETA 估算：ETA = shortestDistanceBlocks / defaultSpeedBlocksPerSecond。
     */
    public static GraphSettings defaults() {
      return new GraphSettings(DEFAULT_GRAPH_SPEED_BLOCKS_PER_SECOND);
    }
  }

  /** AutoStation 相关配置（默认关门提示音与音量/音高）。 */
  public record AutoStationSettings(
      String doorCloseSound, float doorCloseSoundVolume, float doorCloseSoundPitch) {}

  /** 运行时调度配置。 */
  public record RuntimeSettings(
      int dispatchTickIntervalTicks,
      int lookaheadEdges,
      double approachSpeedBps,
      double cautionSpeedBps) {
    public RuntimeSettings {
      if (dispatchTickIntervalTicks <= 0) {
        throw new IllegalArgumentException("dispatchTickIntervalTicks 必须为正数");
      }
      if (lookaheadEdges <= 0) {
        throw new IllegalArgumentException("lookaheadEdges 必须为正数");
      }
      if (!Double.isFinite(approachSpeedBps) || approachSpeedBps < 0.0) {
        throw new IllegalArgumentException("approachSpeedBps 必须为非负数");
      }
      if (!Double.isFinite(cautionSpeedBps) || cautionSpeedBps <= 0.0) {
        throw new IllegalArgumentException("cautionSpeedBps 必须为正数");
      }
    }
  }

  /** 列车类型默认配置（车种映射 + 默认类型）。 */
  public record TrainConfigSettings(
      String defaultType,
      TrainTypeSettings emu,
      TrainTypeSettings dmu,
      TrainTypeSettings dieselPushPull,
      TrainTypeSettings electricLoco) {

    public TrainConfigSettings {
      if (defaultType == null || defaultType.isBlank()) {
        defaultType = "emu";
      }
    }

    public org.fetarute.fetaruteTCAddon.dispatcher.runtime.train.TrainType defaultTrainType() {
      return org.fetarute
          .fetaruteTCAddon
          .dispatcher
          .runtime
          .train
          .TrainType
          .parse(defaultType)
          .orElse(org.fetarute.fetaruteTCAddon.dispatcher.runtime.train.TrainType.EMU);
    }

    public TrainTypeSettings forType(
        org.fetarute.fetaruteTCAddon.dispatcher.runtime.train.TrainType type) {
      if (type == null) {
        return emu;
      }
      return switch (type) {
        case EMU -> emu;
        case DMU -> dmu;
        case DIESEL_PUSH_PULL -> dieselPushPull;
        case ELECTRIC_LOCO -> electricLoco;
      };
    }
  }

  /** 单个列车类型的加减速配置（不包含巡航/警示速度）。 */
  public record TrainTypeSettings(double accelBps2, double decelBps2) {
    public TrainTypeSettings {
      if (!Double.isFinite(accelBps2) || accelBps2 <= 0.0) {
        throw new IllegalArgumentException("accelBps2 必须为正数");
      }
      if (!Double.isFinite(decelBps2) || decelBps2 <= 0.0) {
        throw new IllegalArgumentException("decelBps2 必须为正数");
      }
    }
  }

  private static TrainTypeSettings defaultsEmu() {
    return new TrainTypeSettings(DEFAULT_EMU_ACCEL_BPS2, DEFAULT_EMU_DECEL_BPS2);
  }

  private static TrainTypeSettings defaultsDmu() {
    return new TrainTypeSettings(DEFAULT_DMU_ACCEL_BPS2, DEFAULT_DMU_DECEL_BPS2);
  }

  private static TrainTypeSettings defaultsDieselPushPull() {
    return new TrainTypeSettings(DEFAULT_DIESEL_PP_ACCEL_BPS2, DEFAULT_DIESEL_PP_DECEL_BPS2);
  }

  private static TrainTypeSettings defaultsElectricLoco() {
    return new TrainTypeSettings(
        DEFAULT_ELECTRIC_LOCO_ACCEL_BPS2, DEFAULT_ELECTRIC_LOCO_DECEL_BPS2);
  }

  /** 存储后端定义。 */
  public enum StorageBackend {
    SQLITE,
    MYSQL;

    public static StorageBackend from(String raw) {
      if (raw == null) {
        return SQLITE;
      }
      try {
        return StorageBackend.valueOf(raw.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException ex) {
        return SQLITE;
      }
    }
  }

  /** 存储配置的聚合，便于 StorageManager 统一接收。 */
  public record StorageSettings(
      StorageBackend backend,
      SqliteSettings sqliteSettings,
      Optional<MySqlSettings> mySqlSettings,
      PoolSettings poolSettings) {}

  /** SQLite 配置。 */
  public record SqliteSettings(String file) {}

  /** MySQL 配置。 */
  public record MySqlSettings(
      String address,
      int port,
      String database,
      String username,
      String password,
      String tablePrefix) {}

  /** 连接池配置。 */
  public record PoolSettings(
      int maximumPoolSize,
      long connectionTimeoutMillis,
      long idleTimeoutMillis,
      long maxLifetimeMillis) {}
}
