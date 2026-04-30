package org.fetarute.fetaruteTCAddon.command;

import com.bergerkiller.bukkit.tc.SignActionHeader;
import com.bergerkiller.bukkit.tc.TrainCarts;
import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.components.RailPiece;
import com.bergerkiller.bukkit.tc.controller.components.RailState;
import com.bergerkiller.bukkit.tc.controller.spawnable.SpawnableGroup;
import com.bergerkiller.bukkit.tc.controller.spawnable.SpawnableGroup.SpawnLocationList;
import com.bergerkiller.bukkit.tc.controller.spawnable.SpawnableGroup.SpawnMode;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.command.CommandSender;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.api.CompanyQueryService;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.PlayerIdentity;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.config.ConfigManager.SpawnSettings;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.TrainCartsRailBlockAccess;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDestinationResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteStopResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainNameFormatter;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainTagHelper;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.export.ScheduleCsvExporter;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.model.ScheduleWindow;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.model.ServiceTrip;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.planner.SchedulePlanner;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnPlan;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnTicket;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.TicketAssigner;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry.SignNodeInfo;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.action.AutoStationDoorController;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.parser.flag.CommandFlag;
import org.incendo.cloud.parser.standard.IntegerParser;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.SuggestionProvider;

/**
 * /fta depot 命令注册。
 *
 * <p>用于手动触发车库生成列车（调度前置测试）。车型/编组优先级：命令 {@code --pattern} &gt; route metadata {@code
 * spawn_train_pattern} &gt; depot 牌子第 4 行。
 *
 * <p>生成后会清空 TrainCarts 的 destination/route，并写入 trainName 与 FTA_* tags，便于调度/PIDS 识别。
 */
public final class FtaDepotCommand {

  private static final PlainTextComponentSerializer PLAIN_TEXT =
      PlainTextComponentSerializer.plainText();
  private static final int SUGGESTION_LIMIT = 20;
  private static final long DEPOT_CHUNK_TICKET_TICKS = 200L;
  private static final String ROUTE_SPAWN_PATTERN_KEY = "spawn_train_pattern";

  private final FetaruteTCAddon plugin;

  public FtaDepotCommand(FetaruteTCAddon plugin) {
    this.plugin = plugin;
  }

  /** 注册 {@code /fta depot spawn} 与 depot 计划诊断命令。 */
  public void register(CommandManager<CommandSender> manager) {
    SuggestionProvider<CommandSender> companySuggestions = companySuggestions();
    SuggestionProvider<CommandSender> operatorSuggestions = operatorSuggestions();
    SuggestionProvider<CommandSender> lineSuggestions = lineSuggestions();
    SuggestionProvider<CommandSender> routeSuggestions = routeSuggestions();
    SuggestionProvider<CommandSender> depotSuggestions = depotSuggestions();
    SuggestionProvider<CommandSender> limitSuggestions =
        CommandSuggestionProviders.placeholder("<limit>");

    var patternFlag =
        CommandFlag.builder("pattern").withComponent(StringParser.greedyStringParser()).build();

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("depot")
            .literal("schedule")
            .literal("check")
            .permission("fetarute.spawn")
            .required("node", StringParser.quotedStringParser(), depotSuggestions)
            .optional("limit", IntegerParser.integerParser(1, 100), limitSuggestions)
            .handler(
                ctx -> {
                  int limit = ctx.<Integer>optional("limit").orElse(12);
                  checkDepotSchedule(ctx.sender(), normalizeNodeIdArg(ctx.get("node")), limit);
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("depot")
            .literal("spawn")
            .permission("fetarute.depot.spawn")
            .required("company", StringParser.quotedStringParser(), companySuggestions)
            .required("operator", StringParser.quotedStringParser(), operatorSuggestions)
            .required("line", StringParser.quotedStringParser(), lineSuggestions)
            .required("route", StringParser.quotedStringParser(), routeSuggestions)
            .required("node", StringParser.quotedStringParser(), depotSuggestions)
            .flag(patternFlag)
            .handler(
                ctx -> {
                  CommandSender sender = ctx.sender();
                  LocaleManager locale = plugin.getLocaleManager();
                  Optional<org.fetarute.fetaruteTCAddon.storage.api.StorageProvider> providerOpt =
                      readyProvider(sender);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  var provider = providerOpt.get();
                  CompanyQueryService query = new CompanyQueryService(provider);

                  ResolvedRoute resolved = resolveRoute(ctx, provider, locale, query, true);
                  if (resolved == null) {
                    return;
                  }

                  String nodeRaw = normalizeNodeIdArg(ctx.get("node"));
                  NodeId depotId = NodeId.of(nodeRaw);
                  SignNodeRegistry registry = plugin.getSignNodeRegistry();
                  if (registry == null) {
                    sender.sendMessage(
                        locale.component(
                            "command.depot.spawn.not-found", Map.of("node", depotId.value())));
                    return;
                  }
                  Optional<SignNodeInfo> depotInfoOpt = findDepotNode(registry, depotId);
                  if (depotInfoOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.depot.spawn.not-found", Map.of("node", depotId.value())));
                    return;
                  }
                  SignNodeInfo depotInfo = depotInfoOpt.get();
                  if (depotInfo.definition().nodeType() != NodeType.DEPOT) {
                    sender.sendMessage(
                        locale.component(
                            "command.depot.spawn.not-depot", Map.of("node", depotId.value())));
                    return;
                  }

                  World world = Bukkit.getWorld(depotInfo.worldId());
                  if (world == null) {
                    sender.sendMessage(
                        locale.component(
                            "command.depot.spawn.world-missing",
                            Map.of("world", depotInfo.worldName())));
                    return;
                  }
                  int x = depotInfo.x();
                  int y = depotInfo.y();
                  int z = depotInfo.z();
                  loadNearbyChunks(world, x, z, 4, plugin, DEPOT_CHUNK_TICKET_TICKS);
                  Block signBlock = world.getBlockAt(x, y, z);
                  if (!(signBlock.getState() instanceof Sign sign)) {
                    sender.sendMessage(
                        locale.component(
                            "command.depot.spawn.sign-missing", Map.of("node", depotId.value())));
                    return;
                  }

                  String patternOverride =
                      normalizeSpawnPattern(ctx.flags().getValue(patternFlag, null));
                  Optional<String> routePattern = routeSpawnPattern(resolved.route());
                  Optional<String> signPattern = readDepotPattern(sign);
                  String pattern =
                      firstNonBlank(
                          patternOverride, routePattern.orElse(null), signPattern.orElse(null));
                  if (pattern == null) {
                    sender.sendMessage(locale.component("command.depot.spawn.pattern-missing"));
                    return;
                  }

                  TrainCarts trainCarts = TrainCarts.plugin;
                  if (trainCarts == null) {
                    sender.sendMessage(locale.component("command.depot.spawn.spawn-failed"));
                    return;
                  }
                  SpawnableGroup spawnable = SpawnableGroup.parse(trainCarts, pattern);
                  if (spawnable == null || spawnable.getMembers().isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.depot.spawn.pattern-invalid", Map.of("pattern", pattern)));
                    return;
                  }

                  TrainCartsRailBlockAccess access = new TrainCartsRailBlockAccess(world);
                  Set<RailBlockPos> anchors = findAnchorRails(access, depotInfo);
                  if (anchors.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.depot.spawn.rail-missing", Map.of("node", depotId.value())));
                    return;
                  }

                  Optional<MinecartGroup> spawnedOpt = spawnAtAnchors(world, spawnable, anchors);
                  if (spawnedOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.depot.spawn.spawn-failed", Map.of("node", depotId.value())));
                    return;
                  }

                  MinecartGroup group = spawnedOpt.get();
                  TrainProperties properties = group.getProperties();
                  UUID runId = UUID.randomUUID();
                  Optional<RouteDestinationResolver.DestinationInfo> destInfoOpt =
                      RouteDestinationResolver.resolve(provider, resolved.route());
                  RouteDestinationResolver.DestinationInfo destInfo =
                      destInfoOpt.orElse(
                          new RouteDestinationResolver.DestinationInfo(
                              resolved.route().name(), resolved.route().code()));
                  String trainName =
                      TrainNameFormatter.buildTrainName(
                          resolved.operator().code(),
                          resolved.line().code(),
                          resolved.route().patternType(),
                          destInfo.code(),
                          runId);
                  if (properties != null) {
                    properties.clearDestinationRoute();
                    properties.clearDestination();
                    properties.setTrainName(trainName);
                    addTags(properties, runId, resolved, depotId, pattern, destInfo);
                    initializeRouteIndex(
                        properties, provider, resolved.route(), depotId, sender, locale);
                  }
                  Bukkit.getScheduler()
                      .runTaskLater(
                          plugin, () -> AutoStationDoorController.warmUpDoorAnimations(group), 2L);
                  Bukkit.getScheduler()
                      .runTaskLater(
                          plugin, () -> AutoStationDoorController.warmUpDoorAnimations(group), 10L);

                  sender.sendMessage(
                      locale.component(
                          "command.depot.spawn.success",
                          Map.of(
                              "train",
                              trainName,
                              "route",
                              resolved.route().code(),
                              "pattern",
                              pattern,
                              "dest",
                              destInfo.name(),
                              "run_id",
                              runId.toString())));
                }));
  }

  /**
   * 检查指定 depot 的发车计划与运行时阻塞原因。
   *
   * <p>输出分三层：未来一小时静态计划、当前 SpawnPlan 服务、运行时 queue/pending 票据。这样可以区分“根本没有匹配计划”和“计划已出票但被
   * backoff/gate/上限限制卡住”两类问题。
   */
  private void checkDepotSchedule(CommandSender sender, String nodeRaw, int limit) {
    LocaleManager locale = plugin.getLocaleManager();
    Optional<org.fetarute.fetaruteTCAddon.storage.api.StorageProvider> providerOpt =
        readyProvider(sender);
    if (providerOpt.isEmpty()) {
      return;
    }
    var provider = providerOpt.get();

    NodeId depotId = NodeId.of(nodeRaw);
    SignNodeRegistry registry = plugin.getSignNodeRegistry();
    if (registry == null) {
      sender.sendMessage(
          locale.component("command.depot.spawn.not-found", Map.of("node", depotId.value())));
      return;
    }
    Optional<SignNodeInfo> depotInfoOpt = findDepotNode(registry, depotId);
    if (depotInfoOpt.isEmpty()) {
      sender.sendMessage(
          locale.component("command.depot.spawn.not-found", Map.of("node", depotId.value())));
      return;
    }
    SignNodeInfo depotInfo = depotInfoOpt.get();
    if (depotInfo.definition().nodeType() != NodeType.DEPOT) {
      sender.sendMessage(
          locale.component("command.depot.spawn.not-depot", Map.of("node", depotId.value())));
      return;
    }

    Optional<SpawnManager> managerOpt = plugin.getSpawnManager();
    if (managerOpt.isEmpty()) {
      sender.sendMessage(locale.component("command.spawn.not-ready"));
      return;
    }
    SpawnManager manager = managerOpt.get();
    Optional<TicketAssigner> assignerOpt = plugin.getSpawnTicketAssigner();
    SpawnSettings settings = plugin.getConfigManager().current().spawnSettings();
    Instant now = Instant.now();

    SpawnPlan plan = manager.snapshotPlan();
    List<SpawnService> matchedServices =
        plan.services().stream()
            .filter(
                service ->
                    SpawnScheduleDiagnostics.serviceMatchesDepot(
                        provider, service, depotId.value()))
            .sorted(
                Comparator.comparing(SpawnService::operatorCode, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(SpawnService::lineCode, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(SpawnService::routeCode, String.CASE_INSENSITIVE_ORDER))
            .toList();
    ScheduleWindow window =
        new SchedulePlanner(plugin.getLoggerManager()::debug)
            .plan(provider, now, now.plus(Duration.ofHours(1)));
    List<ServiceTrip> matchedTrips =
        window.trips().stream()
            .filter(trip -> SpawnScheduleDiagnostics.tripMatchesDepot(trip, depotId.value()))
            .sorted(Comparator.comparing(ServiceTrip::plannedDeparture))
            .toList();
    List<SpawnTicket> matchedQueue =
        manager.snapshotQueue().stream()
            .filter(
                ticket ->
                    SpawnScheduleDiagnostics.ticketMatchesDepot(provider, ticket, depotId.value()))
            .sorted(
                Comparator.comparing(SpawnTicket::notBefore)
                    .thenComparing(SpawnTicket::dueAt)
                    .thenComparing(ticket -> ticket.service().routeCode()))
            .toList();
    List<SpawnTicket> pendingSource =
        assignerOpt.map(TicketAssigner::snapshotPendingTickets).orElse(List.of());
    List<SpawnTicket> matchedPending =
        pendingSource.stream()
            .filter(
                ticket ->
                    SpawnScheduleDiagnostics.ticketMatchesDepot(provider, ticket, depotId.value()))
            .sorted(
                Comparator.comparing(SpawnTicket::notBefore)
                    .thenComparing(SpawnTicket::dueAt)
                    .thenComparing(ticket -> ticket.service().routeCode()))
            .toList();

    sender.sendMessage(
        locale.component(
            "command.depot.schedule.header",
            Map.of(
                "node",
                depotId.value(),
                "trips",
                String.valueOf(matchedTrips.size()),
                "services",
                String.valueOf(matchedServices.size()),
                "queue",
                String.valueOf(matchedQueue.size()),
                "pending",
                String.valueOf(matchedPending.size()))));
    sender.sendMessage(
        locale.component(
            "command.depot.schedule.settings",
            Map.of(
                "enabled",
                settings.enabled() ? "是" : "否",
                "tick",
                String.valueOf(settings.tickIntervalTicks()),
                "max_spawn",
                String.valueOf(settings.maxSpawnPerTick()),
                "max_generate",
                String.valueOf(settings.maxGeneratePerTick()))));

    sendDepotScheduleTrips(sender, locale, matchedTrips, limit);
    sendDepotScheduleServices(sender, locale, provider, matchedServices, limit);
    sendDepotScheduleTickets(
        sender, locale, "command.depot.schedule.queue", matchedQueue, now, limit);
    sendDepotScheduleTickets(
        sender, locale, "command.depot.schedule.pending", matchedPending, now, limit);
    sendDepotScheduleReasons(
        sender, locale, settings, matchedServices, matchedTrips, matchedQueue, matchedPending, now);
  }

  private void sendDepotScheduleTrips(
      CommandSender sender, LocaleManager locale, List<ServiceTrip> trips, int limit) {
    if (trips.isEmpty()) {
      sender.sendMessage(locale.component("command.depot.schedule.empty-trips"));
      return;
    }
    for (ServiceTrip trip : trips.stream().limit(limit).toList()) {
      sender.sendMessage(
          locale.component(
              "command.depot.schedule.trip",
              Map.of(
                  "depart",
                  formatInstant(trip.plannedDeparture()),
                  "operator",
                  trip.operatorCode(),
                  "line",
                  trip.lineCode(),
                  "route",
                  trip.routeCode(),
                  "depots",
                  ScheduleCsvExporter.formatDepotCandidates(trip))));
    }
    if (trips.size() > limit) {
      sender.sendMessage(
          locale.component(
              "command.depot.schedule.truncated",
              Map.of("count", String.valueOf(trips.size() - limit))));
    }
  }

  private void sendDepotScheduleServices(
      CommandSender sender,
      LocaleManager locale,
      org.fetarute.fetaruteTCAddon.storage.api.StorageProvider provider,
      List<SpawnService> services,
      int limit) {
    if (services.isEmpty()) {
      sender.sendMessage(locale.component("command.depot.schedule.empty-services"));
      return;
    }
    for (SpawnService service : services.stream().limit(limit).toList()) {
      sender.sendMessage(
          locale.component(
              "command.depot.schedule.service",
              Map.of(
                  "operator",
                  service.operatorCode(),
                  "line",
                  service.lineCode(),
                  "route",
                  service.routeCode(),
                  "headway",
                  formatDuration(service.baseHeadway()),
                  "depots",
                  SpawnScheduleDiagnostics.depotCandidatesText(provider, service))));
    }
    if (services.size() > limit) {
      sender.sendMessage(
          locale.component(
              "command.depot.schedule.truncated",
              Map.of("count", String.valueOf(services.size() - limit))));
    }
  }

  private void sendDepotScheduleTickets(
      CommandSender sender,
      LocaleManager locale,
      String localeKey,
      List<SpawnTicket> tickets,
      Instant now,
      int limit) {
    if (tickets.isEmpty()) {
      return;
    }
    for (SpawnTicket ticket : tickets.stream().limit(limit).toList()) {
      SpawnService service = ticket.service();
      sender.sendMessage(
          locale.component(
              localeKey,
              Map.of(
                  "operator",
                  service.operatorCode(),
                  "line",
                  service.lineCode(),
                  "route",
                  service.routeCode(),
                  "due",
                  formatDurationSec(Duration.between(now, ticket.dueAt())),
                  "not_before",
                  formatDurationSec(Duration.between(now, ticket.notBefore())),
                  "attempts",
                  String.valueOf(ticket.attempts()),
                  "selected_depot",
                  ticket.selectedDepotNodeId().orElse("-"),
                  "reason",
                  SpawnScheduleDiagnostics.ticketReason(ticket, now))));
    }
    if (tickets.size() > limit) {
      sender.sendMessage(
          locale.component(
              "command.depot.schedule.truncated",
              Map.of("count", String.valueOf(tickets.size() - limit))));
    }
  }

  private void sendDepotScheduleReasons(
      CommandSender sender,
      LocaleManager locale,
      SpawnSettings settings,
      List<SpawnService> services,
      List<ServiceTrip> trips,
      List<SpawnTicket> queue,
      List<SpawnTicket> pending,
      Instant now) {
    List<SpawnTicket> tickets = new ArrayList<>(queue.size() + pending.size());
    tickets.addAll(queue);
    tickets.addAll(pending);
    Map<String, Long> reasons = SpawnScheduleDiagnostics.reasonSummary(tickets, now);
    if (reasons.isEmpty()) {
      sender.sendMessage(
          locale.component(
              "command.depot.schedule.idle",
              Map.of("reason", depotIdleReason(settings, services, trips))));
      return;
    }
    List<Map.Entry<String, Long>> sortedReasons =
        reasons.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .toList();
    for (Map.Entry<String, Long> entry : sortedReasons) {
      sender.sendMessage(
          locale.component(
              "command.depot.schedule.reason",
              Map.of("reason", entry.getKey(), "count", String.valueOf(entry.getValue()))));
    }
  }

  private static String depotIdleReason(
      SpawnSettings settings, List<SpawnService> services, List<ServiceTrip> trips) {
    if (settings != null && !settings.enabled()) {
      return "spawn.enabled=false";
    }
    if ((services == null || services.isEmpty()) && (trips == null || trips.isEmpty())) {
      return "该 depot 没有匹配计划：检查 route 首站/CRET、line depot pool 或 /fta spawn diagnose";
    }
    return "该 depot 有计划但暂无已出票 ticket：可能未到 due、SpawnMonitor 尚未 tick、队列刚 reset，或被 backlog/generate 上限限制";
  }

  private Optional<org.fetarute.fetaruteTCAddon.storage.api.StorageProvider> readyProvider(
      CommandSender sender) {
    return CommandStorageProviders.readyProvider(sender, plugin);
  }

  private ResolvedRoute resolveRoute(
      org.incendo.cloud.context.CommandContext<? extends CommandSender> ctx,
      org.fetarute.fetaruteTCAddon.storage.api.StorageProvider provider,
      LocaleManager locale,
      CompanyQueryService query,
      boolean requireManage) {
    CommandSender sender = ctx.sender();
    String companyArg = ((String) ctx.get("company")).trim();
    Optional<Company> companyOpt = query.findCompany(companyArg);
    if (companyOpt.isEmpty()) {
      sender.sendMessage(
          locale.component("command.company.info.not-found", Map.of("company", companyArg)));
      return null;
    }
    Company company = companyOpt.get();
    boolean ok =
        requireManage
            ? canManageCompany(sender, provider, company.id())
            : canReadCompany(sender, provider, company.id());
    if (!ok) {
      sender.sendMessage(locale.component("error.no-permission"));
      return null;
    }

    String operatorArg = ((String) ctx.get("operator")).trim();
    Optional<Operator> operatorOpt = query.findOperator(company.id(), operatorArg);
    if (operatorOpt.isEmpty()) {
      sender.sendMessage(
          locale.component("command.operator.not-found", Map.of("operator", operatorArg)));
      return null;
    }
    Operator operator = operatorOpt.get();
    String lineArg = ((String) ctx.get("line")).trim();
    Optional<Line> lineOpt = query.findLine(operator.id(), lineArg);
    if (lineOpt.isEmpty()) {
      sender.sendMessage(locale.component("command.line.not-found", Map.of("line", lineArg)));
      return null;
    }
    Line line = lineOpt.get();

    String routeArg = ((String) ctx.get("route")).trim();
    Optional<Route> routeOpt = query.findRoute(line.id(), routeArg);
    if (routeOpt.isEmpty()) {
      sender.sendMessage(locale.component("command.route.not-found", Map.of("route", routeArg)));
      return null;
    }
    return new ResolvedRoute(company, operator, line, routeOpt.get());
  }

  private boolean canReadCompany(
      CommandSender sender,
      org.fetarute.fetaruteTCAddon.storage.api.StorageProvider provider,
      UUID companyId) {
    return CompanyAccessChecker.canReadCompanyNoCreateIdentity(sender, provider, companyId);
  }

  private boolean canManageCompany(
      CommandSender sender,
      org.fetarute.fetaruteTCAddon.storage.api.StorageProvider provider,
      UUID companyId) {
    return CompanyAccessChecker.canManageCompanyNoCreateIdentity(sender, provider, companyId);
  }

  private SuggestionProvider<CommandSender> companySuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = normalizePrefix(input);
          List<String> suggestions = new ArrayList<>();
          if (prefix.isBlank()) {
            suggestions.add("<company>");
          }
          suggestions.addAll(listCompanyCodes(ctx.sender(), prefix));
          return suggestions;
        });
  }

  private SuggestionProvider<CommandSender> operatorSuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = normalizePrefix(input);
          List<String> suggestions = new ArrayList<>();
          if (prefix.isBlank()) {
            suggestions.add("<operator>");
          }
          Optional<org.fetarute.fetaruteTCAddon.storage.api.StorageProvider> providerOpt =
              providerIfReady();
          if (providerOpt.isEmpty()) {
            return suggestions;
          }
          Optional<String> companyArgOpt = ctx.optional("company").map(String.class::cast);
          if (companyArgOpt.isEmpty()) {
            return suggestions;
          }
          String companyArg = companyArgOpt.get().trim();
          if (companyArg.isBlank()) {
            return suggestions;
          }
          var provider = providerOpt.get();
          CompanyQueryService query = new CompanyQueryService(provider);
          Optional<Company> companyOpt = query.findCompany(companyArg);
          if (companyOpt.isEmpty()) {
            return suggestions;
          }
          Company company = companyOpt.get();
          if (!canReadCompanyNoCreateIdentity(ctx.sender(), provider, company.id())) {
            return suggestions;
          }
          provider.operators().listByCompany(company.id()).stream()
              .map(Operator::code)
              .filter(Objects::nonNull)
              .map(String::trim)
              .filter(code -> !code.isBlank())
              .filter(code -> code.toLowerCase(Locale.ROOT).startsWith(prefix))
              .distinct()
              .limit(SUGGESTION_LIMIT)
              .forEach(suggestions::add);
          return suggestions;
        });
  }

  private SuggestionProvider<CommandSender> lineSuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = normalizePrefix(input);
          List<String> suggestions = new ArrayList<>();
          if (prefix.isBlank()) {
            suggestions.add("<line>");
          }
          Optional<org.fetarute.fetaruteTCAddon.storage.api.StorageProvider> providerOpt =
              providerIfReady();
          if (providerOpt.isEmpty()) {
            return suggestions;
          }
          Optional<String> companyArgOpt = ctx.optional("company").map(String.class::cast);
          Optional<String> operatorArgOpt = ctx.optional("operator").map(String.class::cast);
          if (companyArgOpt.isEmpty() || operatorArgOpt.isEmpty()) {
            return suggestions;
          }
          String companyArg = companyArgOpt.get().trim();
          String operatorArg = operatorArgOpt.get().trim();
          if (companyArg.isBlank() || operatorArg.isBlank()) {
            return suggestions;
          }
          var provider = providerOpt.get();
          CompanyQueryService query = new CompanyQueryService(provider);
          Optional<Company> companyOpt = query.findCompany(companyArg);
          if (companyOpt.isEmpty()) {
            return suggestions;
          }
          Company company = companyOpt.get();
          if (!canReadCompanyNoCreateIdentity(ctx.sender(), provider, company.id())) {
            return suggestions;
          }
          Optional<Operator> operatorOpt = query.findOperator(company.id(), operatorArg);
          if (operatorOpt.isEmpty()) {
            return suggestions;
          }
          provider.lines().listByOperator(operatorOpt.get().id()).stream()
              .map(Line::code)
              .filter(Objects::nonNull)
              .map(String::trim)
              .filter(code -> !code.isBlank())
              .filter(code -> code.toLowerCase(Locale.ROOT).startsWith(prefix))
              .distinct()
              .limit(SUGGESTION_LIMIT)
              .forEach(suggestions::add);
          return suggestions;
        });
  }

  private SuggestionProvider<CommandSender> routeSuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = normalizePrefix(input);
          List<String> suggestions = new ArrayList<>();
          if (prefix.isBlank()) {
            suggestions.add("<route>");
          }
          Optional<org.fetarute.fetaruteTCAddon.storage.api.StorageProvider> providerOpt =
              providerIfReady();
          if (providerOpt.isEmpty()) {
            return suggestions;
          }
          Optional<String> companyArgOpt = ctx.optional("company").map(String.class::cast);
          Optional<String> operatorArgOpt = ctx.optional("operator").map(String.class::cast);
          Optional<String> lineArgOpt = ctx.optional("line").map(String.class::cast);
          if (companyArgOpt.isEmpty() || operatorArgOpt.isEmpty() || lineArgOpt.isEmpty()) {
            return suggestions;
          }
          String companyArg = companyArgOpt.get().trim();
          String operatorArg = operatorArgOpt.get().trim();
          String lineArg = lineArgOpt.get().trim();
          if (companyArg.isBlank() || operatorArg.isBlank() || lineArg.isBlank()) {
            return suggestions;
          }
          var provider = providerOpt.get();
          CompanyQueryService query = new CompanyQueryService(provider);
          Optional<Company> companyOpt = query.findCompany(companyArg);
          if (companyOpt.isEmpty()) {
            return suggestions;
          }
          Company company = companyOpt.get();
          if (!canReadCompanyNoCreateIdentity(ctx.sender(), provider, company.id())) {
            return suggestions;
          }
          Optional<Operator> operatorOpt = query.findOperator(company.id(), operatorArg);
          if (operatorOpt.isEmpty()) {
            return suggestions;
          }
          Optional<Line> lineOpt = query.findLine(operatorOpt.get().id(), lineArg);
          if (lineOpt.isEmpty()) {
            return suggestions;
          }
          provider.routes().listByLine(lineOpt.get().id()).stream()
              .map(Route::code)
              .filter(Objects::nonNull)
              .map(String::trim)
              .filter(code -> !code.isBlank())
              .filter(code -> code.toLowerCase(Locale.ROOT).startsWith(prefix))
              .distinct()
              .limit(SUGGESTION_LIMIT)
              .forEach(suggestions::add);
          return suggestions;
        });
  }

  private SuggestionProvider<CommandSender> depotSuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = normalizePrefix(input);
          List<String> suggestions = new ArrayList<>();
          if (prefix.isBlank()) {
            suggestions.add("<nodeId>");
          }
          SignNodeRegistry registry = plugin.getSignNodeRegistry();
          if (registry == null) {
            return suggestions;
          }
          registry.snapshotInfos().values().stream()
              .filter(info -> info != null && info.definition() != null)
              .filter(info -> info.definition().nodeType() == NodeType.DEPOT)
              .map(info -> info.definition().nodeId().value())
              .filter(Objects::nonNull)
              .map(String::trim)
              .filter(value -> !value.isBlank())
              .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
              .distinct()
              .sorted()
              .limit(SUGGESTION_LIMIT)
              .map(value -> '"' + value + '"')
              .forEach(suggestions::add);
          return suggestions;
        });
  }

  private Optional<org.fetarute.fetaruteTCAddon.storage.api.StorageProvider> providerIfReady() {
    return CommandStorageProviders.providerIfReady(plugin);
  }

  private boolean canReadCompanyNoCreateIdentity(
      CommandSender sender,
      org.fetarute.fetaruteTCAddon.storage.api.StorageProvider provider,
      UUID companyId) {
    return CompanyAccessChecker.canReadCompanyNoCreateIdentity(sender, provider, companyId);
  }

  private List<String> listCompanyCodes(CommandSender sender, String prefix) {
    Optional<org.fetarute.fetaruteTCAddon.storage.api.StorageProvider> providerOpt =
        providerIfReady();
    if (providerOpt.isEmpty()) {
      return List.of();
    }
    var provider = providerOpt.get();
    if (sender.hasPermission("fetarute.admin")) {
      return provider.companies().listAll().stream()
          .map(Company::code)
          .filter(Objects::nonNull)
          .map(String::trim)
          .filter(code -> !code.isBlank())
          .filter(code -> code.toLowerCase(Locale.ROOT).startsWith(prefix))
          .distinct()
          .limit(SUGGESTION_LIMIT)
          .toList();
    }
    if (!(sender instanceof org.bukkit.entity.Player player)) {
      return List.of();
    }
    Optional<PlayerIdentity> identityOpt =
        provider.playerIdentities().findByPlayerUuid(player.getUniqueId());
    if (identityOpt.isEmpty()) {
      return List.of();
    }
    return provider.companyMembers().listMemberships(identityOpt.get().id()).stream()
        .map(org.fetarute.fetaruteTCAddon.company.model.CompanyMember::companyId)
        .distinct()
        .map(provider.companies()::findById)
        .flatMap(Optional::stream)
        .map(Company::code)
        .filter(Objects::nonNull)
        .map(String::trim)
        .filter(code -> !code.isBlank())
        .filter(code -> code.toLowerCase(Locale.ROOT).startsWith(prefix))
        .distinct()
        .limit(SUGGESTION_LIMIT)
        .toList();
  }

  private static String normalizePrefix(CommandInput input) {
    String prefix = input != null ? input.lastRemainingToken() : "";
    return prefix.trim().toLowerCase(Locale.ROOT);
  }

  private static Optional<SignNodeInfo> findDepotNode(SignNodeRegistry registry, NodeId nodeId) {
    return registry.snapshotInfos().values().stream()
        .filter(info -> info != null && info.definition() != null)
        .filter(info -> nodeId.equals(info.definition().nodeId()))
        .findFirst();
  }

  private static Optional<String> routeSpawnPattern(Route route) {
    if (route == null) {
      return Optional.empty();
    }
    Object value = route.metadata().get(ROUTE_SPAWN_PATTERN_KEY);
    if (value instanceof String raw) {
      String normalized = normalizeSpawnPattern(raw);
      if (normalized != null) {
        return Optional.of(normalized);
      }
    }
    return Optional.empty();
  }

  /** 从 depot 牌子读取车型/编组（第 4 行，允许正反面）。 */
  private static Optional<String> readDepotPattern(Sign sign) {
    if (sign == null) {
      return Optional.empty();
    }
    return readDepotPatternFromSide(sign, Side.FRONT)
        .or(() -> readDepotPatternFromSide(sign, Side.BACK));
  }

  /** 从指定面读取 depot 牌子第 4 行（车型/编组）。 */
  private static Optional<String> readDepotPatternFromSide(Sign sign, Side side) {
    SignSide view = sign.getSide(side);
    String header = PLAIN_TEXT.serialize(view.line(0)).trim();
    SignActionHeader parsed = SignActionHeader.parse(header);
    if (parsed == null || (!parsed.isTrain() && !parsed.isCart())) {
      return Optional.empty();
    }
    String type = PLAIN_TEXT.serialize(view.line(1)).trim().toLowerCase(Locale.ROOT);
    if (!"depot".equals(type)) {
      return Optional.empty();
    }
    String rawPattern = PLAIN_TEXT.serialize(view.line(3));
    String normalized = normalizeSpawnPattern(rawPattern);
    if (normalized == null) {
      return Optional.empty();
    }
    return Optional.of(normalized);
  }

  private static String firstNonBlank(String... values) {
    if (values == null) {
      return null;
    }
    for (String raw : values) {
      if (raw == null) {
        continue;
      }
      String trimmed = raw.trim();
      if (!trimmed.isEmpty()) {
        return trimmed;
      }
    }
    return null;
  }

  private static Set<RailBlockPos> findAnchorRails(
      TrainCartsRailBlockAccess access, SignNodeInfo depotInfo) {
    RailBlockPos center = new RailBlockPos(depotInfo.x(), depotInfo.y(), depotInfo.z());
    Set<RailBlockPos> anchors = access.findNearestRailBlocks(center, 2);
    if (!anchors.isEmpty()) {
      return anchors;
    }
    return access.findNearestRailBlocks(center, 4);
  }

  private static Optional<MinecartGroup> spawnAtAnchors(
      World world, SpawnableGroup spawnable, Set<RailBlockPos> anchors) {
    for (RailBlockPos anchor : anchors) {
      Optional<MinecartGroup> spawned = spawnAtAnchor(world, spawnable, anchor);
      if (spawned.isPresent()) {
        return spawned;
      }
    }
    return Optional.empty();
  }

  private static Optional<MinecartGroup> spawnAtAnchor(
      World world, SpawnableGroup spawnable, RailBlockPos anchor) {
    if (world == null || spawnable == null || anchor == null) {
      return Optional.empty();
    }
    Block railBlock = world.getBlockAt(anchor.x(), anchor.y(), anchor.z());
    RailPiece piece = RailPiece.create(railBlock);
    if (piece == null || piece.isNone()) {
      return Optional.empty();
    }
    RailState state = RailState.getSpawnState(piece);
    if (state == null) {
      return Optional.empty();
    }
    Vector direction = state.motionVector();
    if (direction == null) {
      return Optional.empty();
    }
    SpawnLocationList locations = findSpawnLocations(spawnable, piece, direction);
    if (locations == null) {
      return Optional.empty();
    }
    locations.loadChunks();
    if (locations.isOccupied()) {
      return Optional.empty();
    }
    MinecartGroup group = spawnable.spawn(locations);
    return Optional.ofNullable(group);
  }

  /**
   * 加载 depot 周边区块，并持有短期 chunk ticket 防止立刻卸载。
   *
   * <p>该方法会在 {@code holdTicks} 后自动释放 ticket。
   */
  private static void loadNearbyChunks(
      World world,
      int blockX,
      int blockZ,
      int blockRadius,
      org.bukkit.plugin.Plugin plugin,
      long holdTicks) {
    if (world == null || blockRadius < 0) {
      return;
    }
    int chunkRadius = Math.max(0, (blockRadius + 15) >> 4);
    int baseChunkX = blockX >> 4;
    int baseChunkZ = blockZ >> 4;
    java.util.Set<Long> ticketed = new java.util.HashSet<>();
    for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
      for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
        int cx = baseChunkX + dx;
        int cz = baseChunkZ + dz;
        if (!world.isChunkLoaded(cx, cz)) {
          world.getChunkAt(cx, cz);
        }
        if (plugin != null && holdTicks > 0L) {
          if (world.addPluginChunkTicket(cx, cz, plugin)) {
            ticketed.add((((long) cx) << 32) ^ (cz & 0xffffffffL));
          }
        }
      }
    }
    if (plugin != null && holdTicks > 0L && !ticketed.isEmpty()) {
      Bukkit.getScheduler()
          .runTaskLater(
              plugin,
              () -> {
                for (long key : ticketed) {
                  int cx = (int) (key >> 32);
                  int cz = (int) key;
                  world.removePluginChunkTicket(cx, cz, plugin);
                }
              },
              holdTicks);
    }
  }

  private static SpawnLocationList findSpawnLocations(
      SpawnableGroup spawnable, RailPiece piece, Vector direction) {
    SpawnLocationList locations = spawnable.findSpawnLocations(piece, direction, SpawnMode.DEFAULT);
    if (locations != null && locations.can_move) {
      return locations;
    }
    Vector reversed = direction.clone().multiply(-1.0);
    SpawnLocationList reversedLocations =
        spawnable.findSpawnLocations(piece, reversed, SpawnMode.DEFAULT);
    if (reversedLocations != null && reversedLocations.can_move) {
      return reversedLocations;
    }
    return locations != null ? locations : reversedLocations;
  }

  /**
   * 写入 FTA_* tags（key=value）用于机读调度/PIDS。
   *
   * <p>tag 值会做简单清洗，避免空白或分隔符污染。
   */
  private static void addTags(
      TrainProperties properties,
      UUID runId,
      ResolvedRoute resolved,
      NodeId depotId,
      String spawnPattern,
      RouteDestinationResolver.DestinationInfo destInfo) {
    if (properties == null || runId == null || resolved == null) {
      return;
    }
    Instant now = Instant.now();
    Map<String, String> tags = new HashMap<>();
    tags.put("FTA_RUN_ID", runId.toString());
    tags.put("FTA_ROUTE_ID", resolved.route().id().toString());
    tags.put("FTA_ROUTE_CODE", resolved.route().code());
    tags.put("FTA_LINE_CODE", resolved.line().code());
    tags.put("FTA_OPERATOR_CODE", resolved.operator().code());
    tags.put("FTA_PATTERN", resolved.route().patternType().name());
    tags.put("FTA_DEPOT_ID", depotId != null ? depotId.value() : "");
    tags.put("FTA_SPAWN_PATTERN", spawnPattern);
    tags.put("FTA_DEST_CODE", destInfo.code());
    tags.put("FTA_DEST_NAME", destInfo.name());
    tags.put("FTA_RUN_AT", String.valueOf(now.toEpochMilli()));
    List<String> out = new ArrayList<>();
    for (Map.Entry<String, String> entry : tags.entrySet()) {
      String value = sanitizeTagValue(entry.getValue());
      if (value.isEmpty()) {
        continue;
      }
      out.add(entry.getKey() + "=" + value);
    }
    if (!out.isEmpty()) {
      properties.addTags(out.toArray(new String[0]));
    }
  }

  private void initializeRouteIndex(
      TrainProperties properties,
      org.fetarute.fetaruteTCAddon.storage.api.StorageProvider provider,
      Route route,
      NodeId depotId,
      CommandSender sender,
      LocaleManager locale) {
    if (properties == null || provider == null || route == null || depotId == null) {
      return;
    }
    List<RouteStop> stops = provider.routeStops().listByRoute(route.id());
    if (stops.isEmpty()) {
      sender.sendMessage(
          locale.component("command.depot.spawn.route-empty", Map.of("route", route.code())));
      return;
    }
    RouteStop first = stops.get(0);
    if (first == null) {
      sender.sendMessage(
          locale.component("command.depot.spawn.route-empty", Map.of("route", route.code())));
      return;
    }
    boolean matches =
        RouteStopResolver.resolveNodeId(provider, first)
            .map(node -> node.value().equalsIgnoreCase(depotId.value()))
            .orElse(false);
    if (!matches) {
      Optional<String> cretTarget = resolveCretTarget(first);
      matches = cretTarget.map(target -> target.equalsIgnoreCase(depotId.value())).orElse(false);
    }
    // 尝试 DYNAMIC 匹配：首站可能是 DYNAMIC depot stop
    if (!matches) {
      matches = DynamicStopMatcher.matchesStop(depotId, first);
    }
    if (!matches) {
      sender.sendMessage(
          locale.component(
              "command.depot.spawn.route-mismatch",
              Map.of("route", route.code(), "node", depotId.value())));
      return;
    }
    TrainTagHelper.writeTag(properties, RouteProgressRegistry.TAG_ROUTE_INDEX, String.valueOf(0));
    TrainTagHelper.writeTag(
        properties,
        RouteProgressRegistry.TAG_ROUTE_UPDATED_AT,
        String.valueOf(Instant.now().toEpochMilli()));
  }

  private Optional<String> resolveCretTarget(RouteStop stop) {
    if (stop == null || stop.notes().isEmpty()) {
      return Optional.empty();
    }
    String raw = stop.notes().orElse("");
    if (raw.isBlank()) {
      return Optional.empty();
    }
    String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
    for (String line : normalized.split("\n", -1)) {
      if (line == null) {
        continue;
      }
      String trimmed = line.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      if (!startsWithWord(trimmed, "CRET")) {
        continue;
      }
      String rest = trimmed.substring(4).trim();
      if (rest.startsWith(":")) {
        rest = rest.substring(1).trim();
      }
      if (!rest.isEmpty()) {
        return Optional.of(rest);
      }
    }
    return Optional.empty();
  }

  private static boolean startsWithWord(String text, String word) {
    return text != null
        && word != null
        && text.regionMatches(true, 0, word, 0, word.length())
        && (text.length() == word.length()
            || Character.isWhitespace(text.charAt(word.length()))
            || text.charAt(word.length()) == ':');
  }

  private static String sanitizeTagValue(String raw) {
    if (raw == null) {
      return "";
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      return "";
    }
    String normalized = trimmed.replace('=', '-').replace('|', '-');
    return normalized.replaceAll("\\s+", "_");
  }

  /**
   * 格式化计划时间。
   *
   * @param instant 现实时间
   * @return ISO-8601 字符串；空值或占位时间返回 {@code -}
   */
  private static String formatInstant(Instant instant) {
    if (instant == null || instant.equals(Instant.EPOCH)) {
      return "-";
    }
    return instant.toString();
  }

  /**
   * 格式化正向间隔。
   *
   * @param duration 间隔
   * @return 便于聊天窗口阅读的秒/分钟文本
   */
  private static String formatDuration(Duration duration) {
    if (duration == null) {
      return "-";
    }
    long secs = duration.toSeconds();
    if (secs < 60) {
      return secs + "s";
    }
    return (secs / 60) + "m" + (secs % 60) + "s";
  }

  /**
   * 格式化相对时间。
   *
   * @param duration 相对当前时间，负值表示已过期
   * @return 便于聊天窗口阅读的相对时间文本
   */
  private static String formatDurationSec(Duration duration) {
    if (duration == null) {
      return "-";
    }
    long secs = duration.toSeconds();
    if (secs < 0) {
      return "已过期 " + (-secs) + "s";
    }
    if (secs < 60) {
      return secs + "s";
    }
    return (secs / 60) + "m" + (secs % 60) + "s";
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

  private static String normalizeSpawnPattern(String raw) {
    if (raw == null) {
      return null;
    }
    String trimmed = raw.trim();
    if (trimmed.length() >= 2) {
      char first = trimmed.charAt(0);
      char last = trimmed.charAt(trimmed.length() - 1);
      if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
        trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
      }
    }
    return trimmed.isBlank() ? null : trimmed;
  }

  private record ResolvedRoute(Company company, Operator operator, Line line, Route route) {}
}
