package org.fetarute.fetaruteTCAddon.display.hud.scoreboard;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaService;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.display.hud.HudState;
import org.fetarute.fetaruteTCAddon.display.hud.HudStateTracker;
import org.fetarute.fetaruteTCAddon.display.hud.TrainHudContext;
import org.fetarute.fetaruteTCAddon.display.hud.TrainHudContextResolver;
import org.fetarute.fetaruteTCAddon.display.hud.bossbar.BossBarProgressTracker;
import org.fetarute.fetaruteTCAddon.display.template.HudDefaultTemplateService;
import org.fetarute.fetaruteTCAddon.display.template.HudTemplateService;
import org.fetarute.fetaruteTCAddon.display.template.HudTemplateType;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 车上 Scoreboard HUD：用于车内 LCD/PIDS 多行展示。
 *
 * <p>仅在玩家乘坐列车时显示，离车后恢复原有 Scoreboard。
 */
public final class ScoreboardTrainHudManager implements Listener {

  private static final long DEPARTING_WINDOW_TICKS = 60L;
  private static final String DEFAULT_TEMPLATE =
      "lines: 10\n"
          + "page_duration_ticks: 60\n"
          + "pages:\n"
          + "  IN_TRIP_1:\n"
          + "    title: \"{line} -> {dest_eop}\"\n"
          + "    kind: list_page\n"
          + "    source: next_stops\n"
          + "    limit: 12\n"
          + "    header:\n"
          + "      - \"Upcoming\"\n"
          + "    row: \"{index}. {station} {eta}\"\n"
          + "    window:\n"
          + "      size: 3\n"
          + "      step: 3\n"
          + "      fixed: 3\n"
          + "      mode: chunk\n"
          + "      periodTicks: 120\n"
          + "      resetOnStop: true\n";

  private static final String OBJECTIVE_NAME = "fta_hud";
  private static final String OBJECTIVE_TITLE = " ";
  private static final LegacyComponentSerializer LEGACY_SERIALIZER =
      LegacyComponentSerializer.legacySection();
  private static final NamedTextColor[] LINE_ENTRY_COLORS =
      new NamedTextColor[] {
        NamedTextColor.BLACK,
        NamedTextColor.DARK_BLUE,
        NamedTextColor.DARK_GREEN,
        NamedTextColor.DARK_AQUA,
        NamedTextColor.DARK_RED,
        NamedTextColor.DARK_PURPLE,
        NamedTextColor.GOLD,
        NamedTextColor.GRAY,
        NamedTextColor.DARK_GRAY,
        NamedTextColor.BLUE,
        NamedTextColor.GREEN,
        NamedTextColor.AQUA,
        NamedTextColor.RED,
        NamedTextColor.LIGHT_PURPLE,
        NamedTextColor.YELLOW
      };
  private static final String[] LINE_ENTRIES = buildLineEntries();

  private final FetaruteTCAddon plugin;
  private final ConfigManager configManager;
  private final HudTemplateService templateService;
  private final HudDefaultTemplateService defaultTemplateService;
  private final Consumer<String> debugLogger;
  private final TrainHudContextResolver contextResolver;

  private final BossBarProgressTracker progressTracker = new BossBarProgressTracker();
  private final HudStateTracker stateTracker = new HudStateTracker(DEPARTING_WINDOW_TICKS * 50L);
  private final Map<String, ScoreboardHudTemplate> templateCache = new HashMap<>();
  private final Map<UUID, PlayerHudState> playerStates = new HashMap<>();
  private final Map<WindowKey, WindowState> windowStates = new HashMap<>();
  private final Map<String, TrainFrame> trainFrames = new HashMap<>();
  private long tickCounter = 0L;

  public ScoreboardTrainHudManager(
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
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.configManager = configManager;
    this.templateService = templateService;
    this.defaultTemplateService = defaultTemplateService;
    this.debugLogger = debugLogger != null ? debugLogger : msg -> {};
    this.contextResolver =
        new TrainHudContextResolver(
            plugin,
            locale,
            Objects.requireNonNull(etaService, "etaService"),
            Objects.requireNonNull(routeDefinitions, "routeDefinitions"),
            routeProgressRegistry,
            layoverRegistry,
            templateService,
            this.debugLogger);
  }

  public void register() {
    Bukkit.getPluginManager().registerEvents(this, plugin);
    debugLogger.accept("ScoreboardTrainHudManager registered");
  }

  public void unregister() {
    HandlerList.unregisterAll(this);
    debugLogger.accept("ScoreboardTrainHudManager unregistered");
  }

  public void tick() {
    int intervalTicks = resolveIntervalTicks();
    tickCounter += intervalTicks;
    Set<String> activeTrains = new HashSet<>();
    Set<UUID> activePlayers = new HashSet<>();
    for (Player player : Bukkit.getOnlinePlayers()) {
      Optional<MinecartGroup> groupOpt = contextResolver.resolveGroup(player);
      if (groupOpt.isEmpty()) {
        clear(player);
        continue;
      }
      Optional<String> trainName = showOrUpdate(player, groupOpt.get());
      if (trainName.isPresent()) {
        activeTrains.add(trainName.get());
        activePlayers.add(player.getUniqueId());
      }
    }
    progressTracker.retain(activeTrains);
    stateTracker.retain(activeTrains);
    retainWindowStates(activeTrains);
    clearInactivePlayers(activePlayers);
  }

  public void shutdown() {
    for (Player player : Bukkit.getOnlinePlayers()) {
      clear(player);
    }
    progressTracker.clear();
    stateTracker.clear();
    templateCache.clear();
    contextResolver.clearCaches();
    playerStates.clear();
    windowStates.clear();
    trainFrames.clear();
    debugLogger.accept("ScoreboardTrainHudManager shutdown");
  }

  /** 清理站点/公司等缓存，下次 tick 时重新从存储加载。 */
  public void clearCaches() {
    contextResolver.clearCaches();
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
      clear(player);
      return Optional.empty();
    }
    TrainHudContext context = contextOpt.get();

    Optional<String> templateOpt =
        templateService != null
            ? templateService.resolveTemplate(
                HudTemplateType.PLAYER_DISPLAY,
                context.routeDefinition().flatMap(RouteDefinition::metadata))
            : Optional.empty();
    ScoreboardHudTemplate template = resolveParsedTemplate(resolveTemplate(templateOpt));
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
    TrainFrameDelta frameDelta = updateTrainFrame(trainName, context, state);
    Optional<ScoreboardHudTemplate.Page> pageOpt = template.resolvePage(state, tickCounter);
    String title = resolveTitle(template, pageOpt, placeholders);
    List<String> resolvedLines =
        renderPage(pageOpt, trainName, context, placeholders, state, frameDelta, tickCounter);
    List<String> normalized = normalizeLines(resolvedLines, template.lineCount());

    PlayerHudState stateHolder = ensureScoreboard(player, template.lineCount());
    updateScoreboardTitle(stateHolder, title);
    if (!normalized.equals(stateHolder.lastLines)) {
      updateScoreboardLines(stateHolder, normalized);
      stateHolder.lastLines = List.copyOf(normalized);
    }
    return Optional.of(trainName);
  }

  private PlayerHudState ensureScoreboard(Player player, int lineCount) {
    UUID uuid = player.getUniqueId();
    PlayerHudState state = playerStates.computeIfAbsent(uuid, id -> new PlayerHudState());
    if (state.scoreboard == null || state.lineCount != lineCount) {
      ScoreboardManager manager = Bukkit.getScoreboardManager();
      Scoreboard scoreboard = manager.getNewScoreboard();
      Objective objective = scoreboard.getObjective(OBJECTIVE_NAME);
      if (objective == null) {
        objective =
            scoreboard.registerNewObjective(
                OBJECTIVE_NAME, Criteria.DUMMY, Component.text(OBJECTIVE_TITLE));
        objective.displayName(Component.text(OBJECTIVE_TITLE));
      } else {
        objective.displayName(Component.text(OBJECTIVE_TITLE));
      }
      objective.setDisplaySlot(DisplaySlot.SIDEBAR);
      if (state.previousScoreboard == null) {
        state.previousScoreboard = player.getScoreboard();
      }
      state.scoreboard = scoreboard;
      state.objective = objective;
      state.lineCount = lineCount;
      state.lastLines = List.of();
      state.lastTitle = "";
      prepareLines(state);
    }
    if (player.getScoreboard() != state.scoreboard) {
      player.setScoreboard(state.scoreboard);
    }
    return state;
  }

  private void prepareLines(PlayerHudState state) {
    if (state.scoreboard == null || state.objective == null) {
      return;
    }
    int lineCount = Math.min(state.lineCount, ScoreboardHudTemplate.MAX_LINES);
    for (int i = 0; i < lineCount; i++) {
      String entry = lineEntry(i);
      String teamName = teamName(i);
      Team team = state.scoreboard.getTeam(teamName);
      if (team == null) {
        team = state.scoreboard.registerNewTeam(teamName);
      }
      team.addEntry(entry);
      state.objective.getScore(entry).setScore(lineCount - i);
    }
  }

  private void updateScoreboardLines(PlayerHudState state, List<String> lines) {
    if (state.scoreboard == null) {
      return;
    }
    int lineCount = Math.min(state.lineCount, lines.size());
    for (int i = 0; i < lineCount; i++) {
      String entry = lineEntry(i);
      Team team = state.scoreboard.getTeam(teamName(i));
      if (team == null) {
        continue;
      }
      String raw = lines.get(i);
      Component component = ScoreboardHudTemplateRenderer.renderResolved(raw, debugLogger);
      team.prefix(component);
      team.suffix(Component.empty());
      if (!team.hasEntry(entry)) {
        team.addEntry(entry);
      }
    }
  }

  private void updateScoreboardTitle(PlayerHudState state, String title) {
    if (state == null || state.objective == null) {
      return;
    }
    String resolved = title == null || title.isBlank() ? OBJECTIVE_TITLE : title;
    if (resolved.equals(state.lastTitle)) {
      return;
    }
    Component component = ScoreboardHudTemplateRenderer.renderResolved(resolved, debugLogger);
    state.objective.displayName(component);
    state.lastTitle = resolved;
  }

  private String resolveTemplate(Optional<String> templateOpt) {
    if (templateOpt != null && templateOpt.isPresent() && !templateOpt.get().isBlank()) {
      return templateOpt.get();
    }
    if (configManager != null && configManager.current() != null) {
      Optional<String> configured =
          configManager.current().runtimeSettings().hudPlayerDisplayTemplate();
      if (configured.isPresent() && !configured.get().isBlank()) {
        return configured.get();
      }
    }
    if (defaultTemplateService != null) {
      Optional<String> defaultTemplate =
          defaultTemplateService.resolveTemplate(HudTemplateType.PLAYER_DISPLAY);
      if (defaultTemplate.isPresent() && !defaultTemplate.get().isBlank()) {
        return defaultTemplate.get();
      }
    }
    return DEFAULT_TEMPLATE;
  }

  private ScoreboardHudTemplate resolveParsedTemplate(String rawTemplate) {
    String key = rawTemplate == null ? "" : rawTemplate;
    return templateCache.computeIfAbsent(
        key, value -> ScoreboardHudTemplate.parse(value, debugLogger));
  }

  private String resolveTitle(
      ScoreboardHudTemplate template,
      Optional<ScoreboardHudTemplate.Page> pageOpt,
      Map<String, String> placeholders) {
    String title =
        pageOpt
            .flatMap(ScoreboardHudTemplate.Page::title)
            .orElseGet(() -> template.title().orElse(""));
    return ScoreboardHudTemplateRenderer.applyPlaceholders(title, placeholders);
  }

  private List<String> renderPage(
      Optional<ScoreboardHudTemplate.Page> pageOpt,
      String trainName,
      TrainHudContext context,
      Map<String, String> placeholders,
      HudState state,
      TrainFrameDelta frameDelta,
      long tick) {
    if (pageOpt.isEmpty()) {
      return List.of();
    }
    ScoreboardHudTemplate.Page page = pageOpt.get();
    if (page instanceof ScoreboardHudTemplate.StaticPage staticPage) {
      return renderStaticPage(staticPage, placeholders);
    }
    if (page instanceof ScoreboardHudTemplate.ListPage listPage) {
      return renderListPage(listPage, trainName, context, placeholders, state, frameDelta, tick);
    }
    return List.of();
  }

  private List<String> renderStaticPage(
      ScoreboardHudTemplate.StaticPage page, Map<String, String> placeholders) {
    List<String> output = new ArrayList<>();
    for (String line : page.lines()) {
      output.add(ScoreboardHudTemplateRenderer.applyPlaceholders(line, placeholders));
    }
    return output;
  }

  private List<String> renderListPage(
      ScoreboardHudTemplate.ListPage page,
      String trainName,
      TrainHudContext context,
      Map<String, String> placeholders,
      HudState state,
      TrainFrameDelta frameDelta,
      long tick) {
    List<String> output = new ArrayList<>();
    int totalStops = contextResolver.resolveUpcomingStops(context, 0).total();
    int cappedTotalStops = Math.min(totalStops, page.limit());
    int fixedRows = Math.min(page.window().fixed(), page.limit());
    int windowRows = Math.max(0, page.window().size());
    int rowLines = page.rowLines().isEmpty() ? 1 : page.rowLines().size();
    WindowKey key = new WindowKey(trainName, state, page.order());
    int remainingStops = Math.max(0, cappedTotalStops - fixedRows);
    WindowState windowState =
        resolveWindowState(
            key, frameDelta, remainingStops, page.window(), frameDelta.nextStopChanged(), tick);
    int windowOffset = computeWindowOffset(windowState, tick, remainingStops, page.window());
    int limit = Math.min(page.limit(), fixedRows + windowOffset + windowRows);
    TrainHudContextResolver.UpcomingStops upcoming =
        contextResolver.resolveUpcomingStops(context, limit);

    output.addAll(renderLineList(page.header(), placeholders));
    if (fixedRows + windowRows <= 0) {
      output.addAll(renderLineList(page.footer(), placeholders));
      return output;
    }
    if (cappedTotalStops <= 0) {
      String empty = page.empty().orElse("-");
      String rendered = ScoreboardHudTemplateRenderer.applyPlaceholders(empty, placeholders);
      for (int i = 0; i < fixedRows + windowRows; i++) {
        for (int j = 0; j < rowLines; j++) {
          output.add(rendered);
        }
      }
    } else {
      output.addAll(
          renderUpcomingRows(upcoming.stops(), 0, fixedRows, placeholders, page.rowLines()));
      output.addAll(
          renderUpcomingRows(
              upcoming.stops(),
              fixedRows + windowOffset,
              windowRows,
              placeholders,
              page.rowLines()));
    }
    output.addAll(renderLineList(page.footer(), placeholders));
    return output;
  }

  private List<String> renderLineList(List<String> lines, Map<String, String> placeholders) {
    if (lines == null || lines.isEmpty()) {
      return List.of();
    }
    List<String> output = new ArrayList<>();
    for (String line : lines) {
      output.add(ScoreboardHudTemplateRenderer.applyPlaceholders(line, placeholders));
    }
    return output;
  }

  private List<String> renderUpcomingRows(
      List<TrainHudContextResolver.UpcomingStop> stops,
      int startIndex,
      int rowCount,
      Map<String, String> placeholders,
      List<String> rowLines) {
    List<String> output = new ArrayList<>();
    for (int i = 0; i < rowCount; i++) {
      int index = startIndex + i;
      Map<String, String> itemPlaceholders = new HashMap<>(placeholders);
      if (stops != null && index >= 0 && index < stops.size()) {
        TrainHudContextResolver.UpcomingStop stop = stops.get(index);
        String seq = String.valueOf(index + 1);
        itemPlaceholders.put("idx", seq);
        itemPlaceholders.put("index", seq);
        itemPlaceholders.put("station", stop.display().label());
        itemPlaceholders.put("station_code", stop.display().code());
        itemPlaceholders.put("station_lang2", stop.display().lang2());
        itemPlaceholders.put("eta", formatEta(stop.eta()));
        itemPlaceholders.put("eta_minutes", formatEtaMinutes(stop.eta()));
        contextResolver.applyEtaStatusPlaceholders(itemPlaceholders, stop.eta());
      } else {
        itemPlaceholders.put("idx", "");
        itemPlaceholders.put("index", "");
        itemPlaceholders.put("station", "-");
        itemPlaceholders.put("station_code", "-");
        itemPlaceholders.put("station_lang2", "-");
        itemPlaceholders.put("eta", "");
        itemPlaceholders.put("eta_minutes", "-");
        contextResolver.applyEtaStatusPlaceholders(itemPlaceholders, null);
      }
      for (String rowFormat : rowLines) {
        output.add(ScoreboardHudTemplateRenderer.applyPlaceholders(rowFormat, itemPlaceholders));
      }
    }
    return output;
  }

  private TrainFrameDelta updateTrainFrame(
      String trainName, TrainHudContext context, HudState state) {
    String routeKey = context.routeDefinition().map(route -> route.id().value()).orElse("");
    String nextStopKey = nextStopKey(context);
    TrainFrame current = trainFrames.get(trainName);
    boolean routeChanged = current == null || !routeKey.equals(current.routeKey());
    boolean nextStopChanged = current == null || !nextStopKey.equals(current.nextStopKey());
    HudState previousState = current == null ? null : current.state();
    if (routeChanged || shouldResetOnStateChange(previousState, state)) {
      clearWindowStates(trainName);
    }
    trainFrames.put(trainName, new TrainFrame(routeKey, nextStopKey, state));
    return new TrainFrameDelta(routeKey, nextStopKey, nextStopChanged);
  }

  private WindowState resolveWindowState(
      WindowKey key,
      TrainFrameDelta frameDelta,
      int totalStops,
      ScoreboardHudTemplate.WindowConfig window,
      boolean nextStopChanged,
      long tick) {
    WindowState current = windowStates.get(key);
    boolean routeChanged = current == null || !frameDelta.routeKey().equals(current.routeKey());
    boolean stopReset = nextStopChanged && window.resetOnStop();
    if (current == null || routeChanged || stopReset) {
      WindowState next =
          new WindowState(frameDelta.routeKey(), frameDelta.nextStopKey(), tick, totalStops);
      windowStates.put(key, next);
      return next;
    }
    if (current.totalStops() != totalStops) {
      WindowState next =
          new WindowState(
              current.routeKey(), current.nextStopKey(), current.startTick(), totalStops);
      windowStates.put(key, next);
      return next;
    }
    return current;
  }

  private int computeWindowOffset(
      WindowState state, long tick, int totalStops, ScoreboardHudTemplate.WindowConfig window) {
    if (state == null) {
      return 0;
    }
    int safeWindowSize = Math.max(1, window.size());
    int safeStep = Math.max(1, window.step());
    int safeDuration = Math.max(1, window.periodTicks());
    if (totalStops <= safeWindowSize) {
      return 0;
    }
    int maxIndex = resolveMaxWindowIndex(totalStops, safeWindowSize, safeStep, window.mode());
    if (maxIndex <= 0) {
      return 0;
    }
    long windowIndex = Math.max(0L, (tick - state.startTick()) / safeDuration);
    int index = (int) (windowIndex % (maxIndex + 1));
    return index * safeStep;
  }

  private int resolveMaxWindowIndex(
      int totalStops, int windowSize, int step, ScoreboardHudTemplate.WindowMode mode) {
    if (totalStops <= windowSize || step <= 0) {
      return 0;
    }
    if (mode == ScoreboardHudTemplate.WindowMode.SLIDE) {
      int maxOffset = totalStops - windowSize;
      return Math.max(0, maxOffset / step);
    }
    return Math.max(0, (int) Math.ceil(totalStops / (double) step) - 1);
  }

  private String nextStopKey(TrainHudContext context) {
    if (context == null || context.nextStation() == null) {
      return "";
    }
    String code = context.nextStation().code();
    if (code != null && !code.isBlank() && !"-".equals(code)) {
      return code.toLowerCase(Locale.ROOT);
    }
    String label = context.nextStation().label();
    if (label != null && !label.isBlank()) {
      return label.toLowerCase(Locale.ROOT);
    }
    return "";
  }

  private boolean shouldResetOnStateChange(HudState previous, HudState current) {
    if (previous == null || current == null) {
      return true;
    }
    if (previous == current) {
      return false;
    }
    return isResetState(previous) || isResetState(current);
  }

  private boolean isResetState(HudState state) {
    return state == HudState.ARRIVING
        || state == HudState.TERM_ARRIVING
        || state == HudState.IDLE
        || state == HudState.ON_LAYOVER
        || state == HudState.AT_LAST_STATION;
  }

  private void clearWindowStates(String trainName) {
    if (trainName == null || trainName.isBlank()) {
      return;
    }
    windowStates.keySet().removeIf(key -> trainName.equals(key.trainName()));
  }

  private List<String> normalizeLines(List<String> lines, int lineCount) {
    int safeLineCount = Math.min(Math.max(1, lineCount), ScoreboardHudTemplate.MAX_LINES);
    List<String> normalized = new ArrayList<>(safeLineCount);
    if (lines != null) {
      for (String line : lines) {
        if (normalized.size() >= safeLineCount) {
          break;
        }
        normalized.add(line == null ? "" : line);
      }
    }
    while (normalized.size() < safeLineCount) {
      normalized.add("");
    }
    return normalized;
  }

  private String formatEta(org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaResult eta) {
    if (eta == null) {
      return "-";
    }
    String status = contextResolver.formatEtaStatus(eta);
    return status == null || status.isBlank() ? "-" : status;
  }

  private String formatEtaMinutes(org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaResult eta) {
    if (eta == null) {
      return "-";
    }
    int minutes = eta.etaMinutesRounded();
    return minutes >= 0 ? String.valueOf(minutes) : "-";
  }

  private void clear(Player player) {
    if (player == null) {
      return;
    }
    PlayerHudState state = playerStates.remove(player.getUniqueId());
    if (state == null) {
      return;
    }
    if (player.getScoreboard() == state.scoreboard && state.previousScoreboard != null) {
      player.setScoreboard(state.previousScoreboard);
    }
  }

  private void clearInactivePlayers(Set<UUID> currentPlayers) {
    if (currentPlayers == null || currentPlayers.isEmpty()) {
      for (UUID uuid : new HashSet<>(playerStates.keySet())) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
          clear(player);
        }
      }
      return;
    }
    for (UUID uuid : new HashSet<>(playerStates.keySet())) {
      if (!currentPlayers.contains(uuid)) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
          clear(player);
        }
      }
    }
  }

  private void retainWindowStates(Set<String> activeTrains) {
    if (activeTrains == null || activeTrains.isEmpty()) {
      windowStates.clear();
      trainFrames.clear();
      return;
    }
    windowStates.keySet().removeIf(key -> !activeTrains.contains(key.trainName()));
    trainFrames.keySet().removeIf(trainName -> !activeTrains.contains(trainName));
  }

  private int resolveIntervalTicks() {
    if (configManager != null && configManager.current() != null) {
      int interval = configManager.current().runtimeSettings().hudPlayerDisplayTickIntervalTicks();
      return Math.max(1, interval);
    }
    return 10;
  }

  private String teamName(int index) {
    return "fta_hud_" + index;
  }

  private String lineEntry(int index) {
    if (index < 0 || index >= LINE_ENTRIES.length) {
      return LEGACY_SERIALIZER.serialize(Component.text(" ", NamedTextColor.WHITE)) + index;
    }
    return LINE_ENTRIES[index];
  }

  private static String[] buildLineEntries() {
    String[] entries = new String[LINE_ENTRY_COLORS.length];
    for (int i = 0; i < LINE_ENTRY_COLORS.length; i++) {
      entries[i] = LEGACY_SERIALIZER.serialize(Component.text(" ", LINE_ENTRY_COLORS[i]));
    }
    return entries;
  }

  private static final class PlayerHudState {
    private Scoreboard scoreboard;
    private Scoreboard previousScoreboard;
    private Objective objective;
    private int lineCount;
    private List<String> lastLines = List.of();
    private String lastTitle = "";
  }

  private record WindowKey(String trainName, HudState state, int order) {
    private WindowKey {
      trainName = trainName == null ? "" : trainName;
      state = state == null ? HudState.IN_TRIP : state;
    }
  }

  private record WindowState(String routeKey, String nextStopKey, long startTick, int totalStops) {
    private WindowState {
      routeKey = routeKey == null ? "" : routeKey;
      nextStopKey = nextStopKey == null ? "" : nextStopKey;
    }
  }

  private record TrainFrame(String routeKey, String nextStopKey, HudState state) {
    private TrainFrame {
      routeKey = routeKey == null ? "" : routeKey;
      nextStopKey = nextStopKey == null ? "" : nextStopKey;
      state = state == null ? HudState.IN_TRIP : state;
    }
  }

  private record TrainFrameDelta(String routeKey, String nextStopKey, boolean nextStopChanged) {
    private TrainFrameDelta {
      routeKey = routeKey == null ? "" : routeKey;
      nextStopKey = nextStopKey == null ? "" : nextStopKey;
    }
  }
}
