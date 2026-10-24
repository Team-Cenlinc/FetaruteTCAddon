package org.fetarute.fetaruteTCAddon.command;

import static org.fetarute.fetaruteTCAddon.command.CommandSuggestionProviders.placeholder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.bukkit.command.CommandSender;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.api.CompanyQueryService;
import org.fetarute.fetaruteTCAddon.company.api.ControlZoneDirectory;
import org.fetarute.fetaruteTCAddon.company.api.ControlZoneService;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.ControlZone;
import org.fetarute.fetaruteTCAddon.company.model.ControlZoneMember;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.parser.flag.CommandFlag;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.SuggestionProvider;

/**
 * /fta signal 命令：信号员相关的配置。目前只有控制区（{@code zone}）。
 *
 * <ul>
 *   <li>create / rename / delete：建区、改名、删区（删区需要 --confirm）
 *   <li>add / remove：加入或移出同一运营商的车站（{@code station}）与车库（{@code depot}）；车站要在库里登记过，车库要在调度图里有节点
 *   <li>list / info：列出运营商的控制区、查看成员
 * </ul>
 *
 * <p>站场图屏用 {@code /fta pids set <屏幕> zone <控制区>}
 * 绑定控制区，把区内各车站与车库连同它们之间的线路画在一张图上。改库之后刷新控制区目录，站场图屏下一次检查即生效。信号员控制台不是站台屏， 安装、绑定控制区与拆除在 {@code /fta
 * signal console} 下（见 {@link FtaPidsCommand.Scope#CONSOLE}）。
 */
public final class FtaSignalCommand {

  private static final int SUGGESTION_LIMIT = 20;

  private final FetaruteTCAddon plugin;

  public FtaSignalCommand(FetaruteTCAddon plugin) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
  }

  public void register(CommandManager<CommandSender> manager) {
    Objects.requireNonNull(manager, "manager");
    SuggestionProvider<CommandSender> companies =
        SuggestionProvider.blockingStrings((ctx, input) -> suggestCompanies(ctx, input));
    SuggestionProvider<CommandSender> operators =
        SuggestionProvider.blockingStrings((ctx, input) -> suggestOperators(ctx, input));
    SuggestionProvider<CommandSender> zones =
        SuggestionProvider.blockingStrings((ctx, input) -> suggestZones(ctx, input));
    SuggestionProvider<CommandSender> kinds =
        CommandSuggestionProviders.enumValues(ControlZoneMember.Kind.class, "<kind>");
    SuggestionProvider<CommandSender> candidates =
        SuggestionProvider.blockingStrings((ctx, input) -> suggestCandidates(ctx, input));
    SuggestionProvider<CommandSender> members =
        SuggestionProvider.blockingStrings((ctx, input) -> suggestMembers(ctx, input));
    var confirmFlag = CommandFlag.builder("confirm").build();

    manager.command(
        manager.commandBuilder("fta").literal("signal").handler(ctx -> help(ctx.sender())));
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("signal")
            .literal("zone")
            .handler(ctx -> help(ctx.sender())));
    manager.command(
        zoneCommand(manager, "create", companies, operators)
            .required("code", StringParser.stringParser(), placeholder("<code>"))
            .required("name", StringParser.quotedStringParser(), placeholder("\"<name>\""))
            .optional(
                "secondaryName",
                StringParser.quotedStringParser(),
                placeholder("\"<secondaryName>\""))
            .handler(this::handleCreate));
    manager.command(zoneCommand(manager, "list", companies, operators).handler(this::handleList));
    manager.command(
        zoneCommand(manager, "info", companies, operators)
            .required("zone", StringParser.stringParser(), zones)
            .handler(this::handleInfo));
    manager.command(
        zoneCommand(manager, "rename", companies, operators)
            .required("zone", StringParser.stringParser(), zones)
            .required("name", StringParser.quotedStringParser(), placeholder("\"<name>\""))
            .optional(
                "secondaryName",
                StringParser.quotedStringParser(),
                placeholder("\"<secondaryName>\""))
            .handler(this::handleRename));
    manager.command(
        zoneCommand(manager, "add", companies, operators)
            .required("zone", StringParser.stringParser(), zones)
            .required("kind", StringParser.stringParser(), kinds)
            .required("member", StringParser.stringParser(), candidates)
            .handler(ctx -> handleMember(ctx, true)));
    manager.command(
        zoneCommand(manager, "remove", companies, operators)
            .required("zone", StringParser.stringParser(), zones)
            .required("kind", StringParser.stringParser(), kinds)
            .required("member", StringParser.stringParser(), members)
            .handler(ctx -> handleMember(ctx, false)));
    manager.command(
        zoneCommand(manager, "delete", companies, operators)
            .required("zone", StringParser.stringParser(), zones)
            .flag(confirmFlag)
            .handler(
                ctx -> {
                  if (!ctx.flags().isPresent(confirmFlag)) {
                    ctx.sender()
                        .sendMessage(
                            plugin.getLocaleManager().component("command.common.confirm-required"));
                    return;
                  }
                  handleDelete(ctx);
                }));
  }

  /** {@code /fta signal zone <动作> <公司> <运营商>} 的公共前缀。 */
  private org.incendo.cloud.Command.Builder<CommandSender> zoneCommand(
      CommandManager<CommandSender> manager,
      String action,
      SuggestionProvider<CommandSender> companies,
      SuggestionProvider<CommandSender> operators) {
    return manager
        .commandBuilder("fta")
        .literal("signal")
        .literal("zone")
        .literal(action)
        .required("company", StringParser.quotedStringParser(), companies)
        .required("operator", StringParser.quotedStringParser(), operators);
  }

  private void help(CommandSender sender) {
    LocaleManager locale = plugin.getLocaleManager();
    for (String key :
        List.of(
            "header", "create", "list", "info", "rename", "add", "delete", "bind", "console",
            "duty")) {
      sender.sendMessage(locale.component("command.signal.help." + key));
    }
  }

  // ─── 处理器 ─────────────────────────────────────────────────────────────────

  private void handleCreate(CommandContext<CommandSender> ctx) {
    resolve(ctx, false)
        .ifPresent(
            target -> {
              LocaleManager locale = plugin.getLocaleManager();
              String code = ctx.<String>get("code").trim();
              ControlZoneService.Result result =
                  new ControlZoneService(target.provider())
                      .create(
                          target.operator(),
                          code,
                          ctx.get("name"),
                          ctx.optional("secondaryName"),
                          canManage(ctx.sender(), target.provider()));
              Map<String, String> values =
                  Map.of(
                      "operator", target.operator().code(), "code", code.toUpperCase(Locale.ROOT));
              switch (result.status()) {
                case CREATED -> {
                  ControlZone zone = result.zone().orElseThrow();
                  plugin.refreshControlZones();
                  ctx.sender()
                      .sendMessage(
                          locale.component(
                              "command.signal.zone.create.success",
                              Map.of(
                                  "operator",
                                  target.operator().code(),
                                  "code",
                                  zone.code(),
                                  "name",
                                  zone.name())));
                }
                case DUPLICATE -> ctx.sender()
                    .sendMessage(locale.component("command.signal.zone.create.exists", values));
                case INVALID -> ctx.sender()
                    .sendMessage(locale.component("command.signal.zone.create.invalid"));
                default -> ctx.sender().sendMessage(locale.component("error.no-permission"));
              }
            });
  }

  private void handleList(CommandContext<CommandSender> ctx) {
    resolve(ctx, true)
        .ifPresent(
            target -> {
              LocaleManager locale = plugin.getLocaleManager();
              List<ControlZoneDirectory.Zone> zones =
                  plugin.getControlZoneDirectory().listByOperator(target.operator().id());
              String operator = target.operator().code();
              if (zones.isEmpty()) {
                ctx.sender()
                    .sendMessage(
                        locale.component(
                            "command.signal.zone.list.empty", Map.of("operator", operator)));
                return;
              }
              ctx.sender()
                  .sendMessage(
                      locale.component(
                          "command.signal.zone.list.header",
                          Map.of("operator", operator, "count", String.valueOf(zones.size()))));
              for (ControlZoneDirectory.Zone zone : zones) {
                List<ControlZoneMember> members = zone.members();
                ctx.sender()
                    .sendMessage(
                        locale.component(
                            "command.signal.zone.list.entry",
                            Map.of(
                                "code",
                                zone.code(),
                                "name",
                                zone.zone().name(),
                                "stations",
                                String.valueOf(count(members, ControlZoneMember.Kind.STATION)),
                                "depots",
                                String.valueOf(count(members, ControlZoneMember.Kind.DEPOT)))));
              }
            });
  }

  private void handleInfo(CommandContext<CommandSender> ctx) {
    resolve(ctx, true)
        .flatMap(target -> resolveZone(ctx, target))
        .ifPresent(
            found -> {
              LocaleManager locale = plugin.getLocaleManager();
              ControlZone zone = found.zone();
              StorageProvider provider = found.target().provider();
              CommandSender sender = ctx.sender();
              sender.sendMessage(
                  locale.component(
                      "command.signal.zone.info.header",
                      Map.of(
                          "operator",
                          found.target().operator().code(),
                          "code",
                          zone.code(),
                          "name",
                          zone.name())));
              sender.sendMessage(
                  locale.component(
                      "command.signal.zone.info.secondary",
                      Map.of("secondary", zone.secondaryName().orElse("-"))));
              List<ControlZoneMember> members =
                  plugin
                      .getControlZoneDirectory()
                      .listByOperator(found.target().operator().id())
                      .stream()
                      .filter(entry -> entry.zone().id().equals(zone.id()))
                      .findFirst()
                      .map(ControlZoneDirectory.Zone::members)
                      .orElse(List.of());
              if (members.isEmpty()) {
                sender.sendMessage(locale.component("command.signal.zone.info.empty"));
                return;
              }
              Map<String, String> names =
                  provider.stations().listByOperator(found.target().operator().id()).stream()
                      .collect(
                          Collectors.toMap(
                              station -> station.code().toUpperCase(Locale.ROOT),
                              Station::name,
                              (a, b) -> a));
              String stations =
                  members.stream()
                      .filter(m -> m.kind() == ControlZoneMember.Kind.STATION)
                      .map(
                          m ->
                              Optional.ofNullable(names.get(m.code()))
                                  .map(name -> name + "（" + m.code() + "）")
                                  .orElse(m.code()))
                      .collect(Collectors.joining("、"));
              String depots =
                  members.stream()
                      .filter(m -> m.kind() == ControlZoneMember.Kind.DEPOT)
                      .map(ControlZoneMember::code)
                      .collect(Collectors.joining("、"));
              sender.sendMessage(
                  locale.component(
                      "command.signal.zone.info.stations",
                      Map.of("members", stations.isEmpty() ? "-" : stations)));
              sender.sendMessage(
                  locale.component(
                      "command.signal.zone.info.depots",
                      Map.of("members", depots.isEmpty() ? "-" : depots)));
            });
  }

  private void handleRename(CommandContext<CommandSender> ctx) {
    resolve(ctx, false)
        .flatMap(target -> resolveZone(ctx, target))
        .ifPresent(
            found -> {
              LocaleManager locale = plugin.getLocaleManager();
              ControlZoneService.Result result =
                  new ControlZoneService(found.target().provider())
                      .rename(
                          found.zone(),
                          ctx.get("name"),
                          ctx.optional("secondaryName"),
                          canManage(ctx.sender(), found.target().provider()));
              switch (result.status()) {
                case UPDATED -> {
                  plugin.refreshControlZones();
                  ControlZone zone = result.zone().orElseThrow();
                  ctx.sender()
                      .sendMessage(
                          locale.component(
                              "command.signal.zone.rename.success",
                              Map.of("code", zone.code(), "name", zone.name())));
                }
                case INVALID -> ctx.sender()
                    .sendMessage(locale.component("command.signal.zone.rename.invalid"));
                default -> ctx.sender().sendMessage(locale.component("error.no-permission"));
              }
            });
  }

  private void handleMember(CommandContext<CommandSender> ctx, boolean add) {
    resolve(ctx, false)
        .flatMap(target -> resolveZone(ctx, target))
        .ifPresent(
            found -> {
              LocaleManager locale = plugin.getLocaleManager();
              CommandSender sender = ctx.sender();
              String rawKind = ctx.<String>get("kind").trim();
              Optional<ControlZoneMember.Kind> kind = ControlZoneMember.Kind.fromToken(rawKind);
              if (kind.isEmpty()) {
                sender.sendMessage(
                    locale.component(
                        "command.signal.zone.member.invalid-kind", Map.of("kind", rawKind)));
                return;
              }
              String member = ctx.<String>get("member").trim().toUpperCase(Locale.ROOT);
              String operator = found.target().operator().code();
              Map<String, String> values =
                  Map.of(
                      "operator",
                      operator,
                      "zone",
                      found.zone().code(),
                      "kind",
                      locale.enumText("enum.control-zone-member-kind", kind.get()),
                      "member",
                      member);
              if (add
                  && kind.get() == ControlZoneMember.Kind.DEPOT
                  && !depotCodes(operator).contains(member)) {
                sender.sendMessage(
                    locale.component("command.signal.zone.member.depot-not-found", values));
                return;
              }
              ControlZoneService service = new ControlZoneService(found.target().provider());
              Predicate<UUID> canManage = canManage(sender, found.target().provider());
              ControlZoneService.Result result =
                  add
                      ? service.addMember(found.zone(), kind.get(), member, canManage)
                      : service.removeMember(found.zone(), kind.get(), member, canManage);
              String key =
                  switch (result.status()) {
                    case ADDED -> "command.signal.zone.member.added";
                    case REMOVED -> "command.signal.zone.member.removed";
                    case ALREADY_MEMBER -> "command.signal.zone.member.already";
                    case NOT_MEMBER -> "command.signal.zone.member.not-member";
                    case NOT_FOUND -> "command.signal.zone.member.station-not-found";
                    case INVALID -> "command.signal.zone.member.invalid";
                    case OVERLAP -> "command.signal.zone.member.overlap";
                    default -> "error.no-permission";
                  };
              if (result.status().changed()) {
                plugin.refreshControlZones();
              }
              Map<String, String> shown = new HashMap<>(values);
              result
                  .conflict()
                  .ifPresent(other -> shown.put("other", other.code() + "（" + other.name() + "）"));
              sender.sendMessage(locale.component(key, shown));
            });
  }

  private void handleDelete(CommandContext<CommandSender> ctx) {
    resolve(ctx, false)
        .flatMap(target -> resolveZone(ctx, target))
        .ifPresent(
            found -> {
              LocaleManager locale = plugin.getLocaleManager();
              ControlZoneService.Result result =
                  new ControlZoneService(found.target().provider())
                      .delete(found.zone(), canManage(ctx.sender(), found.target().provider()));
              if (result.status() != ControlZoneService.Status.DELETED) {
                ctx.sender().sendMessage(locale.component("error.no-permission"));
                return;
              }
              plugin.refreshControlZones();
              // 值守这片控制区的信号员随之下岗
              plugin
                  .getSignallerService()
                  .ifPresent(
                      service ->
                          service.zoneRemoved(
                              found.target().operator().code(), found.zone().code()));
              ctx.sender()
                  .sendMessage(
                      locale.component(
                          "command.signal.zone.delete.success",
                          Map.of("zone", found.zone().code())));
            });
  }

  // ─── 解析 ──────────────────────────────────────────────────────────────────

  /** 已解析的公司与运营商。 */
  private record Target(StorageProvider provider, Company company, Operator operator) {}

  /** 已解析的控制区。 */
  private record Found(Target target, ControlZone zone) {}

  /**
   * 解析公司与运营商；查询类命令（{@code read}）先确认能读取该公司，改动类命令的权限由服务判定。
   *
   * @return 存储未就绪、找不到或无权读取时为空（已提示）
   */
  private Optional<Target> resolve(CommandContext<CommandSender> ctx, boolean read) {
    CommandSender sender = ctx.sender();
    Optional<StorageProvider> providerOpt = CommandStorageProviders.readyProvider(sender, plugin);
    if (providerOpt.isEmpty()) {
      return Optional.empty();
    }
    StorageProvider provider = providerOpt.get();
    LocaleManager locale = plugin.getLocaleManager();
    CompanyQueryService query = new CompanyQueryService(provider);
    String companyArg = ctx.<String>get("company").trim();
    Optional<Company> company = query.findCompany(companyArg);
    if (company.isEmpty()) {
      sender.sendMessage(
          locale.component("command.company.info.not-found", Map.of("company", companyArg)));
      return Optional.empty();
    }
    if (read && !CompanyAccessChecker.canReadCompany(sender, provider, company.get().id())) {
      sender.sendMessage(locale.component("error.no-permission"));
      return Optional.empty();
    }
    String operatorArg = ctx.<String>get("operator").trim();
    Optional<Operator> operator = query.findOperator(company.get().id(), operatorArg);
    if (operator.isEmpty()) {
      sender.sendMessage(
          locale.component("command.operator.not-found", Map.of("operator", operatorArg)));
      return Optional.empty();
    }
    return Optional.of(new Target(provider, company.get(), operator.get()));
  }

  private Optional<Found> resolveZone(CommandContext<CommandSender> ctx, Target target) {
    String zoneArg = ctx.<String>get("zone").trim();
    Optional<ControlZone> zone =
        new ControlZoneService(target.provider()).find(target.operator(), zoneArg);
    if (zone.isEmpty()) {
      ctx.sender()
          .sendMessage(
              plugin
                  .getLocaleManager()
                  .component(
                      "command.signal.zone.not-found",
                      Map.of("operator", target.operator().code(), "zone", zoneArg)));
      return Optional.empty();
    }
    return Optional.of(new Found(target, zone.get()));
  }

  /** 调度图快照版本 → 各运营商（大写）的车库代码。 */
  private record DepotIndex(long version, Map<String, Set<String>> codes) {}

  private volatile DepotIndex depots = new DepotIndex(-1L, Map.of());

  /** 调度图里运营商的车库代码（车库股道与车库咽喉节点），各世界合并；车库在库里没有实体，只能这样核对。按调度图快照版本缓存，补全时不必每次扫全图。 */
  private Set<String> depotCodes(String operator) {
    RailGraphService graphs = plugin.getRailGraphService();
    if (graphs == null) {
      return Set.of();
    }
    DepotIndex index = depots;
    long version = graphs.snapshotVersion();
    if (index.version() != version) {
      Map<String, Set<String>> codes = new HashMap<>();
      for (RailGraphService.RailGraphSnapshot snapshot : graphs.snapshotAll().values()) {
        for (RailNode node : snapshot.graph().nodes()) {
          node.waypointMetadata()
              .filter(m -> m.kind() == WaypointKind.DEPOT || m.kind() == WaypointKind.DEPOT_THROAT)
              .ifPresent(
                  m ->
                      codes
                          .computeIfAbsent(
                              m.operator().toUpperCase(Locale.ROOT), ignored -> new TreeSet<>())
                          .add(m.originStation().toUpperCase(Locale.ROOT)));
        }
      }
      Map<String, Set<String>> frozen = new HashMap<>();
      codes.forEach((key, value) -> frozen.put(key, Set.copyOf(value)));
      index = new DepotIndex(version, Map.copyOf(frozen));
      depots = index;
    }
    return index.codes().getOrDefault(operator.toUpperCase(Locale.ROOT), Set.of());
  }

  private static long count(List<ControlZoneMember> members, ControlZoneMember.Kind kind) {
    return members.stream().filter(member -> member.kind() == kind).count();
  }

  private static Predicate<UUID> canManage(CommandSender sender, StorageProvider provider) {
    return companyId -> CompanyAccessChecker.canManageCompany(sender, provider, companyId);
  }

  // ─── 补全 ──────────────────────────────────────────────────────────────────

  private List<String> suggestCompanies(CommandContext<CommandSender> ctx, CommandInput input) {
    Optional<StorageProvider> providerOpt = CommandStorageProviders.providerIfReady(plugin);
    if (providerOpt.isEmpty()) {
      return List.of();
    }
    StorageProvider provider = providerOpt.get();
    String prefix = prefix(input);
    List<String> out = new ArrayList<>();
    if (prefix.isEmpty()) {
      out.add("<company>");
    }
    for (Company company : provider.companies().listAll()) {
      if (out.size() >= SUGGESTION_LIMIT) {
        break;
      }
      if (company != null
          && CompanyAccessChecker.canReadCompanyNoCreateIdentity(
              ctx.sender(), provider, company.id())
          && company.code().toLowerCase(Locale.ROOT).startsWith(prefix)) {
        out.add(company.code());
      }
    }
    return out;
  }

  private List<String> suggestOperators(CommandContext<CommandSender> ctx, CommandInput input) {
    Optional<StorageProvider> providerOpt = CommandStorageProviders.providerIfReady(plugin);
    Optional<Company> company =
        providerOpt.flatMap(
            provider ->
                argument(ctx, "company").flatMap(new CompanyQueryService(provider)::findCompany));
    String prefix = prefix(input);
    List<String> out = new ArrayList<>();
    if (prefix.isEmpty()) {
      out.add("<operator>");
    }
    if (company.isEmpty()
        || !CompanyAccessChecker.canReadCompanyNoCreateIdentity(
            ctx.sender(), providerOpt.get(), company.get().id())) {
      return out;
    }
    providerOpt.get().operators().listByCompany(company.get().id()).stream()
        .map(Operator::code)
        .filter(code -> code.toLowerCase(Locale.ROOT).startsWith(prefix))
        .limit(SUGGESTION_LIMIT)
        .forEach(out::add);
    return out;
  }

  /** 控制区补全：查内存目录，不读库。 */
  private List<String> suggestZones(CommandContext<CommandSender> ctx, CommandInput input) {
    String prefix = prefix(input);
    List<String> out = new ArrayList<>();
    if (prefix.isEmpty()) {
      out.add("<zone>");
    }
    argument(ctx, "operator")
        .map(plugin.getControlZoneDirectory()::listByOperator)
        .orElse(List.of())
        .stream()
        .map(ControlZoneDirectory.Zone::code)
        .filter(code -> code.toLowerCase(Locale.ROOT).startsWith(prefix))
        .limit(SUGGESTION_LIMIT)
        .forEach(out::add);
    return out;
  }

  /** add 的成员补全：车站列运营商登记的车站，车库列调度图里的车库。 */
  private List<String> suggestCandidates(CommandContext<CommandSender> ctx, CommandInput input) {
    String prefix = prefix(input);
    List<String> out = new ArrayList<>();
    if (prefix.isEmpty()) {
      out.add("<code>");
    }
    Optional<ControlZoneMember.Kind> kind =
        argument(ctx, "kind").flatMap(ControlZoneMember.Kind::fromToken);
    Optional<String> operator = argument(ctx, "operator");
    if (kind.isEmpty() || operator.isEmpty()) {
      return out;
    }
    List<String> codes =
        kind.get() == ControlZoneMember.Kind.DEPOT
            ? depotCodes(operator.get()).stream().sorted().toList()
            : stationCodes(ctx);
    codes.stream()
        .filter(code -> code.toLowerCase(Locale.ROOT).startsWith(prefix))
        .limit(SUGGESTION_LIMIT)
        .forEach(out::add);
    return out;
  }

  /** remove 的成员补全：区内这一种类的成员。 */
  private List<String> suggestMembers(CommandContext<CommandSender> ctx, CommandInput input) {
    String prefix = prefix(input);
    List<String> out = new ArrayList<>();
    if (prefix.isEmpty()) {
      out.add("<code>");
    }
    Optional<ControlZoneMember.Kind> kind =
        argument(ctx, "kind").flatMap(ControlZoneMember.Kind::fromToken);
    Optional<ControlZoneDirectory.Zone> zone =
        argument(ctx, "operator")
            .flatMap(
                operator ->
                    argument(ctx, "zone")
                        .flatMap(code -> plugin.getControlZoneDirectory().find(operator, code)));
    if (kind.isEmpty() || zone.isEmpty()) {
      return out;
    }
    zone.get().members().stream()
        .filter(member -> member.kind() == kind.get())
        .map(ControlZoneMember::code)
        .filter(code -> code.toLowerCase(Locale.ROOT).startsWith(prefix))
        .forEach(out::add);
    return out;
  }

  private List<String> stationCodes(CommandContext<CommandSender> ctx) {
    Optional<StorageProvider> providerOpt = CommandStorageProviders.providerIfReady(plugin);
    if (providerOpt.isEmpty()) {
      return List.of();
    }
    CompanyQueryService query = new CompanyQueryService(providerOpt.get());
    return argument(ctx, "company")
        .flatMap(query::findCompany)
        .flatMap(
            company ->
                argument(ctx, "operator").flatMap(op -> query.findOperator(company.id(), op)))
        .map(operator -> providerOpt.get().stations().listByOperator(operator.id()))
        .orElse(List.of())
        .stream()
        .map(Station::code)
        .toList();
  }

  private static Optional<String> argument(CommandContext<CommandSender> ctx, String name) {
    return ctx.<String>optional(name).map(String::trim).filter(value -> !value.isEmpty());
  }

  private static String prefix(CommandInput input) {
    return input == null ? "" : input.lastRemainingToken().trim().toLowerCase(Locale.ROOT);
  }
}
