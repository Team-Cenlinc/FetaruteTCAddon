package org.fetarute.fetaruteTCAddon.command;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.api.CompanyQueryService;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.CompanyMember;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.PlayerIdentity;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPath;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPathFinder;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteStopResolver;
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
 * <p>运行图编辑器的核心目标：
 *
 * <ul>
 *   <li>把 RouteStop 的编辑从“长命令参数”变成“可复制/可存档的文本”
 *   <li>通过 NBT（PDC）保存 routeId 等上下文，避免用 lore/标题做脆弱识别
 *   <li>define 后回显解析结果，便于调试（如生成 /tp 或 /train debug destination 的建议）
 * </ul>
 */
public final class FtaRouteCommand {

  private static final int SUGGESTION_LIMIT = 20;
  private static final int VALIDATION_ISSUE_LIMIT = 20;

  /** define 后的解析回显分页大小（避免一次输出过长刷屏）。 */
  private static final int STOP_DEBUG_PAGE_SIZE = 10;

  private static final Pattern DWELL_PATTERN =
      Pattern.compile("\\bdwell=(\\d+)\\b", Pattern.CASE_INSENSITIVE);
  private static final Set<String> ACTION_PREFIXES = Set.of("CHANGE", "DYNAMIC", "ACTION");
  private static final Set<String> DIRECTIVE_PREFIXES = Set.of("CRET", "DSTY");
  private static final List<String> ROUTE_PATTERN_ALIASES =
      List.of("LOCAL", "RAPID", "NEO_RAPID", "EXPRESS", "LTD_EXPRESS", "LIMITED_EXPRESS");
  private static final List<String> ROUTE_OPERATION_ALIASES =
      List.of("OPERATION", "RETURN", "OP", "RET");
  private static final PlainTextComponentSerializer PLAIN_TEXT =
      PlainTextComponentSerializer.plainText();

  private final FetaruteTCAddon plugin;
  // 运行图编辑器书本上下文存储在 PersistentDataContainer 中：这是“机器可读”的唯一可信来源。
  // 之所以不用 lore/title 做识别：它们受语言/格式化影响，且用户可手动改名导致误判。
  private final NamespacedKey bookRouteIdKey;
  private final NamespacedKey bookCompanyKey;
  private final NamespacedKey bookOperatorKey;
  private final NamespacedKey bookLineKey;
  private final NamespacedKey bookRouteCodeKey;
  private final NamespacedKey bookDefinedAtKey;
  private final NamespacedKey bookEditorMarkerKey;

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
    SuggestionProvider<CommandSender> operationValueSuggestions = routeOperationSuggestions();

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
    var operationFlag =
        CommandFlag.<CommandSender>builder("operation")
            .withComponent(
                CommandComponent.<CommandSender, String>builder(
                        "operation", StringParser.stringParser())
                    .suggestionProvider(operationValueSuggestions))
            .build();
    var runtimeFlag =
        CommandFlag.builder("runtime").withComponent(IntegerParser.integerParser()).build();
    var distanceFlag =
        CommandFlag.builder("distance").withComponent(IntegerParser.integerParser()).build();
    var spawnFlag =
        CommandFlag.<CommandSender>builder("spawn")
            .withComponent(
                CommandComponent.<CommandSender, String>builder(
                        "spawn", StringParser.greedyStringParser())
                    .suggestionProvider(placeholderSuggestion("\"<pattern>\"")))
            .build();
    var spawnClearFlag = CommandFlag.builder("spawn-clear").build();
    var spawnEnabledFlag =
        CommandFlag.<CommandSender>builder("spawn-enabled")
            .withComponent(
                CommandComponent.<CommandSender, String>builder(
                        "spawn-enabled", StringParser.stringParser())
                    .suggestionProvider(placeholderSuggestion("<true|false>")))
            .build();
    var spawnWeightFlag =
        CommandFlag.<CommandSender>builder("spawn-weight")
            .withComponent(
                CommandComponent.<CommandSender, Integer>builder(
                        "spawn-weight", IntegerParser.integerParser())
                    .suggestionProvider(placeholderSuggestion("<weight>")))
            .build();

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
            .flag(operationFlag)
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
                  String operationRaw =
                      flags.getValue(operationFlag, RouteOperationType.OPERATION.name());
                  Optional<RouteOperationType> operationOpt =
                      RouteOperationType.fromToken(operationRaw);
                  if (operationOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.route.enum-invalid",
                            Map.of("field", "operation", "value", String.valueOf(operationRaw))));
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
                          operationOpt.get(),
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
                    String operation =
                        locale.enumText("enum.route-operation-type", route.operationType());
                    sender.sendMessage(
                        locale
                            .component(
                                "command.route.list.entry",
                                Map.of(
                                    "code",
                                    route.code(),
                                    "name",
                                    route.name(),
                                    "pattern",
                                    pattern,
                                    "operation",
                                    operation))
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
                                            "operation",
                                            operation,
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
                          "command.route.info.operation",
                          Map.of(
                              "operation",
                              locale.enumText(
                                  "enum.route-operation-type", route.operationType()))));
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
                  String spawnPattern = "-";
                  Object rawSpawn = route.metadata().get("spawn_train_pattern");
                  if (rawSpawn instanceof String raw && !raw.isBlank()) {
                    spawnPattern = raw.trim();
                  }
                  sender.sendMessage(
                      locale.component(
                          "command.route.info.spawn-pattern", Map.of("pattern", spawnPattern)));

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
            .literal("validate")
            .senderType(Player.class)
            .required("company", StringParser.stringParser(), companySuggestions)
            .required("operator", StringParser.stringParser(), operatorSuggestions)
            .required("line", StringParser.stringParser(), lineSuggestions)
            .optional("route", StringParser.stringParser(), routeSuggestions)
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

                  List<Route> routes = new ArrayList<>();
                  Optional<String> routeArgOpt = ctx.optional("route").map(String.class::cast);
                  if (routeArgOpt.isPresent()) {
                    String routeCode = routeArgOpt.get().trim();
                    Optional<Route> routeOpt =
                        provider.routes().findByLineAndCode(resolved.line().id(), routeCode);
                    if (routeOpt.isEmpty()) {
                      sender.sendMessage(
                          locale.component("command.route.not-found", Map.of("route", routeCode)));
                      return;
                    }
                    routes.add(routeOpt.get());
                  } else {
                    routes.addAll(provider.routes().listByLine(resolved.line().id()));
                  }
                  if (routes.isEmpty()) {
                    sender.sendMessage(locale.component("command.route.list.empty"));
                    return;
                  }

                  List<RouteValidationEntry> entries = new ArrayList<>();
                  int routeIssueCount = 0;
                  boolean reachabilitySkipped = false;
                  for (Route route : routes) {
                    List<RouteStop> stops = provider.routeStops().listByRoute(route.id());
                    RouteValidationResult result = validateRouteStops(provider, stops, true);
                    if (result.reachabilitySkipped()) {
                      reachabilitySkipped = true;
                    }
                    if (result.issues().isEmpty()) {
                      continue;
                    }
                    routeIssueCount++;
                    for (RouteValidationIssue issue : result.issues()) {
                      entries.add(new RouteValidationEntry(route.code(), issue));
                    }
                  }

                  sender.sendMessage(
                      locale.component(
                          "command.route.validate.header",
                          Map.of("count", String.valueOf(routes.size()))));
                  if (reachabilitySkipped) {
                    sender.sendMessage(locale.component("command.route.define.graph-missing"));
                  }
                  if (entries.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.route.validate.ok",
                            Map.of("count", String.valueOf(routes.size()))));
                    return;
                  }
                  sender.sendMessage(
                      locale.component(
                          "command.route.validate.summary",
                          Map.of(
                              "routes",
                              String.valueOf(routeIssueCount),
                              "issues",
                              String.valueOf(entries.size()))));
                  int limit = Math.min(entries.size(), VALIDATION_ISSUE_LIMIT);
                  for (int i = 0; i < limit; i++) {
                    RouteValidationEntry entry = entries.get(i);
                    Component error = locale.component(entry.issue().key(), entry.issue().params());
                    sender.sendMessage(
                        locale.component(
                            "command.route.validate.entry",
                            TagResolver.builder()
                                .resolver(Placeholder.unparsed("route", entry.routeCode()))
                                .resolver(Placeholder.component("error", error))
                                .build()));
                  }
                  if (entries.size() > limit) {
                    sender.sendMessage(
                        locale.component(
                            "command.route.validate.more",
                            Map.of("count", String.valueOf(entries.size() - limit))));
                  }
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
            .flag(operationFlag)
            .flag(runtimeFlag)
            .flag(distanceFlag)
            .flag(spawnFlag)
            .flag(spawnClearFlag)
            .flag(spawnEnabledFlag)
            .flag(spawnWeightFlag)
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
                          || flags.hasFlag(operationFlag)
                          || flags.hasFlag(runtimeFlag)
                          || flags.hasFlag(distanceFlag)
                          || flags.hasFlag(spawnFlag)
                          || flags.hasFlag(spawnClearFlag)
                          || flags.hasFlag(spawnEnabledFlag)
                          || flags.hasFlag(spawnWeightFlag);
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
                  String operationRaw = flags.getValue(operationFlag, route.operationType().name());
                  Optional<RouteOperationType> operationOpt =
                      RouteOperationType.fromToken(operationRaw);
                  if (operationOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.route.enum-invalid",
                            Map.of("field", "operation", "value", String.valueOf(operationRaw))));
                    return;
                  }
                  Integer runtime =
                      flags.getValue(runtimeFlag, route.runtimeSeconds().orElse(null));
                  Integer distance =
                      flags.getValue(distanceFlag, route.distanceMeters().orElse(null));
                  Map<String, Object> metadata = new java.util.HashMap<>(route.metadata());
                  if (flags.hasFlag(spawnClearFlag)) {
                    metadata.remove("spawn_train_pattern");
                  }
                  if (flags.hasFlag(spawnFlag)) {
                    String spawnPattern = normalizeSpawnPattern(flags.getValue(spawnFlag, null));
                    if (spawnPattern == null) {
                      metadata.remove("spawn_train_pattern");
                    } else {
                      metadata.put("spawn_train_pattern", spawnPattern);
                    }
                  }
                  if (flags.hasFlag(spawnEnabledFlag)) {
                    Optional<Boolean> enabled =
                        parseBooleanToken(flags.getValue(spawnEnabledFlag, null));
                    if (enabled.isPresent()) {
                      metadata.put("spawn_enabled", enabled.get());
                    } else {
                      sender.sendMessage(
                          locale.component(
                              "command.common.invalid-boolean",
                              Map.of(
                                  "value",
                                  String.valueOf(flags.getValue(spawnEnabledFlag, null)))));
                      return;
                    }
                  }
                  if (flags.hasFlag(spawnWeightFlag)) {
                    Integer weight = flags.getValue(spawnWeightFlag, null);
                    if (weight == null || weight <= 0) {
                      metadata.remove("spawn_weight");
                    } else {
                      metadata.put("spawn_weight", weight);
                    }
                  }

                  Route updated =
                      new Route(
                          route.id(),
                          route.code(),
                          route.lineId(),
                          name,
                          Optional.ofNullable(secondary),
                          patternOpt.get(),
                          operationOpt.get(),
                          Optional.ofNullable(distance),
                          Optional.ofNullable(runtime),
                          metadata,
                          route.createdAt(),
                          Instant.now());
                  provider.routes().save(updated);
                  plugin.refreshRouteDefinition(
                      provider, resolved.operator(), resolved.line(), updated);
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
                  plugin.removeRouteDefinition(resolved.operator(), resolved.line(), route);
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
                  RouteValidationResult validation = validateRouteStops(provider, stops, true);
                  if (validation.reachabilitySkipped()) {
                    sender.sendMessage(locale.component("command.route.define.graph-missing"));
                  }
                  if (!validation.issues().isEmpty()) {
                    for (RouteValidationIssue issue : validation.issues()) {
                      sender.sendMessage(locale.component(issue.key(), issue.params()));
                    }
                    return;
                  }

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
                  plugin.refreshRouteDefinition(
                      provider, resolved.operator(), resolved.line(), route);
                  sender.sendMessage(
                      locale.component(
                          "command.route.define.success",
                          Map.of("code", route.code(), "count", String.valueOf(stops.size()))));

                  // 若玩家手持的是该 route 的编辑书/成书，则在 define 后将其归档为“成书”，便于存档与回溯。
                  archiveBookIfHolding(locale, sender, provider, resolved, stops);
                  // define 后回显解析结果，便于玩家检查 PASS/TERM/dwell/nodeId 等是否符合预期。
                  String navCommand =
                      "/fta route debug "
                          + resolved.company().code()
                          + " "
                          + resolved.operator().code()
                          + " "
                          + resolved.line().code()
                          + " "
                          + route.code()
                          + " ";
                  sendStopDebug(
                      locale, sender, provider, resolved.operator().id(), stops, navCommand);
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("route")
            .literal("debug")
            .senderType(Player.class)
            .required("company", StringParser.stringParser(), companySuggestions)
            .required("operator", StringParser.stringParser(), operatorSuggestions)
            .required("line", StringParser.stringParser(), lineSuggestions)
            .required("route", StringParser.stringParser(), routeSuggestions)
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

                  ResolvedRoute resolved =
                      resolveRoute(ctx, provider, locale, new CompanyQueryService(provider), false);
                  if (resolved == null) {
                    return;
                  }
                  List<RouteStop> stops = provider.routeStops().listByRoute(resolved.route().id());
                  if (stops.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.route.debug.empty", Map.of("route", resolved.route().code())));
                    return;
                  }
                  int page = ctx.optional("page").map(Integer.class::cast).orElse(1);
                  StopDebugSession session = new StopDebugSession(resolved.operator().id(), stops);
                  List<DebugStopEntry> entries = buildDebugEntries(locale, provider, session);
                  String navCommand =
                      "/fta route debug "
                          + resolved.company().code()
                          + " "
                          + resolved.operator().code()
                          + " "
                          + resolved.line().code()
                          + " "
                          + resolved.route().code()
                          + " ";
                  sendStopDebugPage(
                      locale,
                      sender,
                      provider,
                      resolved.operator().id(),
                      entries,
                      page,
                      navCommand);
                }));

    // /fta route path <company> <operator> <line> <route> - 显示扩展后的实际路径（包含图最短路填充的中间节点）
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("route")
            .literal("path")
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

                  ResolvedRoute resolved =
                      resolveRoute(ctx, provider, locale, new CompanyQueryService(provider), false);
                  if (resolved == null) {
                    return;
                  }

                  // 尝试获取 RouteDefinition
                  if (!plugin.isRouteDefinitionCacheReady()) {
                    sender.sendMessage(locale.component("command.route.path.cache-unavailable"));
                    return;
                  }
                  var definitionOpt =
                      plugin.findRouteDefinitionByCodes(
                          resolved.operator().code(),
                          resolved.line().code(),
                          resolved.route().code());
                  if (definitionOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.route.path.no-definition",
                            Map.of("route", resolved.route().code())));
                    return;
                  }
                  var definition = definitionOpt.get();

                  // 获取 RailGraph
                  if (plugin.getRailGraphService() == null
                      || plugin.getRailGraphService().snapshotCount() <= 0) {
                    sender.sendMessage(locale.component("command.route.path.no-graph"));
                    return;
                  }

                  // 计算扩展路径
                  sendExpandedPath(locale, sender, definition);
                }));
  }

  /** 获取已就绪的 StorageProvider；未就绪时向用户输出统一错误文案。 */
  private Optional<StorageProvider> readyProvider(CommandSender sender) {
    return CommandStorageProviders.readyProvider(sender, plugin);
  }

  /** 仅在 StorageManager ready 的情况下返回 provider，避免命令线程触发初始化或抛异常。 */
  private Optional<StorageProvider> providerIfReady() {
    return CommandStorageProviders.providerIfReady(plugin);
  }

  /** 判断 sender 是否具备读取指定公司的权限（公司成员或管理员）。 */
  private boolean canReadCompany(CommandSender sender, StorageProvider provider, UUID companyId) {
    return CompanyAccessChecker.canReadCompany(sender, provider, companyId);
  }

  /** 判断 sender 是否具备管理指定公司的权限（Owner/Manager 或管理员）。 */
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

  /** operation flag value 补全：提供占位符与常用别名（支持前缀过滤）。 */
  private static SuggestionProvider<CommandSender> routeOperationSuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = input.lastRemainingToken().trim().toUpperCase(Locale.ROOT);
          List<String> suggestions = new ArrayList<>();
          if (prefix.isBlank()) {
            suggestions.add("<operation>");
          }
          for (String value : ROUTE_OPERATION_ALIASES) {
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
   *   <li>CRET/DSTY 作为指令行：直接生成对应的节点 stop，并写入 notes 便于后续识别（运行时视为不停车）
   *   <li>stop 行支持 PASS/STOP/TERM 前缀；支持 dwell=&lt;秒&gt; 参数（基准停车时间，运行时可按调度策略增减）
   *   <li>含冒号的行视为 NodeId，写入 waypointNodeId（字符串原样保存）
   *   <li>不含冒号的行视为 StationCode，需在 stations 表中存在，否则报错
   * </ul>
   *
   * <p>这里不对 NodeId 做强校验：因为节点可能来自轨道牌子注册或后续扩展（如 Switcher）。
   */
  Optional<List<RouteStop>> parseStopsFromBook(
      LocaleManager locale,
      StorageProvider provider,
      UUID operatorId,
      UUID routeId,
      Player sender,
      List<BookLine> lines) {
    List<RouteStop> stops = new ArrayList<>();
    boolean dstySeen = false;
    for (BookLine line : lines) {
      String raw = line.text();
      if (raw == null) {
        continue;
      }
      String trimmed = raw.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) {
        continue;
      }

      Optional<DirectiveLine> directiveOpt = parseDirectiveLine(trimmed);
      if (directiveOpt.isPresent()) {
        DirectiveLine directive = directiveOpt.get();
        if (directive.argument().isBlank()) {
          sender.sendMessage(
              locale.component(
                  "command.route.define.invalid-line",
                  Map.of("line", String.valueOf(line.lineNo()), "text", trimmed)));
          return Optional.empty();
        }
        // CRET/DSTY 支持两种格式：
        // 1. CRET <nodeId> [DYNAMIC:...]  -> 显式 nodeId + 可选 action
        // 2. CRET DYNAMIC:OP:STATION:[range] -> 由 DYNAMIC 推导 nodeId
        String argument = directive.argument();
        String[] argTokens = argument.split("\\s+", 2);
        String firstToken = argTokens[0].trim();
        List<String> trailingActions = new ArrayList<>();

        // 检查第一个 token 是否是 DYNAMIC action（无显式 nodeId 的简写形式）
        boolean firstTokenIsDynamic =
            isActionLine(firstToken)
                && firstToken.toUpperCase(java.util.Locale.ROOT).startsWith("DYNAMIC:");
        String nodeIdPart;
        if (firstTokenIsDynamic) {
          // CRET DYNAMIC:OP:STATION:[range] -> DYNAMIC 运行时动态选择，不写入固定 nodeId
          Optional<DynamicNodeSpec> specOpt = parseDynamicNodeSpec(normalizeActionLine(firstToken));
          if (specOpt.isEmpty()) {
            sender.sendMessage(
                locale.component(
                    "command.route.define.invalid-line",
                    Map.of("line", String.valueOf(line.lineNo()), "text", trimmed)));
            return Optional.empty();
          }
          // nodeIdPart 置空，waypointNodeId 留空
          nodeIdPart = null;
          trailingActions.add(normalizeActionLine(firstToken));
          // 继续解析尾部（如果有）
          if (argTokens.length > 1) {
            String trailing = argTokens[1].trim();
            for (String token : trailing.split("\\s+")) {
              String t = token == null ? "" : token.trim();
              if (t.isEmpty()) {
                continue;
              }
              if (isActionLine(t)) {
                trailingActions.add(normalizeActionLine(t));
              } else {
                sender.sendMessage(
                    locale.component(
                        "command.route.define.invalid-line",
                        Map.of("line", String.valueOf(line.lineNo()), "text", trimmed)));
                return Optional.empty();
              }
            }
          }
        } else {
          // CRET <nodeId> [DYNAMIC:...] -> 显式 nodeId
          nodeIdPart = firstToken;
          if (argTokens.length > 1) {
            String trailing = argTokens[1].trim();
            for (String token : trailing.split("\\s+")) {
              String t = token == null ? "" : token.trim();
              if (t.isEmpty()) {
                continue;
              }
              if (isActionLine(t)) {
                trailingActions.add(normalizeActionLine(t));
              } else {
                sender.sendMessage(
                    locale.component(
                        "command.route.define.invalid-line",
                        Map.of("line", String.valueOf(line.lineNo()), "text", trimmed)));
                return Optional.empty();
              }
            }
          }
        }
        // 重新拼接 directive
        // 当使用 DYNAMIC 简写时（nodeIdPart == null），notes 应写成 "CRET DYNAMIC:OP:D:OFL" 形式
        // 而不是分行写 "CRET\nDYNAMIC:..."，这样 renderStopLine 才能正确解析
        String mergedNotes;
        if (nodeIdPart == null && !trailingActions.isEmpty()) {
          // CRET/DSTY DYNAMIC:... -> 合并为单行 "CRET DYNAMIC:..."
          mergedNotes = directive.prefix() + " " + String.join(" ", trailingActions);
        } else if (nodeIdPart != null) {
          // CRET/DSTY <nodeId> [actions...] -> "CRET nodeId\naction1\naction2..."
          String baseDirective = directive.prefix() + " " + nodeIdPart;
          mergedNotes =
              trailingActions.isEmpty()
                  ? baseDirective
                  : baseDirective + "\n" + String.join("\n", trailingActions);
        } else {
          // CRET/DSTY 无参数（不应该发生，但做兜底）
          mergedNotes = directive.prefix();
        }
        Optional<String> waypointNodeIdOpt =
            nodeIdPart == null ? Optional.empty() : Optional.of(nodeIdPart);
        if ("CRET".equals(directive.prefix())) {
          if (!stops.isEmpty()) {
            sender.sendMessage(
                locale.component(
                    "command.route.define.cret-not-first",
                    Map.of("seq", String.valueOf(stops.size()))));
            return Optional.empty();
          }
          RouteStop stop =
              new RouteStop(
                  routeId,
                  0,
                  Optional.empty(),
                  waypointNodeIdOpt,
                  Optional.empty(),
                  RouteStopPassType.PASS,
                  Optional.of(mergedNotes));
          stops.add(stop);
          continue;
        }
        if ("DSTY".equals(directive.prefix())) {
          RouteStop stop =
              new RouteStop(
                  routeId,
                  stops.size(),
                  Optional.empty(),
                  waypointNodeIdOpt,
                  Optional.empty(),
                  RouteStopPassType.PASS,
                  Optional.of(mergedNotes));
          stops.add(stop);
          dstySeen = true;
          continue;
        }
      }

      // 动作行：附着到上一条 stop 的 notes（用换行拼接，便于后续解释器读取）。
      if (isActionLine(trimmed)) {
        if (stops.isEmpty()) {
          sender.sendMessage(
              locale.component(
                  "command.route.define.action-first",
                  Map.of("line", String.valueOf(line.lineNo()), "text", trimmed)));
          return Optional.empty();
        }
        String normalizedAction = normalizeActionLine(trimmed);
        RouteStop last = stops.get(stops.size() - 1);
        String mergedNotes =
            last.notes()
                .map(
                    existing ->
                        existing.isBlank()
                            ? normalizedAction
                            : (existing + "\n" + normalizedAction))
                .orElse(normalizedAction);
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

      if (dstySeen) {
        sender.sendMessage(
            locale.component(
                "command.route.define.dsty-not-last",
                Map.of("seq", String.valueOf(stops.size() - 1))));
        return Optional.empty();
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

      // 兼容“stop 行尾部附带动作标记”的写法（例如：PTK DYNAMIC:SURN:PTK:[1:3]）。
      // 动作标记会被写入该 stop 的 notes，与“独立 action 行”保持一致。
      List<String> inlineActions = new ArrayList<>();
      String targetToken = content;
      String[] tokens = content.split("\\s+");
      if (tokens.length >= 2) {
        targetToken = tokens[0].trim();
        for (int i = 1; i < tokens.length; i++) {
          String token = tokens[i] == null ? "" : tokens[i].trim();
          if (token.isEmpty()) {
            continue;
          }
          if (isActionLine(token)) {
            inlineActions.add(normalizeActionLine(token));
            continue;
          }
          sender.sendMessage(
              locale.component(
                  "command.route.define.invalid-line",
                  Map.of("line", String.valueOf(line.lineNo()), "text", trimmed)));
          return Optional.empty();
        }
      }

      // 允许 STOP DYNAMIC:OP:STATION:[1:3] 作为"动态站台 stop"的简写形式：
      // - DYNAMIC 行本身携带站点/车库信息（code + 可选范围）
      // - 停靠表仍需落库为"可解析的图节点"，因此这里会写入一个"占位 nodeId"（默认取范围第一个 track）
      // - DYNAMIC 会写入 notes，运行时可据此选择真实站台并下发 destination
      // - 不带范围时表示"所有站台/轨道"，运行时从全部可用中选择
      boolean dynamicStopShorthand = false;
      Optional<DynamicNodeSpec> dynamicNodeSpecOpt = Optional.empty();
      if (isActionLine(targetToken)) {
        Optional<DirectiveLine> actionOpt = parseActionLine(targetToken);
        if (actionOpt.isPresent() && "DYNAMIC".equalsIgnoreCase(actionOpt.get().prefix())) {
          dynamicNodeSpecOpt = parseDynamicNodeSpec(normalizeActionLine(targetToken));
          dynamicStopShorthand = dynamicNodeSpecOpt.isPresent();
        }
        if (!dynamicStopShorthand) {
          sender.sendMessage(
              locale.component(
                  "command.route.define.action-first",
                  Map.of("line", String.valueOf(line.lineNo()), "text", trimmed)));
          return Optional.empty();
        }
      }

      Optional<UUID> stationId = Optional.empty();
      Optional<String> waypointNodeId = Optional.empty();
      if (dynamicStopShorthand) {
        // DYNAMIC 运行时动态选择站台，不写入固定 waypointNodeId
        // waypointNodeId 留空，运行时从 notes 解析 DYNAMIC 指令并选择站台
      } else if (targetToken.contains(":")) {
        // 形如 SURN:S:PTK:1 或 SURN:A:B:1:00 等：当作"图节点引用"原样写入。
        waypointNodeId = Optional.of(targetToken);
      } else {
        // 默认按站点 code 解析：用 UUID 外键保证引用一致性（站点重命名不会破坏停靠表）。
        Optional<Station> stationOpt =
            provider.stations().findByOperatorAndCode(operatorId, targetToken);
        if (stationOpt.isEmpty()) {
          sender.sendMessage(
              locale.component(
                  "command.route.define.station-not-found",
                  Map.of("line", String.valueOf(line.lineNo()), "station", targetToken)));
          return Optional.empty();
        }
        stationId = Optional.of(stationOpt.get().id());
      }

      List<String> noteLines = new ArrayList<>();
      if (dynamicStopShorthand) {
        noteLines.add(normalizeActionLine(targetToken));
      }
      noteLines.addAll(inlineActions);
      Optional<String> notes =
          noteLines.isEmpty() ? Optional.empty() : Optional.of(String.join("\n", noteLines));
      int sequence = stops.size();
      RouteStop stop =
          new RouteStop(
              routeId, sequence, stationId, waypointNodeId, dwellSeconds, passType, notes);
      stops.add(stop);
    }
    return Optional.of(stops);
  }

  private static boolean startsWithWord(String text, String word) {
    return text.regionMatches(true, 0, word, 0, word.length())
        && (text.length() == word.length()
            || Character.isWhitespace(text.charAt(word.length()))
            || text.charAt(word.length()) == ':');
  }

  private static String firstSegment(String line) {
    if (line == null || line.isEmpty()) {
      return "";
    }
    int idx = 0;
    while (idx < line.length()) {
      char ch = line.charAt(idx);
      if (Character.isWhitespace(ch) || ch == ':') {
        break;
      }
      idx++;
    }
    return line.substring(0, idx);
  }

  private static boolean isActionLine(String line) {
    if (line == null) {
      return false;
    }
    String trimmed = line.trim();
    if (trimmed.isEmpty()) {
      return false;
    }
    for (String prefix : ACTION_PREFIXES) {
      if (startsWithWord(trimmed, prefix)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isDirectiveLine(String line) {
    if (line == null) {
      return false;
    }
    String trimmed = line.trim();
    if (trimmed.isEmpty()) {
      return false;
    }
    for (String prefix : DIRECTIVE_PREFIXES) {
      if (startsWithWord(trimmed, prefix)) {
        return true;
      }
    }
    return false;
  }

  private static String normalizeActionLine(String line) {
    if (line == null) {
      return "";
    }
    String trimmed = line.trim();
    Optional<DirectiveLine> directiveOpt = parseDirectiveLine(trimmed);
    if (directiveOpt.isPresent()) {
      DirectiveLine directive = directiveOpt.get();
      if (directive.argument().isBlank()) {
        return directive.prefix();
      }
      return directive.prefix() + " " + directive.argument();
    }
    for (String prefix : ACTION_PREFIXES) {
      if (!startsWithWord(trimmed, prefix)) {
        continue;
      }
      String rest = trimmed.substring(prefix.length()).trim();
      if (rest.isEmpty()) {
        return prefix;
      }
      if (rest.startsWith(":")) {
        return prefix + rest;
      }
      return prefix + ":" + rest;
    }
    return trimmed;
  }

  private static Optional<DirectiveLine> parseActionLine(String line) {
    if (line == null) {
      return Optional.empty();
    }
    String trimmed = line.trim();
    if (trimmed.isEmpty()) {
      return Optional.empty();
    }
    for (String prefix : ACTION_PREFIXES) {
      if (!startsWithWord(trimmed, prefix)) {
        continue;
      }
      String rest = trimmed.substring(prefix.length()).trim();
      if (rest.startsWith(":")) {
        rest = rest.substring(1).trim();
      }
      return Optional.of(new DirectiveLine(prefix, rest));
    }
    return Optional.empty();
  }

  private static Optional<DirectiveLine> parseDirectiveLine(String line) {
    if (line == null) {
      return Optional.empty();
    }
    String trimmed = line.trim();
    if (trimmed.isEmpty()) {
      return Optional.empty();
    }
    for (String prefix : DIRECTIVE_PREFIXES) {
      if (!startsWithWord(trimmed, prefix)) {
        continue;
      }
      String rest = trimmed.substring(prefix.length()).trim();
      if (rest.startsWith(":")) {
        rest = rest.substring(1).trim();
      }
      return Optional.of(new DirectiveLine(prefix, rest));
    }
    return Optional.empty();
  }

  /**
   * 通用 DYNAMIC 节点规格：支持站点（S）和车库（D），带或不带范围。
   *
   * <p>格式：
   *
   * <ul>
   *   <li>DYNAMIC:OP:STATION → 站点所有站台（nodeType=S）
   *   <li>DYNAMIC:OP:S:STATION → 站点所有站台（显式 S）
   *   <li>DYNAMIC:OP:S:STATION:[1:3] → 站点 1~3 站台
   *   <li>DYNAMIC:OP:D:DEPOT → 车库所有轨道（nodeType=D）
   *   <li>DYNAMIC:OP:D:DEPOT:[1:2] → 车库 1~2 轨道
   * </ul>
   */
  private record DynamicNodeSpec(
      String operatorCode, String nodeType, String nodeName, int fromTrack, int toTrack) {
    /** 生成默认占位 NodeId（用于图可达性校验，取范围第一个轨道）。 */
    String resolveDefaultNodeId() {
      return operatorCode + ":" + nodeType + ":" + nodeName + ":" + fromTrack;
    }

    /** 生成指定轨道的 NodeId。 */
    String resolveNodeIdForTrack(int track) {
      return operatorCode + ":" + nodeType + ":" + nodeName + ":" + track;
    }

    /** 生成范围内所有轨道的 NodeId 列表。 */
    List<String> resolveAllNodeIds() {
      List<String> ids = new ArrayList<>();
      for (int t = fromTrack; t <= toTrack; t++) {
        ids.add(resolveNodeIdForTrack(t));
      }
      return ids;
    }
  }

  /**
   * 解析通用 DYNAMIC 节点规格。
   *
   * <p>支持格式：
   *
   * <ul>
   *   <li>DYNAMIC:OP:NAME → 默认 nodeType=S
   *   <li>DYNAMIC:OP:NAME:[range] → 默认 nodeType=S
   *   <li>DYNAMIC:OP:S:NAME 或 DYNAMIC:OP:D:NAME → 显式 nodeType
   *   <li>DYNAMIC:OP:S:NAME:[range] 或 DYNAMIC:OP:D:NAME:[range]
   * </ul>
   */
  private static Optional<DynamicNodeSpec> parseDynamicNodeSpec(String normalizedAction) {
    if (normalizedAction == null) {
      return Optional.empty();
    }
    String trimmed = normalizedAction.trim();
    if (!trimmed.regionMatches(true, 0, "DYNAMIC:", 0, "DYNAMIC:".length())) {
      return Optional.empty();
    }
    String rest = trimmed.substring("DYNAMIC:".length()).trim();

    // 先提取 [range] 部分（如果存在）
    String rangePayload = "";
    int bracketIdx = rest.indexOf('[');
    if (bracketIdx > 0) {
      rangePayload = rest.substring(bracketIdx).trim();
      rest = rest.substring(0, bracketIdx).trim();
      // 去掉可能的尾部 :
      if (rest.endsWith(":")) {
        rest = rest.substring(0, rest.length() - 1).trim();
      }
    }

    // 按 : 拆分剩余部分
    String[] parts = rest.split(":");
    if (parts.length < 2) {
      return Optional.empty();
    }
    String operatorCode = parts[0].trim();
    if (operatorCode.isBlank()) {
      return Optional.empty();
    }
    String nodeType;
    String nodeName;
    if (parts.length == 2) {
      // DYNAMIC:OP:NAME → 默认 nodeType=S
      nodeType = "S";
      nodeName = parts[1].trim();
    } else if (parts.length == 3) {
      // DYNAMIC:OP:X:Y → X 是 S/D 则为 nodeType，否则无效
      String second = parts[1].trim().toUpperCase(java.util.Locale.ROOT);
      if ("S".equals(second) || "D".equals(second)) {
        nodeType = second;
        nodeName = parts[2].trim();
      } else {
        return Optional.empty();
      }
    } else {
      return Optional.empty();
    }
    if (nodeName.isBlank()) {
      return Optional.empty();
    }

    // 解析轨道范围
    int fromTrack = 1;
    int toTrack = 1;
    if (!rangePayload.isBlank()) {
      Optional<int[]> rangeOpt = parseTrackRange(rangePayload);
      if (rangeOpt.isPresent()) {
        int[] range = rangeOpt.get();
        fromTrack = range[0];
        toTrack = range[1];
      }
    }
    return Optional.of(new DynamicNodeSpec(operatorCode, nodeType, nodeName, fromTrack, toTrack));
  }

  /**
   * 解析轨道范围。
   *
   * <p>支持格式：
   *
   * <ul>
   *   <li>[1:3] → from=1, to=3
   *   <li>[2] → from=2, to=2
   *   <li>1:3 → from=1, to=3
   *   <li>2 → from=2, to=2
   * </ul>
   *
   * @return int[2] = {fromTrack, toTrack}，或 empty 如果解析失败
   */
  private static Optional<int[]> parseTrackRange(String rangePayload) {
    if (rangePayload == null || rangePayload.isBlank()) {
      return Optional.empty();
    }
    String normalized = rangePayload.trim();
    if (normalized.startsWith("[") && normalized.endsWith("]")) {
      normalized = normalized.substring(1, normalized.length() - 1).trim();
    }
    if (normalized.isBlank()) {
      return Optional.empty();
    }
    int colon = normalized.indexOf(':');
    if (colon < 0) {
      // 单值
      OptionalInt single = parsePositiveInt(normalized);
      if (single.isEmpty()) {
        return Optional.empty();
      }
      int val = single.getAsInt();
      return Optional.of(new int[] {val, val});
    } else {
      OptionalInt from = parsePositiveInt(normalized.substring(0, colon));
      OptionalInt to = parsePositiveInt(normalized.substring(colon + 1));
      if (from.isEmpty() || to.isEmpty()) {
        return Optional.empty();
      }
      int f = from.getAsInt();
      int t = to.getAsInt();
      return Optional.of(new int[] {Math.min(f, t), Math.max(f, t)});
    }
  }

  private static OptionalInt parsePositiveInt(String raw) {
    if (raw == null) {
      return OptionalInt.empty();
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      return OptionalInt.empty();
    }
    try {
      int value = Integer.parseInt(trimmed);
      return value > 0 ? OptionalInt.of(value) : OptionalInt.empty();
    } catch (NumberFormatException ex) {
      return OptionalInt.empty();
    }
  }

  private RouteValidationResult validateRouteStops(
      StorageProvider provider, List<RouteStop> stops, boolean checkReachability) {
    // 结构校验 + 可选的图可达性检查（依赖已加载的图快照）。
    if (stops == null || stops.isEmpty()) {
      return new RouteValidationResult(List.of(), false);
    }
    List<RouteValidationIssue> issues = new ArrayList<>();

    int cretCount = 0;
    int dstyCount = 0;
    int cretSeq = -1;
    int dstySeq = -1;
    for (RouteStop stop : stops) {
      if (stop == null) {
        continue;
      }
      for (String line : extractActionLines(stop)) {
        String prefix = firstSegment(line).trim().toUpperCase(Locale.ROOT);
        if ("CRET".equals(prefix)) {
          cretCount++;
          cretSeq = stop.sequence();
        } else if ("DSTY".equals(prefix)) {
          dstyCount++;
          dstySeq = stop.sequence();
        }
      }
    }
    if (cretCount > 1) {
      issues.add(new RouteValidationIssue("command.route.define.cret-multi", Map.of()));
    }
    if (cretCount == 1 && cretSeq != 0) {
      issues.add(
          new RouteValidationIssue(
              "command.route.define.cret-not-first", Map.of("seq", String.valueOf(cretSeq))));
    }
    if (dstyCount > 1) {
      issues.add(new RouteValidationIssue("command.route.define.dsty-multi", Map.of()));
    }
    if (dstyCount == 1 && dstySeq != stops.size() - 1) {
      issues.add(
          new RouteValidationIssue(
              "command.route.define.dsty-not-last", Map.of("seq", String.valueOf(dstySeq))));
    }

    boolean missingNode = false;
    // 每个 stop 对应的可用 NodeId 列表（普通 stop 只有 1 个，DYNAMIC 可能有多个）
    List<List<NodeId>> nodeOptions = new ArrayList<>();
    // 每个 stop 的 DYNAMIC spec（用于后续可达性检查）
    List<DynamicNodeSpec> dynamicSpecs = new ArrayList<>();

    for (RouteStop stop : stops) {
      if (stop == null) {
        nodeOptions.add(List.of());
        dynamicSpecs.add(null);
        continue;
      }

      // 检查是否包含 DYNAMIC 动作
      Optional<DynamicNodeSpec> dynamicSpecOpt = extractDynamicSpec(stop);
      if (dynamicSpecOpt.isPresent()) {
        DynamicNodeSpec spec = dynamicSpecOpt.get();
        dynamicSpecs.add(spec);
        // 验证 DYNAMIC 范围内的所有轨道节点
        List<NodeId> validNodes = new ArrayList<>();
        List<String> missingTracks = new ArrayList<>();
        for (String nodeIdStr : spec.resolveAllNodeIds()) {
          NodeId nodeId = NodeId.of(nodeIdStr);
          // 检查节点是否存在于图中（遍历所有已加载快照）
          boolean found = false;
          if (plugin.getRailGraphService() != null) {
            for (World w : plugin.getServer().getWorlds()) {
              Optional<RailGraphService.RailGraphSnapshot> snapshotOpt =
                  plugin.getRailGraphService().getSnapshot(w);
              if (snapshotOpt.isPresent()
                  && snapshotOpt.get().graph().findNode(nodeId).isPresent()) {
                found = true;
                break;
              }
            }
          }
          if (found) {
            validNodes.add(nodeId);
          } else {
            missingTracks.add(nodeIdStr);
          }
        }
        if (validNodes.isEmpty()) {
          // 所有轨道都不存在
          missingNode = true;
          issues.add(
              new RouteValidationIssue(
                  "command.route.define.dynamic-no-valid-tracks",
                  Map.of(
                      "seq", String.valueOf(stop.sequence()),
                      "spec", spec.operatorCode() + ":" + spec.nodeType() + ":" + spec.nodeName(),
                      "range", "[" + spec.fromTrack() + ":" + spec.toTrack() + "]")));
        } else if (!missingTracks.isEmpty()) {
          // 部分轨道不存在（警告，不阻止定义）
          issues.add(
              new RouteValidationIssue(
                  "command.route.define.dynamic-partial-tracks",
                  Map.of(
                      "seq", String.valueOf(stop.sequence()),
                      "missing", String.join(", ", missingTracks),
                      "valid", String.valueOf(validNodes.size()))));
        }
        nodeOptions.add(validNodes);
      } else {
        dynamicSpecs.add(null);
        // 普通 stop：解析单个 NodeId
        Optional<NodeId> nodeIdOpt = RouteStopResolver.resolveNodeId(provider, stop);
        if (nodeIdOpt.isEmpty()) {
          missingNode = true;
          addMissingNodeIssue(provider, stop, issues);
          nodeOptions.add(List.of());
        } else {
          nodeOptions.add(List.of(nodeIdOpt.get()));
        }
      }
    }

    boolean reachabilitySkipped = false;
    if (checkReachability) {
      if (plugin.getRailGraphService() == null
          || plugin.getRailGraphService().snapshotCount() <= 0) {
        reachabilitySkipped = true;
      } else if (!missingNode) {
        // 检查相邻 stop 之间的可达性
        for (int i = 0; i < nodeOptions.size() - 1; i++) {
          List<NodeId> fromNodes = nodeOptions.get(i);
          List<NodeId> toNodes = nodeOptions.get(i + 1);
          if (fromNodes.isEmpty() || toNodes.isEmpty()) {
            reachabilitySkipped = true;
            continue;
          }
          // 检查是否存在任意一条可达路径
          boolean anyReachable = false;
          for (NodeId from : fromNodes) {
            for (NodeId to : toNodes) {
              if (plugin.getRailGraphService().findWorldIdForConnectedPair(from, to).isPresent()) {
                anyReachable = true;
                break;
              }
            }
            if (anyReachable) {
              break;
            }
          }
          if (!anyReachable) {
            // 构建错误信息
            String fromDesc =
                fromNodes.size() == 1
                    ? fromNodes.get(0).value()
                    : "DYNAMIC[" + fromNodes.size() + " tracks]";
            String toDesc =
                toNodes.size() == 1
                    ? toNodes.get(0).value()
                    : "DYNAMIC[" + toNodes.size() + " tracks]";
            issues.add(
                new RouteValidationIssue(
                    "command.route.define.edge-unreachable",
                    Map.of("from", fromDesc, "to", toDesc)));
          }
        }
      }
    }
    return new RouteValidationResult(List.copyOf(issues), reachabilitySkipped);
  }

  /**
   * 从 stop 的 notes 中提取 DYNAMIC 规范（如果存在）。
   *
   * <p>支持以下格式：
   *
   * <ul>
   *   <li>DYNAMIC:OP:S:STATION:[1:3]
   *   <li>CRET DYNAMIC:OP:D:DEPOT:[1:3]
   *   <li>DSTY DYNAMIC:OP:D:DEPOT:[1:3]
   * </ul>
   */
  private Optional<DynamicNodeSpec> extractDynamicSpec(RouteStop stop) {
    if (stop == null) {
      return Optional.empty();
    }
    for (String line : extractActionLines(stop)) {
      String upper = line.toUpperCase(Locale.ROOT);
      // 独立 DYNAMIC 行
      if (upper.startsWith("DYNAMIC:")) {
        return parseDynamicNodeSpec(normalizeActionLine(line));
      }
      // CRET/DSTY DYNAMIC:... 格式
      int dynamicIdx = upper.indexOf(" DYNAMIC:");
      if (dynamicIdx >= 0) {
        String dynamicPart = line.substring(dynamicIdx + 1).trim();
        return parseDynamicNodeSpec(normalizeActionLine(dynamicPart));
      }
    }
    return Optional.empty();
  }

  /**
   * 将“无法解析节点”的 stop 转换为具体的校验问题输出。
   *
   * <p>若 stop 绑定站点则报告站点缺失/站点未绑定图节点，否则报告 nodeId 缺失。
   */
  private void addMissingNodeIssue(
      StorageProvider provider, RouteStop stop, List<RouteValidationIssue> issues) {
    if (stop == null) {
      return;
    }
    if (stop.stationId().isPresent()) {
      Optional<Station> stationOpt = RouteStopResolver.resolveStation(provider, stop);
      if (stationOpt.isEmpty()) {
        issues.add(
            new RouteValidationIssue(
                "command.route.define.station-missing",
                Map.of("seq", String.valueOf(stop.sequence()))));
        return;
      }
      Station station = stationOpt.get();
      issues.add(
          new RouteValidationIssue(
              "command.route.define.station-graph-missing",
              Map.of("seq", String.valueOf(stop.sequence()), "station", station.code())));
      return;
    }
    issues.add(
        new RouteValidationIssue(
            "command.route.define.node-missing", Map.of("seq", String.valueOf(stop.sequence()))));
  }

  /** 从 RouteStop 的 notes 中提取 action 行（仅保留支持的前缀）。 */
  private List<String> extractActionLines(RouteStop stop) {
    if (stop == null || stop.notes().isEmpty()) {
      return List.of();
    }
    String raw = stop.notes().orElse("");
    if (raw.isBlank()) {
      return List.of();
    }
    String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
    List<String> lines = new ArrayList<>();
    for (String line : normalized.split("\n", -1)) {
      if (line == null) {
        continue;
      }
      String trimmed = line.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      if (isActionLine(trimmed) || isDirectiveLine(trimmed)) {
        lines.add(normalizeActionLine(trimmed));
      }
    }
    return lines;
  }

  private record RouteValidationIssue(String key, Map<String, String> params) {}

  private record RouteValidationResult(
      List<RouteValidationIssue> issues, boolean reachabilitySkipped) {}

  private record RouteValidationEntry(String routeCode, RouteValidationIssue issue) {}

  private record ResolvedLine(Company company, Operator operator, Line line) {}

  private record ResolvedRoute(Company company, Operator operator, Line line, Route route) {}

  /** 书本的“原始行”，用于在报错时定位行号。 */
  record BookLine(int lineNo, String text) {}

  private record DirectiveLine(String prefix, String argument) {}

  /**
   * 构建运行图编辑器的书页内容。
   *
   * <p>当 stops 非空时，会把现有停靠表渲染进书中，便于“二次编辑”；同时会把 notes 逐行展开为 action 行。
   *
   * <p>注意：书本每页可显示的行数有限，这里按固定行数切页，避免超出后玩家端显示异常。
   */
  private List<String> buildRouteEditorPages(
      StorageProvider provider, Operator operator, Line line, Route route, List<RouteStop> stops) {
    List<String> rendered = new ArrayList<>();
    rendered.add("# FetaruteTCAddon 运行图编辑器");
    rendered.add("# 线路: " + operator.code() + "/" + line.code());
    rendered.add("# 班次: " + route.code());
    rendered.add("#");
    rendered.add("# 规则：每行=stop/action（停靠/动作）；空行/注释忽略");
    rendered.add("# stop: 站点 code 或 NodeId");
    rendered.add("#   NodeId 例: " + operator.code() + ":S:PTK:1");
    rendered.add("#           或: " + operator.code() + ":A:B:1:00");
    rendered.add("# action: CHANGE/DYNAMIC/ACTION（动作标记）");
    rendered.add("# directive: CRET/DSTY <NodeId>（指令）");
    rendered.add("# 修饰: PASS/STOP/TERM, dwell=<秒>");
    rendered.add("# 限制: CRET 仅允许首个 stop；DSTY 必须为最后 stop");
    rendered.add("#");

    if (stops.isEmpty()) {
      rendered.add("# 示例：");
      rendered.add("# STOP " + operator.code() + ":D:DEPOT:1");
      rendered.add("# CRET " + operator.code() + ":D:DEPOT:1");
      rendered.add("# STOP PTK dwell=30");
      rendered.add("# CHANGE:" + operator.code() + ":" + line.code());
      rendered.add("# PASS " + operator.code() + ":A:B:1:00");
      rendered.add("# DSTY " + operator.code() + ":D:DEPOT:1:01");
      rendered.add("");
    } else {
      rendered.add("# 已加载现有停靠表（可直接修改后 define 覆盖）");
      rendered.add("");
      for (RouteStop stop : stops) {
        rendered.add(renderStopLine(provider, operator.id(), stop));
        Optional<String> directivePrefix = resolveDirectivePrefix(stop);
        stop.notes().stream()
            .flatMap(s -> Stream.of(s.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)))
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .filter(
                s -> {
                  if (directivePrefix.isPresent()) {
                    String prefix = directivePrefix.get();
                    // CRET/DSTY：检查 parseDirectiveLine 匹配
                    if (DIRECTIVE_PREFIXES.contains(prefix)) {
                      Optional<DirectiveLine> parsed = parseDirectiveLine(s);
                      if (parsed.isPresent() && parsed.get().prefix().equalsIgnoreCase(prefix)) {
                        return false;
                      }
                    }
                    // DYNAMIC/CHANGE/ACTION：检查行是否以该前缀开头
                    if (ACTION_PREFIXES.contains(prefix)) {
                      String upper = s.toUpperCase(Locale.ROOT);
                      if (upper.startsWith(prefix + ":") || upper.startsWith(prefix + " ")) {
                        return false;
                      }
                    }
                  }
                  return true;
                })
            .forEach(rendered::add);
      }
      rendered.add("");
    }

    rendered.add("# 在编辑完成后执行：");
    rendered.add("# /fta route define <company> <operator> <line> <route>");

    return splitPages(rendered, 11);
  }

  /**
   * 构建运行图编辑器的空模板内容。
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
    rendered.add("# FetaruteTCAddon 运行图编辑器");
    rendered.add("# 空模板（未绑定班次）");
    rendered.add("#");
    rendered.add("# 用法：每行一个 stop/action（停靠/动作）");
    rendered.add("# stop: 站点 code 或 NodeId");
    rendered.add("# action: CHANGE/DYNAMIC/ACTION（动作标记）");
    rendered.add("# directive: CRET/DSTY <NodeId>（指令）");
    rendered.add("# 修饰: PASS/STOP/TERM, dwell=<秒>");
    rendered.add("#");
    rendered.add("# 右键 waypoint/autostation/depot");
    rendered.add("# 牌子可把 nodeId 追加到末尾（用于快速取点）");
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
   * 将一个 RouteStop 渲染为 stop 行（用于编辑器回显）。
   *
   * <p>渲染规则：
   *
   * <ul>
   *   <li>优先从 notes 提取 directive 行（CRET/DSTY/DYNAMIC/CHANGE/ACTION）
   *   <li>若有 directive，前缀取 directive 类型，target 取 directive 剩余部分
   *   <li>若无 directive，前缀取 passType（PASS/STOP/TERM），target 取 waypointNodeId 或 stationCode
   * </ul>
   *
   * <p>输出示例：
   *
   * <pre>
   * CRET SURN:D:DEPOT:1
   * CRET DYNAMIC:SURN:D:DEPOT:[1:3]
   * DYNAMIC SURN:S:PPK:[1:3]
   * STOP SURN:S:PPK:1 dwell=30
   * </pre>
   */
  private String renderStopLine(StorageProvider provider, UUID operatorId, RouteStop stop) {
    // 从 notes 里提取 directive 行（CRET/DSTY 等）
    // 注意：DYNAMIC/CHANGE/ACTION 不是 directive prefix，而是 target 的一部分
    Optional<String> directiveLine = extractDirectiveLine(stop);
    Optional<String> directivePrefix = resolveDirectivePrefix(stop);

    // prefix 只能是 CRET/DSTY（覆盖 passType），其他情况使用 passType
    String prefix =
        directivePrefix
            .filter(DIRECTIVE_PREFIXES::contains) // 只有 CRET/DSTY 可作为 prefix
            .map(value -> value + " ")
            .orElseGet(
                () ->
                    switch (stop.passType()) {
                      case PASS -> "PASS ";
                      case TERMINATE -> "TERM ";
                      case STOP -> "STOP ";
                    });
    String dwell = stop.dwellSeconds().map(value -> " dwell=" + value).orElse("");

    // 确定 target
    String target;
    if (directiveLine.isPresent()) {
      String line = directiveLine.get();
      // 如果 prefix 是 CRET/DSTY，需要从 line 中去掉该前缀
      if (directivePrefix.isPresent() && DIRECTIVE_PREFIXES.contains(directivePrefix.get())) {
        String prefixPart = directivePrefix.get();
        if (line.toUpperCase(Locale.ROOT).startsWith(prefixPart.toUpperCase(Locale.ROOT))) {
          String remainder = line.substring(prefixPart.length());
          // 去掉分隔符（空格或冒号）
          if (!remainder.isEmpty() && (remainder.charAt(0) == ' ' || remainder.charAt(0) == ':')) {
            remainder = remainder.substring(1);
          }
          target = remainder.isEmpty() ? "<unknown>" : remainder;
        } else {
          target = line;
        }
      } else {
        // DYNAMIC/CHANGE/ACTION 等：整行作为 target
        target = line;
      }
    } else {
      target =
          stop.waypointNodeId()
              .filter(s -> !s.isBlank())
              .or(
                  () ->
                      stop.stationId()
                          .flatMap(provider.stations()::findById)
                          .map(Station::code)
                          .filter(s -> !s.isBlank()))
              .orElse("<unknown>");
    }
    return prefix + target + dwell;
  }

  /** 从 stop 的 notes 里提取第一条 directive 行（CRET/DSTY/DYNAMIC/CHANGE/ACTION）。 */
  /**
   * 从 stop 的 notes 里提取第一条 directive 行。
   *
   * <p>支持的 directive 前缀：
   *
   * <ul>
   *   <li>CRET、DSTY（出入库指令）
   *   <li>DYNAMIC、CHANGE、ACTION（动作指令）
   * </ul>
   *
   * <p>返回完整的 directive 行（如 "CRET SURN:D:DEPOT:1" 或 "DYNAMIC:SURN:S:PPK:[1:3]"）。
   */
  private Optional<String> extractDirectiveLine(RouteStop stop) {
    if (stop == null) {
      return Optional.empty();
    }
    for (String line : extractActionLines(stop)) {
      String prefix = firstSegment(line).trim().toUpperCase(Locale.ROOT);
      // 支持 CRET/DSTY 以及 DYNAMIC/CHANGE/ACTION
      if (DIRECTIVE_PREFIXES.contains(prefix) || ACTION_PREFIXES.contains(prefix)) {
        return Optional.of(line);
      }
    }
    return Optional.empty();
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
   * 从物品的 PDC（NBT）读取运行图编辑器上下文。
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
      List<RouteStop> stops,
      String navCommand) {
    if (stops.isEmpty()) {
      return;
    }
    StopDebugSession session = new StopDebugSession(operatorId, List.copyOf(stops));
    List<DebugStopEntry> entries = buildDebugEntries(locale, provider, session);
    sendStopDebugPage(locale, sender, provider, operatorId, entries, 1, navCommand);
  }

  private void sendStopDebugPage(
      LocaleManager locale,
      Player sender,
      StorageProvider provider,
      UUID operatorId,
      List<DebugStopEntry> entries,
      int page,
      String navCommand) {
    int total = entries.size();
    if (total <= 0) {
      return;
    }

    int pageCount = (total + STOP_DEBUG_PAGE_SIZE - 1) / STOP_DEBUG_PAGE_SIZE;
    int safePage = Math.max(1, Math.min(page, pageCount));
    int start = (safePage - 1) * STOP_DEBUG_PAGE_SIZE;
    int end = Math.min(start + STOP_DEBUG_PAGE_SIZE, total);

    sender.sendMessage(locale.component("command.route.define.debug.header"));
    sender.sendMessage(buildStopDebugNav(locale, safePage, pageCount, navCommand));

    for (int i = start; i < end; i++) {
      DebugStopEntry entry = entries.get(i);
      String index = entry.seq();
      String pass = entry.passLabel();
      String dwellBaseline = entry.dwellBaseline();
      String node = entry.node();
      String stationCode = entry.stationCode();

      String tpCommand =
          resolveTeleportCommand(provider, operatorId, stationCode)
              .orElseGet(() -> node.equals("-") ? "" : "/train debug destination " + node);

      // 只有 STOP/TERM 才显示 dwell
      String dwellSuffix = entry.isPass() ? "" : " <gray>dwell=" + dwellBaseline + "</gray>";

      sender.sendMessage(
          locale
              .component(
                  "command.route.define.debug.entry",
                  Map.of(
                      "seq", index,
                      "pass", pass,
                      "target", stationCode.equals("-") ? node : stationCode,
                      "dwell_suffix", dwellSuffix))
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
    sender.sendMessage(buildStopDebugNav(locale, safePage, pageCount, navCommand));
  }

  /**
   * debug 输出中的 passType 展示。
   *
   * <p>格式：{@code <passType> <directive>:<target>}，例如 "TERM DYNAMIC:SURC:S:PPK"。
   *
   * <p>CRET/DSTY/DYNAMIC 等是"动作指令"，会附加到 passType 后面显示。
   */
  private static String resolveStopPassLabel(LocaleManager locale, RouteStop stop) {
    if (stop == null) {
      return "-";
    }
    String passTypeLabel = resolvePassTypeShort(stop.passType());
    Optional<String> notes = stop.notes();
    if (notes.isEmpty() || notes.get() == null || notes.get().isBlank()) {
      return passTypeLabel;
    }
    String raw = notes.get().trim();
    String upper = raw.toUpperCase(java.util.Locale.ROOT);
    // 按优先级检测 directive 前缀并附加显示
    for (String directive : List.of("CRET", "DSTY", "DYNAMIC", "CHANGE", "ACTION")) {
      if (upper.startsWith(directive + " ")
          || upper.startsWith(directive + ":")
          || directive.equals(upper)) {
        // 提取 directive 后面的目标（如 "DYNAMIC:SURC:S:PPK" -> "SURC:S:PPK"）
        String rest = raw.substring(directive.length()).trim();
        if (rest.startsWith(":")) {
          rest = rest.substring(1).trim();
        }
        // 组合格式：TERM DYNAMIC:SURC:S:PPK
        if (rest.isEmpty()) {
          return passTypeLabel + " " + directive;
        }
        return passTypeLabel + " " + directive + ":" + rest;
      }
    }
    return passTypeLabel;
  }

  /** 返回 passType 的简短标签（STOP/PASS/TERM）。 */
  private static String resolvePassTypeShort(RouteStopPassType passType) {
    if (passType == null) {
      return "-";
    }
    return switch (passType) {
      case STOP -> "STOP";
      case PASS -> "PASS";
      case TERMINATE -> "TERM";
    };
  }

  /**
   * 从 stop 的 notes 里提取 directive 前缀。
   *
   * <p>返回的前缀可能是：
   *
   * <ul>
   *   <li>CRET/DSTY：出入库指令（会覆盖 passType 显示）
   *   <li>DYNAMIC/CHANGE/ACTION：动作指令（作为 target 的一部分，不覆盖 passType）
   * </ul>
   *
   * <p>调用方需根据业务逻辑判断是否用作 prefix 替换 passType。
   */
  private Optional<String> resolveDirectivePrefix(RouteStop stop) {
    if (stop == null) {
      return Optional.empty();
    }
    for (String line : extractActionLines(stop)) {
      String prefix = firstSegment(line).trim().toUpperCase(Locale.ROOT);
      // 支持 CRET/DSTY 以及 DYNAMIC/CHANGE/ACTION
      if (DIRECTIVE_PREFIXES.contains(prefix) || ACTION_PREFIXES.contains(prefix)) {
        return Optional.of(prefix);
      }
    }
    return Optional.empty();
  }

  private Component buildStopDebugNav(
      LocaleManager locale, int page, int pageCount, String navCommand) {
    Component pageText =
        locale.component(
            "command.route.define.debug.page",
            Map.of("page", String.valueOf(page), "pages", String.valueOf(pageCount)));

    Component prev =
        page > 1
            ? locale
                .component("command.route.define.debug.nav.prev")
                .clickEvent(ClickEvent.runCommand(navCommand + (page - 1)))
            : locale.component("command.route.define.debug.nav.prev-disabled");
    Component next =
        page < pageCount
            ? locale
                .component("command.route.define.debug.nav.next")
                .clickEvent(ClickEvent.runCommand(navCommand + (page + 1)))
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

  private List<DebugStopEntry> buildDebugEntries(
      LocaleManager locale, StorageProvider provider, StopDebugSession session) {
    List<RouteStop> stops = session.stops();
    if (stops.isEmpty()) {
      return List.of();
    }
    List<DebugStopEntry> entries = new ArrayList<>();
    RailGraphPathFinder pathFinder = new RailGraphPathFinder();
    for (int i = 0; i < stops.size(); i++) {
      RouteStop stop = stops.get(i);
      entries.add(toDebugEntry(locale, provider, stop, String.valueOf(stop.sequence()), false));
      if (i >= stops.size() - 1) {
        continue;
      }
      Optional<NodeId> fromOpt = RouteStopResolver.resolveNodeId(provider, stop);
      Optional<NodeId> toOpt = RouteStopResolver.resolveNodeId(provider, stops.get(i + 1));
      if (fromOpt.isEmpty() || toOpt.isEmpty()) {
        continue;
      }
      Optional<List<NodeId>> pathOpt =
          findShortestPathNodes(pathFinder, fromOpt.get(), toOpt.get());
      if (pathOpt.isEmpty()) {
        continue;
      }
      List<NodeId> nodes = pathOpt.get();
      if (nodes.size() <= 2) {
        continue;
      }
      for (int idx = 1; idx < nodes.size() - 1; idx++) {
        String seq = stop.sequence() + "." + idx;
        entries.add(toPathDebugEntry(nodes.get(idx), seq));
      }
    }
    return entries;
  }

  private DebugStopEntry toDebugEntry(
      LocaleManager locale,
      StorageProvider provider,
      RouteStop stop,
      String seq,
      boolean expanded) {
    String passLabel = expanded ? "PASS (path)" : resolveStopPassLabel(locale, stop);
    String dwellBaseline = stop.dwellSeconds().map(String::valueOf).orElse("-");
    boolean isPass = expanded || stop.passType() == RouteStopPassType.PASS;

    // 对于 DYNAMIC stop，从 notes 中提取显示信息
    Optional<DynamicNodeSpec> dynamicSpec = extractDynamicSpec(stop);
    String node;
    String stationCode;
    if (dynamicSpec.isPresent()) {
      DynamicNodeSpec spec = dynamicSpec.get();
      // 显示 DYNAMIC 规范而不是 "-"
      String typeCode = spec.nodeType(); // "S" or "D"
      String range =
          spec.fromTrack() == spec.toTrack()
              ? String.valueOf(spec.fromTrack())
              : "[" + spec.fromTrack() + ":" + spec.toTrack() + "]";
      node =
          "DYNAMIC:" + spec.operatorCode() + ":" + typeCode + ":" + spec.nodeName() + ":" + range;
      stationCode = spec.nodeName();
    } else {
      node = stop.waypointNodeId().orElse("-");
      stationCode =
          stop.stationId()
              .flatMap(provider.stations()::findById)
              .map(Station::code)
              .orElseGet(
                  () ->
                      stop.waypointNodeId()
                          .flatMap(id -> tryExtractStationCodeFromNodeId(id, true))
                          .orElse("-"));
    }
    return new DebugStopEntry(seq, passLabel, dwellBaseline, node, stationCode, expanded, isPass);
  }

  private DebugStopEntry toPathDebugEntry(NodeId node, String seq) {
    String nodeValue = node == null ? "-" : node.value();
    String stationCode =
        node == null ? "-" : tryExtractStationCodeFromNodeId(node.value(), true).orElse("-");
    return new DebugStopEntry(seq, "PASS (path)", "-", nodeValue, stationCode, true, true);
  }

  private Optional<List<NodeId>> findShortestPathNodes(
      RailGraphPathFinder pathFinder, NodeId from, NodeId to) {
    if (plugin.getRailGraphService() == null || pathFinder == null) {
      return Optional.empty();
    }
    Optional<UUID> worldOpt = plugin.getRailGraphService().findWorldIdForConnectedPair(from, to);
    if (worldOpt.isEmpty()) {
      return Optional.empty();
    }
    Optional<org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService.RailGraphSnapshot>
        snapshotOpt = plugin.getRailGraphService().getSnapshot(worldOpt.get());
    if (snapshotOpt.isEmpty() || snapshotOpt.get().graph() == null) {
      return Optional.empty();
    }
    org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph graph = snapshotOpt.get().graph();
    return pathFinder
        .shortestPath(graph, from, to, RailGraphPathFinder.Options.shortestDistance())
        .map(path -> path.nodes());
  }

  /**
   * define/debug 回显的“上下文态”。
   *
   * <p>仅用于生成调试回显，不写入存储。
   */
  private record StopDebugSession(UUID operatorId, List<RouteStop> stops) {
    StopDebugSession {
      Objects.requireNonNull(operatorId, "operatorId");
      stops = stops == null ? List.of() : List.copyOf(stops);
    }
  }

  private record DebugStopEntry(
      String seq,
      String passLabel,
      String dwellBaseline,
      String node,
      String stationCode,
      boolean expanded,
      boolean isPass) {}

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

  /** 解析 {@code true/false/yes/no/1/0}，用于命令行布尔参数。 */
  private static Optional<Boolean> parseBooleanToken(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String t = raw.trim().toLowerCase(java.util.Locale.ROOT);
    if (t.isEmpty()) {
      return Optional.empty();
    }
    return switch (t) {
      case "true", "yes", "y", "1" -> Optional.of(true);
      case "false", "no", "n", "0" -> Optional.of(false);
      default -> Optional.empty();
    };
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

  /**
   * 运行图编辑器的书本上下文。
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

  /**
   * 显示 Route 在图中扩展后的实际路径。
   *
   * <p>当 waypoints 中相邻节点没有直连边时，会用最短路填充中间节点。此方法展示填充后的完整路径， 帮助用户诊断"绕远路"问题。
   */
  private void sendExpandedPath(LocaleManager locale, Player sender, RouteDefinition definition) {
    List<NodeId> waypoints = definition.waypoints();
    if (waypoints.isEmpty()) {
      sender.sendMessage(
          locale.component(
              "command.route.path.empty-waypoints", Map.of("route", definition.id().value())));
      return;
    }

    // 查找可用的图快照（按路径第一个节点所在世界）
    var graphService = plugin.getRailGraphService();
    if (graphService == null) {
      sender.sendMessage(locale.component("command.route.path.no-graph"));
      return;
    }
    Optional<RailGraphService.RailGraphSnapshot> snapshotOpt =
        graphService.findWorldIdForPath(waypoints).flatMap(graphService::getSnapshot);
    if (snapshotOpt.isEmpty()) {
      sender.sendMessage(
          locale.component(
              "command.route.path.no-graph-for-route", Map.of("route", definition.id().value())));
      return;
    }
    RailGraph graph = snapshotOpt.get().graph();

    sender.sendMessage(
        locale.component("command.route.path.header", Map.of("route", definition.id().value())));

    // 逐段扩展并显示
    RailGraphPathFinder pathFinder = new RailGraphPathFinder();
    List<NodeId> expandedPath = new ArrayList<>();
    expandedPath.add(waypoints.get(0));

    boolean hasDetour = false;
    int totalBlocks = 0;

    for (int i = 0; i < waypoints.size() - 1; i++) {
      NodeId from = waypoints.get(i);
      NodeId to = waypoints.get(i + 1);

      // 检查是否有直连边
      Optional<RailEdge> directEdge = findEdge(graph, from, to);
      if (directEdge.isPresent()) {
        // 直连：显示正常
        int len = directEdge.get().lengthBlocks();
        totalBlocks += len;
        sender.sendMessage(
            locale.component(
                "command.route.path.direct",
                Map.of("from", from.value(), "to", to.value(), "len", String.valueOf(len))));
        expandedPath.add(to);
      } else {
        // 需要最短路填充
        Optional<RailGraphPath> pathOpt =
            pathFinder.shortestPath(
                graph, from, to, RailGraphPathFinder.Options.shortestDistance());
        if (pathOpt.isEmpty()) {
          sender.sendMessage(
              locale.component(
                  "command.route.path.unreachable",
                  Map.of("from", from.value(), "to", to.value())));
          continue;
        }

        RailGraphPath detourPath = pathOpt.get();
        List<NodeId> detourNodes = detourPath.nodes();
        int detourLen = (int) detourPath.totalLengthBlocks();
        totalBlocks += detourLen;

        // 标记为绕行
        hasDetour = true;
        sender.sendMessage(
            locale.component(
                "command.route.path.detour-header",
                Map.of(
                    "from",
                    from.value(),
                    "to",
                    to.value(),
                    "len",
                    String.valueOf(detourLen),
                    "hops",
                    String.valueOf(detourNodes.size() - 1))));

        // 显示绕行路径中的每一步
        for (int j = 1; j < detourNodes.size(); j++) {
          NodeId step = detourNodes.get(j);
          expandedPath.add(step);
          sender.sendMessage(
              locale.component("command.route.path.detour-step", Map.of("node", step.value())));
        }
      }
    }

    // 总结
    sender.sendMessage(
        locale.component(
            "command.route.path.summary",
            Map.of(
                "total_blocks",
                String.valueOf(totalBlocks),
                "original_count",
                String.valueOf(waypoints.size()),
                "expanded_count",
                String.valueOf(expandedPath.size()),
                "has_detour",
                hasDetour ? locale.text("common.yes") : locale.text("common.no"))));
  }

  private Optional<RailEdge> findEdge(RailGraph graph, NodeId from, NodeId to) {
    if (graph == null || from == null || to == null) {
      return Optional.empty();
    }
    return graph.edgesFrom(from).stream()
        .filter(
            e ->
                e.from().equals(from) && e.to().equals(to)
                    || e.from().equals(to) && e.to().equals(from))
        .findFirst();
  }
}
