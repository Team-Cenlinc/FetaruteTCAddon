package org.fetarute.fetaruteTCAddon.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
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
    config.set("runtime.approach-target-edges", 1);

    ConfigManager.ConfigView view = ConfigManager.parse(config, Logger.getLogger("config-test"));

    assertEquals(72.5, view.runtimeSettings().approachWindowBlocks());
    assertEquals(2, view.runtimeSettings().approachWindowEdges());
    assertEquals(1, view.runtimeSettings().approachTargetEdges());
  }

  @Test
  // health 互卡销毁兜底参数应从配置读取
  void parseHealthDeadlockDestroySettings() {
    YamlConfiguration config = new YamlConfiguration();
    config.set("health.deadlock-destroy-enabled", false);
    config.set("health.deadlock-destroy-threshold-seconds", 75);
    config.set("health.deadlock-destroy-cooldown-seconds", 180);
    config.set("health.deadlock-episode-grace-seconds", 12);
    config.set("health.deadlock-min-stop-seconds", 25);

    ConfigManager.ConfigView view = ConfigManager.parse(config, Logger.getLogger("config-test"));

    assertFalse(view.healthSettings().deadlockDestroyEnabled());
    assertEquals(75, view.healthSettings().deadlockDestroyThresholdSeconds());
    assertEquals(180, view.healthSettings().deadlockDestroyCooldownSeconds());
    assertEquals(12, view.healthSettings().deadlockEpisodeGraceSeconds());
    assertEquals(25, view.healthSettings().deadlockMinStopSeconds());
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
}
