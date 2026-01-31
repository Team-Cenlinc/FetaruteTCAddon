package org.fetarute.fetaruteTCAddon.command;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartGroupStore;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.BoardResult;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaConfidence;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaReason;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaResult;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaService;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaTarget;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainRuntimeSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;
import org.fetarute.fetaruteTCAddon.storage.StorageManager;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.parser.standard.IntegerParser;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.SuggestionProvider;

/**
 * /fta eta 命令注册。
 *
 * <p>用于输出 ETA 诊断信息，便于调试与 HUD 验证。
 */
public final class FtaEtaCommand {

  private static final int SUGGESTION_LIMIT = 20;
  private static final int BOARD_LIMIT = 20;
  private static final int DEFAULT_BOARD_HORIZON_SEC = 600;

  private final FetaruteTCAddon plugin;

  public FtaEtaCommand(FetaruteTCAddon plugin) {
    this.plugin = plugin;
  }

  /** 注册 /fta eta 相关命令与补全。 */
  public void register(CommandManager<CommandSender> manager) {
    var trainSuggestions = trainSuggestions("<train>");
    var stationSuggestions = stationSuggestions("<stationId>");
    var platformSuggestions = graphNodeIdSuggestions("\"<nodeId>\"");
    var ticketSuggestions = ticketSuggestions("<ticketId>");
    var lineSuggestions = lineSuggestions("<lineId>");
    var horizonSuggestions = horizonSuggestions("<sec>");

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("eta")
            .permission("fetarute.eta")
            .handler(ctx -> sendHelp(ctx.sender())));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("eta")
            .literal("train")
            .permission("fetarute.eta")
            .required("train", StringParser.stringParser(), trainSuggestions)
            .handler(
                ctx -> {
                  String trainName = ctx.get("train");
                  showTrainEta(ctx.sender(), trainName, EtaTarget.nextStop());
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("eta")
            .literal("train")
            .literal("station")
            .permission("fetarute.eta")
            .required("train", StringParser.stringParser(), trainSuggestions)
            .required("station", StringParser.stringParser(), stationSuggestions)
            .handler(
                ctx -> {
                  String trainName = ctx.get("train");
                  String stationId = ctx.get("station");
                  showTrainEta(ctx.sender(), trainName, new EtaTarget.Station(stationId));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("eta")
            .literal("train")
            .literal("platform")
            .permission("fetarute.eta")
            .required("train", StringParser.stringParser(), trainSuggestions)
            .required("node", StringParser.quotedStringParser(), platformSuggestions)
            .handler(
                ctx -> {
                  String trainName = ctx.get("train");
                  String nodeId = normalizeNodeIdArg(ctx.get("node"));
                  showTrainEta(
                      ctx.sender(), trainName, new EtaTarget.PlatformNode(NodeId.of(nodeId)));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("eta")
            .literal("ticket")
            .permission("fetarute.eta")
            .required("ticket", StringParser.stringParser(), ticketSuggestions)
            .handler(
                ctx -> {
                  String ticketId = ctx.get("ticket");
                  showTicketEta(ctx.sender(), ticketId);
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("eta")
            .literal("board")
            .permission("fetarute.eta")
            .required("operator", StringParser.stringParser(), operatorSuggestions("<operator>"))
            .required(
                "station", StringParser.stringParser(), stationCodeSuggestions("<stationCode>"))
            .optional("line", StringParser.stringParser(), lineSuggestions)
            .optional("horizon", IntegerParser.integerParser(10, 3600), horizonSuggestions)
            .handler(
                ctx -> {
                  String operator = ctx.get("operator");
                  String station = ctx.get("station");
                  String lineId = ctx.<String>optional("line").orElse(null);
                  int horizonSec =
                      ctx.<Integer>optional("horizon").orElse(DEFAULT_BOARD_HORIZON_SEC);
                  showBoard(
                      ctx.sender(), operator, station, lineId, Duration.ofSeconds(horizonSec));
                }));
  }

  /** 输出 ETA 帮助，并附带可点击建议命令。 */
  private void sendHelp(CommandSender sender) {
    LocaleManager locale = plugin.getLocaleManager();
    sender.sendMessage(locale.component("command.eta.help.header"));
    sendHelpEntry(
        sender,
        locale.component("command.eta.help.entry-train"),
        ClickEvent.suggestCommand("/fta eta train "),
        locale.component("command.eta.help.hover-train"));
    sendHelpEntry(
        sender,
        locale.component("command.eta.help.entry-train-station"),
        ClickEvent.suggestCommand("/fta eta train station "),
        locale.component("command.eta.help.hover-train-station"));
    sendHelpEntry(
        sender,
        locale.component("command.eta.help.entry-train-platform"),
        ClickEvent.suggestCommand("/fta eta train platform "),
        locale.component("command.eta.help.hover-train-platform"));
    sendHelpEntry(
        sender,
        locale.component("command.eta.help.entry-ticket"),
        ClickEvent.suggestCommand("/fta eta ticket "),
        locale.component("command.eta.help.hover-ticket"));
    sendHelpEntry(
        sender,
        locale.component("command.eta.help.entry-board"),
        ClickEvent.suggestCommand("/fta eta board "),
        locale.component("command.eta.help.hover-board"));
  }

  private void sendHelpEntry(
      CommandSender sender, Component text, ClickEvent clickEvent, Component hoverText) {
    sender.sendMessage(text.clickEvent(clickEvent).hoverEvent(HoverEvent.showText(hoverText)));
  }

  private void showTrainEta(CommandSender sender, String trainName, EtaTarget target) {
    EtaService service = plugin.getEtaService();
    if (service == null) {
      sendNotReady(sender);
      return;
    }
    LocaleManager locale = plugin.getLocaleManager();
    EtaResult result = service.getForTrain(trainName, target);
    String targetText = describeTarget(target);
    sender.sendMessage(
        locale.component(
            "command.eta.train.header", Map.of("train", trainName, "target", targetText)));
    sendEtaSummary(sender, locale, result, true);
    Optional<TrainRuntimeSnapshot> snapOpt = service.getRuntimeSnapshot(trainName);
    if (snapOpt.isEmpty()) {
      sender.sendMessage(locale.component("command.eta.train.snapshot-missing"));
      return;
    }
    TrainRuntimeSnapshot snap = snapOpt.get();
    String route = snap.routeId().value();
    String index = String.valueOf(snap.routeIndex());
    String world = snap.worldId().toString();
    String signal = snap.signalAspect().map(Enum::name).orElse("-");
    String dwell = snap.dwellRemainingSec().map(sec -> formatSeconds(sec)).orElse("-");
    String current = snap.currentNodeId().map(node -> node.value()).orElse("-");
    String lastPassed = snap.lastPassedNodeId().map(node -> node.value()).orElse("-");
    String ticket = snap.ticketId().orElse("-");
    sender.sendMessage(
        locale.component(
            "command.eta.train.snapshot",
            Map.of(
                "route",
                route,
                "index",
                index,
                "world",
                world,
                "signal",
                signal,
                "dwell",
                dwell,
                "current",
                current,
                "last",
                lastPassed,
                "ticket",
                ticket)));
  }

  private void showTicketEta(CommandSender sender, String ticketId) {
    EtaService service = plugin.getEtaService();
    if (service == null) {
      sendNotReady(sender);
      return;
    }
    LocaleManager locale = plugin.getLocaleManager();
    EtaResult result = service.getForTicket(ticketId);
    sender.sendMessage(locale.component("command.eta.ticket.header", Map.of("ticket", ticketId)));
    sendEtaSummary(sender, locale, result, false);
  }

  private void showBoard(
      CommandSender sender, String operator, String stationCode, String lineId, Duration horizon) {
    EtaService service = plugin.getEtaService();
    if (service == null) {
      sendNotReady(sender);
      return;
    }
    LocaleManager locale = plugin.getLocaleManager();
    StationNameResolver resolver = buildStationNameResolver();
    BoardResult board = service.getBoard(operator, stationCode, lineId, horizon);
    String lineText = lineId == null || lineId.isBlank() ? "-" : lineId;
    String stationText = operator + ":" + stationCode;
    sender.sendMessage(
        locale.component(
            "command.eta.board.header",
            Map.of(
                "station",
                stationText,
                "line",
                lineText,
                "rows",
                String.valueOf(board.rows().size()))));
    if (board.rows().isEmpty()) {
      sender.sendMessage(locale.component("command.eta.board.empty"));
      return;
    }
    int shown = 0;
    for (BoardResult.BoardRow row : board.rows()) {
      if (shown >= BOARD_LIMIT) {
        sender.sendMessage(locale.component("command.eta.board.truncated"));
        break;
      }
      String destText = formatBoardDestination(row, resolver);
      String endRouteText = formatBoardStation(row.endRoute(), row.endRouteId(), resolver);
      String endOperationText =
          formatBoardStation(row.endOperation(), row.endOperationId(), resolver);
      sender.sendMessage(
          locale.component(
              "command.eta.board.row",
              Map.of(
                  "line",
                  row.lineName(),
                  "route",
                  row.routeId(),
                  "dest",
                  destText,
                  "end_route",
                  endRouteText,
                  "end_operation",
                  endOperationText,
                  "platform",
                  row.platform(),
                  "status",
                  row.statusText(),
                  "reasons",
                  joinReasons(row.reasons()))));
      shown++;
    }
  }

  private void sendEtaSummary(
      CommandSender sender, LocaleManager locale, EtaResult result, boolean includeArriving) {
    String status = result.statusText();
    String etaText = formatEtaMinutes(result);
    String etaAt = formatEtaInstant(result);
    String conf = formatConfidence(result.confidence());
    String reasons = joinReasons(result.reasons());
    String travel = formatSeconds(result.travelSec());
    String dwell = formatSeconds(result.dwellSec());
    String wait = formatSeconds(result.waitSec());
    String minutes =
        result.etaMinutesRounded() >= 0 ? String.valueOf(result.etaMinutesRounded()) : "-";
    Map<String, String> summary =
        Map.of(
            "status",
            status,
            "eta",
            etaText,
            "at",
            etaAt,
            "conf",
            conf,
            "arriving",
            includeArriving ? formatYesNo(locale, result.arriving()) : "-");
    if (includeArriving) {
      sender.sendMessage(locale.component("command.eta.train.summary", summary));
    } else {
      sender.sendMessage(locale.component("command.eta.ticket.summary", summary));
    }
    sender.sendMessage(
        locale.component(
            includeArriving ? "command.eta.train.breakdown" : "command.eta.ticket.breakdown",
            Map.of("travel", travel, "dwell", dwell, "wait", wait, "minutes", minutes)));
    sender.sendMessage(
        locale.component(
            includeArriving ? "command.eta.train.reasons" : "command.eta.ticket.reasons",
            Map.of("reasons", reasons)));
  }

  private String formatEtaMinutes(EtaResult result) {
    int minutes = result.etaMinutesRounded();
    if (minutes < 0) {
      return "N/A";
    }
    return minutes + "m";
  }

  private String formatEtaInstant(EtaResult result) {
    if (result.etaEpochMillis() <= 0L) {
      return "-";
    }
    return DateTimeFormatter.ISO_INSTANT.format(result.eta());
  }

  private String formatSeconds(int sec) {
    if (sec < 0) {
      return "-";
    }
    if (sec < 60) {
      return sec + "s";
    }
    int minutes = sec / 60;
    int remain = sec % 60;
    return remain == 0 ? minutes + "m" : minutes + "m" + remain + "s";
  }

  private String joinReasons(List<EtaReason> reasons) {
    if (reasons == null || reasons.isEmpty()) {
      return "-";
    }
    StringBuilder sb = new StringBuilder();
    for (EtaReason reason : reasons) {
      if (reason == null) {
        continue;
      }
      if (sb.length() > 0) {
        sb.append("/");
      }
      sb.append(reason.name());
    }
    return sb.length() == 0 ? "-" : sb.toString();
  }

  private String formatConfidence(EtaConfidence confidence) {
    if (confidence == null) {
      return "UNKNOWN";
    }
    return confidence.name();
  }

  private String formatYesNo(LocaleManager locale, boolean value) {
    return locale.text(value ? "command.common.yes" : "command.common.no");
  }

  private String describeTarget(EtaTarget target) {
    if (target == null || target instanceof EtaTarget.NextStop) {
      return "next";
    }
    if (target instanceof EtaTarget.Station station) {
      return "station:" + station.stationId();
    }
    if (target instanceof EtaTarget.PlatformNode pn) {
      return "platform:" + pn.nodeId().value();
    }
    return "unknown";
  }

  private StationNameResolver buildStationNameResolver() {
    StorageManager storage = plugin.getStorageManager();
    if (storage == null || !storage.isReady()) {
      return new StationNameResolver(null, new HashMap<>());
    }
    StorageProvider provider = storage.provider().orElse(null);
    return new StationNameResolver(provider, new HashMap<>());
  }

  private String formatBoardDestination(BoardResult.BoardRow row, StationNameResolver resolver) {
    return formatBoardStation(row.destination(), row.destinationId(), resolver);
  }

  private String formatBoardStation(
      String base, Optional<String> destIdOpt, StationNameResolver resolver) {
    String label = base;
    if (destIdOpt.isPresent()) {
      String destId = destIdOpt.get();
      if ("OUT_OF_SERVICE".equalsIgnoreCase(destId)) {
        String primary = (label == null || label.isBlank()) ? "回库" : label;
        return primary + " / Not in Service (" + destId + ")";
      }
      Optional<StationKey> keyOpt = parseStationKey(destId);
      Optional<String> nameOpt =
          keyOpt.isPresent() ? resolveStationName(resolver, keyOpt.get()) : Optional.empty();
      label = nameOpt.orElse(base);
      if (label == null || label.isBlank()) {
        label = destId;
      }
      if (!containsIgnoreCase(label, destId)) {
        label = label + " (" + destId + ")";
      }
    }
    return label == null || label.isBlank() ? "-" : label;
  }

  private Optional<String> resolveStationName(StationNameResolver resolver, StationKey key) {
    if (resolver == null || resolver.provider() == null || key == null) {
      return Optional.empty();
    }
    String cacheKey =
        key.operator().toLowerCase(Locale.ROOT) + ":" + key.station().toLowerCase(Locale.ROOT);
    if (resolver.cache().containsKey(cacheKey)) {
      return resolver.cache().get(cacheKey);
    }
    Optional<String> result = Optional.empty();
    StorageProvider provider = resolver.provider();
    for (Company company : provider.companies().listAll()) {
      if (company == null) {
        continue;
      }
      for (Operator operator : provider.operators().listByCompany(company.id())) {
        if (operator == null || operator.code() == null) {
          continue;
        }
        if (!operator.code().equalsIgnoreCase(key.operator())) {
          continue;
        }
        Optional<Station> stationOpt =
            provider.stations().findByOperatorAndCode(operator.id(), key.station());
        if (stationOpt.isPresent()) {
          Station station = stationOpt.get();
          if (station.name() != null && !station.name().isBlank()) {
            result = Optional.of(station.name());
          }
        }
        break;
      }
      if (result.isPresent()) {
        break;
      }
    }
    resolver.cache().put(cacheKey, result);
    return result;
  }

  private Optional<StationKey> parseStationKey(String stationId) {
    if (stationId == null || stationId.isBlank()) {
      return Optional.empty();
    }
    int idx = stationId.indexOf(':');
    if (idx <= 0 || idx >= stationId.length() - 1) {
      return Optional.empty();
    }
    String operator = stationId.substring(0, idx).trim();
    String station = stationId.substring(idx + 1).trim();
    if (operator.isEmpty() || station.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new StationKey(operator, station));
  }

  private boolean containsIgnoreCase(String raw, String needle) {
    if (raw == null || needle == null) {
      return false;
    }
    return raw.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
  }

  private void sendNotReady(CommandSender sender) {
    LocaleManager locale = plugin.getLocaleManager();
    sender.sendMessage(locale.component("command.eta.not-ready"));
  }

  private Optional<StorageProvider> providerIfReady() {
    return CommandStorageProviders.providerIfReady(plugin);
  }

  private boolean canReadCompanyNoCreateIdentity(
      CommandSender sender, StorageProvider provider, java.util.UUID companyId) {
    return CompanyAccessChecker.canReadCompanyNoCreateIdentity(sender, provider, companyId);
  }

  private record StationNameResolver(
      StorageProvider provider, Map<String, Optional<String>> cache) {}

  private record StationKey(String operator, String station) {}

  private SuggestionProvider<CommandSender> trainSuggestions(String placeholder) {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix =
              input == null ? "" : input.lastRemainingToken().trim().toLowerCase(Locale.ROOT);
          List<String> suggestions = new ArrayList<>();
          if (prefix.isBlank()) {
            suggestions.add(placeholder);
          }
          Set<String> names = new LinkedHashSet<>();
          EtaService service = plugin.getEtaService();
          if (service != null) {
            names.addAll(service.snapshotTrainNames());
          }
          var groups = MinecartGroupStore.getGroups();
          if (groups != null) {
            for (MinecartGroup group : groups) {
              if (group == null || group.getProperties() == null) {
                continue;
              }
              String name = group.getProperties().getTrainName();
              if (name != null && !name.isBlank()) {
                names.add(name);
              }
            }
          }
          for (String name : names) {
            if (prefix.isBlank() || name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
              suggestions.add(name);
            }
            if (suggestions.size() >= SUGGESTION_LIMIT) {
              break;
            }
          }
          return suggestions;
        });
  }

  private SuggestionProvider<CommandSender> graphNodeIdSuggestions(String placeholder) {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = input != null ? input.lastRemainingToken() : "";
          prefix = normalizeNodeIdArg(prefix).toLowerCase(Locale.ROOT);

          List<String> suggestions = new ArrayList<>();
          if (prefix.isBlank() && placeholder != null && !placeholder.isBlank()) {
            suggestions.add(placeholder);
          }

          World world = resolveSuggestionWorld(ctx.sender());
          if (world == null) {
            return suggestions;
          }
          RailGraphService service = plugin.getRailGraphService();
          if (service == null) {
            return suggestions;
          }
          Optional<RailGraphService.RailGraphSnapshot> snapshotOpt = service.getSnapshot(world);
          if (snapshotOpt.isEmpty()) {
            return suggestions;
          }

          List<String> nodeIds = new ArrayList<>();
          for (RailNode node : snapshotOpt.get().graph().nodes()) {
            if (node == null || node.id() == null) {
              continue;
            }
            String raw = node.id().value();
            if (raw == null) {
              continue;
            }
            String lower = raw.toLowerCase(Locale.ROOT);
            if (prefix.isBlank() || lower.startsWith(prefix)) {
              nodeIds.add('"' + raw + '"');
            }
          }
          nodeIds.stream().distinct().sorted().limit(SUGGESTION_LIMIT).forEach(suggestions::add);
          return suggestions;
        });
  }

  private SuggestionProvider<CommandSender> operatorSuggestions(String placeholder) {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix =
              input != null ? input.lastRemainingToken().trim().toLowerCase(Locale.ROOT) : "";
          List<String> suggestions = new ArrayList<>();
          if (prefix.isBlank() && placeholder != null && !placeholder.isBlank()) {
            suggestions.add(placeholder);
          }

          World world = resolveSuggestionWorld(ctx.sender());
          if (world == null) {
            return suggestions;
          }
          RailGraphService service = plugin.getRailGraphService();
          if (service == null) {
            return suggestions;
          }
          Optional<RailGraphService.RailGraphSnapshot> snapshotOpt = service.getSnapshot(world);
          if (snapshotOpt.isEmpty()) {
            return suggestions;
          }

          Set<String> candidates = new LinkedHashSet<>();
          for (RailNode node : snapshotOpt.get().graph().nodes()) {
            if (node == null) {
              continue;
            }
            Optional<WaypointMetadata> metaOpt = node.waypointMetadata();
            if (metaOpt.isEmpty()) {
              continue;
            }
            WaypointMetadata meta = metaOpt.get();
            if (meta.operator() != null && !meta.operator().isBlank()) {
              candidates.add(meta.operator());
            }
          }

          candidates.stream()
              .filter(s -> prefix.isBlank() || s.toLowerCase(Locale.ROOT).startsWith(prefix))
              .sorted(String.CASE_INSENSITIVE_ORDER)
              .limit(SUGGESTION_LIMIT)
              .forEach(suggestions::add);
          return suggestions;
        });
  }

  private SuggestionProvider<CommandSender> stationCodeSuggestions(String placeholder) {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix =
              input != null
                  ? normalizeNodeIdArg(input.lastRemainingToken()).toLowerCase(Locale.ROOT)
                  : "";

          String operator = ctx.optional("operator").map(String.class::cast).orElse(null);
          if (operator != null) {
            operator = operator.trim();
          }

          List<String> suggestions = new ArrayList<>();
          if (prefix.isBlank() && placeholder != null && !placeholder.isBlank()) {
            suggestions.add(placeholder);
          }

          World world = resolveSuggestionWorld(ctx.sender());
          if (world == null) {
            return suggestions;
          }
          RailGraphService service = plugin.getRailGraphService();
          if (service == null) {
            return suggestions;
          }
          Optional<RailGraphService.RailGraphSnapshot> snapshotOpt = service.getSnapshot(world);
          if (snapshotOpt.isEmpty()) {
            return suggestions;
          }

          Set<String> candidates = new LinkedHashSet<>();
          for (RailNode node : snapshotOpt.get().graph().nodes()) {
            if (node == null) {
              continue;
            }
            Optional<WaypointMetadata> metaOpt = node.waypointMetadata();
            if (metaOpt.isEmpty()) {
              continue;
            }
            WaypointMetadata meta = metaOpt.get();
            if (meta.kind() != WaypointKind.STATION && meta.kind() != WaypointKind.STATION_THROAT) {
              continue;
            }
            if (operator != null
                && !operator.isBlank()
                && !meta.operator().equalsIgnoreCase(operator)) {
              continue;
            }
            String station = meta.originStation();
            if (station != null && !station.isBlank()) {
              candidates.add(station);
            }
          }

          candidates.stream()
              .filter(s -> prefix.isBlank() || s.toLowerCase(Locale.ROOT).startsWith(prefix))
              .sorted(String.CASE_INSENSITIVE_ORDER)
              .limit(SUGGESTION_LIMIT)
              .forEach(suggestions::add);
          return suggestions;
        });
  }

  private SuggestionProvider<CommandSender> stationSuggestions(String placeholder) {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String rawPrefix = input != null ? input.lastRemainingToken() : "";
          String prefix = normalizeNodeIdArg(rawPrefix).toLowerCase(Locale.ROOT);

          List<String> suggestions = new ArrayList<>();
          if (prefix.isBlank() && placeholder != null && !placeholder.isBlank()) {
            suggestions.add(placeholder);
          }

          World world = resolveSuggestionWorld(ctx.sender());
          if (world == null) {
            return suggestions;
          }
          RailGraphService service = plugin.getRailGraphService();
          if (service == null) {
            return suggestions;
          }
          Optional<RailGraphService.RailGraphSnapshot> snapshotOpt = service.getSnapshot(world);
          if (snapshotOpt.isEmpty()) {
            return suggestions;
          }

          Set<String> candidates = new LinkedHashSet<>();
          boolean wantsOperator = prefix.contains(":");
          for (RailNode node : snapshotOpt.get().graph().nodes()) {
            if (node == null) {
              continue;
            }
            Optional<WaypointMetadata> metaOpt = node.waypointMetadata();
            if (metaOpt.isEmpty()) {
              continue;
            }
            WaypointMetadata meta = metaOpt.get();
            if (meta.kind() != WaypointKind.STATION && meta.kind() != WaypointKind.STATION_THROAT) {
              continue;
            }
            String station = meta.originStation();
            if (station != null && !station.isBlank()) {
              candidates.add(station);
              if (wantsOperator) {
                candidates.add(meta.operator() + ":" + station);
              }
            }
          }

          candidates.stream()
              .filter(s -> prefix.isBlank() || s.toLowerCase(Locale.ROOT).startsWith(prefix))
              .sorted(String.CASE_INSENSITIVE_ORDER)
              .limit(SUGGESTION_LIMIT)
              .forEach(suggestions::add);
          return suggestions;
        });
  }

  private SuggestionProvider<CommandSender> ticketSuggestions(String placeholder) {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix =
              input != null ? input.lastRemainingToken().trim().toLowerCase(Locale.ROOT) : "";
          List<String> suggestions = new ArrayList<>();
          if (prefix.isBlank() && placeholder != null && !placeholder.isBlank()) {
            suggestions.add(placeholder);
          }
          EtaService service = plugin.getEtaService();
          if (service == null) {
            return suggestions;
          }
          service.suggestTicketIds().stream()
              .filter(s -> prefix.isBlank() || s.toLowerCase(Locale.ROOT).startsWith(prefix))
              .sorted(String.CASE_INSENSITIVE_ORDER)
              .limit(SUGGESTION_LIMIT)
              .forEach(suggestions::add);
          return suggestions;
        });
  }

  private SuggestionProvider<CommandSender> lineSuggestions(String placeholder) {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix =
              input != null ? input.lastRemainingToken().trim().toLowerCase(Locale.ROOT) : "";
          List<String> suggestions = new ArrayList<>();
          if (prefix.isBlank() && placeholder != null && !placeholder.isBlank()) {
            suggestions.add(placeholder);
          }
          EtaService service = plugin.getEtaService();
          java.util.Set<String> candidates = new java.util.LinkedHashSet<>();
          if (service != null) {
            candidates.addAll(service.suggestLineIds());
          }
          candidates.addAll(listLineCodesFromStorage(ctx, prefix));
          candidates.stream()
              .filter(s -> prefix.isBlank() || s.toLowerCase(Locale.ROOT).startsWith(prefix))
              .sorted(String.CASE_INSENSITIVE_ORDER)
              .limit(SUGGESTION_LIMIT)
              .forEach(suggestions::add);
          return suggestions;
        });
  }

  private java.util.Set<String> listLineCodesFromStorage(
      org.incendo.cloud.context.CommandContext<CommandSender> ctx, String prefix) {
    java.util.Set<String> candidates = new java.util.LinkedHashSet<>();
    if (ctx == null) {
      return candidates;
    }
    Optional<StorageProvider> providerOpt = providerIfReady();
    if (providerOpt.isEmpty()) {
      return candidates;
    }
    Optional<String> operatorArgOpt = ctx.optional("operator").map(String.class::cast);
    if (operatorArgOpt.isEmpty()) {
      return candidates;
    }
    String operatorArg = operatorArgOpt.get().trim();
    if (operatorArg.isBlank()) {
      return candidates;
    }
    StorageProvider provider = providerOpt.get();
    for (Company company : provider.companies().listAll()) {
      if (company == null) {
        continue;
      }
      if (!canReadCompanyNoCreateIdentity(ctx.sender(), provider, company.id())) {
        continue;
      }
      Optional<Operator> operatorOpt =
          provider.operators().findByCompanyAndCode(company.id(), operatorArg);
      if (operatorOpt.isEmpty()) {
        continue;
      }
      provider.lines().listByOperator(operatorOpt.get().id()).stream()
          .map(org.fetarute.fetaruteTCAddon.company.model.Line::code)
          .filter(java.util.Objects::nonNull)
          .map(String::trim)
          .filter(code -> !code.isBlank())
          .filter(code -> prefix.isBlank() || code.toLowerCase(Locale.ROOT).startsWith(prefix))
          .forEach(candidates::add);
      break;
    }
    return candidates;
  }

  private static SuggestionProvider<CommandSender> horizonSuggestions(String placeholder) {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix =
              input != null ? input.lastRemainingToken().trim().toLowerCase(Locale.ROOT) : "";
          List<String> suggestions = new ArrayList<>();
          if (prefix.isBlank() && placeholder != null && !placeholder.isBlank()) {
            suggestions.add(placeholder);
          }
          List<String> candidates = List.of("60", "120", "300", "600", "900", "1800", "3600");
          for (String candidate : candidates) {
            if (prefix.isBlank() || candidate.startsWith(prefix)) {
              suggestions.add(candidate);
            }
            if (suggestions.size() >= SUGGESTION_LIMIT) {
              break;
            }
          }
          return suggestions;
        });
  }

  private World resolveSuggestionWorld(CommandSender sender) {
    if (sender instanceof Player player) {
      return player.getWorld();
    }
    return null;
  }

  private static String normalizeNodeIdArg(String raw) {
    if (raw == null) {
      return "";
    }
    String trimmed = raw.trim();
    if (trimmed.length() >= 2) {
      char first = trimmed.charAt(0);
      char last = trimmed.charAt(trimmed.length() - 1);
      if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
        return trimmed.substring(1, trimmed.length() - 1).trim();
      }
    }
    return trimmed;
  }
}
