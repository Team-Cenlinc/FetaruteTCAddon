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
import java.util.Optional;
import java.util.function.Consumer;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
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
import org.fetarute.fetaruteTCAddon.command.FtaStorageCommand;
import org.fetarute.fetaruteTCAddon.command.FtaTemplateCommand;
import org.fetarute.fetaruteTCAddon.command.FtaTrainCommand;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Route;
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
  private RouteProgressRegistry routeProgressRegistry;
  private LayoverRegistry layoverRegistry;
  private DwellRegistry dwellRegistry;
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
    new FtaDepotCommand(this).register(commandManager);
    new FtaEtaCommand(this).register(commandManager);
    new FtaOccupancyCommand(this).register(commandManager);
    new FtaSpawnCommand(this).register(commandManager);
    new FtaSpeedCommand(this).register(commandManager);
    new FtaTrainCommand(this).register(commandManager);
    new FtaGraphCommand(this).register(commandManager);
    new FtaTemplateCommand(this).register(commandManager);
    new FtaHealthCommand(this).register(commandManager);
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
    if (storageManager != null && storageManager.isReady()) {
      storageManager.provider().ifPresent(provider -> routeDefinitionCache.reload(provider));
    }
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
                      service.beginStartupOccupancyReconstruction();
                      debug("运行时恢复等待实体化发车或身份异常物理收容完成，保持 STOP_FIRST");
                    } else {
                      completed = service.prepareStartupOccupancySnapshot(handles);
                    }
                  } catch (RuntimeException | LinkageError ex) {
                    service.beginStartupOccupancyReconstruction();
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
      service.beginStartupOccupancyReconstruction();
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
      service.beginStartupOccupancyReconstruction();
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
    if (railGraphService == null
        || routeDefinitionCache == null
        || occupancyManager == null
        || headwayRule == null
        || routeProgressRegistry == null) {
      return;
    }
    if (trainSnapshotStore == null) {
      trainSnapshotStore = new TrainSnapshotStore();
    }
    etaRuntimeSampler = new EtaRuntimeSampler(routeProgressRegistry, trainSnapshotStore);
    etaService =
        new EtaService(
            trainSnapshotStore,
            railGraphService,
            routeDefinitionCache,
            occupancyManager,
            headwayRule,
            () ->
                configManager != null
                    ? configManager.current().runtimeSettings().lookaheadEdges()
                    : 2,
            () ->
                configManager != null
                    ? configManager.current().runtimeSettings().minClearEdges()
                    : 0,
            () ->
                configManager != null
                    ? configManager.current().runtimeSettings().rearGuardEdges()
                    : 0,
            () ->
                configManager != null
                    ? configManager.current().runtimeSettings().switcherZoneEdges()
                    : 2);
    if (layoverRegistry != null) {
      etaService.attachLayoverRegistry(layoverRegistry);
    }
    if (storageManager != null && storageManager.isReady()) {
      etaService.attachStorageProvider(storageManager.provider().orElse(null));
    }
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
    this.spawnManager = new StorageSpawnManager(managerSettings, loggerManager::debug);
    TrainCartsDepotSpawner depotSpawner =
        new TrainCartsDepotSpawner(this, signNodeRegistry, loggerManager::debug);
    depotSpawner.setOccupancyManager(occupancyManager);
    this.spawnTicketAssigner =
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
    runtimeDispatchService.setLayoverListener(spawnTicketAssigner::onLayoverRegistered);
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
            trainSnapshotStore, routeProgressRegistry, routeDefinitionCache, etaService);
    org.fetarute.fetaruteTCAddon.api.route.RouteApi routeApi =
        new org.fetarute.fetaruteTCAddon.api.internal.RouteApiImpl(
            routeDefinitionCache,
            storageManager != null ? storageManager.provider().orElse(null) : null);
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
              provider.stations(), provider.companies(), provider.operators());
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
    org.fetarute.fetaruteTCAddon.api.FetaruteApi.initialize(
        graphApi, trainApi, routeApi, occupancyApi, stationApi, operatorApi, lineApi, etaApi);
    getLogger()
        .info("公开 API v" + org.fetarute.fetaruteTCAddon.api.FetaruteApi.API_VERSION + " 已初始化");
  }
}
