package org.fetarute.fetaruteTCAddon;

import com.bergerkiller.bukkit.common.cloud.CloudSimpleHandler;
import com.bergerkiller.bukkit.tc.signactions.SignAction;
import java.util.Map;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.fetarute.fetaruteTCAddon.command.FtaCompanyCommand;
import org.fetarute.fetaruteTCAddon.command.FtaGraphCommand;
import org.fetarute.fetaruteTCAddon.command.FtaInfoCommand;
import org.fetarute.fetaruteTCAddon.command.FtaLineCommand;
import org.fetarute.fetaruteTCAddon.command.FtaOperatorCommand;
import org.fetarute.fetaruteTCAddon.command.FtaRootCommand;
import org.fetarute.fetaruteTCAddon.command.FtaRouteCommand;
import org.fetarute.fetaruteTCAddon.command.FtaStorageCommand;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.RouteEditorAppendListener;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignRemoveListener;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.TrainSignBypassListener;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.action.AutoStationSignAction;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.action.DepotSignAction;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.action.WaypointSignAction;
import org.fetarute.fetaruteTCAddon.storage.StorageManager;
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
    this.railGraphService = new RailGraphService(signNodeRegistry, loggerManager::debug);
    preloadRailGraphFromStorage();

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
    sender.sendMessage(localeManager.component("command.reload.success"));
  }

  public LocaleManager getLocaleManager() {
    return localeManager;
  }

  public LoggerManager getLoggerManager() {
    return loggerManager;
  }

  public StorageManager getStorageManager() {
    return storageManager;
  }

  public RailGraphService getRailGraphService() {
    return railGraphService;
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
    this.waypointSignAction =
        new WaypointSignAction(signNodeRegistry, loggerManager::debug, localeManager);
    this.autoStationSignAction =
        new AutoStationSignAction(signNodeRegistry, loggerManager::debug, localeManager);
    this.depotSignAction =
        new DepotSignAction(signNodeRegistry, loggerManager::debug, localeManager);
    SignAction.register(waypointSignAction);
    SignAction.register(autoStationSignAction);
    SignAction.register(depotSignAction);
    getServer()
        .getPluginManager()
        .registerEvents(
            new SignRemoveListener(signNodeRegistry, localeManager, loggerManager::debug), this);
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
  }
}
