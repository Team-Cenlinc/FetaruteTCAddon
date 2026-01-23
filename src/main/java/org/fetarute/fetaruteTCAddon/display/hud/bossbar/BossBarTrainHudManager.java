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
import java.util.Map;
import java.util.Optional;
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
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaResult;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaService;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaTarget;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainTagHelper;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.display.template.HudTemplateService;
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
  private final Consumer<String> debugLogger;

  private final BossBarProgressTracker progressTracker = new BossBarProgressTracker();
  private final Map<UUID, BossBar> bars = new HashMap<>();

  public BossBarTrainHudManager(
      FetaruteTCAddon plugin,
      LocaleManager locale,
      ConfigManager configManager,
      EtaService etaService,
      RouteDefinitionCache routeDefinitions,
      RouteProgressRegistry routeProgressRegistry,
      LayoverRegistry layoverRegistry,
      HudTemplateService templateService,
      Consumer<String> debugLogger) {
    this.plugin = plugin;
    this.locale = locale;
    this.configManager = configManager;
    this.etaService = etaService;
    this.routeDefinitions = routeDefinitions;
    this.routeProgressRegistry = Optional.ofNullable(routeProgressRegistry);
    this.layoverRegistry = Optional.ofNullable(layoverRegistry);
    this.templateService = templateService;
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
  }

  public void shutdown() {
    for (Player player : Bukkit.getOnlinePlayers()) {
      hide(player);
    }
    progressTracker.clear();
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
    Optional<NodeId> nextNode =
        progressEntry.flatMap(RouteProgressRegistry.RouteProgressEntry::nextTarget);
    if (nextNode.isEmpty()) {
      nextNode = routeOpt.flatMap(route -> resolveNextNode(route, routeIndex));
    }
    String nextStation = nextNode.map(HudWaypointLabel::stationLabel).orElse("-");
    Destinations destinations = resolveDestinations(routeOpt);

    EtaResult eta = etaService.getForTrain(trainName, EtaTarget.nextStop());
    SignalAspect signalAspect =
        progressEntry.map(RouteProgressRegistry.RouteProgressEntry::lastSignal).orElse(null);
    Optional<LayoverRegistry.LayoverCandidate> layover = resolveLayover(trainName);

    double speedBps = resolveSpeedBlocksPerSecond(group);
    float progress =
        progressTracker.progress(
            trainName,
            routeIndex,
            eta.etaEpochMillis(),
            System.currentTimeMillis(),
            group.isMoving());

    Component title =
        BossBarHudTemplateRenderer.render(
            resolveTemplate(templateOpt),
            buildPlaceholders(
                trainName,
                lineInfo,
                routeOpt,
                nextStation,
                destinations,
                eta,
                speedBps,
                progress,
                signalAspect,
                layover),
            debugLogger);

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

  private Optional<NodeId> resolveNextNode(RouteDefinition route, int routeIndex) {
    if (route == null || route.waypoints() == null) {
      return Optional.empty();
    }
    int nextIndex = routeIndex + 1;
    if (nextIndex < 0 || nextIndex >= route.waypoints().size()) {
      return Optional.empty();
    }
    return Optional.ofNullable(route.waypoints().get(nextIndex));
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
    if (eta != null && eta.arriving()) {
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
    String fallback = localeTextOrDefault("display.hud.bossbar.template", DEFAULT_TEMPLATE);
    return fallback.isBlank() ? DEFAULT_TEMPLATE : fallback;
  }

  private Map<String, String> buildPlaceholders(
      String trainName,
      Optional<HudTemplateService.LineInfo> lineInfo,
      Optional<RouteDefinition> routeOpt,
      String nextStation,
      Destinations destinations,
      EtaResult eta,
      double speedBps,
      float progress,
      SignalAspect signalAspect,
      Optional<LayoverRegistry.LayoverCandidate> layover) {
    Map<String, String> placeholders = new HashMap<>();
    String safeNext = nextStation == null || nextStation.isBlank() ? "-" : nextStation;
    String etaStatus = eta != null ? eta.statusText() : "-";
    String etaMinutes =
        eta != null && eta.etaMinutesRounded() >= 0 ? String.valueOf(eta.etaMinutesRounded()) : "-";
    String unit = localeTextOrDefault("display.hud.bossbar.unit.kmh", "km/h");
    String labelLine = localeTextOrDefault("display.hud.bossbar.label.line", "Line");
    String labelNext = localeTextOrDefault("display.hud.bossbar.label.next", "Next");
    String signalStatus = resolveSignalStatus(signalAspect);
    String serviceStatus = resolveServiceStatus(layover);
    String lineCode = "-";
    String lineName = "-";
    String lineColor = "";
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
    String lineLabel = resolveLineLabel(lineName, lineCode);
    String safeLine = lineLabel.isBlank() ? "-" : lineLabel;

    placeholders.put("line", safeLine);
    placeholders.put("line_code", safeOrDash(lineCode));
    placeholders.put("line_name", safeOrDash(lineName));
    placeholders.put("line_color", lineColor == null ? "" : lineColor);
    placeholders.put("operator", safeOrDash(operatorCode));
    placeholders.put("route_code", safeOrDash(routeCode));
    placeholders.put("route_id", safeOrDash(routeId));
    placeholders.put("route_name", safeOrDash(routeName));
    placeholders.put("next_station", safeNext);
    placeholders.put("dest_eor", destinations.eor());
    placeholders.put("dest_eop", destinations.eop());
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
    placeholders.put("progress_percent", formatPercent(progress));
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

  private Destinations resolveDestinations(Optional<RouteDefinition> routeOpt) {
    if (routeOpt == null || routeOpt.isEmpty()) {
      return Destinations.empty();
    }
    RouteDefinition route = routeOpt.get();
    String eor = resolveEndOfRoute(route);
    String eop = resolveEndOfOperation(route, eor);
    return new Destinations(eor, eop);
  }

  private String resolveEndOfRoute(RouteDefinition route) {
    if (route == null || route.waypoints().isEmpty()) {
      return "-";
    }
    NodeId last = route.waypoints().get(route.waypoints().size() - 1);
    return HudWaypointLabel.stationLabel(last);
  }

  private String resolveEndOfOperation(RouteDefinition route, String fallback) {
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
      Optional<String> nodeId = stop.waypointNodeId().filter(id -> !id.isBlank());
      if (nodeId.isPresent()) {
        return HudWaypointLabel.stationLabel(NodeId.of(nodeId.get()));
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

  private record Destinations(String eor, String eop) {
    private static Destinations empty() {
      return new Destinations("-", "-");
    }
  }
}
