package org.fetarute.fetaruteTCAddon.command;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.command.CommandSender;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.LineStatus;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TerminalKeyResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.LineSpawnMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnDirectiveParser;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnGroup;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnPlan;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnTicket;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.parser.standard.IntegerParser;
import org.incendo.cloud.suggestion.SuggestionProvider;

/**
 * /fta spawn 命令：发车计划与队列诊断。
 *
 * <p>提供以下子命令：
 *
 * <ul>
 *   <li>{@code /fta spawn plan [limit]} - 查看发车计划（可发车的 Route 服务列表）
 *   <li>{@code /fta spawn diagnose [limit]} - 查看发车配置、交路组与跳过原因
 *   <li>{@code /fta spawn queue [limit]} - 查看发车队列（待发票据）
 *   <li>{@code /fta spawn pending [limit]} - 查看折返待发票据（含失败重试）
 *   <li>{@code /fta spawn reset} - 清空发车队列并重置发车计划
 * </ul>
 *
 * <h3>术语说明</h3>
 *
 * <ul>
 *   <li><b>SpawnPlan</b>：从数据库构建的"可发车服务"快照，包含所有 ACTIVE 线路的发车配置
 *   <li><b>SpawnService</b>：单条可发车服务，包含 route、headway（发车间隔）、depotNodeId（默认出库点）
 *   <li><b>SpawnTicket</b>：一张发车票据，由 SpawnManager 生成并由 TicketAssigner 尝试放行
 *   <li><b>Headway</b>：发车间隔，即同一 route 相邻两班车的时间间隔
 *   <li><b>Pending</b>：折返待发，指等待 Layover 列车可用或占用释放的票据
 * </ul>
 *
 * <h3>权限</h3>
 *
 * <p>所有子命令需要 {@code fetarute.spawn} 权限。
 *
 * @see org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnManager
 * @see org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.TicketAssigner
 */
public final class FtaSpawnCommand {

  /** 默认输出条数限制。 */
  private static final int DEFAULT_LIMIT = 20;

  private final FetaruteTCAddon plugin;

  public FtaSpawnCommand(FetaruteTCAddon plugin) {
    this.plugin = plugin;
  }

  /** 注册 /fta spawn 相关命令与补全。 */
  public void register(CommandManager<CommandSender> manager) {
    SuggestionProvider<CommandSender> limitSuggestions =
        CommandSuggestionProviders.placeholder("<limit>");

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("spawn")
            .permission("fetarute.spawn")
            .handler(ctx -> sendHelp(ctx.sender())));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("spawn")
            .literal("plan")
            .permission("fetarute.spawn")
            .optional("limit", IntegerParser.integerParser(1, 100), limitSuggestions)
            .handler(
                ctx -> {
                  int limit = ctx.<Integer>optional("limit").orElse(DEFAULT_LIMIT);
                  listPlan(ctx.sender(), limit);
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("spawn")
            .literal("diagnose")
            .permission("fetarute.spawn")
            .optional("limit", IntegerParser.integerParser(1, 200), limitSuggestions)
            .handler(
                ctx -> {
                  int limit = ctx.<Integer>optional("limit").orElse(DEFAULT_LIMIT);
                  diagnosePlan(ctx.sender(), limit);
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("spawn")
            .literal("queue")
            .permission("fetarute.spawn")
            .optional("limit", IntegerParser.integerParser(1, 100), limitSuggestions)
            .handler(
                ctx -> {
                  int limit = ctx.<Integer>optional("limit").orElse(DEFAULT_LIMIT);
                  listQueue(ctx.sender(), limit);
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("spawn")
            .literal("pending")
            .permission("fetarute.spawn")
            .optional("limit", IntegerParser.integerParser(1, 100), limitSuggestions)
            .handler(
                ctx -> {
                  int limit = ctx.<Integer>optional("limit").orElse(DEFAULT_LIMIT);
                  listPending(ctx.sender(), limit);
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("spawn")
            .literal("reset")
            .permission("fetarute.spawn")
            .handler(ctx -> resetQueue(ctx.sender())));
  }

  private void sendHelp(CommandSender sender) {
    LocaleManager locale = plugin.getLocaleManager();
    sender.sendMessage(locale.component("command.spawn.help.header"));
    sendHelpEntry(
        sender,
        locale.component("command.spawn.help.entry-plan"),
        locale.component("command.spawn.help.hover-plan"),
        "/fta spawn plan ");
    sendHelpEntry(
        sender,
        locale.component("command.spawn.help.entry-diagnose"),
        locale.component("command.spawn.help.hover-diagnose"),
        "/fta spawn diagnose ");
    sendHelpEntry(
        sender,
        locale.component("command.spawn.help.entry-queue"),
        locale.component("command.spawn.help.hover-queue"),
        "/fta spawn queue ");
    sendHelpEntry(
        sender,
        locale.component("command.spawn.help.entry-pending"),
        locale.component("command.spawn.help.hover-pending"),
        "/fta spawn pending ");
    sendHelpEntry(
        sender,
        locale.component("command.spawn.help.entry-reset"),
        locale.component("command.spawn.help.hover-reset"),
        "/fta spawn reset");
  }

  private void sendHelpEntry(
      CommandSender sender, Component entry, Component hover, String suggest) {
    Component msg =
        entry.hoverEvent(HoverEvent.showText(hover)).clickEvent(ClickEvent.suggestCommand(suggest));
    sender.sendMessage(msg);
  }

  /**
   * 列出发车计划（SpawnPlan）。
   *
   * <p>显示所有可发车服务，包括：
   *
   * <ul>
   *   <li>operator/line/route：服务标识
   *   <li>headway：发车间隔（如 2m30s 表示每 2 分 30 秒发一班）
   *   <li>depot：出库点 NodeId（对于 DYNAMIC 首站会显示 placeholder）
   * </ul>
   *
   * @param sender 命令发送者
   * @param limit 最大显示条数
   */
  private void listPlan(CommandSender sender, int limit) {
    LocaleManager locale = plugin.getLocaleManager();

    var mgrOpt = plugin.getSpawnManager();
    if (mgrOpt.isEmpty()) {
      sender.sendMessage(locale.component("command.spawn.not-ready"));
      return;
    }

    SpawnPlan plan = mgrOpt.get().snapshotPlan();
    List<SpawnService> services = plan.services();

    sender.sendMessage(
        locale.component(
            "command.spawn.plan.header",
            Map.of(
                "count", String.valueOf(services.size()),
                "built_at", formatInstant(plan.builtAt()))));

    if (services.isEmpty()) {
      sender.sendMessage(locale.component("command.spawn.plan.empty"));
      return;
    }

    // 按 operatorCode/lineCode/routeCode 排序
    List<SpawnService> sorted =
        services.stream()
            .sorted(
                Comparator.comparing(SpawnService::operatorCode)
                    .thenComparing(SpawnService::lineCode)
                    .thenComparing(SpawnService::routeCode))
            .limit(limit)
            .toList();

    for (SpawnService svc : sorted) {
      sender.sendMessage(
          locale.component(
              "command.spawn.plan.entry",
              Map.of(
                  "operator", svc.operatorCode(),
                  "line", svc.lineCode(),
                  "route", svc.routeCode(),
                  "headway", formatDuration(svc.baseHeadway()),
                  "depot", svc.depotNodeId())));
    }

    if (services.size() > limit) {
      sender.sendMessage(locale.component("command.spawn.plan.truncated"));
    }
  }

  /**
   * 输出 SpawnPlan 配置诊断。
   *
   * <p>该命令直接读取存储层并复用 SpawnManager 的核心选择规则，重点暴露“服务为什么没有进入 SpawnPlan”的原因，避免只在 debug logger 中留下线索。
   */
  private void diagnosePlan(CommandSender sender, int limit) {
    LocaleManager locale = plugin.getLocaleManager();
    Optional<StorageProvider> providerOpt = CommandStorageProviders.providerIfReady(plugin);
    if (providerOpt.isEmpty()) {
      sender.sendMessage(locale.component("command.spawn.not-ready"));
      return;
    }

    List<LineSpawnDiagnosis> lines = buildSpawnDiagnostics(providerOpt.get());
    int groupCount = lines.stream().mapToInt(line -> line.groups().size()).sum();
    int routeCount = lines.stream().mapToInt(line -> line.routes().size()).reduce(0, Integer::sum);
    sender.sendMessage(
        locale.component(
            "command.spawn.diagnose.header",
            Map.of(
                "lines",
                String.valueOf(lines.size()),
                "groups",
                String.valueOf(groupCount),
                "routes",
                String.valueOf(routeCount))));

    if (lines.isEmpty()) {
      sender.sendMessage(locale.component("command.spawn.diagnose.empty"));
      return;
    }

    int shown = 0;
    for (LineSpawnDiagnosis line : lines) {
      if (shown >= limit) {
        break;
      }
      sender.sendMessage(
          locale.component(
              "command.spawn.diagnose.line",
              Map.of(
                  "operator",
                  line.operator().code(),
                  "line",
                  line.line().code(),
                  "line_baseline",
                  line.lineBaseline(),
                  "status",
                  line.line().status().name())));
      for (GroupSpawnDiagnosis group : line.groups()) {
        sender.sendMessage(
            locale.component(
                "command.spawn.diagnose.group",
                Map.of(
                    "group",
                    group.group(),
                    "baseline",
                    group.baseline(),
                    "routes",
                    group.routes())));
      }
      for (RouteSpawnDiagnosis route : line.routes()) {
        if (shown >= limit) {
          break;
        }
        shown++;
        sender.sendMessage(
            locale.component(
                "command.spawn.diagnose.route",
                Map.of(
                    "route",
                    route.route().code(),
                    "operation",
                    route.route().operationType().name(),
                    "group",
                    route.group(),
                    "weight",
                    route.weight(),
                    "start",
                    route.startNode(),
                    "participates",
                    route.participates() ? "是" : "否",
                    "reasons",
                    route.reasons())));
      }
    }

    if (routeCount > shown) {
      sender.sendMessage(
          locale.component(
              "command.spawn.diagnose.truncated",
              Map.of("count", String.valueOf(routeCount - shown))));
    }
  }

  private List<LineSpawnDiagnosis> buildSpawnDiagnostics(StorageProvider provider) {
    List<LineSpawnDiagnosis> lines = new ArrayList<>();
    for (Company company : provider.companies().listAll()) {
      if (company == null) {
        continue;
      }
      for (Operator operator : provider.operators().listByCompany(company.id())) {
        if (operator == null) {
          continue;
        }
        for (Line line : provider.lines().listByOperator(operator.id())) {
          if (line == null) {
            continue;
          }
          LineSpawnDiagnosis diagnosis = diagnoseLine(provider, operator, line);
          if (!diagnosis.routes().isEmpty() || !diagnosis.groups().isEmpty()) {
            lines.add(diagnosis);
          }
        }
      }
    }
    lines.sort(
        Comparator.comparing((LineSpawnDiagnosis line) -> line.operator().code())
            .thenComparing(line -> line.line().code(), String.CASE_INSENSITIVE_ORDER));
    return List.copyOf(lines);
  }

  private LineSpawnDiagnosis diagnoseLine(StorageProvider provider, Operator operator, Line line) {
    List<Route> routes =
        provider.routes().listByLine(line.id()).stream()
            .filter(route -> route != null)
            .sorted(Comparator.comparing(Route::code, String.CASE_INSENSITIVE_ORDER))
            .toList();
    Map<Route, List<RouteStop>> stopsByRoute = new LinkedHashMap<>();
    for (Route route : routes) {
      stopsByRoute.put(route, provider.routeStops().listByRoute(route.id()));
    }
    List<SpawnGroup> configuredGroups =
        LineSpawnMetadata.parseGroups(line.metadata()).stream()
            .sorted(Comparator.comparing(SpawnGroup::name, String.CASE_INSENSITIVE_ORDER))
            .toList();
    List<RouteSpawnDiagnosis> routeDiagnostics = new ArrayList<>();
    for (Route route : routes) {
      if (!isSpawnRelevant(route)) {
        continue;
      }
      routeDiagnostics.add(diagnoseRoute(line, route, routes, stopsByRoute, configuredGroups));
    }
    List<GroupSpawnDiagnosis> groups = diagnoseGroups(line, configuredGroups, routeDiagnostics);
    return new LineSpawnDiagnosis(
        operator,
        line,
        formatSeconds(line.spawnFreqBaselineSec().filter(value -> value > 0)),
        groups,
        routeDiagnostics);
  }

  private RouteSpawnDiagnosis diagnoseRoute(
      Line line,
      Route route,
      List<Route> allRoutes,
      Map<Route, List<RouteStop>> stopsByRoute,
      List<SpawnGroup> configuredGroups) {
    List<RouteStop> stops = stopsByRoute.getOrDefault(route, List.of());
    RouteStop first = stops.isEmpty() ? null : stops.get(0);
    Optional<String> cret = SpawnDirectiveParser.findDirectiveTarget(first, "CRET");
    Optional<String> start = cret.or(() -> resolveStopNodeId(first));
    Optional<String> explicitGroup = readString(route.metadata(), "spawn_group");
    String group = explicitGroup.orElseGet(() -> resolveCirculationGroupKey(route, stops));
    Optional<Integer> groupBaseline = resolveRouteGroupBaseline(line, route, explicitGroup);
    Optional<Integer> lineBaseline = line.spawnFreqBaselineSec().filter(value -> value > 0);
    Optional<Boolean> enabled = readBoolean(route.metadata(), "spawn_enabled");
    Optional<Integer> weight = readInt(route.metadata(), "spawn_weight");

    List<String> blockers = new ArrayList<>();
    List<String> notes = new ArrayList<>();
    if (line.status() != LineStatus.ACTIVE) {
      blockers.add("line 非 ACTIVE");
    }
    if (enabled.isPresent() && !enabled.get()) {
      blockers.add("disabled");
    }
    if (stops.isEmpty()) {
      blockers.add("缺停靠表");
    }
    if (route.operationType() == RouteOperationType.CREATE && cret.isEmpty()) {
      blockers.add("CREATE 缺 CRET");
    }
    if (start.isEmpty()) {
      blockers.add("首站无法解析");
    }
    if (explicitGroup.isPresent()
        && LineSpawnMetadata.findGroup(configuredGroups, explicitGroup.get()).isEmpty()) {
      notes.add("spawn_group 不存在");
    }
    if (groupBaseline.isEmpty() && lineBaseline.isEmpty()) {
      blockers.add("缺 baseline");
    }
    if (route.operationType() == RouteOperationType.OPERATION) {
      if (weight.isPresent() && weight.get() <= 0) {
        blockers.add("spawn_weight<=0");
      } else if (weight.isEmpty()
          && !enabled.orElse(false)
          && allOperationCandidateCount(allRoutes, stopsByRoute) > 1
          && !isSingleRouteInExplicitGroup(route, line, allRoutes)) {
        blockers.add("缺 spawn_weight");
      }
    }

    List<String> reasons = new ArrayList<>(blockers);
    reasons.addAll(notes);
    String weightText =
        switch (route.operationType()) {
          case OPERATION -> weight.map(String::valueOf).orElse("-");
          case CREATE, RETURN -> "fixed=1";
        };
    return new RouteSpawnDiagnosis(
        route,
        group,
        weightText,
        start.orElse("-"),
        blockers.isEmpty(),
        reasons.isEmpty() ? "-" : String.join(", ", reasons));
  }

  private List<GroupSpawnDiagnosis> diagnoseGroups(
      Line line, List<SpawnGroup> configuredGroups, List<RouteSpawnDiagnosis> routes) {
    Map<String, List<String>> routesByGroup = new LinkedHashMap<>();
    Map<String, String> baselinesByGroup = new LinkedHashMap<>();
    for (SpawnGroup group : configuredGroups) {
      routesByGroup.put(group.name(), new ArrayList<>());
      baselinesByGroup.put(group.name(), formatSeconds(group.baselineSeconds()));
    }
    for (RouteSpawnDiagnosis route : routes) {
      routesByGroup
          .computeIfAbsent(route.group(), ignored -> new ArrayList<>())
          .add(route.route().code());
      baselinesByGroup.putIfAbsent(
          route.group(),
          resolveRouteGroupBaseline(
                  line, route.route(), readString(route.route().metadata(), "spawn_group"))
              .map(value -> value + "s")
              .orElse("-"));
    }
    List<GroupSpawnDiagnosis> groups = new ArrayList<>();
    for (Map.Entry<String, List<String>> entry : routesByGroup.entrySet()) {
      List<String> groupRoutes = entry.getValue();
      groupRoutes.sort(String.CASE_INSENSITIVE_ORDER);
      groups.add(
          new GroupSpawnDiagnosis(
              entry.getKey(),
              baselinesByGroup.getOrDefault(entry.getKey(), "-"),
              groupRoutes.isEmpty() ? "-" : String.join(", ", groupRoutes)));
    }
    return List.copyOf(groups);
  }

  private static boolean isSpawnRelevant(Route route) {
    return route != null
        && (route.operationType() == RouteOperationType.OPERATION
            || route.operationType() == RouteOperationType.CREATE
            || route.operationType() == RouteOperationType.RETURN);
  }

  private int allOperationCandidateCount(
      List<Route> allRoutes, Map<Route, List<RouteStop>> stopsByRoute) {
    int count = 0;
    for (Route candidate : allRoutes) {
      if (candidate == null || candidate.operationType() != RouteOperationType.OPERATION) {
        continue;
      }
      List<RouteStop> stops = stopsByRoute.getOrDefault(candidate, List.of());
      RouteStop first = stops.isEmpty() ? null : stops.get(0);
      if (SpawnDirectiveParser.findDirectiveTarget(first, "CRET").isPresent()
          || resolveStopNodeId(first).isPresent()) {
        count++;
      }
    }
    return count;
  }

  private boolean isSingleRouteInExplicitGroup(Route route, Line line, List<Route> allRoutes) {
    Optional<String> group = readString(route.metadata(), "spawn_group");
    if (group.isEmpty()) {
      return false;
    }
    String key = explicitGroupKey(line, group.get());
    long count = 0L;
    for (Route candidate : allRoutes) {
      if (candidate == null || candidate.operationType() != RouteOperationType.OPERATION) {
        continue;
      }
      Optional<String> candidateGroup = readString(candidate.metadata(), "spawn_group");
      if (candidateGroup.isEmpty()) {
        continue;
      }
      if (explicitGroupKey(line, candidateGroup.get()).equalsIgnoreCase(key)) {
        count++;
      }
    }
    return count == 1L;
  }

  private Optional<Integer> resolveRouteGroupBaseline(
      Line line, Route route, Optional<String> explicitGroup) {
    if (line != null && explicitGroup.isPresent()) {
      Optional<Integer> fromLine =
          LineSpawnMetadata.parseGroupBaseline(line.metadata(), explicitGroup.get());
      if (fromLine.isPresent()) {
        return fromLine;
      }
    }
    if (route == null) {
      return Optional.empty();
    }
    Optional<Integer> canonical = readPositiveInt(route.metadata(), "spawn_group_baseline_sec");
    return canonical.isPresent()
        ? canonical
        : readPositiveInt(route.metadata(), "spawn_group_baseline");
  }

  private String resolveCirculationGroupKey(Route route, List<RouteStop> stops) {
    Optional<String> explicitGroup = readString(route.metadata(), "spawn_group");
    if (explicitGroup.isPresent()) {
      return explicitGroup.get();
    }
    Optional<String> start = resolveStartNode(stops);
    if (start.isEmpty()) {
      return route.code();
    }
    String normalized = start.get().trim().toLowerCase(Locale.ROOT);
    if (SpawnDirectiveParser.isDynamicTarget(normalized)) {
      Optional<DynamicStopMatcher.DynamicSpec> spec =
          DynamicStopMatcher.parseDynamicSpec(normalized);
      if (spec.isPresent()) {
        DynamicStopMatcher.DynamicSpec value = spec.get();
        return value.operatorCode().toLowerCase(Locale.ROOT)
            + ":"
            + value.nodeType().toLowerCase(Locale.ROOT)
            + ":"
            + value.nodeName().toLowerCase(Locale.ROOT);
      }
      return normalized;
    }
    return TerminalKeyResolver.extractStationKey(normalized).orElse(normalized);
  }

  private Optional<String> resolveStartNode(List<RouteStop> stops) {
    if (stops == null || stops.isEmpty()) {
      return Optional.empty();
    }
    RouteStop first = stops.get(0);
    return SpawnDirectiveParser.findDirectiveTarget(first, "CRET")
        .or(() -> resolveStopNodeId(first));
  }

  /**
   * 按 SpawnManager 的规则从首停靠点解析起点。
   *
   * <p>普通 stop 使用 {@code waypointNodeId}；DYNAMIC stop 使用 placeholder NodeId。站点 UUID 外键不会被自动换算为
   * nodeId，这能直接暴露“首站无法参与发车计划”的配置问题。
   */
  private Optional<String> resolveStopNodeId(RouteStop stop) {
    if (stop == null) {
      return Optional.empty();
    }
    if (stop.waypointNodeId().isPresent()) {
      return stop.waypointNodeId();
    }
    return DynamicStopMatcher.parseDynamicSpec(stop)
        .map(DynamicStopMatcher.DynamicSpec::toPlaceholderNodeId);
  }

  private static String explicitGroupKey(Line line, String group) {
    String lineKey = line == null ? "" : line.id().toString();
    String groupKey = group == null ? "" : group.trim().toLowerCase(Locale.ROOT);
    return lineKey + "|" + groupKey;
  }

  private static Optional<Boolean> readBoolean(Map<String, Object> metadata, String key) {
    if (metadata == null || metadata.isEmpty() || key == null || key.isBlank()) {
      return Optional.empty();
    }
    Object raw = metadata.get(key);
    if (raw instanceof Boolean value) {
      return Optional.of(value);
    }
    if (raw instanceof String text) {
      String normalized = text.trim().toLowerCase(Locale.ROOT);
      if ("true".equals(normalized)) {
        return Optional.of(true);
      }
      if ("false".equals(normalized)) {
        return Optional.of(false);
      }
    }
    return Optional.empty();
  }

  private static Optional<Integer> readPositiveInt(Map<String, Object> metadata, String key) {
    return readInt(metadata, key).filter(value -> value > 0);
  }

  private static Optional<Integer> readInt(Map<String, Object> metadata, String key) {
    if (metadata == null || metadata.isEmpty() || key == null || key.isBlank()) {
      return Optional.empty();
    }
    Object raw = metadata.get(key);
    if (raw instanceof Number number) {
      return Optional.of(number.intValue());
    }
    if (raw instanceof String text) {
      try {
        return Optional.of(Integer.parseInt(text.trim()));
      } catch (NumberFormatException ignored) {
        return Optional.empty();
      }
    }
    return Optional.empty();
  }

  private static Optional<String> readString(Map<String, Object> metadata, String key) {
    if (metadata == null || metadata.isEmpty() || key == null || key.isBlank()) {
      return Optional.empty();
    }
    Object raw = metadata.get(key);
    if (raw == null) {
      return Optional.empty();
    }
    String value = String.valueOf(raw).trim();
    return value.isBlank() ? Optional.empty() : Optional.of(value);
  }

  private static String formatSeconds(Optional<Integer> seconds) {
    return seconds.filter(value -> value > 0).map(value -> value + "s").orElse("-");
  }

  /**
   * 列出发车队列（待发票据）。
   *
   * <p>显示由 SpawnManager 生成、等待 TicketAssigner 放行的票据：
   *
   * <ul>
   *   <li>operator/line/route：服务标识
   *   <li>due：计划发车时间（相对当前时间）
   *   <li>notBefore：最早可发车时间（用于重试延迟）
   *   <li>attempts：尝试次数（0 表示首次尝试）
   * </ul>
   *
   * @param sender 命令发送者
   * @param limit 最大显示条数
   */
  private void listQueue(CommandSender sender, int limit) {
    LocaleManager locale = plugin.getLocaleManager();

    var mgrOpt = plugin.getSpawnManager();
    if (mgrOpt.isEmpty()) {
      sender.sendMessage(locale.component("command.spawn.not-ready"));
      return;
    }

    List<SpawnTicket> queue = mgrOpt.get().snapshotQueue();

    sender.sendMessage(
        locale.component(
            "command.spawn.queue.header", Map.of("count", String.valueOf(queue.size()))));

    if (queue.isEmpty()) {
      sender.sendMessage(locale.component("command.spawn.queue.empty"));
      return;
    }

    // 按 dueAt 排序
    List<SpawnTicket> sorted =
        queue.stream().sorted(Comparator.comparing(SpawnTicket::dueAt)).limit(limit).toList();

    Instant now = Instant.now();
    for (SpawnTicket ticket : sorted) {
      SpawnService svc = ticket.service();
      String dueSec = formatDurationSec(Duration.between(now, ticket.dueAt()));
      String notBeforeSec = formatDurationSec(Duration.between(now, ticket.notBefore()));
      sender.sendMessage(
          locale.component(
              "command.spawn.queue.entry",
              Map.of(
                  "operator", svc.operatorCode(),
                  "line", svc.lineCode(),
                  "route", svc.routeCode(),
                  "due", dueSec,
                  "not_before", notBeforeSec,
                  "attempts", String.valueOf(ticket.attempts()))));
    }

    if (queue.size() > limit) {
      sender.sendMessage(locale.component("command.spawn.queue.truncated"));
    }
  }

  /**
   * 列出折返待发票据（Pending Layover）。
   *
   * <p>显示等待 Layover 列车可用或占用释放的票据，这些票据已从主队列移出，等待条件满足后立即派发：
   *
   * <ul>
   *   <li>operator/line/route：服务标识
   *   <li>due：原计划发车时间（相对当前时间）
   *   <li>attempts：尝试次数
   *   <li>error：上次失败原因（如占用冲突、列车不可用等）
   * </ul>
   *
   * @param sender 命令发送者
   * @param limit 最大显示条数
   */
  private void listPending(CommandSender sender, int limit) {
    LocaleManager locale = plugin.getLocaleManager();

    var assignerOpt = plugin.getSpawnTicketAssigner();
    if (assignerOpt.isEmpty()) {
      sender.sendMessage(locale.component("command.spawn.not-ready"));
      return;
    }

    List<SpawnTicket> pending = assignerOpt.get().snapshotPendingTickets();

    sender.sendMessage(
        locale.component(
            "command.spawn.pending.header", Map.of("count", String.valueOf(pending.size()))));

    if (pending.isEmpty()) {
      sender.sendMessage(locale.component("command.spawn.pending.empty"));
      return;
    }

    // 按 dueAt 排序
    List<SpawnTicket> sorted =
        pending.stream().sorted(Comparator.comparing(SpawnTicket::dueAt)).limit(limit).toList();

    Instant now = Instant.now();
    for (SpawnTicket ticket : sorted) {
      SpawnService svc = ticket.service();
      String dueSec = formatDurationSec(Duration.between(now, ticket.dueAt()));
      String error = ticket.lastError().orElse("-");
      sender.sendMessage(
          locale.component(
              "command.spawn.pending.entry",
              Map.of(
                  "operator", svc.operatorCode(),
                  "line", svc.lineCode(),
                  "route", svc.routeCode(),
                  "due", dueSec,
                  "attempts", String.valueOf(ticket.attempts()),
                  "error", error)));
    }

    if (pending.size() > limit) {
      sender.sendMessage(locale.component("command.spawn.pending.truncated"));
    }
  }

  private void resetQueue(CommandSender sender) {
    LocaleManager locale = plugin.getLocaleManager();
    var mgrOpt = plugin.getSpawnManager();
    var assignerOpt = plugin.getSpawnTicketAssigner();
    if (mgrOpt.isEmpty() || assignerOpt.isEmpty()) {
      sender.sendMessage(locale.component("command.spawn.not-ready"));
      return;
    }

    int clearedQueue = 0;
    int clearedStates = 0;
    boolean planReset = false;
    if (mgrOpt.get()
        instanceof
        org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnResetSupport
        support) {
      var result = support.reset(Instant.now());
      clearedQueue = result.clearedQueue();
      clearedStates = result.clearedStates();
      planReset = result.planReset();
    } else {
      sender.sendMessage(locale.component("command.spawn.reset.not-supported"));
    }

    int clearedPending = assignerOpt.get().clearPendingTickets();
    assignerOpt.get().resetDiagnostics();

    sender.sendMessage(
        locale.component(
            "command.spawn.reset.done",
            Map.of(
                "queue",
                String.valueOf(clearedQueue),
                "pending",
                String.valueOf(clearedPending),
                "states",
                String.valueOf(clearedStates),
                "plan",
                planReset ? "✓" : "✗")));
  }

  private record LineSpawnDiagnosis(
      Operator operator,
      Line line,
      String lineBaseline,
      List<GroupSpawnDiagnosis> groups,
      List<RouteSpawnDiagnosis> routes) {
    private LineSpawnDiagnosis {
      groups = groups == null ? List.of() : List.copyOf(groups);
      routes = routes == null ? List.of() : List.copyOf(routes);
    }
  }

  private record GroupSpawnDiagnosis(String group, String baseline, String routes) {}

  private record RouteSpawnDiagnosis(
      Route route,
      String group,
      String weight,
      String startNode,
      boolean participates,
      String reasons) {}

  /**
   * 格式化 Instant 为 ISO 字符串。
   *
   * @param instant 时间点
   * @return ISO 格式字符串，若为 null 或 EPOCH 则返回 "-"
   */
  private static String formatInstant(Instant instant) {
    if (instant == null || instant.equals(Instant.EPOCH)) {
      return "-";
    }
    return instant.toString();
  }

  /**
   * 格式化 Duration 为人类可读字符串（如 "2m30s"）。
   *
   * @param duration 时长
   * @return 格式化字符串，若为 null 则返回 "-"
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
   * 格式化相对时间（可能为负，表示已过期）。
   *
   * @param duration 时长（正数表示未来，负数表示已过期）
   * @return 格式化字符串，负数前缀"已过期"
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
}
