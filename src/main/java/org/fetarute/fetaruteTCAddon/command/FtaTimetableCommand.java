package org.fetarute.fetaruteTCAddon.command;

import static org.fetarute.fetaruteTCAddon.command.CompanyAccessChecker.canManageCompany;
import static org.fetarute.fetaruteTCAddon.command.CompanyAccessChecker.canReadCompany;
import static org.fetarute.fetaruteTCAddon.command.CompanyAccessChecker.canReadCompanyNoCreateIdentity;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.api.CompanyQueryService;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.DynamicTravelTimeModel;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailTravelTimeModel;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.LineSpawnMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.Timetable;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableBuildOptions;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableBuildResult;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableBuilder;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableConflictChecker;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableCsvExporter;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableHeadwayDefaults;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableRoutePlan;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableStatus;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableStop;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableTrip;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.VehicleDuty;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.VehicleDutyPlanner;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.WeightedTripAllocator;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.scope.TimetableFootprint;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.scope.TimetableNeighborhoodLoader;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.component.CommandComponent;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.parser.flag.CommandFlag;
import org.incendo.cloud.parser.standard.IntegerParser;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.SuggestionProvider;

/**
 * {@code /fta timetable} 命令：从运行网络生成时刻表并投入运行。
 *
 * <p>只有两步，没有"先去实服录一遍"这一环：
 *
 * <ol>
 *   <li>{@code build} —— 按 route 定义、调度图限速与运营参数算出整份表，同时报出目标/实际服务比例与车辆交路边界。
 *   <li>{@code publish} —— 只有这一步才会改变列车行为。
 * </ol>
 *
 * <p>时刻表以<b>线路</b>为单位：一条线下的多条 route 通过 weight 分配服务比例，因此参数里没有 route。
 */
public final class FtaTimetableCommand {

  private static final int SUGGESTION_LIMIT = 20;
  private static final int PAGE_SIZE = 10;

  /** 边未设限速时的兜底速度，与 ETA 模块保持同一口径。 */
  private static final double FALLBACK_SPEED_BPS = 8.0D;

  private final FetaruteTCAddon plugin;

  public FtaTimetableCommand(FetaruteTCAddon plugin) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
  }

  /** 注册 {@code /fta timetable} 子命令与补全。 */
  public void register(CommandManager<CommandSender> manager) {
    SuggestionProvider<CommandSender> companySuggestions = companySuggestions();
    SuggestionProvider<CommandSender> operatorSuggestions = operatorSuggestions();
    SuggestionProvider<CommandSender> lineSuggestions = lineSuggestions();
    SuggestionProvider<CommandSender> codeSuggestions = timetableCodeSuggestions();

    CommandFlag<Void> confirmFlag = CommandFlag.builder("confirm").build();
    var nameFlag = stringFlag("name", "\"<name>\"");
    var zoneFlag = stringFlag("zone", "<zoneId>");
    var prefixFlag = stringFlag("prefix", "<tripCodePrefix>");
    var startFlag = stringFlag("start", "<HH:mm>");
    var endFlag = stringFlag("end", "<HH:mm>");
    var headwayFlag = intFlag("headway", "<seconds>", 10, 7200);
    var dwellFlag = intFlag("dwell", "<seconds>", 0, 600);
    var maxTripsFlag = intFlag("max-trips", "<trips>", 1, 64);
    var maxDutyFlag = intFlag("max-duty-minutes", "<minutes>", 1, 1440);
    var turnaroundFlag = intFlag("turnaround", "<seconds>", 0, 3600);
    var separationFlag = intFlag("separation", "<seconds>", 0, 3600);
    CommandFlag<Void> strictFlag = CommandFlag.builder("strict").build();

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("timetable")
            .permission("fetarute.timetable")
            .handler(ctx -> sendHelp(ctx.sender())));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("timetable")
            .literal("build")
            .permission("fetarute.timetable.manage")
            .required("company", StringParser.quotedStringParser(), companySuggestions)
            .required("operator", StringParser.quotedStringParser(), operatorSuggestions)
            .required("line", StringParser.quotedStringParser(), lineSuggestions)
            .required(
                "code",
                StringParser.quotedStringParser(),
                CommandSuggestionProviders.placeholder("<code>"))
            .flag(headwayFlag)
            .flag(startFlag)
            .flag(endFlag)
            .flag(dwellFlag)
            .flag(maxTripsFlag)
            .flag(maxDutyFlag)
            .flag(turnaroundFlag)
            .flag(separationFlag)
            .flag(strictFlag)
            .flag(nameFlag)
            .flag(prefixFlag)
            .flag(zoneFlag)
            .handler(
                ctx ->
                    handleBuild(
                        ctx,
                        new BuildFlags(
                            ctx.flags().getValue(headwayFlag).orElse(null),
                            ctx.flags().getValue(startFlag).orElse(null),
                            ctx.flags().getValue(endFlag).orElse(null),
                            intValue(ctx, dwellFlag, TimetableBuildOptions.DEFAULT_DWELL_SECONDS),
                            intValue(
                                ctx, maxTripsFlag, VehicleDutyPlanner.Limits.DEFAULT_MAX_TRIPS),
                            intValue(
                                ctx,
                                maxDutyFlag,
                                VehicleDutyPlanner.Limits.DEFAULT_MAX_DURATION_SECONDS / 60),
                            intValue(
                                ctx,
                                turnaroundFlag,
                                VehicleDutyPlanner.Limits.DEFAULT_TURNAROUND_SECONDS),
                            intValue(
                                ctx,
                                separationFlag,
                                TimetableBuildOptions.DEFAULT_SEPARATION_SECONDS),
                            ctx.flags().isPresent(strictFlag),
                            ctx.flags().getValue(nameFlag).orElse(null),
                            ctx.flags().getValue(prefixFlag).orElse(null),
                            ctx.flags().getValue(zoneFlag).orElse(null)))));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("timetable")
            .literal("list")
            .permission("fetarute.timetable")
            .required("company", StringParser.quotedStringParser(), companySuggestions)
            .required("operator", StringParser.quotedStringParser(), operatorSuggestions)
            .required("line", StringParser.quotedStringParser(), lineSuggestions)
            .handler(this::handleList));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("timetable")
            .literal("info")
            .permission("fetarute.timetable")
            .required("company", StringParser.quotedStringParser(), companySuggestions)
            .required("operator", StringParser.quotedStringParser(), operatorSuggestions)
            .required("line", StringParser.quotedStringParser(), lineSuggestions)
            .required("code", StringParser.quotedStringParser(), codeSuggestions)
            .optional(
                "page",
                IntegerParser.integerParser(1, 1000),
                CommandSuggestionProviders.placeholder("<page>"))
            .handler(this::handleInfo));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("timetable")
            .literal("duties")
            .permission("fetarute.timetable")
            .required("company", StringParser.quotedStringParser(), companySuggestions)
            .required("operator", StringParser.quotedStringParser(), operatorSuggestions)
            .required("line", StringParser.quotedStringParser(), lineSuggestions)
            .required("code", StringParser.quotedStringParser(), codeSuggestions)
            .optional(
                "page",
                IntegerParser.integerParser(1, 1000),
                CommandSuggestionProviders.placeholder("<page>"))
            .handler(this::handleDuties));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("timetable")
            .literal("publish")
            .permission("fetarute.timetable.manage")
            .required("company", StringParser.quotedStringParser(), companySuggestions)
            .required("operator", StringParser.quotedStringParser(), operatorSuggestions)
            .required("line", StringParser.quotedStringParser(), lineSuggestions)
            .required("code", StringParser.quotedStringParser(), codeSuggestions)
            .handler(ctx -> handleStatusChange(ctx, TimetableStatus.PUBLISHED)));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("timetable")
            .literal("unpublish")
            .permission("fetarute.timetable.manage")
            .required("company", StringParser.quotedStringParser(), companySuggestions)
            .required("operator", StringParser.quotedStringParser(), operatorSuggestions)
            .required("line", StringParser.quotedStringParser(), lineSuggestions)
            .required("code", StringParser.quotedStringParser(), codeSuggestions)
            .handler(ctx -> handleStatusChange(ctx, TimetableStatus.DRAFT)));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("timetable")
            .literal("delete")
            .permission("fetarute.timetable.manage")
            .required("company", StringParser.quotedStringParser(), companySuggestions)
            .required("operator", StringParser.quotedStringParser(), operatorSuggestions)
            .required("line", StringParser.quotedStringParser(), lineSuggestions)
            .required("code", StringParser.quotedStringParser(), codeSuggestions)
            .flag(confirmFlag)
            .handler(ctx -> handleDelete(ctx, ctx.flags().isPresent(confirmFlag))));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("timetable")
            .literal("export")
            .permission("fetarute.timetable")
            .required("company", StringParser.quotedStringParser(), companySuggestions)
            .required("operator", StringParser.quotedStringParser(), operatorSuggestions)
            .required("line", StringParser.quotedStringParser(), lineSuggestions)
            .required("code", StringParser.quotedStringParser(), codeSuggestions)
            .optional(
                "limit",
                IntegerParser.integerParser(1, 200),
                CommandSuggestionProviders.placeholder("<limit>"))
            .handler(this::handleExport));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("timetable")
            .literal("status")
            .permission("fetarute.timetable")
            .handler(ctx -> handleRuntimeStatus(ctx.sender())));
  }

  // ---------------------------------------------------------------- handlers

  private void sendHelp(CommandSender sender) {
    sender.sendMessage(Component.text("===== /fta timetable =====", NamedTextColor.DARK_AQUA));
    sender.sendMessage(hint("编表", "/fta timetable build <company> <operator> <line> <code>"));
    sender.sendMessage(
        Component.text(
            "    可选: --headway --start --end --dwell --max-trips --max-duty-minutes --turnaround"
                + " --separation --strict --name --prefix --zone",
            NamedTextColor.DARK_GRAY));
    sender.sendMessage(hint("列表", "/fta timetable list <company> <operator> <line>"));
    sender.sendMessage(hint("详情", "/fta timetable info <company> <operator> <line> <code>"));
    sender.sendMessage(hint("车辆交路", "/fta timetable duties <company> <operator> <line> <code>"));
    sender.sendMessage(hint("投入运行", "/fta timetable publish <company> <operator> <line> <code>"));
    sender.sendMessage(hint("撤出运行", "/fta timetable unpublish <company> <operator> <line> <code>"));
    sender.sendMessage(hint("导出 CSV", "/fta timetable export <company> <operator> <line> <code>"));
    sender.sendMessage(hint("运行态", "/fta timetable status"));
    sender.sendMessage(Component.text("时刻表由 FTCA 按路网算出，不需要先去实服录制。", NamedTextColor.GRAY));
  }

  private void handleBuild(CommandContext<CommandSender> ctx, BuildFlags flags) {
    CommandSender sender = ctx.sender();
    Optional<StorageProvider> providerOpt = readyProvider(sender);
    if (providerOpt.isEmpty()) {
      return;
    }
    StorageProvider provider = providerOpt.get();
    ResolvedLine resolved = resolveLine(ctx, provider, true);
    if (resolved == null) {
      return;
    }
    String code = ((String) ctx.get("code")).trim();
    if (code.isBlank()) {
      sender.sendMessage(Component.text("时刻表 code 不能为空。", NamedTextColor.RED));
      return;
    }
    ZoneId zone = resolveZone(flags.zone());
    if (zone == null) {
      sender.sendMessage(Component.text("无法识别的时区：" + flags.zone(), NamedTextColor.RED));
      return;
    }
    Optional<Integer> start = parseClock(flags.start());
    Optional<Integer> end = parseClock(flags.end());
    if ((flags.start() != null && start.isEmpty()) || (flags.end() != null && end.isEmpty())) {
      sender.sendMessage(Component.text("首末班时刻格式应为 HH:mm。", NamedTextColor.RED));
      return;
    }
    int serviceStart = start.orElse(TimetableBuildOptions.DEFAULT_SERVICE_START);
    int serviceEnd = end.orElse(TimetableBuildOptions.DEFAULT_SERVICE_END);
    if (serviceEnd <= serviceStart) {
      sender.sendMessage(
          Component.text(
              "末班时刻不能早于首班：跨零点请写成 25:00 这样的形式（当前首班 "
                  + TimetableCsvExporter.clock(serviceStart)
                  + "，末班 "
                  + TimetableCsvExporter.clock(serviceEnd)
                  + "）。",
              NamedTextColor.RED));
      return;
    }

    Optional<Timetable> existing =
        provider.timetables().findByLineAndCode(resolved.line().id(), code);
    if (existing.filter(Timetable::published).isPresent()) {
      sender.sendMessage(Component.text("该时刻表正在运行中，请先 unpublish 再重新 build。", NamedTextColor.RED));
      return;
    }

    List<TimetableBuilder.RouteInput> routeInputs = new ArrayList<>();
    RailGraph graph = null;
    boolean anyOperation = false;
    for (Route route : collectRoutes(provider, resolved)) {
      Optional<RouteDefinition> definitionOpt = plugin.findRouteDefinitionById(route.id());
      if (definitionOpt.isEmpty()) {
        sender.sendMessage(
            Component.text("跳过 route " + route.code() + "：交路定义未加载。", NamedTextColor.YELLOW));
        continue;
      }
      RouteDefinition definition = definitionOpt.get();
      if (graph == null) {
        graph = resolveGraph(definition).orElse(null);
      }
      List<RouteStop> stops =
          provider.routeStops().listByRoute(route.id()).stream()
              .filter(Objects::nonNull)
              .sorted(Comparator.comparingInt(RouteStop::sequence))
              .toList();
      anyOperation |= route.operationType() == RouteOperationType.OPERATION;
      routeInputs.add(
          new TimetableBuilder.RouteInput(
              route.id(),
              route.code(),
              route.operationType(),
              readWeight(route),
              definition,
              stops,
              Optional.empty()));
    }
    if (!anyOperation) {
      sender.sendMessage(Component.text("该线路下没有可编表的 OPERATION route。", NamedTextColor.RED));
      return;
    }
    if (graph == null) {
      sender.sendMessage(
          Component.text("找不到覆盖这条线路的调度图快照，请先 /fta graph build。", NamedTextColor.RED));
      return;
    }

    // baseline 频率是运营目标，时刻表从它出发；命令显式给了 --headway 才覆盖。
    TimetableHeadwayDefaults.Choice headway =
        TimetableHeadwayDefaults.resolve(
            Optional.ofNullable(flags.headwaySeconds()),
            resolved.line().spawnFreqBaselineSec(),
            LineSpawnMetadata.parseGroups(resolved.line().metadata()));
    TimetableBuildOptions options =
        new TimetableBuildOptions(
            serviceStart,
            serviceEnd,
            Duration.ofSeconds(headway.seconds()),
            Duration.ofSeconds(flags.dwellSeconds()),
            new VehicleDutyPlanner.Limits(
                flags.maxTripsPerDuty(), flags.maxDutyMinutes() * 60, flags.turnaroundSeconds()),
            flags.tripCodePrefix() == null ? "" : flags.tripCodePrefix(),
            zone,
            Duration.ofSeconds(flags.separationSeconds()),
            flags.strict());

    TimetableBuilder.BuildInput input =
        new TimetableBuilder.BuildInput(
            existing.map(Timetable::id).orElseGet(UUID::randomUUID),
            resolved.company().id(),
            resolved.operator().id(),
            resolved.line().id(),
            code,
            flags.name() == null ? code : flags.name(),
            routeInputs,
            graph,
            travelTimeModel(),
            Optional.empty());

    // 邻表输入在主线程读库（已发布表、无表线路的 route 与停靠），足迹计算与 build 一起进异步线程。
    NeighborInputs neighborInputs = collectNeighborInputs(provider, resolved, routeInputs);
    RailGraph graphSnapshot = graph;

    // 构建是纯 CPU 运算：时分积分、SWRR、派车、冲突扫描，目标间隔有冲突时还要向上搜索几十次。
    // 输入全是不可变快照，放到异步线程跑，报告与落库回到主线程。
    sender.sendMessage(Component.text("正在按路网编表…", NamedTextColor.GRAY));
    Instant builtAt = Instant.now();
    plugin
        .getServer()
        .getScheduler()
        .runTaskAsynchronously(
            plugin,
            () -> {
              TimetableBuildResult result;
              NeighborReport neighborReport;
              try {
                result = new TimetableBuilder().build(input, options, builtAt);
                neighborReport = neighborInputs.report(graphSnapshot, input.timetableId());
              } catch (RuntimeException ex) {
                plugin
                    .getServer()
                    .getScheduler()
                    .runTask(
                        plugin,
                        () ->
                            sender.sendMessage(
                                Component.text("编表失败：" + ex.getMessage(), NamedTextColor.RED)));
                return;
              }
              TimetableBuildResult built = result;
              NeighborReport neighbors = neighborReport;
              plugin
                  .getServer()
                  .getScheduler()
                  .runTask(
                      plugin,
                      () ->
                          finishBuild(
                              sender, provider, resolved, built, options, headway, neighbors));
            });
  }

  /** 构建完成后的主线程收尾：报告、落库、给出发布入口。 */
  private void finishBuild(
      CommandSender sender,
      StorageProvider provider,
      ResolvedLine resolved,
      TimetableBuildResult result,
      TimetableBuildOptions options,
      TimetableHeadwayDefaults.Choice headway,
      NeighborReport neighbors) {
    sendBuildReport(sender, result, options, headway);
    sendNeighborReport(sender, neighbors);
    if (result.timetable().isEmpty()) {
      return;
    }
    try {
      provider.timetables().save(result.timetable().get());
    } catch (StorageException ex) {
      sender.sendMessage(Component.text("保存时刻表失败：" + ex.getMessage(), NamedTextColor.RED));
      return;
    }
    Timetable timetable = result.timetable().get();
    String publishCommand =
        "/fta timetable publish "
            + resolved.company().code()
            + " "
            + resolved.operator().code()
            + " "
            + resolved.line().code()
            + " "
            + timetable.code();
    sender.sendMessage(
        Component.text("已保存草稿：" + timetable.code(), NamedTextColor.DARK_AQUA)
            .append(Component.text(" [投入运行]", NamedTextColor.GREEN))
            .clickEvent(ClickEvent.suggestCommand(publishCommand)));
  }

  /**
   * 参与编表的 route：本线路的全部 route，外加本运营商其他线路上的 CREATE/RETURN。
   *
   * <p>出库/回库线路经常挂在车库所在的那条线上而不是被服务的线路上；回收侧（ReclaimManager）也是按运营商范围找 RETURN 的，
   * 编表时用同一范围，才不会出现"回收能找到、编表却说没有回库线路"。
   */
  private static List<Route> collectRoutes(StorageProvider provider, ResolvedLine resolved) {
    Map<UUID, Route> out = new java.util.LinkedHashMap<>();
    for (Route route : provider.routes().listByLine(resolved.line().id())) {
      if (route != null) {
        out.put(route.id(), route);
      }
    }
    for (Line line : provider.lines().listByOperator(resolved.operator().id())) {
      if (line == null || line.id().equals(resolved.line().id())) {
        continue;
      }
      for (Route route : provider.routes().listByLine(line.id())) {
        if (route != null && route.operationType() != RouteOperationType.OPERATION) {
          out.putIfAbsent(route.id(), route);
        }
      }
    }
    return List.copyOf(out.values());
  }

  /**
   * 邻表足迹计算需要的输入：全部在主线程读好，异步线程只做纯运算。
   *
   * @param published 已发布时刻表
   * @param displayCodeById 已发布时刻表的显示码
   * @param mine 我这份表的 route
   * @param unscheduled 本世界里不属于任何已发布表、也不属于我这条 line 的 route
   * @param stopsByRoute 上述全部 route 的停靠配置
   * @param definitions 上述全部 route 的交路定义（主线程解析好，异步线程不碰缓存）
   * @param travelTimeModel 行程时间模型
   * @param lineId 我这条 line
   * @param myDisplayCode 我这份表的显示码
   */
  private record NeighborInputs(
      List<Timetable> published,
      Map<UUID, String> displayCodeById,
      List<TimetableNeighborhoodLoader.RouteCandidate> mine,
      List<TimetableNeighborhoodLoader.RouteCandidate> unscheduled,
      Map<UUID, List<RouteStop>> stopsByRoute,
      Map<UUID, RouteDefinition> definitions,
      RailTravelTimeModel travelTimeModel,
      UUID lineId,
      String myDisplayCode) {

    NeighborReport report(RailGraph graph, UUID timetableId) {
      TimetableNeighborhoodLoader loader =
          new TimetableNeighborhoodLoader(
              new org.fetarute
                  .fetaruteTCAddon
                  .dispatcher
                  .schedule
                  .timetable
                  .TimetableTimingCalculator(),
              travelTimeModel,
              routeId -> Optional.ofNullable(definitions.get(routeId)),
              routeId -> stopsByRoute.getOrDefault(routeId, List.of()),
              timetable -> displayCodeById.getOrDefault(timetable.id(), timetable.code()));
      TimetableConflictChecker.GraphIndex index = TimetableConflictChecker.GraphIndex.of(graph);
      TimetableFootprint footprint =
          loader.footprintOf(timetableId, myDisplayCode, mine, graph, index);
      return new NeighborReport(
          loader.footprints(published, lineId, graph, index, footprint),
          loader.unscheduled(unscheduled, graph, index, footprint));
    }
  }

  private record NeighborReport(
      List<TimetableNeighborhoodLoader.FootprintNeighbor> scheduled,
      List<TimetableNeighborhoodLoader.UnscheduledNeighbor> unscheduled) {}

  /** 主线程读库：已发布表及其显示码、无表线路的 route、全部相关 route 的停靠配置。 */
  private NeighborInputs collectNeighborInputs(
      StorageProvider provider,
      ResolvedLine resolved,
      List<TimetableBuilder.RouteInput> routeInputs) {
    String myDisplayCode =
        resolved.company().code() + "/" + resolved.operator().code() + "/" + resolved.line().code();
    Map<UUID, List<RouteStop>> stopsByRoute = new java.util.HashMap<>();
    Map<UUID, RouteDefinition> definitions = new java.util.HashMap<>();
    List<TimetableNeighborhoodLoader.RouteCandidate> mine = new ArrayList<>();
    for (TimetableBuilder.RouteInput route : routeInputs) {
      stopsByRoute.put(route.routeId(), route.stops());
      definitions.put(route.routeId(), route.definition());
      mine.add(
          new TimetableNeighborhoodLoader.RouteCandidate(
              route.routeId(), route.routeCode(), myDisplayCode));
    }
    List<Timetable> published = provider.timetables().listPublished();
    Map<UUID, String> displayCodeById = new java.util.HashMap<>();
    java.util.Set<UUID> scheduledLines = new java.util.HashSet<>();
    for (Timetable timetable : published) {
      scheduledLines.add(timetable.lineId());
      displayCodeById.put(
          timetable.id(), displayCodeOf(provider, timetable) + "/" + timetable.code());
      for (UUID routeId : timetable.routeIds()) {
        stopsByRoute.computeIfAbsent(routeId, id -> sortedStops(provider, id));
        plugin.findRouteDefinitionById(routeId).ifPresent(def -> definitions.put(routeId, def));
      }
    }
    List<TimetableNeighborhoodLoader.RouteCandidate> unscheduled = new ArrayList<>();
    for (Company company : provider.companies().listAll()) {
      for (Operator operator : provider.operators().listByCompany(company.id())) {
        for (Line line : provider.lines().listByOperator(operator.id())) {
          if (line.id().equals(resolved.line().id()) || scheduledLines.contains(line.id())) {
            continue;
          }
          String lineCode = company.code() + "/" + operator.code() + "/" + line.code();
          for (Route route : provider.routes().listByLine(line.id())) {
            stopsByRoute.computeIfAbsent(route.id(), id -> sortedStops(provider, id));
            plugin
                .findRouteDefinitionById(route.id())
                .ifPresent(def -> definitions.put(route.id(), def));
            unscheduled.add(
                new TimetableNeighborhoodLoader.RouteCandidate(route.id(), route.code(), lineCode));
          }
        }
      }
    }
    return new NeighborInputs(
        published,
        displayCodeById,
        mine,
        unscheduled,
        stopsByRoute,
        definitions,
        travelTimeModel(),
        resolved.line().id(),
        myDisplayCode);
  }

  private static List<RouteStop> sortedStops(StorageProvider provider, UUID routeId) {
    return provider.routeStops().listByRoute(routeId).stream()
        .filter(Objects::nonNull)
        .sorted(Comparator.comparingInt(RouteStop::sequence))
        .toList();
  }

  private static String displayCodeOf(StorageProvider provider, Timetable timetable) {
    String company =
        provider.companies().findById(timetable.companyId()).map(Company::code).orElse("?");
    String operator =
        provider.operators().findById(timetable.operatorId()).map(Operator::code).orElse("?");
    String line = provider.lines().findById(timetable.lineId()).map(Line::code).orElse("?");
    return company + "/" + operator + "/" + line;
  }

  /** 「共用资源」一节：有表的邻表会参与检查（下一阶段），无表的只能报告——它们按 headway 发车，干扰单向。 */
  private static void sendNeighborReport(CommandSender sender, NeighborReport neighbors) {
    if (neighbors == null) {
      return;
    }
    if (neighbors.scheduled().isEmpty() && neighbors.unscheduled().isEmpty()) {
      sender.sendMessage(Component.text("  共用资源: 没有别的线路与本表共用区间、站台、单线或道岔", NamedTextColor.GREEN));
      return;
    }
    sender.sendMessage(Component.text("  共用资源:", NamedTextColor.GRAY));
    for (TimetableNeighborhoodLoader.FootprintNeighbor neighbor : neighbors.scheduled()) {
      sender.sendMessage(
          Component.text(
              "    "
                  + neighbor.displayCode()
                  + " 共用 "
                  + neighbor.sharedResources()
                  + " 个资源，已发布——本表未与它做联合排布，两张表各自乐观",
              NamedTextColor.YELLOW));
      for (String warning : neighbor.warnings()) {
        sender.sendMessage(Component.text("      · " + warning, NamedTextColor.DARK_GRAY));
      }
    }
    for (TimetableNeighborhoodLoader.UnscheduledNeighbor neighbor : neighbors.unscheduled()) {
      sender.sendMessage(
          Component.text(
              "    "
                  + neighbor.displayCode()
                  + " 共用 "
                  + neighbor.sharedResources()
                  + " 个资源，无已发布时刻表——它按 headway 发车，干扰单向，无法联合排布",
              NamedTextColor.YELLOW));
    }
  }

  private void sendBuildReport(
      CommandSender sender,
      TimetableBuildResult result,
      TimetableBuildOptions options,
      TimetableHeadwayDefaults.Choice headway) {
    sender.sendMessage(Component.text("===== 构建报告 =====", NamedTextColor.DARK_AQUA));
    if (!result.success()) {
      for (String warning : result.warnings()) {
        sender.sendMessage(Component.text("  ! " + warning, NamedTextColor.RED));
      }
      for (TimetableBuildResult.InfeasibleRoute route : result.infeasibleRoutes()) {
        sender.sendMessage(
            Component.text("  ! " + route.routeCode() + "：" + route.reason(), NamedTextColor.RED));
      }
      return;
    }
    Timetable timetable = result.timetable().orElseThrow();
    long operations = timetable.routePlans().stream().filter(TimetableRoutePlan::operation).count();
    long creates =
        timetable.routePlans().stream()
            .filter(plan -> plan.kind() == RouteOperationType.CREATE)
            .count();
    long returns =
        timetable.routePlans().stream()
            .filter(plan -> plan.kind() == RouteOperationType.RETURN)
            .count();
    sender.sendMessage(field("班次", String.valueOf(result.tripCount())));
    sender.sendMessage(
        field("route", "运营 " + operations + "，出库线路 " + creates + "，回库线路 " + returns));
    sender.sendMessage(
        field(
            "计划窗口",
            TimetableCsvExporter.clock(options.serviceStartSecondOfDay())
                + " → "
                + TimetableCsvExporter.clock(options.serviceEndSecondOfDay())
                + "，间隔 "
                + result.effectiveHeadwaySeconds()
                + "s"
                + (result.headwayRelaxed()
                    ? "（目标 " + result.targetHeadwaySeconds() + "s 有冲突，已放宽）"
                    : "")));
    sender.sendMessage(field("间隔来源", headway.description() + "：" + headway.seconds() + "s"));
    if (result.conflictsAtTarget().isEmpty()) {
      sender.sendMessage(
          Component.text(
              "  冲突检查: 无冲突（区间/站台/单线/道岔，间隔裕量 " + options.separation().toSeconds() + "s）",
              NamedTextColor.GREEN));
    } else {
      sender.sendMessage(
          Component.text(
              "  冲突检查: 目标间隔下 " + result.conflictsAtTarget().size() + " 处冲突，明细见下方警告",
              NamedTextColor.YELLOW));
    }
    sender.sendMessage(field("车辆交路", String.valueOf(result.dutyCount())));
    sender.sendMessage(
        field(
            "计划用车",
            "全天 " + result.plannedVehicles() + " 次出库，峰值同时在线 " + result.peakConcurrentVehicles()));
    if (!result.droppedTrips().isEmpty()) {
      sender.sendMessage(
          Component.text(
              "  取消班次: " + result.droppedTrips().size() + "（起终点缺出库/回库线路，详见下方警告）",
              NamedTextColor.YELLOW));
    }

    sender.sendMessage(Component.text("  目标服务比例 / 实际:", NamedTextColor.GRAY));
    for (WeightedTripAllocator.ShareReport share : result.shares()) {
      NamedTextColor color =
          share.deviationPercentPoints() > TimetableBuilder.SHARE_WARN_PERCENT_POINTS
              ? NamedTextColor.YELLOW
              : NamedTextColor.WHITE;
      sender.sendMessage(
          Component.text(
              String.format(
                  Locale.ROOT,
                  "    %s (w=%d)  目标 %.1f%%  实际 %.1f%%  班次 %d",
                  share.key(),
                  share.weight(),
                  share.targetShare() * 100.0D,
                  share.achievedShare() * 100.0D,
                  share.assignedTrips()),
              color));
    }

    sender.sendMessage(field("最长一趟车", result.longestTripSeconds() + "s"));
    sender.sendMessage(field("单交路最多班次", String.valueOf(result.maxTripsInAnyDuty())));
    sender.sendMessage(field("单交路最长在线", result.maxDutyDurationSeconds() + "s"));
    sender.sendMessage(
        Component.text(
            "  所有交路都以回库收尾: " + (result.allDutiesReturnToStorage() ? "是" : "否"),
            result.allDutiesReturnToStorage() ? NamedTextColor.GREEN : NamedTextColor.RED));
    for (String warning : result.warnings()) {
      sender.sendMessage(Component.text("  ! " + warning, NamedTextColor.YELLOW));
    }
  }

  private void handleList(CommandContext<CommandSender> ctx) {
    CommandSender sender = ctx.sender();
    Optional<StorageProvider> providerOpt = readyProvider(sender);
    if (providerOpt.isEmpty()) {
      return;
    }
    StorageProvider provider = providerOpt.get();
    ResolvedLine resolved = resolveLine(ctx, provider, false);
    if (resolved == null) {
      return;
    }
    List<Timetable> timetables = provider.timetables().listByLine(resolved.line().id());
    if (timetables.isEmpty()) {
      sender.sendMessage(Component.text("该线路下没有时刻表。", NamedTextColor.YELLOW));
      return;
    }
    sender.sendMessage(
        Component.text("===== 时刻表 " + resolved.line().code() + " =====", NamedTextColor.DARK_AQUA));
    for (Timetable timetable : timetables) {
      NamedTextColor color = timetable.published() ? NamedTextColor.GREEN : NamedTextColor.WHITE;
      sender.sendMessage(
          Component.text(
              "  "
                  + timetable.code()
                  + "  "
                  + timetable.status().name()
                  + "  班次="
                  + timetable.trips().size()
                  + "  route="
                  + timetable.routePlans().size()
                  + "  交路="
                  + timetable.duties().size(),
              color));
    }
  }

  private void handleInfo(CommandContext<CommandSender> ctx) {
    CommandSender sender = ctx.sender();
    Optional<StorageProvider> providerOpt = readyProvider(sender);
    if (providerOpt.isEmpty()) {
      return;
    }
    ResolvedTimetable resolved = resolveTimetable(ctx, providerOpt.get(), false);
    if (resolved == null) {
      return;
    }
    Timetable timetable = resolved.timetable();
    sender.sendMessage(
        Component.text("===== " + timetable.code() + " =====", NamedTextColor.DARK_AQUA));
    sender.sendMessage(field("名称", timetable.name()));
    sender.sendMessage(field("状态", timetable.status().name()));
    sender.sendMessage(field("时区", timetable.zoneId().getId()));
    sender.sendMessage(
        field(
            "计划窗口",
            TimetableCsvExporter.clock(timetable.serviceStartSecondOfDay())
                + " → "
                + TimetableCsvExporter.clock(timetable.serviceEndSecondOfDay())));
    sender.sendMessage(Component.text("  各 route 计划:", NamedTextColor.GRAY));
    for (TimetableRoutePlan plan : timetable.routePlans()) {
      long tripCount =
          timetable.trips().stream().filter(trip -> trip.routeId().equals(plan.routeId())).count();
      sender.sendMessage(
          Component.text(
              "    "
                  + plan.routeCode()
                  + (plan.operation() ? "  w=" + plan.weight() : "  [" + plan.kind().name() + "]")
                  + "  停靠="
                  + plan.stops().size()
                  + "  全程="
                  + plan.totalRunSeconds()
                  + "s  班次="
                  + tripCount,
              NamedTextColor.WHITE));
      for (TimetableStop stop : plan.stops()) {
        sender.sendMessage(
            Component.text(
                "      #"
                    + stop.stopSequence()
                    + "  "
                    + stop.stationCode().orElse(stop.nodeId().orElse("-"))
                    + "  到+"
                    + stop.arrivalOffsetSeconds()
                    + "s  开+"
                    + stop.departureOffsetSeconds()
                    + "s",
                NamedTextColor.GRAY));
      }
    }
    int page = ctx.<Integer>optional("page").orElse(1);
    List<TimetableTrip> trips = timetable.trips();
    int pages = Math.max(1, (trips.size() + PAGE_SIZE - 1) / PAGE_SIZE);
    int current = Math.min(page, pages);
    sender.sendMessage(
        Component.text(
            "  发车表（第 " + current + "/" + pages + " 页，共 " + trips.size() + " 趟）:",
            NamedTextColor.GRAY));
    int from = (current - 1) * PAGE_SIZE;
    int to = Math.min(trips.size(), from + PAGE_SIZE);
    for (int i = from; i < to; i++) {
      TimetableTrip trip = trips.get(i);
      String routeCode =
          timetable.routePlan(trip.routeId()).map(TimetableRoutePlan::routeCode).orElse("?");
      String dutyCode =
          trip.dutyId().flatMap(timetable::duty).map(VehicleDuty::dutyCode).orElse("-");
      sender.sendMessage(
          Component.text(
              "    "
                  + trip.tripCode()
                  + "  "
                  + trip.departureText()
                  + "  route="
                  + routeCode
                  + "  交路="
                  + dutyCode,
              NamedTextColor.WHITE));
    }
  }

  private void handleDuties(CommandContext<CommandSender> ctx) {
    CommandSender sender = ctx.sender();
    Optional<StorageProvider> providerOpt = readyProvider(sender);
    if (providerOpt.isEmpty()) {
      return;
    }
    ResolvedTimetable resolved = resolveTimetable(ctx, providerOpt.get(), false);
    if (resolved == null) {
      return;
    }
    Timetable timetable = resolved.timetable();
    List<VehicleDuty> duties = timetable.duties();
    if (duties.isEmpty()) {
      sender.sendMessage(Component.text("这份时刻表没有车辆交路。", NamedTextColor.YELLOW));
      return;
    }
    int page = ctx.<Integer>optional("page").orElse(1);
    int pages = Math.max(1, (duties.size() + PAGE_SIZE - 1) / PAGE_SIZE);
    int current = Math.min(page, pages);
    sender.sendMessage(
        Component.text(
            "===== 车辆交路 "
                + timetable.code()
                + "（第 "
                + current
                + "/"
                + pages
                + " 页，共 "
                + duties.size()
                + " 个）=====",
            NamedTextColor.DARK_AQUA));
    int from = (current - 1) * PAGE_SIZE;
    int to = Math.min(duties.size(), from + PAGE_SIZE);
    for (int i = from; i < to; i++) {
      VehicleDuty duty = duties.get(i);
      sender.sendMessage(
          Component.text(
              "  "
                  + duty.dutyCode()
                  + "  "
                  + TimetableCsvExporter.clock(duty.plannedStartSecondOfDay())
                  + " → "
                  + TimetableCsvExporter.clock(duty.plannedEndSecondOfDay())
                  + "  班次="
                  + duty.tripCount()
                  + "  在线="
                  + duty.plannedDurationSeconds()
                  + "s  收尾="
                  + duty.closeReason().name(),
              NamedTextColor.WHITE));
      String createLeg =
          duty.createRouteId()
              .flatMap(timetable::routePlan)
              .map(plan -> "经 " + plan.routeCode() + " ")
              .orElse("首班自车库始发 ");
      String returnLeg =
          duty.returnRouteId()
              .flatMap(timetable::routePlan)
              .map(
                  plan ->
                      "经 "
                          + plan.routeCode()
                          + " "
                          + TimetableCsvExporter.clock(duty.returnSecondOfDay())
                          + " 发 ")
              .orElse("末班以销毁收尾 ");
      sender.sendMessage(
          Component.text(
              "      出库 "
                  + duty.startDepotNodeId()
                  + " "
                  + createLeg
                  + "→ 回库 "
                  + duty.endDepotNodeId()
                  + " "
                  + returnLeg,
              NamedTextColor.GRAY));
    }
  }

  private void handleStatusChange(CommandContext<CommandSender> ctx, TimetableStatus next) {
    CommandSender sender = ctx.sender();
    Optional<StorageProvider> providerOpt = readyProvider(sender);
    if (providerOpt.isEmpty()) {
      return;
    }
    StorageProvider provider = providerOpt.get();
    ResolvedTimetable resolved = resolveTimetable(ctx, provider, true);
    if (resolved == null) {
      return;
    }
    Timetable timetable = resolved.timetable();
    if (next == TimetableStatus.PUBLISHED && timetable.trips().isEmpty()) {
      sender.sendMessage(Component.text("这份时刻表一趟车都没有，publish 之后这条线会发不出车。", NamedTextColor.RED));
      return;
    }
    provider.timetables().save(timetable.withStatus(next, Instant.now()));
    plugin.getTimetableService().ifPresent(service -> service.reload(provider));
    sender.sendMessage(
        Component.text(
            timetable.code() + " → " + next.name(),
            next == TimetableStatus.PUBLISHED ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
    if (next == TimetableStatus.PUBLISHED) {
      boolean enabled =
          plugin.getTimetableService().map(service -> service.settings().enabled()).orElse(false);
      if (!enabled) {
        sender.sendMessage(
            Component.text(
                "注意：config.yml 的 timetable.enabled 仍为 false，这份表暂时不会影响任何列车。",
                NamedTextColor.YELLOW));
      }
    }
  }

  private void handleDelete(CommandContext<CommandSender> ctx, boolean confirmed) {
    CommandSender sender = ctx.sender();
    Optional<StorageProvider> providerOpt = readyProvider(sender);
    if (providerOpt.isEmpty()) {
      return;
    }
    StorageProvider provider = providerOpt.get();
    ResolvedTimetable resolved = resolveTimetable(ctx, provider, true);
    if (resolved == null) {
      return;
    }
    if (!confirmed) {
      sender.sendMessage(locale().component("command.common.confirm-required"));
      return;
    }
    provider.timetables().delete(resolved.timetable().id());
    plugin.getTimetableService().ifPresent(service -> service.reload(provider));
    sender.sendMessage(
        Component.text("已删除时刻表 " + resolved.timetable().code(), NamedTextColor.DARK_AQUA));
  }

  private void handleExport(CommandContext<CommandSender> ctx) {
    CommandSender sender = ctx.sender();
    Optional<StorageProvider> providerOpt = readyProvider(sender);
    if (providerOpt.isEmpty()) {
      return;
    }
    ResolvedTimetable resolved = resolveTimetable(ctx, providerOpt.get(), false);
    if (resolved == null) {
      return;
    }
    int limit = ctx.<Integer>optional("limit").orElse(40);
    List<String> lines =
        TimetableCsvExporter.export(resolved.timetable()).lines().limit(limit + 1L).toList();
    for (String line : lines) {
      sender.sendMessage(Component.text(line, NamedTextColor.GRAY));
    }
    sender.sendMessage(Component.text("（仅显示前 " + limit + " 行，完整数据请查库）", NamedTextColor.DARK_GRAY));
  }

  private void handleRuntimeStatus(CommandSender sender) {
    Optional<TimetableService> serviceOpt = plugin.getTimetableService();
    if (serviceOpt.isEmpty()) {
      sender.sendMessage(Component.text("按表运行服务尚未初始化。", NamedTextColor.RED));
      return;
    }
    TimetableService.StatusSnapshot status = serviceOpt.get().status();
    sender.sendMessage(Component.text("===== 按表运行 =====", NamedTextColor.DARK_AQUA));
    sender.sendMessage(field("总开关", status.enabled() ? "开" : "关"));
    sender.sendMessage(field("时刻表驱动发车", status.spawnEnabled() ? "开" : "关"));
    sender.sendMessage(field("已发布时刻表", String.valueOf(status.publishedTimetables())));
    sender.sendMessage(field("受管辖 route", String.valueOf(status.managedRoutes())));
    sender.sendMessage(field("绑定失败累计", status.assignMisses() + "（详情看 TIMETABLE_ASSIGN_MISS 日志）"));
    if (status.deviations().isEmpty()) {
      sender.sendMessage(Component.text("  当前没有列车绑定到表定车次。", NamedTextColor.GRAY));
      return;
    }
    sender.sendMessage(Component.text("  绑定中的列车:", NamedTextColor.GRAY));
    for (TimetableService.DeviationEntry entry : status.deviations()) {
      long deviation = entry.initialDeviationSeconds();
      NamedTextColor color =
          deviation > 60
              ? NamedTextColor.RED
              : deviation < -60 ? NamedTextColor.AQUA : NamedTextColor.WHITE;
      sender.sendMessage(
          Component.text(
              "    "
                  + entry.trainName()
                  + "  车次="
                  + entry.tripCode()
                  + " ("
                  + entry.plannedDeparture()
                  + ")  交路="
                  + entry.duty()
                  + "  绑定时偏差="
                  + (deviation >= 0 ? "+" : "")
                  + deviation
                  + "s",
              color));
    }
  }

  // ---------------------------------------------------------------- 构建辅助

  /**
   * 构建行程时间模型。
   *
   * <p>复用 ETA 模块的 {@code DynamicTravelTimeModel}：它按<b>每条边的实际限速</b>加减速积分， 因此一条穿越多个限速区间的 route
   * 不会被一个全线平均速度抹平。不另起一套平行模型，是为了让 "表定时分"和"运行时 ETA"永远出自同一套算法。
   */
  private RailTravelTimeModel travelTimeModel() {
    double fallback = FALLBACK_SPEED_BPS;
    if (plugin.getConfigManager() != null && plugin.getConfigManager().current() != null) {
      double configured =
          plugin.getConfigManager().current().graphSettings().defaultSpeedBlocksPerSecond();
      if (Double.isFinite(configured) && configured > 0.0D) {
        fallback = configured;
      }
    }
    return new DynamicTravelTimeModel(
        DynamicTravelTimeModel.TrainMotionParams.defaults(), fallback);
  }

  /** 找到覆盖这条交路全部节点的调度图快照。 */
  private Optional<RailGraph> resolveGraph(RouteDefinition definition) {
    List<NodeId> waypoints = definition.waypoints();
    return plugin
        .getRailGraphService()
        .findWorldIdForPath(waypoints)
        .flatMap(worldId -> plugin.getRailGraphService().getSnapshot(worldId))
        .map(snapshot -> snapshot.graph());
  }

  /** 读取 route 的目标服务比例权重；未配置时按 1 处理。 */
  private static int readWeight(Route route) {
    Object raw = route.metadata() == null ? null : route.metadata().get("spawn_weight");
    if (raw instanceof Number number) {
      return Math.max(0, number.intValue());
    }
    if (raw instanceof String text) {
      try {
        return Math.max(0, Integer.parseInt(text.trim()));
      } catch (NumberFormatException ignored) {
        return 1;
      }
    }
    return 1;
  }

  /** 解析 {@code HH:mm} 为当日秒数。 */
  private static Optional<Integer> parseClock(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    String[] parts = raw.trim().split(":");
    if (parts.length < 2) {
      return Optional.empty();
    }
    try {
      int hour = Integer.parseInt(parts[0].trim());
      int minute = Integer.parseInt(parts[1].trim());
      if (hour < 0 || hour > 47 || minute < 0 || minute > 59) {
        return Optional.empty();
      }
      return Optional.of(hour * 3600 + minute * 60);
    } catch (NumberFormatException ignored) {
      return Optional.empty();
    }
  }

  // ------------------------------------------------------------- resolution

  private ResolvedLine resolveLine(
      CommandContext<CommandSender> ctx, StorageProvider provider, boolean requireManage) {
    CommandSender sender = ctx.sender();
    LocaleManager locale = locale();
    CompanyQueryService query = new CompanyQueryService(provider);
    String companyArg = ((String) ctx.get("company")).trim();
    Optional<Company> companyOpt = query.findCompany(companyArg);
    if (companyOpt.isEmpty()) {
      sender.sendMessage(
          locale.component("command.company.info.not-found", Map.of("company", companyArg)));
      return null;
    }
    Company company = companyOpt.get();
    boolean allowed =
        requireManage
            ? canManageCompany(sender, provider, company.id())
            : canReadCompany(sender, provider, company.id());
    if (!allowed) {
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
    String lineArg = ((String) ctx.get("line")).trim();
    Optional<Line> lineOpt = query.findLine(operatorOpt.get().id(), lineArg);
    if (lineOpt.isEmpty()) {
      sender.sendMessage(locale.component("command.line.not-found", Map.of("line", lineArg)));
      return null;
    }
    return new ResolvedLine(company, operatorOpt.get(), lineOpt.get());
  }

  private ResolvedTimetable resolveTimetable(
      CommandContext<CommandSender> ctx, StorageProvider provider, boolean requireManage) {
    ResolvedLine line = resolveLine(ctx, provider, requireManage);
    if (line == null) {
      return null;
    }
    String code = ((String) ctx.get("code")).trim();
    Optional<Timetable> timetableOpt =
        provider.timetables().findByLineAndCode(line.line().id(), code);
    if (timetableOpt.isEmpty()) {
      ctx.sender().sendMessage(Component.text("未找到时刻表：" + code, NamedTextColor.RED));
      return null;
    }
    return new ResolvedTimetable(line.company(), line.operator(), line.line(), timetableOpt.get());
  }

  // ------------------------------------------------------------ suggestions

  private SuggestionProvider<CommandSender> companySuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = normalizePrefix(input);
          List<String> out = new ArrayList<>();
          if (prefix.isBlank()) {
            out.add("<company>");
          }
          providerIfReady()
              .ifPresent(
                  provider ->
                      provider.companies().listAll().stream()
                          .filter(
                              company ->
                                  canReadCompanyNoCreateIdentity(
                                      ctx.sender(), provider, company.id()))
                          .map(Company::code)
                          .filter(code -> matches(code, prefix))
                          .limit(SUGGESTION_LIMIT)
                          .forEach(out::add));
          return out;
        });
  }

  private SuggestionProvider<CommandSender> operatorSuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = normalizePrefix(input);
          List<String> out = new ArrayList<>();
          if (prefix.isBlank()) {
            out.add("<operator>");
          }
          resolveCompanyForSuggestion(ctx)
              .ifPresent(
                  pair ->
                      pair.provider().operators().listByCompany(pair.company().id()).stream()
                          .map(Operator::code)
                          .filter(code -> matches(code, prefix))
                          .limit(SUGGESTION_LIMIT)
                          .forEach(out::add));
          return out;
        });
  }

  private SuggestionProvider<CommandSender> lineSuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = normalizePrefix(input);
          List<String> out = new ArrayList<>();
          if (prefix.isBlank()) {
            out.add("<line>");
          }
          resolveOperatorForSuggestion(ctx)
              .ifPresent(
                  pair ->
                      pair.provider().lines().listByOperator(pair.operator().id()).stream()
                          .map(Line::code)
                          .filter(code -> matches(code, prefix))
                          .limit(SUGGESTION_LIMIT)
                          .forEach(out::add));
          return out;
        });
  }

  private SuggestionProvider<CommandSender> timetableCodeSuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = normalizePrefix(input);
          List<String> out = new ArrayList<>();
          if (prefix.isBlank()) {
            out.add("<code>");
          }
          resolveLineForSuggestion(ctx)
              .ifPresent(
                  pair ->
                      pair.provider().timetables().listByLine(pair.line().id()).stream()
                          .map(Timetable::code)
                          .filter(code -> matches(code, prefix))
                          .limit(SUGGESTION_LIMIT)
                          .forEach(out::add));
          return out;
        });
  }

  private Optional<CompanyPair> resolveCompanyForSuggestion(CommandContext<CommandSender> ctx) {
    Optional<StorageProvider> providerOpt = providerIfReady();
    Optional<String> companyArg = ctx.optional("company").map(String.class::cast).map(String::trim);
    if (providerOpt.isEmpty() || companyArg.isEmpty() || companyArg.get().isBlank()) {
      return Optional.empty();
    }
    StorageProvider provider = providerOpt.get();
    return new CompanyQueryService(provider)
        .findCompany(companyArg.get())
        .filter(company -> canReadCompanyNoCreateIdentity(ctx.sender(), provider, company.id()))
        .map(company -> new CompanyPair(provider, company));
  }

  private Optional<OperatorPair> resolveOperatorForSuggestion(CommandContext<CommandSender> ctx) {
    Optional<String> operatorArg =
        ctx.optional("operator").map(String.class::cast).map(String::trim);
    if (operatorArg.isEmpty() || operatorArg.get().isBlank()) {
      return Optional.empty();
    }
    return resolveCompanyForSuggestion(ctx)
        .flatMap(
            pair ->
                new CompanyQueryService(pair.provider())
                    .findOperator(pair.company().id(), operatorArg.get())
                    .map(operator -> new OperatorPair(pair.provider(), operator)));
  }

  private Optional<LinePair> resolveLineForSuggestion(CommandContext<CommandSender> ctx) {
    Optional<String> lineArg = ctx.optional("line").map(String.class::cast).map(String::trim);
    if (lineArg.isEmpty() || lineArg.get().isBlank()) {
      return Optional.empty();
    }
    return resolveOperatorForSuggestion(ctx)
        .flatMap(
            pair ->
                new CompanyQueryService(pair.provider())
                    .findLine(pair.operator().id(), lineArg.get())
                    .map(line -> new LinePair(pair.provider(), line)));
  }

  // ----------------------------------------------------------------- helpers

  private static CommandFlag<String> stringFlag(String name, String placeholder) {
    return CommandFlag.<CommandSender>builder(name)
        .withComponent(
            CommandComponent.<CommandSender, String>builder(name, StringParser.quotedStringParser())
                .suggestionProvider(CommandSuggestionProviders.placeholder(placeholder)))
        .build();
  }

  private static CommandFlag<Integer> intFlag(String name, String placeholder, int min, int max) {
    return CommandFlag.<CommandSender>builder(name)
        .withComponent(
            CommandComponent.<CommandSender, Integer>builder(
                    name, IntegerParser.integerParser(min, max))
                .suggestionProvider(CommandSuggestionProviders.placeholder(placeholder)))
        .build();
  }

  /**
   * 读取整数 flag 并落地成 {@code int}。
   *
   * <p>{@code flags().getValue(flag, default)} 的返回类型是 {@code Integer}，赋给 {@code int} 时会隐式拆箱； 缺省值本身为
   * null 的那条路径就成了一次 NPE。统一走 {@link Optional}，缺省值只有一处来源。
   */
  private static int intValue(
      CommandContext<CommandSender> ctx, CommandFlag<Integer> flag, int fallback) {
    return ctx.flags().getValue(flag).orElse(fallback);
  }

  /** 时区解析失败返回 {@code null}，由调用方给出字段级报错；不静默回退，那会让整张表整体平移。 */
  private static ZoneId resolveZone(String raw) {
    if (raw == null || raw.isBlank()) {
      return ZoneId.systemDefault();
    }
    try {
      return ZoneId.of(raw.trim());
    } catch (java.time.DateTimeException ignored) {
      return null;
    }
  }

  private static boolean matches(String value, String prefix) {
    return value != null && (prefix.isBlank() || value.toLowerCase(Locale.ROOT).startsWith(prefix));
  }

  private static String normalizePrefix(CommandInput input) {
    return input == null ? "" : input.lastRemainingToken().trim().toLowerCase(Locale.ROOT);
  }

  private static Component field(String key, String value) {
    return Component.text("  " + key + ": ", NamedTextColor.GRAY)
        .append(Component.text(value, NamedTextColor.WHITE));
  }

  private static Component hint(String label, String command) {
    return Component.text("  " + label + " ", NamedTextColor.GRAY)
        .append(Component.text(command, NamedTextColor.WHITE))
        .clickEvent(ClickEvent.suggestCommand(command + " "));
  }

  private LocaleManager locale() {
    return plugin.getLocaleManager();
  }

  private Optional<StorageProvider> readyProvider(CommandSender sender) {
    return CommandStorageProviders.readyProvider(sender, plugin);
  }

  private Optional<StorageProvider> providerIfReady() {
    return CommandStorageProviders.providerIfReady(plugin);
  }

  private record ResolvedLine(Company company, Operator operator, Line line) {}

  private record ResolvedTimetable(
      Company company, Operator operator, Line line, Timetable timetable) {}

  private record CompanyPair(StorageProvider provider, Company company) {}

  private record OperatorPair(StorageProvider provider, Operator operator) {}

  private record LinePair(StorageProvider provider, Line line) {}

  /**
   * {@code build} 的参数集合。
   *
   * @param headwaySeconds 全线基准发车间隔；未显式给出时为 {@code null}，由线路 baseline 决定
   * @param start 首班时刻 {@code HH:mm}
   * @param end 末班时刻 {@code HH:mm}
   * @param dwellSeconds 缺省停站时长
   * @param maxTripsPerDuty 单个车辆交路最多班次
   * @param maxDutyMinutes 单个车辆交路最长在线分钟
   * @param turnaroundSeconds 终端折返时间
   * @param separationSeconds 冲突检查里相邻占用之间的最小间隔
   * @param strict 目标 headway 有冲突时构建失败而不是回退
   * @param name 时刻表展示名
   * @param tripCodePrefix 车次号前缀
   * @param zone 时区
   */
  private record BuildFlags(
      Integer headwaySeconds,
      String start,
      String end,
      int dwellSeconds,
      int maxTripsPerDuty,
      int maxDutyMinutes,
      int turnaroundSeconds,
      int separationSeconds,
      boolean strict,
      String name,
      String tripCodePrefix,
      String zone) {}
}
