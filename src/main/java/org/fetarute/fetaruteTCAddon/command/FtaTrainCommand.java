package org.fetarute.fetaruteTCAddon.command;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.properties.TrainPropertiesStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.bukkit.command.CommandSender;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.train.TrainConfig;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.train.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.train.TrainType;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.parser.flag.CommandFlag;
import org.incendo.cloud.parser.standard.DoubleParser;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.SuggestionProvider;

/** /fta train 命令注册（列车速度/加减速配置）。 */
public final class FtaTrainCommand {

  private static final int SUGGESTION_LIMIT = 20;

  private final FetaruteTCAddon plugin;
  private final TrainConfigResolver resolver = new TrainConfigResolver();

  public FtaTrainCommand(FetaruteTCAddon plugin) {
    this.plugin = plugin;
  }

  /** 注册 {@code /fta train config} 子命令。 */
  public void register(CommandManager<CommandSender> manager) {
    SuggestionProvider<CommandSender> trainSuggestions = trainSuggestions();

    var typeFlag = CommandFlag.builder("type").withComponent(StringParser.stringParser()).build();
    var cruiseFlag =
        CommandFlag.builder("cruise").withComponent(DoubleParser.doubleParser()).build();
    var cautionFlag =
        CommandFlag.builder("caution").withComponent(DoubleParser.doubleParser()).build();
    var accelFlag = CommandFlag.builder("accel").withComponent(DoubleParser.doubleParser()).build();
    var decelFlag = CommandFlag.builder("decel").withComponent(DoubleParser.doubleParser()).build();

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("train")
            .literal("config")
            .literal("set")
            .permission("fetarute.train.config")
            .required("train", StringParser.stringParser(), trainSuggestions)
            .flag(typeFlag)
            .flag(cruiseFlag)
            .flag(cautionFlag)
            .flag(accelFlag)
            .flag(decelFlag)
            .handler(
                ctx -> {
                  LocaleManager locale = plugin.getLocaleManager();
                  String trainName = ctx.get("train");
                  TrainProperties properties = TrainPropertiesStore.get(trainName);
                  if (properties == null) {
                    ctx.sender()
                        .sendMessage(
                            locale.component(
                                "command.train.config.not-found", Map.of("train", trainName)));
                    return;
                  }
                  String typeRaw = ctx.flags().getValue(typeFlag, null);
                  TrainType type =
                      Optional.ofNullable(typeRaw)
                          .flatMap(TrainType::parse)
                          .orElse(
                              plugin
                                  .getConfigManager()
                                  .current()
                                  .trainConfigSettings()
                                  .defaultTrainType());
                  TrainConfig defaults =
                      resolver.resolve(properties, plugin.getConfigManager().current());
                  Double cruise = ctx.flags().getValue(cruiseFlag, null);
                  Double caution = ctx.flags().getValue(cautionFlag, null);
                  Double accel = ctx.flags().getValue(accelFlag, null);
                  Double decel = ctx.flags().getValue(decelFlag, null);
                  TrainConfig target =
                      new TrainConfig(
                          type,
                          cruise != null ? cruise : defaults.cruiseSpeedBps(),
                          caution != null ? caution : defaults.cautionSpeedBps(),
                          accel != null ? accel : defaults.accelBps2(),
                          decel != null ? decel : defaults.decelBps2());
                  resolver.writeConfig(
                      properties,
                      target,
                      Optional.ofNullable(cruise),
                      Optional.ofNullable(caution),
                      Optional.ofNullable(accel),
                      Optional.ofNullable(decel));
                  ctx.sender()
                      .sendMessage(
                          locale.component(
                              "command.train.config.set",
                              Map.of(
                                  "train",
                                  trainName,
                                  "type",
                                  target.type().name(),
                                  "cruise",
                                  String.valueOf(target.cruiseSpeedBps()),
                                  "caution",
                                  String.valueOf(target.cautionSpeedBps()),
                                  "accel",
                                  String.valueOf(target.accelBps2()),
                                  "decel",
                                  String.valueOf(target.decelBps2()))));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("train")
            .literal("config")
            .literal("list")
            .permission("fetarute.train.config")
            .required("train", StringParser.stringParser(), trainSuggestions)
            .handler(
                ctx -> {
                  LocaleManager locale = plugin.getLocaleManager();
                  String trainName = ctx.get("train");
                  TrainProperties properties = TrainPropertiesStore.get(trainName);
                  if (properties == null) {
                    ctx.sender()
                        .sendMessage(
                            locale.component(
                                "command.train.config.not-found", Map.of("train", trainName)));
                    return;
                  }
                  TrainConfig config =
                      resolver.resolve(properties, plugin.getConfigManager().current());
                  ctx.sender()
                      .sendMessage(
                          locale.component(
                              "command.train.config.list",
                              Map.of(
                                  "train",
                                  trainName,
                                  "type",
                                  config.type().name(),
                                  "cruise",
                                  String.valueOf(config.cruiseSpeedBps()),
                                  "caution",
                                  String.valueOf(config.cautionSpeedBps()),
                                  "accel",
                                  String.valueOf(config.accelBps2()),
                                  "decel",
                                  String.valueOf(config.decelBps2()))));
                }));
  }

  private SuggestionProvider<CommandSender> trainSuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = normalizePrefix(input);
          List<String> suggestions = new ArrayList<>();
          if (prefix.isBlank()) {
            suggestions.add("<train>");
          }
          for (TrainProperties props : TrainPropertiesStore.getAll()) {
            if (props == null) {
              continue;
            }
            String name = props.getTrainName();
            if (name == null || name.isBlank()) {
              continue;
            }
            String normalized = name.trim();
            if (!normalized.toLowerCase(Locale.ROOT).startsWith(prefix)) {
              continue;
            }
            suggestions.add(normalized);
            if (suggestions.size() >= SUGGESTION_LIMIT) {
              break;
            }
          }
          return suggestions;
        });
  }

  private static String normalizePrefix(CommandInput input) {
    if (input == null) {
      return "";
    }
    String raw = input.lastRemainingToken();
    return raw.trim().toLowerCase(Locale.ROOT);
  }
}
