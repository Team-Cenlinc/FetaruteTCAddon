package org.fetarute.fetaruteTCAddon.display.hud;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.controller.MinecartMemberStore;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaResult;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaService;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaTarget;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainRuntimeSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainTagHelper;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignTextParser;
import org.fetarute.fetaruteTCAddon.display.hud.TrainHudContext.Destinations;
import org.fetarute.fetaruteTCAddon.display.hud.TrainHudContext.StationDisplay;
import org.fetarute.fetaruteTCAddon.display.hud.bossbar.HudWaypointLabel;
import org.fetarute.fetaruteTCAddon.display.template.HudTemplateService;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
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

  private final FetaruteTCAddon plugin;
  private final LocaleManager locale;
  private final EtaService etaService;
  private final RouteDefinitionCache routeDefinitions;
  private final Optional<RouteProgressRegistry> routeProgressRegistry;
  private final Optional<LayoverRegistry> layoverRegistry;
  private final HudTemplateService templateService;
  private final Consumer<String> debugLogger;

  private final Map<String, StationDisplay> stationByKey = new HashMap<>();
  private final Map<UUID, StationDisplay> stationById = new HashMap<>();
  private final Map<UUID, Optional<NodeId>> stationNodeById = new HashMap<>();
  private boolean stationCacheLoaded = false;
  private final Map<String, CompanyDisplay> companyByOperatorCode = new HashMap<>();
  private boolean companyCacheLoaded = false;

  public TrainHudContextResolver(
      FetaruteTCAddon plugin,
      LocaleManager locale,
      EtaService etaService,
      RouteDefinitionCache routeDefinitions,
      RouteProgressRegistry routeProgressRegistry,
      LayoverRegistry layoverRegistry,
      HudTemplateService templateService,
      Consumer<String> debugLogger) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.locale = locale;
    this.etaService = Objects.requireNonNull(etaService, "etaService");
    this.routeDefinitions = Objects.requireNonNull(routeDefinitions, "routeDefinitions");
    this.routeProgressRegistry = Optional.ofNullable(routeProgressRegistry);
    this.layoverRegistry = Optional.ofNullable(layoverRegistry);
    this.templateService = templateService;
    this.debugLogger = debugLogger != null ? debugLogger : msg -> {};
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

  /** 构造 HUD 上下文（若不是 FTA 管控列车则返回 empty）。 */
  public Optional<TrainHudContext> resolveContext(MinecartGroup group) {
    if (group == null) {
      return Optional.empty();
    }
    TrainProperties properties = group.getProperties();
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

    int routeIndex =
        progressEntry
            .map(RouteProgressRegistry.RouteProgressEntry::currentIndex)
            .orElseGet(
                () ->
                    TrainTagHelper.readIntTag(properties, RouteProgressRegistry.TAG_ROUTE_INDEX)
                        .orElse(0));
    Optional<RouteDefinition> routeOpt = resolveRouteDefinition(properties, progressEntry);
    Optional<HudTemplateService.LineInfo> lineInfo =
        templateService != null
            ? templateService.resolveLineInfo(routeOpt.flatMap(RouteDefinition::metadata))
            : Optional.empty();
    Optional<NextStop> nextStopOpt = resolveNextStop(routeOpt, routeIndex);
    StationDisplay nextStation = nextStopOpt.map(NextStop::display).orElse(StationDisplay.empty());
    boolean terminalNextStop =
        nextStopOpt.map(NextStop::terminal).orElse(false) && !nextStation.isEmpty();
    Destinations destinations = resolveDestinations(routeOpt);

    EtaResult eta = etaService.getForTrain(trainName, EtaTarget.nextStop());
    Optional<TrainRuntimeSnapshot> snapshotOpt = etaService.getRuntimeSnapshot(trainName);
    Optional<NodeId> currentNode = snapshotOpt.flatMap(TrainRuntimeSnapshot::currentNodeId);
    StationDisplay currentStation =
        currentNode.map(this::resolveStationDisplay).orElse(StationDisplay.empty());
    boolean stop =
        snapshotOpt
            .flatMap(TrainRuntimeSnapshot::dwellRemainingSec)
            .map(sec -> sec > 0)
            .orElse(false);
    SignalAspect signalAspect =
        progressEntry.map(RouteProgressRegistry.RouteProgressEntry::lastSignal).orElse(null);
    Optional<LayoverRegistry.LayoverCandidate> layover = resolveLayover(trainName);

    double speedBps = resolveSpeedBlocksPerSecond(group);
    boolean moving = group.isMoving();

    TrainHudContext context =
        new TrainHudContext(
            trainName,
            routeIndex,
            routeOpt,
            lineInfo,
            currentStation,
            nextStation,
            destinations,
            eta,
            signalAspect,
            layover,
            stop,
            moving,
            terminalNextStop,
            speedBps);
    return Optional.of(context);
  }

  public void clearCaches() {
    stationByKey.clear();
    stationById.clear();
    stationNodeById.clear();
    stationCacheLoaded = false;
    companyByOperatorCode.clear();
    companyCacheLoaded = false;
  }

  /** 根据上下文生成模板占位符键值。 */
  public Map<String, String> buildPlaceholders(TrainHudContext context, float progress) {
    Map<String, String> placeholders = new HashMap<>();
    StationDisplay safeCurrent =
        context.currentStation() == null
            ? StationDisplay.empty()
            : context.currentStation().sanitized();
    StationDisplay safeNext =
        context.nextStation() == null ? StationDisplay.empty() : context.nextStation().sanitized();
    StationDisplay safeEor = context.destinations().eor().sanitized();
    StationDisplay safeEop = context.destinations().eop().sanitized();
    String etaStatus = context.eta().statusText();
    String etaMinutes =
        context.eta().etaMinutesRounded() >= 0
            ? String.valueOf(context.eta().etaMinutesRounded())
            : "-";
    String unit = localeTextOrDefault("display.hud.bossbar.unit.kmh", "km/h");
    String labelLine = localeTextOrDefault("display.hud.bossbar.label.line", "Line");
    String labelNext = localeTextOrDefault("display.hud.bossbar.label.next", "Next");
    String signalStatus = resolveSignalStatus(context.signalAspect());
    String serviceStatus = resolveServiceStatus(context.layover());
    String lineCode = "-";
    String lineName = "-";
    String lineLang2 = "-";
    String lineColor = "";
    String lineColorTag = "white";
    String operatorCode = "-";
    String routeCode = "-";
    String routeId = "-";
    String routeName = "-";
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
    if (context.lineInfo() != null && context.lineInfo().isPresent()) {
      HudTemplateService.LineInfo info = context.lineInfo().get();
      lineCode = info.code();
      lineName = info.name();
      lineLang2 = info.secondaryName();
      lineColor = info.color();
    }
    if (lineColor != null && !lineColor.isBlank()) {
      lineColorTag = lineColor;
    }
    String lineLabel = resolveLineLabel(lineName, lineCode);
    String safeLine = lineLabel.isBlank() ? "-" : lineLabel;
    String safeLineLang2 = resolveLineLang2(lineLang2, safeLine);

    CompanyDisplay company = resolveCompanyDisplay(operatorCode);

    placeholders.put("company", company.label());
    placeholders.put("company_code", company.code());
    placeholders.put("company_name", company.name());
    placeholders.put("line", safeLine);
    placeholders.put("line_lang2", safeLineLang2);
    placeholders.put("line_code", safeOrDash(lineCode));
    placeholders.put("line_name", safeOrDash(lineName));
    placeholders.put("line_color", lineColor == null ? "" : lineColor);
    placeholders.put("line_color_tag", lineColorTag);
    placeholders.put("operator", safeOrDash(operatorCode));
    placeholders.put("route_code", safeOrDash(routeCode));
    placeholders.put("route_id", safeOrDash(routeId));
    placeholders.put("route_name", safeOrDash(routeName));
    placeholders.put("current_station", safeCurrent.label());
    placeholders.put("current_station_code", safeCurrent.code());
    placeholders.put("current_station_lang2", safeCurrent.lang2());
    placeholders.put("next_station", safeNext.label());
    placeholders.put("next_station_code", safeNext.code());
    placeholders.put("next_station_lang2", safeNext.lang2());
    placeholders.put("dest_eor", safeEor.label());
    placeholders.put("dest_eor_code", safeEor.code());
    placeholders.put("dest_eor_lang2", safeEor.lang2());
    placeholders.put("dest_eop", safeEop.label());
    placeholders.put("dest_eop_code", safeEop.code());
    placeholders.put("dest_eop_lang2", safeEop.lang2());
    placeholders.put("eta_status", etaStatus);
    placeholders.put("eta_minutes", etaMinutes);
    placeholders.put("speed_kmh", formatSpeedValue(context.speedBps()));
    placeholders.put("speed_bps", formatSpeedBps(context.speedBps()));
    placeholders.put("speed_unit", unit);
    placeholders.put("speed", formatSpeed(context.speedBps()));
    placeholders.put(
        "signal_aspect",
        context.signalAspect() == null ? "UNKNOWN" : context.signalAspect().name());
    placeholders.put("signal_status", signalStatus);
    placeholders.put("service_status", serviceStatus);
    placeholders.put("train_name", context.trainName());
    applyProgressPlaceholders(placeholders, progress);
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
   * 注入玩家侧占位符（车厢号/编组总数）。
   *
   * <p>ActionBar/BossBar 共用，避免重复实现；非列车或未在编组内时输出 {@code "-"}。
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

  private Optional<NextStop> resolveNextStop(Optional<RouteDefinition> routeOpt, int routeIndex) {
    if (routeOpt == null || routeOpt.isEmpty() || routeDefinitions == null) {
      return Optional.empty();
    }
    RouteDefinition route = routeOpt.get();
    if (route.waypoints() == null || route.waypoints().isEmpty()) {
      return Optional.empty();
    }
    List<RouteStop> stops = routeDefinitions.listStops(route.id());
    if (stops.isEmpty()) {
      return Optional.empty();
    }
    int lastStopIndex = resolveLastStopIndex(stops);
    Map<NodeId, Integer> nodeIndexMap = buildNodeIndexMap(route.waypoints());
    NextStop best = null;
    int bestIndex = Integer.MAX_VALUE;
    for (int i = 0; i < stops.size(); i++) {
      RouteStop stop = stops.get(i);
      if (stop == null || stop.passType() == RouteStopPassType.PASS) {
        continue;
      }
      Optional<NodeId> nodeIdOpt = resolveStopNodeId(stop);
      StationDisplay display = resolveStopDisplay(stop, nodeIdOpt);
      if (display.isEmpty()) {
        continue;
      }
      Optional<Integer> nodeIndexOpt =
          nodeIdOpt.map(nodeIndexMap::get).filter(index -> index != null);
      if (nodeIndexOpt.isPresent()) {
        int index = nodeIndexOpt.get();
        if (index > routeIndex && index < bestIndex) {
          bestIndex = index;
          best = new NextStop(display, nodeIdOpt, i == lastStopIndex);
        }
      }
    }
    if (best != null) {
      return Optional.of(best);
    }
    if (lastStopIndex >= 0) {
      RouteStop stop = stops.get(lastStopIndex);
      Optional<NodeId> nodeIdOpt = resolveStopNodeId(stop);
      StationDisplay display = resolveStopDisplay(stop, nodeIdOpt);
      if (!display.isEmpty()) {
        return Optional.of(new NextStop(display, nodeIdOpt, true));
      }
    }
    return Optional.empty();
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

  private Map<NodeId, Integer> buildNodeIndexMap(List<NodeId> waypoints) {
    Map<NodeId, Integer> indexMap = new HashMap<>();
    if (waypoints == null) {
      return indexMap;
    }
    for (int i = 0; i < waypoints.size(); i++) {
      NodeId nodeId = waypoints.get(i);
      if (nodeId != null) {
        indexMap.put(nodeId, i);
      }
    }
    return indexMap;
  }

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
    return Optional.empty();
  }

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
    if (nodeIdOpt.isEmpty()) {
      return StationDisplay.empty();
    }
    Optional<WaypointMetadata> metaOpt = parseWaypointMetadata(nodeIdOpt.get());
    if (metaOpt.isPresent()) {
      WaypointMetadata meta = metaOpt.get();
      if (meta.kind() != WaypointKind.STATION && meta.kind() != WaypointKind.STATION_THROAT) {
        return StationDisplay.empty();
      }
    }
    return resolveStationDisplay(nodeIdOpt.get());
  }

  private double resolveSpeedBlocksPerSecond(MinecartGroup group) {
    if (group == null || group.head() == null) {
      return 0.0;
    }
    double bpt = Math.abs(group.head().getRealSpeed());
    return bpt * 20.0;
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

  private CompanyDisplay resolveCompanyDisplay(String operatorCode) {
    if (operatorCode == null || operatorCode.isBlank()) {
      return CompanyDisplay.empty();
    }
    ensureCompanyCache();
    CompanyDisplay cached = companyByOperatorCode.get(operatorCode.trim().toLowerCase(Locale.ROOT));
    if (cached != null) {
      return cached;
    }
    return CompanyDisplay.empty();
  }

  private StationDisplay resolveStationDisplay(UUID stationId) {
    if (stationId == null) {
      return StationDisplay.empty();
    }
    ensureStationCache();
    StationDisplay cached = stationById.get(stationId);
    if (cached != null) {
      return cached;
    }
    Optional<StorageProvider> providerOpt = providerIfReady();
    if (providerOpt.isEmpty()) {
      return StationDisplay.empty();
    }
    StorageProvider provider = providerOpt.get();
    Optional<Station> stationOpt = provider.stations().findById(stationId);
    if (stationOpt.isEmpty()) {
      return StationDisplay.empty();
    }
    StationDisplay display = StationDisplay.fromStation(stationOpt.get());
    Optional<NodeId> nodeId =
        stationOpt.get().graphNodeId().filter(id -> !id.isBlank()).map(NodeId::of);
    stationById.put(stationId, display);
    stationNodeById.put(stationId, nodeId);
    String key =
        stationKey(resolveOperatorCode(stationOpt.get().operatorId()).orElse(""), display.code());
    if (!key.isBlank()) {
      stationByKey.putIfAbsent(key, display);
    }
    return display;
  }

  private StationDisplay resolveStationDisplay(StationKey key) {
    if (key == null) {
      return StationDisplay.empty();
    }
    ensureStationCache();
    StationDisplay cached = stationByKey.get(stationKey(key.operator(), key.station()));
    if (cached != null) {
      return cached;
    }
    return StationDisplay.empty();
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

  private Optional<NodeId> resolveStationNodeId(UUID stationId) {
    if (stationId == null) {
      return Optional.empty();
    }
    ensureStationCache();
    Optional<NodeId> cached = stationNodeById.get(stationId);
    if (cached != null) {
      return cached;
    }
    Optional<StorageProvider> providerOpt = providerIfReady();
    if (providerOpt.isEmpty()) {
      return Optional.empty();
    }
    StorageProvider provider = providerOpt.get();
    Optional<Station> stationOpt = provider.stations().findById(stationId);
    if (stationOpt.isEmpty()) {
      return Optional.empty();
    }
    Optional<NodeId> nodeId =
        stationOpt.get().graphNodeId().filter(id -> !id.isBlank()).map(NodeId::of);
    stationNodeById.put(stationId, nodeId);
    return nodeId;
  }

  private void ensureStationCache() {
    if (stationCacheLoaded) {
      return;
    }
    Optional<StorageProvider> providerOpt = providerIfReady();
    if (providerOpt.isEmpty()) {
      return;
    }
    StorageProvider provider = providerOpt.get();
    try {
      for (Company company : provider.companies().listAll()) {
        if (company == null) {
          continue;
        }
        for (Operator operator : provider.operators().listByCompany(company.id())) {
          if (operator == null) {
            continue;
          }
          for (Station station : provider.stations().listByOperator(operator.id())) {
            if (station == null) {
              continue;
            }
            StationDisplay display = StationDisplay.fromStation(station);
            Optional<NodeId> nodeId =
                station.graphNodeId().filter(id -> !id.isBlank()).map(NodeId::of);
            stationById.put(station.id(), display);
            stationNodeById.put(station.id(), nodeId);
            String key = stationKey(operator.code(), station.code());
            if (!key.isBlank()) {
              stationByKey.put(key, display);
            }
          }
        }
      }
      stationCacheLoaded = true;
    } catch (Exception ex) {
      debugLogger.accept("HUD station cache load failed: " + ex.getMessage());
    }
  }

  private void ensureCompanyCache() {
    if (companyCacheLoaded) {
      return;
    }
    Optional<StorageProvider> providerOpt = providerIfReady();
    if (providerOpt.isEmpty()) {
      return;
    }
    StorageProvider provider = providerOpt.get();
    try {
      for (Company company : provider.companies().listAll()) {
        if (company == null) {
          continue;
        }
        CompanyDisplay display = CompanyDisplay.fromCompany(company);
        for (Operator operator : provider.operators().listByCompany(company.id())) {
          if (operator == null || operator.code() == null || operator.code().isBlank()) {
            continue;
          }
          String key = operator.code().trim().toLowerCase(Locale.ROOT);
          companyByOperatorCode.putIfAbsent(key, display);
        }
      }
      companyCacheLoaded = true;
    } catch (Exception ex) {
      debugLogger.accept("HUD company cache load failed: " + ex.getMessage());
    }
  }

  private Optional<String> resolveOperatorCode(UUID operatorId) {
    if (operatorId == null) {
      return Optional.empty();
    }
    Optional<StorageProvider> providerOpt = providerIfReady();
    if (providerOpt.isEmpty()) {
      return Optional.empty();
    }
    StorageProvider provider = providerOpt.get();
    return provider.operators().findById(operatorId).map(Operator::code);
  }

  private Optional<StorageProvider> providerIfReady() {
    if (plugin.getStorageManager() == null || !plugin.getStorageManager().isReady()) {
      return Optional.empty();
    }
    return plugin.getStorageManager().provider();
  }

  private String stationKey(String operator, String station) {
    if (operator == null || operator.isBlank() || station == null || station.isBlank()) {
      return "";
    }
    return operator.trim().toLowerCase(Locale.ROOT) + ":" + station.trim().toLowerCase(Locale.ROOT);
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

  private Destinations resolveDestinations(Optional<RouteDefinition> routeOpt) {
    if (routeOpt == null || routeOpt.isEmpty()) {
      return Destinations.empty();
    }
    RouteDefinition route = routeOpt.get();
    StationDisplay eor = resolveEndOfRoute(route);
    StationDisplay eop = resolveEndOfOperation(route, eor);
    return new Destinations(eor, eop);
  }

  private StationDisplay resolveEndOfRoute(RouteDefinition route) {
    if (route == null || route.waypoints().isEmpty()) {
      return StationDisplay.empty();
    }
    NodeId last = route.waypoints().get(route.waypoints().size() - 1);
    return resolveStationDisplay(last);
  }

  private StationDisplay resolveEndOfOperation(RouteDefinition route, StationDisplay fallback) {
    if (route == null || routeDefinitions == null) {
      return fallback;
    }
    List<RouteStop> stops = routeDefinitions.listStops(route.id());
    for (int i = stops.size() - 1; i >= 0; i--) {
      RouteStop stop = stops.get(i);
      if (stop == null) {
        continue;
      }
      RouteStopPassType passType = stop.passType();
      if (passType == RouteStopPassType.PASS) {
        continue;
      }
      Optional<UUID> stationId = stop.stationId();
      if (stationId.isPresent()) {
        StationDisplay resolved = resolveStationDisplay(stationId.get());
        if (!resolved.isEmpty()) {
          return resolved;
        }
      }
      Optional<String> nodeId = stop.waypointNodeId().filter(id -> !id.isBlank());
      if (nodeId.isPresent()) {
        StationDisplay resolved = resolveStationDisplay(NodeId.of(nodeId.get()));
        if (!resolved.isEmpty()) {
          return resolved;
        }
      }
      break;
    }
    return fallback;
  }

  private String safeOrDash(String value) {
    if (value == null || value.isBlank()) {
      return "-";
    }
    return value;
  }

  private record StationKey(String operator, String station) {}

  private record NextStop(StationDisplay display, Optional<NodeId> nodeId, boolean terminal) {
    private NextStop {
      Objects.requireNonNull(display, "display");
      nodeId = nodeId == null ? Optional.empty() : nodeId;
    }
  }

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
}
