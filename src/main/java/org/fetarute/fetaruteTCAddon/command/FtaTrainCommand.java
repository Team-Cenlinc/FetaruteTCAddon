package org.fetarute.fetaruteTCAddon.command;

import com.bergerkiller.bukkit.tc.TrainCarts;
import com.bergerkiller.bukkit.tc.commands.selector.SelectorCondition;
import com.bergerkiller.bukkit.tc.commands.selector.SelectorException;
import com.bergerkiller.bukkit.tc.commands.selector.TCSelectorHandlerRegistry;
import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartGroupStore;
import com.bergerkiller.bukkit.tc.properties.CartProperties;
import com.bergerkiller.bukkit.tc.properties.CartPropertiesStore;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.properties.TrainPropertiesStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.api.CompanyQueryService;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainTagHelper;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfig;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainType;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.component.CommandComponent;
import org.incendo.cloud.parser.flag.CommandFlag;
import org.incendo.cloud.parser.standard.DoubleParser;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.SuggestionProvider;

/** /fta train 命令注册（列车速度/加减速配置）。 */
public final class FtaTrainCommand {

  private static final int SUGGESTION_LIMIT = 20;
  private static final String TRAIN_SELECTOR_PREFIX = "@train[";
  private static final List<String> TRAIN_SELECTOR_KEYS =
      List.of(
          "x=",
          "y=",
          "z=",
          "dx=",
          "dy=",
          "dz=",
          "distance=",
          "world=",
          "sort=",
          "limit=",
          "name=",
          "tag=",
          "passengers=",
          "playerpassengers=",
          "destination=",
          "ticket=",
          "speed=",
          "velocity=",
          "speedlimit=",
          "friction=",
          "gravity=",
          "keepchunksloaded=",
          "unloaded=",
          "derailed=");
  private static final List<String> TRAIN_SELECTOR_SORT_VALUES =
      List.of("nearest", "furthest", "random");
  private static final List<String> TRAIN_SELECTOR_BOOLEAN_VALUES = List.of("true", "false");
  private static final List<String> TRAIN_SELECTOR_LIMIT_VALUES =
      List.of("1", "2", "5", "10", "20");
  private static final List<String> TRAIN_SELECTOR_NUMBER_VALUES =
      List.of("0", "1", "2", "5", "10", "20", "50", "100");

  private final FetaruteTCAddon plugin;
  private final TrainConfigResolver resolver = new TrainConfigResolver();

  public FtaTrainCommand(FetaruteTCAddon plugin) {
    this.plugin = plugin;
  }

  /** 注册 {@code /fta train config} 子命令。 */
  public void register(CommandManager<CommandSender> manager) {
    SuggestionProvider<CommandSender> trainSuggestions = trainSuggestions();
    SuggestionProvider<CommandSender> companySuggestions = companySuggestions();
    SuggestionProvider<CommandSender> operatorSuggestions = operatorSuggestions();
    SuggestionProvider<CommandSender> lineSuggestions = lineSuggestions();
    SuggestionProvider<CommandSender> routeSuggestions = routeSuggestions();
    SuggestionProvider<CommandSender> indexOrNodeSuggestions = indexOrNodeSuggestions();

    var typeFlag =
        CommandFlag.builder("type")
            .withComponent(
                CommandComponent.builder("type", StringParser.stringParser())
                    .suggestionProvider(
                        CommandSuggestionProviders.enumValues(TrainType.class, "<type>"))
                    .build())
            .build();
    var accelFlag =
        CommandFlag.builder("accel")
            .withComponent(
                CommandComponent.builder("accel", DoubleParser.doubleParser())
                    .suggestionProvider(CommandSuggestionProviders.placeholder("<bps2>"))
                    .build())
            .build();
    var decelFlag =
        CommandFlag.builder("decel")
            .withComponent(
                CommandComponent.builder("decel", DoubleParser.doubleParser())
                    .suggestionProvider(CommandSuggestionProviders.placeholder("<bps2>"))
                    .build())
            .build();

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("train")
            .permission("fetarute.train.config")
            .handler(ctx -> sendHelp(ctx.sender())));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("train")
            .literal("config")
            .permission("fetarute.train.config")
            .handler(ctx -> sendHelp(ctx.sender())));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("train")
            .literal("config")
            .literal("set")
            .permission("fetarute.train.config")
            .optional("train", StringParser.stringParser(), trainSuggestions)
            .flag(typeFlag)
            .flag(accelFlag)
            .flag(decelFlag)
            .handler(
                ctx -> {
                  LocaleManager locale = plugin.getLocaleManager();
                  Optional<String> trainArg = ctx.optional("train").map(String.class::cast);
                  List<TrainProperties> targets =
                      resolveTrainTargets(ctx.sender(), trainArg, locale);
                  if (targets.isEmpty()) {
                    return;
                  }
                  String typeRaw = ctx.flags().getValue(typeFlag, null);
                  Optional<TrainType> typeOverride =
                      Optional.ofNullable(typeRaw).flatMap(TrainType::parse);
                  Double accel = ctx.flags().getValue(accelFlag, null);
                  Double decel = ctx.flags().getValue(decelFlag, null);
                  for (TrainProperties properties : targets) {
                    TrainConfig current =
                        resolver.resolve(properties, plugin.getConfigManager().current());
                    TrainType type = typeOverride.orElse(current.type());
                    TrainConfig target =
                        new TrainConfig(
                            type,
                            accel != null ? accel : current.accelBps2(),
                            decel != null ? decel : current.decelBps2());
                    resolver.writeConfig(
                        properties, target, Optional.ofNullable(accel), Optional.ofNullable(decel));
                    ctx.sender()
                        .sendMessage(
                            locale.component(
                                "command.train.config.set",
                                Map.of(
                                    "train",
                                    properties.getTrainName(),
                                    "type",
                                    target.type().name(),
                                    "accel",
                                    String.valueOf(target.accelBps2()),
                                    "decel",
                                    String.valueOf(target.decelBps2()))));
                  }
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("train")
            .literal("config")
            .literal("list")
            .permission("fetarute.train.config")
            .optional("train", StringParser.stringParser(), trainSuggestions)
            .handler(
                ctx -> {
                  LocaleManager locale = plugin.getLocaleManager();
                  Optional<String> trainArg = ctx.optional("train").map(String.class::cast);
                  List<TrainProperties> targets =
                      resolveTrainTargets(ctx.sender(), trainArg, locale);
                  if (targets.isEmpty()) {
                    return;
                  }
                  for (TrainProperties properties : targets) {
                    String trainName = properties.getTrainName();
                    TrainConfig config =
                        resolver.resolve(properties, plugin.getConfigManager().current());
                    ctx.sender()
                        .sendMessage(
                            locale.component(
                                "command.train.config.list",
                                Map.of(
                                    "train",
                                    trainName,
                                    "type",
                                    config.type().name(),
                                    "accel",
                                    String.valueOf(config.accelBps2()),
                                    "decel",
                                    String.valueOf(config.decelBps2()))));
                  }
                }));

    // debug 子命令：显示控车诊断数据
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("train")
            .literal("debug")
            .permission("fetarute.train.debug")
            .optional("train", StringParser.stringParser(), trainSuggestions)
            .handler(
                ctx -> handleDebug(ctx.sender(), ctx.optional("train").map(String.class::cast))));

    // debug list 子命令：显示所有缓存的诊断数据
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("train")
            .literal("debug")
            .literal("list")
            .permission("fetarute.train.debug")
            .handler(ctx -> handleDebugList(ctx.sender())));

    // debug set route 子命令：手动写入 route tags（用于调试）
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("train")
            .literal("debug")
            .literal("set")
            .literal("route")
            .permission("fetarute.train.debug")
            .required("company", StringParser.stringParser(), companySuggestions)
            .required("operator", StringParser.stringParser(), operatorSuggestions)
            .required("line", StringParser.stringParser(), lineSuggestions)
            .required("route", StringParser.stringParser(), routeSuggestions)
            .optional("index_or_node", StringParser.quotedStringParser(), indexOrNodeSuggestions)
            .handler(
                ctx ->
                    handleDebugSetRoute(
                        ctx.sender(),
                        Optional.empty(),
                        ((String) ctx.get("company")).trim(),
                        ((String) ctx.get("operator")).trim(),
                        ((String) ctx.get("line")).trim(),
                        ((String) ctx.get("route")).trim(),
                        ctx.optional("index_or_node").map(String.class::cast))));

    // debug set route（指定列车）：使用 "train <train|@train[...]>" 避免与 company 参数歧义
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("train")
            .literal("debug")
            .literal("set")
            .literal("route")
            .permission("fetarute.train.debug")
            .required("company", StringParser.stringParser(), companySuggestions)
            .required("operator", StringParser.stringParser(), operatorSuggestions)
            .required("line", StringParser.stringParser(), lineSuggestions)
            .required("route", StringParser.stringParser(), routeSuggestions)
            .literal("train")
            .required("train", StringParser.stringParser(), trainSuggestions)
            .optional("index_or_node", StringParser.quotedStringParser(), indexOrNodeSuggestions)
            .handler(
                ctx ->
                    handleDebugSetRoute(
                        ctx.sender(),
                        Optional.ofNullable(((String) ctx.get("train"))).map(String::trim),
                        ((String) ctx.get("company")).trim(),
                        ((String) ctx.get("operator")).trim(),
                        ((String) ctx.get("line")).trim(),
                        ((String) ctx.get("route")).trim(),
                        ctx.optional("index_or_node").map(String.class::cast))));
  }

  private void handleDebug(CommandSender sender, Optional<String> trainArg) {
    LocaleManager locale = plugin.getLocaleManager();
    var dispatchServiceOpt = plugin.getRuntimeDispatchService();
    if (dispatchServiceOpt.isEmpty()) {
      sender.sendMessage(locale.component("command.train.debug.unavailable"));
      return;
    }
    var dispatchService = dispatchServiceOpt.get();
    List<TrainProperties> targets = resolveTrainTargets(sender, trainArg, locale);
    if (targets.isEmpty()) {
      return;
    }
    for (TrainProperties properties : targets) {
      String trainName = properties.getTrainName();
      var diagnosticsOpt = dispatchService.getDiagnostics(trainName);
      if (diagnosticsOpt.isEmpty()) {
        sender.sendMessage(
            locale.component("command.train.debug.no-data", Map.of("train", trainName)));
        continue;
      }
      var diag = diagnosticsOpt.get();
      sendDiagnosticsOutput(sender, diag, locale);
    }
  }

  /**
   * 手动写入 route tags（company/operator/line/route + index），用于在线调试与复现问题。
   *
   * <p>命令会：
   *
   * <ul>
   *   <li>从存储解析 company/operator/line/route，确保 company 维度参与定位（避免同名 code 冲突）。
   *   <li>写入 {@code FTA_ROUTE_ID/FTA_OPERATOR_CODE/FTA_LINE_CODE/FTA_ROUTE_CODE/FTA_ROUTE_INDEX} 等
   *       tags。
   *   <li>同步下一跳 destination，并尝试对在线列车执行 {@code refreshSignal} 立即生效。
   * </ul>
   *
   * <p>注意：index 语义为“已抵达节点索引”，即列车已到达 {@code waypoints[index]}，下一跳为 {@code waypoints[index+1]}。
   */
  private void handleDebugSetRoute(
      CommandSender sender,
      Optional<String> trainArg,
      String companyCode,
      String operatorCode,
      String lineCode,
      String routeCode,
      Optional<String> indexOrNode) {
    LocaleManager locale = plugin.getLocaleManager();

    Optional<StorageProvider> providerOpt =
        plugin.getStorageManager() != null
            ? plugin.getStorageManager().provider()
            : Optional.empty();
    if (providerOpt.isEmpty()
        || plugin.getStorageManager() == null
        || !plugin.getStorageManager().isReady()) {
      sender.sendMessage(locale.component("command.train.debug.set.route.storage-not-ready"));
      return;
    }

    StorageProvider provider = providerOpt.get();
    CompanyQueryService query = new CompanyQueryService(provider);
    Optional<Company> companyOpt = query.findCompany(companyCode);
    if (companyOpt.isEmpty()) {
      sender.sendMessage(
          locale.component(
              "command.train.debug.set.route.company-not-found", Map.of("company", companyCode)));
      return;
    }
    Company company = companyOpt.get();
    if (!CompanyAccessChecker.canReadCompany(sender, provider, company.id())) {
      sender.sendMessage(locale.component("error.no-permission"));
      return;
    }
    Optional<Operator> operatorOpt = query.findOperator(company.id(), operatorCode);
    if (operatorOpt.isEmpty()) {
      sender.sendMessage(
          locale.component(
              "command.train.debug.set.route.operator-not-found",
              Map.of("company", company.code(), "operator", operatorCode)));
      return;
    }
    Operator operator = operatorOpt.get();
    Optional<Line> lineOpt = query.findLine(operator.id(), lineCode);
    if (lineOpt.isEmpty()) {
      sender.sendMessage(
          locale.component(
              "command.train.debug.set.route.line-not-found",
              Map.of("operator", operator.code(), "line", lineCode)));
      return;
    }
    Line line = lineOpt.get();
    Optional<Route> routeEntityOpt = query.findRoute(line.id(), routeCode);
    if (routeEntityOpt.isEmpty()) {
      sender.sendMessage(
          locale.component(
              "command.train.debug.set.route.route-not-found",
              Map.of("line", line.code(), "route", routeCode)));
      return;
    }
    Route routeEntity = routeEntityOpt.get();
    Optional<RouteDefinition> definitionOpt = plugin.findRouteDefinitionById(routeEntity.id());
    if (definitionOpt.isEmpty()) {
      // 自愈：如果缓存缺失或过期，尝试从 DB 增量刷新该条线路定义。
      definitionOpt = plugin.refreshRouteDefinition(provider, operator, line, routeEntity);
    }
    if (definitionOpt.isEmpty()) {
      sender.sendMessage(
          locale.component(
              "command.train.debug.set.route.definition-missing",
              Map.of(
                  "company",
                  company.code(),
                  "operator",
                  operator.code(),
                  "line",
                  line.code(),
                  "route",
                  routeEntity.code())));
      return;
    }

    RouteDefinition route = definitionOpt.get();
    Optional<Integer> indexOpt = resolveIndex(indexOrNode, route);
    if (indexOpt.isEmpty()) {
      sender.sendMessage(
          locale.component(
              "command.train.debug.set.route.invalid-index",
              Map.of(
                  "raw",
                  indexOrNode.orElse(""),
                  "route",
                  route.id() != null ? route.id().value() : "-")));
      return;
    }
    int index = indexOpt.get();
    if (index < 0 || index >= route.waypoints().size()) {
      sender.sendMessage(
          locale.component(
              "command.train.debug.set.route.index-out-of-range",
              Map.of(
                  "index",
                  String.valueOf(index),
                  "max",
                  String.valueOf(Math.max(0, route.waypoints().size() - 1)),
                  "route",
                  route.id() != null ? route.id().value() : "-")));
      return;
    }

    List<TrainProperties> targets = resolveTrainTargets(sender, trainArg, locale);
    if (targets.isEmpty()) {
      return;
    }

    boolean refreshedAny = false;
    for (TrainProperties properties : targets) {
      String trainName = properties.getTrainName();

      // 清理运行时缓存/占用，避免“换线后仍按旧 route 驱动”。
      plugin
          .getRuntimeDispatchService()
          .ifPresent(service -> service.handleTrainRemoved(trainName));

      // 写入 route tags：同时写 code 三元组与 route UUID，便于后续定位与兼容回退查找。
      TrainTagHelper.writeTag(
          properties, RouteProgressRegistry.TAG_ROUTE_ID, String.valueOf(routeEntity.id()));
      TrainTagHelper.writeTag(properties, RouteProgressRegistry.TAG_OPERATOR_CODE, operator.code());
      TrainTagHelper.writeTag(properties, RouteProgressRegistry.TAG_LINE_CODE, line.code());
      TrainTagHelper.writeTag(properties, RouteProgressRegistry.TAG_ROUTE_CODE, routeEntity.code());
      TrainTagHelper.writeTag(
          properties, RouteProgressRegistry.TAG_ROUTE_INDEX, String.valueOf(index));
      TrainTagHelper.writeTag(
          properties,
          RouteProgressRegistry.TAG_ROUTE_UPDATED_AT,
          String.valueOf(Instant.now().toEpochMilli()));

      // 同步下一跳 destination，便于立刻观察调度控车（不依赖推进点再次触发）。
      String destination = resolveNextDestination(route, index);
      properties.clearDestinationRoute();
      properties.setDestination(destination);

      sender.sendMessage(
          locale.component(
              "command.train.debug.set.route.success",
              Map.of(
                  "train",
                  trainName != null ? trainName : "-",
                  "company",
                  company.code(),
                  "operator",
                  operator.code(),
                  "line",
                  line.code(),
                  "route",
                  routeEntity.code(),
                  "index",
                  String.valueOf(index),
                  "dest",
                  destination.isBlank() ? "-" : destination)));

      MinecartGroup group = resolveGroupByTrainName(trainName);
      if (group != null) {
        plugin.getRuntimeDispatchService().ifPresent(service -> service.refreshSignal(group));
        refreshedAny = true;
      }
    }

    if (!refreshedAny) {
      sender.sendMessage(locale.component("command.train.debug.set.route.refresh-skipped"));
    }
  }

  /**
   * 解析用户输入的 index 参数。
   *
   * <p>支持两种形式：
   *
   * <ul>
   *   <li>数字：直接作为“已抵达节点索引”（写入 {@code FTA_ROUTE_INDEX}）。
   *   <li>nodeId：在 {@link RouteDefinition#waypoints()} 中查找并返回其索引。
   * </ul>
   *
   * <p>未提供参数时回退为 0（从线路起点开始）。
   */
  private Optional<Integer> resolveIndex(Optional<String> indexOrNode, RouteDefinition route) {
    if (route == null) {
      return Optional.empty();
    }
    if (indexOrNode == null || indexOrNode.isEmpty()) {
      return Optional.of(0);
    }
    String raw = indexOrNode.get();
    if (raw == null) {
      return Optional.of(0);
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      return Optional.of(0);
    }
    try {
      return Optional.of(Integer.parseInt(trimmed));
    } catch (NumberFormatException ex) {
      // 非数字：视为 nodeId，在 route.waypoints() 中查找其索引
      NodeId nodeId = NodeId.of(trimmed);
      int idx = route.waypoints().indexOf(nodeId);
      return idx >= 0 ? Optional.of(idx) : Optional.empty();
    }
  }

  /**
   * 计算并返回“下一跳 destination”字符串。
   *
   * <p>用于 debug set route：写入 tags 后同步 destination，便于立刻观察 TrainCarts 寻路与调度控车行为。
   */
  private String resolveNextDestination(RouteDefinition route, int currentIndex) {
    if (route == null || route.waypoints().isEmpty()) {
      return "";
    }
    int nextIndex = currentIndex + 1;
    if (nextIndex < 0 || nextIndex >= route.waypoints().size()) {
      return "";
    }
    NodeId nextNode = route.waypoints().get(nextIndex);
    if (nextNode == null) {
      return "";
    }
    return plugin
        .getSignNodeRegistry()
        .findByNodeId(nextNode, null)
        .map(info -> info.definition().trainCartsDestination().orElse(nextNode.value()))
        .orElse(nextNode.value());
  }

  /**
   * 尝试通过列车名找到在线 MinecartGroup。
   *
   * <p>若找不到在线实例，则 debug set route 仍会写入 tags，但不会执行 refreshSignal。
   */
  private static MinecartGroup resolveGroupByTrainName(String trainName) {
    if (trainName == null || trainName.isBlank()) {
      return null;
    }
    Collection<MinecartGroup> groups = MinecartGroupStore.getGroups();
    if (groups == null || groups.isEmpty()) {
      return null;
    }
    for (MinecartGroup group : groups) {
      if (group == null || !group.isValid() || group.getProperties() == null) {
        continue;
      }
      String name = group.getProperties().getTrainName();
      if (name != null && name.equalsIgnoreCase(trainName)) {
        return group;
      }
    }
    return null;
  }

  private void handleDebugList(CommandSender sender) {
    LocaleManager locale = plugin.getLocaleManager();
    var dispatchServiceOpt = plugin.getRuntimeDispatchService();
    if (dispatchServiceOpt.isEmpty()) {
      sender.sendMessage(locale.component("command.train.debug.unavailable"));
      return;
    }
    var dispatchService = dispatchServiceOpt.get();
    var snapshot = dispatchService.getDiagnosticsSnapshot();
    if (snapshot.isEmpty()) {
      sender.sendMessage(locale.component("command.train.debug.list-empty"));
      return;
    }
    sender.sendMessage(
        locale.component(
            "command.train.debug.list-header", Map.of("count", String.valueOf(snapshot.size()))));
    for (var entry : snapshot.entrySet()) {
      sendDiagnosticsOutput(sender, entry.getValue(), locale);
    }
  }

  private void sendDiagnosticsOutput(
      CommandSender sender,
      org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.ControlDiagnostics diag,
      LocaleManager locale) {
    // 基础信息
    sender.sendMessage(
        locale.component(
            "command.train.debug.header",
            Map.of(
                "train",
                diag.trainName(),
                "route",
                diag.routeId() != null ? diag.routeId().value() : "-")));

    // 节点信息
    sender.sendMessage(
        locale.component(
            "command.train.debug.nodes",
            Map.of(
                "current", diag.currentNode() != null ? diag.currentNode().value() : "-",
                "next", diag.nextNode() != null ? diag.nextNode().value() : "-")));

    // 速度信息（含边限速）
    String edgeLimitText = diag.edgeLimitBps() > 0 ? formatSpeed(diag.edgeLimitBps()) : "-";
    sender.sendMessage(
        locale.component(
            "command.train.debug.speed",
            Map.of(
                "current",
                formatSpeed(diag.currentSpeedBps()),
                "target",
                formatSpeed(diag.targetSpeedBps()),
                "edge_limit",
                edgeLimitText,
                "recommended",
                diag.recommendedSpeedBps().isPresent()
                    ? formatSpeed(diag.recommendedSpeedBps().getAsDouble())
                    : "-")));

    // 前瞻距离
    sender.sendMessage(
        locale.component(
            "command.train.debug.lookahead",
            Map.of(
                "blocker", formatDistance(diag.distanceToBlocker()),
                "caution", formatDistance(diag.distanceToCaution()),
                "approach", formatDistance(diag.distanceToApproach()))));

    // 信号与状态
    sender.sendMessage(
        locale.component(
            "command.train.debug.signal",
            Map.of(
                "current", diag.currentSignal().name(),
                "effective", diag.effectiveSignal().name(),
                "launch", diag.allowLaunch() ? "✓" : "✗")));
  }

  private static String formatSpeed(double bps) {
    return String.format("%.2f", bps);
  }

  private static String formatDistance(java.util.OptionalLong distance) {
    return distance.isPresent() ? String.valueOf(distance.getAsLong()) : "-";
  }

  private SuggestionProvider<CommandSender> trainSuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          List<String> suggestions = new ArrayList<>();
          String token = input == null ? "" : input.lastRemainingToken();
          token = token.trim();
          if (token.isBlank()) {
            suggestions.add("<train|@train[...]>"); // 对齐 TrainCarts selector 习惯
          }
          if (token.startsWith("@")) {
            suggestions.addAll(selectorSuggestions(ctx.sender(), token));
          } else {
            if (token.isBlank()) {
              suggestions.add(TRAIN_SELECTOR_PREFIX);
            }
            suggestions.addAll(trainNameSuggestions(token));
          }
          return suggestions;
        });
  }

  private List<String> selectorSuggestions(CommandSender sender, String token) {
    if (token == null) {
      return List.of();
    }
    String normalized = token.trim();
    String lowered = normalized.toLowerCase(Locale.ROOT);
    List<String> suggestions = new ArrayList<>();
    if (lowered.equals("@") || TRAIN_SELECTOR_PREFIX.startsWith(lowered)) {
      suggestions.add(TRAIN_SELECTOR_PREFIX);
      return suggestions;
    }
    if (!lowered.startsWith(TRAIN_SELECTOR_PREFIX)) {
      return List.of();
    }

    if (!normalized.endsWith("]")) {
      suggestions.add(normalized + "]");
    }

    int lastComma = normalized.lastIndexOf(',');
    int lastBracket = normalized.lastIndexOf('[');
    if (lastBracket < 0) {
      return suggestions;
    }
    int tailStart = Math.max(lastComma + 1, lastBracket + 1);
    while (tailStart < normalized.length()
        && Character.isWhitespace(normalized.charAt(tailStart))) {
      tailStart++;
    }
    String prefix = normalized.substring(0, tailStart);
    String tail = normalized.substring(tailStart);
    String tailLower = tail.trim().toLowerCase(Locale.ROOT);
    if (tailLower.isEmpty()) {
      for (String key : TRAIN_SELECTOR_KEYS) {
        suggestions.add(prefix + key);
        if (suggestions.size() >= SUGGESTION_LIMIT) {
          break;
        }
      }
      return suggestions;
    }

    int equalsIndex = tailLower.indexOf('=');
    if (equalsIndex >= 0) {
      String key = tailLower.substring(0, equalsIndex + 1);
      String valuePrefix = tailLower.substring(equalsIndex + 1);
      if (key.equals("name=")) {
        for (String name : trainNameSuggestions(valuePrefix)) {
          suggestions.add(prefix + "name=" + name);
          if (suggestions.size() >= SUGGESTION_LIMIT) {
            break;
          }
        }
        return suggestions;
      }
      if (key.equals("tag=")) {
        for (String tag : trainTagSuggestions(valuePrefix)) {
          suggestions.add(prefix + "tag=" + tag);
          if (suggestions.size() >= SUGGESTION_LIMIT) {
            break;
          }
        }
        return suggestions;
      }
      if (key.equals("sort=")) {
        for (String value : TRAIN_SELECTOR_SORT_VALUES) {
          if (value.startsWith(valuePrefix)) {
            suggestions.add(prefix + "sort=" + value);
          }
        }
        return suggestions;
      }
      if (key.equals("limit=")) {
        for (String value : TRAIN_SELECTOR_LIMIT_VALUES) {
          if (value.startsWith(valuePrefix)) {
            suggestions.add(prefix + "limit=" + value);
          }
        }
        return suggestions;
      }
      if (key.equals("world=")) {
        for (org.bukkit.World world : plugin.getServer().getWorlds()) {
          String name = world.getName();
          if (name.toLowerCase(Locale.ROOT).startsWith(valuePrefix)) {
            suggestions.add(prefix + "world=" + name);
            if (suggestions.size() >= SUGGESTION_LIMIT) {
              break;
            }
          }
        }
        return suggestions;
      }
      if (key.equals("destination=")) {
        for (String destination : trainDestinationSuggestions(valuePrefix)) {
          suggestions.add(prefix + "destination=" + destination);
          if (suggestions.size() >= SUGGESTION_LIMIT) {
            break;
          }
        }
        return suggestions;
      }
      if (key.equals("ticket=")) {
        for (String ticket : trainTicketSuggestions(valuePrefix)) {
          suggestions.add(prefix + "ticket=" + ticket);
          if (suggestions.size() >= SUGGESTION_LIMIT) {
            break;
          }
        }
        return suggestions;
      }
      if (key.equals("passengers=") || key.equals("playerpassengers=")) {
        for (String value : TRAIN_SELECTOR_LIMIT_VALUES) {
          if (value.startsWith(valuePrefix)) {
            suggestions.add(prefix + key + value);
          }
        }
        return suggestions;
      }
      if (key.equals("x=")
          || key.equals("y=")
          || key.equals("z=")
          || key.equals("dx=")
          || key.equals("dy=")
          || key.equals("dz=")
          || key.equals("distance=")
          || key.equals("speed=")
          || key.equals("velocity=")
          || key.equals("speedlimit=")
          || key.equals("friction=")
          || key.equals("gravity=")) {
        for (String value : TRAIN_SELECTOR_NUMBER_VALUES) {
          if (value.startsWith(valuePrefix)) {
            suggestions.add(prefix + key + value);
          }
        }
        return suggestions;
      }
      if (key.equals("unloaded=") || key.equals("derailed=") || key.equals("keepchunksloaded=")) {
        for (String value : TRAIN_SELECTOR_BOOLEAN_VALUES) {
          if (value.startsWith(valuePrefix)) {
            suggestions.add(prefix + key + value);
          }
        }
        return suggestions;
      }
    }

    for (String key : TRAIN_SELECTOR_KEYS) {
      if (key.startsWith(tailLower)) {
        suggestions.add(prefix + key);
        if (suggestions.size() >= SUGGESTION_LIMIT) {
          break;
        }
      }
    }
    return suggestions;
  }

  private static List<String> trainNameSuggestions(String token) {
    String prefix = token == null ? "" : token.trim().toLowerCase(Locale.ROOT);
    List<String> suggestions = new ArrayList<>();
    for (TrainProperties props : TrainPropertiesStore.getAll()) {
      if (props == null) {
        continue;
      }
      String name = props.getTrainName();
      if (name == null || name.isBlank()) {
        continue;
      }
      String normalized = name.trim();
      if (!normalized.toLowerCase(Locale.ROOT).startsWith(prefix)) {
        continue;
      }
      suggestions.add(normalized);
    }
    suggestions.sort(String.CASE_INSENSITIVE_ORDER);
    if (suggestions.size() > SUGGESTION_LIMIT) {
      return suggestions.subList(0, SUGGESTION_LIMIT);
    }
    return suggestions;
  }

  private static List<String> trainTagSuggestions(String token) {
    String prefix = token == null ? "" : token.trim().toLowerCase(Locale.ROOT);
    List<String> suggestions = new ArrayList<>();
    for (TrainProperties props : TrainPropertiesStore.getAll()) {
      if (props == null) {
        continue;
      }
      Collection<String> tags = props.getTags();
      if (tags == null || tags.isEmpty()) {
        continue;
      }
      for (String tag : tags) {
        if (tag == null || tag.isBlank()) {
          continue;
        }
        String normalized = tag.trim();
        if (!normalized.toLowerCase(Locale.ROOT).startsWith(prefix)) {
          continue;
        }
        suggestions.add(normalized);
      }
    }
    suggestions.sort(String.CASE_INSENSITIVE_ORDER);
    if (suggestions.size() > SUGGESTION_LIMIT) {
      return suggestions.subList(0, SUGGESTION_LIMIT);
    }
    return suggestions;
  }

  private static List<String> trainDestinationSuggestions(String token) {
    String prefix = token == null ? "" : token.trim().toLowerCase(Locale.ROOT);
    List<String> suggestions = new ArrayList<>();
    for (TrainProperties props : TrainPropertiesStore.getAll()) {
      if (props == null || !props.hasDestination()) {
        continue;
      }
      String destination = props.getDestination();
      if (destination == null || destination.isBlank()) {
        continue;
      }
      String normalized = destination.trim();
      if (!normalized.toLowerCase(Locale.ROOT).startsWith(prefix)) {
        continue;
      }
      suggestions.add(normalized);
    }
    suggestions.sort(String.CASE_INSENSITIVE_ORDER);
    if (suggestions.size() > SUGGESTION_LIMIT) {
      return suggestions.subList(0, SUGGESTION_LIMIT);
    }
    return suggestions;
  }

  private static List<String> trainTicketSuggestions(String token) {
    String prefix = token == null ? "" : token.trim().toLowerCase(Locale.ROOT);
    List<String> suggestions = new ArrayList<>();
    for (TrainProperties props : TrainPropertiesStore.getAll()) {
      if (props == null) {
        continue;
      }
      Collection<String> tickets = props.getTickets();
      if (tickets == null || tickets.isEmpty()) {
        continue;
      }
      for (String ticket : tickets) {
        if (ticket == null || ticket.isBlank()) {
          continue;
        }
        String normalized = ticket.trim();
        if (!normalized.toLowerCase(Locale.ROOT).startsWith(prefix)) {
          continue;
        }
        suggestions.add(normalized);
      }
    }
    suggestions.sort(String.CASE_INSENSITIVE_ORDER);
    if (suggestions.size() > SUGGESTION_LIMIT) {
      return suggestions.subList(0, SUGGESTION_LIMIT);
    }
    return suggestions;
  }

  private List<TrainProperties> resolveTrainTargets(
      CommandSender sender, Optional<String> rawTrain, LocaleManager locale) {
    String raw = rawTrain.map(String::trim).orElse("");
    if (!raw.isEmpty()) {
      if (isSelector(raw)) {
        return resolveSelector(sender, raw, locale);
      }
      TrainProperties properties = TrainPropertiesStore.get(raw);
      if (properties == null) {
        sender.sendMessage(
            locale.component("command.train.config.not-found", Map.of("train", raw)));
        return List.of();
      }
      return List.of(properties);
    }
    if (sender instanceof Player player) {
      CartProperties editing = CartPropertiesStore.getEditing(player);
      if (editing != null && editing.getTrainProperties() != null) {
        return List.of(editing.getTrainProperties());
      }
      MinecartGroup group = MinecartGroupStore.get(player);
      if (group != null && group.getProperties() != null) {
        return List.of(group.getProperties());
      }
    }
    sendNoSelection(sender, locale);
    return List.of();
  }

  private void sendHelp(CommandSender sender) {
    LocaleManager locale = plugin.getLocaleManager();
    sender.sendMessage(locale.component("command.train.help.header"));
    sendHelpEntry(
        sender,
        locale.component("command.train.help.entry-config-list"),
        ClickEvent.suggestCommand("/fta train config list "),
        locale.component("command.train.help.hover-config-list"));
    sendHelpEntry(
        sender,
        locale.component("command.train.help.entry-config-set"),
        ClickEvent.suggestCommand("/fta train config set "),
        locale.component("command.train.help.hover-config-set"));
    sendHelpEntry(
        sender,
        locale.component("command.train.help.entry-debug"),
        ClickEvent.suggestCommand("/fta train debug "),
        locale.component("command.train.help.hover-debug"));
    sendHelpEntry(
        sender,
        locale.component("command.train.help.entry-debug-list"),
        ClickEvent.suggestCommand("/fta train debug list"),
        locale.component("command.train.help.hover-debug-list"));
    sendHelpEntry(
        sender,
        locale.component("command.train.help.entry-debug-set-route"),
        ClickEvent.suggestCommand("/fta train debug set route "),
        locale.component("command.train.help.hover-debug-set-route"));
    sendHelpEntry(
        sender,
        locale.component("command.train.help.entry-train-edit"),
        ClickEvent.suggestCommand("/train edit "),
        locale.component("command.train.help.hover-train-edit"));
    sendHelpEntry(
        sender,
        locale.component("command.train.help.entry-selector"),
        ClickEvent.suggestCommand("/fta train config list @train["),
        locale.component("command.train.help.hover-selector"));
  }

  private SuggestionProvider<CommandSender> companySuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = normalizeLowerPrefix(input);
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
          String prefix = normalizeLowerPrefix(input);
          List<String> suggestions = new ArrayList<>();
          if (prefix.isBlank()) {
            suggestions.add("<operator>");
          }
          Optional<StorageProvider> providerOpt = CommandStorageProviders.providerIfReady(plugin);
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
          if (!CompanyAccessChecker.canReadCompanyNoCreateIdentity(
              ctx.sender(), provider, company.id())) {
            return suggestions;
          }
          provider.operators().listByCompany(company.id()).stream()
              .map(Operator::code)
              .filter(Objects::nonNull)
              .map(String::trim)
              .filter(code -> !code.isBlank())
              .filter(code -> prefix.isBlank() || code.toLowerCase(Locale.ROOT).startsWith(prefix))
              .distinct()
              .limit(SUGGESTION_LIMIT)
              .forEach(suggestions::add);
          return suggestions;
        });
  }

  private SuggestionProvider<CommandSender> lineSuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = normalizeLowerPrefix(input);
          List<String> suggestions = new ArrayList<>();
          if (prefix.isBlank()) {
            suggestions.add("<line>");
          }
          Optional<StorageProvider> providerOpt = CommandStorageProviders.providerIfReady(plugin);
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
          if (!CompanyAccessChecker.canReadCompanyNoCreateIdentity(
              ctx.sender(), provider, company.id())) {
            return suggestions;
          }
          Optional<Operator> operatorOpt = query.findOperator(company.id(), operatorArg);
          if (operatorOpt.isEmpty()) {
            return suggestions;
          }
          Operator operator = operatorOpt.get();
          provider.lines().listByOperator(operator.id()).stream()
              .map(Line::code)
              .filter(Objects::nonNull)
              .map(String::trim)
              .filter(code -> !code.isBlank())
              .filter(code -> prefix.isBlank() || code.toLowerCase(Locale.ROOT).startsWith(prefix))
              .distinct()
              .limit(SUGGESTION_LIMIT)
              .forEach(suggestions::add);
          return suggestions;
        });
  }

  private SuggestionProvider<CommandSender> routeSuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = normalizeLowerPrefix(input);
          List<String> suggestions = new ArrayList<>();
          if (prefix.isBlank()) {
            suggestions.add("<route>");
          }
          Optional<StorageProvider> providerOpt = CommandStorageProviders.providerIfReady(plugin);
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
          if (!CompanyAccessChecker.canReadCompanyNoCreateIdentity(
              ctx.sender(), provider, company.id())) {
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
          Line line = lineOpt.get();
          provider.routes().listByLine(line.id()).stream()
              .map(Route::code)
              .filter(Objects::nonNull)
              .map(String::trim)
              .filter(code -> !code.isBlank())
              .filter(code -> prefix.isBlank() || code.toLowerCase(Locale.ROOT).startsWith(prefix))
              .distinct()
              .limit(SUGGESTION_LIMIT)
              .forEach(suggestions::add);
          return suggestions;
        });
  }

  private SuggestionProvider<CommandSender> indexOrNodeSuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String token = input == null ? "" : input.lastRemainingToken();
          String prefix = token.trim();
          String normalizedPrefix = normalizeQuotedPrefix(prefix);
          String lowerPrefix = normalizedPrefix.toLowerCase(Locale.ROOT);
          List<String> suggestions = new ArrayList<>();
          if (prefix.isBlank()) {
            suggestions.add("[index|nodeId]");
          }

          Optional<StorageProvider> providerOpt = CommandStorageProviders.providerIfReady(plugin);
          if (providerOpt.isEmpty()) {
            return suggestions;
          }

          Optional<String> companyArgOpt = ctx.optional("company").map(String.class::cast);
          Optional<String> operatorArgOpt = ctx.optional("operator").map(String.class::cast);
          Optional<String> lineArgOpt = ctx.optional("line").map(String.class::cast);
          Optional<String> routeArgOpt = ctx.optional("route").map(String.class::cast);
          if (companyArgOpt.isEmpty()
              || operatorArgOpt.isEmpty()
              || lineArgOpt.isEmpty()
              || routeArgOpt.isEmpty()) {
            return suggestions;
          }

          String companyArg = companyArgOpt.get().trim();
          String operatorArg = operatorArgOpt.get().trim();
          String lineArg = lineArgOpt.get().trim();
          String routeArg = routeArgOpt.get().trim();
          if (companyArg.isBlank()
              || operatorArg.isBlank()
              || lineArg.isBlank()
              || routeArg.isBlank()) {
            return suggestions;
          }

          StorageProvider provider = providerOpt.get();
          CompanyQueryService query = new CompanyQueryService(provider);
          Optional<Company> companyOpt = query.findCompany(companyArg);
          if (companyOpt.isEmpty()) {
            return suggestions;
          }
          Company company = companyOpt.get();
          if (!CompanyAccessChecker.canReadCompanyNoCreateIdentity(
              ctx.sender(), provider, company.id())) {
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
          Optional<Route> routeOpt = query.findRoute(lineOpt.get().id(), routeArg);
          if (routeOpt.isEmpty()) {
            return suggestions;
          }
          Optional<RouteDefinition> definitionOpt =
              plugin.findRouteDefinitionById(routeOpt.get().id());
          if (definitionOpt.isEmpty()) {
            return suggestions;
          }
          RouteDefinition route = definitionOpt.get();

          // 数字索引建议
          for (int i = 0; i < route.waypoints().size(); i++) {
            String candidate = String.valueOf(i);
            if (normalizedPrefix.isBlank() || candidate.startsWith(normalizedPrefix)) {
              suggestions.add(candidate);
            }
            if (suggestions.size() >= SUGGESTION_LIMIT) {
              return suggestions;
            }
          }

          // nodeId 建议（用 value）
          route.waypoints().stream()
              .filter(Objects::nonNull)
              .map(NodeId::value)
              .filter(Objects::nonNull)
              .map(String::trim)
              .filter(v -> !v.isBlank())
              .filter(v -> prefix.isBlank() || v.toLowerCase(Locale.ROOT).startsWith(lowerPrefix))
              .map(FtaTrainCommand::quoteNodeIdSuggestion)
              .distinct()
              .limit(SUGGESTION_LIMIT - suggestions.size())
              .forEach(suggestions::add);
          return suggestions;
        });
  }

  private List<String> listCompanyCodes(CommandSender sender, String prefix) {
    Optional<StorageProvider> providerOpt = CommandStorageProviders.providerIfReady(plugin);
    if (providerOpt.isEmpty()) {
      return List.of();
    }
    StorageProvider provider = providerOpt.get();
    if (sender.hasPermission("fetarute.admin")) {
      return provider.companies().listAll().stream()
          .map(Company::code)
          .filter(Objects::nonNull)
          .map(String::trim)
          .filter(code -> !code.isBlank())
          .filter(code -> prefix.isBlank() || code.toLowerCase(Locale.ROOT).startsWith(prefix))
          .distinct()
          .limit(SUGGESTION_LIMIT)
          .toList();
    }
    if (!(sender instanceof Player player)) {
      return List.of();
    }
    Optional<org.fetarute.fetaruteTCAddon.company.model.PlayerIdentity> identityOpt =
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
        .filter(code -> prefix.isBlank() || code.toLowerCase(Locale.ROOT).startsWith(prefix))
        .distinct()
        .limit(SUGGESTION_LIMIT)
        .toList();
  }

  private static String normalizeLowerPrefix(org.incendo.cloud.context.CommandInput input) {
    if (input == null) {
      return "";
    }
    return input.lastRemainingToken().trim().toLowerCase(Locale.ROOT);
  }

  /**
   * 规范化可能带引号的输入前缀（用于补全过滤）。
   *
   * <p>玩家可能已经输入了起始引号（但尚未输入结束引号），此时直接用原 token 做 startsWith 会匹配不到候选。
   *
   * <p>这里仅去掉两端的双引号（若存在），不做转义解析。
   */
  private static String normalizeQuotedPrefix(String raw) {
    if (raw == null || raw.isBlank()) {
      return "";
    }
    String out = raw.trim();
    if (out.startsWith("\"")) {
      out = out.substring(1);
    }
    if (out.endsWith("\"") && out.length() > 1) {
      out = out.substring(0, out.length() - 1);
    }
    return out.trim();
  }

  /**
   * nodeId 补全返回值：始终加双引号包裹。
   *
   * <p>原因：在游戏内输入 {@code :} 形式的字符串时，部分聊天/命令解析路径可能出现歧义；强制引号能确保 nodeId 作为单个参数被 Cloud 正确接收。
   */
  private static String quoteNodeIdSuggestion(String raw) {
    if (raw == null) {
      return "\"\"";
    }
    String trimmed = raw.trim();
    String escaped = trimmed.replace("\\", "\\\\").replace("\"", "\\\"");
    return "\"" + escaped + "\"";
  }

  private void sendNoSelection(CommandSender sender, LocaleManager locale) {
    sender.sendMessage(locale.component("command.train.config.no-selection"));
    if (!(sender instanceof Player)) {
      return;
    }
    sendHelpEntry(
        sender,
        locale.component("command.train.config.no-selection-entry-train-edit"),
        ClickEvent.suggestCommand("/train edit "),
        locale.component("command.train.config.no-selection-hover-train-edit"));
    sendHelpEntry(
        sender,
        locale.component("command.train.config.no-selection-entry-selector"),
        ClickEvent.suggestCommand("/fta train config list @train["),
        locale.component("command.train.config.no-selection-hover-selector"));
  }

  private void sendHelpEntry(
      CommandSender sender, Component text, ClickEvent clickEvent, Component hoverText) {
    sender.sendMessage(text.clickEvent(clickEvent).hoverEvent(HoverEvent.showText(hoverText)));
  }

  private boolean isSelector(String raw) {
    if (raw == null) {
      return false;
    }
    return raw.trim().toLowerCase(Locale.ROOT).startsWith(TRAIN_SELECTOR_PREFIX);
  }

  private List<TrainProperties> resolveSelector(
      CommandSender sender, String raw, LocaleManager locale) {
    TrainCarts trainCarts = TrainCarts.plugin;
    if (trainCarts == null) {
      return List.of();
    }
    if (!(trainCarts.getSelectorHandlerRegistry() instanceof TCSelectorHandlerRegistry registry)) {
      return List.of();
    }
    Optional<String> selectorOpt = parseTrainSelectorConditions(raw);
    if (selectorOpt.isEmpty()) {
      sender.sendMessage(
          locale.component("command.train.config.invalid-selector", Map.of("raw", raw)));
      return List.of();
    }
    String selectorRaw = selectorOpt.get();
    try {
      List<SelectorCondition> conditions =
          selectorRaw.isBlank() ? List.of() : SelectorCondition.parseAll(selectorRaw);
      Collection<TrainProperties> matched = registry.matchTrains(sender, conditions);
      if (matched.isEmpty()) {
        sender.sendMessage(
            locale.component("command.train.config.not-found", Map.of("train", raw)));
        return List.of();
      }
      return matched.stream()
          .filter(props -> props.getTrainName() != null && !props.getTrainName().isBlank())
          .sorted(Comparator.comparing(props -> props.getTrainName().toLowerCase(Locale.ROOT)))
          .toList();
    } catch (SelectorException ex) {
      sender.sendMessage(locale.component("command.train.config.not-found", Map.of("train", raw)));
      return List.of();
    }
  }

  private static Optional<String> parseTrainSelectorConditions(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String selector = raw.trim();
    if (selector.isEmpty()) {
      return Optional.empty();
    }
    String lowered = selector.toLowerCase(Locale.ROOT);
    if (lowered.startsWith(TRAIN_SELECTOR_PREFIX)) {
      if (!selector.endsWith("]")) {
        return Optional.empty();
      }
      String conditions = selector.substring("@train[".length(), selector.length() - 1).trim();
      return Optional.of(conditions);
    }
    return Optional.empty();
  }
}
