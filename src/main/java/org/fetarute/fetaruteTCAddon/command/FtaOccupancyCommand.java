package org.fetarute.fetaruteTCAddon.command;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPath;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPathFinder;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResourceResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceKind;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.parser.standard.IntegerParser;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.SuggestionProvider;

/**
 * /fta occupancy 命令注册。
 *
 * <p>用于查看与清理调度占用状态，方便运维排障。
 */
public final class FtaOccupancyCommand {

  private static final int DEFAULT_LIMIT = 50;

  private final FetaruteTCAddon plugin;

  public FtaOccupancyCommand(FetaruteTCAddon plugin) {
    this.plugin = plugin;
  }

  public void register(CommandManager<CommandSender> manager) {
    var nodeIdSuggestions = graphNodeIdSuggestions("\"<nodeId>\"");
    var kindSuggestions = CommandSuggestionProviders.enumValues(ResourceKind.class, "<kind>");
    var limitSuggestions = CommandSuggestionProviders.<CommandSender>placeholder("<limit>");

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("occupancy")
            .permission("fetarute.occupancy")
            .handler(ctx -> sendHelp(ctx.sender())));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("occupancy")
            .literal("dump")
            .permission("fetarute.occupancy")
            .optional("limit", IntegerParser.integerParser(1, 200), limitSuggestions)
            .handler(
                ctx -> {
                  int limit = ctx.<Integer>optional("limit").orElse(DEFAULT_LIMIT);
                  dumpClaims(ctx.sender(), limit);
                }));

    registerDebugCommands(manager, nodeIdSuggestions);

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("occupancy")
            .literal("release")
            .permission("fetarute.occupancy")
            .required(
                "train",
                StringParser.stringParser(),
                CommandSuggestionProviders.<CommandSender>placeholder("<train>"))
            .handler(
                ctx -> {
                  String trainName = ctx.get("train");
                  OccupancyManager occupancy = plugin.getOccupancyManager();
                  if (occupancy == null) {
                    sendNotReady(ctx.sender());
                    return;
                  }
                  int removed = occupancy.releaseByTrain(trainName);
                  LocaleManager locale = plugin.getLocaleManager();
                  ctx.sender()
                      .sendMessage(
                          locale.component(
                              "command.occupancy.release.success",
                              Map.of("train", trainName, "count", String.valueOf(removed))));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("occupancy")
            .literal("release-resource")
            .permission("fetarute.occupancy")
            .required("kind", StringParser.stringParser(), kindSuggestions)
            .required(
                "key",
                StringParser.greedyStringParser(),
                CommandSuggestionProviders.<CommandSender>placeholder("<key>"))
            .handler(
                ctx -> {
                  OccupancyManager occupancy = plugin.getOccupancyManager();
                  if (occupancy == null) {
                    sendNotReady(ctx.sender());
                    return;
                  }
                  String kindRaw = ctx.get("kind");
                  String key = ctx.get("key");
                  ResourceKind kind;
                  try {
                    kind = ResourceKind.valueOf(kindRaw.toUpperCase(Locale.ROOT));
                  } catch (IllegalArgumentException ex) {
                    LocaleManager locale = plugin.getLocaleManager();
                    ctx.sender()
                        .sendMessage(
                            locale.component(
                                "command.occupancy.invalid-kind", Map.of("kind", kindRaw)));
                    return;
                  }
                  boolean removed =
                      occupancy.releaseResource(new OccupancyResource(kind, key), Optional.empty());
                  LocaleManager locale = plugin.getLocaleManager();
                  String messageKey =
                      removed
                          ? "command.occupancy.release-resource.success"
                          : "command.occupancy.release-resource.none";
                  ctx.sender()
                      .sendMessage(
                          locale.component(messageKey, Map.of("kind", kind.name(), "key", key)));
                }));
  }

  private void sendHelp(CommandSender sender) {
    LocaleManager locale = plugin.getLocaleManager();
    sender.sendMessage(locale.component("command.occupancy.help.header"));
    sendHelpEntry(
        sender,
        locale.component("command.occupancy.help.entry-dump"),
        ClickEvent.suggestCommand("/fta occupancy dump "),
        locale.component("command.occupancy.help.hover-dump"));
    sendHelpEntry(
        sender,
        locale.component("command.occupancy.help.entry-release"),
        ClickEvent.suggestCommand("/fta occupancy release "),
        locale.component("command.occupancy.help.hover-release"));
    sendHelpEntry(
        sender,
        locale.component("command.occupancy.help.entry-release-resource"),
        ClickEvent.suggestCommand("/fta occupancy release-resource "),
        locale.component("command.occupancy.help.hover-release-resource"));
    sendHelpEntry(
        sender,
        locale.component("command.occupancy.help.entry-debug-edge"),
        ClickEvent.suggestCommand("/fta occupancy debug acquire edge "),
        locale.component("command.occupancy.help.hover-debug-edge"));
    sendHelpEntry(
        sender,
        locale.component("command.occupancy.help.entry-debug-path"),
        ClickEvent.suggestCommand("/fta occupancy debug acquire path "),
        locale.component("command.occupancy.help.hover-debug-path"));
  }

  private void sendHelpEntry(
      CommandSender sender, Component text, ClickEvent clickEvent, Component hoverText) {
    sender.sendMessage(text.clickEvent(clickEvent).hoverEvent(HoverEvent.showText(hoverText)));
  }

  private void sendNotReady(CommandSender sender) {
    LocaleManager locale = plugin.getLocaleManager();
    sender.sendMessage(locale.component("command.occupancy.not-ready"));
  }

  private void dumpClaims(CommandSender sender, int limit) {
    OccupancyManager occupancy = plugin.getOccupancyManager();
    if (occupancy == null) {
      sendNotReady(sender);
      return;
    }
    LocaleManager locale = plugin.getLocaleManager();
    List<OccupancyClaim> claims =
        occupancy.snapshotClaims().stream()
            .sorted(Comparator.comparing(claim -> claim.resource().toString()))
            .toList();
    sender.sendMessage(
        locale.component(
            "command.occupancy.dump.header", Map.of("count", String.valueOf(claims.size()))));
    int count = 0;
    for (OccupancyClaim claim : claims) {
      if (count >= limit) {
        sender.sendMessage(
            locale.component(
                "command.occupancy.dump.truncated", Map.of("limit", String.valueOf(limit))));
        break;
      }
      sender.sendMessage(
          locale.component(
              "command.occupancy.dump.entry",
              Map.of(
                  "resource",
                  claim.resource().toString(),
                  "train",
                  claim.trainName(),
                  "since",
                  claim.acquiredAt().toString(),
                  "headway",
                  claim.headway().toString())));
      count++;
    }
  }

  /** 注册 /fta occupancy debug 子命令。 */
  private void registerDebugCommands(
      CommandManager<CommandSender> manager, SuggestionProvider<CommandSender> nodeIdSuggestions) {
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("occupancy")
            .literal("debug")
            .literal("acquire")
            .literal("edge")
            .permission("fetarute.occupancy.debug")
            .required("from", StringParser.quotedStringParser(), nodeIdSuggestions)
            .required("to", StringParser.quotedStringParser(), nodeIdSuggestions)
            .handler(
                ctx -> {
                  String fromRaw = ctx.get("from");
                  String toRaw = ctx.get("to");
                  handleEdgeDecision(ctx.sender(), fromRaw, toRaw, true);
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("occupancy")
            .literal("debug")
            .literal("can")
            .literal("edge")
            .permission("fetarute.occupancy.debug")
            .required("from", StringParser.quotedStringParser(), nodeIdSuggestions)
            .required("to", StringParser.quotedStringParser(), nodeIdSuggestions)
            .handler(
                ctx -> {
                  String fromRaw = ctx.get("from");
                  String toRaw = ctx.get("to");
                  handleEdgeDecision(ctx.sender(), fromRaw, toRaw, false);
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("occupancy")
            .literal("debug")
            .literal("acquire")
            .literal("path")
            .permission("fetarute.occupancy.debug")
            .required("from", StringParser.quotedStringParser(), nodeIdSuggestions)
            .required("to", StringParser.quotedStringParser(), nodeIdSuggestions)
            .handler(
                ctx -> {
                  String fromRaw = ctx.get("from");
                  String toRaw = ctx.get("to");
                  handlePathDecision(ctx.sender(), fromRaw, toRaw, true);
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("occupancy")
            .literal("debug")
            .literal("can")
            .literal("path")
            .permission("fetarute.occupancy.debug")
            .required("from", StringParser.quotedStringParser(), nodeIdSuggestions)
            .required("to", StringParser.quotedStringParser(), nodeIdSuggestions)
            .handler(
                ctx -> {
                  String fromRaw = ctx.get("from");
                  String toRaw = ctx.get("to");
                  handlePathDecision(ctx.sender(), fromRaw, toRaw, false);
                }));
  }

  /** 调试：对单条边执行 can/acquire 判定。 */
  private void handleEdgeDecision(
      CommandSender sender, String fromRaw, String toRaw, boolean acquire) {
    LocaleManager locale = plugin.getLocaleManager();
    Optional<RailGraph> graphOpt = resolveGraph(sender);
    if (graphOpt.isEmpty()) {
      return;
    }
    NodeId from = NodeId.of(fromRaw);
    NodeId to = NodeId.of(toRaw);
    Optional<RailEdge> edgeOpt = findEdge(graphOpt.get(), from, to);
    if (edgeOpt.isEmpty()) {
      sender.sendMessage(
          locale.component(
              "command.occupancy.debug.edge-not-found",
              Map.of("from", from.value(), "to", to.value())));
      return;
    }
    List<OccupancyResource> resources =
        OccupancyResourceResolver.resourcesForEdge(graphOpt.get(), edgeOpt.get());
    String trainName = resolveTrainName(sender, acquire);
    OccupancyRequest request = buildRequest(trainName, resources);
    handleDecision(sender, request, acquire, "edge", resources.size());
  }

  /** 调试：对最短路路径执行 can/acquire 判定。 */
  private void handlePathDecision(
      CommandSender sender, String fromRaw, String toRaw, boolean acquire) {
    LocaleManager locale = plugin.getLocaleManager();
    Optional<RailGraph> graphOpt = resolveGraph(sender);
    if (graphOpt.isEmpty()) {
      return;
    }
    NodeId from = NodeId.of(fromRaw);
    NodeId to = NodeId.of(toRaw);
    RailGraphPathFinder finder = new RailGraphPathFinder();
    Optional<RailGraphPath> pathOpt =
        finder.shortestPath(
            graphOpt.get(), from, to, RailGraphPathFinder.Options.shortestDistance());
    if (pathOpt.isEmpty()) {
      sender.sendMessage(
          locale.component(
              "command.occupancy.debug.path-not-found",
              Map.of("from", from.value(), "to", to.value())));
      return;
    }
    RailGraphPath path = pathOpt.get();
    List<OccupancyResource> resources =
        path.edges().stream()
            .flatMap(
                edge -> OccupancyResourceResolver.resourcesForEdge(graphOpt.get(), edge).stream())
            .distinct()
            .toList();
    if (resources.isEmpty()) {
      sender.sendMessage(locale.component("command.occupancy.debug.no-resources"));
      return;
    }
    String trainName = resolveTrainName(sender, acquire);
    OccupancyRequest request = buildRequest(trainName, resources);
    handleDecision(sender, request, acquire, "path", path.edges().size());
  }

  /** 输出占用判定结果（允许/最早时间/信号/阻塞数）。 */
  private void handleDecision(
      CommandSender sender,
      OccupancyRequest request,
      boolean acquire,
      String scope,
      int edgeCount) {
    LocaleManager locale = plugin.getLocaleManager();
    OccupancyManager occupancy = plugin.getOccupancyManager();
    if (occupancy == null) {
      sendNotReady(sender);
      return;
    }
    var decision = acquire ? occupancy.acquire(request) : occupancy.canEnter(request);
    SignalAspect signal = decision.signal();
    sender.sendMessage(
        locale.component(
            "command.occupancy.debug.decision",
            Map.of(
                "scope",
                scope,
                "edges",
                String.valueOf(edgeCount),
                "allowed",
                String.valueOf(decision.allowed()),
                "earliest",
                decision.earliestTime().toString(),
                "signal",
                signal.name(),
                "blockers",
                String.valueOf(decision.blockers().size()))));
  }

  /** 构造调试占用请求。 */
  private OccupancyRequest buildRequest(String trainName, List<OccupancyResource> resources) {
    Instant now = Instant.now();
    return new OccupancyRequest(trainName, Optional.empty(), now, resources);
  }

  /** 生成调试用 trainName，can 与 acquire 使用不同前缀避免误判自占用。 */
  private String resolveTrainName(CommandSender sender, boolean acquire) {
    String name = sender.getName();
    if (acquire) {
      return "debug:" + name;
    }
    return "probe:" + name;
  }

  private Optional<RailGraph> resolveGraph(CommandSender sender) {
    LocaleManager locale = plugin.getLocaleManager();
    if (!(sender instanceof Player player)) {
      sender.sendMessage(locale.component("command.occupancy.debug.player-only"));
      return Optional.empty();
    }
    World world = player.getWorld();
    RailGraphService service = plugin.getRailGraphService();
    if (service == null) {
      sender.sendMessage(locale.component("command.occupancy.debug.graph-not-ready"));
      return Optional.empty();
    }
    Optional<RailGraphService.RailGraphSnapshot> snapshotOpt = service.getSnapshot(world);
    if (snapshotOpt.isEmpty()) {
      sender.sendMessage(locale.component("command.occupancy.debug.graph-not-ready"));
      return Optional.empty();
    }
    return Optional.of(snapshotOpt.get().graph());
  }

  private Optional<RailEdge> findEdge(RailGraph graph, NodeId from, NodeId to) {
    for (RailEdge edge : graph.edgesFrom(from)) {
      if (edge.from().equals(from) && edge.to().equals(to)) {
        return Optional.of(edge);
      }
      if (edge.from().equals(to) && edge.to().equals(from)) {
        return Optional.of(edge);
      }
    }
    return Optional.empty();
  }

  private SuggestionProvider<CommandSender> graphNodeIdSuggestions(String placeholder) {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = input != null ? input.lastRemainingToken() : "";
          prefix = prefix.trim();
          if (!prefix.isEmpty() && (prefix.charAt(0) == '"' || prefix.charAt(0) == '\'')) {
            prefix = prefix.substring(1);
          }
          if (!prefix.isEmpty()
              && (prefix.charAt(prefix.length() - 1) == '"'
                  || prefix.charAt(prefix.length() - 1) == '\'')) {
            prefix = prefix.substring(0, prefix.length() - 1);
          }
          prefix = prefix.trim().toLowerCase(Locale.ROOT);

          List<String> suggestions = new java.util.ArrayList<>();
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

          List<String> nodeIds = new java.util.ArrayList<>();
          for (var node : snapshotOpt.get().graph().nodes()) {
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
          nodeIds.stream().distinct().sorted().limit(20).forEach(suggestions::add);
          return suggestions;
        });
  }

  private World resolveSuggestionWorld(CommandSender sender) {
    if (sender instanceof Player player) {
      return player.getWorld();
    }
    return null;
  }
}
