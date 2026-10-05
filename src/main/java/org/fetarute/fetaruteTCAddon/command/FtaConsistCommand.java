package org.fetarute.fetaruteTCAddon.command;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.api.CompanyQueryService;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.dispatcher.consist.ConsistOverrideConflicts;
import org.fetarute.fetaruteTCAddon.dispatcher.consist.ConsistOverrides;
import org.fetarute.fetaruteTCAddon.dispatcher.consist.ConsistPlan;
import org.fetarute.fetaruteTCAddon.dispatcher.consist.ConsistPlanBook;
import org.fetarute.fetaruteTCAddon.dispatcher.consist.ConsistPlanService;
import org.fetarute.fetaruteTCAddon.dispatcher.consist.ConsistProfile;
import org.fetarute.fetaruteTCAddon.dispatcher.consist.ConsistProfiles;
import org.fetarute.fetaruteTCAddon.dispatcher.consist.ResolvedConsistPlan;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.parser.flag.CommandFlag;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.SuggestionProvider;

/**
 * 编组方案命令入口：/fta consist ...
 *
 * <p>方案挂在 {@code <company> <operator>} 下，用书与笔编辑（交互与 {@code /fta template} 相同）：{@code create} 发空白书、
 * {@code edit} 把已有方案载入书、{@code define} 读手上的书校验后保存。route 用 {@code /fta route set --consist-plan} 绑定。
 */
public final class FtaConsistCommand {

  private static final int SUGGESTION_LIMIT = 20;
  private static final int BOOK_MAX_LINES_PER_PAGE = 11;
  private static final PlainTextComponentSerializer PLAIN_TEXT =
      PlainTextComponentSerializer.plainText();

  private final FetaruteTCAddon plugin;
  private final NamespacedKey bookMarkerKey;
  private final NamespacedKey bookCompanyKey;
  private final NamespacedKey bookOperatorKey;
  private final NamespacedKey bookNameKey;

  public FtaConsistCommand(FetaruteTCAddon plugin) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.bookMarkerKey = new NamespacedKey(plugin, "consist_editor_marker");
    this.bookCompanyKey = new NamespacedKey(plugin, "consist_company");
    this.bookOperatorKey = new NamespacedKey(plugin, "consist_operator");
    this.bookNameKey = new NamespacedKey(plugin, "consist_name");
  }

  /** 注册 /fta consist 子命令。 */
  public void register(CommandManager<CommandSender> manager) {
    CommandFlag<Void> confirmFlag = CommandFlag.builder("confirm").build();
    SuggestionProvider<CommandSender> companySuggestions = companySuggestions();
    SuggestionProvider<CommandSender> operatorSuggestions = operatorSuggestions();
    SuggestionProvider<CommandSender> planSuggestions = planSuggestions();

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("consist")
            .literal("create")
            .senderType(Player.class)
            .required("company", StringParser.quotedStringParser(), companySuggestions)
            .required("operator", StringParser.quotedStringParser(), operatorSuggestions)
            .required(
                "name",
                StringParser.quotedStringParser(),
                SuggestionProvider.suggestingStrings("<name>"))
            .handler(
                ctx -> {
                  Player sender = (Player) ctx.sender();
                  LocaleManager locale = plugin.getLocaleManager();
                  Optional<Scope> scope =
                      resolveScope(sender, ctx.get("company"), ctx.get("operator"), true);
                  if (scope.isEmpty()) {
                    return;
                  }
                  String name = ((String) ctx.get("name")).trim();
                  if (!validName(name)) {
                    sender.sendMessage(locale.component("command.consist.invalid-name"));
                    return;
                  }
                  if (scope
                      .get()
                      .provider()
                      .consistPlans()
                      .findByOperatorAndName(scope.get().operator().id(), name)
                      .isPresent()) {
                    sender.sendMessage(
                        locale.component("command.consist.create.exists", Map.of("name", name)));
                    return;
                  }
                  giveBook(
                      sender, locale, scope.get(), name, locale.stringList(emptyTemplateKey()));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("consist")
            .literal("edit")
            .senderType(Player.class)
            .required("company", StringParser.quotedStringParser(), companySuggestions)
            .required("operator", StringParser.quotedStringParser(), operatorSuggestions)
            .required("name", StringParser.quotedStringParser(), planSuggestions)
            .handler(
                ctx -> {
                  Player sender = (Player) ctx.sender();
                  LocaleManager locale = plugin.getLocaleManager();
                  Optional<Scope> scope =
                      resolveScope(sender, ctx.get("company"), ctx.get("operator"), true);
                  if (scope.isEmpty()) {
                    return;
                  }
                  Optional<ConsistPlan> plan = findPlan(sender, scope.get(), ctx.get("name"));
                  if (plan.isEmpty()) {
                    return;
                  }
                  giveBook(
                      sender,
                      locale,
                      scope.get(),
                      plan.get().name(),
                      List.of(plan.get().body().split("\\R", -1)));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("consist")
            .literal("define")
            .senderType(Player.class)
            .handler(ctx -> define((Player) ctx.sender())));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("consist")
            .literal("info")
            .required("company", StringParser.quotedStringParser(), companySuggestions)
            .required("operator", StringParser.quotedStringParser(), operatorSuggestions)
            .required("name", StringParser.quotedStringParser(), planSuggestions)
            .handler(
                ctx -> {
                  CommandSender sender = ctx.sender();
                  LocaleManager locale = plugin.getLocaleManager();
                  Optional<Scope> scope =
                      resolveScope(sender, ctx.get("company"), ctx.get("operator"), false);
                  if (scope.isEmpty()) {
                    return;
                  }
                  Optional<ConsistPlan> plan = findPlan(sender, scope.get(), ctx.get("name"));
                  Optional<ConsistPlanService> service = plugin.getConsistPlanService();
                  if (plan.isEmpty()) {
                    return;
                  }
                  if (service.isEmpty()) {
                    sender.sendMessage(locale.component("command.consist.not-ready"));
                    return;
                  }
                  sender.sendMessage(
                      locale.component(
                          "command.consist.info.header",
                          Map.of(
                              "name",
                              plan.get().name(),
                              "operator",
                              scope.get().operator().code(),
                              "updated",
                              plan.get().updatedAt().toString())));
                  ResolvedConsistPlan resolved = service.get().resolve(plan.get());
                  for (ResolvedConsistPlan.Member member : resolved.members()) {
                    sendMember(sender, locale, member.weight(), member.entry().pattern(), member);
                  }
                  List<String> routes = boundRoutes(scope.get(), plan.get().name());
                  sender.sendMessage(
                      routes.isEmpty()
                          ? locale.component("command.consist.info.routes-none")
                          : locale.component(
                              "command.consist.info.routes",
                              Map.of("routes", String.join(", ", routes))));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("consist")
            .literal("list")
            .required("company", StringParser.quotedStringParser(), companySuggestions)
            .required("operator", StringParser.quotedStringParser(), operatorSuggestions)
            .handler(
                ctx -> {
                  CommandSender sender = ctx.sender();
                  LocaleManager locale = plugin.getLocaleManager();
                  Optional<Scope> scope =
                      resolveScope(sender, ctx.get("company"), ctx.get("operator"), false);
                  if (scope.isEmpty()) {
                    return;
                  }
                  List<ConsistPlan> plans =
                      scope
                          .get()
                          .provider()
                          .consistPlans()
                          .listByOperator(scope.get().operator().id());
                  if (plans.isEmpty()) {
                    sender.sendMessage(locale.component("command.consist.list.empty"));
                    return;
                  }
                  sender.sendMessage(
                      locale.component(
                          "command.consist.list.header",
                          Map.of(
                              "operator",
                              scope.get().operator().code(),
                              "count",
                              String.valueOf(plans.size()))));
                  for (ConsistPlan plan : plans) {
                    sender.sendMessage(
                        locale.component(
                            "command.consist.list.entry",
                            Map.of(
                                "name",
                                plan.name(),
                                "count",
                                String.valueOf(
                                    ConsistPlanBook.parse(plan.body()).entries().size()))));
                  }
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("consist")
            .literal("delete")
            .required("company", StringParser.quotedStringParser(), companySuggestions)
            .required("operator", StringParser.quotedStringParser(), operatorSuggestions)
            .required("name", StringParser.quotedStringParser(), planSuggestions)
            .flag(confirmFlag)
            .handler(
                ctx -> {
                  CommandSender sender = ctx.sender();
                  LocaleManager locale = plugin.getLocaleManager();
                  if (!ctx.flags().isPresent(confirmFlag)) {
                    sender.sendMessage(locale.component("command.common.confirm-required"));
                    return;
                  }
                  Optional<Scope> scope =
                      resolveScope(sender, ctx.get("company"), ctx.get("operator"), true);
                  if (scope.isEmpty()) {
                    return;
                  }
                  Optional<ConsistPlan> plan = findPlan(sender, scope.get(), ctx.get("name"));
                  if (plan.isEmpty()) {
                    return;
                  }
                  List<String> routes = boundRoutes(scope.get(), plan.get().name());
                  if (!routes.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.consist.delete.bound",
                            Map.of("routes", String.join(", ", routes))));
                    return;
                  }
                  scope.get().provider().consistPlans().delete(plan.get().id());
                  plugin.getConsistPlanService().ifPresent(ConsistPlanService::reload);
                  sender.sendMessage(
                      locale.component(
                          "command.consist.delete.success", Map.of("name", plan.get().name())));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("consist")
            .literal("profile")
            .required(
                "pattern",
                StringParser.greedyStringParser(),
                SuggestionProvider.suggestingStrings("<pattern>"))
            .handler(
                ctx -> {
                  CommandSender sender = ctx.sender();
                  LocaleManager locale = plugin.getLocaleManager();
                  Optional<ConsistPlanService> service = plugin.getConsistPlanService();
                  if (service.isEmpty()) {
                    sender.sendMessage(locale.component("command.consist.not-ready"));
                    return;
                  }
                  String pattern = ((String) ctx.get("pattern")).trim();
                  ConsistProfiles.Resolution resolution =
                      service.get().profile(pattern, ConsistOverrides.NONE);
                  sender.sendMessage(locale.component("command.consist.profile.header"));
                  sendResolution(sender, locale, 1, pattern, resolution, false);
                  sendIssues(sender, locale, pattern, resolution);
                }));
  }

  /** 读手上的方案书：文本检查 → 档案检查 → 覆盖项一致性检查 → 保存并重载。 */
  private void define(Player sender) {
    LocaleManager locale = plugin.getLocaleManager();
    ItemStack item = sender.getInventory().getItemInMainHand();
    if (item.getType() != Material.WRITABLE_BOOK && item.getType() != Material.WRITTEN_BOOK) {
      sender.sendMessage(locale.component("command.consist.define.book.missing"));
      return;
    }
    if (!(item.getItemMeta() instanceof BookMeta meta)) {
      sender.sendMessage(locale.component("command.consist.define.book.invalid"));
      return;
    }
    Optional<BookContext> context = readBookContext(meta);
    if (context.isEmpty()) {
      sender.sendMessage(locale.component("command.consist.define.book.invalid"));
      return;
    }
    Optional<Scope> scopeOpt =
        resolveScope(sender, context.get().company(), context.get().operator(), true);
    if (scopeOpt.isEmpty()) {
      return;
    }
    Optional<ConsistPlanService> service = plugin.getConsistPlanService();
    if (service.isEmpty()) {
      sender.sendMessage(locale.component("command.consist.not-ready"));
      return;
    }
    Scope scope = scopeOpt.get();
    List<String> lines = readBookLines(meta);
    ConsistPlanBook.Parsed parsed = ConsistPlanBook.parse(lines);
    if (!parsed.ok()) {
      sender.sendMessage(locale.component("command.consist.define.problem-header"));
      for (ConsistPlanBook.Problem problem : parsed.problems()) {
        sender.sendMessage(
            locale.component(
                "command.consist.define.problem",
                Map.of(
                    "line",
                    String.valueOf(problem.lineNo()),
                    "reason",
                    locale.enumText("enum.consist-book-problem", problem.kind()),
                    "detail",
                    problem.detail())));
      }
      return;
    }

    Optional<ConsistPlan> existing =
        scope
            .provider()
            .consistPlans()
            .findByOperatorAndName(scope.operator().id(), context.get().name());
    Instant now = Instant.now();
    ConsistPlan candidate =
        new ConsistPlan(
            existing.map(ConsistPlan::id).orElseGet(UUID::randomUUID),
            scope.operator().id(),
            existing.map(ConsistPlan::name).orElse(context.get().name()),
            String.join("\n", lines),
            existing.map(ConsistPlan::createdAt).orElse(now),
            now);
    ResolvedConsistPlan resolved = service.get().resolve(candidate);
    boolean blocked = false;
    for (ResolvedConsistPlan.Member member : resolved.members()) {
      if (member.profile().isEmpty()) {
        if (!blocked) {
          sender.sendMessage(locale.component("command.consist.define.problem-header"));
        }
        blocked = true;
        sendIssues(sender, locale, member.entry().pattern(), member.resolution());
      }
    }
    if (blocked) {
      return;
    }
    List<ConsistOverrideConflicts.Conflict> conflicts =
        ConsistOverrideConflicts.find(parsed.entries(), otherPlansInCompany(scope, candidate.id()));
    if (!conflicts.isEmpty()) {
      sender.sendMessage(locale.component("command.consist.define.problem-header"));
      for (ConsistOverrideConflicts.Conflict conflict : conflicts) {
        sender.sendMessage(
            locale.component(
                "command.consist.define.conflict",
                Map.of("consist", conflict.pattern(), "plan", conflict.otherPlan())));
      }
      return;
    }

    scope.provider().consistPlans().save(candidate);
    service.get().reload();
    sender.sendMessage(
        locale.component(
            "command.consist.define.success",
            Map.of("name", candidate.name(), "count", String.valueOf(resolved.members().size()))));
    for (ResolvedConsistPlan.Member member : resolved.members()) {
      sendMember(sender, locale, member.weight(), member.entry().pattern(), member);
      sendIssues(sender, locale, member.entry().pattern(), member.resolution());
    }
  }

  /** 同一公司（各运营商）下除自己之外的方案：方案名带运营商前缀，便于定位。 */
  private Map<String, List<ConsistPlanBook.Entry>> otherPlansInCompany(Scope scope, UUID self) {
    Map<String, List<ConsistPlanBook.Entry>> others = new LinkedHashMap<>();
    for (Operator operator : scope.provider().operators().listByCompany(scope.company().id())) {
      for (ConsistPlan plan : scope.provider().consistPlans().listByOperator(operator.id())) {
        if (!plan.id().equals(self)) {
          others.put(
              operator.code() + "/" + plan.name(), ConsistPlanBook.parse(plan.body()).entries());
        }
      }
    }
    return others;
  }

  /** 运营商下绑定了这份方案的 route，写成 {@code 线路/交路}。 */
  private List<String> boundRoutes(Scope scope, String planName) {
    String key = ConsistPlan.nameKey(planName);
    List<String> routes = new ArrayList<>();
    for (Line line : scope.provider().lines().listByOperator(scope.operator().id())) {
      for (Route route : scope.provider().routes().listByLine(line.id())) {
        if (ConsistPlanService.planNameOf(route.metadata())
            .map(ConsistPlan::nameKey)
            .filter(key::equals)
            .isPresent()) {
          routes.add(line.code() + "/" + route.code());
        }
      }
    }
    return routes;
  }

  private void sendMember(
      CommandSender sender,
      LocaleManager locale,
      int weight,
      String pattern,
      ResolvedConsistPlan.Member member) {
    sendResolution(sender, locale, weight, pattern, member.resolution(), true);
  }

  private void sendResolution(
      CommandSender sender,
      LocaleManager locale,
      int weight,
      String pattern,
      ConsistProfiles.Resolution resolution,
      boolean withWeight) {
    if (resolution.profile().isEmpty()) {
      sender.sendMessage(
          locale.component(
              "command.consist.member-unresolved",
              Map.of("weight", withWeight ? String.valueOf(weight) : "-", "consist", pattern)));
      return;
    }
    ConsistProfile profile = resolution.profile().get();
    StringBuilder extra = new StringBuilder();
    profile
        .maxSpeedBps()
        .ifPresent(
            value ->
                extra.append(
                    locale.text("command.consist.max-speed").replace("<value>", format(value))));
    profile
        .spawnLimit()
        .ifPresent(
            value ->
                extra.append(
                    locale
                        .text("command.consist.spawn-limit")
                        .replace("<value>", String.valueOf(value))));
    Map<String, String> placeholders = new LinkedHashMap<>();
    placeholders.put("weight", withWeight ? String.valueOf(weight) : "-");
    placeholders.put("consist", profile.displayName().orElse(profile.pattern()));
    placeholders.put("pattern", profile.pattern());
    placeholders.put("cars", String.valueOf(profile.cars()));
    placeholders.put("length", String.format(Locale.ROOT, "%.1f", profile.lengthBlocks()));
    placeholders.put("type", profile.type().key());
    placeholders.put("source", locale.enumText("enum.consist-type-source", profile.typeSource()));
    placeholders.put("accel", format(profile.accelBps2()));
    placeholders.put("decel", format(profile.decelBps2()));
    placeholders.put("extra", extra.toString());
    sender.sendMessage(locale.component("command.consist.member", placeholders));
  }

  private void sendIssues(
      CommandSender sender,
      LocaleManager locale,
      String pattern,
      ConsistProfiles.Resolution resolution) {
    for (ConsistProfiles.Issue issue : resolution.issues()) {
      sender.sendMessage(
          locale.component(
              issue.blocking()
                  ? "command.consist.define.profile-blocked"
                  : "command.consist.define.profile-warning",
              Map.of(
                  "consist",
                  pattern,
                  "reason",
                  locale.enumText("enum.consist-profile-issue", issue.kind()),
                  "detail",
                  issue.detail())));
    }
  }

  private void giveBook(
      Player sender, LocaleManager locale, Scope scope, String name, List<String> lines) {
    ItemStack book = new ItemStack(Material.WRITABLE_BOOK, 1);
    if (book.getItemMeta() instanceof BookMeta meta) {
      PersistentDataContainer container = meta.getPersistentDataContainer();
      container.set(bookMarkerKey, PersistentDataType.BYTE, (byte) 1);
      container.set(bookCompanyKey, PersistentDataType.STRING, scope.company().code());
      container.set(bookOperatorKey, PersistentDataType.STRING, scope.operator().code());
      container.set(bookNameKey, PersistentDataType.STRING, name);
      Map<String, String> placeholders = Map.of("operator", scope.operator().code(), "name", name);
      meta.displayName(locale.component("command.consist.editor.book.name", placeholders));
      meta.lore(
          List.of(
              locale.component("command.consist.editor.book.lore-1"),
              locale.component("command.consist.editor.book.lore-2")));
      meta.pages(toPages(lines));
      book.setItemMeta(meta);
    }
    if (!sender.getInventory().addItem(book).isEmpty()) {
      sender.getWorld().dropItemNaturally(sender.getLocation(), book);
      sender.sendMessage(locale.component("command.consist.editor.give.dropped"));
      return;
    }
    sender.sendMessage(locale.component("command.consist.editor.give.success"));
  }

  private Optional<BookContext> readBookContext(BookMeta meta) {
    PersistentDataContainer container = meta.getPersistentDataContainer();
    Byte marker = container.get(bookMarkerKey, PersistentDataType.BYTE);
    String company = container.get(bookCompanyKey, PersistentDataType.STRING);
    String operator = container.get(bookOperatorKey, PersistentDataType.STRING);
    String name = container.get(bookNameKey, PersistentDataType.STRING);
    if (marker == null || marker == 0 || company == null || operator == null || name == null) {
      return Optional.empty();
    }
    return Optional.of(new BookContext(company, operator, name));
  }

  private static List<String> readBookLines(BookMeta meta) {
    List<String> lines = new ArrayList<>();
    for (Component page : meta.pages()) {
      String text = page == null ? "" : PLAIN_TEXT.serialize(page);
      if (text.isEmpty()) {
        continue;
      }
      lines.addAll(List.of(text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)));
    }
    return lines;
  }

  private static List<Component> toPages(List<String> lines) {
    List<Component> pages = new ArrayList<>();
    StringBuilder page = new StringBuilder();
    int count = 0;
    for (String line : lines) {
      if (count >= BOOK_MAX_LINES_PER_PAGE) {
        pages.add(Component.text(page.toString()));
        page = new StringBuilder();
        count = 0;
      }
      if (count > 0) {
        page.append('\n');
      }
      page.append(line == null ? "" : line);
      count++;
    }
    if (page.length() > 0 || pages.isEmpty()) {
      pages.add(Component.text(page.toString()));
    }
    return pages;
  }

  private static String emptyTemplateKey() {
    return "command.consist.editor.book.empty-template";
  }

  /** 方案名：非空、不含引号与竖线（竖线是方案书的分隔符，引号会让命令参数断开）。 */
  private static boolean validName(String name) {
    return name != null
        && !name.isBlank()
        && name.indexOf('"') < 0
        && name.indexOf('\'') < 0
        && name.indexOf('|') < 0;
  }

  private static String format(double value) {
    return String.format(Locale.ROOT, "%.2f", value);
  }

  private Optional<ConsistPlan> findPlan(CommandSender sender, Scope scope, Object nameArg) {
    String name = nameArg == null ? "" : nameArg.toString().trim();
    Optional<ConsistPlan> plan =
        scope.provider().consistPlans().findByOperatorAndName(scope.operator().id(), name);
    if (plan.isEmpty()) {
      sender.sendMessage(
          plugin
              .getLocaleManager()
              .component(
                  "command.consist.not-found",
                  Map.of("operator", scope.operator().code(), "name", name)));
    }
    return plan;
  }

  /** 解析公司与运营商并检查权限；失败时已向用户说明原因。 */
  private Optional<Scope> resolveScope(
      CommandSender sender, Object companyArg, Object operatorArg, boolean manage) {
    Optional<StorageProvider> providerOpt = CommandStorageProviders.readyProvider(sender, plugin);
    if (providerOpt.isEmpty()) {
      return Optional.empty();
    }
    StorageProvider provider = providerOpt.get();
    LocaleManager locale = plugin.getLocaleManager();
    CompanyQueryService query = new CompanyQueryService(provider);
    String companyCode = companyArg == null ? "" : companyArg.toString().trim();
    Optional<Company> company = query.findCompany(companyCode);
    if (company.isEmpty()) {
      sender.sendMessage(
          locale.component("command.company.info.not-found", Map.of("company", companyCode)));
      return Optional.empty();
    }
    boolean allowed =
        manage
            ? CompanyAccessChecker.canManageCompany(sender, provider, company.get().id())
            : CompanyAccessChecker.canReadCompany(sender, provider, company.get().id());
    if (!allowed) {
      sender.sendMessage(locale.component("error.no-permission"));
      return Optional.empty();
    }
    String operatorCode = operatorArg == null ? "" : operatorArg.toString().trim();
    Optional<Operator> operator =
        query
            .findOperator(company.get().id(), operatorCode)
            .filter(found -> found.companyId().equals(company.get().id()));
    if (operator.isEmpty()) {
      sender.sendMessage(
          locale.component("command.operator.not-found", Map.of("operator", operatorCode)));
      return Optional.empty();
    }
    return Optional.of(new Scope(provider, company.get(), operator.get()));
  }

  private SuggestionProvider<CommandSender> companySuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = input.lastRemainingToken().trim().toLowerCase(Locale.ROOT);
          List<String> suggestions = new ArrayList<>();
          if (prefix.isBlank()) {
            suggestions.add("<company>");
          }
          Optional<StorageProvider> provider = CommandStorageProviders.providerIfReady(plugin);
          if (provider.isEmpty()) {
            return suggestions;
          }
          provider.get().companies().listAll().stream()
              .filter(
                  company ->
                      CompanyAccessChecker.canReadCompanyNoCreateIdentity(
                          ctx.sender(), provider.get(), company.id()))
              .map(Company::code)
              .filter(code -> code.toLowerCase(Locale.ROOT).startsWith(prefix))
              .limit(SUGGESTION_LIMIT)
              .forEach(suggestions::add);
          return suggestions;
        });
  }

  private SuggestionProvider<CommandSender> operatorSuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = input.lastRemainingToken().trim().toLowerCase(Locale.ROOT);
          List<String> suggestions = new ArrayList<>();
          if (prefix.isBlank()) {
            suggestions.add("<operator>");
          }
          suggestionCompany(ctx.sender(), ctx.optional("company"))
              .ifPresent(
                  found ->
                      found.provider().operators().listByCompany(found.company().id()).stream()
                          .map(Operator::code)
                          .filter(code -> code.toLowerCase(Locale.ROOT).startsWith(prefix))
                          .limit(SUGGESTION_LIMIT)
                          .forEach(suggestions::add));
          return suggestions;
        });
  }

  private SuggestionProvider<CommandSender> planSuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = input.lastRemainingToken().trim().toLowerCase(Locale.ROOT);
          List<String> suggestions = new ArrayList<>();
          if (prefix.isBlank()) {
            suggestions.add("<name>");
          }
          Optional<CompanyScope> company = suggestionCompany(ctx.sender(), ctx.optional("company"));
          Optional<String> operatorArg = ctx.optional("operator").map(String.class::cast);
          if (company.isEmpty() || operatorArg.isEmpty()) {
            return suggestions;
          }
          new CompanyQueryService(company.get().provider())
              .findOperator(company.get().company().id(), operatorArg.get().trim())
              .ifPresent(
                  operator ->
                      company.get().provider().consistPlans().listByOperator(operator.id()).stream()
                          .map(ConsistPlan::name)
                          .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                          .map(CommandUx::commandArgument)
                          .limit(SUGGESTION_LIMIT)
                          .forEach(suggestions::add));
          return suggestions;
        });
  }

  private Optional<CompanyScope> suggestionCompany(CommandSender sender, Optional<Object> arg) {
    Optional<StorageProvider> provider = CommandStorageProviders.providerIfReady(plugin);
    if (provider.isEmpty() || arg.isEmpty()) {
      return Optional.empty();
    }
    return new CompanyQueryService(provider.get())
        .findCompany(arg.get().toString().trim())
        .filter(
            company ->
                CompanyAccessChecker.canReadCompanyNoCreateIdentity(
                    sender, provider.get(), company.id()))
        .map(company -> new CompanyScope(provider.get(), company));
  }

  private record Scope(StorageProvider provider, Company company, Operator operator) {}

  private record CompanyScope(StorageProvider provider, Company company) {}

  private record BookContext(String company, String operator, String name) {}
}
