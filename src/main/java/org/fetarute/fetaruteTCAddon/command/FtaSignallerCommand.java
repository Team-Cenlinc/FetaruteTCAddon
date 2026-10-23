package org.fetarute.fetaruteTCAddon.command;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.drive.DrivePermissions;
import org.fetarute.fetaruteTCAddon.drive.crew.SignallerShift;
import org.fetarute.fetaruteTCAddon.signaller.SignallerService;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.permission.Permission;

/**
 * {@code /fta signal on|off|status}：信号员上岗、下岗与查看各信号楼。
 *
 * <ul>
 *   <li>on：确认上岗，值守最近右键点过的那块控制台（点控制台时聊天栏会给出可点击的确认）
 *   <li>off：下岗，控制区回到自动
 *   <li>status：各信号楼的模式、值守者、在入口等许可的车、已给的许可与扣着的车
 * </ul>
 */
public final class FtaSignallerCommand {

  private final FetaruteTCAddon plugin;

  public FtaSignallerCommand(FetaruteTCAddon plugin) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
  }

  public void register(CommandManager<CommandSender> manager) {
    Objects.requireNonNull(manager, "manager");
    Permission signaller = Permission.of(SignallerService.PERMISSION);
    Permission admin = Permission.of(DrivePermissions.ADMIN);
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("signal")
            .literal("on")
            .permission(signaller)
            .handler(ctx -> handleOn(ctx.sender())));
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("signal")
            .literal("off")
            .permission(signaller)
            .handler(ctx -> handleOff(ctx.sender())));
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("signal")
            .literal("status")
            .permission(Permission.anyOf(List.of(signaller, admin)))
            .handler(ctx -> handleStatus(ctx.sender())));
  }

  private LocaleManager locale() {
    return plugin.getLocaleManager();
  }

  private Optional<SignallerService> service() {
    return plugin.getSignallerService();
  }

  private void handleOn(CommandSender sender) {
    if (!(sender instanceof Player player)) {
      sender.sendMessage(locale().component("signaller.command.player-only"));
      return;
    }
    Optional<SignallerService> service = service();
    if (service.isEmpty()) {
      sender.sendMessage(locale().component("signaller.command.unavailable"));
      return;
    }
    service
        .get()
        .startDuty(
            player.getUniqueId(),
            player.getName(),
            player.hasPermission(SignallerService.PERMISSION));
  }

  private void handleOff(CommandSender sender) {
    if (!(sender instanceof Player player)) {
      sender.sendMessage(locale().component("signaller.command.player-only"));
      return;
    }
    Optional<SignallerService> service = service();
    if (service.isEmpty()
        || !service.get().endDuty(player.getUniqueId(), SignallerShift.EndReason.OFF_DUTY)) {
      sender.sendMessage(locale().component("signaller.command.not-on-duty"));
    }
  }

  private void handleStatus(CommandSender sender) {
    Optional<SignallerService> service = service();
    if (service.isEmpty()) {
      sender.sendMessage(locale().component("signaller.command.unavailable"));
      return;
    }
    List<SignallerService.BoxStatus> boxes = service.get().status();
    if (boxes.isEmpty()) {
      sender.sendMessage(locale().component("signaller.command.status.empty"));
      return;
    }
    sender.sendMessage(locale().component("signaller.command.status.header"));
    for (SignallerService.BoxStatus box : boxes) {
      sender.sendMessage(
          locale()
              .component(
                  "signaller.command.status.line",
                  Map.of(
                      "operator",
                      box.operator(),
                      "zone",
                      box.zone(),
                      "name",
                      box.name(),
                      "mode",
                      locale().text("signaller.mode." + box.mode().name().toLowerCase(Locale.ROOT)),
                      "signaller",
                      box.signaller().orElse(locale().text("signaller.command.status.nobody")),
                      "waiting",
                      String.valueOf(box.waiting()),
                      "permits",
                      String.valueOf(box.permits()),
                      "holds",
                      String.valueOf(box.holds()))));
    }
  }
}
