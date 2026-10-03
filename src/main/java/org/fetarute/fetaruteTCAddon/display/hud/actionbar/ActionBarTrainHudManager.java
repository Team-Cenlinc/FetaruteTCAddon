package org.fetarute.fetaruteTCAddon.display.hud.actionbar;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
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
import org.fetarute.fetaruteTCAddon.display.hud.HudLanguageRotation;
import org.fetarute.fetaruteTCAddon.display.hud.HudState;
import org.fetarute.fetaruteTCAddon.display.hud.HudStateTracker;
import org.fetarute.fetaruteTCAddon.display.hud.HudText;
import org.fetarute.fetaruteTCAddon.display.hud.TrainHudContext;
import org.fetarute.fetaruteTCAddon.display.hud.TrainHudContextResolver;
import org.fetarute.fetaruteTCAddon.display.hud.bossbar.BossBarHudTemplate;
import org.fetarute.fetaruteTCAddon.display.hud.bossbar.BossBarProgressExpression;
import org.fetarute.fetaruteTCAddon.display.hud.bossbar.BossBarProgressTracker;
import org.fetarute.fetaruteTCAddon.display.template.HudDefaultTemplateService;
import org.fetarute.fetaruteTCAddon.display.template.HudTemplateService;
import org.fetarute.fetaruteTCAddon.display.template.HudTemplateType;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 车上 ActionBar HUD：以纯文本输出线路/下一站等状态。
 *
 * <p>模板解析与状态分支逻辑与 BossBar 共用（HudState + BossBarHudTemplate）。 ActionBar 仅负责按 tick 推送文本，不持久化 UI 状态。
 */
public final class ActionBarTrainHudManager implements Listener {

  private static final long DEPARTING_WINDOW_TICKS = 60L;

  /**
   * 文字没变时多久重发一次（毫秒）。原版 ActionBar 约 2 秒内完全不透明、之后淡出；刷新间隔默认 0.5 秒， 文字不变也每次重发是白发包，隔 1.5
   * 秒补一次足以一直显示，被其他插件盖掉后也会很快回来。
   */
  private static final long RESEND_MILLIS = 1500L;

  private static final String DEFAULT_TEMPLATE =
      "<white>欢迎乘坐 {company}/{operator} 列车</white> <gray>|</gray> <white>{line}</white>"
          + " <gray>|</gray> <white>前往 {dest_eop}</white>";

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

  /** 正在显示的玩家，以及上次发出的文字与时刻。 */
  private final Map<UUID, Shown> showingPlayers = new HashMap<>();

  public ActionBarTrainHudManager(
      FetaruteTCAddon plugin,
      LocaleManager locale,
      ConfigManager configManager,
      TrainHudContextResolver contextResolver,
      HudTemplateService templateService,
      HudDefaultTemplateService defaultTemplateService,
      Consumer<String> debugLogger) {
    this.plugin = plugin;
    this.locale = locale;
    this.configManager = configManager;
    this.templateService = templateService;
    this.defaultTemplateService = defaultTemplateService;
    this.debugLogger = debugLogger != null ? debugLogger : msg -> {};
    this.contextResolver = Objects.requireNonNull(contextResolver, "contextResolver");
  }

  public void register() {
    Bukkit.getPluginManager().registerEvents(this, plugin);
    debugLogger.accept("ActionBarTrainHudManager registered");
  }

  public void unregister() {
    HandlerList.unregisterAll(this);
    debugLogger.accept("ActionBarTrainHudManager unregistered");
  }

  public void tick() {
    Set<String> activeTrains = new HashSet<>();
    Set<UUID> currentPlayers = new HashSet<>();
    for (Player player : Bukkit.getOnlinePlayers()) {
      Optional<MinecartGroup> groupOpt = contextResolver.resolveGroup(player);
      if (groupOpt.isEmpty()) {
        clear(player);
        continue;
      }
      Optional<String> trainName = showOrUpdate(player, groupOpt.get());
      if (trainName.isPresent()) {
        activeTrains.add(trainName.get());
        currentPlayers.add(player.getUniqueId());
      }
    }
    progressTracker.retain(activeTrains);
    stateTracker.retain(activeTrains);
    clearInactivePlayers(currentPlayers);
  }

  public void shutdown() {
    for (Player player : Bukkit.getOnlinePlayers()) {
      clear(player);
    }
    progressTracker.clear();
    stateTracker.clear();
    templateCache.clear();
    showingPlayers.clear();
    debugLogger.accept("ActionBarTrainHudManager shutdown");
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    clear(event.getPlayer());
  }

  @EventHandler
  public void onKick(PlayerKickEvent event) {
    clear(event.getPlayer());
  }

  private Optional<String> showOrUpdate(Player player, MinecartGroup group) {
    TrainProperties properties = group.getProperties();
    if (properties == null) {
      clear(player);
      return Optional.empty();
    }

    String trainName = properties.getTrainName();
    if (trainName == null || trainName.isBlank()) {
      clear(player);
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
            ? templateService.resolveTemplateForLine(
                HudTemplateType.ACTIONBAR, context.currentLine())
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
    resolveProgress(template, placeholders, baseProgress);
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
    state =
        HudStateTracker.applyOutOfService(
            state, context.outOfService(), template.defines(HudState.OUT_OF_SERVICE));
    String templateLine =
        template.resolveLine(state, HudLanguageRotation.nowTicks(), placeholders).orElse("");
    send(player, HudText.apply(templateLine, placeholders), nowMillis);
    return Optional.of(trainName);
  }

  private void showDestinationOnly(Player player, TrainProperties properties) {
    String destination = properties == null ? null : properties.getDestination();
    if (destination == null || destination.isBlank()) {
      destination = localeTextOrDefault("display.hud.actionbar.tc_destination.empty", "暂无目的地");
    } else {
      destination = destination.trim();
    }
    send(player, HudText.escape(destination), System.currentTimeMillis());
  }

  /** 文字变了，或距上次发送已超过 {@link #RESEND_MILLIS}，才解析并发送。 */
  private void send(Player player, String text, long nowMillis) {
    if (!needsSend(showingPlayers.get(player.getUniqueId()), text, nowMillis)) {
      return;
    }
    player.sendActionBar(HudText.parse(text, debugLogger));
    showingPlayers.put(player.getUniqueId(), new Shown(text, nowMillis));
  }

  /** 文字变了、从没发过，或同样的文字已经显示了 {@link #RESEND_MILLIS}。 */
  static boolean needsSend(Shown last, String text, long nowMillis) {
    return last == null
        || !last.text().equals(text)
        || nowMillis - last.atMillis() >= RESEND_MILLIS;
  }

  /** 上次发出的文字与时刻。 */
  record Shown(String text, long atMillis) {}

  private void clear(Player player) {
    if (player == null) {
      return;
    }
    if (showingPlayers.remove(player.getUniqueId()) != null) {
      player.sendActionBar(Component.empty());
    }
  }

  private void clearInactivePlayers(Set<UUID> currentPlayers) {
    if (currentPlayers == null || currentPlayers.isEmpty()) {
      for (UUID uuid : new HashSet<>(showingPlayers.keySet())) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
          player.sendActionBar(Component.empty());
        }
      }
      showingPlayers.clear();
      return;
    }
    for (UUID uuid : new HashSet<>(showingPlayers.keySet())) {
      if (!currentPlayers.contains(uuid)) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
          player.sendActionBar(Component.empty());
        }
        showingPlayers.remove(uuid);
      }
    }
  }

  private String resolveTemplate(Optional<String> templateOpt) {
    if (templateOpt != null && templateOpt.isPresent() && !templateOpt.get().isBlank()) {
      return templateOpt.get();
    }
    if (configManager != null && configManager.current() != null) {
      Optional<String> configured =
          configManager.current().runtimeSettings().hudActionBarTemplate();
      if (configured.isPresent() && !configured.get().isBlank()) {
        return configured.get();
      }
    }
    if (defaultTemplateService != null) {
      Optional<String> defaultTemplate =
          defaultTemplateService.resolveTemplate(HudTemplateType.ACTIONBAR);
      if (defaultTemplate.isPresent() && !defaultTemplate.get().isBlank()) {
        return defaultTemplate.get();
      }
    }
    String fallback = localeTextOrDefault("display.hud.actionbar.template", DEFAULT_TEMPLATE);
    return fallback.isBlank() ? DEFAULT_TEMPLATE : fallback;
  }

  private BossBarHudTemplate resolveParsedTemplate(String rawTemplate) {
    String key = rawTemplate == null ? "" : rawTemplate;
    return templateCache.computeIfAbsent(
        key, value -> BossBarHudTemplate.parse(value, debugLogger));
  }

  private void resolveProgress(
      BossBarHudTemplate template, Map<String, String> placeholders, float baseProgress) {
    if (template == null) {
      return;
    }
    Optional<String> expressionOpt = template.progressExpression();
    if (expressionOpt.isEmpty()) {
      return;
    }
    OptionalDouble parsed =
        BossBarProgressExpression.evaluate(expressionOpt.get(), placeholders, debugLogger);
    if (parsed.isEmpty()) {
      return;
    }
    float value = (float) parsed.getAsDouble();
    float clamped = clampProgress(value);
    contextResolver.applyProgressPlaceholders(placeholders, clamped);
  }

  private String localeTextOrDefault(String key, String fallback) {
    if (locale == null) {
      return fallback;
    }
    String value = locale.text(key);
    return value.equals(key) ? fallback : value;
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
