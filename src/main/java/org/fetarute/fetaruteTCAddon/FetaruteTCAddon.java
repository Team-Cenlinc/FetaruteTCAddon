package org.fetarute.fetaruteTCAddon;

import com.bergerkiller.bukkit.common.cloud.CloudSimpleHandler;
import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartGroupStore;
import com.bergerkiller.bukkit.tc.signactions.SignAction;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.fetarute.fetaruteTCAddon.command.FtaCompanyCommand;
import org.fetarute.fetaruteTCAddon.command.FtaDepotCommand;
import org.fetarute.fetaruteTCAddon.command.FtaEtaCommand;
import org.fetarute.fetaruteTCAddon.command.FtaGraphCommand;
import org.fetarute.fetaruteTCAddon.command.FtaInfoCommand;
import org.fetarute.fetaruteTCAddon.command.FtaLineCommand;
import org.fetarute.fetaruteTCAddon.command.FtaOccupancyCommand;
import org.fetarute.fetaruteTCAddon.command.FtaOperatorCommand;
import org.fetarute.fetaruteTCAddon.command.FtaRootCommand;
import org.fetarute.fetaruteTCAddon.command.FtaRouteCommand;
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
import org.fetarute.fetaruteTCAddon.dispatcher.graph.debug.GraphDebugStickListener;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.sync.RailNodeIncrementalSync;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.ReclaimManager;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchListener;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeSignalMonitor;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainCartsRuntimeHandle;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.HeadwayRule;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SimpleTicketAssigner;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnMonitor;
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
import org.fetarute.fetaruteTCAddon.display.DisplayService;
import org.fetarute.fetaruteTCAddon.display.SimpleDisplayService;
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
  private RouteDefinitionCache routeDefinitionCache;
  private RouteProgressRegistry routeProgressRegistry;
  private LayoverRegistry layoverRegistry;
  private RuntimeDispatchService runtimeDispatchService;
  private ReclaimManager reclaimManager;
  private org.bukkit.scheduler.BukkitTask runtimeMonitorTask;
  private SpawnManager spawnManager;
  private TicketAssigner spawnTicketAssigner;
  private org.bukkit.scheduler.BukkitTask spawnMonitorTask;
  private TrainSnapshotStore trainSnapshotStore;
  private EtaRuntimeSampler etaRuntimeSampler;
  private EtaService etaService;
  private DisplayService displayService;
  private HudTemplateService hudTemplateService;

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
    initDisplayService();

    registerCommands();
    getServer()
        .getConsoleSender()
        .sendMessage(
            localeManager.component(
                "locale.loaded", Map.of("locale", localeManager.getCurrentLocale())));
  }

  @Override
  public void onDisable() {
    unregisterSignActions();
    if (runtimeMonitorTask != null) {
      runtimeMonitorTask.cancel();
      runtimeMonitorTask = null;
    }
    if (spawnMonitorTask != null) {
      spawnMonitorTask.cancel();
      spawnMonitorTask = null;
    }
    if (displayService != null) {
      displayService.stop();
      displayService = null;
    }
    if (reclaimManager != null) {
      reclaimManager.stop();
      reclaimManager = null;
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
    initRouteDefinitionCache();
    restartRuntimeMonitor();
    initSpawnScheduler();
    initReclaimManager();
    initDisplayService();
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

  /** 返回运行时调度服务（若未初始化则为空）。 */
  public Optional<RuntimeDispatchService> getRuntimeDispatchService() {
    return Optional.ofNullable(runtimeDispatchService);
  }

  public RailGraphService getRailGraphService() {
    return railGraphService;
  }

  /** 返回当前占用管理器（调度闭塞骨架）。 */
  public OccupancyManager getOccupancyManager() {
    return occupancyManager;
  }

  /** 返回 ETA 服务（若未初始化则为空）。 */
  public EtaService getEtaService() {
    return etaService;
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
    new FtaTrainCommand(this).register(commandManager);
    new FtaGraphCommand(this).register(commandManager);
    new FtaTemplateCommand(this).register(commandManager);
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
    this.railGraphService = new RailGraphService(signNodeRegistry, loggerManager::debug);
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
    getServer()
        .getPluginManager()
        .registerEvents(new TrainSignBypassListener(loggerManager::debug), this);
  }

  private void initOccupancyManager() {
    this.headwayRule = HeadwayRule.fixed(Duration.ZERO);
    this.occupancyManager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());
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
    this.runtimeDispatchService =
        new RuntimeDispatchService(
            occupancyManager,
            railGraphService,
            routeDefinitionCache,
            routeProgressRegistry,
            signNodeRegistry,
            layoverRegistry,
            configManager,
            storageManager,
            new TrainConfigResolver(),
            loggerManager::debug);
    getServer()
        .getPluginManager()
        .registerEvents(new RuntimeDispatchListener(runtimeDispatchService), this);
    initEtaService();
    restartRuntimeMonitor();
    getServer()
        .getScheduler()
        .runTaskLater(
            this,
            () -> {
              try {
                java.util.List<org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeTrainHandle>
                    handles = new java.util.ArrayList<>();
                for (MinecartGroup group : MinecartGroupStore.getGroups()) {
                  if (group == null || !group.isValid()) {
                    continue;
                  }
                  handles.add(new TrainCartsRuntimeHandle(group));
                }
                runtimeDispatchService.rebuildOccupancySnapshot(handles);
              } catch (Exception ex) {
                debug("运行时占用重建失败: " + ex.getMessage());
              }
            },
            1L);
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
                    runtimeDispatchService, etaRuntimeSampler, trainSnapshotStore),
                interval,
                interval);
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
            Math.max(1, spawnSettings.maxSpawnPerTick()));
    this.spawnManager = new StorageSpawnManager(managerSettings, loggerManager::debug);
    TrainCartsDepotSpawner depotSpawner =
        new TrainCartsDepotSpawner(this, signNodeRegistry, loggerManager::debug);
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
            java.time.Duration.ofMillis(spawnSettings.planRefreshTicks() * 50L),
            spawnSettings.maxSpawnPerTick());
    runtimeDispatchService.setLayoverListener(spawnTicketAssigner::onLayoverRegistered);
    if (etaService != null) {
      etaService.attachTicketSources(spawnManager, spawnTicketAssigner);
    }
    restartSpawnMonitor();
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
    this.reclaimManager.start();
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
  }
}
