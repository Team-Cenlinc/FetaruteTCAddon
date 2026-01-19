package org.fetarute.fetaruteTCAddon.command;

import static org.fetarute.fetaruteTCAddon.command.CommandSuggestionProviders.placeholder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
import org.fetarute.fetaruteTCAddon.company.api.PlayerIdentityService;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.CompanyMember;
import org.fetarute.fetaruteTCAddon.company.model.MemberRole;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.company.model.StationLocation;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.parser.flag.CommandFlag;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.SuggestionProvider;

/** /fta station 命令：维护站点名称、图节点绑定与位置等主数据，用于后续 PIDS/显示系统。 */
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
            .required("company", StringParser.stringParser(), companySuggestions)
            .required("operator", StringParser.stringParser(), operatorSuggestions)
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
            .required("company", StringParser.stringParser(), companySuggestions)
            .required("operator", StringParser.stringParser(), operatorSuggestions)
            .required("station", StringParser.stringParser(), stationSuggestions)
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
            .required("company", StringParser.stringParser(), companySuggestions)
            .required("operator", StringParser.stringParser(), operatorSuggestions)
            .required("station", StringParser.stringParser(), stationSuggestions)
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
                          station.metadata(),
                          station.createdAt(),
                          Instant.now());
                  provider.stations().save(updated);
                  sender.sendMessage(
                      locale.component(
                          "command.station.set.success", Map.of("code", station.code())));
                }));

    // dump: 清理不再引用且未出现在 rail_nodes 中的站点记录
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("station")
            .literal("dump")
            .senderType(Player.class)
            .required("company", StringParser.stringParser(), companySuggestions)
            .required("operator", StringParser.stringParser(), operatorSuggestions)
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
                  sender.sendMessage(
                      locale.component(
                          "command.station.dump.success",
                          Map.of("count", String.valueOf(orphans.size()))));
                }));
  }

  private Optional<StorageProvider> readyProvider(CommandSender sender) {
    if (plugin.getStorageManager() == null || !plugin.getStorageManager().isReady()) {
      sender.sendMessage(plugin.getLocaleManager().component("error.storage-unavailable"));
      return Optional.empty();
    }
    return plugin.getStorageManager().provider();
  }

  /** 用于 tab 补全：存储不可用时不输出错误消息，避免刷屏。 */
  private Optional<StorageProvider> providerForSuggestions() {
    if (plugin.getStorageManager() == null || !plugin.getStorageManager().isReady()) {
      return Optional.empty();
    }
    return plugin.getStorageManager().provider();
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
      return null; // Should not happen if required
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
    if (sender.hasPermission("fetarute.admin")) {
      return true;
    }
    if (!(sender instanceof Player player)) {
      return false;
    }
    var identity = new PlayerIdentityService(provider.playerIdentities()).requireIdentity(player);
    return provider.companyMembers().findMembership(companyId, identity.id()).isPresent();
  }

  private boolean canManageCompany(CommandSender sender, StorageProvider provider, UUID companyId) {
    if (sender.hasPermission("fetarute.admin")) {
      return true;
    }
    if (!(sender instanceof Player player)) {
      return false;
    }
    var identity = new PlayerIdentityService(provider.playerIdentities()).requireIdentity(player);
    Optional<CompanyMember> membership =
        provider.companyMembers().findMembership(companyId, identity.id());
    if (membership.isEmpty()) {
      return false;
    }
    var roles = membership.get().roles();
    return roles.contains(MemberRole.OWNER) || roles.contains(MemberRole.MANAGER);
  }

  /**
   * 判断 sender 是否可读取公司，但不创建身份。
   *
   * <p>用于 Tab 补全阶段：若身份尚未建立则返回 false，避免补全触发写操作。
   */
  private boolean canReadCompanyNoCreateIdentity(
      CommandSender sender, StorageProvider provider, UUID companyId) {
    if (sender.hasPermission("fetarute.admin")) {
      return true;
    }
    if (!(sender instanceof Player player)) {
      return false;
    }
    return provider
        .playerIdentities()
        .findByPlayerUuid(player.getUniqueId())
        .map(
            identity ->
                provider.companyMembers().findMembership(companyId, identity.id()).isPresent())
        .orElse(false);
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
