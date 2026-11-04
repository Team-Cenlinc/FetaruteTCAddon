package org.fetarute.fetaruteTCAddon.command;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.drive.DrivePermissions;
import org.fetarute.fetaruteTCAddon.drive.crew.StationMasterShift;
import org.fetarute.fetaruteTCAddon.stationmaster.StationMasterService;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.permission.Permission;

/**
 * {@code /fta stationmaster}（简写 {@code sm}）：站长上岗、下岗与站台作业。
 *
 * <ul>
 *   <li>on：在附近的车站上岗（第一名站长管全站，后来的人按时刻表分走一段股道）
 *   <li>off：下岗，所辖股道交还或回到自动
 *   <li>signal：给身旁等发车信号的车发车信号
 *   <li>next：提前结束休息，排下一列
 *   <li>hold / release：扣住、放行身旁所辖股道上停站的车
 *   <li>close：催车上的乘务员关门
 *   <li>status：各站长的车站、所辖股道与作业数
 * </ul>
 */
public final class FtaStationMasterCommand {

  private final FetaruteTCAddon plugin;

  public FtaStationMasterCommand(FetaruteTCAddon plugin) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
  }

  /** 注册 {@code /fta stationmaster}（简写 {@code sm}）：上岗、下岗、值守概况、发车信号、扣车、放行与催关。 */
  public void register(CommandManager<CommandSender> manager) {
    Objects.requireNonNull(manager, "manager");
    Permission master = Permission.of(StationMasterService.PERMISSION);
    Permission admin = Permission.of(DrivePermissions.ADMIN);
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("stationmaster", "sm")
            .literal("on")
            .permission(master)
            .handler(ctx -> handleOn(ctx.sender())));
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("stationmaster", "sm")
            .literal("off")
            .permission(master)
            .handler(ctx -> handleOff(ctx.sender())));
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("stationmaster", "sm")
            .literal("tasks")
            .permission(master)
            .handler(ctx -> handleTasks(ctx.sender())));
    action(manager, master, "signal", StationMasterService::signal);
    action(manager, master, "next", StationMasterService::next);
    action(manager, master, "hold", StationMasterService::hold);
    action(manager, master, "release", StationMasterService::release);
    action(manager, master, "close", StationMasterService::urgeClose);
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("stationmaster", "sm")
            .literal("status")
            .permission(Permission.anyOf(List.of(master, admin)))
            .handler(ctx -> handleStatus(ctx.sender())));
  }

  /** 站长对身旁的车做一个作业。 */
  private void action(
      CommandManager<CommandSender> manager,
      Permission permission,
      String literal,
      BiConsumer<StationMasterService, UUID> work) {
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("stationmaster", "sm")
            .literal(literal)
            .permission(permission)
            .handler(
                ctx -> {
                  if (!(ctx.sender() instanceof Player player)) {
                    ctx.sender()
                        .sendMessage(locale().component("stationmaster.command.player-only"));
                    return;
                  }
                  service()
                      .ifPresentOrElse(
                          service -> work.accept(service, player.getUniqueId()),
                          () ->
                              player.sendMessage(
                                  locale().component("stationmaster.command.unavailable")));
                }));
  }

  private LocaleManager locale() {
    return plugin.getLocaleManager();
  }

  private Optional<StationMasterService> service() {
    return plugin.getStationMasterService();
  }

  private void handleTasks(CommandSender sender) {
    if (!(sender instanceof Player player)) {
      sender.sendMessage(locale().component("stationmaster.command.player-only"));
      return;
    }
    plugin
        .getStationMasterModule()
        .ifPresentOrElse(
            module -> module.openBoard(player),
            () -> sender.sendMessage(locale().component("stationmaster.command.unavailable")));
  }

  private void handleOn(CommandSender sender) {
    if (!(sender instanceof Player player)) {
      sender.sendMessage(locale().component("stationmaster.command.player-only"));
      return;
    }
    Optional<StationMasterService> service = service();
    if (service.isEmpty()) {
      sender.sendMessage(locale().component("stationmaster.command.unavailable"));
      return;
    }
    service
        .get()
        .startDuty(
            player.getUniqueId(),
            player.getName(),
            player.hasPermission(StationMasterService.PERMISSION));
  }

  private void handleOff(CommandSender sender) {
    if (!(sender instanceof Player player)) {
      sender.sendMessage(locale().component("stationmaster.command.player-only"));
      return;
    }
    Optional<StationMasterService> service = service();
    if (service.isEmpty()
        || !service.get().endDuty(player.getUniqueId(), StationMasterShift.EndReason.OFF_DUTY)) {
      sender.sendMessage(locale().component("stationmaster.command.not-on-duty"));
    }
  }

  private void handleStatus(CommandSender sender) {
    Optional<StationMasterService> service = service();
    if (service.isEmpty()) {
      sender.sendMessage(locale().component("stationmaster.command.unavailable"));
      return;
    }
    List<StationMasterService.DutyStatus> duties = service.get().status();
    if (duties.isEmpty()) {
      sender.sendMessage(locale().component("stationmaster.command.status.empty"));
      return;
    }
    sender.sendMessage(locale().component("stationmaster.command.status.header"));
    String separator = locale().text("stationmaster.track-separator");
    for (StationMasterService.DutyStatus duty : duties) {
      sender.sendMessage(
          locale()
              .component(
                  "stationmaster.command.status.line",
                  Map.of(
                      "player",
                      duty.player(),
                      "station",
                      duty.station(),
                      "tracks",
                      duty.tracks().stream()
                          .map(String::valueOf)
                          .collect(Collectors.joining(separator)),
                      "signals",
                      String.valueOf(duty.signals()),
                      "auto",
                      String.valueOf(duty.autoSignals()),
                      "holds",
                      String.valueOf(duty.holds()))));
    }
  }
}
