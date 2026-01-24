package org.fetarute.fetaruteTCAddon.display.hud.bossbar;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.controller.MinecartMemberStore;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
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
import org.fetarute.fetaruteTCAddon.display.template.HudDefaultTemplateService;
import org.fetarute.fetaruteTCAddon.display.template.HudTemplateService;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 车上 BossBar HUD（MVP）：展示线路/下一站/ETA/速度，并用 BossBar 进度条做“下一站到达进度”近似。
 *
 * <p>BossBar 的结构与计算逻辑保持固定（progress/ETA/speed 等），标题模板可通过 HUD 模板服务覆写。
 *
 * <p>玩家是否在列车上：从 player.getVehicle() 反查 TrainCarts member/group。
 */
public final class BossBarTrainHudManager implements Listener {

  private static final long VEHICLE_HOPS = 3;
  private static final long DEPARTING_WINDOW_TICKS = 60L;
  private static final String DEFAULT_TEMPLATE =
      "Line {line} | Next {next_station} | {eta_status} | {speed}";

  private final FetaruteTCAddon plugin;
  private final LocaleManager locale;
  private final ConfigManager configManager;
  private final EtaService etaService;
  private final RouteDefinitionCache routeDefinitions;
  private final Optional<RouteProgressRegistry> routeProgressRegistry;
  private final Optional<LayoverRegistry> layoverRegistry;
  private final HudTemplateService templateService;
  private final HudDefaultTemplateService defaultTemplateService;
  private final Consumer<String> debugLogger;

  private final BossBarProgressTracker progressTracker = new BossBarProgressTracker();
  private final BossBarHudStateTracker stateTracker =
      new BossBarHudStateTracker(DEPARTING_WINDOW_TICKS * 50L);
  private final Map<String, BossBarHudTemplate> templateCache = new HashMap<>();
  private final Map<String, StationDisplay> stationByKey = new HashMap<>();
  private final Map<UUID, StationDisplay> stationById = new HashMap<>();
  private final Map<UUID, Optional<NodeId>> stationNodeById = new HashMap<>();
  private boolean stationCacheLoaded = false;
  private final Map<UUID, BossBar> bars = new HashMap<>();
  private long tickCounter = 0L;

  public BossBarTrainHudManager(
      FetaruteTCAddon plugin,
      LocaleManager locale,
      ConfigManager configManager,
      EtaService etaService,
      RouteDefinitionCache routeDefinitions,
      RouteProgressRegistry routeProgressRegistry,
      LayoverRegistry layoverRegistry,
      HudTemplateService templateService,
      HudDefaultTemplateService defaultTemplateService,
      Consumer<String> debugLogger) {
    this.plugin = plugin;
    this.locale = locale;
    this.configManager = configManager;
    this.etaService = etaService;
    this.routeDefinitions = routeDefinitions;
    this.routeProgressRegistry = Optional.ofNullable(routeProgressRegistry);
    this.layoverRegistry = Optional.ofNullable(layoverRegistry);
    this.templateService = templateService;
    this.defaultTemplateService = defaultTemplateService;
    this.debugLogger = debugLogger != null ? debugLogger : msg -> {};
  }

  public void register() {
    Bukkit.getPluginManager().registerEvents(this, plugin);
    debugLogger.accept("BossBarTrainHudManager registered");
  }

  public void unregister() {
    HandlerList.unregisterAll(this);
    debugLogger.accept("BossBarTrainHudManager unregistered");
  }

  public void tick() {
    int intervalTicks = resolveIntervalTicks();
    tickCounter += intervalTicks;
    Set<String> activeTrains = new HashSet<>();
    for (Player player : Bukkit.getOnlinePlayers()) {
      Optional<MinecartGroup> groupOpt = resolveGroup(player);
      if (groupOpt.isEmpty()) {
        hide(player);
        continue;
      }
      showOrUpdate(player, groupOpt.get()).ifPresent(activeTrains::add);
    }
    progressTracker.retain(activeTrains);
    stateTracker.retain(activeTrains);
  }

  public void shutdown() {
    for (Player player : Bukkit.getOnlinePlayers()) {
      hide(player);
    }
    progressTracker.clear();
    stateTracker.clear();
    templateCache.clear();
    stationByKey.clear();
    stationById.clear();
    stationNodeById.clear();
    stationCacheLoaded = false;
    bars.clear();
    debugLogger.accept("BossBarTrainHudManager shutdown");
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    hide(event.getPlayer());
  }

  @EventHandler
  public void onKick(PlayerKickEvent event) {
    hide(event.getPlayer());
  }

  private Optional<String> showOrUpdate(Player player, MinecartGroup group) {
    TrainProperties properties = group.getProperties();
    if (properties == null) {
      hide(player);
      return Optional.empty();
    }

    String trainName = properties.getTrainName();
    if (trainName == null || trainName.isBlank()) {
      hide(player);
      return Optional.empty();
    }

    Optional<RouteProgressRegistry.RouteProgressEntry> progressEntry =
        resolveProgressEntry(trainName);
    if (!isFtaManaged(properties, progressEntry)) {
      showDestinationOnly(player, properties);
      return Optional.of(trainName);
    }
    int routeIndex =
        progressEntry
            .map(RouteProgressRegistry.RouteProgressEntry::currentIndex)
            .orElseGet(
                () ->
                    TrainTagHelper.readIntTag(properties, RouteProgressRegistry.TAG_ROUTE_INDEX)
                        .orElse(0));

    Optional<RouteDefinition> routeOpt = resolveRouteDefinition(properties, progressEntry);
    Optional<String> templateOpt =
        templateService != null
            ? templateService.resolveBossBarTemplate(routeOpt.flatMap(RouteDefinition::metadata))
            : Optional.empty();
    Optional<HudTemplateService.LineInfo> lineInfo =
        templateService != null
            ? templateService.resolveLineInfo(routeOpt.flatMap(RouteDefinition::metadata))
            : Optional.empty();
    StationDisplay nextStation =
        resolveNextStopStationDisplay(routeOpt, routeIndex).orElse(StationDisplay.empty());
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
    long nowMillis = System.currentTimeMillis();
    float baseProgress =
        progressTracker.progress(
            trainName, routeIndex, eta.etaEpochMillis(), nowMillis, group.isMoving());

    Map<String, String> placeholders =
        buildPlaceholders(
            trainName,
            lineInfo,
            routeOpt,
            currentStation,
            nextStation,
            destinations,
            eta,
            speedBps,
            baseProgress,
            signalAspect,
            layover);
    BossBarHudTemplate template = resolveParsedTemplate(resolveTemplate(templateOpt));
    float progress = resolveProgress(template, placeholders, baseProgress);
    BossBarHudState state =
        stateTracker.resolve(
            trainName, group.isMoving(), eta.arriving(), layover.isPresent(), stop, nowMillis);
    String templateLine = template.resolveLine(state, tickCounter).orElse("");
    Component title = BossBarHudTemplateRenderer.render(templateLine, placeholders, debugLogger);

    BossBar bar =
        bars.computeIfAbsent(
            player.getUniqueId(),
            id ->
                BossBar.bossBar(
                    Component.empty(), 0.0f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS));

    bar.name(title);
    bar.progress(progress);
    bar.color(resolveColor(eta, signalAspect));

    player.showBossBar(bar);
    return Optional.of(trainName);
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

  private void showDestinationOnly(Player player, TrainProperties properties) {
    String destination = properties == null ? null : properties.getDestination();
    if (destination == null || destination.isBlank()) {
      destination = localeTextOrDefault("display.hud.bossbar.tc_destination.empty", "暂无目的地");
    } else {
      destination = destination.trim();
    }
    BossBar bar =
        bars.computeIfAbsent(
            player.getUniqueId(),
            id ->
                BossBar.bossBar(
                    Component.empty(), 0.0f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS));
    bar.name(Component.text(destination));
    bar.progress(1.0f);
    bar.color(BossBar.Color.BLUE);
    player.showBossBar(bar);
  }

  private void hide(Player player) {
    BossBar bar = bars.remove(player.getUniqueId());
    if (bar != null) {
      player.hideBossBar(bar);
    }
  }

  private Optional<MinecartGroup> resolveGroup(Player player) {
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
            .flatMap(BossBarTrainHudManager::parseUuid);
    if (routeUuid.isEmpty()) {
      return Optional.empty();
    }
    return routeDefinitions.findById(routeUuid.get());
  }

  private Optional<StationDisplay> resolveNextStopStationDisplay(
      Optional<RouteDefinition> routeOpt, int routeIndex) {
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
    Map<NodeId, Integer> nodeIndexMap = buildNodeIndexMap(route.waypoints());
    StationDisplay best = StationDisplay.empty();
    int bestIndex = Integer.MAX_VALUE;
    for (RouteStop stop : stops) {
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
          best = display;
        }
      }
    }
    if (bestIndex != Integer.MAX_VALUE) {
      return Optional.of(best);
    }
    StationDisplay fallback = resolveLastStopDisplay(stops);
    return fallback.isEmpty() ? Optional.empty() : Optional.of(fallback);
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

  private StationDisplay resolveLastStopDisplay(List<RouteStop> stops) {
    if (stops == null || stops.isEmpty()) {
      return StationDisplay.empty();
    }
    for (int i = stops.size() - 1; i >= 0; i--) {
      RouteStop stop = stops.get(i);
      if (stop == null || stop.passType() == RouteStopPassType.PASS) {
        continue;
      }
      Optional<NodeId> nodeIdOpt = resolveStopNodeId(stop);
      StationDisplay display = resolveStopDisplay(stop, nodeIdOpt);
      if (!display.isEmpty()) {
        return display;
      }
    }
    return StationDisplay.empty();
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

  private String localeTextOrDefault(String key, String fallback) {
    if (locale == null) {
      return fallback;
    }
    String value = locale.text(key);
    return value.equals(key) ? fallback : value;
  }

  private BossBar.Color resolveColor(EtaResult eta, SignalAspect signalAspect) {
    if (signalAspect == SignalAspect.STOP) {
      return BossBar.Color.RED;
    }
    if (signalAspect == SignalAspect.CAUTION || signalAspect == SignalAspect.PROCEED_WITH_CAUTION) {
      return BossBar.Color.YELLOW;
    }
    if (eta.arriving()) {
      return BossBar.Color.YELLOW;
    }
    return BossBar.Color.BLUE;
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

  private String resolveTemplate(Optional<String> templateOpt) {
    if (templateOpt != null && templateOpt.isPresent() && !templateOpt.get().isBlank()) {
      return templateOpt.get();
    }
    if (configManager != null && configManager.current() != null) {
      Optional<String> configured = configManager.current().runtimeSettings().hudBossBarTemplate();
      if (configured.isPresent() && !configured.get().isBlank()) {
        return configured.get();
      }
    }
    if (defaultTemplateService != null) {
      Optional<String> defaultTemplate = defaultTemplateService.resolveBossBarTemplate();
      if (defaultTemplate.isPresent() && !defaultTemplate.get().isBlank()) {
        return defaultTemplate.get();
      }
    }
    String fallback = localeTextOrDefault("display.hud.bossbar.template", DEFAULT_TEMPLATE);
    return fallback.isBlank() ? DEFAULT_TEMPLATE : fallback;
  }

  private BossBarHudTemplate resolveParsedTemplate(String rawTemplate) {
    String key = rawTemplate == null ? "" : rawTemplate;
    return templateCache.computeIfAbsent(
        key, value -> BossBarHudTemplate.parse(value, debugLogger));
  }

  private int resolveIntervalTicks() {
    if (configManager != null && configManager.current() != null) {
      int interval = configManager.current().runtimeSettings().hudBossBarTickIntervalTicks();
      return Math.max(1, interval);
    }
    return 1;
  }

  private float resolveProgress(
      BossBarHudTemplate template, Map<String, String> placeholders, float baseProgress) {
    if (template == null) {
      return baseProgress;
    }
    Optional<String> expressionOpt = template.progressExpression();
    if (expressionOpt.isEmpty()) {
      return baseProgress;
    }
    OptionalDouble parsed =
        BossBarProgressExpression.evaluate(expressionOpt.get(), placeholders, debugLogger);
    if (parsed.isEmpty()) {
      return baseProgress;
    }
    float value = (float) parsed.getAsDouble();
    float clamped = clampProgress(value);
    applyProgressPlaceholders(placeholders, clamped);
    return clamped;
  }

  private Map<String, String> buildPlaceholders(
      String trainName,
      Optional<HudTemplateService.LineInfo> lineInfo,
      Optional<RouteDefinition> routeOpt,
      StationDisplay currentStation,
      StationDisplay nextStation,
      Destinations destinations,
      EtaResult eta,
      double speedBps,
      float progress,
      SignalAspect signalAspect,
      Optional<LayoverRegistry.LayoverCandidate> layover) {
    Map<String, String> placeholders = new HashMap<>();
    StationDisplay safeCurrent =
        currentStation == null ? StationDisplay.empty() : currentStation.sanitized();
    StationDisplay safeNext =
        nextStation == null ? StationDisplay.empty() : nextStation.sanitized();
    StationDisplay safeEor = destinations.eor().sanitized();
    StationDisplay safeEop = destinations.eop().sanitized();
    String etaStatus = eta.statusText();
    String etaMinutes =
        eta.etaMinutesRounded() >= 0 ? String.valueOf(eta.etaMinutesRounded()) : "-";
    String unit = localeTextOrDefault("display.hud.bossbar.unit.kmh", "km/h");
    String labelLine = localeTextOrDefault("display.hud.bossbar.label.line", "Line");
    String labelNext = localeTextOrDefault("display.hud.bossbar.label.next", "Next");
    String signalStatus = resolveSignalStatus(signalAspect);
    String serviceStatus = resolveServiceStatus(layover);
    String lineCode = "-";
    String lineName = "-";
    String lineColor = "";
    String lineColorTag = "white";
    String operatorCode = "-";
    String routeCode = "-";
    String routeId = "-";
    String routeName = "-";
    if (routeOpt != null && routeOpt.isPresent()) {
      RouteDefinition route = routeOpt.get();
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
    if (lineInfo != null && lineInfo.isPresent()) {
      HudTemplateService.LineInfo info = lineInfo.get();
      lineCode = info.code();
      lineName = info.name();
      lineColor = info.color();
    }
    if (lineColor != null && !lineColor.isBlank()) {
      lineColorTag = lineColor;
    }
    String lineLabel = resolveLineLabel(lineName, lineCode);
    String safeLine = lineLabel.isBlank() ? "-" : lineLabel;

    placeholders.put("line", safeLine);
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
    placeholders.put("speed_kmh", formatSpeedValue(speedBps));
    placeholders.put("speed_bps", formatSpeedBps(speedBps));
    placeholders.put("speed_unit", unit);
    placeholders.put("speed", formatSpeed(speedBps));
    placeholders.put("signal_aspect", signalAspect == null ? "UNKNOWN" : signalAspect.name());
    placeholders.put("signal_status", signalStatus);
    placeholders.put("service_status", serviceStatus);
    placeholders.put("train_name", trainName);
    applyProgressPlaceholders(placeholders, progress);
    placeholders.put("label_line", labelLine);
    placeholders.put("label_next", labelNext);
    placeholders.put("layover_wait", "-");

    layover.ifPresent(
        candidate ->
            placeholders.put(
                "layover_wait",
                formatDuration(Duration.between(candidate.readyAt(), Instant.now()))));

    return placeholders;
  }

  private void applyProgressPlaceholders(Map<String, String> placeholders, float progress) {
    placeholders.put("progress", formatProgressValue(progress));
    placeholders.put("progress_percent", formatPercent(progress));
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
      debugLogger.accept("BossBar station cache load failed: " + ex.getMessage());
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

  private float clampProgress(float progress) {
    if (progress < 0.0f) {
      return 0.0f;
    }
    if (progress > 1.0f) {
      return 1.0f;
    }
    return progress;
  }

  private String formatProgressValue(float progress) {
    float clamped = clampProgress(progress);
    return String.format(java.util.Locale.ROOT, "%.3f", clamped);
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
    List<org.fetarute.fetaruteTCAddon.company.model.RouteStop> stops =
        routeDefinitions.listStops(route.id());
    for (int i = stops.size() - 1; i >= 0; i--) {
      org.fetarute.fetaruteTCAddon.company.model.RouteStop stop = stops.get(i);
      if (stop == null) {
        continue;
      }
      org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType passType = stop.passType();
      if (passType == org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType.PASS) {
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

  private record Destinations(StationDisplay eor, StationDisplay eop) {
    private static Destinations empty() {
      return new Destinations(StationDisplay.empty(), StationDisplay.empty());
    }
  }

  private record StationDisplay(String label, String code, String lang2) {
    private StationDisplay {
      label = sanitize(label);
      code = sanitize(code);
      lang2 = sanitize(lang2);
    }

    private static StationDisplay empty() {
      return new StationDisplay("-", "-", "-");
    }

    private static StationDisplay of(String label, String code, String lang2) {
      return new StationDisplay(label, code, lang2);
    }

    private static StationDisplay fromStation(Station station) {
      String code = station == null ? "-" : station.code();
      String name = station == null ? "-" : station.name();
      String label = name == null || name.isBlank() ? code : name;
      String secondary =
          station == null ? "-" : station.secondaryName().filter(s -> !s.isBlank()).orElse("-");
      return new StationDisplay(label, code, secondary);
    }

    private boolean isEmpty() {
      return "-".equals(label) && "-".equals(code) && "-".equals(lang2);
    }

    private StationDisplay sanitized() {
      return new StationDisplay(label, code, lang2);
    }

    private static String sanitize(String value) {
      if (value == null || value.isBlank()) {
        return "-";
      }
      return value;
    }
  }

  private record StationKey(String operator, String station) {}
}
