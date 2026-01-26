package org.fetarute.fetaruteTCAddon.display.hud.bossbar;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
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
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.display.hud.HudState;
import org.fetarute.fetaruteTCAddon.display.hud.HudStateTracker;
import org.fetarute.fetaruteTCAddon.display.hud.TrainHudContext;
import org.fetarute.fetaruteTCAddon.display.hud.TrainHudContextResolver;
import org.fetarute.fetaruteTCAddon.display.template.HudDefaultTemplateService;
import org.fetarute.fetaruteTCAddon.display.template.HudTemplateService;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 车上 BossBar HUD（MVP）：展示线路/下一站/ETA/速度，并用 BossBar 进度条做“下一站到达进度”近似。
 *
 * <p>BossBar 的结构与计算逻辑保持固定（progress/ETA/speed 等），标题模板可通过 HUD 模板服务覆写。 占位符统一由 {@link
 * org.fetarute.fetaruteTCAddon.display.hud.TrainHudContextResolver} 填充， 并额外注入玩家侧车厢号信息（{@code
 * player_carriage_no}/{@code player_carriage_total}）。
 *
 * <p>玩家是否在列车上：从 player.getVehicle() 反查 TrainCarts member/group。
 */
public final class BossBarTrainHudManager implements Listener {

  private static final long DEPARTING_WINDOW_TICKS = 60L;
  private static final String DEFAULT_TEMPLATE =
      "线路 {line} | 下一站 {next_station} | {eta_status} | {speed}";

  private final FetaruteTCAddon plugin;
  private final LocaleManager locale;
  private final ConfigManager configManager;
  private final HudTemplateService templateService;
  private final HudDefaultTemplateService defaultTemplateService;
  private final Consumer<String> debugLogger;
  private final TrainHudContextResolver contextResolver;

  private final BossBarProgressTracker progressTracker = new BossBarProgressTracker();
  private final HudStateTracker stateTracker = new HudStateTracker(DEPARTING_WINDOW_TICKS * 50L);
  private final Map<String, BossBarHudTemplate> templateCache = new HashMap<>();
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
    this.templateService = templateService;
    this.defaultTemplateService = defaultTemplateService;
    this.debugLogger = debugLogger != null ? debugLogger : msg -> {};
    this.contextResolver =
        new TrainHudContextResolver(
            plugin,
            locale,
            etaService,
            routeDefinitions,
            routeProgressRegistry,
            layoverRegistry,
            templateService,
            this.debugLogger);
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
      Optional<MinecartGroup> groupOpt = contextResolver.resolveGroup(player);
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
    contextResolver.clearCaches();
    bars.clear();
    debugLogger.accept("BossBarTrainHudManager shutdown");
  }

  /** 清理站点/公司等缓存，下次 tick 时重新从存储加载。 */
  public void clearCaches() {
    contextResolver.clearCaches();
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

    Optional<TrainHudContext> contextOpt = contextResolver.resolveContext(group);
    if (contextOpt.isEmpty()) {
      showDestinationOnly(player, properties);
      return Optional.of(trainName);
    }
    TrainHudContext context = contextOpt.get();

    Optional<String> templateOpt =
        templateService != null
            ? templateService.resolveBossBarTemplate(
                context.routeDefinition().flatMap(RouteDefinition::metadata))
            : Optional.empty();
    BossBarHudTemplate template = resolveParsedTemplate(resolveTemplate(templateOpt));
    long nowMillis = System.currentTimeMillis();
    float baseProgress =
        progressTracker.progress(
            trainName,
            context.routeIndex(),
            context.eta().etaEpochMillis(),
            nowMillis,
            context.moving());
    Map<String, String> placeholders = contextResolver.buildPlaceholders(context, baseProgress);
    contextResolver.applyPlayerPlaceholders(placeholders, player, group);
    float progress = resolveProgress(template, placeholders, baseProgress);
    boolean terminalArriving = context.terminalNextStop() && context.eta().arriving();
    HudState state =
        stateTracker.resolve(
            trainName,
            context.moving(),
            context.eta().arriving(),
            context.layover().isPresent(),
            context.stop(),
            context.atLastStation(),
            terminalArriving,
            nowMillis);
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
    bar.color(resolveColor(context.eta(), context.signalAspect()));

    player.showBossBar(bar);
    return Optional.of(trainName);
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
    contextResolver.applyProgressPlaceholders(placeholders, clamped);
    return clamped;
  }

  private int resolveIntervalTicks() {
    if (configManager != null && configManager.current() != null) {
      int interval = configManager.current().runtimeSettings().hudBossBarTickIntervalTicks();
      return Math.max(1, interval);
    }
    return 1;
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
}
