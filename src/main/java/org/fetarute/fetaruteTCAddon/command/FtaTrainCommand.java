package org.fetarute.fetaruteTCAddon.command;

import com.bergerkiller.bukkit.tc.TrainCarts;
import com.bergerkiller.bukkit.tc.commands.selector.SelectorCondition;
import com.bergerkiller.bukkit.tc.commands.selector.SelectorException;
import com.bergerkiller.bukkit.tc.commands.selector.TCSelectorHandlerRegistry;
import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartGroupStore;
import com.bergerkiller.bukkit.tc.properties.CartProperties;
import com.bergerkiller.bukkit.tc.properties.CartPropertiesStore;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.properties.TrainPropertiesStore;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfig;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainType;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.component.CommandComponent;
import org.incendo.cloud.parser.flag.CommandFlag;
import org.incendo.cloud.parser.standard.DoubleParser;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.SuggestionProvider;

/** /fta train 命令注册（列车速度/加减速配置）。 */
public final class FtaTrainCommand {

  private static final int SUGGESTION_LIMIT = 20;
  private static final String TRAIN_SELECTOR_PREFIX = "@train[";
  private static final List<String> TRAIN_SELECTOR_KEYS =
      List.of(
          "x=",
          "y=",
          "z=",
          "dx=",
          "dy=",
          "dz=",
          "distance=",
          "world=",
          "sort=",
          "limit=",
          "name=",
          "tag=",
          "passengers=",
          "playerpassengers=",
          "destination=",
          "ticket=",
          "speed=",
          "velocity=",
          "speedlimit=",
          "friction=",
          "gravity=",
          "keepchunksloaded=",
          "unloaded=",
          "derailed=");
  private static final List<String> TRAIN_SELECTOR_SORT_VALUES =
      List.of("nearest", "furthest", "random");
  private static final List<String> TRAIN_SELECTOR_BOOLEAN_VALUES = List.of("true", "false");
  private static final List<String> TRAIN_SELECTOR_LIMIT_VALUES =
      List.of("1", "2", "5", "10", "20");
  private static final List<String> TRAIN_SELECTOR_NUMBER_VALUES =
      List.of("0", "1", "2", "5", "10", "20", "50", "100");

  private final FetaruteTCAddon plugin;
  private final TrainConfigResolver resolver = new TrainConfigResolver();

  public FtaTrainCommand(FetaruteTCAddon plugin) {
    this.plugin = plugin;
  }

  /** 注册 {@code /fta train config} 子命令。 */
  public void register(CommandManager<CommandSender> manager) {
    SuggestionProvider<CommandSender> trainSuggestions = trainSuggestions();

    var typeFlag =
        CommandFlag.builder("type")
            .withComponent(
                CommandComponent.builder("type", StringParser.stringParser())
                    .suggestionProvider(
                        CommandSuggestionProviders.enumValues(TrainType.class, "<type>"))
                    .build())
            .build();
    var accelFlag =
        CommandFlag.builder("accel")
            .withComponent(
                CommandComponent.builder("accel", DoubleParser.doubleParser())
                    .suggestionProvider(CommandSuggestionProviders.placeholder("<bps2>"))
                    .build())
            .build();
    var decelFlag =
        CommandFlag.builder("decel")
            .withComponent(
                CommandComponent.builder("decel", DoubleParser.doubleParser())
                    .suggestionProvider(CommandSuggestionProviders.placeholder("<bps2>"))
                    .build())
            .build();

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("train")
            .permission("fetarute.train.config")
            .handler(ctx -> sendHelp(ctx.sender())));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("train")
            .literal("config")
            .permission("fetarute.train.config")
            .handler(ctx -> sendHelp(ctx.sender())));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("train")
            .literal("config")
            .literal("set")
            .permission("fetarute.train.config")
            .optional("train", StringParser.stringParser(), trainSuggestions)
            .flag(typeFlag)
            .flag(accelFlag)
            .flag(decelFlag)
            .handler(
                ctx -> {
                  LocaleManager locale = plugin.getLocaleManager();
                  Optional<String> trainArg = ctx.optional("train").map(String.class::cast);
                  List<TrainProperties> targets =
                      resolveTrainTargets(ctx.sender(), trainArg, locale);
                  if (targets.isEmpty()) {
                    return;
                  }
                  String typeRaw = ctx.flags().getValue(typeFlag, null);
                  Optional<TrainType> typeOverride =
                      Optional.ofNullable(typeRaw).flatMap(TrainType::parse);
                  Double accel = ctx.flags().getValue(accelFlag, null);
                  Double decel = ctx.flags().getValue(decelFlag, null);
                  for (TrainProperties properties : targets) {
                    TrainConfig current =
                        resolver.resolve(properties, plugin.getConfigManager().current());
                    TrainType type = typeOverride.orElse(current.type());
                    TrainConfig target =
                        new TrainConfig(
                            type,
                            accel != null ? accel : current.accelBps2(),
                            decel != null ? decel : current.decelBps2());
                    resolver.writeConfig(
                        properties, target, Optional.ofNullable(accel), Optional.ofNullable(decel));
                    ctx.sender()
                        .sendMessage(
                            locale.component(
                                "command.train.config.set",
                                Map.of(
                                    "train",
                                    properties.getTrainName(),
                                    "type",
                                    target.type().name(),
                                    "accel",
                                    String.valueOf(target.accelBps2()),
                                    "decel",
                                    String.valueOf(target.decelBps2()))));
                  }
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("train")
            .literal("config")
            .literal("list")
            .permission("fetarute.train.config")
            .optional("train", StringParser.stringParser(), trainSuggestions)
            .handler(
                ctx -> {
                  LocaleManager locale = plugin.getLocaleManager();
                  Optional<String> trainArg = ctx.optional("train").map(String.class::cast);
                  List<TrainProperties> targets =
                      resolveTrainTargets(ctx.sender(), trainArg, locale);
                  if (targets.isEmpty()) {
                    return;
                  }
                  for (TrainProperties properties : targets) {
                    String trainName = properties.getTrainName();
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
                                    "accel",
                                    String.valueOf(config.accelBps2()),
                                    "decel",
                                    String.valueOf(config.decelBps2()))));
                  }
                }));

    // debug 子命令：显示控车诊断数据
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("train")
            .literal("debug")
            .permission("fetarute.train.debug")
            .optional("train", StringParser.stringParser(), trainSuggestions)
            .handler(
                ctx -> handleDebug(ctx.sender(), ctx.optional("train").map(String.class::cast))));

    // debug list 子命令：显示所有缓存的诊断数据
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("train")
            .literal("debug")
            .literal("list")
            .permission("fetarute.train.debug")
            .handler(ctx -> handleDebugList(ctx.sender())));
  }

  private void handleDebug(CommandSender sender, Optional<String> trainArg) {
    LocaleManager locale = plugin.getLocaleManager();
    var dispatchServiceOpt = plugin.getRuntimeDispatchService();
    if (dispatchServiceOpt.isEmpty()) {
      sender.sendMessage(locale.component("command.train.debug.unavailable"));
      return;
    }
    var dispatchService = dispatchServiceOpt.get();
    List<TrainProperties> targets = resolveTrainTargets(sender, trainArg, locale);
    if (targets.isEmpty()) {
      return;
    }
    for (TrainProperties properties : targets) {
      String trainName = properties.getTrainName();
      var diagnosticsOpt = dispatchService.getDiagnostics(trainName);
      if (diagnosticsOpt.isEmpty()) {
        sender.sendMessage(
            locale.component("command.train.debug.no-data", Map.of("train", trainName)));
        continue;
      }
      var diag = diagnosticsOpt.get();
      sendDiagnosticsOutput(sender, diag, locale);
    }
  }

  private void handleDebugList(CommandSender sender) {
    LocaleManager locale = plugin.getLocaleManager();
    var dispatchServiceOpt = plugin.getRuntimeDispatchService();
    if (dispatchServiceOpt.isEmpty()) {
      sender.sendMessage(locale.component("command.train.debug.unavailable"));
      return;
    }
    var dispatchService = dispatchServiceOpt.get();
    var snapshot = dispatchService.getDiagnosticsSnapshot();
    if (snapshot.isEmpty()) {
      sender.sendMessage(locale.component("command.train.debug.list-empty"));
      return;
    }
    sender.sendMessage(
        locale.component(
            "command.train.debug.list-header", Map.of("count", String.valueOf(snapshot.size()))));
    for (var entry : snapshot.entrySet()) {
      sendDiagnosticsOutput(sender, entry.getValue(), locale);
    }
  }

  private void sendDiagnosticsOutput(
      CommandSender sender,
      org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.ControlDiagnostics diag,
      LocaleManager locale) {
    // 基础信息
    sender.sendMessage(
        locale.component(
            "command.train.debug.header",
            Map.of(
                "train",
                diag.trainName(),
                "route",
                diag.routeId() != null ? diag.routeId().value() : "-")));

    // 节点信息
    sender.sendMessage(
        locale.component(
            "command.train.debug.nodes",
            Map.of(
                "current", diag.currentNode() != null ? diag.currentNode().value() : "-",
                "next", diag.nextNode() != null ? diag.nextNode().value() : "-")));

    // 速度信息（含边限速）
    String edgeLimitText = diag.edgeLimitBps() > 0 ? formatSpeed(diag.edgeLimitBps()) : "-";
    sender.sendMessage(
        locale.component(
            "command.train.debug.speed",
            Map.of(
                "current",
                formatSpeed(diag.currentSpeedBps()),
                "target",
                formatSpeed(diag.targetSpeedBps()),
                "edge_limit",
                edgeLimitText,
                "recommended",
                diag.recommendedSpeedBps().isPresent()
                    ? formatSpeed(diag.recommendedSpeedBps().getAsDouble())
                    : "-")));

    // 前瞻距离
    sender.sendMessage(
        locale.component(
            "command.train.debug.lookahead",
            Map.of(
                "blocker", formatDistance(diag.distanceToBlocker()),
                "caution", formatDistance(diag.distanceToCaution()),
                "approach", formatDistance(diag.distanceToApproach()))));

    // 信号与状态
    sender.sendMessage(
        locale.component(
            "command.train.debug.signal",
            Map.of(
                "current", diag.currentSignal().name(),
                "effective", diag.effectiveSignal().name(),
                "launch", diag.allowLaunch() ? "✓" : "✗")));
  }

  private static String formatSpeed(double bps) {
    return String.format("%.2f", bps);
  }

  private static String formatDistance(java.util.OptionalLong distance) {
    return distance.isPresent() ? String.valueOf(distance.getAsLong()) : "-";
  }

  private SuggestionProvider<CommandSender> trainSuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          List<String> suggestions = new ArrayList<>();
          String token = input == null ? "" : input.lastRemainingToken();
          token = token.trim();
          if (token.isBlank()) {
            suggestions.add("<train|@train[...]>"); // 对齐 TrainCarts selector 习惯
          }
          if (token.startsWith("@")) {
            suggestions.addAll(selectorSuggestions(ctx.sender(), token));
          } else {
            if (token.isBlank()) {
              suggestions.add(TRAIN_SELECTOR_PREFIX);
            }
            suggestions.addAll(trainNameSuggestions(token));
          }
          return suggestions;
        });
  }

  private List<String> selectorSuggestions(CommandSender sender, String token) {
    if (token == null) {
      return List.of();
    }
    String normalized = token.trim();
    String lowered = normalized.toLowerCase(Locale.ROOT);
    List<String> suggestions = new ArrayList<>();
    if (lowered.equals("@") || TRAIN_SELECTOR_PREFIX.startsWith(lowered)) {
      suggestions.add(TRAIN_SELECTOR_PREFIX);
      return suggestions;
    }
    if (!lowered.startsWith(TRAIN_SELECTOR_PREFIX)) {
      return List.of();
    }

    if (!normalized.endsWith("]")) {
      suggestions.add(normalized + "]");
    }

    int lastComma = normalized.lastIndexOf(',');
    int lastBracket = normalized.lastIndexOf('[');
    if (lastBracket < 0) {
      return suggestions;
    }
    int tailStart = Math.max(lastComma + 1, lastBracket + 1);
    while (tailStart < normalized.length()
        && Character.isWhitespace(normalized.charAt(tailStart))) {
      tailStart++;
    }
    String prefix = normalized.substring(0, tailStart);
    String tail = normalized.substring(tailStart);
    String tailLower = tail.trim().toLowerCase(Locale.ROOT);
    if (tailLower.isEmpty()) {
      for (String key : TRAIN_SELECTOR_KEYS) {
        suggestions.add(prefix + key);
        if (suggestions.size() >= SUGGESTION_LIMIT) {
          break;
        }
      }
      return suggestions;
    }

    int equalsIndex = tailLower.indexOf('=');
    if (equalsIndex >= 0) {
      String key = tailLower.substring(0, equalsIndex + 1);
      String valuePrefix = tailLower.substring(equalsIndex + 1);
      if (key.equals("name=")) {
        for (String name : trainNameSuggestions(valuePrefix)) {
          suggestions.add(prefix + "name=" + name);
          if (suggestions.size() >= SUGGESTION_LIMIT) {
            break;
          }
        }
        return suggestions;
      }
      if (key.equals("tag=")) {
        for (String tag : trainTagSuggestions(valuePrefix)) {
          suggestions.add(prefix + "tag=" + tag);
          if (suggestions.size() >= SUGGESTION_LIMIT) {
            break;
          }
        }
        return suggestions;
      }
      if (key.equals("sort=")) {
        for (String value : TRAIN_SELECTOR_SORT_VALUES) {
          if (value.startsWith(valuePrefix)) {
            suggestions.add(prefix + "sort=" + value);
          }
        }
        return suggestions;
      }
      if (key.equals("limit=")) {
        for (String value : TRAIN_SELECTOR_LIMIT_VALUES) {
          if (value.startsWith(valuePrefix)) {
            suggestions.add(prefix + "limit=" + value);
          }
        }
        return suggestions;
      }
      if (key.equals("world=")) {
        for (org.bukkit.World world : plugin.getServer().getWorlds()) {
          String name = world.getName();
          if (name.toLowerCase(Locale.ROOT).startsWith(valuePrefix)) {
            suggestions.add(prefix + "world=" + name);
            if (suggestions.size() >= SUGGESTION_LIMIT) {
              break;
            }
          }
        }
        return suggestions;
      }
      if (key.equals("destination=")) {
        for (String destination : trainDestinationSuggestions(valuePrefix)) {
          suggestions.add(prefix + "destination=" + destination);
          if (suggestions.size() >= SUGGESTION_LIMIT) {
            break;
          }
        }
        return suggestions;
      }
      if (key.equals("ticket=")) {
        for (String ticket : trainTicketSuggestions(valuePrefix)) {
          suggestions.add(prefix + "ticket=" + ticket);
          if (suggestions.size() >= SUGGESTION_LIMIT) {
            break;
          }
        }
        return suggestions;
      }
      if (key.equals("passengers=") || key.equals("playerpassengers=")) {
        for (String value : TRAIN_SELECTOR_LIMIT_VALUES) {
          if (value.startsWith(valuePrefix)) {
            suggestions.add(prefix + key + value);
          }
        }
        return suggestions;
      }
      if (key.equals("x=")
          || key.equals("y=")
          || key.equals("z=")
          || key.equals("dx=")
          || key.equals("dy=")
          || key.equals("dz=")
          || key.equals("distance=")
          || key.equals("speed=")
          || key.equals("velocity=")
          || key.equals("speedlimit=")
          || key.equals("friction=")
          || key.equals("gravity=")) {
        for (String value : TRAIN_SELECTOR_NUMBER_VALUES) {
          if (value.startsWith(valuePrefix)) {
            suggestions.add(prefix + key + value);
          }
        }
        return suggestions;
      }
      if (key.equals("unloaded=") || key.equals("derailed=") || key.equals("keepchunksloaded=")) {
        for (String value : TRAIN_SELECTOR_BOOLEAN_VALUES) {
          if (value.startsWith(valuePrefix)) {
            suggestions.add(prefix + key + value);
          }
        }
        return suggestions;
      }
    }

    for (String key : TRAIN_SELECTOR_KEYS) {
      if (key.startsWith(tailLower)) {
        suggestions.add(prefix + key);
        if (suggestions.size() >= SUGGESTION_LIMIT) {
          break;
        }
      }
    }
    return suggestions;
  }

  private static List<String> trainNameSuggestions(String token) {
    String prefix = token == null ? "" : token.trim().toLowerCase(Locale.ROOT);
    List<String> suggestions = new ArrayList<>();
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
    }
    suggestions.sort(String.CASE_INSENSITIVE_ORDER);
    if (suggestions.size() > SUGGESTION_LIMIT) {
      return suggestions.subList(0, SUGGESTION_LIMIT);
    }
    return suggestions;
  }

  private static List<String> trainTagSuggestions(String token) {
    String prefix = token == null ? "" : token.trim().toLowerCase(Locale.ROOT);
    List<String> suggestions = new ArrayList<>();
    for (TrainProperties props : TrainPropertiesStore.getAll()) {
      if (props == null) {
        continue;
      }
      Collection<String> tags = props.getTags();
      if (tags == null || tags.isEmpty()) {
        continue;
      }
      for (String tag : tags) {
        if (tag == null || tag.isBlank()) {
          continue;
        }
        String normalized = tag.trim();
        if (!normalized.toLowerCase(Locale.ROOT).startsWith(prefix)) {
          continue;
        }
        suggestions.add(normalized);
      }
    }
    suggestions.sort(String.CASE_INSENSITIVE_ORDER);
    if (suggestions.size() > SUGGESTION_LIMIT) {
      return suggestions.subList(0, SUGGESTION_LIMIT);
    }
    return suggestions;
  }

  private static List<String> trainDestinationSuggestions(String token) {
    String prefix = token == null ? "" : token.trim().toLowerCase(Locale.ROOT);
    List<String> suggestions = new ArrayList<>();
    for (TrainProperties props : TrainPropertiesStore.getAll()) {
      if (props == null || !props.hasDestination()) {
        continue;
      }
      String destination = props.getDestination();
      if (destination == null || destination.isBlank()) {
        continue;
      }
      String normalized = destination.trim();
      if (!normalized.toLowerCase(Locale.ROOT).startsWith(prefix)) {
        continue;
      }
      suggestions.add(normalized);
    }
    suggestions.sort(String.CASE_INSENSITIVE_ORDER);
    if (suggestions.size() > SUGGESTION_LIMIT) {
      return suggestions.subList(0, SUGGESTION_LIMIT);
    }
    return suggestions;
  }

  private static List<String> trainTicketSuggestions(String token) {
    String prefix = token == null ? "" : token.trim().toLowerCase(Locale.ROOT);
    List<String> suggestions = new ArrayList<>();
    for (TrainProperties props : TrainPropertiesStore.getAll()) {
      if (props == null) {
        continue;
      }
      Collection<String> tickets = props.getTickets();
      if (tickets == null || tickets.isEmpty()) {
        continue;
      }
      for (String ticket : tickets) {
        if (ticket == null || ticket.isBlank()) {
          continue;
        }
        String normalized = ticket.trim();
        if (!normalized.toLowerCase(Locale.ROOT).startsWith(prefix)) {
          continue;
        }
        suggestions.add(normalized);
      }
    }
    suggestions.sort(String.CASE_INSENSITIVE_ORDER);
    if (suggestions.size() > SUGGESTION_LIMIT) {
      return suggestions.subList(0, SUGGESTION_LIMIT);
    }
    return suggestions;
  }

  private List<TrainProperties> resolveTrainTargets(
      CommandSender sender, Optional<String> rawTrain, LocaleManager locale) {
    String raw = rawTrain.map(String::trim).orElse("");
    if (!raw.isEmpty()) {
      if (isSelector(raw)) {
        return resolveSelector(sender, raw, locale);
      }
      TrainProperties properties = TrainPropertiesStore.get(raw);
      if (properties == null) {
        sender.sendMessage(
            locale.component("command.train.config.not-found", Map.of("train", raw)));
        return List.of();
      }
      return List.of(properties);
    }
    if (sender instanceof Player player) {
      CartProperties editing = CartPropertiesStore.getEditing(player);
      if (editing != null && editing.getTrainProperties() != null) {
        return List.of(editing.getTrainProperties());
      }
      MinecartGroup group = MinecartGroupStore.get(player);
      if (group != null && group.getProperties() != null) {
        return List.of(group.getProperties());
      }
    }
    sendNoSelection(sender, locale);
    return List.of();
  }

  private void sendHelp(CommandSender sender) {
    LocaleManager locale = plugin.getLocaleManager();
    sender.sendMessage(locale.component("command.train.help.header"));
    sendHelpEntry(
        sender,
        locale.component("command.train.help.entry-config-list"),
        ClickEvent.suggestCommand("/fta train config list "),
        locale.component("command.train.help.hover-config-list"));
    sendHelpEntry(
        sender,
        locale.component("command.train.help.entry-config-set"),
        ClickEvent.suggestCommand("/fta train config set "),
        locale.component("command.train.help.hover-config-set"));
    sendHelpEntry(
        sender,
        locale.component("command.train.help.entry-train-edit"),
        ClickEvent.suggestCommand("/train edit "),
        locale.component("command.train.help.hover-train-edit"));
    sendHelpEntry(
        sender,
        locale.component("command.train.help.entry-selector"),
        ClickEvent.suggestCommand("/fta train config list @train["),
        locale.component("command.train.help.hover-selector"));
  }

  private void sendNoSelection(CommandSender sender, LocaleManager locale) {
    sender.sendMessage(locale.component("command.train.config.no-selection"));
    if (!(sender instanceof Player)) {
      return;
    }
    sendHelpEntry(
        sender,
        locale.component("command.train.config.no-selection-entry-train-edit"),
        ClickEvent.suggestCommand("/train edit "),
        locale.component("command.train.config.no-selection-hover-train-edit"));
    sendHelpEntry(
        sender,
        locale.component("command.train.config.no-selection-entry-selector"),
        ClickEvent.suggestCommand("/fta train config list @train["),
        locale.component("command.train.config.no-selection-hover-selector"));
  }

  private void sendHelpEntry(
      CommandSender sender, Component text, ClickEvent clickEvent, Component hoverText) {
    sender.sendMessage(text.clickEvent(clickEvent).hoverEvent(HoverEvent.showText(hoverText)));
  }

  private boolean isSelector(String raw) {
    if (raw == null) {
      return false;
    }
    return raw.trim().toLowerCase(Locale.ROOT).startsWith(TRAIN_SELECTOR_PREFIX);
  }

  private List<TrainProperties> resolveSelector(
      CommandSender sender, String raw, LocaleManager locale) {
    TrainCarts trainCarts = TrainCarts.plugin;
    if (trainCarts == null) {
      return List.of();
    }
    if (!(trainCarts.getSelectorHandlerRegistry() instanceof TCSelectorHandlerRegistry registry)) {
      return List.of();
    }
    Optional<String> selectorOpt = parseTrainSelectorConditions(raw);
    if (selectorOpt.isEmpty()) {
      sender.sendMessage(
          locale.component("command.train.config.invalid-selector", Map.of("raw", raw)));
      return List.of();
    }
    String selectorRaw = selectorOpt.get();
    try {
      List<SelectorCondition> conditions =
          selectorRaw.isBlank() ? List.of() : SelectorCondition.parseAll(selectorRaw);
      Collection<TrainProperties> matched = registry.matchTrains(sender, conditions);
      if (matched.isEmpty()) {
        sender.sendMessage(
            locale.component("command.train.config.not-found", Map.of("train", raw)));
        return List.of();
      }
      return matched.stream()
          .filter(props -> props.getTrainName() != null && !props.getTrainName().isBlank())
          .sorted(Comparator.comparing(props -> props.getTrainName().toLowerCase(Locale.ROOT)))
          .toList();
    } catch (SelectorException ex) {
      sender.sendMessage(locale.component("command.train.config.not-found", Map.of("train", raw)));
      return List.of();
    }
  }

  private static Optional<String> parseTrainSelectorConditions(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String selector = raw.trim();
    if (selector.isEmpty()) {
      return Optional.empty();
    }
    String lowered = selector.toLowerCase(Locale.ROOT);
    if (lowered.startsWith(TRAIN_SELECTOR_PREFIX)) {
      if (!selector.endsWith("]")) {
        return Optional.empty();
      }
      String conditions = selector.substring("@train[".length(), selector.length() - 1).trim();
      return Optional.of(conditions);
    }
    return Optional.empty();
  }
}
