package org.fetarute.fetaruteTCAddon.command;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.call.CallDialog;
import org.fetarute.fetaruteTCAddon.call.CallService;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskBoardSource;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskBoardStations;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.SuggestionProvider;

/**
 * 乘客命令：{@code /fta call [车站]} 打开叫车对话框（与右键站台屏相同，列全站）。
 *
 * <p>不写车站时取玩家附近最近的车站。所有玩家默认可用（{@code fetarute.call}）。
 */
public final class FtaCallCommand {

  private final FetaruteTCAddon plugin;

  public FtaCallCommand(FetaruteTCAddon plugin) {
    this.plugin = plugin;
  }

  public void register(CommandManager<CommandSender> manager) {
    // 车站可写“运营商:站码”区分重名站，冒号不加引号不合法：参数用 quotedString，需要时候选带引号。
    SuggestionProvider<CommandSender> stationSuggestions =
        SuggestionProvider.blockingStrings(
            (ctx, input) ->
                CommandUx.suggestions(
                    TaskBoardStations.suggestions(TaskBoardSource.stations(plugin)),
                    input.lastRemainingToken()));
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("call")
            .permission(CallService.PERMISSION)
            .senderType(Player.class)
            .optional("station", StringParser.quotedStringParser(), stationSuggestions)
            .handler(
                ctx ->
                    open((Player) ctx.sender(), ctx.optional("station").map(String.class::cast))));
  }

  private void open(Player player, Optional<String> stationArg) {
    LocaleManager locale = plugin.getLocaleManager();
    Optional<TaskBoardSource.Station> station;
    if (stationArg.filter(arg -> !arg.isBlank()).isPresent()) {
      String code = stationArg.get().trim();
      TaskBoardStations.Lookup lookup =
          TaskBoardStations.find(TaskBoardSource.stations(plugin), code);
      if (lookup.outcome() == TaskBoardStations.Outcome.AMBIGUOUS) {
        player.sendMessage(
            locale.component(
                "call.station-ambiguous",
                Map.of("station", code, "candidates", String.join(", ", lookup.candidates()))));
        return;
      }
      if (lookup.outcome() == TaskBoardStations.Outcome.NOT_FOUND) {
        player.sendMessage(locale.component("call.station-not-found", Map.of("station", code)));
        return;
      }
      station = lookup.station();
    } else {
      station = TaskBoardSource.nearestStation(plugin, player.getLocation());
      if (station.isEmpty()) {
        player.sendMessage(locale.component("call.no-station"));
        return;
      }
    }
    TaskBoardSource.Station found = station.orElseThrow();
    CallDialog.open(
        plugin, player, new PidsStationKey(found.operatorCode(), found.stationCode()), Set.of());
  }
}
