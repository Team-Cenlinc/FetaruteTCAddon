package org.fetarute.fetaruteTCAddon;

import com.bergerkiller.bukkit.common.cloud.CloudSimpleHandler;
import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartGroupStore;
import com.bergerkiller.bukkit.tc.signactions.SignAction;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.fetarute.fetaruteTCAddon.api.event.StationGroupChangedEvent;
import org.fetarute.fetaruteTCAddon.command.FtaCompanyCommand;
import org.fetarute.fetaruteTCAddon.command.FtaDepotCommand;
import org.fetarute.fetaruteTCAddon.command.FtaEtaCommand;
import org.fetarute.fetaruteTCAddon.command.FtaGraphCommand;
import org.fetarute.fetaruteTCAddon.command.FtaHealthCommand;
import org.fetarute.fetaruteTCAddon.command.FtaInfoCommand;
import org.fetarute.fetaruteTCAddon.command.FtaLineCommand;
import org.fetarute.fetaruteTCAddon.command.FtaOccupancyCommand;
import org.fetarute.fetaruteTCAddon.command.FtaOperatorCommand;
import org.fetarute.fetaruteTCAddon.command.FtaRootCommand;
import org.fetarute.fetaruteTCAddon.command.FtaRouteCommand;
import org.fetarute.fetaruteTCAddon.command.FtaSpawnCommand;
import org.fetarute.fetaruteTCAddon.command.FtaSpeedCommand;
import org.fetarute.fetaruteTCAddon.command.FtaStationCommand;
import org.fetarute.fetaruteTCAddon.command.FtaStationGroupCommand;
import org.fetarute.fetaruteTCAddon.command.FtaStorageCommand;
import org.fetarute.fetaruteTCAddon.command.FtaTemplateCommand;
import org.fetarute.fetaruteTCAddon.command.FtaTimetableCommand;
import org.fetarute.fetaruteTCAddon.command.FtaTrainCommand;
import org.fetarute.fetaruteTCAddon.company.api.StationDirectory;
import org.fetarute.fetaruteTCAddon.company.api.StationGroupChange;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaService;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.EtaRuntimeSampler;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainSnapshotStore;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRegistryRailGraphBuilder;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.control.SpeedSettingStickListener;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.debug.GraphDebugStickListener;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.sync.RailNodeIncrementalSync;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.DwellRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.ReclaimManager;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchDiagnosticGate;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchListener;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeSignalMonitor;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeTrainHandle;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationPresenceTracker;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationStopObserverHub;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainCartsRuntimeHandle;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.HeadwayRule;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SimpleTicketAssigner;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnMonitor;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnTicket;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.StorageSpawnManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.TicketAssigner;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.TrainCartsDepotSpawner;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.RouteEditorAppendListener;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeStorageSynchronizer;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignRemoveListener;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.TrainSignBypassListener;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.action.AutoStationSignAction;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.action.DepotSignAction;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.action.WaypointSignAction;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.RuntimeDispatchRequestProvider;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.RuntimeSignalReevaluationScheduler;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.SignalEvaluator;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.SignalEventBus;
import org.fetarute.fetaruteTCAddon.display.DisplayService;
import org.fetarute.fetaruteTCAddon.display.SimpleDisplayService;
import org.fetarute.fetaruteTCAddon.display.template.HudDefaultTemplateService;
import org.fetarute.fetaruteTCAddon.display.template.HudTemplateService;
import org.fetarute.fetaruteTCAddon.storage.StorageManager;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.utils.ConfigUpdater;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.fetarute.fetaruteTCAddon.utils.LoggerManager;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.exception.InvalidSyntaxException;

/**
 * 插件入口，负责初始化配置、语言、存储后端与命令/SignAction 生命周期。
 *
 * <p>onEnable 按顺序完成：配置更新 → 语言加载 → 存储管理器初始化 → 注册 SignAction 与命令。
 */
public final class FetaruteTCAddon extends JavaPlugin {

  private final CloudSimpleHandler cloudHandler = new CloudSimpleHandler();
  private ConfigManager configManager;
  private LocaleManager localeManager;
  private StorageManager storageManager;
  private CommandManager<CommandSender> commandManager;
  private LoggerManager loggerManager;
  private SignNodeRegistry signNodeRegistry;
  private RailGraphService railGraphService;
  private WaypointSignAction waypointSignAction;
  private AutoStationSignAction autoStationSignAction;
  private DepotSignAction depotSignAction;
  private OccupancyManager occupancyManager;
  private HeadwayRule headwayRule;
  private SignalEventBus signalEventBus;
  private SignalEvaluator signalEvaluator;
  private RuntimeSignalReevaluationScheduler signalReevaluationScheduler;
  private RouteDefinitionCache routeDefinitionCache;
  private StationDirectory stationDirectory;
  private RouteProgressRegistry routeProgressRegistry;
  private LayoverRegistry layoverRegistry;
  private DwellRegistry dwellRegistry;
  private StationStopObserverHub stationStopHub;
  private StationPresenceTracker stationPresence;
  private org.fetarute.fetaruteTCAddon.api.internal.ApiEventBridge apiEventBridge;
  private org.fetarute.fetaruteTCAddon.dispatcher.health.HealthAlertBus apiEventAlertBus;
  private org.bukkit.scheduler.BukkitTask apiEventTask;
  private RuntimeDispatchService runtimeDispatchService;
  private RuntimeDispatchDiagnosticGate runtimeDispatchDiagnosticGate;
  private boolean runtimeDispatchRecoveryComplete;
  private ReclaimManager reclaimManager;
  private org.bukkit.scheduler.BukkitTask runtimeMonitorTask;
  private org.bukkit.scheduler.BukkitTask runtimeRecoveryTask;
  private org.bukkit.scheduler.BukkitTask healthMonitorTask;
  private SpawnManager spawnManager;
  private TicketAssigner spawnTicketAssigner;
  private org.bukkit.scheduler.BukkitTask spawnMonitorTask;
  private org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableService
      timetableService;
  private org.bukkit.scheduler.BukkitTask timetableReloadTask;
  private SpeedSettingStickListener speedSettingStickListener;
  private TrainSnapshotStore trainSnapshotStore;
  private EtaRuntimeSampler etaRuntimeSampler;
  private EtaService etaService;
  private DisplayService displayService;
  private HudTemplateService hudTemplateService;
  private HudDefaultTemplateService hudDefaultTemplateService;
  private org.fetarute.fetaruteTCAddon.dispatcher.health.HealthMonitor healthMonitor;

  @Override
  public void onEnable() {
    saveDefaultConfig();
    ConfigUpdater.forPlugin(
            getDataFolder(), () -> getResource("config.yml"), new LoggerManager(getLogger()))
        .update();
    this.configManager = new ConfigManager(this);
    this.configManager.reload();

    this.loggerManager = new LoggerManager(getLogger());
    this.loggerManager.setDebugEnabled(configManager.current().debugEnabled());
    // 排查期可临时调高；默认 120 条/分钟在实服拥堵时会丢掉约九成诊断，导致"没 grep 到"无法解读。
    this.runtimeDispatchDiagnosticGate =
        new RuntimeDispatchDiagnosticGate(
            loggerManager::debug, getConfig().getInt("debug.observation-budget-per-minute", 120));

    this.localeManager = new LocaleManager(this, configManager.current().locale(), loggerManager);
    this.localeManager.reload();

    this.storageManager = new StorageManager(this, loggerManager);
    this.storageManager.apply(configManager.current());
    registerSignActions();
    preloadRailGraphFromStorage();
    initOccupancyManager();
    initRouteDefinitionCache();
    initRuntimeDispatch();
    initTimetable();
    initSpawnScheduler();
    initReclaimManager();
    initHudTemplateService();
    initHudDefaultTemplateService();
    initDisplayService();
    initApi();

    registerCommands();
    getServer()
        .getConsoleSender()
        .sendMessage(
            localeManager.component(
                "locale.loaded", Map.of("locale", localeManager.getCurrentLocale())));
  }

  /**
   * 停用插件并关闭 FTA 自身的授权、调度器、监听状态与存储。
   *
   * <p>服务器关闭阶段可能已经离开 Bukkit 主线程，因此这里禁止启动任何 TrainCarts 现场枚举、实体化回滚或编组销毁。 未完成物理收容由持久回滚标签保留，并在下次启动的
   * STOP_FIRST 恢复事务中继续；显式 {@code /fta reload} 仍会在插件保持启用且位于受控主线程生命周期时完成同步收口。
   */
  @Override
  public void onDisable() {
    stopApiEvents();
    org.fetarute.fetaruteTCAddon.api.FetaruteApi.shutdown();
    beginRuntimeDispatchShutdown();
    unregisterSignActions();
    if (signalEventBus != null) {
      signalEventBus.clear();
      signalEventBus = null;
    }
    if (runtimeRecoveryTask != null) {
      runtimeRecoveryTask.cancel();
      runtimeRecoveryTask = null;
    }
    if (displayService != null) {
      displayService.stop();
      displayService = null;
    }
    if (reclaimManager != null) {
      reclaimManager.stop();
      reclaimManager = null;
    }
    runtimeDispatchRecoveryComplete = false;
    if (timetableReloadTask != null) {
      timetableReloadTask.cancel();
      timetableReloadTask = null;
    }
    if (storageManager != null) {
      storageManager.shutdown();
    }
  }

  /** 提供调试日志输出，受 config.yml 开关控制。 */
  public void debug(String message) {
    LoggerManager logger = this.loggerManager;
    if (logger == null) {
      return;
    }
    if (configManager != null
        && configManager.current() != null
        && configManager.current().debugEnabled()) {
      logger.debug(message);
    }
  }

  /**
   * 供命令调用的重载入口。
   *
   * @param sender 触发命令的玩家或控制台
   */
  public void reloadFromCommand(CommandSender sender) {
    if (configManager == null
        || loggerManager == null
        || localeManager == null
        || storageManager == null) {
      getLogger().warning("插件尚未完成初始化，无法重载");
      sender.sendMessage("插件尚未初始化，无法重载");
      return;
    }
    beginRuntimeDispatchRecovery("command-reload");
    java.time.Instant replacementAt = java.time.Instant.now();
    boolean persistentRollbacksContained =
        runtimeDispatchService == null
            || runtimeDispatchService.retryPersistentMaterializedSpawnRollbackRemovals();
    if (!persistentRollbacksContained
        || (spawnTicketAssigner != null
            && !spawnTicketAssigner.prepareForReplacement(replacementAt))) {
      getLogger().severe("重载已安全中止：仍有实体化发车事务未完成物理收容；请检查日志并重试 /fta reload");
      sender.sendMessage(localeManager.component("command.reload.materialized-spawn-pending"));
      scheduleRuntimeOccupancyReconstruction(1L);
      return;
    }
    SpawnManager.ReplacementSnapshot spawnReplacementSnapshot =
        spawnManager == null
            ? new SpawnManager.ReplacementSnapshot(List.of(), Map.of(), 0L)
            : spawnManager.snapshotForReplacement();
    List<SpawnTicket> replacementPendingTickets = new ArrayList<>();
    if (spawnTicketAssigner != null) {
      replacementPendingTickets.addAll(spawnTicketAssigner.snapshotPendingTickets());
    }
    if (displayService != null) {
      displayService.stop();
      displayService = null;
    }
    if (reclaimManager != null) {
      reclaimManager.stop();
      reclaimManager = null;
    }
    ConfigUpdater.forPlugin(getDataFolder(), () -> getResource("config.yml"), loggerManager)
        .update();
    this.configManager.reload();
    this.loggerManager.setDebugEnabled(configManager.current().debugEnabled());
    this.localeManager.reload(configManager.current().locale());
    this.storageManager.apply(configManager.current());
    if (etaService != null) {
      etaService.attachStorageProvider(storageManager.provider().orElse(null));
    }
    if (hudTemplateService != null) {
      hudTemplateService.reload();
    }
    if (hudDefaultTemplateService != null) {
      hudDefaultTemplateService.reload();
    }
    initRouteDefinitionCache();
    initHealthMonitor();
    initTimetable();
    initSpawnScheduler();
    if (spawnManager != null) {
      spawnManager.restoreForReplacement(
          spawnReplacementSnapshot, replacementPendingTickets, replacementAt);
    }
    initReclaimManager();
    initDisplayService();
    // 重新初始化公开 API，确保外部插件引用有效
    initApi();
    scheduleRuntimeOccupancyReconstruction(1L);
    sender.sendMessage(localeManager.component("command.reload.success"));
  }

  public LocaleManager getLocaleManager() {
    return localeManager;
  }

  public LoggerManager getLoggerManager() {
    return loggerManager;
  }

  /** 返回当前已加载的配置视图管理器（用于命令读取调度图/存储等配置）。 */
  public ConfigManager getConfigManager() {
    return configManager;
  }

  public StorageManager getStorageManager() {
    return storageManager;
  }

  public HudTemplateService getHudTemplateService() {
    return hudTemplateService;
  }

  public HudDefaultTemplateService getHudDefaultTemplateService() {
    return hudDefaultTemplateService;
  }

  /** 返回运行时调度服务（若未初始化则为空）。 */
  public Optional<RuntimeDispatchService> getRuntimeDispatchService() {
    return Optional.ofNullable(runtimeDispatchService);
  }

  public RailGraphService getRailGraphService() {
    return railGraphService;
  }

  /** 返回限速设置棍监听器；插件未完成初始化时为空。 */
  public Optional<SpeedSettingStickListener> getSpeedSettingStickListener() {
    return Optional.ofNullable(speedSettingStickListener);
  }

  /** RouteDefinition 缓存是否已加载。 */
  public boolean isRouteDefinitionCacheReady() {
    return routeDefinitionCache != null;
  }

  /** 根据 operator/line/route code 查询缓存定义。 */
  public Optional<RouteDefinition> findRouteDefinitionByCodes(
      String operatorCode, String lineCode, String routeCode) {
    if (routeDefinitionCache == null) {
      return Optional.empty();
    }
    return routeDefinitionCache.findByCodes(operatorCode, lineCode, routeCode);
  }

  /** 根据 route UUID 查询缓存定义。 */
  public Optional<RouteDefinition> findRouteDefinitionById(java.util.UUID routeId) {
    if (routeDefinitionCache == null || routeId == null) {
      return Optional.empty();
    }
    return routeDefinitionCache.findById(routeId);
  }

  /**
   * 与 {@link #findRouteDefinitionById} 的 {@code waypoints()} 下标一一对应的停靠配置。
   *
   * <p>解析不到图节点的停靠已剔除，与运行时、ETA 读同一份；直接读库的原始列表在某站缺图节点时会与 waypoints 错位。
   *
   * @param routeId route UUID
   * @return 对齐后的停靠配置；交路定义未加载时为空列表
   */
  public List<RouteStop> listRouteStopsById(java.util.UUID routeId) {
    if (routeDefinitionCache == null || routeId == null) {
      return List.of();
    }
    return routeDefinitionCache
        .findById(routeId)
        .map(definition -> routeDefinitionCache.listStops(definition.id()))
        .orElse(List.of());
  }

  /** 返回当前占用管理器（调度闭塞骨架）。 */
  public OccupancyManager getOccupancyManager() {
    return occupancyManager;
  }

  /** 返回 ETA 服务（若未初始化则为空）。 */
  public EtaService getEtaService() {
    return etaService;
  }

  /** 返回 SpawnManager（若未初始化则为空）。 */
  public Optional<SpawnManager> getSpawnManager() {
    return Optional.ofNullable(spawnManager);
  }

  /** 返回 TicketAssigner（若未初始化则为空）。 */
  public Optional<TicketAssigner> getSpawnTicketAssigner() {
    return Optional.ofNullable(spawnTicketAssigner);
  }

  /** 返回展示层服务（若未初始化则为空）。 */
  public Optional<DisplayService> getDisplayService() {
    return Optional.ofNullable(displayService);
  }

  /** 返回节点牌子注册表（用于 NodeId 冲突检测与路线编辑器）。 */
  public SignNodeRegistry getSignNodeRegistry() {
    return signNodeRegistry;
  }

  private void preloadRailGraphFromStorage() {
    RailGraphService service = railGraphService;
    if (service == null || storageManager == null || !storageManager.isReady()) {
      return;
    }
    storageManager
        .provider()
        .ifPresent(provider -> service.loadFromStorage(provider, getServer().getWorlds()));
  }

  private void registerCommands() {
    if (loggerManager == null) {
      getLogger().warning("loggerManager 未初始化，跳过命令注册");
      return;
    }
    this.cloudHandler.enable(this);
    this.commandManager = cloudHandler.getManager();
    registerCommandExceptionHandlers();
    FtaInfoCommand infoCommand = new FtaInfoCommand(this);
    new FtaStorageCommand(this).register(commandManager);
    new FtaCompanyCommand(this).register(commandManager);
    new FtaOperatorCommand(this).register(commandManager);
    new FtaLineCommand(this).register(commandManager);
    new FtaRouteCommand(this).register(commandManager);
    new FtaStationCommand(this).register(commandManager);
    new FtaStationGroupCommand(this).register(commandManager);
    new FtaDepotCommand(this).register(commandManager);
    new FtaEtaCommand(this).register(commandManager);
    new FtaOccupancyCommand(this).register(commandManager);
    new FtaSpawnCommand(this).register(commandManager);
    new FtaSpeedCommand(this).register(commandManager);
    new FtaTrainCommand(this).register(commandManager);
    new FtaGraphCommand(this).register(commandManager);
    new FtaTemplateCommand(this).register(commandManager);
    new FtaHealthCommand(this).register(commandManager);
    new FtaTimetableCommand(this).register(commandManager);
    infoCommand.register(commandManager);

    var bukkitCommand = getCommand("fta");
    if (bukkitCommand != null) {
      FtaRootCommand rootCommand = new FtaRootCommand(this, commandManager, infoCommand);
      bukkitCommand.setExecutor(rootCommand);
      bukkitCommand.setTabCompleter(rootCommand);
    } else {
      loggerManager.warn("未在 plugin.yml 中找到 fta 命令定义");
    }
  }

  private void registerCommandExceptionHandlers() {
    LocaleManager locale = this.localeManager;
    if (locale == null) {
      return;
    }
    cloudHandler.handle(
        InvalidSyntaxException.class,
        (sender, ex) ->
            sender.sendMessage(
                locale.component(
                    "command.error.invalid-syntax", Map.of("syntax", ex.correctSyntax()))));
  }

  private void registerSignActions() {
    this.signNodeRegistry = new SignNodeRegistry(loggerManager::debug);
    ConfigManager.GraphSettings graphSettings =
        configManager != null
            ? configManager.current().graphSettings()
            : ConfigManager.GraphSettings.defaults();
    this.railGraphService =
        new RailGraphService(
            new SignRegistryRailGraphBuilder(
                signNodeRegistry, loggerManager::debug, graphSettings.signAnchorSearchRadius()),
            loggerManager::debug);
    SignNodeStorageSynchronizer storageSync =
        new RailNodeIncrementalSync(storageManager, railGraphService, loggerManager::debug);
    this.waypointSignAction =
        new WaypointSignAction(signNodeRegistry, loggerManager::debug, localeManager, storageSync);
    this.autoStationSignAction =
        new AutoStationSignAction(
            this, signNodeRegistry, loggerManager::debug, localeManager, storageSync);
    this.depotSignAction =
        new DepotSignAction(signNodeRegistry, loggerManager::debug, localeManager, storageSync);
    SignAction.register(waypointSignAction);
    SignAction.register(autoStationSignAction);
    SignAction.register(depotSignAction);
    preloadSignNodeRegistryFromStorage();
    getServer()
        .getPluginManager()
        .registerEvents(
            new SignRemoveListener(
                signNodeRegistry, localeManager, loggerManager::debug, storageSync),
            this);
    getServer()
        .getPluginManager()
        .registerEvents(
            new RouteEditorAppendListener(
                this, signNodeRegistry, localeManager, loggerManager::debug),
            this);
    getServer()
        .getPluginManager()
        .registerEvents(
            new GraphDebugStickListener(
                this, signNodeRegistry, railGraphService, localeManager, loggerManager::debug),
            this);
    this.speedSettingStickListener =
        new SpeedSettingStickListener(
            this, signNodeRegistry, railGraphService, localeManager, loggerManager::debug);
    getServer().getPluginManager().registerEvents(speedSettingStickListener, this);
    getServer()
        .getPluginManager()
        .registerEvents(new TrainSignBypassListener(loggerManager::debug), this);
  }

  private void initOccupancyManager() {
    this.headwayRule = HeadwayRule.fixed(Duration.ZERO);
    this.signalEventBus = new SignalEventBus(runtimeDispatchDiagnostics());
    this.occupancyManager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy(), signalEventBus);
    if (this.railGraphService != null) {
      this.railGraphService.setSnapshotActivationGuard(
          () -> this.occupancyManager == null || this.occupancyManager.snapshotClaims().isEmpty());
    }
  }

  /**
   * 返回 Dispatcher、Signal 与 Health 共同使用的诊断出口。
   *
   * <p>生命周期测试或异常初始化路径可能在 gate 建立前调用局部初始化；该情况下保留普通 debug 输出，但正式插件启动必须 使用同一个有界
   * gate，避免多个周期组件分别放大控制台日志。
   *
   * @return 运行时诊断日志出口
   */
  private Consumer<String> runtimeDispatchDiagnostics() {
    if (runtimeDispatchDiagnosticGate != null) {
      return runtimeDispatchDiagnosticGate;
    }
    return loggerManager == null ? message -> {} : loggerManager::debug;
  }

  private void initRouteDefinitionCache() {
    if (this.routeDefinitionCache == null) {
      this.routeDefinitionCache = new RouteDefinitionCache(loggerManager::debug);
    }
    if (this.stationDirectory == null) {
      // 与交路缓存同寿命：重载不换实例，公开 API 的数据版本不会回退。
      this.stationDirectory = new StationDirectory(routeDefinitionCache, loggerManager::debug);
    }
    if (storageManager != null && storageManager.isReady()) {
      storageManager
          .provider()
          .ifPresent(
              provider -> {
                routeDefinitionCache.reload(provider);
                stationDirectory.reload(provider);
              });
    }
  }

  /** 车站目录（车站、车站组、停靠线路的内存索引）；插件未完成初始化时为空。 */
  public Optional<StationDirectory> getStationDirectory() {
    return Optional.ofNullable(stationDirectory);
  }

  /**
   * 车站、线路、运营商或车站组改库之后调用：重读车站目录的主数据并重建索引，公开 API 数据版本随之递增。
   *
   * <p>交路变化不需要调用它——交路缓存刷新时车站目录会自动重算。
   */
  public void refreshStationDirectory() {
    if (stationDirectory == null || storageManager == null || !storageManager.isReady()) {
      return;
    }
    storageManager.provider().ifPresent(stationDirectory::reload);
  }

  /**
   * 车站组改库之后调用：刷新车站目录，并在下一 tick 发出公开事件 {@code StationGroupChangedEvent}。
   *
   * @param change 车站组变化
   */
  public void notifyStationGroupChanged(StationGroupChange change) {
    Objects.requireNonNull(change, "change");
    refreshStationDirectory();
    if (apiEventBridge == null) {
      return;
    }
    StationGroupChangedEvent.ChangeType type =
        switch (change.kind()) {
          case CREATED -> StationGroupChangedEvent.ChangeType.CREATED;
          case MEMBER_ADDED -> StationGroupChangedEvent.ChangeType.MEMBER_ADDED;
          case MEMBER_UPDATED -> StationGroupChangedEvent.ChangeType.MEMBER_UPDATED;
          case MEMBER_REMOVED -> StationGroupChangedEvent.ChangeType.MEMBER_REMOVED;
          case DELETED -> StationGroupChangedEvent.ChangeType.DELETED;
        };
    apiEventBridge.onStationGroupChanged(
        type,
        change.groupId(),
        change.companyId(),
        change.groupCode(),
        change.stationId(),
        stationDirectory == null ? 0L : stationDirectory.revision());
  }

  /**
   * 仅刷新 RouteDefinition 缓存，避免重建实例导致运行时引用失效。
   *
   * @param provider 已就绪的 StorageProvider
   */
  public void reloadRouteDefinitions(StorageProvider provider) {
    if (provider == null || routeDefinitionCache == null) {
      return;
    }
    routeDefinitionCache.reload(provider);
  }

  /**
   * 增量刷新单条 RouteDefinition，避免全量遍历带来的卡顿。
   *
   * @param provider 已就绪的 StorageProvider
   * @param operator 线路所属运营商
   * @param line 线路
   * @param route 线路班次
   * @return 刷新后的定义（若节点不足则返回 empty）
   */
  public Optional<RouteDefinition> refreshRouteDefinition(
      StorageProvider provider, Operator operator, Line line, Route route) {
    if (provider == null || routeDefinitionCache == null) {
      return Optional.empty();
    }
    return routeDefinitionCache.refresh(provider, operator, line, route);
  }

  /**
   * 从缓存中移除单条 RouteDefinition。
   *
   * @param operator 线路所属运营商
   * @param line 线路
   * @param route 线路班次
   */
  public void removeRouteDefinition(Operator operator, Line line, Route route) {
    if (routeDefinitionCache == null) {
      return;
    }
    routeDefinitionCache.remove(operator, line, route);
  }

  /**
   * 初始化运行时调度：注册推进点监听 + 启动信号 tick。
   *
   * <p>启动后会延迟 1 tick 扫描现存列车，触发一次信号评估用于重建占用状态。
   */
  private void initRuntimeDispatch() {
    if (occupancyManager == null || railGraphService == null || configManager == null) {
      return;
    }
    this.routeProgressRegistry = new RouteProgressRegistry();
    this.layoverRegistry = new LayoverRegistry();
    this.dwellRegistry = new DwellRegistry();
    this.runtimeDispatchService =
        new RuntimeDispatchService(
            occupancyManager,
            railGraphService,
            routeDefinitionCache,
            routeProgressRegistry,
            signNodeRegistry,
            layoverRegistry,
            dwellRegistry,
            configManager,
            storageManager,
            new TrainConfigResolver(),
            runtimeDispatchDiagnostics());
    // 停靠事件的唯一出口：时刻表、HUD 在站判定、公开 API 事件都从这里按名字挂载。
    this.stationStopHub = new StationStopObserverHub(loggerManager::debug);
    this.stationPresence = new StationPresenceTracker();
    stationStopHub.register("station-presence", stationPresence);
    runtimeDispatchService.stationStops().setObserver(stationStopHub);
    runtimeDispatchRecoveryComplete = false;
    beginRuntimeDispatchRecovery("plugin-enable");
    runtimeDispatchService.setStartupRecoveryRequestedListener(
        () -> requestRuntimeDispatchRecovery("late-loaded-or-relinked-train"));
    getServer()
        .getPluginManager()
        .registerEvents(new RuntimeDispatchListener(runtimeDispatchService), this);
    initEtaService();
    if (etaService != null) {
      runtimeDispatchService.setEtaService(etaService);
    }
    initHealthMonitor();
    scheduleRuntimeOccupancyReconstruction(1L);
  }

  /**
   * 延迟重建运行时现场占用；安全证据暂不可用时保持 stop-first 并自动重试。
   *
   * <p>重试只重新读取 TrainCarts 当前列车集合，不主动加载区块。图快照、Route 或 claim 冲突恢复后，下一轮会自动完成水合并开放授权。
   */
  private void scheduleRuntimeOccupancyReconstruction(long delayTicks) {
    RuntimeDispatchService service = runtimeDispatchService;
    if (service == null || (runtimeRecoveryTask != null && !runtimeRecoveryTask.isCancelled())) {
      // 已有待执行任务时丢弃本次请求是有意的（避免重复重建），但丢弃本身必须可见：
      // 若任务因故永不执行，这里就是"恢复请求全部被吞掉"的唯一证据。
      debug(
          "运行时占用重建请求被忽略: reason=" + (service == null ? "service-missing" : "task-already-pending"));
      return;
    }
    runtimeRecoveryTask =
        getServer()
            .getScheduler()
            .runTaskLater(
                this,
                () -> {
                  runtimeRecoveryTask = null;
                  boolean completed = false;
                  List<RuntimeTrainHandle> handles = new ArrayList<>();
                  try {
                    boolean persistentRollbacksContained =
                        service.retryPersistentMaterializedSpawnRollbackRemovals();
                    boolean incompleteFtaIdentityFound = false;
                    for (MinecartGroup group : MinecartGroupStore.getGroups()) {
                      if (group == null || !group.isValid()) {
                        continue;
                      }
                      com.bergerkiller.bukkit.tc.properties.TrainProperties properties =
                          group.getProperties();
                      TrainCartsRuntimeHandle handle = new TrainCartsRuntimeHandle(group);
                      if (properties != null
                          && service.hasMaterializedSpawnRollbackTag(properties)) {
                        incompleteFtaIdentityFound = true;
                        if (!service.resumeMaterializedSpawnRollback(handle)) {
                          service.handleAbnormalGroup(
                              group, "startup-materialized-rollback-pending");
                        }
                        continue;
                      }
                      if (properties != null
                          && service.hasFtaRuntimeTag(properties)
                          && !service.hasCompleteFtaRouteIdentity(properties)) {
                        incompleteFtaIdentityFound = true;
                        service.handleAbnormalGroup(group, "startup-incomplete-fta-identity");
                        continue;
                      }
                      handles.add(handle);
                    }
                    boolean materializedSpawnsContained =
                        spawnTicketAssigner == null
                            || spawnTicketAssigner.prepareForReplacement(java.time.Instant.now());
                    if (!persistentRollbacksContained
                        || !materializedSpawnsContained
                        || incompleteFtaIdentityFound) {
                      service.beginStartupOccupancyReconstruction(
                          "scheduleRuntimeOccupancyReconstruction");
                      debug("运行时恢复等待实体化发车或身份异常物理收容完成，保持 STOP_FIRST");
                    } else {
                      completed = service.prepareStartupOccupancySnapshot(handles);
                    }
                  } catch (RuntimeException | LinkageError ex) {
                    service.beginStartupOccupancyReconstruction(
                        "scheduleRuntimeOccupancyReconstruction");
                    debug(
                        "运行时占用重建失败，已回退 STOP_FIRST 并等待重试: "
                            + ex.getClass().getSimpleName()
                            + ":"
                            + String.valueOf(ex.getMessage()));
                  }
                  boolean snapshotPrepared = completed;
                  if (completed && isEnabled() && runtimeDispatchService == service) {
                    completed = startRuntimeDispatchComponentsAfterRecovery(service, handles);
                  }
                  // 每次重建尝试都必须留下结论。实服 2026-09-13 的全线冻结里，恢复被请求了 5 次，
                  // 但三条既有日志（等待收容 / 重建失败 / 桥已启动）一条都没出现——既定不了它有没有跑，
                  // 也定不了是快照没准备好还是组件没起来。这一行让下一次现场直接给出答案。
                  debug(
                      "运行时占用重建尝试: handles="
                          + handles.size()
                          + " snapshotPrepared="
                          + snapshotPrepared
                          + " componentsStarted="
                          + completed
                          + " willRetry="
                          + (!completed && isEnabled() && runtimeDispatchService == service));
                  if (!completed && isEnabled() && runtimeDispatchService == service) {
                    scheduleRuntimeOccupancyReconstruction(20L);
                  }
                },
                Math.max(1L, delayTicks));
  }

  /** 由调度服务请求的迟加载/重组列车全局 fail-safe 恢复。 */
  private void requestRuntimeDispatchRecovery(String reason) {
    if (!isEnabled() || runtimeDispatchService == null) {
      return;
    }
    beginRuntimeDispatchRecovery(reason);
    scheduleRuntimeOccupancyReconstruction(1L);
  }

  /**
   * 在现场占用完成原子提交后启动所有可能签发授权或改变列车生命周期的组件。
   *
   * <p>健康恢复、周期信号、事件信号、自动发车和折返回收共享同一个启动门，避免其中任一组件在空快照或半水合快照上抢先运行。
   */
  private boolean startRuntimeDispatchComponentsAfterRecovery(
      RuntimeDispatchService service, Collection<? extends RuntimeTrainHandle> handles) {
    if (runtimeDispatchRecoveryComplete) {
      return true;
    }
    if (service == null || runtimeDispatchService != service) {
      return false;
    }
    try {
      restartHealthMonitorTask();
      restartRuntimeMonitor();
      initSignalEventDrivenComponents();
      restartSpawnMonitor();
      if (reclaimManager != null) {
        reclaimManager.start();
      }
      if (!service.completeStartupOccupancyReconstruction(handles)) {
        runtimeDispatchRecoveryComplete = false;
        suspendRuntimeDispatchComponentsForRecovery();
        debug("运行时授权刷新未完成，保持 STOP_FIRST 并等待重试");
        return false;
      }
      runtimeDispatchRecoveryComplete = true;
      debug("运行时现场占用已原子恢复，调度/信号/发车组件已启动");
      return true;
    } catch (RuntimeException | LinkageError ex) {
      suspendRuntimeDispatchComponentsForRecovery();
      service.beginStartupOccupancyReconstruction("startRuntimeDispatchComponentsAfterRecovery");
      debug(
          "运行时组件恢复失败，保持 STOP_FIRST 并等待重试: "
              + ex.getClass().getSimpleName()
              + ":"
              + String.valueOf(ex.getMessage()));
      return false;
    }
  }

  /**
   * 进入启动/重载共用的运行时恢复事务。
   *
   * <p>先关闭调度服务的授权门以及所有 wake-up、发车和周期组件，再在主线程同步冻结全部受管编组。RuntimeDispatchListener
   * 保持注册，用服务层统一门控吸收恢复窗口内的牌子与列车事件。
   */
  private void beginRuntimeDispatchRecovery(String reason) {
    runtimeDispatchRecoveryComplete = false;
    RuntimeDispatchService service = runtimeDispatchService;
    if (service != null) {
      service.beginStartupOccupancyReconstruction("beginRuntimeDispatchRecovery");
    }
    suspendRuntimeDispatchComponentsForRecovery();
    try {
      if (service != null) {
        for (MinecartGroup group : MinecartGroupStore.getGroups()) {
          freezeRuntimeGroupForRecovery(service, group);
        }
      }
    } catch (RuntimeException | LinkageError ex) {
      debug(
          "运行时恢复枚举现场列车失败，继续保持 STOP_FIRST: error="
              + ex.getClass().getSimpleName()
              + ":"
              + String.valueOf(ex.getMessage()));
    }
    debug("运行时恢复事务已进入 STOP_FIRST: reason=" + String.valueOf(reason));
  }

  /**
   * 将插件置入停用状态，而不再触碰 TrainCarts 的现场编组。
   *
   * <p>Paper 的服务器关闭可能在非主线程执行插件禁用。此时枚举或冻结 {@link MinecartGroup} 会把 FTA 的恢复流程带入
   * TrainCarts/BKCommonLib 的线程边界，也可能让尚未关闭的重评估 scheduler 尝试注册下一 tick 任务。停用只需撤销授权 与 wake-up，并让运行时保持
   * STOP_FIRST；服务器随后会负责实体和世界的最终关闭。
   */
  private void beginRuntimeDispatchShutdown() {
    runtimeDispatchRecoveryComplete = false;
    if (runtimeDispatchService != null) {
      runtimeDispatchService.beginPluginShutdown();
    }
    suspendRuntimeDispatchComponentsForRecovery();
    debug("运行时停用事务已进入 STOP_FIRST: reason=plugin-disable");
  }

  /** 在完整 ABI 边界内识别并冻结一个现场编组，错误日志不再次访问 TrainCarts 对象。 */
  private void freezeRuntimeGroupForRecovery(RuntimeDispatchService service, MinecartGroup group) {
    String trainName = "-";
    try {
      if (group == null || !group.isValid()) {
        return;
      }
      com.bergerkiller.bukkit.tc.properties.TrainProperties properties = group.getProperties();
      if (properties == null) {
        return;
      }
      trainName = String.valueOf(properties.getTrainName());
      if (!service.hasFtaRuntimeTag(properties)) {
        return;
      }
      TrainCartsRuntimeHandle handle = new TrainCartsRuntimeHandle(group);
      handle.stopHard();
      service.handleSignalTick(group);
    } catch (RuntimeException | LinkageError ex) {
      debug(
          "运行时恢复冻结现场列车失败: train="
              + trainName
              + " error="
              + ex.getClass().getSimpleName()
              + ":"
              + String.valueOf(ex.getMessage()));
    }
  }

  /** 停止所有可能签发授权或改变列车生命周期的运行时组件。 */
  private void suspendRuntimeDispatchComponentsForRecovery() {
    if (signalEvaluator != null) {
      signalEvaluator.stop();
      signalEvaluator = null;
    }
    if (runtimeDispatchService != null) {
      runtimeDispatchService.setSignalReevaluationRequester(null);
    }
    if (signalReevaluationScheduler != null) {
      signalReevaluationScheduler.close();
      signalReevaluationScheduler = null;
    }
    if (runtimeMonitorTask != null) {
      runtimeMonitorTask.cancel();
      runtimeMonitorTask = null;
    }
    if (healthMonitorTask != null) {
      healthMonitorTask.cancel();
      healthMonitorTask = null;
    }
    if (spawnMonitorTask != null) {
      spawnMonitorTask.cancel();
      spawnMonitorTask = null;
    }
    if (reclaimManager != null) {
      reclaimManager.stop();
    }
  }

  /**
   * 初始化占用事件驱动的下一 tick 完整信号重评估组件。
   *
   * <p>事件只触发 wake-up；实际 signal、占用取得和 Movement Authority 仍由 RuntimeDispatchService 的完整周期入口统一计算。
   */
  private void initSignalEventDrivenComponents() {
    if (signalEventBus == null
        || occupancyManager == null
        || railGraphService == null
        || runtimeDispatchService == null
        || loggerManager == null) {
      return;
    }
    if (signalEvaluator != null) {
      signalEvaluator.stop();
      signalEvaluator = null;
    }
    runtimeDispatchService.setSignalReevaluationRequester(null);
    if (signalReevaluationScheduler != null) {
      signalReevaluationScheduler.close();
      signalReevaluationScheduler = null;
    }
    // 创建占用事件桥的只读等待列车查询器
    RuntimeDispatchRequestProvider requestProvider =
        new RuntimeDispatchRequestProvider(
            occupancyManager, runtimeDispatchService::trainsWaitingForDynamicCapacity);
    signalReevaluationScheduler =
        new RuntimeSignalReevaluationScheduler(
            task -> getServer().getScheduler().runTask(this, task),
            runtimeDispatchService::reevaluateSignalByName,
            runtimeDispatchService::failClosedAfterSignalReevaluationFailure,
            runtimeDispatchDiagnostics());
    runtimeDispatchService.setSignalReevaluationRequester(signalReevaluationScheduler::request);
    signalEvaluator =
        new SignalEvaluator(
            signalEventBus,
            requestProvider,
            signalReevaluationScheduler::request,
            runtimeDispatchService::failClosedAfterSignalReevaluationFailure,
            runtimeDispatchDiagnostics());
    signalEvaluator.start();
    loggerManager.debug("占用事件下一 tick 完整信号重评估组件已启动");
  }

  private void initEtaService() {
    if (railGraphService == null || routeDefinitionCache == null || routeProgressRegistry == null) {
      return;
    }
    if (trainSnapshotStore == null) {
      trainSnapshotStore = new TrainSnapshotStore();
    }
    etaRuntimeSampler =
        new EtaRuntimeSampler(
            routeProgressRegistry,
            trainSnapshotStore,
            runtimeDispatchService != null ? runtimeDispatchService::getActiveStopState : null,
            loggerManager::debug);
    etaService = new EtaService(trainSnapshotStore, railGraphService, routeDefinitionCache);
    if (layoverRegistry != null) {
      etaService.attachLayoverRegistry(layoverRegistry);
    }
    if (storageManager != null && storageManager.isReady()) {
      etaService.attachStorageProvider(storageManager.provider().orElse(null));
    }
    // ETA 的等待只看运行时真实停车状态（信号、占用、授权、尾保等），扣多久顺延多久。
    etaService.attachDebugLogger(loggerManager::debug);
    if (runtimeDispatchService != null) {
      etaService.attachRuntimeStopStates(runtimeDispatchService::getActiveStopState);
      // DYNAMIC 选台后按实际股道估算：与控车读同一份有效节点。
      etaService.attachEffectiveWaypoints(
          runtimeDispatchService::resolveEffectiveWaypointsForEvent);
    }
    // 到站后、停站计时注册前的几秒，本站停站按计划计入 ETA。
    etaService.attachStationPresence(this::getStationPresence);
    // 站牌行显示直通换线后的线路时，代码按主数据的写法（与公开 API、HUD 同一口径）。
    etaService.attachLineCanonicalizer(
        line -> stationDirectory == null ? line : stationDirectory.snapshot().canonicalLine(line));
    // 走行参数（车种加减速、进站规则、默认速度、停站开销）与编表读同一组配置；每次估算现读，重载即生效。
    etaService.attachConfigSources(
        signNodeRegistry, () -> configManager == null ? null : configManager.current());
  }

  private void restartRuntimeMonitor() {
    if (runtimeMonitorTask != null) {
      runtimeMonitorTask.cancel();
      runtimeMonitorTask = null;
    }
    if (runtimeDispatchService == null || configManager == null) {
      return;
    }
    int interval = configManager.current().runtimeSettings().dispatchTickIntervalTicks();
    runtimeMonitorTask =
        getServer()
            .getScheduler()
            .runTaskTimer(
                this,
                new RuntimeSignalMonitor(
                    runtimeDispatchService,
                    etaRuntimeSampler,
                    trainSnapshotStore,
                    dwellRegistry,
                    routeProgressRegistry,
                    routeDefinitionCache,
                    interval),
                1L,
                1L);
  }

  /** 重启健康检查定时任务（与 RuntimeSignalMonitor 解耦，避免遗漏初始化或异常链路影响）。 */
  private void restartHealthMonitorTask() {
    if (healthMonitorTask != null) {
      healthMonitorTask.cancel();
      healthMonitorTask = null;
    }
    if (healthMonitor == null) {
      return;
    }
    // 固定 1s tick，具体检查频率由 HealthMonitor.checkInterval 控制。
    healthMonitorTask =
        getServer().getScheduler().runTaskTimer(this, healthMonitor::tick, 20L, 20L);
  }

  public Optional<DwellRegistry> getDwellRegistry() {
    return Optional.ofNullable(dwellRegistry);
  }

  /** 返回列车在站记录（只供显示与估算使用，不参与控车）。 */
  public Optional<StationPresenceTracker> getStationPresence() {
    return Optional.ofNullable(stationPresence);
  }

  /** 返回停靠事件分发器（若运行时未初始化则为空）。 */
  public Optional<StationStopObserverHub> getStationStopHub() {
    return Optional.ofNullable(stationStopHub);
  }

  /** 返回健康监控器（若未初始化则为空）。 */
  public Optional<org.fetarute.fetaruteTCAddon.dispatcher.health.HealthMonitor> getHealthMonitor() {
    return Optional.ofNullable(healthMonitor);
  }

  private void initHealthMonitor() {
    if (runtimeDispatchService == null
        || occupancyManager == null
        || dwellRegistry == null
        || configManager == null) {
      return;
    }
    ConfigManager.HealthSettings settings = configManager.current().healthSettings();
    this.healthMonitor =
        new org.fetarute.fetaruteTCAddon.dispatcher.health.HealthMonitor(
            runtimeDispatchService,
            occupancyManager,
            dwellRegistry,
            configManager,
            runtimeDispatchDiagnostics());
    // 应用配置
    healthMonitor.setEnabled(settings.enabled());
    healthMonitor.setCheckInterval(java.time.Duration.ofSeconds(settings.checkIntervalSeconds()));
    healthMonitor.setStallThreshold(java.time.Duration.ofSeconds(settings.stallThresholdSeconds()));
    healthMonitor.setProgressStuckThreshold(
        java.time.Duration.ofSeconds(settings.progressStuckThresholdSeconds()));
    healthMonitor.setProgressStopGraceThreshold(
        java.time.Duration.ofSeconds(settings.progressStopGraceSeconds()));
    healthMonitor.setDeadlockThreshold(
        java.time.Duration.ofSeconds(settings.deadlockThresholdSeconds()));
    healthMonitor.setDeadlockDestroyThreshold(
        java.time.Duration.ofSeconds(settings.deadlockDestroyThresholdSeconds()));
    healthMonitor.setTrainCleanupEnabled(settings.trainCleanupEnabled());
    healthMonitor.setDeadlockDestroyCooldown(
        java.time.Duration.ofSeconds(settings.deadlockDestroyCooldownSeconds()));
    healthMonitor.setStuckCleanupThreshold(
        java.time.Duration.ofSeconds(settings.stuckCleanupThresholdSeconds()));
    healthMonitor.setStuckCleanupPassengerThreshold(
        java.time.Duration.ofSeconds(settings.stuckCleanupPassengerThresholdSeconds()));
    healthMonitor.setStuckCleanupCooldown(
        java.time.Duration.ofSeconds(settings.stuckCleanupCooldownSeconds()));
    healthMonitor.setDeadlockEpisodeGrace(
        java.time.Duration.ofSeconds(settings.deadlockEpisodeGraceSeconds()));
    healthMonitor.setDeadlockMinStopDuration(
        java.time.Duration.ofSeconds(settings.deadlockMinStopSeconds()));
    healthMonitor.setBlockerSnapshotMaxAge(
        java.time.Duration.ofSeconds(settings.blockerSnapshotMaxAgeSeconds()));
    healthMonitor.setRecoveryCooldown(
        java.time.Duration.ofSeconds(settings.recoveryCooldownSeconds()));
    healthMonitor.setOccupancyTimeout(
        java.time.Duration.ofMinutes(settings.occupancyTimeoutMinutes()));
    healthMonitor.setAutoFixEnabled(settings.autoFixEnabled());
    healthMonitor.setOrphanCleanupEnabled(settings.orphanCleanupEnabled());
    healthMonitor.setTimeoutCleanupEnabled(settings.timeoutCleanupEnabled());
    debug("健康监控器已初始化: enabled=" + settings.enabled());
  }

  /**
   * 初始化按表运行。
   *
   * <p>服务跨 reload 复用同一个实例：运行期的车次绑定与交路进度只活在内存里，重建实例等于让 {@code /fta reload} 悄悄把全网列车的交路额度清零。reload
   * 时只重挂配置与定时任务。
   *
   * <p>装配顺序也在这里定死：先挂计划源与扣留上限，再挂车辆复用闸。反过来会出现"已经开始按交路否决复用、 但扣留上限还是
   * 0"的半装配窗口——那个窗口里车辆被拒绝接班却没有任何时刻约束，看起来像无故停运。
   */
  private void initTimetable() {
    if (runtimeDispatchService == null || configManager == null) {
      return;
    }
    if (timetableService == null) {
      timetableService =
          new org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableService(
              java.time.Instant::now, loggerManager::debug);
    }
    ConfigManager.TimetableSettings settings = configManager.current().timetableSettings();
    timetableService.applySettings(
        new org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableService.Settings(
            settings.enabled(),
            settings.spawnEnabled(),
            java.time.Duration.ofSeconds(settings.holdMaxSeconds()),
            java.time.Duration.ofSeconds(settings.assignToleranceSeconds()),
            java.time.Duration.ofSeconds(settings.maxCatchUpSeconds()),
            settings.resolveZone()));
    runtimeDispatchService.stationStops().setPlan(settings.enabled() ? timetableService : null);
    // 列车销毁/改派时立刻释放它的车次绑定、交路进度与交路归属，不等下一次定时 retain：
    // 迟释放会让 trip claim 挂着、让同名新车继承旧交路。观察者不依赖开关，release 在关闭状态下是空操作。
    stationStopHub.register(
        "timetable",
        new org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationStopObserver() {
          @Override
          public void onStationArrival(
              org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationStopEvent event) {}

          @Override
          public void onStationDeparture(
              org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationStopEvent event) {}

          @Override
          public void onTrainReleased(String trainName, String reason) {
            timetableService.release(trainName, reason);
          }
        });
    runtimeDispatchService
        .stationStops()
        .setMaxHold(
            settings.enabled() ? java.time.Duration.ofSeconds(settings.holdMaxSeconds()) : null);
    // ETA 与站内扣留同一口径：早到的车在站内等点的时间计入 ETA，上限同扣留上限（含 150 秒硬顶）。
    if (etaService != null) {
      etaService.attachPlannedDepartures(
          settings.enabled() ? timetableService::plannedDepartureOf : null,
          settings.enabled()
              ? java.time.Duration.ofSeconds(
                  Math.min(
                      settings.holdMaxSeconds(),
                      org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationStopCoordinator
                          .HOLD_CEILING.toSeconds()))
              : null);
    }
    restartTimetableTasks(settings);
    reloadPublishedTimetables();
  }

  private void restartTimetableTasks(ConfigManager.TimetableSettings settings) {
    if (timetableReloadTask != null) {
      timetableReloadTask.cancel();
      timetableReloadTask = null;
    }
    long reloadTicks = Math.max(20L, settings.reloadIntervalSeconds() * 20L);
    // 读库放异步线程：TimetableService 的快照是 volatile 整体替换，绑定表是并发容器，不需要主线程。
    timetableReloadTask =
        getServer()
            .getScheduler()
            .runTaskTimerAsynchronously(
                this, this::reloadPublishedTimetables, reloadTicks, reloadTicks);
  }

  /**
   * 刷新已发布时刻表缓存（可在任意线程调用），然后回主线程把已经不在网的列车兜底释放掉。
   *
   * <p>正常的释放走 StationStopObserver；这里的 retain 只是兜底，用调度层的规范列车名，与绑定表的键同一口径。
   */
  private void reloadPublishedTimetables() {
    if (timetableService == null || storageManager == null || !storageManager.isReady()) {
      return;
    }
    storageManager.provider().ifPresent(timetableService::reload);
    if (getServer().isPrimaryThread()) {
      retainActiveTimetableTrains();
    } else {
      getServer().getScheduler().runTask(this, this::retainActiveTimetableTrains);
    }
  }

  private void retainActiveTimetableTrains() {
    if (timetableService == null || runtimeDispatchService == null) {
      return;
    }
    java.util.List<String> activeNames = new ArrayList<>();
    for (MinecartGroup group : MinecartGroupStore.getGroups()) {
      if (group == null || !group.isValid()) {
        continue;
      }
      com.bergerkiller.bukkit.tc.properties.TrainProperties properties = group.getProperties();
      if (properties == null) {
        continue;
      }
      runtimeDispatchService.resolveTrackedTrainName(properties).ifPresent(activeNames::add);
      if (properties.getTrainName() != null) {
        activeNames.add(properties.getTrainName());
      }
    }
    timetableService.retain(activeNames);
    if (stationPresence != null) {
      stationPresence.retain(java.util.Set.copyOf(activeNames));
    }
  }

  /** 返回按表运行服务（若未初始化则为空）。 */
  public Optional<org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableService>
      getTimetableService() {
    return Optional.ofNullable(timetableService);
  }

  private void initSpawnScheduler() {
    if (configManager == null
        || storageManager == null
        || occupancyManager == null
        || railGraphService == null
        || routeDefinitionCache == null
        || runtimeDispatchService == null
        || signNodeRegistry == null) {
      return;
    }
    ConfigManager.SpawnSettings spawnSettings = configManager.current().spawnSettings();
    StorageSpawnManager.SpawnManagerSettings managerSettings =
        new StorageSpawnManager.SpawnManagerSettings(
            java.time.Duration.ofMillis(spawnSettings.planRefreshTicks() * 50L),
            java.time.Duration.ZERO,
            spawnSettings.maxBacklogPerService(),
            spawnSettings.maxGeneratePerTick(),
            Math.max(1, spawnSettings.maxSpawnPerTick()),
            java.time.Duration.ofSeconds(spawnSettings.queuedTicketMaxAgeSeconds()));
    SpawnManager baseSpawnManager = new StorageSpawnManager(managerSettings, loggerManager::debug);
    // 按表运行打开时才套上装饰器：关闭状态下发车链路里完全看不到时刻表这一层。
    this.spawnManager =
        timetableService != null && configManager.current().timetableSettings().enabled()
            ? new org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.TimetableSpawnManager(
                baseSpawnManager, timetableService, loggerManager::debug)
            : baseSpawnManager;
    TrainCartsDepotSpawner depotSpawner =
        new TrainCartsDepotSpawner(this, signNodeRegistry, loggerManager::debug);
    depotSpawner.setOccupancyManager(occupancyManager);
    SimpleTicketAssigner simpleAssigner =
        new SimpleTicketAssigner(
            spawnManager,
            depotSpawner,
            occupancyManager,
            railGraphService,
            routeDefinitionCache,
            runtimeDispatchService,
            configManager,
            signNodeRegistry,
            layoverRegistry,
            loggerManager::debug,
            java.time.Duration.ofMillis(spawnSettings.retryDelayTicks() * 50L),
            spawnSettings.maxSpawnPerTick(),
            spawnSettings.maxAttempts());
    this.spawnTicketAssigner = simpleAssigner;
    runtimeDispatchService.setLayoverListener(spawnTicketAssigner::onLayoverRegistered);
    // 车辆交路额度用完就不再接运营班次。回收动作仍由 ReclaimManager/StorageSpawnManager 负责，
    // 这里只是把"不准再接班"这个事实告诉它们——时刻表层不复制一套车辆所有权。
    simpleAssigner.setLayoverReuseGate(
        timetableService == null ? null : timetableService::allowsLayoverReuse);
    // 镜像闸：按表发出的回库票只能带走交路已经跑完的车。
    simpleAssigner.setReturnReuseGate(
        timetableService == null ? null : timetableService::allowsReturn);
    // 票据认识 duty：续班只接本交路的车、到期作废、派发后把车绑到交路上。
    if (spawnManager
        instanceof
        org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.TimetableSpawnManager
        scheduled) {
      simpleAssigner.setLayoverCandidateFilter(scheduled::acceptsCandidate);
      simpleAssigner.setTicketExpiry(scheduled::expiryOf);
      simpleAssigner.setDispatchListener(scheduled::onDispatched);
    }
    if (etaService != null) {
      etaService.attachTicketSources(spawnManager, spawnTicketAssigner);
    }
    if (runtimeDispatchRecoveryComplete) {
      restartSpawnMonitor();
    }
  }

  private void restartSpawnMonitor() {
    if (spawnMonitorTask != null) {
      spawnMonitorTask.cancel();
      spawnMonitorTask = null;
    }
    if (spawnTicketAssigner == null || configManager == null || storageManager == null) {
      return;
    }
    ConfigManager.SpawnSettings settings = configManager.current().spawnSettings();
    if (settings == null || !settings.enabled()) {
      return;
    }
    int interval = settings.tickIntervalTicks();
    spawnMonitorTask =
        getServer()
            .getScheduler()
            .runTaskTimer(
                this,
                new SpawnMonitor(storageManager, configManager, spawnTicketAssigner),
                interval,
                interval);
  }

  private void initReclaimManager() {
    if (layoverRegistry == null
        || spawnTicketAssigner == null
        || configManager == null
        || loggerManager == null) {
      return;
    }
    this.reclaimManager =
        new ReclaimManager(
            this, layoverRegistry, spawnTicketAssigner, configManager, loggerManager::debug);
    // 回收与表定回库票同一个判据：交路还有班次要跑的车不收。
    reclaimManager.setReturnGate(timetableService == null ? null : timetableService::allowsReturn);
    if (runtimeDispatchRecoveryComplete) {
      this.reclaimManager.start();
    }
  }

  /**
   * 初始化展示层（HUD/站牌等）。
   *
   * <p>该层只消费调度/ETA 的快照与缓存，不参与控车与调度决策。
   */
  private void initDisplayService() {
    if (configManager == null || etaService == null || routeDefinitionCache == null) {
      return;
    }
    if (displayService != null) {
      displayService.stop();
    }
    displayService =
        new SimpleDisplayService(
            this,
            configManager,
            etaService,
            routeDefinitionCache,
            routeProgressRegistry,
            layoverRegistry,
            hudTemplateService);
    displayService.start();
  }

  /** 初始化 HUD 模板服务（加载模板与线路绑定缓存）。 */
  private void initHudTemplateService() {
    this.hudTemplateService = new HudTemplateService(storageManager, loggerManager::debug);
    this.hudTemplateService.reload();
  }

  /** 初始化 HUD 默认模板服务（读取 default_hud_template.yml）。 */
  private void initHudDefaultTemplateService() {
    this.hudDefaultTemplateService = new HudDefaultTemplateService(this, loggerManager);
    this.hudDefaultTemplateService.reload();
  }

  /** 从 rail_nodes 预热节点注册表，确保重启后仍可进行 NodeId 冲突检测。 */
  private void preloadSignNodeRegistryFromStorage() {
    SignNodeRegistry registry = signNodeRegistry;
    StorageManager storage = storageManager;
    LoggerManager logger = loggerManager;
    if (registry == null || storage == null || logger == null || !storage.isReady()) {
      return;
    }
    storage
        .provider()
        .ifPresent(
            provider -> {
              for (org.bukkit.World world : getServer().getWorlds()) {
                if (world == null) {
                  continue;
                }
                java.util.UUID worldId = world.getUID();
                java.util.List<RailNodeRecord> nodes;
                try {
                  nodes = provider.railNodes().listByWorld(worldId);
                } catch (Exception ex) {
                  logger.warn("预热节点注册表失败: world=" + world.getName() + " msg=" + ex.getMessage());
                  continue;
                }

                int loaded = 0;
                for (RailNodeRecord node : nodes) {
                  if (node == null) {
                    continue;
                  }
                  if (node.nodeType() != NodeType.WAYPOINT
                      && node.nodeType() != NodeType.STATION
                      && node.nodeType() != NodeType.DEPOT) {
                    continue;
                  }
                  registry.put(
                      worldId,
                      world.getName(),
                      node.x(),
                      node.y(),
                      node.z(),
                      new SignNodeDefinition(
                          node.nodeId(),
                          node.nodeType(),
                          node.trainCartsDestination(),
                          node.waypointMetadata()));
                  loaded++;
                }
                logger.debug("从存储预热节点注册表: world=" + world.getName() + " nodes=" + loaded);
              }
            });
  }

  private void unregisterSignActions() {
    if (waypointSignAction != null) {
      SignAction.unregister(waypointSignAction);
    }
    if (autoStationSignAction != null) {
      SignAction.unregister(autoStationSignAction);
    }
    if (depotSignAction != null) {
      SignAction.unregister(depotSignAction);
    }
    if (signNodeRegistry != null) {
      signNodeRegistry.clear();
    }
    if (runtimeMonitorTask != null) {
      runtimeMonitorTask.cancel();
      runtimeMonitorTask = null;
    }
    if (healthMonitorTask != null) {
      healthMonitorTask.cancel();
      healthMonitorTask = null;
    }
  }

  /** 初始化外部 API 模块，供外部插件访问调度数据。 */
  private void initApi() {
    // 先停旧的事件桥：下面任何一处提前返回都不能留下上一轮的桥与定时任务。
    stopApiEvents();
    if (railGraphService == null
        || trainSnapshotStore == null
        || routeProgressRegistry == null
        || routeDefinitionCache == null
        || occupancyManager == null) {
      getLogger().warning("无法初始化公开 API：缺少必要的运行时组件");
      return;
    }
    org.fetarute.fetaruteTCAddon.api.graph.GraphApi graphApi =
        new org.fetarute.fetaruteTCAddon.api.internal.GraphApiImpl(railGraphService);
    org.fetarute.fetaruteTCAddon.api.train.TrainApi trainApi =
        new org.fetarute.fetaruteTCAddon.api.internal.TrainApiImpl(
            trainSnapshotStore,
            routeProgressRegistry,
            routeDefinitionCache,
            etaService,
            stationDirectory);
    org.fetarute.fetaruteTCAddon.api.route.RouteApi routeApi =
        new org.fetarute.fetaruteTCAddon.api.internal.RouteApiImpl(
            routeDefinitionCache, stationDirectory);
    org.fetarute.fetaruteTCAddon.api.occupancy.OccupancyApi occupancyApi =
        new org.fetarute.fetaruteTCAddon.api.internal.OccupancyApiImpl(occupancyManager);
    // 站点 API
    org.fetarute.fetaruteTCAddon.api.station.StationApi stationApi = null;
    // 运营商 API
    org.fetarute.fetaruteTCAddon.api.operator.OperatorApi operatorApi = null;
    // 线路 API
    org.fetarute.fetaruteTCAddon.api.line.LineApi lineApi = null;
    if (storageManager != null && storageManager.provider().isPresent()) {
      var provider = storageManager.provider().get();
      stationApi =
          new org.fetarute.fetaruteTCAddon.api.internal.StationApiImpl(
              provider.stations(), provider.companies(), provider.operators(), stationDirectory);
      operatorApi =
          new org.fetarute.fetaruteTCAddon.api.internal.OperatorApiImpl(
              provider.operators(), provider.companies());
      lineApi =
          new org.fetarute.fetaruteTCAddon.api.internal.LineApiImpl(
              provider.lines(), provider.companies(), provider.operators());
    }
    org.fetarute.fetaruteTCAddon.api.eta.EtaApi etaApi =
        etaService != null
            ? new org.fetarute.fetaruteTCAddon.api.internal.EtaApiImpl(etaService)
            : null;
    org.fetarute.fetaruteTCAddon.api.timetable.TimetableApi timetableApi =
        new org.fetarute.fetaruteTCAddon.api.internal.TimetableApiImpl(
            () -> Optional.ofNullable(timetableService),
            this::getStationPresence,
            () -> Optional.ofNullable(etaService),
            () -> getServer().getCurrentTick());
    org.fetarute.fetaruteTCAddon.api.FetaruteApi.initialize(
        graphApi,
        trainApi,
        routeApi,
        occupancyApi,
        stationApi,
        operatorApi,
        lineApi,
        etaApi,
        timetableApi,
        () -> stationDirectory == null ? 0L : stationDirectory.revision());
    startApiEvents();
    getLogger()
        .info("公开 API v" + org.fetarute.fetaruteTCAddon.api.FetaruteApi.API_VERSION + " 已初始化");
  }

  /**
   * 启动公开 API 事件桥（启用与重载时调用，先停掉旧的）。
   *
   * <p>桥只从停靠事件、告警总线与只读快照取事实，下一 tick 统一发出 Bukkit 事件；不在调度路径里调用外部代码。
   */
  private void startApiEvents() {
    stopApiEvents();
    if (trainSnapshotStore == null) {
      return;
    }
    TrainSnapshotStore snapshots = trainSnapshotStore;
    EtaService eta = etaService;
    apiEventBridge =
        new org.fetarute.fetaruteTCAddon.api.internal.ApiEventBridge(
            getServer().getPluginManager()::callEvent,
            org.fetarute.fetaruteTCAddon.api.internal.ApiEventBridge::anyRegistered,
            () -> java.util.List.copyOf(snapshots.snapshot().keySet()),
            eta != null ? eta::currentHold : null,
            name ->
                snapshots
                    .getSnapshot(name)
                    .flatMap(
                        org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainRuntimeSnapshot
                            ::signalAspect),
            name ->
                timetableService != null ? timetableService.assignmentOf(name) : Optional.empty(),
            java.time.Instant::now,
            loggerManager::debug);
    if (stationStopHub != null) {
      stationStopHub.register("api-events", apiEventBridge);
    }
    if (healthMonitor != null) {
      apiEventAlertBus = healthMonitor.alertBus();
      apiEventAlertBus.subscribe(apiEventBridge);
    }
    apiEventTask = getServer().getScheduler().runTaskTimer(this, apiEventBridge::tick, 1L, 1L);
  }

  private void stopApiEvents() {
    if (apiEventTask != null) {
      apiEventTask.cancel();
      apiEventTask = null;
    }
    if (stationStopHub != null) {
      stationStopHub.unregister("api-events");
    }
    if (apiEventAlertBus != null && apiEventBridge != null) {
      apiEventAlertBus.unsubscribe(apiEventBridge);
    }
    apiEventAlertBus = null;
    apiEventBridge = null;
  }
}
