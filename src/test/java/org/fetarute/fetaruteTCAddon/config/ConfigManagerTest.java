package org.fetarute.fetaruteTCAddon.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainType;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartDispatcherMode;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartDispatcherPlannerMode;
import org.junit.jupiter.api.Test;

class ConfigManagerTest {

  @Test
  // 应解析 debug 开关与 MySQL 配置
  void parseDebugAndMySqlSettings() {
    YamlConfiguration config = new YamlConfiguration();
    config.set("config-version", 6);
    config.set("debug.enabled", true);
    config.set("storage.backend", "mysql");
    config.set("graph.default-speed-blocks-per-second", 12.5);
    config.set("autostation.door-close-sound", "BLOCK_NOTE_BLOCK_BELL");
    config.set("autostation.door-close-sound-volume", 0.9);
    config.set("autostation.door-close-sound-pitch", 1.1);
    config.set("storage.mysql.db_address", "db.example.com");
    config.set("storage.mysql.db_port", 3307);
    config.set("storage.mysql.db_table", "fta_data");
    config.set("storage.mysql.db_username", "user");
    config.set("storage.mysql.db_password", "secret");
    config.set("storage.mysql.table_prefix", "t_");
    ConfigManager.ConfigView view = ConfigManager.parse(config, Logger.getLogger("config-test"));

    assertEquals(6, view.configVersion());
    assertTrue(view.debugEnabled());
    assertEquals(12.5, view.graphSettings().defaultSpeedBlocksPerSecond());
    assertEquals("BLOCK_NOTE_BLOCK_BELL", view.autoStationSettings().doorCloseSound());
    assertEquals(0.9f, view.autoStationSettings().doorCloseSoundVolume());
    assertEquals(1.1f, view.autoStationSettings().doorCloseSoundPitch());
    assertEquals(ConfigManager.StorageBackend.MYSQL, view.storageSettings().backend());
    ConfigManager.MySqlSettings mysql = view.storageSettings().mySqlSettings().orElseThrow();
    assertEquals("db.example.com", mysql.address());
    assertEquals(3307, mysql.port());
    assertEquals("fta_data", mysql.database());
    assertEquals("user", mysql.username());
    assertEquals("secret", mysql.password());
    assertEquals("t_", mysql.tablePrefix());
  }

  @Test
  // 无效 backend 应回退到 SQLite，并填充默认文件名
  void fallbackToSqliteOnInvalidBackend() {
    YamlConfiguration config = new YamlConfiguration();
    config.set("storage.backend", "unknown");
    ConfigManager.ConfigView view = ConfigManager.parse(config, Logger.getLogger("config-test"));

    assertEquals(0, view.configVersion());
    assertFalse(view.debugEnabled());
    assertEquals(8.0, view.graphSettings().defaultSpeedBlocksPerSecond());
    assertEquals("BLOCK_NOTE_BLOCK_BELL", view.autoStationSettings().doorCloseSound());
    assertEquals(ConfigManager.StorageBackend.SQLITE, view.storageSettings().backend());
    assertEquals("data/fetarute.sqlite", view.storageSettings().sqliteSettings().file());
  }

  @Test
  // approach 窗口用于控制“离停靠点多近才算 approaching”
  void parseRuntimeApproachWindowSettings() {
    YamlConfiguration config = new YamlConfiguration();
    config.set("runtime.approach-window-blocks", 72.5);
    config.set("runtime.approach-window-edges", 2);

    ConfigManager.ConfigView view = ConfigManager.parse(config, Logger.getLogger("config-test"));

    assertEquals(72.5, view.runtimeSettings().approachWindowBlocks());
    assertEquals(2, view.runtimeSettings().approachWindowEdges());
  }

  @Test
  // 没有 train 段时默认车种为 metro，各车种按 TrainType 预设补齐
  void trainTypesDefaultToMetroAndPresets() {
    ConfigManager.ConfigView view =
        ConfigManager.parse(new YamlConfiguration(), Logger.getLogger("config-test"));

    ConfigManager.TrainConfigSettings train = view.trainConfigSettings();
    assertEquals(TrainType.METRO, train.defaultTrainType());
    for (TrainType type : TrainType.values()) {
      assertEquals(type.presetAccelBps2(), train.forType(type).accelBps2(), type.key());
      assertEquals(type.presetDecelBps2(), train.forType(type).decelBps2(), type.key());
    }
    assertEquals(train.forType(TrainType.METRO), train.forType(null), "未指定车种按默认车种");
  }

  @Test
  // 配置逐项覆盖预设：写了的字段按配置，漏写的字段与车种按预设；默认车种写错时退回 metro
  void configuredTrainTypesOverridePresetsFieldByField() {
    YamlConfiguration config = new YamlConfiguration();
    config.set("train.default-type", "tram-train");
    config.set("train.types.emu.accel-bps2", 0.75);

    ConfigManager.TrainConfigSettings train =
        ConfigManager.parse(config, Logger.getLogger("config-test")).trainConfigSettings();

    assertEquals(TrainType.METRO, train.defaultTrainType());
    assertEquals(0.75, train.forType(TrainType.EMU).accelBps2());
    assertEquals(TrainType.EMU.presetDecelBps2(), train.forType(TrainType.EMU).decelBps2());
    assertEquals(TrainType.DMU.presetAccelBps2(), train.forType(TrainType.DMU).accelBps2());
  }

  @Test
  // 兼容旧键的单一 destructive cleanup 总开关应同时服务死锁与普通长时间停滞策略。
  void parseHealthTrainCleanupSettingsFromLegacyKey() {
    YamlConfiguration config = new YamlConfiguration();
    config.set("health.deadlock-destroy-enabled", true);
    config.set("health.deadlock-destroy-threshold-seconds", 75);
    config.set("health.deadlock-destroy-cooldown-seconds", 180);
    config.set("health.deadlock-episode-grace-seconds", 12);
    config.set("health.deadlock-min-stop-seconds", 25);

    ConfigManager.ConfigView view = ConfigManager.parse(config, Logger.getLogger("config-test"));

    assertTrue(view.healthSettings().trainCleanupEnabled());
    assertEquals(75, view.healthSettings().deadlockDestroyThresholdSeconds());
    assertEquals(180, view.healthSettings().deadlockDestroyCooldownSeconds());
    assertEquals(12, view.healthSettings().deadlockEpisodeGraceSeconds());
    assertEquals(25, view.healthSettings().deadlockMinStopSeconds());
  }

  @Test
  // 普通长时间停滞 cleanup 只保留自己的阈值，不再引入第二个总开关。
  void parseHealthStuckCleanupSettings() {
    YamlConfiguration config = new YamlConfiguration();
    config.set("health.deadlock-destroy-enabled", true);
    config.set("health.stuck-cleanup-threshold-seconds", 720);
    config.set("health.stuck-cleanup-passenger-threshold-seconds", 2400);
    config.set("health.stuck-cleanup-cooldown-seconds", 150);

    ConfigManager.ConfigView view = ConfigManager.parse(config, Logger.getLogger("config-test"));

    assertTrue(view.healthSettings().trainCleanupEnabled());
    assertEquals(720, view.healthSettings().stuckCleanupThresholdSeconds());
    assertEquals(2400, view.healthSettings().stuckCleanupPassengerThresholdSeconds());
    assertEquals(150, view.healthSettings().stuckCleanupCooldownSeconds());
  }

  @Test
  // 未显式配置 destructive cleanup 时必须保持关闭，避免默认构造路径绕过安全配置。
  void healthTrainCleanupDefaultsDisabled() {
    YamlConfiguration config = new YamlConfiguration();

    ConfigManager.ConfigView view = ConfigManager.parse(config, Logger.getLogger("config-test"));

    assertFalse(view.healthSettings().trainCleanupEnabled());
    assertFalse(ConfigManager.HealthSettings.defaults().trainCleanupEnabled());
  }

  @Test
  // Smart Dispatcher 默认只观察，避免升级配置后自动启用新动作副作用。
  void parseSmartDispatcherModeDefaultsToObserveOnly() {
    YamlConfiguration config = new YamlConfiguration();

    ConfigManager.ConfigView view = ConfigManager.parse(config, Logger.getLogger("config-test"));

    assertEquals(SmartDispatcherMode.OBSERVE_ONLY, view.smartDispatcherSettings().mode());
  }

  @Test
  // Smart Dispatcher mode 支持显式切换到 ENFORCE。
  void parseSmartDispatcherModeFromConfig() {
    YamlConfiguration config = new YamlConfiguration();
    config.set("smart-dispatcher.mode", "enforce");

    ConfigManager.ConfigView view = ConfigManager.parse(config, Logger.getLogger("config-test"));

    assertEquals(SmartDispatcherMode.ENFORCE, view.smartDispatcherSettings().mode());
  }

  @Test
  // YAML 嵌套 smart-dispatcher: mode: 与 Bukkit path smart-dispatcher.mode 必须解析到同一配置项。
  void parseSmartDispatcherModeFromNestedYaml() throws Exception {
    YamlConfiguration config = new YamlConfiguration();
    config.loadFromString("smart-dispatcher:\n  mode: ENFORCE\n");

    ConfigManager.ConfigView view = ConfigManager.parse(config, Logger.getLogger("config-test"));

    assertEquals(SmartDispatcherMode.ENFORCE, view.smartDispatcherSettings().mode());
  }

  @Test
  // Planner 默认启用只观察模式，避免升级配置后直接创建 unlock reservation。
  void parseSmartDispatcherPlannerDefaultsToObserveOnly() {
    YamlConfiguration config = new YamlConfiguration();

    ConfigManager.ConfigView view = ConfigManager.parse(config, Logger.getLogger("config-test"));

    assertTrue(view.smartDispatcherSettings().plannerSettings().enabled());
    assertEquals(
        SmartDispatcherPlannerMode.OBSERVE_ONLY,
        view.smartDispatcherSettings().plannerSettings().mode());
    assertFalse(view.smartDispatcherSettings().plannerSettings().allowReverse());
    assertEquals(10_000L, view.smartDispatcherSettings().plannerSettings().blockerSnapshotTtlMs());
  }

  @Test
  // Planner 支持显式切换到 minimal forward enforcement。
  void parseSmartDispatcherPlannerFromConfig() {
    YamlConfiguration config = new YamlConfiguration();
    config.set("smart-dispatcher.planner.mode", "ENFORCE_MINIMAL_FORWARD");
    config.set("smart-dispatcher.planner.max-reservation-resources", 2);
    config.set("smart-dispatcher.planner.reservation-ttl-ticks", 20);
    config.set("smart-dispatcher.planner.blocker-snapshot-ttl-ms", 5000L);

    ConfigManager.ConfigView view = ConfigManager.parse(config, Logger.getLogger("config-test"));

    assertEquals(
        SmartDispatcherPlannerMode.ENFORCE_MINIMAL_FORWARD,
        view.smartDispatcherSettings().plannerSettings().mode());
    assertEquals(2, view.smartDispatcherSettings().plannerSettings().maxReservationResources());
    assertEquals(20, view.smartDispatcherSettings().plannerSettings().reservationTtlTicks());
    assertEquals(5000L, view.smartDispatcherSettings().plannerSettings().blockerSnapshotTtlMs());
  }

  @Test
  // Startup/reload 日志必须同时暴露 planner 的有效模式与安全边界。
  void smartDispatcherPlannerConfigTraceIncludesEffectiveMode() {
    ConfigManager.SmartDispatcherPlannerSettings settings =
        new ConfigManager.SmartDispatcherPlannerSettings(
            true,
            SmartDispatcherPlannerMode.ENFORCE_MINIMAL_FORWARD,
            2,
            20,
            5000L,
            true,
            false,
            false,
            true);

    String trace = ConfigManager.smartDispatcherPlannerConfigTrace(settings);

    assertTrue(trace.contains("SMART_DISPATCH_PLANNER_CONFIG_LOADED"));
    assertTrue(trace.contains("enabled=true"));
    assertTrue(trace.contains("mode=ENFORCE_MINIMAL_FORWARD"));
    assertTrue(trace.contains("maxReservationResources=2"));
    assertTrue(trace.contains("ttlTicks=20"));
    assertTrue(trace.contains("blockerSnapshotTtlMs=5000"));
    assertTrue(trace.contains("requireSameDirection=true"));
    assertTrue(trace.contains("allowReverse=false"));
    assertTrue(trace.contains("allowTurnbackBeforeBoundary=false"));
    assertTrue(trace.contains("oneActiveReservationPerCycle=true"));
  }

  @Test
  // Phase 1.8F 启动指纹必须能独立证明当前 jar 与 planner 执行配置。
  void smartDispatcherBuildFingerprintTraceIncludesPlannerExecutionBoundary() {
    ConfigManager.SmartDispatcherPlannerSettings settings =
        new ConfigManager.SmartDispatcherPlannerSettings(
            true,
            SmartDispatcherPlannerMode.ENFORCE_MINIMAL_FORWARD,
            2,
            20,
            5000L,
            true,
            false,
            false,
            true);

    String trace =
        ConfigManager.smartDispatcherBuildFingerprintTrace(
            "0.0.2",
            java.util.Optional.of("abc1234"),
            java.util.Optional.of("build-abc1234-260520"),
            "file:/plugins/FetaruteTCAddon.jar",
            "/server/plugins/FetaruteTCAddon/config.yml",
            SmartDispatcherMode.ENFORCE,
            settings,
            "file",
            "mode=false,planner.mode=false");

    assertTrue(trace.contains("SMART_DISPATCH_BUILD_FINGERPRINT"));
    assertTrue(trace.contains("pluginVersion=0.0.2"));
    assertTrue(trace.contains("gitCommit=abc1234"));
    assertTrue(trace.contains("buildTime=build-abc1234-260520"));
    assertTrue(trace.contains("smartDispatcherMode=ENFORCE"));
    assertTrue(trace.contains("plannerEnabled=true"));
    assertTrue(trace.contains("plannerMode=ENFORCE_MINIMAL_FORWARD"));
    assertTrue(trace.contains("blockerSnapshotTtlMs=5000"));
    assertTrue(trace.contains("maxReservationResources=2"));
    assertTrue(trace.contains("requireSameDirection=true"));
    assertTrue(trace.contains("allowReverse=false"));
    assertTrue(trace.contains("allowTurnbackBeforeBoundary=false"));
    assertTrue(trace.contains("oneActiveReservationPerCycle=true"));
    assertTrue(trace.contains("configSource=file"));
    assertTrue(trace.contains("defaultUsedFlags=mode=false,planner.mode=false"));
  }

  @Test
  // 运行时指纹必须证明现场 jar 已包含物理平交联锁与启动占用重建。
  void smartRuntimeBuildFingerprintTraceIncludesPatchLevel() {
    String trace =
        ConfigManager.smartRuntimeBuildFingerprintTrace(
            "0.0.2",
            java.util.Optional.of("e56dd0e"),
            java.util.Optional.of("build-e56dd0e-260525"));

    assertTrue(trace.contains("SMART_RUNTIME_BUILD_FINGERPRINT"));
    assertTrue(trace.contains("pluginVersion=0.0.2"));
    assertTrue(trace.contains("gitCommit=e56dd0e"));
    assertTrue(trace.contains("buildTime=build-e56dd0e-260525"));
    assertTrue(trace.contains("dispatcherPatchLevel=P2_PHYSICAL_TOPOLOGY_QUERY_INDEX"));
    assertTrue(trace.contains("physicalInterlockingFootprint=true"));
    assertTrue(trace.contains("liveFootprintReverseIndex=true"));
    assertTrue(trace.contains("startupOccupancyReconstruction=true"));
    assertTrue(trace.contains("recoverableHoldContainsRouteStopOrTerminal=true"));
  }

  @Test
  // 应解析发车准入控制：在网列车上限与拥挤门控阈值
  void parseSpawnAdmissionControlSettings() {
    YamlConfiguration config = new YamlConfiguration();
    config.set("spawn.max-active-trains", 12);
    config.set("spawn.congestion-hold-threshold", 0.61);
    config.set("spawn.congestion-release-threshold", 0.5);
    ConfigManager.ConfigView view = ConfigManager.parse(config, Logger.getLogger("config-test"));

    assertEquals(12, view.spawnSettings().maxActiveTrains());
    assertEquals(0.61, view.spawnSettings().congestionHoldThreshold());
    assertEquals(0.5, view.spawnSettings().congestionReleaseThreshold());
  }

  @Test
  // 缺省时应给出默认上限 16 与 0.58/0.48 阈值
  void spawnAdmissionControlDefaultsWhenAbsent() {
    ConfigManager.ConfigView view =
        ConfigManager.parse(new YamlConfiguration(), Logger.getLogger("config-test"));

    assertEquals(16, view.spawnSettings().maxActiveTrains());
    assertEquals(0.58, view.spawnSettings().congestionHoldThreshold());
    assertEquals(0.48, view.spawnSettings().congestionReleaseThreshold());
  }

  @Test
  // 0 表示禁用准入控制，必须被原样保留而不是当成非法值
  void spawnMaxActiveTrainsZeroDisablesAdmissionControl() {
    YamlConfiguration config = new YamlConfiguration();
    config.set("spawn.max-active-trains", 0);
    ConfigManager.ConfigView view = ConfigManager.parse(config, Logger.getLogger("config-test"));

    assertEquals(0, view.spawnSettings().maxActiveTrains());
  }

  @Test
  // 非法上限/阈值应回退默认，而不是抛出或让门控恒真
  void invalidSpawnAdmissionControlValuesFallBack() {
    YamlConfiguration config = new YamlConfiguration();
    config.set("spawn.max-active-trains", -3);
    config.set("spawn.congestion-hold-threshold", 0.0);
    config.set("spawn.congestion-release-threshold", 1.7);
    ConfigManager.ConfigView view = ConfigManager.parse(config, Logger.getLogger("config-test"));

    assertEquals(16, view.spawnSettings().maxActiveTrains());
    assertEquals(0.58, view.spawnSettings().congestionHoldThreshold());
    assertEquals(0.48, view.spawnSettings().congestionReleaseThreshold());
  }

  @Test
  // 解除阈值高于触发阈值会让门控一进入 holding 就出不来，应整对回退默认
  void releaseThresholdAboveHoldThresholdFallsBackToDefaults() {
    YamlConfiguration config = new YamlConfiguration();
    config.set("spawn.congestion-hold-threshold", 0.40);
    config.set("spawn.congestion-release-threshold", 0.90);
    ConfigManager.ConfigView view = ConfigManager.parse(config, Logger.getLogger("config-test"));

    assertEquals(0.58, view.spawnSettings().congestionHoldThreshold());
    assertEquals(0.48, view.spawnSettings().congestionReleaseThreshold());
  }

  /**
   * 准入上限与拥堵评分参考值必须各读各的。
   *
   * <p>它们曾经是同一个数，于是把 {@code max-active-trains} 从 16 提到 24 会<b>同时</b> 抬高天花板并把软刹车调钝（networkPressure
   * 的分母变大），密度实验无法归因。
   *
   * <p>这条钉的是**解析层**：只写 max-active-trains 时，参考值必须保持默认而不跟着跑。 （评分函数本身的解耦在 SpawnAdmissionControlTest
   * 里。两层都要钉： 只钉记录类证明不了解析器没把两个值又接到一起。）
   */
  @Test
  void congestionNetworkReferenceIsParsedIndependentlyOfTheAdmissionCap() {
    YamlConfiguration onlyCap = new YamlConfiguration();
    onlyCap.set("config-version", 31);
    onlyCap.set("spawn.max-active-trains", 24);
    ConfigManager.SpawnSettings raised =
        ConfigManager.parse(onlyCap, Logger.getLogger("config-test")).spawnSettings();

    assertEquals(24, raised.maxActiveTrains(), "准入上限应当读到 24");
    assertEquals(16, raised.congestionNetworkReferenceTrains(), "只改准入上限时，全网参考车数必须继续用默认值 16");

    YamlConfiguration both = new YamlConfiguration();
    both.set("config-version", 31);
    both.set("spawn.max-active-trains", 24);
    both.set("spawn.congestion-network-reference-trains", 20);
    ConfigManager.SpawnSettings explicit =
        ConfigManager.parse(both, Logger.getLogger("config-test")).spawnSettings();
    assertEquals(24, explicit.maxActiveTrains());
    assertEquals(20, explicit.congestionNetworkReferenceTrains(), "显式写了就用写的值");
  }

  /** 晚点追赶三个参数：缺省值写死在 {@code TimetableSettings}，模板里的数必须与之一致；负数回退缺省值，0 表示关掉对应手段。 */
  @Test
  void timetableRecoveryHasDefaultsAndIsConfigurable() {
    ConfigManager.TimetableSettings missing =
        ConfigManager.parse(new YamlConfiguration(), Logger.getLogger("config-test"))
            .timetableSettings();
    assertEquals(
        ConfigManager.TimetableSettings.DEFAULT_RECOVERY_MIN_DWELL_SECONDS,
        missing.recoveryMinDwellSeconds());
    assertEquals(
        ConfigManager.TimetableSettings.DEFAULT_RECOVERY_OVERSPEED_PERCENT,
        missing.recoveryOverspeedPercent());
    assertEquals(
        ConfigManager.TimetableSettings.DEFAULT_RECOVERY_ENGAGE_DELAY_SECONDS,
        missing.recoveryEngageDelaySeconds());

    YamlConfiguration explicit = new YamlConfiguration();
    explicit.set("timetable.recovery.min-dwell-seconds", 0);
    explicit.set("timetable.recovery.overspeed-percent", 5);
    explicit.set("timetable.recovery.engage-delay-seconds", -1);
    ConfigManager.TimetableSettings parsed =
        ConfigManager.parse(explicit, Logger.getLogger("config-test")).timetableSettings();
    assertEquals(0, parsed.recoveryMinDwellSeconds(), "0 关闭停站压缩");
    assertEquals(5, parsed.recoveryOverspeedPercent());
    assertEquals(
        ConfigManager.TimetableSettings.DEFAULT_RECOVERY_ENGAGE_DELAY_SECONDS,
        parsed.recoveryEngageDelaySeconds(),
        "负数回退缺省值");
  }

  /** 叫车配置：模板与代码缺省一致，缺段用缺省，负数回退缺省。 */
  @Test
  void callSettingsParseWithDefaults() throws Exception {
    YamlConfiguration template = new YamlConfiguration();
    try (java.io.InputStream in = ConfigManager.class.getResourceAsStream("/config.yml")) {
      template.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
    }
    assertEquals(
        ConfigManager.CallSettings.defaults(),
        ConfigManager.parse(template, Logger.getLogger("config-test")).callSettings());

    YamlConfiguration missing = new YamlConfiguration();
    assertEquals(
        ConfigManager.CallSettings.defaults(),
        ConfigManager.parse(missing, Logger.getLogger("config-test")).callSettings());

    YamlConfiguration explicit = new YamlConfiguration();
    explicit.set("call.min-wait-minutes", 8);
    explicit.set("call.cooldown-seconds", -3);
    explicit.set("call.terminal-wait-seconds", 0);
    explicit.set("call.default-max-trains", 4);
    ConfigManager.CallSettings parsed =
        ConfigManager.parse(explicit, Logger.getLogger("config-test")).callSettings();
    assertEquals(8, parsed.minWaitMinutes());
    assertEquals(60, parsed.cooldownSeconds(), "负数回退缺省");
    assertEquals(0, parsed.terminalWaitSeconds(), "0 表示到终点就派回库");
    assertEquals(4, parsed.defaultMaxTrains());
  }

  /** 模板里的晚点追赶参数与代码缺省值一致。 */
  @Test
  void bundledTemplateRecoveryMatchesTheDefaults() throws Exception {
    YamlConfiguration template = new YamlConfiguration();
    try (java.io.InputStream in = ConfigManager.class.getResourceAsStream("/config.yml")) {
      template.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
    }
    ConfigManager.TimetableSettings bundled =
        ConfigManager.parse(template, Logger.getLogger("config-test")).timetableSettings();
    assertEquals(
        ConfigManager.TimetableSettings.DEFAULT_RECOVERY_MIN_DWELL_SECONDS,
        bundled.recoveryMinDwellSeconds());
    assertEquals(
        ConfigManager.TimetableSettings.DEFAULT_RECOVERY_OVERSPEED_PERCENT,
        bundled.recoveryOverspeedPercent());
    assertEquals(
        ConfigManager.TimetableSettings.DEFAULT_RECOVERY_ENGAGE_DELAY_SECONDS,
        bundled.recoveryEngageDelaySeconds());
  }

  /**
   * 编表的车站停站开销：缺省 4 秒（实服 136 次停站"压牌→发车"中位 24 秒、dwell 20），可配，负数回退缺省。
   *
   * <p>它进表：改它就改表定发车与折返，所以缺省值必须写死在一处（{@link
   * ConfigManager.TimetableSettings#DEFAULT_STATION_STOP_OVERHEAD_SECONDS}），不能让模板与代码各有一个数。
   */
  @Test
  void timetableStationStopOverheadHasADefaultAndIsConfigurable() {
    YamlConfiguration missing = new YamlConfiguration();
    missing.set("timetable.enabled", true);
    assertEquals(
        ConfigManager.TimetableSettings.DEFAULT_STATION_STOP_OVERHEAD_SECONDS,
        ConfigManager.parse(missing, Logger.getLogger("config-test"))
            .timetableSettings()
            .stationStopOverheadSeconds());

    YamlConfiguration explicit = new YamlConfiguration();
    explicit.set("timetable.station-stop-overhead-seconds", 6);
    assertEquals(
        6,
        ConfigManager.parse(explicit, Logger.getLogger("config-test"))
            .timetableSettings()
            .stationStopOverheadSeconds());

    YamlConfiguration negative = new YamlConfiguration();
    negative.set("timetable.station-stop-overhead-seconds", -3);
    assertEquals(
        ConfigManager.TimetableSettings.DEFAULT_STATION_STOP_OVERHEAD_SECONDS,
        ConfigManager.parse(negative, Logger.getLogger("config-test"))
            .timetableSettings()
            .stationStopOverheadSeconds(),
        "负数回退缺省值");
  }

  /**
   * 内置模板的 {@code config-version} 必须就是解析器期望的版本。
   *
   * <p>{@code ConfigUpdater} 合并时总把版本号写成模板值，所以两者一旦错开，每次起服与重载都会报一条"不匹配"。 {@code b41c1ac} 把模板升到 34
   * 时就漏改了期望值，直到对照实服才发现。这条让下一次升模板时当场变红。
   */
  @Test
  void bundledTemplateMatchesTheExpectedVersion() throws IOException {
    YamlConfiguration template;
    try (InputStream in =
            Objects.requireNonNull(
                ConfigManagerTest.class.getClassLoader().getResourceAsStream("config.yml"));
        Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
      template = YamlConfiguration.loadConfiguration(reader);
    }
    List<String> warnings = new ArrayList<>();
    Handler capture =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            if (record.getLevel() == Level.WARNING) {
              warnings.add(record.getMessage());
            }
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    Logger logger = Logger.getAnonymousLogger();
    logger.setUseParentHandlers(false);
    logger.addHandler(capture);
    try {
      ConfigManager.parse(template, logger);
    } finally {
      logger.removeHandler(capture);
    }

    assertTrue(template.getInt("config-version") > 0, "前置：模板里应当写着 config-version");
    assertTrue(
        warnings.stream().noneMatch(message -> message.contains("config-version")),
        () -> "内置模板的版本与解析器期望的不一致：" + warnings);
  }
}
