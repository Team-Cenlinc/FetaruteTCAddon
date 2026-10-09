package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartGroupStore;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.properties.TrainPropertiesStore;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
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
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteTerminals;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnTicket;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.TicketAssigner;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/**
 * 车辆回收管理器。
 *
 * <p>职责只覆盖“待命列车的回库/销毁入口”，不直接改写运行时信号、占用或进度状态；真正的 dispatch / signal / occupancy 收敛仍由 {@link
 * TicketAssigner} 与 {@link RuntimeDispatchService} 完成。
 *
 * <p>回收优先级从高到低为：
 *
 * <ul>
 *   <li>列车已达到 {@code FTA_OP_MAX} 上限
 *   <li>待命超时
 *   <li>全局活跃列车超限
 *   <li>方向供需失衡
 * </ul>
 *
 * <p>若某次回收成功，本轮会立即扣减方向供给计数，避免同方向连续过回收；失败只记日志，不会 伪造“已回收”状态。
 */
public class ReclaimManager {

  private static final String TAG_OPERATION_TRIPS = "FTA_OP_TRIPS";
  private static final String TAG_MAX_OPERATION_TRIPS = "FTA_OP_MAX";

  /**
   * 方向供需回库阈值：当同方向待命数量 > pending 需求数量 + 此阈值时触发回库。
   *
   * <p>该策略用于高频场景下抑制“单方向过度堆车”引发的阻塞链。
   */
  private static final int DIRECTION_SURPLUS_THRESHOLD = 1;

  /** 方向供需回库最小闲置时间（秒），避免刚入 Layover 立即被回收。 */
  private static final long DIRECTION_MIN_IDLE_SECONDS = 120L;

  /**
   * 正线折返点上的待命车至少闲置这么久才立即回收：刚到时自己的下一班票可能还在路上（到达与派票不在同一拍）。
   *
   * <p>取一个扫描周期以内的短值，实际等待约为"下一次扫描"。
   */
  static final long MAINLINE_TURNBACK_MIN_IDLE_SECONDS = 15L;

  /**
   * 折返交接挂起多久记告警（秒）。
   *
   * <p>没有实测分布，只是一个"明显不正常"的量级：交接由票据分配器在每个发车 tick（{@code spawn.tick-interval-ticks}， 默认 100 tick = 5
   * 秒）重试一次，成功就注销候选、授权被拒就释放认领，挂满两分钟等于连续二十多次既没成功也没被拒。 它只决定告警，不触发任何动作；回收扫描每 {@code
   * reclaim.check-interval-seconds} 一次，所以实际在 120 秒到 120 秒 + 一个扫描间隔之间报出。
   */
  static final long STALE_DISPATCH_ATTEMPT_SECONDS = 120L;

  /**
   * "这个终点有没有回库线路"的查询结果沿用多久（秒）。
   *
   * <p>立即回收要先知道终点有没有 RETURN 交路，而每轮扫描对每辆闲置的待命车都要问一次；查一次要扫遍全部运营商的线路与首站，
   * 放在主线程上不便宜。交路很少改动，晚几分钟生效只影响"立即回收"还是"等闲置上限"。
   */
  static final long RETURN_ROUTE_RECHECK_SECONDS = 300L;

  private static final Logger HEALTH_LOGGER = Logger.getLogger("FetaruteTCAddon");

  private final FetaruteTCAddon plugin;
  private final LayoverRegistry layoverRegistry;
  private final TicketAssigner ticketAssigner;
  private final ConfigManager configManager;
  private final Consumer<String> debugLogger;
  private final java.util.function.IntSupplier activeTrainCountSupplier;

  /** 仅保存已进入 handoff attempt 的本管理器 RETURN 票据，以 ticketId 作为不可变事务身份。 */
  private final Map<String, ServiceTicket> stableReturnTickets = new HashMap<>();

  /**
   * 该回收却一直派不出 RETURN 票的车：首次失败的时刻。
   *
   * <p>典型是回库交路一直被拒、闭塞，或运营商回溯不到。确实没有回库交路的车不等这个计时，当场处理（{@link #destroyWithoutReturnRoute}）。
   * 健康监控明确把待命车排除在清除之外， 所以没有这条兜底的话这辆车会永远留在终点占着站台。
   */
  private final Map<String, Instant> strandedSince = new HashMap<>();

  /** 终点 → 有没有 RETURN 交路从这里出发，以及查询时刻；见 {@link #RETURN_ROUTE_RECHECK_SECONDS}。 */
  private final Map<String, TerminalReturnRoutes> returnRoutesByTerminal = new HashMap<>();

  /** 已经告警过的挂起交接（按 ticketId），同一次交接只告警一次。 */
  private final Set<LayoverRegistry.DispatchAttempt> staleAttemptsReported = new HashSet<>();

  /** 有乘客的车不能被兜底销毁；可注入以便测试。 */
  private final java.util.function.Predicate<String> passengerCheck;

  /** 兜底销毁的执行者：给列车名与原因，返回是否已发起销毁；可注入以便测试。 */
  private final java.util.function.BiPredicate<String, String> destroyer;

  /** 回收扫描用的时钟；滞留阈值按它计，测试里可推进。 */
  private final java.util.function.Supplier<Instant> clock;

  /**
   * 回库闸：这辆待命车能不能被带回车库。默认恒放行。
   *
   * <p>按表运行时装上 {@code TimetableService#allowsReturn}，与表定回库票同一个判据：交路还有班次要跑的车不收，否则回收会把
   * 正等着下一班的车送回车库，那一班就开了天窗。交路已经断了（剩下的班次都过了容差）的车照常回收。
   */
  private volatile java.util.function.Predicate<String> returnGate = trainName -> true;

  /** 留给还没派出的叫车的折返车：开进终点等这一单的票来接，回收一律不碰。默认没有。 */
  private volatile java.util.function.Predicate<String> heldForCall = trainName -> false;

  /**
   * 正线折返点的立即回收闸：参数是列车名与它刚跑完的交路（{@code FTA_ROUTE_ID}）。默认恒拒绝—— 不按表运行时没有"这辆车接哪一班"的对应关系， 照旧等闲置上限或方向供需。
   *
   * <p>按表运行时装上 {@code TimetableService#allowsReturnFromMainlineTurnback}。
   */
  private volatile java.util.function.BiPredicate<String, Optional<UUID>> mainlineReturnGate =
      (trainName, routeId) -> false;

  /**
   * 单股道车站判定：车停在上面就占住了全站唯一的股道（如 CHT）。默认恒否。
   *
   * <p>与正线折返点同一条规则：车进去没多久就得出来，不能在里面等后面的车次。晚点车到站时下一班可能已过容差作废，
   * 交路里剩下的班次又从别处发车、它赶不过去，回库闸却因"交路还有班次"一直不放， 它就会在唯一的股道上一直等到那些班次也过期，后车全部被挡。 装上后这种车与正线折返点一样立即回收。
   */
  private volatile java.util.function.Predicate<org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId>
      singleTrackStation = nodeId -> false;

  /**
   * 换车判定：这辆待命车是不是从交路上被换下来的（交路已交给替补车）。默认恒否。
   *
   * <p>它再也没有班可跑，停在站台上只会挡住别的车；闲置满 {@link #MAINLINE_TURNBACK_MIN_IDLE_SECONDS} 就回收，不等闲置上限。 按表运行时装上
   * {@code TimetableService#retiredFromDuty}。
   */
  private volatile java.util.function.Predicate<String> retiredVehicle = trainName -> false;

  /**
   * 交路判定：这辆待命车是不是绑在某个交路上。默认恒否。
   *
   * <p>只用于"无回库线路的车站"立即回收：绑着交路的车在这样的站上只能接本交路的下一班；没绑交路的车（例如重启后账本丢了） 还可能接一张从这里始发的首班票，照旧等闲置上限。按表运行时装上
   * {@code TimetableService#dutyBindingOf}。
   */
  private volatile java.util.function.Predicate<String> dutyBound = trainName -> false;

  /**
   * 这辆待命车该走的回库线路（它所绑交路的回库线路）。默认恒空。
   *
   * <p>一个终点常有开往不同车库的几条回库线路；回收派票时先试这一条，车回到按表该回的车库，站牌照交路写的终点才对得上。 按表运行时装上 {@code
   * TimetableService#returnRouteOf}。
   */
  private volatile java.util.function.Function<String, Optional<UUID>> preferredReturnRoute =
      trainName -> Optional.empty();

  /**
   * 等回库班判定：这辆待命车停在它自己交路带客回库班的起点站、回库班还开得成。默认恒否。
   *
   * <p>成立时回收不带走它（让回库班的票带走），它也不算本方向的闲置供给——它马上要随回库班离开，不是能接别的票的车。 按表运行时装上 {@code
   * TimetableService#awaitsOwnReturnAt}。
   */
  private volatile java.util.function.BiPredicate<
          String, org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId>
      ownReturnWait = (trainName, location) -> false;

  /** 按表没有后续任务的判定。 */
  @FunctionalInterface
  public interface IdleForGood {

    /**
     * @param trainName 列车名
     * @param location 车停的节点
     * @param routeId 车刚跑完的交路（{@code FTA_ROUTE_ID}）
     * @return 按时刻表确知它再也没有班可跑时为 true
     */
    boolean test(
        String trainName,
        org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId location,
        Optional<UUID> routeId);
  }

  /**
   * 按表没有后续任务：这辆待命车按时刻表再也没有班可跑。默认恒否。
   *
   * <p>成立时与换下来的车一样，闲置满 {@link #MAINLINE_TURNBACK_MIN_IDLE_SECONDS} 就回收、不过回库闸，没有回库线路时原地销毁。
   * 回收关着（{@code reclaim.enabled: false}）也照做：时刻表确知它没有活，留着只会占住站台。按表运行时装上 {@code
   * TimetableService#idleForGoodAt}。
   */
  private volatile IdleForGood idleForGood = (trainName, location, routeId) -> false;

  /** 回收派车成功后的通知（派走前的列车名、走的回库线路）。按表运行时用来结清交路。 */
  private volatile java.util.function.BiConsumer<String, UUID> reclaimListener =
      (trainName, routeId) -> {};

  /** 已经记过“等回库班”的列车，状态变了才再记一行。 */
  private final Set<String> ownReturnWaitReported = new HashSet<>();

  private BukkitTask task;

  public ReclaimManager(
      FetaruteTCAddon plugin,
      LayoverRegistry layoverRegistry,
      TicketAssigner ticketAssigner,
      ConfigManager configManager,
      Consumer<String> debugLogger) {
    this(
        plugin,
        layoverRegistry,
        ticketAssigner,
        configManager,
        debugLogger,
        ReclaimManager::countActiveGroupsFromStore);
  }

  public ReclaimManager(
      FetaruteTCAddon plugin,
      LayoverRegistry layoverRegistry,
      TicketAssigner ticketAssigner,
      ConfigManager configManager,
      Consumer<String> debugLogger,
      java.util.function.IntSupplier activeTrainCountSupplier) {
    this(
        plugin,
        layoverRegistry,
        ticketAssigner,
        configManager,
        debugLogger,
        activeTrainCountSupplier,
        ReclaimManager::hasPlayerPassengersByName,
        (trainName, reason) ->
            plugin
                .getRuntimeDispatchService()
                .map(service -> service.destroyTrainByName(trainName, reason))
                .orElse(false),
        Instant::now);
  }

  ReclaimManager(
      FetaruteTCAddon plugin,
      LayoverRegistry layoverRegistry,
      TicketAssigner ticketAssigner,
      ConfigManager configManager,
      Consumer<String> debugLogger,
      java.util.function.IntSupplier activeTrainCountSupplier,
      java.util.function.Predicate<String> passengerCheck,
      java.util.function.BiPredicate<String, String> destroyer,
      java.util.function.Supplier<Instant> clock) {
    this.plugin = Objects.requireNonNull(plugin);
    this.layoverRegistry = Objects.requireNonNull(layoverRegistry);
    this.ticketAssigner = Objects.requireNonNull(ticketAssigner);
    this.configManager = Objects.requireNonNull(configManager);
    this.debugLogger = debugLogger != null ? debugLogger : msg -> {};
    this.activeTrainCountSupplier =
        activeTrainCountSupplier != null ? activeTrainCountSupplier : () -> 0;
    this.passengerCheck = passengerCheck != null ? passengerCheck : trainName -> true;
    this.destroyer = destroyer != null ? destroyer : (trainName, reason) -> false;
    this.clock = clock != null ? clock : Instant::now;
  }

  /** 读不到实体时按"有人"处理：兜底销毁宁可不做，也不能删掉一辆载客的车。 */
  private static boolean hasPlayerPassengersByName(String trainName) {
    TrainProperties properties = trainName == null ? null : TrainPropertiesStore.get(trainName);
    return RuntimeDispatchService.hasPlayerPassengers(properties);
  }

  /** 装上“留给叫车的折返车”判定；{@code null} 恢复默认（没有）。 */
  public void setHeldForCall(java.util.function.Predicate<String> held) {
    this.heldForCall = held == null ? trainName -> false : held;
  }

  /** 装上回库闸；{@code null} 恢复恒放行。 */
  public void setReturnGate(java.util.function.Predicate<String> gate) {
    this.returnGate = gate == null ? trainName -> true : gate;
  }

  /** 装上正线折返点的立即回收闸；{@code null} 恢复恒拒绝。 */
  public void setMainlineReturnGate(java.util.function.BiPredicate<String, Optional<UUID>> gate) {
    this.mainlineReturnGate = gate == null ? (trainName, routeId) -> false : gate;
  }

  /**
   * 装上换车判定：被换下来的车闲置满 {@link #MAINLINE_TURNBACK_MIN_IDLE_SECONDS} 即回收。
   *
   * @param predicate 列车是不是被换下来的；{@code null} 恢复为恒否
   */
  public void setRetiredVehicle(java.util.function.Predicate<String> predicate) {
    this.retiredVehicle = predicate == null ? trainName -> false : predicate;
  }

  /**
   * 装上按表没有后续任务的判定。
   *
   * @param predicate 列车按表还有没有活；{@code null} 恢复为恒否
   */
  public void setIdleForGood(IdleForGood predicate) {
    this.idleForGood = predicate == null ? (trainName, location, routeId) -> false : predicate;
  }

  /**
   * 装上交路判定：绑着交路的车停在没有回库线路的车站上，时刻表放行就立即回收。
   *
   * @param predicate 列车是不是绑在某个交路上；{@code null} 恢复为恒否
   */
  public void setDutyBound(java.util.function.Predicate<String> predicate) {
    this.dutyBound = predicate == null ? trainName -> false : predicate;
  }

  /**
   * 装上回库线路偏好：回收派票时先试列车所绑交路的回库线路。
   *
   * @param resolver 列车名到回库线路；{@code null} 恢复为没有偏好
   */
  public void setPreferredReturnRoute(
      java.util.function.Function<String, Optional<UUID>> resolver) {
    this.preferredReturnRoute = resolver == null ? trainName -> Optional.empty() : resolver;
  }

  /**
   * 装上等回库班判定：停在自己交路带客回库班起点站、回库班还开得成的车不回收，也不算闲置供给。
   *
   * @param predicate 列车名与它停的节点；{@code null} 恢复为恒否
   */
  public void setOwnReturnWait(
      java.util.function.BiPredicate<String, org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId>
          predicate) {
    this.ownReturnWait = predicate == null ? (trainName, location) -> false : predicate;
  }

  /**
   * 装上回收派车成功的通知。
   *
   * @param listener 派走前的列车名与走的回库线路；{@code null} 恢复为不通知
   */
  public void setReclaimListener(java.util.function.BiConsumer<String, UUID> listener) {
    this.reclaimListener = listener == null ? (trainName, routeId) -> {} : listener;
  }

  /**
   * 装上单股道车站判定：停在这种站上、且过了立即回收闸（{@link #setMainlineReturnGate}）的车，与正线折返点一样立即回收。
   *
   * @param predicate 节点是不是单股道车站；{@code null} 恢复为恒否
   */
  public void setSingleTrackStation(
      java.util.function.Predicate<org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId> predicate) {
    this.singleTrackStation = predicate == null ? nodeId -> false : predicate;
  }

  public void start() {
    stop();
    long interval = configManager.current().reclaimSettings().checkIntervalSeconds() * 20L;
    task =
        Bukkit.getScheduler().runTaskTimer(plugin, this::performReclaimCheck, interval, interval);
  }

  public void stop() {
    if (task == null) {
      return;
    }
    task.cancel();
    task = null;
  }

  /**
   * 执行一次回收扫描。
   *
   * <p>扫描对象仅限 {@link LayoverRegistry} 中的候选列车。若候选列车缺少 RETURN 路线、运营商
   * 标签或存储服务不可用，则只记录诊断并跳过，不会对运行中的普通列车做破坏性清理。
   */
  void performReclaimCheck() {
    ConfigManager.ReclaimSettings settings = configManager.current().reclaimSettings();
    if (!settings.enabled()) {
      reclaimIdleForGood(settings);
      return;
    }

    int activeTrains = activeTrainCountSupplier.getAsInt();
    int maxTrains = settings.maxActiveTrains();
    boolean pressure = activeTrains > maxTrains;
    Optional<StorageProvider> providerOpt = plugin.getStorageManager().provider();

    Instant now = clock.get();
    long maxIdleSec = settings.maxIdleSeconds();

    List<LayoverRegistry.LayoverCandidate> candidates = layoverRegistry.snapshot();
    pruneStableReturnTickets(candidates);
    pruneStranded(candidates);
    reportStaleDispatchAttempts(candidates, now);
    Map<String, Integer> pendingDemandByDirection = buildPendingDemandByDirection();
    Set<String> waitingOwnReturn = new HashSet<>();
    for (LayoverRegistry.LayoverCandidate candidate : candidates) {
      if (ownReturnWait.test(candidate.trainName(), candidate.locationNodeId())) {
        waitingOwnReturn.add(candidate.trainName());
      }
    }
    ownReturnWaitReported.retainAll(waitingOwnReturn);
    Map<String, Integer> layoverSupplyByDirection =
        buildLayoverSupplyByDirection(
            candidates.stream()
                .filter(candidate -> !waitingOwnReturn.contains(candidate.trainName()))
                .toList());

    // 候选排序：优先回收闲置时间更久的列车
    List<LayoverRegistry.LayoverCandidate> sorted =
        candidates.stream()
            .sorted((c1, c2) -> c1.readyAt().compareTo(c2.readyAt())) // readyAt 更早者优先
            .collect(Collectors.toList());

    for (LayoverRegistry.LayoverCandidate candidate : sorted) {
      if (heldForCall.test(candidate.trainName())) {
        // 折返车在终点等叫车的票来接：单股道车站、正线折返点的立即回收也不碰它
        continue;
      }
      boolean shouldReclaim = false;
      long idleSec = ChronoUnit.SECONDS.between(candidate.readyAt(), now);
      String directionKey = toDirectionKey(candidate.terminalKey());
      int operationTrips = readPositiveIntTag(candidate.tags(), TAG_OPERATION_TRIPS);
      int maxOperationTrips = readPositiveIntTag(candidate.tags(), TAG_MAX_OPERATION_TRIPS);

      String blockingKind = blockingTurnbackKind(candidate.locationNodeId());
      boolean mainlineLocation = blockingKind != null;
      if (!mainlineLocation
          && idleSec >= MAINLINE_TURNBACK_MIN_IDLE_SECONDS
          && dutyBound.test(candidate.trainName())
          && withoutReturnRoute(candidate.terminalKey(), providerOpt, now)) {
        // 没有一条回库线路能从这里出发：绑着交路的车在这里只能接本交路的下一班，接不上就再也走不了，等闲置上限只会占着站台。
        blockingKind = "无回库线路的车站";
      }
      boolean mainlineReturn =
          idleSec >= MAINLINE_TURNBACK_MIN_IDLE_SECONDS
              && blockingKind != null
              && mainlineReturnGate.test(
                  candidate.trainName(),
                  parseUuidTag(candidate.tags(), RouteProgressRegistry.TAG_ROUTE_ID));
      // 时刻表已经放行（车被换下、或按表没有后续任务）：不过回库闸，没有回库线路时原地销毁。
      boolean released = false;
      if (mainlineReturn) {
        shouldReclaim = true;
        debugLogger.accept(
            "回收触发: "
                + blockingKind
                + "无后续班次 train="
                + candidate.trainName()
                + " node="
                + candidate.locationNodeId().value()
                + " idle="
                + idleSec
                + "s");
      } else if (idleSec >= MAINLINE_TURNBACK_MIN_IDLE_SECONDS
          && retiredVehicle.test(candidate.trainName())) {
        released = true;
        shouldReclaim = true;
        debugLogger.accept("回收触发: 交路已换车 train=" + candidate.trainName() + " idle=" + idleSec + "s");
      } else if (idleSec >= MAINLINE_TURNBACK_MIN_IDLE_SECONDS && isIdleForGood(candidate)) {
        released = true;
        shouldReclaim = true;
        logIdleForGood(candidate, idleSec);
      } else if (maxOperationTrips > 0 && operationTrips >= maxOperationTrips) {
        shouldReclaim = true;
        debugLogger.accept(
            "回收触发: 生命周期到达上限 train="
                + candidate.trainName()
                + " trips="
                + operationTrips
                + "/"
                + maxOperationTrips);
      } else if (idleSec > maxIdleSec) {
        shouldReclaim = true;
        debugLogger.accept("回收触发: 闲置超时 train=" + candidate.trainName() + " idle=" + idleSec + "s");
      } else if (pressure) {
        shouldReclaim = true;
        debugLogger.accept(
            "回收触发: 车辆超限 train="
                + candidate.trainName()
                + " active="
                + activeTrains
                + "/"
                + maxTrains);
      } else if (idleSec >= DIRECTION_MIN_IDLE_SECONDS) {
        int supply = layoverSupplyByDirection.getOrDefault(directionKey, 0);
        int demand = pendingDemandByDirection.getOrDefault(directionKey, 0);
        int surplus = supply - demand;
        if (surplus > DIRECTION_SURPLUS_THRESHOLD) {
          shouldReclaim = true;
          debugLogger.accept(
              "回收触发: 方向供需失衡 train="
                  + candidate.trainName()
                  + " direction="
                  + directionKey
                  + " idle="
                  + idleSec
                  + "s"
                  + " supply="
                  + supply
                  + " demand="
                  + demand
                  + " surplus="
                  + surplus);
        }
      }

      if (shouldReclaim
          && !mainlineReturn
          && !released
          && !returnGate.test(candidate.trainName())) {
        // 交路还有班次：这不是派不出回库票，不能记成滞留。
        debugLogger.accept("回收跳过: 交路还有班次要跑 train=" + candidate.trainName());
        continue;
      }
      if (shouldReclaim
          && !mainlineReturn
          && !released
          && waitingOwnReturn.contains(candidate.trainName())) {
        // 自己交路的带客回库班还开得成：让它的票带走车，不抢先派去别的车库。
        if (ownReturnWaitReported.add(candidate.trainName())) {
          debugLogger.accept("回收跳过: 等本交路的带客回库班 train=" + candidate.trainName());
        }
        continue;
      }
      if (shouldReclaim
          && reclaim(
                  candidate,
                  providerOpt,
                  now,
                  idleSec,
                  settings,
                  mainlineReturn || released,
                  mainlineLocation)
              == ReturnOutcome.ASSIGNED) {
        decrementDirectionSupply(layoverSupplyByDirection, directionKey);
        pressure = false; // 本轮执行一次回收后，立即解除压力模式
      }
    }
  }

  /**
   * 派回库票；派不出去时按情形销毁或进滞留计时。
   *
   * @param timetableReleased 时刻表已经放行（立即回收的几种情形、车被换下、按表没有后续任务）：没有回库线路时它不会再有班可跑， 等滞留计时只会占着终点，原地销毁
   * @param mainlineLocation 停在正线折返点或单股道车站（只影响日志）
   */
  private ReturnOutcome reclaim(
      LayoverRegistry.LayoverCandidate candidate,
      Optional<StorageProvider> providerOpt,
      Instant now,
      long idleSec,
      ConfigManager.ReclaimSettings settings,
      boolean timetableReleased,
      boolean mainlineLocation) {
    ReturnOutcome outcome = assignReturnTicket(candidate, providerOpt);
    if (outcome == ReturnOutcome.ASSIGNED) {
      strandedSince.remove(candidate.trainName());
    } else if (outcome == ReturnOutcome.NO_ROUTE && timetableReleased) {
      destroyWithoutReturnRoute(
          candidate, now, idleSec, settings.strandedDestroySeconds(), mainlineLocation);
    } else {
      destroyIfStranded(candidate, now, settings.strandedDestroySeconds());
    }
    return outcome;
  }

  /** 回收关着时只收按表没有后续任务的车（{@link #idleForGood}）。 */
  private void reclaimIdleForGood(ConfigManager.ReclaimSettings settings) {
    List<LayoverRegistry.LayoverCandidate> candidates = layoverRegistry.snapshot();
    pruneStableReturnTickets(candidates);
    pruneStranded(candidates);
    Instant now = clock.get();
    Optional<StorageProvider> providerOpt = plugin.getStorageManager().provider();
    for (LayoverRegistry.LayoverCandidate candidate : candidates) {
      long idleSec = ChronoUnit.SECONDS.between(candidate.readyAt(), now);
      if (idleSec < MAINLINE_TURNBACK_MIN_IDLE_SECONDS
          || heldForCall.test(candidate.trainName())
          || !isIdleForGood(candidate)) {
        continue;
      }
      logIdleForGood(candidate, idleSec);
      reclaim(
          candidate,
          providerOpt,
          now,
          idleSec,
          settings,
          true,
          blockingTurnbackKind(candidate.locationNodeId()) != null);
    }
  }

  private boolean isIdleForGood(LayoverRegistry.LayoverCandidate candidate) {
    return candidate.locationNodeId() != null
        && idleForGood.test(
            candidate.trainName(),
            candidate.locationNodeId(),
            parseUuidTag(candidate.tags(), RouteProgressRegistry.TAG_ROUTE_ID));
  }

  private void logIdleForGood(LayoverRegistry.LayoverCandidate candidate, long idleSec) {
    debugLogger.accept(
        "回收触发: 按表没有后续任务 train="
            + candidate.trainName()
            + " node="
            + candidate.locationNodeId().value()
            + " idle="
            + idleSec
            + "s");
  }

  /** 叫来的车送回库的结果。 */
  public enum CalledReturn {
    /** 已派出回库交路。 */
    ASSIGNED,
    /** 这个终点没有回库交路：按滞留兜底处理（{@code reclaim.stranded-destroy-seconds} 为 0 时只记滞留）。 */
    NO_ROUTE,
    /** 这一拍没派出去：回库交路被拒、闭塞或交接进行中，下一拍再试；滞留计时照走。 */
    BLOCKED,
    /** 车已不在待命池里（被叫车票接走或已离开）。 */
    NOT_WAITING
  }

  /**
   * 叫来的车在终点等完：立即派回库，不看 {@code reclaim.enabled} 与闲置门槛。
   *
   * <p>叫来的车不属于任何交路、也不会再有班可跑，所以不过交路闸；没有回库交路时与时刻表放行的车同一条路（原地销毁，有乘客、 折返事务进行中不碰），派不出去时进滞留计时。
   *
   * @param trainName 列车名
   * @param now 当前时刻
   */
  public CalledReturn returnCalledTrain(String trainName, Instant now) {
    Optional<LayoverRegistry.LayoverCandidate> candidate = layoverRegistry.get(trainName);
    if (candidate.isEmpty() || now == null) {
      return CalledReturn.NOT_WAITING;
    }
    ConfigManager.ReclaimSettings settings = configManager.current().reclaimSettings();
    ReturnOutcome outcome =
        assignReturnTicket(candidate.get(), plugin.getStorageManager().provider());
    switch (outcome) {
      case ASSIGNED -> {
        strandedSince.remove(trainName);
        return CalledReturn.ASSIGNED;
      }
      case NO_ROUTE -> {
        long idleSeconds = Math.max(0L, ChronoUnit.SECONDS.between(candidate.get().readyAt(), now));
        destroyWithoutReturnRoute(
            candidate.get(), now, idleSeconds, settings.strandedDestroySeconds(), false);
        return CalledReturn.NO_ROUTE;
      }
      default -> {
        destroyIfStranded(candidate.get(), now, settings.strandedDestroySeconds());
        return CalledReturn.BLOCKED;
      }
    }
  }

  /**
   * 车停在这里会不会挡住后车：正线折返点（挡同股道后车）或单股道车站（占住全站唯一股道）；都不是时为 {@code null}。
   *
   * @return 用于日志的位置类别
   */
  private String blockingTurnbackKind(
      org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId locationNodeId) {
    if (locationNodeId == null) {
      return null;
    }
    if (RouteTerminals.isMainlineTurnback(locationNodeId.value())) {
      return "正线折返点";
    }
    return singleTrackStation.test(locationNodeId) ? "单股道车站" : null;
  }

  /**
   * 挂起过久的折返交接只告警、不释放。
   *
   * <p>认领之后交接可能已经改动了占用，只能由同一张票重试完成；按时间释放会把做到一半的交接丢掉。可它又会让滞留销毁永远跳过这辆车， 所以至少要让人看见：{@code
   * RECLAIM_DISPATCH_ATTEMPT_STALE}，同一次交接只记一次。
   */
  private void reportStaleDispatchAttempts(
      List<LayoverRegistry.LayoverCandidate> candidates, Instant now) {
    Set<LayoverRegistry.DispatchAttempt> live = new HashSet<>();
    for (LayoverRegistry.LayoverCandidate candidate : candidates) {
      if (candidate == null || candidate.dispatchAttempt().isEmpty()) {
        continue;
      }
      LayoverRegistry.DispatchAttempt attempt = candidate.dispatchAttempt().get();
      live.add(attempt);
      long ageSeconds = ChronoUnit.SECONDS.between(attempt.claimedAt(), now);
      if (ageSeconds < STALE_DISPATCH_ATTEMPT_SECONDS || !staleAttemptsReported.add(attempt)) {
        continue;
      }
      String detail =
          "train="
              + candidate.trainName()
              + " ticket="
              + attempt.ticketId()
              + " target="
              + attempt.targetTrainName()
              + " ageSeconds="
              + ageSeconds;
      debugLogger.accept("RECLAIM_DISPATCH_ATTEMPT_STALE " + detail + " action=alert-only");
      HEALTH_LOGGER.warning("[FTA] 折返交接挂起过久，只告警不释放: " + detail);
    }
    staleAttemptsReported.retainAll(live);
  }

  /** 已经不在待命池里的车不再算滞留。 */
  private void pruneStranded(List<LayoverRegistry.LayoverCandidate> candidates) {
    if (strandedSince.isEmpty()) {
      return;
    }
    java.util.Set<String> present = new java.util.HashSet<>();
    for (LayoverRegistry.LayoverCandidate candidate : candidates) {
      if (candidate != null && candidate.trainName() != null) {
        present.add(candidate.trainName());
      }
    }
    strandedSince.keySet().retainAll(present);
  }

  /**
   * 该回收却派不出 RETURN 票的车，滞留超过阈值就销毁。
   *
   * <p>阈值为 0 关闭；折返事务与乘客两道闸见 {@link #destroyUnlessBusy}。
   */
  private void destroyIfStranded(
      LayoverRegistry.LayoverCandidate candidate, Instant now, long strandedDestroySeconds) {
    String trainName = candidate.trainName();
    Instant since = strandedSince.computeIfAbsent(trainName, ignored -> now);
    if (strandedDestroySeconds <= 0) {
      return;
    }
    long strandedSeconds = ChronoUnit.SECONDS.between(since, now);
    if (strandedSeconds < strandedDestroySeconds) {
      return;
    }
    destroyUnlessBusy(
        candidate,
        now,
        "RECLAIM_STRANDED",
        "reclaim-stranded",
        " strandedSeconds=" + strandedSeconds + " threshold=" + strandedDestroySeconds);
  }

  /**
   * 该回收、又确实没有一条 RETURN 交路从这个终点出发的车：原地销毁，不等滞留阈值。
   *
   * <p>没有回库线路就没有能等来的东西：滞留计时是给"有交路只是这一拍没派出去"（闭塞、被拒、交接进行中）留的。 车停在终点挡着后车——正线折返点挡同一股道，
   * 原地折返的车站上两三股道一被占满，同样在这里折返的线路全都进不来。车库发车按需生成，销毁与回库在车辆资源上等价。 有 RETURN
   * 交路只是这一拍没派出去时不走这里，照常进滞留计时；{@code reclaim.stranded-destroy-seconds} 为 0（回收不销毁车）时同样不销毁，只记滞留。
   *
   * <p>只在时刻表已经放行（正线折返点、单股道车站、无回库线路的车站上的立即回收）或车已被换下时走这里：
   * 闲置超时、车辆超限、方向供需这些泛用回收不知道这辆车还有没有班可跑（自由运行的线路、间隔发车）， 照常进滞留计时。
   *
   * @param mainline 停在正线折返点或单股道车站：日志沿用 {@code RECLAIM_MAINLINE}，其余终点记 {@code RECLAIM_NO_ROUTE}
   */
  private void destroyWithoutReturnRoute(
      LayoverRegistry.LayoverCandidate candidate,
      Instant now,
      long idleSeconds,
      long strandedDestroySeconds,
      boolean mainline) {
    if (strandedDestroySeconds <= 0) {
      strandedSince.computeIfAbsent(candidate.trainName(), ignored -> now);
      return;
    }
    destroyUnlessBusy(
        candidate,
        now,
        mainline ? "RECLAIM_MAINLINE" : "RECLAIM_NO_ROUTE",
        mainline ? "reclaim-mainline-turnback" : "reclaim-no-return-route",
        " node=" + candidate.locationNodeId().value() + " idleSeconds=" + idleSeconds);
  }

  /**
   * 这个终点确实没有一条 RETURN 交路出发（查遍全部运营商）。
   *
   * <p>结果按终点缓存 {@link #RETURN_ROUTE_RECHECK_SECONDS}。存储不可用时按"有"处理：只有确知没有，才走立即回收。
   */
  private boolean withoutReturnRoute(
      String terminalKey, Optional<StorageProvider> providerOpt, Instant now) {
    if (terminalKey == null || terminalKey.isBlank() || providerOpt.isEmpty()) {
      return false;
    }
    TerminalReturnRoutes known = returnRoutesByTerminal.get(terminalKey);
    if (known != null
        && ChronoUnit.SECONDS.between(known.checkedAt(), now) < RETURN_ROUTE_RECHECK_SECONDS) {
      return !known.present();
    }
    StorageProvider provider = providerOpt.get();
    boolean present;
    try {
      present =
          allReturnRoutes(provider, Optional.empty()).stream()
              .anyMatch(route -> startsAt(provider, route, terminalKey));
    } catch (StorageException ex) {
      // 查不了就当有：立即回收只给确知没有回库线路的终点，不能让一次存储故障把整轮回收掐断。
      debugLogger.accept("回收: 查询回库线路失败 terminal=" + terminalKey + " error=" + ex.getMessage());
      return false;
    }
    returnRoutesByTerminal.put(terminalKey, new TerminalReturnRoutes(present, now));
    return !present;
  }

  /**
   * 全部 RETURN 交路：先 {@code firstOperatorId} 的，再其它运营商的，去重。
   *
   * <p>直通车滞留在别人的终点时，能带它回家的只有别人的 RETURN；本运营商的排在前面，派票时先试。
   */
  private static List<Route> allReturnRoutes(
      StorageProvider provider, Optional<UUID> firstOperatorId) {
    List<Route> out = new ArrayList<>();
    Set<UUID> seen = new HashSet<>();
    firstOperatorId.ifPresent(
        operatorId -> {
          for (Route route : listReturnRoutes(provider, operatorId)) {
            if (seen.add(route.id())) {
              out.add(route);
            }
          }
        });
    for (Company company : provider.companies().listAll()) {
      if (company == null) {
        continue;
      }
      for (Operator operator : provider.operators().listByCompany(company.id())) {
        if (operator == null || firstOperatorId.filter(operator.id()::equals).isPresent()) {
          continue;
        }
        for (Route route : listReturnRoutes(provider, operator.id())) {
          if (seen.add(route.id())) {
            out.add(route);
          }
        }
      }
    }
    return out;
  }

  /** 通知回收派车成功；监听者异常不得影响回收扫描。 */
  private void notifyReclaimed(String trainName, UUID routeId) {
    try {
      reclaimListener.accept(trainName, routeId);
    } catch (RuntimeException ex) {
      debugLogger.accept("回收通知失败: train=" + trainName + " error=" + ex);
    }
  }

  /** 把偏好的回库线路排到最前面，其余顺序不变。 */
  private static List<Route> preferredFirst(List<Route> routes, Optional<UUID> preferred) {
    if (preferred == null || preferred.isEmpty()) {
      return routes;
    }
    List<Route> out = new ArrayList<>(routes.size());
    routes.stream().filter(route -> route.id().equals(preferred.get())).forEach(out::add);
    routes.stream().filter(route -> !route.id().equals(preferred.get())).forEach(out::add);
    return out;
  }

  /** 这条 RETURN 交路的首站是不是这个终点。 */
  private static boolean startsAt(StorageProvider provider, Route route, String terminalKey) {
    List<RouteStop> stops = provider.routeStops().listByRoute(route.id());
    return !stops.isEmpty() && returnRouteStartsAt(provider, stops.get(0), terminalKey);
  }

  /**
   * 一个终点的回库线路查询结果。
   *
   * @param present 有没有 RETURN 交路从这里出发
   * @param checkedAt 查询时刻
   */
  private record TerminalReturnRoutes(boolean present, Instant checkedAt) {}

  /**
   * 回收销毁的共同闸：已不在待命池里的不碰（这一拍里被派走了）；有进行中的折返事务不碰（那是票据分配器的事务，删车会留下悬空 attempt）；有乘客不碰。
   * 两项都按待命池的当前记录判，不用扫描开头的快照——同一拍里的派票可能刚认领了交接。 销毁走 {@code
   * RuntimeDispatchService#destroyTrainByName}，与健康监控的清除是同一条路径，占用释放仍等物理实体消失。
   *
   * @param event 日志前缀：{@code <event>_SKIP} / {@code <event>_DESTROY} / {@code
   *     <event>_DESTROY_FAILED}
   * @param reason 交给销毁回调的原因
   * @param detail 追加在销毁日志末尾的字段
   */
  private void destroyUnlessBusy(
      LayoverRegistry.LayoverCandidate candidate,
      Instant now,
      String event,
      String reason,
      String detail) {
    String trainName = candidate.trainName();
    Optional<LayoverRegistry.LayoverCandidate> current = layoverRegistry.get(trainName);
    if (current.isEmpty()) {
      debugLogger.accept(event + "_SKIP train=" + trainName + " reason=no-longer-waiting");
      return;
    }
    Optional<LayoverRegistry.DispatchAttempt> attempt = current.get().dispatchAttempt();
    if (attempt.isPresent()) {
      debugLogger.accept(
          event
              + "_SKIP train="
              + trainName
              + " reason=dispatch-attempt-in-progress attemptAgeSeconds="
              + ChronoUnit.SECONDS.between(attempt.get().claimedAt(), now));
      return;
    }
    if (passengerCheck.test(trainName)) {
      debugLogger.accept(event + "_SKIP train=" + trainName + " reason=has-passengers");
      return;
    }
    boolean destroyed = destroyer.test(trainName, reason);
    debugLogger.accept(
        event
            + (destroyed ? "_DESTROY" : "_DESTROY_FAILED")
            + " train="
            + trainName
            + " terminal="
            + candidate.terminalKey()
            + detail);
    if (destroyed) {
      strandedSince.remove(trainName);
    }
  }

  /**
   * 统计当前 pending 票据的“方向需求”。
   *
   * <p>方向 key 使用 terminal 语义（优先 station/depot 级），与 Layover 候选同口径匹配。
   */
  private Map<String, Integer> buildPendingDemandByDirection() {
    Map<String, Integer> demand = new HashMap<>();
    List<SpawnTicket> pendingTickets = ticketAssigner.snapshotPendingTickets();
    for (SpawnTicket ticket : pendingTickets) {
      if (ticket == null || ticket.service() == null) {
        continue;
      }
      String rawDirection = ticket.service().depotNodeId();
      if (rawDirection == null || rawDirection.isBlank()) {
        continue;
      }
      String directionKey = toDirectionKey(rawDirection);
      demand.merge(directionKey, 1, Integer::sum);
    }
    return demand;
  }

  /** 统计当前 Layover 候选在各方向上的供给数量。 */
  private static Map<String, Integer> buildLayoverSupplyByDirection(
      List<LayoverRegistry.LayoverCandidate> candidates) {
    Map<String, Integer> supply = new HashMap<>();
    if (candidates == null || candidates.isEmpty()) {
      return supply;
    }
    for (LayoverRegistry.LayoverCandidate candidate : candidates) {
      if (candidate == null
          || candidate.terminalKey() == null
          || candidate.terminalKey().isBlank()) {
        continue;
      }
      String directionKey = toDirectionKey(candidate.terminalKey());
      supply.merge(directionKey, 1, Integer::sum);
    }
    return supply;
  }

  /** 回库成功后同步扣减方向供给计数，避免单轮过回收。 */
  private static void decrementDirectionSupply(
      Map<String, Integer> supplyByDirection, String directionKey) {
    if (supplyByDirection == null || directionKey == null || directionKey.isBlank()) {
      return;
    }
    int current = supplyByDirection.getOrDefault(directionKey, 0);
    if (current <= 1) {
      supplyByDirection.remove(directionKey);
      return;
    }
    supplyByDirection.put(directionKey, current - 1);
  }

  /**
   * 归一化方向 key。
   *
   * <p>优先抽取 station/depot 级 key（忽略 track），保证同站不同站台共用一组供需计数。
   */
  private static String toDirectionKey(String terminalKey) {
    if (terminalKey == null || terminalKey.isBlank()) {
      return "";
    }
    String normalized = terminalKey.toLowerCase(Locale.ROOT).trim();
    return TerminalKeyResolver.extractStationKey(normalized).orElse(normalized);
  }

  /** 一次派 RETURN 票的结果。 */
  private enum ReturnOutcome {
    /** 已派出。 */
    ASSIGNED,
    /** 查过了：没有一条 RETURN 交路从这个终点出发。 */
    NO_ROUTE,
    /** 没派出去，但不能断定没有交路：存储不可用、运营商回溯不到、匹配的交路被拒或交接进行中。 */
    BLOCKED
  }

  /**
   * 为待回收列车分配 RETURN 票据。
   *
   * <p>该方法只负责票据分配，不直接销毁列车。纯预检拒绝且没有建立 dispatch attempt 时会继续尝试下一条匹配 RETURN route；一旦进入 handoff
   * 事务，则固定保留同一 route/ticket 供下一轮重试，避免同时产生两个折返事务。
   *
   * <p>只有查过全部 RETURN 交路、确实没有一条从这个终点出发时才返回 {@link ReturnOutcome#NO_ROUTE}；原地销毁只认这一种。
   */
  private ReturnOutcome assignReturnTicket(
      LayoverRegistry.LayoverCandidate candidate, Optional<StorageProvider> providerOpt) {
    if (providerOpt.isEmpty()) {
      debugLogger.accept("回收失败: StorageProvider 不可用 train=" + candidate.trainName());
      return ReturnOutcome.BLOCKED;
    }
    StorageProvider provider = providerOpt.get();

    Optional<Operator> operatorOpt = resolveCandidateOperator(provider, candidate);
    if (operatorOpt.isEmpty()) {
      return ReturnOutcome.BLOCKED;
    }
    UUID operatorId = operatorOpt.get().id();
    String opCode = operatorOpt.get().code();

    List<Route> allReturnRoutes =
        preferredFirst(
            allReturnRoutes(provider, Optional.of(operatorId)),
            preferredReturnRoute.apply(candidate.trainName()));

    if (allReturnRoutes.isEmpty()) {
      debugLogger.accept(
          "回收失败: Operator " + opCode + " 无 RETURN 线路 train=" + candidate.trainName());
      return ReturnOutcome.NO_ROUTE;
    }

    boolean matched = false;
    for (Route route : allReturnRoutes) {
      if (startsAt(provider, route, candidate.terminalKey())) {
        matched = true;
        Optional<ServiceTicket> ticketOpt = stableReturnTicket(candidate, route.id());
        if (ticketOpt.isEmpty()) {
          debugLogger.accept("回收跳过: 候选已由其他 dispatch 事务认领 train=" + candidate.trainName());
          continue;
        }
        ServiceTicket ticket = ticketOpt.get();

        debugLogger.accept(
            "尝试回收: 分配 RETURN ticket route=" + route.code() + " train=" + candidate.trainName());

        boolean success = ticketAssigner.forceAssign(provider, candidate.trainName(), ticket);
        if (success) {
          stableReturnTickets.remove(ticket.ticketId());
          notifyReclaimed(candidate.trainName(), route.id());
          debugLogger.accept("回收成功: 已分配 RETURN ticket train=" + candidate.trainName());
          return ReturnOutcome.ASSIGNED;
        }
        debugLogger.accept("回收失败: TicketAssigner 拒绝分配 train=" + candidate.trainName());
        if (layoverRegistry.findDispatchAttemptOwner(ticket.ticketId()).isPresent()) {
          return ReturnOutcome.BLOCKED;
        }
        stableReturnTickets.remove(ticket.ticketId());
      }
    }
    if (matched) {
      return ReturnOutcome.BLOCKED;
    }
    debugLogger.accept(
        "回收失败: 未找到匹配 terminal="
            + candidate.terminalKey()
            + " 的 RETURN 线路 train="
            + candidate.trainName());
    return ReturnOutcome.NO_ROUTE;
  }

  private static List<Route> listReturnRoutes(StorageProvider provider, UUID operatorId) {
    return provider.lines().listByOperator(operatorId).stream()
        .flatMap(line -> provider.routes().listByLine(line.id()).stream())
        .filter(r -> r.operationType() == RouteOperationType.RETURN)
        .toList();
  }

  /**
   * RETURN 线路的首站是不是列车所在的终点。
   *
   * <p>三种写法都要认：按站点主数据引用（比站 code）、直接写图节点 id（直通到外方终点时只能这么写）、DYNAMIC 规范（按站台组比）。 只认第一种的话，凡是首站在别的运营商地盘上的
   * RETURN 永远匹配不上。
   *
   * <p>站 code 只和终点所在车站/车库的名字段比：区间路径点（例如 {@code OFL:MLU:2:004}）里的起讫站段不表示车停在那个站。
   */
  private static boolean returnRouteStartsAt(
      StorageProvider provider, RouteStop first, String terminalKey) {
    if (first == null || terminalKey == null || terminalKey.isBlank()) {
      return false;
    }
    if (first.stationId().isPresent()) {
      Optional<String> stationName =
          TerminalKeyResolver.extractStationKey(terminalKey)
              .map(key -> key.substring(key.lastIndexOf(':') + 1));
      return stationName.isPresent()
          && provider
              .stations()
              .findById(first.stationId().get())
              .filter(station -> station.code().equalsIgnoreCase(stationName.get()))
              .isPresent();
    }
    if (first.waypointNodeId().isPresent()) {
      return TerminalKeyResolver.matches(terminalKey, first.waypointNodeId().get());
    }
    return org.fetarute
        .fetaruteTCAddon
        .dispatcher
        .route
        .DynamicStopMatcher
        .parseDynamicSpec(first)
        .map(spec -> spec.operatorCode() + ":" + spec.nodeType() + ":" + spec.nodeName())
        .map(stationKey -> TerminalKeyResolver.matches(terminalKey, stationKey + ":1"))
        .orElse(false);
  }

  /**
   * 解析候选列车的管理运营商（决定先搜哪家的 RETURN 交路）。
   *
   * <p>管理归属是交路自身的运营商：沿本次列车的 {@code FTA_ROUTE_ID -> Line -> Operator} 精确回溯，不看 {@code
   * FTA_OPERATOR_CODE}——直通运转（CHANGE）会把它改写成对乘客显示的线路，跨运营商换线后必然与交路不一致， 但交路、交路组仍归原运营商管理。
   *
   * <ul>
   *   <li>有交路 ID 却回溯不到（交路已删除、数据不一致）：拒绝回收。此时运营商标签可能是换线后的外方运营商， 按它找会把搜索顺序颠倒；回收失败后由滞留销毁兜底。
   *   <li>没有（或无法解析的）交路 ID 的旧数据才按运营商标签找，且 Operator code 只在公司内唯一，仅在全库恰好一个同 code 运营商时允许回退。
   * </ul>
   */
  private Optional<Operator> resolveCandidateOperator(
      StorageProvider provider, LayoverRegistry.LayoverCandidate candidate) {
    String operatorCode = candidate.tags().get(RouteProgressRegistry.TAG_OPERATOR_CODE);
    Optional<UUID> routeId = parseUuidTag(candidate.tags(), RouteProgressRegistry.TAG_ROUTE_ID);
    if (routeId.isPresent()) {
      Optional<Operator> byRoute =
          provider
              .routes()
              .findById(routeId.get())
              .flatMap(route -> provider.lines().findById(route.lineId()))
              .flatMap(line -> provider.operators().findById(line.operatorId()));
      if (byRoute.isPresent()) {
        if (operatorCode != null && !byRoute.get().code().equalsIgnoreCase(operatorCode)) {
          debugLogger.accept(
              "回收: 按交路归属运营商 "
                  + byRoute.get().code()
                  + "（FTA_OPERATOR_CODE="
                  + operatorCode
                  + " 为直通换线后的显示线路）train="
                  + candidate.trainName()
                  + " routeId="
                  + routeId.get());
        }
        return byRoute;
      }
      debugLogger.accept(
          "回收失败: FTA_ROUTE_ID 回溯不到运营商 train="
              + candidate.trainName()
              + " routeId="
              + routeId.get());
      return Optional.empty();
    }

    if (operatorCode == null) {
      debugLogger.accept(
          "回收失败: 缺失 FTA_ROUTE_ID 与 FTA_OPERATOR_CODE train=" + candidate.trainName());
      return Optional.empty();
    }
    List<Operator> matches =
        provider.companies().listAll().stream()
            .filter(Objects::nonNull)
            .map(company -> provider.operators().findByCompanyAndCode(company.id(), operatorCode))
            .flatMap(Optional::stream)
            .toList();
    if (matches.size() == 1) {
      return Optional.of(matches.get(0));
    }
    if (matches.size() > 1) {
      debugLogger.accept(
          "回收失败: Operator code 跨公司歧义 train="
              + candidate.trainName()
              + " operator="
              + operatorCode
              + " matches="
              + matches.size());
    } else {
      debugLogger.accept("回收失败: 找不到 Operator " + operatorCode + " train=" + candidate.trainName());
    }
    return Optional.empty();
  }

  /**
   * 返回当前回库事务的稳定票据。
   *
   * <p>Layover 的终端、位置、readyAt 与列车名在 handoff/周期刷新中都可能改变，不能作为事务身份。已有 dispatch attempt 只按其不可变 ticketId
   * 找回本管理器保存的 RETURN 票据，并校验 route；其他折返事务的 attempt 不得被回收流程接管。
   */
  private Optional<ServiceTicket> stableReturnTicket(
      LayoverRegistry.LayoverCandidate candidate, UUID routeId) {
    Optional<LayoverRegistry.DispatchAttempt> attempt = candidate.dispatchAttempt();
    if (attempt.isPresent()) {
      ServiceTicket current = stableReturnTickets.get(attempt.get().ticketId());
      return current != null && routeId.equals(current.routeId())
          ? Optional.of(current)
          : Optional.empty();
    }
    ServiceTicket created =
        new ServiceTicket(
            UUID.randomUUID().toString(),
            Instant.now(),
            routeId,
            candidate.terminalKey(),
            -10,
            ServiceTicket.TicketMode.RETURN);
    stableReturnTickets.put(created.ticketId(), created);
    return Optional.of(created);
  }

  private static int countActiveGroupsFromStore() {
    return countActiveGroups(MinecartGroupStore.getGroups());
  }

  /**
   * 统计当前已加载且有效的 TrainCarts group。
   *
   * <p>{@code TrainPropertiesStore#getAll()} 包含持久化的离线属性，不代表 Minecraft 世界中的活跃实体；回收压力只能使用真实 group。
   */
  static int countActiveGroups(Collection<MinecartGroup> groups) {
    if (groups == null || groups.isEmpty()) {
      return 0;
    }
    int active = 0;
    for (MinecartGroup group : groups) {
      if (group != null && group.isValid()) {
        active++;
      }
    }
    return active;
  }

  /** 仅保留仍由 LayoverRegistry 中 active attempt 引用的稳定回库票据。 */
  private void pruneStableReturnTickets(List<LayoverRegistry.LayoverCandidate> candidates) {
    Set<String> activeTicketIds =
        candidates == null
            ? Set.of()
            : candidates.stream()
                .filter(Objects::nonNull)
                .map(LayoverRegistry.LayoverCandidate::dispatchAttempt)
                .flatMap(Optional::stream)
                .map(LayoverRegistry.DispatchAttempt::ticketId)
                .collect(Collectors.toSet());
    stableReturnTickets.keySet().removeIf(ticketId -> !activeTicketIds.contains(ticketId));
  }

  private static int readPositiveIntTag(Map<String, String> tags, String key) {
    if (tags == null || tags.isEmpty() || key == null || key.isBlank()) {
      return 0;
    }
    for (Map.Entry<String, String> entry : tags.entrySet()) {
      if (entry == null || entry.getKey() == null) {
        continue;
      }
      if (!entry.getKey().equalsIgnoreCase(key)) {
        continue;
      }
      String raw = entry.getValue();
      if (raw == null || raw.isBlank()) {
        return 0;
      }
      try {
        int parsed = Integer.parseInt(raw.trim());
        return Math.max(0, parsed);
      } catch (NumberFormatException ignored) {
        return 0;
      }
    }
    return 0;
  }

  private static Optional<UUID> parseUuidTag(Map<String, String> tags, String key) {
    if (tags == null || tags.isEmpty() || key == null || key.isBlank()) {
      return Optional.empty();
    }
    for (Map.Entry<String, String> entry : tags.entrySet()) {
      if (entry == null
          || entry.getKey() == null
          || !entry.getKey().equalsIgnoreCase(key)
          || entry.getValue() == null
          || entry.getValue().isBlank()) {
        continue;
      }
      try {
        return Optional.of(UUID.fromString(entry.getValue().trim()));
      } catch (IllegalArgumentException ignored) {
        return Optional.empty();
      }
    }
    return Optional.empty();
  }
}
