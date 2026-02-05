package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.LineStatus;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/**
 * 基于存储的 SpawnManager：读取 Line.spawnFreqBaselineSec 并生成发车票据。
 *
 * <p>默认行为：
 *
 * <ul>
 *   <li>仅对 {@link LineStatus#ACTIVE} 且配置了 spawnFreqBaselineSec 的线路生成票据
 *   <li>同一条线路可配置多条可发车 OPERATION route：通过 route.metadata 的 {@code spawn_weight} 控制长期比例
 *   <li>RETURN route 也会进入 SpawnPlan（用于 Layover 复用与站牌预测）
 *   <li>若存在多条 CRET route，则要求这些 route 的 depotNodeId 一致（不允许跨 depot 混发）
 *   <li>出库点从 route 的 CRET 指令推导；若首行不是 CRET，则使用首站 nodeId 作为 layover 复用起点
 * </ul>
 */
public final class StorageSpawnManager
    implements SpawnManager, SpawnForecastSupport, SpawnResetSupport {

  private final SpawnManagerSettings settings;
  private final Consumer<String> debugLogger;

  private SpawnPlan plan = SpawnPlan.empty();
  private SpawnPlan forecastPlan = SpawnPlan.empty();
  private Instant lastPlanRefresh = Instant.EPOCH;

  private final Map<SpawnServiceKey, ServiceState> states = new HashMap<>();
  private final PriorityQueue<SpawnTicket> queue =
      new PriorityQueue<>(
          Comparator.<SpawnTicket, Instant>comparing(SpawnTicket::notBefore)
              .thenComparing(SpawnTicket::dueAt)
              .thenComparing(
                  ticket -> ticket.service().operatorCode(), String.CASE_INSENSITIVE_ORDER)
              .thenComparing(ticket -> ticket.service().lineCode(), String.CASE_INSENSITIVE_ORDER)
              .thenComparing(ticket -> ticket.service().routeCode(), String.CASE_INSENSITIVE_ORDER)
              .thenComparing(ticket -> ticket.id().toString()));

  public StorageSpawnManager(SpawnManagerSettings settings, Consumer<String> debugLogger) {
    this.settings = settings == null ? SpawnManagerSettings.defaults() : settings;
    this.debugLogger = debugLogger != null ? debugLogger : message -> {};
  }

  @Override
  public List<SpawnTicket> pollDueTickets(StorageProvider provider, Instant now) {
    if (provider == null || now == null) {
      return List.of();
    }
    refreshPlanIfNeeded(provider, now);
    generateTickets(now);
    return drainDueTickets(now);
  }

  @Override
  public void requeue(SpawnTicket ticket) {
    if (ticket == null) {
      return;
    }
    queue.add(ticket);
  }

  @Override
  public void complete(SpawnTicket ticket) {
    if (ticket == null || ticket.service() == null) {
      return;
    }
    ServiceState state = states.get(ticket.service().key());
    if (state == null) {
      return;
    }
    state.backlog = Math.max(0, state.backlog - 1);
  }

  @Override
  public SpawnPlan snapshotPlan() {
    return plan;
  }

  @Override
  public List<SpawnTicket> snapshotQueue() {
    if (queue.isEmpty()) {
      return List.of();
    }
    List<SpawnTicket> snapshot = new ArrayList<>(queue);
    snapshot.sort(
        Comparator.<SpawnTicket, Instant>comparing(SpawnTicket::notBefore)
            .thenComparing(SpawnTicket::dueAt)
            .thenComparing(ticket -> ticket.service().operatorCode(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(ticket -> ticket.service().lineCode(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(ticket -> ticket.service().routeCode(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(ticket -> ticket.id().toString()));
    return List.copyOf(snapshot);
  }

  @Override
  public SpawnResetResult reset(Instant now) {
    int queueSize = queue.size();
    int stateSize = states.size();
    queue.clear();
    states.clear();
    plan = SpawnPlan.empty();
    forecastPlan = SpawnPlan.empty();
    lastPlanRefresh = Instant.EPOCH;
    debugLogger.accept(
        "SpawnManager reset: queue=" + queueSize + " states=" + stateSize + " at " + now);
    return new SpawnResetResult(queueSize, stateSize, true);
  }

  /**
   * 基于当前计划与内部状态生成“未出票”的预测票据列表（用于站牌展示）。
   *
   * <p>注意：该列表不进入真实队列，不会影响实际出车与 backlog。
   */
  @Override
  public List<SpawnTicket> snapshotForecast(Instant now, Duration horizon, int maxPerService) {
    if (now == null || horizon == null || horizon.isZero() || horizon.isNegative()) {
      return List.of();
    }
    SpawnPlan planSnapshot = this.forecastPlan;
    if (planSnapshot == null || planSnapshot.services().isEmpty()) {
      planSnapshot = this.plan;
      if (planSnapshot == null || planSnapshot.services().isEmpty()) {
        return List.of();
      }
    }
    int limitPerService = Math.max(1, Math.min(20, maxPerService));
    Instant cutoff = now.plus(horizon);
    List<SpawnTicket> out = new ArrayList<>();
    for (SpawnService service : planSnapshot.services()) {
      if (service == null) {
        continue;
      }
      Instant nextDueAt = resolveForecastStart(service, now);
      if (nextDueAt.isAfter(cutoff)) {
        continue;
      }
      Duration headway = service.baseHeadway();
      Instant due = nextDueAt;
      int count = 0;
      while (count < limitPerService && !due.isAfter(cutoff)) {
        out.add(new SpawnTicket(UUID.randomUUID(), service, due, due, 0, Optional.empty()));
        count++;
        due = due.plus(headway);
      }
    }
    return List.copyOf(out);
  }

  private void refreshPlanIfNeeded(StorageProvider provider, Instant now) {
    Duration ttl = settings.planRefreshInterval();
    if (!ttl.isZero() && !ttl.isNegative() && now.isBefore(lastPlanRefresh.plus(ttl))) {
      return;
    }
    SpawnPlan refreshed = buildPlan(provider, now);
    plan = refreshed;
    forecastPlan = buildForecastPlan(provider, now, refreshed);
    lastPlanRefresh = now;
    // 清理已不存在的 service 状态
    java.util.Set<SpawnServiceKey> live = new java.util.HashSet<>();
    for (SpawnService service : refreshed.services()) {
      if (service != null) {
        live.add(service.key());
      }
    }
    states.keySet().removeIf(key -> !live.contains(key));
    if (!queue.isEmpty()) {
      int before = queue.size();
      queue.removeIf(
          ticket ->
              ticket == null || ticket.service() == null || !live.contains(ticket.service().key()));
      int removed = before - queue.size();
      if (removed > 0) {
        debugLogger.accept("SpawnPlan 清理过期票据: removed=" + removed);
      }
    }
  }

  private Instant resolveForecastStart(SpawnService service, Instant now) {
    if (service == null || now == null) {
      return Instant.EPOCH;
    }
    ServiceState state = states.get(service.key());
    Instant nextDueAt = state != null ? state.nextDueAt : null;
    if (nextDueAt == null) {
      nextDueAt = now.plus(settings.initialJitter());
    }
    if (nextDueAt.isBefore(now)) {
      nextDueAt = alignToNextSlot(nextDueAt, now, service.baseHeadway());
    }
    return nextDueAt;
  }

  private Instant alignToNextSlot(Instant base, Instant now, Duration headway) {
    if (base == null || now == null) {
      return Instant.EPOCH;
    }
    if (headway == null || headway.isZero() || headway.isNegative()) {
      return now;
    }
    long headwayNanos;
    long deltaNanos;
    try {
      headwayNanos = headway.toNanos();
      deltaNanos = Duration.between(base, now).toNanos();
    } catch (ArithmeticException ex) {
      return now;
    }
    if (headwayNanos <= 0L) {
      return now;
    }
    if (deltaNanos <= 0L) {
      return base;
    }
    long steps = Math.floorDiv(deltaNanos, headwayNanos) + 1L;
    try {
      return base.plusNanos(Math.multiplyExact(headwayNanos, steps));
    } catch (ArithmeticException ex) {
      return now;
    }
  }

  private void generateTickets(Instant now) {
    int remainingBudget = settings.maxGeneratePerTick();
    for (SpawnService service : plan.services()) {
      if (service == null) {
        continue;
      }
      ServiceState state = states.computeIfAbsent(service.key(), key -> new ServiceState());
      if (state.nextDueAt == null) {
        state.nextDueAt = now.plus(settings.initialJitter());
      }
      while (remainingBudget > 0
          && state.backlog < settings.maxBacklogPerService()
          && (state.nextDueAt == null || !now.isBefore(state.nextDueAt))) {
        Instant dueAt = state.nextDueAt != null ? state.nextDueAt : now;
        SpawnTicket ticket =
            new SpawnTicket(UUID.randomUUID(), service, dueAt, dueAt, 0, Optional.empty());
        queue.add(ticket);
        state.backlog++;
        state.nextDueAt = dueAt.plus(service.baseHeadway());
        remainingBudget--;
      }
      if (remainingBudget <= 0) {
        break;
      }
    }
  }

  private List<SpawnTicket> drainDueTickets(Instant now) {
    int limit = settings.maxPollPerTick();
    if (limit <= 0) {
      return List.of();
    }
    List<SpawnTicket> out = new ArrayList<>();
    while (out.size() < limit) {
      SpawnTicket head = queue.peek();
      if (head == null) {
        break;
      }
      if (head.notBefore() != null && now.isBefore(head.notBefore())) {
        break;
      }
      queue.poll();
      out.add(head);
    }
    return out;
  }

  private SpawnPlan buildForecastPlan(StorageProvider provider, Instant now, SpawnPlan basePlan) {
    if (provider == null) {
      return basePlan == null ? SpawnPlan.empty() : basePlan;
    }
    List<SpawnService> services = new ArrayList<>();
    HashSet<SpawnServiceKey> known = new HashSet<>();
    if (basePlan != null) {
      for (SpawnService service : basePlan.services()) {
        if (service == null || service.key() == null) {
          continue;
        }
        if (known.add(service.key())) {
          services.add(service);
        }
      }
    }

    List<SpawnService> returnServices = buildReturnServices(provider);
    for (SpawnService service : returnServices) {
      if (service == null || service.key() == null) {
        continue;
      }
      if (known.add(service.key())) {
        services.add(service);
      }
    }

    services.sort(
        Comparator.comparing((SpawnService s) -> s.operatorCode().toLowerCase(Locale.ROOT))
            .thenComparing(s -> s.lineCode().toLowerCase(Locale.ROOT))
            .thenComparing(s -> s.routeCode().toLowerCase(Locale.ROOT)));
    debugLogger.accept("SpawnForecast 刷新: services=" + services.size() + " at " + now);
    return new SpawnPlan(now, services);
  }

  private List<SpawnService> buildReturnServices(StorageProvider provider) {
    List<SpawnService> services = new ArrayList<>();
    List<Company> companies = provider.companies().listAll();
    for (Company company : companies) {
      if (company == null) {
        continue;
      }
      for (Operator operator : provider.operators().listByCompany(company.id())) {
        if (operator == null) {
          continue;
        }
        for (Line line : provider.lines().listByOperator(operator.id())) {
          if (line == null) {
            continue;
          }
          if (line.status() != LineStatus.ACTIVE) {
            continue;
          }
          Optional<Integer> baseline = line.spawnFreqBaselineSec();
          if (baseline.isEmpty() || baseline.get() == null || baseline.get() <= 0) {
            continue;
          }
          Duration headway = Duration.ofSeconds(baseline.get());
          List<RouteSelection> selections = selectForecastRoutes(provider, line);
          if (selections.isEmpty()) {
            continue;
          }
          long sumWeight = selections.stream().mapToLong(RouteSelection::spawnWeight).sum();
          if (sumWeight <= 0L) {
            continue;
          }
          for (RouteSelection selection : selections) {
            Duration routeHeadway = scaleHeadway(headway, sumWeight, selection.spawnWeight());
            SpawnService service =
                new SpawnService(
                    new SpawnServiceKey(selection.route().id()),
                    company.id(),
                    company.code(),
                    operator.id(),
                    operator.code(),
                    line.id(),
                    line.code(),
                    selection.route().id(),
                    selection.route().code(),
                    routeHeadway,
                    selection.depotNodeId());
            services.add(service);
          }
        }
      }
    }
    return services;
  }

  private SpawnPlan buildPlan(StorageProvider provider, Instant now) {
    List<SpawnService> services = new ArrayList<>();
    List<Company> companies = provider.companies().listAll();
    for (Company company : companies) {
      if (company == null) {
        continue;
      }
      for (Operator operator : provider.operators().listByCompany(company.id())) {
        if (operator == null) {
          continue;
        }
        for (Line line : provider.lines().listByOperator(operator.id())) {
          if (line == null) {
            continue;
          }
          if (line.status() != LineStatus.ACTIVE) {
            continue;
          }
          Optional<Integer> baseline = line.spawnFreqBaselineSec();
          if (baseline.isEmpty() || baseline.get() == null || baseline.get() <= 0) {
            continue;
          }
          Duration headway = Duration.ofSeconds(baseline.get());
          List<RouteSelection> selections = selectSpawnRoutes(provider, line);
          appendServices(services, company, operator, line, headway, selections);

          List<RouteSelection> returnSelections = selectForecastRoutes(provider, line);
          appendServices(services, company, operator, line, headway, returnSelections);
        }
      }
    }
    services.sort(
        Comparator.comparing((SpawnService s) -> s.operatorCode().toLowerCase(Locale.ROOT))
            .thenComparing(s -> s.lineCode().toLowerCase(Locale.ROOT))
            .thenComparing(s -> s.routeCode().toLowerCase(Locale.ROOT)));
    debugLogger.accept("SpawnPlan 刷新: services=" + services.size() + " at " + now);
    return new SpawnPlan(now, services);
  }

  private void appendServices(
      List<SpawnService> services,
      Company company,
      Operator operator,
      Line line,
      Duration headway,
      List<RouteSelection> selections) {
    if (services == null || selections == null || selections.isEmpty()) {
      return;
    }
    long sumWeight = selections.stream().mapToLong(RouteSelection::spawnWeight).sum();
    if (sumWeight <= 0L) {
      return;
    }
    for (RouteSelection selection : selections) {
      Duration routeHeadway = scaleHeadway(headway, sumWeight, selection.spawnWeight());
      SpawnService service =
          new SpawnService(
              new SpawnServiceKey(selection.route().id()),
              company.id(),
              company.code(),
              operator.id(),
              operator.code(),
              line.id(),
              line.code(),
              selection.route().id(),
              selection.route().code(),
              routeHeadway,
              selection.depotNodeId());
      services.add(service);
    }
  }

  private List<RouteSelection> selectSpawnRoutes(StorageProvider provider, Line line) {
    Objects.requireNonNull(provider, "provider");
    if (line == null) {
      return List.of();
    }
    List<Route> routes = provider.routes().listByLine(line.id());
    if (routes.isEmpty()) {
      return List.of();
    }
    List<RouteSelection> candidates = new ArrayList<>();
    for (Route route : routes) {
      if (route == null || route.operationType() != RouteOperationType.OPERATION) {
        continue;
      }
      List<RouteStop> stops = provider.routeStops().listByRoute(route.id());
      if (stops.isEmpty()) {
        continue;
      }
      RouteStop first = stops.get(0);
      Optional<String> cret = SpawnDirectiveParser.findDirectiveTarget(first, "CRET");
      boolean depotSpawn = cret.isPresent();
      String startNode = cret.orElseGet(() -> resolveStopNodeId(first));
      if (startNode == null || startNode.isBlank()) {
        continue;
      }
      Optional<Boolean> enabledFlag = readBoolean(route.metadata(), "spawn_enabled");
      Optional<Integer> weight = readInt(route.metadata(), "spawn_weight");
      candidates.add(new RouteSelection(route, startNode.trim(), depotSpawn, enabledFlag, weight));
    }
    if (candidates.isEmpty()) {
      return List.of();
    }

    boolean multi = candidates.size() > 1;
    boolean hasExplicitDisable = false;
    boolean hasExplicitWeightZero = false;
    List<RouteSelection> enabled = new ArrayList<>();
    for (RouteSelection selection : candidates) {
      if (selection == null) {
        continue;
      }
      if (selection.spawnEnabledFlag().isPresent() && !selection.spawnEnabledFlag().get()) {
        hasExplicitDisable = true;
        continue;
      }
      Optional<Integer> weight = selection.spawnWeightRaw();
      if (weight.isPresent()) {
        int w = weight.get() == null ? 0 : weight.get();
        if (w <= 0) {
          hasExplicitWeightZero = true;
          continue;
        }
        enabled.add(selection.withResolvedWeight(w));
        continue;
      }
      if (!multi) {
        enabled.add(selection.withResolvedWeight(1));
      } else if (selection.spawnEnabledFlag().orElse(false)) {
        enabled.add(selection.withResolvedWeight(1));
      }
    }

    if (enabled.isEmpty()) {
      if (candidates.size() == 1) {
        RouteSelection only = candidates.get(0);
        if (only.spawnEnabledFlag().isPresent() && !only.spawnEnabledFlag().get()) {
          return List.of();
        }
        if (only.spawnWeightRaw().isPresent()) {
          return List.of();
        }
        return List.of(only.withResolvedWeight(1));
      }
      if (hasExplicitDisable || hasExplicitWeightZero) {
        debugLogger.accept(
            "SpawnPlan 跳过线路(候选 route 已禁用或权重为 0): line="
                + line.code()
                + " routes="
                + String.join(", ", candidates.stream().map(sel -> sel.route().code()).toList()));
        return List.of();
      }
      candidates.sort(
          Comparator.comparing(sel -> sel.route().code(), String.CASE_INSENSITIVE_ORDER));
      debugLogger.accept(
          "SpawnPlan 跳过线路(候选过多未配置 spawn_weight): line="
              + line.code()
              + " routes="
              + String.join(", ", candidates.stream().map(sel -> sel.route().code()).toList()));
      return List.of();
    }

    enabled.sort(Comparator.comparing(sel -> sel.route().code(), String.CASE_INSENSITIVE_ORDER));
    List<RouteSelection> depotSelections =
        enabled.stream().filter(RouteSelection::depotSpawn).toList();
    if (depotSelections.size() > 1) {
      String depot = depotSelections.get(0).depotNodeId();
      boolean depotMismatch =
          depotSelections.stream()
              .map(RouteSelection::depotNodeId)
              .anyMatch(d -> d == null || !d.equalsIgnoreCase(depot));
      if (depotMismatch) {
        debugLogger.accept(
            "SpawnPlan 跳过线路(不允许跨 depot 混发): line="
                + line.code()
                + " routes="
                + String.join(
                    ", ",
                    depotSelections.stream()
                        .map(sel -> sel.route().code() + "@" + sel.depotNodeId())
                        .toList()));
        return List.of();
      }
    }

    return enabled;
  }

  private List<RouteSelection> selectForecastRoutes(StorageProvider provider, Line line) {
    Objects.requireNonNull(provider, "provider");
    if (line == null) {
      return List.of();
    }
    List<Route> routes = provider.routes().listByLine(line.id());
    if (routes.isEmpty()) {
      return List.of();
    }
    List<RouteSelection> candidates = new ArrayList<>();
    for (Route route : routes) {
      if (route == null || route.operationType() != RouteOperationType.RETURN) {
        continue;
      }
      List<RouteStop> stops = provider.routeStops().listByRoute(route.id());
      if (stops.isEmpty()) {
        continue;
      }
      RouteStop first = stops.get(0);
      Optional<String> cret = SpawnDirectiveParser.findDirectiveTarget(first, "CRET");
      boolean depotSpawn = cret.isPresent();
      String startNode = cret.orElseGet(() -> resolveStopNodeId(first));
      if (startNode == null || startNode.isBlank()) {
        continue;
      }
      Optional<Boolean> enabledFlag = readBoolean(route.metadata(), "spawn_enabled");
      if (enabledFlag.isPresent() && !enabledFlag.get()) {
        continue;
      }
      Optional<Integer> weightOpt = readInt(route.metadata(), "spawn_weight");
      int weight = weightOpt.orElse(1);
      if (weight <= 0) {
        continue;
      }
      candidates.add(
          new RouteSelection(route, startNode.trim(), depotSpawn, enabledFlag, weightOpt)
              .withResolvedWeight(weight));
    }
    return candidates;
  }

  /**
   * 从 RouteStop 解析节点 ID。
   *
   * <p>优先使用 waypointNodeId，若为空则尝试解析 DYNAMIC 规范并生成 placeholder。
   */
  private static String resolveStopNodeId(RouteStop stop) {
    if (stop == null) {
      return "";
    }
    if (stop.waypointNodeId().isPresent()) {
      return stop.waypointNodeId().get();
    }
    // 尝试解析 DYNAMIC
    Optional<org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher.DynamicSpec> specOpt =
        org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher.parseDynamicSpec(stop);
    if (specOpt.isPresent()) {
      return specOpt.get().toPlaceholderNodeId();
    }
    return "";
  }

  private Optional<Boolean> readBoolean(Map<String, Object> meta, String key) {
    if (meta == null || key == null || key.isBlank()) {
      return Optional.empty();
    }
    Object value = meta.get(key);
    if (value instanceof Boolean b) {
      return Optional.of(b);
    }
    if (value instanceof String s) {
      String t = s.trim().toLowerCase(Locale.ROOT);
      if ("true".equals(t)) {
        return Optional.of(true);
      }
      if ("false".equals(t)) {
        return Optional.of(false);
      }
    }
    return Optional.empty();
  }

  private Optional<Integer> readInt(Map<String, Object> meta, String key) {
    if (meta == null || key == null || key.isBlank()) {
      return Optional.empty();
    }
    Object value = meta.get(key);
    if (value instanceof Number n) {
      return Optional.of(n.intValue());
    }
    if (value instanceof String s) {
      String t = s.trim();
      if (t.isEmpty()) {
        return Optional.empty();
      }
      try {
        return Optional.of(Integer.parseInt(t));
      } catch (NumberFormatException ignored) {
        return Optional.empty();
      }
    }
    return Optional.empty();
  }

  private static Duration scaleHeadway(Duration baseline, long sumWeight, int weight) {
    if (baseline == null || baseline.isZero() || baseline.isNegative()) {
      return Duration.ofSeconds(1);
    }
    if (sumWeight <= 0L || weight <= 0) {
      return baseline;
    }
    try {
      long baselineNanos = baseline.toNanos();
      long scaledNanos = Math.multiplyExact(baselineNanos, sumWeight) / (long) weight;
      if (scaledNanos <= 0L) {
        scaledNanos = 1L;
      }
      return Duration.ofNanos(scaledNanos);
    } catch (ArithmeticException overflow) {
      long seconds = baseline.getSeconds();
      long scaledSeconds = (seconds * sumWeight) / (long) weight;
      if (scaledSeconds <= 0L) {
        scaledSeconds = 1L;
      }
      return Duration.ofSeconds(scaledSeconds);
    }
  }

  private static final class ServiceState {
    private Instant nextDueAt;
    private int backlog;
  }

  private record RouteSelection(
      Route route,
      String depotNodeId,
      boolean depotSpawn,
      Optional<Boolean> spawnEnabledFlag,
      Optional<Integer> spawnWeightRaw,
      int spawnWeight) {

    private RouteSelection {
      spawnEnabledFlag = spawnEnabledFlag == null ? Optional.empty() : spawnEnabledFlag;
      spawnWeightRaw = spawnWeightRaw == null ? Optional.empty() : spawnWeightRaw;
    }

    private RouteSelection(
        Route route,
        String depotNodeId,
        boolean depotSpawn,
        Optional<Boolean> enabled,
        Optional<Integer> weight) {
      this(route, depotNodeId, depotSpawn, enabled, weight, 0);
    }

    private RouteSelection withResolvedWeight(int weight) {
      int w = Math.max(1, Math.min(1000, weight));
      return new RouteSelection(
          route, depotNodeId, depotSpawn, spawnEnabledFlag, spawnWeightRaw, w);
    }
  }

  /** SpawnManager 的内部参数（独立于 config.yml，便于测试/调参）。 */
  public record SpawnManagerSettings(
      Duration planRefreshInterval,
      Duration initialJitter,
      int maxBacklogPerService,
      int maxGeneratePerTick,
      int maxPollPerTick) {
    public SpawnManagerSettings {
      planRefreshInterval =
          planRefreshInterval == null ? Duration.ofSeconds(10) : planRefreshInterval;
      initialJitter = initialJitter == null ? Duration.ZERO : initialJitter;
      if (maxBacklogPerService <= 0) {
        maxBacklogPerService = 5;
      }
      if (maxGeneratePerTick <= 0) {
        maxGeneratePerTick = 5;
      }
      if (maxPollPerTick <= 0) {
        maxPollPerTick = 1;
      }
    }

    public static SpawnManagerSettings defaults() {
      return new SpawnManagerSettings(Duration.ofSeconds(10), Duration.ZERO, 5, 5, 1);
    }
  }
}
