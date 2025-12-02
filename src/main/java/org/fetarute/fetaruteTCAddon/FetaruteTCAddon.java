package org.fetarute.fetaruteTCAddon;

import com.bergerkiller.bukkit.common.cloud.CloudSimpleHandler;
import com.bergerkiller.bukkit.tc.signactions.SignAction;
import org.incendo.cloud.CommandManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.fetarute.fetaruteTCAddon.command.FtaInfoCommand;
import org.fetarute.fetaruteTCAddon.command.FtaStorageCommand;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.action.AutoStationSignAction;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.action.DepotSignAction;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.action.WaypointSignAction;
import org.fetarute.fetaruteTCAddon.storage.StorageManager;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.Consumer;

public final class FetaruteTCAddon extends JavaPlugin {

    private SignNodeRegistry signNodeRegistry;
    private List<SignAction> registeredSignActions;
    private CloudSimpleHandler cloudHandler;
    private FtaInfoCommand infoCommand;
    private FtaStorageCommand storageCommand;
    private ConfigManager configManager;
    private StorageManager storageManager;
    private boolean debugEnabled;
    private final Consumer<String> debugLogger = this::debug;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        configManager = new ConfigManager(this);
        storageManager = new StorageManager(this);
        reloadFromConfig();

        // 注册自定义牌子以便 TC 与调度层识别节点
        signNodeRegistry = new SignNodeRegistry(debugLogger);
        registeredSignActions = List.of(
                new DepotSignAction(signNodeRegistry, debugLogger),
                new AutoStationSignAction(signNodeRegistry, debugLogger),
                new WaypointSignAction(signNodeRegistry, debugLogger)
        );
        registeredSignActions.forEach(SignAction::register);

        infoCommand = new FtaInfoCommand(this);
        storageCommand = new FtaStorageCommand(this);
        registerCommands();
    }

    @Override
    public void onDisable() {
        if (registeredSignActions != null) {
            registeredSignActions.forEach(SignAction::unregister);
        }
        if (signNodeRegistry != null) {
            signNodeRegistry.clear();
        }
        cloudHandler = null;
        if (storageManager != null) {
            storageManager.shutdown();
        }
    }

    private void registerCommands() {
        try {
            cloudHandler = new CloudSimpleHandler();
            cloudHandler.enable(this);
            CommandManager<CommandSender> manager = cloudHandler.getManager();
            infoCommand.register(manager);
            storageCommand.register(manager);
        } catch (NoClassDefFoundError e) {
            getLogger().warning("缺少 cloud-commandframework 依赖（BKCommonLib 内置 Cloud），/fta 命令未注册: " + e.getMessage());
        } catch (Exception e) {
            getLogger().warning("初始化命令系统失败，/fta 命令未注册: " + e.getMessage());
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!"fta".equalsIgnoreCase(label)) {
            return false;
        }
        // Cloud 已处理玩家命令；这里兜底控制台或 Cloud 未初始化的场景
        if (cloudHandler == null || !(sender instanceof Player)) {
            if (args.length == 0 || "help".equalsIgnoreCase(args[0])) {
                infoCommand.sendHelp(sender);
            } else if ("info".equalsIgnoreCase(args[0])) {
                infoCommand.sendInfo(sender);
            } else if ("reload".equalsIgnoreCase(args[0])) {
                if (!sender.hasPermission("fetarute.reload")) {
                    sender.sendMessage("§c无权限执行重载（需要 fetarute.reload）");
                    return true;
                }
                reloadFromCommand(sender);
            } else {
                sender.sendMessage("§c未知子命令，使用 /fta help 查看帮助");
            }
            return true;
        }
        return false;
    }

    /**
     * 供命令调用，重新加载配置并应用调试/存储设置。
     */
    public void reloadFromCommand(CommandSender sender) {
        reloadFromConfig();
        sender.sendMessage("§a[FTA] 配置已重载，debug=" + (debugEnabled ? "开启" : "关闭")
                + "，存储后端=" + storageManager.backend().name().toLowerCase());
    }

    private void reloadFromConfig() {
        configManager.reload();
        ConfigManager.ConfigView configView = configManager.current();
        debugEnabled = configView.debugEnabled();
        if (debugEnabled) {
            getLogger().info("已启用调试日志输出");
        }
        if (configView.configVersion() != 1) {
            getLogger().warning("config.yml 版本 (" + configView.configVersion() + ") 与插件预期 (1) 不一致，请参考默认模板更新配置。");
        }
        storageManager.apply(configView);
    }

    /**
     * 统一调试输出，避免散落的 logger 判空。
     */
    public void debug(String message) {
        if (debugEnabled) {
            getLogger().info("[DEBUG] " + message);
        }
    }
}
