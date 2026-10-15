package org.fetarute.fetaruteTCAddon.command;

import java.util.Map;
import java.util.Optional;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.coupler.CouplerService;
import org.fetarute.fetaruteTCAddon.coupler.CouplerWrenchListener;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.incendo.cloud.CommandManager;

/**
 * 车钩扳手命令：{@code /fta coupler} 查看用法，{@code /fta coupler wrench} 领取扳手（权限 {@code fetarute.coupler}，默认
 * OP）。
 */
public final class FtaCouplerCommand {

  private final FetaruteTCAddon plugin;

  public FtaCouplerCommand(FetaruteTCAddon plugin) {
    this.plugin = plugin;
  }

  public void register(CommandManager<CommandSender> manager) {
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("coupler")
            .permission(CouplerWrenchListener.PERMISSION)
            .handler(ctx -> sendUsage(ctx.sender())));
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("coupler")
            .literal("wrench")
            .permission(CouplerWrenchListener.PERMISSION)
            .handler(ctx -> giveWrench(ctx.sender())));
  }

  private void sendUsage(CommandSender sender) {
    LocaleManager locale = plugin.getLocaleManager();
    sender.sendMessage(locale.component("coupler.usage.header"));
    for (String line : new String[] {"wrench", "toggle", "split", "push", "info"}) {
      sender.sendMessage(locale.component("coupler.usage." + line));
    }
  }

  private void giveWrench(CommandSender sender) {
    LocaleManager locale = plugin.getLocaleManager();
    if (!(sender instanceof Player player)) {
      sender.sendMessage(locale.component("coupler.player-only"));
      return;
    }
    Optional<CouplerService> service = plugin.getCouplerService();
    if (service.isEmpty()) {
      sender.sendMessage(locale.component("coupler.unavailable"));
      return;
    }
    Map<Integer, ItemStack> leftovers =
        player.getInventory().addItem(service.get().wrench().create());
    leftovers
        .values()
        .forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
    sender.sendMessage(
        locale.component(leftovers.isEmpty() ? "coupler.given" : "coupler.given-dropped"));
    if (!service.get().settings().enabled()) {
      sender.sendMessage(locale.component("coupler.disabled"));
    }
  }
}
