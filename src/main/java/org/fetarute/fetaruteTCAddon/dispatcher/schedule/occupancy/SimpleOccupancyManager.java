package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.OccupancyAcquiredEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.OccupancyReleasedEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.SignalEventBus;

/**
 * 基于内存 Map 的占用管理器，适合作为“最小可用版本”。
 *
 * <p>占用释放由事件驱动触发：释放后即认为可重新判定，不依赖 releaseAt；headway 仅作为配置保留。
 *
 * <p>单线走廊冲突支持方向锁：同向允许多车跟驰，对向互斥。
 *
 * <p>道岔/单线冲突使用 Gate Queue 保障进入顺序，并基于 lookahead entryOrder 优先放行更接近冲突入口的列车。
 *
 * <p>当冲突区两侧车辆互相占用节点而卡死时，会尝试“冲突区放行”以释放队头列车进入。
 */
public final class SimpleOccupancyManager
    implements OccupancyManager, OccupancyQueueSupport, OccupancyPreviewSupport {

  private static final Duration QUEUE_ENTRY_TTL = Duration.ofSeconds(30);

  /** 冲突区放行锁定时长：一旦放行某车，在此期间内不允许对手车放行，避免信号乒乓。 */
  private static final Duration DEADLOCK_RELEASE_LOCK_TTL = Duration.ofSeconds(8);

  private final HeadwayRule headwayRule;
  private final SignalAspectPolicy signalPolicy;
  private final SignalEventBus eventBus;
  private final Map<OccupancyResource, List<OccupancyClaim>> claims = new LinkedHashMap<>();
  private final Map<OccupancyResource, ConflictQueue> queues = new LinkedHashMap<>();

  /** 冲突区放行锁：key=冲突资源 key，value=被放行列车与锁定过期时间。 */
  private final Map<String, DeadlockReleaseLock> deadlockReleaseLocks = new LinkedHashMap<>();

  /**
   * 构建占用管理器（无事件总线）。
   *
   * @param headwayRule 追踪间隔策略
   * @param signalPolicy 信号优先级与放行策略
   */
  public SimpleOccupancyManager(HeadwayRule headwayRule, SignalAspectPolicy signalPolicy) {
    this(headwayRule, signalPolicy, null);
  }

  /**
   * 构建占用管理器。
   *
   * @param headwayRule 追踪间隔策略
   * @param signalPolicy 信号优先级与放行策略
   * @param eventBus 信号事件总线（可选，为 null 时不发布事件）
   */
  public SimpleOccupancyManager(
      HeadwayRule headwayRule, SignalAspectPolicy signalPolicy, SignalEventBus eventBus) {
    this.headwayRule = Objects.requireNonNull(headwayRule, "headwayRule");
    this.signalPolicy = signalPolicy != null ? signalPolicy : SignalAspectPolicy.defaultPolicy();
    this.eventBus = eventBus;
  }

  @Override
  /**
   * 预判是否允许进入指定资源集合（不写入状态）。
   *
   * <p>用于运行时“尝试放行”的决策预演。
   */
  public synchronized OccupancyDecision canEnter(OccupancyRequest request) {
    Objects.requireNonNull(request, "request");
    Instant now = request.now();
    purgeExpiredQueueEntries(now);
    List<OccupancyClaim> blockers = new ArrayList<>();
    Set<OccupancyResource> blockedResources = new LinkedHashSet<>();
    for (OccupancyResource resource : request.resourceList()) {
      if (resource == null) {
        continue;
      }
      List<OccupancyClaim> existing = claims.get(resource);
      if (existing == null || existing.isEmpty()) {
        continue;
      }
      for (OccupancyClaim claim : existing) {
        if (claim == null) {
          continue;
        }
        if (claim.trainName().equalsIgnoreCase(request.trainName())) {
          continue;
        }
        if (isSingleCorridorConflict(resource)
            && isSameCorridorDirection(request, resource, claim)) {
          continue;
        }
        blockers.add(claim);
        blockedResources.add(resource);
      }
    }
    if (!blockers.isEmpty()) {
      Set<OccupancyResource> queueTargets = resolveQueueTargets(request, blockedResources);
      enqueueWaiting(request, queueTargets, now);
      OccupancyDecision resolved = tryResolveConflictDeadlock(request, blockers, now);
      if (resolved != null) {
        return resolved;
      }
      return new OccupancyDecision(false, now, SignalAspect.STOP, List.copyOf(blockers));
    }

    boolean queueBlocked = false;
    for (OccupancyResource resource : request.resourceList()) {
      if (!isQueueableConflict(resource)) {
        continue;
      }
      if (findClaim(claims.get(resource), request.trainName()) != null) {
        continue;
      }
      CorridorDirection direction = queueDirectionFor(request, resource);
      ConflictQueue queue = queues.computeIfAbsent(resource, unused -> new ConflictQueue());
      queue.touch(
          request.trainName(),
          direction,
          now,
          request.priority(),
          queueEntryOrderFor(request, resource));
      if (!isQueueAllowed(request.trainName(), resource, direction, queue)) {
        queueBlocked = true;
        queue
            .blockingEntry(direction)
            .map(entry -> createQueueBlocker(resource, entry))
            .ifPresent(blockers::add);
      }
    }
    if (queueBlocked) {
      return new OccupancyDecision(false, now, SignalAspect.STOP, List.copyOf(blockers));
    }
    SignalAspect signal = signalPolicy.aspectForDelay(Duration.ZERO);
    return new OccupancyDecision(true, now, signal, List.copyOf(blockers));
  }

  @Override
  /**
   * 预览占用判定（不写入状态、不入队）。
   *
   * <p>用于 ETA 估算，避免对运行时队列造成副作用。
   */
  public synchronized OccupancyDecision canEnterPreview(OccupancyRequest request) {
    Objects.requireNonNull(request, "request");
    Instant now = request.now();
    List<OccupancyClaim> blockers = new ArrayList<>();
    for (OccupancyResource resource : request.resourceList()) {
      if (resource == null) {
        continue;
      }
      List<OccupancyClaim> existing = claims.get(resource);
      if (existing == null || existing.isEmpty()) {
        continue;
      }
      for (OccupancyClaim claim : existing) {
        if (claim == null) {
          continue;
        }
        if (claim.trainName().equalsIgnoreCase(request.trainName())) {
          continue;
        }
        if (isSingleCorridorConflict(resource)
            && isSameCorridorDirection(request, resource, claim)) {
          continue;
        }
        blockers.add(claim);
      }
    }
    if (!blockers.isEmpty()) {
      OccupancyDecision resolved = tryResolveConflictDeadlockPreview(request, blockers, now);
      if (resolved != null) {
        return resolved;
      }
      return new OccupancyDecision(false, now, SignalAspect.STOP, List.copyOf(blockers));
    }

    boolean queueBlocked = false;
    for (OccupancyResource resource : request.resourceList()) {
      if (!isQueueableConflict(resource)) {
        continue;
      }
      if (findClaim(claims.get(resource), request.trainName()) != null) {
        continue;
      }
      CorridorDirection direction = queueDirectionFor(request, resource);
      ConflictQueue queue = queues.get(resource);
      if (queue == null || queue.isEmpty()) {
        continue;
      }
      if (!isQueueAllowedPreview(
          request.trainName(),
          resource,
          direction,
          queue,
          request.priority(),
          queueEntryOrderFor(request, resource),
          now)) {
        queueBlocked = true;
        queue
            .blockingEntry(direction)
            .map(entry -> createQueueBlocker(resource, entry))
            .ifPresent(blockers::add);
      }
    }
    if (queueBlocked) {
      return new OccupancyDecision(false, now, SignalAspect.STOP, List.copyOf(blockers));
    }
    SignalAspect signal = signalPolicy.aspectForDelay(Duration.ZERO);
    return new OccupancyDecision(true, now, signal, List.copyOf(blockers));
  }

  @Override
  /**
   * 获取占用：将申请列车写入资源占用与队列状态。
   *
   * <p>若不可进入则返回拒绝决策，并写入排队快照供诊断使用。
   *
   * <p>成功获取后会发布 {@link OccupancyAcquiredEvent}，通知订阅者重新评估信号。
   */
  public synchronized OccupancyDecision acquire(OccupancyRequest request) {
    Objects.requireNonNull(request, "request");
    OccupancyDecision decision = canEnter(request);
    if (!decision.allowed()) {
      return decision;
    }
    Instant now = request.now();
    for (OccupancyResource resource : request.resourceList()) {
      if (resource == null) {
        continue;
      }
      Duration headway = headwayRule.headwayFor(request.routeId(), resource);
      List<OccupancyClaim> existing = claims.computeIfAbsent(resource, unused -> new ArrayList<>());
      OccupancyClaim current = findClaim(existing, request.trainName());
      Optional<CorridorDirection> direction = resolveCorridorDirection(request, resource);
      if (current != null) {
        Duration nextHeadway =
            current.headway().compareTo(headway) >= 0 ? current.headway() : headway;
        existing.remove(current);
        existing.add(
            new OccupancyClaim(
                resource,
                current.trainName(),
                request.routeId(),
                current.acquiredAt(),
                nextHeadway,
                direction));
        continue;
      }
      existing.add(
          new OccupancyClaim(
              resource, request.trainName(), request.routeId(), now, headway, direction));
    }
    removeFromQueuesForResources(request.trainName(), request.resourceList());
    // 发布占用获取事件
    publishAcquiredEvent(request, now);
    return decision;
  }

  @Override
  /**
   * 查询某资源当前占用（只返回首个占用者）。
   *
   * <p>用于诊断，不保证公平队列顺序。
   */
  public synchronized Optional<OccupancyClaim> getClaim(OccupancyResource resource) {
    if (resource == null) {
      return Optional.empty();
    }
    List<OccupancyClaim> list = claims.get(resource);
    if (list == null || list.isEmpty()) {
      return Optional.empty();
    }
    return Optional.ofNullable(list.get(0));
  }

  @Override
  /**
   * 获取全部占用快照。
   *
   * <p>用于诊断，不建议高频调用。
   */
  public synchronized List<OccupancyClaim> snapshotClaims() {
    List<OccupancyClaim> snapshot = new ArrayList<>();
    for (List<OccupancyClaim> list : claims.values()) {
      if (list == null || list.isEmpty()) {
        continue;
      }
      snapshot.addAll(list);
    }
    return List.copyOf(snapshot);
  }

  @Override
  /**
   * 获取排队快照。
   *
   * <p>用于诊断单线走廊方向与排队情况。
   */
  public synchronized List<OccupancyQueueSnapshot> snapshotQueues() {
    List<OccupancyQueueSnapshot> snapshots = new ArrayList<>();
    for (Map.Entry<OccupancyResource, ConflictQueue> entry : queues.entrySet()) {
      ConflictQueue queue = entry.getValue();
      if (queue == null || queue.isEmpty()) {
        continue;
      }
      OccupancyResource resource = entry.getKey();
      Optional<CorridorDirection> activeDirection = activeDirectionFor(resource);
      int activeClaims = claims.getOrDefault(resource, List.of()).size();
      snapshots.add(
          new OccupancyQueueSnapshot(
              resource, activeDirection, activeClaims, queue.snapshotEntries()));
    }
    return List.copyOf(snapshots);
  }

  @Override
  /**
   * 按列车名释放全部占用资源。
   *
   * <p>释放后会发布 {@link OccupancyReleasedEvent}，通知订阅者资源已可用。
   *
   * @return 实际释放的占用数量
   */
  public synchronized int releaseByTrain(String trainName) {
    if (trainName == null || trainName.isBlank()) {
      return 0;
    }
    int removed = 0;
    List<OccupancyResource> releasedResources = new ArrayList<>();
    Iterator<Map.Entry<OccupancyResource, List<OccupancyClaim>>> iterator =
        claims.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<OccupancyResource, List<OccupancyClaim>> entry = iterator.next();
      List<OccupancyClaim> list = entry.getValue();
      if (list == null || list.isEmpty()) {
        iterator.remove();
        continue;
      }
      Iterator<OccupancyClaim> claimIterator = list.iterator();
      while (claimIterator.hasNext()) {
        OccupancyClaim claim = claimIterator.next();
        if (claim != null && claim.trainName().equalsIgnoreCase(trainName)) {
          claimIterator.remove();
          releasedResources.add(entry.getKey());
          removed++;
        }
      }
      if (list.isEmpty()) {
        iterator.remove();
      }
    }
    removeFromQueuesForTrain(trainName);
    releaseDeadlockLocksForTrain(trainName);
    // 发布占用释放事件
    if (!releasedResources.isEmpty()) {
      publishReleasedEvent(trainName, releasedResources, Instant.now());
    }
    return removed;
  }

  @Override
  /**
   * 释放指定资源上的单个列车占用。
   *
   * <p>释放后会发布 {@link OccupancyReleasedEvent}，通知订阅者资源已可用。
   *
   * @return 是否存在并成功移除
   */
  public synchronized boolean releaseResource(
      OccupancyResource resource, Optional<String> trainName) {
    if (resource == null) {
      return false;
    }
    List<OccupancyClaim> list = claims.get(resource);
    if (list == null || list.isEmpty()) {
      return false;
    }
    if (trainName != null && trainName.isPresent()) {
      String expected = trainName.get();
      boolean removed =
          list.removeIf(claim -> claim != null && claim.trainName().equalsIgnoreCase(expected));
      if (list.isEmpty()) {
        claims.remove(resource);
      }
      removeFromQueuesForResources(expected, List.of(resource));
      // 发布占用释放事件
      if (removed) {
        publishReleasedEvent(expected, List.of(resource), Instant.now());
      }
      return removed;
    }
    claims.remove(resource);
    // 全量释放时，trainName 为空，使用占位符
    publishReleasedEvent("*", List.of(resource), Instant.now());
    return true;
  }

  @Override
  /**
   * 优先级让行判定：当冲突队列中存在更高优先级列车时返回 true。
   *
   * <p>仅针对单线走廊与道岔冲突资源；同向单线不触发让行。
   */
  public synchronized boolean shouldYield(OccupancyRequest request) {
    if (request == null || request.trainName() == null || request.trainName().isBlank()) {
      return false;
    }
    Instant now = request.now();
    purgeExpiredQueueEntries(now);
    int priority = request.priority();
    for (OccupancyResource resource : request.resourceList()) {
      if (!isQueueableConflict(resource)) {
        continue;
      }
      ConflictQueue queue = queues.get(resource);
      if (queue == null || queue.isEmpty()) {
        continue;
      }
      if (isSingleCorridorConflict(resource)) {
        Optional<CorridorDirection> direction = resolveCorridorDirection(request, resource);
        if (direction.isPresent()) {
          if (queue.hasHigherPriorityOutside(request.trainName(), direction.get(), priority, now)) {
            return true;
          }
        } else if (queue.hasHigherPriorityAny(request.trainName(), priority, now)) {
          return true;
        }
        continue;
      }
      if (queue.hasHigherPriorityAny(request.trainName(), priority, now)) {
        return true;
      }
    }
    return false;
  }

  // 事件反射式释放不需要“基于时间”的清理。

  private boolean isSameCorridorDirection(
      OccupancyRequest request, OccupancyResource resource, OccupancyClaim claim) {
    Optional<CorridorDirection> requestDirection = resolveCorridorDirection(request, resource);
    Optional<CorridorDirection> claimDirection = claim.corridorDirection();
    if (requestDirection.isEmpty() || claimDirection.isEmpty()) {
      return false;
    }
    return requestDirection.get() == claimDirection.get();
  }

  private Optional<CorridorDirection> resolveCorridorDirection(
      OccupancyRequest request, OccupancyResource resource) {
    if (request == null || resource == null) {
      return Optional.empty();
    }
    if (!isSingleCorridorConflict(resource)) {
      return Optional.empty();
    }
    CorridorDirection direction = request.corridorDirections().get(resource.key());
    if (direction == null || direction == CorridorDirection.UNKNOWN) {
      return Optional.empty();
    }
    return Optional.of(direction);
  }

  private boolean isSingleCorridorConflict(OccupancyResource resource) {
    return resource.kind() == ResourceKind.CONFLICT
        && resource.key().startsWith("single:")
        && !resource.key().contains(":cycle:");
  }

  private boolean isQueueableConflict(OccupancyResource resource) {
    if (resource == null || resource.kind() != ResourceKind.CONFLICT) {
      return false;
    }
    return resource.key().startsWith("single:") || resource.key().startsWith("switcher:");
  }

  private boolean isQueueAllowed(
      String trainName,
      OccupancyResource resource,
      CorridorDirection direction,
      ConflictQueue queue) {
    if (queue == null || queue.isEmpty()) {
      return true;
    }
    if (!resource.key().startsWith("single:") || resource.key().contains(":cycle:")) {
      return queue.isHeadAny(trainName);
    }
    Optional<CorridorDirection> activeDirection = activeDirectionFor(resource);
    if (activeDirection.isEmpty()) {
      return queue.isHeadAny(trainName);
    }
    CorridorDirection active = activeDirection.get();
    if (direction == CorridorDirection.UNKNOWN || direction != active) {
      return false;
    }
    return queue.isHeadForDirection(trainName, direction);
  }

  private boolean isQueueAllowedPreview(
      String trainName,
      OccupancyResource resource,
      CorridorDirection direction,
      ConflictQueue queue,
      int priority,
      int entryOrder,
      Instant now) {
    if (queue == null || queue.isEmpty()) {
      return true;
    }
    if (queue.contains(trainName)) {
      return isQueueAllowed(trainName, resource, direction, queue);
    }
    if (!resource.key().startsWith("single:") || resource.key().contains(":cycle:")) {
      return queue.wouldBeHeadAny(trainName, direction, priority, entryOrder, now);
    }
    Optional<CorridorDirection> activeDirection = activeDirectionFor(resource);
    if (activeDirection.isEmpty()) {
      return queue.wouldBeHeadAny(trainName, direction, priority, entryOrder, now);
    }
    CorridorDirection active = activeDirection.get();
    if (direction == CorridorDirection.UNKNOWN || direction != active) {
      return false;
    }
    return queue.wouldBeHeadForDirection(trainName, direction, priority, entryOrder, now);
  }

  private Optional<CorridorDirection> activeDirectionFor(OccupancyResource resource) {
    if (!resource.key().startsWith("single:") || resource.key().contains(":cycle:")) {
      return Optional.empty();
    }
    List<OccupancyClaim> list = claims.get(resource);
    if (list == null || list.isEmpty()) {
      return Optional.empty();
    }
    CorridorDirection current = null;
    for (OccupancyClaim claim : list) {
      if (claim == null || claim.corridorDirection().isEmpty()) {
        continue;
      }
      CorridorDirection direction = claim.corridorDirection().get();
      if (current == null) {
        current = direction;
        continue;
      }
      if (current != direction) {
        return Optional.empty();
      }
    }
    return Optional.ofNullable(current);
  }

  private CorridorDirection queueDirectionFor(
      OccupancyRequest request, OccupancyResource resource) {
    if (resource == null) {
      return CorridorDirection.UNKNOWN;
    }
    if (!resource.key().startsWith("single:") || resource.key().contains(":cycle:")) {
      return CorridorDirection.UNKNOWN;
    }
    CorridorDirection direction = request.corridorDirections().get(resource.key());
    if (direction == null) {
      return CorridorDirection.UNKNOWN;
    }
    return direction;
  }

  private int queueEntryOrderFor(OccupancyRequest request, OccupancyResource resource) {
    if (request == null || resource == null) {
      return Integer.MAX_VALUE;
    }
    Integer order = request.conflictEntryOrders().get(resource.key());
    if (order == null || order < 0) {
      return Integer.MAX_VALUE;
    }
    return order;
  }

  private Set<OccupancyResource> resolveQueueTargets(
      OccupancyRequest request, Set<OccupancyResource> blockedResources) {
    Set<OccupancyResource> targets = new LinkedHashSet<>();
    if (blockedResources != null) {
      for (OccupancyResource resource : blockedResources) {
        if (isQueueableConflict(resource)) {
          targets.add(resource);
        }
      }
    }
    if (!targets.isEmpty()) {
      return targets;
    }
    resolvePrimaryConflict(request).ifPresent(targets::add);
    return targets;
  }

  private Optional<OccupancyResource> resolvePrimaryConflict(OccupancyRequest request) {
    if (request == null) {
      return Optional.empty();
    }
    Map<String, Integer> entryOrders = request.conflictEntryOrders();
    if (entryOrders != null && !entryOrders.isEmpty()) {
      String bestKey = null;
      int bestOrder = Integer.MAX_VALUE;
      for (Map.Entry<String, Integer> entry : entryOrders.entrySet()) {
        if (entry == null || entry.getKey() == null) {
          continue;
        }
        int order = entry.getValue() != null ? entry.getValue() : Integer.MAX_VALUE;
        if (order < bestOrder) {
          bestOrder = order;
          bestKey = entry.getKey();
        }
      }
      if (bestKey != null) {
        OccupancyResource conflict = OccupancyResource.forConflict(bestKey);
        if (isQueueableConflict(conflict)) {
          return Optional.of(conflict);
        }
      }
    }
    for (OccupancyResource resource : request.resourceList()) {
      if (isQueueableConflict(resource)) {
        return Optional.of(resource);
      }
    }
    return Optional.empty();
  }

  private boolean containsConflictBlocker(List<OccupancyClaim> blockers) {
    if (blockers == null || blockers.isEmpty()) {
      return false;
    }
    for (OccupancyClaim claim : blockers) {
      if (claim == null || claim.resource() == null) {
        continue;
      }
      if (claim.resource().kind() == ResourceKind.CONFLICT) {
        return true;
      }
    }
    return false;
  }

  /**
   * 校验阻塞列车是否都在同一冲突队列内。
   *
   * <p>用于死锁放行锁生效时的安全校验：允许跳过“队头判断”，但仍需确保阻塞来源来自同一冲突队列。
   */
  private boolean areBlockersInQueue(
      List<OccupancyClaim> blockers, ConflictQueue queue, String trainName) {
    if (blockers == null || blockers.isEmpty() || queue == null) {
      return false;
    }
    for (OccupancyClaim claim : blockers) {
      if (claim == null || claim.trainName() == null) {
        continue;
      }
      if (trainName != null && claim.trainName().equalsIgnoreCase(trainName)) {
        continue;
      }
      if (!queue.contains(claim.trainName())) {
        return false;
      }
    }
    return true;
  }

  /**
   * 冲突区放行：当两侧列车互相占用节点导致阻塞时，尝试放行**全局队头**列车进入冲突区。
   *
   * <p>采用"单侧放行 + 稳定性锁定"策略：
   *
   * <ul>
   *   <li>仅放行全局队头（不区分方向的最早到达者），另一侧必须等待
   *   <li>一旦放行某车，锁定该决策一段时间，避免信号乒乓
   *   <li>锁定期间对手车请求直接拒绝
   * </ul>
   *
   * <p>仅当不存在冲突资源占用，且阻塞列车均在同一冲突队列中时生效。
   */
  private OccupancyDecision tryResolveConflictDeadlock(
      OccupancyRequest request, List<OccupancyClaim> blockers, Instant now) {
    if (request == null || blockers == null || blockers.isEmpty()) {
      return null;
    }
    if (containsConflictBlocker(blockers)) {
      return null;
    }
    // 优先检查：列车是否持有任意冲突资源的放行锁（避免主冲突切换导致信号乒乓）
    OccupancyDecision heldLockDecision = tryResolveByHeldLock(request, blockers, now);
    if (heldLockDecision != null) {
      return heldLockDecision;
    }
    Optional<OccupancyResource> conflictOpt = resolvePrimaryConflict(request);
    if (conflictOpt.isEmpty()) {
      return null;
    }
    OccupancyResource conflict = conflictOpt.get();
    ConflictQueue queue = queues.get(conflict);
    if (queue == null || queue.isEmpty()) {
      return null;
    }
    // 检查是否有其他车持有该冲突的锁
    DeadlockReleaseLock existingLock = deadlockReleaseLocks.get(conflict.key());
    if (existingLock != null && !existingLock.isExpired(now)) {
      if (!existingLock.matches(request.trainName())) {
        // 其他车持有锁，当前车必须等待
        return null;
      }
      // 当前车持有锁（已在 tryResolveByHeldLock 处理，理论上不会到这里）
    }
    // 单侧放行优先：必须是全局队头才能触发冲突放行
    if (!queue.isHeadAny(request.trainName())) {
      return null;
    }
    if (!areBlockersInQueue(blockers, queue, request.trainName())) {
      return null;
    }
    // 放行并写入锁定
    Instant expiresAt = now.plus(DEADLOCK_RELEASE_LOCK_TTL);
    deadlockReleaseLocks.put(
        conflict.key(), new DeadlockReleaseLock(request.trainName(), expiresAt));
    SignalAspect signal = signalPolicy.aspectForDelay(Duration.ZERO);
    return new OccupancyDecision(true, now, signal, List.copyOf(blockers));
  }

  /**
   * 检查列车是否持有任意冲突资源的有效放行锁。
   *
   * <p>当列车请求多个冲突资源时，每次 tick 的"主冲突"可能因 blockers 变化而切换。 此方法在确定主冲突之前先扫描所有请求的冲突资源，
   * 若列车持有任意一个有效锁，则直接放行，避免因主冲突切换导致的信号乒乓。
   */
  private OccupancyDecision tryResolveByHeldLock(
      OccupancyRequest request, List<OccupancyClaim> blockers, Instant now) {
    Map<String, Integer> entryOrders = request.conflictEntryOrders();
    if (entryOrders == null || entryOrders.isEmpty()) {
      return null;
    }
    for (String conflictKey : entryOrders.keySet()) {
      DeadlockReleaseLock lock = deadlockReleaseLocks.get(conflictKey);
      if (lock == null || lock.isExpired(now)) {
        continue;
      }
      if (!lock.matches(request.trainName())) {
        // 其他车持有该冲突的锁，当前车不能被任何锁放行
        continue;
      }
      // 当前车持有该冲突的锁：直接放行。
      // 注意：不再检查 areBlockersInQueue，因为：
      // 1. 锁在创建时已验证过阻塞来源
      // 2. 锁有效期内阻塞列车可能已离开队列（如推进到下一站），但锁本身就是放行依据
      // 3. 过度校验会导致持锁列车因 blocker 变化而被拒绝，产生信号乒乓
      SignalAspect signal = signalPolicy.aspectForDelay(Duration.ZERO);
      return new OccupancyDecision(true, now, signal, List.copyOf(blockers));
    }
    return null;
  }

  /**
   * 预览模式下的冲突区放行：不写入锁定/队列，仅基于现有队列状态与 entryOrder 判断是否可放行。
   *
   * <p>用于 ETA/候选站台评估，避免预览逻辑与真实放行决策偏离。
   */
  private OccupancyDecision tryResolveConflictDeadlockPreview(
      OccupancyRequest request, List<OccupancyClaim> blockers, Instant now) {
    if (request == null || blockers == null || blockers.isEmpty()) {
      return null;
    }
    if (containsConflictBlocker(blockers)) {
      return null;
    }
    Optional<OccupancyResource> conflictOpt = resolvePrimaryConflict(request);
    if (conflictOpt.isEmpty()) {
      return null;
    }
    OccupancyResource conflict = conflictOpt.get();
    ConflictQueue queue = queues.get(conflict);
    if (queue == null || queue.isEmpty()) {
      return null;
    }
    // 检查放行锁（只读）
    DeadlockReleaseLock existingLock = deadlockReleaseLocks.get(conflict.key());
    if (existingLock != null && !existingLock.isExpired(now)) {
      if (!existingLock.matches(request.trainName())) {
        return null;
      }
      // 当前车持有锁：跳过队头判断，但仍需确保阻塞来源在同一队列中。
      if (!areBlockersInQueue(blockers, queue, request.trainName())) {
        return null;
      }
      SignalAspect signal = signalPolicy.aspectForDelay(Duration.ZERO);
      return new OccupancyDecision(true, now, signal, List.copyOf(blockers));
    }
    // 单侧放行优先：必须是全局队头才能触发冲突放行
    CorridorDirection direction = queueDirectionFor(request, conflict);
    if (!queue.wouldBeHeadAny(
        request.trainName(),
        direction,
        request.priority(),
        queueEntryOrderFor(request, conflict),
        now)) {
      return null;
    }
    if (!areBlockersInQueue(blockers, queue, request.trainName())) {
      return null;
    }
    SignalAspect signal = signalPolicy.aspectForDelay(Duration.ZERO);
    return new OccupancyDecision(true, now, signal, List.copyOf(blockers));
  }

  private void enqueueWaiting(
      OccupancyRequest request, Set<OccupancyResource> queueTargets, Instant now) {
    if (queueTargets == null || queueTargets.isEmpty()) {
      return;
    }
    for (OccupancyResource resource : queueTargets) {
      if (!isQueueableConflict(resource)) {
        continue;
      }
      CorridorDirection direction = queueDirectionFor(request, resource);
      ConflictQueue queue = queues.computeIfAbsent(resource, unused -> new ConflictQueue());
      queue.touch(
          request.trainName(),
          direction,
          now,
          request.priority(),
          queueEntryOrderFor(request, resource));
    }
  }

  private void purgeExpiredQueueEntries(Instant now) {
    if (now == null) {
      return;
    }
    // 清理过期的冲突放行锁
    if (!deadlockReleaseLocks.isEmpty()) {
      deadlockReleaseLocks
          .entrySet()
          .removeIf(e -> e.getValue() == null || e.getValue().isExpired(now));
    }
    // 清理过期的队列条目
    if (queues.isEmpty()) {
      return;
    }
    Iterator<Map.Entry<OccupancyResource, ConflictQueue>> iterator = queues.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<OccupancyResource, ConflictQueue> entry = iterator.next();
      ConflictQueue queue = entry.getValue();
      if (queue == null) {
        iterator.remove();
        continue;
      }
      queue.purgeExpired(now, QUEUE_ENTRY_TTL);
      if (queue.isEmpty()) {
        iterator.remove();
      }
    }
  }

  private void removeFromQueuesForResources(String trainName, List<OccupancyResource> resources) {
    if (trainName == null || trainName.isBlank()) {
      return;
    }
    if (resources == null || resources.isEmpty()) {
      removeFromQueuesForTrain(trainName);
      return;
    }
    for (OccupancyResource resource : resources) {
      ConflictQueue queue = queues.get(resource);
      if (queue == null) {
        continue;
      }
      queue.remove(trainName);
      if (queue.isEmpty()) {
        queues.remove(resource);
      }
    }
  }

  private void removeFromQueuesForTrain(String trainName) {
    if (trainName == null || trainName.isBlank()) {
      return;
    }
    Iterator<Map.Entry<OccupancyResource, ConflictQueue>> iterator = queues.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<OccupancyResource, ConflictQueue> entry = iterator.next();
      ConflictQueue queue = entry.getValue();
      if (queue == null) {
        iterator.remove();
        continue;
      }
      queue.remove(trainName);
      if (queue.isEmpty()) {
        iterator.remove();
      }
    }
  }

  /** 释放指定列车持有的冲突区放行锁。 */
  private void releaseDeadlockLocksForTrain(String trainName) {
    if (trainName == null || trainName.isBlank() || deadlockReleaseLocks.isEmpty()) {
      return;
    }
    deadlockReleaseLocks
        .entrySet()
        .removeIf(e -> e.getValue() != null && e.getValue().matches(trainName));
  }

  private OccupancyClaim createQueueBlocker(OccupancyResource resource, OccupancyQueueEntry entry) {
    Optional<CorridorDirection> direction =
        entry.direction() == CorridorDirection.UNKNOWN
            ? Optional.empty()
            : Optional.of(entry.direction());
    return new OccupancyClaim(
        resource, entry.trainName(), Optional.empty(), entry.firstSeen(), Duration.ZERO, direction);
  }

  private OccupancyClaim findClaim(List<OccupancyClaim> list, String trainName) {
    if (list == null || trainName == null) {
      return null;
    }
    for (OccupancyClaim claim : list) {
      if (claim != null && claim.trainName().equalsIgnoreCase(trainName)) {
        return claim;
      }
    }
    return null;
  }

  // ========== 事件发布 ==========

  /**
   * 发布占用获取事件。
   *
   * <p>收集受影响的列车（等待这些资源的列车），通知它们重新评估信号。
   */
  private void publishAcquiredEvent(OccupancyRequest request, Instant now) {
    if (eventBus == null) {
      return;
    }
    List<String> affectedTrains =
        collectAffectedTrains(request.resourceList(), request.trainName());
    OccupancyAcquiredEvent event =
        new OccupancyAcquiredEvent(
            now, request.trainName(), request.resourceList(), affectedTrains);
    eventBus.publish(event);
  }

  /** 发布占用释放事件。 */
  private void publishReleasedEvent(
      String trainName, List<OccupancyResource> resources, Instant now) {
    if (eventBus == null) {
      return;
    }
    OccupancyReleasedEvent event = new OccupancyReleasedEvent(now, trainName, resources);
    eventBus.publish(event);
  }

  /**
   * 收集等待指定资源的列车名单（排除自己）。
   *
   * <p>这些列车是占用变化的"受影响方"，需要重新评估信号。
   */
  private List<String> collectAffectedTrains(
      List<OccupancyResource> resources, String excludeTrain) {
    Set<String> affected = new LinkedHashSet<>();
    for (OccupancyResource resource : resources) {
      if (resource == null) {
        continue;
      }
      // 从队列中收集等待该资源的列车
      ConflictQueue queue = queues.get(resource);
      if (queue != null) {
        affected.addAll(queue.allTrainNames());
      }
      // 从现有占用中收集（用于冲突检测）
      List<OccupancyClaim> existing = claims.get(resource);
      if (existing != null) {
        for (OccupancyClaim claim : existing) {
          if (claim != null && claim.trainName() != null) {
            affected.add(claim.trainName());
          }
        }
      }
    }
    // 排除自己
    if (excludeTrain != null) {
      affected.remove(excludeTrain);
      // 忽略大小写移除
      affected.removeIf(name -> name.equalsIgnoreCase(excludeTrain));
    }
    return new ArrayList<>(affected);
  }

  private static final class ConflictQueue {

    private final LinkedHashMap<String, OccupancyQueueEntry> forward = new LinkedHashMap<>();
    private final LinkedHashMap<String, OccupancyQueueEntry> backward = new LinkedHashMap<>();
    private final LinkedHashMap<String, OccupancyQueueEntry> neutral = new LinkedHashMap<>();
    private final Map<String, Integer> priorities = new java.util.HashMap<>();
    private final Map<String, Integer> entryOrders = new java.util.HashMap<>();

    void touch(
        String trainName, CorridorDirection direction, Instant now, int priority, int entryOrder) {
      if (trainName == null || trainName.isBlank() || now == null) {
        return;
      }
      String key = normalize(trainName);
      priorities.put(key, priority);
      int stableEntryOrder = resolveStableEntryOrder(key, entryOrder);
      entryOrders.put(key, stableEntryOrder);
      LinkedHashMap<String, OccupancyQueueEntry> target = mapFor(direction);
      OccupancyQueueEntry existing = target.get(key);
      if (existing == null) {
        target.put(
            key,
            new OccupancyQueueEntry(trainName, direction, now, now, priority, stableEntryOrder));
        return;
      }
      target.put(
          key,
          new OccupancyQueueEntry(
              existing.trainName(),
              direction,
              existing.firstSeen(),
              now,
              priority,
              stableEntryOrder));
    }

    void remove(String trainName) {
      if (trainName == null || trainName.isBlank()) {
        return;
      }
      String key = normalize(trainName);
      forward.remove(key);
      backward.remove(key);
      neutral.remove(key);
      priorities.remove(key);
      entryOrders.remove(key);
    }

    boolean contains(String trainName) {
      if (trainName == null || trainName.isBlank()) {
        return false;
      }
      String key = normalize(trainName);
      return forward.containsKey(key) || backward.containsKey(key) || neutral.containsKey(key);
    }

    /** 获取队列中所有列车名。 */
    Set<String> allTrainNames() {
      Set<String> names = new LinkedHashSet<>();
      for (OccupancyQueueEntry entry : forward.values()) {
        names.add(entry.trainName());
      }
      for (OccupancyQueueEntry entry : backward.values()) {
        names.add(entry.trainName());
      }
      for (OccupancyQueueEntry entry : neutral.values()) {
        names.add(entry.trainName());
      }
      return names;
    }

    boolean isHeadForDirection(String trainName, CorridorDirection direction) {
      if (trainName == null || trainName.isBlank()) {
        return false;
      }
      return headEntry(direction)
          .map(entry -> entry.trainName().equalsIgnoreCase(trainName))
          .orElse(false);
    }

    boolean isHeadAny(String trainName) {
      if (trainName == null || trainName.isBlank()) {
        return false;
      }
      return headAny().map(entry -> entry.trainName().equalsIgnoreCase(trainName)).orElse(false);
    }

    Optional<OccupancyQueueEntry> headEntry(CorridorDirection direction) {
      LinkedHashMap<String, OccupancyQueueEntry> target = mapFor(direction);
      if (target.isEmpty()) {
        return Optional.empty();
      }
      // 排序规则：priority 倒序，其次 entryOrder 正序，再次 firstSeen 正序
      return target.values().stream().sorted(this::compareEntries).findFirst();
    }

    Optional<OccupancyQueueEntry> headEntryWithCandidate(
        CorridorDirection direction,
        OccupancyQueueEntry candidate,
        int candidatePriority,
        int candidateEntryOrder) {
      LinkedHashMap<String, OccupancyQueueEntry> target = mapFor(direction);
      OccupancyQueueEntry best = null;
      int bestPriority = 0;
      int bestEntryOrder = Integer.MAX_VALUE;
      for (OccupancyQueueEntry entry : target.values()) {
        int priority = priorities.getOrDefault(normalize(entry.trainName()), 0);
        int entryOrder = entryOrderFor(entry.trainName());
        if (best == null
            || compareEntries(entry, priority, entryOrder, best, bestPriority, bestEntryOrder)
                < 0) {
          best = entry;
          bestPriority = priority;
          bestEntryOrder = entryOrder;
        }
      }
      if (candidate != null) {
        if (best == null
            || compareEntries(
                    candidate,
                    candidatePriority,
                    candidateEntryOrder,
                    best,
                    bestPriority,
                    bestEntryOrder)
                < 0) {
          best = candidate;
        }
      }
      return Optional.ofNullable(best);
    }

    boolean wouldBeHeadForDirection(
        String trainName, CorridorDirection direction, int priority, int entryOrder, Instant now) {
      if (trainName == null || trainName.isBlank()) {
        return false;
      }
      Instant time = now != null ? now : Instant.now();
      OccupancyQueueEntry candidate =
          new OccupancyQueueEntry(trainName, direction, time, time, priority, entryOrder);
      return headEntryWithCandidate(direction, candidate, priority, entryOrder)
          .map(entry -> entry.trainName().equalsIgnoreCase(trainName))
          .orElse(false);
    }

    boolean wouldBeHeadAny(
        String trainName, CorridorDirection direction, int priority, int entryOrder, Instant now) {
      if (trainName == null || trainName.isBlank()) {
        return false;
      }
      Instant time = now != null ? now : Instant.now();
      OccupancyQueueEntry candidate =
          new OccupancyQueueEntry(trainName, direction, time, time, priority, entryOrder);
      Optional<OccupancyQueueEntry> head =
          pickEarlier(
              headEntryWithCandidate(
                  CorridorDirection.A_TO_B,
                  direction == CorridorDirection.A_TO_B ? candidate : null,
                  priority,
                  entryOrder),
              headEntryWithCandidate(
                  CorridorDirection.B_TO_A,
                  direction == CorridorDirection.B_TO_A ? candidate : null,
                  priority,
                  entryOrder));
      head =
          pickEarlier(
              head,
              headEntryWithCandidate(
                  CorridorDirection.UNKNOWN,
                  direction == CorridorDirection.UNKNOWN ? candidate : null,
                  priority,
                  entryOrder));
      return head.map(entry -> entry.trainName().equalsIgnoreCase(trainName)).orElse(false);
    }

    private int compareEntries(OccupancyQueueEntry a, OccupancyQueueEntry b) {
      int pA = priorities.getOrDefault(normalize(a.trainName()), 0);
      int pB = priorities.getOrDefault(normalize(b.trainName()), 0);
      if (pA != pB) {
        return Integer.compare(pB, pA); // 优先级更高者优先
      }
      int oA = entryOrderFor(a.trainName());
      int oB = entryOrderFor(b.trainName());
      if (oA != oB) {
        return Integer.compare(oA, oB); // entry 更近者优先
      }
      return a.firstSeen().compareTo(b.firstSeen()); // 首见更早者优先（FIFO）
    }

    private int compareEntries(
        OccupancyQueueEntry a,
        int priorityA,
        int entryOrderA,
        OccupancyQueueEntry b,
        int priorityB,
        int entryOrderB) {
      if (priorityA != priorityB) {
        return Integer.compare(priorityB, priorityA);
      }
      if (entryOrderA != entryOrderB) {
        return Integer.compare(entryOrderA, entryOrderB);
      }
      return a.firstSeen().compareTo(b.firstSeen());
    }

    private int resolveStableEntryOrder(String key, int entryOrder) {
      if (key == null || key.isBlank()) {
        return entryOrder;
      }
      Integer existing = entryOrders.get(key);
      if (existing == null) {
        return entryOrder;
      }
      return Math.min(existing, entryOrder);
    }

    boolean hasHigherPriorityAny(String trainName, int priority, Instant now) {
      if (trainName == null || trainName.isBlank()) {
        return false;
      }
      String key = normalize(trainName);
      java.util.Set<String> keys = new java.util.HashSet<>();
      keys.addAll(forward.keySet());
      keys.addAll(backward.keySet());
      keys.addAll(neutral.keySet());
      for (String otherKey : keys) {
        if (otherKey == null || otherKey.equals(key)) {
          continue;
        }
        int otherPriority = priorities.getOrDefault(otherKey, 0);
        if (otherPriority > priority) {
          return true;
        }
      }
      return false;
    }

    boolean hasHigherPriorityOutside(
        String trainName, CorridorDirection direction, int priority, Instant now) {
      if (trainName == null || trainName.isBlank()) {
        return false;
      }
      String key = normalize(trainName);
      List<LinkedHashMap<String, OccupancyQueueEntry>> targets =
          List.of(forward, backward, neutral);
      LinkedHashMap<String, OccupancyQueueEntry> same = mapFor(direction);
      for (LinkedHashMap<String, OccupancyQueueEntry> target : targets) {
        if (target == same) {
          continue;
        }
        for (String otherKey : target.keySet()) {
          if (otherKey == null || otherKey.equals(key)) {
            continue;
          }
          int otherPriority = priorities.getOrDefault(otherKey, 0);
          if (otherPriority > priority) {
            return true;
          }
        }
      }
      return false;
    }

    Optional<OccupancyQueueEntry> blockingEntry(CorridorDirection direction) {
      if (direction == CorridorDirection.UNKNOWN) {
        return headAny();
      }
      Optional<OccupancyQueueEntry> direct = headEntry(direction);
      if (direct.isPresent()) {
        return direct;
      }
      return headAny();
    }

    Optional<OccupancyQueueEntry> headAny() {
      Optional<OccupancyQueueEntry> candidate = headEntry(CorridorDirection.A_TO_B);
      candidate = pickEarlier(candidate, headEntry(CorridorDirection.B_TO_A));
      candidate = pickEarlier(candidate, headEntry(CorridorDirection.UNKNOWN));
      return candidate;
    }

    boolean isEmpty() {
      return forward.isEmpty() && backward.isEmpty() && neutral.isEmpty();
    }

    List<OccupancyQueueEntry> snapshotEntries() {
      List<OccupancyQueueEntry> entries = new ArrayList<>();
      entries.addAll(forward.values());
      entries.addAll(backward.values());
      entries.addAll(neutral.values());
      entries.sort(this::compareEntries);
      return List.copyOf(entries);
    }

    void purgeExpired(Instant now, Duration ttl) {
      if (now == null || ttl == null || ttl.isNegative()) {
        return;
      }
      purgeExpired(forward, now, ttl);
      purgeExpired(backward, now, ttl);
      purgeExpired(neutral, now, ttl);
    }

    private void purgeExpired(
        LinkedHashMap<String, OccupancyQueueEntry> map, Instant now, Duration ttl) {
      Iterator<Map.Entry<String, OccupancyQueueEntry>> iterator = map.entrySet().iterator();
      while (iterator.hasNext()) {
        Map.Entry<String, OccupancyQueueEntry> entry = iterator.next();
        OccupancyQueueEntry value = entry.getValue();
        if (value == null) {
          iterator.remove();
          continue;
        }
        if (value.lastSeen().plus(ttl).isBefore(now)) {
          iterator.remove();
        }
      }
    }

    private LinkedHashMap<String, OccupancyQueueEntry> mapFor(CorridorDirection direction) {
      if (direction == CorridorDirection.B_TO_A) {
        return backward;
      }
      if (direction == CorridorDirection.A_TO_B) {
        return forward;
      }
      return neutral;
    }

    private static String normalize(String trainName) {
      return trainName.trim().toLowerCase(Locale.ROOT);
    }

    private int entryOrderFor(String trainName) {
      if (trainName == null || trainName.isBlank()) {
        return Integer.MAX_VALUE;
      }
      return entryOrders.getOrDefault(normalize(trainName), Integer.MAX_VALUE);
    }

    private Optional<OccupancyQueueEntry> pickEarlier(
        Optional<OccupancyQueueEntry> current, Optional<OccupancyQueueEntry> candidate) {
      if (candidate == null || candidate.isEmpty()) {
        return current;
      }
      if (current == null || current.isEmpty()) {
        return candidate;
      }
      // 跨方向比较也要遵循 priority 优先，其次 FIFO（与 headEntry 的规则一致）。
      if (compareEntries(candidate.get(), current.get()) < 0) {
        return candidate;
      }
      return current;
    }
  }

  /**
   * 冲突区放行锁：记录被放行的列车与锁定过期时间。
   *
   * @param trainName 被放行的列车名
   * @param expiresAt 锁定过期时间
   */
  private record DeadlockReleaseLock(String trainName, Instant expiresAt) {
    boolean isExpired(Instant now) {
      return now != null && now.isAfter(expiresAt);
    }

    boolean matches(String name) {
      return trainName != null && trainName.equalsIgnoreCase(name);
    }
  }
}
