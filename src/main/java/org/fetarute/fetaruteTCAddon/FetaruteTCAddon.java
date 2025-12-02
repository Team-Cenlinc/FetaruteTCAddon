package org.fetarute.fetaruteTCAddon;

import java.util.Map;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.fetarute.fetaruteTCAddon.command.FtaInfoCommand;
import org.fetarute.fetaruteTCAddon.command.FtaRootCommand;
import org.fetarute.fetaruteTCAddon.command.FtaStorageCommand;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignRemoveListener;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.action.AutoStationSignAction;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.action.DepotSignAction;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.action.WaypointSignAction;
import org.fetarute.fetaruteTCAddon.storage.StorageManager;
import org.fetarute.fetaruteTCAddon.utils.ConfigUpdater;
import org.fetarute.fetaruteTCAddon.utils.LoggerManager;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.incendo.cloud.CommandManager;
import com.bergerkiller.bukkit.common.cloud.CloudSimpleHandler;
import com.bergerkiller.bukkit.tc.signactions.SignAction;

/**
 * 插件入口，负责初始化配置、语言与命令。
 */
public final class FetaruteTCAddon extends JavaPlugin {

    private final CloudSimpleHandler cloudHandler = new CloudSimpleHandler();
    private ConfigManager configManager;
    private LocaleManager localeManager;
    private StorageManager storageManager;
    private CommandManager<CommandSender> commandManager;
    private LoggerManager loggerManager;
    private SignNodeRegistry signNodeRegistry;
    private WaypointSignAction waypointSignAction;
    private AutoStationSignAction autoStationSignAction;
    private DepotSignAction depotSignAction;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ConfigUpdater.forPlugin(getDataFolder(), () -> getResource("config.yml"), new LoggerManager(getLogger())).update();
        this.configManager = new ConfigManager(this);
        this.configManager.reload();

        this.loggerManager = new LoggerManager(getLogger());
        this.loggerManager.setDebugEnabled(configManager.current().debugEnabled());

        this.localeManager = new LocaleManager(this, configManager.current().locale(), loggerManager);
        this.localeManager.reload();

        this.storageManager = new StorageManager(this, loggerManager);
        this.storageManager.apply(configManager.current());
        registerSignActions();

        registerCommands();
        getServer()
                .getConsoleSender()
                .sendMessage(localeManager.component("locale.loaded", Map.of("locale", localeManager.getCurrentLocale())));
    }

    @Override
    public void onDisable() {
        unregisterSignActions();
        if (storageManager != null) {
            storageManager.shutdown();
        }
    }

    /**
     * 提供调试日志输出，受 config.yml 开关控制。
     */
    public void debug(String message) {
        if (configManager != null && configManager.current() != null && configManager.current().debugEnabled()) {
            loggerManager.debug(message);
        }
    }

    /**
     * 供命令调用的重载入口。
     *
     * @param sender 触发命令的玩家或控制台
     */
    public void reloadFromCommand(CommandSender sender) {
        ConfigUpdater.forPlugin(getDataFolder(), () -> getResource("config.yml"), loggerManager).update();
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

    private void registerCommands() {
        this.cloudHandler.enable(this);
        this.commandManager = cloudHandler.getManager();
        FtaInfoCommand infoCommand = new FtaInfoCommand(this);
        new FtaStorageCommand(this).register(commandManager);
        infoCommand.register(commandManager);

        var bukkitCommand = getCommand("fta");
        if (bukkitCommand != null) {
            FtaRootCommand rootCommand = new FtaRootCommand(this, infoCommand);
            bukkitCommand.setExecutor(rootCommand);
            bukkitCommand.setTabCompleter(rootCommand);
        } else {
            loggerManager.warn("未在 plugin.yml 中找到 fta 命令定义");
        }
    }

    private void registerSignActions() {
        this.signNodeRegistry = new SignNodeRegistry(loggerManager::debug);
        this.waypointSignAction = new WaypointSignAction(signNodeRegistry, loggerManager::debug, localeManager);
        this.autoStationSignAction = new AutoStationSignAction(signNodeRegistry, loggerManager::debug, localeManager);
        this.depotSignAction = new DepotSignAction(signNodeRegistry, loggerManager::debug, localeManager);
        SignAction.register(waypointSignAction);
        SignAction.register(autoStationSignAction);
        SignAction.register(depotSignAction);
        getServer().getPluginManager().registerEvents(new SignRemoveListener(signNodeRegistry, localeManager), this);
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
