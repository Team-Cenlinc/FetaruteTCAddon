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
import org.fetarute.fetaruteTCAddon.command.FtaGraphCommand;
import org.fetarute.fetaruteTCAddon.command.FtaInfoCommand;
import org.fetarute.fetaruteTCAddon.command.FtaLineCommand;
import org.fetarute.fetaruteTCAddon.command.FtaOccupancyCommand;
import org.fetarute.fetaruteTCAddon.command.FtaOperatorCommand;
import org.fetarute.fetaruteTCAddon.command.FtaRootCommand;
import org.fetarute.fetaruteTCAddon.command.FtaRouteCommand;
import org.fetarute.fetaruteTCAddon.command.FtaStorageCommand;
import org.fetarute.fetaruteTCAddon.command.FtaTrainCommand;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.sync.RailNodeIncrementalSync;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchListener;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeSignalMonitor;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.train.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.HeadwayRule;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.RouteEditorAppendListener;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeStorageSynchronizer;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignRemoveListener;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.TrainSignBypassListener;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.action.AutoStationSignAction;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.action.DepotSignAction;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.action.WaypointSignAction;
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
  private RouteDefinitionCache routeDefinitionCache;
  private RouteProgressRegistry routeProgressRegistry;
  private RuntimeDispatchService runtimeDispatchService;
  private org.bukkit.scheduler.BukkitTask runtimeMonitorTask;

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
    ConfigUpdater.forPlugin(getDataFolder(), () -> getResource("config.yml"), loggerManager)
        .update();
    this.configManager.reload();
    this.loggerManager.setDebugEnabled(configManager.current().debugEnabled());
    this.localeManager.reload(configManager.current().locale());
    this.storageManager.apply(configManager.current());
    initRouteDefinitionCache();
    restartRuntimeMonitor();
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
    new FtaDepotCommand(this).register(commandManager);
    new FtaOccupancyCommand(this).register(commandManager);
    new FtaTrainCommand(this).register(commandManager);
    new FtaGraphCommand(this).register(commandManager);
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
        .registerEvents(new TrainSignBypassListener(loggerManager::debug), this);
  }

  private void initOccupancyManager() {
    this.occupancyManager =
        new SimpleOccupancyManager(
            HeadwayRule.fixed(Duration.ZERO), SignalAspectPolicy.defaultPolicy());
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
    this.runtimeDispatchService =
        new RuntimeDispatchService(
            occupancyManager,
            railGraphService,
            routeDefinitionCache,
            routeProgressRegistry,
            signNodeRegistry,
            configManager,
            new TrainConfigResolver(),
            loggerManager::debug);
    getServer()
        .getPluginManager()
        .registerEvents(new RuntimeDispatchListener(runtimeDispatchService), this);
    restartRuntimeMonitor();
    getServer()
        .getScheduler()
        .runTaskLater(
            this,
            () -> {
              try {
                for (MinecartGroup group : MinecartGroupStore.getGroups()) {
                  if (group == null || !group.isValid()) {
                    continue;
                  }
                  runtimeDispatchService.handleSignalTick(group);
                }
              } catch (Exception ex) {
                debug("运行时占用重建失败: " + ex.getMessage());
              }
            },
            1L);
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
                this, new RuntimeSignalMonitor(runtimeDispatchService), interval, interval);
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
