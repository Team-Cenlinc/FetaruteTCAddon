package org.fetarute.fetaruteTCAddon.config;

import java.io.File;
import java.io.InputStream;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.SpeedCurveType;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainType;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartDispatcherMode;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartDispatcherPlannerMode;

/**
 * 负责读取并缓存 config.yml，统一暴露调试开关、调度图相关配置与存储后端配置。
 *
 * <p>注意：配置文件由 {@code ConfigUpdater} 负责“补缺失键 + 备份后写回”，因此 {@code config-version} 与新增键会随内置模板升级。
 */
public final class ConfigManager {

  private static final int EXPECTED_CONFIG_VERSION = 33;
  private static final String DEFAULT_LOCALE = "zh_CN";
  private static final double DEFAULT_GRAPH_SPEED_BLOCKS_PER_SECOND = 8.0;
  private static final int DEFAULT_GRAPH_SIGN_ANCHOR_SEARCH_RADIUS = 6;
  private static final int DEFAULT_GRAPH_SWITCHER_ANCHOR_SEARCH_RADIUS = 2;
  private static final String DEFAULT_AUTOSTATION_DOOR_CLOSE_SOUND = "BLOCK_NOTE_BLOCK_BELL";
  private static final float DEFAULT_AUTOSTATION_DOOR_CLOSE_VOLUME = 1.0f;
  private static final float DEFAULT_AUTOSTATION_DOOR_CLOSE_PITCH = 1.2f;
  private static final int DEFAULT_DISPATCH_TICK_INTERVAL = 10;
  private static final int DEFAULT_LAUNCH_COOLDOWN_TICKS = 10;
  private static final int DEFAULT_OCCUPANCY_LOOKAHEAD_EDGES = 2;
  private static final int DEFAULT_MIN_CLEAR_EDGES = 1;
  private static final int DEFAULT_REAR_GUARD_EDGES = 1;
  private static final int DEFAULT_SWITCHER_ZONE_EDGES = 3;
  private static final double DEFAULT_APPROACH_SPEED_BPS = 4.0;
  private static final double DEFAULT_APPROACH_WINDOW_BLOCKS = 96.0;
  private static final int DEFAULT_APPROACH_WINDOW_EDGES = 0;
  private static final int DEFAULT_APPROACH_TARGET_EDGES = 1;
  private static final boolean DEFAULT_HUD_BOSSBAR_ENABLED = true;
  private static final int DEFAULT_HUD_BOSSBAR_TICK_INTERVAL = 10;
  private static final Optional<String> DEFAULT_HUD_BOSSBAR_TEMPLATE = Optional.empty();
  private static final boolean DEFAULT_HUD_ACTIONBAR_ENABLED = false;
  private static final int DEFAULT_HUD_ACTIONBAR_TICK_INTERVAL = 10;
  private static final Optional<String> DEFAULT_HUD_ACTIONBAR_TEMPLATE = Optional.empty();
  private static final boolean DEFAULT_HUD_PLAYER_DISPLAY_ENABLED = false;
  private static final int DEFAULT_HUD_PLAYER_DISPLAY_TICK_INTERVAL = 10;
  private static final Optional<String> DEFAULT_HUD_PLAYER_DISPLAY_TEMPLATE = Optional.empty();
  private static final double DEFAULT_CAUTION_SPEED_BPS = 6.0;
  private static final double DEFAULT_APPROACH_DEPOT_SPEED_BPS = 3.5;
  private static final boolean DEFAULT_SPEED_CURVE_ENABLED = true;
  private static final SpeedCurveType DEFAULT_SPEED_CURVE_TYPE = SpeedCurveType.PHYSICS;
  private static final double DEFAULT_SPEED_CURVE_FACTOR = 1.0;
  private static final double DEFAULT_SPEED_CURVE_EARLY_BRAKE_BLOCKS = 0.0;
  private static final boolean DEFAULT_MOVEMENT_AUTHORITY_ENABLED = true;
  private static final double DEFAULT_MOVEMENT_AUTHORITY_STOP_MARGIN_BLOCKS = 2.0;
  private static final double DEFAULT_MOVEMENT_AUTHORITY_CAUTION_MARGIN_BLOCKS = 8.0;
  private static final double DEFAULT_SPEED_COMMAND_HYSTERESIS_BPS = 0.15;
  private static final double DEFAULT_SPEED_COMMAND_ACCEL_FACTOR = 1.0;
  private static final double DEFAULT_SPEED_COMMAND_DECEL_FACTOR = 1.0;
  private static final int DEFAULT_DISTANCE_CACHE_REFRESH_SECONDS = 3;
  private static final double DEFAULT_FAILOVER_STALL_SPEED_BPS = 0.2;
  private static final int DEFAULT_FAILOVER_STALL_TICKS = 60;
  private static final boolean DEFAULT_FAILOVER_UNREACHABLE_STOP = true;
  private static final boolean DEFAULT_CLEAR_DESTINATION_ON_HARD_STOP = true;
  private static final boolean DEFAULT_SIGNAL_ENVELOPE_ENABLED = true;
  private static final boolean DEFAULT_SIGNAL_EVENT_COALESCE = true;
  private static final int DEFAULT_SIGNAL_PROCEED_STABILITY_TICKS = 1;
  private static final int DEFAULT_MAX_ENVELOPE_BUILDS_PER_TICK = 50;
  private static final int DEFAULT_PATH_CACHE_MAX_SIZE = 4096;
  private static final int DEFAULT_STALE_QUEUE_ENTRY_TTL_SECONDS = 30;
  private static final int DEFAULT_FOLLOWING_MIN_CLEAR_BLOCKS = 2;
  private static final int DEFAULT_FOLLOWING_STOP_MARGIN_BLOCKS = 4;
  private static final SmartDispatcherMode DEFAULT_SMART_DISPATCHER_MODE =
      SmartDispatcherMode.OBSERVE_ONLY;
  private static final boolean DEFAULT_SMART_DISPATCHER_PLANNER_ENABLED = true;
  private static final SmartDispatcherPlannerMode DEFAULT_SMART_DISPATCHER_PLANNER_MODE =
      SmartDispatcherPlannerMode.OBSERVE_ONLY;
  private static final int DEFAULT_SMART_DISPATCHER_PLANNER_MAX_RESERVATION_RESOURCES = 4;

  /**
   * unlock 预约的存活时长，单位是 50ms 的信号 tick（见 {@code currentSignalTraceTick}）。
   *
   * <p>原值 60 = **3 秒**。实测「列车被放行 → 走到下一个节点」中位数 **21 秒**、p75 31 秒、p90 66 秒， 只有 **3%** 能在 3
   * 秒内完成。于是预约必然在它等待的那个移动完成之前过期：实服 2026-09-13 第四轮 68 个预约全部 2–4 秒内夭折，66 次 no-release-timeout 里 **65 次
   * {@code currentNodeChanged=true}** ——列车明明已经动了，却被 3 秒的秒表判为失败。
   *
   * <p>1200 tick = 60 秒，高于 p75。过长的代价有界（只多保留一会儿队列 priority 意图，且列车一旦推进 就会由 canonical
   * 进度判定回滚）；过短的代价是恢复层 97% 必然失败。
   */
  private static final int DEFAULT_SMART_DISPATCHER_PLANNER_RESERVATION_TTL_TICKS = 1200;

  private static final long DEFAULT_SMART_DISPATCHER_PLANNER_BLOCKER_SNAPSHOT_TTL_MS = 10_000L;
  private static final boolean DEFAULT_SMART_DISPATCHER_PLANNER_REQUIRE_SAME_DIRECTION = true;
  private static final boolean DEFAULT_SMART_DISPATCHER_PLANNER_ALLOW_REVERSE = false;
  private static final boolean DEFAULT_SMART_DISPATCHER_PLANNER_ALLOW_TURNBACK_BEFORE_BOUNDARY =
      false;
  private static final boolean DEFAULT_SMART_DISPATCHER_PLANNER_ONE_ACTIVE_RESERVATION_PER_CYCLE =
      true;
  private static final double DEFAULT_EMU_ACCEL_BPS2 = 0.8;
  private static final double DEFAULT_EMU_DECEL_BPS2 = 1.0;
  private static final double DEFAULT_DMU_ACCEL_BPS2 = 0.7;
  private static final double DEFAULT_DMU_DECEL_BPS2 = 0.9;
  private static final double DEFAULT_DIESEL_PP_ACCEL_BPS2 = 0.6;
  private static final double DEFAULT_DIESEL_PP_DECEL_BPS2 = 0.8;
  private static final double DEFAULT_ELECTRIC_LOCO_ACCEL_BPS2 = 0.9;
  private static final double DEFAULT_ELECTRIC_LOCO_DECEL_BPS2 = 1.1;
  private static final int DEFAULT_SPAWN_MAX_ATTEMPTS = 10;
  private static final long DEFAULT_SPAWN_QUEUED_TICKET_MAX_AGE_SECONDS = 86400L;
  private static final long DEFAULT_SPAWN_PENDING_LAYOVER_MAX_AGE_SECONDS = 86400L;
  private static final int DEFAULT_SPAWN_MAX_ACTIVE_TRAINS = 16;

  /**
   * 拥堵评分里“全网算满”的参考车数。
   *
   * <p>默认与 {@link #DEFAULT_SPAWN_MAX_ACTIVE_TRAINS} 相等，但它们是**两个不同的量**：
   * 前者是“网络装多少车就算满”（物理/经验量），后者是“我们允许发多少车”（策略量）。
   */
  private static final int DEFAULT_SPAWN_CONGESTION_NETWORK_REFERENCE_TRAINS = 16;

  private static final double DEFAULT_SPAWN_CONGESTION_HOLD_THRESHOLD = 0.58D;
  private static final double DEFAULT_SPAWN_CONGESTION_RELEASE_THRESHOLD = 0.48D;
  private final FetaruteTCAddon plugin;
  private final java.util.logging.Logger logger;
  private ConfigView current;

  public ConfigManager(FetaruteTCAddon plugin) {
    this.plugin = plugin;
    this.logger = plugin.getLogger();
  }

  /** 重新读取磁盘配置，更新缓存。 */
  public void reload() {
    SmartDispatcherMode previousMode =
        current == null || current.smartDispatcherSettings() == null
            ? null
            : current.smartDispatcherSettings().mode();
    plugin.reloadConfig();
    FileConfiguration config = plugin.getConfig();
    current = parse(config, logger);
    traceSmartDispatcherConfig(
        config,
        previousMode,
        current.smartDispatcherSettings().mode(),
        current.smartDispatcherSettings().plannerSettings());
  }

  /**
   * @return 当前配置快照（不可变视图）。
   */
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
    ConfigurationSection spawnSection = config.getConfigurationSection("spawn");
    SpawnSettings spawnSettings = parseSpawn(spawnSection, logger);
    ConfigurationSection trainSection = config.getConfigurationSection("train");
    TrainConfigSettings trainConfigSettings = parseTrain(trainSection, logger);
    ConfigurationSection reclaimSection = config.getConfigurationSection("reclaim");
    ReclaimSettings reclaimSettings = parseReclaim(reclaimSection, logger);
    ConfigurationSection healthSection = config.getConfigurationSection("health");
    HealthSettings healthSettings = parseHealth(healthSection, logger);
    SmartDispatcherSettings smartDispatcherSettings = parseSmartDispatcher(config, logger);
    ConfigurationSection timetableSection = config.getConfigurationSection("timetable");
    TimetableSettings timetableSettings = parseTimetable(timetableSection, logger);
    return new ConfigView(
        version,
        debugEnabled,
        localeTag,
        storageSettings,
        graphSettings,
        autoStationSettings,
        runtimeSettings,
        spawnSettings,
        trainConfigSettings,
        reclaimSettings,
        smartDispatcherSettings,
        healthSettings,
        timetableSettings);
  }

  /**
   * 解析时刻表配置段。
   *
   * <p>所有开关默认关闭：装上这个版本的插件不应该改变任何一列现有列车的行为，必须由运营方显式打开。
   */
  private static TimetableSettings parseTimetable(
      ConfigurationSection section, java.util.logging.Logger logger) {
    if (section == null) {
      return TimetableSettings.defaults();
    }
    TimetableSettings defaults = TimetableSettings.defaults();
    boolean enabled = section.getBoolean("enabled", defaults.enabled());
    boolean spawnEnabled = section.getBoolean("spawn-enabled", defaults.spawnEnabled());
    int holdMaxSeconds =
        readNonNegativeInt(
            section, "hold-max-seconds", defaults.holdMaxSeconds(), "timetable", logger);
    int assignToleranceSeconds =
        readNonNegativeInt(
            section,
            "assign-tolerance-seconds",
            defaults.assignToleranceSeconds(),
            "timetable",
            logger);
    int maxCatchUpSeconds =
        readNonNegativeInt(
            section, "max-catch-up-seconds", defaults.maxCatchUpSeconds(), "timetable", logger);
    int reloadIntervalSeconds =
        Math.max(
            1,
            readNonNegativeInt(
                section,
                "reload-interval-seconds",
                defaults.reloadIntervalSeconds(),
                "timetable",
                logger));
    int recorderFlushIntervalSeconds =
        Math.max(
            1,
            readNonNegativeInt(
                section,
                "recorder-flush-interval-seconds",
                defaults.recorderFlushIntervalSeconds(),
                "timetable",
                logger));
    String zone = section.getString("zone", defaults.zone());
    if (zone != null && !zone.isBlank()) {
      try {
        java.time.ZoneId.of(zone.trim());
      } catch (java.time.DateTimeException ex) {
        logger.warning("timetable.zone 配置无效: " + zone + "，已回退为服务器默认时区");
        zone = "";
      }
    }
    return new TimetableSettings(
        enabled,
        spawnEnabled,
        holdMaxSeconds,
        assignToleranceSeconds,
        maxCatchUpSeconds,
        reloadIntervalSeconds,
        recorderFlushIntervalSeconds,
        zone == null ? "" : zone.trim());
  }

  private static int readNonNegativeInt(
      ConfigurationSection section,
      String key,
      int fallback,
      String sectionName,
      java.util.logging.Logger logger) {
    int value = section.getInt(key, fallback);
    if (value < 0) {
      logger.warning(sectionName + "." + key + " 配置无效: " + value + "，已回退为默认值");
      return fallback;
    }
    return value;
  }

  /** 解析 Smart Dispatcher / Traffic Control Supervisor 配置段。 */
  private static SmartDispatcherSettings parseSmartDispatcher(
      FileConfiguration config, java.util.logging.Logger logger) {
    SmartDispatcherMode mode = DEFAULT_SMART_DISPATCHER_MODE;
    String rawMode = config == null ? null : config.getString("smart-dispatcher.mode", null);
    if (rawMode != null) {
      mode =
          SmartDispatcherMode.parse(rawMode)
              .orElseGet(
                  () -> {
                    logger.warning("smart-dispatcher.mode 配置无效: " + rawMode + "，已回退为 OBSERVE_ONLY");
                    return DEFAULT_SMART_DISPATCHER_MODE;
                  });
    }
    ConfigurationSection plannerSection =
        config == null ? null : config.getConfigurationSection("smart-dispatcher.planner");
    SmartDispatcherPlannerSettings plannerSettings =
        parseSmartDispatcherPlanner(plannerSection, logger);
    return new SmartDispatcherSettings(mode, plannerSettings);
  }

  /** 解析 Smart Dispatcher planner 配置段。 */
  private static SmartDispatcherPlannerSettings parseSmartDispatcherPlanner(
      ConfigurationSection section, java.util.logging.Logger logger) {
    boolean enabled = DEFAULT_SMART_DISPATCHER_PLANNER_ENABLED;
    SmartDispatcherPlannerMode mode = DEFAULT_SMART_DISPATCHER_PLANNER_MODE;
    int maxReservationResources = DEFAULT_SMART_DISPATCHER_PLANNER_MAX_RESERVATION_RESOURCES;
    int reservationTtlTicks = DEFAULT_SMART_DISPATCHER_PLANNER_RESERVATION_TTL_TICKS;
    long blockerSnapshotTtlMs = DEFAULT_SMART_DISPATCHER_PLANNER_BLOCKER_SNAPSHOT_TTL_MS;
    boolean requireSameDirection = DEFAULT_SMART_DISPATCHER_PLANNER_REQUIRE_SAME_DIRECTION;
    boolean allowReverse = DEFAULT_SMART_DISPATCHER_PLANNER_ALLOW_REVERSE;
    boolean allowTurnbackBeforeBoundary =
        DEFAULT_SMART_DISPATCHER_PLANNER_ALLOW_TURNBACK_BEFORE_BOUNDARY;
    boolean oneActiveReservationPerCycle =
        DEFAULT_SMART_DISPATCHER_PLANNER_ONE_ACTIVE_RESERVATION_PER_CYCLE;
    if (section != null) {
      enabled = section.getBoolean("enabled", enabled);
      String rawPlannerMode = section.getString("mode", null);
      if (rawPlannerMode != null) {
        mode =
            SmartDispatcherPlannerMode.parse(rawPlannerMode)
                .orElseGet(
                    () -> {
                      logger.warning(
                          "smart-dispatcher.planner.mode 配置无效: "
                              + rawPlannerMode
                              + "，已回退为 OBSERVE_ONLY");
                      return DEFAULT_SMART_DISPATCHER_PLANNER_MODE;
                    });
      }
      maxReservationResources =
          section.getInt("max-reservation-resources", maxReservationResources);
      reservationTtlTicks = section.getInt("reservation-ttl-ticks", reservationTtlTicks);
      blockerSnapshotTtlMs = section.getLong("blocker-snapshot-ttl-ms", blockerSnapshotTtlMs);
      requireSameDirection = section.getBoolean("require-same-direction", requireSameDirection);
      allowReverse = section.getBoolean("allow-reverse", allowReverse);
      allowTurnbackBeforeBoundary =
          section.getBoolean("allow-turnback-before-boundary", allowTurnbackBeforeBoundary);
      oneActiveReservationPerCycle =
          section.getBoolean("one-active-reservation-per-cycle", oneActiveReservationPerCycle);
    }
    if (maxReservationResources <= 0) {
      logger.warning(
          "smart-dispatcher.planner.max-reservation-resources 配置无效: "
              + maxReservationResources
              + "，已回退为 "
              + DEFAULT_SMART_DISPATCHER_PLANNER_MAX_RESERVATION_RESOURCES);
      maxReservationResources = DEFAULT_SMART_DISPATCHER_PLANNER_MAX_RESERVATION_RESOURCES;
    }
    if (reservationTtlTicks <= 0) {
      logger.warning(
          "smart-dispatcher.planner.reservation-ttl-ticks 配置无效: "
              + reservationTtlTicks
              + "，已回退为 "
              + DEFAULT_SMART_DISPATCHER_PLANNER_RESERVATION_TTL_TICKS);
      reservationTtlTicks = DEFAULT_SMART_DISPATCHER_PLANNER_RESERVATION_TTL_TICKS;
    }
    if (blockerSnapshotTtlMs <= 0) {
      logger.warning(
          "smart-dispatcher.planner.blocker-snapshot-ttl-ms 配置无效: "
              + blockerSnapshotTtlMs
              + "，已回退为 "
              + DEFAULT_SMART_DISPATCHER_PLANNER_BLOCKER_SNAPSHOT_TTL_MS);
      blockerSnapshotTtlMs = DEFAULT_SMART_DISPATCHER_PLANNER_BLOCKER_SNAPSHOT_TTL_MS;
    }
    return new SmartDispatcherPlannerSettings(
        enabled,
        mode,
        maxReservationResources,
        reservationTtlTicks,
        blockerSnapshotTtlMs,
        requireSameDirection,
        allowReverse,
        allowTurnbackBeforeBoundary,
        oneActiveReservationPerCycle);
  }

  private void traceSmartDispatcherConfig(
      FileConfiguration config,
      SmartDispatcherMode previousMode,
      SmartDispatcherMode effectiveMode,
      SmartDispatcherPlannerSettings plannerSettings) {
    String rawMode = config == null ? null : config.getString("smart-dispatcher.mode", null);
    boolean defaultUsed = rawMode == null || SmartDispatcherMode.parse(rawMode).isEmpty();
    String sourceFile = new File(plugin.getDataFolder(), "config.yml").getAbsolutePath();
    String raw = rawMode == null || rawMode.isBlank() ? "<missing>" : rawMode.trim();
    SmartDispatcherMode mode =
        effectiveMode == null ? DEFAULT_SMART_DISPATCHER_MODE : effectiveMode;
    logger.info(
        "SMART_DISPATCHER_CONFIG_LOADED rawConfigValue="
            + raw
            + " effectiveMode="
            + mode
            + " configPath=smart-dispatcher.mode sourceFile="
            + sourceFile
            + " defaultUsed="
            + defaultUsed);
    logger.info(
        "SMART_DISPATCHER_MODE_EFFECTIVE rawConfigValue="
            + raw
            + " effectiveMode="
            + mode
            + " configPath=smart-dispatcher.mode sourceFile="
            + sourceFile
            + " defaultUsed="
            + defaultUsed);
    if (previousMode != null && previousMode != mode) {
      logger.info(
          "SMART_DISPATCHER_MODE_CHANGED previousMode="
              + previousMode
              + " effectiveMode="
              + mode
              + " rawConfigValue="
              + raw
              + " configPath=smart-dispatcher.mode sourceFile="
              + sourceFile
              + " defaultUsed="
              + defaultUsed);
    }
    logger.info(
        smartDispatcherBuildFingerprintTrace(
            plugin.getDescription().getVersion(),
            buildInfoProperty("gitCommit"),
            buildInfoProperty("buildTime").or(() -> buildInfoProperty("buildId")),
            pluginJarPath(),
            sourceFile,
            mode,
            plannerSettings,
            "file",
            smartDispatcherDefaultUsedFlags(config)));
    logger.info(
        smartRuntimeBuildFingerprintTrace(
            plugin.getDescription().getVersion(),
            buildInfoProperty("gitCommit"),
            buildInfoProperty("buildTime").or(() -> buildInfoProperty("buildId"))));
    logger.info(smartDispatcherPlannerConfigTrace(plannerSettings));
  }

  static String smartDispatcherBuildFingerprintTrace(
      String pluginVersion,
      Optional<String> gitCommit,
      Optional<String> buildTime,
      String jarPath,
      String configPath,
      SmartDispatcherMode smartDispatcherMode,
      SmartDispatcherPlannerSettings plannerSettings,
      String configSource,
      String defaultUsedFlags) {
    SmartDispatcherPlannerSettings planner =
        plannerSettings == null ? SmartDispatcherPlannerSettings.defaults() : plannerSettings;
    SmartDispatcherMode mode =
        smartDispatcherMode == null ? DEFAULT_SMART_DISPATCHER_MODE : smartDispatcherMode;
    return "SMART_DISPATCH_BUILD_FINGERPRINT pluginVersion="
        + safeTraceValue(pluginVersion, "unknown")
        + " gitCommit="
        + gitCommit.filter(value -> !value.isBlank()).orElse("unknown")
        + " buildTime="
        + buildTime.filter(value -> !value.isBlank()).orElse("unknown")
        + " jarPath="
        + safeTraceValue(jarPath, "unknown")
        + " configPath="
        + safeTraceValue(configPath, "unknown")
        + " smartDispatcherMode="
        + mode
        + " plannerEnabled="
        + planner.enabled()
        + " plannerMode="
        + planner.mode()
        + " blockerSnapshotTtlMs="
        + planner.blockerSnapshotTtlMs()
        + " maxReservationResources="
        + planner.maxReservationResources()
        + " requireSameDirection="
        + planner.requireSameDirection()
        + " allowReverse="
        + planner.allowReverse()
        + " allowTurnbackBeforeBoundary="
        + planner.allowTurnbackBeforeBoundary()
        + " oneActiveReservationPerCycle="
        + planner.oneActiveReservationPerCycle()
        + " configSource="
        + safeTraceValue(configSource, "unknown")
        + " defaultUsedFlags="
        + safeTraceValue(defaultUsedFlags, "-");
  }

  /**
   * 输出运行时补丁级别指纹，便于现场日志证明正在运行的 jar 已包含关键状态机修复。
   *
   * <p>该 trace 独立于 planner 配置：它描述的是本 jar 编译进来的运行时语义，避免把“配置已开启”误读成“代码已部署”。
   */
  static String smartRuntimeBuildFingerprintTrace(
      String pluginVersion, Optional<String> gitCommit, Optional<String> buildTime) {
    return "SMART_RUNTIME_BUILD_FINGERPRINT pluginVersion="
        + safeTraceValue(pluginVersion, "unknown")
        + " gitCommit="
        + gitCommit.filter(value -> !value.isBlank()).orElse("unknown")
        + " buildTime="
        + buildTime.filter(value -> !value.isBlank()).orElse("unknown")
        + " dispatcherPatchLevel=P2_PHYSICAL_TOPOLOGY_QUERY_INDEX"
        + " physicalInterlockingFootprint=true"
        + " liveFootprintReverseIndex=true"
        + " startupOccupancyReconstruction=true"
        + " recoverableHoldContainsRouteStopOrTerminal=true";
  }

  private Optional<String> buildInfoProperty(String key) {
    try (InputStream in = plugin.getResource("build-info.properties")) {
      if (in == null) {
        return Optional.empty();
      }
      Properties properties = new Properties();
      properties.load(in);
      return Optional.ofNullable(properties.getProperty(key)).map(String::trim);
    } catch (RuntimeException | java.io.IOException ignored) {
      return Optional.empty();
    }
  }

  private String pluginJarPath() {
    try {
      if (plugin.getClass().getProtectionDomain() == null
          || plugin.getClass().getProtectionDomain().getCodeSource() == null
          || plugin.getClass().getProtectionDomain().getCodeSource().getLocation() == null) {
        return "unknown";
      }
      return plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toExternalForm();
    } catch (RuntimeException ignored) {
      return "unknown";
    }
  }

  private static String smartDispatcherDefaultUsedFlags(FileConfiguration config) {
    return "mode="
        + missingOrInvalidSmartDispatcherMode(config)
        + ",planner.enabled="
        + !containsPath(config, "smart-dispatcher.planner.enabled")
        + ",planner.mode="
        + missingOrInvalidPlannerMode(config)
        + ",planner.blocker-snapshot-ttl-ms="
        + !containsPath(config, "smart-dispatcher.planner.blocker-snapshot-ttl-ms")
        + ",planner.max-reservation-resources="
        + !containsPath(config, "smart-dispatcher.planner.max-reservation-resources")
        + ",planner.require-same-direction="
        + !containsPath(config, "smart-dispatcher.planner.require-same-direction")
        + ",planner.allow-reverse="
        + !containsPath(config, "smart-dispatcher.planner.allow-reverse")
        + ",planner.allow-turnback-before-boundary="
        + !containsPath(config, "smart-dispatcher.planner.allow-turnback-before-boundary")
        + ",planner.one-active-reservation-per-cycle="
        + !containsPath(config, "smart-dispatcher.planner.one-active-reservation-per-cycle");
  }

  private static boolean missingOrInvalidSmartDispatcherMode(FileConfiguration config) {
    String raw = config == null ? null : config.getString("smart-dispatcher.mode", null);
    return raw == null || SmartDispatcherMode.parse(raw).isEmpty();
  }

  private static boolean missingOrInvalidPlannerMode(FileConfiguration config) {
    String raw = config == null ? null : config.getString("smart-dispatcher.planner.mode", null);
    return raw == null || SmartDispatcherPlannerMode.parse(raw).isEmpty();
  }

  private static boolean containsPath(FileConfiguration config, String path) {
    return config != null && config.contains(path);
  }

  private static String safeTraceValue(String value, String fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return value.trim().replace(' ', '_');
  }

  static String smartDispatcherPlannerConfigTrace(SmartDispatcherPlannerSettings plannerSettings) {
    SmartDispatcherPlannerSettings planner =
        plannerSettings == null ? SmartDispatcherPlannerSettings.defaults() : plannerSettings;
    return "SMART_DISPATCH_PLANNER_CONFIG_LOADED enabled="
        + planner.enabled()
        + " mode="
        + planner.mode()
        + " maxReservationResources="
        + planner.maxReservationResources()
        + " ttlTicks="
        + planner.reservationTtlTicks()
        + " blockerSnapshotTtlMs="
        + planner.blockerSnapshotTtlMs()
        + " requireSameDirection="
        + planner.requireSameDirection()
        + " allowReverse="
        + planner.allowReverse()
        + " allowTurnbackBeforeBoundary="
        + planner.allowTurnbackBeforeBoundary()
        + " oneActiveReservationPerCycle="
        + planner.oneActiveReservationPerCycle();
  }

  /** 解析 health 配置段。 */
  private static HealthSettings parseHealth(
      ConfigurationSection section, java.util.logging.Logger logger) {
    boolean enabled = true;
    int checkIntervalSeconds = 5;
    boolean autoFixEnabled = true;
    int stallThresholdSeconds = 30;
    int progressStuckThresholdSeconds = 60;
    int progressStopGraceSeconds = 60;
    int deadlockThresholdSeconds = 45;
    int deadlockDestroyThresholdSeconds = 60;
    boolean trainCleanupEnabled = false;
    int deadlockDestroyCooldownSeconds = 120;
    int stuckCleanupThresholdSeconds = 600;
    int stuckCleanupPassengerThresholdSeconds = 1800;
    int stuckCleanupCooldownSeconds = 120;
    int deadlockEpisodeGraceSeconds = 15;
    int deadlockMinStopSeconds = 20;
    int blockerSnapshotMaxAgeSeconds = 20;
    int recoveryCooldownSeconds = 10;
    int occupancyTimeoutMinutes = 10;
    boolean orphanCleanupEnabled = true;
    boolean timeoutCleanupEnabled = true;

    if (section != null) {
      enabled = section.getBoolean("enabled", enabled);
      checkIntervalSeconds = section.getInt("check-interval-seconds", checkIntervalSeconds);
      if (checkIntervalSeconds <= 0) {
        logger.warning("health.check-interval-seconds 配置无效: " + checkIntervalSeconds);
        checkIntervalSeconds = 5;
      }
      autoFixEnabled = section.getBoolean("auto-fix-enabled", autoFixEnabled);
      stallThresholdSeconds = section.getInt("stall-threshold-seconds", stallThresholdSeconds);
      if (stallThresholdSeconds <= 0) {
        logger.warning("health.stall-threshold-seconds 配置无效: " + stallThresholdSeconds);
        stallThresholdSeconds = 30;
      }
      progressStuckThresholdSeconds =
          section.getInt("progress-stuck-threshold-seconds", progressStuckThresholdSeconds);
      if (progressStuckThresholdSeconds <= 0) {
        logger.warning(
            "health.progress-stuck-threshold-seconds 配置无效: " + progressStuckThresholdSeconds);
        progressStuckThresholdSeconds = 60;
      }
      progressStopGraceSeconds =
          section.getInt("progress-stop-grace-seconds", progressStopGraceSeconds);
      if (progressStopGraceSeconds <= 0) {
        logger.warning("health.progress-stop-grace-seconds 配置无效: " + progressStopGraceSeconds);
        progressStopGraceSeconds = 60;
      }
      deadlockThresholdSeconds =
          section.getInt("deadlock-threshold-seconds", deadlockThresholdSeconds);
      if (deadlockThresholdSeconds <= 0) {
        logger.warning("health.deadlock-threshold-seconds 配置无效: " + deadlockThresholdSeconds);
        deadlockThresholdSeconds = 45;
      }
      deadlockDestroyThresholdSeconds =
          section.getInt("deadlock-destroy-threshold-seconds", deadlockDestroyThresholdSeconds);
      if (deadlockDestroyThresholdSeconds < 0) {
        logger.warning(
            "health.deadlock-destroy-threshold-seconds 配置无效: " + deadlockDestroyThresholdSeconds);
        deadlockDestroyThresholdSeconds = 60;
      }
      trainCleanupEnabled = section.getBoolean("deadlock-destroy-enabled", trainCleanupEnabled);
      deadlockDestroyCooldownSeconds =
          section.getInt("deadlock-destroy-cooldown-seconds", deadlockDestroyCooldownSeconds);
      if (deadlockDestroyCooldownSeconds < 0) {
        logger.warning(
            "health.deadlock-destroy-cooldown-seconds 配置无效: " + deadlockDestroyCooldownSeconds);
        deadlockDestroyCooldownSeconds = 120;
      }
      stuckCleanupThresholdSeconds =
          section.getInt("stuck-cleanup-threshold-seconds", stuckCleanupThresholdSeconds);
      if (stuckCleanupThresholdSeconds <= 0) {
        logger.warning(
            "health.stuck-cleanup-threshold-seconds 配置无效: " + stuckCleanupThresholdSeconds);
        stuckCleanupThresholdSeconds = 600;
      }
      stuckCleanupPassengerThresholdSeconds =
          section.getInt(
              "stuck-cleanup-passenger-threshold-seconds", stuckCleanupPassengerThresholdSeconds);
      if (stuckCleanupPassengerThresholdSeconds <= 0) {
        logger.warning(
            "health.stuck-cleanup-passenger-threshold-seconds 配置无效: "
                + stuckCleanupPassengerThresholdSeconds);
        stuckCleanupPassengerThresholdSeconds = 1800;
      }
      stuckCleanupPassengerThresholdSeconds =
          Math.max(stuckCleanupThresholdSeconds, stuckCleanupPassengerThresholdSeconds);
      stuckCleanupCooldownSeconds =
          section.getInt("stuck-cleanup-cooldown-seconds", stuckCleanupCooldownSeconds);
      if (stuckCleanupCooldownSeconds < 0) {
        logger.warning(
            "health.stuck-cleanup-cooldown-seconds 配置无效: " + stuckCleanupCooldownSeconds);
        stuckCleanupCooldownSeconds = 120;
      }
      deadlockEpisodeGraceSeconds =
          section.getInt("deadlock-episode-grace-seconds", deadlockEpisodeGraceSeconds);
      if (deadlockEpisodeGraceSeconds < 0) {
        logger.warning(
            "health.deadlock-episode-grace-seconds 配置无效: " + deadlockEpisodeGraceSeconds);
        deadlockEpisodeGraceSeconds = 15;
      }
      deadlockMinStopSeconds = section.getInt("deadlock-min-stop-seconds", deadlockMinStopSeconds);
      if (deadlockMinStopSeconds < 0) {
        logger.warning("health.deadlock-min-stop-seconds 配置无效: " + deadlockMinStopSeconds);
        deadlockMinStopSeconds = 20;
      }
      blockerSnapshotMaxAgeSeconds =
          section.getInt("blocker-snapshot-max-age-seconds", blockerSnapshotMaxAgeSeconds);
      if (blockerSnapshotMaxAgeSeconds <= 0) {
        logger.warning(
            "health.blocker-snapshot-max-age-seconds 配置无效: " + blockerSnapshotMaxAgeSeconds);
        blockerSnapshotMaxAgeSeconds = 20;
      }
      recoveryCooldownSeconds =
          section.getInt("recovery-cooldown-seconds", recoveryCooldownSeconds);
      if (recoveryCooldownSeconds <= 0) {
        logger.warning("health.recovery-cooldown-seconds 配置无效: " + recoveryCooldownSeconds);
        recoveryCooldownSeconds = 10;
      }
      occupancyTimeoutMinutes =
          section.getInt("occupancy-timeout-minutes", occupancyTimeoutMinutes);
      if (occupancyTimeoutMinutes <= 0) {
        logger.warning("health.occupancy-timeout-minutes 配置无效: " + occupancyTimeoutMinutes);
        occupancyTimeoutMinutes = 10;
      }
      orphanCleanupEnabled = section.getBoolean("orphan-cleanup-enabled", orphanCleanupEnabled);
      timeoutCleanupEnabled = section.getBoolean("timeout-cleanup-enabled", timeoutCleanupEnabled);
    }
    return new HealthSettings(
        enabled,
        checkIntervalSeconds,
        autoFixEnabled,
        stallThresholdSeconds,
        progressStuckThresholdSeconds,
        progressStopGraceSeconds,
        deadlockThresholdSeconds,
        deadlockDestroyThresholdSeconds,
        trainCleanupEnabled,
        deadlockDestroyCooldownSeconds,
        stuckCleanupThresholdSeconds,
        stuckCleanupPassengerThresholdSeconds,
        stuckCleanupCooldownSeconds,
        deadlockEpisodeGraceSeconds,
        deadlockMinStopSeconds,
        blockerSnapshotMaxAgeSeconds,
        recoveryCooldownSeconds,
        occupancyTimeoutMinutes,
        orphanCleanupEnabled,
        timeoutCleanupEnabled);
  }

  /** 解析 reclaim 配置段。 */
  private static ReclaimSettings parseReclaim(
      ConfigurationSection section, java.util.logging.Logger logger) {
    boolean enabled = false;
    long maxIdleSeconds = 300;
    int maxActiveTrains = 50;
    long checkIntervalSeconds = 60;
    long strandedDestroySeconds = ReclaimSettings.DEFAULT_STRANDED_DESTROY_SECONDS;

    if (section != null) {
      enabled = section.getBoolean("enabled", enabled);
      maxIdleSeconds = section.getLong("max-idle-seconds", maxIdleSeconds);
      if (maxIdleSeconds <= 0) {
        logger.warning("reclaim.max-idle-seconds 配置无效: " + maxIdleSeconds);
        maxIdleSeconds = 300;
      }
      maxActiveTrains = section.getInt("max-active-trains", maxActiveTrains);
      if (maxActiveTrains <= 0) {
        logger.warning("reclaim.max-active-trains 配置无效: " + maxActiveTrains);
        maxActiveTrains = 50;
      }
      checkIntervalSeconds = section.getLong("check-interval-seconds", checkIntervalSeconds);
      if (checkIntervalSeconds <= 0) {
        logger.warning("reclaim.check-interval-seconds 配置无效: " + checkIntervalSeconds);
        checkIntervalSeconds = 60;
      }
      strandedDestroySeconds = section.getLong("stranded-destroy-seconds", strandedDestroySeconds);
      if (strandedDestroySeconds < 0) {
        logger.warning("reclaim.stranded-destroy-seconds 配置无效: " + strandedDestroySeconds);
        strandedDestroySeconds = ReclaimSettings.DEFAULT_STRANDED_DESTROY_SECONDS;
      }
    }
    return new ReclaimSettings(
        enabled, maxIdleSeconds, maxActiveTrains, checkIntervalSeconds, strandedDestroySeconds);
  }

  /** 解析 spawn 配置段。 */
  private static SpawnSettings parseSpawn(
      ConfigurationSection section, java.util.logging.Logger logger) {
    boolean enabled = false;
    int tickIntervalTicks = 20;
    int planRefreshTicks = 200;
    int maxSpawnPerTick = 1;
    int maxGeneratePerTick = 5;
    int maxBacklogPerService = 5;
    int retryDelayTicks = 40;
    int maxAttempts = DEFAULT_SPAWN_MAX_ATTEMPTS;
    double layoverFallbackMultiplier = 2.0;
    long queuedTicketMaxAgeSeconds = DEFAULT_SPAWN_QUEUED_TICKET_MAX_AGE_SECONDS;
    long pendingLayoverMaxAgeSeconds = DEFAULT_SPAWN_PENDING_LAYOVER_MAX_AGE_SECONDS;
    int maxActiveTrains = DEFAULT_SPAWN_MAX_ACTIVE_TRAINS;
    int congestionNetworkReferenceTrains = DEFAULT_SPAWN_CONGESTION_NETWORK_REFERENCE_TRAINS;
    double congestionHoldThreshold = DEFAULT_SPAWN_CONGESTION_HOLD_THRESHOLD;
    double congestionReleaseThreshold = DEFAULT_SPAWN_CONGESTION_RELEASE_THRESHOLD;
    if (section != null) {
      enabled = section.getBoolean("enabled", enabled);
      tickIntervalTicks = section.getInt("tick-interval-ticks", tickIntervalTicks);
      if (tickIntervalTicks <= 0) {
        logger.warning("spawn.tick-interval-ticks 配置无效: " + tickIntervalTicks);
        tickIntervalTicks = 20;
      }
      planRefreshTicks = section.getInt("plan-refresh-ticks", planRefreshTicks);
      if (planRefreshTicks <= 0) {
        logger.warning("spawn.plan-refresh-ticks 配置无效: " + planRefreshTicks);
        planRefreshTicks = 200;
      }
      maxSpawnPerTick = section.getInt("max-spawn-per-tick", maxSpawnPerTick);
      if (maxSpawnPerTick <= 0) {
        logger.warning("spawn.max-spawn-per-tick 配置无效: " + maxSpawnPerTick);
        maxSpawnPerTick = 1;
      }
      maxGeneratePerTick = section.getInt("max-generate-per-tick", maxGeneratePerTick);
      if (maxGeneratePerTick <= 0) {
        logger.warning("spawn.max-generate-per-tick 配置无效: " + maxGeneratePerTick);
        maxGeneratePerTick = 5;
      }
      maxBacklogPerService = section.getInt("max-backlog-per-service", maxBacklogPerService);
      if (maxBacklogPerService <= 0) {
        logger.warning("spawn.max-backlog-per-service 配置无效: " + maxBacklogPerService);
        maxBacklogPerService = 5;
      }
      retryDelayTicks = section.getInt("retry-delay-ticks", retryDelayTicks);
      if (retryDelayTicks < 0) {
        logger.warning("spawn.retry-delay-ticks 配置无效: " + retryDelayTicks);
        retryDelayTicks = 40;
      }
      maxAttempts = section.getInt("max-attempts", maxAttempts);
      if (maxAttempts <= 0) {
        logger.warning("spawn.max-attempts 配置无效: " + maxAttempts);
        maxAttempts = DEFAULT_SPAWN_MAX_ATTEMPTS;
      }
      layoverFallbackMultiplier =
          section.getDouble("layover-fallback-multiplier", layoverFallbackMultiplier);
      if (layoverFallbackMultiplier < 0) {
        logger.warning("spawn.layover-fallback-multiplier 配置无效: " + layoverFallbackMultiplier);
        layoverFallbackMultiplier = 2.0;
      }
      queuedTicketMaxAgeSeconds =
          section.getLong("queued-ticket-max-age-seconds", queuedTicketMaxAgeSeconds);
      if (queuedTicketMaxAgeSeconds < 0L) {
        logger.warning("spawn.queued-ticket-max-age-seconds 配置无效: " + queuedTicketMaxAgeSeconds);
        queuedTicketMaxAgeSeconds = DEFAULT_SPAWN_QUEUED_TICKET_MAX_AGE_SECONDS;
      }
      pendingLayoverMaxAgeSeconds =
          section.getLong("pending-layover-max-age-seconds", pendingLayoverMaxAgeSeconds);
      if (pendingLayoverMaxAgeSeconds < 0L) {
        logger.warning(
            "spawn.pending-layover-max-age-seconds 配置无效: " + pendingLayoverMaxAgeSeconds);
        pendingLayoverMaxAgeSeconds = DEFAULT_SPAWN_PENDING_LAYOVER_MAX_AGE_SECONDS;
      }
      maxActiveTrains = section.getInt("max-active-trains", maxActiveTrains);
      if (maxActiveTrains < 0) {
        logger.warning("spawn.max-active-trains 配置无效: " + maxActiveTrains);
        maxActiveTrains = DEFAULT_SPAWN_MAX_ACTIVE_TRAINS;
      }
      congestionNetworkReferenceTrains =
          section.getInt("congestion-network-reference-trains", congestionNetworkReferenceTrains);
      if (congestionNetworkReferenceTrains < 0) {
        logger.warning(
            "spawn.congestion-network-reference-trains 配置无效: " + congestionNetworkReferenceTrains);
        congestionNetworkReferenceTrains = DEFAULT_SPAWN_CONGESTION_NETWORK_REFERENCE_TRAINS;
      }
      congestionHoldThreshold =
          section.getDouble("congestion-hold-threshold", congestionHoldThreshold);
      congestionReleaseThreshold =
          section.getDouble("congestion-release-threshold", congestionReleaseThreshold);
      if (!(congestionHoldThreshold > 0.0D) || congestionHoldThreshold > 1.0D) {
        logger.warning("spawn.congestion-hold-threshold 配置无效: " + congestionHoldThreshold);
        congestionHoldThreshold = DEFAULT_SPAWN_CONGESTION_HOLD_THRESHOLD;
      }
      if (!(congestionReleaseThreshold > 0.0D) || congestionReleaseThreshold > 1.0D) {
        logger.warning("spawn.congestion-release-threshold 配置无效: " + congestionReleaseThreshold);
        congestionReleaseThreshold = DEFAULT_SPAWN_CONGESTION_RELEASE_THRESHOLD;
      }
      if (congestionReleaseThreshold > congestionHoldThreshold) {
        // 解除阈值高于触发阈值会让闸门一进入 holding 就无法退出，直接判为配置错误。
        logger.warning(
            "spawn.congestion-release-threshold 高于 hold 阈值，已回退默认: "
                + congestionReleaseThreshold
                + " > "
                + congestionHoldThreshold);
        congestionHoldThreshold = DEFAULT_SPAWN_CONGESTION_HOLD_THRESHOLD;
        congestionReleaseThreshold = DEFAULT_SPAWN_CONGESTION_RELEASE_THRESHOLD;
      }
    }
    return new SpawnSettings(
        enabled,
        tickIntervalTicks,
        planRefreshTicks,
        maxSpawnPerTick,
        maxGeneratePerTick,
        maxBacklogPerService,
        retryDelayTicks,
        maxAttempts,
        layoverFallbackMultiplier,
        queuedTicketMaxAgeSeconds,
        pendingLayoverMaxAgeSeconds,
        maxActiveTrains,
        congestionNetworkReferenceTrains,
        congestionHoldThreshold,
        congestionReleaseThreshold);
  }

  /** 解析 storage 配置段。 */
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

  /** 解析 graph 配置段。 */
  private static GraphSettings parseGraph(
      ConfigurationSection graphSection, java.util.logging.Logger logger) {
    double defaultSpeedBlocksPerSecond = DEFAULT_GRAPH_SPEED_BLOCKS_PER_SECOND;
    int signAnchorRadius = DEFAULT_GRAPH_SIGN_ANCHOR_SEARCH_RADIUS;
    int switcherAnchorRadius = DEFAULT_GRAPH_SWITCHER_ANCHOR_SEARCH_RADIUS;
    if (graphSection == null) {
      return new GraphSettings(defaultSpeedBlocksPerSecond, signAnchorRadius, switcherAnchorRadius);
    }
    double speed =
        graphSection.getDouble(
            "default-speed-blocks-per-second", DEFAULT_GRAPH_SPEED_BLOCKS_PER_SECOND);
    if (!Double.isFinite(speed) || speed <= 0.0) {
      logger.warning("graph.default-speed-blocks-per-second 配置无效: " + speed + "，已回退为默认值");
      speed = DEFAULT_GRAPH_SPEED_BLOCKS_PER_SECOND;
    }
    int configuredSignRadius = graphSection.getInt("sign-anchor-search-radius", signAnchorRadius);
    if (configuredSignRadius >= 0) {
      signAnchorRadius = configuredSignRadius;
    } else {
      logger.warning("graph.sign-anchor-search-radius 配置无效: " + configuredSignRadius);
    }
    int configuredSwitcherRadius =
        graphSection.getInt("switcher-anchor-search-radius", switcherAnchorRadius);
    if (configuredSwitcherRadius >= 0) {
      switcherAnchorRadius = configuredSwitcherRadius;
    } else {
      logger.warning("graph.switcher-anchor-search-radius 配置无效: " + configuredSwitcherRadius);
    }
    return new GraphSettings(speed, signAnchorRadius, switcherAnchorRadius);
  }

  /** 解析 autostation 配置段。 */
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

  /** 解析 runtime 配置段。 */
  private static RuntimeSettings parseRuntime(
      ConfigurationSection section, java.util.logging.Logger logger) {
    int tickInterval = DEFAULT_DISPATCH_TICK_INTERVAL;
    int launchCooldownTicks = DEFAULT_LAUNCH_COOLDOWN_TICKS;
    int lookaheadEdges = DEFAULT_OCCUPANCY_LOOKAHEAD_EDGES;
    boolean hudBossBarEnabled = DEFAULT_HUD_BOSSBAR_ENABLED;
    int hudBossBarTickInterval = DEFAULT_HUD_BOSSBAR_TICK_INTERVAL;
    Optional<String> hudBossBarTemplate = DEFAULT_HUD_BOSSBAR_TEMPLATE;
    boolean hudActionBarEnabled = DEFAULT_HUD_ACTIONBAR_ENABLED;
    int hudActionBarTickInterval = DEFAULT_HUD_ACTIONBAR_TICK_INTERVAL;
    Optional<String> hudActionBarTemplate = DEFAULT_HUD_ACTIONBAR_TEMPLATE;
    boolean hudPlayerDisplayEnabled = DEFAULT_HUD_PLAYER_DISPLAY_ENABLED;
    int hudPlayerDisplayTickInterval = DEFAULT_HUD_PLAYER_DISPLAY_TICK_INTERVAL;
    Optional<String> hudPlayerDisplayTemplate = DEFAULT_HUD_PLAYER_DISPLAY_TEMPLATE;
    int minClearEdges = DEFAULT_MIN_CLEAR_EDGES;
    int rearGuardEdges = DEFAULT_REAR_GUARD_EDGES;
    int switcherZoneEdges = DEFAULT_SWITCHER_ZONE_EDGES;
    double approachSpeed = DEFAULT_APPROACH_SPEED_BPS;
    double cautionSpeed = DEFAULT_CAUTION_SPEED_BPS;
    double approachDepotSpeed = DEFAULT_APPROACH_DEPOT_SPEED_BPS;
    double approachWindowBlocks = DEFAULT_APPROACH_WINDOW_BLOCKS;
    int approachWindowEdges = DEFAULT_APPROACH_WINDOW_EDGES;
    int approachTargetEdges = DEFAULT_APPROACH_TARGET_EDGES;
    boolean speedCurveEnabled = DEFAULT_SPEED_CURVE_ENABLED;
    SpeedCurveType speedCurveType = DEFAULT_SPEED_CURVE_TYPE;
    double speedCurveFactor = DEFAULT_SPEED_CURVE_FACTOR;
    double speedCurveEarlyBrakeBlocks = DEFAULT_SPEED_CURVE_EARLY_BRAKE_BLOCKS;
    boolean movementAuthorityEnabled = DEFAULT_MOVEMENT_AUTHORITY_ENABLED;
    double movementAuthorityStopMarginBlocks = DEFAULT_MOVEMENT_AUTHORITY_STOP_MARGIN_BLOCKS;
    double movementAuthorityCautionMarginBlocks = DEFAULT_MOVEMENT_AUTHORITY_CAUTION_MARGIN_BLOCKS;
    double speedCommandHysteresisBps = DEFAULT_SPEED_COMMAND_HYSTERESIS_BPS;
    double speedCommandAccelFactor = DEFAULT_SPEED_COMMAND_ACCEL_FACTOR;
    double speedCommandDecelFactor = DEFAULT_SPEED_COMMAND_DECEL_FACTOR;
    int distanceCacheRefreshSeconds = DEFAULT_DISTANCE_CACHE_REFRESH_SECONDS;
    double failoverStallSpeed = DEFAULT_FAILOVER_STALL_SPEED_BPS;
    int failoverStallTicks = DEFAULT_FAILOVER_STALL_TICKS;
    boolean failoverUnreachableStop = DEFAULT_FAILOVER_UNREACHABLE_STOP;
    boolean clearDestinationOnHardStop = DEFAULT_CLEAR_DESTINATION_ON_HARD_STOP;
    boolean signalEnvelopeEnabled = DEFAULT_SIGNAL_ENVELOPE_ENABLED;
    boolean signalEventCoalesce = DEFAULT_SIGNAL_EVENT_COALESCE;
    int signalProceedStabilityTicks = DEFAULT_SIGNAL_PROCEED_STABILITY_TICKS;
    int maxEnvelopeBuildsPerTick = DEFAULT_MAX_ENVELOPE_BUILDS_PER_TICK;
    int pathCacheMaxSize = DEFAULT_PATH_CACHE_MAX_SIZE;
    int staleQueueEntryTtlSeconds = DEFAULT_STALE_QUEUE_ENTRY_TTL_SECONDS;
    int followingMinClearBlocks = DEFAULT_FOLLOWING_MIN_CLEAR_BLOCKS;
    int followingStopMarginBlocks = DEFAULT_FOLLOWING_STOP_MARGIN_BLOCKS;
    if (section != null) {
      ConfigurationSection hud = section.getConfigurationSection("hud");
      if (hud != null) {
        ConfigurationSection bossbar = hud.getConfigurationSection("bossbar");
        if (bossbar != null) {
          hudBossBarEnabled = bossbar.getBoolean("enabled", hudBossBarEnabled);
          int configuredHudInterval = bossbar.getInt("tick-interval-ticks", hudBossBarTickInterval);
          if (configuredHudInterval > 0) {
            hudBossBarTickInterval = configuredHudInterval;
          } else {
            logger.warning(
                "runtime.hud.bossbar.tick-interval-ticks 配置无效: " + configuredHudInterval);
          }
          String configuredTemplate = bossbar.getString("template");
          if (configuredTemplate != null && !configuredTemplate.isBlank()) {
            hudBossBarTemplate = Optional.of(configuredTemplate);
          }
        }
        ConfigurationSection actionbar = hud.getConfigurationSection("actionbar");
        if (actionbar != null) {
          hudActionBarEnabled = actionbar.getBoolean("enabled", hudActionBarEnabled);
          int configuredHudInterval =
              actionbar.getInt("tick-interval-ticks", hudActionBarTickInterval);
          if (configuredHudInterval > 0) {
            hudActionBarTickInterval = configuredHudInterval;
          } else {
            logger.warning(
                "runtime.hud.actionbar.tick-interval-ticks 配置无效: " + configuredHudInterval);
          }
          String configuredTemplate = actionbar.getString("template");
          if (configuredTemplate != null && !configuredTemplate.isBlank()) {
            hudActionBarTemplate = Optional.of(configuredTemplate);
          }
        }
        ConfigurationSection playerDisplay = hud.getConfigurationSection("player_display");
        if (playerDisplay != null) {
          hudPlayerDisplayEnabled = playerDisplay.getBoolean("enabled", hudPlayerDisplayEnabled);
          int configuredHudInterval =
              playerDisplay.getInt("tick-interval-ticks", hudPlayerDisplayTickInterval);
          if (configuredHudInterval > 0) {
            hudPlayerDisplayTickInterval = configuredHudInterval;
          } else {
            logger.warning(
                "runtime.hud.player_display.tick-interval-ticks 配置无效: " + configuredHudInterval);
          }
          String configuredTemplate = playerDisplay.getString("template");
          if (configuredTemplate != null && !configuredTemplate.isBlank()) {
            hudPlayerDisplayTemplate = Optional.of(configuredTemplate);
          }
        }
      }

      int configuredInterval = section.getInt("dispatch-tick-interval-ticks", tickInterval);
      if (configuredInterval > 0) {
        tickInterval = configuredInterval;
      } else {
        logger.warning("runtime.dispatch-tick-interval-ticks 配置无效: " + configuredInterval);
      }
      int configuredLaunchCooldown = section.getInt("launch-cooldown-ticks", launchCooldownTicks);
      if (configuredLaunchCooldown >= 0) {
        launchCooldownTicks = configuredLaunchCooldown;
      } else {
        logger.warning("runtime.launch-cooldown-ticks 配置无效: " + configuredLaunchCooldown);
      }
      int configuredLookahead = section.getInt("lookahead-edges", lookaheadEdges);
      if (configuredLookahead > 0) {
        lookaheadEdges = configuredLookahead;
      } else {
        logger.warning("runtime.lookahead-edges 配置无效: " + configuredLookahead);
      }
      int configuredMinClear = section.getInt("min-clear-edges", minClearEdges);
      if (configuredMinClear >= 0) {
        minClearEdges = configuredMinClear;
      } else {
        logger.warning("runtime.min-clear-edges 配置无效: " + configuredMinClear);
      }
      int configuredRearGuard = section.getInt("rear-guard-edges", rearGuardEdges);
      if (configuredRearGuard >= 0) {
        rearGuardEdges = configuredRearGuard;
      } else {
        logger.warning("runtime.rear-guard-edges 配置无效: " + configuredRearGuard);
      }
      int configuredSwitcherZone = section.getInt("switcher-zone-edges", switcherZoneEdges);
      if (configuredSwitcherZone >= 0) {
        switcherZoneEdges = configuredSwitcherZone;
      } else {
        logger.warning("runtime.switcher-zone-edges 配置无效: " + configuredSwitcherZone);
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
      double configuredDepotApproach =
          section.getDouble("approach-depot-speed-bps", approachDepotSpeed);
      if (Double.isFinite(configuredDepotApproach) && configuredDepotApproach >= 0.0) {
        approachDepotSpeed = configuredDepotApproach;
      } else {
        logger.warning("runtime.approach-depot-speed-bps 配置无效: " + configuredDepotApproach);
      }
      double configuredApproachWindowBlocks =
          section.getDouble("approach-window-blocks", approachWindowBlocks);
      if (Double.isFinite(configuredApproachWindowBlocks)
          && configuredApproachWindowBlocks >= 0.0) {
        approachWindowBlocks = configuredApproachWindowBlocks;
      } else {
        logger.warning("runtime.approach-window-blocks 配置无效: " + configuredApproachWindowBlocks);
      }
      int configuredApproachWindowEdges =
          section.getInt("approach-window-edges", approachWindowEdges);
      if (configuredApproachWindowEdges >= 0) {
        approachWindowEdges = configuredApproachWindowEdges;
      } else {
        logger.warning("runtime.approach-window-edges 配置无效: " + configuredApproachWindowEdges);
      }
      int configuredApproachTargetEdges =
          section.getInt("approach-target-edges", approachTargetEdges);
      if (configuredApproachTargetEdges >= 0) {
        approachTargetEdges = configuredApproachTargetEdges;
      } else {
        logger.warning("runtime.approach-target-edges 配置无效: " + configuredApproachTargetEdges);
      }
      boolean configuredSpeedCurve = section.getBoolean("speed-curve-enabled", speedCurveEnabled);
      speedCurveEnabled = configuredSpeedCurve;
      String rawCurveType = section.getString("speed-curve-type", speedCurveType.name());
      speedCurveType =
          SpeedCurveType.parse(rawCurveType)
              .orElseGet(
                  () -> {
                    logger.warning("runtime.speed-curve-type 配置无效: " + rawCurveType + "，已回退为默认值");
                    return DEFAULT_SPEED_CURVE_TYPE;
                  });
      double configuredSpeedCurveFactor = section.getDouble("speed-curve-factor", speedCurveFactor);
      if (Double.isFinite(configuredSpeedCurveFactor) && configuredSpeedCurveFactor > 0.0) {
        speedCurveFactor = configuredSpeedCurveFactor;
      } else {
        logger.warning("runtime.speed-curve-factor 配置无效: " + configuredSpeedCurveFactor);
      }
      double configuredSpeedCurveEarlyBrake =
          section.getDouble("speed-curve-early-brake-blocks", speedCurveEarlyBrakeBlocks);
      if (Double.isFinite(configuredSpeedCurveEarlyBrake)
          && configuredSpeedCurveEarlyBrake >= 0.0) {
        speedCurveEarlyBrakeBlocks = configuredSpeedCurveEarlyBrake;
      } else {
        logger.warning(
            "runtime.speed-curve-early-brake-blocks 配置无效: " + configuredSpeedCurveEarlyBrake);
      }
      movementAuthorityEnabled =
          section.getBoolean("movement-authority-enabled", movementAuthorityEnabled);
      double configuredAuthorityStopMargin =
          section.getDouble(
              "movement-authority-stop-margin-blocks", movementAuthorityStopMarginBlocks);
      if (Double.isFinite(configuredAuthorityStopMargin) && configuredAuthorityStopMargin >= 0.0) {
        movementAuthorityStopMarginBlocks = configuredAuthorityStopMargin;
      } else {
        logger.warning(
            "runtime.movement-authority-stop-margin-blocks 配置无效: " + configuredAuthorityStopMargin);
      }
      double configuredAuthorityCautionMargin =
          section.getDouble(
              "movement-authority-caution-margin-blocks", movementAuthorityCautionMarginBlocks);
      if (Double.isFinite(configuredAuthorityCautionMargin)
          && configuredAuthorityCautionMargin >= movementAuthorityStopMarginBlocks) {
        movementAuthorityCautionMarginBlocks = configuredAuthorityCautionMargin;
      } else {
        logger.warning(
            "runtime.movement-authority-caution-margin-blocks 配置无效: "
                + configuredAuthorityCautionMargin
                + "（需 >= movement-authority-stop-margin-blocks）");
      }
      double configuredHysteresis =
          section.getDouble("speed-command-hysteresis-bps", speedCommandHysteresisBps);
      if (Double.isFinite(configuredHysteresis) && configuredHysteresis >= 0.0) {
        speedCommandHysteresisBps = configuredHysteresis;
      } else {
        logger.warning("runtime.speed-command-hysteresis-bps 配置无效: " + configuredHysteresis);
      }
      double configuredAccelFactor =
          section.getDouble("speed-command-accel-factor", speedCommandAccelFactor);
      if (Double.isFinite(configuredAccelFactor) && configuredAccelFactor > 0.0) {
        speedCommandAccelFactor = configuredAccelFactor;
      } else {
        logger.warning("runtime.speed-command-accel-factor 配置无效: " + configuredAccelFactor);
      }
      double configuredDecelFactor =
          section.getDouble("speed-command-decel-factor", speedCommandDecelFactor);
      if (Double.isFinite(configuredDecelFactor) && configuredDecelFactor > 0.0) {
        speedCommandDecelFactor = configuredDecelFactor;
      } else {
        logger.warning("runtime.speed-command-decel-factor 配置无效: " + configuredDecelFactor);
      }
      int configuredDistanceCacheRefresh =
          section.getInt("distance-cache-refresh-seconds", distanceCacheRefreshSeconds);
      if (configuredDistanceCacheRefresh > 0) {
        distanceCacheRefreshSeconds = configuredDistanceCacheRefresh;
      } else {
        logger.warning(
            "runtime.distance-cache-refresh-seconds 配置无效: " + configuredDistanceCacheRefresh);
      }
      double configuredStallSpeed =
          section.getDouble("failover-stall-speed-bps", failoverStallSpeed);
      if (Double.isFinite(configuredStallSpeed) && configuredStallSpeed >= 0.0) {
        failoverStallSpeed = configuredStallSpeed;
      } else {
        logger.warning("runtime.failover-stall-speed-bps 配置无效: " + configuredStallSpeed);
      }
      int configuredStallTicks = section.getInt("failover-stall-ticks", failoverStallTicks);
      if (configuredStallTicks > 0) {
        failoverStallTicks = configuredStallTicks;
      } else {
        logger.warning("runtime.failover-stall-ticks 配置无效: " + configuredStallTicks);
      }
      boolean configuredUnreachableStop =
          section.getBoolean("failover-unreachable-stop", failoverUnreachableStop);
      failoverUnreachableStop = configuredUnreachableStop;
      clearDestinationOnHardStop =
          section.getBoolean("clear-destination-on-hard-stop", clearDestinationOnHardStop);
      signalEnvelopeEnabled = section.getBoolean("signal-envelope-enabled", signalEnvelopeEnabled);
      signalEventCoalesce = section.getBoolean("signal-event-coalesce", signalEventCoalesce);
      signalProceedStabilityTicks =
          section.getInt("signal-proceed-stability-ticks", signalProceedStabilityTicks);
      if (signalProceedStabilityTicks < 0) {
        logger.warning(
            "runtime.signal-proceed-stability-ticks 配置无效: " + signalProceedStabilityTicks);
        signalProceedStabilityTicks = DEFAULT_SIGNAL_PROCEED_STABILITY_TICKS;
      }
      maxEnvelopeBuildsPerTick =
          section.getInt("max-envelope-builds-per-tick", maxEnvelopeBuildsPerTick);
      if (maxEnvelopeBuildsPerTick <= 0) {
        logger.warning("runtime.max-envelope-builds-per-tick 配置无效: " + maxEnvelopeBuildsPerTick);
        maxEnvelopeBuildsPerTick = DEFAULT_MAX_ENVELOPE_BUILDS_PER_TICK;
      }
      pathCacheMaxSize = section.getInt("path-cache-max-size", pathCacheMaxSize);
      if (pathCacheMaxSize <= 0) {
        logger.warning("runtime.path-cache-max-size 配置无效: " + pathCacheMaxSize);
        pathCacheMaxSize = DEFAULT_PATH_CACHE_MAX_SIZE;
      }
      staleQueueEntryTtlSeconds =
          section.getInt("stale-queue-entry-ttl-seconds", staleQueueEntryTtlSeconds);
      if (staleQueueEntryTtlSeconds <= 0) {
        logger.warning("runtime.stale-queue-entry-ttl-seconds 配置无效: " + staleQueueEntryTtlSeconds);
        staleQueueEntryTtlSeconds = DEFAULT_STALE_QUEUE_ENTRY_TTL_SECONDS;
      }
      followingMinClearBlocks =
          section.getInt("following-min-clear-blocks", followingMinClearBlocks);
      if (followingMinClearBlocks < 0) {
        logger.warning("runtime.following-min-clear-blocks 配置无效: " + followingMinClearBlocks);
        followingMinClearBlocks = DEFAULT_FOLLOWING_MIN_CLEAR_BLOCKS;
      }
      followingStopMarginBlocks =
          section.getInt("following-stop-margin-blocks", followingStopMarginBlocks);
      if (followingStopMarginBlocks < 0) {
        logger.warning("runtime.following-stop-margin-blocks 配置无效: " + followingStopMarginBlocks);
        followingStopMarginBlocks = DEFAULT_FOLLOWING_STOP_MARGIN_BLOCKS;
      }
    }
    return new RuntimeSettings(
        tickInterval,
        launchCooldownTicks,
        lookaheadEdges,
        minClearEdges,
        rearGuardEdges,
        switcherZoneEdges,
        approachSpeed,
        cautionSpeed,
        approachDepotSpeed,
        approachWindowBlocks,
        approachWindowEdges,
        approachTargetEdges,
        speedCurveEnabled,
        speedCurveType,
        speedCurveFactor,
        speedCurveEarlyBrakeBlocks,
        failoverStallSpeed,
        failoverStallTicks,
        failoverUnreachableStop,
        clearDestinationOnHardStop,
        movementAuthorityEnabled,
        movementAuthorityStopMarginBlocks,
        movementAuthorityCautionMarginBlocks,
        speedCommandHysteresisBps,
        speedCommandAccelFactor,
        speedCommandDecelFactor,
        distanceCacheRefreshSeconds,
        signalEnvelopeEnabled,
        signalEventCoalesce,
        signalProceedStabilityTicks,
        maxEnvelopeBuildsPerTick,
        pathCacheMaxSize,
        staleQueueEntryTtlSeconds,
        followingMinClearBlocks,
        followingStopMarginBlocks,
        hudBossBarEnabled,
        hudBossBarTickInterval,
        hudBossBarTemplate,
        hudActionBarEnabled,
        hudActionBarTickInterval,
        hudActionBarTemplate,
        hudPlayerDisplayEnabled,
        hudPlayerDisplayTickInterval,
        hudPlayerDisplayTemplate);
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
      SpawnSettings spawnSettings,
      TrainConfigSettings trainConfigSettings,
      ReclaimSettings reclaimSettings,
      SmartDispatcherSettings smartDispatcherSettings,
      HealthSettings healthSettings,
      TimetableSettings timetableSettings) {
    public ConfigView {
      smartDispatcherSettings =
          smartDispatcherSettings == null
              ? new SmartDispatcherSettings(DEFAULT_SMART_DISPATCHER_MODE)
              : smartDispatcherSettings;
      timetableSettings =
          timetableSettings == null ? TimetableSettings.defaults() : timetableSettings;
    }

    /** 兼容尚未感知时刻表配置的调用方与测试夹具。 */
    public ConfigView(
        int configVersion,
        boolean debugEnabled,
        String locale,
        StorageSettings storageSettings,
        GraphSettings graphSettings,
        AutoStationSettings autoStationSettings,
        RuntimeSettings runtimeSettings,
        SpawnSettings spawnSettings,
        TrainConfigSettings trainConfigSettings,
        ReclaimSettings reclaimSettings,
        SmartDispatcherSettings smartDispatcherSettings,
        HealthSettings healthSettings) {
      this(
          configVersion,
          debugEnabled,
          locale,
          storageSettings,
          graphSettings,
          autoStationSettings,
          runtimeSettings,
          spawnSettings,
          trainConfigSettings,
          reclaimSettings,
          smartDispatcherSettings,
          healthSettings,
          TimetableSettings.defaults());
    }

    /** 兼容仍按旧参数列表构造配置快照的测试夹具。 */
    public ConfigView(
        int configVersion,
        boolean debugEnabled,
        String locale,
        StorageSettings storageSettings,
        GraphSettings graphSettings,
        AutoStationSettings autoStationSettings,
        RuntimeSettings runtimeSettings,
        SpawnSettings spawnSettings,
        TrainConfigSettings trainConfigSettings,
        ReclaimSettings reclaimSettings,
        HealthSettings healthSettings) {
      this(
          configVersion,
          debugEnabled,
          locale,
          storageSettings,
          graphSettings,
          autoStationSettings,
          runtimeSettings,
          spawnSettings,
          trainConfigSettings,
          reclaimSettings,
          new SmartDispatcherSettings(DEFAULT_SMART_DISPATCHER_MODE),
          healthSettings);
    }
  }

  /**
   * 时刻表（录制 + 按表运行）配置。
   *
   * @param enabled 按表运行总开关；关闭时录制仍可用，但已发布的时刻表不会影响任何列车
   * @param spawnEnabled 是否由时刻表接管发车出票；需要 {@code enabled} 一并打开
   * @param holdMaxSeconds 早到列车最多被扣留多少秒；运行时还会再被调度层的安全上限封顶
   * @param assignToleranceSeconds 列车与表定车次匹配时允许的最大偏差秒数
   * @param maxCatchUpSeconds 发车侧单次轮询最多回补多长的时间窗口
   * @param reloadIntervalSeconds 重新加载已发布时刻表的间隔
   * @param recorderFlushIntervalSeconds 录制结果落库的间隔
   * @param zone 时刻表默认时区；留空表示服务器默认时区
   */
  public record TimetableSettings(
      boolean enabled,
      boolean spawnEnabled,
      int holdMaxSeconds,
      int assignToleranceSeconds,
      int maxCatchUpSeconds,
      int reloadIntervalSeconds,
      int recorderFlushIntervalSeconds,
      String zone) {

    public TimetableSettings {
      holdMaxSeconds = Math.max(0, holdMaxSeconds);
      assignToleranceSeconds = Math.max(0, assignToleranceSeconds);
      maxCatchUpSeconds = Math.max(0, maxCatchUpSeconds);
      reloadIntervalSeconds = Math.max(1, reloadIntervalSeconds);
      recorderFlushIntervalSeconds = Math.max(1, recorderFlushIntervalSeconds);
      zone = zone == null ? "" : zone.trim();
    }

    /** 全部关闭的默认值。 */
    public static TimetableSettings defaults() {
      return new TimetableSettings(false, false, 120, 300, 300, 60, 5, "");
    }

    /** 解析时区，留空时回退服务器默认。 */
    public java.time.ZoneId resolveZone() {
      if (zone.isBlank()) {
        return java.time.ZoneId.systemDefault();
      }
      try {
        return java.time.ZoneId.of(zone);
      } catch (java.time.DateTimeException ignored) {
        return java.time.ZoneId.systemDefault();
      }
    }
  }

  /** Smart Dispatcher / Traffic Control Supervisor 的隔离配置。 */
  public record SmartDispatcherSettings(
      SmartDispatcherMode mode, SmartDispatcherPlannerSettings plannerSettings) {
    public SmartDispatcherSettings {
      mode = mode == null ? DEFAULT_SMART_DISPATCHER_MODE : mode;
      plannerSettings =
          plannerSettings == null ? SmartDispatcherPlannerSettings.defaults() : plannerSettings;
    }

    public SmartDispatcherSettings(SmartDispatcherMode mode) {
      this(mode, SmartDispatcherPlannerSettings.defaults());
    }
  }

  /** Smart Dispatcher minimal forward planner 配置。 */
  public record SmartDispatcherPlannerSettings(
      boolean enabled,
      SmartDispatcherPlannerMode mode,
      int maxReservationResources,
      int reservationTtlTicks,
      long blockerSnapshotTtlMs,
      boolean requireSameDirection,
      boolean allowReverse,
      boolean allowTurnbackBeforeBoundary,
      boolean oneActiveReservationPerCycle) {
    public SmartDispatcherPlannerSettings {
      mode = mode == null ? DEFAULT_SMART_DISPATCHER_PLANNER_MODE : mode;
      maxReservationResources =
          maxReservationResources <= 0
              ? DEFAULT_SMART_DISPATCHER_PLANNER_MAX_RESERVATION_RESOURCES
              : maxReservationResources;
      reservationTtlTicks =
          reservationTtlTicks <= 0
              ? DEFAULT_SMART_DISPATCHER_PLANNER_RESERVATION_TTL_TICKS
              : reservationTtlTicks;
      blockerSnapshotTtlMs =
          blockerSnapshotTtlMs <= 0
              ? DEFAULT_SMART_DISPATCHER_PLANNER_BLOCKER_SNAPSHOT_TTL_MS
              : blockerSnapshotTtlMs;
    }

    public static SmartDispatcherPlannerSettings defaults() {
      return new SmartDispatcherPlannerSettings(
          DEFAULT_SMART_DISPATCHER_PLANNER_ENABLED,
          DEFAULT_SMART_DISPATCHER_PLANNER_MODE,
          DEFAULT_SMART_DISPATCHER_PLANNER_MAX_RESERVATION_RESOURCES,
          DEFAULT_SMART_DISPATCHER_PLANNER_RESERVATION_TTL_TICKS,
          DEFAULT_SMART_DISPATCHER_PLANNER_BLOCKER_SNAPSHOT_TTL_MS,
          DEFAULT_SMART_DISPATCHER_PLANNER_REQUIRE_SAME_DIRECTION,
          DEFAULT_SMART_DISPATCHER_PLANNER_ALLOW_REVERSE,
          DEFAULT_SMART_DISPATCHER_PLANNER_ALLOW_TURNBACK_BEFORE_BOUNDARY,
          DEFAULT_SMART_DISPATCHER_PLANNER_ONE_ACTIVE_RESERVATION_PER_CYCLE);
    }
  }

  /** 健康检查与自动修复配置。 */
  public record HealthSettings(
      boolean enabled,
      int checkIntervalSeconds,
      boolean autoFixEnabled,
      int stallThresholdSeconds,
      int progressStuckThresholdSeconds,
      int progressStopGraceSeconds,
      int deadlockThresholdSeconds,
      int deadlockDestroyThresholdSeconds,
      boolean trainCleanupEnabled,
      int deadlockDestroyCooldownSeconds,
      int stuckCleanupThresholdSeconds,
      int stuckCleanupPassengerThresholdSeconds,
      int stuckCleanupCooldownSeconds,
      int deadlockEpisodeGraceSeconds,
      int deadlockMinStopSeconds,
      int blockerSnapshotMaxAgeSeconds,
      int recoveryCooldownSeconds,
      int occupancyTimeoutMinutes,
      boolean orphanCleanupEnabled,
      boolean timeoutCleanupEnabled) {
    public HealthSettings {
      if (checkIntervalSeconds <= 0) {
        throw new IllegalArgumentException("checkIntervalSeconds 必须为正数");
      }
      if (stallThresholdSeconds <= 0) {
        throw new IllegalArgumentException("stallThresholdSeconds 必须为正数");
      }
      if (progressStuckThresholdSeconds <= 0) {
        throw new IllegalArgumentException("progressStuckThresholdSeconds 必须为正数");
      }
      if (progressStopGraceSeconds <= 0) {
        throw new IllegalArgumentException("progressStopGraceSeconds 必须为正数");
      }
      if (deadlockThresholdSeconds <= 0) {
        throw new IllegalArgumentException("deadlockThresholdSeconds 必须为正数");
      }
      if (deadlockDestroyThresholdSeconds < 0) {
        throw new IllegalArgumentException("deadlockDestroyThresholdSeconds 必须为非负数");
      }
      if (deadlockDestroyCooldownSeconds < 0) {
        throw new IllegalArgumentException("deadlockDestroyCooldownSeconds 必须为非负数");
      }
      if (stuckCleanupThresholdSeconds <= 0) {
        throw new IllegalArgumentException("stuckCleanupThresholdSeconds 必须为正数");
      }
      if (stuckCleanupPassengerThresholdSeconds < stuckCleanupThresholdSeconds) {
        throw new IllegalArgumentException(
            "stuckCleanupPassengerThresholdSeconds 不得小于 stuckCleanupThresholdSeconds");
      }
      if (stuckCleanupCooldownSeconds < 0) {
        throw new IllegalArgumentException("stuckCleanupCooldownSeconds 必须为非负数");
      }
      if (deadlockEpisodeGraceSeconds < 0) {
        throw new IllegalArgumentException("deadlockEpisodeGraceSeconds 必须为非负数");
      }
      if (deadlockMinStopSeconds < 0) {
        throw new IllegalArgumentException("deadlockMinStopSeconds 必须为非负数");
      }
      if (blockerSnapshotMaxAgeSeconds <= 0) {
        throw new IllegalArgumentException("blockerSnapshotMaxAgeSeconds 必须为正数");
      }
      if (recoveryCooldownSeconds <= 0) {
        throw new IllegalArgumentException("recoveryCooldownSeconds 必须为正数");
      }
      if (occupancyTimeoutMinutes <= 0) {
        throw new IllegalArgumentException("occupancyTimeoutMinutes 必须为正数");
      }
    }

    public static HealthSettings defaults() {
      return new HealthSettings(
          true, 5, true, 30, 60, 60, 45, 60, false, 120, 600, 1800, 120, 15, 20, 20, 10, 10, true,
          true);
    }
  }

  /** 车辆回收配置（ReclaimPolicy）。 */
  /**
   * 闲置回收配置。
   *
   * @param strandedDestroySeconds 待命车闲置超时后一直找不到可用 RETURN 线路（例如直通车滞留在外方终点）持续多久就销毁；0 关闭兜底
   */
  public record ReclaimSettings(
      boolean enabled,
      long maxIdleSeconds,
      int maxActiveTrains,
      long checkIntervalSeconds,
      long strandedDestroySeconds) {

    /** 默认滞留 30 分钟后销毁：比闲置回收窗口长得多，给折返事务与晚到的回库票留足时间。 */
    public static final long DEFAULT_STRANDED_DESTROY_SECONDS = 1800L;

    /** 不带滞留销毁阈值的构造，取默认值。 */
    public ReclaimSettings(
        boolean enabled, long maxIdleSeconds, int maxActiveTrains, long checkIntervalSeconds) {
      this(
          enabled,
          maxIdleSeconds,
          maxActiveTrains,
          checkIntervalSeconds,
          DEFAULT_STRANDED_DESTROY_SECONDS);
    }

    public ReclaimSettings {
      if (strandedDestroySeconds < 0) {
        throw new IllegalArgumentException("strandedDestroySeconds 不能为负");
      }
      if (maxIdleSeconds <= 0) {
        throw new IllegalArgumentException("maxIdleSeconds 必须为正数");
      }
      if (maxActiveTrains <= 0) {
        throw new IllegalArgumentException("maxActiveTrains 必须为正数");
      }
      if (checkIntervalSeconds <= 0) {
        throw new IllegalArgumentException("checkIntervalSeconds 必须为正数");
      }
    }
  }

  /** 自动发车配置（SpawnManager / TicketAssigner）。 */
  public record SpawnSettings(
      boolean enabled,
      int tickIntervalTicks,
      int planRefreshTicks,
      int maxSpawnPerTick,
      int maxGeneratePerTick,
      int maxBacklogPerService,
      int retryDelayTicks,
      int maxAttempts,
      double layoverFallbackMultiplier,
      long queuedTicketMaxAgeSeconds,
      long pendingLayoverMaxAgeSeconds,
      int maxActiveTrains,
      int congestionNetworkReferenceTrains,
      double congestionHoldThreshold,
      double congestionReleaseThreshold) {

    /**
     * 兼容旧调用：未指定全网参考车数时，沿用在网列车上限。
     *
     * <p>这正是解耦前的旧行为，只保留给老调用点；新代码请显式传入。
     */
    public SpawnSettings(
        boolean enabled,
        int tickIntervalTicks,
        int planRefreshTicks,
        int maxSpawnPerTick,
        int maxGeneratePerTick,
        int maxBacklogPerService,
        int retryDelayTicks,
        int maxAttempts,
        double layoverFallbackMultiplier,
        long queuedTicketMaxAgeSeconds,
        long pendingLayoverMaxAgeSeconds,
        int maxActiveTrains,
        double congestionHoldThreshold,
        double congestionReleaseThreshold) {
      this(
          enabled,
          tickIntervalTicks,
          planRefreshTicks,
          maxSpawnPerTick,
          maxGeneratePerTick,
          maxBacklogPerService,
          retryDelayTicks,
          maxAttempts,
          layoverFallbackMultiplier,
          queuedTicketMaxAgeSeconds,
          pendingLayoverMaxAgeSeconds,
          maxActiveTrains,
          maxActiveTrains,
          congestionHoldThreshold,
          congestionReleaseThreshold);
    }

    /** 兼容旧调用：未指定在网列车上限与拥挤阈值时沿用默认值。 */
    public SpawnSettings(
        boolean enabled,
        int tickIntervalTicks,
        int planRefreshTicks,
        int maxSpawnPerTick,
        int maxGeneratePerTick,
        int maxBacklogPerService,
        int retryDelayTicks,
        int maxAttempts,
        double layoverFallbackMultiplier,
        long queuedTicketMaxAgeSeconds,
        long pendingLayoverMaxAgeSeconds) {
      this(
          enabled,
          tickIntervalTicks,
          planRefreshTicks,
          maxSpawnPerTick,
          maxGeneratePerTick,
          maxBacklogPerService,
          retryDelayTicks,
          maxAttempts,
          layoverFallbackMultiplier,
          queuedTicketMaxAgeSeconds,
          pendingLayoverMaxAgeSeconds,
          DEFAULT_SPAWN_MAX_ACTIVE_TRAINS,
          DEFAULT_SPAWN_CONGESTION_HOLD_THRESHOLD,
          DEFAULT_SPAWN_CONGESTION_RELEASE_THRESHOLD);
    }

    public SpawnSettings(
        boolean enabled,
        int tickIntervalTicks,
        int planRefreshTicks,
        int maxSpawnPerTick,
        int maxGeneratePerTick,
        int maxBacklogPerService,
        int retryDelayTicks,
        int maxAttempts,
        double layoverFallbackMultiplier) {
      this(
          enabled,
          tickIntervalTicks,
          planRefreshTicks,
          maxSpawnPerTick,
          maxGeneratePerTick,
          maxBacklogPerService,
          retryDelayTicks,
          maxAttempts,
          layoverFallbackMultiplier,
          DEFAULT_SPAWN_QUEUED_TICKET_MAX_AGE_SECONDS,
          DEFAULT_SPAWN_PENDING_LAYOVER_MAX_AGE_SECONDS,
          DEFAULT_SPAWN_MAX_ACTIVE_TRAINS,
          DEFAULT_SPAWN_CONGESTION_HOLD_THRESHOLD,
          DEFAULT_SPAWN_CONGESTION_RELEASE_THRESHOLD);
    }

    public SpawnSettings {
      if (tickIntervalTicks <= 0) {
        throw new IllegalArgumentException("tickIntervalTicks 必须为正数");
      }
      if (planRefreshTicks <= 0) {
        throw new IllegalArgumentException("planRefreshTicks 必须为正数");
      }
      if (maxSpawnPerTick <= 0) {
        throw new IllegalArgumentException("maxSpawnPerTick 必须为正数");
      }
      if (maxGeneratePerTick <= 0) {
        throw new IllegalArgumentException("maxGeneratePerTick 必须为正数");
      }
      if (maxBacklogPerService <= 0) {
        throw new IllegalArgumentException("maxBacklogPerService 必须为正数");
      }
      if (retryDelayTicks < 0) {
        throw new IllegalArgumentException("retryDelayTicks 必须为非负数");
      }
      if (maxAttempts <= 0) {
        throw new IllegalArgumentException("maxAttempts 必须为正数");
      }
      if (layoverFallbackMultiplier < 0) {
        throw new IllegalArgumentException("layoverFallbackMultiplier 必须为非负数");
      }
      if (queuedTicketMaxAgeSeconds < 0L) {
        throw new IllegalArgumentException("queuedTicketMaxAgeSeconds 必须为非负数");
      }
      if (pendingLayoverMaxAgeSeconds < 0L) {
        throw new IllegalArgumentException("pendingLayoverMaxAgeSeconds 必须为非负数");
      }
      if (maxActiveTrains < 0) {
        throw new IllegalArgumentException("maxActiveTrains 必须为非负数");
      }
      if (!(congestionHoldThreshold > 0.0D) || congestionHoldThreshold > 1.0D) {
        throw new IllegalArgumentException("congestionHoldThreshold 必须落在 (0,1]");
      }
      if (!(congestionReleaseThreshold > 0.0D) || congestionReleaseThreshold > 1.0D) {
        throw new IllegalArgumentException("congestionReleaseThreshold 必须落在 (0,1]");
      }
      if (congestionReleaseThreshold > congestionHoldThreshold) {
        throw new IllegalArgumentException(
            "congestionReleaseThreshold 不能高于 congestionHoldThreshold");
      }
    }
  }

  /** 调度图相关配置（默认速度 + 牌子锚点搜索半径）。 */
  public record GraphSettings(
      double defaultSpeedBlocksPerSecond,
      int signAnchorSearchRadius,
      int switcherAnchorSearchRadius) {
    public GraphSettings {
      if (!Double.isFinite(defaultSpeedBlocksPerSecond) || defaultSpeedBlocksPerSecond <= 0.0) {
        throw new IllegalArgumentException("defaultSpeedBlocksPerSecond 必须为正数");
      }
      if (signAnchorSearchRadius < 0) {
        throw new IllegalArgumentException("signAnchorSearchRadius 必须为非负数");
      }
      if (switcherAnchorSearchRadius < 0) {
        throw new IllegalArgumentException("switcherAnchorSearchRadius 必须为非负数");
      }
    }

    /**
     * 返回默认配置（与内置模板保持一致）。
     *
     * <p>默认速度用于诊断/查询命令中的 ETA 估算：ETA = shortestDistanceBlocks / defaultSpeedBlocksPerSecond。
     */
    public static GraphSettings defaults() {
      return new GraphSettings(
          DEFAULT_GRAPH_SPEED_BLOCKS_PER_SECOND,
          DEFAULT_GRAPH_SIGN_ANCHOR_SEARCH_RADIUS,
          DEFAULT_GRAPH_SWITCHER_ANCHOR_SEARCH_RADIUS);
    }
  }

  /** AutoStation 相关配置（默认关门提示音与音量/音高）。 */
  public record AutoStationSettings(
      String doorCloseSound, float doorCloseSoundVolume, float doorCloseSoundPitch) {}

  /** 运行时调度配置。 */
  public record RuntimeSettings(
      int dispatchTickIntervalTicks,
      int launchCooldownTicks,
      int lookaheadEdges,
      int minClearEdges,
      int rearGuardEdges,
      int switcherZoneEdges,
      double approachSpeedBps,
      double cautionSpeedBps,
      double approachDepotSpeedBps,
      double approachWindowBlocks,
      int approachWindowEdges,
      int approachTargetEdges,
      boolean speedCurveEnabled,
      SpeedCurveType speedCurveType,
      double speedCurveFactor,
      double speedCurveEarlyBrakeBlocks,
      double failoverStallSpeedBps,
      int failoverStallTicks,
      boolean failoverUnreachableStop,
      boolean clearDestinationOnHardStop,
      boolean movementAuthorityEnabled,
      double movementAuthorityStopMarginBlocks,
      double movementAuthorityCautionMarginBlocks,
      double speedCommandHysteresisBps,
      double speedCommandAccelFactor,
      double speedCommandDecelFactor,
      int distanceCacheRefreshSeconds,
      boolean signalEnvelopeEnabled,
      boolean signalEventCoalesce,
      int signalProceedStabilityTicks,
      int maxEnvelopeBuildsPerTick,
      int pathCacheMaxSize,
      int staleQueueEntryTtlSeconds,
      int followingMinClearBlocks,
      int followingStopMarginBlocks,
      boolean hudBossBarEnabled,
      int hudBossBarTickIntervalTicks,
      Optional<String> hudBossBarTemplate,
      boolean hudActionBarEnabled,
      int hudActionBarTickIntervalTicks,
      Optional<String> hudActionBarTemplate,
      boolean hudPlayerDisplayEnabled,
      int hudPlayerDisplayTickIntervalTicks,
      Optional<String> hudPlayerDisplayTemplate) {
    public RuntimeSettings {
      if (dispatchTickIntervalTicks <= 0) {
        throw new IllegalArgumentException("dispatchTickIntervalTicks 必须为正数");
      }
      if (launchCooldownTicks < 0) {
        throw new IllegalArgumentException("launchCooldownTicks 必须为非负数");
      }
      if (lookaheadEdges <= 0) {
        throw new IllegalArgumentException("lookaheadEdges 必须为正数");
      }
      if (minClearEdges < 0) {
        throw new IllegalArgumentException("minClearEdges 必须为非负数");
      }
      if (rearGuardEdges < 0) {
        throw new IllegalArgumentException("rearGuardEdges 必须为非负数");
      }
      if (switcherZoneEdges < 0) {
        throw new IllegalArgumentException("switcherZoneEdges 必须为非负数");
      }
      if (!Double.isFinite(approachSpeedBps) || approachSpeedBps < 0.0) {
        throw new IllegalArgumentException("approachSpeedBps 必须为非负数");
      }
      if (!Double.isFinite(cautionSpeedBps) || cautionSpeedBps <= 0.0) {
        throw new IllegalArgumentException("cautionSpeedBps 必须为正数");
      }
      if (!Double.isFinite(approachDepotSpeedBps) || approachDepotSpeedBps < 0.0) {
        throw new IllegalArgumentException("approachDepotSpeedBps 必须为非负数");
      }
      if (!Double.isFinite(approachWindowBlocks) || approachWindowBlocks < 0.0) {
        throw new IllegalArgumentException("approachWindowBlocks 必须为非负数");
      }
      if (approachWindowEdges < 0) {
        throw new IllegalArgumentException("approachWindowEdges 必须为非负数");
      }
      if (approachTargetEdges < 0) {
        throw new IllegalArgumentException("approachTargetEdges 必须为非负数");
      }
      if (speedCurveType == null) {
        throw new IllegalArgumentException("speedCurveType 不能为空");
      }
      if (!Double.isFinite(speedCurveFactor) || speedCurveFactor <= 0.0) {
        throw new IllegalArgumentException("speedCurveFactor 必须为正数");
      }
      if (!Double.isFinite(speedCurveEarlyBrakeBlocks) || speedCurveEarlyBrakeBlocks < 0.0) {
        throw new IllegalArgumentException("speedCurveEarlyBrakeBlocks 必须为非负数");
      }
      if (!Double.isFinite(failoverStallSpeedBps) || failoverStallSpeedBps < 0.0) {
        throw new IllegalArgumentException("failoverStallSpeedBps 必须为非负数");
      }
      if (failoverStallTicks <= 0) {
        throw new IllegalArgumentException("failoverStallTicks 必须为正数");
      }
      if (!Double.isFinite(movementAuthorityStopMarginBlocks)
          || movementAuthorityStopMarginBlocks < 0.0) {
        throw new IllegalArgumentException("movementAuthorityStopMarginBlocks 必须为非负数");
      }
      if (!Double.isFinite(movementAuthorityCautionMarginBlocks)
          || movementAuthorityCautionMarginBlocks < movementAuthorityStopMarginBlocks) {
        throw new IllegalArgumentException(
            "movementAuthorityCautionMarginBlocks 必须大于等于 movementAuthorityStopMarginBlocks");
      }
      if (!Double.isFinite(speedCommandHysteresisBps) || speedCommandHysteresisBps < 0.0) {
        throw new IllegalArgumentException("speedCommandHysteresisBps 必须为非负数");
      }
      if (!Double.isFinite(speedCommandAccelFactor) || speedCommandAccelFactor <= 0.0) {
        throw new IllegalArgumentException("speedCommandAccelFactor 必须为正数");
      }
      if (!Double.isFinite(speedCommandDecelFactor) || speedCommandDecelFactor <= 0.0) {
        throw new IllegalArgumentException("speedCommandDecelFactor 必须为正数");
      }
      if (distanceCacheRefreshSeconds <= 0) {
        throw new IllegalArgumentException("distanceCacheRefreshSeconds 必须为正数");
      }
      if (signalProceedStabilityTicks < 0) {
        throw new IllegalArgumentException("signalProceedStabilityTicks 必须为非负数");
      }
      if (maxEnvelopeBuildsPerTick <= 0) {
        throw new IllegalArgumentException("maxEnvelopeBuildsPerTick 必须为正数");
      }
      if (pathCacheMaxSize <= 0) {
        throw new IllegalArgumentException("pathCacheMaxSize 必须为正数");
      }
      if (staleQueueEntryTtlSeconds <= 0) {
        throw new IllegalArgumentException("staleQueueEntryTtlSeconds 必须为正数");
      }
      if (followingMinClearBlocks < 0) {
        throw new IllegalArgumentException("followingMinClearBlocks 必须为非负数");
      }
      if (followingStopMarginBlocks < 0) {
        throw new IllegalArgumentException("followingStopMarginBlocks 必须为非负数");
      }
      if (hudBossBarTickIntervalTicks <= 0) {
        throw new IllegalArgumentException("hudBossBarTickIntervalTicks 必须为正数");
      }
      if (hudActionBarTickIntervalTicks <= 0) {
        throw new IllegalArgumentException("hudActionBarTickIntervalTicks 必须为正数");
      }
      if (hudPlayerDisplayTickIntervalTicks <= 0) {
        throw new IllegalArgumentException("hudPlayerDisplayTickIntervalTicks 必须为正数");
      }
      hudBossBarTemplate =
          hudBossBarTemplate == null ? Optional.empty() : hudBossBarTemplate.map(String::trim);
      hudActionBarTemplate =
          hudActionBarTemplate == null ? Optional.empty() : hudActionBarTemplate.map(String::trim);
      hudPlayerDisplayTemplate =
          hudPlayerDisplayTemplate == null
              ? Optional.empty()
              : hudPlayerDisplayTemplate.map(String::trim);
    }

    /** 兼容仍按旧参数列表创建配置快照的调用点；新 approach 窗口使用内置默认值。 */
    public RuntimeSettings(
        int dispatchTickIntervalTicks,
        int launchCooldownTicks,
        int lookaheadEdges,
        int minClearEdges,
        int rearGuardEdges,
        int switcherZoneEdges,
        double approachSpeedBps,
        double cautionSpeedBps,
        double approachDepotSpeedBps,
        boolean speedCurveEnabled,
        SpeedCurveType speedCurveType,
        double speedCurveFactor,
        double speedCurveEarlyBrakeBlocks,
        double failoverStallSpeedBps,
        int failoverStallTicks,
        boolean failoverUnreachableStop,
        boolean movementAuthorityEnabled,
        double movementAuthorityStopMarginBlocks,
        double movementAuthorityCautionMarginBlocks,
        double speedCommandHysteresisBps,
        double speedCommandAccelFactor,
        double speedCommandDecelFactor,
        int distanceCacheRefreshSeconds,
        boolean hudBossBarEnabled,
        int hudBossBarTickIntervalTicks,
        Optional<String> hudBossBarTemplate,
        boolean hudActionBarEnabled,
        int hudActionBarTickIntervalTicks,
        Optional<String> hudActionBarTemplate,
        boolean hudPlayerDisplayEnabled,
        int hudPlayerDisplayTickIntervalTicks,
        Optional<String> hudPlayerDisplayTemplate) {
      this(
          dispatchTickIntervalTicks,
          launchCooldownTicks,
          lookaheadEdges,
          minClearEdges,
          rearGuardEdges,
          switcherZoneEdges,
          approachSpeedBps,
          cautionSpeedBps,
          approachDepotSpeedBps,
          DEFAULT_APPROACH_WINDOW_BLOCKS,
          DEFAULT_APPROACH_WINDOW_EDGES,
          DEFAULT_APPROACH_TARGET_EDGES,
          speedCurveEnabled,
          speedCurveType,
          speedCurveFactor,
          speedCurveEarlyBrakeBlocks,
          failoverStallSpeedBps,
          failoverStallTicks,
          failoverUnreachableStop,
          DEFAULT_CLEAR_DESTINATION_ON_HARD_STOP,
          movementAuthorityEnabled,
          movementAuthorityStopMarginBlocks,
          movementAuthorityCautionMarginBlocks,
          speedCommandHysteresisBps,
          speedCommandAccelFactor,
          speedCommandDecelFactor,
          distanceCacheRefreshSeconds,
          DEFAULT_SIGNAL_ENVELOPE_ENABLED,
          DEFAULT_SIGNAL_EVENT_COALESCE,
          DEFAULT_SIGNAL_PROCEED_STABILITY_TICKS,
          DEFAULT_MAX_ENVELOPE_BUILDS_PER_TICK,
          DEFAULT_PATH_CACHE_MAX_SIZE,
          DEFAULT_STALE_QUEUE_ENTRY_TTL_SECONDS,
          DEFAULT_FOLLOWING_MIN_CLEAR_BLOCKS,
          DEFAULT_FOLLOWING_STOP_MARGIN_BLOCKS,
          hudBossBarEnabled,
          hudBossBarTickIntervalTicks,
          hudBossBarTemplate,
          hudActionBarEnabled,
          hudActionBarTickIntervalTicks,
          hudActionBarTemplate,
          hudPlayerDisplayEnabled,
          hudPlayerDisplayTickIntervalTicks,
          hudPlayerDisplayTemplate);
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

    public TrainType defaultTrainType() {
      return TrainType.parse(defaultType).orElse(TrainType.EMU);
    }

    public TrainTypeSettings forType(TrainType type) {
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
