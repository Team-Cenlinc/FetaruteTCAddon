package org.fetarute.fetaruteTCAddon;

import com.bergerkiller.bukkit.common.cloud.CloudSimpleHandler;
import com.bergerkiller.bukkit.tc.signactions.SignAction;
import org.incendo.cloud.CommandManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.fetarute.fetaruteTCAddon.command.FtaInfoCommand;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.action.AutoStationSignAction;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.action.DepotSignAction;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.action.WaypointSignAction;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class FetaruteTCAddon extends JavaPlugin {

    private SignNodeRegistry signNodeRegistry;
    private List<SignAction> registeredSignActions;
    private CloudSimpleHandler cloudHandler;
    private FtaInfoCommand infoCommand;

    @Override
    public void onEnable() {
        // 注册自定义牌子以便 TC 与调度层识别节点
        signNodeRegistry = new SignNodeRegistry();
        registeredSignActions = List.of(
                new DepotSignAction(signNodeRegistry),
                new AutoStationSignAction(signNodeRegistry),
                new WaypointSignAction(signNodeRegistry)
        );
        registeredSignActions.forEach(SignAction::register);

        infoCommand = new FtaInfoCommand(this);
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
    }

    private void registerCommands() {
        try {
            cloudHandler = new CloudSimpleHandler();
            cloudHandler.enable(this);
            CommandManager<CommandSender> manager = cloudHandler.getManager();
            infoCommand.register(manager);
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
            } else {
                sender.sendMessage("§c未知子命令，使用 /fta help 查看帮助");
            }
            return true;
        }
        return false;
    }
}
