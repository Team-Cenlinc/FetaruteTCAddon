package org.fetarute.fetaruteTCAddon.dispatcher.schedule.planner;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.model.ScheduleWindow;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.model.ScheduledStop;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.model.ServiceTrip;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.model.TripSource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.LineSpawnMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnDepot;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnPlan;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.StorageSpawnManager;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/**
 * 轻量排班计划生成器。
 *
 * <p>第一阶段不引入日历、首末班和例外日，而是复用现有 SpawnManager 能表达的 headway 配置： line baseline、route spawn_weight、route
 * group baseline/maxTrips 和 depot pool。输出是只读 {@link ServiceTrip} 列表，可用于人工 preview、CSV/JSON 导出，以及后续
 * runtime/PIDS/ETA/HUD 的机器快照。
 */
public final class SchedulePlanner {

  private static final Duration PLAN_REFRESH_INTERVAL = Duration.ofDays(365);
  private static final int MAX_GENERATE_PER_TICK = 1000;
  private static final int MAX_POLL_PER_TICK = 1000;

  private final Consumer<String> debugLogger;

  public SchedulePlanner() {
    this(message -> {});
  }

  public SchedulePlanner(Consumer<String> debugLogger) {
    this.debugLogger = debugLogger == null ? message -> {} : debugLogger;
  }

  /**
   * 生成指定时间窗内的计划车次。
   *
   * <p>窗口使用半开区间 {@code [windowStart, windowEnd)}。计划中的时间均为 Java {@link Instant}，不在此处做游戏 tick 换算。
   *
   * @param provider 存储提供者
   * @param windowStart 窗口开始
   * @param windowEnd 窗口结束
   * @return 计划窗口；存储或窗口无效时返回空窗口
   */
  public ScheduleWindow plan(StorageProvider provider, Instant windowStart, Instant windowEnd) {
    if (provider == null || windowStart == null || windowEnd == null) {
      Instant start = windowStart == null ? Instant.EPOCH : windowStart;
      Instant end = windowEnd == null || windowEnd.isBefore(start) ? start : windowEnd;
      return ScheduleWindow.empty(start, end);
    }
    if (!windowEnd.isAfter(windowStart)) {
      return ScheduleWindow.empty(windowStart, windowEnd);
    }

    SpawnPlan spawnPlan = buildSpawnPlan(provider, windowStart);
    if (spawnPlan.services().isEmpty()) {
      return new ScheduleWindow(Instant.now(), windowStart, windowEnd, List.of());
    }

    List<ServiceTrip> trips = new ArrayList<>();
    for (SpawnService service : spawnPlan.services()) {
      trips.addAll(buildTripsForService(provider, service, windowStart, windowEnd));
    }
    return new ScheduleWindow(Instant.now(), windowStart, windowEnd, trips);
  }

  private SpawnPlan buildSpawnPlan(StorageProvider provider, Instant now) {
    StorageSpawnManager.SpawnManagerSettings settings =
        new StorageSpawnManager.SpawnManagerSettings(
            PLAN_REFRESH_INTERVAL,
            Duration.ZERO,
            MAX_GENERATE_PER_TICK,
            MAX_GENERATE_PER_TICK,
            MAX_POLL_PER_TICK);
    SpawnManager manager = new StorageSpawnManager(settings, debugLogger);
    manager.pollDueTickets(provider, now);
    return manager.snapshotPlan();
  }

  private List<ServiceTrip> buildTripsForService(
      StorageProvider provider, SpawnService service, Instant windowStart, Instant windowEnd) {
    if (service == null || service.baseHeadway().isZero() || service.baseHeadway().isNegative()) {
      return List.of();
    }
    Optional<Route> routeOpt = provider.routes().findById(service.routeId());
    Optional<Line> lineOpt = provider.lines().findById(service.lineId());
    Optional<Operator> operatorOpt = provider.operators().findById(service.operatorId());
    Optional<Company> companyOpt = provider.companies().findById(service.companyId());
    if (routeOpt.isEmpty() || lineOpt.isEmpty() || operatorOpt.isEmpty() || companyOpt.isEmpty()) {
      return List.of();
    }

    Route route = routeOpt.get();
    Line line = lineOpt.get();
    Operator operator = operatorOpt.get();
    Company company = companyOpt.get();
    List<RouteStop> stops =
        provider.routeStops().listByRoute(route.id()).stream()
            .filter(stop -> stop != null)
            .sorted(Comparator.comparingInt(RouteStop::sequence))
            .toList();
    if (stops.isEmpty()) {
      return List.of();
    }

    List<SpawnDepot> depotCandidates = resolveDepotCandidates(line, service);
    Optional<Integer> maxOperationTrips = resolveMaxOperationTrips(line, route);
    Optional<String> direction = resolveDirection(route.metadata());
    int priority = resolvePriority(route.metadata()).orElse(operator.priority());

    List<ServiceTrip> out = new ArrayList<>();
    Instant departure = windowStart;
    while (departure.isBefore(windowEnd)) {
      List<ScheduledStop> plannedStops = buildScheduledStops(provider, route, stops, departure);
      out.add(
          new ServiceTrip(
              stableTripId(service, departure, TripSource.SCHEDULED),
              TripSource.SCHEDULED,
              company.id(),
              company.code(),
              operator.id(),
              operator.code(),
              line.id(),
              line.code(),
              route.id(),
              route.code(),
              direction,
              departure,
              plannedStops,
              priority,
              depotCandidates,
              maxOperationTrips,
              Optional.empty()));
      departure = departure.plus(service.baseHeadway());
    }
    return List.copyOf(out);
  }

  private List<ScheduledStop> buildScheduledStops(
      StorageProvider provider, Route route, List<RouteStop> stops, Instant plannedDeparture) {
    int segmentSeconds = estimateSegmentSeconds(route, stops.size());
    List<ScheduledStop> out = new ArrayList<>();
    Instant cursor = plannedDeparture;
    for (int i = 0; i < stops.size(); i++) {
      RouteStop stop = stops.get(i);
      Instant arrival = i == 0 ? plannedDeparture : cursor.plusSeconds(segmentSeconds);
      Optional<Duration> dwell = stop.dwellSeconds().map(Duration::ofSeconds);
      Instant departure = dwell.map(arrival::plus).orElse(arrival);
      Optional<String> nodeId = resolveStopNodeId(provider, stop);
      Optional<String> stationCode = resolveStationCode(provider, stop, nodeId);
      out.add(
          new ScheduledStop(
              stop.sequence(),
              stationCode,
              nodeId,
              Optional.of(arrival),
              Optional.of(departure),
              dwell,
              stop.notes()));
      cursor = departure;
    }
    return List.copyOf(out);
  }

  private static int estimateSegmentSeconds(Route route, int stopCount) {
    int edges = Math.max(1, stopCount - 1);
    Optional<Integer> runtime = route == null ? Optional.empty() : route.runtimeSeconds();
    if (runtime.isPresent() && runtime.get() != null && runtime.get() > 0) {
      return Math.max(1, runtime.get() / edges);
    }
    return 60;
  }

  private Optional<String> resolveStopNodeId(StorageProvider provider, RouteStop stop) {
    if (stop == null) {
      return Optional.empty();
    }
    if (stop.waypointNodeId().isPresent()) {
      return stop.waypointNodeId();
    }
    Optional<String> dynamic =
        DynamicStopMatcher.parseDynamicSpec(stop)
            .map(DynamicStopMatcher.DynamicSpec::toPlaceholderNodeId);
    if (dynamic.isPresent()) {
      return dynamic;
    }
    return stop.stationId()
        .flatMap(id -> provider.stations().findById(id))
        .flatMap(Station::graphNodeId);
  }

  private Optional<String> resolveStationCode(
      StorageProvider provider, RouteStop stop, Optional<String> nodeId) {
    if (stop != null && stop.stationId().isPresent()) {
      Optional<String> fromStation =
          provider.stations().findById(stop.stationId().get()).map(Station::code);
      if (fromStation.isPresent()) {
        return fromStation;
      }
    }
    return nodeId.flatMap(SchedulePlanner::parseStationCodeFromNodeId);
  }

  private static Optional<String> parseStationCodeFromNodeId(String nodeId) {
    if (nodeId == null || nodeId.isBlank()) {
      return Optional.empty();
    }
    String normalized = nodeId.trim();
    if (normalized.toUpperCase(Locale.ROOT).startsWith("DYNAMIC:")) {
      normalized = normalized.substring("DYNAMIC:".length());
    }
    String[] parts = normalized.split(":");
    if (parts.length < 3) {
      return Optional.empty();
    }
    String code = parts[2].trim();
    return code.isBlank() ? Optional.empty() : Optional.of(code);
  }

  private static List<SpawnDepot> resolveDepotCandidates(Line line, SpawnService service) {
    List<SpawnDepot> lineDepots = LineSpawnMetadata.parseDepots(line.metadata());
    if (!lineDepots.isEmpty()) {
      return lineDepots;
    }
    return List.of(new SpawnDepot(service.depotNodeId(), 1));
  }

  private static Optional<Integer> resolveMaxOperationTrips(Line line, Route route) {
    Optional<String> group = readString(route.metadata(), "spawn_group");
    if (group.isPresent()) {
      Optional<Integer> fromLine =
          LineSpawnMetadata.parseGroupMaxOperationTrips(line.metadata(), group.get());
      if (fromLine.isPresent()) {
        return fromLine;
      }
    }
    return readPositiveInt(route.metadata(), "max_operation_trips")
        .or(() -> readPositiveInt(route.metadata(), "maxOperationTrips"));
  }

  private static Optional<String> resolveDirection(Map<String, Object> metadata) {
    return readString(metadata, "direction").or(() -> readString(metadata, "direction_code"));
  }

  private static Optional<Integer> resolvePriority(Map<String, Object> metadata) {
    return readPositiveInt(metadata, "priority")
        .or(() -> readPositiveInt(metadata, "spawn_priority"));
  }

  private static Optional<Integer> readPositiveInt(Map<String, Object> metadata, String key) {
    return readInt(metadata, key).filter(value -> value > 0);
  }

  private static Optional<Integer> readInt(Map<String, Object> metadata, String key) {
    if (metadata == null || metadata.isEmpty() || key == null || key.isBlank()) {
      return Optional.empty();
    }
    Object raw = metadata.get(key);
    if (raw instanceof Number number) {
      return Optional.of(number.intValue());
    }
    if (raw instanceof String text) {
      try {
        return Optional.of(Integer.parseInt(text.trim()));
      } catch (NumberFormatException ignored) {
        return Optional.empty();
      }
    }
    return Optional.empty();
  }

  private static Optional<String> readString(Map<String, Object> metadata, String key) {
    if (metadata == null || metadata.isEmpty() || key == null || key.isBlank()) {
      return Optional.empty();
    }
    Object raw = metadata.get(key);
    if (raw == null) {
      return Optional.empty();
    }
    String value = String.valueOf(raw).trim();
    return value.isBlank() ? Optional.empty() : Optional.of(value);
  }

  private static String stableTripId(SpawnService service, Instant departure, TripSource source) {
    return sanitize(source.name())
        + "-"
        + sanitize(service.companyCode())
        + "-"
        + sanitize(service.operatorCode())
        + "-"
        + sanitize(service.lineCode())
        + "-"
        + sanitize(service.routeCode())
        + "-"
        + departure.toEpochMilli();
  }

  private static String sanitize(String value) {
    if (value == null || value.isBlank()) {
      return "unknown";
    }
    return value.trim().replaceAll("[^A-Za-z0-9_-]+", "-");
  }
}
