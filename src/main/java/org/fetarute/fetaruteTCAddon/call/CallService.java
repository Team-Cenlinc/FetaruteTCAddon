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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.call.repository.PendingCallRepository;
import org.fetarute.fetaruteTCAddon.company.api.StationDirectory;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.LineStatus;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLifecycleMode;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteTerminals;
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
 * SimpleTicketAssigner#TAG_CALLED_TRAIN} 标签：不进时刻表、按表或按间隔的票都不接它、进路排队排在所有车之后，
 * 跑完全程到终点后派回库——按表运行的交路到终点即回库（终点股道留给表定列车）， 其余等 {@code
 * call.terminal-wait-seconds}，期间沿途又有人叫车、这趟经过就接着跑。回库途中仍带着叫车标签（时刻表照样不管它）， 只是不再算进线路的叫车车数。
 *
 * <p>只在服务器主线程调用。
 */
public final class CallService {

  public static final String PERMISSION = "fetarute.call";

  /** 叫来的车预先指定的站台（{@link PlatformPin#format()}）；随车走，重启、改名后照样认得。 */
  public static final String TAG_CALL_PLATFORM = "FTA_CALL_PLATFORM";

  /** 叫来的车这一趟叫车跑的交路：车跑上别的回库交路就是在回库途中（{@link #refreshCalledTrains}）。 */
  public static final String TAG_CALL_ROUTE = "FTA_CALL_ROUTE";

  /** 车库在表定出库前后多久之内不给叫车出车（那条股道或车库池留给表定列车）。 */
  static final Duration DEPOT_YIELD_AHEAD = Duration.ofMinutes(3);

  /** 已过计划时刻还没出库的表定车也算：晚一分钟以内的照样让。 */
  static final Duration DEPOT_YIELD_BEHIND = Duration.ofMinutes(1);

  /** 表定出库的车库清单缓存多久：站台屏每次判定都要问。 */
  private static final Duration DEPOT_YIELD_TTL = Duration.ofSeconds(5);

  private static final long SWEEP_PERIOD_TICKS = 20L;
  private static final Duration HINT_TTL = Duration.ofSeconds(5);
  private static final Duration LINE_CACHE_TTL = Duration.ofSeconds(10);
  private static final Duration PENDING_MIN_TIMEOUT = Duration.ofMinutes(10);
  private static final Duration RETURN_RETRY = Duration.ofSeconds(10);

  /** 同一玩家右键站台屏开叫车对话框的最短间隔。 */
  static final Duration DIALOG_INTERVAL = Duration.ofSeconds(1);

  /** 启动后多久才找回还没派出的叫车：等启动时的现场占用重建、待命池登记做完，车源按真实现场排；这之前不撤还没回到发车侧的票。 */
  static final Duration RESTORE_DELAY = Duration.ofSeconds(30);

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

  /**
   * 叫来的车：车名、标签、跑的交路所属线路、预先指定的站台、是否在回库途中。
   *
   * @param returning 跑完叫车、正在回库交路上（不算线路的叫车车数）
   */
  private record CalledTrain(
      String name,
      CallTag tag,
      Optional<UUID> lineId,
      Optional<PlatformPin> pin,
      boolean returning) {}

  /** 此后一段时间里按表从车库出车的出库点（CRET 写法），按计算时刻缓存。 */
  private record DepotDepartures(Instant computedAt, List<String> depots) {}

  private record HintKey(PidsStationKey station, Set<String> platforms, Set<String> lines) {}

  private record CachedHint(Instant computedAt, boolean callable) {}

  private final FetaruteTCAddon plugin;
  private final CallPlanner planner;
  private final Map<UUID, PendingCall> pending = new ConcurrentHashMap<>();
  private final Map<UUID, Instant> lastCallByPlayer = new ConcurrentHashMap<>();
  private final Map<UUID, Instant> lastDialogByPlayer = new ConcurrentHashMap<>();
  private final Map<String, Instant> returnRetryAt = new ConcurrentHashMap<>();
  private final Map<HintKey, CachedHint> hints = new ConcurrentHashMap<>();
  private volatile Map<String, CalledTrain> calledTrains = Map.of();
  private volatile Map<UUID, Line> lines = Map.of();
  private volatile DepotDepartures depotDepartures = new DepotDepartures(Instant.EPOCH, List.of());

  /** 身后有车追近（到下一站的预计间隔不到 min-lead）的叫来的车，列车名小写；每秒扫描时重算。 */
  private volatile Set<String> closeBehind = Set.of();

  private volatile Instant linesLoadedAt = Instant.EPOCH;

  /** 正在后台重读线路开关。 */
  private final AtomicBoolean linesLoading = new AtomicBoolean();

  /** 线路开关至少成功读到过一次。 */
  private volatile boolean linesLoaded;

  /** 线路开关作废的次数：读到一半被作废的那一份读完后不算新鲜，下次用到时再读。 */
  private final AtomicLong linesGeneration = new AtomicLong();

  private boolean storedCallsLoaded;

  /** 下一次找回还没派出的叫车的时刻；为空表示不必找回（已找回，或还没启动）。 */
  private Instant restoreDueAt;

  /** 存库写入排成一条链：后台执行、按提交顺序一条接一条，同一条叫车先存后删不会颠倒。 */
  private CompletableFuture<Void> storageWrites = CompletableFuture.completedFuture(null);

  private BukkitTask task;

  public CallService(FetaruteTCAddon plugin) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.planner = new CallPlanner(plugin);
  }

  // ---------------------------------------------------------------- 生命周期

  /** 开始周期扫描（叫来的车登记、终点回库、叫车超时）；{@link #RESTORE_DELAY} 后找回还没派出的叫车。 */
  public void start() {
    stop();
    restoreDueAt = Instant.now().plus(RESTORE_DELAY);
    reloadLines();
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
    linesGeneration.incrementAndGet();
    linesLoadedAt = Instant.EPOCH;
    hints.clear();
  }

  // ---------------------------------------------------------------- 查询

  /**
   * 车站上能叫的车（按方向）。每个方向都排车源：对话框要据此隐去没有车可派的方向。
   *
   * @param station 车站
   * @param platforms 屏幕绑定的站台；空表示全站
   * @param playerId 叫车的玩家（算个人冷却）；空时不算
   * @param now 当前时刻
   */
  public List<CallOption> options(
      PidsStationKey station, Set<String> platforms, Optional<UUID> playerId, Instant now) {
    List<CallCatalog.CallDirection> directions = directions(station, platforms);
    if (directions.isEmpty()) {
      return List.of();
    }
    Scene scene = scene(station, playerId, now);
    List<CallOption> out = new ArrayList<>(directions.size());
    for (CallCatalog.CallDirection direction : directions) {
      CallRules.Input assumingSource = withoutPlan(direction, scene);
      Optional<CallPlanner.Plan> plan = planner.plan(direction, scene.constraints(), now);
      out.add(withPlan(direction, assumingSource, plan));
    }
    return List.copyOf(out);
  }

  /**
   * 站台屏要不要写“可右键本屏叫车”：屏幕上有一个方向此刻能叫（不算个人冷却）。按 {@link #HINT_TTL} 缓存。
   *
   * <p>先按屏幕的线路筛选、看除车源以外的条件（下一班多久到、车数上限），过了的方向才排车源，排到一个就停。
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
      callable = anyCallable(key, now);
    } catch (RuntimeException ex) {
      debug("叫车提示计算失败 station=" + station + " error=" + ex);
    }
    hints.put(key, new CachedHint(now, callable));
    return callable;
  }

  private boolean anyCallable(HintKey key, Instant now) {
    List<CallCatalog.CallDirection> directions = new ArrayList<>();
    for (CallCatalog.CallDirection direction : directions(key.station(), key.platforms())) {
      if (key.lines().isEmpty()
          || key.lines().stream().anyMatch(line -> line.equalsIgnoreCase(direction.lineCode()))) {
        directions.add(direction);
      }
    }
    if (directions.isEmpty()) {
      return false;
    }
    Scene scene = scene(key.station(), Optional.empty(), now);
    List<CallCatalog.CallDirection> candidates = new ArrayList<>();
    List<CallRules.Input> inputs = new ArrayList<>();
    for (CallCatalog.CallDirection direction : directions) {
      CallRules.Input assumingSource = withoutPlan(direction, scene);
      if (CallRules.evaluate(assumingSource).callable()) {
        candidates.add(direction);
        inputs.add(assumingSource);
      }
    }
    for (int i = 0; i < candidates.size(); i++) {
      CallCatalog.CallDirection direction = candidates.get(i);
      Optional<CallPlanner.Plan> plan = planner.plan(direction, scene.constraints(), now);
      if (withPlan(direction, inputs.get(i), plan).verdict().callable()) {
        return true;
      }
    }
    return false;
  }

  /**
   * 判定要用的现场：站台屏的行、各线路的叫车数、排车源的约束（待命车能不能接、车库让不让）、个人冷却。一次判定只取一次。
   *
   * @param cooldownSeconds 叫车玩家的个人冷却还剩几秒；不算玩家时为 0
   */
  private record Scene(
      PidsStationKey station,
      List<PidsRow> rows,
      Map<UUID, Integer> activeCalls,
      CallPlanner.Constraints constraints,
      long cooldownSeconds,
      Instant now) {}

  private Scene scene(PidsStationKey station, Optional<UUID> playerId, Instant now) {
    return new Scene(
        station,
        rowsAt(station),
        activeCallsByLine(),
        constraints(),
        playerId.map(id -> cooldownSeconds(id, now)).orElse(0L),
        now);
  }

  /**
   * 排车源的约束。
   *
   * <ul>
   *   <li>首站待命车：叫来的车都能接；交路按表运行时只接叫来的车（没绑交路的车可能正等着跑首班或等回收）， 否则不接绑着时刻表交路的车；
   *   <li>车库：同一出库点（DYNAMIC 写法按整个车库池）在表定出库前后（{@link #DEPOT_YIELD_AHEAD}、{@link
   *       #DEPOT_YIELD_BEHIND}）不给叫车出车。
   * </ul>
   */
  private CallPlanner.Constraints constraints() {
    return constraints(false);
  }

  /**
   * 同 {@link #constraints()}。
   *
   * @param restoring 找回重启前的叫车：按表运行打开时首站待命车只接叫来的车——刚重启时交路账本是空的，别的车看着没绑交路， 其实多半还担着时刻表的班（共用终点的线路也一样）
   */
  private CallPlanner.Constraints constraints(boolean restoring) {
    Predicate<String> dutyBound = dutyBoundPredicate();
    Predicate<UUID> managed = restoring && timetableEnabled() ? routeId -> true : timetableRoutes();
    return new CallPlanner.Constraints() {
      @Override
      public boolean acceptsStandby(UUID routeId, LayoverRegistry.LayoverCandidate candidate) {
        return CallService.acceptsStandby(candidate, managed.test(routeId), dutyBound);
      }

      @Override
      public boolean depotYields(UUID routeId, Instant now) {
        return depotYieldsToTimetable(routeId, now);
      }
    };
  }

  /**
   * 首站待命车能不能接叫车：叫来的车都能接；交路按表运行时只接叫来的车，否则不接绑着时刻表交路的车。
   *
   * @param managedRoute 叫车跑的交路按表运行
   */
  static boolean acceptsStandby(
      LayoverRegistry.LayoverCandidate candidate,
      boolean managedRoute,
      Predicate<String> dutyBound) {
    return SimpleTicketAssigner.callMayTake(candidate, managedRoute, dutyBound);
  }

  /** 先当作有车可派：只看下一班多久到、同方向已叫的车、车数上限与个人冷却。 */
  private CallRules.Input withoutPlan(CallCatalog.CallDirection direction, Scene scene) {
    return new CallRules.Input(
        true,
        calledMinutes(direction, scene.station(), scene.rows(), scene.now()),
        nextTrainMinutes(direction, scene.rows(), scene.now()),
        settings().minWaitMinutes(),
        scene.activeCalls().getOrDefault(direction.lineId(), 0),
        maxCalls(direction.lineId()),
        scene.cooldownSeconds(),
        OptionalInt.empty(),
        settings().minLeadMinutes());
  }

  /** 加上车源安排后的判定。 */
  private static CallOption withPlan(
      CallCatalog.CallDirection direction,
      CallRules.Input assumingSource,
      Optional<CallPlanner.Plan> plan) {
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
                plan.map(CallPlanner.Plan::etaMinutes).orElse(OptionalInt.empty()),
                assumingSource.minLeadMinutes()));
    return new CallOption(direction, verdict, plan.map(p -> new CallPlan(p.etaMinutes())));
  }

  /**
   * 玩家这次右键站台屏能不能开叫车对话框：同一玩家 {@link #DIALOG_INTERVAL} 内只开一次。按住右键每秒会触发好几次，每次开对话框都要把各方向的车源排一遍。
   *
   * @param playerId 玩家
   * @param now 当前时刻
   */
  public boolean dialogAllowed(UUID playerId, Instant now) {
    if (playerId == null) {
      return false;
    }
    Instant last = lastDialogByPlayer.get(playerId);
    if (last != null && now.isBefore(last.plus(DIALOG_INTERVAL)) && !now.isBefore(last)) {
      return false;
    }
    lastDialogByPlayer.put(playerId, now);
    return true;
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
    Optional<CallPlanner.Plan> plan = planner.plan(option.get().direction(), constraints(), now);
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
            + plan.get()
                .entry()
                .map(entry -> entry.index() + "/" + entry.node().value())
                .orElse("-")
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
    String trip =
        OnDemandTrip.format(
            new CallTag(id, station).format(), plan.entry().map(CallPlanner.Entry::ticketEntry));
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
    return Optional.of(new PlatformPin(routeId, stopIndex, dynamic.nodeIdForTrack(track).value()));
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
    CalledTrain train = calledTrain(trainName);
    return train == null
        ? Optional.empty()
        : train.pin().flatMap(pin -> pin.at(routeId, stopIndex));
  }

  /** 按列车名找叫来的车：先按原样，找不到再不分大小写（运行时有的路径传的是规范化的小写名）。 */
  private CalledTrain calledTrain(String trainName) {
    Map<String, CalledTrain> current = calledTrains;
    CalledTrain exact = current.get(trainName);
    if (exact != null || current.isEmpty()) {
      return exact;
    }
    String wanted = trainName.trim();
    for (CalledTrain train : current.values()) {
      if (train.name().equalsIgnoreCase(wanted)) {
        return train;
      }
    }
    return null;
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
   * 一条线路上没有车源的叫车方向：每条能跑这一趟的交路都是首站不是车库、首站等不来能接的待命车（没有不按表的交路在首站终到， 按表运行的交路又只接叫来的车）、本站上游也没有能生成车的区间点。
   * 这样的方向不会出现在叫车对话框里。
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
    // 只看本线的交路列方向；能留下待命车的终点、各交路有没有车源都只算一次。
    List<RouteDefinitionCache.RouteEntry> lineEntries =
        entries.stream()
            .filter(entry -> entry != null && lineId.equals(entry.record().line().id()))
            .toList();
    Predicate<UUID> timetableRoutes = timetableRoutes();
    List<String> standbyTerminals = standbyTerminals(entries, timetableRoutes);
    Map<String, Boolean> sourcedRoutes = new HashMap<>();
    List<UnsourcedDirection> out = new ArrayList<>();
    for (PidsStationKey station : CallCatalog.stationsServed(lineEntries, directory, lineId)) {
      for (CallCatalog.CallDirection direction :
          CallCatalog.directions(
              lineEntries,
              directory,
              line -> line != null && lineId.equals(line.id()),
              station,
              Set.of())) {
        boolean sourced =
            direction.routes().stream()
                .anyMatch(
                    route ->
                        sourcedRoutes.computeIfAbsent(
                            route.routeId() + "#" + route.stopIndex(),
                            ignored ->
                                route.fromDepot()
                                    || (route.origin() == CallCatalog.Origin.STANDBY
                                        && !timetableRoutes.test(route.routeId())
                                        && standbyAt(standbyTerminals, route.startNode()))
                                    || planner.entryPossible(route.routeId(), route.stopIndex())));
        if (!sourced) {
          out.add(new UnsourcedDirection(station, direction));
        }
      }
    }
    return List.copyOf(out);
  }

  /**
   * 有没有交路在这个首站终到并留在待命池（终点复用），留下的车又不是时刻表的车：没有的话首站永远等不来可接的待命车。
   *
   * @param timetableRoutes 由时刻表管辖的交路：跑完它的车多半还绑着时刻表交路，叫车不接
   */
  static boolean standbyPossible(
      Collection<RouteDefinitionCache.RouteEntry> entries,
      String startNode,
      Predicate<UUID> timetableRoutes) {
    return standbyAt(standbyTerminals(entries, timetableRoutes), startNode);
  }

  /** 能留下可接的待命车的终点（终点复用、不由时刻表管辖的交路的末节点）。 */
  private static List<String> standbyTerminals(
      Collection<RouteDefinitionCache.RouteEntry> entries, Predicate<UUID> timetableRoutes) {
    if (entries == null) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    for (RouteDefinitionCache.RouteEntry entry : entries) {
      if (entry == null || timetableRoutes.test(entry.routeId())) {
        continue;
      }
      RouteDefinition definition = entry.definition();
      if (definition.lifecycleMode() != RouteLifecycleMode.REUSE_AT_TERM
          || definition.waypoints().isEmpty()) {
        continue;
      }
      out.add(
          TerminalKeyResolver.toTerminalKey(
              definition.waypoints().get(definition.waypoints().size() - 1)));
    }
    return out;
  }

  private static boolean standbyAt(List<String> terminals, String startNode) {
    if (startNode == null || startNode.isBlank()) {
      return false;
    }
    String start = TerminalKeyResolver.toTerminalKey(NodeId.of(startNode));
    for (String terminal : terminals) {
      if (TerminalKeyResolver.matches(terminal, start)) {
        return true;
      }
    }
    return false;
  }

  /** 按表运行打开着。 */
  private boolean timetableEnabled() {
    return plugin.getTimetableService().map(service -> service.settings().enabled()).orElse(false);
  }

  /** 由时刻表管辖的交路（按表运行打开时）。 */
  private Predicate<UUID> timetableRoutes() {
    return plugin
        .getTimetableService()
        .<Predicate<UUID>>map(service -> service::managed)
        .orElse(routeId -> false);
  }

  // ---------------------------------------------------------------- 存库与找回

  /** 记进库里：重启后据此找回。存储不可用时只在内存里，照常派车。 */
  private void remember(PendingCall call) {
    PendingCallRecord record = call.record();
    writeLater("叫车写库失败 call=" + call.id(), calls -> calls.save(record));
  }

  /** 从库里删掉：派出、取消、超时或作废了。 */
  private void forget(UUID callId) {
    writeLater("叫车删库失败 call=" + callId, calls -> calls.delete(callId));
  }

  /** 存库在后台做，不占主线程（派车回调、每秒扫描都会写）；写入排成一条链，按提交顺序执行。这张表只在下次启动时读，晚几毫秒写入无妨。 插件未启用（停服、测试）时当场写。 */
  private void writeLater(String failure, Consumer<PendingCallRepository> work) {
    Optional<StorageProvider> provider;
    try {
      provider = plugin.getStorageManager().provider();
    } catch (RuntimeException ex) {
      debug(failure + " error=" + ex);
      return;
    }
    if (provider.isEmpty()) {
      return;
    }
    Executor executor =
        plugin.isEnabled()
            ? runnable -> Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable)
            : Runnable::run;
    storageWrites =
        storageWrites.thenRunAsync(
            () -> {
              try {
                work.accept(provider.get().pendingCalls());
              } catch (RuntimeException ex) {
                // 失败不断链：后面的写入照常排队执行。
                debug(failure + " error=" + ex.getMessage());
              }
            },
            executor);
  }

  /**
   * 找回还没派出的叫车：首次启动时读库；票已经不在发车侧的（重启、发车侧重建）按原编号重新排车出票。
   *
   * <p>车源重新安排：重启前排的待命车或区间点此刻未必还在。已经派出（有车带着这个编号的叫车标签）的收掉；超时的撤掉；方向没了或排不出车的作废，告知玩家。
   */
  void restorePending(Instant now) {
    refreshCalledTrains();
    if (!storedCallsLoaded) {
      // 读库失败（存储一时不可用）时不记“已读过”：下次启动或重载再读。
      Optional<List<PendingCallRecord>> stored = storedCalls();
      storedCallsLoaded = stored.isPresent();
      for (PendingCallRecord record : stored.orElse(List.of())) {
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
    plugin.getPidsService().ifPresent(PidsService::invalidateSnapshots);
  }

  /** 库里还没派出的叫车；存储不可用或读失败时为空（不是“没有”）。 */
  private Optional<List<PendingCallRecord>> storedCalls() {
    try {
      return plugin
          .getStorageManager()
          .provider()
          .map(provider -> provider.pendingCalls().listAll());
    } catch (StorageException | UnsupportedOperationException ex) {
      debug("读取未派出的叫车失败 error=" + ex.getMessage());
      return Optional.empty();
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
    // 按表运行打开时找回只接叫来的车（认车上的标签，刚重启也认得）：账本刚重启是空的，看不出别的车是否还担着时刻表的班。
    Optional<CallPlanner.Plan> plan = planner.plan(direction.get(), constraints(true), now);
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
        if (ticket.service() != null) {
          TrainTagHelper.writeTag(
              properties, TAG_CALL_ROUTE, ticket.service().routeId().toString());
        }
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
    updated.put(trainName, new CalledTrain(trainName, tag.get(), lineIdOf(ticket), pin, false));
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
      if (restoreDueAt != null) {
        // 找回之前不撤票：重启后票还没回到发车侧，撤了就找不回了。线路开关还没读到时也等：读不到就当方向没了，叫车会被作废。
        if (!now.isBefore(restoreDueAt) && linesReady()) {
          restoreDueAt = null;
          restorePending(now);
        }
      } else {
        sweepPending(now);
      }
      returnFinishedTrains(now);
      refreshCloseBehind();
      forgetIdlePlayers(now);
    } catch (RuntimeException ex) {
      debug("叫车扫描异常 error=" + ex);
    }
  }

  /** 个人冷却已过、对话框间隔已过的玩家不再记着。 */
  private void forgetIdlePlayers(Instant now) {
    Instant cooledDown = now.minusSeconds(Math.max(0L, settings().cooldownSeconds()));
    lastCallByPlayer.values().removeIf(at -> at.isBefore(cooledDown));
    Instant dialogsDone = now.minus(DIALOG_INTERVAL);
    lastDialogByPlayer.values().removeIf(at -> at.isBefore(dialogsDone));
  }

  /**
   * 按车上的叫车标签重建叫来的车。跑上回库交路、又不是这一趟叫车跑的交路（{@link #TAG_CALL_ROUTE}）的车在回库途中：
   * 标签留着（时刻表照样不管它，免得被就近匹配成表里的车次、抢走别人的交路），只是不再算进线路的叫车车数。
   */
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
      Optional<UUID> routeId =
          TrainTagHelper.readTagValue(properties, "FTA_ROUTE_ID").flatMap(CallService::parseUuid);
      Optional<RouteDefinitionCache.RouteRecord> record =
          routeId.flatMap(id -> cache.flatMap(c -> c.findRecord(id)));
      boolean returning =
          returning(
              record.map(r -> r.route().operationType()),
              routeId,
              TrainTagHelper.readTagValue(properties, TAG_CALL_ROUTE)
                  .flatMap(CallService::parseUuid));
      String name = properties.getTrainName();
      found.put(
          name,
          new CalledTrain(
              name,
              tag.get(),
              record.map(r -> r.line().id()),
              TrainTagHelper.readTagValue(properties, TAG_CALL_PLATFORM)
                  .flatMap(PlatformPin::parse),
              returning));
    }
    calledTrains = Map.copyOf(found);
  }

  /**
   * 叫来的车是不是在回库途中：跑的是回库交路，又不是这一趟叫车跑的交路（叫车方向本身就是回库交路时，那一趟照常算叫来的车）。
   *
   * @param operationType 车正在跑的交路的运营类型；查不到时为空
   * @param routeId 车正在跑的交路
   * @param callRoute 这一趟叫车跑的交路；没有记录（旧车）时为空，跑在回库交路上就算回库途中
   */
  static boolean returning(
      Optional<RouteOperationType> operationType,
      Optional<UUID> routeId,
      Optional<UUID> callRoute) {
    if (operationType.isEmpty() || operationType.get() != RouteOperationType.RETURN) {
      return false;
    }
    return callRoute.isEmpty() || !callRoute.equals(routeId);
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

  /**
   * 叫来的车在终点等完：派回库。按表运行的交路不等（终点股道要留给表定列车），其余等 {@code call.terminal-wait-seconds}。
   * 期间被叫车票接走的车不在待命池里，不会走到这里。
   */
  private void returnFinishedTrains(Instant now) {
    Optional<LayoverRegistry> registry = plugin.getLayoverRegistry();
    Optional<ReclaimManager> reclaim = plugin.getReclaimManager();
    if (registry.isEmpty() || reclaim.isEmpty()) {
      return;
    }
    Duration wait = Duration.ofSeconds(settings().terminalWaitSeconds());
    Predicate<UUID> timetableRoutes = timetableRoutes();
    for (LayoverRegistry.LayoverCandidate candidate : registry.get().snapshot()) {
      if (candidate == null
          || candidate.tags() == null
          || !candidate.tags().containsKey(SimpleTicketAssigner.TAG_CALLED_TRAIN)
          || candidate.dispatchAttempt().isPresent()) {
        continue;
      }
      Instant readyAt = candidate.readyAt() == null ? now : candidate.readyAt();
      if (now.isBefore(readyAt.plus(terminalWait(candidate, timetableRoutes, wait)))) {
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

  /**
   * 叫来的车身后有没有车追近：到它下一站的预计间隔不到 {@code min-lead-minutes}（按需降速据此不降，免得把后车也压慢）。 每秒扫描时算好，这里只查。
   *
   * @param trainName 列车名
   */
  public boolean trainCloseBehind(String trainName) {
    return trainName != null && closeBehind.contains(trainName.trim().toLowerCase(Locale.ROOT));
  }

  /** 重算身后有车追近的叫来的车：下一站查不到、本车不在那一站的站台屏上时也算追近（宁可不降速）。 */
  private void refreshCloseBehind() {
    ConfigManager.CallSettings current = settings();
    long leadSeconds = current.minLeadMinutes() * 60L;
    Optional<RouteDefinitionCache> cache = plugin.getRouteDefinitionCache();
    Optional<org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService> runtime =
        plugin.getRuntimeDispatchService();
    if (leadSeconds <= 0L
        || current.followGapSeconds() <= 0
        || calledTrains.isEmpty()
        || cache.isEmpty()
        || runtime.isEmpty()) {
      closeBehind = Set.of();
      return;
    }
    Map<
            String,
            org.fetarute
                .fetaruteTCAddon
                .dispatcher
                .runtime
                .RouteProgressRegistry
                .RouteProgressEntry>
        progress = runtime.get().snapshotProgressEntries();
    StationDirectory.Snapshot directory =
        plugin.getStationDirectory().map(StationDirectory::snapshot).orElse(null);
    Set<String> out = new HashSet<>();
    for (CalledTrain train : calledTrains.values()) {
      OptionalLong gap =
          nextStationOf(train.name(), progress, cache.get(), directory)
              .map(station -> rearGapSeconds(rowsAt(station), train.name()))
              .orElse(OptionalLong.empty());
      if (gap.isEmpty() || gap.getAsLong() < leadSeconds) {
        out.add(train.name().trim().toLowerCase(Locale.ROOT));
      }
    }
    closeBehind = Set.copyOf(out);
  }

  /** 列车下一个停车的车站（含终点）；进度或交路查不到时为空。 */
  private static Optional<PidsStationKey> nextStationOf(
      String trainName,
      Map<
              String,
              org.fetarute
                  .fetaruteTCAddon
                  .dispatcher
                  .runtime
                  .RouteProgressRegistry
                  .RouteProgressEntry>
          progress,
      RouteDefinitionCache cache,
      StationDirectory.Snapshot directory) {
    var entry =
        progress.get(
            org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.TrainNameNormalizer
                .normalizeKey(trainName));
    if (entry == null || entry.routeUuid() == null) {
      return Optional.empty();
    }
    RouteDefinitionCache.RouteEntry route = null;
    for (RouteDefinitionCache.RouteEntry candidate : cache.entries()) {
      if (candidate != null && entry.routeUuid().equals(candidate.routeId())) {
        route = candidate;
        break;
      }
    }
    if (route == null) {
      return Optional.empty();
    }
    List<RouteStop> stops = route.stops();
    List<StationDirectory.StopStation> stations =
        directory == null
            ? List.of()
            : directory.stopStations(
                route.routeId(), stops, Optional.of(route.record().operator()));
    for (int i = Math.max(0, entry.currentIndex() + 1); i < stops.size(); i++) {
      RouteStop stop = stops.get(i);
      if ((stop.stops() || stop.passType() == RouteStopPassType.TERMINATE)
          && RouteTerminals.isStationStop(stop)) {
        return CallCatalog.stationKeyAt(route, stops, stations, i);
      }
    }
    return Optional.empty();
  }

  /**
   * 到站预计的后车间隔：本车之后、同一站台（或同线路同终点）最早到的那一班离本车几秒（取消的不算）。本车不在这些行里时为空；身后没有车时为 {@link Long#MAX_VALUE}。
   *
   * @param rows 下一站的站台屏行
   * @param trainName 本车
   */
  static OptionalLong rearGapSeconds(List<PidsRow> rows, String trainName) {
    PidsRow own = null;
    for (PidsRow row : rows) {
      if (row.trainName().filter(name -> name.equalsIgnoreCase(trainName)).isPresent()) {
        own = row;
        break;
      }
    }
    if (own == null) {
      return OptionalLong.empty();
    }
    long best = Long.MAX_VALUE;
    for (PidsRow row : rows) {
      if (row == own
          || row.status() == PidsRow.Status.CANCELLED
          || !row.expectedAt().isAfter(own.expectedAt())
          || !sameTrack(own, row)) {
        continue;
      }
      best = Math.min(best, Duration.between(own.expectedAt(), row.expectedAt()).getSeconds());
    }
    return OptionalLong.of(best);
  }

  /** 两行是同一条路上的车：同一站台，或站台未定时同线路同终点。 */
  private static boolean sameTrack(PidsRow own, PidsRow row) {
    if (!own.platformPending() && !row.platformPending()) {
      return own.platform().equalsIgnoreCase(row.platform());
    }
    return own.lineName().equalsIgnoreCase(row.lineName())
        && own.destinationId().isPresent()
        && own.destinationId().equals(row.destinationId());
  }

  /** 叫来的车在终点等多久：刚跑完的交路按表运行时不等。 */
  static Duration terminalWait(
      LayoverRegistry.LayoverCandidate candidate, Predicate<UUID> timetableRoutes, Duration wait) {
    Optional<UUID> routeId =
        Optional.ofNullable(candidate.tags())
            .map(tags -> tags.get("FTA_ROUTE_ID"))
            .flatMap(CallService::parseUuid);
    return routeId.filter(timetableRoutes).isPresent() ? Duration.ZERO : wait;
  }

  /**
   * 叫来的车回库先走哪条回库交路：与这一趟叫车跑的交路同一交路组（{@code spawn_group}）、同一线路、从车所在终点出发的回库交路—— 车从哪个车库来就回哪个车库。
   * 不是叫来的车、查不到时为空（回收照常挑）。
   *
   * @param trainName 列车名
   */
  public Optional<UUID> preferredReturnRoute(String trainName) {
    CalledTrain train = trainName == null ? null : calledTrain(trainName);
    Optional<RouteDefinitionCache> cache = plugin.getRouteDefinitionCache();
    if (train == null || cache.isEmpty()) {
      return Optional.empty();
    }
    Optional<UUID> callRoute;
    try {
      callRoute =
          TrainPropertiesStore.exists(train.name())
              ? Optional.ofNullable(TrainPropertiesStore.get(train.name()))
                  .flatMap(
                      properties ->
                          TrainTagHelper.readTagValue(properties, TAG_CALL_ROUTE)
                              .or(() -> TrainTagHelper.readTagValue(properties, "FTA_ROUTE_ID")))
                  .flatMap(CallService::parseUuid)
              : Optional.empty();
    } catch (RuntimeException | LinkageError ex) {
      return Optional.empty();
    }
    Optional<NodeId> location =
        plugin
            .getLayoverRegistry()
            .flatMap(registry -> registry.get(train.name()))
            .map(LayoverRegistry.LayoverCandidate::locationNodeId);
    return callRoute.flatMap(id -> sameGroupReturnRoute(cache.get().entries(), id, location));
  }

  /**
   * 与这条交路同一线路、同一交路组、从车所在终点出发的回库交路；交路没写交路组时为空。
   *
   * @param location 车停的节点；不知道时不按出发终点筛
   */
  static Optional<UUID> sameGroupReturnRoute(
      Collection<RouteDefinitionCache.RouteEntry> entries,
      UUID routeId,
      Optional<NodeId> location) {
    if (entries == null || routeId == null) {
      return Optional.empty();
    }
    RouteDefinitionCache.RouteEntry own = null;
    for (RouteDefinitionCache.RouteEntry entry : entries) {
      if (entry != null && routeId.equals(entry.routeId())) {
        own = entry;
        break;
      }
    }
    Optional<String> group = own == null ? Optional.empty() : spawnGroupOf(own);
    if (group.isEmpty()) {
      return Optional.empty();
    }
    for (RouteDefinitionCache.RouteEntry entry : entries) {
      if (entry != null
          && !routeId.equals(entry.routeId())
          && entry.record().route().operationType() == RouteOperationType.RETURN
          && entry.record().line().id().equals(own.record().line().id())
          && spawnGroupOf(entry).filter(group.get()::equalsIgnoreCase).isPresent()
          && location.map(node -> startsAt(entry, node)).orElse(true)) {
        return Optional.of(entry.routeId());
      }
    }
    return Optional.empty();
  }

  /** 交路从这个节点所在的终点出发（同站不同站台、DYNAMIC 首站都算）。 */
  private static boolean startsAt(RouteDefinitionCache.RouteEntry entry, NodeId location) {
    if (!entry.stops().isEmpty()
        && DynamicStopMatcher.matchesStop(location, entry.stops().get(0))) {
      return true;
    }
    List<NodeId> waypoints = entry.definition().waypoints();
    return !waypoints.isEmpty()
        && TerminalKeyResolver.matches(
            TerminalKeyResolver.toTerminalKey(location),
            TerminalKeyResolver.toTerminalKey(waypoints.get(0)));
  }

  private static Optional<String> spawnGroupOf(RouteDefinitionCache.RouteEntry entry) {
    return SimpleTicketAssigner.readSpawnGroup(entry.record().route().metadata());
  }

  // ---------------------------------------------------------------- 车库让表定

  /**
   * 叫车票此刻能不能从车库出车：首站是车库、车库此刻又要让给表定出库时不能（{@link #depotYieldsToTimetable}）。
   * 发车侧在真正从车库出车前、放弃区间生成改走车库前都问（{@code SimpleTicketAssigner#setOnDemandDepotGate}）：
   * 排车源时判过一次，可票在队列里可能被闸门拖到表定出库的时候。
   *
   * @param ticket 叫车票
   */
  public boolean allowsDepotSpawn(SpawnTicket ticket) {
    if (ticket == null || ticket.service() == null) {
      return true;
    }
    UUID routeId = ticket.service().routeId();
    return depotOf(routeId).isEmpty() || !depotYieldsToTimetable(routeId, Instant.now());
  }

  /**
   * 这条交路的车库此刻要让给按表出库的车：同一出库点（DYNAMIC 写法按整个车库池）在 {@link #DEPOT_YIELD_BEHIND} 之前到 {@link
   * #DEPOT_YIELD_AHEAD} 之后有表定出库。叫来的车先出库、在库里被闭塞扣住时，表定那一班就生成不了。
   */
  private boolean depotYieldsToTimetable(UUID routeId, Instant now) {
    List<String> departures = timetableDepotDepartures(now);
    if (departures.isEmpty()) {
      return false;
    }
    Optional<String> own = depotOf(routeId);
    if (own.isEmpty()) {
      return false;
    }
    for (String depot : departures) {
      if (sameDepot(own.get(), depot)) {
        return true;
      }
    }
    return false;
  }

  /** 交路首站的出库点（CRET 写法）；首站不是车库时为空。 */
  private Optional<String> depotOf(UUID routeId) {
    Optional<RouteDefinitionCache> cache = plugin.getRouteDefinitionCache();
    Optional<RouteDefinition> definition = cache.flatMap(c -> c.findById(routeId));
    if (definition.isEmpty()) {
      return Optional.empty();
    }
    List<RouteStop> stops = cache.get().listStops(definition.get().id());
    return stops.isEmpty()
        ? Optional.empty()
        : SpawnDirectiveParser.findDirectiveTarget(stops.get(0), "CRET");
  }

  /** 此后一段时间里按表从车库出车的出库点：首站是车库的车次与出库走行。缓存 {@link #DEPOT_YIELD_TTL}。 */
  private List<String> timetableDepotDepartures(Instant now) {
    DepotDepartures cached = depotDepartures;
    if (!now.isBefore(cached.computedAt())
        && now.isBefore(cached.computedAt().plus(DEPOT_YIELD_TTL))) {
      return cached.depots();
    }
    List<String> depots = new ArrayList<>();
    Optional<org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableService>
        timetable = plugin.getTimetableService().filter(service -> service.settings().enabled());
    if (timetable.isPresent()) {
      Instant from = now.minus(DEPOT_YIELD_BEHIND);
      Instant to = now.plus(DEPOT_YIELD_AHEAD);
      for (var trip : timetable.get().tripsBetween(from, to)) {
        depotOf(trip.trip().routeId()).ifPresent(depots::add);
      }
      for (var leg : timetable.get().legsBetween(from, to)) {
        if (leg.kind() == RouteOperationType.CREATE) {
          depotOf(leg.routeId()).ifPresent(depots::add);
        }
      }
    }
    List<String> out = List.copyOf(depots);
    depotDepartures = new DepotDepartures(now, out);
    return out;
  }

  /**
   * 两个出库点是不是同一处：都是固定股道时比股道，有一个是 DYNAMIC 写法（车库池）时比车库。
   *
   * @param a 出库点（CRET 写法）
   * @param b 出库点（CRET 写法）
   */
  static boolean sameDepot(String a, String b) {
    if (a == null || b == null || a.isBlank() || b.isBlank()) {
      return false;
    }
    boolean dynamic =
        SpawnDirectiveParser.isDynamicTarget(a) || SpawnDirectiveParser.isDynamicTarget(b);
    if (!dynamic) {
      return a.trim().equalsIgnoreCase(b.trim());
    }
    Optional<String> depotA = depotKey(a);
    return depotA.isPresent() && depotA.equals(depotKey(b));
  }

  /** 出库点所在的车库（{@code 运营商:类型:名称}，小写）。 */
  private static Optional<String> depotKey(String spec) {
    if (SpawnDirectiveParser.isDynamicTarget(spec)) {
      return DynamicStopMatcher.parseDynamicSpec(spec.trim())
          .map(DynamicStopMatcher::specToStationKey);
    }
    return DynamicStopMatcher.extractStationKey(spec.trim());
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

  /**
   * 开放叫车、在运营的线路（按线路 id）。过了 {@link #LINE_CACHE_TTL}
   * 就在后台重读存储（改了线路开关很快就生效），读完之前沿用上一份：站台屏每秒都要用它，不能在主线程等数据库。
   */
  private Map<UUID, Line> callableLines() {
    if (!Instant.now().isBefore(linesLoadedAt.plus(LINE_CACHE_TTL))) {
      reloadLines();
    }
    return lines;
  }

  /** 后台重读线路开关，同一时刻只读一份；读失败也记下时刻，过一个 {@link #LINE_CACHE_TTL} 再试。插件未启用时当场读。 */
  private void reloadLines() {
    if (!linesLoading.compareAndSet(false, true)) {
      return;
    }
    long generation = linesGeneration.get();
    Runnable read =
        () -> {
          try {
            readCallableLines()
                .ifPresent(
                    loaded -> {
                      if (!loaded.equals(lines)) {
                        lines = loaded;
                        hints.clear();
                      }
                      linesLoaded = true;
                    });
          } finally {
            if (linesGeneration.get() == generation) {
              linesLoadedAt = Instant.now();
            }
            linesLoading.set(false);
          }
        };
    try {
      if (plugin.isEnabled()) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, read);
        return;
      }
    } catch (RuntimeException ex) {
      debug("后台读取叫车线路排不上 error=" + ex);
    }
    read.run();
  }

  /** 线路开关至少成功读到过一次；还没有时发起读取。 */
  private boolean linesReady() {
    if (!linesLoaded) {
      reloadLines();
    }
    return linesLoaded;
  }

  private Optional<Map<UUID, Line>> readCallableLines() {
    Map<UUID, Line> loaded = new HashMap<>();
    try {
      Optional<StorageProvider> provider = plugin.getStorageManager().provider();
      if (provider.isEmpty()) {
        return Optional.empty();
      }
      for (Line line : provider.get().lines().listAll()) {
        if (line != null
            && line.status() == LineStatus.ACTIVE
            && LineCallMetadata.allowsPlayerCall(line.metadata())) {
          loaded.put(line.id(), line);
        }
      }
    } catch (RuntimeException ex) {
      debug("读取叫车线路失败 error=" + ex);
      return Optional.empty();
    }
    return Optional.of(Map.copyOf(loaded));
  }

  private int maxCalls(UUID lineId) {
    Line line = callableLines().get(lineId);
    int fallback = settings().defaultMaxTrains();
    return line == null ? fallback : LineCallMetadata.maxTrains(line.metadata()).orElse(fallback);
  }

  /** 线路上叫来的车（不含回库途中的）与还没派出的叫车。 */
  private Map<UUID, Integer> activeCallsByLine() {
    Map<UUID, Integer> out = new HashMap<>();
    for (CalledTrain train : calledTrains.values()) {
      if (!train.returning()) {
        train.lineId().ifPresent(lineId -> out.merge(lineId, 1, Integer::sum));
      }
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
