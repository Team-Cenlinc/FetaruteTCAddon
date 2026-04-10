package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.properties.TrainPropertiesStore;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
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

  private final FetaruteTCAddon plugin;
  private final LayoverRegistry layoverRegistry;
  private final TicketAssigner ticketAssigner;
  private final ConfigManager configManager;
  private final Consumer<String> debugLogger;
  private final java.util.function.IntSupplier activeTrainCountSupplier;
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
        () -> TrainPropertiesStore.getAll().size());
  }

  public ReclaimManager(
      FetaruteTCAddon plugin,
      LayoverRegistry layoverRegistry,
      TicketAssigner ticketAssigner,
      ConfigManager configManager,
      Consumer<String> debugLogger,
      java.util.function.IntSupplier activeTrainCountSupplier) {
    this.plugin = Objects.requireNonNull(plugin);
    this.layoverRegistry = Objects.requireNonNull(layoverRegistry);
    this.ticketAssigner = Objects.requireNonNull(ticketAssigner);
    this.configManager = Objects.requireNonNull(configManager);
    this.debugLogger = debugLogger != null ? debugLogger : msg -> {};
    this.activeTrainCountSupplier =
        activeTrainCountSupplier != null ? activeTrainCountSupplier : () -> 0;
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

    Instant now = Instant.now();
    long maxIdleSec = settings.maxIdleSeconds();

    List<LayoverRegistry.LayoverCandidate> candidates = layoverRegistry.snapshot();
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

      if (shouldReclaim) {
        if (assignReturnTicket(candidate, providerOpt)) {
          decrementDirectionSupply(layoverSupplyByDirection, directionKey);
          if (pressure) {
            pressure = false; // 本轮执行一次回收后，立即解除压力模式
          }
        }
      }
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
   * <p>该方法只负责票据分配，不直接销毁列车。若找不到匹配的 RETURN 线路或 TicketAssigner 拒绝 分配，则返回 false，交由下一轮扫描重试。
   */
  private boolean assignReturnTicket(
      LayoverRegistry.LayoverCandidate candidate, Optional<StorageProvider> providerOpt) {
    if (providerOpt.isEmpty()) {
      debugLogger.accept("回收失败: StorageProvider 不可用 train=" + candidate.trainName());
      return false;
    }
    StorageProvider provider = providerOpt.get();

    String opCode = candidate.tags().get("FTA_OPERATOR_CODE");
    if (opCode == null) {
      debugLogger.accept("回收失败: 缺失 FTA_OPERATOR_CODE train=" + candidate.trainName());
      return false;
    }

    Optional<Operator> operatorOpt = Optional.empty();
    for (Company company : provider.companies().listAll()) {
      operatorOpt = provider.operators().findByCompanyAndCode(company.id(), opCode);
      if (operatorOpt.isPresent()) {
        break;
      }
    }

    if (operatorOpt.isEmpty()) {
      debugLogger.accept("回收失败: 找不到 Operator " + opCode + " train=" + candidate.trainName());
      return false;
    }
    UUID operatorId = operatorOpt.get().id();

    List<Route> allReturnRoutes =
        provider.lines().listByOperator(operatorId).stream()
            .flatMap(line -> provider.routes().listByLine(line.id()).stream())
            .filter(r -> r.operationType() == RouteOperationType.RETURN)
            .toList();

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

      boolean match = false;
      if (first.stationId().isPresent()) {
        var stationOpt = provider.stations().findById(first.stationId().get());
        if (stationOpt.isPresent()) {
          String code = stationOpt.get().code();
          String terminalKey = candidate.terminalKey();
          if (terminalKey != null) {
            for (String part : terminalKey.split(":")) {
              if (part.equalsIgnoreCase(code)) {
                match = true;
                break;
              }
            }
          }
        }
      }

      if (match) {
        ServiceTicket ticket =
            new ServiceTicket(
                UUID.randomUUID().toString(),
                Instant.now(),
                route.id(),
                candidate.terminalKey(),
                -10,
                ServiceTicket.TicketMode.RETURN);

        debugLogger.accept(
            "尝试回收: 分配 RETURN ticket route=" + route.code() + " train=" + candidate.trainName());

        boolean success = ticketAssigner.forceAssign(candidate.trainName(), ticket);
        if (success) {
          debugLogger.accept("回收成功: 已分配 RETURN ticket train=" + candidate.trainName());
        } else {
          debugLogger.accept("回收失败: TicketAssigner 拒绝分配 train=" + candidate.trainName());
        }
        return success;
      }
    }
    debugLogger.accept(
        "回收失败: 未找到匹配 terminal="
            + candidate.terminalKey()
            + " 的 RETURN 线路 train="
            + candidate.trainName());
    return false;
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
}
