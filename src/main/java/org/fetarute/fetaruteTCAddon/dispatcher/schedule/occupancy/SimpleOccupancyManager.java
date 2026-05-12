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
import java.util.concurrent.atomic.AtomicLong;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.SignalComputationTrace;
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
 * <p>这个实现只负责“资源互斥 + 队列公平性 + 冲突区放行”，不承担列车控车、恢复和调度重排。若上层信号看起来不稳定， 这里优先排查的通常是队列位次、锁定边界和 claim
 * 释放粒度，而不是时刻表本身。
 *
 * <p>冲突区放行只处理已证明正在清空冲突区出口的 CONFLICT blocker；真实 NODE/EDGE 硬占用始终按 STOP 处理。
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
  private final AtomicLong version = new AtomicLong();
  private final AtomicLong staleQueueCleanupCount = new AtomicLong();

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

  /** 返回占用/队列快照版本。claim 或 queue 发生真实变更时递增。 */
  public long version() {
    return version.get();
  }

  /** 返回因 TTL 清理的 queue entry 数量。 */
  public long staleQueueCleanupCount() {
    return staleQueueCleanupCount.get();
  }

  /**
   * 预判是否允许进入指定资源集合（不写入状态）。
   *
   * <p>用于运行时”尝试放行”的决策预演。
   */
  @Override
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
      boolean hardAuthority = request.intentFor(resource).hardAuthority();
      if (hardAuthority) {
        Optional<OccupancyDecision> directionBlocked =
            failClosedUnknownSingleConflictEntry(request, resource, now);
        if (directionBlocked.isPresent()) {
          return traceDecision("canEnter:unknown-single", request, directionBlocked.get());
        }
      }
      List<OccupancyClaim> existing = claims.get(resource);
      if (existing == null || existing.isEmpty()) {
        continue;
      }
      for (OccupancyClaim claim : existing) {
        if (claim == null) {
          continue;
        }
        BlockerRelation relation = BlockerClassifier.classify(request, resource, claim);
        if (relation == BlockerRelation.SELF) {
          continue;
        }
        if (!hardAuthority || relation == BlockerRelation.SAME_DIRECTION_FRONT) {
          continue;
        }
        blockers.add(claim);
        blockedResources.add(resource);
      }
    }
    if (!blockers.isEmpty()) {
      Set<OccupancyResource> queueTargets = resolveQueueTargets(request, blockedResources);
      enqueueWaiting(request, queueTargets, now);
      Optional<String> hardBlockerReason = conflictReleaseHardBlockerReason(request, blockers);
      if (hardBlockerReason.isPresent()) {
        return traceDecision(
            "canEnter:" + hardBlockerReason.get(),
            request,
            new OccupancyDecision(
                false,
                now,
                SignalAspect.STOP,
                List.copyOf(blockers),
                false,
                hardBlockerReason.get()));
      }
      OccupancyDecision resolved = tryResolveConflictDeadlock(request, blockers, now);
      if (resolved != null) {
        return traceDecision("canEnter:conflict-release", request, resolved);
      }
      return traceDecision(
          "canEnter:blockers",
          request,
          new OccupancyDecision(false, now, SignalAspect.STOP, List.copyOf(blockers)));
    }

    boolean queueBlocked = false;
    for (OccupancyResource resource : request.resourceList()) {
      if (!request.intentFor(resource).hardAuthority() || !isQueueableConflict(resource)) {
        continue;
      }
      if (findClaim(claims.get(resource), request.trainName()) != null) {
        continue;
      }
      Optional<OccupancyDecision> directionBlocked =
          failClosedUnknownSingleConflictEntry(request, resource, now);
      if (directionBlocked.isPresent()) {
        return traceDecision("canEnter:unknown-single", request, directionBlocked.get());
      }
      CorridorDirection direction = queueDirectionFor(request, resource);
      ConflictQueue queue = queues.computeIfAbsent(resource, unused -> new ConflictQueue());
      queue.touch(
          request.trainName(),
          direction,
          now,
          request.priority(),
          queueEntryOrderFor(request, resource));
      version.incrementAndGet();
      if (!isQueueAllowed(request.trainName(), resource, direction, queue)) {
        queueBlocked = true;
        queue
            .blockingEntry(direction)
            .map(entry -> createQueueBlocker(resource, entry))
            .ifPresent(blockers::add);
      }
    }
    if (queueBlocked) {
      return traceDecision(
          "canEnter:queue-blocked",
          request,
          new OccupancyDecision(false, now, SignalAspect.STOP, List.copyOf(blockers)));
    }
    SignalAspect signal = signalPolicy.aspectForDelay(Duration.ZERO);
    return traceDecision(
        "canEnter:allowed",
        request,
        new OccupancyDecision(true, now, signal, List.copyOf(blockers)));
  }

  /**
   * 预览占用判定（不写入状态、不入队）。
   *
   * <p>用于 ETA 估算，避免对运行时队列造成副作用。
   */
  @Override
  public synchronized OccupancyDecision canEnterPreview(OccupancyRequest request) {
    Objects.requireNonNull(request, "request");
    Instant now = request.now();
    List<OccupancyClaim> blockers = new ArrayList<>();
    for (OccupancyResource resource : request.resourceList()) {
      if (resource == null) {
        continue;
      }
      boolean hardAuthority = request.intentFor(resource).hardAuthority();
      if (hardAuthority) {
        Optional<OccupancyDecision> directionBlocked =
            failClosedUnknownSingleConflictEntry(request, resource, now);
        if (directionBlocked.isPresent()) {
          return traceDecision("canEnterPreview:unknown-single", request, directionBlocked.get());
        }
      }
      List<OccupancyClaim> existing = claims.get(resource);
      if (existing == null || existing.isEmpty()) {
        continue;
      }
      for (OccupancyClaim claim : existing) {
        if (claim == null) {
          continue;
        }
        BlockerRelation relation = BlockerClassifier.classify(request, resource, claim);
        if (relation == BlockerRelation.SELF) {
          continue;
        }
        if (!hardAuthority || relation == BlockerRelation.SAME_DIRECTION_FRONT) {
          continue;
        }
        blockers.add(claim);
      }
    }
    if (!blockers.isEmpty()) {
      Optional<String> hardBlockerReason = conflictReleaseHardBlockerReason(request, blockers);
      if (hardBlockerReason.isPresent()) {
        return traceDecision(
            "canEnterPreview:" + hardBlockerReason.get(),
            request,
            new OccupancyDecision(
                false,
                now,
                SignalAspect.STOP,
                List.copyOf(blockers),
                false,
                hardBlockerReason.get()));
      }
      OccupancyDecision resolved = tryResolveConflictDeadlockPreview(request, blockers, now);
      if (resolved != null) {
        return traceDecision("canEnterPreview:conflict-release", request, resolved);
      }
      return traceDecision(
          "canEnterPreview:blockers",
          request,
          new OccupancyDecision(false, now, SignalAspect.STOP, List.copyOf(blockers)));
    }

    boolean queueBlocked = false;
    for (OccupancyResource resource : request.resourceList()) {
      if (!request.intentFor(resource).hardAuthority() || !isQueueableConflict(resource)) {
        continue;
      }
      if (findClaim(claims.get(resource), request.trainName()) != null) {
        continue;
      }
      Optional<OccupancyDecision> directionBlocked =
          failClosedUnknownSingleConflictEntry(request, resource, now);
      if (directionBlocked.isPresent()) {
        return traceDecision("canEnterPreview:unknown-single", request, directionBlocked.get());
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
      return traceDecision(
          "canEnterPreview:queue-blocked",
          request,
          new OccupancyDecision(false, now, SignalAspect.STOP, List.copyOf(blockers)));
    }
    SignalAspect signal = signalPolicy.aspectForDelay(Duration.ZERO);
    return traceDecision(
        "canEnterPreview:allowed",
        request,
        new OccupancyDecision(true, now, signal, List.copyOf(blockers)));
  }

  /**
   * 获取占用：将申请列车写入资源占用与队列状态。
   *
   * <p>若不可进入则返回拒绝决策，并写入排队快照供诊断使用。
   *
   * <p>成功获取后会发布 {@link OccupancyAcquiredEvent}，通知订阅者重新评估信号。
   */
  @Override
  public synchronized OccupancyDecision acquire(OccupancyRequest request) {
    Objects.requireNonNull(request, "request");
    OccupancyDecision decision = canEnter(request);
    if (!decision.allowed()) {
      return decision;
    }
    Instant now = request.now();
    if (decision.conflictRelease()) {
      Optional<String> hardBlockerReason =
          conflictReleaseHardBlockerReason(request, decision.blockers());
      if (hardBlockerReason.isPresent()) {
        return new OccupancyDecision(
            false, now, SignalAspect.STOP, decision.blockers(), false, hardBlockerReason.get());
      }
    }
    Set<OccupancyResource> blockedResources =
        decision.conflictRelease()
            ? resolveBlockedResourcesForPartialAcquire(decision, request.trainName())
            : Set.of();
    List<OccupancyResource> acquiredResources = new ArrayList<>();
    for (OccupancyResource resource : request.resourceList()) {
      if (resource == null) {
        continue;
      }
      if (blockedResources.contains(resource)) {
        continue;
      }
      Duration headway = headwayRule.headwayFor(request.routeId(), resource);
      List<OccupancyClaim> existing = claims.computeIfAbsent(resource, unused -> new ArrayList<>());
      OccupancyClaim current = findClaim(existing, request.trainName());
      if (current == null
          && !request.intentFor(resource).hardAuthority()
          && hasOtherLogicalClaim(existing, request.trainName())) {
        continue;
      }
      if (current == null
          && !request.intentFor(resource).hardAuthority()
          && hasOtherQueueEntry(resource, request.trainName())) {
        continue;
      }
      Optional<CorridorDirection> direction = resolveCorridorDirection(request, resource);
      ClaimRole role = request.claimRoleFor(resource);
      if (current != null) {
        Duration nextHeadway =
            current.headway().compareTo(headway) >= 0 ? current.headway() : headway;
        Optional<CorridorDirection> nextDirection =
            direction.isPresent() ? direction : current.corridorDirection();
        existing.remove(current);
        existing.add(
            new OccupancyClaim(
                resource,
                current.trainName(),
                request.routeId(),
                current.acquiredAt(),
                nextHeadway,
                nextDirection,
                role));
        acquiredResources.add(resource);
        continue;
      }
      existing.add(
          new OccupancyClaim(
              resource, request.trainName(), request.routeId(), now, headway, direction, role));
      acquiredResources.add(resource);
    }
    if (!acquiredResources.isEmpty()) {
      removeFromQueuesForResources(request.trainName(), acquiredResources);
      version.incrementAndGet();
    }
    // 发布占用获取事件
    publishAcquiredEvent(request, acquiredResources, now);
    return decision;
  }

  /**
   * 查询某资源当前占用（只返回首个占用者）。
   *
   * <p>用于诊断，不保证公平队列顺序。
   */
  @Override
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

  /**
   * 获取全部占用快照。
   *
   * <p>用于诊断，不建议高频调用。
   */
  @Override
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

  /**
   * 获取排队快照。
   *
   * <p>用于诊断单线走廊方向与排队情况。
   */
  @Override
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

  /**
   * 仅刷新冲突队列中的排队位次，不真正获取占用。
   *
   * <p>用于停站/门控场景：列车还停在当前位置，但需要持续保留自己在前方冲突区的排队顺序，避免后车先抢到队头。
   */
  @Override
  public synchronized void touchQueues(OccupancyRequest request) {
    if (request == null) {
      return;
    }
    Instant now = request.now();
    purgeExpiredQueueEntries(now);
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
      version.incrementAndGet();
    }
  }

  /**
   * 从指定冲突队列中移除列车排队条目。
   *
   * <p>只清理 queue，不释放 claim。调用方必须先判断该条目确实是可丢弃的前瞻位次，避免破坏真实会车或道岔让行顺序。
   */
  @Override
  public synchronized int removeQueueEntries(String trainName, List<OccupancyResource> resources) {
    if (trainName == null || trainName.isBlank() || resources == null || resources.isEmpty()) {
      return 0;
    }
    int removed = 0;
    for (OccupancyResource resource : resources) {
      if (!isQueueableConflict(resource)) {
        continue;
      }
      ConflictQueue queue = queues.get(resource);
      if (queue == null || !queue.contains(trainName)) {
        continue;
      }
      queue.remove(trainName);
      removed++;
      version.incrementAndGet();
      if (queue.isEmpty()) {
        queues.remove(resource);
      }
    }
    return removed;
  }

  /**
   * 按列车名释放全部占用资源。
   *
   * <p>释放后会发布 {@link OccupancyReleasedEvent}，通知订阅者资源已可用。
   *
   * @return 实际释放的占用数量
   */
  @Override
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
        if (claim != null && TrainNameNormalizer.sameLogicalTrain(claim.trainName(), trainName)) {
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
      version.incrementAndGet();
      publishReleasedEvent(trainName, releasedResources, Instant.now());
    }
    return removed;
  }

  /**
   * 释放指定资源上的单个列车占用。
   *
   * <p>释放后会发布 {@link OccupancyReleasedEvent}，通知订阅者资源已可用。
   *
   * @return 是否存在并成功移除
   */
  @Override
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
          list.removeIf(
              claim ->
                  claim != null
                      && TrainNameNormalizer.sameLogicalTrain(claim.trainName(), expected));
      if (list.isEmpty()) {
        claims.remove(resource);
      }
      removeFromQueuesForResources(expected, List.of(resource));
      // 发布占用释放事件
      if (removed) {
        version.incrementAndGet();
        publishReleasedEvent(expected, List.of(resource), Instant.now());
      }
      return removed;
    }
    // 全量释放：先收集被驱逐的列车名，再逐一清理队列条目
    List<String> evictedTrains = new ArrayList<>();
    for (OccupancyClaim claim : list) {
      if (claim != null && claim.trainName() != null && !claim.trainName().isBlank()) {
        evictedTrains.add(claim.trainName());
      }
    }
    claims.remove(resource);
    for (String evicted : evictedTrains) {
      removeFromQueuesForResources(evicted, List.of(resource));
    }
    publishReleasedEvent("*", List.of(resource), Instant.now());
    version.incrementAndGet();
    return true;
  }

  /**
   * 优先级让行判定：当冲突队列中存在更高优先级列车时返回 true。
   *
   * <p>仅针对单线走廊与道岔冲突资源；同向单线不触发让行。
   */
  @Override
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

  private Optional<OccupancyDecision> failClosedUnknownSingleConflictEntry(
      OccupancyRequest request, OccupancyResource resource, Instant now) {
    if (request == null || resource == null || !isSingleCorridorConflict(resource)) {
      return Optional.empty();
    }
    if (hasClaimByTrain(resource, request.trainName())) {
      return Optional.empty();
    }
    if (resolveCorridorDirection(request, resource).isPresent()) {
      return Optional.empty();
    }
    return Optional.of(
        new OccupancyDecision(
            false,
            now != null ? now : request.now(),
            SignalAspect.STOP,
            List.of(),
            false,
            "single-conflict-direction-unknown"));
  }

  private boolean hasClaimByTrain(OccupancyResource resource, String trainName) {
    return findClaim(claims.get(resource), trainName) != null;
  }

  private Optional<String> conflictReleaseHardBlockerReason(
      OccupancyRequest request, List<OccupancyClaim> blockers) {
    if (request == null || request.purpose() != AuthorizationPurpose.CONFLICT_CLEARING) {
      return Optional.empty();
    }
    OccupancyClaim hardBlocker = firstHardBlocker(blockers, request.trainName());
    if (hardBlocker == null || hardBlocker.resource() == null) {
      return Optional.empty();
    }
    return Optional.of("conflict-release-hard-blocker:" + hardBlocker.resource());
  }

  private OccupancyClaim firstHardBlocker(List<OccupancyClaim> blockers, String trainName) {
    if (blockers == null || blockers.isEmpty()) {
      return null;
    }
    for (OccupancyClaim blocker : blockers) {
      if (blocker == null || blocker.resource() == null) {
        continue;
      }
      if (TrainNameNormalizer.sameLogicalTrain(blocker.trainName(), trainName)) {
        continue;
      }
      ResourceKind kind = blocker.resource().kind();
      if (kind == ResourceKind.NODE || kind == ResourceKind.EDGE) {
        return blocker;
      }
    }
    return null;
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
      if (direction != CorridorDirection.UNKNOWN && !queue.hasEntriesOutside(direction)) {
        return true;
      }
      return queue.isHeadAny(trainName);
    }
    CorridorDirection active = activeDirection.get();
    if (direction == CorridorDirection.UNKNOWN || direction != active) {
      return false;
    }
    if (!queue.hasEntriesOutside(active)) {
      return true;
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
      if (direction != CorridorDirection.UNKNOWN && !queue.hasEntriesOutside(direction)) {
        return true;
      }
      return queue.wouldBeHeadAny(trainName, direction, priority, entryOrder, now);
    }
    CorridorDirection active = activeDirection.get();
    if (direction == CorridorDirection.UNKNOWN || direction != active) {
      return false;
    }
    if (!queue.hasEntriesOutside(active)) {
      return true;
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
    targets.addAll(resolveConflictCandidates(request));
    return targets;
  }

  /**
   * 按入口距离解析冲突释放候选。
   *
   * <p>长单线 lookahead 可能同时覆盖多个 conflict。若只看最近的 primary conflict，当 blocker 所在列车排在后续 conflict
   * 队列中时会漏掉真实互锁。这里按 entryOrder 从近到远扫描所有候选，并以请求资源顺序补齐没有 entryOrder 的冲突资源，避免主冲突窗口切换导致稳定互卡。
   */
  private List<OccupancyResource> resolveConflictCandidates(OccupancyRequest request) {
    if (request == null) {
      return List.of();
    }
    Map<String, Integer> entryOrders = request.conflictEntryOrders();
    LinkedHashSet<OccupancyResource> candidates = new LinkedHashSet<>();
    if (entryOrders != null && !entryOrders.isEmpty()) {
      entryOrders.entrySet().stream()
          .filter(entry -> entry != null && entry.getKey() != null)
          .sorted(
              java.util.Comparator.comparingInt(
                  entry -> entry.getValue() != null ? entry.getValue() : Integer.MAX_VALUE))
          .forEach(
              entry -> {
                OccupancyResource conflict = OccupancyResource.forConflict(entry.getKey());
                if (isQueueableConflict(conflict)) {
                  candidates.add(conflict);
                }
              });
    }
    for (OccupancyResource resource : request.resourceList()) {
      if (isQueueableConflict(resource)) {
        candidates.add(resource);
      }
    }
    return List.copyOf(candidates);
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

  private boolean hasVerifiedConflictReleaseHint(
      OccupancyRequest request, OccupancyResource conflict) {
    if (request == null
        || conflict == null
        || request.purpose() != AuthorizationPurpose.CONFLICT_CLEARING) {
      return false;
    }
    ConflictReleaseHint hint = request.conflictReleaseHints().get(conflict.key());
    return hint != null && hint.verifiedFor(conflict.key());
  }

  /**
   * 计算冲突释放时不能写入的 CONFLICT blocker 资源。
   *
   * <p>冲突释放只能绕过抽象 CONFLICT claim，不能绕过真实 NODE/EDGE 硬占用。硬占用会在 canEnter/acquire 阶段以 {@code
   * conflict-release-hard-blocker:*} 拒绝。
   */
  private Set<OccupancyResource> resolveBlockedResourcesForPartialAcquire(
      OccupancyDecision decision, String trainName) {
    if (decision == null || decision.blockers().isEmpty()) {
      return Set.of();
    }
    Set<OccupancyResource> resources = new LinkedHashSet<>();
    for (OccupancyClaim blocker : decision.blockers()) {
      if (blocker == null || blocker.resource() == null) {
        continue;
      }
      if (TrainNameNormalizer.sameLogicalTrain(blocker.trainName(), trainName)) {
        continue;
      }
      if (blocker.resource().kind() != ResourceKind.CONFLICT) {
        continue;
      }
      resources.add(blocker.resource());
    }
    return Set.copyOf(resources);
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
      if (TrainNameNormalizer.sameLogicalTrain(claim.trainName(), trainName)) {
        continue;
      }
      if (!queue.contains(claim.trainName()) && !isDirectionalConflictClaim(claim)) {
        return false;
      }
    }
    return true;
  }

  private boolean isDirectionalConflictClaim(OccupancyClaim claim) {
    return claim != null
        && claim.resource() != null
        && claim.resource().kind() == ResourceKind.CONFLICT
        && claim.corridorDirection().isPresent();
  }

  /**
   * 判定当前阻塞是否包含“对向列车”。
   *
   * <p>仅在单线冲突资源上生效：冲突区放行的目标是解开对向会车死锁，不应用于同向跟驰场景。 若存在同向阻塞列车，说明请求侧前方仍有列车，不应优先放行。 对向方向无法判定时（例如 UNKNOWN
   * 或队列中缺少方向），按安全侧拒绝放行，避免把同向前后车误判为会车死锁。
   */
  private boolean hasOppositeDirectionBlockerInQueue(
      OccupancyRequest request,
      List<OccupancyClaim> blockers,
      OccupancyResource conflict,
      ConflictQueue queue) {
    if (!isSingleCorridorConflict(conflict)) {
      return true;
    }
    CorridorDirection requestDirection = queueDirectionFor(request, conflict);
    if (requestDirection == CorridorDirection.UNKNOWN) {
      return false;
    }
    boolean hasOppositeDirectionBlocker = false;
    for (OccupancyClaim blocker : blockers) {
      if (blocker == null || blocker.trainName() == null) {
        continue;
      }
      if (TrainNameNormalizer.sameLogicalTrain(blocker.trainName(), request.trainName())) {
        continue;
      }
      Optional<CorridorDirection> blockerDirection = queue.directionOf(blocker.trainName());
      if (blockerDirection.isEmpty()
          && blocker.resource() != null
          && blocker.resource().equals(conflict)) {
        blockerDirection = blocker.corridorDirection();
      }
      if (blockerDirection.isEmpty() || blockerDirection.get() == CorridorDirection.UNKNOWN) {
        return false;
      }
      if (blockerDirection.get() == requestDirection) {
        return false;
      }
      if (isOppositeDirection(requestDirection, blockerDirection.get())) {
        hasOppositeDirectionBlocker = true;
      }
    }
    return hasOppositeDirectionBlocker;
  }

  private boolean isOppositeDirection(
      CorridorDirection firstDirection, CorridorDirection secondDirection) {
    return (firstDirection == CorridorDirection.A_TO_B
            && secondDirection == CorridorDirection.B_TO_A)
        || (firstDirection == CorridorDirection.B_TO_A
            && secondDirection == CorridorDirection.A_TO_B);
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
    if (request.purpose() != AuthorizationPurpose.CONFLICT_CLEARING) {
      return null;
    }
    if (firstHardBlocker(blockers, request.trainName()) != null) {
      return null;
    }
    if (!containsConflictBlocker(blockers)) {
      return null;
    }
    // 优先检查：列车是否持有任意冲突资源的放行锁（避免主冲突切换导致信号乒乓）
    OccupancyDecision heldLockDecision = tryResolveByHeldLock(request, blockers, now);
    if (heldLockDecision != null) {
      return heldLockDecision;
    }
    List<OccupancyResource> candidates = resolveConflictCandidates(request);
    if (candidates.isEmpty()) {
      return null;
    }
    for (OccupancyResource conflict : candidates) {
      ConflictQueue queue = queues.get(conflict);
      if (queue == null || queue.isEmpty()) {
        continue;
      }
      // 检查是否有其他车持有该冲突的锁
      DeadlockReleaseLock existingLock = deadlockReleaseLocks.get(conflict.key());
      if (existingLock != null && !existingLock.isExpired(now)) {
        if (!existingLock.matches(request.trainName())) {
          // 其他车持有锁，当前车必须等待
          continue;
        }
        // 当前车持有锁（已在 tryResolveByHeldLock 处理，理论上不会到这里）
      }
      // 单侧放行优先：必须是全局队头才能触发冲突放行
      if (!queue.isHeadAny(request.trainName())) {
        continue;
      }
      if (!hasVerifiedConflictReleaseHint(request, conflict)) {
        continue;
      }
      if (!areBlockersInQueue(blockers, queue, request.trainName())) {
        continue;
      }
      if (!hasOppositeDirectionBlockerInQueue(request, blockers, conflict, queue)) {
        continue;
      }
      // 放行并写入锁定
      Instant expiresAt = now.plus(DEADLOCK_RELEASE_LOCK_TTL);
      deadlockReleaseLocks.put(
          conflict.key(), new DeadlockReleaseLock(request.trainName(), expiresAt));
      SignalAspect signal = signalPolicy.aspectForDelay(Duration.ZERO);
      return new OccupancyDecision(true, now, signal, List.copyOf(blockers), true);
    }
    return null;
  }

  /**
   * 检查列车是否持有任意冲突资源的有效放行锁。
   *
   * <p>当列车请求多个冲突资源时，每次 tick 的"主冲突"可能因 blockers 变化而切换。 此方法在确定主冲突之前先扫描所有请求的冲突资源，
   * 若列车持有任意一个有效锁，则直接放行，避免因主冲突切换导致的信号乒乓。
   */
  private OccupancyDecision tryResolveByHeldLock(
      OccupancyRequest request, List<OccupancyClaim> blockers, Instant now) {
    if (request == null || request.purpose() != AuthorizationPurpose.CONFLICT_CLEARING) {
      return null;
    }
    if (firstHardBlocker(blockers, request.trainName()) != null
        || !containsConflictBlocker(blockers)) {
      return null;
    }
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
      if (!hasVerifiedConflictReleaseHint(request, OccupancyResource.forConflict(conflictKey))) {
        continue;
      }
      // 当前车持有该冲突的锁：直接放行。
      // 注意：不再检查 areBlockersInQueue，因为：
      // 1. 锁在创建时已验证过阻塞来源
      // 2. 锁有效期内阻塞列车可能已离开队列（如推进到下一站），但锁本身就是放行依据
      // 3. 过度校验会导致持锁列车因 blocker 变化而被拒绝，产生信号乒乓
      SignalAspect signal = signalPolicy.aspectForDelay(Duration.ZERO);
      return new OccupancyDecision(true, now, signal, List.copyOf(blockers), true);
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
    if (request.purpose() != AuthorizationPurpose.CONFLICT_CLEARING) {
      return null;
    }
    if (firstHardBlocker(blockers, request.trainName()) != null) {
      return null;
    }
    if (!containsConflictBlocker(blockers)) {
      return null;
    }
    List<OccupancyResource> candidates = resolveConflictCandidates(request);
    if (candidates.isEmpty()) {
      return null;
    }
    for (OccupancyResource conflict : candidates) {
      ConflictQueue queue = queues.get(conflict);
      if (queue == null || queue.isEmpty()) {
        continue;
      }
      // 检查放行锁（只读）
      DeadlockReleaseLock existingLock = deadlockReleaseLocks.get(conflict.key());
      if (existingLock != null && !existingLock.isExpired(now)) {
        if (!existingLock.matches(request.trainName())) {
          continue;
        }
        // 当前车持有锁：跳过队头判断，但仍需确保阻塞来源在同一队列中。
        if (!areBlockersInQueue(blockers, queue, request.trainName())) {
          continue;
        }
        if (!hasVerifiedConflictReleaseHint(request, conflict)) {
          continue;
        }
        SignalAspect signal = signalPolicy.aspectForDelay(Duration.ZERO);
        return new OccupancyDecision(true, now, signal, List.copyOf(blockers), true);
      }
      // 单侧放行优先：必须是全局队头才能触发冲突放行
      CorridorDirection direction = queueDirectionFor(request, conflict);
      if (!queue.wouldBeHeadAny(
          request.trainName(),
          direction,
          request.priority(),
          queueEntryOrderFor(request, conflict),
          now)) {
        continue;
      }
      if (!areBlockersInQueue(blockers, queue, request.trainName())) {
        continue;
      }
      if (!hasVerifiedConflictReleaseHint(request, conflict)) {
        continue;
      }
      if (!hasOppositeDirectionBlockerInQueue(request, blockers, conflict, queue)) {
        continue;
      }
      SignalAspect signal = signalPolicy.aspectForDelay(Duration.ZERO);
      return new OccupancyDecision(true, now, signal, List.copyOf(blockers), true);
    }
    return null;
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
      version.incrementAndGet();
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
      int removed = queue.purgeExpired(now, QUEUE_ENTRY_TTL);
      if (removed > 0) {
        staleQueueCleanupCount.addAndGet(removed);
        version.incrementAndGet();
      }
      if (queue.isEmpty()) {
        iterator.remove();
      }
    }
  }

  private OccupancyDecision traceDecision(
      String reason, OccupancyRequest request, OccupancyDecision decision) {
    SignalComputationTrace.emit(
        SignalComputationTrace.builder(
                request != null ? request.trainName() : "-",
                request != null ? request.trainName() : "-",
                SignalComputationTrace.Source.OCCUPANCY,
                decision != null ? decision.signal() : SignalAspect.STOP)
            .primaryReason(reason)
            .field("occupancyVersion", version())
            .field("staleQueueCleanupCount", staleQueueCleanupCount())
            .request(request)
            .decision(decision, request));
    return decision;
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
      if (claim != null && TrainNameNormalizer.sameLogicalTrain(claim.trainName(), trainName)) {
        return claim;
      }
    }
    return null;
  }

  private boolean hasOtherLogicalClaim(List<OccupancyClaim> list, String trainName) {
    if (list == null || list.isEmpty()) {
      return false;
    }
    for (OccupancyClaim claim : list) {
      if (claim == null) {
        continue;
      }
      if (!TrainNameNormalizer.sameLogicalTrain(claim.trainName(), trainName)) {
        return true;
      }
    }
    return false;
  }

  private boolean hasOtherQueueEntry(OccupancyResource resource, String trainName) {
    if (resource == null || !isQueueableConflict(resource)) {
      return false;
    }
    ConflictQueue queue = queues.get(resource);
    if (queue == null || queue.isEmpty()) {
      return false;
    }
    for (String queuedTrain : queue.allTrainNames()) {
      if (!TrainNameNormalizer.sameLogicalTrain(queuedTrain, trainName)) {
        return true;
      }
    }
    return false;
  }

  // ========== 事件发布 ==========

  /**
   * 发布占用获取事件。
   *
   * <p>收集受影响的列车（等待这些资源的列车），通知它们重新评估信号。
   */
  private void publishAcquiredEvent(
      OccupancyRequest request, List<OccupancyResource> acquiredResources, Instant now) {
    if (eventBus == null) {
      return;
    }
    List<OccupancyResource> resources =
        acquiredResources == null ? List.of() : List.copyOf(acquiredResources);
    if (resources.isEmpty()) {
      return;
    }
    List<String> affectedTrains = collectAffectedTrains(resources, request.trainName());
    OccupancyAcquiredEvent event =
        new OccupancyAcquiredEvent(now, request.trainName(), resources, affectedTrains);
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
    // 排除自己（大小写不敏感）
    if (excludeTrain != null) {
      affected.removeIf(name -> TrainNameNormalizer.sameLogicalTrain(name, excludeTrain));
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
      OccupancyQueueEntry existing = detachEntry(key);
      int stableEntryOrder = resolveStableEntryOrder(existing, entryOrder);
      entryOrders.put(key, stableEntryOrder);
      LinkedHashMap<String, OccupancyQueueEntry> target = mapFor(direction);
      target.put(
          key,
          new OccupancyQueueEntry(
              existing != null ? existing.trainName() : trainName,
              direction,
              existing != null ? existing.firstSeen() : now,
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

    Optional<CorridorDirection> directionOf(String trainName) {
      if (trainName == null || trainName.isBlank()) {
        return Optional.empty();
      }
      String key = normalize(trainName);
      OccupancyQueueEntry entry = forward.get(key);
      if (entry == null) {
        entry = backward.get(key);
      }
      if (entry == null) {
        entry = neutral.get(key);
      }
      return entry == null ? Optional.empty() : Optional.ofNullable(entry.direction());
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
          .map(entry -> TrainNameNormalizer.sameLogicalTrain(entry.trainName(), trainName))
          .orElse(false);
    }

    boolean isHeadAny(String trainName) {
      if (trainName == null || trainName.isBlank()) {
        return false;
      }
      return headAny()
          .map(entry -> TrainNameNormalizer.sameLogicalTrain(entry.trainName(), trainName))
          .orElse(false);
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
          .map(entry -> TrainNameNormalizer.sameLogicalTrain(entry.trainName(), trainName))
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
      return head.map(entry -> TrainNameNormalizer.sameLogicalTrain(entry.trainName(), trainName))
          .orElse(false);
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

    /**
     * 计算稳定的冲突入口序号。
     *
     * <p>仅在列车当前仍处于队列中时沿用更小的 entryOrder；旧条目已移除/过期时会重新采用本次值。
     */
    private int resolveStableEntryOrder(OccupancyQueueEntry existing, int entryOrder) {
      if (existing == null) {
        return entryOrder;
      }
      return Math.min(existing.entryOrder(), entryOrder);
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

    /**
     * 是否存在指定方向以外的排队条目。
     *
     * <p>单线冲突本身只互斥对向列车；队列中全是同向列车时，不应把 conflict 队列当成额外闭塞块串行化。真正的同向追踪距离由 NODE/EDGE 硬占用负责。
     */
    boolean hasEntriesOutside(CorridorDirection direction) {
      if (direction == CorridorDirection.A_TO_B) {
        return !backward.isEmpty() || !neutral.isEmpty();
      }
      if (direction == CorridorDirection.B_TO_A) {
        return !forward.isEmpty() || !neutral.isEmpty();
      }
      return !forward.isEmpty() || !backward.isEmpty();
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

    int purgeExpired(Instant now, Duration ttl) {
      if (now == null || ttl == null || ttl.isNegative()) {
        return 0;
      }
      int removed = 0;
      removed += purgeExpired(forward, now, ttl);
      removed += purgeExpired(backward, now, ttl);
      removed += purgeExpired(neutral, now, ttl);
      pruneDetachedMetadata();
      return removed;
    }

    private int purgeExpired(
        LinkedHashMap<String, OccupancyQueueEntry> map, Instant now, Duration ttl) {
      int removed = 0;
      Iterator<Map.Entry<String, OccupancyQueueEntry>> iterator = map.entrySet().iterator();
      while (iterator.hasNext()) {
        Map.Entry<String, OccupancyQueueEntry> entry = iterator.next();
        OccupancyQueueEntry value = entry.getValue();
        if (value == null) {
          iterator.remove();
          removed++;
          continue;
        }
        if (value.lastSeen().plus(ttl).isBefore(now)) {
          iterator.remove();
          removed++;
        }
      }
      return removed;
    }

    /**
     * 从所有方向队列中摘除旧条目，并保留最早 firstSeen。
     *
     * <p>用于处理方向更新时的“中立队列 -> 定向队列”迁移，避免同一列车在多个方向桶中重复存在。
     */
    private OccupancyQueueEntry detachEntry(String key) {
      if (key == null || key.isBlank()) {
        return null;
      }
      OccupancyQueueEntry existing = removeEntry(forward, key);
      existing = pickOlder(existing, removeEntry(backward, key));
      existing = pickOlder(existing, removeEntry(neutral, key));
      return existing;
    }

    private static OccupancyQueueEntry removeEntry(
        LinkedHashMap<String, OccupancyQueueEntry> map, String key) {
      return map == null || key == null ? null : map.remove(key);
    }

    private static OccupancyQueueEntry pickOlder(
        OccupancyQueueEntry current, OccupancyQueueEntry candidate) {
      if (candidate == null) {
        return current;
      }
      if (current == null) {
        return candidate;
      }
      return candidate.firstSeen().isBefore(current.firstSeen()) ? candidate : current;
    }

    /**
     * 清理不再出现在任一方向桶中的元数据。
     *
     * <p>防止 TTL 回收后残留旧 priority/entryOrder，影响列车重新排队时的排序结果。
     */
    private void pruneDetachedMetadata() {
      Set<String> activeKeys = new LinkedHashSet<>();
      activeKeys.addAll(forward.keySet());
      activeKeys.addAll(backward.keySet());
      activeKeys.addAll(neutral.keySet());
      priorities.keySet().removeIf(key -> !activeKeys.contains(key));
      entryOrders.keySet().removeIf(key -> !activeKeys.contains(key));
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
      return TrainNameNormalizer.sameLogicalTrain(trainName, name);
    }
  }
}
