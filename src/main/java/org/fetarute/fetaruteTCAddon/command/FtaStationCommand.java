package org.fetarute.fetaruteTCAddon.command;

import static org.fetarute.fetaruteTCAddon.command.CommandSuggestionProviders.placeholder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.api.CompanyQueryService;
import org.fetarute.fetaruteTCAddon.company.api.StationGroupChange;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.company.model.StationGroup;
import org.fetarute.fetaruteTCAddon.company.model.StationLocation;
import org.fetarute.fetaruteTCAddon.company.model.StationSidingPool;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteTerminals;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.parser.flag.CommandFlag;
import org.incendo.cloud.parser.standard.StringArrayParser;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.SuggestionProvider;

/**
 * /fta station 命令：维护站点名称、图节点绑定与位置等主数据，用于后续 PIDS/显示系统。
 *
 * <p>车站组（{@code /fta station group ...}）见 {@link FtaStationGroupCommand}。
 */
public final class FtaStationCommand {

  private static final int SUGGESTION_LIMIT = 20;

  private final FetaruteTCAddon plugin;

  public FtaStationCommand(FetaruteTCAddon plugin) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
  }

  public void register(CommandManager<CommandSender> manager) {
    Objects.requireNonNull(manager, "manager");

    SuggestionProvider<CommandSender> companySuggestions =
        SuggestionProvider.blockingStrings(
            (ctx, input) -> {
              if (!(ctx.sender() instanceof Player sender)) {
                return List.of();
              }
              Optional<StorageProvider> providerOpt = providerForSuggestions();
              if (providerOpt.isEmpty()) {
                return List.of();
              }
              String prefix = normalizeLowerPrefix(input);
              StorageProvider provider = providerOpt.get();
              List<String> out = new ArrayList<>();
              if (prefix.isEmpty()) {
                out.add("<company>");
              }
              for (Company company : provider.companies().listAll()) {
                if (company == null) {
                  continue;
                }
                if (!canReadCompanyNoCreateIdentity(sender, provider, company.id())) {
                  continue;
                }
                String code = company.code();
                if (code == null) {
                  continue;
                }
                String lower = code.toLowerCase(Locale.ROOT);
                if (prefix.isEmpty() || lower.startsWith(prefix)) {
                  out.add(code);
                }
                if (out.size() >= SUGGESTION_LIMIT) {
                  break;
                }
              }
              return out;
            });

    SuggestionProvider<CommandSender> operatorSuggestions =
        SuggestionProvider.blockingStrings(
            (ctx, input) -> {
              if (!(ctx.sender() instanceof Player sender)) {
                return List.of();
              }
              Optional<StorageProvider> providerOpt = providerForSuggestions();
              if (providerOpt.isEmpty()) {
                return List.of();
              }
              String companyArg;
              try {
                companyArg = ctx.get("company");
              } catch (Exception e) {
                return List.of("<operator>");
              }
              if (companyArg == null || companyArg.isBlank()) {
                return List.of("<operator>");
              }
              String prefix = normalizeLowerPrefix(input);
              CompanyQueryService query = new CompanyQueryService(providerOpt.get());
              Optional<Company> companyOpt = query.findCompany(companyArg.trim());
              if (companyOpt.isEmpty()) {
                return List.of();
              }
              Company company = companyOpt.get();
              if (!canReadCompanyNoCreateIdentity(sender, providerOpt.get(), company.id())) {
                return List.of();
              }
              List<String> out = new ArrayList<>();
              if (prefix.isEmpty()) {
                out.add("<operator>");
              }
              for (Operator op : providerOpt.get().operators().listByCompany(company.id())) {
                if (op == null) {
                  continue;
                }
                String code = op.code();
                String lower = code.toLowerCase(Locale.ROOT);
                if (prefix.isEmpty() || lower.startsWith(prefix)) {
                  out.add(code);
                }
                if (out.size() >= SUGGESTION_LIMIT) {
                  break;
                }
              }
              return out;
            });

    SuggestionProvider<CommandSender> stationSuggestions =
        SuggestionProvider.blockingStrings(
            (ctx, input) -> {
              if (!(ctx.sender() instanceof Player sender)) {
                return List.of();
              }
              Optional<StorageProvider> providerOpt = providerForSuggestions();
              if (providerOpt.isEmpty()) {
                return List.of();
              }
              String companyArg;
              String operatorArg;
              try {
                companyArg = ctx.get("company");
                operatorArg = ctx.get("operator");
              } catch (Exception e) {
                return List.of("<station>");
              }
              if (companyArg == null
                  || companyArg.isBlank()
                  || operatorArg == null
                  || operatorArg.isBlank()) {
                return List.of("<station>");
              }
              StorageProvider provider = providerOpt.get();
              CompanyQueryService query = new CompanyQueryService(provider);
              Optional<Company> companyOpt = query.findCompany(companyArg.trim());
              if (companyOpt.isEmpty()) {
                return List.of();
              }
              Company company = companyOpt.get();
              if (!canReadCompanyNoCreateIdentity(sender, provider, company.id())) {
                return List.of();
              }
              Optional<Operator> operatorOpt = query.findOperator(company.id(), operatorArg);
              if (operatorOpt.isEmpty()) {
                return List.of();
              }
              Operator operator = operatorOpt.get();
              String prefix = normalizeLowerPrefix(input);
              List<String> out = new ArrayList<>();
              if (prefix.isEmpty()) {
                out.add("<station>");
              }
              for (Station station : provider.stations().listByOperator(operator.id())) {
                if (station == null) {
                  continue;
                }
                String code = station.code();
                if (code == null) {
                  continue;
                }
                String lower = code.toLowerCase(Locale.ROOT);
                if (prefix.isEmpty() || lower.startsWith(prefix)) {
                  out.add(code);
                }
                if (out.size() >= SUGGESTION_LIMIT) {
                  break;
                }
              }
              return out;
            });

    var nameFlag =
        CommandFlag.<CommandSender>builder("name")
            .withComponent(
                org.incendo.cloud.component.CommandComponent.<CommandSender, String>builder(
                        "name", StringParser.quotedStringParser())
                    .suggestionProvider(placeholder("\"<name>\"")))
            .build();
    var secondaryFlag =
        CommandFlag.<CommandSender>builder("secondary")
            .withAliases("s")
            .withComponent(
                org.incendo.cloud.component.CommandComponent.<CommandSender, String>builder(
                        "secondary", StringParser.quotedStringParser())
                    .suggestionProvider(placeholder("\"<secondaryName>\"")))
            .build();
    var secondaryClearFlag = CommandFlag.builder("secondary-clear").build();
    var nodeFlag =
        CommandFlag.<CommandSender>builder("node")
            .withComponent(
                org.incendo.cloud.component.CommandComponent.<CommandSender, String>builder(
                        "node", StringParser.stringParser())
                    .suggestionProvider(placeholder("<nodeId>")))
            .build();
    var nodeClearFlag = CommandFlag.builder("node-clear").build();
    var hereFlag = CommandFlag.builder("here").build();
    var locationClearFlag = CommandFlag.builder("location-clear").build();
    var confirmFlag = CommandFlag.builder("confirm").build();

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("station")
            .literal("list")
            .senderType(Player.class)
            .required("company", StringParser.quotedStringParser(), companySuggestions)
            .required("operator", StringParser.quotedStringParser(), operatorSuggestions)
            .handler(
                ctx -> {
                  Player sender = ctx.sender();
                  Optional<StorageProvider> providerOpt = readyProvider(sender);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();
                  LocaleManager locale = plugin.getLocaleManager();
                  CompanyQueryService query = new CompanyQueryService(provider);

                  ResolvedOperator resolved = resolveOperator(ctx, provider, locale, query, false);
                  if (resolved == null) {
                    return;
                  }
                  List<Station> stations =
                      provider.stations().listByOperator(resolved.operator().id());
                  sender.sendMessage(
                      locale.component(
                          "command.station.list.header",
                          Map.of(
                              "company",
                              resolved.company().code(),
                              "operator",
                              resolved.operator().code())));
                  if (stations.isEmpty()) {
                    sender.sendMessage(locale.component("command.station.list.empty"));
                    return;
                  }
                  for (Station station : stations) {
                    if (station == null) {
                      continue;
                    }
                    sender.sendMessage(
                        locale.component(
                            "command.station.list.entry",
                            Map.of("code", station.code(), "name", station.name())));
                  }
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("station")
            .literal("info")
            .senderType(Player.class)
            .required("company", StringParser.quotedStringParser(), companySuggestions)
            .required("operator", StringParser.quotedStringParser(), operatorSuggestions)
            .required("station", StringParser.quotedStringParser(), stationSuggestions)
            .handler(
                ctx -> {
                  Player sender = ctx.sender();
                  Optional<StorageProvider> providerOpt = readyProvider(sender);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();
                  LocaleManager locale = plugin.getLocaleManager();
                  CompanyQueryService query = new CompanyQueryService(provider);

                  ResolvedStation resolved = resolveStation(ctx, provider, locale, query, false);
                  if (resolved == null) {
                    return;
                  }
                  Station station = resolved.station();
                  sender.sendMessage(
                      locale.component(
                          "command.station.info.header",
                          Map.of(
                              "operator",
                              resolved.operator().code(),
                              "code",
                              station.code(),
                              "name",
                              station.name())));
                  sender.sendMessage(
                      locale.component(
                          "command.station.info.secondary",
                          Map.of("secondary", station.secondaryName().orElse("-"))));
                  sender.sendMessage(
                      locale.component(
                          "command.station.info.node",
                          Map.of("node", station.graphNodeId().orElse("-"))));
                  String world = station.world().orElse("-");
                  String loc =
                      station
                          .location()
                          .map(
                              l ->
                                  String.format(
                                      Locale.ROOT,
                                      "%.2f %.2f %.2f (yaw=%.1f pitch=%.1f)",
                                      l.x(),
                                      l.y(),
                                      l.z(),
                                      l.yaw(),
                                      l.pitch()))
                          .orElse("-");
                  sender.sendMessage(
                      locale.component("command.station.info.world", Map.of("world", world)));
                  sender.sendMessage(
                      locale.component("command.station.info.location", Map.of("location", loc)));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("station")
            .literal("set")
            .senderType(Player.class)
            .required("company", StringParser.quotedStringParser(), companySuggestions)
            .required("operator", StringParser.quotedStringParser(), operatorSuggestions)
            .required("station", StringParser.quotedStringParser(), stationSuggestions)
            .flag(nameFlag)
            .flag(secondaryFlag)
            .flag(secondaryClearFlag)
            .flag(nodeFlag)
            .flag(nodeClearFlag)
            .flag(hereFlag)
            .flag(locationClearFlag)
            .handler(
                ctx -> {
                  Player sender = ctx.sender();
                  Optional<StorageProvider> providerOpt = readyProvider(sender);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();
                  LocaleManager locale = plugin.getLocaleManager();
                  CompanyQueryService query = new CompanyQueryService(provider);

                  ResolvedStation resolved = resolveStation(ctx, provider, locale, query, true);
                  if (resolved == null) {
                    return;
                  }
                  Station station = resolved.station();
                  var flags = ctx.flags();
                  boolean any =
                      flags.hasFlag(nameFlag)
                          || flags.hasFlag(secondaryFlag)
                          || flags.hasFlag(secondaryClearFlag)
                          || flags.hasFlag(nodeFlag)
                          || flags.hasFlag(nodeClearFlag)
                          || flags.hasFlag(hereFlag)
                          || flags.hasFlag(locationClearFlag);
                  if (!any) {
                    sender.sendMessage(locale.component("command.station.set.noop"));
                    return;
                  }

                  String name = flags.getValue(nameFlag, station.name());
                  if (name != null) {
                    name = name.trim();
                  }
                  if (name == null || name.isBlank()) {
                    name = station.name();
                  }

                  Optional<String> secondary = station.secondaryName();
                  if (flags.hasFlag(secondaryClearFlag)) {
                    secondary = Optional.empty();
                  } else if (flags.hasFlag(secondaryFlag)) {
                    String raw = flags.getValue(secondaryFlag, null);
                    if (raw != null) {
                      raw = raw.trim();
                    }
                    secondary = raw == null || raw.isBlank() ? Optional.empty() : Optional.of(raw);
                  }

                  Optional<String> node = station.graphNodeId();
                  if (flags.hasFlag(nodeClearFlag)) {
                    node = Optional.empty();
                  } else if (flags.hasFlag(nodeFlag)) {
                    String raw = flags.getValue(nodeFlag, null);
                    if (raw != null) {
                      raw = raw.trim();
                    }
                    node = raw == null || raw.isBlank() ? Optional.empty() : Optional.of(raw);
                  }

                  Optional<String> world = station.world();
                  Optional<StationLocation> location = station.location();
                  if (flags.hasFlag(locationClearFlag)) {
                    world = Optional.empty();
                    location = Optional.empty();
                  } else if (flags.hasFlag(hereFlag)) {
                    world = Optional.of(sender.getWorld().getName());
                    location =
                        Optional.of(
                            new StationLocation(
                                sender.getLocation().getX(),
                                sender.getLocation().getY(),
                                sender.getLocation().getZ(),
                                sender.getLocation().getYaw(),
                                sender.getLocation().getPitch()));
                  }

                  Station updated =
                      new Station(
                          station.id(),
                          station.code(),
                          station.operatorId(),
                          station.primaryLineId(),
                          name,
                          secondary,
                          world,
                          location,
                          node,
                          station.amenities(),
                          station.sidingPools(),
                          station.metadata(),
                          station.createdAt(),
                          Instant.now());
                  provider.stations().save(updated);
                  plugin.refreshStationDirectory();
                  sender.sendMessage(
                      locale.component(
                          "command.station.set.success", Map.of("code", station.code())));
                }));

    var capacityFlag =
        CommandFlag.<CommandSender>builder("capacity")
            .withComponent(
                org.incendo.cloud.component.CommandComponent.<CommandSender, Integer>builder(
                        "capacity",
                        org.incendo.cloud.parser.standard.IntegerParser.integerParser(1))
                    .suggestionProvider(placeholder("<N>")))
            .build();

    // 关联存车线池
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("station")
            .literal("link")
            .literal("siding")
            .senderType(Player.class)
            .required("company", StringParser.quotedStringParser(), companySuggestions)
            .required("operator", StringParser.quotedStringParser(), operatorSuggestions)
            .required("station", StringParser.quotedStringParser(), stationSuggestions)
            .required("poolId", StringParser.stringParser())
            .required("nodes", StringArrayParser.stringArrayParser())
            .flag(capacityFlag)
            .handler(
                ctx -> {
                  Player sender = ctx.sender();
                  Optional<StorageProvider> providerOpt = readyProvider(sender);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();
                  LocaleManager locale = plugin.getLocaleManager();
                  CompanyQueryService query = new CompanyQueryService(provider);

                  ResolvedStation resolved = resolveStation(ctx, provider, locale, query, true);
                  if (resolved == null) {
                    return;
                  }
                  Station station = resolved.station();
                  String poolId = ctx.get("poolId");
                  String[] rawNodes = ctx.get("nodes");
                  List<String> nodes = rawNodes == null ? List.of() : List.of(rawNodes);
                  Optional<Integer> capacityOpt = ctx.flags().getValue(capacityFlag);
                  int capacity = capacityOpt.orElse(nodes.size());

                  if (nodes.isEmpty()) {
                    sender.sendMessage(locale.component("command.station.link.siding.empty-nodes"));
                    return;
                  }

                  // 更新逻辑：移除同 poolId 的旧记录，再追加新记录
                  List<StationSidingPool> pools = new ArrayList<>(station.sidingPools());
                  pools.removeIf(p -> p.poolId().equalsIgnoreCase(poolId));

                  // 创建新存车线池（当前使用默认策略）
                  StationSidingPool pool =
                      new StationSidingPool(
                          poolId,
                          nodes,
                          StationSidingPool.SelectionPolicy.FIRST_AVAILABLE,
                          capacity,
                          StationSidingPool.FallbackPolicy.STAY_AT_PLATFORM);
                  pools.add(pool);

                  Station updated =
                      new Station(
                          station.id(),
                          station.code(),
                          station.operatorId(),
                          station.primaryLineId(),
                          station.name(),
                          station.secondaryName(),
                          station.world(),
                          station.location(),
                          station.graphNodeId(),
                          station.amenities(),
                          pools,
                          station.metadata(),
                          station.createdAt(),
                          Instant.now());
                  provider.stations().save(updated);
                  plugin.refreshStationDirectory();
                  sender.sendMessage(
                      locale.component(
                          "command.station.link.siding.success",
                          Map.of(
                              "station",
                              station.code(),
                              "pool",
                              poolId,
                              "count",
                              String.valueOf(nodes.size()),
                              "capacity",
                              String.valueOf(capacity))));
                }));

    // 列出存车线池
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("station")
            .literal("siding")
            .literal("list")
            .senderType(Player.class)
            .required("company", StringParser.quotedStringParser(), companySuggestions)
            .required("operator", StringParser.quotedStringParser(), operatorSuggestions)
            .required("station", StringParser.quotedStringParser(), stationSuggestions)
            .handler(
                ctx -> {
                  Player sender = ctx.sender();
                  Optional<StorageProvider> providerOpt = readyProvider(sender);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();
                  LocaleManager locale = plugin.getLocaleManager();
                  CompanyQueryService query = new CompanyQueryService(provider);

                  ResolvedStation resolved = resolveStation(ctx, provider, locale, query, false);
                  if (resolved == null) {
                    return;
                  }
                  Station station = resolved.station();

                  if (station.sidingPools().isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.station.siding.list.empty",
                            Map.of("station", station.code())));
                    return;
                  }

                  sender.sendMessage(
                      locale.component(
                          "command.station.siding.list.header", Map.of("station", station.code())));
                  for (StationSidingPool pool : station.sidingPools()) {
                    sender.sendMessage(
                        locale.component(
                            "command.station.siding.list.entry",
                            Map.of(
                                "pool",
                                pool.poolId(),
                                "nodes",
                                String.join(", ", pool.candidateNodes()))));
                  }
                }));

    // 清理不再引用且未出现在 rail_nodes 中的站点记录
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("station")
            .literal("dump")
            .senderType(Player.class)
            .required("company", StringParser.quotedStringParser(), companySuggestions)
            .required("operator", StringParser.quotedStringParser(), operatorSuggestions)
            .flag(confirmFlag)
            .handler(
                ctx -> {
                  Player sender = ctx.sender();
                  Optional<StorageProvider> providerOpt = readyProvider(sender);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();
                  LocaleManager locale = plugin.getLocaleManager();
                  CompanyQueryService query = new CompanyQueryService(provider);

                  ResolvedOperator resolved = resolveOperator(ctx, provider, locale, query, true);
                  if (resolved == null) {
                    return;
                  }

                  Set<UUID> referenced = new HashSet<>();
                  for (var line : provider.lines().listByOperator(resolved.operator().id())) {
                    if (line == null) {
                      continue;
                    }
                    for (var route : provider.routes().listByLine(line.id())) {
                      if (route == null) {
                        continue;
                      }
                      for (var stop : provider.routeStops().listByRoute(route.id())) {
                        if (stop == null) {
                          continue;
                        }
                        stop.stationId().ifPresent(referenced::add);
                      }
                    }
                  }

                  Set<String> activeCodes = new HashSet<>();
                  for (org.bukkit.World world : plugin.getServer().getWorlds()) {
                    if (world == null) {
                      continue;
                    }
                    List<RailNodeRecord> nodes;
                    try {
                      nodes = provider.railNodes().listByWorld(world.getUID());
                    } catch (Exception ignored) {
                      continue;
                    }
                    for (RailNodeRecord node : nodes) {
                      if (node == null) {
                        continue;
                      }
                      WaypointMetadata meta = node.waypointMetadata().orElse(null);
                      if (meta == null || meta.kind() != WaypointKind.STATION) {
                        continue;
                      }
                      if (!meta.operator().equalsIgnoreCase(resolved.operator().code())) {
                        continue;
                      }
                      String code = meta.originStation();
                      if (code != null && !code.isBlank()) {
                        activeCodes.add(code.trim().toLowerCase(Locale.ROOT));
                      }
                    }
                  }

                  List<Station> orphans = new ArrayList<>();
                  List<Station> stations =
                      provider.stations().listByOperator(resolved.operator().id());
                  for (Station station : stations) {
                    if (station == null) {
                      continue;
                    }
                    if (referenced.contains(station.id())) {
                      continue;
                    }
                    String code = station.code();
                    String key = code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
                    if (!key.isEmpty() && activeCodes.contains(key)) {
                      continue;
                    }
                    orphans.add(station);
                  }
                  orphans.sort(Comparator.comparing(s -> s.code().toLowerCase(Locale.ROOT)));

                  sender.sendMessage(
                      locale.component(
                          "command.station.dump.header",
                          Map.of(
                              "operator",
                              resolved.operator().code(),
                              "count",
                              String.valueOf(orphans.size()))));
                  if (orphans.isEmpty()) {
                    sender.sendMessage(locale.component("command.station.dump.empty"));
                    return;
                  }

                  if (!ctx.flags().isPresent(confirmFlag)) {
                    int limit = Math.min(20, orphans.size());
                    for (int i = 0; i < limit; i++) {
                      Station station = orphans.get(i);
                      sender.sendMessage(
                          locale.component(
                              "command.station.dump.entry",
                              Map.of("code", station.code(), "name", station.name())));
                    }
                    if (orphans.size() > limit) {
                      sender.sendMessage(
                          locale.component(
                              "command.station.dump.more",
                              Map.of("count", String.valueOf(orphans.size() - limit))));
                    }
                    sender.sendMessage(locale.component("command.common.confirm-required"));
                    return;
                  }

                  for (Station station : orphans) {
                    provider.stations().delete(station.id());
                  }
                  plugin.refreshStationDirectory();
                  sender.sendMessage(
                      locale.component(
                          "command.station.dump.success",
                          Map.of("count", String.valueOf(orphans.size()))));
                }));

    // 删除单个站点记录：仍被停靠表引用时拒绝；车站组成员随之移除；牌子仍在只警告
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("station")
            .literal("delete")
            .senderType(Player.class)
            .required("company", StringParser.quotedStringParser(), companySuggestions)
            .required("operator", StringParser.quotedStringParser(), operatorSuggestions)
            .required("station", StringParser.quotedStringParser(), stationSuggestions)
            .flag(confirmFlag)
            .handler(
                ctx -> {
                  Player sender = ctx.sender();
                  Optional<StorageProvider> providerOpt = readyProvider(sender);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();
                  LocaleManager locale = plugin.getLocaleManager();
                  CompanyQueryService query = new CompanyQueryService(provider);

                  ResolvedStation resolved = resolveStation(ctx, provider, locale, query, true);
                  if (resolved == null) {
                    return;
                  }
                  List<UUID> worldIds = new ArrayList<>();
                  for (org.bukkit.World world : plugin.getServer().getWorlds()) {
                    if (world != null) {
                      worldIds.add(world.getUID());
                    }
                  }
                  executeDelete(
                      sender,
                      provider,
                      resolved.operator(),
                      resolved.station(),
                      ctx.flags().isPresent(confirmFlag),
                      worldIds);
                }));
  }

  /**
   * 执行站点删除：有停靠点引用时拒绝并列出；未确认只预览；确认后删除记录，车站组成员随之移除。
   *
   * <p>牌子仍在时两种情况都提示下次建图会重建该站点记录。
   */
  void executeDelete(
      CommandSender sender,
      StorageProvider provider,
      Operator operator,
      Station station,
      boolean confirmed,
      List<UUID> worldIds) {
    LocaleManager locale = plugin.getLocaleManager();
    StationDeletionImpact impact = assessDeletion(provider, operator, station, worldIds);
    Map<String, String> base =
        Map.of("operator", operator.code(), "code", station.code(), "name", station.name());

    if (!impact.routeStops().isEmpty()) {
      sender.sendMessage(locale.component("command.station.delete.referenced", base));
      int limit = Math.min(20, impact.routeStops().size());
      for (int i = 0; i < limit; i++) {
        sender.sendMessage(
            locale.component(
                "command.station.delete.referenced-entry",
                Map.of("stop", impact.routeStops().get(i))));
      }
      if (impact.routeStops().size() > limit) {
        sender.sendMessage(
            locale.component(
                "command.station.delete.more",
                Map.of("count", String.valueOf(impact.routeStops().size() - limit))));
      }
      return;
    }

    if (!confirmed) {
      sender.sendMessage(locale.component("command.station.delete.preview", base));
      for (StationGroup group : impact.groups()) {
        sender.sendMessage(
            locale.component("command.station.delete.group", Map.of("group", group.code())));
      }
      sendSignWarning(sender, locale, impact);
      sender.sendMessage(locale.component("command.common.confirm-required"));
      return;
    }

    Instant now = Instant.now();
    provider
        .transactionManager()
        .execute(
            () -> {
              provider.stations().delete(station.id());
              for (StationGroup group : impact.groups()) {
                provider
                    .stationGroups()
                    .save(
                        new StationGroup(
                            group.id(),
                            group.companyId(),
                            group.code(),
                            group.name(),
                            group.secondaryName(),
                            group.metadata(),
                            group.createdAt(),
                            now));
              }
              return null;
            });
    if (impact.groups().isEmpty()) {
      plugin.refreshStationDirectory();
    }
    // 车站组变化通知内部会刷新车站目录，有组时不再重复刷新
    for (StationGroup group : impact.groups()) {
      plugin.notifyStationGroupChanged(
          new StationGroupChange(
              group.id(),
              group.companyId(),
              group.code(),
              StationGroupChange.Kind.MEMBER_REMOVED,
              Optional.of(station.id())));
    }
    sender.sendMessage(locale.component("command.station.delete.success", base));
    sendSignWarning(sender, locale, impact);
  }

  private static void sendSignWarning(
      CommandSender sender, LocaleManager locale, StationDeletionImpact impact) {
    if (impact.signNodes().isEmpty()) {
      return;
    }
    sender.sendMessage(
        locale.component(
            "command.station.delete.signs",
            Map.of(
                "count",
                String.valueOf(impact.signNodes().size()),
                "nodes",
                String.join(", ", impact.signNodes()))));
  }

  /**
   * 删除站点前的影响评估。
   *
   * @param routeStops 引用该站的停靠点，形如 {@code 运营商/线路/交路#序号}；非空即拒绝删除
   * @param groups 该站所在的车站组（删除时成员关系随之移除）
   * @param signNodes 已加载世界的 rail_nodes 中仍挂着该车站代码的 STATION 节点；下次建图会按它们重建站点记录
   */
  record StationDeletionImpact(
      List<String> routeStops, List<StationGroup> groups, List<String> signNodes) {}

  /**
   * 汇总删除站点的影响。
   *
   * <p>停靠表扫描全部线路而非仅本运营商：直通运转的交路可以停靠他家车站。
   */
  static StationDeletionImpact assessDeletion(
      StorageProvider provider, Operator operator, Station station, List<UUID> worldIds) {
    Map<UUID, String> operatorCodes = new HashMap<>();
    List<String> routeStops = new ArrayList<>();
    for (Line line : provider.lines().listAll()) {
      if (line == null) {
        continue;
      }
      for (Route route : provider.routes().listByLine(line.id())) {
        if (route == null) {
          continue;
        }
        for (RouteStop stop : provider.routeStops().listByRoute(route.id())) {
          if (!stopReferencesStation(stop, operator, station)) {
            continue;
          }
          String operatorCode =
              operatorCodes.computeIfAbsent(
                  line.operatorId(),
                  id -> provider.operators().findById(id).map(Operator::code).orElse("?"));
          routeStops.add(
              operatorCode + "/" + line.code() + "/" + route.code() + "#" + stop.sequence());
        }
      }
    }
    routeStops.sort(String.CASE_INSENSITIVE_ORDER);

    List<StationGroup> groups = new ArrayList<>();
    provider
        .stationGroups()
        .findMemberByStation(station.id())
        .flatMap(member -> provider.stationGroups().findById(member.groupId()))
        .ifPresent(groups::add);

    List<String> signNodes = new ArrayList<>();
    String code = station.code() == null ? "" : station.code().trim();
    for (UUID worldId : worldIds) {
      List<RailNodeRecord> nodes;
      try {
        nodes = provider.railNodes().listByWorld(worldId);
      } catch (Exception ignored) {
        continue;
      }
      for (RailNodeRecord node : nodes) {
        if (node == null) {
          continue;
        }
        WaypointMetadata meta = node.waypointMetadata().orElse(null);
        if (meta == null || meta.kind() != WaypointKind.STATION) {
          continue;
        }
        if (!code.isEmpty()
            && meta.operator().trim().equalsIgnoreCase(operator.code())
            && meta.originStation().trim().equalsIgnoreCase(code)) {
          signNodes.add(node.nodeId().value());
        }
      }
    }
    signNodes.sort(String.CASE_INSENSITIVE_ORDER);
    return new StationDeletionImpact(
        List.copyOf(routeStops), List.copyOf(groups), List.copyOf(signNodes));
  }

  /**
   * 停靠点是否引用该站：绑定的 stationId、节点写法或 DYNAMIC 写法的车站身份（站台、咽喉）、或该站绑定的图节点，任一命中即算。
   *
   * <p>口径与车站目录解析停靠点一致，见 {@link RouteTerminals#stationIdentityOf}。
   */
  static boolean stopReferencesStation(RouteStop stop, Operator operator, Station station) {
    if (stop == null) {
      return false;
    }
    if (stop.stationId().map(station.id()::equals).orElse(false)) {
      return true;
    }
    Optional<RouteTerminals.StationRef> ref = RouteTerminals.stationIdentityOf(stop);
    if (ref.isPresent()
        && ref.get().operatorCode().equalsIgnoreCase(operator.code())
        && ref.get().stationCode().equalsIgnoreCase(station.code())) {
      return true;
    }
    Optional<String> boundNode =
        station.graphNodeId().map(String::trim).filter(node -> !node.isEmpty());
    return boundNode.isPresent()
        && stop.waypointNodeId().map(String::trim).map(boundNode.get()::equals).orElse(false);
  }

  private Optional<StorageProvider> readyProvider(CommandSender sender) {
    return CommandStorageProviders.readyProvider(sender, plugin);
  }

  /** 用于 tab 补全：存储不可用时不输出错误消息，避免刷屏。 */
  private Optional<StorageProvider> providerForSuggestions() {
    return CommandStorageProviders.providerIfReady(plugin);
  }

  private ResolvedOperator resolveOperator(
      org.incendo.cloud.context.CommandContext<? extends CommandSender> ctx,
      StorageProvider provider,
      LocaleManager locale,
      CompanyQueryService query,
      boolean requireManage) {
    CommandSender sender = ctx.sender();
    String companyArg;
    try {
      companyArg = ctx.get("company");
    } catch (Exception e) {
      return null;
    }
    if (companyArg == null) {
      return null; // 按 required 声明理论上不会发生
    }
    companyArg = companyArg.trim();

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
    String operatorArg;
    try {
      operatorArg = ctx.get("operator");
    } catch (Exception e) {
      return null;
    }
    if (operatorArg == null) {
      return null;
    }
    operatorArg = operatorArg.trim();
    Optional<Operator> operatorOpt = query.findOperator(company.id(), operatorArg);
    if (operatorOpt.isEmpty()) {
      sender.sendMessage(
          locale.component("command.operator.not-found", Map.of("operator", operatorArg)));
      return null;
    }
    return new ResolvedOperator(company, operatorOpt.get());
  }

  private ResolvedStation resolveStation(
      org.incendo.cloud.context.CommandContext<? extends CommandSender> ctx,
      StorageProvider provider,
      LocaleManager locale,
      CompanyQueryService query,
      boolean requireManage) {
    ResolvedOperator resolved = resolveOperator(ctx, provider, locale, query, requireManage);
    if (resolved == null) {
      return null;
    }
    String stationArg;
    try {
      stationArg = ctx.get("station");
    } catch (Exception e) {
      return null;
    }
    if (stationArg == null) {
      return null;
    }
    stationArg = stationArg.trim();
    Optional<Station> stationOpt =
        provider.stations().findByOperatorAndCode(resolved.operator().id(), stationArg);
    if (stationOpt.isEmpty()) {
      ctx.sender()
          .sendMessage(
              locale.component(
                  "command.station.not-found",
                  Map.of("operator", resolved.operator().code(), "station", stationArg)));
      return null;
    }
    return new ResolvedStation(resolved.company(), resolved.operator(), stationOpt.get());
  }

  private boolean canReadCompany(CommandSender sender, StorageProvider provider, UUID companyId) {
    return CompanyAccessChecker.canReadCompany(sender, provider, companyId);
  }

  private boolean canManageCompany(CommandSender sender, StorageProvider provider, UUID companyId) {
    return CompanyAccessChecker.canManageCompany(sender, provider, companyId);
  }

  /**
   * 判断 sender 是否可读取公司，但不创建身份。
   *
   * <p>用于 Tab 补全阶段：若身份尚未建立则返回 false，避免补全触发写操作。
   */
  private boolean canReadCompanyNoCreateIdentity(
      CommandSender sender, StorageProvider provider, UUID companyId) {
    return CompanyAccessChecker.canReadCompanyNoCreateIdentity(sender, provider, companyId);
  }

  private static String normalizeLowerPrefix(CommandInput input) {
    if (input == null) {
      return "";
    }
    return input.lastRemainingToken().trim().toLowerCase(Locale.ROOT);
  }

  private record ResolvedOperator(Company company, Operator operator) {}

  private record ResolvedStation(Company company, Operator operator, Station station) {}
}
