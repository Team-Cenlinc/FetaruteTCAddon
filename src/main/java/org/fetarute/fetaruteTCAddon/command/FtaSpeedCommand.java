package org.fetarute.fetaruteTCAddon.command;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.control.RailControlParsers;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.control.RailSpeed;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.control.SectionSpeedOverrideWriter;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.control.SectionSpeedService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.control.SectionSpeedService.SectionSpeedChange;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.control.SectionSpeedService.SectionSpeedPlan;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.control.SpeedSettingStickConfig;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.control.SpeedSettingStickListener;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeOverrideRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.parser.standard.IntegerParser;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.SuggestionProvider;

/**
 * /fta speed 命令：面向运营的速度控制入口。
 *
 * <p>第一阶段仅实现 {@code section} 子命令：把业务区间展开为当前调度图最短路径，并复用 {@code rail_edge_overrides}
 * 写入长期/临时限速。底层运行时仍只消费 edge override，不引入第二套速度状态。
 */
public final class FtaSpeedCommand {

  private static final int PAGE_SIZE = 10;

  private final FetaruteTCAddon plugin;
  private final SectionSpeedService sectionSpeedService;
  private final SectionSpeedOverrideWriter overrideWriter;

  public FtaSpeedCommand(FetaruteTCAddon plugin) {
    this(plugin, new SectionSpeedService(), new SectionSpeedOverrideWriter());
  }

  FtaSpeedCommand(
      FetaruteTCAddon plugin,
      SectionSpeedService sectionSpeedService,
      SectionSpeedOverrideWriter overrideWriter) {
    this.plugin = plugin;
    this.sectionSpeedService = sectionSpeedService;
    this.overrideWriter = overrideWriter;
  }

  /** 注册 /fta speed section 相关命令与补全。 */
  public void register(CommandManager<CommandSender> manager) {
    SuggestionProvider<CommandSender> nodeSuggestions = nodeIdSuggestions();
    SuggestionProvider<CommandSender> speedSuggestions = speedSuggestions("<speed>");
    SuggestionProvider<CommandSender> ttlSuggestions = ttlSuggestions("<ttl>");
    SuggestionProvider<CommandSender> pageSuggestions =
        CommandSuggestionProviders.placeholder("<page>");

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("speed")
            .permission("fetarute.speed")
            .handler(ctx -> sendHelp(ctx.sender())));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("speed")
            .literal("section")
            .permission("fetarute.speed")
            .handler(ctx -> sendHelp(ctx.sender())));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("speed")
            .literal("section")
            .literal("preview")
            .permission("fetarute.speed")
            .required("from", StringParser.quotedStringParser(), nodeSuggestions)
            .required("to", StringParser.quotedStringParser(), nodeSuggestions)
            .optional("speed", StringParser.stringParser(), speedSuggestions)
            .optional("ttl", StringParser.stringParser(), ttlSuggestions)
            .handler(
                ctx ->
                    previewSection(
                        ctx.sender(),
                        normalizeNodeIdArg(ctx.get("from")),
                        normalizeNodeIdArg(ctx.get("to")),
                        ctx.<String>optional("speed"),
                        ctx.<String>optional("ttl"))));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("speed")
            .literal("section")
            .literal("set")
            .permission("fetarute.speed")
            .required("from", StringParser.quotedStringParser(), nodeSuggestions)
            .required("to", StringParser.quotedStringParser(), nodeSuggestions)
            .required("speed", StringParser.stringParser(), speedSuggestions)
            .optional("ttl", StringParser.stringParser(), ttlSuggestions)
            .handler(
                ctx ->
                    setSection(
                        ctx.sender(),
                        normalizeNodeIdArg(ctx.get("from")),
                        normalizeNodeIdArg(ctx.get("to")),
                        ctx.get("speed"),
                        ctx.<String>optional("ttl"))));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("speed")
            .literal("section")
            .literal("clear")
            .permission("fetarute.speed")
            .required("from", StringParser.quotedStringParser(), nodeSuggestions)
            .required("to", StringParser.quotedStringParser(), nodeSuggestions)
            .handler(
                ctx ->
                    clearSection(
                        ctx.sender(),
                        normalizeNodeIdArg(ctx.get("from")),
                        normalizeNodeIdArg(ctx.get("to")))));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("speed")
            .literal("section")
            .literal("list")
            .permission("fetarute.speed")
            .optional("page", IntegerParser.integerParser(1), pageSuggestions)
            .handler(ctx -> listSections(ctx.sender(), Optional.empty(), ctx.optional("page"))));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("speed")
            .literal("section")
            .literal("list")
            .literal("node")
            .permission("fetarute.speed")
            .required("node", StringParser.quotedStringParser(), nodeSuggestions)
            .optional("page", IntegerParser.integerParser(1), pageSuggestions)
            .handler(
                ctx ->
                    listSections(
                        ctx.sender(),
                        Optional.of(NodeId.of(normalizeNodeIdArg(ctx.get("node")))),
                        ctx.optional("page"))));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("speed")
            .literal("stick")
            .permission("fetarute.speed.stick")
            .handler(ctx -> sendHelp(ctx.sender())));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("speed")
            .literal("stick")
            .literal("give")
            .permission("fetarute.speed.stick")
            .required("speed", StringParser.stringParser(), speedSuggestions)
            .optional("ttl", StringParser.stringParser(), ttlSuggestions)
            .handler(
                ctx ->
                    giveSpeedStick(ctx.sender(), ctx.get("speed"), ctx.<String>optional("ttl"))));
  }

  private void sendHelp(CommandSender sender) {
    LocaleManager locale = plugin.getLocaleManager();
    sender.sendMessage(locale.component("command.speed.section.help.header"));
    sendHelpEntry(
        sender,
        locale.component("command.speed.section.help.entry-preview"),
        locale.component("command.speed.section.help.hover-preview"),
        "/fta speed section preview ");
    sendHelpEntry(
        sender,
        locale.component("command.speed.section.help.entry-set"),
        locale.component("command.speed.section.help.hover-set"),
        "/fta speed section set ");
    sendHelpEntry(
        sender,
        locale.component("command.speed.section.help.entry-clear"),
        locale.component("command.speed.section.help.hover-clear"),
        "/fta speed section clear ");
    sendHelpEntry(
        sender,
        locale.component("command.speed.section.help.entry-list"),
        locale.component("command.speed.section.help.hover-list"),
        "/fta speed section list ");
    sendHelpEntry(
        sender,
        locale.component("command.speed.section.help.entry-stick"),
        locale.component("command.speed.section.help.hover-stick"),
        "/fta speed stick give ");
  }

  private void sendHelpEntry(
      CommandSender sender, Component text, Component hoverText, String suggestCommand) {
    sender.sendMessage(
        text.clickEvent(ClickEvent.suggestCommand(suggestCommand))
            .hoverEvent(HoverEvent.showText(hoverText)));
  }

  private void previewSection(
      CommandSender sender,
      String fromRaw,
      String toRaw,
      Optional<String> speedRaw,
      Optional<String> ttlRaw) {
    LocaleManager locale = plugin.getLocaleManager();
    Optional<ParsedSpeedMode> modeOpt = parseOptionalSpeedMode(sender, locale, speedRaw, ttlRaw);
    if (modeOpt.isEmpty()) {
      return;
    }
    Optional<ResolvedSection> resolvedOpt = resolveSection(sender, fromRaw, toRaw);
    if (resolvedOpt.isEmpty()) {
      return;
    }
    ResolvedSection resolved = resolvedOpt.get();
    ParsedSpeedMode mode = modeOpt.get();
    Map<String, String> values =
        new HashMap<>(
            Map.of(
                "from",
                resolved.plan().from().value(),
                "to",
                resolved.plan().to().value(),
                "edges",
                Integer.toString(resolved.plan().edgeIds().size()),
                "nodes",
                Integer.toString(resolved.plan().nodes().size()),
                "distance_blocks",
                Long.toString(resolved.plan().totalLengthBlocks()),
                "path",
                formatPath(resolved.plan())));
    if (mode.speed().isPresent()) {
      values.put("speed", mode.speed().get().formatWithAllUnits());
      values.put("ttl", mode.ttl().map(this::formatDuration).orElse("-"));
      sender.sendMessage(locale.component("command.speed.section.preview.with-speed", values));
      sender.sendMessage(buildPreviewActions(resolved.plan(), speedRaw.orElse(""), ttlRaw));
    } else {
      sender.sendMessage(locale.component("command.speed.section.preview.path", values));
      sender.sendMessage(buildPreviewActions(resolved.plan(), "", Optional.empty()));
    }
    sendPreviewEdgeDetails(sender, locale, resolved.plan());
  }

  private Component buildPreviewActions(
      SectionSpeedPlan plan, String speedRaw, Optional<String> ttlRaw) {
    String from = CommandUx.quoteCommandArgument(plan.from().value());
    String to = CommandUx.quoteCommandArgument(plan.to().value());
    Component apply =
        speedRaw == null || speedRaw.isBlank()
            ? null
            : CommandUx.suggestAction(
                "[应用]",
                "/fta speed section set "
                    + from
                    + " "
                    + to
                    + " "
                    + speedRaw.trim()
                    + ttlRaw.map(ttl -> " " + ttl.trim()).orElse(""),
                "填充 set 命令；确认后再回车执行");
    Component clear =
        CommandUx.suggestAction(
            "[清除]", "/fta speed section clear " + from + " " + to, "填充 clear 命令；确认后再回车执行");
    Component path =
        CommandUx.runAction("[详情]", "/fta graph edge get path " + from + " " + to, "查看这段最短路上的区间详情");
    return Component.text("  ").append(CommandUx.actions(apply, clear, path));
  }

  private void sendPreviewEdgeDetails(
      CommandSender sender, LocaleManager locale, SectionSpeedPlan plan) {
    List<EdgeId> edgeIds = plan.edgeIds();
    for (int i = 0; i < edgeIds.size(); i++) {
      EdgeId edgeId = edgeIds.get(i);
      String a = CommandUx.quoteCommandArgument(edgeId.a().value());
      String b = CommandUx.quoteCommandArgument(edgeId.b().value());
      sender.sendMessage(
          locale
              .component(
                  "command.speed.section.preview.edge",
                  Map.of(
                      "seq",
                      Integer.toString(i + 1),
                      "a",
                      edgeId.a().value(),
                      "b",
                      edgeId.b().value()))
              .append(Component.space())
              .append(
                  CommandUx.runAction(
                      "[详情]", "/fta graph edge get " + a + " " + b, "查看该 edge 的限速与封锁状态")));
    }
  }

  private void setSection(
      CommandSender sender,
      String fromRaw,
      String toRaw,
      String speedRaw,
      Optional<String> ttlRaw) {
    LocaleManager locale = plugin.getLocaleManager();
    Optional<ParsedSpeedMode> modeOpt =
        parseRequiredSpeedMode(sender, locale, Optional.ofNullable(speedRaw), ttlRaw);
    if (modeOpt.isEmpty()) {
      return;
    }
    Optional<ResolvedSection> resolvedOpt = resolveSection(sender, fromRaw, toRaw);
    if (resolvedOpt.isEmpty()) {
      return;
    }
    Optional<StorageProvider> providerOpt = CommandStorageProviders.readyProvider(sender, plugin);
    if (providerOpt.isEmpty()) {
      return;
    }

    StorageProvider provider = providerOpt.get();
    ResolvedSection resolved = resolvedOpt.get();
    ParsedSpeedMode mode = modeOpt.get();
    Instant now = Instant.now();
    Optional<Instant> tempUntil = mode.ttl().map(now::plus);
    SectionSpeedChange change =
        sectionSpeedService.buildSetChange(
            resolved.worldId(),
            resolved.plan(),
            mode.speed().orElseThrow(),
            tempUntil,
            overrideWriter.loadExisting(
                provider,
                plugin.getRailGraphService(),
                resolved.worldId(),
                resolved.plan().edgeIds()),
            now);
    if (!applyChange(sender, provider, resolved.worldId(), change)) {
      return;
    }
    Map<String, String> values =
        sectionValues(
            resolved.plan(), mode.speed().orElseThrow(), mode.ttl(), change.touchedEdges());
    sender.sendMessage(
        locale.component(
            mode.ttl().isPresent()
                ? "command.speed.section.set.temp-success"
                : "command.speed.section.set.success",
            values));
  }

  private void clearSection(CommandSender sender, String fromRaw, String toRaw) {
    LocaleManager locale = plugin.getLocaleManager();
    Optional<ResolvedSection> resolvedOpt = resolveSection(sender, fromRaw, toRaw);
    if (resolvedOpt.isEmpty()) {
      return;
    }
    Optional<StorageProvider> providerOpt = CommandStorageProviders.readyProvider(sender, plugin);
    if (providerOpt.isEmpty()) {
      return;
    }

    StorageProvider provider = providerOpt.get();
    ResolvedSection resolved = resolvedOpt.get();
    SectionSpeedChange change =
        sectionSpeedService.buildClearChange(
            resolved.worldId(),
            resolved.plan(),
            overrideWriter.loadExisting(
                provider,
                plugin.getRailGraphService(),
                resolved.worldId(),
                resolved.plan().edgeIds()),
            Instant.now());
    if (change.touchedEdges() == 0) {
      sender.sendMessage(
          locale.component(
              "command.speed.section.clear.none",
              Map.of("from", resolved.plan().from().value(), "to", resolved.plan().to().value())));
      return;
    }
    if (!applyChange(sender, provider, resolved.worldId(), change)) {
      return;
    }
    sender.sendMessage(
        locale.component(
            "command.speed.section.clear.success",
            Map.of(
                "from",
                resolved.plan().from().value(),
                "to",
                resolved.plan().to().value(),
                "edges",
                Integer.toString(change.touchedEdges()))));
  }

  private void listSections(
      CommandSender sender, Optional<NodeId> nodeFilter, Optional<Integer> pageOpt) {
    LocaleManager locale = plugin.getLocaleManager();
    Optional<World> worldOpt = resolveWorld(sender);
    if (worldOpt.isEmpty()) {
      sender.sendMessage(locale.component("command.speed.section.no-world"));
      return;
    }
    Optional<StorageProvider> providerOpt = CommandStorageProviders.readyProvider(sender, plugin);
    if (providerOpt.isEmpty()) {
      return;
    }
    World world = worldOpt.get();
    Instant now = Instant.now();
    List<RailEdgeOverrideRecord> records =
        providerOpt.get().railEdgeOverrides().listByWorld(world.getUID()).stream()
            .filter(record -> hasSpeed(record, now))
            .filter(
                record -> nodeFilter.map(node -> edgeContains(record.edgeId(), node)).orElse(true))
            .sorted(
                Comparator.comparing(
                    record -> record.edgeId().a().value() + "|" + record.edgeId().b().value()))
            .toList();
    if (records.isEmpty()) {
      sender.sendMessage(locale.component("command.speed.section.list.empty"));
      return;
    }
    int page = pageOpt.orElse(1);
    int pages = Math.max(1, (records.size() + PAGE_SIZE - 1) / PAGE_SIZE);
    int clampedPage = Math.min(page, pages);
    int fromIndex = (clampedPage - 1) * PAGE_SIZE;
    int toIndex = Math.min(records.size(), fromIndex + PAGE_SIZE);
    sender.sendMessage(
        locale.component(
            "command.speed.section.list.header",
            Map.of(
                "world",
                world.getName(),
                "count",
                Integer.toString(records.size()),
                "page",
                Integer.toString(clampedPage),
                "pages",
                Integer.toString(pages))));
    for (RailEdgeOverrideRecord record : records.subList(fromIndex, toIndex)) {
      sender.sendMessage(
          locale
              .component("command.speed.section.list.entry", listValues(record, now))
              .append(Component.space())
              .append(buildListActions(record)));
    }
    if (pages > 1) {
      sender.sendMessage(buildListNavigation(nodeFilter, clampedPage, pages));
    }
  }

  private void giveSpeedStick(CommandSender sender, String speedRaw, Optional<String> ttlRaw) {
    LocaleManager locale = plugin.getLocaleManager();
    if (!(sender instanceof Player player)) {
      sender.sendMessage(locale.component("command.speed.stick.player-only"));
      return;
    }
    Optional<RailSpeed> speedOpt =
        Optional.ofNullable(speedRaw).map(String::trim).flatMap(RailControlParsers::parseSpeed);
    if (speedOpt.isEmpty()) {
      sender.sendMessage(
          locale.component(
              "command.speed.section.invalid-speed", Map.of("raw", String.valueOf(speedRaw))));
      return;
    }
    Optional<Duration> ttl = Optional.empty();
    if (ttlRaw.isPresent()) {
      ttl = RailControlParsers.parseTtl(ttlRaw.get().trim());
      if (ttl.isEmpty()) {
        sender.sendMessage(
            locale.component("command.speed.section.invalid-ttl", Map.of("raw", ttlRaw.get())));
        return;
      }
    }
    SpeedSettingStickConfig config = new SpeedSettingStickConfig(speedOpt.get(), ttl);
    Optional<SpeedSettingStickListener> listenerOpt = plugin.getSpeedSettingStickListener();
    if (listenerOpt.isEmpty()) {
      sender.sendMessage(locale.component("command.speed.stick.not-ready"));
      return;
    }
    Map<Integer, org.bukkit.inventory.ItemStack> leftovers =
        player.getInventory().addItem(listenerOpt.get().createStickItem(config));
    if (!leftovers.isEmpty()) {
      leftovers
          .values()
          .forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
      sender.sendMessage(locale.component("command.speed.stick.give.dropped"));
    }
    sender.sendMessage(
        locale.component(
            "command.speed.stick.give.success",
            Map.of("speed", config.speedText(), "ttl", config.ttlText())));
  }

  private Component buildListActions(RailEdgeOverrideRecord record) {
    String a = CommandUx.quoteCommandArgument(record.edgeId().a().value());
    String b = CommandUx.quoteCommandArgument(record.edgeId().b().value());
    return CommandUx.actions(
        CommandUx.runAction("[详情]", "/fta graph edge get " + a + " " + b, "查看该 edge 的当前控制状态"),
        CommandUx.suggestAction(
            "[清除]", "/fta speed section clear " + a + " " + b, "填充 clear 命令；该操作只在你回车后执行"));
  }

  private Component buildListNavigation(Optional<NodeId> nodeFilter, int page, int pages) {
    String prefix =
        nodeFilter
            .map(
                node ->
                    "/fta speed section list node "
                        + CommandUx.quoteCommandArgument(node.value())
                        + " ")
            .orElse("/fta speed section list ");
    Component prev =
        page > 1
            ? CommandUx.runAction("[上一页]", prefix + (page - 1), "查看上一页")
            : Component.text("[上一页]");
    Component next =
        page < pages
            ? CommandUx.runAction("[下一页]", prefix + (page + 1), "查看下一页")
            : Component.text("[下一页]");
    return Component.text("  ").append(CommandUx.actions(prev, next));
  }

  private Optional<ResolvedSection> resolveSection(
      CommandSender sender, String fromRaw, String toRaw) {
    LocaleManager locale = plugin.getLocaleManager();
    RailGraphService service = plugin.getRailGraphService();
    if (service == null) {
      sender.sendMessage(locale.component("command.graph.info.missing"));
      return Optional.empty();
    }
    NodeId from = NodeId.of(fromRaw);
    NodeId to = NodeId.of(toRaw);
    List<UUID> candidates = new ArrayList<>();
    if (sender instanceof Player player) {
      candidates.add(player.getWorld().getUID());
    }
    service.findWorldIdForConnectedPair(from, to).ifPresent(candidates::add);
    service.snapshotAll().keySet().stream().sorted().forEach(candidates::add);

    for (UUID worldId : candidates.stream().distinct().toList()) {
      Optional<RailGraphService.RailGraphSnapshot> snapshotOpt = service.getSnapshot(worldId);
      if (snapshotOpt.isEmpty()) {
        continue;
      }
      RailGraph graph = snapshotOpt.get().graph();
      Optional<SectionSpeedPlan> planOpt = sectionSpeedService.plan(graph, from, to);
      if (planOpt.isPresent() && !planOpt.get().edgeIds().isEmpty()) {
        return Optional.of(new ResolvedSection(worldId, planOpt.get()));
      }
    }

    if (service.snapshotCount() == 0) {
      sender.sendMessage(locale.component("command.graph.info.missing"));
      return Optional.empty();
    }
    sender.sendMessage(
        locale.component(
            "command.speed.section.unreachable", Map.of("from", from.value(), "to", to.value())));
    return Optional.empty();
  }

  private boolean applyChange(
      CommandSender sender, StorageProvider provider, UUID worldId, SectionSpeedChange change) {
    LocaleManager locale = plugin.getLocaleManager();
    try {
      overrideWriter.apply(provider, plugin.getRailGraphService(), worldId, change);
      return true;
    } catch (Exception ex) {
      sender.sendMessage(
          locale.component(
              "command.speed.section.storage-failed",
              Map.of("error", ex.getMessage() != null ? ex.getMessage() : "")));
      return false;
    }
  }

  private Optional<ParsedSpeedMode> parseOptionalSpeedMode(
      CommandSender sender,
      LocaleManager locale,
      Optional<String> speedRaw,
      Optional<String> ttlRaw) {
    if (speedRaw.isEmpty()) {
      return Optional.of(new ParsedSpeedMode(Optional.empty(), Optional.empty()));
    }
    return parseRequiredSpeedMode(sender, locale, speedRaw, ttlRaw);
  }

  private Optional<ParsedSpeedMode> parseRequiredSpeedMode(
      CommandSender sender,
      LocaleManager locale,
      Optional<String> speedRaw,
      Optional<String> ttlRaw) {
    Optional<RailSpeed> speed = speedRaw.flatMap(raw -> RailControlParsers.parseSpeed(raw.trim()));
    if (speed.isEmpty()) {
      sender.sendMessage(
          locale.component(
              "command.speed.section.invalid-speed", Map.of("raw", speedRaw.orElse(""))));
      return Optional.empty();
    }
    Optional<Duration> ttl = Optional.empty();
    if (ttlRaw.isPresent()) {
      ttl = RailControlParsers.parseTtl(ttlRaw.get().trim());
      if (ttl.isEmpty()) {
        sender.sendMessage(
            locale.component(
                "command.speed.section.invalid-ttl", Map.of("raw", ttlRaw.orElse(""))));
        return Optional.empty();
      }
    }
    return Optional.of(new ParsedSpeedMode(speed, ttl));
  }

  private Optional<World> resolveWorld(CommandSender sender) {
    if (sender instanceof Player player) {
      return Optional.of(player.getWorld());
    }
    List<World> worlds = plugin.getServer().getWorlds();
    return worlds.isEmpty() ? Optional.empty() : Optional.of(worlds.get(0));
  }

  private SuggestionProvider<CommandSender> nodeIdSuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = input != null ? input.lastRemainingToken() : "";
          String normalized = normalizeNodeIdArg(prefix).toLowerCase(Locale.ROOT);
          List<String> suggestions = new ArrayList<>();
          if (normalized.isBlank()) {
            suggestions.add("\"<nodeId>\"");
          }
          Optional<World> worldOpt = resolveWorld(ctx.sender());
          RailGraphService service = plugin.getRailGraphService();
          if (worldOpt.isEmpty() || service == null) {
            return suggestions;
          }
          Optional<RailGraphService.RailGraphSnapshot> snapshotOpt =
              service.getSnapshot(worldOpt.get());
          if (snapshotOpt.isEmpty()) {
            return suggestions;
          }
          for (RailNode node : snapshotOpt.get().graph().nodes()) {
            if (node == null || node.id() == null) {
              continue;
            }
            String raw = node.id().value();
            if (raw != null
                && (normalized.isBlank() || raw.toLowerCase(Locale.ROOT).startsWith(normalized))) {
              suggestions.add('"' + raw + '"');
            }
          }
          return suggestions.stream().distinct().sorted().limit(20).toList();
        });
  }

  private static SuggestionProvider<CommandSender> speedSuggestions(String placeholder) {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = input != null ? input.lastRemainingToken() : "";
          prefix = prefix.trim().toLowerCase(Locale.ROOT);
          List<String> suggestions = new ArrayList<>();
          if (prefix.isBlank()) {
            suggestions.add(placeholder);
          }
          for (String candidate : List.of("25kmh", "40kmh", "60kmh", "80kmh", "8bps", "0.4bpt")) {
            if (prefix.isBlank() || candidate.startsWith(prefix)) {
              suggestions.add(candidate);
            }
          }
          return suggestions;
        });
  }

  private static SuggestionProvider<CommandSender> ttlSuggestions(String placeholder) {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = input != null ? input.lastRemainingToken() : "";
          prefix = prefix.trim().toLowerCase(Locale.ROOT);
          List<String> suggestions = new ArrayList<>();
          if (prefix.isBlank()) {
            suggestions.add(placeholder);
          }
          for (String candidate : List.of("90s", "5m", "15m", "30m", "1h")) {
            if (prefix.isBlank() || candidate.startsWith(prefix)) {
              suggestions.add(candidate);
            }
          }
          return suggestions;
        });
  }

  private static boolean hasSpeed(RailEdgeOverrideRecord record, Instant now) {
    return record.speedLimitBlocksPerSecond().isPresent() || record.isTempSpeedActive(now);
  }

  private static boolean edgeContains(EdgeId edgeId, NodeId nodeId) {
    return edgeId.a().equals(nodeId) || edgeId.b().equals(nodeId);
  }

  private Map<String, String> sectionValues(
      SectionSpeedPlan plan, RailSpeed speed, Optional<Duration> ttl, int touchedEdges) {
    return Map.of(
        "from",
        plan.from().value(),
        "to",
        plan.to().value(),
        "edges",
        Integer.toString(touchedEdges),
        "speed",
        speed.formatWithAllUnits(),
        "ttl",
        ttl.map(this::formatDuration).orElse("-"));
  }

  private Map<String, String> listValues(RailEdgeOverrideRecord record, Instant now) {
    String manual =
        record.speedLimitBlocksPerSecond().stream()
            .mapToObj(speed -> RailSpeed.ofBlocksPerSecond(speed).formatWithAllUnits())
            .findFirst()
            .orElse("-");
    String temp =
        record.isTempSpeedActive(now)
            ? RailSpeed.ofBlocksPerSecond(record.tempSpeedLimitBlocksPerSecond().getAsDouble())
                .formatWithAllUnits()
            : "-";
    String until = record.tempSpeedLimitUntil().map(Instant::toString).orElse("-");
    return Map.of(
        "a",
        record.edgeId().a().value(),
        "b",
        record.edgeId().b().value(),
        "speed",
        manual,
        "temp_speed",
        temp,
        "until",
        until,
        "updated_at",
        record.updatedAt().toString());
  }

  private String formatPath(SectionSpeedPlan plan) {
    return plan.nodes().stream().map(NodeId::value).reduce((a, b) -> a + " -> " + b).orElse("-");
  }

  private String formatDuration(Duration duration) {
    long seconds = duration.toSeconds();
    long hours = seconds / 3600;
    long minutes = (seconds % 3600) / 60;
    long rest = seconds % 60;
    List<String> parts = new ArrayList<>();
    if (hours > 0) {
      parts.add(hours + "h");
    }
    if (minutes > 0) {
      parts.add(minutes + "m");
    }
    if (rest > 0 || parts.isEmpty()) {
      parts.add(rest + "s");
    }
    return String.join("", parts);
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

  private record ResolvedSection(UUID worldId, SectionSpeedPlan plan) {}

  private record ParsedSpeedMode(Optional<RailSpeed> speed, Optional<Duration> ttl) {
    private ParsedSpeedMode {
      speed = speed == null ? Optional.empty() : speed;
      ttl = ttl == null ? Optional.empty() : ttl;
    }
  }
}
