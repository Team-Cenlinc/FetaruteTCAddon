package org.fetarute.fetaruteTCAddon.command;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.command.CommandSender;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.fetarute.fetaruteTCAddon.utils.LoggerManager;
import org.incendo.cloud.CommandManager;

/**
 * /fta info 与 /fta help 的注册与输出。
 *
 * <p>帮助列表带点击与悬浮提示，便于玩家快速执行与查阅说明。
 */
public final class FtaInfoCommand {

  private final FetaruteTCAddon plugin;

  public FtaInfoCommand(FetaruteTCAddon plugin) {
    this.plugin = plugin;
  }

  public void register(CommandManager<CommandSender> manager) {
    manager.command(manager.commandBuilder("fta").handler(ctx -> sendHelp(ctx.sender())));
    manager.command(
        manager.commandBuilder("fta").literal("info").handler(ctx -> sendInfo(ctx.sender())));
    manager.command(
        manager.commandBuilder("fta").literal("help").handler(ctx -> sendHelp(ctx.sender())));
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
    sendHelpEntry(
        sender,
        locale.component("command.help.entry-info"),
        ClickEvent.runCommand("/fta info"),
        locale.component("command.help.hover-info"));
    sendHelpEntry(
        sender,
        locale.component("command.help.entry-help"),
        ClickEvent.runCommand("/fta help"),
        locale.component("command.help.hover-help"));
    sendHelpEntry(
        sender,
        locale.component("command.help.entry-company"),
        ClickEvent.suggestCommand("/fta company "),
        locale.component("command.help.hover-company"));
    sendHelpEntry(
        sender,
        locale.component("command.help.entry-operator"),
        ClickEvent.suggestCommand("/fta operator "),
        locale.component("command.help.hover-operator"));
    sendHelpEntry(
        sender,
        locale.component("command.help.entry-line"),
        ClickEvent.suggestCommand("/fta line "),
        locale.component("command.help.hover-line"));
    sendHelpEntry(
        sender,
        locale.component("command.help.entry-route"),
        ClickEvent.suggestCommand("/fta route "),
        locale.component("command.help.hover-route"));
    sendHelpEntry(
        sender,
        locale.component("command.help.entry-depot"),
        ClickEvent.suggestCommand("/fta depot "),
        locale.component("command.help.hover-depot"));
    sendHelpEntry(
        sender,
        locale.component("command.help.entry-graph"),
        ClickEvent.suggestCommand("/fta graph "),
        locale.component("command.help.hover-graph"));
    sendHelpEntry(
        sender,
        locale.component("command.help.entry-reload"),
        ClickEvent.runCommand("/fta reload"),
        locale.component("command.help.hover-reload"));
  }

  private void sendHelpEntry(
      CommandSender sender, Component text, ClickEvent clickEvent, Component hoverText) {
    sender.sendMessage(text.clickEvent(clickEvent).hoverEvent(HoverEvent.showText(hoverText)));
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
          Optional.ofNullable(buildId).filter(s -> !s.isBlank()).map(String::trim));
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
