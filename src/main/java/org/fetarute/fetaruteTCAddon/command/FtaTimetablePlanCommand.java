package org.fetarute.fetaruteTCAddon.command;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
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
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.LineSpawnMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnGroup;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.ServiceGroupClassifier;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.Timetable;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.plan.Band;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.plan.Pin;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.plan.ResolvedPlan;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.plan.ServicePattern;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.plan.TimetablePlan;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.plan.TimetablePlanBinding;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.plan.TimetablePlanBook;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.plan.TimetablePlanRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.plan.TimetablePlans;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.component.CommandComponent;
import org.incendo.cloud.parser.flag.CommandFlag;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.SuggestionProvider;

/**
 * 运行计划书命令入口：{@code /fta timetable plan ...}
 *
 * <p>计划书写分时段频率与定点班次（写法见 {@link TimetablePlanBook}），挂在 {@code <company> <operator>} 下，用书与笔编辑， 交互与
 * {@code /fta consist} 相同：{@code create} 发空白书、{@code edit} 把已有计划载入书、{@code define} 读手上的书校验后保存。
 *
 * <p>计划书以时刻表编号为单位绑定（{@code bind}/{@code unbind}），编表时由 {@link TimetablePlans#resolve} 读取；本命令只管输入与存储，
 * 不改动已编成的表。
 */
public final class FtaTimetablePlanCommand {

  private static final int SUGGESTION_LIMIT = 20;
  private static final int MAX_CODE_LENGTH = 64;
  private static final String ROOT = "/fta timetable plan";
  private static final String PERMISSION_READ = "fetarute.timetable";
  private static final String PERMISSION_MANAGE = "fetarute.timetable.manage";

  private final FetaruteTCAddon plugin;
  private final NamespacedKey bookMarkerKey;
  private final NamespacedKey bookCompanyKey;
  private final NamespacedKey bookOperatorKey;
  private final NamespacedKey bookCodeKey;
  private final NamespacedKey bookNameKey;

  public FtaTimetablePlanCommand(FetaruteTCAddon plugin) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.bookMarkerKey = new NamespacedKey(plugin, "timetable_plan_editor_marker");
    this.bookCompanyKey = new NamespacedKey(plugin, "timetable_plan_company");
    this.bookOperatorKey = new NamespacedKey(plugin, "timetable_plan_operator");
    this.bookCodeKey = new NamespacedKey(plugin, "timetable_plan_code");
    this.bookNameKey = new NamespacedKey(plugin, "timetable_plan_name");
  }

  /** 注册 {@code /fta timetable plan} 子命令与补全。 */
  public void register(CommandManager<CommandSender> manager) {
    CommandFlag<Void> confirmFlag = CommandFlag.builder("confirm").build();
    CommandFlag<String> nameFlag =
        CommandFlag.<CommandSender>builder("name")
            .withComponent(
                CommandComponent.<CommandSender, String>builder(
                        "name", StringParser.quotedStringParser())
                    .suggestionProvider(CommandSuggestionProviders.placeholder("\"<name>\"")))
            .build();
    SuggestionProvider<CommandSender> companySuggestions = companySuggestions();
    SuggestionProvider<CommandSender> operatorSuggestions = operatorSuggestions();
    SuggestionProvider<CommandSender> planSuggestions = planSuggestions();

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("timetable")
            .literal("plan")
            .permission(PERMISSION_READ)
            .handler(ctx -> sendHelp(ctx.sender())));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("timetable")
            .literal("plan")
            .literal("create")
            .permission(PERMISSION_MANAGE)
            .senderType(Player.class)
            .required("company", StringParser.quotedStringParser(), companySuggestions)
            .required("operator", StringParser.quotedStringParser(), operatorSuggestions)
            .required(
                "plan",
                StringParser.quotedStringParser(),
                SuggestionProvider.suggestingStrings("<plan>"))
            .flag(nameFlag)
            .handler(
                ctx -> {
                  Player sender = (Player) ctx.sender();
                  LocaleManager locale = plugin.getLocaleManager();
                  Optional<Scope> scope =
                      resolveScope(sender, ctx.get("company"), ctx.get("operator"), true);
                  if (scope.isEmpty()) {
                    return;
                  }
                  String code = ((String) ctx.get("plan")).trim();
                  if (!validCode(code)) {
                    sender.sendMessage(locale.component("command.timetable.plan.invalid-code"));
                    return;
                  }
                  if (plans(scope.get())
                      .findByOperatorAndCode(scope.get().operator().id(), code)
                      .isPresent()) {
                    sender.sendMessage(
                        locale.component(
                            "command.timetable.plan.create.exists", Map.of("plan", code)));
                    return;
                  }
                  giveBook(
                      sender,
                      scope.get(),
                      code,
                      ctx.flags().getValue(nameFlag).map(String::trim).orElse(code),
                      EditorBooks.pages(
                          locale.stringList("command.timetable.plan.editor.book.empty-template")));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("timetable")
            .literal("plan")
            .literal("edit")
            .permission(PERMISSION_MANAGE)
            .senderType(Player.class)
            .required("company", StringParser.quotedStringParser(), companySuggestions)
            .required("operator", StringParser.quotedStringParser(), operatorSuggestions)
            .required("plan", StringParser.quotedStringParser(), planSuggestions)
            .flag(nameFlag)
            .handler(
                ctx -> {
                  Player sender = (Player) ctx.sender();
                  Optional<Scope> scope =
                      resolveScope(sender, ctx.get("company"), ctx.get("operator"), true);
                  if (scope.isEmpty()) {
                    return;
                  }
                  findPlan(sender, scope.get(), ctx.get("plan"))
                      .ifPresent(
                          plan ->
                              giveBook(
                                  sender,
                                  scope.get(),
                                  plan.code(),
                                  ctx.flags()
                                      .getValue(nameFlag)
                                      .map(String::trim)
                                      .orElse(plan.name()),
                                  TimetablePlanBook.pages(plan.body()).stream()
                                      .map(page -> (Component) Component.text(page))
                                      .toList()));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("timetable")
            .literal("plan")
            .literal("define")
            .permission(PERMISSION_MANAGE)
            .senderType(Player.class)
            .handler(ctx -> define((Player) ctx.sender())));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("timetable")
            .literal("plan")
            .literal("info")
            .permission(PERMISSION_READ)
            .required("company", StringParser.quotedStringParser(), companySuggestions)
            .required("operator", StringParser.quotedStringParser(), operatorSuggestions)
            .required("plan", StringParser.quotedStringParser(), planSuggestions)
            .handler(
                ctx -> {
                  CommandSender sender = ctx.sender();
                  Optional<Scope> scope =
                      resolveScope(sender, ctx.get("company"), ctx.get("operator"), false);
                  if (scope.isEmpty()) {
                    return;
                  }
                  findPlan(sender, scope.get(), ctx.get("plan"))
                      .ifPresent(plan -> sendInfo(sender, scope.get(), plan));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("timetable")
            .literal("plan")
            .literal("list")
            .permission(PERMISSION_READ)
            .required("company", StringParser.quotedStringParser(), companySuggestions)
            .required("operator", StringParser.quotedStringParser(), operatorSuggestions)
            .handler(
                ctx -> {
                  CommandSender sender = ctx.sender();
                  Optional<Scope> scope =
                      resolveScope(sender, ctx.get("company"), ctx.get("operator"), false);
                  scope.ifPresent(found -> sendList(sender, found));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("timetable")
            .literal("plan")
            .literal("delete")
            .permission(PERMISSION_MANAGE)
            .required("company", StringParser.quotedStringParser(), companySuggestions)
            .required("operator", StringParser.quotedStringParser(), operatorSuggestions)
            .required("plan", StringParser.quotedStringParser(), planSuggestions)
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
                  Optional<TimetablePlan> plan = findPlan(sender, scope.get(), ctx.get("plan"));
                  if (plan.isEmpty()) {
                    return;
                  }
                  List<String> codes = boundCodes(scope.get(), plan.get());
                  if (!codes.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.timetable.plan.delete.bound",
                            Map.of("codes", String.join(", ", codes))));
                    return;
                  }
                  plans(scope.get()).delete(plan.get().id());
                  sender.sendMessage(
                      locale.component(
                          "command.timetable.plan.delete.success",
                          Map.of("plan", plan.get().code())));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("timetable")
            .literal("plan")
            .literal("bind")
            .permission(PERMISSION_MANAGE)
            .required("company", StringParser.quotedStringParser(), companySuggestions)
            .required("operator", StringParser.quotedStringParser(), operatorSuggestions)
            .required("code", StringParser.quotedStringParser(), timetableCodeSuggestions(false))
            .required("plan", StringParser.quotedStringParser(), planSuggestions)
            .handler(
                ctx -> {
                  CommandSender sender = ctx.sender();
                  Optional<Scope> scope =
                      resolveScope(sender, ctx.get("company"), ctx.get("operator"), true);
                  if (scope.isEmpty()) {
                    return;
                  }
                  String code = ((String) ctx.get("code")).trim();
                  if (code.isEmpty()) {
                    return;
                  }
                  findPlan(sender, scope.get(), ctx.get("plan"))
                      .ifPresent(plan -> bind(sender, scope.get(), code, plan));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("timetable")
            .literal("plan")
            .literal("unbind")
            .permission(PERMISSION_MANAGE)
            .required("company", StringParser.quotedStringParser(), companySuggestions)
            .required("operator", StringParser.quotedStringParser(), operatorSuggestions)
            .required("code", StringParser.quotedStringParser(), timetableCodeSuggestions(true))
            .handler(
                ctx -> {
                  CommandSender sender = ctx.sender();
                  LocaleManager locale = plugin.getLocaleManager();
                  Optional<Scope> scope =
                      resolveScope(sender, ctx.get("company"), ctx.get("operator"), true);
                  if (scope.isEmpty()) {
                    return;
                  }
                  String code = ((String) ctx.get("code")).trim();
                  boolean removed = plans(scope.get()).unbind(scope.get().operator().id(), code);
                  sender.sendMessage(
                      locale.component(
                          removed
                              ? "command.timetable.plan.unbind.success"
                              : "command.timetable.plan.unbind.not-bound",
                          Map.of("code", code)));
                }));
  }

  private void sendHelp(CommandSender sender) {
    LocaleManager locale = plugin.getLocaleManager();
    sender.sendMessage(locale.component("command.timetable.plan.help.header"));
    Map<String, String> entries = new LinkedHashMap<>();
    entries.put("create", "<company> <operator> <plan> [--name \"<name>\"]");
    entries.put("edit", "<company> <operator> <plan> [--name \"<name>\"]");
    entries.put("define", "");
    entries.put("info", "<company> <operator> <plan>");
    entries.put("list", "<company> <operator>");
    entries.put("delete", "<company> <operator> <plan> --confirm");
    entries.put("bind", "<company> <operator> <code> <plan>");
    entries.put("unbind", "<company> <operator> <code>");
    entries.forEach(
        (sub, args) ->
            sender.sendMessage(
                locale.component(
                    "command.timetable.plan.help.entry",
                    Map.of(
                        "command",
                        ROOT + " " + sub,
                        "args",
                        args,
                        "description",
                        locale.text("command.timetable.plan.help." + sub)))));
  }

  /** 读手上的计划书：校验 → 保存 → 回显解析结果与引用提示。 */
  private void define(Player sender) {
    LocaleManager locale = plugin.getLocaleManager();
    ItemStack item = sender.getInventory().getItemInMainHand();
    if (item.getType() != Material.WRITABLE_BOOK && item.getType() != Material.WRITTEN_BOOK) {
      sender.sendMessage(locale.component("command.timetable.plan.define.book.missing"));
      return;
    }
    if (!(item.getItemMeta() instanceof BookMeta meta)) {
      sender.sendMessage(locale.component("command.timetable.plan.define.book.invalid"));
      return;
    }
    Optional<BookContext> context = readBookContext(meta);
    if (context.isEmpty()) {
      sender.sendMessage(locale.component("command.timetable.plan.define.book.invalid"));
      return;
    }
    Optional<Scope> scopeOpt =
        resolveScope(sender, context.get().company(), context.get().operator(), true);
    if (scopeOpt.isEmpty()) {
      return;
    }
    Scope scope = scopeOpt.get();
    List<String> pages = EditorBooks.pageTexts(meta);
    TimetablePlanBook.Parsed parsed = TimetablePlanBook.parsePages(pages);
    if (!parsed.ok()) {
      sender.sendMessage(locale.component("command.timetable.plan.define.problem-header"));
      parsed.issues().forEach(issue -> sendIssue(sender, issue));
      return;
    }

    Optional<TimetablePlan> existing =
        plans(scope).findByOperatorAndCode(scope.operator().id(), context.get().code());
    Instant now = Instant.now();
    TimetablePlan plan =
        plans(scope)
            .save(
                new TimetablePlan(
                    existing.map(TimetablePlan::id).orElseGet(UUID::randomUUID),
                    scope.operator().id(),
                    context.get().code(),
                    context.get().name(),
                    TimetablePlanBook.body(pages),
                    existing.map(TimetablePlan::createdAt).orElse(now),
                    now));
    ServicePattern pattern = parsed.pattern();
    sender.sendMessage(
        locale.component(
            "command.timetable.plan.define.success",
            Map.of(
                "plan",
                plan.code(),
                "bands",
                String.valueOf(pattern.bands().size()),
                "pins",
                String.valueOf(pattern.pins().size()))));
    sendPattern(sender, pattern);
    sendReferenceWarnings(sender, scope, pattern);
    sendBindings(sender, scope, plan);
  }

  private void sendIssue(CommandSender sender, TimetablePlanBook.Issue issue) {
    LocaleManager locale = plugin.getLocaleManager();
    sender.sendMessage(
        issue.page() <= 0
            ? locale.component(
                "command.timetable.plan.define.problem-book", Map.of("reason", issue.reason()))
            : locale.component(
                "command.timetable.plan.define.problem",
                Map.of(
                    "page",
                    String.valueOf(issue.page()),
                    "line",
                    String.valueOf(issue.line()),
                    "reason",
                    issue.reason())));
  }

  private void sendInfo(CommandSender sender, Scope scope, TimetablePlan plan) {
    LocaleManager locale = plugin.getLocaleManager();
    sender.sendMessage(
        locale.component(
            "command.timetable.plan.info.header",
            Map.of(
                "plan",
                plan.code(),
                "name",
                plan.name(),
                "operator",
                scope.operator().code(),
                "updated",
                plan.updatedAt().toString())));
    ResolvedPlan resolved = TimetablePlans.resolve(plan);
    if (!resolved.ok()) {
      sender.sendMessage(locale.component("command.timetable.plan.info.broken"));
      resolved
          .issues()
          .forEach(
              issue ->
                  sender.sendMessage(
                      locale.component(
                          "command.timetable.plan.info.issue", Map.of("reason", issue))));
    } else {
      sendPattern(sender, resolved.pattern());
    }
    sendBindings(sender, scope, plan);
    sender.sendMessage(
        locale.component("command.timetable.plan.info.actions", clickArguments(scope, plan)));
  }

  private void sendPattern(CommandSender sender, ServicePattern pattern) {
    LocaleManager locale = plugin.getLocaleManager();
    if (pattern.bands().isEmpty()) {
      sender.sendMessage(locale.component("command.timetable.plan.info.bands-none"));
    } else {
      sender.sendMessage(locale.component("command.timetable.plan.info.bands"));
      for (Band band : pattern.bands()) {
        sender.sendMessage(
            locale.component(
                "command.timetable.plan.info.band",
                Map.of(
                    "key",
                    band.key(),
                    "from",
                    TimetablePlanBook.formatClock(band.fromSecond()),
                    "to",
                    TimetablePlanBook.formatClock(band.toSecond()),
                    "interval",
                    String.valueOf(band.intervalSeconds()))));
      }
    }
    if (pattern.pins().isEmpty()) {
      sender.sendMessage(locale.component("command.timetable.plan.info.pins-none"));
      return;
    }
    sender.sendMessage(locale.component("command.timetable.plan.info.pins"));
    for (Pin pin : pattern.pins()) {
      sender.sendMessage(
          locale.component(
              pin.extra()
                  ? "command.timetable.plan.info.pin-extra"
                  : "command.timetable.plan.info.pin",
              Map.of(
                  "time",
                  TimetablePlanBook.formatClock(pin.secondOfDay()),
                  "route",
                  pin.routeCode())));
    }
  }

  private void sendBindings(CommandSender sender, Scope scope, TimetablePlan plan) {
    LocaleManager locale = plugin.getLocaleManager();
    List<String> codes = boundCodes(scope, plan);
    sender.sendMessage(
        codes.isEmpty()
            ? locale.component("command.timetable.plan.info.bindings-none")
            : locale.component(
                "command.timetable.plan.info.bindings", Map.of("codes", String.join(", ", codes))));
  }

  private void sendList(CommandSender sender, Scope scope) {
    LocaleManager locale = plugin.getLocaleManager();
    List<TimetablePlan> all = plans(scope).listByOperator(scope.operator().id());
    if (all.isEmpty()) {
      sender.sendMessage(locale.component("command.timetable.plan.list.empty"));
      return;
    }
    sender.sendMessage(
        locale.component(
            "command.timetable.plan.list.header",
            Map.of("operator", scope.operator().code(), "count", String.valueOf(all.size()))));
    Map<UUID, List<String>> codesByPlan =
        plans(scope).listBindingsByOperator(scope.operator().id()).stream()
            .collect(
                Collectors.groupingBy(
                    TimetablePlanBinding::planId,
                    Collectors.mapping(TimetablePlanBinding::timetableCode, Collectors.toList())));
    for (TimetablePlan plan : all) {
      ResolvedPlan resolved = TimetablePlans.resolve(plan);
      List<String> codes = codesByPlan.getOrDefault(plan.id(), List.of());
      Map<String, String> placeholders = new LinkedHashMap<>(clickArguments(scope, plan));
      placeholders.put("plan", plan.code());
      placeholders.put("name", plan.name());
      placeholders.put("bands", String.valueOf(resolved.pattern().bands().size()));
      placeholders.put("pins", String.valueOf(resolved.pattern().pins().size()));
      placeholders.put(
          "codes",
          codes.isEmpty()
              ? locale.text("command.timetable.plan.list.codes-none")
              : String.join(", ", codes));
      sender.sendMessage(
          locale.component(
              resolved.ok()
                  ? "command.timetable.plan.list.entry"
                  : "command.timetable.plan.list.broken",
              placeholders));
    }
  }

  private void bind(CommandSender sender, Scope scope, String code, TimetablePlan plan) {
    LocaleManager locale = plugin.getLocaleManager();
    UUID operatorId = scope.operator().id();
    Optional<TimetablePlanBinding> previous = plans(scope).findBinding(operatorId, code);
    if (previous.isPresent() && previous.get().planId().equals(plan.id())) {
      sender.sendMessage(
          locale.component(
              "command.timetable.plan.bind.unchanged", Map.of("code", code, "plan", plan.code())));
      return;
    }
    plans(scope).bind(new TimetablePlanBinding(operatorId, code, plan.id()));
    Optional<String> previousCode =
        previous
            .flatMap(binding -> plans(scope).findById(binding.planId()))
            .map(TimetablePlan::code);
    sender.sendMessage(
        previousCode.isPresent()
            ? locale.component(
                "command.timetable.plan.bind.replaced",
                Map.of("code", code, "plan", plan.code(), "previous", previousCode.get()))
            : locale.component(
                "command.timetable.plan.bind.success", Map.of("code", code, "plan", plan.code())));
    if (!timetableCodes(scope).contains(code)) {
      sender.sendMessage(
          locale.component("command.timetable.plan.bind.no-timetable", Map.of("code", code)));
    } else {
      sender.sendMessage(locale.component("command.timetable.plan.bind.rebuild"));
    }
  }

  /**
   * 计划书里点名的线路、交路组与 route 在运营商下找不到时给出提示，不拦截保存：可以先写计划书、后建线路。
   *
   * <p>交路组取线路发车配置里的组名、route metadata 的 {@code spawn_group} 与默认组 {@link
   * ServiceGroupClassifier#DEFAULT_GROUP}。
   */
  private void sendReferenceWarnings(CommandSender sender, Scope scope, ServicePattern pattern) {
    LocaleManager locale = plugin.getLocaleManager();
    Set<String> lineCodes = new LinkedHashSet<>();
    Set<String> routeCodes = new LinkedHashSet<>();
    Set<String> groups = new LinkedHashSet<>();
    groups.add(ServiceGroupClassifier.DEFAULT_GROUP);
    for (Line line : scope.provider().lines().listByOperator(scope.operator().id())) {
      lineCodes.add(line.code());
      LineSpawnMetadata.parseGroups(line.metadata()).stream()
          .map(SpawnGroup::name)
          .forEach(groups::add);
      for (Route route : scope.provider().routes().listByLine(line.id())) {
        routeCodes.add(route.code());
        if (route.metadata().get("spawn_group") instanceof String group && !group.isBlank()) {
          groups.add(group.trim());
        }
      }
    }
    for (Band band : pattern.bands()) {
      if (band.line().isPresent() && !lineCodes.contains(band.line().get())) {
        sender.sendMessage(
            locale.component(
                "command.timetable.plan.define.warning-line",
                Map.of("line", band.line().get(), "key", band.key())));
      } else if (!groups.contains(band.group())) {
        sender.sendMessage(
            locale.component(
                "command.timetable.plan.define.warning-group",
                Map.of("group", band.group(), "key", band.key())));
      }
    }
    for (Pin pin : pattern.pins()) {
      if (!routeCodes.contains(pin.routeCode())) {
        sender.sendMessage(
            locale.component(
                "command.timetable.plan.define.warning-route",
                Map.of(
                    "route",
                    pin.routeCode(),
                    "time",
                    TimetablePlanBook.formatClock(pin.secondOfDay()))));
      }
    }
  }

  private void giveBook(
      Player sender, Scope scope, String code, String name, List<Component> pages) {
    LocaleManager locale = plugin.getLocaleManager();
    ItemStack book = new ItemStack(Material.WRITABLE_BOOK, 1);
    if (book.getItemMeta() instanceof BookMeta meta) {
      PersistentDataContainer container = meta.getPersistentDataContainer();
      container.set(bookMarkerKey, PersistentDataType.BYTE, (byte) 1);
      container.set(bookCompanyKey, PersistentDataType.STRING, scope.company().code());
      container.set(bookOperatorKey, PersistentDataType.STRING, scope.operator().code());
      container.set(bookCodeKey, PersistentDataType.STRING, code);
      container.set(bookNameKey, PersistentDataType.STRING, name.isBlank() ? code : name);
      meta.displayName(
          locale.component(
              "command.timetable.plan.editor.book.name",
              Map.of("operator", scope.operator().code(), "plan", code)));
      meta.lore(
          List.of(
              locale.component("command.timetable.plan.editor.book.lore-1"),
              locale.component("command.timetable.plan.editor.book.lore-2")));
      meta.pages(pages);
      book.setItemMeta(meta);
    }
    if (!sender.getInventory().addItem(book).isEmpty()) {
      sender.getWorld().dropItemNaturally(sender.getLocation(), book);
      sender.sendMessage(locale.component("command.timetable.plan.editor.give.dropped"));
      return;
    }
    sender.sendMessage(locale.component("command.timetable.plan.editor.give.success"));
  }

  private Optional<BookContext> readBookContext(BookMeta meta) {
    PersistentDataContainer container = meta.getPersistentDataContainer();
    Byte marker = container.get(bookMarkerKey, PersistentDataType.BYTE);
    String company = container.get(bookCompanyKey, PersistentDataType.STRING);
    String operator = container.get(bookOperatorKey, PersistentDataType.STRING);
    String code = container.get(bookCodeKey, PersistentDataType.STRING);
    String name = container.get(bookNameKey, PersistentDataType.STRING);
    if (marker == null || marker == 0 || company == null || operator == null || code == null) {
      return Optional.empty();
    }
    return Optional.of(new BookContext(company, operator, code, name == null ? code : name));
  }

  /** 计划 code：与线路、route 的 code 一样只用不必加引号的字符，便于命令里直接书写与补全。 */
  private static boolean validCode(String code) {
    return code.length() <= MAX_CODE_LENGTH && CommandUx.unquotedSafe(code);
  }

  private static TimetablePlanRepository plans(Scope scope) {
    return scope.provider().timetablePlans();
  }

  private static List<String> boundCodes(Scope scope, TimetablePlan plan) {
    return plans(scope).listBindingsByPlan(plan.id()).stream()
        .map(TimetablePlanBinding::timetableCode)
        .toList();
  }

  /** 运营商下各线路已有的时刻表编号（去重，按出现顺序）。 */
  private static Set<String> timetableCodes(Scope scope) {
    Set<String> codes = new LinkedHashSet<>();
    for (Line line : scope.provider().lines().listByOperator(scope.operator().id())) {
      scope.provider().timetables().listByLine(line.id()).stream()
          .map(Timetable::code)
          .forEach(codes::add);
    }
    return codes;
  }

  /** 点击命令里的参数：一律加引号，code 带特殊字符时命令也不会断开。 */
  private static Map<String, String> clickArguments(Scope scope, TimetablePlan plan) {
    return Map.of(
        "company_arg",
        CommandUx.commandArgument(scope.company().code()),
        "operator_arg",
        CommandUx.commandArgument(scope.operator().code()),
        "plan_arg",
        CommandUx.commandArgument(plan.code()));
  }

  private Optional<TimetablePlan> findPlan(CommandSender sender, Scope scope, Object codeArg) {
    String code = codeArg == null ? "" : codeArg.toString().trim();
    Optional<TimetablePlan> plan = plans(scope).findByOperatorAndCode(scope.operator().id(), code);
    if (plan.isEmpty()) {
      sender.sendMessage(
          plugin
              .getLocaleManager()
              .component(
                  "command.timetable.plan.not-found",
                  Map.of("operator", scope.operator().code(), "plan", code)));
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
          String token = input.lastRemainingToken();
          List<String> suggestions = new ArrayList<>();
          if (CommandUx.suggestionPrefix(token).isBlank()) {
            suggestions.add("<company>");
          }
          Optional<StorageProvider> provider = CommandStorageProviders.providerIfReady(plugin);
          if (provider.isEmpty()) {
            return suggestions;
          }
          List<String> codes =
              provider.get().companies().listAll().stream()
                  .filter(
                      company ->
                          CompanyAccessChecker.canReadCompanyNoCreateIdentity(
                              ctx.sender(), provider.get(), company.id()))
                  .map(Company::code)
                  .toList();
          CommandUx.suggestions(codes, token).stream()
              .limit(SUGGESTION_LIMIT)
              .forEach(suggestions::add);
          return suggestions;
        });
  }

  private SuggestionProvider<CommandSender> operatorSuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String token = input.lastRemainingToken();
          List<String> suggestions = new ArrayList<>();
          if (CommandUx.suggestionPrefix(token).isBlank()) {
            suggestions.add("<operator>");
          }
          suggestionCompany(ctx.sender(), ctx.optional("company"))
              .ifPresent(
                  found ->
                      CommandUx.suggestions(
                              found
                                  .provider()
                                  .operators()
                                  .listByCompany(found.company().id())
                                  .stream()
                                  .map(Operator::code)
                                  .toList(),
                              token)
                          .stream()
                          .limit(SUGGESTION_LIMIT)
                          .forEach(suggestions::add));
          return suggestions;
        });
  }

  private SuggestionProvider<CommandSender> planSuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String token = input.lastRemainingToken();
          List<String> suggestions = new ArrayList<>();
          if (CommandUx.suggestionPrefix(token).isBlank()) {
            suggestions.add("<plan>");
          }
          suggestionScope(ctx.sender(), ctx.optional("company"), ctx.optional("operator"))
              .ifPresent(
                  scope ->
                      CommandUx.suggestions(
                              plans(scope).listByOperator(scope.operator().id()).stream()
                                  .map(TimetablePlan::code)
                                  .toList(),
                              token)
                          .stream()
                          .limit(SUGGESTION_LIMIT)
                          .forEach(suggestions::add));
          return suggestions;
        });
  }

  /**
   * 时刻表编号补全。
   *
   * @param boundOnly 只给已绑定的编号（解除绑定用）；否则给各线已有的编号与已绑定的编号
   */
  private SuggestionProvider<CommandSender> timetableCodeSuggestions(boolean boundOnly) {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String token = input.lastRemainingToken();
          List<String> suggestions = new ArrayList<>();
          if (CommandUx.suggestionPrefix(token).isBlank()) {
            suggestions.add("<code>");
          }
          suggestionScope(ctx.sender(), ctx.optional("company"), ctx.optional("operator"))
              .ifPresent(
                  scope -> {
                    Set<String> codes =
                        boundOnly
                            ? new LinkedHashSet<>()
                            : new LinkedHashSet<>(timetableCodes(scope));
                    plans(scope).listBindingsByOperator(scope.operator().id()).stream()
                        .map(TimetablePlanBinding::timetableCode)
                        .forEach(codes::add);
                    CommandUx.suggestions(List.copyOf(codes), token).stream()
                        .limit(SUGGESTION_LIMIT)
                        .forEach(suggestions::add);
                  });
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

  private Optional<Scope> suggestionScope(
      CommandSender sender, Optional<Object> companyArg, Optional<Object> operatorArg) {
    Optional<CompanyScope> company = suggestionCompany(sender, companyArg);
    if (company.isEmpty() || operatorArg.isEmpty()) {
      return Optional.empty();
    }
    return new CompanyQueryService(company.get().provider())
        .findOperator(company.get().company().id(), operatorArg.get().toString().trim())
        .filter(operator -> operator.companyId().equals(company.get().company().id()))
        .map(operator -> new Scope(company.get().provider(), company.get().company(), operator));
  }

  private record Scope(StorageProvider provider, Company company, Operator operator) {}

  private record CompanyScope(StorageProvider provider, Company company) {}

  private record BookContext(String company, String operator, String code, String name) {}
}
