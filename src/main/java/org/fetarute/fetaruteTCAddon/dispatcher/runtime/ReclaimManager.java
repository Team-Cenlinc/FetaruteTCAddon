package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.properties.TrainPropertiesStore;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.TicketAssigner;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/**
 * 车辆回收管理器 (ReclaimPolicy)。
 *
 * <p>定期检查 LayoverRegistry 中的列车，回收长期闲置或超出数量限制的车辆。 回收方式为：分配 RETURN 类型的 Ticket，使其驶向 DSTY 销毁。
 */
public class ReclaimManager {

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

  void performReclaimCheck() {
    ConfigManager.ReclaimSettings settings = configManager.current().reclaimSettings();
    if (!settings.enabled()) {
      return;
    }

    int activeTrains = activeTrainCountSupplier.getAsInt();
    int maxTrains = settings.maxActiveTrains();
    boolean pressure = activeTrains > maxTrains;

    Instant now = Instant.now();
    long maxIdleSec = settings.maxIdleSeconds();

    List<LayoverRegistry.LayoverCandidate> candidates = layoverRegistry.snapshot();

    // 候选排序：优先回收闲置时间更久的列车
    List<LayoverRegistry.LayoverCandidate> sorted =
        candidates.stream()
            .sorted((c1, c2) -> c1.readyAt().compareTo(c2.readyAt())) // readyAt 更早者优先
            .collect(Collectors.toList());

    for (LayoverRegistry.LayoverCandidate candidate : sorted) {
      boolean shouldReclaim = false;
      long idleSec = ChronoUnit.SECONDS.between(candidate.readyAt(), now);

      if (idleSec > maxIdleSec) {
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
      }

      if (shouldReclaim) {
        if (assignReturnTicket(candidate)) {
          if (pressure) {
            pressure = false; // 本轮执行一次回收后，立即解除压力模式
          }
        }
      }
    }
  }

  private boolean assignReturnTicket(LayoverRegistry.LayoverCandidate candidate) {
    Optional<StorageProvider> providerOpt = plugin.getStorageManager().provider();
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

        if (ticketAssigner
            instanceof
            org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SimpleTicketAssigner
            simple) {
          boolean success = simple.forceAssign(candidate.trainName(), ticket);
          if (success) {
            debugLogger.accept("回收成功: 已分配 RETURN ticket train=" + candidate.trainName());
          } else {
            debugLogger.accept("回收失败: TicketAssigner 拒绝分配 train=" + candidate.trainName());
          }
          return success;
        }
      }
    }
    debugLogger.accept(
        "回收失败: 未找到匹配 terminal="
            + candidate.terminalKey()
            + " 的 RETURN 线路 train="
            + candidate.trainName());
    return false;
  }
}
