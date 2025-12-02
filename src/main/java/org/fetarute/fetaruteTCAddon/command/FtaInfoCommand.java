package org.fetarute.fetaruteTCAddon.command;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import org.bukkit.command.CommandSender;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.utils.LoggerManager;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.incendo.cloud.CommandManager;

/**
 * 注册 /fta info 与 /fta help 子命令，输出版本与帮助信息。
 */
public final class FtaInfoCommand {

    private final FetaruteTCAddon plugin;

    public FtaInfoCommand(FetaruteTCAddon plugin) {
        this.plugin = plugin;
    }

    public void register(CommandManager<CommandSender> manager) {
        manager.command(manager.commandBuilder("fta")
                .handler(ctx -> sendHelp(ctx.sender()))
        );
        manager.command(manager.commandBuilder("fta")
                .literal("info")
                .handler(ctx -> sendInfo(ctx.sender()))
        );
        manager.command(manager.commandBuilder("fta")
                .literal("help")
                .handler(ctx -> sendHelp(ctx.sender()))
        );
    }

    public void sendInfo(CommandSender sender) {
        LocaleManager locale = plugin.getLocaleManager();
        LoggerManager logger = plugin.getLoggerManager();
        String version = Optional.of(plugin.getPluginMeta().getVersion()).orElse("dev");
        BuildInfo buildInfo = readBuildInfo(logger);
        String buildId = buildInfo.buildId().orElse(version);
        String git = buildInfo.gitCommit().orElse("unknown");
        sender.sendMessage(locale.component("command.info.header"));
        sender.sendMessage(locale.component("command.info.version", Map.of("version", version)));
        sender.sendMessage(locale.component("command.info.build", Map.of("build", buildId)));
        sender.sendMessage(locale.component("command.info.git", Map.of("git", git)));
    }

    public void sendHelp(CommandSender sender) {
        LocaleManager locale = plugin.getLocaleManager();
        sender.sendMessage(locale.component("command.help.header"));
        sender.sendMessage(locale.component("command.help.entry-info"));
        sender.sendMessage(locale.component("command.help.entry-help"));
        sender.sendMessage(locale.component("command.help.entry-reload"));
    }

    private BuildInfo readBuildInfo(LoggerManager logger) {
        try (InputStream in = plugin.getResource("build-info.properties")) {
            if (in == null) {
                return BuildInfo.empty();
            }
            Properties props = new Properties();
            props.load(in);
            String commit = props.getProperty("gitCommit");
            String buildId = props.getProperty("buildId");
            return new BuildInfo(
                    Optional.ofNullable(commit).filter(s -> !s.isBlank()).map(String::trim),
                    Optional.ofNullable(buildId).filter(s -> !s.isBlank()).map(String::trim)
            );
        } catch (IOException ex) {
            logger.debug("读取 build-info 失败: " + ex.getMessage());
            return BuildInfo.empty();
        }
    }

    private record BuildInfo(Optional<String> gitCommit, Optional<String> buildId) {
        static BuildInfo empty() {
            return new BuildInfo(Optional.empty(), Optional.empty());
        }
    }
}
