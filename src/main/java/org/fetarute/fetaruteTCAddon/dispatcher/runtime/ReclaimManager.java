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
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnTicket;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.TicketAssigner;
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
   * 折返交接挂起多久记告警（秒）。
   *
   * <p>没有实测分布，只是一个"明显不正常"的量级：交接由票据分配器在每个发车 tick（{@code spawn.tick-interval-ticks}， 默认 100 tick = 5
   * 秒）重试一次，成功就注销候选、授权被拒就释放认领，挂满两分钟等于连续二十多次既没成功也没被拒。 它只决定告警，不触发任何动作；回收扫描每 {@code
   * reclaim.check-interval-seconds} 一次，所以实际在 120 秒到 120 秒 + 一个扫描间隔之间报出。
   */
  static final long STALE_DISPATCH_ATTEMPT_SECONDS = 120L;

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
   * <p>典型是直通车滞留在别的运营商的终点，或回库票过期后再没有线路能从那里出发。健康监控明确把待命车排除在清除之外， 所以没有这条兜底的话这辆车会永远留在终点占着站台。
   */
  private final Map<String, Instant> strandedSince = new HashMap<>();

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

  /** 装上回库闸；{@code null} 恢复恒放行。 */
  public void setReturnGate(java.util.function.Predicate<String> gate) {
    this.returnGate = gate == null ? trainName -> true : gate;
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
    Map<String, Integer> layoverSupplyByDirection = buildLayoverSupplyByDirection(candidates);

    // 候选排序：优先回收闲置时间更久的列车
    List<LayoverRegistry.LayoverCandidate> sorted =
        candidates.stream()
            .sorted((c1, c2) -> c1.readyAt().compareTo(c2.readyAt())) // readyAt 更早者优先
            .collect(Collectors.toList());

    for (LayoverRegistry.LayoverCandidate candidate : sorted) {
      boolean shouldReclaim = false;
      long idleSec = ChronoUnit.SECONDS.between(candidate.readyAt(), now);
      String directionKey = toDirectionKey(candidate.terminalKey());
      int operationTrips = readPositiveIntTag(candidate.tags(), TAG_OPERATION_TRIPS);
      int maxOperationTrips = readPositiveIntTag(candidate.tags(), TAG_MAX_OPERATION_TRIPS);

      if (maxOperationTrips > 0 && operationTrips >= maxOperationTrips) {
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

      if (shouldReclaim && !returnGate.test(candidate.trainName())) {
        // 交路还有班次：这不是派不出回库票，不能记成滞留。
        debugLogger.accept("回收跳过: 交路还有班次要跑 train=" + candidate.trainName());
        continue;
      }
      if (shouldReclaim) {
        if (assignReturnTicket(candidate, providerOpt)) {
          strandedSince.remove(candidate.trainName());
          decrementDirectionSupply(layoverSupplyByDirection, directionKey);
          if (pressure) {
            pressure = false; // 本轮执行一次回收后，立即解除压力模式
          }
        } else {
          destroyIfStranded(candidate, now, settings.strandedDestroySeconds());
        }
      }
    }
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
   * <p>三道闸：阈值为 0 关闭；有进行中的折返事务不碰（那是票据分配器的事务，删车会留下悬空 attempt）；有乘客不碰。 销毁走 {@code
   * RuntimeDispatchService#destroyTrainByName}，与健康监控的清除是同一条路径，占用释放仍等物理实体消失。
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
    if (candidate.dispatchAttempt().isPresent()) {
      debugLogger.accept(
          "RECLAIM_STRANDED_SKIP train="
              + trainName
              + " reason=dispatch-attempt-in-progress attemptAgeSeconds="
              + ChronoUnit.SECONDS.between(candidate.dispatchAttempt().get().claimedAt(), now));
      return;
    }
    if (passengerCheck.test(trainName)) {
      debugLogger.accept("RECLAIM_STRANDED_SKIP train=" + trainName + " reason=has-passengers");
      return;
    }
    boolean destroyed = destroyer.test(trainName, "reclaim-stranded");
    debugLogger.accept(
        (destroyed ? "RECLAIM_STRANDED_DESTROY" : "RECLAIM_STRANDED_DESTROY_FAILED")
            + " train="
            + trainName
            + " terminal="
            + candidate.terminalKey()
            + " strandedSeconds="
            + strandedSeconds
            + " threshold="
            + strandedDestroySeconds);
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

  /**
   * 为待回收列车分配 RETURN 票据。
   *
   * <p>该方法只负责票据分配，不直接销毁列车。纯预检拒绝且没有建立 dispatch attempt 时会继续尝试下一条匹配 RETURN route；一旦进入 handoff
   * 事务，则固定保留同一 route/ticket 供下一轮重试，避免同时产生两个折返事务。
   */
  private boolean assignReturnTicket(
      LayoverRegistry.LayoverCandidate candidate, Optional<StorageProvider> providerOpt) {
    if (providerOpt.isEmpty()) {
      debugLogger.accept("回收失败: StorageProvider 不可用 train=" + candidate.trainName());
      return false;
    }
    StorageProvider provider = providerOpt.get();

    Optional<Operator> operatorOpt = resolveCandidateOperator(provider, candidate);
    if (operatorOpt.isEmpty()) {
      return false;
    }
    UUID operatorId = operatorOpt.get().id();
    String opCode = operatorOpt.get().code();

    // 先本运营商，再其它运营商：直通车滞留在别人的终点时，能带它回家的只有别人的 RETURN。
    List<Route> allReturnRoutes = new ArrayList<>(listReturnRoutes(provider, operatorId));
    java.util.Set<UUID> seen = new java.util.HashSet<>();
    allReturnRoutes.forEach(route -> seen.add(route.id()));
    for (Company company : provider.companies().listAll()) {
      if (company == null) {
        continue;
      }
      for (Operator other : provider.operators().listByCompany(company.id())) {
        if (other == null || other.id().equals(operatorId)) {
          continue;
        }
        for (Route route : listReturnRoutes(provider, other.id())) {
          if (seen.add(route.id())) {
            allReturnRoutes.add(route);
          }
        }
      }
    }

    if (allReturnRoutes.isEmpty()) {
      debugLogger.accept(
          "回收失败: Operator " + opCode + " 无 RETURN 线路 train=" + candidate.trainName());
      return false;
    }

    for (Route route : allReturnRoutes) {
      List<RouteStop> stops = provider.routeStops().listByRoute(route.id());
      if (stops.isEmpty()) {
        continue;
      }
      RouteStop first = stops.get(0);
      boolean match = returnRouteStartsAt(provider, first, candidate.terminalKey());

      if (match) {
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
          debugLogger.accept("回收成功: 已分配 RETURN ticket train=" + candidate.trainName());
          return true;
        }
        debugLogger.accept("回收失败: TicketAssigner 拒绝分配 train=" + candidate.trainName());
        if (layoverRegistry.findDispatchAttemptOwner(ticket.ticketId()).isPresent()) {
          return false;
        }
        stableReturnTickets.remove(ticket.ticketId());
      }
    }
    debugLogger.accept(
        "回收失败: 未找到匹配 terminal="
            + candidate.terminalKey()
            + " 的 RETURN 线路 train="
            + candidate.trainName());
    return false;
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
   */
  private static boolean returnRouteStartsAt(
      StorageProvider provider, RouteStop first, String terminalKey) {
    if (first == null || terminalKey == null || terminalKey.isBlank()) {
      return false;
    }
    if (first.stationId().isPresent()) {
      var stationOpt = provider.stations().findById(first.stationId().get());
      if (stationOpt.isPresent()) {
        String code = stationOpt.get().code();
        for (String part : terminalKey.split(":")) {
          if (part.equalsIgnoreCase(code)) {
            return true;
          }
        }
      }
      return false;
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
