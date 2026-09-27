package org.fetarute.fetaruteTCAddon.command;

import static org.fetarute.fetaruteTCAddon.command.CommandSuggestionProviders.placeholder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.bukkit.command.CommandSender;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.api.CompanyQueryService;
import org.fetarute.fetaruteTCAddon.company.api.StationDirectory;
import org.fetarute.fetaruteTCAddon.company.api.StationGroupService;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.company.model.StationGroup;
import org.fetarute.fetaruteTCAddon.company.model.StationGroupMember;
import org.fetarute.fetaruteTCAddon.company.model.StationTransferType;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.parser.flag.CommandFlag;
import org.incendo.cloud.parser.standard.IntegerParser;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.SuggestionProvider;

/**
 * /fta station group 命令：维护车站组（乘客视角的换乘站）。
 *
 * <ul>
 *   <li>create：建组（需要所属公司的管理权限）
 *   <li>add：加入/更新成员；加入其他公司的车站需要同时能管理两家公司
 *   <li>remove：移除成员（组所在公司或车站所属公司的管理者均可）
 *   <li>info：成员、各成员停靠线路、换乘方式
 *   <li>list：列出车站组
 *   <li>delete：删组（需要 --confirm）
 * </ul>
 *
 * <p>组代码可写成 {@code 公司代码:组代码} 消除歧义；运营商同理。改库之后刷新车站目录并发出公开事件。
 */
public final class FtaStationGroupCommand {

  private static final int SUGGESTION_LIMIT = 20;

  private final FetaruteTCAddon plugin;

  public FtaStationGroupCommand(FetaruteTCAddon plugin) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
  }

  public void register(CommandManager<CommandSender> manager) {
    Objects.requireNonNull(manager, "manager");

    SuggestionProvider<CommandSender> manageableCompanySuggestions =
        SuggestionProvider.blockingStrings(
            (ctx, input) ->
                suggestCompanies(
                    ctx.sender(),
                    input,
                    (sender, provider, companyId) ->
                        CompanyAccessChecker.canManageCompanyNoCreateIdentity(
                            sender, provider, companyId)));
    SuggestionProvider<CommandSender> readableCompanySuggestions =
        SuggestionProvider.blockingStrings(
            (ctx, input) ->
                suggestCompanies(
                    ctx.sender(),
                    input,
                    (sender, provider, companyId) ->
                        CompanyAccessChecker.canReadCompanyNoCreateIdentity(
                            sender, provider, companyId)));
    SuggestionProvider<CommandSender> groupSuggestions =
        SuggestionProvider.blockingStrings((ctx, input) -> suggestGroups(ctx.sender(), input));
    SuggestionProvider<CommandSender> operatorSuggestions =
        SuggestionProvider.blockingStrings((ctx, input) -> suggestOperators(ctx, input));
    SuggestionProvider<CommandSender> stationSuggestions =
        SuggestionProvider.blockingStrings((ctx, input) -> suggestStations(ctx, input));
    SuggestionProvider<CommandSender> memberOperatorSuggestions =
        SuggestionProvider.blockingStrings((ctx, input) -> suggestMemberOperators(ctx, input));
    SuggestionProvider<CommandSender> memberStationSuggestions =
        SuggestionProvider.blockingStrings((ctx, input) -> suggestMemberStations(ctx, input));
    SuggestionProvider<CommandSender> transferSuggestions =
        CommandSuggestionProviders.enumValues(StationTransferType.class, "<transferType>");

    var confirmFlag = CommandFlag.builder("confirm").build();

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("station")
            .literal("group")
            .literal("create")
            .required("company", StringParser.quotedStringParser(), manageableCompanySuggestions)
            .required("code", StringParser.stringParser(), placeholder("<code>"))
            .required("name", StringParser.quotedStringParser(), placeholder("\"<name>\""))
            .optional(
                "secondaryName",
                StringParser.quotedStringParser(),
                placeholder("\"<secondaryName>\""))
            .handler(this::handleCreate));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("station")
            .literal("group")
            .literal("add")
            .required("group", StringParser.stringParser(), groupSuggestions)
            .required("operator", StringParser.stringParser(), operatorSuggestions)
            .required("station", StringParser.quotedStringParser(), stationSuggestions)
            .optional("transferType", StringParser.stringParser(), transferSuggestions)
            .optional("walkSecs", IntegerParser.integerParser(0), placeholder("<walkSecs>"))
            .handler(this::handleAdd));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("station")
            .literal("group")
            .literal("remove")
            .required("group", StringParser.stringParser(), groupSuggestions)
            .required("operator", StringParser.stringParser(), memberOperatorSuggestions)
            .required("station", StringParser.quotedStringParser(), memberStationSuggestions)
            .handler(this::handleRemove));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("station")
            .literal("group")
            .literal("info")
            .required("group", StringParser.stringParser(), groupSuggestions)
            .handler(this::handleInfo));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("station")
            .literal("group")
            .literal("list")
            .optional("company", StringParser.quotedStringParser(), readableCompanySuggestions)
            .handler(this::handleList));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("station")
            .literal("group")
            .literal("delete")
            .required("group", StringParser.stringParser(), groupSuggestions)
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

  // ─── 处理器 ─────────────────────────────────────────────────────────────────

  private void handleCreate(CommandContext<CommandSender> ctx) {
    CommandSender sender = ctx.sender();
    Optional<StorageProvider> providerOpt = readyProvider(sender);
    if (providerOpt.isEmpty()) {
      return;
    }
    StorageProvider provider = providerOpt.get();
    LocaleManager locale = plugin.getLocaleManager();
    String companyArg = ctx.<String>get("company").trim();
    Optional<Company> companyOpt = new CompanyQueryService(provider).findCompany(companyArg);
    if (companyOpt.isEmpty()) {
      sender.sendMessage(
          locale.component("command.company.info.not-found", Map.of("company", companyArg)));
      return;
    }
    Company company = companyOpt.get();
    String code = ctx.<String>get("code").trim();
    StationGroupService.Result result =
        new StationGroupService(provider)
            .create(
                company,
                code,
                ctx.<String>get("name"),
                ctx.<String>optional("secondaryName"),
                canManage(sender, provider));
    switch (result.status()) {
      case CREATED -> {
        StationGroup group = result.group().orElseThrow();
        result.change().ifPresent(plugin::notifyStationGroupChanged);
        sender.sendMessage(
            locale.component(
                "command.station.group.create.success",
                Map.of("company", company.code(), "code", group.code(), "name", group.name())));
      }
      case DUPLICATE -> sender.sendMessage(
          locale.component(
              "command.station.group.create.exists",
              Map.of("company", company.code(), "code", code)));
      case INVALID -> sender.sendMessage(locale.component("command.station.group.create.invalid"));
      default -> sender.sendMessage(locale.component("error.no-permission"));
    }
  }

  private void handleAdd(CommandContext<CommandSender> ctx) {
    CommandSender sender = ctx.sender();
    Optional<StorageProvider> providerOpt = readyProvider(sender);
    if (providerOpt.isEmpty()) {
      return;
    }
    StorageProvider provider = providerOpt.get();
    LocaleManager locale = plugin.getLocaleManager();
    StationGroupService service = new StationGroupService(provider);
    Optional<StationGroup> groupOpt = resolveGroup(sender, provider, service, ctx.get("group"));
    if (groupOpt.isEmpty()) {
      return;
    }
    StationGroup group = groupOpt.get();
    Optional<Station> stationOpt =
        resolveStation(sender, provider, service, group, ctx.get("operator"), ctx.get("station"));
    if (stationOpt.isEmpty()) {
      return;
    }
    Station station = stationOpt.get();

    StationTransferType transferType = StationTransferType.IN_STATION;
    Optional<String> rawType = ctx.optional("transferType");
    if (rawType.isPresent()) {
      Optional<StationTransferType> parsed = StationTransferType.fromToken(rawType.get());
      if (parsed.isEmpty()) {
        sender.sendMessage(
            locale.component(
                "command.station.group.add.invalid-type", Map.of("type", rawType.get())));
        return;
      }
      transferType = parsed.get();
    }
    Optional<Integer> walkSeconds = ctx.optional("walkSecs");

    StationGroupService.Result result =
        service.addMember(group, station, transferType, walkSeconds, canManage(sender, provider));
    Map<String, String> placeholders = new HashMap<>();
    placeholders.put("group", group.code());
    placeholders.put("station", station.code());
    placeholders.put("name", station.name());
    placeholders.put("type", transferText(locale, transferType));
    placeholders.put("walk", walkText(locale, walkSeconds));
    switch (result.status()) {
      case ADDED, UPDATED -> {
        result.change().ifPresent(plugin::notifyStationGroupChanged);
        String key =
            result.status() == StationGroupService.Status.ADDED
                ? "command.station.group.add.success"
                : "command.station.group.add.updated";
        sender.sendMessage(locale.component(key, placeholders));
      }
      case IN_OTHER_GROUP -> {
        placeholders.put("other", result.group().map(StationGroup::code).orElse("?"));
        sender.sendMessage(
            locale.component("command.station.group.add.in-other-group", placeholders));
      }
      case CROSS_COMPANY_DENIED -> {
        placeholders.put("company", companyCode(provider, result.stationCompanyId()));
        sender.sendMessage(
            locale.component("command.station.group.add.cross-company-denied", placeholders));
      }
      case INVALID -> sender.sendMessage(
          locale.component("command.station.group.add.invalid-station", placeholders));
      default -> sender.sendMessage(locale.component("error.no-permission"));
    }
  }

  private void handleRemove(CommandContext<CommandSender> ctx) {
    CommandSender sender = ctx.sender();
    Optional<StorageProvider> providerOpt = readyProvider(sender);
    if (providerOpt.isEmpty()) {
      return;
    }
    StorageProvider provider = providerOpt.get();
    LocaleManager locale = plugin.getLocaleManager();
    StationGroupService service = new StationGroupService(provider);
    Optional<StationGroup> groupOpt = resolveGroup(sender, provider, service, ctx.get("group"));
    if (groupOpt.isEmpty()) {
      return;
    }
    StationGroup group = groupOpt.get();
    Optional<Station> stationOpt =
        resolveStation(sender, provider, service, group, ctx.get("operator"), ctx.get("station"));
    if (stationOpt.isEmpty()) {
      return;
    }
    Station station = stationOpt.get();
    StationGroupService.Result result =
        service.removeMember(group, station, canManage(sender, provider));
    Map<String, String> placeholders = Map.of("group", group.code(), "station", station.code());
    switch (result.status()) {
      case REMOVED -> {
        result.change().ifPresent(plugin::notifyStationGroupChanged);
        sender.sendMessage(locale.component("command.station.group.remove.success", placeholders));
      }
      case NOT_MEMBER -> sender.sendMessage(
          locale.component("command.station.group.remove.not-member", placeholders));
      default -> sender.sendMessage(locale.component("error.no-permission"));
    }
  }

  private void handleDelete(CommandContext<CommandSender> ctx) {
    CommandSender sender = ctx.sender();
    Optional<StorageProvider> providerOpt = readyProvider(sender);
    if (providerOpt.isEmpty()) {
      return;
    }
    StorageProvider provider = providerOpt.get();
    LocaleManager locale = plugin.getLocaleManager();
    StationGroupService service = new StationGroupService(provider);
    Optional<StationGroup> groupOpt = resolveGroup(sender, provider, service, ctx.get("group"));
    if (groupOpt.isEmpty()) {
      return;
    }
    StationGroup group = groupOpt.get();
    StationGroupService.Result result = service.delete(group, canManage(sender, provider));
    if (result.status() != StationGroupService.Status.DELETED) {
      sender.sendMessage(locale.component("error.no-permission"));
      return;
    }
    result.change().ifPresent(plugin::notifyStationGroupChanged);
    sender.sendMessage(
        locale.component("command.station.group.delete.success", Map.of("group", group.code())));
  }

  private void handleInfo(CommandContext<CommandSender> ctx) {
    CommandSender sender = ctx.sender();
    Optional<StorageProvider> providerOpt = readyProvider(sender);
    if (providerOpt.isEmpty()) {
      return;
    }
    StorageProvider provider = providerOpt.get();
    LocaleManager locale = plugin.getLocaleManager();
    StationGroupService service = new StationGroupService(provider);
    Optional<StationGroup> groupOpt = resolveGroup(sender, provider, service, ctx.get("group"));
    if (groupOpt.isEmpty()) {
      return;
    }
    StationGroup group = groupOpt.get();
    if (!CompanyAccessChecker.canReadCompany(sender, provider, group.companyId())) {
      sender.sendMessage(locale.component("error.no-permission"));
      return;
    }
    sender.sendMessage(
        locale.component(
            "command.station.group.info.header",
            Map.of(
                "company",
                companyCode(provider, Optional.of(group.companyId())),
                "code",
                group.code(),
                "name",
                group.name())));
    sender.sendMessage(
        locale.component(
            "command.station.group.info.secondary",
            Map.of("secondary", group.secondaryName().orElse("-"))));
    List<StationGroupMember> members = provider.stationGroups().listMembers(group.id());
    if (members.isEmpty()) {
      sender.sendMessage(locale.component("command.station.group.info.empty"));
      return;
    }
    StationDirectory.Snapshot snapshot =
        plugin
            .getStationDirectory()
            .map(StationDirectory::snapshot)
            .orElseGet(StationDirectory::detachedSnapshot);
    Map<UUID, String> operatorCodes = new HashMap<>();
    int index = 1;
    for (StationGroupMember member : members) {
      Optional<Station> stationOpt = provider.stations().findById(member.stationId());
      if (stationOpt.isEmpty()) {
        continue;
      }
      Station station = stationOpt.get();
      String operatorCode =
          operatorCodes.computeIfAbsent(
              station.operatorId(),
              id -> provider.operators().findById(id).map(Operator::code).orElse("?"));
      sender.sendMessage(
          locale.component(
              "command.station.group.info.member",
              Map.of(
                  "index",
                  String.valueOf(index++),
                  "operator",
                  operatorCode,
                  "station",
                  station.code(),
                  "name",
                  station.name(),
                  "type",
                  transferText(locale, member.transferType()),
                  "walk",
                  walkText(locale, member.walkSeconds()))));
      List<StationDirectory.LineAtStation> lines = snapshot.linesAt(station.id());
      if (lines.isEmpty()) {
        sender.sendMessage(locale.component("command.station.group.info.member-no-lines"));
      } else {
        String text =
            lines.stream()
                .map(line -> line.operator().code() + ":" + line.line().code())
                .collect(Collectors.joining(", "));
        sender.sendMessage(
            locale.component("command.station.group.info.member-lines", Map.of("lines", text)));
      }
    }
  }

  private void handleList(CommandContext<CommandSender> ctx) {
    CommandSender sender = ctx.sender();
    Optional<StorageProvider> providerOpt = readyProvider(sender);
    if (providerOpt.isEmpty()) {
      return;
    }
    StorageProvider provider = providerOpt.get();
    LocaleManager locale = plugin.getLocaleManager();
    Optional<String> companyArg = ctx.<String>optional("company").map(String::trim);
    List<Company> companies = new ArrayList<>();
    if (companyArg.isPresent()) {
      Optional<Company> companyOpt =
          new CompanyQueryService(provider).findCompany(companyArg.get());
      if (companyOpt.isEmpty()) {
        sender.sendMessage(
            locale.component(
                "command.company.info.not-found", Map.of("company", companyArg.get())));
        return;
      }
      if (!CompanyAccessChecker.canReadCompany(sender, provider, companyOpt.get().id())) {
        sender.sendMessage(locale.component("error.no-permission"));
        return;
      }
      companies.add(companyOpt.get());
    } else {
      for (Company company : provider.companies().listAll()) {
        if (company != null
            && CompanyAccessChecker.canReadCompanyNoCreateIdentity(
                sender, provider, company.id())) {
          companies.add(company);
        }
      }
    }
    sender.sendMessage(
        locale.component(
            "command.station.group.list.header",
            Map.of(
                "company",
                companyArg.orElseGet(() -> locale.text("command.station.group.list.all")))));
    int count = 0;
    for (Company company : companies) {
      for (StationGroup group : provider.stationGroups().listByCompany(company.id())) {
        int members = provider.stationGroups().listMembers(group.id()).size();
        sender.sendMessage(
            locale.component(
                "command.station.group.list.entry",
                Map.of(
                    "company",
                    company.code(),
                    "code",
                    group.code(),
                    "name",
                    group.name(),
                    "count",
                    String.valueOf(members))));
        count++;
      }
    }
    if (count == 0) {
      sender.sendMessage(locale.component("command.station.group.list.empty"));
    }
  }

  // ─── 解析 ──────────────────────────────────────────────────────────────────

  private Optional<StationGroup> resolveGroup(
      CommandSender sender, StorageProvider provider, StationGroupService service, String raw) {
    LocaleManager locale = plugin.getLocaleManager();
    String arg = raw == null ? "" : raw.trim();
    StationGroupService.Lookup<StationGroup> lookup = service.findGroup(arg);
    if (lookup.ambiguous()) {
      sender.sendMessage(
          locale.component(
              "command.station.group.ambiguous",
              Map.of("group", arg, "candidates", qualifiedGroups(provider, lookup.matches()))));
      return Optional.empty();
    }
    Optional<StationGroup> group = lookup.unique();
    if (group.isEmpty()) {
      sender.sendMessage(locale.component("command.station.group.not-found", Map.of("group", arg)));
    }
    return group;
  }

  private Optional<Station> resolveStation(
      CommandSender sender,
      StorageProvider provider,
      StationGroupService service,
      StationGroup group,
      String operatorRaw,
      String stationRaw) {
    LocaleManager locale = plugin.getLocaleManager();
    String operatorArg = operatorRaw == null ? "" : operatorRaw.trim();
    StationGroupService.Lookup<Operator> lookup =
        service.findOperator(operatorArg, Optional.of(group.companyId()));
    if (lookup.ambiguous()) {
      String candidates =
          lookup.matches().stream()
              .map(op -> companyCode(provider, Optional.of(op.companyId())) + ":" + op.code())
              .collect(Collectors.joining(", "));
      sender.sendMessage(
          locale.component(
              "command.station.group.operator-ambiguous",
              Map.of("operator", operatorArg, "candidates", candidates)));
      return Optional.empty();
    }
    Optional<Operator> operatorOpt = lookup.unique();
    if (operatorOpt.isEmpty()) {
      sender.sendMessage(
          locale.component("command.operator.not-found", Map.of("operator", operatorArg)));
      return Optional.empty();
    }
    Operator operator = operatorOpt.get();
    String stationArg = stationRaw == null ? "" : stationRaw.trim();
    Optional<Station> station =
        new CompanyQueryService(provider).findStation(operator.id(), stationArg);
    if (station.isEmpty()) {
      station = findStationIgnoreCase(provider, operator.id(), stationArg);
    }
    if (station.isEmpty()) {
      sender.sendMessage(
          locale.component(
              "command.station.not-found",
              Map.of("operator", operator.code(), "station", stationArg)));
    }
    return station;
  }

  private static Optional<Station> findStationIgnoreCase(
      StorageProvider provider, UUID operatorId, String code) {
    for (Station station : provider.stations().listByOperator(operatorId)) {
      if (station != null && station.code().equalsIgnoreCase(code)) {
        return Optional.of(station);
      }
    }
    return Optional.empty();
  }

  private static String qualifiedGroups(StorageProvider provider, List<StationGroup> groups) {
    return groups.stream()
        .map(group -> companyCode(provider, Optional.of(group.companyId())) + ":" + group.code())
        .collect(Collectors.joining(", "));
  }

  private static String companyCode(StorageProvider provider, Optional<UUID> companyId) {
    return companyId
        .flatMap(id -> provider.companies().findById(id))
        .map(Company::code)
        .orElse("?");
  }

  /** 步行时间的显示文本：「90 秒」；未设置时为「未设置」。 */
  private static String walkText(LocaleManager locale, Optional<Integer> walkSeconds) {
    return walkSeconds
        .map(
            seconds ->
                locale
                    .text("command.station.group.walk.seconds")
                    .replace("{seconds}", String.valueOf(seconds)))
        .orElseGet(() -> locale.text("command.station.group.walk.none"));
  }

  private static String transferText(LocaleManager locale, StationTransferType type) {
    return locale.enumText("enum.station-transfer-type", type);
  }

  private static Predicate<UUID> canManage(CommandSender sender, StorageProvider provider) {
    return companyId -> CompanyAccessChecker.canManageCompany(sender, provider, companyId);
  }

  private Optional<StorageProvider> readyProvider(CommandSender sender) {
    return CommandStorageProviders.readyProvider(sender, plugin);
  }

  // ─── 补全 ──────────────────────────────────────────────────────────────────

  @FunctionalInterface
  private interface CompanyFilter {
    boolean test(CommandSender sender, StorageProvider provider, UUID companyId);
  }

  private List<String> suggestCompanies(
      CommandSender sender, CommandInput input, CompanyFilter filter) {
    Optional<StorageProvider> providerOpt = CommandStorageProviders.providerIfReady(plugin);
    if (providerOpt.isEmpty()) {
      return List.of();
    }
    StorageProvider provider = providerOpt.get();
    String prefix = normalizeLowerPrefix(input);
    List<String> out = new ArrayList<>();
    if (prefix.isEmpty()) {
      out.add("<company>");
    }
    for (Company company : provider.companies().listAll()) {
      if (company == null || !filter.test(sender, provider, company.id())) {
        continue;
      }
      if (prefix.isEmpty() || company.code().toLowerCase(Locale.ROOT).startsWith(prefix)) {
        out.add(company.code());
      }
      if (out.size() >= SUGGESTION_LIMIT) {
        break;
      }
    }
    return out;
  }

  /** 组代码补全：只列能读取的公司的组；同代码出现在多家公司时补全成 {@code 公司代码:组代码}。 */
  private List<String> suggestGroups(CommandSender sender, CommandInput input) {
    Optional<StorageProvider> providerOpt = CommandStorageProviders.providerIfReady(plugin);
    if (providerOpt.isEmpty()) {
      return List.of();
    }
    StorageProvider provider = providerOpt.get();
    String prefix = normalizeLowerPrefix(input);
    Map<String, List<String>> byCode = new LinkedHashMap<>();
    for (Company company : provider.companies().listAll()) {
      if (company == null
          || !CompanyAccessChecker.canReadCompanyNoCreateIdentity(sender, provider, company.id())) {
        continue;
      }
      for (StationGroup group : provider.stationGroups().listByCompany(company.id())) {
        byCode
            .computeIfAbsent(group.code().toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
            .add(company.code() + ":" + group.code());
      }
    }
    List<String> out = new ArrayList<>();
    if (prefix.isEmpty()) {
      out.add("<group>");
    }
    for (List<String> qualified : byCode.values()) {
      List<String> candidates =
          qualified.size() == 1
              ? List.of(qualified.get(0).substring(qualified.get(0).indexOf(':') + 1))
              : qualified;
      for (String candidate : candidates) {
        String lower = candidate.toLowerCase(Locale.ROOT);
        if (prefix.isEmpty() || lower.startsWith(prefix)) {
          out.add(candidate);
        }
      }
      if (out.size() >= SUGGESTION_LIMIT) {
        break;
      }
    }
    return out;
  }

  /** add 的运营商补全：能管理的公司名下的运营商（加入车站需要管理车站所属公司）。 */
  private List<String> suggestOperators(CommandContext<CommandSender> ctx, CommandInput input) {
    Optional<StorageProvider> providerOpt = CommandStorageProviders.providerIfReady(plugin);
    if (providerOpt.isEmpty()) {
      return List.of();
    }
    StorageProvider provider = providerOpt.get();
    String prefix = normalizeLowerPrefix(input);
    Map<String, List<String>> byCode = new LinkedHashMap<>();
    for (Company company : provider.companies().listAll()) {
      if (company == null
          || !CompanyAccessChecker.canManageCompanyNoCreateIdentity(
              ctx.sender(), provider, company.id())) {
        continue;
      }
      for (Operator operator : provider.operators().listByCompany(company.id())) {
        byCode
            .computeIfAbsent(operator.code().toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
            .add(company.code() + ":" + operator.code());
      }
    }
    List<String> out = new ArrayList<>();
    if (prefix.isEmpty()) {
      out.add("<operator>");
    }
    for (List<String> qualified : byCode.values()) {
      List<String> candidates =
          qualified.size() == 1
              ? List.of(qualified.get(0).substring(qualified.get(0).indexOf(':') + 1))
              : qualified;
      for (String candidate : candidates) {
        if (prefix.isEmpty() || candidate.toLowerCase(Locale.ROOT).startsWith(prefix)) {
          out.add(candidate);
        }
      }
      if (out.size() >= SUGGESTION_LIMIT) {
        break;
      }
    }
    return out;
  }

  private List<String> suggestStations(CommandContext<CommandSender> ctx, CommandInput input) {
    Optional<StorageProvider> providerOpt = CommandStorageProviders.providerIfReady(plugin);
    if (providerOpt.isEmpty()) {
      return List.of();
    }
    StorageProvider provider = providerOpt.get();
    Optional<Operator> operator = contextOperator(ctx, provider);
    if (operator.isEmpty()) {
      return List.of("<station>");
    }
    String prefix = normalizeLowerPrefix(input);
    List<String> out = new ArrayList<>();
    if (prefix.isEmpty()) {
      out.add("<station>");
    }
    for (Station station : provider.stations().listByOperator(operator.get().id())) {
      if (station == null) {
        continue;
      }
      if (prefix.isEmpty() || station.code().toLowerCase(Locale.ROOT).startsWith(prefix)) {
        out.add(station.code());
      }
      if (out.size() >= SUGGESTION_LIMIT) {
        break;
      }
    }
    return out;
  }

  /** remove 的运营商补全：组内成员的运营商。 */
  private List<String> suggestMemberOperators(
      CommandContext<CommandSender> ctx, CommandInput input) {
    List<Station> members = contextMembers(ctx);
    Optional<StorageProvider> providerOpt = CommandStorageProviders.providerIfReady(plugin);
    if (providerOpt.isEmpty()) {
      return List.of();
    }
    String prefix = normalizeLowerPrefix(input);
    List<String> out = new ArrayList<>();
    if (prefix.isEmpty()) {
      out.add("<operator>");
    }
    Set<UUID> seen = new HashSet<>();
    for (Station station : members) {
      if (!seen.add(station.operatorId())) {
        continue;
      }
      providerOpt
          .get()
          .operators()
          .findById(station.operatorId())
          .map(Operator::code)
          .filter(code -> prefix.isEmpty() || code.toLowerCase(Locale.ROOT).startsWith(prefix))
          .ifPresent(out::add);
    }
    return out;
  }

  /** remove 的车站补全：组内属于所选运营商的成员。 */
  private List<String> suggestMemberStations(
      CommandContext<CommandSender> ctx, CommandInput input) {
    Optional<StorageProvider> providerOpt = CommandStorageProviders.providerIfReady(plugin);
    if (providerOpt.isEmpty()) {
      return List.of();
    }
    Optional<Operator> operator = contextOperator(ctx, providerOpt.get());
    String prefix = normalizeLowerPrefix(input);
    List<String> out = new ArrayList<>();
    if (prefix.isEmpty()) {
      out.add("<station>");
    }
    for (Station station : contextMembers(ctx)) {
      if (operator.isPresent() && !station.operatorId().equals(operator.get().id())) {
        continue;
      }
      if (prefix.isEmpty() || station.code().toLowerCase(Locale.ROOT).startsWith(prefix)) {
        out.add(station.code());
      }
    }
    return out;
  }

  private Optional<StationGroup> contextGroup(
      CommandContext<CommandSender> ctx, StorageProvider provider) {
    String groupArg;
    try {
      groupArg = ctx.get("group");
    } catch (Exception e) {
      return Optional.empty();
    }
    if (groupArg == null || groupArg.isBlank()) {
      return Optional.empty();
    }
    return new StationGroupService(provider).findGroup(groupArg).unique();
  }

  private Optional<Operator> contextOperator(
      CommandContext<CommandSender> ctx, StorageProvider provider) {
    String operatorArg;
    try {
      operatorArg = ctx.get("operator");
    } catch (Exception e) {
      return Optional.empty();
    }
    if (operatorArg == null || operatorArg.isBlank()) {
      return Optional.empty();
    }
    Optional<UUID> preferred = contextGroup(ctx, provider).map(StationGroup::companyId);
    return new StationGroupService(provider).findOperator(operatorArg, preferred).unique();
  }

  private List<Station> contextMembers(CommandContext<CommandSender> ctx) {
    Optional<StorageProvider> providerOpt = CommandStorageProviders.providerIfReady(plugin);
    if (providerOpt.isEmpty()) {
      return List.of();
    }
    StorageProvider provider = providerOpt.get();
    Optional<StationGroup> group = contextGroup(ctx, provider);
    if (group.isEmpty()) {
      return List.of();
    }
    List<Station> stations = new ArrayList<>();
    for (StationGroupMember member : provider.stationGroups().listMembers(group.get().id())) {
      provider.stations().findById(member.stationId()).ifPresent(stations::add);
    }
    return stations;
  }

  private static String normalizeLowerPrefix(CommandInput input) {
    if (input == null) {
      return "";
    }
    return input.lastRemainingToken().trim().toLowerCase(Locale.ROOT);
  }
}
