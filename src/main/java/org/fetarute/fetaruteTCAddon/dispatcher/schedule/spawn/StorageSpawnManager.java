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
import java.util.stream.Collectors;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.LineStatus;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TerminalKeyResolver;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/**
 * 基于存储的 SpawnManager：从线路与交路配置生成发车票据。
 *
 * <p>这个类只负责“计划生成”和“票据排序”，不直接创建列车，也不接管运行时信号/占用。实际出车由 {@link SimpleTicketAssigner} 执行。
 *
 * <p>默认行为：
 *
 * <ul>
 *   <li>仅对 {@link LineStatus#ACTIVE} 的线路生成票据（可使用 line baseline 或 group baseline）
 *   <li>同一条线路可配置多条 CREATE/OPERATION/RETURN route，并在“交路组”内按权重分配频率
 *   <li>RETURN route 也会进入 SpawnPlan 与 forecast（用于 Layover 复用、折返与站牌预测）
 *   <li>允许线路跨 depot 混发（可配合 Line.metadata.spawn_depots 做集中管理）
 *   <li>出库点从 route 的 CRET 指令推导；若首行不是 CRET，则使用首站 nodeId 作为 layover 复用起点
 * </ul>
 *
 * <p>交路组规则：
 *
 * <ul>
 *   <li>优先使用 route.metadata 的 {@code spawn_group}
 *   <li>未配置时按首站/起点（含 DYNAMIC）自动推导，便于同终点折返与回库 route 进入同组
 *   <li>组内仅 {@code OPERATION} 使用 {@code spawn_weight} 分摊；{@code CREATE/RETURN} 固定为 1
 *   <li>组优先使用 {@code spawn_group_baseline_sec} 作为发车基准；未配置时才回退 line baseline
 *   <li>{@code spawn_group_weight} 仅作为旧配置回退（已不推荐）
 * </ul>
 *
 * <p>这意味着它不会修改列车的生命周期标签，也不会触碰 pending layover 或 reclaim 状态。
 *
 * <p>长期运行时会按票据首次 due 时间清理过旧队列项，并同步释放对应 service 的 backlog，避免堵塞 route 在数天后形成幽灵积压。
 */
public final class StorageSpawnManager
    implements SpawnManager, SpawnForecastSupport, SpawnResetSupport {

  private final SpawnManagerSettings settings;
  private final Consumer<String> debugLogger;

  private SpawnPlan plan = SpawnPlan.empty();
  private SpawnPlan forecastPlan = SpawnPlan.empty();
  private Instant lastPlanRefresh = Instant.EPOCH;
  private final Map<SpawnServiceKey, Duration> initialOffsets = new HashMap<>();

  private final Map<SpawnServiceKey, ServiceState> states = new HashMap<>();
  private final PriorityQueue<SpawnTicket> queue =
      new PriorityQueue<>(
          Comparator.<SpawnTicket, Instant>comparing(SpawnTicket::notBefore)
              .thenComparing(SpawnTicket::dueAt)
              .thenComparing(SpawnTicket::sequenceNumber)
              .thenComparing(
                  ticket -> ticket.service().operatorCode(), String.CASE_INSENSITIVE_ORDER)
              .thenComparing(ticket -> ticket.service().lineCode(), String.CASE_INSENSITIVE_ORDER)
              .thenComparing(ticket -> ticket.service().routeCode(), String.CASE_INSENSITIVE_ORDER)
              .thenComparing(ticket -> ticket.id().toString()));
  private long globalSequence = 0;

  /** 计划生成游标：在多线路/多交路组长期积压时按服务轮转出票，避免固定排序饿死后续服务。 */
  private int generationCursor = 0;

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
    cleanupExpiredQueuedTickets(now);
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
    initialOffsets.clear();
    plan = SpawnPlan.empty();
    forecastPlan = SpawnPlan.empty();
    lastPlanRefresh = Instant.EPOCH;
    generationCursor = 0;
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
      long seq = 0;
      while (count < limitPerService && !due.isAfter(cutoff)) {
        out.add(
            new SpawnTicket(
                UUID.randomUUID(),
                service,
                due,
                due,
                0,
                seq++,
                Optional.empty(),
                Optional.empty()));
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
    if (generationCursor >= refreshed.services().size()) {
      generationCursor = 0;
    }
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

  /** 清理超过最大保留时间的队列票据，并释放对应 service 的 backlog。 */
  private void cleanupExpiredQueuedTickets(Instant now) {
    Duration maxAge = settings.queuedTicketMaxAge();
    if (now == null
        || maxAge == null
        || maxAge.isZero()
        || maxAge.isNegative()
        || queue.isEmpty()) {
      return;
    }
    Map<SpawnServiceKey, Integer> removedByService = new HashMap<>();
    int before = queue.size();
    queue.removeIf(
        ticket -> {
          if (!isExpiredQueuedTicket(ticket, now, maxAge)) {
            return false;
          }
          if (ticket != null && ticket.service() != null) {
            removedByService.merge(ticket.service().key(), 1, Integer::sum);
          }
          return true;
        });
    if (removedByService.isEmpty()) {
      return;
    }
    for (Map.Entry<SpawnServiceKey, Integer> entry : removedByService.entrySet()) {
      ServiceState state = states.get(entry.getKey());
      if (state == null) {
        continue;
      }
      state.backlog = Math.max(0, state.backlog - entry.getValue());
    }
    debugLogger.accept(
        "SpawnPlan 清理超龄票据: removed="
            + (before - queue.size())
            + " maxAge="
            + maxAge.toSeconds()
            + "s");
  }

  private boolean isExpiredQueuedTicket(SpawnTicket ticket, Instant now, Duration maxAge) {
    if (ticket == null || now == null || maxAge == null) {
      return ticket == null;
    }
    Instant firstDueAt = ticket.firstDueAt();
    if (firstDueAt == null) {
      firstDueAt = ticket.dueAt();
    }
    if (firstDueAt == null || firstDueAt.isAfter(now)) {
      return false;
    }
    return Duration.between(firstDueAt, now).compareTo(maxAge) >= 0;
  }

  private Instant resolveForecastStart(SpawnService service, Instant now) {
    if (service == null || now == null) {
      return Instant.EPOCH;
    }
    ServiceState state = states.get(service.key());
    Instant nextDueAt = state != null ? state.nextDueAt : null;
    if (nextDueAt == null) {
      nextDueAt = initialDueAt(service, now);
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
    List<SpawnService> services = plan.services();
    if (remainingBudget <= 0 || services.isEmpty()) {
      return;
    }
    if (generationCursor < 0 || generationCursor >= services.size()) {
      generationCursor = 0;
    }
    int cursor = generationCursor;
    boolean generatedInCycle = true;
    while (remainingBudget > 0 && generatedInCycle) {
      generatedInCycle = false;
      int checked = 0;
      while (remainingBudget > 0 && checked < services.size()) {
        SpawnService service = services.get(cursor);
        cursor = (cursor + 1) % services.size();
        checked++;
        if (tryGenerateOneTicket(service, now)) {
          remainingBudget--;
          generatedInCycle = true;
          generationCursor = cursor;
        }
      }
    }
  }

  /**
   * 对单个服务最多生成一张票据。
   *
   * <p>票据追赶由外层轮转多轮完成，这样在 {@code maxGeneratePerTick} 较小时，长期积压的第一条服务不会独占全部生成预算。
   */
  private boolean tryGenerateOneTicket(SpawnService service, Instant now) {
    if (service == null) {
      return false;
    }
    ServiceState state = states.computeIfAbsent(service.key(), key -> new ServiceState());
    if (state.nextDueAt == null) {
      state.nextDueAt = initialDueAt(service, now);
    }
    if (state.backlog >= settings.maxBacklogPerService()
        || (state.nextDueAt != null && now.isBefore(state.nextDueAt))) {
      return false;
    }
    Instant dueAt = state.nextDueAt != null ? state.nextDueAt : now;
    SpawnTicket ticket =
        new SpawnTicket(
            UUID.randomUUID(),
            service,
            dueAt,
            dueAt,
            0,
            globalSequence++,
            Optional.empty(),
            Optional.empty());
    queue.add(ticket);
    state.backlog++;
    state.nextDueAt = dueAt.plus(service.baseHeadway());
    return true;
  }

  /**
   * 计算某条服务的首次出车时刻。
   *
   * <p>包含两部分偏移：
   *
   * <ul>
   *   <li>全局 jitter（配置项）
   *   <li>交路组内相位偏移（避免同组 route 同 tick 同时出票）
   * </ul>
   */
  private Instant initialDueAt(SpawnService service, Instant now) {
    if (service == null || now == null) {
      return Instant.EPOCH;
    }
    Duration base = settings.initialJitter();
    Duration offset = initialOffsets.getOrDefault(service.key(), Duration.ZERO);
    return now.plus(base).plus(offset);
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
          Optional<Duration> lineHeadway = resolveLineBaselineHeadway(line);
          List<RouteSelection> selections = selectForecastRoutes(provider, line);
          if (selections.isEmpty()) {
            continue;
          }
          appendCirculationServices(
              services, company, operator, line, lineHeadway, selections, new HashMap<>());
        }
      }
    }
    return services;
  }

  private SpawnPlan buildPlan(StorageProvider provider, Instant now) {
    List<SpawnService> services = new ArrayList<>();
    Map<SpawnServiceKey, Duration> computedOffsets = new HashMap<>();
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
          Optional<Duration> lineHeadway = resolveLineBaselineHeadway(line);
          List<RouteSelection> selections = new ArrayList<>(selectSpawnRoutes(provider, line));
          List<RouteSelection> returnSelections = selectForecastRoutes(provider, line);
          selections.addAll(returnSelections);
          appendCirculationServices(
              services, company, operator, line, lineHeadway, selections, computedOffsets);
        }
      }
    }
    services.sort(
        Comparator.comparing((SpawnService s) -> s.operatorCode().toLowerCase(Locale.ROOT))
            .thenComparing(s -> s.lineCode().toLowerCase(Locale.ROOT))
            .thenComparing(s -> s.routeCode().toLowerCase(Locale.ROOT)));
    initialOffsets.clear();
    initialOffsets.putAll(computedOffsets);
    debugLogger.accept("SpawnPlan 刷新: services=" + services.size() + " at " + now);
    return new SpawnPlan(now, services);
  }

  /**
   * 交路组维度构建 SpawnService。
   *
   * <p>优先使用 group baseline，再按 route 权重拆分；当 group baseline 缺失时回退 line baseline。
   *
   * <p>权重策略：
   *
   * <ul>
   *   <li>{@code OPERATION}: 使用 {@code spawn_weight}（默认 1）
   *   <li>{@code CREATE/RETURN}: 固定权重 1（不受 {@code spawn_weight} 影响）
   * </ul>
   *
   * <ul>
   *   <li>推荐：使用 {@code spawn_group_baseline_sec} 明确每个交路组频率
   *   <li>兼容：未配置 group baseline 时，回退 line baseline（旧配置下仍支持 {@code spawn_group_weight}）
   * </ul>
   */
  private void appendCirculationServices(
      List<SpawnService> services,
      Company company,
      Operator operator,
      Line line,
      Optional<Duration> lineHeadway,
      List<RouteSelection> selections,
      Map<SpawnServiceKey, Duration> offsetByService) {
    if (services == null || selections == null || selections.isEmpty()) {
      return;
    }
    Map<String, List<RouteSelection>> byGroup = new HashMap<>();
    for (RouteSelection selection : selections) {
      if (selection == null) {
        continue;
      }
      String group = resolveCirculationGroupKey(line, selection);
      byGroup.computeIfAbsent(group, ignored -> new ArrayList<>()).add(selection);
    }
    if (byGroup.isEmpty()) {
      return;
    }
    Map<String, SpawnGroup> configuredGroups =
        indexConfiguredGroups(LineSpawnMetadata.parseGroups(line.metadata()));
    List<CirculationGroupSelection> groups = new ArrayList<>();
    for (Map.Entry<String, List<RouteSelection>> entry : byGroup.entrySet()) {
      List<RouteSelection> groupRoutes = entry.getValue();
      if (groupRoutes == null || groupRoutes.isEmpty()) {
        continue;
      }
      Optional<Integer> groupBaselineSeconds =
          resolveGroupBaselineSeconds(configuredGroups, entry.getKey(), groupRoutes);
      if (groupBaselineSeconds.isEmpty() && (lineHeadway == null || lineHeadway.isEmpty())) {
        debugLogger.accept(
            "SpawnPlan 跳过交路组(缺少 baseline): line=" + line.code() + " group=" + entry.getKey());
        continue;
      }
      int groupWeight = resolveGroupWeight(groupRoutes);
      if (groupWeight <= 0) {
        continue;
      }
      groups.add(
          new CirculationGroupSelection(
              entry.getKey(), groupRoutes, groupWeight, groupBaselineSeconds));
    }
    if (groups.isEmpty()) {
      return;
    }
    groups.sort(
        Comparator.comparing(CirculationGroupSelection::key, String.CASE_INSENSITIVE_ORDER));
    boolean hasExplicitGroupBaseline =
        groups.stream().anyMatch(group -> group.groupBaselineSeconds().isPresent());
    long sumGroupWeight = groups.stream().mapToLong(CirculationGroupSelection::groupWeight).sum();
    if (!hasExplicitGroupBaseline
        && lineHeadway != null
        && lineHeadway.isPresent()
        && sumGroupWeight <= 0L) {
      return;
    }

    for (CirculationGroupSelection group : groups) {
      if (hasExplicitGroupBaseline && group.groupBaselineSeconds().isEmpty()) {
        debugLogger.accept(
            "SpawnPlan 交路组回退 line baseline: line=" + line.code() + " group=" + group.key());
      }
      Optional<Duration> groupHeadwayOpt =
          resolveGroupHeadway(group, lineHeadway, hasExplicitGroupBaseline, sumGroupWeight);
      if (groupHeadwayOpt.isEmpty()) {
        continue;
      }
      Duration groupHeadway = groupHeadwayOpt.get();
      List<RouteSelection> groupRoutes = new ArrayList<>(group.routes());
      groupRoutes.sort(
          Comparator.comparing(
              selection -> selection.route().code(), String.CASE_INSENSITIVE_ORDER));
      long sumRouteWeight = groupRoutes.stream().mapToLong(RouteSelection::spawnWeight).sum();
      if (sumRouteWeight <= 0L) {
        continue;
      }
      for (RouteSelection selection : groupRoutes) {
        Duration routeHeadway = scaleHeadway(groupHeadway, sumRouteWeight, selection.spawnWeight());
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
      assignGroupInitialOffsets(groupRoutes, groupHeadway, offsetByService);
    }
  }

  /**
   * 为同一交路组分配初始相位。
   *
   * <p>该偏移只用于首次出车，后续由各服务自身 headway 推进。
   */
  private static void assignGroupInitialOffsets(
      List<RouteSelection> groupRoutes,
      Duration groupHeadway,
      Map<SpawnServiceKey, Duration> offsetByService) {
    if (groupRoutes == null
        || groupRoutes.isEmpty()
        || groupHeadway == null
        || groupHeadway.isZero()
        || groupHeadway.isNegative()
        || offsetByService == null) {
      return;
    }
    int slot = 0;
    for (RouteSelection selection : groupRoutes) {
      if (selection == null || selection.route() == null) {
        continue;
      }
      SpawnServiceKey key = new SpawnServiceKey(selection.route().id());
      Duration offset = multiplyDuration(groupHeadway, slot);
      offsetByService.putIfAbsent(key, offset);
      slot++;
    }
  }

  private static Duration multiplyDuration(Duration duration, int multiplier) {
    if (duration == null || duration.isZero() || duration.isNegative() || multiplier <= 0) {
      return Duration.ZERO;
    }
    try {
      return duration.multipliedBy(multiplier);
    } catch (ArithmeticException overflow) {
      return Duration.ZERO;
    }
  }

  private int resolveGroupWeight(List<RouteSelection> groupRoutes) {
    if (groupRoutes == null || groupRoutes.isEmpty()) {
      return 1;
    }
    int max = 1;
    for (RouteSelection selection : groupRoutes) {
      if (selection == null || selection.route() == null) {
        continue;
      }
      Optional<Integer> configured = readInt(selection.route().metadata(), "spawn_group_weight");
      if (configured.isPresent() && configured.get() != null && configured.get() > 0) {
        max = Math.max(max, configured.get());
      }
    }
    return Math.max(1, Math.min(1000, max));
  }

  /**
   * 解析交路组发车基准秒数。
   *
   * <p>优先读取 {@code spawn_group_baseline_sec}，兼容旧别名 {@code spawn_group_baseline}。同组出现多个值时，
   * 取更保守（更大的）秒数，避免因配置不一致导致突发增发。
   */
  private Optional<Integer> resolveGroupBaselineSeconds(
      Map<String, SpawnGroup> configuredGroups, String groupKey, List<RouteSelection> groupRoutes) {
    if (configuredGroups != null && !configuredGroups.isEmpty()) {
      String normalizedGroupName = extractGroupName(groupKey);
      if (!normalizedGroupName.isBlank()) {
        SpawnGroup configured = configuredGroups.get(normalizedGroupName);
        if (configured != null && configured.baselineSeconds().isPresent()) {
          return configured.baselineSeconds();
        }
      }
    }
    if (groupRoutes == null || groupRoutes.isEmpty()) {
      return Optional.empty();
    }
    HashSet<Integer> configuredValues = new HashSet<>();
    for (RouteSelection selection : groupRoutes) {
      if (selection == null || selection.route() == null) {
        continue;
      }
      Optional<Integer> configured =
          readInt(selection.route().metadata(), "spawn_group_baseline_sec");
      if (configured.isEmpty()) {
        configured = readInt(selection.route().metadata(), "spawn_group_baseline");
      }
      if (configured.isPresent() && configured.get() != null && configured.get() > 0) {
        configuredValues.add(configured.get());
      }
    }
    if (configuredValues.isEmpty()) {
      return Optional.empty();
    }
    if (configuredValues.size() > 1) {
      debugLogger.accept("SpawnPlan 交路组 baseline 不一致，取最大值: values=" + configuredValues);
    }
    return configuredValues.stream().max(Integer::compareTo);
  }

  private static Map<String, SpawnGroup> indexConfiguredGroups(List<SpawnGroup> groups) {
    if (groups == null || groups.isEmpty()) {
      return Map.of();
    }
    Map<String, SpawnGroup> index = new HashMap<>();
    for (SpawnGroup group : groups) {
      if (group == null || group.name().isBlank()) {
        continue;
      }
      index.put(group.normalizedName(), group);
    }
    return Map.copyOf(index);
  }

  private static String extractGroupName(String groupKey) {
    if (groupKey == null || groupKey.isBlank()) {
      return "";
    }
    int separator = groupKey.indexOf('|');
    String raw = separator >= 0 ? groupKey.substring(separator + 1) : groupKey;
    return raw.trim().toLowerCase(Locale.ROOT);
  }

  /**
   * 解析交路组实际 headway。
   *
   * <p>策略：
   *
   * <ul>
   *   <li>有 {@code spawn_group_baseline_sec}：直接使用该值
   *   <li>无 group baseline 且无任何显式 group baseline：沿用旧逻辑，按 {@code spawn_group_weight} 拆分 line
   *       baseline
   *   <li>无 group baseline 但存在其他显式 group baseline：回退为 line baseline（不再使用 group weight）
   * </ul>
   */
  private static Optional<Duration> resolveGroupHeadway(
      CirculationGroupSelection group,
      Optional<Duration> lineHeadway,
      boolean hasExplicitGroupBaseline,
      long sumGroupWeight) {
    if (group == null) {
      return Optional.empty();
    }
    if (group.groupBaselineSeconds().isPresent()) {
      Integer baseline = group.groupBaselineSeconds().get();
      if (baseline != null && baseline > 0) {
        return Optional.of(Duration.ofSeconds(baseline));
      }
    }
    if (lineHeadway == null || lineHeadway.isEmpty()) {
      return Optional.empty();
    }
    Duration base = lineHeadway.get();
    if (hasExplicitGroupBaseline) {
      return Optional.of(base);
    }
    return Optional.of(scaleHeadway(base, Math.max(1L, sumGroupWeight), group.groupWeight()));
  }

  /**
   * 解析 route 所属交路组。
   *
   * <p>优先使用 {@code spawn_group}，未配置时按首站/起点归并。
   */
  private String resolveCirculationGroupKey(Line line, RouteSelection selection) {
    if (selection == null || selection.route() == null) {
      return "default";
    }
    Optional<String> configured = readString(selection.route().metadata(), "spawn_group");
    if (configured.isPresent()) {
      String group = configured.get().trim().toLowerCase(Locale.ROOT);
      if (!group.isBlank()) {
        return line.id() + "|" + group;
      }
    }
    String normalizedStart = normalizeStartForGroup(selection.depotNodeId());
    if (normalizedStart.isBlank()) {
      normalizedStart = selection.route().code().toLowerCase(Locale.ROOT);
    }
    return line.id() + "|" + normalizedStart;
  }

  private static String normalizeStartForGroup(String startNode) {
    if (startNode == null || startNode.isBlank()) {
      return "";
    }
    String normalized = startNode.trim().toLowerCase(Locale.ROOT);
    if (SpawnDirectiveParser.isDynamicTarget(normalized)) {
      Optional<String> dynamic = parseDynamicGroupKey(normalized);
      if (dynamic.isPresent()) {
        return dynamic.get();
      }
      return normalized;
    }
    Optional<String> stationKey = TerminalKeyResolver.extractStationKey(normalized);
    return stationKey.orElse(normalized);
  }

  private static Optional<String> parseDynamicGroupKey(String dynamicSpec) {
    if (dynamicSpec == null || dynamicSpec.isBlank()) {
      return Optional.empty();
    }
    String normalized = dynamicSpec.trim().toLowerCase(Locale.ROOT);
    if (!normalized.startsWith("dynamic:")) {
      return Optional.empty();
    }
    String rest = normalized.substring("dynamic:".length());
    String[] parts = rest.split(":", 4);
    if (parts.length < 3) {
      return Optional.empty();
    }
    String operator = parts[0].trim();
    String type = parts[1].trim();
    String name = parts[2].trim();
    if (operator.isBlank() || type.isBlank() || name.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(operator + ":" + type + ":" + name);
  }

  private Optional<String> readString(Map<String, Object> meta, String key) {
    if (meta == null || key == null || key.isBlank()) {
      return Optional.empty();
    }
    Object value = meta.get(key);
    if (value == null) {
      return Optional.empty();
    }
    String text = String.valueOf(value).trim();
    return text.isEmpty() ? Optional.empty() : Optional.of(text);
  }

  private Optional<Duration> resolveLineBaselineHeadway(Line line) {
    if (line == null) {
      return Optional.empty();
    }
    Optional<Integer> baseline = line.spawnFreqBaselineSec();
    if (baseline.isEmpty() || baseline.get() == null || baseline.get() <= 0) {
      return Optional.empty();
    }
    return Optional.of(Duration.ofSeconds(baseline.get()));
  }

  private record CirculationGroupSelection(
      String key,
      List<RouteSelection> routes,
      int groupWeight,
      Optional<Integer> groupBaselineSeconds) {
    private CirculationGroupSelection {
      routes = routes == null ? List.of() : List.copyOf(routes);
      groupBaselineSeconds = groupBaselineSeconds == null ? Optional.empty() : groupBaselineSeconds;
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
    List<RouteSelection> operationCandidates = new ArrayList<>();
    List<RouteSelection> createCandidates = new ArrayList<>();
    for (Route route : routes) {
      if (route == null) {
        continue;
      }
      if (route.operationType() != RouteOperationType.OPERATION
          && route.operationType() != RouteOperationType.CREATE) {
        continue;
      }
      List<RouteStop> stops = provider.routeStops().listByRoute(route.id());
      if (stops.isEmpty()) {
        continue;
      }
      RouteStop first = stops.get(0);
      Optional<String> cret = SpawnDirectiveParser.findDirectiveTarget(first, "CRET");
      if (route.operationType() == RouteOperationType.CREATE && cret.isEmpty()) {
        debugLogger.accept(
            "SpawnPlan 跳过 CREATE route(缺少 CRET): line=" + line.code() + " route=" + route.code());
        continue;
      }
      boolean depotSpawn = cret.isPresent();
      String startNode = cret.orElseGet(() -> resolveStopNodeId(first));
      if (startNode == null || startNode.isBlank()) {
        continue;
      }
      Optional<Boolean> enabledFlag = readBoolean(route.metadata(), "spawn_enabled");
      Optional<Integer> weight = readInt(route.metadata(), "spawn_weight");
      RouteSelection selection =
          new RouteSelection(route, startNode.trim(), depotSpawn, enabledFlag, weight);
      if (route.operationType() == RouteOperationType.OPERATION) {
        operationCandidates.add(selection);
      } else {
        createCandidates.add(selection);
      }
    }
    if (operationCandidates.isEmpty() && createCandidates.isEmpty()) {
      return List.of();
    }

    List<RouteSelection> enabled = new ArrayList<>();

    // CREATE 路由不参与权重分摊，只受 spawn_enabled 开关控制。
    for (RouteSelection selection : createCandidates) {
      if (selection == null) {
        continue;
      }
      if (selection.spawnEnabledFlag().isPresent() && !selection.spawnEnabledFlag().get()) {
        continue;
      }
      enabled.add(selection.withResolvedWeight(1));
    }

    List<RouteSelection> enabledOperations =
        selectEnabledOperationRoutes(line, operationCandidates);

    enabled.addAll(enabledOperations);
    if (enabled.isEmpty()) {
      return List.of();
    }
    enabled.sort(Comparator.comparing(sel -> sel.route().code(), String.CASE_INSENSITIVE_ORDER));
    return enabled;
  }

  /**
   * 选择可参与自动发车的运营 route。
   *
   * <p>旧逻辑按整条线路判断“多 route 必须配置 spawn_weight”，会把“两个显式交路组、每组一条 route” 误判为歧义配置并跳过整条线路。这里把默认权重 1
   * 的放行范围缩小到单个显式交路组：同组多条运营 route 仍需要 {@code spawn_weight} 或 {@code spawn_enabled=true} 明确表达比例。
   */
  private List<RouteSelection> selectEnabledOperationRoutes(
      Line line, List<RouteSelection> operationCandidates) {
    if (operationCandidates == null || operationCandidates.isEmpty()) {
      return List.of();
    }
    if (operationCandidates.size() == 1) {
      return selectSingleOperationRoute(operationCandidates.get(0));
    }

    Map<String, Long> explicitGroupSizes =
        operationCandidates.stream()
            .filter(this::hasExplicitSpawnGroup)
            .collect(
                Collectors.groupingBy(
                    selection -> resolveCirculationGroupKey(line, selection),
                    Collectors.counting()));
    List<RouteSelection> enabledOperations = new ArrayList<>();
    boolean hasExplicitDisable = false;
    boolean hasExplicitWeightZero = false;
    List<RouteSelection> ambiguous = new ArrayList<>();

    for (RouteSelection selection : operationCandidates) {
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
        enabledOperations.add(selection.withResolvedWeight(w));
        continue;
      }
      if (selection.spawnEnabledFlag().orElse(false)) {
        enabledOperations.add(selection.withResolvedWeight(1));
        continue;
      }
      if (isSingleRouteInExplicitGroup(line, selection, explicitGroupSizes)) {
        enabledOperations.add(selection.withResolvedWeight(1));
      } else {
        ambiguous.add(selection);
      }
    }

    if (!enabledOperations.isEmpty()) {
      logSkippedOperationRoutes(line, ambiguous, hasExplicitDisable, hasExplicitWeightZero);
      return List.copyOf(enabledOperations);
    }
    logSkippedOperationRoutes(line, operationCandidates, hasExplicitDisable, hasExplicitWeightZero);
    return List.of();
  }

  private List<RouteSelection> selectSingleOperationRoute(RouteSelection selection) {
    if (selection == null) {
      return List.of();
    }
    if (selection.spawnEnabledFlag().isPresent() && !selection.spawnEnabledFlag().get()) {
      return List.of();
    }
    if (selection.spawnWeightRaw().isPresent()) {
      Integer weight = selection.spawnWeightRaw().get();
      if (weight == null || weight <= 0) {
        return List.of();
      }
      return List.of(selection.withResolvedWeight(weight));
    }
    return List.of(selection.withResolvedWeight(1));
  }

  private boolean isSingleRouteInExplicitGroup(
      Line line, RouteSelection selection, Map<String, Long> explicitGroupSizes) {
    if (!hasExplicitSpawnGroup(selection)) {
      return false;
    }
    if (explicitGroupSizes == null || explicitGroupSizes.isEmpty()) {
      return false;
    }
    String groupKey = resolveCirculationGroupKey(line, selection);
    return explicitGroupSizes.getOrDefault(groupKey, 0L) == 1L;
  }

  private boolean hasExplicitSpawnGroup(RouteSelection selection) {
    return selection != null
        && selection.route() != null
        && readString(selection.route().metadata(), "spawn_group").isPresent();
  }

  private void logSkippedOperationRoutes(
      Line line,
      List<RouteSelection> skipped,
      boolean hasExplicitDisable,
      boolean hasExplicitWeightZero) {
    if (skipped == null || skipped.isEmpty()) {
      return;
    }
    List<RouteSelection> sorted = new ArrayList<>(skipped);
    sorted.sort(Comparator.comparing(sel -> sel.route().code(), String.CASE_INSENSITIVE_ORDER));
    String routes = String.join(", ", sorted.stream().map(sel -> sel.route().code()).toList());
    if (hasExplicitDisable || hasExplicitWeightZero) {
      debugLogger.accept("SpawnPlan 跳过运营候选(已禁用或权重为 0): line=" + line.code() + " routes=" + routes);
      return;
    }
    debugLogger.accept(
        "SpawnPlan 跳过运营候选(未配置 spawn_weight 且不满足单 route 显式交路组): line="
            + line.code()
            + " routes="
            + routes);
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
      candidates.add(
          new RouteSelection(route, startNode.trim(), depotSpawn, enabledFlag, Optional.empty())
              .withResolvedWeight(1));
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
      int maxPollPerTick,
      Duration queuedTicketMaxAge) {
    public SpawnManagerSettings(
        Duration planRefreshInterval,
        Duration initialJitter,
        int maxBacklogPerService,
        int maxGeneratePerTick,
        int maxPollPerTick) {
      this(
          planRefreshInterval,
          initialJitter,
          maxBacklogPerService,
          maxGeneratePerTick,
          maxPollPerTick,
          Duration.ofDays(1));
    }

    public SpawnManagerSettings {
      planRefreshInterval =
          planRefreshInterval == null ? Duration.ofSeconds(10) : planRefreshInterval;
      initialJitter = initialJitter == null ? Duration.ZERO : initialJitter;
      queuedTicketMaxAge = queuedTicketMaxAge == null ? Duration.ofDays(1) : queuedTicketMaxAge;
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
      return new SpawnManagerSettings(
          Duration.ofSeconds(10), Duration.ZERO, 5, 5, 1, Duration.ofDays(1));
    }
  }
}
