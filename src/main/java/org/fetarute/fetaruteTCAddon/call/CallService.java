package org.fetarute.fetaruteTCAddon.call;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.properties.TrainPropertiesStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.api.StationDirectory;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.LineStatus;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLifecycleMode;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.ReclaimManager;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TerminalKeyResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainTagHelper;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.model.TripSource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.OnDemandTrip;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SimpleTicketAssigner;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnDirectiveParser;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnServiceKey;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnTicket;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.TicketAssigner;
import org.fetarute.fetaruteTCAddon.display.pids.PidsRow;
import org.fetarute.fetaruteTCAddon.display.pids.PidsService;
import org.fetarute.fetaruteTCAddon.display.pids.PidsSnapshot;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 叫车：玩家在站台屏或命令里叫一趟车，按需出一张发车票。
 *
 * <p>叫车票（{@link TripSource#ON_DEMAND}）经现有发车流程派车（闭塞、车队上限照查）；派出的车带 {@link
 * SimpleTicketAssigner#TAG_CALLED_TRAIN} 标签：不进时刻表、按表或按间隔的票都不接它，跑完全程到终点后等 {@code
 * call.terminal-wait-seconds}，期间沿途又有人叫车、这趟经过就接着跑，否则派回库。
 *
 * <p>只在服务器主线程调用。
 */
public final class CallService {

  public static final String PERMISSION = "fetarute.call";

  /** 叫来的车预先指定的站台（{@link PlatformPin#format()}）；随车走，重启、改名后照样认得。 */
  public static final String TAG_CALL_PLATFORM = "FTA_CALL_PLATFORM";

  private static final long SWEEP_PERIOD_TICKS = 20L;
  private static final Duration HINT_TTL = Duration.ofSeconds(5);
  private static final Duration LINE_CACHE_TTL = Duration.ofSeconds(10);
  private static final Duration PENDING_MIN_TIMEOUT = Duration.ofMinutes(10);
  private static final Duration RETURN_RETRY = Duration.ofSeconds(10);

  /**
   * 一个方向能不能叫、叫了派哪辆车。
   *
   * @param direction 方向
   * @param verdict 判定
   * @param plan 车源安排；没有车可派时为空
   */
  public record CallOption(
      CallCatalog.CallDirection direction, CallRules.Verdict verdict, Optional<CallPlan> plan) {}

  /**
   * 对外的车源安排。
   *
   * @param etaMinutes 预计几分钟后到站；估不出时为空
   */
  public record CallPlan(OptionalInt etaMinutes) {}

  /** 叫车结果。 */
  public enum Outcome {
    ISSUED,
    UNAVAILABLE,
    NOT_FOUND,
    FAILED
  }

  /**
   * 叫车结果。
   *
   * @param outcome 结果
   * @param option 叫的那个方向（找到时）
   * @param callId 叫车 id（已叫时）
   */
  public record CallResult(Outcome outcome, Optional<CallOption> option, Optional<UUID> callId) {}

  /** 取消结果。 */
  public enum CancelOutcome {
    CANCELLED,
    ALREADY_DEPARTED,
    NOT_FOUND
  }

  /**
   * 叫车预先指定的站台：本站是 DYNAMIC 停靠、玩家右键的是单站台的屏时，这一趟停那条股道；到站时被占才由选台改选，站台屏照常显示站台变更。
   *
   * @param routeId 叫车跑的交路
   * @param stopIndex 本站在交路节点表里的下标
   * @param nodeId 股道节点
   */
  public record PlatformPin(UUID routeId, int stopIndex, String nodeId) {

    public PlatformPin {
      Objects.requireNonNull(routeId, "routeId");
      Objects.requireNonNull(nodeId, "nodeId");
    }

    /** {@code 交路|下标|股道}：节点名里有冒号，用竖线分隔。 */
    public String format() {
      return routeId + "|" + stopIndex + "|" + nodeId;
    }

    /** 解析 {@link #format()} 的写法；不认得时为空。 */
    public static Optional<PlatformPin> parse(String raw) {
      if (raw == null) {
        return Optional.empty();
      }
      String[] parts = raw.trim().split("\\|", -1);
      if (parts.length != 3 || parts[2].isBlank()) {
        return Optional.empty();
      }
      try {
        return Optional.of(
            new PlatformPin(
                UUID.fromString(parts[0]), Integer.parseInt(parts[1]), parts[2].trim()));
      } catch (IllegalArgumentException ex) {
        return Optional.empty();
      }
    }

    Optional<String> at(UUID route, int index) {
      return routeId.equals(route) && stopIndex == index ? Optional.of(nodeId) : Optional.empty();
    }
  }

  /**
   * 一条线路上没有车源的叫车方向（交路校验用）。
   *
   * @param station 车站
   * @param direction 方向
   */
  public record UnsourcedDirection(PidsStationKey station, CallCatalog.CallDirection direction) {}

  /** 还没派出的叫车。 */
  private record PendingCall(
      UUID id,
      UUID playerId,
      PidsStationKey station,
      Set<String> screenPlatforms,
      String directionKey,
      UUID lineId,
      Instant createdAt,
      OptionalInt etaSeconds,
      Optional<PlatformPin> pin) {

    PendingCallRecord record() {
      return new PendingCallRecord(
          id, playerId, station, screenPlatforms, directionKey, lineId, createdAt, etaSeconds);
    }
  }

  /** 叫来的车：车名、标签、跑的交路所属线路、预先指定的站台。 */
  private record CalledTrain(
      String name, CallTag tag, Optional<UUID> lineId, Optional<PlatformPin> pin) {}

  private record HintKey(PidsStationKey station, Set<String> platforms, Set<String> lines) {}

  private record CachedHint(Instant computedAt, boolean callable) {}

  private final FetaruteTCAddon plugin;
  private final CallPlanner planner;
  private final Map<UUID, PendingCall> pending = new ConcurrentHashMap<>();
  private final Map<UUID, Instant> lastCallByPlayer = new ConcurrentHashMap<>();
  private final Map<String, Instant> returnRetryAt = new ConcurrentHashMap<>();
  private final Map<HintKey, CachedHint> hints = new ConcurrentHashMap<>();
  private volatile Map<String, CalledTrain> calledTrains = Map.of();
  private volatile Map<UUID, Line> lines = Map.of();
  private volatile Instant linesLoadedAt = Instant.EPOCH;
  private boolean storedCallsLoaded;
  private BukkitTask task;

  public CallService(FetaruteTCAddon plugin) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.planner = new CallPlanner(plugin);
  }

  // ---------------------------------------------------------------- 生命周期

  /** 开始周期扫描（叫来的车登记、终点回库、叫车超时）；先找回还没派出的叫车。 */
  public void start() {
    stop();
    try {
      restorePending(Instant.now());
    } catch (RuntimeException ex) {
      debug("找回未派出的叫车失败 error=" + ex);
    }
    task =
        Bukkit.getScheduler()
            .runTaskTimer(
                plugin, () -> sweep(Instant.now()), SWEEP_PERIOD_TICKS, SWEEP_PERIOD_TICKS);
  }

  public void stop() {
    if (task != null) {
      task.cancel();
      task = null;
    }
  }

  /** 交路或线路改了：丢掉缓存的走行时分与线路开关。 */
  public void invalidate() {
    planner.clearTimings();
    linesLoadedAt = Instant.EPOCH;
    hints.clear();
  }

  // ---------------------------------------------------------------- 查询

  /**
   * 车站上能叫的车（按方向）。
   *
   * @param station 车站
   * @param platforms 屏幕绑定的站台；空表示全站
   * @param playerId 叫车的玩家（算个人冷却）；空时不算
   * @param now 当前时刻
   */
  public List<CallOption> options(
      PidsStationKey station, Set<String> platforms, Optional<UUID> playerId, Instant now) {
    return options(station, platforms, playerId, now, true);
  }

  /**
   * @param planAll 每个方向都排车源（对话框要据此隐去没有车可派的方向）；为假时只给“除车源外都能叫”的方向排（站台屏提示只关心能不能叫）
   */
  private List<CallOption> options(
      PidsStationKey station,
      Set<String> platforms,
      Optional<UUID> playerId,
      Instant now,
      boolean planAll) {
    List<CallCatalog.CallDirection> directions = directions(station, platforms);
    if (directions.isEmpty()) {
      return List.of();
    }
    List<PidsRow> rows = rowsAt(station);
    Map<UUID, Integer> active = activeCallsByLine();
    Predicate<String> dutyBound = dutyBoundPredicate();
    long cooldown = playerId.map(id -> cooldownSeconds(id, now)).orElse(0L);
    List<CallOption> out = new ArrayList<>(directions.size());
    for (CallCatalog.CallDirection direction : directions) {
      CallRules.Input assumingSource =
          new CallRules.Input(
              true,
              calledMinutes(direction, station, rows, now),
              nextTrainMinutes(direction, rows, now),
              settings().minWaitMinutes(),
              active.getOrDefault(direction.lineId(), 0),
              maxCalls(direction.lineId()),
              cooldown,
              OptionalInt.empty());
      CallRules.Verdict withoutPlan = CallRules.evaluate(assumingSource);
      if (!planAll && !withoutPlan.callable()) {
        out.add(new CallOption(direction, withoutPlan, Optional.empty()));
        continue;
      }
      Optional<CallPlanner.Plan> plan = planner.plan(direction, dutyBound, now);
      CallRules.Verdict verdict =
          CallRules.evaluate(
              new CallRules.Input(
                  plan.isPresent(),
                  assumingSource.calledTrainMinutes(),
                  assumingSource.nextTrainMinutes(),
                  assumingSource.minWaitMinutes(),
                  assumingSource.activeCalls(),
                  assumingSource.maxCalls(),
                  assumingSource.cooldownSeconds(),
                  plan.map(CallPlanner.Plan::etaMinutes).orElse(OptionalInt.empty())));
      out.add(new CallOption(direction, verdict, plan.map(p -> new CallPlan(p.etaMinutes()))));
    }
    return List.copyOf(out);
  }

  /**
   * 站台屏要不要写“可右键本屏叫车”：屏幕上有一个方向此刻能叫（不算个人冷却）。按 {@link #HINT_TTL} 缓存。
   *
   * @param station 车站
   * @param platforms 屏幕绑定的站台；空表示全站
   * @param lines 屏幕的线路筛选（线路代码）；空表示不筛
   */
  public boolean callableAt(PidsStationKey station, Set<String> platforms, Set<String> lines) {
    if (station == null) {
      return false;
    }
    Instant now = Instant.now();
    HintKey key =
        new HintKey(
            station,
            platforms == null ? Set.of() : Set.copyOf(platforms),
            lines == null ? Set.of() : Set.copyOf(lines));
    CachedHint cached = hints.get(key);
    if (cached != null && now.isBefore(cached.computedAt().plus(HINT_TTL))) {
      return cached.callable();
    }
    boolean callable = false;
    try {
      for (CallOption option : options(station, key.platforms(), Optional.empty(), now, false)) {
        if (!key.lines().isEmpty()
            && key.lines().stream()
                .noneMatch(line -> line.equalsIgnoreCase(option.direction().lineCode()))) {
          continue;
        }
        if (option.verdict().callable()) {
          callable = true;
          break;
        }
      }
    } catch (RuntimeException ex) {
      debug("叫车提示计算失败 station=" + station + " error=" + ex);
    }
    hints.put(key, new CachedHint(now, callable));
    return callable;
  }

  /** 叫来的车（列车名）。 */
  public Set<String> calledTrainNames() {
    return calledTrains.keySet();
  }

  /**
   * 列车是不是叫来的车：读车上的叫车标签（重启后同样认得出）。
   *
   * @param trainName 列车名
   */
  public boolean isCalledTrain(String trainName) {
    if (trainName == null || trainName.isBlank()) {
      return false;
    }
    if (calledTrains.containsKey(trainName)) {
      return true;
    }
    try {
      if (!TrainPropertiesStore.exists(trainName)) {
        return false;
      }
      TrainProperties properties = TrainPropertiesStore.get(trainName);
      return properties != null
          && TrainTagHelper.readTagValue(properties, SimpleTicketAssigner.TAG_CALLED_TRAIN)
              .isPresent();
    } catch (RuntimeException | LinkageError ex) {
      return false;
    }
  }

  // ---------------------------------------------------------------- 叫车

  /**
   * 叫一趟车。
   *
   * @param player 叫车的玩家
   * @param station 车站
   * @param platforms 屏幕绑定的站台；空表示全站
   * @param directionKey 方向（{@link CallCatalog.CallDirection#key()}）
   */
  public CallResult call(
      Player player, PidsStationKey station, Set<String> platforms, String directionKey) {
    Instant now = Instant.now();
    UUID playerId = player.getUniqueId();
    List<CallOption> options = options(station, platforms, Optional.of(playerId), now);
    Optional<CallOption> option =
        options.stream().filter(o -> o.direction().key().equals(directionKey)).findFirst();
    if (option.isEmpty()) {
      return new CallResult(Outcome.NOT_FOUND, Optional.empty(), Optional.empty());
    }
    if (!option.get().verdict().callable()) {
      return new CallResult(Outcome.UNAVAILABLE, option, Optional.empty());
    }
    Optional<CallPlanner.Plan> plan =
        planner.plan(option.get().direction(), dutyBoundPredicate(), now);
    if (plan.isEmpty()) {
      return new CallResult(Outcome.UNAVAILABLE, option, Optional.empty());
    }
    Optional<UUID> callId =
        issue(
            station,
            platforms == null ? Set.of() : platforms,
            option.get().direction(),
            plan.get(),
            playerId,
            UUID.randomUUID(),
            now,
            plan.get().etaSeconds(),
            now);
    if (callId.isEmpty()) {
      return new CallResult(Outcome.FAILED, option, Optional.empty());
    }
    lastCallByPlayer.put(playerId, now);
    hints.clear();
    plugin.getPidsService().ifPresent(PidsService::invalidateSnapshots);
    debug(
        "叫车出票 call="
            + callId.get()
            + " player="
            + player.getName()
            + " station="
            + station
            + " route="
            + plan.get().routeId()
            + " source="
            + plan.get().source()
            + " entry="
            + (plan.get().entryIndex().isPresent() ? plan.get().entryIndex().getAsInt() : "-")
            + " eta="
            + (plan.get().etaSeconds().isPresent() ? plan.get().etaSeconds().getAsInt() : "-"));
    return new CallResult(Outcome.ISSUED, option, callId);
  }

  /**
   * 取消还没派出的叫车。
   *
   * @param playerId 叫车的玩家（只能取消自己叫的）
   * @param callId 叫车 id
   */
  public CancelOutcome cancel(UUID playerId, UUID callId) {
    PendingCall call = pending.get(callId);
    if (call == null || !call.playerId().equals(playerId)) {
      return isDispatched(callId) ? CancelOutcome.ALREADY_DEPARTED : CancelOutcome.NOT_FOUND;
    }
    boolean withdrawn = plugin.getSpawnTicketAssigner().map(a -> a.withdraw(callId)).orElse(false);
    if (!withdrawn) {
      return CancelOutcome.ALREADY_DEPARTED;
    }
    pending.remove(callId);
    forget(callId);
    hints.clear();
    plugin.getPidsService().ifPresent(PidsService::invalidateSnapshots);
    debug("叫车取消 call=" + callId);
    return CancelOutcome.CANCELLED;
  }

  private boolean isDispatched(UUID callId) {
    return calledTrains.values().stream().anyMatch(train -> train.tag().callId().equals(callId));
  }

  /**
   * 出叫车票并记进库里。
   *
   * @param id 叫车编号（也是票的编号）：新叫的车给新编号，找回的叫车沿用原编号——玩家手里的“取消”与车上的标签都认它
   * @param createdAt 叫车时刻（超时从它算）
   * @param etaSeconds 从叫车时刻算的预计到站秒数
   */
  private Optional<UUID> issue(
      PidsStationKey station,
      Set<String> screenPlatforms,
      CallCatalog.CallDirection direction,
      CallPlanner.Plan plan,
      UUID playerId,
      UUID id,
      Instant createdAt,
      OptionalInt etaSeconds,
      Instant now) {
    Optional<SpawnManager> spawnManager = plugin.getSpawnManager();
    Optional<RouteDefinitionCache> cache = plugin.getRouteDefinitionCache();
    if (spawnManager.isEmpty() || cache.isEmpty()) {
      return Optional.empty();
    }
    Optional<SpawnService> service = serviceFor(spawnManager.get(), cache.get(), plan.routeId());
    if (service.isEmpty()) {
      return Optional.empty();
    }
    String trip = OnDemandTrip.format(new CallTag(id, station).format(), plan.entryIndex());
    SpawnTicket ticket =
        new SpawnTicket(
            id,
            service.get(),
            now,
            now,
            now,
            0,
            0L,
            Optional.empty(),
            Optional.empty(),
            Optional.of(trip),
            TripSource.ON_DEMAND,
            0,
            Optional.empty());
    PendingCall call =
        new PendingCall(
            id,
            playerId,
            station,
            Set.copyOf(screenPlatforms),
            direction.key(),
            direction.lineId(),
            createdAt,
            etaSeconds,
            pinFor(direction, screenPlatforms, plan));
    pending.put(id, call);
    spawnManager.get().requeue(ticket);
    remember(call);
    return Optional.of(id);
  }

  /**
   * 叫车的站台：本站是 DYNAMIC 停靠、玩家右键的是单站台的屏时，指定那条股道；否则不指定（选台照常）。
   *
   * @param screenPlatforms 屏幕绑定的站台；空表示全站（统屏、命令）
   */
  private Optional<PlatformPin> pinFor(
      CallCatalog.CallDirection direction, Set<String> screenPlatforms, CallPlanner.Plan plan) {
    Optional<RouteDefinitionCache> cache = plugin.getRouteDefinitionCache();
    if (cache.isEmpty()) {
      return Optional.empty();
    }
    Optional<RouteDefinition> definition = cache.get().findById(plan.routeId());
    if (definition.isEmpty()) {
      return Optional.empty();
    }
    List<RouteStop> stops = cache.get().listStops(definition.get().id());
    if (plan.stopIndex() < 0 || plan.stopIndex() >= stops.size()) {
      return Optional.empty();
    }
    return pinFor(
        direction.platforms(),
        screenPlatforms,
        plan.routeId(),
        plan.stopIndex(),
        stops.get(plan.stopIndex()));
  }

  /**
   * {@link #pinFor(CallCatalog.CallDirection, Set, CallPlanner.Plan)} 的判定部分。
   *
   * @param directionPlatforms 方向在本站的站台（屏幕筛过的 DYNAMIC 股道范围；不限股道时为空）
   * @param stop 本站的停靠
   */
  static Optional<PlatformPin> pinFor(
      Set<String> directionPlatforms,
      Set<String> screenPlatforms,
      UUID routeId,
      int stopIndex,
      RouteStop stop) {
    if (screenPlatforms == null || screenPlatforms.isEmpty() || stop == null) {
      return Optional.empty();
    }
    Optional<DynamicStopMatcher.DynamicSpec> spec = DynamicStopMatcher.parseDynamicSpec(stop);
    if (spec.isEmpty() || !spec.get().isStation()) {
      return Optional.empty();
    }
    Set<String> candidates =
        directionPlatforms == null || directionPlatforms.isEmpty()
            ? CallCatalog.normalizePlatforms(screenPlatforms)
            : directionPlatforms;
    if (candidates.size() != 1) {
      return Optional.empty();
    }
    int track;
    try {
      track = Integer.parseInt(candidates.iterator().next());
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
    DynamicStopMatcher.DynamicSpec dynamic = spec.get();
    if (track < 1
        || (!dynamic.unbounded() && (track < dynamic.fromTrack() || track > dynamic.toTrack()))) {
      return Optional.empty();
    }
    String node =
        dynamic.operatorCode().trim()
            + ":"
            + dynamic.nodeType().trim()
            + ":"
            + dynamic.nodeName().trim()
            + ":"
            + track;
    return Optional.of(new PlatformPin(routeId, stopIndex, node));
  }

  /**
   * 叫来的车在这个停靠预先指定的站台（选台偏好与站台屏读同一份）。
   *
   * @param trainName 列车名
   * @param routeId 列车当前交路
   * @param stopIndex 交路节点下标
   */
  public Optional<String> pinnedPlatformOf(String trainName, UUID routeId, int stopIndex) {
    if (trainName == null || routeId == null) {
      return Optional.empty();
    }
    CalledTrain train = calledTrains.get(trainName);
    return train == null
        ? Optional.empty()
        : train.pin().flatMap(pin -> pin.at(routeId, stopIndex));
  }

  /**
   * 还没派出的叫车票在这个停靠预先指定的站台（站台屏排队行用）。
   *
   * @param ticket 发车票
   * @param stopIndex 交路节点下标
   */
  public Optional<NodeId> pinnedPlatformOf(SpawnTicket ticket, int stopIndex) {
    if (ticket == null || ticket.source() != TripSource.ON_DEMAND || ticket.service() == null) {
      return Optional.empty();
    }
    PendingCall call = pending.get(ticket.id());
    return call == null
        ? Optional.empty()
        : call.pin().flatMap(pin -> pin.at(ticket.service().routeId(), stopIndex)).map(NodeId::of);
  }

  // ---------------------------------------------------------------- 交路校验

  /**
   * 一条线路上没有车源的叫车方向：每条能跑这一趟的交路都是首站不是车库、没有交路在首站终到（不会有待命车）、本站上游也没有能生成车的区间点。 这样的方向不会出现在叫车对话框里。
   *
   * @param lineId 线路
   */
  public List<UnsourcedDirection> unsourcedDirections(UUID lineId) {
    Optional<RouteDefinitionCache> cache = plugin.getRouteDefinitionCache();
    if (cache.isEmpty() || lineId == null) {
      return List.of();
    }
    StationDirectory.Snapshot directory =
        plugin.getStationDirectory().map(StationDirectory::snapshot).orElse(null);
    Collection<RouteDefinitionCache.RouteEntry> entries = cache.get().entries();
    List<UnsourcedDirection> out = new ArrayList<>();
    for (PidsStationKey station : CallCatalog.stationsServed(entries, directory, lineId)) {
      for (CallCatalog.CallDirection direction :
          CallCatalog.directions(
              entries,
              directory,
              line -> line != null && lineId.equals(line.id()),
              station,
              Set.of())) {
        boolean sourced =
            direction.routes().stream()
                .anyMatch(
                    route ->
                        route.fromDepot()
                            || standbyPossible(entries, route.startNode())
                            || planner.entryPossible(route.routeId(), route.stopIndex()));
        if (!sourced) {
          out.add(new UnsourcedDirection(station, direction));
        }
      }
    }
    return List.copyOf(out);
  }

  /** 有没有交路在这个首站终到并留在待命池（终点复用）：没有的话首站永远等不来待命车。 */
  static boolean standbyPossible(
      Collection<RouteDefinitionCache.RouteEntry> entries, String startNode) {
    if (entries == null || startNode == null || startNode.isBlank()) {
      return false;
    }
    String start = TerminalKeyResolver.toTerminalKey(NodeId.of(startNode));
    for (RouteDefinitionCache.RouteEntry entry : entries) {
      if (entry == null) {
        continue;
      }
      RouteDefinition definition = entry.definition();
      if (definition.lifecycleMode() != RouteLifecycleMode.REUSE_AT_TERM
          || definition.waypoints().isEmpty()) {
        continue;
      }
      NodeId last = definition.waypoints().get(definition.waypoints().size() - 1);
      if (TerminalKeyResolver.matches(TerminalKeyResolver.toTerminalKey(last), start)) {
        return true;
      }
    }
    return false;
  }

  // ---------------------------------------------------------------- 存库与找回

  /** 记进库里：重启后据此找回。存储不可用时只在内存里，照常派车。 */
  private void remember(PendingCall call) {
    try {
      plugin
          .getStorageManager()
          .provider()
          .ifPresent(provider -> provider.pendingCalls().save(call.record()));
    } catch (StorageException | UnsupportedOperationException ex) {
      debug("叫车写库失败 call=" + call.id() + " error=" + ex.getMessage());
    }
  }

  /** 从库里删掉：派出、取消、超时或作废了。 */
  private void forget(UUID callId) {
    try {
      plugin
          .getStorageManager()
          .provider()
          .ifPresent(provider -> provider.pendingCalls().delete(callId));
    } catch (StorageException | UnsupportedOperationException ex) {
      debug("叫车删库失败 call=" + callId + " error=" + ex.getMessage());
    }
  }

  /**
   * 找回还没派出的叫车：首次启动时读库；票已经不在发车侧的（重启、发车侧重建）按原编号重新排车出票。
   *
   * <p>车源重新安排：重启前排的待命车或区间点此刻未必还在。已经派出（有车带着这个编号的叫车标签）的收掉；超时的撤掉；方向没了或排不出车的作废，告知玩家。
   */
  void restorePending(Instant now) {
    refreshCalledTrains();
    if (!storedCallsLoaded) {
      storedCallsLoaded = true;
      for (PendingCallRecord record : storedCalls()) {
        pending.putIfAbsent(
            record.id(),
            new PendingCall(
                record.id(),
                record.playerId(),
                record.station(),
                record.screenPlatforms(),
                record.directionKey(),
                record.lineId(),
                record.createdAt(),
                record.etaSeconds(),
                Optional.empty()));
      }
    }
    if (pending.isEmpty()) {
      return;
    }
    Set<UUID> dispatched = dispatchedCallIds();
    Optional<TicketAssigner> assigner = plugin.getSpawnTicketAssigner();
    for (PendingCall call : List.copyOf(pending.values())) {
      if (dispatched.contains(call.id())) {
        pending.remove(call.id());
        forget(call.id());
        continue;
      }
      if (assigner.map(a -> a.isTicketLive(call.id())).orElse(false)) {
        continue;
      }
      if (now.isAfter(call.createdAt().plus(timeoutOf(call)))) {
        dropPending(call, "call.timeout", "叫车找回时已超时");
        continue;
      }
      if (!reissue(call, now)) {
        dropPending(call, "call.failed", "叫车找回后排不出车");
        continue;
      }
      debug("叫车已找回并重新出票 call=" + call.id());
    }
    hints.clear();
  }

  private List<PendingCallRecord> storedCalls() {
    try {
      return plugin
          .getStorageManager()
          .provider()
          .map(provider -> provider.pendingCalls().listAll())
          .orElse(List.of());
    } catch (StorageException | UnsupportedOperationException ex) {
      debug("读取未派出的叫车失败 error=" + ex.getMessage());
      return List.of();
    }
  }

  /** 按原编号重新排车出票；方向没了（线路关了叫车、交路改了）或排不出车时为 false。 */
  private boolean reissue(PendingCall call, Instant now) {
    Optional<CallCatalog.CallDirection> direction =
        directions(call.station(), call.screenPlatforms()).stream()
            .filter(candidate -> candidate.key().equals(call.directionKey()))
            .findFirst();
    if (direction.isEmpty()) {
      return false;
    }
    Optional<CallPlanner.Plan> plan = planner.plan(direction.get(), dutyBoundPredicate(), now);
    if (plan.isEmpty()) {
      return false;
    }
    long waited = Math.max(0L, Duration.between(call.createdAt(), now).toSeconds());
    OptionalInt eta =
        plan.get().etaSeconds().isPresent()
            ? OptionalInt.of(
                (int) Math.min(Integer.MAX_VALUE, waited + plan.get().etaSeconds().getAsInt()))
            : OptionalInt.empty();
    return issue(
            call.station(),
            call.screenPlatforms(),
            direction.get(),
            plan.get(),
            call.playerId(),
            call.id(),
            call.createdAt(),
            eta,
            now)
        .isPresent();
  }

  private void dropPending(PendingCall call, String messageKey, String reason) {
    pending.remove(call.id());
    forget(call.id());
    hints.clear();
    debug(reason + " call=" + call.id());
    notifyPlayer(call.playerId(), messageKey, Map.of());
  }

  /** 叫车等多久没派出就撤：预计到站再加 5 分钟，至少 {@link #PENDING_MIN_TIMEOUT}。 */
  private static Duration timeoutOf(PendingCall call) {
    Duration timeout =
        call.etaSeconds().isPresent()
            ? Duration.ofSeconds(call.etaSeconds().getAsInt()).plusMinutes(5)
            : PENDING_MIN_TIMEOUT;
    return timeout.compareTo(PENDING_MIN_TIMEOUT) < 0 ? PENDING_MIN_TIMEOUT : timeout;
  }

  private Set<UUID> dispatchedCallIds() {
    Set<UUID> dispatched = new HashSet<>();
    for (CalledTrain train : calledTrains.values()) {
      dispatched.add(train.tag().callId());
    }
    return dispatched;
  }

  /** 交路的发车服务：发车计划里有就用它（车库规范一致），否则按交路现拼一份（叫车不靠发车间隔，间隔只是占位）。 */
  private Optional<SpawnService> serviceFor(
      SpawnManager spawnManager, RouteDefinitionCache cache, UUID routeId) {
    for (SpawnService service : spawnManager.snapshotPlan().services()) {
      if (service != null && routeId.equals(service.routeId())) {
        return Optional.of(service);
      }
    }
    Optional<RouteDefinitionCache.RouteRecord> record = cache.findRecord(routeId);
    Optional<RouteDefinition> definition = cache.findById(routeId);
    Optional<StorageProvider> provider = plugin.getStorageManager().provider();
    if (record.isEmpty() || definition.isEmpty() || provider.isEmpty()) {
      return Optional.empty();
    }
    List<RouteStop> stops = cache.listStops(definition.get().id());
    String depotNode =
        stops.isEmpty()
            ? definition.get().waypoints().get(0).value()
            : SpawnDirectiveParser.findDirectiveTarget(stops.get(0), "CRET")
                .orElse(definition.get().waypoints().get(0).value());
    UUID companyId = record.get().operator().companyId();
    String companyCode =
        provider
            .get()
            .companies()
            .findById(companyId)
            .map(company -> company.code())
            .orElse(record.get().operator().code());
    try {
      return Optional.of(
          new SpawnService(
              new SpawnServiceKey(routeId),
              companyId,
              companyCode,
              record.get().operator().id(),
              record.get().operator().code(),
              record.get().line().id(),
              record.get().line().code(),
              routeId,
              record.get().route().code(),
              Duration.ofMinutes(10),
              depotNode));
    } catch (IllegalArgumentException ex) {
      debug("叫车拼发车服务失败 route=" + routeId + " error=" + ex.getMessage());
      return Optional.empty();
    }
  }

  // ---------------------------------------------------------------- 派车回调

  /**
   * 发车侧派出了一张票（{@link TicketAssigner#addDispatchObserver}）：叫车票派出的车写上叫车标签。
   *
   * @param ticket 票据
   * @param trainName 派出的列车（折返改名后的新名字）
   */
  public void onDispatched(SpawnTicket ticket, String trainName) {
    if (ticket == null || trainName == null || ticket.source() != TripSource.ON_DEMAND) {
      return;
    }
    Optional<CallTag> tag = OnDemandTrip.callTagOf(ticket.serviceTripId()).flatMap(CallTag::parse);
    if (tag.isEmpty()) {
      return;
    }
    PendingCall call = pending.remove(tag.get().callId());
    Optional<PlatformPin> pin = call == null ? Optional.empty() : call.pin();
    try {
      if (TrainPropertiesStore.exists(trainName)) {
        TrainProperties properties = TrainPropertiesStore.get(trainName);
        TrainTagHelper.writeTag(
            properties, SimpleTicketAssigner.TAG_CALLED_TRAIN, tag.get().format());
        // 接着跑下一趟叫车时上一趟的站台不再算数：没有指定站台就摘掉旧的。
        if (pin.isPresent()) {
          TrainTagHelper.writeTag(properties, TAG_CALL_PLATFORM, pin.get().format());
        } else {
          TrainTagHelper.removeTagKey(properties, TAG_CALL_PLATFORM);
        }
      }
    } catch (RuntimeException | LinkageError ex) {
      debug("叫车标签写入失败 train=" + trainName + " error=" + ex);
    }
    Map<String, CalledTrain> updated = new HashMap<>(calledTrains);
    updated.put(trainName, new CalledTrain(trainName, tag.get(), lineIdOf(ticket), pin));
    calledTrains = Map.copyOf(updated);
    forget(tag.get().callId());
    hints.clear();
    plugin.getPidsService().ifPresent(PidsService::invalidateSnapshots);
    debug("叫车派出 call=" + tag.get().callId() + " train=" + trainName);
    if (call != null) {
      notifyPlayer(
          call.playerId(), "call.departed", Map.of("station", stationName(call.station())));
    }
  }

  private static Optional<UUID> lineIdOf(SpawnTicket ticket) {
    return ticket.service() == null
        ? Optional.empty()
        : Optional.ofNullable(ticket.service().lineId());
  }

  // ---------------------------------------------------------------- 周期扫描

  /** 周期扫描：登记叫来的车、终点等完的车派回库、没派出去的叫车超时或作废。 */
  void sweep(Instant now) {
    try {
      refreshCalledTrains();
      sweepPending(now);
      returnFinishedTrains(now);
    } catch (RuntimeException ex) {
      debug("叫车扫描异常 error=" + ex);
    }
  }

  /** 按车上的叫车标签重建叫来的车：正在跑回库交路的车摘掉标签（已经不算叫来的车）。 */
  private void refreshCalledTrains() {
    Optional<RouteDefinitionCache> cache = plugin.getRouteDefinitionCache();
    Map<String, CalledTrain> found = new HashMap<>();
    Collection<TrainProperties> all;
    try {
      all = List.copyOf(TrainPropertiesStore.getAll());
    } catch (RuntimeException | LinkageError ex) {
      return;
    }
    for (TrainProperties properties : all) {
      if (properties == null) {
        continue;
      }
      Optional<CallTag> tag =
          TrainTagHelper.readTagValue(properties, SimpleTicketAssigner.TAG_CALLED_TRAIN)
              .flatMap(CallTag::parse);
      if (tag.isEmpty()) {
        continue;
      }
      Optional<RouteDefinitionCache.RouteRecord> record =
          TrainTagHelper.readTagValue(properties, "FTA_ROUTE_ID")
              .flatMap(CallService::parseUuid)
              .flatMap(routeId -> cache.flatMap(c -> c.findRecord(routeId)));
      if (record.isPresent() && record.get().route().operationType() == RouteOperationType.RETURN) {
        TrainTagHelper.removeTagKeys(
            properties, SimpleTicketAssigner.TAG_CALLED_TRAIN, TAG_CALL_PLATFORM);
        debug("叫来的车已派回库，摘掉叫车标签 train=" + properties.getTrainName());
        continue;
      }
      String name = properties.getTrainName();
      found.put(
          name,
          new CalledTrain(
              name,
              tag.get(),
              record.map(r -> r.line().id()),
              TrainTagHelper.readTagValue(properties, TAG_CALL_PLATFORM)
                  .flatMap(PlatformPin::parse)));
    }
    calledTrains = Map.copyOf(found);
  }

  /** 没派出去的叫车：已派出的收掉；票没了的作废；等太久的撤票。 */
  private void sweepPending(Instant now) {
    if (pending.isEmpty()) {
      return;
    }
    Set<UUID> dispatched = dispatchedCallIds();
    Optional<TicketAssigner> assigner = plugin.getSpawnTicketAssigner();
    for (PendingCall call : List.copyOf(pending.values())) {
      if (dispatched.contains(call.id())) {
        pending.remove(call.id());
        forget(call.id());
        continue;
      }
      boolean live = assigner.map(a -> a.isTicketLive(call.id())).orElse(false);
      if (!live) {
        dropPending(call, "call.failed", "叫车未能派出");
        continue;
      }
      if (now.isAfter(call.createdAt().plus(timeoutOf(call)))
          && assigner.map(a -> a.withdraw(call.id())).orElse(false)) {
        dropPending(call, "call.timeout", "叫车等候超时撤票");
      }
    }
  }

  /** 叫来的车在终点等完 {@code call.terminal-wait-seconds}：派回库。期间被叫车票接走的车不在待命池里，不会走到这里。 */
  private void returnFinishedTrains(Instant now) {
    Optional<LayoverRegistry> registry = plugin.getLayoverRegistry();
    Optional<ReclaimManager> reclaim = plugin.getReclaimManager();
    if (registry.isEmpty() || reclaim.isEmpty()) {
      return;
    }
    Duration wait = Duration.ofSeconds(settings().terminalWaitSeconds());
    for (LayoverRegistry.LayoverCandidate candidate : registry.get().snapshot()) {
      if (candidate == null
          || candidate.tags() == null
          || !candidate.tags().containsKey(SimpleTicketAssigner.TAG_CALLED_TRAIN)
          || candidate.dispatchAttempt().isPresent()) {
        continue;
      }
      Instant readyAt = candidate.readyAt() == null ? now : candidate.readyAt();
      if (now.isBefore(readyAt.plus(wait))) {
        continue;
      }
      Instant retryAt = returnRetryAt.get(candidate.trainName());
      if (retryAt != null && now.isBefore(retryAt)) {
        continue;
      }
      ReclaimManager.CalledReturn result =
          reclaim.get().returnCalledTrain(candidate.trainName(), now);
      if (result == ReclaimManager.CalledReturn.ASSIGNED) {
        returnRetryAt.remove(candidate.trainName());
        debug("叫来的车等候期满，已派回库 train=" + candidate.trainName());
      } else {
        returnRetryAt.put(candidate.trainName(), now.plus(RETURN_RETRY));
        debug("叫来的车派回库未成 train=" + candidate.trainName() + " result=" + result);
      }
    }
    returnRetryAt.keySet().retainAll(calledTrains.keySet());
  }

  // ---------------------------------------------------------------- 判定用的数

  private List<CallCatalog.CallDirection> directions(
      PidsStationKey station, Set<String> platforms) {
    Optional<RouteDefinitionCache> cache = plugin.getRouteDefinitionCache();
    if (cache.isEmpty() || station == null) {
      return List.of();
    }
    Map<UUID, Line> callable = callableLines();
    if (callable.isEmpty()) {
      return List.of();
    }
    StationDirectory.Snapshot directory =
        plugin.getStationDirectory().map(StationDirectory::snapshot).orElse(null);
    return CallCatalog.directions(
        cache.get().entries(),
        directory,
        line -> line != null && callable.containsKey(line.id()),
        station,
        platforms);
  }

  /** 开放叫车、在运营的线路（按线路 id），按 {@link #LINE_CACHE_TTL} 重读存储：改了线路开关很快就生效。 */
  private Map<UUID, Line> callableLines() {
    Instant now = Instant.now();
    if (now.isBefore(linesLoadedAt.plus(LINE_CACHE_TTL))) {
      return lines;
    }
    Map<UUID, Line> loaded = new HashMap<>();
    try {
      plugin
          .getStorageManager()
          .provider()
          .ifPresent(
              provider -> {
                for (Line line : provider.lines().listAll()) {
                  if (line != null
                      && line.status() == LineStatus.ACTIVE
                      && LineCallMetadata.allowsPlayerCall(line.metadata())) {
                    loaded.put(line.id(), line);
                  }
                }
              });
    } catch (RuntimeException ex) {
      debug("读取叫车线路失败 error=" + ex);
      return lines;
    }
    lines = Map.copyOf(loaded);
    linesLoadedAt = now;
    return lines;
  }

  private int maxCalls(UUID lineId) {
    Line line = callableLines().get(lineId);
    int fallback = settings().defaultMaxTrains();
    return line == null ? fallback : LineCallMetadata.maxTrains(line.metadata()).orElse(fallback);
  }

  /** 线路上叫来的车（不含已派回库的）与还没派出的叫车。 */
  private Map<UUID, Integer> activeCallsByLine() {
    Map<UUID, Integer> out = new HashMap<>();
    for (CalledTrain train : calledTrains.values()) {
      train.lineId().ifPresent(lineId -> out.merge(lineId, 1, Integer::sum));
    }
    for (PendingCall call : pending.values()) {
      out.merge(call.lineId(), 1, Integer::sum);
    }
    return out;
  }

  /** 同方向已叫的车几分钟后到：还没派出的按叫车时的估计，已在路上的按站台屏的预计。 */
  private OptionalInt calledMinutes(
      CallCatalog.CallDirection direction,
      PidsStationKey station,
      List<PidsRow> rows,
      Instant now) {
    for (PendingCall call : pending.values()) {
      if (call.station().equals(station) && call.directionKey().equals(direction.key())) {
        int remaining =
            call.etaSeconds().isPresent()
                ? (int)
                    Math.max(
                        0L,
                        Duration.between(
                                now, call.createdAt().plusSeconds(call.etaSeconds().getAsInt()))
                            .toSeconds())
                : 0;
        return OptionalInt.of(Math.max(1, (remaining + 59) / 60));
      }
    }
    Set<String> called = calledTrains.keySet();
    OptionalInt best = OptionalInt.empty();
    for (PidsRow row : rows) {
      if (row.trainName().filter(called::contains).isEmpty() || !serves(direction, row)) {
        continue;
      }
      int minutes = minutesUntil(row.expectedAt(), now);
      if (best.isEmpty() || minutes < best.getAsInt()) {
        best = OptionalInt.of(minutes);
      }
    }
    return best;
  }

  /** 同方向下一班可以乘坐的车（不算叫来的车）几分钟后到；看不到时为空。 */
  private OptionalInt nextTrainMinutes(
      CallCatalog.CallDirection direction, List<PidsRow> rows, Instant now) {
    Set<String> called = calledTrains.keySet();
    OptionalInt best = OptionalInt.empty();
    for (PidsRow row : rows) {
      if (row.trainName().filter(called::contains).isPresent() || !serves(direction, row)) {
        continue;
      }
      int minutes = minutesUntil(row.expectedAt(), now);
      if (best.isEmpty() || minutes < best.getAsInt()) {
        best = OptionalInt.of(minutes);
      }
    }
    return best;
  }

  /** 这一行是同方向（同线路、同终点、停这些站台）可以乘坐的车。 */
  static boolean serves(CallCatalog.CallDirection direction, PidsRow row) {
    if (row.status() == PidsRow.Status.CANCELLED
        || row.passing()
        || row.terminating()
        || row.outOfService()) {
      return false;
    }
    if (!row.lineName().equalsIgnoreCase(direction.lineCode())) {
      return false;
    }
    if (!row.destinationId()
        .map(id -> id.equalsIgnoreCase(direction.destination().toString()))
        .orElse(false)) {
      return false;
    }
    return direction.platforms().isEmpty() || row.mayUse(direction.platforms());
  }

  /** 与站台屏同一口径：不足一分钟按一分钟，已经到点为 0。 */
  static int minutesUntil(Instant at, Instant now) {
    long seconds = Duration.between(now, at).getSeconds();
    return seconds <= 0 ? 0 : (int) ((seconds + 59) / 60);
  }

  private List<PidsRow> rowsAt(PidsStationKey station) {
    return plugin
        .getPidsService()
        .map(service -> service.snapshot(station))
        .map(PidsSnapshot::rows)
        .orElse(List.of());
  }

  private long cooldownSeconds(UUID playerId, Instant now) {
    Instant last = lastCallByPlayer.get(playerId);
    if (last == null) {
      return 0L;
    }
    long remaining =
        Duration.between(now, last.plusSeconds(settings().cooldownSeconds())).getSeconds();
    return Math.max(0L, remaining);
  }

  private Predicate<String> dutyBoundPredicate() {
    return plugin
        .getTimetableService()
        .<Predicate<String>>map(service -> name -> service.dutyBindingOf(name).isPresent())
        .orElse(name -> false);
  }

  private ConfigManager.CallSettings settings() {
    ConfigManager manager = plugin.getConfigManager();
    return manager == null
        ? ConfigManager.CallSettings.defaults()
        : manager.current().callSettings();
  }

  // ---------------------------------------------------------------- 文本

  /** 车站名：车站目录里有就用站名，否则用站码。 */
  public String stationName(PidsStationKey station) {
    return plugin
        .getStationDirectory()
        .map(StationDirectory::snapshot)
        .flatMap(snapshot -> snapshot.findStation(station.operatorCode(), station.stationCode()))
        .map(StationDirectory.StationEntry::name)
        .orElse(station.stationCode());
  }

  /** 方向的说法：{@code 线路 运营类型 开往 终点}。 */
  public Map<String, String> directionPlaceholders(CallCatalog.CallDirection direction) {
    LocaleManager locale = plugin.getLocaleManager();
    Map<String, String> out = new HashMap<>();
    out.put("line", direction.lineCode());
    out.put("type", locale.text(typeKey(direction.pattern())));
    out.put("destination", stationName(direction.destination()));
    out.put("platform", direction.platformLabel().isEmpty() ? "-" : direction.platformLabel());
    return out;
  }

  private static String typeKey(RoutePatternType pattern) {
    return switch (pattern) {
      case LOCAL -> "pids.board.type.local";
      case RAPID, NEO_RAPID -> "pids.board.type.rapid";
      case EXPRESS, LIMITED_EXPRESS -> "pids.board.type.express";
    };
  }

  private void notifyPlayer(UUID playerId, String key, Map<String, String> placeholders) {
    Player player = Bukkit.getPlayer(playerId);
    if (player == null || !player.isOnline()) {
      return;
    }
    Component message = plugin.getLocaleManager().component(key, placeholders);
    player.sendMessage(message);
  }

  private static Optional<UUID> parseUuid(String raw) {
    try {
      return Optional.of(UUID.fromString(raw.trim()));
    } catch (IllegalArgumentException ex) {
      return Optional.empty();
    }
  }

  private void debug(String message) {
    if (plugin.getLoggerManager() != null) {
      plugin.getLoggerManager().debug(message);
    }
  }
}
