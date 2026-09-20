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
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.ApproachingConfig;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.DynamicTravelTimeModel;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailTravelTimeModel;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.LineSpawnMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnGroup;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnPlan;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.ServiceGroupClassifier;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.Timetable;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableBuildOptions;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableBuildReportText;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableBuildResult;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableBuilder;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableConflictChecker;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableCsvExporter;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableEdgeSpeeds;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableHeadwayDefaults;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableOccupancyProjector;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableRouteMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableRoutePlan;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableSetBuilder;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableStatus;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableStop;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableTimingCalculator;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableTrip;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TurnaroundTable;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.VehicleDuty;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.VehicleDutyPlanner;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.WeightedTripAllocator;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.scope.NeighborTimetable;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.scope.TimetableBaseline;
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

  /** 报告里最多列出几条让车明细。 */
  private static final int YIELD_DETAIL_LIMIT = 5;

  /** 「不设闲置上限」：一整天，计划窗口内等价于永不回收。回收关着时用它。 */
  private static final int NO_IDLE_LIMIT_SECONDS = 86_400;

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
    var groupHeadwayFlag =
        CommandFlag.<CommandSender>builder("group-headway")
            .withComponent(
                CommandComponent.<CommandSender, String>builder(
                        "group-headway", StringParser.quotedStringParser())
                    .suggestionProvider(groupHeadwaySuggestions()))
            .asRepeatable()
            .build();
    var maxWaitFlag = intFlag("max-wait", "<seconds>", 0, 1800);
    var maxIdleFlag = intFlag("max-idle", "<seconds>", 30, 86400);
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
            .flag(groupHeadwayFlag)
            .flag(startFlag)
            .flag(endFlag)
            .flag(dwellFlag)
            .flag(maxTripsFlag)
            .flag(maxDutyFlag)
            .flag(turnaroundFlag)
            .flag(separationFlag)
            .flag(maxWaitFlag)
            .flag(maxIdleFlag)
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
                            new ArrayList<>(ctx.flags().getAll(groupHeadwayFlag)),
                            ctx.flags().getValue(startFlag).orElse(null),
                            ctx.flags().getValue(endFlag).orElse(null),
                            intValue(ctx, dwellFlag, TimetableBuildOptions.DEFAULT_DWELL_SECONDS),
                            intValue(
                                ctx, maxTripsFlag, VehicleDutyPlanner.Limits.DEFAULT_MAX_TRIPS),
                            intValue(
                                ctx,
                                maxDutyFlag,
                                VehicleDutyPlanner.Limits.DEFAULT_MAX_DURATION_SECONDS / 60),
                            ctx.flags().getValue(turnaroundFlag).orElse(null),
                            intValue(
                                ctx,
                                separationFlag,
                                TimetableBuildOptions.DEFAULT_SEPARATION_SECONDS),
                            ctx.flags().getValue(maxWaitFlag).orElse(null),
                            ctx.flags().getValue(maxIdleFlag).orElse(null),
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
            .literal("neighbors")
            .permission("fetarute.timetable")
            .required("company", StringParser.quotedStringParser(), companySuggestions)
            .required("operator", StringParser.quotedStringParser(), operatorSuggestions)
            .required("line", StringParser.quotedStringParser(), lineSuggestions)
            .required("code", StringParser.quotedStringParser(), codeSuggestions)
            .handler(this::handleNeighbors));

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
    sender.sendMessage(
        hint("编表", "/fta timetable build <company> <operator> <line>[,<line>…] <code>"));
    sender.sendMessage(
        Component.text(
            "    可选: --headway --group-headway <组>=<秒>（可重复） --start --end --dwell --max-trips"
                + " --max-duty-minutes --turnaround --separation --max-wait --max-idle --strict --name --prefix --zone",
            NamedTextColor.DARK_GRAY));
    sender.sendMessage(hint("列表", "/fta timetable list <company> <operator> <line>"));
    sender.sendMessage(hint("详情", "/fta timetable info <company> <operator> <line> <code>"));
    sender.sendMessage(hint("车辆交路", "/fta timetable duties <company> <operator> <line> <code>"));
    sender.sendMessage(hint("邻表", "/fta timetable neighbors <company> <operator> <line> <code>"));
    sender.sendMessage(
        hint("投入运行", "/fta timetable publish <company> <operator> <line>[,<line>…] <code>"));
    sender.sendMessage(
        hint("撤出运行", "/fta timetable unpublish <company> <operator> <line>[,<line>…] <code>"));
    sender.sendMessage(hint("导出 CSV", "/fta timetable export <company> <operator> <line> <code>"));
    sender.sendMessage(hint("运行态", "/fta timetable status"));
    sender.sendMessage(Component.text("时刻表由 FTCA 按路网算出，不需要先去实服录制。", NamedTextColor.GRAY));
    sender.sendMessage(
        Component.text("同一 operator 下几条线写成 WS,MT 可以一起编表：共享相位与让车，每线一张表、整组发布。", NamedTextColor.GRAY));
  }

  private void handleBuild(CommandContext<CommandSender> ctx, BuildFlags flags) {
    CommandSender sender = ctx.sender();
    Optional<StorageProvider> providerOpt = readyProvider(sender);
    if (providerOpt.isEmpty()) {
      return;
    }
    StorageProvider provider = providerOpt.get();
    List<ResolvedLine> lines = resolveLines(ctx, provider, true);
    if (lines == null) {
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

    // 每条线各自收集 route（含本 operator 的走行线路）；几条线一起编时它们必须在同一个世界。
    boolean joint = lines.size() > 1;
    List<LineRoutes> members = new ArrayList<>();
    WorldGraph graph = null;
    for (ResolvedLine resolved : lines) {
      Optional<Timetable> existing =
          provider.timetables().findByLineAndCode(resolved.line().id(), code);
      if (existing.filter(Timetable::published).isPresent()) {
        sender.sendMessage(
            Component.text(
                resolved.line().code() + "/" + code + " 正在运行中，请先 unpublish 再重新 build。",
                NamedTextColor.RED));
        return;
      }
      LineRoutes member =
          collectRouteInputs(sender, provider, resolved, existing.map(Timetable::id));
      if (member == null) {
        return;
      }
      if (graph == null) {
        graph = member.graph();
      } else if (member.graph() != null && !member.graph().worldId().equals(graph.worldId())) {
        sender.sendMessage(
            Component.text(
                "联编的几条线必须在同一个世界：" + resolved.line().code() + " 不在。", NamedTextColor.RED));
        return;
      }
      members.add(member);
    }
    if (graph == null) {
      sender.sendMessage(
          Component.text("找不到覆盖这些线路的调度图快照，请先 /fta graph build。", NamedTextColor.RED));
      return;
    }

    // 每个交路组一个间隔：--group-headway 点名的 > --headway（全部组）> 组 baseline > 线路 baseline > 默认。
    // 多线联编时组键带线前缀（WS/full），--group-headway 既接受 full=150（凡有 full 组的线都用）也接受 WS/full=150。
    Map<String, Integer> explicitByGroup = parseGroupHeadways(sender, flags.groupHeadways());
    if (explicitByGroup == null) {
      return;
    }
    Map<String, Integer> groupIntervals = new java.util.TreeMap<>();
    Map<String, String> groupSources = new java.util.TreeMap<>();
    TimetableHeadwayDefaults.Choice headway = null;
    for (LineRoutes member : members) {
      Line line = member.line().line();
      TimetableHeadwayDefaults.Choice lineChoice =
          TimetableHeadwayDefaults.resolve(
              Optional.ofNullable(flags.headwaySeconds()),
              line.spawnFreqBaselineSec(),
              LineSpawnMetadata.parseGroups(line.metadata()));
      if (headway == null) {
        headway = lineChoice;
      }
      List<String> groupNames =
          ServiceGroupClassifier.classify(member.inputs()).groups().stream()
              .map(ServiceGroupClassifier.Group::name)
              .toList();
      Map<String, Integer> explicitForLine = new java.util.TreeMap<>();
      explicitByGroup.forEach(
          (key, seconds) -> {
            int slash = key.indexOf('/');
            if (slash < 0) {
              explicitForLine.put(key, seconds);
            } else if (key.substring(0, slash).equalsIgnoreCase(line.code())) {
              explicitForLine.put(key.substring(slash + 1), seconds);
            }
          });
      Map<String, TimetableHeadwayDefaults.Choice> groupChoices =
          TimetableHeadwayDefaults.resolveGroups(
              Optional.ofNullable(flags.headwaySeconds()),
              explicitForLine,
              line.spawnFreqBaselineSec(),
              LineSpawnMetadata.parseGroups(line.metadata()),
              groupNames);
      groupChoices.forEach(
          (group, choice) -> {
            String key = TimetableSetBuilder.groupKey(line.code(), Optional.of(group), joint);
            groupIntervals.put(key, choice.seconds());
            groupSources.put(key, choice.description());
          });
    }
    TimetableBuildOptions options =
        new TimetableBuildOptions(
            serviceStart,
            serviceEnd,
            Duration.ofSeconds(headway.seconds()),
            Duration.ofSeconds(flags.dwellSeconds()),
            new VehicleDutyPlanner.Limits(
                flags.maxTripsPerDuty(),
                flags.maxDutyMinutes() * 60,
                // 不传 --turnaround 就不存在全线折返数：builder 按各 route 终到站的 dwell 建表。
                flags.turnaroundSeconds() == null
                    ? TurnaroundTable.none()
                    : TurnaroundTable.fixed(flags.turnaroundSeconds()),
                resolveMaxIdleSeconds(flags.maxIdleSeconds())),
            flags.tripCodePrefix() == null ? "" : flags.tripCodePrefix(),
            zone,
            Duration.ofSeconds(flags.separationSeconds()),
            flags.strict(),
            groupIntervals,
            repairOptions(sender, flags.maxWaitSeconds()));
    String timetableName = flags.name() == null ? code : flags.name();

    // 邻表输入在主线程读库（已发布表、无表线路的 route 与停靠），足迹计算、邻表投影与 build 一起进异步线程。
    // 一起编的几条线互相不算邻表——它们在同一次相位选择与修复里。
    Map<UUID, List<RouteStop>> myStops = new java.util.HashMap<>();
    Map<UUID, RouteDefinition> myDefinitions = new java.util.HashMap<>();
    List<TimetableNeighborhoodLoader.RouteCandidate> myRoutes = new ArrayList<>();
    List<UUID> lineIds = new ArrayList<>();
    List<TimetableSetBuilder.Member> setMembers = new ArrayList<>();
    RailGraph graphSnapshot = graph.graph();
    RailTravelTimeModel model = travelTimeModel(graph.worldId());
    for (LineRoutes member : members) {
      String display = displayCodeOf(member.line());
      lineIds.add(member.line().line().id());
      for (TimetableBuilder.RouteInput route : member.inputs()) {
        myStops.put(route.routeId(), route.stops());
        myDefinitions.put(route.routeId(), route.definition());
        if (member.ownRouteIds().contains(route.routeId())
            || myRoutes.stream().noneMatch(c -> c.routeId().equals(route.routeId()))) {
          myRoutes.add(
              new TimetableNeighborhoodLoader.RouteCandidate(
                  route.routeId(), route.routeCode(), display));
        }
      }
      setMembers.add(
          new TimetableSetBuilder.Member(
              new TimetableBuilder.BuildInput(
                  member.timetableId(),
                  member.line().company().id(),
                  member.line().operator().id(),
                  member.line().line().id(),
                  code,
                  timetableName,
                  member.inputs(),
                  graphSnapshot,
                  model,
                  Optional.empty()),
              member.line().line().code(),
              display,
              member.ownRouteIds()));
    }
    UUID footprintId =
        joint ? TimetableSetBuilder.jointTimetableId(code, lineIds) : members.get(0).timetableId();
    NeighborInputs neighborInputs =
        collectNeighborInputs(provider, lines, myRoutes, myStops, myDefinitions, model);

    // 构建是纯 CPU 运算：时分积分、SWRR、派车、串行、让车、冲突扫描，目标间隔有真冲突时还要向上搜索几十次。
    // 输入全是不可变快照，放到异步线程跑，报告与落库回到主线程。
    sender.sendMessage(
        Component.text(
            joint ? "正在按路网联编 " + lines.size() + " 条线…" : "正在按路网编表…", NamedTextColor.GRAY));
    Instant builtAt = Instant.now();
    TimetableHeadwayDefaults.Choice headwayChoice = headway;
    plugin
        .getServer()
        .getScheduler()
        .runTaskAsynchronously(
            plugin,
            () -> {
              TimetableSetBuilder.SetResult result;
              NeighborReport neighborReport;
              try {
                TimetableConflictChecker.GraphIndex index =
                    TimetableConflictChecker.GraphIndex.of(graphSnapshot);
                TimetableFootprint footprint =
                    neighborInputs.footprint(graphSnapshot, index, footprintId);
                neighborReport = neighborInputs.report(graphSnapshot, index, footprint);
                List<NeighborTimetable> neighbors =
                    neighborInputs.project(graphSnapshot, index, footprint, options);
                result =
                    new TimetableSetBuilder()
                        .build(
                            new TimetableSetBuilder.SetInput(setMembers, neighbors),
                            options,
                            builtAt);
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
              TimetableSetBuilder.SetResult built = result;
              NeighborReport neighbors = neighborReport;
              plugin
                  .getServer()
                  .getScheduler()
                  .runTask(
                      plugin,
                      () ->
                          finishBuild(
                              sender,
                              provider,
                              lines,
                              built,
                              options,
                              headwayChoice,
                              neighbors,
                              groupSources));
            });
  }

  /**
   * 一条线在 build 里的输入。
   *
   * @param line 线路
   * @param inputs 参与的 route（含本 operator 的走行线路与显式指定的外方线路）
   * @param ownRouteIds 属于这条线的 route（车池与拆表归属）
   * @param graph 覆盖这条线的图快照
   * @param timetableId 这条线这份表的 id（已有草稿则沿用）
   */
  private record LineRoutes(
      ResolvedLine line,
      List<TimetableBuilder.RouteInput> inputs,
      java.util.Set<UUID> ownRouteIds,
      WorldGraph graph,
      UUID timetableId) {}

  /** 收集一条线参与 build 的 route；没有可编表的班次或缺定义时提示并返回 null。 */
  private LineRoutes collectRouteInputs(
      CommandSender sender,
      StorageProvider provider,
      ResolvedLine resolved,
      Optional<UUID> existingId) {
    List<TimetableBuilder.RouteInput> routeInputs = new ArrayList<>();
    WorldGraph graph = null;
    boolean anyOperation = false;
    java.util.Set<UUID> own = new java.util.HashSet<>();
    for (Route route : provider.routes().listByLine(resolved.line().id())) {
      if (route != null) {
        own.add(route.id());
      }
    }
    List<Route> routes = new ArrayList<>(collectRoutes(provider, resolved));
    // 直通运转：运营 route 显式指定的外方出库/回库线路也进 build，并优先于本 operator 搜到的同站线路。
    DeclaredRoutes declared = resolveDeclaredRoutes(sender, provider, routes);
    // 本 operator 范围之外的走行线路：借用它出库/回库，但它不受本表管辖（它所在线路自己的 headway 票照常发）。
    java.util.Set<UUID> externalRoutes = new java.util.HashSet<>();
    for (Route extra : declared.routes()) {
      if (routes.stream().noneMatch(route -> route.id().equals(extra.id()))) {
        routes.add(extra);
        externalRoutes.add(extra.id());
      }
    }
    for (Route route : routes) {
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
      TimetableBuilder.RouteInput routeInput =
          new TimetableBuilder.RouteInput(
              route.id(),
              route.code(),
              route.operationType(),
              readWeight(route),
              definition,
              stops,
              Optional.empty(),
              Optional.ofNullable(declared.typeOf().get(route.id())),
              externalRoutes.contains(route.id()),
              readSpawnGroup(route));
      // 带客的出库班（CREATE 且中途有停站）也是班次：DS 那种只有 CREATE + RETURN 的线一样能编表。
      anyOperation |=
          own.contains(route.id())
              && (route.operationType() == RouteOperationType.OPERATION
                  || (route.operationType() == RouteOperationType.CREATE
                      && ServiceGroupClassifier.carriesPassengers(routeInput)));
      routeInputs.add(routeInput);
    }
    warnDeclaredWithoutSpawnService(sender, declared);
    if (!anyOperation) {
      sender.sendMessage(
          Component.text(
              resolved.line().code() + " 下没有可编表的班次：既没有 OPERATION route，也没有带客的 CREATE route。",
              NamedTextColor.RED));
      return null;
    }
    return new LineRoutes(
        resolved, routeInputs, own, graph, existingId.orElseGet(UUID::randomUUID));
  }

  /** 构建完成后的主线程收尾：报告（联编时一份）、逐线落库、给出发布与查看入口。 */
  private void finishBuild(
      CommandSender sender,
      StorageProvider provider,
      List<ResolvedLine> lines,
      TimetableSetBuilder.SetResult set,
      TimetableBuildOptions options,
      TimetableHeadwayDefaults.Choice headway,
      NeighborReport neighbors,
      Map<String, String> groupSources) {
    TimetableBuildResult result = set.joint();
    sendBuildReport(sender, result, options, headway, groupSources);
    sendNeighborReport(sender, neighbors, result.neighbors());
    sendExternalConflicts(
        sender,
        result.externalConflictsAtTarget(),
        options.serviceStartSecondOfDay(),
        result.headwayRelaxed());
    if (!set.success()) {
      return;
    }
    List<Timetable> saved = new ArrayList<>();
    for (ResolvedLine line : lines) {
      Timetable timetable = set.tables().get(line.line().id());
      if (timetable == null) {
        sender.sendMessage(
            Component.text(line.line().code() + " 没有拆出表来，这是一个不应发生的状态。", NamedTextColor.RED));
        return;
      }
      try {
        provider.timetables().save(timetable);
        provider
            .timetables()
            .replaceBaselines(
                timetable.id(), set.baselines().getOrDefault(line.line().id(), List.of()));
      } catch (StorageException ex) {
        sender.sendMessage(
            Component.text(
                "保存 " + line.line().code() + " 的时刻表失败：" + ex.getMessage(), NamedTextColor.RED));
        return;
      }
      saved.add(timetable);
    }
    ResolvedLine first = lines.get(0);
    String code = saved.get(0).code();
    String lineArg = lineArgumentOf(lines);
    String target =
        first.company().code() + " " + first.operator().code() + " " + lineArg + " " + code;
    sender.sendMessage(
        Component.text(
                (lines.size() > 1 ? "已保存草稿（" + lines.size() + " 张，互为基线）：" : "已保存草稿：")
                    + lineArg
                    + "/"
                    + code
                    + " ",
                NamedTextColor.DARK_AQUA)
            .append(
                CommandUx.suggestAction(
                    lines.size() > 1 ? "[整组投入运行]" : "[投入运行]",
                    "/fta timetable publish " + target,
                    lines.size() > 1
                        ? "填入 publish 命令：几张表一起重检、一起发布，任一张拒绝则整组不发"
                        : "填入 publish 命令，回车后按表运行")));
    for (ResolvedLine line : lines) {
      String single =
          first.company().code()
              + " "
              + first.operator().code()
              + " "
              + line.line().code()
              + " "
              + code;
      sender.sendMessage(
          Component.text("  " + line.line().code() + " ", NamedTextColor.GRAY)
              .append(
                  CommandUx.actions(
                      CommandUx.runAction("[详情]", "/fta timetable info " + single, "查看班次"),
                      CommandUx.runAction("[交路]", "/fta timetable duties " + single, "查看每条车辆交路"),
                      CommandUx.runAction("[导出]", "/fta timetable export " + single, "导出 CSV"))));
    }
    if (result.headwayRelaxed()) {
      sender.sendMessage(
          Component.text("  目标间隔被放宽 ", NamedTextColor.GRAY)
              .append(
                  CommandUx.suggestAction(
                      "[按放宽后的间隔重建]",
                      rebuildCommand(first, lineArg, saved.get(0), options, result),
                      "把放宽后的各组间隔填成 --group-headway 再建一次；无冲突就是可以写回配置的值")));
      for (TimetableBuildResult.GroupInterval group : result.groupIntervals()) {
        if (group.effectiveSeconds() == group.targetSeconds()) {
          continue;
        }
        int slash = group.group().indexOf('/');
        String lineCode = slash < 0 ? first.line().code() : group.group().substring(0, slash);
        String groupName = slash < 0 ? group.group() : group.group().substring(slash + 1);
        if (ServiceGroupClassifier.DEFAULT_GROUP.equals(groupName)) {
          continue;
        }
        sender.sendMessage(
            Component.text("    ", NamedTextColor.GRAY)
                .append(
                    CommandUx.suggestAction(
                        "[写回 " + group.group() + " baseline " + group.effectiveSeconds() + "s]",
                        "/fta route group set "
                            + first.company().code()
                            + " "
                            + first.operator().code()
                            + " "
                            + lineCode
                            + " "
                            + CommandUx.quoteCommandArgument(groupName)
                            + " --baseline "
                            + group.effectiveSeconds(),
                        "把这个交路组的 baselineSec 改成放宽后的间隔，下次不传 --headway 也是它")));
      }
    }
  }

  /** 几条线在命令里的写法：逗号分隔，不加引号（line code 里没有空格）。 */
  private static String lineArgumentOf(List<ResolvedLine> lines) {
    List<String> codes = new ArrayList<>();
    for (ResolvedLine line : lines) {
      codes.add(line.line().code());
    }
    return String.join(",", codes);
  }

  /** 按放宽后的各组间隔重建的命令：显式给出各组 --group-headway，其余只带与默认值不同的参数。 */
  private static String rebuildCommand(
      ResolvedLine first,
      String lineArg,
      Timetable timetable,
      TimetableBuildOptions options,
      TimetableBuildResult result) {
    StringBuilder command =
        new StringBuilder("/fta timetable build ")
            .append(first.company().code())
            .append(' ')
            .append(first.operator().code())
            .append(' ')
            .append(lineArg)
            .append(' ')
            .append(timetable.code());
    for (TimetableBuildResult.GroupInterval group : result.groupIntervals()) {
      command
          .append(" --group-headway ")
          .append(CommandUx.quoteCommandArgument(group.group() + "=" + group.effectiveSeconds()));
    }
    if (result.groupIntervals().isEmpty()) {
      command.append(" --headway ").append(result.effectiveHeadwaySeconds());
    }
    if (options.serviceStartSecondOfDay() != TimetableBuildOptions.DEFAULT_SERVICE_START) {
      command
          .append(" --start ")
          .append(TimetableCsvExporter.clock(options.serviceStartSecondOfDay()));
    }
    if (options.serviceEndSecondOfDay() != TimetableBuildOptions.DEFAULT_SERVICE_END) {
      command.append(" --end ").append(TimetableCsvExporter.clock(options.serviceEndSecondOfDay()));
    }
    if (options.defaultDwell().toSeconds() != TimetableBuildOptions.DEFAULT_DWELL_SECONDS) {
      command.append(" --dwell ").append(options.defaultDwell().toSeconds());
    }
    VehicleDutyPlanner.Limits limits = options.dutyLimits();
    if (limits.maxTripsPerDuty() != VehicleDutyPlanner.Limits.DEFAULT_MAX_TRIPS) {
      command.append(" --max-trips ").append(limits.maxTripsPerDuty());
    }
    if (limits.maxDutyDurationSeconds() != VehicleDutyPlanner.Limits.DEFAULT_MAX_DURATION_SECONDS) {
      command.append(" --max-duty-minutes ").append(limits.maxDutyDurationSeconds() / 60);
    }
    if (limits.turnaround().fixed()) {
      command.append(" --turnaround ").append(limits.turnaround().fallbackSeconds());
    }
    if (limits.maxIdleSeconds() != VehicleDutyPlanner.Limits.DEFAULT_MAX_IDLE_SECONDS) {
      // 重建命令带上它：缺省值来自 reclaim.max-idle-seconds，与默认常数不同的一律显式写出，免得重建换了个数。
      command.append(" --max-idle ").append(limits.maxIdleSeconds());
    }
    if (options.separation().toSeconds() != TimetableBuildOptions.DEFAULT_SEPARATION_SECONDS) {
      command.append(" --separation ").append(options.separation().toSeconds());
    }
    if (options.repair().maxWaitSeconds()
        != TimetableBuildOptions.Repair.DEFAULT_MAX_WAIT_SECONDS) {
      command.append(" --max-wait ").append(options.repair().maxWaitSeconds());
    }
    if (options.strictConflicts()) {
      command.append(" --strict");
    }
    if (!options.tripCodePrefix().isBlank()) {
      command.append(" --prefix ").append(options.tripCodePrefix());
    }
    if (!timetable.name().equals(timetable.code())) {
      command.append(" --name ").append(CommandUx.quoteCommandArgument(timetable.name()));
    }
    return command.toString();
  }

  /**
   * 运营 route 在 metadata 里显式指定的出库/回库走行线路（{@link TimetableRouteMetadata}）。
   *
   * @param routes 解析成功的线路（可能属于别的 operator / company）
   * @param typeOf 每条线路被指定为的类型；同一条线路被同时指定为出库与回库时按先读到的算
   */
  private record DeclaredRoutes(List<Route> routes, Map<UUID, RouteOperationType> typeOf) {}

  /** 解析本线路各运营 route 指定的走行线路：四段 code 逐级查库；找不到只警告不中断，因为没有它 build 也能照常做（只是该站班次可能被取消并说明原因）。 */
  private static DeclaredRoutes resolveDeclaredRoutes(
      CommandSender sender, StorageProvider provider, List<Route> routes) {
    Map<UUID, Route> found = new java.util.LinkedHashMap<>();
    Map<UUID, RouteOperationType> typeOf = new java.util.LinkedHashMap<>();
    for (Route route : routes) {
      if (route.operationType() != RouteOperationType.OPERATION) {
        continue;
      }
      for (String key :
          List.of(
              TimetableRouteMetadata.KEY_CREATE_ROUTE, TimetableRouteMetadata.KEY_RETURN_ROUTE)) {
        Optional<TimetableRouteMetadata.RouteRef> ref =
            TimetableRouteMetadata.read(route.metadata(), key);
        if (ref.isEmpty()) {
          continue;
        }
        Optional<Route> target = findRouteByRef(provider, ref.get());
        if (target.isEmpty()) {
          sender.sendMessage(
              Component.text(
                  "运营线路 " + route.code() + " 指定的走行线路 " + ref.get().format() + " 不存在，忽略。",
                  NamedTextColor.YELLOW));
          continue;
        }
        found.putIfAbsent(target.get().id(), target.get());
        typeOf.putIfAbsent(target.get().id(), TimetableRouteMetadata.typeOf(key));
      }
    }
    return new DeclaredRoutes(List.copyOf(found.values()), Map.copyOf(typeOf));
  }

  /** {@code <company>/<operator>/<line>/<route>} 四段 code 逐级查库。 */
  private static Optional<Route> findRouteByRef(
      StorageProvider provider, TimetableRouteMetadata.RouteRef ref) {
    return provider
        .companies()
        .findByCode(ref.company())
        .flatMap(company -> provider.operators().findByCompanyAndCode(company.id(), ref.operator()))
        .flatMap(operator -> provider.lines().findByOperatorAndCode(operator.id(), ref.line()))
        .flatMap(line -> provider.routes().findByLineAndCode(line.id(), ref.route()));
  }

  /**
   * 被指定的走行线路必须本身是可发车服务（所在 line 配了车库并开了发车），否则票发不出去 （运行时 {@code TIMETABLE_SPAWN_SKIP
   * reason=no-spawn-service}）。编表时就提醒，别等到运营那天。
   */
  private void warnDeclaredWithoutSpawnService(CommandSender sender, DeclaredRoutes declared) {
    if (declared.routes().isEmpty()) {
      return;
    }
    Optional<SpawnPlan> plan = plugin.getSpawnManager().map(SpawnManager::snapshotPlan);
    if (plan.isEmpty()) {
      return;
    }
    for (Route route : declared.routes()) {
      boolean served =
          plan.get().services().stream()
              .anyMatch(service -> service != null && route.id().equals(service.routeId()));
      if (!served) {
        sender.sendMessage(
            Component.text(
                "指定的走行线路 " + route.code() + " 不是可发车服务（所在线路未配置车库或未开启发车），按表运行时它的票会被跳过。",
                NamedTextColor.YELLOW));
      }
    }
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
   * 邻表计算需要的输入：全部在主线程读好，异步线程只做纯运算。
   *
   * @param published 已发布时刻表
   * @param displayCodeById 已发布时刻表的显示码
   * @param mine 我这份表的 route
   * @param unscheduled 本世界里不属于任何已发布表、也不属于我这条 line 的 route
   * @param stopsByRoute 上述全部 route 的停靠配置
   * @param definitions 上述全部 route 的交路定义（主线程解析好，异步线程不碰缓存）
   * @param travelTimeModel 行程时间模型
   * @param lineIds 我这几条 line（联编时不止一条）：它们名下的表互相不算邻表
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
      java.util.Set<UUID> lineIds,
      String myDisplayCode,
      TimetableNeighborhoodLoader loader) {

    /** 主线程读好的 stops / definitions 变成 loader；同一份输入只建一个 loader，它内部缓存重算结果。 */
    static TimetableNeighborhoodLoader loaderOf(
        Map<UUID, RouteDefinition> definitions,
        Map<UUID, List<RouteStop>> stopsByRoute,
        Map<UUID, String> displayCodeById,
        RailTravelTimeModel travelTimeModel) {
      return new TimetableNeighborhoodLoader(
          new TimetableTimingCalculator(),
          travelTimeModel,
          routeId -> Optional.ofNullable(definitions.get(routeId)),
          routeId -> stopsByRoute.getOrDefault(routeId, List.of()),
          timetable -> displayCodeById.getOrDefault(timetable.id(), timetable.code()));
    }

    /** build 用：我的 route 还没有落库时分，按当前图算足迹。 */
    TimetableFootprint footprint(
        RailGraph graph, TimetableConflictChecker.GraphIndex index, UUID timetableId) {
      return loader.footprintOf(timetableId, myDisplayCode, mine, graph, index);
    }

    /** 已落库的表用：路径按当前图、时刻按落库值，检查的是将要运行的那张表。 */
    Map<UUID, TimetableConflictChecker.RouteProfile> myProfiles(
        Timetable timetable, RailGraph graph, TimetableConflictChecker.GraphIndex index) {
      return loader.rebasedProfilesOf(timetable, graph, index);
    }

    NeighborReport report(
        RailGraph graph, TimetableConflictChecker.GraphIndex index, TimetableFootprint footprint) {
      return new NeighborReport(
          loader.footprints(published, lineIds, graph, index, footprint),
          loader.unscheduled(unscheduled, graph, index, footprint));
    }

    List<NeighborTimetable> project(
        RailGraph graph,
        TimetableConflictChecker.GraphIndex index,
        TimetableFootprint footprint,
        TimetableBuildOptions options) {
      return project(
          graph,
          index,
          footprint,
          options.serviceStartSecondOfDay(),
          options.horizonSeconds(),
          options.zoneId());
    }

    List<NeighborTimetable> project(
        RailGraph graph,
        TimetableConflictChecker.GraphIndex index,
        TimetableFootprint footprint,
        int serviceStartSecondOfDay,
        int horizonSeconds,
        ZoneId zone) {
      return loader.project(
          published,
          lineIds,
          graph,
          index,
          footprint,
          serviceStartSecondOfDay,
          horizonSeconds,
          zone,
          java.time.LocalDate.now(zone));
    }
  }

  private record NeighborReport(
      List<TimetableNeighborhoodLoader.FootprintNeighbor> scheduled,
      List<TimetableNeighborhoodLoader.UnscheduledNeighbor> unscheduled) {}

  /** 对一份已落库的表做作用域检查的结果：邻表、基线是否仍对得上、与邻表的冲突。 */
  private record ScopeCheck(
      List<NeighborTimetable> neighbors,
      NeighborReport report,
      boolean baselinesMatch,
      TimetableConflictChecker.Report conflicts) {}

  private static String displayCodeOf(ResolvedLine resolved) {
    return resolved.company().code()
        + "/"
        + resolved.operator().code()
        + "/"
        + resolved.line().code();
  }

  /** 主线程读库：已发布表及其显示码、无表线路的 route、全部相关 route 的停靠配置与交路定义。 */
  private NeighborInputs collectNeighborInputs(
      StorageProvider provider,
      List<ResolvedLine> lines,
      List<TimetableNeighborhoodLoader.RouteCandidate> mine,
      Map<UUID, List<RouteStop>> knownStops,
      Map<UUID, RouteDefinition> knownDefinitions,
      RailTravelTimeModel model) {
    Map<UUID, List<RouteStop>> stopsByRoute = new java.util.HashMap<>(knownStops);
    Map<UUID, RouteDefinition> definitions = new java.util.HashMap<>(knownDefinitions);
    for (TimetableNeighborhoodLoader.RouteCandidate route : mine) {
      stopsByRoute.computeIfAbsent(route.routeId(), id -> sortedStops(provider, id));
      if (!definitions.containsKey(route.routeId())) {
        plugin
            .findRouteDefinitionById(route.routeId())
            .ifPresent(def -> definitions.put(route.routeId(), def));
      }
    }
    java.util.Set<UUID> lineIds = new java.util.LinkedHashSet<>();
    List<String> displayCodes = new ArrayList<>();
    for (ResolvedLine resolved : lines) {
      lineIds.add(resolved.line().id());
      displayCodes.add(displayCodeOf(resolved));
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
          if (lineIds.contains(line.id()) || scheduledLines.contains(line.id())) {
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
        model,
        lineIds,
        String.join("+", displayCodes),
        NeighborInputs.loaderOf(definitions, stopsByRoute, displayCodeById, model));
  }

  /** 已落库的表的 route 集合，供 publish 重检与 neighbors 命令。 */
  private static List<TimetableNeighborhoodLoader.RouteCandidate> routesOf(
      Timetable timetable, String displayCode) {
    List<TimetableNeighborhoodLoader.RouteCandidate> out = new ArrayList<>();
    for (TimetableRoutePlan plan : timetable.routePlans()) {
      out.add(
          new TimetableNeighborhoodLoader.RouteCandidate(
              plan.routeId(), plan.routeCode(), displayCode));
    }
    return out;
  }

  /**
   * 对一份已落库的表做作用域检查（异步线程）：投影邻表与我的表，比对基线，扫外部冲突。
   *
   * <p>基线全部对得上就不需要重扫——邻表没变，build 时的结论仍然成立。
   */
  private static ScopeCheck scopeCheck(
      NeighborInputs inputs, RailGraph graph, Timetable timetable, List<TimetableBaseline> stored) {
    TimetableConflictChecker.GraphIndex index = TimetableConflictChecker.GraphIndex.of(graph);
    // 我的表已经落库：投影用落库时分（与邻表同一口径），足迹直接从这份投影算，不再按当前图另算一遍。
    Map<UUID, TimetableConflictChecker.RouteProfile> profiles =
        new java.util.HashMap<>(inputs.myProfiles(timetable, graph, index));
    TimetableFootprint footprint =
        TimetableFootprint.of(timetable.id(), inputs.myDisplayCode(), profiles.values(), index);
    NeighborReport report = inputs.report(graph, index, footprint);
    List<NeighborTimetable> neighbors =
        inputs.project(
            graph,
            index,
            footprint,
            timetable.serviceStartSecondOfDay(),
            timetable.serviceEndSecondOfDay() - timetable.serviceStartSecondOfDay(),
            timetable.zoneId());
    boolean baselinesMatch =
        neighbors.size() == stored.size()
            && neighbors.stream()
                .allMatch(neighbor -> stored.stream().anyMatch(b -> b.matches(neighbor)));
    TimetableOccupancyProjector.Occupancy mine =
        TimetableOccupancyProjector.project(
            timetable, profiles, timetable.serviceStartSecondOfDay());
    List<TimetableConflictChecker.Movement> movements = new ArrayList<>(mine.movements());
    List<TimetableConflictChecker.Stay> stays = new ArrayList<>(mine.stays());
    for (NeighborTimetable neighbor : neighbors) {
      neighbor.profiles().forEach(profiles::putIfAbsent);
      movements.addAll(neighbor.movements());
      stays.addAll(neighbor.stays());
    }
    TimetableConflictChecker.Report conflicts =
        TimetableConflictChecker.check(
            index,
            profiles,
            movements,
            stays,
            TimetableBuildOptions.DEFAULT_SEPARATION_SECONDS,
            TimetableConflictChecker.vehicleOf(timetable));
    return new ScopeCheck(neighbors, report, baselinesMatch, conflicts);
  }

  private static List<TimetableBaseline> baselinesOf(Timetable timetable, ScopeCheck check) {
    Map<String, Integer> byOwner = check.conflicts().externalByOwner();
    List<TimetableBaseline> out = new ArrayList<>();
    for (NeighborTimetable neighbor : check.neighbors()) {
      out.add(
          TimetableBaseline.of(
              timetable.id(), neighbor, byOwner.getOrDefault(neighbor.displayCode(), 0)));
    }
    return out;
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

  /** 「共用资源」一节：有表的邻表参与了检查（列冲突数），无表的只能报告——它们按 headway 发车，干扰单向。 */
  private static void sendNeighborReport(
      CommandSender sender,
      NeighborReport neighbors,
      List<TimetableBuildResult.NeighborSummary> summaries) {
    if (neighbors == null) {
      return;
    }
    if (neighbors.scheduled().isEmpty() && neighbors.unscheduled().isEmpty()) {
      sender.sendMessage(Component.text("  共用资源: 没有别的线路与本表共用区间、站台、单线或道岔", NamedTextColor.GREEN));
      return;
    }
    Map<String, TimetableBuildResult.NeighborSummary> byCode = new java.util.HashMap<>();
    for (TimetableBuildResult.NeighborSummary summary : summaries) {
      byCode.put(summary.displayCode(), summary);
    }
    sender.sendMessage(Component.text("  共用资源:", NamedTextColor.GRAY));
    for (TimetableNeighborhoodLoader.FootprintNeighbor neighbor : neighbors.scheduled()) {
      TimetableBuildResult.NeighborSummary summary = byCode.get(neighbor.displayCode());
      String verdict =
          summary == null
              ? "已发布，本次未参与检查"
              : summary.conflictsAtTarget() == 0
                  ? "已发布，已避让，目标间隔下无冲突"
                  : "已发布，目标间隔下与它冲突 " + summary.conflictsAtTarget() + " 处（只能挪自己）";
      String flags =
          (summary != null && summary.stale() ? "，对方表基于旧图" : "")
              + (summary != null && summary.zoneApproximated() ? "，时区不同按当日偏移换算" : "");
      sender.sendMessage(
          Component.text(
              "    "
                  + neighbor.displayCode()
                  + " 共用 "
                  + neighbor.sharedResources()
                  + " 个资源，"
                  + verdict
                  + flags,
              summary != null && summary.conflictsAtTarget() == 0
                  ? NamedTextColor.WHITE
                  : NamedTextColor.YELLOW));
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

  /** 「外部冲突」一节：与已发布邻表撞上的，建议只有四种——我不能挪别人。 */
  private static void sendExternalConflicts(
      CommandSender sender,
      List<TimetableConflictChecker.Conflict> external,
      int serviceStartSecondOfDay,
      boolean relaxed) {
    if (external.isEmpty()) {
      return;
    }
    sender.sendMessage(
        Component.text("  外部冲突（目标间隔下，与已发布邻表）: " + external.size() + " 处", NamedTextColor.YELLOW));
    int shown = Math.min(external.size(), TimetableBuildResult.CONFLICT_DETAIL_LIMIT);
    for (int i = 0; i < shown; i++) {
      sender.sendMessage(
          Component.text(
              "    · "
                  + external
                      .get(i)
                      .describe(
                          seconds -> TimetableCsvExporter.clock(serviceStartSecondOfDay + seconds)),
              NamedTextColor.GRAY));
    }
    if (external.size() > shown) {
      sender.sendMessage(
          Component.text("    · … 另有 " + (external.size() - shown) + " 处", NamedTextColor.GRAY));
    }
    sender.sendMessage(
        Component.text(
            "    可做的事: "
                + (relaxed ? "目标间隔已自动放宽；" : "")
                + "加大 --separation / 换股道（改 DYNAMIC 范围或站台）/ 与对方运营方协商由其 unpublish 重编",
            NamedTextColor.DARK_GRAY));
  }

  private void sendBuildReport(
      CommandSender sender,
      TimetableBuildResult result,
      TimetableBuildOptions options,
      TimetableHeadwayDefaults.Choice headway,
      Map<String, String> groupSources) {
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
    if (result.groupIntervals().isEmpty()) {
      sender.sendMessage(field("间隔来源", headway.description() + "：" + headway.seconds() + "s"));
    }
    for (String line :
        TimetableBuildReportText.describeGroups(result.groupIntervals(), groupSources)) {
      sender.sendMessage(Component.text("  " + line, NamedTextColor.GRAY));
    }
    for (String note : result.phaseNotes()) {
      sender.sendMessage(Component.text("  相位: " + note, NamedTextColor.DARK_GRAY));
    }
    for (String line : TimetableBuildReportText.describeInterleaves(result.interleaves())) {
      sender.sendMessage(Component.text("  " + line, NamedTextColor.GRAY));
    }
    sender.sendMessage(
        Component.text(
            "  " + TimetableBuildReportText.describeDutyShapes(result.dutyShapes()),
            NamedTextColor.GRAY));
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
    sender.sendMessage(
        Component.text(
            "  "
                + TimetableBuildReportText.describeYields(
                    result.yields(), options.repair().maxWaitSeconds()),
            result.yields().isEmpty() ? NamedTextColor.GRAY : NamedTextColor.AQUA));
    for (String line :
        TimetableBuildReportText.describeYieldDetails(
            result.yields(), options.serviceStartSecondOfDay(), YIELD_DETAIL_LIMIT)) {
      sender.sendMessage(Component.text("    " + line, NamedTextColor.DARK_GRAY));
    }
    for (String line :
        TimetableBuildReportText.describeTerminals(
            result.terminals(), result.shifts(), options.dutyLimits().turnaround())) {
      sender.sendMessage(
          Component.text(
              "  " + line, line.contains("超过 100%") ? NamedTextColor.YELLOW : NamedTextColor.GRAY));
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
    List<TimetableBaseline> baselines =
        providerOpt.get().timetables().listBaselines(timetable.id());
    sender.sendMessage(
        field(
            "邻表基线",
            baselines.isEmpty()
                ? "无"
                : baselines.size() + " 份（用 /fta timetable neighbors 查看是否仍一致）"));
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
    List<ResolvedLine> lines = resolveLines(ctx, provider, true);
    if (lines == null) {
      return;
    }
    String code = ((String) ctx.get("code")).trim();
    List<Timetable> tables = new ArrayList<>();
    for (ResolvedLine line : lines) {
      Optional<Timetable> timetable =
          provider.timetables().findByLineAndCode(line.line().id(), code);
      if (timetable.isEmpty()) {
        sender.sendMessage(
            Component.text("未找到时刻表：" + line.line().code() + "/" + code, NamedTextColor.RED));
        return;
      }
      tables.add(timetable.get());
    }
    if (next != TimetableStatus.PUBLISHED) {
      for (Timetable timetable : tables) {
        applyStatus(sender, provider, timetable, next);
      }
      return;
    }
    for (Timetable timetable : tables) {
      if (timetable.trips().isEmpty()) {
        sender.sendMessage(
            Component.text(
                timetable.code()
                    + "（"
                    + lineCodeOf(lines, timetable)
                    + "）一趟车都没有，publish 之后这条线会发不出车。",
                NamedTextColor.RED));
        return;
      }
    }
    // 发布前重检：邻表集合较 build 时有变化就重新扫一遍外部冲突。谁后发布谁避让，没有 --force。
    // 几张表一起发时互相不算邻表（它们本来就是一起编的），任一张与外部邻表撞上则整组不发。
    Map<UUID, List<TimetableBaseline>> stored = new java.util.LinkedHashMap<>();
    for (Timetable timetable : tables) {
      stored.put(timetable.id(), provider.timetables().listBaselines(timetable.id()));
    }
    WorldGraph worldGraph = graphForTimetable(tables.get(0));
    if (worldGraph == null) {
      boolean anyBaseline = stored.values().stream().anyMatch(list -> !list.isEmpty());
      if (anyBaseline) {
        sender.sendMessage(
            Component.text("找不到覆盖这条线路的调度图快照，无法对照邻表基线重检；请先 /fta graph build。", NamedTextColor.RED));
        return;
      }
      // build 时没有邻表：没有需要重检的路权约束，不该因为图快照还没建而把线路卡在 DRAFT。
      sender.sendMessage(Component.text("找不到调度图快照，跳过发布前重检（build 时没有邻表基线）。", NamedTextColor.YELLOW));
      publishAll(sender, provider, tables, Map.of(), Map.of());
      return;
    }
    RailGraph graph = worldGraph.graph();
    RailTravelTimeModel model = travelTimeModel(worldGraph.worldId());
    Map<UUID, NeighborInputs> inputsById = new java.util.LinkedHashMap<>();
    for (int i = 0; i < tables.size(); i++) {
      Timetable timetable = tables.get(i);
      ResolvedLine line = lines.get(i);
      inputsById.put(
          timetable.id(),
          collectNeighborInputs(
              provider,
              lines,
              routesOf(timetable, displayCodeOf(line)),
              Map.of(),
              Map.of(),
              model));
    }
    sender.sendMessage(Component.text("正在对照已发布邻表重检…", NamedTextColor.GRAY));
    plugin
        .getServer()
        .getScheduler()
        .runTaskAsynchronously(
            plugin,
            () -> {
              Map<UUID, ScopeCheck> checks = new java.util.LinkedHashMap<>();
              for (Timetable timetable : tables) {
                checks.put(
                    timetable.id(),
                    scopeCheck(
                        inputsById.get(timetable.id()),
                        graph,
                        timetable,
                        stored.getOrDefault(timetable.id(), List.of())));
              }
              plugin
                  .getServer()
                  .getScheduler()
                  .runTask(plugin, () -> finishPublish(sender, provider, tables, checks, stored));
            });
  }

  private static String lineCodeOf(List<ResolvedLine> lines, Timetable timetable) {
    for (ResolvedLine line : lines) {
      if (line.line().id().equals(timetable.lineId())) {
        return line.line().code();
      }
    }
    return "";
  }

  /**
   * 发布重检的主线程收尾：基线没变直接发；变了且有外部冲突则拒绝（整组）；变了但无冲突则更新基线再发。
   *
   * <p>重检在异步线程跑了一会儿，期间表可能被重新 build 或删除；save 会整体重写发车表与交路，所以必须回读一次， 变了就拒绝而不是把重检前的旧表写回去。
   */
  private void finishPublish(
      CommandSender sender,
      StorageProvider provider,
      List<Timetable> checked,
      Map<UUID, ScopeCheck> checks,
      Map<UUID, List<TimetableBaseline>> stored) {
    List<Timetable> tables = new ArrayList<>();
    for (Timetable before : checked) {
      Optional<Timetable> current = provider.timetables().findById(before.id());
      if (current.isEmpty()) {
        sender.sendMessage(Component.text(before.code() + " 在重检期间被删除，取消发布。", NamedTextColor.RED));
        return;
      }
      if (!current.get().updatedAt().equals(before.updatedAt())) {
        sender.sendMessage(
            Component.text(
                before.code() + " 在重检期间被修改（例如重新 build），请重新 publish。", NamedTextColor.RED));
        return;
      }
      tables.add(current.get());
    }
    boolean rejected = false;
    for (Timetable timetable : tables) {
      ScopeCheck check = checks.get(timetable.id());
      if (check.baselinesMatch()) {
        continue;
      }
      List<TimetableConflictChecker.Conflict> external = check.conflicts().external();
      if (!external.isEmpty()) {
        sender.sendMessage(
            Component.text(
                "TIMETABLE_PUBLISH_REJECTED：邻表自 build 以来有变化，与已发布邻表有 "
                    + external.size()
                    + " 处冲突，拒绝发布。请重新 build 避让后再发布。",
                NamedTextColor.RED));
        sendExternalConflicts(sender, external, timetable.serviceStartSecondOfDay(), false);
        rejected = true;
      }
    }
    if (rejected) {
      if (tables.size() > 1) {
        sender.sendMessage(Component.text("整组一张都没有发布。", NamedTextColor.RED));
      }
      return;
    }
    publishAll(sender, provider, tables, checks, stored);
  }

  /** 把几张表一起置为 PUBLISHED：同一个时刻，互相的基线也记这个时刻（它们从此互为已发布邻表，updatedAt 要对得上）；外部邻表的基线按重检结果更新。 */
  private void publishAll(
      CommandSender sender,
      StorageProvider provider,
      List<Timetable> tables,
      Map<UUID, ScopeCheck> checks,
      Map<UUID, List<TimetableBaseline>> stored) {
    Instant now = Instant.now();
    java.util.Set<UUID> setIds = new java.util.HashSet<>();
    for (Timetable timetable : tables) {
      setIds.add(timetable.id());
    }
    for (Timetable timetable : tables) {
      ScopeCheck check = checks.get(timetable.id());
      boolean refresh = tables.size() > 1 || (check != null && !check.baselinesMatch());
      if (refresh) {
        List<TimetableBaseline> baselines =
            check == null ? new ArrayList<>() : new ArrayList<>(baselinesOf(timetable, check));
        for (TimetableBaseline old : stored.getOrDefault(timetable.id(), List.of())) {
          if (setIds.contains(old.neighborTimetableId())) {
            baselines.add(
                new TimetableBaseline(
                    timetable.id(),
                    old.neighborTimetableId(),
                    old.neighborCode(),
                    now,
                    old.sharedResources(),
                    old.conflictsAtTarget(),
                    old.staleAgainstGraph()));
          }
        }
        try {
          provider.timetables().replaceBaselines(timetable.id(), baselines);
        } catch (StorageException ex) {
          sender.sendMessage(Component.text("更新基线失败：" + ex.getMessage(), NamedTextColor.RED));
          return;
        }
        if (check != null && !check.baselinesMatch()) {
          sender.sendMessage(
              Component.text(timetable.code() + "：邻表有变化但无冲突，已更新基线。", NamedTextColor.GRAY));
        }
      }
      provider.timetables().save(timetable.withStatus(TimetableStatus.PUBLISHED, now));
    }
    plugin.getTimetableService().ifPresent(service -> service.reload(provider));
    for (Timetable timetable : tables) {
      sender.sendMessage(Component.text(timetable.code() + " → PUBLISHED", NamedTextColor.GREEN));
    }
    boolean enabled =
        plugin.getTimetableService().map(service -> service.settings().enabled()).orElse(false);
    if (!enabled) {
      sender.sendMessage(
          Component.text(
              "注意：config.yml 的 timetable.enabled 仍为 false，这份表暂时不会影响任何列车。", NamedTextColor.YELLOW));
    }
  }

  private void applyStatus(
      CommandSender sender, StorageProvider provider, Timetable timetable, TimetableStatus next) {
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

  /** 从表里任意一条 route 的定义找到它所在世界的图快照。 */
  private WorldGraph graphForTimetable(Timetable timetable) {
    for (TimetableRoutePlan plan : timetable.routePlans()) {
      Optional<WorldGraph> graph =
          plugin.findRouteDefinitionById(plan.routeId()).flatMap(this::resolveGraph);
      if (graph.isPresent()) {
        return graph.get();
      }
    }
    return null;
  }

  /** 图快照连同它所属的世界：限速覆盖表按世界存，编表要拿对世界的那份。 */
  private record WorldGraph(UUID worldId, RailGraph graph) {}

  /** 只读：列出与本表共用资源的邻表、基线是否仍对得上、当前外部冲突。 */
  private void handleNeighbors(CommandContext<CommandSender> ctx) {
    CommandSender sender = ctx.sender();
    Optional<StorageProvider> providerOpt = readyProvider(sender);
    if (providerOpt.isEmpty()) {
      return;
    }
    StorageProvider provider = providerOpt.get();
    ResolvedTimetable resolved = resolveTimetable(ctx, provider, false);
    if (resolved == null) {
      return;
    }
    Timetable timetable = resolved.timetable();
    ResolvedLine line = new ResolvedLine(resolved.company(), resolved.operator(), resolved.line());
    WorldGraph worldGraph = graphForTimetable(timetable);
    if (worldGraph == null) {
      sender.sendMessage(Component.text("找不到覆盖这条线路的调度图快照。", NamedTextColor.RED));
      return;
    }
    RailGraph graph = worldGraph.graph();
    NeighborInputs inputs =
        collectNeighborInputs(
            provider,
            List.of(line),
            routesOf(timetable, displayCodeOf(line)),
            Map.of(),
            Map.of(),
            travelTimeModel(worldGraph.worldId()));
    List<TimetableBaseline> stored = provider.timetables().listBaselines(timetable.id());
    plugin
        .getServer()
        .getScheduler()
        .runTaskAsynchronously(
            plugin,
            () -> {
              ScopeCheck check = scopeCheck(inputs, graph, timetable, stored);
              plugin
                  .getServer()
                  .getScheduler()
                  .runTask(
                      plugin,
                      () -> {
                        sender.sendMessage(
                            Component.text(
                                "===== 邻表 "
                                    + timetable.code()
                                    + "（"
                                    + timetable.status().name()
                                    + "）=====",
                                NamedTextColor.DARK_AQUA));
                        sender.sendMessage(
                            field(
                                "基线",
                                stored.isEmpty()
                                    ? "无（build 时没有邻表，或基线尚未落库）"
                                    : check.baselinesMatch() ? "与当前邻表一致" : "已过期：邻表自 build 以来有变化"));
                        List<TimetableBuildResult.NeighborSummary> summaries = new ArrayList<>();
                        Map<String, Integer> byOwner = check.conflicts().externalByOwner();
                        for (NeighborTimetable neighbor : check.neighbors()) {
                          summaries.add(
                              TimetableBuildResult.NeighborSummary.of(
                                  neighbor, byOwner.getOrDefault(neighbor.displayCode(), 0)));
                        }
                        sendNeighborReport(sender, check.report(), summaries);
                        sendExternalConflicts(
                            sender,
                            check.conflicts().external(),
                            timetable.serviceStartSecondOfDay(),
                            false);
                      });
            });
  }

  private void handleDelete(CommandContext<CommandSender> ctx, boolean confirmed) {
    CommandSender sender = ctx.sender();
    Optional<StorageProvider> providerOpt = readyProvider(sender);
    if (providerOpt.isEmpty()) {
      return;
    }
    StorageProvider provider = providerOpt.get();
    List<ResolvedLine> lines = resolveLines(ctx, provider, true);
    if (lines == null) {
      return;
    }
    String code = ((String) ctx.get("code")).trim();
    List<Timetable> tables = new ArrayList<>();
    for (ResolvedLine line : lines) {
      Optional<Timetable> timetable =
          provider.timetables().findByLineAndCode(line.line().id(), code);
      if (timetable.isEmpty()) {
        sender.sendMessage(
            Component.text("未找到时刻表：" + line.line().code() + "/" + code, NamedTextColor.RED));
        return;
      }
      tables.add(timetable.get());
    }
    if (!confirmed) {
      sender.sendMessage(locale().component("command.common.confirm-required"));
      return;
    }
    for (Timetable timetable : tables) {
      provider.timetables().delete(timetable.id());
    }
    plugin.getTimetableService().ifPresent(service -> service.reload(provider));
    sender.sendMessage(
        Component.text(
            "已删除时刻表 "
                + lineArgumentOf(lines)
                + "/"
                + code
                + (tables.size() > 1 ? "（" + tables.size() + " 张）" : ""),
            NamedTextColor.DARK_AQUA));
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
  /**
   * 编表用的行程时间模型：默认加减速参数 + 该世界的永久限速覆盖（{@link TimetableEdgeSpeeds}）。 图里的边基础限速多半是
   * 0，不接覆盖表全线就按默认速度算，时分会慢两到三倍。
   */
  private RailTravelTimeModel travelTimeModel(UUID worldId) {
    double fallback = FALLBACK_SPEED_BPS;
    if (plugin.getConfigManager() != null && plugin.getConfigManager().current() != null) {
      double configured =
          plugin.getConfigManager().current().graphSettings().defaultSpeedBlocksPerSecond();
      if (Double.isFinite(configured) && configured > 0.0D) {
        fallback = configured;
      }
    }
    return new DynamicTravelTimeModel(
        DynamicTravelTimeModel.TrainMotionParams.defaults(),
        fallback,
        ApproachingConfig.disabled(),
        TimetableEdgeSpeeds.resolver(
            worldId == null ? Map.of() : plugin.getRailGraphService().edgeOverrides(worldId)));
  }

  /** 找到覆盖这条交路全部节点的调度图快照。 */
  private Optional<WorldGraph> resolveGraph(RouteDefinition definition) {
    List<NodeId> waypoints = definition.waypoints();
    return plugin
        .getRailGraphService()
        .findWorldIdForPath(waypoints)
        .flatMap(
            worldId ->
                plugin
                    .getRailGraphService()
                    .getSnapshot(worldId)
                    .map(snapshot -> new WorldGraph(worldId, snapshot.graph())));
  }

  /** route metadata 的交路组名；没配返回空，编表时按起点站台组推导。 */
  private static Optional<String> readSpawnGroup(Route route) {
    Object raw = route.metadata() == null ? null : route.metadata().get("spawn_group");
    if (raw == null) {
      return Optional.empty();
    }
    String group = String.valueOf(raw).trim();
    return group.isBlank() ? Optional.empty() : Optional.of(group);
  }

  /**
   * 解析 {@code --group-headway A=150 --group-headway B=300}（也接受一个值里逗号分隔）；格式不对时提示并返回 null。
   *
   * <p>组名按 metadata 原样匹配，不改大小写；秒数必须为正。
   */

  /**
   * 让车参数：{@code --max-wait} 缺省取 {@code timetable.assign-tolerance-seconds}，上限 1800 s。
   *
   * <p>它<b>不再</b>被 {@code timetable.hold-max-seconds} 封顶。{@code hold-max} 约束的是"早到的车在站台被扣多久"，
   * 超了运行时直接放行；而车在资源前排队是占用队列的事，无界。两者不是同一种等待，用前者去限制后者会把大量 现实可行的表判成不可行——实测 WS 在这条封顶下把 60 s
   * 以上的让车全判成了真冲突。 报告里仍用 hold-max 区分让车发生在哪：{@code ≤ hold-max} 是站台扣留，超过的那部分由资源前的排队兑现。
   *
   * <p>累计上限跟 assign-tolerance（超过它车次就对不上了），但不小于单步上限——否则第一处让车就会把交路截断。
   */
  private TimetableBuildOptions.Repair repairOptions(CommandSender sender, Integer requested) {
    ConfigManager.TimetableSettings settings =
        plugin.getConfigManager() != null && plugin.getConfigManager().current() != null
            ? plugin.getConfigManager().current().timetableSettings()
            : ConfigManager.TimetableSettings.defaults();
    int tolerance = settings.assignToleranceSeconds();
    int maxWait = requested == null ? tolerance : requested;
    if (maxWait > TimetableBuildOptions.Repair.MAX_WAIT_CEILING_SECONDS) {
      sender.sendMessage(
          Component.text(
              "--max-wait "
                  + maxWait
                  + " 超过上限 "
                  + TimetableBuildOptions.Repair.MAX_WAIT_CEILING_SECONDS
                  + " s，按上限计。",
              NamedTextColor.YELLOW));
      maxWait = TimetableBuildOptions.Repair.MAX_WAIT_CEILING_SECONDS;
    }
    return new TimetableBuildOptions.Repair(
        Duration.ofSeconds(maxWait), Duration.ofSeconds(tolerance));
  }

  /**
   * 端点闲置上限：不传时取 {@code reclaim.max-idle-seconds}。
   *
   * <p>编表侧必须和运行时用同一个数——运行时待命超过它就派回库票，编表却让车在端点干等到下一个时隙的话， 表上那段待命占用是假的，还会把站台判成冲突（实测 MT 有车在 PPK 等了 19
   * 分钟，运行时 5 分钟就收走了）。
   */
  private int resolveMaxIdleSeconds(Integer requested) {
    if (requested != null) {
      return requested;
    }
    if (plugin.getConfigManager() != null && plugin.getConfigManager().current() != null) {
      ConfigManager.ReclaimSettings reclaim = plugin.getConfigManager().current().reclaimSettings();
      if (!reclaim.enabled()) {
        // 回收关着：运行时的待命车不会被收走，会一直等到下一班。编表也必须让它等，
        // 否则会把运行时实际跑得了的班次当成"没有车"取消掉。
        return NO_IDLE_LIMIT_SECONDS;
      }
      long configured = reclaim.maxIdleSeconds();
      if (configured > 0 && configured <= NO_IDLE_LIMIT_SECONDS) {
        return (int) configured;
      }
    }
    return VehicleDutyPlanner.Limits.DEFAULT_MAX_IDLE_SECONDS;
  }

  /** {@code --group-headway} 的补全：本线路 metadata 里的交路组名加 {@code =}，没配组时给默认组。 */
  private SuggestionProvider<CommandSender> groupHeadwaySuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = normalizePrefix(input);
          if (prefix.startsWith("\"")) {
            prefix = prefix.substring(1);
          }
          final String matchPrefix = prefix;
          List<String> out = new ArrayList<>();
          if (matchPrefix.isBlank()) {
            out.add("<group>=<seconds>");
          }
          resolveLineForSuggestion(ctx)
              .ifPresent(
                  pair -> {
                    List<String> names = new ArrayList<>();
                    for (SpawnGroup group : LineSpawnMetadata.parseGroups(pair.line().metadata())) {
                      names.add(group.name());
                    }
                    if (names.isEmpty()) {
                      names.add(ServiceGroupClassifier.DEFAULT_GROUP);
                    }
                    for (String name : names) {
                      String candidate = name + "=";
                      if (matches(candidate, matchPrefix)) {
                        out.add(candidate);
                      }
                    }
                  });
          return out;
        });
  }

  private static Map<String, Integer> parseGroupHeadways(
      CommandSender sender, List<String> entries) {
    Map<String, Integer> out = new java.util.TreeMap<>();
    if (entries == null || entries.isEmpty()) {
      return out;
    }
    List<String> parts = new ArrayList<>();
    for (String entry : entries) {
      if (entry == null || entry.isBlank()) {
        continue;
      }
      for (String part : entry.split(",")) {
        if (!part.isBlank()) {
          parts.add(part);
        }
      }
    }
    for (String part : parts) {
      String[] kv = part.trim().split("=", 2);
      Integer seconds = null;
      if (kv.length == 2 && !kv[0].isBlank()) {
        try {
          seconds = Integer.parseInt(kv[1].trim());
        } catch (NumberFormatException ignored) {
          seconds = null;
        }
      }
      if (seconds == null || seconds <= 0) {
        sender.sendMessage(
            Component.text(
                "--group-headway 格式应为 <组名>=<秒>[,<组名>=<秒>...]，秒数为正：" + part.trim(),
                NamedTextColor.RED));
        return null;
      }
      out.put(kv[0].trim(), seconds);
    }
    return out;
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

  /**
   * 解析 {@code <line>} 参数：逗号分隔的一到多条线，全部在同一 company/operator 下；重复的去掉，顺序保留。 build / publish /
   * unpublish / delete 用它；单线命令仍走 {@link #resolveLine}。
   */
  private List<ResolvedLine> resolveLines(
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
    Operator operator = operatorOpt.get();
    List<ResolvedLine> out = new ArrayList<>();
    java.util.Set<UUID> seen = new java.util.HashSet<>();
    for (String part : ((String) ctx.get("line")).split(",")) {
      String lineArg = part.trim();
      if (lineArg.isEmpty()) {
        continue;
      }
      Optional<Line> lineOpt = query.findLine(operator.id(), lineArg);
      if (lineOpt.isEmpty()) {
        sender.sendMessage(locale.component("command.line.not-found", Map.of("line", lineArg)));
        return null;
      }
      if (seen.add(lineOpt.get().id())) {
        out.add(new ResolvedLine(company, operator, lineOpt.get()));
      }
    }
    if (out.isEmpty()) {
      sender.sendMessage(locale.component("command.line.not-found", Map.of("line", "")));
      return null;
    }
    return out;
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

  /** 线路补全：支持逗号分隔的多条线——光标在最后一段上补全，前面已选的原样保留、不再重复建议。 */
  private SuggestionProvider<CommandSender> lineSuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String token = input == null ? "" : input.lastRemainingToken().trim();
          int comma = token.lastIndexOf(',');
          String head = comma < 0 ? "" : token.substring(0, comma + 1);
          String prefix = (comma < 0 ? token : token.substring(comma + 1)).toLowerCase(Locale.ROOT);
          java.util.Set<String> chosen = new java.util.HashSet<>();
          for (String part : head.split(",")) {
            if (!part.isBlank()) {
              chosen.add(part.trim().toLowerCase(Locale.ROOT));
            }
          }
          List<String> out = new ArrayList<>();
          if (prefix.isBlank() && head.isEmpty()) {
            out.add("<line>");
          }
          resolveOperatorForSuggestion(ctx)
              .ifPresent(
                  pair ->
                      pair.provider().lines().listByOperator(pair.operator().id()).stream()
                          .map(Line::code)
                          .filter(code -> matches(code, prefix))
                          .filter(code -> !chosen.contains(code.toLowerCase(Locale.ROOT)))
                          .limit(SUGGESTION_LIMIT)
                          .forEach(code -> out.add(head + code)));
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
   * @param turnaroundSeconds 终端折返时间；{@code null} 表示不覆盖，按各 route 终到站的 dwell 算
   * @param separationSeconds 冲突检查里相邻占用之间的最小间隔
   * @param strict 目标 headway 有冲突时构建失败而不是回退
   * @param name 时刻表展示名
   * @param tripCodePrefix 车次号前缀
   * @param zone 时区
   */
  private record BuildFlags(
      Integer headwaySeconds,
      List<String> groupHeadways,
      String start,
      String end,
      int dwellSeconds,
      int maxTripsPerDuty,
      int maxDutyMinutes,
      Integer turnaroundSeconds,
      int separationSeconds,
      Integer maxWaitSeconds,
      Integer maxIdleSeconds,
      boolean strict,
      String name,
      String tripCodePrefix,
      String zone) {}
}
