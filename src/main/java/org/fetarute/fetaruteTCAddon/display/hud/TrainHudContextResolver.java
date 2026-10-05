package org.fetarute.fetaruteTCAddon.display.hud;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.controller.MinecartMemberStore;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.api.StationDirectory;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaResult;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaService;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaTarget;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainRuntimeSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLineChanges;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteTerminals;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainTagHelper;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignTextParser;
import org.fetarute.fetaruteTCAddon.display.DisplayService;
import org.fetarute.fetaruteTCAddon.display.Lateness;
import org.fetarute.fetaruteTCAddon.display.hud.TrainHudContext.Destinations;
import org.fetarute.fetaruteTCAddon.display.hud.TrainHudContext.StationDisplay;
import org.fetarute.fetaruteTCAddon.display.hud.bossbar.HudWaypointLabel;
import org.fetarute.fetaruteTCAddon.display.template.HudTemplateService;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * HUD 上下文解析器：从 TrainCarts + 运行时缓存解析 HUD 所需信息与占位符。
 *
 * <p>负责：
 *
 * <ul>
 *   <li>玩家载具 → MinecartGroup 解析
 *   <li>Route/Station/ETA/信号/待命等上下文汇总
 *   <li>占位符填充与缓存（站点/公司）
 * </ul>
 */
public final class TrainHudContextResolver {

  private static final long VEHICLE_HOPS = 3;
  private static final List<String> DEFAULT_LOCALE_TAGS = List.of("zh_CN", "en_US");
  private static final DateTimeFormatter CLOCK_MINUTES = DateTimeFormatter.ofPattern("HH:mm");
  private static final DateTimeFormatter CLOCK_SECONDS = DateTimeFormatter.ofPattern("HH:mm:ss");

  private final FetaruteTCAddon plugin;
  private final LocaleManager locale;
  private final EtaService etaService;
  private final RouteDefinitionCache routeDefinitions;
  private final Optional<RouteProgressRegistry> routeProgressRegistry;
  private final Optional<LayoverRegistry> layoverRegistry;
  private final HudTemplateService templateService;
  private final Consumer<String> debugLogger;

  private final Map<String, Map<RoutePatternType, String>> patternTextByLocale = new HashMap<>();
  private final Map<String, EtaStatusTemplates> etaStatusByLocale = new HashMap<>();

  /** 交路停靠表里不随列车变化的部分，按交路缓存，见 {@link StopTable}。 */
  private final Map<RouteId, StopTable> stopTables = new HashMap<>();

  /**
   * 同一 tick 内的结果复用：三块 HUD 的刷新落在同一个 tick，同一列车上的乘客看的是同一份上下文与占位符。 tick 变了整体作废； tick 来源给负数时不复用。只在主线程使用。
   */
  private final LongSupplier tickSource;

  private long memoTick = Long.MIN_VALUE;
  private final Map<String, Optional<TrainHudContext>> contextMemo = new HashMap<>();
  private final Map<TrainHudContext, Map<String, String>> placeholderMemo = new IdentityHashMap<>();
  private final Map<TrainHudContext, List<StopInfo>> upcomingMemo = new IdentityHashMap<>();
  private final Map<TrainHudContext, Map<Integer, UpcomingStop>> upcomingStopMemo =
      new IdentityHashMap<>();

  private static final String ETA_STATUS_PREFIX = "display.hud.eta.status.";
  private static final EtaStatusTemplates DEFAULT_ETA_STATUS =
      new EtaStatusTemplates(
          "Arriving", "Delayed {minutes}m", "Scheduled {minutes}m", "{minutes}m", "-");

  public TrainHudContextResolver(
      FetaruteTCAddon plugin,
      LocaleManager locale,
      EtaService etaService,
      RouteDefinitionCache routeDefinitions,
      RouteProgressRegistry routeProgressRegistry,
      LayoverRegistry layoverRegistry,
      HudTemplateService templateService,
      Consumer<String> debugLogger) {
    this(
        plugin,
        locale,
        etaService,
        routeDefinitions,
        routeProgressRegistry,
        layoverRegistry,
        templateService,
        debugLogger,
        () -> -1L);
  }

  /**
   * 运行时用这个构造器传入服务器 tick（{@code Bukkit::getCurrentTick}），同一 tick 内复用结果；上面的构造器不复用。
   *
   * @param tickSource 当前 tick；负数表示不复用
   */
  public TrainHudContextResolver(
      FetaruteTCAddon plugin,
      LocaleManager locale,
      EtaService etaService,
      RouteDefinitionCache routeDefinitions,
      RouteProgressRegistry routeProgressRegistry,
      LayoverRegistry layoverRegistry,
      HudTemplateService templateService,
      Consumer<String> debugLogger,
      LongSupplier tickSource) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.locale = locale;
    this.etaService = Objects.requireNonNull(etaService, "etaService");
    this.routeDefinitions = Objects.requireNonNull(routeDefinitions, "routeDefinitions");
    this.routeProgressRegistry = Optional.ofNullable(routeProgressRegistry);
    this.layoverRegistry = Optional.ofNullable(layoverRegistry);
    this.templateService = templateService;
    this.debugLogger = debugLogger != null ? debugLogger : msg -> {};
    this.tickSource = Objects.requireNonNull(tickSource, "tickSource");
  }

  /** tick 变了（或取不到 tick）时作废同 tick 复用的结果。 */
  private void syncMemo() {
    long tick = tickSource.getAsLong();
    if (tick >= 0 && tick == memoTick) {
      return;
    }
    memoTick = tick;
    contextMemo.clear();
    placeholderMemo.clear();
    upcomingMemo.clear();
    upcomingStopMemo.clear();
  }

  /** 从玩家载具链路反查 TrainCarts 编组。 */
  public Optional<MinecartGroup> resolveGroup(Player player) {
    if (player == null) {
      return Optional.empty();
    }
    Entity vehicle = player.getVehicle();
    long hops = 0;
    while (vehicle != null && hops < VEHICLE_HOPS) {
      MinecartMember<?> member = MinecartMemberStore.getFromEntity(vehicle);
      if (member != null) {
        MinecartGroup group = member.getGroup();
        if (group != null && group.isValid()) {
          return Optional.of(group);
        }
      }
      vehicle = vehicle.getVehicle();
      hops++;
    }
    return Optional.empty();
  }

  /** 构造 HUD 上下文（若不是 FTA 管控列车则返回 empty）；同一 tick 内同一列车只算一次。 */
  public Optional<TrainHudContext> resolveContext(MinecartGroup group) {
    if (group == null) {
      return Optional.empty();
    }
    TrainProperties properties = group.getProperties();
    String trainName = properties == null ? null : properties.getTrainName();
    syncMemo();
    if (trainName == null || trainName.isBlank() || memoTick < 0) {
      return resolveContext(properties, group.isMoving(), resolveSpeedBlocksPerSecond(group));
    }
    return contextMemo.computeIfAbsent(
        trainName,
        ignored ->
            resolveContext(properties, group.isMoving(), resolveSpeedBlocksPerSecond(group)));
  }

  /**
   * 按列车属性构造 HUD 上下文；运动状态由调用方从 TrainCarts 编组取出。
   *
   * @param properties 列车属性（标签）
   * @param moving 是否在移动
   * @param speedBps 速度（blocks/s）
   */
  Optional<TrainHudContext> resolveContext(
      TrainProperties properties, boolean moving, double speedBps) {
    if (properties == null) {
      return Optional.empty();
    }
    String trainName = properties.getTrainName();
    if (trainName == null || trainName.isBlank()) {
      return Optional.empty();
    }

    Optional<RouteProgressRegistry.RouteProgressEntry> progressEntry =
        resolveProgressEntry(trainName);
    if (!isFtaManaged(properties, progressEntry)) {
      return Optional.empty();
    }

    Optional<Integer> knownRouteIndex =
        progressEntry
            .map(RouteProgressRegistry.RouteProgressEntry::currentIndex)
            .or(() -> TrainTagHelper.readIntTag(properties, RouteProgressRegistry.TAG_ROUTE_INDEX));
    int routeIndex = knownRouteIndex.orElse(0);
    Optional<RouteDefinition> routeOpt = resolveRouteDefinition(properties, progressEntry);
    List<RouteStop> routeStops =
        routeOpt.map(route -> routeDefinitions.listStops(route.id())).orElse(List.of());
    Optional<RouteLineChanges.LineRef> routeLine =
        routeOpt.flatMap(RouteDefinition::metadata).flatMap(RouteLineChanges.LineRef::of);
    // 直通运转：线路名、颜色、运营商与模板跟列车当前所属的线路走，而不是交路本身的线路。
    Optional<RouteLineChanges.LineRef> currentLine = resolveCurrentLine(properties, routeLine);
    Optional<HudTemplateService.LineInfo> lineInfo = currentLine.flatMap(this::resolveLineInfo);
    Optional<TrainHudContext.ThroughService> throughService =
        routeLine
            .flatMap(base -> RouteLineChanges.nextChangeAfter(routeStops, routeIndex, base))
            .map(change -> resolveThroughService(routeStops, change));
    Optional<RoutePatternType> routePatternType =
        resolveRoutePatternType(properties, progressEntry, routeOpt);
    Optional<RouteOperationType> operationType =
        resolveRouteOperationType(properties, progressEntry);
    boolean outOfService =
        routeOpt.isPresent()
            && RouteTerminals.outOfService(operationType.orElse(null), routeStops, routeIndex);
    // 越过运营终点后不再有载客停靠站：不能退回去显示已经过的终点站。
    Optional<NextStop> nextStopOpt =
        outOfService ? Optional.empty() : resolveNextStop(routeOpt, routeIndex);
    StationDisplay nextStation = nextStopOpt.map(NextStop::display).orElse(StationDisplay.empty());
    String nextStationTrack = nextStopOpt.map(next -> trackFor(trainName, next.info())).orElse("-");
    boolean terminalNextStop =
        nextStopOpt.map(NextStop::terminal).orElse(false) && !nextStation.isEmpty();
    Destinations destinations = resolveDestinations(routeOpt, routeIndex, operationType);

    EtaResult eta = etaService.getForTrain(trainName, EtaTarget.nextStop());
    List<TrainHudContext.Transfer> nextStopTransfers =
        nextStopOpt.map(next -> transfersOf(next.info(), currentLine)).orElse(List.of());
    OptionalLong nextStopDelay =
        nextStopOpt
            .filter(NextStop::ahead)
            .map(next -> etaService.arrivalDeviationSeconds(trainName, next.stopIndex()))
            .orElse(OptionalLong.empty());
    Optional<TrainRuntimeSnapshot> snapshotOpt = etaService.getRuntimeSnapshot(trainName);
    Optional<NodeId> currentNode = snapshotOpt.flatMap(TrainRuntimeSnapshot::currentNodeId);
    StationDisplay currentStation =
        currentNode.map(this::resolveStationDisplay).orElse(StationDisplay.empty());
    // “在站”不能只看停站计时：计时要等车停稳若干 tick 才开始，而进度在那之前就已推进到本站；
    // 计时到期后还要关门、过发车门控。两个空档里只看计时，HUD 会按“已推进的下一站”闪一下，
    // 或把站台上关门的车显示成临时停车。
    boolean dwelling =
        snapshotOpt
            .flatMap(TrainRuntimeSnapshot::dwellRemainingSec)
            .map(sec -> sec > 0)
            .orElse(false);
    // 在站判定要拿真实进度下标比；下标未知时 routeIndex 的 0 是补出来的，不能参与比较。
    boolean stop =
        dwelling
            || knownRouteIndex.map(index -> isAtStation(trainName, routeOpt, index)).orElse(false);
    SignalAspect signalAspect =
        progressEntry.map(RouteProgressRegistry.RouteProgressEntry::lastSignal).orElse(null);
    Optional<LayoverRegistry.LayoverCandidate> layover = resolveLayover(trainName);
    // 用 stop 匹配而不是节点字符串相等：DYNAMIC 终点的占位节点只是范围里的第一条股道，
    // 车停进别的股道时字符串永远不相等，终到提示就不会出现。
    Optional<RouteStop> eopStop = routeOpt.flatMap(this::resolveEndOfOperationStop);
    boolean atLastStation =
        stop
            && currentNode.isPresent()
            && eopStop.isPresent()
            && RouteTerminals.matches(currentNode.get(), eopStop.get());

    TrainHudContext context =
        new TrainHudContext(
            trainName,
            routeIndex,
            routeOpt,
            lineInfo,
            routePatternType,
            currentStation,
            nextStation,
            nextStationTrack,
            destinations,
            eta,
            signalAspect,
            layover,
            stop,
            moving,
            atLastStation,
            terminalNextStop,
            speedBps,
            currentLine,
            throughService,
            outOfService,
            nextStopTransfers,
            nextStopDelay,
            nextStopOpt
                .filter(NextStop::ahead)
                .map(next -> OptionalInt.of(next.stopIndex()))
                .orElse(OptionalInt.empty()));
    return Optional.of(context);
  }

  /**
   * 列车当前对乘客显示的线路（{@link RouteLineChanges#current}：线路标签优先，否则为交路本身的线路）。
   *
   * @return 交路线路与标签都不明时为空
   */
  private Optional<RouteLineChanges.LineRef> resolveCurrentLine(
      TrainProperties properties, Optional<RouteLineChanges.LineRef> routeLine) {
    Optional<RouteLineChanges.LineRef> lineTag =
        RouteLineChanges.LineRef.of(
            TrainTagHelper.readTagValue(properties, RouteProgressRegistry.TAG_OPERATOR_CODE)
                .orElse(null),
            TrainTagHelper.readTagValue(properties, RouteProgressRegistry.TAG_LINE_CODE)
                .orElse(null));
    return RouteLineChanges.current(lineTag, routeLine).map(this::canonicalLine);
  }

  /** 线路存在时换成主数据的写法（{@link StationDirectory.Snapshot#canonicalLine}）；没有车站目录时原样。 */
  private RouteLineChanges.LineRef canonicalLine(RouteLineChanges.LineRef line) {
    return plugin
        .getStationDirectory()
        .map(directory -> directory.snapshot().canonicalLine(line))
        .orElse(line);
  }

  private Optional<HudTemplateService.LineInfo> resolveLineInfo(RouteLineChanges.LineRef line) {
    return templateService == null
        ? Optional.empty()
        : templateService.resolveLineInfo(line.operatorCode(), line.lineCode());
  }

  private TrainHudContext.ThroughService resolveThroughService(
      List<RouteStop> stops, RouteLineChanges.Change change) {
    RouteStop stop = stops.get(change.index());
    StationDisplay station = resolveStopDisplay(stop, resolveStopNodeId(stop));
    RouteLineChanges.LineRef line = canonicalLine(change.to());
    return new TrainHudContext.ThroughService(station, line, resolveLineInfo(line));
  }

  /**
   * 停靠点可换乘的线路：停靠该站及同组车站的线路（{@link StationDirectory.Snapshot#linesServing}，与站台屏同一口径），
   * 去掉本车在这一站所属的线路——换线站以原线路到达、以新线路发车，两条都去掉。
   *
   * @param node 停靠点节点
   * @param stops 交路停靠表
   * @param stopIndex 停靠表下标
   * @param routeLine 交路自身的线路
   * @param currentLine 列车当前所属线路（交路线路不明时用它排除本线）
   * @return 可换乘线路；节点不是车站或没有车站目录时为空列表
   */
  private List<TrainHudContext.Transfer> resolveTransfers(
      Optional<NodeId> node,
      List<RouteStop> stops,
      int stopIndex,
      Optional<RouteLineChanges.LineRef> routeLine,
      Optional<RouteLineChanges.LineRef> currentLine) {
    Optional<StationDirectory.Snapshot> snapshot =
        node.isEmpty()
            ? Optional.empty()
            : plugin.getStationDirectory().map(StationDirectory::snapshot);
    if (snapshot.isEmpty()) {
      return List.of();
    }
    // 按该站所属线路排除：换线之后的站，列车离开的那条线在那里就是一条可换乘的线。
    List<RouteLineChanges.LineRef> own =
        routeLine
            .map(
                base ->
                    List.of(
                        RouteLineChanges.lineAt(stops, stopIndex, base),
                        RouteLineChanges.lineAt(stops, stopIndex - 1, base)))
            .orElseGet(() -> currentLine.map(List::of).orElse(List.of()));
    List<TrainHudContext.Transfer> transfers = new ArrayList<>();
    for (StationDirectory.ServingLineEntry entry :
        snapshot
            .get()
            .stationIdOfNode(node.get().value())
            .map(snapshot.get()::linesServing)
            .orElse(List.of())) {
      Line line = entry.line().line();
      RouteLineChanges.LineRef ref =
          new RouteLineChanges.LineRef(entry.line().operator().code(), line.code());
      if (own.stream().anyMatch(ref::sameLine)) {
        continue;
      }
      transfers.add(
          new TrainHudContext.Transfer(
              line.code(),
              line.name(),
              line.secondaryName().filter(name -> !name.isBlank()).orElse(line.name()),
              HudText.colorTag(entry.line().color().orElse(""))));
    }
    return List.copyOf(transfers);
  }

  /**
   * 前方停靠列表（车内显示屏、后续站点对话框）：前 {@code limit} 站带 ETA、晚点与换乘，{@code total} 为前方停靠站总数。
   *
   * <p>limit=0 仅返回 total 计数，不计算 ETA。
   */
  public UpcomingStops resolveUpcomingStops(TrainHudContext context, int limit) {
    List<StopInfo> ahead = upcomingStops(context);
    int count = Math.min(Math.max(0, limit), ahead.size());
    List<UpcomingStop> stops = new ArrayList<>(count);
    for (int position = 0; position < count; position++) {
      stops.add(upcomingStop(context, ahead, position));
    }
    return new UpcomingStops(stops, ahead.size());
  }

  /**
   * 列车前方各停靠站（能显示站名的停车点）的静态信息，不含 ETA。同一 tick 内同一上下文只算一次。
   *
   * @param context 上下文
   * @return 按停靠顺序；交路不明时为空列表
   */
  public List<StopInfo> upcomingStops(TrainHudContext context) {
    syncMemo();
    return upcomingMemo.computeIfAbsent(context, this::computeUpcomingStops);
  }

  private List<StopInfo> computeUpcomingStops(TrainHudContext context) {
    return context
        .routeDefinition()
        .flatMap(this::stopTable)
        .map(
            table ->
                table.infos().stream().filter(info -> info.index() > context.routeIndex()).toList())
        .orElse(List.of());
  }

  /**
   * 前方第 {@code position} 站（0 起，对应 {@link #upcomingStops} 的下标）的完整信息：ETA、晚点与换乘。 只在真正显示的行上计算，同一 tick
   * 内同一站只算一次。
   *
   * @param context 上下文
   * @param ahead {@link #upcomingStops} 的结果
   * @param position 前方第几站（0 起）
   */
  public UpcomingStop upcomingStop(TrainHudContext context, List<StopInfo> ahead, int position) {
    syncMemo();
    return upcomingStopMemo
        .computeIfAbsent(context, ignored -> new HashMap<>())
        .computeIfAbsent(
            position, ignored -> materialize(context, ahead.get(position), position + 1));
  }

  private UpcomingStop materialize(TrainHudContext context, StopInfo info, int sequence) {
    EtaResult eta =
        etaService.getForTrain(context.trainName(), new EtaTarget.StopIndex(info.index()));
    return new UpcomingStop(
        sequence,
        info.display(),
        eta,
        trackFor(context.trainName(), info),
        info.line(),
        transfersOf(info, context.currentLine()),
        etaService.arrivalDeviationSeconds(context.trainName(), info.index()),
        info.terminal());
  }

  /** 站台号：动态站台按这趟车的选台结果，尚未选台时为 {@code -}，不显示占位股道。 */
  private String trackFor(String trainName, StopInfo info) {
    if (!info.dynamic()) {
      return info.track();
    }
    return etaService
        .placedStopNode(trainName, info.index())
        .map(this::resolveTrackFromNodeId)
        .orElse("-");
  }

  /** 停靠站的换乘线路：交路线路已知时用预先算好的；不明时按列车当前线路排除本线。 */
  private List<TrainHudContext.Transfer> transfersOf(
      StopInfo info, Optional<RouteLineChanges.LineRef> currentLine) {
    return info.transfers()
        .orElseGet(
            () ->
                resolveTransfers(
                    info.nodeId(), List.of(), info.index(), Optional.empty(), currentLine));
  }

  /** 交路的静态停靠表。停靠表实例与车站目录版本都没变时复用：交路缓存刷新会换一份新的停靠表，车站、线路改库会让目录版本递增。 */
  private Optional<StopTable> stopTable(RouteDefinition route) {
    if (route == null || routeDefinitions == null) {
      return Optional.empty();
    }
    List<RouteStop> stops = routeDefinitions.listStops(route.id());
    if (stops.isEmpty()) {
      return Optional.empty();
    }
    long revision = plugin.getStationDirectory().map(StationDirectory::revision).orElse(-1L);
    StopTable cached = stopTables.get(route.id());
    if (cached != null && cached.stops() == stops && cached.directoryRevision() == revision) {
      return Optional.of(cached);
    }
    StopTable built = buildStopTable(route, stops, revision);
    stopTables.put(route.id(), built);
    return Optional.of(built);
  }

  private StopTable buildStopTable(RouteDefinition route, List<RouteStop> stops, long revision) {
    Optional<RouteLineChanges.LineRef> routeLine =
        route.metadata().flatMap(RouteLineChanges.LineRef::of);
    List<RouteLineChanges.LineRef> lines =
        routeLine.map(base -> RouteLineChanges.linesByIndex(stops, base)).orElse(List.of());
    int lastStopIndex = resolveLastStopIndex(stops);
    List<StopInfo> infos = new ArrayList<>();
    for (int index = 0; index < stops.size(); index++) {
      RouteStop stop = stops.get(index);
      if (stop == null || stop.passType() == RouteStopPassType.PASS) {
        continue;
      }
      Optional<NodeId> nodeId = resolveStopNodeId(stop);
      StationDisplay display = resolveStopDisplay(stop, nodeId);
      if (display.isEmpty()) {
        continue;
      }
      Optional<RouteLineChanges.LineRef> line =
          index < lines.size() ? Optional.of(lines.get(index)) : Optional.empty();
      Optional<List<TrainHudContext.Transfer>> transfers =
          routeLine.isPresent()
              ? Optional.of(resolveTransfers(nodeId, stops, index, routeLine, Optional.empty()))
              : Optional.empty();
      boolean dynamic = DynamicStopMatcher.isDynamicStop(stop);
      infos.add(
          new StopInfo(
              index,
              nodeId,
              display,
              dynamic ? "-" : nodeId.map(this::resolveTrackFromNodeId).orElse("-"),
              line,
              transfers,
              index == lastStopIndex,
              dynamic));
    }
    return new StopTable(stops, revision, List.copyOf(infos), lastStopIndex);
  }

  /**
   * 根据上下文生成模板占位符键值。不含进度的部分同一 tick 内同一上下文只算一次，返回的是可以自由改写的副本。
   *
   * @param context 上下文
   * @param progress 进度（各块 HUD 自己的进度跟踪结果）
   */
  public Map<String, String> buildPlaceholders(TrainHudContext context, float progress) {
    syncMemo();
    Map<String, String> placeholders =
        new HashMap<>(placeholderMemo.computeIfAbsent(context, this::basePlaceholders));
    applyProgressPlaceholders(placeholders, progress);
    return placeholders;
  }

  private Map<String, String> basePlaceholders(TrainHudContext context) {
    Map<String, String> placeholders = new HashMap<>(128);
    LocalTime now = LocalTime.now();
    String timeHhmm = now.format(CLOCK_MINUTES);
    String timeHhmmss = now.format(CLOCK_SECONDS);
    StationDisplay safeCurrent =
        context.currentStation() == null
            ? StationDisplay.empty()
            : context.currentStation().sanitized();
    StationDisplay safeNext =
        context.nextStation() == null ? StationDisplay.empty() : context.nextStation().sanitized();
    StationDisplay safeEor = context.destinations().eor().sanitized();
    StationDisplay safeEop = context.destinations().eop().sanitized();
    String etaMinutes =
        context.eta().etaMinutesRounded() >= 0
            ? String.valueOf(context.eta().etaMinutesRounded())
            : "-";
    String unit = localeTextOrDefault("display.hud.bossbar.unit.kmh", "km/h");
    String labelLine = localeTextOrDefault("display.hud.bossbar.label.line", "Line");
    String labelNext = localeTextOrDefault("display.hud.bossbar.label.next", "Next");
    String signalStatus = resolveSignalStatus(context.signalAspect());
    String signalColorTag = resolveSignalColorTag(context.signalAspect());
    String serviceStatus = resolveServiceStatus(context.layover());
    String lineCode = "-";
    String operatorCode = "-";
    String routeCode = "-";
    String routeId = "-";
    String routeName = "-";
    String routePattern = "-";
    if (context.routeDefinition() != null && context.routeDefinition().isPresent()) {
      RouteDefinition route = context.routeDefinition().get();
      routeId = route.id().toString();
      Optional<RouteMetadata> metaOpt = route.metadata();
      if (metaOpt.isPresent()) {
        RouteMetadata meta = metaOpt.get();
        operatorCode = meta.operator();
        lineCode = meta.lineId();
        routeCode = meta.serviceId();
        routeName = meta.displayName().filter(name -> !name.isBlank()).orElse(routeName);
      }
    }
    // 直通运转换线后，线路与运营商按列车当前所属的线路；交路代码、名称仍是交路本身的。
    if (context.currentLine().isPresent()) {
      operatorCode = context.currentLine().get().operatorCode();
      lineCode = context.currentLine().get().lineCode();
    }
    if (context.routePatternType() != null && context.routePatternType().isPresent()) {
      RoutePatternType patternType = context.routePatternType().get();
      routePattern = locale.enumText("enum.route-pattern-type", patternType);
    }

    CompanyDisplay company = resolveCompanyDisplay(operatorCode);

    placeholders.put("company", company.label());
    placeholders.put("company_code", company.code());
    placeholders.put("company_name", company.name());
    putLinePlaceholders(placeholders, "", context.lineInfo(), lineCode);
    placeholders.put("operator", safeOrDash(operatorCode));
    putThroughPlaceholders(placeholders, context.throughService());
    applyStopPlaceholders(
        placeholders, context.nextStopTransfers(), context.nextStopDelaySeconds());
    placeholders.put("route_code", safeOrDash(routeCode));
    placeholders.put("route_id", safeOrDash(routeId));
    placeholders.put("route_name", safeOrDash(routeName));
    placeholders.put("route_pattern", safeOrDash(routePattern));
    placeholders.putAll(resolvePatternLocalePlaceholders(context.routePatternType()));
    placeholders.put("current_station", safeCurrent.label());
    placeholders.put("current_station_code", safeCurrent.code());
    placeholders.put("current_station_lang2", safeCurrent.lang2());
    placeholders.put("next_station", safeNext.label());
    placeholders.put("next_station_code", safeNext.code());
    placeholders.put("next_station_lang2", safeNext.lang2());
    placeholders.put("next_station_track", context.nextStationTrack());
    placeholders.put("dest_eor", safeEor.label());
    placeholders.put("dest_eor_code", safeEor.code());
    placeholders.put("dest_eor_lang2", safeEor.lang2());
    placeholders.put("dest_eop", safeEop.label());
    placeholders.put("dest_eop_code", safeEop.code());
    placeholders.put("dest_eop_lang2", safeEop.lang2());
    applyEtaStatusPlaceholders(placeholders, context.eta());
    placeholders.put("eta_minutes", etaMinutes);
    placeholders.put("speed_kmh", formatSpeedValue(context.speedBps()));
    placeholders.put("speed_bps", formatSpeedBps(context.speedBps()));
    placeholders.put("speed_unit", unit);
    placeholders.put("speed", formatSpeed(context.speedBps()));
    placeholders.put(
        "signal_aspect",
        context.signalAspect() == null ? "UNKNOWN" : context.signalAspect().name());
    placeholders.put("signal_status", signalStatus);
    placeholders.put("signal_color_tag", signalColorTag);
    placeholders.put("service_status", serviceStatus);
    placeholders.put("train_name", context.trainName());
    placeholders.put("time_hhmm", timeHhmm);
    placeholders.put("time_hhmmss", timeHhmmss);
    placeholders.put("time_HHmm", timeHhmm);
    placeholders.put("time_HHmmSS", timeHhmmss);
    placeholders.put("label_line", labelLine);
    placeholders.put("label_next", labelNext);
    placeholders.put("layover_wait", "-");

    context
        .layover()
        .ifPresent(
            candidate ->
                placeholders.put(
                    "layover_wait",
                    formatDuration(Duration.between(candidate.readyAt(), Instant.now()))));

    return placeholders;
  }

  /**
   * 覆盖为某条线路的线路占位符（{@code line}、{@code line_lang2}、{@code line_code}、{@code line_name}、{@code
   * line_color}、{@code line_color_tag}）。
   *
   * <p>LCD 前方停靠列表里，直通运转换线之后的各站按该站所属线路着色。
   *
   * @param placeholders 占位符表
   * @param line 线路
   */
  public void applyLinePlaceholders(
      Map<String, String> placeholders, RouteLineChanges.LineRef line) {
    if (placeholders == null || line == null) {
      return;
    }
    putLinePlaceholders(placeholders, "", resolveLineInfo(line), line.lineCode());
  }

  /**
   * 写入一组线路占位符。
   *
   * @param prefix 键前缀：当前线路为空串，直通换线后的线路为 {@code through_}
   * @param info 线路元信息；线路不存在时为空
   * @param fallbackCode 没有线路元信息时的线路代码
   */
  private void putLinePlaceholders(
      Map<String, String> placeholders,
      String prefix,
      Optional<HudTemplateService.LineInfo> info,
      String fallbackCode) {
    String lineCode = fallbackCode;
    String lineName = "-";
    String lineLang2 = "-";
    String lineColor = "";
    if (info != null && info.isPresent()) {
      lineCode = info.get().code();
      lineName = info.get().name();
      lineLang2 = info.get().secondaryName();
      lineColor = info.get().color();
    }
    String lineColorTag = HudText.colorTag(lineColor);
    String lineLabel = resolveLineLabel(lineName, lineCode);
    String safeLine = lineLabel.isBlank() ? "-" : lineLabel;
    placeholders.put(prefix + "line", safeLine);
    placeholders.put(prefix + "line_lang2", resolveLineLang2(lineLang2, safeLine));
    placeholders.put(prefix + "line_code", safeOrDash(lineCode));
    placeholders.put(prefix + "line_name", safeOrDash(lineName));
    placeholders.put(prefix + "line_color", lineColor == null ? "" : lineColor);
    placeholders.put(prefix + "line_color_tag", lineColorTag);
  }

  /**
   * 直通运转占位符：前方下一次换线的车站与新线路（{@code through_station*}、{@code through_line*}、{@code
   * through_operator}）；没有换线时为 {@code -}（颜色为空、颜色标签为 {@code white}）。
   */
  private void putThroughPlaceholders(
      Map<String, String> placeholders, Optional<TrainHudContext.ThroughService> through) {
    if (through == null || through.isEmpty()) {
      putLinePlaceholders(placeholders, "through_", Optional.empty(), "-");
      placeholders.put("through_operator", "-");
      placeholders.put("through_station", "-");
      placeholders.put("through_station_code", "-");
      placeholders.put("through_station_lang2", "-");
      return;
    }
    TrainHudContext.ThroughService service = through.get();
    putLinePlaceholders(placeholders, "through_", service.lineInfo(), service.line().lineCode());
    placeholders.put("through_operator", safeOrDash(service.line().operatorCode()));
    StationDisplay station = service.station().sanitized();
    placeholders.put("through_station", station.label());
    placeholders.put("through_station_code", station.code());
    placeholders.put("through_station_lang2", station.lang2());
  }

  /**
   * 写入一个停靠站的换乘与晚点占位符：下一站（全局占位符）与前方停靠列表的每一行共用。
   *
   * <ul>
   *   <li>{@code transfer_lines}：换乘线路的色块 + 线路代码，如 {@code █DS █WS}
   *   <li>{@code transfer_line_names} / {@code transfer_line_names_lang2}：色块 + 线路名 / 第二语言名
   *   <li>{@code delay_minutes}：按表运行时晚点的分钟数（晚点达到 {@link Lateness#LATE_SECONDS} 才有值）
   * </ul>
   *
   * <p>缺失时都是 {@code -}，配合条件占位符 {@code {?key}} 可让整行只在有换乘、有晚点时显示。
   *
   * @param placeholders 占位符表
   * @param transfers 可换乘线路
   * @param delaySeconds 相对计划的偏差秒数；不按表运行时为空
   */
  public void applyStopPlaceholders(
      Map<String, String> placeholders,
      List<TrainHudContext.Transfer> transfers,
      OptionalLong delaySeconds) {
    placeholders.put("transfer_lines", transferChips(transfers, TrainHudContext.Transfer::code));
    placeholders.put(
        "transfer_line_names", transferChips(transfers, TrainHudContext.Transfer::name));
    placeholders.put(
        "transfer_line_names_lang2", transferChips(transfers, TrainHudContext.Transfer::lang2));
    placeholders.put(
        "delay_minutes",
        delaySeconds.isPresent() && Lateness.of(delaySeconds.getAsLong()) != Lateness.ON_TIME
            ? String.valueOf(Lateness.minutes(delaySeconds.getAsLong()))
            : HudText.MISSING);
  }

  /**
   * 前方停靠列表一行的占位符：在全局占位符之上覆盖该站的站名、站台、ETA、换乘、晚点与所属线路。
   *
   * <p>车内显示屏的列表行与后续站点对话框共用。线路占位符（{@code line}、{@code line_color_tag} 等）取该站所属线路：
   * 直通运转换线之后的各站按新线路着色，与列车当前线路相同的站不覆盖。
   *
   * @param base 全局占位符（不修改）
   * @param stop 停靠站；为空表示列表补位的空行
   * @param sequence 行序号（从 1 起）
   * @param currentLine 列车当前所属线路
   * @return 新的占位符表
   */
  public Map<String, String> stopPlaceholders(
      Map<String, String> base,
      Optional<UpcomingStop> stop,
      int sequence,
      Optional<RouteLineChanges.LineRef> currentLine) {
    Map<String, String> placeholders = new HashMap<>(base);
    if (stop.isEmpty()) {
      placeholders.put("idx", "");
      placeholders.put("index", "");
      placeholders.put("station", HudText.MISSING);
      placeholders.put("station_code", HudText.MISSING);
      placeholders.put("station_lang2", HudText.MISSING);
      placeholders.put("station_track", HudText.MISSING);
      placeholders.put("eta", "");
      placeholders.put("eta_minutes", HudText.MISSING);
      applyEtaStatusPlaceholders(placeholders, null);
      applyStopPlaceholders(placeholders, List.of(), OptionalLong.empty());
      return placeholders;
    }
    UpcomingStop row = stop.get();
    String seq = String.valueOf(sequence);
    placeholders.put("idx", seq);
    placeholders.put("index", seq);
    placeholders.put("station", row.display().label());
    placeholders.put("station_code", row.display().code());
    placeholders.put("station_lang2", row.display().lang2());
    placeholders.put("station_track", row.track());
    String status = formatEtaStatus(row.eta());
    placeholders.put("eta", status == null || status.isBlank() ? HudText.MISSING : status);
    int minutes = row.eta().etaMinutesRounded();
    placeholders.put("eta_minutes", minutes >= 0 ? String.valueOf(minutes) : HudText.MISSING);
    applyEtaStatusPlaceholders(placeholders, row.eta());
    applyStopPlaceholders(placeholders, row.transfers(), row.delaySeconds());
    row.line()
        .filter(line -> currentLine.map(current -> !current.sameLine(line)).orElse(true))
        .ifPresent(line -> applyLinePlaceholders(placeholders, line));
    return placeholders;
  }

  /**
   * 换乘线路写成 MiniMessage：每条线一个线路色色块紧跟文字，线路之间空一格；没有换乘时为 {@code -}。
   *
   * @param transfers 可换乘线路
   * @param label 每条线显示的文字（代码、线路名等）
   */
  public static String transferChips(
      List<TrainHudContext.Transfer> transfers, Function<TrainHudContext.Transfer, String> label) {
    if (transfers == null || transfers.isEmpty()) {
      return HudText.MISSING;
    }
    StringBuilder out = new StringBuilder();
    for (TrainHudContext.Transfer transfer : transfers) {
      if (!out.isEmpty()) {
        out.append(' ');
      }
      out.append('<')
          .append(transfer.colorTag())
          .append(">█</")
          .append(transfer.colorTag())
          .append('>')
          .append(HudText.escape(label.apply(transfer)));
    }
    return out.toString();
  }

  /**
   * 注入玩家侧占位符：车厢号/编组总数，与后续站点对话框的入口按键 {@code trip_dialog_key}。
   *
   * <p>三块 HUD 共用，避免重复实现；非列车或未在编组内时输出 {@code "-"}。{@code trip_dialog_key} 只在玩家还没打开过对话框时有值（见 {@link
   * org.fetarute.fetaruteTCAddon.display.hud.trip.TripDialogService#hintKey}），配合条件占位符做一次性的入口提示。
   */
  public void applyPlayerPlaceholders(
      Map<String, String> placeholders, Player player, MinecartGroup group) {
    if (placeholders == null) {
      return;
    }
    int carriageNo = resolvePlayerCarriageNo(player, group);
    int carriageTotal = group == null ? 0 : group.size();
    placeholders.put("player_carriage_no", carriageNo > 0 ? String.valueOf(carriageNo) : "-");
    placeholders.put(
        "player_carriage_total", carriageTotal > 0 ? String.valueOf(carriageTotal) : "-");
    placeholders.put(
        "trip_dialog_key",
        plugin
            .getDisplayService()
            .flatMap(DisplayService::tripDialog)
            .flatMap(dialog -> dialog.hintKey(player))
            .orElse(HudText.MISSING));
  }

  /** 注入 ETA 状态占位符（含当前语言与指定语言版本）。 */
  public void applyEtaStatusPlaceholders(Map<String, String> placeholders, EtaResult eta) {
    if (placeholders == null) {
      return;
    }
    String localeTag = locale == null ? null : locale.getCurrentLocale();
    placeholders.put("eta_status", formatEtaStatus(eta, localeTag));
    placeholders.putAll(resolveEtaStatusLocalePlaceholders(eta));
  }

  /** 使用当前语言渲染 ETA 状态文本。 */
  public String formatEtaStatus(EtaResult eta) {
    String localeTag = locale == null ? null : locale.getCurrentLocale();
    return formatEtaStatus(eta, localeTag);
  }

  /** 使用指定语言渲染 ETA 状态文本。 */
  public String formatEtaStatus(EtaResult eta, String localeTag) {
    EtaStatusView view = resolveEtaStatusView(eta);
    if (view.kind() == EtaStatusKind.RAW) {
      return view.raw();
    }
    EtaStatusTemplates templates = resolveEtaStatusTemplates(localeTag);
    String rendered = renderEtaStatus(view, templates);
    if (rendered != null && !rendered.isBlank()) {
      return rendered;
    }
    if (view.raw() != null && !view.raw().isBlank()) {
      return view.raw();
    }
    if (view.minutes() >= 0) {
      return view.minutes() + "m";
    }
    return "-";
  }

  public void applyProgressPlaceholders(Map<String, String> placeholders, float progress) {
    placeholders.put("progress", formatProgressValue(progress));
    placeholders.put("progress_percent", formatPercent(progress));
  }

  public String localeTextOrDefault(String key, String fallback) {
    if (locale == null) {
      return fallback;
    }
    String value = locale.text(key);
    return value.equals(key) ? fallback : value;
  }

  private Set<String> resolveLocaleCandidates() {
    Set<String> locales = new LinkedHashSet<>();
    if (locale != null) {
      String current = locale.getCurrentLocale();
      if (current != null && !current.isBlank()) {
        locales.add(current);
      }
      locales.addAll(locale.availableLocales());
    }
    locales.addAll(DEFAULT_LOCALE_TAGS);
    return locales;
  }

  private Map<String, String> resolveEtaStatusLocalePlaceholders(EtaResult eta) {
    Set<String> locales = resolveLocaleCandidates();
    for (String candidate : locales) {
      ensureEtaLocale(candidate);
    }
    Map<String, String> placeholders = new HashMap<>();
    for (String localeKey : locales) {
      if (localeKey == null || localeKey.isBlank()) {
        continue;
      }
      placeholders.put("eta_status_" + localeKey, formatEtaStatus(eta, localeKey));
    }
    return placeholders;
  }

  private EtaStatusTemplates resolveEtaStatusTemplates(String localeTag) {
    if (localeTag == null || localeTag.isBlank()) {
      return DEFAULT_ETA_STATUS;
    }
    ensureEtaLocale(localeTag);
    return etaStatusByLocale.getOrDefault(localeTag, DEFAULT_ETA_STATUS);
  }

  private void ensureEtaLocale(String localeTag) {
    if (localeTag == null || localeTag.isBlank() || etaStatusByLocale.containsKey(localeTag)) {
      return;
    }
    EtaStatusTemplates templates = DEFAULT_ETA_STATUS;
    try {
      File langDir = new File(plugin.getDataFolder(), "lang");
      File file = new File(langDir, localeTag + ".yml");
      if (!file.exists()) {
        try (InputStream stream =
            LocaleManager.class
                .getClassLoader()
                .getResourceAsStream("lang/" + localeTag + ".yml")) {
          if (stream == null) {
            etaStatusByLocale.put(localeTag, templates);
            return;
          }
          YamlConfiguration config =
              YamlConfiguration.loadConfiguration(
                  new InputStreamReader(stream, StandardCharsets.UTF_8));
          templates = loadEtaStatusTemplates(config);
          etaStatusByLocale.put(localeTag, templates);
          return;
        }
      }
      YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
      templates = loadEtaStatusTemplates(config);
    } catch (IOException | RuntimeException ex) {
      debugLogger.accept("HUD eta_status locale 加载失败: " + ex.getMessage());
    }
    etaStatusByLocale.put(localeTag, templates);
  }

  private EtaStatusTemplates loadEtaStatusTemplates(YamlConfiguration config) {
    if (config == null) {
      return DEFAULT_ETA_STATUS;
    }
    String arriving =
        readEtaTemplate(config, ETA_STATUS_PREFIX + "arriving", DEFAULT_ETA_STATUS.arriving());
    String delayed =
        readEtaTemplate(config, ETA_STATUS_PREFIX + "delayed", DEFAULT_ETA_STATUS.delayed());
    String scheduled =
        readEtaTemplate(config, ETA_STATUS_PREFIX + "scheduled", DEFAULT_ETA_STATUS.scheduled());
    String minutes =
        readEtaTemplate(config, ETA_STATUS_PREFIX + "minutes", DEFAULT_ETA_STATUS.minutes());
    String unavailable =
        readEtaTemplate(
            config, ETA_STATUS_PREFIX + "unavailable", DEFAULT_ETA_STATUS.unavailable());
    return new EtaStatusTemplates(arriving, delayed, scheduled, minutes, unavailable);
  }

  private String readEtaTemplate(YamlConfiguration config, String key, String fallback) {
    String value = config.getString(key);
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return value;
  }

  private String renderEtaStatus(EtaStatusView view, EtaStatusTemplates templates) {
    if (view == null) {
      return "-";
    }
    if (view.kind() == EtaStatusKind.UNAVAILABLE) {
      return templates.unavailable();
    }
    if (view.kind() == EtaStatusKind.ARRIVING) {
      return templates.arriving();
    }
    if (view.kind() == EtaStatusKind.DELAYED) {
      return view.minutes() >= 0 ? replaceMinutes(templates.delayed(), view.minutes()) : view.raw();
    }
    if (view.kind() == EtaStatusKind.SCHEDULED) {
      return view.minutes() >= 0
          ? replaceMinutes(templates.scheduled(), view.minutes())
          : view.raw();
    }
    if (view.kind() == EtaStatusKind.MINUTES) {
      return view.minutes() >= 0 ? replaceMinutes(templates.minutes(), view.minutes()) : view.raw();
    }
    return view.raw();
  }

  private String replaceMinutes(String template, int minutes) {
    if (template == null || template.isBlank()) {
      return "";
    }
    return template.replace("{minutes}", String.valueOf(minutes));
  }

  private EtaStatusView resolveEtaStatusView(EtaResult eta) {
    if (eta == null) {
      return new EtaStatusView(EtaStatusKind.UNAVAILABLE, -1, "-");
    }
    String raw = eta.statusText();
    raw = raw == null ? "" : raw.trim();
    if (raw.isEmpty()) {
      return eta.etaMinutesRounded() >= 0
          ? new EtaStatusView(EtaStatusKind.MINUTES, eta.etaMinutesRounded(), raw)
          : new EtaStatusView(EtaStatusKind.UNAVAILABLE, -1, "-");
    }
    if ("-".equals(raw) || "N/A".equalsIgnoreCase(raw)) {
      return new EtaStatusView(EtaStatusKind.UNAVAILABLE, -1, raw);
    }
    String lower = raw.toLowerCase(Locale.ROOT);
    if ("arriving".equals(lower)) {
      return new EtaStatusView(EtaStatusKind.ARRIVING, -1, raw);
    }
    if (lower.startsWith("delayed")) {
      return new EtaStatusView(EtaStatusKind.DELAYED, parseMinutes(raw, eta), raw);
    }
    if (lower.startsWith("scheduled")) {
      return new EtaStatusView(EtaStatusKind.SCHEDULED, parseMinutes(raw, eta), raw);
    }
    if (lower.endsWith("m")) {
      int minutes = parseMinutes(raw, eta);
      if (minutes >= 0) {
        return new EtaStatusView(EtaStatusKind.MINUTES, minutes, raw);
      }
    }
    if (eta.etaMinutesRounded() >= 0) {
      return new EtaStatusView(EtaStatusKind.MINUTES, eta.etaMinutesRounded(), raw);
    }
    return new EtaStatusView(EtaStatusKind.RAW, -1, raw);
  }

  private int parseMinutes(String raw, EtaResult eta) {
    if (raw == null) {
      return eta == null ? -1 : eta.etaMinutesRounded();
    }
    StringBuilder digits = new StringBuilder();
    for (int i = 0; i < raw.length(); i++) {
      char ch = raw.charAt(i);
      if (ch >= '0' && ch <= '9') {
        digits.append(ch);
      }
    }
    if (digits.length() == 0) {
      return eta == null ? -1 : eta.etaMinutesRounded();
    }
    try {
      return Integer.parseInt(digits.toString());
    } catch (NumberFormatException ex) {
      return eta == null ? -1 : eta.etaMinutesRounded();
    }
  }

  private boolean isFtaManaged(
      TrainProperties properties,
      Optional<RouteProgressRegistry.RouteProgressEntry> progressEntry) {
    if (progressEntry != null && progressEntry.isPresent()) {
      return true;
    }
    if (properties == null) {
      return false;
    }
    return TrainTagHelper.readTagValue(properties, RouteProgressRegistry.TAG_ROUTE_ID).isPresent();
  }

  private Optional<RouteDefinition> resolveRouteDefinition(
      TrainProperties properties,
      Optional<RouteProgressRegistry.RouteProgressEntry> progressEntry) {
    if (properties == null || routeDefinitions == null) {
      return Optional.empty();
    }
    if (progressEntry != null) {
      Optional<UUID> routeUuid =
          progressEntry.map(RouteProgressRegistry.RouteProgressEntry::routeUuid);
      if (routeUuid.isPresent()) {
        return routeDefinitions.findById(routeUuid.get());
      }
    }
    Optional<UUID> routeUuid =
        TrainTagHelper.readTagValue(properties, RouteProgressRegistry.TAG_ROUTE_ID)
            .flatMap(TrainHudContextResolver::parseUuid);
    if (routeUuid.isEmpty()) {
      return Optional.empty();
    }
    return routeDefinitions.findById(routeUuid.get());
  }

  /**
   * 解析列车当前交路的种别（各站停/快速/特快…），取自交路缓存里的 {@code pattern_type}。
   *
   * <p>不读 {@code FTA_PATTERN} 标签：它是出车时写下的，折返复用换交路时不一定同步，快速交路的车全靠复用接班，读标签就会一直显示出车时那条交路的种别。缓存随交路重载刷新，
   * 管理员改了种别不必再等 HUD 自己的缓存过期。
   */
  private Optional<RoutePatternType> resolveRoutePatternType(
      TrainProperties properties,
      Optional<RouteProgressRegistry.RouteProgressEntry> progressEntry,
      Optional<RouteDefinition> routeOpt) {
    if (routeOpt == null || routeOpt.isEmpty()) {
      return Optional.empty();
    }
    Optional<UUID> routeId =
        progressEntry != null
            ? progressEntry.map(RouteProgressRegistry.RouteProgressEntry::routeUuid)
            : Optional.empty();
    if (routeId.isEmpty() && properties != null) {
      routeId =
          TrainTagHelper.readTagValue(properties, RouteProgressRegistry.TAG_ROUTE_ID)
              .flatMap(TrainHudContextResolver::parseUuid);
    }
    return routeId
        .flatMap(routeDefinitions::findRecord)
        .map(RouteDefinitionCache.RouteRecord::route)
        .map(Route::patternType);
  }

  private Map<String, String> resolvePatternLocalePlaceholders(
      Optional<RoutePatternType> patternOpt) {
    if (patternOpt == null || patternOpt.isEmpty()) {
      return Map.of();
    }
    RoutePatternType pattern = patternOpt.get();
    Set<String> locales = resolveLocaleCandidates();
    for (String candidate : locales) {
      ensurePatternLocale(candidate);
    }
    Map<String, String> placeholders = new HashMap<>();
    for (Map.Entry<String, Map<RoutePatternType, String>> entry : patternTextByLocale.entrySet()) {
      String localeKey = entry.getKey();
      if (localeKey == null || localeKey.isBlank()) {
        continue;
      }
      String text = entry.getValue().getOrDefault(pattern, pattern.name());
      placeholders.put("route_pattern_" + localeKey, text);
    }
    return placeholders;
  }

  private void ensurePatternLocale(String localeTag) {
    if (localeTag == null || localeTag.isBlank() || patternTextByLocale.containsKey(localeTag)) {
      return;
    }
    Map<RoutePatternType, String> mapping = new EnumMap<>(RoutePatternType.class);
    try {
      File langDir = new File(plugin.getDataFolder(), "lang");
      File file = new File(langDir, localeTag + ".yml");
      if (!file.exists()) {
        try (InputStream stream =
            LocaleManager.class
                .getClassLoader()
                .getResourceAsStream("lang/" + localeTag + ".yml")) {
          if (stream == null) {
            patternTextByLocale.put(localeTag, mapping);
            return;
          }
          YamlConfiguration config =
              YamlConfiguration.loadConfiguration(
                  new InputStreamReader(stream, StandardCharsets.UTF_8));
          loadPatternMapping(config, mapping);
          patternTextByLocale.put(localeTag, mapping);
          return;
        }
      }
      YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
      loadPatternMapping(config, mapping);
    } catch (IOException | RuntimeException ex) {
      debugLogger.accept("HUD route_pattern locale 加载失败: " + ex.getMessage());
    }
    patternTextByLocale.put(localeTag, mapping);
  }

  private void loadPatternMapping(YamlConfiguration config, Map<RoutePatternType, String> mapping) {
    if (config == null || mapping == null) {
      return;
    }
    String prefix = "enum.route-pattern-type.";
    for (RoutePatternType type : RoutePatternType.values()) {
      String key = prefix + type.name().toLowerCase(Locale.ROOT);
      String value = config.getString(key);
      if (value == null || value.isBlank()) {
        value = type.name();
      }
      mapping.put(type, value);
    }
  }

  private enum EtaStatusKind {
    ARRIVING,
    DELAYED,
    SCHEDULED,
    MINUTES,
    UNAVAILABLE,
    RAW
  }

  private record EtaStatusView(EtaStatusKind kind, int minutes, String raw) {
    private EtaStatusView {
      kind = kind == null ? EtaStatusKind.UNAVAILABLE : kind;
      raw = raw == null ? "" : raw;
    }
  }

  private record EtaStatusTemplates(
      String arriving, String delayed, String scheduled, String minutes, String unavailable) {}

  private Optional<NextStop> resolveNextStop(Optional<RouteDefinition> routeOpt, int routeIndex) {
    if (routeOpt == null || routeOpt.isEmpty()) {
      return Optional.empty();
    }
    RouteDefinition route = routeOpt.get();
    if (route.waypoints() == null || route.waypoints().isEmpty()) {
      return Optional.empty();
    }
    Optional<StopTable> table = stopTable(route);
    if (table.isEmpty() || table.get().infos().isEmpty()) {
      return Optional.empty();
    }
    // 停靠表与 waypoints 下标对齐：下一站就是进度下标之后第一个可显示的停车点。
    List<StopInfo> infos = table.get().infos();
    for (StopInfo info : infos) {
      if (info.index() > routeIndex) {
        return Optional.of(new NextStop(info, true));
      }
    }
    StopInfo last = infos.get(infos.size() - 1);
    return last.index() == table.get().lastStopIndex()
        ? Optional.of(new NextStop(last, false))
        : Optional.empty();
  }

  private int resolveLastStopIndex(List<RouteStop> stops) {
    if (stops == null || stops.isEmpty()) {
      return -1;
    }
    for (int i = stops.size() - 1; i >= 0; i--) {
      RouteStop stop = stops.get(i);
      if (stop == null || stop.passType() == RouteStopPassType.PASS) {
        continue;
      }
      return i;
    }
    return -1;
  }

  /**
   * 解析 RouteStop 对应的 NodeId。
   *
   * <p>支持三种来源：
   *
   * <ul>
   *   <li>直接的 waypointNodeId
   *   <li>关联的 stationId 查询
   *   <li>DYNAMIC 规范中提取（从 notes）
   * </ul>
   */
  private Optional<NodeId> resolveStopNodeId(RouteStop stop) {
    if (stop == null) {
      return Optional.empty();
    }
    if (stop.waypointNodeId().isPresent()) {
      return Optional.of(NodeId.of(stop.waypointNodeId().get()));
    }
    if (stop.stationId().isPresent()) {
      return resolveStationNodeId(stop.stationId().get());
    }
    // 支持 DYNAMIC 规范：从 notes 中解析（例如 TERM DYNAMIC:OP:S:STATION:[1:2]）
    Optional<DynamicStopMatcher.DynamicSpec> dynamicSpec =
        DynamicStopMatcher.parseDynamicSpec(stop);
    if (dynamicSpec.isPresent()) {
      DynamicStopMatcher.DynamicSpec spec = dynamicSpec.get();
      // 使用 placeholder nodeId（取 fromTrack 作为默认轨道）
      return Optional.of(NodeId.of(spec.toPlaceholderNodeId()));
    }
    return Optional.empty();
  }

  /**
   * 解析 RouteStop 的显示信息。
   *
   * <p>支持三种来源（按优先级）：
   *
   * <ul>
   *   <li>关联的 stationId 查询
   *   <li>直接的 nodeIdOpt 解析
   *   <li>DYNAMIC 规范中提取（当 nodeIdOpt 为空时回退）
   * </ul>
   */
  private StationDisplay resolveStopDisplay(RouteStop stop, Optional<NodeId> nodeIdOpt) {
    if (stop == null) {
      return StationDisplay.empty();
    }
    if (stop.stationId().isPresent()) {
      StationDisplay display = resolveStationDisplay(stop.stationId().get());
      if (!display.isEmpty()) {
        return display;
      }
    }
    // 优先使用传入的 nodeIdOpt（可能已从 DYNAMIC 解析）
    if (nodeIdOpt.isPresent()) {
      Optional<WaypointMetadata> metaOpt = parseWaypointMetadata(nodeIdOpt.get());
      if (metaOpt.isPresent()) {
        WaypointMetadata meta = metaOpt.get();
        if (meta.kind() != WaypointKind.STATION && meta.kind() != WaypointKind.STATION_THROAT) {
          // 非站点类型但有 DYNAMIC 时继续尝试
          Optional<DynamicStopMatcher.DynamicSpec> dynamicSpec =
              DynamicStopMatcher.parseDynamicSpec(stop);
          if (dynamicSpec.isEmpty()) {
            return StationDisplay.empty();
          }
        }
      }
      StationDisplay display = resolveStationDisplay(nodeIdOpt.get());
      if (!display.isEmpty()) {
        return display;
      }
    }
    // 回退：尝试从 DYNAMIC 规范解析（若 nodeIdOpt 为空或解析失败）
    Optional<DynamicStopMatcher.DynamicSpec> dynamicSpec =
        DynamicStopMatcher.parseDynamicSpec(stop);
    if (dynamicSpec.isPresent() && dynamicSpec.get().isStation()) {
      DynamicStopMatcher.DynamicSpec spec = dynamicSpec.get();
      String candidate = spec.toPlaceholderNodeId();
      return resolveStationDisplay(NodeId.of(candidate));
    }
    return StationDisplay.empty();
  }

  /**
   * 获取列车实际速度（blocks per second）。
   *
   * <p>使用实体的物理速度（velocity）而非 TrainCarts 的 getRealSpeed()， 因为后者在 launch 期间会返回目标速度而非实际速度。
   * 再与限速取小：TrainCarts 用限速截速时只截每步位移、不缩短速度向量，制动途中向量仍是制动前的速度，直接显示会比实际快。
   */
  private double resolveSpeedBlocksPerSecond(MinecartGroup group) {
    if (group == null || group.head() == null) {
      return 0.0;
    }
    MinecartMember<?> head = group.head();
    Entity entity = head.getEntity() != null ? head.getEntity().getEntity() : null;
    if (entity == null) {
      return 0.0;
    }
    // velocity 是每 tick 的移动向量，length() 得到标量速度 (blocks/tick)
    org.bukkit.util.Vector velocity = entity.getVelocity();
    if (velocity == null) {
      return 0.0;
    }
    double bpt = velocity.length();
    TrainProperties properties = group.getProperties();
    if (properties != null && Double.isFinite(properties.getSpeedLimit())) {
      bpt = Math.min(bpt, Math.max(0.0, properties.getSpeedLimit()));
    }
    return bpt * 20.0; // blocks/tick → blocks/second
  }

  private static Optional<UUID> parseUuid(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(raw.trim()));
    } catch (IllegalArgumentException ex) {
      return Optional.empty();
    }
  }

  private String formatSpeed(double blocksPerSecond) {
    String unit = localeTextOrDefault("display.hud.bossbar.unit.kmh", "km/h");
    return formatSpeedValue(blocksPerSecond) + " " + unit;
  }

  private String formatSpeedValue(double blocksPerSecond) {
    double kmh = blocksPerSecond * 3.6;
    return String.format(java.util.Locale.ROOT, "%.1f", kmh);
  }

  private String formatSpeedBps(double blocksPerSecond) {
    return String.format(java.util.Locale.ROOT, "%.2f", blocksPerSecond);
  }

  private String formatPercent(float progress) {
    int percent = Math.round(progress * 100.0f);
    if (percent < 0) {
      percent = 0;
    } else if (percent > 100) {
      percent = 100;
    }
    return String.valueOf(percent);
  }

  private Optional<RouteProgressRegistry.RouteProgressEntry> resolveProgressEntry(
      String trainName) {
    if (trainName == null || trainName.isBlank()) {
      return Optional.empty();
    }
    return routeProgressRegistry.flatMap(registry -> registry.get(trainName));
  }

  private Optional<LayoverRegistry.LayoverCandidate> resolveLayover(String trainName) {
    if (trainName == null || trainName.isBlank()) {
      return Optional.empty();
    }
    return layoverRegistry.flatMap(registry -> registry.get(trainName));
  }

  private String resolveLineLabel(String lineName, String lineCode) {
    if (lineName != null && !lineName.isBlank()) {
      return lineName;
    }
    if (lineCode != null && !lineCode.isBlank()) {
      return lineCode;
    }
    return "-";
  }

  /** 解析线路第二语言展示名；缺省时回退到主线路标签，保持 ActionBar/BossBar 输出连贯。 */
  private String resolveLineLang2(String lineLang2, String fallback) {
    if (lineLang2 != null && !lineLang2.isBlank()) {
      return lineLang2;
    }
    if (fallback != null && !fallback.isBlank()) {
      return fallback;
    }
    return "-";
  }

  private int resolvePlayerCarriageNo(Player player, MinecartGroup group) {
    if (player == null || group == null) {
      return 0;
    }
    MinecartMember<?> member = resolveMember(player);
    if (member == null || member.getGroup() != group) {
      return 0;
    }
    int index = group.indexOf(member);
    if (index < 0) {
      return 0;
    }
    return index + 1;
  }

  private MinecartMember<?> resolveMember(Player player) {
    if (player == null) {
      return null;
    }
    Entity vehicle = player.getVehicle();
    long hops = 0;
    while (vehicle != null && hops < VEHICLE_HOPS) {
      MinecartMember<?> member = MinecartMemberStore.getFromEntity(vehicle);
      if (member != null) {
        return member;
      }
      vehicle = vehicle.getVehicle();
      hops++;
    }
    return null;
  }

  private String resolveSignalStatus(SignalAspect aspect) {
    if (aspect == null) {
      return localeTextOrDefault("display.hud.bossbar.signal.unknown", "-");
    }
    return switch (aspect) {
      case PROCEED -> localeTextOrDefault("display.hud.bossbar.signal.proceed", "PROCEED");
      case PROCEED_WITH_CAUTION -> localeTextOrDefault(
          "display.hud.bossbar.signal.proceed_with_caution", "CAUTION");
      case CAUTION -> localeTextOrDefault("display.hud.bossbar.signal.caution", "CAUTION");
      case STOP -> localeTextOrDefault("display.hud.bossbar.signal.stop", "STOP");
    };
  }

  /**
   * 根据信号等级返回 MiniMessage 颜色标签。
   *
   * <p>用于 HUD 模板中的动态颜色显示：{@code <{signal_color_tag}>●</{signal_color_tag}>}。
   */
  private String resolveSignalColorTag(SignalAspect aspect) {
    if (aspect == null) {
      return "gray";
    }
    return switch (aspect) {
      case PROCEED -> "green";
      case PROCEED_WITH_CAUTION -> "yellow";
      case CAUTION -> "gold";
      case STOP -> "red";
    };
  }

  private String resolveServiceStatus(Optional<LayoverRegistry.LayoverCandidate> layover) {
    if (layover != null && layover.isPresent()) {
      return localeTextOrDefault("display.hud.bossbar.service.layover", "LAYOVER");
    }
    return localeTextOrDefault("display.hud.bossbar.service.in_service", "IN SERVICE");
  }

  private String formatDuration(Duration duration) {
    if (duration == null || duration.isNegative()) {
      return "0m";
    }
    long minutes = Math.max(0L, duration.toMinutes());
    return minutes + "m";
  }

  private StationDisplay resolveStationDisplay(NodeId nodeId) {
    if (nodeId == null || nodeId.value() == null || nodeId.value().isBlank()) {
      return StationDisplay.empty();
    }
    Optional<StationKey> keyOpt = resolveStationKey(nodeId);
    if (keyOpt.isPresent()) {
      StationKey key = keyOpt.get();
      StationDisplay resolved = resolveStationDisplay(key);
      if (!resolved.isEmpty()) {
        return resolved;
      }
      return StationDisplay.of(key.station(), key.station(), "-");
    }
    String label = HudWaypointLabel.stationLabel(nodeId);
    return StationDisplay.of(label, "-", "-");
  }

  /** 运营商代码所属公司，查车站目录（与车站、线路查找同一套运营商代码口径）。 */
  private CompanyDisplay resolveCompanyDisplay(String operatorCode) {
    if (operatorCode == null || operatorCode.isBlank()) {
      return CompanyDisplay.empty();
    }
    return directory()
        .flatMap(snapshot -> snapshot.companyOfOperator(operatorCode))
        .map(CompanyDisplay::fromCompany)
        .orElse(CompanyDisplay.empty());
  }

  /**
   * 车站目录的当前快照。HUD 每 tick 为每位乘客解析上下文，车站、运营商、公司都查这份内存目录，不读库； 改库命令会刷新目录（{@code
   * FetaruteTCAddon#refreshStationDirectory}）。
   */
  private Optional<StationDirectory.Snapshot> directory() {
    return plugin.getStationDirectory().map(StationDirectory::snapshot);
  }

  private StationDisplay resolveStationDisplay(UUID stationId) {
    if (stationId == null) {
      return StationDisplay.empty();
    }
    return directory()
        .flatMap(snapshot -> snapshot.station(stationId))
        .map(entry -> StationDisplay.fromStation(entry.station()))
        .orElse(StationDisplay.empty());
  }

  /** 按运营商代码 + 站码找车站：与公开 API 同一个索引、同一套运营商代码口径，两边不会给出不同的车站。 */
  private StationDisplay resolveStationDisplay(StationKey key) {
    if (key == null) {
      return StationDisplay.empty();
    }
    return directory()
        .flatMap(snapshot -> snapshot.findStation(key.operator(), key.station()))
        .map(entry -> StationDisplay.fromStation(entry.station()))
        .orElse(StationDisplay.empty());
  }

  private Optional<StationKey> resolveStationKey(NodeId nodeId) {
    Optional<WaypointMetadata> metaOpt = parseWaypointMetadata(nodeId);
    if (metaOpt.isEmpty()) {
      return Optional.empty();
    }
    WaypointMetadata meta = metaOpt.get();
    String operator = meta.operator();
    if (operator == null || operator.isBlank()) {
      return Optional.empty();
    }
    String station =
        meta.kind() == WaypointKind.INTERVAL
            ? meta.destinationStation().orElse(meta.originStation())
            : meta.originStation();
    if (station == null || station.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(new StationKey(operator, station));
  }

  private Optional<WaypointMetadata> parseWaypointMetadata(NodeId nodeId) {
    if (nodeId == null || nodeId.value() == null || nodeId.value().isBlank()) {
      return Optional.empty();
    }
    return SignTextParser.parseWaypointLike(nodeId.value(), NodeType.WAYPOINT)
        .flatMap(SignNodeDefinition::waypointMetadata);
  }

  /**
   * 从 NodeId 解析站台编号。
   *
   * <p>支持的格式：
   *
   * <ul>
   *   <li>4 段站点格式 {@code Op:S:Station:Track} → Track
   *   <li>4 段车库格式 {@code Op:D:Depot:Track} → Track
   *   <li>5 段站咽喉 {@code Op:S:Station:Track:Seq} → Track
   *   <li>5 段库咽喉 {@code Op:D:Depot:Track:Seq} → Track
   *   <li>5 段区间格式 {@code Op:From:To:Track:Seq} → Track
   * </ul>
   *
   * @param nodeId 节点 ID
   * @return 站台编号字符串（如 "1"/"2"），无法解析时返回 "-"
   */
  private String resolveTrackFromNodeId(NodeId nodeId) {
    if (nodeId == null || nodeId.value() == null || nodeId.value().isBlank()) {
      return "-";
    }
    Optional<WaypointMetadata> metaOpt = parseWaypointMetadata(nodeId);
    if (metaOpt.isPresent()) {
      int trackNumber = metaOpt.get().trackNumber();
      return trackNumber >= 0 ? String.valueOf(trackNumber) : "-";
    }
    return "-";
  }

  private Optional<NodeId> resolveStationNodeId(UUID stationId) {
    if (stationId == null) {
      return Optional.empty();
    }
    return directory()
        .flatMap(snapshot -> snapshot.station(stationId))
        .flatMap(entry -> entry.station().graphNodeId())
        .filter(id -> !id.isBlank())
        .map(NodeId::of);
  }

  private String formatProgressValue(float progress) {
    float clamped = clampProgress(progress);
    return String.format(java.util.Locale.ROOT, "%.3f", clamped);
  }

  private float clampProgress(float progress) {
    if (progress < 0.0f) {
      return 0.0f;
    }
    if (progress > 1.0f) {
      return 1.0f;
    }
    return progress;
  }

  /**
   * 解析线路终点与运营终点，选站口径见 {@link RouteTerminals}。
   *
   * <p>回库线路在越过运营终点之前显示终点站名，之后显示“回库 / Not in Service”——与站牌同一规则。
   */
  private Destinations resolveDestinations(
      Optional<RouteDefinition> routeOpt,
      int routeIndex,
      Optional<RouteOperationType> operationType) {
    if (routeOpt == null || routeOpt.isEmpty()) {
      return Destinations.empty();
    }
    RouteDefinition route = routeOpt.get();
    List<RouteStop> stops =
        routeDefinitions != null ? routeDefinitions.listStops(route.id()) : List.of();
    java.util.OptionalInt eorIndex = RouteTerminals.endOfRouteIndex(stops);
    StationDisplay eor =
        eorIndex.isPresent()
            ? RouteTerminals.depotRef(stops.get(eorIndex.getAsInt()))
                .map(this::resolveDepotDisplay)
                .orElse(StationDisplay.empty())
            : StationDisplay.empty();
    if (eor.isEmpty()) {
      eor = resolveStopDisplay(stops, RouteTerminals.endOfRouteLabelIndex(stops));
    }
    if (eor.isEmpty()) {
      eor = resolveEndOfRoute(route);
    }
    StationDisplay eop;
    if (RouteTerminals.outOfService(operationType.orElse(null), stops, routeIndex)) {
      eop =
          StationDisplay.of(
              RouteTerminals.OUT_OF_SERVICE_LABEL,
              RouteTerminals.OUT_OF_SERVICE_ID,
              RouteTerminals.OUT_OF_SERVICE_LANG2);
    } else {
      eop = resolveStopDisplay(stops, RouteTerminals.endOfOperationIndex(stops));
      if (eop.isEmpty()) {
        eop = eor;
      }
    }
    return new Destinations(eor, eop);
  }

  private StationDisplay resolveEndOfRoute(RouteDefinition route) {
    if (route == null || route.waypoints().isEmpty()) {
      return StationDisplay.empty();
    }
    NodeId last = route.waypoints().get(route.waypoints().size() - 1);
    return resolveStationDisplay(last);
  }

  private boolean isAtStation(
      String trainName, Optional<RouteDefinition> routeOpt, int routeIndex) {
    if (plugin == null || routeOpt == null || routeOpt.isEmpty()) {
      return false;
    }
    String routeKey = routeOpt.get().id().value();
    return plugin
        .getStationPresence()
        .map(presence -> presence.isAtStation(trainName, routeKey, routeIndex))
        .orElse(false);
  }

  /**
   * 车库显示：同代码车站的名称接「车库」（如「林湾车库 / Lym Won Depot」）；没有同代码车站时用站码（「LWN车库 / LWN Depot」）。
   *
   * <p>按站码直接查车站会把车库显示成车站本身，与车次终点混在一起。
   */
  private StationDisplay resolveDepotDisplay(RouteTerminals.StationRef depot) {
    String code = depot.stationCode();
    StationDisplay station = resolveStationDisplay(new StationKey(depot.operatorCode(), code));
    if (station.isEmpty()) {
      return StationDisplay.of(
          code + RouteTerminals.DEPOT_SUFFIX, code, code + " " + RouteTerminals.DEPOT_SUFFIX_LANG2);
    }
    String lang2 =
        "-".equals(station.lang2())
            ? code + " " + RouteTerminals.DEPOT_SUFFIX_LANG2
            : station.lang2() + " " + RouteTerminals.DEPOT_SUFFIX_LANG2;
    return StationDisplay.of(station.label() + RouteTerminals.DEPOT_SUFFIX, code, lang2);
  }

  /** 运营终点对应的 stop（用于判断列车是否已停在终点站）。 */
  private Optional<RouteStop> resolveEndOfOperationStop(RouteDefinition route) {
    if (route == null || routeDefinitions == null) {
      return Optional.empty();
    }
    List<RouteStop> stops = routeDefinitions.listStops(route.id());
    java.util.OptionalInt index = RouteTerminals.endOfOperationIndex(stops);
    return index.isPresent() ? Optional.ofNullable(stops.get(index.getAsInt())) : Optional.empty();
  }

  /** 把 {@link RouteTerminals} 选中的 stop 解析成显示：stationId → DYNAMIC 占位节点 → waypoint。 */
  private StationDisplay resolveStopDisplay(List<RouteStop> stops, java.util.OptionalInt index) {
    if (stops == null || index == null || index.isEmpty()) {
      return StationDisplay.empty();
    }
    RouteStop stop = stops.get(index.getAsInt());
    if (stop == null) {
      return StationDisplay.empty();
    }
    Optional<UUID> stationId = stop.stationId();
    if (stationId.isPresent()) {
      StationDisplay resolved = resolveStationDisplay(stationId.get());
      if (!resolved.isEmpty()) {
        return resolved;
      }
    }
    return RouteTerminals.stationRef(stop)
        .map(ref -> resolveStationDisplay(NodeId.of(ref.nodeId())))
        .orElse(StationDisplay.empty());
  }

  private Optional<RouteOperationType> resolveRouteOperationType(
      TrainProperties properties,
      Optional<RouteProgressRegistry.RouteProgressEntry> progressEntry) {
    Optional<UUID> routeId =
        progressEntry != null
            ? progressEntry.map(RouteProgressRegistry.RouteProgressEntry::routeUuid)
            : Optional.empty();
    if (routeId.isEmpty() && properties != null) {
      routeId =
          TrainTagHelper.readTagValue(properties, RouteProgressRegistry.TAG_ROUTE_ID)
              .flatMap(TrainHudContextResolver::parseUuid);
    }
    // 交路缓存随交路改库刷新，不必另读库。
    return routeId
        .flatMap(routeDefinitions::findRecord)
        .map(record -> record.route().operationType());
  }

  private String safeOrDash(String value) {
    if (value == null || value.isBlank()) {
      return "-";
    }
    return value;
  }

  private record StationKey(String operator, String station) {}

  /**
   * 下一停靠站。
   *
   * @param info 停靠站静态信息
   * @param ahead 在列车前方；前方已没有停车点、只能退回显示末站时为 false
   */
  private record NextStop(StopInfo info, boolean ahead) {
    private NextStop {
      Objects.requireNonNull(info, "info");
    }

    StationDisplay display() {
      return info.display();
    }

    Optional<NodeId> nodeId() {
      return info.nodeId();
    }

    boolean terminal() {
      return info.terminal();
    }

    /** 停靠表下标，与进度下标、时刻表停靠序号同一口径。 */
    int stopIndex() {
      return info.index();
    }
  }

  /**
   * 一个停靠站不随列车变化的信息。
   *
   * @param index 停靠表下标（与进度下标、时刻表停靠序号同一口径）
   * @param nodeId 停靠节点
   * @param display 站名
   * @param track 站台号；无法解析或动态站台时为 {@code -}（动态站台的站台号随列车，见 {@code trackFor}）
   * @param line 列车在该站所属的线路（直通运转换线后为新线路）；交路线路不明时为空
   * @param transfers 可换乘线路（按该站所属线路排除本车）；交路线路不明时为空，届时按列车当前线路现算
   * @param terminal 是否为本交路最后一个停车点
   * @param dynamic 动态站台（DYNAMIC）停靠；{@code nodeId} 是占位股道
   */
  public record StopInfo(
      int index,
      Optional<NodeId> nodeId,
      StationDisplay display,
      String track,
      Optional<RouteLineChanges.LineRef> line,
      Optional<List<TrainHudContext.Transfer>> transfers,
      boolean terminal,
      boolean dynamic) {
    public StopInfo {
      Objects.requireNonNull(display, "display");
      nodeId = nodeId == null ? Optional.empty() : nodeId;
      line = line == null ? Optional.empty() : line;
      transfers = transfers == null ? Optional.empty() : transfers.map(List::copyOf);
    }
  }

  /**
   * 交路的静态停靠表。
   *
   * @param stops 生成时的停靠表实例（交路缓存刷新会换新实例）
   * @param directoryRevision 生成时的车站目录版本
   * @param infos 能显示站名的停车点，按停靠顺序
   * @param lastStopIndex 最后一个停车点的下标
   */
  private record StopTable(
      List<RouteStop> stops, long directoryRevision, List<StopInfo> infos, int lastStopIndex) {}

  private record CompanyDisplay(String label, String code, String name) {
    private CompanyDisplay {
      label = sanitize(label);
      code = sanitize(code);
      name = sanitize(name);
    }

    private static CompanyDisplay empty() {
      return new CompanyDisplay("-", "-", "-");
    }

    private static CompanyDisplay fromCompany(Company company) {
      String code = company == null ? "-" : company.code();
      String name = company == null ? "-" : company.name();
      String label = name == null || name.isBlank() ? code : name;
      return new CompanyDisplay(label, code, name);
    }

    private static String sanitize(String value) {
      if (value == null || value.isBlank()) {
        return "-";
      }
      return value;
    }
  }

  /**
   * 未来停靠预览项（用于车内显示屏与后续站点对话框）。
   *
   * @param track 站台编号（从 NodeId 解析，如 "1"/"2"），无法解析时为 "-"
   * @param line 列车在该站所属的线路（直通运转换线后为新线路）；交路线路不明时为空
   * @param transfers 该站可换乘的线路（不含本车在该站所属的线路）
   * @param delaySeconds 按表运行时到达该站的偏差秒数（正数为晚点）；不按表运行时为空
   * @param terminal 是否为本交路最后一个停靠站
   */
  public record UpcomingStop(
      int sequence,
      StationDisplay display,
      EtaResult eta,
      String track,
      Optional<RouteLineChanges.LineRef> line,
      List<TrainHudContext.Transfer> transfers,
      OptionalLong delaySeconds,
      boolean terminal) {
    public UpcomingStop {
      Objects.requireNonNull(display, "display");
      Objects.requireNonNull(eta, "eta");
      if (sequence <= 0) {
        throw new IllegalArgumentException("sequence 必须为正数");
      }
      track = track == null || track.isBlank() ? "-" : track;
      line = line == null ? Optional.empty() : line;
      transfers = transfers == null ? List.of() : List.copyOf(transfers);
      delaySeconds = delaySeconds == null ? OptionalLong.empty() : delaySeconds;
    }
  }

  /** 未来停靠列表及总数。 */
  public record UpcomingStops(List<UpcomingStop> stops, int total) {
    public UpcomingStops {
      stops = stops == null ? List.of() : List.copyOf(stops);
      if (total < 0) {
        throw new IllegalArgumentException("total 必须为非负数");
      }
    }

    public static UpcomingStops empty() {
      return new UpcomingStops(List.of(), 0);
    }
  }
}
