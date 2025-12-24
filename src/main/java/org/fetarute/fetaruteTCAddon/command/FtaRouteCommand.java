package org.fetarute.fetaruteTCAddon.command;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
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
import org.fetarute.fetaruteTCAddon.company.api.PlayerIdentityService;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.CompanyMember;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.MemberRole;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.PlayerIdentity;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.component.CommandComponent;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.parser.flag.CommandFlag;
import org.incendo.cloud.parser.standard.IntegerParser;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.SuggestionProvider;

/**
 * Route 管理命令入口：/fta route ...
 *
 * <p>Route 描述一条线路下的“运行图/停靠表”，由若干 {@link RouteStop} 组成。由于 RouteStop 的编辑更像脚本，
 * 本插件提供“用书与笔承载定义”的方式：每一行代表一个站点/节点或一个动作标记（如 CHANGE/DYNAMIC）。
 *
 * <p>Route Editor 的核心目标：
 *
 * <ul>
 *   <li>把 RouteStop 的编辑从“长命令参数”变成“可复制/可存档的文本”
 *   <li>通过 NBT（PDC）保存 routeId 等上下文，避免用 lore/标题做脆弱识别
 *   <li>define 后回显解析结果，便于调试（如生成 /tp 或 /train debug destination 的建议）
 * </ul>
 */
public final class FtaRouteCommand {

  private static final int SUGGESTION_LIMIT = 20;
  private static final int STOP_DEBUG_PAGE_SIZE = 10;
  private static final Duration STOP_DEBUG_SESSION_TTL = Duration.ofMinutes(10);
  private static final Pattern DWELL_PATTERN =
      Pattern.compile("\\bdwell=(\\d+)\\b", Pattern.CASE_INSENSITIVE);
  private static final Set<String> ACTION_PREFIXES = Set.of("CHANGE", "DYNAMIC", "ACTION");
  private static final List<String> ROUTE_PATTERN_ALIASES =
      List.of("LOCAL", "RAPID", "NEO_RAPID", "EXPRESS", "LTD_EXPRESS", "LIMITED_EXPRESS");
  private static final PlainTextComponentSerializer PLAIN_TEXT =
      PlainTextComponentSerializer.plainText();

  private final FetaruteTCAddon plugin;
  // Route Editor 书本上下文存储在 PersistentDataContainer 中：这是“机器可读”的唯一可信来源。
  // 之所以不用 lore/title 做识别：它们受语言/格式化影响，且用户可手动改名导致误判。
  private final NamespacedKey bookRouteIdKey;
  private final NamespacedKey bookCompanyKey;
  private final NamespacedKey bookOperatorKey;
  private final NamespacedKey bookLineKey;
  private final NamespacedKey bookRouteCodeKey;
  private final NamespacedKey bookDefinedAtKey;
  private final NamespacedKey bookEditorMarkerKey;
  private final ConcurrentHashMap<UUID, StopDebugSession> stopDebugSessions =
      new ConcurrentHashMap<>();

  public FtaRouteCommand(FetaruteTCAddon plugin) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.bookRouteIdKey = new NamespacedKey(plugin, "route_editor_route_id");
    this.bookCompanyKey = new NamespacedKey(plugin, "route_editor_company");
    this.bookOperatorKey = new NamespacedKey(plugin, "route_editor_operator");
    this.bookLineKey = new NamespacedKey(plugin, "route_editor_line");
    this.bookRouteCodeKey = new NamespacedKey(plugin, "route_editor_route");
    this.bookDefinedAtKey = new NamespacedKey(plugin, "route_editor_defined_at");
    this.bookEditorMarkerKey = new NamespacedKey(plugin, "route_editor_marker");
  }

  /**
   * 注册 /fta route 子命令。
   *
   * <p>当前包含：
   *
   * <ul>
   *   <li>create/list/info/set/delete：Route 主数据维护
   *   <li>define：从书与笔/成书读取内容并写入 RouteStop
   *   <li>editor give：发放某条 route 的编辑书（可预载现有停靠表）
   *   <li>editor edit：将插件生成的成书转换为书与笔，便于再次编辑
   * </ul>
   */
  public void register(CommandManager<CommandSender> manager) {
    CommandFlag<Void> confirmFlag = CommandFlag.builder("confirm").build();
    SuggestionProvider<CommandSender> companySuggestions = companySuggestions();
    SuggestionProvider<CommandSender> operatorSuggestions = operatorSuggestions();
    SuggestionProvider<CommandSender> lineSuggestions = lineSuggestions();
    SuggestionProvider<CommandSender> routeSuggestions = routeSuggestions();

    SuggestionProvider<CommandSender> codeSuggestions = placeholderSuggestion("<code>");
    SuggestionProvider<CommandSender> nameSuggestions = placeholderSuggestion("\"<name>\"");
    SuggestionProvider<CommandSender> secondarySuggestions =
        placeholderSuggestion("\"<secondaryName>\"");
    SuggestionProvider<CommandSender> patternValueSuggestions = routePatternSuggestions();

    var nameFlag =
        CommandFlag.<CommandSender>builder("name")
            .withComponent(
                CommandComponent.<CommandSender, String>builder(
                        "name", StringParser.quotedStringParser())
                    .suggestionProvider(nameSuggestions))
            .build();
    var secondaryFlag =
        CommandFlag.<CommandSender>builder("secondary")
            .withAliases("s")
            .withComponent(
                CommandComponent.<CommandSender, String>builder(
                        "secondary", StringParser.quotedStringParser())
                    .suggestionProvider(secondarySuggestions))
            .build();
    var patternFlag =
        CommandFlag.<CommandSender>builder("pattern")
            .withComponent(
                CommandComponent.<CommandSender, String>builder(
                        "pattern", StringParser.stringParser())
                    .suggestionProvider(patternValueSuggestions))
            .build();
    var runtimeFlag =
        CommandFlag.builder("runtime").withComponent(IntegerParser.integerParser()).build();
    var distanceFlag =
        CommandFlag.builder("distance").withComponent(IntegerParser.integerParser()).build();

    // editor give：允许“空模板”与“绑定到指定 route”两种模式；要么四参齐全要么不填。
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("route")
            .literal("editor")
            .literal("give")
            .senderType(Player.class)
            .handler(
                ctx -> {
                  Player sender = (Player) ctx.sender();
                  LocaleManager locale = plugin.getLocaleManager();
                  ItemStack book = new ItemStack(Material.WRITABLE_BOOK, 1);
                  if (book.getItemMeta() instanceof BookMeta meta) {
                    // 空模板也需要被识别为“运行图编辑器”：用于后续右键牌子追加 nodeId。
                    meta.getPersistentDataContainer()
                        .set(bookEditorMarkerKey, PersistentDataType.BYTE, (byte) 1);
                    meta.displayName(locale.component("command.route.editor.book.name-empty"));
                    meta.lore(
                        List.of(
                            locale.component("command.route.editor.book.lore-1"),
                            locale.component(
                                "command.route.editor.book.lore-2",
                                Map.of(
                                    "company",
                                    "<company>",
                                    "operator",
                                    "<operator>",
                                    "line",
                                    "<line>",
                                    "route",
                                    "<route>"))));
                    meta.pages(toComponents(buildRouteEditorEmptyPages(locale)));
                    book.setItemMeta(meta);
                  }
                  var leftovers = sender.getInventory().addItem(book);
                  if (!leftovers.isEmpty()) {
                    sender.getWorld().dropItemNaturally(sender.getLocation(), book);
                    sender.sendMessage(locale.component("command.route.editor.give.dropped"));
                    return;
                  }
                  sender.sendMessage(locale.component("command.route.editor.give.empty-success"));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("route")
            .literal("editor")
            .literal("give")
            .senderType(Player.class)
            .required("company", StringParser.stringParser(), companySuggestions)
            .required("operator", StringParser.stringParser(), operatorSuggestions)
            .required("line", StringParser.stringParser(), lineSuggestions)
            .required("route", StringParser.stringParser(), routeSuggestions)
            .handler(
                ctx -> {
                  Player sender = (Player) ctx.sender();
                  Optional<StorageProvider> providerOpt = readyProvider(sender);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();
                  LocaleManager locale = plugin.getLocaleManager();
                  CompanyQueryService query = new CompanyQueryService(provider);

                  ResolvedRoute resolved = resolveRoute(ctx, provider, locale, query, true);
                  if (resolved == null) {
                    return;
                  }
                  Route route = resolved.route();

                  // editor give 总是发放“可编辑”的书与笔；即使已有归档成书，也不会覆盖玩家已有物品。
                  ItemStack book = new ItemStack(Material.WRITABLE_BOOK, 1);
                  if (book.getItemMeta() instanceof BookMeta meta) {
                    // 绑定 routeId 等上下文到 NBT（PDC），后续 define/归档都以此作为可靠识别依据。
                    persistBookContext(
                        meta, resolved.company(), resolved.operator(), resolved.line(), route);
                    meta.displayName(
                        locale.component(
                            "command.route.editor.book.name",
                            Map.of("line", resolved.line().code(), "route", route.code())));
                    meta.lore(
                        List.of(
                            locale.component("command.route.editor.book.lore-1"),
                            locale.component(
                                "command.route.editor.book.lore-2",
                                Map.of(
                                    "company",
                                    resolved.company().code(),
                                    "operator",
                                    resolved.operator().code(),
                                    "line",
                                    resolved.line().code(),
                                    "route",
                                    route.code()))));
                    // 若数据库已有 stops，则预载到书中，让“再次编辑”无需手动复制。
                    List<RouteStop> existingStops = provider.routeStops().listByRoute(route.id());
                    meta.pages(
                        toComponents(
                            buildRouteEditorPages(
                                provider,
                                resolved.operator(),
                                resolved.line(),
                                route,
                                existingStops)));
                    book.setItemMeta(meta);
                  }

                  var leftovers = sender.getInventory().addItem(book);
                  if (!leftovers.isEmpty()) {
                    // 背包满时落地，避免“命令执行成功但物品丢失”的体验问题。
                    sender.getWorld().dropItemNaturally(sender.getLocation(), book);
                    sender.sendMessage(locale.component("command.route.editor.give.dropped"));
                    return;
                  }
                  sender.sendMessage(
                      locale.component(
                          "command.route.editor.give.success",
                          Map.of("route", route.code(), "line", resolved.line().code())));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("route")
            .literal("editor")
            .literal("edit")
            .senderType(Player.class)
            .handler(
                ctx -> {
                  Player sender = (Player) ctx.sender();
                  LocaleManager locale = plugin.getLocaleManager();
                  ItemStack item = sender.getInventory().getItemInMainHand();
                  Optional<RouteBookContext> contextOpt = readBookContext(item);
                  if (contextOpt.isEmpty()) {
                    sender.sendMessage(locale.component("command.route.editor.edit.not-editor"));
                    return;
                  }
                  if (item.getType() != Material.WRITTEN_BOOK) {
                    sender.sendMessage(locale.component("command.route.editor.edit.not-written"));
                    return;
                  }
                  if (!(item.getItemMeta() instanceof BookMeta originalMeta)) {
                    sender.sendMessage(locale.component("command.route.editor.edit.not-editor"));
                    return;
                  }
                  // 成书不可直接编辑：此处转换为书与笔并保留 pages + NBT 上下文。
                  ItemStack editable = new ItemStack(Material.WRITABLE_BOOK, 1);
                  if (editable.getItemMeta() instanceof BookMeta meta) {
                    copyBookContext(originalMeta, meta);
                    meta.displayName(
                        locale.component(
                            "command.route.editor.book.name",
                            Map.of(
                                "line",
                                contextOpt.get().lineCode(),
                                "route",
                                contextOpt.get().routeCode())));
                    meta.lore(
                        List.of(
                            locale.component("command.route.editor.book.lore-1"),
                            locale.component(
                                "command.route.editor.book.lore-2",
                                Map.of(
                                    "company",
                                    contextOpt.get().companyCode(),
                                    "operator",
                                    contextOpt.get().operatorCode(),
                                    "line",
                                    contextOpt.get().lineCode(),
                                    "route",
                                    contextOpt.get().routeCode()))));
                    meta.pages(originalMeta.pages());
                    editable.setItemMeta(meta);
                  }
                  sender.getInventory().setItemInMainHand(editable);
                  sender.sendMessage(locale.component("command.route.editor.edit.success"));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("route")
            .literal("create")
            .senderType(Player.class)
            .required("company", StringParser.stringParser(), companySuggestions)
            .required("operator", StringParser.stringParser(), operatorSuggestions)
            .required("line", StringParser.stringParser(), lineSuggestions)
            .required("code", StringParser.stringParser(), codeSuggestions)
            .required("name", StringParser.quotedStringParser(), nameSuggestions)
            .optional("secondaryName", StringParser.quotedStringParser(), secondarySuggestions)
            .flag(patternFlag)
            .flag(runtimeFlag)
            .flag(distanceFlag)
            .handler(
                ctx -> {
                  Player sender = (Player) ctx.sender();
                  Optional<StorageProvider> providerOpt = readyProvider(sender);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();
                  LocaleManager locale = plugin.getLocaleManager();
                  CompanyQueryService query = new CompanyQueryService(provider);

                  ResolvedLine resolved = resolveLine(ctx, provider, locale, query, true);
                  if (resolved == null) {
                    return;
                  }

                  String code = ((String) ctx.get("code")).trim();
                  String name = ((String) ctx.get("name")).trim();
                  String secondaryName =
                      ctx.optional("secondaryName").map(String.class::cast).orElse(null);

                  if (provider.routes().findByLineAndCode(resolved.line().id(), code).isPresent()) {
                    sender.sendMessage(
                        locale.component("command.route.create.exists", Map.of("code", code)));
                    return;
                  }

                  var flags = ctx.flags();
                  String patternRaw = flags.getValue(patternFlag, RoutePatternType.LOCAL.name());
                  Optional<RoutePatternType> patternOpt = RoutePatternType.fromToken(patternRaw);
                  if (patternOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.route.enum-invalid",
                            Map.of("field", "pattern", "value", String.valueOf(patternRaw))));
                    return;
                  }
                  Integer runtime = flags.getValue(runtimeFlag, null);
                  Integer distance = flags.getValue(distanceFlag, null);

                  Instant now = Instant.now();
                  Route route =
                      new Route(
                          UUID.randomUUID(),
                          code,
                          resolved.line().id(),
                          name,
                          Optional.ofNullable(secondaryName),
                          patternOpt.get(),
                          Optional.ofNullable(distance),
                          Optional.ofNullable(runtime),
                          Map.of(),
                          now,
                          now);
                  provider.routes().save(route);
                  sender.sendMessage(
                      locale.component(
                          "command.route.create.success",
                          Map.of("code", route.code(), "line", resolved.line().code())));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("route")
            .literal("list")
            .senderType(Player.class)
            .required("company", StringParser.stringParser(), companySuggestions)
            .required("operator", StringParser.stringParser(), operatorSuggestions)
            .required("line", StringParser.stringParser(), lineSuggestions)
            .handler(
                ctx -> {
                  Player sender = (Player) ctx.sender();
                  Optional<StorageProvider> providerOpt = readyProvider(sender);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();
                  LocaleManager locale = plugin.getLocaleManager();
                  CompanyQueryService query = new CompanyQueryService(provider);

                  ResolvedLine resolved = resolveLine(ctx, provider, locale, query, false);
                  if (resolved == null) {
                    return;
                  }

                  sender.sendMessage(
                      locale.component(
                          "command.route.list.header",
                          Map.of(
                              "company",
                              resolved.company().code(),
                              "operator",
                              resolved.operator().code(),
                              "line",
                              resolved.line().code())));
                  List<Route> routes = provider.routes().listByLine(resolved.line().id());
                  if (routes.isEmpty()) {
                    sender.sendMessage(locale.component("command.route.list.empty"));
                    return;
                  }
                  for (Route route : routes) {
                    String secondary = route.secondaryName().filter(s -> !s.isBlank()).orElse("-");
                    String runtime = route.runtimeSeconds().map(String::valueOf).orElse("-");
                    String distance = route.distanceMeters().map(String::valueOf).orElse("-");
                    String pattern =
                        locale.enumText("enum.route-pattern-type", route.patternType());
                    sender.sendMessage(
                        locale
                            .component(
                                "command.route.list.entry",
                                Map.of(
                                    "code", route.code(), "name", route.name(), "pattern", pattern))
                            .hoverEvent(
                                HoverEvent.showText(
                                    locale.component(
                                        "command.route.list.hover-entry",
                                        Map.of(
                                            "code",
                                            route.code(),
                                            "name",
                                            route.name(),
                                            "secondary",
                                            secondary,
                                            "pattern",
                                            pattern,
                                            "runtime",
                                            runtime,
                                            "distance",
                                            distance)))));
                  }
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("route")
            .literal("info")
            .senderType(Player.class)
            .required("company", StringParser.stringParser(), companySuggestions)
            .required("operator", StringParser.stringParser(), operatorSuggestions)
            .required("line", StringParser.stringParser(), lineSuggestions)
            .required("route", StringParser.stringParser(), routeSuggestions)
            .handler(
                ctx -> {
                  Player sender = (Player) ctx.sender();
                  Optional<StorageProvider> providerOpt = readyProvider(sender);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();
                  LocaleManager locale = plugin.getLocaleManager();
                  CompanyQueryService query = new CompanyQueryService(provider);

                  ResolvedRoute resolved = resolveRoute(ctx, provider, locale, query, false);
                  if (resolved == null) {
                    return;
                  }
                  Route route = resolved.route();

                  sender.sendMessage(
                      locale.component(
                          "command.route.info.header",
                          Map.of(
                              "line",
                              resolved.line().code(),
                              "code",
                              route.code(),
                              "name",
                              route.name())));
                  sender.sendMessage(
                      locale.component(
                          "command.route.info.pattern",
                          Map.of(
                              "pattern",
                              locale.enumText("enum.route-pattern-type", route.patternType()))));
                  sender.sendMessage(
                      locale.component(
                          "command.route.info.runtime",
                          Map.of(
                              "runtime", route.runtimeSeconds().map(String::valueOf).orElse("-"))));
                  sender.sendMessage(
                      locale.component(
                          "command.route.info.distance",
                          Map.of(
                              "distance",
                              route.distanceMeters().map(String::valueOf).orElse("-"))));

                  List<RouteStop> stops = provider.routeStops().listByRoute(route.id());
                  sender.sendMessage(
                      locale.component(
                          "command.route.info.stops",
                          Map.of("count", String.valueOf(stops.size()))));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("route")
            .literal("set")
            .senderType(Player.class)
            .required("company", StringParser.stringParser(), companySuggestions)
            .required("operator", StringParser.stringParser(), operatorSuggestions)
            .required("line", StringParser.stringParser(), lineSuggestions)
            .required("route", StringParser.stringParser(), routeSuggestions)
            .flag(nameFlag)
            .flag(secondaryFlag)
            .flag(patternFlag)
            .flag(runtimeFlag)
            .flag(distanceFlag)
            .handler(
                ctx -> {
                  Player sender = (Player) ctx.sender();
                  Optional<StorageProvider> providerOpt = readyProvider(sender);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();
                  LocaleManager locale = plugin.getLocaleManager();
                  CompanyQueryService query = new CompanyQueryService(provider);

                  ResolvedRoute resolved = resolveRoute(ctx, provider, locale, query, true);
                  if (resolved == null) {
                    return;
                  }
                  Route route = resolved.route();

                  var flags = ctx.flags();
                  boolean any =
                      flags.hasFlag(nameFlag)
                          || flags.hasFlag(secondaryFlag)
                          || flags.hasFlag(patternFlag)
                          || flags.hasFlag(runtimeFlag)
                          || flags.hasFlag(distanceFlag);
                  if (!any) {
                    sender.sendMessage(locale.component("command.route.set.noop"));
                    return;
                  }

                  String name = flags.getValue(nameFlag, route.name());
                  if (name != null) {
                    name = name.trim();
                  }
                  if (name == null || name.isBlank()) {
                    name = route.name();
                  }
                  String secondary =
                      flags.getValue(secondaryFlag, route.secondaryName().orElse(null));
                  if (secondary != null) {
                    secondary = secondary.trim();
                  }

                  String patternRaw = flags.getValue(patternFlag, route.patternType().name());
                  Optional<RoutePatternType> patternOpt = RoutePatternType.fromToken(patternRaw);
                  if (patternOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.route.enum-invalid",
                            Map.of("field", "pattern", "value", String.valueOf(patternRaw))));
                    return;
                  }
                  Integer runtime =
                      flags.getValue(runtimeFlag, route.runtimeSeconds().orElse(null));
                  Integer distance =
                      flags.getValue(distanceFlag, route.distanceMeters().orElse(null));

                  Route updated =
                      new Route(
                          route.id(),
                          route.code(),
                          route.lineId(),
                          name,
                          Optional.ofNullable(secondary),
                          patternOpt.get(),
                          Optional.ofNullable(distance),
                          Optional.ofNullable(runtime),
                          route.metadata(),
                          route.createdAt(),
                          Instant.now());
                  provider.routes().save(updated);
                  sender.sendMessage(
                      locale.component("command.route.set.success", Map.of("code", route.code())));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("route")
            .literal("delete")
            .senderType(Player.class)
            .required("company", StringParser.stringParser(), companySuggestions)
            .required("operator", StringParser.stringParser(), operatorSuggestions)
            .required("line", StringParser.stringParser(), lineSuggestions)
            .required("route", StringParser.stringParser(), routeSuggestions)
            .flag(confirmFlag)
            .handler(
                ctx -> {
                  Player sender = (Player) ctx.sender();
                  Optional<StorageProvider> providerOpt = readyProvider(sender);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  if (!ctx.flags().isPresent(confirmFlag)) {
                    sender.sendMessage(
                        plugin.getLocaleManager().component("command.common.confirm-required"));
                    return;
                  }
                  StorageProvider provider = providerOpt.get();
                  LocaleManager locale = plugin.getLocaleManager();
                  CompanyQueryService query = new CompanyQueryService(provider);

                  ResolvedRoute resolved = resolveRoute(ctx, provider, locale, query, true);
                  if (resolved == null) {
                    return;
                  }
                  Route route = resolved.route();

                  provider
                      .transactionManager()
                      .execute(
                          () -> {
                            provider.routeStops().deleteAll(route.id());
                            provider.routes().delete(route.id());
                            return null;
                          });
                  sender.sendMessage(
                      locale.component(
                          "command.route.delete.success", Map.of("code", route.code())));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("route")
            .literal("define")
            .senderType(Player.class)
            .required("company", StringParser.stringParser(), companySuggestions)
            .required("operator", StringParser.stringParser(), operatorSuggestions)
            .required("line", StringParser.stringParser(), lineSuggestions)
            .required("route", StringParser.stringParser(), routeSuggestions)
            .handler(
                ctx -> {
                  Player sender = (Player) ctx.sender();
                  Optional<StorageProvider> providerOpt = readyProvider(sender);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();
                  LocaleManager locale = plugin.getLocaleManager();
                  CompanyQueryService query = new CompanyQueryService(provider);

                  ResolvedRoute resolved = resolveRoute(ctx, provider, locale, query, true);
                  if (resolved == null) {
                    return;
                  }
                  Route route = resolved.route();

                  Optional<List<BookLine>> bookLinesOpt = readBookLines(locale, sender);
                  if (bookLinesOpt.isEmpty()) {
                    return;
                  }
                  List<BookLine> bookLines = bookLinesOpt.get();
                  if (bookLines.isEmpty()) {
                    sender.sendMessage(locale.component("command.route.define.empty"));
                    return;
                  }

                  // 将书中的定义解析为 RouteStop 列表；动作行会附着到上一条 stop 的 notes。
                  Optional<List<RouteStop>> stopsOpt =
                      parseStopsFromBook(
                          locale,
                          provider,
                          resolved.operator().id(),
                          route.id(),
                          sender,
                          bookLines);
                  if (stopsOpt.isEmpty()) {
                    return;
                  }
                  List<RouteStop> stops = stopsOpt.get();

                  provider
                      .transactionManager()
                      .execute(
                          () -> {
                            // 采用“整表替换”写入：先清空再按 sequence 顺序写入，保证停靠表一致性。
                            provider.routeStops().deleteAll(route.id());
                            for (RouteStop stop : stops) {
                              provider.routeStops().save(stop);
                            }
                            return null;
                          });
                  sender.sendMessage(
                      locale.component(
                          "command.route.define.success",
                          Map.of("code", route.code(), "count", String.valueOf(stops.size()))));

                  // 若玩家手持的是该 route 的编辑书/成书，则在 define 后将其归档为“成书”，便于存档与回溯。
                  archiveBookIfHolding(locale, sender, provider, resolved, stops);
                  // define 后回显解析结果，便于玩家检查 PASS/TERM/dwell/nodeId 等是否符合预期。
                  sendStopDebug(locale, sender, provider, resolved.operator().id(), stops);
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("route")
            .literal("define")
            .literal("debug")
            .senderType(Player.class)
            .optional("page", IntegerParser.integerParser())
            .handler(
                ctx -> {
                  Player sender = (Player) ctx.sender();
                  Optional<StorageProvider> providerOpt = readyProvider(sender);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();
                  LocaleManager locale = plugin.getLocaleManager();

                  int page = ctx.optional("page").map(Integer.class::cast).orElse(1);
                  StopDebugSession session = stopDebugSessions.get(sender.getUniqueId());
                  if (session == null) {
                    sender.sendMessage(
                        locale.component("command.route.define.debug.session-missing"));
                    return;
                  }
                  if (session.isExpired()) {
                    stopDebugSessions.remove(sender.getUniqueId());
                    sender.sendMessage(
                        locale.component("command.route.define.debug.session-expired"));
                    return;
                  }
                  sendStopDebugPage(locale, sender, provider, session, page);
                }));
  }

  /** 获取已就绪的 StorageProvider；未就绪时向用户输出统一错误文案。 */
  private Optional<StorageProvider> readyProvider(CommandSender sender) {
    Optional<StorageProvider> providerOpt = providerIfReady();
    if (providerOpt.isEmpty()) {
      sender.sendMessage(plugin.getLocaleManager().component("error.storage-unavailable"));
    }
    return providerOpt;
  }

  /** 仅在 StorageManager ready 的情况下返回 provider，避免命令线程触发初始化或抛异常。 */
  private Optional<StorageProvider> providerIfReady() {
    if (plugin.getStorageManager() == null || !plugin.getStorageManager().isReady()) {
      return Optional.empty();
    }
    return plugin.getStorageManager().provider();
  }

  /** 判断 sender 是否具备读取指定公司的权限（公司成员或管理员）。 */
  private boolean canReadCompany(CommandSender sender, StorageProvider provider, UUID companyId) {
    if (sender.hasPermission("fetarute.admin")) {
      return true;
    }
    if (!(sender instanceof Player player)) {
      return false;
    }
    PlayerIdentity identity =
        new PlayerIdentityService(provider.playerIdentities()).requireIdentity(player);
    return provider.companyMembers().findMembership(companyId, identity.id()).isPresent();
  }

  /** 判断 sender 是否具备管理指定公司的权限（Owner/Manager 或管理员）。 */
  private boolean canManageCompany(CommandSender sender, StorageProvider provider, UUID companyId) {
    if (sender.hasPermission("fetarute.admin")) {
      return true;
    }
    if (!(sender instanceof Player player)) {
      return false;
    }
    PlayerIdentity identity =
        new PlayerIdentityService(provider.playerIdentities()).requireIdentity(player);
    Optional<CompanyMember> membership =
        provider.companyMembers().findMembership(companyId, identity.id());
    if (membership.isEmpty()) {
      return false;
    }
    Set<MemberRole> roles = membership.get().roles();
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
    Optional<PlayerIdentity> identityOpt =
        provider.playerIdentities().findByPlayerUuid(player.getUniqueId());
    if (identityOpt.isEmpty()) {
      return false;
    }
    return provider.companyMembers().findMembership(companyId, identityOpt.get().id()).isPresent();
  }

  private SuggestionProvider<CommandSender> placeholderSuggestion(String placeholder) {
    return SuggestionProvider.suggestingStrings(placeholder);
  }

  /**
   * pattern flag value 补全：同时给出占位符与常用别名（支持前缀过滤）。
   *
   * <p>这里不直接输出枚举全量值，而是输出“玩家常用服务等级”列表，降低记忆成本。
   */
  private static SuggestionProvider<CommandSender> routePatternSuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = input.lastRemainingToken().trim().toUpperCase(Locale.ROOT);
          List<String> suggestions = new ArrayList<>();
          if (prefix.isBlank()) {
            suggestions.add("<pattern>");
          }
          for (String value : ROUTE_PATTERN_ALIASES) {
            if (prefix.isBlank() || value.startsWith(prefix)) {
              suggestions.add(value);
            }
          }
          return suggestions;
        });
  }

  /**
   * company 参数补全：按权限过滤可见范围，避免泄露其他公司的 code。
   *
   * <p>空输入时会附带一个占位符，提示用户参数形态。
   */
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
          Optional<StorageProvider> providerOpt = providerIfReady();
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
          StorageProvider provider = providerOpt.get();
          CompanyQueryService query = new CompanyQueryService(provider);
          Optional<Company> companyOpt = query.findCompany(companyArg);
          if (companyOpt.isEmpty()) {
            return suggestions;
          }
          Company company = companyOpt.get();
          if (!canReadCompanyNoCreateIdentity(ctx.sender(), provider, company.id())) {
            return suggestions;
          }
          // 候选数量限制，避免大型数据导致补全阻塞与刷屏。
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

  /**
   * line 参数补全：依赖 company/operator 参数，并按权限过滤。
   *
   * <p>返回的 code 需满足前缀匹配，且数量限制在 {@link #SUGGESTION_LIMIT} 以内。
   */
  private SuggestionProvider<CommandSender> lineSuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = normalizePrefix(input);
          List<String> suggestions = new ArrayList<>();
          if (prefix.isBlank()) {
            suggestions.add("<line>");
          }
          Optional<StorageProvider> providerOpt = providerIfReady();
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
          StorageProvider provider = providerOpt.get();
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

  /**
   * route 参数补全：依赖 company/operator/line 参数，并按权限过滤。
   *
   * <p>返回 code 列表并限制数量，避免大型数据导致补全阻塞。
   */
  private SuggestionProvider<CommandSender> routeSuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = normalizePrefix(input);
          List<String> suggestions = new ArrayList<>();
          if (prefix.isBlank()) {
            suggestions.add("<route>");
          }
          Optional<StorageProvider> providerOpt = providerIfReady();
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
          StorageProvider provider = providerOpt.get();
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

  /** 将 Cloud 的输入 token 规范化为小写前缀，用于前缀过滤。 */
  private static String normalizePrefix(CommandInput input) {
    if (input == null) {
      return "";
    }
    return input.lastRemainingToken().trim().toLowerCase(Locale.ROOT);
  }

  /** 列出 sender 可见的公司 code 列表，供补全使用。 */
  private List<String> listCompanyCodes(CommandSender sender, String prefix) {
    Optional<StorageProvider> providerOpt = providerIfReady();
    if (providerOpt.isEmpty()) {
      return List.of();
    }
    StorageProvider provider = providerOpt.get();
    Stream<Company> companies;
    if (sender.hasPermission("fetarute.admin")) {
      companies = provider.companies().listAll().stream();
    } else if (sender instanceof Player player) {
      Optional<PlayerIdentity> identityOpt =
          provider.playerIdentities().findByPlayerUuid(player.getUniqueId());
      if (identityOpt.isEmpty()) {
        return List.of();
      }
      List<CompanyMember> memberships =
          provider.companyMembers().listMemberships(identityOpt.get().id());
      if (memberships.isEmpty()) {
        return List.of();
      }
      companies =
          memberships.stream()
              .map(CompanyMember::companyId)
              .distinct()
              .map(provider.companies()::findById)
              .flatMap(Optional::stream);
    } else {
      return List.of();
    }
    return companies
        .map(Company::code)
        .filter(Objects::nonNull)
        .map(String::trim)
        .filter(code -> !code.isBlank())
        .filter(code -> code.toLowerCase(Locale.ROOT).startsWith(prefix))
        .distinct()
        .limit(SUGGESTION_LIMIT)
        .toList();
  }

  private ResolvedLine resolveLine(
      org.incendo.cloud.context.CommandContext<? extends CommandSender> ctx,
      StorageProvider provider,
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
    return new ResolvedLine(company, operator, lineOpt.get());
  }

  private ResolvedRoute resolveRoute(
      org.incendo.cloud.context.CommandContext<? extends CommandSender> ctx,
      StorageProvider provider,
      LocaleManager locale,
      CompanyQueryService query,
      boolean requireManage) {
    ResolvedLine resolvedLine = resolveLine(ctx, provider, locale, query, requireManage);
    if (resolvedLine == null) {
      return null;
    }
    String routeArg = ((String) ctx.get("route")).trim();
    Optional<Route> routeOpt = query.findRoute(resolvedLine.line().id(), routeArg);
    if (routeOpt.isEmpty()) {
      ctx.sender()
          .sendMessage(locale.component("command.route.not-found", Map.of("route", routeArg)));
      return null;
    }
    return new ResolvedRoute(
        resolvedLine.company(), resolvedLine.operator(), resolvedLine.line(), routeOpt.get());
  }

  /**
   * 读取玩家主手书本内容并拆分为“带行号的文本行”。
   *
   * <p>支持书与笔（可编辑）与成书（已归档）。为保证跨平台一致性，会统一换行符为 {@code \n}。
   */
  private Optional<List<BookLine>> readBookLines(LocaleManager locale, Player sender) {
    ItemStack item = sender.getInventory().getItemInMainHand();
    if (item.getType() == Material.AIR) {
      sender.sendMessage(locale.component("command.route.define.book.missing"));
      return Optional.empty();
    }
    if (!(item.getType() == Material.WRITABLE_BOOK || item.getType() == Material.WRITTEN_BOOK)) {
      sender.sendMessage(locale.component("command.route.define.book.invalid"));
      return Optional.empty();
    }
    if (!(item.getItemMeta() instanceof BookMeta meta)) {
      sender.sendMessage(locale.component("command.route.define.book.invalid"));
      return Optional.empty();
    }
    List<Component> pages = meta.pages();
    if (pages.isEmpty()) {
      return Optional.of(List.of());
    }
    List<BookLine> lines = new ArrayList<>();
    int lineNo = 0;
    for (Component page : pages) {
      String pageText = page == null ? "" : PLAIN_TEXT.serialize(page);
      if (pageText.isEmpty()) {
        continue;
      }
      // Bukkit 的页面文本可能含 \r\n 或 \r；这里统一为 \n 以便稳定 split。
      String normalized = pageText.replace("\r\n", "\n").replace('\r', '\n');
      for (String rawLine : normalized.split("\n", -1)) {
        lineNo++;
        lines.add(new BookLine(lineNo, rawLine));
      }
    }
    return Optional.of(lines);
  }

  /**
   * 将书本的文本行解析为 RouteStop 列表。
   *
   * <p>解析规则（第一版）：
   *
   * <ul>
   *   <li>空行、#、// 开头的行忽略
   *   <li>动作行（CHANGE/DYNAMIC/ACTION 前缀）附着到上一条 stop 的 notes（用换行拼接）
   *   <li>stop 行支持 PASS/STOP/TERM 前缀；支持 dwell=&lt;秒&gt; 参数
   *   <li>含冒号的行视为 NodeId，写入 waypointNodeId（字符串原样保存）
   *   <li>不含冒号的行视为 StationCode，需在 stations 表中存在，否则报错
   * </ul>
   *
   * <p>这里不对 NodeId 做强校验：因为节点可能来自轨道牌子注册或后续扩展（如 Switcher）。
   */
  private Optional<List<RouteStop>> parseStopsFromBook(
      LocaleManager locale,
      StorageProvider provider,
      UUID operatorId,
      UUID routeId,
      Player sender,
      List<BookLine> lines) {
    List<RouteStop> stops = new ArrayList<>();
    for (BookLine line : lines) {
      String raw = line.text();
      if (raw == null) {
        continue;
      }
      String trimmed = raw.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) {
        continue;
      }

      // 动作行：附着到上一条 stop 的 notes（用换行拼接，便于后续解释器读取）。
      String firstSegment = firstSegment(trimmed);
      if (isActionLine(firstSegment)) {
        if (stops.isEmpty()) {
          sender.sendMessage(
              locale.component(
                  "command.route.define.action-first",
                  Map.of("line", String.valueOf(line.lineNo()), "text", trimmed)));
          return Optional.empty();
        }
        RouteStop last = stops.get(stops.size() - 1);
        String mergedNotes =
            last.notes()
                .map(existing -> existing.isBlank() ? trimmed : (existing + "\n" + trimmed))
                .orElse(trimmed);
        stops.set(
            stops.size() - 1,
            new RouteStop(
                last.routeId(),
                last.sequence(),
                last.stationId(),
                last.waypointNodeId(),
                last.dwellSeconds(),
                last.passType(),
                Optional.of(mergedNotes)));
        continue;
      }

      // 允许 PASS/STOP/TERM 前缀控制 passType（默认 STOP）。
      RouteStopPassType passType = RouteStopPassType.STOP;
      String content = trimmed;
      if (startsWithWord(content, "PASS")) {
        passType = RouteStopPassType.PASS;
        content = content.substring(4).trim();
      } else if (startsWithWord(content, "STOP")) {
        passType = RouteStopPassType.STOP;
        content = content.substring(4).trim();
      } else if (startsWithWord(content, "TERM") || startsWithWord(content, "TERMINATE")) {
        passType = RouteStopPassType.TERMINATE;
        content =
            content.replaceFirst("(?i)^TERMINATE\\b", "").replaceFirst("(?i)^TERM\\b", "").trim();
      }

      Optional<Integer> dwellSeconds = Optional.empty();
      Matcher dwellMatcher = DWELL_PATTERN.matcher(content);
      if (dwellMatcher.find()) {
        try {
          dwellSeconds = Optional.of(Integer.parseInt(dwellMatcher.group(1)));
        } catch (NumberFormatException ignored) {
          dwellSeconds = Optional.empty();
        }
        content = dwellMatcher.replaceAll("").trim();
      }

      if (content.isBlank()) {
        sender.sendMessage(
            locale.component(
                "command.route.define.invalid-line",
                Map.of("line", String.valueOf(line.lineNo()), "text", trimmed)));
        return Optional.empty();
      }

      Optional<UUID> stationId = Optional.empty();
      Optional<String> waypointNodeId = Optional.empty();
      if (content.contains(":")) {
        // 形如 SURN:S:PTK:1 或 SURN:A:B:1:00 等：当作“图节点引用”原样写入。
        waypointNodeId = Optional.of(content);
      } else {
        // 默认按站点 code 解析：用 UUID 外键保证引用一致性（站点重命名不会破坏停靠表）。
        Optional<Station> stationOpt =
            provider.stations().findByOperatorAndCode(operatorId, content);
        if (stationOpt.isEmpty()) {
          sender.sendMessage(
              locale.component(
                  "command.route.define.station-not-found",
                  Map.of("line", String.valueOf(line.lineNo()), "station", content)));
          return Optional.empty();
        }
        stationId = Optional.of(stationOpt.get().id());
      }

      int sequence = stops.size();
      RouteStop stop =
          new RouteStop(
              routeId,
              sequence,
              stationId,
              waypointNodeId,
              dwellSeconds,
              passType,
              Optional.empty());
      stops.add(stop);
    }
    return Optional.of(stops);
  }

  private static boolean startsWithWord(String text, String word) {
    return text.regionMatches(true, 0, word, 0, word.length())
        && (text.length() == word.length() || Character.isWhitespace(text.charAt(word.length())));
  }

  private static String firstSegment(String line) {
    int idx = line.indexOf(':');
    return idx < 0 ? line : line.substring(0, idx);
  }

  private static boolean isActionLine(String firstSegment) {
    if (firstSegment == null) {
      return false;
    }
    return ACTION_PREFIXES.contains(firstSegment.trim().toUpperCase(Locale.ROOT));
  }

  private record ResolvedLine(Company company, Operator operator, Line line) {}

  private record ResolvedRoute(Company company, Operator operator, Line line, Route route) {}

  /** 书本的“原始行”，用于在报错时定位行号。 */
  private record BookLine(int lineNo, String text) {}

  /**
   * 构建 Route Editor 的书页内容。
   *
   * <p>当 stops 非空时，会把现有停靠表渲染进书中，便于“二次编辑”；同时会把 notes 逐行展开为 action 行。
   *
   * <p>注意：书本每页可显示的行数有限，这里按固定行数切页，避免超出后玩家端显示异常。
   */
  private List<String> buildRouteEditorPages(
      StorageProvider provider, Operator operator, Line line, Route route, List<RouteStop> stops) {
    List<String> rendered = new ArrayList<>();
    rendered.add("# FetaruteTCAddon Route Editor");
    rendered.add("# Line: " + operator.code() + "/" + line.code());
    rendered.add("# Route: " + route.code());
    rendered.add("#");
    rendered.add("# 规则：每行=stop/action；空行/注释忽略");
    rendered.add("# stop: StationCode 或 NodeId");
    rendered.add("#   NodeId 例: " + operator.code() + ":S:PTK:1");
    rendered.add("#           或: " + operator.code() + ":A:B:1:00");
    rendered.add("# action: CHANGE/DYNAMIC/ACTION");
    rendered.add("# 修饰: PASS/STOP/TERM, dwell=<秒>");
    rendered.add("#");

    if (stops.isEmpty()) {
      rendered.add("# 示例：");
      rendered.add("# STOP PTK dwell=30");
      rendered.add("# CHANGE:" + operator.code() + ":" + line.code());
      rendered.add("# PASS " + operator.code() + ":A:B:1:00");
      rendered.add("# STOP " + operator.code() + ":D:DEPOT:1");
      rendered.add("# STOP " + operator.code() + ":D:DEPOT:1:01");
      rendered.add("");
    } else {
      rendered.add("# 已加载现有停靠表（可直接修改后 define 覆盖）");
      rendered.add("");
      for (RouteStop stop : stops) {
        rendered.add(renderStopLine(provider, operator.id(), stop));
        stop.notes().stream()
            .flatMap(s -> Stream.of(s.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)))
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .forEach(rendered::add);
      }
      rendered.add("");
    }

    rendered.add("# 在编辑完成后执行：");
    rendered.add("# /fta route define <company> <operator> <line> <route>");

    return splitPages(rendered, 11);
  }

  /**
   * 构建 Route Editor 的空模板内容。
   *
   * <p>空模板不绑定 routeId：用于“先写草稿/拿来当剪贴板”，以及右键牌子快速收集 nodeId。
   */
  private List<String> buildRouteEditorEmptyPages(LocaleManager locale) {
    if (locale != null) {
      List<String> template = locale.stringList("command.route.editor.book.empty-template");
      if (!template.isEmpty()) {
        return splitPages(template, 11);
      }
    }

    // 兜底：语言键缺失时仍给出可用帮助。
    List<String> rendered = new ArrayList<>();
    rendered.add("# FetaruteTCAddon Route Editor");
    rendered.add("# 空模板（未绑定 Route）");
    rendered.add("#");
    rendered.add("# 用法：每行一个 stop/action");
    rendered.add("# stop: StationCode 或 NodeId");
    rendered.add("# action: CHANGE/DYNAMIC/ACTION");
    rendered.add("# 修饰: PASS/STOP/TERM, dwell=<秒>");
    rendered.add("#");
    rendered.add("# 右键 waypoint/autostation/depot");
    rendered.add("# 牌子可把 nodeId 追加到末尾");
    rendered.add("#");
    rendered.add("# 示例：");
    rendered.add("# PTK");
    rendered.add("# SURN:D:AAA:1");
    rendered.add("# SURN:D:AAA:1:01");
    rendered.add("# SURN:AAA:BBB:1:00");
    rendered.add("# DYNAMIC:SURN:PTK:[1:3]");
    return splitPages(rendered, 11);
  }

  /**
   * 将一个 RouteStop 渲染为 stop 行。
   *
   * <p>优先显示 waypointNodeId（用于库口/咽喉/区间点）；否则尝试回填 stationId 对应的站点 code。
   */
  private String renderStopLine(StorageProvider provider, UUID operatorId, RouteStop stop) {
    String prefix =
        switch (stop.passType()) {
          case PASS -> "PASS ";
          case TERMINATE -> "TERM ";
          case STOP -> "STOP ";
        };
    String dwell = stop.dwellSeconds().map(value -> " dwell=" + value).orElse("");
    String target =
        stop.waypointNodeId()
            .filter(s -> !s.isBlank())
            .or(
                () ->
                    stop.stationId()
                        .flatMap(provider.stations()::findById)
                        .map(Station::code)
                        .filter(s -> !s.isBlank()))
            .orElse("<unknown>");
    return prefix + target + dwell;
  }

  /**
   * 将文本行切分为若干页。
   *
   * <p>这里按“行数”切页（而不是字符数），原因是 MC 客户端对书本换行显示更稳定；后续若需要更严格的分页，可再 引入字符限制。
   */
  private List<String> splitPages(List<String> lines, int maxLinesPerPage) {
    List<String> pages = new ArrayList<>();
    StringBuilder page = new StringBuilder();
    int count = 0;
    for (String line : lines) {
      if (count >= maxLinesPerPage) {
        pages.add(page.toString());
        page = new StringBuilder();
        count = 0;
      }
      if (page.length() > 0) {
        page.append('\n');
      }
      page.append(line);
      count++;
    }
    if (page.length() > 0) {
      pages.add(page.toString());
    }
    return pages;
  }

  /**
   * 将 route 上下文写入编辑书的 PDC（NBT）。
   *
   * <p>这些字段用于：
   *
   * <ul>
   *   <li>识别“这本书属于哪条 Route”（routeId）
   *   <li>在 define 后归档成书时保留元信息（company/operator/line/route）
   * </ul>
   */
  private void persistBookContext(
      BookMeta meta, Company company, Operator operator, Line line, Route route) {
    PersistentDataContainer container = meta.getPersistentDataContainer();
    container.set(bookEditorMarkerKey, PersistentDataType.BYTE, (byte) 1);
    container.set(bookRouteIdKey, PersistentDataType.STRING, route.id().toString());
    container.set(bookCompanyKey, PersistentDataType.STRING, company.code());
    container.set(bookOperatorKey, PersistentDataType.STRING, operator.code());
    container.set(bookLineKey, PersistentDataType.STRING, line.code());
    container.set(bookRouteCodeKey, PersistentDataType.STRING, route.code());
  }

  /**
   * 从物品的 PDC（NBT）读取 Route Editor 上下文。
   *
   * <p>仅当 {@code route_editor_route_id} 存在且可解析为 UUID 时，才认为这是“插件生成的编辑书/成书”。
   */
  private Optional<RouteBookContext> readBookContext(ItemStack item) {
    if (item == null || item.getType() == Material.AIR) {
      return Optional.empty();
    }
    if (!(item.getItemMeta() instanceof BookMeta meta)) {
      return Optional.empty();
    }
    PersistentDataContainer container = meta.getPersistentDataContainer();
    String routeIdRaw = container.get(bookRouteIdKey, PersistentDataType.STRING);
    if (routeIdRaw == null || routeIdRaw.isBlank()) {
      return Optional.empty();
    }
    Optional<UUID> routeId = tryParseUuid(routeIdRaw);
    if (routeId.isEmpty()) {
      return Optional.empty();
    }
    String company = container.get(bookCompanyKey, PersistentDataType.STRING);
    String operator = container.get(bookOperatorKey, PersistentDataType.STRING);
    String line = container.get(bookLineKey, PersistentDataType.STRING);
    String route = container.get(bookRouteCodeKey, PersistentDataType.STRING);
    Long definedAt = container.get(bookDefinedAtKey, PersistentDataType.LONG);
    return Optional.of(
        new RouteBookContext(
            company == null ? "" : company,
            operator == null ? "" : operator,
            line == null ? "" : line,
            route == null ? "" : route,
            routeId.get(),
            Optional.ofNullable(definedAt)));
  }

  /**
   * 在不同书本类型（书与笔/成书）之间复制上下文。
   *
   * <p>注意：这里只复制我们插件自用的 key，避免污染或覆盖其他插件的数据。
   */
  private void copyBookContext(BookMeta from, BookMeta to) {
    PersistentDataContainer src = from.getPersistentDataContainer();
    PersistentDataContainer dst = to.getPersistentDataContainer();
    Byte marker = src.get(bookEditorMarkerKey, PersistentDataType.BYTE);
    if (marker != null) {
      dst.set(bookEditorMarkerKey, PersistentDataType.BYTE, marker);
    }
    List<NamespacedKey> keys =
        List.of(bookRouteIdKey, bookCompanyKey, bookOperatorKey, bookLineKey, bookRouteCodeKey);
    for (NamespacedKey key : keys) {
      String value = src.get(key, PersistentDataType.STRING);
      if (value != null) {
        dst.set(key, PersistentDataType.STRING, value);
      }
    }
    Long definedAt = src.get(bookDefinedAtKey, PersistentDataType.LONG);
    if (definedAt != null) {
      dst.set(bookDefinedAtKey, PersistentDataType.LONG, definedAt);
    }
  }

  /**
   * 若玩家主手拿着该 route 的编辑书/成书，则在 define 成功后将其归档为成书。
   *
   * <p>归档内容包含：标题（FTA:&lt;OP&gt;:&lt;LINE&gt;:&lt;ROUTE&gt;）、写入时间 lore、以及预载 stops 的书页内容， 方便玩家存档与分享。
   */
  private void archiveBookIfHolding(
      LocaleManager locale,
      Player sender,
      StorageProvider provider,
      ResolvedRoute resolved,
      List<RouteStop> stops) {
    ItemStack item = sender.getInventory().getItemInMainHand();
    if (item.getType() == Material.AIR) {
      return;
    }
    Optional<RouteBookContext> contextOpt = readBookContext(item);
    if (contextOpt.isEmpty()) {
      return;
    }
    RouteBookContext context = contextOpt.get();
    if (!context.routeId().equals(resolved.route().id())) {
      return;
    }
    if (!(item.getItemMeta() instanceof BookMeta meta)) {
      return;
    }

    Instant now = Instant.now();
    // 归档策略：将当前主手替换为成书，并把写入时间写入 PDC，便于玩家留档与追溯。
    ItemStack archived = new ItemStack(Material.WRITTEN_BOOK, 1);
    if (archived.getItemMeta() instanceof BookMeta archivedMeta) {
      copyBookContext(meta, archivedMeta);
      archivedMeta
          .getPersistentDataContainer()
          .set(bookDefinedAtKey, PersistentDataType.LONG, now.toEpochMilli());
      archivedMeta.displayName(
          locale.component(
              "command.route.editor.book.name",
              Map.of("line", resolved.line().code(), "route", resolved.route().code())));
      archivedMeta.lore(
          List.of(
              locale.component("command.route.editor.archived.lore-1"),
              locale.component(
                  "command.route.editor.archived.lore-2", Map.of("when", now.toString()))));
      archivedMeta.setAuthor(sender.getName());
      archivedMeta.setTitle(
          "FTA:"
              + resolved.operator().code()
              + ":"
              + resolved.line().code()
              + ":"
              + resolved.route().code());
      archivedMeta.pages(
          toComponents(
              buildRouteEditorPages(
                  provider, resolved.operator(), resolved.line(), resolved.route(), stops)));
      archived.setItemMeta(archivedMeta);
    }

    sender.getInventory().setItemInMainHand(archived);
    sender.sendMessage(locale.component("command.route.editor.archived.success"));
  }

  /**
   * define 后回显解析结果（分页，每页 10 条）。
   *
   * <p>输出使用 hover 展示细节（passType/dwell/station/node），并提供建议命令：
   *
   * <ul>
   *   <li>若站点有坐标：给出 /tp x y z
   *   <li>否则若有 nodeId：给出 /train debug destination &lt;nodeId&gt;
   * </ul>
   */
  private void sendStopDebug(
      LocaleManager locale,
      Player sender,
      StorageProvider provider,
      UUID operatorId,
      List<RouteStop> stops) {
    if (stops.isEmpty()) {
      return;
    }
    StopDebugSession session = new StopDebugSession(Instant.now(), operatorId, List.copyOf(stops));
    stopDebugSessions.put(sender.getUniqueId(), session);
    sendStopDebugPage(locale, sender, provider, session, 1);
  }

  private void sendStopDebugPage(
      LocaleManager locale,
      Player sender,
      StorageProvider provider,
      StopDebugSession session,
      int page) {
    int total = session.stops().size();
    if (total <= 0) {
      return;
    }

    int pageCount = (total + STOP_DEBUG_PAGE_SIZE - 1) / STOP_DEBUG_PAGE_SIZE;
    int safePage = Math.max(1, Math.min(page, pageCount));
    int start = (safePage - 1) * STOP_DEBUG_PAGE_SIZE;
    int end = Math.min(start + STOP_DEBUG_PAGE_SIZE, total);

    sender.sendMessage(locale.component("command.route.define.debug.header"));
    sender.sendMessage(buildStopDebugNav(locale, safePage, pageCount));

    for (int i = start; i < end; i++) {
      RouteStop stop = session.stops().get(i);
      String index = String.valueOf(stop.sequence());
      String pass = locale.enumText("enum.route-stop-pass-type", stop.passType());
      String dwellBaseline = stop.dwellSeconds().map(String::valueOf).orElse("-");
      String node = stop.waypointNodeId().orElse("-");
      String stationCode =
          stop.stationId()
              .flatMap(provider.stations()::findById)
              .map(Station::code)
              .orElseGet(
                  () ->
                      stop.waypointNodeId()
                          .flatMap(id -> tryExtractStationCodeFromNodeId(id, true))
                          .orElse("-"));

      String tpCommand =
          resolveTeleportCommand(provider, session.operatorId(), stationCode)
              .orElseGet(() -> node.equals("-") ? "" : "/train debug destination " + node);

      sender.sendMessage(
          locale
              .component(
                  "command.route.define.debug.entry",
                  Map.of("seq", index, "target", stationCode.equals("-") ? node : stationCode))
              .hoverEvent(
                  HoverEvent.showText(
                      locale.component(
                          "command.route.define.debug.hover",
                          Map.of(
                              "seq",
                              index,
                              "pass",
                              pass,
                              "dwell",
                              dwellBaseline,
                              "station",
                              stationCode,
                              "node",
                              node,
                              "tp",
                              tpCommand)))));
    }
    sender.sendMessage(buildStopDebugNav(locale, safePage, pageCount));
  }

  private Component buildStopDebugNav(LocaleManager locale, int page, int pageCount) {
    Component pageText =
        locale.component(
            "command.route.define.debug.page",
            Map.of("page", String.valueOf(page), "pages", String.valueOf(pageCount)));

    Component prev =
        page > 1
            ? locale
                .component("command.route.define.debug.nav.prev")
                .clickEvent(ClickEvent.runCommand("/fta route define debug " + (page - 1)))
            : locale.component("command.route.define.debug.nav.prev-disabled");
    Component next =
        page < pageCount
            ? locale
                .component("command.route.define.debug.nav.next")
                .clickEvent(ClickEvent.runCommand("/fta route define debug " + (page + 1)))
            : locale.component("command.route.define.debug.nav.next-disabled");
    Component hint = locale.component("command.route.define.debug.nav.hint");

    return Component.empty()
        .append(pageText)
        .append(Component.space())
        .append(prev)
        .append(Component.space())
        .append(next)
        .append(Component.space())
        .append(hint);
  }

  private record StopDebugSession(Instant createdAt, UUID operatorId, List<RouteStop> stops) {
    StopDebugSession {
      Objects.requireNonNull(createdAt, "createdAt");
      Objects.requireNonNull(operatorId, "operatorId");
      stops = stops == null ? List.of() : List.copyOf(stops);
    }

    boolean isExpired() {
      return createdAt.plus(STOP_DEBUG_SESSION_TTL).isBefore(Instant.now());
    }
  }

  /**
   * 根据 stationCode 解析可用的传送命令。
   *
   * <p>当前仅输出坐标 tp（不包含 world），避免跨世界误传送；后续可根据 world 字段扩展为 /execute in 或显式 world 传送。
   */
  private Optional<String> resolveTeleportCommand(
      StorageProvider provider, UUID operatorId, String stationCode) {
    if (stationCode == null || stationCode.isBlank() || stationCode.equals("-")) {
      return Optional.empty();
    }
    Optional<Station> stationOpt =
        provider.stations().findByOperatorAndCode(operatorId, stationCode);
    if (stationOpt.isEmpty()) {
      return Optional.empty();
    }
    Station station = stationOpt.get();
    if (station.world().isEmpty() || station.location().isEmpty()) {
      return Optional.empty();
    }
    var loc = station.location().get();
    return Optional.of(
        "/tp "
            + formatTpNumber(loc.x())
            + " "
            + formatTpNumber(loc.y())
            + " "
            + formatTpNumber(loc.z()));
  }

  private String formatTpNumber(double value) {
    return String.format(Locale.ROOT, "%.2f", value);
  }

  /**
   * 从 NodeId 中尝试推导站点 code（用于 debug 回显）。
   *
   * <p>仅支持 4 段格式（Operator:S:Station:Track），因为这类节点天然绑定某个站点；区间点/其他节点无法可靠推导。
   */
  private Optional<String> tryExtractStationCodeFromNodeId(String nodeIdRaw, boolean onlyStation) {
    if (nodeIdRaw == null) {
      return Optional.empty();
    }
    String[] segments = nodeIdRaw.trim().split(":");
    if (segments.length != 4) {
      return Optional.empty();
    }
    String second = segments[1].trim();
    if (second.isEmpty()) {
      return Optional.empty();
    }
    if (onlyStation && !"S".equalsIgnoreCase(second)) {
      return Optional.empty();
    }
    String name = segments[2].trim();
    if (name.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(name);
  }

  /** 将字符串安全解析为 UUID，用于从 PDC 中读取 routeId。 */
  private Optional<UUID> tryParseUuid(String raw) {
    try {
      return Optional.of(UUID.fromString(raw));
    } catch (IllegalArgumentException ex) {
      return Optional.empty();
    }
  }

  /**
   * Route Editor 的书本上下文。
   *
   * <p>其中 routeId 是唯一可信的绑定键；company/operator/line/routeCode 用于展示与归档。
   */
  private record RouteBookContext(
      String companyCode,
      String operatorCode,
      String lineCode,
      String routeCode,
      UUID routeId,
      Optional<Long> definedAtEpochMilli) {}

  private List<Component> toComponents(List<String> pages) {
    List<Component> components = new ArrayList<>(pages.size());
    for (String page : pages) {
      components.add(Component.text(page == null ? "" : page));
    }
    return components;
  }
}
