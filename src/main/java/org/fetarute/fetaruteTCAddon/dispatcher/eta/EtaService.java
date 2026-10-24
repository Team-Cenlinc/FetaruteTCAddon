package org.fetarute.fetaruteTCAddon.dispatcher.eta;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.fetarute.fetaruteTCAddon.company.api.StationDirectory;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.cache.EtaCache;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.PathProgressModel;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.RunCurveModel;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.SpawnTrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.StationThroats;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.TravelTimeModel;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainRuntimeSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainSnapshotStore;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLineChanges;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeStopState;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationPresenceTracker;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfig;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.DepotSpawnPattern;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.DutyContinuitySupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnForecastSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnTicket;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.TicketAssigner;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;

/**
 * ETA 服务（唯一入口）。
 *
 * <p>职责：为 HUD/内部占位符提供结构化 ETA。
 *
 * <p>数据来源：
 *
 * <ul>
 *   <li>运行时列车快照：{@link TrainSnapshotStore}
 *   <li>未发车票据：{@link SpawnManager}/{@link TicketAssigner} 的队列快照
 * </ul>
 *
 * <p>约束：EtaService 不做运行时采样，也不读库；只读 Snapshot + Graph/Route/车站目录快照 + Cache（站牌由站台屏、站台广播在主线程高频查询）。
 *
 * <p>注意：站牌会合并未出票服务预测（见 {@link SpawnForecastSupport}）；若 SpawnMonitor 未运行或计划为空，站牌仍可能为空。
 *
 * <p>Layover：若起点存在待命列车，未发车 ETA 会使用候选的 readyAt 修正最早发车时间。
 *
 * <h2>走行模型</h2>
 *
 * <p>与编表同一条运行曲线（{@link RunCurveModel}）：按停车点拆段，第一段从列车当前位置与速度出发、之后每段从停车点静止起步， 受车种加减速约束，按边的有效限速行驶，进站前按
 * {@code runtime.approach-*} 减速；途中车站停站 = dwell + 停站开销。 所以 ETA 与表定时刻的差就是晚点，不含两套模型的口径差。
 *
 * <ul>
 *   <li>边限速：运行时的有效限速（图基础限速 + 永久覆盖 + 临时限速）
 *   <li>加减速：默认车种；未发车票据按 CRET 车库推断车种
 *   <li>当前速度：快照有采样时用，没有时按首边限速
 * </ul>
 *
 * <h2>线程</h2>
 *
 * <p>站台屏、站台广播在主线程调用，web-api 在自己的后台线程调用站牌（钩子背后的注册表已能容忍非主线程的只读访问，见 {@code docs/dev/web-api.md}）。计算本体在
 * {@link EtaCalculator}，外部事实经由 {@link EtaSources} 读取：
 *
 * <ul>
 *   <li>单车与票据 ETA（{@link #getForTrain}、{@link #getForTicket}）在调用线程上现算，带短时缓存。只算一列车，开销小； HUD
 *       要靠它及时报“即将到站”，经过节点时还会主动作废（{@link #invalidateTrainEta}），所以不改成先给旧值。
 *   <li>站牌（{@link #getBoard}）要遍历全网列车与票据，交给后台线程：调用线程把钩子背后的事实拷成 {@link EtaBoardInput}， 后台只读这份拷贝算站牌。
 *       缓存新鲜时直接返回；过期但不太旧时返回旧值并在后台刷新（同一块站牌同一时刻只有一个刷新任务）；没有旧值、旧值太旧、 调度图换过或后台停用时在调用线程上现算。调用线程从不等后台。
 * </ul>
 *
 * <p>插件停用时调用 {@link #close()}，之后站牌一律在调用线程上现算。
 *
 * @see RunCurveModel
 * @see SpawnTrainConfigResolver
 */
public final class EtaService implements AutoCloseable {

  /** 站牌单次重算超过该耗时（纳秒）时输出调试日志。 */
  private static final long SLOW_BOARD_NANOS = 5_000_000L;

  /**
   * 未知时长扣停的估算上限（秒）。
   *
   * <p>扣停时长重尾（PROTECTIVE_RETAIN_HOLD 多数只有数秒，个别死锁可达上千秒）， 按“已扣多久就估计还要多久”顺延：扣得越久 ETA
   * 越往后推，解除后立刻回落。上限防止一次长时间死锁把站牌推到半小时后。
   */
  static final long HOLD_ESTIMATE_CAP_SEC = 300L;

  /**
   * 停站计时结束后、正常关门与过发车门控的用时（秒）；超出才算停站超时（扣停）。
   *
   * <p>停站计时结束到实际发车通常在 5 秒内。
   */
  static final long STATION_DEPARTURE_OVERHEAD_SEC = 5L;

  /** 站牌结果在这么久内直接返回。 */
  static final Duration BOARD_FRESH = Duration.ofMillis(1500);

  /**
   * 站牌旧值最多用多久：超过时不再先给旧值，在调用线程上现算。
   *
   * <p>站台屏按 {@code render.snapshot-ttl-seconds}（默认 5 秒）取一次站牌，上限要比它宽，屏幕才能一直走后台刷新； 又不能太宽，偶尔查一次的命令与公开
   * API 不该拿到很久以前的站牌。
   */
  static final Duration BOARD_MAX_STALE = Duration.ofSeconds(15);

  /**
   * 后台刷新共用同一份站牌输入的时长：这段时间内各站的刷新共用一份拷贝，拷贝开销不随车站数增长。
   *
   * <p>须短于 {@link #BOARD_FRESH}：过期的站牌刷新时用的输入一定比它自己的输入新。
   */
  static final Duration BOARD_INPUT_SHARE = Duration.ofMillis(500);

  /** 每写入多少次站牌清一遍太旧的条目（车站改名、撤屏后旧键不会再被读到）。 */
  private static final int BOARD_PURGE_EVERY = 256;

  /** 自有后台线程空闲多久后退出。 */
  private static final long BOARD_THREAD_IDLE_SECONDS = 30L;

  private static final AtomicInteger BOARD_THREAD_IDS = new AtomicInteger();

  private final TrainSnapshotStore snapshotStore;
  private final RailGraphService railGraphService;
  private final RouteDefinitionCache routeDefinitions;
  private final InstantSource clock;

  /** 咽喉查找：按图实例缓存，单车 ETA 与后台站牌共用。 */
  private final StationThroats stationThroats = new StationThroats();

  private final EtaSources.Routes liveRoutes;
  private final EtaSources.Trains liveTrains;
  private final EtaSources.Tickets liveTickets = new LiveTickets();

  /** 运行时真实停车状态（列车名 → 当前 STOP 生命周期）；未接入时视为无扣停。 */
  private volatile java.util.function.Function<String, Optional<RuntimeStopState>> stopStates;

  /** 联挂之后的去向：回程支线交路 → 联挂后接着跑的主干交路；未接入或不联挂时为空。 */
  private volatile java.util.function.Function<UUID, Optional<ThroughRoute>> throughRoutes =
      routeId -> Optional.empty();

  /**
   * 回程支线联挂以后接着跑的主干。
   *
   * @param route 主干交路
   * @param joinIndex 联挂站在主干交路里的下标
   */
  public record ThroughRoute(UUID route, int joinIndex) {

    public ThroughRoute {
      Objects.requireNonNull(route, "route");
    }
  }

  /** 在运营终点分行后留下、不再载客的那一段停在本站（见 {@link DivisionRemainders}）；未接入时没有。 */
  private volatile DivisionRemainders divisionRemainders = (train, route, index) -> false;

  /** 时刻表计划发车（列车名, 停靠序号 → 计划发车时刻）；未接入或未按表运行时为空。 */
  private volatile java.util.function.BiFunction<String, Integer, Optional<Instant>>
      plannedDepartures;

  /** 与 StationStopCoordinator 同口径的计划扣留上限；早于计划超过它的不会被扣留。 */
  private volatile Duration plannedHoldCap = Duration.ZERO;

  /** 时刻表计划到达（列车名, 停靠序号 → 计划到达时刻）；只用于站牌行的晚点秒数。 */
  private volatile java.util.function.BiFunction<String, Integer, Optional<Instant>>
      plannedArrivals;

  /** 运行时实际节点（列车名, 交路 → DYNAMIC 选台后的节点序列）；未接入时按交路声明节点估算。 */
  private volatile java.util.function.BiFunction<String, RouteDefinition, List<NodeId>>
      effectiveWaypoints;

  /** 运行时哪些下标已有实际节点；未接入时把实际节点与声明节点不同的下标视为已定站台。 */
  private volatile PlacedStops placedStops;

  private volatile SkippedStops skippedStops;

  private volatile PlannedPlatforms plannedPlatforms;

  /** 还没派出的票预先指定的站台（叫车）；为 null 时没有。 */
  private volatile TicketPlatforms ticketPlatforms;

  /** 列车在站记录，用来识别“已到站、停站计时尚未开始”的空档；未接入时该空档按 0 计。 */
  private volatile java.util.function.Supplier<Optional<StationPresenceTracker>> stationPresence;

  /** 线路代码的规范写法（直通指令里的代码可能写成小写）；未接入时原样显示。 */
  private volatile java.util.function.UnaryOperator<RouteLineChanges.LineRef> lineCanonicalizer =
      java.util.function.UnaryOperator.identity();

  private volatile java.util.function.Consumer<String> debugLogger = message -> {};

  private final EtaCache<String, EtaResult> trainCache = new EtaCache<>(Duration.ofMillis(800));
  private final EtaCache<String, EtaResult> ticketCache = new EtaCache<>(Duration.ofMillis(1200));
  private final EtaBoardStats boardStats;

  /** 站牌：键 → 最近一次结果与它的输入戳。 */
  private final ConcurrentMap<String, BoardEntry> boards = new ConcurrentHashMap<>();

  /** 正在后台刷新的站牌键。 */
  private final ConcurrentMap<String, InFlight> refreshing = new ConcurrentHashMap<>();

  private final AtomicLong inputSequence = new AtomicLong();
  private final AtomicInteger boardPuts = new AtomicInteger();

  /** 最近一次拷贝的站牌输入，{@link #BOARD_INPUT_SHARE} 内的后台刷新共用。 */
  private volatile EtaBoardInput sharedInput;

  /** 接入的后台执行器；为 null 时用自有线程。 */
  private volatile Executor injectedExecutor;

  private final Object executorLock = new Object();

  /** 自有后台线程（按需创建，空闲后线程退出）；由 {@link #executorLock} 保护。 */
  private ThreadPoolExecutor ownExecutor;

  private volatile boolean closed;

  /** 未发车票据的车种推断，按交路缓存。 */
  private final SpawnTrainConfigResolver spawnTrainConfigs;

  private volatile java.util.function.BiFunction<UUID, Optional<String>, Optional<TrainConfig>>
      plannedConsist = (routeId, consist) -> Optional.empty();

  private volatile SpawnManager spawnManager;
  private volatile java.util.function.Supplier<List<SpawnTicket>> pendingTicketSupplier;
  private volatile LayoverRegistry layoverRegistry;

  /** 车站 ID → 车站与所属运营商（内存目录）；站牌解析终点站用，不读库。 */
  private volatile java.util.function.Function<UUID, Optional<StationDirectory.StationEntry>>
      stationLookup = id -> Optional.empty();

  private volatile SignNodeRegistry signNodeRegistry;

  /** 当前配置；每次估算现读，{@code /fta reload} 之后立即生效。未接入时按默认加减速、不做进站限速、不加停站开销。 */
  private volatile java.util.function.Supplier<ConfigManager.ConfigView> configSource;

  public EtaService(
      TrainSnapshotStore snapshotStore,
      RailGraphService railGraphService,
      RouteDefinitionCache routeDefinitions) {
    this(snapshotStore, railGraphService, routeDefinitions, InstantSource.system());
  }

  /**
   * @param clock 时钟（测试注入）
   */
  EtaService(
      TrainSnapshotStore snapshotStore,
      RailGraphService railGraphService,
      RouteDefinitionCache routeDefinitions,
      InstantSource clock) {
    // ETA 不用占用预判估算等待，改读运行时真实停车状态（见 attachRuntimeStopStates）。
    this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
    this.railGraphService = Objects.requireNonNull(railGraphService, "railGraphService");
    this.routeDefinitions = Objects.requireNonNull(routeDefinitions, "routeDefinitions");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.liveRoutes = EtaSources.Routes.of(routeDefinitions);
    this.liveTrains = EtaSources.Trains.of(snapshotStore);
    this.boardStats = new EtaBoardStats(clock.instant());
    this.spawnTrainConfigs =
        new SpawnTrainConfigResolver(this::readRoute, this::readRouteStops, this::readDepotSign);
    routeDefinitions.addChangeListener(spawnTrainConfigs::invalidateAll);
  }

  /** 接入调试日志（读取外部状态失败时留痕）。 */
  public void attachDebugLogger(java.util.function.Consumer<String> logger) {
    this.debugLogger = logger == null ? message -> {} : logger;
  }

  /**
   * 接入站牌后台刷新用的执行器。
   *
   * <p>不接入时用自有的单个守护线程（按需创建，空闲 30 秒后退出）。执行器拒绝任务时这一次在调用线程上现算。
   *
   * @param executor 执行器；传 null 恢复用自有线程
   */
  public void attachBoardExecutor(Executor executor) {
    this.injectedExecutor = executor;
  }

  /**
   * 停用站牌后台刷新：关掉自有线程，之后站牌一律在调用线程上现算（与缓存过期即重算相同）。
   *
   * <p>插件停用时调用；只发出关闭，不等正在算的那一次（主线程不阻塞），它算完写回缓存后线程退出。接入的执行器由接入方自己关。
   */
  @Override
  public void close() {
    ThreadPoolExecutor executor;
    synchronized (executorLock) {
      closed = true;
      executor = ownExecutor;
      ownExecutor = null;
    }
    if (executor != null) {
      executor.shutdown();
    }
  }

  /**
   * 接入运行时真实停车状态，ETA 据此判断列车是否被扣停、扣了多久。
   *
   * @param stopStates 列车名 → 当前 STOP 生命周期；传 null 表示断开
   */
  public void attachRuntimeStopStates(
      java.util.function.Function<String, Optional<RuntimeStopState>> stopStates) {
    this.stopStates = stopStates;
  }

  /**
   * 接入联挂之后的去向：回程支线的车在联挂站之前，站牌主目的地写主干的运营终点（乘客留在车上就到那里）；联挂站上它仍是终到。
   *
   * @param through 回程支线交路 → 联挂后接着跑的主干交路与联挂站在主干里的下标；传 null 表示断开
   */
  public void attachThroughRoutes(
      java.util.function.Function<UUID, Optional<ThroughRoute>> through) {
    this.throughRoutes = through == null ? routeId -> Optional.empty() : through;
  }

  /** 分行站就是运营终点、解下的一段接着载客时，拆开以后留下的一段在这里终到、不再载客。站台上同时停着两段，站牌只列解下的一段（开往支线），留下的一段停着的时候不另列“本站终到”。 */
  @FunctionalInterface
  public interface DivisionRemainders {

    /**
     * @param trainName 列车名
     * @param routeId 列车此刻的交路（{@code 运营商:线路:交路}）
     * @param index 列车此刻的路径点下标
     * @return 这辆车是在这里分行后留下、不再载客的一段
     */
    boolean remainsAt(String trainName, String routeId, int index);
  }

  /**
   * 接入分行后留下、不再载客的那一段（见 {@link DivisionRemainders}）。
   *
   * @param remainders 传 null 表示断开
   */
  public void attachDivisionRemainders(DivisionRemainders remainders) {
    this.divisionRemainders = remainders == null ? (train, route, index) -> false : remainders;
  }

  /**
   * 接入时刻表计划发车，早到列车在站内等点的时间计入 ETA。
   *
   * @param plannedDepartures (列车名, 停靠序号) → 计划发车时刻；传 null 表示断开
   * @param holdCap 计划扣留上限，与 StationStopCoordinator 同口径；非正值表示不扣留
   */
  public void attachPlannedDepartures(
      java.util.function.BiFunction<String, Integer, Optional<Instant>> plannedDepartures,
      Duration holdCap) {
    this.plannedDepartures = plannedDepartures;
    this.plannedHoldCap =
        holdCap == null || holdCap.isNegative() || holdCap.isZero() ? Duration.ZERO : holdCap;
  }

  /**
   * 接入时刻表计划到达，站牌行据此给出运行中列车到达本站的晚点秒数。
   *
   * @param plannedArrivals (列车名, 停靠序号) → 计划到达时刻；传 null 表示断开
   */
  public void attachPlannedArrivals(
      java.util.function.BiFunction<String, Integer, Optional<Instant>> plannedArrivals) {
    this.plannedArrivals = plannedArrivals;
  }

  /**
   * 接入运行时实际节点：DYNAMIC 选台后，ETA 按选中的股道估算，而不是交路里声明的占位股道。
   *
   * @param effectiveWaypoints (列车名, 交路) → 与交路等长的实际节点序列；传 null 表示断开
   */
  public void attachEffectiveWaypoints(
      java.util.function.BiFunction<String, RouteDefinition, List<NodeId>> effectiveWaypoints) {
    this.effectiveWaypoints = effectiveWaypoints;
  }

  /**
   * 接入运行时已定站台的记录：DYNAMIC 选中的恰好是占位股道时，实际节点与声明节点相同，只有这份记录能把它与尚未选台分开， 站牌才不会把已选好的站台显示成待定。
   *
   * @param placedStops 运行时已定站台的记录；传 null 表示断开
   */
  public void attachPlacedStops(PlacedStops placedStops) {
    this.placedStops = placedStops;
  }

  /**
   * 接入信号员令越过的停车站：站牌上这趟车在那一站成为通过行。
   *
   * @param skippedStops 来源；传 null 表示断开
   */
  public void attachSkippedStops(SkippedStops skippedStops) {
    this.skippedStops = skippedStops;
  }

  /** 信号员令越过的停车站（只在内存里，读可在任意线程）。 */
  @FunctionalInterface
  public interface SkippedStops {

    /**
     * @param trainName 列车名
     * @param routeUuid 列车当前交路
     * @param index 交路节点下标
     */
    boolean skipped(String trainName, UUID routeUuid, int index);
  }

  /** 运行时某停靠下标是否已有实际节点（DYNAMIC 已选台，或到站时记下了实际股道）。 */
  @FunctionalInterface
  public interface PlacedStops {

    /**
     * @param trainName 列车名
     * @param route 列车当前交路
     * @param index 交路节点下标
     */
    boolean placed(String trainName, RouteDefinition route, int index);
  }

  /**
   * 接入运行中列车的计划站台来源：DYNAMIC 尚未选台时，站牌写时刻表排定的计划站台，或列车下一个停车站的暂定站台。
   *
   * @param plannedPlatforms 来源；传 null 表示断开
   */
  public void attachPlannedPlatforms(PlannedPlatforms plannedPlatforms) {
    this.plannedPlatforms = plannedPlatforms;
  }

  /**
   * 接入还没派出的票预先指定的站台：叫车票在 DYNAMIC 停靠指定了右键的那条股道时，站牌排队行写它。
   *
   * @param ticketPlatforms 来源；传 null 表示断开
   */
  public void attachTicketPlatforms(TicketPlatforms ticketPlatforms) {
    this.ticketPlatforms = ticketPlatforms;
  }

  /** 还没派出的票在某个 DYNAMIC 停靠预先指定的站台。 */
  @FunctionalInterface
  public interface TicketPlatforms {

    /**
     * @param ticket 发车票
     * @param index 交路节点下标
     * @return 指定的股道；没有时为空
     */
    Optional<NodeId> pinned(SpawnTicket ticket, int index);
  }

  /** 运行中列车在某个尚未选台的 DYNAMIC 停靠的计划或暂定站台。 */
  @FunctionalInterface
  public interface PlannedPlatforms {

    /**
     * @param trainName 列车名
     * @param route 列车当前交路
     * @param index 交路节点下标
     * @return 计划或暂定股道
     */
    Optional<NodeId> planned(String trainName, RouteDefinition route, int index);
  }

  /**
   * 接入列车在站记录：到站后、停站计时注册前的几秒里，本站停站按计划停站计入 ETA。
   *
   * @param stationPresence 在站记录来源；传 null 表示断开
   */
  public void attachStationPresence(
      java.util.function.Supplier<Optional<StationPresenceTracker>> stationPresence) {
    this.stationPresence = stationPresence;
  }

  /**
   * 接入线路代码的规范写法：站牌行显示直通运转换线后的线路时，与公开 API、HUD 用同一种写法。
   *
   * @param canonicalizer 线路 → 主数据写法的线路；传 null 表示断开（原样显示）
   */
  public void attachLineCanonicalizer(
      java.util.function.UnaryOperator<RouteLineChanges.LineRef> canonicalizer) {
    this.lineCanonicalizer =
        canonicalizer == null ? java.util.function.UnaryOperator.identity() : canonicalizer;
  }

  /** 绑定票据来源（SpawnManager/TicketAssigner），用于未发车 ETA。 */
  public void attachTicketSources(SpawnManager spawnManager, TicketAssigner ticketAssigner) {
    this.spawnManager = spawnManager;
    this.pendingTicketSupplier =
        ticketAssigner != null ? ticketAssigner::snapshotPendingTickets : null;
  }

  /** 绑定 LayoverRegistry，用于未发车 ETA 的待命时间修正。 */
  public void attachLayoverRegistry(LayoverRegistry layoverRegistry) {
    this.layoverRegistry = layoverRegistry;
  }

  /**
   * 接入车站目录：站牌解析终点站（车站 → 站码与运营商）时查内存目录。站牌由站台屏、站台广播在主线程高频查询，不能读库。
   *
   * @param stationLookup 车站 ID → 车站与所属运营商；传 null 表示断开（终点站只按节点解析）
   */
  public void attachStationLookup(
      java.util.function.Function<UUID, Optional<StationDirectory.StationEntry>> stationLookup) {
    this.stationLookup = stationLookup == null ? id -> Optional.empty() : stationLookup;
  }

  /**
   * 接入编组方案：票上指定了车型（按表出的票，交路的车型）时按这个车型估算走行；没指定、route 绑了方案时按方案下一班预计的车型， 不再读车库牌子推断。
   *
   * @param plannedConsist (routeId, 票上的车型) → 车型的加减速与最高速度；都没有时为空
   */
  public void attachPlannedConsist(
      java.util.function.BiFunction<UUID, Optional<String>, Optional<TrainConfig>> plannedConsist) {
    this.plannedConsist =
        plannedConsist == null ? (routeId, consist) -> Optional.empty() : plannedConsist;
  }

  /**
   * 接入配置与牌子注册表。
   *
   * <p>走行参数（车种加减速、进站规则、默认速度、车站停站开销）与编表读同一组配置（{@link RunCurveModel.Settings#fromConfig}），表定时分与 ETA
   * 出自同一条运行曲线；牌子注册表用于未发车票据按车库推断车种。
   *
   * @param signNodeRegistry 牌子注册表
   * @param configSource 当前配置；每次估算现读
   */
  public void attachConfigSources(
      SignNodeRegistry signNodeRegistry,
      java.util.function.Supplier<ConfigManager.ConfigView> configSource) {
    this.signNodeRegistry = signNodeRegistry;
    this.configSource = configSource;
    spawnTrainConfigs.invalidateAll();
  }

  private ConfigManager.ConfigView currentConfig() {
    java.util.function.Supplier<ConfigManager.ConfigView> source = this.configSource;
    if (source == null) {
      return null;
    }
    try {
      return source.get();
    } catch (RuntimeException ex) {
      debugLogger.accept("ETA_CONFIG_READ_FAILED error=" + ex);
      return null;
    }
  }

  /**
   * 查询指定列车的 ETA。
   *
   * @param trainName 列车名（trainId）
   * @param target 目标
   */
  public EtaResult getForTrain(String trainName, EtaTarget target) {
    Instant now = clock.instant();
    String key = trainName + "|" + (target == null ? "null" : target);
    return trainCache
        .getIfFresh(key, now)
        .orElseGet(
            () -> {
              EtaResult r = liveCalculator().computeForTrain(trainName, target, now);
              trainCache.put(key, r, now);
              return r;
            });
  }

  /**
   * 使指定列车的 ETA 缓存失效，下次 getForTrain 会重新计算。
   *
   * <p>适用于列车经过 waypoint 或信号确认等需要立即刷新 ETA 的场景。
   */
  public void invalidateTrainEta(String trainName) {
    if (trainName == null || trainName.isBlank()) {
      return;
    }
    trainCache.invalidateByPrefix(trainName + "|");
  }

  /** 丢弃各站牌的缓存，下一次查询现算：某列车的停站计划变了（信号员令越站或撤销），旧站牌会把它画错好几秒。 */
  public void invalidateBoards() {
    boards.clear();
  }

  /**
   * 预排班/未发车 ETA。
   *
   * <p>说明：仅基于已生成的票据（pending queue），不会读取 SpawnPlan 本身。
   */
  public EtaResult getForTicket(String ticketId) {
    Instant now = clock.instant();
    String key = ticketId == null ? "null" : ticketId.trim();
    return ticketCache
        .getIfFresh(key, now)
        .orElseGet(
            () -> {
              EtaResult r = liveCalculator().computeForTicket(ticketId, now);
              ticketCache.put(key, r, now);
              return r;
            });
  }

  /**
   * 站牌列表：基于运行中列车 + 未发车票据聚合输出。
   *
   * <p>注意：stationId 支持两种输入：
   *
   * <ul>
   *   <li><code>StationCode</code>
   *   <li><code>Operator:StationCode</code>
   * </ul>
   *
   * <p>限制：
   *
   * <ul>
   *   <li>站牌会合并已生成票据与未出票预测（若 SpawnManager 支持预测）。
   *   <li>仅保留 ETA 落在 horizon 窗口内的行，窗口外会被过滤。
   *   <li>站点匹配依赖 RouteDefinition 的节点序列，若站点未映射到图节点会被忽略。
   * </ul>
   *
   * <p>新鲜度：{@link #BOARD_FRESH} 内直接返回缓存；之后、{@link #BOARD_MAX_STALE} 以内返回上一次的结果并在后台刷新，
   * 下一次查询拿到新结果。行里的时刻都是绝对时刻，旧结果只是晚一点反映列车状态的变化。调度图换过之后不再返回按旧图算的结果。
   */
  public BoardResult getBoard(String stationId, String lineId, Duration horizon) {
    if (stationId == null || stationId.isBlank()) {
      return new BoardResult(List.of());
    }
    Instant now = clock.instant();
    String key =
        stationId.trim()
            + "|"
            + (lineId == null ? "null" : lineId.trim())
            + "|"
            + (horizon == null ? "null" : horizon.toString());
    long graphVersion = railGraphService.snapshotVersion();
    BoardEntry cached = boards.get(key);
    if (cached != null && cached.graphVersion() == graphVersion) {
      Duration age = Duration.between(cached.capturedAt(), now);
      if (age.compareTo(BOARD_FRESH) <= 0) {
        boardStats.recordCacheHit();
        return cached.result();
      }
      if (age.compareTo(BOARD_MAX_STALE) <= 0
          && refreshInBackground(key, stationId, lineId, horizon, now, graphVersion)) {
        boardStats.recordStaleHit();
        return cached.result();
      }
    }
    return computeBoardNow(key, stationId, lineId, horizon, now, graphVersion);
  }

  /** 在调用线程上拷贝输入并算站牌（冷启动、旧值太旧、换图或后台不可用时）。 */
  private BoardResult computeBoardNow(
      String key,
      String stationId,
      String lineId,
      Duration horizon,
      Instant now,
      long graphVersion) {
    long startNanos = System.nanoTime();
    EtaBoardInput input = captureInput(now, EtaCalculator.boardWindow(horizon), graphVersion);
    BoardResult result = computeBoard(input, stationId, lineId, horizon);
    long elapsedNanos = System.nanoTime() - startNanos;
    boardStats.recordCompute(elapsedNanos);
    logIfSlow(stationId, elapsedNanos, result, "caller");
    store(key, input, result);
    return result;
  }

  /**
   * 交给后台刷新这块站牌。
   *
   * @return 旧值可以先用（已在刷新，或刚交出刷新任务，或拷贝输入失败、继续用旧值）；后台不可用时为 false，由调用方现算
   */
  private boolean refreshInBackground(
      String key,
      String stationId,
      String lineId,
      Duration horizon,
      Instant now,
      long graphVersion) {
    if (closed) {
      return false;
    }
    InFlight mine = new InFlight(now);
    InFlight running = refreshing.putIfAbsent(key, mine);
    if (running != null
        && (Duration.between(running.since(), now).compareTo(BOARD_MAX_STALE) <= 0
            || !refreshing.replace(key, running, mine))) {
      return true;
    }
    // 走到这里：没有在刷新，或上一次交出的任务迟迟没跑（执行器丢了任务），由这一次接手重交。
    boolean handedOff = false;
    try {
      Executor executor = boardExecutor();
      if (executor == null) {
        return false;
      }
      EtaBoardInput input;
      try {
        input = sharedInput(now, EtaCalculator.boardWindow(horizon), graphVersion);
      } catch (RuntimeException ex) {
        // 钩子读取失败：先继续用旧值，旧值太旧之后由调用线程现算（失败照常抛给调用方）。
        debugLogger.accept("ETA_BOARD_CAPTURE_FAILED station=" + stationId + " error=" + ex);
        return true;
      }
      executor.execute(() -> refresh(key, mine, input, stationId, lineId, horizon));
      handedOff = true;
      return true;
    } catch (RuntimeException ex) {
      // 执行器拒收（已关闭，或插件停用后调度器不再接任务）：这一次由调用线程现算。
      debugLogger.accept("ETA_BOARD_REFRESH_REJECTED station=" + stationId + " error=" + ex);
      return false;
    } finally {
      if (!handedOff) {
        refreshing.remove(key, mine);
      }
    }
  }

  /** 交给后台的一次刷新：交出时刻；按身份比较，迟到的任务清不掉后来接手的标记。 */
  private static final class InFlight {
    private final Instant since;

    private InFlight(Instant since) {
      this.since = since;
    }

    private Instant since() {
      return since;
    }
  }

  /** 后台线程：按拷好的输入算站牌并写回。算的期间换了图，结果作废。 */
  private void refresh(
      String key,
      InFlight mine,
      EtaBoardInput input,
      String stationId,
      String lineId,
      Duration horizon) {
    try {
      long startNanos = System.nanoTime();
      BoardResult result = computeBoard(input, stationId, lineId, horizon);
      long elapsedNanos = System.nanoTime() - startNanos;
      boardStats.recordRefresh(elapsedNanos);
      logIfSlow(stationId, elapsedNanos, result, "refresh");
      if (railGraphService.snapshotVersion() == input.graphVersion()) {
        store(key, input, result);
      }
    } catch (RuntimeException ex) {
      debugLogger.accept("ETA_BOARD_REFRESH_FAILED station=" + stationId + " error=" + ex);
    } finally {
      refreshing.remove(key, mine);
    }
  }

  /** 写回站牌：只留输入较新的那一份；顺带定期清掉太旧的条目。 */
  private void store(String key, EtaBoardInput input, BoardResult result) {
    BoardEntry entry =
        new BoardEntry(
            result,
            input.capturedAt(),
            input.graphVersion(),
            input.trainsVersion(),
            input.sequence());
    boards.merge(key, entry, (old, fresh) -> fresh.sequence() >= old.sequence() ? fresh : old);
    if (boardPuts.incrementAndGet() % BOARD_PURGE_EVERY == 0) {
      Instant now = clock.instant();
      boards
          .values()
          .removeIf(
              known -> Duration.between(known.capturedAt(), now).compareTo(BOARD_MAX_STALE) > 0);
    }
  }

  private void logIfSlow(String stationId, long elapsedNanos, BoardResult result, String thread) {
    if (elapsedNanos > SLOW_BOARD_NANOS) {
      debugLogger.accept(
          "ETA_BOARD_SLOW station="
              + stationId
              + " ms="
              + elapsedNanos / 1_000_000L
              + " rows="
              + result.rows().size()
              + " thread="
              + thread);
    }
  }

  /** {@link #BOARD_INPUT_SHARE} 内、同一时间窗、同一张图时共用最近一次的拷贝，否则重新拷贝。 */
  private EtaBoardInput sharedInput(Instant now, Duration window, long graphVersion) {
    EtaBoardInput shared = this.sharedInput;
    if (shared != null
        && shared.graphVersion() == graphVersion
        && shared.window().equals(window)
        && !now.isBefore(shared.capturedAt())
        && Duration.between(shared.capturedAt(), now).compareTo(BOARD_INPUT_SHARE) < 0) {
      return shared;
    }
    return captureInput(now, window, graphVersion);
  }

  /** 在调用线程上拷贝站牌输入，并记为最近一次的拷贝。 */
  private EtaBoardInput captureInput(Instant now, Duration window, long graphVersion) {
    long startNanos = System.nanoTime();
    EtaBoardInput input =
        EtaBoardInput.capture(
            liveSources(),
            inputSequence.incrementAndGet(),
            now,
            window,
            graphVersion,
            snapshotStore.version(),
            debugLogger);
    boardStats.recordCapture(System.nanoTime() - startNanos);
    this.sharedInput = input;
    return input;
  }

  /** 后台执行器；停用后为 null。 */
  private Executor boardExecutor() {
    Executor injected = this.injectedExecutor;
    synchronized (executorLock) {
      if (closed) {
        return null;
      }
      if (injected != null) {
        return injected;
      }
      if (ownExecutor == null) {
        ownExecutor = newBoardThread();
      }
      return ownExecutor;
    }
  }

  private static ThreadPoolExecutor newBoardThread() {
    String name = "FetaruteTCAddon-ETA-Board-" + BOARD_THREAD_IDS.incrementAndGet();
    ThreadPoolExecutor executor =
        new ThreadPoolExecutor(
            1,
            1,
            BOARD_THREAD_IDLE_SECONDS,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            runnable -> {
              Thread thread = new Thread(runnable, name);
              thread.setDaemon(true);
              return thread;
            });
    executor.allowCoreThreadTimeOut(true);
    return executor;
  }

  /**
   * 站牌缓存条目。
   *
   * @param result 站牌
   * @param capturedAt 输入的拷贝时刻（站牌按它算，新鲜度按它判）
   * @param graphVersion 输入的调度图快照版本
   * @param trainsVersion 输入的列车快照版本
   * @param sequence 输入的拷贝序号
   */
  private record BoardEntry(
      BoardResult result,
      Instant capturedAt,
      long graphVersion,
      long trainsVersion,
      long sequence) {}

  /** 返回站牌查询的统计快照：缓存命中、重算次数与重算耗时。 */
  public EtaBoardStats.Snapshot boardStatsSnapshot() {
    return boardStats.snapshot();
  }

  /** 清零站牌查询统计，起算时刻改为现在。 */
  public void resetBoardStats() {
    boardStats.reset(clock.instant());
  }

  /**
   * 站牌列表（推荐形式）：显式传入 operator + stationCode，避免同名站点冲突。
   *
   * @param operator 运营商 code（全局唯一）
   * @param stationCode 站点 code（可能跨 operator 重名）
   */
  public BoardResult getBoard(
      String operator, String stationCode, String lineId, Duration horizon) {
    String stationId =
        operator == null || operator.isBlank()
            ? stationCode
            : operator.trim() + ":" + (stationCode == null ? "" : stationCode.trim());
    return getBoard(stationId, lineId, horizon);
  }

  /** 拷贝站牌输入（测试用：与 {@link #computeBoard} 搭配，验证拷贝与现读结果一致）。 */
  EtaBoardInput captureBoardInput(Instant now, Duration horizon) {
    return captureInput(
        now, EtaCalculator.boardWindow(horizon), railGraphService.snapshotVersion());
  }

  /** 按拷好的输入算站牌：不读任何钩子，可在后台线程调用。 */
  BoardResult computeBoard(EtaBoardInput input, String stationId, String lineId, Duration horizon) {
    return new EtaCalculator(input.sources(), railGraphService, stationThroats, debugLogger)
        .computeBoard(stationId, lineId, horizon, input.capturedAt());
  }

  /** 直接读钩子算站牌（测试用：拷贝前的原始算法）。 */
  BoardResult computeBoardFromLiveSources(
      String stationId, String lineId, Duration horizon, Instant now) {
    return liveCalculator().computeBoard(stationId, lineId, horizon, now);
  }

  /** 查询运行时采样快照（用于调试输出）。 */
  public Optional<TrainRuntimeSnapshot> getRuntimeSnapshot(String trainName) {
    return snapshotStore.getSnapshot(trainName);
  }

  /**
   * 列车当前交路上第 {@code stopIndex} 个节点的实际节点：DYNAMIC 已选台时为选中的股道，否则为交路声明节点。
   *
   * @param trainName 列车名
   * @param stopIndex 交路 {@code waypoints()} 的 0 起下标
   * @return 列车无快照、交路缺失或下标越界时为空
   */
  public Optional<NodeId> effectiveStopNode(String trainName, int stopIndex) {
    return liveCalculator().effectiveStopNode(trainName, stopIndex);
  }

  /**
   * 列车在该停靠下标实际停靠的节点：固定站台与已选台的 DYNAMIC 给出节点；DYNAMIC 尚未选台、或不知道这趟车时为空。
   *
   * <p>与 {@link #effectiveStopNode} 的区别：未选台时那里给占位股道，这里给空，显示站台号的地方据此写“待定”而不是占位股道。
   */
  public Optional<NodeId> placedStopNode(String trainName, int stopIndex) {
    return liveCalculator().placedStopNode(trainName, stopIndex);
  }

  /** 获取当前采样到的列车名集合（用于补全）。 */
  public Set<String> snapshotTrainNames() {
    return Set.copyOf(snapshotStore.snapshot().keySet());
  }

  /**
   * 供 /fta eta 命令 tab 补全使用：返回可见的 ticketId 候选。
   *
   * <p>来源包含：
   *
   * <ul>
   *   <li>运行中列车已绑定的 FTA_TICKET_ID
   *   <li>SpawnManager/TicketAssigner 队列中的待发车票据
   * </ul>
   */
  public List<String> suggestTicketIds() {
    return liveCalculator().suggestTicketIds();
  }

  /** 供 /fta eta 命令 tab 补全使用：返回已知 lineId 候选（来自运行中列车与待发车票据）。 */
  public List<String> suggestLineIds() {
    return liveCalculator().suggestLineIds();
  }

  /**
   * 列车当前是否被扣停（ETA 顺延与公开 API 扣停事件共用这一个判定）。
   *
   * <ul>
   *   <li>非例行停车（信号、占用、授权、尾保、安全状态不可用等）：开始时刻取采样器记下的连续扣停起点—— 运行时停车状态换原因或阻挡者时会整体替换、{@code enteredAt}
   *       随之重置，不能拿来量“已经扣了多久”。
   *   <li>例行停站/门控：停站计时结束后（按表早到则到计划发车后）超过 {@link #STATION_DEPARTURE_OVERHEAD_SEC}
   *       秒仍未发车，按停站超时算扣停，起点就是超时开始的那一刻。
   *   <li>折返待命、终点作业不算扣停。
   * </ul>
   *
   * @param trainName 列车名
   * @return 当前扣停；未被扣停时为空
   */
  public Optional<TrainHold> currentHold(String trainName) {
    return currentHold(trainName, clock.instant());
  }

  Optional<TrainHold> currentHold(String trainName, Instant now) {
    return liveCalculator().currentHold(trainName, now);
  }

  /**
   * 按表运行的列车到达某停靠点的偏差秒数（正数为晚点），与站牌行同一口径：预计到达减计划到达。
   *
   * <p>先查计划：不按表运行的列车不算 ETA。预计到达按下标估算（{@link EtaTarget.StopIndex}），与计划是同一个停靠点。
   *
   * @param trainName 列车名
   * @param stopIndex 停靠序号（交路 waypoints 的 0 起下标，与运行时进度索引同义）
   * @return 偏差秒数；未按表运行、没有该站计划或预计不可用时为空
   */
  public OptionalLong arrivalDeviationSeconds(String trainName, int stopIndex) {
    if (trainName == null || stopIndex < 0) {
      return OptionalLong.empty();
    }
    Optional<Instant> planned = liveCalculator().plannedArrivalOf(trainName, stopIndex);
    if (planned.isEmpty()) {
      return OptionalLong.empty();
    }
    EtaResult eta = getForTrain(trainName, new EtaTarget.StopIndex(stopIndex));
    return eta.etaEpochMillis() <= 0L
        ? OptionalLong.empty()
        : EtaCalculator.deviationSeconds(planned, eta.eta());
  }

  /** 未发车票据的 ETA（包内可见供测试直接指定目标）。 */
  EtaResult computeForSpawnTicket(SpawnTicket ticket, EtaTarget target, Instant now) {
    return liveCalculator().computeForSpawnTicket(ticket, target, now);
  }

  /**
   * 未发车票据的走行模型：车种按 Route 的 CRET 车库推断（{@link SpawnTrainConfigResolver}），推断不出来用默认车种； 其余参数与运行中列车相同。
   *
   * @param routeUuid Route UUID
   * @param worldId 这段交路所在的世界
   * @param now 当前时刻（临时限速按它判断是否生效）
   */
  TravelTimeModel resolveTravelTimeModelForRoute(UUID routeUuid, UUID worldId, Instant now) {
    return liveCalculator().resolveTravelTimeModelForRoute(routeUuid, worldId, now);
  }

  /**
   * 同 {@link #resolveTravelTimeModelForRoute(UUID, UUID, Instant)}，票上指定了车型时按这个车型（加减速与最高速度）。
   *
   * @param consist 票上的车型
   */
  TravelTimeModel resolveTravelTimeModelForRoute(
      UUID routeUuid, Optional<String> consist, UUID worldId, Instant now) {
    return liveCalculator().resolveTravelTimeModelForRoute(routeUuid, consist, worldId, now);
  }

  /** 见 {@link EtaCalculator#trimTraveled}。 */
  static PathProgressModel.PathProgress trimTraveled(
      PathProgressModel.PathProgress progress, NodeId lastPassed, OptionalDouble traveled) {
    return EtaCalculator.trimTraveled(progress, lastPassed, traveled);
  }

  /** 见 {@link EtaCalculator#ceilSeconds}。 */
  static long ceilSeconds(Duration duration) {
    return EtaCalculator.ceilSeconds(duration);
  }

  /** 见 {@link EtaCalculator#spawnsDownstreamOf}。 */
  static boolean spawnsDownstreamOf(SpawnTicket ticket, int targetIndex) {
    return EtaCalculator.spawnsDownstreamOf(ticket, targetIndex);
  }

  /** 见 {@link EtaCalculator#ticketStartIndex}。 */
  static int ticketStartIndex(SpawnTicket ticket, int targetIndex) {
    return EtaCalculator.ticketStartIndex(ticket, targetIndex);
  }

  /** 直接读钩子的计算（在调用线程上用）。 */
  private EtaCalculator liveCalculator() {
    return new EtaCalculator(liveSources(), railGraphService, stationThroats, debugLogger);
  }

  /** 实时来源：直接读当前接入的钩子，在调用线程上用。 */
  private EtaSources liveSources() {
    java.util.function.Supplier<Optional<StationPresenceTracker>> presence = this.stationPresence;
    LayoverRegistry layovers = this.layoverRegistry;
    return new EtaSources(
        liveRoutes,
        liveTrains,
        stopStates,
        plannedDepartures,
        plannedHoldCap,
        plannedArrivals,
        effectiveWaypoints,
        placedStops,
        plannedPlatforms,
        ticketPlatforms,
        presence == null
            ? null
            : (trainName, routeKey, routeIndex) ->
                presence
                    .get()
                    .map(tracker -> tracker.isAtStation(trainName, routeKey, routeIndex))
                    .orElse(false),
        lineCanonicalizer,
        stationLookup,
        currentConfig(),
        this::resolveTicketTrainConfig,
        liveTickets,
        spawnManager instanceof DutyContinuitySupport support ? support : null,
        layovers == null ? null : EtaSources.Layovers.of(layovers),
        throughRoutes,
        divisionRemainders,
        skippedStops);
  }

  /** 未发车票据的车型：票上指定或编组方案预计的车型优先，否则按 CRET 车库推断（读牌子，只能在主线程）。 */
  private TrainConfig resolveTicketTrainConfig(
      UUID routeUuid, Optional<String> consist, ConfigManager.ConfigView config, Instant now) {
    Optional<TrainConfig> planned = plannedConsist.apply(routeUuid, consist);
    return planned.isPresent()
        ? planned.get()
        : spawnTrainConfigs.resolve(routeUuid, config.trainConfigSettings(), now);
  }

  /** 实时票据来源：发车队列与待派票据（按 id 去重），以及排班器的预测。 */
  private final class LiveTickets implements EtaSources.Tickets {

    @Override
    public List<SpawnTicket> pending() {
      Set<UUID> seen = new HashSet<>();
      List<SpawnTicket> out = new ArrayList<>();
      SpawnManager manager = spawnManager;
      if (manager != null) {
        for (SpawnTicket ticket : manager.snapshotQueue()) {
          if (ticket == null) {
            continue;
          }
          if (seen.add(ticket.id())) {
            out.add(ticket);
          }
        }
      }
      java.util.function.Supplier<List<SpawnTicket>> supplier = pendingTicketSupplier;
      if (supplier != null) {
        for (SpawnTicket ticket : supplier.get()) {
          if (ticket == null) {
            continue;
          }
          if (seen.add(ticket.id())) {
            out.add(ticket);
          }
        }
      }
      return out;
    }

    @Override
    public Optional<List<SpawnTicket>> forecast(Instant now, Duration window, int maxPerService) {
      if (!(spawnManager instanceof SpawnForecastSupport forecastSupport)) {
        return Optional.empty();
      }
      List<SpawnTicket> tickets = forecastSupport.snapshotForecast(now, window, maxPerService);
      return Optional.of(tickets == null ? List.of() : tickets);
    }
  }

  private Optional<Route> readRoute(UUID routeUuid) {
    return routeDefinitions.findRecord(routeUuid).map(RouteDefinitionCache.RouteRecord::route);
  }

  /** 交路缓存里与 waypoints 对齐的停靠表：首项就是出车（CRET）站，与运行时判断同一份数据。 */
  private List<RouteStop> readRouteStops(UUID routeUuid) {
    return routeDefinitions
        .findById(routeUuid)
        .map(definition -> routeDefinitions.listStops(definition.id()))
        .orElse(List.of());
  }

  private DepotSpawnPattern.SignRead readDepotSign(NodeId depotId) {
    SignNodeRegistry registry = this.signNodeRegistry;
    return registry == null
        ? DepotSpawnPattern.SignRead.missing()
        : DepotSpawnPattern.readLoaded(registry, depotId);
  }
}
